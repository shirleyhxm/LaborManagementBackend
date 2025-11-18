package org.labormanagement.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.labormanagement.dto.*
import org.labormanagement.model.UserRole
import org.labormanagement.service.TimeoffService
import java.time.LocalDate
import java.util.*

/**
 * Controller for timeoff request endpoints
 */
fun Route.timeoffRoutes(timeoffService: TimeoffService) {

    route("/api/timeoff") {

        /**
         * Submit a timeoff request
         * POST /api/timeoff
         */
        post {
            try {
                val request = call.receive<SubmitTimeoffRequest>()

                val employeeId = try {
                    UUID.fromString(request.employeeId)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
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
         * DELETE /api/timeoff/{id}/cancel
         */
        delete("/{id}/cancel") {
            try {
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

                val result = timeoffService.cancelTimeoffRequest(requestId, employeeId)
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
         * POST /api/timeoff/{id}/approve
         */
        post("/{id}/approve") {
            try {
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
         * POST /api/timeoff/{id}/deny
         */
        post("/{id}/deny") {
            try {
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
         * GET /api/timeoff/employee/{employeeId}
         */
        get("/employee/{employeeId}") {
            val employeeId = try {
                UUID.fromString(call.parameters["employeeId"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
                return@get
            }

            val requests = timeoffService.getTimeoffRequestsByEmployee(employeeId)
            call.respond(HttpStatusCode.OK, requests.map { it.toResponse() })
        }

        /**
         * Get pending timeoff requests (manager/admin)
         * GET /api/timeoff/pending
         */
        get("/pending") {
            val requests = timeoffService.getPendingTimeoffRequests()
            call.respond(HttpStatusCode.OK, requests.map { it.toResponse() })
        }

        /**
         * Get all timeoff requests (admin)
         * GET /api/timeoff
         */
        get {
            val requests = timeoffService.getAllTimeoffRequests()
            call.respond(HttpStatusCode.OK, requests.map { it.toResponse() })
        }

        /**
         * Get a timeoff request by ID
         * GET /api/timeoff/{id}
         */
        get("/{id}") {
            val id = try {
                UUID.fromString(call.parameters["id"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID format"))
                return@get
            }

            val timeoffRequest = timeoffService.getTimeoffRequestById(id)
            if (timeoffRequest != null) {
                call.respond(HttpStatusCode.OK, timeoffRequest.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Timeoff request not found"))
            }
        }
    }
}
