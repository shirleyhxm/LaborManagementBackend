package org.labormanagement.dto

import org.labormanagement.model.Business
import org.labormanagement.model.BusinessSettings
import org.labormanagement.model.BusinessStatus
import org.labormanagement.model.SubscriptionPlan
import java.time.DayOfWeek
import java.time.Instant
import java.util.UUID

/**
 * Request to create a new business
 */
data class CreateBusinessRequest(
    val name: String,
    val subdomain: String? = null,
    val settings: BusinessSettingsDto? = null
)

/**
 * Request to update an existing business
 */
data class UpdateBusinessRequest(
    val name: String? = null,
    val subdomain: String? = null,
    val settings: BusinessSettingsDto? = null,
    val billingEmail: String? = null
)

/**
 * Full business response with all details
 */
data class BusinessResponse(
    val id: String,
    val name: String,
    val ownerId: String,
    val subdomain: String?,
    val plan: String,
    val status: String,
    val settings: BusinessSettingsDto,
    val maxEmployees: Int,
    val maxLocations: Int,
    val createdAt: String,
    val subscriptionExpiresAt: String?
)

/**
 * Minimal business summary for business selector/list
 */
data class BusinessSummary(
    val id: String,
    val name: String,
    val plan: String,
    val status: String
)

/**
 * Business settings DTO
 */
data class BusinessSettingsDto(
    val timezone: String = "America/New_York",
    val currency: String = "USD",
    val weekStartsOn: String = "SUNDAY",
    val dateFormat: String = "MM/dd/yyyy"
)

// Extension functions for conversion

/**
 * Convert Business model to full response DTO
 */
fun Business.toResponse(): BusinessResponse {
    return BusinessResponse(
        id = this.id.toString(),
        name = this.name,
        ownerId = this.ownerId,
        subdomain = this.subdomain,
        plan = this.plan.name,
        status = this.status.name,
        settings = this.settings.toDto(),
        maxEmployees = this.maxEmployees,
        maxLocations = this.maxLocations,
        createdAt = this.createdAt.toString(),
        subscriptionExpiresAt = this.subscriptionExpiresAt?.toString()
    )
}

/**
 * Convert Business model to summary DTO
 */
fun Business.toSummary(): BusinessSummary {
    return BusinessSummary(
        id = this.id.toString(),
        name = this.name,
        plan = this.plan.name,
        status = this.status.name
    )
}

/**
 * Convert BusinessSettings model to DTO
 */
fun BusinessSettings.toDto(): BusinessSettingsDto {
    return BusinessSettingsDto(
        timezone = this.timezone,
        currency = this.currency,
        weekStartsOn = this.weekStartsOn.name,
        dateFormat = this.dateFormat
    )
}

/**
 * Convert BusinessSettingsDto to model
 */
fun BusinessSettingsDto.toModel(): BusinessSettings {
    return BusinessSettings(
        timezone = this.timezone,
        currency = this.currency,
        weekStartsOn = DayOfWeek.valueOf(this.weekStartsOn.uppercase()),
        dateFormat = this.dateFormat
    )
}

/**
 * Convert CreateBusinessRequest to Business model
 * The ownerId must be provided from the authenticated user context
 */
fun CreateBusinessRequest.toModel(ownerId: String): Business {
    // Set default plan limits based on FREE tier
    val plan = SubscriptionPlan.FREE
    val maxEmployees = when (plan) {
        SubscriptionPlan.FREE -> 10
        SubscriptionPlan.STARTER -> 50
        SubscriptionPlan.PROFESSIONAL -> 200
        SubscriptionPlan.ENTERPRISE -> Int.MAX_VALUE
    }
    val maxLocations = when (plan) {
        SubscriptionPlan.FREE -> 1
        SubscriptionPlan.STARTER -> 3
        SubscriptionPlan.PROFESSIONAL -> 10
        SubscriptionPlan.ENTERPRISE -> Int.MAX_VALUE
    }

    return Business(
        name = this.name,
        ownerId = ownerId,
        subdomain = this.subdomain,
        plan = plan,
        settings = this.settings?.toModel() ?: BusinessSettings(),
        status = BusinessStatus.ACTIVE,
        maxEmployees = maxEmployees,
        maxLocations = maxLocations
    )
}
