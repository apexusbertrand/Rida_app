package rida.pour.les.pros.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rida.pour.les.pros.data.io.RidaWorkbookImporter
import rida.pour.les.pros.domain.Rida
import rida.pour.les.pros.domain.RidaStatus
import rida.pour.les.pros.domain.RidaType
import rida.pour.les.pros.xlsx.XlsxReader
import java.time.LocalDate

class ImporterTest {
    private val today = LocalDate.of(2026, 9, 28)

    private fun plan() = javaClass.classLoader!!.getResourceAsStream("rida_export_sample.xlsx").use {
        RidaWorkbookImporter.plan(XlsxReader.read(it), today, "Moi")
    }

    @Test fun clients_du_referentiel_puis_onglets_non_references() {
        val p = plan()
        assertEquals(listOf("DERET", "LOG'S", "CIM", "ACME", Rida.NON_IDENTIFIE, "HORSREF"), p.clients.map { it.code })
        assertTrue(p.clients.single { it.code == Rida.NON_IDENTIFIE }.isSystem)
        assertTrue(p.warnings.any { it.contains("HORSREF") })
    }

    @Test fun lignes_ids_et_compteur() {
        val p = plan()
        assertEquals(7, p.lines.size)
        assertEquals(listOf(12L, 13L, 45L, 46L, 20L, 30L, 31L), p.lines.map { it.id })
        assertEquals(47L, p.nextId)
        assertTrue(p.warnings.any { it.contains("ligne d'exemple") })
        assertTrue(p.warnings.any { it.contains("en double") })
    }

    @Test fun contenu_d_une_ligne_texte_riche_dates_historique() {
        val l = plan().lines.first { it.id == 12L }
        assertEquals("Relancer le devis", l.sujet)
        assertEquals(LocalDate.of(2026, 9, 1), l.createdDate)
        assertEquals(LocalDate.of(2026, 9, 16), l.echeance)
        assertNull(l.realisation)
        assertEquals(RidaStatus.EN_COURS, l.statut)
        assertEquals("note manuelle", l.commentaire)
        assertEquals(2, l.history.size)
        assertEquals("relance faite", l.history[1].text)
        assertEquals("Stéphanie", l.interlocuteur)
    }

    @Test fun lignes_masquees_et_valeurs_invalides() {
        val p = plan()
        val merged = p.lines.first { it.id == 13L }
        assertTrue(merged.hidden)
        assertEquals(12L, merged.mergedInto)
        val bad = p.lines.first { it.sujet == "Sans ID" }
        assertEquals(RidaType.INFORMATION, bad.type)
        assertEquals(RidaStatus.A_FAIRE, bad.statut)
        assertEquals(today, bad.createdDate)
        assertNull(bad.echeance)
        assertEquals(RidaStatus.TERMINE, p.lines.first { it.sujet == "Doublon d'ID" }.statut)
        assertEquals("Moi", p.lines.first { it.sujet == "Doublon d'ID" }.interlocuteur)
    }
}
