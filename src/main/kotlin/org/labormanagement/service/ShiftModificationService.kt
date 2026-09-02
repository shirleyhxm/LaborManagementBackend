package org.labormanagement.service

import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.model.*
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.ScheduleRepository
import org.labormanagement.repository.TimeoffRepository
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
    private val constraintsService: ConstraintsService = ConstraintsService(),
    private val timeoffRepository: TimeoffRepository = TimeoffRepository(),
    private val planValidator: ShiftPlanValidator = ShiftPlanValidator(),
    private val undoService: ScheduleUndoService = ScheduleUndoService()
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
        val movedShift = shift.copy(
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
        val violations = validateShiftModification(schedule, shift, movedShift)

        if (violations.isEmpty()) {
            // Replace the shift in the schedule
            val movedShifts = schedule.shifts.map {
                if (it.id == shiftId) movedShift else it
            }

            // Moving a shift changes the running hour totals of both the employee who
            // gave it up and the one who received it, so overtime has to be re-derived
            // across each of their whole schedules — not just for the moved shift, whose
            // new owner may be pushed over the threshold on a *later* shift.
            val updatedShifts = recalculateOvertimeFor(
                businessId = businessId,
                shifts = movedShifts,
                employeeIds = setOfNotNull(shift.employeeId, movedShift.employeeId)
            )

            // The recalculation preserves the moved shift's id, but the row it reports on
            // still has to be found by position: when the move makes the shift contiguous
            // with an existing one the two merge into a single block, which keeps the
            // *earliest* row's id — the neighbour's, not necessarily the moved shift's.
            // Report the row the block now starts with.
            val modifiedShift = updatedShifts
                .filter {
                    it.employeeId == movedShift.employeeId &&
                    it.date == movedShift.date &&
                    it.startTime >= movedShift.startTime &&
                    it.startTime < movedShift.endTime
                }
                .minByOrNull { it.startTime }
                ?: movedShift

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

            // Recorded before the write, so an undo returns to the shifts as they were
            // rather than as they are about to become. Only successful edits are
            // snapshotted: a rejected move changes nothing, so there is nothing to undo.
            //
            // Both in one transaction, so a schedule can never end up edited with no way
            // back, or carrying a snapshot for an edit that didn't land.
            transaction {
                undoService.recordSnapshot(scheduleId, schedule.shifts, modifiedBy)
                scheduleRepository.update(scheduleId, updatedSchedule)
            }

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
            // Adding hours can push this employee's later shifts over their overtime
            // threshold, so re-derive the flag across all of their shifts.
            val updatedShifts = recalculateOvertimeFor(
                businessId = businessId,
                shifts = schedule.shifts + duplicatedShift,
                employeeIds = setOf(duplicatedShift.employeeId)
            )

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

            transaction {
                undoService.recordSnapshot(scheduleId, schedule.shifts, createdBy)
                scheduleRepository.update(scheduleId, updatedSchedule)
            }

            return DuplicateShiftResult(
                // As in modifyShift, the duplicate may have been split, so its id may no
                // longer be present — locate the row it now starts with by position.
                shift = updatedShifts
                    .filter {
                        it.employeeId == duplicatedShift.employeeId &&
                        it.date == duplicatedShift.date &&
                        it.startTime >= duplicatedShift.startTime &&
                        it.startTime < duplicatedShift.endTime
                    }
                    .minByOrNull { it.startTime }
                    ?: duplicatedShift,
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

        val deletedShift = schedule.findShift(shiftId)
            ?: throw IllegalArgumentException("Shift not found: $shiftId")

        // Removing hours can pull this employee's later shifts back under their overtime
        // threshold, so re-derive the flag across their remaining shifts.
        val updatedShifts = recalculateOvertimeFor(
            businessId = businessId,
            shifts = schedule.shifts.filter { it.id != shiftId },
            employeeIds = setOf(deletedShift.employeeId)
        )

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

        transaction {
            undoService.recordSnapshot(scheduleId, schedule.shifts, modifiedBy)
            scheduleRepository.update(scheduleId, updatedSchedule)
        }
    }

    /**
     * Restore the shifts a draft schedule had before its most recent edit.
     *
     * The snapshot is consumed, so one edit can be undone once. This is what makes undo
     * work across a merge: [modifyShift] can only move rows that exist, and a merge
     * destroys the boundary it would need to move back, whereas the recorded rows state
     * the previous shape outright.
     *
     * The restored plan is re-validated but never refused. A state that was acceptable
     * when it was recorded can breach a rule that has been tightened since, and blocking
     * the undo would strand the manager in a state they explicitly asked to leave. The
     * resulting violations are recorded on the schedule instead — the same treatment
     * generation gives a schedule it could not make perfectly compliant.
     */
    fun undoLastChange(
        businessId: UUID,
        scheduleId: UUID,
        modifiedBy: String
    ): UndoResult {
        val schedule = scheduleRepository.findById(businessId, scheduleId)
            ?: throw IllegalArgumentException("Schedule not found: $scheduleId")

        if (!schedule.isDraft) {
            throw IllegalStateException("Cannot undo changes to a ${schedule.status} schedule. Only DRAFT schedules can be edited.")
        }

        val employees = schedule.employeeIds.mapNotNull { employeeRepository.findById(businessId, it) }

        // Consuming the snapshot and writing the restore share one transaction: a failure
        // partway through has to leave the undo still available, rather than spending it
        // on a restore that never landed.
        return transaction {
            val restoredShifts = undoService.consumeLatestSnapshot(scheduleId)
                ?: return@transaction UndoResult(
                    schedule = schedule,
                    restored = false,
                    violations = emptyList()
                )

            // Validated as a whole rather than as a diff: there is no "edit" being judged
            // here, just a plan being reinstated, so every violation it carries is reported.
            val violations = planValidator.validate(
                shifts = restoredShifts,
                employees = employees,
                rules = buildRules(schedule, employees.map { it.id }.toSet()),
                restrictTo = null
            )

            val restoredSchedule = schedule.copy(
                shifts = restoredShifts,
                metrics = calculateScheduleMetrics(restoredShifts, employees, businessId),
                staffingRequirements = recalculateStaffingRequirements(
                    shifts = restoredShifts,
                    originalRequirements = schedule.staffingRequirements,
                    employees = employees
                ),
                violations = violations,
                version = schedule.version + 1,
                lastModifiedAt = Instant.now(),
                lastModifiedBy = modifiedBy
            )

            scheduleRepository.update(scheduleId, restoredSchedule)

            UndoResult(schedule = restoredSchedule, restored = true, violations = violations)
        }
    }

    /** Whether [undoLastChange] currently has anything to restore. */
    fun canUndo(scheduleId: UUID): Boolean = transaction { undoService.hasSnapshot(scheduleId) }

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

        // A published schedule is immutable, so a retained undo could only ever be an
        // offer to reinstate draft shifts into a schedule that no longer accepts edits.
        return transaction {
            undoService.clear(scheduleId)
            scheduleRepository.update(scheduleId, publishedSchedule)
        }
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
     * Re-derive regular/overtime rows for every shift belonging to the given employees.
     *
     * Overtime is a property of the employee's accumulated hours, not of the shift, so
     * any change to one of their shifts can affect the others: adding an hour can push a
     * later block over the threshold, and removing one can pull it back under.
     *
     * Delegates to OvertimeSplitter so reassignment produces exactly the same shapes as
     * schedule generation, including splitting a block that straddles the threshold.
     *
     * Note that this can change the number of shifts and their ids: a block that crosses
     * the threshold becomes two rows, and one that no longer crosses collapses back into
     * one. Callers must not assume a shift id survives.
     *
     * Shifts belonging to other employees are returned untouched.
     */
    private fun recalculateOvertimeFor(
        businessId: UUID,
        shifts: List<Shift>,
        employeeIds: Set<UUID>
    ): List<Shift> {
        var result = shifts

        for (employeeId in employeeIds) {
            val employee = employeeRepository.findById(businessId, employeeId) ?: continue
            result = OvertimeSplitter.recalculateFor(employee, result)
        }

        return result
    }

    /**
     * Validate a shift modification (employee change, time change, etc.).
     *
     * Delegates to [ShiftPlanValidator], the same checker that validates a generated
     * schedule, so a dragged shift is judged against the rules the optimizer enforces
     * rather than a hand-maintained subset of them. Before this was shared, every rule the
     * solver knew about but this path didn't was a way to assemble by dragging a schedule
     * generation would have refused to produce.
     *
     * The plan handed to the validator is the schedule as it *would be* after the move: the
     * original row is dropped and the modified one added, so rules that depend on the whole
     * day or week (block length, daily totals, rest between shifts) see the real result
     * rather than the moved shift in isolation.
     *
     * Only violations the edit actually *introduces* are returned. A draft routinely
     * carries violations already — generation records them rather than refusing to produce
     * a schedule, and rules can be tightened after the fact, which retroactively makes
     * saved schedules non-compliant. Reporting the resulting state as-is would blame each
     * edit for every pre-existing problem on the employees it touches and leave the grid
     * uneditable, so the plan is validated before and after and the difference is what
     * gets reported.
     */
    private fun validateShiftModification(
        schedule: Schedule,
        originalShift: Shift,
        modifiedShift: Shift
    ): List<ConstraintViolation> {
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

        // Both sides of a reassignment are affected: the employee receiving the shift, and
        // the one giving it up, whose own totals and rest gaps change too.
        val affected = setOfNotNull(employee.id, originalShift.employeeId)
        val employees = affected.mapNotNull { employeeRepository.findById(schedule.businessId, it) }
        val rules = buildRules(schedule, affected)

        fun check(shifts: List<Shift>) = planValidator.validate(
            shifts = shifts,
            employees = employees,
            rules = rules,
            restrictTo = affected
        )

        val before = check(schedule.shifts).map { it.identity() }.toMutableList()
        val after = check(schedule.shifts.filter { it.id != originalShift.id } + modifiedShift)

        // Set subtraction would collapse duplicates: two identical violations before and
        // three after is one new problem, not none. Remove matches one at a time instead.
        return after.filterNot { violation -> before.remove(violation.identity()) }
    }

    /**
     * What makes two violations "the same problem" across a before/after comparison.
     *
     * Descriptions embed hours and times that shift as shifts move, so comparing them
     * directly would report a pre-existing violation as new whenever the edit nudged its
     * numbers. The type plus who and when it concerns is what identifies the problem.
     */
    private fun ConstraintViolation.identity(): Triple<ViolationType, UUID?, LocalDate?> =
        when (this) {
            is ConstraintViolation.Shift -> Triple(type, employeeId, date)
            is ConstraintViolation.EmployeeDay -> Triple(type, employeeId, date)
            is ConstraintViolation.Employee -> Triple(type, employeeId, null)
            is ConstraintViolation.TimeBlock -> Triple(type, null, date)
            is ConstraintViolation.ScheduleLevel -> Triple(type, null, null)
        }

    /**
     * Assembles the business's saved rules for a validation run.
     *
     * Time off is fetched per affected employee rather than for the whole business: a move
     * only concerns the people it touches, and the schedule period bounds the dates worth
     * asking about.
     */
    private fun buildRules(schedule: Schedule, affected: Set<UUID>): ShiftPlanValidator.Rules {
        val dates = schedule.schedulePeriod.getAllDates()
        val timeoffDates = if (dates.isEmpty()) {
            emptyMap()
        } else {
            affected.associateWith { employeeId ->
                timeoffRepository.findApprovedByEmployeeIdAndDateRange(
                    schedule.businessId,
                    employeeId,
                    dates.min(),
                    dates.max()
                ).flatMap { request ->
                    generateSequence(request.startDate) { day ->
                        day.plusDays(1).takeIf { !it.isAfter(request.endDate) }
                    }.toList()
                }.toSet()
            }.filterValues { it.isNotEmpty() }
        }

        return ShiftPlanValidator.Rules(
            workingHours = constraintsService.getWorkingHoursRules(schedule.businessId),
            compliance = constraintsService.getComplianceRules(schedule.businessId),
            timeoffDates = timeoffDates
        )
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

/**
 * Result of undoing the last change to a schedule.
 *
 * [restored] is false when there was nothing retained to go back to — a distinct case
 * from a failure, and the one the caller reports as "nothing to undo" rather than an
 * error. [violations] are those the restored plan carries; it is reinstated regardless.
 */
data class UndoResult(
    val schedule: Schedule,
    val restored: Boolean,
    val violations: List<ConstraintViolation>
)