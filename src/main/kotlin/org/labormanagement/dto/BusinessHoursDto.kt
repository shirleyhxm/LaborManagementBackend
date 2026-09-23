package org.labormanagement.dto

import org.labormanagement.model.BusinessDayHours
import org.labormanagement.model.BusinessHourOverride
import org.labormanagement.model.BusinessHours
import org.labormanagement.util.parseFlexibleTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val WIRE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * One day of the weekly pattern. Times are "HH:mm", matching how shift times already
 * cross the wire.
 */
data class BusinessDayHoursDto(
    val dayOfWeek: String,
    val openTime: String = "09:00",
    val closeTime: String = "21:00",
    val isClosed: Boolean = false
)

data class BusinessHourOverrideDto(
    val id: String? = null,
    val date: String,
    val openTime: String = "09:00",
    val closeTime: String = "21:00",
    val isClosed: Boolean = false,
    val label: String? = null
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

fun BusinessDayHours.toDto() = BusinessDayHoursDto(
    dayOfWeek = dayOfWeek.name,
    openTime = openTime.format(WIRE_TIME),
    closeTime = closeTime.format(WIRE_TIME),
    isClosed = isClosed
)

fun BusinessDayHoursDto.toModel() = BusinessDayHours(
    dayOfWeek = DayOfWeek.valueOf(dayOfWeek.uppercase()),
    // parseFlexibleTime rather than LocalTime.parse: a business that closes at midnight
    // is naturally written "24:00", which LocalTime rejects outright.
    openTime = parseFlexibleTime(openTime),
    closeTime = parseFlexibleTime(closeTime),
    isClosed = isClosed
)

fun BusinessHourOverride.toDto() = BusinessHourOverrideDto(
    id = id.toString(),
    date = date.toString(),
    openTime = openTime.format(WIRE_TIME),
    closeTime = closeTime.format(WIRE_TIME),
    isClosed = isClosed,
    label = label
)

fun BusinessHourOverrideDto.toModel(businessId: UUID) = BusinessHourOverride(
    id = id?.let { UUID.fromString(it) } ?: UUID.randomUUID(),
    businessId = businessId,
    date = LocalDate.parse(date),
    openTime = parseFlexibleTime(openTime),
    closeTime = parseFlexibleTime(closeTime),
    isClosed = isClosed,
    label = label?.takeIf { it.isNotBlank() }
)

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
                isClosed = false
            )
        },
        overrides = overrides.map { it.toDto() }
    )
}
