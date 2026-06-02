# MyPlus Microservices - Stop All Services
# Finds and kills all java processes running the myplus JARs

$services = @(
    "eureka-server-1.0.0-SNAPSHOT.jar",
    "config-server-1.0.0-SNAPSHOT.jar",
    "api-gateway-1.0.0-SNAPSHOT.jar",
    "auth-service-1.0.0-SNAPSHOT.jar",
    "business-service-1.0.0-SNAPSHOT.jar",
    "education-service-1.0.0-SNAPSHOT.jar"
)

Write-Host "`n=== Stopping MyPlus Microservices ===" -ForegroundColor Magenta

$javaProcs = Get-WmiObject Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue
if (-not $javaProcs) {
    Write-Host "No java processes found." -ForegroundColor Yellow
    exit 0
}

$stopped = 0
foreach ($proc in $javaProcs) {
    $cmdLine = $proc.CommandLine
    foreach ($jar in $services) {
        if ($cmdLine -like "*$jar*") {
            Write-Host "  Stopping PID $($proc.ProcessId) ($jar) ..." -ForegroundColor Cyan
            Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
            $stopped++
            break
        }
    }
}

if ($stopped -eq 0) {
    Write-Host "No matching MyPlus service processes found." -ForegroundColor Yellow
} else {
    Write-Host "`nStopped $stopped service(s)." -ForegroundColor Green
}
