# Masroofi AI (مصروفي الذكي) — Personal Finance & Expense Tracker for Android

**Masroofi AI (مصروفي)** is a local-first, privacy-focused native Android application built with **Kotlin**, **Jetpack Compose**, **Material 3**, **Room**, and **DataStore**. It enables users to record daily expenses and income effortlessly—either manually or by describing multiple transactions at once in **Egyptian Arabic, Standard Arabic, or English** via text or speech-to-text.

---

## 1. SDK & Build Specifications

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose + Material 3 (Full Right-to-Left `LayoutDirection.Rtl` support + bundled `Cairo` Arabic font)
- **Architecture**: MVVM (`MasroofiViewModel` + `FinanceRepository` + `FinancialCalculator` + `AiProviderClient` abstraction)
- **Minimum Android SDK (`minSdk`)**: `24` (Android 7.0 Nougat — supports >99% of active Android devices, `AndroidKeyStore` AES-GCM, Room, and `SpeechRecognizer`)
- **Target & Compile SDK (`targetSdk` / `compileSdk`)**: `36`
- **Application ID**: `com.aistudio.masroofiai.egxfin`

---

## 2. Implemented Features

1. **RTL Arabic Home Dashboard**:
   - Displays **Current Cash Balance** calculated strictly from `Opening Balance + Saved Income - Saved Expenses` (never includes unsaved AI suggestions).
   - Displays **Monthly Income**, **Monthly Expenses**, **Remaining Monthly Budget** with progress indicators, **Spending by Category** donut & bar chart, **Quick Actions**, and **Recent Transactions**.
2. **AI-Powered Multi-Transaction & Speech-to-Text Entry**:
   - Parses multiple transactions in a single Egyptian Arabic/Arabic/English sentence (e.g., *"اشتريت طماطم بعشرين جنيه وعلبة جبنة بخمسة وأربعين جنيه ودفعت تاكسي عشرين جنيه"*) or multi-line shorthand (*"طماطم 20\nجبنة 45"*).
   - Built-in **Speech-to-Text (`SpeechRecognizer`)** with runtime `RECORD_AUDIO` permission handling, live listening status, and editable recognized text without sending raw audio to external AI providers.
   - **Editable Transaction Review Screen**: Displays item count and batch sum, allows editing descriptions, amounts, dates, types, and categories, flags ambiguous/missing amounts, and commits valid batches atomically in a single Room `@Transaction` with idempotency (`batchOperationId`).
   - Optional **Auto-Save Mode** (disabled by default) that only auto-saves when all items pass validation, have matched categories, and exceed the user's confidence threshold.
3. **User-Controlled Categories & Learned Preferences**:
   - Pre-seeded with 10 default Arabic expense categories and 5 income categories.
   - Full CRUD: add, rename, pick icon/color, archive/unarchive, view category totals, and safely delete categories (`ForeignKey.SET_NULL` preserves transaction history).
   - The AI parser never creates categories automatically and remembers user category corrections locally (`CategoryRuleEntity`).
4. **AI Provider Abstraction (`Google Gemini` & `OpenRouter` + Local Parser)**:
   - Supports **Google Gemini REST API** and **OpenRouter Chat Completions API** with user-configured model IDs and connection testing.
   - User-supplied API keys are encrypted via **Android Keystore (`AES/GCM/NoPadding`)**, masked in the UI, never logged, and never exported in backups.
   - Works completely offline for all financial operations and includes a deterministic **Egyptian Arabic Local Smart Parser** when no external API key is configured.
5. **Reports, Analytics & Optional Smart Summary (`ملخص ذكي`)**:
   - Filters for Today, This Week, This Month, Previous Month, This Year, and Custom Date Range.
   - Interactive Category Breakdown with tap-to-drill-down transactions, Daily Spending Trend chart, and Monthly Comparison (shown only when prior comparison data exists).
   - Optional **ملخص ذكي** computes exact aggregates locally first before requesting a short Arabic summary.
6. **Searchable Transaction History**:
   - Filter by search query, Expense/Income type, Category, Min/Max amount, and Start/End date, grouped by date with daily net totals.
7. **Local JSON Backup/Restore & UTF-8 CSV Export**:
   - Export/Import structured JSON backups via Android Storage Access Framework (`ActivityResultContracts`) with pre-restore validation preview and choice between **Merge** and **Replace All**.
   - Export transactions to UTF-8 BOM CSV compatible with Excel and Google Sheets.

---

## 3. Setup, Build & Test Instructions

### Building the Debug APK via Gradle
```bash
gradle assembleDebug
```
The generated installable APK is located at:
`app/build/outputs/apk/debug/app-debug.apk`

### Running Automated Unit & Robolectric Tests
```bash
gradle :app:testDebugUnitTest
```

---

## 4. Known Limitations & Security Notes

- **Client-Side API Keys**: User-entered API keys are encrypted at rest using Android Keystore (`AES/GCM/NoPadding`), but client-side storage on a rooted or compromised device cannot guarantee absolute protection against physical extraction.
- **Local Room Database Storage**: Financial records are stored locally in SQLite via Room inside the app's private sandbox directory. Standard SQLite files are not encrypted at rest unless full-disk/file-based encryption (enabled by default on modern Android devices) is active.
- **Speech Recognition Availability**: Voice dictation relies on the device's installed Android `RecognitionService` (such as Google Speech Services) and language packs (`ar-EG`).
