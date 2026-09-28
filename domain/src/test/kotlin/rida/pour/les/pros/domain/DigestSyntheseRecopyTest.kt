package rida.pour.les.pros.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestSyntheseRecopyTest {
    private val t = F.TODAY

    @Test fun digest_jour_et_deux_jours_suivants() {
        val lines = listOf(
            F.line(1, echeance = t),
            F.line(2, client = F.CIM, echeance = t.plusDays(1)),
            F.line(3, echeance = t.plusDays(2)),
            F.line(4, echeance = t.plusDays(3)),
            F.line(5, statut = RidaStatus.TERMINE, echeance = t),
            F.line(6, echeance = t, hidden = true),
        )
        val d = Digest.build(lines, F.BY_ID, t)
        assertEquals(mapOf("DERET" to listOf("#1 Sujet 1")), d.dueToday)
        assertEquals(mapOf("CIM" to listOf("#2 Sujet 2"), "DERET" to listOf("#3 Sujet 3")), d.dueNext)
        assertTrue(Digest.messageToday(d).startsWith("📅 Échéances RIDA du 28/09/2026"))
        assertTrue(Digest.messageNext(d).startsWith("🔜 Échéances RIDA du 29/09/2026 au 30/09/2026"))
        val empty = Digest.build(emptyList(), F.BY_ID, t)
        assertTrue(Digest.messageToday(empty).endsWith("Aucune échéance."))
    }

    @Test fun synthese_retard_et_7_jours_avec_jours_de_retard() {
        val lines = listOf(
            F.line(1, echeance = t.minusDays(3)),
            F.line(2, echeance = t.plusDays(7)),
            F.line(3, echeance = t.plusDays(8)),
            F.line(4, statut = RidaStatus.TERMINE, echeance = t.minusDays(1)),
            F.line(5, client = F.CIM, echeance = t),
        )
        val rows = Synthese.build(lines, F.BY_ID, SyntheseRequest(), t)
        assertEquals(listOf(5L, 1L, 2L), rows.map { it.line.id })
        assertEquals(3, rows.first { it.line.id == 1L }.retardJours)
        val table = Synthese.table(rows)
        assertEquals("ID", table[0][0])
        assertEquals("3", table.first { it[0] == "#1" }[7])

        val onlyCim = Synthese.build(lines, F.BY_ID, SyntheseRequest(clientCodes = setOf("cim")), t)
        assertEquals(listOf(5L), onlyCim.map { it.line.id })
    }

    @Test fun recopie_planifiee_J_moins_1_et_a_la_demande_J() {
        val lines = listOf(F.line(8, commentaire = "  appel fait "), F.line(9))
        val planned = CommentRecopy.apply(lines, F.BY_ID, RecopyMode.PLANIFIEE, t)
        assertEquals(1, planned.total)
        val u = planned.updatedLines.single()
        assertEquals("", u.commentaire)
        assertEquals("27/09/2026 : appel fait", HistoryFormat.line(u.history.last()))
        assertEquals(2, u.history.size)

        val onDemand = CommentRecopy.apply(lines, F.BY_ID, RecopyMode.A_LA_DEMANDE, t)
        assertEquals("28/09/2026 : appel fait", HistoryFormat.line(onDemand.updatedLines.single().history.last()))
        assertTrue(CommentRecopy.message(onDemand, RecopyMode.A_LA_DEMANDE)!!.contains("DERET : 1 commentaire(s) recopié(s) (#8)"))

        val none = CommentRecopy.apply(listOf(F.line(9)), F.BY_ID, RecopyMode.PLANIFIEE, t)
        assertNull(CommentRecopy.message(none, RecopyMode.PLANIFIEE))
    }
}
