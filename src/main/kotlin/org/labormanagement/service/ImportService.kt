package org.labormanagement.service

import org.labormanagement.dto.AvailabilityDto
import org.labormanagement.dto.CreateEmployeeRequest
import org.labormanagement.dto.ContractDto
import org.labormanagement.dto.toModel
import org.labormanagement.model.Employee
import org.labormanagement.repository.EmployeeRepository
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/**
 * Service for importing employees from CSV files.
 * Handles parsing and validation of bulk employee data.
 */
class ImportService(
    private val employeeRepository: EmployeeRepository
) {
    private val log = LoggerFactory.getLogger(ImportService::class.java)

    /**
     * Import employees from CSV content and save them to the repository.
     * Expected CSV format:
     * name,dateOfBirth,hourlyRate,overtimeRate,groups,availability,maxHoursPerWeek,productivity
     *
     * Example:
     * "John Doe","01/01/1990",18.50,27.75,"cashier;supervisor","MON 9:00-17:00;TUE 9:00-17:00",40,1.2
     */
    fun importEmployeesFromCsv(csvContent: String, businessId: UUID): EmployeeImportResponse {
        val errors = mutableListOf<String>()
        val employees = mutableListOf<Employee>()

        try {
            val lines = csvContent.trim().lines()

            if (lines.isEmpty()) {
                return EmployeeImportResponse(
                    success = false,
                    employeesImported = 0,
                    errors = listOf("CSV file is empty"),
                    employeeIds = emptyList()
                )
            }

            // Skip header row
            val dataLines = if (lines.first().lowercase().contains("name")) {
                lines.drop(1)
            } else {
                lines
            }

            dataLines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed

                try {
                    val employeeRequest = parseCsvLine(line, index + 1)
                    val employee = employeeRepository.create(employeeRequest.toModel(businessId))
                    if (employee != null) {
                        employees.add(employee)
                    } else {
                        errors.add("Line ${index + 1}: Failed to create employee (may already exist)")
                    }
                } catch (e: Exception) {
                    errors.add("Line ${index + 1}: ${e.message}")
                    log.warn("[ImportService] Failed to parse line ${index + 1}: $line", e)
                }
            }

            return EmployeeImportResponse(
                success = errors.isEmpty(),
                employeesImported = employees.size,
                errors = errors,
                employeeIds = employees.map { it.id.toString() }
            )

        } catch (e: Exception) {
            log.error("[ImportService] Failed to import employees from CSV", e)
            return EmployeeImportResponse(
                success = false,
                employeesImported = 0,
                errors = listOf("Failed to parse CSV: ${e.message}"),
                employeeIds = emptyList()
            )
        }
    }

    data class EmployeeImportResponse(
        val success: Boolean,
        val employeesImported: Int,
        val errors: List<String> = emptyList(),
        val employeeIds: List<String>
    )

    /**
     * Parse a single CSV line into a CreateEmployeeRequest.
     * Expected format: name,dateOfBirth,hourlyRate,overtimeRate,groups,availability,maxHoursPerWeek,productivity
     */
    private fun parseCsvLine(line: String, lineNumber: Int): CreateEmployeeRequest {
        // Simple CSV parsing (handles quoted fields)
        val fields = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
            .map { it.trim().removeSurrounding("\"") }

        if (fields.size < 6) {
            throw IllegalArgumentException("Expected at least 6 fields (name, dateOfBirth, hourlyRate, overtimeRate, groups, availability), got ${fields.size}")
        }

        val nameParts = fields[0].split(" ")
        val firstName = nameParts.firstOrNull() ?: throw IllegalArgumentException("Employee name cannot be empty")
        val lastName = nameParts.drop(1).joinToString(" ").ifBlank { "Unknown" }

        val dateOfBirth = fields[1].ifBlank { "01/01/1990" }

        val hourlyRate = try {
            fields[2].toDouble()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid hourly rate: ${fields[2]}")
        }

        if (hourlyRate <= 0) {
            throw IllegalArgumentException("Hourly rate must be positive")
        }

        val overtimeRate = try {
            if (fields[3].isNotBlank()) fields[3].toDouble() else hourlyRate * 1.5
        } catch (e: NumberFormatException) {
            hourlyRate * 1.5
        }

        val groups = if (fields[4].isNotBlank()) {
            fields[4].split(";").map { it.trim() }.toSet()
        } else {
            emptySet()
        }

        val availability = parseAvailability(fields[5])
        if (availability.isEmpty()) {
            throw IllegalArgumentException("Employee must have at least one availability slot")
        }

        val maxHoursPerWeek = if (fields.size > 6 && fields[6].isNotBlank()) {
            try {
                fields[6].toDouble()
            } catch (e: NumberFormatException) {
                40.0  // Default
            }
        } else {
            40.0
        }

        val productivity = if (fields.size > 7 && fields[7].isNotBlank()) {
            try {
                fields[7].toDouble()
            } catch (e: NumberFormatException) {
                1.0  // Default
            }
        } else {
            1.0
        }

        return CreateEmployeeRequest(
            firstName = firstName,
            lastName = lastName,
            dateOfBirth = dateOfBirth,
            normalPayRate = hourlyRate,
            overtimePayRate = overtimeRate,
            productivity = productivity,
            contract = ContractDto(
                maxHoursPerWeek = maxHoursPerWeek,
                contractedHoursPerWeek = maxHoursPerWeek
            ),
            availability = availability,
            groups = groups
        )
    }

    /**
     * Parse availability string into list of AvailabilityDto.
     * Format: "MON 9:00-17:00;TUE 9:00-17:00;WED 9:00-17:00"
     * or: "MON-FRI 9:00-17:00" (shorthand for Monday through Friday)
     */
    private fun parseAvailability(availabilityStr: String): List<AvailabilityDto> {
        if (availabilityStr.isBlank()) return emptyList()

        val slots = mutableListOf<AvailabilityDto>()

        availabilityStr.split(";").forEach { slotStr ->
            val trimmed = slotStr.trim()
            if (trimmed.isBlank()) return@forEach

            try {
                // Parse format: "MON 9:00-17:00" or "MON-FRI 9:00-17:00"
                val parts = trimmed.split(" ")
                if (parts.size != 2) {
                    throw IllegalArgumentException("Invalid availability format: $trimmed")
                }

                val dayPart = parts[0]
                val timePart = parts[1]

                val timeParts = timePart.split("-")
                if (timeParts.size != 2) {
                    throw IllegalArgumentException("Invalid time range format: $timePart")
                }

                val startTime = timeParts[0].trim()
                val endTime = timeParts[1].trim()

                // Check for day range (e.g., MON-FRI)
                if (dayPart.contains("-")) {
                    val dayRange = dayPart.split("-")
                    if (dayRange.size != 2) {
                        throw IllegalArgumentException("Invalid day range format: $dayPart")
                    }

                    val startDay = parseDayOfWeek(dayRange[0])
                    val endDay = parseDayOfWeek(dayRange[1])

                    // Expand range
                    var currentDay = startDay
                    while (true) {
                        slots.add(AvailabilityDto(
                            dayOfWeek = currentDay.name,
                            startTime = startTime,
                            endTime = endTime
                        ))

                        if (currentDay == endDay) break

                        currentDay = currentDay.plus(1)
                    }
                } else {
                    // Single day
                    val day = parseDayOfWeek(dayPart)
                    slots.add(AvailabilityDto(
                        dayOfWeek = day.name,
                        startTime = startTime,
                        endTime = endTime
                    ))
                }

            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to parse availability slot '$trimmed': ${e.message}")
            }
        }

        return slots
    }

    /**
     * Parse day of week string (supports various formats).
     */
    private fun parseDayOfWeek(dayStr: String): DayOfWeek {
        val normalized = dayStr.trim().uppercase()

        return when {
            normalized.startsWith("MON") -> DayOfWeek.MONDAY
            normalized.startsWith("TUE") -> DayOfWeek.TUESDAY
            normalized.startsWith("WED") -> DayOfWeek.WEDNESDAY
            normalized.startsWith("THU") -> DayOfWeek.THURSDAY
            normalized.startsWith("FRI") -> DayOfWeek.FRIDAY
            normalized.startsWith("SAT") -> DayOfWeek.SATURDAY
            normalized.startsWith("SUN") -> DayOfWeek.SUNDAY
            else -> {
                try {
                    DayOfWeek.valueOf(normalized)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid day of week: $dayStr")
                }
            }
        }
    }

    /**
     * Generate a sample CSV template for employee import.
     */
    fun generateCsvTemplate(): String {
        return """
            name,dateOfBirth,hourlyRate,overtimeRate,groups,availability,maxHoursPerWeek,productivity
            "John Doe","01/01/1990",18.50,27.75,"cashier;supervisor","MON-FRI 9:00-17:00",40,1.2
            "Jane Smith","15/03/1985",15.00,22.50,"floor","MON 9:00-17:00;WED 9:00-17:00;FRI 9:00-17:00",30,1.0
            "Bob Johnson","22/07/1988",22.00,33.00,"manager;cashier","MON-SAT 8:00-18:00",45,1.5
        """.trimIndent()
    }
}
