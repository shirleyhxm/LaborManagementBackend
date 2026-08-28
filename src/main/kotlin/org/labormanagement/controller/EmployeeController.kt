package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import org.labormanagement.config.EnvironmentConfig
import org.labormanagement.dto.CreateEmployeeRequest
import org.labormanagement.dto.CreateInviteRequest
import org.labormanagement.dto.CreateInviteResponse
import org.labormanagement.dto.UpdateEmployeeRequest
import org.labormanagement.dto.toModel
import org.labormanagement.dto.toResponse
import org.labormanagement.model.EmployeeInvite
import org.labormanagement.model.UserRole
import org.labormanagement.repository.EmployeeInviteRepository
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.service.ImportService
import org.labormanagement.service.TenantContextHolder
import org.labormanagement.service.ForbiddenException
import org.labormanagement.service.UnauthorizedException
import org.labormanagement.service.effectiveRoleOr
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

class EmployeeController(
    private val employeeRepository: EmployeeRepository,
    private val importService: ImportService,
    private val employeeInviteRepository: EmployeeInviteRepository = EmployeeInviteRepository(),
    private val frontendOrigin: String = EnvironmentConfig.get("FRONTEND_ORIGIN", "http://localhost:3000")
) {
    private val logger = LoggerFactory.getLogger(EmployeeController::class.java)

    init {
        if (EnvironmentConfig.isProduction() && frontendOrigin == "http://localhost:3000") {
            logger.warn(
                "FRONTEND_ORIGIN is not set in production - invite links will point to " +
                "localhost:3000 instead of the real frontend. Set FRONTEND_ORIGIN in the " +
                "deployment environment."
            )
        }
    }

    fun Route.employeeRoutes() {
        // Resolves the Employee record linked to the caller's own login account.
        // Not business-scoped in the URL, since the caller doesn't know their
        // businessId yet - that's exactly what this endpoint derives.
        route("/api/employees") {
            authenticate("auth-jwt") {
                get("/me") {
                    val principal = call.principal<JWTPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                        return@get
                    }
                    val userId = principal.payload.getClaim("userId").asString()
                    val employee = employeeRepository.findByUserId(userId)
                    if (employee != null) {
                        call.respond(HttpStatusCode.OK, employee.toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No employee record linked to this account"))
                    }
                }
            }
        }

        route("/api/businesses/{businessId}/employees") {
        authenticate("auth-jwt") {

            // Create employee
            post {
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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employees from different business"))
                        return@post
                    }

                    val request = call.receive<CreateEmployeeRequest>()
                    val employee = request.toModel(businessId)
                    val created = employeeRepository.create(employee)
                    if (created == null) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Employee already exists"))
                    } else {
                        call.respond(HttpStatusCode.Created, created.toResponse())
                    }
                } catch (e: ForbiddenException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.log.error("Failed to create employee", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to (e.message ?: "Invalid request"),
                            "type" to e::class.simpleName
                        )
                    )
                }
            }

            // Get all employees (optionally filtered by group)
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

                    // Listing every employee's full record (including both pay
                    // rates) is an admin/manager operation, not something any
                    // authenticated employee should be able to call.
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    val callerRole = effectiveRoleOr(
                        try {
                            UserRole.valueOf(principal.payload.getClaim("role").asString())
                        } catch (e: Exception) {
                            UserRole.EMPLOYEE
                        }
                    )
                    if (callerRole != UserRole.ADMIN && callerRole != UserRole.MANAGER) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Requires ADMIN or MANAGER role"))
                        return@get
                    }

                    val groupName = call.request.queryParameters["group"]

                    val employees = if (groupName != null) {
                        employeeRepository.findByGroup(businessId, groupName)
                    } else {
                        employeeRepository.findAllByBusiness(businessId)
                    }

                    call.respond(HttpStatusCode.OK, employees.map { it.toResponse() })
                } catch (e: UnauthorizedException) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                } catch (e: ForbiddenException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.log.error("Failed to get employees", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to (e.message ?: "Invalid request"),
                            "type" to e::class.simpleName
                        )
                    )
                }
            }

            // Get employee by ID
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

                    // Same reasoning as the list route - a full employee record
                    // (both pay rates included) is admin/manager-only, except an
                    // employee fetching their own record (e.g. to refresh state
                    // after self-editing availability via PUT, which is allowed).
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                    val id = call.parameters["id"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee ID"))
                        return@get
                    }

                    val callerRole = effectiveRoleOr(
                        try {
                            UserRole.valueOf(principal.payload.getClaim("role").asString())
                        } catch (e: Exception) {
                            UserRole.EMPLOYEE
                        }
                    )
                    val callerUserId = principal.payload.getClaim("userId").asString()
                    val isSelf = employeeRepository.findByUserId(callerUserId)?.id == id
                    if (callerRole != UserRole.ADMIN && callerRole != UserRole.MANAGER && !isSelf) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Requires ADMIN or MANAGER role"))
                        return@get
                    }

                    val employee = employeeRepository.findById(businessId, id)
                    if (employee != null) {
                        call.respond(HttpStatusCode.OK, employee.toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee not found"))
                    }
                } catch (e: UnauthorizedException) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                } catch (e: ForbiddenException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.log.error("Failed to get employee by ID", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to (e.message ?: "Invalid request"),
                            "type" to e::class.simpleName
                        )
                    )
                }
            }

            // Invite an employee to create a login account for this record
            post("/{id}/invite") {
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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employees from different business"))
                        return@post
                    }

                    val id = call.parameters["id"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee ID"))
                        return@post
                    }

                    val employee = employeeRepository.findById(businessId, id)
                    if (employee == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee not found"))
                        return@post
                    }
                    if (employee.userId != null) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Employee is already linked to an account"))
                        return@post
                    }

                    val request = call.receive<CreateInviteRequest>()
                    if (request.email.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Email is required"))
                        return@post
                    }

                    // Re-inviting supersedes any existing pending invite for this
                    // employee, e.g. the admin fixing a typo'd email - the old
                    // link stops working the moment a new one is generated.
                    employeeInviteRepository.findPendingByEmployeeId(id)?.let {
                        employeeInviteRepository.markRevoked(it.id)
                    }

                    val invitedBy = TenantContextHolder.getContext()?.userId ?: "unknown"
                    val invite = employeeInviteRepository.create(
                        EmployeeInvite(
                            employeeId = id,
                            businessId = businessId,
                            email = request.email,
                            token = UUID.randomUUID().toString(),
                            invitedBy = invitedBy,
                            invitedAt = Instant.now()
                        )
                    )

                    call.respond(
                        HttpStatusCode.Created,
                        CreateInviteResponse(inviteLink = "$frontendOrigin/join?token=${invite.token}")
                    )
                } catch (e: Exception) {
                    call.application.log.error("Failed to invite employee", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (e.message ?: "Invalid request"))
                    )
                }
            }

            // Revoke a pending invite for an employee
            delete("/{id}/invite") {
                try {
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@delete
                    }

                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employees from different business"))
                        return@delete
                    }

                    val id = call.parameters["id"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee ID"))
                        return@delete
                    }

                    val employee = employeeRepository.findById(businessId, id)
                    if (employee == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee not found"))
                        return@delete
                    }

                    val pendingInvite = employeeInviteRepository.findPendingByEmployeeId(id)
                    if (pendingInvite == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No pending invite for this employee"))
                        return@delete
                    }

                    employeeInviteRepository.markRevoked(pendingInvite.id)
                    call.respond(HttpStatusCode.NoContent)
                } catch (e: Exception) {
                    call.application.log.error("Failed to revoke invite", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (e.message ?: "Invalid request"))
                    )
                }
            }

            // Update employee
            put("/{id}") {
                try {
                    // Extract and validate businessId from path
                    val businessId = call.parameters["businessId"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (businessId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))
                        return@put
                    }

                    // Validate businessId matches tenant context
                    val contextBusinessId = TenantContextHolder.getContext()?.businessId
                    if (contextBusinessId != null && contextBusinessId != businessId) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employees from different business"))
                        return@put
                    }

                    val id = call.parameters["id"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee ID"))
                        return@put
                    }

                    val request = call.receive<UpdateEmployeeRequest>()
                    val existing = employeeRepository.findById(businessId, id)

                    if (existing == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee not found"))
                        return@put
                    }

                    val updated = existing.copy(
                        firstName = request.firstName ?: existing.firstName,
                        lastName = request.lastName ?: existing.lastName,
                        middleName = request.middleName ?: existing.middleName,
                        normalPayRate = request.normalPayRate ?: existing.normalPayRate,
                        overtimePayRate = request.overtimePayRate ?: existing.overtimePayRate,
                        productivity = request.productivity ?: existing.productivity,
                        contract = request.contract?.toModel() ?: existing.contract,
                        availability = request.availability?.map { it.toModel() } ?: existing.availability,
                        groups = request.groups ?: existing.groups
                    )

                    // Editing someone lent in from another business changes the
                    // single shared record, so it lands in their home business
                    // too. That is the agreed behaviour, but a pay or contract
                    // change reaching across businesses should be traceable to
                    // whoever made it.
                    val isBorrowed = existing.businessId != businessId
                    if (isBorrowed) {
                        val touchesPay = request.normalPayRate != null ||
                            request.overtimePayRate != null ||
                            request.contract != null
                        if (touchesPay) {
                            val actor = TenantContextHolder.getContext()?.userId ?: "unknown"
                            call.application.log.warn(
                                "[EmployeeController] AUDIT: user $actor acting in business " +
                                    "$businessId changed pay/contract for shared employee $id " +
                                    "(home business ${existing.businessId})"
                            )
                        }
                    }

                    val saved = employeeRepository.update(businessId, id, updated)
                    if (saved == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee not found"))
                        return@put
                    }
                    call.respond(HttpStatusCode.OK, saved.toResponse())
                } catch (e: Exception) {
                    call.application.log.error("Failed to update employee", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to (e.message ?: "Invalid request"),
                            "type" to e::class.simpleName
                        )
                    )
                }
            }

            // Delete employee
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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employees from different business"))
                        return@delete
                    }

                    val id = call.parameters["id"]?.let {
                        try { UUID.fromString(it) } catch (e: Exception) { null }
                    }
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid employee ID"))
                        return@delete
                    }

                    val deleted = employeeRepository.delete(businessId, id)
                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee not found"))
                    }
                } catch (e: Exception) {
                    call.application.log.error("Failed to delete employee", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to (e.message ?: "Invalid request"),
                            "type" to e::class.simpleName
                        )
                    )
                }
            }

            // Import employees from CSV
            post("/import") {
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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employees from different business"))
                        return@post
                    }

                    val csvContent = call.receiveText()

                    if (csvContent.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "CSV content cannot be empty")
                        )
                        return@post
                    }

                    val response = importService.importEmployeesFromCsv(csvContent, businessId)

                    if (response.success) {
                        call.respond(HttpStatusCode.OK, response)
                    } else {
                        call.respond(HttpStatusCode.BadRequest, response)
                    }

                } catch (e: Exception) {
                    call.application.log.error("[EmployeeController] Failed to import employees", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to import employees: ${e.message}")
                    )
                }
            }

            // Get CSV template for employee import
            get("/import/template") {
                try {
                    val template = importService.generateCsvTemplate()
                    call.respondText(template, io.ktor.http.ContentType.Text.CSV)
                } catch (e: Exception) {
                    call.application.log.error("[EmployeeController] Failed to generate template", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to generate template: ${e.message}")
                    )
                }
            }
        }
        }
    }
}
