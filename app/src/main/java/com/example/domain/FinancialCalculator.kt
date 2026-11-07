package com.example.domain

import com.example.data.local.BudgetEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.RefundAccountingMode
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.preferences.SupportedCurrencies

enum class BudgetStatus {
    UNSET,
    NORMAL,
    APPROACHING, // >= 80%
    EXCEEDED     // > 100%
}

data class BudgetSummary(
    val budgetId: String?,
    val categoryId: String?,
    val categoryName: String?,
    val limitMinorUnits: Long,
    val spentMinorUnits: Long,
    val remainingMinorUnits: Long,
    val percentageUsed: Double,
    val status: BudgetStatus
)

data class CategoryBreakdownItem(
    val categoryId: String?,
    val categoryName: String,
    val iconName: String,
    val colorHex: Long,
    val totalMinorUnits: Long,
    val percentageOfTotal: Double,
    val transactionCount: Int
)

data class DailySpendingPoint(
    val isoDate: String,
    val dayLabel: String,
    val expenseMinorUnits: Long,
    val incomeMinorUnits: Long
)

data class MonthlyComparisonSummary(
    val currentPeriodExpenseMinor: Long,
    val previousPeriodExpenseMinor: Long,
    val hasComparisonData: Boolean,
    val differenceMinorUnits: Long,
    val percentageChange: Double?
)

data class CurrencyBalanceSummary(
    val currencyCode: String,
    val currencySymbol: String,
    val totalIncomeMinor: Long,
    val totalExpenseMinor: Long,
    val netBalanceMinor: Long
)

data class FinancialSummary(
    val currencyCode: String = "EGP",
    val openingBalanceMinorUnits: Long,
    val openingBalanceConfigured: Boolean,
    val totalAllTimeIncomeMinor: Long,
    val totalAllTimeExpenseMinor: Long,
    val currentBalanceMinor: Long,
    val periodIncomeMinor: Long,
    val periodExpenseMinor: Long,
    val periodNetCashFlowMinor: Long,
    val overallBudget: BudgetSummary?,
    val categoryBudgets: List<BudgetSummary>,
    val expenseByCategory: List<CategoryBreakdownItem>,
    val dailyTrends: List<DailySpendingPoint>,
    val monthlyComparison: MonthlyComparisonSummary,
    val otherCurrencyBalances: List<CurrencyBalanceSummary> = emptyList()
)

/**
 * Pure, deterministic financial calculator for Masroofi AI.
 * - Never fabricates numbers and protects against division by zero.
 * - Separates multi-currency transactions strictly by currencyCode (never adds USD + EGP together).
 * - Unifies refund offset calculations between category breakdowns and total expenses so a refund
 *   only offsets its own original expense category and never reduces unrelated categories.
 */
object FinancialCalculator {

    private data class CategoryNetExpenseCalc(
        val categoryId: String?,
        val grossExpenseMinor: Long,
        val appliedRefundOffsetMinor: Long,
        val netExpenseMinor: Long,
        val expenseTxCount: Int
    )

    fun filterByCurrency(
        transactions: List<TransactionEntity>,
        currencyCode: String?
    ): List<TransactionEntity> {
        if (currencyCode.isNullOrBlank()) return transactions
        return transactions.filter { it.currencyCode.equals(currencyCode, ignoreCase = true) }
    }

    private fun computeCategoryNetExpenses(
        transactions: List<TransactionEntity>,
        refundMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET
    ): List<CategoryNetExpenseCalc> {
        val expenseTransactions = transactions.filter { it.type == TransactionType.EXPENSE }
        if (expenseTransactions.isEmpty()) return emptyList()

        val refundOffsetsByCategory: Map<String?, Long> =
            if (refundMode == RefundAccountingMode.EXPENSE_OFFSET) {
                transactions
                    .filter { it.type == TransactionType.INCOME && it.isRefundOffset }
                    .groupBy { it.categoryId }
                    .mapValues { (_, list) -> list.sumOf { it.amountMinorUnits } }
            } else {
                emptyMap()
            }

        val grouped = expenseTransactions.groupBy { it.categoryId }
        return grouped.map { (catId, txs) ->
            val gross = txs.sumOf { it.amountMinorUnits }
            val rawOffset = refundOffsetsByCategory[catId] ?: 0L
            // A refund can only offset up to the gross expense of its own category
            val appliedOffset = minOf(gross, rawOffset)
            val net = (gross - appliedOffset).coerceAtLeast(0L)
            CategoryNetExpenseCalc(
                categoryId = catId,
                grossExpenseMinor = gross,
                appliedRefundOffsetMinor = appliedOffset,
                netExpenseMinor = net,
                expenseTxCount = txs.size
            )
        }
    }

    fun calculateTotalIncome(
        transactions: List<TransactionEntity>,
        refundMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET,
        currencyCode: String? = null
    ): Long {
        val scoped = filterByCurrency(transactions, currencyCode)
        val grossInflows = scoped
            .asSequence()
            .filter { it.type == TransactionType.INCOME }
            .sumOf { it.amountMinorUnits }

        val appliedRefundOffsets = computeCategoryNetExpenses(scoped, refundMode)
            .sumOf { it.appliedRefundOffsetMinor }

        return (grossInflows - appliedRefundOffsets).coerceAtLeast(0L)
    }

    fun calculateTotalExpenses(
        transactions: List<TransactionEntity>,
        refundMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET,
        currencyCode: String? = null
    ): Long {
        val scoped = filterByCurrency(transactions, currencyCode)
        return computeCategoryNetExpenses(scoped, refundMode).sumOf { it.netExpenseMinor }
    }

    fun calculateNetCashFlow(
        transactions: List<TransactionEntity>,
        refundMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET,
        currencyCode: String? = null
    ): Long {
        return calculateTotalIncome(transactions, refundMode, currencyCode) -
            calculateTotalExpenses(transactions, refundMode, currencyCode)
    }

    fun calculateCurrentBalance(
        openingBalanceMinorUnits: Long,
        allSavedTransactions: List<TransactionEntity>,
        currencyCode: String? = null
    ): Long {
        val scoped = filterByCurrency(allSavedTransactions, currencyCode)
        val netFlow = scoped.sumOf { tx ->
            when (tx.type) {
                TransactionType.INCOME -> tx.amountMinorUnits
                TransactionType.EXPENSE -> -tx.amountMinorUnits
            }
        }
        return openingBalanceMinorUnits + netFlow
    }

    fun calculateCurrencySummaries(
        allTransactions: List<TransactionEntity>,
        refundMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET
    ): List<CurrencyBalanceSummary> {
        if (allTransactions.isEmpty()) return emptyList()
        return allTransactions
            .groupBy { it.currencyCode.uppercase() }
            .entries
            .sortedBy { it.key }
            .map { (code, txs) ->
                val symbol = SupportedCurrencies.fromCode(code).symbol
                val income = calculateTotalIncome(txs, refundMode)
                val expense = calculateTotalExpenses(txs, refundMode)
                val net = txs.sumOf { tx ->
                    when (tx.type) {
                        TransactionType.INCOME -> tx.amountMinorUnits
                        TransactionType.EXPENSE -> -tx.amountMinorUnits
                    }
                }
                CurrencyBalanceSummary(
                    currencyCode = code,
                    currencySymbol = symbol,
                    totalIncomeMinor = income,
                    totalExpenseMinor = expense,
                    netBalanceMinor = net
                )
            }
    }

    fun filterByDateRange(
        transactions: List<TransactionEntity>,
        startMillisInclusive: Long,
        endMillisInclusive: Long
    ): List<TransactionEntity> {
        return transactions.filter { tx ->
            tx.transactionDate in startMillisInclusive..endMillisInclusive
        }
    }

    fun calculateExpenseCategoryBreakdown(
        periodTransactions: List<TransactionEntity>,
        categoriesMap: Map<String, CategoryEntity>,
        refundMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET,
        currencyCode: String? = null
    ): List<CategoryBreakdownItem> {
        val scoped = filterByCurrency(periodTransactions, currencyCode)
        val rawTotals = computeCategoryNetExpenses(scoped, refundMode)
            .filter { it.netExpenseMinor > 0L }

        val totalExpenses = rawTotals.sumOf { it.netExpenseMinor }
        if (totalExpenses <= 0L) return emptyList()

        return rawTotals
            .sortedByDescending { it.netExpenseMinor }
            .map { item ->
                val category = item.categoryId?.let { categoriesMap[it] }
                val percentage = (item.netExpenseMinor.toDouble() * 100.0) / totalExpenses.toDouble()
                CategoryBreakdownItem(
                    categoryId = item.categoryId,
                    categoryName = category?.name ?: "غير مصنف",
                    iconName = category?.iconName ?: "help_outline",
                    colorHex = category?.colorHex ?: 0xFF64748BL,
                    totalMinorUnits = item.netExpenseMinor,
                    percentageOfTotal = percentage,
                    transactionCount = item.expenseTxCount
                )
            }
    }

    fun calculateBudgetSummary(
        budget: BudgetEntity?,
        spentMinorUnits: Long,
        categoryName: String? = null
    ): BudgetSummary? {
        if (budget == null || budget.limitMinorUnits <= 0L) return null
        val limit = budget.limitMinorUnits
        val remaining = limit - spentMinorUnits
        val pct = (spentMinorUnits.toDouble() * 100.0) / limit.toDouble()
        val status = when {
            spentMinorUnits > limit -> BudgetStatus.EXCEEDED
            pct >= 80.0 -> BudgetStatus.APPROACHING
            else -> BudgetStatus.NORMAL
        }
        return BudgetSummary(
            budgetId = budget.id,
            categoryId = budget.categoryId,
            categoryName = categoryName,
            limitMinorUnits = limit,
            spentMinorUnits = spentMinorUnits,
            remainingMinorUnits = remaining,
            percentageUsed = pct,
            status = status
        )
    }

    fun calculateDailyTrends(
        periodTransactions: List<TransactionEntity>,
        refundMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET,
        currencyCode: String? = null
    ): List<DailySpendingPoint> {
        val scoped = filterByCurrency(periodTransactions, currencyCode)
        if (scoped.isEmpty()) return emptyList()
        val byDay = scoped.groupBy { MoneyUtils.epochMillisToIsoDate(it.transactionDate) }
        return byDay.keys.sorted().map { isoDate ->
            val list = byDay[isoDate].orEmpty()
            val expense = calculateTotalExpenses(list, refundMode)
            val income = calculateTotalIncome(list, refundMode)
            val dayLabel = isoDate.takeLast(5) // MM-DD
            DailySpendingPoint(
                isoDate = isoDate,
                dayLabel = dayLabel,
                expenseMinorUnits = expense,
                incomeMinorUnits = income
            )
        }
    }

    fun calculateMonthlyComparison(
        currentPeriodTransactions: List<TransactionEntity>,
        previousPeriodTransactions: List<TransactionEntity>,
        refundMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET,
        currencyCode: String? = null
    ): MonthlyComparisonSummary {
        val currentScoped = filterByCurrency(currentPeriodTransactions, currencyCode)
        val prevScoped = filterByCurrency(previousPeriodTransactions, currencyCode)
        val currentExpense = calculateTotalExpenses(currentScoped, refundMode)
        val prevExpense = calculateTotalExpenses(prevScoped, refundMode)
        val hasPreviousData = prevScoped.any { it.type == TransactionType.EXPENSE } &&
            prevExpense > 0L
        val diff = currentExpense - prevExpense
        val pctChange = if (hasPreviousData) {
            (diff.toDouble() * 100.0) / prevExpense.toDouble()
        } else {
            null
        }
        return MonthlyComparisonSummary(
            currentPeriodExpenseMinor = currentExpense,
            previousPeriodExpenseMinor = prevExpense,
            hasComparisonData = hasPreviousData,
            differenceMinorUnits = diff,
            percentageChange = pctChange
        )
    }

    fun buildSummary(
        openingBalanceMinorUnits: Long,
        openingBalanceConfigured: Boolean,
        allTransactions: List<TransactionEntity>,
        periodTransactions: List<TransactionEntity>,
        previousPeriodTransactions: List<TransactionEntity>,
        categories: List<CategoryEntity>,
        monthBudgets: List<BudgetEntity>,
        currencyCode: String = "EGP",
        refundAccountingMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET
    ): FinancialSummary {
        val categoriesMap = categories.associateBy { it.id }
        val allScoped = filterByCurrency(allTransactions, currencyCode)
        val periodScoped = filterByCurrency(periodTransactions, currencyCode)
        val prevScoped = filterByCurrency(previousPeriodTransactions, currencyCode)

        val allTimeIncome = calculateTotalIncome(allScoped, refundAccountingMode)
        val allTimeExpense = calculateTotalExpenses(allScoped, refundAccountingMode)
        val currentBalance = calculateCurrentBalance(openingBalanceMinorUnits, allScoped)

        val periodIncome = calculateTotalIncome(periodScoped, refundAccountingMode)
        val periodExpense = calculateTotalExpenses(periodScoped, refundAccountingMode)
        val periodNet = periodIncome - periodExpense

        val breakdown = calculateExpenseCategoryBreakdown(
            periodTransactions = periodScoped,
            categoriesMap = categoriesMap,
            refundMode = refundAccountingMode
        )
        val overallBudgetEntity = monthBudgets.firstOrNull { it.categoryId == null }
        val overallBudgetSummary = calculateBudgetSummary(
            budget = overallBudgetEntity,
            spentMinorUnits = periodExpense,
            categoryName = "الميزانية الشهرية العامة"
        )

        val spentByCategoryMap = breakdown.associate { it.categoryId to it.totalMinorUnits }
        val categoryBudgetSummaries = monthBudgets
            .filter { it.categoryId != null && it.limitMinorUnits > 0L }
            .mapNotNull { b ->
                val cat = categoriesMap[b.categoryId]
                val spent = spentByCategoryMap[b.categoryId] ?: 0L
                calculateBudgetSummary(
                    budget = b,
                    spentMinorUnits = spent,
                    categoryName = cat?.name ?: "تصنيف"
                )
            }

        val dailyTrends = calculateDailyTrends(periodScoped, refundAccountingMode)
        val comparison = calculateMonthlyComparison(periodScoped, prevScoped, refundAccountingMode)

        val otherCurrencies = calculateCurrencySummaries(
            allTransactions = allTransactions.filter { !it.currencyCode.equals(currencyCode, ignoreCase = true) },
            refundMode = refundAccountingMode
        )

        return FinancialSummary(
            currencyCode = currencyCode,
            openingBalanceMinorUnits = openingBalanceMinorUnits,
            openingBalanceConfigured = openingBalanceConfigured,
            totalAllTimeIncomeMinor = allTimeIncome,
            totalAllTimeExpenseMinor = allTimeExpense,
            currentBalanceMinor = currentBalance,
            periodIncomeMinor = periodIncome,
            periodExpenseMinor = periodExpense,
            periodNetCashFlowMinor = periodNet,
            overallBudget = overallBudgetSummary,
            categoryBudgets = categoryBudgetSummaries,
            expenseByCategory = breakdown,
            dailyTrends = dailyTrends,
            monthlyComparison = comparison,
            otherCurrencyBalances = otherCurrencies
        )
    }
}
