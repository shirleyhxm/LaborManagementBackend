package org.labormanagement.dto

import org.labormanagement.model.BusinessDayHours
import org.labormanagement.model.BusinessHourOverride
import org.labormanagement.model.BusinessHours
import org.labormanagement.model.OpenInterval
import org.labormanagement.util.parseFlexibleTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val WIRE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** One open stretch of a day, "HH:mm" to "HH:mm". */
data class OpenIntervalDto(
    val openTime: String,
    val closeTime: String
)

/**
 * One day of the weekly pattern. Times are "HH:mm", matching how shift times already
 * cross the wire.
 *
 * [intervals] is always filled on the way out - one entry for an ordinary day - so a
 * client reads a single shape. On the way in it is optional: a client that only knows
 * [openTime]/[closeTime] still describes a single-stretch day correctly. When both are
 * sent, [intervals] wins and [openTime]/[closeTime] are recomputed from it.
 */
data class BusinessDayHoursDto(
    val dayOfWeek: String,
    val openTime: String = "09:00",
    val closeTime: String = "21:00",
    val isClosed: Boolean = false,
    val intervals: List<OpenIntervalDto>? = null
)

data class BusinessHourOverrideDto(
    val id: String? = null,
    val date: String,
    val openTime: String = "09:00",
    val closeTime: String = "21:00",
    val isClosed: Boolean = false,
    val label: String? = null,
    val intervals: List<OpenIntervalDto>? = null
)

/**
 * A business's hours as the client sees them.
 *
 * [week] always carries seven days even for a business that has saved none, so the editor
 * can render a full week without knowing whether anything was configured; the days are
 * filled from the business's legacy default hours in that case.
 */
data class BusinessHoursResponse(
    val businessId: String,
    val week: List<BusinessDayHoursDto>,
    val overrides: List<BusinessHourOverrideDto>
)

/** Body of PUT /businesses/{id}/hours - the whole week at once. */
data class UpdateBusinessHoursRequest(
    val week: List<BusinessDayHoursDto>
)

/** The stretches as sent, or the single [open]..[close] stretch when none were. */
private fun parseIntervals(
    intervals: List<OpenIntervalDto>?,
    open: String,
    close: String
): List<OpenInterval> =
    intervals?.takeIf { it.isNotEmpty() }
        // parseFlexibleTime rather than LocalTime.parse: a business that closes at
        // midnight is naturally written "24:00", which LocalTime rejects outright.
        ?.map { OpenInterval(parseFlexibleTime(it.openTime), parseFlexibleTime(it.closeTime)) }
        ?: listOf(OpenInterval(parseFlexibleTime(open), parseFlexibleTime(close)))

/** Every stretch of the day, including the single one an ordinary day has. */
private fun renderIntervals(
    intervals: List<OpenInterval>,
    open: LocalTime,
    close: LocalTime
): List<OpenIntervalDto> =
    intervals.ifEmpty { listOf(OpenInterval(open, close)) }
        .map { OpenIntervalDto(it.openTime.format(WIRE_TIME), it.closeTime.format(WIRE_TIME)) }

fun BusinessDayHours.toDto() = BusinessDayHoursDto(
    dayOfWeek = dayOfWeek.name,
    openTime = openTime.format(WIRE_TIME),
    closeTime = closeTime.format(WIRE_TIME),
    isClosed = isClosed,
    intervals = renderIntervals(intervals, openTime, closeTime)
)

fun BusinessDayHoursDto.toModel(): BusinessDayHours {
    val stretches = parseIntervals(intervals, openTime, closeTime)
    return BusinessDayHours(
        dayOfWeek = DayOfWeek.valueOf(dayOfWeek.uppercase()),
        // The span is derived rather than trusted, so a client that sends stretches and a
        // stale open/close cannot leave the two disagreeing.
        openTime = stretches.first().openTime,
        closeTime = stretches.last().closeTime,
        isClosed = isClosed,
        intervals = stretches.takeIf { it.size > 1 } ?: emptyList()
    )
}

fun BusinessHourOverride.toDto() = BusinessHourOverrideDto(
    id = id.toString(),
    date = date.toString(),
    openTime = openTime.format(WIRE_TIME),
    closeTime = closeTime.format(WIRE_TIME),
    isClosed = isClosed,
    label = label,
    intervals = renderIntervals(intervals, openTime, closeTime)
)

fun BusinessHourOverrideDto.toModel(businessId: UUID): BusinessHourOverride {
    val stretches = parseIntervals(intervals, openTime, closeTime)
    return BusinessHourOverride(
        id = id?.let { UUID.fromString(it) } ?: UUID.randomUUID(),
        businessId = businessId,
        date = LocalDate.parse(date),
        openTime = stretches.first().openTime,
        closeTime = stretches.last().closeTime,
        isClosed = isClosed,
        label = label?.takeIf { it.isNotBlank() },
        intervals = stretches.takeIf { it.size > 1 } ?: emptyList()
    )
}

/**
 * Render hours for the client, filling in any weekday the business has not saved.
 *
 * The gap is filled from [BusinessHours.fallback] - the legacy default pair - so a
 * business that has never opened the editor still gets a sensible seven-day week rather
 * than a partial one the UI would have to special-case.
 */
fun BusinessHours.toResponse(): BusinessHoursResponse {
    val saved = week.associateBy { it.dayOfWeek }
    return BusinessHoursResponse(
        businessId = businessId.toString(),
        week = DayOfWeek.entries.map { day ->
            saved[day]?.toDto() ?: BusinessDayHoursDto(
                dayOfWeek = day.name,
                openTime = fallback.openTime.format(WIRE_TIME),
                closeTime = fallback.closeTime.format(WIRE_TIME),
                isClosed = false,
                intervals = renderIntervals(emptyList(), fallback.openTime, fallback.closeTime)
            )
        },
        overrides = overrides.map { it.toDto() }
    )
}
