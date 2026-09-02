package org.labormanagement.service

import org.labormanagement.model.Availability
import org.labormanagement.model.ComplianceRules
import org.labormanagement.model.ConstraintViolation
import org.labormanagement.model.Employee
import org.labormanagement.model.Shift
import org.labormanagement.model.ViolationType
import org.labormanagement.model.WorkingHoursRules
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The rules a set of shifts has to satisfy, checked against a concrete shift list.
 *
 * Scheduling rules were previously written twice: ScheduleOptimizer encodes them as CP-SAT
 * constraints over slot variables, and ShiftModificationService re-checked a subset of them
 * by hand when a manager dragged a shift. The two drifted, and every rule the solver knew
 * about but the move path didn't was a way to hand-assemble by dragging a schedule the
 * optimizer would have refused to generate.
 *
 * The solver genuinely cannot share this code — it builds constraints rather than inspecting
 * shifts, and it has to reason about placements that don't exist yet. So this is the other
 * half of the pair: one checker over a finished shift list, used both to validate a
 * generated schedule and to vet a proposed manual edit before it is saved. The solver's
 * constraints remain the generation-time encoding of the same rules; this is the arbiter of
 * whether a concrete plan actually satisfies them.
 *
 * Everything here is a pure function of its inputs. Callers fetch employees, rules and
 * time off, and decide what to do with the violations that come back — generation records
 * them on the schedule, the move path rejects the edit.
 */
class ShiftPlanValidator {

    /**
     * Rules that apply to a whole plan. All optional: a business that hasn't configured
     * working-hours or compliance rules simply isn't checked against them, matching the
     * optimizer, which skips those constraint groups when the rules are absent.
     */
    data class Rules(
        val workingHours: WorkingHoursRules? = null,
        val compliance: ComplianceRules? = null,
        /** Dates each employee has approved time off for. */
        val timeoffDates: Map<UUID, Set<LocalDate>> = emptyMap(),
        /**
         * Shifts these people already work at other locations in the same window. One
         * person can only be in one place at once, and their weekly cap is a single budget
         * across every location.
         */
        val shiftsElsewhere: List<Shift> = emptyList(),
        /** Total labor cost cap for the plan; null means uncapped. */
        val laborCostBudget: Double? = null
    )

    /**
     * Validates [shifts] for the given [employees].
     *
     * When [restrictTo] is non-null only violations concerning those employees are
     * returned. A manual edit is judged on the people it actually touches: the rest of the
     * schedule may well have pre-existing violations, and blocking an unrelated move
     * because someone else's day is already non-compliant would make the grid uneditable.
     */
    fun validate(
        shifts: List<Shift>,
        employees: List<Employee>,
        rules: Rules = Rules(),
        restrictTo: Set<UUID>? = null
    ): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()

        rules.laborCostBudget?.let { budget ->
            val totalCost = shifts.sumOf { it.laborCost }
            if (totalCost > budget) {
                violations.add(
                    ConstraintViolation.ScheduleLevel(
                        type = ViolationType.BUDGET_EXCEEDED,
                        description = "Total labor cost $${"%.2f".format(totalCost)} exceeds budget $${"%.2f".format(budget)}"
                    )
                )
            }
        }

        val shiftsByEmployee = shifts.groupBy { it.employeeId }

        employees
            .filter { restrictTo == null || it.id in restrictTo }
            .forEach { employee ->
                val theirs = shiftsByEmployee[employee.id].orEmpty()
                if (theirs.isEmpty()) return@forEach
                violations += validateEmployee(employee, theirs, rules)
            }

        return violations
    }

    private fun validateEmployee(
        employee: Employee,
        shifts: List<Shift>,
        rules: Rules
    ): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()
        val elsewhere = rules.shiftsElsewhere.filter { it.employeeId == employee.id }

        violations += checkAvailability(employee, shifts)
        violations += checkTimeoff(employee, shifts, rules.timeoffDates[employee.id].orEmpty())
        violations += checkOverlaps(employee, shifts, elsewhere)
        violations += checkContractHours(employee, shifts, elsewhere)
        violations += checkWorkingHoursRules(employee, shifts, elsewhere, rules.workingHours)
        violations += checkRestBreaks(employee, shifts, rules.compliance)

        return violations
    }

    /**
     * Availability is resolved through [Availability.isAvailableOn], which honours
     * availabilityType — a SPECIFIC_DATE or DATE_RANGE row grants availability only on the
     * dates it actually covers. Comparing dayOfWeek and times directly, as the move path
     * used to, silently promotes those rows to blanket weekly availability.
     */
    private fun checkAvailability(employee: Employee, shifts: List<Shift>): List<ConstraintViolation> =
        shifts.filterNot { shift ->
            employee.availability.any { it.isAvailableOn(shift.date, shift.startTime, shift.endTime) }
        }.map { shift ->
            ConstraintViolation.Shift(
                type = ViolationType.AVAILABILITY_CONFLICT,
                description = "${employee.fullName} is not available on ${shift.dayOfWeek} " +
                    "from ${shift.startTime} to ${shift.endTime}",
                employeeId = employee.id,
                date = shift.date,
                startTime = shift.startTime,
                endTime = shift.endTime
            )
        }

    /** Approved time off makes a date unavailable outright, as it does for the solver. */
    private fun checkTimeoff(
        employee: Employee,
        shifts: List<Shift>,
        timeoffDates: Set<LocalDate>
    ): List<ConstraintViolation> =
        shifts.filter { it.date in timeoffDates }.map { shift ->
            ConstraintViolation.Shift(
                type = ViolationType.AVAILABILITY_CONFLICT,
                description = "${employee.fullName} has approved time off on ${shift.date}",
                employeeId = employee.id,
                date = shift.date,
                startTime = shift.startTime,
                endTime = shift.endTime
            )
        }

    /**
     * Overlaps within this plan, and against shifts the employee already works elsewhere.
     */
    private fun checkOverlaps(
        employee: Employee,
        shifts: List<Shift>,
        elsewhere: List<Shift>
    ): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()

        for (i in shifts.indices) {
            for (j in i + 1 until shifts.size) {
                if (shifts[i].overlaps(shifts[j])) {
                    violations.add(
                        ConstraintViolation.Shift(
                            type = ViolationType.SHIFT_OVERLAP,
                            description = "${employee.fullName} has overlapping shifts on ${shifts[i].dayOfWeek}",
                            employeeId = employee.id,
                            date = shifts[i].date,
                            startTime = shifts[i].startTime,
                            endTime = shifts[i].endTime
                        )
                    )
                }
            }
        }

        shifts.forEach { shift ->
            if (elsewhere.any { it.overlaps(shift) }) {
                violations.add(
                    ConstraintViolation.Shift(
                        type = ViolationType.SHIFT_OVERLAP,
                        description = "${employee.fullName} is already working at another location on " +
                            "${shift.dayOfWeek} from ${shift.startTime} to ${shift.endTime}",
                        employeeId = employee.id,
                        date = shift.date,
                        startTime = shift.startTime,
                        endTime = shift.endTime
                    )
                )
            }
        }

        return violations
    }

    /**
     * Contract caps. Both are summed over the relevant window rather than read off a single
     * shift: the daily cap in particular is a property of the day, so two short shifts that
     * are individually fine can still break it together.
     *
     * Hours already worked elsewhere count towards the weekly cap, which is one budget for
     * the person rather than one per location.
     */
    private fun checkContractHours(
        employee: Employee,
        shifts: List<Shift>,
        elsewhere: List<Shift>
    ): List<ConstraintViolation> {
        val violations = mutableListOf<ConstraintViolation>()
        val contract = employee.contract

        val weeklyHours = shifts.sumOf { it.durationHours } + elsewhere.sumOf { it.durationHours }
        if (weeklyHours > contract.maxHoursPerWeek) {
            violations.add(
                ConstraintViolation.Employee(
                    type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                    description = "Total weekly hours (${"%.1f".format(weeklyHours)}) exceeds maximum " +
                        "(${contract.maxHoursPerWeek}) for ${employee.fullName}",
                    employeeId = employee.id
                )
            )
        }

        shifts.groupBy { it.date }.forEach { (date, dayShifts) ->
            val dailyHours = dayShifts.sumOf { it.durationHours } +
                elsewhere.filter { it.date == date }.sumOf { it.durationHours }
            if (dailyHours > contract.maxHoursPerDay) {
                violations.add(
                    ConstraintViolation.EmployeeDay(
                        type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                        description = "${employee.fullName} is scheduled ${"%.1f".format(dailyHours)}h on " +
                            "${date.dayOfWeek}, exceeding the daily maximum of ${contract.maxHoursPerDay}h",
                        employeeId = employee.id,
                        date = date
                    )
                )
            }
        }

        return violations
    }

    /**
     * The business-level working-hours rules the solver enforces in
     * addWorkingHoursRulesConstraints: shift length bounds, the weekly cap and overtime
     * ceiling, daily rest between shifts, and the consecutive-days limit.
     *
     * Weekly rest (one full day off per rolling seven) is deliberately not checked here.
     * The solver models it over the whole schedule period, and a single edit can't be
     * judged against it without the surrounding weeks; flagging it on a shift move would
     * report a violation the manager has no way to act on from the grid.
     */
    private fun checkWorkingHoursRules(
        employee: Employee,
        shifts: List<Shift>,
        elsewhere: List<Shift>,
        rules: WorkingHoursRules?
    ): List<ConstraintViolation> {
        if (rules == null) return emptyList()

        val violations = mutableListOf<ConstraintViolation>()
        val blocks = mergeIntoBlocks(shifts)

        blocks.forEach { block ->
            if (rules.maxShiftLength > 0 && block.durationHours > rules.maxShiftLength) {
                violations.add(
                    ConstraintViolation.Shift(
                        type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                        description = "${employee.fullName} would work ${"%.1f".format(block.durationHours)}h " +
                            "straight (${block.startTime} to ${block.endTime}) on ${block.date.dayOfWeek}, " +
                            "exceeding the maximum shift length of ${rules.maxShiftLength}h",
                        employeeId = employee.id,
                        date = block.date,
                        startTime = block.startTime,
                        endTime = block.endTime
                    )
                )
            }

            if (rules.minShiftLength > 0 && block.durationHours < rules.minShiftLength) {
                violations.add(
                    ConstraintViolation.Shift(
                        type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                        description = "${employee.fullName}'s shift on ${block.date.dayOfWeek} " +
                            "(${block.startTime} to ${block.endTime}) is ${"%.1f".format(block.durationHours)}h, " +
                            "below the minimum shift length of ${rules.minShiftLength}h",
                        employeeId = employee.id,
                        date = block.date,
                        startTime = block.startTime,
                        endTime = block.endTime
                    )
                )
            }
        }

        val weeklyHours = shifts.sumOf { it.durationHours } + elsewhere.sumOf { it.durationHours }
        if (rules.maxHoursPerWeek > 0 && weeklyHours > rules.maxHoursPerWeek) {
            violations.add(
                ConstraintViolation.Employee(
                    type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                    description = "${employee.fullName} is scheduled ${"%.1f".format(weeklyHours)}h this week, " +
                        "exceeding the maximum of ${rules.maxHoursPerWeek}h",
                    employeeId = employee.id
                )
            )
        }

        val overtime = weeklyHours - employee.contract.overtimeThreshold
        if (rules.maxOvertimeHours > 0 && overtime > rules.maxOvertimeHours) {
            violations.add(
                ConstraintViolation.Employee(
                    type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                    description = "${employee.fullName} would work ${"%.1f".format(overtime)}h of overtime, " +
                        "exceeding the maximum of ${rules.maxOvertimeHours}h",
                    employeeId = employee.id
                )
            )
        }

        violations += checkDailyRest(employee, blocks, rules.minRestBetweenShifts)
        violations += checkConsecutiveDays(employee, shifts, rules.maxConsecutiveDays)

        return violations
    }

    /**
     * Daily rest between shifts. Same-day gaps are excluded, matching the solver: those are
     * the rest-break rule's concern, and treating a lunch break as insufficient daily rest
     * would flag every normal split shift.
     */
    private fun checkDailyRest(
        employee: Employee,
        blocks: List<WorkBlock>,
        minRestHours: Double
    ): List<ConstraintViolation> {
        if (minRestHours <= 0.0) return emptyList()

        return blocks.sortedWith(compareBy({ it.date }, { it.startTime }))
            .zipWithNext()
            .filter { (earlier, later) -> earlier.date != later.date }
            .mapNotNull { (earlier, later) ->
                val gapHours = Duration.between(earlier.endsAt, later.startsAt).toMinutes() / 60.0
                if (gapHours > 0.0 && gapHours < minRestHours) {
                    ConstraintViolation.Shift(
                        type = ViolationType.MISSING_BREAK,
                        description = "${employee.fullName} gets only ${"%.1f".format(gapHours)}h rest between " +
                            "${earlier.date.dayOfWeek} and ${later.date.dayOfWeek}; " +
                            "at least ${minRestHours}h is required",
                        employeeId = employee.id,
                        date = later.date,
                        startTime = later.startTime,
                        endTime = later.endTime
                    )
                } else {
                    null
                }
            }
    }

    /**
     * The consecutive-days cap, checked over the days this plan actually contains. A run
     * cut short only by the plan's edges isn't flagged, mirroring the solver, which doesn't
     * model days outside the schedule period.
     */
    private fun checkConsecutiveDays(
        employee: Employee,
        shifts: List<Shift>,
        maxConsecutiveDays: Int
    ): List<ConstraintViolation> {
        if (maxConsecutiveDays <= 0) return emptyList()

        val workedDays = shifts.map { it.date }.distinct().sorted()
        if (workedDays.size <= maxConsecutiveDays) return emptyList()

        val violations = mutableListOf<ConstraintViolation>()
        var runStart = 0

        for (i in workedDays.indices) {
            val startsNewRun = i > 0 && workedDays[i - 1].plusDays(1) != workedDays[i]
            if (startsNewRun) runStart = i

            val runLength = i - runStart + 1
            if (runLength == maxConsecutiveDays + 1) {
                violations.add(
                    ConstraintViolation.EmployeeDay(
                        type = ViolationType.CONTRACT_HOURS_EXCEEDED,
                        description = "${employee.fullName} would work $runLength days in a row " +
                            "(${workedDays[runStart]} to ${workedDays[i]}), exceeding the maximum of " +
                            "$maxConsecutiveDays consecutive days",
                        employeeId = employee.id,
                        date = workedDays[i]
                    )
                )
            }
        }

        return violations
    }

    /**
     * Rest breaks, mirroring ScheduleOptimizer.addRestBreakConstraints as two paired rules:
     * no contiguous block may exceed mealBreakMinShiftHours, and any same-day gap between
     * blocks must be at least mealBreakDuration — otherwise the first rule could be
     * satisfied by a token gap shorter than the break the manager configured.
     */
    private fun checkRestBreaks(
        employee: Employee,
        shifts: List<Shift>,
        compliance: ComplianceRules?
    ): List<ConstraintViolation> {
        if (compliance == null || !compliance.mealBreakRequired) return emptyList()
        if (compliance.mealBreakMinShiftHours <= 0.0) return emptyList()

        val violations = mutableListOf<ConstraintViolation>()
        val blocksByDate = mergeIntoBlocks(shifts).groupBy { it.date }

        blocksByDate.forEach { (date, blocks) ->
            blocks.filter { it.durationHours > compliance.mealBreakMinShiftHours }.forEach { block ->
                violations.add(
                    ConstraintViolation.Shift(
                        type = ViolationType.MISSING_BREAK,
                        description = "${employee.fullName} would work ${"%.1f".format(block.durationHours)}h " +
                            "straight (${block.startTime} to ${block.endTime}) on ${date.dayOfWeek} without a " +
                            "break; a break is required after " +
                            "${"%.1f".format(compliance.mealBreakMinShiftHours)}h",
                        employeeId = employee.id,
                        date = date,
                        startTime = block.startTime,
                        endTime = block.endTime
                    )
                )
            }

            if (compliance.mealBreakDuration > 0) {
                blocks.sortedBy { it.startTime }.zipWithNext().forEach { (earlier, later) ->
                    val gapMinutes = Duration.between(earlier.endTime, later.startTime).toMinutes()
                    if (gapMinutes > 0 && gapMinutes < compliance.mealBreakDuration) {
                        violations.add(
                            ConstraintViolation.Shift(
                                type = ViolationType.MISSING_BREAK,
                                description = "${employee.fullName}'s break on ${date.dayOfWeek} " +
                                    "(${earlier.endTime} to ${later.startTime}) is only $gapMinutes minutes; " +
                                    "at least ${compliance.mealBreakDuration} minutes are required",
                                employeeId = employee.id,
                                date = date,
                                startTime = earlier.endTime,
                                endTime = later.startTime
                            )
                        )
                    }
                }
            }
        }

        return violations
    }

    /** A contiguous span of work on one day, merged from one or more stored shift rows. */
    private data class WorkBlock(
        val date: LocalDate,
        val startTime: LocalTime,
        val endTime: LocalTime
    ) {
        val durationHours: Double
            get() = Duration.between(startTime, endTime).toMinutes() / 60.0

        val startsAt: java.time.LocalDateTime get() = date.atTime(startTime)
        val endsAt: java.time.LocalDateTime get() = date.atTime(endTime)
    }

    /**
     * Merges each day's shifts into contiguous blocks.
     *
     * Shifts are stored split at the overtime boundary, so two rows that look like separate
     * shifts are often one continuous block. Checking raw rows would miss the block they
     * form — the very thing the length and break rules are about — and would also report
     * spurious zero-length gaps between the halves of a single shift.
     *
     * Overlapping rows are merged as well as abutting ones: an overlap is reported
     * separately as SHIFT_OVERLAP, and folding it into one block here keeps the length
     * rules from also failing on it with a confusingly different message.
     */
    private fun mergeIntoBlocks(shifts: List<Shift>): List<WorkBlock> {
        val blocks = mutableListOf<WorkBlock>()

        shifts.groupBy { it.date }.forEach { (date, sameDay) ->
            var current: WorkBlock? = null

            for (shift in sameDay.sortedBy { it.startTime }) {
                val block = current
                current = when {
                    block == null -> WorkBlock(date, shift.startTime, shift.endTime)
                    shift.startTime <= block.endTime ->
                        block.copy(endTime = maxOf(block.endTime, shift.endTime))
                    else -> {
                        blocks.add(block)
                        WorkBlock(date, shift.startTime, shift.endTime)
                    }
                }
            }

            current?.let { blocks.add(it) }
        }

        return blocks.sortedWith(compareBy({ it.date }, { it.startTime }))
    }
}
