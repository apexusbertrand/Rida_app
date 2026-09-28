package rida.pour.les.pros.domain

/** Résolution des noms de clients et normalisation des graphies (ex. "Absys Cyborg" → "Absys-Cyborg"). */
object Aliases {

    /** Client correspondant à un nom (code, libellé ou alias), en ignorant casse, accents et ponctuation. */
    fun resolve(name: String, clients: List<Client>): Client? {
        val k = Text.key(name)
        if (k.isEmpty()) return null
        return clients.firstOrNull { c -> Text.key(c.code) == k || c.aliases.any { Text.key(it) == k } }
    }

    /**
     * Remplace dans un texte libre les graphies déclarées en alias par le nom canonique
     * (mot entier, casse et séparateurs indifférents). [canonical] : alias → nom canonique.
     */
    fun normalizeText(text: String, canonical: Map<String, String>): String {
        var result = text
        // Les alias les plus longs d'abord pour éviter les remplacements partiels.
        for ((alias, target) in canonical.entries.sortedByDescending { it.key.length }) {
            val tokens = alias.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val pattern = tokens.joinToString("[\\s\\-_'’]*") { Regex.escape(it) }
            val regex = Regex(
                "(?<![\\p{L}\\p{N}])$pattern(?![\\p{L}\\p{N}])",
                setOf(RegexOption.IGNORE_CASE),
            )
            result = regex.replace(result) { target }
        }
        return result
    }

    /** Table alias → nom canonique pour l'ensemble des clients. */
    fun canonicalMap(clients: List<Client>, displayName: (Client) -> String = { it.code }): Map<String, String> =
        clients.flatMap { c -> c.aliases.map { it to displayName(c) } }.toMap()
}
