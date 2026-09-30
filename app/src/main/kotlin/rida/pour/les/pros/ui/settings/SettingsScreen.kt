package rida.pour.les.pros.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rida.pour.les.pros.agent.ChatService
import rida.pour.les.pros.daily.DailyScheduler
import rida.pour.les.pros.data.io.ImportPlan
import rida.pour.les.pros.data.io.RidaWorkbookExporter
import rida.pour.les.pros.data.io.RidaWorkbookImporter
import rida.pour.les.pros.data.repo.AppSettings
import rida.pour.les.pros.data.repo.ChatRepository
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.data.repo.SecretStore
import rida.pour.les.pros.data.repo.SettingsRepository
import rida.pour.les.pros.domain.RidaDates
import rida.pour.les.pros.ui.common.XLSX_MIME
import rida.pour.les.pros.ui.common.userMessage
import rida.pour.les.pros.xlsx.XlsxReader
import rida.pour.les.pros.xlsx.XlsxWriter
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: RidaRepository,
    private val settingsRepo: SettingsRepository,
    private val secrets: SecretStore,
    private val chatService: ChatService,
    private val chat: ChatRepository,
) : ViewModel() {
    // Général
    var interlocuteur by mutableStateOf("")
    var zone by mutableStateOf("")

    // Agent
    var apiKeyInput by mutableStateOf("")
    var maskedKey by mutableStateOf<String?>(null); private set
    var model by mutableStateOf("")
    var memory by mutableStateOf("10")

    // Mail
    var mailTo by mutableStateOf("")
    var mailCc by mutableStateOf("")
    var mailSubject by mutableStateOf("")

    // Digest
    var digestEnabled by mutableStateOf(true)
    var digestTime by mutableStateOf("07:45")
    var digestDays by mutableStateOf(setOf(1, 2, 3, 4, 5))

    var pendingImport by mutableStateOf<ImportPlan?>(null); private set
    var busy by mutableStateOf(false); private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch {
            val s = settingsRepo.current()
            interlocuteur = s.defaultInterlocuteur
            zone = s.zoneId
            model = s.model
            memory = s.memoryExchanges.toString()
            mailTo = s.mailTo.joinToString(", ")
            mailCc = s.mailCc.joinToString(", ")
            mailSubject = s.mailSubject
            digestEnabled = s.digestEnabled
            digestTime = s.digestTime
            digestDays = s.digestDays
            maskedKey = withContext(Dispatchers.IO) { secrets.maskedApiKey() }
        }
    }

    fun saveApiKey() = launchSafe {
        val key = apiKeyInput.trim()
        require(key.isNotEmpty()) { "Colle ta clé API Claude avant d'enregistrer." }
        val error = chatService.testApiKey(key, model.ifBlank { AppSettings.DEFAULT_MODEL })
        withContext(Dispatchers.IO) { secrets.setApiKey(key) }
        apiKeyInput = ""
        maskedKey = secrets.maskedApiKey()
        _messages.emit(if (error == null) "Clé enregistrée et vérifiée ✔" else "Clé enregistrée, mais le test a échoué : $error")
    }

    fun testApiKey() = launchSafe {
        val key = withContext(Dispatchers.IO) { secrets.apiKey() } ?: error("Aucune clé enregistrée.")
        val error = chatService.testApiKey(key, model.ifBlank { AppSettings.DEFAULT_MODEL })
        _messages.emit(if (error == null) "La clé fonctionne ✔" else "Échec du test : $error")
    }

    fun deleteApiKey() = launchSafe {
        secrets.clearApiKey()
        maskedKey = null
        _messages.emit("Clé supprimée.")
    }

    fun saveAgent() = launchSafe {
        settingsRepo.updateAgent(model, memory.trim().toIntOrNull() ?: error("Mémoire : nombre attendu."))
        _messages.emit("Réglages de l'agent enregistrés.")
    }

    fun saveMail() = launchSafe {
        settingsRepo.updateMail(mailTo, mailCc, mailSubject)
        _messages.emit("Destinataires enregistrés.")
    }

    fun saveDigest() = launchSafe {
        settingsRepo.updateDigest(digestEnabled, digestTime, digestDays)
        DailyScheduler.schedule(context, settingsRepo.current())
        _messages.emit(if (digestEnabled) "Digest programmé à ${settingsRepo.current().digestTime}." else "Digest désactivé.")
    }

    fun runDigestNow() = launchSafe {
        chatService.runDaily(force = true)
        _messages.emit("Digest lancé : résultat dans le chat.")
    }

    fun saveGeneral() = launchSafe {
        settingsRepo.updateGeneral(interlocuteur, zone)
        _messages.emit("Paramètres enregistrés.")
    }

    fun toggleDay(day: Int) {
        digestDays = if (day in digestDays) digestDays - day else digestDays + day
    }

    fun clearChat() = launchSafe {
        chat.clear()
        _messages.emit("Historique du chat effacé (le RIDA n'est pas touché).")
    }

    fun prepareImport(uri: Uri) = launchSafe {
        val s = settingsRepo.current()
        val today = repo.today()
        pendingImport = withContext(Dispatchers.IO) {
            val sheets = context.contentResolver.openInputStream(uri)?.use { XlsxReader.read(it) }
                ?: error("Impossible d'ouvrir le fichier.")
            RidaWorkbookImporter.plan(sheets, today, s.defaultInterlocuteur)
        }
    }

    fun cancelImport() {
        pendingImport = null
    }

    fun confirmImport() = launchSafe {
        val plan = pendingImport ?: return@launchSafe
        pendingImport = null
        repo.replaceAll(plan)
        _messages.emit("Import terminé : ${plan.lines.size} ligne(s).")
    }

    fun export(uri: Uri) = launchSafe {
        val today = repo.today()
        val sheets = RidaWorkbookExporter.workbook(repo.getClients(), repo.getAllLines(), repo.peekNextId(), today)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { XlsxWriter.write(sheets, it) }
                ?: error("Impossible d'écrire le fichier.")
        }
        _messages.emit("Export terminé.")
    }

    suspend fun exportFileName(): String = "RIDA_export_${RidaDates.format(repo.today()).replace('/', '-')}.xlsx"

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                block()
            } catch (e: Exception) {
                _messages.emit(e.userMessage())
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var exportName by remember { mutableStateOf("RIDA_export.xlsx") }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        exportName = vm.exportFileName()
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::prepareImport)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        uri?.let(vm::export)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Agent Claude")
            Text(
                vm.maskedKey?.let { "Clé enregistrée : $it (chiffrée sur le téléphone)" } ?: "Aucune clé enregistrée.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                vm.apiKeyInput, { vm.apiKeyInput = it },
                label = { Text(if (vm.maskedKey == null) "Clé API Claude (sk-ant-…)" else "Remplacer la clé") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::saveApiKey, enabled = !vm.busy && vm.apiKeyInput.isNotBlank()) { Text("Enregistrer et tester") }
                if (vm.maskedKey != null) {
                    OutlinedButton(onClick = vm::testApiKey, enabled = !vm.busy) { Text("Tester la clé") }
                    TextButton(onClick = vm::deleteApiKey, enabled = !vm.busy) { Text("Supprimer") }
                }
            }
            Text(
                "La clé se crée sur console.anthropic.com. Tes messages et les lignes du RIDA utiles à la demande sont envoyés au service Claude pour être interprétés.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(vm.model, { vm.model = it }, label = { Text("Modèle (défaut ${AppSettings.DEFAULT_MODEL})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                vm.memory, { vm.memory = it },
                label = { Text("Mémoire du chat (nombre d'échanges)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = vm::saveAgent, enabled = !vm.busy) { Text("Enregistrer") }

            HorizontalDivider()
            Section("Envoi des extraits RIDA")
            OutlinedTextField(vm.mailTo, { vm.mailTo = it }, label = { Text("Destinataire(s) par défaut") }, supportingText = { Text("Séparés par des virgules") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.mailCc, { vm.mailCc = it }, label = { Text("Copie (facultatif)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.mailSubject, { vm.mailSubject = it }, label = { Text("Objet") }, supportingText = { Text("{date} est remplacé par la date du jour") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = vm::saveMail, enabled = !vm.busy) { Text("Enregistrer") }
            Text("Le mail s'ouvre prérempli dans ta messagerie, avec le fichier Excel joint : tu n'as plus qu'à appuyer sur Envoyer.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Section("Digest quotidien")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recopie des commentaires + échéances, avec notification", Modifier.weight(1f))
                Switch(checked = vm.digestEnabled, onCheckedChange = { vm.digestEnabled = it })
            }
            OutlinedTextField(vm.digestTime, { vm.digestTime = it }, label = { Text("Heure (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { d ->
                    FilterChip(selected = d in vm.digestDays, onClick = { vm.toggleDay(d) }, label = { Text(AppSettings.DAY_LABELS.getValue(d)) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::saveDigest, enabled = !vm.busy) { Text("Enregistrer") }
                TextButton(onClick = vm::runDigestNow, enabled = !vm.busy) { Text("Lancer maintenant") }
            }
            Text(
                "Le digest part dans les 15 minutes qui suivent l'heure choisie (Android ne garantit pas la minute exacte). Si le téléphone était éteint, il est rattrapé à l'ouverture de l'app.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Section("Général")
            OutlinedTextField(vm.interlocuteur, { vm.interlocuteur = it }, label = { Text("Interlocuteur par défaut (ton nom)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.zone, { vm.zone = it }, label = { Text("Fuseau horaire (ex. Europe/Paris)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = vm::saveGeneral, enabled = !vm.busy) { Text("Enregistrer") }

            HorizontalDivider()
            Section("Données")
            Text(
                "Import : dans Google Sheets, Fichier › Télécharger › Microsoft Excel (.xlsx), puis choisis le fichier. L'import remplace toutes les données de l'application.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { importLauncher.launch(arrayOf(XLSX_MIME)) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Importer un RIDA (.xlsx)")
            }
            OutlinedButton(onClick = { exportLauncher.launch(exportName) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Exporter tout le RIDA (.xlsx)")
            }
            TextButton(onClick = { confirmClear = true }) { Text("Effacer l'historique du chat") }
            Text("Données 100 % stockées sur ce téléphone. Pense à exporter régulièrement pour garder une sauvegarde.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(88.dp))
        }
    }

    vm.pendingImport?.let { plan ->
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text("Remplacer toutes les données ?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(plan.report(), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmImport) { Text("Remplacer") } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Annuler") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer l'historique du chat ?") },
            text = { Text("Les messages du chat seront supprimés. Les lignes du RIDA ne sont pas touchées.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearChat() }) { Text("Effacer") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}
