package com.expenselens.data.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// We hardcode the Drive scope string to avoid pulling the full
// google-api-services-drive artifact. The string is a stable Google
// API constant — see https://developers.google.com/drive/api/v3/about-auth
private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * Wraps the Google Sign-In + Drive OAuth flow.
 *
 *  - The user signs in once with the system account picker.
 *  - We ask for the [DriveScopes.DRIVE_FILE] scope so we can read/write a
 *    single app-owned folder in their Drive (the rest of their Drive is
 *    not touched).
 *  - The resulting access token lands in [TokenStore] (encrypted via
 *    EncryptedSharedPreferences). Non-sensitive display state (last-sync
 *    time, account email) lives in the regular DataStore prefs.
 *
 * NOTE: This implementation exchanges the chosen Google account for a
 * short-lived (≈1h) OAuth token via [GoogleAuthUtil]. To get a refresh
 * token you'd need a backend that swaps the server auth code — out of
 * scope for this on-device-only app. When the token expires we
 * re-prompt the user for the scope and persist a fresh one.
 */
class GoogleAuthManager(
    private val context: Context,
    private val tokenStore: TokenStore
) {

    private fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .build()

    private fun client(): GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions())

    /** The intent the activity should launch to begin the sign-in flow. */
    fun signInIntent(): Intent = client().signInIntent

    /**
     * Process the result from the sign-in activity. On success, fetches
     * and persists an OAuth access token for the Drive REST API.
     */
    suspend fun handleSignInResult(data: Intent?): SignInResult = withContext(Dispatchers.IO) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val account: GoogleSignInAccount = try {
            task.getResult(ApiException::class.java)
        } catch (e: ApiException) {
            val msg = when (e.statusCode) {
                GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Sign-in cancelled"
                GoogleSignInStatusCodes.SIGN_IN_FAILED -> "Sign-in failed: ${e.message}"
                GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS -> "Already in progress"
                else -> "Sign-in error (${e.statusCode}): ${e.message}"
            }
            Log.w(TAG, "Sign-in failed: $msg")
            return@withContext SignInResult.Error(msg)
        }
        val token = try {
            GoogleAuthUtil.getToken(
                context, account.account!!, "oauth2:$DRIVE_FILE_SCOPE"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to get Drive access token", t)
            return@withContext SignInResult.Error("Couldn't fetch Drive permission: ${t.message}")
        }
        val email = account.email ?: return@withContext SignInResult.Error("No email on account")
        tokenStore.saveTokens(
            accessToken = token,
            refreshToken = null,
            expiresAtMillis = System.currentTimeMillis() + 55 * 60 * 1000L,
            accountEmail = email
        )
        SignInResult.Success(email = email, displayName = account.displayName ?: email)
    }

    /** Check whether the last sign-in attempt is still usable. */
    fun isConnected(): Boolean = tokenStore.isAccessTokenValid() &&
        GoogleSignIn.getLastSignedInAccount(context) != null

    fun accountEmail(): String? = tokenStore.accountEmail()

    /**
     * Sign out from Google and clear our local token store. Safe to call
     * even if the user wasn't connected. Revokes server-side access so a
     * re-connect re-prompts for the Drive scope.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            client().revokeAccess().await()
        } catch (t: Throwable) {
            Log.w(TAG, "Sign-out threw: ${t.message}")
        }
        tokenStore.clear()
    }

    /** Re-fetch the access token from the cache (no network needed). */
    suspend fun ensureFreshToken(): String? = withContext(Dispatchers.IO) {
        if (tokenStore.isAccessTokenValid()) return@withContext tokenStore.accessToken()
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        try {
            val fresh = GoogleAuthUtil.getToken(
                context, account.account!!, "oauth2:$DRIVE_FILE_SCOPE"
            )
            tokenStore.updateAccessToken(
                fresh, System.currentTimeMillis() + 55 * 60 * 1000L
            )
            fresh
        } catch (t: Throwable) {
            Log.w(TAG, "Token refresh failed: ${t.message}")
            null
        }
    }

    sealed class SignInResult {
        data class Success(val email: String, val displayName: String) : SignInResult()
        data class Error(val message: String) : SignInResult()
    }

    companion object {
        private const val TAG = "GoogleAuthManager"
    }
}
