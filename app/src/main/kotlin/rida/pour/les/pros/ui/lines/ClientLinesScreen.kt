package rida.pour.les.pros.ui.lines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import rida.pour.les.pros.data.repo.RidaRepository
import rida.pour.les.pros.domain.ColorRules
import rida.pour.les.pros.domain.LineColors
import rida.pour.les.pros.domain.RidaDates
import rida.pour.les.pros.domain.RidaLine
import rida.pour.les.pros.ui.common.ColorChip
import javax.inject.Inject

data class LineItem(val line: RidaLine, val colors: LineColors)

data class ClientLinesState(val title: String, val items: List<LineItem>, val hiddenCount: Int, val showHidden: Boolean)

@HiltViewModel
class ClientLinesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repo: RidaRepository,
) : ViewModel() {
    val clientId: String = checkNotNull(savedState["clientId"])
    private val showHidden = MutableStateFlow(false)

    val state: StateFlow<ClientLinesState?> = combine(
        repo.observeClients().map { l -> l.firstOrNull { it.id == clientId }?.code ?: "" },
        repo.observeClientLines(clientId),
        showHidden,
        flow { emit(repo.today()) },
    ) { title, lines, show, today ->
        val visible = if (show) lines else lines.filter { !it.hidden }
        ClientLinesState(title, visible.map { LineItem(it, ColorRules.compute(it, today)) }, lines.count { it.hidden }, show)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleHidden() {
        showHidden.value = !showHidden.value
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientLinesScreen(
    onBack: () -> Unit,
    onOpenLine: (Long) -> Unit,
    onNewLine: (String) -> Unit,
    vm: ClientLinesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s?.title.orEmpty()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    IconButton(onClick = { onNewLine(vm.clientId) }) { Icon(Icons.Default.PostAdd, "Nouvelle ligne (saisie manuelle)") }
                    if ((s?.hiddenCount ?: 0) > 0) {
                        IconButton(onClick = vm::toggleHidden) {
                            Icon(
                                if (s?.showHidden == true) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                if (s?.showHidden == true) "Cacher les lignes masquées" else "Afficher les lignes masquées",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (s == null) return@Scaffold
        if (s.items.isEmpty()) {
            Text("Aucune ligne pour ce client.", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(s.items, key = { it.line.uuid }) { item -> LineCard(item) { onOpenLine(item.line.id) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineCard(item: LineItem, onClick: () -> Unit) {
    val l = item.line
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick).alpha(if (l.hidden) 0.5f else 1f),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "#${l.id} · ${l.type.label} · ${l.interlocuteur}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(l.sujet, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (l.action.isNotBlank()) Text(l.action, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ColorChip(l.statut.label, item.colors.statut)
                ColorChip(if (l.echeance != null) "Éch. ${RidaDates.format(l.echeance)}" else "Sans échéance", item.colors.echeance, showIcon = false)
                if (l.realisation != null) ColorChip("Réal. ${RidaDates.format(l.realisation)}", item.colors.realisation, showIcon = false)
            }
            if (l.commentaire.isNotBlank()) {
                Text("💬 ${l.commentaire}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
