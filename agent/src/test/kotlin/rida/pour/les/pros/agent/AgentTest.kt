package rida.pour.les.pros.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import rida.pour.les.pros.agent.llm.Block
import rida.pour.les.pros.agent.llm.LlmProvider
import rida.pour.les.pros.agent.llm.LlmRequest
import rida.pour.les.pros.agent.llm.LlmResponse
import rida.pour.les.pros.data.db.RidaDatabase
import rida.pour.les.pros.data.repo.AppSettings
import rida.pour.les.pros.data.repo.RidaClock
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.data.repo.SettingsProvider
import rida.pour.les.pros.domain.HistoryFormat
import rida.pour.les.pros.domain.LineDraft
import rida.pour.les.pros.domain.Rida
import rida.pour.les.pros.domain.RidaStatus
import rida.pour.les.pros.domain.RidaType
import rida.pour.les.pros.domain.SyntheseRow
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/** Modèle factice : rejoue une suite de réponses (la dernière est répétée) et enregistre les requêtes. */
class ScriptedLlm(private val script: MutableList<(LlmRequest) -> LlmResponse>) : LlmProvider {
    val requests = mutableListOf<LlmRequest>()
    override suspend fun complete(apiKey: String, request: LlmRequest): LlmResponse {
        requests += request
        val step = if (script.size > 1) script.removeAt(0) else script.first()
        return step(request)
    }
}

private var counter = 0

fun toolCall(name: String, vararg args: Pair<String, Any>): LlmResponse = LlmResponse(
    listOf(
        Block.ToolUse(
            "t${counter++}", name,
            buildJsonObject {
                args.forEach { (k, v) ->
                    when (v) {
                        is String -> put(k, v)
                        is Number -> put(k, v)
                        is Boolean -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            },
        ),
    ),
    "tool_use",
)

private fun LlmRequest.lastToolResult(): String =
    (messages.last().content.filterIsInstance<Block.ToolResult>().single()).content

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AgentTest {
    private lateinit var db: RidaDatabase
    private lateinit var repo: RidaRepository
    private val today = LocalDate.of(2026, 10, 1)
    private val writer = object : SyntheseFileWriter {
        override suspend fun write(rows: List<SyntheseRow>, today: LocalDate) = "/tmp/synthese.xlsx"
    }

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RidaDatabase::class.java)
            .setQueryExecutor(Executors.newFixedThreadPool(2))
            .setTransactionExecutor(Executors.newSingleThreadExecutor())
            .build()
        repo = RidaRepository(
            db,
            object : SettingsProvider {
                override suspend fun current() = AppSettings(defaultInterlocuteur = "Moi")
            },
            object : RidaClock {
                override fun today(zone: ZoneId) = today
                override fun nowMillis() = 0L
            },
        )
        runBlocking {
            repo.ensureSystemClients()
            val c = repo.addClient("Absys-Cyborg")
            repo.addAlias(c.id, "Absisses")
            repo.addClient("DERET")
        }
    }

    @After fun tearDown() = db.close()

    private fun agent(llm: LlmProvider) = RidaAgent(llm, RidaTools(repo, writer))

    @Test fun creation_puis_reponse_avec_invariants_imposes_par_le_code() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(
                {
                    toolCall(
                        "creer_ligne", "client" to "deret", "type" to "ACTION", "sujet" to "Devis signé chez absisses",
                        "action" to "Stéphanie envoie le devis", "statut" to "A faire", "resume_historique" to "demande client",
                        "interlocuteur" to "Stéphanie",
                    )
                },
                { toolCall("repondre", "resume" to "Ajouté #1 chez DERET") },
            ),
        )
        val r = agent(llm).run("k", "m", emptyList(), "Date du jour : 01/10/2026\nMessage : note ça")
        assertEquals("Ajouté #1 chez DERET", r.answer.resume)
        val l = repo.getLine(1)!!
        assertEquals(today, l.createdDate)
        assertEquals(today.plusDays(15), l.echeance)
        assertEquals("Devis signé chez Absys-Cyborg", l.sujet)
        assertEquals("", l.commentaire)
        assertEquals(setOf(1L), r.touchedIds)
        assertTrue(llm.requests.first().tools.any { it.name == "repondre" })
    }

    @Test fun client_inconnu_puis_non_identifie_avec_commentaire() = runBlocking {
        val errors = mutableListOf<String>()
        val llm = ScriptedLlm(
            mutableListOf(
                {
                    toolCall(
                        "creer_ligne", "client" to "Dupond", "type" to "ACTION", "sujet" to "Rappeler", "action" to "Appel",
                        "statut" to "A faire", "resume_historique" to "x",
                    )
                },
                { req ->
                    errors += req.lastToolResult()
                    toolCall(
                        "creer_ligne", "client" to Rida.NON_IDENTIFIE, "type" to "ACTION", "sujet" to "Rappeler",
                        "action" to "Appel", "statut" to "A faire", "resume_historique" to "x",
                    )
                },
                { req ->
                    errors += req.lastToolResult()
                    toolCall(
                        "creer_ligne", "client" to Rida.NON_IDENTIFIE, "type" to "ACTION", "sujet" to "Rappeler", "action" to "Appel",
                        "statut" to "A faire", "resume_historique" to "x", "commentaire_non_identifie" to "Client mentionné : Dupond",
                    )
                },
                { toolCall("repondre", "resume" to "Ajouté #1 en NonIdentifié") },
            ),
        )
        agent(llm).run("k", "m", emptyList(), "x")
        assertTrue(errors[0], errors[0].contains("absent du référentiel"))
        assertTrue(errors[1], errors[1].contains("commentaire_non_identifie"))
        assertEquals("Client mentionné : Dupond", repo.getLine(1)!!.commentaire)
    }

    @Test fun mise_a_jour_cloture_et_commentaire_intouchable() = runBlocking {
        val deret = repo.getClients().first { it.code == "DERET" }
        repo.createLine(LineDraft(deret, null, RidaType.ACTION, "S", "A", RidaStatus.A_FAIRE, commentaire = "ma note", byUser = true))
        val llm = ScriptedLlm(
            mutableListOf(
                { toolCall("mettre_a_jour_ligne", "id" to 1, "statut" to "Terminé", "entree_historique" to "clôturé") },
                { toolCall("repondre", "resume" to "Clôturé #1") },
            ),
        )
        agent(llm).run("k", "m", emptyList(), "clôture la 1")
        val l = repo.getLine(1)!!
        assertEquals(RidaStatus.TERMINE, l.statut)
        assertEquals(today, l.realisation)
        assertEquals("ma note", l.commentaire)
        assertEquals("01/10/2026 : clôturé", HistoryFormat.line(l.history.last()))
    }

    @Test fun garde_fou_erreurs_repetees() = runBlocking {
        val llm = ScriptedLlm(mutableListOf({ toolCall("lire_ligne", "id" to 999) }))
        val r = agent(llm).run("k", "m", emptyList(), "x")
        assertTrue(r.answer.resume, r.answer.resume.contains("échoue"))
        assertEquals(3, llm.requests.size)
    }

    @Test fun synthese_puis_mail() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(
                { toolCall("synthese") },
                { toolCall("repondre", "resume" to "Synthèse prête", "envoyer_par_mail" to true) },
            ),
        )
        val r = agent(llm).run("k", "m", emptyList(), "synthèse des retards de Stéphanie par mail")
        assertNotNull(r.synthese)
        assertTrue(r.answer.sendByMail)
    }

    @Test fun reponse_texte_libre_sans_outil() = runBlocking {
        val llm = ScriptedLlm(mutableListOf({ LlmResponse(listOf(Block.Text("Bonjour !")), "end_turn") }))
        assertEquals("Bonjour !", agent(llm).run("k", "m", emptyList(), "salut").answer.resume)
    }
}
