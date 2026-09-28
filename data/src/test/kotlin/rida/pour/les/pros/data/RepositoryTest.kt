package rida.pour.les.pros.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import rida.pour.les.pros.data.db.RidaDatabase
import rida.pour.les.pros.data.io.RidaWorkbookExporter
import rida.pour.les.pros.data.io.RidaWorkbookImporter
import rida.pour.les.pros.data.repo.AppSettings
import rida.pour.les.pros.data.repo.RidaClock
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.data.repo.SettingsProvider
import rida.pour.les.pros.domain.AgentPatch
import rida.pour.les.pros.domain.LineDraft
import rida.pour.les.pros.domain.RecopyMode
import rida.pour.les.pros.domain.Rida
import rida.pour.les.pros.domain.RidaStatus
import rida.pour.les.pros.domain.RidaType
import rida.pour.les.pros.domain.RidaValidationException
import rida.pour.les.pros.domain.UserEdit
import rida.pour.les.pros.xlsx.XlsxReader
import rida.pour.les.pros.xlsx.XlsxWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryTest {
    private lateinit var db: RidaDatabase
    private lateinit var repo: RidaRepository
    private val today = LocalDate.of(2026, 9, 28)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RidaDatabase::class.java)
            .setQueryExecutor(Executors.newFixedThreadPool(4))
            .setTransactionExecutor(Executors.newSingleThreadExecutor())
            .build()
        val settings = object : SettingsProvider {
            override suspend fun current() = AppSettings(defaultInterlocuteur = "Moi")
        }
        val clock = object : RidaClock {
            override fun today(zone: ZoneId) = today
            override fun nowMillis() = 0L
        }
        repo = RidaRepository(db, settings, clock)
        runBlocking { repo.ensureSystemClients() }
    }

    @After fun tearDown() = db.close()

    private fun draft(client: rida.pour.les.pros.domain.Client, sujet: String = "Sujet") =
        LineDraft(client, null, RidaType.ACTION, sujet, "Action", RidaStatus.A_FAIRE, historySummary = sujet)

    @Test fun ids_globaux_uniques_meme_en_parallele_et_jamais_reutilises() = runBlocking {
        val a = repo.addClient("DERET")
        val b = repo.addClient("CIM")
        val created = (1..20).map { i -> async { repo.createLine(draft(if (i % 2 == 0) a else b, "S$i")) } }.awaitAll()
        assertEquals((1L..20L).toList(), created.map { it.id }.sorted())
        repo.hide(20, "doublon", null)
        assertEquals(21L, repo.createLine(draft(a)).id)
    }

    @Test fun maj_agent_preserve_date_id_commentaire_et_empile_l_historique() = runBlocking {
        val c = repo.addClient("DERET")
        val l = repo.createLine(draft(c))
        repo.userEdit(l.id, UserEdit("Moi", RidaType.ACTION, "Sujet", "Action", RidaStatus.A_FAIRE, l.echeance, null, "ma note", ""))
        val u = repo.agentUpdate(l.id, AgentPatch(statut = RidaStatus.TERMINE), "clôturé")
        val reloaded = repo.getLine(l.id)!!
        assertEquals(u, reloaded)
        assertEquals("ma note", reloaded.commentaire)
        assertEquals(today, reloaded.realisation)
        assertEquals(2, reloaded.history.size)
        assertEquals(l.createdDate, reloaded.createdDate)
    }

    @Test fun commentaire_refuse_a_la_creation_hors_non_identifie() = runBlocking {
        val c = repo.addClient("DERET")
        try {
            repo.createLine(draft(c).copy(commentaire = "x"))
            fail("refus attendu")
        } catch (_: RidaValidationException) {
        }
        val nonId = repo.getClients().single { it.code == Rida.NON_IDENTIFIE }
        assertEquals("Client mentionné : Dupond", repo.createLine(draft(nonId).copy(commentaire = "Client mentionné : Dupond")).commentaire)
    }

    @Test fun client_en_double_refuse_y_compris_via_alias() = runBlocking {
        val c = repo.addClient("Absys-Cyborg")
        repo.addAlias(c.id, "Absys Cyborg alias")
        for (name in listOf("ABSYS CYBORG", "absys cyborg alias")) {
            try {
                repo.addClient(name)
                fail("refus attendu pour $name")
            } catch (_: Exception) {
            }
        }
    }

    @Test fun recopie_commentaires_planifiee() = runBlocking {
        val c = repo.addClient("DERET")
        val l = repo.createLine(draft(c))
        repo.userEdit(l.id, UserEdit("Moi", RidaType.ACTION, "Sujet", "Action", RidaStatus.A_FAIRE, l.echeance, null, "appel fait", ""))
        val r = repo.recopyComments(RecopyMode.PLANIFIEE)
        assertEquals(1, r.total)
        val reloaded = repo.getLine(l.id)!!
        assertEquals("", reloaded.commentaire)
        assertEquals(today.minusDays(1), reloaded.history.last().date)
    }

    @Test fun import_export_reimport_sans_perte() = runBlocking {
        val plan = javaClass.classLoader!!.getResourceAsStream("rida_export_sample.xlsx").use {
            RidaWorkbookImporter.plan(XlsxReader.read(it), today, "Moi")
        }
        repo.replaceAll(plan)
        val lines = repo.getAllLines()
        assertEquals(7, lines.size)
        assertEquals(47L, repo.peekNextId())

        val out = ByteArrayOutputStream()
        XlsxWriter.write(RidaWorkbookExporter.workbook(repo.getClients(), lines, repo.peekNextId(), today), out)
        val again = RidaWorkbookImporter.plan(XlsxReader.read(ByteArrayInputStream(out.toByteArray())), today, "Moi")

        assertEquals(47L, again.nextId)
        val before = lines.associateBy { it.id }
        assertEquals(before.keys, again.lines.map { it.id }.toSet())
        for (l in again.lines) {
            val b = before.getValue(l.id)
            assertEquals(b.sujet, l.sujet)
            assertEquals(b.createdDate, l.createdDate)
            assertEquals(b.echeance, l.echeance)
            assertEquals(b.statut, l.statut)
            assertEquals(b.commentaire, l.commentaire)
            assertEquals(b.historiqueText, l.historiqueText)
            assertEquals(b.hidden, l.hidden)
        }
        assertTrue(again.warnings.none { it.contains("en double") })
    }
}
