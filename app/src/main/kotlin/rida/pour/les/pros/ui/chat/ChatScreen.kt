package rida.pour.les.pros.ui.chat

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rida.pour.les.pros.AppScope
import rida.pour.les.pros.agent.ChatService
import rida.pour.les.pros.data.repo.AppSettings
import rida.pour.les.pros.data.repo.ChatMessage
import rida.pour.les.pros.data.repo.ChatOrigin
import rida.pour.les.pros.data.repo.ChatRepository
import rida.pour.les.pros.data.repo.ChatRole
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.data.repo.SecretStore
import rida.pour.les.pros.data.repo.SettingsRepository
import rida.pour.les.pros.domain.Client
import rida.pour.les.pros.ui.common.openFile
import rida.pour.les.pros.ui.common.openMail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    chat: ChatRepository,
    repo: RidaRepository,
    secrets: SecretStore,
    settingsRepo: SettingsRepository,
    private val service: ChatService,
    @AppScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val contextClientId = MutableStateFlow(savedState.get<String>("clientId"))

    val contextClient: StateFlow<Client?> = combine(repo.observeClients(), contextClientId) { clients, id ->
        id?.let { i -> clients.firstOrNull { it.id == i } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<ChatMessage>> =
        chat.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val busy: StateFlow<Boolean> = service.busy
    val hasApiKey: StateFlow<Boolean> = secrets.hasApiKey
    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    var input by mutableStateOf("")

    fun send() {
        val t = input.trim()
        if (t.isEmpty()) return
        input = ""
        submit(t)
    }

    fun sendQuick(text: String) = submit(text)

    private fun submit(t: String) {
        val ctx = contextClientId.value
        // Portée applicative : la demande continue même si l'utilisateur change d'écran.
        appScope.launch { service.submit(t, ctx) }
    }

    fun clearContext() {
        contextClientId.value = null
    }
}

private val QUICK = listOf("Synthèse", "Échéances", "Recopie commentaires", "Aide")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenLine: (Long) -> Unit,
    onOpenClients: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val contextClient by vm.contextClient.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(msg: String?) {
        if (msg != null) scope.launch { snackbar.showSnackbar(msg) }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) vm.input = listOf(vm.input, spoken).filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Rida")
                        Text("Pilotage du RIDA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenClients) { Icon(Icons.Default.Groups, "Clients et lignes") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Paramètres") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!hasKey) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Ajoute ta clé API Claude pour que je comprenne tes messages. Synthèse, échéances et recopie marchent déjà sans.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = onOpenSettings) { Text("Ajouter") }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) item(key = "busy") { Thinking() }
                if (messages.isEmpty()) item(key = "welcome") { Welcome() }
                items(messages, key = { it.id }) { m ->
                    MessageBubble(
                        m,
                        onOpenLine = onOpenLine,
                        onMail = { draft -> notify(openMail(context, draft, settings.mailCc, m.attachmentPath)) },
                        onOpenFile = { path -> notify(openFile(context, path)) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QUICK.forEach { q -> AssistChip(onClick = { vm.sendQuick(q) }, label = { Text(q) }) }
            }
            contextClient?.let { c ->
                InputChip(
                    selected = true,
                    onClick = vm::clearContext,
                    label = { Text("Contexte : ${c.code}") },
                    trailingIcon = { Icon(Icons.Default.Close, "Retirer le contexte", Modifier.size(16.dp)) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    placeholder = { Text("Écris ou dicte une consigne…") },
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dicte ta consigne RIDA")
                    try {
                        speech.launch(intent)
                    } catch (_: Exception) {
                        notify("La dictée vocale n'est pas disponible sur ce téléphone.")
                    }
                }) { Icon(Icons.Default.Mic, "Dicter") }
                FilledIconButton(onClick = { vm.send() }, enabled = vm.input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Envoyer")
                }
            }
        }
    }
}

@Composable
private fun Thinking() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(4.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text("Rida réfléchit…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Welcome() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Bienvenue", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ce chat pilote tout ton RIDA. Écris ou dicte comme sur Telegram : « Chez DERET, Stéphanie envoie le devis vendredi », " +
                    "« clôture la #17 », « qu'est-ce qui est en retard ? », « synthèse par mail ». Tape « aide » pour la liste complète.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun originLabel(o: ChatOrigin): String = when (o) {
    ChatOrigin.DIGEST -> "⏰ Échéances"
    ChatOrigin.RECOPIE -> "🗒️ Recopie"
    ChatOrigin.SYNTHESE -> "📊 Synthèse"
    ChatOrigin.ERREUR -> "⚠️ Erreur"
    ChatOrigin.INFO -> "ℹ️ Info"
    else -> ""
}

private val TIME = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
private val ID_REF = Regex("#(\\d+)")

/** Texte où chaque « #12 » est cliquable et ouvre la fiche de la ligne. */
@Composable
private fun linkedText(text: String, onOpenLine: (Long) -> Unit): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var last = 0
        for (m in ID_REF.findAll(text)) {
            append(text.substring(last, m.range.first))
            val id = m.groupValues[1].toLong()
            withLink(
                LinkAnnotation.Clickable(
                    tag = "line-$id",
                    styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onOpenLine(id) },
                ),
            ) { append(m.value) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

@Composable
private fun MessageBubble(
    m: ChatMessage,
    onOpenLine: (Long) -> Unit,
    onMail: (rida.pour.les.pros.data.repo.MailDraft) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val isUser = m.role == ChatRole.USER
    val isSystem = m.role == ChatRole.SYSTEM
    val bg = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        m.origin == ChatOrigin.ERREUR -> MaterialTheme.colorScheme.errorContainer
        isSystem -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val label = originLabel(m.origin)
            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                linkedText(m.text, onOpenLine),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isUser) FontWeight.Normal else FontWeight.Medium,
            )
            if (m.detail.isNotBlank()) {
                Text(linkedText(m.detail, onOpenLine), style = MaterialTheme.typography.bodySmall)
            }
            if (m.mail != null || m.attachmentPath != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    m.mail?.let { draft ->
                        OutlinedButton(onClick = { onMail(draft) }) {
                            Icon(Icons.Default.Email, null, Modifier.size(16.dp))
                            Text(" Envoyer par mail")
                        }
                    }
                    m.attachmentPath?.let { path ->
                        OutlinedButton(onClick = { onOpenFile(path) }) {
                            Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp))
                            Text(" Excel")
                        }
                    }
                }
            }
            Text(
                TIME.format(Date(m.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.align(if (isUser) Alignment.End else Alignment.Start),
            )
        }
    }
}
