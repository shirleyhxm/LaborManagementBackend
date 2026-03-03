package org.labormanagement.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.labormanagement.model.*
import org.labormanagement.service.AuthService
import org.labormanagement.service.RateLimiter
import org.labormanagement.service.Requires2FAException

class AuthController(
    private val authService: AuthService,
    private val rateLimiter: RateLimiter = RateLimiter()
) {
    fun Route.authRoutes() {
        route("/api/auth") {
            // POST /api/auth/register - Register new user and create default business
            post("/register") {
                try {
                    val registerRequest = call.receive<RegisterRequest>()

                    // Validate input
                    if (registerRequest.email.isBlank() ||
                        registerRequest.firstName.isBlank() ||
                        registerRequest.lastName.isBlank() ||
                        registerRequest.password.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("All fields are required")
                        )
                        return@post
                    }

                    // Register user
                    val authResponse = authService.register(registerRequest)
                    call.respond(HttpStatusCode.Created, authResponse)
                } catch (e: org.labormanagement.service.ConflictException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(e.message ?: "Registration conflict")
                    )
                } catch (e: org.labormanagement.service.BadRequestException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(e.message ?: "Invalid registration request")
                    )
                } catch (e: Exception) {
                    call.application.log.error("Registration error", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Registration failed: ${e.message}")
                    )
                }
            }

            // POST /api/auth/login - Authenticate user and return JWT token
            post("/login") {
                try {
                    val loginRequest = call.receive<LoginRequest>()

                    // Validate input
                    if (loginRequest.email.isBlank() || loginRequest.password.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Email and password are required")
                        )
                        return@post
                    }

                    // Get identifier for rate limiting (use IP + email combination)
                    val ipAddress = call.request.local.remoteHost
                    val identifier = "${ipAddress}:${loginRequest.email}"

                    // Check if locked out due to too many failed attempts
                    if (rateLimiter.isLockedOut(identifier)) {
                        val timeRemaining = rateLimiter.getLockoutTimeRemaining(identifier)
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            ErrorResponse(
                                "Too many failed login attempts. " +
                                "Account locked for ${timeRemaining ?: 0} minutes."
                            )
                        )
                        return@post
                    }

                    // Attempt authentication
                    try {
                        val authResponse = authService.login(loginRequest)

                        if (authResponse != null) {
                            // Successful login - clear failed attempts
                            rateLimiter.recordSuccessfulLogin(identifier)
                            call.respond(HttpStatusCode.OK, authResponse)
                        } else {
                            // Failed login - record attempt
                            val isLockedOut = rateLimiter.recordFailedAttempt(identifier)

                            if (isLockedOut) {
                                val timeRemaining = rateLimiter.getLockoutTimeRemaining(identifier)
                                call.respond(
                                    HttpStatusCode.TooManyRequests,
                                    ErrorResponse(
                                        "Too many failed login attempts. " +
                                        "Account locked for ${timeRemaining ?: 0} minutes."
                                    )
                                )
                            } else {
                                val remaining = rateLimiter.getRemainingAttempts(identifier)
                                call.respond(
                                    HttpStatusCode.Unauthorized,
                                    ErrorResponse(
                                        "Invalid email or password. " +
                                        "$remaining attempts remaining before lockout."
                                    )
                                )
                            }
                        }
                    } catch (e: Requires2FAException) {
                        // 2FA is required - don't clear rate limit attempts yet
                        call.respond(
                            HttpStatusCode.Accepted,
                            Login2FARequiredResponse(
                                requires2FA = true,
                                message = "Two-factor authentication required. Please provide your verification code."
                            )
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid request format")
                    )
                }
            }

            // POST /api/auth/verify-2fa - Verify 2FA code and complete login
            post("/verify-2fa") {
                try {
                    val request = call.receive<Verify2FARequest>()

                    // Validate input
                    if (request.email.isBlank() || request.code.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Email and verification code are required")
                        )
                        return@post
                    }

                    // Get identifier for rate limiting
                    val ipAddress = call.request.local.remoteHost
                    val identifier = "${ipAddress}:${request.email}"

                    // Verify 2FA and complete login
                    val authResponse = authService.verify2FAAndLogin(request)

                    if (authResponse != null) {
                        // Successful 2FA verification - clear failed attempts
                        rateLimiter.recordSuccessfulLogin(identifier)
                        call.respond(HttpStatusCode.OK, authResponse)
                    } else {
                        // Failed verification - record attempt
                        val isLockedOut = rateLimiter.recordFailedAttempt(identifier)

                        if (isLockedOut) {
                            val timeRemaining = rateLimiter.getLockoutTimeRemaining(identifier)
                            call.respond(
                                HttpStatusCode.TooManyRequests,
                                ErrorResponse(
                                    "Too many failed attempts. " +
                                    "Account locked for ${timeRemaining ?: 0} minutes."
                                )
                            )
                        } else {
                            val remaining = rateLimiter.getRemainingAttempts(identifier)
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                ErrorResponse(
                                    "Invalid verification code. " +
                                    "$remaining attempts remaining before lockout."
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid request format")
                    )
                }
            }

            // POST /api/auth/forgot-password - Request password reset
            post("/forgot-password") {
                try {
                    val request = call.receive<ForgotPasswordRequest>()

                    // Validate email
                    if (request.email.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Email is required")
                        )
                        return@post
                    }

                    val response = authService.forgotPassword(request)
                    call.respond(HttpStatusCode.OK, response)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid request format")
                    )
                }
            }

            // POST /api/auth/reset-password - Reset password with token
            post("/reset-password") {
                try {
                    val request = call.receive<ResetPasswordRequest>()

                    // Validate input
                    if (request.token.isBlank() || request.newPassword.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Token and new password are required")
                        )
                        return@post
                    }

                    val response = authService.resetPassword(request)
                    call.respond(HttpStatusCode.OK, response)
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(e.message ?: "Invalid request")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to reset password")
                    )
                }
            }

            // Protected routes - require JWT authentication
            authenticate("auth-jwt") {
                // POST /api/auth/logout - Logout user (server-side token cleanup)
                post("/logout") {
                    try {
                        val principal = call.principal<JWTPrincipal>()
                        if (principal != null) {
                            // Token is valid, logout successful
                            call.respond(
                                HttpStatusCode.OK,
                                LogoutResponse("Logged out successfully")
                            )
                        } else {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                ErrorResponse("Invalid or expired token")
                            )
                        }
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Logout failed")
                        )
                    }
                }

                // GET /api/auth/validate - Validate JWT token
                get("/validate") {
                    try {
                        val principal = call.principal<JWTPrincipal>()
                        if (principal != null) {
                            val userId = principal.payload.getClaim("userId").asString()
                            val user = authService.validateToken(
                                call.request.header("Authorization")?.removePrefix("Bearer ")?.trim() ?: ""
                            )

                            if (user != null) {
                                call.respond(
                                    HttpStatusCode.OK,
                                    ValidateResponse(
                                        valid = true,
                                        user = user.toDTO()
                                    )
                                )
                            } else {
                                call.respond(
                                    HttpStatusCode.Unauthorized,
                                    ErrorResponse("Invalid or expired token")
                                )
                            }
                        } else {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                ErrorResponse("Invalid or expired token")
                            )
                        }
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Invalid or expired token")
                        )
                    }
                }

                // POST /api/auth/2fa/setup - Set up 2FA for current user
                post("/2fa/setup") {
                    try {
                        val principal = call.principal<JWTPrincipal>()
                        if (principal == null) {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                ErrorResponse("Invalid or expired token")
                            )
                            return@post
                        }

                        val userId = principal.payload.getClaim("userId").asString()
                        val response = authService.setup2FA(userId)
                        call.respond(HttpStatusCode.OK, response)
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(e.message ?: "Invalid request")
                        )
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Failed to set up 2FA")
                        )
                    }
                }

                // POST /api/auth/2fa/enable - Enable 2FA after verifying code
                post("/2fa/enable") {
                    try {
                        val principal = call.principal<JWTPrincipal>()
                        if (principal == null) {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                ErrorResponse("Invalid or expired token")
                            )
                            return@post
                        }

                        val request = call.receive<Enable2FARequest>()
                        val userId = principal.payload.getClaim("userId").asString()

                        // Ensure user can only enable 2FA for themselves
                        if (request.userId != userId) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                ErrorResponse("Cannot enable 2FA for another user")
                            )
                            return@post
                        }

                        val response = authService.enable2FA(request)
                        call.respond(HttpStatusCode.OK, response)
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(e.message ?: "Invalid request")
                        )
                    } catch (e: IllegalStateException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(e.message ?: "Invalid state")
                        )
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Failed to enable 2FA")
                        )
                    }
                }

                // POST /api/auth/2fa/disable - Disable 2FA for current user
                post("/2fa/disable") {
                    try {
                        val principal = call.principal<JWTPrincipal>()
                        if (principal == null) {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                ErrorResponse("Invalid or expired token")
                            )
                            return@post
                        }

                        val request = call.receive<Disable2FARequest>()
                        val userId = principal.payload.getClaim("userId").asString()

                        // Ensure user can only disable 2FA for themselves
                        if (request.userId != userId) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                ErrorResponse("Cannot disable 2FA for another user")
                            )
                            return@post
                        }

                        val response = authService.disable2FA(request)
                        call.respond(HttpStatusCode.OK, response)
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(e.message ?: "Invalid request")
                        )
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Failed to disable 2FA")
                        )
                    }
                }
            }
        }
    }
}