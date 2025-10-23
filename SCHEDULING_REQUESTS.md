# Scheduling Request Management Guide

This document explains how to manage the scheduling request via the REST API. The scheduling request contains persistent business data (forecasts, budgets, periods, employee lists) used for generating schedules.

## Overview

The system maintains a **single latest scheduling request** at any time. This request contains all the business data needed to generate schedules:

1. **Automatic Creation**: If no request exists, one is created automatically with default values when you call `/api/scheduling/generate`
2. **Simple Management**: View, update, or replace the current request via REST APIs
3. **Version Tracking**: Every update increments the version number for audit purposes
4. **Automatic Usage**: Schedule generation automatically uses the latest request

## Key Concepts

### Single Latest Request

Unlike traditional systems that store multiple requests, this system maintains only **one active scheduling request** at a time:
- Creating or saving a new request **replaces** the previous one
- The latest request is always used for schedule generation
- Full version history tracking for audit compliance

### Scheduling Request Contents

- **Business Data**: Labor budget, sales forecast, scheduling period, employee IDs
- **Metadata**: Name, description for identification
- **Audit Trail**: Version, created/updated timestamps, created/updated by

## API Endpoints

### 1. Get Latest Scheduling Request

**GET** `/api/scheduling-request`

Retrieves the current scheduling request.

**Example Request:**
```bash
curl http://localhost:8080/api/scheduling-request
```

**Example Response:**
```json
{
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "name": "Current Schedule Configuration",
  "description": "Weekly scheduling parameters",
  "laborCostBudget": 5000.0,
  "salesForecast": {
    "MONDAY": {
      "09:00": 800.0,
      "12:00": 1200.0
    }
  },
  "schedulingPeriod": {
    "daysToSchedule": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
    "operatingHours": {
      "MONDAY": {
        "openTime": "09:00",
        "closeTime": "21:00"
      }
    }
  },
  "employeeIds": ["e8c7d4f2-1234-5678-9abc-def012345678"],
  "version": 3,
  "createdTime": "2025-01-15T10:00:00Z",
  "createdBy": "manager@company.com",
  "lastUpdatedTime": "2025-01-15T14:30:00Z",
  "lastUpdatedBy": "manager@company.com",
  "status": "ACTIVE"
}
```

**If no request exists:**
```json
{
  "message": "No scheduling request found. One will be created automatically when you generate a schedule."
}
```

### 2. Save or Replace Latest Request

**PUT** `/api/scheduling-request`

Saves a new scheduling request, replacing any existing one.

**Request Body:**
```json
{
  "name": "Week of 2025-01-15",
  "description": "Regular weekday schedule",
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
  },
  "employeeIds": ["e8c7d4f2-1234-5678-9abc-def012345678", "a1b2c3d4-5678-9abc-def0-123456789abc"],
  "createdBy": "manager@company.com"
}
```

**Example Request:**
```bash
curl -X PUT http://localhost:8080/api/scheduling-request \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Week of 2025-01-15",
    "description": "Regular weekday schedule",
    "laborCostBudget": 5000.0,
    "salesForecast": {...},
    "schedulingPeriod": {...},
    "employeeIds": ["e8c7d4f2-1234-5678-9abc-def012345678"],
    "createdBy": "manager@company.com"
  }'
```

**Validation:**
- `name`: Required, must not be blank
- `laborCostBudget`: Must be > 0
- `salesForecast`: All values must be >= 0
- `employeeIds`: All must be valid UUIDs that exist in the system, at least one required
- `schedulingPeriod`: At least one day required

### 3. Update Specific Fields

**PUT** `/api/scheduling-request/update`

Updates specific fields of the current request without replacing the entire request.

**Request Body** (all fields optional):
```json
{
  "laborCostBudget": 5500.0,
  "description": "Updated budget for increased demand",
  "updatedBy": "manager@company.com"
}
```

**Example Request:**
```bash
curl -X PUT http://localhost:8080/api/scheduling-request/update \
  -H "Content-Type: application/json" \
  -d '{
    "laborCostBudget": 5500.0,
    "updatedBy": "manager@company.com"
  }'
```

**Example Response:**
```json
{
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "name": "Week of 2025-01-15",
  "laborCostBudget": 5500.0,
  "version": 4,
  "lastUpdatedTime": "2025-01-15T15:00:00Z",
  "lastUpdatedBy": "manager@company.com",
  ...
}
```

### 4. Delete Latest Request

**DELETE** `/api/scheduling-request`

Deletes the current scheduling request. A new default one will be created automatically on next schedule generation.

**Example Request:**
```bash
curl -X DELETE http://localhost:8080/api/scheduling-request
```

**Example Response:**
```json
{
  "message": "Scheduling request deleted successfully"
}
```

## Generating Schedules

### Generate Schedule

**POST** `/api/scheduling/generate`

Generates a schedule using the latest scheduling request. **No parameters required.**

**Automatic Behavior:**
- If a scheduling request exists, it uses that
- If no request exists, creates a default one with all employees and standard hours
- Always uses the latest configuration settings

**Example Request:**
```bash
curl -X POST http://localhost:8080/api/scheduling/generate
```

**Example Response:**
```json
{
  "shifts": [
    {
      "id": "shift-uuid-1",
      "employeeId": "e8c7d4f2-1234-5678-9abc-def012345678",
      "employeeName": "John Doe",
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
    "totalLaborCost": 480.0,
    "estimatedTotalSales": 15000.0,
    "laborCostPercentage": 3.2,
    "employeeUtilization": {
      "John Doe": 80.0
    }
  },
  "violations": [],
  "staffingRequirements": [...],
  "isValid": true
}
```

## Complete Workflow Example

### 1. Create Sample Employees

```bash
curl -X POST http://localhost:8080/api/test/create-sample-employees
```

### 2. Get Employee IDs

```bash
curl http://localhost:8080/api/test/employee-ids
```

Response:
```json
{
  "employeeIds": [
    "e8c7d4f2-1234-5678-9abc-def012345678",
    "a1b2c3d4-5678-9abc-def0-123456789abc",
    ...
  ]
}
```

### 3. Configure Scheduling Request

```bash
curl -X PUT http://localhost:8080/api/scheduling-request \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Weekly Schedule",
    "description": "Standard weekly configuration",
    "laborCostBudget": 5000.0,
    "salesForecast": {
      "MONDAY": {"09:00": 800.0, "12:00": 1200.0, "15:00": 1000.0, "18:00": 600.0},
      "TUESDAY": {"09:00": 700.0, "12:00": 1100.0, "15:00": 900.0, "18:00": 500.0}
    },
    "schedulingPeriod": {
      "daysToSchedule": ["MONDAY", "TUESDAY"],
      "operatingHours": {
        "MONDAY": {"openTime": "09:00", "closeTime": "21:00"},
        "TUESDAY": {"openTime": "09:00", "closeTime": "21:00"}
      }
    },
    "employeeIds": ["e8c7d4f2-1234-5678-9abc-def012345678", "a1b2c3d4-5678-9abc-def0-123456789abc"],
    "createdBy": "manager@company.com"
  }'
```

### 4. Generate Schedule

```bash
curl -X POST http://localhost:8080/api/scheduling/generate
```

The system automatically uses the request you just configured.

### 5. Update Budget if Needed

```bash
curl -X PUT http://localhost:8080/api/scheduling-request/update \
  -H "Content-Type: application/json" \
  -d '{
    "laborCostBudget": 5500.0,
    "updatedBy": "manager@company.com"
  }'
```

### 6. Regenerate Schedule

```bash
curl -X POST http://localhost:8080/api/scheduling/generate
```

The system automatically picks up the updated budget.

## Default Scheduling Request

If you call `/api/scheduling/generate` without first creating a scheduling request, the system automatically creates one with:

- **Name**: "Default Scheduling Request"
- **Description**: "Auto-generated default request"
- **Labor Budget**: $5000
- **Days**: Monday - Friday
- **Operating Hours**: 9 AM - 9 PM (Monday-Thursday), 9 AM - 10 PM (Friday)
- **Sales Forecast**: Moderate forecasts throughout the day
- **Employees**: All employees in the system

You can then view and modify this default request using the APIs above.

## Versioning and Audit Tracking

### Version Tracking

Every update to the scheduling request increments the version number:

```bash
# Save new request (version 1)
curl -X PUT http://localhost:8080/api/scheduling-request -d '{...}'
# {"version": 1, "createdBy": "alice@company.com", ...}

# Update budget (version 2)
curl -X PUT http://localhost:8080/api/scheduling-request/update \
  -d '{"laborCostBudget": 5500.0, "updatedBy": "alice@company.com"}'
# {"version": 2, "lastUpdatedBy": "alice@company.com", ...}

# Update forecast (version 3)
curl -X PUT http://localhost:8080/api/scheduling-request/update \
  -d '{"salesForecast": {...}, "updatedBy": "bob@company.com"}'
# {"version": 3, "lastUpdatedBy": "bob@company.com", ...}
```

### Audit Information

Every scheduling request records:
- **createdTime**: ISO-8601 timestamp of creation
- **createdBy**: Identifier of who created it
- **lastUpdatedTime**: ISO-8601 timestamp of last update
- **lastUpdatedBy**: Identifier of who made the last change

**Best Practice**: Always include `createdBy` and `updatedBy` for accountability:

```bash
# Save with creator
curl -X PUT http://localhost:8080/api/scheduling-request \
  -d '{
    "name": "Weekly Schedule",
    "createdBy": "alice@company.com",
    ...
  }'

# Update with updater
curl -X PUT http://localhost:8080/api/scheduling-request/update \
  -d '{
    "laborCostBudget": 5500.0,
    "updatedBy": "bob@company.com"
  }'
```

## Error Handling

### Invalid Budget

**Response (400 Bad Request):**
```json
{
  "error": "laborCostBudget must be greater than 0"
}
```

### Invalid Employee IDs

**Response (400 Bad Request):**
```json
{
  "error": "Employee IDs not found: [invalid-id-1, invalid-id-2]"
}
```

### Negative Sales Forecast

**Response (400 Bad Request):**
```json
{
  "error": "Sales forecast values must be non-negative"
}
```

### No Employees Provided

**Response (400 Bad Request):**
```json
{
  "error": "At least one employee ID must be provided"
}
```

## Best Practices

### 1. Use Meaningful Names

Give your scheduling request a descriptive name:
- Good: "Week of 2025-01-15", "January 2025 Schedule", "Holiday Week"
- Bad: "Request 1", "Test", "Untitled"

### 2. Add Descriptions

Use the description field to provide context:
```json
{
  "name": "Week of 2025-01-15",
  "description": "Regular weekday schedule with increased coverage on Friday due to expected sales spike"
}
```

### 3. Track User Actions

Always include `createdBy` and `updatedBy` for accountability and audit compliance.

### 4. Incremental Updates

Use `/update` endpoint for small changes instead of replacing the entire request:

```bash
# Good - only update what changed
curl -X PUT http://localhost:8080/api/scheduling-request/update \
  -d '{"laborCostBudget": 5500.0}'

# Less efficient - replaces everything
curl -X PUT http://localhost:8080/api/scheduling-request \
  -d '{entire request...}'
```

### 5. Review Before Generating

Always review the current request before generating a schedule:

```bash
# Check current configuration
curl http://localhost:8080/api/scheduling-request

# Generate schedule
curl -X POST http://localhost:8080/api/scheduling/generate
```

## Integration with Configuration

Scheduling requests contain **business data** (forecasts, budgets, periods). They work alongside **configuration settings** (min shift duration, optimization objective).

**Complete Workflow:**

1. **Set Configuration** (scheduling algorithm parameters):
```bash
curl -X PUT http://localhost:8080/api/configuration \
  -d '{
    "minShiftDurationHours": 2.0,
    "defaultOptimizationObjective": "MAXIMIZE_SALES"
  }'
```

2. **Set Scheduling Request** (business data):
```bash
curl -X PUT http://localhost:8080/api/scheduling-request \
  -d '{
    "name": "Week of 2025-01-15",
    "laborCostBudget": 5000.0,
    "salesForecast": {...},
    ...
  }'
```

3. **Generate Schedule** (uses both):
```bash
curl -X POST http://localhost:8080/api/scheduling/generate
```

The schedule generation uses:
- **From Configuration**: `minShiftDurationHours`, `defaultOptimizationObjective`
- **From Request**: `laborCostBudget`, `salesForecast`, `schedulingPeriod`, `employeeIds`

## See Also

- [Configuration Management Guide](CONFIGURATION.md) - Managing scheduling configuration
- [Testing Guide](TESTING.md) - Testing scheduling functionality
- [README](README.md) - General project documentation
