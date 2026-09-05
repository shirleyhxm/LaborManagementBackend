package org.labormanagement.optimization

import com.google.ortools.Loader
import com.google.ortools.sat.*
import org.labormanagement.model.Employee
import org.labormanagement.model.OptimizationObjective
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Mathematical optimization engine for employee scheduling using Google OR-Tools CP-SAT solver.
 *
 * This class encapsulates the constraint programming model that optimizes employee schedules
 * based on sales forecasts, employee availability, labor costs, and business constraints.
 */
class ScheduleOptimizer {
    private val log = LoggerFactory.getLogger(ScheduleOptimizer::class.java)

    init {
        Loader.loadNativeLibraries()
    }

    companion object {
        /** Age below which minor labor law restrictions apply. */
        private const val MINOR_AGE_YEARS = 18L

        /** Daily hours cap applied to minors when minor labor laws are enabled. */
        private const val MINOR_MAX_HOURS_PER_DAY = 8L

        /**
         * Minors may not work between these times. A slot is forbidden if it
         * overlaps [MINOR_NIGHT_START, midnight) or [midnight, MINOR_NIGHT_END).
         */
        private val MINOR_NIGHT_START: LocalTime = LocalTime.of(22, 0)
        private val MINOR_NIGHT_END: LocalTime = LocalTime.of(6, 0)
    }

    /**
     * Optimizes employee schedules to minimize labor costs while meeting sales coverage requirements.
     *
     * @param input The optimization input containing employees, sales forecasts, and constraints
     * @return OptimizationResult containing the optimal schedule or null if no feasible solution exists
     */
    fun optimize(input: OptimizationInput): OptimizationResult? {
        val model = CpModel()

        val numEmployees = input.employees.size
        val numSlots = input.timeSlots.size

        // Decision variables: x[e][t] = 1 if employee e works time slot t
        val x = Array(numEmployees) { e ->
            Array(numSlots) { t ->
                model.newBoolVar("x_${e}_${t}")
            }
        }

        /* Objective 1: Minimize labor cost */
        // Hours variables for each employee
        val totalHours = Array(numEmployees) { e ->
            model.newIntVar(0, 200, "hours_$e")
        }
        // Overtime hours
        val overtime = Array(numEmployees) { e ->
            model.newIntVar(0, 200, "ot_$e")
        }
        // Regular hours
        val regular = Array(numEmployees) { e ->
            val threshold = input.employees[e].contract.overtimeThreshold.toLong()
            model.newIntVar(0, threshold, "reg_$e")
        }
        val laborCost = model.newIntVar(0, 1_000_000, "labor_cost")

        /* Objective 2: Maximize sales coverage */
        // Coverage output per slot (integer productivity sum)
        val coverage = Array(numSlots) { t ->
            model.newIntVar(0, 1_000_000, "coverage_$t")
        }

        /* Objective 3: Minimize variance in assigned / workable hours (for fairness) */
        val hoursDeviation = Array(numEmployees) { e ->
            model.newIntVar(0, 1_000_000, "deviation_$e")
        }

        // Slack variable for unmet coverage in slot t
        val slack = Array(numSlots) { t ->
            model.newIntVar(0, 1_000_000, "slack_$t")
        }

        // Add constraints
        addAvailabilityConstraints(model, x, input)
        addMinimumShiftLengthConstraints(model, x, input)
        addMaxHoursPerDayConstraints(model, x, input)
        addHoursConstraints(model, x, totalHours, regular, overtime, input)
        addSalesCoverageConstraints(model, x, coverage, input, slack)
        addLaborCostConstraints(model, regular, overtime, laborCost, input)
        addLaborHoursVariable(model, x, hoursDeviation, input)

        // Add constraints from ConstraintsService
        addWorkingHoursRulesConstraints(model, x, input)
        addContractWeeklyHoursConstraints(model, x, input)
        addContractedHoursConstraints(model, x, totalHours, input)
        addComplianceRulesConstraints(model, x, input)

        // Set objective based on optimization objective
        setObjective(model, regular, overtime, coverage, hoursDeviation, input, slack)

        // Solve the model
        val solver = CpSolver()
        solver.parameters.maxTimeInSeconds = input.maxSolveTimeSeconds

        val status = solver.solve(model)

        log.info("[ScheduleOptimizer] Solver status: $status")
        log.info("[ScheduleOptimizer] Number of employees: ${input.employees.size}")
        log.info("[ScheduleOptimizer] Number of time slots: ${input.timeSlots.size}")

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            log.info("[ScheduleOptimizer] No feasible solution found")
            return null // No feasible solution found
        }

        log.info("[ScheduleOptimizer] Solution found! Objective value: ${solver.objectiveValue()}")
        log.info("[ScheduleOptimizer] Is optimal: ${status == CpSolverStatus.OPTIMAL}")

        log.debug("Slot shortfalls:")
        for (t in 0 until numSlots) {
            val s = solver.value(slack[t])
            if (s > 0) log.debug("  Slot $t: Shortfall = $s")
        }

        // Extract solution
        return extractSolution(solver, x, totalHours, input, status == CpSolverStatus.OPTIMAL)
    }

    private fun addAvailabilityConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput
    ) {
        for (e in input.employees.indices) {
            for (t in input.timeSlots.indices) {
                if (!input.isAvailable(e, t)) {
                    model.addEquality(x[e][t], 0)
                }
            }
        }
    }

    /**
     * Enforces minimum shift length constraint.
     *
     * Approach:
     * 1. Define shiftStart[e][t] = 1 iff a shift starts at slot t for employee e
     * 2. Link shiftStart to x variables: shiftStart[e][t] = 1 iff x[e][t]=1 AND x[e][t-1]=0
     * 3. For every shift start, require minShiftLength consecutive slots to be worked
     *
     * This ensures no shift can be shorter than minShiftLength.
     */
    private fun addMinimumShiftLengthConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput
    ) {
        val minShiftLength = input.workingHoursRules?.minShiftLength ?: return
        if (minShiftLength <= 0) return

        val numEmployees = input.employees.size
        val numSlots = input.timeSlots.size

        // Calculate minimum number of consecutive slots needed
        val slotDuration = input.timeSlots.firstOrNull()?.durationHours ?: 1.0
        val minSlots = kotlin.math.ceil(minShiftLength / slotDuration).toInt()

        if (minSlots <= 1) return // No constraint needed

        // Step 1: Define shiftStart variables
        val shiftStart = Array(numEmployees) { e ->
            Array(numSlots) { t ->
                model.newBoolVar("shiftStart_${e}_${t}")
            }
        }

        // Step 2: Link shiftStart to x variables
        for (e in 0 until numEmployees) {
            for (t in 0 until numSlots) {
                if (t == 0) {
                    // First slot: shiftStart[e][0] == x[e][0]
                    model.addEquality(shiftStart[e][0], x[e][0])
                } else {
                    // Check if previous slot is on same day and consecutive
                    val currSlot = input.timeSlots[t]
                    val prevSlot = input.timeSlots[t - 1]
                    val isConsecutive = prevSlot.date == currSlot.date && prevSlot.endTime == currSlot.startTime

                    if (isConsecutive) {
                        // shiftStart[e][t] >= x[e][t] - x[e][t-1]
                        // This means: if we work slot t but not t-1, shiftStart must be 1
                        model.addGreaterOrEqual(
                            shiftStart[e][t],
                            LinearExpr.sum(arrayOf(x[e][t], LinearExpr.term(x[e][t - 1], -1)))
                        )
                    } else {
                        // Not consecutive (different day or gap) - treat as potential shift start
                        model.addEquality(shiftStart[e][t], x[e][t])
                    }
                }

                // shiftStart[e][t] <= x[e][t]
                // This means: can only start a shift if we're working
                model.addLessOrEqual(shiftStart[e][t], x[e][t])
            }
        }

        // Step 3: Enforce minimum shift length
        for (e in 0 until numEmployees) {
            for (t in 0 until numSlots) {
                // Collect consecutive slots starting from t
                val window = mutableListOf<IntVar>()
                var currentIdx = t

                while (window.size < minSlots && currentIdx < numSlots) {
                    // Check if this slot is consecutive with the window
                    if (window.isEmpty()) {
                        window.add(x[e][currentIdx])
                        currentIdx++
                    } else {
                        val prevSlot = input.timeSlots[currentIdx - 1]
                        val currSlot = input.timeSlots[currentIdx]
                        val isConsecutive = prevSlot.date == currSlot.date && prevSlot.endTime == currSlot.startTime

                        if (isConsecutive) {
                            window.add(x[e][currentIdx])
                            currentIdx++
                        } else {
                            break // Hit a gap or day boundary
                        }
                    }
                }

                // If there aren't enough consecutive slots available, prevent starting a shift at this slot
                if (window.size < minSlots) {
                    // Not enough consecutive slots available - cannot start a shift here
                    model.addEquality(shiftStart[e][t], 0)
                } else {
                    // Enough slots available - if shift starts here, all minSlots must be worked
                    model.addGreaterOrEqual(
                        LinearExpr.sum(window.toTypedArray()),
                        LinearExpr.term(shiftStart[e][t], minSlots.toLong())
                    )
                }
            }
        }
    }

    private fun addHoursConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        totalHours: Array<IntVar>,
        regular: Array<IntVar>,
        overtime: Array<IntVar>,
        input: OptimizationInput
    ) {
        // Precompute slot durations (convert to long for OR-Tools)
        val slotDurations = input.timeSlots.map { it.durationHours.toLong() }.toLongArray()

        for (e in input.employees.indices) {
            // Total hours = sum of (slot worked * slot duration)
            val weightedSum = LinearExpr.weightedSum(x[e], slotDurations)
            model.addEquality(totalHours[e], weightedSum)

            // Total hours = regular + overtime
            model.addEquality(
                totalHours[e],
                LinearExpr.sum(arrayOf(regular[e], overtime[e]))
            )

            // Regular hours <= overtime threshold
            val threshold = input.employees[e].contract.overtimeThreshold.toLong()
            model.addLessOrEqual(regular[e], threshold)

            // Overtime >= total hours - threshold
            model.addGreaterOrEqual(
                overtime[e],
                LinearExpr.affine(totalHours[e], 1, -threshold)
            )
        }
    }

    private fun addSalesCoverageConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        coverage: Array<IntVar>,
        input: OptimizationInput,
        slack: Array<IntVar>
    ) {
        for (t in input.timeSlots.indices) {
            val vars = mutableListOf<IntVar>()
            val coeffs = mutableListOf<Long>()

            for (e in input.employees.indices) {
                vars.add(x[e][t])
                coeffs.add(input.getProductivity(e, t))
            }

            // coverage[t] = Σ_e productivity[e][t] * x[e][t] - This is NOT a constraint, just definition
            model.addEquality(
                coverage[t],
                LinearExpr.weightedSum(vars.toTypedArray(), coeffs.toLongArray())
            )

            vars.add(slack[t]) // Add slack term
            coeffs.add(1) // slack contributes directly to coverage

            // Sum of (productivity * hours * assigned) >= coverage fraction * projected sales
            model.addGreaterOrEqual(
                LinearExpr.weightedSum(vars.toTypedArray(), coeffs.toLongArray()),
                (input.coverageFraction * input.projectedSales[t]).toLong()
            )
        }
    }

    private fun addLaborCostConstraints(
        model: CpModel,
        regular: Array<IntVar>,
        overtime: Array<IntVar>,
        laborCost: IntVar,
        input: OptimizationInput
    ) {
        val costTerms = mutableListOf<LinearExpr>()

        for (e in input.employees.indices) {
            // regular wages
            costTerms += LinearExpr.term(regular[e], input.employees[e].normalPayRate.toLong())
            // overtime wages
            costTerms += LinearExpr.term(overtime[e], input.employees[e].overtimePayRate.toLong())
        }

        model.addEquality(laborCost, LinearExpr.sum(costTerms.toTypedArray()))
        model.addLessOrEqual(laborCost, input.laborBudget)
    }

    private fun addLaborHoursVariable(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        deviation: Array<IntVar>,
        input: OptimizationInput,
    ) {
        // Precompute slot durations
        val slotDurations = input.timeSlots.map { it.durationHours.toLong() }.toLongArray()

        // Calculate workable hours (sum of durations for available slots)
        val workableHours = LongArray(input.employees.size) { e ->
            (0 until input.timeSlots.size).sumOf { t ->
                if (input.isAvailable(e, t)) input.timeSlots[t].durationHours.toLong() else 0L
            }
        }

        val assignedHours = Array(input.employees.size) { e ->
            model.newIntVar(0, workableHours[e], "assigned_hours_$e")
        }
        val targetHours = model.newIntVar(0, 1_000_000, "target_hours")
        val totalAssignedHours = model.newIntVar(0, 1_000_000, "total_assigned_hours")

        val totalHourTerms = mutableListOf<LinearExpr>()

        for (e in input.employees.indices) {
            // Assigned hours = sum of (slot worked * slot duration)
            val assignedHoursExpr = LinearExpr.weightedSum(x[e], slotDurations)
            model.addEquality(assignedHours[e], assignedHoursExpr)

            totalHourTerms.add(LinearExpr.term(assignedHours[e], 1))
        }
        model.addEquality(totalAssignedHours, LinearExpr.sum(totalHourTerms.toTypedArray()))

        // targetHours = totalAssignedHours / numEmployees
        model.addEquality(LinearExpr.term(targetHours, input.employees.size.toLong()), totalAssignedHours)

        for (e in input.employees.indices) {
            // deviation[e] ≥ assigned_hours - targetHours
            model.addGreaterOrEqual(
                deviation[e],
                LinearExpr.sum(arrayOf(assignedHours[e], LinearExpr.term(targetHours, -1)))
            )

            // deviation[e] ≥ targetHours - assigned_hours
            model.addGreaterOrEqual(
                deviation[e],
                LinearExpr.sum(arrayOf(targetHours, LinearExpr.term(assignedHours[e], -1)))
            )
        }
    }

    private fun setObjective(
        model: CpModel,
        regular: Array<IntVar>,
        overtime: Array<IntVar>,
        coverage: Array<IntVar>,
        hoursDeviation: Array<IntVar>,
        input: OptimizationInput,
        slack: Array<IntVar>
    ) {
        val M_SLACK = 1_000_000L   // Primary → minimize coverage shortfall
        val M_OT    = 1_000L       // Optional: discourage overtime slightly

        when (input.objective) {
            OptimizationObjective.MINIMIZE_LABOR_COST, OptimizationObjective.BALANCED -> {
                // Minimize total labor cost = sum of (regular hours * normal rate + overtime hours * overtime rate)
                val costTerms = mutableListOf<LinearExpr>()

                for (t in input.timeSlots.indices) {
                    // Penalize slack heavily to ensure coverage
                    costTerms += LinearExpr.term(slack[t], M_SLACK)
                }

                for (e in input.employees.indices) {
                    val employee = input.employees[e]
                    costTerms += LinearExpr.term(regular[e], employee.normalPayRate.toLong())
                    costTerms += LinearExpr.term(overtime[e], employee.overtimePayRate.toLong() + M_OT)
                }

                model.minimize(LinearExpr.sum(costTerms.toTypedArray()))
            }
            OptimizationObjective.MAXIMIZE_SALES -> {
                // Maximize Σ coverage[t]
                val totalCoverage = LinearExpr.sum(coverage.asList().toTypedArray())

                model.minimize(LinearExpr.term(totalCoverage, -1))
            }
            OptimizationObjective.MAXIMIZE_FAIRNESS -> {
                // Minimize hour deviations for fairness, but still penalize coverage shortfalls
                val fairnessTerms = mutableListOf<LinearExpr>()

                // Primary: minimize coverage shortfall (slack)
                for (t in input.timeSlots.indices) {
                    fairnessTerms += LinearExpr.term(slack[t], M_SLACK)
                }

                // Secondary: minimize hour deviations for fairness
                for (e in input.employees.indices) {
                    fairnessTerms += LinearExpr.term(hoursDeviation[e], 1)
                }

                model.minimize(LinearExpr.sum(fairnessTerms.toTypedArray()))
            }
        }
    }

    /**
     * Enforces working hours rules from ConstraintsService.
     * Includes max shift length, min shift length, max consecutive days, and rest between shifts.
     */
    /**
     * Caps each employee at their own contract's weekly hours, less anything
     * already worked at another location that week.
     *
     * Separate from [addWorkingHoursRulesConstraints] because that block bails
     * out entirely when a business has saved no working-hours rules - which a
     * newly created location has not. The contract belongs to the employee
     * rather than the location, so it has to bind either way; otherwise a
     * second location could hand someone a full week on top of a full week
     * they are already working.
     */
    private fun addContractWeeklyHoursConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput
    ) {
        val slotDurations = input.timeSlots.map { it.durationHours.toLong() }.toLongArray()

        for (e in input.employees.indices) {
            val contractCap = input.employees[e].contract.maxHoursPerWeek
            if (contractCap <= 0.0) continue

            val allSlots = input.timeSlots.indices.map { x[e][it] }.toTypedArray()
            model.addLessOrEqual(
                LinearExpr.weightedSum(allSlots, slotDurations),
                input.remainingOf(contractCap, e)
            )
        }
    }

    private fun addWorkingHoursRulesConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput
    ) {
        val rules = input.workingHoursRules ?: return

        // Precompute slot durations
        val slotDurations = input.timeSlots.map { it.durationHours.toLong() }.toLongArray()

        // Max shift length constraint (applies to each consecutive shift)
        // A consecutive shift is a sequence of slots where each slot's end time equals the next slot's start time
        // This can span across days (e.g., overnight shifts like 10pm-6am)
        for (e in input.employees.indices) {
            // For each slot, check all possible consecutive windows starting from that slot
            for (startIdx in input.timeSlots.indices) {
                var totalDuration = 0.0
                var currentIdx = startIdx

                // Build consecutive windows by following slots that connect end-to-start
                while (currentIdx < input.timeSlots.size) {
                    val currentSlot = input.timeSlots[currentIdx]
                    totalDuration += currentSlot.durationHours

                    // If this window exceeds max shift length, ensure at least one slot is not worked
                    if (totalDuration > rules.maxShiftLength) {
                        val windowIndices = (startIdx..currentIdx).toList()
                        val windowVars = windowIndices.map { x[e][it] }.toTypedArray()

                        // At least one slot in this window must not be worked (creates a break)
                        model.addLessOrEqual(
                            LinearExpr.sum(windowVars),
                            windowIndices.size.toLong() - 1
                        )
                    }

                    // Check if there's a next slot that's consecutive (end time matches next start time)
                    val nextIdx = currentIdx + 1
                    if (nextIdx < input.timeSlots.size) {
                        val nextSlot = input.timeSlots[nextIdx]
                        // Check if slots are consecutive (current end == next start)
                        // Allow consecutive slots across midnight (current date + 1 == next date)
                        if (currentSlot.endTime == nextSlot.startTime &&
                            (currentSlot.date == nextSlot.date ||
                             currentSlot.date.plusDays(1) == nextSlot.date)) {
                            currentIdx = nextIdx
                        } else {
                            break  // Not consecutive, stop extending this window
                        }
                    } else {
                        break  // No more slots
                    }
                }
            }
        }

        // Max hours per week (weighted by slot duration). The cap is shared
        // across every location the employee works at, so hours already
        // committed elsewhere come off it before the solver allocates any.
        for (e in input.employees.indices) {
            val allSlots = input.timeSlots.indices.map { x[e][it] }.toTypedArray()
            model.addLessOrEqual(
                LinearExpr.weightedSum(allSlots, slotDurations),
                input.remainingOf(rules.maxHoursPerWeek, e)
            )
        }

        // Max overtime hours per week (weighted by slot duration)
        for (e in input.employees.indices) {
            // The overtime threshold is likewise reached across all locations:
            // hours worked elsewhere count towards it, so someone already past
            // it starts this schedule in overtime rather than at zero.
            val overtimeThreshold = input.remainingOf(
                input.employees[e].contract.overtimeThreshold,
                e
            )
            val allSlots = input.timeSlots.indices.map { x[e][it] }.toTypedArray()

            // Total weekly hours worked
            val totalWeeklyHours = LinearExpr.weightedSum(allSlots, slotDurations)

            // Overtime hours = max(0, total hours - threshold)
            // Constraint: overtime <= maxOvertimeHours
            val overtimeHours = model.newIntVar(0, 200, "overtime_hours_$e")
            model.addGreaterOrEqual(
                overtimeHours,
                LinearExpr.affine(totalWeeklyHours, 1, -overtimeThreshold)
            )
            model.addLessOrEqual(overtimeHours, rules.maxOvertimeHours.toLong())
        }

        addMinRestBetweenShiftsConstraints(model, x, input, rules.minRestBetweenShifts)
        addMaxConsecutiveDaysConstraints(model, x, input, rules.maxConsecutiveDays)
        addMinWeeklyRestConstraints(model, x, input, rules.minWeeklyRestHours)
    }

    /**
     * Builds workedOnDay[e][d] = 1 if employee e works any slot on
     * sortedDates[d], for every employee. Shared by the consecutive-days and
     * weekly-rest constraints, which both reason about whole days off.
     */
    private fun buildWorkedOnDayVars(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput,
        slotsByDate: Map<LocalDate, List<Int>>,
        sortedDates: List<LocalDate>
    ): Array<Array<BoolVar>> {
        return Array(input.employees.size) { e ->
            Array(sortedDates.size) { d ->
                val date = sortedDates[d]
                val dayVars = slotsByDate.getValue(date).map { x[e][it] }.toTypedArray()
                val worked = model.newBoolVar("worked_${e}_$date")
                // worked == OR(dayVars): worked >= each var, and worked <= sum(vars)
                dayVars.forEach { model.addLessOrEqual(it, worked) }
                model.addLessOrEqual(worked, LinearExpr.sum(dayVars))
                worked
            }
        }
    }

    /**
     * Enforces a maximum number of consecutive worked days: over any window of
     * (maxConsecutiveDays + 1) consecutive calendar days in the schedule
     * period, at least one day must be entirely unworked for that employee.
     * Days outside the schedule period aren't modeled, so a run that's only
     * cut short by the period's start/end (rather than an actual day off)
     * isn't flagged - this mirrors how the rest-between-shifts constraint only
     * reasons about slots that actually exist in this schedule.
     */
    private fun addMaxConsecutiveDaysConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput,
        maxConsecutiveDays: Int
    ) {
        if (maxConsecutiveDays <= 0) return

        // Grouped by businessDate, not the calendar date: the small hours of a late night
        // belong to the night that opened, so a single 21:00-02:00 shift counts as one day
        // worked. Counting it as two would let one shift eat two days of the allowance and
        // trip this limit off a schedule nobody would call excessive.
        val slotsByDate = input.timeSlots.indices.groupBy { input.timeSlots[it].businessDate }
        val sortedDates = slotsByDate.keys.sorted()
        if (sortedDates.size <= maxConsecutiveDays) return

        val workedOnDay = buildWorkedOnDayVars(model, x, input, slotsByDate, sortedDates)

        for (e in input.employees.indices) {
            // Slide a window of (maxConsecutiveDays + 1) days; at least one
            // day in every such window must be unworked.
            val windowSize = maxConsecutiveDays + 1
            for (start in 0..(sortedDates.size - windowSize)) {
                val window = (start until start + windowSize).map { workedOnDay[e][it] }.toTypedArray()
                model.addLessOrEqual(LinearExpr.sum(window), maxConsecutiveDays.toLong())
            }
        }
    }

    /**
     * Enforces weekly rest (gov.uk: an uninterrupted 24 hours without work
     * each week): over any rolling 7-calendar-day window in the schedule
     * period, at least one full calendar day must be entirely unworked.
     *
     * This is a deliberate simplification of "an uninterrupted period of at
     * least minWeeklyRestHours somewhere in the window," which would require
     * reasoning about exact clock-time gaps spanning multiple days (including
     * closed/non-operating hours that have no time slots at all to reason
     * about). A full day off, combined with the closed hours on the days
     * immediately before and after it, comfortably exceeds a same-order-of-
     * magnitude rest requirement (e.g. 24h) in every realistic operating-
     * hours scenario, so "at least one full day off per rolling week" is used
     * as a sound proxy rather than exact interval arithmetic. Weeks entirely
     * outside the schedule period aren't modeled, matching the other rest
     * constraints.
     */
    private fun addMinWeeklyRestConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput,
        minWeeklyRestHours: Double
    ) {
        if (minWeeklyRestHours <= 0.0) return

        // By businessDate for the same reason as the consecutive-days rule: this is about
        // whole days off, and the small hours of a late night are part of the night before.
        // Keyed by calendar date, a Saturday shift running to 02:00 would mark Sunday as
        // worked and leave the employee with no free day the rule could find.
        val slotsByDate = input.timeSlots.indices.groupBy { input.timeSlots[it].businessDate }
        val sortedDates = slotsByDate.keys.sorted()
        val windowSize = 7
        if (sortedDates.size < windowSize) return

        val workedOnDay = buildWorkedOnDayVars(model, x, input, slotsByDate, sortedDates)

        for (e in input.employees.indices) {
            // At least one day in every rolling 7-day window must be
            // unworked, i.e. at most 6 of the 7 days can be worked.
            for (start in 0..(sortedDates.size - windowSize)) {
                val window = (start until start + windowSize).map { workedOnDay[e][it] }.toTypedArray()
                model.addLessOrEqual(LinearExpr.sum(window), (windowSize - 1).toLong())
            }
        }
    }

    /**
     * Enforces minimum rest between shift days: an employee cannot start a
     * shift on one calendar day within `minRestHours` of ending a shift on a
     * different calendar day (e.g. finishing at 8pm means the next day's
     * shift cannot start before 7am for an 11-hour minimum). Deliberately
     * does NOT apply within the same calendar day - a same-day split shift
     * with a short gap is a separate concept (meal/rest breaks), not this
     * constraint.
     *
     * Only applies at genuine shift boundaries - the end of one contiguous
     * worked block to the start of the next - not to every pair of worked
     * slots with a small gap. Naively forbidding any such pair would also
     * forbid the solver from filling in the slots between them (turning a
     * legitimate single longer shift into an impossible "split shift"),
     * which can make otherwise-easy scenarios infeasible.
     */
    private fun addMinRestBetweenShiftsConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput,
        minRestHours: Double
    ) {
        if (minRestHours <= 0.0) return

        val slots = input.timeSlots

        val (prevSlot, nextSlot) = buildAdjacentSlotIndex(slots)

        val slotStarts = slots.map { it.date.atTime(it.startTime) }
        // A slot ending at midnight ends at the *close* of its date. Taken literally,
        // 00:00 on the same date lands before the slot began, turning a real rest gap
        // into a negative one that the constraint below then ignores.
        val slotEnds = slots.map {
            if (it.endTime <= it.startTime) it.date.plusDays(1).atTime(it.endTime)
            else it.date.atTime(it.endTime)
        }

        for (e in input.employees.indices) {
            // isBlockEnd[t] = worked(t) AND NOT worked(nextSlot(t)) (or no next
            // slot at all) - i.e. t is worked but doesn't continue into another
            // worked slot right after it. isBlockStart[t] is the mirror image.
            val (isBlockStart, isBlockEnd) =
                buildBlockBoundaryVars(model, x, e, slots, prevSlot, nextSlot, "rest")

            // Forbid a block-end -> block-start pair whose gap is positive but
            // shorter than the minimum rest, and only when the two fall on
            // different calendar dates - a same-day gap is out of scope.
            for (t1 in slots.indices) {
                for (t2 in slots.indices) {
                    if (t1 == t2) continue
                    if (slots[t1].date == slots[t2].date) continue
                    val gapHours = java.time.Duration.between(slotEnds[t1], slotStarts[t2]).toMinutes() / 60.0
                    if (gapHours > 0.0 && gapHours < minRestHours) {
                        model.addLessOrEqual(
                            LinearExpr.sum(arrayOf(isBlockEnd[t1]!!, isBlockStart[t2]!!)),
                            1
                        )
                    }
                }
            }
        }
    }

    /**
     * Enforces each employee's per-day hour cap (Contract.maxHoursPerDay).
     * Unlike the weekly rules above, this comes directly from the employee's
     * own contract rather than the business-level WorkingHoursRules, so it
     * applies unconditionally regardless of whether working hours rules are
     * configured for the business.
     */
    private fun addMaxHoursPerDayConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput
    ) {
        val slotDurations = input.timeSlots.map { it.durationHours.toLong() }.toLongArray()
        val slotsByDate = input.timeSlots.indices.groupBy { input.timeSlots[it].date }

        for (e in input.employees.indices) {
            val maxHoursPerDay = input.employees[e].contract.maxHoursPerDay.toLong()

            for ((_, slotIndices) in slotsByDate) {
                val dayVars = slotIndices.map { x[e][it] }.toTypedArray()
                val dayDurations = slotIndices.map { slotDurations[it] }.toLongArray()

                model.addLessOrEqual(
                    LinearExpr.weightedSum(dayVars, dayDurations),
                    maxHoursPerDay
                )
            }
        }
    }

    /**
     * Enforces contracted hours constraints from ConstraintsService.
     * Ensures employees work within their min/contracted/max hours.
     */
    private fun addContractedHoursConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        totalHours: Array<IntVar>,
        input: OptimizationInput
    ) {
        if (input.contractedHours.isEmpty()) return

        for (e in input.employees.indices) {
            val employee = input.employees[e]
            val contracted = input.contractedHours[employee.id] ?: continue

            // Employee should work at least minHours
            model.addGreaterOrEqual(totalHours[e], contracted.minHours.toLong())

            // Employee should not exceed maxHours
            model.addLessOrEqual(totalHours[e], contracted.maxHours.toLong())

            // Optionally: soft constraint to prefer contractedHours
            // This would require additional objective terms
        }
    }

    /**
     * Enforces compliance rules from ConstraintsService: the rest-break and
     * minor-labor-law rules. Overtime is handled separately, in
     * addHoursConstraints / the cost model, via each employee's
     * contract.overtimeThreshold.
     */
    private fun addComplianceRulesConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput
    ) {
        val compliance = input.complianceRules ?: return

        if (compliance.mealBreakRequired) {
            addRestBreakConstraints(
                model,
                x,
                input,
                compliance.mealBreakMinShiftHours,
                compliance.mealBreakDuration
            )
        }

        if (compliance.minorLaborLawsEnabled) {
            addMinorLaborLawConstraints(model, x, input)
        }
    }

    /**
     * Enforces rest breaks (gov.uk: an uninterrupted break during the working
     * day once it exceeds a threshold length) as two paired constraints:
     *
     *  1. No contiguous worked block may exceed `maxContinuousHours` - over
     *     any consecutive run of slots longer than the threshold, at least one
     *     slot must be unworked, which forces a break to appear somewhere.
     *  2. Whenever a block ends and the same employee starts another block
     *     later that same day, the gap between them must be at least
     *     `breakDurationMinutes` - otherwise constraint 1 could be satisfied
     *     by a token one-slot gap that is shorter than the break the manager
     *     configured.
     *
     * Together these mean "work at most N hours before taking a break of at
     * least M minutes". Constraint 2 is deliberately same-day only: gaps
     * spanning different calendar days are the daily-rest rule's concern, and
     * addMinRestBetweenShiftsConstraints already covers those (it skips
     * same-day pairs for exactly this reason), so the two compose without
     * either one duplicating or contradicting the other.
     *
     * Note that a break is modeled as an unworked slot, so the granularity of
     * an enforceable break is the time-slot length (1 hour as slots are
     * currently generated). A configured break shorter than one slot is still
     * enforced correctly, just conservatively - the gap it produces is a whole
     * slot, which is longer than strictly required.
     */
    private fun addRestBreakConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput,
        maxContinuousHours: Double,
        breakDurationMinutes: Int
    ) {
        if (maxContinuousHours <= 0.0) return

        val slots = input.timeSlots

        // Constraint 1: cap the length of any contiguous worked block. Mirrors
        // the max-shift-length window logic - walk each consecutive run and,
        // once it exceeds the threshold, forbid every slot in it being worked.
        for (e in input.employees.indices) {
            for (startIdx in slots.indices) {
                var totalDuration = 0.0
                var currentIdx = startIdx

                while (currentIdx < slots.size) {
                    val currentSlot = slots[currentIdx]
                    totalDuration += currentSlot.durationHours

                    if (totalDuration > maxContinuousHours) {
                        val windowVars = (startIdx..currentIdx).map { x[e][it] }.toTypedArray()
                        model.addLessOrEqual(
                            LinearExpr.sum(windowVars),
                            (currentIdx - startIdx + 1).toLong() - 1
                        )
                        break // Longer windows are implied by this one
                    }

                    val nextIdx = currentIdx + 1
                    if (nextIdx >= slots.size) break
                    val nextSlot = slots[nextIdx]
                    val contiguous = currentSlot.endTime == nextSlot.startTime &&
                        (currentSlot.date == nextSlot.date ||
                            currentSlot.date.plusDays(1) == nextSlot.date)
                    if (!contiguous) break
                    currentIdx = nextIdx
                }
            }
        }

        if (breakDurationMinutes <= 0) return

        // Constraint 2: any same-day gap between two worked blocks must be at
        // least the configured break duration.
        val minBreakHours = breakDurationMinutes / 60.0
        val (prevSlot, nextSlot) = buildAdjacentSlotIndex(slots)
        val slotStarts = slots.map { it.date.atTime(it.startTime) }
        // A slot ending at midnight ends at the *close* of its date. Taken literally,
        // 00:00 on the same date lands before the slot began, turning a real rest gap
        // into a negative one that the constraint below then ignores.
        val slotEnds = slots.map {
            if (it.endTime <= it.startTime) it.date.plusDays(1).atTime(it.endTime)
            else it.date.atTime(it.endTime)
        }

        for (e in input.employees.indices) {
            val (isBlockStart, isBlockEnd) = buildBlockBoundaryVars(model, x, e, slots, prevSlot, nextSlot, "break")

            for (t1 in slots.indices) {
                for (t2 in slots.indices) {
                    if (t1 == t2) continue
                    // Same-day only - cross-day gaps belong to daily rest.
                    if (slots[t1].date != slots[t2].date) continue
                    val gapHours = java.time.Duration.between(slotEnds[t1], slotStarts[t2]).toMinutes() / 60.0
                    if (gapHours > 0.0 && gapHours < minBreakHours) {
                        model.addLessOrEqual(
                            LinearExpr.sum(arrayOf(isBlockEnd[t1]!!, isBlockStart[t2]!!)),
                            1
                        )
                    }
                }
            }
        }
    }

    /**
     * Restricts employees who are under 18 for any part of the schedule
     * period. Age is derived from Employee.dateOfBirth against each slot's
     * own date, so an employee turning 18 mid-schedule is correctly treated
     * as a minor only for the slots that fall before their birthday.
     *
     * Applies the two restrictions that are well-defined without
     * jurisdiction-specific configuration: a lower daily hours cap, and a ban
     * on working late-night hours. These are intentionally conservative
     * defaults rather than a full model of any single jurisdiction's minor
     * labor code.
     */
    private fun addMinorLaborLawConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput
    ) {
        val slots = input.timeSlots
        val slotsByDate = slots.indices.groupBy { slots[it].date }

        for (e in input.employees.indices) {
            val dateOfBirth = input.employees[e].dateOfBirth

            for ((date, slotIndices) in slotsByDate) {
                val isMinorOnDate = date.isBefore(dateOfBirth.plusYears(MINOR_AGE_YEARS))
                if (!isMinorOnDate) continue

                // Lower daily hours cap for minors.
                val dayVars = slotIndices.map { x[e][it] }.toTypedArray()
                val dayDurations = slotIndices.map { slots[it].durationHours.toLong() }.toLongArray()
                model.addLessOrEqual(
                    LinearExpr.weightedSum(dayVars, dayDurations),
                    MINOR_MAX_HOURS_PER_DAY
                )

                // No late-night work: forbid any slot that overlaps the
                // restricted window [MINOR_NIGHT_START, MINOR_NIGHT_END).
                for (t in slotIndices) {
                    val slot = slots[t]
                    val overlapsNight = slot.startTime < MINOR_NIGHT_END || slot.endTime > MINOR_NIGHT_START
                    if (overlapsNight) {
                        model.addEquality(x[e][t], 0)
                    }
                }
            }
        }
    }

    /**
     * For each slot, the index of the slot immediately preceding/following it
     * in time (contiguous: one's end time equals the other's start time, same
     * date or the next calendar date), or null if none exists in this
     * schedule's slot list.
     */
    private fun buildAdjacentSlotIndex(
        slots: List<TimeSlot>
    ): Pair<Array<Int?>, Array<Int?>> {
        val prevSlot = arrayOfNulls<Int>(slots.size)
        val nextSlot = arrayOfNulls<Int>(slots.size)

        // Matching purely on "end time == start time" is ambiguous once the
        // schedule spans multiple days: every day has a slot starting at the
        // same clock time, so a same-day successor and a different-day one
        // both match. Comparing full date-times instead makes the successor
        // unique - and it still admits a genuine overnight slot, whose start
        // date-time really is the previous slot's end date-time rolled past
        // midnight.
        val startAt = slots.map { it.date.atTime(it.startTime) }
        val endAt = slots.map {
            // A slot ending at midnight belongs to the following calendar day.
            if (it.endTime <= it.startTime) it.date.plusDays(1).atTime(it.endTime)
            else it.date.atTime(it.endTime)
        }

        val startIndex = startAt.indices.groupBy { startAt[it] }
        for (t1 in slots.indices) {
            val successor = startIndex[endAt[t1]]?.firstOrNull { it != t1 } ?: continue
            nextSlot[t1] = successor
            prevSlot[successor] = t1
        }
        return prevSlot to nextSlot
    }

    /**
     * Builds isBlockStart[t] / isBlockEnd[t] for one employee: t is worked but
     * isn't preceded / followed by another worked slot, i.e. t is the boundary
     * of a contiguous worked block. Used by the rest-break and daily-rest
     * constraints, which both reason about gaps between blocks rather than
     * between individual slots.
     */
    private fun buildBlockBoundaryVars(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        e: Int,
        slots: List<TimeSlot>,
        prevSlot: Array<Int?>,
        nextSlot: Array<Int?>,
        namePrefix: String
    ): Pair<Array<BoolVar?>, Array<BoolVar?>> {
        val isBlockStart = arrayOfNulls<BoolVar>(slots.size)
        val isBlockEnd = arrayOfNulls<BoolVar>(slots.size)

        for (t in slots.indices) {
            val next = nextSlot[t]
            isBlockEnd[t] = if (next == null) {
                x[e][t]
            } else {
                val blockEnd = model.newBoolVar("${namePrefix}_blockend_${e}_$t")
                // blockEnd <= worked(t); blockEnd <= 1 - worked(next); blockEnd >= worked(t) - worked(next)
                model.addLessOrEqual(blockEnd, x[e][t])
                model.addLessOrEqual(blockEnd, LinearExpr.affine(x[e][next], -1, 1))
                model.addGreaterOrEqual(
                    blockEnd,
                    LinearExpr.weightedSum(arrayOf(x[e][t], x[e][next]), longArrayOf(1, -1))
                )
                blockEnd
            }

            val prev = prevSlot[t]
            isBlockStart[t] = if (prev == null) {
                x[e][t]
            } else {
                val blockStart = model.newBoolVar("${namePrefix}_blockstart_${e}_$t")
                model.addLessOrEqual(blockStart, x[e][t])
                model.addLessOrEqual(blockStart, LinearExpr.affine(x[e][prev], -1, 1))
                model.addGreaterOrEqual(
                    blockStart,
                    LinearExpr.weightedSum(arrayOf(x[e][t], x[e][prev]), longArrayOf(1, -1))
                )
                blockStart
            }
        }

        return isBlockStart to isBlockEnd
    }

    private fun extractSolution(
        solver: CpSolver,
        x: Array<Array<BoolVar>>,
        totalHours: Array<IntVar>,
        input: OptimizationInput,
        isOptimal: Boolean
    ): OptimizationResult {
        val assignments = mutableListOf<EmployeeAssignment>()

        for (e in input.employees.indices) {
            val workedSlots = mutableListOf<Int>()
            for (t in input.timeSlots.indices) {
                if (solver.value(x[e][t]) == 1L) {
                    workedSlots.add(t)
                }
            }

            if (workedSlots.isNotEmpty()) {
                assignments.add(
                    EmployeeAssignment(
                        employeeIndex = e,
                        employeeId = input.employees[e].id,
                        timeSlotIndices = workedSlots,
                        totalHours = solver.value(totalHours[e])
                    )
                )
            }
        }

        return OptimizationResult(
            assignments = assignments,
            objectiveValue = solver.objectiveValue(),
            isOptimal = isOptimal
        )
    }
}

/**
 * Input data for the schedule optimization model.
 */
data class OptimizationInput(
    val employees: List<Employee>,
    val timeSlots: List<TimeSlot>,
    val projectedSales: List<Double>, // Sales forecast for each time slot
    val availability: List<List<Boolean>>, // availability[employee][timeSlot]
    val productivity: List<List<Double>>, // productivity[employee][timeSlot] - can vary by time
    val coverageFraction: Double = 0.8, // What fraction of sales should be covered
    val laborBudget: Long = Long.MAX_VALUE, // Maximum labor budget
    val objective: OptimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST,
    val maxSolveTimeSeconds: Double = 5.0,

    // Constraint objects from ConstraintsService
    val budgetConstraints: org.labormanagement.model.BudgetConstraints? = null,
    val workingHoursRules: org.labormanagement.model.WorkingHoursRules? = null,
    val complianceRules: org.labormanagement.model.ComplianceRules? = null,
    val fairnessSettings: org.labormanagement.model.FairnessSettings? = null,
    val contractedHours: Map<UUID, org.labormanagement.model.EmployeeContractedHours> = emptyMap(),

    // Hours already committed at other locations in this window, by employee.
    // Weekly caps are one budget per person rather than one per location, so
    // these hours are spent before the solver allocates anything.
    val hoursCommittedElsewhere: Map<UUID, Double> = emptyMap()
) {
    fun isAvailable(employeeIndex: Int, timeSlotIndex: Int): Boolean {
        return availability[employeeIndex][timeSlotIndex]
    }

    /**
     * How many hours of [cap] remain for this employee once hours already
     * worked at other locations are deducted. Never negative: someone already
     * over their cap elsewhere gets zero here, not a constraint the solver
     * cannot satisfy.
     */
    fun remainingOf(cap: Double, employeeIndex: Int): Long {
        val spent = hoursCommittedElsewhere[employees[employeeIndex].id] ?: 0.0
        return maxOf(0.0, cap - spent).toLong()
    }

    fun getProductivity(employeeIndex: Int, timeSlotIndex: Int): Long {
        return productivity[employeeIndex][timeSlotIndex].toLong()
    }

}

/**
 * Represents a time slot in the schedule (e.g., a single hour or shift period).
 */
/**
 * One schedulable hour of a business day.
 *
 * [date] is the calendar date the slot actually falls on, so a business open 21:00-02:00
 * produces slots on both the opening date and the one after. Hours therefore land on the
 * day they are really worked.
 *
 * [businessDate] is the date whose opening the slot belongs to - the same for every slot
 * of one night, including the ones past midnight. The two differ only after midnight, and
 * keeping both is what lets "how many hours on Sunday" and "did they work Saturday night"
 * be answered differently. Counting a 21:00-02:00 shift as two days worked would make one
 * night out look like two, which trips the consecutive-days limit off a single shift.
 */
data class TimeSlot(
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val durationHours: Double,
    val businessDate: LocalDate = date
) {
    val dayOfWeek: DayOfWeek = date.dayOfWeek

    /** True when this slot falls after midnight, in the tail of the previous day's opening. */
    val isAfterMidnight: Boolean get() = date != businessDate
}

/**
 * Result from the optimization solver.
 */
data class OptimizationResult(
    val assignments: List<EmployeeAssignment>,
    val objectiveValue: Double,
    val isOptimal: Boolean
)

/**
 * Represents the assignment of an employee to time slots.
 */
data class EmployeeAssignment(
    val employeeIndex: Int,
    val employeeId: UUID,
    val timeSlotIndices: List<Int>,
    val totalHours: Long
)
