package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.labormanagement.dto.createValidationErrorResponse
import org.labormanagement.dto.toTeamShiftResponse
import org.labormanagement.model.ScheduleInputPayload
import org.labormanagement.model.ScheduleKind
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.ScheduleRepository
import org.labormanagement.service.ShiftModificationService
import org.labormanagement.service.ShiftScheduler
import org.labormanagement.service.TenantContextHolder
import org.labormanagement.util.parseFlexibleTime
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
    private val shiftModificationService: ShiftModificationService,
    private val employeeRepository: EmployeeRepository
) {

    fun Route.scheduleRoutes() {
        route("/api/businesses/{businessId}/schedules") {

            // Generate a new draft schedule from ScheduleInput
            post("/generate") {
                try {
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@post
                    }

                    val request = call.receive<GenerateScheduleRequest>()

                    val schedule = shiftScheduler.generateSchedule(
                        input = request.input.toScheduleInput(businessId),
                        name = request.name ?: "Generated Schedule",
                        generatedBy = request.generatedBy ?: "system",
                        businessId = businessId
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
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@get
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@get
                    }

                    val statusParam = call.request.queryParameters["status"]
                    val schedules = if (statusParam != null) {
                        try {
                            val status = org.labormanagement.model.ScheduleStatus.valueOf(statusParam.uppercase())
                            scheduleRepository.findByBusinessAndStatus(businessId, status)
                        } catch (e: IllegalArgumentException) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid status: $statusParam. Valid values: DRAFT, PUBLISHED, ARCHIVED")
                            )
                            return@get
                        }
                    } else {
                        // Regular schedules only: this backs the schedule list and its
                        // history dropdown, which are about the business's rosters. Event
                        // schedules are reached through the week's events, not here.
                        scheduleRepository.findAllByBusiness(businessId, ScheduleKind.REGULAR)
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

            // Get schedule by date range
            get("/by-date-range") {
                try {
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@get
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@get
                    }

                    val startDateParam = call.request.queryParameters["startDate"]
                    val endDateParam = call.request.queryParameters["endDate"]

                    if (startDateParam == null || endDateParam == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Both startDate and endDate query parameters are required (format: YYYY-MM-DD)")
                        )
                        return@get
                    }

                    val startDate = try {
                        java.time.LocalDate.parse(startDateParam)
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid startDate format. Use YYYY-MM-DD")
                        )
                        return@get
                    }

                    val endDate = try {
                        java.time.LocalDate.parse(endDateParam)
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid endDate format. Use YYYY-MM-DD")
                        )
                        return@get
                    }

                    val schedule = scheduleRepository.findByBusinessAndDateRange(businessId, startDate, endDate)
                    if (schedule != null) {
                        call.respond(HttpStatusCode.OK, schedule)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "No schedule found for date range $startDate to $endDate")
                        )
                    }
                } catch (e: Exception) {
                    call.application.log.error("Failed to fetch schedule by date range", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to fetch schedule by date range: ${e.message}")
                    )
                }
            }

            // Get one employee's shifts within a date range, across any schedule for
            // the business. Unlike /by-date-range (which requires an exact match on a
            // single schedule's own start/end dates), this does a real overlap query
            // and returns just the matching shifts - built for callers like the
            // employee portal calendar that need "my shifts this week" regardless of
            // how the underlying schedules were chunked.
            // Authenticated so the allLocations option can tell whether the
            // caller is asking about their own record - same reason
            // /team-shifts below is authenticated.
            authenticate("auth-jwt") {
            get("/shifts") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@get
                    }

                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@get
                    }

                    val employeeIdParam = call.request.queryParameters["employeeId"]
                    val employeeId = employeeIdParam?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (employeeId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Valid employeeId query parameter is required"))
                        return@get
                    }

                    val startDateParam = call.request.queryParameters["startDate"]
                    val endDateParam = call.request.queryParameters["endDate"]
                    if (startDateParam == null || endDateParam == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Both startDate and endDate query parameters are required (format: YYYY-MM-DD)")
                        )
                        return@get
                    }

                    val startDate = try {
                        java.time.LocalDate.parse(startDateParam)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid startDate format. Use YYYY-MM-DD"))
                        return@get
                    }

                    val endDate = try {
                        java.time.LocalDate.parse(endDateParam)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid endDate format. Use YYYY-MM-DD"))
                        return@get
                    }

                    val statusParam = call.request.queryParameters["status"]
                    val status = if (statusParam != null) {
                        try {
                            org.labormanagement.model.ScheduleStatus.valueOf(statusParam.uppercase())
                        } catch (e: IllegalArgumentException) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid status: $statusParam. Valid values: DRAFT, PUBLISHED, ARCHIVED")
                            )
                            return@get
                        }
                    } else null

                    // An employee assigned to several locations has one
                    // calendar, so they can ask for all of it rather than just
                    // the location they happen to be viewing. Restricted to
                    // their own record: this route takes employeeId as a query
                    // parameter, so without the check anyone could read another
                    // employee's schedule across every location.
                    val wantsAllLocations =
                        call.request.queryParameters["allLocations"]?.toBoolean() == true
                    val callerUserId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
                        ?.payload?.getClaim("userId")?.asString()
                    val isSelf = callerUserId != null &&
                        employeeRepository.findByUserId(callerUserId)?.id == employeeId

                    if (wantsAllLocations && isSelf) {
                        call.respond(
                            HttpStatusCode.OK,
                            scheduleRepository.findAllShiftsForEmployeeInRange(
                                employeeId, startDate, endDate, status
                            )
                        )
                        return@get
                    }

                    val shifts = scheduleRepository.findShiftsForEmployeeInRange(
                        businessId, employeeId, startDate, endDate, status
                    )
                    call.respond(HttpStatusCode.OK, shifts)
                } catch (e: Exception) {
                    call.application.log.error("Failed to fetch employee shifts", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to fetch employee shifts: ${e.message}")
                    )
                }
            }
            }

            // Get every employee's shifts within a date range - the team-wide
            // view an employee needs to see who else is working, to make shift
            // swaps possible. payRate is only included for the caller's own
            // shifts; every other row has it redacted to null. Authenticated
            // (unlike most sibling routes here) since it needs a real
            // JWTPrincipal to know which employee is "the caller".
            authenticate("auth-jwt") {
            get("/team-shifts") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@get
                    }

                    val startDateParam = call.request.queryParameters["startDate"]
                    val endDateParam = call.request.queryParameters["endDate"]
                    if (startDateParam == null || endDateParam == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Both startDate and endDate query parameters are required (format: YYYY-MM-DD)")
                        )
                        return@get
                    }

                    val startDate = try {
                        java.time.LocalDate.parse(startDateParam)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid startDate format. Use YYYY-MM-DD"))
                        return@get
                    }

                    val endDate = try {
                        java.time.LocalDate.parse(endDateParam)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid endDate format. Use YYYY-MM-DD"))
                        return@get
                    }

                    val statusParam = call.request.queryParameters["status"]
                    val status = if (statusParam != null) {
                        try {
                            org.labormanagement.model.ScheduleStatus.valueOf(statusParam.uppercase())
                        } catch (e: IllegalArgumentException) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid status: $statusParam. Valid values: DRAFT, PUBLISHED, ARCHIVED")
                            )
                            return@get
                        }
                    } else null

                    val principal = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    val callerUserId = principal.payload.getClaim("userId").asString()
                    val callerEmployee = employeeRepository.findByUserId(callerUserId)

                    val teamShifts = scheduleRepository.findTeamShiftsInRange(businessId, startDate, endDate, status)
                    val response = teamShifts.map { it.toTeamShiftResponse(callerEmployee?.id) }
                    call.respond(HttpStatusCode.OK, response)
                } catch (e: Exception) {
                    call.application.log.error("Failed to fetch team shifts", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to fetch team shifts: ${e.message}")
                    )
                }
            }
            }

            // Get schedule by ID
            get("/{id}") {
                try {
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@get
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@get
                    }

                    val id = call.parameters["id"]?.let { UUID.fromString(it) }
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid schedule ID")
                            )
                            return@get
                        }

                    val schedule = scheduleRepository.findById(businessId, id)
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
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@post
                    }

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
                        businessId = businessId,
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
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@post
                    }

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
                        businessId = businessId,
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
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@delete
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@delete
                    }

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
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@patch
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@patch
                    }

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
                        businessId = businessId,
                        scheduleId = scheduleId,
                        shiftId = shiftId,
                        newEmployeeId = request.employeeId?.let { UUID.fromString(it) },
                        newDayOfWeek = request.dayOfWeek?.let { DayOfWeek.valueOf(it.uppercase()) },
                        newStartTime = request.startTime?.let { parseFlexibleTime(it) },
                        newEndTime = request.endTime?.let { parseFlexibleTime(it) },
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
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@post
                    }

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
                        businessId = businessId,
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
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@delete
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@delete
                    }

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
                        businessId = businessId,
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

            /**
             * Whether an undo is currently available for this schedule.
             *
             * Its own endpoint rather than a field on the schedule payload: undo
             * availability is server-side state that changes independently of the
             * schedule itself, and widening the schedule response would change a shape
             * every existing consumer already parses.
             */
            get("/{scheduleId}/undo") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@get
                    }

                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@get
                    }

                    val scheduleId = call.parameters["scheduleId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (scheduleId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid schedule ID"))
                        return@get
                    }

                    val schedule = scheduleRepository.findById(businessId, scheduleId)
                    if (schedule == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Schedule not found"))
                        return@get
                    }

                    call.respond(HttpStatusCode.OK, mapOf(
                        // A published schedule can't be edited, so a retained snapshot
                        // is not an offer worth making.
                        "canUndo" to (schedule.isDraft && shiftModificationService.canUndo(scheduleId))
                    ))
                } catch (e: Exception) {
                    call.application.log.error("Failed to check undo availability", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to check undo availability: ${e.message}")
                    )
                }
            }

            /**
             * Undo the last shift change to a draft schedule.
             *
             * Answers 200 either way: `restored: false` means there was nothing retained
             * to go back to, which is an ordinary state for a schedule that hasn't been
             * edited yet, not a failure. Any violations the restored plan carries are
             * returned alongside it — the restore is not refused for them.
             */
            post("/{scheduleId}/undo") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@post
                    }

                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access schedules from different business"))
                        return@post
                    }

                    val scheduleId = call.parameters["scheduleId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (scheduleId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid schedule ID"))
                        return@post
                    }

                    val request = try {
                        call.receive<UndoScheduleRequest>()
                    } catch (e: Exception) {
                        UndoScheduleRequest()
                    }

                    val result = shiftModificationService.undoLastChange(
                        businessId = businessId,
                        scheduleId = scheduleId,
                        modifiedBy = request.modifiedBy
                    )

                    call.respond(HttpStatusCode.OK, mapOf(
                        "restored" to result.restored,
                        "schedule" to result.schedule,
                        "violations" to result.violations
                    ))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.log.error("Failed to undo schedule change", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to undo change: ${e.message}")
                    )
                }
            }
        }
    }
}

data class UndoScheduleRequest(
    val modifiedBy: String = "system"
)

// DTOs
data class GenerateScheduleRequest(
    val input: ScheduleInputPayload,
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