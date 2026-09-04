/**
 * D-6 — NOTHING MAY FAIL SILENTLY.
 *
 * Design:   microservices/docs/slices/d6-undelivered-outbox-design.md
 * Analysis: microservices/docs/slices/d6-undelivered-outbox-analysis.md
 * Origin:   E5 ruling D-6, the one ruling from that slice that did not reach the code.
 *
 * ── What this gates, and why it stopped being a convenience ─────────────────────────────────────
 * Every service delivers its events through a transactional outbox, and `OutboxRelay` dead-letters a row
 * at 20 attempts. Nothing then looks at it again: no count anywhere, no way to re-send, no screen.
 *
 * Reviewing all seven outboxes found 57 dead-lettered GENERAL-LEDGER events worth PKR 137,510 —
 * `myplusdb_education.gl_outbox` holds 56 rows, every one FAILED and none POSTED, so not one education fee
 * has ever reached the books. They were found by going looking. That is the defect this slice closes: not
 * the failures themselves, but that a person had to go looking.
 *
 * ── ⚠ THIS SPEC SEEDS ITS OWN FAILED ROW, AND MUST ─────────────────────────────────────────────
 * The 57 real rows are lost accounting. A spec that re-drove them would have done the operator's job
 * without the operator, into a live ledger, with nobody deciding it. `feedback_fixture_eligibility`:
 * existence is not eligibility. Case 3 seeds a row that is safe to replay and asserts on that.
 *
 * ── ⚠ Assert the ENVELOPE, never the HTTP status, on the proxied writes ────────────────────────
 * A refusal arrives as 200 with `success:false`. The exception is case 7, where @PreAuthorize refuses and
 * it is a real 403.
 *
 * ── Accounts ────────────────────────────────────────────────────────────────────────────────────
 *   admin@myplus.com          the platform operator (ROLE_ADMIN, never ADMIN_PRIVILEGE)
 *   owner.business@           a tenant owner — must be refused
 */

const GW = 'http://localhost:8765'
const OPERATOR = 'admin@myplus.com'
const OPERATOR_PW = Cypress.env('adminPassword') || 'Admin@2025!'
const OWNER = 'owner.business@myplus.com'
const DEMO_PW = 'Demo@2025!'

const PORTAL = '/platformDashboard'
const RUN = `D6-${Date.now()}`

/** The service and table this spec seeds into. catalog's audit outbox is the smallest and carries no money. */
const SVC = 'catalog'
const TABLE = 'audit_outbox'

const gwLogin = (email, password) =>
  cy
    .request({
      method: 'POST',
      url: `${GW}/api/auth/login`,
      headers: { 'Content-Type': 'application/json' },
      body: { email, password },
      failOnStatusCode: false,
    })
    .then((r) => {
      expect(r.status, `gateway login ${email}: ${JSON.stringify(r.body)}`).to.eq(200)
      const token = r.body && r.body.data && r.body.data.accessToken
      expect(token, `no accessToken for ${email}`).to.be.a('string')
      return token
    })

const health = (token, svc) =>
  cy.request({
    method: 'GET',
    url: `${GW}/api/${svc}/outbox-health`,
    headers: { Authorization: `Bearer ${token}` },
    failOnStatusCode: false,
  })

const redrive = (token, svc, body) =>
  cy.request({
    method: 'POST',
    url: `${GW}/api/${svc}/outbox-health/redrive`,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body,
    failOnStatusCode: false,
  })

/** The count of FAILED rows in one table, from the health payload. */
const failedIn = (body, table) => {
  const rows = (body && body.data && body.data.tables) || []
  const hit = rows.find((t) => t.table === table)
  return hit ? hit.failed : null
}

describe('D-6 — an undelivered record is visible, and can be sent again', () => {
  let operatorToken = null
  /** The row this spec seeds and re-drives. Never one of the 57 real ones. */
  let seededId = null

  before(() => {
    gwLogin(OPERATOR, OPERATOR_PW).then((t) => {
      operatorToken = t
    })
  })

  // ── 1. the count exists at all ──────────────────────────────────────────────────────────────────

  it('⭐ 1 — a service reports its outbox health, per table, with reasons', () => {
    /*
     * THE CASE. On 16 August 57 general-ledger events dead-lettered and nothing anywhere said so; they were
     * found weeks later by someone reading the database directly. This endpoint is what would have said it.
     */
    health(operatorToken, SVC).then((r) => {
      expect(r.body && r.body.success, `outbox health: ${JSON.stringify(r.body)}`).to.eq(true)
      const tables = r.body.data.tables
      expect(tables, 'every outbox this service owns is reported').to.be.an('array').and.not.be.empty

      const t = tables.find((x) => x.table === TABLE)
      expect(t, `${SVC} must report ${TABLE}`).to.exist
      expect(t.pending, 'pending is a number, not absent').to.be.a('number')
      expect(t.failed, 'and so is failed').to.be.a('number')
      // The field that turns a number into a diagnosis: 57 failures sharing two messages is exactly the
      // shape where one fix clears everything, and a bare count says none of that.
      expect(t.reasons, 'reasons is always an array, even when empty').to.be.an('array')
    })
  })

  it('2 — education\'s general-ledger outbox is reported too, not just audit', () => {
    /*
     * ⚠ The whole finding of the analysis is that this mechanism drops GL events, not only audit ones. A
     * build that reported audit outboxes and quietly skipped gl_outbox would pass case 1 and hide the 56
     * rows that made this slice worth doing.
     *
     * Asserted on the SHAPE, not on the number 56 — those rows are expected to be re-driven one day, and a
     * spec pinned to their existence would go red when the operator finally does their job.
     */
    health(operatorToken, 'education').then((r) => {
      expect(r.body && r.body.success, `education health: ${JSON.stringify(r.body)}`).to.eq(true)
      const names = r.body.data.tables.map((t) => t.table)
      expect(names, 'education owns three outboxes and all three are reported')
        .to.include.members(['audit_outbox', 'gl_outbox', 'notify_outbox'])
    })
  })

  // ── 3. the round trip ───────────────────────────────────────────────────────────────────────────

  it('⭐ 3 — a seeded FAILED row can be re-driven, and the count falls', () => {
    /*
     * The round trip, not just the warning. A count nobody can act on is a nag; ONB-3's own lesson was that
     * a warning an operator cannot act on is advice, not a feature.
     *
     * ⚠ SEEDED, never one of the 57. Those are real lost accounting: replaying them writes to a live ledger
     * and is the operator's decision, taken once, with a reason. A spec must not make it for them.
     */
    cy.request({
      method: 'POST',
      url: `${GW}/api/${SVC}/outbox-health/seed-failed`,
      headers: { Authorization: `Bearer ${operatorToken}`, 'Content-Type': 'application/json' },
      body: { table: TABLE, reason: `${RUN} gate fixture` },
      failOnStatusCode: false,
    }).then((r) => {
      expect(r.body && r.body.success, `seed a failed row: ${JSON.stringify(r.body)}`).to.eq(true)
      seededId = r.body.data.id
      expect(seededId, 'the seeder returns the row it made').to.be.a('number')
    })

    // Before-state is the OPPOSITE: the row is genuinely FAILED, or "delivered afterwards" proves nothing.
    cy.then(() =>
      health(operatorToken, SVC).then((r) => {
        expect(failedIn(r.body, TABLE), 'the seeded row is counted as failed').to.be.greaterThan(0)
      }),
    )

    cy.then(() =>
      redrive(operatorToken, SVC, { table: TABLE, ids: [seededId], reason: `${RUN} re-drive` }).then((r) => {
        expect(r.body && r.body.success, `re-drive: ${JSON.stringify(r.body)}`).to.eq(true)
        expect(r.body.data.reset, 'it says how many it moved').to.eq(1)
      }),
    )

    // The relay picks PENDING rows up on its own. Poll rather than cy.wait — a fixed wait encodes whichever
    // timing this machine happened to have.
    const untilDelivered = (attempts) =>
      health(operatorToken, SVC).then((r) => {
        const t = (r.body.data.tables || []).find((x) => x.table === TABLE)
        if (t && t.pending === 0) return
        if (attempts <= 1) throw new Error(`row ${seededId} never left PENDING: ${JSON.stringify(t)}`)
        return cy.wait(1000, { log: false }).then(() => untilDelivered(attempts - 1))
      })
    cy.then(() => untilDelivered(40))
  })

  // ── 4 + 5. a re-drive is a deliberate act ───────────────────────────────────────────────────────

  it('⭐ 4 — a re-drive without a reason is refused', () => {
    // E2's rule: a UI-only requirement is not a requirement. Re-driving into a ledger unexplained is exactly
    // the kind of thing somebody needs to be able to ask about six months later.
    redrive(operatorToken, SVC, { table: TABLE, all: true }).then((r) => {
      expect(r.body && r.body.success, `a reasonless re-drive must be refused: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
  })

  it('⭐ 5 — a re-drive naming neither ids nor all is refused, never defaulted', () => {
    /*
     * The difference between replaying ONE event to read its exception and replaying fifty-six into a live
     * ledger is one word, and it must be a word somebody typed. A default here would make the safe case and
     * the irreversible one look identical at the call site.
     */
    redrive(operatorToken, SVC, { table: TABLE, reason: `${RUN} no scope` }).then((r) => {
      expect(r.body && r.body.success, `an unscoped re-drive must be refused: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
  })

  it('⭐ 6 — an unknown table is refused, not interpolated', () => {
    /*
     * The endpoint takes a table name and the service builds SQL from it. Validated against the registry the
     * service itself declares — a re-drive that accepts an arbitrary table name is an arbitrary UPDATE.
     */
    redrive(operatorToken, SVC, { table: 'products', all: true, reason: `${RUN} not an outbox` }).then((r) => {
      expect(r.body && r.body.success, `a non-outbox table must be refused: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
  })

  // ── 7. the ladder ───────────────────────────────────────────────────────────────────────────────

  it('⭐ 7 — a tenant owner can neither read the health nor re-drive', () => {
    // ROLE_ADMIN, never ADMIN_PRIVILEGE — every owner holds the privilege inside their own organization, so
    // a privilege gate would hand every customer the platform's delivery state and a way to replay it.
    gwLogin(OWNER, DEMO_PW).then((ownerToken) => {
      health(ownerToken, SVC).then((r) => {
        expect(r.status, `an owner must not read outbox health: ${JSON.stringify(r.body)}`).to.eq(403)
      })
      redrive(ownerToken, SVC, { table: TABLE, all: true, reason: 'should not work' }).then((r) => {
        expect(r.status, 'nor re-drive').to.eq(403)
      })
    })
  })

  // ── 8. it is recorded ───────────────────────────────────────────────────────────────────────────

  it('⭐ 8 — the re-drive itself is in the audit trail, as the platform', () => {
    /*
     * A re-drive is a control-plane action: somebody outside the tenant pushed events into their books. E4
     * built exactly the axis that records that, so this reuses it rather than inventing a second trail.
     */
    cy.request({
      method: 'GET',
      url: `${GW}/api/audit?action=OUTBOX_REDRIVEN&limit=50`,
      headers: { Authorization: `Bearer ${operatorToken}` },
      failOnStatusCode: false,
    }).then((r) => {
      const rows = Array.isArray(r.body) ? r.body : []
      const hit = rows.find((e) => String(e.reason || '').indexOf(RUN) >= 0)
      expect(hit, `no OUTBOX_REDRIVEN event for this run: ${rows.length} rows`).to.exist
      expect(hit.actorType, 'recorded as the platform, not as the tenant').to.eq('PLATFORM_OPERATOR')
    })
  })

  // ── 9 + 10. the screen ──────────────────────────────────────────────────────────────────────────

  it('⭐ 9 — the console shows a strip naming the undelivered records', () => {
    /*
     * E2's lesson, and the reason this case exists: C6 shipped a policy with a green API gate and no control
     * anywhere. A count nobody can see is a column in a table.
     *
     * ⚠ The strip must say "records", not "audit records" — the whole finding is that this mechanism drops
     * general-ledger events too, and a label naming only audit would hide 57 lost postings behind a word.
     */
    cy.loginAsOperator()
    cy.visit(PORTAL)
    cy.get('[data-testid="tenant-row"]', { timeout: 15000 }).should('have.length.greaterThan', 1)

    // The 56 education GL rows make this non-zero today. Asserted as "renders when non-zero", so the case
    // stays meaningful after they are finally re-driven.
    cy.get('[data-testid="outbox-strip"]', { timeout: 15000 }).should('be.visible')
    cy.get('[data-testid="outbox-strip"]').invoke('text').should('match', /\d+/)
    cy.get('[data-testid="outbox-strip"]').should('not.contain.text', 'audit records')
  })

  it('10 — the strip lists the failing REASON, not just a number', () => {
    // 57 failures sharing two distinct messages is the shape where one diagnosis fixes everything. A bare
    // count sends an operator to the database; a message sends them to the cause.
    cy.loginAsOperator()
    cy.visit(PORTAL)
    cy.get('[data-testid="outbox-strip"]', { timeout: 15000 }).should('be.visible')
    cy.get('[data-testid="outbox-detail"]').click()
    cy.get('[data-testid="outbox-reason"]', { timeout: 10000 })
      .should('exist')
      .invoke('text')
      .should('have.length.greaterThan', 0)
  })
})
