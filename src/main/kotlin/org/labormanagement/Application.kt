package org.labormanagement

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.routing.get
import org.labormanagement.controller.EmployeeController
import org.labormanagement.controller.SalesForecastController
import org.labormanagement.controller.ScheduleController
import org.labormanagement.controller.TestDataController
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.SalesForecastRepository
import org.labormanagement.repository.ScheduleRepository
import org.labormanagement.service.ConstraintValidator
import org.labormanagement.service.ShiftModificationService
import org.labormanagement.service.ShiftScheduler
import org.slf4j.event.Level
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Initialize repositories and services
    val employeeRepository = EmployeeRepository()
    val scheduleRepository = ScheduleRepository()
    val salesForecastRepository = SalesForecastRepository()

    val constraintValidator = ConstraintValidator()
    val shiftScheduler = ShiftScheduler(
        employeeRepository = employeeRepository,
        scheduleRepository = scheduleRepository,
        salesForecastRepository = salesForecastRepository
    )

    val shiftModificationService = ShiftModificationService(
        scheduleRepository = scheduleRepository,
        employeeRepository = employeeRepository,
        constraintValidator = constraintValidator
    )

    // Initialize controllers
    val employeeController = EmployeeController(employeeRepository)
    val scheduleController = ScheduleController(
        scheduleRepository = scheduleRepository,
        shiftScheduler = shiftScheduler,
        shiftModificationService = shiftModificationService
    )
    val salesForecastController = SalesForecastController(salesForecastRepository)
    val testDataController = TestDataController(employeeRepository)

    // Configure plugins
    install(ContentNegotiation) {
        gson {
            // Pretty printing disabled for performance - adds 3-5x serialization overhead
            // Use browser extensions (JSONView) for formatting instead
            serializeNulls()

            // Register type adapters for Java 8 time types
            // Use HH:mm format for times (e.g., "09:00" instead of "09:00:00")
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
            registerTypeAdapter(LocalTime::class.java, JsonSerializer<LocalTime> { src, _, _ ->
                com.google.gson.JsonPrimitive(src.format(timeFormatter))
            })
            registerTypeAdapter(LocalTime::class.java, JsonDeserializer { json, _, _ ->
                LocalTime.parse(json.asString, timeFormatter)
            })

            registerTypeAdapter(LocalDate::class.java, JsonSerializer<LocalDate> { src, _, _ ->
                com.google.gson.JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE))
            })
            registerTypeAdapter(LocalDate::class.java, JsonDeserializer { json, _, _ ->
                LocalDate.parse(json.asString, DateTimeFormatter.ISO_LOCAL_DATE)
            })

            registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { src, _, _ ->
                com.google.gson.JsonPrimitive(src.toString())
            })
            registerTypeAdapter(Instant::class.java, JsonDeserializer { json, _, _ ->
                Instant.parse(json.asString)
            })

            registerTypeAdapter(DayOfWeek::class.java, JsonSerializer<DayOfWeek> { src, _, _ ->
                com.google.gson.JsonPrimitive(src.name)
            })
            registerTypeAdapter(DayOfWeek::class.java, JsonDeserializer { json, _, _ ->
                DayOfWeek.valueOf(json.asString)
            })
        }
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error: ${cause.message}")
            )
        }
    }

    // Configure routing
    routing {
        // Health check endpoint
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "UP",
                    "org/labormanagement/serviceormanagement/service" to "Labor Management API",
                    "version" to "1.0.0"
                )
            )
        }

        // API documentation endpoint
        get("/api") {
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "name" to "Labor Management API",
                    "version" to "1.0.0",
                    "endpoints" to listOf(
                        mapOf(
                            "path" to "/api/employees",
                            "methods" to listOf("GET", "POST"),
                            "description" to "Manage employees"
                        ),
                        mapOf(
                            "path" to "/api/employees/{id}",
                            "methods" to listOf("POST", "GET", "PUT", "DELETE"),
                            "description" to "Create, Get, update, or delete a specific employee"
                        ),
                        mapOf(
                            "path" to "/api/schedules/generate",
                            "methods" to listOf("POST"),
                            "description" to "Generate schedule from ScheduleInput (requires ScheduleInput in request body)"
                        ),
                        mapOf(
                            "path" to "/api/test/create-sample-employees",
                            "methods" to listOf("POST"),
                            "description" to "Create 10 sample employees for testing"
                        ),
                        mapOf(
                            "path" to "/api/test/employee-ids",
                            "methods" to listOf("GET"),
                            "description" to "Get all employee IDs for scheduling requests"
                        ),
                        mapOf(
                            "path" to "/api/schedule-history",
                            "methods" to listOf("GET"),
                            "description" to "View all schedule generation history (with pagination)"
                        ),
                        mapOf(
                            "path" to "/api/schedule-history/latest",
                            "methods" to listOf("GET"),
                            "description" to "Get the most recent schedule generation"
                        ),
                        mapOf(
                            "path" to "/api/schedule-history/{id}",
                            "methods" to listOf("GET", "DELETE"),
                            "description" to "Get or delete a specific schedule history record"
                        ),
                        mapOf(
                            "path" to "/api/schedule-history/by-user/{user}",
                            "methods" to listOf("GET"),
                            "description" to "Get schedule history by user"
                        ),
                        mapOf(
                            "path" to "/api/sales-forecast",
                            "methods" to listOf("GET", "PUT"),
                            "description" to "Get or update the sales forecast"
                        ),
                        mapOf(
                            "path" to "/api/sales-forecast/reset",
                            "methods" to listOf("POST"),
                            "description" to "Reset sales forecast to default values"
                        )
                    )
                )
            )
        }

        // Register controller routes
        with(employeeController) {
            employeeRoutes()
        }

        with(scheduleController) {
            scheduleRoutes()
        }

        with(salesForecastController) {
            salesForecastRoutes()
        }

        with(testDataController) {
            testDataRoutes()
        }
    }

    log.info("Labor Management API started on port 8080")
}
