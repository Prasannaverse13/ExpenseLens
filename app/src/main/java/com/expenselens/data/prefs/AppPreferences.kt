package com.expenselens.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.expenselens.domain.model.PaymentMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("expense_lens_prefs")

class AppPreferences(private val context: Context) {

    private val keyCurrency = stringPreferencesKey("currency")
    private val keyPayment = stringPreferencesKey("default_payment")
    private val keyLlmEndpoint = stringPreferencesKey("llm_endpoint")
    private val keyLlmKey = stringPreferencesKey("llm_key")
    private val keyOnboarded = booleanPreferencesKey("user_onboarded")

    // Legacy local-profile keys — kept read-only for one migration cycle so
    // existing installs don't crash if a stray reference slips through.
    // They are no longer written to.
    @Suppress("unused")
    private val keyLegacyUserName = stringPreferencesKey("user_name")
    @Suppress("unused")
    private val keyLegacyUserEmail = stringPreferencesKey("user_email")

    // Smart (LLM-assisted) extraction — never exposes the underlying provider.
    private val keySmartEnabled = booleanPreferencesKey("smart_enabled")
    private val keySmartConsent = booleanPreferencesKey("smart_consent")
    private val keySmartCalls = intPreferencesKey("smart_calls")
    private val keySmartCallsMonth = stringPreferencesKey("smart_calls_month") // yyyy-MM

    // Google Drive backup state (OAuth tokens are stored separately in
    // EncryptedSharedPreferences — these keys only hold the *display* state).
    private val keyDriveConnected = booleanPreferencesKey("drive_connected")
    private val keyDriveAccount = stringPreferencesKey("drive_account")
    private val keyDriveAccountName = stringPreferencesKey("drive_account_name")
    private val keyDriveLastSync = longPreferencesKey("drive_last_sync") // epoch millis
    private val keyDriveLastBackupId = stringPreferencesKey("drive_last_backup_id")

    // Premium subscription — local toggle only. Real Play Billing
    // verification (when added) will write the same key from the receipt
    // validator.
    private val keyPremium = booleanPreferencesKey("is_premium")

    val currency: Flow<String> = context.dataStore.data.map { it[keyCurrency] ?: "INR" }
    val defaultPayment: Flow<PaymentMethod> =
        context.dataStore.data.map { PaymentMethod.fromName(it[keyPayment]) }
    val llmEndpoint: Flow<String> = context.dataStore.data.map { it[keyLlmEndpoint] ?: "" }
    val llmKey: Flow<String> = context.dataStore.data.map { it[keyLlmKey] ?: "" }

    // Onboarding flag — set to true after the carousel. Used by the splash
    // to decide between onboarding → welcome → sign-in vs direct routing.
    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[keyOnboarded] ?: false }

    val smartEnabled: Flow<Boolean> = context.dataStore.data.map { it[keySmartEnabled] ?: false }
    val smartConsent: Flow<Boolean> = context.dataStore.data.map { it[keySmartConsent] ?: false }
    val smartCalls: Flow<Int> = context.dataStore.data.map { it[keySmartCalls] ?: 0 }
    val smartCallsMonth: Flow<String> = context.dataStore.data.map { it[keySmartCallsMonth] ?: "" }

    // Google Drive — non-sensitive display state. Actual OAuth tokens live
    // in EncryptedSharedPreferences (see [com.expenselens.data.auth.TokenStore]).
    val driveConnected: Flow<Boolean> = context.dataStore.data.map { it[keyDriveConnected] ?: false }
    val driveAccount: Flow<String> = context.dataStore.data.map { it[keyDriveAccount] ?: "" }
    val driveAccountName: Flow<String> = context.dataStore.data.map { it[keyDriveAccountName] ?: "" }
    val driveLastSync: Flow<Long> = context.dataStore.data.map { it[keyDriveLastSync] ?: 0L }
    val driveLastBackupId: Flow<String> = context.dataStore.data.map { it[keyDriveLastBackupId] ?: "" }

    /**
     * Premium subscription state. Off by default. When a real Play Billing
     * integration is added, the receipt validator will flip this to true.
     */
    val isPremium: Flow<Boolean> = context.dataStore.data.map { it[keyPremium] ?: false }

    suspend fun snapshot(): Preferences = context.dataStore.data.first()

    suspend fun setCurrency(value: String) = context.dataStore.edit { it[keyCurrency] = value }
    suspend fun setDefaultPayment(value: PaymentMethod) =
        context.dataStore.edit { it[keyPayment] = value.displayName }
    suspend fun setLlm(endpoint: String, key: String) =
        context.dataStore.edit {
            it[keyLlmEndpoint] = endpoint
            it[keyLlmKey] = key
        }

    suspend fun setOnboarded(value: Boolean) = context.dataStore.edit {
        it[keyOnboarded] = value
    }

    suspend fun setSmartEnabled(value: Boolean) = context.dataStore.edit {
        it[keySmartEnabled] = value
    }
    suspend fun setSmartConsent(value: Boolean) = context.dataStore.edit {
        it[keySmartConsent] = value
    }

    /**
     * Atomically increment the smart-extraction call counter for the current
     * month. When the month rolls over, the counter resets to 1.
     */
    suspend fun incrementSmartCalls(monthKey: String) {
        context.dataStore.edit { prefs ->
            val currentMonth = prefs[keySmartCallsMonth]
            val base = if (currentMonth == monthKey) (prefs[keySmartCalls] ?: 0) else 0
            prefs[keySmartCalls] = base + 1
            prefs[keySmartCallsMonth] = monthKey
        }
    }

    suspend fun setDriveConnected(value: Boolean) = context.dataStore.edit {
        it[keyDriveConnected] = value
    }

    suspend fun setDriveAccount(value: String) = context.dataStore.edit {
        it[keyDriveAccount] = value
    }

    suspend fun setDriveAccountName(value: String) = context.dataStore.edit {
        it[keyDriveAccountName] = value
    }

    suspend fun setDriveLastSync(epochMillis: Long, backupId: String = "") = context.dataStore.edit {
        it[keyDriveLastSync] = epochMillis
        if (backupId.isNotEmpty()) it[keyDriveLastBackupId] = backupId
    }

    suspend fun setPremium(value: Boolean) = context.dataStore.edit {
        it[keyPremium] = value
    }
}
