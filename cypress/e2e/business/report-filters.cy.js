/**
 * B2B Phase 3e-1 (#6) — the sale report filters, and exports what it shows.
 *
 * WHAT #6 NEEDED: there was exactly ONE report (sale detail) and its only filter was the date range. No
 * customer / product / category / channel filter, no export.
 *
 * THE GUARANTEE THIS GATE PROTECTS: the CSV is produced by calling the SAME method the screen calls, so the
 * exported file honours every active filter. A client-side filter with a server-side export would quietly
 * hand the customer a file that ignored their filters — these assertions make that regression impossible.
 *
 * Design: microservices/docs/slices/b2b-P3-documents-reports.md  (§3e)
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/report-filters.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return Array.isArray(body) ? body : []
}

const customer = (name, customerType) =>
  cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: { name, contact: 'C' + uniq(), customerType } })
    .then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      return cy.request('/getUserCustomer')
    })
    .then((r) => {
      const c = list(r.body).find((x) => x.name === name)
      expect(c, 'customer created').to.exist
      return cy.wrap(c)
    })

const sell = (cust, productId, rate) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      customer: { customerId: cust.customerId, name: cust.name, contact: cust.contact,
                  customerType: cust.customerType },
      sales: [{ productId, quantity: 1, sellRate: rate, totalAmount: rate, netAmount: rate }],
      tenders: [{ method: 'CASH', amount: rate }], paidAmount: rate, grandTotal: rate,
      idempotencyKey: 'cy-rf-' + uniq(),
    },
  }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

/** Two customers on different channels, each with one sale, in the current month. */
const twoSales = () =>
  cy.seedProduct({ name: 'Rf_' + uniq(), sellingPrice: 70, stock: 20 }).then(({ productId }) =>
    customer('RfWhole_' + uniq(), 'WHOLESALE').then((w) =>
      sell(w, productId, 70).then(() =>
        customer('RfWalk_' + uniq(), 'WALK_IN').then((k) =>
          sell(k, productId, 70).then(() => cy.wrap({ productId, wholesale: w, walkIn: k }))))))

const report = (extra) =>
  cy.request({ method: 'POST', url: '/loadSR', form: true, failOnStatusCode: false,
    body: Object.assign({ rp: 0 }, extra || {}) })

describe('B2B P3e-1 — report filters and export (#6)', () => {

  beforeEach(() => { cy.loginAsOwner() })

  it('with NO filter the report is unchanged — the live-modules guarantee', () => {
    twoSales().then(() => {
      report().then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        expect(list(r.body).length, 'the unfiltered report still returns rows').to.be.greaterThan(1)
      })
    })
  })

  it('filtering by customer returns only that customer', () => {
    twoSales().then((ctx) => {
      report({ customerId: ctx.wholesale.customerId }).then((r) => {
        const rows = list(r.body)
        expect(rows.length, 'at least the one sale').to.be.greaterThan(0)
        rows.forEach((row) => {
          expect(String(row.customerId), 'every row is the requested customer')
            .to.eq(String(ctx.wholesale.customerId))
        })
      })
    })
  })

  it('filtering by channel separates B2B from B2C', () => {
    twoSales().then(() => {
      report({ customerType: 'WHOLESALE' }).then((r) => {
        const rows = list(r.body)
        expect(rows.length, 'wholesale lines exist').to.be.greaterThan(0)
        rows.forEach((row) => {
          expect(String(row.customerType).toUpperCase(), 'no walk-in leaked in').to.eq('WHOLESALE')
        })
      })
    })
  })

  it('THE guarantee: the CSV export honours the active filter', () => {
    // If filtering were client-side, this file would contain every row regardless of the filter.
    twoSales().then((ctx) => {
      report({ customerId: ctx.wholesale.customerId }).then((json) => {
        const jsonRows = list(json.body)

        cy.request('/saleReport.csv?rp=0&customerId=' + ctx.wholesale.customerId).then((csv) => {
          expect(csv.status).to.eq(200)
          expect(csv.headers['content-disposition'], 'saved, not rendered').to.contain('attachment')

          const lines = String(csv.body).trim().split(/\r?\n/)
          expect(lines[0], 'header row').to.contain('Date,Invoice,Customer,Channel')
          expect(lines.length - 1, 'one CSV row per filtered JSON row').to.eq(jsonRows.length)
          // and the OTHER customer must be absent from the file
          expect(String(csv.body)).to.not.contain(ctx.walkIn.name)
        })
      })
    })
  })

  it('an export with no matches is a header-only file, not an error', () => {
    // "No sales matched" is a valid answer to a report. A 500 here would be wrong.
    cy.request({ url: '/saleReport.csv?rp=0&customerId=99999999', failOnStatusCode: false })
      .then((csv) => {
        expect(csv.status).to.eq(200)
        const lines = String(csv.body).trim().split(/\r?\n/)
        expect(lines[0], 'the header still names the columns').to.contain('Date,Invoice')
        expect(lines.length, 'and there are no data rows').to.eq(1)
      })
  })

  it('the shared filter rail mounts on the report with its labels translated', () => {
    cy.visit('/businessDashboard')
    cy.window().then((w) => {
      expect(typeof w.mountReportFilters, 'the SHARED component is loaded, not a per-screen copy')
        .to.eq('function')
      ;['ui.js.filterCustomer', 'ui.js.filterChannel', 'ui.js.allCategories', 'ui.js.exportCsv']
        .forEach((k) => expect(w.t(k), `${k} resolves`).to.not.eq(k))
    })
    // SRDiv is a SELL sub-section on #sellType — openSection drives #registrationType and cannot reach it.
    cy.openSellSection('SRDiv')
    cy.get('#rfCustomer', { timeout: 10000 }).should('exist')
    cy.get('#rfChannel').should('exist')
    cy.get('#rfExport').should('have.attr', 'href').and('contain', 'saleReport.csv')
  })
})
