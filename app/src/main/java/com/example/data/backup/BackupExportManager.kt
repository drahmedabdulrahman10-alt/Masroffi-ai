package com.example.data.backup

import com.example.data.local.BudgetEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.CategoryRuleEntity
import com.example.data.local.RefundAccountingMode
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionSource
import com.example.data.local.TransactionType
import com.example.domain.MoneyUtils
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

data class BackupPayload(
    val schemaVersion: Int,
    val exportedAtMillis: Long,
    val currencyCode: String,
    val openingBalanceMinorUnits: Long,
    val openingBalanceConfigured: Boolean,
    val refundAccountingMode: RefundAccountingMode,
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    val budgets: List<BudgetEntity>,
    val categoryRules: List<CategoryRuleEntity>
)

data class BackupValidationPreview(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val payload: BackupPayload? = null,
    val categoriesCount: Int = 0,
    val transactionsCount: Int = 0,
    val budgetsCount: Int = 0,
    val currencyCode: String = "EGP",
    val openingBalanceMinorUnits: Long = 0L
)

/**
 * Handles local JSON backup creation, pre-restore validation, and UTF-8 CSV export
 * using Moshi JsonWriter/JsonReader so it works identically on Android and JVM unit tests.
 * Explicitly excludes API keys and credentials from all exported files.
 */
object BackupExportManager {

    private const val BACKUP_FORMAT_ID = "masroofi_backup_v1"

    fun createBackupJsonString(
        currencyCode: String,
        openingBalanceMinorUnits: Long,
        openingBalanceConfigured: Boolean,
        refundMode: RefundAccountingMode,
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        budgets: List<BudgetEntity>,
        categoryRules: List<CategoryRuleEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer).apply {
            indent = "  "
            serializeNulls = true
        }

        writer.beginObject()
        writer.name("format").value(BACKUP_FORMAT_ID)
        writer.name("schemaVersion").value(2)
        writer.name("exportedAtMillis").value(nowMillis)

        writer.name("settings").beginObject()
        writer.name("currencyCode").value(currencyCode)
        writer.name("openingBalanceMinorUnits").value(openingBalanceMinorUnits)
        writer.name("openingBalanceConfigured").value(openingBalanceConfigured)
        writer.name("refundAccountingMode").value(refundMode.name)
        writer.endObject()

        writer.name("categories").beginArray()
        for (c in categories) {
            writer.beginObject()
            writer.name("id").value(c.id)
            writer.name("name").value(c.name)
            writer.name("type").value(c.type.name)
            writer.name("iconName").value(c.iconName)
            writer.name("colorHex").value(c.colorHex)
            writer.name("isArchived").value(c.isArchived)
            writer.name("isDefault").value(c.isDefault)
            writer.name("createdAt").value(c.createdAt)
            writer.endObject()
        }
        writer.endArray()

        writer.name("transactions").beginArray()
        for (t in transactions) {
            writer.beginObject()
            writer.name("id").value(t.id)
            writer.name("description").value(t.description)
            writer.name("amountMinorUnits").value(t.amountMinorUnits)
            writer.name("currencyCode").value(t.currencyCode)
            writer.name("type").value(t.type.name)
            writer.name("categoryId").value(t.categoryId)
            writer.name("transactionDate").value(t.transactionDate)
            writer.name("createdAt").value(t.createdAt)
            writer.name("updatedAt").value(t.updatedAt)
            writer.name("notes").value(t.notes)
            writer.name("source").value(t.source.name)
            writer.name("batchOperationId").value(t.batchOperationId)
            writer.name("isRefundOffset").value(t.isRefundOffset)
            writer.endObject()
        }
        writer.endArray()

        writer.name("budgets").beginArray()
        for (b in budgets) {
            writer.beginObject()
            writer.name("id").value(b.id)
            writer.name("yearMonth").value(b.yearMonth)
            writer.name("categoryId").value(b.categoryId)
            writer.name("limitMinorUnits").value(b.limitMinorUnits)
            writer.name("updatedAt").value(b.updatedAt)
            writer.endObject()
        }
        writer.endArray()

        writer.name("categoryRules").beginArray()
        for (r in categoryRules) {
            writer.beginObject()
            writer.name("keyword").value(r.keyword)
            writer.name("categoryId").value(r.categoryId)
            writer.name("transactionType").value(r.transactionType.name)
            writer.name("updatedAt").value(r.updatedAt)
            writer.endObject()
        }
        writer.endArray()

        writer.endObject()
        writer.close()
        return buffer.readUtf8()
    }

    /**
     * Validates a backup JSON string before touching existing database records.
     * Verifies format header, entity IDs, positive transaction amounts, and foreign key integrity.
     */
    fun validateAndPreviewBackupJson(rawJson: String): BackupValidationPreview {
        if (rawJson.isBlank()) {
            return BackupValidationPreview(
                isValid = false,
                errorMessage = "ملف النسخة الاحتياطية فارغ."
            )
        }
        return try {
            val buffer = Buffer().writeUtf8(rawJson.trim())
            val reader = JsonReader.of(buffer)

            var format = ""
            var schemaVersion = 1
            var exportedAt = System.currentTimeMillis()
            var currencyCode = "EGP"
            var openingBalance = 0L
            var openingConfigured = false
            var refundMode = RefundAccountingMode.INCOME_CATEGORY

            val categories = mutableListOf<CategoryEntity>()
            val rawTransactions = mutableListOf<TransactionEntity>()
            val rawBudgets = mutableListOf<BudgetEntity>()
            val rawRules = mutableListOf<CategoryRuleEntity>()

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "format" -> format = readStringOrEmpty(reader)
                    "schemaVersion" -> schemaVersion = reader.nextInt()
                    "exportedAtMillis" -> exportedAt = reader.nextLong()
                    "settings" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "currencyCode" -> currencyCode = readStringOrEmpty(reader).ifBlank { "EGP" }
                                "openingBalanceMinorUnits" -> openingBalance = reader.nextLong()
                                "openingBalanceConfigured" -> openingConfigured = reader.nextBoolean()
                                "refundAccountingMode" -> {
                                    val modeStr = readStringOrEmpty(reader)
                                    refundMode = runCatching { RefundAccountingMode.valueOf(modeStr) }
                                        .getOrDefault(RefundAccountingMode.INCOME_CATEGORY)
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    "categories" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var id = ""
                            var name = ""
                            var typeStr = ""
                            var iconName = "category"
                            var colorHex = 0xFF0F766EL
                            var isArchived = false
                            var isDefault = false
                            var createdAt = System.currentTimeMillis()

                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "id" -> id = readStringOrEmpty(reader).trim()
                                    "name" -> name = readStringOrEmpty(reader).trim()
                                    "type" -> typeStr = readStringOrEmpty(reader).trim()
                                    "iconName" -> iconName = readStringOrEmpty(reader).ifBlank { "category" }
                                    "colorHex" -> colorHex = reader.nextLong()
                                    "isArchived" -> isArchived = reader.nextBoolean()
                                    "isDefault" -> isDefault = reader.nextBoolean()
                                    "createdAt" -> createdAt = reader.nextLong()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (id.isEmpty() || name.isEmpty()) {
                                return BackupValidationPreview(
                                    isValid = false,
                                    errorMessage = "يوجد تصنيف بدون معرّف أو اسم صالح في ملف النسخة الاحتياطية."
                                )
                            }
                            val type = runCatching { TransactionType.valueOf(typeStr) }.getOrNull()
                                ?: return BackupValidationPreview(
                                    isValid = false,
                                    errorMessage = "نوع تصنيف غير صالح ($typeStr) في النسخة الاحتياطية."
                                )
                            categories.add(
                                CategoryEntity(
                                    id = id,
                                    name = name,
                                    type = type,
                                    iconName = iconName,
                                    colorHex = colorHex,
                                    isArchived = isArchived,
                                    isDefault = isDefault,
                                    createdAt = createdAt
                                )
                            )
                        }
                        reader.endArray()
                    }
                    "transactions" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var id = ""
                            var desc = ""
                            var amountMinor = -1L
                            var txCurrency = currencyCode
                            var typeStr = ""
                            var catId: String? = null
                            var txDate = -1L
                            var createdAt = -1L
                            var updatedAt = -1L
                            var notes = ""
                            var sourceStr = TransactionSource.IMPORT.name
                            var batchOpId: String? = null
                            var isRefundOffset = false

                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "id" -> id = readStringOrEmpty(reader).trim()
                                    "description" -> desc = readStringOrEmpty(reader).trim()
                                    "amountMinorUnits" -> amountMinor = reader.nextLong()
                                    "currencyCode" -> txCurrency = readStringOrEmpty(reader).ifBlank { currencyCode }
                                    "type" -> typeStr = readStringOrEmpty(reader).trim()
                                    "categoryId" -> catId = readNullableString(reader)?.takeIf { it.isNotBlank() && it != "null" }
                                    "transactionDate" -> txDate = reader.nextLong()
                                    "createdAt" -> createdAt = reader.nextLong()
                                    "updatedAt" -> updatedAt = reader.nextLong()
                                    "notes" -> notes = readNullableString(reader).orEmpty()
                                    "source" -> sourceStr = readStringOrEmpty(reader)
                                    "batchOperationId" -> batchOpId = readNullableString(reader)?.takeIf { it.isNotBlank() && it != "null" }
                                    "isRefundOffset" -> isRefundOffset = reader.nextBoolean()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (id.isEmpty() || desc.isEmpty() || amountMinor <= 0L || txDate <= 0L) {
                                return BackupValidationPreview(
                                    isValid = false,
                                    errorMessage = "توجد معاملة غير صالحة أو بمبلغ صفري/سالب في ملف النسخة الاحتياطية."
                                )
                            }
                            val type = runCatching { TransactionType.valueOf(typeStr) }.getOrNull()
                                ?: return BackupValidationPreview(
                                    isValid = false,
                                    errorMessage = "نوع معاملة غير صالح في النسخة الاحتياطية."
                                )
                            val source = runCatching { TransactionSource.valueOf(sourceStr) }
                                .getOrDefault(TransactionSource.IMPORT)

                            rawTransactions.add(
                                TransactionEntity(
                                    id = id,
                                    description = desc,
                                    amountMinorUnits = amountMinor,
                                    currencyCode = txCurrency,
                                    type = type,
                                    categoryId = catId,
                                    transactionDate = txDate,
                                    createdAt = if (createdAt > 0L) createdAt else txDate,
                                    updatedAt = if (updatedAt > 0L) updatedAt else txDate,
                                    notes = notes,
                                    source = source,
                                    batchOperationId = batchOpId,
                                    isRefundOffset = isRefundOffset
                                )
                            )
                        }
                        reader.endArray()
                    }
                    "budgets" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var id = ""
                            var ym = ""
                            var catId: String? = null
                            var limit = -1L
                            var updatedAt = System.currentTimeMillis()

                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "id" -> id = readStringOrEmpty(reader).trim()
                                    "yearMonth" -> ym = readStringOrEmpty(reader).trim()
                                    "categoryId" -> catId = readNullableString(reader)?.takeIf { it.isNotBlank() && it != "null" }
                                    "limitMinorUnits" -> limit = reader.nextLong()
                                    "updatedAt" -> updatedAt = reader.nextLong()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (id.isNotEmpty() && ym.isNotEmpty() && limit > 0L) {
                                rawBudgets.add(
                                    BudgetEntity(
                                        id = id,
                                        yearMonth = ym,
                                        categoryId = catId,
                                        limitMinorUnits = limit,
                                        updatedAt = updatedAt
                                    )
                                )
                            }
                        }
                        reader.endArray()
                    }
                    "categoryRules" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var kw = ""
                            var catId = ""
                            var typeStr = "EXPENSE"
                            var updatedAt = System.currentTimeMillis()

                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "keyword" -> kw = readStringOrEmpty(reader).trim()
                                    "categoryId" -> catId = readStringOrEmpty(reader).trim()
                                    "transactionType" -> typeStr = readStringOrEmpty(reader).trim()
                                    "updatedAt" -> updatedAt = reader.nextLong()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            val type = runCatching { TransactionType.valueOf(typeStr) }
                                .getOrDefault(TransactionType.EXPENSE)
                            if (kw.isNotEmpty() && catId.isNotEmpty()) {
                                rawRules.add(
                                    CategoryRuleEntity(
                                        keyword = kw,
                                        categoryId = catId,
                                        transactionType = type,
                                        updatedAt = updatedAt
                                    )
                                )
                            }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (format != BACKUP_FORMAT_ID) {
                return BackupValidationPreview(
                    isValid = false,
                    errorMessage = "ملف غير مدعوم. يرجى اختيار ملف نسخة احتياطية صالح من تطبيق مصروفي."
                )
            }

            val categoryIds = categories.map { it.id }.toSet()
            val safeTransactions = rawTransactions.map { tx ->
                tx.copy(categoryId = tx.categoryId?.takeIf { categoryIds.contains(it) })
            }
            val safeBudgets = rawBudgets.map { b ->
                b.copy(categoryId = b.categoryId?.takeIf { categoryIds.contains(it) })
            }
            val safeRules = rawRules.filter { categoryIds.contains(it.categoryId) }

            val payload = BackupPayload(
                schemaVersion = schemaVersion,
                exportedAtMillis = exportedAt,
                currencyCode = currencyCode,
                openingBalanceMinorUnits = openingBalance,
                openingBalanceConfigured = openingConfigured,
                refundAccountingMode = refundMode,
                categories = categories,
                transactions = safeTransactions,
                budgets = safeBudgets,
                categoryRules = safeRules
            )

            BackupValidationPreview(
                isValid = true,
                payload = payload,
                categoriesCount = categories.size,
                transactionsCount = safeTransactions.size,
                budgetsCount = safeBudgets.size,
                currencyCode = currencyCode,
                openingBalanceMinorUnits = openingBalance
            )
        } catch (e: Exception) {
            BackupValidationPreview(
                isValid = false,
                errorMessage = "فشل قراءة ملف النسخة الاحتياطية: تنسيق JSON غير صالح."
            )
        }
    }

    private fun readNullableString(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                null
            }
            JsonReader.Token.STRING -> reader.nextString()
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    private fun readStringOrEmpty(reader: JsonReader): String = readNullableString(reader).orEmpty()

    /**
     * Generates a UTF-8 CSV string with UTF-8 BOM so Microsoft Excel and Google Sheets
     * display Arabic text properly, with strict RFC-4180 field escaping.
     */
    fun exportTransactionsToCsv(
        transactions: List<TransactionEntity>,
        categoriesMap: Map<String, CategoryEntity>
    ): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append("Date,Description,Type,Amount,Currency,Category,Notes\r\n")

        for (tx in transactions) {
            val dateStr = MoneyUtils.epochMillisToIsoDate(tx.transactionDate)
            val typeStr = if (tx.type == TransactionType.INCOME) "INCOME (دخل)" else "EXPENSE (مصروف)"
            val amountStr = MoneyUtils.formatPlainMajorAmount(tx.amountMinorUnits)
            val catName = tx.categoryId?.let { categoriesMap[it]?.name } ?: "غير مصنف"

            sb.append(escapeCsvField(dateStr)).append(',')
            sb.append(escapeCsvField(tx.description)).append(',')
            sb.append(escapeCsvField(typeStr)).append(',')
            sb.append(escapeCsvField(amountStr)).append(',')
            sb.append(escapeCsvField(tx.currencyCode)).append(',')
            sb.append(escapeCsvField(catName)).append(',')
            sb.append(escapeCsvField(tx.notes)).append("\r\n")
        }
        return sb.toString()
    }

    fun escapeCsvField(field: String): String {
        val needsQuotes = field.contains(',') ||
            field.contains('"') ||
            field.contains('\n') ||
            field.contains('\r')
        if (!needsQuotes) return field
        val escaped = field.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
