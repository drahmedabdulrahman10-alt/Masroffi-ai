package com.example.domain.ai

import com.example.data.local.CategoryEntity
import com.example.data.preferences.AiProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Dedicated provider abstraction and HTTP client for Google Gemini API and OpenRouter API.
 * Enforces timeouts, never logs API keys or authorization headers, and translates HTTP
 * errors into clear Arabic domain exceptions.
 */
class RemoteAiProviderClient(
    private val providerType: AiProviderType,
    private val apiKeyProvider: () -> String,
    private val modelIdProvider: () -> String,
    private val httpClient: OkHttpClient = defaultOkHttpClient()
) : AiProviderClient {

    override suspend fun requestStructuredTransactionsJson(
        userText: String,
        todayIsoDate: String,
        defaultCurrency: String,
        eligibleCategories: List<CategoryEntity>
    ): Result<String> = withContext(Dispatchers.IO) {
        if (userText.length > MAX_INPUT_CHARS) {
            return@withContext Result.failure(AiServiceException.InputTooLong())
        }
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(AiServiceException.MissingApiKey())
        }
        val modelId = modelIdProvider().trim()
        if (modelId.isEmpty()) {
            return@withContext Result.failure(AiServiceException.UnsupportedModel())
        }

        val prompt = buildTransactionParsingPrompt(
            userText = userText,
            todayIsoDate = todayIsoDate,
            defaultCurrency = defaultCurrency,
            eligibleCategories = eligibleCategories.filter { !it.isArchived }
        )

        executeModelCall(
            apiKey = apiKey,
            modelId = modelId,
            systemInstruction = SYSTEM_INSTRUCTION_JSON_PARSER,
            userPrompt = prompt,
            expectJson = true
        )
    }

    override suspend fun requestSmartSummaryText(
        aggregatedFactsArabic: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(AiServiceException.MissingApiKey())
        }
        val modelId = modelIdProvider().trim()
        if (modelId.isEmpty()) {
            return@withContext Result.failure(AiServiceException.UnsupportedModel())
        }

        val userPrompt = """
            فيما يلي إحصائيات مالية محسوبة بدقة من قاعدة البيانات المحلية للمستخدم:
            $aggregatedFactsArabic
            
            المطلوب: كتابة ملخص مالي قصير وواضح باللغة العربية (من 2 إلى 3 جمل فقط) يوضح أهم ملاحظات الإنفاق والدخل بناءً على هذه الأرقام فقط.
            قواعد صارمة:
            - لا تخترع أي أرقام أو نسب غير موجودة في الملخص أعلاه.
            - لا تقدم توقعات مستقبلية غير مدعومة.
        """.trimIndent()

        executeModelCall(
            apiKey = apiKey,
            modelId = modelId,
            systemInstruction = "أنت مساعد مالي شخصي دقيق ومختصر لتطبيق مصروفي. التزم بالأرقام المعطاة فقط.",
            userPrompt = userPrompt,
            expectJson = false
        )
    }

    override suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(AiServiceException.MissingApiKey())
        }
        val modelId = modelIdProvider().trim()
        if (modelId.isEmpty()) {
            return@withContext Result.failure(AiServiceException.UnsupportedModel())
        }

        val res = executeModelCall(
            apiKey = apiKey,
            modelId = modelId,
            systemInstruction = "Return a short JSON object: {\"status\":\"ok\"}",
            userPrompt = "Ping connection test. Return {\"status\":\"ok\"}",
            expectJson = false
        )
        res.map { "تم الاتصال بنجاح مع المزود (${providerType.name}) والنموذج ($modelId)." }
    }

    private fun executeModelCall(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        userPrompt: String,
        expectJson: Boolean
    ): Result<String> {
        return try {
            val request = when (providerType) {
                AiProviderType.GEMINI -> buildGeminiRequest(
                    apiKey = apiKey,
                    modelId = modelId,
                    systemInstruction = systemInstruction,
                    userPrompt = userPrompt,
                    expectJson = expectJson
                )
                AiProviderType.OPENROUTER -> buildOpenRouterRequest(
                    apiKey = apiKey,
                    modelId = modelId,
                    systemInstruction = systemInstruction,
                    userPrompt = userPrompt,
                    expectJson = expectJson
                )
            }

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.failure(mapHttpError(response.code, bodyStr))
                }
                if (bodyStr.isBlank()) {
                    return Result.failure(AiServiceException.EmptyResponse())
                }
                val extractedText = when (providerType) {
                    AiProviderType.GEMINI -> extractGeminiText(bodyStr)
                    AiProviderType.OPENROUTER -> extractOpenRouterText(bodyStr)
                }
                if (extractedText.isNullOrBlank()) {
                    Result.failure(AiServiceException.EmptyResponse())
                } else {
                    Result.success(extractedText)
                }
            }
        } catch (e: SocketTimeoutException) {
            Result.failure(
                AiServiceException.NetworkFailure("انتهت مهلة الاتصال بالخادم. يرجى المحاولة مرة أخرى.")
            )
        } catch (e: IOException) {
            Result.failure(AiServiceException.NetworkFailure())
        } catch (e: Exception) {
            Result.failure(AiServiceException.MalformedResponse())
        }
    }

    private fun buildGeminiRequest(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        userPrompt: String,
        expectJson: Boolean
    ): Request {
        val cleanModel = modelId.removePrefix("models/").trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent"

        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction))
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", userPrompt))
                    )
                )
            )
            val genConfig = JSONObject().apply {
                put("temperature", 0.1)
                if (expectJson) {
                    put("responseMimeType", "application/json")
                }
            }
            put("generationConfig", genConfig)
        }

        return Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildOpenRouterRequest(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        userPrompt: String,
        expectJson: Boolean
    ): Request {
        val url = "https://openrouter.ai/api/v1/chat/completions"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemInstruction))
            .put(JSONObject().put("role", "user").put("content", userPrompt))

        val payload = JSONObject().apply {
            put("model", modelId.trim())
            put("messages", messages)
            put("temperature", 0.1)
            if (expectJson) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
        }

        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Title", "Masroofi AI Android")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun extractGeminiText(responseBody: String): String? {
        val json = JSONObject(responseBody)
        val candidates = json.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        if (parts.length() == 0) return null
        return parts.optJSONObject(0)?.optString("text")
    }

    private fun extractOpenRouterText(responseBody: String): String? {
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        return message.optString("content")
    }

    private fun mapHttpError(code: Int, body: String): AiServiceException {
        return when (code) {
            400 -> {
                if (body.contains("API_KEY_INVALID", ignoreCase = true) ||
                    body.contains("key", ignoreCase = true)
                ) {
                    AiServiceException.InvalidApiKey()
                } else if (body.contains("model", ignoreCase = true)) {
                    AiServiceException.UnsupportedModel()
                } else {
                    AiServiceException.MalformedResponse("طلب غير صالح (400). يرجى مراجعة إعدادات النموذج.")
                }
            }
            401, 403 -> AiServiceException.InvalidApiKey()
            404 -> AiServiceException.UnsupportedModel("النموذج المحدد غير موجود (404). يرجى التحقق من اسم النموذج في الإعدادات.")
            429 -> AiServiceException.RateLimited()
            in 500..599 -> AiServiceException.ServerFailure()
            else -> AiServiceException.ServerFailure("فشل الطلب برمز HTTP $code.")
        }
    }

    private fun buildTransactionParsingPrompt(
        userText: String,
        todayIsoDate: String,
        defaultCurrency: String,
        eligibleCategories: List<CategoryEntity>
    ): String {
        val catsJson = JSONArray()
        for (cat in eligibleCategories) {
            catsJson.put(
                JSONObject()
                    .put("id", cat.id)
                    .put("name", cat.name)
                    .put("type", cat.type.name)
            )
        }
        return """
            Current Date: $todayIsoDate
            Default Currency: $defaultCurrency
            Eligible User Categories (ONLY choose categoryId from this list or null if none fits):
            $catsJson
            
            User Input:
            $userText
        """.trimIndent()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_INPUT_CHARS = 4000

        private val SYSTEM_INSTRUCTION_JSON_PARSER = """
            أنت محلل معاملات مالية دقيق لتطبيق "مصروفي" (Masroofi AI) باللهجة المصرية والعربية والإنجليزية.
            مهمتك تحويل نص المستخدم إلى قائمة معاملات مالية منفصلة بصيغة JSON فقط وفق المخطط التالي:
            {
              "transactions": [
                {
                  "description": "وصف مختصر للصنف",
                  "amount": "20.00",
                  "currency": "EGP",
                  "type": "EXPENSE أو INCOME",
                  "date": "YYYY-MM-DD",
                  "categoryId": "معرف تصنيف موجود فقط أو null",
                  "notes": "",
                  "confidence": 0.95
                }
              ],
              "needsClarification": false,
              "clarificationQuestion": null
            }
            قواعد صارمة:
            1. افصل كل عملية شراء أو دخل مستقلة في عنصر منفصل ولا تدمج مشتريات غير مترابطة.
            2. لا تخترع أي مبلغ غير مذكور؛ إذا لم يُذكر المبلغ اجعل amount مساوياً "" واجعل needsClarification = true.
            3. اختر categoryId فقط من قائمة التصنيفات المتاحة المطابقة لنوع المعاملة (EXPENSE أو INCOME)، وإذا لم يوجد تصنيف مناسب اجعل categoryId = null.
        """.trimIndent()

        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}
