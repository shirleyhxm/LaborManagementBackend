# Performance Profiling Guide

This document explains how to profile the shift scheduling algorithm to identify performance bottlenecks.

## Overview

The `PerformanceProfiler` class provides a lightweight profiling solution that measures execution time of different operations within the scheduling algorithm. It tracks:

- Individual operation times
- Cumulative statistics (count, total, average, min, max)
- Sorted results by total time spent

## Running Performance Tests

### Run All Performance Tests

```bash
./gradlew test --tests "org.labormanagement.service.PerformanceProfilerTest"
```

### Run Specific Workload Tests

```bash
# Small workload (2 employees, 2 days)
./gradlew test --tests "*small*"

# Medium workload (10 employees, 5 days)
./gradlew test --tests "*medium*"

# Large workload (50 employees, 7 days)
./gradlew test --tests "*large*"

# Compare optimization objectives
./gradlew test --tests "*compare*"
```

## Profiled Operations

The scheduler measures the following operations:

### Top-Level Operations
- `generateSchedule` - Total scheduling time
- `generateSchedule.allDays` - Time to process all days
- `generateSchedule.singleDay` - Time per individual day (called once per day)
- `generateSchedule.mergeShifts` - Time to merge consecutive shifts
- `generateSchedule.mergeRequirements` - Time to merge staffing requirements
- `generateSchedule.validate` - Time for constraint validation
- `generateSchedule.calculateMetrics` - Time to calculate metrics

### Per-Day Operations
- `generateShiftsForDay.sortEmployees` - Initial employee sorting by optimization objective
- `generateShiftsForDay.generateIntervals` - Creating hourly evaluation intervals
- `generateShiftsForDay.initCoverage` - Initializing sales coverage tracking
- `generateShiftsForDay.hourByHourScheduling` - Main hour-by-hour assignment loop
- `generateShiftsForDay.generateRequirements` - Generating staffing requirements

## Example Output

```
=== Performance Profile Report ===
Operation                                    Count    Total (ms)     Avg (ms)     Min (ms)     Max (ms)
----------------------------------------------------------------------------------------------------
generateSchedule                                 1       45.234       45.234       45.234       45.234
generateSchedule.allDays                         1       42.156       42.156       42.156       42.156
generateSchedule.singleDay                       5        8.234        8.234        7.892        8.876
generateShiftsForDay.hourByHourScheduling        5       30.123        6.025        5.234        7.456
generateShiftsForDay.sortEmployees               5        2.345        0.469        0.423        0.512
generateShiftsForDay.generateIntervals           5        0.567        0.113        0.098        0.145
generateShiftsForDay.initCoverage                5        1.234        0.247        0.212        0.289
generateShiftsForDay.generateRequirements        5        3.456        0.691        0.645        0.756
generateSchedule.mergeShifts                     1        1.234        1.234        1.234        1.234
generateSchedule.mergeRequirements               1        0.567        0.567        0.567        0.567
generateSchedule.validate                        1        0.789        0.789        0.789        0.789
generateSchedule.calculateMetrics                1        0.456        0.456        0.456        0.456
----------------------------------------------------------------------------------------------------
Total measured time: 45.234 ms
====================================================================================================
```

## Interpreting Results

### Key Metrics

1. **Total Time**: Absolute time spent in operation (sum of all calls)
   - High total = major contributor to overall runtime
   - Use this to identify biggest bottlenecks

2. **Count**: Number of times operation was called
   - High count = operation called frequently
   - Consider optimization if called unexpectedly often

3. **Average Time**: Mean duration per call
   - High average = individual calls are slow
   - Focus optimization on algorithm efficiency

4. **Min/Max Time**: Range of execution times
   - Large range = inconsistent performance
   - May indicate different code paths or input-dependent behavior

### Common Bottlenecks

#### Hour-by-Hour Scheduling
- **Symptom**: `hourByHourScheduling` has highest total time
- **Cause**: Nested loops (hours × employees)
- **Solutions**:
  - Pre-filter employees by availability
  - Early termination when demand is met
  - Cache employee stats calculations

#### Employee Sorting
- **Symptom**: `sortEmployees` called many times or takes significant time
- **Cause**: Sorting on every iteration
- **Solutions**:
  - Cache sorted employee lists
  - Use partial sorting for top N employees
  - Reduce re-sorting frequency

#### Shift Merging
- **Symptom**: `mergeShifts` or `mergeRequirements` takes significant time
- **Cause**: Multiple iterations over shift collections
- **Solutions**:
  - Maintain sorted shifts during creation
  - Merge during creation rather than post-processing

## Performance Expectations

### Typical Performance (Intel i7, 16GB RAM)

| Workload | Employees | Days | Hours/Day | Total Time | Per Day |
|----------|-----------|------|-----------|------------|---------|
| Small    | 2         | 2    | 12        | ~5 ms      | ~2.5 ms |
| Medium   | 10        | 5    | 12        | ~50 ms     | ~10 ms  |
| Large    | 50        | 7    | 16        | ~500 ms    | ~70 ms  |

### Scaling Characteristics

- **Linear with days**: Each day adds ~same amount of time
- **Quadratic with hours**: Doubling operating hours ≈ 4x time
- **Quadratic with employees**: Doubling employees ≈ 4x time (hour-by-hour assignment)

## Using the Profiler in Your Code

### Basic Usage

```kotlin
// Enable profiling globally
PerformanceProfiler.enabled = true

// Profile a specific block
PerformanceProfiler.profile {
    // Your code here
    scheduler.generateSchedule(input)
}
// Automatically prints report after block completes
```

### Annotation-Based Profiling (Recommended)

Use the `@Profile` annotation for clean, declarative profiling:

```kotlin
@Profile  // Automatically names operation as "ClassName.functionName"
fun generateSchedule(input: SchedulingInput): SchedulingOutput = profile {
    // Function body - auto-profiled as "ShiftScheduler.generateSchedule"
    val result = doWork(input)

    // Nested profiling with custom names
    val processed = profile("generateSchedule.processResults") {
        processResults(result)
    }

    return processed
}
```

### Profile Specific Operations

```kotlin
fun myFunction() {
    // With custom name
    profile("myFunction.step1") {
        // Step 1 code
    }

    // Auto-derived name from context
    profile {
        // Step 2 code - profiled as "ClassName.myFunction"
    }
}
```

### Manual Control

```kotlin
val profiler = PerformanceProfiler()

profiler.start("operation1")
// Do work
profiler.stop("operation1")

profiler.start("operation2")
// Do more work
profiler.stop("operation2")

profiler.printReport()
```

## Optimization Recommendations

Based on profiling results:

### 1. If `hourByHourScheduling` is the bottleneck:
- ✓ Pre-filter employees by availability for each hour
- ✓ Track which employees can't work anymore (exhausted contract hours)
- ✓ Stop iterating when hour demand is fully covered

### 2. If `sortEmployees` takes significant time:
- ✓ Cache sorted lists when employee stats don't change
- ✓ Use stable sort to maintain relative ordering
- ✓ Consider bucketing employees by similar stats

### 3. If `mergeShifts` is slow:
- ✓ Keep shifts sorted by employee+day during creation
- ✓ Merge incrementally as shifts are created
- ✓ Use LinkedList for O(1) insertions

### 4. For overall performance:
- ✓ Add early termination when budget is exhausted
- ✓ Skip hours with zero demand
- ✓ Cache employee productivity/payRate calculations

## Parallel Processing Considerations

Currently, the scheduler runs **sequentially** because:

1. **Cross-day dependencies**: `weeklyHours` map is shared and mutated across days
2. **Fairness optimization**: Requires knowing cumulative hours from previous days
3. **Budget tracking**: Sequential budget consumption

**Potential for parallelization:**
- ✗ Days within same schedule (dependencies exist)
- ✓ Multiple independent schedules (different weeks/locations)
- ✓ Constraint validation (read-only after scheduling)
- ✓ Metrics calculation (read-only after scheduling)

For typical workloads (5-7 days, 10-50 employees), sequential processing is fast enough (< 100ms). Parallelization overhead would likely exceed benefits.

## Benchmarking

To establish performance baselines:

```bash
# Run all performance tests 5 times and average results
for i in {1..5}; do
    ./gradlew test --tests "org.labormanagement.service.PerformanceProfilerTest"
done
```

Compare results after making optimizations to measure improvement.
