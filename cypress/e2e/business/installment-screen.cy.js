/**
 * INST-1 (UI) — selling on terms from the sale SCREEN, the way a cashier does.
 * Design: microservices/docs/installment-dues-reminders-design.md (§15)
 *
 * WHY THIS EXISTS SEPARATELY FROM installment-plan.cy.js.
 *
 * That spec drives the API. This one drives the screen — and every INST-1 defect so far lived on a path that
 * looked fine from the API and was broken for a person: the customer id that only exists after the sale, the
 * JSON key a browser omits, the mapping that only fails on a real insert. A feature reachable only by
 * `cy.request` is review finding R7, which this programme has hit three times.
 *
 * The case that matters most is the last one: a sale rung up through the FORM creates the plan. Everything
 * before it is scaffolding for that.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/installment-screen.cy.js --headed --no-exit
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

/** yyyy-MM-dd n months out, from LOCAL components — toISOString() is UTC and shifts the day at +05:00. */
/** dd-MM-yyyy — what the visible date box shows and what a cashier actually types. */
const ddmmyyyy = (iso) => `${iso.slice(8, 10)}-${iso.slice(5, 7)}-${iso.slice(0, 4)}`

const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

describe('INST-1 — the sale screen sells on terms', () => {
  beforeEach(() => {
    cy.loginAsOwner()
  })

  after(() => {
    // Leave no server state behind: a setting left ON changes the sale screen for every later spec.
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'false')
  })

  // ── the panel is a tenant decision ────────────────────────────────────────────────────────────────────

  it('the panel is HIDDEN for a shop that has not switched installments on', () => {
    // A default is not a decision. A grocery on the same BUSINESS user type must see its sale screen
    // unchanged — which is the whole argument for this being a setting rather than a vertical.
    setConfig('pos.installment.enabled', 'false')

    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#sellType').select('sellDiv', { force: true })

    cy.get('#sellInstallmentWrap').should('not.be.visible')
  })

  it('the panel APPEARS once the shop switches it on', () => {
    // The positive control for the case above: without it, "not visible" would be satisfied by a panel that
    // never renders at all, and the setting would be proving nothing.
    setConfig('pos.installment.enabled', 'true')

    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#sellType').select('sellDiv', { force: true })

    cy.get('#sellInstallmentWrap', { timeout: 10000 }).should('be.visible')
    // The fields stay collapsed until the cashier ticks the box — an ordinary sale is still the default act.
    cy.get('#sellInstallmentFields').should('not.be.visible')
  })

  /**
   * Wait for the app's "please wait" overlay to lift.
   *
   * Adding a cart line POSTs, and `.ao-box` covers the whole form while that is in flight — so a test that
   * clicks Add and immediately types into the plan fields is racing it. Cypress reports that as
   * "cy.clear() failed because this element is being covered by another element", which reads as a broken
   * field rather than a timing problem.
   *
   * NOT `{force: true}`: forcing would type into a control the operator genuinely cannot reach yet, so the
   * test would pass on a screen a human could not use. Waiting asserts the same thing a cashier experiences.
   */
  const overlayGone = () => {
    cy.get('#appAjaxOverlay', { timeout: 30000 }).should('not.be.visible')
    cy.get('.ao-box', { timeout: 30000 }).should('not.be.visible')
  }

  // ── the preview ───────────────────────────────────────────────────────────────────────────────────────

  it('the preview shows the schedule BEFORE anything is committed', () => {
    const run = uniq()
    setConfig('pos.installment.enabled', 'true')

    cy.seedProduct({ name: `UIP_${run}`, sellingPrice: 60000, stock: 5 }).then(({ productId }) => {
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.get('#sellType').select('sellDiv', { force: true })

      // Put the handset in the cart so the plan has a price to finance.
      cy.get('#sellItemDD', { timeout: 10000 }).select(String(productId), { force: true })
      cy.get('#sellItems').clear().type('1')
      cy.get('#addInviceItem').click({ force: true })   // sic: the app's id carries the typo
      overlayGone()

      cy.get('#sellOnInstallment').check({ force: true })
      cy.get('#instCount').clear().type('6')
      cy.get('#instFirstDueDateText').clear().type(ddmmyyyy(monthsOut(1))).blur()
      cy.get('#instFrequency').select('monthly', { force: true })
      // Nudge the preview the way a cashier's last keystroke would.
      cy.get('#instCount').trigger('change')

      cy.get('#instScheduleTable tbody tr', { timeout: 10000 }).should('have.length', 6)

      // The amounts shown must sum to what is being financed — this is the promise being read aloud to a
      // customer, and it is the same generator the commit uses, so it must reconcile here too.
      cy.get('#instScheduleTable tbody tr td:nth-child(3)').then(($cells) => {
        const sum = [...$cells].reduce((t, c) => t + Number(c.innerText.replace(/,/g, '')), 0)
        expect(sum, 'the previewed schedule sums to the financed amount').to.eq(60000)
      })
    })
  })

  it('an impossible plan is refused in words the cashier can act on', () => {
    const run = uniq()
    setConfig('pos.installment.enabled', 'true')

    cy.seedProduct({ name: `UIR_${run}`, sellingPrice: 60000, stock: 5 }).then(({ productId }) => {
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.get('#sellType').select('sellDiv', { force: true })

      cy.get('#sellItemDD', { timeout: 10000 }).select(String(productId), { force: true })
      cy.get('#sellItems').clear().type('1')
      cy.get('#addInviceItem').click({ force: true })   // sic: the app's id carries the typo
      overlayGone()

      cy.get('#sellOnInstallment').check({ force: true })
      cy.get('#instFirstDueDateText').clear().type(ddmmyyyy(monthsOut(1))).blur()
      // A down payment larger than the price. The server's own words come back, not a generic failure —
      // "the down payment cannot be more than the price" tells the cashier which field to change.
      cy.get('#instDownPayment').clear().type('90000').trigger('change')

      cy.get('#instSchedulePreview', { timeout: 10000 })
        .should('contain.text', 'down payment')
      cy.get('#instScheduleTable').should('not.exist')
    })
  })

  // ── ⭐ THE CASE THAT CARRIES THE SLICE ─────────────────────────────────────────────────────────────────

  it('a sale rung up through the FORM creates the plan', () => {
    const run = uniq()
    const buyer = `UI Buyer ${run}`
    setConfig('pos.installment.enabled', 'true')

    cy.seedProduct({ name: `UIS_${run}`, sellingPrice: 60000, stock: 5 }).then(({ productId }) => {
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.get('#sellType').select('sellDiv', { force: true })

      cy.get('#sellItemDD', { timeout: 10000 }).select(String(productId), { force: true })
      cy.get('#sellItems').clear().type('1')
      cy.get('#addInviceItem').click({ force: true })   // sic: the app's id carries the typo
      overlayGone()

      // A financed sale needs a named customer — it is chased for months.
      cy.get('#btnModeManual').click({ force: true })
      cy.get('#sellCN').clear().type(buyer)
      cy.get('#sellCC').clear().type(`0300U${run}`)

      // On account: the whole balance is the plan.
      cy.get('#sellPayMethod').select('CREDIT', { force: true })

      cy.get('#sellOnInstallment').check({ force: true })
      cy.get('#instCount').clear().type('6')
      cy.get('#instFirstDueDateText').clear().type(ddmmyyyy(monthsOut(1))).blur()
      cy.get('#instAssetRef').clear().type(`IMEI${run}`)
      cy.get('#instCount').trigger('change')
      cy.get('#instScheduleTable tbody tr', { timeout: 10000 }).should('have.length', 6)

      cy.intercept('POST', '**/addSell').as('sale')
      cy.get('#addSell').click({ force: true })

      cy.wait('@sale', { timeout: 20000 }).then((i) => {
        // THE assertion the API spec cannot make: the BROWSER put the plan block on the wire. If main.js
        // did not contribute it, or installment.js read a field that does not exist, the sale would still
        // succeed and the plan would silently never exist — which is exactly design note F2's failure.
        expect(i.request.body.installmentPlan, 'the browser sent the plan block').to.exist
        expect(i.request.body.installmentPlan.installmentCount).to.eq(6)
        expect(i.request.body.installmentPlan.assetRef).to.eq(`IMEI${run}`)
        expect(i.response.body.status, JSON.stringify(i.response.body)).to.eq('SUCCESS')
        expect(i.response.body.message, 'and the server created it').to.contain('PLN-')
      })

      // And it is readable afterwards, with the schedule the cashier previewed.
      cy.request('/getUserCustomer?q=-1').then((r) => {
        const c = list(r.body).find((x) => x.name === buyer)
        expect(c, 'the buyer was created by the sale').to.exist

        cy.request(`/installmentPlans?customerId=${c.customerId || c.id}`).then((pr) => {
          const plan = list(pr.body)[0]
          expect(plan, 'the plan is stored').to.exist
          expect(plan.installments.length).to.eq(6)
          expect(plan.assetRef).to.eq(`IMEI${run}`)

          const total = plan.installments.reduce((t, i) => t + Number(i.amount), 0)
          expect(total, 'what was stored matches what was previewed').to.eq(60000)
        })
      })
    })
  })
})
