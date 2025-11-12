package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.labormanagement.dto.createValidationErrorResponse
import org.labormanagement.model.ScheduleInput
import org.labormanagement.repository.ScheduleRepository
import org.labormanagement.service.ShiftModificationService
import org.labormanagement.service.ShiftScheduler
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/**
 * Unified controller for schedule operations:
 * - Schedule generation (create new schedules from requests)
 * - Schedule lifecycle management (draft/published workflow)
 * - Shift modifications within draft schedules (drag-and-drop)
 */
class ScheduleController(
    private val scheduleRepository: ScheduleRepository,
    private val shiftScheduler: ShiftScheduler,
    private val shiftModificationService: ShiftModificationService
) {

    fun Route.scheduleRoutes() {
        route("/api/schedules") {

            // Generate a new draft schedule from ScheduleInput
            post("/generate") {
                try {
                    val request = call.receive<GenerateScheduleRequest>()

                    val schedule = shiftScheduler.generateSchedule(
                        input = request.input,
                        name = request.name ?: "Generated Schedule",
                        generatedBy = request.generatedBy ?: "system"
                    )

                    call.respond(HttpStatusCode.Created, schedule)
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.application.log.error("Failed to generate schedule", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to generate schedule: ${e.message}")
                    )
                }
            }

            // Get all schedules (with optional status filter)
            get {
                try {
                    val statusParam = call.request.queryParameters["status"]
                    val schedules = if (statusParam != null) {
                        try {
                            val status = org.labormanagement.model.ScheduleStatus.valueOf(statusParam.uppercase())
                            scheduleRepository.findByStatus(status)
                        } catch (e: IllegalArgumentException) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid status: $statusParam. Valid values: DRAFT, PUBLISHED, ARCHIVED")
                            )
                            return@get
                        }
                    } else {
                        scheduleRepository.findAll()
                    }

                    call.respond(HttpStatusCode.OK, mapOf(
                        "schedules" to schedules,
                        "total" to schedules.size
                    ))
                } catch (e: Exception) {
                    call.application.log.error("Failed to fetch schedules", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to fetch schedules: ${e.message}")
                    )
                }
            }

            // Get schedule by ID
            get("/{id}") {
                try {
                    val id = call.parameters["id"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid schedule ID")
                            )
                            return@get
                        }

                    val schedule = scheduleRepository.findById(id)
                    if (schedule != null) {
                        call.respond(HttpStatusCode.OK, schedule)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Schedule not found")
                        )
                    }
                } catch (e: Exception) {
                    call.application.log.error("Failed to fetch schedule", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to fetch schedule: ${e.message}")
                    )
                }
            }

            // Publish a schedule
            post("/{id}/publish") {
                try {
                    val id = call.parameters["id"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid schedule ID")
                            )
                            return@post
                        }

                    val request = call.receive<PublishScheduleRequest>()

                    val published = shiftModificationService.publishSchedule(
                        scheduleId = id,
                        publishedBy = request.publishedBy
                    )

                    call.respond(HttpStatusCode.OK, published)
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to e.message)
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.application.log.error("Failed to publish schedule", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to publish schedule: ${e.message}")
                    )
                }
            }

            // Duplicate a schedule
            post("/{id}/duplicate") {
                try {
                    val id = call.parameters["id"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid schedule ID")
                            )
                            return@post
                        }

                    val request = call.receive<DuplicateScheduleRequest>()

                    val duplicated = shiftModificationService.duplicateSchedule(
                        scheduleId = id,
                        newName = request.name,
                        createdBy = request.createdBy
                    )

                    call.respond(HttpStatusCode.Created, duplicated)
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.application.log.error("Failed to duplicate schedule", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to duplicate schedule: ${e.message}")
                    )
                }
            }

            // Delete a draft schedule
            delete("/{id}") {
                try {
                    val id = call.parameters["id"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid schedule ID")
                            )
                            return@delete
                        }

                    scheduleRepository.delete(id)

                    call.respond(HttpStatusCode.OK, mapOf(
                        "message" to "Schedule deleted successfully",
                        "scheduleId" to id.toString()
                    ))
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to e.message)
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.application.log.error("Failed to delete schedule", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete schedule: ${e.message}")
                    )
                }
            }

            // === SHIFT MODIFICATION ENDPOINTS ===

            // Modify a shift (move to different employee/day/time)
            patch("/{scheduleId}/shifts/{shiftId}") {
                try {
                    val scheduleId = call.parameters["scheduleId"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid schedule ID")
                            )
                            return@patch
                        }

                    val shiftId = call.parameters["shiftId"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid shift ID")
                            )
                            return@patch
                        }

                    val request = try {
                        call.receive<ModifyShiftRequest>()
                    } catch (e: Exception) {
                        call.application.log.error("Failed to parse ModifyShiftRequest", e)
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid request body: ${e.message}")
                        )
                        return@patch
                    }

                    call.application.log.info("Modifying shift: scheduleId=$scheduleId, shiftId=$shiftId, request=$request")

                    val result = shiftModificationService.modifyShift(
                        scheduleId = scheduleId,
                        shiftId = shiftId,
                        newEmployeeId = request.employeeId?.let { UUID.fromString(it) },
                        newDayOfWeek = request.dayOfWeek?.let { DayOfWeek.valueOf(it.uppercase()) },
                        newStartTime = request.startTime?.let { LocalTime.parse(it) },
                        newEndTime = request.endTime?.let { LocalTime.parse(it) },
                        modifiedBy = request.modifiedBy
                    )

                    if (result.isValid) {
                        call.respond(HttpStatusCode.OK, mapOf(
                            "success" to true,
                            "shift" to result.shift
                        ))
                    } else {
                        call.application.log.warn("Shift modification validation failed: ${result.violations.size} violations")
                        result.violations.forEach { violation ->
                            call.application.log.warn("  - $violation")
                        }

                        // Return 422 Unprocessable Entity for validation failures
                        val errorResponse = createValidationErrorResponse(
                            violations = result.violations,
                            message = "Cannot modify shift due to constraint violations"
                        )
                        call.respond(HttpStatusCode.UnprocessableEntity, errorResponse)
                    }
                } catch (e: IllegalStateException) {
                    call.application.log.error("Illegal state during shift modification", e)
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to e.message)
                    )
                } catch (e: IllegalArgumentException) {
                    call.application.log.error("Illegal argument during shift modification", e)
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.application.log.error("Failed to modify shift", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to modify shift: ${e.message}")
                    )
                }
            }

            // Duplicate a shift
            post("/{scheduleId}/shifts/{shiftId}/duplicate") {
                try {
                    val scheduleId = call.parameters["scheduleId"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid schedule ID")
                            )
                            return@post
                        }

                    val shiftId = call.parameters["shiftId"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid shift ID")
                            )
                            return@post
                        }

                    val request = call.receive<DuplicateShiftRequest>()

                    val result = shiftModificationService.duplicateShift(
                        scheduleId = scheduleId,
                        shiftId = shiftId,
                        newEmployeeId = request.employeeId?.let { UUID.fromString(it) },
                        newDayOfWeek = request.dayOfWeek?.let { DayOfWeek.valueOf(it.uppercase()) },
                        createdBy = request.createdBy
                    )

                    if (result.isValid) {
                        call.respond(HttpStatusCode.Created, mapOf(
                            "success" to true,
                            "shift" to result.shift
                        ))
                    } else {
                        call.application.log.warn("Shift duplication validation failed: ${result.violations.size} violations")
                        result.violations.forEach { violation ->
                            call.application.log.warn("  - $violation")
                        }

                        // Return 422 Unprocessable Entity for validation failures
                        val errorResponse = createValidationErrorResponse(
                            violations = result.violations,
                            message = "Cannot duplicate shift due to constraint violations"
                        )
                        call.respond(HttpStatusCode.UnprocessableEntity, errorResponse)
                    }
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to e.message)
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.application.log.error("Failed to duplicate shift", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to duplicate shift: ${e.message}")
                    )
                }
            }

            // Delete a shift
            delete("/{scheduleId}/shifts/{shiftId}") {
                try {
                    val scheduleId = call.parameters["scheduleId"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid schedule ID")
                            )
                            return@delete
                        }

                    val shiftId = call.parameters["shiftId"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid shift ID")
                            )
                            return@delete
                        }

                    val request = call.receive<DeleteShiftRequest>()

                    shiftModificationService.deleteShift(
                        scheduleId = scheduleId,
                        shiftId = shiftId,
                        modifiedBy = request.modifiedBy
                    )

                    call.respond(HttpStatusCode.OK, mapOf(
                        "message" to "Shift deleted successfully",
                        "shiftId" to shiftId.toString()
                    ))
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to e.message)
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.application.log.error("Failed to delete shift", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete shift: ${e.message}")
                    )
                }
            }
        }
    }
}

// DTOs
data class GenerateScheduleRequest(
    val input: ScheduleInput,
    val name: String? = null,
    val generatedBy: String? = null
)

data class PublishScheduleRequest(
    val publishedBy: String
)

data class DuplicateScheduleRequest(
    val name: String?,
    val createdBy: String
)

data class ModifyShiftRequest(
    val employeeId: String?,
    val dayOfWeek: String?,
    val startTime: String?,
    val endTime: String?,
    val modifiedBy: String
)

data class DuplicateShiftRequest(
    val employeeId: String?,
    val dayOfWeek: String?,
    val createdBy: String
)

data class DeleteShiftRequest(
    val modifiedBy: String
)