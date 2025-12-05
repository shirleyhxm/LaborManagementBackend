package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.labormanagement.model.EmployeeGroup
import org.labormanagement.repository.EmployeeGroupRepository

class EmployeeGroupController(
    private val employeeGroupRepository: EmployeeGroupRepository
) {

    fun Route.employeeGroupRoutes() {
        route("/api/employee-groups") {

            // Create/add a new group tag
            post {
                try {
                    val request = call.receive<CreateEmployeeGroupRequest>()
                    val name = request.name.trim()

                    if (name.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Group name cannot be empty"))
                        return@post
                    }

                    val created = employeeGroupRepository.add(name)
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
                val groups = employeeGroupRepository.findAll()
                call.respond(HttpStatusCode.OK, groups.map { it.toResponse() })
            }

            // Get employee group by name
            get("/{name}") {
                val name = call.parameters["name"]?.trim()

                if (name.isNullOrEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group name"))
                    return@get
                }

                if (employeeGroupRepository.exists(name)) {
                    call.respond(HttpStatusCode.OK, EmployeeGroup(name).toResponse())
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee group not found"))
                }
            }

            // Rename a group tag
            put("/{name}") {
                val oldName = call.parameters["name"]?.trim()

                if (oldName.isNullOrEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group name"))
                    return@put
                }

                try {
                    val request = call.receive<RenameEmployeeGroupRequest>()
                    val newName = request.newName.trim()

                    if (newName.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "New group name cannot be empty"))
                        return@put
                    }

                    val renamed = employeeGroupRepository.rename(oldName, newName)
                    if (renamed) {
                        call.respond(HttpStatusCode.OK, EmployeeGroup(newName).toResponse())
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
                val name = call.parameters["name"]?.trim()

                if (name.isNullOrEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group name"))
                    return@delete
                }

                val deleted = employeeGroupRepository.delete(name)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Employee group not found"))
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
