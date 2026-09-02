package org.labormanagement.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class Employee(
    val id: UUID = UUID.randomUUID(),
    val businessId: UUID,  // Multi-tenancy: Business this employee belongs to
    val userId: String? = null,  // Linked login account, set once an invite is accepted
    val firstName: String,
    val lastName: String,
    val middleName: String = "",
    val dateOfBirth: LocalDate,
    val normalPayRate: Double,
    val overtimePayRate: Double,
    val productivity: Double, // Sales ($) per hour
    val contract: Contract,
    val availability: List<Availability>,
    val groups: Set<String> = emptySet(), // Tag-based group membership (e.g., "Sales", "Management")
    // A manager's record exists so their login has somewhere to hang - it is
    // not a person to be scheduled. Kept off the roster and out of schedule
    // generation unless someone deliberately puts them on it.
    val schedulable: Boolean = true
) {
    val fullName: String
        get() = if (middleName.isNotEmpty()) "$firstName $middleName $lastName" else "$firstName $lastName"
}

private const val MINUTES_PER_DAY = 24 * 60

data class Availability(
    // Support both recurring patterns and specific date ranges
    val availabilityType: AvailabilityType,
    val dayOfWeek: DayOfWeek? = null,           // For WEEKLY_RECURRING
    val specificDate: LocalDate? = null,         // For SPECIFIC_DATE
    val dateRange: DateRange? = null,            // For DATE_RANGE
    val startTime: LocalTime,
    val endTime: LocalTime
) {
    /**
     * This window's end as minutes from midnight, with 00:00 meaning the end of the
     * day rather than the start of it.
     *
     * A window running to midnight is stored as a LocalTime of 00:00, which is the
     * *smallest* LocalTime there is. Compared directly, `end <= 00:00` is false for
     * every real end time, so a window like 08:00-00:00 — an employee available all
     * evening — matches nothing at all and reads as no availability whatsoever.
     *
     * Only the end is normalized: a start of 00:00 genuinely means midnight at the
     * beginning of the day, and is already the lowest possible bound.
     */
    val endMinuteOfDay: Int
        get() = if (endTime == LocalTime.MIDNIGHT) MINUTES_PER_DAY else endTime.toSecondOfDay() / 60

    /** True when [start]..[end] falls entirely inside this window. */
    fun covers(start: LocalTime, end: LocalTime): Boolean {
        val startMinute = start.toSecondOfDay() / 60
        // The requested end gets the same midnight treatment: a shift finishing at
        // 00:00 ends at the close of the day, not before it began.
        val endMinute = if (end == LocalTime.MIDNIGHT) MINUTES_PER_DAY else end.toSecondOfDay() / 60
        return startMinute >= this.startTime.toSecondOfDay() / 60 && endMinute <= endMinuteOfDay
    }

    fun isAvailableOn(date: LocalDate, startTime: LocalTime, endTime: LocalTime): Boolean {
        val timeMatches = covers(startTime, endTime)

        return when (availabilityType) {
            AvailabilityType.WEEKLY_RECURRING ->
                dayOfWeek != null && date.dayOfWeek == dayOfWeek && timeMatches

            AvailabilityType.SPECIFIC_DATE ->
                date == specificDate && timeMatches

            AvailabilityType.DATE_RANGE ->
                dateRange?.contains(date) == true && timeMatches
        }
    }

    // Keep old canWork method for backward compatibility
    fun canWork(shift: Shift): Boolean {
        return isAvailableOn(shift.date, shift.startTime, shift.endTime)
    }
}

enum class AvailabilityType {
    WEEKLY_RECURRING,  // Every Monday, etc.
    SPECIFIC_DATE,     // Only on specific date
    DATE_RANGE         // Date range
}

data class DateRange(
    val startDate: LocalDate,
    val endDate: LocalDate
) {
    fun contains(date: LocalDate): Boolean =
        !date.isBefore(startDate) && !date.isAfter(endDate)
}

/**
 * Assigns an employee to a second location under the same owner.
 *
 * Deliberately not a field on Employee: the employee stays one row owned by
 * their home location, and assignments only widen who can see and schedule
 * them.
 */
data class EmployeeLocation(
    val id: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val businessId: UUID,
    val assignedBy: String,
    val assignedAt: java.time.Instant = java.time.Instant.now()
)

data class Contract(
    val contractedHoursPerWeek: Double,
    val maxHoursPerWeek: Double,
    val maxHoursPerDay: Double,
    val overtimeThreshold: Double, // Hours per week before overtime kicks in
    val requiresBreak: Boolean = true,
    val breakDurationMinutes: Int = 30,
    val shiftLengthThresholdHours: Int = 4,

    // New: Support monthly/custom period constraints
    val maxHoursPerMonth: Double? = null,
    val constraintPeriodDays: Int? = null,  // e.g., 14 for bi-weekly, 30 for monthly
    val maxHoursPerPeriod: Double? = null
)
