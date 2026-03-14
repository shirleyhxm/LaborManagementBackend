package org.labormanagement.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.config.GsonConfig.createGson
import org.labormanagement.database.Availabilities
import org.labormanagement.database.Employees
import org.labormanagement.model.*
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.util.UUID

/**
 * PostgreSQL-backed employee repository using Exposed ORM.
 * Handles employees with multi-tenancy support and complex nested data (Contract, Availability).
 */
class EmployeeRepository(
    private val gson: Gson = createGson()
) {
    private val logger = LoggerFactory.getLogger(EmployeeRepository::class.java)

    /**
     * Create a new employee.
     * Checks for duplicates within the same business only.
     */
    fun create(employee: Employee): Employee? = transaction {
        // Check for duplicates within the same business only
        val businessEmployees = findAllByBusiness(employee.businessId)
        val duplicate = businessEmployees.any {
            it.firstName == employee.firstName &&
            it.lastName == employee.lastName &&
            it.dateOfBirth.isEqual(employee.dateOfBirth)
        }

        if (duplicate) {
            return@transaction null
        }

        // Insert employee
        Employees.insert {
            it[id] = employee.id
            it[businessId] = employee.businessId
            it[firstName] = employee.firstName
            it[lastName] = employee.lastName
            it[middleName] = employee.middleName
            it[dateOfBirth] = employee.dateOfBirth
            it[normalPayRate] = employee.normalPayRate
            it[overtimePayRate] = employee.overtimePayRate
            it[productivity] = employee.productivity
            it[groups] = gson.toJson(employee.groups.toList())

            // Contract fields
            it[contractedHoursPerWeek] = employee.contract.contractedHoursPerWeek
            it[maxHoursPerWeek] = employee.contract.maxHoursPerWeek
            it[maxHoursPerDay] = employee.contract.maxHoursPerDay
            it[overtimeThreshold] = employee.contract.overtimeThreshold
            it[requiresBreak] = employee.contract.requiresBreak
            it[breakDurationMinutes] = employee.contract.breakDurationMinutes
            it[shiftLengthThresholdHours] = employee.contract.shiftLengthThresholdHours
            it[maxHoursPerMonth] = employee.contract.maxHoursPerMonth
            it[constraintPeriodDays] = employee.contract.constraintPeriodDays
            it[maxHoursPerPeriod] = employee.contract.maxHoursPerPeriod
        }

        // Insert availabilities
        employee.availability.forEach { avail ->
            Availabilities.insert {
                it[id] = UUID.randomUUID()
                it[employeeId] = employee.id
                it[availabilityType] = avail.availabilityType.name
                it[dayOfWeek] = avail.dayOfWeek?.name
                it[specificDate] = avail.specificDate
                it[dateRangeStart] = avail.dateRange?.startDate
                it[dateRangeEnd] = avail.dateRange?.endDate
                it[startTime] = avail.startTime
                it[endTime] = avail.endTime
            }
        }

        employee
    }

    /**
     * Find an employee by ID with business verification.
     * Returns null if employee doesn't belong to the specified business.
     */
    fun findById(businessId: UUID, id: UUID): Employee? = transaction {
        Employees.selectAll().where { (Employees.id eq id) and (Employees.businessId eq businessId) }
            .singleOrNull()
            ?.toEmployee()
    }

    /**
     * Find all employees for a specific business.
     */
    fun findAllByBusiness(businessId: UUID): List<Employee> = transaction {
        Employees.selectAll().where { Employees.businessId eq businessId }
            .map { it.toEmployee() }
    }

    /**
     * Update an employee with business verification.
     * Returns null if employee doesn't belong to the specified business.
     */
    fun update(businessId: UUID, id: UUID, employee: Employee): Employee? = transaction {
        // Security: Verify ownership
        val existing = Employees.selectAll().where { (Employees.id eq id) and (Employees.businessId eq businessId) }
            .singleOrNull() ?: return@transaction null

        // Update employee
        Employees.update({ Employees.id eq id }) {
            it[Employees.businessId] = employee.businessId
            it[firstName] = employee.firstName
            it[lastName] = employee.lastName
            it[middleName] = employee.middleName
            it[dateOfBirth] = employee.dateOfBirth
            it[normalPayRate] = employee.normalPayRate
            it[overtimePayRate] = employee.overtimePayRate
            it[productivity] = employee.productivity
            it[groups] = gson.toJson(employee.groups.toList())

            // Contract fields
            it[contractedHoursPerWeek] = employee.contract.contractedHoursPerWeek
            it[maxHoursPerWeek] = employee.contract.maxHoursPerWeek
            it[maxHoursPerDay] = employee.contract.maxHoursPerDay
            it[overtimeThreshold] = employee.contract.overtimeThreshold
            it[requiresBreak] = employee.contract.requiresBreak
            it[breakDurationMinutes] = employee.contract.breakDurationMinutes
            it[shiftLengthThresholdHours] = employee.contract.shiftLengthThresholdHours
            it[maxHoursPerMonth] = employee.contract.maxHoursPerMonth
            it[constraintPeriodDays] = employee.contract.constraintPeriodDays
            it[maxHoursPerPeriod] = employee.contract.maxHoursPerPeriod
        }

        // Delete old availabilities and insert new ones
        Availabilities.deleteWhere { Availabilities.employeeId eq id }
        employee.availability.forEach { avail ->
            Availabilities.insert {
                it[Availabilities.id] = UUID.randomUUID()
                it[employeeId] = employee.id
                it[availabilityType] = avail.availabilityType.name
                it[dayOfWeek] = avail.dayOfWeek?.name
                it[specificDate] = avail.specificDate
                it[dateRangeStart] = avail.dateRange?.startDate
                it[dateRangeEnd] = avail.dateRange?.endDate
                it[startTime] = avail.startTime
                it[endTime] = avail.endTime
            }
        }

        employee
    }

    /**
     * Delete an employee with business verification.
     * Returns false if employee doesn't belong to the specified business.
     */
    fun delete(businessId: UUID, id: UUID): Boolean = transaction {
        val existing = Employees.selectAll().where { (Employees.id eq id) and (Employees.businessId eq businessId) }
            .singleOrNull() ?: return@transaction false

        // Delete availabilities first (foreign key constraint)
        Availabilities.deleteWhere { Availabilities.employeeId eq id }

        // Delete employee
        Employees.deleteWhere { Employees.id eq id } > 0
    }

    /**
     * Find employees by IDs with business verification.
     * Only returns employees that belong to the specified business.
     */
    fun findByIds(businessId: UUID, ids: List<UUID>): List<Employee> = transaction {
        Employees.selectAll().where { (Employees.id inList ids) and (Employees.businessId eq businessId) }
            .map { it.toEmployee() }
    }

    /**
     * Find employees by group name within a specific business.
     */
    fun findByGroup(businessId: UUID, groupName: String): List<Employee> = transaction {
        val groupKey = groupName.lowercase()
        findAllByBusiness(businessId).filter { employee ->
            employee.groups.any { it.lowercase() == groupKey }
        }
    }

    /**
     * Find employees by multiple group names within a specific business.
     */
    fun findByGroups(businessId: UUID, groupNames: Set<String>): List<Employee> = transaction {
        val groupKeys = groupNames.map { it.lowercase() }.toSet()
        findAllByBusiness(businessId).filter { employee ->
            employee.groups.any { it.lowercase() in groupKeys }
        }
    }

    /**
     * Clear all data (for testing).
     */
    fun clear() = transaction {
        Availabilities.deleteAll()
        Employees.deleteAll()
    }

    /**
     * Extension function to convert ResultRow to Employee domain model
     */
    private fun ResultRow.toEmployee(): Employee {
        val employeeId = this[Employees.id]

        // Load availabilities for this employee
        val availabilities = Availabilities.selectAll().where { Availabilities.employeeId eq employeeId }
            .map { row ->
                Availability(
                    availabilityType = AvailabilityType.valueOf(row[Availabilities.availabilityType]),
                    dayOfWeek = row[Availabilities.dayOfWeek]?.let { DayOfWeek.valueOf(it) },
                    specificDate = row[Availabilities.specificDate],
                    dateRange = if (row[Availabilities.dateRangeStart] != null && row[Availabilities.dateRangeEnd] != null) {
                        DateRange(row[Availabilities.dateRangeStart]!!, row[Availabilities.dateRangeEnd]!!)
                    } else null,
                    startTime = row[Availabilities.startTime],
                    endTime = row[Availabilities.endTime]
                )
            }

        val setType = object : TypeToken<Set<String>>() {}.type
        val groups: Set<String> = gson.fromJson(this[Employees.groups], setType)

        return Employee(
            id = employeeId,
            businessId = this[Employees.businessId],
            firstName = this[Employees.firstName],
            lastName = this[Employees.lastName],
            middleName = this[Employees.middleName],
            dateOfBirth = this[Employees.dateOfBirth],
            normalPayRate = this[Employees.normalPayRate],
            overtimePayRate = this[Employees.overtimePayRate],
            productivity = this[Employees.productivity],
            contract = Contract(
                contractedHoursPerWeek = this[Employees.contractedHoursPerWeek],
                maxHoursPerWeek = this[Employees.maxHoursPerWeek],
                maxHoursPerDay = this[Employees.maxHoursPerDay],
                overtimeThreshold = this[Employees.overtimeThreshold],
                requiresBreak = this[Employees.requiresBreak],
                breakDurationMinutes = this[Employees.breakDurationMinutes],
                shiftLengthThresholdHours = this[Employees.shiftLengthThresholdHours],
                maxHoursPerMonth = this[Employees.maxHoursPerMonth],
                constraintPeriodDays = this[Employees.constraintPeriodDays],
                maxHoursPerPeriod = this[Employees.maxHoursPerPeriod]
            ),
            availability = availabilities,
            groups = groups
        )
    }
}
