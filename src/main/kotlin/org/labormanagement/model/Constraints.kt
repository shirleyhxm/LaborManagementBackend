package org.labormanagement.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ====== Budget Constraints ======

data class BudgetConstraints(
    val businessId: UUID,  // Multi-tenancy: Business these constraints belong to
    val weeklyBudget: Double,
    val monthlyBudget: Double,
    val hardBudgetLimit: Boolean,
    val budgetWarningThreshold: Double, // percentage (80, 90, 95)
    val updatedAt: Instant = Instant.now()
)

data class HourlyRateRule(
    val roleId: String? = null, // optional: role-specific rates (e.g., "Server")
    val baseRate: Double,
    val overtimeMultiplier: Double, // e.g., 1.5
    val weekendPremium: Double // additional rate for Sat-Sun
)

// ====== Hours Constraints ======

data class WorkingHoursRules(
    val businessId: UUID,  // Multi-tenancy: Business these rules belong to
    val maxHoursPerWeek: Double,
    val maxOvertimeHours: Double,
    val minRestBetweenShifts: Double, // in hours
    val maxConsecutiveDays: Int,
    val maxShiftLength: Double, // in hours
    val minShiftLength: Double, // in hours
    val updatedAt: Instant = Instant.now()
)

data class EmployeeContractedHours(
    val businessId: UUID,  // Multi-tenancy: Business these hours belong to
    val employeeId: UUID,
    val minHours: Double,
    val contractedHours: Double,
    val maxHours: Double,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null,
    val updatedAt: Instant = Instant.now()
)

// ====== Compliance Rules ======

data class ComplianceRules(
    val businessId: UUID,  // Multi-tenancy: Business these rules belong to
    val flsaOvertimeEnabled: Boolean,
    val mealBreakRequired: Boolean,
    val mealBreakMinShiftHours: Double, // e.g., 6 hours
    val mealBreakDuration: Int, // e.g., 30 minutes
    val minorLaborLawsEnabled: Boolean,
    val advanceNoticePeriod: Int, // in days
    val updatedAt: Instant = Instant.now()
)

enum class CustomComplianceRuleType {
    SPLIT_SHIFT,
    STATE_SPECIFIC,
    CUSTOM
}

data class CustomComplianceRule(
    val businessId: UUID,  // Multi-tenancy: Business this rule belongs to
    val name: String,
    val description: String,
    val isActive: Boolean,
    val ruleType: CustomComplianceRuleType,
    val configuration: Map<String, Any> // flexible JSON for rule parameters
)

// ====== Priority & Fairness Rules ======

enum class PriorityType {
    CONTRACTED_HOURS,
    AVAILABILITY,
    FORECAST,
    LABOR_COST,
    FAIR_DISTRIBUTION
}

data class SchedulingPriority(
    val businessId: UUID,  // Multi-tenancy: Business this priority belongs to
    val priorityOrder: Int, // 1-5
    val priorityType: PriorityType,
    val name: String,
    val description: String,
    val isEnabled: Boolean
)

data class FairnessSettings(
    val businessId: UUID,  // Multi-tenancy: Business these settings belong to
    val rotateWeekendShifts: Boolean,
    val balanceDesirableShifts: Boolean,
    val seniorityPreference: Boolean,
    val updatedAt: Instant = Instant.now()
)

// ====== Payroll Cost Rules ======
// Employer-side on-costs on top of wage pay (e.g. UK Employer National
// Insurance: a flat rate above a per-employee weekly earnings threshold).
// Simplified single threshold/rate model - no per-employee NI category
// letter (under-21/apprentice discounted rates). Values are user-editable
// since HMRC revises the threshold/rate periodically and businesses may
// need to correct them for validation purposes.

data class PayrollCostRules(
    val businessId: UUID,  // Multi-tenancy: Business these rules belong to
    val employerNiEnabled: Boolean,
    val employerNiWeeklyThreshold: Double, // Secondary Threshold, currency/week
    val employerNiRate: Double, // percentage applied above the threshold
    val updatedAt: Instant = Instant.now()
)
