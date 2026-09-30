package rida.pour.les.pros.ui.clients

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.domain.CellColor
import rida.pour.les.pros.domain.Client
import rida.pour.les.pros.domain.ColorRules
import rida.pour.les.pros.domain.RidaStatus
import rida.pour.les.pros.ui.common.ColorChip
import javax.inject.Inject

data class ClientSummary(val client: Client, val open: Int, val late: Int, val soon: Int, val hidden: Int)

@HiltViewModel
class ClientsViewModel @Inject constructor(private val repo: RidaRepository) : ViewModel() {
    init {
        viewModelScope.launch { repo.ensureSystemClients() }
    }

    val summaries: StateFlow<List<ClientSummary>?> = combine(
        repo.observeClients(),
        repo.observeAllLines(),
        flow { emit(repo.today()) },
    ) { clients, lines, today ->
        val byClient = lines.groupBy { it.clientId }
        clients.mapNotNull { c ->
            val l = byClient[c.id].orEmpty()
            if (c.isSystem && l.isEmpty()) return@mapNotNull null
            val visible = l.filter { !it.hidden }
            val colors = visible.filter { it.statut != RidaStatus.TERMINE }.map { ColorRules.compute(it, today).echeance }
            ClientSummary(
                client = c,
                open = visible.count { it.statut != RidaStatus.TERMINE },
                late = colors.count { it == CellColor.ROUGE_VIF },
                soon = colors.count { it == CellColor.ORANGE || it == CellColor.VERT_VIF },
                hidden = l.size - visible.size,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(
    onBack: () -> Unit,
    onOpenClient: (String) -> Unit,
    onOpenReferentiel: () -> Unit,
    vm: ClientsViewModel = hiltViewModel(),
) {
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clients") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    IconButton(onClick = onOpenReferentiel) { Icon(Icons.Default.Groups, "Référentiel clients") }
                },
            )
        },
    ) { padding ->
        val list = summaries
        when {
            list == null -> Unit
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Aucun client pour l'instant.", style = MaterialTheme.typography.titleMedium)
                Text("Ajoute tes clients dans le Référentiel (icône en haut), ou importe ton RIDA existant depuis les Paramètres du chat.")
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.client.id }) { s -> ClientCard(s) { onOpenClient(s.client.id) } }
            }
        }
    }
}

@Composable
private fun ClientCard(s: ClientSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.client.code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${s.open} ouverte(s)", style = MaterialTheme.typography.bodyMedium)
                if (s.late > 0) ColorChip("${s.late} en retard", CellColor.ROUGE_VIF)
                if (s.soon > 0) ColorChip("${s.soon} sous 4 j", CellColor.ORANGE)
            }
        }
    }
}
