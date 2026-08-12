plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "org.labormanagement"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server dependencies
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-gson:2.3.12")
    implementation("io.ktor:ktor-server-cors:2.3.12")
    implementation("io.ktor:ktor-server-call-logging:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")

    // JWT authentication
    implementation("io.ktor:ktor-server-auth:2.3.12")
    implementation("io.ktor:ktor-server-auth-jwt:2.3.12")

    // Password hashing
    implementation("org.mindrot:jbcrypt:0.4")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // OR-Tools for optimization
    implementation("com.google.ortools:ortools-java:9.10.4067")

    // PostgreSQL database dependencies
    implementation("org.postgresql:postgresql:42.7.2")
    // H2 in-memory database for local development
    implementation("com.h2database:h2:2.2.224")
    implementation("org.jetbrains.exposed:exposed-core:0.48.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.48.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.48.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.48.0")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Environment configuration
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
}

application {
    mainClass.set("org.labormanagement.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true

        // Show summary after test execution
        showStandardStreams = false
    }

    // Show test results summary
    afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        if (desc.parent == null) { // Only print for the overall test suite
            println("\nTest Results: ${result.resultType}")
            println("  Tests: ${result.testCount}")
            println("  Passed: ${result.successfulTestCount}")
            println("  Failed: ${result.failedTestCount}")
            println("  Skipped: ${result.skippedTestCount}")
        }
    }))
}