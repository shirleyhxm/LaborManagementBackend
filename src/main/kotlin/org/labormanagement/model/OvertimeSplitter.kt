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
     *
     * Rebuilt rows reuse the ids of the rows their block was merged from, so recalculating
     * does not invalidate ids callers are already holding; a new id is minted only when a
     * split yields more rows than went in. Without this, every reassignment silently
     * retired the ids it had just been handed, and a client acting on a slightly stale
     * view — a second browser tab, a queued request, another user on the same draft —
     * would get "Shift not found" for a shift that plainly exists.
     */
    fun recalculateFor(employee: Employee, shifts: List<Shift>): List<Shift> {
        val (theirs, others) = shifts.partition { it.employeeId == employee.id }
        if (theirs.isEmpty()) return shifts

        val result = mutableListOf<Shift>()
        var hoursSoFar = 0.0

        for ((block, sourceIds) in mergeContiguous(theirs)) {
            // Hand the rebuilt rows the ids of the rows this block was merged from, in
            // order, falling back to a new id only when a split produces more rows than
            // went in. A block that re-splits at the same point therefore comes back with
            // exactly the ids it had.
            val reusableIds = ArrayDeque(sourceIds)

            result += split(
                employee = employee,
                date = block.date,
                startTime = block.startTime,
                endTime = block.endTime,
                hoursBefore = hoursSoFar,
                blockDurationHours = block.durationHours,
                idFor = { reusableIds.removeFirstOrNull() ?: UUID.randomUUID() }
            )
            hoursSoFar += block.durationHours
        }

        return others + result
    }

    /**
     * A contiguous block of work, together with the ids of the rows it was merged from.
     *
     * The ids are kept so the rebuilt rows can reuse them instead of minting new ones —
     * see [recalculateFor]. They are held in chronological order, so re-splitting a block
     * at the same point hands each row back the id it had before.
     */
    private data class Block(val shift: Shift, val sourceIds: List<UUID>)

    /**
     * Merges a single employee's shifts into contiguous blocks, joining rows that touch
     * on the same day regardless of pay rate.
     *
     * Deliberately ignores payRate/isOvertime, unlike ShiftScheduler's display-level
     * merge: the whole point is to rejoin rows that an earlier split separated so the
     * threshold can be reapplied to the original block.
     */
    private fun mergeContiguous(shifts: List<Shift>): List<Block> {
        val merged = mutableListOf<Block>()

        shifts.groupBy { it.date }.forEach { (_, sameDay) ->
            var current: Shift? = null
            var currentIds = mutableListOf<UUID>()

            for (shift in sameDay.sortedBy { it.startTime }) {
                when {
                    current == null -> {
                        current = shift
                        currentIds = mutableListOf(shift.id)
                    }
                    current.endTime == shift.startTime -> {
                        current = current.copy(endTime = shift.endTime)
                        currentIds.add(shift.id)
                    }
                    else -> {
                        merged.add(Block(current, currentIds))
                        current = shift
                        currentIds = mutableListOf(shift.id)
                    }
                }
            }

            current?.let { merged.add(Block(it, currentIds)) }
        }

        return merged.sortedWith(compareBy({ it.shift.date }, { it.shift.startTime }))
    }
}
