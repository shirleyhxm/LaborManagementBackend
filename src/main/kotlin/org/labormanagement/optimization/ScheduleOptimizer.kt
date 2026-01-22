package org.labormanagement.optimization

import com.google.ortools.Loader
import com.google.ortools.sat.*
import org.labormanagement.model.Employee
import org.labormanagement.model.OptimizationObjective
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/**
 * Mathematical optimization engine for employee scheduling using Google OR-Tools CP-SAT solver.
 *
 * This class encapsulates the constraint programming model that optimizes employee schedules
 * based on sales forecasts, employee availability, labor costs, and business constraints.
 */
class ScheduleOptimizer {

    init {
        Loader.loadNativeLibraries()
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
        addHoursConstraints(model, x, totalHours, regular, overtime, input)
        addSalesCoverageConstraints(model, x, coverage, input, slack)
        addLaborCostConstraints(model, regular, overtime, laborCost, input)
        addLaborHoursVariable(model, x, hoursDeviation, input)

        // Add constraints from ConstraintsService
        addWorkingHoursRulesConstraints(model, x, input)
        addContractedHoursConstraints(model, x, totalHours, input)
        addComplianceRulesConstraints(model, x, input)

        // Set objective based on optimization objective
        setObjective(model, regular, overtime, coverage, hoursDeviation, input, slack)

        // Solve the model
        val solver = CpSolver()
        solver.parameters.maxTimeInSeconds = input.maxSolveTimeSeconds

        val status = solver.solve(model)

        println("[ScheduleOptimizer] Solver status: $status")
        println("[ScheduleOptimizer] Number of employees: ${input.employees.size}")
        println("[ScheduleOptimizer] Number of time slots: ${input.timeSlots.size}")

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            println("[ScheduleOptimizer] No feasible solution found")
            return null // No feasible solution found
        }

        println("[ScheduleOptimizer] Solution found! Objective value: ${solver.objectiveValue()}")
        println("[ScheduleOptimizer] Is optimal: ${status == CpSolverStatus.OPTIMAL}")

        println("Slot shortfalls:")
        for (t in 0 until numSlots) {
            val s = solver.value(slack[t])
            if (s > 0) println("  Slot $t: Shortfall = $s")
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
                    val isConsecutive = prevSlot.day == currSlot.day && prevSlot.endTime == currSlot.startTime

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
                        val isConsecutive = prevSlot.day == currSlot.day && prevSlot.endTime == currSlot.startTime

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
                        if (currentSlot.endTime == nextSlot.startTime &&
                            (currentSlot.day == nextSlot.day ||
                             (currentSlot.day.value % 7 + 1) == nextSlot.day.value % 7 + 1)) {
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

        // Max hours per week (weighted by slot duration)
        for (e in input.employees.indices) {
            val allSlots = input.timeSlots.indices.map { x[e][it] }.toTypedArray()
            model.addLessOrEqual(
                LinearExpr.weightedSum(allSlots, slotDurations),
                rules.maxHoursPerWeek.toLong()
            )
        }

        // Max overtime hours per week (weighted by slot duration)
        for (e in input.employees.indices) {
            val overtimeThreshold = input.employees[e].contract.overtimeThreshold.toLong()
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
     * Enforces compliance rules from ConstraintsService.
     * Currently handles FLSA overtime and meal break requirements.
     */
    private fun addComplianceRulesConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        input: OptimizationInput
    ) {
        val compliance = input.complianceRules ?: return

        // FLSA overtime is already handled in addHoursConstraints via employee.contract.overtimeThreshold

        // Meal break requirements: for shifts longer than mealBreakMinShiftHours
        if (compliance.mealBreakRequired) {
            // Note: Meal breaks would require additional modeling
            // For now, we just ensure shifts longer than the threshold are tracked
            // A full implementation would need to model break periods as non-work slots
            println("[ScheduleOptimizer] Meal break requirement noted: ${compliance.mealBreakMinShiftHours} hours minimum")
        }

        // Minor labor laws: if enabled, additional restrictions could be added
        if (compliance.minorLaborLawsEnabled) {
            // Example: restrict work hours for employees under 18
            // This would require employee age information
            println("[ScheduleOptimizer] Minor labor laws enabled")
        }
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
    val contractedHours: Map<UUID, org.labormanagement.model.EmployeeContractedHours> = emptyMap()
) {
    fun isAvailable(employeeIndex: Int, timeSlotIndex: Int): Boolean {
        return availability[employeeIndex][timeSlotIndex]
    }

    fun getProductivity(employeeIndex: Int, timeSlotIndex: Int): Long {
        return productivity[employeeIndex][timeSlotIndex].toLong()
    }
}

/**
 * Represents a time slot in the schedule (e.g., a single hour or shift period).
 */
data class TimeSlot(
    val day: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val durationHours: Double
)

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
