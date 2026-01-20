# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Kotlin backend API for employee shift scheduling with mathematical optimization. The system generates optimal work schedules based on labor budgets, sales forecasts, employee availability, and various constraints.

**Tech Stack:**
- Kotlin 2.2.20
- Ktor 2.3.12 (web framework)
- Google OR-Tools 9.10 (constraint programming solver)
- Gradle (build tool)
- Gson (JSON serialization)

## Common Commands

### Build & Run
```bash
# Build the project
./gradlew build

# Run the application (starts server on http://localhost:8080)
./gradlew run

# Clean build artifacts
./gradlew clean
```

### Testing
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "org.labormanagement.service.ShiftSchedulerTest"

# Run performance profiling tests
./gradlew test --tests "org.labormanagement.service.PerformanceProfilerTest"

# Run optimization tests
./gradlew test --tests "org.labormanagement.optimization.ScheduleOptimizerTest"
```

### Quick Testing Workflow
```bash
# 1. Start the server
./gradlew run

# 2. Create sample employees (in another terminal)
curl -X POST http://localhost:8080/api/test/create-sample-employees

# 3. Get employee IDs
curl http://localhost:8080/api/test/employee-ids

# 4. Generate a schedule (see TESTING.md for full request body)
curl -X POST http://localhost:8080/api/scheduling/generate \
  -H "Content-Type: application/json" \
  -d @schedule-request.json
```

## Architecture

### Core Package Structure
```
src/main/kotlin/org/labormanagement/
├── Application.kt          # Main entry point, Ktor server setup
├── controller/             # HTTP API endpoints
├── dto/                    # Data transfer objects
├── model/                  # Domain models
├── repository/             # In-memory data storage
├── service/                # Business logic
└── optimization/           # OR-Tools optimization engine
```

### Key Controllers
- `ScheduleController.kt` - Schedule generation and management
- `EmployeeController.kt` - Employee CRUD operations
- `ConstraintsController.kt` - Constraint configuration (budget, hours, compliance)
- `TestDataController.kt` - Test data generation endpoints
- `AuthController.kt` - User authentication

### Domain Models (`model/`)
- `Employee.kt` - Employee with contract and availability
- `Schedule.kt` - Shift assignments and scheduling metadata
- `Constraints.kt` - Budget, hours, compliance rules
- `SalesForecast.kt` - Sales projections for scheduling
- `Timeoff.kt` - Time-off requests and approvals

### Services (`service/`)

**ShiftScheduler.kt** - Primary scheduling engine (greedy/heuristic approach)
- Hour-by-hour assignment algorithm
- Supports 4 optimization objectives: MINIMIZE_LABOR_COST, MAXIMIZE_SALES, BALANCED, MAXIMIZE_FAIRNESS
- Merges consecutive shifts automatically
- Enforces budget and availability constraints

**ConstraintValidator.kt** - Validates schedules against constraints
- Checks budget limits, availability conflicts, contract hours
- Detects shift overlaps and understaffing
- Returns detailed violation reports

**ShiftModificationService.kt** - Post-scheduling modifications
- Shift swaps, cancellations, extensions
- Re-validation after changes

**ConstraintsService.kt** - Manages constraint configurations
- CRUD operations for budget, hours, compliance rules
- Supports custom compliance rules and scheduling priorities

### Optimization Module (`optimization/`)

**ScheduleOptimizer.kt** - Mathematical optimization using Google OR-Tools CP-SAT
- Global optimization vs. greedy heuristics
- Constraint programming model with decision variables
- Guarantees constraint satisfaction
- Multiple objectives: cost minimization, sales maximization, balanced, fairness
- Integrates with ConstraintsService for centralized constraint management
- Supports variable-duration time slots (weighted by slot.durationHours)
- Enforces shift length constraints on consecutive shifts (handles overnight shifts)

**OptimizationConverter.kt** - Transforms between domain models and optimization inputs
- Converts Employee/SalesForecast → OptimizationInput
- Converts OptimizationResult → Shifts
- Handles time slot generation with configurable duration
- Enforces minShiftLength by using it as minimum slot duration
- Automatically groups consecutive slots into shifts

**Usage Note:** The optimizer is now the default scheduling approach (configurable via `SchedulingApproach` enum). It provides mathematically optimal schedules with guaranteed constraint satisfaction.

## Scheduling Architecture

### Two Scheduling Approaches

**1. Greedy Hour-by-Hour Scheduler** (`ShiftScheduler.kt`)
- Current default implementation
- Fast execution (< 100ms for typical workloads)
- Hour-by-hour evaluation with employee sorting by objective
- Merges consecutive shifts after assignment
- Good for interactive schedule generation

**2. CP-SAT Optimizer** (`ScheduleOptimizer.kt`)
- Mathematical optimization using OR-Tools CP-SAT solver
- Finds globally optimal solutions
- Slower but more accurate (< 5 seconds typical, configurable via maxSolveTimeSeconds)
- Better for complex constraint scenarios with multiple interacting rules
- **Now the default approach** (set via `SchedulingApproach.OPTIMIZER` in ShiftScheduler constructor)
- Automatically falls back to GREEDY approach if no feasible solution found

### Optimization Objectives

Both schedulers support these objectives:
- **MINIMIZE_LABOR_COST** - Sort by lowest pay rate (budget-constrained scenarios)
- **MAXIMIZE_SALES** - Sort by highest productivity (revenue-focused periods)
- **BALANCED** - Sort by productivity/cost ratio (general use)
- **MAXIMIZE_FAIRNESS** - Sort by current hours worked (equitable distribution)

### Key Scheduling Concepts

**Hour-by-Hour Assignment:**
- Each hour evaluated independently based on sales forecast
- Employees sorted by optimization objective
- Staffing calculated using actual team productivity: `employees_needed = ceil(sales / avg_productivity)`
- Consecutive hours naturally form longer shifts

**Shift Merging:**
- Post-processing step merges consecutive hour-long assignments
- Only merges if employee, day, and overtime status match
- Results in realistic multi-hour shifts

**Constraint Enforcement:**
- Budget tracking throughout assignment
- Availability checked per hour
- Contract hours (daily/weekly) enforced
- Overtime rates applied when threshold exceeded

## Performance Profiling

The codebase includes a performance profiling system (`PerformanceProfiler.kt`):

```kotlin
// Enable profiling globally
PerformanceProfiler.enabled = true

// Profile a code block
profile("operationName") {
    // Code to measure
}

// Or use @Profile annotation
@Profile
fun myFunction() {
    // Auto-profiled as "ClassName.myFunction"
}
```

**Common Bottlenecks:**
- `hourByHourScheduling` - Nested loops (hours × employees)
- `sortEmployees` - Frequent re-sorting
- `mergeShifts` - Post-processing iteration

See PERFORMANCE.md for detailed profiling guide.

## Constraints System

The system supports comprehensive constraint management through **ConstraintsService** (see CONSTRAINTS_API.md):

**Constraint Types:**
1. **Budget Constraints** - Weekly/monthly labor cost limits
2. **Working Hours Rules** - Max/min hours per week, shift length limits, overtime limits, rest periods
3. **Employee Contracted Hours** - Min/contracted/max hours per employee
4. **Compliance Rules** - FLSA overtime, meal breaks, minor labor laws
5. **Custom Compliance Rules** - Organization-specific rules
6. **Scheduling Priorities** - Configurable priority ordering
7. **Fairness Settings** - Weekend rotation, shift balancing

**Key Constraint Enforcement:**
- **minShiftLength**: Enforced at time slot generation level - each slot is at least minShiftLength hours, ensuring all consecutive shifts meet the minimum
- **maxShiftLength**: Enforced on consecutive shift sequences - prevents any continuous work period from exceeding the limit (handles overnight shifts)
- **maxHoursPerWeek**: Total weekly hours constraint (weighted by slot duration)
- **maxOvertimeHours**: Overtime hours beyond threshold constraint
- All constraints fetched from ConstraintsService at runtime, no hardcoded parameters

**Accessing Constraints:**
```bash
# Get all constraints
GET /api/v1/constraints

# Update specific constraint type
PUT /api/v1/constraints/budget
PUT /api/v1/constraints/working-hours
PUT /api/v1/constraints/compliance
```

## Important Implementation Details

### Time Representation
- Operating hours stored as "HH:mm" strings (e.g., "09:00")
- Days of week as enums (MONDAY, TUESDAY, etc.)
- Shift durations in decimal hours (e.g., 4.0, 8.5)

### Data Storage
- **In-memory repositories** - No persistent database
- Data resets on server restart
- Use `TestDataController` to quickly recreate sample data

### Sales Forecasting
- Sales forecasts are hour-specific: `Map<DayOfWeek, Map<String, Double>>`
- Example: `{"MONDAY": {"09:00": 800.0, "12:00": 1500.0}}`
- Used to calculate staffing needs per hour

### Overtime Calculation
- Tracks weekly hours per employee
- Applies overtime rate when `weeklyHours > overtimeThreshold`
- Overtime tracked per shift in `isOvertime` field

### Employee Groups
- Employees can belong to groups for collective scheduling
- Groups have associated constraints and rules
- Used for role-based or department-based scheduling

## Common Development Patterns

### Adding a New Constraint

1. Define model in `Constraints.kt`
2. Add repository method in `ConstraintsService.kt`
3. Create DTO in `ConstraintsDto.kt`
4. Add controller endpoint in `ConstraintsController.kt`
5. Update validation in `ConstraintValidator.kt`
6. Integrate into `ShiftScheduler.kt` if needed

### Adding a New Optimization Objective

1. Add enum value to `OptimizationObjective.kt`
2. Implement sorting logic in `ShiftScheduler.kt::sortEmployeesByObjective()`
3. Update API documentation
4. Add test cases in `ShiftSchedulerTest.kt`

### Modifying Scheduling Logic

**Important:** When changing `ShiftScheduler.kt` or `ScheduleOptimizer.kt`:
- Always run performance tests to check for regressions
- Validate all constraint types still work
- Test all 4 optimization objectives
- Check edge cases: zero budget, no availability, extreme forecasts
- Remember: time slots can be variable duration (not always 1 hour)
- Shift length constraints apply to **consecutive shifts**, not daily totals
- All constraints should weight by `slot.durationHours` when summing hours

## Testing Strategy

**Unit Tests:**
- Service layer tests for business logic
- Repository tests for data operations
- Scheduler tests for algorithm correctness

**Integration Tests:**
- End-to-end schedule generation with real data
- Constraint validation workflows

**Performance Tests:**
- Measure scheduling time for small/medium/large workloads
- Profile bottlenecks using PerformanceProfiler

## API Versioning

- Constraints API uses `/api/v1/constraints` prefix
- Core scheduling endpoints use `/api` prefix
- Test endpoints use `/api/test` prefix

## Common Pitfalls

1. **Heap space errors** - Fixed in current version with proper loop termination
2. **No shifts generated** - Check operating hours match employee availability
3. **Employee not found errors** - Ensure sample employees created before scheduling
4. **Time format issues** - Use "HH:mm" format (e.g., "09:00" not "9:00")

## References

- **README.md** - API documentation and usage examples
- **TESTING.md** - Quick testing guide with sample requests
- **CONSTRAINTS_API.md** - Comprehensive constraints API documentation
- **PERFORMANCE.md** - Performance profiling and optimization guide
- **optimization/README.md** - OR-Tools optimization module documentation