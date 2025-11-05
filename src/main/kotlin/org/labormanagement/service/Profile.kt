package org.labormanagement.service

/**
 * Annotation to mark functions for performance profiling.
 * When profiling is enabled, annotated functions will have their execution time measured.
 *
 * Usage with the `profile` extension:
 *
 * ```kotlin
 * @Profile
 * fun generateSchedule(input: ScheduleInput) = profile {
 *     // Function body - automatically profiled as "ShiftScheduler.generateSchedule"
 *     // ... implementation ...
 * }
 *
 * @Profile("CustomName")
 * fun anotherFunction() = profile("CustomName") {
 *     // Function body - profiled as "CustomName"
 *     // ... implementation ...
 * }
 * ```
 *
 * The annotation serves as:
 * 1. Documentation - clearly marks profiled functions
 * 2. IDE support - easy to find all profiled code
 * 3. Future tooling - can be used by static analysis tools
 *
 * @param name Optional custom name for the profiling operation. If empty, auto-derives from context.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class Profile(val name: String = "")