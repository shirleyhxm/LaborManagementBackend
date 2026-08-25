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
 * Verifies that ScheduleOptimizer's output never violates the hard
 * constraints it's supposed to enforce: an employee assigned outside their
 * availability, a shift shorter/longer than allowed, hours beyond a weekly
 * cap, overtime beyond its cap, or cost beyond budget would all be silent,
 * production-breaking bugs that "a solution exists" assertions can't catch.
 *
 * Each scenario is deliberately tight: the constraint under test is set
 * low enough, or demand high enough, that a correct solver is forced to
 * either hit the boundary or leave demand unmet (slack) rather than being
 * able to trivially satisfy the constraint by accident.
 */
class HardConstraintTest {
    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val scheduleDate = LocalDate.of(2024, 1, 1) // a Monday

    private fun contract(
        overtimeThreshold: Double = 40.0,
        maxHoursPerWeek: Double = 60.0
    ) = Contract(
        contractedHoursPerWeek = 40.0,
        maxHoursPerWeek = maxHoursPerWeek,
        maxHoursPerDay = 12.0,
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

    /** One-hour slots spanning [startHour, endHour) on scheduleDate. */
    private fun hourlySlots(startHour: Int, endHour: Int, date: LocalDate = scheduleDate): List<TimeSlot> {
        return (startHour until endHour).map { hour ->
            TimeSlot(date, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), 1.0)
        }
    }

    /** High demand per slot, so the solver is pushed to schedule as much as it's allowed to. */
    private fun highDemand(count: Int, perSlot: Double = 1000.0) = List(count) { perSlot }

    private fun buildInput(
        employees: List<Employee>,
        timeSlots: List<TimeSlot>,
        projectedSales: List<Double>,
        coverageFraction: Double = 0.8,
        laborBudget: Long = Long.MAX_VALUE,
        objective: OptimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST,
        workingHoursRules: WorkingHoursRules? = null,
        contractedHours: Map<UUID, EmployeeContractedHours> = emptyMap()
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
            objective = objective,
            workingHoursRules = workingHoursRules,
            contractedHours = contractedHours
        )
    }

    // ===== Availability =====

    @Test
    fun `never assigns an employee outside their declared availability`() {
        // Available only 9-12 (3 slots), but time slots span 9-17 and demand
        // covers the whole day - a broken availability constraint would let
        // the solver use this employee's high productivity for slots after 12.
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(12, 0))
            )
        )
        val timeSlots = hourlySlots(9, 17)
        val input = buildInput(listOf(emp), timeSlots, highDemand(timeSlots.size))

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result, "Should find a feasible solution")

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        assertNotNull(assignment, "Employee should be assigned within their available window")

        for (slotIndex in assignment!!.timeSlotIndices) {
            val slot = timeSlots[slotIndex]
            assertTrue(
                slot.startTime >= LocalTime.of(9, 0) && slot.endTime <= LocalTime.of(12, 0),
                "Assigned slot ${slot.startTime}-${slot.endTime} falls outside declared availability 09:00-12:00"
            )
        }
    }

    // ===== Minimum shift length =====

    @Test
    fun `never creates a continuous shift shorter than the minimum shift length`() {
        val minShiftLength = 4.0
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )
        val timeSlots = hourlySlots(9, 17) // 8 one-hour slots
        val rules = WorkingHoursRules(
            businessId = testBusinessId,
            maxHoursPerWeek = 60.0,
            maxOvertimeHours = 20.0,
            minRestBetweenShifts = 8.0,
            maxConsecutiveDays = 6,
            maxShiftLength = 12.0,
            minShiftLength = minShiftLength
        )
        // Demand only in a single slot (index 0): without a minimum shift
        // length constraint, a cost-minimizing solver would work just that
        // one hour, since the heavy slack penalty only applies where demand
        // is actually nonzero.
        val demand = listOf(1000.0) + List(timeSlots.size - 1) { 0.0 }
        val input = buildInput(listOf(emp), timeSlots, demand, coverageFraction = 0.8, workingHoursRules = rules)

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        assertNotNull(assignment, "Employee should be assigned to meet coverage")

        val shiftGroups = groupConsecutive(assignment!!.timeSlotIndices)
        for (group in shiftGroups) {
            val hours = group.size * 1.0 // 1-hour slots
            assertTrue(
                hours >= minShiftLength,
                "Shift of $hours hours (slots $group) is shorter than the minimum shift length of $minShiftLength hours"
            )
        }
    }

    // ===== Maximum shift length =====

    @Test
    fun `never creates a continuous shift longer than the maximum shift length`() {
        val maxShiftLength = 4.0
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )
        val timeSlots = hourlySlots(9, 17) // 8 one-hour slots
        val rules = WorkingHoursRules(
            businessId = testBusinessId,
            maxHoursPerWeek = 60.0,
            maxOvertimeHours = 20.0,
            minRestBetweenShifts = 8.0,
            maxConsecutiveDays = 6,
            maxShiftLength = maxShiftLength,
            minShiftLength = 0.0
        )
        // High demand across the whole day pushes the solver to want to work
        // this employee continuously all 8 hours if nothing stops it.
        val input = buildInput(listOf(emp), timeSlots, highDemand(timeSlots.size), workingHoursRules = rules)

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        assertNotNull(assignment, "Employee should be assigned to meet coverage")

        val shiftGroups = groupConsecutive(assignment!!.timeSlotIndices)
        for (group in shiftGroups) {
            val hours = group.size * 1.0
            assertTrue(
                hours <= maxShiftLength,
                "Shift of $hours hours (slots $group) exceeds the maximum shift length of $maxShiftLength hours"
            )
        }
    }

    // ===== Weekly hour cap =====

    @Test
    fun `never schedules an employee beyond the weekly hour cap`() {
        val maxHoursPerWeek = 20.0
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(0, 0), LocalTime.of(23, 59))
            ),
            contract = contract(overtimeThreshold = 100.0) // keep overtime out of this test
        )
        // 24 one-hour slots in a single day, but the employee's weekly cap
        // (20h) is well below that - only the cap should limit hours here.
        val timeSlots = (0 until 23).map { hour ->
            TimeSlot(scheduleDate, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), 1.0)
        }
        val rules = WorkingHoursRules(
            businessId = testBusinessId,
            maxHoursPerWeek = maxHoursPerWeek,
            maxOvertimeHours = 20.0,
            minRestBetweenShifts = 0.0,
            maxConsecutiveDays = 6,
            maxShiftLength = 24.0,
            minShiftLength = 0.0
        )
        val input = buildInput(listOf(emp), timeSlots, highDemand(timeSlots.size), workingHoursRules = rules)

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        val totalHours = assignment?.totalHours ?: 0L

        assertTrue(
            totalHours <= maxHoursPerWeek.toLong(),
            "Employee was scheduled for $totalHours hours, exceeding the weekly cap of $maxHoursPerWeek hours"
        )
    }

    // ===== Daily hour cap (Contract.maxHoursPerDay) =====

    @Test
    fun `never schedules an employee beyond their per-day hour cap`() {
        val maxHoursPerDay = 6.0
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(0, 0), LocalTime.of(23, 59))
            ),
            // Weekly cap and overtime threshold set loose so the daily cap is
            // the only binding constraint being tested here.
            contract = Contract(
                contractedHoursPerWeek = 40.0,
                maxHoursPerWeek = 60.0,
                maxHoursPerDay = maxHoursPerDay,
                overtimeThreshold = 100.0
            )
        )
        // 23 one-hour slots all on the same day, with demand high enough to
        // want every hour if nothing stops it.
        val timeSlots = (0 until 23).map { hour ->
            TimeSlot(scheduleDate, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), 1.0)
        }
        val input = buildInput(listOf(emp), timeSlots, highDemand(timeSlots.size))

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        val totalHours = assignment?.totalHours ?: 0L

        assertTrue(
            totalHours <= maxHoursPerDay.toLong(),
            "Employee was scheduled for $totalHours hours in a single day, exceeding the daily cap of $maxHoursPerDay hours"
        )
    }

    @Test
    fun `enforces the per-day hour cap independently on each day`() {
        val maxHoursPerDay = 4.0
        val monday = scheduleDate
        val tuesday = scheduleDate.plusDays(1)

        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(0, 0), LocalTime.of(23, 59)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(0, 0), LocalTime.of(23, 59))
            ),
            contract = Contract(
                contractedHoursPerWeek = 40.0,
                maxHoursPerWeek = 60.0,
                maxHoursPerDay = maxHoursPerDay,
                overtimeThreshold = 100.0
            )
        )
        // High demand across both days: a per-week-only cap would allow all
        // the hours to be front-loaded onto a single day, but a genuinely
        // per-day cap must limit each day independently.
        val mondaySlots = (0 until 10).map { hour -> TimeSlot(monday, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), 1.0) }
        val tuesdaySlots = (0 until 10).map { hour -> TimeSlot(tuesday, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), 1.0) }
        val timeSlots = mondaySlots + tuesdaySlots
        val input = buildInput(listOf(emp), timeSlots, highDemand(timeSlots.size))

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        assertNotNull(assignment, "Employee should be assigned to meet coverage")

        val hoursByDate = assignment!!.timeSlotIndices
            .map { timeSlots[it].date }
            .groupingBy { it }
            .eachCount()

        for ((date, hours) in hoursByDate) {
            assertTrue(
                hours <= maxHoursPerDay.toInt(),
                "Employee worked $hours hours on $date, exceeding the daily cap of $maxHoursPerDay hours"
            )
        }
    }

    // ===== Overtime cap =====

    @Test
    fun `never schedules overtime beyond the maximum overtime hours`() {
        val overtimeThreshold = 10.0
        val maxOvertimeHours = 4.0
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(0, 0), LocalTime.of(23, 59))
            ),
            contract = contract(overtimeThreshold = overtimeThreshold, maxHoursPerWeek = 60.0)
        )
        val timeSlots = (0 until 23).map { hour ->
            TimeSlot(scheduleDate, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), 1.0)
        }
        val rules = WorkingHoursRules(
            businessId = testBusinessId,
            maxHoursPerWeek = 60.0, // loose, so the overtime cap is the binding constraint
            maxOvertimeHours = maxOvertimeHours,
            minRestBetweenShifts = 0.0,
            maxConsecutiveDays = 6,
            maxShiftLength = 24.0,
            minShiftLength = 0.0
        )
        val input = buildInput(listOf(emp), timeSlots, highDemand(timeSlots.size), workingHoursRules = rules)

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        val totalHours = assignment?.totalHours ?: 0L
        val overtimeHours = (totalHours - overtimeThreshold.toLong()).coerceAtLeast(0L)

        assertTrue(
            overtimeHours <= maxOvertimeHours.toLong(),
            "Employee worked $overtimeHours overtime hours (total $totalHours, threshold $overtimeThreshold), exceeding the cap of $maxOvertimeHours"
        )
    }

    // ===== Labor budget =====

    @Test
    fun `never exceeds the labor budget`() {
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            ),
            normalPayRate = 20.0,
            overtimePayRate = 30.0
        )
        val timeSlots = hourlySlots(9, 17) // 8 hours available, would cost 8*20=160 if fully scheduled
        val laborBudget = 60L // enough for 3 hours at $20/hr, not all 8
        val input = buildInput(
            listOf(emp), timeSlots, highDemand(timeSlots.size),
            coverageFraction = 0.1, // low enough that slack can absorb what the budget can't afford
            laborBudget = laborBudget
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result, "Should find a feasible solution even if it can't fully meet coverage within budget")

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        val cost = (assignment?.totalHours ?: 0L) * emp.normalPayRate

        assertTrue(
            cost <= laborBudget,
            "Labor cost $cost exceeds the budget of $laborBudget"
        )
    }

    // ===== Contracted hours (min/max) =====

    @Test
    fun `never schedules an employee below their contracted minimum hours`() {
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )
        val timeSlots = hourlySlots(9, 17)
        val minHours = 3.0
        val contractedHours = mapOf(
            emp.id to EmployeeContractedHours(
                businessId = testBusinessId,
                employeeId = emp.id,
                minHours = minHours,
                contractedHours = 4.0,
                maxHours = 8.0,
                effectiveFrom = scheduleDate
            )
        )
        // Zero demand everywhere: without a minimum-hours floor, a
        // cost-minimizing solver has no reason to schedule this employee
        // at all, since there's no coverage to earn by working.
        val input = buildInput(
            listOf(emp), timeSlots, List(timeSlots.size) { 0.0 },
            coverageFraction = 0.8,
            contractedHours = contractedHours
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        val totalHours = assignment?.totalHours ?: 0L

        assertTrue(
            totalHours >= minHours.toLong(),
            "Employee worked $totalHours hours, below the contracted minimum of $minHours"
        )
    }

    @Test
    fun `never schedules an employee above their contracted maximum hours`() {
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(9, 0), LocalTime.of(17, 0))
            )
        )
        val timeSlots = hourlySlots(9, 17)
        val maxHours = 5.0
        val contractedHours = mapOf(
            emp.id to EmployeeContractedHours(
                businessId = testBusinessId,
                employeeId = emp.id,
                minHours = 0.0,
                contractedHours = 4.0,
                maxHours = maxHours,
                effectiveFrom = scheduleDate
            )
        )
        // High demand across every slot: without a maximum-hours ceiling, a
        // solver chasing coverage would work all 8 available hours.
        val input = buildInput(
            listOf(emp), timeSlots, highDemand(timeSlots.size),
            coverageFraction = 0.8,
            contractedHours = contractedHours
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        val totalHours = assignment?.totalHours ?: 0L

        assertTrue(
            totalHours <= maxHours.toLong(),
            "Employee worked $totalHours hours, above the contracted maximum of $maxHours"
        )
    }

    // ===== Minimum rest between shift days =====

    @Test
    fun `never starts a new shift within the minimum rest window of the previous one`() {
        val minRestHours = 11.0
        // Available 4pm-11:59pm on day 1 and 6am-11:59pm on day 2 - late finish
        // on day 1 followed by an early slot on day 2 would violate an 11-hour
        // rest requirement (e.g. finish 8pm day 1, next shift can't start
        // before 7am day 2).
        val day2 = scheduleDate.plusDays(1)
        val emp = employee(
            availability = listOf(
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null, LocalTime.of(16, 0), LocalTime.of(23, 59)),
                Availability(AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null, LocalTime.of(6, 0), LocalTime.of(23, 59))
            )
        )
        val timeSlots = hourlySlots(16, 23, scheduleDate) + hourlySlots(6, 23, day2)
        val rules = WorkingHoursRules(
            businessId = testBusinessId,
            maxHoursPerWeek = 80.0,
            maxOvertimeHours = 40.0,
            minRestBetweenShifts = minRestHours,
            maxConsecutiveDays = 6,
            maxShiftLength = 12.0,
            minShiftLength = 0.0
        )
        // High demand everywhere: without a rest constraint, a coverage-hungry
        // solver would happily work right up to day 1's close and resume at
        // day 2's open, an effective gap far shorter than 11 hours.
        val input = buildInput(listOf(emp), timeSlots, highDemand(timeSlots.size), workingHoursRules = rules)

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        assertNotNull(assignment, "Employee should be assigned to meet coverage")

        val workedSlots = assignment!!.timeSlotIndices.map { timeSlots[it] }.sortedBy { it.date.atTime(it.startTime) }
        for (i in 1 until workedSlots.size) {
            val prevEnd = workedSlots[i - 1].date.atTime(workedSlots[i - 1].endTime)
            val currStart = workedSlots[i].date.atTime(workedSlots[i].startTime)
            val gapHours = java.time.Duration.between(prevEnd, currStart).toMinutes() / 60.0
            assertTrue(
                gapHours <= 0.0 || gapHours >= minRestHours,
                "Gap of $gapHours hours between ${workedSlots[i - 1]} and ${workedSlots[i]} is shorter than the minimum rest of $minRestHours hours"
            )
        }
    }

    // ===== Maximum consecutive days =====

    @Test
    fun `never works more consecutive days than the maximum allowed`() {
        val maxConsecutiveDays = 3
        val dates = (0 until 6).map { scheduleDate.plusDays(it.toLong()) } // Mon-Sat
        val availability = dates.map { date ->
            Availability(AvailabilityType.WEEKLY_RECURRING, date.dayOfWeek, null, null, LocalTime.of(9, 0), LocalTime.of(12, 0))
        }
        val emp = employee(availability = availability)
        val timeSlots = dates.flatMap { hourlySlots(9, 12, it) }
        val rules = WorkingHoursRules(
            businessId = testBusinessId,
            maxHoursPerWeek = 80.0,
            maxOvertimeHours = 40.0,
            minRestBetweenShifts = 0.0,
            maxConsecutiveDays = maxConsecutiveDays,
            maxShiftLength = 12.0,
            minShiftLength = 0.0
        )
        // High demand every day: without a consecutive-days constraint, a
        // coverage-hungry solver would work all 6 available days straight.
        val input = buildInput(listOf(emp), timeSlots, highDemand(timeSlots.size), workingHoursRules = rules)

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val assignment = result!!.assignments.firstOrNull { it.employeeIndex == 0 }
        assertNotNull(assignment, "Employee should be assigned to meet coverage")

        val workedDates = assignment!!.timeSlotIndices.map { timeSlots[it].date }.toSortedSet().toList()
        var run = 0
        var maxRun = 0
        for (i in dates.indices) {
            if (dates[i] in workedDates) {
                run += 1
                maxRun = maxOf(maxRun, run)
            } else {
                run = 0
            }
        }
        assertTrue(
            maxRun <= maxConsecutiveDays,
            "Employee worked $maxRun consecutive days, above the maximum of $maxConsecutiveDays"
        )
    }

    /** Groups a sorted or unsorted list of hourly slot indices into consecutive runs. */
    private fun groupConsecutive(indices: List<Int>): List<List<Int>> {
        if (indices.isEmpty()) return emptyList()
        val sorted = indices.sorted()
        val groups = mutableListOf(mutableListOf(sorted[0]))
        for (i in 1 until sorted.size) {
            if (sorted[i] == sorted[i - 1] + 1) {
                groups.last().add(sorted[i])
            } else {
                groups.add(mutableListOf(sorted[i]))
            }
        }
        return groups
    }
}
