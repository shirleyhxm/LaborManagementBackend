# Schedule History & Audit Trail

This document explains the schedule history and audit trail system that automatically persists all schedule generations for compliance, analysis, and historical tracking.

## Overview

Every time a schedule is generated via `/api/scheduling/generate`, the system automatically creates an audit record containing:

1. **Complete Snapshot**: The full scheduling request, configuration, and resulting schedule
2. **Audit Metadata**: Timestamp, user who generated it, and version info
3. **Permanent Record**: Historical data for compliance and analysis

## Key Features

- **Automatic Persistence**: No manual action required - history is saved automatically on every generation
- **Complete Context**: Includes all parameters, configurations, and results in one record
- **Queryable History**: REST APIs to view, filter, and analyze historical schedules
- **Compliance Ready**: Full audit trail with timestamps and user tracking
- **Performance Analysis**: Compare schedule quality across different parameters

## Schedule History Record Structure

```json
{
  "id": "uuid",
  "generatedAt": "2025-01-15T14:30:00Z",
  "generatedBy": "manager@company.com",
  "schedulingRequest": {
    "id": "request-uuid",
    "name": "Week of 2025-01-15",
    "laborCostBudget": 5000.0,
    "salesForecast": { /* complete forecast data */ },
    "schedulingPeriod": { /* period configuration */ },
    "employeeIds": ["..."],
    "version": 3
  },
  "configuration": {
    "minShiftDurationHours": 1.0,
    "defaultOptimizationObjective": "BALANCED",
    "version": 2
  },
  "scheduleOutput": {
    "shifts": [ /* all generated shifts */ ],
    "metrics": {
      "totalLaborCost": 4800.0,
      "estimatedTotalSales": 150000.0,
      "laborCostPercentage": 3.2
    },
    "violations": [],
    "staffingRequirements": []
  },
  "notes": null,
  "version": 1
}
```

## API Endpoints

### 1. View All Schedule History (Paginated)

**GET** `/api/schedule-history?limit=50&offset=0`

Retrieves all schedule generation history records with pagination.

**Query Parameters:**
- `limit` (optional, default: 50): Maximum number of records to return
- `offset` (optional, default: 0): Number of records to skip

**Example Request:**
```bash
curl "http://localhost:8080/api/schedule-history?limit=20&offset=0"
```

**Example Response:**
```json
{
  "total": 150,
  "limit": 20,
  "offset": 0,
  "history": [
    {
      "id": "history-uuid-1",
      "generatedAt": "2025-01-15T14:30:00Z",
      "generatedBy": "manager@company.com",
      "schedulingRequest": { /* ... */ },
      "configuration": { /* ... */ },
      "scheduleOutput": { /* ... */ }
    },
    /* ... more records ... */
  ]
}
```

### 2. Get Latest Schedule Generation

**GET** `/api/schedule-history/latest`

Retrieves the most recent schedule generation record.

**Example Request:**
```bash
curl http://localhost:8080/api/schedule-history/latest
```

**Example Response:**
```json
{
  "id": "latest-uuid",
  "generatedAt": "2025-01-15T16:45:00Z",
  "generatedBy": "manager@company.com",
  "schedulingRequest": { /* complete request */ },
  "configuration": { /* complete config */ },
  "scheduleOutput": { /* complete schedule */ }
}
```

### 3. Get Specific History Record by ID

**GET** `/api/schedule-history/{id}`

Retrieves a specific schedule history record.

**Example Request:**
```bash
curl http://localhost:8080/api/schedule-history/f47ac10b-58cc-4372-a567-0e02b2c3d479
```

**Example Response:**
```json
{
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "generatedAt": "2025-01-12T10:00:00Z",
  "generatedBy": "alice@company.com",
  "schedulingRequest": { /* ... */ },
  "configuration": { /* ... */ },
  "scheduleOutput": { /* ... */ }
}
```

### 4. Get Schedule History by User

**GET** `/api/schedule-history/by-user/{user}`

Retrieves all schedule generations created by a specific user.

**Example Request:**
```bash
curl http://localhost:8080/api/schedule-history/by-user/manager@company.com
```

**Example Response:**
```json
{
  "user": "manager@company.com",
  "count": 25,
  "history": [
    { /* history record 1 */ },
    { /* history record 2 */ },
    /* ... */
  ]
}
```

### 5. Delete Specific History Record

**DELETE** `/api/schedule-history/{id}`

Deletes a specific schedule history record.

**Example Request:**
```bash
curl -X DELETE http://localhost:8080/api/schedule-history/f47ac10b-58cc-4372-a567-0e02b2c3d479
```

**Example Response:**
```json
{
  "message": "Schedule history deleted successfully"
}
```

### 6. Cleanup Old History Records

**DELETE** `/api/schedule-history/cleanup/older-than/{days}`

Deletes all history records older than specified number of days.

**Example Request:**
```bash
# Delete all records older than 90 days
curl -X DELETE http://localhost:8080/api/schedule-history/cleanup/older-than/90
```

**Example Response:**
```json
{
  "message": "Deleted 42 schedule history records older than 90 days",
  "deletedCount": 42,
  "cutoffDate": "2024-10-17T14:30:00Z"
}
```

## Use Cases

### 1. Compliance & Audit Trail

**Scenario**: Regulatory compliance requires maintaining records of all scheduling decisions.

**Solution**:
```bash
# Get all schedules generated in the last 30 days
curl "http://localhost:8080/api/schedule-history?limit=1000&offset=0"

# Filter by specific user for accountability
curl http://localhost:8080/api/schedule-history/by-user/manager@company.com
```

### 2. Performance Analysis

**Scenario**: Compare schedule quality across different optimization objectives.

**Workflow**:
1. Generate schedule with `MAXIMIZE_SALES` objective
2. Generate schedule with `BALANCED` objective
3. Retrieve both from history and compare metrics

```bash
# Get last 2 generations
curl "http://localhost:8080/api/schedule-history?limit=2&offset=0"

# Compare metrics.laborCostPercentage and metrics.estimatedTotalSales
```

### 3. Parameter Tuning

**Scenario**: Find optimal labor budget by testing different values.

**Workflow**:
1. Generate schedules with budgets: $4000, $4500, $5000, $5500
2. Review history to see which budget minimizes violations while meeting demand

```bash
# Get latest generation
curl http://localhost:8080/api/schedule-history/latest

# Check violations array and staffing requirements
```

### 4. Reproduce Previous Schedule

**Scenario**: Need to recreate a schedule that worked well 2 weeks ago.

**Solution**:
```bash
# Get specific history record
curl http://localhost:8080/api/schedule-history/{historical-id}

# Extract schedulingRequest and configuration
# Use PUT /api/scheduling-request to apply those parameters
# Use PUT /api/configuration to apply that configuration
# Generate new schedule with historical parameters
```

### 5. Data Retention Management

**Scenario**: Comply with data retention policies (keep 1 year, delete older).

**Solution**:
```bash
# Monthly cleanup job: delete records older than 365 days
curl -X DELETE http://localhost:8080/api/schedule-history/cleanup/older-than/365
```

## Integration with Schedule Generation

The schedule generation workflow automatically creates history:

```bash
# 1. Create sample employees
curl -X POST http://localhost:8080/api/test/create-sample-employees

# 2. Configure scheduling request
curl -X PUT http://localhost:8080/api/scheduling-request \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Schedule",
    "laborCostBudget": 5000.0,
    "salesForecast": {...},
    "schedulingPeriod": {...},
    "employeeIds": [...],
    "createdBy": "test@example.com"
  }'

# 3. Generate schedule (automatically creates history record)
curl -X POST http://localhost:8080/api/scheduling/generate

# 4. View the generated history
curl http://localhost:8080/api/schedule-history/latest
```

## Best Practices

### 1. Use Meaningful User Identifiers

When generating schedules, the system tracks who created them. Use meaningful identifiers:

```bash
# In future: support for generatedBy parameter
# Currently defaults to "system"
```

### 2. Regular Cleanup

Implement automated cleanup to manage storage:

```bash
# Retention policy: keep 1 year of history
# Run monthly:
curl -X DELETE http://localhost:8080/api/schedule-history/cleanup/older-than/365
```

### 3. Export for Long-Term Storage

For compliance, export history to external storage:

```bash
# Get all records
curl "http://localhost:8080/api/schedule-history?limit=1000" > history_export.json

# Archive to external system
aws s3 cp history_export.json s3://compliance-archives/schedule-history/
```

### 4. Monitor History Growth

Track repository size to plan storage:

```bash
# Check total count
curl http://localhost:8080/api/schedule-history | jq '.total'
```

## Error Handling

### Invalid ID

**Request:**
```bash
curl http://localhost:8080/api/schedule-history/invalid-uuid
```

**Response (400 Bad Request):**
```json
{
  "error": "Invalid ID format"
}
```

### History Not Found

**Request:**
```bash
curl http://localhost:8080/api/schedule-history/00000000-0000-0000-0000-000000000000
```

**Response (404 Not Found):**
```json
{
  "error": "Schedule history not found"
}
```

### Invalid Cleanup Days

**Request:**
```bash
curl -X DELETE http://localhost:8080/api/schedule-history/cleanup/older-than/0
```

**Response (400 Bad Request):**
```json
{
  "error": "Invalid days parameter (must be >= 1)"
}
```

## Data Model Details

### ScheduleHistory

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Unique identifier for history record |
| `generatedAt` | Instant | Timestamp when schedule was generated |
| `generatedBy` | String | User/system that generated the schedule |
| `schedulingRequest` | SchedulingRequest | Complete snapshot of request used |
| `configuration` | SchedulingConfiguration | Complete snapshot of config used |
| `scheduleOutput` | SchedulingOutput | Complete generated schedule with all shifts and metrics |
| `notes` | String? | Optional notes or description |
| `version` | Int | Schema version for future migrations |

### Storage

- **Current Implementation**: In-memory ConcurrentHashMap
- **Production**: Should use persistent database (PostgreSQL, MongoDB, etc.)
- **Scalability**: Paginated queries support large datasets

## See Also

- [Scheduling Request Management](SCHEDULING_REQUESTS.md) - Managing business data for schedules
- [Configuration Management](CONFIGURATION.md) - Algorithm configuration settings
- [Testing Guide](TESTING.md) - Testing scheduling functionality
- [README](README.md) - General project documentation
