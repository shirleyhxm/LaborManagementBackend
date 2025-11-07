package org.labormanagement.service

import org.labormanagement.model.AuthResponse
import org.labormanagement.model.LoginRequest
import org.labormanagement.model.User
import org.labormanagement.model.toDTO
import org.labormanagement.repository.UserRepository

class AuthService(
    private val userRepository: UserRepository,
    private val jwtService: JwtService
) {
    /**
     * Authenticate user with username and password
     * Returns AuthResponse with user info and token if successful
     * Returns null if authentication fails
     */
    fun login(loginRequest: LoginRequest): AuthResponse? {
        // Validate input
        if (loginRequest.username.isBlank() || loginRequest.password.isBlank()) {
            return null
        }

        // Find user by username
        val user = userRepository.findByUsername(loginRequest.username) ?: return null

        // Verify password
        if (!userRepository.verifyPassword(loginRequest.password, user.passwordHash)) {
            return null
        }

        // Generate JWT token
        val token = jwtService.generateToken(user)

        // Return auth response with user DTO (never expose password hash)
        return AuthResponse(
            user = user.toDTO(),
            token = token
        )
    }

    /**
     * Validate a JWT token and return the associated user
     * Returns user if token is valid, null otherwise
     */
    fun validateToken(token: String): User? {
        val userId = jwtService.getUserIdFromToken(token) ?: return null
        return userRepository.findById(userId)
    }

    /**
     * Logout - in a stateless JWT system, this is primarily handled client-side
     * Server-side implementation for potential token blacklisting in the future
     */
    fun logout(token: String): Boolean {
        // For now, just verify the token is valid
        // In production, you might want to add the token to a blacklist
        return jwtService.verifyToken(token) != null
    }
}