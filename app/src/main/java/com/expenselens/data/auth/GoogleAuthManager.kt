package com.expenselens.data.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.expenselens.BuildConfig
import com.expenselens.data.supabase.SupabaseClientProvider
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.IDToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// We hardcode the Drive scope string to avoid pulling the full
// google-api-services-drive artifact. The string is a stable Google
// API constant — see https://developers.google.com/drive/api/v3/about-auth
private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * Wraps the Google Sign-In + Drive OAuth flow AND the Google→Supabase
 * session exchange.
 *
 *  - The user signs in once with the system account picker.
 *  - We ask for the [DRIVE_FILE_SCOPE] scope so we can read/write a
 *    single app-owned folder in their Drive (the rest of their Drive
 *    is not touched). The Drive permission is kept ONLY for the
 *    one-time migration on first launch after the Supabase upgrade —
 *    after that we drop it (no further Drive reads/writes).
 *  - We request the Google ID token so we can hand it to Supabase's
 *    `signInWithIdToken(Google, …)`. Supabase verifies the token
 *    against Google's public JWKS and mints a session JWT.
 *  - The Drive access token lands in [TokenStore] (encrypted).
 *  - The Supabase session lands in [SupabaseAuthStore] (encrypted).
 *
 *  Sign-in order: Drive token first (for the migration), then Supabase
 *  session (the durable identity for everything going forward).
 */
class GoogleAuthManager(
    private val context: Context,
    private val tokenStore: TokenStore,
    private val supabaseAuth: SupabaseAuthStore,
    private val supabase: SupabaseClientProvider
) {

    private fun signInOptions(): GoogleSignInOptions {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
        // ID token requires a Web OAuth client ID. The Client ID alone
        // is fine — Supabase doesn't need the Secret. If the developer
        // hasn't set it, we still get Drive scope but skip the Supabase
        // exchange (app works in local-only mode).
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isNotBlank()) {
            builder.requestIdToken(webClientId)
        }
        return builder.build()
    }

    private fun client(): GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions())

    /** The intent the activity should launch to begin the sign-in flow. */
    fun signInIntent(): Intent = client().signInIntent

    /**
     * Process the result from the sign-in activity. On success:
     *   1. fetches and persists the Drive access token
     *   2. fetches the Google ID token (if Web Client ID is configured)
     *   3. exchanges the ID token with Supabase for a session
     *   4. persists the Supabase access token + refresh + user_id
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
        val email = account.email ?: return@withContext SignInResult.Error("No email on account")

        // 1) Drive access token (kept for the one-time migration).
        val driveToken = try {
            GoogleAuthUtil.getToken(
                context, account.account!!, "oauth2:$DRIVE_FILE_SCOPE"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to get Drive access token (continuing without it): ${t.message}")
            null
        }
        if (driveToken != null) {
            tokenStore.saveTokens(
                accessToken = driveToken,
                refreshToken = null,
                expiresAtMillis = System.currentTimeMillis() + 55 * 60 * 1000L,
                accountEmail = email
            )
        }

        // 2 + 3) Google ID token → Supabase session.
        var supabaseOk = false
        val idToken = account.idToken
        if (idToken != null && supabase.isConfigured()) {
            val exchangeResult = exchangeGoogleIdTokenForSupabaseSession(idToken, email)
            supabaseOk = exchangeResult
        } else if (idToken == null) {
            Log.w(TAG, "No Google ID token — check GOOGLE_WEB_CLIENT_ID in local.properties")
        } else {
            Log.w(TAG, "Supabase not configured — cloud sync disabled")
        }

        if (supabaseOk) {
            SignInResult.Success(email = email, displayName = account.displayName ?: email, supabaseReady = true)
        } else {
            // Drive-only fallback. App still works locally.
            SignInResult.Success(email = email, displayName = account.displayName ?: email, supabaseReady = false)
        }
    }

    /**
     * Hand the Google ID token to Supabase's signInWithIdToken API.
     * Returns true on success, false (and logs) on any failure.
     */
    private suspend fun exchangeGoogleIdTokenForSupabaseSession(
        googleIdToken: String,
        email: String
    ): Boolean = withContext(Dispatchers.IO) {
        val sb = supabase.client ?: run {
            Log.w(TAG, "Supabase client not initialised")
            return@withContext false
        }
        try {
            sb.auth.signInWith(IDToken) {
                this.idToken = googleIdToken
                this.provider = Google
                this.nonce = ""  // not enforced server-side for Google
            }
            // After signInWith, the session is loaded. Read user info.
            val user = sb.auth.currentUserOrNull()
            if (user == null) {
                Log.w(TAG, "Supabase signInWithIdToken returned no user")
                return@withContext false
            }
            val session = sb.auth.currentSessionOrNull()
            if (session == null) {
                Log.w(TAG, "Supabase signInWithIdToken returned no session")
                return@withContext false
            }
            supabaseAuth.saveSession(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                userId = user.id,
                userEmail = user.email ?: email
            )
            Log.i(TAG, "Supabase session ready: userId=${user.id}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Supabase signInWithIdToken failed", t)
            false
        }
    }

    /** Check whether the last sign-in attempt is still usable. */
    fun isConnected(): Boolean = GoogleSignIn.getLastSignedInAccount(context) != null

    /** True if we have a Supabase session JWT (cloud sync available). */
    fun isSupabaseReady(): Boolean = supabaseAuth.isSignedIn()

    fun accountEmail(): String? = tokenStore.accountEmail()
    fun supabaseUserId(): String? = supabaseAuth.userId()

    /**
     * Sign out from Google and clear BOTH local token stores. Safe to
     * call even if the user wasn't connected. Revokes server-side
     * access so a re-connect re-prompts for the Drive scope.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            client().revokeAccess().await()
        } catch (t: Throwable) {
            Log.w(TAG, "Sign-out threw: ${t.message}")
        }
        tokenStore.clear()
        supabaseAuth.clear()
    }

    /** Re-fetch the Drive access token from the cache (no network needed). */
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
        data class Success(
            val email: String,
            val displayName: String,
            /** True if Supabase session was successfully established. */
            val supabaseReady: Boolean
        ) : SignInResult()
        data class Error(val message: String) : SignInResult()
    }

    companion object {
        private const val TAG = "GoogleAuthManager"
    }
}
