package rida.pour.les.pros.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import rida.pour.les.pros.agent.llm.ToolSpec
import rida.pour.les.pros.data.repo.RidaException
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.domain.AgentPatch
import rida.pour.les.pros.domain.Aliases
import rida.pour.les.pros.domain.Client
import rida.pour.les.pros.domain.HistoryFormat
import rida.pour.les.pros.domain.LineDraft
import rida.pour.les.pros.domain.RecopyMode
import rida.pour.les.pros.domain.CommentRecopy
import rida.pour.les.pros.domain.Rida
import rida.pour.les.pros.domain.RidaDates
import rida.pour.les.pros.domain.RidaLine
import rida.pour.les.pros.domain.RidaStatus
import rida.pour.les.pros.domain.RidaType
import rida.pour.les.pros.domain.RidaValidationException
import rida.pour.les.pros.domain.SortRules
import rida.pour.les.pros.domain.Synthese
import rida.pour.les.pros.domain.SyntheseRequest
import rida.pour.les.pros.domain.Text
import java.time.LocalDate

/** Erreur d'appel d'outil : renvoyée telle quelle à l'agent pour qu'il corrige. */
class ToolError(message: String) : Exception(message)

/** Résultat final demandé par l'agent via l'outil repondre. */
data class FinalAnswer(
    val resume: String,
    val detail: String,
    val sendByMail: Boolean,
    val recipients: List<String>,
)

/** État d'une exécution d'agent (synthèse produite, lignes touchées…). */
class RunState {
    var synthese: SyntheseOutput? = null
    val touchedIds = linkedSetOf<Long>()
    var final: FinalAnswer? = null
}

data class SyntheseOutput(val text: String, val filePath: String?, val count: Int)

/** Production du fichier de synthèse (implémentée côté Android). */
interface SyntheseFileWriter {
    suspend fun write(rows: List<rida.pour.les.pros.domain.SyntheseRow>, today: LocalDate): String
}

/** Outils exposés à l'agent : le code garantit les invariants, pas le modèle. */
class RidaTools(
    private val repo: RidaRepository,
    private val fileWriter: SyntheseFileWriter,
) {
    val specs: List<ToolSpec> = listOf(
        spec("lister_clients", "Liste les clients du référentiel avec leurs alias et le nombre de lignes ouvertes.") {},
        spec("lire_client", "Lit les lignes d'un client, triées (non terminées par échéance, puis terminées). Les lignes masquées sont exclues sauf demande.") {
            str("client", "Nom du client (tel que dans le référentiel, alias accepté)", required = true)
            bool("inclure_terminees", "Inclure les lignes terminées (défaut true)")
            bool("inclure_masquees", "Inclure les lignes masquées (défaut false)")
        },
        spec("lire_ligne", "Lit une ligne complète (avec tout l'Historique) à partir de son ID global.") {
            int("id", "ID de la ligne", required = true)
        },
        spec("rechercher", "Recherche des lignes sur tous les clients (texte dans sujet, action, commentaire, historique, interlocuteur) avec filtres facultatifs.") {
            str("texte", "Mots à rechercher (tous doivent apparaître)")
            str("client", "Limiter à ce client")
            enumStr("statut", "Filtrer par statut", RidaStatus.entries.map { it.label })
            enumStr("type", "Filtrer par type", RidaType.entries.map { it.label })
            str("echeance_avant", "Échéance au plus tard le (JJ/MM/AAAA)")
            str("echeance_apres", "Échéance au plus tôt le (JJ/MM/AAAA)")
            bool("ouvertes_seulement", "Exclure les lignes terminées")
        },
        spec("creer_ligne", "Crée une nouvelle ligne. L'ID, la date de création, l'échéance par défaut (J+15 pour une ACTION) et la date de réalisation sont gérés par l'application.") {
            str("client", "Client du référentiel, ou NonIdentifié si le client n'est pas identifiable avec certitude", required = true)
            enumStr("type", "Type de la ligne", RidaType.entries.map { it.label }, required = true)
            str("sujet", "Titre, 120 caractères maximum", required = true)
            str("action", "Ce qui a été décidé / doit être fait / info clé, 120 caractères maximum", required = true)
            enumStr("statut", "Statut", RidaStatus.entries.map { it.label }, required = true)
            str("interlocuteur", "Personne source ou référente, si citée")
            str("echeance", "JJ/MM/AAAA, seulement si une échéance est donnée")
            str("realisation", "JJ/MM/AAAA, seulement si la ligne est créée déjà terminée à une date précise")
            str("resume_historique", "Résumé bref pour la première entrée d'Historique", required = true)
            str("recul", "Analyse PMO courte (facultatif)")
            str("commentaire_non_identifie", "UNIQUEMENT pour le client NonIdentifié : nom du client tel que compris")
        },
        spec("mettre_a_jour_ligne", "Met à jour une ligne existante. Seuls les champs fournis changent. Date de création, ID et Commentaire ne sont pas modifiables. Une entrée d'Historique datée du jour est ajoutée.") {
            int("id", "ID de la ligne", required = true)
            str("entree_historique", "Ce qui a été fait ou a changé (sans la date, ajoutée automatiquement)", required = true)
            enumStr("type", "Nouveau type", RidaType.entries.map { it.label })
            str("sujet", "Nouveau sujet, 120 caractères maximum")
            str("action", "Nouvelle action, 120 caractères maximum")
            enumStr("statut", "Nouveau statut", RidaStatus.entries.map { it.label })
            str("interlocuteur", "Nouvel interlocuteur")
            str("echeance", "Nouvelle échéance JJ/MM/AAAA")
            bool("effacer_echeance", "Supprimer l'échéance (impossible pour une ACTION)")
            str("realisation", "Date de réalisation JJ/MM/AAAA si différente d'aujourd'hui")
            str("recul", "Nouvelle analyse PMO")
        },
        spec("masquer_ligne", "Masque une ligne (suppression ou fusion) : rien n'est effacé, l'ID est conservé.") {
            int("id", "ID de la ligne à masquer", required = true)
            str("motif", "Raison de la suppression (facultatif)")
            int("fusionnee_dans", "ID de la ligne qui recueille le contenu, en cas de fusion")
        },
        spec("reclasser_ligne", "Déplace une ligne vers un autre client (typiquement depuis NonIdentifié). L'ID ne change pas.") {
            int("id", "ID de la ligne", required = true)
            str("client", "Client de destination", required = true)
        },
        spec("synthese", "Construit la synthèse des lignes non terminées en retard et/ou à échéance proche, tous clients ou filtrée, et prépare le fichier Excel.") {
            strArray("clients", "Clients à inclure (vide = tous)")
            bool("en_retard", "Inclure les lignes en retard (défaut true)")
            bool("a_echeance", "Inclure les lignes à échéance proche (défaut true)")
            int("horizon_jours", "Horizon des échéances proches en jours (défaut 7)")
        },
        spec("recopier_commentaires", "Recopie les commentaires saisis par l'utilisateur dans l'Historique (datés du jour) puis vide les commentaires.") {},
        spec("repondre", "Réponse finale à l'utilisateur. À appeler une seule fois, en dernier.") {
            str("resume", "Une phrase autonome, avec les ID concernés", required = true)
            str("detail", "Réponse complète pour les listes et analyses (puces « - »), vide pour une simple confirmation")
            bool("envoyer_par_mail", "Préparer l'envoi de la synthèse par mail (après l'outil synthese)")
            strArray("destinataires", "Adresses mail si l'utilisateur les précise")
        },
    )

    suspend fun execute(name: String, input: JsonObject, state: RunState): String = try {
        when (name) {
            "lister_clients" -> listerClients()
            "lire_client" -> lireClient(input)
            "lire_ligne" -> lineJson(line(input.long("id")), full = true).toString()
            "rechercher" -> rechercher(input)
            "creer_ligne" -> creer(input, state)
            "mettre_a_jour_ligne" -> mettreAJour(input, state)
            "masquer_ligne" -> masquer(input, state)
            "reclasser_ligne" -> reclasser(input, state)
            "synthese" -> synthese(input, state)
            "recopier_commentaires" -> recopier()
            "repondre" -> {
                state.final = FinalAnswer(
                    resume = input.string("resume")?.trim().orEmpty().ifEmpty { throw ToolError("resume est obligatoire") },
                    detail = input.string("detail")?.trim().orEmpty(),
                    sendByMail = input.bool("envoyer_par_mail") ?: false,
                    recipients = input.strings("destinataires"),
                )
                "ok"
            }
            else -> throw ToolError("Outil inconnu : $name")
        }
    } catch (e: RidaValidationException) {
        throw ToolError(e.errors.joinToString("\n") { "- ${it.field} : ${it.message}" })
    } catch (e: RidaException) {
        throw ToolError(e.message ?: "erreur")
    } catch (e: IllegalArgumentException) {
        throw ToolError(e.message ?: "paramètre invalide")
    } catch (e: IllegalStateException) {
        throw ToolError(e.message ?: "état invalide")
    }

    // ---------- Outils ----------

    private suspend fun listerClients(): String {
        val lines = repo.getAllLines()
        return buildJsonArray {
            repo.getClients().forEach { c ->
                val l = lines.filter { it.clientId == c.id && !it.hidden }
                add(buildJsonObject {
                    put("client", c.code)
                    if (c.aliases.isNotEmpty()) putJsonArray("alias") { c.aliases.forEach { add(JsonPrimitive(it)) } }
                    put("lignes_ouvertes", l.count { it.statut != RidaStatus.TERMINE })
                    put("lignes_total", l.size)
                    if (c.isSystem) put("systeme", true)
                })
            }
        }.toString()
    }

    private suspend fun lireClient(input: JsonObject): String {
        val client = resolveClient(input.string("client"))
        val withDone = input.bool("inclure_terminees") ?: true
        val withHidden = input.bool("inclure_masquees") ?: false
        val lines = SortRules.sort(repo.getAllLines().filter { it.clientId == client.id })
            .filter { (withHidden || !it.hidden) && (withDone || it.statut != RidaStatus.TERMINE) }
        return buildJsonObject {
            put("client", client.code)
            put("nombre", lines.size)
            put("lignes", JsonArray(lines.take(MAX_LIST).map { lineJson(it, full = false, clientCode = client.code) }))
            if (lines.size > MAX_LIST) put("tronque", "seules les $MAX_LIST premières lignes sont affichées")
        }.toString()
    }

    private suspend fun rechercher(input: JsonObject): String {
        val clients = repo.getClients()
        val byId = clients.associateBy { it.id }
        val client = input.string("client")?.takeIf { it.isNotBlank() }?.let { resolveClient(it) }
        val words = input.string("texte").orEmpty().split(Regex("\\s+")).map { Text.key(it) }.filter { it.isNotEmpty() }
        val statut = input.string("statut")?.let { RidaStatus.fromLabel(it) ?: throw ToolError("statut inconnu : $it") }
        val type = input.string("type")?.let { RidaType.fromLabel(it) ?: throw ToolError("type inconnu : $it") }
        val before = input.date("echeance_avant")
        val after = input.date("echeance_apres")
        val openOnly = input.bool("ouvertes_seulement") ?: false
        val found = repo.getAllLines().filter { l ->
            !l.hidden &&
                (client == null || l.clientId == client.id) &&
                (statut == null || l.statut == statut) &&
                (type == null || l.type == type) &&
                (!openOnly || l.statut != RidaStatus.TERMINE) &&
                (before == null || (l.echeance != null && !l.echeance!!.isAfter(before))) &&
                (after == null || (l.echeance != null && !l.echeance!!.isBefore(after))) &&
                (words.isEmpty() || Text.key(listOf(l.sujet, l.action, l.commentaire, l.historiqueText, l.interlocuteur, l.recul).joinToString(" ")).let { hay -> words.all { hay.contains(it) } })
        }
        val sorted = SortRules.sort(found)
        return buildJsonObject {
            put("nombre", sorted.size)
            put("lignes", JsonArray(sorted.take(MAX_LIST).map { lineJson(it, full = false, clientCode = byId[it.clientId]?.code) }))
        }.toString()
    }

    private suspend fun creer(input: JsonObject, state: RunState): String {
        val clients = repo.getClients()
        val client = resolveClient(input.string("client"), clients)
        val canonical = Aliases.canonicalMap(clients)
        fun norm(s: String?) = s?.let { Aliases.normalizeText(it, canonical) }
        val commentaire = input.string("commentaire_non_identifie")?.trim().orEmpty()
        if (client.isNonIdentifie && commentaire.isEmpty()) {
            throw ToolError("Pour le client NonIdentifié, renseigne commentaire_non_identifie avec le nom du client tel que compris.")
        }
        if (!client.isNonIdentifie && commentaire.isNotEmpty()) {
            throw ToolError("commentaire_non_identifie n'est autorisé que pour le client NonIdentifié ; le Commentaire est réservé à l'utilisateur.")
        }
        val line = repo.createLine(
            LineDraft(
                client = client,
                interlocuteur = input.string("interlocuteur"),
                type = input.type("type") ?: throw ToolError("type obligatoire"),
                sujet = norm(input.string("sujet")).orEmpty(),
                action = norm(input.string("action")).orEmpty(),
                statut = input.status("statut") ?: RidaStatus.A_FAIRE,
                echeance = input.date("echeance"),
                realisation = input.date("realisation"),
                commentaire = commentaire,
                historySummary = norm(input.string("resume_historique")).orEmpty(),
                recul = norm(input.string("recul")).orEmpty(),
                byUser = false,
            ),
        )
        state.touchedIds += line.id
        return buildJsonObject {
            put("resultat", "ligne créée")
            put("ligne", lineJson(line, full = true, clientCode = client.code))
        }.toString()
    }

    private suspend fun mettreAJour(input: JsonObject, state: RunState): String {
        val clients = repo.getClients()
        val canonical = Aliases.canonicalMap(clients)
        fun norm(s: String?) = s?.let { Aliases.normalizeText(it, canonical) }
        val id = input.long("id")
        val updated = repo.agentUpdate(
            id,
            AgentPatch(
                interlocuteur = input.string("interlocuteur"),
                type = input.type("type"),
                sujet = norm(input.string("sujet")),
                action = norm(input.string("action")),
                statut = input.status("statut"),
                echeance = input.date("echeance"),
                clearEcheance = input.bool("effacer_echeance") ?: false,
                realisation = input.date("realisation"),
                recul = norm(input.string("recul")),
            ),
            norm(input.string("entree_historique")).orEmpty(),
        )
        state.touchedIds += updated.id
        return buildJsonObject {
            put("resultat", "ligne mise à jour")
            put("ligne", lineJson(updated, full = false, clientCode = clients.firstOrNull { it.id == updated.clientId }?.code))
        }.toString()
    }

    private suspend fun masquer(input: JsonObject, state: RunState): String {
        val id = input.long("id")
        val merged = input.longOrNull("fusionnee_dans")
        val l = repo.hide(id, input.string("motif"), merged)
        state.touchedIds += l.id
        return buildJsonObject {
            put("resultat", if (merged != null) "ligne #$id fusionnée dans #$merged et masquée" else "ligne #$id masquée")
        }.toString()
    }

    private suspend fun reclasser(input: JsonObject, state: RunState): String {
        val target = resolveClient(input.string("client"))
        if (target.isNonIdentifie) throw ToolError("Impossible de reclasser vers NonIdentifié.")
        val l = repo.reclassify(input.long("id"), target.id)
        state.touchedIds += l.id
        return buildJsonObject { put("resultat", "ligne #${l.id} reclassée chez ${target.code}") }.toString()
    }

    private suspend fun synthese(input: JsonObject, state: RunState): String {
        val clients = repo.getClients()
        val codes = input.strings("clients").map { resolveClient(it, clients).code }.toSet()
        val today = repo.today()
        val request = SyntheseRequest(
            clientCodes = codes,
            includeRetard = input.bool("en_retard") ?: true,
            includeAEcheance = input.bool("a_echeance") ?: true,
            horizonDays = (input.longOrNull("horizon_jours") ?: 7L).coerceIn(1, 90),
        )
        val rows = Synthese.build(repo.getAllLines(), clients.associateBy { it.id }, request, today)
        val text = Synthese.text(rows, today)
        val path = fileWriter.write(rows, today)
        state.synthese = SyntheseOutput(text, path, rows.size)
        return buildJsonObject {
            put("nombre_de_lignes", rows.size)
            put("fichier_excel", "prêt")
            put("synthese", text)
        }.toString()
    }

    private suspend fun recopier(): String {
        val r = repo.recopyComments(RecopyMode.A_LA_DEMANDE)
        return CommentRecopy.message(r, RecopyMode.A_LA_DEMANDE).orEmpty()
    }

    // ---------- Aides ----------

    private suspend fun resolveClient(name: String?, clients: List<Client>? = null): Client {
        val all = clients ?: repo.getClients()
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) throw ToolError("client obligatoire")
        return Aliases.resolve(n, all) ?: throw ToolError(
            "Client « $n » absent du référentiel. Clients valides : ${all.joinToString(", ") { it.code }}. " +
                "Si le client n'est pas identifiable, utilise ${Rida.NON_IDENTIFIE} avec commentaire_non_identifie.",
        )
    }

    private suspend fun line(id: Long): RidaLine = repo.getLine(id) ?: throw ToolError("Ligne #$id introuvable.")

    private suspend fun lineJson(l: RidaLine, full: Boolean, clientCode: String? = null): JsonObject {
        val code = clientCode ?: repo.getClients().firstOrNull { it.id == l.clientId }?.code
        return buildJsonObject {
            put("id", l.id)
            put("client", code)
            put("date_creation", RidaDates.format(l.createdDate))
            put("interlocuteur", l.interlocuteur)
            put("type", l.type.label)
            put("sujet", l.sujet)
            put("action", l.action)
            put("statut", l.statut.label)
            put("echeance", RidaDates.format(l.echeance))
            put("realisation", RidaDates.format(l.realisation))
            if (l.commentaire.isNotBlank()) put("commentaire", l.commentaire)
            val history = if (full) l.history else l.history.takeLast(3)
            put("historique", HistoryFormat.join(history))
            if (l.recul.isNotBlank()) put("recul", l.recul)
            if (l.hidden) {
                put("masquee", true)
                l.mergedInto?.let { put("fusionnee_dans", it) }
            }
        }
    }

    private companion object {
        const val MAX_LIST = 150
    }
}

// ---------- Lecture des paramètres d'outil ----------

private fun JsonObject.prim(name: String): JsonPrimitive? = (this[name] as? JsonPrimitive)?.takeIf { it.contentOrNull != null }

internal fun JsonObject.string(name: String): String? = prim(name)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.bool(name: String): Boolean? = prim(name)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }

internal fun JsonObject.longOrNull(name: String): Long? = prim(name)?.let { it.longOrNull ?: it.content.trim().removePrefix("#").toDoubleOrNull()?.toLong() }

internal fun JsonObject.long(name: String): Long = longOrNull(name) ?: throw ToolError("$name obligatoire (nombre)")

internal fun JsonObject.strings(name: String): List<String> = when (val v = this[name]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
    is JsonPrimitive -> v.contentOrNull?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    else -> emptyList()
}

internal fun JsonObject.date(name: String): LocalDate? {
    val raw = string(name) ?: return null
    return RidaDates.parse(raw) ?: throw ToolError("$name : date illisible « $raw », format attendu JJ/MM/AAAA")
}

internal fun JsonObject.type(name: String): RidaType? =
    string(name)?.let { RidaType.fromLabel(it) ?: throw ToolError("$name inconnu « $it » (INFORMATION, DECISION ou ACTION)") }

internal fun JsonObject.status(name: String): RidaStatus? =
    string(name)?.let { RidaStatus.fromLabel(it) ?: throw ToolError("$name inconnu « $it » (A faire, En cours ou Terminé)") }

// ---------- Construction des schémas ----------

private class SchemaBuilder {
    val props = mutableMapOf<String, JsonElement>()
    val required = mutableListOf<String>()

    fun str(name: String, description: String, required: Boolean = false) = add(name, required) {
        put("type", "string"); put("description", description)
    }

    fun int(name: String, description: String, required: Boolean = false) = add(name, required) {
        put("type", "integer"); put("description", description)
    }

    fun bool(name: String, description: String, required: Boolean = false) = add(name, required) {
        put("type", "boolean"); put("description", description)
    }

    fun enumStr(name: String, description: String, values: List<String>, required: Boolean = false) = add(name, required) {
        put("type", "string"); put("description", description)
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }

    fun strArray(name: String, description: String, required: Boolean = false) = add(name, required) {
        put("type", "array"); put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    private fun add(name: String, isRequired: Boolean, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        props[name] = buildJsonObject(block)
        if (isRequired) required += name
    }
}

private fun spec(name: String, description: String, block: SchemaBuilder.() -> Unit): ToolSpec {
    val b = SchemaBuilder().apply(block)
    return ToolSpec(
        name,
        description,
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(b.props))
            if (b.required.isNotEmpty()) putJsonArray("required") { b.required.forEach { add(JsonPrimitive(it)) } }
        },
    )
}
