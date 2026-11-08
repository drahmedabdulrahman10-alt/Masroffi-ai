package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.local.CategoryEntity
import com.example.data.local.RefundAccountingMode
import com.example.data.local.TransactionType
import com.example.data.preferences.AiProviderType
import com.example.data.preferences.SupportedCurrencies
import com.example.data.preferences.ThemePreference
import com.example.data.repository.RestoreMode
import com.example.domain.MoneyUtils
import com.example.ui.components.CategoryIconHelper
import com.example.ui.viewmodel.SettingsUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAndCategoriesScreen(
    uiState: SettingsUiState,
    onUpdateCurrency: (String) -> Unit,
    onUpdateTheme: (ThemePreference) -> Unit,
    onUpdateRefundMode: (RefundAccountingMode) -> Unit = {},
    onSaveOpeningBalance: (String) -> Unit,
    onSaveMonthlyBudget: (String, String?, String) -> Unit,
    onDeleteBudget: (String) -> Unit,
    onSaveCategory: (String?, String, TransactionType, String, Long, Boolean) -> Unit,
    onToggleArchiveCategory: (CategoryEntity) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onSaveAiSettings: (Boolean, AiProviderType, String, String, String, Boolean, Float, Boolean) -> Unit,
    onRemoveApiKey: (AiProviderType) -> Unit,
    onTestAiConnection: (AiProviderType, String, String) -> Unit,
    onCreateBackupJson: suspend () -> String = { "" },
    onValidateRestoreJson: (String) -> Unit,
    onDismissBackupPreview: () -> Unit,
    onConfirmRestoreBackup: (RestoreMode) -> Unit,
    onExportCsv: suspend () -> String = { "" },
    onExportBackupToUri: (Uri) -> Unit = {},
    onImportBackupFromUri: (Uri) -> Unit = {},
    onExportCsvToUri: (Uri) -> Unit = {},
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = uiState.preferences
    val currency = prefs.currencySymbol

    // AI Settings Local Form State
    var aiEnabled by rememberSaveable(prefs.aiEnabled) { mutableStateOf(prefs.aiEnabled) }
    var selectedProvider by rememberSaveable(prefs.selectedProvider) { mutableStateOf(prefs.selectedProvider) }
    var geminiModel by rememberSaveable(prefs.geminiModelId) { mutableStateOf(prefs.geminiModelId) }
    var openRouterModel by rememberSaveable(prefs.openRouterModelId) { mutableStateOf(prefs.openRouterModelId) }
    var apiKeyInput by rememberSaveable { mutableStateOf("") }
    var showApiKeyPlain by rememberSaveable { mutableStateOf(false) }
    var autoSaveEnabled by rememberSaveable(prefs.autoSaveEnabled) { mutableStateOf(prefs.autoSaveEnabled) }
    var autoSaveConfidence by rememberSaveable(prefs.autoSaveConfidenceThreshold) {
        mutableFloatStateOf(prefs.autoSaveConfidenceThreshold)
    }
    var aiSummaryEnabled by rememberSaveable(prefs.aiSummaryEnabled) { mutableStateOf(prefs.aiSummaryEnabled) }

    // Category Modal State
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var showCategoryModal by rememberSaveable { mutableStateOf(false) }
    var categoryToDelete by remember { mutableStateOf<CategoryEntity?>(null) }

    // Category Budget Modal State
    var showCategoryBudgetModal by rememberSaveable { mutableStateOf(false) }

    // Totals by Category from saved transactions in the active currency only (never mixes currencies)
    val categoryTotalsMap = remember(uiState.allTransactions, prefs.currencyCode) {
        uiState.allTransactions
            .filter { it.currencyCode.equals(prefs.currencyCode, ignoreCase = true) }
            .groupBy { it.categoryId }
            .mapValues { (_, list) -> list.sumOf { it.amountMinorUnits } }
    }

    // SAF Launchers for Backup & CSV Export (executed safely inside ViewModel scope)
    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            onExportBackupToUri(uri)
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportBackupFromUri(uri)
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            onExportCsvToUri(uri)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "الإعدادات وإدارة البيانات",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "التحكم في التصنيفات، مزودي الذكاء الاصطناعي، الميزانيات، والنسخ الاحتياطي",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 1. User-Controlled Categories Management Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("categories_management_section")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "إدارة التصنيفات (${uiState.categories.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "أنت فقط من يملك صلاحية إضافة أو تعديل أو أرشفة التصنيفات",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                editingCategory = null
                                showCategoryModal = true
                            },
                            modifier = Modifier.testTag("add_category_button")
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تصنيف جديد")
                        }
                    }

                    uiState.categories.forEach { cat ->
                        val catTotal = categoryTotalsMap[cat.id] ?: 0L
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = if (cat.isArchived) 0.25f else 0.5f
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(cat.colorHex).copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = CategoryIconHelper.getIcon(cat.iconName),
                                        contentDescription = cat.name,
                                        tint = Color(cat.colorHex),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = cat.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (cat.type == TransactionType.INCOME) "(دخل)" else "(مصروف)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (cat.isArchived) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "[مؤرشف]",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    Text(
                                        text = "الإجمالي المسجل: ${MoneyUtils.formatMinorUnits(catTotal, currency)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        editingCategory = cat
                                        showCategoryModal = true
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "تعديل التصنيف",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onToggleArchiveCategory(cat) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = if (cat.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                        contentDescription = if (cat.isArchived) "استعادة التصنيف" else "أرشفة التصنيف",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { categoryToDelete = cat },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "حذف التصنيف",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. AI Provider Configuration Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_provider_settings_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "إعدادات الذكاء الاصطناعي (AI)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "دعم Google Gemini و OpenRouter مع تشفير Android Keystore",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = aiEnabled,
                            onCheckedChange = { aiEnabled = it },
                            modifier = Modifier.testTag("ai_enabled_switch")
                        )
                    }

                    HorizontalDivider()

                    Text(
                        text = "اختر مزود الخدمة:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedProvider == AiProviderType.GEMINI,
                            onClick = { selectedProvider = AiProviderType.GEMINI },
                            label = { Text("Google Gemini API") },
                            modifier = Modifier.testTag("provider_chip_gemini")
                        )
                        FilterChip(
                            selected = selectedProvider == AiProviderType.OPENROUTER,
                            onClick = { selectedProvider = AiProviderType.OPENROUTER },
                            label = { Text("OpenRouter API") },
                            modifier = Modifier.testTag("provider_chip_openrouter")
                        )
                    }

                    val currentMaskedKey = if (selectedProvider == AiProviderType.GEMINI) {
                        uiState.maskedGeminiKey
                    } else {
                        uiState.maskedOpenRouterKey
                    }
                    val hasSavedKey = if (selectedProvider == AiProviderType.GEMINI) {
                        uiState.hasGeminiKey
                    } else {
                        uiState.hasOpenRouterKey
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "المفتاح المحفوظ حالياً: $currentMaskedKey",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (hasSavedKey) {
                            TextButton(
                                onClick = { onRemoveApiKey(selectedProvider) },
                                modifier = Modifier.testTag("remove_api_key_btn")
                            ) {
                                Text("حذف المفتاح", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = {
                            Text(
                                if (selectedProvider == AiProviderType.GEMINI) {
                                    "مفتاح Gemini API الجديد (اختياري للتحديث)"
                                } else {
                                    "مفتاح OpenRouter API الجديد (اختياري للتحديث)"
                                }
                            )
                        },
                        visualTransformation = if (showApiKeyPlain) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showApiKeyPlain = !showApiKeyPlain }) {
                                Icon(
                                    imageVector = if (showApiKeyPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKeyPlain) "إخفاء المفتاح" else "إظهار المفتاح"
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("api_key_input_field")
                    )

                    if (selectedProvider == AiProviderType.GEMINI) {
                        OutlinedTextField(
                            value = geminiModel,
                            onValueChange = { geminiModel = it },
                            label = { Text("معرّف نموذج Gemini (مثال: gemini-2.5-flash)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("gemini_model_input")
                        )
                    } else {
                        OutlinedTextField(
                            value = openRouterModel,
                            onValueChange = { openRouterModel = it },
                            label = { Text("معرّف نموذج OpenRouter (مثال: google/gemini-2.5-flash)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("openrouter_model_input")
                        )
                    }

                    // Auto-Save Settings
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "الحفظ التلقائي (Auto-Save)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "مغلق افتراضياً — يحفظ تلقائياً فقط المعاملات المكتملة الواضحة التي تتجاوز درجة الثقة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoSaveEnabled,
                            onCheckedChange = { autoSaveEnabled = it },
                            modifier = Modifier.testTag("auto_save_switch")
                        )
                    }

                    if (autoSaveEnabled) {
                        Column {
                            Text(
                                text = "الحد الأدنى لدرجة الثقة للحفظ التلقائي: ${(autoSaveConfidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Slider(
                                value = autoSaveConfidence,
                                onValueChange = { autoSaveConfidence = it },
                                valueRange = 0.70f..0.99f
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "تفعيل ميزة (ملخص ذكي) في شاشة التقارير",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = aiSummaryEnabled,
                            onCheckedChange = { aiSummaryEnabled = it }
                        )
                    }

                    if (uiState.connectionTestMessage != null) {
                        Surface(
                            color = if (uiState.connectionTestSuccess == true) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connection_test_result_banner")
                        ) {
                            Text(
                                text = uiState.connectionTestMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.connectionTestSuccess == true) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val model = if (selectedProvider == AiProviderType.GEMINI) geminiModel else openRouterModel
                                onTestAiConnection(selectedProvider, apiKeyInput, model)
                            },
                            enabled = !uiState.isTestingConnection,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("test_ai_connection_btn")
                        ) {
                            if (uiState.isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("اختبار الاتصال")
                        }

                        Button(
                            onClick = {
                                onSaveAiSettings(
                                    aiEnabled,
                                    selectedProvider,
                                    geminiModel,
                                    openRouterModel,
                                    apiKeyInput,
                                    autoSaveEnabled,
                                    autoSaveConfidence,
                                    aiSummaryEnabled
                                )
                                apiKeyInput = ""
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_ai_settings_btn")
                        ) {
                            Text("حفظ الإعدادات")
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ملاحظة أمان: يتم تشفير مفتاح API الخاص بك محلياً عبر Android Keystore ولا يُضمّن أبداً في ملفات النسخ الاحتياطي، ولكن تخزين المفاتيح على جهاز العميل لا يحميها بالكامل في حال تعرض الجهاز نفسه للاختراق أو كسر الحماية (Root).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 3. Currency, Theme, and Category Budgets Section
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
                        text = "العملة والمظهر والميزانيات الفرعية",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text("العملة الافتراضية:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SupportedCurrencies.all.forEach { curr ->
                            FilterChip(
                                selected = prefs.currencyCode == curr.code,
                                onClick = { onUpdateCurrency(curr.code) },
                                label = { Text(curr.arabicName) },
                                modifier = Modifier.testTag("currency_chip_${curr.code}")
                            )
                        }
                    }

                    Text("المظهر (الوضع الليلي/النهاري):", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = prefs.themePreference == ThemePreference.SYSTEM,
                            onClick = { onUpdateTheme(ThemePreference.SYSTEM) },
                            label = { Text("تلقائي (النظام)") }
                        )
                        FilterChip(
                            selected = prefs.themePreference == ThemePreference.LIGHT,
                            onClick = { onUpdateTheme(ThemePreference.LIGHT) },
                            label = { Text("فاتح") }
                        )
                        FilterChip(
                            selected = prefs.themePreference == ThemePreference.DARK,
                            onClick = { onUpdateTheme(ThemePreference.DARK) },
                            label = { Text("داكن") }
                        )
                    }

                    Text("طريقة محاسبة مبالغ الاسترداد (Refunds):", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = prefs.refundAccountingMode == RefundAccountingMode.EXPENSE_OFFSET,
                            onClick = { onUpdateRefundMode(RefundAccountingMode.EXPENSE_OFFSET) },
                            label = { Text("خصم من المصروف الأصلي") },
                            modifier = Modifier.testTag("refund_mode_expense_offset")
                        )
                        FilterChip(
                            selected = prefs.refundAccountingMode == RefundAccountingMode.INCOME_CATEGORY,
                            onClick = { onUpdateRefundMode(RefundAccountingMode.INCOME_CATEGORY) },
                            label = { Text("إضافة كدخل مستقل") },
                            modifier = Modifier.testTag("refund_mode_income_category")
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ميزانيات التصنيفات الفرعية (${uiState.selectedHomeYearMonth})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        FilledTonalButton(onClick = { showCategoryBudgetModal = true }) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تخصيص ميزانية تصنيف")
                        }
                    }
                }
            }
        }

        // 4. Backup, Restore, and CSV Export Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup_export_section")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "النسخ الاحتياطي المحلي وتصدير البيانات",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "تتضمن النسخة الاحتياطية (JSON) معاملاتك وتصنيفاتك وميزانياتك وإعداداتك المالية، ولا تتضمن مطلقاً مفاتيح API.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val fileName = "masroofi_backup_${MoneyUtils.todayIsoDate()}.json"
                                createBackupLauncher.launch(fileName)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_backup_json_btn")
                        ) {
                            Icon(imageVector = Icons.Default.FileDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تصدير نسخة JSON")
                        }

                        OutlinedButton(
                            onClick = {
                                openBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_backup_json_btn")
                        ) {
                            Icon(imageVector = Icons.Default.FileUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("استعادة نسخة JSON")
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            val fileName = "masroofi_transactions_${MoneyUtils.todayIsoDate()}.csv"
                            exportCsvLauncher.launch(fileName)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_csv_btn")
                    ) {
                        Icon(imageVector = Icons.Default.TableView, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تصدير جميع المعاملات إلى ملف جدول بيانات (CSV)")
                    }
                }
            }
        }
    }

    // Category Add/Edit Dialog
    if (showCategoryModal) {
        CategoryEditDialog(
            existingCategory = editingCategory,
            onDismiss = { showCategoryModal = false },
            onSave = { id, name, type, icon, color, archived ->
                onSaveCategory(id, name, type, icon, color, archived)
                showCategoryModal = false
            }
        )
    }

    // Category Delete Confirmation Dialog
    if (categoryToDelete != null) {
        val cat = categoryToDelete!!
        AlertDialog(
            onDismissRequest = { categoryToDelete = null },
            title = { Text("تأكيد حذف التصنيف (${cat.name})") },
            text = {
                Text(
                    "هل أنت متأكد من حذف هذا التصنيف؟ لن يتم حذف أي معاملة مالية سابقة؛ سيتم تحويل المعاملات المرتبطة به إلى (غير مصنف) للحفاظ على دقة سجلاتك."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCategory(cat.id)
                        categoryToDelete = null
                    }
                ) {
                    Text("حذف بأمان")
                }
            },
            dismissButton = {
                TextButton(onClick = { categoryToDelete = null }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Category Budget Dialog
    if (showCategoryBudgetModal) {
        val expenseCats = remember(uiState.categories) {
            uiState.categories.filter { !it.isArchived && it.type == TransactionType.EXPENSE }
        }
        var selectedCatId by rememberSaveable {
            mutableStateOf(expenseCats.firstOrNull()?.id)
        }
        var amountInput by rememberSaveable { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCategoryBudgetModal = false },
            title = { Text("تخصيص ميزانية لتصنيف (${uiState.selectedHomeYearMonth})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اختر التصنيف:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        expenseCats.forEach { c ->
                            FilterChip(
                                selected = selectedCatId == c.id,
                                onClick = { selectedCatId = c.id },
                                label = { Text(c.name) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text("حد الميزانية الشهرية ($currency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedCatId != null) {
                            onSaveMonthlyBudget(uiState.selectedHomeYearMonth, selectedCatId, amountInput)
                            showCategoryBudgetModal = false
                        }
                    }
                ) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCategoryBudgetModal = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Backup Validation & Restore Preview Dialog
    val backupPreview = uiState.pendingBackupPreview
    if (backupPreview != null && backupPreview.isValid) {
        AlertDialog(
            onDismissRequest = onDismissBackupPreview,
            title = { Text("معاينة استعادة النسخة الاحتياطية") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "تم التحقق من سلامة ملف النسخة الاحتياطية بنجاح:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("• عدد التصنيفات: ${backupPreview.categoriesCount}")
                    Text("• عدد المعاملات: ${backupPreview.transactionsCount}")
                    Text("• عدد الميزانيات: ${backupPreview.budgetsCount}")
                    Text("• العملة: ${backupPreview.currencyCode}")
                    HorizontalDivider()
                    Text(
                        text = "كيف ترغب في تطبيق هذه النسخة على سجلاتك الحالية؟\n- (دمج): يضيف السجلات ويحدّث المتطابق منها دون مسح معاملاتك الأخرى.\n- (استبدال الكل): يمسح السجلات الحالية بالكامل ويستبدلها ببيانات النسخة الاحتياطية.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = { onConfirmRestoreBackup(RestoreMode.REPLACE_ALL) },
                        modifier = Modifier.testTag("restore_replace_all_btn")
                    ) {
                        Text("استبدال الكل")
                    }
                    Button(
                        onClick = { onConfirmRestoreBackup(RestoreMode.MERGE_BY_ID) },
                        modifier = Modifier.testTag("restore_merge_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Restore, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("دمج آمن")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBackupPreview) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditDialog(
    existingCategory: CategoryEntity?,
    onDismiss: () -> Unit,
    onSave: (
        existingId: String?,
        name: String,
        type: TransactionType,
        iconName: String,
        colorHex: Long,
        isArchived: Boolean
    ) -> Unit
) {
    val categoryKey = existingCategory?.id ?: "new_category"
    var name by rememberSaveable(categoryKey) { mutableStateOf(existingCategory?.name ?: "") }
    var type by rememberSaveable(categoryKey) {
        mutableStateOf(existingCategory?.type ?: TransactionType.EXPENSE)
    }
    var iconName by rememberSaveable(categoryKey) {
        mutableStateOf(existingCategory?.iconName ?: "category")
    }
    var colorHex by rememberSaveable(categoryKey) {
        mutableLongStateOf(existingCategory?.colorHex ?: 0xFF0F766EL)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (existingCategory != null) "تعديل التصنيف" else "إضافة تصنيف جديد")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم التصنيف") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("category_name_input")
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TransactionType.EXPENSE,
                        onClick = { type = TransactionType.EXPENSE },
                        label = { Text("مصروف") }
                    )
                    FilterChip(
                        selected = type == TransactionType.INCOME,
                        onClick = { type = TransactionType.INCOME },
                        label = { Text("دخل") }
                    )
                }

                Text("الأيقونة:", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CategoryIconHelper.availableIcons.forEach { (key, label) ->
                        val isSelected = iconName == key
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { iconName = key },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = CategoryIconHelper.getIcon(key),
                                contentDescription = label,
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text("اللون:", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CategoryIconHelper.availableColors.forEach { hex ->
                        val isSelected = colorHex == hex
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (isSelected) 3.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { colorHex = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        existingCategory?.id,
                        name,
                        type,
                        iconName,
                        colorHex,
                        existingCategory?.isArchived ?: false
                    )
                },
                modifier = Modifier.testTag("save_category_confirm_btn")
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
