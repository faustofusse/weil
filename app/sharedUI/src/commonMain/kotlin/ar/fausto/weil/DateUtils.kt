package ar.fausto.weil

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Copy of the old finance app's only date format: absolute local time,
 * "yyyy-MM-dd HH:mm:ss" — no relative dates, no grouping.
 */
fun formatTimestamp(timestamp: Long): String {
    val dt = Instant.fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val year = dt.year.toString().padStart(4, '0')
    val month = dt.monthNumber.toString().padStart(2, '0')
    val day = dt.dayOfMonth.toString().padStart(2, '0')
    val hour = dt.hour.toString().padStart(2, '0')
    val minute = dt.minute.toString().padStart(2, '0')
    val second = dt.second.toString().padStart(2, '0')
    return "$year-$month-$day $hour:$minute:$second"
}

/** "HH:mm" (24h) for secondary lines under a day header. */
fun timeShort(timestamp: Long): String {
    val dt = Instant.fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return dt.hour.toString().padStart(2, '0') + ":" + dt.minute.toString().padStart(2, '0')
}

/**
 * Journal day header as a value object so localized "Today"/"Yesterday" can
 * be resolved at the composable call site. [DayGroup.key] is the list key.
 */
sealed interface DayGroup {
    val key: String
}

data object DayToday : DayGroup {
    override val key: String get() = "today"
}

data object DayYesterday : DayGroup {
    override val key: String get() = "yesterday"
}

/** An absolute day; [year] is null within the current year. Names are localized at the call site. */
data class DayDate(val weekday: Int, val day: Int, val month: Int, val year: Int?) : DayGroup {
    override val key: String get() = "$year-$month-$day"
}

/** "Today" / "Yesterday" / an absolute [DayDate] for day headers. */
fun dayGroup(timestamp: Long): DayGroup {
    val tz = TimeZone.currentSystemDefault()
    val date = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(tz).date
    val today = Instant.fromEpochMilliseconds(epochMillis()).toLocalDateTime(tz).date
    return when {
        date == today -> DayToday
        today.toEpochDays() == date.toEpochDays() + 1 -> DayYesterday
        else -> DayDate(
            weekday = date.dayOfWeek.ordinal,
            day = date.dayOfMonth,
            month = date.monthNumber,
            year = date.year.takeIf { it != today.year },
        )
    }
}

/** "YYYY-MM-DD" for user-facing input of the transaction date. */
fun dateInputOf(timestamp: Long): String {
    val dt = Instant.fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val month = dt.monthNumber.toString().padStart(2, '0')
    val day = dt.dayOfMonth.toString().padStart(2, '0')
    return dt.year.toString().padStart(4, '0') + "-" + month + "-" + day
}

fun todayInput(): String = dateInputOf(epochMillis())

/** Parses "YYYY-MM-DD" to epoch ms at local midnight; null when malformed. */
fun parseDateInput(text: String): Long? {
    val parts = text.trim().split('-')
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    if (year !in 1..9999 || month !in 1..12 || day !in 1..31) return null
    return try {
        LocalDate(year, month, day)
            .atStartOfDayIn(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()
    } catch (_: IllegalArgumentException) {
        null
    }
}
