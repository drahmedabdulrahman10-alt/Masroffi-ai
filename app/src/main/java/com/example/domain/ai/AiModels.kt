package com.example.domain.ai

import com.example.data.local.CategoryEntity
import com.example.data.local.TransactionType
import com.example.domain.MoneyUtils
import java.util.UUID

data class ProposedTransactionDraft(
    val draftId: String = UUID.randomUUID().toString(),
    val description: String,
    val amountText: String,
    val amountMinorUnits: Long?,
    val currencyCode: String = "EGP",
    val type: TransactionType = TransactionType.EXPENSE,
    val isoDate: String,
    val categoryId: String?,
    val notes: String = "",
    val confidence: Float = 0.90f,
    val isAmbiguous: Boolean = false,
    val ambiguityReason: String? = null,
    val isRefundOffset: Boolean = false,
    val suggestedCategoryId: String? = categoryId,
    val userConfirmedCategory: Boolean = false,
    val aiDetectedDate: Boolean = false,
    val userModifiedDate: Boolean = false,
    /**
     * True when the transaction text mentioned a date expression that could not be resolved to a
     * single calendar day (period-only expressions like "الأسبوع اللي فات" / "الشهر اللي فات",
     * vague expressions like "بعد كام يوم", or invalid dates like "يوم 31 الشهر اللي فات" in a
     * 30-day month). The draft keeps a placeholder [isoDate] and requires the user to confirm the
     * date individually before saving; it is never auto-saved.
     */
    val dateNeedsConfirmation: Boolean = false,
    /** Arabic explanation shown to the user when [dateNeedsConfirmation] is true. */
    val dateAmbiguityReason: String? = null
) {
    val isValidToSave: Boolean
        get() = description.isNotBlank() &&
            amountMinorUnits != null &&
            amountMinorUnits > 0L &&
            isoDate.isNotBlank() &&
            MoneyUtils.isoDateToEpochMillis(isoDate) != null
}

data class AiParseResult(
    val proposals: List<ProposedTransactionDraft>,
    val needsClarification: Boolean,
    val clarificationQuestion: String?,
    val providerSourceLabel: String,
    val errorMessage: String? = null,
    val batchOperationId: String = UUID.randomUUID().toString()
)

sealed class AiServiceException(val arabicMessage: String, cause: Throwable? = null) :
    Exception(arabicMessage, cause) {
    class MissingApiKey(message: String = "لم يتم إعداد مفتاح API للمزود المحدد في الإعدادات.") :
        AiServiceException(message)

    class InvalidApiKey(message: String = "مفتاح API غير صالح أو مرفوض. يرجى التحقق من المفتاح في الإعدادات.") :
        AiServiceException(message)

    class RateLimited(message: String = "تم تجاوز حد الطلبات المسموح به (Rate Limit) أو انتهت الحصة. حاول مرة أخرى لاحقاً.") :
        AiServiceException(message)

    class UnsupportedModel(message: String = "معرّف النموذج (Model ID) غير مدعوم أو غير متاح حالياً.") :
        AiServiceException(message)

    class NetworkFailure(message: String = "تعذر الاتصال بالإنترنت. يرجى التحقق من الشبكة أو المحاولة لاحقاً.") :
        AiServiceException(message)

    class ServerFailure(message: String = "حدث خطأ من خادم الذكاء الاصطناعي. يرجى المحاولة لاحقاً.") :
        AiServiceException(message)

    class MalformedResponse(message: String = "استجابة غير صالحة من خدمة الذكاء الاصطناعي. لم يتم حفظ أي بيانات.") :
        AiServiceException(message)

    class EmptyResponse(message: String = "لم يُرجع المزود أي نتائج قابلة للتحليل.") :
        AiServiceException(message)

    class InputTooLong(message: String = "النص المدخل طويل جداً. يرجى تقسيمه إلى دفعات أصغر.") :
        AiServiceException(message)
}

interface AiProviderClient {
    suspend fun requestStructuredTransactionsJson(
        userText: String,
        todayIsoDate: String,
        defaultCurrency: String,
        eligibleCategories: List<CategoryEntity>
    ): Result<String>

    suspend fun requestSmartSummaryText(
        aggregatedFactsArabic: String
    ): Result<String>

    suspend fun testConnection(): Result<String>
}
