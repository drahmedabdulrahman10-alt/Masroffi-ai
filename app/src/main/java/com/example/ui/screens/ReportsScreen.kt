package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.TransactionEntity
import com.example.domain.BudgetStatus
import com.example.domain.MoneyUtils
import com.example.ui.components.CategoryDonutAndBarsChart
import com.example.ui.components.DailySpendingTrendChart
import com.example.ui.components.MasroofiCalendarDatePickerDialog
import com.example.ui.components.TransactionItemCard
import com.example.ui.theme.DangerRedLight
import com.example.ui.theme.ExpenseRedLight
import com.example.ui.theme.IncomeGreenLight
import com.example.ui.theme.WarningAmberLight
import com.example.ui.viewmodel.ReportPeriodFilter
import com.example.ui.viewmodel.ReportsUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(
    uiState: ReportsUiState,
    onSelectPeriodFilter: (ReportPeriodFilter, String, String) -> Unit,
    onSelectCategoryDrilldown: (String?) -> Unit,
    onRequestSmartSummary: () -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = uiState.reportSummary
    val currency = uiState.preferences.currencySymbol
    val categoriesMap = remember(uiState.categories) {
        uiState.categories.associateBy { it.id }
    }

    var customStart by rememberSaveable(uiState.customReportStartIso) {
        mutableStateOf(uiState.customReportStartIso)
    }
    var customEnd by rememberSaveable(uiState.customReportEndIso) {
        mutableStateOf(uiState.customReportEndIso)
    }
    var pickingCustomStartDate by rememberSaveable { mutableStateOf(false) }
    var pickingCustomEndDate by rememberSaveable { mutableStateOf(false) }

    val drilldownTransactions = remember(
        uiState.reportPeriodTransactions,
        uiState.selectedReportDrilldownCategoryId
    ) {
        val selectedId = uiState.selectedReportDrilldownCategoryId
        if (selectedId == null) {
            emptyList()
        } else {
            uiState.reportPeriodTransactions.filter { it.categoryId == selectedId }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("reports_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "التقارير والإحصائيات",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "تحليل دقيق مبني مباشرة على معاملاتك المحفوظة",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Period Filter Chips
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReportPeriodFilter.entries.forEach { period ->
                        FilterChip(
                            selected = uiState.reportPeriodFilter == period,
                            onClick = {
                                onSelectPeriodFilter(period, customStart, customEnd)
                            },
                            label = { Text(period.arabicLabel) },
                            modifier = Modifier.testTag("report_period_${period.name}")
                        )
                    }
                }

                if (uiState.reportPeriodFilter == ReportPeriodFilter.CUSTOM) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = customStart,
                                    onValueChange = { customStart = it },
                                    label = { Text("من (YYYY-MM-DD)") },
                                    trailingIcon = {
                                        IconButton(onClick = { pickingCustomStartDate = true }) {
                                            Icon(
                                                imageVector = Icons.Default.CalendarMonth,
                                                contentDescription = "اختيار تاريخ البداية"
                                            )
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = customEnd,
                                    onValueChange = { customEnd = it },
                                    label = { Text("إلى (YYYY-MM-DD)") },
                                    trailingIcon = {
                                        IconButton(onClick = { pickingCustomEndDate = true }) {
                                            Icon(
                                                imageVector = Icons.Default.CalendarMonth,
                                                contentDescription = "اختيار تاريخ النهاية"
                                            )
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Button(
                                onClick = {
                                    onSelectPeriodFilter(ReportPeriodFilter.CUSTOM, customStart, customEnd)
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("تطبيق الفترة")
                            }
                        }
                    }
                }
            }
        }

        // Aggregates Cards: Income, Expenses, Net Cash Flow
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryStatCard(
                        title = "إجمالي الدخل",
                        value = MoneyUtils.formatMinorUnits(summary.periodIncomeMinor, currency),
                        valueColor = IncomeGreenLight,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatCard(
                        title = "إجمالي المصروفات",
                        value = MoneyUtils.formatMinorUnits(summary.periodExpenseMinor, currency),
                        valueColor = ExpenseRedLight,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatCard(
                        title = "صافي التدفق",
                        value = MoneyUtils.formatMinorUnits(
                            summary.periodNetCashFlowMinor,
                            currency,
                            showPlusSign = true
                        ),
                        valueColor = if (summary.periodNetCashFlowMinor >= 0) IncomeGreenLight else ExpenseRedLight,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (summary.otherCurrencyBalances.isNotEmpty()) {
                    val otherText = summary.otherCurrencyBalances.joinToString(" • ") { item ->
                        MoneyUtils.formatMinorUnits(item.netBalanceMinor, item.currencySymbol, showPlusSign = true)
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "معاملات بعملات أخرى (محفوظة بشكل منفصل دون دمج مضلل): $otherText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Optional AI Financial Summary ("ملخص ذكي")
        if (uiState.preferences.aiEnabled && uiState.preferences.aiSummaryEnabled) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("smart_summary_card")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ملخص ذكي (AI)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Button(
                                onClick = onRequestSmartSummary,
                                enabled = !uiState.isGeneratingSmartSummary,
                                modifier = Modifier.testTag("generate_smart_summary_btn")
                            ) {
                                if (uiState.isGeneratingSmartSummary) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("جاري التلخيص...")
                                } else {
                                    Text("توليد ملخص ذكي")
                                }
                            }
                        }

                        Text(
                            text = "يحسب التطبيق جميع الأرقام محلياً أولاً، ثم يرسل الإجماليات المحسوبة فقط لصياغة قراءة عربية مختصرة.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (uiState.smartSummaryResultText != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = uiState.smartSummaryResultText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .testTag("smart_summary_result_text")
                                )
                            }
                        }

                        if (uiState.smartSummaryError != null) {
                            Text(
                                text = uiState.smartSummaryError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // Category Breakdown & Drill-down
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "المصروفات حسب التصنيف (اضغط على أي تصنيف لعرض معاملاته)",
                        style = MaterialTheme.typography.titleMedium
                    )

                    CategoryDonutAndBarsChart(
                        items = summary.expenseByCategory,
                        currencySymbol = currency,
                        selectedCategoryId = uiState.selectedReportDrilldownCategoryId,
                        onCategoryClick = onSelectCategoryDrilldown
                    )

                    summary.expenseByCategory.forEach { catItem ->
                        val isSelected = uiState.selectedReportDrilldownCategoryId == catItem.categoryId
                        Surface(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            shape = RoundedCornerShape(10.dp),
                            onClick = { onSelectCategoryDrilldown(catItem.categoryId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("report_cat_row_${catItem.categoryId ?: "uncat"}")
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${catItem.categoryName} (${catItem.transactionCount} معاملات)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${MoneyUtils.formatMinorUnits(catItem.totalMinorUnits, currency)} • ${String.format("%.1f", catItem.percentageOfTotal)}%",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { (catItem.percentageOfTotal.toFloat() / 100f).coerceIn(0f, 1f) },
                                    color = Color(catItem.colorHex),
                                    trackColor = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }
                        }
                    }
                }
            }
        }

        // Drill-down Transactions for Selected Category
        if (uiState.selectedReportDrilldownCategoryId != null) {
            val selectedCatName = categoriesMap[uiState.selectedReportDrilldownCategoryId]?.name ?: "غير مصنف"
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "معاملات تصنيف: $selectedCatName (${drilldownTransactions.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { onSelectCategoryDrilldown(null) }) {
                        Text("إخفاء")
                    }
                }
            }

            items(
                items = drilldownTransactions,
                key = { "drill_${it.id}" }
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

        // Daily Spending Trends & Period Comparison
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "اتجاه الإنفاق اليومي والمقارنة الزمنية",
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (summary.dailyTrends.isEmpty()) {
                        Text(
                            text = "لا توجد بيانات يومية كافية في هذه الفترة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        DailySpendingTrendChart(
                            points = summary.dailyTrends,
                            currencySymbol = currency
                        )
                    }

                    val comp = summary.monthlyComparison
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (!comp.hasComparisonData || comp.percentageChange == null) {
                                Text(
                                    text = "مقارنة مع الفترة السابقة: لا تتوفر بيانات مصروفات كافية في الفترة السابقة لإجراء مقارنة دقيقة.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                val diffSign = if (comp.differenceMinorUnits > 0) "ارتفع" else if (comp.differenceMinorUnits < 0) "انخفض" else "استقر"
                                val pctAbs = String.format("%.1f", kotlin.math.abs(comp.percentageChange))
                                Text(
                                    text = "مقارنة بالفترة السابقة: $diffSign الإنفاق بنسبة $pctAbs% (${MoneyUtils.formatMinorUnits(comp.previousPeriodExpenseMinor, currency)} سابقاً مقابل ${MoneyUtils.formatMinorUnits(comp.currentPeriodExpenseMinor, currency)} حالياً).",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // Category Budgets Status (if configured)
        if (summary.categoryBudgets.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "ميزانيات التصنيفات الفرعية",
                            style = MaterialTheme.typography.titleMedium
                        )
                        summary.categoryBudgets.forEach { cb ->
                            val statusColor = when (cb.status) {
                                BudgetStatus.EXCEEDED -> DangerRedLight
                                BudgetStatus.APPROACHING -> WarningAmberLight
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = cb.categoryName ?: "تصنيف",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${MoneyUtils.formatMinorUnits(cb.spentMinorUnits, currency)} / ${MoneyUtils.formatMinorUnits(cb.limitMinorUnits, currency)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = statusColor
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { (cb.percentageUsed.toFloat() / 100f).coerceIn(0f, 1f) },
                                    color = statusColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickingCustomStartDate) {
        MasroofiCalendarDatePickerDialog(
            initialIsoDate = customStart.ifBlank { MoneyUtils.todayIsoDate() },
            onConfirmDate = { selectedIso ->
                val (newStart, newEnd) = MoneyUtils.normalizeDateRangeBounds(
                    startIsoDate = selectedIso,
                    endIsoDate = customEnd,
                    isStartUpdated = true
                )
                customStart = newStart
                customEnd = newEnd
                onSelectPeriodFilter(ReportPeriodFilter.CUSTOM, newStart, newEnd)
                pickingCustomStartDate = false
            },
            onDismiss = { pickingCustomStartDate = false }
        )
    }

    if (pickingCustomEndDate) {
        MasroofiCalendarDatePickerDialog(
            initialIsoDate = customEnd.ifBlank { MoneyUtils.todayIsoDate() },
            onConfirmDate = { selectedIso ->
                val (newStart, newEnd) = MoneyUtils.normalizeDateRangeBounds(
                    startIsoDate = customStart,
                    endIsoDate = selectedIso,
                    isStartUpdated = false
                )
                customStart = newStart
                customEnd = newEnd
                onSelectPeriodFilter(ReportPeriodFilter.CUSTOM, newStart, newEnd)
                pickingCustomEndDate = false
            },
            onDismiss = { pickingCustomEndDate = false }
        )
    }
}

@Composable
private fun SummaryStatCard(
    title: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
        }
    }
}
