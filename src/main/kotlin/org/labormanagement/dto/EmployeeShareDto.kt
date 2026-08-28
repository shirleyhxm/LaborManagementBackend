package org.labormanagement.dto

/**
 * A business an employee is currently lent to.
 */
data class EmployeeShareResponse(
    val employeeId: String,
    val businessId: String,
    val businessName: String,
    val sharedAt: String
)

data class EmployeeSharesListResponse(
    val employeeId: String,
    val homeBusinessId: String,
    val sharedWith: List<EmployeeShareResponse>
)

/**
 * Lend an employee to another business under the same owner.
 */
data class ShareEmployeeRequest(
    val businessId: String
)
