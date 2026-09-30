package rida.pour.les.pros.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import rida.pour.les.pros.ui.chat.ChatScreen
import rida.pour.les.pros.ui.clients.ClientsScreen
import rida.pour.les.pros.ui.lines.ClientLinesScreen
import rida.pour.les.pros.ui.lines.LineEditScreen
import rida.pour.les.pros.ui.referentiel.ReferentielScreen
import rida.pour.les.pros.ui.settings.SettingsScreen

object Routes {
    const val CHAT = "chat?clientId={clientId}"
    const val CLIENTS = "clients"
    const val CLIENT = "client/{clientId}"
    const val LINE = "line/{lineId}"
    const val NEW_LINE = "new/{clientId}"
    const val REFERENTIEL = "referentiel"
    const val SETTINGS = "settings"

    fun chat(clientId: String? = null) = if (clientId == null) "chat" else "chat?clientId=$clientId"
    fun client(id: String) = "client/$id"
    fun line(id: Long) = "line/$id"
    fun newLine(clientId: String) = "new/$clientId"
}

/**
 * Le chat est l'écran d'accueil et le poste de pilotage. Sur tous les autres écrans, un bouton
 * flottant y ramène, avec le client affiché comme contexte.
 */
@Composable
fun RidaNavHost() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val contextClientId = entry?.arguments?.getString("clientId")?.takeIf { route == Routes.CLIENT || route == Routes.NEW_LINE }

    fun openChat(clientId: String?) {
        nav.navigate(Routes.chat(clientId)) {
            popUpTo(Routes.CHAT) { inclusive = true }
            launchSingleTop = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = Routes.CHAT) {
            composable(
                Routes.CHAT,
                arguments = listOf(navArgument("clientId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) {
                ChatScreen(
                    onOpenLine = { nav.navigate(Routes.line(it)) },
                    onOpenClients = { nav.navigate(Routes.CLIENTS) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.CLIENTS) {
                ClientsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenClient = { nav.navigate(Routes.client(it)) },
                    onOpenReferentiel = { nav.navigate(Routes.REFERENTIEL) },
                )
            }
            composable(Routes.CLIENT, arguments = listOf(navArgument("clientId") { type = NavType.StringType })) {
                ClientLinesScreen(
                    onBack = { nav.popBackStack() },
                    onOpenLine = { nav.navigate(Routes.line(it)) },
                    onNewLine = { nav.navigate(Routes.newLine(it)) },
                )
            }
            composable(Routes.LINE, arguments = listOf(navArgument("lineId") { type = NavType.LongType })) {
                LineEditScreen(onBack = { nav.popBackStack() }, onOpenLine = { nav.navigate(Routes.line(it)) })
            }
            composable(Routes.NEW_LINE, arguments = listOf(navArgument("clientId") { type = NavType.StringType })) {
                LineEditScreen(onBack = { nav.popBackStack() }, onOpenLine = { nav.navigate(Routes.line(it)) })
            }
            composable(Routes.REFERENTIEL) { ReferentielScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        }

        if (route != null && route != Routes.CHAT) {
            FloatingActionButton(
                onClick = { openChat(contextClientId) },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(20.dp),
            ) {
                Icon(Icons.Default.AddComment, contentDescription = "Ouvrir le chat Rida")
            }
        }
    }
}
