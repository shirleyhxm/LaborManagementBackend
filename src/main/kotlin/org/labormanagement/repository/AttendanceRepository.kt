package org.labormanagement.repository

import org.labormanagement.model.ClockRecord
import java.time.Instant
import java.time.LocalDate
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing clock in/out records
 */
class AttendanceRepository {
    private val clockRecords = ConcurrentHashMap<UUID, ClockRecord>()

    /**
     * Create a new clock record
     */
    fun create(record: ClockRecord): ClockRecord {
        clockRecords[record.id] = record
        return record
    }

    /**
     * Find a clock record by ID
     */
    fun findById(id: UUID): ClockRecord? {
        return clockRecords[id]
    }

    /**
     * Find all clock records
     */
    fun findAll(): List<ClockRecord> {
        return clockRecords.values.toList()
    }

    /**
     * Find clock records by employee ID
     */
    fun findByEmployeeId(employeeId: UUID): List<ClockRecord> {
        return clockRecords.values.filter { it.employeeId == employeeId }
    }

    /**
     * Find active clock record (clocked in, not clocked out) for an employee
     */
    fun findActiveByEmployeeId(employeeId: UUID): ClockRecord? {
        return clockRecords.values.firstOrNull {
            it.employeeId == employeeId && it.isActive()
        }
    }

    /**
     * Find clock records by shift ID
     */
    fun findByShiftId(shiftId: UUID): List<ClockRecord> {
        return clockRecords.values.filter { it.shiftId == shiftId }
    }

    /**
     * Find clock records by schedule ID
     */
    fun findByScheduleId(scheduleId: UUID): List<ClockRecord> {
        return clockRecords.values.filter { it.scheduleId == scheduleId }
    }

    /**
     * Find clock records within a date range
     */
    fun findByDateRange(employeeId: UUID, startDate: Instant, endDate: Instant): List<ClockRecord> {
        return clockRecords.values.filter { record ->
            record.employeeId == employeeId &&
            !record.clockInTime.isBefore(startDate) &&
            !record.clockInTime.isAfter(endDate)
        }
    }

    /**
     * Update a clock record
     */
    fun update(record: ClockRecord): ClockRecord {
        clockRecords[record.id] = record.copy(updatedAt = Instant.now())
        return clockRecords[record.id]!!
    }

    /**
     * Delete a clock record
     */
    fun delete(id: UUID): Boolean {
        return clockRecords.remove(id) != null
    }

    /**
     * Delete all clock records (for testing)
     */
    fun deleteAll() {
        clockRecords.clear()
    }

    /**
     * Get total hours worked by an employee in a date range
     */
    fun getTotalHoursWorked(employeeId: UUID, startDate: Instant, endDate: Instant): Double {
        return findByDateRange(employeeId, startDate, endDate)
            .mapNotNull { it.durationHours }
            .sum()
    }
}
