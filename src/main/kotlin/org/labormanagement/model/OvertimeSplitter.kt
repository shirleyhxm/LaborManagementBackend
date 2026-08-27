package org.labormanagement.model

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Single source of truth for splitting a continuous block of work into regular- and
 * overtime-paid shifts.
 *
 * Overtime is a property of an employee's accumulated hours, not of an individual shift:
 * a block is regular up to the point where the employee reaches their weekly overtime
 * threshold and overtime after it. A block that straddles the threshold is therefore
 * stored as two rows so each portion bills at the correct rate.
 *
 * All three paths that create shifts — CP-SAT conversion, the greedy fallback, and
 * shift reassignment — go through here so they produce identical shapes for identical
 * inputs. Previously each implemented its own rule and they disagreed: only the CP-SAT
 * path split, so the same schedule billed differently depending on which solver ran.
 */
object OvertimeSplitter {

    /**
     * Splits one continuous block of work into one or two shifts.
     *
     * @param hoursBefore hours the employee has already worked in the period before this
     *   block begins — the value that decides where (or whether) the threshold falls.
     * @return one shift when the block sits wholly on one side of the threshold, or two
     *   (regular then overtime) when it crosses.
     */
    fun split(
        employee: Employee,
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        hoursBefore: Double,
        blockDurationHours: Double,
        idFor: () -> UUID = { UUID.randomUUID() }
    ): List<Shift> {
        val threshold = employee.contract.overtimeThreshold
        val hoursAfter = hoursBefore + blockDurationHours

        // Crosses the threshold: bill the hours on each side at their own rate.
        if (hoursBefore < threshold && hoursAfter > threshold) {
            val regularHours = threshold - hoursBefore
            val overtimeStartTime = startTime.plusMinutes((regularHours * 60).toLong())

            // Guard against a split that would produce a zero-length row, which can
            // happen when the threshold lands exactly on the block boundary after
            // rounding to whole minutes.
            if (overtimeStartTime > startTime && overtimeStartTime < endTime) {
                return listOf(
                    Shift(
                        id = idFor(),
                        employeeId = employee.id,
                        date = date,
                        startTime = startTime,
                        endTime = overtimeStartTime,
                        payRate = employee.normalPayRate,
                        isOvertime = false
                    ),
                    Shift(
                        id = idFor(),
                        employeeId = employee.id,
                        date = date,
                        startTime = overtimeStartTime,
                        endTime = endTime,
                        payRate = employee.overtimePayRate,
                        isOvertime = true
                    )
                )
            }
        }

        val isOvertime = hoursBefore >= threshold
        return listOf(
            Shift(
                id = idFor(),
                employeeId = employee.id,
                date = date,
                startTime = startTime,
                endTime = endTime,
                payRate = if (isOvertime) employee.overtimePayRate else employee.normalPayRate,
                isOvertime = isOvertime
            )
        )
    }

    /**
     * Re-derives regular/overtime rows for every shift belonging to [employee].
     *
     * Merges the employee's shifts into contiguous blocks first, so a block that was
     * previously split at the threshold is reconsidered as a whole. That makes splitting
     * reversible: if the employee no longer crosses the threshold during that block, it
     * comes back as a single row.
     *
     * Shifts are walked in chronological order and each block is billed against the hours
     * accumulated before it.
     */
    fun recalculateFor(employee: Employee, shifts: List<Shift>): List<Shift> {
        val (theirs, others) = shifts.partition { it.employeeId == employee.id }
        if (theirs.isEmpty()) return shifts

        val result = mutableListOf<Shift>()
        var hoursSoFar = 0.0

        for (block in mergeContiguous(theirs)) {
            result += split(
                employee = employee,
                date = block.date,
                startTime = block.startTime,
                endTime = block.endTime,
                hoursBefore = hoursSoFar,
                blockDurationHours = block.durationHours
            )
            hoursSoFar += block.durationHours
        }

        return others + result
    }

    /**
     * Merges a single employee's shifts into contiguous blocks, joining rows that touch
     * on the same day regardless of pay rate.
     *
     * Deliberately ignores payRate/isOvertime, unlike ShiftScheduler's display-level
     * merge: the whole point is to rejoin rows that an earlier split separated so the
     * threshold can be reapplied to the original block.
     */
    private fun mergeContiguous(shifts: List<Shift>): List<Shift> {
        val merged = mutableListOf<Shift>()

        shifts.groupBy { it.date }.forEach { (_, sameDay) ->
            var current: Shift? = null

            for (shift in sameDay.sortedBy { it.startTime }) {
                current = when {
                    current == null -> shift
                    current.endTime == shift.startTime ->
                        current.copy(endTime = shift.endTime)
                    else -> {
                        merged.add(current)
                        shift
                    }
                }
            }

            current?.let { merged.add(it) }
        }

        return merged.sortedWith(compareBy({ it.date }, { it.startTime }))
    }
}
