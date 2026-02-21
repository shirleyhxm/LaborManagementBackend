package org.labormanagement.model

/**
 * Request to set up 2FA for a user
 */
data class Setup2FARequest(
    val userId: String
)

/**
 * Response for 2FA setup
 */
data class Setup2FAResponse(
    val secret: String,
    val qrCodeUrl: String,
    val message: String
)

/**
 * Request to enable 2FA (requires verifying a code)
 */
data class Enable2FARequest(
    val userId: String,
    val verificationCode: String
)

/**
 * Response for enabling 2FA
 */
data class Enable2FAResponse(
    val enabled: Boolean,
    val message: String
)

/**
 * Request to disable 2FA
 */
data class Disable2FARequest(
    val userId: String,
    val password: String
)

/**
 * Response for disabling 2FA
 */
data class Disable2FAResponse(
    val disabled: Boolean,
    val message: String
)

/**
 * Request to verify 2FA code during login
 */
data class Verify2FARequest(
    val username: String,
    val code: String
)

/**
 * Login response when 2FA is required
 */
data class Login2FARequiredResponse(
    val requires2FA: Boolean,
    val message: String
)
