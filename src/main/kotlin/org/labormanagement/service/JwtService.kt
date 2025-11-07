package org.labormanagement.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import org.labormanagement.model.User
import java.util.*

class JwtService {
    private val secret = System.getenv("JWT_SECRET") ?: "labor-management-secret-key-change-this-in-production-minimum-256-bits"
    private val issuer = "labor-management-app"
    private val audience = "labor-management-users"
    private val validityInMs = 86400000L // 24 hours

    private val algorithm = Algorithm.HMAC256(secret)

    /**
     * Generate a JWT token for a user
     */
    fun generateToken(user: User): String {
        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", user.id)
            .withClaim("username", user.username)
            .withClaim("role", user.role.name)
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + validityInMs))
            .sign(algorithm)
    }

    /**
     * Verify and decode a JWT token
     * Returns null if token is invalid or expired
     */
    fun verifyToken(token: String): DecodedJWT? {
        return try {
            val verifier = JWT.require(algorithm)
                .withAudience(audience)
                .withIssuer(issuer)
                .build()
            verifier.verify(token)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract user ID from token
     */
    fun getUserIdFromToken(token: String): String? {
        return verifyToken(token)?.getClaim("userId")?.asString()
    }

    /**
     * Extract username from token
     */
    fun getUsernameFromToken(token: String): String? {
        return verifyToken(token)?.getClaim("username")?.asString()
    }

    /**
     * Extract role from token
     */
    fun getRoleFromToken(token: String): String? {
        return verifyToken(token)?.getClaim("role")?.asString()
    }
}