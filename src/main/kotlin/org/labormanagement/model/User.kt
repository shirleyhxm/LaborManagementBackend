package org.labormanagement.model

data class User(
    val id: String,
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val passwordHash: String,
    val role: UserRole
)

enum class UserRole {
    ADMIN,    // Full system access
    MANAGER,  // Schedule management access
    EMPLOYEE  // View-only access
}