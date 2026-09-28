package rida.pour.les.pros.domain

import java.time.LocalDate

data class DigestResult(
    val today: LocalDate,
    /** Code client → libellés "#ID Sujet" (échéance = aujourd'hui). */
    val dueToday: Map<String, List<String>>,
    /** Code client → libellés "#ID Sujet" (échéance à J+1 ou J+2). */
    val dueNext: Map<String, List<String>>,
)

/** Digest quotidien : échéances du jour et des 2 jours suivants (lignes non terminées, non masquées). */
object Digest {
    const val NEXT_DAYS = 2L

    fun build(lines: List<RidaLine>, clientsById: Map<String, Client>, today: LocalDate): DigestResult {
        val dueToday = sortedMapOf<String, MutableList<String>>()
        val dueNext = sortedMapOf<String, MutableList<String>>()
        val limit = today.plusDays(NEXT_DAYS)
        for (line in lines) {
            if (line.hidden || line.statut == RidaStatus.TERMINE) continue
            val e = line.echeance ?: continue
            val code = clientsById[line.clientId]?.code ?: continue
            val label = "#${line.id} ${line.sujet}"
            when {
                e == today -> dueToday.getOrPut(code) { mutableListOf() } += label
                e.isAfter(today) && !e.isAfter(limit) -> dueNext.getOrPut(code) { mutableListOf() } += label
            }
        }
        return DigestResult(today, dueToday, dueNext)
    }

    fun messageToday(d: DigestResult): String =
        format("📅 Échéances RIDA du ${RidaDates.format(d.today)}", d.dueToday)

    fun messageNext(d: DigestResult): String = format(
        "🔜 Échéances RIDA du ${RidaDates.format(d.today.plusDays(1))} au ${RidaDates.format(d.today.plusDays(NEXT_DAYS))}",
        d.dueNext,
    )

    private fun format(heading: String, byClient: Map<String, List<String>>): String = buildString {
        append(heading)
        if (byClient.isEmpty()) {
            append("\n\nAucune échéance.")
        } else {
            for ((client, items) in byClient) {
                append("\n\n").append(client)
                items.forEach { append("\n- ").append(it) }
            }
        }
    }
}
