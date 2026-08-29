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

/**
 * The MOBILE SHOP tenant, not the POS one.
 *
 * Serial/IMEI tracking is a mobile-shop concern, and testing it as owner.business@ proved the mechanism while
 * proving nothing about the business that needs it. It also hid a real fact: the `retail` shape preset does
 * NOT include serialTracking or conditionGrading, so a mobile shop switches them on deliberately — exactly
 * what this spec now does, in the order a new shop would.
 */
const MOBILE = 'owner.mobile@myplus.com'
const POS = 'owner.business@myplus.com'
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
    cy.loginAsMobileOwner()
    /*
     * The mobile shop's day-one setup, in the order a real owner does it.
     *
     * `retail` is the shape — a counter selling finished goods. Its preset brings installments and dealer
     * pricing and NOT serial tracking, because a furniture showroom is the same shape and has no IMEIs. So a
     * mobile shop switches serial and condition on itself, which is the two-axis model working: the shape says
     * what kind of counter this is, the capabilities say what this particular shop does.
     *
     * Setting the shape first also proves the ordering matters the way the design claims — an explicit
     * capability override survives a shape that does not include it.
     */
    cy.setShape('retail')
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

  beforeEach(() => cy.loginAsMobileOwner())

  after(() => {
    cy.loginAsMobileOwner()
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

  it('⭐ a POS shop on the SAME shape does not see the serial fields', () => {
    /*
     * The cross-tenant proof, and the one this suite could not make while it ran as a single account.
     *
     * owner.business@ is retail too — same shape, same dashboard, same screens. What differs is that it never
     * switched serial tracking on. So this asserts the thing the whole capability platform is for: two shops
     * of the same KIND see different tills, decided by what each does rather than by what either is called.
     *
     * If this ever fails, a hardware counter is being asked for an IMEI, and the two-axis model has collapsed
     * back into "one vertical, one screen".
     */
    cy.loginAsOwner(POS)
    cy.setShape('retail')
    cy.setCapability('serialTracking', false)
    cy.visit('/businessDashboard')
    /*
     * `not.be.visible`, NOT `have.class('cap-off')`.
     *
     * The capability attribute sits on the WRAPPER, so the input itself never carries the class — the first
     * version of this assertion failed for that reason while the feature was working correctly. Visibility is
     * also the property that actually matters: whether the operator can reach the field. A class check would
     * additionally break the day the markup is restructured, for no gain.
     */
    cy.get('#purchaseSerials').should('exist').and('not.be.visible')
    cy.get('#sellSerials').should('exist').and('not.be.visible')

    // Leave the POS tenant as it was found — it is the account most other specs log in as, and a capability
    // left off here would redden them for a reason nothing in those files could explain.
    cy.setCapability('serialTracking', true)
    cy.setShape('general')
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

/**
 * SER-3 — the till consumes a unit.
 *
 * SER-2 recorded what ARRIVED. This is the half that answers the question the register exists for: **who did
 * we sell this handset to?** A unit that is sold must stop being sellable, and must carry the invoice a
 * customer can quote.
 *
 * ⚠ `/addSell` answers GenericResponse, so a refusal is `status:"ERROR"` with HTTP 200.
 */
describe('SER-3 — selling a tracked unit', () => {
  let tracked = null
  let other = null

  const uniqS = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)

  /** Receive one unit of `productId` under `serial`, so there is something real to sell. */
  const receive = (productId, serial) =>
    cy.request({
      method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
      body: {
        productId, quantity: 1, serials: serial,
        purchaseRate: 100, 'stock.bpurchaseRate': 100, 'stock.bsellRate': 150,
        totalAmount: 100, netAmount: 100, paidAmount: 100, purchaseInvoiceNo: 'SER-' + uniqS(),
      },
    }).then((r) => {
      expect(String(r.body.status), `receiving ${serial}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })

  const sell = (productId, serials, qty) =>
    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: {
        customer: { name: `SER Buyer ${uniqS()}`, contact: '0300' + uniqS(), paidAmount: 150, dueAmount: 0 },
        sales: [{ productId, quantity: qty || 1, sellRate: 150, totalAmount: 150, netAmount: 150,
                  serials: serials }],
        paidAmount: 150, dueAmount: 0, grandTotal: 150,
      },
    })

  const historyOf = (serial) =>
    cy.request(`/serialHistory?serial=${serial}`).then((r) => (r.body && r.body.collection) || [])

  before(() => {
    cy.loginAsMobileOwner()
    cy.setCapability('serialTracking', true)
    cy.seedProduct({ name: `SER3_TRACKED_${uniqS()}`, sellingPrice: 150, stock: 50 }).then((p) => {
      tracked = p.productId
      cy.request({ method: 'POST', url: '/setProductTracking', form: true,
                   body: { id: tracked, requiresSerial: true } })
    })
    // A SECOND tracked product, so "this serial belongs to something else" can be tested at all.
    cy.seedProduct({ name: `SER3_OTHER_${uniqS()}`, sellingPrice: 150, stock: 50 }).then((p) => {
      other = p.productId
      cy.request({ method: 'POST', url: '/setProductTracking', form: true,
                   body: { id: other, requiresSerial: true } })
    })
  })

  beforeEach(() => cy.loginAsMobileOwner())

  after(() => {
    cy.loginAsMobileOwner()
    cy.setCapability('serialTracking', true)
  })

  it('⭐ selling a handset marks THAT unit sold and records the invoice', () => {
    const serial = `IMEI${uniqS()}S`
    receive(tracked, serial)

    sell(tracked, serial).then((r) => {
      expect(String(r.body.status), JSON.stringify(r.body)).to.eq('SUCCESS')
    })

    historyOf(serial).then((rows) => {
      expect(rows.length, 'the unit is in the register').to.eq(1)
      expect(rows[0].status, 'and it is no longer on the shelf').to.eq('SOLD')
      // The invoice is the whole point: a customer with a receipt can be matched to this handset.
      expect(rows[0].invoiceNo, 'the unit knows which sale took it out').to.match(/INV-/)
    })
  })

  it('the same unit cannot be sold twice', () => {
    /*
     * The register's reason to exist. Without it a shop can sell one physical handset to two customers and
     * only discover it when the second one comes to collect.
     */
    const serial = `IMEI${uniqS()}T`
    receive(tracked, serial)
    sell(tracked, serial).then((r) => expect(String(r.body.status), 'first sale').to.eq('SUCCESS'))
    sell(tracked, serial).then((r) => {
      expect(String(r.body.status), `the second sale must be REFUSED: ${JSON.stringify(r.body)}`).to.eq('ERROR')
    })
  })

  it('a serial that was never received cannot be sold', () => {
    sell(tracked, `IMEI${uniqS()}NOPE`).then((r) => {
      expect(String(r.body.status), `must be REFUSED: ${JSON.stringify(r.body)}`).to.eq('ERROR')
    })
  })

  it("⭐ a serial belonging to a DIFFERENT product is refused", () => {
    /*
     * Selling handset A's IMEI on a line for handset B would mark the wrong unit sold, and the shop would
     * find out when the real one came back under warranty. The serial is the customer's evidence, so it has
     * to point at what they were actually handed.
     */
    const serial = `IMEI${uniqS()}X`
    receive(other, serial)
    sell(tracked, serial).then((r) => {
      expect(String(r.body.status), `wrong-product serial must be REFUSED: ${JSON.stringify(r.body)}`)
        .to.eq('ERROR')
    })
    // And it is untouched — still on the shelf under its real product.
    historyOf(serial).then((rows) => {
      expect(rows[0].status, 'a refused sale does not consume the unit').to.eq('IN_STOCK')
    })
  })

  it('a tracked product cannot be sold without naming the unit', () => {
    sell(tracked, '').then((r) => {
      expect(String(r.body.status), `must be REFUSED: ${JSON.stringify(r.body)}`).to.eq('ERROR')
    })
  })

  it('the serial box is on the sale screen for a shop that tracks serials', () => {
    // The reachability assertion. Every case above drives cy.request, which reaches the endpoint whether the
    // till has a field or not — the blind spot that let C6 ship a policy no shopkeeper could set.
    cy.setCapability('serialTracking', true)
    cy.visit('/businessDashboard')
    cy.get('#sellSerials').should('exist').and('have.attr', 'name', 'serials')
  })
})

/**
 * SER-4 — the till shows what the unit actually is.
 *
 * SER-2 recorded a condition grade at intake. Until this slice it lived only in the database: a cashier
 * selling a second-hand handset saw exactly the screen of one selling a new handset. The grade was a fact the
 * shop owned and could not act on, and the moment it matters is BEFORE the money is taken.
 *
 * Advisory by design — nothing here blocks a sale. `SagaSellService` is the control; this is a courtesy in
 * front of it, so the assertions are about what the cashier SEES.
 */
describe('SER-4 — condition at the till', () => {
  const uniq4 = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)
  let tracked = null

  const receiveGraded = (serial, grade) =>
    cy.request({
      method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
      body: {
        productId: tracked, quantity: 1, serials: serial, conditionGrade: grade,
        purchaseRate: 100, 'stock.bpurchaseRate': 100, 'stock.bsellRate': 150,
        totalAmount: 100, netAmount: 100, paidAmount: 100, purchaseInvoiceNo: 'SER4-' + uniq4(),
      },
    }).then((r) => {
      expect(String(r.body.status), `receiving ${grade} ${serial}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })

  /** Type a serial into the sale screen and wait for the register lookup to answer. */
  const enterSerial = (serial) => {
    cy.visitSaleScreen()
    cy.get('#sellSerials').should('be.visible').clear().type(serial).blur()
  }

  before(() => {
    cy.loginAsMobileOwner()
    cy.setShape('retail')
    cy.setCapability('serialTracking', true)
    cy.setCapability('conditionGrading', true)
    cy.seedProduct({ name: `SER4_${uniq4()}`, sellingPrice: 150, stock: 20 }).then((p) => {
      tracked = p.productId
      cy.request({ method: 'POST', url: '/setProductTracking', form: true,
                   body: { id: tracked, requiresSerial: true } })
    })
  })

  beforeEach(() => cy.loginAsMobileOwner())

  it('⭐ a USED handset says so before the money is taken', () => {
    /*
     * The case the slice exists for. A shop that grades stock at intake and then cannot see the grade at the
     * counter has bought itself a record and no benefit.
     */
    const serial = `IMEI${uniq4()}U`
    receiveGraded(serial, 'USED')
    enterSerial(serial)
    cy.get('#sellSerialInfo').should('be.visible')
      .and('have.class', 'serial-info-warn')     // colour carries meaning on a busy counter
      .invoke('text').should('match', /used|usado|occasion|इस्तेमाल|مستعمل|استعمال/i)
  })

  it('a NEW handset is confirmed, and quietly — it is the ordinary case', () => {
    // The positive control. Without it, a build that showed "Used" for everything would pass the case above.
    const serial = `IMEI${uniq4()}N`
    receiveGraded(serial, 'NEW')
    enterSerial(serial)
    cy.get('#sellSerialInfo').should('be.visible').and('have.class', 'serial-info-ok')
  })

  it('a serial that is not on the shelf says so as it is typed', () => {
    /*
     * The server already refuses this — but only at submit, after the basket is built and the customer is
     * waiting. Saying it while the cashier is still typing turns a refusal into something they can fix
     * cheaply. It does NOT replace the server check, which is why nothing here blocks the sale.
     */
    enterSerial(`IMEI${uniq4()}GHOST`)
    cy.get('#sellSerialInfo').should('be.visible').and('have.class', 'serial-info-miss')
  })

  after(() => {
    cy.loginAsMobileOwner()
    cy.setCapability('serialTracking', true)
    cy.setCapability('conditionGrading', true)
  })
})
