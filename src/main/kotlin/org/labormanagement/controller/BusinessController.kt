package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.labormanagement.dto.CreateBusinessRequest
import org.labormanagement.dto.UpdateBusinessRequest
import org.labormanagement.model.UserRole
import org.labormanagement.service.BusinessService
import org.labormanagement.service.ForbiddenException
import org.labormanagement.service.NotFoundException
import org.labormanagement.service.TenantContext
import org.labormanagement.service.TenantContextHolder
import org.labormanagement.service.UnauthorizedException
import java.util.UUID

/**
 * Controller for business management endpoints.
 * Handles CRUD operations for businesses in a multi-tenant system.
 */
class BusinessController(
    private val businessService: BusinessService
) {

    /**
     * Helper function to set up tenant context from JWT principal
     * Returns userId if successful, null if authentication failed
     */
    private suspend fun ApplicationCall.setupTenantContext(): String? {
        val principal = principal<JWTPrincipal>() ?: return null

        val userId = principal.payload.getClaim("userId").asString()
        val roleString = principal.payload.getClaim("role").asString()
        val userRole = try {
            UserRole.valueOf(roleString)
        } catch (e: IllegalArgumentException) {
            UserRole.EMPLOYEE
        }

        TenantContextHolder.setContext(TenantContext(
            userId = userId,
            businessId = null,
            userRole = userRole
        ))

        return userId
    }

    fun Route.businessRoutes() {
        route("/api/businesses") {
            authenticate("auth-jwt") {
            // Create a new business
            post {
                try {
                    val userId = call.setupTenantContext()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                    val request = call.receive<CreateBusinessRequest>()

                    val business = businessService.createBusiness(userId, request)

                    call.respond(HttpStatusCode.Created, business)
                } catch (e: UnauthorizedException) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.log.error("Failed to create business", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (e.message ?: "Invalid request"))
                    )
                } finally {
                    TenantContextHolder.clear()
                }
            }

            // Get all businesses for the authenticated user
            get {
                try {
                    val userId = call.setupTenantContext()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                    val businesses = businessService.getBusinessesForUser(userId)
                    call.respond(HttpStatusCode.OK, businesses)
                } catch (e: UnauthorizedException) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.log.error("Failed to get businesses", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve businesses")
                    )
                } finally {
                    TenantContextHolder.clear()
                }
            }

            // Get a specific business by ID
            get("/{businessId}") {
                try {
                    val userId = call.setupTenantContext()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                    val businessIdStr = call.parameters["businessId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Business ID required"))

                    val businessId = try {
                        UUID.fromString(businessIdStr)
                    } catch (e: IllegalArgumentException) {
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID format"))
                    }

                    val business = businessService.getBusiness(userId, businessId)

                    call.respond(HttpStatusCode.OK, business)
                } catch (e: UnauthorizedException) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                } catch (e: NotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
                } catch (e: ForbiddenException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.log.error("Failed to get business", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve business")
                    )
                } finally {
                    TenantContextHolder.clear()
                }
            }

            // Update a business
            put("/{businessId}") {
                try {
                    val userId = call.setupTenantContext()
                        ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                    val businessIdStr = call.parameters["businessId"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Business ID required"))

                    val businessId = try {
                        UUID.fromString(businessIdStr)
                    } catch (e: IllegalArgumentException) {
                        return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID format"))
                    }

                    val request = call.receive<UpdateBusinessRequest>()

                    val updatedBusiness = businessService.updateBusiness(userId, businessId, request)

                    call.respond(HttpStatusCode.OK, updatedBusiness)
                } catch (e: UnauthorizedException) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                } catch (e: NotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
                } catch (e: ForbiddenException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.log.error("Failed to update business", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (e.message ?: "Invalid request"))
                    )
                } finally {
                    TenantContextHolder.clear()
                }
            }

            // Delete a business (soft delete)
            delete("/{businessId}") {
                try {
                    val userId = call.setupTenantContext()
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                    val businessIdStr = call.parameters["businessId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Business ID required"))

                    val businessId = try {
                        UUID.fromString(businessIdStr)
                    } catch (e: IllegalArgumentException) {
                        return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID format"))
                    }

                    businessService.deleteBusiness(userId, businessId)

                    call.respond(HttpStatusCode.NoContent)
                } catch (e: UnauthorizedException) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                } catch (e: NotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
                } catch (e: ForbiddenException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.log.error("Failed to delete business", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete business")
                    )
                } finally {
                    TenantContextHolder.clear()
                }
            }
            } // End authenticate
        }
    }
}
