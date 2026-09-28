package rida.pour.les.pros.data.io

import rida.pour.les.pros.domain.HistoryFormat
import rida.pour.les.pros.domain.HistoryOrigin
import rida.pour.les.pros.domain.Rida
import rida.pour.les.pros.domain.RidaDates
import rida.pour.les.pros.domain.RidaLine
import rida.pour.les.pros.domain.RidaStatus
import rida.pour.les.pros.domain.RidaType
import rida.pour.les.pros.domain.Text
import rida.pour.les.pros.xlsx.ReadSheet
import java.time.LocalDate
import java.util.UUID

data class PlannedClient(
    val id: String,
    val code: String,
    val isSystem: Boolean,
    val aliases: List<String>,
)

data class ImportPlan(
    val clients: List<PlannedClient>,
    val lines: List<RidaLine>,
    val nextId: Long,
    val warnings: List<String>,
) {
    fun report(): String = buildString {
        append("${lines.size} ligne(s) dans ${clients.size} client(s). Prochain ID : #$nextId.")
        val counts = lines.groupingBy { it.clientId }.eachCount()
        clients.forEach { c -> append("\n- ${c.code} : ${counts[c.id] ?: 0} ligne(s)") }
        if (warnings.isNotEmpty()) {
            append("\n\n⚠️ ${warnings.size} point(s) d'attention :")
            warnings.take(50).forEach { append("\n- ").append(it) }
            if (warnings.size > 50) append("\n- … et ${warnings.size - 50} autre(s)")
        }
    }
}

/**
 * Transforme un export xlsx du classeur Google Sheets « RIDA » (REFERENTIEL + Modèle + un onglet
 * par client, colonnes A→L) en plan d'import. Logique pure, sans Android.
 */
object RidaWorkbookImporter {
    private val REFERENTIEL = Text.key("REFERENTIEL")
    private val MODELE = Text.key("Modèle")
    private val NON_IDENTIFIE = Text.key(Rida.NON_IDENTIFIE)
    private val MERGED = Regex("^Fusionn[ée]e dans\\s*#?(\\d+)", RegexOption.IGNORE_CASE)
    private val DELETED = Regex("^Supprim[ée]e", RegexOption.IGNORE_CASE)

    private enum class Col(vararg val headers: String) {
        DATE("Date"), INTERLOCUTEUR("Interlocuteur"), TYPE("Type"), SUJET("Sujet"), ACTION("Action"),
        STATUT("Statut"), ECHEANCE("Échéance", "Echeance"), REALISATION("Réalisation", "Realisation"),
        COMMENTAIRE("Commentaire"), HISTORIQUE("Historique"), RECUL("Recul"), ID("ID", "Identifiant"),
    }

    fun plan(sheets: List<ReadSheet>, today: LocalDate, defaultInterlocuteur: String): ImportPlan {
        val warnings = mutableListOf<String>()
        val ref = sheets.firstOrNull { Text.key(it.name) == REFERENTIEL }
        if (ref == null) warnings += "Onglet REFERENTIEL absent : les clients sont déduits des onglets."
        val (refClients, refAliases, counter) = ref?.let { readReferentiel(it) } ?: Triple(emptyList<String>(), emptyMap<String, List<String>>(), null as Long?)
        val exampleSujet = sheets.firstOrNull { Text.key(it.name) == MODELE }?.let { exampleSubject(it) }

        // Clients : ordre du référentiel, puis onglets non référencés.
        val clients = linkedMapOf<String, PlannedClient>()
        refClients.forEach { code ->
            val k = Text.key(code)
            if (k.isNotEmpty() && k !in clients) {
                clients[k] = PlannedClient(UUID.randomUUID().toString(), code, false, refAliases[k].orEmpty())
            }
        }
        val dataSheets = sheets.filter { Text.key(it.name) !in setOf(REFERENTIEL, MODELE) }
        for (s in dataSheets) {
            val k = Text.key(s.name)
            if (k.isEmpty() || k in clients) continue
            val system = k == NON_IDENTIFIE
            if (!system) warnings += "Onglet « ${s.name} » absent du REFERENTIEL : client créé."
            clients[k] = PlannedClient(UUID.randomUUID().toString(), if (system) Rida.NON_IDENTIFIE else s.name, system, emptyList())
        }

        // Lignes.
        val drafts = mutableListOf<Pair<Long?, RidaLine>>()
        for (s in dataSheets) {
            val client = clients[Text.key(s.name)] ?: continue
            drafts += readLines(s, client, today, defaultInterlocuteur, exampleSujet, warnings)
        }

        // ID : conservés si uniques, sinon réattribués après le maximum.
        val used = mutableSetOf<Long>()
        val maxId = drafts.mapNotNull { it.first }.maxOrNull() ?: 0
        var next = maxOf(maxId + 1, counter ?: 0)
        val lines = drafts.map { (id, line) ->
            val finalId = if (id != null && id > 0 && used.add(id)) {
                id
            } else {
                val n = next++
                used += n
                val code = clients.values.first { it.id == line.clientId }.code
                warnings += if (id == null) {
                    "$code « ${line.sujet.take(40)} » : ID absent, #$n attribué."
                } else {
                    "$code « ${line.sujet.take(40)} » : ID #$id en double, #$n attribué."
                }
                n
            }
            line.copy(id = finalId)
        }
        if (counter != null && counter < maxId + 1) {
            warnings += "Compteur du REFERENTIEL ($counter) inférieur au plus grand ID + 1 : corrigé."
        }
        return ImportPlan(clients.values.toList(), lines, next, warnings)
    }

    private fun readReferentiel(s: ReadSheet): Triple<List<String>, Map<String, List<String>>, Long?> {
        var clientCol = -1
        var aliasCol = -1
        var headerRow = -1
        loop@ for (r in 0 until minOf(5, s.rows.size)) {
            for ((c, v) in s.rows[r].withIndex()) {
                val k = Text.key(v)
                if (k == "CLIENT" || k == "CLIENTS") { clientCol = c; headerRow = r }
                if (k == "ALIAS" || k == "ALIASES") aliasCol = c
            }
            if (clientCol >= 0) break@loop
        }
        // Compteur : cellule à droite d'un libellé "ID suivant", sinon C1.
        var counter: Long? = null
        for (row in s.rows.take(5)) {
            val i = row.indexOfFirst { Text.key(it).contains("IDSUIVANT") }
            if (i >= 0) counter = row.getOrNull(i + 1)?.trim()?.toDoubleOrNull()?.toLong()
        }
        if (counter == null) counter = s.cell(0, 2).trim().toDoubleOrNull()?.toLong()

        val clients = mutableListOf<String>()
        val aliases = mutableMapOf<String, List<String>>()
        if (clientCol >= 0) {
            for (r in headerRow + 1 until s.rows.size) {
                val code = s.cell(r, clientCol).trim()
                if (code.isEmpty()) continue
                clients += code
                if (aliasCol >= 0) {
                    aliases[Text.key(code)] = s.cell(r, aliasCol).split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
        }
        return Triple(clients, aliases, counter)
    }

    private fun exampleSubject(modele: ReadSheet): String? {
        val cols = columns(modele) ?: return null
        return modele.cell(cols.first + 1, cols.second.getValue(Col.SUJET)).trim().ifEmpty { null }
    }

    /** (ligne d'en-tête, index des colonnes). */
    private fun columns(s: ReadSheet): Pair<Int, Map<Col, Int>>? {
        for (r in 0 until minOf(5, s.rows.size)) {
            val keys = s.rows[r].map { Text.key(it) }
            if (Text.key("Sujet") !in keys) continue
            val map = Col.entries.associateWith { col ->
                keys.indexOfFirst { k -> col.headers.any { Text.key(it) == k } }
            }
            val positional = Col.entries.associateWith { it.ordinal }
            return r to map.mapValues { (col, i) -> if (i >= 0) i else positional.getValue(col) }
        }
        return null
    }

    private fun readLines(
        s: ReadSheet,
        client: PlannedClient,
        today: LocalDate,
        defaultInterlocuteur: String,
        exampleSujet: String?,
        warnings: MutableList<String>,
    ): List<Pair<Long?, RidaLine>> {
        val (header, cols) = columns(s) ?: (0 to Col.entries.associateWith { it.ordinal })
        val result = mutableListOf<Pair<Long?, RidaLine>>()
        for (r in header + 1 until s.rows.size) {
            fun v(c: Col) = s.cell(r, cols.getValue(c)).trim()
            val sujet = v(Col.SUJET)
            val idRaw = v(Col.ID)
            if (sujet.isEmpty() && idRaw.isEmpty() && v(Col.ACTION).isEmpty()) continue
            val where = "${client.code} ligne ${r + 1}"
            if (exampleSujet != null && sujet == exampleSujet && idRaw.isEmpty()) {
                warnings += "$where : ligne d'exemple du Modèle ignorée."
                continue
            }
            val created = RidaDates.parse(v(Col.DATE)) ?: today.also {
                warnings += "$where : date de création illisible (« ${v(Col.DATE)} »), date d'import utilisée."
            }
            val type = RidaType.fromLabel(v(Col.TYPE)) ?: RidaType.INFORMATION.also {
                warnings += "$where : type « ${v(Col.TYPE)} » inconnu, INFORMATION retenu."
            }
            val statut = RidaStatus.fromLabel(v(Col.STATUT)) ?: RidaStatus.A_FAIRE.also {
                if (v(Col.STATUT).isNotEmpty()) warnings += "$where : statut « ${v(Col.STATUT)} » inconnu, A faire retenu."
            }
            val echeance = dateOrWarn(v(Col.ECHEANCE), "$where : échéance", warnings)
            val realisation = dateOrWarn(v(Col.REALISATION), "$where : réalisation", warnings)
            val merged = MERGED.find(sujet)?.groupValues?.get(1)?.toLongOrNull()
            val hidden = merged != null || DELETED.containsMatchIn(sujet)
            if (sujet.length > Rida.SUJET_MAX) warnings += "$where : Sujet de ${sujet.length} caractères (> ${Rida.SUJET_MAX}) conservé tel quel."
            val line = RidaLine(
                uuid = UUID.randomUUID().toString(),
                id = 0,
                clientId = client.id,
                createdDate = created,
                interlocuteur = v(Col.INTERLOCUTEUR).ifEmpty { defaultInterlocuteur },
                type = type,
                sujet = sujet.ifEmpty { "(sans sujet)" },
                action = v(Col.ACTION),
                statut = statut,
                echeance = echeance,
                realisation = realisation,
                commentaire = v(Col.COMMENTAIRE),
                history = HistoryFormat.parse(s.cell(r, cols.getValue(Col.HISTORIQUE)), created, HistoryOrigin.IMPORT),
                recul = v(Col.RECUL),
                hidden = hidden,
                hiddenReason = if (hidden) "import" else null,
                mergedInto = merged,
            )
            result += idRaw.toDoubleOrNull()?.toLong() to line
        }
        return result
    }

    private fun dateOrWarn(raw: String, label: String, warnings: MutableList<String>): LocalDate? {
        if (raw.isEmpty()) return null
        return RidaDates.parse(raw) ?: null.also { warnings += "$label illisible (« $raw »), laissée vide." }
    }
}
