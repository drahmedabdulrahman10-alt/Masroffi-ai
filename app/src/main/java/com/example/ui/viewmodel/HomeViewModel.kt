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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val preferences: AppPreferencesState = AppPreferencesState(),
    val categories: List<CategoryEntity> = emptyList(),
    val allTransactions: List<TransactionEntity> = emptyList(),
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
    val snackbarMessage: String? = null
)

class HomeViewModel(
    private val repository: FinanceRepository,
    private val snackbarManager: SnackbarManager = SnackbarManager(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val selectedHomeYearMonth = MutableStateFlow(MoneyUtils.currentYearMonth())

    init {
        viewModelScope.launch(ioDispatcher) {
            repository.ensureDefaultCategoriesSeeded()
        }
    }

    private val computedHomeFlow = combine(
        repository.preferencesFlow,
        repository.allCategoriesFlow,
        repository.allTransactionsFlow,
        repository.allBudgetsFlow,
        selectedHomeYearMonth
    ) { prefs, categories, transactions, budgets, homeYearMonth ->
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

        HomeUiState(
            preferences = prefs,
            categories = categories,
            allTransactions = transactions,
            selectedHomeYearMonth = homeYearMonth,
            homeSummary = homeSummary
        )
    }.flowOn(defaultDispatcher)

    val uiState: StateFlow<HomeUiState> = combine(
        computedHomeFlow,
        snackbarManager.messages
    ) { computed, snackbar ->
        computed.copy(snackbarMessage = snackbar)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState()
    )

    fun setHomeYearMonth(yearMonth: String) {
        selectedHomeYearMonth.value = yearMonth
    }

    fun saveOpeningBalance(amountText: String) {
        val minor = MoneyUtils.parseSignedAmountToMinorUnits(amountText)
        if (minor == null) {
            snackbarManager.showMessage("يرجى إدخال رصيد افتتاحي صالح")
            return
        }
        viewModelScope.launch {
            repository.preferencesRepository.setOpeningBalance(minor)
            snackbarManager.showMessage("تم تحديث الرصيد الافتتاحي")
        }
    }

    fun saveMonthlyBudget(
        yearMonth: String,
        categoryId: String?,
        amountText: String
    ) {
        val minor = MoneyUtils.parseAmountToMinorUnits(amountText)
        if (minor == null || minor <= 0L) {
            snackbarManager.showMessage("يرجى إدخال مبلغ ميزانية صحيح أكبر من الصفر")
            return
        }
        viewModelScope.launch {
            repository.saveBudget(yearMonth, categoryId, minor)
            snackbarManager.showMessage("تم حفظ الميزانية الشهرية")
        }
    }

    fun clearSnackbar() {
        snackbarManager.clearMessage()
    }
}
