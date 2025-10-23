package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import org.labormanagement.repository.ScheduleHistoryRepository
import java.time.Instant
import java.util.UUID

/**
 * Controller for managing schedule generation history and audit trail.
 * Provides REST APIs for viewing historical schedule generations.
 */
class ScheduleHistoryController(
    private val scheduleHistoryRepository: ScheduleHistoryRepository
) {

    fun Route.scheduleHistoryRoutes() {
        route("/api/schedule-history") {

            // Get all schedule history records (with optional pagination)
            get {
                try {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                    val history = scheduleHistoryRepository.findAll(limit, offset)
                    val total = scheduleHistoryRepository.count()

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "total" to total,
                            "limit" to limit,
                            "offset" to offset,
                            "history" to history
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve schedule history: ${e.message}")
                    )
                }
            }

            // Get the most recent schedule generation
            get("/latest") {
                try {
                    val latest = scheduleHistoryRepository.findLatest()
                    if (latest != null) {
                        call.respond(HttpStatusCode.OK, latest)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("message" to "No schedule history found")
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve latest schedule: ${e.message}")
                    )
                }
            }

            // Get a specific schedule history record by ID
            get("/{id}") {
                try {
                    val id = call.parameters["id"]?.let { UUID.fromString(it) }
                    if (id == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid or missing ID")
                        )
                        return@get
                    }

                    val history = scheduleHistoryRepository.findById(id)
                    if (history != null) {
                        call.respond(HttpStatusCode.OK, history)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Schedule history not found")
                        )
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid ID format")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve schedule history: ${e.message}")
                    )
                }
            }

            // Get schedule history by user
            get("/by-user/{user}") {
                try {
                    val user = call.parameters["user"]
                    if (user.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "User parameter is required")
                        )
                        return@get
                    }

                    val history = scheduleHistoryRepository.findByGeneratedBy(user)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "user" to user,
                            "count" to history.size,
                            "history" to history
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve user history: ${e.message}")
                    )
                }
            }

            // Delete a specific schedule history record
            delete("/{id}") {
                try {
                    val id = call.parameters["id"]?.let { UUID.fromString(it) }
                    if (id == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid or missing ID")
                        )
                        return@delete
                    }

                    val deleted = scheduleHistoryRepository.delete(id)
                    if (deleted) {
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf("message" to "Schedule history deleted successfully")
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Schedule history not found")
                        )
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid ID format")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete schedule history: ${e.message}")
                    )
                }
            }

            // Delete old history records (cleanup)
            delete("/cleanup/older-than/{days}") {
                try {
                    val days = call.parameters["days"]?.toLongOrNull()
                    if (days == null || days < 0) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid days parameter (must be >= 0)")
                        )
                        return@delete
                    }

                    val cutoffTime = Instant.now().minusSeconds(days * 24 * 60 * 60)
                    val deletedCount = scheduleHistoryRepository.deleteOlderThan(cutoffTime)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "message" to "Deleted $deletedCount schedule history records older than $days days",
                            "deletedCount" to deletedCount,
                            "cutoffDate" to cutoffTime.toString()
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to cleanup history: ${e.message}")
                    )
                }
            }
        }
    }
}