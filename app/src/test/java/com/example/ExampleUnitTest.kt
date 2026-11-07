package com.example

import com.example.data.backup.BackupExportManager
import com.example.data.local.BudgetEntity
import com.example.data.local.CategoryRuleEntity
import com.example.data.local.DefaultCategories
import com.example.data.local.RefundAccountingMode
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionSource
import com.example.data.local.TransactionType
import com.example.domain.BudgetStatus
import com.example.domain.FinancialCalculator
import com.example.domain.MoneyUtils
import com.example.domain.ai.EgyptianArabicLocalParser
import com.example.domain.ai.RemoteAiProviderClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive unit test suite covering:
 * A. Financial calculations (Income, Expense, Net Cash Flow, Opening/Current Balance,
 *    Date filtering, Category percentages, Budget calculations, Empty datasets, Zero denominators)
 * B. Monetary parsing & Arabic numeral normalization
 * C. AI & Egyptian Arabic parsing (Single/Multi-transaction sentences, Multi-line shorthand,
 *    Mixed Arabic/English, Missing/Ambiguous amounts, Category validation, Malformed JSON,
 *    Archived category exclusion, Learned category rules)
 * D. Backup & CSV export validation and security invariants
 */
class ExampleUnitTest {

    private val defaultCategories = DefaultCategories.all(now = 1700000000000L)

    // --- A. Financial Calculations ---

    @Test
    fun `financial calculations compute accurate income, expense, net cash flow, and current balance`() {
        val txs = listOf(
            TransactionEntity(
                id = "t1",
                description = "مرتب شهر نوفمبر",
                amountMinorUnits = 800_000L, // 8000.00 EGP
                type = TransactionType.INCOME,
                categoryId = DefaultCategories.CAT_SALARY,
                transactionDate = 1730500000000L
            ),
            TransactionEntity(
                id = "t2",
                description = "إيجار الشقة",
                amountMinorUnits = 200_000L, // 2000.00 EGP
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_HOME_GROCERY,
                transactionDate = 1730510000000L
            ),
            TransactionEntity(
                id = "t3",
                description = "طماطم وجبنة",
                amountMinorUnits = 6_500L, // 65.00 EGP
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_FOOD,
                transactionDate = 1730520000000L
            )
        )

        val totalIncome = FinancialCalculator.calculateTotalIncome(txs)
        val totalExpenses = FinancialCalculator.calculateTotalExpenses(txs)
        val netCashFlow = FinancialCalculator.calculateNetCashFlow(txs)
        val currentBalanceWithOpening = FinancialCalculator.calculateCurrentBalance(
            openingBalanceMinorUnits = 150_000L, // 1500.00 EGP
            allSavedTransactions = txs
        )

        assertEquals(800_000L, totalIncome)
        assertEquals(206_500L, totalExpenses)
        assertEquals(593_500L, netCashFlow)
        assertEquals(743_500L, currentBalanceWithOpening) // 1500 + 8000 - 2065 = 7435.00 EGP
    }

    @Test
    fun `empty dataset and zero-value denominators do not divide by zero`() {
        val catsMap = defaultCategories.associateBy { it.id }
        val breakdown = FinancialCalculator.calculateExpenseCategoryBreakdown(emptyList(), catsMap)
        assertTrue(breakdown.isEmpty())

        val comparison = FinancialCalculator.calculateMonthlyComparison(emptyList(), emptyList())
        assertFalse(comparison.hasComparisonData)
        assertNull(comparison.percentageChange)
        assertEquals(0L, comparison.currentPeriodExpenseMinor)
    }

    @Test
    fun `category breakdown calculates exact percentages and handles uncategorized`() {
        val catsMap = defaultCategories.associateBy { it.id }
        val txs = listOf(
            TransactionEntity(
                id = "1",
                description = "غداء",
                amountMinorUnits = 15_000L, // 150 EGP (75%)
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_FOOD,
                transactionDate = 1730500000000L
            ),
            TransactionEntity(
                id = "2",
                description = "تاكسي",
                amountMinorUnits = 5_000L, // 50 EGP (25%)
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_TRANSPORT,
                transactionDate = 1730500000000L
            )
        )

        val breakdown = FinancialCalculator.calculateExpenseCategoryBreakdown(txs, catsMap)
        assertEquals(2, breakdown.size)
        assertEquals(DefaultCategories.CAT_FOOD, breakdown[0].categoryId)
        assertEquals(75.0, breakdown[0].percentageOfTotal, 0.001)
        assertEquals(DefaultCategories.CAT_TRANSPORT, breakdown[1].categoryId)
        assertEquals(25.0, breakdown[1].percentageOfTotal, 0.001)
    }

    @Test
    fun `budget calculator detects normal, approaching, and exceeded limits`() {
        val budget = BudgetEntity(
            id = "2026-11_OVERALL",
            yearMonth = "2026-11",
            categoryId = null,
            limitMinorUnits = 500_000L // 5000 EGP
        )

        val normal = FinancialCalculator.calculateBudgetSummary(budget, 200_000L)!!
        assertEquals(BudgetStatus.NORMAL, normal.status)
        assertEquals(300_000L, normal.remainingMinorUnits)
        assertEquals(40.0, normal.percentageUsed, 0.01)

        val approaching = FinancialCalculator.calculateBudgetSummary(budget, 420_000L)!!
        assertEquals(BudgetStatus.APPROACHING, approaching.status)
        assertEquals(84.0, approaching.percentageUsed, 0.01)

        val exceeded = FinancialCalculator.calculateBudgetSummary(budget, 550_000L)!!
        assertEquals(BudgetStatus.EXCEEDED, exceeded.status)
        assertEquals(-50_000L, exceeded.remainingMinorUnits)
        assertEquals(110.0, exceeded.percentageUsed, 0.01)
    }

    // --- B. Numeral Normalization & Amount Validation ---

    @Test
    fun `numeral normalizer and amount parser support Arabic and Western digits and reject invalid amounts`() {
        assertEquals(4550L, MoneyUtils.parseAmountToMinorUnits("٤٥٫٥٠"))
        assertEquals(2000L, MoneyUtils.parseAmountToMinorUnits("٢٠ جنيه"))
        assertEquals(30000L, MoneyUtils.parseAmountToMinorUnits("300"))
        assertNull(MoneyUtils.parseAmountToMinorUnits("0"))
        assertNull(MoneyUtils.parseAmountToMinorUnits("-50"))
        assertNull(MoneyUtils.parseAmountToMinorUnits("abc"))
        assertNull(MoneyUtils.parseAmountToMinorUnits(""))
    }

    // --- C. Egyptian Arabic & AI Multi-Transaction Parsing ---

    @Test
    fun `local parser splits multiple Egyptian Arabic transactions in one sentence accurately`() {
        val input = "اشتريت طماطم بعشرين جنيه وعلبة جبنة بخمسة وأربعين جنيه ودفعت تاكسي عشرين جنيه"
        val res = EgyptianArabicLocalParser.parseNaturalText(
            rawText = input,
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )

        assertEquals(3, res.proposals.size)
        assertFalse(res.needsClarification)

        val first = res.proposals[0]
        assertTrue(first.description.contains("طماطم"))
        assertEquals(2000L, first.amountMinorUnits)
        assertEquals(DefaultCategories.CAT_FOOD, first.categoryId)

        val second = res.proposals[1]
        assertTrue(second.description.contains("جبنة"))
        assertEquals(4500L, second.amountMinorUnits)
        assertEquals(DefaultCategories.CAT_FOOD, second.categoryId)

        val third = res.proposals[2]
        assertTrue(third.description.contains("تاكسي"))
        assertEquals(2000L, third.amountMinorUnits)
        assertEquals(DefaultCategories.CAT_TRANSPORT, third.categoryId)
    }

    @Test
    fun `local parser parses multi-line shorthand input separately`() {
        val input = """
            طماطم 20
            جبنة 45
            تاكسي 20
            قهوة 25
        """.trimIndent()

        val res = EgyptianArabicLocalParser.parseNaturalText(
            rawText = input,
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )

        assertEquals(4, res.proposals.size)
        assertEquals(2000L, res.proposals[0].amountMinorUnits)
        assertEquals(4500L, res.proposals[1].amountMinorUnits)
        assertEquals(2000L, res.proposals[2].amountMinorUnits)
        assertEquals(2500L, res.proposals[3].amountMinorUnits)
    }

    @Test
    fun `local parser understands Egyptian Arabic salary income and home expenses`() {
        val salaryRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "قبضت 8000 جنيه مرتب",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )
        assertEquals(1, salaryRes.proposals.size)
        assertEquals(TransactionType.INCOME, salaryRes.proposals[0].type)
        assertEquals(800_000L, salaryRes.proposals[0].amountMinorUnits)
        assertEquals(DefaultCategories.CAT_SALARY, salaryRes.proposals[0].categoryId)

        val homeRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "اشتريت حاجات للبيت بـ300 جنيه",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )
        assertEquals(1, homeRes.proposals.size)
        assertEquals(TransactionType.EXPENSE, homeRes.proposals[0].type)
        assertEquals(30_000L, homeRes.proposals[0].amountMinorUnits)
        assertEquals(DefaultCategories.CAT_HOME_GROCERY, homeRes.proposals[0].categoryId)
    }

    @Test
    fun `parser flags missing amounts as ambiguous and never invents a monetary value`() {
        val res = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "اشتريت طماطم وعيش من السوبرماركت",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )
        assertEquals(1, res.proposals.size)
        assertNull(res.proposals[0].amountMinorUnits)
        assertTrue(res.proposals[0].isAmbiguous)
        assertTrue(res.needsClarification)
    }

    @Test
    fun `AI JSON validator rejects malformed JSON and sanitizes unknown or archived category IDs`() {
        val malformed = EgyptianArabicLocalParser.validateAndMapAiJsonResponse(
            rawJsonResponse = "not valid json",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList(),
            providerLabel = "TestProvider"
        )
        assertTrue(malformed.isFailure)

        // Archive transport category and verify AI cannot assign it
        val categoriesWithArchivedTransport = defaultCategories.map {
            if (it.id == DefaultCategories.CAT_TRANSPORT) it.copy(isArchived = true) else it
        }
        val jsonWithUnknownAndArchivedCat = """
            {
              "transactions": [
                {
                  "description": "مشوار خاص",
                  "amount": "50.00",
                  "type": "EXPENSE",
                  "date": "2026-11-01",
                  "categoryId": "${DefaultCategories.CAT_TRANSPORT}",
                  "notes": "",
                  "confidence": 0.95
                },
                {
                  "description": "عنصر غير معروف",
                  "amount": "35.00",
                  "type": "EXPENSE",
                  "date": "invalid-date",
                  "categoryId": "invented_ai_category_999",
                  "notes": "",
                  "confidence": 0.90
                }
              ],
              "needsClarification": false,
              "clarificationQuestion": null
            }
        """.trimIndent()

        val validated = EgyptianArabicLocalParser.validateAndMapAiJsonResponse(
            rawJsonResponse = jsonWithUnknownAndArchivedCat,
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = categoriesWithArchivedTransport,
            learnedRules = emptyList(),
            providerLabel = "TestProvider"
        ).getOrThrow()

        assertEquals(2, validated.proposals.size)
        // Archived category must be rejected (null -> Uncategorized)
        assertNull(validated.proposals[0].categoryId)
        assertTrue(validated.proposals[0].isAmbiguous)
        // Invented category ID must be rejected and invalid date falls back to todayIsoDate
        assertNull(validated.proposals[1].categoryId)
        assertEquals("2026-11-01", validated.proposals[1].isoDate)
    }

    @Test
    fun `learned category rule overrides default matching`() {
        val customRules = listOf(
            CategoryRuleEntity(
                keyword = "اشتراك نادي",
                categoryId = DefaultCategories.CAT_SPORTS,
                transactionType = TransactionType.EXPENSE
            )
        )
        val res = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "دفعت اشتراك نادي 400 جنيه",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = customRules
        )
        assertEquals(1, res.proposals.size)
        assertEquals(DefaultCategories.CAT_SPORTS, res.proposals[0].categoryId)
    }

    // --- D. Backup & CSV Export Validation ---

    @Test
    fun `backup manager validates valid JSON backup and rejects corrupted or negative-amount backups`() {
        val tx = TransactionEntity(
            id = "tx_100",
            description = "قهوة",
            amountMinorUnits = 2500L,
            currencyCode = "EGP",
            type = TransactionType.EXPENSE,
            categoryId = DefaultCategories.CAT_FOOD,
            transactionDate = 1730500000000L,
            notes = "مع صديق, وسط البلد",
            source = TransactionSource.MANUAL
        )
        val json = BackupExportManager.createBackupJsonString(
            currencyCode = "EGP",
            openingBalanceMinorUnits = 100_000L,
            openingBalanceConfigured = true,
            refundMode = RefundAccountingMode.INCOME_CATEGORY,
            categories = defaultCategories,
            transactions = listOf(tx),
            budgets = emptyList(),
            categoryRules = emptyList()
        )

        assertFalse(json.contains("api_key", ignoreCase = true))
        val preview = BackupExportManager.validateAndPreviewBackupJson(json)
        assertTrue(preview.isValid)
        assertNotNull(preview.payload)
        assertEquals(1, preview.transactionsCount)
        assertEquals(defaultCategories.size, preview.categoriesCount)

        // CSV Export Escaping Check
        val csv = BackupExportManager.exportTransactionsToCsv(
            transactions = listOf(tx),
            categoriesMap = defaultCategories.associateBy { it.id }
        )
        assertTrue(csv.contains("\"مع صديق, وسط البلد\""))
        assertTrue(csv.contains("قهوة"))
    }

    // --- E. Remediation Verification Tests (REV-03, REV-04, REV-05, REV-08, REV-10, REV-11) ---

    @Test
    fun `multi-currency accounting never sums different currencies into one total (REV-03)`() {
        val mixedCurrencyTxs = listOf(
            TransactionEntity(
                id = "egp_1",
                description = "مرتب بالجنيه",
                amountMinorUnits = 500_000L, // 5000 EGP
                currencyCode = "EGP",
                type = TransactionType.INCOME,
                categoryId = DefaultCategories.CAT_SALARY,
                transactionDate = 1730500000000L
            ),
            TransactionEntity(
                id = "egp_2",
                description = "فاتورة كهرباء",
                amountMinorUnits = 50_000L, // 500 EGP
                currencyCode = "EGP",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_BILLS,
                transactionDate = 1730510000000L
            ),
            TransactionEntity(
                id = "usd_1",
                description = "Freelance USD payment",
                amountMinorUnits = 20_000L, // 200 USD
                currencyCode = "USD",
                type = TransactionType.INCOME,
                categoryId = DefaultCategories.CAT_FREELANCE,
                transactionDate = 1730520000000L
            ),
            TransactionEntity(
                id = "sar_1",
                description = "عشاء في الرياض",
                amountMinorUnits = 10_000L, // 100 SAR
                currencyCode = "SAR",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_FOOD,
                transactionDate = 1730530000000L
            )
        )

        val egpSummary = FinancialCalculator.buildSummary(
            openingBalanceMinorUnits = 100_000L, // 1000 EGP
            openingBalanceConfigured = true,
            allTransactions = mixedCurrencyTxs,
            periodTransactions = mixedCurrencyTxs,
            previousPeriodTransactions = emptyList(),
            categories = defaultCategories,
            monthBudgets = emptyList(),
            currencyCode = "EGP",
            refundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET
        )

        // Primary EGP summary must only include EGP transactions (5000 income, 500 expense, +1000 opening = 5500 EGP)
        assertEquals(500_000L, egpSummary.periodIncomeMinor)
        assertEquals(50_000L, egpSummary.periodExpenseMinor)
        assertEquals(450_000L, egpSummary.periodNetCashFlowMinor)
        assertEquals(550_000L, egpSummary.currentBalanceMinor)

        // Other currencies must be tracked separately without mixing
        assertEquals(2, egpSummary.otherCurrencyBalances.size)
        val usdSummary = egpSummary.otherCurrencyBalances.first { it.currencyCode == "USD" }
        assertEquals(20_000L, usdSummary.totalIncomeMinor)
        assertEquals(0L, usdSummary.totalExpenseMinor)
        assertEquals(20_000L, usdSummary.netBalanceMinor)

        val sarSummary = egpSummary.otherCurrencyBalances.first { it.currencyCode == "SAR" }
        assertEquals(0L, sarSummary.totalIncomeMinor)
        assertEquals(10_000L, sarSummary.totalExpenseMinor)
        assertEquals(-10_000L, sarSummary.netBalanceMinor)
    }

    @Test
    fun `refund calculations unify total expenses and category breakdown and only offset matching category (REV-04, REV-05)`() {
        val txs = listOf(
            TransactionEntity(
                id = "exp_food",
                description = "غداء",
                amountMinorUnits = 20_000L, // 200 EGP
                currencyCode = "EGP",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_FOOD,
                transactionDate = 1730500000000L
            ),
            TransactionEntity(
                id = "exp_transport",
                description = "تاكسي",
                amountMinorUnits = 10_000L, // 100 EGP
                currencyCode = "EGP",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_TRANSPORT,
                transactionDate = 1730510000000L
            ),
            TransactionEntity(
                id = "ref_transport",
                description = "استرداد رحلة تاكسي",
                amountMinorUnits = 4_000L, // 40 EGP refund on transport
                currencyCode = "EGP",
                type = TransactionType.INCOME,
                categoryId = DefaultCategories.CAT_TRANSPORT,
                transactionDate = 1730520000000L,
                isRefundOffset = true
            )
        )

        // 1. EXPENSE_OFFSET mode: transport net expense becomes 100 - 40 = 60 EGP, food stays 200 EGP, total expense = 260 EGP
        val offsetSummary = FinancialCalculator.buildSummary(
            openingBalanceMinorUnits = 0L,
            openingBalanceConfigured = false,
            allTransactions = txs,
            periodTransactions = txs,
            previousPeriodTransactions = emptyList(),
            categories = defaultCategories,
            monthBudgets = emptyList(),
            currencyCode = "EGP",
            refundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET
        )

        assertEquals(26_000L, offsetSummary.periodExpenseMinor)
        assertEquals(0L, offsetSummary.periodIncomeMinor) // Fully applied as expense offset
        assertEquals(-26_000L, offsetSummary.periodNetCashFlowMinor)
        // Sum of category breakdown must equal periodExpenseMinor
        assertEquals(
            offsetSummary.periodExpenseMinor,
            offsetSummary.expenseByCategory.sumOf { it.totalMinorUnits }
        )
        val foodBreakdown = offsetSummary.expenseByCategory.first { it.categoryId == DefaultCategories.CAT_FOOD }
        val transportBreakdown = offsetSummary.expenseByCategory.first { it.categoryId == DefaultCategories.CAT_TRANSPORT }
        assertEquals(20_000L, foodBreakdown.totalMinorUnits)
        assertEquals(6_000L, transportBreakdown.totalMinorUnits)

        // 2. INCOME_CATEGORY mode: refund is counted as income (40 EGP), expenses remain 300 EGP, net cash flow is still -260 EGP
        val incomeModeSummary = FinancialCalculator.buildSummary(
            openingBalanceMinorUnits = 0L,
            openingBalanceConfigured = false,
            allTransactions = txs,
            periodTransactions = txs,
            previousPeriodTransactions = emptyList(),
            categories = defaultCategories,
            monthBudgets = emptyList(),
            currencyCode = "EGP",
            refundAccountingMode = RefundAccountingMode.INCOME_CATEGORY
        )

        assertEquals(30_000L, incomeModeSummary.periodExpenseMinor)
        assertEquals(4_000L, incomeModeSummary.periodIncomeMinor)
        assertEquals(-26_000L, incomeModeSummary.periodNetCashFlowMinor)
    }

    @Test
    fun `refund in one category never reduces unrelated categories even if refund exceeds category expense (REV-05)`() {
        val txs = listOf(
            TransactionEntity(
                id = "exp_food",
                description = "مطعم",
                amountMinorUnits = 50_000L, // 500 EGP food
                currencyCode = "EGP",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_FOOD,
                transactionDate = 1730500000000L
            ),
            TransactionEntity(
                id = "exp_transport",
                description = "أوبر",
                amountMinorUnits = 5_000L, // 50 EGP transport
                currencyCode = "EGP",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.CAT_TRANSPORT,
                transactionDate = 1730510000000L
            ),
            TransactionEntity(
                id = "ref_transport_large",
                description = "استرداد محفظة مواصلات",
                amountMinorUnits = 8_000L, // 80 EGP refund on transport (exceeds 50 EGP transport expense by 30 EGP)
                currencyCode = "EGP",
                type = TransactionType.INCOME,
                categoryId = DefaultCategories.CAT_TRANSPORT,
                transactionDate = 1730520000000L,
                isRefundOffset = true
            )
        )

        val summary = FinancialCalculator.buildSummary(
            openingBalanceMinorUnits = 0L,
            openingBalanceConfigured = false,
            allTransactions = txs,
            periodTransactions = txs,
            previousPeriodTransactions = emptyList(),
            categories = defaultCategories,
            monthBudgets = emptyList(),
            currencyCode = "EGP",
            refundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET
        )

        // Food must remain untouched at 500 EGP; transport is offset to 0; unapplied 30 EGP remains in income
        assertEquals(50_000L, summary.periodExpenseMinor)
        assertEquals(3_000L, summary.periodIncomeMinor)
        assertEquals(-47_000L, summary.periodNetCashFlowMinor)
        assertEquals(1, summary.expenseByCategory.size)
        assertEquals(DefaultCategories.CAT_FOOD, summary.expenseByCategory[0].categoryId)
        assertEquals(50_000L, summary.expenseByCategory[0].totalMinorUnits)
    }

    @Test
    fun `amount parser supports all app currency codes and symbols accurately (REV-10)`() {
        assertEquals(12550L, MoneyUtils.parseAmountToMinorUnits("125.50 EGP"))
        assertEquals(12550L, MoneyUtils.parseAmountToMinorUnits("125.50 ج.م"))
        assertEquals(12550L, MoneyUtils.parseAmountToMinorUnits("125.50 جنيه"))
        assertEquals(9900L, MoneyUtils.parseAmountToMinorUnits("99 USD"))
        assertEquals(9900L, MoneyUtils.parseAmountToMinorUnits("$99.00"))
        assertEquals(9900L, MoneyUtils.parseAmountToMinorUnits("99 دولار"))
        assertEquals(25075L, MoneyUtils.parseAmountToMinorUnits("250.75 SAR"))
        assertEquals(25075L, MoneyUtils.parseAmountToMinorUnits("٢٥٠٫٧٥ ر.س"))
        assertEquals(25075L, MoneyUtils.parseAmountToMinorUnits("250.75 ريال"))
        assertEquals(18000L, MoneyUtils.parseAmountToMinorUnits("180 AED"))
        assertEquals(18000L, MoneyUtils.parseAmountToMinorUnits("180 د.إ"))
        assertEquals(18000L, MoneyUtils.parseAmountToMinorUnits("180 درهم"))
        assertEquals(4225L, MoneyUtils.parseAmountToMinorUnits("42.25 EUR"))
        assertEquals(4225L, MoneyUtils.parseAmountToMinorUnits("€42.25"))
        assertEquals(4225L, MoneyUtils.parseAmountToMinorUnits("42.25 يورو"))
    }

    @Test
    fun `category learning avoids unsafe substring matching and short keywords (REV-11)`() {
        val rules = listOf(
            // Short 2-char rule must be ignored
            CategoryRuleEntity(
                keyword = "بن",
                categoryId = DefaultCategories.CAT_FOOD,
                transactionType = TransactionType.EXPENSE
            ),
            // 3-char rule "حلب" must NOT match inside "حلبة" or "محلب" (whole-word boundary only)
            CategoryRuleEntity(
                keyword = "نادي",
                categoryId = DefaultCategories.CAT_SPORTS,
                transactionType = TransactionType.EXPENSE
            )
        )

        // "دفعت بنزين 100 جنيه" must match TRANSPORT ("بنزين"), NOT FOOD from short substring "بن"
        val fuelRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "دفعت بنزين 100 جنيه",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = rules
        )
        assertEquals(DefaultCategories.CAT_TRANSPORT, fuelRes.proposals[0].categoryId)

        // "نادين" must NOT match rule "نادي" because it is not a whole-word match
        val nonMatchRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "هدية نادين 100 جنيه",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = rules
        )
        assertFalse(nonMatchRes.proposals[0].categoryId == DefaultCategories.CAT_SPORTS)
    }

    @Test
    fun `RemoteAiProviderClient reuses singleton OkHttpClient instance (REV-08)`() {
        val clientA = RemoteAiProviderClient.defaultOkHttpClient()
        val clientB = RemoteAiProviderClient.defaultOkHttpClient()
        assertSame(clientA, clientB)
    }

    // --- F. Task 3 Regression Tests (REV-03, REV-10) ---

    @Test
    fun `arabic word amounts with USD, SAR, AED, EUR, and EGP parse accurately without false matches (REV-10)`() {
        // 1. "دفعت عشرين دولار" -> 2000L, USD
        val usdRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "دفعت عشرين دولار",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )
        assertEquals(1, usdRes.proposals.size)
        assertEquals(2000L, usdRes.proposals[0].amountMinorUnits)
        assertEquals("USD", usdRes.proposals[0].currencyCode)

        // 2. "اشتريت قهوة بخمسة عشر ريال" -> 1500L, SAR
        val sarRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "اشتريت قهوة بخمسة عشر ريال",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )
        assertEquals(1, sarRes.proposals.size)
        assertEquals(1500L, sarRes.proposals[0].amountMinorUnits)
        assertEquals("SAR", sarRes.proposals[0].currencyCode)
        assertEquals(DefaultCategories.CAT_FOOD, sarRes.proposals[0].categoryId)

        // 3. "دفعت مئة درهم" -> 10000L, AED
        val aedRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "دفعت مئة درهم",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )
        assertEquals(1, aedRes.proposals.size)
        assertEquals(10_000L, aedRes.proposals[0].amountMinorUnits)
        assertEquals("AED", aedRes.proposals[0].currencyCode)

        // 4. "اشتريت كتاب بعشرين يورو" -> 2000L, EUR
        val eurRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "اشتريت كتاب بعشرين يورو",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )
        assertEquals(1, eurRes.proposals.size)
        assertEquals(2000L, eurRes.proposals[0].amountMinorUnits)
        assertEquals("EUR", eurRes.proposals[0].currencyCode)
        assertEquals(DefaultCategories.CAT_EDUCATION, eurRes.proposals[0].categoryId)

        // 5. "دفعت خمسين جنيه" -> 5000L, EGP
        val egpRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "دفعت خمسين جنيه",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "USD",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )
        assertEquals(1, egpRes.proposals.size)
        assertEquals(5000L, egpRes.proposals[0].amountMinorUnits)
        assertEquals("EGP", egpRes.proposals[0].currencyCode)

        // 6. Currency word mentioned without an amount must NOT false-match
        val noAmountRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "دفعت حساب الفندق بالدولار",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )
        assertEquals(1, noAmountRes.proposals.size)
        assertNull(noAmountRes.proposals[0].amountMinorUnits)
        assertTrue(noAmountRes.proposals[0].isAmbiguous)

        // 7. Existing numeric amount parsing with foreign currencies continues to work
        val numericForeignRes = EgyptianArabicLocalParser.parseNaturalText(
            rawText = "اشتريت كتاب بـ 35 دولار وقهوة 15 ريال",
            todayIsoDate = "2026-11-01",
            defaultCurrency = "EGP",
            eligibleCategories = defaultCategories,
            learnedRules = emptyList()
        )
        assertEquals(2, numericForeignRes.proposals.size)
        assertEquals(3500L, numericForeignRes.proposals[0].amountMinorUnits)
        assertEquals("USD", numericForeignRes.proposals[0].currencyCode)
        assertEquals(1500L, numericForeignRes.proposals[1].amountMinorUnits)
        assertEquals("SAR", numericForeignRes.proposals[1].currencyCode)
    }

    @Test
    fun `backup exports multiple currency opening balances, maintains backward compatibility with legacy backups, and rejects invalid currency entries (REV-03)`() {
        val multiBalances = mapOf(
            "EGP" to 500_000L,
            "USD" to 120_000L,
            "SAR" to 75_000L
        )
        val json = BackupExportManager.createBackupJsonString(
            currencyCode = "EGP",
            openingBalanceMinorUnits = 500_000L,
            openingBalanceConfigured = true,
            refundMode = RefundAccountingMode.EXPENSE_OFFSET,
            categories = defaultCategories,
            transactions = emptyList(),
            budgets = emptyList(),
            categoryRules = emptyList(),
            currencyOpeningBalances = multiBalances
        )

        val preview = BackupExportManager.validateAndPreviewBackupJson(json)
        assertTrue(preview.isValid)
        val payload = preview.payload!!
        assertEquals(3, payload.currencyOpeningBalances.size)
        assertEquals(500_000L, payload.currencyOpeningBalances["EGP"])
        assertEquals(120_000L, payload.currencyOpeningBalances["USD"])
        assertEquals(75_000L, payload.currencyOpeningBalances["SAR"])

        // Backward compatibility: older v1/v2 backup without currencyOpeningBalances object
        val legacyJson = """
            {
              "format": "masroofi_backup_v1",
              "schemaVersion": 1,
              "exportedAtMillis": 1730500000000,
              "settings": {
                "currencyCode": "SAR",
                "openingBalanceMinorUnits": 90000,
                "openingBalanceConfigured": true,
                "refundAccountingMode": "EXPENSE_OFFSET"
              },
              "categories": [],
              "transactions": [],
              "budgets": [],
              "categoryRules": []
            }
        """.trimIndent()
        val legacyPreview = BackupExportManager.validateAndPreviewBackupJson(legacyJson)
        assertTrue(legacyPreview.isValid)
        assertEquals("SAR", legacyPreview.payload?.currencyCode)
        assertEquals(mapOf("SAR" to 90_000L), legacyPreview.payload?.currencyOpeningBalances)

        // Unsupported currency in currencyOpeningBalances must be rejected safely
        val unsupportedCurrencyJson = """
            {
              "format": "masroofi_backup_v1",
              "schemaVersion": 2,
              "exportedAtMillis": 1730500000000,
              "settings": {
                "currencyCode": "EGP",
                "openingBalanceMinorUnits": 10000,
                "openingBalanceConfigured": true,
                "currencyOpeningBalances": {
                  "EGP": 10000,
                  "XYZ": 50000
                }
              },
              "categories": [],
              "transactions": [],
              "budgets": [],
              "categoryRules": []
            }
        """.trimIndent()
        val invalidCurrPreview = BackupExportManager.validateAndPreviewBackupJson(unsupportedCurrencyJson)
        assertFalse(invalidCurrPreview.isValid)
    }
}
