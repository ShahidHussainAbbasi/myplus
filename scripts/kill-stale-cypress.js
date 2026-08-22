#!/usr/bin/env node
/**
 * Kill ORPHANED Cypress processes before a headless run.
 *
 * ── Why this exists ──────────────────────────────────────────────────────────────────────────────
 * A Cypress run that is interrupted — a killed terminal, a timed-out CI step, a closed laptop lid —
 * regularly leaves its browser processes behind. They are invisible in day-to-day work and they
 * accumulate: this script was written after finding FIFTEEN of them from three separate days, holding
 * about 1.4 GB between them.
 *
 * The damage is not the memory, it is the misdiagnosis. A loaded machine makes Cypress slow enough to
 * trip its own timeouts, so the symptoms are:
 *
 *   - tests that took 18s taking 75s
 *   - `cy.visit()` failing with "the request failed without a response" while the server answers
 *     healthily in 23ms when asked directly
 *   - whole specs failing in their `before each` hook, every test after it reported as skipped
 *
 * Every one of those reads as an application defect. During the work that prompted this, two such
 * failures were investigated as product bugs before anyone looked at the process table. A test suite
 * whose failures cannot be trusted is worse than one that is simply red.
 *
 * ── Why an age threshold, and not "kill every Cypress process" ───────────────────────────────────
 * `cypress open` is a long-lived interactive session someone may be deliberately using in another
 * window, and stopping a colleague's debugger because you started a headless run would be its own
 * small betrayal. No headless SPEC RUN legitimately lasts an hour, so anything older than the
 * threshold is orphaned by definition, while a session started this morning survives.
 *
 * Set STALE_CYPRESS_MINUTES to change it; 0 means "kill them all" for CI, where nothing is interactive.
 *
 * ── Why it never fails the build ─────────────────────────────────────────────────────────────────
 * This is housekeeping, not a test. If the platform is unrecognised, the command is missing, or the
 * permissions are wrong, the right outcome is to say so and let the tests run — a cleanup step that
 * can block a test run has swapped one unreliable signal for another.
 */
'use strict';

const { execFileSync } = require('child_process');

const MINUTES = Number.parseInt(process.env.STALE_CYPRESS_MINUTES ?? '60', 10);
const minutes = Number.isFinite(MINUTES) && MINUTES >= 0 ? MINUTES : 60;

/** Windows: PowerShell knows each process's StartTime, so the age filter is exact. */
function killOnWindows() {
    // -NoProfile: a developer's $PROFILE can print banners or change the error preference, and this
    // has to behave the same on every machine.
    const ps = [
        '$cut = (Get-Date).AddMinutes(-' + minutes + ');',
        // StartTime throws on processes this user may not query; -ErrorAction SilentlyContinue on the
        // Where-Object keeps one inaccessible process from aborting the whole sweep.
        '$p = @(Get-Process -Name Cypress -ErrorAction SilentlyContinue |',
        '        Where-Object { $_.StartTime -lt $cut } -ErrorAction SilentlyContinue);',
        'if ($p.Count -gt 0) { $p | Stop-Process -Force -ErrorAction SilentlyContinue }',
        'Write-Output $p.Count',
    ].join(' ');
    const out = execFileSync('powershell', ['-NoProfile', '-NonInteractive', '-Command', ps], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
    });
    return Number.parseInt(String(out).trim(), 10) || 0;
}

/**
 * macOS / Linux: `etimes` is elapsed seconds, which is the age directly.
 *
 * Matched on the executable name rather than the full command line, so this cannot match the npm
 * script that invoked it — a `pkill -f cypress` here would kill its own parent.
 */
function killOnPosix() {
    let listing = '';
    try {
        listing = execFileSync('ps', ['-eo', 'pid=,etimes=,comm='], {
            encoding: 'utf8',
            stdio: ['ignore', 'pipe', 'ignore'],
        });
    } catch {
        return 0;
    }
    const cutoff = minutes * 60;
    let killed = 0;
    for (const line of listing.split('\n')) {
        const m = line.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/);
        if (!m) continue;
        const [, pid, age, comm] = m;
        if (!/cypress/i.test(comm)) continue;
        if (Number(age) < cutoff) continue;
        try {
            process.kill(Number(pid), 'SIGKILL');
            killed += 1;
        } catch {
            /* already gone, or not ours to kill */
        }
    }
    return killed;
}

try {
    const killed = process.platform === 'win32' ? killOnWindows() : killOnPosix();
    if (killed > 0) {
        console.log(
            `[e2e] cleaned up ${killed} orphaned Cypress process(es) older than ${minutes} min.`
        );
    }
} catch (err) {
    // Say what happened. A silent catch here would hide the very kind of accumulation this exists to
    // prevent, and the next person would rediscover it the same expensive way.
    console.warn(`[e2e] stale-Cypress cleanup skipped: ${err && err.message ? err.message : err}`);
}

process.exit(0);
