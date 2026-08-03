package com.expenselens.data.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.expenselens.BuildConfig
import com.expenselens.data.auth.SupabaseAuthStore
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.data.supabase.PremiumRow
import com.expenselens.data.supabase.SupabaseClientProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Paddle Premium subscription.
 *
 * Paddle Billing v2 (the new dashboard) does **not** support the
 * `https://buy.paddle.com/product/{id}` hosted-checkout pattern for
 * native mobile apps — the dashboard explicitly gates hosted checkouts
 * to "app-to-web sales funnels" and "non-mobile desktop apps". Native
 * Android apps have to run the Paddle.js inline checkout on an
 * *approved domain* and have the user open that page in a Custom Tab.
 *
 * Our approved domain is `prasannaverse13.github.io`, so the flow is:
 *
 *   App  →  Custom Tab to https://prasannaverse13.github.io/pricing.html
 *   User →  Taps "Subscribe" on the page. Paddle.js opens the inline
 *           checkout overlay.
 *   Paddle  →  After payment, redirects to /success.html (the default
 *              payment link set in the dashboard).
 *   success.html  →  Fires the deep link `expenselens://premium-callback?status=success`
 *                    and shows a "Open ExpenseLens" button.
 *   App  →  MainActivity receives the deep link, flips the local
 *           `is_premium` flag, syncs to Drive (5s debounce), and the
 *           Settings screen reflects the new state.
 *
 * The actual Paddle price id, product id, and client-side token live
 * on the website (not in the app). The app only needs to know the
 * pricing page URL and the deep link scheme.
 *
 * SECURITY: This is still *client-trusted*. Anyone with the APK can
 * flip the local premium flag by hitting the deep link directly. For
 * real money, a backend (Firebase Cloud Functions, Cloudflare Worker,
 * etc.) should verify the Paddle `subscription.created` webhook and
 * flip the flag from there. The Restore Premium button in Settings is
 * the current safety net for users whose deep link missed.
 */
@Singleton
class PaddleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val supabase: SupabaseClientProvider,
    private val supabaseAuth: SupabaseAuthStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * True when local.properties has the Paddle product + price IDs set
     * and the Subscribe button should be enabled. The product/price IDs
     * are still useful for the in-app "Restore Premium" audit trail
     * (logged when premium flips), even though the actual checkout runs
     * on the marketing site.
     */
    fun isConfigured(): Boolean =
        BuildConfig.PADDLE_PRODUCT_ID.isNotBlank() &&
            BuildConfig.PADDLE_PRICE_ID.isNotBlank()

    /** What we render in the Subscribe button label (e.g. "4.99"). */
    fun priceLabel(): String = BuildConfig.PADDLE_PRICE_USD.ifBlank { "4.99" }

    /** Optional customer-portal URL. Returns null if not configured. */
    fun portalUrl(): String? =
        BuildConfig.PADDLE_PORTAL_URL.ifBlank { null }

    /**
     * Where to send the user when they tap "Subscribe" in Settings. By
     * default this is the marketing-site pricing page (which hosts the
     * Paddle.js inline checkout). Override via `paddle.pricing.url` in
     * local.properties for staging / alt deployments.
     */
    fun pricingPageUrl(): String =
        BuildConfig.PADDLE_PRICING_URL.ifBlank {
            "https://prasannaverse13.github.io/pricing.html"
        }

    /**
     * Open the Paddle.js inline checkout in a Custom Tab. The user pays
     * on the marketing site, the site redirects to /success.html, and
     * the success page fires the `expenselens://premium-callback?status=success`
     * deep link, which lands back in MainActivity and flips premium.
     */
    @Suppress("UNUSED_PARAMETER")
    fun openCheckout(activity: Activity, customerEmail: String) {
        if (!isConfigured()) {
            Log.w(TAG, "Paddle not configured (PADDLE_PRODUCT_ID or PADDLE_PRICE_ID missing)")
            onResult(PaymentResult.ConfigMissing)
            return
        }
        val url = pricingPageUrl()
        Log.i(TAG, "Opening Paddle pricing page: $url")
        try {
            val intent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
            intent.launchUrl(activity, Uri.parse(url))
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open Paddle pricing page", t)
            onResult(PaymentResult.Error(t.message ?: "Couldn't open checkout"))
        }
    }

    /**
     * Called from MainActivity when the deep link returns. Inspects the
     * query params, flips the local premium flag, and forwards the
     * result to whatever was waiting (the Settings ViewModel).
     */
    fun handleReturn(uri: Uri?) {
        if (uri == null) {
            onResult(PaymentResult.Cancelled)
            return
        }
        val status = uri.getQueryParameter("status")
            ?: uri.lastPathSegment
            ?: ""
        Log.i(TAG, "Paddle return: uri=$uri, status=$status")
        when {
            status.equals("success", ignoreCase = true) ||
                status.equals("completed", ignoreCase = true) ||
                status.equals("active", ignoreCase = true) -> {
                val subId = uri.getQueryParameter("paddle_subscription_id")
                    ?: uri.getQueryParameter("subscription_id")
                    ?: ""
                Log.i(TAG, "Paddle success: subscription=$subId")
                runBlocking { prefs.setPremium(true) }
                // Mirror to the Supabase `premium` table (best-effort, fire-and-forget).
                if (supabase.isConfigured() && supabaseAuth.isSignedIn()) {
                    val userId = supabaseAuth.userId().orEmpty()
                    val subIdFinal = subId
                    scope.launch {
                        writePremiumRow(userId, subIdFinal, isPremium = true)
                    }
                }
                onResult(PaymentResult.Success(subscriptionId = subId))
            }
            status.equals("cancelled", ignoreCase = true) ||
                status.equals("canceled", ignoreCase = true) ||
                status.equals("cancel", ignoreCase = true) -> {
                onResult(PaymentResult.Cancelled)
            }
            else -> {
                onResult(PaymentResult.Error("Unknown return status: $status"))
            }
        }
    }

    /**
     * Clear the local premium flag (used by the "Cancel" button in
     * Settings — until the real customer portal is wired up, this just
     * clears the local view).
     */
    suspend fun clearPremium() {
        prefs.setPremium(false)
        if (supabase.isConfigured() && supabaseAuth.isSignedIn()) {
            val userId = supabaseAuth.userId().orEmpty()
            scope.launch { writePremiumRow(userId, "", isPremium = false) }
        }
    }

    /**
     * Mirror the current premium state into the Supabase `premium`
     * table. Best-effort: failure is logged but never thrown (the local
     * prefs are the source of truth for the UI).
     */
    private suspend fun writePremiumRow(userId: String, subscriptionId: String, isPremium: Boolean) {
        try {
            val sb = supabase.client ?: return
            sb.postgrest.from("premium").upsert(
                PremiumRow(
                    userId = userId,
                    isPremium = isPremium,
                    paddleSubscriptionId = subscriptionId.ifBlank { null },
                    updatedAt = java.time.Instant.now().toString()
                )
            )
            Log.i(TAG, "Supabase premium row written: $isPremium (sub=$subscriptionId)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write Supabase premium row: ${t.message}")
        }
    }

    /** The current list of in-app billing results. */
    sealed class PaymentResult {
        data object ConfigMissing : PaymentResult()
        data object Cancelled : PaymentResult()
        data class Success(val subscriptionId: String) : PaymentResult()
        data class Error(val message: String) : PaymentResult()
    }

    /**
     * One-shot listener for the Settings UI. The Settings ViewModel
     * calls [setListener] before launching the Custom Tab, and the
     * listener is cleared the moment a result is delivered.
     */
    private val lock = Any()
    @Volatile private var pendingListener: ((PaymentResult) -> Unit)? = null

    fun setListener(l: ((PaymentResult) -> Unit)?) {
        synchronized(lock) { pendingListener = l }
    }

    private fun onResult(r: PaymentResult) {
        val cb: ((PaymentResult) -> Unit)? = synchronized(lock) {
            val v = pendingListener
            pendingListener = null
            v
        }
        if (cb == null) {
            Log.w(TAG, "No pending listener — dropping $r")
            return
        }
        cb(r)
    }

    companion object {
        private const val TAG = "PaddleManager"

        /** Deep link the success page fires back to the app. */
        const val SUCCESS_REDIRECT = "expenselens://premium-callback?status=success"

        /** Where Paddle should redirect on cancel. */
        const val CANCEL_REDIRECT = "expenselens://premium-callback?status=cancelled"

        /** Launch the customer's Paddle portal (manage / cancel / invoices). */
        fun openPortal(context: Context, url: String) {
            try {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(context, Uri.parse(url))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open Paddle portal", t)
            }
        }
    }
}
