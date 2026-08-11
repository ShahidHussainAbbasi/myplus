/**
 * OMS O7 D2 — the order booker: identity, attribution, own-orders, and credit AT the counter.
 * Design: microservices/docs/slices/oms-O7-distribution-presales.md
 *
 * D1 built the review phase but attributed nothing — an order arrived in the queue with no way to say which
 * rep took it, so the founding requirement's *"after confirm or reject the status should be visible to the
 * order booker"* was unimplementable.
 *
 * The case that carries the slice is **"a booker cannot confirm their own order"**: the entire pre-sales model
 * rests on the person who books not being the person who releases, and everything else here is bookkeeping
 * around that one separation.
 */
describe('OMS O7 D2 — a field rep books, and cannot approve their own work', () => {
  const run = String(Date.now()).slice(-6)
  let productId, outletId

  before(() => {
    // Seeded as the OWNER: the booker is a member of this same org, so everything below is one tenant.
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'BookerProd_' + run, sku: 'BK' + run, sellingPrice: 50, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 100 }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    // An outlet WITH a credit limit — the whole point of showing standing at the counter.
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name: 'Irfan Medical ' + run, contact: '0300' + run, creditLimit: 1000 },
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.request('/getUserCustomer').then((r) => {
      const rows = r.body.collection || r.body.data || []
      const mine = rows.find((c) => c.name === 'Irfan Medical ' + run)
      expect(mine, 'the outlet was created').to.exist
      outletId = mine.customerId || mine.id
    })
  })

  const bookAs = (label) => cy.request({
    method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customerName: label, customerContact: '0300' + run,
      items: [{ productId, quantity: 4, price: 50, productName: 'BookerProd_' + run }],
    },
  })

  it('THE SEPARATION — a booker can book, but cannot confirm or reject their own order', () => {
    // The control the whole pre-sales model rests on. ROLE_ORDER_BOOKER deliberately carries no
    // ADMIN_PRIVILEGE, so this must be a 403 from the server — not a hidden button, which proves nothing.
    cy.loginAsOrderBooker()
    bookAs('SelfApprove ' + run).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const id = r.body.data.id
      cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id }, failOnStatusCode: false,
      }).then((s) => expect(s.body.success, 'a rep must not release their own order').to.not.eq(true))
      cy.request({
        method: 'POST', url: '/rejectOrder', headers: { 'Content-Type': 'application/json' },
        body: { id, reason: 'nice try' }, failOnStatusCode: false,
      }).then((s) => expect(s.body.success, 'nor reject it').to.not.eq(true))
    })
  })

  it('the order is attributed to the rep who took it', () => {
    cy.loginAsOrderBooker()
    bookAs('Attributed ' + run).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.bookedByName, 'stamped at write, so it survives the rep leaving')
        .to.contain('booker.marketplace')
      expect(r.body.data.bookedByUserId).to.be.a('number')
    })
  })

  it('a rep sees THEIR OWN orders, and the admin sees everyone\'s', () => {
    // The founding requirement: "after confirm or reject the status should be visible to the order booker".
    const mineLabel = 'MineOnly ' + run
    cy.loginAsOrderBooker()
    bookAs(mineLabel).then(() => cy.request('/getOrders?mine=true')).then((r) => {
      const rows = (r.body.data && r.body.data.content) || []
      expect(rows.length, 'the rep has orders of their own').to.be.greaterThan(0)
      rows.forEach((o) => {
        expect(o.bookedByName, 'every row on "mine" is mine').to.contain('booker.marketplace')
      })
    })

    // The admin's queue is not filtered to the admin — they must see what the reps booked, or there is
    // nothing to review.
    cy.loginAsMarketplaceOwner()
    cy.request('/getOrders?q=' + encodeURIComponent(mineLabel)).then((r) => {
      const rows = (r.body.data && r.body.data.content) || []
      expect(rows.length, 'the reviewer sees the rep\'s order').to.be.greaterThan(0)
      expect(rows[0].bookedByName).to.contain('booker.marketplace')
    })
  })

  it('"mine" cannot be pointed at a colleague', () => {
    // `mine` is a BOOLEAN and the server resolves whose. If it took an id, a rep could read a colleague's book
    // by editing a number in the URL — the anti-IDOR rule this platform applies everywhere else.
    cy.loginAsOrderBooker()
    cy.request('/getOrders?mine=true&bookedBy=99999').then((r) => {
      const rows = (r.body.data && r.body.data.content) || []
      rows.forEach((o) => expect(o.bookedByName).to.contain('booker.marketplace'))
    })
  })

  it('the booker is told the outlet\'s credit standing BEFORE writing the order', () => {
    // Finding B3. Rejecting an over-limit order a day later wastes the visit; the credit engine already
    // existed and simply was not exposed at the counter.
    cy.loginAsOrderBooker()
    cy.request('/creditStanding?customerId=' + outletId).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      const s = r.body.object
      expect(s, 'an outlet WITH a limit has a standing').to.not.be.null
      expect(Number(s.creditLimit)).to.eq(1000)
      expect(s).to.have.property('owed')
      expect(s).to.have.property('available')
      expect(s.overLimit).to.eq(false)
    })
  })

  it('an outlet with NO limit reports no standing rather than a false zero', () => {
    // A customer with no limit is uncapped, not "at 0% of 0". Showing them as breached would train bookers to
    // ignore the warning — which is the failure mode that matters for a warning.
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name: 'NoLimit ' + run, contact: '0311' + run },
    })
    cy.request('/getUserCustomer').then((r) => {
      const rows = r.body.collection || r.body.data || []
      const c = rows.find((x) => x.name === 'NoLimit ' + run)
      expect(c, 'the uncapped outlet exists').to.exist
      return cy.request('/creditStanding?customerId=' + (c.customerId || c.id))
    }).then((r) => {
      expect(r.body.status).to.eq('SUCCESS')
      expect(r.body.object, 'uncapped means NO standing, not a zero one').to.be.oneOf([null, undefined])
    })
  })

  it('the admin can still confirm what the rep booked — the separation cuts one way only', () => {
    let id
    cy.loginAsOrderBooker()
    bookAs('AdminConfirms ' + run).then((r) => { id = r.body.data.id })
    cy.then(() => cy.loginAsMarketplaceOwner())
    cy.then(() => cy.request({
      method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
      body: { id }, failOnStatusCode: false,
    })).then((s) => {
      expect(s.body.success, JSON.stringify(s.body)).to.eq(true)
      expect(s.body.data.fulfilmentStatus).to.eq('NEW')
      expect(s.body.data.bookedByName, 'attribution survives the review').to.contain('booker.marketplace')
    })
  })
})
