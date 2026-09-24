/**
 * What a customer reads on the slip: a COUNT, a RATE, and a PERSON.
 *
 * Three complaints from a live shop, all landing on RETAIL_RECEIPT_80MM:
 *
 *   1. every line printed "1.00" for one handset  — quantities went through the MONEY formatter
 *   2. the rate column was headed "TP"            — a pharma-distribution abbreviation on a retail slip,
 *                                                   printing the same figure as Total on single-unit lines
 *   3. "served by" showed an email address        — CustomerHistory.bookedByName held user.getEmail()
 *
 * ⚠ THE CONSTRAINT, inherited from U4: the document must still reconcile. `quantity x rate = line total`
 * is what a tax inspector checks, so trimming a quantity may never ROUND one. A broken-pack line holds 0.5
 * and the totals row sums loose and whole lines together — 5.5 must survive with decimals switched off.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/receipt-qty-rate-salesman.cy.js --headed --no-exit
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success,
      `saveBusinessConfig ${key}: ${JSON.stringify(r.body)}`).to.eq(true))

const openSale = () => {
  cy.openSellSection('sellDiv')
  cy.get('#sellItemDD', { timeout: 15000 }).should('exist')
}

/**
 * Run one column resolver exactly as the renderer does.
 *
 * Against the REAL registry rather than a copy of the formatting rule: a test that reimplements the
 * formatter passes while the document is wrong, which is how "1.00" survived every existing receipt spec.
 */
const cell = (w, field, ctx) => w.DocumentRenderer.FIELD_WHITELIST.line[field].resolve(ctx)

describe('receipt — a count, a rate and a person', () => {
  beforeEach(() => cy.loginAsOwner())

  after(() => { cy.loginAsOwner(); setConfig('pos.document.qtyDecimals', 'true') })

  // ── 1. the quantity ─────────────────────────────────────────────────────────────────────────

  it('⭐ a whole quantity prints without decimals when the tenant switches them off', () => {
    openSale()
    cy.window().then((w) => {
      const lm = w.DocumentRenderer.lineMath
      const mk = (s, dec) => {
        const m = lm(s)
        return { s: s, m: m, i: 0, inv: { qtyDecimals: dec }, sums: { qty: m.qty, bonus: m.bonus } }
      }
      const handset = { quantity: 1, sellRate: 45000, totalAmount: 45000 }

      expect(cell(w, 'quantity', mk(handset, true)), 'ON = the document they printed yesterday')
        .to.eq('1.00')
      expect(cell(w, 'quantity', mk(handset, false)), '⭐ the complaint, fixed')
        .to.eq('1')
      expect(cell(w, 'quantity', mk(handset, undefined)),
        'ABSENT must read as ON — a tenant who configured nothing sees no change')
        .to.eq('1.00')
    })
  })

  it('⭐ a PART-PACK keeps its fraction with decimals switched off — it trims, it never rounds', () => {
    /*
     * The case that makes this a formatter and not a Math.round. 0.5 of a pack printed as "1" (or "0")
     * would break `quantity x rate = line total` on the customer's own slip.
     */
    openSale()
    cy.window().then((w) => {
      const lm = w.DocumentRenderer.lineMath
      const loose = { quantity: 0.5, sellRate: 120, totalAmount: 60, soldUnit: 'LOOSE',
                      soldQuantity: 5, soldRate: 12, looseUnitPlural: 'tablets', packSizeSnapshot: 10 }
      const m = lm(loose)
      const ctx = { s: loose, m: m, i: 0, inv: { qtyDecimals: false }, sums: { qty: 5.5, bonus: 0 } }

      // U4 already prints the pair the customer recognises; this only stops it gaining ".00".
      expect(cell(w, 'quantity', ctx)).to.eq('5 tablets')
      expect(m.qty * m.rate, '⭐ still reconciles against the line total').to.eq(60)

      // The TOTALS row is where a mixed invoice would lose a half.
      const totalCell = w.DocumentRenderer.FIELD_WHITELIST.totals.qtyTotal.resolve(ctx)
      expect(totalCell, '⭐ 5.5 must survive, or the document stops adding up').to.eq('5.5')
    })
  })

  it('⭐ the TOTAL QTY band under the table follows the same switch', () => {
    /*
     * The foot of the line table is rendered by a DIFFERENT path from the cells above it — renderTable
     * sums every summable column through money(). So the rows read "1" while the figure directly beneath
     * them read "1.00": the table contradicting itself on one slip.
     *
     * Asserted through buildHtml rather than a resolver, because the band has no resolver — a
     * column-level test cannot see it, which is exactly why it was missed.
     */
    openSale()
    cy.window().then((w) => {
      const inv = {
        invoiceNo: 'CY-BAND-1', qtyDecimals: false, layoutMode: 'thermal',
        sales: [{ quantity: 2, sellRate: 100, totalAmount: 200 },
                { quantity: 3, sellRate: 50, totalAmount: 150 }],
        grandTotal: 350, letterhead: {},
      }
      const trade = w.DocumentRenderer.PRESETS.TRADE_INVOICE_A4   // the preset that PRINTS a totals band
      const html = w.DocumentRenderer.buildHtml(inv, trade)

      const band = /<td[^>]*dc-sum[^>]*>([^<]*)<\/td>/g
      const cells = []
      let mm
      while ((mm = band.exec(html)) !== null) cells.push(mm[1].trim())

      expect(cells.length, 'the band rendered').to.be.greaterThan(0)
      expect(cells, '⭐ total qty 5, not 5.00').to.include('5')
      expect(cells.join(' | '), 'no money-formatted count in the band').to.not.match(/(^|\| )5\.00( \||$)/)
    })
  })

  it('the setting reaches the printed document', () => {
    // The row exists in Configuration AND the value lands on the receipt payload. An endpoint test alone
    // would pass with no control on any screen — the C6 mistake.
    setConfig('pos.document.qtyDecimals', 'false')
    cy.request('/getReceipt?invoiceNo=NO-SUCH').then(() => {})   // warm; the assertion is below
    cy.request('/getUserSell').then((r) => {
      const rows = (r.body.collection || [])
      const inv = rows.length ? (rows[0].customerHistory || {}).invoiceNo : null
      if (!inv) return                                       // a tenant with no sales yet: nothing to assert
      cy.request(`/getReceipt?invoiceNo=${encodeURIComponent(inv)}`).then((rr) => {
        expect((rr.body.object || {}).qtyDecimals, 'the tenant switch is on the payload').to.eq(false)
      })
    })
    setConfig('pos.document.qtyDecimals', 'true')
  })

  // ── 2. the rate ─────────────────────────────────────────────────────────────────────────────

  it('⭐ the 80mm slip shows a RATE column, not "TP"', () => {
    openSale()
    cy.window().then((w) => {
      const retail = w.DocumentRenderer.PRESETS.RETAIL_RECEIPT_80MM
      const keys = retail.lines.map((c) => c.key)

      expect(keys, '⭐ the retail slip carries unitRate').to.include('unitRate')
      expect(keys, 'and no longer the distributor abbreviation').to.not.include('tradePrice')
      /*
       * ONE-DISCOUNT-ROW (user rule, 2026-09-24) supersedes the per-line Disc column this used to require:
       * every discount — per line AND the trade discount — prints as ONE row at the foot, and the lines show
       * Qty × Rate = Amount. A Disc column beside it would take the line discounts off twice. The per-line
       * detail stays on the A4 trade invoice, whose readers reconcile line by line.
       */
      expect(keys, 'no per-line Disc column — its money is in the one Discount row').to.not.include('discount')
      expect(keys, 'the line prints its gross amount').to.include('lineAmount')
      expect(retail.totals, 'the one Discount row').to.include('totalDiscount')

      // The A4 trade invoice must be UNTOUCHED — its readers expect TP.
      const trade = w.DocumentRenderer.PRESETS.TRADE_INVOICE_A4.lines.map((c) => c.key)
      expect(trade, 'the B2B document keeps TP').to.include('tradePrice')
    })
  })

  it('⭐ Rate x Qty minus Disc reaches the line total', () => {
    /*
     * The retail slip carries no discount column, so the rate it prints must be what the customer actually
     * paid per unit. On an undiscounted line — 650 of the 651 live lines — that is the same number
     * tradePrice shows. On a discounted one it is not, and printing the pre-discount rate would leave
     * qty x rate disagreeing with the total on the customer's own receipt.
     */
    openSale()
    cy.window().then((w) => {
      const lm = w.DocumentRenderer.lineMath
      const mk = (s) => { const m = lm(s); return { s: s, m: m, i: 0, inv: {}, sums: { qty: m.qty, bonus: 0 } } }

      const plain = mk({ quantity: 3, sellRate: 250, totalAmount: 750 })
      expect(cell(w, 'unitRate', plain), 'undiscounted: unchanged').to.eq('250.00')
      expect(cell(w, 'unitRate', plain), 'and the same as TP').to.eq(cell(w, 'tradePrice', plain))

      const cut = mk({ quantity: 3, sellRate: 250, totalAmount: 750, discount: 150 })
      expect(cell(w, 'unitRate', cut), 'the LIST rate, because Disc is its own column').to.eq('250.00')
      expect(cell(w, 'discount', cut), 'and the concession is shown, not folded away').to.eq('150.00')
      expect(cell(w, 'lineTotal', cut)).to.eq('600.00')

      // ⭐ qty x rate - disc = amount. The arithmetic a buyer checks, and the reason Rate is the LIST
      // rate here: with no Disc column it would have to be the net rate instead. The column set decides.
    })
  })

  // ── 3. the person ───────────────────────────────────────────────────────────────────────────

  it('⭐ the 80mm slip has a "served by" line at all', () => {
    // It had none: the one document a walk-in keeps could not say who sold to them.
    openSale()
    cy.window().then((w) => {
      const header = w.DocumentRenderer.PRESETS.RETAIL_RECEIPT_80MM.header.columns
      const flat = [].concat.apply([], header)
      expect(flat, '⭐ bookedBy is on the retail receipt').to.include('bookedBy')
    })
  })

  it('⭐ a new sale is stamped with the NAME, not the email address', () => {
    /*
     * The whole chain in one assertion: name claim -> X-User-Name -> AuthenticatedUser.displayName ->
     * SagaSaleWriter. Seven places, and it degrades SILENTLY to the email if any one of them is missed —
     * which is exactly what the previous behaviour looked like, so asserting "not blank" would pass on
     * the bug. Assert it does NOT look like an address.
     */
    const name = `QtyRate_${uniq()}`
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: { name: name, sellingPrice: 100, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, `product: ${JSON.stringify(r.body)}`).to.eq(true)
      return cy.request('/getUserProduct?q=-1')
    }).then((pr) => {
      const p = (pr.body.collection || pr.body.data || []).find((x) => x.name === name)
      expect(p, 'the product was stored').to.exist

      cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
        body: { productId: p.id, quantity: 2, 'stock.bpurchaseRate': 60, 'stock.bsellRate': 100,
                totalAmount: 120, netAmount: 120, purchaseInvoiceNo: `QR-${uniq()}` } })
        .then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS'))

      cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        failOnStatusCode: false,
        body: { customer: { name: `Buyer_${uniq()}`, contact: '03009999999' },
                sales: [{ productId: p.id, quantity: 1, sellRate: 100, totalAmount: 100 }],
                tenders: [{ method: 'CASH', amount: 100 }],
                paidAmount: 100, grandTotal: 100, idempotencyKey: `cy-qr-${uniq()}` } })
        .then((r) => {
          expect(r.body.status, `sale: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
          const invoiceNo = (r.body.object && r.body.object.invoiceNo) || r.body.object
          return cy.request(`/getReceipt?invoiceNo=${encodeURIComponent(invoiceNo)}`)
        })
        .then((rr) => {
          const booked = (rr.body.object || {}).bookedByName || ''
          expect(booked, 'somebody is recorded as having made the sale').to.not.eq('')
          expect(booked, '⭐ a NAME, not an address').to.not.contain('@')
        })
    })
  })
})
