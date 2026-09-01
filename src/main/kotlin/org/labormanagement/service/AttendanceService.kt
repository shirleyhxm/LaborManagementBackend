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

        // Check if already clocked in - anywhere, not just here. A person can
        // only be in one place at a time, so an open session at another
        // location has to block this one; scoping the check to this business
        // would let them run two clock-ins at once.
        val activeRecord = attendanceRepository.findActiveByEmployeeIdAnyLocation(employeeId)
        if (activeRecord != null) {
            return Result.failure(
                Exception(
                    if (activeRecord.businessId == businessId) "Employee is already clocked in"
                    else "Employee is already clocked in at another location"
                )
            )
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
        // Find the open session wherever it was opened - someone who clocked in
        // at another location still has to be able to clock out, and the portal
        // they use has no location switcher.
        val activeRecord = attendanceRepository.findActiveByEmployeeIdAnyLocation(employeeId)
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

        // Scoped to the record's own location, not the caller's - the session
        // being closed may have been opened somewhere else.
        val updated = attendanceRepository.update(activeRecord.businessId, updatedRecord)
            ?: return Result.failure(Exception("Failed to update clock record"))
        return Result.success(updated)
    }

    /**
     * Get an employee's open clock-in, wherever it was made. Someone can only
     * be clocked in one place at a time, so scoping this to a location would
     * show them as clocked out while a session was still running elsewhere.
     */
    fun getActiveClockRecord(businessId: UUID, employeeId: UUID): ClockRecord? {
        return attendanceRepository.findActiveByEmployeeIdAnyLocation(employeeId)
    }

    /**
     * Get an employee's clock records across every location they work at -
     * attendance is a record of what the person did, not of one location's
     * roster.
     */
    fun getClockRecordsByEmployee(businessId: UUID, employeeId: UUID): List<ClockRecord> {
        return attendanceRepository.findAllByEmployeeId(employeeId)
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

        // Across locations, matching how scheduled hours are counted below.
        val clockRecords = attendanceRepository.findByDateRangeAllLocations(employeeId, startInstant, endInstant)
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
    /**
     * Hours this employee is scheduled for across every location they work at.
     *
     * Counted per person rather than per location so it lines up with the hours
     * they actually worked, which are counted the same way - comparing one
     * location's actual against one location's expected would be internally
     * consistent but would not describe anybody's week.
     */
    private fun calculateScheduledHours(
        businessId: UUID,
        employeeId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): Double {
        return scheduleRepository.findAllShiftsForEmployeeInRange(
            employeeId = employeeId,
            startDate = startDate,
            endDate = endDate,
            status = org.labormanagement.model.ScheduleStatus.PUBLISHED
        ).sumOf { row ->
            java.time.Duration.between(row.startTime, row.endTime).toMinutes() / 60.0
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
