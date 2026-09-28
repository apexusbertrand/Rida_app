package rida.pour.les.pros.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSortTest {
    private val t = F.TODAY
    private fun c(s: RidaStatus, e: java.time.LocalDate?, r: java.time.LocalDate? = null) =
        ColorRules.compute(s, e, r, t)

    @Test fun defaut_vert_pastel() {
        assertEquals(LineColors(CellColor.VERT_PASTEL, CellColor.VERT_PASTEL, CellColor.VERT_PASTEL), c(RidaStatus.A_FAIRE, t.plusDays(5)))
        assertEquals(CellColor.VERT_PASTEL, c(RidaStatus.A_FAIRE, null).statut)
    }

    @Test fun orange_si_a_faire_entre_J_et_J4() {
        assertEquals(CellColor.ORANGE, c(RidaStatus.A_FAIRE, t).echeance)
        assertEquals(CellColor.ORANGE, c(RidaStatus.A_FAIRE, t.plusDays(4)).statut)
        assertEquals(CellColor.VERT_PASTEL, c(RidaStatus.A_FAIRE, t.plusDays(5)).statut)
    }

    @Test fun vert_vif_si_en_cours_entre_J_et_J4() {
        assertEquals(CellColor.VERT_VIF, c(RidaStatus.EN_COURS, t.plusDays(2)).realisation)
    }

    @Test fun rouge_vif_si_depassee_et_non_terminee() {
        assertEquals(CellColor.ROUGE_VIF, c(RidaStatus.EN_COURS, t.minusDays(1)).statut)
        assertEquals(CellColor.VERT_PASTEL, c(RidaStatus.TERMINE, t.minusDays(1), t).statut)
    }

    @Test fun rouge_pastel_statut_seul_si_termine_sans_realisation() {
        val col = c(RidaStatus.TERMINE, t.minusDays(3), null)
        assertEquals(CellColor.ROUGE_PASTEL, col.statut)
        assertEquals(CellColor.VERT_PASTEL, col.echeance)
    }

    @Test fun tri_ouvertes_croissant_puis_terminees_decroissant_sans_date_en_fin() {
        val lines = listOf(
            F.line(1, statut = RidaStatus.TERMINE, echeance = t.minusDays(5)),
            F.line(2, echeance = null),
            F.line(3, echeance = t.plusDays(3)),
            F.line(4, statut = RidaStatus.TERMINE, echeance = null),
            F.line(5, echeance = t.minusDays(2)),
            F.line(6, statut = RidaStatus.TERMINE, echeance = t.minusDays(1)),
        )
        assertEquals(listOf(5L, 3L, 2L, 6L, 1L, 4L), SortRules.sort(lines).map { it.id })
    }
}
