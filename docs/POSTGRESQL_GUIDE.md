# PostgreSQL Migration & Deployment Guide

**Status: ✅ Migration Complete** - All repositories migrated to PostgreSQL with full multi-tenancy support.

This comprehensive guide covers the PostgreSQL migration status and deployment instructions for the Labor Management System.

**Official AWS Documentation:**
- [Amazon RDS User Guide](https://docs.aws.amazon.com/rds/)
- [Amazon RDS for PostgreSQL](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_PostgreSQL.html)
- [Getting Started with Amazon RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_GettingStarted.html)

## Table of Contents

1. [Local Development Setup](#local-development-setup)
2. [Testing](#testing)
3. [AWS RDS Deployment](#aws-rds-deployment)
4. [Security Best Practices](#security-best-practices)
5. [Troubleshooting](#troubleshooting)
6. [Additional Resources](#additional-resources)

---

## Local Development Setup

### Prerequisites

- PostgreSQL 14+ installed locally
- Java 11+ and Gradle installed
- Basic understanding of PostgreSQL

### 1. Install PostgreSQL

**macOS (using Homebrew):**
```bash
brew install postgresql@16
brew services start postgresql@16
```

**Ubuntu/Debian:**
```bash
sudo apt update
sudo apt install postgresql postgresql-contrib
sudo systemctl start postgresql
```

**Windows:**
Download and install from [PostgreSQL.org](https://www.postgresql.org/download/windows/)

### 2. Create Local Databases

```bash
# Create development database
createdb labormanagement

# Create test database (for running tests)
createdb labormanagement_test

# Optional: Create user and grant permissions
psql postgres -c "CREATE USER labormanagement_user WITH PASSWORD 'your_password';"
psql postgres -c "GRANT ALL PRIVILEGES ON DATABASE labormanagement TO labormanagement_user;"
psql postgres -c "GRANT ALL PRIVILEGES ON DATABASE labormanagement_test TO labormanagement_user;"
```

### 3. Configure Environment Variables

Create a `.env` file in the project root (copy from `.env.example`):

```bash
cp .env.example .env
```

Edit `.env` with your local settings:

```env
# Local PostgreSQL Configuration
DATABASE_URL=jdbc:postgresql://localhost:5432/labormanagement
DATABASE_USER=shirleyhe
DATABASE_PASSWORD=

# JWT Secret
JWT_SECRET=your-secret-key-change-in-production

# Application Port
PORT=8080
```

### 4. Run the Application

```bash
# Build the project
./gradlew build

# Run the application
./gradlew run
```

**Expected Output:**
```
Initializing database connection for environment: development
Connecting to: jdbc:postgresql://localhost:5432/labormanagement
Database initialized successfully
Creating database tables...
Database tables created successfully
Labor Management API started on port 8080
```

The application will automatically:
- Connect to PostgreSQL
- Create all 12 tables
- Create default test users (admin@shiftoptimizer.com, manager@shiftoptimizer.com, employee@shiftoptimizer.com)

---

## Testing

### Run All Tests

```bash
# Ensure test database exists
createdb labormanagement_test

# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "org.labormanagement.service.ShiftSchedulerTest"
```

### Test Status

- **Total Tests**: 29
- **Passing**: 29 ✅
- **Failing**: 0

### Test Database Setup

Tests automatically:
1. Initialize PostgreSQL connection (once per test class)
2. Reset database before each test for isolation
3. Create a test business to satisfy foreign key constraints
4. Clean up after tests complete

### Test API Endpoints

```bash
# Health check
curl http://localhost:8080/health

# Login with test user
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@shiftoptimizer.com",
    "password": "Admin123!"
  }'

# Create a business (requires JWT token from login)
curl -X POST http://localhost:8080/api/businesses \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "name": "My Coffee Shop"
  }'
```

---

## AWS RDS Deployment

### 1. Create RDS PostgreSQL Instance

**AWS Documentation Reference:**
- [Creating a DB Instance Running PostgreSQL](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_CreatePostgreSQLInstance.html)
- [DB Instance Classes](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.DBInstanceClass.html)

#### Via AWS Console:

1. Navigate to **AWS RDS Console** → **Create database**
2. Choose **PostgreSQL** as the engine
3. Select **PostgreSQL 16** (or latest stable version)
4. Choose template:
   - **Production** for production environments
   - **Dev/Test** for testing
   - **Free tier** for learning/development

5. Configure instance:
   - **DB instance identifier**: `labormanagement-db`
   - **Master username**: `labormanagement_admin`
   - **Master password**: (choose a strong password)
   - **DB instance class**:
     - Free tier: `db.t3.micro`
     - Production: `db.t3.small` or higher
   - **Storage**:
     - Allocated storage: 20 GB (minimum)
     - Storage autoscaling: Enable with max 100 GB

6. Connectivity:
   - **VPC**: Choose your VPC
   - **Public access**:
     - **Yes** for development/testing
     - **No** for production (use VPN/bastion host)
   - **VPC security group**: Create new or use existing
   - **Availability Zone**: No preference
   - **Database port**: 5432 (default)

7. Additional configuration:
   - **Initial database name**: `labormanagement`
   - **Backup retention**: 7 days (recommended)
   - **Enable encryption**: Yes (recommended)
   - **Enable enhanced monitoring**: Yes (optional)

8. Click **Create database**

#### Via AWS CLI:

```bash
aws rds create-db-instance \
    --db-instance-identifier labormanagement-db \
    --db-instance-class db.t3.small \
    --engine postgres \
    --engine-version 16.1 \
    --master-username labormanagement_admin \
    --master-user-password YOUR_SECURE_PASSWORD \
    --allocated-storage 20 \
    --storage-type gp3 \
    --storage-encrypted \
    --vpc-security-group-ids sg-xxxxxxxx \
    --db-subnet-group-name your-subnet-group \
    --backup-retention-period 7 \
    --preferred-backup-window "03:00-04:00" \
    --preferred-maintenance-window "mon:04:00-mon:05:00" \
    --db-name labormanagement \
    --publicly-accessible \
    --enable-cloudwatch-logs-exports '["postgresql"]' \
    --tags Key=Environment,Value=Production Key=Application,Value=LaborManagement
```

### 2. Configure Security Group

**AWS Documentation Reference:**
- [Controlling Access with Security Groups](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.RDSSecurityGroups.html)

Allow inbound traffic on port 5432:

1. Navigate to **EC2 Console** → **Security Groups**
2. Find the security group associated with your RDS instance
3. Edit **Inbound rules**:
   - **Type**: PostgreSQL
   - **Protocol**: TCP
   - **Port**: 5432
   - **Source**:
     - For testing: `Your IP` or `0.0.0.0/0` (not recommended for production)
     - For production: Your application's security group or VPC CIDR

### 3. Get RDS Endpoint

After the instance is created (takes ~10-15 minutes):

1. Navigate to **RDS Console** → **Databases** → Select your instance
2. Copy the **Endpoint** (e.g., `labormanagement-db.abc123.us-east-1.rds.amazonaws.com`)
3. Copy the **Port** (default: 5432)

### 4. Test Connection to RDS

```bash
psql -h labormanagement-db.abc123.us-east-1.rds.amazonaws.com \
     -p 5432 \
     -U labormanagement_admin \
     -d labormanagement

# If successful, you'll see:
# Password for user labormanagement_admin:
# psql (16.x)
# Type "help" for help.
# labormanagement=>
```

### 5. Update Application Configuration

Update your `.env` file or set environment variables:

```env
# AWS RDS PostgreSQL Configuration
DATABASE_URL=jdbc:postgresql://labormanagement-db.abc123.us-east-1.rds.amazonaws.com:5432/labormanagement
DATABASE_USER=labormanagement_admin
DATABASE_PASSWORD=your_rds_master_password

# JWT Secret (use a strong random value in production)
JWT_SECRET=$(openssl rand -base64 32)

# Application Port
PORT=8080
```

### 6. AWS Secrets Manager (Recommended for Production)

**AWS Documentation Reference:**
- [Using Secrets Manager with Amazon RDS](https://docs.aws.amazon.com/secretsmanager/latest/userguide/intro.html)

Store sensitive credentials:

```bash
# Store database password
aws secretsmanager create-secret \
    --name rds/labormanagement/password \
    --secret-string "your_rds_master_password"

# Store JWT secret
aws secretsmanager create-secret \
    --name app/labormanagement/jwt \
    --secret-string "$(openssl rand -base64 32)"
```

### 7. Deploy Application

```bash
# Run with RDS configuration
./gradlew run

# The application will:
# 1. Connect to RDS PostgreSQL
# 2. Create all tables automatically (first run only)
# 3. Start server on port 8080
```

### 8. Verify Deployment

Connect to RDS and verify tables:

```bash
psql -h your-rds-endpoint.rds.amazonaws.com \
     -U labormanagement_admin \
     -d labormanagement

# List all tables
\dt

# Expected tables:
#  public | attendances         | table
#  public | availabilities      | table
#  public | businesses          | table
#  public | employee_groups     | table
#  public | employees           | table
#  public | password_resets     | table
#  public | sales               | table
#  public | sales_forecasts     | table
#  public | schedules           | table
#  public | shifts              | table
#  public | timeoffs            | table
#  public | users               | table
```

---

## Security Best Practices

**AWS Documentation Reference:**
- [Security in Amazon RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.html)
- [Encrypting Amazon RDS Resources](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.Encryption.html)

### Network Security

1. **VPC Configuration**
   - Deploy RDS in a private subnet (no public accessibility)
   - Use VPN or AWS Direct Connect for secure access
   - Reference: [Amazon RDS in a VPC](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_VPC.html)

2. **Security Groups**
   - Restrict inbound traffic to application security groups only
   - Never allow 0.0.0.0/0 in production
   - Reference: [Security Groups for RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.RDSSecurityGroups.html)

### Database Security

1. **Encryption at Rest**
   - Enable encryption when creating the DB instance
   - Uses AWS KMS for key management
   - Reference: [Encrypting DB Instances](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.Encryption.html)

2. **Encryption in Transit**
   - Enforce SSL/TLS connections
   - Download RDS SSL/TLS certificates
   - Reference: [Using SSL with PostgreSQL](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.SSL.html)

3. **IAM Database Authentication** (Optional)
   - Use IAM roles instead of passwords
   - Better for applications running on EC2/ECS
   - Reference: [IAM Database Authentication](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.html)

### Access Control

1. **Secrets Management**
   - Store credentials in AWS Secrets Manager
   - Enable automatic rotation
   - Reference: [Rotating Secrets](https://docs.aws.amazon.com/secretsmanager/latest/userguide/rotating-secrets.html)

2. **Audit Logging**
   - Enable PostgreSQL audit logs
   - Export logs to CloudWatch
   - Reference: [PostgreSQL Database Log Files](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_LogAccess.Concepts.PostgreSQL.html)

### Production Checklist

- [ ] RDS instance is **NOT** publicly accessible
- [ ] Database credentials stored in AWS Secrets Manager
- [ ] SSL/TLS enabled for database connections
- [ ] Encryption at rest enabled
- [ ] Automated backups enabled (7+ day retention)
- [ ] Multi-AZ deployment for high availability
- [ ] CloudWatch alarms configured
- [ ] Enhanced monitoring enabled
- [ ] Parameter groups configured for production workload
- [ ] Security group restricts access to application servers only
- [ ] Regular snapshots scheduled
- [ ] Test restore procedure documented
- [ ] IAM database authentication enabled (optional)
- [ ] Audit logging configured

---

## Troubleshooting

### Connection Refused

**Error:** `Connection to localhost:5432 refused`

**Solution:**
- Verify PostgreSQL is running: `pg_isready`
- Check firewall settings
- Verify `DATABASE_URL` environment variable

### Authentication Failed

**Error:** `FATAL: password authentication failed`

**Solution:**
- Verify username and password in `.env`
- Check RDS master username matches `DATABASE_USER`
- Reset RDS master password if needed (AWS Console → RDS → Modify)

### RDS Endpoint Not Reachable

**Error:** `Connection timeout` or `Host unreachable`

**Solution:**
- Verify RDS instance is `Available` (not `Creating` or `Stopped`)
- Check security group allows inbound traffic on port 5432
- Verify you're using the correct endpoint (check AWS Console)
- Enable **Public accessibility** if connecting from outside VPC

### Table Already Exists

**Error:** `ERROR: relation "users" already exists`

**Solution:**
This is expected if tables were created in a previous run. The application uses `SchemaUtils.create()` which only creates tables if they don't exist.

### Connection Pool Exhausted

**Error:** `HikariPool - Connection is not available`

**Solution:**
- Increase `maximumPoolSize` in `DatabaseFactory.kt`
- Check for connection leaks (ensure `transaction {}` blocks complete)
- Monitor RDS connections: AWS Console → RDS → Monitoring → DatabaseConnections

### Test Failures

**Error:** Foreign key constraint violations in tests

**Solution:**
- Ensure test database exists: `createdb labormanagement_test`
- Tests automatically create a test business for multi-tenancy
- Database is reset before each test for isolation

---

## Additional Resources

### AWS RDS Documentation
- [Amazon RDS User Guide](https://docs.aws.amazon.com/rds/) - Complete RDS documentation
- [Amazon RDS for PostgreSQL](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_PostgreSQL.html) - PostgreSQL-specific features
- [Best Practices for Amazon RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_BestPractices.html) - Performance and security best practices

### Security & Compliance
- [Security in Amazon RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.html)
- [Compliance Validation for RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_ComplInfo.html)
- [AWS Shared Responsibility Model](https://aws.amazon.com/compliance/shared-responsibility-model/)

### Monitoring & Performance
- [Monitoring Amazon RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Monitoring.html)
- [Using Performance Insights](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PerfInsights.html)
- [Amazon RDS Metrics](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-metrics.html)

### Backup & Recovery
- [Backing Up and Restoring RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_CommonTasks.BackupRestore.html)
- [Working with Backups](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_WorkingWithAutomatedBackups.html)
- [Point-in-Time Recovery](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PIT.html)

### High Availability & Scaling
- [Multi-AZ Deployments](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZ.html)
- [Read Replicas](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_ReadRepl.html)
- [Modifying a DB Instance](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.DBInstance.Modifying.html)

### Application Integration
- [Exposed ORM Documentation](https://github.com/JetBrains/Exposed) - Kotlin SQL framework
- [HikariCP Connection Pooling](https://github.com/brettwooldridge/HikariCP) - High-performance JDBC connection pool
- [PostgreSQL JDBC Driver](https://jdbc.postgresql.org/) - Official PostgreSQL JDBC documentation
- [PostgreSQL Best Practices](https://www.postgresql.org/docs/current/index.html) - Official PostgreSQL documentation

### Cost Optimization
- [Amazon RDS Pricing](https://aws.amazon.com/rds/pricing/)
- [Reserved DB Instances](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_WorkingWithReservedDBInstances.html)
- [Storage Autoscaling](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PIOPS.StorageTypes.html#USER_PIOPS.Autoscaling)

### Project Documentation
- `CLAUDE.md` - Project overview and architecture
- `README.md` - API documentation and usage examples
- `DEPLOYMENT_GUIDE.md` - General deployment documentation
