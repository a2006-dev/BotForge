package com.ajx.botforge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ajx.botforge.ui.screens.BotListScreen
import com.ajx.botforge.ui.screens.DashboardScreen
import com.ajx.botforge.ui.screens.LogScreen
import com.ajx.botforge.ui.screens.ModelListScreen
import com.ajx.botforge.ui.screens.PersonaListScreen
import com.ajx.botforge.ui.screens.PlaceholderScreen
import com.ajx.botforge.ui.screens.SettingsScreen

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    onRequestBattery: () -> Unit = {},
    onRequestOverlay: () -> Unit = {},
    onApplyShizuku: () -> Unit = {},
    onReloadBots: () -> Unit = {}
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
                onOpenAbout = { navController.navigate(Routes.ABOUT) }
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
            PlaceholderScreen(
                title = if (id.isEmpty()) "添加机器人" else "编辑机器人",
                note = "表单页迁移中",
                onBack = { navController.popBackStack() }
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
        ) {
            PlaceholderScreen(
                title = "群配置",
                note = "群列表迁移中",
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
        ) {
            PlaceholderScreen(
                title = "人设编辑",
                note = "人设表单迁移中",
                onBack = { navController.popBackStack() }
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
        ) {
            PlaceholderScreen(
                title = "模型编辑",
                note = "模型表单迁移中",
                onBack = { navController.popBackStack() }
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
            PlaceholderScreen(
                title = "插件",
                note = "插件页迁移中",
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ABOUT) {
            PlaceholderScreen(
                title = "关于",
                note = "关于页迁移中",
                onBack = { navController.popBackStack() }
            )
        }
    }
}