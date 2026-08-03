package com.expenselens.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for the Supabase session JWT and the user's UUID
 * (the latter is what we send as `user_id` in every row insert).
 *
 * Backed by EncryptedSharedPreferences (AES-256 GCM, KeyStore-backed
 * master key), same scheme as [TokenStore]. The two stores are kept
 * separate so a Drive-token rotation never touches the Supabase
 * session and vice versa.
 */
class SupabaseAuthStore(context: Context) {

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
        Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back: ${t.message}")
        context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    fun saveSession(
        accessToken: String,
        refreshToken: String,
        userId: String,
        userEmail: String
    ) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, userEmail)
            .apply()
    }

    fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)
    fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)
    fun userId(): String? = prefs.getString(KEY_USER_ID, null)
    fun userEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    fun isSignedIn(): Boolean = !accessToken().isNullOrBlank() && !userId().isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "SupabaseAuthStore"
        private const val FILE_NAME = "expense_lens_supabase_auth"
        private const val FALLBACK_FILE_NAME = "expense_lens_supabase_auth_fallback"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
    }
}
