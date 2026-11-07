package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MasroofiDao {

    // --- Categories ---
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, createdAt ASC")
    fun observeAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY isDefault DESC, createdAt ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY isDefault DESC, createdAt ASC")
    suspend fun getActiveCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getCategoryById(id: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategoriesIgnore(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteCategoryById(categoryId: String)

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countTransactionsForCategory(categoryId: String): Int

    // --- Transactions ---
    @Query("SELECT * FROM transactions ORDER BY transactionDate DESC, createdAt DESC")
    fun observeAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY transactionDate DESC, createdAt DESC")
    suspend fun getAllTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getTransactionById(id: String): TransactionEntity?

    @Query("SELECT COUNT(*) FROM transactions WHERE batchOperationId = :batchOperationId")
    suspend fun countByBatchOperationId(batchOperationId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<TransactionEntity>)

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: String)

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    /**
     * Atomically saves a batch of AI or multi-entry transactions while preventing duplicate
     * batch operations if the same batchOperationId was already committed.
     * Returns true if committed, false if skipped due to idempotency match.
     */
    @Transaction
    suspend fun insertBatchWithIdempotency(
        batchOperationId: String,
        transactions: List<TransactionEntity>
    ): Boolean {
        if (transactions.isEmpty()) return false
        val existingCount = countByBatchOperationId(batchOperationId)
        if (existingCount > 0) {
            return false
        }
        val tagged = transactions.map { it.copy(batchOperationId = batchOperationId) }
        insertTransactions(tagged)
        return true
    }

    // --- Budgets ---
    @Query("SELECT * FROM budgets ORDER BY yearMonth DESC")
    fun observeAllBudgets(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets")
    suspend fun getAllBudgets(): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth")
    fun observeBudgetsForMonth(yearMonth: String): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudget(budget: BudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudgets(budgets: List<BudgetEntity>)

    @Query("DELETE FROM budgets WHERE id = :budgetId")
    suspend fun deleteBudgetById(budgetId: String)

    @Query("DELETE FROM budgets")
    suspend fun deleteAllBudgets()

    // --- Learned Category Rules ---
    @Query("SELECT * FROM category_rules ORDER BY updatedAt DESC")
    fun observeCategoryRules(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules")
    suspend fun getAllCategoryRules(): List<CategoryRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategoryRule(rule: CategoryRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategoryRules(rules: List<CategoryRuleEntity>)

    @Query("DELETE FROM category_rules WHERE keyword = :keyword")
    suspend fun deleteCategoryRule(keyword: String)

    @Query("DELETE FROM category_rules")
    suspend fun deleteAllCategoryRules()

    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()

    // --- Backup Restore Transactions ---
    @Transaction
    suspend fun replaceAllDataTransaction(
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        budgets: List<BudgetEntity>,
        rules: List<CategoryRuleEntity>
    ) {
        deleteAllTransactions()
        deleteAllBudgets()
        deleteAllCategoryRules()
        deleteAllCategories()
        val categoryIds = categories.map { it.id }.toSet()
        for (cat in categories) {
            require(cat.id.isNotBlank() && cat.name.isNotBlank()) {
                "تصنيف غير صالح أثناء استعادة النسخة الاحتياطية"
            }
        }
        for (tx in transactions) {
            require(tx.id.isNotBlank() && tx.description.isNotBlank() && tx.amountMinorUnits > 0L) {
                "معاملة غير صالحة أو تالفة أثناء استعادة النسخة الاحتياطية"
            }
            require(tx.categoryId == null || categoryIds.contains(tx.categoryId)) {
                "تصنيف المعاملة غير موجود في النسخة الاحتياطية (${tx.categoryId})"
            }
        }
        upsertCategories(categories)
        insertTransactions(transactions)
        insertBudgets(budgets)
        insertCategoryRules(rules)
    }

    @Transaction
    suspend fun mergeBackupDataTransaction(
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        budgets: List<BudgetEntity>,
        rules: List<CategoryRuleEntity>
    ) {
        for (cat in categories) {
            require(cat.id.isNotBlank() && cat.name.isNotBlank()) {
                "تصنيف غير صالح أثناء دمج النسخة الاحتياطية"
            }
        }
        for (tx in transactions) {
            require(tx.id.isNotBlank() && tx.description.isNotBlank() && tx.amountMinorUnits > 0L) {
                "معاملة غير صالحة أو تالفة أثناء دمج النسخة الاحتياطية"
            }
        }
        upsertCategories(categories)
        insertTransactions(transactions)
        insertBudgets(budgets)
        insertCategoryRules(rules)
    }
}
