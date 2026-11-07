package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.preferences.SupportedCurrencies
import com.example.data.preferences.ThemePreference
import com.example.ui.components.ConfirmDeleteDialog
import com.example.ui.components.ManualTransactionDialog
import com.example.ui.screens.AiEntrySheetScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ReportsScreen
import com.example.ui.screens.SettingsAndCategoriesScreen
import com.example.ui.screens.TransactionsScreen
import com.example.ui.theme.MasroofiTheme
import com.example.ui.viewmodel.MasroofiViewModel

enum class MasroofiDestination(
    val route: String,
    val arabicTitle: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME(
        route = "home",
        arabicTitle = "الرئيسية",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        testTag = "nav_tab_home"
    ),
    TRANSACTIONS(
        route = "transactions",
        arabicTitle = "المعاملات",
        selectedIcon = Icons.Filled.ReceiptLong,
        unselectedIcon = Icons.Outlined.ReceiptLong,
        testTag = "nav_tab_transactions"
    ),
    REPORTS(
        route = "reports",
        arabicTitle = "التقارير",
        selectedIcon = Icons.Filled.BarChart,
        unselectedIcon = Icons.Outlined.BarChart,
        testTag = "nav_tab_reports"
    ),
    SETTINGS(
        route = "settings",
        arabicTitle = "الإعدادات",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_tab_settings"
    )
}

@Composable
fun MasroofiApp(
    viewModel: MasroofiViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (uiState.preferences.themePreference) {
        ThemePreference.SYSTEM -> systemDark
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    MasroofiTheme(darkTheme = isDark) {
        var currentDestination by rememberSaveable { mutableStateOf(MasroofiDestination.HOME) }
        var showAiEntryScreen by rememberSaveable { mutableStateOf(false) }

        var showManualDialog by rememberSaveable { mutableStateOf(false) }
        var manualDialogInitialType by rememberSaveable { mutableStateOf(TransactionType.EXPENSE) }
        var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
        var transactionPendingDelete by remember { mutableStateOf<TransactionEntity?>(null) }

        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(uiState.snackbarMessage) {
            val msg = uiState.snackbarMessage
            if (!msg.isNullOrBlank()) {
                snackbarHostState.showSnackbar(msg)
                viewModel.clearSnackbar()
            }
        }

        if (currentDestination != MasroofiDestination.HOME && !showAiEntryScreen) {
            BackHandler {
                currentDestination = MasroofiDestination.HOME
            }
        }

        if (showAiEntryScreen) {
            AiEntrySheetScreen(
                uiState = uiState,
                onClose = { showAiEntryScreen = false },
                onUpdateInputText = viewModel::updateAiInputText,
                onAppendSpeechText = viewModel::appendRecognizedSpeech,
                onAnalyzeInput = viewModel::analyzeAiInput,
                onUpdateDraft = viewModel::updateProposedDraft,
                onRemoveDraft = viewModel::removeProposedDraft,
                onCancelReview = viewModel::cancelAiReview,
                onSaveAllReviewed = {
                    viewModel.saveAllReviewedProposals(
                        onSavedSuccess = { showAiEntryScreen = false }
                    )
                }
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isExpandedScreen = maxWidth >= 600.dp

                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        if (!isExpandedScreen) {
                            NavigationBar(modifier = Modifier.testTag("bottom_navigation_bar")) {
                                MasroofiDestination.entries.forEach { dest ->
                                    val selected = currentDestination == dest
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { currentDestination = dest },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = dest.arabicTitle
                                            )
                                        },
                                        label = { Text(dest.arabicTitle) },
                                        modifier = Modifier.testTag(dest.testTag)
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (isExpandedScreen) {
                            NavigationRail(modifier = Modifier.testTag("side_navigation_rail")) {
                                MasroofiDestination.entries.forEach { dest ->
                                    val selected = currentDestination == dest
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { currentDestination = dest },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = dest.arabicTitle
                                            )
                                        },
                                        label = { Text(dest.arabicTitle) },
                                        modifier = Modifier.testTag(dest.testTag)
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            when (currentDestination) {
                                MasroofiDestination.HOME -> {
                                    HomeScreen(
                                        uiState = uiState,
                                        onOpenManualEntry = { type ->
                                            editingTransaction = null
                                            manualDialogInitialType = type
                                            showManualDialog = true
                                        },
                                        onOpenAiEntry = { showAiEntryScreen = true },
                                        onOpenReports = { currentDestination = MasroofiDestination.REPORTS },
                                        onOpenAllTransactions = { currentDestination = MasroofiDestination.TRANSACTIONS },
                                        onEditTransaction = { tx ->
                                            editingTransaction = tx
                                            manualDialogInitialType = tx.type
                                            showManualDialog = true
                                        },
                                        onDeleteTransaction = { tx ->
                                            transactionPendingDelete = tx
                                        },
                                        onSaveOpeningBalance = viewModel::saveOpeningBalance,
                                        onSaveMonthlyBudget = { amount ->
                                            viewModel.saveMonthlyBudget(
                                                yearMonth = uiState.selectedHomeYearMonth,
                                                categoryId = null,
                                                amountText = amount
                                            )
                                        },
                                        onChangeMonth = viewModel::setHomeYearMonth
                                    )
                                }

                                MasroofiDestination.TRANSACTIONS -> {
                                    TransactionsScreen(
                                        uiState = uiState,
                                        onUpdateFilter = viewModel::updateHistoryFilter,
                                        onClearFilters = viewModel::clearHistoryFilters,
                                        onOpenManualEntry = { type ->
                                            editingTransaction = null
                                            manualDialogInitialType = type
                                            showManualDialog = true
                                        },
                                        onOpenAiEntry = { showAiEntryScreen = true },
                                        onEditTransaction = { tx ->
                                            editingTransaction = tx
                                            manualDialogInitialType = tx.type
                                            showManualDialog = true
                                        },
                                        onDeleteTransaction = { tx ->
                                            transactionPendingDelete = tx
                                        }
                                    )
                                }

                                MasroofiDestination.REPORTS -> {
                                    ReportsScreen(
                                        uiState = uiState,
                                        onSelectPeriodFilter = viewModel::setReportPeriodFilter,
                                        onSelectCategoryDrilldown = viewModel::selectReportCategoryDrilldown,
                                        onRequestSmartSummary = viewModel::generateSmartSummaryForCurrentReport,
                                        onEditTransaction = { tx ->
                                            editingTransaction = tx
                                            manualDialogInitialType = tx.type
                                            showManualDialog = true
                                        },
                                        onDeleteTransaction = { tx ->
                                            transactionPendingDelete = tx
                                        }
                                    )
                                }

                                MasroofiDestination.SETTINGS -> {
                                    SettingsAndCategoriesScreen(
                                        uiState = uiState,
                                        onUpdateCurrency = viewModel::updateCurrency,
                                        onUpdateTheme = viewModel::updateThemePreference,
                                        onUpdateRefundMode = viewModel::updateRefundAccountingMode,
                                        onSaveOpeningBalance = viewModel::saveOpeningBalance,
                                        onSaveMonthlyBudget = viewModel::saveMonthlyBudget,
                                        onDeleteBudget = viewModel::deleteBudget,
                                        onSaveCategory = viewModel::saveCategory,
                                        onToggleArchiveCategory = viewModel::toggleCategoryArchive,
                                        onDeleteCategory = viewModel::deleteCategory,
                                        onSaveAiSettings = viewModel::saveAiSettings,
                                        onRemoveApiKey = viewModel::removeApiKey,
                                        onTestAiConnection = viewModel::testAiConnection,
                                        onCreateBackupJson = { viewModel.createBackupJsonString() },
                                        onValidateRestoreJson = viewModel::validateBackupFileContent,
                                        onDismissBackupPreview = viewModel::dismissBackupPreview,
                                        onConfirmRestoreBackup = viewModel::confirmRestoreBackup,
                                        onExportCsv = { viewModel.exportCsvString() },
                                        onExportBackupToUri = { uri ->
                                            viewModel.exportBackupToUri(context.contentResolver, uri)
                                        },
                                        onImportBackupFromUri = { uri ->
                                            viewModel.importBackupFromUri(context.contentResolver, uri)
                                        },
                                        onExportCsvToUri = { uri ->
                                            viewModel.exportCsvToUri(context.contentResolver, uri)
                                        },
                                        onShowMessage = viewModel::showUserMessage
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showManualDialog) {
            val activeCurrencySymbol = editingTransaction?.let {
                SupportedCurrencies.fromCode(it.currencyCode).symbol
            } ?: uiState.preferences.currencySymbol
            ManualTransactionDialog(
                initialType = manualDialogInitialType,
                existingTransaction = editingTransaction,
                categories = uiState.categories,
                currencySymbol = activeCurrencySymbol,
                onDismiss = {
                    showManualDialog = false
                    editingTransaction = null
                },
                onSave = { existingId, desc, amount, type, catId, isoDate, notes, isRefundOffset ->
                    viewModel.saveManualTransaction(
                        existingId = existingId,
                        description = desc,
                        amountText = amount,
                        type = type,
                        categoryId = catId,
                        isoDate = isoDate,
                        notes = notes,
                        isRefundOffset = isRefundOffset,
                        onSuccess = {
                            showManualDialog = false
                            editingTransaction = null
                        }
                    )
                }
            )
        }

        if (transactionPendingDelete != null) {
            val tx = transactionPendingDelete!!
            ConfirmDeleteDialog(
                title = "حذف المعاملة",
                message = "هل أنت متأكد من حذف معاملة (${tx.description})؟ سيتم تحديث الرصيد والتقارير فوراً.",
                onConfirm = {
                    viewModel.deleteTransaction(tx.id)
                    transactionPendingDelete = null
                },
                onDismiss = { transactionPendingDelete = null }
            )
        }
    }
}
