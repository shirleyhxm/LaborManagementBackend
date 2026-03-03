package org.labormanagement.dto

import org.labormanagement.model.OptimizationObjective
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Simplified optimization request for v2 API.
 * Only requires demand matrix - fetches employees, constraints, and sales forecasts from repositories.
 * Optionally can filter to specific employees or use all available employees.
 * Note: businessId is now extracted from the route path parameter.
 */
data class OptimizationRequestV2(
    val demandMatrix: DemandMatrixDto,
    val employeeIds: List<String>? = null,  // Optional: specific employee UUIDs. If null, uses all employees
    val objective: ObjectiveConfigDto = ObjectiveConfigDto(primary = "BALANCED"),
    val requestedBy: String? = null  // Optional: defaults to "v2-api" if not provided
)

/**
 * Demand matrix representing coverage requirements across time.
 */
data class DemandMatrixDto(
    val startDate: String,  // ISO date format (YYYY-MM-DD)
    val endDate: String,
    val timeSlotMinutes: Int = 60,  // Default 1-hour slots
    val slots: List<DemandSlotDto>
)

data class DemandSlotDto(
    val day: String,  // "MONDAY", "TUESDAY", etc.
    val startTime: String,  // "HH:mm" format
    val endTime: String,
    val requiredCount: Int,  // Number of workers needed
    val skillsNeeded: List<String> = emptyList()
)

/**
 * Optimization objective configuration.
 */
data class ObjectiveConfigDto(
    val primary: String,  // "MINIMIZE_COST", "MINIMIZE_HOURS", "MAXIMIZE_COVERAGE", "BALANCE_WORKLOAD"
    val weights: Map<String, Double>? = null  // For multi-objective optimization
)

/**
 * Response containing job ID for async tracking.
 */
data class OptimizationJobResponse(
    val jobId: String,
    val status: String,  // "QUEUED", "RUNNING", "COMPLETED", "FAILED"
    val message: String? = null
)

/**
 * Optimization job status and results.
 */
data class OptimizationJobStatus(
    val jobId: String,
    val status: String,  // "QUEUED", "RUNNING", "COMPLETED", "FAILED"
    val solveStatus: String? = null,  // "OPTIMAL", "FEASIBLE", "INFEASIBLE", "TIMEOUT"
    val progress: Int? = null,  // 0-100
    val solveTimeMs: Long? = null,
    val results: OptimizationResultDto? = null,
    val error: String? = null,
    val createdAt: String,
    val completedAt: String? = null
)

/**
 * Optimization result with assignments and metrics.
 */
data class OptimizationResultDto(
    val solveStatus: String,  // "OPTIMAL", "FEASIBLE", "INFEASIBLE", "TIMEOUT"
    val isOptimal: Boolean,
    val solveTimeMs: Long,
    val assignments: List<AssignmentDto>,
    val metrics: ResultMetricsDto,
    val violations: List<String> = emptyList()
)

data class AssignmentDto(
    val workerId: String,
    val workerName: String,
    val shifts: List<ShiftDto>,
    val totalHours: Double,
    val totalPay: Double,
    val utilization: Double  // Percentage of max hours
)

data class ShiftDto(
    val day: String,  // "MONDAY", "TUESDAY", etc.
    val startTime: String,  // "HH:mm"
    val endTime: String,
    val duration: Double,  // hours
    val payRate: Double,
    val isOvertime: Boolean
)

data class ResultMetricsDto(
    val totalLaborCost: Double,
    val totalHours: Double,
    val coveragePercent: Double,
    val averageUtilization: Double,
    val numberOfWorkers: Int,
    val numberOfShifts: Int,
    val comparisonToBaseline: ComparisonMetricsDto? = null
)

data class ComparisonMetricsDto(
    val costSavingsPercent: Double,
    val hoursSavedPercent: Double,
    val coverageImprovement: Double
)
