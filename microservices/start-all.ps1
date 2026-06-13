# MyPlus Microservices - Start Services
#
# Usage (from microservices\ folder):
#   .\start-all.ps1 all                         # start every service
#   .\start-all.ps1 auth-service                # start a single service
#   .\start-all.ps1 auth-service,business-service   # start a list
#   .\start-all.ps1 auth-service business-service   # space-separated also works
#   .\start-all.ps1                             # no arg -> prompts for a list
#   .\start-all.ps1 all -JavaHome "C:\Program Files\Java\jdk-21.0.10"
#
# Logs written to: logs\<service>.log  (created automatically)
# To stop services: .\stop-all.ps1

param(
    [Parameter(Position = 0)]
    [string[]]$Services,
    [string]$JavaHome = "C:\Program Files\Java\jdk-21.0.10"
)

$ROOT = $PSScriptRoot
$JAVA = Join-Path $JavaHome "bin\java.exe"
$LOGS = Join-Path $ROOT "logs"

# --- Load local secrets (DB creds, etc.) from the env file if present ---
# Canonical file is `.env` (also the one docker-compose auto-reads); `.env.local`
# is kept as a fallback for backward compat. Both are git-ignored, keeping real
# passwords out of tracked config. Service application.yml files read
# ${DB_USER}/${DB_PASSWORD}; the java.exe child processes inherit what we set on Env:.
$envFile = Join-Path $ROOT ".env"
if (-not (Test-Path $envFile)) { $envFile = Join-Path $ROOT ".env.local" }
if (Test-Path $envFile) {
    foreach ($raw in (Get-Content $envFile)) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $kv = $line -split '=', 2
        if ($kv.Count -eq 2) {
            $key = $kv[0].Trim()
            $val = $kv[1].Trim().Trim('"').Trim("'")
            Set-Item -Path "Env:$key" -Value $val
        }
    }
    Write-Host ("  Loaded secrets from {0}" -f (Split-Path $envFile -Leaf)) -ForegroundColor DarkGray
}

# Canonical start order: infra first (eureka, config), then gateway, then the services.
$catalog = [ordered]@{
    'eureka-server'       = 8761
    'config-server'       = 8888
    'api-gateway'         = 8765
    'auth-service'        = 8081
    'inventory-service'   = 8082
    'business-service'    = 8083
    'education-service'   = 8084
    'welfare-service'     = 8085
    'agriculture-service' = 8086
    'pharma-service'      = 8087
    'marketplace-service' = 8088
    'campaign-service'    = 8089
    'analytics-service'   = 8090
    'appointment-service' = 8091
}
$order = @($catalog.Keys)

# Preflight: Java
if (-not (Test-Path $JAVA)) {
    Write-Host "ERROR: java.exe not found at: $JAVA" -ForegroundColor Red
    Write-Host "       Set -JavaHome to your JDK 25 path." -ForegroundColor Yellow
    exit 1
}

# --- Resolve the requested service selection ---
if (-not $Services -or $Services.Count -eq 0) {
    Write-Host "Available services:" -ForegroundColor Cyan
    foreach ($n in $order) { Write-Host ("   - {0}" -f $n) }
    Write-Host ""
    $answer = Read-Host "Enter 'all', or a comma/space-separated list of service names"
    $Services = $answer -split '[,\s]+' | Where-Object { $_ -ne '' }
}
if (-not $Services -or $Services.Count -eq 0) {
    Write-Host "No services specified. Nothing to do." -ForegroundColor Yellow
    exit 0
}

# Normalise + expand 'all'
$requested = $Services | ForEach-Object { $_.Trim().ToLower() }
if ($requested -contains 'all') {
    $selected = $order
} else {
    # Preserve canonical order; warn about unknown names
    $selected = $order | Where-Object { $requested -contains $_ }
    $unknown = $requested | Where-Object { $order -notcontains $_ }
    foreach ($u in $unknown) {
        Write-Host ("WARNING: unknown service '{0}' (ignored). Valid: {1}" -f $u, ($order -join ', ')) -ForegroundColor Yellow
    }
}
if (-not $selected -or $selected.Count -eq 0) {
    Write-Host "No valid services to start." -ForegroundColor Red
    exit 1
}

# Preflight: MySQL (only matters for DB-backed services; warn but continue otherwise)
$needsDb = $selected | Where-Object { $_ -notin @('eureka-server','config-server','api-gateway') }

# Fail fast if a DB-backed service was requested but no password is available.
# Without this the service silently falls back to the 'changeme' placeholder in
# application.yml and dies with "Access denied for user 'root'@'localhost'".
if ($needsDb -and -not $env:DB_PASSWORD) {
    Write-Host "ERROR: DB_PASSWORD is not set (and .env / .env.local did not provide it)." -ForegroundColor Red
    Write-Host "       DB-backed services would fall back to the 'changeme' placeholder and fail." -ForegroundColor Yellow
    Write-Host "       Create microservices\.env (copy from .env.example) with:" -ForegroundColor Yellow
    Write-Host "         DB_USER=root" -ForegroundColor Yellow
    Write-Host "         DB_PASSWORD=<your-mysql-root-password>" -ForegroundColor Yellow
    Write-Host "       (the file is git-ignored), or set `$env:DB_PASSWORD before running." -ForegroundColor Yellow
    exit 1
}

try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect("localhost", 3306); $tcp.Close()
    Write-Host "  MySQL 3306 : UP" -ForegroundColor Green
} catch {
    if ($needsDb) {
        Write-Host "WARNING: MySQL not reachable on localhost:3306 - DB-backed services may fail to start." -ForegroundColor Yellow
    }
}

if (-not (Test-Path $LOGS)) { New-Item -ItemType Directory -Path $LOGS | Out-Null }

# Helper: start a service JAR in background (jar name derived from service name)
function Start-Svc {
    param([string]$name)
    $jarPath = Join-Path $ROOT "$name\target\$name-1.0.0-SNAPSHOT.jar"
    if (-not (Test-Path $jarPath)) {
        Write-Host "  SKIP $name - JAR not found ($jarPath). Run: mvn -pl $name -am clean package -DskipTests" -ForegroundColor Yellow
        return $null
    }
    $logFile = Join-Path $LOGS "$name.log"
    Write-Host "  Starting $name ..." -ForegroundColor Cyan
    $procArgs = @{
        FilePath               = $JAVA
        ArgumentList           = @("-jar", $jarPath)
        WorkingDirectory       = (Join-Path $ROOT $name)
        RedirectStandardOutput = $logFile
        RedirectStandardError  = "$logFile.err"
        NoNewWindow            = $true
        PassThru               = $true
    }
    return (Start-Process @procArgs)
}

# Helper: wait for a TCP port to become available
function Wait-Port {
    param([int]$port, [string]$label, [int]$timeoutSec = 90)
    Write-Host "  Waiting for $label (port $port) ..." -ForegroundColor Yellow
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $t = New-Object System.Net.Sockets.TcpClient
            $t.Connect("localhost", $port); $t.Close()
            Write-Host "  $label : READY" -ForegroundColor Green
            return $true
        } catch { Start-Sleep -Seconds 2 }
    }
    Write-Host "  WARNING: $label did not respond on port $port within ${timeoutSec}s (check logs\$label.log)" -ForegroundColor Red
    return $false
}

Write-Host ""
Write-Host "====  MyPlus Microservices  ====" -ForegroundColor Magenta
Write-Host ("Starting: {0}" -f ($selected -join ', ')) -ForegroundColor White
Write-Host ""

# Start in canonical order. Block on the infra services that dependents need.
foreach ($name in $selected) {
    Start-Svc $name | Out-Null
    if ($name -eq 'eureka-server') {
        Wait-Port 8761 'eureka-server' 90 | Out-Null
    } elseif ($name -eq 'config-server') {
        Wait-Port 8888 'config-server' 60 | Out-Null
        Start-Sleep -Seconds 3
    } else {
        # Stagger DB-backed services so they don't open their pools against MySQL all at once
        # (cold-start handshake burst → server error 1159 "Got timeout reading communication packets").
        Start-Sleep -Seconds 2
    }
}

# Poll each service's port until it is up, or until the timeout elapses.
# DB-backed services can take well over 40s to boot, so wait up to 150s.
$readyTimeoutSec = 150
Write-Host ""
Write-Host "Waiting for services to come online (up to ${readyTimeoutSec}s) ..." -ForegroundColor Yellow

$pending = [System.Collections.Generic.List[string]]::new()
foreach ($name in $selected) { $pending.Add($name) }
$deadline = (Get-Date).AddSeconds($readyTimeoutSec)
while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
    foreach ($name in @($pending)) {
        $port = $catalog[$name]
        try {
            $t = New-Object System.Net.Sockets.TcpClient
            $t.Connect("localhost", $port); $t.Close()
            Write-Host ("  {0,-22} port {1}  READY" -f $name, $port) -ForegroundColor Green
            [void]$pending.Remove($name)
        } catch { }
    }
    if ($pending.Count -gt 0) { Start-Sleep -Seconds 3 }
}

# Status report (only for the services we started)
Write-Host ""
Write-Host "====  Service Status  ====" -ForegroundColor Magenta
foreach ($name in $selected) {
    $port = $catalog[$name]
    if ($pending -contains $name) {
        Write-Host ("  {0,-22} port {1}  DOWN  <-- check logs\{2}.log" -f $name, $port, $name) -ForegroundColor Red
    } else {
        Write-Host ("  {0,-22} port {1}  UP" -f $name, $port) -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "  Eureka Dashboard : http://localhost:8761" -ForegroundColor White
Write-Host "  API Gateway      : http://localhost:8765" -ForegroundColor White
Write-Host "  Logs             : $LOGS\" -ForegroundColor White
Write-Host ""
Write-Host "To stop services: .\stop-all.ps1" -ForegroundColor DarkGray
