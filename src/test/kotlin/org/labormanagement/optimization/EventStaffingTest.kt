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
 * Verifies the group-aware staffing requirement an event carries.
 *
 * The requirement is deliberately *soft*: an event asking for more bartenders than exist must
 * still produce a schedule, because a manager can work from a thin rota and cannot work from
 * an error message. That makes the failure mode silence - a requirement that quietly binds
 * nothing, or a shortfall the solver pays for and then discards - so these tests assert on the
 * reported shortfall rather than only on "a solution exists".
 */
class EventStaffingTest {
    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val eventDate = LocalDate.of(2024, 1, 1) // a Monday

    private fun contract() = Contract(
        contractedHoursPerWeek = 40.0,
        maxHoursPerWeek = 60.0,
        maxHoursPerDay = 12.0,
        overtimeThreshold = 40.0
    )

    /** Available all day on the event date, so availability never confounds a staffing result. */
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
        availability: List<Availability> = allDay(),
        normalPayRate: Double = 20.0
    ) = Employee(
        id = UUID.randomUUID(),
        businessId = testBusinessId,
        firstName = "Test",
        lastName = "Employee",
        dateOfBirth = LocalDate.of(1990, 1, 1),
        normalPayRate = normalPayRate,
        overtimePayRate = normalPayRate * 1.5,
        productivity = 100.0,
        contract = contract(),
        availability = availability,
        groups = groups
    )

    /**
     * One-hour slots over [startHour, endHour). A slot ending at midnight is written as
     * 23:00-00:00, since LocalTime has no hour 24 - the same convention the converter uses.
     */
    private fun hourlySlots(startHour: Int, endHour: Int): List<TimeSlot> =
        (startHour until endHour).map { hour ->
            TimeSlot(
                eventDate,
                LocalTime.of(hour, 0),
                LocalTime.of((hour + 1) % 24, 0),
                1.0
            )
        }

    private fun buildInput(
        employees: List<Employee>,
        timeSlots: List<TimeSlot>,
        eventRequirements: List<EventSlotRequirement> = emptyList(),
        objective: OptimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST,
        // No demand by default: an event's staffing must come from its requirements, not from
        // a forecast. Zero here means anyone scheduled was put there by the requirement alone.
        projectedSales: List<Double> = List(timeSlots.size) { 0.0 }
    ): OptimizationInput {
        val availability = employees.map { emp ->
            timeSlots.map { slot ->
                emp.availability.any { it.isAvailableOn(slot.date, slot.startTime, slot.endTime) }
            }
        }
        val productivity = employees.map { emp ->
            timeSlots.map { emp.productivity * it.durationHours }
        }
        return OptimizationInput(
            employees = employees,
            timeSlots = timeSlots,
            projectedSales = projectedSales,
            availability = availability,
            productivity = productivity,
            objective = objective,
            eventRequirements = eventRequirements
        )
    }

    /** Every slot, which is what an event schedule always spans. */
    private fun requirement(
        groupName: String,
        count: Int,
        employeeIndices: List<Int>,
        slotCount: Int
    ) = EventSlotRequirement(groupName, count, employeeIndices, (0 until slotCount).toList())

    /** How many of [indices] are working slot [t]. */
    private fun assignedIn(result: OptimizationResult, indices: List<Int>, t: Int): Int =
        result.assignments.count { it.employeeIndex in indices && t in it.timeSlotIndices }

    // ===== The requirement actually binds =====

    @Test
    fun `staffs the requested headcount from the requested group`() {
        // Two bartenders wanted, three available, and no sales demand at all - so the only
        // reason to schedule anyone is the requirement itself.
        val bar = List(3) { employee(groups = setOf("Bar")) }
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = bar,
            timeSlots = slots,
            eventRequirements = listOf(requirement("Bar", 2, listOf(0, 1, 2), slots.size))
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        assertTrue(result.eventShortfalls.isEmpty(), "requirement was satisfiable, so nothing should be short")
        for (t in slots.indices) {
            assertEquals(2, assignedIn(result, listOf(0, 1, 2), t), "slot $t should have exactly the 2 required")
        }
    }

    @Test
    fun `only counts people carrying the group tag`() {
        // One bartender among three people. The requirement asks for two, and the other two
        // are ineligible - so this is short by one however many bodies are available.
        val employees = listOf(
            employee(groups = setOf("Bar")),
            employee(groups = setOf("Kitchen")),
            employee(groups = setOf("FOH"))
        )
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = employees,
            timeSlots = slots,
            eventRequirements = listOf(requirement("Bar", 2, listOf(0), slots.size))
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        assertEquals(slots.size, result.eventShortfalls.size, "every slot is one bartender short")
        result.eventShortfalls.forEach {
            assertEquals("Bar", it.groupName)
            assertEquals(1, it.shortfall)
            assertEquals(2, it.required)
            assertEquals(1, it.assigned)
        }
    }

    // ===== Softness: never infeasible =====

    @Test
    fun `an impossible requirement still returns a schedule`() {
        // Five bartenders wanted, one exists. This must not make the model infeasible: the
        // greedy fallback would take over and quietly drop every event rule with it.
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

    @Test
    fun `a group with no members reports the whole requirement as short`() {
        // A group everyone has left. Nobody can be assigned, so the shortfall is the entire
        // count - which is the state most likely to be mistaken for "the solver ignored it".
        val slots = hourlySlots(19, 21)
        val input = buildInput(
            employees = listOf(employee(groups = setOf("Kitchen"))),
            timeSlots = slots,
            eventRequirements = listOf(requirement("Security", 2, emptyList(), slots.size))
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        assertEquals(slots.size, result.eventShortfalls.size)
        result.eventShortfalls.forEach {
            assertEquals(2, it.shortfall)
            assertEquals(0, it.assigned)
        }
    }

    @Test
    fun `availability that rules the group out is reported as a shortfall, not an error`() {
        // The one bartender is unavailable for the whole event window. Availability is hard;
        // the requirement is soft - so this resolves as a reported gap rather than no schedule.
        val unavailable = employee(
            groups = setOf("Bar"),
            availability = listOf(
                Availability(
                    dayOfWeek = DayOfWeek.MONDAY,
                    startTime = LocalTime.of(9, 0),
                    endTime = LocalTime.of(12, 0),
                    availabilityType = AvailabilityType.WEEKLY_RECURRING
                )
            )
        )
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = listOf(unavailable),
            timeSlots = slots,
            eventRequirements = listOf(requirement("Bar", 1, listOf(0), slots.size))
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        assertEquals(slots.size, result.eventShortfalls.size)
        result.eventShortfalls.forEach { assertEquals(1, it.shortfall) }
    }

    // ===== Several groups at once =====

    @Test
    fun `satisfies several group requirements independently`() {
        // 2 Bar, 1 Kitchen. Bar is satisfiable, Kitchen is one short - and the two must be
        // reported separately rather than as one aggregate gap.
        val employees = listOf(
            employee(groups = setOf("Bar")),
            employee(groups = setOf("Bar")),
            employee(groups = setOf("Kitchen"))
        )
        val slots = hourlySlots(19, 21)
        val input = buildInput(
            employees = employees,
            timeSlots = slots,
            eventRequirements = listOf(
                requirement("Bar", 2, listOf(0, 1), slots.size),
                requirement("Kitchen", 2, listOf(2), slots.size)
            )
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        assertTrue(result.eventShortfalls.none { it.groupName == "Bar" }, "Bar was satisfiable")
        val kitchen = result.eventShortfalls.filter { it.groupName == "Kitchen" }
        assertEquals(slots.size, kitchen.size)
        kitchen.forEach { assertEquals(1, it.shortfall) }
    }

    @Test
    fun `someone in two groups can satisfy only one requirement at a time`() {
        // One person tagged both Bar and FOH, with both groups needing someone. They cannot be
        // in two places in the same hour, so exactly one requirement goes short per slot.
        val dual = employee(groups = setOf("Bar", "FOH"))
        val slots = hourlySlots(19, 21)
        val input = buildInput(
            employees = listOf(dual),
            timeSlots = slots,
            eventRequirements = listOf(
                requirement("Bar", 1, listOf(0), slots.size),
                requirement("FOH", 1, listOf(0), slots.size)
            )
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        for (t in slots.indices) {
            val shortInSlot = result.eventShortfalls.filter { it.timeSlotIndex == t }
            assertEquals(1, shortInSlot.size, "exactly one of the two groups is short in slot $t")
        }
    }

    @Test
    fun `multi-skilled staff do not count twice toward the headcount`() {
        // Everyone tagged both Bar and FOH, asked for 2 of each. Counting a person toward both
        // groups at once would satisfy all four places with two people and report nothing
        // short - so a manager staffing "2 Bar, 2 FOH" would be handed half a team and no
        // warning. Multi-group tagging is the normal way these records are set up, which is
        // what makes this the ordinary case rather than a corner one.
        val dual = List(3) { employee(groups = setOf("Bar", "FOH")) }
        val slots = hourlySlots(19, 21)
        val input = buildInput(
            employees = dual,
            timeSlots = slots,
            eventRequirements = listOf(
                requirement("Bar", 2, listOf(0, 1, 2), slots.size),
                requirement("FOH", 2, listOf(0, 1, 2), slots.size)
            )
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        // Four places, three people: exactly one place goes unfilled in each slot.
        for (t in slots.indices) {
            val shortHere = result.eventShortfalls.filter { it.timeSlotIndex == t }.sumOf { it.shortfall }
            assertEquals(1, shortHere, "slot $t: 4 places and 3 people means 1 short")
            assertEquals(3, assignedIn(result, listOf(0, 1, 2), t), "all three should be working")
        }
    }

    // ===== Objectives =====

    @Test
    fun `requirement is honoured under MAXIMIZE_SALES`() {
        // This objective ignores coverage slack entirely, so a penalty left out of its
        // objective would cost nothing and the requirement would bind nothing.
        val employees = listOf(
            employee(groups = setOf("Bar")),
            employee(groups = setOf("Bar"))
        )
        val slots = hourlySlots(19, 21)
        val input = buildInput(
            employees = employees,
            timeSlots = slots,
            eventRequirements = listOf(requirement("Bar", 2, listOf(0, 1), slots.size)),
            objective = OptimizationObjective.MAXIMIZE_SALES
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)
        assertTrue(result.eventShortfalls.isEmpty())
        for (t in slots.indices) assertEquals(2, assignedIn(result, listOf(0, 1), t))
    }

    @Test
    fun `requirement is honoured under MAXIMIZE_FAIRNESS`() {
        val employees = listOf(
            employee(groups = setOf("Bar")),
            employee(groups = setOf("Bar"))
        )
        val slots = hourlySlots(19, 21)
        val input = buildInput(
            employees = employees,
            timeSlots = slots,
            eventRequirements = listOf(requirement("Bar", 2, listOf(0, 1), slots.size)),
            objective = OptimizationObjective.MAXIMIZE_FAIRNESS
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)
        assertTrue(result.eventShortfalls.isEmpty())
    }

    @Test
    fun `a costly requirement is still met when cost is being minimised`() {
        // Under MINIMIZE_LABOR_COST the cheapest schedule is an empty one, since there is no
        // demand to cover. The requirement has to outweigh wages or it does nothing at all.
        val expensive = List(2) { employee(groups = setOf("Bar"), normalPayRate = 500.0) }
        val slots = hourlySlots(19, 22)
        val input = buildInput(
            employees = expensive,
            timeSlots = slots,
            eventRequirements = listOf(requirement("Bar", 2, listOf(0, 1), slots.size))
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)

        assertTrue(result.eventShortfalls.isEmpty(), "the requirement must outrank the wage saving")
        for (t in slots.indices) assertEquals(2, assignedIn(result, listOf(0, 1), t))
    }

    // ===== Regular schedules are untouched =====

    @Test
    fun `a schedule with no requirements reports no shortfalls`() {
        // The regression that matters for every existing schedule: no requirements means no
        // new variables, no new penalty, and nothing added to the result.
        val slots = hourlySlots(9, 17)
        val input = buildInput(
            employees = listOf(employee(groups = setOf("Bar"))),
            timeSlots = slots,
            projectedSales = List(slots.size) { 500.0 }
        )

        val result = ScheduleOptimizer().optimize(input)
        assertNotNull(result)
        assertTrue(result.eventShortfalls.isEmpty())
    }
}
