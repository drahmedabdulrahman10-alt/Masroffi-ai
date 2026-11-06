package com.example.domain

import com.example.data.local.BudgetEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import java.util.Calendar

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

data class FinancialSummary(
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
    val monthlyComparison: MonthlyComparisonSummary
)

/**
 * Pure, deterministic financial calculator for Masroofi AI.
 * Never fabricates numbers, protects against division by zero, and calculates balances strictly
 * from saved transactions and configured opening balance.
 */
object FinancialCalculator {

    fun calculateTotalIncome(transactions: List<TransactionEntity>): Long {
        return transactions
            .asSequence()
            .filter { it.type == TransactionType.INCOME && !it.isRefundOffset }
            .sumOf { it.amountMinorUnits }
    }

    fun calculateTotalExpenses(transactions: List<TransactionEntity>): Long {
        val grossExpenses = transactions
            .asSequence()
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amountMinorUnits }

        val refundOffsets = transactions
            .asSequence()
            .filter { it.type == TransactionType.INCOME && it.isRefundOffset }
            .sumOf { it.amountMinorUnits }

        return (grossExpenses - refundOffsets).coerceAtLeast(0L)
    }

    fun calculateNetCashFlow(transactions: List<TransactionEntity>): Long {
        return calculateTotalIncome(transactions) - calculateTotalExpenses(transactions)
    }

    fun calculateCurrentBalance(
        openingBalanceMinorUnits: Long,
        allSavedTransactions: List<TransactionEntity>
    ): Long {
        val netFlow = allSavedTransactions.sumOf { tx ->
            when (tx.type) {
                TransactionType.INCOME -> tx.amountMinorUnits
                TransactionType.EXPENSE -> -tx.amountMinorUnits
            }
        }
        return openingBalanceMinorUnits + netFlow
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
        categoriesMap: Map<String, CategoryEntity>
    ): List<CategoryBreakdownItem> {
        val expenseTransactions = periodTransactions.filter { it.type == TransactionType.EXPENSE }
        val refundOffsetsByCategory = periodTransactions
            .filter { it.type == TransactionType.INCOME && it.isRefundOffset }
            .groupBy { it.categoryId }
            .mapValues { (_, list) -> list.sumOf { it.amountMinorUnits } }

        if (expenseTransactions.isEmpty()) return emptyList()

        val grouped = expenseTransactions.groupBy { it.categoryId }
        val rawTotals = grouped.map { (catId, txs) ->
            val gross = txs.sumOf { it.amountMinorUnits }
            val offset = refundOffsetsByCategory[catId] ?: 0L
            val net = (gross - offset).coerceAtLeast(0L)
            Triple(catId, net, txs.size)
        }.filter { it.second > 0L }

        val totalExpenses = rawTotals.sumOf { it.second }
        if (totalExpenses <= 0L) return emptyList()

        return rawTotals
            .sortedByDescending { it.second }
            .map { (catId, netTotal, count) ->
                val category = catId?.let { categoriesMap[it] }
                val percentage = if (totalExpenses > 0L) {
                    (netTotal.toDouble() * 100.0) / totalExpenses.toDouble()
                } else {
                    0.0
                }
                CategoryBreakdownItem(
                    categoryId = catId,
                    categoryName = category?.name ?: "غير مصنف",
                    iconName = category?.iconName ?: "help_outline",
                    colorHex = category?.colorHex ?: 0xFF64748BL,
                    totalMinorUnits = netTotal,
                    percentageOfTotal = percentage,
                    transactionCount = count
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
        periodTransactions: List<TransactionEntity>
    ): List<DailySpendingPoint> {
        if (periodTransactions.isEmpty()) return emptyList()
        val byDay = periodTransactions.groupBy { MoneyUtils.epochMillisToIsoDate(it.transactionDate) }
        return byDay.keys.sorted().map { isoDate ->
            val list = byDay[isoDate].orEmpty()
            val expense = calculateTotalExpenses(list)
            val income = calculateTotalIncome(list)
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
        previousPeriodTransactions: List<TransactionEntity>
    ): MonthlyComparisonSummary {
        val currentExpense = calculateTotalExpenses(currentPeriodTransactions)
        val prevExpense = calculateTotalExpenses(previousPeriodTransactions)
        val hasPreviousData = previousPeriodTransactions.any { it.type == TransactionType.EXPENSE } &&
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
        monthBudgets: List<BudgetEntity>
    ): FinancialSummary {
        val categoriesMap = categories.associateBy { it.id }
        val allTimeIncome = calculateTotalIncome(allTransactions)
        val allTimeExpense = calculateTotalExpenses(allTransactions)
        val currentBalance = calculateCurrentBalance(openingBalanceMinorUnits, allTransactions)

        val periodIncome = calculateTotalIncome(periodTransactions)
        val periodExpense = calculateTotalExpenses(periodTransactions)
        val periodNet = periodIncome - periodExpense

        val breakdown = calculateExpenseCategoryBreakdown(periodTransactions, categoriesMap)
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

        val dailyTrends = calculateDailyTrends(periodTransactions)
        val comparison = calculateMonthlyComparison(periodTransactions, previousPeriodTransactions)

        return FinancialSummary(
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
            monthlyComparison = comparison
        )
    }
}
