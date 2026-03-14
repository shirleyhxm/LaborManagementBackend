package org.labormanagement.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.*

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

    override val primaryKey = PrimaryKey(id)
}

// ===== Employee Tables =====

object Employees : Table("employees") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(Businesses.id)
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)
    val middleName = varchar("middle_name", 100).default("")
    val dateOfBirth = date("date_of_birth")
    val normalPayRate = double("normal_pay_rate")
    val overtimePayRate = double("overtime_pay_rate")
    val productivity = double("productivity")
    val groups = text("groups").default("[]") // JSON array of group names

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
