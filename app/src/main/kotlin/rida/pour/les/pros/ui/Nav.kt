package rida.pour.les.pros.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import rida.pour.les.pros.ui.clients.ClientsScreen
import rida.pour.les.pros.ui.lines.ClientLinesScreen
import rida.pour.les.pros.ui.lines.LineEditScreen
import rida.pour.les.pros.ui.referentiel.ReferentielScreen
import rida.pour.les.pros.ui.settings.SettingsScreen

object Routes {
    const val CLIENTS = "clients"
    const val CLIENT = "client/{clientId}"
    const val LINE = "line/{lineId}"
    const val NEW_LINE = "new/{clientId}"
    const val REFERENTIEL = "referentiel"
    const val SETTINGS = "settings"

    fun client(id: String) = "client/$id"
    fun line(id: Long) = "line/$id"
    fun newLine(clientId: String) = "new/$clientId"
}

@Composable
fun RidaNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.CLIENTS) {
        composable(Routes.CLIENTS) {
            ClientsScreen(
                onOpenClient = { nav.navigate(Routes.client(it)) },
                onOpenReferentiel = { nav.navigate(Routes.REFERENTIEL) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
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
}
