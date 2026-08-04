/**
 * B2B Phase 3d (#5) — the statement of account is downloadable.
 *
 * WHAT #5 ACTUALLY NEEDED: `customerStatement` / `vendorStatement` already existed (slice F2) with a running
 * balance and an anti-IDOR check. The gap was never the statement — it was letting the customer HAVE it.
 *
 * THE GUARANTEE THIS GATE PROTECTS: the CSV is an adapter over the SAME service method the JSON endpoint
 * calls, so the file a customer reconciles against can never disagree with the screen. If someone later
 * "optimises" the CSV route into its own query, these assertions fail.
 *
 * Design: microservices/docs/slices/b2b-P3-documents-reports.md  (§3d)
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/statement-download.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return Array.isArray(body) ? body : []
}

const customer = (name) =>
  cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: { name, contact: 'C' + uniq() } })
    .then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      return cy.request('/getUserCustomer')
    })
    .then((r) => {
      const c = list(r.body).find((x) => x.name === name)
      expect(c, 'customer created').to.exist
      return cy.wrap(c)
    })

/** A customer with one credit sale, so the statement has something on it. */
const customerWithASale = () => {
  const name = 'Stmt_' + uniq()
  return cy.seedProduct({ name: 'Stmt_' + uniq(), sellingPrice: 150, stock: 10 })
    .then(({ productId }) => customer(name).then((c) =>
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        failOnStatusCode: false,
        body: {
          customer: { customerId: c.customerId, name: c.name, contact: c.contact },
          sales: [{ productId, quantity: 1, sellRate: 150, totalAmount: 150, netAmount: 150 }],
          tenders: [], paidAmount: 0, grandTotal: 150,
          idempotencyKey: 'cy-stmt-' + uniq(),
        },
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        return cy.wrap(c)
      })))
}

describe('B2B P3d — statement download (#5)', () => {

  beforeEach(() => { cy.loginAsOwner() })

  it('the statement downloads as CSV, as an attachment', () => {
    customerWithASale().then((c) => {
      cy.request('/customerStatement.csv?customerId=' + c.customerId).then((r) => {
        expect(r.status).to.eq(200)
        expect(r.headers['content-type'], 'served as CSV').to.contain('text/csv')
        // Without this the browser renders the text instead of saving a file.
        expect(r.headers['content-disposition'], 'saved, not rendered').to.contain('attachment')
        expect(r.headers['content-disposition'], 'named after the customer').to.contain('customer-statement')
      })
    })
  })

  it('the CSV carries the statement columns and one row per document', () => {
    customerWithASale().then((c) => {
      cy.request('/customerStatement.csv?customerId=' + c.customerId).then((csv) => {
        const rows = String(csv.body).trim().split(/\r?\n/)
        expect(rows[0], 'header row').to.eq('Date,Document,Type,Debit,Credit,Balance')
        expect(rows.length, 'at least the header plus the sale').to.be.greaterThan(1)
        expect(rows[1], 'the invoice appears').to.contain('INV-')
      })
    })
  })

  it('THE guarantee: the CSV agrees with the JSON, line for line', () => {
    // The whole reason the CSV route is an adapter over the same service method rather than a second query.
    customerWithASale().then((c) => {
      cy.request('/customerStatement?customerId=' + c.customerId).then((json) => {
        const lines = list(json.body)
        expect(lines.length, 'the JSON statement has lines').to.be.greaterThan(0)

        cy.request('/customerStatement.csv?customerId=' + c.customerId).then((csv) => {
          const rows = String(csv.body).trim().split(/\r?\n/)
          expect(rows.length - 1, 'one CSV row per JSON line').to.eq(lines.length)

          // and the closing balance — the number the customer actually cares about — matches
          const lastJson = Number(lines[lines.length - 1].balance || 0).toFixed(2)
          const lastCsv = String(rows[rows.length - 1]).split(',').pop()
          expect(Number(lastCsv).toFixed(2), 'closing balance identical').to.eq(lastJson)
        })
      })
    })
  })

  it('a vendor statement downloads too', () => {
    cy.request('/getUserVender').then((r) => {
      const vendors = list(r.body)
      if (!vendors.length) return   // an org with no vendors has nothing to assert
      // VenderDTO exposes `id`, NOT `venderId` — sending the wrong field yields venderId=undefined, which
      // business-service rejects and the proxy reports as 502. The id field name is the contract here.
      const venderId = vendors[0].id
      expect(venderId, 'the vendor list gives an id to ask for').to.be.a('number')
      cy.request({ url: '/vendorStatement.csv?venderId=' + venderId, failOnStatusCode: false })
        .then((csv) => {
          expect(csv.status).to.eq(200)
          expect(csv.headers['content-disposition']).to.contain('vendor-statement')
          expect(String(csv.body).split(/\r?\n/)[0]).to.eq('Date,Document,Type,Debit,Credit,Balance')
        })
    })
  })

  it('the Download button appears in the statement dialog and points at the CSV', () => {
    customerWithASale().then((c) => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => {
        expect(typeof w.openStatement, 'the statement dialog exists').to.eq('function')
        expect(w.t('ui.js.download'), 'the label resolves in the active language').to.not.eq('ui.js.download')
        w.openStatement('CUSTOMER', c.customerId, c.name)
      })
      cy.get('#StatementDownloadBtn', { timeout: 10000 })
        .should('be.visible')
        .and('have.attr', 'href')
        .and('contain', 'customerStatement.csv?customerId=' + c.customerId)
    })
  })
})
