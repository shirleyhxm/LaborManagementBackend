package org.labormanagement.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Represents a complete work schedule with lifecycle management and audit history.
 *
 * This model consolidates:
 * - Schedule data (shifts, metrics, violations, staffing requirements)
 * - Operational lifecycle (DRAFT schedules are editable, PUBLISHED are immutable)
 * - Audit trail (preserves original generation inputs for reproducibility)
 *
 * Lifecycle:
 * - DRAFT: Shifts can be modified, moved, deleted. Schedule is editable.
 * - PUBLISHED: Immutable, cannot modify any shifts. Final version.
 * - ARCHIVED: Historical record, no longer active.
 *
 * All shifts within a schedule share the same status as the parent schedule.
 */
data class Schedule(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val status: ScheduleStatus = ScheduleStatus.DRAFT,
    val schedulePeriod: SchedulePeriod,

    // Schedule data (formerly in SchedulingOutput)
    val shifts: List<Shift>,
    val metrics: SchedulingMetrics,
    val violations: List<ConstraintViolation> = emptyList(),
    val staffingRequirements: List<StaffingRequirement> = emptyList(),

    // Generation input parameters (for historical reference and reproducibility)
    val employeeIds: List<UUID>,
    val laborCostBudget: Double,
    val minShiftDurationHours: Double,
    val optimizationObjective: OptimizationObjective,

    // Lifecycle metadata
    val version: Int = 1,
    val createdAt: Instant = Instant.now(),
    val createdBy: String = "system",
    val publishedAt: Instant? = null,
    val publishedBy: String? = null,
    val lastModifiedAt: Instant = Instant.now(),
    val lastModifiedBy: String = "system",

    // Optional notes
    val notes: String? = null
) {
    // Convenience properties
    val isDraft: Boolean get() = status == ScheduleStatus.DRAFT
    val isPublished: Boolean get() = status == ScheduleStatus.PUBLISHED
    val isArchived: Boolean get() = status == ScheduleStatus.ARCHIVED
    val isEditable: Boolean get() = status == ScheduleStatus.DRAFT

    val totalShifts: Int get() = shifts.size
    val totalLaborCost: Double get() = metrics.totalLaborCost
    val isValid: Boolean get() = violations.isEmpty()

    /**
     * Get shift by ID
     */
    fun findShift(shiftId: UUID): Shift? {
        return shifts.find { it.id == shiftId }
    }

    /**
     * Get all shifts for a specific employee
     */
    fun getShiftsByEmployee(employeeId: UUID): List<Shift> {
        return shifts.filter { it.employeeId == employeeId }
    }
}

/**
 * Schedule lifecycle status
 */
enum class ScheduleStatus {
    /** Shifts can be edited, moved, deleted */
    DRAFT,

    /** Immutable, no changes allowed */
    PUBLISHED,

    /** Historical record */
    ARCHIVED
}

data class SchedulingMetrics(
    val totalLaborCost: Double,
    val estimatedTotalSales: Double,
    val laborCostPercentage: Double,
    val employeeUtilization: Map<String, Double>
)

/**
 * Represents constraint violations at different scopes in the schedule.
 * Using sealed classes provides type-safe access to relevant identifiers
 * for each violation type, enabling the frontend to map violations to
 * the appropriate UI component.
 */
sealed class ConstraintViolation {
    abstract val type: ViolationType
    abstract val description: String

    /**
     * Schedule-level violations (e.g., budget exceeded)
     */
    data class ScheduleLevel(
        override val type: ViolationType,
        override val description: String
    ) : ConstraintViolation()

    /**
     * Time block violations (e.g., understaffing at a specific time)
     */
    data class TimeBlock(
        override val type: ViolationType,
        override val description: String,
        val dayOfWeek: DayOfWeek,
        val startTime: LocalTime,
        val endTime: LocalTime
    ) : ConstraintViolation()

    /**
     * Employee-level violations (e.g., weekly hours exceeded)
     */
    data class Employee(
        override val type: ViolationType,
        override val description: String,
        val employeeId: UUID
    ) : ConstraintViolation()

    /**
     * Employee + Day violations (e.g., daily hours exceeded)
     */
    data class EmployeeDay(
        override val type: ViolationType,
        override val description: String,
        val employeeId: UUID,
        val dayOfWeek: DayOfWeek
    ) : ConstraintViolation()

    /**
     * Shift-level violations (e.g., availability conflict, overlapping shifts)
     */
    data class Shift(
        override val type: ViolationType,
        override val description: String,
        val employeeId: UUID,
        val dayOfWeek: DayOfWeek,
        val startTime: LocalTime,
        val endTime: LocalTime
    ) : ConstraintViolation()
}

enum class ViolationType {
    BUDGET_EXCEEDED,
    AVAILABILITY_CONFLICT,
    CONTRACT_HOURS_EXCEEDED,
    MISSING_BREAK,
    SHIFT_OVERLAP,
    UNDERSTAFFING
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

data class Shift(
    val id: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val payRate: Double,
    val isOvertime: Boolean = false
) {
    // Calculated once during construction, cached for performance and frontend access
    // Handle overnight shifts (e.g., 22:00 to 02:00) by adding 24 hours if endTime < startTime
    val durationHours: Double = run {
        val minutes = if (endTime <= startTime) {
            // Overnight shift: calculate minutes to midnight + minutes from midnight to end
            ChronoUnit.MINUTES.between(startTime, LocalTime.MAX) +
            ChronoUnit.MINUTES.between(LocalTime.MIN, endTime) + 1
        } else {
            // Regular shift within the same day
            ChronoUnit.MINUTES.between(startTime, endTime)
        }
        minutes / 60.0
    }
    val laborCost: Double = durationHours * payRate

    fun overlaps(other: Shift): Boolean {
        if (this.dayOfWeek != other.dayOfWeek) return false
        return this.startTime < other.endTime && this.endTime > other.startTime
    }
}
