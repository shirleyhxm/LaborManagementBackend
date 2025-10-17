package org.labormanagement.model

import java.time.DayOfWeek
import java.time.LocalTime

data class Availability(
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime
) {
    fun canWork(shift: Shift): Boolean {
        return shift.dayOfWeek == this.dayOfWeek &&
                shift.startTime >= this.startTime &&
                shift.endTime <= this.endTime
    }
}
