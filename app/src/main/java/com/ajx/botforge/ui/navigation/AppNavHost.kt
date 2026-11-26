package com.ajx.botforge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ajx.botforge.ui.screens.AboutScreen
import com.ajx.botforge.ui.screens.BotEditScreen
import com.ajx.botforge.ui.screens.BotListScreen
import com.ajx.botforge.ui.screens.DashboardScreen
import com.ajx.botforge.ui.screens.GroupListScreen
import com.ajx.botforge.ui.screens.LogScreen
import com.ajx.botforge.ui.screens.ModelEditScreen
import com.ajx.botforge.ui.screens.ModelListScreen
import com.ajx.botforge.ui.screens.PersonaEditScreen
import com.ajx.botforge.ui.screens.PersonaListScreen
import com.ajx.botforge.ui.screens.PluginScreen
import com.ajx.botforge.ui.screens.SettingsScreen
import com.ajx.botforge.ui.screens.TerminalScreen
import com.ajx.botforge.ui.screens.ToolScreen

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    onRequestBattery: () -> Unit = {},
    onRequestOverlay: () -> Unit = {},
    onApplyShizuku: () -> Unit = {},
    onReloadBots: () -> Unit = {},
    /** 首页的启动/停止主操作 */
    onToggleService: () -> Unit = {},
    /** 补齐通知/悬浮窗/电池白名单权限 */
    onRequestPermissions: () -> Unit = {}
) {
    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenBots = { navController.navigate(Routes.BOTS) },
                onOpenPersonas = { navController.navigate(Routes.PERSONAS) },
                onOpenModels = { navController.navigate(Routes.MODELS) },
                onOpenLogs = { navController.navigate(Routes.LOGS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenPlugins = { navController.navigate(Routes.PLUGINS) },
                onOpenTerminal = { navController.navigate(Routes.TERMINAL) },
                onOpenTools = { navController.navigate(Routes.TOOLS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onToggleService = onToggleService,
                onRequestPermissions = onRequestPermissions
            )
        }

        composable(Routes.BOTS) {
            BotListScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Routes.botEdit(id)) },
                onGroups = { botId -> navController.navigate(Routes.botGroups(botId)) }
            )
        }

        composable(
            route = "${Routes.BOT_EDIT}?id={id}",
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            BotEditScreen(
                botId = id,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            route = "${Routes.BOT_GROUPS}?botId={botId}",
            arguments = listOf(
                navArgument("botId") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val botId = entry.arguments?.getString("botId").orEmpty()
            GroupListScreen(
                botId = botId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PERSONAS) {
            PersonaListScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Routes.personaEdit(id)) }
            )
        }

        composable(
            route = "${Routes.PERSONA_EDIT}?id={id}",
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            PersonaEditScreen(
                personaId = id,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(Routes.MODELS) {
            ModelListScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Routes.modelEdit(id)) }
            )
        }

        composable(
            route = "${Routes.MODEL_EDIT}?id={id}",
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ModelEditScreen(
                modelId = id,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(Routes.LOGS) {
            LogScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onRequestBattery = onRequestBattery,
                onRequestOverlay = onRequestOverlay,
                onApplyShizuku = onApplyShizuku,
                onReloadBots = onReloadBots
            )
        }

        composable(Routes.PLUGINS) {
            PluginScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TERMINAL) {
            TerminalScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TOOLS) {
            ToolScreen(onBack = { navController.popBackStack() })
        }
    }
}