package rida.pour.les.pros.ui.lines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.domain.Client
import rida.pour.les.pros.domain.HistoryFormat
import rida.pour.les.pros.domain.LineDraft
import rida.pour.les.pros.domain.Rida
import rida.pour.les.pros.domain.RidaDates
import rida.pour.les.pros.domain.RidaLine
import rida.pour.les.pros.domain.RidaStatus
import rida.pour.les.pros.domain.RidaType
import rida.pour.les.pros.domain.UserEdit
import rida.pour.les.pros.ui.common.DateField
import rida.pour.les.pros.ui.common.userMessage
import java.time.LocalDate
import javax.inject.Inject

sealed interface LineEvent {
    data class Message(val text: String) : LineEvent
    data object Close : LineEvent
}

@HiltViewModel
class LineEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: RidaRepository,
) : ViewModel() {
    private val lineId: Long? = savedState.get<Long>("lineId")
    private val newClientId: String? = savedState.get<String>("clientId")
    val isNew: Boolean get() = lineId == null

    var loaded by mutableStateOf(false); private set
    var existing by mutableStateOf<RidaLine?>(null); private set
    var client by mutableStateOf<Client?>(null); private set
    var clients by mutableStateOf<List<Client>>(emptyList()); private set

    var interlocuteur by mutableStateOf("")
    var type by mutableStateOf(RidaType.ACTION)
    var sujet by mutableStateOf("")
    var action by mutableStateOf("")
    var statut by mutableStateOf(RidaStatus.A_FAIRE)
    var echeance by mutableStateOf<LocalDate?>(null)
    var realisation by mutableStateOf<LocalDate?>(null)
    var commentaire by mutableStateOf("")
    var recul by mutableStateOf("")

    private val _events = MutableSharedFlow<LineEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LineEvent> = _events

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        clients = repo.getClients()
        if (lineId != null) {
            val l = repo.getLine(lineId)
            if (l == null) {
                _events.emit(LineEvent.Message("Ligne #$lineId introuvable."))
                _events.emit(LineEvent.Close)
                return
            }
            fill(l)
        } else {
            client = clients.firstOrNull { it.id == newClientId }
            echeance = RidaDates.defaultEcheance(repo.today())
        }
        loaded = true
    }

    private fun fill(l: RidaLine) {
        existing = l
        client = clients.firstOrNull { it.id == l.clientId }
        interlocuteur = l.interlocuteur
        type = l.type
        sujet = l.sujet
        action = l.action
        statut = l.statut
        echeance = l.echeance
        realisation = l.realisation
        commentaire = l.commentaire
        recul = l.recul
    }

    fun save() = launchSafe {
        val current = existing
        if (current == null) {
            val c = client ?: error("Client introuvable.")
            val created = repo.createLine(
                LineDraft(
                    client = c, interlocuteur = interlocuteur, type = type, sujet = sujet, action = action,
                    statut = statut, echeance = echeance, realisation = realisation, commentaire = commentaire,
                    historySummary = "saisie manuelle", recul = recul, byUser = true,
                ),
            )
            _events.emit(LineEvent.Message("Ligne #${created.id} créée chez ${c.code}."))
            _events.emit(LineEvent.Close)
        } else {
            val saved = repo.userEdit(
                current.id,
                UserEdit(interlocuteur, type, sujet, action, statut, echeance, realisation, commentaire, recul),
            )
            fill(saved)
            _events.emit(LineEvent.Message("Ligne #${saved.id} enregistrée."))
        }
    }

    fun hide(reason: String, mergedInto: Long?) = launchSafe {
        val current = existing ?: return@launchSafe
        fill(repo.hide(current.id, reason, mergedInto))
        _events.emit(LineEvent.Message("Ligne #${current.id} masquée."))
    }

    fun reclassify(target: Client) = launchSafe {
        val current = existing ?: return@launchSafe
        fill(repo.reclassify(current.id, target.id))
        _events.emit(LineEvent.Message("Ligne #${current.id} reclassée chez ${target.code}."))
    }

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _events.emit(LineEvent.Message(e.userMessage()))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineEditScreen(
    onBack: () -> Unit,
    onOpenLine: (Long) -> Unit,
    vm: LineEditViewModel = hiltViewModel(),
) {
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var hideDialog by remember { mutableStateOf(false) }
    var reclassDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is LineEvent.Message -> scope.launch { snackbar.showSnackbar(e.text) }
                LineEvent.Close -> onBack()
            }
        }
    }

    val existing = vm.existing
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (existing != null) "#${existing.id} · ${vm.client?.code.orEmpty()}"
                        else "Nouvelle ligne · ${vm.client?.code.orEmpty()}",
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    if (existing != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'actions") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Reclasser vers un autre client") },
                                    onClick = { menu = false; reclassDialog = true },
                                )
                                if (!existing.hidden) {
                                    DropdownMenuItem(
                                        text = { Text("Supprimer ou fusionner (masquer)") },
                                        onClick = { menu = false; hideDialog = true },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (existing != null) {
                Text(
                    "Créée le ${RidaDates.format(existing.createdDate)} — ID #${existing.id} (non modifiables)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (existing.hidden) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Ligne masquée", style = MaterialTheme.typography.titleSmall)
                            existing.originalSujet?.let { Text("Sujet d'origine : $it") }
                            existing.mergedInto?.let { id ->
                                TextButton(onClick = { onOpenLine(id) }) { Text("Ouvrir la ligne #$id") }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EnumPicker("Type", vm.type, RidaType.entries, { it.label }, { vm.type = it }, Modifier.weight(1f))
                EnumPicker("Statut", vm.statut, RidaStatus.entries, { it.label }, { vm.statut = it }, Modifier.weight(1f))
            }
            LimitedField("Sujet", vm.sujet, { vm.sujet = it }, Rida.SUJET_MAX)
            LimitedField("Action", vm.action, { vm.action = it }, Rida.ACTION_MAX)
            OutlinedTextField(vm.interlocuteur, { vm.interlocuteur = it }, label = { Text("Interlocuteur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateField(
                "Échéance", vm.echeance, { vm.echeance = it }, Modifier.fillMaxWidth(),
                isError = vm.type == RidaType.ACTION && vm.echeance == null,
                supporting = if (vm.type == RidaType.ACTION) "Obligatoire pour une ACTION" else null,
            )
            DateField("Réalisation", vm.realisation, { vm.realisation = it }, Modifier.fillMaxWidth())
            LimitedField("Commentaire (le tien)", vm.commentaire, { vm.commentaire = it }, Rida.COMMENTAIRE_MAX, minLines = 3)
            OutlinedTextField(vm.recul, { vm.recul = it }, label = { Text("Recul (analyse PMO)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text(if (existing == null) "Créer la ligne" else "Enregistrer") }

            if (existing != null && existing.history.isNotEmpty()) {
                HorizontalDivider()
                Text("Historique", style = MaterialTheme.typography.titleSmall)
                existing.history.forEach { h ->
                    Text(HistoryFormat.line(h), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(88.dp))
        }
    }

    if (hideDialog && existing != null) {
        HideDialog(existing.id, onDismiss = { hideDialog = false }) { reason, merged ->
            hideDialog = false
            vm.hide(reason, merged)
        }
    }
    if (reclassDialog && existing != null) {
        AlertDialog(
            onDismissRequest = { reclassDialog = false },
            title = { Text("Reclasser #${existing.id}") },
            text = {
                Column {
                    vm.clients.filter { !it.isSystem && it.id != existing.clientId }.forEach { c ->
                        TextButton(onClick = { reclassDialog = false; vm.reclassify(c) }) { Text(c.code) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { reclassDialog = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun LimitedField(label: String, value: String, onChange: (String) -> Unit, max: Int, minLines: Int = 1) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = value.length > max,
        supportingText = { Text("${value.length} / $max") },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun <T> EnumPicker(
    label: String,
    value: T,
    options: List<T>,
    display: (T) -> String,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label : ${display(value)}", modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(display(o)) }, onClick = { open = false; onChange(o) })
            }
        }
    }
}

@Composable
private fun HideDialog(id: Long, onDismiss: () -> Unit, onConfirm: (String, Long?) -> Unit) {
    var reason by remember { mutableStateOf("") }
    var mergeInto by remember { mutableStateOf("") }
    val merged = mergeInto.trim().removePrefix("#").toLongOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Masquer la ligne #$id") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("La ligne n'est jamais supprimée : son contenu et son ID sont conservés, elle est seulement masquée.")
                OutlinedTextField(
                    mergeInto, { mergeInto = it },
                    label = { Text("Fusionner dans l'ID (facultatif)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(reason, { reason = it }, label = { Text("Raison (facultatif)") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason, merged) }) { Text(if (merged != null) "Fusionner" else "Supprimer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
