package org.labormanagement.repository

import org.labormanagement.model.AccountType
import org.labormanagement.model.User
import org.labormanagement.model.UserRole
import org.labormanagement.service.PasswordValidator
import org.mindrot.jbcrypt.BCrypt

class UserRepository(
    private val passwordValidator: PasswordValidator = PasswordValidator()
) {
    // In-memory storage for users (in production, this would be a database)
    private val users = mutableMapOf<String, User>()

    init {
        // Create test users with hashed passwords
        createTestUsers()
    }

    private fun createTestUsers() {
        // Create test users with strong passwords that meet security requirements
        // NOTE: In production, these would be created through proper user registration
        val testUsers = listOf(
            User(
                id = "1",
                email = "admin@shiftoptimizer.com",
                firstName = "Admin",
                lastName = "User",
                // Password: Admin123! (meets all requirements)
                passwordHash = BCrypt.hashpw("Admin123!", BCrypt.gensalt(12)),
                role = UserRole.ADMIN,
                accountType = AccountType.BUSINESS_OWNER,
                ownedBusinessIds = emptyList(),
                memberBusinessIds = emptyList()
            ),
            User(
                id = "2",
                email = "manager@shiftoptimizer.com",
                firstName = "Manager",
                lastName = "Smith",
                // Password: Manager123! (meets all requirements)
                passwordHash = BCrypt.hashpw("Manager123!", BCrypt.gensalt(12)),
                role = UserRole.MANAGER,
                accountType = AccountType.BUSINESS_OWNER,
                ownedBusinessIds = emptyList(),
                memberBusinessIds = emptyList()
            ),
            User(
                id = "3",
                email = "employee@shiftoptimizer.com",
                firstName = "John",
                lastName = "Doe",
                // Password: Employee123! (meets all requirements)
                passwordHash = BCrypt.hashpw("Employee123!", BCrypt.gensalt(12)),
                role = UserRole.EMPLOYEE,
                accountType = AccountType.BUSINESS_OWNER,
                ownedBusinessIds = emptyList(),
                memberBusinessIds = emptyList()
            )
        )

        testUsers.forEach { user ->
            users[user.email] = user
        }
    }

    /**
     * Find user by ID
     */
    fun findById(id: String): User? {
        return users.values.find { it.id == id }
    }

    /**
     * Verify password against stored hash
     */
    fun verifyPassword(plainPassword: String, hashedPassword: String): Boolean {
        return BCrypt.checkpw(plainPassword, hashedPassword)
    }

    /**
     * Create a new user
     * @throws PasswordValidationException if password doesn't meet strength requirements
     */
    fun createUser(
        email: String,
        firstName: String,
        lastName: String,
        password: String,
        role: UserRole,
        accountType: AccountType = AccountType.BUSINESS_OWNER
    ): User {
        // Validate password strength (throws exception if invalid)
        passwordValidator.validatePasswordOrThrow(password)

        // Check if email already exists
        if (users.containsKey(email)) {
            throw IllegalArgumentException("Email '$email' already exists")
        }

        val id = (users.size + 1).toString()
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(12))

        val user = User(
            id = id,
            email = email,
            firstName = firstName,
            lastName = lastName,
            passwordHash = passwordHash,
            role = role,
            accountType = accountType,
            ownedBusinessIds = emptyList(),
            memberBusinessIds = emptyList()
        )

        users[email] = user
        return user
    }

    /**
     * Update a user (for multi-tenancy updates like adding businesses)
     */
    fun update(userId: String, user: User): User? {
        val existing = findById(userId) ?: return null
        users[existing.email] = user
        return user
    }

    /**
     * Update user password
     * @throws PasswordValidationException if password doesn't meet strength requirements
     */
    fun updatePassword(userId: String, newPassword: String): Boolean {
        // Validate password strength (throws exception if invalid)
        passwordValidator.validatePasswordOrThrow(newPassword)

        val user = findById(userId) ?: return false
        val passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12))

        val updatedUser = user.copy(passwordHash = passwordHash)
        users[user.email] = updatedUser
        return true
    }

    /**
     * Find user by email
     */
    fun findByEmail(email: String): User? {
        return users.values.find { it.email == email }
    }

    /**
     * Set up 2FA for a user (generate secret, but don't enable yet)
     */
    fun setup2FA(userId: String, secret: String): Boolean {
        val user = findById(userId) ?: return false
        val updatedUser = user.copy(twoFactorSecret = secret, twoFactorEnabled = false)
        users[user.email] = updatedUser
        return true
    }

    /**
     * Enable 2FA for a user (after verifying initial code)
     */
    fun enable2FA(userId: String): Boolean {
        val user = findById(userId) ?: return false
        if (user.twoFactorSecret == null) return false

        val updatedUser = user.copy(twoFactorEnabled = true)
        users[user.email] = updatedUser
        return true
    }

    /**
     * Disable 2FA for a user
     */
    fun disable2FA(userId: String): Boolean {
        val user = findById(userId) ?: return false
        val updatedUser = user.copy(twoFactorEnabled = false, twoFactorSecret = null)
        users[user.email] = updatedUser
        return true
    }

    /**
     * Get all users (for admin purposes)
     */
    fun getAllUsers(): List<User> {
        return users.values.toList()
    }
}