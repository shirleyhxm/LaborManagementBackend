package org.labormanagement.plugin

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.util.*
import org.labormanagement.model.UserRole
import org.labormanagement.service.*
import java.util.UUID

/**
 * Ktor plugin for multi-tenant request handling.
 *
 * Responsibilities:
 * 1. Extract JWT token from Authorization header
 * 2. Extract businessId from X-Business-Id header or path parameter
 * 3. Validate user has access to the business
 * 4. Set TenantContext for the request
 * 5. Clear context after request completion
 *
 * Usage:
 * ```
 * install(TenantInterceptor) {
 *     businessService = businessService
 *     jwtService = jwtService
 * }
 * ```
 */
class TenantInterceptorPlugin(private val config: Configuration) {

    class Configuration {
        var businessService: BusinessService? = null
        var jwtService: JwtService? = null
    }

    companion object Plugin : BaseApplicationPlugin<ApplicationCallPipeline, Configuration, TenantInterceptorPlugin> {
        override val key = AttributeKey<TenantInterceptorPlugin>("TenantInterceptor")

        override fun install(
            pipeline: ApplicationCallPipeline,
            configure: Configuration.() -> Unit
        ): TenantInterceptorPlugin {
            val configuration = Configuration().apply(configure)
            val plugin = TenantInterceptorPlugin(configuration)

            val businessService = configuration.businessService
                ?: throw IllegalStateException("BusinessService must be configured for TenantInterceptor")
            val jwtService = configuration.jwtService
                ?: throw IllegalStateException("JwtService must be configured for TenantInterceptor")

            // Intercept after authentication (must run after authenticate blocks set the principal)
            pipeline.intercept(ApplicationCallPipeline.Call) {
                try {
                    val path = call.request.path()
                    val method = call.request.httpMethod

                    // Skip OPTIONS requests (CORS preflight)
                    if (method == HttpMethod.Options) {
                        return@intercept
                    }

                    // Check if this is a public endpoint (no businessId required)
                    val isPublic = isPublicEndpoint(path)

                    // Extract user information from JWT
                    call.application.log.info("[TenantInterceptor] Processing $method $path (isPublic=$isPublic)")
                    val authHeader = call.request.headers["Authorization"]
                    call.application.log.info("[TenantInterceptor] Auth header: ${if (authHeader != null) "Present (${authHeader.take(30)}...)" else "Missing"}")

                    val principal = call.principal<JWTPrincipal>()
                    call.application.log.info("[TenantInterceptor] JWT Principal: ${if (principal != null) "Present" else "null"}")

                    if (principal == null) {
                        call.application.log.warn("[TenantInterceptor] No JWT principal - skipping context setup for $path")
                        // No authentication - context not set, endpoints should handle appropriately
                        return@intercept
                    }

                    val userId = principal.payload.getClaim("userId").asString()
                    val roleString = principal.payload.getClaim("role").asString()
                    val userRole = try {
                        UserRole.valueOf(roleString)
                    } catch (e: IllegalArgumentException) {
                        UserRole.EMPLOYEE // Default fallback
                    }

                    // Extract businessId from header or path
                    val businessId = extractBusinessId(call)

                    // For public endpoints, set context with userId only (no businessId validation)
                    if (isPublic) {
                        call.application.log.info("[TenantInterceptor] Public endpoint - setting context with userId=$userId, businessId=$businessId")
                        val tenantContext = TenantContext(
                            userId = userId,
                            businessId = businessId, // May be null for public endpoints
                            userRole = userRole
                        )
                        TenantContextHolder.setContext(tenantContext)
                        call.application.log.info("[TenantInterceptor] Context set successfully for public endpoint")
                        return@intercept
                    }

                    // For non-public endpoints, require and validate businessId
                    if (businessId != null) {
                        // Validate user has access to this business
                        try {
                            businessService.validateAccess(userId, businessId)
                        } catch (e: ForbiddenException) {
                            println("Access denied for user $userId to business $businessId: ${e.message}")
                            call.respond(
                                HttpStatusCode.Forbidden,
                                mapOf("error" to e.message)
                            )
                            finish()
                            return@intercept
                        } catch (e: NotFoundException) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                mapOf("error" to e.message)
                            )
                            finish()
                            return@intercept
                        }

                        // Set tenant context for this request
                        val tenantContext = TenantContext(
                            userId = userId,
                            businessId = businessId,
                            userRole = userRole
                        )
                        TenantContextHolder.setContext(tenantContext)
                    }
                } catch (e: Exception) {
                    // Log error but don't block request - let endpoint handle missing context
                    call.application.log.warn("Error setting tenant context: ${e.message}")
                }
            }

            // Clear context after request processing
            pipeline.environment?.monitor?.subscribe(ApplicationStopping) {
                // Cleanup on application stop
            }

            // Use response pipeline to clear context after each request
            pipeline.sendPipeline.intercept(ApplicationSendPipeline.After) {
                TenantContextHolder.clear()
            }

            return plugin
        }

        /**
         * Check if the endpoint is public (doesn't require tenant context)
         */
        private fun isPublicEndpoint(path: String): Boolean {
            val publicPaths = listOf(
                "/health",
                "/api",
                "/api/auth/login",
                "/api/auth/register", // User registration
                "/api/auth/forgot-password",
                "/api/auth/reset-password",
                "/api/auth/verify-2fa",
                "/api/businesses", // Business management (handles auth internally)
                "/api/test" // Test endpoints (should be removed in production)
            )

            return publicPaths.any { path == it || path.startsWith(it) }
        }

        /**
         * Extract businessId from request.
         * Looks in:
         * 1. X-Business-Id header
         * 2. Path parameter /api/businesses/{businessId}/...
         * 3. Query parameter ?businessId=...
         */
        private fun extractBusinessId(call: ApplicationCall): UUID? {
            // 1. Check X-Business-Id header
            val headerBusinessId = call.request.header("X-Business-Id")
            if (headerBusinessId != null) {
                return try {
                    UUID.fromString(headerBusinessId)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }

            // 2. Check path parameter /api/businesses/{businessId}/...
            val path = call.request.path()
            val businessPathRegex = Regex("""/api/businesses/([0-9a-fA-F-]{36})(/.*)?""")
            val matchResult = businessPathRegex.find(path)
            if (matchResult != null) {
                val businessIdString = matchResult.groupValues[1]
                return try {
                    UUID.fromString(businessIdString)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }

            // 3. Check query parameter
            val queryBusinessId = call.request.queryParameters["businessId"]
            if (queryBusinessId != null) {
                return try {
                    UUID.fromString(queryBusinessId)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }

            return null
        }
    }
}

/**
 * Convenience function to install TenantInterceptor
 */
fun Application.configureTenantInterceptor(
    businessService: BusinessService,
    jwtService: JwtService
) {
    install(TenantInterceptorPlugin) {
        this.businessService = businessService
        this.jwtService = jwtService
    }
}
