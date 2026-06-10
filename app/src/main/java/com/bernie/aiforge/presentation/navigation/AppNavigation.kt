package com.bernie.aiforge.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bernie.aiforge.presentation.chat.ChatScreen
import com.bernie.aiforge.presentation.home.HomeScreen
import com.bernie.aiforge.presentation.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Home     : Screen("home")
    object Settings : Screen("settings")
    object Chat     : Screen("chat?chatId={chatId}&skillId={skillId}") {
        fun go(chatId: String? = null, skillId: String = "default") =
            "chat?chatId=${chatId ?: ""}&skillId=$skillId"
    }
    // Stubs — implement in next session
    object History  : Screen("history")
    object Memory   : Screen("memory")
    object Skills   : Screen("skills")
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Screen.Home.route) {

        composable(Screen.Home.route) {
            HomeScreen(
                onOpenChat     = { chatId, skillId -> navController.navigate(Screen.Chat.go(chatId, skillId)) },
                onOpenHistory  = { navController.navigate(Screen.History.route) },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onOpenSkills   = { navController.navigate(Screen.Skills.route) },
                onOpenMemory   = { navController.navigate(Screen.Memory.route) },
            )
        }

        composable(
            route     = Screen.Chat.route,
            arguments = listOf(
                navArgument("chatId")  { type = NavType.StringType; defaultValue = "" },
                navArgument("skillId") { type = NavType.StringType; defaultValue = "default" },
            ),
        ) {
            ChatScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        // Stub screens — replace with real implementations
        composable(Screen.History.route) {
            StubScreen("History", navController)
        }
        composable(Screen.Memory.route) {
            StubScreen("Memory", navController)
        }
        composable(Screen.Skills.route) {
            StubScreen("Skills", navController)
        }
    }
}

@Composable
private fun StubScreen(name: String, navController: NavHostController) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text(name) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(
                        onClick = { navController.popBackStack() }
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Default.ArrowBack, "Back"
                        )
                    }
                },
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.material3.Text("$name — coming in next session")
        }
    }
}
