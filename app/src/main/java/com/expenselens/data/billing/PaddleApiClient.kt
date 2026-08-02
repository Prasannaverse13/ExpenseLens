package com.expenselens.data.billing

import android.util.Log
import com.expenselens.BuildConfig
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal client for Paddle Billing v2 — only what we need:
 * looking up active subscriptions by the signed-in user's email so the
 * "Verify with Paddle" action in Settings can confirm a payment that
 * the success-page deep link missed.
 *
 * No retrofit / no okhttp — HttpURLConnection is enough for two
 * GETs and keeps the dep graph small. All calls are dispatched on
 * Dispatchers.IO; the caller is a ViewModel.
 *
 * Security: `paddle.api.key` (BuildConfig.PADDLE_API_KEY) is a *server-
 * side* key. We put it in the APK because we have no backend, which
 * means anyone who unzips the APK can extract it. The risk is bounded
 * — Paddle API keys can be scoped per-product, and revoking is
 * instant. For a production app with revenue you'd put a thin server
 * in front (Cloudflare Worker / Firebase Function) and call Paddle
 * from there.
 */
@Singleton
class PaddleApiClient @Inject constructor() {

    /**
     * `true` when a v2 server-side API key is configured in
     * local.properties (PADDLE_API_KEY). The Settings screen uses
     * this to enable / disable the "Verify with Paddle" button.
     */
    fun isConfigured(): Boolean = BuildConfig.PADDLE_API_KEY.isNotBlank()

    /**
     * Result of [verifyActiveSubscription]. Sealed so the UI can show
     * a precise message instead of guessing from a string.
     */
    sealed class VerifyResult {
        /** Paddle confirmed an active or trialing subscription. */
        data class Active(
            val subscriptionId: String,
            val customerEmail: String,
            val nextBilledAt: String?
        ) : VerifyResult()

        /** Paddle returned successfully but no active subscription. */
        data object NoActiveSubscription : VerifyResult()

        /** No Paddle API key configured in local.properties. */
        data object NotConfigured : VerifyResult()

        /** Paddle returned a 4xx/5xx — surfaced so the user knows. */
        data class ApiError(val status: Int, val message: String) : VerifyResult()

        /** Network / parsing failure. */
        data class NetworkError(val message: String) : VerifyResult()
    }

    /**
     * Calls Paddle's `GET /v2/subscriptions?customer_email=...&status=active,trialing`
     * and returns the first matching subscription if any. Safe to call
     * from the UI thread — it dispatches to IO internally.
     */
    suspend fun verifyActiveSubscription(customerEmail: String): VerifyResult =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext VerifyResult.NotConfigured
            val email = customerEmail.trim()
            if (email.isEmpty()) {
                return@withContext VerifyResult.NetworkError("Empty email")
            }
            val url = URL(
                "https://api.paddle.com/subscriptions" +
                    "?customer_email=" + java.net.URLEncoder.encode(email, "UTF-8") +
                    "&status=active,trialing" +
                    "&per_page=5"
            )
            Log.i(TAG, "verifyActiveSubscription: GET $url")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer " + BuildConfig.PADDLE_API_KEY)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val body = (conn.errorStream ?: conn.inputStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.w(TAG, "Paddle verify failed: HTTP $code — $body")
                    return@withContext VerifyResult.ApiError(
                        status = code,
                        message = parsePaddleError(body) ?: "HTTP $code"
                    )
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val sub = parseFirstActiveSubscription(body)
                if (sub != null) {
                    Log.i(TAG, "verifyActiveSubscription: ACTIVE sub=${sub.subscriptionId}")
                    VerifyResult.Active(
                        subscriptionId = sub.subscriptionId,
                        customerEmail = email,
                        nextBilledAt = sub.nextBilledAt
                    )
                } else {
                    Log.i(TAG, "verifyActiveSubscription: no active subscription")
                    VerifyResult.NoActiveSubscription
                }
            } catch (t: Throwable) {
                Log.e(TAG, "verifyActiveSubscription: network error", t)
                VerifyResult.NetworkError(t.message ?: "Network error")
            } finally {
                conn.disconnect()
            }
        }

    private data class SubSummary(
        val subscriptionId: String,
        val nextBilledAt: String?
    )

    private fun parseFirstActiveSubscription(body: String): SubSummary? {
        val json = JSONObject(body)
        val data: JSONArray = json.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").takeIf { it.isNotEmpty() } ?: continue
            val status = obj.optString("status", "")
            if (status != "active" && status != "trialing") continue
            val nextBilled = obj.optJSONObject("next_billed_at")?.optString("date")
                ?: obj.optString("next_billed_at", "").takeIf { it.isNotEmpty() }
            return SubSummary(subscriptionId = id, nextBilledAt = nextBilled)
        }
        return null
    }

    private fun parsePaddleError(body: String): String? {
        return try {
            val obj = JSONObject(body)
            val err = obj.optJSONObject("error") ?: return null
            val detail = err.optString("detail", "").ifBlank { null }
            val code = err.optString("code", "").ifBlank { null }
            listOfNotNull(detail, code).joinToString(" — ").ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG = "PaddleApiClient"
    }
}
