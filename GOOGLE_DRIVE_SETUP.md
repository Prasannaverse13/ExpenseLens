# Google Drive + Sign-In — setup guide

The Google Drive data store + Google Sign-In login are fully implemented
in code, but for them to actually talk to Google's servers you need a
one-time Google Cloud Console setup. This is a 15-minute job.

## What's already wired in code

- `GoogleAuthManager` — handles the system account-picker flow + requests
  the `https://www.googleapis.com/auth/drive.file` scope (so we can only
  see our own app folder, not the rest of the user's Drive). **Google
  Sign-In is the only login method** — there's no local name/email
  profile any more. Disconnecting in Settings = revoking Google access
  and clearing the local cache.
- `TokenStore` — stores the access token in `EncryptedSharedPreferences`
  (AES-256-GCM, master key in the Android KeyStore).
- `GoogleDriveManager` — talks to the Drive REST API v3 directly
  (upload, list, download, delete) using `HttpURLConnection`. Files live
  in the user's hidden `appDataFolder` so the main Drive is never touched.
- `BackupManager` — serialises every expense, line item, category,
  vendor correction, and receipt image to a single JSON blob and
  uploads it. Called automatically on every save.
- Settings UI — shows the connected Google account, the sync status,
  and a "Disconnect" button. No more Sync now / Restore buttons —
  sync happens in the background.

## What you (the developer) need to do once

### 1. Create a Google Cloud project

1. Go to https://console.cloud.google.com/
2. Click the project dropdown (top-left) → **New project**
3. Name it `ExpenseLens` (or whatever you like)
4. Click **Create**

### 2. Enable the Google Drive API

1. In the left menu, go to **APIs & Services → Library**
2. Search for **Google Drive API**
3. Click it, then click **Enable**

### 3. Configure the OAuth consent screen

1. Go to **APIs & Services → OAuth consent screen**
2. Choose **External** (unless you're a Google Workspace org), then **Create**
3. Fill in:
   - **App name**: `ExpenseLens`
   - **User support email**: your email
   - **Developer contact email**: your email
4. **Scopes** page → **Add or remove scopes** → add:
   - `https://www.googleapis.com/auth/drive.file`
5. **Test users** page → add **every Google account** that should be
   able to sign in. Until the app is verified by Google, only test
   users can authenticate. **If a user gets `Error 403: access_denied`
   / "App has not completed the Google verification process", they
   aren't in this list yet — add their email here.**
6. **Back to dashboard**

### 4. Create the OAuth 2.0 client ID

1. Go to **APIs & Services → Credentials**
2. **Create credentials → OAuth client ID**
3. Application type: **Android**
4. Name: `ExpenseLens Android`
5. Package name: `com.expenselens` (must match the app's
   `applicationId` in `app/build.gradle.kts`)
6. **SHA-1 certificate fingerprint** — `keytool` ships with the JDK
   (in its `bin` folder) and is **not** in `PATH` by default on
   Windows. Run this in PowerShell:

   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   & "$env:JAVA_HOME\bin\keytool.exe" -list -v `
     -keystore "$env:USERPROFILE\.android\debug.keystore" `
     -alias androiddebugkey -storepass android -keypass android
   ```

   Copy the `SHA1:` line into the Google Console form. (For a release
   build you'll repeat this with your own keystore.)

7. Click **Create**. Note the client ID — you don't need to paste it
   anywhere; Android matches it by package + SHA-1.

### 5. Rebuild + run

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "C:\Users\YourName\path\to\ExpenseLens"
.\gradlew :app:assembleDebug
```

Install the APK. Open the app → **Profile** → scroll to **Google
Drive** → **Connect Google Drive**. Pick the test user account. Done.

## Where backups live

Every backup file is stored in the user's Drive under
`App data → ExpenseLens`. They don't see it in their normal Drive
view, but if they go to https://drive.google.com/drive/u/0/search?q=owner:me%20appDataFolder
they can see / delete it.

File naming: `expenselens-backup-YYYY-MM-DD-<timestamp>.json`

## How tokens work

- After sign-in we get a short-lived (≈1 hour) access token via
  `GoogleAuthUtil.getToken(...)`.
- It lives in `EncryptedSharedPreferences` (AES-256, KeyStore-backed).
- We **don't** have a refresh token (those require a backend to do the
  auth-code exchange), so when the token expires the user re-prompted
  for the scope on next sync. In practice the token lasts an hour and
  most users sync less often than that — the re-prompt only happens if
  you leave the app for 60+ minutes and then hit Sync.

## Limitations of the current implementation

| | |
|---|---|
| **Auto-backup** | No — user has to tap **Sync now**. (Could be a `WorkManager` job later.) |
| **Background sync** | No — happens on the UI thread's coroutine scope. |
| **Multi-device restore** | Restore is destructive (replaces all local data) — fine for one device, but a future v2 should support merge. |
| **App verification** | OAuth consent screen is in "Testing" mode, capped at 100 test users. To publish on the Play Store, submit for Google verification. |
| **Versioning of backups** | Each sync creates a new file; old files stay in `appDataFolder` forever. Could add a "keep last N" cleanup later. |

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Sign-in failed (10)" | SHA-1 in Google Console doesn't match the keystore that signed the APK. Re-run `keytool -list ... debug.keystore`, update the Console, wait 5 min, rebuild. |
| "Sign-in failed (12501)" | Package name mismatch — the package in `defaultConfig` must match what's in the OAuth client. |
| **"Error 403: access_denied" / "App has not completed the Google verification process"** | **The account trying to sign in isn't in the OAuth consent screen's Test users list.** Go to **APIs & Services → OAuth consent screen → Test users → Add users**, paste the account email, save. Try signing in again — no rebuild needed. |
| "Couldn't fetch Drive permission" | Account isn't in the test-users list on the consent screen. |
| "Drive upload failed" | The token expired mid-upload. Hit **Sync now** again. |
| "App not verified" warning | Normal in dev — click **Advanced → Go to ExpenseLens (unsafe)**. Goes away once you submit for Google verification. |

## When the app is ready for real users

The OAuth consent screen in "Testing" mode is capped at 100 test users.
For wider distribution you have two paths:

1. **Keep it personal / small-team** — stay in Testing mode, add users manually.
2. **Ship to the Play Store / open worldwide sign-up** — submit the app
   for Google's OAuth verification. This is the **only** way to let
   arbitrary Google accounts sign in (otherwise they get the
   `Error 403: access_denied` wall).

### Steps to submit for Google OAuth verification

1. **OAuth consent screen** → change **Publishing status** from
   "Testing" to "In production". Google will warn you that the app
   isn't verified yet — that's fine, you can still submit for review.
2. **App information** (under OAuth consent screen):
   - **App homepage** — a real URL on a domain you own. A GitHub
     Pages site works for the placeholder.
   - **App privacy policy** — a real URL hosting a privacy policy
     that says "we store your bills in your own Google Drive and
     never see the data". Required.
   - **App terms of service** — another URL, optional but recommended.
   - **Authorised domains** — the domain your privacy policy is
     hosted on.
   - **Developer contact** — a real email Google can reach you at.
3. **Scopes justification** — for the `drive.file` scope, write a
   short paragraph explaining that the app uses it to back up user
   bills to a hidden appDataFolder in their own Drive, nothing else.
4. **Verification request** → click **Submit for verification**. You'll
   need to:
   - Pick the scope(s) you're using
   - Describe how you use the data (we use it to back up the user's
     own bills; we never see the data; nothing leaves the user's Drive
     except the appDataFolder file they own)
   - Provide a demo video showing the app signing in, capturing a bill,
     and the data appearing in the user's Drive
   - Wait. Google reviews take **2-4 weeks** for non-sensitive scopes
     like `drive.file`. Sensitive scopes take longer.
5. Once approved, the consent screen flips to "In production" and
   any Google account can sign in.

### What stays the same after verification

- Per-user data isolation already works — each Google account has
  their own `appDataFolder` in their own Drive.
- The 10/month AI cap is per-device, not per-user. To make it
  per-user you need a backend (Firebase / Supabase / your own).
- The Premium tier is a local toggle. To charge for it, you need
  Google Play Billing + a real subscription product.
