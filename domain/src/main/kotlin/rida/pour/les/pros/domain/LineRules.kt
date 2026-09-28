package rida.pour.les.pros.domain

import java.time.LocalDate

/** Erreur de validation, formulée pour être renvoyée telle quelle à l'agent ou affichée. */
data class ValidationError(val field: String, val message: String)

class RidaValidationException(val errors: List<ValidationError>) :
    IllegalArgumentException(errors.joinToString("; ") { "${it.field} : ${it.message}" })

/** Données fournies pour créer une ligne (par l'agent ou l'utilisateur). */
data class LineDraft(
    val client: Client,
    val interlocuteur: String?,
    val type: RidaType,
    val sujet: String,
    val action: String,
    val statut: RidaStatus,
    val echeance: LocalDate? = null,
    val realisation: LocalDate? = null,
    /** Accepté uniquement pour le client NonIdentifié (nom du client tel que compris). */
    val commentaire: String = "",
    /** Résumé pour la première entrée d'Historique ("création — …"). */
    val historySummary: String = "",
    val recul: String = "",
    /** Saisie manuelle dans l'app : le Commentaire (colonne de l'utilisateur) est alors autorisé. */
    val byUser: Boolean = false,
)

/** Modification d'une ligne par l'agent : null = inchangé. Date, ID et Commentaire ne sont pas modifiables. */
data class AgentPatch(
    val interlocuteur: String? = null,
    val type: RidaType? = null,
    val sujet: String? = null,
    val action: String? = null,
    val statut: RidaStatus? = null,
    val echeance: LocalDate? = null,
    val clearEcheance: Boolean = false,
    val realisation: LocalDate? = null,
    val recul: String? = null,
)

/** Édition manuelle depuis la fiche : l'utilisateur peut tout modifier sauf Date, ID et Historique. */
data class UserEdit(
    val interlocuteur: String,
    val type: RidaType,
    val sujet: String,
    val action: String,
    val statut: RidaStatus,
    val echeance: LocalDate?,
    val realisation: LocalDate?,
    val commentaire: String,
    val recul: String,
)

object LineRules {

    fun validateTexts(
        sujet: String,
        action: String,
        commentaire: String,
        recul: String,
    ): List<ValidationError> = buildList {
        if (sujet.isBlank()) add(ValidationError("sujet", "le Sujet est obligatoire"))
        if (sujet.length > Rida.SUJET_MAX) add(tooLong("sujet", "Sujet", sujet, Rida.SUJET_MAX))
        if (action.length > Rida.ACTION_MAX) add(tooLong("action", "Action", action, Rida.ACTION_MAX))
        if (commentaire.length > Rida.COMMENTAIRE_MAX) {
            add(tooLong("commentaire", "Commentaire", commentaire, Rida.COMMENTAIRE_MAX))
        }
        if (recul.length > Rida.RECUL_MAX) add(tooLong("recul", "Recul", recul, Rida.RECUL_MAX))
    }

    private fun tooLong(field: String, label: String, value: String, max: Int) = ValidationError(
        field,
        "$label trop long (${value.length} caractères, maximum $max) : reformuler en résumant, sans tronquer",
    )

    /**
     * Crée une ligne en imposant les invariants : date = aujourd'hui, ID fourni par la base,
     * Commentaire vide (sauf client NonIdentifié), échéance J+15 par défaut pour une ACTION,
     * Réalisation = aujourd'hui si créée Terminée, première entrée d'Historique datée.
     */
    fun create(
        draft: LineDraft,
        id: Long,
        uuid: String,
        today: LocalDate,
        defaultInterlocuteur: String,
    ): RidaLine {
        val commentaire = draft.commentaire.trim()
        val errors = validateTexts(draft.sujet.trim(), draft.action.trim(), commentaire, draft.recul.trim())
            .toMutableList()
        if (commentaire.isNotEmpty() && !draft.client.isNonIdentifie && !draft.byUser) {
            errors += ValidationError(
                "commentaire",
                "le Commentaire est réservé à l'utilisateur : laisser vide (sauf client NonIdentifié)",
            )
        }
        if (errors.isNotEmpty()) throw RidaValidationException(errors)

        val echeance = draft.echeance
            ?: if (draft.type == RidaType.ACTION) RidaDates.defaultEcheance(today) else null
        val realisation = draft.realisation
            ?: if (draft.statut == RidaStatus.TERMINE) today else null
        val summary = draft.historySummary.trim().ifEmpty { draft.sujet.trim() }
        return RidaLine(
            uuid = uuid,
            id = id,
            clientId = draft.client.id,
            createdDate = today,
            interlocuteur = draft.interlocuteur?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultInterlocuteur,
            type = draft.type,
            sujet = draft.sujet.trim(),
            action = draft.action.trim(),
            statut = draft.statut,
            echeance = echeance,
            realisation = realisation,
            commentaire = commentaire,
            history = listOf(
                HistoryEntry(today, "création — $summary", if (draft.byUser) HistoryOrigin.UTILISATEUR else HistoryOrigin.AGENT),
            ),
            recul = draft.recul.trim(),
        )
    }

    /** Mise à jour par l'agent : ajoute toujours une entrée d'Historique datée du jour. */
    fun agentUpdate(existing: RidaLine, patch: AgentPatch, historyText: String, today: LocalDate): RidaLine {
        val entry = historyText.trim()
        val errors = mutableListOf<ValidationError>()
        if (entry.isEmpty()) {
            errors += ValidationError("historique", "une entrée d'Historique décrivant le changement est obligatoire")
        }
        val type = patch.type ?: existing.type
        val sujet = patch.sujet?.trim() ?: existing.sujet
        val action = patch.action?.trim() ?: existing.action
        val recul = patch.recul?.trim() ?: existing.recul
        val statut = patch.statut ?: existing.statut
        errors += validateTexts(sujet, action, existing.commentaire, recul)
        var echeance = when {
            patch.clearEcheance -> null
            patch.echeance != null -> patch.echeance
            else -> existing.echeance
        }
        if (type == RidaType.ACTION && echeance == null) {
            echeance = RidaDates.defaultEcheance(existing.createdDate)
        }
        if (errors.isNotEmpty()) throw RidaValidationException(errors)

        val realisation = when {
            patch.realisation != null -> patch.realisation
            statut == RidaStatus.TERMINE && existing.realisation == null -> today
            else -> existing.realisation
        }
        return existing.copy(
            interlocuteur = patch.interlocuteur?.trim()?.ifEmpty { null } ?: existing.interlocuteur,
            type = type,
            sujet = sujet,
            action = action,
            statut = statut,
            echeance = echeance,
            realisation = realisation,
            recul = recul,
            history = existing.history + HistoryEntry(today, entry, HistoryOrigin.AGENT),
        )
    }

    /** Édition manuelle depuis la fiche : pas d'entrée d'Historique automatique (comme dans le tableur). */
    fun userEdit(existing: RidaLine, edit: UserEdit, today: LocalDate): RidaLine {
        val errors = validateTexts(edit.sujet.trim(), edit.action.trim(), edit.commentaire.trim(), edit.recul.trim())
            .toMutableList()
        if (edit.type == RidaType.ACTION && edit.echeance == null) {
            errors += ValidationError("echeance", "l'Échéance est obligatoire pour une ACTION")
        }
        if (errors.isNotEmpty()) throw RidaValidationException(errors)
        val realisation = edit.realisation
            ?: if (edit.statut == RidaStatus.TERMINE && existing.statut != RidaStatus.TERMINE) today else null
        return existing.copy(
            interlocuteur = edit.interlocuteur.trim(),
            type = edit.type,
            sujet = edit.sujet.trim(),
            action = edit.action.trim(),
            statut = edit.statut,
            echeance = edit.echeance,
            realisation = realisation,
            commentaire = edit.commentaire.trim(),
            recul = edit.recul.trim(),
        )
    }

    /**
     * Fusion ou suppression : jamais de suppression physique. Le Sujet est remplacé par le motif,
     * toutes les autres données sont conservées, la ligne est masquée.
     */
    fun hide(existing: RidaLine, reason: String?, mergedInto: Long?): RidaLine {
        require(!existing.hidden) { "la ligne #${existing.id} est déjà masquée" }
        require(mergedInto != existing.id) { "une ligne ne peut pas être fusionnée dans elle-même" }
        val label = if (mergedInto != null) {
            "Fusionnée dans #$mergedInto"
        } else {
            val r = reason?.trim().orEmpty()
            if (r.isEmpty()) "Supprimée à la demande" else "Supprimée à la demande : $r"
        }
        return existing.copy(
            sujet = shorten(label, Rida.SUJET_MAX),
            hidden = true,
            hiddenReason = reason?.trim()?.ifEmpty { null },
            mergedInto = mergedInto,
            originalSujet = existing.sujet,
        )
    }

    /** Reclassement (typiquement NonIdentifié → vrai client) : l'ID ne change pas. */
    fun reclassify(existing: RidaLine, target: Client): RidaLine {
        require(!target.isNonIdentifie || existing.clientId == target.id) {
            "reclassement vers NonIdentifié non autorisé"
        }
        return existing.copy(clientId = target.id)
    }

    /** Raccourcit sur une frontière de mot (utilisé uniquement pour les libellés générés par le code). */
    fun shorten(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.substring(0, max - 1)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > max / 2) cut.substring(0, lastSpace) else cut).trimEnd() + "…"
    }
}
