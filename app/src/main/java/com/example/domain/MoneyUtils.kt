package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exact monetary and date utilities for Masroofi AI.
 * Stores all monetary amounts in integer minor units (e.g., 100 piastres = 1.00 EGP).
 */
object MoneyUtils {

    private val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

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
     * Returns null if invalid, zero, negative, or overflowed.
     */
    fun parseAmountToMinorUnits(rawInput: String?): Long? {
        if (rawInput.isNullOrBlank()) return null
        val cleaned = normalizeDigits(rawInput.trim())
            .replace(",", "")
            .replace(" ", "")
            .replace("ج.م", "")
            .replace("جنيه", "")
            .replace("EGP", "", ignoreCase = true)
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

        val symbols = DecimalFormatSymbols(Locale.US)
        val majorFormatter = DecimalFormat("#,##0", symbols)
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

    fun todayIsoDate(nowMillis: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date(nowMillis))
    }

    fun epochMillisToIsoDate(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date(epochMillis))
    }

    fun formatArabicReadableDate(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date(epochMillis))
    }

    fun isoDateToEpochMillis(isoDate: String?): Long? {
        if (isoDate.isNullOrBlank()) return null
        val normalized = normalizeDigits(isoDate.trim())
        if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(normalized)) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
            }
            sdf.parse(normalized)?.time
        } catch (e: Exception) {
            null
        }
    }

    fun currentYearMonth(nowMillis: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM", Locale.US)
        return sdf.format(Date(nowMillis))
    }

    fun yearMonthFromMillis(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM", Locale.US)
        return sdf.format(Date(epochMillis))
    }

    fun previousYearMonth(yearMonth: String): String {
        val parts = yearMonth.split("-")
        if (parts.size != 2) return yearMonth
        val year = parts[0].toIntOrNull() ?: return yearMonth
        val month = parts[1].toIntOrNull() ?: return yearMonth
        return if (month == 1) {
            String.format(Locale.US, "%04d-12", year - 1)
        } else {
            String.format(Locale.US, "%04d-%02d", year, month - 1)
        }
    }

    fun startOfDayMillis(epochMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = epochMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun endOfDayMillis(epochMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = epochMillis
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return cal.timeInMillis
    }

    fun monthDateRange(yearMonth: String): Pair<Long, Long> {
        val parts = yearMonth.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val month = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
        val startCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = (startCal.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return startCal.timeInMillis to endCal.timeInMillis
    }
}
