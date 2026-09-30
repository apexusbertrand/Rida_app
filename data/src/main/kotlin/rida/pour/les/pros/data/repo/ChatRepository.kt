package rida.pour.les.pros.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import rida.pour.les.pros.data.db.ChatMessageEntity
import rida.pour.les.pros.data.db.RidaDatabase
import javax.inject.Inject
import javax.inject.Singleton

enum class ChatRole { USER, AGENT, SYSTEM }

/** Origine d'un message : sert à l'icône et au libellé dans le fil. */
enum class ChatOrigin { CHAT, COMMANDE, DIGEST, RECOPIE, SYNTHESE, ERREUR, INFO }

data class MailDraft(val to: List<String>, val subject: String, val body: String)

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val origin: ChatOrigin,
    /** Texte principal (le « résumé » pour une réponse de l'agent). */
    val text: String,
    /** Détail complet (listes, synthèse…). */
    val detail: String,
    val createdAt: Long,
    val attachmentPath: String? = null,
    val mail: MailDraft? = null,
)

@Singleton
class ChatRepository @Inject constructor(db: RidaDatabase, private val clock: RidaClock) {
    private val dao = db.chatDao()

    fun observe(limit: Int = 300): Flow<List<ChatMessage>> = dao.observeLatest(limit).map { l -> l.map { it.toDomain() } }

    /** Derniers messages, du plus ancien au plus récent. */
    suspend fun recent(limit: Int): List<ChatMessage> = dao.latest(limit).map { it.toDomain() }.reversed()

    suspend fun post(
        role: ChatRole,
        origin: ChatOrigin,
        text: String,
        detail: String = "",
        attachmentPath: String? = null,
        mail: MailDraft? = null,
    ): ChatMessage {
        val entity = ChatMessageEntity(
            role = role.name, origin = origin.name, text = text, detail = detail, createdAt = clock.nowMillis(),
            attachmentPath = attachmentPath, mailTo = mail?.to?.joinToString(","), mailSubject = mail?.subject, mailBody = mail?.body,
        )
        return entity.copy(id = dao.insert(entity)).toDomain()
    }

    suspend fun clear() = dao.clear()

    private fun ChatMessageEntity.toDomain() = ChatMessage(
        id = id,
        role = runCatching { ChatRole.valueOf(role) }.getOrDefault(ChatRole.SYSTEM),
        origin = runCatching { ChatOrigin.valueOf(origin) }.getOrDefault(ChatOrigin.INFO),
        text = text,
        detail = detail,
        createdAt = createdAt,
        attachmentPath = attachmentPath,
        mail = if (mailSubject != null) {
            MailDraft(mailTo.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }, mailSubject, mailBody.orEmpty())
        } else {
            null
        },
    )
}
