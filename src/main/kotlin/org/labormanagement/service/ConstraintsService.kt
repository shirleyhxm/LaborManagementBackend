package org.labormanagement.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.config.GsonConfig.createGson
import org.labormanagement.database.BudgetConstraintsTable
import org.labormanagement.database.ComplianceRulesTable
import org.labormanagement.database.CustomComplianceRules
import org.labormanagement.database.EmployeeContractedHoursTable
import org.labormanagement.database.FairnessSettingsTable
import org.labormanagement.database.HourlyRateRules
import org.labormanagement.database.PayrollCostRulesTable
import org.labormanagement.database.SchedulingPriorities
import org.labormanagement.database.WorkingHoursRulesTable
import org.labormanagement.dto.*
import org.labormanagement.model.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Postgres-backed constraints service using Exposed ORM. Each business's
 * settings survive backend restarts, matching every other repository in
 * this codebase (Attendance, Timeoff, etc).
 */
class ConstraintsService(private val gson: Gson = createGson()) {

    // ====== Budget Constraints ======

    fun getBudgetConstraints(businessId: UUID): BudgetConstraints? = transaction {
        BudgetConstraintsTable.selectAll().where { BudgetConstraintsTable.businessId eq businessId }
            .singleOrNull()?.toBudgetConstraints()
    }

    fun updateBudgetConstraints(businessId: UUID, request: BudgetConstraintsRequest): BudgetConstraints = transaction {
        val model = request.toModel(businessId)
        val exists = BudgetConstraintsTable.selectAll().where { BudgetConstraintsTable.businessId eq businessId }.any()
        if (exists) {
            BudgetConstraintsTable.update({ BudgetConstraintsTable.businessId eq businessId }) { it.fromModel(model) }
        } else {
            BudgetConstraintsTable.insert { it.fromModel(model) }
        }
        model
    }

    // ====== Hourly Rate Rules ======

    fun getHourlyRateRules(businessId: UUID, roleId: String?): List<HourlyRateRule> = transaction {
        val query = if (roleId != null) {
            HourlyRateRules.selectAll().where { (HourlyRateRules.businessId eq businessId) and (HourlyRateRules.roleId eq roleId) }
        } else {
            HourlyRateRules.selectAll().where { HourlyRateRules.businessId eq businessId }
        }
        query.map { it.toHourlyRateRule() }
    }

    fun createHourlyRateRule(businessId: UUID, request: HourlyRateRuleRequest): HourlyRateRule = transaction {
        val model = request.toModel()
        // Replace any existing rule for the same (businessId, roleId) - roleId acts as the natural key.
        HourlyRateRules.deleteWhere { (HourlyRateRules.businessId eq businessId) and (HourlyRateRules.roleId eq model.roleId) }
        HourlyRateRules.insert {
            it[id] = UUID.randomUUID()
            it[HourlyRateRules.businessId] = businessId
            it[roleId] = model.roleId
            it[baseRate] = model.baseRate
            it[overtimeMultiplier] = model.overtimeMultiplier
            it[weekendPremium] = model.weekendPremium
        }
        model
    }

    fun deleteHourlyRateRule(businessId: UUID, roleId: String?): Boolean = transaction {
        val deleted = HourlyRateRules.deleteWhere { (HourlyRateRules.businessId eq businessId) and (HourlyRateRules.roleId eq roleId) }
        deleted > 0
    }

    // ====== Working Hours Rules ======

    fun getWorkingHoursRules(businessId: UUID): WorkingHoursRules? = transaction {
        WorkingHoursRulesTable.selectAll().where { WorkingHoursRulesTable.businessId eq businessId }
            .singleOrNull()?.toWorkingHoursRules()
    }

    fun updateWorkingHoursRules(businessId: UUID, request: WorkingHoursRulesRequest): WorkingHoursRules = transaction {
        val model = request.toModel(businessId)
        val exists = WorkingHoursRulesTable.selectAll().where { WorkingHoursRulesTable.businessId eq businessId }.any()
        if (exists) {
            WorkingHoursRulesTable.update({ WorkingHoursRulesTable.businessId eq businessId }) { it.fromModel(model) }
        } else {
            WorkingHoursRulesTable.insert { it.fromModel(model) }
        }
        model
    }

    // ====== Employee Contracted Hours ======
    // Multiple effective-dated rows per employee are genuinely supported -
    // an employee can have a past rule (effectiveTo in the past) and a
    // current/future one, and both should be retrievable.

    fun getContractedHours(businessId: UUID, employeeId: UUID?): List<EmployeeContractedHours> = transaction {
        val query = if (employeeId != null) {
            EmployeeContractedHoursTable.selectAll().where {
                (EmployeeContractedHoursTable.businessId eq businessId) and (EmployeeContractedHoursTable.employeeId eq employeeId)
            }
        } else {
            EmployeeContractedHoursTable.selectAll().where { EmployeeContractedHoursTable.businessId eq businessId }
        }
        query.orderBy(EmployeeContractedHoursTable.effectiveFrom).map { it.toEmployeeContractedHours() }
    }

    fun createContractedHours(businessId: UUID, request: EmployeeContractedHoursRequest): EmployeeContractedHours = transaction {
        val model = request.toModel(businessId)
        EmployeeContractedHoursTable.insert {
            it[id] = UUID.randomUUID()
            it.fromModel(model)
        }
        model
    }

    // Updates the specific contracted-hours row identified by (employeeId, effectiveFrom)
    // in the request body, rather than "the" row for an employee - an employee can have
    // more than one effective-dated row.
    fun updateContractedHours(businessId: UUID, employeeId: UUID, request: EmployeeContractedHoursRequest): Boolean = transaction {
        if (UUID.fromString(request.employeeId) != employeeId) return@transaction false
        val effectiveFrom = LocalDate.parse(request.effectiveFrom)
        val updated = EmployeeContractedHoursTable.update({
            (EmployeeContractedHoursTable.businessId eq businessId) and
                (EmployeeContractedHoursTable.employeeId eq employeeId) and
                (EmployeeContractedHoursTable.effectiveFrom eq effectiveFrom)
        }) { it.fromModel(request.toModel(businessId)) }
        updated > 0
    }

    fun deleteContractedHours(businessId: UUID, employeeId: UUID, effectiveFrom: LocalDate? = null): Boolean = transaction {
        val deleted = if (effectiveFrom != null) {
            EmployeeContractedHoursTable.deleteWhere {
                (EmployeeContractedHoursTable.businessId eq businessId) and
                    (EmployeeContractedHoursTable.employeeId eq employeeId) and
                    (EmployeeContractedHoursTable.effectiveFrom eq effectiveFrom)
            }
        } else {
            EmployeeContractedHoursTable.deleteWhere {
                (EmployeeContractedHoursTable.businessId eq businessId) and (EmployeeContractedHoursTable.employeeId eq employeeId)
            }
        }
        deleted > 0
    }

    fun getActiveContractedHours(businessId: UUID, employeeId: UUID, asOfDate: LocalDate = LocalDate.now()): EmployeeContractedHours? {
        return getContractedHours(businessId, employeeId).firstOrNull { hours ->
            !asOfDate.isBefore(hours.effectiveFrom) && (hours.effectiveTo == null || !asOfDate.isAfter(hours.effectiveTo))
        }
    }

    // ====== Compliance Rules ======

    fun getComplianceRules(businessId: UUID): ComplianceRules? = transaction {
        ComplianceRulesTable.selectAll().where { ComplianceRulesTable.businessId eq businessId }
            .singleOrNull()?.toComplianceRules()
    }

    fun updateComplianceRules(businessId: UUID, request: ComplianceRulesRequest): ComplianceRules = transaction {
        val model = request.toModel(businessId)
        val exists = ComplianceRulesTable.selectAll().where { ComplianceRulesTable.businessId eq businessId }.any()
        if (exists) {
            ComplianceRulesTable.update({ ComplianceRulesTable.businessId eq businessId }) { it.fromModel(model) }
        } else {
            ComplianceRulesTable.insert { it.fromModel(model) }
        }
        model
    }

    // ====== Custom Compliance Rules ======

    fun getCustomComplianceRules(businessId: UUID): List<CustomComplianceRule> = transaction {
        CustomComplianceRules.selectAll().where { CustomComplianceRules.businessId eq businessId }
            .map { it.toCustomComplianceRule() }
    }

    fun createCustomComplianceRule(businessId: UUID, request: CustomComplianceRuleRequest): CustomComplianceRule = transaction {
        val model = request.toModel(businessId)
        CustomComplianceRules.deleteWhere { (CustomComplianceRules.businessId eq businessId) and (CustomComplianceRules.name eq model.name) }
        CustomComplianceRules.insert {
            it[id] = UUID.randomUUID()
            it.fromModel(model)
        }
        model
    }

    fun updateCustomComplianceRule(businessId: UUID, name: String, request: CustomComplianceRuleRequest): CustomComplianceRule? = transaction {
        if (name != request.name) return@transaction null
        val model = request.toModel(businessId)
        val updated = CustomComplianceRules.update({
            (CustomComplianceRules.businessId eq businessId) and (CustomComplianceRules.name eq name)
        }) { it.fromModel(model) }
        if (updated > 0) model else null
    }

    fun deleteCustomComplianceRule(businessId: UUID, name: String): Boolean = transaction {
        val deleted = CustomComplianceRules.deleteWhere { (CustomComplianceRules.businessId eq businessId) and (CustomComplianceRules.name eq name) }
        deleted > 0
    }

    // ====== Scheduling Priorities ======

    fun getSchedulingPriorities(businessId: UUID): List<SchedulingPriority> = transaction {
        SchedulingPriorities.selectAll().where { SchedulingPriorities.businessId eq businessId }
            .orderBy(SchedulingPriorities.priorityOrder)
            .map { it.toSchedulingPriority() }
    }

    fun reorderPriorities(businessId: UUID, request: PriorityReorderRequest): List<SchedulingPriority> = transaction {
        SchedulingPriorities.deleteWhere { SchedulingPriorities.businessId eq businessId }
        val models = request.priorities.map { it.toModel(businessId) }
        models.forEach { model ->
            SchedulingPriorities.insert {
                it[id] = UUID.randomUUID()
                it.fromModel(model)
            }
        }
        models
    }

    // ====== Fairness Settings ======

    fun getFairnessSettings(businessId: UUID): FairnessSettings? = transaction {
        FairnessSettingsTable.selectAll().where { FairnessSettingsTable.businessId eq businessId }
            .singleOrNull()?.toFairnessSettings()
    }

    fun updateFairnessSettings(businessId: UUID, request: FairnessSettingsRequest): FairnessSettings = transaction {
        val model = request.toModel(businessId)
        val exists = FairnessSettingsTable.selectAll().where { FairnessSettingsTable.businessId eq businessId }.any()
        if (exists) {
            FairnessSettingsTable.update({ FairnessSettingsTable.businessId eq businessId }) { it.fromModel(model) }
        } else {
            FairnessSettingsTable.insert { it.fromModel(model) }
        }
        model
    }

    // ====== Payroll Cost Rules ======

    fun getPayrollCostRules(businessId: UUID): PayrollCostRules? = transaction {
        PayrollCostRulesTable.selectAll().where { PayrollCostRulesTable.businessId eq businessId }
            .singleOrNull()?.toPayrollCostRules()
    }

    fun updatePayrollCostRules(businessId: UUID, request: PayrollCostRulesRequest): PayrollCostRules = transaction {
        val model = request.toModel(businessId)
        val exists = PayrollCostRulesTable.selectAll().where { PayrollCostRulesTable.businessId eq businessId }.any()
        if (exists) {
            PayrollCostRulesTable.update({ PayrollCostRulesTable.businessId eq businessId }) { it.fromModel(model) }
        } else {
            PayrollCostRulesTable.insert { it.fromModel(model) }
        }
        model
    }

    // ====== Bulk Operations ======

    fun getAllConstraints(businessId: UUID) = AllConstraintsResponse(
        budget = getBudgetConstraints(businessId)?.toResponse(),
        hourlyRates = getHourlyRateRules(businessId, null).map { it.toResponse() },
        workingHours = getWorkingHoursRules(businessId)?.toResponse(),
        contractedHours = getContractedHours(businessId, null).map { it.toResponse() },
        compliance = getComplianceRules(businessId)?.toResponse(),
        customCompliance = getCustomComplianceRules(businessId).map { it.toResponse() },
        priorities = getSchedulingPriorities(businessId).map { it.toResponse() },
        fairness = getFairnessSettings(businessId)?.toResponse(),
        payrollCost = getPayrollCostRules(businessId)?.toResponse()
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
            "payrollCost" -> {
                val threshold = (request.constraints["employerNiWeeklyThreshold"] as? Number)?.toDouble()
                val rate = (request.constraints["employerNiRate"] as? Number)?.toDouble()

                if (threshold != null && threshold < 0) {
                    errors.add(ValidationError("employerNiWeeklyThreshold", "Weekly threshold must be >= 0"))
                }
                if (rate != null && (rate < 0 || rate > 100)) {
                    errors.add(ValidationError("employerNiRate", "Rate must be between 0 and 100"))
                }
            }
        }

        return ConstraintValidationResponse(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    // ====== Row <-> model mapping ======

    private fun org.jetbrains.exposed.sql.ResultRow.toBudgetConstraints() = BudgetConstraints(
        businessId = this[BudgetConstraintsTable.businessId],
        weeklyBudget = this[BudgetConstraintsTable.weeklyBudget],
        monthlyBudget = this[BudgetConstraintsTable.monthlyBudget],
        hardBudgetLimit = this[BudgetConstraintsTable.hardBudgetLimit],
        budgetWarningThreshold = this[BudgetConstraintsTable.budgetWarningThreshold],
        updatedAt = this[BudgetConstraintsTable.updatedAt]
    )

    private fun org.jetbrains.exposed.sql.statements.UpdateBuilder<Int>.fromModel(model: BudgetConstraints) {
        this[BudgetConstraintsTable.businessId] = model.businessId
        this[BudgetConstraintsTable.weeklyBudget] = model.weeklyBudget
        this[BudgetConstraintsTable.monthlyBudget] = model.monthlyBudget
        this[BudgetConstraintsTable.hardBudgetLimit] = model.hardBudgetLimit
        this[BudgetConstraintsTable.budgetWarningThreshold] = model.budgetWarningThreshold
        this[BudgetConstraintsTable.updatedAt] = model.updatedAt
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toHourlyRateRule() = HourlyRateRule(
        roleId = this[HourlyRateRules.roleId],
        baseRate = this[HourlyRateRules.baseRate],
        overtimeMultiplier = this[HourlyRateRules.overtimeMultiplier],
        weekendPremium = this[HourlyRateRules.weekendPremium]
    )

    private fun org.jetbrains.exposed.sql.ResultRow.toWorkingHoursRules() = WorkingHoursRules(
        businessId = this[WorkingHoursRulesTable.businessId],
        maxHoursPerWeek = this[WorkingHoursRulesTable.maxHoursPerWeek],
        maxOvertimeHours = this[WorkingHoursRulesTable.maxOvertimeHours],
        minRestBetweenShifts = this[WorkingHoursRulesTable.minRestBetweenShifts],
        maxConsecutiveDays = this[WorkingHoursRulesTable.maxConsecutiveDays],
        maxShiftLength = this[WorkingHoursRulesTable.maxShiftLength],
        minShiftLength = this[WorkingHoursRulesTable.minShiftLength],
        updatedAt = this[WorkingHoursRulesTable.updatedAt]
    )

    private fun org.jetbrains.exposed.sql.statements.UpdateBuilder<Int>.fromModel(model: WorkingHoursRules) {
        this[WorkingHoursRulesTable.businessId] = model.businessId
        this[WorkingHoursRulesTable.maxHoursPerWeek] = model.maxHoursPerWeek
        this[WorkingHoursRulesTable.maxOvertimeHours] = model.maxOvertimeHours
        this[WorkingHoursRulesTable.minRestBetweenShifts] = model.minRestBetweenShifts
        this[WorkingHoursRulesTable.maxConsecutiveDays] = model.maxConsecutiveDays
        this[WorkingHoursRulesTable.maxShiftLength] = model.maxShiftLength
        this[WorkingHoursRulesTable.minShiftLength] = model.minShiftLength
        this[WorkingHoursRulesTable.updatedAt] = model.updatedAt
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toEmployeeContractedHours() = EmployeeContractedHours(
        businessId = this[EmployeeContractedHoursTable.businessId],
        employeeId = this[EmployeeContractedHoursTable.employeeId],
        minHours = this[EmployeeContractedHoursTable.minHours],
        contractedHours = this[EmployeeContractedHoursTable.contractedHours],
        maxHours = this[EmployeeContractedHoursTable.maxHours],
        effectiveFrom = this[EmployeeContractedHoursTable.effectiveFrom],
        effectiveTo = this[EmployeeContractedHoursTable.effectiveTo],
        updatedAt = this[EmployeeContractedHoursTable.updatedAt]
    )

    private fun org.jetbrains.exposed.sql.statements.UpdateBuilder<Int>.fromModel(model: EmployeeContractedHours) {
        this[EmployeeContractedHoursTable.businessId] = model.businessId
        this[EmployeeContractedHoursTable.employeeId] = model.employeeId
        this[EmployeeContractedHoursTable.minHours] = model.minHours
        this[EmployeeContractedHoursTable.contractedHours] = model.contractedHours
        this[EmployeeContractedHoursTable.maxHours] = model.maxHours
        this[EmployeeContractedHoursTable.effectiveFrom] = model.effectiveFrom
        this[EmployeeContractedHoursTable.effectiveTo] = model.effectiveTo
        this[EmployeeContractedHoursTable.updatedAt] = model.updatedAt
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toComplianceRules() = ComplianceRules(
        businessId = this[ComplianceRulesTable.businessId],
        flsaOvertimeEnabled = this[ComplianceRulesTable.flsaOvertimeEnabled],
        mealBreakRequired = this[ComplianceRulesTable.mealBreakRequired],
        mealBreakMinShiftHours = this[ComplianceRulesTable.mealBreakMinShiftHours],
        mealBreakDuration = this[ComplianceRulesTable.mealBreakDuration],
        minorLaborLawsEnabled = this[ComplianceRulesTable.minorLaborLawsEnabled],
        advanceNoticePeriod = this[ComplianceRulesTable.advanceNoticePeriod],
        updatedAt = this[ComplianceRulesTable.updatedAt]
    )

    private fun org.jetbrains.exposed.sql.statements.UpdateBuilder<Int>.fromModel(model: ComplianceRules) {
        this[ComplianceRulesTable.businessId] = model.businessId
        this[ComplianceRulesTable.flsaOvertimeEnabled] = model.flsaOvertimeEnabled
        this[ComplianceRulesTable.mealBreakRequired] = model.mealBreakRequired
        this[ComplianceRulesTable.mealBreakMinShiftHours] = model.mealBreakMinShiftHours
        this[ComplianceRulesTable.mealBreakDuration] = model.mealBreakDuration
        this[ComplianceRulesTable.minorLaborLawsEnabled] = model.minorLaborLawsEnabled
        this[ComplianceRulesTable.advanceNoticePeriod] = model.advanceNoticePeriod
        this[ComplianceRulesTable.updatedAt] = model.updatedAt
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toCustomComplianceRule(): CustomComplianceRule {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val configuration: Map<String, Any> = gson.fromJson(this[CustomComplianceRules.configuration], type) ?: emptyMap()
        return CustomComplianceRule(
            businessId = this[CustomComplianceRules.businessId],
            name = this[CustomComplianceRules.name],
            description = this[CustomComplianceRules.description],
            isActive = this[CustomComplianceRules.isActive],
            ruleType = CustomComplianceRuleType.valueOf(this[CustomComplianceRules.ruleType]),
            configuration = configuration
        )
    }

    private fun org.jetbrains.exposed.sql.statements.UpdateBuilder<Int>.fromModel(model: CustomComplianceRule) {
        this[CustomComplianceRules.businessId] = model.businessId
        this[CustomComplianceRules.name] = model.name
        this[CustomComplianceRules.description] = model.description
        this[CustomComplianceRules.isActive] = model.isActive
        this[CustomComplianceRules.ruleType] = model.ruleType.name
        this[CustomComplianceRules.configuration] = gson.toJson(model.configuration)
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toSchedulingPriority() = SchedulingPriority(
        businessId = this[SchedulingPriorities.businessId],
        priorityOrder = this[SchedulingPriorities.priorityOrder],
        priorityType = PriorityType.valueOf(this[SchedulingPriorities.priorityType]),
        name = this[SchedulingPriorities.name],
        description = this[SchedulingPriorities.description],
        isEnabled = this[SchedulingPriorities.isEnabled]
    )

    private fun org.jetbrains.exposed.sql.statements.UpdateBuilder<Int>.fromModel(model: SchedulingPriority) {
        this[SchedulingPriorities.businessId] = model.businessId
        this[SchedulingPriorities.priorityOrder] = model.priorityOrder
        this[SchedulingPriorities.priorityType] = model.priorityType.name
        this[SchedulingPriorities.name] = model.name
        this[SchedulingPriorities.description] = model.description
        this[SchedulingPriorities.isEnabled] = model.isEnabled
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toFairnessSettings() = FairnessSettings(
        businessId = this[FairnessSettingsTable.businessId],
        rotateWeekendShifts = this[FairnessSettingsTable.rotateWeekendShifts],
        balanceDesirableShifts = this[FairnessSettingsTable.balanceDesirableShifts],
        seniorityPreference = this[FairnessSettingsTable.seniorityPreference],
        updatedAt = this[FairnessSettingsTable.updatedAt]
    )

    private fun org.jetbrains.exposed.sql.statements.UpdateBuilder<Int>.fromModel(model: FairnessSettings) {
        this[FairnessSettingsTable.businessId] = model.businessId
        this[FairnessSettingsTable.rotateWeekendShifts] = model.rotateWeekendShifts
        this[FairnessSettingsTable.balanceDesirableShifts] = model.balanceDesirableShifts
        this[FairnessSettingsTable.seniorityPreference] = model.seniorityPreference
        this[FairnessSettingsTable.updatedAt] = model.updatedAt
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toPayrollCostRules() = PayrollCostRules(
        businessId = this[PayrollCostRulesTable.businessId],
        employerNiEnabled = this[PayrollCostRulesTable.employerNiEnabled],
        employerNiWeeklyThreshold = this[PayrollCostRulesTable.employerNiWeeklyThreshold],
        employerNiRate = this[PayrollCostRulesTable.employerNiRate],
        updatedAt = this[PayrollCostRulesTable.updatedAt]
    )

    private fun org.jetbrains.exposed.sql.statements.UpdateBuilder<Int>.fromModel(model: PayrollCostRules) {
        this[PayrollCostRulesTable.businessId] = model.businessId
        this[PayrollCostRulesTable.employerNiEnabled] = model.employerNiEnabled
        this[PayrollCostRulesTable.employerNiWeeklyThreshold] = model.employerNiWeeklyThreshold
        this[PayrollCostRulesTable.employerNiRate] = model.employerNiRate
        this[PayrollCostRulesTable.updatedAt] = model.updatedAt
    }
}
