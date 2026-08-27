package org.labormanagement.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * OvertimeSplitter is pure, so these tests need no database.
 */
class OvertimeSplitterTest {

    private val date: LocalDate = LocalDate.of(2026, 9, 6)

    private fun employee(
        threshold: Double = 40.0,
        normalRate: Double = 20.0,
        overtimeRate: Double = 30.0
    ) = Employee(
        businessId = UUID.randomUUID(),
        firstName = "Split",
        lastName = "Test",
        dateOfBirth = LocalDate.of(1990, 1, 1),
        normalPayRate = normalRate,
        overtimePayRate = overtimeRate,
        productivity = 1.0,
        contract = Contract(
            contractedHoursPerWeek = threshold,
            maxHoursPerWeek = 60.0,
            maxHoursPerDay = 12.0,
            overtimeThreshold = threshold,
            requiresBreak = false,
            breakDurationMinutes = 0,
            shiftLengthThresholdHours = 6
        ),
        availability = emptyList()
    )

    private fun shift(
        employee: Employee,
        date: LocalDate,
        start: String,
        end: String,
        isOvertime: Boolean = false
    ) = Shift(
        employeeId = employee.id,
        date = date,
        startTime = LocalTime.parse(start),
        endTime = LocalTime.parse(end),
        payRate = if (isOvertime) employee.overtimePayRate else employee.normalPayRate,
        isOvertime = isOvertime
    )

    @Test
    fun `block entirely below threshold is a single regular shift`() {
        val e = employee(threshold = 40.0)

        val result = OvertimeSplitter.split(
            employee = e,
            date = date,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(12, 0),
            hoursBefore = 10.0,
            blockDurationHours = 3.0
        )

        assertEquals(1, result.size)
        assertFalse(result[0].isOvertime)
        assertEquals(20.0, result[0].payRate)
    }

    @Test
    fun `block entirely above threshold is a single overtime shift`() {
        val e = employee(threshold = 40.0)

        val result = OvertimeSplitter.split(
            employee = e,
            date = date,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(12, 0),
            hoursBefore = 45.0,
            blockDurationHours = 3.0
        )

        assertEquals(1, result.size)
        assertTrue(result[0].isOvertime)
        assertEquals(30.0, result[0].payRate)
    }

    @Test
    fun `block starting exactly at the threshold is entirely overtime`() {
        val e = employee(threshold = 40.0)

        val result = OvertimeSplitter.split(
            employee = e,
            date = date,
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(19, 0),
            hoursBefore = 40.0,
            blockDurationHours = 1.0
        )

        assertEquals(1, result.size)
        assertTrue(result[0].isOvertime)
    }

    @Test
    fun `block crossing the threshold splits at the crossing point`() {
        val e = employee(threshold = 40.0)

        // 39h worked, then a 3h block: 1h regular then 2h overtime.
        val result = OvertimeSplitter.split(
            employee = e,
            date = date,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(12, 0),
            hoursBefore = 39.0,
            blockDurationHours = 3.0
        )

        assertEquals(2, result.size)

        val (regular, overtime) = result
        assertFalse(regular.isOvertime)
        assertEquals(LocalTime.of(9, 0), regular.startTime)
        assertEquals(LocalTime.of(10, 0), regular.endTime)
        assertEquals(20.0, regular.payRate)

        assertTrue(overtime.isOvertime)
        assertEquals(LocalTime.of(10, 0), overtime.startTime)
        assertEquals(LocalTime.of(12, 0), overtime.endTime)
        assertEquals(30.0, overtime.payRate)

        // The split must conserve both time and money.
        assertEquals(3.0, result.sumOf { it.durationHours })
        assertEquals(1 * 20.0 + 2 * 30.0, result.sumOf { it.laborCost })
    }

    @Test
    fun `split handles a fractional crossing point`() {
        val e = employee(threshold = 40.0)

        // 39.5h worked: the threshold falls half an hour into the block.
        val result = OvertimeSplitter.split(
            employee = e,
            date = date,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            hoursBefore = 39.5,
            blockDurationHours = 2.0
        )

        assertEquals(2, result.size)
        assertEquals(LocalTime.of(9, 30), result[0].endTime)
        assertEquals(LocalTime.of(9, 30), result[1].startTime)
        assertEquals(2.0, result.sumOf { it.durationHours })
    }

    @Test
    fun `recalculate splits the block where the employee crosses the threshold`() {
        val e = employee(threshold = 8.0)

        // Two 6h days: the second crosses the 8h threshold two hours in.
        val shifts = listOf(
            shift(e, date, "09:00", "15:00"),
            shift(e, date.plusDays(1), "09:00", "15:00")
        )

        val result = OvertimeSplitter.recalculateFor(e, shifts).sortedWith(
            compareBy({ it.date }, { it.startTime })
        )

        assertEquals(3, result.size)
        assertFalse(result[0].isOvertime)                          // day 1, all regular
        assertFalse(result[1].isOvertime)                          // day 2, 09:00-11:00
        assertEquals(LocalTime.of(11, 0), result[1].endTime)
        assertTrue(result[2].isOvertime)                           // day 2, 11:00-15:00
        assertEquals(LocalTime.of(11, 0), result[2].startTime)
    }

    @Test
    fun `recalculate collapses a split block back into one row when it no longer crosses`() {
        val e = employee(threshold = 40.0)

        // A block previously split at the threshold, for an employee who now has
        // no other hours at all — it should come back as a single regular shift.
        val shifts = listOf(
            shift(e, date, "09:00", "10:00", isOvertime = false),
            shift(e, date, "10:00", "12:00", isOvertime = true)
        )

        val result = OvertimeSplitter.recalculateFor(e, shifts)

        assertEquals(1, result.size)
        assertFalse(result[0].isOvertime)
        assertEquals(LocalTime.of(9, 0), result[0].startTime)
        assertEquals(LocalTime.of(12, 0), result[0].endTime)
        assertEquals(20.0, result[0].payRate)
    }

    @Test
    fun `recalculate leaves other employees untouched`() {
        val e = employee(threshold = 8.0)
        val other = employee(threshold = 8.0)

        val otherShift = shift(other, date, "09:00", "20:00", isOvertime = true)
        val shifts = listOf(shift(e, date, "09:00", "12:00"), otherShift)

        val result = OvertimeSplitter.recalculateFor(e, shifts)

        val untouched = result.single { it.employeeId == other.id }
        assertEquals(otherShift.id, untouched.id)
        assertTrue(untouched.isOvertime)
        assertEquals(LocalTime.of(20, 0), untouched.endTime)
    }

    @Test
    fun `recalculate does not join shifts separated by a gap`() {
        val e = employee(threshold = 40.0)

        val shifts = listOf(
            shift(e, date, "09:00", "12:00"),
            shift(e, date, "14:00", "17:00")   // two hours later, not contiguous
        )

        val result = OvertimeSplitter.recalculateFor(e, shifts)

        assertEquals(2, result.size)
        assertEquals(6.0, result.sumOf { it.durationHours })
    }

    @Test
    fun `recalculate keeps the shift id when the block does not split`() {
        val e = employee(threshold = 40.0)
        val original = shift(e, date, "09:00", "15:00")

        val result = OvertimeSplitter.recalculateFor(e, listOf(original))

        assertEquals(1, result.size)
        assertEquals(original.id, result[0].id)
    }

    @Test
    fun `recalculate keeps the shift id on the first row of a split block`() {
        val e = employee(threshold = 8.0)
        val day1 = shift(e, date, "09:00", "15:00")
        val day2 = shift(e, date.plusDays(1), "09:00", "15:00")   // crosses at 11:00

        val result = OvertimeSplitter.recalculateFor(e, listOf(day1, day2))
            .filter { it.date == day2.date }
            .sortedBy { it.startTime }

        assertEquals(2, result.size)
        // The regular portion keeps the original id; only the overtime row is new.
        assertEquals(day2.id, result[0].id)
        assertFalse(result[0].isOvertime)
        assertNotEquals(day2.id, result[1].id)
        assertTrue(result[1].isOvertime)
    }

    @Test
    fun `recalculate keeps ids stable across repeated runs`() {
        val e = employee(threshold = 8.0)
        val shifts = listOf(
            shift(e, date, "09:00", "15:00"),
            shift(e, date.plusDays(1), "09:00", "15:00")
        )

        val once = OvertimeSplitter.recalculateFor(e, shifts)
        val twice = OvertimeSplitter.recalculateFor(e, once)

        // Ids a caller obtained from the first pass still resolve after the second,
        // which is what stops a slightly stale client from 404ing.
        assertEquals(once.map { it.id }.toSet(), twice.map { it.id }.toSet())
    }

    @Test
    fun `recalculate collapsing a split block keeps the first row's id`() {
        val e = employee(threshold = 40.0)
        val regular = shift(e, date, "09:00", "10:00", isOvertime = false)
        val overtime = shift(e, date, "10:00", "12:00", isOvertime = true)

        val result = OvertimeSplitter.recalculateFor(e, listOf(regular, overtime))

        assertEquals(1, result.size)
        assertEquals(regular.id, result[0].id)
    }

    @Test
    fun `recalculate is idempotent`() {
        val e = employee(threshold = 8.0)
        val shifts = listOf(
            shift(e, date, "09:00", "15:00"),
            shift(e, date.plusDays(1), "09:00", "15:00")
        )

        val once = OvertimeSplitter.recalculateFor(e, shifts)
        val twice = OvertimeSplitter.recalculateFor(e, once)

        val shape = { list: List<Shift> ->
            list.map { Triple(it.startTime, it.endTime, it.isOvertime) }
                .sortedBy { it.first.toString() }
        }
        assertEquals(shape(once), shape(twice))
        assertEquals(once.sumOf { it.laborCost }, twice.sumOf { it.laborCost })
    }
}
