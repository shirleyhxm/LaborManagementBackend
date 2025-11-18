package org.labormanagement.repository

import org.labormanagement.model.SalesRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing sales records
 */
class SalesRepository {
    private val salesRecords = ConcurrentHashMap<UUID, SalesRecord>()

    /**
     * Create a new sales record
     */
    fun create(record: SalesRecord): SalesRecord {
        salesRecords[record.id] = record
        return record
    }

    /**
     * Find a sales record by ID
     */
    fun findById(id: UUID): SalesRecord? {
        return salesRecords[id]
    }

    /**
     * Find all sales records
     */
    fun findAll(): List<SalesRecord> {
        return salesRecords.values.toList()
    }

    /**
     * Find sales records by employee ID
     */
    fun findByEmployeeId(employeeId: UUID): List<SalesRecord> {
        return salesRecords.values.filter { it.employeeId == employeeId }
    }

    /**
     * Find sales records by shift ID
     */
    fun findByShiftId(shiftId: UUID): List<SalesRecord> {
        return salesRecords.values.filter { it.shiftId == shiftId }
    }

    /**
     * Find sales records by schedule ID
     */
    fun findByScheduleId(scheduleId: UUID): List<SalesRecord> {
        return salesRecords.values.filter { it.scheduleId == scheduleId }
    }

    /**
     * Find sales records within a date range
     */
    fun findByDateRange(startDate: Instant, endDate: Instant): List<SalesRecord> {
        return salesRecords.values.filter { record ->
            !record.recordedAt.isBefore(startDate) &&
            !record.recordedAt.isAfter(endDate)
        }
    }

    /**
     * Find sales records by employee within a date range
     */
    fun findByEmployeeIdAndDateRange(
        employeeId: UUID,
        startDate: Instant,
        endDate: Instant
    ): List<SalesRecord> {
        return salesRecords.values.filter { record ->
            record.employeeId == employeeId &&
            !record.recordedAt.isBefore(startDate) &&
            !record.recordedAt.isAfter(endDate)
        }
    }

    /**
     * Find sales records by category
     */
    fun findByCategory(category: String): List<SalesRecord> {
        return salesRecords.values.filter {
            it.category.equals(category, ignoreCase = true)
        }
    }

    /**
     * Get total sales for an employee in a date range
     */
    fun getTotalSales(employeeId: UUID, startDate: Instant, endDate: Instant): Double {
        return findByEmployeeIdAndDateRange(employeeId, startDate, endDate)
            .sumOf { it.amount }
    }

    /**
     * Get total sales for all employees in a date range
     */
    fun getTotalSalesAllEmployees(startDate: Instant, endDate: Instant): Double {
        return findByDateRange(startDate, endDate)
            .sumOf { it.amount }
    }

    /**
     * Get sales count for an employee in a date range
     */
    fun getSalesCount(employeeId: UUID, startDate: Instant, endDate: Instant): Int {
        return findByEmployeeIdAndDateRange(employeeId, startDate, endDate).size
    }

    /**
     * Update a sales record
     */
    fun update(record: SalesRecord): SalesRecord {
        salesRecords[record.id] = record
        return record
    }

    /**
     * Delete a sales record
     */
    fun delete(id: UUID): Boolean {
        return salesRecords.remove(id) != null
    }

    /**
     * Delete all sales records (for testing)
     */
    fun deleteAll() {
        salesRecords.clear()
    }
}
