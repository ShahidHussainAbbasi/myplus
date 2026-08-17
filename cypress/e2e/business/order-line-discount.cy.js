/**
 * Per-line discount on a booked order — the ARITHMETIC, followed all the way to the invoice.
 *
 * <h3>What is being proved</h3>
 * A rep negotiates product by product. Before this, the order line could carry only a price, so the only way
 * to give 5% on one item was to overwrite that price — which arrives at the same money and destroys the
 * information: the invoice then reads as a cheaper trade price instead of "list, less discount", the
 * shopkeeper cannot see what they were given, and nobody can total what the round cost in concessions.
 *
 * <h3>Why every stage is asserted, not just the end</h3>
 * The discount crosses four boundaries — booking screen → order → sale contract → invoice — and each one is a
 * place it can be dropped in silence. One of them nearly was: the sale path reads a line discount from a
 * NESTED stock object, not from the obvious {@code SellDTO.discount}, so setting the obvious field would have
 * sent a discount that travelled the whole way and then did nothing. The end-to-end assertion is the only one
 * that could have caught it.
 *
 * <h3>The figures</h3>
 * Deliberately chosen so a rounding slip is visible rather than plausible:
 * <pre>
 *   line A   20 x 70.00 = 1400.00   less 10%      =  140.00  ->  1260.00
 *   line B   20 x 90.00 = 1800.00   less 150.00   =  150.00  ->  1650.00
 *   line C   10 x 90.00 =  900.00   no discount   =    0.00  ->   900.00
 *                        ---------                ---------      ---------
 *                          4100.00                  290.00        3810.00
 * </pre>
 */
describe('Booked order — a discount per line, all the way to the invoice', () => {
  const run = String(Date.now()).slice(-6)

  const A = { qty: 20, price: 70, discount: 140,  gross: 1400, net: 1260 }   // 10% of 1400
  const B = { qty: 20, price: 90, discount: 150,  gross: 1800, net: 1650 }   // a flat amount
  const C = { qty: 10, price: 90, discount: 0,    gross: 900,  net: 900 }

  const GROSS = 4100
  const DISCOUNT = 290
  const NET = 3810

  const ctx = { p: {} }

  before(() => {
    cy.loginAsMarketplaceOwner()
    ;[['a', 70], ['b', 90], ['c', 90]].forEach(([k, price]) => {
      cy.request({
        method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { name: 'DiscProd_' + k + '_' + run, sku: 'DP' + k.toUpperCase() + run, sellingPrice: price, taxRate: 0, unit: 'pcs' },
      }).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        ctx.p[k] = r.body.data.id
        return cy.request({
          method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
          body: { productId: ctx.p[k], quantity: 200 }, failOnStatusCode: false,
        })
      }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    })

    // A real outlet WITH a credit limit — /creditStanding answers null for an uncapped customer, and the
    // receivable assertion at the end reads `owed` off it.
    ctx.outletName = 'DiscOutlet_' + run
    cy.then(() => cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name: ctx.outletName, contact: '0300' + run, creditLimit: 100000 },
    })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserCustomer')).then((r) => {
      const rows = (r.body.collection || r.body.data || []).filter((c) => c.name === ctx.outletName)
      expect(rows.length, 'the outlet was created exactly once').to.eq(1)
      ctx.outletId = rows[0].customerId || rows[0].id
    })
  })

  const invoice = (no) => cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(no)).then((r) => {
    expect(r.body.status, 'invoice readable: ' + JSON.stringify(r.body)).to.eq('SUCCESS')
    return r.body.object
  })

  // ── stage 1 · the browser's arithmetic ────────────────────────────────────────────────────────────────

  it('the booking screen resolves a percentage the same way the sale form does', () => {
    // Not a re-implementation of the maths — the screen calls the sale form's own sellLineMath, and this
    // asserts that shared function directly. If the two ever diverged, the rep would quote one figure at the
    // counter and the till would raise another.
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => {
      expect(w.sellLineMath, 'the shared line maths is reachable from the booking screen').to.be.a('function')

      const pct = w.sellLineMath(A.price, A.qty, 0, 10, 1)     // 10 PERCENT
      expect(Number(pct.total), 'gross').to.eq(A.gross)
      expect(Number(pct.discount), '10% of 1400').to.eq(A.discount)
      expect(Number(pct.receivable), 'net').to.eq(A.net)

      const amt = w.sellLineMath(B.price, B.qty, 0, B.discount, 0)   // a flat AMOUNT
      expect(Number(amt.discount)).to.eq(B.discount)
      expect(Number(amt.receivable)).to.eq(B.net)

      // The clamp: a concession bigger than the line is a typo, not a credit note.
      const over = w.sellLineMath(10, 1, 0, 999, 0)
      expect(Number(over.discount), 'clamped to the line total').to.eq(10)
      expect(Number(over.receivable), 'never negative').to.eq(0)

      // The rounding ORDER that makes 5% of 99.99 safe: gross fixed to 2dp first, then the percentage.
      const odd = w.sellLineMath(99.99, 1, 0, 5, 1)
      expect(Number(odd.discount)).to.eq(5)
      expect(Number(odd.receivable)).to.eq(94.99)
    })
  })

  // ── stage 2 · the order ───────────────────────────────────────────────────────────────────────────────

  it('the order stores each concession and totals NET of them', () => {
    cy.loginAsOrderBooker()
    cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerId: ctx.outletId, customerName: ctx.outletName,
        customerContact: '0300' + run, shippingAddress: 'Discount Rd', paymentMode: 'CREDIT',
        items: [
          { productId: ctx.p.a, productName: 'A', quantity: A.qty, price: A.price, discount: A.discount },
          { productId: ctx.p.b, productName: 'B', quantity: B.qty, price: B.price, discount: B.discount },
          { productId: ctx.p.c, productName: 'C', quantity: C.qty, price: C.price },
        ],
      },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ctx.order = r.body.data

      // The figure the rep quotes at the counter. Gross would be wrong in the direction that starts arguments.
      expect(Number(ctx.order.total), 'order total is net of the concessions').to.eq(NET)

      const byProduct = {}
      ctx.order.items.forEach((it) => { byProduct[it.productId] = it })
      expect(Number(byProduct[ctx.p.a].discount), 'line A kept its 140').to.eq(A.discount)
      expect(Number(byProduct[ctx.p.b].discount), 'line B kept its 150').to.eq(B.discount)
      expect(byProduct[ctx.p.c].discount, 'line C has none').to.not.be.ok
      // The price is untouched — that is the whole point. A discounted line still shows what goods LIST at.
      expect(Number(byProduct[ctx.p.a].price), 'list price preserved, not quietly reduced').to.eq(A.price)
    })
  })

  it('the review screen reads the concessions back', () => {
    cy.loginAsMarketplaceOwner()
    cy.request('/getOrder?id=' + ctx.order.id).then((r) => {
      const items = r.body.data.items || []
      const total = items.reduce((sum, it) => sum + (Number(it.discount) || 0), 0)
      expect(total, 'the reviewer can see what was given away').to.eq(DISCOUNT)
    })
  })

  // ── stage 3 · the invoice ─────────────────────────────────────────────────────────────────────────────

  it('the invoice bills the discounted figure — and shows the discount, not a cheaper price', () => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
      body: { id: ctx.order.id }, failOnStatusCode: false,
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => cy.request('/getOrder?id=' + ctx.order.id)).then((r) => {
      const o = r.body.data
      return cy.request({
        method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { id: o.id, carrier: 'SAEED AHMED', lines: o.items.map((it) => ({ orderItemId: it.id, quantity: it.quantity })) },
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => cy.request('/getOrder?id=' + ctx.order.id)).then((r) => {
      ctx.invoiceNo = r.body.data.invoiceNo
      expect(ctx.invoiceNo, 'dispatch raised an invoice').to.match(/^INV-/)
    })

    cy.then(() => invoice(ctx.invoiceNo)).then((inv) => {
      // THE assertion. Anywhere in the chain that dropped the discount, this reads 4100.
      expect(Number(inv.grandTotal), 'billed net of the concessions').to.eq(NET)
      expect(Number(inv.subTotal)).to.eq(NET)

      const sales = inv.sales || inv.lines || []
      expect(sales.length, 'the invoice has its lines').to.eq(3)
      const discounted = sales.reduce((sum, l) => sum + (Number(l.discount) || 0), 0)
      expect(discounted, 'and each line carries the concession it was given').to.eq(DISCOUNT)

      // The information the whole feature exists to preserve: goods still LIST at 4,100.
      const gross = sales.reduce((sum, l) => sum + (Number(l.totalAmount) || 0), 0)
      expect(gross, 'list value intact — the price was not quietly lowered').to.eq(GROSS)
    })
  })

  it('and the outlet owes the discounted amount, not the list price', () => {
    cy.loginAsMarketplaceOwner()
    cy.request('/creditStanding?customerId=' + ctx.outletId).then((r) => {
      expect(r.body.object, 'the outlet has a credit standing').to.not.be.null
      expect(Number(r.body.object.owed), 'the receivable follows the invoice').to.eq(NET)
    })
  })
})
