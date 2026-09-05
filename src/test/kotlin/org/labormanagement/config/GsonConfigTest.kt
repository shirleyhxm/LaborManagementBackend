package org.labormanagement.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.labormanagement.controller.GenerateScheduleRequest
import org.labormanagement.dto.CreateEmployeeRequest
import org.labormanagement.dto.EventStaffingRequirementDto
import org.labormanagement.dto.SpecialEventResponse
import java.util.UUID

class GsonConfigTest {

    private val gson = GsonConfig.createGson()

    @Test
    fun `applies Kotlin defaults for fields omitted from JSON`() {
        // No middleName, no groups - the exact payload shape that crashed
        // POST /api/businesses/{id}/employees with a NullPointerException
        // before KotlinDefaultsTypeAdapterFactory existed.
        val json = """
            {
                "firstName": "Bob",
                "lastName": "Smith",
                "dateOfBirth": "22/08/1988",
                "normalPayRate": 22.0,
                "overtimePayRate": 33.0
            }
        """.trimIndent()

        val request = gson.fromJson(json, CreateEmployeeRequest::class.java)

        assertEquals("Bob", request.firstName)
        assertEquals("Smith", request.lastName)
        assertEquals("", request.middleName)
        assertEquals(emptySet<String>(), request.groups)
        assertEquals(1.0, request.productivity)
        assertEquals(emptyList<Any>(), request.availability)
    }

    @Test
    fun `explicit values still override defaults`() {
        val json = """
            {
                "firstName": "Alice",
                "lastName": "Johnson",
                "middleName": "Marie",
                "dateOfBirth": "15/05/1990",
                "normalPayRate": 20.0,
                "overtimePayRate": 30.0,
                "productivity": 250.0,
                "groups": ["kitchen", "front-of-house"]
            }
        """.trimIndent()

        val request = gson.fromJson(json, CreateEmployeeRequest::class.java)

        assertEquals("Marie", request.middleName)
        assertEquals(250.0, request.productivity)
        assertEquals(setOf("kitchen", "front-of-house"), request.groups)
    }

    @Test
    fun `explicit null for a non-nullable field falls back to the default instead of crashing`() {
        val json = """
            {
                "firstName": "Carol",
                "lastName": "Davis",
                "middleName": null,
                "dateOfBirth": "10/03/1992",
                "normalPayRate": 18.0,
                "overtimePayRate": 27.0
            }
        """.trimIndent()

        val request = gson.fromJson(json, CreateEmployeeRequest::class.java)

        assertEquals("", request.middleName)
    }

    @Test
    fun `deserializes GenerateScheduleRequest without businessId in the body`() {
        // The exact payload shape the frontend sends to POST
        // /api/businesses/{businessId}/schedules/generate - businessId only ever
        // appears in the URL path, never in the body. GenerateScheduleRequest.input
        // is a ScheduleInputPayload (no businessId field) for exactly this reason;
        // this crashed with "No argument provided for a required parameter:
        // businessId" back when it deserialized straight into ScheduleInput, which
        // requires one.
        val json = """
            {
                "input": {
                    "employeeIds": [],
                    "laborCostBudget": 5000.0,
                    "schedulePeriod": {
                        "startDate": "2026-08-10",
                        "endDate": "2026-08-16",
                        "operatingHours": {}
                    }
                },
                "name": "Test Week",
                "generatedBy": "test"
            }
        """.trimIndent()

        val request = gson.fromJson(json, GenerateScheduleRequest::class.java)

        assertEquals("Test Week", request.name)
        assertEquals(0, request.input.employeeIds.size)

        // ScheduleController combines this payload with the path's businessId to
        // build the real ScheduleInput, which still requires businessId.
        val businessId = UUID.randomUUID()
        val scheduleInput = request.input.toScheduleInput(businessId)
        assertEquals(businessId, scheduleInput.businessId)
    }

    @Test
    fun `serializes a null field of a class carrying Kotlin defaults`() {
        // KotlinDefaultsTypeAdapterFactory intercepts any class with an optional
        // constructor parameter. Its write() override once declared the value as
        // non-null, narrowing what Gson's contract allows, so serializing an object
        // whose nullable field happened to be null threw "Parameter specified as
        // non-null is null" - naming the adapter rather than the absent field, and
        // surfacing as a 400 on a request that was perfectly valid.
        val response = SpecialEventResponse(
            id = "e1", businessId = "b1", name = "NYE Party",
            date = "2026-12-31", startTime = "21:00", endTime = "02:00",
            endDate = "2027-01-01", crossesMidnight = true,
            notes = null,
            employeeIds = emptyList(),
            expectedRevenue = null,
            objective = "BALANCED",
            requirements = listOf(
                // Defaulted params, so this nested type is intercepted too - and both
                // of its pay fields are null whenever a group is paid its usual rate.
                EventStaffingRequirementDto(groupName = "Bartender", count = 2)
            ),
            ruleOverrides = null,
            scheduleId = null,
            createdAt = "2026-09-05T00:00:00Z",
            createdBy = "1"
        )

        val json = gson.toJson(response)
        assertTrue(json.contains("\"name\":\"NYE Party\""), json)
        assertTrue(json.contains("\"ruleOverrides\":null"), json)
    }
}
