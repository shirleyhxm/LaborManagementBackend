package org.labormanagement.dto

import org.labormanagement.model.ClockRecord
import org.labormanagement.model.AttendanceStats
import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Request to clock in
 */
data class ClockInRequest(
    val employeeId: String,
    val scheduleId: String? = null,
    val shiftId: String? = null,
    val notes: String = ""
)

/**
 * Request to clock out
 */
data class ClockOutRequest(
    val employeeId: String,
    val notes: String = ""
)

/**
 * Response for a clock record
 */
data class ClockRecordResponse(
    val id: String,
    val employeeId: String,
    // Where this was clocked. Records now span every location the employee
    // works at, so each one has to say which it belongs to.
    val businessId: String,
    val businessName: String?,
    val scheduleId: String?,
    val shiftId: String?,
    val clockInTime: String,
    val clockOutTime: String?,
    val durationHours: Double?,
    val notes: String,
    val createdAt: String,
    val updatedAt: String,
    val isActive: Boolean
)

/**
 * Response for attendance statistics
 */
data class AttendanceStatsResponse(
    val employeeId: String,
    val startDate: String,
    val endDate: String,
    val totalHoursWorked: Double,
    val totalScheduledHours: Double,
    val attendanceRate: Double,
    val totalClockRecords: Int,
    val averageHoursPerDay: Double
)

/**
 * Extension functions for conversion
 */
fun ClockRecord.toResponse(businessName: String? = null): ClockRecordResponse {
    return ClockRecordResponse(
        id = id.toString(),
        employeeId = employeeId.toString(),
        businessId = businessId.toString(),
        businessName = businessName,
        scheduleId = scheduleId?.toString(),
        shiftId = shiftId?.toString(),
        clockInTime = clockInTime.toString(),
        clockOutTime = clockOutTime?.toString(),
        durationHours = durationHours,
        notes = notes,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        isActive = isActive()
    )
}

fun AttendanceStats.toResponse(): AttendanceStatsResponse {
    return AttendanceStatsResponse(
        employeeId = employeeId.toString(),
        startDate = period.startDate.toString(),
        endDate = period.endDate.toString(),
        totalHoursWorked = totalHoursWorked,
        totalScheduledHours = totalScheduledHours,
        attendanceRate = attendanceRate,
        totalClockRecords = totalClockRecords,
        averageHoursPerDay = averageHoursPerDay
    )
}
