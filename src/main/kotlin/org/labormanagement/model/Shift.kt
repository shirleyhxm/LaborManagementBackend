package org.labormanagement.model

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

data class Shift(
    val id: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val payRate: Double,
    val isOvertime: Boolean = false
) {
    // Calculated once during construction, cached for performance and frontend access
    val durationHours: Double = ChronoUnit.MINUTES.between(startTime, endTime) / 60.0
    val laborCost: Double = durationHours * payRate

    fun overlaps(other: Shift): Boolean {
        if (this.dayOfWeek != other.dayOfWeek) return false
        return this.startTime < other.endTime && this.endTime > other.startTime
    }
}