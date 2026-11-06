package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.domain.BudgetStatus
import com.example.domain.MoneyUtils
import com.example.ui.components.CategoryDonutAndBarsChart
import com.example.ui.components.TransactionItemCard
import com.example.ui.theme.DangerRedLight
import com.example.ui.theme.EmeraldPrimaryDark
import com.example.ui.theme.EmeraldPrimaryLight
import com.example.ui.theme.ExpenseRedLight
import com.example.ui.theme.IncomeGreenLight
import com.example.ui.theme.WarningAmberLight
import com.example.ui.viewmodel.MasroofiUiState

@Composable
fun HomeScreen(
    uiState: MasroofiUiState,
    onOpenManualEntry: (TransactionType) -> Unit,
    onOpenAiEntry: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenAllTransactions: () -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    onSaveOpeningBalance: (String) -> Unit,
    onSaveMonthlyBudget: (String) -> Unit,
    onChangeMonth: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = uiState.homeSummary
    val currency = uiState.preferences.currencySymbol
    val categoriesMap = remember(uiState.categories) {
        uiState.categories.associateBy { it.id }
    }

    var showOpeningBalanceDialog by rememberSaveable { mutableStateOf(false) }
    var showBudgetDialog by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Month Selector Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "مصروفي الذكي",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ملخصك المالي الشخصي والمحلي",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                onChangeMonth(MoneyUtils.previousYearMonth(uiState.selectedHomeYearMonth))
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "الشهر السابق"
                            )
                        }
                        Text(
                            text = uiState.selectedHomeYearMonth,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                        IconButton(
                            onClick = {
                                val nextYm = nextYearMonth(uiState.selectedHomeYearMonth)
                                onChangeMonth(nextYm)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = "الشهر التالي"
                            )
                        }
                    }
                }
            }
        }

        // A. Current Balance Hero Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("current_balance_card"),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    EmeraldPrimaryLight,
                                    Color(0xFF115E59)
                                )
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "الرصيد النقدي الحالي",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            TextButton(
                                onClick = { showOpeningBalanceDialog = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                modifier = Modifier.testTag("edit_opening_balance_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (summary.openingBalanceConfigured) {
                                        "الرصيد الافتتاحي: ${MoneyUtils.formatMinorUnits(summary.openingBalanceMinorUnits, currency)}"
                                    } else {
                                        "ضبط الرصيد الافتتاحي"
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = MoneyUtils.formatMinorUnits(
                                minorUnits = summary.currentBalanceMinor,
                                currencySymbol = currency,
                                showPlusSign = false
                            ),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (summary.currentBalanceMinor >= 0) Color.White else Color(0xFFFCA5A5),
                            modifier = Modifier.testTag("current_balance_value")
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (summary.openingBalanceConfigured) {
                                "محسوب من الرصيد الافتتاحي وجميع المعاملات المحفوظة فقط"
                            } else {
                                "الرصيد الافتتاحي غير مضبوط (0 ج.م) — يعكس الرصيد المعاملات المسجلة فقط"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // B & C. Monthly Income & Monthly Expenses Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("monthly_income_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                contentDescription = null,
                                tint = IncomeGreenLight,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "دخل الشهر",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = MoneyUtils.formatMinorUnits(summary.periodIncomeMinor, currency),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = IncomeGreenLight,
                            modifier = Modifier.testTag("monthly_income_value")
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("monthly_expenses_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.TrendingDown,
                                contentDescription = null,
                                tint = ExpenseRedLight,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "مصروفات الشهر",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = MoneyUtils.formatMinorUnits(summary.periodExpenseMinor, currency),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = ExpenseRedLight,
                            modifier = Modifier.testTag("monthly_expenses_value")
                        )
                    }
                }
            }
        }

        // G. Quick Actions
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenAiEntry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("quick_action_ai_entry"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "إدخال معاملات متعددة بالذكاء الاصطناعي أو الصوت",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onOpenManualEntry(TransactionType.EXPENSE) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("quick_action_add_expense")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RemoveCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("مصروف")
                    }

                    FilledTonalButton(
                        onClick = { onOpenManualEntry(TransactionType.INCOME) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("quick_action_add_income")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("دخل")
                    }

                    OutlinedButton(
                        onClick = onOpenReports,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("quick_action_reports")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("التقارير")
                    }
                }
            }
        }

        // D. Remaining Budget Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_budget_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                val overallBudget = summary.overallBudget
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Savings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "الميزانية الشهرية (${uiState.selectedHomeYearMonth})",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        TextButton(
                            onClick = { showBudgetDialog = true },
                            modifier = Modifier.testTag("configure_budget_button")
                        ) {
                            Text(if (overallBudget != null) "تعديل" else "تحديد ميزانية")
                        }
                    }

                    if (overallBudget == null) {
                        Text(
                            text = "لم تقم بتحديد ميزانية شهرية بعد. اضغط على (تحديد ميزانية) لمتابعة حدود الإنفاق.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val barColor = when (overallBudget.status) {
                            BudgetStatus.EXCEEDED -> DangerRedLight
                            BudgetStatus.APPROACHING -> WarningAmberLight
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (overallBudget.percentageUsed.toFloat() / 100f).coerceIn(0f, 1f) },
                            color = barColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "المصروف: ${MoneyUtils.formatMinorUnits(overallBudget.spentMinorUnits, currency)} (${String.format("%.1f", overallBudget.percentageUsed)}%)",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = if (overallBudget.remainingMinorUnits >= 0) {
                                    "المتبقي: ${MoneyUtils.formatMinorUnits(overallBudget.remainingMinorUnits, currency)}"
                                } else {
                                    "تجاوز بـ: ${MoneyUtils.formatMinorUnits(-overallBudget.remainingMinorUnits, currency)}"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = barColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // E. Spending by Category
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_category_spending_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "الإنفاق حسب التصنيف",
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(onClick = onOpenReports) {
                            Text("تفاصيل أكثر")
                        }
                    }
                    CategoryDonutAndBarsChart(
                        items = summary.expenseByCategory,
                        currencySymbol = currency
                    )
                }
            }
        }

        // F. Recent Transactions Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "أحدث المعاملات المحفوظة",
                    style = MaterialTheme.typography.titleMedium
                )
                if (uiState.allTransactions.isNotEmpty()) {
                    TextButton(onClick = onOpenAllTransactions) {
                        Text("عرض الكل (${uiState.allTransactions.size})")
                    }
                }
            }
        }

        if (uiState.allTransactions.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("empty_transactions_placeholder"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "لا توجد معاملات مسجلة حتى الآن",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ابدأ بإضافة مصروف أو دخل يدوياً، أو جرب الإدخال الذكي لوصف عدة معاملات في جملة واحدة بالعامية المصرية!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                items = uiState.allTransactions.take(8),
                key = { it.id }
            ) { tx ->
                TransactionItemCard(
                    transaction = tx,
                    category = tx.categoryId?.let { categoriesMap[it] },
                    currencySymbol = currency,
                    onEdit = onEditTransaction,
                    onDelete = onDeleteTransaction
                )
            }
        }
    }

    if (showOpeningBalanceDialog) {
        var input by rememberSaveable {
            mutableStateOf(
                MoneyUtils.formatPlainMajorAmount(summary.openingBalanceMinorUnits)
            )
        }
        AlertDialog(
            onDismissRequest = { showOpeningBalanceDialog = false },
            title = { Text("ضبط الرصيد الافتتاحي") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "يُضاف الرصيد الافتتاحي إلى صافي الدخل والمصروفات لحساب رصيدك النقدي الفعلي.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("الرصيد الافتتاحي ($currency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("opening_balance_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveOpeningBalance(input)
                        showOpeningBalanceDialog = false
                    },
                    modifier = Modifier.testTag("save_opening_balance_btn")
                ) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOpeningBalanceDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (showBudgetDialog) {
        var budgetInput by rememberSaveable {
            mutableStateOf(
                summary.overallBudget?.let { MoneyUtils.formatPlainMajorAmount(it.limitMinorUnits) } ?: ""
            )
        }
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            title = { Text("تحديد الميزانية الشهرية (${uiState.selectedHomeYearMonth})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "الميزانية أداة تخطيطية لمتابعة إنفاقك الشهري ولا تمنعك من تسجيل أي معاملة.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = budgetInput,
                        onValueChange = { budgetInput = it },
                        label = { Text("حد الإنفاق الشهري ($currency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("monthly_budget_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveMonthlyBudget(budgetInput)
                        showBudgetDialog = false
                    },
                    modifier = Modifier.testTag("save_monthly_budget_btn")
                ) {
                    Text("حفظ الميزانية")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

private fun nextYearMonth(yearMonth: String): String {
    val parts = yearMonth.split("-")
    if (parts.size != 2) return yearMonth
    val year = parts[0].toIntOrNull() ?: return yearMonth
    val month = parts[1].toIntOrNull() ?: return yearMonth
    return if (month == 12) {
        String.format(java.util.Locale.US, "%04d-01", year + 1)
    } else {
        String.format(java.util.Locale.US, "%04d-%02d", year, month + 1)
    }
}
