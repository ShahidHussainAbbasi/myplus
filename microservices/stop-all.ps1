# MyPlus Microservices - Stop Services
# Finds and kills the java processes running the selected myplus JARs.
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

# Known services (jar name = <service>-1.0.0-SNAPSHOT.jar)
$order = @(
    'eureka-server', 'config-server', 'api-gateway', 'auth-service',
    'inventory-service', 'business-service', 'education-service',
    'welfare-service', 'agriculture-service', 'pharma-service',
    'marketplace-service', 'campaign-service', 'analytics-service'
)

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

# Get-CimInstance works on both Windows PowerShell 5.1 and PowerShell 7+.
$javaProcs = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue
if (-not $javaProcs) {
    Write-Host "No java processes found." -ForegroundColor Yellow
    exit 0
}

# Build the set of jar markers we want to stop.
$jars = $selected | ForEach-Object { "$_-1.0.0-SNAPSHOT.jar" }

$stopped = 0
foreach ($proc in $javaProcs) {
    $cmdLine = $proc.CommandLine
    if (-not $cmdLine) { continue }
    foreach ($jar in $jars) {
        if ($cmdLine -like "*$jar*") {
            Write-Host "  Stopping PID $($proc.ProcessId) ($jar) ..." -ForegroundColor Cyan
            Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
            $stopped++
            break
        }
    }
}

if ($stopped -eq 0) {
    Write-Host "No matching MyPlus service processes found for the selection." -ForegroundColor Yellow
} else {
    Write-Host "`nStopped $stopped service(s)." -ForegroundColor Green
}
