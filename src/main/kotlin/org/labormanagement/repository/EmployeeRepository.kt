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
    private val gson: Gson = createGson(),
    private val shareRepository: EmployeeShareRepository = EmployeeShareRepository()
) {
    private val logger = LoggerFactory.getLogger(EmployeeRepository::class.java)

    /**
     * Create a new employee.
     * Checks for duplicates within the same business only.
     */
    fun create(employee: Employee): Employee? = transaction {
        // Check for duplicates among this business's own staff only - a
        // borrowed employee who happens to share a name must not block the
        // business from hiring someone.
        val businessEmployees = findOwnedByBusiness(employee.businessId)
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
            it[userId] = employee.userId
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
     * Find an employee this business may work with: their own staff, or
     * someone lent to them via EmployeeShares.
     *
     * This is the chokepoint the scheduler, timeoff and swap paths all resolve
     * employees through, so widening it here is what makes a shared employee
     * schedulable everywhere without touching those callers.
     *
     * Returns null when the employee neither belongs to nor is shared into
     * this business - the tenant boundary still holds, it is just drawn around
     * "owned or lent" rather than "owned".
     */
    fun findById(businessId: UUID, id: UUID): Employee? = transaction {
        val employee = Employees.selectAll().where { Employees.id eq id }
            .singleOrNull()
            ?.toEmployee()
            ?: return@transaction null

        val reachable = employee.businessId == businessId ||
            shareRepository.isSharedInto(id, businessId)

        if (reachable) employee else null
    }

    /**
     * Find an employee owned outright by this business, ignoring shares.
     *
     * Used where borrowing is not enough - deleting someone, or lending them
     * on - so a borrowing business cannot give away staff it does not own.
     */
    fun findOwnedById(businessId: UUID, id: UUID): Employee? = transaction {
        Employees.selectAll().where { (Employees.id eq id) and (Employees.businessId eq businessId) }
            .singleOrNull()
            ?.toEmployee()
    }

    /**
     * Find the employee record linked to a login account.
     * Intentionally not business-scoped, unlike every other finder here — its
     * purpose is to derive businessId for a user who doesn't know it yet.
     */
    fun findByUserId(userId: String): Employee? = transaction {
        Employees.selectAll().where { Employees.userId eq userId }
            .singleOrNull()
            ?.toEmployee()
    }

    /**
     * Link an employee record to a login account (called on invite acceptance).
     */
    fun setUserId(employeeId: UUID, userId: String) = transaction {
        Employees.update({ Employees.id eq employeeId }) {
            it[Employees.userId] = userId
        }
    }

    /**
     * Every employee this business can schedule: its own staff plus anyone
     * lent to it.
     */
    fun findAllByBusiness(businessId: UUID): List<Employee> = transaction {
        val owned = Employees.selectAll().where { Employees.businessId eq businessId }
            .map { it.toEmployee() }

        val sharedIds = shareRepository.findEmployeeIdsSharedInto(businessId)
        if (sharedIds.isEmpty()) return@transaction owned

        // A share row could in principle point at someone who has since moved
        // business, so filter rather than trust the join blindly.
        val ownedIds = owned.map { it.id }.toSet()
        val shared = Employees.selectAll()
            .where { Employees.id inList sharedIds.filterNot { it in ownedIds } }
            .map { it.toEmployee() }

        owned + shared
    }

    /**
     * Employees owned outright by this business, ignoring anyone lent in.
     * Used for the duplicate check on create, which should only compare
     * against the business's own staff.
     */
    fun findOwnedByBusiness(businessId: UUID): List<Employee> = transaction {
        Employees.selectAll().where { Employees.businessId eq businessId }
            .map { it.toEmployee() }
    }

    /**
     * Update an employee with business verification.
     * Returns null if employee doesn't belong to the specified business.
     */
    fun update(businessId: UUID, id: UUID, employee: Employee): Employee? = transaction {
        // Reachable means owned or lent in: an admin of a borrowing business
        // may edit a shared employee, and the edit lands on the single shared
        // record, so it applies in the home business too.
        val existing = findById(businessId, id) ?: return@transaction null

        // Update employee. businessId is taken from the existing row, never
        // the caller's - editing someone must not silently move them between
        // businesses, least of all a borrower reassigning ownership to itself.
        Employees.update({ Employees.id eq id }) {
            it[Employees.businessId] = existing.businessId
            it[Employees.userId] = employee.userId
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

        // Delete old availabilities and insert new ones. Keyed on the row
        // being updated rather than the incoming object, so the two halves
        // cannot disagree.
        Availabilities.deleteWhere { Availabilities.employeeId eq id }
        employee.availability.forEach { avail ->
            Availabilities.insert {
                it[Availabilities.id] = UUID.randomUUID()
                it[employeeId] = id
                it[availabilityType] = avail.availabilityType.name
                it[dayOfWeek] = avail.dayOfWeek?.name
                it[specificDate] = avail.specificDate
                it[dateRangeStart] = avail.dateRange?.startDate
                it[dateRangeEnd] = avail.dateRange?.endDate
                it[startTime] = avail.startTime
                it[endTime] = avail.endTime
            }
        }

        // Report the home business, not the caller's, so a borrower editing a
        // shared employee doesn't appear to have taken ownership of them.
        employee.copy(id = id, businessId = existing.businessId)
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

        // Any business borrowing this employee stops borrowing them. Also a
        // foreign key constraint, so the delete would fail without this.
        shareRepository.deleteByEmployee(id)

        // Delete employee
        Employees.deleteWhere { Employees.id eq id } > 0
    }

    /**
     * Find employees by ID, restricted to those this business may work with -
     * its own staff or anyone lent to it.
     */
    fun findByIds(businessId: UUID, ids: List<UUID>): List<Employee> = transaction {
        if (ids.isEmpty()) return@transaction emptyList()

        val sharedIds = shareRepository.findEmployeeIdsSharedInto(businessId).toSet()
        Employees.selectAll().where { Employees.id inList ids }
            .map { it.toEmployee() }
            .filter { it.businessId == businessId || it.id in sharedIds }
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
            userId = this[Employees.userId],
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
