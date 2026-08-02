package com.expenselens.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.ui.common.BrandAvatar
import com.expenselens.ui.common.ExpenseLensSecondaryCard
import com.expenselens.ui.common.ExpenseLensTopBar
import com.expenselens.ui.common.GrainientBackground
import com.expenselens.ui.theme.GlassEdge
import com.expenselens.ui.theme.GlassInner
import com.expenselens.ui.theme.GlassLight
import com.expenselens.ui.theme.Sage200
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings / profile screen. The "profile" is no longer a local name
 * + email field — it's whatever Google Sign-In gave us. The screen
 * shows the connected Google account, lets the user manage smart
 * extraction, currency, sync to/from their Google Drive, and (most
 * importantly) disconnect their Google account, which wipes local
 * data and returns to the sign-in welcome screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val authManager: com.expenselens.data.auth.GoogleAuthManager,
    private val backupManager: com.expenselens.data.backup.BackupManager,
    private val paddle: com.expenselens.data.billing.PaddleManager
) : ViewModel() {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            _ui.value = UiState(
                accountName = prefs.driveAccountName.first(),
                accountEmail = prefs.driveAccount.first(),
                currency = prefs.currency.first(),
                smartEnabled = prefs.smartEnabled.first(),
                smartConsent = prefs.smartConsent.first(),
                callsThisMonth = prefs.smartCalls.first(),
                lastSync = prefs.driveLastSync.first(),
                isPremium = prefs.isPremium.first()
            )
        }
    }

    fun saveCurrency(value: String) = viewModelScope.launch {
        prefs.setCurrency(value.ifBlank { "INR" })
    }

    /**
     * Local-only premium toggle. When a real Paddle webhook is verified
     * server-side, that path will call [setPremium] too — same key,
     * same effect, just coming from the receipt validator instead of
     * a user tap.
     */
    fun setPremium(value: Boolean) = viewModelScope.launch {
        prefs.setPremium(value)
        _ui.value = _ui.value.copy(isPremium = value)
    }

    fun isPaddleConfigured(): Boolean = paddle.isConfigured()
    fun paddlePriceUsd(): String = paddle.priceLabel()
    fun paddlePortalUrl(): String? = paddle.portalUrl()

    /**
     * Launch the Paddle hosted checkout in a Chrome Custom Tab. The
     * result comes back via the `expenselens://premium-callback`
     * deep link, which lands in MainActivity.onNewIntent /
     * onResume → paddle.handleReturn(uri) → this listener.
     */
    fun subscribe(activity: android.app.Activity) {
        paddle.setListener { result -> handlePaddleResult(result) }
        paddle.openCheckout(
            activity = activity,
            customerEmail = _ui.value.accountEmail
        )
    }

    fun handlePaddleResult(result: com.expenselens.data.billing.PaddleManager.PaymentResult) {
        when (result) {
            is com.expenselens.data.billing.PaddleManager.PaymentResult.Success -> {
                // Premium flag was already flipped by PaddleManager
                // (the runBlocking prefs.setPremium call in handleReturn).
                // Just refresh the UI.
                _ui.value = _ui.value.copy(
                    isPremium = true,
                    billingMessage = "Premium activated." +
                        if (result.subscriptionId.isNotBlank()) " Subscription ${result.subscriptionId}" else ""
                )
            }
            is com.expenselens.data.billing.PaddleManager.PaymentResult.Cancelled -> {
                _ui.value = _ui.value.copy(billingMessage = "Payment cancelled")
            }
            is com.expenselens.data.billing.PaddleManager.PaymentResult.Error -> {
                _ui.value = _ui.value.copy(billingMessage = result.message)
            }
            is com.expenselens.data.billing.PaddleManager.PaymentResult.ConfigMissing -> {
                _ui.value = _ui.value.copy(
                    billingMessage = "Paddle not configured. Add paddle.product.id and paddle.price.id to local.properties."
                )
            }
        }
    }

    /**
     * Open the Paddle customer portal in a Custom Tab. The user can
     * cancel the subscription, change their card, view invoices etc.
     */
    fun openPortal(context: android.content.Context) {
        paddle.portalUrl()?.let { url ->
            com.expenselens.data.billing.PaddleManager.openPortal(context, url)
        }
    }

    fun clearBillingMessage() {
        _ui.value = _ui.value.copy(billingMessage = null)
    }

    fun setSmartEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setSmartEnabled(enabled)
        _ui.value = _ui.value.copy(smartEnabled = enabled)
    }

    fun setSmartConsent(consented: Boolean) = viewModelScope.launch {
        prefs.setSmartConsent(consented)
        _ui.value = _ui.value.copy(smartConsent = consented)
    }

    /**
     * Disconnect = revoke Google access + clear local cached data +
     * route back to the sign-in screen. The user's data on their
     * Drive is untouched (they can sign back in with the same account
     * and it'll still be there).
     */
    fun disconnect(onDone: () -> Unit) = viewModelScope.launch {
        authManager.signOut()
        prefs.setDriveConnected(false)
        prefs.setDriveAccount("")
        prefs.setDriveAccountName("")
        prefs.setDriveLastSync(0L, "")
        onDone()
    }

    fun clearDriveError() {
        _ui.value = _ui.value.copy(driveError = null)
    }

    data class UiState(
        val accountName: String = "",
        val accountEmail: String = "",
        val currency: String = "INR",
        val smartEnabled: Boolean = false,
        val smartConsent: Boolean = false,
        val callsThisMonth: Int = 0,
        val lastSync: Long = 0L,
        val driveBusy: Boolean = false,
        val driveError: String? = null,
        val isPremium: Boolean = false,
        val billingMessage: String? = null
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit = {},
    onAdd: () -> Unit = {},
    onTab: (String) -> Unit = {},
    vm: SettingsViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsState()
    var showConsent by remember { mutableStateOf(false) }
    var pendingToggle by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var isDisconnecting by remember { mutableStateOf(false) }

    // Derive display values from the Google account.
    val firstName = ui.accountName.split(' ').firstOrNull().orEmpty()
    val initials = firstName.firstOrNull()?.uppercase() ?: "EX"
    val displayName = ui.accountName.ifBlank { "Signed in" }
    val displayEmail = ui.accountEmail.ifBlank { "—" }

    GrainientBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                ExpenseLensTopBar(title = "Profile", onBack = onBack)

                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 24.dp, end = 24.dp, top = 8.dp, bottom = 200.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // Google account card
                    item {
                        ExpenseLensSecondaryCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BrandAvatar(size = 64.dp, initials = initials)
                                Spacer(Modifier.size(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        displayName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        displayEmail,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CloudDone,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Signed in with Google",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            // Sync status + last-synced + buttons
                            if (ui.lastSync > 0L) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Last synced " + android.text.format.DateUtils
                                        .getRelativeTimeSpanString(
                                            ui.lastSync,
                                            System.currentTimeMillis(),
                                            android.text.format.DateUtils.MINUTE_IN_MILLIS
                                        ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            if (ui.driveBusy) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Working…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                // No manual sync/restore buttons. The
                                // SyncCoordinator pushes to Drive
                                // automatically a few seconds after every
                                // save (5s debounce), and pulls the
                                // latest backup on app start. The user
                                // just sees the "Last synced" label.
                                Text(
                                    "Your bills and receipts are stored in your " +
                                        "Google Drive. They sync automatically — no " +
                                        "need to tap anything.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            ui.driveError?.let { err ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // Smart extraction card
                    item {
                        ExpenseLensSecondaryCard {
                            Text(
                                "Smart extraction",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "When enabled, the app will send the captured bill image " +
                                    "(JPEG, downscaled to at most 1536px on the long edge) " +
                                    "to OpenAI's vision model to recover details the on-device " +
                                    "parser can't — merchant phone, FSSAI number, visit time, " +
                                    "item count. The full image is sent, not a redacted " +
                                    "snippet. Nothing else leaves your device. Free plan: " +
                                    "10 calls per month, then the on-device parser takes over. " +
                                    "Premium: unlimited.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Use smart extraction",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = ui.smartEnabled,
                                    onCheckedChange = { desired ->
                                        if (desired && !ui.smartConsent) {
                                            pendingToggle = true
                                            showConsent = true
                                        } else {
                                            vm.setSmartEnabled(desired)
                                        }
                                    }
                                )
                            }
                            if (ui.smartEnabled) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (ui.isPremium) "Calls this month: ${ui.callsThisMonth} (Premium — unlimited)"
                                    else "Calls this month: ${ui.callsThisMonth} / 10",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Currency card
                    item {
                        ExpenseLensSecondaryCard {
                            Text(
                                "Currency",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = ui.currency,
                                onValueChange = { v -> vm.saveCurrency(v.uppercase().take(3)) },
                                label = { Text("Default currency (e.g. INR, USD)") },
                                singleLine = true,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = GlassLight,
                                    unfocusedContainerColor = GlassLight,
                                    focusedBorderColor = GlassEdge,
                                    unfocusedBorderColor = GlassInner
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Premium tier card — Paddle-powered
                    item {
                        ExpenseLensSecondaryCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (ui.isPremium) "Premium — active" else "Free plan",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (ui.isPremium)
                                    "Unlimited AI-powered bill extraction. " +
                                        "Renews monthly via Paddle. Tap below to manage."
                                else
                                    "Free plan: 10 AI calls per month, then the on-device " +
                                        "parser takes over. Premium removes the cap.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            if (ui.isPremium) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val ctx = androidx.compose.ui.platform.LocalContext.current
                                    OutlinedButton(
                                        onClick = { vm.openPortal(ctx) },
                                        enabled = vm.paddlePortalUrl() != null,
                                        modifier = Modifier.weight(1f),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                    ) {
                                        Text("Manage", style = MaterialTheme.typography.labelLarge)
                                    }
                                    OutlinedButton(
                                        onClick = { vm.setPremium(false) },
                                        modifier = Modifier.weight(1f),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                    ) {
                                        Text(
                                            "Cancel",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            } else {
                                val context = androidx.compose.ui.platform.LocalContext.current
                                val activity = context as? android.app.Activity
                                Button(
                                    onClick = {
                                        if (activity != null) vm.subscribe(activity)
                                    },
                                    enabled = activity != null && vm.isPaddleConfigured(),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Subscribe — \$${vm.paddlePriceUsd()}/month",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (!vm.isPaddleConfigured()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Add paddle.product.id and paddle.price.id to " +
                                            "local.properties to enable subscription.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            ui.billingMessage?.let { msg ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Disconnect button
                    item { Spacer(Modifier.height(8.dp)) }
                    item {
                        ExpenseLensSecondaryCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                                    .clickable(enabled = !isDisconnecting) {
                                        showDisconnectConfirm = true
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Disconnect Google account",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Signs you out and clears cached data on " +
                                            "this device. Your Drive data is untouched — " +
                                            "sign back in with the same account any time.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Floating bottom nav
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                com.expenselens.ui.common.LiquidGlassBottomNav(
                    current = "profile",
                    onSelect = onTab,
                    onAdd = onAdd
                )
            }
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Disconnect Google account?") },
            text = {
                Text(
                    "You'll be signed out and the cached data on this device " +
                        "will be cleared. The data in your Google Drive is " +
                        "untouched — sign back in with the same account to " +
                        "see it again."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isDisconnecting,
                    onClick = {
                        showDisconnectConfirm = false
                        isDisconnecting = true
                        vm.disconnect { onLoggedOut() }
                    }
                ) {
                    if (isDisconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false; pendingToggle = false },
            title = { Text("Enable smart extraction?") },
            text = {
                Text(
                    "Smart extraction sends the captured bill image to OpenAI's " +
                        "vision model to recover details the on-device parser can't " +
                        "— merchant phone, FSSAI number, visit time, item count.\n\n" +
                        "What is sent: the JPEG of the bill, downscaled to at most " +
                        "1536px on the long edge. Nothing else leaves your device.\n\n" +
                        "Capped at 10 calls per month on the free plan. " +
                        "Premium removes the cap. You can turn this off any time."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setSmartConsent(true)
                    if (pendingToggle) vm.setSmartEnabled(true)
                    pendingToggle = false
                    showConsent = false
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false; pendingToggle = false }) {
                    Text("Not now")
                }
            }
        )
    }
}
