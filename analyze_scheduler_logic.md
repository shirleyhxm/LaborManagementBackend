# ShiftScheduler Logic Analysis

## Current Algorithm Flow

The ShiftScheduler uses an hour-by-hour greedy algorithm:

1. **Sort employees** by optimization objective
2. **For each hour** in the operating period:
   - Calculate uncovered sales demand for that hour
   - **If uncovered sales > 0**: Try to assign employees in sorted order
   - **Stop assigning** when: `hourlyCoverage[interval] <= 0`
3. Employee assignment updates coverage for ALL hours their shift covers

## Potential Issue with MAXIMIZE_SALES

### Scenario:
- **Employee A**: productivity = 600, rate = $50/hr
- **Employee B**: productivity = 150, rate = $10/hr
- **Budget**: $2000
- **Expected sales per hour**: $800/hr for 10 hours = $8000 total

### MAXIMIZE_SALES behavior (prioritizes Employee A):
1. Hour 1: Assign Employee A (productivity 600 > expected 800)
   - Coverage for hour 1: 800 - 600 = 200 remaining
2. Hour 1: Assign Employee B (productivity 150)
   - Coverage for hour 1: 200 - 150 = 50 remaining
3. Hour 1: Try to assign more, but might stop here
4. Continue for remaining hours...
5. Budget runs out after ~20 hours (mix of A and B)
6. **Total sales**: Maybe $6000

### MINIMIZE_LABOR_COST behavior (prioritizes Employee B):
1. Hour 1: Assign Employee B (productivity 150)
   - Coverage for hour 1: 800 - 150 = 650 remaining
2. Hour 1: Assign more Employee B shifts...
   - Multiple low-cost employees can be scheduled
3. Budget allows ~200 hours of Employee B
4. **Total sales**: Could be $30,000+ (way more!)

## Root Cause

The issue is the **coverage-based stopping condition** (line 291):
```kotlin
if (currentUncovered <= 0) break  // Hour is covered, move to next hour
```

This makes the algorithm stop assigning employees once an hour's demand is "covered", even though:
- For MAXIMIZE_SALES: We should keep assigning high-productivity employees even if they exceed demand
- For MINIMIZE_LABOR_COST: The current behavior is correct (cover minimum demand)

## Solution

**For MAXIMIZE_SALES objective specifically**:
1. Don't stop when `currentUncovered <= 0`
2. Continue assigning employees based on budget and contract limits
3. Prioritize filling the schedule with high-productivity employees

**For other objectives**: Keep current coverage-based logic
