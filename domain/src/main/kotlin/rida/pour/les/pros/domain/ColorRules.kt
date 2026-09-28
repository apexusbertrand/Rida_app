package rida.pour.les.pros.domain

import java.time.LocalDate

/** Couleurs du RIDA (identiques au job n8n de 7h45). */
enum class CellColor(val hex: String) {
    VERT_PASTEL("#D9EAD3"),
    ORANGE("#FFA500"),
    VERT_VIF("#34A853"),
    ROUGE_VIF("#FF0000"),
    ROUGE_PASTEL("#F4CCCC"),
}

/** Couleur des colonnes Statut (F), Échéance (G) et Réalisation (H). */
data class LineColors(val statut: CellColor, val echeance: CellColor, val realisation: CellColor)

object ColorRules {
    const val HORIZON_DAYS = 4L

    fun compute(statut: RidaStatus, echeance: LocalDate?, realisation: LocalDate?, today: LocalDate): LineColors {
        var all = CellColor.VERT_PASTEL
        if (echeance != null) {
            val withinNext = !echeance.isBefore(today) && !echeance.isAfter(today.plusDays(HORIZON_DAYS))
            if (withinNext && statut == RidaStatus.A_FAIRE) all = CellColor.ORANGE
            if (withinNext && statut == RidaStatus.EN_COURS) all = CellColor.VERT_VIF
            if (echeance.isBefore(today) && statut != RidaStatus.TERMINE) all = CellColor.ROUGE_VIF
        }
        val statutColor = if (statut == RidaStatus.TERMINE && realisation == null) CellColor.ROUGE_PASTEL else all
        return LineColors(statutColor, all, all)
    }

    fun compute(line: RidaLine, today: LocalDate) = compute(line.statut, line.echeance, line.realisation, today)
}

object SortRules {
    /**
     * Non terminées par échéance croissante (retards d'abord, sans date en fin de groupe),
     * puis terminées par échéance décroissante (sans date en fin de groupe). Tri stable.
     */
    fun sort(lines: List<RidaLine>): List<RidaLine> {
        val (termine, ouvert) = lines.partition { it.statut == RidaStatus.TERMINE }
        val asc = Comparator<RidaLine> { a, b -> compareDates(a.echeance, b.echeance, ascending = true) }
        val desc = Comparator<RidaLine> { a, b -> compareDates(a.echeance, b.echeance, ascending = false) }
        return ouvert.sortedWith(asc) + termine.sortedWith(desc)
    }

    /** Dates absentes toujours en fin de groupe. */
    private fun compareDates(da: LocalDate?, db: LocalDate?, ascending: Boolean): Int = when {
        da == null && db == null -> 0
        da == null -> 1
        db == null -> -1
        ascending -> da.compareTo(db)
        else -> db.compareTo(da)
    }
}
