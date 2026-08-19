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
     * Detect the appropriate JDBC driver based on the JDBC URL.
     */
    private fun detectDriver(jdbcUrl: String): String {
        return when {
            jdbcUrl.startsWith("jdbc:h2:") -> "org.h2.Driver"
            jdbcUrl.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
            else -> throw IllegalArgumentException("Unsupported database type in URL: $jdbcUrl")
        }
    }

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
        jdbcUrl: String = EnvironmentConfig.get("DATABASE_URL", "jdbc:h2:mem:labormanagement;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"),
        user: String = EnvironmentConfig.get("DATABASE_USER", "sa"),
        password: String = EnvironmentConfig.get("DATABASE_PASSWORD", ""),
        driver: String = detectDriver(jdbcUrl),
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

        val allTables = arrayOf(
            org.labormanagement.database.Users,
            org.labormanagement.database.Businesses,
            org.labormanagement.database.Employees,
            org.labormanagement.database.EmployeeInvites,
            org.labormanagement.database.Availabilities,
            org.labormanagement.database.EmployeeGroups,
            org.labormanagement.database.Schedules,
            org.labormanagement.database.Shifts,
            org.labormanagement.database.SwapRequests,
            org.labormanagement.database.SalesForecasts,
            org.labormanagement.database.Timeoffs,
            org.labormanagement.database.Attendances,
            org.labormanagement.database.Sales,
            org.labormanagement.database.PasswordResets,
            org.labormanagement.database.RefreshTokens
        )

        SchemaUtils.create(*allTables)

        // This repo has no migration tool (no Flyway/Liquibase); reconcile any
        // columns/tables added to the Kotlin definitions but missing from an
        // already-existing live database (e.g. a new nullable column on a
        // table that already existed in production). No-op if nothing is missing.
        val alterStatements = SchemaUtils.addMissingColumnsStatements(*allTables)
        alterStatements.forEach { org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(it) }

        logger.info("Database tables created/updated successfully")
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
                org.labormanagement.database.RefreshTokens,
                org.labormanagement.database.PasswordResets,
                org.labormanagement.database.Sales,
                org.labormanagement.database.Attendances,
                org.labormanagement.database.Timeoffs,
                org.labormanagement.database.SalesForecasts,
                org.labormanagement.database.SwapRequests,
                org.labormanagement.database.Shifts,
                org.labormanagement.database.Schedules,
                org.labormanagement.database.EmployeeGroups,
                org.labormanagement.database.Availabilities,
                org.labormanagement.database.EmployeeInvites,
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
