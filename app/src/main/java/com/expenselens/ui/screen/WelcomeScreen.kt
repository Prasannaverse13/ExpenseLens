package com.expenselens.ui.screen

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.auth.GoogleAuthManager
import com.expenselens.data.auth.TokenStore
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.ui.common.ExBrandMark
import com.expenselens.ui.common.GrainientBackground
import com.expenselens.ui.theme.Emerald500
import com.expenselens.ui.theme.Emerald800
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Welcome / sign-in landing. The ONLY way in is Google Sign-In — no more
 * local name/email profile. After sign-in, the user's data lives in
 * their own Google Drive (a hidden appDataFolder the rest of their Drive
 * never sees), so there's no backend, no cloud server, and the user can
 * sign out and switch accounts whenever.
 *
 * Sign-in throttling: 3 failed attempts → 1 hour lockout, doubles each
 * time, capped at 24 hours. State lives in EncryptedSharedPreferences
 * (harder to reset than regular app data).
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val auth: GoogleAuthManager,
    private val prefs: AppPreferences,
    private val throttle: com.expenselens.data.auth.SignInThrottle,
    private val syncCoordinator: com.expenselens.data.sync.SyncCoordinator
) : ViewModel() {

    sealed class State {
        object Idle : State()
        object SigningIn : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val throttleState: StateFlow<com.expenselens.data.auth.SignInThrottle.ThrottleState> =
        throttle.state

    fun buildSignInIntent() = auth.signInIntent()

    /** Check the throttle. Returns null if the user can sign in now. */
    fun checkThrottle(): Long? {
        val s = throttle.state.value
        return if (s.isBlocked()) s.remainingMillis() else null
    }

    fun handleSignInResult(data: android.content.Intent?, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _state.value = State.SigningIn
            when (val r = auth.handleSignInResult(data)) {
                is GoogleAuthManager.SignInResult.Success -> {
                    throttle.recordSuccess()
                    prefs.setDriveConnected(true)
                    prefs.setDriveAccount(r.email)
                    prefs.setDriveAccountName(r.displayName)
                    // Run the one-time Drive → Supabase migration
                    // (no-op if already done). Then pull the new user's
                    // latest data from Supabase so they see their own
                    // data instead of whatever was on this device before.
                    try {
                        syncCoordinator.runMigrationIfNeeded()
                    } catch (t: Throwable) {
                        android.util.Log.w(
                            "WelcomeViewModel",
                            "migration failed: ${t.message}"
                        )
                    }
                    try {
                        syncCoordinator.pullOnStart()
                    } catch (t: Throwable) {
                        android.util.Log.w(
                            "WelcomeViewModel",
                            "pullOnStart failed: ${t.message}"
                        )
                    }
                    _state.value = State.Idle
                    onSuccess()
                }
                is GoogleAuthManager.SignInResult.Error -> {
                    // Cancelled is not a real failure — don't count it.
                    if (!r.message.contains("cancelled", ignoreCase = true)) {
                        throttle.recordFailure()
                    }
                    _state.value = State.Error(r.message)
                }
            }
        }
    }

    fun clearError() { _state.value = State.Idle }

    /**
     * Called by the UI when the user taps the sign-in button so we can
     * record a "user actually attempted" failure if the system flow
     * never returns (network drop, etc).
     */
    fun registerAttempt() {
        // No-op here — the system returns a result whether the user
        // signed in successfully or cancelled, so the success path above
        // is the only place that needs to record anything. Kept for
        // future use if we add a network probe before launching.
    }
}

@Composable
fun WelcomeScreen(
    onSignedIn: () -> Unit,
    vm: WelcomeViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val throttle by vm.throttleState.collectAsState()
    val context = LocalContext.current

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.handleSignInResult(result.data, onSignedIn)
    }

    // Re-render every second while blocked so the countdown ticks down.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(throttle.isBlocked()) {
        if (throttle.isBlocked()) {
            while (true) {
                delay(1000L)
                nowMs = System.currentTimeMillis()
            }
        }
    }
    val blocked = throttle.blockedUntil > nowMs
    val remaining = (throttle.blockedUntil - nowMs).coerceAtLeast(0L)

    GrainientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            // Top bar with brand
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                ExBrandMark(size = 36.dp)
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(40.dp))

            // Big EX circle
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Emerald500, Emerald800)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "EX",
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = "Welcome to ExpenseLens",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Capture bills in seconds. Yours to keep.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(40.dp))

            // Sign in button
            Button(
                onClick = {
                    if (!blocked) signInLauncher.launch(vm.buildSignInIntent())
                },
                enabled = !blocked && state !is WelcomeViewModel.State.SigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (blocked) {
                    Text(
                        "Locked — ${formatRemaining(remaining)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                } else if (state is WelcomeViewModel.State.SigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Signing in…",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        "Sign in with Google",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Throttle hint: how many attempts before lockout
            if (!blocked && throttle.attempts in 1..2) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${3 - throttle.attempts} attempt${if (3 - throttle.attempts == 1) "" else "s"} " +
                        "left before temporary lockout.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Error message
            (state as? WelcomeViewModel.State.Error)?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text(
                    err.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Private by design.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

/** "1h 23m", "47m", "12s" — coarse human label for the lockout countdown. */
private fun formatRemaining(ms: Long): String {
    if (ms <= 0) return "0s"
    val totalSec = ms / 1000
    val days = totalSec / 86_400
    val hours = (totalSec % 86_400) / 3_600
    val mins = (totalSec % 3_600) / 60
    val secs = totalSec % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${mins}m"
        mins > 0 -> "${mins}m"
        else -> "${secs}s"
    }
}
