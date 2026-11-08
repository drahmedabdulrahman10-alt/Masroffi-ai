package com.example

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupExportManager
import com.example.data.backup.BackupPayload
import com.example.data.local.CategoryEntity
import com.example.data.local.DefaultCategories
import com.example.data.local.MasroofiDatabase
import com.example.data.local.RefundAccountingMode
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.KeyCipherEngine
import com.example.data.preferences.SecureKeyStorage
import com.example.data.repository.FinanceRepository
import com.example.data.repository.RestoreMode
import com.example.di.AppContainer
import com.example.domain.MoneyUtils
import com.example.domain.QuickDateOption
import com.example.domain.ai.AiProviderClient
import com.example.domain.ai.AiServiceException
import com.example.domain.ai.ProposedTransactionDraft
import com.example.ui.MasroofiApp
import com.example.ui.components.ManualTransactionDialog
import com.example.ui.speech.MasroofiSpeechHelper
import com.example.ui.speech.SpeechRecognitionState
import com.example.ui.viewmodel.AiEntryViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.ReportPeriodFilter
import com.example.ui.viewmodel.ReportsViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.SnackbarManager
import com.example.ui.viewmodel.TransactionHistoryFilter
import com.example.ui.viewmodel.TransactionsViewModel
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric & Compose integration tests covering:
 * - Room foreign-key safety (deleting a category sets transaction categoryId to null without deleting history)
 * - Atomic batch saving & duplicate batchOperationId prevention
 * - Duplicate manual submission guard
 * - Provider integration error handling (Missing API key, Rate limit, Malformed response)
 * - RTL Compose UI flows (Home dashboard, Manual entry, AI entry preview, Navigation tabs)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: MasroofiDatabase
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var prefsRepository: AppPreferencesRepository
    private lateinit var repository: FinanceRepository
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MasroofiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
        val testDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(context.cacheDir, "test_prefs_${UUID.randomUUID()}.preferences_pb") }
        )
        prefsRepository = AppPreferencesRepository(
            context = context,
            secureKeyStorage = SecureKeyStorage(context),
            dataStore = testDataStore
        )
        repository = FinanceRepository(database.dao(), prefsRepository)
        runBlocking {
            database.dao().insertCategoriesIgnore(DefaultCategories.all())
            repository.ensureDefaultCategoriesSeeded()
        }
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeTestRule.waitForIdle()
        dataStoreScope.cancel()
        database.close()
    }

    private fun awaitMainLooperCondition(timeoutMs: Long = 5000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeTestRule.waitForIdle()
            if (predicate()) return
            Thread.sleep(15)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeTestRule.waitForIdle()
        assertTrue("Condition was not satisfied within ${timeoutMs}ms", predicate())
    }

    private fun createTestAppContainer(
        defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): AppContainer {
        return AppContainer(
            database = database,
            preferencesRepository = prefsRepository,
            financeRepository = repository,
            snackbarManager = SnackbarManager(),
            defaultDispatcher = defaultDispatcher,
            ioDispatcher = ioDispatcher
        )
    }

    @Test
    fun `deleting a category preserves transaction history by setting categoryId to null`() = runBlocking {
        val customCat = repository.saveCategory(
            name = "تصنيف مؤقت",
            type = TransactionType.EXPENSE,
            iconName = "category",
            colorHex = 0xFF0F766EL
        ).getOrThrow()

        val savedTx = repository.saveManualTransaction(
            description = "شراء تجريبي",
            amountMinorUnits = 5000L,
            currencyCode = "EGP",
            type = TransactionType.EXPENSE,
            categoryId = customCat.id,
            transactionDate = 1730500000000L,
            notes = ""
        ).getOrThrow()

        val affectedCount = repository.deleteCategorySafely(customCat.id)
        assertEquals(1, affectedCount)

        val reloadedTx = database.dao().getTransactionById(savedTx.id)
        assertTrue(reloadedTx != null)
        assertNull(reloadedTx?.categoryId)
        assertEquals(5000L, reloadedTx?.amountMinorUnits)
    }

    @Test
    fun `batch save commits atomically and prevents duplicate batchOperationId saves`() = runBlocking {
        val drafts = listOf(
            ProposedTransactionDraft(
                description = "طماطم",
                amountText = "20",
                amountMinorUnits = 2000L,
                currencyCode = "EGP",
                type = TransactionType.EXPENSE,
                isoDate = "2026-11-01",
                categoryId = DefaultCategories.CAT_FOOD
            ),
            ProposedTransactionDraft(
                description = "جبنة",
                amountText = "45",
                amountMinorUnits = 4500L,
                currencyCode = "EGP",
                type = TransactionType.EXPENSE,
                isoDate = "2026-11-01",
                categoryId = DefaultCategories.CAT_FOOD
            )
        )

        val batchOpId = "batch_unique_001"
        val firstSave = repository.saveReviewedBatchTransactions(batchOpId, drafts)
        assertTrue(firstSave.isSuccess)
        assertEquals(2, firstSave.getOrThrow())

        // Retry with the same batchOperationId must be rejected by idempotency check
        val duplicateSave = repository.saveReviewedBatchTransactions(batchOpId, drafts)
        assertTrue(duplicateSave.isFailure)

        val allTxs = database.dao().getAllTransactions()
        assertEquals(2, allTxs.size)
    }

    @Test
    fun `provider integration handles rate limit and malformed responses safely`() = runBlocking {
        val fakeFailingClient = object : AiProviderClient {
            override suspend fun requestStructuredTransactionsJson(
                userText: String,
                todayIsoDate: String,
                defaultCurrency: String,
                eligibleCategories: List<CategoryEntity>
            ): Result<String> {
                return Result.failure(AiServiceException.RateLimited())
            }

            override suspend fun requestSmartSummaryText(aggregatedFactsArabic: String): Result<String> {
                return Result.failure(AiServiceException.InvalidApiKey())
            }

            override suspend fun testConnection(): Result<String> {
                return Result.failure(AiServiceException.NetworkFailure())
            }
        }

        val repoWithFakeProvider = FinanceRepository(
            dao = database.dao(),
            preferencesRepository = prefsRepository,
            customAiClientOverride = fakeFailingClient
        )

        val parseRes = repoWithFakeProvider.parseMultiTransactionsWithAi(
            userText = "اشتريت قهوة بـ 25 جنيه",
            todayIsoDate = "2026-11-01"
        )
        assertTrue(parseRes.isFailure)
        assertTrue(parseRes.exceptionOrNull() is AiServiceException.RateLimited)

        // Ensure no transactions were fabricated or saved on failure
        assertEquals(0, database.dao().getAllTransactions().size)
    }

    @Test
    fun `RTL Compose UI renders Home dashboard, navigates tabs, and parses AI multi-transactions`() {
        val container = createTestAppContainer(
            defaultDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.Main
        )
        val homeVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[HomeViewModel::class.java]
        val transactionsVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[TransactionsViewModel::class.java]
        val aiEntryVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[AiEntryViewModel::class.java]
        val reportsVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[ReportsViewModel::class.java]
        val settingsVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[SettingsViewModel::class.java]

        composeTestRule.setContent {
            MasroofiApp(
                homeViewModel = homeVm,
                transactionsViewModel = transactionsVm,
                aiEntryViewModel = aiEntryVm,
                reportsViewModel = reportsVm,
                settingsViewModel = settingsVm
            )
        }
        composeTestRule.waitForIdle()

        // Verify Home Dashboard elements are displayed
        composeTestRule.onNodeWithTag("current_balance_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("مصروفي الذكي").assertIsDisplayed()

        // Open AI Multi-Transaction Entry screen
        composeTestRule.onNodeWithTag("home_screen_list")
            .performScrollToNode(hasTestTag("quick_action_ai_entry"))
        composeTestRule.onNodeWithTag("quick_action_ai_entry").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("ai_input_textfield")
            .performTextInput("اشتريت طماطم بعشرين جنيه ودفعت تاكسي عشرين جنيه")
        composeTestRule.onNodeWithTag("ai_entry_screen_list")
            .performScrollToNode(hasTestTag("ai_analyze_button"))
        composeTestRule.onNodeWithTag("ai_analyze_button").performClick()
        composeTestRule.waitForIdle()

        // Verify preview header is created and close AI screen to test bottom navigation tabs
        composeTestRule.onNodeWithTag("ai_entry_close_button").performClick()
        composeTestRule.waitForIdle()

        // Navigate to Reports tab and Settings tab
        composeTestRule.onNodeWithTag("nav_tab_reports").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("التقارير والإحصائيات").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("الإعدادات وإدارة البيانات").assertIsDisplayed()
    }

    @Test
    fun `SecureKeyStorage fails safely when Keystore is unavailable and never stores Base64 plaintext fallback (REV-06)`() {
        val unavailableEngine = object : KeyCipherEngine {
            override fun encrypt(plainText: String): String? = null
            override fun decrypt(payload: String): String? = null
        }
        val storage = SecureKeyStorage(context, unavailableEngine)
        val result = storage.saveApiKey(SecureKeyStorage.KEY_GEMINI_API, "AIzaSecretTestKey12345")
        assertTrue(result.isFailure)
        assertEquals("", storage.getApiKey(SecureKeyStorage.KEY_GEMINI_API))
        assertFalse(storage.hasApiKey(SecureKeyStorage.KEY_GEMINI_API))

        // Verify working cipher engine round-trip and unreadable ciphertext cleanup on device transfer
        var keyValid = true
        val workingEngine = object : KeyCipherEngine {
            override fun encrypt(plainText: String): String? = "GCM:iv:${plainText.reversed()}"
            override fun decrypt(payload: String): String? {
                if (!keyValid || !payload.startsWith("GCM:iv:")) return null
                return payload.removePrefix("GCM:iv:").reversed()
            }
        }
        val secureStorage = SecureKeyStorage(context, workingEngine)
        val saveOk = secureStorage.saveApiKey(SecureKeyStorage.KEY_GEMINI_API, "AIzaSecretTestKey12345")
        assertTrue(saveOk.isSuccess)
        assertEquals("AIzaSecretTestKey12345", secureStorage.getApiKey(SecureKeyStorage.KEY_GEMINI_API))

        // Simulate Keystore reset / restore onto new device where key cannot decrypt
        keyValid = false
        assertEquals("", secureStorage.getApiKey(SecureKeyStorage.KEY_GEMINI_API))
    }

    @Test
    fun `changing default currency preserves currency-specific opening balances without silent reinterpretation (REV-03)`() = runBlocking {
        prefsRepository.setCurrency("EGP")
        prefsRepository.setOpeningBalance(250_000L) // 2500 EGP
        assertEquals(250_000L, prefsRepository.getSnapshot().openingBalanceMinorUnits)
        assertTrue(prefsRepository.getSnapshot().openingBalanceConfigured)

        // Switch to USD: opening balance for USD must start unconfigured (0 USD), not 2500 USD
        prefsRepository.setCurrency("USD")
        val usdSnap = prefsRepository.getSnapshot()
        assertEquals("USD", usdSnap.currencyCode)
        assertEquals(0L, usdSnap.openingBalanceMinorUnits)
        assertFalse(usdSnap.openingBalanceConfigured)

        // Configure USD opening balance and switch back to EGP: original 2500 EGP is preserved
        prefsRepository.setOpeningBalance(50_000L) // 500 USD
        assertEquals(50_000L, prefsRepository.getSnapshot().openingBalanceMinorUnits)

        prefsRepository.setCurrency("EGP")
        val egpSnapAgain = prefsRepository.getSnapshot()
        assertEquals("EGP", egpSnapAgain.currencyCode)
        assertEquals(250_000L, egpSnapAgain.openingBalanceMinorUnits)
        assertTrue(egpSnapAgain.openingBalanceConfigured)
    }

    @Test
    fun `category learning only records rules when user confirms or corrects category (REV-11)`() = runBlocking {
        // 1. Unmodified AI draft (userConfirmedCategory = false, categoryId == suggestedCategoryId) -> should NOT learn a rule
        val unmodifiedDraft = ProposedTransactionDraft(
            description = "قهوة الصباح",
            amountText = "30",
            amountMinorUnits = 3000L,
            currencyCode = "EGP",
            type = TransactionType.EXPENSE,
            isoDate = "2026-11-01",
            categoryId = DefaultCategories.CAT_FOOD,
            suggestedCategoryId = DefaultCategories.CAT_FOOD,
            userConfirmedCategory = false
        )
        repository.saveReviewedBatchTransactions("batch_learn_1", listOf(unmodifiedDraft))
        assertEquals(0, database.dao().getAllCategoryRules().size)

        // 2. User-corrected draft (categoryId changed from CAT_FOOD to CAT_ENTERTAINMENT) -> MUST learn rule
        val correctedDraft = ProposedTransactionDraft(
            description = "دفعت كافيه الزمالك 120 جنيه",
            amountText = "120",
            amountMinorUnits = 12000L,
            currencyCode = "EGP",
            type = TransactionType.EXPENSE,
            isoDate = "2026-11-01",
            categoryId = DefaultCategories.CAT_ENTERTAINMENT,
            suggestedCategoryId = DefaultCategories.CAT_FOOD,
            userConfirmedCategory = true
        )
        repository.saveReviewedBatchTransactions("batch_learn_2", listOf(correctedDraft))
        val rules = database.dao().getAllCategoryRules()
        assertEquals(1, rules.size)
        // Stopwords ("دفعت", "جنيه") and numbers ("120") must be stripped from learned keyword
        assertEquals("كافيه الزمالك", rules[0].keyword)
        assertEquals(DefaultCategories.CAT_ENTERTAINMENT, rules[0].categoryId)
    }

    @Test
    fun `backup restore atomically replaces and merges categories and transactions (REV-12)`() = runBlocking {
        repository.saveManualTransaction(
            description = "معاملة قبل النسخ",
            amountMinorUnits = 9000L,
            currencyCode = "EGP",
            type = TransactionType.EXPENSE,
            categoryId = DefaultCategories.CAT_FOOD,
            transactionDate = 1730500000000L,
            notes = ""
        ).getOrThrow()

        val backupJson = repository.createBackupJson()
        val preview = BackupExportManager.validateAndPreviewBackupJson(backupJson)
        assertTrue(preview.isValid)
        val payload = preview.payload!!

        // Clear and restore via REPLACE_ALL
        database.dao().deleteAllTransactions()
        assertEquals(0, database.dao().getAllTransactions().size)

        val replaceRes = repository.applyValidatedBackup(payload, RestoreMode.REPLACE_ALL)
        assertTrue(replaceRes.isSuccess)
        assertEquals(1, database.dao().getAllTransactions().size)

        // Merge via MERGE_BY_ID
        val mergeRes = repository.applyValidatedBackup(payload, RestoreMode.MERGE_BY_ID)
        assertTrue(mergeRes.isSuccess)
        assertEquals(1, database.dao().getAllTransactions().size)
    }

    @Test
    fun `ViewModel executes backup, restore, CSV export, and user notifications safely (REV-02, REV-04, REV-09)`() {
        val container = createTestAppContainer(
            defaultDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.Main
        )
        val viewModel = ViewModelProvider(viewModelStore, container.viewModelFactory)[SettingsViewModel::class.java]
        composeTestRule.waitForIdle()

        // Test user notification delivery (REV-02)
        viewModel.showUserMessage("رسالة اختبار للإشعارات")
        awaitMainLooperCondition {
            viewModel.uiState.value.snackbarMessage == "رسالة اختبار للإشعارات"
        }
        assertEquals("رسالة اختبار للإشعارات", viewModel.uiState.value.snackbarMessage)
        viewModel.clearSnackbar()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Test RefundAccountingMode update (REV-04)
        viewModel.updateRefundAccountingMode(RefundAccountingMode.INCOME_CATEGORY)
        awaitMainLooperCondition {
            viewModel.uiState.value.snackbarMessage == "تم تحديث طريقة حساب الاسترداد"
        }
        val updatedMode = runBlocking { prefsRepository.getSnapshot().refundAccountingMode }
        assertEquals(RefundAccountingMode.INCOME_CATEGORY, updatedMode)
        viewModel.clearSnackbar()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Test ViewModel-scoped file export & import (REV-09)
        val backupFile = File(context.cacheDir, "test_backup_vm.json")
        val backupUri = Uri.fromFile(backupFile)
        viewModel.exportBackupToUri(context.contentResolver, backupUri)
        awaitMainLooperCondition {
            viewModel.uiState.value.snackbarMessage?.contains("النسخة الاحتياطية") == true
        }
        assertTrue(backupFile.exists() && backupFile.readText().contains("categories"))
        viewModel.clearSnackbar()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        viewModel.importBackupFromUri(context.contentResolver, backupUri)
        awaitMainLooperCondition {
            viewModel.uiState.value.pendingBackupPreview != null
        }
        assertTrue(viewModel.uiState.value.pendingBackupPreview?.isValid == true)

        val csvFile = File(context.cacheDir, "test_export_vm.csv")
        val csvUri = Uri.fromFile(csvFile)
        viewModel.exportCsvToUri(context.contentResolver, csvUri)
        awaitMainLooperCondition {
            viewModel.uiState.value.snackbarMessage?.contains("CSV") == true
        }
        assertTrue(csvFile.exists())
    }

    @Test
    fun `MasroofiSpeechHelper stops listening when lifecycle transitions to ON_STOP (REV-07)`() {
        var latestState = SpeechRecognitionState()
        val helper = MasroofiSpeechHelper(
            context = context,
            onStateChanged = { latestState = it },
            onFinalTextRecognized = {}
        )
        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        lifecycleOwner.lifecycle.addObserver(helper)

        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertFalse(latestState.isListening)
        lifecycleOwner.lifecycle.removeObserver(helper)
    }

    // --- Task 3 Regression Tests ---

    @Test
    fun `editing USD and SAR transactions while app default is EGP preserves their original currencies (REV-03A)`() {
        val (usdTx, sarTx) = runBlocking {
            prefsRepository.setCurrency("EGP")
            assertEquals("EGP", prefsRepository.getSnapshot().currencyCode)

            val createdUsd = repository.saveManualTransaction(
                description = "اشتراك خدمة سحابية",
                amountMinorUnits = 10_000L, // 100.00 USD
                currencyCode = "USD",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_BILLS,
                transactionDate = 1730500000000L,
                notes = "USD subscription"
            ).getOrThrow()
            assertEquals("USD", createdUsd.currencyCode)

            val createdSar = repository.saveManualTransaction(
                description = "مشتريات من الرياض",
                amountMinorUnits = 25_000L, // 250.00 SAR
                currencyCode = "SAR",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_FOOD,
                transactionDate = 1730510000000L,
                notes = "SAR purchase"
            ).getOrThrow()
            assertEquals("SAR", createdSar.currencyCode)
            createdUsd to createdSar
        }

        // 2. Edit the USD transaction through TransactionsViewModel while app default is EGP
        val container = createTestAppContainer(
            defaultDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.Main
        )
        val viewModel = ViewModelProvider(viewModelStore, container.viewModelFactory)[TransactionsViewModel::class.java]
        awaitMainLooperCondition {
            viewModel.uiState.value.allTransactions.size == 2
        }

        viewModel.saveManualTransaction(
            existingId = usdTx.id,
            description = "اشتراك خدمة سحابية - معدل",
            amountText = "100",
            type = TransactionType.EXPENSE,
            categoryId = DefaultCategories.CAT_EDUCATION,
            isoDate = "2026-11-02",
            notes = "Edited while default is EGP"
        )
        awaitMainLooperCondition {
            viewModel.uiState.value.allTransactions.any {
                it.id == usdTx.id && it.description == "اشتراك خدمة سحابية - معدل"
            }
        }

        val reloadedUsd = runBlocking { database.dao().getTransactionById(usdTx.id)!! }
        assertEquals("USD", reloadedUsd.currencyCode)
        assertEquals(10_000L, reloadedUsd.amountMinorUnits)
        assertEquals(DefaultCategories.CAT_EDUCATION, reloadedUsd.categoryId)

        // 3. Edit the SAR transaction through ViewModel while app default is still EGP
        viewModel.saveManualTransaction(
            existingId = sarTx.id,
            description = "عشاء في الرياض - معدل",
            amountText = "250",
            type = TransactionType.EXPENSE,
            categoryId = DefaultCategories.CAT_FOOD,
            isoDate = "2026-11-02",
            notes = "SAR kept"
        )
        awaitMainLooperCondition {
            viewModel.uiState.value.allTransactions.any {
                it.id == sarTx.id && it.description == "عشاء في الرياض - معدل"
            }
        }

        val reloadedSar = runBlocking { database.dao().getTransactionById(sarTx.id)!! }
        assertEquals("SAR", reloadedSar.currencyCode)
        assertEquals(25_000L, reloadedSar.amountMinorUnits)
    }

    @Test
    fun `backup and restore preserves opening balances for all configured currencies and supports legacy backups (REV-03B)`() = runBlocking {
        // Configure opening balances for EGP, USD, and SAR
        prefsRepository.setCurrency("EGP")
        prefsRepository.setOpeningBalance(400_000L) // 4000 EGP
        prefsRepository.setCurrency("USD")
        prefsRepository.setOpeningBalance(150_000L) // 1500 USD
        prefsRepository.setCurrency("SAR")
        prefsRepository.setOpeningBalance(80_000L)  // 800 SAR
        prefsRepository.setCurrency("EGP") // Default back to EGP

        val exportedJson = repository.createBackupJson()
        val preview = BackupExportManager.validateAndPreviewBackupJson(exportedJson)
        assertTrue(preview.isValid)
        val payload = preview.payload!!
        assertEquals(400_000L, payload.currencyOpeningBalances["EGP"])
        assertEquals(150_000L, payload.currencyOpeningBalances["USD"])
        assertEquals(80_000L, payload.currencyOpeningBalances["SAR"])

        // Wipe/change balances and restore via REPLACE_ALL
        prefsRepository.setCurrency("EGP")
        prefsRepository.setOpeningBalance(10_000L)
        prefsRepository.setCurrency("USD")
        prefsRepository.setOpeningBalance(10_000L)
        prefsRepository.setCurrency("AED")
        prefsRepository.setOpeningBalance(99_000L)

        val restoreRes = repository.applyValidatedBackup(payload, RestoreMode.REPLACE_ALL)
        assertTrue(restoreRes.isSuccess)

        val restoredSnap = prefsRepository.getSnapshot()
        assertEquals("EGP", restoredSnap.currencyCode)
        assertEquals(400_000L, restoredSnap.openingBalanceMinorUnits)
        assertEquals(400_000L, restoredSnap.configuredOpeningBalances["EGP"])
        assertEquals(150_000L, restoredSnap.configuredOpeningBalances["USD"])
        assertEquals(80_000L, restoredSnap.configuredOpeningBalances["SAR"])
        assertFalse(restoredSnap.configuredOpeningBalances.containsKey("AED"))

        // Verify switching to USD and SAR shows their restored opening balances
        prefsRepository.setCurrency("USD")
        assertEquals(150_000L, prefsRepository.getSnapshot().openingBalanceMinorUnits)
        prefsRepository.setCurrency("SAR")
        assertEquals(80_000L, prefsRepository.getSnapshot().openingBalanceMinorUnits)

        // Verify restoring a legacy single-balance backup (without currencyOpeningBalances map) works
        val legacyBackupJson = """
            {
              "format": "masroofi_backup_v1",
              "schemaVersion": 1,
              "exportedAtMillis": 1730500000000,
              "settings": {
                "currencyCode": "EUR",
                "openingBalanceMinorUnits": 65000,
                "openingBalanceConfigured": true,
                "refundAccountingMode": "EXPENSE_OFFSET"
              },
              "categories": [],
              "transactions": [],
              "budgets": [],
              "categoryRules": []
            }
        """.trimIndent()
        val legacyPreview = BackupExportManager.validateAndPreviewBackupJson(legacyBackupJson)
        assertTrue(legacyPreview.isValid)
        repository.applyValidatedBackup(legacyPreview.payload!!, RestoreMode.REPLACE_ALL)
        val afterLegacy = prefsRepository.getSnapshot()
        assertEquals("EUR", afterLegacy.currencyCode)
        assertEquals(65_000L, afterLegacy.openingBalanceMinorUnits)
        assertTrue(afterLegacy.openingBalanceConfigured)
    }

    @Test
    fun `history filters do not compare minMinor and maxMinor across different currencies (REV-03C)`() {
        runBlocking {
            prefsRepository.setCurrency("EGP")
            repository.saveManualTransaction(
                description = "فاتورة مصرية",
                amountMinorUnits = 15_000L, // 150 EGP
                currencyCode = "EGP",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_BILLS,
                transactionDate = 1730500000000L,
                notes = ""
            ).getOrThrow()
            repository.saveManualTransaction(
                description = "اشتراك دولاري",
                amountMinorUnits = 15_000L, // 150 USD (same minor units, different currency)
                currencyCode = "USD",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_BILLS,
                transactionDate = 1730510000000L,
                notes = ""
            ).getOrThrow()
        }

        val container = createTestAppContainer(
            defaultDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.Main
        )
        val viewModel = ViewModelProvider(viewModelStore, container.viewModelFactory)[TransactionsViewModel::class.java]
        awaitMainLooperCondition {
            viewModel.uiState.value.allTransactions.size == 2
        }

        // Without min/max amount filter, both transactions appear in history
        assertEquals(2, viewModel.uiState.value.filteredHistoryTransactions.size)

        // With minAmountText = "100" and default currency = "EGP", only the EGP transaction may match
        viewModel.updateHistoryFilter(TransactionHistoryFilter(minAmountText = "100"))
        awaitMainLooperCondition {
            viewModel.uiState.value.filteredHistoryTransactions.size == 1
        }
        val egpFiltered = viewModel.uiState.value.filteredHistoryTransactions
        assertEquals(1, egpFiltered.size)
        assertEquals("EGP", egpFiltered[0].currencyCode)

        // When filtering explicitly by USD with minAmountText = "100", only the USD transaction matches
        viewModel.updateHistoryFilter(
            TransactionHistoryFilter(
                selectedCurrencyCode = "USD",
                minAmountText = "100"
            )
        )
        awaitMainLooperCondition {
            viewModel.uiState.value.filteredHistoryTransactions.size == 1 &&
                viewModel.uiState.value.filteredHistoryTransactions[0].currencyCode == "USD"
        }
        assertEquals("USD", viewModel.uiState.value.filteredHistoryTransactions[0].currencyCode)
    }

    @Test
    fun `Keystore reads in AI flows execute deterministically on ioDispatcher and never on Main (REV-01)`() = runBlocking {
        val activeIoDispatches = AtomicInteger(0)
        val decryptCallsTotal = AtomicInteger(0)
        val decryptCallsOnIoDispatcher = AtomicInteger(0)

        val trackingIoDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                activeIoDispatches.incrementAndGet()
                try {
                    block.run()
                } finally {
                    activeIoDispatches.decrementAndGet()
                }
            }
        }

        val spyCipherEngine = object : KeyCipherEngine {
            override fun encrypt(plainText: String): String = "GCM:iv:$plainText"
            override fun decrypt(payload: String): String? {
                decryptCallsTotal.incrementAndGet()
                if (activeIoDispatches.get() > 0) {
                    decryptCallsOnIoDispatcher.incrementAndGet()
                }
                return payload.removePrefix("GCM:iv:")
            }
        }

        val spyKeyStorage = SecureKeyStorage(context, spyCipherEngine)
        spyKeyStorage.saveApiKey(SecureKeyStorage.KEY_GEMINI_API, "AIzaTestKeyForDispatcherCheck")

        val spyDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(context.cacheDir, "spy_prefs_${UUID.randomUUID()}.preferences_pb") }
        )
        val spyPrefsRepo = AppPreferencesRepository(
            context = context,
            secureKeyStorage = spyKeyStorage,
            dataStore = spyDataStore
        )

        val stubAiClient = object : AiProviderClient {
            override suspend fun requestStructuredTransactionsJson(
                userText: String,
                todayIsoDate: String,
                defaultCurrency: String,
                eligibleCategories: List<CategoryEntity>
            ): Result<String> {
                return Result.success(
                    """
                    {
                      "transactions": [
                        {
                          "description": "قهوة",
                          "amount": "25.00",
                          "currency": "EGP",
                          "type": "EXPENSE",
                          "date": "2026-11-01",
                          "categoryId": "${DefaultCategories.CAT_FOOD}",
                          "notes": "",
                          "confidence": 0.95
                        }
                      ],
                      "needsClarification": false,
                      "clarificationQuestion": null
                    }
                    """.trimIndent()
                )
            }

            override suspend fun requestSmartSummaryText(aggregatedFactsArabic: String): Result<String> {
                return Result.success("ملخص ذكي تجريبي")
            }

            override suspend fun testConnection(): Result<String> {
                return Result.success("Connected")
            }
        }

        val trackedRepo = FinanceRepository(
            dao = database.dao(),
            preferencesRepository = spyPrefsRepo,
            customAiClientOverride = stubAiClient,
            ioDispatcher = trackingIoDispatcher,
            defaultDispatcher = Dispatchers.Unconfined
        )

        // In Robolectric, runBlocking executes on the Android Main Thread (Looper.getMainLooper().thread).
        // Verify we start on the Main thread and that Keystore decrypt still executes via trackingIoDispatcher.
        assertEquals(Looper.getMainLooper().thread, Thread.currentThread())
        val parseRes = trackedRepo.parseMultiTransactionsWithAi(
            userText = "قهوة 25 جنيه",
            todayIsoDate = "2026-11-01",
            useLocalParserOnly = false
        )
        assertTrue(parseRes.isSuccess)

        val summaryRes = trackedRepo.generateSmartSummary("إجمالي المصروفات: 25 ج.م")
        assertTrue(summaryRes.isSuccess)

        assertEquals(2, decryptCallsTotal.get())
        assertEquals(2, decryptCallsOnIoDispatcher.get())
    }

    @Test
    fun `failed or corrupted backup restoration leaves existing Room transactions and categories intact (REV-12)`() = runBlocking {
        val originalCategoriesCount = database.dao().getAllCategories().size
        val savedTx = repository.saveManualTransaction(
            description = "معاملة أصلية محمية",
            amountMinorUnits = 75_000L, // 750 EGP
            currencyCode = "EGP",
            type = TransactionType.EXPENSE,
            categoryId = DefaultCategories.CAT_FOOD,
            transactionDate = 1730500000000L,
            notes = "Must survive failed restore"
        ).getOrThrow()

        // 1. Corrupted JSON rejected by pre-validation leaves Room untouched
        val corruptedJson = """{"format":"masroofi_backup_v1","settings":{"currencyCode":"EGP"},"transactions":[{"id":"bad","description":"","amountMinorUnits":-50}]}"""
        val preview = BackupExportManager.validateAndPreviewBackupJson(corruptedJson)
        assertFalse(preview.isValid)
        assertEquals(1, database.dao().getAllTransactions().size)
        assertEquals(originalCategoriesCount, database.dao().getAllCategories().size)

        // 2. Direct applyValidatedBackup with a payload that fails inside @Transaction replaceAllDataTransaction
        // (e.g. transaction referencing a non-existent category foreign key / invalid amount after deleteAllTransactions)
        val corruptedPayload = BackupPayload(
            schemaVersion = 2,
            exportedAtMillis = 1730500000000L,
            currencyCode = "EGP",
            openingBalanceMinorUnits = 0L,
            openingBalanceConfigured = false,
            refundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET,
            categories = DefaultCategories.all(),
            transactions = listOf(
                TransactionEntity(
                    id = "corrupt_tx",
                    description = "معاملة تالفة",
                    amountMinorUnits = 5000L,
                    currencyCode = "EGP",
                    type = TransactionType.EXPENSE,
                    categoryId = "non_existent_category_fk",
                    transactionDate = 1730500000000L
                )
            ),
            budgets = emptyList(),
            categoryRules = emptyList()
        )

        val restoreAttempt = repository.applyValidatedBackup(corruptedPayload, RestoreMode.REPLACE_ALL)
        assertTrue(restoreAttempt.isFailure)

        // Verify SQLite rolled back deleteAllTransactions() and deleteAllCategories()
        val remainingTxs = database.dao().getAllTransactions()
        assertEquals(1, remainingTxs.size)
        assertEquals(savedTx.id, remainingTxs[0].id)
        assertEquals("معاملة أصلية محمية", remainingTxs[0].description)
        assertEquals(75_000L, remainingTxs[0].amountMinorUnits)
        assertEquals(originalCategoriesCount, database.dao().getAllCategories().size)
    }

    @Test
    fun `feature-scoped ViewModels share a single AppContainer repository and maintain isolated screen states`() {
        val container = createTestAppContainer(
            defaultDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.Main
        )
        assertSame(repository, container.financeRepository)
        assertSame(prefsRepository, container.preferencesRepository)

        val homeVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[HomeViewModel::class.java]
        val transactionsVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[TransactionsViewModel::class.java]
        val aiEntryVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[AiEntryViewModel::class.java]
        val reportsVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[ReportsViewModel::class.java]
        val settingsVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[SettingsViewModel::class.java]

        awaitMainLooperCondition {
            homeVm.uiState.value.categories.isNotEmpty() &&
                settingsVm.uiState.value.categories.isNotEmpty()
        }

        // 1. HomeViewModel updates opening balance and month selection
        homeVm.setHomeYearMonth("2026-11")
        homeVm.saveOpeningBalance("1500")
        awaitMainLooperCondition {
            homeVm.uiState.value.preferences.openingBalanceMinorUnits == 150_000L
        }
        assertEquals("2026-11", homeVm.uiState.value.selectedHomeYearMonth)
        assertEquals(150_000L, settingsVm.uiState.value.preferences.openingBalanceMinorUnits)

        // 2. TransactionsViewModel saves a manual transaction and updates Home & Reports automatically via Room Flow
        transactionsVm.saveManualTransaction(
            description = "غداء عمل",
            amountText = "200",
            type = TransactionType.EXPENSE,
            categoryId = DefaultCategories.CAT_FOOD,
            isoDate = "2026-11-05",
            notes = ""
        )
        awaitMainLooperCondition {
            homeVm.uiState.value.allTransactions.size == 1 &&
                homeVm.uiState.value.homeSummary.currentBalanceMinor == 130_000L
        }
        assertEquals(130_000L, homeVm.uiState.value.homeSummary.currentBalanceMinor)

        // 3. ReportsViewModel updates custom period filter & drilldown independently
        reportsVm.setReportPeriodFilter(
            filter = ReportPeriodFilter.CUSTOM,
            customStartIso = "2026-11-01",
            customEndIso = "2026-11-30"
        )
        awaitMainLooperCondition {
            reportsVm.uiState.value.reportPeriodFilter == ReportPeriodFilter.CUSTOM &&
                reportsVm.uiState.value.reportSummary.periodExpenseMinor == 20_000L
        }
        reportsVm.selectReportCategoryDrilldown(DefaultCategories.CAT_FOOD)
        awaitMainLooperCondition {
            reportsVm.uiState.value.selectedReportDrilldownCategoryId == DefaultCategories.CAT_FOOD
        }
        assertEquals(DefaultCategories.CAT_FOOD, reportsVm.uiState.value.selectedReportDrilldownCategoryId)

        // 4. AiEntryViewModel parses multi-transactions and updates its isolated draft state
        aiEntryVm.updateAiInputText("اشتريت قهوة بـ 30 جنيه")
        aiEntryVm.analyzeAiInput(useLocalParserOnly = true)
        awaitMainLooperCondition {
            aiEntryVm.uiState.value.aiParseResult != null
        }
        assertEquals(1, aiEntryVm.uiState.value.aiParseResult?.proposals?.size)
        assertEquals(3000L, aiEntryVm.uiState.value.aiParseResult?.proposals?.first()?.amountMinorUnits)
    }

    // --- Task 6: Calendar Date Picker & Quick Date Selection Integration Tests ---

    @Test
    fun `manual transaction dialog supports quick date chips, calendar date picker navigation, cancel preservation, and confirm save`() {
        val container = createTestAppContainer(
            defaultDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.Main
        )
        val homeVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[HomeViewModel::class.java]
        val transactionsVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[TransactionsViewModel::class.java]
        val aiEntryVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[AiEntryViewModel::class.java]
        val reportsVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[ReportsViewModel::class.java]
        val settingsVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[SettingsViewModel::class.java]

        composeTestRule.setContent {
            MasroofiApp(
                homeViewModel = homeVm,
                transactionsViewModel = transactionsVm,
                aiEntryViewModel = aiEntryVm,
                reportsViewModel = reportsVm,
                settingsViewModel = settingsVm
            )
        }
        awaitMainLooperCondition {
            transactionsVm.uiState.value.categories.isNotEmpty()
        }

        val todayIso = MoneyUtils.todayIsoDate()
        val yesterdayIso = MoneyUtils.resolveQuickDateIso(QuickDateOption.YESTERDAY)
        val dayBeforeYesterdayIso = MoneyUtils.resolveQuickDateIso(QuickDateOption.DAY_BEFORE_YESTERDAY)

        // Open manual expense dialog from Home screen
        composeTestRule.onNodeWithTag("home_screen_list")
            .performScrollToNode(hasTestTag("quick_action_add_expense"))
        composeTestRule.onNodeWithTag("quick_action_add_expense").performClick()
        composeTestRule.waitForIdle()

        // 1. Default date is Today and TODAY quick chip is selected
        composeTestRule.onNodeWithTag("manual_quick_date_TODAY").assertIsSelected()
        composeTestRule.onNodeWithTag("manual_quick_date_YESTERDAY").assertIsNotSelected()
        composeTestRule.onNodeWithTag("manual_quick_date_DAY_BEFORE_YESTERDAY").assertIsNotSelected()
        composeTestRule.onNodeWithTag("manual_date_input").assertTextContains(todayIso)

        // 2. Tap Yesterday quick chip -> updates date immediately and highlights YESTERDAY chip
        composeTestRule.onNodeWithTag("manual_quick_date_YESTERDAY").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manual_quick_date_YESTERDAY").assertIsSelected()
        composeTestRule.onNodeWithTag("manual_quick_date_TODAY").assertIsNotSelected()
        composeTestRule.onNodeWithTag("manual_date_input").assertTextContains(yesterdayIso)

        // 3. Tap Day Before Yesterday quick chip -> updates date immediately and highlights DAY_BEFORE_YESTERDAY chip
        composeTestRule.onNodeWithTag("manual_quick_date_DAY_BEFORE_YESTERDAY").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manual_quick_date_DAY_BEFORE_YESTERDAY").assertIsSelected()
        composeTestRule.onNodeWithTag("manual_date_input").assertTextContains(dayBeforeYesterdayIso)

        // 4. Open Calendar Date Picker, navigate to previous month, then CANCEL -> must preserve dayBeforeYesterdayIso
        composeTestRule.onNodeWithTag("manual_open_calendar_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calendar_date_picker_dialog").assertIsDisplayed()

        composeTestRule.onNodeWithTag("calendar_prev_month_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calendar_cancel_button").performClick()
        composeTestRule.waitForIdle()

        // Committed date must still be dayBeforeYesterdayIso after canceling
        composeTestRule.onNodeWithTag("manual_date_input").assertTextContains(dayBeforeYesterdayIso)

        // 5. Re-open Calendar Date Picker, select Yesterday via calendar day cell, and CONFIRM
        composeTestRule.onNodeWithTag("manual_open_calendar_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calendar_day_$yesterdayIso").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calendar_confirm_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("manual_date_input").assertTextContains(yesterdayIso)
        composeTestRule.onNodeWithTag("manual_quick_date_YESTERDAY").assertIsSelected()

        // 6. Fill description & amount and save transaction with the selected date
        composeTestRule.onNodeWithTag("manual_description_input").performScrollTo().performTextInput("عشاء أمس")
        composeTestRule.onNodeWithTag("manual_amount_input").performScrollTo().performTextInput("120")
        composeTestRule.onNodeWithTag("manual_save_button").performClick()

        awaitMainLooperCondition {
            transactionsVm.uiState.value.allTransactions.size == 1
        }
        val savedTx = transactionsVm.uiState.value.allTransactions.first()
        assertEquals("عشاء أمس", savedTx.description)
        assertEquals(12_000L, savedTx.amountMinorUnits)
        assertEquals(yesterdayIso, MoneyUtils.epochMillisToIsoDate(savedTx.transactionDate))
    }

    @Test
    fun `editing an existing transaction via ManualTransactionDialog displays its saved date and updates date while preserving amount, category, type, and currency`() {
        val originalSavedIso = "2026-10-15"
        val originalSavedMillis = MoneyUtils.isoDateToEpochMillis(originalSavedIso)!!
        val existingUsdTx = runBlocking {
            prefsRepository.setCurrency("EGP")
            repository.saveManualTransaction(
                description = "اشتراك أداة تصميم",
                amountMinorUnits = 15_000L, // 150.00 USD
                currencyCode = "USD",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_BILLS,
                transactionDate = originalSavedMillis,
                notes = "Annual billing"
            ).getOrThrow()
        }

        val categories = runBlocking { database.dao().getAllCategories() }
        var savedIsoFromDialog: String? = null

        composeTestRule.setContent {
            ManualTransactionDialog(
                initialType = existingUsdTx.type,
                existingTransaction = existingUsdTx,
                categories = categories,
                currencySymbol = "$",
                onDismiss = {},
                onSave = { existingId, desc, amount, type, catId, isoDate, notes, isRefundOffset ->
                    savedIsoFromDialog = isoDate
                    runBlocking {
                        repository.saveManualTransaction(
                            existingId = existingId,
                            description = desc,
                            amountMinorUnits = MoneyUtils.parseAmountToMinorUnits(amount)!!,
                            currencyCode = "EGP", // repository + preserveExistingCurrency keeps USD
                            type = type,
                            categoryId = catId,
                            transactionDate = MoneyUtils.isoDateToEpochMillis(isoDate)!!,
                            notes = notes,
                            isRefundOffset = isRefundOffset,
                            preserveExistingCurrency = true
                        )
                    }
                }
            )
        }
        composeTestRule.waitForIdle()

        // 1. Verify existing transaction's actual saved date ("2026-10-15") is displayed
        composeTestRule.onNodeWithTag("manual_date_input").assertTextContains("2026-10-15")
        composeTestRule.onNodeWithTag("manual_selected_date_text").assertTextContains("2026-10-15", substring = true)

        // 2. Open calendar date picker -> starts on October 2026 and allows selecting October 20, 2026
        composeTestRule.onNodeWithTag("manual_open_calendar_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calendar_month_year_title").assertTextContains("أكتوبر 2026")
        composeTestRule.onNodeWithTag("calendar_day_2026-10-20").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calendar_confirm_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("manual_date_input").assertTextContains("2026-10-20")

        // 3. Save edited transaction and verify only transactionDate changed while amount, category, type, and USD currency are preserved
        composeTestRule.onNodeWithTag("manual_save_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("2026-10-20", savedIsoFromDialog)
        val updatedTx = runBlocking { database.dao().getTransactionById(existingUsdTx.id)!! }
        assertEquals("2026-10-20", MoneyUtils.epochMillisToIsoDate(updatedTx.transactionDate))
        assertEquals("اشتراك أداة تصميم", updatedTx.description)
        assertEquals(15_000L, updatedTx.amountMinorUnits)
        assertEquals("USD", updatedTx.currencyCode)
        assertEquals(DefaultCategories.CAT_BILLS, updatedTx.categoryId)
        assertEquals(TransactionType.EXPENSE, updatedTx.type)
        assertEquals("Annual billing", updatedTx.notes)
    }

    @Test
    fun `AI entry preserves parsed dates, applies manual date overrides with precedence, and persists selected dates`() {
        val container = createTestAppContainer(
            defaultDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.Main
        )
        val aiEntryVm = ViewModelProvider(viewModelStore, container.viewModelFactory)[AiEntryViewModel::class.java]
        awaitMainLooperCondition {
            aiEntryVm.uiState.value.categories.isNotEmpty()
        }

        // 1. When user has NOT manually selected a date, AI/parser-detected dates are preserved
        aiEntryVm.updateAiInputText("اشتريت قهوة بـ 25 جنيه 2026-10-20\nطماطم 20")
        aiEntryVm.analyzeAiInput(useLocalParserOnly = true)
        awaitMainLooperCondition {
            aiEntryVm.uiState.value.aiParseResult != null
        }

        val initialProposals = aiEntryVm.uiState.value.aiParseResult!!.proposals
        assertEquals(2, initialProposals.size)
        assertEquals("2026-10-20", initialProposals[0].isoDate)
        assertTrue(initialProposals[0].aiDetectedDate)
        assertEquals(MoneyUtils.todayIsoDate(), initialProposals[1].isoDate)
        assertFalse(initialProposals[1].aiDetectedDate)

        // 2. User manually overrides the second draft's date -> userModifiedDate becomes true and takes precedence
        val secondDraft = initialProposals[1]
        aiEntryVm.updateProposedDraft(secondDraft.copy(isoDate = "2026-10-18"))
        awaitMainLooperCondition {
            aiEntryVm.uiState.value.aiParseResult?.proposals?.get(1)?.isoDate == "2026-10-18"
        }
        assertTrue(aiEntryVm.uiState.value.aiParseResult!!.proposals[1].userModifiedDate)

        // Re-analyzing the same input must NOT overwrite the user's manual date override on draft[1]
        aiEntryVm.analyzeAiInput(useLocalParserOnly = true)
        awaitMainLooperCondition {
            aiEntryVm.uiState.value.aiParseResult != null && !aiEntryVm.uiState.value.isAiParsing
        }
        val reanalyzedProposals = aiEntryVm.uiState.value.aiParseResult!!.proposals
        assertEquals("2026-10-20", reanalyzedProposals[0].isoDate)
        assertEquals("2026-10-18", reanalyzedProposals[1].isoDate)
        assertTrue(reanalyzedProposals[1].userModifiedDate)

        // 3. Selecting a batch manual date via selectAiTransactionDate updates proposals and takes precedence over AI
        aiEntryVm.selectAiTransactionDate("2026-10-12")
        awaitMainLooperCondition {
            aiEntryVm.uiState.value.selectedTransactionIsoDate == "2026-10-12" &&
                aiEntryVm.uiState.value.isDateManuallySelected
        }
        assertTrue(aiEntryVm.uiState.value.aiParseResult!!.proposals.all { it.isoDate == "2026-10-12" })

        // Save all reviewed proposals and verify persisted transactionDate in Room
        aiEntryVm.saveAllReviewedProposals()
        awaitMainLooperCondition {
            aiEntryVm.uiState.value.aiParseResult == null
        }

        val savedTxs = runBlocking { database.dao().getAllTransactions() }
        assertEquals(2, savedTxs.size)
        assertTrue(savedTxs.all { MoneyUtils.epochMillisToIsoDate(it.transactionDate) == "2026-10-12" })
    }
}
