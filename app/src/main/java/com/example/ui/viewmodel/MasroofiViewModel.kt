package com.example.ui.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.backup.BackupExportManager
import com.example.data.backup.BackupValidationPreview
import com.example.data.local.BudgetEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.MasroofiDatabase
import com.example.data.local.RefundAccountingMode
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.preferences.AiProviderType
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.AppPreferencesState
import com.example.data.preferences.SecureKeyStorage
import com.example.data.preferences.ThemePreference
import com.example.data.repository.FinanceRepository
import com.example.data.repository.RestoreMode
import com.example.domain.FinancialCalculator
import com.example.domain.FinancialSummary
import com.example.domain.MoneyUtils
import com.example.domain.ai.AiParseResult
import com.example.domain.ai.AiServiceException
import com.example.domain.ai.ProposedTransactionDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Calendar

enum class ReportPeriodFilter(val arabicLabel: String) {
    TODAY("اليوم"),
    CURRENT_WEEK("هذا الأسبوع"),
    CURRENT_MONTH("هذا الشهر"),
    PREVIOUS_MONTH("الشهر السابق"),
    CURRENT_YEAR("هذه السنة"),
    CUSTOM("فترة مخصصة")
}

data class TransactionHistoryFilter(
    val searchQuery: String = "",
    val selectedType: TransactionType? = null,
    val selectedCategoryId: String? = null,
    val minAmountText: String = "",
    val maxAmountText: String = "",
    val startIsoDate: String = "",
    val endIsoDate: String = ""
)

data class MasroofiUiState(
    val preferences: AppPreferencesState = AppPreferencesState(),
    val categories: List<CategoryEntity> = emptyList(),
    val allTransactions: List<TransactionEntity> = emptyList(),
    val allBudgets: List<BudgetEntity> = emptyList(),
    val selectedHomeYearMonth: String = MoneyUtils.currentYearMonth(),
    val homeSummary: FinancialSummary = FinancialCalculator.buildSummary(
        openingBalanceMinorUnits = 0L,
        openingBalanceConfigured = false,
        allTransactions = emptyList(),
        periodTransactions = emptyList(),
        previousPeriodTransactions = emptyList(),
        categories = emptyList(),
        monthBudgets = emptyList()
    ),
    val reportPeriodFilter: ReportPeriodFilter = ReportPeriodFilter.CURRENT_MONTH,
    val customReportStartIso: String = MoneyUtils.todayIsoDate(),
    val customReportEndIso: String = MoneyUtils.todayIsoDate(),
    val reportSummary: FinancialSummary = FinancialCalculator.buildSummary(
        openingBalanceMinorUnits = 0L,
        openingBalanceConfigured = false,
        allTransactions = emptyList(),
        periodTransactions = emptyList(),
        previousPeriodTransactions = emptyList(),
        categories = emptyList(),
        monthBudgets = emptyList()
    ),
    val selectedReportDrilldownCategoryId: String? = null,
    val reportPeriodTransactions: List<TransactionEntity> = emptyList(),
    val historyFilter: TransactionHistoryFilter = TransactionHistoryFilter(),
    val filteredHistoryTransactions: List<TransactionEntity> = emptyList(),
    // AI Entry State
    val aiInputText: String = "",
    val isAiParsing: Boolean = false,
    val aiParseResult: AiParseResult? = null,
    val aiErrorMessage: String? = null,
    // Smart Summary State
    val isGeneratingSmartSummary: Boolean = false,
    val smartSummaryResultText: String? = null,
    val smartSummaryError: String? = null,
    // Settings / API Keys
    val maskedGeminiKey: String = "غير مُعد",
    val maskedOpenRouterKey: String = "غير مُعد",
    val hasGeminiKey: Boolean = false,
    val hasOpenRouterKey: Boolean = false,
    val isTestingConnection: Boolean = false,
    val connectionTestMessage: String? = null,
    val connectionTestSuccess: Boolean? = null,
    // Backup Preview
    val pendingBackupPreview: BackupValidationPreview? = null,
    // Feedback Snackbar
    val snackbarMessage: String? = null
)

private data class FinancialFilterState(
    val selectedHomeYearMonth: String = MoneyUtils.currentYearMonth(),
    val reportPeriodFilter: ReportPeriodFilter = ReportPeriodFilter.CURRENT_MONTH,
    val customReportStartIso: String = MoneyUtils.todayIsoDate(),
    val customReportEndIso: String = MoneyUtils.todayIsoDate(),
    val selectedReportDrilldownCategoryId: String? = null,
    val historyFilter: TransactionHistoryFilter = TransactionHistoryFilter()
)

private data class UiFeedbackAndAiState(
    val aiInputText: String = "",
    val isAiParsing: Boolean = false,
    val aiParseResult: AiParseResult? = null,
    val aiErrorMessage: String? = null,
    val isGeneratingSmartSummary: Boolean = false,
    val smartSummaryResultText: String? = null,
    val smartSummaryError: String? = null,
    val isTestingConnection: Boolean = false,
    val connectionTestMessage: String? = null,
    val connectionTestSuccess: Boolean? = null,
    val pendingBackupPreview: BackupValidationPreview? = null,
    val snackbarMessage: String? = null
)

private data class ComputedFinancials(
    val preferences: AppPreferencesState = AppPreferencesState(),
    val categories: List<CategoryEntity> = emptyList(),
    val allTransactions: List<TransactionEntity> = emptyList(),
    val allBudgets: List<BudgetEntity> = emptyList(),
    val filterState: FinancialFilterState = FinancialFilterState(),
    val homeSummary: FinancialSummary = FinancialCalculator.buildSummary(
        openingBalanceMinorUnits = 0L,
        openingBalanceConfigured = false,
        allTransactions = emptyList(),
        periodTransactions = emptyList(),
        previousPeriodTransactions = emptyList(),
        categories = emptyList(),
        monthBudgets = emptyList()
    ),
    val reportSummary: FinancialSummary = FinancialCalculator.buildSummary(
        openingBalanceMinorUnits = 0L,
        openingBalanceConfigured = false,
        allTransactions = emptyList(),
        periodTransactions = emptyList(),
        previousPeriodTransactions = emptyList(),
        categories = emptyList(),
        monthBudgets = emptyList()
    ),
    val reportPeriodTransactions: List<TransactionEntity> = emptyList(),
    val filteredHistoryTransactions: List<TransactionEntity> = emptyList()
)

private data class KeyStatus(
    val maskedGeminiKey: String = "غير مُعد",
    val maskedOpenRouterKey: String = "غير مُعد",
    val hasGeminiKey: Boolean = false,
    val hasOpenRouterKey: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class MasroofiViewModel(
    private val repository: FinanceRepository,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val financialFilterState = MutableStateFlow(FinancialFilterState())
    private val uiFeedbackAndAiState = MutableStateFlow(UiFeedbackAndAiState())
    private val keyRefreshTrigger = MutableStateFlow(0)

    init {
        viewModelScope.launch(ioDispatcher) {
            repository.ensureDefaultCategoriesSeeded()
        }
    }

    private val keyStatusFlow: StateFlow<KeyStatus> = keyRefreshTrigger
        .mapLatest {
            val secureStorage = repository.preferencesRepository.secureKeyStorage
            val geminiKey = secureStorage.getApiKey(SecureKeyStorage.KEY_GEMINI_API)
            val openRouterKey = secureStorage.getApiKey(SecureKeyStorage.KEY_OPENROUTER_API)
            KeyStatus(
                maskedGeminiKey = secureStorage.maskApiKey(geminiKey),
                maskedOpenRouterKey = secureStorage.maskApiKey(openRouterKey),
                hasGeminiKey = geminiKey.isNotBlank(),
                hasOpenRouterKey = openRouterKey.isNotBlank()
            )
        }
        .flowOn(ioDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = KeyStatus()
        )

    private val computedFinancialsFlow: StateFlow<ComputedFinancials> = combine(
        repository.preferencesRepository.preferencesFlow,
        repository.allCategoriesFlow,
        repository.allTransactionsFlow,
        repository.allBudgetsFlow,
        financialFilterState
    ) { prefs, categories, transactions, budgets, filters ->
        val homeYearMonth = filters.selectedHomeYearMonth
        val (homeStart, homeEnd) = MoneyUtils.monthDateRange(homeYearMonth)
        val prevHomeYearMonth = MoneyUtils.previousYearMonth(homeYearMonth)
        val (prevHomeStart, prevHomeEnd) = MoneyUtils.monthDateRange(prevHomeYearMonth)

        val homePeriodTxs = FinancialCalculator.filterByDateRange(transactions, homeStart, homeEnd)
        val prevHomePeriodTxs = FinancialCalculator.filterByDateRange(transactions, prevHomeStart, prevHomeEnd)
        val homeBudgets = budgets.filter { it.yearMonth == homeYearMonth }

        val homeSummary = FinancialCalculator.buildSummary(
            openingBalanceMinorUnits = prefs.openingBalanceMinorUnits,
            openingBalanceConfigured = prefs.openingBalanceConfigured,
            allTransactions = transactions,
            periodTransactions = homePeriodTxs,
            previousPeriodTransactions = prevHomePeriodTxs,
            categories = categories,
            monthBudgets = homeBudgets,
            currencyCode = prefs.currencyCode,
            refundAccountingMode = prefs.refundAccountingMode
        )

        val (reportStart, reportEnd, prevReportRange) = resolveReportPeriodBounds(
            filter = filters.reportPeriodFilter,
            customStartIso = filters.customReportStartIso,
            customEndIso = filters.customReportEndIso
        )
        val reportTxs = FinancialCalculator.filterByCurrency(
            FinancialCalculator.filterByDateRange(transactions, reportStart, reportEnd),
            prefs.currencyCode
        )
        val prevReportTxs = if (prevReportRange != null) {
            FinancialCalculator.filterByCurrency(
                FinancialCalculator.filterByDateRange(transactions, prevReportRange.first, prevReportRange.second),
                prefs.currencyCode
            )
        } else {
            emptyList()
        }
        val reportYearMonth = MoneyUtils.yearMonthFromMillis(reportStart)
        val reportBudgets = budgets.filter { it.yearMonth == reportYearMonth }

        val reportSummary = FinancialCalculator.buildSummary(
            openingBalanceMinorUnits = prefs.openingBalanceMinorUnits,
            openingBalanceConfigured = prefs.openingBalanceConfigured,
            allTransactions = transactions,
            periodTransactions = reportTxs,
            previousPeriodTransactions = prevReportTxs,
            categories = categories,
            monthBudgets = reportBudgets,
            currencyCode = prefs.currencyCode,
            refundAccountingMode = prefs.refundAccountingMode
        )

        val filteredHistory = applyHistoryFilters(transactions, filters.historyFilter)

        ComputedFinancials(
            preferences = prefs,
            categories = categories,
            allTransactions = transactions,
            allBudgets = budgets,
            filterState = filters,
            homeSummary = homeSummary,
            reportSummary = reportSummary,
            reportPeriodTransactions = reportTxs,
            filteredHistoryTransactions = filteredHistory
        )
    }
        .flowOn(defaultDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ComputedFinancials()
        )

    val uiState: StateFlow<MasroofiUiState> = combine(
        computedFinancialsFlow,
        uiFeedbackAndAiState,
        keyStatusFlow
    ) { computed, uiFeedback, keys ->
        MasroofiUiState(
            preferences = computed.preferences,
            categories = computed.categories,
            allTransactions = computed.allTransactions,
            allBudgets = computed.allBudgets,
            selectedHomeYearMonth = computed.filterState.selectedHomeYearMonth,
            homeSummary = computed.homeSummary,
            reportPeriodFilter = computed.filterState.reportPeriodFilter,
            customReportStartIso = computed.filterState.customReportStartIso,
            customReportEndIso = computed.filterState.customReportEndIso,
            reportSummary = computed.reportSummary,
            selectedReportDrilldownCategoryId = computed.filterState.selectedReportDrilldownCategoryId,
            reportPeriodTransactions = computed.reportPeriodTransactions,
            historyFilter = computed.filterState.historyFilter,
            filteredHistoryTransactions = computed.filteredHistoryTransactions,
            aiInputText = uiFeedback.aiInputText,
            isAiParsing = uiFeedback.isAiParsing,
            aiParseResult = uiFeedback.aiParseResult,
            aiErrorMessage = uiFeedback.aiErrorMessage,
            isGeneratingSmartSummary = uiFeedback.isGeneratingSmartSummary,
            smartSummaryResultText = uiFeedback.smartSummaryResultText,
            smartSummaryError = uiFeedback.smartSummaryError,
            maskedGeminiKey = keys.maskedGeminiKey,
            maskedOpenRouterKey = keys.maskedOpenRouterKey,
            hasGeminiKey = keys.hasGeminiKey,
            hasOpenRouterKey = keys.hasOpenRouterKey,
            isTestingConnection = uiFeedback.isTestingConnection,
            connectionTestMessage = uiFeedback.connectionTestMessage,
            connectionTestSuccess = uiFeedback.connectionTestSuccess,
            pendingBackupPreview = uiFeedback.pendingBackupPreview,
            snackbarMessage = uiFeedback.snackbarMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MasroofiUiState()
    )

    // --- Snackbar & UI Feedback ---

    fun clearSnackbar() {
        uiFeedbackAndAiState.update { it.copy(snackbarMessage = null) }
    }

    fun showUserMessage(message: String) {
        if (message.isNotBlank()) {
            showSnackbar(message)
        }
    }

    private fun showSnackbar(msg: String) {
        uiFeedbackAndAiState.update { it.copy(snackbarMessage = msg) }
    }

    // --- Manual Transaction Entry, Edit, Delete ---

    fun saveManualTransaction(
        existingId: String? = null,
        description: String,
        amountText: String,
        type: TransactionType,
        categoryId: String?,
        isoDate: String,
        notes: String,
        isRefundOffset: Boolean = false,
        onSuccess: () -> Unit = {}
    ) {
        val minorUnits = MoneyUtils.parseAmountToMinorUnits(amountText)
        if (minorUnits == null || minorUnits <= 0L) {
            showSnackbar("يرجى إدخال مبلغ صحيح أكبر من الصفر")
            return
        }
        val dateMillis = MoneyUtils.isoDateToEpochMillis(isoDate)
        if (dateMillis == null) {
            showSnackbar("تاريخ غير صالح، يرجى استخدام الصيغة YYYY-MM-DD")
            return
        }
        if (description.isBlank()) {
            showSnackbar("يرجى إدخال وصف المعاملة")
            return
        }

        viewModelScope.launch {
            val currencyCode = uiState.value.preferences.currencyCode
            val result = repository.saveManualTransaction(
                existingId = existingId,
                description = description,
                amountMinorUnits = minorUnits,
                currencyCode = currencyCode,
                type = type,
                categoryId = categoryId,
                transactionDate = dateMillis,
                notes = notes,
                isRefundOffset = isRefundOffset
            )
            result.fold(
                onSuccess = {
                    showSnackbar(if (existingId != null) "تم تحديث المعاملة بنجاح" else "تم حفظ المعاملة بنجاح")
                    onSuccess()
                },
                onFailure = { err ->
                    showSnackbar(err.message ?: "تعذر حفظ المعاملة")
                }
            )
        }
    }

    fun deleteTransaction(transactionId: String) {
        viewModelScope.launch {
            repository.deleteTransaction(transactionId)
            showSnackbar("تم حذف المعاملة")
        }
    }

    // --- AI Multi-Transaction Entry & Speech-to-Text ---

    fun updateAiInputText(text: String) {
        uiFeedbackAndAiState.update {
            it.copy(aiInputText = text, aiErrorMessage = null)
        }
    }

    fun appendRecognizedSpeech(spokenText: String) {
        if (spokenText.isBlank()) return
        uiFeedbackAndAiState.update { state ->
            val combined = if (state.aiInputText.isBlank()) {
                spokenText.trim()
            } else {
                "${state.aiInputText.trim()} ${spokenText.trim()}"
            }
            state.copy(aiInputText = combined, aiErrorMessage = null)
        }
    }

    fun analyzeAiInput(useLocalParserOnly: Boolean = false) {
        val currentText = uiFeedbackAndAiState.value.aiInputText.trim()
        if (currentText.isEmpty()) {
            uiFeedbackAndAiState.update {
                it.copy(aiErrorMessage = "يرجى كتابة أو إملاء معاملة واحدة على الأقل قبل التحليل.")
            }
            return
        }

        uiFeedbackAndAiState.update {
            it.copy(isAiParsing = true, aiErrorMessage = null, aiParseResult = null)
        }

        viewModelScope.launch {
            val todayIso = MoneyUtils.todayIsoDate()
            val parseRes = repository.parseMultiTransactionsWithAi(
                userText = currentText,
                todayIsoDate = todayIso,
                useLocalParserOnly = useLocalParserOnly
            )

            parseRes.fold(
                onSuccess = { result ->
                    val prefs = uiState.value.preferences
                    // Check if Auto-Save is enabled and ALL proposals meet strict criteria
                    val canAutoSave = prefs.autoSaveEnabled &&
                        result.proposals.isNotEmpty() &&
                        !result.needsClarification &&
                        result.proposals.all { draft ->
                            draft.isValidToSave &&
                                !draft.isAmbiguous &&
                                draft.categoryId != null &&
                                draft.confidence >= prefs.autoSaveConfidenceThreshold
                        }

                    if (canAutoSave) {
                        val batchRes = repository.saveReviewedBatchTransactions(
                            batchOperationId = result.batchOperationId,
                            drafts = result.proposals
                        )
                        batchRes.fold(
                            onSuccess = { count ->
                                uiFeedbackAndAiState.update {
                                    it.copy(
                                        isAiParsing = false,
                                        aiInputText = "",
                                        aiParseResult = null,
                                        aiErrorMessage = null,
                                        snackbarMessage = "تم الحفظ التلقائي لـ $count معاملات بنجاح"
                                    )
                                }
                            },
                            onFailure = { err ->
                                uiFeedbackAndAiState.update {
                                    it.copy(
                                        isAiParsing = false,
                                        aiParseResult = result,
                                        aiErrorMessage = err.message
                                    )
                                }
                            }
                        )
                    } else {
                        uiFeedbackAndAiState.update {
                            it.copy(
                                isAiParsing = false,
                                aiParseResult = result,
                                aiErrorMessage = result.errorMessage
                            )
                        }
                    }
                },
                onFailure = { err ->
                    val msg = (err as? AiServiceException)?.arabicMessage
                        ?: err.message
                        ?: "حدث خطأ أثناء تحليل المعاملات."
                    uiFeedbackAndAiState.update {
                        it.copy(isAiParsing = false, aiErrorMessage = msg)
                    }
                }
            )
        }
    }

    fun updateProposedDraft(updatedDraft: ProposedTransactionDraft) {
        val currentResult = uiFeedbackAndAiState.value.aiParseResult ?: return
        val recalculatedMinor = MoneyUtils.parseAmountToMinorUnits(updatedDraft.amountText)
        val hasValidDate = MoneyUtils.isoDateToEpochMillis(updatedDraft.isoDate) != null
        val isNowAmbiguous = recalculatedMinor == null ||
            recalculatedMinor <= 0L ||
            updatedDraft.description.isBlank() ||
            updatedDraft.categoryId == null ||
            !hasValidDate

        val ambiguityReason = when {
            updatedDraft.description.isBlank() -> "الوصف مطلوب"
            recalculatedMinor == null || recalculatedMinor <= 0L -> "يرجى إدخال مبلغ صحيح أكبر من الصفر"
            !hasValidDate -> "التاريخ غير صالح (YYYY-MM-DD)"
            updatedDraft.categoryId == null -> "يرجى اختيار تصنيف للمعاملة"
            else -> null
        }

        val normalizedDraft = updatedDraft.copy(
            amountMinorUnits = recalculatedMinor,
            isAmbiguous = isNowAmbiguous,
            ambiguityReason = ambiguityReason
        )

        val updatedList = currentResult.proposals.map {
            if (it.draftId == normalizedDraft.draftId) normalizedDraft else it
        }
        uiFeedbackAndAiState.update {
            it.copy(
                aiParseResult = currentResult.copy(
                    proposals = updatedList,
                    needsClarification = updatedList.any { d -> d.isAmbiguous }
                )
            )
        }
    }

    fun removeProposedDraft(draftId: String) {
        val currentResult = uiFeedbackAndAiState.value.aiParseResult ?: return
        val remaining = currentResult.proposals.filterNot { it.draftId == draftId }
        uiFeedbackAndAiState.update {
            it.copy(
                aiParseResult = if (remaining.isEmpty()) {
                    null
                } else {
                    currentResult.copy(
                        proposals = remaining,
                        needsClarification = remaining.any { d -> d.isAmbiguous }
                    )
                }
            )
        }
    }

    fun cancelAiReview() {
        uiFeedbackAndAiState.update {
            it.copy(aiParseResult = null, aiErrorMessage = null)
        }
    }

    fun saveAllReviewedProposals(onSavedSuccess: () -> Unit = {}) {
        val currentResult = uiFeedbackAndAiState.value.aiParseResult ?: return
        val invalidDraft = currentResult.proposals.firstOrNull { !it.isValidToSave }
        if (invalidDraft != null) {
            showSnackbar("يرجى استكمال المبلغ والوصف والتاريخ لجميع المعاملات المقترحة قبل الحفظ.")
            return
        }

        viewModelScope.launch {
            val saveRes = repository.saveReviewedBatchTransactions(
                batchOperationId = currentResult.batchOperationId,
                drafts = currentResult.proposals
            )
            saveRes.fold(
                onSuccess = { count ->
                    uiFeedbackAndAiState.update {
                        it.copy(
                            aiInputText = "",
                            aiParseResult = null,
                            aiErrorMessage = null,
                            snackbarMessage = "تم حفظ $count معاملات بنجاح"
                        )
                    }
                    onSavedSuccess()
                },
                onFailure = { err ->
                    showSnackbar(err.message ?: "تعذر حفظ الدفعة")
                }
            )
        }
    }

    // --- Categories Management ---

    fun saveCategory(
        existingId: String? = null,
        name: String,
        type: TransactionType,
        iconName: String,
        colorHex: Long,
        isArchived: Boolean = false
    ) {
        viewModelScope.launch {
            val res = repository.saveCategory(
                existingId = existingId,
                name = name,
                type = type,
                iconName = iconName,
                colorHex = colorHex,
                isArchived = isArchived
            )
            res.fold(
                onSuccess = {
                    showSnackbar(if (existingId != null) "تم تحديث التصنيف" else "تمت إضافة التصنيف")
                },
                onFailure = { err ->
                    showSnackbar(err.message ?: "تعذر حفظ التصنيف")
                }
            )
        }
    }

    fun toggleCategoryArchive(category: CategoryEntity) {
        viewModelScope.launch {
            repository.setCategoryArchived(category.id, !category.isArchived)
            showSnackbar(if (!category.isArchived) "تمت أرشفة التصنيف" else "تمت استعادة التصنيف")
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            val affected = repository.deleteCategorySafely(categoryId)
            val msg = if (affected > 0) {
                "تم حذف التصنيف وتحويل $affected معاملات مرتبطة إلى (غير مصنف) للحفاظ على سجلاتك."
            } else {
                "تم حذف التصنيف بنجاح."
            }
            showSnackbar(msg)
        }
    }

    // --- Budgets & Opening Balance ---

    fun saveOpeningBalance(amountText: String) {
        val minor = MoneyUtils.parseSignedAmountToMinorUnits(amountText)
        if (minor == null) {
            showSnackbar("يرجى إدخال رصيد افتتاحي صالح")
            return
        }
        viewModelScope.launch {
            repository.preferencesRepository.setOpeningBalance(minor)
            showSnackbar("تم تحديث الرصيد الافتتاحي")
        }
    }

    fun saveMonthlyBudget(
        yearMonth: String,
        categoryId: String?,
        amountText: String
    ) {
        val minor = MoneyUtils.parseAmountToMinorUnits(amountText)
        if (minor == null || minor <= 0L) {
            showSnackbar("يرجى إدخال مبلغ ميزانية صحيح أكبر من الصفر")
            return
        }
        viewModelScope.launch {
            repository.saveBudget(yearMonth, categoryId, minor)
            showSnackbar("تم حفظ الميزانية الشهرية")
        }
    }

    fun deleteBudget(budgetId: String) {
        viewModelScope.launch {
            repository.deleteBudget(budgetId)
            showSnackbar("تم حذف الميزانية")
        }
    }

    // --- Filters & Navigation State ---

    fun setHomeYearMonth(yearMonth: String) {
        financialFilterState.update { it.copy(selectedHomeYearMonth = yearMonth) }
    }

    fun setReportPeriodFilter(
        filter: ReportPeriodFilter,
        customStartIso: String = financialFilterState.value.customReportStartIso,
        customEndIso: String = financialFilterState.value.customReportEndIso
    ) {
        financialFilterState.update {
            it.copy(
                reportPeriodFilter = filter,
                customReportStartIso = customStartIso,
                customReportEndIso = customEndIso,
                selectedReportDrilldownCategoryId = null
            )
        }
        uiFeedbackAndAiState.update {
            it.copy(
                smartSummaryResultText = null,
                smartSummaryError = null
            )
        }
    }

    fun selectReportCategoryDrilldown(categoryId: String?) {
        financialFilterState.update {
            val next = if (it.selectedReportDrilldownCategoryId == categoryId) null else categoryId
            it.copy(selectedReportDrilldownCategoryId = next)
        }
    }

    fun updateHistoryFilter(filter: TransactionHistoryFilter) {
        financialFilterState.update { it.copy(historyFilter = filter) }
    }

    fun clearHistoryFilters() {
        financialFilterState.update { it.copy(historyFilter = TransactionHistoryFilter()) }
    }

    // --- Smart Financial Summary ---

    fun generateSmartSummaryForCurrentReport() {
        val summary = uiState.value.reportSummary
        val currency = uiState.value.preferences.currencySymbol
        val periodLabel = uiState.value.reportPeriodFilter.arabicLabel

        val topCategoriesText = if (summary.expenseByCategory.isEmpty()) {
            "لا توجد مصروفات مسجلة في هذه الفترة."
        } else {
            summary.expenseByCategory.take(3).joinToString("، ") { item ->
                "${item.categoryName}: ${MoneyUtils.formatMinorUnits(item.totalMinorUnits, currency)} (${String.format("%.1f", item.percentageOfTotal)}%)"
            }
        }

        val facts = """
            الفترة: $periodLabel
            إجمالي الدخل: ${MoneyUtils.formatMinorUnits(summary.periodIncomeMinor, currency)}
            إجمالي المصروفات: ${MoneyUtils.formatMinorUnits(summary.periodExpenseMinor, currency)}
            صافي التدفق النقدي: ${MoneyUtils.formatMinorUnits(summary.periodNetCashFlowMinor, currency, showPlusSign = true)}
            أعلى تصنيفات الإنفاق: $topCategoriesText
        """.trimIndent()

        uiFeedbackAndAiState.update {
            it.copy(
                isGeneratingSmartSummary = true,
                smartSummaryError = null,
                smartSummaryResultText = null
            )
        }

        viewModelScope.launch {
            val res = repository.generateSmartSummary(facts)
            res.fold(
                onSuccess = { text ->
                    uiFeedbackAndAiState.update {
                        it.copy(isGeneratingSmartSummary = false, smartSummaryResultText = text)
                    }
                },
                onFailure = { err ->
                    val msg = (err as? AiServiceException)?.arabicMessage
                        ?: err.message
                        ?: "تعذر إنشاء الملخص الذكي"
                    uiFeedbackAndAiState.update {
                        it.copy(isGeneratingSmartSummary = false, smartSummaryError = msg)
                    }
                }
            )
        }
    }

    // --- Settings & AI Provider Credentials ---

    fun updateCurrency(currencyCode: String) {
        viewModelScope.launch {
            repository.preferencesRepository.setCurrency(currencyCode)
            showSnackbar("تم تغيير العملة إلى $currencyCode")
        }
    }

    fun updateThemePreference(theme: ThemePreference) {
        viewModelScope.launch {
            repository.preferencesRepository.setThemePreference(theme)
        }
    }

    fun updateRefundAccountingMode(mode: RefundAccountingMode) {
        viewModelScope.launch {
            repository.preferencesRepository.setRefundAccountingMode(mode)
            showSnackbar("تم تحديث طريقة حساب الاسترداد")
        }
    }

    fun saveAiSettings(
        aiEnabled: Boolean,
        provider: AiProviderType,
        geminiModelId: String,
        openRouterModelId: String,
        newApiKeyInput: String,
        autoSaveEnabled: Boolean,
        autoSaveConfidence: Float,
        aiSummaryEnabled: Boolean
    ) {
        viewModelScope.launch {
            repository.preferencesRepository.setAiConfiguration(
                aiEnabled = aiEnabled,
                provider = provider,
                geminiModelId = geminiModelId,
                openRouterModelId = openRouterModelId,
                autoSaveEnabled = autoSaveEnabled,
                autoSaveConfidence = autoSaveConfidence,
                aiSummaryEnabled = aiSummaryEnabled
            )
            var keySaveErrorMessage: String? = null
            if (newApiKeyInput.isNotBlank()) {
                val keyId = when (provider) {
                    AiProviderType.GEMINI -> SecureKeyStorage.KEY_GEMINI_API
                    AiProviderType.OPENROUTER -> SecureKeyStorage.KEY_OPENROUTER_API
                }
                val saveRes = withContext(ioDispatcher) {
                    repository.preferencesRepository.secureKeyStorage.saveApiKey(keyId, newApiKeyInput.trim())
                }
                if (saveRes.isFailure) {
                    keySaveErrorMessage = saveRes.exceptionOrNull()?.message
                        ?: "تعذر تشفير مفتاح API بأمان عبر Android Keystore."
                }
            }
            keyRefreshTrigger.update { it + 1 }
            uiFeedbackAndAiState.update {
                it.copy(
                    snackbarMessage = keySaveErrorMessage ?: "تم حفظ إعدادات الذكاء الاصطناعي بنجاح"
                )
            }
        }
    }

    fun removeApiKey(provider: AiProviderType) {
        val keyId = when (provider) {
            AiProviderType.GEMINI -> SecureKeyStorage.KEY_GEMINI_API
            AiProviderType.OPENROUTER -> SecureKeyStorage.KEY_OPENROUTER_API
        }
        viewModelScope.launch {
            withContext(ioDispatcher) {
                repository.preferencesRepository.secureKeyStorage.removeApiKey(keyId)
            }
            keyRefreshTrigger.update { it + 1 }
            uiFeedbackAndAiState.update {
                it.copy(
                    connectionTestMessage = null,
                    connectionTestSuccess = null,
                    snackbarMessage = "تم حذف مفتاح API المحفوظ"
                )
            }
        }
    }

    fun testAiConnection(
        provider: AiProviderType,
        candidateKeyInput: String,
        modelIdInput: String
    ) {
        val storedKeyId = when (provider) {
            AiProviderType.GEMINI -> SecureKeyStorage.KEY_GEMINI_API
            AiProviderType.OPENROUTER -> SecureKeyStorage.KEY_OPENROUTER_API
        }

        uiFeedbackAndAiState.update {
            it.copy(
                isTestingConnection = true,
                connectionTestMessage = null,
                connectionTestSuccess = null
            )
        }

        viewModelScope.launch {
            val effectiveKey = candidateKeyInput.trim().ifEmpty {
                withContext(ioDispatcher) {
                    repository.preferencesRepository.secureKeyStorage.getApiKey(storedKeyId)
                }
            }
            val res = repository.testProviderConnection(
                provider = provider,
                apiKey = effectiveKey,
                modelId = modelIdInput
            )
            res.fold(
                onSuccess = { msg ->
                    uiFeedbackAndAiState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionTestMessage = msg,
                            connectionTestSuccess = true
                        )
                    }
                },
                onFailure = { err ->
                    val msg = (err as? AiServiceException)?.arabicMessage
                        ?: err.message
                        ?: "فشل الاتصال بالمزود"
                    uiFeedbackAndAiState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionTestMessage = msg,
                            connectionTestSuccess = false
                        )
                    }
                }
            )
        }
    }

    // --- Backup & CSV Export ---

    suspend fun createBackupJsonString(): String = withContext(ioDispatcher) {
        repository.createBackupJson()
    }

    suspend fun exportCsvString(): String = withContext(ioDispatcher) {
        repository.exportCsvString()
    }

    fun exportBackupToUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val json = repository.createBackupJson()
                    val out = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("تعذر فتح ملف الوجهة للكتابة")
                    out.use { stream ->
                        stream.write(json.toByteArray(StandardCharsets.UTF_8))
                        stream.flush()
                    }
                }
                showSnackbar("تم حفظ النسخة الاحتياطية (JSON) بنجاح بدون أي مفاتيح API.")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                showSnackbar("فشل تصدير النسخة الاحتياطية: ${e.message}")
            }
        }
    }

    fun importBackupFromUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            try {
                val content = withContext(ioDispatcher) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("تعذر فتح ملف النسخة الاحتياطية")
                    input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
                validateBackupFileContent(content)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                showSnackbar("تعذر قراءة ملف النسخة الاحتياطية: ${e.message}")
            }
        }
    }

    fun exportCsvToUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val csv = repository.exportCsvString()
                    val out = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("تعذر فتح ملف CSV للكتابة")
                    out.use { stream ->
                        stream.write(csv.toByteArray(StandardCharsets.UTF_8))
                        stream.flush()
                    }
                }
                showSnackbar("تم تصدير المعاملات إلى ملف CSV بنجاح.")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                showSnackbar("فشل تصدير ملف CSV: ${e.message}")
            }
        }
    }

    fun validateBackupFileContent(rawJson: String) {
        val preview = BackupExportManager.validateAndPreviewBackupJson(rawJson)
        if (!preview.isValid) {
            showSnackbar(preview.errorMessage ?: "ملف النسخة الاحتياطية غير صالح")
        }
        uiFeedbackAndAiState.update { it.copy(pendingBackupPreview = preview) }
    }

    fun dismissBackupPreview() {
        uiFeedbackAndAiState.update { it.copy(pendingBackupPreview = null) }
    }

    fun confirmRestoreBackup(mode: RestoreMode) {
        val preview = uiFeedbackAndAiState.value.pendingBackupPreview
        val payload = preview?.payload
        if (preview == null || !preview.isValid || payload == null) {
            showSnackbar("لا توجد نسخة احتياطية صالحة للاستعادة")
            return
        }
        viewModelScope.launch {
            val res = withContext(ioDispatcher) {
                repository.applyValidatedBackup(payload, mode)
            }
            res.fold(
                onSuccess = {
                    uiFeedbackAndAiState.update {
                        it.copy(
                            pendingBackupPreview = null,
                            snackbarMessage = "تمت استعادة النسخة الاحتياطية بنجاح (${payload.transactions.size} معاملة)"
                        )
                    }
                },
                onFailure = { err ->
                    showSnackbar(err.message ?: "فشل استعادة النسخة الاحتياطية")
                }
            )
        }
    }

    private fun applyHistoryFilters(
        allTransactions: List<TransactionEntity>,
        filter: TransactionHistoryFilter
    ): List<TransactionEntity> {
        val query = filter.searchQuery.trim().lowercase()
        val minMinor = MoneyUtils.parseAmountToMinorUnits(filter.minAmountText)
        val maxMinor = MoneyUtils.parseAmountToMinorUnits(filter.maxAmountText)
        val startMillis = MoneyUtils.isoDateToEpochMillis(filter.startIsoDate)?.let {
            MoneyUtils.startOfDayMillis(it)
        }
        val endMillis = MoneyUtils.isoDateToEpochMillis(filter.endIsoDate)?.let {
            MoneyUtils.endOfDayMillis(it)
        }

        return allTransactions.filter { tx ->
            val matchesQuery = query.isEmpty() ||
                tx.description.lowercase().contains(query) ||
                tx.notes.lowercase().contains(query)
            val matchesType = filter.selectedType == null || tx.type == filter.selectedType
            val matchesCat = when (filter.selectedCategoryId) {
                null -> true
                "UNCATEGORIZED" -> tx.categoryId == null
                else -> tx.categoryId == filter.selectedCategoryId
            }
            val matchesMin = minMinor == null || tx.amountMinorUnits >= minMinor
            val matchesMax = maxMinor == null || tx.amountMinorUnits <= maxMinor
            val matchesStart = startMillis == null || tx.transactionDate >= startMillis
            val matchesEnd = endMillis == null || tx.transactionDate <= endMillis

            matchesQuery && matchesType && matchesCat && matchesMin && matchesMax && matchesStart && matchesEnd
        }
    }

    private fun resolveReportPeriodBounds(
        filter: ReportPeriodFilter,
        customStartIso: String,
        customEndIso: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Triple<Long, Long, Pair<Long, Long>?> {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return when (filter) {
            ReportPeriodFilter.TODAY -> {
                val start = MoneyUtils.startOfDayMillis(nowMillis)
                val end = MoneyUtils.endOfDayMillis(nowMillis)
                val prevStart = start - 86_400_000L
                val prevEnd = end - 86_400_000L
                Triple(start, end, prevStart to prevEnd)
            }
            ReportPeriodFilter.CURRENT_WEEK -> {
                val end = MoneyUtils.endOfDayMillis(nowMillis)
                val startCal = (cal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, -6)
                }
                val start = MoneyUtils.startOfDayMillis(startCal.timeInMillis)
                val prevEnd = start - 1L
                val prevStart = prevEnd - (7 * 86_400_000L) + 1L
                Triple(start, end, prevStart to prevEnd)
            }
            ReportPeriodFilter.CURRENT_MONTH -> {
                val ym = MoneyUtils.currentYearMonth(nowMillis)
                val (start, end) = MoneyUtils.monthDateRange(ym)
                val prevYm = MoneyUtils.previousYearMonth(ym)
                val prevRange = MoneyUtils.monthDateRange(prevYm)
                Triple(start, end, prevRange)
            }
            ReportPeriodFilter.PREVIOUS_MONTH -> {
                val currentYm = MoneyUtils.currentYearMonth(nowMillis)
                val prevYm = MoneyUtils.previousYearMonth(currentYm)
                val (start, end) = MoneyUtils.monthDateRange(prevYm)
                val twoMonthsAgoYm = MoneyUtils.previousYearMonth(prevYm)
                val twoMonthsAgoRange = MoneyUtils.monthDateRange(twoMonthsAgoYm)
                Triple(start, end, twoMonthsAgoRange)
            }
            ReportPeriodFilter.CURRENT_YEAR -> {
                val year = cal.get(Calendar.YEAR)
                val (start, _) = MoneyUtils.monthDateRange("$year-01")
                val (_, end) = MoneyUtils.monthDateRange("$year-12")
                val prevYear = year - 1
                val (prevStart, _) = MoneyUtils.monthDateRange("$prevYear-01")
                val (_, prevEnd) = MoneyUtils.monthDateRange("$prevYear-12")
                Triple(start, end, prevStart to prevEnd)
            }
            ReportPeriodFilter.CUSTOM -> {
                val start = MoneyUtils.isoDateToEpochMillis(customStartIso)
                    ?.let { MoneyUtils.startOfDayMillis(it) }
                    ?: MoneyUtils.startOfDayMillis(nowMillis)
                val end = MoneyUtils.isoDateToEpochMillis(customEndIso)
                    ?.let { MoneyUtils.endOfDayMillis(it) }
                    ?: MoneyUtils.endOfDayMillis(nowMillis)
                val safeEnd = if (end >= start) end else MoneyUtils.endOfDayMillis(start)
                Triple(start, safeEnd, null)
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = MasroofiDatabase.getInstance(context)
            val prefsRepo = AppPreferencesRepository(context.applicationContext)
            val repository = FinanceRepository(db.dao(), prefsRepo)
            return MasroofiViewModel(repository) as T
        }
    }
}
