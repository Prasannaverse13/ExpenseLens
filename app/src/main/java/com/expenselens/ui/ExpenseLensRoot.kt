package com.expenselens.ui

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.ui.screen.CaptureScreen
import kotlinx.coroutines.launch
import com.expenselens.ui.screen.DashboardScreen
import com.expenselens.ui.screen.DetailScreen
import com.expenselens.ui.screen.ListScreen
import com.expenselens.ui.screen.ManualEntryScreen
import com.expenselens.ui.screen.OnboardingScreen
import com.expenselens.ui.screen.ReportsScreen
import com.expenselens.ui.screen.ReviewScreen
import com.expenselens.ui.screen.SettingsScreen
import com.expenselens.ui.screen.SplashScreen
import com.expenselens.ui.screen.WelcomeScreen
import com.expenselens.ui.theme.ExpenseLensTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RootDependencies @Inject constructor(
    val preferences: AppPreferences
) : ViewModel()

@Composable
fun ExpenseLensRoot(rootDeps: RootDependencies = hiltViewModel()) {
    ExpenseLensTheme {
        val nav = rememberNavController()

        // Centralised bottom-nav routing: from any secondary screen, tapping
        // a tab (other than +) pops the back stack and navigates to that tab
        // so the user never has to drill back through every previous screen.
        fun goTab(tab: String) {
            val route = when (tab) {
                "home" -> "dashboard"
                "reports" -> "reports"
                "expenses" -> "expenses"
                "profile" -> "settings"
                else -> return
            }
            nav.navigate(route) {
                popUpTo(nav.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        NavHost(navController = nav, startDestination = "splash") {
            composable("splash") {
                SplashScreen(
                    onNavigate = { dest -> nav.navigate(dest) { popUpTo("splash") { inclusive = true } } }
                )
            }
            composable("onboarding") {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                OnboardingScreen(
                    onGetStarted = {
                        // Mark onboarding as seen so the splash skips it on
                        // every future launch. Local profile keys are gone —
                        // the Google account is the only identity.
                        scope.launch { rootDeps.preferences.setOnboarded(true) }
                        nav.navigate("welcome") { popUpTo("onboarding") { inclusive = true } }
                    },
                    onSkip = {
                        scope.launch { rootDeps.preferences.setOnboarded(true) }
                        nav.navigate("welcome") { popUpTo("onboarding") { inclusive = true } }
                    }
                )
            }
            composable("welcome") {
                WelcomeScreen(
                    onSignedIn = {
                        nav.navigate("dashboard") { popUpTo("welcome") { inclusive = true } }
                    }
                )
            }
            composable("dashboard") {
                DashboardScreen(
                    onAdd = { nav.navigate("capture") },
                    onOpenItem = { id -> nav.navigate("detail/$id") },
                    onOpenCapture = { nav.navigate("capture") },
                    onOpenReports = { nav.navigate("reports") },
                    onOpenExpenses = { nav.navigate("expenses") },
                    onOpenProfile = { nav.navigate("settings") }
                )
            }
            composable("capture") {
                CaptureScreen(
                    onBack = { nav.popBackStack() },
                    onReview = { id -> nav.navigate("review/$id") },
                    onManual = { nav.navigate("manual") }
                )
            }
            composable("expenses") {
                ListScreen(
                    onOpen = { id -> nav.navigate("detail/$id") },
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate("capture") },
                    onTab = { goTab(it) }
                )
            }
            composable("reports") {
                ReportsScreen(
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate("capture") },
                    onTab = { goTab(it) }
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
                    onCancel = { nav.popBackStack() },
                    onTab = { goTab(it) },
                    onAdd = { nav.navigate("capture") }
                )
            }
            composable(
                "detail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                DetailScreen(
                    id = id,
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate("capture") },
                    onTab = { goTab(it) }
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onLoggedOut = {
                        nav.navigate("welcome") { popUpTo(0) { inclusive = true } }
                    },
                    onAdd = { nav.navigate("capture") },
                    onTab = { goTab(it) }
                )
            }
        }
    }
}
