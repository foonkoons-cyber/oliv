package com.khabar.reader.util

import java.util.Calendar
import java.util.TimeZone

/**
 * Date formatting for the list and the reader.
 *
 * Hand-rolled instead of SimpleDateFormat: the app targets minSdk 24 (no java.time) and the
 * output must not change with the device locale — a Hindi phone showing "मार्च" next to English
 * "min pehle" reads worse than one consistent register.
 */
object TimeText {

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** "abhi", "5 min pehle", "3 ghante pehle", "kal", "4 din pehle", else "12 Mar 2025". */
    fun relative(epochMillis: Long, nowMillis: Long): String {
        val diff = nowMillis - epochMillis
        // Feeds routinely carry timestamps a few minutes in the future (clock skew, scheduled
        // posts). Showing "-3 min pehle" would look broken, so anything ahead of now is "abhi".
        if (diff < MINUTE) return "abhi"
        if (diff < HOUR) return "${diff / MINUTE} min pehle"
        if (diff < DAY) {
            val h = diff / HOUR
            return if (h == 1L) "1 ghanta pehle" else "$h ghante pehle"
        }
        if (diff < 2 * DAY) return "kal"
        if (diff < 7 * DAY) return "${diff / DAY} din pehle"
        return date(epochMillis)
    }

    /** "12 Mar 2025". */
    fun date(epochMillis: Long): String {
        val c = calendar(epochMillis)
        return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** "12 Mar 2025, 18:40". */
    fun absolute(epochMillis: Long): String {
        val c = calendar(epochMillis)
        val hh = c.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = c.get(Calendar.MINUTE).toString().padStart(2, '0')
        return "${date(epochMillis)}, $hh:$mm"
    }

    private fun calendar(epochMillis: Long): Calendar =
        Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = epochMillis }
}
