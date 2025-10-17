package org.labormanagement.model

import java.time.LocalDate
import java.util.UUID

data class Employee(
    val id: UUID = UUID.randomUUID(),
    val firstName: String,
    val lastName: String,
    val middleName: String = "",
    val dateOfBirth: LocalDate,
    val normalPayRate: Double,
    val overtimePayRate: Double,
    val productivity: Double, // Sales ($) per hour
    val contract: Contract,
    val availability: List<Availability>
) {
    val fullName: String
        get() = if (middleName.isNotEmpty()) "$firstName $middleName $lastName" else "$firstName $lastName"
}