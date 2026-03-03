package org.labormanagement.model

import java.util.UUID

/**
 * Represents a user account in the system.
 * Users can own multiple businesses or be members of businesses owned by others.
 */
data class User(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val passwordHash: String,
    val role: UserRole,
    val twoFactorEnabled: Boolean = false,
    val twoFactorSecret: String? = null,

    // Multi-tenancy fields
    val accountType: AccountType = AccountType.BUSINESS_OWNER,
    val ownedBusinessIds: List<UUID> = emptyList(),  // Businesses this user owns
    val memberBusinessIds: List<UUID> = emptyList()   // Businesses this user is a member of
)

/**
 * Account type determines what actions a user can perform in the system
 */
enum class AccountType {
    BUSINESS_OWNER,  // Can create and own multiple businesses
    TEAM_MEMBER,     // Can be invited to businesses
    ADMIN            // Platform admin
}

/**
 * User role determines permissions within a specific business
 */
enum class UserRole {
    ADMIN,    // Full business access
    MANAGER,  // Schedule management access
    EMPLOYEE  // View-only access
}