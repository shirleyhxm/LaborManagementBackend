package org.labormanagement.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SortOrder
import org.labormanagement.database.SwapRequests
import org.labormanagement.model.SwapRequest
import org.labormanagement.model.SwapRequestStatus
import java.time.Instant
import java.util.UUID

/**
 * PostgreSQL-backed repository for shift-swap requests using Exposed ORM.
 */
class SwapRequestRepository {

    fun create(request: SwapRequest): SwapRequest = transaction {
        SwapRequests.insert {
            it[id] = request.id
            it[businessId] = request.businessId
            it[requestingEmployeeId] = request.requestingEmployeeId
            it[targetShiftId] = request.targetShiftId
            it[targetEmployeeId] = request.targetEmployeeId
            it[offeredShiftId] = request.offeredShiftId
            it[message] = request.message
            it[status] = request.status.name
            it[requestedAt] = request.requestedAt
            it[respondedAt] = request.respondedAt
            it[respondedBy] = request.respondedBy
        }
        request
    }

    fun findById(businessId: UUID, id: UUID): SwapRequest? = transaction {
        SwapRequests.selectAll()
            .where { (SwapRequests.id eq id) and (SwapRequests.businessId eq businessId) }
            .singleOrNull()?.toSwapRequest()
    }

    /** Requests targeting a shift this employee currently owns. */
    fun findIncomingForEmployee(businessId: UUID, employeeId: UUID): List<SwapRequest> = transaction {
        SwapRequests.selectAll()
            .where { (SwapRequests.businessId eq businessId) and (SwapRequests.targetEmployeeId eq employeeId) }
            .orderBy(SwapRequests.requestedAt, SortOrder.DESC)
            .map { it.toSwapRequest() }
    }

    /** Requests this employee initiated. */
    fun findOutgoingForEmployee(businessId: UUID, employeeId: UUID): List<SwapRequest> = transaction {
        SwapRequests.selectAll()
            .where { (SwapRequests.businessId eq businessId) and (SwapRequests.requestingEmployeeId eq employeeId) }
            .orderBy(SwapRequests.requestedAt, SortOrder.DESC)
            .map { it.toSwapRequest() }
    }

    fun findAllByBusiness(businessId: UUID): List<SwapRequest> = transaction {
        SwapRequests.selectAll().where { SwapRequests.businessId eq businessId }
            .orderBy(SwapRequests.requestedAt, SortOrder.DESC)
            .map { it.toSwapRequest() }
    }

    /** Pending requests already outstanding on a given shift - used to block duplicate requests. */
    fun findPendingForShift(shiftId: UUID): List<SwapRequest> = transaction {
        SwapRequests.selectAll()
            .where { (SwapRequests.targetShiftId eq shiftId) and (SwapRequests.status eq SwapRequestStatus.PENDING.name) }
            .map { it.toSwapRequest() }
    }

    fun updateStatus(id: UUID, status: SwapRequestStatus, respondedBy: String) = transaction {
        SwapRequests.update({ SwapRequests.id eq id }) {
            it[SwapRequests.status] = status.name
            it[respondedAt] = Instant.now()
            it[SwapRequests.respondedBy] = respondedBy
        }
    }

    private fun ResultRow.toSwapRequest(): SwapRequest = SwapRequest(
        id = this[SwapRequests.id],
        businessId = this[SwapRequests.businessId],
        requestingEmployeeId = this[SwapRequests.requestingEmployeeId],
        targetShiftId = this[SwapRequests.targetShiftId],
        targetEmployeeId = this[SwapRequests.targetEmployeeId],
        offeredShiftId = this[SwapRequests.offeredShiftId],
        message = this[SwapRequests.message],
        status = SwapRequestStatus.valueOf(this[SwapRequests.status]),
        requestedAt = this[SwapRequests.requestedAt],
        respondedAt = this[SwapRequests.respondedAt],
        respondedBy = this[SwapRequests.respondedBy]
    )
}
