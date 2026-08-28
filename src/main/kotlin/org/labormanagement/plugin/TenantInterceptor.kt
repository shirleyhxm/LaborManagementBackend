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

            // Runs on the Call phase, ahead of the route's authenticate block,
            // so it verifies the bearer token itself rather than relying on a
            // principal that is not installed yet.
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

                    // This runs on ApplicationCallPipeline.Call, which is
                    // *before* the route's authenticate("auth-jwt") block
                    // installs the principal - so the principal is normally
                    // still null here. Verify the bearer token directly
                    // instead; jwtService.verifyToken checks signature,
                    // audience and issuer, and returns null on anything
                    // invalid or expired, so this is not a weaker check.
                    // Requests without a usable token fall through untouched
                    // and the authenticate block rejects them as before.
                    val claims = call.principal<JWTPrincipal>()?.payload
                        ?: authHeader
                            ?.removePrefix("Bearer ")
                            ?.trim()
                            ?.let { jwtService.verifyToken(it) }

                    if (claims == null) {
                        call.application.log.warn("[TenantInterceptor] No verified JWT - skipping context setup for $path")
                        // No authentication - context not set, endpoints should handle appropriately
                        return@intercept
                    }

                    val userId = claims.getClaim("userId").asString()

                    // Account-level role from the token. Only used for public
                    // endpoints that have no business to scope to - anything
                    // business-scoped resolves its role per business below,
                    // since this claim cannot express "admin of my own chain,
                    // manager elsewhere" and would go stale on revocation.
                    val roleString = claims.getClaim("role").asString()
                    val accountRole = try {
                        UserRole.valueOf(roleString)
                    } catch (e: IllegalArgumentException) {
                        UserRole.EMPLOYEE // Default fallback
                    }

                    // Extract businessId from header or path
                    val businessId = extractBusinessId(call)

                    // For public endpoints, set context with userId only (no businessId validation)
                    if (isPublic) {
                        call.application.log.info("[TenantInterceptor] Public endpoint - setting context with userId=$userId, businessId=$businessId")
                        // "Public" here is broader than it looks: the prefix
                        // match on /api/businesses means every
                        // /api/businesses/{id}/... route lands in this branch,
                        // so business-scoped requests must still be authorized
                        // rather than waved through on the account claim.
                        val publicRole = if (businessId != null) {
                            val resolved = businessService.resolveEffectiveRole(userId, businessId)
                            if (resolved == null) {
                                // No grant, no ownership, no employee record -
                                // e.g. a manager whose access was just revoked.
                                // Falling back to the token's claim here would
                                // keep them in until the token expired.
                                call.application.log.warn(
                                    "[TenantInterceptor] No role resolvable for user $userId " +
                                        "in business $businessId - denying"
                                )
                                call.respond(
                                    HttpStatusCode.Forbidden,
                                    mapOf("error" to "User does not have access to business: $businessId")
                                )
                                finish()
                                return@intercept
                            }
                            resolved
                        } else {
                            accountRole
                        }

                        val tenantContext = TenantContext(
                            userId = userId,
                            businessId = businessId, // May be null for public endpoints
                            userRole = publicRole
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

                        // Resolve what this user may do *in this business*.
                        // validateAccess above already established they can
                        // reach it, so a null here would mean the two
                        // disagreed - deny rather than guess.
                        val effectiveRole = businessService.resolveEffectiveRole(userId, businessId)
                        if (effectiveRole == null) {
                            call.application.log.warn(
                                "[TenantInterceptor] No role resolvable for user $userId in business $businessId"
                            )
                            call.respond(
                                HttpStatusCode.Forbidden,
                                mapOf("error" to "User does not have access to business: $businessId")
                            )
                            finish()
                            return@intercept
                        }

                        // Set tenant context for this request
                        val tenantContext = TenantContext(
                            userId = userId,
                            businessId = businessId,
                            userRole = effectiveRole
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
