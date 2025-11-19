package org.labormanagement.service

import org.labormanagement.model.*
import org.labormanagement.repository.SalesRepository
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.ScheduleRepository
import org.labormanagement.repository.AttendanceRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

/**
 * Service for managing sales records and performance tracking
 */
class SalesService(
    private val salesRepository: SalesRepository,
    private val employeeRepository: EmployeeRepository,
    private val scheduleRepository: ScheduleRepository,
    private val attendanceRepository: AttendanceRepository
) {
    /**
     * Record a sale
     */
    fun recordSale(
        employeeId: UUID,
        amount: Double,
        category: String = "GENERAL",
        description: String = "",
        scheduleId: UUID? = null,
        shiftId: UUID? = null,
        recordedBy: String
    ): Result<SalesRecord> {
        // Verify employee exists
        val employee = employeeRepository.findById(employeeId)
            ?: return Result.failure(Exception("Employee not found"))

        // Validate amount
        if (amount <= 0) {
            return Result.failure(Exception("Sale amount must be greater than zero"))
        }

        // Verify schedule and shift if provided
        if (scheduleId != null) {
            val schedule = scheduleRepository.findById(scheduleId)
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

        val salesRecord = SalesRecord(
            employeeId = employeeId,
            scheduleId = scheduleId,
            shiftId = shiftId,
            amount = amount,
            category = category,
            description = description,
            createdBy = recordedBy
        )

        val created = salesRepository.create(salesRecord)

        // Update employee productivity metric
        updateEmployeeProductivity(employeeId)

        return Result.success(created)
    }

    /**
     * Get sales records for an employee
     */
    fun getSalesByEmployee(employeeId: UUID): List<SalesRecord> {
        return salesRepository.findByEmployeeId(employeeId)
    }

    /**
     * Get sales records for a shift
     */
    fun getSalesByShift(shiftId: UUID): List<SalesRecord> {
        return salesRepository.findByShiftId(shiftId)
    }

    /**
     * Get sales records for a schedule
     */
    fun getSalesBySchedule(scheduleId: UUID): List<SalesRecord> {
        return salesRepository.findByScheduleId(scheduleId)
    }

    /**
     * Get sales performance for an employee in a period
     */
    fun getSalesPerformance(
        employeeId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<SalesPerformance> {
        // Verify employee exists
        val employee = employeeRepository.findById(employeeId)
            ?: return Result.failure(Exception("Employee not found"))

        val startInstant = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endInstant = endDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant()

        val salesRecords = salesRepository.findByEmployeeIdAndDateRange(employeeId, startInstant, endInstant)

        val totalSales = salesRecords.sumOf { it.amount }
        val totalTransactions = salesRecords.size
        val averageSaleAmount = if (totalTransactions > 0) totalSales / totalTransactions else 0.0

        // Get hours worked from attendance
        val hoursWorked = attendanceRepository.getTotalHoursWorked(employeeId, startInstant, endInstant)

        val salesPerHour = if (hoursWorked > 0) totalSales / hoursWorked else 0.0

        // Calculate target sales (based on employee productivity and hours worked)
        val targetSales = if (hoursWorked > 0) employee.productivity * hoursWorked else null

        val performanceRate = if (targetSales != null && targetSales > 0) {
            (totalSales / targetSales) * 100
        } else null

        val performance = SalesPerformance(
            employeeId = employeeId,
            period = SalesPeriod(startDate, endDate),
            totalSales = totalSales,
            totalTransactions = totalTransactions,
            averageSaleAmount = averageSaleAmount,
            hoursWorked = hoursWorked,
            salesPerHour = salesPerHour,
            targetSales = targetSales,
            performanceRate = performanceRate
        )

        return Result.success(performance)
    }

    /**
     * Update employee productivity based on actual sales performance
     */
    private fun updateEmployeeProductivity(employeeId: UUID) {
        val employee = employeeRepository.findById(employeeId) ?: return

        // Calculate productivity over last 30 days
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(30)
        val startInstant = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endInstant = endDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant()

        val totalSales = salesRepository.getTotalSales(employeeId, startInstant, endInstant)
        val hoursWorked = attendanceRepository.getTotalHoursWorked(employeeId, startInstant, endInstant)

        if (hoursWorked > 0) {
            val actualProductivity = totalSales / hoursWorked

            // Update employee productivity with weighted average (70% old, 30% new)
            val updatedProductivity = (employee.productivity * 0.7) + (actualProductivity * 0.3)

            val updatedEmployee = employee.copy(productivity = updatedProductivity)
            employeeRepository.update(employeeId, updatedEmployee)
        }
    }

    /**
     * Get daily sales summary
     */
    fun getDailySalesSummary(date: LocalDate): DailySalesSummary {
        val startInstant = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endInstant = date.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant()

        val salesRecords = salesRepository.findByDateRange(startInstant, endInstant)

        val totalSales = salesRecords.sumOf { it.amount }
        val totalTransactions = salesRecords.size

        // Group by employee and calculate top performers
        val employeeSales = salesRecords.groupBy { it.employeeId }
            .map { (employeeId, records) ->
                val employee = employeeRepository.findById(employeeId)
                EmployeeSalesSnapshot(
                    employeeId = employeeId,
                    employeeName = employee?.let { "${it.firstName} ${it.lastName}" } ?: "Unknown",
                    totalSales = records.sumOf { it.amount },
                    totalTransactions = records.size
                )
            }
            .sortedByDescending { it.totalSales }
            .take(10)

        return DailySalesSummary(
            date = date,
            totalSales = totalSales,
            totalTransactions = totalTransactions,
            topEmployees = employeeSales
        )
    }

    /**
     * Get sales record by ID
     */
    fun getSalesRecordById(id: UUID): SalesRecord? {
        return salesRepository.findById(id)
    }

    /**
     * Delete a sales record (admin only)
     */
    fun deleteSalesRecord(id: UUID): Result<Unit> {
        val deleted = salesRepository.delete(id)
        return if (deleted) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Sales record not found"))
        }
    }
}
