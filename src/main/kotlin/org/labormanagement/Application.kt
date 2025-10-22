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
import org.labormanagement.controller.TestDataController
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.SchedulingConfigurationRepository
import org.labormanagement.service.ShiftScheduler
import org.slf4j.event.Level

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Initialize repositories and services
    val employeeRepository = EmployeeRepository()
    val configurationRepository = SchedulingConfigurationRepository()
    val shiftScheduler = ShiftScheduler(configRepository = configurationRepository)

    // Initialize controllers
    val employeeController = EmployeeController(employeeRepository)
    val schedulingController = SchedulingController(
        employeeRepository,
        shiftScheduler
    )
    val configurationController = ConfigurationController(configurationRepository)
    val testDataController = TestDataController(employeeRepository)

    // Configure plugins
    install(ContentNegotiation) {
        gson {
            setPrettyPrinting()
            serializeNulls()
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
                            "description" to "Generate an optimized work schedule"
                        ),
                        mapOf(
                            "path" to "/api/scheduling/sample-request",
                            "methods" to listOf("GET"),
                            "description" to "Get a sample scheduling request"
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

        with(configurationController) {
            configurationRoutes()
        }

        with(testDataController) {
            testDataRoutes()
        }
    }

    log.info("Labor Management API started on port 8080")
}
