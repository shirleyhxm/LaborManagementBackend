package org.labormanagement.config

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import org.slf4j.LoggerFactory

/**
 * Environment configuration loader.
 * Automatically loads the appropriate .env file based on APP_ENV:
 * - local: .env.local (default)
 * - production: .env.production
 *
 * Falls back to system environment variables if .env file doesn't exist.
 */
object EnvironmentConfig {
    private val logger = LoggerFactory.getLogger(EnvironmentConfig::class.java)

    private val dotenv: Dotenv? by lazy {
        try {
            // Get environment (local or production)
            val appEnv = System.getenv("APP_ENV") ?: "local"
            logger.info("Loading environment configuration for: $appEnv")

            // Try to load environment-specific .env file
            val envFile = when (appEnv) {
                "production", "prod" -> ".env.production"
                "local", "dev", "development" -> ".env.local"
                else -> ".env"
            }

            dotenv {
                directory = "."
                filename = envFile
                ignoreIfMissing = true  // Don't fail if .env file doesn't exist
            }
        } catch (e: Exception) {
            logger.warn("Could not load .env file, using system environment variables: ${e.message}")
            null
        }
    }

    /**
     * Get environment variable value.
     * Priority: 1) System env vars, 2) .env file, 3) default value
     */
    fun get(key: String, defaultValue: String = ""): String {
        // First check system environment variables (highest priority)
        System.getenv(key)?.let { return it }

        // Then check .env file
        dotenv?.get(key)?.let { return it }

        // Finally use default
        return defaultValue
    }

    /**
     * Check if we're in production environment
     */
    fun isProduction(): Boolean {
        val appEnv = System.getenv("APP_ENV") ?: "local"
        return appEnv == "production" || appEnv == "prod"
    }

    /**
     * Get current environment name
     */
    fun getEnvironment(): String {
        return System.getenv("APP_ENV") ?: "local"
    }
}
