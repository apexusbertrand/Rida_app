package rida.pour.les.pros.ui.referentiel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.domain.Client
import rida.pour.les.pros.ui.common.userMessage
import javax.inject.Inject

@HiltViewModel
class ReferentielViewModel @Inject constructor(private val repo: RidaRepository) : ViewModel() {
    val clients: StateFlow<List<Client>> =
        repo.observeClients().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun addClient(code: String) = launchSafe { repo.addClient(code); _messages.emit("Client « ${code.trim()} » ajouté.") }
    fun rename(c: Client, code: String) = launchSafe { repo.renameClient(c.id, code) }
    fun delete(c: Client) = launchSafe { repo.deleteClient(c.id); _messages.emit("Client « ${c.code} » supprimé.") }
    fun addAlias(c: Client, alias: String) = launchSafe { repo.addAlias(c.id, alias) }
    fun removeAlias(alias: String) = launchSafe { repo.removeAlias(alias) }

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _messages.emit(e.userMessage())
            }
        }
    }
}

private sealed interface Dialog {
    data class Rename(val client: Client) : Dialog
    data class Alias(val client: Client) : Dialog
    data class Delete(val client: Client) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReferentielScreen(onBack: () -> Unit, vm: ReferentielViewModel = hiltViewModel()) {
    val clients by vm.clients.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var newClient by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Référentiel clients") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "L'agent ne pourra jamais créer un client absent de cette liste. Les alias servent à reconnaître " +
                        "les autres graphies (dictée, fautes de frappe) et sont corrigés automatiquement.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newClient, { newClient = it }, label = { Text("Nouveau client") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.addClient(newClient); newClient = "" }, enabled = newClient.isNotBlank()) { Text("Ajouter") }
                }
            }
            items(clients, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.code + if (c.isSystem) " (système)" else "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (!c.isSystem) {
                                IconButton(onClick = { dialog = Dialog.Alias(c) }) { Icon(Icons.Default.Add, "Ajouter un alias") }
                                IconButton(onClick = { dialog = Dialog.Rename(c) }) { Icon(Icons.Default.Edit, "Renommer") }
                                IconButton(onClick = { dialog = Dialog.Delete(c) }) { Icon(Icons.Default.Delete, "Supprimer") }
                            }
                        }
                        if (c.aliases.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                c.aliases.forEach { a ->
                                    InputChip(
                                        selected = false,
                                        onClick = { vm.removeAlias(a) },
                                        label = { Text(a) },
                                        trailingIcon = { Icon(Icons.Default.Close, "Retirer l'alias $a") },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is Dialog.Rename -> TextDialog("Renommer ${d.client.code}", d.client.code, onDismiss = { dialog = null }) {
            dialog = null; vm.rename(d.client, it)
        }
        is Dialog.Alias -> TextDialog("Alias pour ${d.client.code}", "", onDismiss = { dialog = null }) {
            dialog = null; vm.addAlias(d.client, it)
        }
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Supprimer ${d.client.code} ?") },
            text = { Text("Possible uniquement si le client n'a aucune ligne.") },
            confirmButton = { TextButton(onClick = { dialog = null; vm.delete(d.client) }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annuler") } },
        )
        null -> Unit
    }
}

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
