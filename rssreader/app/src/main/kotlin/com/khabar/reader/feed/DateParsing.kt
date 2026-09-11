package com.khabar.reader.feed

/**
 * Dates out of feeds, by hand.
 *
 * SimpleDateFormat is not usable here: it is lenient by default (it will happily read "Mon, 45
 * Xyz 2024" as something), its parsing moves with the device locale, and covering the shapes that
 * actually appear would mean a dozen format objects tried in turn. java.time is out at minSdk 24.
 * So: tokenize, resolve the zone, and do the civil-days arithmetic.
 */
object DateParsing {

    /** Epoch millis, or null when the string is not a date we recognise. Never throws. */
    fun parse(raw: String?): Long? {
        var s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        // Trailing "(GMT)" / "(Pacific Standard Time)" comments are legal RFC-822 and useless.
        val paren = s.indexOf('(')
        if (paren > 0) s = s.substring(0, paren).trim()
        if (s.isEmpty()) return null

        return if (s.length >= 8 && s[4] == '-' && s.take(4).all { it.isDigit() }) {
            parseIso(s)
        } else {
            parseRfc822(s) ?: parseIso(s)
        }
    }

    // ---- ISO-8601 / RFC-3339, plus the SQL-ish "2024-01-15 10:30:00" variant ----------------

    private fun parseIso(input: String): Long? {
        val s = input.trim()
        if (s.length < 10) return null
        val year = s.substring(0, 4).toIntOrNull() ?: return null
        if (s[4] != '-' || s[7] != '-') return null
        val month = s.substring(5, 7).toIntOrNull() ?: return null
        val day = s.substring(8, 10).toIntOrNull() ?: return null

        if (s.length == 10) return toEpochMillis(year, month, day, 0, 0, 0, 0, 0)

        val sep = s[10]
        if (sep != 'T' && sep != 't' && sep != ' ') return null
        var rest = s.substring(11)

        // The zone is whatever trails the time: Z, ±HH:MM, ±HHMM or ±HH.
        var offsetSeconds = 0
        val zoneStart = rest.indexOfLast { it == 'Z' || it == 'z' || it == '+' || it == '-' }
        if (zoneStart > 0) {
            val zone = rest.substring(zoneStart)
            offsetSeconds = numericZoneSeconds(zone) ?: return null
            rest = rest.substring(0, zoneStart)
        } else if (rest.endsWith("Z") || rest.endsWith("z")) {
            rest = rest.dropLast(1)
        }

        val timeParts = rest.split(':')
        if (timeParts.size < 2) return null
        val hour = timeParts[0].toIntOrNull() ?: return null
        val minute = timeParts[1].toIntOrNull() ?: return null
        var second = 0
        var millis = 0
        if (timeParts.size >= 3) {
            val secText = timeParts[2]
            val dot = secText.indexOfFirst { it == '.' || it == ',' }
            if (dot >= 0) {
                second = secText.substring(0, dot).toIntOrNull() ?: return null
                val frac = secText.substring(dot + 1).takeWhile { it.isDigit() }
                millis = when {
                    frac.isEmpty() -> 0
                    else -> (frac.take(3).padEnd(3, '0')).toIntOrNull() ?: 0
                }
            } else {
                second = secText.toIntOrNull() ?: return null
            }
        }
        return toEpochMillis(year, month, day, hour, minute, second, millis, offsetSeconds)
    }

    // ---- RFC-822 / RFC-1123 and the asctime shape --------------------------------------------

    private fun parseRfc822(input: String): Long? {
        val tokens = input.split(' ', '\t', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.size < 3) return null

        // Drop a leading weekday, correct or not — nothing depends on it.
        var t = tokens
        if (monthIndex(t[0]) == null && t[0].toIntOrNull() == null) t = t.drop(1)
        if (t.size < 3) return null

        // asctime: "Sep 23 10:11:21 2022" once the weekday is gone.
        if (monthIndex(t[0]) != null) return parseAsctime(t)

        val day = t[0].toIntOrNull() ?: return null
        val month = monthIndex(t.getOrNull(1)) ?: return null
        val year = normalizeYear(t.getOrNull(2)?.toIntOrNull() ?: return null)
        val time = t.getOrNull(3)
        if (time == null) return toEpochMillis(year, month, day, 0, 0, 0, 0, 0)

        val (h, mi, sec) = splitTime(time) ?: return null
        val offsetSeconds = zoneSeconds(t.getOrNull(4))
        return toEpochMillis(year, month, day, h, mi, sec, 0, offsetSeconds)
    }

    private fun parseAsctime(t: List<String>): Long? {
        val month = monthIndex(t[0]) ?: return null
        val day = t.getOrNull(1)?.toIntOrNull() ?: return null
        val (h, mi, sec) = splitTime(t.getOrNull(2) ?: return null) ?: return null
        // "Sep 23 10:11:21 GMT 2022" and "Sep 23 10:11:21 2022" both occur.
        val tail = t.drop(3)
        val year = normalizeYear(tail.firstNotNullOfOrNull { it.toIntOrNull() } ?: return null)
        val zone = tail.firstOrNull { it.toIntOrNull() == null }
        return toEpochMillis(year, month, day, h, mi, sec, 0, zoneSeconds(zone))
    }

    private fun splitTime(text: String): Triple<Int, Int, Int>? {
        val parts = text.split(':')
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = if (parts.size >= 3) parts[2].takeWhile { it.isDigit() }.toIntOrNull() ?: 0 else 0
        return Triple(h, m, s)
    }

    /** RFC-822 allows two-digit years; the usual pivot is 50. */
    private fun normalizeYear(year: Int): Int = when {
        year in 0..49 -> 2000 + year
        year in 50..99 -> 1900 + year
        else -> year
    }

    private fun monthIndex(token: String?): Int? {
        val t = token?.trim()?.lowercase() ?: return null
        if (t.length < 3) return null
        return when (t.take(3)) {
            "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
            "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12
            else -> null
        }
    }

    /** RFC-822 says an unknown zone means "unknown", which in practice is treated as UTC. */
    private fun zoneSeconds(token: String?): Int {
        val z = token?.trim() ?: return 0
        if (z.isEmpty()) return 0
        numericZoneSeconds(z)?.let { return it }
        return when (z.uppercase()) {
            "GMT", "UT", "UTC", "Z", "UCT" -> 0
            "EST" -> -5 * 3600
            "EDT" -> -4 * 3600
            "CST" -> -6 * 3600
            "CDT" -> -5 * 3600
            "MST" -> -7 * 3600
            "MDT" -> -6 * 3600
            "PST" -> -8 * 3600
            "PDT" -> -7 * 3600
            "IST" -> 5 * 3600 + 1800     // India, by far the most likely IST in these feeds
            else -> militaryZoneSeconds(z)
        }
    }

    /** Single-letter military zones. 'J' is deliberately not a zone; 'Z' is UTC. */
    private fun militaryZoneSeconds(z: String): Int {
        if (z.length != 1) return 0
        val c = z[0].uppercaseChar()
        return when (c) {
            in 'A'..'I' -> (c - 'A' + 1) * 3600
            in 'K'..'M' -> (c - 'K' + 10) * 3600
            in 'N'..'Y' -> -((c - 'N' + 1) * 3600)
            else -> 0
        }
    }

    /** "+0530", "+05:30", "-08", "Z". Null when it is not a numeric offset at all. */
    private fun numericZoneSeconds(token: String): Int? {
        val z = token.trim()
        if (z.equals("Z", ignoreCase = true)) return 0
        if (z.isEmpty()) return null
        val sign = when (z[0]) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val digits = z.substring(1).replace(":", "")
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
        val hours: Int
        val minutes: Int
        when (digits.length) {
            1, 2 -> { hours = digits.toInt(); minutes = 0 }
            3 -> { hours = digits.substring(0, 1).toInt(); minutes = digits.substring(1).toInt() }
            4 -> { hours = digits.substring(0, 2).toInt(); minutes = digits.substring(2).toInt() }
            else -> return null
        }
        if (hours > 14 || minutes > 59) return null
        return sign * (hours * 3600 + minutes * 60)
    }

    // ---- civil date -> epoch ------------------------------------------------------------------

    private fun toEpochMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        millis: Int,
        offsetSeconds: Int
    ): Long? {
        if (month !in 1..12 || day !in 1..31) return null
        if (hour !in 0..24 || minute !in 0..59 || second !in 0..60) return null
        if (year < 1 || year > 9999) return null
        val days = daysFromCivil(year, month, day)
        val seconds = days * 86400L + hour * 3600L + minute * 60L +
            second.coerceAtMost(59).toLong() - offsetSeconds
        return seconds * 1000L + millis
    }

    /** Howard Hinnant's days_from_civil: exact, branch-free, and no Calendar in sight. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400                                   // [0, 399]
        val mp = (month + 9) % 12                                 // Mar = 0 … Feb = 11
        val doy = (153 * mp + 2) / 5 + day - 1                    // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy           // [0, 146096]
        return era * 146097 + doe - 719468
    }
}
