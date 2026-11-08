package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.CategoryEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionSource
import com.example.data.local.TransactionType
import com.example.data.preferences.SupportedCurrencies
import com.example.domain.CategoryBreakdownItem
import com.example.domain.DailySpendingPoint
import com.example.domain.MoneyUtils
import com.example.domain.QuickDateOption
import com.example.ui.theme.ExpenseRedLight
import com.example.ui.theme.IncomeGreenLight
import java.time.YearMonth
import java.time.ZoneId

object CategoryIconHelper {
    val availableIcons = listOf(
        "restaurant" to "طعام ومطاعم",
        "shopping_basket" to "تسوق وطلبات",
        "directions_car" to "مواصلات",
        "school" to "دراسة",
        "medical_services" to "صحة وأدوية",
        "fitness_center" to "رياضة",
        "checkroom" to "ملابس",
        "movie" to "ترفيه",
        "receipt_long" to "فواتير",
        "payments" to "مرتب ودخل",
        "work" to "عمل حر",
        "card_giftcard" to "هدايا",
        "replay" to "استرداد",
        "account_balance_wallet" to "محفظة",
        "category" to "عام"
    )

    val availableColors = listOf(
        0xFF0F766EL,
        0xFFEA580CL,
        0xFF2563EBL,
        0xFF7C3AEDL,
        0xFFDC2626L,
        0xFF16A34AL,
        0xFFDB2777L,
        0xFFD97706L,
        0xFF475569L,
        0xFF0284C7L
    )

    fun getIcon(name: String?): ImageVector {
        return when (name) {
            "restaurant" -> Icons.Default.Restaurant
            "shopping_basket" -> Icons.Default.ShoppingBasket
            "directions_car" -> Icons.Default.DirectionsCar
            "school" -> Icons.Default.School
            "medical_services" -> Icons.Default.MedicalServices
            "fitness_center" -> Icons.Default.FitnessCenter
            "checkroom" -> Icons.Default.Checkroom
            "movie" -> Icons.Default.Movie
            "receipt_long" -> Icons.AutoMirrored.Filled.ReceiptLong
            "payments" -> Icons.Default.Payments
            "work" -> Icons.Default.Work
            "card_giftcard" -> Icons.Default.CardGiftcard
            "replay" -> Icons.Default.Replay
            "account_balance_wallet" -> Icons.Default.AccountBalanceWallet
            "category" -> Icons.Default.Category
            else -> Icons.AutoMirrored.Filled.HelpOutline
        }
    }
}

@Composable
fun TransactionItemCard(
    transaction: TransactionEntity,
    category: CategoryEntity?,
    currencySymbol: String,
    onEdit: (TransactionEntity) -> Unit,
    onDelete: (TransactionEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val isIncome = transaction.type == TransactionType.INCOME
    val accentColor = if (isIncome) IncomeGreenLight else ExpenseRedLight
    val categoryColor = category?.colorHex?.let { Color(it) } ?: MaterialTheme.colorScheme.outline

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEdit(transaction) }
            .testTag("transaction_card_${transaction.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(categoryColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = CategoryIconHelper.getIcon(category?.iconName),
                    contentDescription = category?.name ?: "غير مصنف",
                    tint = categoryColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = transaction.description,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (transaction.source == TransactionSource.AI_BATCH) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "AI",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = category?.name ?: "غير مصنف",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " • ${MoneyUtils.formatArabicReadableDate(transaction.transactionDate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (transaction.notes.isNotBlank()) {
                    Text(
                        text = transaction.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                val signedMinor = if (isIncome) transaction.amountMinorUnits else -transaction.amountMinorUnits
                val txCurrencySymbol = remember(transaction.currencyCode, currencySymbol) {
                    if (transaction.currencyCode.isNotBlank()) {
                        SupportedCurrencies.fromCode(transaction.currencyCode).symbol
                    } else {
                        currencySymbol
                    }
                }
                Text(
                    text = MoneyUtils.formatMinorUnits(
                        minorUnits = signedMinor,
                        currencySymbol = txCurrencySymbol,
                        showPlusSign = true
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Row {
                    IconButton(
                        onClick = { onEdit(transaction) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("edit_tx_${transaction.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "تعديل المعاملة",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { onDelete(transaction) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("delete_tx_${transaction.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "حذف المعاملة",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryDonutAndBarsChart(
    items: List<CategoryBreakdownItem>,
    currencySymbol: String,
    selectedCategoryId: String? = null,
    onCategoryClick: ((String?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "لا توجد مصروفات مسجلة لعرض الرسم البياني",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(114.dp)) {
                    val strokeWidth = 22.dp.toPx()
                    val diameter = size.minDimension - strokeWidth
                    val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                    var startAngle = -90f
                    for (item in items) {
                        val sweep = ((item.percentageOfTotal.toFloat() / 100f) * 360f).coerceAtLeast(2f)
                        drawArc(
                            color = Color(item.colorHex),
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = Size(diameter, diameter),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                        )
                        startAngle += sweep
                    }
                }
                Text(
                    text = "${items.size} فئات",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.take(5).forEach { item ->
                    val isSelected = selectedCategoryId != null && selectedCategoryId == item.categoryId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else Color.Transparent
                            )
                            .clickable(enabled = onCategoryClick != null) {
                                onCategoryClick?.invoke(item.categoryId)
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(item.colorHex))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.categoryName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${String.format("%.1f", item.percentageOfTotal)}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = MoneyUtils.formatMinorUnits(item.totalMinorUnits, currencySymbol),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DailySpendingTrendChart(
    points: List<DailySpendingPoint>,
    currencySymbol: String,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return
    val maxExpense = points.maxOfOrNull { it.expenseMinorUnits }?.coerceAtLeast(1L) ?: 1L
    val recentPoints = points.takeLast(10)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            for (pt in recentPoints) {
                val fraction = (pt.expenseMinorUnits.toFloat() / maxExpense.toFloat()).coerceIn(0.05f, 1f)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height((80 * fraction).dp)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = pt.dayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualTransactionDialog(
    initialType: TransactionType = TransactionType.EXPENSE,
    existingTransaction: TransactionEntity? = null,
    categories: List<CategoryEntity>,
    currencySymbol: String,
    onDismiss: () -> Unit,
    onSave: (
        existingId: String?,
        description: String,
        amountText: String,
        type: TransactionType,
        categoryId: String?,
        isoDate: String,
        notes: String,
        isRefundOffset: Boolean
    ) -> Unit
) {
    val dialogKey = existingTransaction?.id ?: "new_${initialType.name}"
    val effectiveCurrencySymbol = remember(existingTransaction?.currencyCode, currencySymbol) {
        existingTransaction?.currencyCode?.takeIf { it.isNotBlank() }?.let {
            SupportedCurrencies.fromCode(it).symbol
        } ?: currencySymbol
    }

    var selectedType by rememberSaveable(dialogKey) {
        mutableStateOf(existingTransaction?.type ?: initialType)
    }
    var description by rememberSaveable(dialogKey) {
        mutableStateOf(existingTransaction?.description ?: "")
    }
    var amountText by rememberSaveable(dialogKey) {
        mutableStateOf(
            existingTransaction?.let { MoneyUtils.formatPlainMajorAmount(it.amountMinorUnits) } ?: ""
        )
    }
    var isoDate by rememberSaveable(dialogKey) {
        mutableStateOf(
            existingTransaction?.let { MoneyUtils.epochMillisToIsoDate(it.transactionDate) }
                ?: MoneyUtils.todayIsoDate()
        )
    }
    var notes by rememberSaveable(dialogKey) {
        mutableStateOf(existingTransaction?.notes ?: "")
    }
    var isRefundOffset by rememberSaveable(dialogKey) {
        mutableStateOf(existingTransaction?.isRefundOffset ?: false)
    }

    val eligibleCategories = remember(categories, selectedType, isRefundOffset, existingTransaction?.categoryId) {
        val targetType = if (selectedType == TransactionType.INCOME && isRefundOffset) {
            TransactionType.EXPENSE
        } else {
            selectedType
        }
        categories.filter {
            (!it.isArchived || it.id == existingTransaction?.categoryId) && it.type == targetType
        }
    }

    var selectedCategoryId by rememberSaveable(dialogKey) {
        mutableStateOf(
            existingTransaction?.categoryId ?: eligibleCategories.firstOrNull()?.id
        )
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (existingTransaction != null) "تعديل معاملة" else "إضافة معاملة يدوياً",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedType == TransactionType.EXPENSE,
                        onClick = {
                            selectedType = TransactionType.EXPENSE
                            isRefundOffset = false
                            selectedCategoryId = categories
                                .firstOrNull { !it.isArchived && it.type == TransactionType.EXPENSE }?.id
                        },
                        label = { Text("مصروف") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_type_expense")
                    )
                    FilterChip(
                        selected = selectedType == TransactionType.INCOME,
                        onClick = {
                            selectedType = TransactionType.INCOME
                            selectedCategoryId = categories
                                .firstOrNull { !it.isArchived && it.type == TransactionType.INCOME }?.id
                        },
                        label = { Text("دخل") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_type_income")
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it
                        errorMessage = null
                    },
                    label = { Text("الوصف (مثال: طماطم، تاكسي، مرتب)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_description_input")
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        errorMessage = null
                    },
                    label = { Text("المبلغ ($effectiveCurrencySymbol)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_amount_input")
                )

                TransactionDateSelectorSection(
                    selectedIsoDate = isoDate,
                    onDateSelected = { updatedDate ->
                        isoDate = updatedDate
                        errorMessage = null
                    },
                    tagPrefix = "manual"
                )

                if (selectedType == TransactionType.INCOME) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                isRefundOffset = !isRefundOffset
                                val targetType = if (isRefundOffset) TransactionType.EXPENSE else TransactionType.INCOME
                                selectedCategoryId = categories.firstOrNull { !it.isArchived && it.type == targetType }?.id
                            }
                    ) {
                        Checkbox(
                            checked = isRefundOffset,
                            onCheckedChange = { checked ->
                                isRefundOffset = checked
                                val targetType = if (checked) TransactionType.EXPENSE else TransactionType.INCOME
                                selectedCategoryId = categories.firstOrNull { !it.isArchived && it.type == targetType }?.id
                            }
                        )
                        Text(
                            text = "هذا المبلغ استرداد لمصروف سابق (يُخصم من إجمالي المصروفات)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Text(
                    text = "التصنيف:",
                    style = MaterialTheme.typography.labelLarge
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    eligibleCategories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategoryId == cat.id,
                            onClick = { selectedCategoryId = cat.id },
                            label = { Text(cat.name) },
                            modifier = Modifier.testTag("manual_cat_${cat.id}")
                        )
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("ملاحظات اختيارية") },
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_notes_input")
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("manual_validation_error")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanDesc = description.trim()
                    val minor = MoneyUtils.parseAmountToMinorUnits(amountText)
                    val dateEpoch = MoneyUtils.isoDateToEpochMillis(isoDate)
                    when {
                        cleanDesc.isEmpty() -> errorMessage = "يرجى إدخال وصف المعاملة"
                        minor == null || minor <= 0L -> errorMessage = "يرجى إدخال مبلغ صحيح أكبر من الصفر"
                        dateEpoch == null -> errorMessage = "يرجى إدخال تاريخ صحيح بالصيغة YYYY-MM-DD"
                        else -> {
                            val normalizedIso = MoneyUtils.epochMillisToIsoDate(dateEpoch)
                            onSave(
                                existingTransaction?.id,
                                cleanDesc,
                                amountText,
                                selectedType,
                                selectedCategoryId,
                                normalizedIso,
                                notes,
                                isRefundOffset
                            )
                        }
                    }
                },
                modifier = Modifier.testTag("manual_save_button")
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("manual_cancel_button")
            ) {
                Text("إلغاء")
            }
        }
    )
}

/**
 * Reusable Arabic-first date selection control combining:
 * 1. Clear display of the currently selected date + button to open the Calendar Date Picker dialog.
 * 2. Dynamic quick-selection chips (اليوم / Today, أمس / Yesterday, أول أمس / Day Before Yesterday)
 *    with active-selection highlighting.
 * 3. Optional ISO date text field (YYYY-MM-DD) with a trailing calendar trigger icon.
 */
@Composable
fun TransactionDateSelectorSection(
    selectedIsoDate: String,
    onDateSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "manual",
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    showTextField: Boolean = true
) {
    var showCalendarDialog by rememberSaveable(tagPrefix) { mutableStateOf(false) }
    val matchedQuickOption = remember(selectedIsoDate, nowMillis, zoneId) {
        MoneyUtils.matchQuickDateOption(selectedIsoDate, nowMillis, zoneId)
    }
    val formattedBannerText = remember(selectedIsoDate, nowMillis, zoneId) {
        MoneyUtils.formatSelectedDateBannerText(selectedIsoDate, nowMillis, zoneId)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${tagPrefix}_selected_date_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "التاريخ المحدد:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formattedBannerText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("${tagPrefix}_selected_date_text")
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = { showCalendarDialog = true },
                    modifier = Modifier.testTag("${tagPrefix}_open_calendar_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = "اختيار التاريخ من التقويم",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "التقويم",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // Quick Date Selection Chips: اليوم (Today), أمس (Yesterday), أول أمس (Day Before Yesterday)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickDateOption.entries.forEach { option ->
                val isSelected = matchedQuickOption == option
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        val resolvedIso = MoneyUtils.resolveQuickDateIso(option, nowMillis, zoneId)
                        onDateSelected(resolvedIso)
                    },
                    label = {
                        Text(
                            text = option.arabicLabel,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("${tagPrefix}_quick_date_${option.name}")
                )
            }
        }

        if (showTextField) {
            OutlinedTextField(
                value = selectedIsoDate,
                onValueChange = { onDateSelected(it) },
                label = { Text("التاريخ (YYYY-MM-DD)") },
                trailingIcon = {
                    IconButton(
                        onClick = { showCalendarDialog = true },
                        modifier = Modifier.testTag("${tagPrefix}_date_field_calendar_icon")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = "فتح التقويم"
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${tagPrefix}_date_input")
            )
        }
    }

    if (showCalendarDialog) {
        MasroofiCalendarDatePickerDialog(
            initialIsoDate = selectedIsoDate,
            nowMillis = nowMillis,
            zoneId = zoneId,
            onConfirmDate = { confirmedIso ->
                onDateSelected(confirmedIso)
                showCalendarDialog = false
            },
            onDismiss = {
                showCalendarDialog = false
            }
        )
    }
}

/**
 * Arabic-first RTL Calendar Date Picker Dialog built on java.time (LocalDate & YearMonth).
 * - Avoids any UTC-to-local date shift by operating strictly on LocalDate in the local ZoneId.
 * - Supports month and year navigation (previous/next month, previous/next year).
 * - Highlights Today distinctly from the selected date.
 * - Preserves the previously committed date when canceled or dismissed without confirmation.
 */
@Composable
fun MasroofiCalendarDatePickerDialog(
    initialIsoDate: String,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    onConfirmDate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val todayDate = remember(nowMillis, zoneId) {
        MoneyUtils.epochMillisToLocalDate(nowMillis, zoneId)
    }
    val initialDate = remember(initialIsoDate, todayDate) {
        MoneyUtils.parseIsoLocalDate(initialIsoDate) ?: todayDate
    }

    var pendingSelectedIso by remember(initialIsoDate) {
        mutableStateOf(MoneyUtils.formatLocalDateToIso(initialDate))
    }
    val pendingLocalDate = remember(pendingSelectedIso, initialDate) {
        MoneyUtils.parseIsoLocalDate(pendingSelectedIso) ?: initialDate
    }

    var displayedYearMonthStr by remember(initialIsoDate) {
        mutableStateOf(YearMonth.from(initialDate).toString())
    }
    val displayedYearMonth = remember(displayedYearMonthStr, initialDate) {
        try {
            YearMonth.parse(displayedYearMonthStr)
        } catch (_: Exception) {
            YearMonth.from(initialDate)
        }
    }

    val calendarCells = remember(displayedYearMonth) {
        MoneyUtils.buildCalendarMonthCells(displayedYearMonth)
    }
    val matchedPendingQuickOption = remember(pendingSelectedIso, nowMillis, zoneId) {
        MoneyUtils.matchQuickDateOption(pendingSelectedIso, nowMillis, zoneId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("calendar_date_picker_dialog"),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "اختيار التاريخ",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "التاريخ المختار: ${MoneyUtils.formatSelectedDateBannerText(pendingSelectedIso, nowMillis, zoneId)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("calendar_selected_date_banner")
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Quick Date Shortcuts inside the Calendar Dialog
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuickDateOption.entries.forEach { option ->
                        val resolvedDate = MoneyUtils.resolveQuickDate(option, todayDate)
                        val resolvedIso = MoneyUtils.formatLocalDateToIso(resolvedDate)
                        FilterChip(
                            selected = matchedPendingQuickOption == option,
                            onClick = {
                                pendingSelectedIso = resolvedIso
                                displayedYearMonthStr = YearMonth.from(resolvedDate).toString()
                            },
                            label = {
                                Text(
                                    text = option.arabicLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("calendar_quick_date_${option.name}")
                        )
                    }
                }

                // Month & Year Navigation Header
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    displayedYearMonthStr = displayedYearMonth.minusYears(1).toString()
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("calendar_prev_year_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardDoubleArrowRight,
                                    contentDescription = "السنة السابقة"
                                )
                            }
                            IconButton(
                                onClick = {
                                    displayedYearMonthStr = displayedYearMonth.minusMonths(1).toString()
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("calendar_prev_month_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "الشهر السابق"
                                )
                            }
                        }

                        Text(
                            text = MoneyUtils.arabicMonthYearTitle(displayedYearMonth),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("calendar_month_year_title")
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    displayedYearMonthStr = displayedYearMonth.plusMonths(1).toString()
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("calendar_next_month_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "الشهر التالي"
                                )
                            }
                            IconButton(
                                onClick = {
                                    displayedYearMonthStr = displayedYearMonth.plusYears(1).toString()
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("calendar_next_year_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardDoubleArrowLeft,
                                    contentDescription = "السنة التالية"
                                )
                            }
                        }
                    }
                }

                // Arabic Week Days Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MoneyUtils.arabicWeekDaysShort.forEach { dayName ->
                        Text(
                            text = dayName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Calendar Days Grid (7 columns per week row)
                val weekRows = remember(calendarCells) { calendarCells.chunked(7) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    weekRows.forEach { week ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            week.forEach { cellDate ->
                                if (cellDate == null) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(32.dp)
                                    )
                                } else {
                                    val cellIso = MoneyUtils.formatLocalDateToIso(cellDate)
                                    val isSelected = cellDate == pendingLocalDate
                                    val isToday = cellDate == todayDate

                                    val bgColor = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        else -> Color.Transparent
                                    }
                                    val textColor = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                    val borderModifier = if (isToday && !isSelected) {
                                        Modifier.border(
                                            width = 1.5.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape
                                        )
                                    } else {
                                        Modifier
                                    }

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(32.dp)
                                            .padding(1.dp)
                                            .clip(CircleShape)
                                            .background(bgColor)
                                            .then(borderModifier)
                                            .clickable {
                                                pendingSelectedIso = cellIso
                                            }
                                            .testTag("calendar_day_$cellIso")
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = cellDate.dayOfMonth.toString(),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                                color = textColor,
                                                textAlign = TextAlign.Center
                                            )
                                            if (isToday) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                            else MaterialTheme.colorScheme.primary
                                                        )
                                                        .testTag("calendar_today_indicator_$cellIso")
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmDate(pendingSelectedIso) },
                modifier = Modifier.testTag("calendar_confirm_button")
            ) {
                Text("تأكيد")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("calendar_cancel_button")
            ) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                modifier = Modifier.testTag("confirm_delete_button")
            ) {
                Text("تأكيد الحذف")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_delete_button")
            ) {
                Text("إلغاء")
            }
        }
    )
}
