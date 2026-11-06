# Masroofi AI (مصروفي) — Personal Finance & AI Expense Tracker

Masroofi AI (مصروفي) is a local-first, privacy-conscious native Android personal finance application tailored for Egyptian Arabic and bilingual users. It streamlines daily expense and income tracking by combining a fast manual ledger with natural-language multi-transaction AI entry (via text or voice) backed by strict local validation, exact minor-unit monetary arithmetic, and Room persistence.

## User Review & Critical Decisions

> [!IMPORTANT]
> Below are the recommended product and technical decisions ready for your confirmation before implementation begins:

- **Default AI & Offline Parsing Strategy (Recommended Default)**: Support both configurable **Google Gemini API** and **OpenRouter API** providers via user-supplied API keys stored in Android Keystore-backed encrypted storage, paired with a deterministic **Egyptian Arabic & Bilingual Local Smart Parser fallback** so multi-transaction entry (e.g., *"اشتريت طماطم بعشرين جنيه وعلبة جبنة بـ 45 جنيه"*) also works immediately out-of-the-box and offline when no API key is configured, while clearly indicating whether a parse came from the configured AI provider or local rules.
- **Refund Accounting Model (Recommended Default)**: Allow users to record refunds explicitly as either an **Income** transaction (under a dedicated *"استرداد / Refunds"* income category) or an **Expense Offset / Adjustment** during review, ensuring net cash flow and category totals remain transparent and never silently distort income.
- **Backup Restore Strategy (Recommended Default)**: Before applying a JSON backup via the Storage Access Framework, display a validation summary (record counts, schema check, integrity status) and allow the user to choose between **Replace All Records** (atomic wipe-and-restore inside a database transaction) or **Merge by Stable ID** (preserving existing non-conflicting records).

---

## 1. Overview & Core Concept

- **What It Does**: Enables individuals to record daily expenses and income in seconds—either manually or by speaking/typing natural Egyptian Arabic, standard Arabic, or English sentences containing multiple purchases at once—while maintaining complete local control over categories, budgets, reports, and data backups.
- **Target Audience / Persona**: Everyday Egyptian and Arabic-speaking individuals seeking a fast, lightweight, private financial ledger without bank-linking friction, mandatory accounts, ads, or complex enterprise accounting.
- **Key Value**: Eliminates the tedious one-by-one form entry of daily expenses through a validated AI batch-entry review workflow, while guaranteeing 100% financial accuracy by storing all amounts in integer minor units (piastres/cents) and computing all balances directly from authoritative local Room records.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Home Dashboard Flow**: Launches directly into an RTL Arabic dashboard displaying the calculated Current Balance (Opening Balance + Total Income − Total Expenses), selected month's Income, Expenses, Remaining Budget indicator, a visual Spending-by-Category breakdown, Quick Action buttons (*إضافة مصروف*, *إضافة دخل*, *إدخال ذكي بالـ AI*, *التقارير*), and Recent Transactions.
2. **AI Multi-Transaction & Voice Entry Flow**:
   - User taps the prominent AI Entry button on Home or Transactions.
   - A privacy notice clearly states when text will be sent to the configured external AI provider (only transaction text and eligible category names/IDs are sent).
   - User types multi-item natural language (single sentence or multi-line shorthand) or taps the **Microphone button** (`SpeechRecognizer` with runtime `RECORD_AUDIO` permission handling and live listening indicator) to dictate in Egyptian Arabic (`ar-EG`).
   - User taps **تحليل المعاملات (Analyze)** -> system parses into structured proposals, validates categories against active Room categories, applies learned user category-mapping rules, and flags any ambiguous/missing amounts.
   - **Editable Preview & Batch Save**: Proposals appear in an interactive review list showing total count and batch sum. Users can edit descriptions, amounts, dates, types, or categories, resolve ambiguities, remove items, and commit all valid items atomically in a single database transaction (or use optional Auto-Save when confidence meets threshold and zero fields are ambiguous).
3. **Manual Transaction & Category Management Flow**:
   - Fast modal/screen for single Expense or Income entry with Arabic & Western numeral normalization, decimal validation, date picker defaulting to today, and duplicate-submission protection.
   - Dedicated Category Management screen accessible from Settings and forms to add, rename, pick icons/colors, archive, or safely delete categories (blocked or reassigned if referenced by transactions).
4. **Transactions History & Advanced Search Flow**:
   - Searchable, date-grouped ledger with filters for Date Range, Transaction Type (Income/Expense), Category, Min/Max Amount, and text query. Supports inline editing and confirmed deletion.
5. **Reports, Budgets & Smart Summary Flow**:
   - Period filters (*اليوم*, *هذا الأسبوع*, *هذا الشهر*, *الشهر السابق*, *هذه السنة*, *فترة مخصصة*).
   - Displays Total Income, Total Expenses, Net Cash Flow, interactive Category Donut/Bar Chart (tap a category to drill down into its transactions), Daily Spending Trend chart, and Monthly Comparison (only when prior period data exists).
   - **ملخص ذكي (Smart AI Summary)**: Computes exact aggregates locally first, then optionally sends only those summary numbers to the configured AI provider to generate a concise Arabic financial insight clearly labeled as AI commentary.
6. **Settings, AI Configuration & Backup/Export Flow**:
   - Configure Currency (default `EGP / ج.م`), Opening Balance, Monthly & Per-Category Budgets, AI Provider (`Google Gemini` or `OpenRouter`), masked API Key (encrypted with Android Keystore AES-GCM), configurable Model ID, Connection Test button, Auto-Save toggle, JSON Backup/Restore with preview, and UTF-8 CSV Export.

### Visual Identity & Theme
- **Aesthetic Direction**: Clean, utilitarian, modern Material 3 financial ledger with full Right-to-Left (`LayoutDirection.Rtl`) ergonomics and zero visual clutter.
- **Color Palette & Mood**:
  - **Primary / Brand**: Deep Emerald Teal (`#0F766E` Light / `#2DD4BF` Dark) evoking financial clarity and trust.
  - **Income / Positive Accent**: Restrained Forest Green (`#15803D` Light / `#4ADE80` Dark) on soft green containers (`#DCFCE7` / `#052E16`).
  - **Expense / Outflow Accent**: Restrained Terracotta Coral/Red (`#C2410C` / `#DC2626` Light, `#FB923C` / `#F87171` Dark) on warm muted containers.
  - **Warning / Budget Alert**: Amber Gold (`#B45309` / `#FBBF24`) when budget utilization crosses 80%.
  - **Surfaces**: Warm Alabaster (`#F8FAFC`) in Light mode and Deep Slate (`#0F172A`) in Dark mode with subtle M3 `tonalElevation` cards (`12.dp` corner radius).
- **Typography & Hierarchy**:
  - Locally bundled Google Font **Cairo** (installed via `font-util`) for crisp Arabic & Latin legibility across headers, body copy, and tabular financial numerals.
  - Clear typographic scale: `28.sp` Bold for primary balance figures, `18.sp` SemiBold for section titles, `15.sp` Medium for transaction descriptions, and `13.sp` for metadata/dates.
- **Component Styling & Layout**:
  - Standard Material 3 `NavigationBar` with 4 destinations (*الرئيسية*, *المعاملات*, *التقارير*, *الإعدادات*) respecting `WindowInsets.navigationBars`, switching to `NavigationRail` on expanded tablet widths.
  - Minimum `48.dp` interactive touch targets across all icon buttons, chips, and list actions, with Compose `testTag` attributes on all primary controls.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Exact Integer Minor-Unit Storage (`amountMinorUnits: Long`)**
  - *Chosen Approach*: Store all monetary values in integer minor units (e.g., `1 EGP = 100 piastres`, so `45.50 EGP` is stored as `4550L`) alongside currency code.
  - *Why*: Eliminates IEEE 754 floating-point rounding errors across multi-transaction sums, net cash flow, and percentage calculations.
  - *Alternatives Considered*: `Double` or `Float` (rejected due to precision drift); string-only storage (rejected because SQLite `SUM()` aggregations in Room queries require numeric types).
- **Decision 2: Android Keystore AES-GCM Encryption for User API Keys**
  - *Chosen Approach*: Encrypt user-entered Gemini and OpenRouter API keys using an `AES/GCM/NoPadding` secret key generated inside the hardware-backed `AndroidKeyStore`, storing only the Base64 ciphertext and IV in local preferences, while excluding credentials from all logs and JSON backups.
  - *Why*: Protects credentials at rest without introducing deprecated third-party security wrappers, while transparently informing the user about client-side device compromises.
- **Decision 3: Deterministic Category Validation & Learned Keyword Memory**
  - *Chosen Approach*: Pass only active (non-archived) categories (`id`, `name`, `type`) to the AI provider, strictly verify every returned `categoryId` against Room, fall back to an explicit `Uncategorized (غير مصنف)` state if invalid or uncertain, and persist a local `CategoryRule` whenever the user manually corrects a proposed category for a keyword.
  - *Why*: Guarantees the AI never invents categories or corrupts foreign keys, while making repeat items (e.g., *"طماطم"*, *"تاكسي"*) increasingly accurate over time even offline.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌────────────────────────────────────────────────────────────────────────────┐
│                        UI Layer (Jetpack Compose + RTL)                    │
│  ┌──────────────┐  ┌──────────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │ HomeScreen   │  │ Transactions     │  │ Reports      │  │ Settings & │  │
│  │ & Quick      │  │ Screen & Search  │  │ & Custom     │  │ Categories │  │
│  │ Actions      │  │ Filters          │  │ ComposeChart │  │ & Backup   │  │
│  └──────┬───────┘  └────────┬─────────┘  └──────┬───────┘  └─────┬──────┘  │
│         └───────────────────┼───────────────────┼────────────────┘         │
│                             ▼                   ▼                          │
│         ┌───────────────────────────────────────────────────────┐          │
│         │   AI Multi-Transaction Entry & SpeechRecognizer Sheet │          │
│         │   (Live Voice Input -> Editable Preview -> Batch Save)│          │
│         └───────────────────────────┬───────────────────────────┘          │
└─────────────────────────────────────┼──────────────────────────────────────┘
                                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                     ViewModel Layer (StateFlow + MVVM)                     │
│  ┌─────────────────────────┐  ┌────────────────────┐  ┌─────────────────┐  │
│  │ FinanceViewModel        │  │ AiEntryViewModel   │  │ Settings &      │  │
│  │ (Balances, Ledger,      │  │ (Voice State,      │  │ BackupViewModel │  │
│  │  Budgets, Analytics)    │  │  Drafts, Batch ID) │  │ (Keystore, SAF) │  │
│  └────────────┬────────────┘  └─────────┬──────────┘  └────────┬────────┘  │
└───────────────┼─────────────────────────┼──────────────────────┼───────────┘
                ▼                         ▼                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                   Domain, AI Service & Repository Layer                    │
│  ┌──────────────────────────────┐    ┌──────────────────────────────────┐  │
│  │ FinanceRepository            │    │ AiTransactionService             │  │
│  │ • Atomic Batch Inserts       │    │ • Numeral Normalizer (٠-٩ -> 0-9)│  │
│  │ • Idempotency Operation IDs  │    │ • Schema & Category ID Validator │  │
│  │ • Learned Category Rules     │    │ • Learned Keyword Matcher        │  │
│  │ • JSON Backup & CSV Exporter │    │ • GeminiProvider / OpenRouter    │  │
│  └──────────────┬───────────────┘    │ • Local Arabic Smart Fallback    │  │
│                 │                    └──────────────────────────────────┘  │
└─────────────────┼──────────────────────────────────────────────────────────┘
                  ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                 Local Persistence Layer (Room + DataStore)                 │
│  ┌────────────────┐ ┌────────────────┐ ┌───────────────┐ ┌──────────────┐  │
│  │ Transaction    │ │ CategoryEntity │ │ BudgetEntity  │ │ Keystore &   │  │
│  │ Entity (FK     │ │ (Expense /     │ │ (Overall &    │ │ DataStore    │  │
│  │ SET NULL/      │ │  Income, Icon, │ │  Per-Category │ │ Preferences  │  │
│  │ RESTRICT)      │ │  Archived)     │ │  Monthly)     │ │ (OpeningBal) │  │
│  └────────────────┘ └────────────────┘ └───────────────┘ └──────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State
- **`CategoryEntity`**: `id` (stable String UUID/slug), `name` (Arabic label), `type` (`EXPENSE` or `INCOME`), `iconName`, `colorHex`, `isArchived`, `isDefault`, `createdAt`. Seeded on first launch with the 10 default Arabic expense categories (*أكل وشرب*, *طلبات البيت*, *مواصلات*, *دراسة*, *صحة وأدوية*, *رياضة*, *ملابس*, *ترفيه*, *فواتير واشتراكات*, *أخرى*) and default income categories (*مرتب*, *عمل حر*, *مكافآت وهدايا*, *استرداد*, *دخل آخر*).
- **`TransactionEntity`**: `id` (UUID), `description`, `amountMinorUnits` (`Long`), `currencyCode` (`String`, default `"EGP"`), `type` (`EXPENSE` or `INCOME`), `categoryId` (nullable foreign key to `CategoryEntity` with `ON DELETE SET NULL` and safety checks before category deletion), `transactionDate` (epoch millis normalized to day/time), `createdAt`, `updatedAt`, `notes`, `source` (`MANUAL`, `AI_BATCH`, `IMPORT`), `batchOperationId` (indexed for idempotency so retries never duplicate saves).
- **`BudgetEntity`**: `id`, `yearMonth` (`"YYYY-MM"`), `categoryId` (nullable — `null` represents the overall monthly spending budget), `limitMinorUnits` (`Long`), `updatedAt`.
- **`CategoryRuleEntity`**: `keyword` (normalized Arabic/English token), `categoryId`, `transactionType`, `updatedAt` — stores user category corrections so the parser remembers preferences locally.
- **Preferences & Secure Credentials (`DataStore` + `AndroidKeyStore`)**: Stores `currencyCode` (`EGP`), `openingBalanceMinorUnits` (`Long`, default `0L` with UI note when unset), `aiEnabled` (`Boolean`), `selectedProvider` (`GEMINI` or `OPENROUTER`), `geminiModelId` (default `"gemini-2.5-flash"`), `openRouterModelId` (default `"google/gemini-2.5-flash"`), `autoSaveEnabled` (`Boolean`, default `false`), `autoSaveConfidenceThreshold` (`Float`, default `0.90f`), and AES-GCM encrypted API keys.

### Interactive Component & State Mapping
- **Numeral & Input Normalization**: Every amount and text input automatically converts Eastern Arabic numerals (`٠١٢٣٤٥٦٧٨٩`) and Arabic decimal separators into canonical digits before validation or AI parsing.
- **AI Parsing & Validation Pipeline**:
  1. Constructs a minimal prompt containing the current date, user text, and compact active category list (`id`, `name`, `type`).
  2. Executes HTTP call via OkHttp/Moshi to either **Google Gemini REST API** (`v1beta/models/{model}:generateContent` with `responseMimeType = "application/json"`) or **OpenRouter Chat Completions API** (`https://openrouter.ai/api/v1/chat/completions`), or routes to the local Egyptian Arabic parser when offline/unconfigured.
  3. Parses and validates JSON strictly: verifies `amount > 0`, valid `type`, valid `date`, and checks `categoryId` against active database IDs (overriding with learned `CategoryRuleEntity` matches or setting `categoryId = null` / `Uncategorized` if unknown).
  4. Marks `needsConfirmation = true` on any item with missing/zero amount, uncategorized state, or confidence below threshold, preventing Auto-Save from committing ambiguous items.
- **Atomic Batch Persistence**: Uses Room `@Transaction` with `batchOperationId` deduplication so saving a multi-transaction preview either commits all items together or rolls back cleanly on error.
- **Automated Verification Suite**: Unit and Robolectric tests covering financial calculations (opening balance, net cash flow, zero-denominator category percentages, budget alerts), manual entry validation, Egyptian Arabic single-line and multi-line AI parsing edge cases, Room foreign-key & batch-save invariants, backup/restore integrity, and RTL Compose UI flows.
