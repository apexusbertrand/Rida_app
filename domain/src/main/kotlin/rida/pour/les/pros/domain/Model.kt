package rida.pour.les.pros.domain

import java.time.LocalDate

/** Type d'une ligne RIDA (colonne C). */
enum class RidaType(val label: String) {
    INFORMATION("INFORMATION"),
    DECISION("DECISION"),
    ACTION("ACTION");

    companion object {
        fun fromLabel(raw: String?): RidaType? {
            val key = Text.key(raw ?: return null)
            return entries.firstOrNull { Text.key(it.label) == key }
        }
    }
}

/** Statut d'une ligne RIDA (colonne F). */
enum class RidaStatus(val label: String) {
    A_FAIRE("A faire"),
    EN_COURS("En cours"),
    TERMINE("Terminé");

    companion object {
        /** Tolérant : accents, casse et espaces ignorés ("termine", "A FAIRE", "à faire"…). */
        fun fromLabel(raw: String?): RidaStatus? {
            val key = Text.key(raw ?: return null)
            return entries.firstOrNull { Text.key(it.label) == key }
        }
    }
}

/** Origine d'une entrée d'Historique. */
enum class HistoryOrigin { AGENT, UTILISATEUR, RECOPIE_AUTO, RECOPIE_DEMANDE, IMPORT, SYSTEME }

data class HistoryEntry(
    val date: LocalDate,
    val text: String,
    val origin: HistoryOrigin,
)

/** Un client = un « onglet » du RIDA. */
data class Client(
    val id: String,
    /** Nom d'onglet tel que dans le REFERENTIEL (ex. "DERET"). */
    val code: String,
    val isSystem: Boolean = false,
    val aliases: List<String> = emptyList(),
) {
    val isNonIdentifie: Boolean get() = isSystem && code == Rida.NON_IDENTIFIE
}

/** Une ligne du RIDA (colonnes A→L). */
data class RidaLine(
    val uuid: String,
    /** ID métier global (colonne L), affiché "#12". Immuable. */
    val id: Long,
    val clientId: String,
    /** Date de création (colonne A). Immuable. */
    val createdDate: LocalDate,
    val interlocuteur: String,
    val type: RidaType,
    val sujet: String,
    val action: String,
    val statut: RidaStatus,
    val echeance: LocalDate?,
    val realisation: LocalDate?,
    /** Réservé à l'utilisateur (saisie manuelle). */
    val commentaire: String,
    val history: List<HistoryEntry>,
    val recul: String,
    val hidden: Boolean = false,
    val hiddenReason: String? = null,
    val mergedInto: Long? = null,
    /** Sujet d'origine conservé quand la ligne est masquée (traçabilité). */
    val originalSujet: String? = null,
) {
    val historiqueText: String get() = HistoryFormat.join(history)
}

object Rida {
    const val NON_IDENTIFIE = "NonIdentifié"
    const val SUJET_MAX = 120
    const val ACTION_MAX = 120
    const val COMMENTAIRE_MAX = 500
    const val RECUL_MAX = 1000
    const val DEFAULT_ECHEANCE_DAYS = 15L

    /** Ordre historique des 12 colonnes du classeur (A→L). */
    val COLUMNS = listOf(
        "Date", "Interlocuteur", "Type", "Sujet", "Action", "Statut",
        "Échéance", "Réalisation", "Commentaire", "Historique", "Recul", "ID",
    )
}
