package com.expenselens.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.auth.GoogleAuthManager
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.ui.common.ExBrandMark
import com.expenselens.ui.common.SplashBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Splash ViewModel. Decides where the user lands next based on whether
 * they're already signed in with Google. The local "What should we
 * call you?" profile is gone — Google Sign-In is the only identity.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val auth: GoogleAuthManager,
    private val prefs: AppPreferences
) : ViewModel() {

    sealed class Dest {
        object Onboarding : Dest()          // first launch ever
        object Dashboard : Dest()           // already signed in
        object Welcome : Dest()             // not signed in → Google Sign-In
    }

    private val _dest = MutableStateFlow<Dest?>(null)
    val dest: StateFlow<Dest?> = _dest.asStateFlow()

    fun decide() {
        viewModelScope.launch {
            val onboarded = prefs.onboarded.first()
            _dest.value = when {
                // First launch ever (never seen onboarding) — only for the very
                // first install. Once they've tapped "Get Started" once, skip.
                !onboarded && !auth.isConnected() -> Dest.Onboarding
                auth.isConnected() -> Dest.Dashboard
                else -> Dest.Welcome
            }
        }
    }
}

/**
 * Splash: dark sage gradient, EX circle, brand wordmark, tagline.
 * Decides the next destination based on Google Sign-In status.
 */
@Composable
fun SplashScreen(
    onNavigate: (String) -> Unit,
    vm: SplashViewModel = hiltViewModel()
) {
    val dest by vm.dest.collectAsState()

    LaunchedEffect(Unit) {
        delay(1200)
        vm.decide()
    }

    LaunchedEffect(dest) {
        when (dest) {
            SplashViewModel.Dest.Onboarding -> onNavigate("onboarding")
            SplashViewModel.Dest.Dashboard -> onNavigate("dashboard")
            SplashViewModel.Dest.Welcome -> onNavigate("welcome")
            null -> Unit
        }
    }

    SplashBackground {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ExBrandMark(
                size = 120.dp,
                textColor = Color(0xFF0E3B2E),
                plateColor = Color(0xFFA0D0C2).copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "ExpenseLens",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AI-powered expense clarity",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
        }

        // Loading dashes (matches the design's three-dash indicator)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier.height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { i ->
                    Box(
                        modifier = Modifier
                            .height(4.dp)
                            .width(if (i == 1) 24.dp else 16.dp)
                            .background(Color.White.copy(alpha = if (i == 1) 0.9f else 0.5f))
                    )
                }
            }
        }
    }
}
