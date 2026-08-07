<#
.SYNOPSIS
  Build-and-run the MyPlus stack in the ONE order that is always correct, with the checks that catch the
  failures this project actually has.

.DESCRIPTION
  Every deployment problem hit on 2026-08-05/06 was an ARTEFACT MISMATCH, not a code fault:

    * a jar containing IDE-compiled classes that throw `java.lang.Error: Unresolved compilation problem`
      at runtime (Eclipse/JDT emits those; javac never does) — `mvn package` without `clean` packages
      them because the .class is newer than the .java
    * an image built around a stale jar, so a container ran last week's code with no error anywhere
    * containers from different build generations in one stack
    * a runbook naming a compose service that did not exist
    * a hand-started container squatting a compose `container_name`

  None of those announce themselves. This script makes each one impossible or loud.

  It NEVER skips `clean`. That is the whole point — see PREFLIGHT/BUILD below.

.PARAMETER Profile
  pos (default) | pharmacy | full   — which compose profile to bring up.

.PARAMETER SkipBuild
  Reuse the jars already in target/. Only safe when you just built them.

.EXAMPLE
  .\deploy.ps1                    # POS subset
  .\deploy.ps1 -Profile full      # every module
#>
[CmdletBinding()]
param(
    [ValidateSet('pos', 'pharmacy', 'full')]
    [string]$Profile = 'pos',
    [switch]$SkipBuild,
    # Build only the modules whose sources changed since their jar was written - plus every module that
    # DEPENDS on them (-amd), because a service bundles its libraries at package time. Each selected
    # module is still `clean`ed; this narrows WHICH modules build, it never skips a clean. Typically
    # 30-60s instead of 5-10min. Falls back to a full build whenever detection is not trustworthy.
    [switch]$Changed,
    # Force every container to be replaced even when compose sees no change. Use after editing something
    # compose cannot detect (a config-server value, a mounted file). Never needs `down` first - compose
    # replaces containers in place and the mysql-data volume is untouched either way.
    [switch]$Recreate,
    # Rebuild and redeploy ONE service (or a few) instead of the whole stack. Names are compose service
    # names, which for every module match the directory name: business-service, api-gateway, monolith...
    #   .\deploy.ps1 -Service business-service
    #   .\deploy.ps1 -Service business-service,catalog-service
    # Builds the module with -am (its libraries too, so it never links against a stale one), rebuilds
    # only that image, and waits only on that container. The rest of the stack keeps running untouched.
    [string[]]$Service = @()
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$micro = $PSScriptRoot
$failed = @()

function Step($n, $m) { Write-Host "`n=== [$n] $m" -ForegroundColor Cyan }
function Ok($m) { Write-Host "    OK  $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    !!  $m" -ForegroundColor Yellow }
function Info($m) { Write-Host "    .   $m" -ForegroundColor Gray }
function Die($m) { Write-Host "`nFAILED: $m" -ForegroundColor Red; Stop-Log; exit 1 }

# Echo every external command before running it, so the console is a transcript you can replay by hand.
# When a step misbehaves you can copy the exact line out of the log rather than reconstruct it.
function Run($exe, [string[]]$cmdArgs) {
    Write-Host "    $ $exe $($cmdArgs -join ' ')" -ForegroundColor DarkCyan
    # `| Out-Host` is load-bearing. A PowerShell function returns EVERY uncaptured value, not just what
    # `return` names - so a bare `& $exe @cmdArgs` sends the command's entire stdout into the function's
    # output, and `$rc = Run ...` becomes @(all output..., exitcode). Two failures at once: the output
    # never reaches the console (a 10-minute build looks silent, and "scroll up for the [ERROR]" has
    # nothing to scroll to), and `$rc -ne 0` compares an ARRAY, which is truthy, so every run is treated
    # as a failure. Out-Host writes to the console instead of the pipeline, leaving the exit code alone.
    & $exe @cmdArgs | Out-Host
    return $LASTEXITCODE
}

# Full console output is also written to a file, because the interesting part of a failed deploy is
# usually 200 lines above where you stopped looking.
$logDir = Join-Path $micro 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$logFile = Join-Path $logDir ("deploy-{0}-{1:yyyyMMdd-HHmmss}.log" -f $Profile, (Get-Date))
$script:logging = $false
function Start-Log { try { Start-Transcript -Path $logFile -Force | Out-Null; $script:logging = $true } catch { } }
function Stop-Log { if ($script:logging) { try { Stop-Transcript | Out-Null } catch { }; $script:logging = $false } }
Start-Log

$composeArgs = if ($Profile -eq 'pos') { @() } else { @('--profile', $Profile) }

# Print something IMMEDIATELY. If this banner does not appear, the script never started (execution
# policy, wrong directory, a typo in the name) - that is a different problem from a slow step.
Write-Host ""
Write-Host "  MyPlus deploy - profile '$Profile'$(if ($SkipBuild) { ' (skipping build)' })$(if ($Recreate) { ' (force-recreate)' })" -ForegroundColor White
Write-Host "  started $(Get-Date -Format 'HH:mm:ss')  -  the build step is the long one, output will scroll" -ForegroundColor DarkGray
Write-Host "  repo   $repo"  -ForegroundColor DarkGray
Write-Host "  log    $logFile" -ForegroundColor DarkGray
Write-Host ""

# ---------------------------------------------------------------------------------------------------
Step 1 "Preflight"

# A branch that does not compile produces a stale jar, not an error. Check the version line first.
$boot = Select-String -Path "$micro\pom.xml" -Pattern '<version>([\d.]+)</version>' |
        Select-Object -First 1
if ($boot -match '4\.\d+\.\d+') {
    Die "microservices/pom.xml is on Spring Boot 4.x, which does not compile here. The deployable line is 3.5.0."
}
Ok "Spring Boot version line looks deployable"

# A service named in a runbook but missing from docker-compose.yml fails HERE in 2 seconds,
# instead of half way through a deploy.
$services = & docker compose @composeArgs config --services 2>$null
if ($LASTEXITCODE -ne 0) { Die "docker compose config failed - the compose file is not valid" }
$expected = @{ pos = 14; pharmacy = 15; full = 22 }[$Profile]
if ($services.Count -ne $expected) {
    Warn "profile '$Profile' resolves to $($services.Count) services, expected $expected"
} else {
    Ok "profile '$Profile' resolves to $($services.Count) services"
}
# Validate -Service against the profile NOW. A typo would otherwise surface as a Maven "project not
# found" three minutes in, or worse, as a compose no-op that looks like a successful deploy.
if ($Service.Count -gt 0) {
    $unknown = @($Service | Where-Object { $services -notcontains $_ })
    if ($unknown.Count -gt 0) {
        Die "unknown service(s): $($unknown -join ', ')`n       Valid names for profile '$Profile':`n       $(($services | Sort-Object) -join ', ')"
    }
    Ok "targeting $($Service.Count) service(s): $($Service -join ', ')  - the rest of the stack is left running"
}

# Print them. A missing service is far easier to spot in a list than in a count. Fixed-width columns,
# five per row, so the same service sits in the same place run to run and a gap is visible at a glance.
$sorted = @($services | Sort-Object)
for ($i = 0; $i -lt $sorted.Count; $i += 5) {
    $row = $sorted[$i..([Math]::Min($i + 4, $sorted.Count - 1))]
    Info (($row | ForEach-Object { '{0,-22}' -f $_ }) -join '')
}

# WHICH .env - there are five env-ish files in this repo and only ONE reaches compose.
#
#   microservices/.env         <- THE ONE. Compose auto-loads `.env` from the project directory.
#   microservices/.env.local   <- read by start-all.ps1 (bare JAR runs). Compose IGNORES it.
#   microservices/.env.example    template, not read
#   <repo-root>/.env.local     <- the MONOLITH's persistence.properties / mail / recaptcha
#   <repo-root>/.env.example      template, not read
#
# Verified empirically (probe var in both files; compose resolved the one from .env). Compose has NO
# .env.local convention - that is a Spring/Vite habit, and assuming it here means editing a file that
# nothing reads and wondering why the change did nothing.
$envFile = Join-Path $micro '.env'
if (-not (Test-Path $envFile)) {
    Die "microservices/.env not found - compose has no values to interpolate. Copy .env.example and fill it in."
}
Ok "env file: $envFile  ($((Get-Item $envFile).Length) bytes, modified $((Get-Item $envFile).LastWriteTime.ToString('yyyy-MM-dd HH:mm')))"

# Drift between .env and .env.local is silent and nasty: compose runs one set of secrets, start-all.ps1
# runs another, and the two stacks disagree about (say) JWT_SECRET - which presents as "login works in
# one and 401s in the other" with nothing in any log to explain it.
$envLocal = Join-Path $micro '.env.local'
if (Test-Path $envLocal) {
    function Read-EnvPairs($path) {
        $h = @{}
        Get-Content $path | Where-Object { $_ -match '^\s*([A-Z_][A-Z0-9_]*)\s*=(.*)$' } |
            ForEach-Object { if ($_ -match '^\s*([A-Z_][A-Z0-9_]*)\s*=(.*)$') { $h[$Matches[1]] = $Matches[2] } }
        return $h
    }
    $a = Read-EnvPairs $envFile
    $b = Read-EnvPairs $envLocal
    $diff = @($b.Keys | Where-Object { $a.ContainsKey($_) -and $a[$_] -ne $b[$_] })
    if ($diff.Count -gt 0) {
        Warn "these keys DIFFER between .env (used by compose) and .env.local (used by start-all.ps1):"
        $diff | ForEach-Object { Warn "    $_" }
        Warn "compose will use the .env value. Reconcile them or the two ways of running disagree."
    } else {
        Ok ".env and .env.local agree on every shared key"
    }
}

# A plain `docker run` container carries no compose labels, so Compose will not adopt it - but Docker
# still reserves the name. That is the "container name /myplus-redis is already in use" failure.
#
# Done with two --filter calls rather than `docker inspect --format '{{index .Config.Labels "..."}}'`:
# PowerShell strips the inner double quotes when handing the argument to a native exe, so Docker receives
# {{index .Config.Labels com.docker.compose.project}} and dies with 'function "com" not defined'.
# These templates contain no quotes, so there is nothing for PowerShell to mangle.
$allMyplus = @(& docker ps -a --filter 'name=myplus' --format '{{.Names}}')
$composeManaged = @(& docker ps -a --filter 'name=myplus' --filter 'label=com.docker.compose.project' --format '{{.Names}}')
$squatters = @($allMyplus | Where-Object { $_ -and $composeManaged -notcontains $_ })

if ($squatters.Count -eq 0) {
    Ok "no unmanaged containers squatting compose names"
} else {
    # Reclaim the name automatically - but only when there is provably no data to lose. A squatter is
    # never adopted by compose, so leaving it in place means compose silently runs WITHOUT that service:
    # myplus-redis sat here running and unmanaged while compose had no redis at all and the gateway's
    # demo-quota limiter failed open, logging nothing.
    #
    # `{{range .Mounts}}` carries no double quotes, so PowerShell has nothing to strip (see above).
    foreach ($s in $squatters) {
        $mounts = @(& docker inspect $s --format '{{range .Mounts}}{{.Type}}={{.Name}};{{end}}') -join ''
        $vols = @($mounts -split ';' | Where-Object { $_ -match '^volume=(.+)$' } | ForEach-Object { $Matches[1] })

        # An ANONYMOUS volume is a 64-char hex id Docker invents when an image declares VOLUME and the
        # caller named nothing - `redis:7-alpine` declares VOLUME /data, so every `docker run redis` gets
        # one. Nobody chose it, nothing else references it, and it dies with the container it was made
        # for. Treating those as "data at risk" made this guard refuse to clean up the exact container it
        # exists to clean up. Only a volume somebody NAMED (mysql-data, myplus-mysql-data) is evidence of
        # deliberate state.
        $named = @($vols | Where-Object { $_ -notmatch '^[0-9a-f]{64}$' })
        $anon  = @($vols | Where-Object { $_ -match  '^[0-9a-f]{64}$' })
        if ($anon.Count -gt 0) {
            Info "$s has $($anon.Count) anonymous volume(s) - image-declared scratch space, not deliberate state"
        }

        if ($named.Count -gt 0) {
            # Named volume = somebody's data. Never destroy that to reclaim a name.
            Die @"
Container '$s' is not compose-managed AND owns named volume(s): $($named -join ', ')
       Refusing to remove it automatically - that volume may hold data.
       Inspect it, then remove by hand once you are sure:  docker rm -f $s
"@
        }

        Warn "reclaiming '$s' - not compose-managed, no named volumes (nothing to lose)"
        # -v removes the container's ANONYMOUS volumes with it. Without this they accumulate: every
        # `docker run redis` that is later removed leaves an orphaned 64-hex volume behind forever.
        # Named volumes are unaffected by -v on `docker rm` - and this branch has already proven there
        # are none.
        & docker rm -f -v $s 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { Die "could not remove '$s'; remove it by hand: docker rm -f $s" }
        Ok "removed '$s' - compose will create its own on the compose network"
    }
}

# A `java -jar target\...jar` left running holds that file open, and Windows will not let Maven delete
# it. `mvn clean` then fails with "The process cannot access the file because it is being used by
# another process" - which reads like a build error and sends you looking at the source. It is not:
# nothing is wrong with the code, a process is simply sitting on the artefact.
#
# This cost two full builds on 2026-08-06. Both stopped at marketplace-service, whose jar was held by a
# java -jar started earlier to reproduce a bean-creation error.
# The filter is deliberately narrow: the process must be `java -jar <path>` where <path> is a .jar
# living under THIS repo in a target/ directory. That is, by definition, a rebuildable build artefact
# being run by hand outside compose - safe to stop, because the next line rebuilds it anyway.
#
# It cannot match the VS Code Java language server or a Spring app launched from the IDE: those run with
# -cp / --add-modules, never `-jar <repo>\...\target\...jar`. Verified against all five java.exe
# processes running here on 2026-08-06 - only the hand-started marketplace jar qualified.
$locks = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
           ForEach-Object {
               if ($_.CommandLine -match '-jar\s+"?([^"]+?\.jar)"?(\s|$)') {
                   $jarPath = $Matches[1].Trim()
                   if ($jarPath -like "$repo*" -and $jarPath -match '\\target\\') {
                       [pscustomobject]@{ ProcessId = $_.ProcessId; Jar = $jarPath }
                   }
               }
           })

if ($locks.Count -eq 0) {
    Ok "no stray java -jar processes holding this repo's artefacts"
} else {
    # Stop them rather than printing an instruction. Leaving one running guarantees `mvn clean` fails
    # with "The process cannot access the file because it is being used by another process" - which
    # reads like a build error and sends you looking at the source. Nothing is wrong with the code; a
    # process is just sitting on the artefact. This cost two full builds on 2026-08-06.
    foreach ($p in $locks) {
        Warn "stopping PID $($p.ProcessId) - it is running $(Split-Path $p.Jar -Leaf) from target/ and would block the clean"
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            # The lock is released when the process actually exits, not when the kill is issued.
            Wait-Process -Id $p.ProcessId -Timeout 15 -ErrorAction SilentlyContinue
        } catch {
            Die "could not stop PID $($p.ProcessId): $($_.Exception.Message)`n       Stop it by hand: Stop-Process -Id $($p.ProcessId) -Force"
        }
    }

    # Prove the lock is gone rather than assume the kill worked - the whole point is not to discover it
    # four minutes into a build.
    $still = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
               Where-Object { $_.ProcessId -in $locks.ProcessId })
    if ($still.Count -gt 0) {
        Die "PID(s) $($still.ProcessId -join ', ') are still running and still hold the jar. Stop them by hand and re-run."
    }
    Ok "stopped $($locks.Count) stray java -jar process(es) - artefacts are writable again"
}

# ---------------------------------------------------------------------------------------------------
if (-not $SkipBuild) {
    Step 2 "Build jars (ALWAYS clean)"

    # `clean` is not optional. Without it Maven skips recompiling any .class newer than its .java - which
    # is exactly what an IDE language server produces. Eclipse/JDT emits class files for code that does
    # NOT compile; they throw java.lang.Error at bean-creation time instead of failing the build.
    #
    # NOT `-q`. Quiet mode prints nothing at all, so a 5-10 minute clean build of 22 modules looks like a
    # hung terminal and there is no way to tell progress from a stall. `-B` (batch) gives clean,
    # non-ANSI, line-per-module output that is readable live and greppable afterwards.
    # ---- which modules actually need building -------------------------------------------------------
    # A module is dirty when anything under it (src/, pom.xml, and the DIRECTORIES themselves - a deleted
    # file changes only its parent directory's mtime) is newer than the jar in its target/.
    #
    # Deliberately NOT git-based: most work here is uncommitted, so `git diff` would report almost
    # everything as changed and save nothing. Timestamps describe the actual build state.
    $mvnArgs = @('-B', '-DskipTests', 'clean', 'install')
    $scope = 'full reactor'

    if ($Service.Count -gt 0) {
        # -am (also-make) builds the named module's LIBRARIES as well. Without it a service links against
        # whatever common-* jar happens to be in ~/.m2, which may predate a library change - the exact
        # artefact mismatch this script exists to prevent. -am costs a few seconds and removes the risk.
        # `monolith` is not a reactor module (it builds from the repo root), so it is filtered out here
        # and handled by the monolith block below.
        $reactorTargets = @($Service | Where-Object { $_ -ne 'monolith' })
        if ($reactorTargets.Count -gt 0) {
            $mvnArgs = @('-B', '-DskipTests', 'clean', 'install', '-pl', ($reactorTargets -join ','), '-am')
            $scope = "$($reactorTargets -join ', ') + libraries"
        } else {
            $mvnArgs = $null      # monolith only - nothing to do in the reactor
            $scope = 'monolith only'
        }
    }
    elseif ($Changed) {
        # Skip <packaging>pom</packaging> modules (service-parent). They produce no jar, so a
        # "jar is missing" test would mark them dirty on every single run - and with -amd that pulls in
        # every module that inherits from them, i.e. the whole reactor. Incremental would never help.
        $modules = @(Get-ChildItem -Path $micro -Directory |
                     Where-Object { Test-Path (Join-Path $_.FullName 'pom.xml') } |
                     Where-Object {
                         (Select-String -Path (Join-Path $_.FullName 'pom.xml') `
                                        -Pattern '<packaging>\s*pom\s*</packaging>' -Quiet) -ne $true
                     })
        $rootPom = Get-Item (Join-Path $micro 'pom.xml')
        $dirty = @()
        $reason = @{}

        foreach ($m in $modules) {
            $jar = Get-ChildItem -Path (Join-Path $m.FullName 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -notmatch 'sources|javadoc' } |
                   Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if (-not $jar) { $dirty += $m.Name; $reason[$m.Name] = 'no jar - never built'; continue }

            # Files AND directories, so a deletion counts too.
            $newest = Get-ChildItem -Path (Join-Path $m.FullName 'src'), (Join-Path $m.FullName 'pom.xml') `
                                    -Recurse -Force -ErrorAction SilentlyContinue |
                      Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if ($newest -and $newest.LastWriteTime -gt $jar.LastWriteTime) {
                $dirty += $m.Name
                $reason[$m.Name] = "$($newest.Name) ($($newest.LastWriteTime.ToString('HH:mm')) > jar $($jar.LastWriteTime.ToString('HH:mm')))"
            }
        }

        # The parent pom carries dependency versions and plugin config - a change there can alter every
        # module's output, and no per-module timestamp would reveal it. Fall back to a full build.
        $rootChanged = @($modules | ForEach-Object {
            Get-ChildItem -Path (Join-Path $_.FullName 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' }
        } | Where-Object { $rootPom.LastWriteTime -gt $_.LastWriteTime }).Count -gt 0

        if ($rootChanged) {
            Warn "microservices/pom.xml is newer than some jars - it sets versions for every module, so a"
            Warn "partial build could not be trusted. Falling back to the FULL reactor."
        } elseif ($dirty.Count -eq 0) {
            Ok "no module sources changed since their jars were written - skipping the reactor build"
            $mvnArgs = $null
        } else {
            Info "dirty modules ($($dirty.Count) of $($modules.Count)):"
            $dirty | ForEach-Object { Info ("    {0,-24} {1}" -f $_, $reason[$_]) }
            # -amd = also-make-dependents. A service bundles its libraries INTO its jar at package time,
            # so a changed library leaves every dependent service holding a stale copy. Rebuilding only
            # the library would produce exactly the artefact mismatch this script exists to prevent.
            $mvnArgs = @('-B', '-DskipTests', 'clean', 'install', '-pl', ($dirty -join ','), '-amd')
            $scope = "$($dirty.Count) changed module(s) + dependents"
        }
    }

    if ($mvnArgs) {
        if ($scope -eq 'full reactor') {
            Warn "clean build of the full reactor - expect 5-10 minutes, module names will scroll past"
        } else {
            Info "building $scope - each one is still CLEANed, only the module list is narrowed"
        }
        $t0 = Get-Date

        Push-Location $micro
        $rc = Run 'mvn' $mvnArgs
        Pop-Location
    } else {
        $rc = 0
        $t0 = Get-Date
    }
    if ($rc -ne 0) { Die "microservices build failed - do NOT build images on top of this. Scroll up for the first [ERROR]." }
    Ok ("microservices reactor built in {0:mm}m{0:ss}s" -f ([datetime]::MinValue + ((Get-Date) - $t0)))

    # The monolith is a single module, so the same test applies: is anything under src/ newer than the jar?
    # With -Service it builds only when explicitly named - rebuilding the UI on every backend tweak is
    # minutes of waiting for an artefact nothing asked for.
    $buildMonolith = $true
    if ($Service.Count -gt 0) { $buildMonolith = $Service -contains 'monolith' }
    if ($buildMonolith -and $Changed) {
        $monoJar = Get-ChildItem -Path (Join-Path $repo 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -notmatch 'sources|javadoc' } |
                   Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($monoJar) {
            $newest = Get-ChildItem -Path (Join-Path $repo 'src'), (Join-Path $repo 'pom.xml') `
                                    -Recurse -Force -ErrorAction SilentlyContinue |
                      Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if (-not $newest -or $newest.LastWriteTime -le $monoJar.LastWriteTime) {
                Ok "monolith unchanged since $($monoJar.LastWriteTime.ToString('HH:mm')) - skipping"
                $buildMonolith = $false
            } else {
                Info "monolith dirty: $($newest.Name) ($($newest.LastWriteTime.ToString('HH:mm')) > jar $($monoJar.LastWriteTime.ToString('HH:mm')))"
            }
        }
    }

    if ($buildMonolith) {
        $t1 = Get-Date
        Push-Location $repo
        $rc = Run 'mvn' @('-B','-DskipTests','clean','package')
        Pop-Location
        if ($rc -ne 0) { Die "monolith build failed - scroll up for the first [ERROR]." }
        Ok ("monolith built in {0:mm}m{0:ss}s" -f ([datetime]::MinValue + ((Get-Date) - $t1)))
    }
}

# ---------------------------------------------------------------------------------------------------
Step 3 "Verify the jars are javac output, not IDE output"

# The check that would have caught marketplace-service on 2026-08-06 before it ever reached a container.
#
# Scans EVERY jar, including ones -Changed decided not to rebuild. That is the point: an incremental
# build is a heuristic, and the jars it skipped are exactly the ones nothing else is checking. This step
# costs a second or two and is what makes -Changed safe to use.
$scan = @'
import sys, zipfile
bad = []
for jar in sys.argv[1:]:
    try: z = zipfile.ZipFile(jar)
    except Exception: continue
    for e in z.namelist():
        if e.startswith('BOOT-INF/classes/') and e.endswith('.class'):
            if b'Unresolved compilation problem' in z.read(e):
                bad.append(jar + ' :: ' + e.replace('BOOT-INF/classes/', ''))
print('\n'.join(bad))
'@
$scanFile = Join-Path $env:TEMP 'myplus-scan-jars.py'
Set-Content -Path $scanFile -Value $scan -Encoding utf8

# -Unique because microservices/ lives UNDER the repo root, so recursing both paths finds every
# microservice jar twice (63 hits for 32 jars). Harmless but it doubles the work of the scan.
$jars = Get-ChildItem -Path $micro, $repo -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\target\\' -and $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -ExpandProperty FullName -Unique
$broken = & python $scanFile @jars
if ($broken) {
    Write-Host $broken -ForegroundColor Red
    Die @"
Those classes were compiled by the IDE, not by Maven. Eclipse/JDT writes a .class for code that does not
compile; it throws `java.lang.Error: Unresolved compilation problem` when Spring instantiates the bean.
Fix: close the IDE (or let it finish indexing), then re-run this script WITHOUT -SkipBuild.
"@
}
Ok "no IDE-compiled classes in $($jars.Count) jars (including the monolith)"

# Every Dockerfile is COPY target/*.jar - a missing jar makes `docker build` fail with a confusing
# "COPY failed" rather than "you have not built this". The monolith is the easy one to miss because it
# builds from the REPO ROOT, not the reactor, and -SkipBuild skips both.
$required = @{ 'monolith' = (Join-Path $repo 'target') }
foreach ($svc in @('api-gateway', 'auth-service', 'business-service')) {
    $required[$svc] = Join-Path $micro "$svc\target"
}
foreach ($svc in $required.Keys) {
    $j = Get-ChildItem -Path $required[$svc] -Filter '*.jar' -ErrorAction SilentlyContinue |
         Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    if (-not $j) {
        Die "$svc has no jar in $($required[$svc]) - `docker build` would fail on COPY. Re-run without -SkipBuild."
    }
    Info ("{0,-18} jar {1}  ({2:yyyy-MM-dd HH:mm})" -f $svc, $j.Name, $j.LastWriteTime)
}

# ---------------------------------------------------------------------------------------------------
Step 4 "Build images and start"

# `up -d --build` replaces any container whose image or config changed - in place, no `down`, and the
# mysql-data volume survives regardless. `down` is never needed here and would cost you the stack.
$upArgs = @('up', '-d', '--build')
if ($Recreate) { $upArgs += '--force-recreate' }
# Naming services limits `up` to them. Compose starts their dependencies if any are down, but leaves
# every already-running container alone - so a single-service redeploy does not disturb the stack.
if ($Service.Count -gt 0) { $upArgs += $Service }

# NOTE: deliberately NOT --remove-orphans. Same project name, different compose files: the observability
# stack (grafana/loki/tempo/prometheus/otel) is part of project `microservices` but defined in
# observability/docker-compose.observability.yml, so --remove-orphans here DELETES all of it.

Push-Location $micro
$rc = Run 'docker' (@('compose') + $composeArgs + $upArgs)
Pop-Location

# `up` aborting is NOT proof of failure. When the gateway misses its health window compose gives up and
# never starts the monolith, while every service comes up fine a minute later. Verify, do not assume.
if ($rc -ne 0) { Warn "compose exited $rc - verifying actual state before believing it" }

# ---------------------------------------------------------------------------------------------------
Step 5 "Wait for health"

# Print WHICH services are outstanding on every poll, not just a count. During a staged start the set
# should shrink in waves (core -> gateway -> verticals -> monolith); a set that stops shrinking is the
# real signal, and you cannot see that from "17/22".
$deadline = (Get-Date).AddMinutes(8)
$startWait = Get-Date
do {
    Start-Sleep -Seconds 15
    $rows = @(& docker compose @composeArgs ps --format '{{.Service}}|{{.State}}|{{.Health}}' 2>$null)
    # With -Service, wait only on what was redeployed. Judging a targeted redeploy by the health of
    # containers it never touched would report someone else's problem as this deploy's failure.
    if ($Service.Count -gt 0) {
        $rows = @($rows | Where-Object { $Service -contains ($_ -split '\|')[0] })
    }
    $unhealthy = @($rows | Where-Object { $_ -notmatch '\|healthy$' })
    $elapsed = ((Get-Date) - $startWait).ToString('mm\:ss')
    Write-Host ("    [{0}] {1}/{2} healthy" -f $elapsed, ($rows.Count - $unhealthy.Count), $rows.Count) -ForegroundColor Gray
    foreach ($u in $unhealthy) {
        $p = $u -split '\|'
        Info ("waiting: {0,-24} state={1} health={2}" -f $p[0], $p[1], $(if ($p[2]) { $p[2] } else { '(none)' }))
    }
} while ($unhealthy.Count -gt 0 -and (Get-Date) -lt $deadline)

if ($unhealthy.Count -gt 0) {
    Warn "still not healthy: $($unhealthy -join ', ')"
    Warn "check whether they are SLOW or BROKEN: docker compose logs <svc> | Select-String 'ERROR|Caused by'"
    Warn "ordinary startup lines with 30-60s gaps = slow (fine). A stack trace = broken."
    $failed += 'health'
} else {
    Ok "all $($rows.Count) services healthy"
}

# ---------------------------------------------------------------------------------------------------
Step 6 "Check for build-generation drift"

# On 2026-08-05 business-service ran an image from 07:08 while api-gateway ran one from 11:16, in the same
# stack, because only some services were rebuilt. Compose reported everything healthy. Nothing was wrong
# with any single container - they just were not the same build. Catch that here rather than in behaviour.
$stamps = @{}
foreach ($svc in $services) {
    $cid = (& docker compose @composeArgs ps -q $svc 2>$null | Select-Object -First 1)
    if (-not $cid) { continue }
    $img = & docker inspect $cid --format '{{.Image}}' 2>$null
    $created = & docker inspect $img --format '{{.Created}}' 2>$null
    if ($created) { $stamps[$svc] = [datetime]::Parse($created).ToUniversalTime() }
}
# Ignore the pinned third-party images (mysql/redis) - they are pulled, not built here, and are years old
# by design; only locally built images should share a generation.
$built = $stamps.GetEnumerator() | Where-Object { $_.Key -notin @('mysql', 'redis') }
if ($built) {
    $newest = ($built | Measure-Object -Property Value -Maximum).Maximum
    $stale = @($built | Where-Object { ($newest - $_.Value).TotalMinutes -gt 30 })
    # Always print every timestamp, not only the offenders - seeing the whole set is what makes a
    # 4-hour-old outlier obvious, and it is the record you want when comparing local against the VPS.
    foreach ($e in ($built | Sort-Object Value)) {
        $age = [int]($newest - $e.Value).TotalMinutes
        Info ("{0,-24} image built {1:yyyy-MM-dd HH:mm} UTC{2}" -f $e.Key, $e.Value, $(if ($age -gt 30) { "   <-- $age min behind" } else { '' }))
    }
    if ($stale.Count -gt 0) {
        # With -Service this is EXPECTED, not a fault: you rebuilt one image, so it is newer than the
        # rest by design. Report it as information, and do not fail the run over it.
        if ($Service.Count -gt 0) {
            Info "the gap is expected - only $($Service -join ', ') was rebuilt"
        } else {
            Warn "$($stale.Count) service(s) run an image 30+ min older than the newest - different build generations in one stack"
            Warn "re-run with -Recreate to replace them (no 'down' needed, no data lost)"
            $failed += 'drift'
        }
    } else {
        Ok "all locally built images are from the same generation"
    }
}

Step 7 "Verify schemas actually migrated"

# A container starts happily on a stale jar and reports nothing. The schema version is the backstop.
# NOT MAX(version): flyway_schema_history.version is a VARCHAR, so MAX() compares lexically and a
# database at V36 reports '9'. Order by installed_rank, which is an integer and the true apply order.
#
# Keyed by SERVICE, not database, so -Service can check just the one it redeployed. A service that owns
# no schema (api-gateway, monolith, eureka...) simply has no entry and is skipped.
$schemaOf = @{
    'business-service'  = @{ db = 'myplusdb';           v = 36 }
    'catalog-service'   = @{ db = 'myplusdb_catalog';   v = 8  }
    'inventory-service' = @{ db = 'myplusdb_inventory'; v = 5  }
    'auth-service'      = @{ db = 'myplusdb_auth';      v = 5  }
    'finance-service'   = @{ db = 'myplusdb_finance';   v = 4  }
    'party-service'     = @{ db = 'myplusdb_party';     v = 3  }
}
if ($Profile -ne 'pos') { $schemaOf['pharma-service'] = @{ db = 'myplusdb_pharma'; v = 6 } }

$schemaTargets = if ($Service.Count -gt 0) {
    @($Service | Where-Object { $schemaOf.ContainsKey($_) })
} else { @($schemaOf.Keys) }

if ($Service.Count -gt 0 -and $schemaTargets.Count -eq 0) {
    Ok "$($Service -join ', ') owns no database - nothing to verify"
}

$expectSchema = @{}
foreach ($s in $schemaTargets) { $expectSchema[$schemaOf[$s].db] = $schemaOf[$s].v }

# Read the password in PowerShell and pass each argument separately. The earlier form nested a `sh -c`
# inside a PowerShell string inside a native call - three levels of quoting, and PowerShell mangles the
# innermost pair exactly as it did with the inspect template above.
$dbpw = (Select-String -Path (Join-Path $micro '.env') -Pattern '^\s*DB_PASSWORD=(.*)$' |
         Select-Object -First 1).Matches.Groups[1].Value
if ([string]::IsNullOrWhiteSpace($dbpw)) { Warn "DB_PASSWORD not found in .env - skipping schema checks"; $dbpw = $null }

foreach ($db in $expectSchema.Keys) {
    if (-not $dbpw) { break }
    $q = "SELECT version FROM $db.flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;"

    # MYSQL_PWD via `exec -e`, NOT `-p<pw>` on the argv. `-p` makes the client print
    # "[Warning] Using a password on the command line interface can be insecure." to STDERR, and Windows
    # PowerShell 5.1 wraps ANY native stderr line in a NativeCommandError ErrorRecord - which under
    # $ErrorActionPreference='Stop' aborts the script on a message that is not an error at all.
    # Setting the password in the environment removes the warning at source instead of filtering it,
    # and keeps it out of the container's process list.
    $got = & docker compose @composeArgs exec -T -e "MYSQL_PWD=$dbpw" mysql mysql -uroot -N -e $q
    $got = ($got | Where-Object { $_ -and $_ -notmatch 'Warning' } | Select-Object -First 1)
    if ("$got".Trim() -ne "$($expectSchema[$db])") {
        Warn "$db is at '$got', expected $($expectSchema[$db]) - that service is running a STALE jar"
        $failed += "schema:$db"
    } else {
        Ok "$db at V$got"
    }
}

# ---------------------------------------------------------------------------------------------------
Step 8 "Summary"

if ($failed.Count -eq 0) {
    Write-Host "`nStack is up and verified. Open http://localhost:8080" -ForegroundColor Green
    Write-Host "Full console log: $logFile" -ForegroundColor DarkGray
    if ($Profile -ne 'pos') {
        Write-Host "Pharmacy: run the clinical-flag backfill once - DEPLOY-POS-RETAIL.md section 9." -ForegroundColor Yellow
    }
    Stop-Log
} else {
    Write-Host "`nUp, but with warnings: $($failed -join ', ')" -ForegroundColor Yellow
    Write-Host "Do not treat this as a successful deploy until they are explained." -ForegroundColor Yellow
    Write-Host "Full console log: $logFile" -ForegroundColor DarkGray
    Stop-Log
    exit 1
}
