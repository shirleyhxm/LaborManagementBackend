# Deployment Guide

## Environment Configuration

The application automatically loads configuration based on the `APP_ENV` environment variable:

- **`APP_ENV=local`** (default) → Loads `.env.local` → Uses local PostgreSQL
- **`APP_ENV=production`** → Loads `.env.production` → Uses AWS RDS

## Local Development

```bash
# Option 1: No environment variable needed (defaults to local)
./gradlew run

# Option 2: Explicitly set local environment
APP_ENV=local ./gradlew run
```

The application will automatically use `.env.local` which connects to your local PostgreSQL database.

## Production Deployment to AWS EC2

### Prerequisites

1. AWS EC2 instance (Ubuntu/Amazon Linux recommended)
2. Java 17+ installed on EC2
3. AWS RDS PostgreSQL instance already created (see POSTGRESQL_GUIDE.md for setup instructions)
4. Security groups configured (EC2 can access RDS on port 5432)

### Step 1: Prepare EC2 Instance

```bash
# SSH into your EC2 instance
ssh -i your-key.pem ec2-user@your-ec2-instance.com

# Install Java 17
sudo yum install java-17-amazon-corretto -y  # Amazon Linux
# OR
sudo apt-get update && sudo apt-get install openjdk-17-jdk -y  # Ubuntu

# Verify Java installation
java -version
```

### Step 2: Upload Application to EC2

**Option A: Using Git (Recommended)**
```bash
# On EC2 instance
git clone git@github.com:shirleyhxm/LaborManagementBackend.git
cd LaborManagementBackend
```

**Note:** If you already cloned with HTTPS and encounter "Password authentication is not supported" errors, change the remote URL to SSH:

```bash
cd LaborManagementBackend
git remote set-url origin git@github.com:shirleyhxm/LaborManagementBackend.git
git pull
```

**Option B: Using SCP**
```bash
# From your local machine
scp -i your-key.pem -r /path/to/LaborManagement ec2-user@your-ec2-instance.com:~/
```

### Step 3: Configure Production Environment

On the EC2 instance, create `.env.production`:

```bash
cd LaborManagementBackend

# Create .env.production file
cat > .env.production << 'EOF'
# Production Environment Configuration
DATABASE_URL=jdbc:postgresql://optmlshift-dev.czkw4aomy2ia.us-east-2.rds.amazonaws.com:5432/postgres
DATABASE_USER=postgres
DATABASE_PASSWORD=LyMGD6qzJdaOtegiVkQ2
JWT_SECRET=your-production-jwt-secret-change-this
PORT=8080
EOF

# Secure the file (only owner can read)
chmod 600 .env.production
```

### Step 4: Build and Run

```bash
# Build the application
./gradlew build --no-daemon -Dorg.gradle.jvmargs="-Xmx512m -XX:MaxMetaspaceSize=256m" -x test

# Run in production mode
APP_ENV=production ./gradlew run
```

### Step 5: Run as Background Service (Recommended)

Create a systemd service for automatic startup:

```bash
# Create service file
sudo nano /etc/systemd/system/labormanagement.service
```

Add this content:

```ini
[Unit]
Description=Labor Management API
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user/LaborManagementBackend
Environment="APP_ENV=production"
ExecStart=/home/ec2-user/LaborManagementBackend/gradlew run
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start the service:

```bash
# Reload systemd
sudo systemctl daemon-reload

# Enable service to start on boot
sudo systemctl enable labormanagement

# Start the service
sudo systemctl start labormanagement

# Check status
sudo systemctl status labormanagement

# View logs
sudo journalctl -u labormanagement -f
```

### Step 6: Configure Security Groups

**EC2 Security Group:**
- Inbound: Port 8080 from your IP or load balancer
- Outbound: Port 5432 to RDS security group

**RDS Security Group:**
- Inbound: Port 5432 from EC2 security group

### Step 7: Set Up NGINX Reverse Proxy (Optional but Recommended)

```bash
# Install NGINX
sudo yum install nginx -y  # Amazon Linux
# OR
sudo apt-get install nginx -y  # Ubuntu

# Configure NGINX
sudo nano /etc/nginx/conf.d/labormanagement.conf
```

Add this configuration:

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Start NGINX:

```bash
sudo systemctl start nginx
sudo systemctl enable nginx
```

## Alternative Deployment Methods

### Using Docker

Create `Dockerfile`:

```dockerfile
FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle build --no-daemon

FROM amazoncorretto:17
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENV APP_ENV=production
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

Build and run:

```bash
# Build Docker image
docker build -t labormanagement:latest .

# Run with environment variables
docker run -d \
  -p 8080:8080 \
  -e APP_ENV=production \
  -e DATABASE_URL="jdbc:postgresql://your-rds-endpoint:5432/postgres" \
  -e DATABASE_USER="postgres" \
  -e DATABASE_PASSWORD="your-password" \
  labormanagement:latest
```

### Using AWS Elastic Beanstalk

```bash
# Initialize EB
eb init -p docker labor-management

# Create environment
eb create production

# Set environment variables
eb setenv APP_ENV=production \
  DATABASE_URL="jdbc:postgresql://your-rds-endpoint:5432/postgres" \
  DATABASE_USER="postgres" \
  DATABASE_PASSWORD="your-password"

# Deploy
eb deploy
```

## Environment Variables Reference

| Variable | Local Default | Production | Required |
|----------|--------------|------------|----------|
| `APP_ENV` | `local` | `production` | No |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/labormanagement` | Your RDS endpoint | Yes |
| `DATABASE_USER` | `shirleyhe` | `postgres` | Yes |
| `DATABASE_PASSWORD` | `` | Your RDS password | Yes |
| `JWT_SECRET` | `dev-secret` | Strong random value | Yes |
| `PORT` | `8080` | `8080` | No |

## Switching Between Environments

```bash
# Run locally
APP_ENV=local ./gradlew run

# Run in production mode (on EC2)
APP_ENV=production ./gradlew run

# Override specific variables
APP_ENV=production DATABASE_URL="jdbc:postgresql://other-db:5432/db" ./gradlew run
```

## Troubleshooting

### Application can't connect to RDS

1. Check security groups allow EC2 → RDS on port 5432
2. Verify RDS endpoint in `.env.production`
3. Test connection: `psql -h your-rds-endpoint -U postgres -d postgres`

### Wrong environment loaded

```bash
# Check which environment is active
APP_ENV=production ./gradlew run
# Look for log: "Loading environment configuration for: production"
```

### Database tables not created

The application automatically creates tables on first run. Check logs for:
```
Creating database tables...
Database tables created successfully
```

## Security Best Practices

1. **Never commit `.env.production`** to Git (already in `.gitignore`)
2. **Use AWS Secrets Manager** for production credentials (advanced)
3. **Rotate RDS password** regularly
4. **Use HTTPS** with SSL certificate (Let's Encrypt or AWS ACM)
5. **Enable RDS encryption** at rest
6. **Use IAM database authentication** instead of passwords (advanced)

## Monitoring

```bash
# View application logs (systemd)
sudo journalctl -u labormanagement -f

# Check database connections
psql -h your-rds-endpoint -U postgres -c "SELECT count(*) FROM pg_stat_activity;"

# Monitor resource usage
htop
```

## Updating the Application

```bash
# On EC2 instance
cd LaborManagementBackend
git pull origin main
./gradlew build
sudo systemctl restart labormanagement
```
