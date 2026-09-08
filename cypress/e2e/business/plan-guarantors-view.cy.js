/**
 * R4c — the guarantors on a plan can be SEEN, and added afterwards.
 *
 * ── The gap ─────────────────────────────────────────────────────────────────────────────────────
 * Guarantors were recorded at the till and then could not be read back anywhere. `GET /planGuarantors`
 * existed, was tenant-scoped, and was proxied through the monolith — and **no client code called it.** A
 * shop could take two people's names, CNICs and phone numbers and never see them again, which is most of
 * the point of taking them: a guarantor matters precisely when a plan stops being paid.
 *
 * R4b made it urgent rather than merely missing. A shortfall is now allowed, and the message a cashier gets
 * says *"add the rest on the plan when you have them"* — a promise the product could not keep, because
 * there was nowhere to add them.
 *
 * ── Where it lives ──────────────────────────────────────────────────────────────────────────────
 * With the schedule, under the plan list — the same reasoning INST-5a used for the IMEI and the repossess
 * action: it is the screen a shopkeeper is already on when a plan goes wrong.
 */

const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)

/**
 * ⚠ A PHONE NUMBER THE WAY THE SERVER READS IT.
 *
 * PlanGuarantorService.normalisePhone keeps the LAST TEN DIGITS and compares those, because a guarantor
 * reachable only on the debtor's own number cannot be contacted independently of him — the one thing a
 * guarantor has to be.
 *
 * So a raw `0300` + run and `0311` + run are the SAME person to the server: the only difference sits at
 * the front of a 15-digit string and is discarded. Every guarantor sent with a sale here was silently dropped as
 * "the buyer's own mobile number", the plan was created carrying none, and the failure read as the panel
 * not rendering them.
 *
 * A 7-digit run leaves room for a distinct 3-digit prefix inside the ten the server keeps. Same helper and
 * same lesson as installment-guarantors.cy.js: a fixture must fake a value the way the SERVER reads it.
 */
const phoneFor = (prefix, run) => prefix + String(run).slice(-7)


/** The tenant Configuration write, and it ASSERTS — a silently ignored setting makes a case vacuous. */
const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))
const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** A financed sale carrying the guarantors given. Returns the plan row it created. */
const sellOnPlan = (run, guarantors) =>
  cy.seedProduct({ name: `R4C_${run}`, sellingPrice: 50000, stock: 5 }).then(({ productId }) =>
    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: `Buyer ${run}`, contact: phoneFor('0300', run), paidAmount: 20000, dueAmount: 0 },
        sales: [{ productId, quantity: 1, sellRate: 50000, totalAmount: 50000, netAmount: 50000 }],
        paidAmount: 20000, dueAmount: 0, grandTotal: 50000,
        tenders: [{ method: 'CASH', amount: 20000, reference: '' }],
        installmentPlan: {
          cashPrice: 50000, downPayment: 20000, installmentCount: 3,
          frequency: 'monthly', firstDueDate: monthsOut(1),
          guarantors,
        },
      }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.body.status, `the sale completed: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
      return r
    }))

/**
 * The plan just created, found by its buyer.
 *
 * ⚠ `/installmentPlansOpen`, not `/installmentPlans` — the latter REQUIRES a customerId and 400s without
 * one. This is the endpoint the Installments screen itself loads, so the fixture and the screen agree about
 * which plans exist. Rows land in `collection` (GenericResponse overloads on Collection vs Object).
 */
const planOf = (run) =>
  cy.request('/installmentPlansOpen').then((r) => {
    const plans = (r.body && (r.body.collection || r.body.object || r.body.data)) || []
    const mine = plans.filter((p) => String(p.customerName || '').indexOf(run) >= 0)
    expect(mine.length, `a plan exists for Buyer ${run}: ${JSON.stringify(r.body).slice(0, 200)}`)
      .to.be.greaterThan(0)
    return mine[0]
  })

/**
 * Open the Installments screen and click the plan's row, so its schedule — and the guarantor panel under
 * it — renders.
 *
 * ⚠ `showInstallments()`, not a `#sellType` select: the Installments screen is reached from a MENU entry
 * that calls that function, and it is not an option on the sale-type dropdown at all. Same way
 * installment-worklist.cy.js opens it.
 */
const openPlan = (planNo) => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().then((w) => w.showInstallments())
  cy.get('#InstallmentDiv', { timeout: 20000 }).should('be.visible')
  cy.get('#installmentBody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)

  /*
   * ⚠ SEARCH FIRST — the grid is a DataTable and it PAGES at 50.
   *
   * The list comes back most-overdue-first, and a plan created seconds ago has nothing overdue, so it
   * sorts last. This tenant carries 128 active plans, so the row under test is never on page one and
   * cy.contains() only ever sees what is rendered — which is how every case here failed with
   * "Expected to find content: PLN-000125" for a plan that demonstrably existed.
   *
   * Same helper installment-worklist.cy.js uses for the same reason.
   */
  cy.get('#tableInstallment_filter input', { timeout: 10000 }).clear().type(planNo, { delay: 0 })
  cy.contains('#installmentBody tr', planNo, { timeout: 20000 }).click({ force: true })
  cy.get('#installmentSchedule', { timeout: 20000 }).should('not.be.empty')
}

describe('R4c — viewing a plan\'s guarantors', () => {
  const REQ = 'installments.guarantorsRequired'

  before(() => {
    // ESTABLISH what this spec needs; do not inherit it from whichever spec ran last.
    cy.loginAsMobileOwner()
    setConfig('pos.installment.enabled', 'true')
    setConfig('pos.installment.serialRequired', 'false')
    setConfig(REQ, '0')
  })

  after(() => {
    // Leave no server state behind — REQ is raised by case 3 and would otherwise follow every later spec.
    cy.loginAsMobileOwner()
    setConfig(REQ, '0')
  })

  beforeEach(() => cy.loginAsMobileOwner())

  it('⭐ 1 — the guarantors taken at the till are shown on the plan', () => {
    /*
     * THE GAP, asserted end to end: recorded through the SALE, read back through the SCREEN. A test that
     * posted to /savePlanGuarantor and then read /planGuarantors would pass without any UI at all — which
     * is exactly the state this slice found.
     */
    const run = uniq()
    sellOnPlan(run, [
      { role: 'GUARANTOR', name: `Imran ${run}`, cnic: '35201-1234567-8', contact: phoneFor('0311', run),
        address: '12 Mall Road, Lahore' },
    ]).then(() => planOf(run).then((plan) => {
      openPlan(plan.planNo)
      cy.get('#planGuarantors', { timeout: 20000 }).should('be.visible')
      cy.get('#planGuarantors').should('contain.text', `Imran ${run}`)
      // The CNIC and the phone are the whole reason the record exists — a name alone cannot be chased.
      cy.get('#planGuarantors').should('contain.text', '35201-1234567-8')
      cy.get('#planGuarantors').should('contain.text', phoneFor('0311', run))
      // ⭐ The number is dialable: on a phone, chasing is a tap.
      cy.get('#planGuarantors a[href^="tel:"]').should('exist')
    }))
  })

  it('⭐ 2 — a plan with NO guarantors says so, rather than showing nothing', () => {
    /*
     * R4b lets a sale complete with none, so this is now the common case. "Nobody is recorded" and a panel
     * that failed to load look identical if the empty state is silent — and they mean opposite things to a
     * shop deciding whether it can chase anyone.
     */
    const run = uniq()
    sellOnPlan(run, []).then(() => planOf(run).then((plan) => {
      openPlan(plan.planNo)
      cy.get('#planGuarantors', { timeout: 20000 })
        .should('be.visible')
        .and('contain.text', 'Nobody is recorded')
    }))
  })

  it('⭐⭐ 3 — a guarantor can be ADDED afterwards, which is what R4b promises', () => {
    /*
     * ⭐ The message a cashier gets on a shortfall says "add the rest on the plan when you have them".
     * Until this panel existed the product could not keep that promise. Asserted through the SCREEN and
     * then re-read, so a build that posted successfully but never refreshed the list would fail.
     */
    const run = uniq()
    setConfig(REQ, '2')

    sellOnPlan(run, [
      { role: 'GUARANTOR', name: `First ${run}`, cnic: '35201-1111111-1', contact: phoneFor('0311', run) },
    ]).then(() => planOf(run).then((plan) => {
      openPlan(plan.planNo)
      cy.get('#planGuarantors', { timeout: 20000 }).should('contain.text', `First ${run}`)

      cy.get('#pgName').type(`Second ${run}`)
      cy.get('#pgContact').type(phoneFor('0322', run))
      cy.get('#pgAdd').click()

      // Re-rendered from the SERVER, not from the form — the row must survive a real round trip.
      cy.get('#planGuarantors', { timeout: 20000 }).should('contain.text', `Second ${run}`)
      // openPlan() re-visits the dashboard, so it IS the fresh load — a cy.reload() before it only threw
      // away a page that was about to be replaced.
      openPlan(plan.planNo)
      cy.get('#planGuarantors', { timeout: 20000 })
        .should('contain.text', `First ${run}`)
        .and('contain.text', `Second ${run}`)
    }))

    setConfig(REQ, '0')
  })

  it('4 — a guarantor with no name is refused, and says why', () => {
    // The NAME is the only required field, exactly as on the sale screen. Refused in the panel rather than
    // posted and bounced, so the cashier is told where the problem is.
    const run = uniq()
    sellOnPlan(run, []).then(() => planOf(run).then((plan) => {
      openPlan(plan.planNo)
      cy.get('#pgAdd', { timeout: 20000 }).click()
      cy.get('#pgMsg').should('be.visible').and('contain.text', 'name')
      cy.get('#planGuarantors').should('contain.text', 'Nobody is recorded')
    }))
  })
})
