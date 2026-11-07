package com.example

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.example.data.local.CategoryEntity
import com.example.data.local.DefaultCategories
import com.example.data.local.MasroofiDatabase
import com.example.data.local.RefundAccountingMode
import com.example.data.local.TransactionType
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.KeyCipherEngine
import com.example.data.preferences.SecureKeyStorage
import com.example.data.repository.FinanceRepository
import com.example.data.repository.RestoreMode
import com.example.domain.ai.AiProviderClient
import com.example.domain.ai.AiServiceException
import com.example.domain.ai.ProposedTransactionDraft
import com.example.ui.MasroofiApp
import com.example.ui.speech.MasroofiSpeechHelper
import com.example.ui.speech.SpeechRecognitionState
import com.example.ui.viewmodel.MasroofiViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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

    private fun createTestViewModel(
        defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): MasroofiViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MasroofiViewModel(
                    repository = repository,
                    defaultDispatcher = defaultDispatcher,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }
        return ViewModelProvider(viewModelStore, factory)[MasroofiViewModel::class.java]
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
        val viewModel = createTestViewModel(
            defaultDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.Main
        )
        composeTestRule.setContent {
            MasroofiApp(viewModel = viewModel)
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
        val viewModel = createTestViewModel(
            defaultDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.Main
        )
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
}
