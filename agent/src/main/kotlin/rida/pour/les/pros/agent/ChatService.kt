package rida.pour.les.pros.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rida.pour.les.pros.agent.llm.LlmMessage
import rida.pour.les.pros.agent.llm.LlmProvider
import rida.pour.les.pros.agent.llm.LlmRequest
import rida.pour.les.pros.data.repo.ChatOrigin
import rida.pour.les.pros.data.repo.ChatRepository
import rida.pour.les.pros.data.repo.ChatRole
import rida.pour.les.pros.data.repo.MailDraft
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.data.repo.SecretProvider
import rida.pour.les.pros.data.repo.SettingsRepository
import rida.pour.les.pros.domain.CommentRecopy
import rida.pour.les.pros.domain.Digest
import rida.pour.les.pros.domain.RecopyMode
import rida.pour.les.pros.domain.RidaDates
import rida.pour.les.pros.domain.Synthese
import rida.pour.les.pros.domain.SyntheseRequest
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class DailyOutcome(val title: String, val text: String)

/** Point d'entrée unique du chat : commandes directes, agent, synthèse, digest quotidien. */
@Singleton
class ChatService @Inject constructor(
    private val repo: RidaRepository,
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretProvider,
    private val llm: LlmProvider,
    files: SyntheseFileWriter,
) {
    private val mutex = Mutex()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val agent = RidaAgent(llm, RidaTools(repo, files))
    private val fileWriter = files

    /** Traite un message de l'utilisateur. [contextClientId] : client affiché quand le chat a été ouvert. */
    suspend fun submit(text: String, contextClientId: String? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        chat.post(ChatRole.USER, ChatOrigin.CHAT, t)
        mutex.withLock {
            _busy.value = true
            try {
                handle(t, contextClientId)
            } catch (e: Exception) {
                chat.post(
                    ChatRole.SYSTEM, ChatOrigin.ERREUR,
                    "Je n'ai pas pu traiter ta demande : ${e.message ?: e::class.simpleName}",
                    "Demande reçue : $t",
                )
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun handle(t: String, contextClientId: String?) {
        val clients = repo.getClients()
        val apiKey = secrets.apiKey()
        when (val cmd = Commands.parse(t, clients)) {
            Command.Aide -> chat.post(ChatRole.AGENT, ChatOrigin.COMMANDE, "Voici ce que je sais faire.", Commands.HELP)
            Command.Recopie -> {
                val r = repo.recopyComments(RecopyMode.A_LA_DEMANDE)
                chat.post(ChatRole.AGENT, ChatOrigin.RECOPIE, CommentRecopy.message(r, RecopyMode.A_LA_DEMANDE).orEmpty())
            }
            Command.Digest -> postDigest(ChatRole.AGENT)
            is Command.SyntheseCmd -> {
                if (cmd.complex && apiKey != null) {
                    runAgent(apiKey, t, contextClientId)
                } else {
                    postSynthese(cmd.clientCodes, cmd.byMail, cmd.recipients)
                }
            }
            null -> {
                if (apiKey == null) {
                    chat.post(
                        ChatRole.SYSTEM, ChatOrigin.INFO,
                        "Pour que je comprenne tes messages, ajoute ta clé API Claude dans Paramètres.",
                        "Sans clé, ces commandes fonctionnent déjà : synthèse, échéances, recopie commentaires, aide.",
                    )
                } else {
                    runAgent(apiKey, t, contextClientId)
                }
            }
        }
    }

    private suspend fun runAgent(apiKey: String, text: String, contextClientId: String?) {
        val s = settings.current()
        val today = repo.today()
        val clients = repo.getClients()
        val context = contextClientId?.let { id -> clients.firstOrNull { it.id == id }?.code }
        val day = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRENCH)
        val header = buildString {
            append("Date du jour : ${RidaDates.format(today)} ($day)\n")
            append("Référentiel clients : ${clients.joinToString(", ") { it.code }}\n")
            if (context != null) append("L'utilisateur consulte actuellement le client : $context\n")
            append("\nMessage : ").append(text)
        }
        val result = agent.run(apiKey, s.model, history(s.memoryExchanges), header)
        val syn = result.synthese
        val detail = result.answer.detail.ifEmpty { syn?.text?.substringAfter('\n', "")?.trim().orEmpty() }
        val mail = if (syn != null && result.answer.sendByMail) {
            MailDraft(result.answer.recipients.ifEmpty { s.mailTo }, subject(s.mailSubject, today), syn.text)
        } else {
            null
        }
        chat.post(
            ChatRole.AGENT,
            if (syn != null) ChatOrigin.SYNTHESE else ChatOrigin.CHAT,
            result.answer.resume,
            detail,
            attachmentPath = syn?.filePath,
            mail = mail ?: syn?.let { MailDraft(s.mailTo, subject(s.mailSubject, today), it.text) },
        )
    }

    /** Historique récent en alternance user / assistant (les messages système ne sont pas envoyés au modèle). */
    private suspend fun history(exchanges: Int): List<LlmMessage> {
        if (exchanges <= 0) return emptyList()
        val recent = chat.recent(exchanges * 2 + 1).dropLast(1) // sans le message qui vient d'être posté
        val raw = recent.mapNotNull { m ->
            when (m.role) {
                ChatRole.USER -> LlmMessage.Role.USER to m.text
                ChatRole.AGENT -> LlmMessage.Role.ASSISTANT to listOf(m.text, m.detail).filter { it.isNotBlank() }.joinToString("\n")
                ChatRole.SYSTEM -> null
            }
        }
        val merged = mutableListOf<Pair<LlmMessage.Role, String>>()
        for ((role, text) in raw) {
            if (merged.isNotEmpty() && merged.last().first == role) {
                merged[merged.lastIndex] = role to (merged.last().second + "\n\n" + text)
            } else {
                merged += role to text
            }
        }
        while (merged.isNotEmpty() && merged.first().first != LlmMessage.Role.USER) merged.removeAt(0)
        while (merged.isNotEmpty() && merged.last().first != LlmMessage.Role.ASSISTANT) merged.removeAt(merged.lastIndex)
        return merged.map { (role, text) -> LlmMessage(role, listOf(rida.pour.les.pros.agent.llm.Block.Text(text))) }
    }

    private suspend fun postSynthese(codes: List<String>, byMail: Boolean, recipients: List<String>) {
        val s = settings.current()
        val today = repo.today()
        val clients = repo.getClients()
        val rows = Synthese.build(repo.getAllLines(), clients.associateBy { it.id }, SyntheseRequest(clientCodes = codes.toSet()), today)
        val text = Synthese.text(rows, today)
        val path = fileWriter.write(rows, today)
        val to = recipients.ifEmpty { s.mailTo }
        val scope = if (codes.isEmpty()) "tous clients" else codes.joinToString(", ")
        chat.post(
            ChatRole.AGENT, ChatOrigin.SYNTHESE,
            text.substringBefore('\n') + " ($scope)" + if (byMail && to.isEmpty()) " — ajoute un destinataire dans Paramètres ou dans ton message." else "",
            text.substringAfter('\n', "").trim(),
            attachmentPath = path,
            mail = MailDraft(to, subject(s.mailSubject, today), text),
        )
    }

    private suspend fun postDigest(role: ChatRole): Pair<Int, Int> {
        val today = repo.today()
        val d = Digest.build(repo.getAllLines(), repo.getClients().associateBy { it.id }, today)
        chat.post(role, ChatOrigin.DIGEST, Digest.messageToday(d).substringBefore('\n'), Digest.messageToday(d).substringAfter('\n', "").trim())
        chat.post(role, ChatOrigin.DIGEST, Digest.messageNext(d).substringBefore('\n'), Digest.messageNext(d).substringAfter('\n', "").trim())
        return d.dueToday.values.sumOf { it.size } to d.dueNext.values.sumOf { it.size }
    }

    /**
     * Job quotidien (7h45 par défaut) : recopie Commentaire → Historique datée de la veille, puis digest.
     * Idempotent : ne tourne qu'une fois par jour, sauf [force].
     */
    suspend fun runDaily(force: Boolean = false): DailyOutcome? = mutex.withLock {
        val s = settings.current()
        val today = repo.today()
        if (!force && (!s.digestEnabled || !s.isDigestDay(today) || s.lastDailyRun == today.toString())) return@withLock null
        settings.setLastDailyRun(today)
        val r = repo.recopyComments(RecopyMode.PLANIFIEE)
        CommentRecopy.message(r, RecopyMode.PLANIFIEE)?.let { chat.post(ChatRole.SYSTEM, ChatOrigin.RECOPIE, it) }
        val (dueToday, dueNext) = postDigest(ChatRole.SYSTEM)
        val parts = buildList {
            add(if (dueToday == 0) "Aucune échéance aujourd'hui" else "$dueToday échéance(s) aujourd'hui")
            add("$dueNext dans les 2 prochains jours")
            if (r.total > 0) add("${r.total} commentaire(s) recopié(s)")
        }
        DailyOutcome("RIDA du ${RidaDates.format(today)}", parts.joinToString(" · "))
    }

    /** Vérifie une clé API par un appel minimal. Renvoie null si tout va bien, sinon le message d'erreur. */
    suspend fun testApiKey(apiKey: String, model: String): String? = try {
        llm.complete(apiKey, LlmRequest(model, "Réponds simplement OK.", emptyList(), listOf(LlmMessage.user("Test")), maxTokens = 16))
        null
    } catch (e: Exception) {
        e.message ?: "erreur inconnue"
    }

    private fun subject(template: String, today: java.time.LocalDate) = template.replace("{date}", RidaDates.format(today))
}
