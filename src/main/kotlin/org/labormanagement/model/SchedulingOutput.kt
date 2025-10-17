package org.labormanagement.model

import java.time.DayOfWeek
import java.time.LocalTime

data class SchedulingOutput(
    val shifts: List<Shift>,
    val metrics: SchedulingMetrics,
    val violations: List<ConstraintViolation> = emptyList(),
    val staffingRequirements: List<StaffingRequirement> = emptyList()
) {
    fun isValid(): Boolean = violations.isEmpty()
}

data class StaffingRequirement(
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val employeesNeeded: Int,
    val employeesAssigned: Int,
    val expectedSales: Double
) {
    val isUnderstaffed: Boolean
        get() = employeesAssigned < employeesNeeded

    val staffingGap: Int
        get() = maxOf(0, employeesNeeded - employeesAssigned)
}

data class SchedulingMetrics(
    val totalLaborCost: Double,
    val estimatedTotalSales: Double,
    val laborCostPercentage: Double,
    val employeeUtilization: Map<String, Double>
)

data class ConstraintViolation(
    val type: ViolationType,
    val description: String,
    val employeeId: String? = null
)

enum class ViolationType {
    BUDGET_EXCEEDED,
    AVAILABILITY_CONFLICT,
    CONTRACT_HOURS_EXCEEDED,
    MISSING_BREAK,
    SHIFT_OVERLAP,
    UNDERSTAFFING
}
