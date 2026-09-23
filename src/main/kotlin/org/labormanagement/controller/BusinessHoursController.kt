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
import org.labormanagement.dto.BusinessHourOverrideDto
import org.labormanagement.dto.UpdateBusinessHoursRequest
import org.labormanagement.model.UserRole
import org.labormanagement.service.BusinessHoursService
import org.labormanagement.service.ForbiddenException
import org.labormanagement.service.NotFoundException
import org.labormanagement.service.TenantContext
import org.labormanagement.service.TenantContextHolder
import org.labormanagement.service.UnauthorizedException
import java.util.UUID

/**
 * Opening-hours endpoints, mounted under the business they belong to.
 *
 * Every write returns the business's complete hours rather than just what changed, so the
 * client never has to merge a partial response into its own copy - saving one override and
 * re-rendering the week are the same round trip.
 */
class BusinessHoursController(
    private val businessHoursService: BusinessHoursService
) {

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

    private suspend fun ApplicationCall.businessIdParam(): UUID? {
        val raw = parameters["businessId"] ?: return null
        return try {
            UUID.fromString(raw)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun Route.businessHoursRoutes() {
        route("/api/businesses/{businessId}/hours") {
            authenticate("auth-jwt") {

                // The business's week plus its date exceptions.
                get {
                    try {
                        call.setupTenantContext()
                            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                        val businessId = call.businessIdParam()
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))

                        call.respond(HttpStatusCode.OK, businessHoursService.getHours(businessId))
                    } catch (e: NotFoundException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
                    } catch (e: UnauthorizedException) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to load business hours", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to load business hours"))
                    } finally {
                        TenantContextHolder.clear()
                    }
                }

                // Replace the weekly pattern. All seven days, saved as a unit.
                put {
                    try {
                        val userId = call.setupTenantContext()
                            ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                        val businessId = call.businessIdParam()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))

                        val request = call.receive<UpdateBusinessHoursRequest>()

                        call.respond(
                            HttpStatusCode.OK,
                            businessHoursService.updateWeek(userId, businessId, request)
                        )
                    } catch (e: NotFoundException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
                    } catch (e: ForbiddenException) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                    } catch (e: UnauthorizedException) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to update business hours", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    } finally {
                        TenantContextHolder.clear()
                    }
                }

                // Add or replace a date exception.
                post("/overrides") {
                    try {
                        val userId = call.setupTenantContext()
                            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                        val businessId = call.businessIdParam()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))

                        val dto = call.receive<BusinessHourOverrideDto>()

                        call.respond(
                            HttpStatusCode.OK,
                            businessHoursService.saveOverride(userId, businessId, dto)
                        )
                    } catch (e: NotFoundException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
                    } catch (e: ForbiddenException) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                    } catch (e: UnauthorizedException) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to save business hours override", e)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    } finally {
                        TenantContextHolder.clear()
                    }
                }

                delete("/overrides/{overrideId}") {
                    try {
                        val userId = call.setupTenantContext()
                            ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

                        val businessId = call.businessIdParam()
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid business ID"))

                        val overrideId = try {
                            UUID.fromString(call.parameters["overrideId"])
                        } catch (e: IllegalArgumentException) {
                            return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid override ID"))
                        }

                        call.respond(
                            HttpStatusCode.OK,
                            businessHoursService.deleteOverride(userId, businessId, overrideId)
                        )
                    } catch (e: NotFoundException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
                    } catch (e: ForbiddenException) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                    } catch (e: UnauthorizedException) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to delete business hours override", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete override"))
                    } finally {
                        TenantContextHolder.clear()
                    }
                }
            }
        }
    }
}
