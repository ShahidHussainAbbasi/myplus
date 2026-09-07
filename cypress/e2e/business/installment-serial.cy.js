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

/**
 * Sell one handset on a plan. `serial` may be omitted to test the required-serial rule.
 *
 * ⭐ INST-5b — `serialTracked` is opt-in, and which cases opt in is load-bearing.
 *
 * <p>The serial rule used to read the tenant setting alone, so any product would do. It now asks the
 * CATALOG whether the item being financed actually has a serial — a shop that turned the rule on was
 * otherwise refused selling Panadol on terms, for an item flagged {@code requires_serial = 0}.
 *
 * <p>Most cases here are about `assetRef` (the plan's label for the financed unit) and its
 * one-live-plan-per-serial rule, which applies to any non-null value whether the product is tracked or
 * not — so they stay UNTRACKED and sell cleanly. Only a case about the REQUIRED rule flags the product,
 * because on an unflagged one that rule no longer fires at all and the case would pass while testing
 * nothing.
 */
const sellOnPlan = (buyer, serial, monthsAgo = -6, price = 60000, serialTracked = false) => {
  const run = uniq()
  return cy.seedProduct({ name: `RPS_${run}`, sellingPrice: price, stock: 5 }).then(({ productId }) => {
    if (serialTracked) {
      cy.request({
        method: 'POST', url: '/setProductTracking', form: true,
        body: { id: productId, requiresSerial: 'true' }, failOnStatusCode: false,
      }).then((tr) => {
        // Assert the fixture, never assume it: an unflagged product makes the required-serial cases
        // vacuous, and they would pass.
        expect(JSON.stringify(tr.body), `product ${productId} is serial-tracked`).to.not.match(/error/i)
      })
    }
    const plan = {
      cashPrice: price, downPayment: 0, installmentCount: 6,
      frequency: 'monthly', firstDueDate: monthsOut(monthsAgo),
    }
    if (serial !== undefined) plan.assetRef = serial
    return cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: buyer, contact: `0300P${run}`, paidAmount: 0, dueAmount: 0 },
        /*
         * ⚠ NO `serials` ON THE LINE, deliberately — and the product is UNTRACKED by default.
         *
         * A serial named on a line must already exist in the register as a received unit: an unknown one is
         * refused with `Serial "X" is not in stock.` This fixture seeds a product and stock, never serial
         * UNITS, so sending the serial down the line would refuse every case here for a reason that has
         * nothing to do with plans. (An earlier pass did exactly that.)
         *
         * `assetRef` on the plan is a different thing — the plan's label for the financed unit — and its
         * duplicate check runs on any non-null value, tracked or not. That is what cases 1 and 2 exercise.
         */
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
    /*
     * ⚠ The two halves use DIFFERENT PRODUCTS, and they have to.
     *
     * "A furniture shop has nothing to type in the box" is a statement about the PRODUCT, so the first half
     * sells an untracked one — it was selling a tracked product, which the per-line rule refuses on every
     * sale regardless of any installment setting.
     *
     * The second half keeps a tracked product with no serial. ⚠ Note what actually refuses it: since
     * INST-5b that is SerialUnitService's LINE rule, not `pos.installment.serialRequired`. See the case
     * below — the two rules now overlap completely, and this one records the outcome a shopkeeper sees.
     */
    setConfig('pos.installment.serialRequired', 'false')
    sellOnPlan(`No Serial ${uniq()}`, undefined, -6, 60000, false).then((r) => {
      expect(r.body.status, 'a furniture shop has nothing to type in the box').to.eq('SUCCESS')
    })

    setConfig('pos.installment.serialRequired', 'true')
    sellOnPlan(`Needs Serial ${uniq()}`, undefined, -6, 60000, true).then((r) => {
      expect(r.body.status, 'and a phone shop can insist on one').to.eq('FAILED')
      expect(r.body.message.toLowerCase()).to.contain('serial')
    })
    setConfig('pos.installment.serialRequired', 'false')
  })

  it('⚠ INST-5b — a tracked product needs its serial even with the installment rule OFF', () => {
    /*
     * ⭐ THE FINDING THIS SLICE SURFACED, recorded as a test so it cannot be forgotten.
     *
     * `pos.installment.serialRequired` now applies only to serial-tracked products — and a serial-tracked
     * product ALREADY needs its serial on every sale, financed or not, via SerialUnitService.validateForSale.
     * So the setting is switched OFF here and the sale is still refused.
     *
     * That makes the setting redundant for anything reachable from the sale screen: the case it used to
     * cover on its own was untracked products, which is exactly the defect INST-5b removed. Left in place
     * for now rather than deleted, because retiring a tenant setting is a product decision; this case is
     * what will fail loudly if somebody assumes it still does work of its own.
     */
    setConfig('pos.installment.serialRequired', 'false')
    sellOnPlan(`Tracked No Serial ${uniq()}`, undefined, -6, 60000, true).then((r) => {
      expect(r.body.status, `the LINE rule refuses it: ${JSON.stringify(r.body)}`).to.eq('ERROR')
      expect(r.body.message.toLowerCase(), 'and says which rule').to.contain('tracked by serial')
    })
  })

  it('⭐⭐ INST-5b — with the rule ON, a product that has no serial still sells on terms', () => {
    /*
     * ⭐ THE DEFECT, reported from Shahzad Mobile Shop (org 41): the rule was on, and selling Panadol on
     * terms was refused with "This sale needs an IMEI or serial number before it can go on a plan."
     *
     * A serial requirement can only mean "type the serial this item has". Applied to an item that has
     * none it is unsatisfiable — there is no keystroke that clears it — so the shop simply could not
     * finance most of its catalogue. That org is 3 untracked products to 1 tracked; the blanket rule
     * refused the ordinary case and protected nothing extra.
     *
     * Paired with the case above ON PURPOSE: that one proves the rule still bites for a tracked product,
     * this one proves it lets an untracked one through. Either alone would be satisfied by a build that
     * got the other backwards.
     */
    setConfig('pos.installment.serialRequired', 'true')
    sellOnPlan(`Untracked ${uniq()}`, undefined, -6, 60000, false).then((r) => {
      expect(r.body.status,
        `a product with no serial to give must still sell: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
      expect(JSON.stringify(r.body), 'and nothing asks for an IMEI it cannot have').to.not.match(/IMEI/i)
    })
    setConfig('pos.installment.serialRequired', 'false')
  })
})
