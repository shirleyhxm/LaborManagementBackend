package org.labormanagement.repository

import org.labormanagement.model.Employee
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EmployeeRepository {
    private val employees = ConcurrentHashMap<UUID, Employee>()

    fun create(employee: Employee): Employee? {
        val duplicate = employees.values.any {
            it.firstName == employee.firstName &&
            it.lastName == employee.lastName &&
            it.dateOfBirth.isEqual(employee.dateOfBirth)
        }
        return if (duplicate) {
            null
        } else {
            employees[employee.id] = employee
            employee
        }
    }

    fun findById(id: UUID): Employee? {
        return employees[id]
    }

    fun findAll(): List<Employee> {
        return employees.values.toList()
    }

    fun update(id: UUID, employee: Employee): Employee? {
        return if (employees.containsKey(id)) {
            employees[id] = employee
            employee
        } else {
            null
        }
    }

    fun delete(id: UUID): Boolean {
        return employees.remove(id) != null
    }

    fun findByIds(ids: List<UUID>): List<Employee> {
        return ids.mapNotNull { employees[it] }
    }

    fun findByGroup(groupName: String): List<Employee> {
        val groupKey = groupName.lowercase()
        return employees.values.filter { employee ->
            employee.groups.any { it.lowercase() == groupKey }
        }
    }

    fun findByGroups(groupNames: Set<String>): List<Employee> {
        val groupKeys = groupNames.map { it.lowercase() }.toSet()
        return employees.values.filter { employee ->
            employee.groups.any { it.lowercase() in groupKeys }
        }
    }
}
