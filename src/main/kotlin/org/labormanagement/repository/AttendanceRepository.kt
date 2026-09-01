package org.labormanagement.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.Attendances
import org.labormanagement.model.ClockRecord
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * PostgreSQL-backed attendance repository using Exposed ORM.
 * Manages clock in/out records with multi-tenant support.
 */
class AttendanceRepository {
    private val logger = LoggerFactory.getLogger(AttendanceRepository::class.java)

    /**
     * Create a new clock record
     */
    fun create(record: ClockRecord): ClockRecord = transaction {
        Attendances.insert {
            it[id] = record.id
            it[businessId] = record.businessId
            it[employeeId] = record.employeeId
            it[scheduleId] = record.scheduleId
            it[shiftId] = record.shiftId
            it[clockInTime] = record.clockInTime
            it[clockOutTime] = record.clockOutTime
            it[durationHours] = record.durationHours
            it[notes] = record.notes
            it[createdAt] = record.createdAt
            it[updatedAt] = record.updatedAt
        }

        record
    }

    /**
     * Find a clock record by ID with business verification
     */
    fun findById(businessId: UUID, id: UUID): ClockRecord? = transaction {
        Attendances.selectAll().where { (Attendances.id eq id) and (Attendances.businessId eq businessId) }
            .singleOrNull()
            ?.toClockRecord()
    }

    /**
     * Find all clock records for a specific business
     */
    fun findAllByBusiness(businessId: UUID): List<ClockRecord> = transaction {
        Attendances.selectAll().where { Attendances.businessId eq businessId }
            .orderBy(Attendances.clockInTime, SortOrder.DESC)
            .map { it.toClockRecord() }
    }

    /**
     * Find clock records by employee ID within a specific business
     */
    fun findByEmployeeId(businessId: UUID, employeeId: UUID): List<ClockRecord> = transaction {
        Attendances.selectAll().where {
            (Attendances.businessId eq businessId) and (Attendances.employeeId eq employeeId)
        }
            .orderBy(Attendances.clockInTime, SortOrder.DESC)
            .map { it.toClockRecord() }
    }

    /**
     * Find active clock record (clocked in, not clocked out) for an employee within a specific business
     */
    fun findActiveByEmployeeId(businessId: UUID, employeeId: UUID): ClockRecord? = transaction {
        Attendances.selectAll().where {
            (Attendances.businessId eq businessId) and
            (Attendances.employeeId eq employeeId) and
            (Attendances.clockOutTime.isNull())
        }
            .orderBy(Attendances.clockInTime, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toClockRecord()
    }

    /**
     * Every clock record for an employee, across every location they work at.
     *
     * Deliberately not business-scoped: attendance is a record of what a person
     * actually did, and someone working at two locations has one history. Each
     * record still carries its own businessId, so callers can say where it
     * happened.
     */
    fun findAllByEmployeeId(employeeId: UUID): List<ClockRecord> = transaction {
        Attendances.selectAll().where { Attendances.employeeId eq employeeId }
            .orderBy(Attendances.clockInTime, SortOrder.DESC)
            .map { it.toClockRecord() }
    }

    /**
     * The employee's open clock-in, wherever they made it.
     *
     * Someone can only be clocked in one place at a time, so this is not
     * business-scoped - otherwise clocking in at one location would leave them
     * looking clocked out at another, and they could open a second session.
     */
    fun findActiveByEmployeeIdAnyLocation(employeeId: UUID): ClockRecord? = transaction {
        Attendances.selectAll().where {
            (Attendances.employeeId eq employeeId) and
            (Attendances.clockOutTime.isNull())
        }
            .orderBy(Attendances.clockInTime, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toClockRecord()
    }

    /**
     * Clock records in a date range for an employee, across every location.
     * Used for stats, which should reflect the person's whole week.
     */
    fun findByDateRangeAllLocations(
        employeeId: UUID,
        startDate: Instant,
        endDate: Instant
    ): List<ClockRecord> = transaction {
        Attendances.selectAll().where {
            (Attendances.employeeId eq employeeId) and
            (Attendances.clockInTime greaterEq startDate) and
            (Attendances.clockInTime lessEq endDate)
        }
            .orderBy(Attendances.clockInTime, SortOrder.DESC)
            .map { it.toClockRecord() }
    }

    /**
     * Find clock records by shift ID within a specific business
     */
    fun findByShiftId(businessId: UUID, shiftId: UUID): List<ClockRecord> = transaction {
        Attendances.selectAll().where {
            (Attendances.businessId eq businessId) and (Attendances.shiftId eq shiftId)
        }
            .orderBy(Attendances.clockInTime, SortOrder.DESC)
            .map { it.toClockRecord() }
    }

    /**
     * Find clock records by schedule ID within a specific business
     */
    fun findByScheduleId(businessId: UUID, scheduleId: UUID): List<ClockRecord> = transaction {
        Attendances.selectAll().where {
            (Attendances.businessId eq businessId) and (Attendances.scheduleId eq scheduleId)
        }
            .orderBy(Attendances.clockInTime, SortOrder.DESC)
            .map { it.toClockRecord() }
    }

    /**
     * Find clock records within a date range for an employee within a specific business
     */
    fun findByDateRange(
        businessId: UUID,
        employeeId: UUID,
        startDate: Instant,
        endDate: Instant
    ): List<ClockRecord> = transaction {
        Attendances.selectAll().where {
            (Attendances.businessId eq businessId) and
            (Attendances.employeeId eq employeeId) and
            (Attendances.clockInTime greaterEq startDate) and
            (Attendances.clockInTime lessEq endDate)
        }
            .orderBy(Attendances.clockInTime, SortOrder.DESC)
            .map { it.toClockRecord() }
    }

    /**
     * Update a clock record with business verification
     */
    fun update(businessId: UUID, record: ClockRecord): ClockRecord? = transaction {
        val existing = Attendances.selectAll().where {
            (Attendances.id eq record.id) and (Attendances.businessId eq businessId)
        }.singleOrNull() ?: return@transaction null

        val updatedRecord = record.copy(updatedAt = Instant.now())

        Attendances.update({ Attendances.id eq record.id }) {
            it[Attendances.businessId] = updatedRecord.businessId
            it[employeeId] = updatedRecord.employeeId
            it[scheduleId] = updatedRecord.scheduleId
            it[shiftId] = updatedRecord.shiftId
            it[clockInTime] = updatedRecord.clockInTime
            it[clockOutTime] = updatedRecord.clockOutTime
            it[durationHours] = updatedRecord.durationHours
            it[notes] = updatedRecord.notes
            it[updatedAt] = updatedRecord.updatedAt
        }

        updatedRecord
    }

    /**
     * Delete a clock record with business verification
     */
    fun delete(businessId: UUID, id: UUID): Boolean = transaction {
        val deletedCount = Attendances.deleteWhere {
            (Attendances.id eq id) and (Attendances.businessId eq businessId)
        }
        deletedCount > 0
    }

    /**
     * Delete all clock records for a business (for testing)
     */
    fun deleteAllForBusiness(businessId: UUID) = transaction {
        Attendances.deleteWhere { Attendances.businessId eq businessId }
    }

    /**
     * Get total hours worked by an employee in a date range within a specific business
     */
    fun getTotalHoursWorked(
        businessId: UUID,
        employeeId: UUID,
        startDate: Instant,
        endDate: Instant
    ): Double = transaction {
        findByDateRange(businessId, employeeId, startDate, endDate)
            .mapNotNull { it.durationHours }
            .sum()
    }

    /**
     * Extension function to convert ResultRow to ClockRecord domain model
     */
    private fun ResultRow.toClockRecord(): ClockRecord {
        return ClockRecord(
            id = this[Attendances.id],
            businessId = this[Attendances.businessId],
            employeeId = this[Attendances.employeeId],
            scheduleId = this[Attendances.scheduleId],
            shiftId = this[Attendances.shiftId],
            clockInTime = this[Attendances.clockInTime],
            clockOutTime = this[Attendances.clockOutTime],
            durationHours = this[Attendances.durationHours],
            notes = this[Attendances.notes],
            createdAt = this[Attendances.createdAt],
            updatedAt = this[Attendances.updatedAt]
        )
    }
}
