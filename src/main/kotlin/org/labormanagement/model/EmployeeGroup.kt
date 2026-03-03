package org.labormanagement.model

import java.util.UUID

// Simplified tag-based employee group (like an enum)
data class EmployeeGroup(
    val businessId: UUID,  // Multi-tenancy: Business this group belongs to
    val name: String
)