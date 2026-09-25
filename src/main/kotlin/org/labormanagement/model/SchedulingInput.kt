package org.labormanagement.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.util.UUID

data class ScheduleInput(
    val businessId: UUID,
    val employeeIds: List<UUID>,
    val laborCostBudget: Double,
    val schedulePeriod: SchedulePeriod,
    val optimizationObjective: OptimizationObjective = OptimizationObjective.BALANCED // Optional
)

/**
 * Wire shape for the POST /schedules/generate request body. Deliberately omits
 * businessId: the client never sends it in the body, only as the {businessId}
 * path segment, so ScheduleController combines this with the path value to
 * build the real (always-populated) ScheduleInput passed to ShiftScheduler.
 *
 * Also deliberately omits a labor budget - ShiftScheduler.generateSchedule
 * resolves the real cap itself from the business's saved Configurations
 * budget, so there's nothing for a caller to usefully supply here.
 */
data class ScheduleInputPayload(
    val employeeIds: List<UUID>,
    val schedulePeriod: SchedulePeriod,
    val optimizationObjective: OptimizationObjective = OptimizationObjective.BALANCED // Optional
) {
    fun toScheduleInput(businessId: UUID): ScheduleInput = ScheduleInput(
        businessId = businessId,
        employeeIds = employeeIds,
        laborCostBudget = Double.MAX_VALUE, // Overwritten by ShiftScheduler.generateSchedule
        schedulePeriod = schedulePeriod,
        optimizationObjective = optimizationObjective
    )
}

data class SchedulePeriod(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val operatingHours: Map<LocalDate, OperatingHours>
) {
    /**
     * Returns all dates in the scheduling period (inclusive)
     */
    fun getAllDates(): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var currentDate = startDate
        while (!currentDate.isAfter(endDate)) {
            dates.add(currentDate)
            currentDate = currentDate.plusDays(1)
        }
        return dates
    }

    /**
     * The duration of the scheduling period
     */
    val duration: Period
        get() = Period.between(startDate, endDate.plusDays(1))

    /**
     * Total number of days in the scheduling period (inclusive)
     */
    val totalDays: Int
        get() = getAllDates().size
}

/**
 * One continuous stretch of a day the business is open - "9:00 to 12:00".
 *
 * [closeTime] earlier than [openTime] means the stretch runs past midnight.
 */
data class OpenInterval(
    val openTime: LocalTime,
    val closeTime: LocalTime
)

/**
 * When the business is open on a date.
 *
 * [openTime] and [closeTime] are the overall span - first opening to last closing - and
 * are what everything that only needs the extent of the day reads: the timeline window,
 * events, the job service. [intervals] says which parts of that span are actually open,
 * for a day that closes over lunch.
 *
 * [intervals] is nullable rather than defaulted to a list because this crosses the wire
 * through Gson, which ignores Kotlin defaults: a request that leaves it out would
 * otherwise produce a non-null List field holding null. Read [openIntervals] instead.
 */
data class OperatingHours(
    val openTime: LocalTime,
    val closeTime: LocalTime,
    val intervals: List<OpenInterval>? = null
) {
    /**
     * The open stretches of the day, in order. A single stretch covering the whole span
     * when none were given, which is every day that does not close in the middle.
     *
     * Anything that turns hours into slots, demand or coverage has to walk these rather
     * than the span, or a closed lunch hour reads as an hour the business is trading.
     */
    fun openIntervals(): List<OpenInterval> =
        intervals?.takeIf { it.isNotEmpty() } ?: listOf(OpenInterval(openTime, closeTime))

    companion object {
        /** Hours made of [intervals], with the span taken from the first and last. */
        fun of(intervals: List<OpenInterval>): OperatingHours = OperatingHours(
            openTime = intervals.first().openTime,
            closeTime = intervals.last().closeTime,
            intervals = intervals.takeIf { it.size > 1 }
        )
    }
}
