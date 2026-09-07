package vn.unlimit.vpngate.utils

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for formatting dates according to current app language/calendar.
 * - For Persian ('fa'): Converts Gregorian date to Persian / Solar Hijri (شمسی) calendar.
 * - For English ('en') and others: Gregorian (میلادی) calendar.
 */
object DateTimeFormatterUtil {

    private val PERSIAN_MONTH_NAMES = arrayOf(
        "فروردین", "اردیبهشت", "خرداد",
        "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر",
        "دی", "بهمن", "اسفند"
    )

    fun formatLastUpdated(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val isPersian = isPersianLocale()
        val date = Date(timestamp)

        return if (isPersian) {
            val (year, month, day) = gregorianToJalali(date)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timePart = timeFormat.format(date)
            val monthName = PERSIAN_MONTH_NAMES.getOrElse(month - 1) { "$month" }
            "$day $monthName $year - $timePart"
        } else {
            val format = SimpleDateFormat("dd MMM yyyy - HH:mm", Locale.ENGLISH)
            format.format(date)
        }
    }

    fun formatDate(date: Date?): String {
        if (date == null) return ""
        return formatLastUpdated(date.time)
    }

    private fun isPersianLocale(): Boolean {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (!appLocales.isEmpty) {
            for (i in 0 until appLocales.size()) {
                val locale = appLocales.get(i)
                if (locale?.language?.equals("fa", ignoreCase = true) == true) {
                    return true
                }
            }
        }
        return Locale.getDefault().language.equals("fa", ignoreCase = true)
    }

    /**
     * Converts a Java Date to Jalali (Solar Hijri) (Year, Month, Day)
     * Algorithm based on J. D. Behzad's astronomical Gregorian-Jalali conversion.
     */
    fun gregorianToJalali(date: Date): Triple<Int, Int, Int> {
        val cal = java.util.Calendar.getInstance()
        cal.time = date
        val gYear = cal.get(java.util.Calendar.YEAR)
        val gMonth = cal.get(java.util.Calendar.MONTH) + 1
        val gDay = cal.get(java.util.Calendar.DAY_OF_MONTH)

        return gregorianToJalaliDate(gYear, gMonth, gDay)
    }

    fun gregorianToJalaliDate(gYear: Int, gMonth: Int, gDay: Int): Triple<Int, Int, Int> {
        val gDaysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val jDaysInMonth = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)

        val gy = gYear - 1600
        val gm = gMonth - 1
        val gd = gDay - 1

        var gDayNo = 365 * gy + ((gy + 3) / 4) - ((gy + 99) / 100) + ((gy + 399) / 400)

        for (i in 0 until gm) {
            gDayNo += gDaysInMonth[i]
        }
        if (gm > 1 && ((gYear % 4 == 0 && gYear % 100 != 0) || (gYear % 400 == 0))) {
            // Leap year in Gregorian
            gDayNo++
        }
        gDayNo += gd

        var jDayNo = gDayNo - 79

        val jNp = jDayNo / 12053
        jDayNo %= 12053

        var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
        jDayNo %= 1461

        if (jDayNo >= 366) {
            jy += (jDayNo - 1) / 365
            jDayNo = (jDayNo - 1) % 365
        }

        var jm = 0
        var jd = 0
        for (i in 0 until 11) {
            if (jDayNo < jDaysInMonth[i]) {
                jm = i + 1
                jd = jDayNo + 1
                break
            }
            jDayNo -= jDaysInMonth[i]
        }
        if (jm == 0) {
            jm = 12
            jd = jDayNo + 1
        }

        return Triple(jy, jm, jd)
    }
}
