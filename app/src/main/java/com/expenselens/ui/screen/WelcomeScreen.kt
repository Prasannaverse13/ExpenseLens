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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.auth.GoogleAuthManager
import com.expenselens.data.auth.TokenStore
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.data.supabase.SupabaseClientProvider
import com.expenselens.ui.common.ExBrandMark
import com.expenselens.ui.common.GrainientBackground
import com.expenselens.ui.theme.Emerald500
import com.expenselens.ui.theme.Emerald800
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val syncCoordinator: com.expenselens.data.sync.SyncCoordinator,
    private val supabase: SupabaseClientProvider
) : ViewModel() {

    /** Which email/password mode the form is in. */
    enum class Tab { SignIn, SignUp }

    sealed class State {
        object Idle : State()
        object SigningIn : State()
        data class Error(val message: String) : State()
        data class Info(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val throttleState: StateFlow<com.expenselens.data.auth.SignInThrottle.ThrottleState> =
        throttle.state

    /** Sign in vs Create account tab. */
    private val _tab = MutableStateFlow(Tab.SignIn)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    fun setTab(t: Tab) {
        if (_tab.value != t) {
            _tab.value = t
            // Clear stale errors when switching tabs
            _state.value = State.Idle
        }
    }

    /**
     * First-time vs returning. Drives the screen's hero copy:
     *   - false → "Get started" / "We'll create your account"
     *   - true  → "Welcome back" / "Sign in to continue"
     */
    val hasSignedInBefore: StateFlow<Boolean> = prefs.hasSignedInBefore
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            false
        )

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
                    // First successful sign-in on this device — flip
                    // the flag so the Welcome screen switches to
                    // "Welcome back" copy on subsequent launches.
                    prefs.setHasSignedInBefore(true)
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

    // ===== Email / password auth =====

    /**
     * Sign in with email + password. Validates locally first so the
     * user gets instant feedback for typos before we hit the network.
     */
    fun signInWithEmail(email: String, password: String, onSuccess: () -> Unit) {
        val cleanEmail = email.trim()
        if (!isValidEmail(cleanEmail)) {
            _state.value = State.Error("Please enter a valid email address.")
            return
        }
        if (password.length < MIN_PASSWORD_LEN) {
            _state.value = State.Error("Password must be at least $MIN_PASSWORD_LEN characters.")
            return
        }
        viewModelScope.launch {
            _state.value = State.SigningIn
            try {
                val sb = supabase.client
                    ?: throw IllegalStateException("Supabase not configured")
                sb.auth.signInWith(Email) {
                    this.email = cleanEmail
                    this.password = password
                }
                onEmailAuthSuccess(cleanEmail, onSuccess)
            } catch (t: Throwable) {
                _state.value = State.Error(mapEmailAuthError(t))
                android.util.Log.w(TAG, "email signIn failed: ${t.message}")
            }
        }
    }

    /**
     * Create a new account with email + password. Supabase will
     * either auto-confirm (if email confirmation is off in the
     * dashboard) or send a verification email (if it's on).
     */
    fun signUpWithEmail(email: String, password: String, onSuccess: () -> Unit) {
        val cleanEmail = email.trim()
        if (!isValidEmail(cleanEmail)) {
            _state.value = State.Error("Please enter a valid email address.")
            return
        }
        if (password.length < MIN_PASSWORD_LEN) {
            _state.value = State.Error("Password must be at least $MIN_PASSWORD_LEN characters.")
            return
        }
        viewModelScope.launch {
            _state.value = State.SigningIn
            try {
                val sb = supabase.client
                    ?: throw IllegalStateException("Supabase not configured")
                sb.auth.signUpWith(Email) {
                    this.email = cleanEmail
                    this.password = password
                }
                // Two outcomes after signUp:
                //  - email confirmation OFF  → session is returned immediately
                //  - email confirmation ON   → session is null, user must verify
                val session = sb.auth.currentSessionOrNull()
                if (session != null) {
                    onEmailAuthSuccess(cleanEmail, onSuccess)
                } else {
                    _state.value = State.Info(
                        "Account created. Check your email to confirm, then sign in."
                    )
                }
            } catch (t: Throwable) {
                _state.value = State.Error(mapEmailAuthError(t))
                android.util.Log.w(TAG, "email signUp failed: ${t.message}")
            }
        }
    }

    /** Send a password-reset email. No state change on success. */
    fun resetPassword(email: String) {
        val cleanEmail = email.trim()
        if (!isValidEmail(cleanEmail)) {
            _state.value = State.Error("Please enter a valid email address.")
            return
        }
        viewModelScope.launch {
            _state.value = State.SigningIn
            try {
                val sb = supabase.client
                    ?: throw IllegalStateException("Supabase not configured")
                sb.auth.resetPasswordForEmail(cleanEmail)
                _state.value = State.Info(
                    "Password reset email sent. Check your inbox."
                )
            } catch (t: Throwable) {
                _state.value = State.Error(mapEmailAuthError(t))
                android.util.Log.w(TAG, "resetPassword failed: ${t.message}")
            }
        }
    }

    /** Common post-success hook for both email sign-in and sign-up. */
    private suspend fun onEmailAuthSuccess(email: String, onSuccess: () -> Unit) {
        throttle.recordSuccess()
        prefs.setDriveConnected(true)
        prefs.setDriveAccount(email)
        prefs.setDriveAccountName(email.substringBefore('@'))
        prefs.setHasSignedInBefore(true)
        try {
            syncCoordinator.runMigrationIfNeeded()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "migration failed: ${t.message}")
        }
        try {
            syncCoordinator.pullOnStart()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "pullOnStart failed: ${t.message}")
        }
        _state.value = State.Idle
        onSuccess()
    }

    /** Loose email regex — matches `x@y.z` and stops there. */
    private fun isValidEmail(raw: String): Boolean =
        android.util.Patterns.EMAIL_ADDRESS.matcher(raw).matches()

    /**
     * Supabase throws [RestException] with a `error` field on the body.
     * The user sees the human label, not the raw status code.
     */
    private fun mapEmailAuthError(t: Throwable): String {
        if (t is RestException) {
            // Supabase error body looks like: {"error":"invalid_grant","error_description":"Invalid login credentials"}
            val desc = (t.description ?: "").lowercase()
            val err = (t.error ?: "").lowercase()
            return when {
                "invalid login credentials" in desc ||
                    "invalid_grant" in err ||
                    "invalid email or password" in desc ->
                    "Wrong email or password. Try again or use 'Forgot password'."
                "user already registered" in desc ||
                    "user_already_exists" in err ||
                    "already registered" in desc ->
                    "An account with that email already exists. Try signing in instead."
                "password should be at least" in desc ||
                    "weak password" in desc ||
                    "characters" in desc ->
                    "Password is too weak. Use at least $MIN_PASSWORD_LEN characters with a mix of letters and numbers."
                "email rate limit" in desc ||
                    "email_address" in err && "rate" in desc ->
                    "Too many attempts. Please wait a few minutes and try again."
                "signup_disabled" in err ->
                    "New sign-ups are temporarily disabled. Please try again later."
                "network" in desc || "timeout" in desc ->
                    "Network problem. Check your connection and try again."
                else -> "Sign-in failed. Please check your details and try again."
            }
        }
        return t.message?.takeIf { it.isNotBlank() } ?: "Sign-in failed. Please try again."
    }

    fun clearError() { _state.value = State.Idle }

    /**
     * Escape hatch: clear the sign-in throttle AND the cached Google
     * sign-in state. Shown as a small "Having trouble?" button on the
     * Welcome screen. Lets the user recover from a lockout without
     * uninstalling or clearing app data.
     */
    fun resetAttempts() {
        viewModelScope.launch {
            try {
                auth.signOut()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "signOut during reset: ${t.message}")
            }
            throttle.reset()
            _state.value = State.Idle
        }
    }

    /**
     * Called by the UI when the user taps the sign-in button so we can
     * record a "user actually attempted" failure if the system flow
     * never returns (network drop, etc).
     */
    fun registerAttempt() {
        // No-op — the system returns a result whether the user
        // signed in successfully or cancelled, so the success path above
        // is the only place that needs to record anything.
    }

    companion object {
        private const val TAG = "WelcomeViewModel"
        private const val MIN_PASSWORD_LEN = 8
    }
}

@Composable
fun WelcomeScreen(
    onSignedIn: () -> Unit,
    vm: WelcomeViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val throttle by vm.throttleState.collectAsState()
    val hasSignedInBefore by vm.hasSignedInBefore.collectAsState()
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

            // Hero copy — first-time vs returning.
            // First-time: "Welcome to ExpenseLens" / "Capture bills in seconds."
            // Returning:   "Welcome back"          / "Sign in to your account."
            Text(
                text = if (hasSignedInBefore) "Welcome back" else "Welcome to ExpenseLens",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (hasSignedInBefore) "Sign in to continue."
                else "Capture bills in seconds. Yours to keep.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(40.dp))

            // ===== Email / password form =====
            // Tab toggle between "Sign in" and "Create account". The
            // form below adapts: Sign in gets a "Forgot password?"
            // link, Create account gets a "min 8 characters" hint.
            val currentTab by vm.tab.collectAsState()
            TabRow(
                selectedTabIndex = if (currentTab == WelcomeViewModel.Tab.SignIn) 0 else 1,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = { tabPositions ->
                    if (currentTab.ordinal < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[currentTab.ordinal]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                Tab(
                    selected = currentTab == WelcomeViewModel.Tab.SignIn,
                    onClick = { vm.setTab(WelcomeViewModel.Tab.SignIn) },
                    text = {
                        Text(
                            "Sign in",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (currentTab == WelcomeViewModel.Tab.SignIn)
                                FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = currentTab == WelcomeViewModel.Tab.SignUp,
                    onClick = { vm.setTab(WelcomeViewModel.Tab.SignUp) },
                    text = {
                        Text(
                            "Create account",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (currentTab == WelcomeViewModel.Tab.SignUp)
                                FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // Email field
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            var passwordVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                enabled = state !is WelcomeViewModel.State.SigningIn,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(Modifier.height(12.dp))

            // Password field with show/hide toggle
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = state !is WelcomeViewModel.State.SigningIn,
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password"
                            else "Show password",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                supportingText = {
                    if (currentTab == WelcomeViewModel.Tab.SignUp) {
                        Text(
                            "At least 8 characters",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            )

            // Forgot password — only on the Sign in tab.
            if (currentTab == WelcomeViewModel.Tab.SignIn) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { vm.resetPassword(email) },
                        enabled = state !is WelcomeViewModel.State.SigningIn
                    ) {
                        Text(
                            "Forgot password?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Email primary button — "Sign in" or "Create account"
            // depending on the active tab. Shows the same loading
            // spinner as the Google flow when in flight.
            val isSigningIn = state is WelcomeViewModel.State.SigningIn
            Button(
                onClick = {
                    when (currentTab) {
                        WelcomeViewModel.Tab.SignIn ->
                            vm.signInWithEmail(email, password, onSignedIn)
                        WelcomeViewModel.Tab.SignUp ->
                            vm.signUpWithEmail(email, password, onSignedIn)
                    }
                },
                enabled = !blocked && !isSigningIn && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (currentTab) {
                            WelcomeViewModel.Tab.SignIn -> "Signing in…"
                            WelcomeViewModel.Tab.SignUp -> "Creating account…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        when (currentTab) {
                            WelcomeViewModel.Tab.SignIn -> "Sign in"
                            WelcomeViewModel.Tab.SignUp -> "Create account"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // "or" divider with Google option
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Text(
                    "  or continue with  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Google sign-in button — unchanged from before, works
            // alongside the email form.
            Button(
                onClick = {
                    if (!blocked) signInLauncher.launch(vm.buildSignInIntent())
                },
                enabled = !blocked && !isSigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                if (blocked) {
                    Text(
                        "Locked — ${formatRemaining(remaining)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        "Continue with Google",
                        style = MaterialTheme.typography.titleMedium,
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

            // Info message — used for "check your email to confirm"
            // and "password reset email sent". Different from Error:
            // not red, just informational.
            (state as? WelcomeViewModel.State.Info)?.let { info ->
                Spacer(Modifier.height(12.dp))
                Text(
                    info.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Reset escape hatch: shown whenever the user has had at
            // least one failed attempt or is locked out. A small,
            // low-emphasis TextButton — doesn't compete with the main
            // sign-in CTA but is discoverable. Tapping it clears the
            // throttle counter + cached Google state so the user can
            // try again immediately (no 1-hour wait, no uninstall).
            if (blocked || throttle.attempts >= 1 ||
                state is WelcomeViewModel.State.Error
            ) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.TextButton(
                    onClick = { vm.resetAttempts() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (blocked) "Stuck? Reset and try again"
                        else "Having trouble? Reset sign-in",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Hint copy — first-time vs returning. Only shown on a
            // clean state (no failed attempts) so it doesn't fight
            // the error copy.
            if (!blocked && throttle.attempts == 0 && state !is WelcomeViewModel.State.Error) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (hasSignedInBefore)
                        "Your account and bills are safe in the cloud."
                    else
                        "First time here? Your account is created on first sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
