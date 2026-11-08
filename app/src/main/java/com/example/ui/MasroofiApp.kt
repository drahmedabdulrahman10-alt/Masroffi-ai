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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
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
import com.example.ui.viewmodel.AiEntryViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.ReportsViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.TransactionsViewModel

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
        selectedIcon = Icons.AutoMirrored.Filled.ReceiptLong,
        unselectedIcon = Icons.AutoMirrored.Outlined.ReceiptLong,
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
    homeViewModel: HomeViewModel,
    transactionsViewModel: TransactionsViewModel,
    aiEntryViewModel: AiEntryViewModel,
    reportsViewModel: ReportsViewModel,
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val transactionsUiState by transactionsViewModel.uiState.collectAsStateWithLifecycle()
    val aiEntryUiState by aiEntryViewModel.uiState.collectAsStateWithLifecycle()
    val reportsUiState by reportsViewModel.uiState.collectAsStateWithLifecycle()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    val systemDark = isSystemInDarkTheme()
    val isDark = when (homeUiState.preferences.themePreference) {
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
        val activeSnackbarMessage = homeUiState.snackbarMessage
            ?: transactionsUiState.snackbarMessage
            ?: aiEntryUiState.snackbarMessage
            ?: reportsUiState.snackbarMessage
            ?: settingsUiState.snackbarMessage

        LaunchedEffect(activeSnackbarMessage) {
            val msg = activeSnackbarMessage
            if (!msg.isNullOrBlank()) {
                snackbarHostState.showSnackbar(msg)
                homeViewModel.clearSnackbar()
                transactionsViewModel.clearSnackbar()
                aiEntryViewModel.clearSnackbar()
                reportsViewModel.clearSnackbar()
                settingsViewModel.clearSnackbar()
            }
        }

        if (currentDestination != MasroofiDestination.HOME && !showAiEntryScreen) {
            BackHandler {
                currentDestination = MasroofiDestination.HOME
            }
        }

        if (showAiEntryScreen) {
            AiEntrySheetScreen(
                uiState = aiEntryUiState,
                onClose = { showAiEntryScreen = false },
                onUpdateInputText = aiEntryViewModel::updateAiInputText,
                onAppendSpeechText = aiEntryViewModel::appendRecognizedSpeech,
                onAnalyzeInput = aiEntryViewModel::analyzeAiInput,
                onUpdateDraft = aiEntryViewModel::updateProposedDraft,
                onRemoveDraft = aiEntryViewModel::removeProposedDraft,
                onCancelReview = aiEntryViewModel::cancelAiReview,
                onSaveAllReviewed = {
                    aiEntryViewModel.saveAllReviewedProposals(
                        onSavedSuccess = { showAiEntryScreen = false }
                    )
                },
                onSelectBatchDate = aiEntryViewModel::selectAiTransactionDate
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
                                        uiState = homeUiState,
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
                                        onSaveOpeningBalance = homeViewModel::saveOpeningBalance,
                                        onSaveMonthlyBudget = { amount ->
                                            homeViewModel.saveMonthlyBudget(
                                                yearMonth = homeUiState.selectedHomeYearMonth,
                                                categoryId = null,
                                                amountText = amount
                                            )
                                        },
                                        onChangeMonth = homeViewModel::setHomeYearMonth
                                    )
                                }

                                MasroofiDestination.TRANSACTIONS -> {
                                    TransactionsScreen(
                                        uiState = transactionsUiState,
                                        onUpdateFilter = transactionsViewModel::updateHistoryFilter,
                                        onClearFilters = transactionsViewModel::clearHistoryFilters,
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
                                        uiState = reportsUiState,
                                        onSelectPeriodFilter = reportsViewModel::setReportPeriodFilter,
                                        onSelectCategoryDrilldown = reportsViewModel::selectReportCategoryDrilldown,
                                        onRequestSmartSummary = reportsViewModel::generateSmartSummaryForCurrentReport,
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
                                        uiState = settingsUiState,
                                        onUpdateCurrency = settingsViewModel::updateCurrency,
                                        onUpdateTheme = settingsViewModel::updateThemePreference,
                                        onUpdateRefundMode = settingsViewModel::updateRefundAccountingMode,
                                        onSaveOpeningBalance = settingsViewModel::saveOpeningBalance,
                                        onSaveMonthlyBudget = settingsViewModel::saveMonthlyBudget,
                                        onDeleteBudget = settingsViewModel::deleteBudget,
                                        onSaveCategory = settingsViewModel::saveCategory,
                                        onToggleArchiveCategory = settingsViewModel::toggleCategoryArchive,
                                        onDeleteCategory = settingsViewModel::deleteCategory,
                                        onSaveAiSettings = settingsViewModel::saveAiSettings,
                                        onRemoveApiKey = settingsViewModel::removeApiKey,
                                        onTestAiConnection = settingsViewModel::testAiConnection,
                                        onCreateBackupJson = { settingsViewModel.createBackupJsonString() },
                                        onValidateRestoreJson = settingsViewModel::validateBackupFileContent,
                                        onDismissBackupPreview = settingsViewModel::dismissBackupPreview,
                                        onConfirmRestoreBackup = settingsViewModel::confirmRestoreBackup,
                                        onExportCsv = { settingsViewModel.exportCsvString() },
                                        onExportBackupToUri = { uri ->
                                            settingsViewModel.exportBackupToUri(context.contentResolver, uri)
                                        },
                                        onImportBackupFromUri = { uri ->
                                            settingsViewModel.importBackupFromUri(context.contentResolver, uri)
                                        },
                                        onExportCsvToUri = { uri ->
                                            settingsViewModel.exportCsvToUri(context.contentResolver, uri)
                                        },
                                        onShowMessage = settingsViewModel::showUserMessage
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
            } ?: transactionsUiState.preferences.currencySymbol
            ManualTransactionDialog(
                initialType = manualDialogInitialType,
                existingTransaction = editingTransaction,
                categories = transactionsUiState.categories,
                currencySymbol = activeCurrencySymbol,
                onDismiss = {
                    showManualDialog = false
                    editingTransaction = null
                },
                onSave = { existingId, desc, amount, type, catId, isoDate, notes, isRefundOffset ->
                    transactionsViewModel.saveManualTransaction(
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
                    transactionsViewModel.deleteTransaction(tx.id)
                    transactionPendingDelete = null
                },
                onDismiss = { transactionPendingDelete = null }
            )
        }
    }
}
