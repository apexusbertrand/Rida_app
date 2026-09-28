package rida.pour.les.pros.domain

import java.text.Normalizer

object Text {
    /** Clé de comparaison : sans accents, majuscules, uniquement lettres et chiffres. */
    fun key(raw: String): String {
        val noAccents = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noAccents.uppercase().filter { it.isLetterOrDigit() }
    }

    fun ids(ids: List<Long>): String = ids.joinToString(", ") { "#$it" }
}
