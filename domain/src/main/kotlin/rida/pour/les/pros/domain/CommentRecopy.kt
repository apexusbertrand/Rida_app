package rida.pour.les.pros.domain

import java.time.LocalDate

enum class RecopyMode { PLANIFIEE, A_LA_DEMANDE }

data class RecopyResult(
    val updatedLines: List<RidaLine>,
    /** Par code client : IDs recopiés. */
    val recopiedByClient: Map<String, List<Long>>,
) {
    val total: Int get() = recopiedByClient.values.sumOf { it.size }
}

/**
 * Recopie Commentaire → Historique puis vide le Commentaire.
 * Planifiée (7h45) : date de la veille (J-1). À la demande : date du jour (J).
 */
object CommentRecopy {

    fun entryDate(mode: RecopyMode, today: LocalDate): LocalDate =
        if (mode == RecopyMode.PLANIFIEE) today.minusDays(1) else today

    fun apply(
        lines: List<RidaLine>,
        clientsById: Map<String, Client>,
        mode: RecopyMode,
        today: LocalDate,
    ): RecopyResult {
        val date = entryDate(mode, today)
        val origin = if (mode == RecopyMode.PLANIFIEE) HistoryOrigin.RECOPIE_AUTO else HistoryOrigin.RECOPIE_DEMANDE
        val updated = mutableListOf<RidaLine>()
        val byClient = linkedMapOf<String, MutableList<Long>>()
        for (line in lines) {
            val c = line.commentaire.trim()
            if (c.isEmpty()) continue
            updated += line.copy(commentaire = "", history = line.history + HistoryEntry(date, c, origin))
            val code = clientsById[line.clientId]?.code ?: line.clientId
            byClient.getOrPut(code) { mutableListOf() } += line.id
        }
        return RecopyResult(updated, byClient)
    }

    /** Message de confirmation ; null si rien n'a été recopié et que la recopie est planifiée. */
    fun message(result: RecopyResult, mode: RecopyMode): String? {
        if (result.total == 0) {
            return if (mode == RecopyMode.A_LA_DEMANDE) "🗒️ Aucun commentaire à recopier." else null
        }
        val lines = result.recopiedByClient.toSortedMap().map { (client, ids) ->
            "- $client : ${ids.size} commentaire(s) recopié(s) (${Text.ids(ids)})"
        }
        return "🗒️ Recopie commentaires → historique : ${result.total} commentaire(s) recopié(s).\n\n" +
            lines.joinToString("\n")
    }
}
