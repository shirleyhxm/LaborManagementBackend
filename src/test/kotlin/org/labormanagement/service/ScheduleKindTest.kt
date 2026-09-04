package org.labormanagement.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.labormanagement.database.DatabaseFactory
import org.labormanagement.model.*
import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.ScheduleRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Keeping regular schedules and special events apart, and keeping their hours together.
 *
 * These two things pull in opposite directions, which is the whole reason this file
 * exists. The schedules have to be separate populations - one roster per week, events
 * beside it - while the hours they consume have to be pooled, because an employee's
 * weekly cap belongs to the person and not to whichever schedule happens to spend it.
 */
class ScheduleKindTest {

    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val otherBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000004")
    private lateinit var employeeRepository: EmployeeRepository
    private lateinit var scheduleRepository: ScheduleRepository
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

    private val monday = LocalDate.of(2026, 9, 7)

    @BeforeEach
    fun setup() {
        DatabaseFactory.resetDatabase()
        businessRepository = BusinessRepository()
        businessRepository.create(
            Business(id = testBusinessId, name = "Kind Test Business", ownerId = "test-owner")
        )
        businessRepository.create(
            Business(id = otherBusinessId, name = "Other Location", ownerId = "test-owner")
        )
        employeeRepository = EmployeeRepository()
        scheduleRepository = ScheduleRepository()
    }

    private fun employee(name: String, businessId: UUID = testBusinessId): Employee {
        val e = Employee(
            businessId = businessId,
            firstName = name,
            lastName = "Test",
            dateOfBirth = LocalDate.of(1990, 1, 1),
            normalPayRate = 15.0,
            overtimePayRate = 22.5,
            productivity = 100.0,
            contract = Contract(
                contractedHoursPerWeek = 40.0,
                maxHoursPerWeek = 48.0,
                maxHoursPerDay = 12.0,
                overtimeThreshold = 40.0
            ),
            availability = DayOfWeek.entries.map {
                Availability(
                    availabilityType = AvailabilityType.WEEKLY_RECURRING,
                    dayOfWeek = it,
                    startTime = LocalTime.of(6, 0),
                    endTime = LocalTime.of(23, 0)
                )
            }
        )
        employeeRepository.create(e)
        return e
    }

    private fun saveSchedule(
        kind: ScheduleKind,
        startDate: LocalDate,
        endDate: LocalDate,
        shifts: List<Shift> = emptyList(),
        status: ScheduleStatus = ScheduleStatus.DRAFT,
        businessId: UUID = testBusinessId,
        name: String = "Test schedule"
    ): Schedule = scheduleRepository.save(
        Schedule(
            businessId = businessId,
            id = UUID.randomUUID(),
            name = name,
            status = status,
            kind = kind,
            schedulePeriod = SchedulePeriod(
                startDate = startDate,
                endDate = endDate,
                operatingHours = emptyMap()
            ),
            shifts = shifts,
            metrics = SchedulingMetrics(0.0, 0.0, 0.0, emptyMap()),
            violations = emptyList(),
            staffingRequirements = emptyList(),
            employeeIds = shifts.map { it.employeeId }.distinct(),
            laborCostBudget = 100_000.0,
            optimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST,
            version = 1,
            createdAt = Instant.now(),
            createdBy = "test",
            lastModifiedAt = Instant.now(),
            lastModifiedBy = "test"
        )
    )

    private fun shift(employeeId: UUID, date: LocalDate, start: Int, end: Int) = Shift(
        id = UUID.randomUUID(),
        employeeId = employeeId,
        date = date,
        startTime = LocalTime.of(start, 0),
        endTime = LocalTime.of(end, 0),
        payRate = 15.0
    )

    // ===== Kind survives a round trip =====

    @Test
    fun `kind is persisted and read back`() {
        val event = saveSchedule(ScheduleKind.EVENT, monday, monday)
        val reloaded = scheduleRepository.findById(testBusinessId, event.id)

        assertNotNull(reloaded)
        assertEquals(ScheduleKind.EVENT, reloaded!!.kind)
    }

    @Test
    fun `a schedule saved without a kind is regular`() {
        // Mirrors what every pre-existing row becomes once the column is added with its
        // REGULAR default: nothing already in the database may turn into an event.
        val schedule = saveSchedule(ScheduleKind.REGULAR, monday, monday.plusDays(6))
        assertEquals(ScheduleKind.REGULAR, scheduleRepository.findById(testBusinessId, schedule.id)!!.kind)
    }

    // ===== The two populations stay separate =====

    @Test
    fun `date range lookup ignores an event covering the same dates`() {
        // The trap this guards: findByBusinessAndDateRange matches start and end exactly
        // and returns singleOrNull. An event sharing a roster's dates would be handed back
        // in its place, and a second one would make the query throw outright.
        val roster = saveSchedule(ScheduleKind.REGULAR, monday, monday.plusDays(6), name = "Week")
        saveSchedule(ScheduleKind.EVENT, monday, monday.plusDays(6), name = "Gala")
        saveSchedule(ScheduleKind.EVENT, monday, monday.plusDays(6), name = "Second gala")

        val found = scheduleRepository.findByBusinessAndDateRange(
            testBusinessId, monday, monday.plusDays(6)
        )

        assertNotNull(found)
        assertEquals(roster.id, found!!.id)
        assertEquals(ScheduleKind.REGULAR, found.kind)
    }

    @Test
    fun `generating an event does not delete the roster it shares dates with`() {
        val roster = saveSchedule(ScheduleKind.REGULAR, monday, monday, name = "Week")
        saveSchedule(ScheduleKind.EVENT, monday, monday, name = "Party")

        assertNotNull(scheduleRepository.findById(testBusinessId, roster.id))
    }

    @Test
    fun `two events on one day coexist`() {
        // A lunch private hire and an evening party share a start and end date. Replacing
        // by date range would silently delete the first when the second is generated.
        val lunch = saveSchedule(ScheduleKind.EVENT, monday, monday, name = "Lunch hire")
        val evening = saveSchedule(ScheduleKind.EVENT, monday, monday, name = "Evening party")

        assertNotNull(scheduleRepository.findById(testBusinessId, lunch.id))
        assertNotNull(scheduleRepository.findById(testBusinessId, evening.id))
    }

    @Test
    fun `regenerating a roster still replaces the previous one`() {
        // The behaviour scoping by kind must not cost us: one roster per period.
        val first = saveSchedule(ScheduleKind.REGULAR, monday, monday.plusDays(6))
        val second = saveSchedule(ScheduleKind.REGULAR, monday, monday.plusDays(6))

        assertNull(scheduleRepository.findById(testBusinessId, first.id))
        assertNotNull(scheduleRepository.findById(testBusinessId, second.id))
    }

    @Test
    fun `schedule listing excludes events`() {
        saveSchedule(ScheduleKind.REGULAR, monday, monday.plusDays(6))
        saveSchedule(ScheduleKind.EVENT, monday, monday)

        val regular = scheduleRepository.findAllByBusiness(testBusinessId, ScheduleKind.REGULAR)
        assertEquals(1, regular.size)
        assertEquals(ScheduleKind.REGULAR, regular.single().kind)

        // Retention has to see everything, or events would never be cleaned up.
        assertEquals(2, scheduleRepository.findAllByBusiness(testBusinessId, kind = null).size)
    }

    @Test
    fun `status listing defaults to regular schedules`() {
        saveSchedule(ScheduleKind.REGULAR, monday, monday.plusDays(6), status = ScheduleStatus.PUBLISHED)
        saveSchedule(ScheduleKind.EVENT, monday, monday, status = ScheduleStatus.PUBLISHED)

        val published = scheduleRepository.findByBusinessAndStatus(testBusinessId, ScheduleStatus.PUBLISHED)
        assertEquals(1, published.size)
        assertEquals(ScheduleKind.REGULAR, published.single().kind)
    }

    @Test
    fun `events in a week are found by overlap`() {
        val inWeek = saveSchedule(ScheduleKind.EVENT, monday.plusDays(2), monday.plusDays(2))
        val alsoInWeek = saveSchedule(ScheduleKind.EVENT, monday.plusDays(5), monday.plusDays(5))
        saveSchedule(ScheduleKind.EVENT, monday.plusDays(14), monday.plusDays(14))
        saveSchedule(ScheduleKind.REGULAR, monday, monday.plusDays(6))

        val events = scheduleRepository.findEventsByBusinessAndDateRange(
            testBusinessId, monday, monday.plusDays(6)
        )

        assertEquals(listOf(inWeek.id, alsoInWeek.id), events.map { it.id })
    }

    // ===== ...but their hours are pooled =====

    @Test
    fun `an event's hours count against the roster's schedule`() {
        // The defect this exists for: hours were previously gathered with a
        // "different business" filter, so a same-business event was invisible to the
        // roster's weekly cap and vice versa. Someone could be booked twice over.
        val alice = employee("Alice")
        saveSchedule(
            kind = ScheduleKind.EVENT,
            startDate = monday,
            endDate = monday,
            shifts = listOf(shift(alice.id, monday, 20, 23)),
            status = ScheduleStatus.PUBLISHED
        )

        val committed = scheduleRepository.findCommittedShiftsElsewhere(
            excludeScheduleId = null,
            employeeIds = listOf(alice.id),
            startDate = monday,
            endDate = monday.plusDays(6)
        )

        assertEquals(1, committed.size)
        assertEquals(3.0, committed.single().durationHours, 0.001)
    }

    @Test
    fun `the schedule being generated does not count against itself`() {
        // Without the exclusion, re-generating a published week would charge the new
        // schedule for the hours of the one it is replacing, and each regeneration would
        // starve itself further.
        val alice = employee("Alice")
        val roster = saveSchedule(
            kind = ScheduleKind.REGULAR,
            startDate = monday,
            endDate = monday.plusDays(6),
            shifts = listOf(shift(alice.id, monday, 9, 17)),
            status = ScheduleStatus.PUBLISHED
        )

        val committed = scheduleRepository.findCommittedShiftsElsewhere(
            excludeScheduleId = roster.id,
            employeeIds = listOf(alice.id),
            startDate = monday,
            endDate = monday.plusDays(6)
        )

        assertTrue(committed.isEmpty(), "the schedule under generation must not count its own hours")
    }

    @Test
    fun `another location's hours still count`() {
        // The original purpose of this query, which the generalisation must not lose.
        val alice = employee("Alice")
        saveSchedule(
            kind = ScheduleKind.REGULAR,
            startDate = monday,
            endDate = monday,
            shifts = listOf(shift(alice.id, monday, 9, 17)),
            status = ScheduleStatus.PUBLISHED,
            businessId = otherBusinessId
        )

        val committed = scheduleRepository.findCommittedShiftsElsewhere(
            excludeScheduleId = null,
            employeeIds = listOf(alice.id),
            startDate = monday,
            endDate = monday.plusDays(6)
        )

        assertEquals(1, committed.size)
    }

    @Test
    fun `draft hours do not count`() {
        // A draft is a proposal, not a commitment. Letting drafts block each other would
        // mean whichever schedule generated first silently won.
        val alice = employee("Alice")
        saveSchedule(
            kind = ScheduleKind.EVENT,
            startDate = monday,
            endDate = monday,
            shifts = listOf(shift(alice.id, monday, 20, 23)),
            status = ScheduleStatus.DRAFT
        )

        val committed = scheduleRepository.findCommittedShiftsElsewhere(
            excludeScheduleId = null,
            employeeIds = listOf(alice.id),
            startDate = monday,
            endDate = monday.plusDays(6)
        )

        assertTrue(committed.isEmpty())
    }
}
