package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.CategoryEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.preferences.AppPreferencesState
import com.example.data.repository.FinanceRepository
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

data class TransactionHistoryFilter(
    val searchQuery: String = "",
    val selectedType: TransactionType? = null,
    val selectedCategoryId: String? = null,
    val selectedCurrencyCode: String? = null,
    val minAmountText: String = "",
    val maxAmountText: String = "",
    val startIsoDate: String = "",
    val endIsoDate: String = ""
)

data class TransactionsUiState(
    val preferences: AppPreferencesState = AppPreferencesState(),
    val categories: List<CategoryEntity> = emptyList(),
    val allTransactions: List<TransactionEntity> = emptyList(),
    val historyFilter: TransactionHistoryFilter = TransactionHistoryFilter(),
    val filteredHistoryTransactions: List<TransactionEntity> = emptyList(),
    val snackbarMessage: String? = null
)

class TransactionsViewModel(
    private val repository: FinanceRepository,
    private val snackbarManager: SnackbarManager = SnackbarManager(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val historyFilterState = MutableStateFlow(TransactionHistoryFilter())

    private val computedTransactionsFlow = combine(
        repository.preferencesFlow,
        repository.allCategoriesFlow,
        repository.allTransactionsFlow,
        historyFilterState
    ) { prefs, categories, transactions, filter ->
        val filtered = applyHistoryFilters(
            allTransactions = transactions,
            filter = filter,
            defaultCurrencyCode = prefs.currencyCode
        )
        TransactionsUiState(
            preferences = prefs,
            categories = categories,
            allTransactions = transactions,
            historyFilter = filter,
            filteredHistoryTransactions = filtered
        )
    }.flowOn(defaultDispatcher)

    val uiState: StateFlow<TransactionsUiState> = combine(
        computedTransactionsFlow,
        snackbarManager.messages
    ) { computed, snackbar ->
        computed.copy(snackbarMessage = snackbar)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TransactionsUiState()
    )

    fun updateHistoryFilter(filter: TransactionHistoryFilter) {
        val current = historyFilterState.value
        val isStartUpdated = filter.startIsoDate != current.startIsoDate ||
            filter.endIsoDate == current.endIsoDate
        val (normStart, normEnd) = MoneyUtils.normalizeDateRangeBounds(
            startIsoDate = filter.startIsoDate,
            endIsoDate = filter.endIsoDate,
            isStartUpdated = isStartUpdated
        )
        historyFilterState.value = filter.copy(
            startIsoDate = normStart,
            endIsoDate = normEnd
        )
    }

    fun clearHistoryFilters() {
        historyFilterState.value = TransactionHistoryFilter()
    }

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
            snackbarManager.showMessage("يرجى إدخال مبلغ صحيح أكبر من الصفر")
            return
        }
        val dateMillis = MoneyUtils.isoDateToEpochMillis(isoDate)
        if (dateMillis == null) {
            snackbarManager.showMessage("تاريخ غير صالح، يرجى استخدام الصيغة YYYY-MM-DD")
            return
        }
        if (description.isBlank()) {
            snackbarManager.showMessage("يرجى إدخال وصف المعاملة")
            return
        }

        viewModelScope.launch {
            val existingCurrency = existingId?.let { id ->
                uiState.value.allTransactions.firstOrNull { it.id == id }?.currencyCode
            }
            val currencyCode = existingCurrency ?: uiState.value.preferences.currencyCode
            val result = repository.saveManualTransaction(
                existingId = existingId,
                description = description,
                amountMinorUnits = minorUnits,
                currencyCode = currencyCode,
                type = type,
                categoryId = categoryId,
                transactionDate = dateMillis,
                notes = notes,
                isRefundOffset = isRefundOffset,
                preserveExistingCurrency = true
            )
            result.fold(
                onSuccess = {
                    snackbarManager.showMessage(
                        if (existingId != null) "تم تحديث المعاملة بنجاح" else "تم حفظ المعاملة بنجاح"
                    )
                    onSuccess()
                },
                onFailure = { err ->
                    snackbarManager.showMessage(err.message ?: "تعذر حفظ المعاملة")
                }
            )
        }
    }

    fun deleteTransaction(transactionId: String) {
        viewModelScope.launch {
            repository.deleteTransaction(transactionId)
            snackbarManager.showMessage("تم حذف المعاملة")
        }
    }

    fun clearSnackbar() {
        snackbarManager.clearMessage()
    }

    internal fun applyHistoryFilters(
        allTransactions: List<TransactionEntity>,
        filter: TransactionHistoryFilter,
        defaultCurrencyCode: String = "EGP"
    ): List<TransactionEntity> {
        val query = filter.searchQuery.trim().lowercase()
        val minMinor = MoneyUtils.parseAmountToMinorUnits(filter.minAmountText)
        val maxMinor = MoneyUtils.parseAmountToMinorUnits(filter.maxAmountText)
        val hasAmountBounds = minMinor != null || maxMinor != null
        val targetAmountCurrency = (filter.selectedCurrencyCode ?: defaultCurrencyCode).trim()
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
            val matchesSelectedCurrency = filter.selectedCurrencyCode == null ||
                tx.currencyCode.equals(filter.selectedCurrencyCode, ignoreCase = true)
            val matchesAmountCurrencyBasis = !hasAmountBounds ||
                tx.currencyCode.equals(targetAmountCurrency, ignoreCase = true)
            val matchesMin = minMinor == null ||
                (matchesAmountCurrencyBasis && tx.amountMinorUnits >= minMinor)
            val matchesMax = maxMinor == null ||
                (matchesAmountCurrencyBasis && tx.amountMinorUnits <= maxMinor)
            val matchesStart = startMillis == null || tx.transactionDate >= startMillis
            val matchesEnd = endMillis == null || tx.transactionDate <= endMillis

            matchesQuery &&
                matchesType &&
                matchesCat &&
                matchesSelectedCurrency &&
                matchesAmountCurrencyBasis &&
                matchesMin &&
                matchesMax &&
                matchesStart &&
                matchesEnd
        }
    }
}
