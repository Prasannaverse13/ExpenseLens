# Build & run guide

## Quickest path

1. Install **Android Studio Hedgehog** (or newer) from
   <https://developer.android.com/studio>.
2. Open the project root (`ExpenseLens/`) in Android Studio. Wait for Gradle
   sync to finish.
3. Plug in a phone with USB debugging enabled, or start an emulator (API 26+).
4. Press **Run ▶**.

## Command-line build

```bash
cd ExpenseLens
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Common gotchas

- **Wrong JDK**: Studio bundles a JDK. If you build from the shell, ensure
  `JAVA_HOME` points to a JDK 17 install.
- **No SDK**: `local.properties` is missing by default. Studio writes it for
  you on first sync. If you build from the shell and the file is missing,
  create it with `sdk.dir=/path/to/Android/sdk`.
- **Tesseract fallback**: without `assets/tessdata/eng.traineddata` the app
  silently uses ML Kit only. This is fine for most receipts.

## Debugging

- `adb logcat | grep ExpenseLens` — app logs
- `adb logcat | grep MLKit` — OCR pipeline logs

## Where data lives

- Database: `/data/data/com.expenselens/databases/expense_lens.db`
- Original bills: `/data/data/com.expenselens/files/bills/`
- Exports: `/data/data/com.expenselens/files/exports/`
- Drafts (in-progress extractions): `/data/data/com.expenselens/files/drafts/`
