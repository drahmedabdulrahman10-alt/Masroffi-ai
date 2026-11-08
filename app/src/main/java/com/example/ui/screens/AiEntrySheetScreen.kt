package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.local.CategoryEntity
import com.example.data.local.TransactionType
import com.example.data.preferences.SupportedCurrencies
import com.example.domain.MoneyUtils
import com.example.domain.ai.ProposedTransactionDraft
import com.example.ui.components.TransactionDateSelectorSection
import com.example.ui.speech.MasroofiSpeechHelper
import com.example.ui.speech.SpeechRecognitionState
import com.example.ui.theme.ExpenseRedLight
import com.example.ui.theme.IncomeGreenLight
import com.example.ui.theme.WarningAmberLight
import com.example.ui.viewmodel.AiEntryUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiEntrySheetScreen(
    uiState: AiEntryUiState,
    onClose: () -> Unit,
    onUpdateInputText: (String) -> Unit,
    onAppendSpeechText: (String) -> Unit,
    onAnalyzeInput: (Boolean) -> Unit,
    onUpdateDraft: (ProposedTransactionDraft) -> Unit,
    onRemoveDraft: (String) -> Unit,
    onCancelReview: () -> Unit,
    onSaveAllReviewed: () -> Unit,
    onSelectBatchDate: (String) -> Unit = {}
) {
    BackHandler { onClose() }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnAppendSpeechText by rememberUpdatedState(onAppendSpeechText)
    var speechState by remember { mutableStateOf(SpeechRecognitionState()) }

    val speechHelper = remember(context) {
        MasroofiSpeechHelper(
            context = context,
            onStateChanged = { speechState = it },
            onFinalTextRecognized = { recognized ->
                currentOnAppendSpeechText(recognized)
            }
        )
    }

    DisposableEffect(lifecycleOwner, speechHelper) {
        lifecycleOwner.lifecycle.addObserver(speechHelper)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(speechHelper)
            speechHelper.stopListening()
        }
    }

    val systemRecognizerIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val top = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (top.isNotEmpty()) {
                onAppendSpeechText(top)
            } else {
                speechState = speechState.copy(errorMessage = "لم يتم التقاط نص من الإملاء الصوتي.")
            }
        }
    }

    val startVoiceInput = {
        if (speechHelper.isAvailable()) {
            speechHelper.startListening("ar-EG")
        } else {
            try {
                systemRecognizerIntentLauncher.launch(speechHelper.buildFallbackRecognizerIntent("ar-EG"))
            } catch (e: Exception) {
                speechState = speechState.copy(
                    errorMessage = "خدمة التعرف على الصوت غير مدعومة أو غير مثبتة على هذا الجهاز."
                )
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceInput()
        } else {
            speechState = speechState.copy(
                errorMessage = "تم رفض إذن الميكروفون. يمكنك كتابة المعاملات نصياً أو منح الإذن عند الحاجة."
            )
        }
    }

    val samplePrompts = listOf(
        "اشتريت طماطم بعشرين جنيه وعلبة جبنة بخمسة وأربعين جنيه ودفعت تاكسي عشرين جنيه",
        "طماطم 20\nجبنة 45\nتاكسي 20\nقهوة 25",
        "قبضت 8000 جنيه مرتب ودفعت إيجار 2000 جنيه"
    )

    val activeCategories = remember(uiState.categories) {
        uiState.categories.filter { !it.isArchived }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "الإدخال الذكي للمعاملات المتعددة",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "بالعامية المصرية أو العربية أو الإنجليزية (نص أو صوت)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("ai_entry_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("ai_entry_screen_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Privacy & Provider Transparency Notice
            item {
                val hasRemoteKey = (uiState.preferences.selectedProvider.name == "GEMINI" && uiState.hasGeminiKey) ||
                    (uiState.preferences.selectedProvider.name == "OPENROUTER" && uiState.hasOpenRouterKey)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (hasRemoteKey && uiState.preferences.aiEnabled) {
                                "تنبيه الخصوصية: عند الضغط على (تحليل)، سيتم إرسال النص المدخل وأسماء تصنيفاتك الحالية فقط إلى المزود (${uiState.preferences.selectedProvider.name}) دون إرسال سجل معاملاتك السابق."
                            } else {
                                "الوضع المحلي النشط: يعمل محلل مصروفي الذكي محلياً على جهازك دون إرسال أي بيانات للخارج. يمكنك إضافة مفتاح Gemini أو OpenRouter في الإعدادات متى شئت."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Input Card with Speech-to-Text
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.aiInputText,
                            onValueChange = onUpdateInputText,
                            label = {
                                Text("اكتب أو انطق معاملاتك (جملة واحدة أو عدة أسطر)")
                            },
                            placeholder = {
                                Text("مثال: اشتريت طماطم بعشرين جنيه وعلبة جبنة بخمسة وأربعين جنيه ودفعت تاكسي عشرين جنيه")
                            },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_input_textfield")
                        )

                        // Speech Recognition Active Banner
                        if (speechState.isListening) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("speech_listening_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (speechState.partialText.isNotBlank()) {
                                                "جاري الاستماع: ${speechState.partialText}"
                                            } else {
                                                "جاري الاستماع... تحدث الآن باللهجة المصرية أو العربية"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    IconButton(onClick = { speechHelper.stopListening() }) {
                                        Icon(
                                            imageVector = Icons.Default.StopCircle,
                                            contentDescription = "إيقاف التسجيل",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        if (speechState.errorMessage != null) {
                            Text(
                                text = speechState.errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("speech_error_text")
                            )
                        }

                        Text(
                            text = "ملاحظة: يعتمد الإملاء الصوتي على خدمات التعرف الصوتي للنظام في أندرويد ولا يُرسل تسجيلاً صوتياً خاماً لمزود الذكاء الاصطناعي.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Quick sample chips for convenience
                        Text(
                            text = "أمثلة سريعة للتجربة:",
                            style = MaterialTheme.typography.labelMedium
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            samplePrompts.forEachIndexed { idx, sample ->
                                AssistChip(
                                    onClick = { onUpdateInputText(sample) },
                                    label = {
                                        Text(
                                            text = when (idx) {
                                                0 -> "مثال 1: طماطم وجبنة وتاكسي"
                                                1 -> "مثال 2: عدة أسطر سريعة"
                                                else -> "مثال 3: مرتب وإيجار"
                                            },
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag("ai_sample_chip_$idx")
                                )
                            }
                        }

                        TransactionDateSelectorSection(
                            selectedIsoDate = uiState.selectedTransactionIsoDate,
                            onDateSelected = onSelectBatchDate,
                            tagPrefix = "ai_batch",
                            showTextField = false
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    if (speechState.isListening) {
                                        speechHelper.stopListening()
                                    } else {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            startVoiceInput()
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("ai_mic_button")
                            ) {
                                Icon(
                                    imageVector = if (speechState.isListening) Icons.Default.StopCircle else Icons.Default.Mic,
                                    contentDescription = "إدخال صوتي بالميكروفون"
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (speechState.isListening) "إيقاف" else "صوت")
                            }

                            Button(
                                onClick = { onAnalyzeInput(false) },
                                enabled = !uiState.isAiParsing,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("ai_analyze_button")
                            ) {
                                if (uiState.isAiParsing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("جاري التحليل...")
                                } else {
                                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("تحليل المعاملات")
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.aiErrorMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_error_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.aiErrorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Transaction Preview / Review Section
            val parseResult = uiState.aiParseResult
            if (parseResult != null && parseResult.proposals.isNotEmpty()) {
                val expenseTotalsByCurrency = parseResult.proposals
                    .filter { it.type == TransactionType.EXPENSE }
                    .groupBy { it.currencyCode }
                    .mapValues { (_, list) -> list.sumOf { it.amountMinorUnits ?: 0L } }
                    .filterValues { it > 0L }

                val incomeTotalsByCurrency = parseResult.proposals
                    .filter { it.type == TransactionType.INCOME }
                    .groupBy { it.currencyCode }
                    .mapValues { (_, list) -> list.sumOf { it.amountMinorUnits ?: 0L } }
                    .filterValues { it > 0L }

                val allValid = parseResult.proposals.all { it.isValidToSave }

                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_preview_summary_header")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "مراجعة المعاملات المقترحة (${parseResult.proposals.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (expenseTotalsByCurrency.isNotEmpty()) {
                                    val formattedExpenses = expenseTotalsByCurrency.entries.joinToString(" + ") { (code, minor) ->
                                        MoneyUtils.formatMinorUnits(minor, SupportedCurrencies.fromCode(code).symbol)
                                    }
                                    Text(
                                        text = "إجمالي المصروفات: $formattedExpenses",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = ExpenseRedLight
                                    )
                                }
                                if (incomeTotalsByCurrency.isNotEmpty()) {
                                    val formattedIncome = incomeTotalsByCurrency.entries.joinToString(" + ") { (code, minor) ->
                                        MoneyUtils.formatMinorUnits(minor, SupportedCurrencies.fromCode(code).symbol)
                                    }
                                    Text(
                                        text = "إجمالي الدخل/الاسترداد: $formattedIncome",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = IncomeGreenLight
                                    )
                                }
                            }
                            Text(
                                text = "المصدر: ${parseResult.providerSourceLabel} • لا يتم تعديل رصيدك إلا بعد الضغط على (حفظ الكل).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (parseResult.clarificationQuestion != null) {
                                Text(
                                    text = "تنبيه: ${parseResult.clarificationQuestion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WarningAmberLight,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                items(
                    items = parseResult.proposals,
                    key = { it.draftId }
                ) { draft ->
                    ProposedDraftEditorCard(
                        draft = draft,
                        activeCategories = activeCategories,
                        onUpdateDraft = onUpdateDraft,
                        onRemoveDraft = { onRemoveDraft(draft.draftId) }
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancelReview,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("ai_cancel_batch_button")
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("إلغاء الدفعة")
                        }

                        Button(
                            onClick = onSaveAllReviewed,
                            enabled = allValid,
                            modifier = Modifier
                                .weight(1.4f)
                                .height(50.dp)
                                .testTag("ai_save_batch_button")
                        ) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("حفظ الكل (${parseResult.proposals.size})")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProposedDraftEditorCard(
    draft: ProposedTransactionDraft,
    activeCategories: List<CategoryEntity>,
    onUpdateDraft: (ProposedTransactionDraft) -> Unit,
    onRemoveDraft: () -> Unit
) {
    val eligibleForType = remember(activeCategories, draft.type, draft.isRefundOffset) {
        if (draft.type == TransactionType.INCOME && draft.isRefundOffset) {
            activeCategories.filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.INCOME }
        } else {
            activeCategories.filter { it.type == draft.type }
        }
    }
    val draftCurrencySymbol = remember(draft.currencyCode) {
        SupportedCurrencies.fromCode(draft.currencyCode).symbol
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_draft_card_${draft.draftId}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = draft.type == TransactionType.EXPENSE,
                        onClick = {
                            val newCat = activeCategories
                                .firstOrNull { it.type == TransactionType.EXPENSE }?.id
                            onUpdateDraft(
                                draft.copy(
                                    type = TransactionType.EXPENSE,
                                    categoryId = newCat,
                                    isRefundOffset = false,
                                    userConfirmedCategory = true
                                )
                            )
                        },
                        label = { Text("مصروف") }
                    )
                    FilterChip(
                        selected = draft.type == TransactionType.INCOME,
                        onClick = {
                            val newCat = activeCategories
                                .firstOrNull { it.type == TransactionType.INCOME }?.id
                            onUpdateDraft(
                                draft.copy(
                                    type = TransactionType.INCOME,
                                    categoryId = newCat,
                                    userConfirmedCategory = true
                                )
                            )
                        },
                        label = { Text("دخل") }
                    )
                }

                IconButton(
                    onClick = onRemoveDraft,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("remove_draft_${draft.draftId}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "حذف هذه المعاملة المقترحة",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (draft.isAmbiguous && draft.ambiguityReason != null) {
                Surface(
                    color = WarningAmberLight.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = WarningAmberLight,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = draft.ambiguityReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = WarningAmberLight,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { onUpdateDraft(draft.copy(description = it)) },
                    label = { Text("الوصف") },
                    singleLine = true,
                    modifier = Modifier.weight(1.3f)
                )

                OutlinedTextField(
                    value = draft.amountText,
                    onValueChange = { onUpdateDraft(draft.copy(amountText = it)) },
                    label = { Text("المبلغ ($draftCurrencySymbol)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = draft.amountMinorUnits == null || draft.amountMinorUnits <= 0L,
                    modifier = Modifier.weight(1f)
                )
            }

            TransactionDateSelectorSection(
                selectedIsoDate = draft.isoDate,
                onDateSelected = { updatedIsoDate ->
                    onUpdateDraft(
                        draft.copy(
                            isoDate = updatedIsoDate,
                            userModifiedDate = true
                        )
                    )
                },
                tagPrefix = "ai_draft_${draft.draftId}",
                showTextField = true
            )

            if (draft.type == TransactionType.INCOME) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = draft.isRefundOffset,
                        onCheckedChange = { checked ->
                            onUpdateDraft(draft.copy(isRefundOffset = checked))
                        }
                    )
                    Text(
                        text = "هذه المعاملة استرداد لمصروف سابق (تخصم من المصروفات عند تفعيل خيار الاسترداد)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Text(
                text = "التصنيف (عند تعديله يتذكر التطبيق اختيارك مستقبلاً):",
                style = MaterialTheme.typography.labelMedium
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = draft.categoryId == null,
                    onClick = { onUpdateDraft(draft.copy(categoryId = null, userConfirmedCategory = false)) },
                    label = { Text("غير مصنف") }
                )
                eligibleForType.forEach { cat ->
                    FilterChip(
                        selected = draft.categoryId == cat.id,
                        onClick = { onUpdateDraft(draft.copy(categoryId = cat.id, userConfirmedCategory = true)) },
                        label = { Text(cat.name) }
                    )
                }
            }
        }
    }
}
