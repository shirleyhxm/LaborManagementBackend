package org.labormanagement.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.labormanagement.database.DatabaseFactory
import org.labormanagement.model.*
import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.SalesForecastRepository
import org.labormanagement.repository.ScheduleRepository
import org.labormanagement.repository.SpecialEventRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Generating the schedule an event produces.
 *
 * The event definition and the schedule are separate things: the definition is what a
 * manager edits and re-generates from, the schedule is derived. Most of what matters here is
 * that the parts an event overrides - its hours, its forecast, its rules, its pool - actually
 * reach the solver, and that the parts it may not override survive untouched.
 */
class EventSchedulerTest {

    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000009")
    private lateinit var eventScheduler: EventScheduler
    private lateinit var eventRepository: SpecialEventRepository
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var employeeRepository: EmployeeRepository
    private lateinit var businessRepository: BusinessRepository

    companion object {
        @JvmStatic
        @BeforeAll
        fun initDatabase() {
            DatabaseFactory.init(
                jdbcUrl = System.getenv("TEST_DATABASE_URL")
                    ?: "jdbc:postgresql://localhost:5432/labormanagement_test",
                user = System.getenv("TEST_DATABASE_USER") ?: "shirleyhe",
                password = System.getenv("TEST_DATABASE_PASSWORD") ?: ""
            )
        }
    }

    /** A Saturday, so the event sits well inside its week. */
    private val eventDate = LocalDate.of(2026, 9, 12)

    @BeforeEach
    fun setup() {
        DatabaseFactory.resetDatabase()
        businessRepository = BusinessRepository()
        businessRepository.create(
            Business(
                id = testBusinessId,
                name = "Event Scheduling Business",
                ownerId = "test-owner",
                // Ordinary daytime hours, which the event's own window has to override.
                settings = BusinessSettings(
                    defaultOpenTime = LocalTime.of(9, 0),
                    defaultCloseTime = LocalTime.of(17, 0)
                )
            )
        )
        employeeRepository = EmployeeRepository()
        eventRepository = SpecialEventRepository()
        scheduleRepository = ScheduleRepository()
        eventScheduler = EventScheduler()
    }

    private fun employee(name: String, from: String = "17:00", to: String = "04:00"): Employee {
        val e = Employee(
            businessId = testBusinessId,
            firstName = name,
            lastName = "Test",
            dateOfBirth = LocalDate.of(1990, 1, 1),
            normalPayRate = 15.0,
            overtimePayRate = 22.5,
            productivity = 100.0,
            contract = Contract(
                contractedHoursPerWeek = 40.0,
                maxHoursPerWeek = 60.0,
                maxHoursPerDay = 12.0,
                overtimeThreshold = 40.0
            ),
            availability = DayOfWeek.entries.map {
                Availability(
                    availabilityType = AvailabilityType.WEEKLY_RECURRING,
                    dayOfWeek = it,
                    startTime = LocalTime.parse(from),
                    endTime = LocalTime.parse(to)
                )
            }
        )
        employeeRepository.create(e)
        return e
    }

    /** The business's ordinary daytime trade, which an event's own figures should replace. */
    private fun seedDaytimeForecast() {
        val hours = (9..16).associate { LocalTime.of(it, 0) to 300.0 }
        SalesForecastRepository().updateForBusiness(
            businessId = testBusinessId,
            weeklyPattern = DayOfWeek.entries.associateWith { hours },
            updatedBy = "test"
        )
    }

    private fun createEvent(
        name: String = "NYE Party",
        start: LocalTime = LocalTime.of(20, 0),
        end: LocalTime = LocalTime.of(23, 0),
        expectedRevenue: Map<LocalTime, Double>? = mapOf(
            LocalTime.of(20, 0) to 900.0,
            LocalTime.of(21, 0) to 900.0,
            LocalTime.of(22, 0) to 900.0
        ),
        employeeIds: List<UUID> = emptyList(),
        ruleOverrides: EventRuleOverrides? = null,
        objective: OptimizationObjective = OptimizationObjective.MAXIMIZE_SALES
    ): SpecialEvent = eventRepository.create(
        SpecialEvent(
            businessId = testBusinessId,
            name = name,
            date = eventDate,
            startTime = start,
            endTime = end,
            expectedRevenue = expectedRevenue,
            employeeIds = employeeIds,
            ruleOverrides = ruleOverrides,
            objective = objective,
            createdBy = "test"
        )
    )

    // ===== The schedule an event produces =====

    @Test
    fun `generating an event produces an EVENT schedule`() {
        employee("Alice")
        val event = createEvent()

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)

        assertNotNull(schedule)
        assertEquals(ScheduleKind.EVENT, schedule!!.kind)
        assertEquals("NYE Party", schedule.name)
        assertTrue(schedule.shifts.isNotEmpty(), "the event produced no shifts")
    }

    @Test
    fun `an unknown event reports itself rather than generating`() {
        assertNull(eventScheduler.generateForEvent(testBusinessId, UUID.randomUUID()))
    }

    @Test
    fun `the event is linked to the schedule it produced`() {
        employee("Alice")
        val event = createEvent()

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!

        assertEquals(schedule.id, eventRepository.findById(testBusinessId, event.id)!!.scheduleId)
    }

    // ===== What an event overrides =====

    @Test
    fun `the event's own hours are used, not the business's`() {
        // The business closes at 17:00; the event runs 20:00-23:00 regardless.
        employee("Alice")
        val event = createEvent()

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!

        assertTrue(schedule.shifts.isNotEmpty())
        schedule.shifts.forEach { shift ->
            assertTrue(
                shift.startTime >= LocalTime.of(20, 0) && shift.endTime <= LocalTime.of(23, 0),
                "scheduled outside the event window: ${shift.startTime}-${shift.endTime}"
            )
        }
    }

    @Test
    fun `an event running past midnight is scheduled across the boundary`() {
        employee("Alice")
        val event = createEvent(
            start = LocalTime.of(22, 0),
            end = LocalTime.of(2, 0),
            expectedRevenue = mapOf(
                LocalTime.of(22, 0) to 900.0,
                LocalTime.of(23, 0) to 900.0,
                LocalTime.of(0, 0) to 900.0,
                LocalTime.of(1, 0) to 900.0
            )
        )

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!
        val hours = schedule.shifts.sumOf { it.durationHours }

        assertTrue(schedule.shifts.isNotEmpty(), "an overnight event produced no shifts")
        assertTrue(hours > 0.0 && hours <= 4.0, "credited $hours hours for a 4 hour event")
    }

    @Test
    fun `availability declared for the night's own day covers its small hours`() {
        // The real-world shape, and the one an every-day availability fixture hides: someone
        // says they work Saturday nights, and the event runs Saturday 21:00 to Sunday 02:00.
        // Matched on the calendar date alone the 00:00-02:00 slots fall on Sunday, so the
        // last two hours of every late night were unstaffable by exactly the people who had
        // declared themselves free for them - the schedule simply stopped at midnight.
        val saturdayOnly = Employee(
            businessId = testBusinessId,
            firstName = "Sat",
            lastName = "Only",
            dateOfBirth = LocalDate.of(1990, 1, 1),
            normalPayRate = 15.0,
            overtimePayRate = 22.5,
            productivity = 100.0,
            contract = Contract(
                contractedHoursPerWeek = 40.0,
                maxHoursPerWeek = 60.0,
                maxHoursPerDay = 12.0,
                overtimeThreshold = 40.0
            ),
            availability = listOf(
                Availability(
                    availabilityType = AvailabilityType.WEEKLY_RECURRING,
                    // eventDate is a Saturday; the tail of the night lands on Sunday.
                    dayOfWeek = DayOfWeek.SATURDAY,
                    startTime = LocalTime.of(18, 0),
                    endTime = LocalTime.of(3, 0)
                )
            )
        )
        employeeRepository.create(saturdayOnly)

        val event = createEvent(
            start = LocalTime.of(21, 0),
            end = LocalTime.of(2, 0),
            expectedRevenue = listOf(21, 22, 23, 0, 1).associate { LocalTime.of(it, 0) to 900.0 }
        )

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!
        val covered = schedule.shifts.flatMap { shift ->
            (0 until shift.durationHours.toInt()).map { shift.startTime.plusHours(it.toLong()).hour }
        }.toSet()

        assertTrue(schedule.shifts.isNotEmpty(), "no shifts were generated at all")
        assertTrue(
            0 in covered || 1 in covered,
            "the hours after midnight went unstaffed: covered=${covered.sorted()}"
        )
    }

    @Test
    fun `the event's forecast replaces the business's for that day`() {
        // Asserted on the sales the schedule was built against rather than on whether shifts
        // exist: under MAXIMIZE_SALES with spare labour the window gets staffed either way,
        // so "some shifts appeared" would pass even with the override disabled entirely.
        //
        // The business trades 09:00-16:00 at 300/hour, outside the event window; the event
        // expects 900/hour across its three hours. Which figure the schedule reports is what
        // says whose forecast actually reached the solver.
        seedDaytimeForecast()
        employee("Alice")
        val event = createEvent()

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!

        // Staffing requirements carry the demand each slot was judged against, which is the
        // forecast itself rather than anything derived from the shifts that came out.
        val eventWindow = schedule.staffingRequirements.filter {
            it.startTime >= LocalTime.of(20, 0) && it.endTime <= LocalTime.of(23, 0)
        }
        assertTrue(eventWindow.isNotEmpty(), "no staffing requirements inside the event window")
        assertTrue(
            eventWindow.any { it.expectedSales > 0.0 },
            "the event window drew no demand - its expected revenue never reached the solver. " +
                "Requirements: ${eventWindow.map { "${it.startTime}=${it.expectedSales}" }}"
        )
    }

    @Test
    fun `an event with no revenue figures falls back to the business forecast`() {
        // A manager who leaves the takings blank has not asked for a day with no demand.
        seedDaytimeForecast()
        employee("Alice", from = "08:00", to = "18:00")
        val event = createEvent(
            start = LocalTime.of(10, 0),
            end = LocalTime.of(14, 0),
            expectedRevenue = null
        )

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!

        assertTrue(
            schedule.staffingRequirements.any { it.expectedSales > 0.0 },
            "the business forecast was not used for an event carrying none of its own"
        )
    }

    @Test
    fun `only the event's own employees are considered`() {
        val alice = employee("Alice")
        employee("Bob")
        val event = createEvent(employeeIds = listOf(alice.id))

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!

        assertTrue(schedule.shifts.isNotEmpty())
        assertTrue(
            schedule.shifts.all { it.employeeId == alice.id },
            "someone outside the event's pool was scheduled"
        )
    }

    @Test
    fun `an empty pool means everyone is a candidate`() {
        // Not "nobody may work" - a manager who has not narrowed the pool means the roster.
        employee("Alice")
        employee("Bob")
        val event = createEvent(employeeIds = emptyList())

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!

        assertTrue(schedule.shifts.isNotEmpty())
        assertEquals(2, schedule.employeeIds.size, "the pool did not fall back to the roster")
    }

    @Test
    fun `a minimum shift length override reaches the solver`() {
        employee("Alice")
        val event = createEvent(ruleOverrides = EventRuleOverrides(minShiftLength = 3.0))

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!

        assertTrue(schedule.shifts.isNotEmpty())
        // Shifts are stored split at the overtime boundary, so compare the whole block.
        val blockHours = schedule.shifts.sumOf { it.durationHours }
        assertTrue(blockHours >= 3.0, "block of $blockHours hours is under the 3 hour minimum")
    }

    @Test
    fun `an event budget is used as given rather than pro-rated`() {
        // A weekly business budget divided down to a three-hour event would land far below
        // what staffing it costs, and the solver would refuse to fill it.
        employee("Alice")
        val event = createEvent(ruleOverrides = EventRuleOverrides(laborCostBudget = 500.0))

        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!

        assertEquals(500.0, schedule.laborCostBudget, 0.001)
        assertTrue(schedule.metrics.totalLaborCost <= 500.0)
    }

    // ===== Re-generating =====

    @Test
    fun `re-generating replaces the previous schedule`() {
        // The definition is the lasting thing; generating again should not accumulate drafts,
        // and the superseded schedule must not keep counting against everyone's hours.
        employee("Alice")
        val event = createEvent()

        val first = eventScheduler.generateForEvent(testBusinessId, event.id)!!
        val second = eventScheduler.generateForEvent(testBusinessId, event.id)!!

        assertNotEquals(first.id, second.id)
        assertNull(scheduleRepository.findById(testBusinessId, first.id), "the old schedule survived")
        assertEquals(second.id, eventRepository.findById(testBusinessId, event.id)!!.scheduleId)
    }

    @Test
    fun `deleting an event takes its schedule with it`() {
        // The schedule exists only to serve this event. Left behind it would be unreachable,
        // since event schedules are found through their definitions - while its shifts kept
        // counting against everyone's weekly hours, making staff unavailable for a party
        // that is no longer happening.
        employee("Alice")
        val event = createEvent()
        val schedule = eventScheduler.generateForEvent(testBusinessId, event.id)!!

        assertTrue(eventRepository.delete(testBusinessId, event.id))

        assertNull(
            scheduleRepository.findById(testBusinessId, schedule.id),
            "the event's schedule outlived the event"
        )
    }

    @Test
    fun `an event schedule does not replace the week's roster`() {
        // They share a business and a week, and one must never delete the other.
        employee("Alice")
        val roster = scheduleRepository.save(
            Schedule(
                businessId = testBusinessId,
                name = "Week",
                kind = ScheduleKind.REGULAR,
                schedulePeriod = SchedulePeriod(eventDate, eventDate, emptyMap()),
                shifts = emptyList(),
                metrics = SchedulingMetrics(0.0, 0.0, 0.0, emptyMap()),
                employeeIds = emptyList(),
                laborCostBudget = 1000.0,
                optimizationObjective = OptimizationObjective.BALANCED,
                createdBy = "test",
                lastModifiedBy = "test"
            )
        )
        val event = createEvent()

        eventScheduler.generateForEvent(testBusinessId, event.id)

        assertNotNull(scheduleRepository.findById(testBusinessId, roster.id), "the roster was deleted")
    }
}
