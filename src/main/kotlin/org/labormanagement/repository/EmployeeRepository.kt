package org.labormanagement.repository

import org.labormanagement.model.Employee
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for managing Employee entities with multi-tenancy support.
 * All operations are scoped by businessId to ensure tenant isolation.
 */
class EmployeeRepository {
    private val employees = ConcurrentHashMap<UUID, Employee>()
    private val businessIndex = ConcurrentHashMap<UUID, MutableSet<UUID>>()  // businessId -> Set<employeeId>

    /**
     * Create a new employee.
     * Checks for duplicates within the same business only.
     */
    fun create(employee: Employee): Employee? {
        // Check for duplicates within the same business only
        val businessEmployees = findAllByBusiness(employee.businessId)
        val duplicate = businessEmployees.any {
            it.firstName == employee.firstName &&
            it.lastName == employee.lastName &&
            it.dateOfBirth.isEqual(employee.dateOfBirth)
        }

        return if (duplicate) {
            null
        } else {
            employees[employee.id] = employee
            businessIndex.computeIfAbsent(employee.businessId) { ConcurrentHashMap.newKeySet() }.add(employee.id)
            employee
        }
    }

    /**
     * Find an employee by ID with business verification.
     * Returns null if employee doesn't belong to the specified business.
     */
    fun findById(businessId: UUID, id: UUID): Employee? {
        val employee = employees[id]
        // Security: Verify employee belongs to the business
        return if (employee?.businessId == businessId) employee else null
    }

    /**
     * Find all employees for a specific business.
     */
    fun findAllByBusiness(businessId: UUID): List<Employee> {
        val employeeIds = businessIndex[businessId] ?: return emptyList()
        return employeeIds.mapNotNull { employees[it] }
    }

    /**
     * Update an employee with business verification.
     * Returns null if employee doesn't belong to the specified business.
     */
    fun update(businessId: UUID, id: UUID, employee: Employee): Employee? {
        // Security: Verify ownership
        val existing = employees[id]
        if (existing?.businessId != businessId) return null

        employees[id] = employee
        return employee
    }

    /**
     * Delete an employee with business verification.
     * Returns false if employee doesn't belong to the specified business.
     */
    fun delete(businessId: UUID, id: UUID): Boolean {
        val employee = employees[id]
        if (employee?.businessId != businessId) return false

        employees.remove(id)
        businessIndex[businessId]?.remove(id)
        return true
    }

    /**
     * Find employees by IDs with business verification.
     * Only returns employees that belong to the specified business.
     */
    fun findByIds(businessId: UUID, ids: List<UUID>): List<Employee> {
        return ids.mapNotNull { id ->
            val employee = employees[id]
            if (employee?.businessId == businessId) employee else null
        }
    }

    /**
     * Find employees by group name within a specific business.
     */
    fun findByGroup(businessId: UUID, groupName: String): List<Employee> {
        val groupKey = groupName.lowercase()
        val businessEmployees = findAllByBusiness(businessId)
        return businessEmployees.filter { employee ->
            employee.groups.any { it.lowercase() == groupKey }
        }
    }

    /**
     * Find employees by multiple group names within a specific business.
     */
    fun findByGroups(businessId: UUID, groupNames: Set<String>): List<Employee> {
        val groupKeys = groupNames.map { it.lowercase() }.toSet()
        val businessEmployees = findAllByBusiness(businessId)
        return businessEmployees.filter { employee ->
            employee.groups.any { it.lowercase() in groupKeys }
        }
    }

    /**
     * Clear all data (for testing).
     */
    fun clear() {
        employees.clear()
        businessIndex.clear()
    }
}
