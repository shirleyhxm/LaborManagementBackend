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

class AuthController(
    private val authService: AuthService
) {
    fun Routing.authRoutes() {
        route("/api/auth") {
            // POST /api/auth/login - Authenticate user and return JWT token
            post("/login") {
                try {
                    val loginRequest = call.receive<LoginRequest>()

                    // Validate input
                    if (loginRequest.username.isBlank() || loginRequest.password.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Username and password are required")
                        )
                        return@post
                    }

                    // Attempt authentication
                    val authResponse = authService.login(loginRequest)

                    if (authResponse != null) {
                        call.respond(HttpStatusCode.OK, authResponse)
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Invalid username or password")
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid request format")
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
            }
        }
    }
}