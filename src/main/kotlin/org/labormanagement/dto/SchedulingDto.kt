package org.labormanagement.dto

import org.labormanagement.model.Employee
import org.labormanagement.model.OperatingHours
import org.labormanagement.model.SchedulingOutput
import org.labormanagement.model.SchedulingPeriod
import org.labormanagement.model.Shift
import java.time.DayOfWeek
import java.time.LocalTime

data class GenerateScheduleRequest(
    val employeeIds: List<String>,
    val laborCostBudget: Double,
    val salesForecast: Map<String, Map<String, Double>>, // DayOfWeek -> Time -> Sales
    val schedulingPeriod: SchedulingPeriodDto
    // Note: Configuration settings (minShiftDurationHours, optimizationObjective) are managed via /api/configuration
)

data class SchedulingPeriodDto(
    val daysToSchedule: List<String>,
    val operatingHours: Map<String, OperatingHoursDto>
)

data class OperatingHoursDto(
    val openTime: String,
    val closeTime: String
)

data class ShiftResponse(
    val id: String,
    val employeeId: String,
    val employeeName: String,
    val dayOfWeek: String,
    val startTime: String,
    val endTime: String,
    val durationHours: Double,
    val payRate: Double,
    val laborCost: Double,
    val isOvertime: Boolean
)

data class SchedulingResponse(
    val shifts: List<ShiftResponse>,
    val metrics: SchedulingMetricsResponse,
    val violations: List<ConstraintViolationResponse>,
    val staffingRequirements: List<StaffingRequirementResponse>,
    val isValid: Boolean
)

data class StaffingRequirementResponse(
    val dayOfWeek: String,
    val startTime: String,
    val endTime: String,
    val employeesNeeded: Int,
    val employeesAssigned: Int,
    val expectedSales: Double,
    val isUnderstaffed: Boolean,
    val staffingGap: Int
)

data class SchedulingMetricsResponse(
    val totalLaborCost: Double,
    val estimatedTotalSales: Double,
    val laborCostPercentage: Double,
    val employeeUtilization: Map<String, Double>
)

data class ConstraintViolationResponse(
    val type: String,
    val description: String,
    val employeeId: String?
)

// Extension functions
fun Shift.toResponse(employeeName: String): ShiftResponse {
    return ShiftResponse(
        id = this.id.toString(),
        employeeId = this.employeeId.toString(),
        employeeName = employeeName,
        dayOfWeek = this.dayOfWeek.name,
        startTime = this.startTime.toString(),
        endTime = this.endTime.toString(),
        durationHours = this.durationHours,
        payRate = this.payRate,
        laborCost = this.laborCost,
        isOvertime = this.isOvertime
    )
}

fun SchedulingOutput.toResponse(employees: List<Employee>): SchedulingResponse {
    val employeeMap = employees.associateBy { it.id }
    return SchedulingResponse(
        shifts = this.shifts.map { shift ->
            val employee = employeeMap[shift.employeeId]
            shift.toResponse(employee?.fullName ?: "Unknown")
        },
        metrics = SchedulingMetricsResponse(
            totalLaborCost = this.metrics.totalLaborCost,
            estimatedTotalSales = this.metrics.estimatedTotalSales,
            laborCostPercentage = this.metrics.laborCostPercentage,
            employeeUtilization = this.metrics.employeeUtilization
        ),
        violations = this.violations.map {
            ConstraintViolationResponse(
                type = it.type.name,
                description = it.description,
                employeeId = it.employeeId
            )
        },
        staffingRequirements = this.staffingRequirements.map {
            StaffingRequirementResponse(
                dayOfWeek = it.dayOfWeek.name,
                startTime = it.startTime.toString(),
                endTime = it.endTime.toString(),
                employeesNeeded = it.employeesNeeded,
                employeesAssigned = it.employeesAssigned,
                expectedSales = it.expectedSales,
                isUnderstaffed = it.isUnderstaffed,
                staffingGap = it.staffingGap
            )
        },
        isValid = this.isValid()
    )
}

fun SchedulingPeriodDto.toModel(): SchedulingPeriod {
    return SchedulingPeriod(
        daysToSchedule = this.daysToSchedule.map { DayOfWeek.valueOf(it.uppercase()) },
        operatingHours = this.operatingHours.mapKeys { DayOfWeek.valueOf(it.key.uppercase()) }
            .mapValues {
                OperatingHours(
                    openTime = LocalTime.parse(it.value.openTime),
                    closeTime = LocalTime.parse(it.value.closeTime)
                )
            }
    )
}
