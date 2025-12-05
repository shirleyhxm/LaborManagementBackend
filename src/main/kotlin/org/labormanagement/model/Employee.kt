package org.labormanagement.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class Employee(
    val id: UUID = UUID.randomUUID(),
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
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime
) {
    fun canWork(shift: Shift): Boolean {
        return shift.dayOfWeek == this.dayOfWeek &&
                shift.startTime >= this.startTime &&
                shift.endTime <= this.endTime
    }
}

data class Contract(
    val contractedHoursPerWeek: Double,
    val maxHoursPerWeek: Double,
    val maxHoursPerDay: Double,
    val overtimeThreshold: Double, // Hours per week before overtime kicks in
    val requiresBreak: Boolean = true,
    val breakDurationMinutes: Int = 30, // Break duration if shift exceeds breakThresholdMinutes
    val shiftLengthThresholdHours: Int = 4 // Shift length that requires a break
)
