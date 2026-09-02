package org.labormanagement.service

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.labormanagement.config.GsonConfig.createGson
import org.labormanagement.database.ScheduleUndoSnapshots
import org.labormanagement.model.Shift
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Keeps the last few pre-edit states of a draft schedule's shifts, so an edit can be
 * reversed exactly.
 *
 * Why a snapshot rather than replaying the edit backwards: re-deriving overtime can
 * merge a moved shift into a contiguous neighbour, and a merge is not reversible by
 * moving anything. The merged block carries hours that belonged to a different row, so
 * handing it back to the original employee gives them more hours than they started with
 * — routinely tripping their weekly cap and getting the "undo" refused, or worse,
 * silently succeeding with hours that were never theirs. Restoring the recorded rows
 * sidesteps the whole problem: the pre-edit shape is stated outright rather than
 * reconstructed.
 *
 * Storage is bounded by [UNDO_DEPTH] snapshots per schedule, trimmed on every write.
 */
class ScheduleUndoService {

    companion object {
        /**
         * How many pre-edit states to retain per schedule.
         *
         * One is enough for the "undo that last drag" case the UI offers. Raising it is
         * the only change needed to support a deeper stack server-side — the trim, the
         * lookup and the storage bound are all expressed in terms of this.
         */
        const val UNDO_DEPTH = 1
    }

    private val gson = createGson()

    /**
     * The stored form of a shift: exactly the columns the shifts table holds.
     *
     * Deliberately not the [Shift] model, whose `durationHours` and `laborCost` are
     * computed at construction. Serializing those would persist derived values that a
     * later restore could hand back stale — they are recomputed from the times and pay
     * rate on the way out instead.
     */
    private data class ShiftSnapshotDto(
        val id: String,
        val employeeId: String,
        val date: String,
        val startTime: String,
        val endTime: String,
        val payRate: Double,
        val isOvertime: Boolean
    )

    /**
     * Record the schedule's current shifts as the state an undo would return to.
     *
     * Call inside the same transaction as the edit, *before* applying it, so the two
     * either both land or neither does. Trims to [UNDO_DEPTH] as part of the same write.
     */
    fun recordSnapshot(scheduleId: UUID, shifts: List<Shift>, createdBy: String) {
        val nextSequence = (currentMaxSequence(scheduleId) ?: 0L) + 1

        ScheduleUndoSnapshots.insert {
            it[ScheduleUndoSnapshots.id] = UUID.randomUUID()
            it[ScheduleUndoSnapshots.scheduleId] = scheduleId
            it[sequence] = nextSequence
            it[ScheduleUndoSnapshots.shifts] = gson.toJson(shifts.map { shift -> shift.toDto() })
            it[createdAt] = Instant.now()
            it[ScheduleUndoSnapshots.createdBy] = createdBy
        }

        trim(scheduleId)
    }

    /**
     * The shifts to restore for the most recent undo, or null when nothing is retained.
     *
     * Consumes the snapshot: an undo is spent once used, so the caller cannot walk the
     * same edit backwards twice, and a restore never becomes its own undo target.
     */
    fun consumeLatestSnapshot(scheduleId: UUID): List<Shift>? {
        val row = ScheduleUndoSnapshots
            .selectAll()
            .where { ScheduleUndoSnapshots.scheduleId eq scheduleId }
            .orderBy(ScheduleUndoSnapshots.sequence, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?: return null

        val json = row[ScheduleUndoSnapshots.shifts]
        val sequence = row[ScheduleUndoSnapshots.sequence]

        ScheduleUndoSnapshots.deleteWhere {
            (ScheduleUndoSnapshots.scheduleId eq scheduleId) and
                (ScheduleUndoSnapshots.sequence eq sequence)
        }

        val dtos = gson.fromJson(json, Array<ShiftSnapshotDto>::class.java) ?: return emptyList()
        return dtos.map { it.toModel() }
    }

    /** Whether an undo is currently available for this schedule. */
    fun hasSnapshot(scheduleId: UUID): Boolean = currentMaxSequence(scheduleId) != null

    /**
     * Drop every retained state for a schedule.
     *
     * Used when publishing: a published schedule is immutable, so an undo that restored
     * draft shifts into it would be reinstating rows the schedule is no longer allowed
     * to change. Also used when a schedule is deleted, since the rows reference it.
     */
    fun clear(scheduleId: UUID) {
        ScheduleUndoSnapshots.deleteWhere { ScheduleUndoSnapshots.scheduleId eq scheduleId }
    }

    private fun currentMaxSequence(scheduleId: UUID): Long? =
        ScheduleUndoSnapshots
            .select(ScheduleUndoSnapshots.sequence.max())
            .where { ScheduleUndoSnapshots.scheduleId eq scheduleId }
            .singleOrNull()
            ?.get(ScheduleUndoSnapshots.sequence.max())

    /**
     * Keep only the newest [UNDO_DEPTH] snapshots.
     *
     * Expressed as a cutoff on the sequence rather than a NOT IN over the ids kept, so
     * the delete stays a single indexed range scan whatever the depth is set to.
     */
    private fun trim(scheduleId: UUID) {
        val keepFrom = ScheduleUndoSnapshots
            .selectAll()
            .where { ScheduleUndoSnapshots.scheduleId eq scheduleId }
            .orderBy(ScheduleUndoSnapshots.sequence, SortOrder.DESC)
            .limit(UNDO_DEPTH)
            .map { it[ScheduleUndoSnapshots.sequence] }
            .minOrNull()
            ?: return

        ScheduleUndoSnapshots.deleteWhere {
            (ScheduleUndoSnapshots.scheduleId eq scheduleId) and
                (ScheduleUndoSnapshots.sequence less keepFrom)
        }
    }

    private fun Shift.toDto() = ShiftSnapshotDto(
        id = id.toString(),
        employeeId = employeeId.toString(),
        date = date.toString(),
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        payRate = payRate,
        isOvertime = isOvertime
    )

    private fun ShiftSnapshotDto.toModel() = Shift(
        id = UUID.fromString(id),
        employeeId = UUID.fromString(employeeId),
        date = LocalDate.parse(date),
        startTime = LocalTime.parse(startTime),
        endTime = LocalTime.parse(endTime),
        payRate = payRate,
        isOvertime = isOvertime
    )
}
