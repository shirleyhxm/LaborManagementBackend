package org.labormanagement.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.labormanagement.dto.*
import org.labormanagement.service.AttendanceService
import java.time.LocalDate
import java.util.*

/**
 * Controller for employee attendance (clock in/out) endpoints
 */
fun Route.attendanceRoutes(attendanceService: AttendanceService) {

    route("/api/attendance") {

        /**
         * Clock in an employee
         * POST /api/attendance/clock-in
         */
        post("/clock-in") {
            try {
                val request = call.receive<ClockInRequest>()

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

                val result = attendanceService.clockIn(
                    employeeId = employeeId,
                    scheduleId = scheduleId,
                    shiftId = shiftId,
                    notes = request.notes
                )

                result.fold(
                    onSuccess = { clockRecord ->
                        call.respond(HttpStatusCode.Created, clockRecord.toResponse())
                    },
                    onFailure = { error ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Failed to clock in"))
                    }
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request: ${e.message}"))
            }
        }

        /**
         * Clock out an employee
         * POST /api/attendance/clock-out
         */
        post("/clock-out") {
            try {
                val request = call.receive<ClockOutRequest>()

                val employeeId = try {
                    UUID.fromString(request.employeeId)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
                    return@post
                }

                val result = attendanceService.clockOut(
                    employeeId = employeeId,
                    notes = request.notes
                )

                result.fold(
                    onSuccess = { clockRecord ->
                        call.respond(HttpStatusCode.OK, clockRecord.toResponse())
                    },
                    onFailure = { error ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Failed to clock out"))
                    }
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request: ${e.message}"))
            }
        }

        /**
         * Get active clock record for an employee
         * GET /api/attendance/active/{employeeId}
         */
        get("/active/{employeeId}") {
            val employeeId = try {
                UUID.fromString(call.parameters["employeeId"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
                return@get
            }

            val activeRecord = attendanceService.getActiveClockRecord(employeeId)
            if (activeRecord != null) {
                call.respond(HttpStatusCode.OK, activeRecord.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("No active clock record found"))
            }
        }

        /**
         * Get all clock records for an employee
         * GET /api/attendance/employee/{employeeId}
         */
        get("/employee/{employeeId}") {
            val employeeId = try {
                UUID.fromString(call.parameters["employeeId"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid employee ID format"))
                return@get
            }

            val records = attendanceService.getClockRecordsByEmployee(employeeId)
            call.respond(HttpStatusCode.OK, records.map { it.toResponse() })
        }

        /**
         * Get clock records for a shift
         * GET /api/attendance/shift/{shiftId}
         */
        get("/shift/{shiftId}") {
            val shiftId = try {
                UUID.fromString(call.parameters["shiftId"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid shift ID format"))
                return@get
            }

            val records = attendanceService.getClockRecordsByShift(shiftId)
            call.respond(HttpStatusCode.OK, records.map { it.toResponse() })
        }

        /**
         * Get clock records for a schedule
         * GET /api/attendance/schedule/{scheduleId}
         */
        get("/schedule/{scheduleId}") {
            val scheduleId = try {
                UUID.fromString(call.parameters["scheduleId"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID format"))
                return@get
            }

            val records = attendanceService.getClockRecordsBySchedule(scheduleId)
            call.respond(HttpStatusCode.OK, records.map { it.toResponse() })
        }

        /**
         * Get attendance statistics for an employee
         * GET /api/attendance/stats/{employeeId}?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
         */
        get("/stats/{employeeId}") {
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

            val result = attendanceService.getAttendanceStats(employeeId, startDate, endDate)
            result.fold(
                onSuccess = { stats ->
                    call.respond(HttpStatusCode.OK, stats.toResponse())
                },
                onFailure = { error ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Failed to get stats"))
                }
            )
        }

        /**
         * Get a clock record by ID
         * GET /api/attendance/{id}
         */
        get("/{id}") {
            val id = try {
                UUID.fromString(call.parameters["id"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid clock record ID format"))
                return@get
            }

            val record = attendanceService.getClockRecordById(id)
            if (record != null) {
                call.respond(HttpStatusCode.OK, record.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Clock record not found"))
            }
        }

        /**
         * Delete a clock record (admin only)
         * DELETE /api/attendance/{id}
         */
        delete("/{id}") {
            val id = try {
                UUID.fromString(call.parameters["id"])
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid clock record ID format"))
                return@delete
            }

            val result = attendanceService.deleteClockRecord(id)
            result.fold(
                onSuccess = {
                    call.respond(HttpStatusCode.NoContent)
                },
                onFailure = { error ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error.message ?: "Clock record not found"))
                }
            )
        }
    }
}
