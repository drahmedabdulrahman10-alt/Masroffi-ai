package com.example.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.backup.BackupExportManager
import com.example.data.backup.BackupValidationPreview
import com.example.data.local.BudgetEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.RefundAccountingMode
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.preferences.AiProviderType
import com.example.data.preferences.ApiKeyStatus
import com.example.data.preferences.AppPreferencesState
import com.example.data.preferences.ThemePreference
import com.example.data.repository.FinanceRepository
import com.example.data.repository.RestoreMode
import com.example.domain.MoneyUtils
import com.example.domain.ai.AiServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

data class SettingsUiState(
    val preferences: AppPreferencesState = AppPreferencesState(),
    val categories: List<CategoryEntity> = emptyList(),
    val allTransactions: List<TransactionEntity> = emptyList(),
    val allBudgets: List<BudgetEntity> = emptyList(),
    val selectedHomeYearMonth: String = MoneyUtils.currentYearMonth(),
    val maskedGeminiKey: String = "غير مُعد",
    val maskedOpenRouterKey: String = "غير مُعد",
    val hasGeminiKey: Boolean = false,
    val hasOpenRouterKey: Boolean = false,
    val isTestingConnection: Boolean = false,
    val connectionTestMessage: String? = null,
    val connectionTestSuccess: Boolean? = null,
    val pendingBackupPreview: BackupValidationPreview? = null,
    val snackbarMessage: String? = null
)

private data class LocalSettingsFeedbackState(
    val isTestingConnection: Boolean = false,
    val connectionTestMessage: String? = null,
    val connectionTestSuccess: Boolean? = null,
    val pendingBackupPreview: BackupValidationPreview? = null
)

private data class SettingsDataSnapshot(
    val preferences: AppPreferencesState = AppPreferencesState(),
    val categories: List<CategoryEntity> = emptyList(),
    val allTransactions: List<TransactionEntity> = emptyList(),
    val allBudgets: List<BudgetEntity> = emptyList(),
    val keyStatus: ApiKeyStatus = ApiKeyStatus()
)

class SettingsViewModel(
    private val repository: FinanceRepository,
    private val snackbarManager: SnackbarManager = SnackbarManager(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val localFeedbackState = MutableStateFlow(LocalSettingsFeedbackState())

    private val settingsDataFlow = combine(
        repository.preferencesFlow,
        repository.allCategoriesFlow,
        repository.allTransactionsFlow,
        repository.allBudgetsFlow,
        repository.observeApiKeyStatus()
    ) { prefs, categories, transactions, budgets, keyStatus ->
        SettingsDataSnapshot(
            preferences = prefs,
            categories = categories,
            allTransactions = transactions,
            allBudgets = budgets,
            keyStatus = keyStatus
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsDataFlow,
        localFeedbackState,
        snackbarManager.messages
    ) { data, feedback, snackbar ->
        SettingsUiState(
            preferences = data.preferences,
            categories = data.categories,
            allTransactions = data.allTransactions,
            allBudgets = data.allBudgets,
            maskedGeminiKey = data.keyStatus.maskedGeminiKey,
            maskedOpenRouterKey = data.keyStatus.maskedOpenRouterKey,
            hasGeminiKey = data.keyStatus.hasGeminiKey,
            hasOpenRouterKey = data.keyStatus.hasOpenRouterKey,
            isTestingConnection = feedback.isTestingConnection,
            connectionTestMessage = feedback.connectionTestMessage,
            connectionTestSuccess = feedback.connectionTestSuccess,
            pendingBackupPreview = feedback.pendingBackupPreview,
            snackbarMessage = snackbar
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState()
    )

    fun showUserMessage(message: String) {
        if (message.isNotBlank()) {
            snackbarManager.showMessage(message)
        }
    }

    fun clearSnackbar() {
        snackbarManager.clearMessage()
    }

    // --- Categories Management ---

    fun saveCategory(
        existingId: String? = null,
        name: String,
        type: TransactionType,
        iconName: String,
        colorHex: Long,
        isArchived: Boolean = false
    ) {
        viewModelScope.launch {
            val res = repository.saveCategory(
                existingId = existingId,
                name = name,
                type = type,
                iconName = iconName,
                colorHex = colorHex,
                isArchived = isArchived
            )
            res.fold(
                onSuccess = {
                    snackbarManager.showMessage(
                        if (existingId != null) "تم تحديث التصنيف" else "تمت إضافة التصنيف"
                    )
                },
                onFailure = { err ->
                    snackbarManager.showMessage(err.message ?: "تعذر حفظ التصنيف")
                }
            )
        }
    }

    fun toggleCategoryArchive(category: CategoryEntity) {
        viewModelScope.launch {
            repository.setCategoryArchived(category.id, !category.isArchived)
            snackbarManager.showMessage(
                if (!category.isArchived) "تمت أرشفة التصنيف" else "تمت استعادة التصنيف"
            )
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            val affected = repository.deleteCategorySafely(categoryId)
            val msg = if (affected > 0) {
                "تم حذف التصنيف وتحويل $affected معاملات مرتبطة إلى (غير مصنف) للحفاظ على سجلاتك."
            } else {
                "تم حذف التصنيف بنجاح."
            }
            snackbarManager.showMessage(msg)
        }
    }

    // --- Budgets & Opening Balance ---

    fun saveOpeningBalance(amountText: String) {
        val minor = MoneyUtils.parseSignedAmountToMinorUnits(amountText)
        if (minor == null) {
            snackbarManager.showMessage("يرجى إدخال رصيد افتتاحي صالح")
            return
        }
        viewModelScope.launch {
            repository.preferencesRepository.setOpeningBalance(minor)
            snackbarManager.showMessage("تم تحديث الرصيد الافتتاحي")
        }
    }

    fun saveMonthlyBudget(
        yearMonth: String,
        categoryId: String?,
        amountText: String
    ) {
        val minor = MoneyUtils.parseAmountToMinorUnits(amountText)
        if (minor == null || minor <= 0L) {
            snackbarManager.showMessage("يرجى إدخال مبلغ ميزانية صحيح أكبر من الصفر")
            return
        }
        viewModelScope.launch {
            repository.saveBudget(yearMonth, categoryId, minor)
            snackbarManager.showMessage("تم حفظ الميزانية الشهرية")
        }
    }

    fun deleteBudget(budgetId: String) {
        viewModelScope.launch {
            repository.deleteBudget(budgetId)
            snackbarManager.showMessage("تم حذف الميزانية")
        }
    }

    // --- Settings & AI Provider Credentials ---

    fun updateCurrency(currencyCode: String) {
        viewModelScope.launch {
            repository.preferencesRepository.setCurrency(currencyCode)
            snackbarManager.showMessage("تم تغيير العملة إلى $currencyCode")
        }
    }

    fun updateThemePreference(theme: ThemePreference) {
        viewModelScope.launch {
            repository.preferencesRepository.setThemePreference(theme)
        }
    }

    fun updateRefundAccountingMode(mode: RefundAccountingMode) {
        viewModelScope.launch {
            repository.preferencesRepository.setRefundAccountingMode(mode)
            snackbarManager.showMessage("تم تحديث طريقة حساب الاسترداد")
        }
    }

    fun saveAiSettings(
        aiEnabled: Boolean,
        provider: AiProviderType,
        geminiModelId: String,
        openRouterModelId: String,
        newApiKeyInput: String,
        autoSaveEnabled: Boolean,
        autoSaveConfidence: Float,
        aiSummaryEnabled: Boolean
    ) {
        viewModelScope.launch {
            repository.preferencesRepository.setAiConfiguration(
                aiEnabled = aiEnabled,
                provider = provider,
                geminiModelId = geminiModelId,
                openRouterModelId = openRouterModelId,
                autoSaveEnabled = autoSaveEnabled,
                autoSaveConfidence = autoSaveConfidence,
                aiSummaryEnabled = aiSummaryEnabled
            )
            var keySaveErrorMessage: String? = null
            if (newApiKeyInput.isNotBlank()) {
                val saveRes = repository.saveProviderApiKey(provider, newApiKeyInput.trim())
                if (saveRes.isFailure) {
                    keySaveErrorMessage = saveRes.exceptionOrNull()?.message
                        ?: "تعذر تشفير مفتاح API بأمان عبر Android Keystore."
                }
            }
            snackbarManager.showMessage(
                keySaveErrorMessage ?: "تم حفظ إعدادات الذكاء الاصطناعي بنجاح"
            )
        }
    }

    fun removeApiKey(provider: AiProviderType) {
        viewModelScope.launch {
            repository.removeProviderApiKey(provider)
            localFeedbackState.update {
                it.copy(
                    connectionTestMessage = null,
                    connectionTestSuccess = null
                )
            }
            snackbarManager.showMessage("تم حذف مفتاح API المحفوظ")
        }
    }

    fun testAiConnection(
        provider: AiProviderType,
        candidateKeyInput: String,
        modelIdInput: String
    ) {
        localFeedbackState.update {
            it.copy(
                isTestingConnection = true,
                connectionTestMessage = null,
                connectionTestSuccess = null
            )
        }

        viewModelScope.launch {
            val effectiveKey = candidateKeyInput.trim().ifEmpty {
                repository.getProviderApiKey(provider)
            }
            val res = repository.testProviderConnection(
                provider = provider,
                apiKey = effectiveKey,
                modelId = modelIdInput
            )
            res.fold(
                onSuccess = { msg ->
                    localFeedbackState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionTestMessage = msg,
                            connectionTestSuccess = true
                        )
                    }
                },
                onFailure = { err ->
                    val msg = (err as? AiServiceException)?.arabicMessage
                        ?: err.message
                        ?: "فشل الاتصال بالمزود"
                    localFeedbackState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionTestMessage = msg,
                            connectionTestSuccess = false
                        )
                    }
                }
            )
        }
    }

    // --- Backup & CSV Export ---

    suspend fun createBackupJsonString(): String = withContext(ioDispatcher) {
        repository.createBackupJson()
    }

    suspend fun exportCsvString(): String = withContext(ioDispatcher) {
        repository.exportCsvString()
    }

    fun exportBackupToUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val json = repository.createBackupJson()
                    val out = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("تعذر فتح ملف الوجهة للكتابة")
                    out.use { stream ->
                        stream.write(json.toByteArray(StandardCharsets.UTF_8))
                        stream.flush()
                    }
                }
                snackbarManager.showMessage("تم حفظ النسخة الاحتياطية (JSON) بنجاح بدون أي مفاتيح API.")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                snackbarManager.showMessage("فشل تصدير النسخة الاحتياطية: ${e.message}")
            }
        }
    }

    fun importBackupFromUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            try {
                val content = withContext(ioDispatcher) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("تعذر فتح ملف النسخة الاحتياطية")
                    input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
                validateBackupFileContent(content)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                snackbarManager.showMessage("تعذر قراءة ملف النسخة الاحتياطية: ${e.message}")
            }
        }
    }

    fun exportCsvToUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val csv = repository.exportCsvString()
                    val out = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("تعذر فتح ملف CSV للكتابة")
                    out.use { stream ->
                        stream.write(csv.toByteArray(StandardCharsets.UTF_8))
                        stream.flush()
                    }
                }
                snackbarManager.showMessage("تم تصدير المعاملات إلى ملف CSV بنجاح.")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                snackbarManager.showMessage("فشل تصدير ملف CSV: ${e.message}")
            }
        }
    }

    fun validateBackupFileContent(rawJson: String) {
        val preview = BackupExportManager.validateAndPreviewBackupJson(rawJson)
        if (!preview.isValid) {
            snackbarManager.showMessage(preview.errorMessage ?: "ملف النسخة الاحتياطية غير صالح")
        }
        localFeedbackState.update { it.copy(pendingBackupPreview = preview) }
    }

    fun dismissBackupPreview() {
        localFeedbackState.update { it.copy(pendingBackupPreview = null) }
    }

    fun confirmRestoreBackup(mode: RestoreMode) {
        val preview = localFeedbackState.value.pendingBackupPreview
        val payload = preview?.payload
        if (preview == null || !preview.isValid || payload == null) {
            snackbarManager.showMessage("لا توجد نسخة احتياطية صالحة للاستعادة")
            return
        }
        viewModelScope.launch {
            val res = withContext(ioDispatcher) {
                repository.applyValidatedBackup(payload, mode)
            }
            res.fold(
                onSuccess = {
                    localFeedbackState.update {
                        it.copy(pendingBackupPreview = null)
                    }
                    snackbarManager.showMessage(
                        "تمت استعادة النسخة الاحتياطية بنجاح (${payload.transactions.size} معاملة)"
                    )
                },
                onFailure = { err ->
                    snackbarManager.showMessage(err.message ?: "فشل استعادة النسخة الاحتياطية")
                }
            )
        }
    }
}
