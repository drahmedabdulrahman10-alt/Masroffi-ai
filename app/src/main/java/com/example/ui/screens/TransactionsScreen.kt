package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.preferences.SupportedCurrencies
import com.example.domain.MoneyUtils
import com.example.ui.components.TransactionItemCard
import com.example.ui.viewmodel.TransactionHistoryFilter
import com.example.ui.viewmodel.TransactionsUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionsScreen(
    uiState: TransactionsUiState,
    onUpdateFilter: (TransactionHistoryFilter) -> Unit,
    onClearFilters: () -> Unit,
    onOpenManualEntry: (TransactionType) -> Unit,
    onOpenAiEntry: () -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val filter = uiState.historyFilter
    val currency = uiState.preferences.currencySymbol
    val categoriesMap = remember(uiState.categories) {
        uiState.categories.associateBy { it.id }
    }
    var showAdvancedFilters by rememberSaveable { mutableStateOf(false) }

    val groupedByDate = remember(uiState.filteredHistoryTransactions) {
        uiState.filteredHistoryTransactions.groupBy {
            MoneyUtils.formatArabicReadableDate(it.transactionDate)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("transactions_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header & Actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "سجل المعاملات",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "إجمالي المعروض: ${uiState.filteredHistoryTransactions.size} معاملة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onOpenAiEntry,
                        modifier = Modifier.testTag("tx_screen_ai_btn")
                    ) {
                        Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إدخال ذكي")
                    }
                    Button(
                        onClick = { onOpenManualEntry(TransactionType.EXPENSE) },
                        modifier = Modifier.testTag("tx_screen_manual_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("يدوي")
                    }
                }
            }
        }

        // Search & Filter Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = filter.searchQuery,
                            onValueChange = { onUpdateFilter(filter.copy(searchQuery = it)) },
                            label = { Text("ابحث بالوصف أو الملاحظات...") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Default.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (filter.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onUpdateFilter(filter.copy(searchQuery = "")) }) {
                                        Icon(imageVector = Icons.Default.Clear, contentDescription = "مسح البحث")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tx_search_input")
                        )

                        IconButton(
                            onClick = { showAdvancedFilters = !showAdvancedFilters },
                            modifier = Modifier.testTag("tx_toggle_filters_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "تصفية متقدمة",
                                tint = if (showAdvancedFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Type Filter Chips
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = filter.selectedType == null,
                            onClick = { onUpdateFilter(filter.copy(selectedType = null)) },
                            label = { Text("الكل") }
                        )
                        FilterChip(
                            selected = filter.selectedType == TransactionType.EXPENSE,
                            onClick = { onUpdateFilter(filter.copy(selectedType = TransactionType.EXPENSE)) },
                            label = { Text("المصروفات") }
                        )
                        FilterChip(
                            selected = filter.selectedType == TransactionType.INCOME,
                            onClick = { onUpdateFilter(filter.copy(selectedType = TransactionType.INCOME)) },
                            label = { Text("الدخل") }
                        )
                    }

                    AnimatedVisibility(visible = showAdvancedFilters) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "تصفية حسب التصنيف:",
                                style = MaterialTheme.typography.labelMedium
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilterChip(
                                    selected = filter.selectedCategoryId == null,
                                    onClick = { onUpdateFilter(filter.copy(selectedCategoryId = null)) },
                                    label = { Text("كل التصنيفات") }
                                )
                                FilterChip(
                                    selected = filter.selectedCategoryId == "UNCATEGORIZED",
                                    onClick = { onUpdateFilter(filter.copy(selectedCategoryId = "UNCATEGORIZED")) },
                                    label = { Text("غير مصنف") }
                                )
                                uiState.categories.forEach { cat ->
                                    FilterChip(
                                        selected = filter.selectedCategoryId == cat.id,
                                        onClick = { onUpdateFilter(filter.copy(selectedCategoryId = cat.id)) },
                                        label = { Text(cat.name) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = filter.minAmountText,
                                    onValueChange = { onUpdateFilter(filter.copy(minAmountText = it)) },
                                    label = { Text("أقل مبلغ ($currency)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = filter.maxAmountText,
                                    onValueChange = { onUpdateFilter(filter.copy(maxAmountText = it)) },
                                    label = { Text("أعلى مبلغ ($currency)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = filter.startIsoDate,
                                    onValueChange = { onUpdateFilter(filter.copy(startIsoDate = it)) },
                                    label = { Text("من تاريخ (YYYY-MM-DD)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = filter.endIsoDate,
                                    onValueChange = { onUpdateFilter(filter.copy(endIsoDate = it)) },
                                    label = { Text("إلى تاريخ (YYYY-MM-DD)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = onClearFilters) {
                                    Text("إعادة ضبط الفلاتر")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (groupedByDate.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            text = "لا توجد معاملات مطابقة للبحث",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "جرب تعديل كلمات البحث أو مسح الفلاتر، أو أضف معاملة جديدة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            groupedByDate.forEach { (dateLabel, dayTransactions) ->
                item(key = "header_$dateLabel") {
                    val dayNetsFormatted = dayTransactions
                        .groupBy { it.currencyCode.ifBlank { uiState.preferences.currencyCode } }
                        .entries
                        .joinToString(" | ") { (code, txs) ->
                            val net = txs.sumOf {
                                if (it.type == TransactionType.INCOME) it.amountMinorUnits else -it.amountMinorUnits
                            }
                            MoneyUtils.formatMinorUnits(
                                minorUnits = net,
                                currencySymbol = SupportedCurrencies.fromCode(code).symbol,
                                showPlusSign = true
                            )
                        }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dateLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "صافي اليوم: $dayNetsFormatted",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(
                    items = dayTransactions,
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
    }
}
