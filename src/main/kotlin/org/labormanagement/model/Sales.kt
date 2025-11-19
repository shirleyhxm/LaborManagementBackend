package org.labormanagement.model

import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Represents a sales transaction recorded by an employee
 */
data class SalesRecord(
    val id: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val scheduleId: UUID?,          // Optional: Link to schedule
    val shiftId: UUID?,             // Optional: Link to shift
    val amount: Double,             // Sale amount in dollars
    val category: String = "GENERAL", // Product category
    val description: String = "",    // Optional description
    val recordedAt: Instant = Instant.now(),
    val createdBy: String            // User ID who recorded the sale
)

/**
 * Aggregated sales performance for an employee
 */
data class SalesPerformance(
    val employeeId: UUID,
    val period: SalesPeriod,
    val totalSales: Double,
    val totalTransactions: Int,
    val averageSaleAmount: Double,
    val hoursWorked: Double,        // From attendance records
    val salesPerHour: Double,       // Productivity metric
    val targetSales: Double?,       // Optional target/forecast
    val performanceRate: Double?    // Percentage of target achieved
)

/**
 * Period for sales performance statistics
 */
data class SalesPeriod(
    val startDate: LocalDate,
    val endDate: LocalDate
)

/**
 * Daily sales summary for analytics
 */
data class DailySalesSummary(
    val date: LocalDate,
    val totalSales: Double,
    val totalTransactions: Int,
    val topEmployees: List<EmployeeSalesSnapshot>
)

/**
 * Snapshot of employee sales for a period
 */
data class EmployeeSalesSnapshot(
    val employeeId: UUID,
    val employeeName: String,
    val totalSales: Double,
    val totalTransactions: Int
)
