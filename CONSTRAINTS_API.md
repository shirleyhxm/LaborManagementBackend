# Constraints Management API Documentation

This document provides comprehensive documentation for the Constraints Management API endpoints implemented in the Labor Management system.

## Overview

The Constraints Management API supports four main categories of constraints:

1. **Budget Constraints** - Labor cost limits and rate rules
2. **Hours Constraints** - Working hours limits and employee-specific rules
3. **Compliance Rules** - Legal requirements and custom compliance rules
4. **Priority Rules** - Scheduling optimization priorities and fairness settings

All endpoints are prefixed with `/api/v1/constraints`.

---

## 1. Budget Constraints

### Get Budget Constraints

**Endpoint:** `GET /api/v1/constraints/budget`

**Description:** Retrieves the budget constraints for the organization.

**Response (200):**
```json
{
  "id": "budget_123",
  "organizationId": "org_456",
  "weeklyBudget": 15000.0,
  "monthlyBudget": 60000.0,
  "hardBudgetLimit": true,
  "budgetWarningThreshold": 90.0,
  "createdAt": "2025-01-15T10:00:00Z",
  "updatedAt": "2025-01-20T14:30:00Z"
}
```

**Response (404):**
```json
{
  "error": "Budget constraints not found"
}
```

---

### Update Budget Constraints

**Endpoint:** `PUT /api/v1/constraints/budget`

**Description:** Updates or creates budget constraints for the organization.

**Request Body:**
```json
{
  "weeklyBudget": 15000.0,
  "monthlyBudget": 60000.0,
  "hardBudgetLimit": true,
  "budgetWarningThreshold": 90.0
}
```

**Response (200):** Updated budget constraints object

---

## 2. Hourly Rate Rules

### Get Hourly Rate Rules

**Endpoint:** `GET /api/v1/constraints/hourly-rates`

**Query Parameters:**
- `roleId` (optional): Filter by specific role

**Description:** Retrieves all hourly rate rules, optionally filtered by role.

**Response (200):**
```json
[
  {
    "id": "rate_123",
    "organizationId": "org_456",
    "roleId": "role_server",
    "baseRate": 15.00,
    "overtimeMultiplier": 1.5,
    "weekendPremium": 2.00,
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-20T14:30:00Z"
  }
]
```

---

### Create Hourly Rate Rule

**Endpoint:** `POST /api/v1/constraints/hourly-rates`

**Description:** Creates a new hourly rate rule.

**Request Body:**
```json
{
  "roleId": "role_server",
  "baseRate": 15.00,
  "overtimeMultiplier": 1.5,
  "weekendPremium": 2.00
}
```

**Response (201):** Created rate rule object

---

## 3. Working Hours Rules

### Get Working Hours Rules

**Endpoint:** `GET /api/v1/constraints/working-hours`

**Description:** Retrieves the working hours rules for the organization.

**Response (200):**
```json
{
  "id": "hours_123",
  "organizationId": "org_456",
  "maxHoursPerWeek": 40.0,
  "maxOvertimeHours": 10.0,
  "minRestBetweenShifts": 8.0,
  "maxConsecutiveDays": 6,
  "maxShiftLength": 12.0,
  "minShiftLength": 1.0,
  "createdAt": "2025-01-15T10:00:00Z",
  "updatedAt": "2025-01-20T14:30:00Z"
}
```

---

### Update Working Hours Rules

**Endpoint:** `PUT /api/v1/constraints/working-hours`

**Description:** Updates or creates working hours rules for the organization.

**Request Body:**
```json
{
  "maxHoursPerWeek": 40.0,
  "maxOvertimeHours": 10.0,
  "minRestBetweenShifts": 8.0,
  "maxConsecutiveDays": 6,
  "maxShiftLength": 12.0,
  "minShiftLength": 1.0
}
```

**Response (200):** Updated working hours rules object

---

## 4. Employee Contracted Hours

### Get Employee Contracted Hours

**Endpoint:** `GET /api/v1/constraints/contracted-hours`

**Query Parameters:**
- `employeeId` (optional): Filter by specific employee

**Description:** Retrieves all contracted hours records, optionally filtered by employee.

**Response (200):**
```json
[
  {
    "id": "contract_123",
    "employeeId": "emp_001",
    "minHours": 20.0,
    "contractedHours": 32.0,
    "maxHours": 40.0,
    "effectiveFrom": "2025-01-01",
    "effectiveTo": null,
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-20T14:30:00Z"
  }
]
```

---

### Create Employee Contracted Hours

**Endpoint:** `POST /api/v1/constraints/contracted-hours`

**Description:** Creates a new contracted hours record for an employee.

**Request Body:**
```json
{
  "employeeId": "emp_001",
  "minHours": 20.0,
  "contractedHours": 32.0,
  "maxHours": 40.0,
  "effectiveFrom": "2025-01-01"
}
```

**Response (201):** Created contracted hours object

---

### Update Employee Contracted Hours

**Endpoint:** `PUT /api/v1/constraints/contracted-hours/{id}`

**Description:** Updates an existing contracted hours record.

**Request Body:**
```json
{
  "minHours": 20.0,
  "contractedHours": 32.0,
  "maxHours": 40.0
}
```

**Response (200):** Updated contracted hours object

**Response (404):**
```json
{
  "error": "Contracted hours not found"
}
```

---

## 5. Compliance Rules

### Get Compliance Rules

**Endpoint:** `GET /api/v1/constraints/compliance`

**Description:** Retrieves the compliance rules for the organization.

**Response (200):**
```json
{
  "id": "compliance_123",
  "organizationId": "org_456",
  "flsaOvertimeEnabled": true,
  "mealBreakRequired": true,
  "mealBreakMinShiftHours": 6.0,
  "mealBreakDuration": 30,
  "minorLaborLawsEnabled": true,
  "advanceNoticePeriod": 7,
  "createdAt": "2025-01-15T10:00:00Z",
  "updatedAt": "2025-01-20T14:30:00Z"
}
```

---

### Update Compliance Rules

**Endpoint:** `PUT /api/v1/constraints/compliance`

**Description:** Updates or creates compliance rules for the organization.

**Request Body:**
```json
{
  "flsaOvertimeEnabled": true,
  "mealBreakRequired": true,
  "mealBreakMinShiftHours": 6.0,
  "mealBreakDuration": 30,
  "minorLaborLawsEnabled": true,
  "advanceNoticePeriod": 7
}
```

**Response (200):** Updated compliance rules object

---

## 6. Custom Compliance Rules

### List Custom Compliance Rules

**Endpoint:** `GET /api/v1/constraints/custom-compliance`

**Description:** Retrieves all custom compliance rules for the organization.

**Response (200):**
```json
[
  {
    "id": "custom_123",
    "organizationId": "org_456",
    "name": "California Split Shift Rule",
    "description": "Premium pay for split shifts over 1 hour apart",
    "isActive": true,
    "ruleType": "split_shift",
    "configuration": {
      "minGapHours": 1,
      "premiumAmount": 50
    },
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-20T14:30:00Z"
  }
]
```

---

### Create Custom Compliance Rule

**Endpoint:** `POST /api/v1/constraints/custom-compliance`

**Description:** Creates a new custom compliance rule.

**Request Body:**
```json
{
  "name": "California Split Shift Rule",
  "description": "Premium pay for split shifts over 1 hour apart",
  "isActive": true,
  "ruleType": "split_shift",
  "configuration": {
    "minGapHours": 1,
    "premiumAmount": 50
  }
}
```

**Valid Rule Types:**
- `split_shift`
- `state_specific`
- `custom`

**Response (201):** Created custom compliance rule object

---

### Update Custom Compliance Rule

**Endpoint:** `PUT /api/v1/constraints/custom-compliance/{id}`

**Description:** Updates an existing custom compliance rule.

**Request Body:**
```json
{
  "name": "California Split Shift Rule",
  "description": "Premium pay for split shifts over 1 hour apart",
  "isActive": true,
  "configuration": {
    "minGapHours": 1,
    "premiumAmount": 50
  }
}
```

**Response (200):** Updated custom compliance rule object

**Response (404):**
```json
{
  "error": "Custom compliance rule not found"
}
```

---

### Delete Custom Compliance Rule

**Endpoint:** `DELETE /api/v1/constraints/custom-compliance/{id}`

**Description:** Deletes a custom compliance rule.

**Response (204):** No content

**Response (404):**
```json
{
  "error": "Custom compliance rule not found"
}
```

---

## 7. Scheduling Priorities

### List Scheduling Priorities

**Endpoint:** `GET /api/v1/constraints/priorities`

**Description:** Retrieves all scheduling priorities for the organization, sorted by priority order.

**Response (200):**
```json
[
  {
    "id": "priority_1",
    "organizationId": "org_456",
    "priorityOrder": 1,
    "priorityType": "contracted_hours",
    "name": "Meet Contracted Hours",
    "description": "Highest priority",
    "isEnabled": true,
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-20T14:30:00Z"
  },
  {
    "id": "priority_2",
    "organizationId": "org_456",
    "priorityOrder": 2,
    "priorityType": "availability",
    "name": "Respect Employee Availability",
    "description": "High priority",
    "isEnabled": true,
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-20T14:30:00Z"
  }
]
```

**Valid Priority Types:**
- `contracted_hours`
- `availability`
- `forecast`
- `labor_cost`
- `fair_distribution`

---

### Reorder Priorities

**Endpoint:** `PUT /api/v1/constraints/priorities/reorder`

**Description:** Updates the priority order of scheduling priorities.

**Request Body:**
```json
{
  "priorities": [
    { "id": "priority_1", "priorityOrder": 1 },
    { "id": "priority_2", "priorityOrder": 2 },
    { "id": "priority_3", "priorityOrder": 3 }
  ]
}
```

**Response (200):** Updated priorities list

---

## 8. Fairness Settings

### Get Fairness Settings

**Endpoint:** `GET /api/v1/constraints/fairness`

**Description:** Retrieves the fairness settings for the organization.

**Response (200):**
```json
{
  "id": "fairness_123",
  "organizationId": "org_456",
  "rotateWeekendShifts": true,
  "balanceDesirableShifts": true,
  "seniorityPreference": false,
  "createdAt": "2025-01-15T10:00:00Z",
  "updatedAt": "2025-01-20T14:30:00Z"
}
```

---

### Update Fairness Settings

**Endpoint:** `PUT /api/v1/constraints/fairness`

**Description:** Updates or creates fairness settings for the organization.

**Request Body:**
```json
{
  "rotateWeekendShifts": true,
  "balanceDesirableShifts": true,
  "seniorityPreference": false
}
```

**Response (200):** Updated fairness settings object

---

## 9. Bulk Operations

### Get All Constraints

**Endpoint:** `GET /api/v1/constraints`

**Description:** Retrieves a complete snapshot of all constraints for the organization.

**Response (200):**
```json
{
  "budget": { /* BudgetConstraints object */ },
  "hourlyRates": [ /* Array of HourlyRateRules */ ],
  "workingHours": { /* WorkingHoursRules object */ },
  "contractedHours": [ /* Array of EmployeeContractedHours */ ],
  "compliance": { /* ComplianceRules object */ },
  "customCompliance": [ /* Array of CustomComplianceRule */ ],
  "priorities": [ /* Array of SchedulingPriority */ ],
  "fairness": { /* FairnessSettings object */ }
}
```

---

## 10. Validation

### Validate Constraints

**Endpoint:** `POST /api/v1/constraints/validate`

**Description:** Validates constraint configuration before applying.

**Request Body:**
```json
{
  "constraintType": "budget",
  "constraints": {
    "weeklyBudget": 15000,
    "monthlyBudget": 60000
  }
}
```

**Valid Constraint Types:**
- `budget`
- `workingHours`
- `contractedHours`

**Response (200):**
```json
{
  "isValid": true,
  "errors": [],
  "warnings": [
    {
      "field": "weeklyBudget",
      "message": "Weekly budget multiplied by 4 exceeds monthly budget"
    }
  ]
}
```

---

## Error Responses

### Standard Error Format

All error responses follow this format:

```json
{
  "error": "Error message describing what went wrong"
}
```

### Common HTTP Status Codes

- **200 OK** - Request succeeded
- **201 Created** - Resource created successfully
- **204 No Content** - Resource deleted successfully
- **400 Bad Request** - Invalid input data
- **404 Not Found** - Resource not found
- **500 Internal Server Error** - Server error

---

## Implementation Notes

### Multi-tenancy

- All constraint entities are scoped to `organizationId`
- API endpoints automatically filter by authenticated user's organization
- No cross-organization data access is allowed

### Validation Rules

#### Budget Constraints
- `weeklyBudget` and `monthlyBudget` must be positive
- `budgetWarningThreshold` must be between 0-100

#### Hours Constraints
- `maxHoursPerWeek` should be >= contracted hours for all employees
- `minRestBetweenShifts` should be >= 0
- `maxShiftLength` should be > `minShiftLength`

#### Employee Contracted Hours
- Must satisfy: `minHours` <= `contractedHours` <= `maxHours`
- No overlapping effective date ranges for the same employee

#### Priority Rules
- `priorityOrder` must be unique within organization
- All priorities 1-N must exist (no gaps)

---

## Future Enhancements

1. **Constraint Templates** - Pre-configured constraint sets for different industries
2. **Constraint Versioning** - Historical tracking with ability to rollback
3. **Rule Conflict Detection** - Automatic detection of conflicting constraints
4. **AI-Assisted Configuration** - Smart suggestions based on industry best practices
5. **Constraint Inheritance** - Department-level overrides of organization defaults
6. **Batch Import/Export** - CSV/JSON import for bulk constraint configuration
7. **Impact Analysis** - Analyze how constraint changes affect existing schedules

---

## Example Usage

### Setting up complete constraint configuration

```bash
# 1. Set budget constraints
curl -X PUT http://localhost:8080/api/v1/constraints/budget \
  -H "Content-Type: application/json" \
  -d '{
    "weeklyBudget": 15000,
    "monthlyBudget": 60000,
    "hardBudgetLimit": true,
    "budgetWarningThreshold": 90
  }'

# 2. Set working hours rules
curl -X PUT http://localhost:8080/api/v1/constraints/working-hours \
  -H "Content-Type: application/json" \
  -d '{
    "maxHoursPerWeek": 40,
    "maxOvertimeHours": 10,
    "minRestBetweenShifts": 8,
    "maxConsecutiveDays": 6,
    "maxShiftLength": 12,
    "minShiftLength": 1
  }'

# 3. Create hourly rate rule
curl -X POST http://localhost:8080/api/v1/constraints/hourly-rates \
  -H "Content-Type: application/json" \
  -d '{
    "roleId": "server",
    "baseRate": 15.00,
    "overtimeMultiplier": 1.5,
    "weekendPremium": 2.00
  }'

# 4. Set compliance rules
curl -X PUT http://localhost:8080/api/v1/constraints/compliance \
  -H "Content-Type: application/json" \
  -d '{
    "flsaOvertimeEnabled": true,
    "mealBreakRequired": true,
    "mealBreakMinShiftHours": 6,
    "mealBreakDuration": 30,
    "minorLaborLawsEnabled": true,
    "advanceNoticePeriod": 7
  }'

# 5. Get all constraints
curl http://localhost:8080/api/v1/constraints
```
