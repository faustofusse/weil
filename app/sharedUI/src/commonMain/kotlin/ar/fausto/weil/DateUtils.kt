package ar.fausto.weil

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Copy of the old finance app's only date format: absolute local time,
 * "yyyy-MM-dd HH:mm:ss" — no relative dates, no grouping.
 */
fun formatTimestamp(timestamp: Long): String {
    val dt = Instant.fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val year = dt.year.toString().padStart(4, '0')
    val month = (dt.month.ordinal + 1).toString().padStart(2, '0')
    val day = dt.day.toString().padStart(2, '0')
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
            day = date.day,
            month = date.month.ordinal + 1,
            year = date.year.takeIf { it != today.year },
        )
    }
}

/** "YYYY-MM-DD" for user-facing input of the transaction date. */
fun dateInputOf(timestamp: Long): String {
    val dt = Instant.fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val month = (dt.month.ordinal + 1).toString().padStart(2, '0')
    val day = dt.day.toString().padStart(2, '0')
    return dt.year.toString().padStart(4, '0') + "-" + month + "-" + day
}

fun todayInput(): String = dateInputOf(epochMillis())

/**
 * Material 3's `DatePicker` speaks UTC-midnight millis, not local instants:
 * formatting its selection in the local zone lands on the previous day west
 * of Greenwich (UTC-3 turns the 15th 00:00Z into the 14th 21:00). These two
 * translate between the "YYYY-MM-DD" field and the picker in UTC only.
 */
fun pickerMillisOf(text: String?): Long {
    val date = text?.let { parseDateInput(it) }?.let {
        Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).date
    } ?: Instant.fromEpochMilliseconds(epochMillis()).toLocalDateTime(TimeZone.currentSystemDefault()).date
    return date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
}

fun dateInputOfPicker(millis: Long): String {
    val d = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
    return d.year.toString().padStart(4, '0') + "-" +
        (d.month.ordinal + 1).toString().padStart(2, '0') + "-" +
        d.day.toString().padStart(2, '0')
}

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

/** "HH:mm" (24h) for user-facing input of the transaction time. */
fun timeInputOf(timestamp: Long): String {
    val dt = Instant.fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return dt.hour.toString().padStart(2, '0') + ":" + dt.minute.toString().padStart(2, '0')
}

/** Current local time as "HH:mm", so a new transaction defaults to now. */
fun nowTimeInput(): String = timeInputOf(epochMillis())

/** Parses "HH:mm" to an hour/minute pair; null when malformed. */
fun parseTimeInput(text: String): Pair<Int, Int>? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

/** Combines a "YYYY-MM-DD" date and an "HH:mm" time into epoch ms; null when either is malformed. */
fun parseDateTimeInput(dateText: String, timeText: String): Long? {
    val dateParts = dateText.trim().split('-')
    if (dateParts.size != 3) return null
    val year = dateParts[0].toIntOrNull() ?: return null
    val month = dateParts[1].toIntOrNull() ?: return null
    val day = dateParts[2].toIntOrNull() ?: return null
    if (year !in 1..9999 || month !in 1..12 || day !in 1..31) return null
    val (hour, minute) = parseTimeInput(timeText) ?: return null
    return try {
        LocalDateTime(year, month, day, hour, minute)
            .toInstant(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()
    } catch (_: IllegalArgumentException) {
        null
    }
}
