/**
 * SER-2 — the per-unit register: a purchase records WHICH handsets arrived.
 *
 * <h3>The gap this closes</h3>
 * Before this, an IMEI was captured only when a handset was FINANCED — `InstallmentPlan.assetRef`, free text,
 * called "a LABEL, not a register" by its own javadoc. A shop that bought ten handsets and sold three for cash
 * recorded no IMEI at all, and could not answer "who did we sell this one to?" — the question a warranty
 * claim, a return and a police enquiry all begin with.
 *
 * <h3>What is asserted</h3>
 * Not "the purchase succeeded" — that was already true when serials were being silently dropped. The
 * assertions are about the REGISTER: how many units exist, which serials, and that a refused purchase left
 * nothing behind.
 *
 * ⚠ Envelope, not HTTP status. `/addPurchase` answers GenericResponse, so a refusal is `status:"ERROR"` with a
 * 200. This has caught me three times on this codebase.
 */

const OWNER = 'owner.business@myplus.com'
const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)

/** The units of a product currently on the shelf, straight from the register. */
const unitsOf = (productId) =>
  cy.request(`/serialUnits?productId=${productId}`).then((r) => (r.body && r.body.collection) || [])

const buy = (productId, quantity, extra) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: Object.assign({
      productId, quantity,
      purchaseRate: 100, 'stock.bpurchaseRate': 100, 'stock.bsellRate': 150,
      totalAmount: 100 * quantity, netAmount: 100 * quantity, paidAmount: 100 * quantity,
      purchaseInvoiceNo: 'SER-' + uniq(),
    }, extra || {}),
  })

describe('SER-2 — the serial register', () => {
  let tracked = null      // a product that REQUIRES a serial
  let plain = null        // one that does not — the control

  before(() => {
    cy.loginAsOwner(OWNER)
    cy.setCapability('serialTracking', true)
    cy.setCapability('conditionGrading', true)

    cy.seedProduct({ name: `SER_TRACKED_${uniq()}` }).then((p) => {
      tracked = p.productId
      // Mark it serial-tracked through the product-policy endpoint — the same path an owner uses, so the
      // fixture exercises the real rule rather than writing the flag behind it.
      cy.request({
        method: 'POST', url: '/setProductTracking', form: true,
        body: { id: tracked, requiresSerial: true },
      }).then((r) => {
        expect(r.body && r.body.success, `the product must really be serial-tracked: ${JSON.stringify(r.body)}`)
          .to.not.eq(false)
      })
    })
    cy.seedProduct({ name: `SER_PLAIN_${uniq()}` }).then((p) => { plain = p.productId })
  })

  beforeEach(() => cy.loginAsOwner(OWNER))

  after(() => {
    cy.loginAsOwner(OWNER)
    cy.setCapability('serialTracking', true)
    cy.setCapability('conditionGrading', true)
  })

  // ── reachability ────────────────────────────────────────────────────────────────────────────────

  it('⭐ an operator can actually ENTER serials — the fields are on the purchase form', () => {
    /*
     * Every other case in this file drives the API, and cy.request reaches an endpoint whether a screen
     * exists or not. C6 shipped a per-product policy that way: column, endpoint, guard and a fully green API
     * gate, with no control anywhere for a shopkeeper to use.
     *
     * A register that can only be filled by a test is not a feature, so this asserts the SCREEN.
     */
    cy.setCapability('serialTracking', true)
    cy.setCapability('conditionGrading', true)
    cy.visit('/businessDashboard')
    cy.get('#purchaseSerials').should('exist').and('not.have.class', 'cap-off')
    cy.get('#purchaseCondition').should('exist')
      .find('option').should('have.length.at.least', 3)   // NEW / USED / REFURBISHED
  })

  it('and the fields disappear for a shop that does not track serials', () => {
    // The other half: a hardware shop should not be asked for an IMEI it will never have. Without this, a
    // build that showed the row to everyone would pass the case above perfectly.
    cy.setCapability('serialTracking', false)
    cy.setCapability('conditionGrading', false)
    cy.visit('/businessDashboard')
    cy.get('[data-capability="serialTracking,conditionGrading"]')
      .should('have.class', 'cap-off').and('not.be.visible')
    // Restore immediately: the cases below need the capability, and an after() hook is not a guarantee —
    // a token expiring mid-run once left a setting on and reddened the next spec for no visible reason.
    cy.setCapability('serialTracking', true)
    cy.setCapability('conditionGrading', true)
  })

  // ── the control ─────────────────────────────────────────────────────────────────────────────────

  it('a product that needs no serial buys exactly as it always did', () => {
    /*
     * The negative control, and it carries real weight: the whole risk of this slice is making every purchase
     * in the product harder. Most products in most shops have no serial and never will.
     */
    buy(plain, 5).then((r) => {
      expect(String(r.body.status), `an ordinary purchase must be unaffected: ${JSON.stringify(r.body)}`)
        .to.eq('SUCCESS')
    })
    unitsOf(plain).then((units) => {
      expect(units, 'and it registers no units').to.have.length(0)
    })
  })

  // ── capture ─────────────────────────────────────────────────────────────────────────────────────

  it('⭐ buying 3 handsets registers 3 units, each with its own IMEI', () => {
    const run = uniq()
    const serials = [`IMEI${run}A`, `IMEI${run}B`, `IMEI${run}C`]

    buy(tracked, 3, { serials: serials.join('\n'), conditionGrade: 'NEW' }).then((r) => {
      expect(String(r.body.status), JSON.stringify(r.body)).to.eq('SUCCESS')
    })

    unitsOf(tracked).then((units) => {
      const got = units.map((u) => u.serialNo)
      // Each unit individually, not just the count: three rows all carrying the FIRST serial is exactly what
      // the collapsing-proxy bug would have produced, and a length check alone would have passed it.
      serials.forEach((s) => expect(got, `${s} is in the register`).to.include(s))
      expect(units.filter((u) => u.status === 'IN_STOCK').length,
        'all three are on the shelf').to.be.at.least(3)
    })
  })

  it('the register answers "where is this one" — by history, not just what is in stock', () => {
    const run = uniq()
    const serial = `IMEI${run}H`
    buy(tracked, 1, { serials: serial }).then((r) => {
      expect(String(r.body.status), JSON.stringify(r.body)).to.eq('SUCCESS')
    })
    cy.request(`/serialHistory?serial=${serial}`).then((r) => {
      const rows = (r.body && r.body.collection) || []
      expect(rows.length, 'the unit has a history entry').to.be.at.least(1)
      expect(rows[0].serialNo).to.eq(serial)
      expect(rows[0].purchaseId, 'and it knows which delivery brought it in').to.not.eq(null)
    })
  })

  // ── refusals ────────────────────────────────────────────────────────────────────────────────────

  it('⭐ the same IMEI cannot be in stock twice', () => {
    /*
     * The safety property. Two identical handsets is not a thing, and a shop that believes it owns one twice
     * cannot be talked out of it by a report.
     */
    const run = uniq()
    const serial = `IMEI${run}DUP`
    buy(tracked, 1, { serials: serial }).then((r) => {
      expect(String(r.body.status), 'first receipt: ' + JSON.stringify(r.body)).to.eq('SUCCESS')
    })
    buy(tracked, 1, { serials: serial }).then((r) => {
      expect(String(r.body.status), `the second must be REFUSED: ${JSON.stringify(r.body)}`).to.eq('ERROR')
    })
    // And the refusal changed nothing — one unit, not two, and no half-written second purchase.
    cy.request(`/serialHistory?serial=${serial}`).then((r) => {
      const rows = (r.body && r.body.collection) || []
      expect(rows.length, 'exactly one unit exists under that serial').to.eq(1)
    })
  })

  it('the serial count must match the quantity received', () => {
    // Three handsets with two IMEIs means one unit is unaccounted for, and nothing downstream would ever
    // reveal which — the register would simply be short.
    const run = uniq()
    buy(tracked, 3, { serials: `IMEI${run}X\nIMEI${run}Y` }).then((r) => {
      expect(String(r.body.status), `a miscount must be REFUSED: ${JSON.stringify(r.body)}`).to.eq('ERROR')
      expect(String(r.body.message), 'and the message says what is wrong').to.match(/serial|unit/i)
    })
  })

  it('a serial-tracked product cannot be received with no serials at all', () => {
    const before = uniq()
    buy(tracked, 1, {}).then((r) => {
      expect(String(r.body.status), `must be REFUSED: ${JSON.stringify(r.body)} (${before})`).to.eq('ERROR')
    })
  })
})
