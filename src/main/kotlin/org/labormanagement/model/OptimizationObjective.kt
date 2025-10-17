package org.labormanagement.model

/**
 * Defines the optimization objective for shift scheduling.
 * Different objectives will prioritize different aspects of the schedule.
 */
enum class OptimizationObjective {
    /**
     * Minimize overall labor cost by prioritizing cheaper employees.
     * Best for: Tight budgets, low-margin periods
     */
    MINIMIZE_LABOR_COST,

    /**
     * Maximize total sales by prioritizing most productive employees.
     * Best for: High-demand periods, maximizing revenue
     */
    MAXIMIZE_SALES,

    /**
     * Balance cost and productivity by optimizing for best productivity-to-cost ratio.
     * Best for: General use, balanced approach
     */
    BALANCED
}
