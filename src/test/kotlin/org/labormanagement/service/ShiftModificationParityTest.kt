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
import org.labormanagement.repository.TimeoffRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Pins drag-and-drop validation (ShiftModificationService) to what schedule generation
 * would allow.
 *
 * Generation resolves every rule in the solver; a manual move re-checks them through the
 * shared ShiftPlanValidator. Any rule the solver enforces but the move path doesn't would
 * be a way to hand-assemble, by dragging, a schedule the optimizer would have refused to
 * produce — so each test here covers one rule that used to be missing from the move path.
 */
class ShiftModificationParityTest {

    private val testBusinessId = UUID.fromString("00000000-0000-0000-0000-000000000001")
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

    // A Monday inside the schedule period used by every test here.
    private val monday = LocalDate.of(2026, 9, 7)

    @BeforeEach
    fun setup() {
        DatabaseFactory.resetDatabase()
        businessRepository = BusinessRepository()
        businessRepository.create(
            Business(id = testBusinessId, name = "Test Business", ownerId = "test-owner")
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

    private fun employee(
        name: String,
        availability: List<Availability>,
        maxHoursPerDay: Double = 12.0,
        maxHoursPerWeek: Double = 60.0
    ): Employee {
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
                maxHoursPerDay = maxHoursPerDay,
                overtimeThreshold = 40.0
            ),
            availability = availability
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
            name = "Parity test schedule",
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

    /**
     * Availability is resolved through Availability.isAvailableOn, which honours
     * availabilityType: a SPECIFIC_DATE row only grants availability on that one date.
     * The move path used to compare dayOfWeek/start/end inline and ignore the type, which
     * silently promoted SPECIFIC_DATE and DATE_RANGE rows to blanket weekly availability.
     */
    @Test
    fun `move against a SPECIFIC_DATE availability row for a different date is rejected`() {
        // Available only on a date that is NOT the shift's date, but the row's dayOfWeek
        // happens to be Monday.
        val unrelatedMonday = monday.plusDays(7)
        val receiver = employee(
            "Receiver",
            listOf(
                Availability(
                    availabilityType = AvailabilityType.SPECIFIC_DATE,
                    dayOfWeek = DayOfWeek.MONDAY,
                    specificDate = unrelatedMonday,
                    dateRange = null,
                    startTime = LocalTime.of(9, 0),
                    endTime = LocalTime.of(21, 0)
                )
            )
        )
        val giver = employee(
            "Giver",
            listOf(
                Availability(
                    AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null,
                    LocalTime.of(9, 0), LocalTime.of(21, 0)
                )
            )
        )

        val moved = shift(giver.id, 10, 14)
        val schedule = saveSchedule(listOf(moved), listOf(giver.id, receiver.id))

        // The optimizer would never place this: isAvailableOn is false for `monday`.
        assertFalse(
            receiver.availability.single().isAvailableOn(
                monday, LocalTime.of(10, 0), LocalTime.of(14, 0)
            ),
            "precondition: receiver is genuinely unavailable on the shift's date"
        )

        val result = service.modifyShift(
            businessId = testBusinessId,
            scheduleId = schedule.id,
            shiftId = moved.id,
            newEmployeeId = receiver.id,
            modifiedBy = "test"
        )

        assertFalse(result.isValid, "SPECIFIC_DATE availability must be honoured by the move path")
        assertTrue(
            result.violations.any { it.type == ViolationType.AVAILABILITY_CONFLICT },
            "expected AVAILABILITY_CONFLICT, got ${result.violations.map { it.type }}"
        )
    }

    /**
     * The rest-break rule is enforced on the merged block, matching the solver, so a move
     * that creates an over-long continuous run is rejected. This is the behaviour the
     * break fix added; it guards against regressing back to per-row checks.
     */
    @Test
    fun `move creating an over-long continuous block is rejected`() {
        ConstraintsService().updateComplianceRules(
            testBusinessId,
            org.labormanagement.dto.ComplianceRulesRequest(
                mealBreakRequired = true,
                mealBreakMinShiftHours = 6.0,
                mealBreakDuration = 30,
                minorLaborLawsEnabled = false,
                advanceNoticePeriod = 7
            )
        )

        val avail = listOf(
            Availability(
                AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null,
                LocalTime.of(9, 0), LocalTime.of(21, 0)
            )
        )
        val receiver = employee("Receiver", avail)
        val giver = employee("Giver", avail)

        // Receiver already works 13:00-18:00; moving 11:00-13:00 onto them makes 11:00-18:00.
        val existing = shift(receiver.id, 13, 18)
        val moved = shift(giver.id, 11, 13)
        val schedule = saveSchedule(listOf(existing, moved), listOf(giver.id, receiver.id))

        val result = service.modifyShift(
            businessId = testBusinessId,
            scheduleId = schedule.id,
            shiftId = moved.id,
            newEmployeeId = receiver.id,
            modifiedBy = "test"
        )

        assertFalse(result.isValid, "a 7h unbroken block must be rejected")
        assertTrue(
            result.violations.any { it.type == ViolationType.MISSING_BREAK },
            "expected MISSING_BREAK, got ${result.violations.map { it.type }}"
        )
    }

    /**
     * WorkingHoursRules.maxShiftLength is enforced by the solver
     * (addWorkingHoursRulesConstraints). The move path reads the same saved rules, so a
     * drag can no longer produce a block longer than the configured maximum shift.
     */
    @Test
    fun `move exceeding maxShiftLength is rejected`() {
        ConstraintsService().updateWorkingHoursRules(
            testBusinessId,
            org.labormanagement.dto.WorkingHoursRulesRequest(
                maxHoursPerWeek = 60.0,
                maxOvertimeHours = 20.0,
                minRestBetweenShifts = 11.0,
                maxConsecutiveDays = 6,
                maxShiftLength = 5.0,
                minShiftLength = 2.0
            )
        )

        val avail = listOf(
            Availability(
                AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null,
                LocalTime.of(9, 0), LocalTime.of(21, 0)
            )
        )
        val receiver = employee("Receiver", avail)
        val giver = employee("Giver", avail)

        // Receiver works 13:00-17:00 (4h, under the 5h cap); the move makes it 11:00-17:00 (6h).
        val existing = shift(receiver.id, 13, 17)
        val moved = shift(giver.id, 11, 13)
        val schedule = saveSchedule(listOf(existing, moved), listOf(giver.id, receiver.id))

        val result = service.modifyShift(
            businessId = testBusinessId,
            scheduleId = schedule.id,
            shiftId = moved.id,
            newEmployeeId = receiver.id,
            modifiedBy = "test"
        )

        assertFalse(result.isValid, "maxShiftLength (5h) must be enforced on a 6h merged block")
        assertTrue(
            result.violations.any { it.type == ViolationType.CONTRACT_HOURS_EXCEEDED },
            "expected CONTRACT_HOURS_EXCEEDED, got ${result.violations.map { it.type }}"
        )
    }

    /**
     * Contract.maxHoursPerDay is enforced per *day* by the solver
     * (addMaxHoursPerDayConstraints sums every slot on the date). The move path used to
     * compare only the moved shift's own duration, so hours already worked that day didn't
     * count and two individually-legal shifts could break the cap together.
     */
    @Test
    fun `move exceeding daily contract hours across several shifts is rejected`() {
        val avail = listOf(
            Availability(
                AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null,
                LocalTime.of(6, 0), LocalTime.of(23, 0)
            )
        )
        // 6h/day cap.
        val receiver = employee("Receiver", avail, maxHoursPerDay = 6.0)
        val giver = employee("Giver", avail, maxHoursPerDay = 6.0)

        // Receiver already works 5h; the moved 3h shift takes them to 8h on the day,
        // well past the 6h cap — but each individual shift is under it.
        val existing = shift(receiver.id, 8, 13)
        val moved = shift(giver.id, 14, 17)
        val schedule = saveSchedule(listOf(existing, moved), listOf(giver.id, receiver.id))

        val result = service.modifyShift(
            businessId = testBusinessId,
            scheduleId = schedule.id,
            shiftId = moved.id,
            newEmployeeId = receiver.id,
            modifiedBy = "test"
        )

        assertFalse(result.isValid, "8h on one day against a 6h/day cap must be rejected")
        assertTrue(
            result.violations.any { it.type == ViolationType.CONTRACT_HOURS_EXCEEDED },
            "expected CONTRACT_HOURS_EXCEEDED, got ${result.violations.map { it.type }}"
        )
    }

    /**
     * The solver drops dates with approved time off from the availability matrix entirely.
     * The move path consults the same requests, so a shift can't be dragged onto someone
     * who is on approved leave that day.
     */
    @Test
    fun `move onto an employee with approved time off is rejected`() {
        val avail = listOf(
            Availability(
                AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null,
                LocalTime.of(9, 0), LocalTime.of(21, 0)
            )
        )
        val receiver = employee("Receiver", avail)
        val giver = employee("Giver", avail)

        TimeoffRepository().create(
            TimeoffRequest(
                businessId = testBusinessId,
                employeeId = receiver.id,
                startDate = monday,
                endDate = monday,
                reason = "Annual leave",
                status = TimeoffStatus.APPROVED
            )
        )

        val moved = shift(giver.id, 10, 14)
        val schedule = saveSchedule(listOf(moved), listOf(giver.id, receiver.id))

        val result = service.modifyShift(
            businessId = testBusinessId,
            scheduleId = schedule.id,
            shiftId = moved.id,
            newEmployeeId = receiver.id,
            modifiedBy = "test"
        )

        assertFalse(result.isValid, "a shift must not land on approved time off")
        assertTrue(
            result.violations.any { it.type == ViolationType.AVAILABILITY_CONFLICT },
            "expected AVAILABILITY_CONFLICT, got ${result.violations.map { it.type }}"
        )
    }

    /**
     * Validation covers the whole resulting plan, so it has to be scoped to the people the
     * edit touches. A draft can legitimately carry violations elsewhere — generation
     * records them rather than refusing to produce a schedule — and letting a stranger's
     * non-compliant day block an unrelated move would make the grid uneditable.
     */
    @Test
    fun `a pre-existing violation for an uninvolved employee does not block the move`() {
        ConstraintsService().updateComplianceRules(
            testBusinessId,
            org.labormanagement.dto.ComplianceRulesRequest(
                mealBreakRequired = true,
                mealBreakMinShiftHours = 6.0,
                mealBreakDuration = 30,
                minorLaborLawsEnabled = false,
                advanceNoticePeriod = 7
            )
        )

        val avail = listOf(
            Availability(
                AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null,
                LocalTime.of(6, 0), LocalTime.of(23, 0)
            )
        )
        val receiver = employee("Receiver", avail)
        val giver = employee("Giver", avail)
        val bystander = employee("Bystander", avail)

        // The bystander already works an unbroken 8h block: a MISSING_BREAK violation that
        // has nothing to do with the move being attempted.
        val bystanderShift = shift(bystander.id, 9, 17)
        val moved = shift(giver.id, 10, 13)
        val schedule = saveSchedule(
            listOf(bystanderShift, moved),
            listOf(giver.id, receiver.id, bystander.id)
        )

        val result = service.modifyShift(
            businessId = testBusinessId,
            scheduleId = schedule.id,
            shiftId = moved.id,
            newEmployeeId = receiver.id,
            modifiedBy = "test"
        )

        assertTrue(
            result.isValid,
            "someone else's pre-existing violation must not block this move: " +
                "${result.violations.map { it.description }}"
        )
    }

    /**
     * The same protection, for the employees the edit *does* touch. Rules get tightened
     * after schedules are saved, which retroactively makes them non-compliant; if every
     * such violation were blamed on the next edit, those drafts could never be corrected
     * by dragging. Only what the move newly breaks should stop it.
     */
    @Test
    fun `a pre-existing violation on an untouched day of an involved employee does not block the move`() {
        ConstraintsService().updateComplianceRules(
            testBusinessId,
            org.labormanagement.dto.ComplianceRulesRequest(
                mealBreakRequired = true,
                mealBreakMinShiftHours = 6.0,
                mealBreakDuration = 30,
                minorLaborLawsEnabled = false,
                advanceNoticePeriod = 7
            )
        )

        val avail = listOf(
            Availability(
                AvailabilityType.WEEKLY_RECURRING, DayOfWeek.MONDAY, null, null,
                LocalTime.of(6, 0), LocalTime.of(23, 0)
            ),
            Availability(
                AvailabilityType.WEEKLY_RECURRING, DayOfWeek.TUESDAY, null, null,
                LocalTime.of(6, 0), LocalTime.of(23, 0)
            )
        )
        val receiver = employee("Receiver", avail)
        val giver = employee("Giver", avail)

        // The receiver already breaks the break rule on Tuesday - an 8h unbroken block that
        // predates this edit and has nothing to do with the Monday shift being moved.
        val tuesday = monday.plusDays(1)
        val preExisting = Shift(
            id = UUID.randomUUID(),
            employeeId = receiver.id,
            date = tuesday,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            payRate = 15.0
        )
        val moved = shift(giver.id, 10, 13)
        val schedule = saveSchedule(listOf(preExisting, moved), listOf(giver.id, receiver.id))

        val result = service.modifyShift(
            businessId = testBusinessId,
            scheduleId = schedule.id,
            shiftId = moved.id,
            newEmployeeId = receiver.id,
            modifiedBy = "test"
        )

        assertTrue(
            result.isValid,
            "the receiver's pre-existing Tuesday violation must not block a Monday move: " +
                "${result.violations.map { it.description }}"
        )
    }
}
