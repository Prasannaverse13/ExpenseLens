# Paddle — Premium subscription setup

This document explains how to set up Paddle for the ExpenseLens
Premium tier. Paddle is a Merchant of Record (MoR) — they handle
tax, invoicing and global payment methods for you. There's no
Indian KYC requirement (PAN/Aadhaar), so you can launch as soon as
your products are live.

If you only want a local-only premium toggle for testing, you can
skip this entirely and the "Subscribe" button will just stay
disabled in Settings. Everything else in the app still works.

---

## 1. Create a Paddle account

1. Sign up at https://vendors.paddle.com/
2. Pick a business name and country.
3. Skip the bank / tax verification for now — you can sell up to a
   small amount in test mode without it.

## 2. Create a Product

1. In the Paddle dashboard, go to **Catalog → Products → New product**.
2. Fill in:
   - **Name**: `ExpenseLens Premium`
   - **Description**: `Unlimited AI-powered bill extraction`
   - **Tax category**: `saas` (or `standard`, depending on your region)
3. Save. Copy the product ID — it looks like `pro_01hxxxxxxxxxxxxxxx`.

## 3. Create a Price (subscription)

1. Still in Catalog, click into your new product and choose **New price**.
2. Fill in:
   - **Type**: `Recurring`
   - **Billing period**: `Monthly`
   - **Currency**: `USD` (or your local currency)
   - **Price**: e.g. `4.99`
   - **Quantity**: `1` (one seat)
3. Save. Copy the price ID — it looks like `pri_01hxxxxxxxxxxxxxxx`.

## 4. Configure the checkout redirect URLs

Paddle's hosted checkout redirects the user back to your app on
success and cancel. The deep link is already wired in ExpenseLens:

- Success: `expenselens://premium-callback?status=success`
- Cancel:  `expenselens://premium-callback?status=cancelled`

The intent filter is declared in `AndroidManifest.xml`:

```xml
<intent-filter>
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="expenselens" android:host="premium-callback" />
</intent-filter>
```

Paddle's checkout supports these `redirect` / `redirect[success]` /
`redirect[cancel]` query params. They're set automatically by
`PaddleManager.buildCheckoutUrl(...)` so you don't need to configure
anything in the dashboard.

## 5. (Optional) Create a Customer Portal URL

Paddle's customer portal lets users cancel, update their card, and
download invoices.

1. Dashboard → **Developer tools → Customer portal**.
2. Add a domain approval (if asked) — you can skip this for testing.
3. Copy the URL (looks like `https://YOURDOMAIN.paddle.com/customer-portal/...`).

If you don't have a domain yet, leave `paddle.portal.url` blank and
the in-app "Manage" button just stays disabled.

## 6. Fill in `local.properties`

Open `local.properties` (gitignored, lives at the project root) and
add your IDs:

```properties
paddle.product.id=pro_01hxxxxxxxxxxxxxxx
paddle.price.id=pri_01hxxxxxxxxxxxxxxx
paddle.checkout.url=https://buy.paddle.com/product
paddle.price.usd=4.99
# Optional — only needed for the in-app "Manage" button
paddle.portal.url=
```

After saving, run a fresh build. The settings screen's Subscribe
button is now enabled and reads `Subscribe — $4.99/month`.

---

## How the flow works

1. User taps **Subscribe** in Settings.
2. `SettingsViewModel.subscribe(activity)` arms a one-shot listener
   on `PaddleManager` and calls `paddle.openCheckout(...)`.
3. `PaddleManager.openCheckout` builds the URL
   `https://buy.paddle.com/product/{productId}?prices[]={priceId}&quantity=1&redirect=expenselens://premium-callback?status=success`
   and launches a Chrome Custom Tab.
4. User pays with card / PayPal / Google Pay / iDEAL (Paddle picks
   the right ones for the buyer's country).
5. Paddle redirects the Custom Tab to
   `expenselens://premium-callback?status=success&paddle_subscription_id=sub_xxx`.
6. Android wakes MainActivity (`onNewIntent` if alive,
   `onCreate` if cold-launched). The activity sees the deep link
   and calls `paddle.handleReturn(uri)`.
7. `PaddleManager.handleReturn` flips the local premium flag,
   delivers the result to the armed listener, and the Settings
   screen switches to "Premium — active" within a frame or two.

---

## Testing in sandbox mode

1. In the Paddle dashboard, toggle the sandbox environment on
   (top-right of the dashboard).
2. Re-create your product + price in sandbox, copy the **sandbox**
   product and price IDs.
3. Put them in `local.properties` and rebuild.
4. Use these test card numbers at checkout (from Paddle docs):
   - **Card succeeds**: `4242 4242 4242 4242`
   - **Card declined**: `4000 0000 0000 0002`
   - **Any future expiry** and **any CVC**.
5. After success, the Settings screen should switch to
   "Premium — active" within a second.

## Going live

1. Complete Paddle's business verification (tax, bank).
2. Switch the dashboard back to production.
3. Replace the sandbox IDs in `local.properties` with the
   production ones.
4. Re-build & ship a new APK.

---

## Security caveat (read this once)

The current integration is **client-trusted** — anyone with the
APK can flip the local premium flag by hitting the deep link
directly. This is fine for a sideload / friend-and-family
distribution, and it's the same trade-off Razorpay made.

For real money at scale, you need a server-side receipt
validator. The cheapest path is a Cloudflare Worker or Firebase
Cloud Function that:

1. Receives Paddle's `subscription.created` webhook (sent to
   your endpoint, not the app).
2. Verifies the webhook signature using Paddle's public key.
3. Writes the premium flag to your own backend.
4. The app reads that flag on launch.

The `is_premium` boolean in `AppPreferences` is the single source
of truth on the device. When you wire the validator, have it call
`prefs.setPremium(true)` the same way the current client-side
`PaddleManager.handleReturn` does — the rest of the app already
honours that key (see `ExtractionPipeline.isPremium`).

---

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Subscribe button is greyed out | `paddle.product.id` or `paddle.price.id` is empty in `local.properties`. Rebuild. |
| Custom Tab opens then closes immediately | The product/price IDs are wrong for the current environment (sandbox vs prod). Re-check. |
| Settings never switches to "Premium — active" | Logcat: filter by tag `PaddleManager`. The deep link is being delivered (you'll see "Paddle return: uri=…"). If not, check AndroidManifest's intent-filter is in your main activity. |
| "Unknown return status" error | Paddle changed their redirect query param names. Update `PaddleManager.handleReturn` to match the new field. |
