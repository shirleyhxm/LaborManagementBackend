package org.labormanagement.dto

/**
 * A location an employee is assigned to work at, beyond their home location.
 */
data class EmployeeLocationResponse(
    val employeeId: String,
    val businessId: String,
    val businessName: String,
    val assignedAt: String
)

data class EmployeeLocationsListResponse(
    val employeeId: String,
    val homeBusinessId: String,
    val assignedTo: List<EmployeeLocationResponse>
)

/**
 * Assign an employee to another location under the same owner.
 */
data class AssignEmployeeLocationRequest(
    val businessId: String
)
