package rida.pour.les.pros.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import rida.pour.les.pros.data.db.ClientAliasEntity
import rida.pour.les.pros.data.db.ClientEntity
import rida.pour.les.pros.data.db.IdSequenceEntity
import rida.pour.les.pros.data.db.RidaDatabase
import rida.pour.les.pros.data.db.toDomain
import rida.pour.les.pros.data.db.toEntity
import rida.pour.les.pros.data.io.ImportPlan
import rida.pour.les.pros.domain.AgentPatch
import rida.pour.les.pros.domain.Client
import rida.pour.les.pros.domain.CommentRecopy
import rida.pour.les.pros.domain.LineDraft
import rida.pour.les.pros.domain.LineRules
import rida.pour.les.pros.domain.RecopyMode
import rida.pour.les.pros.domain.RecopyResult
import rida.pour.les.pros.domain.Rida
import rida.pour.les.pros.domain.RidaLine
import rida.pour.les.pros.domain.SortRules
import rida.pour.les.pros.domain.Text
import rida.pour.les.pros.domain.UserEdit
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Erreur métier lisible (affichée à l'utilisateur ou renvoyée à l'agent). */
class RidaException(message: String) : Exception(message)

@Singleton
class RidaRepository @Inject constructor(
    private val db: RidaDatabase,
    private val settings: SettingsProvider,
    private val clock: RidaClock,
) {
    private val clients = db.clientDao()
    private val lines = db.lineDao()
    private val sequences = db.sequenceDao()

    // ---------- Lecture ----------

    fun observeClients(): Flow<List<Client>> = clients.observeAll().map { l -> l.map { it.toDomain() } }

    fun observeAllLines(): Flow<List<RidaLine>> = lines.observeAll().map { l -> l.map { it.toDomain() } }

    /** Lignes d'un client, triées selon la règle RIDA. */
    fun observeClientLines(clientId: String): Flow<List<RidaLine>> =
        lines.observeByClient(clientId).map { l -> SortRules.sort(l.map { it.toDomain() }) }

    fun observeLine(id: Long): Flow<RidaLine?> = lines.observeByBusinessId(id).map { it?.toDomain() }

    suspend fun getLine(id: Long): RidaLine? = lines.getByBusinessId(id)?.toDomain()

    suspend fun getClients(): List<Client> = clients.getAll().map { it.toDomain() }

    suspend fun getAllLines(): List<RidaLine> = lines.getAll().map { it.toDomain() }

    suspend fun today(): LocalDate = clock.today(settings.current().zone)

    suspend fun peekNextId(): Long = sequences.get(SEQ) ?: ((lines.maxBusinessId() ?: 0) + 1)

    // ---------- Référentiel clients ----------

    suspend fun ensureSystemClients() = db.withTransaction {
        val key = Text.key(Rida.NON_IDENTIFIE)
        if (clients.getByKey(key) == null) {
            val now = clock.nowMillis()
            clients.insert(ClientEntity(UUID.randomUUID().toString(), Rida.NON_IDENTIFIE, key, true, Int.MAX_VALUE, now, now))
        }
    }

    suspend fun addClient(code: String): Client = db.withTransaction {
        val c = code.trim()
        val key = Text.key(c)
        if (key.isEmpty()) throw RidaException("Nom de client vide.")
        clients.getByKey(key)?.let { throw RidaException("Le client « ${it.code} » existe déjà.") }
        clients.aliasByKey(key)?.let { throw RidaException("« $c » est déjà un alias d'un autre client.") }
        val now = clock.nowMillis()
        val entity = ClientEntity(UUID.randomUUID().toString(), c, key, false, 0, now, now)
        clients.insert(entity)
        Client(entity.id, entity.code)
    }

    suspend fun renameClient(clientId: String, newCode: String) = db.withTransaction {
        val existing = clients.getById(clientId) ?: throw RidaException("Client introuvable.")
        if (existing.isSystem) throw RidaException("Le client système ne peut pas être renommé.")
        val c = newCode.trim()
        val key = Text.key(c)
        if (key.isEmpty()) throw RidaException("Nom de client vide.")
        clients.getByKey(key)?.takeIf { it.id != clientId }?.let { throw RidaException("Le client « ${it.code} » existe déjà.") }
        clients.update(existing.copy(code = c, codeKey = key, updatedAt = clock.nowMillis()))
    }

    suspend fun deleteClient(clientId: String) = db.withTransaction {
        val existing = clients.getById(clientId) ?: return@withTransaction
        if (existing.isSystem) throw RidaException("Le client système ne peut pas être supprimé.")
        if (clients.lineCount(clientId) > 0) {
            throw RidaException("Ce client a des lignes : reclasse-les ou masque-les avant de le supprimer.")
        }
        clients.delete(clientId)
    }

    suspend fun addAlias(clientId: String, alias: String) = db.withTransaction {
        val a = alias.trim()
        val key = Text.key(a)
        if (key.isEmpty()) throw RidaException("Alias vide.")
        clients.getByKey(key)?.let { throw RidaException("« $a » est déjà le nom du client ${it.code}.") }
        clients.aliasByKey(key)?.let { throw RidaException("L'alias « $a » existe déjà.") }
        clients.insertAlias(ClientAliasEntity(clientId = clientId, aliasRaw = a, aliasKey = key))
    }

    suspend fun removeAlias(alias: String) = db.withTransaction {
        clients.aliasByKey(Text.key(alias))?.let { clients.deleteAlias(it.id) }
    }

    // ---------- Écriture des lignes ----------

    /** Crée une ligne : l'ID est attribué ici, dans la même transaction que l'insertion. */
    suspend fun createLine(draft: LineDraft): RidaLine = db.withTransaction {
        val s = settings.current()
        val client = clients.getById(draft.client.id) ?: throw RidaException("Client introuvable.")
        if (client.deleted) throw RidaException("Client supprimé.")
        val id = allocateId()
        val line = LineRules.create(draft, id, UUID.randomUUID().toString(), clock.today(s.zone), s.defaultInterlocuteur)
        val now = clock.nowMillis()
        lines.insert(line.toEntity(now))
        lines.insertHistory(line.history.mapIndexed { i, h -> h.toEntity(line.uuid, i, now) })
        line
    }

    suspend fun agentUpdate(id: Long, patch: AgentPatch, historyText: String): RidaLine =
        modify(id) { existing, today -> LineRules.agentUpdate(existing, patch, historyText, today) }

    suspend fun userEdit(id: Long, edit: UserEdit): RidaLine =
        modify(id) { existing, today -> LineRules.userEdit(existing, edit, today) }

    suspend fun hide(id: Long, reason: String?, mergedInto: Long?): RidaLine = db.withTransaction {
        if (mergedInto != null) {
            val target = lines.getByBusinessId(mergedInto) ?: throw RidaException("Ligne #$mergedInto introuvable.")
            if (target.line.hidden) throw RidaException("La ligne #$mergedInto est elle-même masquée.")
        }
        modify(id) { existing, _ -> LineRules.hide(existing, reason, mergedInto) }
    }

    suspend fun reclassify(id: Long, clientId: String): RidaLine = db.withTransaction {
        val target = clients.getAll().firstOrNull { it.client.id == clientId }?.toDomain()
            ?: throw RidaException("Client introuvable.")
        modify(id) { existing, _ -> LineRules.reclassify(existing, target) }
    }

    /** Recopie Commentaire → Historique (J-1 si planifiée, J si à la demande). */
    suspend fun recopyComments(mode: RecopyMode): RecopyResult = db.withTransaction {
        val today = today()
        val all = getAllLines()
        val byId = getClients().associateBy { it.id }
        val result = CommentRecopy.apply(all, byId, mode, today)
        val previous = all.associateBy { it.uuid }
        result.updatedLines.forEach { save(it, previous.getValue(it.uuid).history.size) }
        result
    }

    /** Remplace toutes les données par celles d'un import. */
    suspend fun replaceAll(plan: ImportPlan) = db.withTransaction {
        val admin = db.adminDao()
        admin.clearHistory(); admin.clearLines(); admin.clearAliases(); admin.clearClients(); admin.clearSequences()
        val now = clock.nowMillis()
        plan.clients.forEachIndexed { i, c ->
            clients.insert(ClientEntity(c.id, c.code, Text.key(c.code), c.isSystem, if (c.isSystem) Int.MAX_VALUE else i, now, now))
            c.aliases.distinctBy { Text.key(it) }.forEach { a ->
                if (clients.aliasByKey(Text.key(a)) == null) {
                    clients.insertAlias(ClientAliasEntity(clientId = c.id, aliasRaw = a, aliasKey = Text.key(a)))
                }
            }
        }
        plan.lines.forEach { l ->
            lines.insert(l.toEntity(now))
            lines.insertHistory(l.history.mapIndexed { i, h -> h.toEntity(l.uuid, i, now) })
        }
        sequences.set(IdSequenceEntity(SEQ, plan.nextId))
        ensureSystemClients()
    }

    // ---------- Interne ----------

    private suspend fun modify(id: Long, change: (RidaLine, LocalDate) -> RidaLine): RidaLine = db.withTransaction {
        val existing = getLine(id) ?: throw RidaException("Ligne #$id introuvable.")
        val updated = change(existing, today())
        check(updated.id == existing.id && updated.createdDate == existing.createdDate && updated.uuid == existing.uuid) {
            "invariant violé : ID ou date de création modifiés"
        }
        check(updated.history.take(existing.history.size) == existing.history) { "invariant violé : Historique réécrit" }
        save(updated, existing.history.size)
        updated
    }

    private suspend fun save(line: RidaLine, previousHistorySize: Int) {
        val now = clock.nowMillis()
        lines.update(line.toEntity(now))
        val added = line.history.drop(previousHistorySize)
        if (added.isNotEmpty()) {
            lines.insertHistory(added.mapIndexed { i, h -> h.toEntity(line.uuid, previousHistorySize + i, now) })
        }
    }

    private suspend fun allocateId(): Long {
        val max = lines.maxBusinessId() ?: 0
        val next = maxOf(sequences.get(SEQ) ?: (max + 1), max + 1)
        sequences.set(IdSequenceEntity(SEQ, next + 1))
        return next
    }

    private companion object {
        const val SEQ = "rida_line"
    }
}
