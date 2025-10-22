package org.labormanagement.service

/**
 * Simple performance profiler for measuring execution time of code blocks.
 * Tracks both individual operation times and cumulative statistics.
 */
class PerformanceProfiler {
    private val measurements = mutableMapOf<String, MutableList<Long>>()
    private val startTimes = mutableMapOf<String, Long>()

    /**
     * Start timing a named operation
     */
    fun start(operationName: String) {
        startTimes[operationName] = System.nanoTime()
    }

    /**
     * Stop timing a named operation and record the duration
     */
    fun stop(operationName: String) {
        val endTime = System.nanoTime()
        val startTime = startTimes.remove(operationName) ?: return
        val duration = endTime - startTime
        measurements.getOrPut(operationName) { mutableListOf() }.add(duration)
    }

    /**
     * Execute a block of code and measure its execution time
     */
    inline fun <T> measure(operationName: String, block: () -> T): T {
        start(operationName)
        try {
            return block()
        } finally {
            stop(operationName)
        }
    }

    /**
     * Get statistics for all measured operations
     */
    fun getStats(): Map<String, OperationStats> {
        return measurements.mapValues { (_, durations) ->
            OperationStats(
                count = durations.size,
                totalTimeMs = durations.sum() / 1_000_000.0,
                avgTimeMs = durations.average() / 1_000_000.0,
                minTimeMs = (durations.minOrNull() ?: 0) / 1_000_000.0,
                maxTimeMs = (durations.maxOrNull() ?: 0) / 1_000_000.0
            )
        }
    }

    /**
     * Print a formatted report of all measurements
     */
    fun printReport() {
        println("\n=== Performance Profile Report ===")
        println("%-40s %8s %12s %12s %12s %12s".format(
            "Operation", "Count", "Total (ms)", "Avg (ms)", "Min (ms)", "Max (ms)"
        ))
        println("-".repeat(100))

        val stats = getStats()
        val sortedStats = stats.entries.sortedByDescending { it.value.totalTimeMs }

        for ((name, stat) in sortedStats) {
            println("%-40s %8d %12.3f %12.3f %12.3f %12.3f".format(
                name.take(40),
                stat.count,
                stat.totalTimeMs,
                stat.avgTimeMs,
                stat.minTimeMs,
                stat.maxTimeMs
            ))
        }

        val totalTime = stats.values.sumOf { it.totalTimeMs }
        println("-".repeat(100))
        println("Total measured time: %.3f ms".format(totalTime))
        println("=".repeat(100) + "\n")
    }

    /**
     * Clear all measurements
     */
    fun reset() {
        measurements.clear()
        startTimes.clear()
    }

    data class OperationStats(
        val count: Int,
        val totalTimeMs: Double,
        val avgTimeMs: Double,
        val minTimeMs: Double,
        val maxTimeMs: Double
    )

    companion object {
        @PublishedApi
        internal val globalProfiler = PerformanceProfiler()

        /**
         * Enable global profiling
         */
        var enabled: Boolean = false

        /**
         * Get the global profiler instance
         */
        fun global(): PerformanceProfiler = globalProfiler

        /**
         * Execute a block with profiling enabled
         */
        inline fun <T> withProfiling(block: () -> T): T {
            val wasEnabled = enabled
            enabled = true
            globalProfiler.reset()
            try {
                return block()
            } finally {
                if (!wasEnabled) {
                    enabled = false
                }
            }
        }

        /**
         * Execute and profile a block, then print the report
         */
        inline fun <T> profile(block: () -> T): T {
            return withProfiling {
                val result = block()
                globalProfiler.printReport()
                result
            }
        }
    }
}

/**
 * Extension function to easily profile any code block with explicit name
 */
inline fun <T> profileOperation(operationName: String, block: () -> T): T {
    return if (PerformanceProfiler.enabled) {
        PerformanceProfiler.global().measure(operationName, block)
    } else {
        block()
    }
}

/**
 * Profile a code block with a custom name.
 * Designed to work with @Profile annotation for clean, declarative profiling.
 *
 * Usage:
 * ```kotlin
 * @Profile("myOperation")
 * fun myFunction() = profile("myOperation") {
 *     // function body
 * }
 * ```
 */
inline fun <T> profile(operationName: String, block: () -> T): T {
    return profileOperation(operationName, block)
}

/**
 * Profile a code block with auto-derived name from calling context.
 * Designed to work with @Profile annotation for clean, declarative profiling.
 *
 * Usage:
 * ```kotlin
 * @Profile
 * fun myFunction() = profile {
 *     // function body - automatically named "ClassName.myFunction"
 * }
 * ```
 */
inline fun <T> profile(block: () -> T): T {
    // Get the calling function name from stack trace
    val callerName = Throwable().stackTrace[1].let {
        "${it.className.substringAfterLast('.')}.${it.methodName}"
    }
    return profileOperation(callerName, block)
}
