package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CampusTimeUtils {
    const val CAMPUS_TIMEZONE_ID = "Asia/Kolkata"

    private val dateFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(CAMPUS_TIMEZONE_ID)
        }

    /**
     * Returns the current date formatted as 'yyyy-MM-dd' in the Asia/Kolkata (IST) timezone.
     */
    fun getTodayCampusDate(epochMillis: Long = System.currentTimeMillis()): String {
        return dateFormat.format(Date(epochMillis))
    }

    /**
     * Checks if a stored date string matches today's date in Asia/Kolkata.
     */
    fun isTodayInCampusTimezone(dateString: String?, epochMillis: Long = System.currentTimeMillis()): Boolean {
        if (dateString.isNullOrBlank()) return false
        return dateString == getTodayCampusDate(epochMillis)
    }
}
