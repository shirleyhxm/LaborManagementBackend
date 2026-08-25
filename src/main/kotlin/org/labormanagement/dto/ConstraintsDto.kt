package org.labormanagement.dto

import org.labormanagement.model.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ====== Budget Constraints DTOs ======

data class BudgetConstraintsRequest(
    val weeklyBudget: Double,
    val monthlyBudget: Double,
    val hardBudgetLimit: Boolean,
    val budgetWarningThreshold: Double
)

data class BudgetConstraintsResponse(
    val weeklyBudget: Double,
    val monthlyBudget: Double,
    val hardBudgetLimit: Boolean,
    val budgetWarningThreshold: Double,
    val updatedAt: String
)

// ====== Hourly Rate Rules DTOs ======

data class HourlyRateRuleRequest(
    val roleId: String? = null,
    val baseRate: Double,
    val overtimeMultiplier: Double,
    val weekendPremium: Double
)

data class HourlyRateRuleResponse(
    val roleId: String?,
    val baseRate: Double,
    val overtimeMultiplier: Double,
    val weekendPremium: Double
)

// ====== Working Hours Rules DTOs ======

data class WorkingHoursRulesRequest(
    val maxHoursPerWeek: Double,
    val maxOvertimeHours: Double,
    val minRestBetweenShifts: Double,
    val maxConsecutiveDays: Int,
    val maxShiftLength: Double,
    val minShiftLength: Double
)

data class WorkingHoursRulesResponse(
    val maxHoursPerWeek: Double,
    val maxOvertimeHours: Double,
    val minRestBetweenShifts: Double,
    val maxConsecutiveDays: Int,
    val maxShiftLength: Double,
    val minShiftLength: Double,
    val updatedAt: String
)

// ====== Employee Contracted Hours DTOs ======

data class EmployeeContractedHoursRequest(
    val employeeId: String,
    val minHours: Double,
    val contractedHours: Double,
    val maxHours: Double,
    val effectiveFrom: String, // ISO date format
    val effectiveTo: String? = null // ISO date format
)

data class EmployeeContractedHoursResponse(
    val employeeId: String,
    val minHours: Double,
    val contractedHours: Double,
    val maxHours: Double,
    val effectiveFrom: String,
    val effectiveTo: String?,
    val updatedAt: String
)

// ====== Compliance Rules DTOs ======

data class ComplianceRulesRequest(
    val flsaOvertimeEnabled: Boolean,
    val mealBreakRequired: Boolean,
    val mealBreakMinShiftHours: Double,
    val mealBreakDuration: Int,
    val minorLaborLawsEnabled: Boolean,
    val advanceNoticePeriod: Int
)

data class ComplianceRulesResponse(
    val flsaOvertimeEnabled: Boolean,
    val mealBreakRequired: Boolean,
    val mealBreakMinShiftHours: Double,
    val mealBreakDuration: Int,
    val minorLaborLawsEnabled: Boolean,
    val advanceNoticePeriod: Int,
    val updatedAt: String
)

// ====== Custom Compliance Rules DTOs ======

data class CustomComplianceRuleRequest(
    val name: String,
    val description: String,
    val isActive: Boolean,
    val ruleType: String, // "split_shift", "state_specific", "custom"
    val configuration: Map<String, Any>
)

data class CustomComplianceRuleResponse(
    val name: String,
    val description: String,
    val isActive: Boolean,
    val ruleType: String,
    val configuration: Map<String, Any>
)

// ====== Scheduling Priority DTOs ======

data class SchedulingPriorityResponse(
    val priorityOrder: Int,
    val priorityType: String,
    val name: String,
    val description: String,
    val isEnabled: Boolean
)

data class PriorityReorderRequest(
    val priorities: List<SchedulingPriorityRequest>
)

data class SchedulingPriorityRequest(
    val priorityOrder: Int,
    val priorityType: String,
    val name: String,
    val description: String,
    val isEnabled: Boolean
)

// ====== Fairness Settings DTOs ======

data class FairnessSettingsRequest(
    val rotateWeekendShifts: Boolean,
    val balanceDesirableShifts: Boolean,
    val seniorityPreference: Boolean
)

data class FairnessSettingsResponse(
    val rotateWeekendShifts: Boolean,
    val balanceDesirableShifts: Boolean,
    val seniorityPreference: Boolean,
    val updatedAt: String
)

// ====== Payroll Cost Rules DTOs ======

data class PayrollCostRulesRequest(
    val employerNiEnabled: Boolean,
    val employerNiWeeklyThreshold: Double,
    val employerNiRate: Double
)

data class PayrollCostRulesResponse(
    val employerNiEnabled: Boolean,
    val employerNiWeeklyThreshold: Double,
    val employerNiRate: Double,
    val updatedAt: String
)

// ====== Bulk Operations DTOs ======

data class AllConstraintsResponse(
    val budget: BudgetConstraintsResponse?,
    val hourlyRates: List<HourlyRateRuleResponse>,
    val workingHours: WorkingHoursRulesResponse?,
    val contractedHours: List<EmployeeContractedHoursResponse>,
    val compliance: ComplianceRulesResponse?,
    val customCompliance: List<CustomComplianceRuleResponse>,
    val priorities: List<SchedulingPriorityResponse>,
    val fairness: FairnessSettingsResponse?,
    val payrollCost: PayrollCostRulesResponse?
)

// ====== Validation DTOs ======

data class ConstraintValidationRequest(
    val constraintType: String,
    val constraints: Map<String, Any>
)

data class ValidationError(
    val field: String,
    val message: String
)

data class ValidationWarning(
    val field: String,
    val message: String
)

data class ConstraintValidationResponse(
    val isValid: Boolean,
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning>
)

// ====== Extension Functions for Conversions ======

fun BudgetConstraints.toResponse(): BudgetConstraintsResponse {
    return BudgetConstraintsResponse(
        weeklyBudget = this.weeklyBudget,
        monthlyBudget = this.monthlyBudget,
        hardBudgetLimit = this.hardBudgetLimit,
        budgetWarningThreshold = this.budgetWarningThreshold,
        updatedAt = this.updatedAt.toString()
    )
}

fun HourlyRateRule.toResponse(): HourlyRateRuleResponse {
    return HourlyRateRuleResponse(
        roleId = this.roleId,
        baseRate = this.baseRate,
        overtimeMultiplier = this.overtimeMultiplier,
        weekendPremium = this.weekendPremium
    )
}

fun WorkingHoursRules.toResponse(): WorkingHoursRulesResponse {
    return WorkingHoursRulesResponse(
        maxHoursPerWeek = this.maxHoursPerWeek,
        maxOvertimeHours = this.maxOvertimeHours,
        minRestBetweenShifts = this.minRestBetweenShifts,
        maxConsecutiveDays = this.maxConsecutiveDays,
        maxShiftLength = this.maxShiftLength,
        minShiftLength = this.minShiftLength,
        updatedAt = this.updatedAt.toString()
    )
}

fun EmployeeContractedHours.toResponse(): EmployeeContractedHoursResponse {
    return EmployeeContractedHoursResponse(
        employeeId = this.employeeId.toString(),
        minHours = this.minHours,
        contractedHours = this.contractedHours,
        maxHours = this.maxHours,
        effectiveFrom = this.effectiveFrom.toString(),
        effectiveTo = this.effectiveTo?.toString(),
        updatedAt = this.updatedAt.toString()
    )
}

fun ComplianceRules.toResponse(): ComplianceRulesResponse {
    return ComplianceRulesResponse(
        flsaOvertimeEnabled = this.flsaOvertimeEnabled,
        mealBreakRequired = this.mealBreakRequired,
        mealBreakMinShiftHours = this.mealBreakMinShiftHours,
        mealBreakDuration = this.mealBreakDuration,
        minorLaborLawsEnabled = this.minorLaborLawsEnabled,
        advanceNoticePeriod = this.advanceNoticePeriod,
        updatedAt = this.updatedAt.toString()
    )
}

fun CustomComplianceRule.toResponse(): CustomComplianceRuleResponse {
    return CustomComplianceRuleResponse(
        name = this.name,
        description = this.description,
        isActive = this.isActive,
        ruleType = this.ruleType.name.lowercase(),
        configuration = this.configuration
    )
}

fun SchedulingPriority.toResponse(): SchedulingPriorityResponse {
    return SchedulingPriorityResponse(
        priorityOrder = this.priorityOrder,
        priorityType = this.priorityType.name.lowercase(),
        name = this.name,
        description = this.description,
        isEnabled = this.isEnabled
    )
}

fun FairnessSettings.toResponse(): FairnessSettingsResponse {
    return FairnessSettingsResponse(
        rotateWeekendShifts = this.rotateWeekendShifts,
        balanceDesirableShifts = this.balanceDesirableShifts,
        seniorityPreference = this.seniorityPreference,
        updatedAt = this.updatedAt.toString()
    )
}

fun PayrollCostRules.toResponse(): PayrollCostRulesResponse {
    return PayrollCostRulesResponse(
        employerNiEnabled = this.employerNiEnabled,
        employerNiWeeklyThreshold = this.employerNiWeeklyThreshold,
        employerNiRate = this.employerNiRate,
        updatedAt = this.updatedAt.toString()
    )
}

// Conversion from request to model
fun BudgetConstraintsRequest.toModel(businessId: UUID): BudgetConstraints {
    return BudgetConstraints(
        businessId = businessId,
        weeklyBudget = this.weeklyBudget,
        monthlyBudget = this.monthlyBudget,
        hardBudgetLimit = this.hardBudgetLimit,
        budgetWarningThreshold = this.budgetWarningThreshold,
        updatedAt = Instant.now()
    )
}

fun HourlyRateRuleRequest.toModel(): HourlyRateRule {
    return HourlyRateRule(
        roleId = this.roleId,
        baseRate = this.baseRate,
        overtimeMultiplier = this.overtimeMultiplier,
        weekendPremium = this.weekendPremium
    )
}

fun WorkingHoursRulesRequest.toModel(businessId: UUID): WorkingHoursRules {
    return WorkingHoursRules(
        businessId = businessId,
        maxHoursPerWeek = this.maxHoursPerWeek,
        maxOvertimeHours = this.maxOvertimeHours,
        minRestBetweenShifts = this.minRestBetweenShifts,
        maxConsecutiveDays = this.maxConsecutiveDays,
        maxShiftLength = this.maxShiftLength,
        minShiftLength = this.minShiftLength,
        updatedAt = Instant.now()
    )
}

fun EmployeeContractedHoursRequest.toModel(businessId: UUID): EmployeeContractedHours {
    return EmployeeContractedHours(
        businessId = businessId,
        employeeId = UUID.fromString(this.employeeId),
        minHours = this.minHours,
        contractedHours = this.contractedHours,
        maxHours = this.maxHours,
        effectiveFrom = LocalDate.parse(this.effectiveFrom),
        effectiveTo = this.effectiveTo?.let { LocalDate.parse(it) },
        updatedAt = Instant.now()
    )
}

fun ComplianceRulesRequest.toModel(businessId: UUID): ComplianceRules {
    return ComplianceRules(
        businessId = businessId,
        flsaOvertimeEnabled = this.flsaOvertimeEnabled,
        mealBreakRequired = this.mealBreakRequired,
        mealBreakMinShiftHours = this.mealBreakMinShiftHours,
        mealBreakDuration = this.mealBreakDuration,
        minorLaborLawsEnabled = this.minorLaborLawsEnabled,
        advanceNoticePeriod = this.advanceNoticePeriod,
        updatedAt = Instant.now()
    )
}

fun CustomComplianceRuleRequest.toModel(businessId: UUID): CustomComplianceRule {
    return CustomComplianceRule(
        businessId = businessId,
        name = this.name,
        description = this.description,
        isActive = this.isActive,
        ruleType = CustomComplianceRuleType.valueOf(this.ruleType.uppercase()),
        configuration = this.configuration
    )
}

fun SchedulingPriorityRequest.toModel(businessId: UUID): SchedulingPriority {
    return SchedulingPriority(
        businessId = businessId,
        priorityOrder = this.priorityOrder,
        priorityType = PriorityType.valueOf(this.priorityType.uppercase()),
        name = this.name,
        description = this.description,
        isEnabled = this.isEnabled
    )
}

fun FairnessSettingsRequest.toModel(businessId: UUID): FairnessSettings {
    return FairnessSettings(
        businessId = businessId,
        rotateWeekendShifts = this.rotateWeekendShifts,
        balanceDesirableShifts = this.balanceDesirableShifts,
        seniorityPreference = this.seniorityPreference,
        updatedAt = Instant.now()
    )
}

fun PayrollCostRulesRequest.toModel(businessId: UUID): PayrollCostRules {
    return PayrollCostRules(
        businessId = businessId,
        employerNiEnabled = this.employerNiEnabled,
        employerNiWeeklyThreshold = this.employerNiWeeklyThreshold,
        employerNiRate = this.employerNiRate,
        updatedAt = Instant.now()
    )
}
