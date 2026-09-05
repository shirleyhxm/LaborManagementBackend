package org.labormanagement.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.labormanagement.database.DatabaseFactory
import org.labormanagement.dto.EventStaffingRequirementDto
import org.labormanagement.dto.SpecialEventRequest
import org.labormanagement.dto.toModel
import org.labormanagement.model.*
import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.ScheduleRepository
import org.labormanagement.repository.SpecialEventRepository
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Storing and reading back a special event definition.
 *
 * The definition is the manager's input and is edited repeatedly; the schedule generated
 * from it is a separate thing that may not exist yet. Most of what is worth testing here is
 * that the two stay properly separated, and that the shapes which cannot be expressed in SQL
 * - the sealed pay override, the optional rule overrides - survive the round trip intact.
 */
class SpecialEventRepositoryTest {

    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000007")
    private lateinit var repository: SpecialEventRepository
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

    private val newYearsEve = LocalDate.of(2026, 12, 31)

    @BeforeEach
    fun setup() {
        DatabaseFactory.resetDatabase()
        businessRepository = BusinessRepository()
        businessRepository.create(
            Business(id = testBusinessId, name = "Event Test Business", ownerId = "test-owner")
        )
        repository = SpecialEventRepository()
    }

    private fun event(
        name: String = "NYE Party",
        date: LocalDate = newYearsEve,
        start: LocalTime = LocalTime.of(21, 0),
        end: LocalTime = LocalTime.of(2, 0),
        requirements: List<EventStaffingRequirement> = emptyList(),
        expectedRevenue: Map<LocalTime, Double>? = null,
        ruleOverrides: EventRuleOverrides? = null,
        employeeIds: List<UUID> = emptyList()
    ) = SpecialEvent(
        businessId = testBusinessId,
        name = name,
        date = date,
        startTime = start,
        endTime = end,
        requirements = requirements,
        expectedRevenue = expectedRevenue,
        ruleOverrides = ruleOverrides,
        employeeIds = employeeIds,
        createdBy = "test"
    )

    // ===== Round trip =====

    @Test
    fun `an event survives a round trip`() {
        val created = repository.create(event())
        val loaded = repository.findById(testBusinessId, created.id)

        assertNotNull(loaded)
        assertEquals("NYE Party", loaded!!.name)
        assertEquals(newYearsEve, loaded.date)
        assertEquals(LocalTime.of(21, 0), loaded.startTime)
        assertEquals(LocalTime.of(2, 0), loaded.endTime)
    }

    @Test
    fun `an overnight event reports the day it finishes`() {
        val created = repository.create(event(start = LocalTime.of(21, 0), end = LocalTime.of(2, 0)))
        val loaded = repository.findById(testBusinessId, created.id)!!

        assertTrue(loaded.crossesMidnight)
        assertEquals(newYearsEve.plusDays(1), loaded.endDate)
    }

    @Test
    fun `an event finishing before midnight does not roll over`() {
        val created = repository.create(event(start = LocalTime.of(18, 0), end = LocalTime.of(23, 0)))
        val loaded = repository.findById(testBusinessId, created.id)!!

        assertFalse(loaded.crossesMidnight)
        assertEquals(newYearsEve, loaded.endDate)
    }

    @Test
    fun `staffing requirements round trip with both pay forms`() {
        // The sealed override has no SQL equivalent - it is stored as two nullable columns,
        // so which form comes back has to be reconstructed from which column is populated.
        val created = repository.create(
            event(requirements = listOf(
                EventStaffingRequirement("Bartender", 2, EventPayOverride.AbsoluteRate(28.0)),
                EventStaffingRequirement("Server", 4, EventPayOverride.Uplift(5.0)),
                EventStaffingRequirement("Security", 1, null)
            ))
        )

        val loaded = repository.findById(testBusinessId, created.id)!!
        val byGroup = loaded.requirements.associateBy { it.groupName }

        assertEquals(3, loaded.requirements.size)
        assertEquals(EventPayOverride.AbsoluteRate(28.0), byGroup["Bartender"]!!.payOverride)
        assertEquals(EventPayOverride.Uplift(5.0), byGroup["Server"]!!.payOverride)
        assertNull(byGroup["Security"]!!.payOverride)
        assertEquals(2, byGroup["Bartender"]!!.count)
    }

    @Test
    fun `rule overrides round trip, and absent ones stay absent`() {
        // Null must survive as null rather than becoming a copied default: an override that
        // silently froze the business's current value would stop tracking later changes.
        val created = repository.create(
            event(ruleOverrides = EventRuleOverrides(minShiftLength = 3.0, coverageFraction = 1.0))
        )

        val loaded = repository.findById(testBusinessId, created.id)!!
        assertEquals(3.0, loaded.ruleOverrides!!.minShiftLength)
        assertEquals(1.0, loaded.ruleOverrides!!.coverageFraction)
        assertNull(loaded.ruleOverrides!!.maxShiftLength)
        assertNull(loaded.ruleOverrides!!.laborCostBudget)
    }

    @Test
    fun `an event with no overrides reads back with none`() {
        val created = repository.create(event())
        assertNull(repository.findById(testBusinessId, created.id)!!.ruleOverrides)
    }

    @Test
    fun `expected revenue round trips by hour`() {
        val created = repository.create(
            event(expectedRevenue = mapOf(
                LocalTime.of(21, 0) to 1200.0,
                LocalTime.of(0, 0) to 800.0
            ))
        )

        val loaded = repository.findById(testBusinessId, created.id)!!
        assertEquals(1200.0, loaded.expectedRevenue!![LocalTime.of(21, 0)])
        assertEquals(800.0, loaded.expectedRevenue!![LocalTime.of(0, 0)])
    }

    // ===== Editing =====

    @Test
    fun `updating replaces requirements rather than merging them`() {
        // The edit form submits the whole set, so a group the manager removed has to
        // disappear instead of lingering because nothing mentioned it.
        val created = repository.create(
            event(requirements = listOf(
                EventStaffingRequirement("Bartender", 2),
                EventStaffingRequirement("Server", 4)
            ))
        )

        repository.update(testBusinessId, created.id, created.copy(
            requirements = listOf(EventStaffingRequirement("Bartender", 3))
        ))

        val loaded = repository.findById(testBusinessId, created.id)!!
        assertEquals(1, loaded.requirements.size)
        assertEquals("Bartender", loaded.requirements.single().groupName)
        assertEquals(3, loaded.requirements.single().count)
    }

    @Test
    fun `updating an event that does not exist reports it`() {
        assertNull(repository.update(testBusinessId, UUID.randomUUID(), event()))
    }

    // ===== Isolation between businesses =====

    @Test
    fun `an event is not visible from another business`() {
        val otherBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000008")
        businessRepository.create(
            Business(id = otherBusinessId, name = "Other", ownerId = "test-owner")
        )
        val created = repository.create(event())

        assertNull(repository.findById(otherBusinessId, created.id))
        assertTrue(repository.findByBusiness(otherBusinessId).isEmpty())
    }

    @Test
    fun `deleting is scoped to the owning business`() {
        val otherBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000008")
        businessRepository.create(
            Business(id = otherBusinessId, name = "Other", ownerId = "test-owner")
        )
        val created = repository.create(event())

        assertFalse(repository.delete(otherBusinessId, created.id))
        assertNotNull(repository.findById(testBusinessId, created.id))

        assertTrue(repository.delete(testBusinessId, created.id))
        assertNull(repository.findById(testBusinessId, created.id))
    }

    // ===== Date range =====

    @Test
    fun `events are found by date range, soonest first`() {
        val early = repository.create(event(name = "Lunch hire", date = newYearsEve.minusDays(2)))
        val late = repository.create(event(name = "NYE", date = newYearsEve))
        repository.create(event(name = "Next month", date = newYearsEve.plusMonths(1)))

        val found = repository.findByBusinessAndDateRange(
            testBusinessId, newYearsEve.minusDays(3), newYearsEve
        )

        assertEquals(listOf(early.id, late.id), found.map { it.id })
    }

    @Test
    fun `two events on one day both survive`() {
        // A lunch hire and an evening party share a date; neither replaces the other.
        val lunch = repository.create(event(name = "Lunch hire", start = LocalTime.of(12, 0), end = LocalTime.of(15, 0)))
        val evening = repository.create(event(name = "Evening party"))

        val found = repository.findByBusinessAndDateRange(testBusinessId, newYearsEve, newYearsEve)
        assertEquals(setOf(lunch.id, evening.id), found.map { it.id }.toSet())
    }

    // ===== Link to the generated schedule =====

    @Test
    fun `an event starts with no schedule and can be linked to one`() {
        val created = repository.create(event())
        assertNull(created.scheduleId)

        val schedule = saveEventSchedule()
        assertTrue(repository.linkSchedule(testBusinessId, created.id, schedule.id))
        assertEquals(schedule.id, repository.findById(testBusinessId, created.id)!!.scheduleId)
    }

    @Test
    fun `deleting the generated schedule leaves the event defined`() {
        // The definition is the manager's work and outlives any one generation, so a
        // deleted schedule leaves the event intact and simply not yet generated. Were the
        // link not cleared, the foreign key would refuse the delete outright.
        val created = repository.create(event())
        val schedule = saveEventSchedule()
        repository.linkSchedule(testBusinessId, created.id, schedule.id)

        ScheduleRepository().delete(schedule.id)

        val loaded = repository.findById(testBusinessId, created.id)
        assertNotNull(loaded, "the event was deleted along with its schedule")
        assertNull(loaded!!.scheduleId, "the event still points at a schedule that is gone")
    }

    /** A minimal EVENT-kind draft schedule, standing in for one that generation produced. */
    private fun saveEventSchedule(): Schedule = ScheduleRepository().save(
        Schedule(
            businessId = testBusinessId,
            name = "NYE Party",
            status = ScheduleStatus.DRAFT,
            kind = ScheduleKind.EVENT,
            schedulePeriod = SchedulePeriod(newYearsEve, newYearsEve, emptyMap()),
            shifts = emptyList(),
            metrics = SchedulingMetrics(0.0, 0.0, 0.0, emptyMap()),
            employeeIds = emptyList(),
            laborCostBudget = 1000.0,
            optimizationObjective = OptimizationObjective.BALANCED,
            createdBy = "test",
            lastModifiedBy = "test"
        )
    )

    // ===== Request validation =====

    @Test
    fun `a request rejects a zero-length event`() {
        val request = SpecialEventRequest(
            name = "Instant", date = "2026-12-31", startTime = "21:00", endTime = "21:00"
        )
        val error = assertThrows(IllegalArgumentException::class.java) { request.toModel(testBusinessId) }
        assertTrue(error.message!!.contains("no duration"), error.message)
    }

    @Test
    fun `a request accepts an event running past midnight`() {
        // The ordinary shape for a party, and the one an earlier draft of this feature
        // would have rejected outright.
        val request = SpecialEventRequest(
            name = "NYE", date = "2026-12-31", startTime = "21:00", endTime = "02:00"
        )
        val model = request.toModel(testBusinessId)
        assertTrue(model.crossesMidnight)
    }

    @Test
    fun `a request rejects a requirement carrying both pay forms`() {
        val request = SpecialEventRequest(
            name = "NYE", date = "2026-12-31", startTime = "21:00", endTime = "02:00",
            requirements = listOf(EventStaffingRequirementDto("Bartender", 2, payRate = 28.0, payUplift = 5.0))
        )
        val error = assertThrows(IllegalArgumentException::class.java) { request.toModel(testBusinessId) }
        assertTrue(error.message!!.contains("only one can apply"), error.message)
    }

    @Test
    fun `a request rejects duplicate groups`() {
        val request = SpecialEventRequest(
            name = "NYE", date = "2026-12-31", startTime = "21:00", endTime = "02:00",
            requirements = listOf(
                EventStaffingRequirementDto("Bartender", 2),
                EventStaffingRequirementDto("bartender", 3)
            )
        )
        val error = assertThrows(IllegalArgumentException::class.java) { request.toModel(testBusinessId) }
        assertTrue(error.message!!.contains("Duplicate"), error.message)
    }

    @Test
    fun `a request rejects a requirement for nobody`() {
        val request = SpecialEventRequest(
            name = "NYE", date = "2026-12-31", startTime = "21:00", endTime = "02:00",
            requirements = listOf(EventStaffingRequirementDto("Bartender", 0))
        )
        assertThrows(IllegalArgumentException::class.java) { request.toModel(testBusinessId) }
    }

    @Test
    fun `a request accepts a midnight close written as 24 00`() {
        val request = SpecialEventRequest(
            name = "Late", date = "2026-12-31", startTime = "18:00", endTime = "24:00"
        )
        assertEquals(LocalTime.MIDNIGHT, request.toModel(testBusinessId).endTime)
    }
}
