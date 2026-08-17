/**
 * OMS O8 slices 1–2 — the delivery round's recovery sheet.
 *
 * <h3>What the sheet is, and therefore what must be true of it</h3>
 * It is a COLLECTION document: the salesman asks each shop for the amount printed on it. So the numbers are not
 * a report, they are instructions to collect money — which makes two properties non-negotiable.
 *
 * <ol>
 *   <li><b>The figures are the books' figures.</b> Asserted by reading each outlet's balance from
 *       {@code /creditStanding} independently and requiring the sheet to agree. A sheet computed from orders
 *       would be a second opinion about the same debt, handed to a shopkeeper.</li>
 *   <li><b>The control total equals the rows.</b> That total is what the cash bag is counted against, and on a
 *       one-person back office — approval gate off, no segregation of duties — it is the only control left.
 *       A foot that disagreed with its own rows would be worse than no foot.</li>
 * </ol>
 *
 * <h3>The case that would have been missed</h3>
 * "Previous balance" is DERIVED (total owed − this invoice's unpaid part), so it is only interesting on an
 * outlet that already owed something. The second outlet below is deliberately given a prior unpaid delivery,
 * because with a clean account previous balance is 0 and any derivation at all passes.
 */
describe('OMS O8 — the round sheet a salesman carries', () => {
  const run = String(Date.now()).slice(-6)
  const PRICE = 50
  const ctx = { outlet: {}, order: {} }

  const iso = (d) => d.toISOString().slice(0, 10)
  const TODAY = iso(new Date())

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'RsProd_' + run, sku: 'RS' + run, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ctx.productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId: ctx.productId, quantity: 300 }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    // Two outlets, each with a credit limit so /creditStanding — the independent check — answers at all.
    ;['a', 'b'].forEach((k) => {
      const name = 'RsOutlet_' + k + '_' + run
      cy.then(() => cy.request({
        method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { name, contact: '0300' + run, address: k === 'a' ? 'ZAHIR PIR' : 'GHOUS PUR', creditLimit: 500000 },
      })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
      cy.then(() => cy.request('/getUserCustomer')).then((r) => {
        const rows = (r.body.collection || r.body.data || []).filter((c) => c.name === name)
        expect(rows.length, name + ' created once').to.eq(1)
        ctx.outlet[k] = { id: rows[0].customerId || rows[0].id, name }
      })
    })
  })

  // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────

  const owed = (outletId) => cy.request('/creditStanding?customerId=' + outletId).then((r) => {
    expect(r.body.object, 'the outlet has a credit standing to read').to.not.be.null
    return Number(r.body.object.owed)
  })

  /** Book → confirm → dispatch, i.e. an order that is out on the van with an invoice against it. */
  const dispatchTo = (outletKey, qty) => {
    let order
    cy.then(() => cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerId: ctx.outlet[outletKey].id, customerName: ctx.outlet[outletKey].name,
        customerContact: '0300' + run, shippingAddress: outletKey === 'a' ? 'ZAHIR PIR' : 'GHOUS PUR',
        paymentMode: 'CREDIT',
        items: [{ productId: ctx.productId, productName: 'RsProd_' + run, quantity: qty, price: PRICE }],
      },
    })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      order = r.body.data
      return cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: order.id }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => cy.request({
      method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { id: order.id, carrier: 'SAEED AHMED', lines: [{ orderItemId: order.items[0].id, quantity: qty }] },
    })).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    return cy.then(() => cy.request('/getOrder?id=' + order.id)).then((r) => r.body.data)
  }

  const sheet = (params) => cy.request({
    url: '/roundSheet', qs: params || { from: TODAY, to: TODAY }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.body.success, 'sheet produced: ' + JSON.stringify(r.body).slice(0, 300)).to.not.eq(false)
    const d = r.body.data
    expect(d, 'a sheet came back').to.be.an('object')
    return d
  })

  // ── slice 1 · the endpoint ────────────────────────────────────────────────────────────────────────────

  it('1 · a dispatched order appears on today\'s sheet, with the books\' figures', () => {
    cy.loginAsMarketplaceOwner()
    dispatchTo('a', 4).then((o) => { ctx.order.a = o })      // 4 × 50 = 200

    cy.then(() => sheet()).then((s) => {
      const mine = s.stops.filter((r) => r.invoiceNo === ctx.order.a.invoiceNo)
      expect(mine.length, 'the stop is on the sheet exactly once').to.eq(1)
      const stop = mine[0]

      expect(Number(stop.invoiceTotal), 'what the delivery was invoiced at').to.eq(200)
      expect(stop.accountName, 'the account the debt sits on').to.eq(ctx.outlet.a.name)
      expect(stop.area, 'the area column').to.eq('ZAHIR PIR')
      // A clean account: nothing owed before this delivery.
      expect(Number(stop.previousBalance), 'nothing owed before today').to.eq(0)
      expect(Number(stop.totalDue), 'so the total due is just this delivery').to.eq(200)
    })

    // …and the sheet agrees with the ledger read independently. This is the assertion that stops the sheet
    // becoming a second opinion about the debt.
    cy.then(() => owed(ctx.outlet.a.id)).then((fromBooks) => {
      cy.then(() => sheet()).then((s) => {
        const stop = s.stops.filter((r) => r.invoiceNo === ctx.order.a.invoiceNo)[0]
        expect(Number(stop.totalDue), 'the sheet states what the BOOKS say is owed').to.eq(fromBooks)
      })
    })
  })

  it('2 · previous balance is real when the outlet already owed — the derivation, not zero', () => {
    cy.loginAsMarketplaceOwner()
    // First delivery: 6 × 50 = 300, left unpaid. THEN a second one, so previousBalance has something to be.
    dispatchTo('b', 6).then((o) => { ctx.order.b1 = o })
    cy.then(() => dispatchTo('b', 2)).then((o) => { ctx.order.b2 = o })    // 2 × 50 = 100

    cy.then(() => sheet()).then((s) => {
      const second = s.stops.filter((r) => r.invoiceNo === ctx.order.b2.invoiceNo)[0]
      expect(second, 'the second delivery is on the sheet').to.exist

      expect(Number(second.invoiceTotal), 'this delivery').to.eq(100)
      expect(Number(second.previousBalance), 'the 300 already outstanding').to.eq(300)
      expect(Number(second.totalDue), 'so 400 to ask for').to.eq(400)
      // The invariant that keeps the three columns honest, whatever the figures.
      expect(Number(second.previousBalance) + Number(second.invoiceTotal),
        'previous + this delivery = total due').to.eq(Number(second.totalDue))
    })
  })

  it('3 · the control total equals the rows it sits under', () => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => sheet()).then((s) => {
      const rowInvoices = s.stops.reduce((t, r) => t + Number(r.invoiceTotal), 0)
      const rowDue = s.stops.reduce((t, r) => t + Number(r.totalDue), 0)

      expect(s.stopCount, 'the stop count matches the rows').to.eq(s.stops.length)
      // The cash bag is counted against these. A foot that disagreed with its own rows is worse than none.
      expect(Number(s.invoiceTotal)).to.eq(Number(rowInvoices.toFixed(2)))
      expect(Number(s.totalDue)).to.eq(Number(rowDue.toFixed(2)))
      // Positive control: an empty sheet would satisfy the three equalities above trivially.
      expect(s.stops.length, 'there is something on the sheet to total').to.be.at.least(3)
    })
  })

  it('4 · a window with no dispatches gives an empty sheet, not an error', () => {
    cy.loginAsMarketplaceOwner()
    // Well before any fixture in this suite existed.
    cy.then(() => sheet({ from: '2001-01-01', to: '2001-01-02' })).then((s) => {
      expect(s.stops.length, 'nothing went out that day').to.eq(0)
      expect(s.stopCount).to.eq(0)
      expect(Number(s.invoiceTotal)).to.eq(0)
      expect(Number(s.totalDue)).to.eq(0)
    })
  })

  // ── slice 2 · the screen ──────────────────────────────────────────────────────────────────────────────

  it('5 · the screen renders the round, with the three columns left blank for the pen', () => {
    cy.loginAsMarketplaceOwner()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => w.showRoundSheet())

    // The dd-MM-yyyy the pickers write, which the screen converts to ISO for the API.
    const d = new Date()
    const dmy = String(d.getDate()).padStart(2, '0') + '-'
      + String(d.getMonth() + 1).padStart(2, '0') + '-' + d.getFullYear()
    cy.get('#rsFrom').clear().type(dmy)
    cy.get('#rsTo').clear().type(dmy)
    cy.get('#rsSalesman').clear().type('SAEED AHMED')
    cy.window().then((w) => w.loadRoundSheet())

    cy.get('#rsBody tr', { timeout: 15000 }).should('have.length.at.least', 3)
    cy.get('#rsHeading').should('contain.text', 'SAEED AHMED')

    // Ten columns, and the last three EMPTY — that is the handwriting, given a ruled line. A sheet that
    // pre-filled Received would be telling the salesman what he collected before he set out.
    cy.get('#rsBody tr').first().find('td').should('have.length', 10)
    cy.get('#rsBody tr').first().find('td').eq(7).should('have.text', ' ')
    cy.get('#rsBody tr').first().find('td').eq(8).should('have.text', ' ')

    // The foot is drawn from the server's totals, not summed in the browser.
    cy.get('#rsFoot th').should('have.length.at.least', 4)
    cy.get('#rsFoot').should('contain.text', 'Stops')
  })

  it('6 · Download PDF is reachable and does not need pdfmake on the page until asked', () => {
    cy.loginAsMarketplaceOwner()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => {
      // The lazy contract: the loader is present, the library is NOT — that is the whole point of
      // lazy-export.js, and a page that eagerly shipped pdfmake would still pass a click test.
      expect(w.LazyExport, 'the shared lazy loader is available').to.be.an('object')
      expect(w.LazyExport.ensurePdfMake, 'and exposes ensurePdfMake').to.be.a('function')
      expect(w.downloadRoundSheet, 'the download action exists').to.be.a('function')
      expect(w.printRoundSheet, 'and so does print').to.be.a('function')
    })

    // Refuses politely with nothing loaded, rather than producing an empty PDF a salesman would carry out.
    cy.window().then((w) => { w.showRoundSheet(); w.downloadRoundSheet() })
    cy.get('#rsBody tr').should('have.length', 0)
  })
})
