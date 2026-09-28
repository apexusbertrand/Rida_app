package rida.pour.les.pros.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import rida.pour.les.pros.data.io.ImportPlan
import rida.pour.les.pros.data.io.RidaWorkbookExporter
import rida.pour.les.pros.data.io.RidaWorkbookImporter
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.data.repo.SettingsRepository
import rida.pour.les.pros.domain.RidaDates
import rida.pour.les.pros.ui.common.userMessage
import rida.pour.les.pros.xlsx.XlsxReader
import rida.pour.les.pros.xlsx.XlsxWriter
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: RidaRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    var interlocuteur by mutableStateOf("")
    var zone by mutableStateOf("")
    var pendingImport by mutableStateOf<ImportPlan?>(null); private set
    var busy by mutableStateOf(false); private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch {
            val s = settingsRepo.current()
            interlocuteur = s.defaultInterlocuteur
            zone = s.zoneId
        }
    }

    fun saveSettings() = launchSafe {
        settingsRepo.update(interlocuteur, zone)
        _messages.emit("Paramètres enregistrés.")
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

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var exportName by remember { mutableStateOf("RIDA_export.xlsx") }
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
            Text("Général", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                vm.interlocuteur, { vm.interlocuteur = it },
                label = { Text("Interlocuteur par défaut (ton nom)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                vm.zone, { vm.zone = it },
                label = { Text("Fuseau horaire (ex. Europe/Paris)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = vm::saveSettings, enabled = !vm.busy) { Text("Enregistrer") }

            HorizontalDivider()
            Text("Données", style = MaterialTheme.typography.titleMedium)
            Text(
                "Import : dans Google Sheets, Fichier › Télécharger › Microsoft Excel (.xlsx), puis choisis le fichier. " +
                    "L'import remplace toutes les données de l'application.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { importLauncher.launch(arrayOf(XLSX_MIME)) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Importer un RIDA (.xlsx)")
            }
            OutlinedButton(onClick = { exportLauncher.launch(exportName) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Exporter tout le RIDA (.xlsx)")
            }
            Text(
                "Données 100 % stockées sur ce téléphone. Pense à exporter régulièrement pour garder une sauvegarde.",
                style = MaterialTheme.typography.bodySmall,
            )
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
}
