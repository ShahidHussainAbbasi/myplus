/**
 * SRA-3 — every invoice records WHICH SALES REP took the order and HOW it came in.
 * Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §6 · Test Book section 29 (C1–C6).
 *
 * The rule (SalesRepAttribution, business-service):
 *   the booker's login IS a rep            → that rep          REP_LOGIN   (a rep cannot book in a colleague's name)
 *   otherwise a rep was chosen (the office) → the chosen rep    REP_PAPER   (checked against THIS business's reps)
 *   otherwise                               → no rep            COUNTER (till) · OFFICE (an order) · PORTAL (online)
 * An edit keeps the stamped rep; only a rep.manage holder may change it, and the change is audited.
 * PORTAL is covered by SalesRepAttributionTest (a storefront checkout is not drivable from here).
 *
 * Tenant: owner.marketplace@ (the distributor, holds rep.manage). Rep with a login: booker.marketplace@.
 * Leaves no server state another spec could trip over: the booker is UNLINKED and the reps deactivated in after()
 * (a booker left linked to a rep narrows every booking-screen spec); the till sales are voided.
 */
const run = String(Date.now()).slice(-6)
const PRICE = 50
const list = (body) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
  return []
}
const ctx = { reps: [], invoices: [] }

const addRep = (body) => cy.request({ method: 'POST', url: '/addSalesRep', form: true, failOnStatusCode: false, body })
const updateRep = (body) => cy.request({ method: 'POST', url: '/updateSalesRep', form: true, failOnStatusCode: false, body })
const reps = () => cy.request('/getSalesReps').then((r) => list(r.body))
const userIdOf = (email) =>
  cy.request({ method: 'POST', url: 'http://localhost:8765/api/auth/login', headers: { 'Content-Type': 'application/json' },
    body: { email, password: 'Demo@2025!' } }).then((r) => {
    const claims = JSON.parse(atob(r.body.data.accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return Number(claims.userId)
  })
/** The invoice as the receipt reads it — rep, source and "Booked By" are all stamped on it. */
const receipt = (invoiceNo) => cy.request(`/getReceipt?invoiceNo=${encodeURIComponent(invoiceNo)}`).then((r) => {
  expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
  return r.body.object
})
const order = (id) => cy.request('/getOrder?id=' + id).then((r) => r.body.data)
const post = (url, body) => cy.request({ method: 'POST', url, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false, body })
const tillSale = (extra) => post('/addSell', Object.assign({
  customer: { customerId: ctx.outletId, name: ctx.outletName, contact: ctx.outletContact },
  sales: [{ productId: ctx.productId, quantity: 1, sellRate: PRICE, totalAmount: PRICE, netAmount: PRICE }],
  paidAmount: 0, dueAmount: 0, grandTotal: PRICE,
}, extra))
/** Book (as whoever is signed in), confirm and dispatch (as the owner) — the dispatch raises the invoice. */
const bookAndDispatch = (repId) => {
  const body = {
    customerId: ctx.outletId, customerName: ctx.outletName, customerContact: ctx.outletContact,
    items: [{ productId: ctx.productId, quantity: 2, price: PRICE, productName: 'SraProd_' + run }],
  }
  if (repId !== undefined) body.repId = repId
  return post('/bookOrder', body).then((r) => {
    expect(r.body.success, 'booked: ' + JSON.stringify(r.body).slice(0, 300)).to.eq(true)
    const o = r.body.data
    cy.loginAsMarketplaceOwner()
    post('/confirmOrder', { id: o.id }).its('body.success').should('eq', true)
    post('/shipOrder', { id: o.id, lines: [{ orderItemId: o.items[0].id, quantity: 2 }], carrier: 'SRA van' })
      .then((s) => expect(s.body.success, JSON.stringify(s.body).slice(0, 300)).to.eq(true))
    return order(o.id).then((d) => {
      expect(d.invoiceNo, 'the dispatch raised the invoice').to.match(/^INV-/)
      return d
    })
  })
}

describe('SRA-3 — the rep and the source on every invoice', () => {
  before(() => {
    cy.loginAsMarketplaceOwner()
    userIdOf('booker.marketplace@myplus.com').then((id) => { ctx.bookerUserId = id })
    reps().then((all) => {   // an interrupted run may have left the booker linked
      const linked = all.find((x) => Number(x.userId) === ctx.bookerUserId)
      if (linked) updateRep({ id: linked.id, userId: 0 })
    })
    addRep({ name: `SRA3 Bilal ${run}` }).then((r) => {   // a rep who never logs in — his orders come on paper
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      ctx.bilal = r.body.object.id
      ctx.reps.push(ctx.bilal)
    })
    cy.then(() => addRep({ name: `SRA3 Booker ${run}`, userId: ctx.bookerUserId })).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      ctx.bookerRep = r.body.object.id
      ctx.reps.push(ctx.bookerRep)
    })
    post('/addProduct', { name: 'SraProd_' + run, sku: 'SR' + run, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ctx.productId = r.body.data.id
      return post('/addProductStock', { productId: ctx.productId, quantity: 100 })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    // The outlet, with Bilal as its USUAL rep — the screens default to him.
    ctx.outletName = 'SRA3 Outlet ' + run
    ctx.outletContact = '0301' + run
    cy.then(() => cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name: ctx.outletName, contact: ctx.outletContact, creditLimit: 100000, defaultRepId: ctx.bilal } }))
      .its('body.status').should('eq', 'SUCCESS')
    cy.request('/getUserCustomer').then((r) => {
      const c = list(r.body).find((x) => x.name === ctx.outletName)
      expect(c, 'the outlet was created').to.exist
      expect(c.defaultRepId, 'with Bilal as its usual rep').to.eq(ctx.bilal)
      ctx.outletId = c.customerId
    })
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  after(() => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => ctx.invoices.forEach((invoiceNo) => cy.request({ method: 'POST', url: '/voidSell', form: true,
      failOnStatusCode: false, body: { invoiceNo, reason: 'CY SRA-3 cleanup' } })))
    cy.then(() => ctx.reps.forEach((id) => updateRep({ id, userId: 0, status: 'INACTIVE' })))
  })

  // ── C1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('C1 — the office keys a rep\'s PAPER order: the invoice credits the rep, and names who keyed it', () => {
    bookAndDispatch(ctx.bilal).then((o) => {
      expect(o.repId, 'the order carries the rep the office chose').to.eq(ctx.bilal)
      receipt(o.invoiceNo).then((inv) => {
        expect(inv.repId, 'credited to Bilal').to.eq(ctx.bilal)
        expect(inv.repName, 'his name stamped on the invoice').to.eq(`SRA3 Bilal ${run}`)
        expect(inv.saleSource).to.eq('REP_PAPER')
        expect(inv.bookedByName, '"Booked By" is who keyed it').to.be.a('string').and.not.be.empty
      })
    })
  })

  // ── C2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('C2 — a rep booking signed in is credited themselves, cannot book as a colleague, and the dispatcher is not named', () => {
    cy.loginAsOrderBooker()
    bookAndDispatch(ctx.bilal).then((o) => {   // the booker TRIES to credit Bilal
      expect(o.repId ?? null, 'a rep\'s choice of rep is dropped at booking').to.eq(null)
      receipt(o.invoiceNo).then((inv) => {
        expect(inv.repId, 'credited to the rep linked to the booker\'s login').to.eq(ctx.bookerRep)
        expect(inv.repName).to.eq(`SRA3 Booker ${run}`)
        expect(inv.saleSource).to.eq('REP_LOGIN')
        // D1: the invoice used to name whoever DISPATCHED the order (the owner) as "Booked By".
        expect(inv.bookedByName, 'Booked By = the booker, not the dispatcher').to.contain('booker')
      })
    })
  })

  // ── C3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('C3 — a counter sale has no rep; the till can credit a paper order\'s rep; a foreign rep id is refused', () => {
    tillSale({}).then((r) => {
      expect(r.body.status, JSON.stringify(r.body).slice(0, 300)).to.eq('SUCCESS')
      ctx.invoices.push(r.body.object)
      receipt(r.body.object).then((inv) => {
        expect(inv.repId ?? null, 'no rep').to.eq(null)
        expect(inv.saleSource).to.eq('COUNTER')
      })
    })
    tillSale({ repId: 0 }).then((r) => {
      ctx.invoices.push(r.body.object)
      receipt(r.body.object).its('saleSource').should('eq', 'COUNTER')   // 0 = "No rep (counter)"
    })
    tillSale({ repId: ctx.bilal }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body).slice(0, 300)).to.eq('SUCCESS')
      ctx.invoices.push(r.body.object)
      ctx.tillInvoice = r.body.object
      receipt(r.body.object).then((inv) => {
        expect(inv.repId).to.eq(ctx.bilal)
        expect(inv.saleSource).to.eq('REP_PAPER')
      })
    })
    tillSale({ repId: 987654321 }).then((r) => {
      expect(r.body.status, 'not credited to nobody').to.not.eq('SUCCESS')
      expect(JSON.stringify(r.body)).to.contain('sales rep was not found')
    })
  })

  // ── C4 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('C4 — an edit keeps the stamped rep; a rep manager may change it, and the change is audited', () => {
    receipt(ctx.tillInvoice).then((inv) => {
      const edit = (repId) => post('/updateSell', {
        customer_history_id: inv.customer_history_id,
        customer: { customerId: ctx.outletId, name: ctx.outletName, contact: ctx.outletContact },
        sales: [{ productId: ctx.productId, quantity: 1, sellRate: PRICE, totalAmount: PRICE, netAmount: PRICE }],
        repId,
      })
      edit(undefined).its('body.status').should('eq', 'SUCCESS')   // the edit did not touch the rep
      receipt(ctx.tillInvoice).its('repId').should('eq', ctx.bilal)
      edit(ctx.bookerRep).then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 300)).to.eq('SUCCESS'))
      receipt(ctx.tillInvoice).then((after) => {
        expect(after.repId, 'the owner moved the credit').to.eq(ctx.bookerRep)
        expect(after.repName).to.eq(`SRA3 Booker ${run}`)
      })
      cy.findAudit((x) => x.action === 'SALE_REP_CHANGE' && x.entityRef === ctx.tillInvoice, `SALE_REP_CHANGE ${ctx.tillInvoice}`)
    })
  })

  // ── C5 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('C5 — the sale screen: the Rep picker defaults to the customer\'s usual rep, and the sale is credited to him', () => {
    cy.viewport(1400, 900)
    cy.visitSaleScreen()
    cy.get('#sellRepWrap', { timeout: 15000 }).should('be.visible')
    cy.get(`#sellRepId option[value="${ctx.bilal}"]`).should('exist')
    cy.get(`#sellCustomerDD option[value="${ctx.outletId}"]`, { timeout: 15000 }).should('exist')
    cy.get('#sellCustomerDD').select(String(ctx.outletId), { force: true })
    cy.get('#sellRepId').should('have.value', String(ctx.bilal))
    cy.get('#sellRepId option:selected').should('have.text', `SRA3 Bilal ${run}`)
    // The payload the till sends carries it (the monolith's DTO twin keeps it on the hop).
    cy.window().then((w) => expect(Number(w.$('#sellRepId').val())).to.eq(ctx.bilal))
  })

  // ── C6 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('C6 — the booking screen: a manager picks the rep (defaulted from the shop); a rep never sees the picker', () => {
    cy.viewport(1400, 900)
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showOrderBooking')
    cy.window().then((w) => w.showOrderBooking())
    cy.get(`#bkOutlet option[value="${ctx.outletId}"]`, { timeout: 15000 }).should('exist')
    cy.get('#bkOutlet').select(String(ctx.outletId), { force: true })
    cy.get('#bkRepWrap').should('be.visible')
    cy.get('#bkRepId').should('have.value', String(ctx.bilal))

    cy.loginAsOrderBooker()
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showOrderBooking')
    cy.window().then((w) => w.showOrderBooking())
    cy.get('#bkOutlet').should('exist')   // positive control: the booking screen is there
    cy.get('#bkRepId').should('not.exist')
    cy.request('/outlets').then((r) => {
      const rows = list(r.body)
      expect(rows.length, 'positive control: the rep has outlets (their own + unassigned)').to.be.greaterThan(0)
      // The booker is linked to a rep here, so the SRA-2 territory rule hides Bilal's shop from them.
      expect(rows.find((x) => x.id === ctx.outletId), 'a colleague\'s outlet is not in their list').to.eq(undefined)
      rows.forEach((x) => expect(x.defaultRepId ?? null, `row ${x.id} does not say who covers it`).to.eq(null))
    })
  })
})
