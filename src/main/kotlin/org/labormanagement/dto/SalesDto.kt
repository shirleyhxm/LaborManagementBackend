package org.labormanagement.dto

import org.labormanagement.model.SalesRecord
import org.labormanagement.model.SalesPerformance
import org.labormanagement.model.DailySalesSummary
import org.labormanagement.model.EmployeeSalesSnapshot
import java.util.*

/**
 * Request to record a sale
 */
data class RecordSaleRequest(
    val employeeId: String,
    val amount: Double,
    val category: String = "GENERAL",
    val description: String = "",
    val scheduleId: String? = null,
    val shiftId: String? = null
)

/**
 * Response for a sales record
 */
data class SalesRecordResponse(
    val id: String,
    val employeeId: String,
    val scheduleId: String?,
    val shiftId: String?,
    val amount: Double,
    val category: String,
    val description: String,
    val recordedAt: String,
    val createdBy: String
)

/**
 * Response for sales performance
 */
data class SalesPerformanceResponse(
    val employeeId: String,
    val startDate: String,
    val endDate: String,
    val totalSales: Double,
    val totalTransactions: Int,
    val averageSaleAmount: Double,
    val hoursWorked: Double,
    val salesPerHour: Double,
    val targetSales: Double?,
    val performanceRate: Double?
)

/**
 * Response for daily sales summary
 */
data class DailySalesSummaryResponse(
    val date: String,
    val totalSales: Double,
    val totalTransactions: Int,
    val topEmployees: List<EmployeeSalesSnapshotResponse>
)

/**
 * Response for employee sales snapshot
 */
data class EmployeeSalesSnapshotResponse(
    val employeeId: String,
    val employeeName: String,
    val totalSales: Double,
    val totalTransactions: Int
)

/**
 * Extension functions for conversion
 */
fun SalesRecord.toResponse(): SalesRecordResponse {
    return SalesRecordResponse(
        id = id.toString(),
        employeeId = employeeId.toString(),
        scheduleId = scheduleId?.toString(),
        shiftId = shiftId?.toString(),
        amount = amount,
        category = category,
        description = description,
        recordedAt = recordedAt.toString(),
        createdBy = createdBy
    )
}

fun SalesPerformance.toResponse(): SalesPerformanceResponse {
    return SalesPerformanceResponse(
        employeeId = employeeId.toString(),
        startDate = period.startDate.toString(),
        endDate = period.endDate.toString(),
        totalSales = totalSales,
        totalTransactions = totalTransactions,
        averageSaleAmount = averageSaleAmount,
        hoursWorked = hoursWorked,
        salesPerHour = salesPerHour,
        targetSales = targetSales,
        performanceRate = performanceRate
    )
}

fun DailySalesSummary.toResponse(): DailySalesSummaryResponse {
    return DailySalesSummaryResponse(
        date = date.toString(),
        totalSales = totalSales,
        totalTransactions = totalTransactions,
        topEmployees = topEmployees.map { it.toResponse() }
    )
}

fun EmployeeSalesSnapshot.toResponse(): EmployeeSalesSnapshotResponse {
    return EmployeeSalesSnapshotResponse(
        employeeId = employeeId.toString(),
        employeeName = employeeName,
        totalSales = totalSales,
        totalTransactions = totalTransactions
    )
}
