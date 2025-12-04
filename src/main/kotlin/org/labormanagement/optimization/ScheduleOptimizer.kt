package org.labormanagement.optimization

import com.google.ortools.Loader
import com.google.ortools.sat.*
import io.ktor.http.decodeCookieValue
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
        addHoursConstraints(model, x, totalHours, regular, overtime, input)
        addSalesCoverageConstraints(model, x, coverage, input, slack)
        addLaborCostConstraints(model, regular, overtime, laborCost, input)
        addLaborHoursVariable(model, x, hoursDeviation, input)

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

    private fun addHoursConstraints(
        model: CpModel,
        x: Array<Array<BoolVar>>,
        totalHours: Array<IntVar>,
        regular: Array<IntVar>,
        overtime: Array<IntVar>,
        input: OptimizationInput
    ) {
        for (e in input.employees.indices) {
            // Total hours = sum of all time slots worked
            val sum = LinearExpr.sum(x[e])
            model.addEquality(totalHours[e], sum)

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
        val workableHours = IntArray(input.employees.size) { e ->
            // Count how many slots this employee *could* work
            (0 until input.timeSlots.size).count { t -> input.isAvailable(e, t) }
        }
        val assignedHours = Array(input.employees.size) { e ->
            model.newIntVar(0, workableHours[e].toLong(), "assigned_hours_$e")
        }
        val targetHours = model.newIntVar(0, 1_000_000, "target_hours")
        val totalAssignedHours = model.newIntVar(0, 1_000_000, "total_assigned_hours")

        val totalHourTerms = mutableListOf<LinearExpr>()

        for (e in input.employees.indices) {
            val assignedHourTerms = mutableListOf<LinearExpr>()

            for (t in input.timeSlots.indices) {
                totalHourTerms += LinearExpr.term(x[e][t], 1)
                assignedHourTerms += LinearExpr.term(x[e][t], 1)
            }

            model.addEquality(assignedHours[e], LinearExpr.sum(assignedHourTerms.toTypedArray()))
        }
        model.addEquality(totalAssignedHours, LinearExpr.sum(totalHourTerms.toTypedArray()))

        // targetHours = totalAssignedHours / numEmployees
        model.addEquality(LinearExpr.term(targetHours, input.employees.size.toLong()),totalAssignedHours)

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
    val maxSolveTimeSeconds: Double = 5.0
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
