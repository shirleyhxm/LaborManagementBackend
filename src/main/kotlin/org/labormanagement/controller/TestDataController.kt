package org.labormanagement.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.labormanagement.dto.toResponse
import org.labormanagement.model.Availability
import org.labormanagement.model.Contract
import org.labormanagement.model.Employee
import org.labormanagement.repository.EmployeeRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class TestDataController(
    private val employeeRepository: EmployeeRepository
) {

    companion object {
        // Static sample employee templates to avoid recreating objects on every API call
        private val SAMPLE_EMPLOYEE_TEMPLATES = listOf(
            // High performers - Full time
            Employee(
                firstName = "Alice",
                lastName = "Johnson",
                middleName = "Marie",
                dateOfBirth = LocalDate.of(1990, 5, 15),
                normalPayRate = 20.0,
                overtimePayRate = 30.0,
                productivity = 250.0, // High productivity
                contract = Contract(
                    contractedHoursPerWeek = 40.0,
                    maxHoursPerWeek = 50.0,
                    maxHoursPerDay = 10.0,
                    overtimeThreshold = 40.0,
                    requiresBreak = true,
                    breakDurationMinutes = 30,
                    shiftLengthThresholdHours = 6
                ),
                availability = listOf(
                    Availability(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    Availability(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    Availability(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    Availability(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                    Availability(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(20, 0))
                )
            ),

            Employee(
                firstName = "Bob",
                lastName = "Smith",
                dateOfBirth = LocalDate.of(1988, 8, 22),
                normalPayRate = 22.0,
                overtimePayRate = 33.0,
                productivity = 280.0, // Very high productivity
                contract = Contract(
                    contractedHoursPerWeek = 40.0,
                    maxHoursPerWeek = 55.0,
                    maxHoursPerDay = 12.0,
                    overtimeThreshold = 40.0,
                    requiresBreak = true,
                    breakDurationMinutes = 30,
                    shiftLengthThresholdHours = 6
                ),
                availability = listOf(
                    Availability(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.THURSDAY, LocalTime.of(9, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(22, 0))
                )
            ),

            // Mid-level - Full time
            Employee(
                firstName = "Carol",
                lastName = "Davis",
                dateOfBirth = LocalDate.of(1992, 3, 10),
                normalPayRate = 18.0,
                overtimePayRate = 27.0,
                productivity = 180.0,
                contract = Contract(
                    contractedHoursPerWeek = 40.0,
                    maxHoursPerWeek = 48.0,
                    maxHoursPerDay = 10.0,
                    overtimeThreshold = 40.0,
                    requiresBreak = true,
                    breakDurationMinutes = 30,
                    shiftLengthThresholdHours = 6
                ),
                availability = listOf(
                    Availability(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(19, 0)),
                    Availability(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(19, 0)),
                    Availability(DayOfWeek.WEDNESDAY, LocalTime.of(10, 0), LocalTime.of(19, 0)),
                    Availability(DayOfWeek.THURSDAY, LocalTime.of(10, 0), LocalTime.of(19, 0)),
                    Availability(DayOfWeek.FRIDAY, LocalTime.of(10, 0), LocalTime.of(19, 0))
                )
            ),

            Employee(
                firstName = "David",
                lastName = "Wilson",
                dateOfBirth = LocalDate.of(1995, 11, 5),
                normalPayRate = 17.0,
                overtimePayRate = 25.5,
                productivity = 170.0,
                contract = Contract(
                    contractedHoursPerWeek = 40.0,
                    maxHoursPerWeek = 50.0,
                    maxHoursPerDay = 10.0,
                    overtimeThreshold = 40.0,
                    requiresBreak = true,
                    breakDurationMinutes = 30,
                    shiftLengthThresholdHours = 6
                ),
                availability = listOf(
                    Availability(DayOfWeek.MONDAY, LocalTime.of(7, 0), LocalTime.of(18, 0)),
                    Availability(DayOfWeek.TUESDAY, LocalTime.of(7, 0), LocalTime.of(18, 0)),
                    Availability(DayOfWeek.WEDNESDAY, LocalTime.of(7, 0), LocalTime.of(18, 0)),
                    Availability(DayOfWeek.THURSDAY, LocalTime.of(7, 0), LocalTime.of(18, 0)),
                    Availability(DayOfWeek.FRIDAY, LocalTime.of(7, 0), LocalTime.of(18, 0))
                )
            ),

            // Part-time employees
            Employee(
                firstName = "Emma",
                lastName = "Brown",
                dateOfBirth = LocalDate.of(1998, 6, 18),
                normalPayRate = 15.0,
                overtimePayRate = 22.5,
                productivity = 140.0,
                contract = Contract(
                    contractedHoursPerWeek = 25.0,
                    maxHoursPerWeek = 35.0,
                    maxHoursPerDay = 8.0,
                    overtimeThreshold = 25.0,
                    requiresBreak = true,
                    breakDurationMinutes = 30,
                    shiftLengthThresholdHours = 6
                ),
                availability = listOf(
                    Availability(DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(20, 0)),
                    Availability(DayOfWeek.WEDNESDAY, LocalTime.of(12, 0), LocalTime.of(20, 0)),
                    Availability(DayOfWeek.FRIDAY, LocalTime.of(12, 0), LocalTime.of(20, 0)),
                    Availability(DayOfWeek.SATURDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))
                )
            ),

            Employee(
                firstName = "Frank",
                lastName = "Miller",
                dateOfBirth = LocalDate.of(1997, 9, 25),
                normalPayRate = 16.0,
                overtimePayRate = 24.0,
                productivity = 150.0,
                contract = Contract(
                    contractedHoursPerWeek = 30.0,
                    maxHoursPerWeek = 40.0,
                    maxHoursPerDay = 8.0,
                    overtimeThreshold = 30.0,
                    requiresBreak = true,
                    breakDurationMinutes = 30,
                    shiftLengthThresholdHours = 6
                ),
                availability = listOf(
                    Availability(DayOfWeek.TUESDAY, LocalTime.of(14, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.THURSDAY, LocalTime.of(14, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.FRIDAY, LocalTime.of(14, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.SATURDAY, LocalTime.of(10, 0), LocalTime.of(18, 0)),
                    Availability(DayOfWeek.SUNDAY, LocalTime.of(10, 0), LocalTime.of(16, 0))
                )
            ),

            // Junior employees - Lower productivity
            Employee(
                firstName = "Grace",
                lastName = "Taylor",
                dateOfBirth = LocalDate.of(2000, 1, 30),
                normalPayRate = 14.0,
                overtimePayRate = 21.0,
                productivity = 110.0,
                contract = Contract(
                    contractedHoursPerWeek = 20.0,
                    maxHoursPerWeek = 30.0,
                    maxHoursPerDay = 8.0,
                    overtimeThreshold = 20.0,
                    requiresBreak = true,
                    breakDurationMinutes = 30,
                    shiftLengthThresholdHours = 6
                ),
                availability = listOf(
                    Availability(DayOfWeek.MONDAY, LocalTime.of(16, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.TUESDAY, LocalTime.of(16, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.WEDNESDAY, LocalTime.of(16, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.THURSDAY, LocalTime.of(16, 0), LocalTime.of(22, 0)),
                    Availability(DayOfWeek.SATURDAY, LocalTime.of(12, 0), LocalTime.of(20, 0)),
                    Availability(DayOfWeek.SUNDAY, LocalTime.of(12, 0), LocalTime.of(20, 0))
                )
            ),

            Employee(
                firstName = "Henry",
                lastName = "Anderson",
                dateOfBirth = LocalDate.of(1999, 12, 8),
                normalPayRate = 13.5,
                overtimePayRate = 20.25,
                productivity = 100.0,
                contract = Contract(
                    contractedHoursPerWeek = 20.0,
                    maxHoursPerWeek = 28.0,
                    maxHoursPerDay = 8.0,
                    overtimeThreshold = 20.0,
                    requiresBreak = true,
                    breakDurationMinutes = 30,
                    shiftLengthThresholdHours = 6
                ),
                availability = listOf(
                    Availability(DayOfWeek.MONDAY, LocalTime.of(18, 0), LocalTime.of(23, 0)),
                    Availability(DayOfWeek.WEDNESDAY, LocalTime.of(18, 0), LocalTime.of(23, 0)),
                    Availability(DayOfWeek.FRIDAY, LocalTime.of(18, 0), LocalTime.of(23, 0)),
                    Availability(DayOfWeek.SATURDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                    Availability(DayOfWeek.SUNDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))
                )
            ),

            // Weekend specialists
            Employee(
                firstName = "Isabel",
                lastName = "Martinez",
                dateOfBirth = LocalDate.of(1994, 4, 12),
                normalPayRate = 19.0,
                overtimePayRate = 28.5,
                productivity = 200.0,
                contract = Contract(
                    contractedHoursPerWeek = 24.0,
                    maxHoursPerWeek = 32.0,
                    maxHoursPerDay = 10.0,
                    overtimeThreshold = 24.0,
                    requiresBreak = true,
                    breakDurationMinutes = 30,
                    shiftLengthThresholdHours = 6
                ),
                availability = listOf(
                    Availability(DayOfWeek.FRIDAY, LocalTime.of(16, 0), LocalTime.of(23, 0)),
                    Availability(DayOfWeek.SATURDAY, LocalTime.of(8, 0), LocalTime.of(23, 0)),
                    Availability(DayOfWeek.SUNDAY, LocalTime.of(8, 0), LocalTime.of(23, 0))
                )
            ),

            Employee(
                firstName = "Jack",
                lastName = "Thomas",
                dateOfBirth = LocalDate.of(1996, 7, 20),
                normalPayRate = 16.5,
                overtimePayRate = 24.75,
                productivity = 160.0,
                contract = Contract(
                    contractedHoursPerWeek = 32.0,
                    maxHoursPerWeek = 40.0,
                    maxHoursPerDay = 10.0,
                    overtimeThreshold = 32.0,
                    requiresBreak = true,
                    breakDurationMinutes = 30,
                    shiftLengthThresholdHours = 6
                ),
                availability = listOf(
                    Availability(DayOfWeek.WEDNESDAY, LocalTime.of(11, 0), LocalTime.of(21, 0)),
                    Availability(DayOfWeek.THURSDAY, LocalTime.of(11, 0), LocalTime.of(21, 0)),
                    Availability(DayOfWeek.FRIDAY, LocalTime.of(11, 0), LocalTime.of(23, 0)),
                    Availability(DayOfWeek.SATURDAY, LocalTime.of(9, 0), LocalTime.of(21, 0)),
                    Availability(DayOfWeek.SUNDAY, LocalTime.of(9, 0), LocalTime.of(21, 0))
                )
            )
        )
    }

    fun Route.testDataRoutes() {
        route("/api/test") {

            // Create 10 sample employees
            post("/create-sample-employees") {
                try {
                    // Clear existing employees first
                    val existingEmployees = employeeRepository.findAll()
                    existingEmployees.forEach { employeeRepository.delete(it.id) }

                    val createdEmployees = SAMPLE_EMPLOYEE_TEMPLATES.map { employee ->
                        employeeRepository.create(employee)
                    }

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "message" to "Created ${createdEmployees.size} sample employees",
                            "employees" to createdEmployees.filterNotNull().map { it.toResponse() }
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Failed to create sample employees"))
                    )
                }
            }

            // Get IDs of all employees (useful for scheduling requests)
            get("/employee-ids") {
                val employees = employeeRepository.findAll()
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "count" to employees.size,
                        "employeeIds" to employees.map { it.id.toString() }
                    )
                )
            }
        }
    }
}
