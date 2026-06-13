# MyPlus Microservices - Stop Services
# Stops the selected services regardless of HOW they were launched:
#   * java -jar <service>-1.0.0-SNAPSHOT.jar   (start-all.ps1 style)
#   * mvn spring-boot:run                       (forks a java app on the service port)
#   * IDE / debug runs
# It works by finding the process that OWNS the service's port, plus a jar-name
# fallback, and also cleans up the parent Maven wrapper when present.
#
# Usage (from microservices\ folder):
#   .\stop-all.ps1 all                          # stop every service
#   .\stop-all.ps1 auth-service                 # stop a single service
#   .\stop-all.ps1 auth-service,business-service    # stop a list
#   .\stop-all.ps1 auth-service business-service    # space-separated also works
#   .\stop-all.ps1                              # no arg -> prompts for a list

param(
    [Parameter(Position = 0)]
    [string[]]$Services
)

# Service -> port (must match start-all.ps1). Order = canonical start order.
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

# --- Resolve the requested selection ---
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

$requested = $Services | ForEach-Object { $_.Trim().ToLower() }
if ($requested -contains 'all') {
    $selected = $order
} else {
    $selected = $order | Where-Object { $requested -contains $_ }
    $unknown = $requested | Where-Object { $order -notcontains $_ }
    foreach ($u in $unknown) {
        Write-Host ("WARNING: unknown service '{0}' (ignored). Valid: {1}" -f $u, ($order -join ', ')) -ForegroundColor Yellow
    }
}
if (-not $selected -or $selected.Count -eq 0) {
    Write-Host "No valid services to stop." -ForegroundColor Red
    exit 1
}

Write-Host "`n=== Stopping MyPlus Microservices ===" -ForegroundColor Magenta
Write-Host ("Stopping: {0}" -f ($selected -join ', ')) -ForegroundColor White

# Snapshot all java processes once (for jar-name fallback + parent lookups).
$javaProcs = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue

# Find the PID listening on a TCP port (or $null).
function Get-PortOwnerPid {
    param([int]$Port)
    try {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop |
                Select-Object -First 1
        if ($conn) { return [int]$conn.OwningProcess }
    } catch { }
    return $null
}

# Accumulate "pid -> reason" so we kill each process once.
$toKill = @{}

foreach ($name in $selected) {
    $jar  = "$name-1.0.0-SNAPSHOT.jar"
    $port = $catalog[$name]

    # 1) Whoever owns the service port (covers mvn spring-boot:run, jar, IDE runs).
    $ownerPid = Get-PortOwnerPid -Port $port
    if ($ownerPid) {
        $toKill[$ownerPid] = "$name (port $port)"

        # If its parent is the Maven 'spring-boot:run' wrapper, stop that too so
        # Maven doesn't linger or try to restart the fork.
        $owner = $javaProcs | Where-Object { $_.ProcessId -eq $ownerPid } | Select-Object -First 1
        if (-not $owner) {
            $owner = Get-CimInstance Win32_Process -Filter "ProcessId = $ownerPid" -ErrorAction SilentlyContinue
        }
        if ($owner -and $owner.ParentProcessId) {
            $parent = Get-CimInstance Win32_Process -Filter "ProcessId = $($owner.ParentProcessId)" -ErrorAction SilentlyContinue
            if ($parent -and $parent.CommandLine -and $parent.CommandLine -like '*spring-boot:run*') {
                $toKill[[int]$parent.ProcessId] = "$name (mvn spring-boot:run wrapper)"
            }
        }
    }

    # 2) Fallback: jar-name match on the command line (start-all.ps1 style).
    foreach ($proc in $javaProcs) {
        if ($proc.CommandLine -and $proc.CommandLine -like "*$jar*") {
            $toKill[[int]$proc.ProcessId] = "$name ($jar)"
        }
    }
}

if ($toKill.Count -eq 0) {
    Write-Host "No matching MyPlus service processes found for the selection." -ForegroundColor Yellow
    exit 0
}

$stopped = 0
foreach ($procId in $toKill.Keys) {
    Write-Host ("  Stopping PID {0} - {1} ..." -f $procId, $toKill[$procId]) -ForegroundColor Cyan
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    if ($?) { $stopped++ }
}

Write-Host "`nStopped $stopped process(es)." -ForegroundColor Green
