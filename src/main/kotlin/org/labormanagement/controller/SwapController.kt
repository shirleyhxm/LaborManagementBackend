package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.labormanagement.dto.CreateSwapRequestRequest
import org.labormanagement.dto.SwapRequestResponse
import org.labormanagement.dto.SwapRequestsListResponse
import org.labormanagement.model.Employee
import org.labormanagement.model.SwapRequest
import org.labormanagement.model.SwapRequestStatus
import org.labormanagement.model.UserRole
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.ScheduleRepository
import org.labormanagement.repository.SwapRequestRepository
import org.labormanagement.service.effectiveRoleOr
import java.util.UUID

/**
 * Shift-swap requests: an employee asks to take over a specific coworker's
 * published shift (donation/pickup model - not a mutual trade in v1). The
 * shift's current owner accepts or declines; accepting reassigns the shift.
 */
class SwapController(
    private val swapRequestRepository: SwapRequestRepository,
    private val scheduleRepository: ScheduleRepository,
    private val employeeRepository: EmployeeRepository
) {

    fun Route.swapRoutes() {
        route("/api/businesses/{businessId}/swap-requests") {
        authenticate("auth-jwt") {

            post {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }

                    val callerUserId = call.callerUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    val callerEmployee = requireCallerEmployee(callerUserId, businessId)
                    if (callerEmployee == null) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No employee record linked to this account"))
                        return@post
                    }

                    val request = call.receive<CreateSwapRequestRequest>()
                    val shiftId = try {
                        UUID.fromString(request.targetShiftId)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid targetShiftId"))
                        return@post
                    }

                    val targetShift = scheduleRepository.findShiftById(businessId, shiftId)
                    if (targetShift == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Shift not found"))
                        return@post
                    }
                    if (targetShift.employeeId == callerEmployee.id) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot request a swap for your own shift"))
                        return@post
                    }

                    val existingPending = swapRequestRepository.findPendingForShift(shiftId)
                        .any { it.requestingEmployeeId == callerEmployee.id }
                    if (existingPending) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "You already have a pending request for this shift"))
                        return@post
                    }

                    val swapRequest = swapRequestRepository.create(
                        SwapRequest(
                            businessId = businessId,
                            requestingEmployeeId = callerEmployee.id,
                            targetShiftId = shiftId,
                            targetEmployeeId = targetShift.employeeId,
                            message = request.message
                        )
                    )

                    call.respond(HttpStatusCode.Created, swapRequest.toResponse(employeeRepository, scheduleRepository, businessId))
                } catch (e: Exception) {
                    call.application.log.error("Failed to create swap request", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create swap request: ${e.message}"))
                }
            }

            get {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@get
                    }

                    val callerUserId = call.callerUserId()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    val callerEmployee = requireCallerEmployee(callerUserId, businessId)
                    if (callerEmployee == null) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No employee record linked to this account"))
                        return@get
                    }

                    val incoming = swapRequestRepository.findIncomingForEmployee(businessId, callerEmployee.id)
                        .map { it.toResponse(employeeRepository, scheduleRepository, businessId) }
                    val outgoing = swapRequestRepository.findOutgoingForEmployee(businessId, callerEmployee.id)
                        .map { it.toResponse(employeeRepository, scheduleRepository, businessId) }

                    call.respond(HttpStatusCode.OK, SwapRequestsListResponse(incoming, outgoing))
                } catch (e: Exception) {
                    call.application.log.error("Failed to list swap requests", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to list swap requests: ${e.message}"))
                }
            }

            get("/all") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@get
                    }

                    val callerRole = call.callerRole()
                    if (callerRole != UserRole.ADMIN && callerRole != UserRole.MANAGER) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Requires ADMIN or MANAGER role"))
                        return@get
                    }

                    val all = swapRequestRepository.findAllByBusiness(businessId)
                        .map { it.toResponse(employeeRepository, scheduleRepository, businessId) }
                    call.respond(HttpStatusCode.OK, all)
                } catch (e: Exception) {
                    call.application.log.error("Failed to list all swap requests", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to list all swap requests: ${e.message}"))
                }
            }

            post("/{id}/approve") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }
                    val swapId = call.parameters["id"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (swapId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid swap request ID"))
                        return@post
                    }

                    val callerRole = call.callerRole()
                    if (callerRole != UserRole.ADMIN && callerRole != UserRole.MANAGER) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Requires ADMIN or MANAGER role"))
                        return@post
                    }
                    val callerUserId = call.callerUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                    val swapRequest = swapRequestRepository.findById(businessId, swapId)
                    if (swapRequest == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Swap request not found"))
                        return@post
                    }
                    if (swapRequest.status != SwapRequestStatus.PENDING_APPROVAL) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "This request is not awaiting approval"))
                        return@post
                    }

                    scheduleRepository.reassignShift(swapRequest.targetShiftId, swapRequest.requestingEmployeeId)
                    swapRequestRepository.updateReview(swapId, SwapRequestStatus.APPROVED, callerUserId)

                    call.respond(HttpStatusCode.OK, mapOf("status" to "APPROVED"))
                } catch (e: Exception) {
                    call.application.log.error("Failed to approve swap request", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to approve swap request: ${e.message}"))
                }
            }

            post("/{id}/deny") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }
                    val swapId = call.parameters["id"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (swapId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid swap request ID"))
                        return@post
                    }

                    val callerRole = call.callerRole()
                    if (callerRole != UserRole.ADMIN && callerRole != UserRole.MANAGER) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Requires ADMIN or MANAGER role"))
                        return@post
                    }
                    val callerUserId = call.callerUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                    val swapRequest = swapRequestRepository.findById(businessId, swapId)
                    if (swapRequest == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Swap request not found"))
                        return@post
                    }
                    if (swapRequest.status != SwapRequestStatus.PENDING_APPROVAL) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "This request is not awaiting approval"))
                        return@post
                    }

                    // Shift never moved on accept, so denying is just a status
                    // change - nothing to reverse.
                    swapRequestRepository.updateReview(swapId, SwapRequestStatus.DENIED, callerUserId)

                    call.respond(HttpStatusCode.OK, mapOf("status" to "DENIED"))
                } catch (e: Exception) {
                    call.application.log.error("Failed to deny swap request", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to deny swap request: ${e.message}"))
                }
            }

            post("/{id}/accept") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }
                    val swapId = call.parameters["id"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (swapId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid swap request ID"))
                        return@post
                    }
                    val callerUserId = call.callerUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    val callerEmployee = requireCallerEmployee(callerUserId, businessId)
                    if (callerEmployee == null) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No employee record linked to this account"))
                        return@post
                    }

                    val swapRequest = swapRequestRepository.findById(businessId, swapId)
                    if (swapRequest == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Swap request not found"))
                        return@post
                    }
                    if (swapRequest.targetEmployeeId != callerEmployee.id) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "This request is not addressed to you"))
                        return@post
                    }
                    if (swapRequest.status != SwapRequestStatus.PENDING) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "This request is no longer pending"))
                        return@post
                    }

                    // Accepting hands the request off for admin/manager approval -
                    // the shift itself doesn't move until that's granted, so
                    // nothing changes on anyone's calendar while it's pending.
                    swapRequestRepository.updateStatus(swapId, SwapRequestStatus.PENDING_APPROVAL, callerUserId)

                    call.respond(HttpStatusCode.OK, mapOf("status" to "PENDING_APPROVAL"))
                } catch (e: Exception) {
                    call.application.log.error("Failed to accept swap request", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to accept swap request: ${e.message}"))
                }
            }

            post("/{id}/decline") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }
                    val swapId = call.parameters["id"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (swapId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid swap request ID"))
                        return@post
                    }
                    val callerUserId = call.callerUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    val callerEmployee = requireCallerEmployee(callerUserId, businessId)
                    if (callerEmployee == null) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No employee record linked to this account"))
                        return@post
                    }

                    val swapRequest = swapRequestRepository.findById(businessId, swapId)
                    if (swapRequest == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Swap request not found"))
                        return@post
                    }
                    if (swapRequest.targetEmployeeId != callerEmployee.id) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "This request is not addressed to you"))
                        return@post
                    }
                    if (swapRequest.status != SwapRequestStatus.PENDING) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "This request is no longer pending"))
                        return@post
                    }

                    swapRequestRepository.updateStatus(swapId, SwapRequestStatus.DECLINED, callerUserId)

                    call.respond(HttpStatusCode.OK, mapOf("status" to "DECLINED"))
                } catch (e: Exception) {
                    call.application.log.error("Failed to decline swap request", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to decline swap request: ${e.message}"))
                }
            }

            post("/{id}/cancel") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }
                    val swapId = call.parameters["id"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (swapId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid swap request ID"))
                        return@post
                    }
                    val callerUserId = call.callerUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    val callerEmployee = requireCallerEmployee(callerUserId, businessId)
                    if (callerEmployee == null) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No employee record linked to this account"))
                        return@post
                    }

                    val swapRequest = swapRequestRepository.findById(businessId, swapId)
                    if (swapRequest == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Swap request not found"))
                        return@post
                    }
                    if (swapRequest.requestingEmployeeId != callerEmployee.id) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "This is not your request"))
                        return@post
                    }
                    if (swapRequest.status != SwapRequestStatus.PENDING) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "This request is no longer pending"))
                        return@post
                    }

                    swapRequestRepository.updateStatus(swapId, SwapRequestStatus.CANCELLED, callerUserId)

                    call.respond(HttpStatusCode.OK, mapOf("status" to "CANCELLED"))
                } catch (e: Exception) {
                    call.application.log.error("Failed to cancel swap request", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to cancel swap request: ${e.message}"))
                }
            }
        }
        }
    }

    private fun io.ktor.server.application.ApplicationCall.callerUserId(): String? {
        val principal = principal<JWTPrincipal>() ?: return null
        return principal.payload.getClaim("userId").asString()
    }

    private fun io.ktor.server.application.ApplicationCall.callerRole(): UserRole {
        val principal = principal<JWTPrincipal>() ?: return UserRole.EMPLOYEE
        val claimed = try {
            UserRole.valueOf(principal.payload.getClaim("role").asString())
        } catch (e: Exception) {
            UserRole.EMPLOYEE
        }
        // Per-business role wins over the account-level claim.
        return effectiveRoleOr(claimed)
    }

    private fun requireCallerEmployee(userId: String, businessId: UUID): Employee? {
        val employee = employeeRepository.findByUserId(userId) ?: return null
        // Reachable rather than owned - someone assigned here from another
        // location is on this roster and can swap shifts on it.
        return employeeRepository.findById(businessId, employee.id)
    }

    private fun SwapRequest.toResponse(
        employeeRepository: EmployeeRepository,
        scheduleRepository: ScheduleRepository,
        businessId: UUID
    ): SwapRequestResponse {
        val requestingEmployee = employeeRepository.findById(businessId, requestingEmployeeId)
        val targetEmployee = employeeRepository.findById(businessId, targetEmployeeId)
        val shift = scheduleRepository.findShiftById(businessId, targetShiftId)

        return SwapRequestResponse(
            id = id.toString(),
            requestingEmployeeId = requestingEmployeeId.toString(),
            requestingEmployeeName = requestingEmployee?.fullName ?: "Unknown",
            targetEmployeeId = targetEmployeeId.toString(),
            targetEmployeeName = targetEmployee?.fullName ?: "Unknown",
            targetShiftId = targetShiftId.toString(),
            shiftDate = shift?.date?.toString() ?: "",
            shiftStartTime = shift?.startTime?.toString() ?: "",
            shiftEndTime = shift?.endTime?.toString() ?: "",
            message = message,
            status = status.name,
            requestedAt = requestedAt.toString(),
            respondedAt = respondedAt?.toString(),
            reviewedAt = reviewedAt?.toString(),
            reviewedBy = reviewedBy
        )
    }
}
