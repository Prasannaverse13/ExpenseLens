package com.expenselens.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted on-device storage for the user's OAuth tokens. Backed by
 * EncryptedSharedPreferences, which AES-encrypts both keys and values
 * with a key in the Android KeyStore.
 *
 * The non-sensitive display state (last-sync time, account email) lives
 * in the regular DataStore via [com.expenselens.data.prefs.AppPreferences];
 * only the tokens that could be replayed against Google live here.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        // KeyStore can be unavailable on emulators with no PIN set or in
        // some test configurations. Fall back to plain prefs so the rest
        // of the app keeps working — the user just has to reconnect.
        Log.w("TokenStore", "EncryptedSharedPreferences unavailable, falling back: ${t.message}")
        context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        expiresAtMillis: Long,
        accountEmail: String
    ) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES, expiresAtMillis)
            .putString(KEY_ACCOUNT, accountEmail)
            .apply()
    }

    fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)
    fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)
    fun expiresAt(): Long = prefs.getLong(KEY_EXPIRES, 0L)
    fun accountEmail(): String? = prefs.getString(KEY_ACCOUNT, null)

    fun isAccessTokenValid(): Boolean {
        val token = accessToken() ?: return false
        if (token.isBlank()) return false
        val expires = expiresAt()
        // Treat as expired 60s early to avoid races.
        return expires <= 0L || System.currentTimeMillis() < expires - 60_000L
    }

    fun updateAccessToken(accessToken: String, expiresAtMillis: Long) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putLong(KEY_EXPIRES, expiresAtMillis)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "expense_lens_drive_tokens"
        private const val FALLBACK_FILE_NAME = "expense_lens_drive_tokens_fallback"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_ACCOUNT = "account_email"
    }
}
