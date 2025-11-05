# Labor Management API

A Kotlin backend application for managing employee shift scheduling with optimization based on labor costs, sales forecasts, and employee availability.

## Features

- Employee management (CRUD operations)
- Automated shift scheduling with optimization
- Constraint validation (availability, contract hours, labor budget)
- Sales forecasting integration
- Productivity-based scheduling
- Overtime calculation

## Tech Stack

- **Kotlin** 2.2.20
- **Ktor** 2.3.12 (Web framework)
- **Gradle** (Build tool)
- **Gson** (JSON serialization)

## Getting Started

### Prerequisites

- JDK 17 or higher
- Gradle 8.x

### Running the Application

```bash
# Build the project
./gradlew build

# Run the application
./gradlew run
```

The server will start on `http://localhost:8080`

### Health Check

```bash
curl http://localhost:8080/health
```

## API Endpoints

### Employee Management

#### Create Employee
```http
POST /api/employees
Content-Type: application/json

{
  "firstName": "John",
  "lastName": "Smith",
  "middleName": "",
  "normalPayRate": 15.0,
  "overtimePayRate": 22.5,
  "productivity": 150.0,
  "contract": {
    "contractedHoursPerWeek": 40.0,
    "maxHoursPerWeek": 50.0,
    "maxHoursPerDay": 10.0,
    "overtimeThreshold": 40.0,
    "requiresBreak": true,
    "breakDurationMinutes": 30,
    "breakThresholdMinutes": 6.0
  },
  "availability": [
    {
      "dayOfWeek": "MONDAY",
      "startTime": "09:00",
      "endTime": "18:00"
    },
    {
      "dayOfWeek": "TUESDAY",
      "startTime": "09:00",
      "endTime": "18:00"
    }
  ]
}
```

#### Get All Employees
```http
GET /api/employees
```

#### Get Employee by ID
```http
GET /api/employees/{id}
```

#### Update Employee
```http
PUT /api/employees/{id}
Content-Type: application/json

{
  "normalPayRate": 16.0,
  "productivity": 160.0
}
```

#### Delete Employee
```http
DELETE /api/employees/{id}
```

### Scheduling

#### Generate Schedule
```http
POST /api/scheduling/generate
Content-Type: application/json

{
  "employeeIds": [
    "employee-uuid-1",
    "employee-uuid-2"
  ],
  "laborCostBudget": 5000.0,
  "salesForecast": {
    "MONDAY": {
      "09:00": 800.0,
      "12:00": 1200.0,
      "15:00": 1000.0,
      "18:00": 600.0
    },
    "TUESDAY": {
      "09:00": 700.0,
      "12:00": 1100.0,
      "15:00": 900.0,
      "18:00": 500.0
    }
  },
  "schedulingPeriod": {
    "daysToSchedule": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
    "operatingHours": {
      "MONDAY": {
        "openTime": "09:00",
        "closeTime": "21:00"
      },
      "TUESDAY": {
        "openTime": "09:00",
        "closeTime": "21:00"
      }
    }
  }
}
```

**Response:**
```json
{
  "shifts": [
    {
      "id": "shift-uuid",
      "employeeId": "employee-uuid",
      "employeeName": "John Smith",
      "dayOfWeek": "MONDAY",
      "startTime": "09:00",
      "endTime": "17:00",
      "durationHours": 8.0,
      "payRate": 15.0,
      "laborCost": 120.0,
      "isOvertime": false
    }
  ],
  "metrics": {
    "totalLaborCost": 2500.0,
    "estimatedTotalSales": 15000.0,
    "laborCostPercentage": 16.67,
    "employeeUtilization": {
      "John Smith": 95.0,
      "Jane Doe": 87.5
    }
  },
  "violations": [],
  "isValid": true
}
```

#### Get Sample Request
```http
GET /api/scheduling/sample-request
```

## Data Models

### Employee
- **id**: UUID (auto-generated)
- **firstName**: String
- **lastName**: String
- **middleName**: String (optional)
- **normalPayRate**: Double (hourly rate)
- **overtimePayRate**: Double (hourly rate for overtime)
- **productivity**: Double (sales per hour)
- **contract**: Contract object
- **availability**: List of Availability objects

### Contract
- **contractedHoursPerWeek**: Double
- **maxHoursPerWeek**: Double
- **maxHoursPerDay**: Double
- **overtimeThreshold**: Double
- **requiresBreak**: Boolean
- **breakDurationMinutes**: Int
- **breakThresholdMinutes**: Double

### Availability
- **dayOfWeek**: String (MONDAY, TUESDAY, etc.)
- **startTime**: String (HH:mm format)
- **endTime**: String (HH:mm format)

## Scheduling Algorithm

The scheduling algorithm uses an intelligent hour-by-hour approach with configurable optimization objectives:

### Algorithm Strategy

1. **Hour-by-Hour Evaluation**: The scheduler evaluates demand and assigns employees hour-by-hour, treating each hour as an independent scheduling decision

2. **Optimization-Based Employee Sorting**: Employees are sorted according to the selected optimization objective (see below)

3. **Dynamic Staffing Calculation**:
   - Calculates expected sales for each hour based on hour-specific sales forecasts
   - Determines staffing needs using **actual employee productivity** rather than fixed estimates
   - Formula: `employees_needed = ceil(expected_sales / (avg_productivity * hour_duration))`
   - Considers only employees who are available for that specific hour
   - Ensures realistic staffing levels based on team capabilities

4. **Consecutive Shift Preference**: When all employee stats are equal, the scheduler prioritizes employees who worked the previous consecutive hour, naturally creating longer continuous shifts

5. **Shift Merging**: After hour-by-hour assignment, consecutive shifts with identical properties (same employee, pay rate, overtime status) are automatically merged into longer shifts

6. **Overtime Management**: Automatically applies overtime rates when employee exceeds their weekly overtime threshold

7. **Budget Enforcement**: Continuously tracks remaining budget and prevents over-allocation

### Optimization Objectives

The scheduler supports four optimization objectives that can be configured via the `optimizationObjective` parameter:

#### 1. MAXIMIZE_SALES (Best for high-demand periods)
- **Strategy**: Prioritizes employees with highest productivity first
- **Use case**: Maximizing revenue during peak sales periods
- **Employee sorting**: Descending by productivity (sales per hour)

#### 2. MINIMIZE_LABOR_COST (Best for tight budgets)
- **Strategy**: Prioritizes employees with lowest pay rates first
- **Use case**: Minimizing costs during low-margin periods
- **Employee sorting**: Ascending by current pay rate (considering overtime status)

#### 3. BALANCED (Best for general use)
- **Strategy**: Optimizes for best productivity-to-cost ratio
- **Use case**: Balancing cost efficiency with sales performance
- **Employee sorting**: Descending by productivity/payRate ratio

#### 4. MAXIMIZE_FAIRNESS (Best for equitable distribution)
- **Strategy**: Balances scheduled hours across all employees
- **Use case**: Ensuring fair work distribution and equal opportunity
- **Employee sorting**: Ascending by current weekly hours (employees with fewer hours get priority)
- **Key feature**: Dynamically re-prioritizes as hours accumulate throughout the week

### Key Implementation Details

**Hour-by-Hour Scheduling**:
- Each hour's sales forecast is treated independently
- Uncovered demand in one hour does not carry over to the next hour (lost sales model)
- Minimum shift duration defaults to 1 hour, but shifts can extend longer based on consecutive demand

**Shift Continuity**:
- When multiple employees have identical stats, those who worked the previous hour are prioritized
- This creates natural shift continuity without explicit multi-hour shift logic
- Results in more realistic work schedules with fewer fragmented shifts

**Dynamic vs. Fixed Staffing**:
- ❌ Old approach: Fixed heuristic (e.g., "1 employee per $500 in sales")
- ✅ New approach: Calculates staffing based on actual team productivity
- **Benefits**:
  - More accurate staffing for teams with varying skill levels
  - Prevents understaffing when team has lower productivity
  - Prevents overstaffing when team has higher productivity
  - Adapts to available employee pool for each hour

### Constraints
- Labor cost budget
- Employee availability (hour-specific)
- Contract hours (daily and weekly limits)
- Break requirements (validated but not enforced in schedule generation)

### Constraint Violations
The system validates and reports:
- Budget exceeded
- Availability conflicts
- Contract hours exceeded (daily/weekly)
- Shift overlaps for the same employee
- Break requirements (informational)

## Example Usage

### Complete Workflow

1. **Create employees**:
```bash
curl -X POST http://localhost:8080/api/employees \
  -H "Content-Type: application/json" \
  -d @employee1.json
```

2. **List all employees** to get their IDs:
```bash
curl http://localhost:8080/api/employees
```

3. **Generate schedule** using employee IDs:
```bash
curl -X POST http://localhost:8080/api/scheduling/generate \
  -H "Content-Type: application/json" \
  -d @schedule-request.json
```

## Architecture

```
src/main/kotlin/
├── Application.kt              # Main application entry point
├── controller/
│   ├── EmployeeController.kt   # Employee API endpoints
│   └── SchedulingController.kt # Scheduling API endpoints
├── dto/
│   ├── EmployeeDto.kt          # Employee DTOs and converters
│   └── SchedulingDto.kt        # Scheduling DTOs and converters
├── model/
│   ├── Employee.kt             # Employee domain model
│   ├── Contract.kt             # Contract domain model
│   ├── Shift.kt                # Shift domain model
│   ├── Availability.kt         # Availability domain model
│   ├── ScheduleInput.kt      # Scheduling input models
│   └── SchedulingOutput.kt     # Scheduling output models
├── repository/
│   └── EmployeeRepository.kt   # In-memory data storage
└── service/
    ├── ShiftScheduler.kt       # Scheduling algorithm
    └── ConstraintValidator.kt  # Constraint validation logic
```

## Future Enhancements

- [ ] Database integration (PostgreSQL/MongoDB)
- [ ] Advanced optimization algorithms (genetic algorithms, simulated annealing)
- [ ] Shift swap functionality
- [ ] Employee preferences and priorities
- [ ] Historical data analysis
- [ ] Real-time schedule updates
- [ ] Notification system
- [ ] Multiple location support
- [ ] Role-based access control
- [ ] Shift templates
- [ ] Time-off requests management

## License

MIT License
