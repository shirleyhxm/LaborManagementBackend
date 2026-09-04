package org.labormanagement.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.*
import java.time.LocalTime

/**
 * Database table definitions using Exposed ORM.
 * These tables map to the domain models in the org.labormanagement.model package.
 */

// ===== User Management Tables =====

object Users : Table("users") {
    val id = varchar("id", 50)
    val email = varchar("email", 255).uniqueIndex()
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)
    val passwordHash = varchar("password_hash", 255)
    val role = varchar("role", 50)
    val twoFactorEnabled = bool("two_factor_enabled").default(false)
    val twoFactorSecret = varchar("two_factor_secret", 255).nullable()
    val accountType = varchar("account_type", 50)
    val ownedBusinessIds = text("owned_business_ids").default("[]") // JSON array
    val memberBusinessIds = text("member_business_ids").default("[]") // JSON array

    override val primaryKey = PrimaryKey(id)
}

object PasswordResets : Table("password_resets") {
    val token = varchar("token", 255)
    val userId = varchar("user_id", 50)
    val email = varchar("email", 255)
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val used = bool("used").default(false)

    override val primaryKey = PrimaryKey(token)
}

object RefreshTokens : Table("refresh_tokens") {
    val token = varchar("token", 255)
    val userId = varchar("user_id", 50)
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val revoked = bool("revoked").default(false)

    override val primaryKey = PrimaryKey(token)
}

// ===== Business & Multi-Tenancy Tables =====

object Businesses : Table("businesses") {
    val id = uuid("id")
    val name = varchar("name", 255)
    val ownerId = varchar("owner_id", 50)
    val subdomain = varchar("subdomain", 100).nullable().uniqueIndex()
    val plan = varchar("plan", 50)
    val status = varchar("status", 50)
    val createdAt = timestamp("created_at")
    val subscriptionId = varchar("subscription_id", 255).nullable()
    val billingEmail = varchar("billing_email", 255).nullable()
    val subscriptionExpiresAt = timestamp("subscription_expires_at").nullable()
    val maxEmployees = integer("max_employees").default(10)
    val maxLocations = integer("max_locations").default(1)

    // Business settings (stored as JSON or separate columns)
    val timezone = varchar("timezone", 100).default("America/New_York")
    val currency = varchar("currency", 10).default("USD")
    val weekStartsOn = varchar("week_starts_on", 20).default("SUNDAY")
    val dateFormat = varchar("date_format", 50).default("MM/dd/yyyy")
    // Defaulted to the hours the client used to hardcode, so existing rows keep
    // generating the schedules they did before this became configurable.
    val defaultOpenTime = time("default_open_time").default(LocalTime.of(9, 0))
    val defaultCloseTime = time("default_close_time").default(LocalTime.of(21, 0))

    override val primaryKey = PrimaryKey(id)
}

/**
 * Grants a user a role within one specific business.
 *
 * Only ever holds MANAGER grants. ADMIN is deliberately not stored here: an
 * admin is the account owner, and their authority over a business derives from
 * Businesses.ownerId. Writing admin rows here would mean re-deriving that
 * invariant on every business creation, and it would silently break the first
 * time someone forgot.
 *
 * A user may hold rows for several businesses (one manager covering two sites),
 * so the uniqueness is on the pair, not on userId alone.
 */
object BusinessMemberships : Table("business_memberships") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val userId = varchar("user_id", 50).references(Users.id)
    val role = varchar("role", 50)
    val status = varchar("status", 50)
    val invitedBy = varchar("invited_by", 50)
    val invitedAt = timestamp("invited_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(businessId, userId)
    }
}

// ===== Employee Tables =====

object Employees : Table("employees") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val userId = varchar("user_id", 50).nullable() // links to Users.id once an invite is accepted
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)
    val middleName = varchar("middle_name", 100).default("")
    val dateOfBirth = date("date_of_birth")
    val normalPayRate = double("normal_pay_rate")
    val overtimePayRate = double("overtime_pay_rate")
    val productivity = double("productivity")
    val groups = text("groups").default("[]") // JSON array of group names
    // Whether this person is scheduled for shifts. False for the record that
    // backs a manager's login, which exists to hold the account rather than to
    // represent someone who works shifts. Defaults true so every existing row
    // stays on the roster.
    val schedulable = bool("schedulable").default(true)

    // Contract fields
    val contractedHoursPerWeek = double("contracted_hours_per_week")
    val maxHoursPerWeek = double("max_hours_per_week")
    val maxHoursPerDay = double("max_hours_per_day")
    val overtimeThreshold = double("overtime_threshold")
    val requiresBreak = bool("requires_break").default(true)
    val breakDurationMinutes = integer("break_duration_minutes").default(30)
    val shiftLengthThresholdHours = integer("shift_length_threshold_hours").default(4)
    val maxHoursPerMonth = double("max_hours_per_month").nullable()
    val constraintPeriodDays = integer("constraint_period_days").nullable()
    val maxHoursPerPeriod = double("max_hours_per_period").nullable()

    override val primaryKey = PrimaryKey(id)
}

object Availabilities : Table("availabilities") {
    val id = uuid("id")
    val employeeId = uuid("employee_id").references(Employees.id)
    val availabilityType = varchar("availability_type", 50)
    val dayOfWeek = varchar("day_of_week", 20).nullable()
    val specificDate = date("specific_date").nullable()
    val dateRangeStart = date("date_range_start").nullable()
    val dateRangeEnd = date("date_range_end").nullable()
    val startTime = time("start_time")
    val endTime = time("end_time")

    override val primaryKey = PrimaryKey(id)
}

object EmployeeInvites : Table("employee_invites") {
    val id = uuid("id")
    val employeeId = uuid("employee_id").references(Employees.id)
    val businessId = uuid("business_id").references(Businesses.id)
    val email = varchar("email", 255)
    val token = varchar("token", 255).uniqueIndex()
    val role = varchar("role", 20).default("EMPLOYEE") // what the accepted account becomes
    val status = varchar("status", 20).default("PENDING") // PENDING | ACCEPTED | REVOKED
    val invitedBy = varchar("invited_by", 50)
    val invitedAt = timestamp("invited_at")
    val expiresAt = timestamp("expires_at").nullable() // null = no expiry; only unset on rows created before this column existed
    val acceptedAt = timestamp("accepted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Assigns one employee to an additional location.
 *
 * The employee row itself is untouched: Employees.businessId stays their home
 * location, and this table only widens who can see and schedule them. That
 * keeps someone working at two locations a single record with one availability
 * calendar and one set of hours, so combined hours and double-booking stay
 * answerable.
 *
 * Both locations must have the same owner - this moves staff between locations
 * of one account, never across account boundaries.
 *
 * The physical table is still employee_shares, from when this was modelled as
 * "sharing". This repo has no migration tool, so renaming it would strand the
 * existing rows in a table nothing reads. Column names likewise.
 */
object EmployeeLocations : Table("employee_shares") {
    val id = uuid("id")
    val employeeId = uuid("employee_id").references(Employees.id)
    val businessId = uuid("business_id").references(Businesses.id)
    val assignedBy = varchar("shared_by", 50)
    val assignedAt = timestamp("shared_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(employeeId, businessId)
    }
}

/**
 * Contract documents belonging to an employee.
 *
 * The file itself lives in [content] rather than on disk or in object storage.
 * Contracts are small and low-volume, and keeping the bytes in the same
 * transaction as the metadata means an upload cannot half-succeed and leave a
 * row pointing at a file that is not there - which for a signed employment
 * contract is the failure mode worth spending storage to avoid.
 *
 * businessId is the employee's *home* location, matching who is allowed to
 * manage the documents. A location that has merely borrowed the employee never
 * owns their contracts.
 *
 * Queries that only need the listing must select columns explicitly and leave
 * [content] out: the runtime heap is 384MB, and `selectAll()` here would pull
 * every stored file into memory to render a list of file names.
 */
object EmployeeContracts : Table("employee_contracts") {
    val id = uuid("id")
    val employeeId = uuid("employee_id").references(Employees.id)
    val businessId = uuid("business_id").references(Businesses.id)
    val fileName = varchar("file_name", 255)
    val contentType = varchar("content_type", 100)
    val sizeBytes = long("size_bytes")
    // No length argument: that is what maps to Postgres bytea rather than a
    // fixed-width bytea(n).
    val content = binary("content")
    val uploadedBy = varchar("uploaded_by", 50)
    val uploadedAt = timestamp("uploaded_at")

    override val primaryKey = PrimaryKey(id)

    init {
        // Every read is "this employee's contracts, newest first".
        index(false, employeeId)
    }
}

object EmployeeGroups : Table("employee_groups") {
    val businessId = uuid("business_id").references(Businesses.id)
    val name = varchar("name", 100)

    override val primaryKey = PrimaryKey(businessId, name)
}

// ===== Schedule Tables =====

object Schedules : Table("schedules") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val name = varchar("name", 255)
    val status = varchar("status", 50)
    // Regular roster vs one-off special event. Defaulted so existing rows - all of
    // which are regular schedules - migrate without a backfill.
    val kind = varchar("kind", 20).default("REGULAR")
    val startDate = date("start_date")
    val endDate = date("end_date")

    // Generation input parameters
    val employeeIds = text("employee_ids") // JSON array
    val laborCostBudget = double("labor_cost_budget")
    val optimizationObjective = varchar("optimization_objective", 50)

    // Metrics
    val totalLaborCost = double("total_labor_cost")
    val estimatedTotalSales = double("estimated_total_sales")
    val laborCostPercentage = double("labor_cost_percentage")
    val employeeUtilization = text("employee_utilization") // JSON object
    val totalEmployerOnCost = double("total_employer_on_cost").default(0.0)

    // Violations and staffing (stored as JSON)
    val violations = text("violations").default("[]")
    val staffingRequirements = text("staffing_requirements").default("[]")

    // Lifecycle metadata
    val version = integer("version").default(1)
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 100)
    val publishedAt = timestamp("published_at").nullable()
    val publishedBy = varchar("published_by", 100).nullable()
    val lastModifiedAt = timestamp("last_modified_at")
    val lastModifiedBy = varchar("last_modified_by", 100)
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}

object Shifts : Table("shifts") {
    val id = uuid("id")
    val scheduleId = uuid("schedule_id").references(Schedules.id)
    val employeeId = uuid("employee_id").references(Employees.id)
    val date = date("date")
    val startTime = time("start_time")
    val endTime = time("end_time")
    val payRate = double("pay_rate")
    val isOvertime = bool("is_overtime").default(false)

    override val primaryKey = PrimaryKey(id)
}

/**
 * The shift rows of a draft schedule as they stood immediately before an edit, kept
 * so the edit can be undone.
 *
 * Only the shifts are stored. Metrics, violations and staffing requirements are all
 * derived from the shift list — ShiftModificationService recomputes them on every
 * edit — so snapshotting them would store nothing new and give the restore a second,
 * possibly stale, source of truth to disagree with.
 *
 * Deliberately *not* a full version history: rows are trimmed to the newest
 * [org.labormanagement.service.ScheduleUndoService.UNDO_DEPTH] per schedule on every
 * write, so the table is bounded by (schedules x depth) rather than growing with edit
 * count. That is the whole reason this exists as its own table instead of leaning on
 * Schedules.version, which would have to retain every intermediate state to be useful.
 */
object ScheduleUndoSnapshots : Table("schedule_undo_snapshots") {
    val id = uuid("id")
    val scheduleId = uuid("schedule_id").references(Schedules.id)
    // Monotonic per schedule; the newest snapshot is the one with the highest value.
    // An explicit sequence rather than ordering on createdAt, whose resolution can tie
    // when two edits land in the same instant.
    val sequence = long("sequence")
    val shifts = text("shifts") // JSON array of ShiftSnapshotDto
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 100)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, scheduleId, sequence)
    }
}

object SwapRequests : Table("swap_requests") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val requestingEmployeeId = uuid("requesting_employee_id").references(Employees.id)
    val targetShiftId = uuid("target_shift_id").references(Shifts.id)
    val targetEmployeeId = uuid("target_employee_id").references(Employees.id)
    val offeredShiftId = uuid("offered_shift_id").references(Shifts.id).nullable() // reserved for future mutual-trade support
    val message = text("message").nullable()
    val status = varchar("status", 20).default("PENDING") // PENDING | PENDING_APPROVAL | APPROVED | DENIED | DECLINED | CANCELLED
    val requestedAt = timestamp("requested_at")
    val respondedAt = timestamp("responded_at").nullable()
    val respondedBy = varchar("responded_by", 50).nullable()
    val reviewedAt = timestamp("reviewed_at").nullable()
    val reviewedBy = varchar("reviewed_by", 50).nullable()

    override val primaryKey = PrimaryKey(id)
}

// ===== Sales Forecast Tables =====

object SalesForecasts : Table("sales_forecasts") {
    val id = varchar("id", 50)
    val businessId = uuid("business_id").references(Businesses.id)
    val dateSpecificForecast = text("date_specific_forecast").nullable() // JSON
    val weeklyPattern = text("weekly_pattern").nullable() // JSON
    val lastUpdatedAt = timestamp("last_updated_at")
    val lastUpdatedBy = varchar("last_updated_by", 100)

    override val primaryKey = PrimaryKey(id, businessId)
}

// ===== Timeoff Tables =====

object Timeoffs : Table("timeoffs") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val employeeId = uuid("employee_id").references(Employees.id)
    val startDate = date("start_date")
    val endDate = date("end_date")
    val reason = text("reason")
    val status = varchar("status", 50)
    val requestedAt = timestamp("requested_at")
    val reviewedAt = timestamp("reviewed_at").nullable()
    val reviewedBy = varchar("reviewed_by", 100).nullable()
    val reviewNotes = text("review_notes").default("")
    val totalDays = integer("total_days")

    override val primaryKey = PrimaryKey(id)
}

// ===== Attendance Tables =====

object Attendances : Table("attendances") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val employeeId = uuid("employee_id").references(Employees.id)
    val scheduleId = uuid("schedule_id").references(Schedules.id).nullable()
    val shiftId = uuid("shift_id").references(Shifts.id).nullable()
    val clockInTime = timestamp("clock_in_time")
    val clockOutTime = timestamp("clock_out_time").nullable()
    val durationHours = double("duration_hours").nullable()
    val notes = text("notes").default("")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ===== Sales Tables =====

object Sales : Table("sales") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val employeeId = uuid("employee_id").references(Employees.id)
    val scheduleId = uuid("schedule_id").references(Schedules.id).nullable()
    val shiftId = uuid("shift_id").references(Shifts.id).nullable()
    val amount = double("amount")
    val category = varchar("category", 100).default("GENERAL")
    val description = text("description").default("")
    val recordedAt = timestamp("recorded_at")
    val createdBy = varchar("created_by", 100)

    override val primaryKey = PrimaryKey(id)
}

// ===== Constraints Tables =====
// One row per business for singleton settings; businessId is unique so
// upserts can key off it directly.

object BudgetConstraintsTable : Table("business_budget_constraints") {
    val businessId = uuid("business_id").references(Businesses.id).uniqueIndex()
    val weeklyBudget = double("weekly_budget")
    val monthlyBudget = double("monthly_budget")
    val hardBudgetLimit = bool("hard_budget_limit")
    val budgetWarningThreshold = double("budget_warning_threshold")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(businessId)
}

object WorkingHoursRulesTable : Table("business_working_hours_rules") {
    val businessId = uuid("business_id").references(Businesses.id).uniqueIndex()
    val maxHoursPerWeek = double("max_hours_per_week")
    val maxOvertimeHours = double("max_overtime_hours")
    val minRestBetweenShifts = double("min_rest_between_shifts")
    val maxConsecutiveDays = integer("max_consecutive_days")
    val maxShiftLength = double("max_shift_length")
    val minShiftLength = double("min_shift_length")
    val minWeeklyRestHours = double("min_weekly_rest_hours").default(24.0)
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(businessId)
}

object ComplianceRulesTable : Table("business_compliance_rules") {
    val businessId = uuid("business_id").references(Businesses.id).uniqueIndex()

    // Vestigial: FLSA overtime was a US-specific rule, removed from the domain
    // model, the API and the UI. Overtime itself still applies, driven by each
    // employee's contract.overtimeThreshold rather than a business-level
    // toggle. The column is retained (NOT NULL, so it's still written with a
    // fixed value) because this repo has no migration tooling to drop it
    // safely from existing databases. Nothing reads it - do not reintroduce a
    // dependency on it; revisit if/when the US market is in scope.
    val flsaOvertimeEnabled = bool("flsa_overtime_enabled")
    val mealBreakRequired = bool("meal_break_required")
    val mealBreakMinShiftHours = double("meal_break_min_shift_hours")
    val mealBreakDuration = integer("meal_break_duration")
    val minorLaborLawsEnabled = bool("minor_labor_laws_enabled")
    val advanceNoticePeriod = integer("advance_notice_period")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(businessId)
}

object FairnessSettingsTable : Table("business_fairness_settings") {
    val businessId = uuid("business_id").references(Businesses.id).uniqueIndex()
    val rotateWeekendShifts = bool("rotate_weekend_shifts")
    val balanceDesirableShifts = bool("balance_desirable_shifts")
    val seniorityPreference = bool("seniority_preference")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(businessId)
}

object PayrollCostRulesTable : Table("business_payroll_cost_rules") {
    val businessId = uuid("business_id").references(Businesses.id).uniqueIndex()
    val employerNiEnabled = bool("employer_ni_enabled")
    val employerNiWeeklyThreshold = double("employer_ni_weekly_threshold")
    val employerNiRate = double("employer_ni_rate")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(businessId)
}

// Many rows per business - each has its own generated id as primary key.

object HourlyRateRules : Table("business_hourly_rate_rules") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val roleId = varchar("role_id", 100).nullable()
    val baseRate = double("base_rate")
    val overtimeMultiplier = double("overtime_multiplier")
    val weekendPremium = double("weekend_premium")

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(businessId, roleId)
    }
}

object CustomComplianceRules : Table("business_custom_compliance_rules") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val name = varchar("name", 200)
    val description = text("description").default("")
    val isActive = bool("is_active").default(true)
    val ruleType = varchar("rule_type", 50)
    val configuration = text("configuration").default("{}") // JSON

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(businessId, name)
    }
}

object SchedulingPriorities : Table("business_scheduling_priorities") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val priorityOrder = integer("priority_order")
    val priorityType = varchar("priority_type", 50)
    val name = varchar("name", 200)
    val description = text("description").default("")
    val isEnabled = bool("is_enabled").default(true)

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(businessId, priorityOrder)
    }
}

// Employee contracted hours genuinely supports multiple effective-dated
// rows per employee (effectiveFrom/effectiveTo windows), so this is a
// real list keyed by a generated id, not one row per employee.

object EmployeeContractedHoursTable : Table("employee_contracted_hours") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val employeeId = uuid("employee_id").references(Employees.id)
    val minHours = double("min_hours")
    val contractedHours = double("contracted_hours")
    val maxHours = double("max_hours")
    val effectiveFrom = date("effective_from")
    val effectiveTo = date("effective_to").nullable()
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
