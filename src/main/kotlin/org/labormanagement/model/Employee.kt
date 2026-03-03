package org.labormanagement.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class Employee(
    val id: UUID = UUID.randomUUID(),
    val businessId: UUID,  // Multi-tenancy: Business this employee belongs to
    val firstName: String,
    val lastName: String,
    val middleName: String = "",
    val dateOfBirth: LocalDate,
    val normalPayRate: Double,
    val overtimePayRate: Double,
    val productivity: Double, // Sales ($) per hour
    val contract: Contract,
    val availability: List<Availability>,
    val groups: Set<String> = emptySet() // Tag-based group membership (e.g., "Sales", "Management")
) {
    val fullName: String
        get() = if (middleName.isNotEmpty()) "$firstName $middleName $lastName" else "$firstName $lastName"
}

data class Availability(
    // Support both recurring patterns and specific date ranges
    val availabilityType: AvailabilityType,
    val dayOfWeek: DayOfWeek? = null,           // For WEEKLY_RECURRING
    val specificDate: LocalDate? = null,         // For SPECIFIC_DATE
    val dateRange: DateRange? = null,            // For DATE_RANGE
    val startTime: LocalTime,
    val endTime: LocalTime
) {
    fun isAvailableOn(date: LocalDate, startTime: LocalTime, endTime: LocalTime): Boolean {
        val timeMatches = startTime >= this.startTime && endTime <= this.endTime

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
