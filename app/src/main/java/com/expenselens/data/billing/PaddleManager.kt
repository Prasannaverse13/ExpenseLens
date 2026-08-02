package com.expenselens.data.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.expenselens.BuildConfig
import com.expenselens.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Paddle Premium subscription.
 *
 * Paddle for Android doesn't ship an SDK the way Razorpay does — instead
 * you point the user at Paddle's hosted checkout URL in a Chrome Custom
 * Tab. When the user finishes (success or cancel), Paddle redirects to
 * a deep link you control. We use `expenselens://premium-callback` and
 * the Android system wakes our MainActivity with the URI; we read
 * `?status=...` and flip the local premium flag.
 *
 * The checkout URL is built from [BuildConfig.PADDLE_CHECKOUT_URL] +
 * product id + price id. The full URL pattern is:
 *
 *   https://buy.paddle.com/product/{product_id}?prices[]={price_id}
 *
 * Why not a Paddle SDK?
 *  - Paddle's SDK is JavaScript-targeted (web/react-native). On Android
 *    the recommended path is a Custom Tab + deep link.
 *  - No SDK = ~zero dependency surface and no manifest hacks.
 *  - The Custom Tab shares cookies with Chrome, so the user only has
 *    to enter their card once and Paddle auto-fills subsequent ones.
 *
 * SECURITY: Like the previous Razorpay integration, this is *client-
 * trusted*. Anyone with the APK can flip the local premium flag by
 * hitting the deep link directly. For real money, a backend (Firebase
 * Cloud Functions, Cloudflare Worker, etc.) should receive Paddle's
 * `subscription.created` webhook and flip the flag from there.
 */
@Singleton
class PaddleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences
) {

    /**
     * True when local.properties has the Paddle product + price IDs set
     * and the Subscribe button should be enabled.
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
     * Build the full checkout URL. Two query params get added so we know
     * who the purchase is for when the deep link comes back:
     *  - `email` (if we know the user's Google account)
     *  - `passthrough` = the user's email — Paddle forwards this back
     *    on the success URL so we can cross-reference. We also just put
     *    it in `email` for our own convenience.
     */
    fun buildCheckoutUrl(customerEmail: String): String {
        val base = BuildConfig.PADDLE_CHECKOUT_URL
            .ifBlank { "https://buy.paddle.com/product" }
        val product = BuildConfig.PADDLE_PRODUCT_ID
        val price = BuildConfig.PADDLE_PRICE_ID
        val u = Uri.parse("$base/$product").buildUpon()
            .appendQueryParameter("prices[]", price)
            .appendQueryParameter("quantity", "1")
            .appendQueryParameter("billing_cycle", "monthly")
            .appendQueryParameter("redirect", SUCCESS_REDIRECT)
            .appendQueryParameter("redirect[success]", SUCCESS_REDIRECT)
            .appendQueryParameter("redirect[cancel]", CANCEL_REDIRECT)
        if (customerEmail.isNotBlank()) {
            u.appendQueryParameter("email", customerEmail)
            u.appendQueryParameter("passthrough", customerEmail)
        }
        return u.build().toString()
    }

    /**
     * Open the Paddle hosted checkout in a Custom Tab. The result comes
     * back via the deep link in AndroidManifest, which lands in
     * MainActivity.onNewIntent / onResume. The Activity must still be
     * alive when the user returns (use `launchMode="singleTop"` so the
     * same instance is reused).
     */
    fun openCheckout(activity: Activity, customerEmail: String) {
        if (!isConfigured()) {
            Log.w(TAG, "Paddle not configured (PADDLE_PRODUCT_ID or PADDLE_PRICE_ID missing)")
            onResult(PaymentResult.ConfigMissing)
            return
        }
        val url = buildCheckoutUrl(customerEmail)
        Log.i(TAG, "Opening Paddle checkout: $url")
        try {
            val intent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
            intent.launchUrl(activity, Uri.parse(url))
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open Paddle checkout", t)
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
                // Flip the local premium flag. The subscription_id is
                // available in `paddle_subscription_id`; we log it for
                // the user's own reference (and any future server-side
                // verification).
                val subId = uri.getQueryParameter("paddle_subscription_id")
                    ?: uri.getQueryParameter("subscription_id")
                    ?: ""
                Log.i(TAG, "Paddle success: subscription=$subId")
                runBlocking { prefs.setPremium(true) }
                onResult(PaymentResult.Success(subscriptionId = subId))
            }
            status.equals("cancelled", ignoreCase = true) ||
                status.equals("canceled", ignoreCase = true) || // one L
                status.equals("cancel", ignoreCase = true) -> {
                onResult(PaymentResult.Cancelled)
            }
            else -> {
                // Unknown status — treat as error but don't unlock.
                onResult(PaymentResult.Error("Unknown return status: $status"))
            }
        }
    }

    /**
     * Optional: clear the local premium flag (used by the "Cancel"
     * button in Settings — until the real customer portal is wired up,
     * this just clears the local view).
     */
    suspend fun clearPremium() {
        prefs.setPremium(false)
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

        /** Where Paddle should redirect on success. */
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
