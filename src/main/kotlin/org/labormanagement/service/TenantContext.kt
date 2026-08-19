package org.labormanagement.service

import org.labormanagement.model.UserRole
import java.util.UUID

/**
 * Represents the current tenant (business) context for a request.
 * Contains the user ID, business ID, and user role within that business.
 * businessId may be null for public endpoints like /api/businesses
 */
data class TenantContext(
    val userId: String,
    val businessId: UUID?,
    val userRole: UserRole
)

/**
 * Thread-local storage for tenant context.
 * This allows each HTTP request to have its own isolated tenant context.
 */
object TenantContextHolder {
    private val context = ThreadLocal<TenantContext>()

    /**
     * Set the tenant context for the current thread (request).
     */
    fun setContext(tenantContext: TenantContext) {
        context.set(tenantContext)
    }

    /**
     * Get the tenant context for the current thread (request).
     * Returns null if no context has been set.
     */
    fun getContext(): TenantContext? {
        return context.get()
    }

    /**
     * Clear the tenant context for the current thread (request).
     * Should be called after request processing to prevent memory leaks.
     */
    fun clear() {
        context.remove()
    }

    /**
     * Get the current business ID from the context.
     * Throws UnauthorizedException if no context exists or businessId is not set.
     */
    fun requireBusinessId(): UUID {
        val context = getContext()
            ?: throw UnauthorizedException("No tenant context - authentication required")
        return context.businessId
            ?: throw BadRequestException("Business ID required - please provide X-Business-Id header")
    }

    /**
     * Get the current user ID from the context.
     * Throws UnauthorizedException if no context exists.
     */
    fun requireUserId(): String {
        return getContext()?.userId
            ?: throw UnauthorizedException("No tenant context - authentication required")
    }

    /**
     * Assert the current user's role is one of the allowed roles.
     * Throws UnauthorizedException if no context exists, or ForbiddenException
     * if the role isn't allowed.
     */
    fun requireRole(vararg allowed: UserRole) {
        val role = getContext()?.userRole
            ?: throw UnauthorizedException("No tenant context - authentication required")
        if (role !in allowed) {
            throw ForbiddenException("Requires one of: ${allowed.joinToString()}, but caller has $role")
        }
    }
}

/**
 * Exception thrown when a user is not authenticated
 */
class UnauthorizedException(message: String) : Exception(message)

/**
 * Exception thrown when a user is authenticated but doesn't have access to a resource
 */
class ForbiddenException(message: String) : Exception(message)

/**
 * Exception thrown for bad requests
 */
class BadRequestException(message: String) : Exception(message)

/**
 * Exception thrown when a resource is not found
 */
class NotFoundException(message: String) : Exception(message)

/**
 * Exception thrown when a resource already exists (conflict)
 */
class ConflictException(message: String) : Exception(message)
