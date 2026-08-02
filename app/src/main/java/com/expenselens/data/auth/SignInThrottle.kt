package com.expenselens.data.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Client-side sign-in throttling. NOT a real security mechanism — a
 * determined attacker can clear app data or uninstall. But it slows
 * down casual abuse and protects users from accidentally hammering
 * the Google sign-in flow.
 *
 * Policy (exponential backoff):
 *  - 1st and 2nd failed attempt: no wait
 *  - 3rd: 1 hour
 *  - 4th: 2 hours
 *  - 5th: 4 hours
 *  - 6th: 8 hours
 *  - 7th+: 24 hours (cap)
 *
 * State is stored in EncryptedSharedPreferences so clearing regular
 * app data doesn't reset the counter. Only clearing *all* app data
 * (or uninstalling) does.
 */
class SignInThrottle(context: Context) {

    private val prefs = try {
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
        Log.w(TAG, "EncryptedSharedPreferences unavailable: ${t.message}")
        context.getSharedPreferences(FALLBACK, Context.MODE_PRIVATE)
    }

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<ThrottleState> = _state.asStateFlow()

    fun snapshot(): ThrottleState = ThrottleState(
        attempts = prefs.getInt(KEY_ATTEMPTS, 0),
        blockedUntil = prefs.getLong(KEY_BLOCKED_UNTIL, 0L)
    )

    /** True if the user is currently blocked. */
    fun isBlocked(): Boolean {
        val until = prefs.getLong(KEY_BLOCKED_UNTIL, 0L)
        return until > System.currentTimeMillis()
    }

    /** Milliseconds until the user can try again, or 0 if not blocked. */
    fun remainingMillis(): Long {
        val until = prefs.getLong(KEY_BLOCKED_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return if (until > now) until - now else 0L
    }

    /**
     * Record a failed sign-in attempt. Returns the new state (which
     * may now be blocked). Updates the in-memory StateFlow so the UI
     * can re-render.
     */
    fun recordFailure(): ThrottleState {
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0) + 1
        val waitMillis = computeBackoff(attempts)
        val blockedUntil = if (waitMillis > 0) System.currentTimeMillis() + waitMillis else 0L
        prefs.edit()
            .putInt(KEY_ATTEMPTS, attempts)
            .putLong(KEY_BLOCKED_UNTIL, blockedUntil)
            .apply()
        val s = ThrottleState(attempts = attempts, blockedUntil = blockedUntil)
        _state.value = s
        return s
    }

    /** Record a successful sign-in. Resets the counter. */
    fun recordSuccess() {
        prefs.edit()
            .putInt(KEY_ATTEMPTS, 0)
            .putLong(KEY_BLOCKED_UNTIL, 0L)
            .apply()
        _state.value = ThrottleState()
    }

    /** Manually reset (e.g., from a "Forgot attempts" admin action). */
    fun reset() {
        prefs.edit().clear().apply()
        _state.value = ThrottleState()
    }

    /**
     * Returns the wait duration in ms for the Nth consecutive failure.
     * 0 means "no wait, try again now".
     */
    private fun computeBackoff(attempts: Int): Long = when (attempts) {
        0, 1, 2 -> 0L
        3 -> ONE_HOUR_MS
        4 -> 2 * ONE_HOUR_MS
        5 -> 4 * ONE_HOUR_MS
        6 -> 8 * ONE_HOUR_MS
        else -> 24 * ONE_HOUR_MS
    }

    data class ThrottleState(
        val attempts: Int = 0,
        val blockedUntil: Long = 0L
    ) {
        fun isBlocked(): Boolean = blockedUntil > System.currentTimeMillis()
        fun remainingMillis(): Long =
            (blockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    companion object {
        private const val TAG = "SignInThrottle"
        private const val FILE_NAME = "expense_lens_signin_throttle"
        private const val FALLBACK = "expense_lens_signin_throttle_fallback"
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_BLOCKED_UNTIL = "blocked_until"
        private const val ONE_HOUR_MS = 60L * 60L * 1000L
    }
}
