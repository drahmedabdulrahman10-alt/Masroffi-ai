package com.example.data.repository

import com.example.data.backup.BackupExportManager
import com.example.data.backup.BackupPayload
import com.example.data.local.BudgetEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.CategoryRuleEntity
import com.example.data.local.DefaultCategories
import com.example.data.local.MasroofiDao
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionSource
import com.example.data.local.TransactionType
import com.example.data.preferences.AiProviderType
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.SecureKeyStorage
import com.example.domain.ai.AiParseResult
import com.example.domain.ai.AiProviderClient
import com.example.domain.ai.AiServiceException
import com.example.domain.ai.EgyptianArabicLocalParser
import com.example.domain.ai.ProposedTransactionDraft
import com.example.domain.ai.RemoteAiProviderClient
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import java.util.UUID

enum class RestoreMode {
    REPLACE_ALL,
    MERGE_BY_ID
}

/**
 * Central repository coordinating local Room database persistence, user-controlled categories,
 * learned category rules, AI provider orchestration, and atomic batch saves.
 */
class FinanceRepository(
    private val dao: MasroofiDao,
    val preferencesRepository: AppPreferencesRepository,
    private val customAiClientOverride: AiProviderClient? = null
) {

    val allCategoriesFlow: Flow<List<CategoryEntity>> = dao.observeAllCategories()
    val allTransactionsFlow: Flow<List<TransactionEntity>> = dao.observeAllTransactions()
    val allBudgetsFlow: Flow<List<BudgetEntity>> = dao.observeAllBudgets()
    val categoryRulesFlow: Flow<List<CategoryRuleEntity>> = dao.observeCategoryRules()

    private var lastManualSaveSignature: String? = null
    private var lastManualSaveTimestamp: Long = 0L

    /**
     * Seeds default Egyptian Arabic expense and income categories on first launch only.
     * Users remain free to rename, archive, or remove defaults afterward.
     */
    suspend fun ensureDefaultCategoriesSeeded() {
        val prefs = preferencesRepository.getSnapshot()
        val existing = dao.getAllCategories()
        if (!prefs.categoriesSeeded && existing.isEmpty()) {
            dao.insertCategoriesIgnore(DefaultCategories.all())
            preferencesRepository.markCategoriesSeeded()
        } else if (!prefs.categoriesSeeded) {
            preferencesRepository.markCategoriesSeeded()
        }
    }

    // --- Manual Transaction Operations ---

    /**
     * Validates and saves a manual transaction with duplicate-submission protection.
     */
    suspend fun saveManualTransaction(
        existingId: String? = null,
        description: String,
        amountMinorUnits: Long,
        currencyCode: String,
        type: TransactionType,
        categoryId: String?,
        transactionDate: Long,
        notes: String,
        isRefundOffset: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<TransactionEntity> {
        val cleanDesc = description.trim()
        if (cleanDesc.isEmpty()) {
            return Result.failure(IllegalArgumentException("يرجى إدخال وصف المعاملة"))
        }
        if (amountMinorUnits <= 0L) {
            return Result.failure(IllegalArgumentException("يرجى إدخال مبلغ صحيح أكبر من الصفر"))
        }

        // Prevent accidental rapid double-tap duplicate submission for new entries
        if (existingId == null) {
            val signature = "$cleanDesc|$amountMinorUnits|$type|$categoryId|$transactionDate"
            if (signature == lastManualSaveSignature && (nowMillis - lastManualSaveTimestamp) < 1500L) {
                return Result.failure(IllegalArgumentException("تم حفظ هذه المعاملة بالفعل"))
            }
            lastManualSaveSignature = signature
            lastManualSaveTimestamp = nowMillis
        }

        // Verify categoryId exists in database if provided
        val verifiedCategoryId = if (categoryId != null) {
            dao.getCategoryById(categoryId)?.id
        } else {
            null
        }

        val entity = if (existingId != null) {
            val prev = dao.getTransactionById(existingId)
            TransactionEntity(
                id = existingId,
                description = cleanDesc,
                amountMinorUnits = amountMinorUnits,
                currencyCode = currencyCode,
                type = type,
                categoryId = verifiedCategoryId,
                transactionDate = transactionDate,
                createdAt = prev?.createdAt ?: nowMillis,
                updatedAt = nowMillis,
                notes = notes.trim(),
                source = prev?.source ?: TransactionSource.MANUAL,
                batchOperationId = prev?.batchOperationId,
                isRefundOffset = isRefundOffset
            )
        } else {
            TransactionEntity(
                id = UUID.randomUUID().toString(),
                description = cleanDesc,
                amountMinorUnits = amountMinorUnits,
                currencyCode = currencyCode,
                type = type,
                categoryId = verifiedCategoryId,
                transactionDate = transactionDate,
                createdAt = nowMillis,
                updatedAt = nowMillis,
                notes = notes.trim(),
                source = TransactionSource.MANUAL,
                batchOperationId = null,
                isRefundOffset = isRefundOffset
            )
        }

        dao.upsertTransaction(entity)
        return Result.success(entity)
    }

    suspend fun deleteTransaction(transactionId: String) {
        dao.deleteTransactionById(transactionId)
    }

    // --- Batch AI Transaction Saving ---

    /**
     * Atomically saves a list of reviewed AI transaction drafts using a batchOperationId
     * so retries never duplicate saved records. Also records any user category corrections
     * as local CategoryRuleEntity preferences.
     */
    suspend fun saveReviewedBatchTransactions(
        batchOperationId: String,
        drafts: List<ProposedTransactionDraft>,
        rememberCategoryRules: Boolean = true,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<Int> {
        if (drafts.isEmpty()) {
            return Result.failure(IllegalArgumentException("لا توجد معاملات لحفظها"))
        }

        val allCategoriesMap = dao.getAllCategories().associateBy { it.id }
        val entities = mutableListOf<TransactionEntity>()

        for (draft in drafts) {
            val cleanDesc = draft.description.trim()
            val minor = draft.amountMinorUnits
            if (cleanDesc.isEmpty() || minor == null || minor <= 0L) {
                return Result.failure(
                    IllegalArgumentException("توجد معاملة بدون وصف أو بمبلغ غير صالح (${draft.description}).")
                )
            }
            val dateMillis = com.example.domain.MoneyUtils.isoDateToEpochMillis(draft.isoDate)
                ?: nowMillis

            val verifiedCat = draft.categoryId?.let { allCategoriesMap[it] }
            entities.add(
                TransactionEntity(
                    id = UUID.randomUUID().toString(),
                    description = cleanDesc,
                    amountMinorUnits = minor,
                    currencyCode = draft.currencyCode,
                    type = draft.type,
                    categoryId = verifiedCat?.id,
                    transactionDate = dateMillis,
                    createdAt = nowMillis,
                    updatedAt = nowMillis,
                    notes = draft.notes.trim(),
                    source = TransactionSource.AI_BATCH,
                    batchOperationId = batchOperationId,
                    isRefundOffset = draft.isRefundOffset
                )
            )

            if (rememberCategoryRules && verifiedCat != null && cleanDesc.length >= 2) {
                rememberCategoryCorrection(
                    description = cleanDesc,
                    categoryId = verifiedCat.id,
                    transactionType = draft.type
                )
            }
        }

        val committed = dao.insertBatchWithIdempotency(batchOperationId, entities)
        return if (committed) {
            Result.success(entities.size)
        } else {
            Result.failure(IllegalStateException("تم حفظ هذه الدفعة مسبقاً ولن يتم تكرارها."))
        }
    }

    suspend fun rememberCategoryCorrection(
        description: String,
        categoryId: String,
        transactionType: TransactionType
    ) {
        val cleanKeyword = description.lowercase(Locale.ROOT).trim()
        if (cleanKeyword.length < 2) return
        dao.upsertCategoryRule(
            CategoryRuleEntity(
                keyword = cleanKeyword,
                categoryId = categoryId,
                transactionType = transactionType,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // --- User-Controlled Category Management ---

    suspend fun saveCategory(
        existingId: String? = null,
        name: String,
        type: TransactionType,
        iconName: String,
        colorHex: Long,
        isArchived: Boolean = false
    ): Result<CategoryEntity> {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("يرجى إدخال اسم التصنيف"))
        }
        val existing = existingId?.let { dao.getCategoryById(it) }
        val entity = CategoryEntity(
            id = existingId ?: "cat_${UUID.randomUUID().toString().take(8)}",
            name = cleanName,
            type = type,
            iconName = iconName.ifBlank { "category" },
            colorHex = colorHex,
            isArchived = isArchived,
            isDefault = existing?.isDefault ?: false,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        dao.upsertCategory(entity)
        return Result.success(entity)
    }

    suspend fun setCategoryArchived(categoryId: String, archived: Boolean) {
        val existing = dao.getCategoryById(categoryId) ?: return
        dao.upsertCategory(existing.copy(isArchived = archived))
    }

    /**
     * Safely deletes a category. If transactions reference this category,
     * ForeignKey.SET_NULL preserves all transaction history cleanly by setting their
     * categoryId to null (Uncategorized), never destroying financial records.
     */
    suspend fun deleteCategorySafely(categoryId: String): Int {
        val linkedCount = dao.countTransactionsForCategory(categoryId)
        dao.deleteCategoryById(categoryId)
        return linkedCount
    }

    suspend fun countTransactionsForCategory(categoryId: String): Int {
        return dao.countTransactionsForCategory(categoryId)
    }

    // --- Budgets ---

    suspend fun saveBudget(
        yearMonth: String,
        categoryId: String?,
        limitMinorUnits: Long
    ): Result<BudgetEntity> {
        if (limitMinorUnits <= 0L) {
            return Result.failure(IllegalArgumentException("يرجى إدخال قيمة ميزانية أكبر من الصفر"))
        }
        val id = if (categoryId == null) {
            "${yearMonth}_OVERALL"
        } else {
            "${yearMonth}_$categoryId"
        }
        val budget = BudgetEntity(
            id = id,
            yearMonth = yearMonth,
            categoryId = categoryId,
            limitMinorUnits = limitMinorUnits,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertBudget(budget)
        return Result.success(budget)
    }

    suspend fun deleteBudget(budgetId: String) {
        dao.deleteBudgetById(budgetId)
    }

    // --- AI Parsing & Provider Orchestration ---

    private fun resolveActiveProviderClient(providerType: AiProviderType): AiProviderClient {
        if (customAiClientOverride != null) return customAiClientOverride
        return RemoteAiProviderClient(
            providerType = providerType,
            apiKeyProvider = {
                val keyId = when (providerType) {
                    AiProviderType.GEMINI -> SecureKeyStorage.KEY_GEMINI_API
                    AiProviderType.OPENROUTER -> SecureKeyStorage.KEY_OPENROUTER_API
                }
                preferencesRepository.secureKeyStorage.getApiKey(keyId)
            },
            modelIdProvider = {
                // Synchronous read cached via preferences snapshot in caller
                ""
            }
        )
    }

    suspend fun parseMultiTransactionsWithAi(
        userText: String,
        todayIsoDate: String,
        useLocalParserOnly: Boolean = false
    ): Result<AiParseResult> {
        val prefs = preferencesRepository.getSnapshot()
        val activeCategories = dao.getActiveCategories()
        val learnedRules = dao.getAllCategoryRules()

        if (!prefs.aiEnabled) {
            return Result.failure(
                IllegalStateException("ميزة الذكاء الاصطناعي متوقفة حالياً من الإعدادات. يمكنك تفعيلها في أي وقت.")
            )
        }

        if (useLocalParserOnly) {
            val localRes = EgyptianArabicLocalParser.parseNaturalText(
                rawText = userText,
                todayIsoDate = todayIsoDate,
                defaultCurrency = prefs.currencyCode,
                eligibleCategories = activeCategories,
                learnedRules = learnedRules
            )
            return Result.success(localRes)
        }

        val keyId = when (prefs.selectedProvider) {
            AiProviderType.GEMINI -> SecureKeyStorage.KEY_GEMINI_API
            AiProviderType.OPENROUTER -> SecureKeyStorage.KEY_OPENROUTER_API
        }
        val configuredApiKey = preferencesRepository.secureKeyStorage.getApiKey(keyId).trim()
        val modelId = when (prefs.selectedProvider) {
            AiProviderType.GEMINI -> prefs.geminiModelId
            AiProviderType.OPENROUTER -> prefs.openRouterModelId
        }

        // If a custom test client override is injected, or if the user configured an API key, call the remote provider
        if (customAiClientOverride != null || configuredApiKey.isNotEmpty()) {
            val client = customAiClientOverride ?: RemoteAiProviderClient(
                providerType = prefs.selectedProvider,
                apiKeyProvider = { configuredApiKey },
                modelIdProvider = { modelId }
            )
            val providerLabel = when (prefs.selectedProvider) {
                AiProviderType.GEMINI -> "Google Gemini ($modelId)"
                AiProviderType.OPENROUTER -> "OpenRouter ($modelId)"
            }
            val rawJsonResult = client.requestStructuredTransactionsJson(
                userText = userText,
                todayIsoDate = todayIsoDate,
                defaultCurrency = prefs.currencyCode,
                eligibleCategories = activeCategories
            )
            return rawJsonResult.fold(
                onSuccess = { rawJson ->
                    EgyptianArabicLocalParser.validateAndMapAiJsonResponse(
                        rawJsonResponse = rawJson,
                        todayIsoDate = todayIsoDate,
                        defaultCurrency = prefs.currencyCode,
                        eligibleCategories = activeCategories,
                        learnedRules = learnedRules,
                        providerLabel = providerLabel
                    )
                },
                onFailure = { err ->
                    Result.failure(err)
                }
            )
        }

        // When no remote API key is configured yet, use the deterministic local Egyptian Arabic parser
        val localResult = EgyptianArabicLocalParser.parseNaturalText(
            rawText = userText,
            todayIsoDate = todayIsoDate,
            defaultCurrency = prefs.currencyCode,
            eligibleCategories = activeCategories,
            learnedRules = learnedRules
        )
        return Result.success(localResult)
    }

    suspend fun generateSmartSummary(aggregatedFactsArabic: String): Result<String> {
        val prefs = preferencesRepository.getSnapshot()
        if (!prefs.aiEnabled || !prefs.aiSummaryEnabled) {
            return Result.failure(IllegalStateException("ميزة الملخص الذكي غير مفعلة في الإعدادات."))
        }
        val keyId = when (prefs.selectedProvider) {
            AiProviderType.GEMINI -> SecureKeyStorage.KEY_GEMINI_API
            AiProviderType.OPENROUTER -> SecureKeyStorage.KEY_OPENROUTER_API
        }
        val apiKey = preferencesRepository.secureKeyStorage.getApiKey(keyId).trim()
        val modelId = when (prefs.selectedProvider) {
            AiProviderType.GEMINI -> prefs.geminiModelId
            AiProviderType.OPENROUTER -> prefs.openRouterModelId
        }

        if (customAiClientOverride != null || apiKey.isNotEmpty()) {
            val client = customAiClientOverride ?: RemoteAiProviderClient(
                providerType = prefs.selectedProvider,
                apiKeyProvider = { apiKey },
                modelIdProvider = { modelId }
            )
            return client.requestSmartSummaryText(aggregatedFactsArabic)
        }

        return Result.success(
            "ملخص محلي محسوب من سجلاتك:\n$aggregatedFactsArabic\n(أضف مفتاح API في الإعدادات للحصول على صياغة تحليلية إضافية عبر الذكاء الاصطناعي)."
        )
    }

    suspend fun testProviderConnection(
        provider: AiProviderType,
        apiKey: String,
        modelId: String
    ): Result<String> {
        val client = customAiClientOverride ?: RemoteAiProviderClient(
            providerType = provider,
            apiKeyProvider = { apiKey.trim() },
            modelIdProvider = { modelId.trim() }
        )
        return client.testConnection()
    }

    // --- Backup & Restore ---

    suspend fun createBackupJson(): String {
        val prefs = preferencesRepository.getSnapshot()
        val categories = dao.getAllCategories()
        val transactions = dao.getAllTransactions()
        val budgets = dao.getAllBudgets()
        val rules = dao.getAllCategoryRules()

        return BackupExportManager.createBackupJsonString(
            currencyCode = prefs.currencyCode,
            openingBalanceMinorUnits = prefs.openingBalanceMinorUnits,
            openingBalanceConfigured = prefs.openingBalanceConfigured,
            refundMode = prefs.refundAccountingMode,
            categories = categories,
            transactions = transactions,
            budgets = budgets,
            categoryRules = rules
        )
    }

    suspend fun applyValidatedBackup(
        payload: BackupPayload,
        mode: RestoreMode
    ): Result<Unit> {
        return try {
            when (mode) {
                RestoreMode.REPLACE_ALL -> {
                    dao.replaceAllDataTransaction(
                        categories = payload.categories,
                        transactions = payload.transactions,
                        budgets = payload.budgets,
                        rules = payload.categoryRules
                    )
                }
                RestoreMode.MERGE_BY_ID -> {
                    dao.mergeBackupDataTransaction(
                        categories = payload.categories,
                        transactions = payload.transactions,
                        budgets = payload.budgets,
                        rules = payload.categoryRules
                    )
                }
            }
            preferencesRepository.restoreBackupSettings(
                currencyCode = payload.currencyCode,
                openingBalanceMinorUnits = payload.openingBalanceMinorUnits,
                openingBalanceConfigured = payload.openingBalanceConfigured,
                refundMode = payload.refundAccountingMode
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("فشل استعادة النسخة الاحتياطية بأمان: ${e.message}"))
        }
    }

    suspend fun exportCsvString(): String {
        val categoriesMap = dao.getAllCategories().associateBy { it.id }
        val transactions = dao.getAllTransactions()
        return BackupExportManager.exportTransactionsToCsv(transactions, categoriesMap)
    }
}
