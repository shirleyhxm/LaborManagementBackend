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
 * Undoing an edit to a draft schedule.
 *
 * The case that justifies snapshots over "move it back" is [undo restores a merge]:
 * re-deriving overtime fuses a moved shift with a contiguous neighbour, and the merged
 * block carries hours that belonged to a different row. Moving that block back is not
 * the inverse of the original move — it hands the origin employee more hours than they
 * had, which either trips their weekly cap or silently succeeds with the wrong result.
 */
class ScheduleUndoTest {

    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private lateinit var employeeRepository: EmployeeRepository
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var businessRepository: BusinessRepository
    private lateinit var service: ShiftModificationService

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
            Business(id = testBusinessId, name = "Undo Test Business", ownerId = "test-owner")
        )
        employeeRepository = EmployeeRepository()
        scheduleRepository = ScheduleRepository()
        service = ShiftModificationService(
            scheduleRepository = scheduleRepository,
            employeeRepository = employeeRepository,
            constraintValidator = ConstraintValidator(),
            constraintsService = ConstraintsService()
        )
    }

    private fun allWeekAvailability() = DayOfWeek.entries.map {
        Availability(
            availabilityType = AvailabilityType.WEEKLY_RECURRING,
            dayOfWeek = it,
            startTime = LocalTime.of(6, 0),
            endTime = LocalTime.of(23, 0)
        )
    }

    private fun employee(name: String, maxHoursPerWeek: Double = 60.0): Employee {
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
                maxHoursPerWeek = maxHoursPerWeek,
                maxHoursPerDay = 12.0,
                overtimeThreshold = 40.0
            ),
            availability = allWeekAvailability()
        )
        employeeRepository.create(e)
        return e
    }

    private fun shift(employeeId: UUID, start: Int, end: Int) = Shift(
        id = UUID.randomUUID(),
        employeeId = employeeId,
        date = monday,
        startTime = LocalTime.of(start, 0),
        endTime = LocalTime.of(end, 0),
        payRate = 15.0
    )

    private fun saveSchedule(shifts: List<Shift>, employeeIds: List<UUID>): Schedule {
        val schedule = Schedule(
            businessId = testBusinessId,
            id = UUID.randomUUID(),
            name = "Undo test schedule",
            status = ScheduleStatus.DRAFT,
            schedulePeriod = SchedulePeriod(
                startDate = monday,
                endDate = monday.plusDays(6),
                operatingHours = (0L..6L).associate {
                    monday.plusDays(it) to OperatingHours(LocalTime.of(6, 0), LocalTime.of(23, 0))
                }
            ),
            shifts = shifts,
            metrics = SchedulingMetrics(0.0, 0.0, 0.0, emptyMap()),
            violations = emptyList(),
            staffingRequirements = emptyList(),
            employeeIds = employeeIds,
            laborCostBudget = 100_000.0,
            optimizationObjective = OptimizationObjective.MINIMIZE_LABOR_COST,
            version = 1,
            createdAt = Instant.now(),
            createdBy = "test",
            lastModifiedAt = Instant.now(),
            lastModifiedBy = "test"
        )
        return scheduleRepository.save(schedule)
    }

    /** Shifts as (employee, start, end), ordered, for comparing whole plans. */
    private fun layoutOf(schedule: Schedule) = schedule.shifts
        .map { Triple(it.employeeId, it.startTime, it.endTime) }
        .sortedWith(compareBy({ it.first.toString() }, { it.second }))

    @Test
    fun `undo restores a simple reassign`() {
        val alice = employee("Alice")
        val bob = employee("Bob")
        val target = shift(alice.id, 9, 12)
        val saved = saveSchedule(listOf(target), listOf(alice.id, bob.id))
        val before = layoutOf(saved)

        service.modifyShift(
            businessId = testBusinessId,
            scheduleId = saved.id,
            shiftId = target.id,
            newEmployeeId = bob.id,
            modifiedBy = "test"
        )
        assertEquals(bob.id, scheduleRepository.findById(testBusinessId, saved.id)!!.shifts.single().employeeId)

        val result = service.undoLastChange(testBusinessId, saved.id, "test")

        assertTrue(result.restored)
        assertEquals(before, layoutOf(scheduleRepository.findById(testBusinessId, saved.id)!!))
    }

    /**
     * The reason this feature exists.
     *
     * Bob already works 12:00-15:00. Moving Alice's 09:00-12:00 onto him makes the two
     * contiguous, so they merge into one 09:00-15:00 row. Undo has to bring back *two*
     * rows split at 12:00 — something no single shift move can express, because the
     * boundary it would need no longer exists.
     */
    @Test
    fun `undo restores a merge`() {
        val alice = employee("Alice")
        val bob = employee("Bob")
        val moved = shift(alice.id, 9, 12)
        val bobsOwn = shift(bob.id, 12, 15)
        val saved = saveSchedule(listOf(moved, bobsOwn), listOf(alice.id, bob.id))
        val before = layoutOf(saved)

        service.modifyShift(
            businessId = testBusinessId,
            scheduleId = saved.id,
            shiftId = moved.id,
            newEmployeeId = bob.id,
            modifiedBy = "test"
        )

        // Precondition: the move really did merge the two rows into one block.
        val merged = scheduleRepository.findById(testBusinessId, saved.id)!!
        assertEquals(1, merged.shifts.size, "expected the adjacent rows to merge into one block")
        assertEquals(LocalTime.of(9, 0), merged.shifts.single().startTime)
        assertEquals(LocalTime.of(15, 0), merged.shifts.single().endTime)

        val result = service.undoLastChange(testBusinessId, saved.id, "test")

        assertTrue(result.restored)
        val restored = scheduleRepository.findById(testBusinessId, saved.id)!!
        assertEquals(2, restored.shifts.size, "the merged block must split back into two rows")
        assertEquals(before, layoutOf(restored))
    }

    @Test
    fun `undo restores a deleted shift`() {
        val alice = employee("Alice")
        val target = shift(alice.id, 9, 12)
        val saved = saveSchedule(listOf(target), listOf(alice.id))
        val before = layoutOf(saved)

        service.deleteShift(testBusinessId, saved.id, target.id, "test")
        assertEquals(0, scheduleRepository.findById(testBusinessId, saved.id)!!.shifts.size)

        val result = service.undoLastChange(testBusinessId, saved.id, "test")

        assertTrue(result.restored)
        assertEquals(before, layoutOf(scheduleRepository.findById(testBusinessId, saved.id)!!))
    }

    // One undo per edit: the snapshot is consumed, so the button can't flip a shift
    // back and forth forever.
    @Test
    fun `undo is spent after one use`() {
        val alice = employee("Alice")
        val bob = employee("Bob")
        val target = shift(alice.id, 9, 12)
        val saved = saveSchedule(listOf(target), listOf(alice.id, bob.id))

        service.modifyShift(
            businessId = testBusinessId,
            scheduleId = saved.id,
            shiftId = target.id,
            newEmployeeId = bob.id,
            modifiedBy = "test"
        )

        assertTrue(service.undoLastChange(testBusinessId, saved.id, "test").restored)
        assertFalse(service.canUndo(saved.id))

        val second = service.undoLastChange(testBusinessId, saved.id, "test")
        assertFalse(second.restored, "a spent undo must not restore anything")
    }

    @Test
    fun `undo reports nothing to restore on an untouched schedule`() {
        val alice = employee("Alice")
        val saved = saveSchedule(listOf(shift(alice.id, 9, 12)), listOf(alice.id))

        assertFalse(service.canUndo(saved.id))
        assertFalse(service.undoLastChange(testBusinessId, saved.id, "test").restored)
    }

    // Only the newest UNDO_DEPTH states are kept, so storage stays bounded however many
    // edits are made. At depth 1 the second edit displaces the first.
    @Test
    fun `only the most recent edit is retained`() {
        val alice = employee("Alice")
        val bob = employee("Bob")
        val target = shift(alice.id, 9, 12)
        val saved = saveSchedule(listOf(target), listOf(alice.id, bob.id))

        service.modifyShift(
            businessId = testBusinessId, scheduleId = saved.id, shiftId = target.id,
            newEmployeeId = bob.id, modifiedBy = "test"
        )
        val afterFirstEdit = layoutOf(scheduleRepository.findById(testBusinessId, saved.id)!!)

        service.modifyShift(
            businessId = testBusinessId, scheduleId = saved.id, shiftId = target.id,
            newEmployeeId = alice.id, modifiedBy = "test"
        )

        service.undoLastChange(testBusinessId, saved.id, "test")

        // Undo goes back one step, to the state after the first edit - not to the start.
        assertEquals(afterFirstEdit, layoutOf(scheduleRepository.findById(testBusinessId, saved.id)!!))
        assertFalse(service.canUndo(saved.id), "depth 1 retains exactly one step")
    }

    @Test
    fun `publishing clears any pending undo`() {
        val alice = employee("Alice")
        val bob = employee("Bob")
        val target = shift(alice.id, 9, 12)
        val saved = saveSchedule(listOf(target), listOf(alice.id, bob.id))

        service.modifyShift(
            businessId = testBusinessId, scheduleId = saved.id, shiftId = target.id,
            newEmployeeId = bob.id, modifiedBy = "test"
        )
        assertTrue(service.canUndo(saved.id))

        service.publishSchedule(testBusinessId, saved.id, "test")

        assertFalse(service.canUndo(saved.id), "a published schedule must not offer an undo")
    }

    @Test
    fun `undo is refused on a published schedule`() {
        val alice = employee("Alice")
        val saved = saveSchedule(listOf(shift(alice.id, 9, 12)), listOf(alice.id))
        service.publishSchedule(testBusinessId, saved.id, "test")

        assertThrows(IllegalStateException::class.java) {
            service.undoLastChange(testBusinessId, saved.id, "test")
        }
    }

    /**
     * A restore is never blocked by validation.
     *
     * Rules can be tightened between the edit and the undo, which retroactively makes
     * the recorded state non-compliant. Refusing would strand the manager in the state
     * they asked to leave, so the violations are reported on the schedule instead.
     */
    @Test
    fun `undo restores a state that now breaches a tightened rule`() {
        // 10h in one day, under the 12h cap the shifts were created against.
        val alice = employee("Alice")
        val bob = employee("Bob")
        val long = shift(alice.id, 9, 19)
        val saved = saveSchedule(listOf(long), listOf(alice.id, bob.id))

        service.modifyShift(
            businessId = testBusinessId, scheduleId = saved.id, shiftId = long.id,
            newEmployeeId = bob.id, modifiedBy = "test"
        )

        // Tighten Alice's daily cap below the shift she used to hold.
        employeeRepository.update(
            testBusinessId,
            alice.id,
            alice.copy(contract = alice.contract.copy(maxHoursPerDay = 8.0))
        )

        val result = service.undoLastChange(testBusinessId, saved.id, "test")

        assertTrue(result.restored, "the restore must not be refused")
        val restored = scheduleRepository.findById(testBusinessId, saved.id)!!
        assertEquals(alice.id, restored.shifts.single().employeeId)
        assertTrue(
            result.violations.any { it is ConstraintViolation.EmployeeDay || it is ConstraintViolation.Employee },
            "the now-breached rule should be reported: ${result.violations}"
        )
    }
}
