package org.labormanagement.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.labormanagement.config.EnvironmentConfig
import org.slf4j.LoggerFactory

/**
 * Database connection factory using HikariCP connection pooling.
 * Manages PostgreSQL database initialization and connection management.
 */
object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    /**
     * Initialize the database connection pool and create tables.
     *
     * @param jdbcUrl Database JDBC URL (e.g., jdbc:postgresql://localhost:5432/labormanagement)
     * @param user Database username
     * @param password Database password
     * @param driver JDBC driver class name
     * @param maxPoolSize Maximum connection pool size
     */
    fun init(
        jdbcUrl: String = EnvironmentConfig.get("DATABASE_URL", "jdbc:postgresql://localhost:5432/labormanagement"),
        user: String = EnvironmentConfig.get("DATABASE_USER", "shirleyhe"),
        password: String = EnvironmentConfig.get("DATABASE_PASSWORD", ""),
        driver: String = "org.postgresql.Driver",
        maxPoolSize: Int = 10
    ) {
        logger.info("Initializing database connection for environment: ${EnvironmentConfig.getEnvironment()}")
        logger.info("Connecting to: $jdbcUrl")

        try {
            val dataSource = createHikariDataSource(jdbcUrl, user, password, driver, maxPoolSize)
            Database.connect(dataSource)

            // Create tables if they don't exist
            transaction {
                createTables()
            }

            logger.info("Database initialized successfully")
        } catch (e: Exception) {
            logger.error("Failed to initialize database", e)
            throw e
        }
    }

    /**
     * Create HikariCP data source with connection pooling.
     */
    private fun createHikariDataSource(
        jdbcUrl: String,
        user: String,
        password: String,
        driver: String,
        maxPoolSize: Int
    ): HikariDataSource {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            this.driverClassName = driver
            this.maximumPoolSize = maxPoolSize
            this.minimumIdle = 2
            this.connectionTimeout = 30000 // 30 seconds
            this.idleTimeout = 600000 // 10 minutes
            this.maxLifetime = 1800000 // 30 minutes
            this.isAutoCommit = true
            this.transactionIsolation = "TRANSACTION_READ_COMMITTED"
            this.poolName = "LaborManagementHikariPool"

            // PostgreSQL-specific optimizations
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }

        return HikariDataSource(config)
    }

    /**
     * Create all database tables.
     * Will be called during initialization.
     */
    private fun createTables() {
        logger.info("Creating database tables...")

        SchemaUtils.create(
            org.labormanagement.database.Users,
            org.labormanagement.database.Businesses,
            org.labormanagement.database.Employees,
            org.labormanagement.database.Availabilities,
            org.labormanagement.database.EmployeeGroups,
            org.labormanagement.database.Schedules,
            org.labormanagement.database.Shifts,
            org.labormanagement.database.SalesForecasts,
            org.labormanagement.database.Timeoffs,
            org.labormanagement.database.Attendances,
            org.labormanagement.database.Sales,
            org.labormanagement.database.PasswordResets
        )

        logger.info("Database tables created successfully")
    }

    /**
     * Drop all tables and recreate them (DANGER: This will delete all data!)
     * Use only for development/testing purposes.
     */
    fun resetDatabase() {
        logger.warn("RESETTING DATABASE - ALL DATA WILL BE LOST!")

        transaction {
            // Drop tables in reverse order to handle foreign key constraints
            SchemaUtils.drop(
                org.labormanagement.database.PasswordResets,
                org.labormanagement.database.Sales,
                org.labormanagement.database.Attendances,
                org.labormanagement.database.Timeoffs,
                org.labormanagement.database.SalesForecasts,
                org.labormanagement.database.Shifts,
                org.labormanagement.database.Schedules,
                org.labormanagement.database.EmployeeGroups,
                org.labormanagement.database.Availabilities,
                org.labormanagement.database.Employees,
                org.labormanagement.database.Businesses,
                org.labormanagement.database.Users
            )

            // Recreate tables
            createTables()
        }

        logger.info("Database reset complete")
    }

    /**
     * Close the database connection pool (for graceful shutdown).
     */
    fun close() {
        logger.info("Closing database connection pool")
        // HikariCP will automatically close when the application shuts down
    }
}
