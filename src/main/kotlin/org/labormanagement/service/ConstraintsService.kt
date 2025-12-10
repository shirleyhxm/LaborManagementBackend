package org.labormanagement.service

import org.labormanagement.dto.*
import org.labormanagement.model.*
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class ConstraintsService {
    // Simple in-memory storage for global configurations
    private val budget = AtomicReference<BudgetConstraints?>()
    private val workingHours = AtomicReference<WorkingHoursRules?>()
    private val compliance = AtomicReference<ComplianceRules?>()
    private val fairness = AtomicReference<FairnessSettings?>()

    private val hourlyRates = ConcurrentHashMap<String?, HourlyRateRule>()
    private val contractedHours = ConcurrentHashMap<UUID, EmployeeContractedHours>()
    private val customCompliance = ConcurrentHashMap<String, CustomComplianceRule>()
    private val priorities = AtomicReference<List<SchedulingPriority>>(emptyList())

    // ====== Budget Constraints ======

    fun getBudgetConstraints() = budget.get()

    fun updateBudgetConstraints(request: BudgetConstraintsRequest) =
        request.toModel().also { budget.set(it) }

    // ====== Hourly Rate Rules ======

    fun getHourlyRateRules(roleId: String?) =
        if (roleId != null) {
            listOfNotNull(hourlyRates[roleId])
        } else {
            hourlyRates.values.toList()
        }

    fun createHourlyRateRule(request: HourlyRateRuleRequest) =
        request.toModel().also { hourlyRates[it.roleId] = it }

    fun deleteHourlyRateRule(roleId: String?) = hourlyRates.remove(roleId) != null

    // ====== Working Hours Rules ======

    fun getWorkingHoursRules() = workingHours.get()

    fun updateWorkingHoursRules(request: WorkingHoursRulesRequest) =
        request.toModel().also { workingHours.set(it) }

    // ====== Employee Contracted Hours ======

    fun getContractedHours(employeeId: UUID?) =
        if (employeeId != null) {
            listOfNotNull(contractedHours[employeeId])
        } else {
            contractedHours.values.toList()
        }

    fun createContractedHours(request: EmployeeContractedHoursRequest) =
        request.toModel().also { contractedHours[it.employeeId] = it }

    fun updateContractedHours(employeeId: UUID, request: EmployeeContractedHoursRequest): Boolean {
        val hours = request.toModel()
        if (hours.employeeId != employeeId) return false
        contractedHours[employeeId] = hours
        return true
    }

    fun deleteContractedHours(employeeId: UUID) = contractedHours.remove(employeeId) != null

    fun getActiveContractedHours(employeeId: UUID, asOfDate: LocalDate = LocalDate.now()): EmployeeContractedHours? {
        val hours = contractedHours[employeeId] ?: return null
        return if (!asOfDate.isBefore(hours.effectiveFrom) &&
            (hours.effectiveTo == null || !asOfDate.isAfter(hours.effectiveTo))) {
            hours
        } else {
            null
        }
    }

    // ====== Compliance Rules ======

    fun getComplianceRules() = compliance.get()

    fun updateComplianceRules(request: ComplianceRulesRequest) =
        request.toModel().also { compliance.set(it) }

    // ====== Custom Compliance Rules ======

    fun getCustomComplianceRules() = customCompliance.values.toList()

    fun createCustomComplianceRule(request: CustomComplianceRuleRequest) =
        request.toModel().also { customCompliance[it.name] = it }

    fun updateCustomComplianceRule(name: String, request: CustomComplianceRuleRequest) =
        if (name == request.name) {
            request.toModel().also { customCompliance[name] = it }
        } else null

    fun deleteCustomComplianceRule(name: String) = customCompliance.remove(name) != null

    // ====== Scheduling Priorities ======

    fun getSchedulingPriorities() = priorities.get().sortedBy { it.priorityOrder }

    fun reorderPriorities(request: PriorityReorderRequest) =
        request.priorities.map { it.toModel() }.also { priorities.set(it) }

    // ====== Fairness Settings ======

    fun getFairnessSettings() = fairness.get()

    fun updateFairnessSettings(request: FairnessSettingsRequest) =
        request.toModel().also { fairness.set(it) }

    // ====== Bulk Operations ======

    fun getAllConstraints() = AllConstraintsResponse(
        budget = getBudgetConstraints()?.toResponse(),
        hourlyRates = getHourlyRateRules(null).map { it.toResponse() },
        workingHours = getWorkingHoursRules()?.toResponse(),
        contractedHours = contractedHours.values.map { it.toResponse() },
        compliance = getComplianceRules()?.toResponse(),
        customCompliance = getCustomComplianceRules().map { it.toResponse() },
        priorities = getSchedulingPriorities().map { it.toResponse() },
        fairness = getFairnessSettings()?.toResponse()
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
