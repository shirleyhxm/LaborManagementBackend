package org.labormanagement.service

/**
 * Service for validating password strength requirements
 */
class PasswordValidator {

    companion object {
        const val MIN_LENGTH = 8
        const val REQUIRE_UPPERCASE = true
        const val REQUIRE_LOWERCASE = true
        const val REQUIRE_DIGIT = true
        const val REQUIRE_SPECIAL_CHAR = true

        private val SPECIAL_CHARS = setOf(
            '!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '-', '_',
            '=', '+', '[', ']', '{', '}', '|', '\\', ';', ':', '\'',
            '"', ',', '.', '<', '>', '/', '?', '~', '`'
        )
    }

    /**
     * Validate password strength
     * @return PasswordValidationResult with success status and error messages
     */
    fun validatePassword(password: String): PasswordValidationResult {
        val errors = mutableListOf<String>()

        // Check minimum length
        if (password.length < MIN_LENGTH) {
            errors.add("Password must be at least $MIN_LENGTH characters long")
        }

        // Check for uppercase letter
        if (REQUIRE_UPPERCASE && !password.any { it.isUpperCase() }) {
            errors.add("Password must contain at least one uppercase letter")
        }

        // Check for lowercase letter
        if (REQUIRE_LOWERCASE && !password.any { it.isLowerCase() }) {
            errors.add("Password must contain at least one lowercase letter")
        }

        // Check for digit
        if (REQUIRE_DIGIT && !password.any { it.isDigit() }) {
            errors.add("Password must contain at least one number")
        }

        // Check for special character
        if (REQUIRE_SPECIAL_CHAR && !password.any { it in SPECIAL_CHARS }) {
            errors.add("Password must contain at least one special character (e.g., !@#$%^&*)")
        }

        return PasswordValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }

    /**
     * Validate password and throw exception if invalid
     * @throws PasswordValidationException if password doesn't meet requirements
     */
    fun validatePasswordOrThrow(password: String) {
        val result = validatePassword(password)
        if (!result.isValid) {
            throw PasswordValidationException(result.errors)
        }
    }
}

/**
 * Result of password validation
 */
data class PasswordValidationResult(
    val isValid: Boolean,
    val errors: List<String>
)

/**
 * Exception thrown when password validation fails
 */
class PasswordValidationException(
    val errors: List<String>
) : Exception("Password validation failed: ${errors.joinToString(", ")}")
