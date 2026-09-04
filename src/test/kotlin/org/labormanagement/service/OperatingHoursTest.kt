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
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Where the working day comes from when the caller does not describe it.
 *
 * Generation used to depend on the client sending operating hours for every date, and the
 * client sent a hardcoded 09:00-21:00 for all of them. So a business's real opening hours
 * never reached the solver, and the two scheduling paths disagreed about what to do with a
 * date that had none: CP-SAT substituted 09:00-17:00 while the greedy path skipped the day
 * entirely.
 */
class OperatingHoursTest {

    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000005")
    private lateinit var employeeRepository: EmployeeRepository
    private lateinit var businessRepository: BusinessRepository
    private lateinit var scheduleRepository: ScheduleRepository

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
        employeeRepository = EmployeeRepository()
        scheduleRepository = ScheduleRepository()
    }

    private fun createBusiness(open: LocalTime, close: LocalTime) {
        businessRepository.create(
            Business(
                id = testBusinessId,
                name = "Hours Test Business",
                ownerId = "test-owner",
                settings = BusinessSettings(defaultOpenTime = open, defaultCloseTime = close)
            )
        )
    }

    private fun employee(name: String): Employee {
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
                maxHoursPerDay = 14.0,
                overtimeThreshold = 40.0
            ),
            // Available all day, so availability never limits the window under test.
            availability = DayOfWeek.entries.map {
                Availability(
                    availabilityType = AvailabilityType.WEEKLY_RECURRING,
                    dayOfWeek = it,
                    startTime = LocalTime.of(0, 0),
                    endTime = LocalTime.of(23, 59)
                )
            }
        )
        employeeRepository.create(e)
        return e
    }

    /** A period with no per-date hours at all - what the client now sends. */
    private fun periodWithoutHours(date: LocalDate) = SchedulePeriod(
        startDate = date,
        endDate = date,
        operatingHours = emptyMap()
    )

    private fun generate(
        period: SchedulePeriod,
        employees: List<Employee>,
        approach: SchedulingApproach
    ): Schedule {
        val scheduler = ShiftScheduler(schedulingApproach = approach)
        return scheduler.generateSchedule(
            input = ScheduleInput(
                businessId = testBusinessId,
                employeeIds = employees.map { it.id },
                laborCostBudget = Double.MAX_VALUE,
                schedulePeriod = period,
                optimizationObjective = OptimizationObjective.MAXIMIZE_SALES
            ),
            name = "Hours test",
            generatedBy = "test",
            businessId = testBusinessId
        )
    }

    private fun span(schedule: Schedule): Pair<LocalTime, LocalTime>? {
        if (schedule.shifts.isEmpty()) return null
        return schedule.shifts.minOf { it.startTime } to schedule.shifts.maxOf { it.endTime }
    }

    @Test
    fun `settings round trip through the repository`() {
        createBusiness(LocalTime.of(11, 0), LocalTime.of(23, 0))
        val loaded = businessRepository.findById(testBusinessId)

        assertNotNull(loaded)
        assertEquals(LocalTime.of(11, 0), loaded!!.settings.defaultOpenTime)
        assertEquals(LocalTime.of(23, 0), loaded.settings.defaultCloseTime)
    }

    @Test
    fun `a business created without hours keeps the previous hardcoded window`() {
        // Every existing row migrates to these, so nothing already scheduled shifts around.
        businessRepository.create(
            Business(id = testBusinessId, name = "Default Business", ownerId = "test-owner")
        )
        val loaded = businessRepository.findById(testBusinessId)!!

        assertEquals(LocalTime.of(9, 0), loaded.settings.defaultOpenTime)
        assertEquals(LocalTime.of(21, 0), loaded.settings.defaultCloseTime)
    }

    @Test
    fun `optimizer generates within the business's configured hours`() {
        createBusiness(LocalTime.of(18, 0), LocalTime.of(23, 0))
        val alice = employee("Alice")

        val schedule = generate(periodWithoutHours(monday), listOf(alice), SchedulingApproach.OPTIMIZER)
        val span = span(schedule)

        assertNotNull(span, "an evening-only business should still be scheduled")
        assertTrue(span!!.first >= LocalTime.of(18, 0), "started before opening: ${span.first}")
        assertTrue(span.second <= LocalTime.of(23, 0), "ran past closing: ${span.second}")
    }

    @Test
    fun `greedy generates within the business's configured hours`() {
        // The greedy path used to skip a date with no hours outright, producing nothing.
        createBusiness(LocalTime.of(18, 0), LocalTime.of(23, 0))
        val alice = employee("Alice")

        val schedule = generate(periodWithoutHours(monday), listOf(alice), SchedulingApproach.GREEDY)
        val span = span(schedule)

        assertNotNull(span, "greedy skipped a date carrying no explicit hours")
        assertTrue(span!!.first >= LocalTime.of(18, 0), "started before opening: ${span.first}")
        assertTrue(span.second <= LocalTime.of(23, 0), "ran past closing: ${span.second}")
    }

    @Test
    fun `both paths agree on the working day`() {
        // They previously did not: 09:00-17:00 on one, nothing at all on the other.
        createBusiness(LocalTime.of(10, 0), LocalTime.of(16, 0))
        val alice = employee("Alice")

        val optimizer = generate(periodWithoutHours(monday), listOf(alice), SchedulingApproach.OPTIMIZER)
        val greedy = generate(periodWithoutHours(monday), listOf(alice), SchedulingApproach.GREEDY)

        listOf("optimizer" to optimizer, "greedy" to greedy).forEach { (label, schedule) ->
            val span = span(schedule)
            assertNotNull(span, "$label produced no shifts")
            assertTrue(span!!.first >= LocalTime.of(10, 0), "$label started at ${span.first}")
            assertTrue(span.second <= LocalTime.of(16, 0), "$label ended at ${span.second}")
        }
    }

    @Test
    fun `explicit per-date hours still win over the business default`() {
        // Overriding a single day has to keep working - it is what event schedules rely on.
        createBusiness(LocalTime.of(9, 0), LocalTime.of(17, 0))
        val alice = employee("Alice")

        val lateNight = SchedulePeriod(
            startDate = monday,
            endDate = monday,
            operatingHours = mapOf(monday to OperatingHours(LocalTime.of(20, 0), LocalTime.of(23, 0)))
        )
        val schedule = generate(lateNight, listOf(alice), SchedulingApproach.OPTIMIZER)
        val span = span(schedule)

        assertNotNull(span)
        assertTrue(span!!.first >= LocalTime.of(20, 0), "explicit hours were ignored: ${span.first}")
        assertTrue(span.second <= LocalTime.of(23, 0), "explicit hours were ignored: ${span.second}")
    }

    @Test
    fun `staffing requirements cover a date with no explicit hours`() {
        // These are reported per date from the resolved hours. Reading them from the raw
        // period instead would silently drop the day and read as "no demand".
        createBusiness(LocalTime.of(9, 0), LocalTime.of(17, 0))
        val alice = employee("Alice")

        val schedule = generate(periodWithoutHours(monday), listOf(alice), SchedulingApproach.OPTIMIZER)

        assertTrue(
            schedule.staffingRequirements.any { it.date == monday },
            "no staffing requirement reported for a date the caller left unspecified"
        )
    }
}
