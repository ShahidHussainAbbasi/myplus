# Welfare + Agriculture — common-settings rollout (design)

**Goal:** make `common-settings` carry its **3rd and 4th consumers** (welfare, agriculture) and give these two thin
verticals their first owner-configurable surface — a self-rendering Configuration screen backed by real,
behaviour-wired toggles. No dead toggles (the lesson from the removed `pos.sale.negativeStockAllowed`): every flag
shipped is read by a code path.

Ties: [`domain-lifecycle-audit.md`](domain-lifecycle-audit.md) (Phase A), the education/business consumers, and the
SPI design (`SettingsCatalogProvider` + `SettingsStore`, lib carries no `@Entity`).

## Reuse (identical to education/business)
Per service, the only new code is:
1. `pom.xml` += `common-web` + `common-settings`.
2. App class: `@SpringBootApplication(exclude = CommonWebAutoConfiguration.class)` — **required**, both services own a
   `GlobalExceptionHandler` with the same default bean name as common-web's (the boot-collision gotcha). common-web
   stays on the classpath only for `ApiResponse` used by the shared `SettingsController`.
3. `entity/OrgSetting` + `repository/OrgSettingRepository` (own `org_setting` table — data ownership stays per service).
4. `config/JpaSettingsStore` (`SettingsStore` SPI — its presence activates the shared auto-config).
5. `config/<Vertical>SettingsCatalog` (`SettingsCatalogProvider` — the vertical's toggles).
6. Flyway `org_setting` migration — **welfare V5, agri V4**.
7. Inject the shared `com.myplus.common.settings.SettingsService` into the controller(s) and read `getBool(key)`.

The shared engine, REST surface (`/settings` GET/POST, owner/admin-gated), and wiring come from the library unchanged.

## Toggles (all behaviour-wired)

### Welfare (donations)
| Key | Default | Group | Behaviour when ON | Hook |
|---|---|---|---|---|
| `welfare.donation.requireDonor` | false | Donations | A donation must name a donor — `addDonation` rejects a blank `donatorId` (attribution/audit for grant-funded charities). | `DonationController.addDonation` |
| `welfare.donator.allowDuplicateNames` | false | Donors | Permit two donors with the same name (families, common names) — skips the existing same-name dedup guard. | `DonationController.addDonator` (gates the current `if (exists)` block) |

### Agriculture (income/expense per plot)
| Key | Default | Group | Behaviour when ON | Hook |
|---|---|---|---|---|
| `agri.entry.requireLand` | false | Entries | An income/expense must reference a land/plot — both add paths reject a null `landId` (enables real per-plot P&L). | `AgricultureIncome/ExpenseController.add*` |

Defaults are **off = today's behaviour**, so enabling the lib changes nothing until an owner opts in (safe rollout).

## UI (self-rendering Config screen, mirror of education/business)
- Monolith proxy: `/getConfig` (GET → service `/settings`) + `/saveConfig` (POST key/value). New proxy controllers using
  `WelfareRestClient` / `AgricultureRestClient`; the service `/settings` is the shared endpoint.
- Dashboard: a `#ConfigDiv` `formDiv` + a Configuration nav item (gated `SUPER_PRIVILEGE`, matching these dashboards'
  existing owner gating), + `showConfig/loadConfig/saveConfigToggle` in `welfare.js` / `agriculture.js`. The screen reads
  the catalog (`GenericResponse.collection`) and renders a checkbox per BOOL grouped by `group`; a toggle saves
  immediately and reverts on failure. (Same three functions as education — a future `config-screen.js` extraction can
  DRY this once a 5th consumer appears; not now.)

## Test (Cypress gate per vertical)
- `cypress/e2e/welfare/config.cy.js` — catalog served (keys present, defaults off); owner override persists (isDefault
  flips); unknown key rejected; **behaviour**: turn `welfare.donation.requireDonor` on → `addDonation` with no donor is
  refused; restore off.
- `cypress/e2e/agriculture/config.cy.js` — same shape; **behaviour**: `agri.entry.requireLand` on → add expense with no
  `landId` refused; restore off.
- These are the first real Cypress specs for either vertical (both had 1 skeleton spec — audit §6/§7 test-coverage cliff).

## Out of scope (register, don't build)
Deeper domain features flagged in the audit — welfare receipts/campaigns/pledges, agri crop/season P&L — remain phase
C. This slice only adds the configuration foundation + first honest toggles.
