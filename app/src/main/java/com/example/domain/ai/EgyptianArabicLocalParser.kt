package com.example.domain.ai

import com.example.data.local.CategoryEntity
import com.example.data.local.CategoryRuleEntity
import com.example.data.local.RefundAccountingMode
import com.example.data.local.TransactionType
import com.example.domain.MoneyUtils
import com.squareup.moshi.JsonReader
import okio.Buffer
import java.util.Locale

/**
 * Deterministic Egyptian Arabic, Standard Arabic, and English transaction parser and
 * strict JSON response validator.
 *
 * Enforces all strict AI & Category rules:
 * - Never invents categories; matches only against non-archived eligible categories.
 * - Remembers and applies user category rules.
 * - Never invents missing amounts; flags ambiguous items for mandatory user confirmation.
 * - Separates multiple purchases in one sentence or multiple lines into individual drafts.
 */
object EgyptianArabicLocalParser {

    private val numberWordsMap = mapOf(
        "نص" to 0.5,
        "نصف" to 0.5,
        "ربع" to 0.25,
        "واحد" to 1.0,
        "جنيه" to 1.0,
        "جنيهين" to 2.0,
        "اتنين" to 2.0,
        "اثنين" to 2.0,
        "تلاتة" to 3.0,
        "ثلاثة" to 3.0,
        "تلات" to 3.0,
        "ثلاث" to 3.0,
        "اربعة" to 4.0,
        "أربعة" to 4.0,
        "اربع" to 4.0,
        "أربع" to 4.0,
        "خمسة" to 5.0,
        "خمس" to 5.0,
        "ستة" to 6.0,
        "ست" to 6.0,
        "سبعة" to 7.0,
        "سبع" to 7.0,
        "تمانية" to 8.0,
        "ثمانية" to 8.0,
        "تمن" to 8.0,
        "تسعة" to 9.0,
        "تسع" to 9.0,
        "عشرة" to 10.0,
        "عشر" to 10.0,
        "حداشر" to 11.0,
        "أحد عشر" to 11.0,
        "اتناشر" to 12.0,
        "اثنا عشر" to 12.0,
        "تلتاشر" to 13.0,
        "اربعتاشر" to 14.0,
        "خمستاشر" to 15.0,
        "خمسة عشر" to 15.0,
        "ستاشر" to 16.0,
        "سبعتاشر" to 17.0,
        "تمنتاشر" to 18.0,
        "تسعتاشر" to 19.0,
        "عشرين" to 20.0,
        "تلاتين" to 30.0,
        "ثلاثين" to 30.0,
        "اربعين" to 40.0,
        "أربعين" to 40.0,
        "خمسين" to 50.0,
        "ستين" to 60.0,
        "سبعين" to 70.0,
        "تمانين" to 80.0,
        "ثمانين" to 80.0,
        "تسعين" to 90.0,
        "مية" to 100.0,
        "مائة" to 100.0,
        "مئة" to 100.0,
        "ميت" to 100.0,
        "ميتين" to 200.0,
        "مائتين" to 200.0,
        "مئتين" to 200.0,
        "تلتمية" to 300.0,
        "ثلاثمائة" to 300.0,
        "ربعمية" to 400.0,
        "أربعمائة" to 400.0,
        "خمسمية" to 500.0,
        "خمسمائة" to 500.0,
        "ستمية" to 600.0,
        "سبعمية" to 700.0,
        "تمنمية" to 800.0,
        "تسعمية" to 900.0,
        "الف" to 1000.0,
        "ألف" to 1000.0,
        "الفين" to 2000.0,
        "ألفين" to 2000.0
    )

    private val incomeActionKeywords = listOf(
        "قبضت", "مرتب", "راتب", "دخل", "استلمت", "جالي", "حصلت", "ارباح", "أرباح",
        "فريلانسر", "عمل حر", "مكافأة", "مكافاه", "هدية", "استرداد", "حافز", "عمولة",
        "salary", "income", "freelance", "bonus", "refund", "received", "earned"
    )

    private val expenseActionPrefixes = listOf(
        "اشتريت", "دفعت", "صرفت", "ركبت", "جبت", "حاسبت", "شحنت", "سددت", "كلت", "شربت",
        "bought", "paid", "spent"
    )

    private val refundKeywords = listOf(
        "استرداد", "مرجع", "مرتجع", "كاش باك", "refund", "cashback"
    )

    private val lineSplitRegex = Regex("""[\r\n؛;]+""")
    private val splitPattern = Regex(
        """(?<=(?:جنيه|ج\.م|ريال|ر\.س|دولار|درهم|د\.إ|يورو|قرش|EGP|USD|SAR|AED|EUR|\d))\s+(?:و\s*|كمان\s+|ثم\s+|،\s*)|(?:\s+و\s*(?=(?:اشتريت|دفعت|صرفت|ركبت|جبت|قبضت|حاسبت|شحنت|سددت)(?:\s|$)))"""
    )
    private val compoundRegex = Regex(
        """(?:بـ?\s*)?(واحد|اتنين|اثنين|تلاتة|ثلاثة|اربعة|أربعة|خمسة|ستة|سبعة|تمانية|ثمانية|تسعة)\s+و\s*(عشرين|تلاتين|ثلاثين|اربعين|أربعين|خمسين|ستين|سبعين|تمانين|ثمانين|تسعين)(?:\s+ونص|\s+وربع)?(?:\s*(?:جنيه|ج\.م|ريال|ر\.س|دولار|درهم|د\.إ|يورو|EGP|SAR|USD|AED|EUR))?"""
    )
    private val explicitCurrencyNumericRegex = Regex(
        """(?:بـ?\s*)?(\d+(?:\.\d{1,2})?)\s*(?:ونص|و\s*نص)?\s*(?:جنيه|ج\.م|جنية|ريال|ر\.س|دولار|درهم|د\.إ|يورو|\$|€|EGP|SAR|USD|AED|EUR)""",
        RegexOption.IGNORE_CASE
    )
    private val singleWordKeysPattern = numberWordsMap.keys
        .filter { it != "جنيه" }
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    private val wordAmountRegex = Regex(
        """(?:بـ?\s*($singleWordKeysPattern)(?:\s+ونص|\s+وربع)?(?:\s*(?:جنيه|ج\.م|جنية))?)|(?:($singleWordKeysPattern)(?:\s+ونص|\s+وربع)?\s*(?:جنيه|ج\.م|جنية))"""
    )
    private val biNumberRegex = Regex("""بـ?\s*(\d+(?:\.\d{1,2})?)""")
    private val trailingOrStandaloneNumber = Regex("""(?:^|\s)(\d+(?:\.\d{1,2})?)(?:\s*$)""")
    private val residualCurrencyRegex = Regex(
        """(?:^|\s)(?:جنيه|جنية|ج\.م|ريال|ر\.س|درهم|د\.إ|دولار|يورو|\$|€|EGP|USD|SAR|AED|EUR)(?=\s|$)""",
        RegexOption.IGNORE_CASE
    )
    private val leadingActionVerbRegexes = (expenseActionPrefixes + listOf("قبضت", "استلمت", "جالي")).map { verb ->
        Regex("""^\s*${Regex.escape(verb)}\s+(?:على\s+|في\s+)?""")
    }
    private val leadingPrepositionRegex = Regex("""^(?:على|في|بـ|ب)\s+""")
    private val trailingBiRegex = Regex("""\s+بـ?\s*$""")
    private val whitespaceRegex = Regex("""\s+""")
    private val tokenSplitRegex = Regex("""[^\p{L}\p{N}]+""")

    private val categoryKeywordDictionary: Map<String, List<String>> = mapOf(
        "أكل وشرب" to listOf(
            "طماطم", "جبنة", "عيش", "خبز", "أكل", "اكل", "شرب", "قهوة", "شاي", "عصير",
            "مطعم", "فطار", "غدا", "غداء", "عشا", "عشاء", "ساندوتش", "كشري", "فول", "طعمية",
            "لبن", "حليب", "فراخ", "لحمة", "سمك", "فاكهة", "خضار", "بطاطس", "مية", "نسكافيه",
            "سوبرماركت", "بقالة", "حلويات", "food", "coffee", "lunch", "dinner", "breakfast",
            "tomatoes", "cheese", "bread", "groceries", "restaurant"
        ),
        "طلبات البيت" to listOf(
            "للبيت", "البيت", "منظفات", "مسحوق", "صابون", "مناديل", "أنبوبة", "انبوبة",
            "إيجار", "ايجار", "صيانة", "مطبخ", "سوبر ماركت", "خزين", "home", "rent", "house"
        ),
        "مواصلات" to listOf(
            "تاكسي", "أوبر", "اوبر", "كريم", "ميكروباص", "مترو", "أتوبيس", "اتوبيس", "مواصلات",
            "بنزين", "ركبت", "قطر", "قطار", "توصيلة", "جراج", "ركنة", "taxi", "uber", "metro",
            "bus", "fuel", "gas", "transport"
        ),
        "دراسة" to listOf(
            "دراسة", "كورس", "كتاب", "كتب", "ملازم", "جامعة", "مدرسة", "دروس", "درس",
            "امتحان", "أدوات مكتبية", "كلية", "study", "course", "book", "tuition", "school"
        ),
        "صحة وأدوية" to listOf(
            "دواء", "أدوية", "ادوية", "صيدلية", "دكتور", "طبيب", "كشف", "تحاليل", "أشعة",
            "مستشفى", "علاج", "فيتامين", "مسكن", "medicine", "pharmacy", "doctor", "health"
        ),
        "رياضة" to listOf(
            "جيم", "رياضة", "تمرين", "نادي", "سباحة", "كورة", "حجز ملعب", "بروتين",
            "gym", "sport", "fitness", "workout"
        ),
        "ملابس" to listOf(
            "ملابس", "هدوم", "قميص", "بنطلون", "تيشيرت", "جزمة", "حذاء", "جاكيت", "فستان",
            "شراب", "كوتشي", "clothes", "shirt", "shoes", "jacket"
        ),
        "ترفيه" to listOf(
            "ترفيه", "سينما", "خروجة", "لعبة", "رحلة", "مصيف", "كافيه", "بلايستيشن", "تذكرة",
            "cinema", "movie", "game", "outing", "entertainment"
        ),
        "فواتير واشتراكات" to listOf(
            "فاتورة", "فواتير", "كهرباء", "غاز", "مياه", "إنترنت", "انترنت", "نت", "باقة",
            "موبايل", "رصيد", "فودافون", "اتصالات", "اورنج", "وي", "اشتراك", "نتفليكس",
            "bill", "internet", "electricity", "water", "subscription", "mobile"
        ),
        "مرتب" to listOf("مرتب", "راتب", "قبضت", "معاش", "salary", "payroll"),
        "عمل حر" to listOf("عمل حر", "فريلانسر", "مشروع", "عميل", "freelance", "client"),
        "مكافآت وهدايا" to listOf("هدية", "مكافأة", "مكافاه", "عيدية", "حافز", "gift", "bonus"),
        "استرداد" to listOf("استرداد", "مرجع", "مرتجع", "كاش باك", "refund", "cashback")
    )

    /**
     * Parses free-form Egyptian Arabic, Arabic, or English text into validated transaction drafts.
     */
    fun parseNaturalText(
        rawText: String,
        todayIsoDate: String,
        defaultCurrency: String,
        eligibleCategories: List<CategoryEntity>,
        learnedRules: List<CategoryRuleEntity>,
        refundAccountingMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET
    ): AiParseResult {
        val activeCategories = eligibleCategories.filter { !it.isArchived }
        val normalizedInput = MoneyUtils.normalizeDigits(rawText.trim())
        if (normalizedInput.isBlank()) {
            return AiParseResult(
                proposals = emptyList(),
                needsClarification = true,
                clarificationQuestion = "يرجى كتابة أو نطق معاملة واحدة على الأقل.",
                providerSourceLabel = "المحلل المحلي",
                errorMessage = "النص المدخل فارغ."
            )
        }

        val segments = splitIntoTransactionSegments(normalizedInput)
        val proposals = mutableListOf<ProposedTransactionDraft>()

        for (segment in segments) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue
            val draft = parseSingleSegment(
                segment = trimmed,
                todayIsoDate = todayIsoDate,
                defaultCurrency = defaultCurrency,
                activeCategories = activeCategories,
                learnedRules = learnedRules,
                refundAccountingMode = refundAccountingMode
            )
            if (draft != null) {
                proposals.add(draft)
            }
        }

        val hasAmbiguous = proposals.any { it.isAmbiguous || it.amountMinorUnits == null }
        val clarification = when {
            proposals.isEmpty() -> "لم نتمكن من استخراج معاملات مالية واضحة. يرجى توضيح الصنف والمبلغ."
            hasAmbiguous -> "بعض المعاملات تحتاج إلى تأكيد المبلغ أو التصنيف قبل الحفظ."
            else -> null
        }

        return AiParseResult(
            proposals = proposals,
            needsClarification = proposals.isEmpty() || hasAmbiguous,
            clarificationQuestion = clarification,
            providerSourceLabel = "محلل مصروفي الذكي (محلي)"
        )
    }

    /**
     * Splits multi-line or compound Arabic/English sentences into discrete transaction clauses
     * without breaking compound Arabic numbers (e.g. "خمسة وأربعين") or category phrases ("أكل وشرب").
     * Uses Unicode-safe whitespace boundaries rather than ASCII \b.
     */
    fun splitIntoTransactionSegments(text: String): List<String> {
        val lines = text.split(lineSplitRegex)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<String>()

        for (line in lines) {
            val protectedLine = protectCompoundArabicNumbers(line)
            val parts = protectedLine.split(splitPattern)
            for (part in parts) {
                val restored = restoreProtectedNumbers(part).trim()
                    .trim(',', '،', '.', '-')
                    .trim()
                if (restored.isNotBlank()) {
                    result.add(restored)
                }
            }
        }
        return result
    }

    private fun protectCompoundArabicNumbers(input: String): String {
        var out = input
        val units = listOf("واحد", "اتنين", "اثنين", "تلاتة", "ثلاثة", "اربعة", "أربعة", "خمسة", "ستة", "سبعة", "تمانية", "ثمانية", "تسعة")
        val tens = listOf("عشرين", "تلاتين", "ثلاثين", "اربعين", "أربعين", "خمسين", "ستين", "سبعين", "تمانين", "ثمانين", "تسعين")
        for (u in units) {
            for (t in tens) {
                out = out.replace("$u و$t", "${u}_AND_${t}")
                out = out.replace("$u و $t", "${u}_AND_${t}")
            }
        }
        out = out.replace("أكل وشرب", "أكل_AND_شرب")
        out = out.replace("اكل وشرب", "اكل_AND_شرب")
        out = out.replace("صحة وأدوية", "صحة_AND_أدوية")
        out = out.replace("فواتير واشتراكات", "فواتير_AND_اشتراكات")
        out = out.replace("مكافآت وهدايا", "مكافآت_AND_هدايا")
        out = out.replace("ونص", "_AND_HALF")
        out = out.replace("و نص", "_AND_HALF")
        out = out.replace("وربع", "_AND_QUARTER")
        return out
    }

    private fun restoreProtectedNumbers(input: String): String {
        return input
            .replace("_AND_HALF", " ونص")
            .replace("_AND_QUARTER", " وربع")
            .replace("_AND_", " و")
    }

    private fun isRefundSegment(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return refundKeywords.any { containsWholePhrase(lower, it) }
    }

    private fun parseSingleSegment(
        segment: String,
        todayIsoDate: String,
        defaultCurrency: String,
        activeCategories: List<CategoryEntity>,
        learnedRules: List<CategoryRuleEntity>,
        refundAccountingMode: RefundAccountingMode
    ): ProposedTransactionDraft? {
        val txType = detectTransactionType(segment)
        val extractedAmount = extractMonetaryAmount(segment)
        val cleanDescription = extractCleanDescription(segment, extractedAmount?.matchedSpan)

        if (cleanDescription.isBlank() && extractedAmount == null) {
            return null
        }

        val finalDesc = cleanDescription.ifBlank {
            if (txType == TransactionType.INCOME) "دخل" else "مصروف"
        }

        val amountMinor = extractedAmount?.minorUnits
        val isAmbiguousAmount = amountMinor == null || amountMinor <= 0L || extractedAmount.isUncertain

        val isRefund = txType == TransactionType.INCOME && isRefundSegment(segment)
        val isRefundOffset = isRefund && refundAccountingMode == RefundAccountingMode.EXPENSE_OFFSET

        val matchedCategory = if (isRefundOffset) {
            // Try matching the original expense category being refunded first, falling back to income refund category
            matchBestCategory(
                description = finalDesc,
                fullSegment = segment,
                type = TransactionType.EXPENSE,
                activeCategories = activeCategories,
                learnedRules = learnedRules
            ) ?: matchBestCategory(
                description = finalDesc,
                fullSegment = segment,
                type = TransactionType.INCOME,
                activeCategories = activeCategories,
                learnedRules = learnedRules
            )
        } else {
            matchBestCategory(
                description = finalDesc,
                fullSegment = segment,
                type = txType,
                activeCategories = activeCategories,
                learnedRules = learnedRules
            )
        }

        val isAmbiguousCategory = matchedCategory == null
        val confidence = when {
            isAmbiguousAmount -> 0.40f
            isAmbiguousCategory -> 0.65f
            extractedAmount?.fromWords == true -> 0.92f
            else -> 0.96f
        }

        val ambiguityReason = when {
            amountMinor == null || amountMinor <= 0L -> "المبلغ غير محدد أو غير واضح"
            extractedAmount.isUncertain -> "يرجى تأكيد قيمة المبلغ"
            isAmbiguousCategory -> "لم يتم العثور على تصنيف مطابق، يرجى اختيار التصنيف"
            else -> null
        }

        return ProposedTransactionDraft(
            description = finalDesc,
            amountText = if (amountMinor != null && amountMinor > 0L) {
                MoneyUtils.formatPlainMajorAmount(amountMinor)
            } else {
                ""
            },
            amountMinorUnits = amountMinor,
            currencyCode = extractedAmount?.currencyCode ?: defaultCurrency,
            type = txType,
            isoDate = todayIsoDate,
            categoryId = matchedCategory?.id,
            notes = "",
            confidence = confidence,
            isAmbiguous = isAmbiguousAmount || isAmbiguousCategory,
            ambiguityReason = ambiguityReason,
            isRefundOffset = isRefundOffset,
            suggestedCategoryId = matchedCategory?.id,
            userConfirmedCategory = false
        )
    }

    private data class ExtractedAmount(
        val minorUnits: Long,
        val matchedSpan: String,
        val currencyCode: String? = null,
        val fromWords: Boolean = false,
        val isUncertain: Boolean = false
    )

    private fun extractMonetaryAmount(segment: String): ExtractedAmount? {
        val normalized = MoneyUtils.normalizeDigits(segment)

        // 1. Check compound Arabic word numbers first (e.g. "بخمسة وأربعين جنيه", "خمسة وأربعين جنيه")
        val compoundMatch = compoundRegex.find(normalized)
        if (compoundMatch != null) {
            val unitWord = compoundMatch.groupValues[1]
            val tenWord = compoundMatch.groupValues[2]
            val unitVal = numberWordsMap[unitWord] ?: 0.0
            val tenVal = numberWordsMap[tenWord] ?: 0.0
            val extra = when {
                compoundMatch.value.contains("ونص") -> 0.5
                compoundMatch.value.contains("وربع") -> 0.25
                else -> 0.0
            }
            val total = unitVal + tenVal + extra
            if (total > 0) {
                return ExtractedAmount(
                    minorUnits = kotlin.math.round(total * 100).toLong(),
                    matchedSpan = compoundMatch.value,
                    currencyCode = detectCurrencyInSpan(compoundMatch.value),
                    fromWords = true
                )
            }
        }

        // 2. Check explicit numeric figures with optional 'بـ' or currency (e.g. "بـ300 جنيه", "45 جنيه")
        val explicitMatch = explicitCurrencyNumericRegex.find(normalized)
        if (explicitMatch != null) {
            val baseVal = explicitMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val half = if (explicitMatch.value.contains("نص")) 0.5 else 0.0
            val total = baseVal + half
            if (total > 0) {
                return ExtractedAmount(
                    minorUnits = kotlin.math.round(total * 100).toLong(),
                    matchedSpan = explicitMatch.value,
                    currencyCode = detectCurrencyInSpan(explicitMatch.value)
                )
            }
        }

        // 3. Single Arabic number word with 'بـ' or 'جنيه' (e.g. "بعشرين جنيه", "عشرين جنيه", "بعشرة جنيه")
        val wordMatch = wordAmountRegex.find(normalized)
        if (wordMatch != null) {
            val word = wordMatch.groupValues[1].ifEmpty { wordMatch.groupValues[2] }
            val base = numberWordsMap[word] ?: 0.0
            val extra = when {
                wordMatch.value.contains("ونص") -> 0.5
                wordMatch.value.contains("وربع") -> 0.25
                else -> 0.0
            }
            val total = base + extra
            if (total > 0) {
                return ExtractedAmount(
                    minorUnits = kotlin.math.round(total * 100).toLong(),
                    matchedSpan = wordMatch.value,
                    fromWords = true
                )
            }
        }

        // 4. Number prefixed with بـ or ب (e.g., "بـ 300", "ب20")
        val biMatch = biNumberRegex.find(normalized)
        if (biMatch != null) {
            val value = biMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            if (value > 0) {
                return ExtractedAmount(
                    minorUnits = kotlin.math.round(value * 100).toLong(),
                    matchedSpan = biMatch.value
                )
            }
        }

        // 5. Shorthand line item: "<description> <number>" (e.g. "طماطم 20", "دفعت إيجار 2000")
        val trailingMatch = trailingOrStandaloneNumber.find(normalized.trim())
        if (trailingMatch != null) {
            val value = trailingMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            if (value > 0) {
                return ExtractedAmount(
                    minorUnits = kotlin.math.round(value * 100).toLong(),
                    matchedSpan = trailingMatch.value
                )
            }
        }

        return null
    }

    private fun detectCurrencyInSpan(span: String): String? {
        val upper = span.uppercase(Locale.US)
        return when {
            upper.contains("USD") || span.contains("دولار") || span.contains("$") -> "USD"
            upper.contains("SAR") || span.contains("ريال") || span.contains("ر.س") -> "SAR"
            upper.contains("AED") || span.contains("درهم") || span.contains("د.إ") -> "AED"
            upper.contains("EUR") || span.contains("يورو") || span.contains("€") -> "EUR"
            upper.contains("EGP") || span.contains("جنيه") || span.contains("ج.م") -> "EGP"
            else -> null
        }
    }

    private fun extractCleanDescription(segment: String, matchedAmountSpan: String?): String {
        var cleaned = segment
        if (!matchedAmountSpan.isNullOrBlank()) {
            cleaned = cleaned.replaceFirst(matchedAmountSpan, " ")
        }
        // Remove residual standalone currency words using Unicode-safe whitespace boundaries
        cleaned = cleaned.replace(residualCurrencyRegex, " ")
        for (verbRegex in leadingActionVerbRegexes) {
            cleaned = cleaned.replace(verbRegex, "")
        }
        cleaned = cleaned.replace(leadingPrepositionRegex, "")
        cleaned = cleaned.replace(trailingBiRegex, "")
        return cleaned.replace(whitespaceRegex, " ").trim()
    }

    fun detectTransactionType(text: String): TransactionType {
        val lower = text.lowercase(Locale.ROOT)
        for (kw in incomeActionKeywords) {
            if (containsWholePhrase(lower, kw)) {
                return TransactionType.INCOME
            }
        }
        return TransactionType.EXPENSE
    }

    internal fun containsWholePhrase(text: String, phrase: String): Boolean {
        val cleanPhrase = phrase.lowercase(Locale.ROOT).trim()
        if (cleanPhrase.isEmpty()) return false
        val textTokens = tokenSplitRegex.split(text.lowercase(Locale.ROOT).trim()).filter { it.isNotEmpty() }
        val phraseTokens = tokenSplitRegex.split(cleanPhrase).filter { it.isNotEmpty() }
        if (phraseTokens.isEmpty() || textTokens.size < phraseTokens.size) return false
        for (i in 0..(textTokens.size - phraseTokens.size)) {
            var allMatch = true
            for (j in phraseTokens.indices) {
                if (textTokens[i + j] != phraseTokens[j]) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) return true
        }
        return false
    }

    /**
     * Strictly selects from the user's active, non-archived categories.
     * Checks learned user rules first, then exact category name match, then semantic dictionary.
     * Returns null if no suitable active category exists (never guesses a random category).
     */
    fun matchBestCategory(
        description: String,
        fullSegment: String,
        type: TransactionType,
        activeCategories: List<CategoryEntity>,
        learnedRules: List<CategoryRuleEntity>
    ): CategoryEntity? {
        val eligibleForType = activeCategories.filter { !it.isArchived && it.type == type }
        if (eligibleForType.isEmpty()) return null
        val byId = eligibleForType.associateBy { it.id }

        val normalizedDesc = description.lowercase(Locale.ROOT).trim()
        val normalizedFull = fullSegment.lowercase(Locale.ROOT).trim()

        // 1. Check user's learned category rules first (whole-word/phrase boundary, min length 3, longest first)
        val sortedValidRules = learnedRules
            .filter { it.transactionType == type && it.keyword.trim().length >= 3 }
            .sortedByDescending { it.keyword.trim().length }

        for (rule in sortedValidRules) {
            val kw = rule.keyword.lowercase(Locale.ROOT).trim()
            if (normalizedDesc == kw ||
                containsWholePhrase(normalizedDesc, kw) ||
                containsWholePhrase(normalizedFull, kw)
            ) {
                val cat = byId[rule.categoryId]
                if (cat != null) return cat
            }
        }

        // 2. Direct match with an existing active category name
        for (cat in eligibleForType) {
            val catNameLower = cat.name.lowercase(Locale.ROOT).trim()
            if (catNameLower.isNotEmpty() && catNameLower != "أخرى" && catNameLower != "دخل آخر") {
                if (normalizedDesc == catNameLower ||
                    containsWholePhrase(normalizedDesc, catNameLower) ||
                    containsWholePhrase(normalizedFull, catNameLower)
                ) {
                    return cat
                }
            }
        }

        // 3. Dictionary keyword matching against active categories by name
        for ((canonicalCatName, keywords) in categoryKeywordDictionary) {
            val matchingCategory = eligibleForType.firstOrNull {
                it.name.trim() == canonicalCatName || it.name.contains(canonicalCatName)
            } ?: continue

            for (kw in keywords) {
                if (containsWholePhrase(normalizedDesc, kw) || containsWholePhrase(normalizedFull, kw)) {
                    return matchingCategory
                }
            }
        }

        return null
    }

    /**
     * Validates a raw JSON string returned by an external AI model (Gemini or OpenRouter)
     * against the strict Masroofi schema and current database categories using Moshi JsonReader.
     */
    fun validateAndMapAiJsonResponse(
        rawJsonResponse: String,
        todayIsoDate: String,
        defaultCurrency: String,
        eligibleCategories: List<CategoryEntity>,
        learnedRules: List<CategoryRuleEntity>,
        providerLabel: String,
        refundAccountingMode: RefundAccountingMode = RefundAccountingMode.EXPENSE_OFFSET
    ): Result<AiParseResult> {
        val activeCategories = eligibleCategories.filter { !it.isArchived }
        val activeCategoryMap = activeCategories.associateBy { it.id }

        val cleanedJson = extractJsonObjectString(rawJsonResponse)
            ?: return Result.failure(AiServiceException.MalformedResponse())

        return try {
            val buffer = Buffer().writeUtf8(cleanedJson)
            val reader = JsonReader.of(buffer)

            var modelNeedsClarification = false
            var modelClarificationQ: String? = null
            val drafts = mutableListOf<ProposedTransactionDraft>()

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "needsClarification" -> {
                        modelNeedsClarification = if (reader.peek() == JsonReader.Token.BOOLEAN) {
                            reader.nextBoolean()
                        } else {
                            reader.skipValue()
                            false
                        }
                    }
                    "clarificationQuestion" -> {
                        modelClarificationQ = readNullableString(reader)?.takeIf {
                            it.isNotBlank() && it != "null"
                        }
                    }
                    "transactions" -> {
                        if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val parsedDraft = parseSingleJsonTransaction(
                                    reader = reader,
                                    todayIsoDate = todayIsoDate,
                                    defaultCurrency = defaultCurrency,
                                    activeCategories = activeCategories,
                                    activeCategoryMap = activeCategoryMap,
                                    learnedRules = learnedRules,
                                    refundAccountingMode = refundAccountingMode
                                )
                                if (parsedDraft != null) {
                                    drafts.add(parsedDraft)
                                }
                            }
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (drafts.isEmpty() && !modelNeedsClarification) {
                return Result.failure(AiServiceException.EmptyResponse())
            }

            val anyAmbiguous = modelNeedsClarification || drafts.any { it.isAmbiguous }
            val finalQuestion = modelClarificationQ ?: if (anyAmbiguous) {
                "يرجى مراجعة المعاملات المقترحة وتأكيد الحقول غير المكتملة قبل الحفظ."
            } else {
                null
            }

            Result.success(
                AiParseResult(
                    proposals = drafts,
                    needsClarification = anyAmbiguous,
                    clarificationQuestion = finalQuestion,
                    providerSourceLabel = providerLabel
                )
            )
        } catch (e: AiServiceException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(AiServiceException.MalformedResponse())
        }
    }

    private fun parseSingleJsonTransaction(
        reader: JsonReader,
        todayIsoDate: String,
        defaultCurrency: String,
        activeCategories: List<CategoryEntity>,
        activeCategoryMap: Map<String, CategoryEntity>,
        learnedRules: List<CategoryRuleEntity>,
        refundAccountingMode: RefundAccountingMode
    ): ProposedTransactionDraft? {
        var description = ""
        var rawAmount = ""
        var rawCurrency: String? = null
        var rawType = "EXPENSE"
        var rawDate = todayIsoDate
        var rawCatId: String? = null
        var notes = ""
        var confidence = 0.85f

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "description" -> description = readNullableString(reader).orEmpty().trim()
                "amount" -> rawAmount = readNullableString(reader).orEmpty().trim()
                "currency" -> rawCurrency = readNullableString(reader)?.trim()
                "type" -> rawType = readNullableString(reader).orEmpty().uppercase(Locale.US).trim()
                "date" -> rawDate = readNullableString(reader).orEmpty().trim()
                "categoryId" -> rawCatId = readNullableString(reader)?.trim()?.takeIf {
                    it.isNotBlank() && it != "null"
                }
                "notes" -> notes = readNullableString(reader)?.takeIf { it != "null" }.orEmpty().trim()
                "confidence" -> {
                    confidence = when (reader.peek()) {
                        JsonReader.Token.NUMBER -> reader.nextDouble().toFloat().coerceIn(0f, 1f)
                        JsonReader.Token.STRING -> reader.nextString().toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.85f
                        else -> {
                            reader.skipValue()
                            0.85f
                        }
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val type = when (rawType) {
            "EXPENSE" -> TransactionType.EXPENSE
            "INCOME" -> TransactionType.INCOME
            else -> throw AiServiceException.MalformedResponse(
                "نوع معاملة غير مدعوم في استجابة الذكاء الاصطناعي: $rawType"
            )
        }

        val validDateEpoch = MoneyUtils.isoDateToEpochMillis(rawDate.ifEmpty { todayIsoDate })
        val finalIsoDate = if (validDateEpoch != null) {
            MoneyUtils.epochMillisToIsoDate(validDateEpoch)
        } else {
            todayIsoDate
        }

        val amountMinor = MoneyUtils.parseAmountToMinorUnits(rawAmount)

        val isRefund = type == TransactionType.INCOME && isRefundSegment(description)
        val isRefundOffset = isRefund && refundAccountingMode == RefundAccountingMode.EXPENSE_OFFSET

        // If the AI model explicitly returned a non-null categoryId that is NOT in activeCategoryMap
        // (e.g. an invented ID or an archived category ID), treat it strictly as uncategorized (null)
        val verifiedCategory: CategoryEntity? = if (rawCatId != null) {
            activeCategoryMap[rawCatId]?.takeIf {
                !it.isArchived && (it.type == type || (isRefundOffset && it.type == TransactionType.EXPENSE))
            }
        } else if (isRefundOffset) {
            matchBestCategory(
                description = description,
                fullSegment = description,
                type = TransactionType.EXPENSE,
                activeCategories = activeCategories,
                learnedRules = learnedRules
            ) ?: matchBestCategory(
                description = description,
                fullSegment = description,
                type = TransactionType.INCOME,
                activeCategories = activeCategories,
                learnedRules = learnedRules
            )
        } else {
            matchBestCategory(
                description = description,
                fullSegment = description,
                type = type,
                activeCategories = activeCategories,
                learnedRules = learnedRules
            )
        }

        val isMissingOrInvalidAmount = amountMinor == null || amountMinor <= 0L
        val isUncategorized = verifiedCategory == null
        val isLowConfidence = confidence < 0.75f
        val isAmbiguous = isMissingOrInvalidAmount || isUncategorized || isLowConfidence

        val ambiguityReason = when {
            isMissingOrInvalidAmount -> "المبلغ مفقود أو غير صالح، يرجى إدخاله يدوياً"
            isUncategorized -> "التصنيف غير محدد، يرجى اختيار تصنيف مناسب"
            isLowConfidence -> "درجة الثقة منخفضة، يرجى مراجعة البيانات"
            else -> null
        }

        if (description.isBlank() && amountMinor == null) return null

        return ProposedTransactionDraft(
            description = description.ifBlank { "معاملة بدون وصف" },
            amountText = if (amountMinor != null && amountMinor > 0L) {
                MoneyUtils.formatPlainMajorAmount(amountMinor)
            } else {
                ""
            },
            amountMinorUnits = amountMinor,
            currencyCode = rawCurrency?.takeIf { it.isNotBlank() && it != "null" } ?: defaultCurrency,
            type = type,
            isoDate = finalIsoDate,
            categoryId = verifiedCategory?.id,
            notes = notes,
            confidence = confidence,
            isAmbiguous = isAmbiguous,
            ambiguityReason = ambiguityReason,
            isRefundOffset = isRefundOffset,
            suggestedCategoryId = verifiedCategory?.id,
            userConfirmedCategory = false
        )
    }

    private fun readNullableString(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                null
            }
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.NUMBER -> reader.nextDouble().toString()
            JsonReader.Token.BOOLEAN -> reader.nextBoolean().toString()
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    private fun extractJsonObjectString(raw: String): String? {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) return null
        return trimmed.substring(firstBrace, lastBrace + 1)
    }
}
