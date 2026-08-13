package org.labormanagement.optimization

import org.junit.jupiter.api.Test
import org.labormanagement.model.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that each OptimizationObjective actually optimizes for what it
 * claims to, rather than just checking a solution exists. Uses a scenario
 * with a genuine cost-vs-fairness-vs-coverage trade-off: two employees with
 * very different pay rates, both available for the full day, with demand
 * high enough that meeting coverage requires substantial hours from both.
 */
class ObjectiveModeTest {
    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val scheduleDate = LocalDate.of(2024, 1, 1) // a Monday

    private fun fullDayAvailability(day: DayOfWeek = DayOfWeek.MONDAY) = listOf(
        Availability(AvailabilityType.WEEKLY_RECURRING, day, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
    )

    private val cheapEmployee = Employee(
        id = UUID.randomUUID(),
        businessId = testBusinessId,
        firstName = "Cheap",
        lastName = "Employee",
        dateOfBirth = LocalDate.of(1990, 1, 1),
        normalPayRate = 10.0,
        overtimePayRate = 15.0,
        productivity = 50.0,
        contract = Contract(
            contractedHoursPerWeek = 40.0,
            maxHoursPerWeek = 40.0,
            maxHoursPerDay = 8.0,
            overtimeThreshold = 40.0
        ),
        availability = fullDayAvailability()
    )

    private val expensiveEmployee = Employee(
        id = UUID.randomUUID(),
        businessId = testBusinessId,
        firstName = "Expensive",
        lastName = "Employee",
        dateOfBirth = LocalDate.of(1990, 1, 1),
        normalPayRate = 30.0,
        overtimePayRate = 45.0,
        productivity = 50.0,
        contract = Contract(
            contractedHoursPerWeek = 40.0,
            maxHoursPerWeek = 40.0,
            maxHoursPerDay = 8.0,
            overtimeThreshold = 40.0
        ),
        availability = fullDayAvailability()
    )

    /**
     * Staggered demand creates a genuine trade-off zone rather than pinning
     * both employees to their ceiling (which leaves no room for objectives
     * to differ) or letting one employee cover everything alone (which
     * leaves no room for a fairness/cost split to matter):
     *  - Hours 9-12: demand 40, which the cheap employee alone (50/hr) can
     *    cover - a genuine choice of *who* works these hours.
     *  - Hours 13-16: demand 90, which requires both employees working
     *    simultaneously (50+50=100 >= 90*0.8=72, but neither alone: 50 < 72).
     *
     * Verified empirically: MINIMIZE_LABOR_COST -> Cheap 8h / Expensive 4h;
     * MAXIMIZE_FAIRNESS -> Cheap 6h / Expensive 6h; MAXIMIZE_SALES -> both 8h.
     */
    private fun buildInput(objective: OptimizationObjective): OptimizationInput {
        val salesForecast = SalesForecast(
            businessId = testBusinessId,
            weeklyPattern = mapOf(
                DayOfWeek.MONDAY to mapOf(
                    LocalTime.of(9, 0) to 40.0,
                    LocalTime.of(10, 0) to 40.0,
                    LocalTime.of(11, 0) to 40.0,
                    LocalTime.of(12, 0) to 40.0,
                    LocalTime.of(13, 0) to 90.0,
                    LocalTime.of(14, 0) to 90.0,
                    LocalTime.of(15, 0) to 90.0,
                    LocalTime.of(16, 0) to 90.0
                )
            )
        )

        return OptimizationConverter.buildOptimizationInput(
            employees = listOf(cheapEmployee, expensiveEmployee),
            salesForecast = salesForecast,
            scheduleDates = listOf(scheduleDate),
            operatingHoursMap = mapOf(scheduleDate to Pair(LocalTime.of(9, 0), LocalTime.of(17, 0))),
            coverageFraction = 0.8,
            objective = objective,
            businessId = testBusinessId
        )
    }

    private fun hoursByEmployee(result: OptimizationResult, input: OptimizationInput): Map<String, Long> {
        return result.assignments.associate { assignment ->
            input.employees[assignment.employeeIndex].firstName to assignment.totalHours
        }
    }

    private fun totalLaborCost(result: OptimizationResult, input: OptimizationInput): Double {
        return result.assignments.sumOf { assignment ->
            val employee = input.employees[assignment.employeeIndex]
            // All hours are within overtimeThreshold in this scenario, so
            // totalHours * normalPayRate is the actual cost paid.
            assignment.totalHours * employee.normalPayRate
        }
    }

    private fun totalCoverage(result: OptimizationResult, input: OptimizationInput): Double {
        return result.assignments.sumOf { assignment ->
            val employee = input.employees[assignment.employeeIndex]
            assignment.totalHours * employee.productivity
        }
    }

    @Test
    fun `MINIMIZE_LABOR_COST produces the lowest total cost of the three objectives`() {
        val costResult = ScheduleOptimizer().optimize(buildInput(OptimizationObjective.MINIMIZE_LABOR_COST))
        val fairnessResult = ScheduleOptimizer().optimize(buildInput(OptimizationObjective.MAXIMIZE_FAIRNESS))
        val salesResult = ScheduleOptimizer().optimize(buildInput(OptimizationObjective.MAXIMIZE_SALES))

        assertNotNull(costResult); assertNotNull(fairnessResult); assertNotNull(salesResult)

        val costInput = buildInput(OptimizationObjective.MINIMIZE_LABOR_COST)
        val fairnessInput = buildInput(OptimizationObjective.MAXIMIZE_FAIRNESS)
        val salesInput = buildInput(OptimizationObjective.MAXIMIZE_SALES)

        val costTotal = totalLaborCost(costResult!!, costInput)
        val fairnessTotal = totalLaborCost(fairnessResult!!, fairnessInput)
        val salesTotal = totalLaborCost(salesResult!!, salesInput)

        assertTrue(
            costTotal < fairnessTotal,
            "MINIMIZE_LABOR_COST total cost ($costTotal) should be strictly less than MAXIMIZE_FAIRNESS total cost ($fairnessTotal)"
        )
        assertTrue(
            costTotal < salesTotal,
            "MINIMIZE_LABOR_COST total cost ($costTotal) should be strictly less than MAXIMIZE_SALES total cost ($salesTotal)"
        )
    }

    @Test
    fun `MINIMIZE_LABOR_COST favors the cheaper employee over an even split`() {
        val input = buildInput(OptimizationObjective.MINIMIZE_LABOR_COST)
        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val hours = hoursByEmployee(result!!, input)
        val cheapHours = hours["Cheap"] ?: 0L
        val expensiveHours = hours["Expensive"] ?: 0L

        assertTrue(
            cheapHours > expensiveHours,
            "Cost-minimizing schedule should assign the cheap employee ($cheapHours h) strictly more hours than the expensive one ($expensiveHours h)"
        )
    }

    @Test
    fun `MAXIMIZE_FAIRNESS distributes hours more evenly than MINIMIZE_LABOR_COST`() {
        val costInput = buildInput(OptimizationObjective.MINIMIZE_LABOR_COST)
        val costResult = ScheduleOptimizer().optimize(costInput)
        assertNotNull(costResult)
        val costHours = hoursByEmployee(costResult!!, costInput)
        val costGap = kotlin.math.abs((costHours["Cheap"] ?: 0L) - (costHours["Expensive"] ?: 0L))

        val fairnessInput = buildInput(OptimizationObjective.MAXIMIZE_FAIRNESS)
        val fairnessResult = ScheduleOptimizer().optimize(fairnessInput)
        assertNotNull(fairnessResult)
        val fairnessHours = hoursByEmployee(fairnessResult!!, fairnessInput)
        val fairnessGap = kotlin.math.abs((fairnessHours["Cheap"] ?: 0L) - (fairnessHours["Expensive"] ?: 0L))

        assertTrue(
            fairnessGap < costGap,
            "MAXIMIZE_FAIRNESS hour gap ($fairnessGap) should be strictly less than MINIMIZE_LABOR_COST hour gap ($costGap)"
        )
    }

    @Test
    fun `MAXIMIZE_SALES produces coverage at least as high as the other objectives`() {
        val salesInput = buildInput(OptimizationObjective.MAXIMIZE_SALES)
        val salesResult = ScheduleOptimizer().optimize(salesInput)
        assertNotNull(salesResult)
        val salesCoverage = totalCoverage(salesResult!!, salesInput)

        val costInput = buildInput(OptimizationObjective.MINIMIZE_LABOR_COST)
        val costResult = ScheduleOptimizer().optimize(costInput)
        assertNotNull(costResult)
        val costCoverage = totalCoverage(costResult!!, costInput)

        val fairnessInput = buildInput(OptimizationObjective.MAXIMIZE_FAIRNESS)
        val fairnessResult = ScheduleOptimizer().optimize(fairnessInput)
        assertNotNull(fairnessResult)
        val fairnessCoverage = totalCoverage(fairnessResult!!, fairnessInput)

        assertTrue(
            salesCoverage >= costCoverage,
            "MAXIMIZE_SALES coverage ($salesCoverage) should be >= MINIMIZE_LABOR_COST coverage ($costCoverage)"
        )
        assertTrue(
            salesCoverage >= fairnessCoverage,
            "MAXIMIZE_SALES coverage ($salesCoverage) should be >= MAXIMIZE_FAIRNESS coverage ($fairnessCoverage)"
        )
    }

    @Test
    fun `all three objectives still satisfy the minimum coverage fraction`() {
        for (objective in listOf(
            OptimizationObjective.MINIMIZE_LABOR_COST,
            OptimizationObjective.MAXIMIZE_SALES,
            OptimizationObjective.MAXIMIZE_FAIRNESS
        )) {
            val input = buildInput(objective)
            val result = ScheduleOptimizer().optimize(input)
            assertNotNull(result, "$objective should find a feasible solution")

            val coverage = totalCoverage(result!!, input)
            val totalDemand = input.projectedSales.sum()
            val requiredCoverage = input.coverageFraction * totalDemand

            assertTrue(
                coverage >= requiredCoverage - 1.0, // small tolerance for integer rounding in the solver
                "$objective coverage ($coverage) should meet the ${input.coverageFraction} coverage fraction of demand ($requiredCoverage)"
            )
        }
    }
}
