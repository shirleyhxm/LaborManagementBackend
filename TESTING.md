# Testing Guide - Labor Management API

## Quick Start Testing

### 1. Start the Server
```bash
./gradlew run
```

Server will start on `http://localhost:8080`

### 2. Create Sample Employees

This endpoint creates 10 diverse sample employees with different:
- Productivity levels (100-280 sales/hour)
- Availability patterns (full-time, part-time, weekends)
- Pay rates ($13.50-$22/hour)
- Contract terms (20-40 hours/week)

```bash
curl -X POST http://localhost:8080/api/test/create-sample-employees
```

**Response includes:**
- Confirmation message
- List of all created employees with their IDs

### 3. Get Employee IDs

```bash
curl http://localhost:8080/api/test/employee-ids
```

**Response:**
```json
{
  "count": 10,
  "employeeIds": [
    "uuid-1",
    "uuid-2",
    ...
  ]
}
```

### 4. Generate Schedule

Copy the employee IDs from step 3 and use them in the scheduling request:

```bash
curl -X POST http://localhost:8080/api/scheduling/generate \
  -H "Content-Type: application/json" \
  -d '{
    "employeeIds": ["uuid-1", "uuid-2", "uuid-3", "uuid-4", "uuid-5", "uuid-6", "uuid-7", "uuid-8", "uuid-9", "uuid-10"],
    "laborCostBudget": 5000.0,
    "salesForecast": {
      "MONDAY": {
        "09:00": 800.0,
        "12:00": 1500.0,
        "15:00": 1200.0,
        "18:00": 900.0
      },
      "TUESDAY": {
        "09:00": 700.0,
        "12:00": 1400.0,
        "15:00": 1100.0,
        "18:00": 800.0
      },
      "WEDNESDAY": {
        "09:00": 750.0,
        "12:00": 1600.0,
        "15:00": 1300.0,
        "18:00": 1000.0
      },
      "THURSDAY": {
        "09:00": 800.0,
        "12:00": 1700.0,
        "15:00": 1400.0,
        "18:00": 1100.0
      },
      "FRIDAY": {
        "09:00": 1000.0,
        "12:00": 2000.0,
        "15:00": 1800.0,
        "18:00": 1500.0
      }
    },
    "schedulingPeriod": {
      "daysToSchedule": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
      "operatingHours": {
        "MONDAY": { "openTime": "09:00", "closeTime": "21:00" },
        "TUESDAY": { "openTime": "09:00", "closeTime": "21:00" },
        "WEDNESDAY": { "openTime": "09:00", "closeTime": "21:00" },
        "THURSDAY": { "openTime": "09:00", "closeTime": "21:00" },
        "FRIDAY": { "openTime": "09:00", "closeTime": "22:00" }
      }
    },
    "shiftDurationHours": 4.0,
    "optimizationObjective": "BALANCED"
  }'
```

**Optional Parameters:**
- **`shiftDurationHours`**: Defaults to 4.0 hours if not provided. You can customize it based on your needs (e.g., 6.0 for 6-hour shifts, 8.0 for 8-hour shifts).
- **`optimizationObjective`**: Defines the scheduling strategy. Defaults to `BALANCED` if not provided. Available options:
  - `MINIMIZE_LABOR_COST` - Prioritize cheapest employees (best for tight budgets)
  - `MAXIMIZE_SALES` - Prioritize most productive employees (best for high-demand periods)
  - `MINIMIZE_EMPLOYEES` - Use fewest employees possible (best for simplifying management)
  - `BALANCED` - Optimize for best productivity-to-cost ratio (general use)

## Sample Employees Overview

The test endpoint creates these 10 employees:

### High Performers (Full-time)
1. **Alice Johnson** - $20/hr, 250 sales/hr, M-F 8am-8pm
2. **Bob Smith** - $22/hr, 280 sales/hr, M-F 9am-10pm

### Mid-Level (Full-time)
3. **Carol Davis** - $18/hr, 180 sales/hr, M-F 10am-7pm
4. **David Wilson** - $17/hr, 170 sales/hr, M-F 7am-6pm

### Part-Time
5. **Emma Brown** - $15/hr, 140 sales/hr, M/W/F/Sat
6. **Frank Miller** - $16/hr, 150 sales/hr, Tu/Th/F/Sat/Sun

### Junior (Part-time)
7. **Grace Taylor** - $14/hr, 110 sales/hr, M-Th evenings + weekends
8. **Henry Anderson** - $13.50/hr, 100 sales/hr, M/W/F evenings + weekends

### Weekend Specialists
9. **Isabel Martinez** - $19/hr, 200 sales/hr, Fri evening + Sat/Sun
10. **Jack Thomas** - $16.50/hr, 160 sales/hr, W-Sun

## Understanding the Response

### Shifts
Each shift includes:
- Employee details (ID, name)
- Schedule (day, start/end times, duration)
- Cost information (pay rate, labor cost, overtime flag)

### Metrics
- **totalLaborCost**: Total cost of all scheduled shifts
- **estimatedTotalSales**: Expected sales based on employee productivity
- **laborCostPercentage**: Labor cost as % of sales (industry standard: 20-30%)
- **employeeUtilization**: Each employee's scheduled hours vs contracted hours

### Violations
Checks for:
- **BUDGET_EXCEEDED**: Schedule exceeds budget
- **UNDERSTAFFING**: Time slots with insufficient employees
- **AVAILABILITY_CONFLICT**: Employee scheduled when unavailable
- **CONTRACT_HOURS_EXCEEDED**: Employee exceeds max hours
- **SHIFT_OVERLAP**: Employee has overlapping shifts

### Staffing Requirements
For each time slot:
- **employeesNeeded**: Calculated based on sales forecast & productivity
- **employeesAssigned**: Actual employees scheduled
- **isUnderstaffed**: Boolean flag
- **staffingGap**: How many more employees needed
- **expectedSales**: Sales forecast for that slot

## Optimization Objective Examples

### Example 1: Minimize Labor Cost
Use when: Tight budget, low-margin periods

```bash
curl -X POST http://localhost:8080/api/scheduling/generate \
  -H "Content-Type: application/json" \
  -d '{
    "employeeIds": ["..."],
    "laborCostBudget": 2000.0,
    "optimizationObjective": "MINIMIZE_LABOR_COST",
    ...
  }'
```

**Result**: Schedules cheaper employees first (Henry, Grace, Emma), even if less productive. Lower total labor cost but potentially lower sales.

### Example 2: Maximize Sales
Use when: High-demand periods, maximizing revenue

```bash
curl -X POST http://localhost:8080/api/scheduling/generate \
  -H "Content-Type: application/json" \
  -d '{
    "employeeIds": ["..."],
    "laborCostBudget": 5000.0,
    "optimizationObjective": "MAXIMIZE_SALES",
    ...
  }'
```

**Result**: Schedules most productive employees first (Bob, Alice, Isabel). Higher estimated sales but higher labor cost.

### Example 3: Minimize Employees
Use when: Simplifying management, reducing coordination

```bash
curl -X POST http://localhost:8080/api/scheduling/generate \
  -H "Content-Type: application/json" \
  -d '{
    "employeeIds": ["..."],
    "laborCostBudget": 5000.0,
    "optimizationObjective": "MINIMIZE_EMPLOYEES",
    ...
  }'
```

**Result**: Fewer total employees scheduled, each working more hours (closer to their max). Simpler schedule to manage.

### Example 4: Balanced (Default)
Use when: General scheduling, balanced approach

```bash
curl -X POST http://localhost:8080/api/scheduling/generate \
  -H "Content-Type: application/json" \
  -d '{
    "employeeIds": ["..."],
    "laborCostBudget": 5000.0,
    "optimizationObjective": "BALANCED",
    ...
  }'
```

**Result**: Best productivity-to-cost ratio. Balances sales potential with cost efficiency.

## Test Scenarios

### Scenario 1: Adequate Budget
Budget: $5000, Week: Mon-Fri
**Expected**: Full coverage, minimal understaffing

### Scenario 2: Limited Budget
Budget: $2000, Week: Mon-Fri
**Expected**: Understaffing violations, budget constraint met

### Scenario 3: High Sales Period
Friday with very high sales forecast (>$2000/hour)
**Expected**: Multiple employees scheduled, may show understaffing

### Scenario 4: Weekend Schedule
Only Saturday-Sunday in `daysToSchedule`
**Expected**: Only weekend-available employees scheduled

## Troubleshooting

### "Employee not found" errors
- Run `/api/test/create-sample-employees` first
- Ensure you're using the correct UUIDs from `/api/test/employee-ids`

### Heap space errors
- Fixed in latest version with proper loop termination
- If still occurring, check time slot generation logic

### No shifts generated
- Check if operating hours match employee availability
- Verify budget is sufficient
- Ensure sales forecast times are within operating hours

## API Endpoints Summary

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/api` | GET | API documentation |
| `/api/test/create-sample-employees` | POST | Create 10 test employees |
| `/api/test/employee-ids` | GET | Get all employee IDs |
| `/api/employees` | GET | List all employees |
| `/api/employees` | POST | Create employee |
| `/api/employees/{id}` | GET | Get employee by ID |
| `/api/employees/{id}` | PUT | Update employee |
| `/api/employees/{id}` | DELETE | Delete employee |
| `/api/scheduling/generate` | POST | Generate schedule |
| `/api/scheduling/sample-request` | GET | Sample request format |
