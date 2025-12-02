# Schedule Optimization Module

This module provides mathematical optimization for employee scheduling using Google OR-Tools CP-SAT solver.

## Architecture

The optimization module has been refactored into clean, reusable components:

### Core Components

1. **ScheduleOptimizer** (`ScheduleOptimizer.kt`)
   - Encapsulates the CP-SAT mathematical model
   - Handles constraint definition and objective setting
   - Supports multiple optimization objectives (minimize cost, maximize sales, balanced, fairness)

2. **OptimizationConverter** (`OptimizationConverter.kt`)
   - Transforms domain models (Employee, SalesForecast) into optimization inputs
   - Converts optimization results back to domain models (Shifts)
   - Handles time slot generation and availability mapping

3. **Data Classes**
   - `OptimizationInput`: Input parameters for the optimizer
   - `OptimizationResult`: Output from the optimization solver
   - `TimeSlot`: Represents a schedulable time period
   - `EmployeeAssignment`: Maps employees to time slots

## Usage

### Basic Example

```kotlin
// 1. Create or fetch domain models
val employees: List<Employee> = /* ... */
val salesForecast: SalesForecast = /* ... */

// 2. Build optimization input
val input = OptimizationConverter.buildOptimizationInput(
    employees = employees,
    salesForecast = salesForecast,
    scheduleDays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
    operatingHoursMap = mapOf(
        DayOfWeek.MONDAY to Pair(LocalTime.of(9, 0), LocalTime.of(17, 0))
    ),
    slotDurationHours = 1.0,
    coverageFraction = 0.8,
    objective = OptimizationObjective.MINIMIZE_LABOR_COST
)

// 3. Run optimization
val optimizer = ScheduleOptimizer()
val result = optimizer.optimize(input)

// 4. Convert to shifts
if (result != null) {
    val shifts = OptimizationConverter.convertToShifts(result, input)
    // Use the shifts...
}
```

## Optimization Model

### Decision Variables
- `x[e][t]`: Binary variable = 1 if employee `e` works time slot `t`
- `totalHours[e]`: Total hours worked by employee `e`
- `regular[e]`: Regular (non-overtime) hours for employee `e`
- `overtime[e]`: Overtime hours for employee `e`

### Constraints

1. **Availability**: Employees can only work during their available times
2. **Hour Accounting**: `totalHours = regular + overtime`
3. **Overtime Threshold**: `regular ≤ overtimeThreshold`
4. **Sales Coverage**: Sum of (productivity × hours) ≥ coverageFraction × projectedSales

### Objectives

- **MINIMIZE_LABOR_COST**: Minimize total wage costs (regular + overtime)
- **MAXIMIZE_SALES**: Maximize expected sales (prioritize productive employees)
- **BALANCED**: Balance cost and productivity
- **MAXIMIZE_FAIRNESS**: Distribute hours fairly (work in progress)

## Integration with Existing Code

This optimization module can be integrated into the existing `ShiftScheduler` service to provide mathematically optimal schedules instead of the current greedy heuristic approach. The `ScheduleOptimizer` ensures global optimization across all constraints.

### Benefits over Current Greedy Approach

1. **Optimality**: Finds the best solution according to the objective
2. **Constraint Satisfaction**: Guarantees all hard constraints are met
3. **Flexibility**: Easy to add new constraints or objectives
4. **Verifiability**: Solution quality can be proven mathematically

## Testing

Run the optimizer tests:
```bash
./gradlew test --tests "org.labormanagement.optimization.ScheduleOptimizerTest"
```

Run the example:
```bash
./gradlew run
```

## Performance

The CP-SAT solver is highly efficient for scheduling problems:
- Typical solve time: < 5 seconds for weekly schedules with 10-20 employees
- Configurable time limit via `maxSolveTimeSeconds`
- Returns best solution found within time limit if optimal not reached

## Future Enhancements

- [ ] Add break time constraints
- [ ] Support shift length preferences
- [ ] Implement fairness objective properly (min-max hours variance)
- [ ] Add employee skill-based constraints
- [ ] Support multi-week optimization
- [ ] Cache and reuse solver models for similar inputs
