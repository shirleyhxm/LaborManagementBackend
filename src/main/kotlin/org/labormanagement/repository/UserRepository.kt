package org.labormanagement.repository

import org.labormanagement.model.User
import org.labormanagement.model.UserRole
import org.mindrot.jbcrypt.BCrypt

class UserRepository {
    // In-memory storage for users (in production, this would be a database)
    private val users = mutableMapOf<String, User>()

    init {
        // Create test users with hashed passwords
        createTestUsers()
    }

    private fun createTestUsers() {
        val testUsers = listOf(
            User(
                id = "1",
                username = "admin",
                email = "admin@shiftoptimizer.com",
                firstName = "Admin",
                lastName = "User",
                passwordHash = BCrypt.hashpw("admin123", BCrypt.gensalt(12)),
                role = UserRole.ADMIN
            ),
            User(
                id = "2",
                username = "manager",
                email = "manager@shiftoptimizer.com",
                firstName = "Manager",
                lastName = "Smith",
                passwordHash = BCrypt.hashpw("manager123", BCrypt.gensalt(12)),
                role = UserRole.MANAGER
            ),
            User(
                id = "3",
                username = "employee",
                email = "employee@shiftoptimizer.com",
                firstName = "John",
                lastName = "Doe",
                passwordHash = BCrypt.hashpw("employee123", BCrypt.gensalt(12)),
                role = UserRole.EMPLOYEE
            )
        )

        testUsers.forEach { user ->
            users[user.username] = user
        }
    }

    /**
     * Find user by username
     */
    fun findByUsername(username: String): User? {
        return users[username]
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
     */
    fun createUser(
        username: String,
        email: String,
        firstName: String,
        lastName: String,
        password: String,
        role: UserRole
    ): User {
        val id = (users.size + 1).toString()
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(12))

        val user = User(
            id = id,
            username = username,
            email = email,
            firstName = firstName,
            lastName = lastName,
            passwordHash = passwordHash,
            role = role
        )

        users[username] = user
        return user
    }

    /**
     * Get all users (for admin purposes)
     */
    fun getAllUsers(): List<User> {
        return users.values.toList()
    }
}