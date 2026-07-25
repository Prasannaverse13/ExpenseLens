# ExpenseLens — AI-Powered Expense Management (Android, Kotlin)

A free Android app that captures bills (camera or file), uses on-device OCR + an
optional LLM to extract structured data, and auto-categorises into the seven
required expense buckets:

- Food Cost
- Packaging Cost
- Electricity
- Staff Salary
- Staff Rent
- Shop Rent
- Miscellaneous

## What it does

- **Capture** — CameraX live capture, or upload PDF / Word / image (JPG, PNG, HEIC).
- **OCR** — ML Kit Text Recognition v2 by default; Tesseract4Android fallback
  for thermal paper or low-confidence scans.
- **Extract** — heuristic parser pulls vendor, bill number, date, totals, tax,
  and line items from the OCR text.
- **Categorise** — keyword classifier; learns from your corrections over time.
- **Review** — edit any field, change categories per line item, save.
- **Dashboard** — daily / weekly / monthly summaries, charts, recent list.
- **List & Search** — filter by category, search by vendor / bill / notes.
- **Export** — CSV, XLSX, PDF.
- **Manual entry** — full form for bills you can't scan.
- **Backup** — local auto-backup (DataStore) plus Android system backup rules.
  Optional cloud LLM endpoint is configurable in Settings.

## Tech stack

- Kotlin 1.9.24, AGP 8.5.2, Gradle 8.7
- Jetpack Compose (Material 3), Navigation-Compose
- MVVM + Hilt + Coroutines + Flow
- Room 2.6.1
- CameraX 1.3.4
- ML Kit Text Recognition v2 (on-device)
- Tesseract4Android (optional fallback)
- Vico 1.14.0 (charts)
- Apache PDFBox-Android, Apache POI, OpenCSV, iText-compatible PDF
- DataStore for preferences

## Build

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34, JDK 17
- A device or emulator running Android 8.0 (API 26) or newer

### Steps
1. Open Android Studio → **File → Open** → select the `ExpenseLens` folder.
2. Let Gradle sync (this downloads everything declared in `libs.versions.toml`).
3. Connect a device with USB debugging on, or start an emulator.
4. **Run → Run 'app'** to build and deploy the debug APK.
5. The first launch will request the camera permission.

### Build from the command line
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

```
app/src/main/java/com/expenselens/
├── App.kt                  Hilt application
├── MainActivity.kt         Single Compose activity
├── di/                     Hilt modules
├── data/
│   ├── db/                 Room: entities, DAOs, database
│   ├── prefs/              DataStore preferences
│   ├── storage/            Bill storage helper
│   └── repo/               ExpenseRepository
├── domain/model/           Pure-Kotlin domain types
├── ocr/                    ML Kit + Tesseract adapters + fallback
├── extract/                DocumentTextExtractor, ReceiptParser, LlmExtractor
├── categorize/             KeywordCategoryClassifier (and composite wrapper)
├── export/                 CSV / XLSX / PDF exports
└── ui/
    ├── ExpenseLensRoot.kt  Theme + navigation
    ├── common/             Reusable components & formatting
    └── screen/             Dashboard, Capture, Review, Manual, List, Detail, Settings
```

## How the AI categorisation learns

Every time you save an expense, the `VendorCorrection` table records
`vendorKey → categoryId`. The next time the same vendor (case-insensitive,
prefix-matched) shows up, the Review screen pre-selects that category. You can
still change it; the correction updates its `hitCount`.

## Optional cloud LLM

In **Settings**, paste an OpenAI-compatible endpoint (e.g. `https://api.openai.com/v1/chat/completions`)
and your API key. The next time you process a bill, `LlmExtractor` is consulted
first; if it returns a valid structure we use that. If you leave the fields
blank, the app stays 100% on-device.

## Tests

```bash
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # Instrumented
```

`ReceiptParserTest` covers vendor/date/amount/tax extraction plus classification
rules for all seven categories.

## Optional: Tesseract data

For Tesseract fallback OCR, drop `eng.traineddata` into
`app/src/main/assets/tessdata/`. Without it, the app still works — ML Kit
handles everything.
