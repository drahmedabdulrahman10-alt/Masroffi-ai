package com.example.ui.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

data class SpeechRecognitionState(
    val isListening: Boolean = false,
    val partialText: String = "",
    val errorMessage: String? = null
)

/**
 * Android SpeechRecognizer wrapper for Masroofi AI.
 * Converts spoken Egyptian Arabic (ar-EG), Standard Arabic, or English into editable text locally
 * via Android speech services without transmitting raw audio to external AI providers.
 * Never records in the background and never saves audio files.
 */
class MasroofiSpeechHelper(
    private val context: Context,
    private val onStateChanged: (SpeechRecognitionState) -> Unit,
    private val onFinalTextRecognized: (String) -> Unit
) : LifecycleEventObserver {
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentState = SpeechRecognitionState()

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_PAUSE ||
            event == Lifecycle.Event.ON_STOP ||
            event == Lifecycle.Event.ON_DESTROY
        ) {
            stopListening()
        }
    }

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun buildFallbackRecognizerIntent(languageTag: String = "ar-EG"): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "تحدث الآن لوصف معاملاتك المالية (مثال: اشتريت طماطم بعشرين جنيه)"
            )
        }
    }

    fun startListening(languageTag: String = "ar-EG") {
        stopListening()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            updateState(
                SpeechRecognitionState(
                    isListening = false,
                    errorMessage = "خدمة التعرف على الصوت المباشرة غير متوفرة على هذا الجهاز. جرب نافذة الإملاء الصوتية للنظام."
                )
            )
            return
        }

        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    updateState(
                        SpeechRecognitionState(
                            isListening = true,
                            partialText = "",
                            errorMessage = null
                        )
                    )
                }

                override fun onBeginningOfSpeech() {
                    updateState(currentState.copy(isListening = true, errorMessage = null))
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    updateState(currentState.copy(isListening = false))
                }

                override fun onError(error: Int) {
                    destroyRecognizerSilently()
                    val msg = mapSpeechErrorToArabic(error)
                    updateState(
                        SpeechRecognitionState(
                            isListening = false,
                            partialText = "",
                            errorMessage = msg
                        )
                    )
                }

                override fun onResults(results: Bundle?) {
                    destroyRecognizerSilently()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val topText = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    if (topText.isEmpty()) {
                        updateState(
                            SpeechRecognitionState(
                                isListening = false,
                                partialText = "",
                                errorMessage = "لم يتم التقاط أي كلام واضح. يرجى المحاولة مرة أخرى."
                            )
                        )
                    } else {
                        updateState(SpeechRecognitionState(isListening = false, partialText = ""))
                        onFinalTextRecognized(topText)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches =
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull().orEmpty()
                    if (partial.isNotBlank()) {
                        updateState(currentState.copy(partialText = partial))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = buildFallbackRecognizerIntent(languageTag)
            recognizer.startListening(intent)
        } catch (e: Exception) {
            updateState(
                SpeechRecognitionState(
                    isListening = false,
                    errorMessage = "تعذر بدء التعرف على الصوت على هذا الجهاز."
                )
            )
        }
    }

    fun stopListening() {
        destroyRecognizerSilently()
        if (currentState.isListening) {
            updateState(currentState.copy(isListening = false, partialText = ""))
        }
    }

    private fun destroyRecognizerSilently() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            speechRecognizer = null
        }
    }

    private fun updateState(newState: SpeechRecognitionState) {
        currentState = newState
        onStateChanged(newState)
    }

    companion object {
        fun mapSpeechErrorToArabic(errorCode: Int): String {
            return when (errorCode) {
                SpeechRecognizer.ERROR_AUDIO ->
                    "حدثت مشكلة في التقاط الصوت من الميكروفون."
                SpeechRecognizer.ERROR_CLIENT ->
                    "تم إيقاف التسجيل الصوتي."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "يلزم منح إذن الميكروفون لاستخدام الإدخال الصوتي."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "تعذر الاتصال بالشبكة لخدمة التعرف على الصوت. تأكد من اتصالك بالإنترنت أو حزمة اللغة."
                SpeechRecognizer.ERROR_NO_MATCH ->
                    "لم يتم التعرف على الكلام. حاول التحدث بوضوح باللهجة المصرية أو العربية."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "خدمة التعرف على الصوت مشغولة حالياً. انتظر لحظة وحاول مجدداً."
                SpeechRecognizer.ERROR_SERVER ->
                    "تعذر معالجة الصوت من خادم التعرف على الكلام."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "لم يتم سماع أي صوت. اضغط على الميكروفون وتحدث مباشرة."
                12, 13 -> // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31+)
                    "اللغة المحددة غير مدعومة في حزمة التعرف الصوتي المثبتة على الجهاز."
                else ->
                    "تعذر تحويل الصوت إلى نص (رمز $errorCode). يمكنك الكتابة مباشرة أو المحاولة مجدداً."
            }
        }
    }
}
