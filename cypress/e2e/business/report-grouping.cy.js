/**
 * B2B Phase 3e-2 (#6) — report group-by with subtotals.
 *
 * THE GUARANTEES THIS GATE PROTECTS:
 *   1. subtotals come from the SAME query as the detail, in one response — the two views cannot disagree
 *   2. a multi-line invoice counts as ONE transaction, not three
 *   3. when the screen is grouped the EXPORT is grouped — a grouped screen with a detail-level file would
 *      hand the customer a different document from the one they are looking at
 *   4. an unknown groupBy means ungrouped, never a 500 — a stale bookmark must not break a report
 *
 * Design: microservices/docs/slices/b2b-P3-documents-reports.md  (§3e)
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/report-grouping.cy.js --headed --no-exit --config screenshotOnRunFailure=false
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

/** ONE invoice with TWO lines — the case that inflates a naive transaction count. */
const twoLineSale = () => {
  const name = 'Grp_' + uniq()
  return cy.seedProduct({ name: 'GrpA_' + uniq(), sellingPrice: 100, stock: 20 }).then((a) =>
    cy.seedProduct({ name: 'GrpB_' + uniq(), sellingPrice: 50, stock: 20 }).then((b) =>
      customer(name).then((c) =>
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          failOnStatusCode: false,
          body: {
            customer: { customerId: c.customerId, name: c.name, contact: c.contact },
            sales: [
              { productId: a.productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 },
              { productId: b.productId, quantity: 2, sellRate: 50, totalAmount: 100, netAmount: 100 },
            ],
            tenders: [{ method: 'CASH', amount: 200 }], paidAmount: 200, grandTotal: 200,
            idempotencyKey: 'cy-grp-' + uniq(),
          },
        }).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          return cy.wrap({ customer: c, invoiceNo: r.body.object })
        }))))
}

const report = (extra) =>
  cy.request({ method: 'POST', url: '/loadSR', form: true, failOnStatusCode: false,
    body: Object.assign({ rp: 0 }, extra || {}) })

describe('B2B P3e-2 — report grouping (#6)', () => {

  beforeEach(() => { cy.loginAsOwner() })

  it('with NO grouping the response carries detail only — today unchanged', () => {
    twoLineSale().then(() => {
      report().then((r) => {
        expect(list(r.body).length, 'detail rows').to.be.greaterThan(0)
        expect(r.body.object, 'no subtotals unless asked for').to.be.oneOf([null, undefined])
      })
    })
  })

  it('grouping returns subtotals ALONGSIDE the detail, from one call', () => {
    twoLineSale().then((ctx) => {
      report({ groupBy: 'CUSTOMER', customerId: ctx.customer.customerId }).then((r) => {
        expect(list(r.body).length, 'the detail is still there').to.be.greaterThan(0)
        const groups = r.body.object
        expect(groups, 'subtotals in the same response').to.be.an('array')
        expect(groups.length, 'one group for one customer').to.eq(1)
        expect(groups[0].label, 'labelled by customer').to.eq(ctx.customer.name)
        expect(Number(groups[0].total), 'both lines summed').to.be.closeTo(200, 0.01)
      })
    })
  })

  it('a TWO-LINE invoice counts as ONE transaction', () => {
    // The failure this prevents: a day with 2 sales reported as 3 because one had two lines.
    twoLineSale().then((ctx) => {
      report({ groupBy: 'CUSTOMER', customerId: ctx.customer.customerId }).then((r) => {
        const g = r.body.object[0]
        expect(g.invoices, 'distinct invoices, not lines').to.eq(1)
        expect(Number(g.quantity), 'but the units still add up (1 + 2)').to.be.closeTo(3, 0.01)
      })
    })
  })

  it('THE guarantee: a grouped screen exports a grouped file', () => {
    twoLineSale().then((ctx) => {
      report({ groupBy: 'CUSTOMER', customerId: ctx.customer.customerId }).then((json) => {
        const groups = json.body.object

        cy.request('/saleReport.csv?rp=0&groupBy=CUSTOMER&customerId=' + ctx.customer.customerId)
          .then((csv) => {
            expect(csv.status).to.eq(200)
            const lines = String(csv.body).trim().split(/\r?\n/)
            expect(lines[0], 'the header names the grouping, not the detail columns')
              .to.eq('CUSTOMER,Invoices,Qty,Total,Tax,Gross')
            expect(lines.length - 1, 'one row per group, not per line').to.eq(groups.length)
            expect(lines[1], 'the group label').to.contain(ctx.customer.name)
          })
      })
    })
  })

  it('an unknown groupBy is ungrouped, not a failure', () => {
    // A stale bookmark or a hand-typed URL must not 500 a report.
    twoLineSale().then(() => {
      report({ groupBy: 'byUnicorn' }).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        expect(r.body.object, 'silently ungrouped').to.be.oneOf([null, undefined])
      })
    })
  })

  it('the group-by control is in the SHARED rail, translated', () => {
    cy.visit('/businessDashboard')
    cy.window().then((w) => {
      ;['ui.js.groupBy', 'ui.js.noGrouping', 'ui.js.groupDay', 'ui.js.invoices', 'ui.js.grossSales']
        .forEach((k) => expect(w.t(k), `${k} resolves — only ui.js.* reaches the browser`).to.not.eq(k))
    })
    cy.openSellSection('SRDiv')
    cy.get('#rfGroupBy', { timeout: 10000 }).should('exist')
    cy.get('#rfGroupBy option').should('have.length.greaterThan', 6)   // "no grouping" + six dimensions
    cy.get('#rfExport').should('have.attr', 'href').and('contain', 'groupBy=')
  })
})
