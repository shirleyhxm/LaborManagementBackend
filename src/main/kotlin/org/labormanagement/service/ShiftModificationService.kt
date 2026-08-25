package org.labormanagement.service

import org.labormanagement.model.*
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.ScheduleRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Service for modifying shifts within draft schedules.
 * Enforces business rules: shifts can only be modified in DRAFT schedules.
 */
class ShiftModificationService(
    private val scheduleRepository: ScheduleRepository,
    private val employeeRepository: EmployeeRepository,
    private val constraintValidator: ConstraintValidator,
    private val constraintsService: ConstraintsService = ConstraintsService()
) {

    /**
     * Move or modify a shift within a draft schedule.
     * Can change employee, day, or time.
     */
    fun modifyShift(
        businessId: UUID,
        scheduleId: UUID,
        shiftId: UUID,
        newEmployeeId: UUID? = null,
        newDayOfWeek: DayOfWeek? = null,
        newStartTime: LocalTime? = null,
        newEndTime: LocalTime? = null,
        modifiedBy: String
    ): ShiftModificationResult {
        val schedule = scheduleRepository.findById(businessId, scheduleId)
            ?: throw IllegalArgumentException("Schedule not found: $scheduleId")

        if (!schedule.isDraft) {
            throw IllegalStateException("Cannot modify shifts in ${schedule.status} schedule. Only DRAFT schedules can be edited.")
        }

        val shift = schedule.findShift(shiftId)
            ?: throw IllegalArgumentException("Shift not found: $shiftId")

        // Create modified shift
        val modifiedShift = shift.copy(
            employeeId = newEmployeeId ?: shift.employeeId,
            date = if (newDayOfWeek != null) {
                // Find a date in the schedule period that matches the new day of week
                schedule.schedulePeriod.getAllDates().find { it.dayOfWeek == newDayOfWeek } ?: shift.date
            } else {
                shift.date
            },
            startTime = newStartTime ?: shift.startTime,
            endTime = newEndTime ?: shift.endTime
        )

        // Validate the modification
        val violations = validateShiftModification(schedule, shift, modifiedShift)

        if (violations.isEmpty()) {
            // Replace the shift in the schedule
            val updatedShifts = schedule.shifts.map {
                if (it.id == shiftId) modifiedShift else it
            }

            // Recalculate metrics and staffing requirements with updated shifts
            val employees = schedule.employeeIds.mapNotNull { employeeRepository.findById(businessId, it) }
            val updatedMetrics = calculateScheduleMetrics(updatedShifts, employees, businessId)
            val updatedStaffingRequirements = recalculateStaffingRequirements(
                shifts = updatedShifts,
                originalRequirements = schedule.staffingRequirements,
                employees = employees
            )

            val updatedSchedule = schedule.copy(
                shifts = updatedShifts,
                metrics = updatedMetrics,
                staffingRequirements = updatedStaffingRequirements,
                version = schedule.version + 1,
                lastModifiedAt = Instant.now(),
                lastModifiedBy = modifiedBy
            )

            scheduleRepository.update(scheduleId, updatedSchedule)

            return ShiftModificationResult(
                shift = modifiedShift,
                violations = emptyList(),
                isValid = true
            )
        } else {
            return ShiftModificationResult(
                shift = shift,
                violations = violations,
                isValid = false
            )
        }
    }

    /**
     * Duplicate a shift within the same schedule (creates a new shift)
     */
    fun duplicateShift(
        businessId: UUID,
        scheduleId: UUID,
        shiftId: UUID,
        newEmployeeId: UUID? = null,
        newDayOfWeek: DayOfWeek? = null,
        createdBy: String
    ): DuplicateShiftResult {
        val schedule = scheduleRepository.findById(businessId, scheduleId)
            ?: throw IllegalArgumentException("Schedule not found: $scheduleId")

        if (!schedule.isDraft) {
            throw IllegalStateException("Cannot add shifts to ${schedule.status} schedule. Only DRAFT schedules can be modified.")
        }

        val originalShift = schedule.findShift(shiftId)
            ?: throw IllegalArgumentException("Shift not found: $shiftId")

        val duplicatedShift = Shift(
            id = UUID.randomUUID(),
            employeeId = newEmployeeId ?: originalShift.employeeId,
            date = if (newDayOfWeek != null) {
                // Find a date in the schedule period that matches the new day of week
                schedule.schedulePeriod.getAllDates().find { it.dayOfWeek == newDayOfWeek } ?: originalShift.date
            } else {
                originalShift.date
            },
            startTime = originalShift.startTime,
            endTime = originalShift.endTime,
            payRate = originalShift.payRate,
            isOvertime = originalShift.isOvertime
        )

        // Validate the new shift
        val violations = validateNewShift(schedule, duplicatedShift)

        if (violations.isEmpty()) {
            val updatedShifts = schedule.shifts + duplicatedShift

            // Recalculate metrics and staffing requirements
            val employees = schedule.employeeIds.mapNotNull { employeeRepository.findById(businessId, it) }
            val updatedMetrics = calculateScheduleMetrics(updatedShifts, employees, businessId)
            val updatedStaffingRequirements = recalculateStaffingRequirements(
                shifts = updatedShifts,
                originalRequirements = schedule.staffingRequirements,
                employees = employees
            )

            val updatedSchedule = schedule.copy(
                shifts = updatedShifts,
                metrics = updatedMetrics,
                staffingRequirements = updatedStaffingRequirements,
                version = schedule.version + 1,
                lastModifiedAt = Instant.now(),
                lastModifiedBy = createdBy
            )

            scheduleRepository.update(scheduleId, updatedSchedule)

            return DuplicateShiftResult(
                shift = duplicatedShift,
                violations = emptyList(),
                isValid = true
            )
        } else {
            return DuplicateShiftResult(
                shift = null,
                violations = violations,
                isValid = false
            )
        }
    }

    /**
     * Delete a shift from a draft schedule
     */
    fun deleteShift(
        businessId: UUID,
        scheduleId: UUID,
        shiftId: UUID,
        modifiedBy: String
    ) {
        val schedule = scheduleRepository.findById(businessId, scheduleId)
            ?: throw IllegalArgumentException("Schedule not found: $scheduleId")

        if (!schedule.isDraft) {
            throw IllegalStateException("Cannot delete shifts from ${schedule.status} schedule. Only DRAFT schedules can be modified.")
        }

        schedule.findShift(shiftId)
            ?: throw IllegalArgumentException("Shift not found: $shiftId")

        val updatedShifts = schedule.shifts.filter { it.id != shiftId }

        // Recalculate metrics and staffing requirements
        val employees = schedule.employeeIds.mapNotNull { employeeRepository.findById(businessId, it) }
        val updatedMetrics = calculateScheduleMetrics(updatedShifts, employees, businessId)
        val updatedStaffingRequirements = recalculateStaffingRequirements(
            shifts = updatedShifts,
            originalRequirements = schedule.staffingRequirements,
            employees = employees
        )

        val updatedSchedule = schedule.copy(
            shifts = updatedShifts,
            metrics = updatedMetrics,
            staffingRequirements = updatedStaffingRequirements,
            version = schedule.version + 1,
            lastModifiedAt = Instant.now(),
            lastModifiedBy = modifiedBy
        )

        scheduleRepository.update(scheduleId, updatedSchedule)
    }

    /**
     * Publish a schedule (makes it immutable)
     */
    fun publishSchedule(
        businessId: UUID,
        scheduleId: UUID,
        publishedBy: String
    ): Schedule {
        val schedule = scheduleRepository.findById(businessId, scheduleId)
            ?: throw IllegalArgumentException("Schedule not found: $scheduleId")

        if (schedule.isPublished) {
            throw IllegalStateException("Schedule is already published")
        }

        if (schedule.isArchived) {
            throw IllegalStateException("Cannot publish archived schedule")
        }

        val publishedSchedule = schedule.copy(
            status = ScheduleStatus.PUBLISHED,
            publishedAt = Instant.now(),
            publishedBy = publishedBy,
            version = schedule.version + 1,
            lastModifiedAt = Instant.now(),
            lastModifiedBy = publishedBy
        )

        return scheduleRepository.update(scheduleId, publishedSchedule)
    }

    /**
     * Duplicate an entire schedule (creates new draft from any schedule)
     */
    fun duplicateSchedule(
        businessId: UUID,
        scheduleId: UUID,
        newName: String?,
        createdBy: String
    ): Schedule {
        val originalSchedule = scheduleRepository.findById(businessId, scheduleId)
            ?: throw IllegalArgumentException("Schedule not found: $scheduleId")

        // Create new shifts with new IDs
        val newShifts = originalSchedule.shifts.map { shift ->
            shift.copy(id = UUID.randomUUID())
        }

        val newSchedule = Schedule(
            businessId = businessId,
            id = UUID.randomUUID(),
            name = newName ?: "${originalSchedule.name} (Copy)",
            status = ScheduleStatus.DRAFT,
            schedulePeriod = originalSchedule.schedulePeriod,
            shifts = newShifts,
            metrics = originalSchedule.metrics,
            violations = originalSchedule.violations,
            staffingRequirements = originalSchedule.staffingRequirements,
            employeeIds = originalSchedule.employeeIds,
            laborCostBudget = originalSchedule.laborCostBudget,
            optimizationObjective = originalSchedule.optimizationObjective,
            version = 1,
            createdAt = Instant.now(),
            createdBy = createdBy,
            lastModifiedAt = Instant.now(),
            lastModifiedBy = createdBy
        )

        return scheduleRepository.save(newSchedule)
    }

    /**
     * Validate a shift modification (employee change, time change, etc.)
     */
    private fun validateShiftModification(
        schedule: Schedule,
        originalShift: Shift,
        modifiedShift: Shift
    ): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()

        // Get employee
        val employee = employeeRepository.findById(schedule.businessId, modifiedShift.employeeId)
            ?: return listOf(
                ConstraintViolation.Shift(
                    type = ViolationType.AVAILABILITY_CONFLICT,
                    description = "Employee not found",
                    employeeId = modifiedShift.employeeId,
                    date = modifiedShift.date,
                    startTime = modifiedShift.startTime,
                    endTime = modifiedShift.endTime
                )
            )

        // Check employee availability
        val isAvailable = employee.availability.any {
            it.dayOfWeek == modifiedShift.date.dayOfWeek &&
                    it.startTime <= modifiedShift.startTime &&
                    it.endTime >= modifiedShift.endTime
        }

        if (!isAvailable) {
            violations.add(
                ConstraintViolation.Shift(
                    type = ViolationType.AVAILABILITY_CONFLICT,
                    description = "Employee ${employee.fullName} is not available on ${modifiedShift.date.dayOfWeek} from ${modifiedShift.startTime} to ${modifiedShift.endTime}",
                    employeeId = modifiedShift.employeeId,
                    date = modifiedShift.date,
                    startTime = modifiedShift.startTime,
                    endTime = modifiedShift.endTime
                )
            )
        }

        // Check for shift overlaps (exclude the original shift being modified)
        val otherShifts = schedule.shifts.filter { it.id != originalShift.id }
        val hasOverlap = otherShifts.any {
            it.employeeId == modifiedShift.employeeId && it.overlaps(modifiedShift)
        }

        if (hasOverlap) {
            violations.add(
                ConstraintViolation.Shift(
                    type = ViolationType.SHIFT_OVERLAP,
                    description = "Shift overlaps with another shift for ${employee.fullName}",
                    employeeId = modifiedShift.employeeId,
                    date = modifiedShift.date,
                    startTime = modifiedShift.startTime,
                    endTime = modifiedShift.endTime
                )
            )
        }

        // Check contract hours (calculate weekly total excluding original shift)
        val weeklyHours = otherShifts
            .filter { it.employeeId == modifiedShift.employeeId }
            .sumOf { it.durationHours } + modifiedShift.durationHours

        if (weeklyHours > employee.contract.maxHoursPerWeek) {
            violations.add(
                ConstraintViolation.Employee(
                    type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                    description = "Total weekly hours (${String.format("%.1f", weeklyHours)}) exceeds maximum (${employee.contract.maxHoursPerWeek}) for ${employee.fullName}",
                    employeeId = modifiedShift.employeeId
                )
            )
        }

        // Check daily hours
        if (modifiedShift.durationHours > employee.contract.maxHoursPerDay) {
            violations.add(
                ConstraintViolation.Shift(
                    type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                    description = "Shift duration (${String.format("%.1f", modifiedShift.durationHours)}h) exceeds daily maximum (${employee.contract.maxHoursPerDay}h) for ${employee.fullName}",
                    employeeId = modifiedShift.employeeId,
                    date = modifiedShift.date,
                    startTime = modifiedShift.startTime,
                    endTime = modifiedShift.endTime
                )
            )
        }

        return violations
    }

    /**
     * Validate a new shift (for duplication)
     */
    private fun validateNewShift(
        schedule: Schedule,
        newShift: Shift
    ): List<ConstraintViolation> {
        // For new shifts, we don't have an "original" to exclude, so use empty shift
        val dummyOriginal = newShift.copy(id = UUID.randomUUID())
        return validateShiftModification(schedule, dummyOriginal, newShift)
    }

    /**
     * Calculate schedule metrics (labor cost, estimated sales, utilization)
     */
    private fun calculateScheduleMetrics(
        shifts: List<Shift>,
        employees: List<Employee>,
        businessId: UUID
    ): SchedulingMetrics {
        val totalLaborCost = shifts.sumOf { it.laborCost }

        // Calculate estimated sales based on employee productivity
        val employeeMap = employees.associateBy { it.id }
        val estimatedSales = shifts.sumOf { shift ->
            val employee = employeeMap[shift.employeeId] ?: return@sumOf 0.0
            shift.durationHours * employee.productivity
        }

        val laborCostPercentage = if (estimatedSales > 0) {
            (totalLaborCost / estimatedSales) * 100
        } else {
            0.0
        }

        // Calculate employee utilization
        val utilization = mutableMapOf<String, Double>()
        employees.forEach { employee ->
            val employeeShifts = shifts.filter { it.employeeId == employee.id }
            val scheduledHours = employeeShifts.sumOf { it.durationHours }
            val utilizationRate = (scheduledHours / employee.contract.contractedHoursPerWeek) * 100
            utilization[employee.fullName] = utilizationRate
        }

        return SchedulingMetrics(
            totalLaborCost = totalLaborCost,
            estimatedTotalSales = estimatedSales,
            laborCostPercentage = laborCostPercentage,
            employeeUtilization = utilization,
            totalEmployerOnCost = calculateEmployerOnCost(shifts, businessId)
        )
    }

    /**
     * Computes total employer on-cost (e.g. Employer National Insurance) for
     * a set of shifts, grouped by employee and by the Monday-start week each
     * shift's date falls in. Mirrors ShiftScheduler.calculateEmployerOnCost -
     * kept separate since this class already duplicates
     * calculateScheduleMetrics rather than sharing it with ShiftScheduler.
     */
    private fun calculateEmployerOnCost(shifts: List<Shift>, businessId: UUID): Double {
        val rules = constraintsService.getPayrollCostRules(businessId)
        if (rules == null || !rules.employerNiEnabled) return 0.0

        val weeklyPayByEmployeeAndWeek = mutableMapOf<Pair<UUID, LocalDate>, Double>()
        shifts.forEach { shift ->
            val weekStart = shift.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val key = shift.employeeId to weekStart
            weeklyPayByEmployeeAndWeek[key] = (weeklyPayByEmployeeAndWeek[key] ?: 0.0) + shift.laborCost
        }

        return weeklyPayByEmployeeAndWeek.values.sumOf { weeklyPay ->
            val excess = weeklyPay - rules.employerNiWeeklyThreshold
            if (excess > 0) excess * (rules.employerNiRate / 100.0) else 0.0
        }
    }

    /**
     * Recalculate staffing requirements based on updated shifts
     */
    private fun recalculateStaffingRequirements(
        shifts: List<Shift>,
        originalRequirements: List<StaffingRequirement>,
        employees: List<Employee>
    ): List<StaffingRequirement> {
        // Update each staffing requirement with the new employee count assigned
        return originalRequirements.map { requirement ->
            val assignedCount = shifts.count { shift ->
                shift.date == requirement.date &&
                shift.startTime <= requirement.startTime &&
                shift.endTime >= requirement.endTime
            }

            requirement.copy(employeesAssigned = assignedCount)
        }
    }
}

/**
 * Result of a shift modification operation
 */
data class ShiftModificationResult(
    val shift: Shift,
    val violations: List<ConstraintViolation>,
    val isValid: Boolean
)

/**
 * Result of a shift duplication operation
 */
data class DuplicateShiftResult(
    val shift: Shift?,
    val violations: List<ConstraintViolation>,
    val isValid: Boolean
)