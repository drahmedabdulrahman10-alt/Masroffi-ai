package com.example.domain.ai

import com.example.domain.MoneyUtils
import com.example.domain.QuickDateOption
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * Structured result of extracting and resolving an explicit or relative date expression
 * from a natural-language Egyptian Arabic, Standard Arabic, or English transaction segment.
 *
 * - When a specific calendar date is deterministically resolved:
 *   [resolvedDate] and [resolvedIsoDate] are non-null, [isAmbiguous] is false, and [isInvalid] is false.
 * - When an expression refers only to an entire period (e.g. "الأسبوع اللي فات", "الشهر اللي فات",
 *   "السنة اللي فاتت", "في نص الشهر", "آخر الشهر", "في بداية الأسبوع") or is vague ("بعد كام يوم"):
 *   [resolvedDate] and [resolvedIsoDate] are null, [isAmbiguous] is true, [periodStart]/[periodEnd]
 *   capture the period boundaries where applicable, and [diagnosticMessage] explains why user
 *   confirmation is required without inventing an arbitrary day.
 * - When an expression specifies an impossible calendar date (e.g. "يوم 31 الشهر اللي فات" in a
 *   30-day month or "2025-02-29" in a non-leap year):
 *   [resolvedDate] and [resolvedIsoDate] are null, [isInvalid] is true, [isAmbiguous] is true,
 *   and [diagnosticMessage] explains the calendar validation error.
 */
data class DateResolutionResult(
    val resolvedDate: LocalDate?,
    val resolvedIsoDate: String?,
    val matchedSpan: String,
    val normalizedExpression: String,
    val isExplicit: Boolean,
    val isRelative: Boolean,
    val isAmbiguous: Boolean = false,
    val isInvalid: Boolean = false,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val diagnosticMessage: String? = null
) {
    val isResolved: Boolean
        get() = resolvedDate != null && !resolvedIsoDate.isNullOrBlank() && !isAmbiguous && !isInvalid
}

/**
 * Deterministic Egyptian Arabic, Standard Arabic, and English date expression resolver.
 *
 * Uses a single caller-supplied [referenceDate] per parsing operation (never calling `LocalDate.now()`
 * internally) and follows Masroofi AI's Saturday-first (`DayOfWeek.SATURDAY` .. `DayOfWeek.FRIDAY`)
 * Arabic calendar convention.
 */
object EgyptianArabicDateResolver {

    private const val BOUNDARY_START = """(?<=^|\s|[،,.;؛:()\-])"""
    private const val BOUNDARY_END = """(?=\s|$|[،,.;؛:()\-])"""

    private val countWordsMap: Map<String, Int> = mapOf(
        "واحد" to 1, "واحدة" to 1, "واحده" to 1,
        "اتنين" to 2, "اثنين" to 2, "إثنين" to 2, "اثنتين" to 2,
        "تلات" to 3, "تلاتة" to 3, "تلاته" to 3, "ثلاث" to 3, "ثلاثة" to 3, "ثلاثه" to 3,
        "اربع" to 4, "أربع" to 4, "اربعة" to 4, "أربعة" to 4, "اربعه" to 4, "أربعه" to 4,
        "خمس" to 5, "خمسة" to 5, "خمسه" to 5,
        "ست" to 6, "ستة" to 6, "سته" to 6,
        "سبع" to 7, "سبعة" to 7, "سبعه" to 7,
        "تمن" to 8, "تمانية" to 8, "تمانيه" to 8, "ثمان" to 8, "ثماني" to 8, "ثمانية" to 8, "ثمانيه" to 8,
        "تسع" to 9, "تسعة" to 9, "تسعه" to 9,
        "عشر" to 10, "عشرة" to 10, "عشره" to 10,
        "حداشر" to 11, "أحد عشر" to 11, "احد عشر" to 11, "إحدى عشر" to 11,
        "اتناشر" to 12, "اثنا عشر" to 12, "إثنا عشر" to 12,
        "تلتاشر" to 13, "ثلاثة عشر" to 13, "تلاتة عشر" to 13,
        "اربعتاشر" to 14, "أربعة عشر" to 14, "اربعة عشر" to 14,
        "خمستاشر" to 15, "خمسة عشر" to 15,
        "ستاشر" to 16, "ستة عشر" to 16,
        "سبعتاشر" to 17, "سبعة عشر" to 17,
        "تمنتاشر" to 18, "ثمانية عشر" to 18, "تمانية عشر" to 18,
        "تسعتاشر" to 19, "تسعة عشر" to 19,
        "عشرين" to 20,
        "واحد وعشرين" to 21, "اتنين وعشرين" to 22, "اثنين وعشرين" to 22,
        "تلاتة وعشرين" to 23, "ثلاثة وعشرين" to 23, "اربعة وعشرين" to 24, "أربعة وعشرين" to 24,
        "خمسة وعشرين" to 25, "ستة وعشرين" to 26, "سبعة وعشرين" to 27,
        "تمانية وعشرين" to 28, "ثمانية وعشرين" to 28, "تسعة وعشرين" to 29,
        "تلاتين" to 30, "ثلاثين" to 30,
        "واحد وتلاتين" to 31, "واحد وثلاثين" to 31
    )

    private val countTokenPattern: String = run {
        val words = countWordsMap.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        """(?:\d{1,3}|$words)"""
    }

    private val arabicMonthsMap: Map<String, Int> = mapOf(
        "يناير" to 1,
        "فبراير" to 2,
        "مارس" to 3,
        "أبريل" to 4, "ابريل" to 4, "إبريل" to 4,
        "مايو" to 5,
        "يونيو" to 6, "يونيه" to 6,
        "يوليو" to 7, "يوليه" to 7,
        "أغسطس" to 8, "اغسطس" to 8,
        "سبتمبر" to 9,
        "أكتوبر" to 10, "اكتوبر" to 10,
        "نوفمبر" to 11,
        "ديسمبر" to 12
    )

    private val arabicMonthNamesPattern: String = arabicMonthsMap.keys
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }

    private val allWeekdayMap: Map<String, DayOfWeek> = mapOf(
        "السبت" to DayOfWeek.SATURDAY, "سبت" to DayOfWeek.SATURDAY,
        "الأحد" to DayOfWeek.SUNDAY, "الاحد" to DayOfWeek.SUNDAY, "الإحد" to DayOfWeek.SUNDAY,
        "الحد" to DayOfWeek.SUNDAY, "أحد" to DayOfWeek.SUNDAY, "احد" to DayOfWeek.SUNDAY, "حد" to DayOfWeek.SUNDAY,
        "الاثنين" to DayOfWeek.MONDAY, "الإثنين" to DayOfWeek.MONDAY, "الأثنين" to DayOfWeek.MONDAY,
        "الاتنين" to DayOfWeek.MONDAY, "الإتنين" to DayOfWeek.MONDAY, "الأتنين" to DayOfWeek.MONDAY,
        "اثنين" to DayOfWeek.MONDAY, "إثنين" to DayOfWeek.MONDAY, "اتنين" to DayOfWeek.MONDAY,
        "الثلاثاء" to DayOfWeek.TUESDAY, "التلاتاء" to DayOfWeek.TUESDAY, "التلات" to DayOfWeek.TUESDAY,
        "الثلاث" to DayOfWeek.TUESDAY, "ثلاثاء" to DayOfWeek.TUESDAY, "تلات" to DayOfWeek.TUESDAY,
        "الأربعاء" to DayOfWeek.WEDNESDAY, "الاربعاء" to DayOfWeek.WEDNESDAY, "الإربعاء" to DayOfWeek.WEDNESDAY,
        "الأربع" to DayOfWeek.WEDNESDAY, "الاربع" to DayOfWeek.WEDNESDAY, "الإربع" to DayOfWeek.WEDNESDAY,
        "أربعاء" to DayOfWeek.WEDNESDAY, "اربعاء" to DayOfWeek.WEDNESDAY, "أربع" to DayOfWeek.WEDNESDAY, "اربع" to DayOfWeek.WEDNESDAY,
        "الخميس" to DayOfWeek.THURSDAY, "خميس" to DayOfWeek.THURSDAY,
        "الجمعة" to DayOfWeek.FRIDAY, "الجمعه" to DayOfWeek.FRIDAY, "جمعة" to DayOfWeek.FRIDAY, "جمعه" to DayOfWeek.FRIDAY
    )

    private val allWeekdayNamesPattern: String = allWeekdayMap.keys
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }

    /**
     * Unambiguous definite weekday names that can appear without a leading "يوم" or trailing
     * "اللي فات" / "الجاي" qualifier (excludes short homographs like "الحد", "الأربع", "الثلاث").
     */
    private val standaloneDefiniteWeekdayNames = listOf(
        "السبت",
        "الأحد", "الاحد", "الإحد",
        "الاثنين", "الإثنين", "الأثنين", "الاتنين", "الإتنين", "الأتنين",
        "الثلاثاء", "التلاتاء", "التلات",
        "الأربعاء", "الاربعاء", "الإربعاء",
        "الخميس",
        "الجمعة", "الجمعه"
    ).sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    // --- Stage 1: Explicit Calendar Dates ---

    private val explicitYearFirstDateRegex = Regex(
        """$BOUNDARY_START(?:(?:في\s+يوم|بتاريخ|في\s+تاريخ|يوم|في)\s+)?(\d{4})[-/](\d{1,2})[-/](\d{1,2})$BOUNDARY_END"""
    )

    private val explicitDayFirstDateRegex = Regex(
        """$BOUNDARY_START(?:(?:في\s+يوم|بتاريخ|في\s+تاريخ|يوم|في)\s+)?(\d{1,2})[-/](\d{1,2})[-/](\d{4})$BOUNDARY_END"""
    )

    private val explicitDayNamedMonthRegex = Regex(
        """$BOUNDARY_START(?:(?:في\s+يوم|بتاريخ|يوم|في)\s+)?($countTokenPattern)\s+(?:من\s+)?(?:شهر\s+)?($arabicMonthNamesPattern)(?:\s+(?:سنة\s+|عام\s+)?(\d{4}|السنة\s+اللي\s+فاتت|السنه\s+اللي\s+فاتت|السنة\s+الماضية|العام\s+الماضي|السنة\s+دي|السنة\s+دى|السنة\s+الحالية|هذا\s+العام|السنة\s+الجاية|السنه\s+الجايه|السنة\s+القادمة|العام\s+القادم))?$BOUNDARY_END"""
    )

    // --- Stage 2: Explicit Day Within Relative Month ---

    private val dayInRelativeMonthRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?يوم\s+($countTokenPattern)\s+(?:في\s+|من\s+)?(الشهر\s+اللي\s+فات|الشهر\s+الماضي|الشهر\s+السابق|الشهر\s+ده|الشهر\s+الحالي|هذا\s+الشهر|الشهر\s+الجاي|الشهر\s+القادم|الشهر\s+المقبل)$BOUNDARY_END"""
    )

    // --- Stage 3: Anchored Month Boundaries & Sub-Month Periods ---

    private val firstDayOfPrevMonthRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:يوم\s+)?(?:أول|اول|بداية)\s+(?:يوم\s+(?:في\s+|من\s+)?)?(?:الشهر\s+اللي\s+فات|الشهر\s+الماضي|الشهر\s+السابق)$BOUNDARY_END"""
    )

    private val lastDayOfPrevMonthRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:يوم\s+)?(?:آخر|اخر|نهاية)\s+(?:يوم\s+(?:في\s+|من\s+)?)?(?:الشهر\s+اللي\s+فات|الشهر\s+الماضي|الشهر\s+السابق)$BOUNDARY_END"""
    )

    private val firstDayOfNextMonthRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:يوم\s+)?(?:أول|اول|بداية)\s+(?:يوم\s+(?:في\s+|من\s+)?)?(?:الشهر\s+الجاي|الشهر\s+القادم|الشهر\s+المقبل)$BOUNDARY_END"""
    )

    private val firstDayOfCurrentMonthRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:يوم\s+)?(?:أول|اول|بداية)\s+(?:يوم\s+(?:في\s+|من\s+)?)?(?:الشهر(?:\s+ده|\s+الحالي)?|هذا\s+الشهر)$BOUNDARY_END"""
    )

    private val midMonthAmbiguousRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:نص|نصف|منتصف)\s+الشهر(?:\s+(?:اللي\s+فات|الماضي|السابق|ده|الحالي|الجاي|القادم|المقبل))?$BOUNDARY_END"""
    )

    private val endOfCurrentMonthAmbiguousRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:آخر|اخر|نهاية)\s+(?:الشهر(?:\s+ده|\s+الحالي|\s+الجاي|\s+القادم)?|هذا\s+الشهر)$BOUNDARY_END"""
    )

    // --- Stage 4: Exact Relative Offsets ("من / منذ ...") ---

    private const val OPTIONAL_PAST_VERB_SUFFIX = """(?:\s+(?:فاتوا|فاتت|فات|مضوا|مضت|مضى))?"""

    /**
     * Prevents single-unit offsets ("من يوم", "من شهر", "من سنة", "من أسبوع") from matching when the
     * unit noun is actually the start of a longer specifier such as "من يوم الخميس", "من يوم ما",
     * "من شهر رمضان", "من سنة 2020", or "من شهر نوفمبر".
     */
    private val singleUnitSpecifierGuard: String =
        """(?!\s+(?:ال|\d|(?:ما|رمضان|شعبان|$arabicMonthNamesPattern)(?:\s|$|[،,.;؛])))"""

    private val twoDaysAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+يومين$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END"""
    )

    private val oneDayAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+يوم(?:\s+واحد)?$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END$singleUnitSpecifierGuard"""
    )

    private val nDaysAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+($countTokenPattern)\s*(?:أيام|ايام|يوم)$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END"""
    )

    private val twoWeeksAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+(?:أسبوعين|اسبوعين)$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END"""
    )

    private val oneWeekAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+(?:أسبوع|اسبوع)(?:\s+واحد)?$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END$singleUnitSpecifierGuard"""
    )

    private val nWeeksAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+($countTokenPattern)\s*(?:أسابيع|اسابيع|أسبوع|اسبوع)$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END"""
    )

    private val twoMonthsAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+شهرين$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END"""
    )

    private val oneMonthAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+شهر(?:\s+واحد)?$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END$singleUnitSpecifierGuard"""
    )

    private val nMonthsAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+($countTokenPattern)\s*(?:شهور|أشهر|اشهر|شهر)$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END"""
    )

    private val twoYearsAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+(?:سنتين|عامين)$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END"""
    )

    private val oneYearAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+(?:سنة|سنه|عام)(?:\s+واحدة|\s+واحد)?$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END$singleUnitSpecifierGuard"""
    )

    private val nYearsAgoOffsetRegex = Regex(
        """$BOUNDARY_START(?:من|منذ)\s+($countTokenPattern)\s*(?:سنين|سنوات|سنة|سنه|أعوام|اعوام)$OPTIONAL_PAST_VERB_SUFFIX$BOUNDARY_END"""
    )

    // --- Stage 5: Quick Relative Day Phrases ---

    private val dayBeforeYesterdayPhraseRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:يوم\s+)?(?:أول\s+أمس|اول\s+امس|أول\s+امس|اول\s+أمس|أول\s+امبارح|اول\s+امبارح|أول\s+إمبارح|اول\s+إمبارح|day\s+before\s+yesterday)$BOUNDARY_END""",
        RegexOption.IGNORE_CASE
    )

    private val yesterdayPhraseRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:يوم\s+)?(?:أمس|امس|امبارح|إمبارح|البارحة|البارحه|yesterday)$BOUNDARY_END""",
        RegexOption.IGNORE_CASE
    )

    private val todayPhraseRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:يوم\s+)?(?:اليوم|النهارده|النهاردة|انهارده|انهاردة|today)$BOUNDARY_END""",
        RegexOption.IGNORE_CASE
    )

    // --- Stage 6: Days of the Week ---

    /**
     * Definite weekday names (with the Arabic article "ال") that may be used with a relative
     * qualifier ("اللي فات", "الجاي") without a leading "يوم". Bare forms like "حد" or "خميس"
     * are only accepted after an explicit "يوم" to prevent false positives on ordinary words
     * (e.g. "حد اللي فات" meaning "someone who passed by").
     */
    private val definiteWeekdayNamesPattern: String = allWeekdayMap.keys
        .filter { it.startsWith("ال") }
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }

    /** Words that commonly precede a weekday name as part of a place or event noun ("سوق الجمعة"). */
    private const val WEEKDAY_NOUN_GUARD = """(?<!سوق\s)(?<!صلاة\s)(?<!صلاه\s)"""

    private val weekdayTokenPattern: String =
        """(?:يوم\s+($allWeekdayNamesPattern)|$WEEKDAY_NOUN_GUARD($definiteWeekdayNamesPattern))"""

    private const val PAST_WEEK_QUALIFIER = """(?:اللي\s+فات|الماضي|السابق)"""
    private const val CURRENT_WEEK_QUALIFIER = """(?:ده|دا|الحالي)"""
    private const val NEXT_WEEK_QUALIFIER = """(?:الجاي|القادم|المقبل)"""

    private val pastQualifiedWeekdayRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?$weekdayTokenPattern\s+(?:اللي\s+فاتت|اللي\s+فات|الماضية|الماضي|السابقة|السابق)$BOUNDARY_END"""
    )

    private val futureQualifiedWeekdayRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?$weekdayTokenPattern\s+(?:الجاية|الجايه|الجاي|القادمة|القادم|المقبلة|المقبل)$BOUNDARY_END"""
    )

    /** "الخميس في الأسبوع اللي فات" / "يوم الاتنين من الأسبوع الجاي" (weekday first, then week). */
    private val weekdayThenWeekQualifierRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?$weekdayTokenPattern\s+(?:في\s+|من\s+)?(?:الأسبوع|الاسبوع)\s+($PAST_WEEK_QUALIFIER|$CURRENT_WEEK_QUALIFIER|$NEXT_WEEK_QUALIFIER)$BOUNDARY_END"""
    )

    /** "الأسبوع اللي فات يوم الخميس" / "في الأسبوع الجاي يوم السبت" (week first, then weekday). */
    private val weekQualifierThenWeekdayRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:الأسبوع|الاسبوع)\s+($PAST_WEEK_QUALIFIER|$CURRENT_WEEK_QUALIFIER|$NEXT_WEEK_QUALIFIER)\s+(?:يوم\s+($allWeekdayNamesPattern)|($definiteWeekdayNamesPattern))$BOUNDARY_END"""
    )

    private val explicitYawmWeekdayRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?يوم\s+($allWeekdayNamesPattern)(?:\s+(?:ده|دا|الحالي|الحالية))?$BOUNDARY_END"""
    )

    private val standaloneDefiniteWeekdayRegex = Regex(
        """$BOUNDARY_START$WEEKDAY_NOUN_GUARD(?:في\s+)?($standaloneDefiniteWeekdayNames)(?:\s+(?:ده|دا|الحالي|الحالية))?$BOUNDARY_END"""
    )

    // --- Stage 7: Ambiguous Period-Only & Vague Expressions ---

    private val weekSubPeriodAmbiguousRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:بداية|أول|اول|آخر|اخر|نهاية|نص|نصف|منتصف)\s+(?:الأسبوع|الاسبوع)(?:\s+(?:اللي\s+فات|الماضي|السابق|ده|الحالي|الجاي|القادم|المقبل))?$BOUNDARY_END"""
    )

    private val wholeWeekAmbiguousRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:الأسبوع|الاسبوع)\s+(اللي\s+فات|الماضي|السابق|ده|الحالي|الجاي|القادم|المقبل)$BOUNDARY_END"""
    )

    private val wholeMonthAmbiguousRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:(الشهر\s+(?:اللي\s+فات|الماضي|السابق|ده|الحالي|الجاي|القادم|المقبل))|(هذا\s+الشهر))$BOUNDARY_END"""
    )

    private val wholeYearAmbiguousRegex = Regex(
        """$BOUNDARY_START(?:في\s+)?(?:((?:السنة|السنه)\s+(?:اللي\s+فاتت|الماضية|السابقة|دي|دى|الحالية|الجاية|الجايه|القادمة|المقبلة))|(العام\s+(?:الماضي|السابق|الحالي|القادم|المقبل))|(هذا\s+العام))$BOUNDARY_END"""
    )

    private val vagueDayExpressionRegex = Regex(
        """$BOUNDARY_START(?:بعد\s+كام\s+يوم|من\s+كام\s+يوم|منذ\s+عدة\s+أيام|من\s+كذا\s+يوم|اليومين\s+اللي\s+فاتوا|الكام\s+يوم\s+اللي\s+فاتوا|اليومين\s+دول)$BOUNDARY_END"""
    )

    private val multiSpaceRegex = Regex("""\s+""")

    /**
     * Extracts and resolves the first date expression in [segment] relative to [referenceDate].
     *
     * @param segment The input transaction text segment.
     * @param referenceDate The single reference [LocalDate] for the current parsing operation.
     * @param isAlreadyNormalized Set to true if [MoneyUtils.normalizeDigits] was already applied to [segment].
     */
    fun resolveDate(
        segment: String,
        referenceDate: LocalDate,
        isAlreadyNormalized: Boolean = false
    ): DateResolutionResult? {
        if (segment.isBlank()) return null
        val normalized = if (isAlreadyNormalized) segment else MoneyUtils.normalizeDigits(segment)

        // 1. Explicit Calendar Dates (YYYY-MM-DD, YYYY/M/D, D/M/YYYY, or "يوم D <ArabicMonth> [YYYY]")
        matchExplicitCalendarDate(normalized, referenceDate)?.let { return it }

        // 2. Specific Day Within a Relative Month ("يوم 5 الشهر اللي فات", "يوم 15 الشهر ده", "يوم 20 الشهر الجاي")
        matchDayInRelativeMonth(normalized, referenceDate)?.let { return it }

        // 3. Anchored Month Boundaries ("أول الشهر", "آخر الشهر اللي فات") & Sub-Month Periods ("في نص الشهر", "آخر الشهر")
        matchMonthBoundaryOrSubPeriod(normalized, referenceDate)?.let { return it }

        // 4. Exact Relative Offsets ("من يومين", "من 3 أيام", "من أسبوع", "من شهرين فاتوا", "من 3 سنين")
        matchRelativeOffset(normalized, referenceDate)?.let { return it }

        // 5. Quick Relative Day Phrases ("أول امبارح", "امبارح", "النهارده", "اليوم")
        matchQuickDayPhrase(normalized, referenceDate)?.let { return it }

        // 6. Days of the Week ("الخميس اللي فات", "يوم الاتنين الجاي", "يوم الخميس", "السبت")
        matchWeekdayExpression(normalized, referenceDate)?.let { return it }

        // 7. Ambiguous Period-Only & Vague Expressions ("الأسبوع اللي فات", "الشهر اللي فات", "السنة اللي فاتت", "بعد كام يوم")
        matchAmbiguousPeriodOrVagueExpression(normalized, referenceDate)?.let { return it }

        return null
    }

    private fun matchExplicitCalendarDate(
        normalized: String,
        referenceDate: LocalDate
    ): DateResolutionResult? {
        val yearFirstMatch = explicitYearFirstDateRegex.find(normalized)
        if (yearFirstMatch != null) {
            val year = yearFirstMatch.groupValues[1].toIntOrNull()
            val month = yearFirstMatch.groupValues[2].toIntOrNull()
            val day = yearFirstMatch.groupValues[3].toIntOrNull()
            return buildValidatedExplicitDateResult(
                matchedSpan = yearFirstMatch.value,
                year = year,
                month = month,
                day = day
            )
        }

        val dayFirstMatch = explicitDayFirstDateRegex.find(normalized)
        if (dayFirstMatch != null) {
            val day = dayFirstMatch.groupValues[1].toIntOrNull()
            val month = dayFirstMatch.groupValues[2].toIntOrNull()
            val year = dayFirstMatch.groupValues[3].toIntOrNull()
            return buildValidatedExplicitDateResult(
                matchedSpan = dayFirstMatch.value,
                year = year,
                month = month,
                day = day
            )
        }

        val namedMonthMatch = explicitDayNamedMonthRegex.find(normalized)
        if (namedMonthMatch != null) {
            val day = parseArabicCountToken(namedMonthMatch.groupValues[1])
            val monthName = namedMonthMatch.groupValues[2].trim()
            val month = arabicMonthsMap[monthName]
            val yearToken = namedMonthMatch.groupValues[3].trim()
            val year = when {
                yearToken.isEmpty() -> referenceDate.year
                yearToken.all { it.isDigit() } -> yearToken.toIntOrNull() ?: referenceDate.year
                yearToken.contains("فاتت") || yearToken.contains("الماضية") || yearToken.contains("الماضي") -> referenceDate.year - 1
                yearToken.contains("الجاية") || yearToken.contains("الجايه") || yearToken.contains("القادمة") || yearToken.contains("القادم") -> referenceDate.year + 1
                else -> referenceDate.year
            }
            return buildValidatedExplicitDateResult(
                matchedSpan = namedMonthMatch.value,
                year = year,
                month = month,
                day = day
            )
        }

        return null
    }

    private fun buildValidatedExplicitDateResult(
        matchedSpan: String,
        year: Int?,
        month: Int?,
        day: Int?
    ): DateResolutionResult {
        val normExpr = normalizeWhitespace(matchedSpan)
        if (year == null || month == null || day == null || year !in 1900..2100 || month !in 1..12) {
            return DateResolutionResult(
                resolvedDate = null,
                resolvedIsoDate = null,
                matchedSpan = matchedSpan,
                normalizedExpression = normExpr,
                isExplicit = true,
                isRelative = false,
                isAmbiguous = true,
                isInvalid = true,
                diagnosticMessage = "التاريخ المذكور ($normExpr) غير صالح تقويمياً، يرجى مراجعته"
            )
        }
        val ym = YearMonth.of(year, month)
        if (day !in 1..ym.lengthOfMonth()) {
            return DateResolutionResult(
                resolvedDate = null,
                resolvedIsoDate = null,
                matchedSpan = matchedSpan,
                normalizedExpression = normExpr,
                isExplicit = true,
                isRelative = false,
                isAmbiguous = true,
                isInvalid = true,
                diagnosticMessage = "اليوم ($day) غير موجود في شهر ${MoneyUtils.arabicMonthName(month)} $year، يرجى تصحيح التاريخ"
            )
        }
        val date = ym.atDay(day)
        return DateResolutionResult(
            resolvedDate = date,
            resolvedIsoDate = MoneyUtils.formatLocalDateToIso(date),
            matchedSpan = matchedSpan,
            normalizedExpression = normExpr,
            isExplicit = true,
            isRelative = false
        )
    }

    private fun matchDayInRelativeMonth(
        normalized: String,
        referenceDate: LocalDate
    ): DateResolutionResult? {
        val match = dayInRelativeMonthRegex.find(normalized) ?: return null
        val dayToken = match.groupValues[1].trim()
        val monthPhrase = match.groupValues[2].trim()
        val day = parseArabicCountToken(dayToken)
        val normExpr = normalizeWhitespace(match.value)

        val currentYm = YearMonth.from(referenceDate)
        val targetYm = when {
            monthPhrase.contains("فات") || monthPhrase.contains("الماضي") || monthPhrase.contains("السابق") ->
                currentYm.minusMonths(1)
            monthPhrase.contains("الجاي") || monthPhrase.contains("القادم") || monthPhrase.contains("المقبل") ->
                currentYm.plusMonths(1)
            else -> currentYm
        }

        if (day == null || day !in 1..targetYm.lengthOfMonth()) {
            return DateResolutionResult(
                resolvedDate = null,
                resolvedIsoDate = null,
                matchedSpan = match.value,
                normalizedExpression = normExpr,
                isExplicit = false,
                isRelative = true,
                isAmbiguous = true,
                isInvalid = true,
                periodStart = targetYm.atDay(1),
                periodEnd = targetYm.atEndOfMonth(),
                diagnosticMessage = "اليوم ($dayToken) غير موجود في شهر ${MoneyUtils.arabicMonthName(targetYm.monthValue)} ${targetYm.year}، يرجى تصحيح التاريخ"
            )
        }

        val resolved = targetYm.atDay(day)
        return DateResolutionResult(
            resolvedDate = resolved,
            resolvedIsoDate = MoneyUtils.formatLocalDateToIso(resolved),
            matchedSpan = match.value,
            normalizedExpression = normExpr,
            isExplicit = false,
            isRelative = true
        )
    }

    private fun matchMonthBoundaryOrSubPeriod(
        normalized: String,
        referenceDate: LocalDate
    ): DateResolutionResult? {
        val currentYm = YearMonth.from(referenceDate)

        firstDayOfPrevMonthRegex.find(normalized)?.let { match ->
            val prevYm = currentYm.minusMonths(1)
            return buildResolvedRelativeResult(prevYm.atDay(1), match.value)
        }

        lastDayOfPrevMonthRegex.find(normalized)?.let { match ->
            val prevYm = currentYm.minusMonths(1)
            return buildResolvedRelativeResult(prevYm.atEndOfMonth(), match.value)
        }

        firstDayOfNextMonthRegex.find(normalized)?.let { match ->
            val nextYm = currentYm.plusMonths(1)
            return buildResolvedRelativeResult(nextYm.atDay(1), match.value)
        }

        firstDayOfCurrentMonthRegex.find(normalized)?.let { match ->
            return buildResolvedRelativeResult(currentYm.atDay(1), match.value)
        }

        midMonthAmbiguousRegex.find(normalized)?.let { match ->
            return DateResolutionResult(
                resolvedDate = null,
                resolvedIsoDate = null,
                matchedSpan = match.value,
                normalizedExpression = normalizeWhitespace(match.value),
                isExplicit = false,
                isRelative = true,
                isAmbiguous = true,
                periodStart = currentYm.atDay(1),
                periodEnd = currentYm.atEndOfMonth(),
                diagnosticMessage = "عبارة منتصف الشهر غير محددة بيوم دقيق، يرجى تحديد التاريخ"
            )
        }

        endOfCurrentMonthAmbiguousRegex.find(normalized)?.let { match ->
            return DateResolutionResult(
                resolvedDate = null,
                resolvedIsoDate = null,
                matchedSpan = match.value,
                normalizedExpression = normalizeWhitespace(match.value),
                isExplicit = false,
                isRelative = true,
                isAmbiguous = true,
                periodStart = currentYm.atDay(1),
                periodEnd = currentYm.atEndOfMonth(),
                diagnosticMessage = "عبارة آخر الشهر غير محددة بيوم دقيق، يرجى تحديد التاريخ"
            )
        }

        return null
    }

    private fun matchRelativeOffset(
        normalized: String,
        referenceDate: LocalDate
    ): DateResolutionResult? {
        // Days offset
        twoDaysAgoOffsetRegex.find(normalized)?.let { match ->
            return buildResolvedRelativeResult(referenceDate.minusDays(2), match.value)
        }
        oneDayAgoOffsetRegex.find(normalized)?.let { match ->
            return buildResolvedRelativeResult(referenceDate.minusDays(1), match.value)
        }
        nDaysAgoOffsetRegex.find(normalized)?.let { match ->
            val count = parseArabicCountToken(match.groupValues[1])
            if (count != null && count in 1..3650) {
                return buildResolvedRelativeResult(referenceDate.minusDays(count.toLong()), match.value)
            }
        }

        // Weeks offset
        twoWeeksAgoOffsetRegex.find(normalized)?.let { match ->
            return buildResolvedRelativeResult(referenceDate.minusWeeks(2), match.value)
        }
        oneWeekAgoOffsetRegex.find(normalized)?.let { match ->
            return buildResolvedRelativeResult(referenceDate.minusWeeks(1), match.value)
        }
        nWeeksAgoOffsetRegex.find(normalized)?.let { match ->
            val count = parseArabicCountToken(match.groupValues[1])
            if (count != null && count in 1..520) {
                return buildResolvedRelativeResult(referenceDate.minusWeeks(count.toLong()), match.value)
            }
        }

        // Months offset
        twoMonthsAgoOffsetRegex.find(normalized)?.let { match ->
            return buildResolvedRelativeResult(referenceDate.minusMonths(2), match.value)
        }
        oneMonthAgoOffsetRegex.find(normalized)?.let { match ->
            return buildResolvedRelativeResult(referenceDate.minusMonths(1), match.value)
        }
        nMonthsAgoOffsetRegex.find(normalized)?.let { match ->
            val count = parseArabicCountToken(match.groupValues[1])
            if (count != null && count in 1..1200) {
                return buildResolvedRelativeResult(referenceDate.minusMonths(count.toLong()), match.value)
            }
        }

        // Years offset
        twoYearsAgoOffsetRegex.find(normalized)?.let { match ->
            return buildResolvedRelativeResult(referenceDate.minusYears(2), match.value)
        }
        oneYearAgoOffsetRegex.find(normalized)?.let { match ->
            return buildResolvedRelativeResult(referenceDate.minusYears(1), match.value)
        }
        nYearsAgoOffsetRegex.find(normalized)?.let { match ->
            val count = parseArabicCountToken(match.groupValues[1])
            if (count != null && count in 1..100) {
                return buildResolvedRelativeResult(referenceDate.minusYears(count.toLong()), match.value)
            }
        }

        return null
    }

    private fun matchQuickDayPhrase(
        normalized: String,
        referenceDate: LocalDate
    ): DateResolutionResult? {
        dayBeforeYesterdayPhraseRegex.find(normalized)?.let { match ->
            val resolved = MoneyUtils.resolveQuickDate(QuickDateOption.DAY_BEFORE_YESTERDAY, referenceDate)
            return buildResolvedRelativeResult(resolved, match.value)
        }
        yesterdayPhraseRegex.find(normalized)?.let { match ->
            val resolved = MoneyUtils.resolveQuickDate(QuickDateOption.YESTERDAY, referenceDate)
            return buildResolvedRelativeResult(resolved, match.value)
        }
        todayPhraseRegex.find(normalized)?.let { match ->
            val resolved = MoneyUtils.resolveQuickDate(QuickDateOption.TODAY, referenceDate)
            return buildResolvedRelativeResult(resolved, match.value)
        }
        return null
    }

    /**
     * Resolves Egyptian Arabic and Standard Arabic weekday expressions:
     * 1. Week-anchored ("الخميس في الأسبوع اللي فات", "الأسبوع الجاي يوم السبت"):
     *    Resolves to that weekday inside the referenced Saturday..Friday Arabic week.
     * 2. Past-qualified ("الخميس اللي فات", "الجمعة اللي فاتت", "السبت الماضي"):
     *    Strictly resolves to the most recent past occurrence before [referenceDate] (1..7 days ago).
     * 3. Future-qualified ("يوم الاتنين الجاي", "الثلاثاء القادم"):
     *    Strictly resolves to the next upcoming occurrence after [referenceDate] (1..7 days ahead).
     * 4. Unqualified ("يوم الخميس", "السبت"):
     *    Resolves to the most recent occurrence on or before [referenceDate] (0..6 days ago),
     *    because transactions are normally recorded after they happen.
     */
    private fun matchWeekdayExpression(
        normalized: String,
        referenceDate: LocalDate
    ): DateResolutionResult? {
        weekdayThenWeekQualifierRegex.find(normalized)?.let { match ->
            val targetDow = weekdayFromGroups(match, 1, 2) ?: return@let
            val weeksOffset = weekOffsetFromQualifier(match.groupValues[3])
            return buildResolvedRelativeResult(
                resolveWeekdayInArabicWeek(referenceDate, weeksOffset, targetDow),
                match.value
            )
        }

        weekQualifierThenWeekdayRegex.find(normalized)?.let { match ->
            val targetDow = weekdayFromGroups(match, 2, 3) ?: return@let
            val weeksOffset = weekOffsetFromQualifier(match.groupValues[1])
            return buildResolvedRelativeResult(
                resolveWeekdayInArabicWeek(referenceDate, weeksOffset, targetDow),
                match.value
            )
        }

        pastQualifiedWeekdayRegex.find(normalized)?.let { match ->
            val targetDow = weekdayFromGroups(match, 1, 2) ?: return@let
            val diff = (referenceDate.dayOfWeek.value - targetDow.value + 7) % 7
            val daysAgo = if (diff == 0) 7L else diff.toLong()
            return buildResolvedRelativeResult(referenceDate.minusDays(daysAgo), match.value)
        }

        futureQualifiedWeekdayRegex.find(normalized)?.let { match ->
            val targetDow = weekdayFromGroups(match, 1, 2) ?: return@let
            val diff = (targetDow.value - referenceDate.dayOfWeek.value + 7) % 7
            val daysAhead = if (diff == 0) 7L else diff.toLong()
            return buildResolvedRelativeResult(referenceDate.plusDays(daysAhead), match.value)
        }

        explicitYawmWeekdayRegex.find(normalized)?.let { match ->
            val targetDow = allWeekdayMap[match.groupValues[1].trim()] ?: return@let
            val daysAgo = ((referenceDate.dayOfWeek.value - targetDow.value + 7) % 7).toLong()
            return buildResolvedRelativeResult(referenceDate.minusDays(daysAgo), match.value)
        }

        standaloneDefiniteWeekdayRegex.find(normalized)?.let { match ->
            val targetDow = allWeekdayMap[match.groupValues[1].trim()] ?: return@let
            val daysAgo = ((referenceDate.dayOfWeek.value - targetDow.value + 7) % 7).toLong()
            return buildResolvedRelativeResult(referenceDate.minusDays(daysAgo), match.value)
        }

        return null
    }

    private fun weekdayFromGroups(match: MatchResult, vararg groupIndexes: Int): DayOfWeek? {
        for (idx in groupIndexes) {
            val token = match.groupValues.getOrNull(idx)?.trim().orEmpty()
            if (token.isNotEmpty()) {
                allWeekdayMap[token]?.let { return it }
            }
        }
        return null
    }

    private fun weekOffsetFromQualifier(qualifier: String): Long {
        return when {
            qualifier.contains("فات") || qualifier.contains("الماضي") || qualifier.contains("السابق") -> -1L
            qualifier.contains("الجاي") || qualifier.contains("القادم") || qualifier.contains("المقبل") -> 1L
            else -> 0L
        }
    }

    /**
     * Returns [targetDow] inside the Saturday-first Arabic week shifted by [weeksOffset] from the week
     * containing [referenceDate].
     */
    private fun resolveWeekdayInArabicWeek(
        referenceDate: LocalDate,
        weeksOffset: Long,
        targetDow: DayOfWeek
    ): LocalDate {
        val (weekStart, _) = MoneyUtils.arabicWeekRange(referenceDate, weeksOffset)
        val indexInArabicWeek = (targetDow.value + 1) % 7 // Sat=0, Sun=1, ..., Fri=6
        return weekStart.plusDays(indexInArabicWeek.toLong())
    }

    private fun matchAmbiguousPeriodOrVagueExpression(
        normalized: String,
        referenceDate: LocalDate
    ): DateResolutionResult? {
        weekSubPeriodAmbiguousRegex.find(normalized)?.let { match ->
            val text = match.value
            val weeksOffset = when {
                text.contains("فات") || text.contains("الماضي") || text.contains("السابق") -> -1L
                text.contains("الجاي") || text.contains("القادم") || text.contains("المقبل") -> 1L
                else -> 0L
            }
            val (weekStart, weekEnd) = MoneyUtils.arabicWeekRange(referenceDate, weeksOffset)
            return DateResolutionResult(
                resolvedDate = null,
                resolvedIsoDate = null,
                matchedSpan = match.value,
                normalizedExpression = normalizeWhitespace(match.value),
                isExplicit = false,
                isRelative = true,
                isAmbiguous = true,
                periodStart = weekStart,
                periodEnd = weekEnd,
                diagnosticMessage = "العبارة تشير إلى جزء غير محدد بيوم دقيق من الأسبوع، يرجى اختيار التاريخ المحدد"
            )
        }

        wholeWeekAmbiguousRegex.find(normalized)?.let { match ->
            val qualifier = match.groupValues[1]
            val weeksOffset = when {
                qualifier.contains("فات") || qualifier.contains("الماضي") || qualifier.contains("السابق") -> -1L
                qualifier.contains("الجاي") || qualifier.contains("القادم") || qualifier.contains("المقبل") -> 1L
                else -> 0L
            }
            val (weekStart, weekEnd) = MoneyUtils.arabicWeekRange(referenceDate, weeksOffset)
            return DateResolutionResult(
                resolvedDate = null,
                resolvedIsoDate = null,
                matchedSpan = match.value,
                normalizedExpression = normalizeWhitespace(match.value),
                isExplicit = false,
                isRelative = true,
                isAmbiguous = true,
                periodStart = weekStart,
                periodEnd = weekEnd,
                diagnosticMessage = "التاريخ المذكور يشير إلى أسبوع كامل (${MoneyUtils.formatLocalDateToIso(weekStart)} إلى ${MoneyUtils.formatLocalDateToIso(weekEnd)}) دون تحديد يوم بعينه، يرجى اختيار التاريخ"
            )
        }

        wholeMonthAmbiguousRegex.find(normalized)?.let { match ->
            val text = match.value
            val currentYm = YearMonth.from(referenceDate)
            val targetYm = when {
                text.contains("فات") || text.contains("الماضي") || text.contains("السابق") -> currentYm.minusMonths(1)
                text.contains("الجاي") || text.contains("القادم") || text.contains("المقبل") -> currentYm.plusMonths(1)
                else -> currentYm
            }
            return DateResolutionResult(
                resolvedDate = null,
                resolvedIsoDate = null,
                matchedSpan = match.value,
                normalizedExpression = normalizeWhitespace(match.value),
                isExplicit = false,
                isRelative = true,
                isAmbiguous = true,
                periodStart = targetYm.atDay(1),
                periodEnd = targetYm.atEndOfMonth(),
                diagnosticMessage = "التاريخ المذكور يشير إلى شهر ${MoneyUtils.arabicMonthName(targetYm.monthValue)} ${targetYm.year} بالكامل دون تحديد يوم بعينه، يرجى اختيار التاريخ"
            )
        }

        wholeYearAmbiguousRegex.find(normalized)?.let { match ->
            val text = match.value
            val targetYear = when {
                text.contains("فاتت") || text.contains("الماضية") || text.contains("الماضي") || text.contains("السابقة") || text.contains("السابق") ->
                    referenceDate.year - 1
                text.contains("الجاية") || text.contains("الجايه") || text.contains("القادمة") || text.contains("القادم") || text.contains("المقبلة") || text.contains("المقبل") ->
                    referenceDate.year + 1
                else -> referenceDate.year
            }
            return DateResolutionResult(
                resolvedDate = null,
                resolvedIsoDate = null,
                matchedSpan = match.value,
                normalizedExpression = normalizeWhitespace(match.value),
                isExplicit = false,
                isRelative = true,
                isAmbiguous = true,
                periodStart = LocalDate.of(targetYear, 1, 1),
                periodEnd = LocalDate.of(targetYear, 12, 31),
                diagnosticMessage = "التاريخ المذكور يشير إلى سنة $targetYear بالكامل دون تحديد اليوم والشهر، يرجى اختيار التاريخ"
            )
        }

        vagueDayExpressionRegex.find(normalized)?.let { match ->
            return DateResolutionResult(
                resolvedDate = null,
                resolvedIsoDate = null,
                matchedSpan = match.value,
                normalizedExpression = normalizeWhitespace(match.value),
                isExplicit = false,
                isRelative = true,
                isAmbiguous = true,
                diagnosticMessage = "العبارة الزمنية غير محددة بعدد أيام دقيق، يرجى اختيار التاريخ المحدد"
            )
        }

        return null
    }

    private fun buildResolvedRelativeResult(
        date: LocalDate,
        matchedSpan: String
    ): DateResolutionResult {
        return DateResolutionResult(
            resolvedDate = date,
            resolvedIsoDate = MoneyUtils.formatLocalDateToIso(date),
            matchedSpan = matchedSpan,
            normalizedExpression = normalizeWhitespace(matchedSpan),
            isExplicit = false,
            isRelative = true
        )
    }

    internal fun parseArabicCountToken(rawToken: String): Int? {
        val cleaned = normalizeWhitespace(MoneyUtils.normalizeDigits(rawToken).lowercase(Locale.ROOT))
        if (cleaned.isEmpty()) return null
        cleaned.toIntOrNull()?.let { return it }
        return countWordsMap[cleaned]
    }

    private fun normalizeWhitespace(input: String): String {
        return input.trim().replace(multiSpaceRegex, " ")
    }
}
