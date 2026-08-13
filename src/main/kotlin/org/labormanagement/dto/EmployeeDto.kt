package org.labormanagement.dto

import org.labormanagement.model.Availability
import org.labormanagement.model.AvailabilityType
import org.labormanagement.model.Contract
import org.labormanagement.model.Employee
import org.labormanagement.util.parseFlexibleTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH)

data class CreateEmployeeRequest(
    val firstName: String,
    val lastName: String,
    val middleName: String = "",
    val dateOfBirth: String,
    val normalPayRate: Double,
    val overtimePayRate: Double,
    val productivity: Double = 1.0,
    val contract: ContractDto = ContractDto(),
    val availability: List<AvailabilityDto> = emptyList(),
    val groups: Set<String> = emptySet()
    // Note: businessId is NOT in the request - it comes from the URL path/context
)

data class UpdateEmployeeRequest(
    val firstName: String?,
    val lastName: String?,
    val middleName: String?,
    val dateOfBirth: String?,
    val normalPayRate: Double?,
    val overtimePayRate: Double?,
    val productivity: Double?,
    val contract: ContractDto?,
    val availability: List<AvailabilityDto>?,
    val groups: Set<String>?
)

data class EmployeeResponse(
    val id: String,
    val businessId: String,  // Include businessId for frontend reference
    val firstName: String,
    val lastName: String,
    val middleName: String,
    val fullName: String,
    val dateOfBirth: String,
    val normalPayRate: Double,
    val overtimePayRate: Double,
    val productivity: Double,
    val contract: ContractDto,
    val availability: List<AvailabilityDto>,
    val groups: Set<String>
)

data class ContractDto(
    val contractedHoursPerWeek: Double = 40.0,
    val maxHoursPerWeek: Double = 60.0,
    val maxHoursPerDay: Double = 12.0,
    val overtimeThreshold: Double = 40.0,
    val requiresBreak: Boolean = true,
    val breakDurationMinutes: Int = 30,
    val shiftLengthThresholdHours: Int = 4
)

data class AvailabilityDto(
    val dayOfWeek: String,
    val startTime: String,
    val endTime: String
)

// Extension functions for conversion
fun Employee.toResponse(): EmployeeResponse {
    return EmployeeResponse(
        id = this.id.toString(),
        businessId = this.businessId.toString(),
        firstName = this.firstName,
        lastName = this.lastName,
        middleName = this.middleName,
        fullName = this.fullName,
        dateOfBirth = this.dateOfBirth.format(DATE_FORMATTER),
        normalPayRate = this.normalPayRate,
        overtimePayRate = this.overtimePayRate,
        productivity = this.productivity,
        contract = this.contract.toDto(),
        availability = this.availability.map { it.toDto() },
        groups = this.groups
    )
}

fun Contract.toDto(): ContractDto {
    return ContractDto(
        contractedHoursPerWeek = this.contractedHoursPerWeek,
        maxHoursPerWeek = this.maxHoursPerWeek,
        maxHoursPerDay = this.maxHoursPerDay,
        overtimeThreshold = this.overtimeThreshold,
        requiresBreak = this.requiresBreak,
        breakDurationMinutes = this.breakDurationMinutes,
        shiftLengthThresholdHours = this.shiftLengthThresholdHours
    )
}

fun Availability.toDto(): AvailabilityDto {
    return AvailabilityDto(
        dayOfWeek = this.dayOfWeek?.name ?: "",
        startTime = this.startTime.toString(),
        endTime = this.endTime.toString()
    )
}

fun ContractDto.toModel(): Contract {
    return Contract(
        contractedHoursPerWeek = this.contractedHoursPerWeek,
        maxHoursPerWeek = this.maxHoursPerWeek,
        maxHoursPerDay = this.maxHoursPerDay,
        overtimeThreshold = this.overtimeThreshold,
        requiresBreak = this.requiresBreak,
        breakDurationMinutes = this.breakDurationMinutes,
        shiftLengthThresholdHours = this.shiftLengthThresholdHours
    )
}

fun AvailabilityDto.toModel(): Availability {
    return Availability(
        availabilityType = AvailabilityType.WEEKLY_RECURRING,
        dayOfWeek = DayOfWeek.valueOf(this.dayOfWeek.uppercase()),
        startTime = parseFlexibleTime(this.startTime),
        endTime = parseFlexibleTime(this.endTime)
    )
}

/**
 * Convert CreateEmployeeRequest to Employee model.
 * The businessId must be provided by the controller/service from the request context.
 */
fun CreateEmployeeRequest.toModel(businessId: UUID): Employee {
    return Employee(
        businessId = businessId,
        firstName = this.firstName,
        lastName = this.lastName,
        middleName = this.middleName,
        dateOfBirth = LocalDate.parse(this.dateOfBirth, DATE_FORMATTER),
        normalPayRate = this.normalPayRate,
        overtimePayRate = this.overtimePayRate,
        productivity = this.productivity,
        contract = this.contract.toModel(),
        availability = this.availability.map { it.toModel() },
        groups = this.groups
    )
}
