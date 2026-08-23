/**
 * INST-2 — the Installments screen. Requirement R2: "know the dues".
 * Design: microservices/docs/installment-dues-reminders-design.md (§16)
 *
 * The customer asked to track dues and remaining balances. The plan data and the read endpoints existed after
 * INST-1, and a shopkeeper could reach none of it — which is review finding R7 in the same costume this
 * programme has now worn four times. This screen is that half.
 *
 * Read-only by design. Money moves through the Receive Payment action the counter already has, so this
 * cannot become a second way to collect and there is no second place for the two to disagree.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/installment-worklist.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
  return []
}

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Sell one item on a plan, through the API — this spec is about the SCREEN, not the sale. */
const sellOnPlan = (name, price, count, firstDue) => {
  const run = uniq()
  return cy.seedProduct({ name: `WL_${run}`, sellingPrice: price, stock: 5 }).then(({ productId }) =>
    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name, contact: `0300W${run}`, paidAmount: 0, dueAmount: 0 },
        sales: [{ productId, quantity: 1, sellRate: price, totalAmount: price, netAmount: price }],
        paidAmount: 0, dueAmount: 0, grandTotal: price,
        installmentPlan: {
          cashPrice: price, downPayment: 0, installmentCount: count,
          frequency: 'monthly', firstDueDate: firstDue, assetRef: `IMEI${run}`,
        },
      }, failOnStatusCode: false,
    }).then((r) => {
      // Assert the fixture, loudly. A plan that silently failed to exist would make every assertion below
      // pass or fail for a reason that has nothing to do with the screen.
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      expect(r.body.message, 'the fixture plan exists').to.contain('PLN-')
      return cy.wrap(run)
    }))
}

/**
 * Filter the plans grid to one customer before looking for their row.
 *
 * WHY EVERY LOOKUP GOES THROUGH THIS. The grid is paginated, so only ONE PAGE of rows is in the DOM at a
 * time. `cy.contains('#installmentBody tr', name)` therefore stops meaning "this plan exists" and starts
 * meaning "this plan is on the page that happens to be showing" — and the fixture is usually not, because
 * the server returns most-overdue-first and a plan created seconds ago is the least overdue thing there is.
 *
 * The absence assertions are the dangerous half: "the settled plan is gone" would pass while the row sat
 * happily on page three. Searching first makes the whole set the subject again, and gates the search box
 * into the bargain.
 */
const searchPlans = (text) =>
  cy.get('#tableInstallment_filter input', { timeout: 10000 }).clear().type(text, { delay: 0 })

describe('INST-2 — the Installments screen', () => {
  before(() => {
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'true')
  })

  beforeEach(() => {
    cy.loginAsOwner()
  })

  after(() => {
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'false')
  })

  // ── ⭐ reachable, and showing the right money ─────────────────────────────────────────────────────────

  it('the screen lists a plan with its financed, paid and remaining figures', () => {
    const buyer = `Worklist Buyer ${uniq()}`

    sellOnPlan(buyer, 60000, 6, monthsOut(1)).then(() => {
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.window().then((w) => w.showInstallments())

      cy.get('#InstallmentDiv').should('be.visible')
      cy.get('#installmentBody tr', { timeout: 10000 }).should('have.length.greaterThan', 0)

      searchPlans(buyer)
      cy.contains('#installmentBody tr', buyer).within(() => {
        // The three figures the customer actually asked to see, on one row.
        cy.get('td').eq(4).should('contain.text', '60000')   // financed
        cy.get('td').eq(5).should('contain.text', '0.00')    // paid
        cy.get('td').eq(6).should('contain.text', '60000')   // remaining
      })
    })
  })

  it('clicking a plan shows its schedule', () => {
    const buyer = `Schedule Buyer ${uniq()}`

    sellOnPlan(buyer, 30000, 3, monthsOut(1)).then(() => {
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.window().then((w) => w.showInstallments())

      searchPlans(buyer)
      cy.contains('#installmentBody tr', buyer).click()

      cy.get('#installmentScheduleTable tbody tr', { timeout: 10000 }).should('have.length', 3)
      cy.get('#installmentScheduleTable tbody tr td:nth-child(3)').then(($c) => {
        const sum = [...$c].reduce((t, c) => t + Number(c.innerText.replace(/,/g, '')), 0)
        expect(sum, 'the schedule shown reconciles to the financed amount').to.eq(30000)
      })
    })
  })

  it('a payment is reflected without touching this screen', () => {
    // Read-only, and this proves it means something: the money moves through Receive Payment and the screen
    // simply tells the truth afterwards. Two ways to collect would be two places to disagree.
    const buyer = `Paying Buyer ${uniq()}`

    sellOnPlan(buyer, 30000, 3, monthsOut(1)).then(() => {
      cy.request('/getUserCustomer?q=-1').then((r) => {
        const c = list(r.body).find((x) => x.name === buyer)
        cy.request({
          method: 'POST', url: '/receivePayment', form: true,
          body: { customerId: c.customerId || c.id, amount: 10000, method: 'CASH' },
          failOnStatusCode: false,
        }).then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))

        cy.visit('/businessDashboard')
        cy.waitForAppReady()
        cy.window().then((w) => w.showInstallments())

        searchPlans(buyer)
        cy.contains('#installmentBody tr', buyer).within(() => {
          cy.get('td').eq(5).should('contain.text', '10000')   // paid
          cy.get('td').eq(6).should('contain.text', '20000')   // remaining
        })
      })
    })
  })

  it('an overdue plan is flagged; a current one is not', () => {
    const late = `Late Buyer ${uniq()}`
    const ontime = `Ontime Buyer ${uniq()}`

    sellOnPlan(late, 30000, 3, monthsOut(-2)).then(() => {
      sellOnPlan(ontime, 30000, 3, monthsOut(1)).then(() => {
        cy.visit('/businessDashboard')
        cy.waitForAppReady()
        cy.window().then((w) => w.showInstallments())

        // The overdue count comes from the SERVER, computed with the same predicate the reminder scanner
        // will use — so a row flagged late here and a reminder sent for it cannot disagree.
        searchPlans(late)
        cy.contains('#installmentBody tr', late).within(() => {
          cy.get('td').eq(8).invoke('text').should('match', /[1-9]/)
        })
        // The negative control. Without it, "flagged" would be satisfied by a screen that flags everything.
        searchPlans(ontime)
        cy.contains('#installmentBody tr', ontime).within(() => {
          cy.get('td').eq(8).invoke('text').should('eq', '')
        })
      })
    })
  })

  // ── the grid's own controls ───────────────────────────────────────────────────────────────────────────

  it('the grid searches, pages and exports', () => {
    const mine = `Grid Buyer ${uniq()}`

    sellOnPlan(mine, 30000, 3, monthsOut(1)).then(() => {
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.window().then((w) => w.showInstallments())

      cy.get('#tableInstallment_filter input', { timeout: 10000 }).should('exist')

      // PAGING. Asserted through the controls this app actually renders: `dom: 'Bfrtip'` has no 'l', so there
      // is no native length <select> — the page-size control is the Buttons `pageLength` entry, exactly as
      // loadDataTable() does it for every other grid here. Asserting select[name=..._length] tested a
      // convention this codebase deliberately does not use.
      cy.get('#tableInstallment_paginate').should('exist')
      cy.get('#tableInstallment_info').should('exist')
      cy.get('.dt-buttons').should('be.visible')

      // SEARCH — the positive control first, then the thing that makes it a filter rather than a no-op.
      searchPlans(mine)
      cy.contains('#installmentBody tr', mine).should('exist')
      cy.get('#installmentBody tr').should('have.length', 1)

      searchPlans(`NoSuchCustomer${uniq()}`)
      cy.get('#installmentBody tr').should('have.length', 1)   // DataTables' "no matching records" row
      cy.contains('#installmentBody tr', mine).should('not.exist')

      // Clear it, or the next case inherits a filtered grid.
      cy.get('#tableInstallment_filter input').clear()

      // EXPORTS — present, and named so a shopkeeper can find them.
      cy.contains('.dt-button', /Excel/i).should('exist')
      cy.contains('.dt-button', /Print/i).should('exist')
      cy.contains('.dt-button', /PDF/i).should('exist')
    })
  })

  it('⭐ the export libraries are NOT loaded until somebody exports', () => {
    // PERF-4b, and the reason this grid uses lazyPdfButton rather than a plain 'pdfHtml5'. pdfmake plus its
    // font file are ~900KB gzipped — most of what is left in the bundle — and the overwhelming majority of
    // sessions never press an export button once.
    //
    // Asserting the PROPERTY (the library is absent from the window) rather than the artefact (a button
    // exists). A lazily-declared button that eagerly loaded its library would look identical on screen.
    sellOnPlan(`Lazy Buyer ${uniq()}`, 30000, 3, monthsOut(1)).then(() => {
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.window().then((w) => w.showInstallments())

      // THE LAZINESS ITSELF: the PDF button is on screen and pdfmake is still not in the page. A button
      // declared with a plain 'pdfHtml5' would have pulled the library at table init, so this single pair of
      // assertions is the whole property.
      cy.get('.dt-buttons', { timeout: 10000 }).should('be.visible')
      cy.contains('.dt-button', /PDF/i).should('exist')
      cy.window().should((w) => {
        expect(w.pdfMake, 'pdfmake has not been fetched').to.be.undefined
      })

      // And the loader genuinely works — the positive control, without which "undefined" would be satisfied
      // by an export feature that is simply broken.
      //
      // Driven through LazyExport.ensurePdfMake() rather than by CLICKING the button, deliberately: a real
      // click generates a PDF and hands the browser a DOWNLOAD, which in headed mode can leave the tab in a
      // state where Cypress cannot even take a screenshot — the failure then reports as "Unable to capture
      // screenshot" and hides whatever actually went wrong, including in the tests that follow.
      // lazy-export.js exposes this hook with the comment "Exposed for the Cypress gate"; this is that gate.
      cy.window().then((w) => w.LazyExport.ensurePdfMake())
      cy.window({ timeout: 20000 }).should((w) => {
        expect(w.pdfMake, 'and arrives when actually needed').to.not.be.undefined
      })
    })
  })

  it('a settled plan drops off the worklist', () => {
    // The list is what the shop still has to chase. A completed plan sitting on it forever is how a
    // worklist becomes something nobody reads.
    const buyer = `Settled Buyer ${uniq()}`

    sellOnPlan(buyer, 30000, 3, monthsOut(1)).then(() => {
      cy.request('/getUserCustomer?q=-1').then((r) => {
        const c = list(r.body).find((x) => x.name === buyer)

        // POSITIVE CONTROL: it is on the list before it is paid, so its absence afterwards means settled
        // rather than never-shown.
        cy.visit('/businessDashboard')
        cy.waitForAppReady()
        cy.window().then((w) => w.showInstallments())
        searchPlans(buyer)
        cy.contains('#installmentBody tr', buyer).should('exist')

        cy.request({
          method: 'POST', url: '/receivePayment', form: true,
          body: { customerId: c.customerId || c.id, amount: 30000, method: 'CASH' },
          failOnStatusCode: false,
        }).then((p) => expect(p.body.status).to.eq('SUCCESS'))

        cy.visit('/businessDashboard')
        cy.waitForAppReady()
        cy.window().then((w) => w.showInstallments())
        // Searched first: without it this passes while the row sits on another page.
        searchPlans(buyer)
        cy.contains('#installmentBody tr', buyer).should('not.exist')
      })
    })
  })
})
