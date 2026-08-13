package org.labormanagement.service

import org.labormanagement.model.*
import org.labormanagement.repository.PasswordResetRepository
import org.labormanagement.repository.RefreshTokenRepository
import org.labormanagement.repository.UserRepository
import org.labormanagement.dto.CreateBusinessRequest

class AuthService(
    private val userRepository: UserRepository,
    private val jwtService: JwtService,
    private val businessService: BusinessService? = null, // Optional for registration with auto-business creation
    private val passwordResetRepository: PasswordResetRepository = PasswordResetRepository(),
    private val refreshTokenRepository: RefreshTokenRepository = RefreshTokenRepository(),
    private val totpService: TotpService = TotpService()
) {
    // Temporary storage for pending 2FA verifications during login
    // In production, use Redis or similar with expiration
    private val pending2FALogins = mutableMapOf<String, User>()
    /**
     * Authenticate user with email and password
     * Returns AuthResponse with user info and token if successful
     * Returns null if authentication fails
     * Throws Requires2FAException if 2FA is enabled for the user
     */
    fun login(loginRequest: LoginRequest): AuthResponse? {
        // Validate input
        if (loginRequest.email.isBlank() || loginRequest.password.isBlank()) {
            return null
        }

        // Find user by email
        val user = userRepository.findByEmail(loginRequest.email) ?: return null

        // Verify password
        if (!userRepository.verifyPassword(loginRequest.password, user.passwordHash)) {
            return null
        }

        // Check if 2FA is enabled
        if (user.twoFactorEnabled && user.twoFactorSecret != null) {
            // Store user temporarily for 2FA verification (expires after 5 minutes in production)
            pending2FALogins[loginRequest.email] = user
            throw Requires2FAException("Two-factor authentication required")
        }

        // Generate JWT access token and a rotating refresh token
        val token = jwtService.generateToken(user)
        val refreshToken = refreshTokenRepository.createRefreshToken(user.id)

        // Return auth response with user DTO (never expose password hash)
        return AuthResponse(
            user = user.toDTO(),
            token = token,
            refreshToken = refreshToken.token
        )
    }

    /**
     * Register a new user and automatically create a default business.
     * Returns AuthResponse with user info, token, and businessId if successful.
     * Throws exceptions for validation errors (email exists, weak password, etc.)
     */
    fun register(registerRequest: RegisterRequest): AuthResponse {
        // Validate input
        if (registerRequest.email.isBlank() ||
            registerRequest.firstName.isBlank() ||
            registerRequest.lastName.isBlank() ||
            registerRequest.password.isBlank()) {
            throw BadRequestException("All fields are required")
        }

        // Check if email already exists
        if (userRepository.findByEmail(registerRequest.email) != null) {
            throw ConflictException("Email '${registerRequest.email}' is already registered")
        }

        // Create the user (this validates password strength)
        val user = try {
            userRepository.createUser(
                email = registerRequest.email,
                firstName = registerRequest.firstName,
                lastName = registerRequest.lastName,
                password = registerRequest.password,
                role = UserRole.ADMIN, // New users get admin role for their business
                accountType = AccountType.BUSINESS_OWNER
            )
        } catch (e: IllegalArgumentException) {
            // Password validation failure
            throw BadRequestException(e.message ?: "Registration failed")
        }

        // Auto-create default business if BusinessService is available
        val businessId = if (businessService != null) {
            try {
                val businessName = registerRequest.businessName ?: "${registerRequest.firstName}'s Business"
                val createBusinessRequest = CreateBusinessRequest(name = businessName)
                val business = businessService.createBusiness(user.id, createBusinessRequest)
                business.id
            } catch (e: Exception) {
                // Log error but don't fail registration - user can create business later
                println("Warning: Failed to auto-create business for user ${user.id}: ${e.message}")
                null
            }
        } else {
            null
        }

        // Generate JWT access token and a rotating refresh token
        val token = jwtService.generateToken(user)
        val refreshToken = refreshTokenRepository.createRefreshToken(user.id)

        // Return auth response with user DTO and businessId
        return AuthResponse(
            user = user.toDTO(),
            token = token,
            refreshToken = refreshToken.token,
            businessId = businessId?.toString()
        )
    }

    /**
     * Verify 2FA code and complete login
     */
    fun verify2FAAndLogin(request: Verify2FARequest): AuthResponse? {
        // Get pending user
        val user = pending2FALogins[request.email] ?: return null

        // Verify 2FA code
        if (user.twoFactorSecret == null || !totpService.verifyCode(user.twoFactorSecret, request.code)) {
            return null
        }

        // Remove from pending logins
        pending2FALogins.remove(request.email)

        // Generate JWT access token and a rotating refresh token
        val token = jwtService.generateToken(user)
        val refreshToken = refreshTokenRepository.createRefreshToken(user.id)

        // Return auth response
        return AuthResponse(
            user = user.toDTO(),
            token = token,
            refreshToken = refreshToken.token
        )
    }

    /**
     * Exchange a valid refresh token for a new access token and rotated refresh token.
     * Returns null if the refresh token is missing, expired, or already used/revoked.
     */
    fun refreshAccessToken(refreshToken: String): AuthResponse? {
        val rotated = refreshTokenRepository.rotateToken(refreshToken) ?: return null
        val user = userRepository.findById(rotated.userId) ?: return null

        val token = jwtService.generateToken(user)

        return AuthResponse(
            user = user.toDTO(),
            token = token,
            refreshToken = rotated.token
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
     * Logout - revokes the user's refresh token so it can no longer be used
     * to mint new access tokens. The access token itself remains valid
     * until it naturally expires, since JWTs are stateless.
     */
    fun logout(token: String, refreshToken: String? = null): Boolean {
        val valid = jwtService.verifyToken(token) != null
        if (refreshToken != null) {
            refreshTokenRepository.revokeToken(refreshToken)
        }
        return valid
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

        // Revoke all existing refresh tokens so other sessions can't silently
        // keep minting new access tokens after a password reset
        refreshTokenRepository.revokeAllForUser(resetToken.userId)

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