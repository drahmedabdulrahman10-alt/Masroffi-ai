package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

enum class QuickDateOption(
    val daysOffset: Long,
    val arabicLabel: String
) {
    TODAY(daysOffset = 0L, arabicLabel = "اليوم"),
    YESTERDAY(daysOffset = -1L, arabicLabel = "أمس"),
    DAY_BEFORE_YESTERDAY(daysOffset = -2L, arabicLabel = "أول أمس")
}

/**
 * Exact monetary and date utilities for Masroofi AI.
 * Stores all monetary amounts in integer minor units (e.g., 100 piastres = 1.00 EGP).
 * Standardizes all date/time conversions on thread-safe java.time (LocalDate, LocalDateTime,
 * YearMonth, Instant, ZoneId, DateTimeFormatter) while preserving epoch-millisecond Room storage.
 */
object MoneyUtils {

    private val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    private val currencyTokensRegex = Regex(
        """(?i)(?:ج\.م|جنيه|جنية|ر\.س|ريال|د\.إ|درهم|دولار|يورو|EGP|SAR|AED|USD|EUR|\$|€)"""
    )
    private val isoDateFormatRegex = Regex("""^\d{4}-\d{2}-\d{2}$""")

    private val isoDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US)
            .withResolverStyle(ResolverStyle.STRICT)

    private val yearMonthFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM", Locale.US)
            .withResolverStyle(ResolverStyle.STRICT)

    private val majorNumberFormatterThreadLocal = object : ThreadLocal<DecimalFormat>() {
        override fun initialValue(): DecimalFormat =
            DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    }

    /**
     * Converts Eastern Arabic and Persian numerals as well as Arabic decimal separators
     * into standard Western ASCII digits and decimal points.
     */
    fun normalizeDigits(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val arabicIdx = arabicDigits.indexOf(ch)
            val persianIdx = persianDigits.indexOf(ch)
            when {
                arabicIdx >= 0 -> sb.append(('0'.code + arabicIdx).toChar())
                persianIdx >= 0 -> sb.append(('0'.code + persianIdx).toChar())
                ch == '٫' || ch == '،' -> sb.append('.')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Parses a user-entered or AI-generated amount string into integer minor units (100 = 1.00).
     * Supports all app currency symbols/codes (EGP, SAR, AED, USD, EUR) and returns null if
     * invalid, zero, negative, or overflowed.
     */
    fun parseAmountToMinorUnits(rawInput: String?): Long? {
        if (rawInput.isNullOrBlank()) return null
        val cleaned = normalizeDigits(rawInput.trim())
            .replace(",", "")
            .replace(currencyTokensRegex, "")
            .replace(" ", "")
            .trim()
        if (cleaned.isEmpty()) return null
        return try {
            val bd = BigDecimal(cleaned)
            if (bd <= BigDecimal.ZERO) return null
            // Reject astronomical / unrealistic single transaction amounts (> 1 billion)
            if (bd > BigDecimal("1000000000")) return null
            val scaled = bd.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)
            val minor = scaled.longValueExact()
            if (minor <= 0L) null else minor
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses a signed or zero balance value (such as Opening Balance) into minor units.
     */
    fun parseSignedAmountToMinorUnits(rawInput: String?): Long? {
        if (rawInput.isNullOrBlank()) return null
        val cleaned = normalizeDigits(rawInput.trim())
            .replace(",", "")
            .replace(currencyTokensRegex, "")
            .replace(" ", "")
            .trim()
        if (cleaned.isEmpty()) return null
        return try {
            val bd = BigDecimal(cleaned)
            if (bd.abs() > BigDecimal("10000000000")) return null
            bd.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Formats integer minor units for readable display in the UI.
     * E.g., 2000L -> "20 ج.م", 4550L -> "45.50 ج.م"
     */
    fun formatMinorUnits(
        minorUnits: Long,
        currencySymbol: String = "ج.م",
        showPlusSign: Boolean = false
    ): String {
        val isNegative = minorUnits < 0
        val absMinor = kotlin.math.abs(minorUnits)
        val major = absMinor / 100
        val fraction = absMinor % 100

        val majorFormatter = majorNumberFormatterThreadLocal.get()
            ?: DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
        val formattedMajor = majorFormatter.format(major)

        val numberPart = if (fraction == 0L) {
            formattedMajor
        } else {
            String.format(Locale.US, "%s.%02d", formattedMajor, fraction)
        }

        val prefix = when {
            isNegative -> "-"
            showPlusSign && minorUnits > 0 -> "+"
            else -> ""
        }
        return "$prefix$numberPart $currencySymbol".trim()
    }

    /**
     * Formats minor units into a plain editable number string (e.g., "45" or "45.50").
     */
    fun formatPlainMajorAmount(minorUnits: Long): String {
        val isNegative = minorUnits < 0
        val absMinor = kotlin.math.abs(minorUnits)
        val major = absMinor / 100
        val fraction = absMinor % 100
        val prefix = if (isNegative) "-" else ""
        return if (fraction == 0L) {
            "$prefix$major"
        } else {
            String.format(Locale.US, "%s%d.%02d", prefix, major, fraction)
        }
    }

    fun todayIsoDate(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        return epochMillisToLocalDate(nowMillis, zoneId).format(isoDateFormatter)
    }

    fun epochMillisToLocalDate(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): LocalDate {
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
    }

    fun epochMillisToLocalDateTime(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): LocalDateTime {
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDateTime()
    }

    fun epochMillisToIsoDate(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        return epochMillisToLocalDate(epochMillis, zoneId).format(isoDateFormatter)
    }

    fun formatArabicReadableDate(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        return epochMillisToIsoDate(epochMillis, zoneId)
    }

    fun isoDateToEpochMillis(
        isoDate: String?,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val parsedDate = parseIsoLocalDate(isoDate) ?: return null
        return parsedDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    /**
     * Strictly parses an ISO date string (YYYY-MM-DD, supporting Western, Eastern Arabic, or Persian digits)
     * into a LocalDate using ResolverStyle.STRICT. Returns null if invalid.
     */
    fun parseIsoLocalDate(isoDate: String?): LocalDate? {
        if (isoDate.isNullOrBlank()) return null
        val normalized = normalizeDigits(isoDate.trim())
        if (!isoDateFormatRegex.matches(normalized)) return null
        return try {
            LocalDate.parse(normalized, isoDateFormatter)
        } catch (e: Exception) {
            null
        }
    }

    fun formatLocalDateToIso(date: LocalDate): String {
        return date.format(isoDateFormatter)
    }

    /**
     * Dynamically calculates the LocalDate for Today, Yesterday, or Day Before Yesterday
     * relative to the provided reference LocalDate.
     */
    fun resolveQuickDate(
        option: QuickDateOption,
        referenceDate: LocalDate
    ): LocalDate {
        return referenceDate.plusDays(option.daysOffset)
    }

    /**
     * Dynamically calculates the ISO date string (YYYY-MM-DD) for Today, Yesterday, or
     * Day Before Yesterday in the specified local ZoneId.
     */
    fun resolveQuickDateIso(
        option: QuickDateOption,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val todayLocal = epochMillisToLocalDate(nowMillis, zoneId)
        return resolveQuickDate(option, todayLocal).format(isoDateFormatter)
    }

    /**
     * Returns the matching QuickDateOption if selectedIsoDate equals Today, Yesterday,
     * or Day Before Yesterday in the given local ZoneId, or null otherwise.
     */
    fun matchQuickDateOption(
        selectedIsoDate: String?,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): QuickDateOption? {
        val selectedDate = parseIsoLocalDate(selectedIsoDate) ?: return null
        val todayLocal = epochMillisToLocalDate(nowMillis, zoneId)
        return QuickDateOption.entries.firstOrNull { option ->
            resolveQuickDate(option, todayLocal) == selectedDate
        }
    }

    /**
     * Formats the currently selected ISO date into a clear Arabic display label
     * (e.g., "اليوم (2026-11-08)" or "15 نوفمبر 2026 (2026-11-15)").
     */
    fun formatSelectedDateBannerText(
        selectedIsoDate: String?,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val parsed = parseIsoLocalDate(selectedIsoDate)
            ?: return selectedIsoDate?.trim()?.takeIf { it.isNotEmpty() } ?: "غير محدد"
        val normalizedIso = parsed.format(isoDateFormatter)
        val matchedQuick = matchQuickDateOption(normalizedIso, nowMillis, zoneId)
        return if (matchedQuick != null) {
            "${matchedQuick.arabicLabel} ($normalizedIso)"
        } else {
            "${arabicDayMonthYearLabel(parsed)} ($normalizedIso)"
        }
    }

    fun arabicMonthName(monthValue: Int): String {
        return when (monthValue) {
            1 -> "يناير"
            2 -> "فبراير"
            3 -> "مارس"
            4 -> "أبريل"
            5 -> "مايو"
            6 -> "يونيو"
            7 -> "يوليو"
            8 -> "أغسطس"
            9 -> "سبتمبر"
            10 -> "أكتوبر"
            11 -> "نوفمبر"
            12 -> "ديسمبر"
            else -> ""
        }
    }

    fun arabicDayMonthYearLabel(date: LocalDate): String {
        return "${date.dayOfMonth} ${arabicMonthName(date.monthValue)} ${date.year}"
    }

    fun arabicMonthYearTitle(yearMonth: YearMonth): String {
        return "${arabicMonthName(yearMonth.monthValue)} ${yearMonth.year}"
    }

    val arabicWeekDaysShort: List<String> = listOf(
        "سبت",
        "أحد",
        "اثنين",
        "ثلاثاء",
        "أربعاء",
        "خميس",
        "جمعة"
    )

    /**
     * Builds a 7-column calendar grid for the given YearMonth starting on Saturday (السبت).
     * Leading and trailing empty slots are represented as null.
     * Automatically respects leap years (28 vs 29 days in February) and 30/31-day months.
     */
    fun buildCalendarMonthCells(yearMonth: YearMonth): List<LocalDate?> {
        val firstDay = yearMonth.atDay(1)
        // DayOfWeek ISO values: Mon=1 .. Fri=5, Sat=6, Sun=7.
        // With Saturday as column 0: Sat(6)->0, Sun(7)->1, Mon(1)->2, ..., Fri(5)->6
        val leadingEmptyCells = (firstDay.dayOfWeek.value + 1) % 7
        val totalDays = yearMonth.lengthOfMonth()
        val cells = ArrayList<LocalDate?>(leadingEmptyCells + totalDays + 7)
        repeat(leadingEmptyCells) { cells.add(null) }
        for (day in 1..totalDays) {
            cells.add(yearMonth.atDay(day))
        }
        while (cells.size % 7 != 0) {
            cells.add(null)
        }
        return cells
    }

    fun currentYearMonth(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        return yearMonthFromMillis(nowMillis, zoneId)
    }

    fun yearMonthFromMillis(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val localDate = epochMillisToLocalDate(epochMillis, zoneId)
        return YearMonth.from(localDate).format(yearMonthFormatter)
    }

    fun previousYearMonth(yearMonth: String): String {
        val normalized = normalizeDigits(yearMonth.trim())
        return try {
            YearMonth.parse(normalized, yearMonthFormatter)
                .minusMonths(1)
                .format(yearMonthFormatter)
        } catch (e: Exception) {
            yearMonth
        }
    }

    fun nextYearMonth(yearMonth: String): String {
        val normalized = normalizeDigits(yearMonth.trim())
        return try {
            YearMonth.parse(normalized, yearMonthFormatter)
                .plusMonths(1)
                .format(yearMonthFormatter)
        } catch (e: Exception) {
            yearMonth
        }
    }

    fun startOfDayMillis(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val localDate = epochMillisToLocalDate(epochMillis, zoneId)
        return localDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    fun endOfDayMillis(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val localDate = epochMillisToLocalDate(epochMillis, zoneId)
        return LocalDateTime.of(localDate, LocalTime.of(23, 59, 59, 999_000_000))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Normalizes a custom date range when one boundary is updated so startIsoDate is never
     * later than endIsoDate. If both are valid ISO dates and start > end:
     * - When updating start (isStartUpdated = true), endIsoDate is adjusted to startIsoDate.
     * - When updating end (isStartUpdated = false), startIsoDate is adjusted to endIsoDate.
     */
    fun normalizeDateRangeBounds(
        startIsoDate: String,
        endIsoDate: String,
        isStartUpdated: Boolean
    ): Pair<String, String> {
        val startLocal = parseIsoLocalDate(startIsoDate)
        val endLocal = parseIsoLocalDate(endIsoDate)
        val normStart = startLocal?.let { formatLocalDateToIso(it) } ?: startIsoDate
        val normEnd = endLocal?.let { formatLocalDateToIso(it) } ?: endIsoDate
        if (startLocal != null && endLocal != null && startLocal.isAfter(endLocal)) {
            return if (isStartUpdated) {
                normStart to normStart
            } else {
                normEnd to normEnd
            }
        }
        return normStart to normEnd
    }

    fun monthDateRange(
        yearMonth: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<Long, Long> {
        val normalized = normalizeDigits(yearMonth.trim())
        val ym = try {
            YearMonth.parse(normalized, yearMonthFormatter)
        } catch (e: Exception) {
            val parts = normalized.split("-")
            val year = parts.getOrNull(0)?.toIntOrNull() ?: 2026
            val month = (parts.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 12)
            YearMonth.of(year, month)
        }
        val startMillis = ym.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = LocalDateTime.of(ym.atEndOfMonth(), LocalTime.of(23, 59, 59, 999_000_000))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        return startMillis to endMillis
    }
}
