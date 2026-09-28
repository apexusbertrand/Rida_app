package rida.pour.les.pros.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rida.pour.les.pros.domain.CellColor
import rida.pour.les.pros.domain.RidaDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun CellColor.toColor(): Color = Color(android.graphics.Color.parseColor(hex))

/** Icône associée à chaque couleur : l'état reste lisible sans la couleur (accessibilité). */
fun CellColor.icon(): ImageVector = when (this) {
    CellColor.VERT_PASTEL -> Icons.Default.CheckCircle
    CellColor.ORANGE -> Icons.Default.Schedule
    CellColor.VERT_VIF -> Icons.Default.PlayCircle
    CellColor.ROUGE_VIF -> Icons.Default.Warning
    CellColor.ROUGE_PASTEL -> Icons.Default.ErrorOutline
}

fun CellColor.description(): String = when (this) {
    CellColor.VERT_PASTEL -> "normal"
    CellColor.ORANGE -> "échéance proche, à faire"
    CellColor.VERT_VIF -> "échéance proche, en cours"
    CellColor.ROUGE_VIF -> "en retard"
    CellColor.ROUGE_PASTEL -> "terminé sans date de réalisation"
}

/** Pastille colorée : texte toujours foncé sur fond clair ou vif, lisible en thème clair et sombre. */
@Composable
fun ColorChip(label: String, color: CellColor, modifier: Modifier = Modifier, showIcon: Boolean = true) {
    Row(
        modifier = modifier
            .background(color.toColor(), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (showIcon) Icon(color.icon(), contentDescription = color.description(), tint = Color(0xFF111111), modifier = Modifier.size(14.dp))
        Text(label, color = Color(0xFF111111), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Champ date JJ/MM/AAAA avec calendrier. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supporting: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = RidaDates.format(value),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        trailingIcon = {
            Row {
                if (value != null) {
                    IconButton(onClick = { onChange(null) }) { Icon(Icons.Default.Close, "Effacer la date") }
                }
                IconButton(onClick = { open = true }) { Icon(Icons.Default.CalendarMonth, "Choisir une date") }
            }
        },
        modifier = modifier,
    )
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Annuler") } },
        ) {
            DatePicker(state = state)
        }
    }
}

/** Texte d'erreur lisible pour l'utilisateur. */
fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Erreur inattendue (${this::class.simpleName})."
