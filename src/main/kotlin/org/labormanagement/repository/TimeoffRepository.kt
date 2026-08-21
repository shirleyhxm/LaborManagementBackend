package org.labormanagement.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.database.Timeoffs
import org.labormanagement.model.TimeoffRequest
import org.labormanagement.model.TimeoffStatus
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

/**
 * PostgreSQL-backed timeoff repository using Exposed ORM.
 * Manages timeoff requests with multi-tenant support.
 */
class TimeoffRepository {
    private val logger = LoggerFactory.getLogger(TimeoffRepository::class.java)

    /**
     * Create a new timeoff request
     */
    fun create(request: TimeoffRequest): TimeoffRequest = transaction {
        Timeoffs.insert {
            it[id] = request.id
            it[businessId] = request.businessId
            it[employeeId] = request.employeeId
            it[startDate] = request.startDate
            it[endDate] = request.endDate
            it[reason] = request.reason
            it[status] = request.status.name
            it[requestedAt] = request.requestedAt
            it[reviewedAt] = request.reviewedAt
            it[reviewedBy] = request.reviewedBy
            it[reviewNotes] = request.reviewNotes
            it[totalDays] = request.totalDays
        }

        request
    }

    /**
     * Find a timeoff request by ID with business verification
     */
    fun findById(businessId: UUID, id: UUID): TimeoffRequest? = transaction {
        Timeoffs.selectAll().where { (Timeoffs.id eq id) and (Timeoffs.businessId eq businessId) }
            .singleOrNull()
            ?.toTimeoffRequest()
    }

    /**
     * Find all timeoff requests for a specific business
     */
    fun findAllByBusiness(businessId: UUID): List<TimeoffRequest> = transaction {
        Timeoffs.selectAll().where { Timeoffs.businessId eq businessId }
            .orderBy(Timeoffs.requestedAt, SortOrder.DESC)
            .map { it.toTimeoffRequest() }
    }

    /**
     * Find timeoff requests by employee ID within a specific business
     */
    fun findByEmployeeId(businessId: UUID, employeeId: UUID): List<TimeoffRequest> = transaction {
        Timeoffs.selectAll().where {
            (Timeoffs.businessId eq businessId) and (Timeoffs.employeeId eq employeeId)
        }
            .orderBy(Timeoffs.requestedAt, SortOrder.DESC)
            .map { it.toTimeoffRequest() }
    }

    /**
     * Find timeoff requests by status within a specific business
     */
    fun findByStatus(businessId: UUID, status: TimeoffStatus): List<TimeoffRequest> = transaction {
        Timeoffs.selectAll().where {
            (Timeoffs.businessId eq businessId) and (Timeoffs.status eq status.name)
        }
            .orderBy(Timeoffs.requestedAt, SortOrder.DESC)
            .map { it.toTimeoffRequest() }
    }

    /**
     * Find pending timeoff requests for an employee within a specific business
     */
    fun findPendingByEmployeeId(businessId: UUID, employeeId: UUID): List<TimeoffRequest> = transaction {
        Timeoffs.selectAll().where {
            (Timeoffs.businessId eq businessId) and
            (Timeoffs.employeeId eq employeeId) and
            (Timeoffs.status eq TimeoffStatus.PENDING.name)
        }
            .orderBy(Timeoffs.requestedAt, SortOrder.DESC)
            .map { it.toTimeoffRequest() }
    }

    /**
     * Find approved timeoff requests for an employee in a date range within a specific business
     */
    fun findApprovedByEmployeeIdAndDateRange(
        businessId: UUID,
        employeeId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<TimeoffRequest> = transaction {
        Timeoffs.selectAll().where {
            (Timeoffs.businessId eq businessId) and
            (Timeoffs.employeeId eq employeeId) and
            (Timeoffs.status eq TimeoffStatus.APPROVED.name)
        }
            .map { it.toTimeoffRequest() }
            .filter { request ->
                // Check if date ranges overlap
                !(request.endDate.isBefore(startDate) || request.startDate.isAfter(endDate))
            }
    }

    /**
     * Find all approved timeoff requests for a business that overlap a date range,
     * across all employees - used by the scheduler to exclude employees from
     * shifts on days they're approved to be off, without an N+1 per-employee query.
     */
    fun findApprovedByBusinessAndDateRange(
        businessId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<TimeoffRequest> = transaction {
        Timeoffs.selectAll().where {
            (Timeoffs.businessId eq businessId) and
            (Timeoffs.status eq TimeoffStatus.APPROVED.name) and
            (Timeoffs.startDate lessEq endDate) and
            (Timeoffs.endDate greaterEq startDate)
        }
            .map { it.toTimeoffRequest() }
    }

    /**
     * Check if employee has approved timeoff on a specific date within a specific business
     */
    fun hasApprovedTimeoffOnDate(businessId: UUID, employeeId: UUID, date: LocalDate): Boolean = transaction {
        !Timeoffs.selectAll().where {
            (Timeoffs.businessId eq businessId) and
            (Timeoffs.employeeId eq employeeId) and
            (Timeoffs.status eq TimeoffStatus.APPROVED.name) and
            (Timeoffs.startDate lessEq date) and
            (Timeoffs.endDate greaterEq date)
        }.empty()
    }

    /**
     * Update a timeoff request with business verification
     */
    fun update(businessId: UUID, request: TimeoffRequest): TimeoffRequest? = transaction {
        val existing = Timeoffs.selectAll().where {
            (Timeoffs.id eq request.id) and (Timeoffs.businessId eq businessId)
        }.singleOrNull() ?: return@transaction null

        Timeoffs.update({ Timeoffs.id eq request.id }) {
            it[Timeoffs.businessId] = request.businessId
            it[employeeId] = request.employeeId
            it[startDate] = request.startDate
            it[endDate] = request.endDate
            it[reason] = request.reason
            it[status] = request.status.name
            it[requestedAt] = request.requestedAt
            it[reviewedAt] = request.reviewedAt
            it[reviewedBy] = request.reviewedBy
            it[reviewNotes] = request.reviewNotes
            it[totalDays] = request.totalDays
        }

        request
    }

    /**
     * Delete a timeoff request with business verification
     */
    fun delete(businessId: UUID, id: UUID): Boolean = transaction {
        val deletedCount = Timeoffs.deleteWhere {
            (Timeoffs.id eq id) and (Timeoffs.businessId eq businessId)
        }
        deletedCount > 0
    }

    /**
     * Delete all timeoff requests for a business (for testing)
     */
    fun deleteAllForBusiness(businessId: UUID) = transaction {
        Timeoffs.deleteWhere { Timeoffs.businessId eq businessId }
    }

    /**
     * Extension function to convert ResultRow to TimeoffRequest domain model
     */
    private fun ResultRow.toTimeoffRequest(): TimeoffRequest {
        return TimeoffRequest(
            id = this[Timeoffs.id],
            businessId = this[Timeoffs.businessId],
            employeeId = this[Timeoffs.employeeId],
            startDate = this[Timeoffs.startDate],
            endDate = this[Timeoffs.endDate],
            reason = this[Timeoffs.reason],
            status = TimeoffStatus.valueOf(this[Timeoffs.status]),
            requestedAt = this[Timeoffs.requestedAt],
            reviewedAt = this[Timeoffs.reviewedAt],
            reviewedBy = this[Timeoffs.reviewedBy],
            reviewNotes = this[Timeoffs.reviewNotes],
            totalDays = this[Timeoffs.totalDays]
        )
    }
}
