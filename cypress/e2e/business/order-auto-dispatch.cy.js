/**
 * OMS O8 — dispatch on approval, for a shop with no warehouse step.
 *
 * WHY THE STEP IS PERFORMED RATHER THAN SKIPPED.
 *
 * A small distributor loads the van from the room the order was approved in, so walking a pick list and
 * recording a parcel for goods that never sat on a shelf is ceremony — and ceremony gets skipped. In this
 * system that would be fatal rather than merely untidy: a field order's invoice is raised BY the shipment
 * (DispatchInvoiceService, called from ShipmentService), so an order that never records a parcel is never
 * invoiced at all. That is OMS-1, the defect this whole programme began with.
 *
 * So approving RECORDS the parcel through the ordinary path. ShipmentService stays the only writer of
 * shipments and the only trigger of a dispatch invoice, and everything downstream is untouched because none
 * of it can tell who recorded it.
 *
 * THE CASE THAT CARRIES THIS FILE is the refusal: an order whose stock could not be set aside must NOT be
 * dispatched. Ordinarily a failed hold is advisory — the admin may promise goods the shop has not got, and
 * the warehouse discovers it at picking. There is no picking here, so the same advisory would invoice and
 * decrement stock that does not exist.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/order-auto-dispatch.cy.js --headed --no-exit
 */
describe('OMS O8 — dispatch on approval', () => {
  const GW = 'http://localhost:8765'
  const PW = 'Demo@2025!'
  const AUTO = 'order.flow.autoDispatchOnApproval'
  const SCAN = 'order.pack.scanRequired'
  const run = String(Date.now()).slice(-6)
  const PRICE = 40

  let auth = null
  let productId = null

  const login = (email) => cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' }, body: { email, password: PW }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login as ${email}: ${JSON.stringify(r.body)}`).to.eq(200)
    const token = r.body.data && r.body.data.accessToken
    expect(token, 'no access token').to.be.a('string')
    return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
  })

  const setKey = (key, value) => cy.request({
    method: 'POST', url: `${GW}/api/marketplace/settings?key=${key}&value=${value}`,
    headers: auth, failOnStatusCode: false,
  }).then((r) => {
    expect(r.body.success, `saving ${key}=${value} failed: ${JSON.stringify(r.body)}`).to.eq(true)
  })

  /** Seed a product with a KNOWN stock level — the shortfall case depends on the number being exact. */
  const seedProduct = (label, stock) => {
    cy.loginAsMarketplaceOwner()
    return cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: label, sku: label, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const id = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId: id, quantity: stock }, failOnStatusCode: false,
      }).then((s) => {
        expect(s.body.success, JSON.stringify(s.body)).to.eq(true)
        return cy.wrap(id)
      })
    })
  }

  const book = (outlet, pid, qty, label) => {
    cy.loginAsOrderBooker()
    return cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerName: outlet, customerContact: '0300' + run, shippingAddress: '7 Van Rd',
        items: [{ productId: pid, quantity: qty, price: PRICE, productName: label }],
      },
    }).then((r) => {
      // Assert the fixture, loudly: an order that failed to book would make every assertion below pass or
      // fail for a reason that has nothing to do with dispatching.
      expect(r.body.success, 'booked: ' + JSON.stringify(r.body)).to.eq(true)
      return r.body.data
    })
  }

  const confirm = (id) => {
    cy.loginAsMarketplaceOwner()
    return cy.request({
      method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
      body: { id }, failOnStatusCode: false,
    })
  }

  const readOrder = (id) => {
    cy.loginAsMarketplaceOwner()
    return cy.request({ url: '/getOrder?id=' + id, failOnStatusCode: false })
      .then((r) => (r.body && (r.body.data || r.body.object)) || r.body)
  }

  before(() => {
    login('owner.marketplace@myplus.com').then((h) => { auth = h })
    seedProduct('AutoDisp_' + run, 100).then((id) => { productId = id })
  })

  after(() => {
    // Leave no server state behind. Both keys default OFF, and a tenant left on auto-dispatch would silently
    // change what every other OMS spec observes at confirm.
    login('owner.marketplace@myplus.com').then((h) => {
      auth = h
      setKey(AUTO, false)
      setKey(SCAN, false)
    })
  })

  // ── the default is unchanged ──────────────────────────────────────────────────────────────────────────

  it('OFF (the default): approving leaves the order to be packed', () => {
    // The negative control for the whole file. Without it, "dispatched on approval" could be satisfied by an
    // order that dispatches on approval whatever the setting says.
    cy.then(() => setKey(AUTO, false))

    book('PackMe_' + run, productId, 2, 'AutoDisp_' + run).then((o) => {
      confirm(o.id).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

      readOrder(o.id).then((after) => {
        expect(after.fulfilmentStatus, 'waiting for the warehouse').to.eq('NEW')
        expect(after.invoiceNo, 'and NOT invoiced yet').to.be.oneOf([null, undefined, ''])
      })
    })
  })

  // ── ⭐ the shortcut ───────────────────────────────────────────────────────────────────────────────────

  it('⭐ ON: approving dispatches the whole order and raises the invoice', () => {
    cy.then(() => setKey(AUTO, true))

    book('VanRun_' + run, productId, 3, 'AutoDisp_' + run).then((o) => {
      confirm(o.id).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

      readOrder(o.id).then((after) => {
        // The parcel was recorded, so the status is the DERIVED one — not NEW.
        expect(after.fulfilmentStatus, 'dispatched without a warehouse step').to.eq('SHIPPED')

        // THE ASSERTION THAT MATTERS. The shipment is what invoices a field order; if this is empty the
        // goods have left with nothing behind them, which is the defect the whole programme began with.
        expect(after.invoiceNo, 'and the invoice exists').to.match(/INV-/)
      })
    })
  })

  it('the round sheet picks it up with no change of its own', () => {
    // Everything downstream reads SHIPPED and cannot tell who recorded the parcel. This proves the claim
    // rather than assuming it — the sheet is what the driver carries, so an order missing from it is an
    // order nobody delivers.
    cy.then(() => setKey(AUTO, true))

    book('SheetStop_' + run, productId, 2, 'AutoDisp_' + run).then((o) => {
      confirm(o.id)

      cy.loginAsMarketplaceOwner()
      cy.request({ url: '/roundSheet', failOnStatusCode: false }).then((r) => {
        const sheet = (r.body && (r.body.data || r.body.object)) || {}
        const stops = sheet.stops || sheet.rows || []
        const mine = stops.filter((s) => JSON.stringify(s).indexOf('SheetStop_' + run) >= 0)
        expect(mine.length, 'the auto-dispatched stop is on the round sheet').to.be.greaterThan(0)
      })
    })
  })

  // ── ⭐ THE REFUSAL THAT CARRIES THE SLICE ─────────────────────────────────────────────────────────────

  it('⭐ stock it has not got is NOT dispatched — the order waits to be packed by hand', () => {
    /*
     * Ordinarily a failed hold is ADVISORY: the admin is entitled to promise goods the shop has not got, and
     * the warehouse finds out at picking. There is no picking on this path, so the same advisory would
     * invoice and decrement stock that does not exist — inventing inventory, which is the one thing an
     * order system must never do.
     */
    cy.then(() => setKey(AUTO, true))

    // Three in stock, five ordered. The shortfall is deliberate and exact.
    seedProduct('Scarce_' + run, 3).then((scarceId) => {
      book('ShortStop_' + run, scarceId, 5, 'Scarce_' + run).then((o) => {
        confirm(o.id).then((r) => {
          // The APPROVAL still stands — refusing it would lose the review decision the admin just made.
          expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        })

        readOrder(o.id).then((after) => {
          expect(after.fulfilmentStatus, 'left for a human to pick').to.eq('NEW')
          expect(after.invoiceNo, 'and NOTHING was invoiced').to.be.oneOf([null, undefined, ''])
        })
      })
    })
  })

  it('scan-to-pack and auto-dispatch together are reported, not silently resolved', () => {
    // Nobody scans anything on this path, so a tenant with both on has asked for a verification that cannot
    // happen. O5d withdrew a setting for being exactly this — enforced correctly, satisfiable by nothing.
    // Choosing for them would be choosing which of their two stated intentions to ignore.
    cy.then(() => setKey(AUTO, true))
    cy.then(() => setKey(SCAN, true))

    book('Conflict_' + run, productId, 2, 'AutoDisp_' + run).then((o) => {
      confirm(o.id).then((r) => expect(r.body.success).to.eq(true))

      readOrder(o.id).then((after) => {
        expect(after.fulfilmentStatus, 'not dispatched while the two settings disagree').to.eq('NEW')
      })
    })

    cy.then(() => setKey(SCAN, false))
  })
})
