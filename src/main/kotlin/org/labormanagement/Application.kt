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
import org.labormanagement.controller.ConfigurationController
import org.labormanagement.controller.EmployeeController
import org.labormanagement.controller.SchedulingController
import org.labormanagement.controller.SchedulingRequestController
import org.labormanagement.controller.ScheduleHistoryController
import org.labormanagement.controller.TestDataController
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.SchedulingConfigurationRepository
import org.labormanagement.repository.SchedulingRequestRepository
import org.labormanagement.repository.ScheduleHistoryRepository
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
    val configurationRepository = SchedulingConfigurationRepository()
    val schedulingRequestRepository = SchedulingRequestRepository()
    val scheduleHistoryRepository = ScheduleHistoryRepository()
    val shiftScheduler = ShiftScheduler(
        configRepository = configurationRepository,
        schedulingRequestRepository = schedulingRequestRepository,
        employeeRepository = employeeRepository,
        scheduleHistoryRepository = scheduleHistoryRepository
    )

    // Initialize controllers
    val employeeController = EmployeeController(employeeRepository)
    val schedulingController = SchedulingController(shiftScheduler)
    val schedulingRequestController = SchedulingRequestController(
        schedulingRequestRepository,
        employeeRepository
    )
    val configurationController = ConfigurationController(configurationRepository)
    val scheduleHistoryController = ScheduleHistoryController(scheduleHistoryRepository)
    val testDataController = TestDataController(employeeRepository)

    // Configure plugins
    install(ContentNegotiation) {
        gson {
            setPrettyPrinting()
            serializeNulls()

            // Register type adapters for Java 8 time types
            registerTypeAdapter(LocalTime::class.java, JsonSerializer<LocalTime> { src, _, _ ->
                com.google.gson.JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_TIME))
            })
            registerTypeAdapter(LocalTime::class.java, JsonDeserializer { json, _, _ ->
                LocalTime.parse(json.asString, DateTimeFormatter.ISO_LOCAL_TIME)
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
                            "path" to "/api/scheduling/generate",
                            "methods" to listOf("POST"),
                            "description" to "Generate schedule from active scheduling request (no parameters required)"
                        ),
                        mapOf(
                            "path" to "/api/scheduling/sample-request",
                            "methods" to listOf("GET"),
                            "description" to "Get a sample scheduling request"
                        ),
                        mapOf(
                            "path" to "/api/scheduling-request",
                            "methods" to listOf("GET", "PUT", "DELETE"),
                            "description" to "View, save, or delete the latest scheduling request"
                        ),
                        mapOf(
                            "path" to "/api/scheduling-request/update",
                            "methods" to listOf("PUT"),
                            "description" to "Update specific fields of the latest scheduling request"
                        ),
                        mapOf(
                            "path" to "/api/configuration",
                            "methods" to listOf("GET", "PUT"),
                            "description" to "View or update scheduling configuration"
                        ),
                        mapOf(
                            "path" to "/api/configuration/reset",
                            "methods" to listOf("POST"),
                            "description" to "Reset configuration to default values"
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
                        )
                    )
                )
            )
        }

        // Register controller routes
        with(employeeController) {
            employeeRoutes()
        }

        with(schedulingController) {
            schedulingRoutes()
        }

        with(schedulingRequestController) {
            schedulingRequestRoutes()
        }

        with(configurationController) {
            configurationRoutes()
        }

        with(scheduleHistoryController) {
            scheduleHistoryRoutes()
        }

        with(testDataController) {
            testDataRoutes()
        }
    }

    log.info("Labor Management API started on port 8080")
}
