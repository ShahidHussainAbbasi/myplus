/**
 * INST-5a — one handset, one live plan.
 * Design: microservices/docs/slices/inst-5-serial-units-repossession.md
 *
 * WHAT THIS ASSERTS.
 *
 * Before this slice the same IMEI could be financed on two plans at once and nothing objected — the shop
 * simply believed it was owed for a phone it had sold once. That is the whole subject of this file.
 *
 * The guarantee is a DATABASE constraint, not a service check: two tills can pass an application-level
 * "is this serial free?" in the same millisecond and both insert. `uq_plan_live_asset` (V44) closes that,
 * and it is built on a STORED generated column so it applies only to LIVE plans — a plain unique on
 * `asset_ref` would have blocked the shop from ever re-selling a handset it legitimately repossessed.
 * That half is asserted in installment-repossession.cy.js, where a plan can actually be closed.
 *
 * SPLIT FROM installment-repossession.cy.js: the two were one file, which grew long enough to outlive the
 * 15-minute auth token and fail from the middle onward with no assertion error at all (see commands.js).
 * A gate that is intermittently red teaches people to re-run rather than to read.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/installment-serial.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Sell one handset on a plan. `serial` may be omitted to test the required-serial rule. */
const sellOnPlan = (buyer, serial, monthsAgo = -6, price = 60000) => {
  const run = uniq()
  return cy.seedProduct({ name: `RPS_${run}`, sellingPrice: price, stock: 5 }).then(({ productId }) => {
    const plan = {
      cashPrice: price, downPayment: 0, installmentCount: 6,
      frequency: 'monthly', firstDueDate: monthsOut(monthsAgo),
    }
    if (serial !== undefined) plan.assetRef = serial
    return cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: buyer, contact: `0300P${run}`, paidAmount: 0, dueAmount: 0 },
        sales: [{ productId, quantity: 1, sellRate: price, totalAmount: price, netAmount: price }],
        paidAmount: 0, dueAmount: 0, grandTotal: price,
        installmentPlan: plan,
      }, failOnStatusCode: false,
    })
  })
}

describe('INST-5a — one handset, one live plan', () => {
  before(() => {
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'true')
  })

  beforeEach(() => cy.loginAsOwner())

  after(() => {
    // Leave no server state behind — both of these change behaviour for every later spec, and
    // serialRequired in particular would make an ordinary financed sale start refusing itself.
    // Only what THIS file touches: the repossession settings belong to the spec that sets them.
    cy.loginAsOwner()
    setConfig('pos.installment.serialRequired', 'false')
    setConfig('pos.installment.enabled', 'false')
  })

  // ── ⭐ THE FIRST THING THAT CARRIES THE SLICE ──────────────────────────────────────────────────────────

  it('the same IMEI cannot be financed twice while the first plan is live', () => {
    const imei = `IMEI${uniq()}`

    sellOnPlan(`Serial First ${uniq()}`, imei).then((first) => {
      // POSITIVE CONTROL. Without it the refusal below is satisfied by BOTH sales failing — for instance
      // because the fixture product never existed — and the case would pass against no rule at all.
      expect(first.body.status, JSON.stringify(first.body)).to.eq('SUCCESS')
      expect(first.body.message).to.contain('PLN-')

      sellOnPlan(`Serial Second ${uniq()}`, imei).then((second) => {
        expect(second.body.status, 'the second sale on the same IMEI is refused').to.eq('FAILED')
        // The message NAMES the plan that already holds it. "Duplicate" would leave the cashier with a
        // refusal and nowhere to look.
        expect(second.body.message, JSON.stringify(second.body)).to.contain('PLN-')
      })
    })
  })

  it('a different IMEI on the same product is fine', () => {
    // The negative control for the rule above: without it, "refused" would be satisfied by a shop that can
    // no longer finance two handsets of the same model.
    sellOnPlan(`Serial A ${uniq()}`, `IMEI${uniq()}`).then((a) => {
      expect(a.body.status).to.eq('SUCCESS')
      sellOnPlan(`Serial B ${uniq()}`, `IMEI${uniq()}`).then((b) => {
        expect(b.body.status, JSON.stringify(b.body)).to.eq('SUCCESS')
      })
    })
  })

  it('a serial is optional until the shop says otherwise', () => {
    setConfig('pos.installment.serialRequired', 'false')
    sellOnPlan(`No Serial ${uniq()}`, undefined).then((r) => {
      expect(r.body.status, 'a furniture shop has nothing to type in the box').to.eq('SUCCESS')
    })

    setConfig('pos.installment.serialRequired', 'true')
    sellOnPlan(`Needs Serial ${uniq()}`, undefined).then((r) => {
      expect(r.body.status, 'and a phone shop can insist on one').to.eq('FAILED')
      expect(r.body.message.toLowerCase()).to.contain('serial')
    })
    setConfig('pos.installment.serialRequired', 'false')
  })
})
