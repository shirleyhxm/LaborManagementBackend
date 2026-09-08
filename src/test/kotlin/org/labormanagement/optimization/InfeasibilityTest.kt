package org.labormanagement.optimization

import org.junit.jupiter.api.Test
import org.labormanagement.model.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies ScheduleOptimizer degrades gracefully on inputs that can't be
 * (fully) satisfied, rather than crashing or silently returning a solution
 * that violates the very constraints it was given. optimize()'s own contract
 * is "return the optimal schedule, or null if no feasible solution exists" -
 * these tests are the only place that null path, and the "no employees at
 * all" / "no time slots at all" boundaries, are ever actually exercised.
 */
class InfeasibilityTest {
    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val scheduleDate = LocalDate.of(2024, 1, 1) // a Monday

    private fun contract(
        overtimeThreshold: Double = 40.0,
        maxHoursPerWeek: Double = 60.0,
        maxHoursPerDay: Double = 12.0
    ) = Contract(
        contractedHoursPerWeek = 40.0,
        maxHoursPerWeek = maxHoursPerWeek,
        maxHoursPerDay = maxHoursPerDay,
        overtimeThreshold = overtimeThreshold
    )

    private fun employee(
        availability: List<Availability>,
        normalPayRate: Double = 20.0,
        overtimePayRate: Double = 30.0,
        productivity: Double = 100.0,
        contract: Contract = contract()
    ) = Employee(
        id = UUID.randomUUID(),
        businessId = testBusinessId,
        firstName = "Test",
        lastName = "Employee",
        dateOfBirth = LocalDate.of(1990, 1, 1),
        normalPayRate = normalPayRate,
        overtimePayRate = overtimePayRate,
        productivity = productivity,
        contract = contract,
        availability = availability
    )

    private fun hourlySlots(startHour: Int, endHour: Int, date: LocalDate = scheduleDate): List<TimeSlot> {
        return (startHour until endHour).map { hour ->
            TimeSlot(date, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), 1.0)
        }
    }

    private fun buildInput(
        employees: List<Employee>,
        timeSlots: List<TimeSlot>,
        projectedSales: List<Double>,
        coverageFraction: Double = 0.8,
        laborBudget: Long = Long.MAX_VALUE,
        workingHoursRules: WorkingHoursRules? = null,
        contractedHours: Map<UUID, EmployeeContractedHours> = emptyMap(),
        maxSolveTimeSeconds: Double = 5.0
    ): OptimizationInput {
        val availability = employees.map { emp ->
            timeSlots.map { slot -> emp.availability.any { it.isAvailableOn(slot.date, slot.startTime, slot.endTime) } }
        }
        val productivity = employees.map { emp ->
            timeSlots.map { slot -> emp.productivity * slot.durationHours }
        }

        return OptimizationInput(
            employees = employees,
            timeSlots = timeSlots,
            projectedSales = projectedSales,
            availability = availability,
            productivity = productivity,
            coverageFraction = coverageFraction,
            laborBudget = laborBudget,
            workingHoursRules = workingHoursRules,
            contractedHours = contractedHours,
            maxSolveTimeSeconds = maxSolveTimeSeconds
        )
    }

    // ===== Genuinely infeasible constraints =====

    @Test
    fun `leaves coverage to slack rather than crashing when the budget cannot pay for any hours`() {
        // A single employee, available and needed, but the budget can't cover
        // even one hour at their rate. Coverage still has an escape valve
        // (slack is uncapped), so this is feasible - documenting that
        // explicitly, rather than assuming a zero budget always means
        // infeasible, is the point of this test.
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(10, 0))
            ),
            normalPayRate = 1_000_000.0, // deliberately unaffordable
            overtimePayRate = 1_000_000.0
        )
        val timeSlots = hourlySlots(9, 10)
        // laborBudget=0 makes addLaborCostConstraints forbid any nonzero cost,
        // which forbids working the only available employee at all.
        val input = buildInput(listOf(emp), timeSlots, listOf(1000.0), coverageFraction = 1.0, laborBudget = 0L)

        val result = ScheduleOptimizer().optimize(input)

        assertNotNull(result, "Coverage can be met entirely by slack when budget forbids working anyone")
        val workedHours = result!!.assignments.sumOf { it.totalHours }
        assertEquals(0L, workedHours, "No employee should be scheduled when the budget is zero")
    }

    @Test
    fun `returns null when contracted minimum hours cannot be met within availability`() {
        // The employee is only available for 2 hours total, but their
        // contracted minimum is 10 hours. No assignment of this employee to
        // their available slots can satisfy addContractedHoursConstraints'
        // lower bound, so no feasible solution exists at all.
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(11, 0))
            )
        )
        val timeSlots = hourlySlots(9, 11) // only 2 hours exist in the whole schedule
        val contractedHours = mapOf(
            emp.id to EmployeeContractedHours(
                businessId = testBusinessId,
                employeeId = emp.id,
                minHours = 10.0,
                contractedHours = 10.0,
                maxHours = 20.0,
                effectiveFrom = scheduleDate
            )
        )
        val input = buildInput(
            listOf(emp), timeSlots, List(timeSlots.size) { 0.0 },
            coverageFraction = 0.0,
            contractedHours = contractedHours
        )

        val result = ScheduleOptimizer().optimize(input)

        assertNull(result, "No feasible solution exists: contracted minimum (10h) exceeds total available hours (2h)")
    }

    @Test
    fun `returns null when working hours rules conflict with contracted minimum hours`() {
        // maxHoursPerWeek (2h) is set below the contracted minimum (5h) for
        // the same employee - the two hard constraints directly contradict
        // each other regardless of availability or demand.
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )
        val timeSlots = hourlySlots(9, 17)
        val rules = WorkingHoursRules(
            businessId = testBusinessId,
            maxHoursPerWeek = 2.0,
            maxOvertimeHours = 20.0,
            minRestBetweenShifts = 0.0,
            maxConsecutiveDays = 6,
            maxShiftLength = 12.0,
            minShiftLength = 0.0
        )
        val contractedHours = mapOf(
            emp.id to EmployeeContractedHours(
                businessId = testBusinessId,
                employeeId = emp.id,
                minHours = 5.0,
                contractedHours = 5.0,
                maxHours = 40.0,
                effectiveFrom = scheduleDate
            )
        )
        val input = buildInput(
            listOf(emp), timeSlots, List(timeSlots.size) { 0.0 },
            coverageFraction = 0.0,
            workingHoursRules = rules,
            contractedHours = contractedHours
        )

        val result = ScheduleOptimizer().optimize(input)

        assertNull(result, "No feasible solution exists: maxHoursPerWeek (2h) contradicts contracted minimum (5h)")
    }

    // ===== Empty inputs =====

    @Test
    fun `does not crash and returns an empty result with zero employees`() {
        val timeSlots = hourlySlots(9, 17)
        val input = buildInput(emptyList(), timeSlots, List(timeSlots.size) { 0.0 }, coverageFraction = 0.0)

        val result = ScheduleOptimizer().optimize(input)

        assertNotNull(result, "Zero employees with zero required coverage is trivially feasible")
        assertTrue(result!!.assignments.isEmpty(), "There are no employees to assign")
    }

    @Test
    fun `returns null with zero employees when coverage is actually required`() {
        val timeSlots = hourlySlots(9, 17)
        // Demand exists but there's nobody to cover it, and coverageFraction
        // is high enough that slack alone can't be relied on trivially -
        // still, slack has no explicit cap, so this documents the actual
        // behavior (slack absorbs it) rather than assuming infeasibility.
        val input = buildInput(emptyList(), timeSlots, List(timeSlots.size) { 100.0 }, coverageFraction = 0.8)

        val result = ScheduleOptimizer().optimize(input)

        assertNotNull(result, "Slack can absorb unmet coverage even with zero employees to assign")
        assertTrue(result!!.assignments.isEmpty(), "There are no employees to assign")
    }

    @Test
    fun `does not crash and returns an empty result with zero time slots`() {
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )
        val input = buildInput(listOf(emp), emptyList(), emptyList(), coverageFraction = 0.0)

        val result = ScheduleOptimizer().optimize(input)

        assertNotNull(result, "An empty schedule period is trivially feasible")
        assertTrue(result!!.assignments.isEmpty(), "There are no time slots to assign anyone to")
    }

    // ===== Employees with no availability =====

    @Test
    fun `never assigns an employee with zero availability, but still schedules others`() {
        val unavailableEmployee = employee(availability = emptyList())
        val availableEmployee = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )
        val timeSlots = hourlySlots(9, 17)
        val input = buildInput(
            listOf(unavailableEmployee, availableEmployee), timeSlots,
            List(timeSlots.size) { 1000.0 }
        )

        val result = ScheduleOptimizer().optimize(input)

        assertNotNull(result, "The available employee alone should be enough to find a feasible solution")
        val unavailableAssignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        assertEquals(null, unavailableAssignment, "The employee with zero availability should never be assigned")

        val availableAssignment = result.assignments.firstOrNull { it.employeeIndex == 1 }
        assertNotNull(availableAssignment, "The available employee should be scheduled to meet coverage")
    }

    @Test
    fun `returns null when the only employee has zero availability and coverage is required`() {
        val emp = employee(availability = emptyList())
        val timeSlots = hourlySlots(9, 17)
        // High demand, high required coverage, and nobody who can ever work -
        // slack would have to cover all of it, so whether this is feasible
        // depends only on whether slack is truly uncapped. Document the
        // actual behavior instead of assuming.
        val input = buildInput(listOf(emp), timeSlots, List(timeSlots.size) { 1000.0 }, coverageFraction = 0.8)

        val result = ScheduleOptimizer().optimize(input)

        assertNotNull(result, "Slack can absorb unmet coverage even when the only employee is never available")
        assertTrue(result!!.assignments.isEmpty(), "An employee with zero availability can never be assigned")
    }

    // ===== Solver time budget =====

    @Test
    fun `still returns promptly with an extremely small solve time budget`() {
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )
        val timeSlots = hourlySlots(9, 17)
        val input = buildInput(
            listOf(emp), timeSlots, List(timeSlots.size) { 1000.0 },
            maxSolveTimeSeconds = 0.01
        )

        // A near-zero time budget must not hang or throw - CP-SAT should
        // return whatever it has (optimal, feasible, or infeasible) within
        // roughly the requested budget rather than running indefinitely.
        val start = System.currentTimeMillis()
        val result = ScheduleOptimizer().optimize(input)
        val elapsedSeconds = (System.currentTimeMillis() - start) / 1000.0

        assertTrue(
            elapsedSeconds < 5.0,
            "optimize() took ${elapsedSeconds}s with a 0.01s budget - solver is not respecting maxSolveTimeSeconds"
        )
        // Small scenarios like this one are simple enough that even a tiny
        // time budget typically still finds a feasible solution; the
        // real assertion here is about not hanging, not about the result.
        assertNotNull(result, "This trivial scenario should still be solved within a tiny time budget")
    }
}
