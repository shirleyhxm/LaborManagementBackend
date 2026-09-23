package org.labormanagement.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.labormanagement.database.DatabaseFactory
import org.labormanagement.model.*
import org.labormanagement.repository.BusinessHoursRepository
import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.EmployeeRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Per-day opening hours and the dates that depart from them.
 *
 * The weekly pattern supersedes BusinessSettings.defaultOpenTime/defaultCloseTime, which
 * could only describe a business whose every day looked the same. The legacy pair is still
 * the fallback for a business that has saved no week, which OperatingHoursTest covers;
 * what matters here is that a saved week beats it, an override beats the week, and a
 * closed day produces no shifts on either scheduling path.
 */
class BusinessHoursTest {

    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000007")
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

    // A Monday, so weekday/weekend cases are easy to express.
    private val monday = LocalDate.of(2026, 9, 7)
    private val saturday = monday.plusDays(5)

    @BeforeEach
    fun setup() {
        DatabaseFactory.resetDatabase()
        employeeRepository = EmployeeRepository()
        businessRepository = BusinessRepository()
        hoursRepository = BusinessHoursRepository()
        businessRepository.create(
            Business(
                id = testBusinessId,
                name = "Hours Test Business",
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

    /** A week where every day shares [open]-[close]. */
    private fun uniformWeek(open: LocalTime, close: LocalTime) =
        DayOfWeek.entries.map { BusinessDayHours(dayOfWeek = it, openTime = open, closeTime = close) }

    private fun periodWithoutHours(start: LocalDate, end: LocalDate = start) = SchedulePeriod(
        startDate = start,
        endDate = end,
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
            name = "Business hours test",
            generatedBy = "test",
            businessId = testBusinessId
        )
    }

    @Test
    fun `a saved week round trips`() {
        val week = uniformWeek(LocalTime.of(10, 0), LocalTime.of(18, 0))
            .map { if (it.dayOfWeek == DayOfWeek.SUNDAY) it.copy(isClosed = true) else it }
        hoursRepository.saveWeek(testBusinessId, week)

        val loaded = hoursRepository.findByBusinessId(testBusinessId)

        assertEquals(7, loaded.week.size)
        assertEquals(LocalTime.of(10, 0), loaded.resolve(monday)!!.openTime)
        assertEquals(LocalTime.of(18, 0), loaded.resolve(monday)!!.closeTime)
        assertNull(loaded.resolve(monday.plusDays(6)), "Sunday is closed, so it resolves to no hours")
    }

    @Test
    fun `saving the week again replaces it rather than accumulating`() {
        hoursRepository.saveWeek(testBusinessId, uniformWeek(LocalTime.of(10, 0), LocalTime.of(18, 0)))
        hoursRepository.saveWeek(testBusinessId, uniformWeek(LocalTime.of(8, 0), LocalTime.of(16, 0)))

        val loaded = hoursRepository.findByBusinessId(testBusinessId)

        assertEquals(7, loaded.week.size, "A second save must not leave fourteen rows behind")
        assertEquals(LocalTime.of(8, 0), loaded.resolve(monday)!!.openTime)
    }

    @Test
    fun `days may differ from one another`() {
        hoursRepository.saveWeek(
            testBusinessId,
            uniformWeek(LocalTime.of(9, 0), LocalTime.of(21, 0)).map {
                if (it.dayOfWeek == DayOfWeek.SATURDAY) {
                    it.copy(openTime = LocalTime.of(10, 0), closeTime = LocalTime.of(16, 0))
                } else it
            }
        )

        val loaded = hoursRepository.findByBusinessId(testBusinessId)

        assertEquals(LocalTime.of(9, 0), loaded.resolve(monday)!!.openTime)
        assertEquals(LocalTime.of(10, 0), loaded.resolve(saturday)!!.openTime)
        assertEquals(LocalTime.of(16, 0), loaded.resolve(saturday)!!.closeTime)
    }

    @Test
    fun `an override beats the weekly pattern`() {
        hoursRepository.saveWeek(testBusinessId, uniformWeek(LocalTime.of(9, 0), LocalTime.of(21, 0)))
        hoursRepository.saveOverride(
            BusinessHourOverride(
                businessId = testBusinessId,
                date = monday,
                openTime = LocalTime.of(12, 0),
                closeTime = LocalTime.of(15, 0),
                label = "Stocktake"
            )
        )

        val loaded = hoursRepository.findByBusinessId(testBusinessId)

        assertEquals(LocalTime.of(12, 0), loaded.resolve(monday)!!.openTime)
        // The following Monday is untouched: an override is one date, not a weekday.
        assertEquals(LocalTime.of(9, 0), loaded.resolve(monday.plusDays(7))!!.openTime)
    }

    @Test
    fun `a closing override shuts a day the week says is open`() {
        hoursRepository.saveWeek(testBusinessId, uniformWeek(LocalTime.of(9, 0), LocalTime.of(21, 0)))
        hoursRepository.saveOverride(
            BusinessHourOverride(
                businessId = testBusinessId,
                date = monday,
                isClosed = true,
                label = "Bank holiday"
            )
        )

        assertTrue(hoursRepository.findByBusinessId(testBusinessId).isClosedOn(monday))
    }

    @Test
    fun `one date carries one override`() {
        hoursRepository.saveOverride(
            BusinessHourOverride(businessId = testBusinessId, date = monday, isClosed = true, label = "First")
        )
        hoursRepository.saveOverride(
            BusinessHourOverride(
                businessId = testBusinessId,
                date = monday,
                openTime = LocalTime.of(11, 0),
                closeTime = LocalTime.of(14, 0),
                label = "Corrected"
            )
        )

        val overrides = hoursRepository.findByBusinessId(testBusinessId).overrides

        assertEquals(1, overrides.size, "Saving the same date twice is a correction, not a second holiday")
        assertEquals("Corrected", overrides.single().label)
        assertFalse(overrides.single().isClosed)
    }

    @Test
    fun `greedy generates no shifts on a closed day`() {
        hoursRepository.saveWeek(
            testBusinessId,
            uniformWeek(LocalTime.of(9, 0), LocalTime.of(21, 0)).map {
                if (it.dayOfWeek == DayOfWeek.MONDAY) it.copy(isClosed = true) else it
            }
        )
        val employees = listOf(employee("Greedy"))

        val schedule = generate(periodWithoutHours(monday), employees, SchedulingApproach.GREEDY)

        assertTrue(schedule.shifts.isEmpty(), "A closed Monday must produce no shifts, got ${schedule.shifts.size}")
    }

    @Test
    fun `optimizer generates no shifts on a closed day`() {
        hoursRepository.saveWeek(
            testBusinessId,
            uniformWeek(LocalTime.of(9, 0), LocalTime.of(21, 0)).map {
                if (it.dayOfWeek == DayOfWeek.MONDAY) it.copy(isClosed = true) else it
            }
        )
        val employees = listOf(employee("Optimizer"))

        val schedule = generate(periodWithoutHours(monday), employees, SchedulingApproach.OPTIMIZER)

        assertTrue(schedule.shifts.isEmpty(), "A closed Monday must produce no shifts, got ${schedule.shifts.size}")
    }

    @Test
    fun `a closed day does not suppress the days around it`() {
        hoursRepository.saveWeek(
            testBusinessId,
            uniformWeek(LocalTime.of(9, 0), LocalTime.of(17, 0)).map {
                if (it.dayOfWeek == DayOfWeek.TUESDAY) it.copy(isClosed = true) else it
            }
        )
        val employees = listOf(employee("Neighbour"))

        val schedule = generate(periodWithoutHours(monday, monday.plusDays(2)), employees, SchedulingApproach.GREEDY)

        val dates = schedule.shifts.map { it.date }.toSet()
        assertFalse(dates.contains(monday.plusDays(1)), "Tuesday is closed")
        assertTrue(dates.contains(monday), "Monday is open and should still be scheduled")
    }

    @Test
    fun `generation honours per-day hours`() {
        hoursRepository.saveWeek(
            testBusinessId,
            uniformWeek(LocalTime.of(9, 0), LocalTime.of(21, 0)).map {
                if (it.dayOfWeek == DayOfWeek.SATURDAY) {
                    it.copy(openTime = LocalTime.of(10, 0), closeTime = LocalTime.of(16, 0))
                } else it
            }
        )
        val employees = listOf(employee("Weekend"))

        val schedule = generate(periodWithoutHours(saturday), employees, SchedulingApproach.GREEDY)

        assertTrue(schedule.shifts.isNotEmpty(), "Saturday is open and should be scheduled")
        assertTrue(
            schedule.shifts.all { it.startTime >= LocalTime.of(10, 0) && it.endTime <= LocalTime.of(16, 0) },
            "Every Saturday shift must fall inside 10:00-16:00, got " +
                schedule.shifts.map { "${it.startTime}-${it.endTime}" }
        )
    }

    @Test
    fun `explicit per-date hours still win over saved hours`() {
        hoursRepository.saveWeek(testBusinessId, uniformWeek(LocalTime.of(9, 0), LocalTime.of(21, 0)))
        val employees = listOf(employee("Explicit"))

        val period = SchedulePeriod(
            startDate = monday,
            endDate = monday,
            operatingHours = mapOf(monday to OperatingHours(LocalTime.of(13, 0), LocalTime.of(17, 0)))
        )
        val schedule = generate(period, employees, SchedulingApproach.GREEDY)

        assertTrue(schedule.shifts.isNotEmpty())
        assertTrue(
            schedule.shifts.all { it.startTime >= LocalTime.of(13, 0) && it.endTime <= LocalTime.of(17, 0) },
            "A caller's explicit hours must still win, got " +
                schedule.shifts.map { "${it.startTime}-${it.endTime}" }
        )
    }
}
