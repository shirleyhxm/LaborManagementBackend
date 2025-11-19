package org.labormanagement.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.labormanagement.dto.*
import org.labormanagement.model.ErrorResponse
import org.labormanagement.service.SalesService
import java.time.LocalDate
import java.util.*

/**
 * Controller for sales recording endpoints
 */
fun Route.salesRoutes(salesService: SalesService) {

    route("/api/sales") {

        /**
         * Record a sale
         * POST /api/sales
         */
        post {
            try {
                val request = call.receive<RecordSaleRequest>()

                val employeeId = try {
                    UUID.fromString(request.employeeId)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
                    return@post
                }

                val scheduleId = request.scheduleId?.let {
                    try {
                        UUID.fromString(it)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID format"))
                        return@post
                    }
                }

                val shiftId = request.shiftId?.let {
                    try {
                        UUID.fromString(it)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid shift ID format"))
                        return@post
                    }
                }

                // Get user ID from JWT (if authenticated)
                val recordedBy = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString() ?: "system"

                val result = salesService.recordSale(
                    employeeId = employeeId,
                    amount = request.amount,
                    category = request.category,
                    description = request.description,
                    scheduleId = scheduleId,
                    shiftId = shiftId,
                    recordedBy = recordedBy
                )

                result.fold(
                    onSuccess = { salesRecord ->
                        call.respond(HttpStatusCode.Created, salesRecord.toResponse())
                    },
                    onFailure = { error ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Failed to record sale"))
                    }
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request: ${e.message}"))
            }
        }

        /**
         * Get sales records for an employee
         * GET /api/sales/employee/{employeeId}
         */
        get("/employee/{employeeId}") {
            val employeeId = try {
                UUID.fromString(call.parameters["employeeId"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
                return@get
            }

            val records = salesService.getSalesByEmployee(employeeId)
            call.respond(HttpStatusCode.OK, records.map { it.toResponse() })
        }

        /**
         * Get sales records for a shift
         * GET /api/sales/shift/{shiftId}
         */
        get("/shift/{shiftId}") {
            val shiftId = try {
                UUID.fromString(call.parameters["shiftId"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid shift ID format"))
                return@get
            }

            val records = salesService.getSalesByShift(shiftId)
            call.respond(HttpStatusCode.OK, records.map { it.toResponse() })
        }

        /**
         * Get sales records for a schedule
         * GET /api/sales/schedule/{scheduleId}
         */
        get("/schedule/{scheduleId}") {
            val scheduleId = try {
                UUID.fromString(call.parameters["scheduleId"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID format"))
                return@get
            }

            val records = salesService.getSalesBySchedule(scheduleId)
            call.respond(HttpStatusCode.OK, records.map { it.toResponse() })
        }

        /**
         * Get sales performance for an employee
         * GET /api/sales/performance/{employeeId}?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
         */
        get("/performance/{employeeId}") {
            val employeeId = try {
                UUID.fromString(call.parameters["employeeId"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
                return@get
            }

            val startDateStr = call.request.queryParameters["startDate"]
            val endDateStr = call.request.queryParameters["endDate"]

            if (startDateStr == null || endDateStr == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Start date and end date are required"))
                return@get
            }

            val startDate = try {
                LocalDate.parse(startDateStr)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid start date format"))
                return@get
            }

            val endDate = try {
                LocalDate.parse(endDateStr)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid end date format"))
                return@get
            }

            val result = salesService.getSalesPerformance(employeeId, startDate, endDate)
            result.fold(
                onSuccess = { performance ->
                    call.respond(HttpStatusCode.OK, performance.toResponse())
                },
                onFailure = { error ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Failed to get performance"))
                }
            )
        }

        /**
         * Get daily sales summary
         * GET /api/sales/summary/daily?date=YYYY-MM-DD
         */
        get("/summary/daily") {
            val dateStr = call.request.queryParameters["date"]

            if (dateStr == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Date is required"))
                return@get
            }

            val date = try {
                LocalDate.parse(dateStr)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date format"))
                return@get
            }

            val summary = salesService.getDailySalesSummary(date)
            call.respond(HttpStatusCode.OK, summary.toResponse())
        }

        /**
         * Get a sales record by ID
         * GET /api/sales/{id}
         */
        get("/{id}") {
            val id = try {
                UUID.fromString(call.parameters["id"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid sales record ID format"))
                return@get
            }

            val record = salesService.getSalesRecordById(id)
            if (record != null) {
                call.respond(HttpStatusCode.OK, record.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Sales record not found"))
            }
        }

        /**
         * Delete a sales record (admin only)
         * DELETE /api/sales/{id}
         */
        delete("/{id}") {
            val id = try {
                UUID.fromString(call.parameters["id"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid sales record ID format"))
                return@delete
            }

            val result = salesService.deleteSalesRecord(id)
            result.fold(
                onSuccess = {
                    call.respond(HttpStatusCode.NoContent)
                },
                onFailure = { error ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error.message ?: "Sales record not found"))
                }
            )
        }
    }
}
