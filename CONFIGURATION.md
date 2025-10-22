# Configuration Management Guide

This document explains how to manage scheduling configuration settings via the REST API.

## Overview

Configuration settings control various aspects of the shift scheduling algorithm. These settings are stored in a repository and can be updated via REST APIs. **All schedules generated via `/api/scheduling/generate` use the latest configuration settings from the repository.** To change scheduling behavior, update the configuration before generating the schedule.

Configuration includes versioning and audit tracking to maintain a complete history of changes for compliance and debugging purposes.

## Configuration Settings

### Available Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `minShiftDurationHours` | Double | 1.0 | Minimum shift duration in hours. Shifts can be longer but must be at least this long. |
| `defaultOptimizationObjective` | String | BALANCED | Default optimization strategy. Valid values: `MAXIMIZE_SALES`, `MINIMIZE_LABOR_COST`, `BALANCED`, `MAXIMIZE_FAIRNESS` |

## API Endpoints

### Get Current Configuration

**GET** `/api/configuration`

Returns the current configuration settings including version and audit information.

**Example Request:**
```bash
curl http://localhost:8080/api/configuration
```

**Example Response:**
```json
{
  "configId": "default",
  "minShiftDurationHours": 1.0,
  "defaultOptimizationObjective": "BALANCED",
  "version": 1,
  "lastUpdatedTime": "2025-01-15T10:30:00Z",
  "lastUpdatedBy": "system"
}
```

**Response Fields:**
- `configId`: Identifier for this configuration
- `minShiftDurationHours`: Minimum shift duration setting
- `defaultOptimizationObjective`: Current optimization strategy
- `version`: Version number (incremented with each update)
- `lastUpdatedTime`: ISO-8601 timestamp of last update
- `lastUpdatedBy`: Identifier of user who made the last update

### Update Configuration

**PUT** `/api/configuration`

Updates one or more configuration settings. All fields are optional - only provided fields will be updated.

**Request Body:**
```json
{
  "minShiftDurationHours": 2.0,
  "defaultOptimizationObjective": "MAXIMIZE_SALES"
}
```

**Example Request:**
```bash
curl -X PUT http://localhost:8080/api/configuration \
  -H "Content-Type: application/json" \
  -d '{
    "minShiftDurationHours": 2.0,
    "defaultOptimizationObjective": "MAXIMIZE_SALES"
  }'
```

**Example Response:**
```json
{
  "configId": "default",
  "minShiftDurationHours": 2.0,
  "defaultOptimizationObjective": "MAXIMIZE_SALES"
}
```

### Reset Configuration to Defaults

**POST** `/api/configuration/reset`

Resets all configuration settings to their default values.

**Example Request:**
```bash
curl -X POST http://localhost:8080/api/configuration/reset
```

**Example Response:**
```json
{
  "configId": "default",
  "minShiftDurationHours": 1.0,
  "defaultOptimizationObjective": "BALANCED"
}
```

## Using Configuration in Scheduling

When generating a schedule via `/api/scheduling/generate`, configuration values are **always** fetched from the configuration repository. To change scheduling behavior, you must update the configuration via `/api/configuration`.

### Example: Using Configuration

```bash
# First, set your desired configuration
curl -X PUT http://localhost:8080/api/configuration \
  -H "Content-Type: application/json" \
  -d '{
    "minShiftDurationHours": 2.0,
    "defaultOptimizationObjective": "MAXIMIZE_SALES"
  }'

# Then generate schedule - it will use the configuration settings
curl -X POST http://localhost:8080/api/scheduling/generate \
  -H "Content-Type: application/json" \
  -d '{
    "employeeIds": ["emp1", "emp2"],
    "laborCostBudget": 5000.0,
    "salesForecast": { ... },
    "schedulingPeriod": { ... }
  }'
```

In this example, the schedule will be generated using `minShiftDurationHours = 2.0` and `optimizationObjective = MAXIMIZE_SALES` from the configuration.

### Changing Optimization Strategy

If you want to generate schedules with different optimization strategies, update the configuration before each request:

```bash
# Generate with MAXIMIZE_SALES
curl -X PUT http://localhost:8080/api/configuration \
  -d '{"defaultOptimizationObjective": "MAXIMIZE_SALES"}'
curl -X POST http://localhost:8080/api/scheduling/generate -d '{...}'

# Generate with MINIMIZE_LABOR_COST
curl -X PUT http://localhost:8080/api/configuration \
  -d '{"defaultOptimizationObjective": "MINIMIZE_LABOR_COST"}'
curl -X POST http://localhost:8080/api/scheduling/generate -d '{...}'
```

## Optimization Objectives

### MAXIMIZE_SALES
Prioritizes employees with highest productivity first. Maximizes expected sales but may result in higher labor costs.

### MINIMIZE_LABOR_COST
Prioritizes employees with lowest pay rates first (considering overtime status). Minimizes labor costs but may reduce sales potential.

### BALANCED
Best productivity-to-cost ratio (sales per dollar spent). Balances both sales and cost considerations.

### MAXIMIZE_FAIRNESS
Prioritizes employees with fewest scheduled hours. Distributes work evenly among all employees.

## Validation Rules

### minShiftDurationHours
- Must be greater than 0
- Typical values: 0.5 - 8.0 hours
- Lower values allow more scheduling flexibility
- Higher values reduce shift fragmentation

### defaultOptimizationObjective
- Must be one of the valid optimization objectives
- Invalid values will be rejected with a 400 Bad Request error

## Error Handling

### Invalid Values

**Request:**
```bash
curl -X PUT http://localhost:8080/api/configuration \
  -H "Content-Type: application/json" \
  -d '{"minShiftDurationHours": -1.0}'
```

**Response (400 Bad Request):**
```json
{
  "error": "minShiftDurationHours must be greater than 0"
}
```

### Invalid Optimization Objective

**Request:**
```bash
curl -X PUT http://localhost:8080/api/configuration \
  -H "Content-Type: application/json" \
  -d '{"defaultOptimizationObjective": "INVALID_STRATEGY"}'
```

**Response (400 Bad Request):**
```json
{
  "error": "Invalid optimization objective. Valid values: MAXIMIZE_SALES, MINIMIZE_LABOR_COST, BALANCED, MAXIMIZE_FAIRNESS"
}
```

## Audit and Versioning

### Version Tracking

Every configuration update increments the version number. This allows you to:
- Track configuration changes over time
- Identify when specific settings were modified
- Debug issues by comparing configuration versions

**Example - Tracking Version Changes:**
```bash
# Initial configuration (version 1)
curl http://localhost:8080/api/configuration
# { "version": 1, "minShiftDurationHours": 1.0, ... }

# Update configuration
curl -X PUT http://localhost:8080/api/configuration \
  -d '{"minShiftDurationHours": 2.0, "updatedBy": "alice@company.com"}'
# { "version": 2, "minShiftDurationHours": 2.0, "lastUpdatedBy": "alice@company.com", ... }

# Update again
curl -X PUT http://localhost:8080/api/configuration \
  -d '{"defaultOptimizationObjective": "MAXIMIZE_SALES", "updatedBy": "bob@company.com"}'
# { "version": 3, "defaultOptimizationObjective": "MAXIMIZE_SALES", "lastUpdatedBy": "bob@company.com", ... }
```

### Audit Information

Every configuration change records:
- **lastUpdatedTime**: ISO-8601 timestamp of when the change occurred
- **lastUpdatedBy**: Identifier of who made the change (defaults to "anonymous" if not provided)

**Providing User Information:**

When updating configuration, include the `updatedBy` field to track who made the change:

```bash
curl -X PUT http://localhost:8080/api/configuration \
  -H "Content-Type: application/json" \
  -d '{
    "minShiftDurationHours": 2.0,
    "updatedBy": "alice@company.com"
  }'
```

**Response:**
```json
{
  "configId": "default",
  "minShiftDurationHours": 2.0,
  "defaultOptimizationObjective": "BALANCED",
  "version": 2,
  "lastUpdatedTime": "2025-01-15T14:30:00Z",
  "lastUpdatedBy": "alice@company.com"
}
```

### Use Cases for Audit Information

1. **Compliance**: Track all configuration changes for regulatory requirements
2. **Debugging**: Identify when problematic settings were introduced
3. **Accountability**: Know who made specific configuration changes
4. **Rollback**: Understand previous configurations to restore settings

## Best Practices

### 1. Set Configuration Before Scheduling
Always update the configuration **before** generating a schedule. The system uses the current configuration at the time of schedule generation.

### 2. Test Configuration Changes
After updating configuration, test with a sample schedule request to ensure the settings work as expected.

### 3. Document Organization Defaults
Keep a record of your organization's configuration settings and the rationale behind them.

### 4. Reset to Defaults When Needed
Use `/api/configuration/reset` to quickly return to default settings if you've made experimental changes.

### 5. Configuration is Global
Remember that configuration changes affect all schedule generation requests. If you need different settings for different schedules, update the configuration before each request.

## Future Enhancements

### Multi-Tenant Support
Currently, there's a single "default" configuration. Future versions may support:
- Multiple configurations per tenant/location
- Configuration versioning and history
- Scheduled configuration changes

### Additional Configuration Settings
Future settings may include:
- Break duration rules
- Shift start time constraints
- Employee preference weights
- Maximum consecutive working days
