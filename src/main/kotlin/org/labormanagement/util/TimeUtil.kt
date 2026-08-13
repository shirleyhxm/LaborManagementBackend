package org.labormanagement.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Parses a time string that may use the "24:00" convention for end-of-day,
 * which java.time.LocalTime rejects (valid hours are 0-23). "24:00" is
 * normalized to midnight (00:00).
 *
 * Callers that need "24:00" to sort/compare as the end of the day (e.g.
 * finding the latest end time among a set of shifts) must not rely on
 * LocalTime ordering alone, since midnight is otherwise the smallest time.
 */
fun parseFlexibleTime(value: String, formatter: DateTimeFormatter? = null): LocalTime {
    val trimmed = value.trim()
    if (trimmed == "24:00" || trimmed == "24:00:00") {
        return LocalTime.MIDNIGHT
    }
    return if (formatter != null) LocalTime.parse(trimmed, formatter) else LocalTime.parse(trimmed)
}
