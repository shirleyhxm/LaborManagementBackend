package org.labormanagement

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import org.labormanagement.controller.AuthController
import org.labormanagement.controller.BusinessController
import org.labormanagement.controller.ConstraintsController
import org.labormanagement.controller.EmployeeController
import org.labormanagement.controller.EmployeeGroupController
import org.labormanagement.controller.OptimizationControllerV2
import org.labormanagement.controller.SalesForecastController
import org.labormanagement.controller.ScheduleController
import org.labormanagement.controller.ScheduleTtlController
import org.labormanagement.controller.TestDataController
import org.labormanagement.controller.attendanceRoutes
import org.labormanagement.controller.timeoffRoutes
import org.labormanagement.controller.salesRoutes
import org.labormanagement.repository.AttendanceRepository
import org.labormanagement.repository.BusinessRepository
import org.labormanagement.repository.EmployeeRepository
import org.labormanagement.repository.EmployeeGroupRepository
import org.labormanagement.repository.SalesForecastRepository
import org.labormanagement.repository.SalesRepository
import org.labormanagement.repository.ScheduleRepository
import org.labormanagement.repository.TimeoffRepository
import org.labormanagement.repository.UserRepository
import org.labormanagement.service.AttendanceService
import org.labormanagement.service.AuthService
import org.labormanagement.service.BusinessService
import org.labormanagement.service.ConstraintValidator
import org.labormanagement.service.ConstraintsService
import org.labormanagement.service.ImportService
import org.labormanagement.service.JwtService
import org.labormanagement.service.OptimizationJobService
import org.labormanagement.service.SalesService
import org.labormanagement.service.ScheduleTtlService
import org.labormanagement.service.SchedulingApproach
import org.labormanagement.service.ShiftModificationService
import org.labormanagement.service.ShiftScheduler
import org.labormanagement.service.TimeoffService
import org.slf4j.event.Level
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
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
    val businessRepository = BusinessRepository()
    val employeeRepository = EmployeeRepository()
    val employeeGroupRepository = EmployeeGroupRepository()
    val scheduleRepository = ScheduleRepository()
    val salesForecastRepository = SalesForecastRepository()
    val userRepository = UserRepository()
    val attendanceRepository = AttendanceRepository()
    val timeoffRepository = TimeoffRepository()
    val salesRepository = SalesRepository()
    val constraintValidator = ConstraintValidator()
    val constraintsService = ConstraintsService()
    val schedulingApproach = SchedulingApproach.OPTIMIZER

    val shiftScheduler = ShiftScheduler(
        employeeRepository = employeeRepository,
        scheduleRepository = scheduleRepository,
        salesForecastRepository = salesForecastRepository,
        constraintsService = constraintsService,
        schedulingApproach = schedulingApproach
    )

    val shiftModificationService = ShiftModificationService(
        scheduleRepository = scheduleRepository,
        employeeRepository = employeeRepository,
        constraintValidator = constraintValidator
    )

    // Initialize auth services (business service initialized after for dependency)
    val jwtService = JwtService()

    // Initialize business service (multi-tenancy)
    val businessService = BusinessService(
        businessRepository = businessRepository,
        userRepository = userRepository
    )

    // Initialize auth service with business service for auto-business creation on registration
    val authService = AuthService(
        userRepository = userRepository,
        jwtService = jwtService,
        businessService = businessService
    )

    // Initialize new services
    val attendanceService = AttendanceService(
        attendanceRepository = attendanceRepository,
        employeeRepository = employeeRepository,
        scheduleRepository = scheduleRepository
    )

    val timeoffService = TimeoffService(
        timeoffRepository = timeoffRepository,
        employeeRepository = employeeRepository
    )

    val salesService = SalesService(
        salesRepository = salesRepository,
        employeeRepository = employeeRepository,
        scheduleRepository = scheduleRepository,
        attendanceRepository = attendanceRepository
    )

    // Initialize TTL service for automatic schedule cleanup
    val ttlRetentionWeeks = System.getenv("SCHEDULE_TTL_WEEKS")?.toIntOrNull() ?: 2
    val ttlCleanupIntervalHours = System.getenv("SCHEDULE_TTL_CLEANUP_INTERVAL_HOURS")?.toLongOrNull() ?: 24L
    val scheduleTtlService = ScheduleTtlService(
        scheduleRepository = scheduleRepository,
        businessRepository = businessRepository,
        retentionWeeks = ttlRetentionWeeks
    )

    // Initialize v2 services (simplified optimization API - wraps ShiftScheduler)
    val importService = ImportService(employeeRepository)
    val optimizationJobService = OptimizationJobService(
        shiftScheduler = shiftScheduler,
        employeeRepository = employeeRepository
    )

    // Initialize controllers
    val businessController = BusinessController(businessService)
    val employeeController = EmployeeController(employeeRepository, importService)
    val employeeGroupController = EmployeeGroupController(employeeGroupRepository)
    val scheduleController = ScheduleController(
        scheduleRepository = scheduleRepository,
        shiftScheduler = shiftScheduler,
        shiftModificationService = shiftModificationService
    )
    val salesForecastController = SalesForecastController(salesForecastRepository)
    val testDataController = TestDataController(employeeRepository)
    val authController = AuthController(authService)
    val constraintsController = ConstraintsController(constraintsService)
    val optimizationControllerV2 = OptimizationControllerV2(
        optimizationJobService = optimizationJobService
    )
    val scheduleTtlController = ScheduleTtlController(
        scheduleTtlService = scheduleTtlService
    )

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
        // Allow specific origins (localhost for development)
        allowHost("localhost:3001", schemes = listOf("http"))
        allowHost("localhost:3000", schemes = listOf("http"))
        allowHost("localhost:4173", schemes = listOf("http"))  // Vite preview server

        // Allow all standard headers
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-User-Id")
        allowHeader("X-Business-Id")
        allowHeader(HttpHeaders.AccessControlAllowOrigin)

        // Allow credentials (cookies, authorization headers)
        allowCredentials = true

        // Allow all standard HTTP methods
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)

        // Expose headers so frontend can read them
        exposeHeader(HttpHeaders.ContentType)
        exposeHeader(HttpHeaders.Authorization)
        exposeHeader("X-User-Id")
        exposeHeader("X-Business-Id")
        exposeHeader(HttpHeaders.AccessControlAllowOrigin)
    }

    log.info("CORS configured for localhost:3000, localhost:3001, and localhost:4173")

    // Configure JWT Authentication
    val jwtSecret = System.getenv("JWT_SECRET") ?: "labor-management-secret-key-change-this-in-production-minimum-256-bits"
    val jwtIssuer = "labor-management-app"
    val jwtAudience = "labor-management-users"
    val jwtRealm = "Labor Management App"

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtRealm
            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(jwtAudience)) {
                    io.ktor.server.auth.jwt.JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            // Skip authentication for OPTIONS requests (CORS preflight)
            skipWhen { call ->
                call.request.httpMethod == HttpMethod.Options
            }
        }
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
        // Global OPTIONS handler for CORS preflight - must be first
        options("{...}") {
            call.respond(HttpStatusCode.OK)
        }

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
        with(authController) {
            authRoutes()
        }

        with(businessController) {
            businessRoutes()
        }

        with(employeeController) {
            employeeRoutes()
        }

        with(employeeGroupController) {
            employeeGroupRoutes()
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

        // Register new employee operation routes
        attendanceRoutes(attendanceService)
        timeoffRoutes(timeoffService)
        salesRoutes(salesService)

        // Register constraints routes
        with(constraintsController) {
            constraintsRoutes()
        }

        // Register v2 optimization routes (simplified API)
        with(optimizationControllerV2) {
            optimizationRoutesV2()
        }

        // Register TTL management routes
        with(scheduleTtlController) {
            scheduleTtlRoutes()
        }
    }

    log.info("Labor Management API started on port 8080")
    log.info("Scheduling approach: $schedulingApproach")
    log.info("V2 Optimization API available at /api/v2")

    // Start background TTL cleanup task
    log.info("Starting schedule TTL cleanup task (retention: $ttlRetentionWeeks weeks, interval: $ttlCleanupIntervalHours hours)")
    GlobalScope.launch {
        // Initial delay of 1 minute to allow server to fully start
        delay(60_000)

        while (true) {
            try {
                log.info("Running scheduled TTL cleanup...")
                val result = scheduleTtlService.cleanupOldSchedules(dryRun = false)
                log.info("TTL cleanup completed: deleted=${result.deletedCount}, retained=${result.retainedCount}, errors=${result.errors.size}")

                if (result.errors.isNotEmpty()) {
                    log.warn("TTL cleanup errors: ${result.errors.joinToString("; ")}")
                }
            } catch (e: Exception) {
                log.error("Error during scheduled TTL cleanup", e)
            }

            // Wait for the next cleanup interval
            delay(ttlCleanupIntervalHours.hours)
        }
    }
}
