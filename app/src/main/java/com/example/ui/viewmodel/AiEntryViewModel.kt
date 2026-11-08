package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.CategoryEntity
import com.example.data.preferences.AppPreferencesState
import com.example.data.repository.FinanceRepository
import com.example.domain.MoneyUtils
import com.example.domain.ai.AiParseResult
import com.example.domain.ai.AiServiceException
import com.example.domain.ai.ProposedTransactionDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiEntryUiState(
    val preferences: AppPreferencesState = AppPreferencesState(),
    val categories: List<CategoryEntity> = emptyList(),
    val aiInputText: String = "",
    val selectedTransactionIsoDate: String = MoneyUtils.todayIsoDate(),
    val isDateManuallySelected: Boolean = false,
    val isAiParsing: Boolean = false,
    val aiParseResult: AiParseResult? = null,
    val aiErrorMessage: String? = null,
    val hasGeminiKey: Boolean = false,
    val hasOpenRouterKey: Boolean = false,
    val snackbarMessage: String? = null
)

private data class LocalAiState(
    val aiInputText: String = "",
    val selectedTransactionIsoDate: String = MoneyUtils.todayIsoDate(),
    val isDateManuallySelected: Boolean = false,
    val isAiParsing: Boolean = false,
    val aiParseResult: AiParseResult? = null,
    val aiErrorMessage: String? = null
)

class AiEntryViewModel(
    private val repository: FinanceRepository,
    private val snackbarManager: SnackbarManager = SnackbarManager()
) : ViewModel() {

    private val localAiState = MutableStateFlow(LocalAiState())

    val uiState: StateFlow<AiEntryUiState> = combine(
        repository.preferencesFlow,
        repository.allCategoriesFlow,
        repository.observeApiKeyStatus(),
        localAiState,
        snackbarManager.messages
    ) { prefs, categories, keyStatus, local, snackbar ->
        AiEntryUiState(
            preferences = prefs,
            categories = categories,
            aiInputText = local.aiInputText,
            selectedTransactionIsoDate = local.selectedTransactionIsoDate,
            isDateManuallySelected = local.isDateManuallySelected,
            isAiParsing = local.isAiParsing,
            aiParseResult = local.aiParseResult,
            aiErrorMessage = local.aiErrorMessage,
            hasGeminiKey = keyStatus.hasGeminiKey,
            hasOpenRouterKey = keyStatus.hasOpenRouterKey,
            snackbarMessage = snackbar
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AiEntryUiState()
    )

    fun updateAiInputText(text: String) {
        localAiState.update {
            it.copy(aiInputText = text, aiErrorMessage = null)
        }
    }

    /**
     * Allows the user to select a batch-level default transaction date for AI entry (e.g. Today,
     * Yesterday, Day Before Yesterday, or from the Calendar Date Picker).
     *
     * Three-tier date precedence (AUD-6.1-01 & AUD-6.1-03):
     * 1. User-modified individual draft date (userModifiedDate == true) — highest precedence;
     *    never overwritten by batch date changes.
     * 2. Explicit AI/parser-detected date in transaction text (aiDetectedDate == true) — preserved
     *    over batch-level default dates.
     * 3. Batch-level default date (selectedTransactionIsoDate) — applied to eligible drafts that
     *    have neither an explicit AI-detected date nor an individual user date override.
     *
     * Selecting Today resets isDateManuallySelected to false so the user can predictably return
     * to automatic date interpretation.
     */
    fun selectAiTransactionDate(isoDate: String) {
        val parsedLocal = MoneyUtils.parseIsoLocalDate(isoDate)
        val normalizedIso = parsedLocal?.let {
            MoneyUtils.formatLocalDateToIso(it)
        } ?: MoneyUtils.normalizeDigits(isoDate.trim())
        val isManualBatch = parsedLocal != null && normalizedIso != MoneyUtils.todayIsoDate()

        localAiState.update { state ->
            val updatedParseResult = state.aiParseResult?.let { currentResult ->
                if (parsedLocal != null) {
                    val updatedProposals = currentResult.proposals.map { draft ->
                        if (!draft.userModifiedDate && !draft.aiDetectedDate) {
                            draft.copy(
                                isoDate = normalizedIso,
                                userModifiedDate = false
                            )
                        } else {
                            draft
                        }
                    }
                    currentResult.copy(proposals = updatedProposals)
                } else {
                    currentResult
                }
            }
            state.copy(
                selectedTransactionIsoDate = normalizedIso,
                isDateManuallySelected = isManualBatch,
                aiParseResult = updatedParseResult,
                aiErrorMessage = null
            )
        }
    }

    fun appendRecognizedSpeech(spokenText: String) {
        if (spokenText.isBlank()) return
        localAiState.update { state ->
            val combined = if (state.aiInputText.isBlank()) {
                spokenText.trim()
            } else {
                "${state.aiInputText.trim()} ${spokenText.trim()}"
            }
            state.copy(aiInputText = combined, aiErrorMessage = null)
        }
    }

    fun analyzeAiInput(useLocalParserOnly: Boolean = false) {
        val currentState = localAiState.value
        val currentText = currentState.aiInputText.trim()
        if (currentText.isEmpty()) {
            localAiState.update {
                it.copy(aiErrorMessage = "يرجى كتابة أو إملاء معاملة واحدة على الأقل قبل التحليل.")
            }
            return
        }

        val previousProposals = currentState.aiParseResult?.proposals.orEmpty()
        val manualBatchDate = currentState.selectedTransactionIsoDate.takeIf {
            currentState.isDateManuallySelected && MoneyUtils.isoDateToEpochMillis(it) != null
        }

        localAiState.update {
            it.copy(isAiParsing = true, aiErrorMessage = null, aiParseResult = null)
        }

        viewModelScope.launch {
            val todayIso = MoneyUtils.todayIsoDate()
            val parseRes = repository.parseMultiTransactionsWithAi(
                userText = currentText,
                todayIsoDate = todayIso,
                useLocalParserOnly = useLocalParserOnly
            )

            parseRes.fold(
                onSuccess = { rawResult ->
                    val latestState = localAiState.value
                    val activeManualDate = latestState.selectedTransactionIsoDate.takeIf {
                        latestState.isDateManuallySelected && MoneyUtils.isoDateToEpochMillis(it) != null
                    } ?: manualBatchDate
                    val result = applyManualDatePrecedence(
                        parseResult = rawResult,
                        manualBatchDate = activeManualDate,
                        previousProposals = previousProposals
                    )

                    val prefs = uiState.value.preferences
                    val canAutoSave = prefs.autoSaveEnabled &&
                        result.proposals.isNotEmpty() &&
                        !result.needsClarification &&
                        result.proposals.all { draft ->
                            draft.isValidToSave &&
                                !draft.isAmbiguous &&
                                draft.categoryId != null &&
                                draft.confidence >= prefs.autoSaveConfidenceThreshold
                        }

                    if (canAutoSave) {
                        val batchRes = repository.saveReviewedBatchTransactions(
                            batchOperationId = result.batchOperationId,
                            drafts = result.proposals
                        )
                        batchRes.fold(
                            onSuccess = { count ->
                                localAiState.update {
                                    it.copy(
                                        isAiParsing = false,
                                        aiInputText = "",
                                        selectedTransactionIsoDate = MoneyUtils.todayIsoDate(),
                                        isDateManuallySelected = false,
                                        aiParseResult = null,
                                        aiErrorMessage = null
                                    )
                                }
                                snackbarManager.showMessage("تم الحفظ التلقائي لـ $count معاملات بنجاح")
                            },
                            onFailure = { err ->
                                localAiState.update {
                                    it.copy(
                                        isAiParsing = false,
                                        aiParseResult = result,
                                        aiErrorMessage = err.message
                                    )
                                }
                            }
                        )
                    } else {
                        localAiState.update {
                            it.copy(
                                isAiParsing = false,
                                aiParseResult = result,
                                aiErrorMessage = result.errorMessage
                            )
                        }
                    }
                },
                onFailure = { err ->
                    val msg = (err as? AiServiceException)?.arabicMessage
                        ?: err.message
                        ?: "حدث خطأ أثناء تحليل المعاملات."
                    localAiState.update {
                        it.copy(isAiParsing = false, aiErrorMessage = msg)
                    }
                }
            )
        }
    }

    private fun applyManualDatePrecedence(
        parseResult: AiParseResult,
        manualBatchDate: String?,
        previousProposals: List<ProposedTransactionDraft>
    ): AiParseResult {
        if (manualBatchDate == null && previousProposals.none { it.userModifiedDate }) {
            return parseResult
        }

        // Maintain a consumable pool of previous proposals so each previous draft is matched at most once.
        // Never inherit a date solely by list index (AUD-6.1-02): require matching normalized description and type.
        val availablePrevious = previousProposals.toMutableList()

        val mergedProposals = parseResult.proposals.mapIndexed { index, draft ->
            val draftDesc = draft.description.trim()
            val positionalCandidate = previousProposals.getOrNull(index)?.takeIf { candidate ->
                availablePrevious.contains(candidate) &&
                    candidate.description.trim().equals(draftDesc, ignoreCase = true) &&
                    candidate.type == draft.type
            }

            val matchedPrevious = positionalCandidate?.takeIf {
                it.amountMinorUnits == draft.amountMinorUnits
            } ?: availablePrevious.firstOrNull { candidate ->
                candidate.description.trim().equals(draftDesc, ignoreCase = true) &&
                    candidate.type == draft.type &&
                    candidate.amountMinorUnits == draft.amountMinorUnits
            } ?: positionalCandidate ?: availablePrevious.firstOrNull { candidate ->
                candidate.description.trim().equals(draftDesc, ignoreCase = true) &&
                    candidate.type == draft.type
            }

            if (matchedPrevious != null) {
                availablePrevious.remove(matchedPrevious)
            }

            val validManualOverride = matchedPrevious?.takeIf {
                it.userModifiedDate && MoneyUtils.isoDateToEpochMillis(it.isoDate) != null
            }

            when {
                validManualOverride != null -> draft.copy(
                    isoDate = validManualOverride.isoDate,
                    userModifiedDate = true
                )
                draft.aiDetectedDate -> draft
                manualBatchDate != null -> draft.copy(
                    isoDate = manualBatchDate,
                    userModifiedDate = false
                )
                else -> draft
            }
        }
        return parseResult.copy(proposals = mergedProposals)
    }

    fun updateProposedDraft(updatedDraft: ProposedTransactionDraft) {
        val currentResult = localAiState.value.aiParseResult ?: return
        val existingDraft = currentResult.proposals.firstOrNull { it.draftId == updatedDraft.draftId }
        val normalizedIsoDate = MoneyUtils.parseIsoLocalDate(updatedDraft.isoDate)?.let {
            MoneyUtils.formatLocalDateToIso(it)
        } ?: updatedDraft.isoDate
        val userChangedDate = updatedDraft.userModifiedDate ||
            (existingDraft != null && existingDraft.isoDate != normalizedIsoDate)

        val recalculatedMinor = MoneyUtils.parseAmountToMinorUnits(updatedDraft.amountText)
        val hasValidDate = MoneyUtils.isoDateToEpochMillis(normalizedIsoDate) != null
        val isNowAmbiguous = recalculatedMinor == null ||
            recalculatedMinor <= 0L ||
            updatedDraft.description.isBlank() ||
            updatedDraft.categoryId == null ||
            !hasValidDate

        val ambiguityReason = when {
            updatedDraft.description.isBlank() -> "الوصف مطلوب"
            recalculatedMinor == null || recalculatedMinor <= 0L -> "يرجى إدخال مبلغ صحيح أكبر من الصفر"
            !hasValidDate -> "التاريخ غير صالح (YYYY-MM-DD)"
            updatedDraft.categoryId == null -> "يرجى اختيار تصنيف للمعاملة"
            else -> null
        }

        val normalizedDraft = updatedDraft.copy(
            isoDate = normalizedIsoDate,
            amountMinorUnits = recalculatedMinor,
            isAmbiguous = isNowAmbiguous,
            ambiguityReason = ambiguityReason,
            userModifiedDate = userChangedDate
        )

        val updatedList = currentResult.proposals.map {
            if (it.draftId == normalizedDraft.draftId) normalizedDraft else it
        }
        localAiState.update {
            it.copy(
                aiParseResult = currentResult.copy(
                    proposals = updatedList,
                    needsClarification = updatedList.any { d -> d.isAmbiguous }
                )
            )
        }
    }

    fun removeProposedDraft(draftId: String) {
        val currentResult = localAiState.value.aiParseResult ?: return
        val remaining = currentResult.proposals.filterNot { it.draftId == draftId }
        localAiState.update {
            it.copy(
                aiParseResult = if (remaining.isEmpty()) {
                    null
                } else {
                    currentResult.copy(
                        proposals = remaining,
                        needsClarification = remaining.any { d -> d.isAmbiguous }
                    )
                }
            )
        }
    }

    fun cancelAiReview() {
        localAiState.update {
            it.copy(
                selectedTransactionIsoDate = MoneyUtils.todayIsoDate(),
                isDateManuallySelected = false,
                aiParseResult = null,
                aiErrorMessage = null
            )
        }
    }

    fun saveAllReviewedProposals(onSavedSuccess: () -> Unit = {}) {
        val currentResult = localAiState.value.aiParseResult ?: return
        val invalidDraft = currentResult.proposals.firstOrNull { !it.isValidToSave }
        if (invalidDraft != null) {
            snackbarManager.showMessage("يرجى استكمال المبلغ والوصف والتاريخ لجميع المعاملات المقترحة قبل الحفظ.")
            return
        }

        viewModelScope.launch {
            val saveRes = repository.saveReviewedBatchTransactions(
                batchOperationId = currentResult.batchOperationId,
                drafts = currentResult.proposals
            )
            saveRes.fold(
                onSuccess = { count ->
                    localAiState.update {
                        it.copy(
                            aiInputText = "",
                            selectedTransactionIsoDate = MoneyUtils.todayIsoDate(),
                            isDateManuallySelected = false,
                            aiParseResult = null,
                            aiErrorMessage = null
                        )
                    }
                    snackbarManager.showMessage("تم حفظ $count معاملات بنجاح")
                    onSavedSuccess()
                },
                onFailure = { err ->
                    snackbarManager.showMessage(err.message ?: "تعذر حفظ الدفعة")
                }
            )
        }
    }

    fun clearSnackbar() {
        snackbarManager.clearMessage()
    }
}
