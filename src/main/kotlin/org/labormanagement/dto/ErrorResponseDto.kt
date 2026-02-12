package org.labormanagement.dto

import org.labormanagement.model.ConstraintViolation
import org.labormanagement.model.ViolationType
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/**
 * Standardized error response for validation failures.
 * Returns detailed information about constraint violations to help
 * the frontend explain to users why an operation failed.
 */
data class ValidationErrorResponse(
    val success: Boolean = false,
    val message: String,
    val violations: List<ViolationDto>
)

/**
 * DTO for serializing constraint violations to JSON.
 * Includes all relevant identifiers so the frontend can map violations
 * to specific UI components (employees, shifts, time blocks).
 */
data class ViolationDto(
    val type: ViolationType,
    val description: String,
    val scope: ViolationScope,

    // Optional fields based on violation scope
    val employeeId: String? = null,
    val dayOfWeek: String? = null,
    val startTime: String? = null,
    val endTime: String? = null
)

/**
 * Categorizes violations by scope to help frontend routing
 */
enum class ViolationScope {
    SCHEDULE,      // Budget, overall schedule issues
    TIME_BLOCK,    // Understaffing at specific time
    EMPLOYEE,      // Weekly hours exceeded
    EMPLOYEE_DAY,  // Daily hours exceeded
    SHIFT          // Availability conflict, overlap, individual shift issues
}

/**
 * Extension function to convert sealed ConstraintViolation to serializable DTO
 */
fun ConstraintViolation.toDto(): ViolationDto {
    return when (this) {
        is ConstraintViolation.ScheduleLevel -> ViolationDto(
            type = this.type,
            description = this.description,
            scope = ViolationScope.SCHEDULE
        )

        is ConstraintViolation.TimeBlock -> ViolationDto(
            type = this.type,
            description = this.description,
            scope = ViolationScope.TIME_BLOCK,
            dayOfWeek = this.date.dayOfWeek.toString(),
            startTime = this.startTime.toString(),
            endTime = this.endTime.toString()
        )

        is ConstraintViolation.Employee -> ViolationDto(
            type = this.type,
            description = this.description,
            scope = ViolationScope.EMPLOYEE,
            employeeId = this.employeeId.toString()
        )

        is ConstraintViolation.EmployeeDay -> ViolationDto(
            type = this.type,
            description = this.description,
            scope = ViolationScope.EMPLOYEE_DAY,
            employeeId = this.employeeId.toString(),
            dayOfWeek = this.date.dayOfWeek.toString()
        )

        is ConstraintViolation.Shift -> ViolationDto(
            type = this.type,
            description = this.description,
            scope = ViolationScope.SHIFT,
            employeeId = this.employeeId.toString(),
            dayOfWeek = this.date.dayOfWeek.toString(),
            startTime = this.startTime.toString(),
            endTime = this.endTime.toString()
        )
    }
}

/**
 * Helper to create validation error response from violations
 */
fun createValidationErrorResponse(
    violations: List<ConstraintViolation>,
    message: String = "Shift modification failed due to constraint violations"
): ValidationErrorResponse {
    return ValidationErrorResponse(
        success = false,
        message = message,
        violations = violations.map { it.toDto() }
    )
}