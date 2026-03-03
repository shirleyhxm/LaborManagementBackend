package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.labormanagement.model.EmployeeGroup
import org.labormanagement.repository.EmployeeGroupRepository
import org.labormanagement.service.TenantContextHolder
import java.util.UUID

class EmployeeGroupController(
    private val employeeGroupRepository: EmployeeGroupRepository
) {

    fun Route.employeeGroupRoutes() {
        route("/api/businesses/{businessId}/employee-groups") {

            // Create/add a new group tag
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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employee groups from different business"))
                        return@post
                    }

                    val request = call.receive<CreateEmployeeGroupRequest>()
                    val name = request.name.trim()

                    if (name.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Group name cannot be empty"))
                        return@post
                    }

                    val created = employeeGroupRepository.addForBusiness(businessId, name)
                    if (created == null) {
                        call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "Employee group already exists (case-insensitive)")
                        )
                    } else {
                        call.respond(HttpStatusCode.Created, created.toResponse())
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (e.message ?: "Invalid request"))
                    )
                }
            }

            // Get all employee group tags
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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employee groups from different business"))
                        return@get
                    }

                    val groups = employeeGroupRepository.findAllForBusiness(businessId)
                    call.respond(HttpStatusCode.OK, groups.map { it.toResponse() })
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            // Get employee group by name
            get("/{name}") {
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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employee groups from different business"))
                        return@get
                    }

                    val name = call.parameters["name"]?.trim()
                    if (name.isNullOrEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group name"))
                        return@get
                    }

                    if (employeeGroupRepository.existsForBusiness(businessId, name)) {
                        call.respond(HttpStatusCode.OK, EmployeeGroup(businessId, name).toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee group not found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            // Rename a group tag
            put("/{name}") {
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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employee groups from different business"))
                        return@put
                    }

                    val oldName = call.parameters["name"]?.trim()
                    if (oldName.isNullOrEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group name"))
                        return@put
                    }

                    val request = call.receive<RenameEmployeeGroupRequest>()
                    val newName = request.newName.trim()

                    if (newName.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "New group name cannot be empty"))
                        return@put
                    }

                    val renamed = employeeGroupRepository.renameForBusiness(businessId, oldName, newName)
                    if (renamed) {
                        call.respond(HttpStatusCode.OK, EmployeeGroup(businessId, newName).toResponse())
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Employee group not found or new name already exists")
                        )
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            // Delete a group tag
            delete("/{name}") {
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
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot access employee groups from different business"))
                        return@delete
                    }

                    val name = call.parameters["name"]?.trim()
                    if (name.isNullOrEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group name"))
                        return@delete
                    }

                    val deleted = employeeGroupRepository.deleteForBusiness(businessId, name)
                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee group not found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }
        }
    }
}

// DTOs
data class CreateEmployeeGroupRequest(
    val name: String
)

data class RenameEmployeeGroupRequest(
    val newName: String
)

data class EmployeeGroupResponse(
    val name: String
)

fun EmployeeGroup.toResponse(): EmployeeGroupResponse {
    return EmployeeGroupResponse(
        name = this.name
    )
}
