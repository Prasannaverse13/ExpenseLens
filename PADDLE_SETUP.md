# Paddle Premium setup

ExpenseLens uses [Paddle](https://paddle.com) for the Premium
subscription ($4.99 / month globally, ₹299 / month in India — applied
automatically by Paddle's local-pricing rules).

> Paddle is the international Merchant of Record. They handle sales
> tax / VAT / GST in every country, so we don't need a Razorpay
> account or an Indian KYC for sideload distribution. The same
> Paddle account also wires us up to debit cards, Google Pay, UPI,
> Apple Pay, PayPal, iDEAL, and ~20 more payment methods worldwide.

## How the flow works

```
┌─────────────────────────────────────────────────────┐
│  App  ──▶  Custom Tab opens pricing.html            │
│                                                     │
│  pricing.html  ──▶  Paddle.js inline checkout       │
│                                                     │
│  Paddle  ──▶  redirects to /success.html            │
│                                                     │
│  success.html  ──▶  fires expenselens:// deep link   │
│                                                     │
│  App  ──▶  MainActivity catches deep link,          │
│             flips is_premium, syncs to Drive        │
└─────────────────────────────────────────────────────┘
```

We **don't** open `https://buy.paddle.com/product/…` from the app. Paddle
Billing v2 gates the hosted-checkout pattern to "app-to-web" and
"non-mobile desktop" flows; for a native Android app you have to run
Paddle.js on an *approved domain* and have the user open that page in
a Custom Tab.

## One-time setup

### 1. Paddle dashboard

1. Sign up at <https://vendors.paddle.com> (Business account).
2. Complete business verification (PAN, DOB, trading name, payout
   bank). Usually 24–48h.
3. Create a product in **Catalog → Products**:
   - Name: `ExpenseLens Premium`
   - Tax category: `SaaS`
   - Add a price: USD $4.99 / monthly, recurring. Add an India
     local-pricing override for ₹299 / month.
4. Create a **client-side token** in **Developer tools →
   Authentication → Client-side tokens → New client-side token**.
   Name it `ExpenseLens web checkout`. **Copy the `live_…` token** —
   you'll paste it into `pricing.html` next.
5. Approve the marketing domain at
   <https://vendors.paddle.com/website-approval>:
   - Domain: `prasannaverse13.github.io`
   - Type: Website approval (or Paddle will show the option for the
     inline checkout).

### 2. `expenselens-website/pricing.html`

Open the file and replace the placeholder:

```js
var PADDLE_CLIENT_TOKEN = 'live_REPLACE_WITH_YOUR_CLIENT_SIDE_TOKEN';
```

…with the `live_…` token from step 1.4. The page already references
the right price id, the success-event handler, and the
`/success.html` redirect, so nothing else needs to change.

Commit and push the file to the `Prasannaverse13.github.io` repo (or
run the deploy script in this repo).

### 3. `local.properties`

```properties
paddle.product.id=pro_xxxxxxxxxxxx
paddle.price.id=pri_xxxxxxxxxxxx
paddle.pricing.url=https://prasannaverse13.github.io/pricing.html
paddle.price.usd=4.99
paddle.portal.url=
```

`paddle.pricing.url` defaults to the live site; override for
staging / alt deployments. The portal URL is optional — leave blank
until you wire up a customer portal.

## Sandbox testing

The Paddle.js inline checkout supports a `test_…` client-side token
and a `sandbox` environment. To test against sandbox:

1. In the **sandbox** Paddle dashboard, create a sandbox product +
   price + client-side token (`test_…`).
2. In `pricing.html`, set
   `Paddle.Environment.set('sandbox')` and use the `test_…` token.
3. Use a [sandbox test card](https://developer.paddle.com/concepts/sell/test-cards)
   (e.g. `4242 4242 4242 4242`).
4. To test the deep link manually, open
   `expenselens://premium-callback?status=success` from the device —
   it'll wake the app and unlock Premium locally.

## Security caveat

The local `is_premium` flag is **client-trusted**. Anyone with the
APK can flip it by hitting the deep link directly. For real money,
a backend should verify the Paddle `subscription.created` webhook
and flip the flag from there. The "Restore Premium" button in
Settings is the current safety net for users whose deep link missed.
