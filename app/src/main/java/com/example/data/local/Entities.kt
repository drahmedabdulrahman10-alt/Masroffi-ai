package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class TransactionType {
    EXPENSE,
    INCOME
}

enum class TransactionSource {
    MANUAL,
    AI_BATCH,
    IMPORT
}

enum class RefundAccountingMode {
    INCOME_CATEGORY,
    EXPENSE_OFFSET
}

class RoomConverters {
    @TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType =
        runCatching { TransactionType.valueOf(value) }.getOrDefault(TransactionType.EXPENSE)

    @TypeConverter
    fun fromTransactionSource(value: TransactionSource): String = value.name

    @TypeConverter
    fun toTransactionSource(value: String): TransactionSource =
        runCatching { TransactionSource.valueOf(value) }.getOrDefault(TransactionSource.MANUAL)
}

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["type"]),
        Index(value = ["isArchived"])
    ]
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: TransactionType,
    val iconName: String,
    val colorHex: Long,
    val isArchived: Boolean = false,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["transactionDate"]),
        Index(value = ["type"]),
        Index(value = ["batchOperationId"])
    ]
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val description: String,
    val amountMinorUnits: Long,
    val currencyCode: String = "EGP",
    val type: TransactionType,
    val categoryId: String?,
    val transactionDate: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    val source: TransactionSource = TransactionSource.MANUAL,
    val batchOperationId: String? = null,
    val isRefundOffset: Boolean = false
)

@Entity(
    tableName = "budgets",
    indices = [
        Index(value = ["yearMonth"]),
        Index(value = ["categoryId"])
    ]
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    val yearMonth: String,
    val categoryId: String?, // null = overall monthly spending budget
    val limitMinorUnits: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "category_rules",
    indices = [Index(value = ["categoryId"])]
)
data class CategoryRuleEntity(
    @PrimaryKey val keyword: String,
    val categoryId: String,
    val transactionType: TransactionType,
    val updatedAt: Long = System.currentTimeMillis()
)

object DefaultCategories {
    const val CAT_FOOD = "cat_food_drink"
    const val CAT_HOME_GROCERY = "cat_home_needs"
    const val CAT_TRANSPORT = "cat_transport"
    const val CAT_EDUCATION = "cat_study"
    const val CAT_HEALTH = "cat_health"
    const val CAT_SPORTS = "cat_sports"
    const val CAT_CLOTHING = "cat_clothing"
    const val CAT_ENTERTAINMENT = "cat_entertainment"
    const val CAT_BILLS = "cat_bills"
    const val CAT_OTHER_EXPENSE = "cat_other_expense"

    const val CAT_SALARY = "cat_income_salary"
    const val CAT_FREELANCE = "cat_income_freelance"
    const val CAT_GIFTS = "cat_income_gifts"
    const val CAT_REFUNDS = "cat_income_refunds"
    const val CAT_OTHER_INCOME = "cat_income_other"

    fun all(now: Long = 1700000000000L): List<CategoryEntity> = listOf(
        // Expense Categories
        CategoryEntity(
            id = CAT_FOOD,
            name = "أكل وشرب",
            type = TransactionType.EXPENSE,
            iconName = "restaurant",
            colorHex = 0xFFEA580CL,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_HOME_GROCERY,
            name = "طلبات البيت",
            type = TransactionType.EXPENSE,
            iconName = "shopping_basket",
            colorHex = 0xFF0D9488L,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_TRANSPORT,
            name = "مواصلات",
            type = TransactionType.EXPENSE,
            iconName = "directions_car",
            colorHex = 0xFF2563EBL,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_EDUCATION,
            name = "دراسة",
            type = TransactionType.EXPENSE,
            iconName = "school",
            colorHex = 0xFF7C3AEDL,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_HEALTH,
            name = "صحة وأدوية",
            type = TransactionType.EXPENSE,
            iconName = "medical_services",
            colorHex = 0xFFDC2626L,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_SPORTS,
            name = "رياضة",
            type = TransactionType.EXPENSE,
            iconName = "fitness_center",
            colorHex = 0xFF16A34AL,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_CLOTHING,
            name = "ملابس",
            type = TransactionType.EXPENSE,
            iconName = "checkroom",
            colorHex = 0xFFDB2777L,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_ENTERTAINMENT,
            name = "ترفيه",
            type = TransactionType.EXPENSE,
            iconName = "movie",
            colorHex = 0xFFD97706L,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_BILLS,
            name = "فواتير واشتراكات",
            type = TransactionType.EXPENSE,
            iconName = "receipt_long",
            colorHex = 0xFF475569L,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_OTHER_EXPENSE,
            name = "أخرى",
            type = TransactionType.EXPENSE,
            iconName = "category",
            colorHex = 0xFF64748BL,
            isDefault = true,
            createdAt = now
        ),
        // Income Categories
        CategoryEntity(
            id = CAT_SALARY,
            name = "مرتب",
            type = TransactionType.INCOME,
            iconName = "payments",
            colorHex = 0xFF15803DL,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_FREELANCE,
            name = "عمل حر",
            type = TransactionType.INCOME,
            iconName = "work",
            colorHex = 0xFF0F766EL,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_GIFTS,
            name = "مكافآت وهدايا",
            type = TransactionType.INCOME,
            iconName = "card_giftcard",
            colorHex = 0xFF9333EAL,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_REFUNDS,
            name = "استرداد",
            type = TransactionType.INCOME,
            iconName = "replay",
            colorHex = 0xFF0284C7L,
            isDefault = true,
            createdAt = now
        ),
        CategoryEntity(
            id = CAT_OTHER_INCOME,
            name = "دخل آخر",
            type = TransactionType.INCOME,
            iconName = "account_balance_wallet",
            colorHex = 0xFF16A34AL,
            isDefault = true,
            createdAt = now
        )
    )
}
