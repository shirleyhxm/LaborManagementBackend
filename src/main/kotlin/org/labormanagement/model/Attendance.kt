package org.labormanagement.model

import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Represents a clock in/out record for an employee
 */
data class ClockRecord(
    val id: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val scheduleId: UUID?,          // Optional: Link to scheduled shift
    val shiftId: UUID?,             // Optional: Link to specific shift
    val clockInTime: Instant,
    val clockOutTime: Instant?,     // Null if still clocked in
    val durationHours: Double?,     // Calculated when clocked out
    val notes: String = "",         // Optional notes (e.g., reason for early/late)
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    /**
     * Check if the employee is currently clocked in
     */
    fun isActive(): Boolean = clockOutTime == null

    /**
     * Calculate the duration in hours between clock in and clock out
     */
    fun calculateDuration(): Double? {
        return clockOutTime?.let {
            val durationMillis = it.toEpochMilli() - clockInTime.toEpochMilli()
            durationMillis / (1000.0 * 60 * 60) // Convert to hours
        }
    }
}

/**
 * Aggregated attendance statistics for an employee
 */
data class AttendanceStats(
    val employeeId: UUID,
    val period: AttendancePeriod,
    val totalHoursWorked: Double,
    val totalScheduledHours: Double,
    val attendanceRate: Double,     // Percentage of scheduled hours worked
    val totalClockRecords: Int,
    val averageHoursPerDay: Double
)

/**
 * Period for attendance statistics
 */
data class AttendancePeriod(
    val startDate: LocalDate,
    val endDate: LocalDate
)
