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
    val durationHours: Double
        get() = ChronoUnit.MINUTES.between(startTime, endTime) / 60.0

    val laborCost: Double
        get() = durationHours * payRate

    fun overlaps(other: Shift): Boolean {
        if (this.dayOfWeek != other.dayOfWeek) return false
        return this.startTime < other.endTime && this.endTime > other.startTime
    }
}