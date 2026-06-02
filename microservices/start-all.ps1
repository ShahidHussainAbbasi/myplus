# MyPlus Microservices - Start All Services
# Usage (from microservices\ folder):
#   .\start-all.ps1
#   .\start-all.ps1 -JavaHome "C:\Program Files\Java\jdk-25.0.3"
#
# Logs written to: logs\<service>.log  (created automatically)
# To stop all services: .\stop-all.ps1

param(
    [string]$JavaHome = "C:\Program Files\Java\jdk-25.0.3"
)

$ROOT = $PSScriptRoot
$JAVA = Join-Path $JavaHome "bin\java.exe"
$LOGS = Join-Path $ROOT "logs"

# Preflight checks
if (-not (Test-Path $JAVA)) {
    Write-Host "ERROR: java.exe not found at: $JAVA" -ForegroundColor Red
    Write-Host "       Set -JavaHome to your JDK 25 path." -ForegroundColor Yellow
    exit 1
}

# Check MySQL on 3306
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect("localhost", 3306)
    $tcp.Close()
    Write-Host "  MySQL 3306 : UP" -ForegroundColor Green
} catch {
    Write-Host "ERROR: MySQL is not reachable on localhost:3306." -ForegroundColor Red
    Write-Host "       Please start MySQL before running this script." -ForegroundColor Yellow
    exit 1
}

# Create logs directory
if (-not (Test-Path $LOGS)) {
    New-Item -ItemType Directory -Path $LOGS | Out-Null
}

# Helper: start a service JAR in background
function Start-Svc {
    param([string]$name, [string]$jar)
    $jarPath = Join-Path $ROOT "$name\target\$jar"
    if (-not (Test-Path $jarPath)) {
        Write-Host "  SKIP $name - JAR not found. Run: mvn clean package -DskipTests" -ForegroundColor Yellow
        return $null
    }
    $logFile = Join-Path $LOGS "$name.log"
    Write-Host "  Starting $name ..." -ForegroundColor Cyan
    $procArgs = @{
        FilePath              = $JAVA
        ArgumentList          = @("-jar", $jarPath)
        WorkingDirectory      = (Join-Path $ROOT $name)
        RedirectStandardOutput = $logFile
        RedirectStandardError  = "$logFile.err"
        NoNewWindow           = $true
        PassThru              = $true
    }
    $proc = Start-Process @procArgs
    return $proc
}

# Helper: wait for a TCP port to become available
function Wait-Port {
    param([int]$port, [string]$label, [int]$timeoutSec = 90)
    Write-Host "  Waiting for $label (port $port) ..." -ForegroundColor Yellow
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $t = New-Object System.Net.Sockets.TcpClient
            $t.Connect("localhost", $port)
            $t.Close()
            Write-Host "  $label : READY" -ForegroundColor Green
            return $true
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    Write-Host "  WARNING: $label did not respond on port $port within ${timeoutSec}s" -ForegroundColor Red
    Write-Host "           Check log: $LOGS\$label.log" -ForegroundColor Yellow
    return $false
}

# Start services in order
Write-Host ""
Write-Host "====  MyPlus Microservices  ====" -ForegroundColor Magenta

# 1. Eureka Server
Start-Svc "eureka-server" "eureka-server-1.0.0-SNAPSHOT.jar" | Out-Null
if (-not (Wait-Port 8761 "eureka-server" 90)) { exit 1 }

# 2. Config Server
Start-Svc "config-server" "config-server-1.0.0-SNAPSHOT.jar" | Out-Null
if (-not (Wait-Port 8888 "config-server" 60)) { exit 1 }

Start-Sleep -Seconds 3

# 3. API Gateway + business services (parallel)
Start-Svc "api-gateway"       "api-gateway-1.0.0-SNAPSHOT.jar"       | Out-Null
Start-Svc "auth-service"      "auth-service-1.0.0-SNAPSHOT.jar"      | Out-Null
Start-Svc "business-service"  "business-service-1.0.0-SNAPSHOT.jar"  | Out-Null
Start-Svc "education-service" "education-service-1.0.0-SNAPSHOT.jar" | Out-Null

Write-Host ""
Write-Host "Waiting for all services to come online (~40s) ..." -ForegroundColor Yellow
Start-Sleep -Seconds 40

# Status report
$checks = @(
    @{ Port=8761; Label="eureka-server"    },
    @{ Port=8888; Label="config-server"    },
    @{ Port=8765; Label="api-gateway"      },
    @{ Port=8081; Label="auth-service"     },
    @{ Port=8083; Label="business-service" },
    @{ Port=8084; Label="education-service"}
)

Write-Host ""
Write-Host "====  Service Status  ====" -ForegroundColor Magenta
foreach ($svc in $checks) {
    try {
        $t = New-Object System.Net.Sockets.TcpClient
        $t.Connect("localhost", $svc.Port)
        $t.Close()
        Write-Host ("  {0,-22} port {1}  UP" -f $svc.Label, $svc.Port) -ForegroundColor Green
    } catch {
        Write-Host ("  {0,-22} port {1}  DOWN  <-- check logs\{2}.log" -f $svc.Label, $svc.Port, $svc.Label) -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "  Eureka Dashboard : http://localhost:8761" -ForegroundColor White
Write-Host "  API Gateway      : http://localhost:8765" -ForegroundColor White
Write-Host "  Main App         : http://localhost:8081" -ForegroundColor White
Write-Host "  Logs             : $LOGS\" -ForegroundColor White
Write-Host ""
Write-Host "To stop all: .\stop-all.ps1" -ForegroundColor DarkGray
