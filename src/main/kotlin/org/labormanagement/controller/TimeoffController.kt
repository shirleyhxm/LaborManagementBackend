package org.labormanagement.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.labormanagement.dto.*
import org.labormanagement.model.ErrorResponse
import org.labormanagement.model.UserRole
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.service.TimeoffService
import org.labormanagement.service.TenantContextHolder
import org.labormanagement.service.effectiveRoleOr
import java.time.LocalDate
import java.util.*

/**
 * Controller for timeoff request endpoints
 */
fun Route.timeoffRoutes(timeoffService: TimeoffService, employeeRepository: EmployeeRepository) {

    fun ApplicationCall.callerRole(): UserRole {
        val principal = principal<JWTPrincipal>() ?: return UserRole.EMPLOYEE
        val claimed = try {
            UserRole.valueOf(principal.payload.getClaim("role").asString())
        } catch (e: Exception) {
            UserRole.EMPLOYEE
        }
        // Per-business role wins over the account-level claim.
        return effectiveRoleOr(claimed)
    }

    fun ApplicationCall.isManager(): Boolean {
        val role = callerRole()
        return role == UserRole.ADMIN || role == UserRole.MANAGER
    }

    // True if the caller's own employee record (resolved from their JWT userId)
    // matches the given employeeId - lets an employee submit/cancel/view their
    // own requests without needing manager privileges.
    fun ApplicationCall.isSelf(businessId: UUID, employeeId: UUID): Boolean {
        val callerUserId = principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString() ?: return false
        val employee = employeeRepository.findByUserId(callerUserId) ?: return false
        return employee.businessId == businessId && employee.id == employeeId
    }

    authenticate("auth-jwt") {
    route("/api/businesses/{businessId}/timeoff") {

        /**
         * Submit a timeoff request
         * POST /api/businesses/{businessId}/timeoff
         */
        post {
            try {
                // Extract and validate businessId from path
                val businessId = call.parameters["businessId"]?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }
                if (businessId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid business ID"))
                    return@post
                }

                // Validate businessId matches tenant context
                val contextBusinessId = TenantContextHolder.getContext()?.businessId
                if (contextBusinessId != null && contextBusinessId != businessId) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot access timeoff from different business"))
                    return@post
                }

                val request = call.receive<SubmitTimeoffRequest>()

                val employeeId = try {
                    UUID.fromString(request.employeeId)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
                    return@post
                }

                if (!call.isManager() && !call.isSelf(businessId, employeeId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot submit timeoff for another employee"))
                    return@post
                }

                val startDate = try {
                    LocalDate.parse(request.startDate)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid start date format"))
                    return@post
                }

                val endDate = try {
                    LocalDate.parse(request.endDate)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid end date format"))
                    return@post
                }

                val result = timeoffService.submitTimeoffRequest(
                    businessId = businessId,
                    employeeId = employeeId,
                    startDate = startDate,
                    endDate = endDate,
                    reason = request.reason
                )

                result.fold(
                    onSuccess = { timeoffRequest ->
                        call.respond(HttpStatusCode.Created, timeoffRequest.toResponse())
                    },
                    onFailure = { error ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Failed to submit request"))
                    }
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request: ${e.message}"))
            }
        }

        /**
         * Cancel a timeoff request
         * DELETE /api/businesses/{businessId}/timeoff/{id}/cancel
         */
        delete("/{id}/cancel") {
            try {
                // Extract and validate businessId from path
                val businessId = call.parameters["businessId"]?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }
                if (businessId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid business ID"))
                    return@delete
                }

                // Validate businessId matches tenant context
                val contextBusinessId = TenantContextHolder.getContext()?.businessId
                if (contextBusinessId != null && contextBusinessId != businessId) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot access timeoff from different business"))
                    return@delete
                }

                val requestId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID format"))
                    return@delete
                }

                val request = call.receive<CancelTimeoffRequest>()

                val employeeId = try {
                    UUID.fromString(request.employeeId)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
                    return@delete
                }

                if (!call.isManager() && !call.isSelf(businessId, employeeId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot cancel timeoff for another employee"))
                    return@delete
                }

                val result = timeoffService.cancelTimeoffRequest(businessId, requestId, employeeId)
                result.fold(
                    onSuccess = { timeoffRequest ->
                        call.respond(HttpStatusCode.OK, timeoffRequest.toResponse())
                    },
                    onFailure = { error ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Failed to cancel request"))
                    }
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request: ${e.message}"))
            }
        }

        /**
         * Approve a timeoff request (manager/admin only)
         * POST /api/businesses/{businessId}/timeoff/{id}/approve
         */
        post("/{id}/approve") {
            try {
                // Extract and validate businessId from path
                val businessId = call.parameters["businessId"]?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }
                if (businessId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid business ID"))
                    return@post
                }

                // Validate businessId matches tenant context
                val contextBusinessId = TenantContextHolder.getContext()?.businessId
                if (contextBusinessId != null && contextBusinessId != businessId) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot access timeoff from different business"))
                    return@post
                }

                if (!call.isManager()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Requires ADMIN or MANAGER role"))
                    return@post
                }

                val requestId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID format"))
                    return@post
                }

                val request = call.receive<ReviewTimeoffRequest>()

                // Get user ID from JWT (if authenticated)
                val reviewerId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString() ?: "system"

                val result = timeoffService.approveTimeoffRequest(
                    businessId = businessId,
                    requestId = requestId,
                    reviewerId = reviewerId,
                    reviewNotes = request.reviewNotes
                )

                result.fold(
                    onSuccess = { timeoffRequest ->
                        call.respond(HttpStatusCode.OK, timeoffRequest.toResponse())
                    },
                    onFailure = { error ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Failed to approve request"))
                    }
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request: ${e.message}"))
            }
        }

        /**
         * Deny a timeoff request (manager/admin only)
         * POST /api/businesses/{businessId}/timeoff/{id}/deny
         */
        post("/{id}/deny") {
            try {
                // Extract and validate businessId from path
                val businessId = call.parameters["businessId"]?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }
                if (businessId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid business ID"))
                    return@post
                }

                // Validate businessId matches tenant context
                val contextBusinessId = TenantContextHolder.getContext()?.businessId
                if (contextBusinessId != null && contextBusinessId != businessId) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot access timeoff from different business"))
                    return@post
                }

                if (!call.isManager()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Requires ADMIN or MANAGER role"))
                    return@post
                }

                val requestId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID format"))
                    return@post
                }

                val request = call.receive<ReviewTimeoffRequest>()

                // Get user ID from JWT (if authenticated)
                val reviewerId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString() ?: "system"

                val result = timeoffService.denyTimeoffRequest(
                    businessId = businessId,
                    requestId = requestId,
                    reviewerId = reviewerId,
                    reviewNotes = request.reviewNotes
                )

                result.fold(
                    onSuccess = { timeoffRequest ->
                        call.respond(HttpStatusCode.OK, timeoffRequest.toResponse())
                    },
                    onFailure = { error ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Failed to deny request"))
                    }
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request: ${e.message}"))
            }
        }

        /**
         * Get timeoff requests for an employee
         * GET /api/businesses/{businessId}/timeoff/employee/{employeeId}
         */
        get("/employee/{employeeId}") {
            // Extract and validate businessId from path
            val businessId = call.parameters["businessId"]?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (businessId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid business ID"))
                return@get
            }

            // Validate businessId matches tenant context
            val contextBusinessId = TenantContextHolder.getContext()?.businessId
            if (contextBusinessId != null && contextBusinessId != businessId) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot access timeoff from different business"))
                return@get
            }

            val employeeId = try {
                UUID.fromString(call.parameters["employeeId"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
                return@get
            }

            if (!call.isManager() && !call.isSelf(businessId, employeeId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot view timeoff for another employee"))
                return@get
            }

            val requests = timeoffService.getTimeoffRequestsByEmployee(businessId, employeeId)
            call.respond(HttpStatusCode.OK, requests.map { it.toResponse() })
        }

        /**
         * Get pending timeoff requests (manager/admin)
         * GET /api/businesses/{businessId}/timeoff/pending
         */
        get("/pending") {
            // Extract and validate businessId from path
            val businessId = call.parameters["businessId"]?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (businessId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid business ID"))
                return@get
            }

            // Validate businessId matches tenant context
            val contextBusinessId = TenantContextHolder.getContext()?.businessId
            if (contextBusinessId != null && contextBusinessId != businessId) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot access timeoff from different business"))
                return@get
            }

            if (!call.isManager()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Requires ADMIN or MANAGER role"))
                return@get
            }

            val requests = timeoffService.getPendingTimeoffRequests(businessId)
            call.respond(HttpStatusCode.OK, requests.map { it.toResponse() })
        }

        /**
         * Get all timeoff requests (admin)
         * GET /api/businesses/{businessId}/timeoff
         */
        get {
            // Extract and validate businessId from path
            val businessId = call.parameters["businessId"]?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (businessId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid business ID"))
                return@get
            }

            // Validate businessId matches tenant context
            val contextBusinessId = TenantContextHolder.getContext()?.businessId
            if (contextBusinessId != null && contextBusinessId != businessId) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot access timeoff from different business"))
                return@get
            }

            if (!call.isManager()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Requires ADMIN or MANAGER role"))
                return@get
            }

            val requests = timeoffService.getAllTimeoffRequests(businessId)
            call.respond(HttpStatusCode.OK, requests.map { it.toResponse() })
        }

        /**
         * Get a timeoff request by ID
         * GET /api/businesses/{businessId}/timeoff/{id}
         */
        get("/{id}") {
            // Extract and validate businessId from path
            val businessId = call.parameters["businessId"]?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (businessId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid business ID"))
                return@get
            }

            // Validate businessId matches tenant context
            val contextBusinessId = TenantContextHolder.getContext()?.businessId
            if (contextBusinessId != null && contextBusinessId != businessId) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot access timeoff from different business"))
                return@get
            }

            val id = try {
                UUID.fromString(call.parameters["id"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID format"))
                return@get
            }

            val timeoffRequest = timeoffService.getTimeoffRequestById(businessId, id)
            if (timeoffRequest == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Timeoff request not found"))
                return@get
            }

            if (!call.isManager() && !call.isSelf(businessId, timeoffRequest.employeeId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot view timeoff for another employee"))
                return@get
            }

            call.respond(HttpStatusCode.OK, timeoffRequest.toResponse())
        }
    }
    }
}
