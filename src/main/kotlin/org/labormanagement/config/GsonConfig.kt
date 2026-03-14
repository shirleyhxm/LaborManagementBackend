package org.labormanagement.config

import com.google.gson.*
import org.labormanagement.model.ConstraintViolation
import org.labormanagement.model.ViolationType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Shared Gson configuration for both HTTP serialization and database JSON storage.
 * Ensures consistent serialization/deserialization across the application.
 */
object GsonConfig {

    /**
     * Create a configured Gson instance with custom TypeAdapters for Java 8 time types.
     */
    fun createGson(): Gson {
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        return GsonBuilder()
            .serializeNulls()
            .enableComplexMapKeySerialization()
            // LocalTime adapter
            .registerTypeAdapter(LocalTime::class.java, JsonSerializer<LocalTime> { src, _, _ ->
                com.google.gson.JsonPrimitive(src.format(timeFormatter))
            })
            .registerTypeAdapter(LocalTime::class.java, JsonDeserializer { json, _, _ ->
                LocalTime.parse(json.asString, timeFormatter)
            })
            // LocalDate adapter
            .registerTypeAdapter(LocalDate::class.java, JsonSerializer<LocalDate> { src, _, _ ->
                com.google.gson.JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE))
            })
            .registerTypeAdapter(LocalDate::class.java, JsonDeserializer { json, _, _ ->
                LocalDate.parse(json.asString, DateTimeFormatter.ISO_LOCAL_DATE)
            })
            // Instant adapter
            .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { src, _, _ ->
                com.google.gson.JsonPrimitive(src.toString())
            })
            .registerTypeAdapter(Instant::class.java, JsonDeserializer { json, _, _ ->
                Instant.parse(json.asString)
            })
            // DayOfWeek adapter
            .registerTypeAdapter(DayOfWeek::class.java, JsonSerializer<DayOfWeek> { src, _, _ ->
                com.google.gson.JsonPrimitive(src.name)
            })
            .registerTypeAdapter(DayOfWeek::class.java, JsonDeserializer { json, _, _ ->
                DayOfWeek.valueOf(json.asString)
            })
            // ConstraintViolation sealed class adapter
            .registerTypeAdapter(ConstraintViolation::class.java, ConstraintViolationAdapter(timeFormatter, dateFormatter))
            .create()
    }

    /**
     * Custom type adapter for ConstraintViolation sealed class hierarchy.
     * Uses a "violationClass" discriminator to determine which subclass to instantiate.
     */
    private class ConstraintViolationAdapter(
        private val timeFormatter: DateTimeFormatter,
        private val dateFormatter: DateTimeFormatter
    ) : JsonSerializer<ConstraintViolation>, JsonDeserializer<ConstraintViolation> {

        override fun serialize(src: ConstraintViolation, typeOfSrc: java.lang.reflect.Type, context: JsonSerializationContext): JsonElement {
            val obj = JsonObject()

            // Add discriminator field
            obj.addProperty("violationClass", src::class.simpleName)
            obj.addProperty("type", src.type.name)
            obj.addProperty("description", src.description)

            // Add subclass-specific fields
            when (src) {
                is ConstraintViolation.ScheduleLevel -> {
                    // No additional fields
                }
                is ConstraintViolation.TimeBlock -> {
                    obj.addProperty("date", src.date.format(dateFormatter))
                    obj.addProperty("startTime", src.startTime.format(timeFormatter))
                    obj.addProperty("endTime", src.endTime.format(timeFormatter))
                }
                is ConstraintViolation.Employee -> {
                    obj.addProperty("employeeId", src.employeeId.toString())
                }
                is ConstraintViolation.EmployeeDay -> {
                    obj.addProperty("employeeId", src.employeeId.toString())
                    obj.addProperty("date", src.date.format(dateFormatter))
                }
                is ConstraintViolation.Shift -> {
                    obj.addProperty("employeeId", src.employeeId.toString())
                    obj.addProperty("date", src.date.format(dateFormatter))
                    obj.addProperty("startTime", src.startTime.format(timeFormatter))
                    obj.addProperty("endTime", src.endTime.format(timeFormatter))
                }
            }

            return obj
        }

        override fun deserialize(json: JsonElement, typeOfT: java.lang.reflect.Type, context: JsonDeserializationContext): ConstraintViolation {
            val obj = json.asJsonObject

            // Check if violationClass exists (new format), otherwise infer from fields (backward compatibility)
            val violationClass = obj.get("violationClass")?.asString

            if (violationClass == null) {
                // Backward compatibility: infer type from fields present
                val type = ViolationType.valueOf(obj.get("type").asString)
                val description = obj.get("description").asString

                return when {
                    obj.has("employeeId") && obj.has("date") && obj.has("startTime") && obj.has("endTime") -> {
                        // Shift violation
                        ConstraintViolation.Shift(
                            type = type,
                            description = description,
                            employeeId = UUID.fromString(obj.get("employeeId").asString),
                            date = LocalDate.parse(obj.get("date").asString, dateFormatter),
                            startTime = LocalTime.parse(obj.get("startTime").asString, timeFormatter),
                            endTime = LocalTime.parse(obj.get("endTime").asString, timeFormatter)
                        )
                    }
                    obj.has("employeeId") && obj.has("date") && !obj.has("startTime") -> {
                        // EmployeeDay violation
                        ConstraintViolation.EmployeeDay(
                            type = type,
                            description = description,
                            employeeId = UUID.fromString(obj.get("employeeId").asString),
                            date = LocalDate.parse(obj.get("date").asString, dateFormatter)
                        )
                    }
                    obj.has("employeeId") && !obj.has("date") -> {
                        // Employee violation
                        ConstraintViolation.Employee(
                            type = type,
                            description = description,
                            employeeId = UUID.fromString(obj.get("employeeId").asString)
                        )
                    }
                    obj.has("date") && obj.has("startTime") && obj.has("endTime") && !obj.has("employeeId") -> {
                        // TimeBlock violation
                        ConstraintViolation.TimeBlock(
                            type = type,
                            description = description,
                            date = LocalDate.parse(obj.get("date").asString, dateFormatter),
                            startTime = LocalTime.parse(obj.get("startTime").asString, timeFormatter),
                            endTime = LocalTime.parse(obj.get("endTime").asString, timeFormatter)
                        )
                    }
                    else -> {
                        // ScheduleLevel violation (fallback)
                        ConstraintViolation.ScheduleLevel(type, description)
                    }
                }
            }

            // New format with violationClass discriminator
            val type = ViolationType.valueOf(obj.get("type").asString)
            val description = obj.get("description").asString

            return when (violationClass) {
                "ScheduleLevel" -> ConstraintViolation.ScheduleLevel(type, description)

                "TimeBlock" -> ConstraintViolation.TimeBlock(
                    type = type,
                    description = description,
                    date = LocalDate.parse(obj.get("date").asString, dateFormatter),
                    startTime = LocalTime.parse(obj.get("startTime").asString, timeFormatter),
                    endTime = LocalTime.parse(obj.get("endTime").asString, timeFormatter)
                )

                "Employee" -> ConstraintViolation.Employee(
                    type = type,
                    description = description,
                    employeeId = UUID.fromString(obj.get("employeeId").asString)
                )

                "EmployeeDay" -> ConstraintViolation.EmployeeDay(
                    type = type,
                    description = description,
                    employeeId = UUID.fromString(obj.get("employeeId").asString),
                    date = LocalDate.parse(obj.get("date").asString, dateFormatter)
                )

                "Shift" -> ConstraintViolation.Shift(
                    type = type,
                    description = description,
                    employeeId = UUID.fromString(obj.get("employeeId").asString),
                    date = LocalDate.parse(obj.get("date").asString, dateFormatter),
                    startTime = LocalTime.parse(obj.get("startTime").asString, timeFormatter),
                    endTime = LocalTime.parse(obj.get("endTime").asString, timeFormatter)
                )

                else -> throw JsonParseException("Unknown ConstraintViolation subclass: $violationClass")
            }
        }
    }
}
