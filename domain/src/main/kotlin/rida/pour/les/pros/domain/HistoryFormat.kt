package rida.pour.les.pros.domain

/** Format de la colonne Historique : une entrée par ligne, "JJ/MM/AAAA : texte". */
object HistoryFormat {
    private val ENTRY = Regex("^\\s*(\\d{1,2}/\\d{1,2}/\\d{4})\\s*:\\s?(.*)$")

    fun line(entry: HistoryEntry): String = "${RidaDates.format(entry.date)} : ${entry.text}"

    fun join(entries: List<HistoryEntry>): String = entries.joinToString("\n") { line(it) }

    /**
     * Découpe un Historique texte en entrées. Les lignes sans date sont rattachées à l'entrée
     * précédente ; un texte sans aucune date devient une seule entrée datée [fallbackDate].
     */
    fun parse(raw: String?, fallbackDate: java.time.LocalDate, origin: HistoryOrigin): List<HistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val result = mutableListOf<HistoryEntry>()
        for (l in raw.replace("\r\n", "\n").split('\n')) {
            val m = ENTRY.find(l)
            val date = m?.let { RidaDates.parse(it.groupValues[1]) }
            if (m != null && date != null) {
                result += HistoryEntry(date, m.groupValues[2].trim(), origin)
            } else if (l.isNotBlank()) {
                if (result.isEmpty()) {
                    result += HistoryEntry(fallbackDate, l.trim(), origin)
                } else {
                    val last = result.removeAt(result.lastIndex)
                    result += last.copy(text = (last.text + "\n" + l.trim()).trim())
                }
            }
        }
        return result
    }
}
