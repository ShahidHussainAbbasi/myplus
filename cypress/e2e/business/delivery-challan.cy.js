/**
 * OMS O8 slices 3–4 — the per-stop slip the shop signs for, and document PDF download.
 *
 * <h3>The two things that must be true of the slip</h3>
 * <ol>
 *   <li><b>It is a CHALLAN, not a second invoice.</b> One sale may produce only one taxable document. A slip
 *       that read as an invoice would create a second record of the same supply — a duplicate tax entry and an
 *       argument about which copy is real. So it is titled Delivery Challan and carries the INVOICE NUMBER,
 *       which is what lets the shopkeeper match the two.</li>
 *   <li><b>It carries what a pharmaceutical delivery legally and practically needs</b> — batch and expiry per
 *       line (recall traceability), the list price and the discount separately, and a signature block.</li>
 * </ol>
 *
 * <h3>Why it is asserted through the RENDERER and not by eyeballing a PDF</h3>
 * A PDF's bytes tell you nothing useful in a test. What matters is the resolved document — the same model both
 * the printed page and the PDF are drawn from — so this drives {@code DocumentRenderer.toPrintModel} and checks
 * the labels, cells and totals it produces. If those are right, both outputs are right; if a second layout ever
 * appeared, this is what would stop agreeing with the paper.
 */
describe('OMS O8 — the delivery challan, and document PDF download', () => {
  const run = String(Date.now()).slice(-6)
  const PRICE = 90
  const QTY = 8
  const LINE_DISCOUNT = 72          // 10% of 720
  const ctx = {}

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      // `unit` is what prints in the Packing column of a distribution document.
      body: { name: 'ChProd_' + run, sku: 'CH' + run, sellingPrice: PRICE, taxRate: 0, unit: '1000 ml' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ctx.productId = r.body.data.id
      // Batch + expiry are stocked WITH the goods, and they are the fields a pharma challan must carry.
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId: ctx.productId, quantity: 100, batchNo: 'A265007', expiryDate: '2027-09-30' },
        failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    ctx.outletName = 'ChOutlet_' + run
    cy.then(() => cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name: ctx.outletName, contact: '0300' + run, address: 'ZAHIR PIR', creditLimit: 200000 },
    })).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserCustomer')).then((r) => {
      const rows = (r.body.collection || r.body.data || []).filter((c) => c.name === ctx.outletName)
      expect(rows.length, 'the outlet was created once').to.eq(1)
      ctx.outletId = rows[0].customerId || rows[0].id
    })

    // Book → confirm → dispatch, so there is a real invoice for the challan to reference. With a LINE
    // DISCOUNT, because the challan must show list-less-discount rather than a quietly reduced rate.
    cy.then(() => cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerId: ctx.outletId, customerName: ctx.outletName, customerContact: '0300' + run,
        shippingAddress: 'ZAHIR PIR', paymentMode: 'CREDIT',
        items: [{ productId: ctx.productId, productName: 'ChProd_' + run, quantity: QTY, price: PRICE,
          discount: LINE_DISCOUNT }],
      },
    })).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ctx.order = r.body.data
      return cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: ctx.order.id }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => cy.request({
      method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { id: ctx.order.id, carrier: 'SAEED AHMED',
        lines: [{ orderItemId: ctx.order.items[0].id, quantity: QTY }] },
    })).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.then(() => cy.request('/getOrder?id=' + ctx.order.id)).then((r) => {
      ctx.invoiceNo = r.body.data.invoiceNo
      expect(ctx.invoiceNo, 'dispatch raised the invoice the challan references').to.match(/^INV-/)
    })
  })

  /** The resolved document, exactly as both the printed page and the PDF are drawn from it. */
  const model = (presetName) => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    return cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(ctx.invoiceNo)).then((r) => {
      expect(r.body.status, 'invoice readable: ' + JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
      return cy.window().then((w) => {
        const DR = w.DocumentRenderer
        expect(DR, 'the renderer is on the page').to.be.an('object')
        expect(DR.toPrintModel, 'and exposes the neutral print model').to.be.a('function')
        const preset = presetName ? DR.PRESETS[presetName] : null
        expect(presetName ? preset : true, 'preset ' + presetName + ' exists').to.be.ok
        return DR.toPrintModel(r.body.object, preset)
      })
    })
  }

  // ── slice 3 · the challan ─────────────────────────────────────────────────────────────────────────────

  it('is titled a CHALLAN and references the invoice — never a second invoice', () => {
    cy.loginAsMarketplaceOwner()
    model('DELIVERY_CHALLAN_A4').then((m) => {
      expect(m.title.toUpperCase(), 'says what it is').to.contain('CHALLAN')
      expect(m.title.toUpperCase(), 'and does not claim to be an invoice').to.not.contain('INVOICE')
      // The number the shopkeeper matches their invoice against.
      expect(m.invoiceNo).to.eq(ctx.invoiceNo)
      const header = m.headerFields.map((f) => f.key)
      expect(header, 'the invoice number is printed on the face').to.include('invoiceNo')
    })
  })

  it('carries the distribution columns a pharma delivery needs — batch, expiry, packing, discount', () => {
    cy.loginAsMarketplaceOwner()
    model('DELIVERY_CHALLAN_A4').then((m) => {
      const keys = m.columns.map((c) => c.key)
      // Batch and expiry are a recall-traceability obligation on medicines, not a nicety.
      expect(keys, 'batch').to.include('batchNo')
      expect(keys, 'expiry').to.include('expiryDate')
      expect(keys, 'pack size').to.include('packing')
      // List price AND the concession, separately: a lower rate would hide what the shop was given.
      expect(keys, 'list price').to.include('tradePrice')
      expect(keys, 'the concession, as its own column').to.include('discount')

      // Every column has a resolved label — an unlabelled column is a column nobody can read.
      m.columns.forEach((c) => expect(c.label, 'label for ' + c.key).to.be.a('string').and.not.be.empty)
    })
  })

  it('resolves the line from the real invoice: batch, packing and the discount all present', () => {
    cy.loginAsMarketplaceOwner()
    model('DELIVERY_CHALLAN_A4').then((m) => {
      expect(m.rows.length, 'one line was delivered').to.eq(1)
      const cell = (key) => m.rows[0][m.columns.findIndex((c) => c.key === key)]

      expect(String(cell('batchNo')), 'the batch that was picked').to.contain('A265007')
      expect(String(cell('expiryDate')), 'and its expiry').to.contain('2027')
      expect(String(cell('packing'))).to.contain('1000 ml')
      expect(Number(cell('quantity'))).to.eq(QTY)
      // 8 × 90 = 720 listed, 72 off, 648 charged. The three figures a shopkeeper checks.
      expect(Number(cell('lineValue')), 'list value').to.eq(720)
      expect(Number(cell('discount')), 'the concession').to.eq(LINE_DISCOUNT)
      expect(Number(cell('lineTotal')), 'what is charged').to.eq(648)
    })
  })

  it('has a signature block with the four boxes a signed delivery needs', () => {
    cy.loginAsMarketplaceOwner()
    model('DELIVERY_CHALLAN_A4').then((m) => {
      // Delivered by / Received by / Amount received / Balance. The last two are written in at the door —
      // this slip does two jobs, proving arrival and recording payment.
      expect(m.signature.length, 'four boxes').to.eq(4)
      m.signature.forEach((s) => expect(s, 'each box is labelled').to.be.a('string').and.not.be.empty)
    })
  })

  it('and the ordinary invoice is unchanged — the preset added a document, it did not edit one', () => {
    cy.loginAsMarketplaceOwner()

    // The A4 trade invoice, checked EXPLICITLY. This is the document the challan preset sits beside and the one
    // that could have been damaged by making the signature strip configurable, so it is named rather than
    // resolved: two boxes, exactly as before.
    model('TRADE_INVOICE_A4').then((m) => {
      expect(m.title.toUpperCase(), 'still an invoice').to.not.contain('CHALLAN')
      expect(m.signature.length, 'the trade invoice keeps its original two boxes').to.eq(2)
    })

    /*
     * And what the renderer picks on its OWN for this buyer.
     *
     * Not the A4 invoice — this outlet was created through /addCustomer, which defaults customerType to
     * WALK_IN, so `isTradeCustomer` is false and the renderer correctly chooses the 80mm thermal receipt (no
     * signature strip, hence zero boxes). Channel picks the layout; the vertical picks the words.
     *
     * Worth knowing rather than asserting away: a DISTRIBUTION outlet must be created as RETAILER or WHOLESALE
     * to be handed an A4 trade invoice. Left as WALK_IN, a wholesale delivery prints as a till slip.
     */
    model(null).then((m) => {
      expect(m.title.toUpperCase(), 'whatever is chosen, it is not the challan').to.not.contain('CHALLAN')
      expect(m.paper, 'a WALK_IN buyer resolves to the thermal receipt').to.eq('80mm')
      expect(m.signature.length, 'which has no signature strip, by design').to.eq(0)
    })
  })

  // ── slice 4 · download ────────────────────────────────────────────────────────────────────────────────

  it('exposes download for the challan AND the invoice, with pdfmake still unloaded', () => {
    cy.loginAsMarketplaceOwner()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => {
      expect(w.downloadChallan, 'the challan download').to.be.a('function')
      expect(w.downloadInvoicePdf, 'and the invoice, which had none until now').to.be.a('function')
      expect(w.downloadDocumentPdf, 'both go through one emitter').to.be.a('function')
      expect(w.printChallan, 'print, for the copy that goes out with the goods').to.be.a('function')

      // The lazy contract. pdfmake plus its fonts is ~900KB gzipped and must not be on the page for the many
      // users who never export a document — a click test alone would pass either way.
      expect(w.pdfMake === undefined || !w.pdfMake.vfs,
        'pdfmake is NOT loaded until a download is asked for').to.eq(true)
      expect(w.LazyExport.ensurePdfMake, 'the shared loader is what fetches it').to.be.a('function')
    })
  })

  it('the order detail offers the challan only once there is an invoice to reference', () => {
    cy.loginAsMarketplaceOwner()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => w.showOrders())
    cy.get('#ordFilterQ').clear().type(ctx.order.orderNo)
    cy.window().then((w) => w.applyOrderFilters())
    cy.get('#ordersBody tr', { timeout: 15000 }).should('have.length.at.least', 1)
    cy.window().then((w) => w.openOrderDetail(ctx.order.id))

    // Dispatched, so it has an invoice, so both buttons are drawn.
    cy.get('[data-act="challan"][data-order="' + ctx.order.id + '"]', { timeout: 10000 }).should('exist')
    cy.get('[data-act="challan-pdf"][data-order="' + ctx.order.id + '"]').should('exist')
  })
})
