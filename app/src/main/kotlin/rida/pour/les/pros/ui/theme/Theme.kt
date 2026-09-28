package rida.pour.les.pros.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Orange = Color(0xFFE8590C)
private val Ink = Color(0xFF1F2A37)

private val Light = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Orange,
    onSecondary = Color.White,
    tertiary = Orange,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFCBD5E1),
    onPrimary = Ink,
    secondary = Color(0xFFFF8A4C),
    onSecondary = Ink,
    tertiary = Color(0xFFFF8A4C),
)

@Composable
fun RidaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
