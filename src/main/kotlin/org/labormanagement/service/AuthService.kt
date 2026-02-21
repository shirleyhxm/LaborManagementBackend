package org.labormanagement.service

import org.labormanagement.model.*
import org.labormanagement.repository.PasswordResetRepository
import org.labormanagement.repository.UserRepository

class AuthService(
    private val userRepository: UserRepository,
    private val jwtService: JwtService,
    private val passwordResetRepository: PasswordResetRepository = PasswordResetRepository(),
    private val totpService: TotpService = TotpService()
) {
    // Temporary storage for pending 2FA verifications during login
    // In production, use Redis or similar with expiration
    private val pending2FALogins = mutableMapOf<String, User>()
    /**
     * Authenticate user with username and password
     * Returns AuthResponse with user info and token if successful
     * Returns null if authentication fails
     * Throws Requires2FAException if 2FA is enabled for the user
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

        // Check if 2FA is enabled
        if (user.twoFactorEnabled && user.twoFactorSecret != null) {
            // Store user temporarily for 2FA verification (expires after 5 minutes in production)
            pending2FALogins[loginRequest.username] = user
            throw Requires2FAException("Two-factor authentication required")
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
     * Verify 2FA code and complete login
     */
    fun verify2FAAndLogin(request: Verify2FARequest): AuthResponse? {
        // Get pending user
        val user = pending2FALogins[request.username] ?: return null

        // Verify 2FA code
        if (user.twoFactorSecret == null || !totpService.verifyCode(user.twoFactorSecret, request.code)) {
            return null
        }

        // Remove from pending logins
        pending2FALogins.remove(request.username)

        // Generate JWT token
        val token = jwtService.generateToken(user)

        // Return auth response
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

    /**
     * Initiate password reset flow
     * Generates a reset token and sends it to the user's email
     * Always returns success to prevent email enumeration attacks
     */
    fun forgotPassword(request: ForgotPasswordRequest): ForgotPasswordResponse {
        // Validate email format
        if (!isValidEmail(request.email)) {
            // Return success anyway to prevent email enumeration
            return ForgotPasswordResponse(
                "If an account exists with this email, a password reset link has been sent."
            )
        }

        // Find user by email
        val user = userRepository.findByEmail(request.email)

        if (user != null) {
            // Generate reset token
            val resetToken = passwordResetRepository.createResetToken(user.id, user.email)

            // TODO: In production, send email with reset link
            // For now, we'll log the token (in production, never log tokens!)
            println("Password reset token for ${user.email}: ${resetToken.token}")
            println("Reset link: http://localhost:8080/reset-password?token=${resetToken.token}")
        }

        // Always return the same message to prevent email enumeration
        return ForgotPasswordResponse(
            "If an account exists with this email, a password reset link has been sent."
        )
    }

    /**
     * Reset password using a valid reset token
     */
    fun resetPassword(request: ResetPasswordRequest): ResetPasswordResponse {
        // Validate token
        if (!passwordResetRepository.isValidToken(request.token)) {
            throw IllegalArgumentException("Invalid or expired reset token")
        }

        val resetToken = passwordResetRepository.findByToken(request.token)
            ?: throw IllegalArgumentException("Invalid or expired reset token")

        // Update user password (this will validate password strength)
        val success = userRepository.updatePassword(resetToken.userId, request.newPassword)

        if (!success) {
            throw IllegalStateException("Failed to update password")
        }

        // Mark token as used
        passwordResetRepository.markTokenAsUsed(request.token)

        return ResetPasswordResponse("Password has been reset successfully")
    }

    /**
     * Set up 2FA for a user
     */
    fun setup2FA(userId: String): Setup2FAResponse {
        val user = userRepository.findById(userId)
            ?: throw IllegalArgumentException("User not found")

        // Generate new secret
        val secret = totpService.generateSecret()

        // Store secret in user record (not enabled yet)
        userRepository.setup2FA(userId, secret)

        // Generate QR code URL
        val qrCodeUrl = totpService.generateQrCodeUrl(secret, user.email)

        return Setup2FAResponse(
            secret = secret,
            qrCodeUrl = qrCodeUrl,
            message = "Scan the QR code with your authenticator app and verify with a code to enable 2FA"
        )
    }

    /**
     * Enable 2FA after verifying initial code
     */
    fun enable2FA(request: Enable2FARequest): Enable2FAResponse {
        val user = userRepository.findById(request.userId)
            ?: throw IllegalArgumentException("User not found")

        val secret = user.twoFactorSecret
            ?: throw IllegalStateException("2FA setup not initiated. Call setup2FA first.")

        // Verify the code
        if (!totpService.verifyCode(secret, request.verificationCode)) {
            throw IllegalArgumentException("Invalid verification code")
        }

        // Enable 2FA
        userRepository.enable2FA(request.userId)

        return Enable2FAResponse(
            enabled = true,
            message = "Two-factor authentication enabled successfully"
        )
    }

    /**
     * Disable 2FA for a user
     */
    fun disable2FA(request: Disable2FARequest): Disable2FAResponse {
        val user = userRepository.findById(request.userId)
            ?: throw IllegalArgumentException("User not found")

        // Verify password before disabling 2FA
        if (!userRepository.verifyPassword(request.password, user.passwordHash)) {
            throw IllegalArgumentException("Invalid password")
        }

        // Disable 2FA
        userRepository.disable2FA(request.userId)

        return Disable2FAResponse(
            disabled = true,
            message = "Two-factor authentication disabled successfully"
        )
    }

    /**
     * Simple email validation
     */
    private fun isValidEmail(email: String): Boolean {
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$".toRegex()
        return email.matches(emailRegex)
    }
}

/**
 * Exception thrown when 2FA is required during login
 */
class Requires2FAException(message: String) : Exception(message)