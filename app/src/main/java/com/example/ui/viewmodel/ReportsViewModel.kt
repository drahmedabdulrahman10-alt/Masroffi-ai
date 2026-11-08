package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.CategoryEntity
import com.example.data.local.TransactionEntity
import com.example.data.preferences.AppPreferencesState
import com.example.data.repository.FinanceRepository
import com.example.domain.FinancialCalculator
import com.example.domain.FinancialSummary
import com.example.domain.MoneyUtils
import com.example.domain.ai.AiServiceException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

enum class ReportPeriodFilter(val arabicLabel: String) {
    TODAY("اليوم"),
    CURRENT_WEEK("هذا الأسبوع"),
    CURRENT_MONTH("هذا الشهر"),
    PREVIOUS_MONTH("الشهر السابق"),
    CURRENT_YEAR("هذه السنة"),
    CUSTOM("فترة مخصصة")
}

data class ReportsUiState(
    val preferences: AppPreferencesState = AppPreferencesState(),
    val categories: List<CategoryEntity> = emptyList(),
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
    val isGeneratingSmartSummary: Boolean = false,
    val smartSummaryResultText: String? = null,
    val smartSummaryError: String? = null,
    val snackbarMessage: String? = null
)

private data class LocalReportsFilterState(
    val reportPeriodFilter: ReportPeriodFilter = ReportPeriodFilter.CURRENT_MONTH,
    val customReportStartIso: String = MoneyUtils.todayIsoDate(),
    val customReportEndIso: String = MoneyUtils.todayIsoDate(),
    val selectedReportDrilldownCategoryId: String? = null
)

private data class LocalSmartSummaryState(
    val isGeneratingSmartSummary: Boolean = false,
    val smartSummaryResultText: String? = null,
    val smartSummaryError: String? = null
)

class ReportsViewModel(
    private val repository: FinanceRepository,
    private val snackbarManager: SnackbarManager = SnackbarManager(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val filterState = MutableStateFlow(LocalReportsFilterState())
    private val smartSummaryState = MutableStateFlow(LocalSmartSummaryState())

    private val computedReportsFlow = combine(
        repository.preferencesFlow,
        repository.allCategoriesFlow,
        repository.allTransactionsFlow,
        repository.allBudgetsFlow,
        filterState
    ) { prefs, categories, transactions, budgets, filters ->
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

        ReportsUiState(
            preferences = prefs,
            categories = categories,
            reportPeriodFilter = filters.reportPeriodFilter,
            customReportStartIso = filters.customReportStartIso,
            customReportEndIso = filters.customReportEndIso,
            reportSummary = reportSummary,
            selectedReportDrilldownCategoryId = filters.selectedReportDrilldownCategoryId,
            reportPeriodTransactions = reportTxs
        )
    }.flowOn(defaultDispatcher)

    val uiState: StateFlow<ReportsUiState> = combine(
        computedReportsFlow,
        smartSummaryState,
        snackbarManager.messages
    ) { computed, summaryUi, snackbar ->
        computed.copy(
            isGeneratingSmartSummary = summaryUi.isGeneratingSmartSummary,
            smartSummaryResultText = summaryUi.smartSummaryResultText,
            smartSummaryError = summaryUi.smartSummaryError,
            snackbarMessage = snackbar
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ReportsUiState()
    )

    fun setReportPeriodFilter(
        filter: ReportPeriodFilter,
        customStartIso: String = filterState.value.customReportStartIso,
        customEndIso: String = filterState.value.customReportEndIso
    ) {
        filterState.update {
            it.copy(
                reportPeriodFilter = filter,
                customReportStartIso = customStartIso,
                customReportEndIso = customEndIso,
                selectedReportDrilldownCategoryId = null
            )
        }
        smartSummaryState.update {
            it.copy(
                smartSummaryResultText = null,
                smartSummaryError = null
            )
        }
    }

    fun selectReportCategoryDrilldown(categoryId: String?) {
        filterState.update {
            val next = if (it.selectedReportDrilldownCategoryId == categoryId) null else categoryId
            it.copy(selectedReportDrilldownCategoryId = next)
        }
    }

    fun generateSmartSummaryForCurrentReport() {
        val summary = uiState.value.reportSummary
        val currency = uiState.value.preferences.currencySymbol
        val periodLabel = uiState.value.reportPeriodFilter.arabicLabel

        val topCategoriesText = if (summary.expenseByCategory.isEmpty()) {
            "لا توجد مصروفات مسجلة في هذه الفترة."
        } else {
            summary.expenseByCategory.take(3).joinToString("، ") { item ->
                "${item.categoryName}: ${MoneyUtils.formatMinorUnits(item.totalMinorUnits, currency)} (${String.format(Locale.US, "%.1f", item.percentageOfTotal)}%)"
            }
        }

        val facts = """
            الفترة: $periodLabel
            إجمالي الدخل: ${MoneyUtils.formatMinorUnits(summary.periodIncomeMinor, currency)}
            إجمالي المصروفات: ${MoneyUtils.formatMinorUnits(summary.periodExpenseMinor, currency)}
            صافي التدفق النقدي: ${MoneyUtils.formatMinorUnits(summary.periodNetCashFlowMinor, currency, showPlusSign = true)}
            أعلى تصنيفات الإنفاق: $topCategoriesText
        """.trimIndent()

        smartSummaryState.update {
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
                    smartSummaryState.update {
                        it.copy(isGeneratingSmartSummary = false, smartSummaryResultText = text)
                    }
                },
                onFailure = { err ->
                    val msg = (err as? AiServiceException)?.arabicMessage
                        ?: err.message
                        ?: "تعذر إنشاء الملخص الذكي"
                    smartSummaryState.update {
                        it.copy(isGeneratingSmartSummary = false, smartSummaryError = msg)
                    }
                }
            )
        }
    }

    fun clearSnackbar() {
        snackbarManager.clearMessage()
    }

    internal fun resolveReportPeriodBounds(
        filter: ReportPeriodFilter,
        customStartIso: String,
        customEndIso: String,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Triple<Long, Long, Pair<Long, Long>?> {
        val currentLocalDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        return when (filter) {
            ReportPeriodFilter.TODAY -> {
                val start = currentLocalDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = currentLocalDate.atTime(LocalTime.of(23, 59, 59, 999_000_000))
                    .atZone(zoneId).toInstant().toEpochMilli()
                val prevDate = currentLocalDate.minusDays(1)
                val prevStart = prevDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val prevEnd = prevDate.atTime(LocalTime.of(23, 59, 59, 999_000_000))
                    .atZone(zoneId).toInstant().toEpochMilli()
                Triple(start, end, prevStart to prevEnd)
            }
            ReportPeriodFilter.CURRENT_WEEK -> {
                val end = currentLocalDate.atTime(LocalTime.of(23, 59, 59, 999_000_000))
                    .atZone(zoneId).toInstant().toEpochMilli()
                val startDate = currentLocalDate.minusDays(6)
                val start = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val prevEndDate = startDate.minusDays(1)
                val prevStartDate = prevEndDate.minusDays(6)
                val prevStart = prevStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val prevEnd = prevEndDate.atTime(LocalTime.of(23, 59, 59, 999_000_000))
                    .atZone(zoneId).toInstant().toEpochMilli()
                Triple(start, end, prevStart to prevEnd)
            }
            ReportPeriodFilter.CURRENT_MONTH -> {
                val ym = MoneyUtils.currentYearMonth(nowMillis, zoneId)
                val (start, end) = MoneyUtils.monthDateRange(ym, zoneId)
                val prevYm = MoneyUtils.previousYearMonth(ym)
                val prevRange = MoneyUtils.monthDateRange(prevYm, zoneId)
                Triple(start, end, prevRange)
            }
            ReportPeriodFilter.PREVIOUS_MONTH -> {
                val currentYm = MoneyUtils.currentYearMonth(nowMillis, zoneId)
                val prevYm = MoneyUtils.previousYearMonth(currentYm)
                val (start, end) = MoneyUtils.monthDateRange(prevYm, zoneId)
                val twoMonthsAgoYm = MoneyUtils.previousYearMonth(prevYm)
                val twoMonthsAgoRange = MoneyUtils.monthDateRange(twoMonthsAgoYm, zoneId)
                Triple(start, end, twoMonthsAgoRange)
            }
            ReportPeriodFilter.CURRENT_YEAR -> {
                val year = currentLocalDate.year
                val (start, _) = MoneyUtils.monthDateRange("$year-01", zoneId)
                val (_, end) = MoneyUtils.monthDateRange("$year-12", zoneId)
                val prevYear = year - 1
                val (prevStart, _) = MoneyUtils.monthDateRange("$prevYear-01", zoneId)
                val (_, prevEnd) = MoneyUtils.monthDateRange("$prevYear-12", zoneId)
                Triple(start, end, prevStart to prevEnd)
            }
            ReportPeriodFilter.CUSTOM -> {
                val start = MoneyUtils.isoDateToEpochMillis(customStartIso, zoneId)
                    ?.let { MoneyUtils.startOfDayMillis(it, zoneId) }
                    ?: MoneyUtils.startOfDayMillis(nowMillis, zoneId)
                val end = MoneyUtils.isoDateToEpochMillis(customEndIso, zoneId)
                    ?.let { MoneyUtils.endOfDayMillis(it, zoneId) }
                    ?: MoneyUtils.endOfDayMillis(nowMillis, zoneId)
                val safeEnd = if (end >= start) end else MoneyUtils.endOfDayMillis(start, zoneId)
                Triple(start, safeEnd, null)
            }
        }
    }
}
