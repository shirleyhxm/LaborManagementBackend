package org.labormanagement.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.labormanagement.dto.CreateEmployeeRequest

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
}
