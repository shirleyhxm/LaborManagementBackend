package org.labormanagement.service

import org.labormanagement.model.ClockRecord
import org.labormanagement.model.AttendanceStats
import org.labormanagement.model.AttendancePeriod
import org.labormanagement.repository.AttendanceRepository
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.ScheduleRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Service for managing employee attendance (clock in/out)
 */
class AttendanceService(
    private val attendanceRepository: AttendanceRepository,
    private val employeeRepository: EmployeeRepository,
    private val scheduleRepository: ScheduleRepository
) {
    /**
     * Clock in an employee
     */
    fun clockIn(
        businessId: UUID,
        employeeId: UUID,
        scheduleId: UUID? = null,
        shiftId: UUID? = null,
        notes: String = ""
    ): Result<ClockRecord> {
        // Verify employee exists
        val employee = employeeRepository.findById(businessId, employeeId)
            ?: return Result.failure(Exception("Employee not found"))

        // Check if already clocked in
        val activeRecord = attendanceRepository.findActiveByEmployeeId(businessId, employeeId)
        if (activeRecord != null) {
            return Result.failure(Exception("Employee is already clocked in"))
        }

        // Verify schedule and shift if provided
        if (scheduleId != null) {
            val schedule = scheduleRepository.findById(businessId, scheduleId)
                ?: return Result.failure(Exception("Schedule not found"))

            if (shiftId != null) {
                val shift = schedule.shifts.find { it.id == shiftId }
                    ?: return Result.failure(Exception("Shift not found in schedule"))

                // Verify shift belongs to this employee
                if (shift.employeeId != employeeId) {
                    return Result.failure(Exception("Shift does not belong to this employee"))
                }
            }
        }

        val clockRecord = ClockRecord(
            businessId = businessId,
            employeeId = employeeId,
            scheduleId = scheduleId,
            shiftId = shiftId,
            clockInTime = Instant.now(),
            clockOutTime = null,
            durationHours = null,
            notes = notes
        )

        val created = attendanceRepository.create(clockRecord)
        return Result.success(created)
    }

    /**
     * Clock out an employee
     */
    fun clockOut(
        businessId: UUID,
        employeeId: UUID,
        notes: String = ""
    ): Result<ClockRecord> {
        // Find active clock record
        val activeRecord = attendanceRepository.findActiveByEmployeeId(businessId, employeeId)
            ?: return Result.failure(Exception("Employee is not clocked in"))

        val clockOutTime = Instant.now()
        val duration = activeRecord.calculateDuration() ?: run {
            val durationMillis = clockOutTime.toEpochMilli() - activeRecord.clockInTime.toEpochMilli()
            durationMillis / (1000.0 * 60 * 60)
        }

        val updatedRecord = activeRecord.copy(
            clockOutTime = clockOutTime,
            durationHours = duration,
            notes = if (notes.isNotEmpty()) "${activeRecord.notes}\n$notes".trim() else activeRecord.notes
        )

        val updated = attendanceRepository.update(businessId, updatedRecord)
            ?: return Result.failure(Exception("Failed to update clock record"))
        return Result.success(updated)
    }

    /**
     * Get active clock record for an employee
     */
    fun getActiveClockRecord(businessId: UUID, employeeId: UUID): ClockRecord? {
        return attendanceRepository.findActiveByEmployeeId(businessId, employeeId)
    }

    /**
     * Get all clock records for an employee
     */
    fun getClockRecordsByEmployee(businessId: UUID, employeeId: UUID): List<ClockRecord> {
        return attendanceRepository.findByEmployeeId(businessId, employeeId)
    }

    /**
     * Get clock records for a shift
     */
    fun getClockRecordsByShift(businessId: UUID, shiftId: UUID): List<ClockRecord> {
        return attendanceRepository.findByShiftId(businessId, shiftId)
    }

    /**
     * Get clock records for a schedule
     */
    fun getClockRecordsBySchedule(businessId: UUID, scheduleId: UUID): List<ClockRecord> {
        return attendanceRepository.findByScheduleId(businessId, scheduleId)
    }

    /**
     * Get attendance statistics for an employee in a period
     */
    fun getAttendanceStats(
        businessId: UUID,
        employeeId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<AttendanceStats> {
        // Verify employee exists
        val employee = employeeRepository.findById(businessId, employeeId)
            ?: return Result.failure(Exception("Employee not found"))

        val startInstant = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endInstant = endDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant()

        val clockRecords = attendanceRepository.findByDateRange(businessId, employeeId, startInstant, endInstant)
        val completedRecords = clockRecords.filter { it.clockOutTime != null }

        val totalHoursWorked = completedRecords.mapNotNull { it.durationHours }.sum()

        // Calculate scheduled hours from schedules
        val scheduledHours = calculateScheduledHours(businessId, employeeId, startDate, endDate)

        val attendanceRate = if (scheduledHours > 0) {
            (totalHoursWorked / scheduledHours) * 100
        } else {
            0.0
        }

        val days = ChronoUnit.DAYS.between(startDate, endDate) + 1
        val averageHoursPerDay = if (days > 0) totalHoursWorked / days else 0.0

        val stats = AttendanceStats(
            employeeId = employeeId,
            period = AttendancePeriod(startDate, endDate),
            totalHoursWorked = totalHoursWorked,
            totalScheduledHours = scheduledHours,
            attendanceRate = attendanceRate,
            totalClockRecords = completedRecords.size,
            averageHoursPerDay = averageHoursPerDay
        )

        return Result.success(stats)
    }

    /**
     * Calculate scheduled hours for an employee in a date range, from
     * published schedules' shifts falling on dates within [startDate, endDate].
     */
    private fun calculateScheduledHours(
        businessId: UUID,
        employeeId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): Double {
        val schedules = scheduleRepository.findByBusinessAndStatus(
            businessId,
            org.labormanagement.model.ScheduleStatus.PUBLISHED
        )

        return schedules.sumOf { schedule ->
            schedule.shifts
                .filter { it.employeeId == employeeId && it.date >= startDate && it.date <= endDate }
                .sumOf { it.durationHours }
        }
    }

    /**
     * Get clock record by ID
     */
    fun getClockRecordById(businessId: UUID, id: UUID): ClockRecord? {
        return attendanceRepository.findById(businessId, id)
    }

    /**
     * Delete a clock record (admin only)
     */
    fun deleteClockRecord(businessId: UUID, id: UUID): Result<Unit> {
        val deleted = attendanceRepository.delete(businessId, id)
        return if (deleted) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Clock record not found"))
        }
    }
}
