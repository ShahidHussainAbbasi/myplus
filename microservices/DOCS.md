# MyPlus Microservices — Complete Developer Guide

## Table of Contents
1. [Overview](#1-overview)
2. [Prerequisites](#2-prerequisites)
3. [Project Structure](#3-project-structure)
4. [Configuration Reference](#4-configuration-reference)
5. [Build](#5-build)
6. [Run Locally](#6-run-locally)
7. [Default Credentials](#7-default-credentials)
8. [API Reference](#8-api-reference)
9. [Development Workflow](#9-development-workflow)
10. [Deployment](#10-deployment)

---

## 1. Overview

MyPlus is a multi-module Spring Boot 3.3.4 microservices platform. Six services communicate via an API Gateway backed by Eureka service discovery. A centralized config server serves all runtime configuration.

```
Browser / Client
      │
      ▼
 API Gateway :8765   ◄── validates JWT, injects user headers
      │
      ├── auth-service     :8081  ← register, login, tokens
      ├── business-service :8083  ← companies, inventory, POS
      └── education-service:8084  ← schools, students, fees
      
 eureka-server :8761   ← service registry
 config-server :8888   ← centralized config (classpath/native)
```

**Technology Stack**
| Item | Version |
|------|---------|
| Java | 25 (targets Java 25 bytecode) |
| Spring Boot | 3.3.4 |
| Spring Cloud | 2023.0.3 |
| Database | MySQL 8 |
| JWT Library | JJWT 0.12.6 |
| Lombok | 1.18.46 |

---

## 2. Prerequisites

### Required Software

| Software | Version | Notes |
|----------|---------|-------|
| JDK | 25.x | Set `JAVA_HOME=C:\Program Files\Java\jdk-25.0.3` |
| Maven | 3.9+ | Bundled `mvnw` can also be used |
| MySQL | 8.x | Must be running before starting services |

### MySQL Setup

MySQL must be running on `localhost:3306` with:
- User: `root`
- Password: set `DB_PASSWORD` in your git-ignored `.env.local` (see `.env.example`)

**Databases are created automatically** on first service startup via `createDatabaseIfNotExist=true` in each JDBC URL:
- `myplusdb_auth` (auth-service)
- `myplusdb` (business-service)
- `myplusdb_education` (education-service)

If you need to create them manually:
```sql
CREATE DATABASE IF NOT EXISTS myplusdb_auth;
CREATE DATABASE IF NOT EXISTS myplusdb;
CREATE DATABASE IF NOT EXISTS myplusdb_education;
```

### Gmail App Password (for email features)

Email sending (registration verification, password reset) uses Gmail SMTP.

Configured account: `maxtheservice@gmail.com` — set the app password as `MAIL_PASSWORD` in `.env.local`

> **Important:** If the Gmail account has 2-Step Verification enabled, you must use a **Gmail App Password**, not your regular password. Generate one at: Google Account → Security → App Passwords.

> **Local dev tip:** Email failures are non-fatal. The service logs a warning and continues. You can test without email by using the seeded admin account (see Section 7).

---

## 3. Project Structure

```
myplus\microservices\
│
├── pom.xml                        ← Parent POM (dependency management)
├── start-all.ps1                  ← Start all services (PowerShell)
├── stop-all.ps1                   ← Stop all services (PowerShell)
│
├── eureka-server/                 ← Service registry (port 8761)
├── config-server/                 ← Central config server (port 8888)
│   └── src/main/resources/
│       ├── application.yml        ← Config server settings
│       └── configs/               ← Config files served to all services
│           ├── application.yml    ← SHARED: eureka, datasource, jwt, logging
│           ├── auth-service.yml   ← port 8081, mail, app URLs
│           ├── business-service.yml ← port 8083, datasource URL
│           └── education-service.yml ← port 8084, datasource URL
│
├── api-gateway/                   ← JWT gateway (port 8765)
│   └── src/main/resources/
│       ├── application.yml        ← Routes, CORS, JWT secret
│       └── bootstrap.yml          ← Points to config-server
│
├── auth-service/                  ← Auth + User management (port 8081)
├── business-service/              ← Business/POS module (port 8083)
└── education-service/             ← Education module (port 8084)
```

### Inside each service

```
src/main/java/com/myplus/<service>/
├── <Service>Application.java      ← Main class (@SpringBootApplication)
├── controller/                    ← REST controllers
├── service/                       ← Business logic
├── repository/                    ← Spring Data JPA repositories
├── entity/                        ← JPA entities
├── dto/                           ← Request/Response DTOs
├── security/
│   ├── HeaderAuthFilter.java      ← Reads X-User-Id header, sets principal
│   ├── SecurityConfig.java        ← Spring Security filter chain
│   └── AuthenticatedUser.java     ← Principal object (@AuthenticationPrincipal)
└── exception/
    ├── GlobalExceptionHandler.java← @ControllerAdvice error responses
    ├── ResourceNotFoundException.java
    └── ValidationException.java
```

---

## 4. Configuration Reference

### Shared Config (`config-server/src/main/resources/configs/application.yml`)

Applies to ALL services. Override in service-specific files.

```yaml
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URI:http://localhost:8761/eureka}

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:changeme}
  jpa:
    hibernate:
      ddl-auto: update           # auto-creates/updates tables
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect

jwt:
  secret: ${JWT_SECRET:dev-only-insecure-change-me-jwt-secret-min-32-bytes-padding}
  access-token-expiration-ms: 900000      # 15 minutes
  refresh-token-expiration-ms: 604800000  # 7 days
```

### Key Environment Variables

Override any default with these environment variables before running:

| Variable | Default | Description |
|----------|---------|-------------|
| `JAVA_HOME` | `C:\Program Files\Java\jdk-25.0.3` | JDK path |
| `DB_HOST` | `localhost` | MySQL host |
| `DB_PORT` | `3306` | MySQL port |
| `DB_USER` | `root` | MySQL user |
| `DB_PASSWORD` | (set in `.env.local`) | MySQL password |
| `JWT_SECRET` | (base64 default) | HMAC-SHA signing key |
| `MAIL_USER` | `maxtheservice@gmail.com` | Gmail sender |
| `MAIL_PASSWORD` | (set in `.env.local`) | Gmail app password |
| `APP_BASE_URL` | `http://localhost:8765` | Used in verification email links |
| `EUREKA_URI` | `http://localhost:8761/eureka` | Eureka server URL |
| `CONFIG_URI` | `http://localhost:8888` | Config server URL |

### IMPORTANT: Config Server Uses Classpath (Native Mode)

Configs live **inside** the config-server JAR (`classpath:/configs`). After changing any `.yml` file under `config-server/src/main/resources/configs/`, you must **rebuild config-server**:

```powershell
mvn package -DskipTests -pl config-server
```

---

## 5. Build

### Environment Setup

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.3"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
java -version   # should print: java version "25.0.3"
```

### Build All Services

```powershell
cd C:\Users\HP\Shahid\software\myplus\microservices
mvn clean package -DskipTests
```

Expected output:
```
[INFO] BUILD SUCCESS
[INFO] Total time: ~55s
```

### Build a Single Service

```powershell
# -pl selects the module, -am builds its dependencies too
mvn clean package -DskipTests -pl auth-service -am
mvn clean package -DskipTests -pl business-service -am
mvn clean package -DskipTests -pl education-service -am
mvn clean package -DskipTests -pl config-server      # after config changes
```

### Build Only Core Services (skip scaffold-only modules)

```powershell
mvn clean package -DskipTests -pl "eureka-server,config-server,api-gateway,auth-service,business-service,education-service" -am
```

### JAR Locations After Build

| Service | JAR |
|---------|-----|
| eureka-server | `eureka-server/target/eureka-server-1.0.0-SNAPSHOT.jar` |
| config-server | `config-server/target/config-server-1.0.0-SNAPSHOT.jar` |
| api-gateway | `api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar` |
| auth-service | `auth-service/target/auth-service-1.0.0-SNAPSHOT.jar` |
| business-service | `business-service/target/business-service-1.0.0-SNAPSHOT.jar` |
| education-service | `education-service/target/education-service-1.0.0-SNAPSHOT.jar` |

---

## 6. Run Locally

### Start Everything

```powershell
cd C:\Users\HP\Shahid\software\myplus\microservices
.\start-all.ps1
```

The script starts services **in order**, waiting for each to be healthy before proceeding:
1. eureka-server (waits up to 90s for port 8761)
2. config-server (waits up to 60s for port 8888)
3. api-gateway, auth-service, business-service, education-service (all started together)

### Stop Everything

```powershell
.\stop-all.ps1
```

### Stop a Single Service

```powershell
# Replace "education-service" with the service you want to stop
$svc = "education-service"
$jar = "$svc-1.0.0-SNAPSHOT.jar"
Get-WmiObject Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object { $_.CommandLine -like "*$jar*" } |
  ForEach-Object {
      Stop-Process -Id $_.ProcessId -Force
      Write-Host "Stopped $jar (PID $($_.ProcessId))"
  }
```

### Start a Single Service

```powershell
# Replace "education-service" with the service you want to start
$svc = "education-service"
$ROOT = "C:\Users\HP\Shahid\software\myplus\microservices"
$JAVA = "C:\Program Files\Java\jdk-25.0.3\bin\java.exe"
$jar  = "$ROOT\$svc\target\$svc-1.0.0-SNAPSHOT.jar"
$log  = "$ROOT\logs\$svc.log"

Start-Process -FilePath $JAVA `
              -ArgumentList @("-jar", $jar) `
              -WorkingDirectory "$ROOT\$svc" `
              -RedirectStandardOutput $log `
              -RedirectStandardError  "$log.err" `
              -NoNewWindow
Write-Host "Started $svc — tail log: $log"
```

### Restart a Single Service (stop → rebuild → start)

```powershell
# Set the service name once
$svc  = "education-service"
$ROOT = "C:\Users\HP\Shahid\software\myplus\microservices"
$JAVA = "C:\Program Files\Java\jdk-25.0.3\bin\java.exe"
$jar  = "$ROOT\$svc\target\$svc-1.0.0-SNAPSHOT.jar"
$log  = "$ROOT\logs\$svc.log"

# 1. Stop
Get-WmiObject Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object { $_.CommandLine -like "*$svc*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Write-Host "Stopped $svc"

# 2. Rebuild (omit this step if you did not change code)
Set-Location $ROOT
mvn package -DskipTests -pl $svc
Set-Location $ROOT

# 3. Start
Start-Process -FilePath $JAVA `
              -ArgumentList @("-jar", $jar) `
              -WorkingDirectory "$ROOT\$svc" `
              -RedirectStandardOutput $log `
              -RedirectStandardError  "$log.err" `
              -NoNewWindow
Write-Host "Started $svc — tail log: $log"
```

### Restart the Main Monolith (myplus)

```powershell
$ROOT = "C:\Users\HP\Shahid\software\myplus"
$JAVA = "C:\Program Files\Java\jdk-25.0.3\bin\java.exe"

# Stop
Get-WmiObject Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object { $_.CommandLine -like "*myplus.jar*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# Rebuild (omit if no code changes)
Set-Location $ROOT
mvn package -DskipTests

# Start
Start-Process -FilePath $JAVA -ArgumentList @("-jar", "$ROOT\target\myplus.jar") `
              -WorkingDirectory $ROOT -NoNewWindow
Write-Host "Started myplus monolith on port 8080"
```

### List All Running Services

```powershell
Get-WmiObject Win32_Process -Filter "Name = 'java.exe'" |
  Select-Object ProcessId,
    @{n='Service'; e={ ($_.CommandLine -split ' ')[-1] -replace '.*\\','' }} |
  Format-Table -AutoSize
```

### Verify Everything is Running

1. **Eureka Dashboard**: http://localhost:8761
   - All registered services should appear: `API-GATEWAY`, `AUTH-SERVICE`, `BUSINESS-SERVICE`, `EDUCATION-SERVICE`

2. **Health checks**:
   ```
   http://localhost:8081/actuator/health   ← auth-service
   http://localhost:8083/actuator/health   ← business-service
   http://localhost:8084/actuator/health   ← education-service
   http://localhost:8765/actuator/health   ← api-gateway
   ```

3. **Test login** (all requests go through port 8765):
   ```
   POST http://localhost:8765/api/auth/login
   ```

### Startup Order (Critical)

Services will retry config-server and eureka registration on startup, but the safest startup order is:

```
MySQL → eureka-server → config-server → (api-gateway + auth + business + education)
```

---

## 7. Default Credentials

### Admin User (seeded automatically on first startup)

| Field | Value |
|-------|-------|
| Email | `admin@myplus.com` |
| Password | `Admin@2025!` |
| Role | `ROLE_ADMIN` |
| Account enabled | Yes (no email verification needed) |

This account is created by `SetupDataLoader` on `ApplicationReadyEvent`. It is idempotent — running it again does not duplicate the user.

### Default Roles Created

| Role | Intended For |
|------|-------------|
| `ROLE_ADMIN` | Platform admin |
| `ROLE_BUSINESS_USER` | Business/POS module users |
| `ROLE_EDUCATION_USER` | Education module users |
| `ROLE_WELFARE_USER` | Welfare module users |
| `ROLE_AGRICULTURE_USER` | Agriculture module users |
| `ROLE_PHARMA_USER` | Pharma module users |
| `ROLE_MARKETPLACE_BUYER` | Marketplace buyers |
| `ROLE_MARKETPLACE_SELLER` | Marketplace sellers |

When registering, pass `"userType": "BUSINESS"` or `"EDUCATION"` etc. to get the correct role automatically.

---

## 8. API Reference

All requests go through the **API Gateway on port 8765**.

### Response Envelope

Every endpoint returns the same wrapper:

```json
{
  "success": true,
  "message": "Success",
  "data": { ... },
  "statusCode": 200
}
```

Error responses:
```json
{
  "success": false,
  "message": "Invalid credentials",
  "data": null,
  "statusCode": 400
}
```

---

### 8.1 Auth Service (`/api/auth/**`)

No JWT token required for these endpoints.

#### Register

```
POST http://localhost:8765/api/auth/register
Content-Type: application/json

{
  "firstName": "John",
  "lastName": "Doe",
  "email": "john@example.com",
  "password": "MyPass@2025",
  "phone": "03001234567",
  "userType": "BUSINESS"
}
```

`userType` options: `BUSINESS`, `EDUCATION`, `WELFARE`, `AGRICULTURE`, `PHARMA`

Response:
```json
{
  "success": true,
  "message": "Registered successfully",
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "uuid-string",
    "tokenType": "Bearer",
    "userId": 2,
    "email": "john@example.com",
    "roles": ["ROLE_BUSINESS_USER"],
    "twoFactorRequired": false
  }
}
```

> Note: A verification email is sent. The account has `enabled=false` until the email is verified. The tokens are still returned so you can test APIs immediately, but some protected operations may check `enabled`.

#### Login

```
POST http://localhost:8765/api/auth/login
Content-Type: application/json

{
  "email": "admin@myplus.com",
  "password": "Admin@2025!"
}
```

Response is the same `AuthResponse` structure as register.

#### Refresh Token

```
POST http://localhost:8765/api/auth/refresh
Content-Type: application/json

{
  "refreshToken": "uuid-string-from-login"
}
```

#### Email Verification

```
GET http://localhost:8765/api/auth/verify-email?token=<token-from-email>
```

#### Forgot Password

```
POST http://localhost:8765/api/auth/forgot-password
Content-Type: application/json

{ "email": "john@example.com" }
```

#### Reset Password

```
POST http://localhost:8765/api/auth/reset-password
Content-Type: application/json

{
  "token": "uuid-from-email",
  "newPassword": "NewPass@2025"
}
```

#### Logout (requires token)

```
POST http://localhost:8765/api/auth/logout
Authorization: Bearer <access-token>
```

#### Validate Token

```
GET http://localhost:8765/api/auth/validate
Authorization: Bearer <access-token>
```

---

### 8.2 How to Authenticate API Requests

All business/education endpoints require the JWT `accessToken` from login:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

The API Gateway validates the token and injects these headers into every downstream request:
- `X-User-Id`: numeric user ID
- `X-User-Email`: user's email
- `X-User-Roles`: comma-separated roles

Services read these headers and set the authenticated principal. Your code accesses the user via `@AuthenticationPrincipal AuthenticatedUser user`.

---

### 8.3 Business Service (`/api/business/**`)

All endpoints require `Authorization: Bearer <token>`.

Data is scoped per user — each user only sees their own records.

#### Companies

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/business/companies` | List all (paginated: `?page=0&size=20`) |
| GET | `/api/business/companies/{id}` | Get by ID |
| POST | `/api/business/companies` | Create |
| PUT | `/api/business/companies/{id}` | Update |
| DELETE | `/api/business/companies/{id}` | Delete |

**Create Company:**
```json
POST /api/business/companies
{ "name": "My Shop", "phone": "0300000000", "email": "shop@test.com", "address": "Lahore" }
```

#### Venders (Suppliers)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/business/venders` | List (paginated) |
| GET | `/api/business/venders/{id}` | Get by ID |
| POST | `/api/business/venders` | Create |
| PUT | `/api/business/venders/{id}` | Update |
| DELETE | `/api/business/venders/{id}` | Delete |

**Create Vender:**
```json
{ "name": "ABC Supplier", "companyId": 1, "phone": "031111111", "email": "abc@sup.com" }
```

#### Items (Products)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/business/items` | List (paginated) |
| GET | `/api/business/items/{id}` | Get by ID |
| POST | `/api/business/items` | Create (includes stock details) |
| PUT | `/api/business/items/{id}` | Update |
| DELETE | `/api/business/items/{id}` | Delete |

**Create Item with Stock:**
```json
{
  "iname": "Product A",
  "icode": "PRD-001",
  "idesc": "Description",
  "companyId": 1,
  "venderId": 1,
  "stock": {
    "bpurchaseRate": 100.00,
    "bsellRate": 150.00,
    "stock": 50.0,
    "srp": 160.00
  }
}
```

#### Stocks

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/business/stocks` | List (paginated) |
| GET | `/api/business/stocks/{id}` | Get by ID |
| POST | `/api/business/stocks` | Add stock entry |
| PUT | `/api/business/stocks/{id}` | Update |
| DELETE | `/api/business/stocks/{id}` | Delete |

#### Customers

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/business/customers` | List (paginated) |
| GET | `/api/business/customers/{id}` | Get by ID |
| POST | `/api/business/customers` | Create |
| PUT | `/api/business/customers/{id}` | Update |
| DELETE | `/api/business/customers/{id}` | Delete |

**Create Customer:**
```json
{ "name": "Walk-in Customer", "contact": "03001234567", "address": "Lahore" }
```

#### Purchases

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/business/purchases` | List (paginated) |
| GET | `/api/business/purchases/{id}` | Get by ID |
| GET | `/api/business/purchases/report?from=...&to=...` | Date range report |
| POST | `/api/business/purchases` | Create purchase |
| PUT | `/api/business/purchases/{id}` | Update |
| DELETE | `/api/business/purchases/{id}` | Delete |

**Create Purchase:**
```json
{
  "stockId": 1,
  "venderId": 1,
  "quantity": 10.0,
  "totalAmount": 1000.00,
  "netAmount": 950.00,
  "paidAmount": 500.00,
  "dueAmount": 450.00,
  "dueDate": "2025-12-31"
}
```

#### Sells (Sales / POS)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/business/sells` | List (paginated) |
| GET | `/api/business/sells/{id}` | Get by ID |
| GET | `/api/business/sells/report?from=...&to=...` | Date range report |
| POST | `/api/business/sells` | Create single sale (deducts stock) |
| POST | `/api/business/sells/bulk` | Create multiple sales at once |
| PUT | `/api/business/sells/{id}` | Update |
| DELETE | `/api/business/sells/{id}` | Delete |
| DELETE | `/api/business/sells/{id}/return` | Return sale (restores stock) |

**Create Sale:**
```json
{
  "stockId": 1,
  "customerId": 1,
  "quantity": 3.0,
  "totalAmount": 450.00,
  "netAmount": 430.00,
  "paidAmount": 430.00,
  "dueAmount": 0.00
}
```

#### Dashboard

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/business/dashboard/stats` | KPI summary |
| GET | `/api/business/dashboard/charts` | Chart data |

---

### 8.4 Education Service (`/api/education/**`)

All endpoints require `Authorization: Bearer <token>`.

#### Schools

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/education/schools` | List (paginated) |
| GET | `/api/education/schools/{id}` | Get by ID |
| POST | `/api/education/schools` | Create |
| PUT | `/api/education/schools/{id}` | Update |
| DELETE | `/api/education/schools/{id}` | Delete |

**Create School:**
```json
{ "name": "City Grammar School", "address": "Lahore", "phone": "042-111222" }
```

#### Students

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/education/students` | List (paginated) |
| GET | `/api/education/students/{id}` | Get by ID |
| POST | `/api/education/students` | Create |
| PUT | `/api/education/students/{id}` | Update |
| DELETE | `/api/education/students/{id}` | Delete |

#### Staff

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/education/staff` | List (paginated) |
| GET | `/api/education/staff/{id}` | Get by ID |
| POST | `/api/education/staff` | Create |
| PUT | `/api/education/staff/{id}` | Update |
| DELETE | `/api/education/staff/{id}` | Delete |

#### Other Education Endpoints

| Resource | Base Path |
|----------|-----------|
| Guardians | `/api/education/guardians` |
| Grades | `/api/education/grades` |
| Subjects | `/api/education/subjects` |
| Attendances | `/api/education/attendances` |
| Fee Collections | `/api/education/fee-collections` |
| Discounts | `/api/education/discounts` |
| Owners | `/api/education/owners` |
| Vehicles | `/api/education/vehicles` |
| Alerts | `/api/education/alerts` |
| Alert Channels | `/api/education/alert-channels` |

All follow the same CRUD pattern: GET (list, paginated), GET/{id}, POST, PUT/{id}, DELETE/{id}.

#### Education Dashboard

```
GET /api/education/dashboard/stats
```
Returns: `{ totalSchools, totalStudents, totalStaff, totalGuardians }`

---

## 9. Development Workflow

### Adding a New Endpoint to an Existing Service

1. **Add the entity** in `entity/` with `@Entity`, `@Getter`, `@Setter`, `@Builder`
2. **Add the repository** in `repository/` extending `JpaRepository<Entity, Long>`
3. **Add the DTO** in `dto/` (inner static class in the `*DTOs.java` file or standalone)
4. **Add the service** in `service/` with `@Service`, `@RequiredArgsConstructor`
5. **Add the controller** in `controller/` with `@RestController`, `@RequestMapping`
6. **Rebuild only that service:**
   ```powershell
   mvn package -DskipTests -pl business-service -am
   ```
7. **Restart only that service** — see [Stop a Single Service](#stop-a-single-service) then [Start a Single Service](#start-a-single-service) above, substituting `business-service`.

### Adding a New Microservice Module

1. Create the module directory: `my-new-service/`
2. Add `pom.xml` referiting the parent POM
3. Add main class with `@SpringBootApplication`
4. Add `src/main/resources/bootstrap.yml`:
   ```yaml
   spring:
     application:
       name: my-new-service
     cloud:
       config:
         uri: ${CONFIG_URI:http://localhost:8888}
         fail-fast: false
   ```
5. Add `config-server/src/main/resources/configs/my-new-service.yml` with port + datasource
6. Add route in `api-gateway/src/main/resources/application.yml`
7. **Register the module** in parent `pom.xml` `<modules>` section
8. **Rebuild config-server AND api-gateway** (both have config changes)
9. Add `SecurityConfig` + `HeaderAuthFilter` + `AuthenticatedUser` (copy from business-service)

### Changing a Config Value at Runtime

Since config-server uses native/classpath mode, changes require a rebuild and restart of config-server:

```powershell
# 1. Edit the config file
notepad config-server\src\main\resources\configs\auth-service.yml

# 2. Rebuild config-server
mvn package -DskipTests -pl config-server

# 3. Restart config-server
$svc  = "config-server"
$ROOT = "C:\Users\HP\Shahid\software\myplus\microservices"
$JAVA = "C:\Program Files\Java\jdk-25.0.3\bin\java.exe"
$jar  = "$ROOT\$svc\target\$svc-1.0.0-SNAPSHOT.jar"
$log  = "$ROOT\logs\$svc.log"
Get-WmiObject Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object { $_.CommandLine -like "*$svc*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Process -FilePath $JAVA -ArgumentList @("-jar", $jar) `
              -WorkingDirectory "$ROOT\$svc" `
              -RedirectStandardOutput $log -RedirectStandardError "$log.err" -NoNewWindow
```

For production, consider switching config-server to git-backed mode to get `/actuator/refresh` support without rebuilds.

### JWT Token Lifecycle

```
Login → accessToken (15 min) + refreshToken (7 days)
         │
         ├── Use accessToken for all API calls
         ├── When expired → POST /api/auth/refresh with refreshToken
         └── Logout → POST /api/auth/logout (invalidates refreshToken)
```

---

## 10. Deployment

### Build All JARs for Production

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.3"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
cd C:\Users\HP\Shahid\software\myplus\microservices
mvn clean package -DskipTests
```

### Run Each Service as a Background Process (Windows)

Set the required environment variables for production, then run each JAR:

```powershell
# Example: running auth-service with production overrides
java `
  -DJWT_SECRET=your-production-secret-base64 `
  -DDB_PASSWORD=your-db-password `
  -DMAIL_PASSWORD=your-app-password `
  -jar auth-service\target\auth-service-1.0.0-SNAPSHOT.jar
```

### Deployment Order

```
1. MySQL (running)
2. eureka-server
3. config-server        ← wait ~15s
4. api-gateway          ← wait ~20s (needs eureka + config)
5. auth-service         ← can start in parallel with gateway
6. business-service     ← can start in parallel
7. education-service    ← can start in parallel
```

### Docker (Future)

Docker Compose and Terraform files are planned in this repository. When available they will be in `docker-compose.yml` at the project root.

To manually build a Docker image for any service:

```bash
docker build -t myplus/auth-service:latest \
  --build-arg JAR_FILE=target/auth-service-1.0.0-SNAPSHOT.jar \
  auth-service/
```

### Production Environment Variables

Override these defaults for production:

```bash
DB_HOST=your-mysql-host
DB_PORT=3306
DB_USER=your-db-user
DB_PASSWORD=your-db-password
JWT_SECRET=<strong-base64-encoded-256-bit-key>
MAIL_USER=maxtheservice@gmail.com
MAIL_PASSWORD=your-gmail-app-password
APP_BASE_URL=https://your-domain.com
EUREKA_URI=http://your-eureka-host:8761/eureka
CONFIG_URI=http://your-config-host:8888
```

**Generate a secure JWT secret:**
```powershell
# PowerShell — generates a 256-bit base64 key
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RNGCryptoServiceProvider]::new().GetBytes($bytes)
[System.Convert]::ToBase64String($bytes)
```

---

## Quick Reference Card

```
PORTS
  8761  Eureka Dashboard      http://localhost:8761
  8888  Config Server         http://localhost:8888/auth-service/default
  8765  API Gateway           http://localhost:8765
  8081  Auth Service          (internal, access via gateway)
  8083  Business Service      (internal, access via gateway)
  8084  Education Service     (internal, access via gateway)

BUILD
  All:     mvn clean package -DskipTests
  One:     mvn package -DskipTests -pl <service> -am

START/STOP
  Start:   .\start-all.ps1
  Stop:    .\stop-all.ps1

DEFAULT LOGIN
  POST http://localhost:8765/api/auth/login
  { "email": "admin@myplus.com", "password": "Admin@2025!" }

AUTHENTICATED REQUEST
  GET http://localhost:8765/api/business/companies
  Authorization: Bearer <accessToken>
```
