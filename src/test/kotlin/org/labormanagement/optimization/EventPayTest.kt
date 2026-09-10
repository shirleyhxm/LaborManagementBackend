package org.labormanagement.optimization

import org.junit.jupiter.api.Test
import org.labormanagement.model.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies what an event pays, and that the roster is the size the event asked for.
 *
 * Pay follows the *role someone fills*, not merely the groups they belong to: a person tagged
 * both Bar and FOH earns the bar's premium in the hours they are behind the bar and the front
 * of house's in the hours they are not. The solver already decides which role each person
 * fills in each slot, so these assertions read that decision back rather than re-deriving it.
 */
class EventPayTest {
    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val eventDate = LocalDate.of(2024, 1, 1) // a Monday

    private fun allDay() = listOf(
        Availability(
            dayOfWeek = DayOfWeek.MONDAY,
            startTime = LocalTime.of(0, 0),
            endTime = LocalTime.of(23, 59),
            availabilityType = AvailabilityType.WEEKLY_RECURRING
        )
    )

    private fun employee(
        groups: Set<String> = emptySet(),
        normalPayRate: Double = 20.0,
        overtimePayRate: Double = 30.0,
        overtimeThreshold: Double = 40.0
    ) = Employee(
        id = UUID.randomUUID(),
        businessId = testBusinessId,
        firstName = "Test",
        lastName = "Employee",
        dateOfBirth = LocalDate.of(1990, 1, 1),
        normalPayRate = normalPayRate,
        overtimePayRate = overtimePayRate,
        productivity = 100.0,
        contract = Contract(
            contractedHoursPerWeek = 40.0,
            maxHoursPerWeek = 60.0,
            maxHoursPerDay = 12.0,
            overtimeThreshold = overtimeThreshold
        ),
        availability = allDay(),
        groups = groups
    )

    private fun hourlySlots(startHour: Int, endHour: Int): List<TimeSlot> =
        (startHour until endHour).map { hour ->
            TimeSlot(eventDate, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), 1.0)
        }

    private fun buildInput(
        employees: List<Employee>,
        timeSlots: List<TimeSlot>,
        eventRequirements: List<EventSlotRequirement>,
        objective: OptimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST,
        projectedSales: List<Double> = List(timeSlots.size) { 0.0 },
        hoursCommittedElsewhere: Map<UUID, Double> = emptyMap()
    ) = OptimizationInput(
        employees = employees,
        timeSlots = timeSlots,
        projectedSales = projectedSales,
        availability = employees.map { emp ->
            timeSlots.map { slot ->
                emp.availability.any { it.isAvailableOn(slot.date, slot.startTime, slot.endTime) }
            }
        },
        productivity = employees.map { emp -> timeSlots.map { emp.productivity * it.durationHours } },
        objective = objective,
        eventRequirements = eventRequirements,
        hoursCommittedElsewhere = hoursCommittedElsewhere
    )

    private fun requirement(
        groupName: String,
        count: Int,
        employeeIndices: List<Int>,
        slotCount: Int,
        payOverride: EventPayRate? = null
    ) = EventSlotRequirement(
        groupName, count, employeeIndices, (0 until slotCount).toList(), payOverride
    )

    // ===== Pay follows the role =====

    @Test
    fun `an uplift is added to the employee's own rate`() {
        val bar = employee(groups = setOf("Bar"), normalPayRate = 19.0)
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = listOf(bar),
            timeSlots = slots,
            eventRequirements = listOf(
                requirement("Bar", 1, listOf(0), slots.size, EventPayRate.Uplift(2.0))
            )
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val shifts = OptimizationConverter.convertToShifts(result, input)
        assertTrue(shifts.isNotEmpty(), "the bartender should be scheduled")
        shifts.forEach { assertEquals(21.0, it.payRate, "19 base + 2 uplift") }
    }

    @Test
    fun `an absolute rate replaces the employee's own rate`() {
        // Deliberately below the base rate: an absolute rate is a replacement, not a floor,
        // and treating it as a premium would silently ignore a rate set lower on purpose.
        val bar = employee(groups = setOf("Bar"), normalPayRate = 30.0)
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = listOf(bar),
            timeSlots = slots,
            eventRequirements = listOf(
                requirement("Bar", 1, listOf(0), slots.size, EventPayRate.Absolute(25.0))
            )
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val shifts = OptimizationConverter.convertToShifts(result, input)
        assertTrue(shifts.isNotEmpty())
        shifts.forEach { assertEquals(25.0, it.payRate) }
    }

    @Test
    fun `no override leaves everyone on their usual rate`() {
        val bar = employee(groups = setOf("Bar"), normalPayRate = 18.0)
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = listOf(bar),
            timeSlots = slots,
            eventRequirements = listOf(requirement("Bar", 1, listOf(0), slots.size))
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val shifts = OptimizationConverter.convertToShifts(result, input)
        assertTrue(shifts.isNotEmpty())
        shifts.forEach { assertEquals(18.0, it.payRate) }
    }

    @Test
    fun `someone in two groups is paid for the role they actually fill`() {
        // Two people, both Bar and FOH, and one place in each group. Whoever is behind the bar
        // earns the bar's premium and the other earns front of house's - so the two shifts
        // must carry different rates, rather than both taking the same group's.
        val dual = List(2) { employee(groups = setOf("Bar", "FOH"), normalPayRate = 20.0) }
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = dual,
            timeSlots = slots,
            eventRequirements = listOf(
                requirement("Bar", 1, listOf(0, 1), slots.size, EventPayRate.Uplift(5.0)),
                requirement("FOH", 1, listOf(0, 1), slots.size, EventPayRate.Uplift(1.0))
            )
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val shifts = OptimizationConverter.convertToShifts(result, input)
        val rates = shifts.map { it.payRate }.toSet()
        assertEquals(
            setOf(25.0, 21.0),
            rates,
            "one shift at the bar premium and one at front of house, got $rates"
        )
    }

    @Test
    fun `the premium is what decides between two roles of equal base cost`() {
        // One person who could fill either of two roles, both needing somebody, and only one
        // of them can be filled. Base pay is identical, so the ordinary wage terms cannot
        // separate the choices - the *premium* is the entire difference in cost, and if it is
        // not charged in the objective the solver has no reason to prefer either.
        //
        // Deliberately not two people on different base rates: the wage terms would pick the
        // cheaper one on their own, and the test would pass whether or not the premium was
        // ever costed.
        //
        // The cheap role is listed *first* on purpose. Left to itself the solver settles on
        // the later requirement, so an ordering where the cheap role is also the default one
        // passes whether or not the premium is costed at all - which is exactly how this test
        // read before mutation testing caught it.
        val versatile = employee(groups = setOf("Bar", "FOH"), normalPayRate = 20.0)
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = listOf(versatile),
            timeSlots = slots,
            eventRequirements = listOf(
                requirement("Bar", 1, listOf(0), slots.size, EventPayRate.Uplift(1.0)),
                requirement("FOH", 1, listOf(0), slots.size, EventPayRate.Uplift(50.0))
            )
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val shifts = OptimizationConverter.convertToShifts(result, input)
        assertTrue(shifts.isNotEmpty())
        // Minimising cost, so the cheap role is the one to fill: 20 + 1, not 20 + 50.
        shifts.forEach {
            assertEquals(21.0, it.payRate, "should fill the cheaper role when premiums are compared")
        }
    }

    // ===== Overtime =====

    @Test
    fun `overtime is paid on the uplifted rate`() {
        // Already at the threshold, so every hour of the event is overtime. The uplift lands
        // on the base rate before the multiplier, so the overtime rate must move with it:
        // base 20 with a 30 overtime rate is a 1.5x multiplier, and 20+10 uplifted should
        // bill overtime at 45, not at the untouched 30.
        val bar = employee(
            groups = setOf("Bar"),
            normalPayRate = 20.0,
            overtimePayRate = 30.0,
            overtimeThreshold = 40.0
        )
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = listOf(bar),
            timeSlots = slots,
            eventRequirements = listOf(
                requirement("Bar", 1, listOf(0), slots.size, EventPayRate.Uplift(10.0))
            ),
            hoursCommittedElsewhere = mapOf(bar.id to 40.0)
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        val shifts = OptimizationConverter.convertToShifts(result, input)
        assertTrue(shifts.isNotEmpty(), "should still be scheduled, in overtime")
        shifts.forEach {
            assertTrue(it.isOvertime, "past the threshold, so these are overtime hours")
            assertEquals(45.0, it.payRate, "(20 + 10) at the same 1.5x multiplier")
        }
    }

    // ===== The headcount is a cap as well as a floor =====

    @Test
    fun `does not staff beyond the requested headcount`() {
        // Four bartenders available, one asked for, and revenue that would justify staffing
        // all of them. The requirement is what decides the roster - otherwise "1 x Bar" reads
        // as one bartender plus however many more the forecast talks the solver into.
        val bar = List(4) { employee(groups = setOf("Bar")) }
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = bar,
            timeSlots = slots,
            eventRequirements = listOf(requirement("Bar", 1, listOf(0, 1, 2, 3), slots.size)),
            projectedSales = List(slots.size) { 5000.0 }
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        for (t in slots.indices) {
            val working = result.assignments.count { t in it.timeSlotIndices }
            assertEquals(1, working, "slot $t should have exactly the one bartender asked for")
        }
    }

    @Test
    fun `nobody outside the required groups is rostered`() {
        // A kitchen porter no requirement mentions, and demand that would happily use them.
        // An event staffs the roles it asked for and nothing else.
        val employees = listOf(
            employee(groups = setOf("Bar")),
            employee(groups = setOf("Kitchen"))
        )
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = employees,
            timeSlots = slots,
            eventRequirements = listOf(requirement("Bar", 1, listOf(0), slots.size)),
            projectedSales = List(slots.size) { 5000.0 }
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        assertTrue(
            result.assignments.none { it.employeeIndex == 1 },
            "the kitchen employee is in no required group and should not be scheduled"
        )
    }

    @Test
    fun `the cap never makes the model infeasible`() {
        // Asking for more than exist still has to produce a schedule - the cap only ever
        // forbids assignments, so it cannot be the thing that leaves no solution.
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = listOf(employee(groups = setOf("Bar"))),
            timeSlots = slots,
            eventRequirements = listOf(requirement("Bar", 5, listOf(0), slots.size))
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result, "an unmeetable requirement must not make the model infeasible")
        result.eventShortfalls.forEach { assertEquals(4, it.shortfall) }
    }

    // ===== Regular schedules are untouched =====

    @Test
    fun `an ordinary schedule reports no event pay rates`() {
        val slots = hourlySlots(9, 17)
        val input = OptimizationInput(
            employees = listOf(employee(groups = setOf("Bar"))),
            timeSlots = slots,
            projectedSales = List(slots.size) { 500.0 },
            availability = listOf(slots.map { true }),
            productivity = listOf(slots.map { 100.0 }),
            objective = OptimizationObjective.MINIMIZE_LABOR_COST
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)
        assertTrue(result.eventPayRates.isEmpty())

        val shifts = OptimizationConverter.convertToShifts(result, input)
        shifts.forEach { assertEquals(20.0, it.payRate, "contract rate, untouched") }
    }
}
