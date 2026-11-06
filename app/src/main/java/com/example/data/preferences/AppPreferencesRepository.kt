package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.local.RefundAccountingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.masroofiDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "masroofi_preferences"
)

enum class AiProviderType {
    GEMINI,
    OPENROUTER
}

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

data class SupportedCurrency(
    val code: String,
    val symbol: String,
    val arabicName: String
)

object SupportedCurrencies {
    val EGP = SupportedCurrency("EGP", "ج.م", "جنيه مصري (ج.م)")
    val SAR = SupportedCurrency("SAR", "ر.س", "ريال سعودي (ر.س)")
    val AED = SupportedCurrency("AED", "د.إ", "درهم إماراتي (د.إ)")
    val USD = SupportedCurrency("USD", "$", "دولار أمريكي ($)")
    val EUR = SupportedCurrency("EUR", "€", "يورو (€)")

    val all = listOf(EGP, SAR, AED, USD, EUR)

    fun fromCode(code: String): SupportedCurrency =
        all.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EGP
}

data class AppPreferencesState(
    val currencyCode: String = "EGP",
    val currencySymbol: String = "ج.م",
    val openingBalanceMinorUnits: Long = 0L,
    val openingBalanceConfigured: Boolean = false,
    val aiEnabled: Boolean = true,
    val selectedProvider: AiProviderType = AiProviderType.GEMINI,
    val geminiModelId: String = "gemini-2.5-flash",
    val openRouterModelId: String = "google/gemini-2.5-flash",
    val autoSaveEnabled: Boolean = false,
    val autoSaveConfidenceThreshold: Float = 0.90f,
    val aiSummaryEnabled: Boolean = true,
    val refundAccountingMode: RefundAccountingMode = RefundAccountingMode.INCOME_CATEGORY,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val categoriesSeeded: Boolean = false
)

class AppPreferencesRepository(
    private val context: Context,
    val secureKeyStorage: SecureKeyStorage = SecureKeyStorage(context)
) {

    private object Keys {
        val CURRENCY_CODE = stringPreferencesKey("currency_code")
        val OPENING_BALANCE_MINOR = longPreferencesKey("opening_balance_minor")
        val OPENING_BALANCE_CONFIGURED = booleanPreferencesKey("opening_balance_configured")
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val SELECTED_PROVIDER = stringPreferencesKey("selected_provider")
        val GEMINI_MODEL_ID = stringPreferencesKey("gemini_model_id")
        val OPENROUTER_MODEL_ID = stringPreferencesKey("openrouter_model_id")
        val AUTO_SAVE_ENABLED = booleanPreferencesKey("auto_save_enabled")
        val AUTO_SAVE_CONFIDENCE = floatPreferencesKey("auto_save_confidence")
        val AI_SUMMARY_ENABLED = booleanPreferencesKey("ai_summary_enabled")
        val REFUND_MODE = stringPreferencesKey("refund_accounting_mode")
        val THEME_PREF = stringPreferencesKey("theme_preference")
        val CATEGORIES_SEEDED = booleanPreferencesKey("categories_seeded")
    }

    val preferencesFlow: Flow<AppPreferencesState> = context.masroofiDataStore.data.map { prefs ->
        val code = prefs[Keys.CURRENCY_CODE] ?: "EGP"
        val currency = SupportedCurrencies.fromCode(code)
        val provider = runCatching {
            AiProviderType.valueOf(prefs[Keys.SELECTED_PROVIDER] ?: AiProviderType.GEMINI.name)
        }.getOrDefault(AiProviderType.GEMINI)
        val refundMode = runCatching {
            RefundAccountingMode.valueOf(
                prefs[Keys.REFUND_MODE] ?: RefundAccountingMode.INCOME_CATEGORY.name
            )
        }.getOrDefault(RefundAccountingMode.INCOME_CATEGORY)
        val themePref = runCatching {
            ThemePreference.valueOf(prefs[Keys.THEME_PREF] ?: ThemePreference.SYSTEM.name)
        }.getOrDefault(ThemePreference.SYSTEM)

        AppPreferencesState(
            currencyCode = currency.code,
            currencySymbol = currency.symbol,
            openingBalanceMinorUnits = prefs[Keys.OPENING_BALANCE_MINOR] ?: 0L,
            openingBalanceConfigured = prefs[Keys.OPENING_BALANCE_CONFIGURED] ?: false,
            aiEnabled = prefs[Keys.AI_ENABLED] ?: true,
            selectedProvider = provider,
            geminiModelId = prefs[Keys.GEMINI_MODEL_ID]?.takeIf { it.isNotBlank() }
                ?: "gemini-2.5-flash",
            openRouterModelId = prefs[Keys.OPENROUTER_MODEL_ID]?.takeIf { it.isNotBlank() }
                ?: "google/gemini-2.5-flash",
            autoSaveEnabled = prefs[Keys.AUTO_SAVE_ENABLED] ?: false,
            autoSaveConfidenceThreshold = (prefs[Keys.AUTO_SAVE_CONFIDENCE] ?: 0.90f)
                .coerceIn(0.50f, 0.99f),
            aiSummaryEnabled = prefs[Keys.AI_SUMMARY_ENABLED] ?: true,
            refundAccountingMode = refundMode,
            themePreference = themePref,
            categoriesSeeded = prefs[Keys.CATEGORIES_SEEDED] ?: false
        )
    }

    suspend fun getSnapshot(): AppPreferencesState = preferencesFlow.first()

    suspend fun setCurrency(currencyCode: String) {
        val valid = SupportedCurrencies.fromCode(currencyCode)
        context.masroofiDataStore.edit { prefs ->
            prefs[Keys.CURRENCY_CODE] = valid.code
        }
    }

    suspend fun setOpeningBalance(minorUnits: Long) {
        context.masroofiDataStore.edit { prefs ->
            prefs[Keys.OPENING_BALANCE_MINOR] = minorUnits
            prefs[Keys.OPENING_BALANCE_CONFIGURED] = true
        }
    }

    suspend fun clearOpeningBalance() {
        context.masroofiDataStore.edit { prefs ->
            prefs[Keys.OPENING_BALANCE_MINOR] = 0L
            prefs[Keys.OPENING_BALANCE_CONFIGURED] = false
        }
    }

    suspend fun setAiConfiguration(
        aiEnabled: Boolean,
        provider: AiProviderType,
        geminiModelId: String,
        openRouterModelId: String,
        autoSaveEnabled: Boolean,
        autoSaveConfidence: Float,
        aiSummaryEnabled: Boolean
    ) {
        context.masroofiDataStore.edit { prefs ->
            prefs[Keys.AI_ENABLED] = aiEnabled
            prefs[Keys.SELECTED_PROVIDER] = provider.name
            prefs[Keys.GEMINI_MODEL_ID] = geminiModelId.trim().ifEmpty { "gemini-2.5-flash" }
            prefs[Keys.OPENROUTER_MODEL_ID] =
                openRouterModelId.trim().ifEmpty { "google/gemini-2.5-flash" }
            prefs[Keys.AUTO_SAVE_ENABLED] = autoSaveEnabled
            prefs[Keys.AUTO_SAVE_CONFIDENCE] = autoSaveConfidence.coerceIn(0.50f, 0.99f)
            prefs[Keys.AI_SUMMARY_ENABLED] = aiSummaryEnabled
        }
    }

    suspend fun setRefundAccountingMode(mode: RefundAccountingMode) {
        context.masroofiDataStore.edit { prefs ->
            prefs[Keys.REFUND_MODE] = mode.name
        }
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        context.masroofiDataStore.edit { prefs ->
            prefs[Keys.THEME_PREF] = theme.name
        }
    }

    suspend fun markCategoriesSeeded() {
        context.masroofiDataStore.edit { prefs ->
            prefs[Keys.CATEGORIES_SEEDED] = true
        }
    }

    suspend fun restoreBackupSettings(
        currencyCode: String,
        openingBalanceMinorUnits: Long,
        openingBalanceConfigured: Boolean,
        refundMode: RefundAccountingMode
    ) {
        val valid = SupportedCurrencies.fromCode(currencyCode)
        context.masroofiDataStore.edit { prefs ->
            prefs[Keys.CURRENCY_CODE] = valid.code
            prefs[Keys.OPENING_BALANCE_MINOR] = openingBalanceMinorUnits
            prefs[Keys.OPENING_BALANCE_CONFIGURED] = openingBalanceConfigured
            prefs[Keys.REFUND_MODE] = refundMode.name
            prefs[Keys.CATEGORIES_SEEDED] = true
        }
    }
}
