# Documentation

This folder contains all project documentation for the Labor Management System.

## Available Documentation

### [POSTGRESQL_GUIDE.md](./POSTGRESQL_GUIDE.md)
**PostgreSQL Migration & Deployment Guide** - Comprehensive guide covering:
- ✅ Migration status (100% complete)
- Local PostgreSQL setup
- Testing with PostgreSQL (29/29 tests passing)
- AWS RDS deployment instructions
- Security best practices
- Troubleshooting guide

**Read this first** if you need to:
- Understand the PostgreSQL migration
- Set up local development environment
- Deploy to AWS RDS
- Troubleshoot database issues

### [DEPLOYMENT_GUIDE.md](./DEPLOYMENT_GUIDE.md)
**Application Deployment Guide** - Instructions for deploying the application:
- Environment configuration (`APP_ENV` variable)
- Local development deployment
- AWS EC2 deployment with systemd service
- Docker deployment
- AWS Elastic Beanstalk deployment
- NGINX reverse proxy setup

**Read this** if you need to:
- Deploy to production
- Set up environment variables
- Configure systemd service
- Use Docker containers

## Quick Links

### Local Development
1. Install PostgreSQL: See [POSTGRESQL_GUIDE.md - Local Development Setup](./POSTGRESQL_GUIDE.md#local-development-setup)
2. Configure environment: See [DEPLOYMENT_GUIDE.md - Local Development](./DEPLOYMENT_GUIDE.md#local-development)
3. Run tests: `./gradlew test`
4. Run application: `./gradlew run`

### Production Deployment
1. Create RDS instance: See [POSTGRESQL_GUIDE.md - AWS RDS Deployment](./POSTGRESQL_GUIDE.md#aws-rds-deployment)
2. Deploy to EC2: See [DEPLOYMENT_GUIDE.md - Production Deployment](./DEPLOYMENT_GUIDE.md#production-deployment-to-aws-ec2)
3. Configure security: See [POSTGRESQL_GUIDE.md - Security Best Practices](./POSTGRESQL_GUIDE.md#security-best-practices)

## Other Documentation

- **[../CLAUDE.md](../CLAUDE.md)** - Project instructions for Claude Code AI assistant
- **Project README** - Coming soon (main project overview)

## Need Help?

1. Check the [Troubleshooting](./POSTGRESQL_GUIDE.md#troubleshooting) section
2. Review [Security Best Practices](./POSTGRESQL_GUIDE.md#security-best-practices)
3. Consult [Additional Resources](./POSTGRESQL_GUIDE.md#additional-resources)
