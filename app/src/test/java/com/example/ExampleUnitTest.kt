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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
