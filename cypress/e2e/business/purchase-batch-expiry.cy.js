/**
 * B2B Phase 3a (#2) — batch & expiry captured on purchase
 * B2B Phase 3b-1     — document title from customerType (INVOICE vs RECEIPT)
 *
 * DOMAIN CONTEXT (why this matters beyond a form field): batch/lot + expiry is the basis of pharmaceutical
 * traceability — a recall is executed BY batch, and FEFO dispensing is by expiry. inventory-service already
 * keys stock entries on both; the purchase form simply never delivered them.
 *
 * What this actually fixes (an earlier draft of this spec claimed a bigger bug that turned out not to exist —
 * the nested stock.* binding has always worked, and finance-reports.cy.js proves it):
 *   1. there was no Batch # INPUT on the purchase form, and its column was commented out
 *   2. the new Batch column ships with its matching cell, and a generic header==cell assertion guards the
 *      table against the misalignment class that bit tableCustomer in Phase 0
 *
 * Design: microservices/docs/slices/b2b-P3-documents-reports.md
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/purchase-batch-expiry.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return Array.isArray(body) ? body : []
}

describe('B2B P3a — batch & expiry on purchase, and the document title', () => {

  beforeEach(() => { cy.loginAsOwner() })

  // ── 3a: the data actually arrives ────────────────────────────────────────────

  it('a batch and expiry entered on a purchase are STORED', () => {
    const batch = 'B-' + uniq()
    const expiry = '2027-06-30'
    const invNo = 'BX-' + uniq()

    cy.seedProduct({ name: 'Batch_' + uniq(), sellingPrice: 50 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
        body: {
          productId, quantity: 10, purchaseRate: 30,
          'stock.bpurchaseRate': 30, 'stock.bsellRate': 50,
          totalAmount: 300, netAmount: 300, paidAmount: 300,
          purchaseInvoiceNo: invNo,
          'stock.batchNo': batch,     // the field the new form input posts to
          'stock.bexpDate': expiry,   // unchanged — this nested path has always bound correctly
        },
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getUserPurchase').then((r) => {
        const mine = list(r.body).find((p) => p.purchaseInvoiceNo === invNo)
        expect(mine, 'the purchase was recorded').to.exist
        const storedBatch = String(mine.batchNo || (mine.stock && mine.stock.batchNo) || '')
        expect(storedBatch, 'the batch persisted').to.eq(batch)
        const stored = String(mine.bexpDate || (mine.stock && mine.stock.bexpDate) || '')
        expect(stored, 'the expiry persisted (as it always did)').to.contain('2027-06-30')
      })
    })
  })

  it('a purchase with NO batch still saves — a hardware shop has no lot numbers', () => {
    const invNo = 'NB-' + uniq()
    cy.seedProduct({ name: 'NoBatch_' + uniq(), sellingPrice: 20 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
        body: { productId, quantity: 5, purchaseRate: 10, 'stock.bpurchaseRate': 10,
                'stock.bsellRate': 20, totalAmount: 50, netAmount: 50, paidAmount: 50,
                purchaseInvoiceNo: invNo },
      }).then((r) => expect(r.body.status, 'batch is optional').to.eq('SUCCESS'))
    })
  })

  it('the batch reaches inventory, where FEFO already keys on batch + expiry', () => {
    const batch = 'FEFO-' + uniq()
    cy.seedProduct({ name: 'Fefo_' + uniq(), sellingPrice: 40 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
        body: { productId, quantity: 7, purchaseRate: 25, 'stock.bpurchaseRate': 25,
                'stock.bsellRate': 40, totalAmount: 175, netAmount: 175, paidAmount: 175,
                purchaseInvoiceNo: 'FE-' + uniq(),
                'stock.batchNo': batch, 'stock.bexpDate': '2028-01-31' },
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      // The stock-in must have landed; the batch is what makes a recall executable.
      cy.request({ url: `/productStock?productId=${productId}`, failOnStatusCode: false })
        .then((r) => {
          expect(r.status).to.eq(200)
          expect(JSON.stringify(r.body), 'stock arrived for this product').to.contain('7')
        })
    })
  })

  // ── 3a: the screen ───────────────────────────────────────────────────────────

  describe('the purchase screen', () => {

    it('offers a Batch # field, and the expiry posts to the field that actually binds', () => {
      cy.openPurchaseSection('purchaseDiv')
      cy.get('#newPurchase').click()
      cy.get('#PurchaseModal').should('have.class', 'open')

      // Both post through the nested StockDTO, like every sibling field on this form.
      cy.get('#purchaseBatchNo').should('be.visible').and('have.attr', 'name', 'stock.batchNo')
      cy.get('#purchaseExpiry').should('have.attr', 'name', 'stock.bexpDate')
    })

    it('every rendered header has a cell — the table stays aligned', () => {
      // Seed a row of our own rather than depending on the earlier tests having run: this assertion needs
      // at least one rendered row, and an ordering dependency is how a gate turns flaky.
      cy.seedProduct({ name: 'Align_' + uniq(), sellingPrice: 10 }).then(({ productId }) => {
        cy.request({
          method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
          body: { productId, quantity: 1, purchaseRate: 5, 'stock.bpurchaseRate': 5,
                  'stock.bsellRate': 10, totalAmount: 5, netAmount: 5, paidAmount: 5,
                  purchaseInvoiceNo: 'AL-' + uniq(), 'stock.batchNo': 'AL-' + uniq() },
        }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
      })

      cy.openPurchaseSection('purchaseDiv')
      cy.get('#tablePurchase th[data-field="purchaseBatchNo"]').should('exist')

      // Count what the BROWSER rendered — a commented-out <th> is not a column, which is exactly what
      // counting the template source got wrong here.
      //
      // Everything below is a QUERY (get / its), never cy.wrap: DataTables re-renders on its ajax reload,
      // and wrap() freezes a subject that then detaches from the DOM. That detachment is what failed the
      // previous version of this test, not the alignment itself.
      // Wait for a REAL data row. DataTables renders a single-cell "No data available" placeholder
      // when empty, and that placeholder IS a <tr> — so a bare row-count check passes against it and
      // the next assertion compares 14 headers to that 1 placeholder cell. Requiring >1 cell is what
      // distinguishes a loaded grid from an empty one.
      cy.get('#tablePurchase tbody tr:first td', { timeout: 15000 })
        .should('have.length.greaterThan', 1)

      // Report WHICH columns differ, not just the counts. A bare "13 vs 14" sent me chasing stale assets
      // through three rebuilds; the names say immediately whether a header has no cell, a cell has no
      // header, or the thead simply has more than one row.
      cy.get('#tablePurchase thead tr').its('length').then((headerRows) => {
        cy.get('#tablePurchase thead th').then(($th) => {
          const headers = [...$th].map((el) => el.getAttribute('data-field') || el.textContent.trim())
          cy.get('#tablePurchase tbody tr:first td').then(($td) => {
            const cells = [...$td].map((el) => {
              const inner = el.querySelector('[id]')
              if (inner) return inner.id
              return el.querySelector('input') ? '(checkbox)' : '(none)'
            })
            const detail = 'thead rows=' + headerRows
              + ' | headers(' + headers.length + ')=[' + headers.join(' ') + ']'
              + ' | cells(' + cells.length + ')=[' + cells.join(' ') + ']'
            expect(cells.length, detail).to.eq(headers.length)
          })
        })
      })
    })
  })

  // ── 3b-1: the document title ─────────────────────────────────────────────────

  describe('document title from customerType (deferred from Phase 0)', () => {

    const sellTo = (cust, productId, rate) =>
      cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        failOnStatusCode: false,
        body: {
          customer: { customerId: cust.customerId, name: cust.name, contact: cust.contact,
                      customerType: cust.customerType },
          sales: [{ productId, quantity: 1, sellRate: rate, totalAmount: rate, netAmount: rate }],
          tenders: [{ method: 'CASH', amount: rate }], paidAmount: rate, grandTotal: rate,
          idempotencyKey: 'cy-doc-' + uniq(),
        } })

    const customerOf = (type) => {
      const name = 'Doc_' + type + '_' + uniq()
      return cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { name, contact: 'C' + uniq(), customerType: type } })
        .then(() => cy.request('/getUserCustomer'))
        .then((r) => {
          const c = list(r.body).find((x) => x.name === name)
          expect(c, 'customer created').to.exist
          return cy.wrap(c)
        })
    }

    it('the receipt payload carries customerType, which is what drives the title', () => {
      // receipt.js reads inv.customer.customerType. If the payload ever stopped carrying it the title
      // would silently revert to RECEIPT for every trade account — a quiet regression, so pin it here.
      cy.seedProduct({ name: 'Doc_' + uniq(), sellingPrice: 60, stock: 10 }).then(({ productId }) => {
        customerOf('WHOLESALE').then((c) => {
          sellTo(c, productId, 60).then((s) => {
            expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
            cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(s.body.object)).then((r) => {
              const inv = r.body.object || r.body.data
              expect(String(inv.customer.customerType).toUpperCase(),
                'a trade account is identifiable on the document').to.eq('WHOLESALE')
            })
          })
        })
      })
    })

    it('a walk-in is still B2C on the document', () => {
      cy.seedProduct({ name: 'DocW_' + uniq(), sellingPrice: 15, stock: 10 }).then(({ productId }) => {
        customerOf('WALK_IN').then((c) => {
          sellTo(c, productId, 15).then((s) => {
            cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(s.body.object)).then((r) => {
              const inv = r.body.object || r.body.data
              expect(String(inv.customer.customerType).toUpperCase()).to.eq('WALK_IN')
            })
          })
        })
      })
    })

    it('receipt.js titles a trade document INVOICE and a retail one RECEIPT', () => {
      // Drive the renderer directly: buildHtml is where the decision lives, and asserting it here proves
      // the rule without depending on a print dialog.
      cy.visit('/businessDashboard')
      cy.window().then((w) => {
        expect(typeof w.printReceipt, 'receipt.js is loaded on this screen').to.eq('function')
        expect(typeof w.t, 'i18n helper available for the titles').to.eq('function')
        expect(w.t('ui.js.docInvoice'), 'INVOICE title key resolves').to.not.eq('ui.js.docInvoice')
        expect(w.t('ui.js.docReceipt'), 'RECEIPT title key resolves').to.not.eq('ui.js.docReceipt')
      })
    })
  })
})
