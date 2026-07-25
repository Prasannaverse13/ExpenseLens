package com.expenselens.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.expenselens.ui.screen.CaptureScreen
import com.expenselens.ui.screen.DashboardScreen
import com.expenselens.ui.screen.DetailScreen
import com.expenselens.ui.screen.ListScreen
import com.expenselens.ui.screen.ManualEntryScreen
import com.expenselens.ui.screen.ReviewScreen
import com.expenselens.ui.screen.SettingsScreen

@Composable
fun ExpenseLensRoot() {
    val nav = rememberNavController()
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFFFB68F), onPrimary = Color(0xFF4A1500), primaryContainer = Color(0xFF6B2400),
        secondary = Color(0xFFFFD0A2), background = Color(0xFF1B1A18), surface = Color(0xFF24221F)
    ) else lightColorScheme(
        primary = Color(0xFFFF7043), onPrimary = Color.White, primaryContainer = Color(0xFFFFE0CC),
        secondary = Color(0xFF7D5800)
    )
    MaterialTheme(colorScheme = scheme) {
        NavHost(navController = nav, startDestination = "dashboard") {
            composable("dashboard") {
                DashboardScreen(
                    onAdd = { nav.navigate("capture") },
                    onOpenItem = { id -> nav.navigate("detail/$id") },
                    onOpenList = { nav.navigate("list") },
                    onOpenSettings = { nav.navigate("settings") }
                )
            }
            composable("capture") {
                CaptureScreen(
                    onBack = { nav.popBackStack() },
                    onReview = { id -> nav.navigate("review/$id") },
                    onManual = { nav.navigate("manual") }
                )
            }
            composable(
                "review/{draftId}",
                arguments = listOf(navArgument("draftId") { type = NavType.StringType })
            ) { entry ->
                ReviewScreen(
                    draftId = entry.arguments?.getString("draftId").orEmpty(),
                    onDone = {
                        nav.popBackStack(route = "dashboard", inclusive = false)
                    },
                    onCancel = { nav.popBackStack() }
                )
            }
            composable("manual") {
                ManualEntryScreen(
                    onSaved = { nav.popBackStack(route = "dashboard", inclusive = false) },
                    onCancel = { nav.popBackStack() }
                )
            }
            composable("list") {
                ListScreen(
                    onOpen = { id -> nav.navigate("detail/$id") },
                    onBack = { nav.popBackStack() }
                )
            }
            composable(
                "detail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                DetailScreen(id = id, onBack = { nav.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
