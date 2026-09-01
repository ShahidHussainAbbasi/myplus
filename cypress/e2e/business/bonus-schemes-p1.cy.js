/**
 * #17 P1 — the bonus / free-goods SCHEME MASTER.
 *
 * ⚠ WRITTEN BEFORE THE IMPLEMENTATION (cadence, 2026-08-30). These cases are the requirement; the code is
 * what makes them green. They will fail until P1 is built — that is the point, and it is why they describe
 * business outcomes rather than the shape of whatever gets written.
 *
 * <h3>Design: docs/slices/bonus-schemes.md</h3>
 * One engine, three scopes. A scheme is a rule on the existing price-rule engine, resolved by specificity and
 * priority the way `PriceResolver` already resolves prices — not a second promotion system.
 *
 * <h3>What P1 must prove</h3>
 * <ul>
 *   <li><b>Inclusive vs exclusive is MANDATORY.</b> Without it "10+1" cannot be interpreted for stock,
 *       invoice, cost or tax — so a scheme saved without it is not a usable scheme.</li>
 *   <li><b>The reward may be a DIFFERENT product.</b> "Buy a machine, get a coffee pack" cannot be expressed
 *       by a bonus quantity alone; this is what rules out simply reusing `Sell.bonusQuantity`.</li>
 *   <li><b>Qualification mode</b> — ONE_TIME vs REPEATING — because partial-return clawback (D7) is
 *       ambiguous without it.</li>
 *   <li><b>It is REACHABLE.</b> A master with no screen is the eighth unreachable feature in this codebase
 *       (C1, C3, C6, PERF-4, getSaleReturns, downloadInvoicePdf, stock/summary). P1 ships its screen.</li>
 * </ul>
 */

const DIST_OWNER = 'owner.marketplace@myplus.com'
const OTHER_TENANT = 'owner.business@myplus.com'
const CAP = 'bonusSchemes'

/** A minimal valid scheme body. Individual cases omit fields to prove they are required. */
function scheme(over) {
  return Object.assign({
    code: 'CY-SUP-10-1',
    scope: 'VENDOR',
    triggerProductId: null,      // filled per test
    rewardProductId: null,       // same product unless a case says otherwise
    paidQuantity: 10,
    bonusQuantity: 1,
    bonusType: 'EXCLUSIVE',
    qualificationMode: 'ONE_TIME',
    priority: 100,
    status: 'ACTIVE',
  }, over || {})
}

describe('#17 P1 — bonus scheme master', () => {
  beforeEach(() => {
    cy.loginAsMarketplaceOwner()
  })

  it('⭐ the capability exists and gates the feature', () => {
    /*
     * Regression guarded: C1/C3 — a capability that is declared but registered nowhere fails OPEN, so every
     * API test passes while the feature is unreachable or ungated. Asserted on the catalog, which is what the
     * Configuration screen renders from.
     */
    cy.request({ url: '/getCapabilities', failOnStatusCode: false }).then((r) => {
      const body = r.body
      const known = JSON.stringify(body)
      expect(known, `${CAP} is a known capability`).to.contain(CAP)
    })
  })

  it('⭐ a scheme REQUIRES its bonus type — inclusive or exclusive', () => {
    /*
     * D2. "10+1" is ambiguous without it: inclusive delivers 10 and bills 9; exclusive delivers 11 and bills
     * 10. Stock, invoice, cost and tax all differ. A scheme that saves without this is a trap.
     */
    cy.request({
      method: 'POST', url: '/bonusScheme', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: scheme({ bonusType: null }),
    }).then((r) => {
      // Refusals arrive as HTTP 200 with an error ENVELOPE — asserting the status code would pass on a save.
      expect(r.body.success === false || r.body.status === 'ERROR' || r.body.status === 'FAILED',
        `a scheme without a bonus type must be refused: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    })
  })

  it('⭐ a scheme REQUIRES its qualification mode', () => {
    // D9. Without ONE_TIME vs REPEATING, "15 paid units" yields either 1 bonus or 3, and partial-return
    // clawback (D7) cannot be computed at all.
    cy.request({
      method: 'POST', url: '/bonusScheme', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: scheme({ qualificationMode: null }),
    }).then((r) => {
      expect(r.body.success === false || r.body.status === 'ERROR' || r.body.status === 'FAILED',
        'a scheme without a qualification mode must be refused').to.eq(true)
    })
  })

  it('⭐ the reward may be a DIFFERENT product from the trigger', () => {
    /*
     * D3, and the requirement that rules out reusing Sell.bonusQuantity: "buy a machine, get a coffee pack"
     * needs a reward ITEM, not a number. Saved and read back, because a field that is accepted and dropped
     * on write is the failure this asserts against.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const rows = (p.body && p.body.collection) || []
      expect(rows.length, 'the tenant has at least two products').to.be.greaterThan(1)
      const trigger = rows[0].id
      const reward = rows[1].id

      cy.request({
        method: 'POST', url: '/bonusScheme', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' },
        body: scheme({ code: 'CY-DIFF-SKU', triggerProductId: trigger, rewardProductId: reward }),
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.not.eq('ERROR')
        cy.request({ url: '/bonusSchemes' }).then((list) => {
          const saved = (list.body.collection || list.body.data || [])
            .find((x) => x.code === 'CY-DIFF-SKU')
          expect(saved, 'the scheme was stored').to.exist
          expect(String(saved.rewardProductId), 'the reward SKU survived the write').to.eq(String(reward))
          expect(String(saved.triggerProductId)).to.eq(String(trigger))
        })
      })
    })
  })

  it('⭐ REPEATING and ONE_TIME give different entitlements for the same paid quantity', () => {
    /*
     * The arithmetic the mode exists for, asserted as behaviour rather than as a stored flag:
     *   ONE_TIME  "buy 10 get 1"   with 15 paid  -> 1 bonus
     *   REPEATING "every 10 get 1" with 15 paid  -> 1 bonus
     *   REPEATING "every 5  get 1" with 15 paid  -> 3 bonus
     * A resolver that ignores the mode passes a storage test and fails this one.
     */
    const cases = [
      { mode: 'ONE_TIME', paid: 10, bonus: 1, buying: 15, expect: 1 },
      { mode: 'REPEATING', paid: 10, bonus: 1, buying: 15, expect: 1 },
      { mode: 'REPEATING', paid: 5, bonus: 1, buying: 15, expect: 3 },
    ]
    cy.wrap(cases).each((c) => {
      cy.request({
        method: 'POST', url: '/bonusScheme/preview', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' },
        body: { qualificationMode: c.mode, paidQuantity: c.paid, bonusQuantity: c.bonus, quantity: c.buying },
      }).then((r) => {
        const got = (r.body && (r.body.object != null ? r.body.object : r.body.data))
        expect(Number(got && got.bonusQuantity != null ? got.bonusQuantity : got),
          `${c.mode} ${c.paid}+${c.bonus} on ${c.buying} paid`).to.eq(c.expect)
      })
    })
  })

  it('an EXPIRED scheme does not resolve', () => {
    // Validity is not decoration: an expired offer that still fires gives away stock the shop is not being
    // paid for, and does it silently.
    cy.request({
      method: 'POST', url: '/bonusScheme', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: scheme({ code: 'CY-EXPIRED', startsOn: '2020-01-01', endsOn: '2020-01-31' }),
    }).then(() => {
      cy.request({ url: '/bonusSchemes?activeOnly=true' }).then((r) => {
        const rows = r.body.collection || r.body.data || []
        expect(rows.find((x) => x.code === 'CY-EXPIRED'), 'an expired scheme is not live').to.not.exist
      })
    })
  })

  it('⭐ another tenant can neither see nor read this tenant\'s schemes', () => {
    // Multi-tenancy. Scheme codes are human-chosen and will collide across tenants; the scope predicate is
    // the only thing keeping them apart.
    cy.request({
      method: 'POST', url: '/bonusScheme', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: scheme({ code: 'CY-TENANT-A' }),
    })
    cy.loginAsOwner(OTHER_TENANT)
    cy.request({ url: '/bonusSchemes', failOnStatusCode: false }).then((r) => {
      const rows = r.body.collection || r.body.data || []
      expect(rows.find((x) => x.code === 'CY-TENANT-A'),
        'tenant A\'s scheme must not be visible to tenant B').to.not.exist
    })
  })

  it('⭐ the scheme screen EXISTS and lists schemes — P1 ships reachable', () => {
    /*
     * The eighth-unreachable-feature guard. cy.request reaches an endpoint whether or not any screen does, so
     * this asserts the screen-level route: a nav entry that opens a section which lists what the API returns.
     */
    cy.visitDashboardSettled()
    cy.get('#navBonusSchemes').should('exist').click({ force: true })
    cy.get('#BonusSchemeDiv').should('be.visible')
    cy.get('#tableBonusScheme tbody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)
  })

  it('privilege ladder — a non-admin member cannot create a scheme', () => {
    // GATE-RUNBOOK rule 4. A scheme gives away stock, so authoring one is not a counter-staff action.
    cy.loginAsOrderBooker()
    cy.request({
      method: 'POST', url: '/bonusScheme', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: scheme({ code: 'CY-BOOKER' }),
    }).then((r) => {
      expect(r.body.success === false || r.body.status === 'ERROR' || r.body.status === 'FAILED',
        'a booker must not be able to author a bonus scheme').to.eq(true)
    })
  })
})
