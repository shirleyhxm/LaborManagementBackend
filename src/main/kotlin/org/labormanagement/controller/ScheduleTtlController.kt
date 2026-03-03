package org.labormanagement.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.labormanagement.service.ScheduleTtlService

/**
 * Controller for schedule TTL (Time-To-Live) management endpoints.
 *
 * Provides manual cleanup triggers and statistics about the TTL cleanup process.
 */
class ScheduleTtlController(
    private val scheduleTtlService: ScheduleTtlService
) {
    fun Route.scheduleTtlRoutes() {
        route("/api/schedules/ttl") {

            /**
             * GET /api/schedules/ttl/stats
             * Get statistics about schedules that would be cleaned up
             */
            get("/stats") {
                try {
                    val stats = scheduleTtlService.getCleanupStats()
                    call.respond(HttpStatusCode.OK, stats)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get cleanup stats: ${e.message}")
                    )
                }
            }

            /**
             * POST /api/schedules/ttl/cleanup
             * Manually trigger schedule cleanup
             *
             * Query parameters:
             * - dryRun (optional, default: false): If true, only reports what would be deleted
             */
            post("/cleanup") {
                try {
                    val dryRun = call.request.queryParameters["dryRun"]?.toBoolean() ?: false
                    val result = scheduleTtlService.cleanupOldSchedules(dryRun = dryRun)

                    val response = mapOf(
                        "success" to true,
                        "dryRun" to dryRun,
                        "deletedCount" to result.deletedCount,
                        "retainedCount" to result.retainedCount,
                        "cutoffDate" to result.cutoffDate.toString(),
                        "errors" to result.errors,
                        "message" to if (dryRun) {
                            "Dry run completed: ${result.deletedCount} schedules would be deleted"
                        } else {
                            "Cleanup completed: ${result.deletedCount} schedules deleted"
                        }
                    )

                    call.respond(HttpStatusCode.OK, response)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "success" to false,
                            "error" to "Failed to perform cleanup: ${e.message}"
                        )
                    )
                }
            }

            /**
             * GET /api/schedules/ttl/config
             * Get current TTL configuration
             */
            get("/config") {
                try {
                    val config = mapOf(
                        "retentionWeeks" to scheduleTtlService.calculateCutoffDate().let {
                            java.time.temporal.ChronoUnit.WEEKS.between(it, java.time.LocalDate.now())
                        },
                        "cutoffDate" to scheduleTtlService.calculateCutoffDate().toString(),
                        "description" to "Schedules with start dates before the cutoff date will be automatically deleted"
                    )
                    call.respond(HttpStatusCode.OK, config)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get TTL config: ${e.message}")
                    )
                }
            }
        }
    }
}
