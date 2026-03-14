package org.labormanagement.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.config.GsonConfig.createGson
import org.labormanagement.database.Users
import org.labormanagement.model.AccountType
import org.labormanagement.model.User
import org.labormanagement.model.UserRole
import org.labormanagement.service.PasswordValidator
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * PostgreSQL-backed user repository using Exposed ORM.
 * Replaces the in-memory UserRepository implementation.
 */
class UserRepository(
    private val passwordValidator: PasswordValidator = PasswordValidator(),
    private val gson: Gson = createGson()
) {
    private val logger = LoggerFactory.getLogger(UserRepository::class.java)

    init {
        // Create test users if database is empty
        transaction {
            if (Users.selectAll().empty()) {
                createTestUsers()
            }
        }
    }

    private fun createTestUsers() {
        logger.info("Creating test users...")

        val testUsers = listOf(
            User(
                id = "1",
                email = "admin@shiftoptimizer.com",
                firstName = "Admin",
                lastName = "User",
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
                passwordHash = BCrypt.hashpw("Employee123!", BCrypt.gensalt(12)),
                role = UserRole.EMPLOYEE,
                accountType = AccountType.BUSINESS_OWNER,
                ownedBusinessIds = emptyList(),
                memberBusinessIds = emptyList()
            )
        )

        testUsers.forEach { user ->
            Users.insert {
                it[id] = user.id
                it[email] = user.email
                it[firstName] = user.firstName
                it[lastName] = user.lastName
                it[passwordHash] = user.passwordHash
                it[role] = user.role.name
                it[twoFactorEnabled] = user.twoFactorEnabled
                it[twoFactorSecret] = user.twoFactorSecret
                it[accountType] = user.accountType.name
                it[ownedBusinessIds] = gson.toJson(user.ownedBusinessIds)
                it[memberBusinessIds] = gson.toJson(user.memberBusinessIds)
            }
        }

        logger.info("Test users created successfully")
    }

    /**
     * Find user by ID
     */
    fun findById(id: String): User? = transaction {
        Users.selectAll().where { Users.id eq id }
            .singleOrNull()
            ?.toUser()
    }

    /**
     * Find user by email
     */
    fun findByEmail(email: String): User? = transaction {
        Users.selectAll().where { Users.email eq email }
            .singleOrNull()
            ?.toUser()
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
    ): User = transaction {
        // Validate password strength (throws exception if invalid)
        passwordValidator.validatePasswordOrThrow(password)

        // Check if email already exists
        val existing = Users.selectAll().where { Users.email eq email }.singleOrNull()
        if (existing != null) {
            throw IllegalArgumentException("Email '$email' already exists")
        }

        // Generate new ID
        val id = (Users.selectAll().count() + 1).toString()
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(12))

        Users.insert {
            it[Users.id] = id
            it[Users.email] = email
            it[Users.firstName] = firstName
            it[Users.lastName] = lastName
            it[Users.passwordHash] = passwordHash
            it[Users.role] = role.name
            it[Users.twoFactorEnabled] = false
            it[Users.twoFactorSecret] = null
            it[Users.accountType] = accountType.name
            it[Users.ownedBusinessIds] = gson.toJson(emptyList<UUID>())
            it[Users.memberBusinessIds] = gson.toJson(emptyList<UUID>())
        }

        User(
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
    }

    /**
     * Update a user (for multi-tenancy updates like adding businesses)
     */
    fun update(userId: String, user: User): User? = transaction {
        val existing = findById(userId) ?: return@transaction null

        Users.update({ Users.id eq userId }) {
            it[email] = user.email
            it[firstName] = user.firstName
            it[lastName] = user.lastName
            it[passwordHash] = user.passwordHash
            it[role] = user.role.name
            it[twoFactorEnabled] = user.twoFactorEnabled
            it[twoFactorSecret] = user.twoFactorSecret
            it[accountType] = user.accountType.name
            it[ownedBusinessIds] = gson.toJson(user.ownedBusinessIds)
            it[memberBusinessIds] = gson.toJson(user.memberBusinessIds)
        }

        user
    }

    /**
     * Update user password
     * @throws PasswordValidationException if password doesn't meet strength requirements
     */
    fun updatePassword(userId: String, newPassword: String): Boolean = transaction {
        // Validate password strength (throws exception if invalid)
        passwordValidator.validatePasswordOrThrow(newPassword)

        val user = findById(userId) ?: return@transaction false
        val passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12))

        Users.update({ Users.id eq userId }) {
            it[Users.passwordHash] = passwordHash
        }

        true
    }

    /**
     * Set up 2FA for a user (generate secret, but don't enable yet)
     */
    fun setup2FA(userId: String, secret: String): Boolean = transaction {
        val user = findById(userId) ?: return@transaction false

        Users.update({ Users.id eq userId }) {
            it[twoFactorSecret] = secret
            it[twoFactorEnabled] = false
        }

        true
    }

    /**
     * Enable 2FA for a user (after verifying initial code)
     */
    fun enable2FA(userId: String): Boolean = transaction {
        val user = findById(userId) ?: return@transaction false
        if (user.twoFactorSecret == null) return@transaction false

        Users.update({ Users.id eq userId }) {
            it[twoFactorEnabled] = true
        }

        true
    }

    /**
     * Disable 2FA for a user
     */
    fun disable2FA(userId: String): Boolean = transaction {
        val user = findById(userId) ?: return@transaction false

        Users.update({ Users.id eq userId }) {
            it[twoFactorEnabled] = false
            it[twoFactorSecret] = null
        }

        true
    }

    /**
     * Get all users (for admin purposes)
     */
    fun getAllUsers(): List<User> = transaction {
        Users.selectAll().map { it.toUser() }
    }

    /**
     * Extension function to convert ResultRow to User domain model
     */
    private fun ResultRow.toUser(): User {
        val uuidListType = object : TypeToken<List<UUID>>() {}.type

        return User(
            id = this[Users.id],
            email = this[Users.email],
            firstName = this[Users.firstName],
            lastName = this[Users.lastName],
            passwordHash = this[Users.passwordHash],
            role = UserRole.valueOf(this[Users.role]),
            twoFactorEnabled = this[Users.twoFactorEnabled],
            twoFactorSecret = this[Users.twoFactorSecret],
            accountType = AccountType.valueOf(this[Users.accountType]),
            ownedBusinessIds = gson.fromJson(this[Users.ownedBusinessIds], uuidListType),
            memberBusinessIds = gson.fromJson(this[Users.memberBusinessIds], uuidListType)
        )
    }
}
