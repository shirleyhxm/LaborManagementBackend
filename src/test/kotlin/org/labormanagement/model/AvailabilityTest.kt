package org.labormanagement.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Availability windows, with particular attention to the ones that run to midnight.
 *
 * An "available until end of day" window is stored with an end of 00:00, because that
 * is what LocalTime normalizes 24:00 to. Compared naively, 00:00 is the *smallest*
 * LocalTime, so such a window matched nothing and the employee read as never available
 * — which surfaced as a drag-and-drop reassign being refused with an availability
 * conflict against an employee whose card plainly showed them as available.
 */
class AvailabilityTest {

    // A Friday, to match the weekly-recurring windows below.
    private val friday = LocalDate.of(2026, 9, 11)

    private fun weekly(start: String, end: String, day: DayOfWeek = DayOfWeek.FRIDAY) =
        Availability(
            availabilityType = AvailabilityType.WEEKLY_RECURRING,
            dayOfWeek = day,
            startTime = LocalTime.parse(start),
            endTime = LocalTime.parse(end)
        )

    @Test
    fun `covers a shift inside an ordinary window`() {
        val avail = weekly("08:00", "17:00")
        assertTrue(avail.isAvailableOn(friday, LocalTime.of(9, 0), LocalTime.of(12, 0)))
    }

    @Test
    fun `rejects a shift running past the end of an ordinary window`() {
        val avail = weekly("08:00", "17:00")
        assertFalse(avail.isAvailableOn(friday, LocalTime.of(16, 0), LocalTime.of(18, 0)))
    }

    // The regression: 08:00-00:00 means "available all day from 8am", and an afternoon
    // shift sits well inside it.
    @Test
    fun `covers an afternoon shift when the window runs to midnight`() {
        val avail = weekly("08:00", "00:00")
        assertTrue(avail.isAvailableOn(friday, LocalTime.of(15, 0), LocalTime.of(17, 0)))
    }

    @Test
    fun `covers a shift ending exactly at midnight`() {
        val avail = weekly("08:00", "00:00")
        assertTrue(avail.isAvailableOn(friday, LocalTime.of(22, 0), LocalTime.MIDNIGHT))
    }

    @Test
    fun `covers the last hour of a window that runs to midnight`() {
        val avail = weekly("21:00", "00:00")
        assertTrue(avail.isAvailableOn(friday, LocalTime.of(23, 0), LocalTime.MIDNIGHT))
    }

    @Test
    fun `still rejects a shift starting before a midnight-ending window opens`() {
        val avail = weekly("21:00", "00:00")
        assertFalse(avail.isAvailableOn(friday, LocalTime.of(20, 0), LocalTime.of(23, 0)))
    }

    // A start of 00:00 is genuinely the start of the day and must not be pushed to the end.
    @Test
    fun `covers an early shift when the window starts at midnight`() {
        val avail = weekly("00:00", "08:00")
        assertTrue(avail.isAvailableOn(friday, LocalTime.of(1, 0), LocalTime.of(5, 0)))
    }

    @Test
    fun `treats a midnight-to-midnight window as the whole day`() {
        val avail = weekly("00:00", "00:00")
        assertTrue(avail.isAvailableOn(friday, LocalTime.of(9, 0), LocalTime.of(17, 0)))
        assertTrue(avail.isAvailableOn(friday, LocalTime.of(23, 0), LocalTime.MIDNIGHT))
    }

    @Test
    fun `does not match a different day of week`() {
        val avail = weekly("08:00", "00:00", DayOfWeek.MONDAY)
        assertFalse(avail.isAvailableOn(friday, LocalTime.of(15, 0), LocalTime.of(17, 0)))
    }

    @Test
    fun `honours minute precision`() {
        val avail = weekly("08:30", "17:30")
        assertTrue(avail.isAvailableOn(friday, LocalTime.of(8, 30), LocalTime.of(17, 30)))
        assertFalse(avail.isAvailableOn(friday, LocalTime.of(8, 0), LocalTime.of(17, 0)))
    }
}
