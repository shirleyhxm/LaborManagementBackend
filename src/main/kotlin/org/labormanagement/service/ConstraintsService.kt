package org.labormanagement.service

import org.labormanagement.dto.*
import org.labormanagement.model.*
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConstraintsService {
    // Per-business storage for constraints (businessId -> constraint)
    private val budgets = ConcurrentHashMap<UUID, BudgetConstraints>()
    private val workingHoursMap = ConcurrentHashMap<UUID, WorkingHoursRules>()
    private val complianceMap = ConcurrentHashMap<UUID, ComplianceRules>()
    private val fairnessMap = ConcurrentHashMap<UUID, FairnessSettings>()
    private val prioritiesMap = ConcurrentHashMap<UUID, List<SchedulingPriority>>()

    // Per-business storage for collections (businessId -> Map<key, value>)
    private val hourlyRatesMap = ConcurrentHashMap<UUID, ConcurrentHashMap<String?, HourlyRateRule>>()
    private val contractedHoursMap = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, EmployeeContractedHours>>()
    private val customComplianceMap = ConcurrentHashMap<UUID, ConcurrentHashMap<String, CustomComplianceRule>>()

    // ====== Budget Constraints ======

    fun getBudgetConstraints(businessId: UUID): BudgetConstraints? = budgets[businessId]

    fun updateBudgetConstraints(businessId: UUID, request: BudgetConstraintsRequest): BudgetConstraints =
        request.toModel(businessId).also { budgets[businessId] = it }

    // ====== Hourly Rate Rules ======

    fun getHourlyRateRules(businessId: UUID, roleId: String?): List<HourlyRateRule> {
        val businessRates = hourlyRatesMap.computeIfAbsent(businessId) { ConcurrentHashMap() }
        return if (roleId != null) {
            listOfNotNull(businessRates[roleId])
        } else {
            businessRates.values.toList()
        }
    }

    fun createHourlyRateRule(businessId: UUID, request: HourlyRateRuleRequest): HourlyRateRule {
        val businessRates = hourlyRatesMap.computeIfAbsent(businessId) { ConcurrentHashMap() }
        return request.toModel().also { businessRates[it.roleId] = it }
    }

    fun deleteHourlyRateRule(businessId: UUID, roleId: String?): Boolean {
        val businessRates = hourlyRatesMap[businessId] ?: return false
        return businessRates.remove(roleId) != null
    }

    // ====== Working Hours Rules ======

    fun getWorkingHoursRules(businessId: UUID): WorkingHoursRules? = workingHoursMap[businessId]

    fun updateWorkingHoursRules(businessId: UUID, request: WorkingHoursRulesRequest): WorkingHoursRules =
        request.toModel(businessId).also { workingHoursMap[businessId] = it }

    // ====== Employee Contracted Hours ======

    fun getContractedHours(businessId: UUID, employeeId: UUID?): List<EmployeeContractedHours> {
        val businessHours = contractedHoursMap.computeIfAbsent(businessId) { ConcurrentHashMap() }
        return if (employeeId != null) {
            listOfNotNull(businessHours[employeeId])
        } else {
            businessHours.values.toList()
        }
    }

    fun createContractedHours(businessId: UUID, request: EmployeeContractedHoursRequest): EmployeeContractedHours {
        val businessHours = contractedHoursMap.computeIfAbsent(businessId) { ConcurrentHashMap() }
        return request.toModel(businessId).also { businessHours[it.employeeId] = it }
    }

    fun updateContractedHours(businessId: UUID, employeeId: UUID, request: EmployeeContractedHoursRequest): Boolean {
        val businessHours = contractedHoursMap.computeIfAbsent(businessId) { ConcurrentHashMap() }
        val hours = request.toModel(businessId)
        if (hours.employeeId != employeeId) return false
        businessHours[employeeId] = hours
        return true
    }

    fun deleteContractedHours(businessId: UUID, employeeId: UUID): Boolean {
        val businessHours = contractedHoursMap[businessId] ?: return false
        return businessHours.remove(employeeId) != null
    }

    fun getActiveContractedHours(businessId: UUID, employeeId: UUID, asOfDate: LocalDate = LocalDate.now()): EmployeeContractedHours? {
        val businessHours = contractedHoursMap[businessId] ?: return null
        val hours = businessHours[employeeId] ?: return null
        return if (!asOfDate.isBefore(hours.effectiveFrom) &&
            (hours.effectiveTo == null || !asOfDate.isAfter(hours.effectiveTo))) {
            hours
        } else {
            null
        }
    }

    // ====== Compliance Rules ======

    fun getComplianceRules(businessId: UUID): ComplianceRules? = complianceMap[businessId]

    fun updateComplianceRules(businessId: UUID, request: ComplianceRulesRequest): ComplianceRules =
        request.toModel(businessId).also { complianceMap[businessId] = it }

    // ====== Custom Compliance Rules ======

    fun getCustomComplianceRules(businessId: UUID): List<CustomComplianceRule> {
        val businessCompliance = customComplianceMap.computeIfAbsent(businessId) { ConcurrentHashMap() }
        return businessCompliance.values.toList()
    }

    fun createCustomComplianceRule(businessId: UUID, request: CustomComplianceRuleRequest): CustomComplianceRule {
        val businessCompliance = customComplianceMap.computeIfAbsent(businessId) { ConcurrentHashMap() }
        return request.toModel(businessId).also { businessCompliance[it.name] = it }
    }

    fun updateCustomComplianceRule(businessId: UUID, name: String, request: CustomComplianceRuleRequest): CustomComplianceRule? {
        val businessCompliance = customComplianceMap.computeIfAbsent(businessId) { ConcurrentHashMap() }
        return if (name == request.name) {
            request.toModel(businessId).also { businessCompliance[name] = it }
        } else null
    }

    fun deleteCustomComplianceRule(businessId: UUID, name: String): Boolean {
        val businessCompliance = customComplianceMap[businessId] ?: return false
        return businessCompliance.remove(name) != null
    }

    // ====== Scheduling Priorities ======

    fun getSchedulingPriorities(businessId: UUID): List<SchedulingPriority> {
        return prioritiesMap[businessId]?.sortedBy { it.priorityOrder } ?: emptyList()
    }

    fun reorderPriorities(businessId: UUID, request: PriorityReorderRequest): List<SchedulingPriority> {
        return request.priorities.map { it.toModel(businessId) }.also { prioritiesMap[businessId] = it }
    }

    // ====== Fairness Settings ======

    fun getFairnessSettings(businessId: UUID): FairnessSettings? = fairnessMap[businessId]

    fun updateFairnessSettings(businessId: UUID, request: FairnessSettingsRequest): FairnessSettings =
        request.toModel(businessId).also { fairnessMap[businessId] = it }

    // ====== Bulk Operations ======

    fun getAllConstraints(businessId: UUID) = AllConstraintsResponse(
        budget = getBudgetConstraints(businessId)?.toResponse(),
        hourlyRates = getHourlyRateRules(businessId, null).map { it.toResponse() },
        workingHours = getWorkingHoursRules(businessId)?.toResponse(),
        contractedHours = getContractedHours(businessId, null).map { it.toResponse() },
        compliance = getComplianceRules(businessId)?.toResponse(),
        customCompliance = getCustomComplianceRules(businessId).map { it.toResponse() },
        priorities = getSchedulingPriorities(businessId).map { it.toResponse() },
        fairness = getFairnessSettings(businessId)?.toResponse()
    )

    // ====== Validation ======

    fun validateConstraints(request: ConstraintValidationRequest): ConstraintValidationResponse {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()

        when (request.constraintType) {
            "budget" -> {
                val weeklyBudget = (request.constraints["weeklyBudget"] as? Number)?.toDouble()
                val monthlyBudget = (request.constraints["monthlyBudget"] as? Number)?.toDouble()
                val threshold = (request.constraints["budgetWarningThreshold"] as? Number)?.toDouble()

                if (weeklyBudget != null && weeklyBudget <= 0) {
                    errors.add(ValidationError("weeklyBudget", "Weekly budget must be positive"))
                }
                if (monthlyBudget != null && monthlyBudget <= 0) {
                    errors.add(ValidationError("monthlyBudget", "Monthly budget must be positive"))
                }
                if (threshold != null && (threshold < 0 || threshold > 100)) {
                    errors.add(ValidationError("budgetWarningThreshold", "Budget warning threshold must be between 0 and 100"))
                }
                if (weeklyBudget != null && monthlyBudget != null && weeklyBudget * 4 > monthlyBudget) {
                    warnings.add(ValidationWarning("weeklyBudget", "Weekly budget multiplied by 4 exceeds monthly budget"))
                }
            }
            "workingHours" -> {
                val minRestBetweenShifts = (request.constraints["minRestBetweenShifts"] as? Number)?.toDouble()
                val maxShiftLength = (request.constraints["maxShiftLength"] as? Number)?.toDouble()
                val minShiftLength = (request.constraints["minShiftLength"] as? Number)?.toDouble()

                if (minRestBetweenShifts != null && minRestBetweenShifts < 0) {
                    errors.add(ValidationError("minRestBetweenShifts", "Minimum rest between shifts must be >= 0"))
                }
                if (maxShiftLength != null && minShiftLength != null && maxShiftLength <= minShiftLength) {
                    errors.add(ValidationError("maxShiftLength", "Max shift length must be greater than min shift length"))
                }
            }
            "contractedHours" -> {
                val minHours = (request.constraints["minHours"] as? Number)?.toDouble()
                val contractedHours = (request.constraints["contractedHours"] as? Number)?.toDouble()
                val maxHours = (request.constraints["maxHours"] as? Number)?.toDouble()

                if (minHours != null && contractedHours != null && maxHours != null) {
                    if (!(minHours <= contractedHours && contractedHours <= maxHours)) {
                        errors.add(ValidationError("contractedHours", "Must satisfy: minHours <= contractedHours <= maxHours"))
                    }
                }
            }
        }

        return ConstraintValidationResponse(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
}
