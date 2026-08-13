package org.labormanagement.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TimeUtilTest {

    @Test
    fun `parses 24-00 as midnight`() {
        assertEquals(LocalTime.MIDNIGHT, parseFlexibleTime("24:00"))
    }

    @Test
    fun `parses 24-00-00 as midnight`() {
        assertEquals(LocalTime.MIDNIGHT, parseFlexibleTime("24:00:00"))
    }

    @Test
    fun `parses normal times unaffected`() {
        assertEquals(LocalTime.of(9, 30), parseFlexibleTime("09:30"))
        assertEquals(LocalTime.of(23, 59), parseFlexibleTime("23:59"))
        assertEquals(LocalTime.MIDNIGHT, parseFlexibleTime("00:00"))
    }

    @Test
    fun `works with a custom formatter`() {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        assertEquals(LocalTime.MIDNIGHT, parseFlexibleTime("24:00", formatter))
        assertEquals(LocalTime.of(14, 15), parseFlexibleTime("14:15", formatter))
    }
}
