package rida.pour.les.pros.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class SyntheseSection(val label: String) {
    EN_RETARD("🔴 En retard"),
    A_ECHEANCE("🟠 Échéance ≤ 7 jours"),
}

data class SyntheseRow(
    val section: SyntheseSection,
    val clientCode: String,
    val line: RidaLine,
    /** Jours de retard (0 si non en retard). */
    val retardJours: Long,
)

data class SyntheseRequest(
    /** Codes clients à inclure ; vide = tous. */
    val clientCodes: Set<String> = emptySet(),
    val includeRetard: Boolean = true,
    val includeAEcheance: Boolean = true,
    val horizonDays: Long = 7,
)

/** Synthèse multi-client : lignes non terminées en retard et/ou à échéance ≤ N jours. */
object Synthese {
    val HEADERS = listOf("ID", "Client", "Sujet", "Action", "Type", "Interlocuteur", "Échéance", "Retard (j)", "Statut")

    fun build(
        lines: List<RidaLine>,
        clientsById: Map<String, Client>,
        request: SyntheseRequest,
        today: LocalDate,
    ): List<SyntheseRow> {
        val wanted = request.clientCodes.map { Text.key(it) }.toSet()
        val limit = today.plusDays(request.horizonDays)
        return lines.mapNotNull { line ->
            if (line.hidden || line.statut == RidaStatus.TERMINE) return@mapNotNull null
            val client = clientsById[line.clientId] ?: return@mapNotNull null
            if (wanted.isNotEmpty() && Text.key(client.code) !in wanted) return@mapNotNull null
            val e = line.echeance ?: return@mapNotNull null
            when {
                e.isBefore(today) && request.includeRetard ->
                    SyntheseRow(SyntheseSection.EN_RETARD, client.code, line, ChronoUnit.DAYS.between(e, today))
                !e.isBefore(today) && !e.isAfter(limit) && request.includeAEcheance ->
                    SyntheseRow(SyntheseSection.A_ECHEANCE, client.code, line, 0)
                else -> null
            }
        }.sortedWith(
            compareBy<SyntheseRow>({ it.clientCode }, { it.section.ordinal })
                .thenBy { it.line.echeance!!.toEpochDay() },
        )
    }

    /** Lignes du tableau (en-têtes en première ligne), pour l'export xlsx. */
    fun table(rows: List<SyntheseRow>): List<List<String>> = listOf(HEADERS) + rows.map { r ->
        listOf(
            "#${r.line.id}", r.clientCode, r.line.sujet, r.line.action, r.line.type.label,
            r.line.interlocuteur, RidaDates.format(r.line.echeance),
            if (r.section == SyntheseSection.EN_RETARD) r.retardJours.toString() else "",
            r.line.statut.label,
        )
    }

    /** Texte lisible dans le chat ou le corps du mail. */
    fun text(rows: List<SyntheseRow>, today: LocalDate): String = buildString {
        val retard = rows.count { it.section == SyntheseSection.EN_RETARD }
        val proche = rows.count { it.section == SyntheseSection.A_ECHEANCE }
        append("Synthèse RIDA du ${RidaDates.format(today)} : $retard en retard, $proche à échéance ≤ 7 jours.")
        if (rows.isEmpty()) return@buildString
        for ((client, clientRows) in rows.groupBy { it.clientCode }) {
            append("\n\n").append(client)
            for ((section, sRows) in clientRows.groupBy { it.section }) {
                append("\n").append(section.label)
                for (r in sRows) {
                    append("\n- #${r.line.id} ${r.line.sujet} — ${r.line.action} (${r.line.type.label}, ")
                    append("${r.line.interlocuteur}) — échéance ${RidaDates.format(r.line.echeance)}")
                    if (section == SyntheseSection.EN_RETARD) append(", ${r.retardJours} j de retard")
                    append(" — ${r.line.statut.label}")
                }
            }
        }
    }
}
