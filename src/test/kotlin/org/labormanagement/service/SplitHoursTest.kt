package org.labormanagement.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.labormanagement.database.DatabaseFactory
import org.labormanagement.dto.BusinessDayHoursDto
import org.labormanagement.dto.OpenIntervalDto
import org.labormanagement.dto.toDto
import org.labormanagement.dto.toModel
import org.labormanagement.model.*
import org.labormanagement.repository.BusinessHoursRepository
import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.EmployeeRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * A day that closes in the middle - open 09:00-13:00 and 14:00-18:00.
 *
 * The span of such a day is 09:00-18:00, and everything that turns hours into slots,
 * demand or coverage used to walk the span. So the questions here are all about the gap:
 * that neither scheduling path puts anyone in it, that the staffing report does not call
 * it understaffed, and that the stretches survive the database and the wire intact.
 *
 * Stretches are four hours each so a minimum-shift rule cannot be what leaves them
 * empty and mask a real failure.
 */
class SplitHoursTest {

    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000009")
    private lateinit var employeeRepository: EmployeeRepository
    private lateinit var businessRepository: BusinessRepository
    private lateinit var hoursRepository: BusinessHoursRepository

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

    private val morning = OpenInterval(LocalTime.of(9, 0), LocalTime.of(13, 0))
    private val afternoon = OpenInterval(LocalTime.of(14, 0), LocalTime.of(18, 0))
    private val gapStart = LocalTime.of(13, 0)
    private val gapEnd = LocalTime.of(14, 0)

    @BeforeEach
    fun setup() {
        DatabaseFactory.resetDatabase()
        employeeRepository = EmployeeRepository()
        businessRepository = BusinessRepository()
        hoursRepository = BusinessHoursRepository()
        businessRepository.create(
            Business(
                id = testBusinessId,
                name = "Split Hours Test Business",
                ownerId = "test-owner",
                settings = BusinessSettings(
                    defaultOpenTime = LocalTime.of(9, 0),
                    defaultCloseTime = LocalTime.of(21, 0)
                )
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
            // Available all day, so availability can never be what keeps the gap empty.
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

    /** Every day open [morning] and [afternoon], closed between. */
    private fun splitWeek() = DayOfWeek.entries.map {
        BusinessDayHours(
            dayOfWeek = it,
            openTime = morning.openTime,
            closeTime = afternoon.closeTime,
            intervals = listOf(morning, afternoon)
        )
    }

    private fun generate(employees: List<Employee>, approach: SchedulingApproach): Schedule =
        ShiftScheduler(schedulingApproach = approach).generateSchedule(
            input = ScheduleInput(
                businessId = testBusinessId,
                employeeIds = employees.map { it.id },
                laborCostBudget = Double.MAX_VALUE,
                schedulePeriod = SchedulePeriod(monday, monday, emptyMap()),
                optimizationObjective = OptimizationObjective.MAXIMIZE_SALES
            ),
            name = "Split hours test",
            generatedBy = "test",
            businessId = testBusinessId
        )

    private fun overlapsGap(start: LocalTime, end: LocalTime) = start < gapEnd && end > gapStart

    private fun describe(shifts: List<Shift>) = shifts.map { "${it.startTime}-${it.endTime}" }

    // ----- Persistence and resolution -----

    @Test
    fun `a split week round trips through the database`() {
        hoursRepository.saveWeek(testBusinessId, splitWeek())

        val resolved = hoursRepository.findByBusinessId(testBusinessId).resolve(monday)!!

        assertEquals(listOf(morning, afternoon), resolved.openIntervals())
        // The span is still first opening to last closing, for everything that only
        // needs the extent of the day.
        assertEquals(morning.openTime, resolved.openTime)
        assertEquals(afternoon.closeTime, resolved.closeTime)
    }

    @Test
    fun `an ordinary day is one stretch covering its span`() {
        hoursRepository.saveWeek(
            testBusinessId,
            DayOfWeek.entries.map {
                BusinessDayHours(dayOfWeek = it, openTime = LocalTime.of(9, 0), closeTime = LocalTime.of(17, 0))
            }
        )

        val resolved = hoursRepository.findByBusinessId(testBusinessId).resolve(monday)!!

        assertEquals(
            listOf(OpenInterval(LocalTime.of(9, 0), LocalTime.of(17, 0))),
            resolved.openIntervals()
        )
    }

    @Test
    fun `a split override beats a single-stretch week`() {
        hoursRepository.saveWeek(
            testBusinessId,
            DayOfWeek.entries.map {
                BusinessDayHours(dayOfWeek = it, openTime = LocalTime.of(9, 0), closeTime = LocalTime.of(21, 0))
            }
        )
        hoursRepository.saveOverride(
            BusinessHourOverride(
                businessId = testBusinessId,
                date = monday,
                openTime = morning.openTime,
                closeTime = afternoon.closeTime,
                intervals = listOf(morning, afternoon),
                label = "Staff lunch"
            )
        )

        val hours = hoursRepository.findByBusinessId(testBusinessId)

        assertEquals(listOf(morning, afternoon), hours.resolve(monday)!!.openIntervals())
        // The override is one date, not the weekday.
        assertEquals(1, hours.resolve(monday.plusDays(7))!!.openIntervals().size)
    }

    // ----- Scheduling -----

    @Test
    fun `greedy puts nobody in the gap`() {
        hoursRepository.saveWeek(testBusinessId, splitWeek())

        val schedule = generate(listOf(employee("Greedy")), SchedulingApproach.GREEDY)

        assertTrue(schedule.shifts.isNotEmpty(), "Both stretches are open and should be staffed")
        assertTrue(
            schedule.shifts.none { overlapsGap(it.startTime, it.endTime) },
            "No shift may cover the 13:00-14:00 closure, got ${describe(schedule.shifts)}"
        )
    }

    @Test
    fun `optimizer puts nobody in the gap`() {
        hoursRepository.saveWeek(testBusinessId, splitWeek())

        val schedule = generate(listOf(employee("Optimizer")), SchedulingApproach.OPTIMIZER)

        assertTrue(schedule.shifts.isNotEmpty(), "Both stretches are open and should be staffed")
        assertTrue(
            schedule.shifts.none { overlapsGap(it.startTime, it.endTime) },
            "No shift may cover the 13:00-14:00 closure, got ${describe(schedule.shifts)}"
        )
    }

    @Test
    fun `both paths keep every shift inside an open stretch`() {
        hoursRepository.saveWeek(testBusinessId, splitWeek())

        for (approach in listOf(SchedulingApproach.GREEDY, SchedulingApproach.OPTIMIZER)) {
            val schedule = generate(listOf(employee("Inside-$approach")), approach)
            schedule.shifts.forEach { shift ->
                val inside = listOf(morning, afternoon).any {
                    shift.startTime >= it.openTime && shift.endTime <= it.closeTime
                }
                assertTrue(inside, "$approach: ${shift.startTime}-${shift.endTime} is not inside one stretch")
            }
        }
    }

    @Test
    fun `the staffing report does not call the gap understaffed`() {
        hoursRepository.saveWeek(testBusinessId, splitWeek())

        val schedule = generate(listOf(employee("Report")), SchedulingApproach.GREEDY)

        val inGap = schedule.staffingRequirements.filter {
            it.date == monday && overlapsGap(it.startTime, it.endTime)
        }
        assertTrue(
            inGap.isEmpty(),
            "The business is shut 13:00-14:00, so it has no staffing requirement there; got " +
                inGap.map { "${it.startTime}-${it.endTime} needs ${it.employeesNeeded}" }
        )
    }

    // ----- The wire -----

    @Test
    fun `stretches sent over the wire win over a stale open and close`() {
        val model = BusinessDayHoursDto(
            dayOfWeek = "MONDAY",
            // A client that updated the stretches but not the span.
            openTime = "10:00",
            closeTime = "11:00",
            intervals = listOf(OpenIntervalDto("09:00", "13:00"), OpenIntervalDto("14:00", "18:00"))
        ).toModel()

        assertEquals(listOf(morning, afternoon), model.intervals)
        assertEquals(morning.openTime, model.openTime)
        assertEquals(afternoon.closeTime, model.closeTime)
    }

    @Test
    fun `a client that only knows open and close still describes its day`() {
        val model = BusinessDayHoursDto(dayOfWeek = "MONDAY", openTime = "09:00", closeTime = "17:00").toModel()

        assertEquals(LocalTime.of(9, 0), model.openTime)
        assertEquals(LocalTime.of(17, 0), model.closeTime)
        assertTrue(model.intervals.isEmpty(), "A single stretch is stored as the span alone")
    }

    @Test
    fun `the response always carries stretches, even for an ordinary day`() {
        val dto = BusinessDayHours(
            dayOfWeek = DayOfWeek.MONDAY,
            openTime = LocalTime.of(9, 0),
            closeTime = LocalTime.of(17, 0)
        ).toDto()

        assertEquals(listOf(OpenIntervalDto("09:00", "17:00")), dto.intervals)
    }

    // ----- Validation -----

    @Test
    fun `stretches in order and apart are accepted`() {
        assertNull(invalidIntervalsReason(listOf(morning, afternoon)))
    }

    @Test
    fun `overlapping stretches are rejected`() {
        val reason = invalidIntervalsReason(
            listOf(
                OpenInterval(LocalTime.of(9, 0), LocalTime.of(13, 0)),
                OpenInterval(LocalTime.of(12, 0), LocalTime.of(17, 0))
            )
        )
        assertNotNull(reason)
    }

    @Test
    fun `stretches out of order are rejected`() {
        assertNotNull(invalidIntervalsReason(listOf(afternoon, morning)))
    }

    @Test
    fun `only the last stretch may run past midnight`() {
        val lateLast = listOf(
            OpenInterval(LocalTime.of(11, 0), LocalTime.of(15, 0)),
            OpenInterval(LocalTime.of(18, 0), LocalTime.of(1, 0))
        )
        assertNull(invalidIntervalsReason(lateLast), "A late evening stretch is a normal bar")

        val lateFirst = listOf(
            OpenInterval(LocalTime.of(22, 0), LocalTime.of(2, 0)),
            OpenInterval(LocalTime.of(9, 0), LocalTime.of(12, 0))
        )
        assertNotNull(invalidIntervalsReason(lateFirst))
    }

    @Test
    fun `a stretch of no length is rejected`() {
        assertNotNull(invalidIntervalsReason(listOf(OpenInterval(LocalTime.of(9, 0), LocalTime.of(9, 0)))))
    }
}
