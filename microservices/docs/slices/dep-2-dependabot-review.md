# DEP-2 — Dependabot review (2026-09-26) — REVIEW ONLY, paused for the three open items

36 open alerts = 30 on `home/runner/work/myplus/myplus/pom.xml` + 6 on `package-lock.json`.

## The 30 "Maven" alerts come from a STALE submitted snapshot
- GitHub's dependency graph for `master` still holds the Spring Boot 2.0 graph the deleted `maven.yml` submitted
  (SBOM: tomcat-embed-core 8.5.32, spring-webmvc 5.0.8, logback-core 1.2.3). No workflow on master submits one now
  (checked all 5). Submitted snapshots persist until REPLACED, so every new advisory keeps firing on it — the 224
  dismissed in DEP-1 were the same source; these 30 are newer advisories (June–Sept).
- ⚠ The real poms raise NO alerts at all — every Maven alert points at the snapshot. Real exposure is UNDER-reported.

## Real for current code (checked against the built jars, not the poms)
All 16 jars (monolith + 15 services) ship **jackson 2.19.0** and **logback 1.5.18** (Spring Boot 3.5.0):
| Alert | Package | Sev | Vulnerable | Fixed |
|---|---|---|---|---|
| GHSA-r7wm-3cxj-wff9 | jackson-core | high | ≥2.19.0 <2.21.4 | 2.21.4 |
| GHSA-hgj6-7826-r7m5 | jackson-databind | medium | ≥2.19.0 <2.21.4 | 2.21.4 |
| GHSA-5jmj-h7xm-6q6v | jackson-databind | medium | ≥2.19.0 <2.21.5 | 2.21.5 |
| GHSA-p47f-322f-whfh | logback-core | low | ≤1.5.32 | 1.5.33 |
| GHSA-jhq6-gfmj-v8fx | logback-core | low | <1.5.34 | 1.5.34 |
The other 25 (Spring ≤5.3.39, Tomcat 8.5/9.0, Security ≤5.7, Data ≤2.7) do NOT apply to Spring 6.2 / Tomcat 10.1.

## Proposed (needs consent)
1. Spring Boot 3.5.0 → **3.5.16** (manages jackson 2.21.4, logback 1.5.34, tomcat 10.1.55, spring 6.2.19) +
   `jackson-bom.version` 2.21.5 for GHSA-5jmj. Platform BOM bump, not per-library pins; full `mvn test` + regression.
2. Replace the stale snapshot (an empty snapshot under its old job correlator, or a real submission from CI) so
   Dependabot sees the ACTUAL graph; then dismiss the 25 inapplicable alerts as `not_used`.
3. npm (6, all dev-only via cypress 13.17.0 → @cypress/request / extract-zip / tmp): no production exposure;
   form-data 4.0.5→4.0.6 may resolve with a lock refresh; the rest need a Cypress major — won't-fix as in DEP-1.
