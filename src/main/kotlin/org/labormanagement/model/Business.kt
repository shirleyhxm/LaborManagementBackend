package org.labormanagement.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * Represents a business/organization in the multi-tenant system.
 * Each business is isolated from others and has its own employees, schedules, and settings.
 */
data class Business(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val ownerId: String,  // User ID of the business owner
    val subdomain: String? = null,  // Optional: for subdomain routing (e.g., acme.yourapp.com)
    val plan: SubscriptionPlan = SubscriptionPlan.FREE,
    val settings: BusinessSettings = BusinessSettings(),
    val createdAt: Instant = Instant.now(),
    val status: BusinessStatus = BusinessStatus.ACTIVE,

    // Subscription & billing
    val subscriptionId: String? = null,
    val billingEmail: String? = null,
    val subscriptionExpiresAt: Instant? = null,

    // Limits based on plan
    val maxEmployees: Int = 10,
    val maxLocations: Int = 1
)

/**
 * Status of a business in the system
 */
enum class BusinessStatus {
    ACTIVE,      // Business is active and operational
    SUSPENDED,   // Business is temporarily suspended (e.g., payment issue)
    TRIAL,       // Business is in trial period
    CANCELLED    // Business has been cancelled
}

/**
 * Subscription plan tiers
 */
enum class SubscriptionPlan {
    FREE,          // Free tier with limited features
    STARTER,       // Basic paid tier
    PROFESSIONAL,  // Professional tier with advanced features
    ENTERPRISE     // Enterprise tier with full features
}

/**
 * Business-specific settings and preferences
 */
data class BusinessSettings(
    val timezone: String = "America/New_York",
    val currency: String = "USD",
    val weekStartsOn: DayOfWeek = DayOfWeek.SUNDAY,
    val dateFormat: String = "MM/dd/yyyy",
    // When the business is open on an ordinary day. Schedule generation covers
    // this window unless a caller sends explicit per-date hours, so it decides
    // the span of every shift that can be created.
    //
    // The defaults reproduce what the client used to hardcode, so businesses
    // that never set them keep generating exactly what they did before.
    val defaultOpenTime: LocalTime = LocalTime.of(9, 0),
    val defaultCloseTime: LocalTime = LocalTime.of(21, 0)
)

/**
 * Represents membership of a user in a business for team collaboration.
 * This allows multiple users to work within the same business.
 */
data class BusinessMembership(
    val id: UUID = UUID.randomUUID(),
    val businessId: UUID,
    val userId: String,
    val role: UserRole,  // Role within this specific business
    val invitedBy: String,
    val invitedAt: Instant = Instant.now(),
    val status: MembershipStatus = MembershipStatus.ACTIVE
)

/**
 * Status of a business membership
 */
enum class MembershipStatus {
    PENDING,    // Invitation sent but not accepted
    ACTIVE,     // Active membership
    SUSPENDED   // Temporarily suspended
}
