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

/**
 * Receive ONE unit of a product into the serial register, the way a shop does: on a purchase.
 *
 * A serial cannot be SOLD until it has been BOUGHT - SerialUnitService refuses with
 * 'Serial "X" is not in stock.', which is the register doing its job. cy.seedProduct() creates stock but no
 * units, so a case that sells an IMEI has to receive that IMEI first.
 *
 * Shaped after buy() in serial-register.cy.js rather than invented. Note `serials` is ONE newline-joined
 * string, never repeated parameters: the monolith's purchase proxy collapses repeats and the extras vanish
 * silently.
 */
const receiveSerial = (productId, serial) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: {
      productId, quantity: 1, serials: serial,
      purchaseRate: 40000, 'stock.bpurchaseRate': 40000, 'stock.bsellRate': 60000,
      totalAmount: 40000, netAmount: 40000, paidAmount: 40000,
      purchaseInvoiceNo: 'INSTSER-' + Date.now().toString().slice(-8),
    },
  }).then((r) => {
    // Assert the receipt: /addPurchase answers GenericResponse, so a refusal is 200 with status:"ERROR".
    // Unasserted, the sale below would fail on a missing serial and read like a defect in the sale path.
    expect(r.body && r.body.status, `receiveSerial: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
  })

/**
 * Switch the installment panel on and WAIT FOR ITS OWN AJAX to land.
 *
 * ⚠ Why an intercept and not a "wait for the overlay to clear".
 *
 * R4 made ticking #sellOnInstallment call loadGuarantorPolicy(), which fires two $.gets
 * (guarantorsRequired, recentGuarantors). jQuery's ajaxStart then raises the shared "Please wait"
 * overlay over the panel, so a cy.type() into #instCount or #instFirstDueDateText fails with
 * "covered by another element: <div class='ao-box'>".
 *
 * Asserting the overlay is NOT visible does not fix it and is worse than nothing: ajaxStart fires when
 * the request BEGINS, so an assertion running in the gap between the click and the first request finds
 * no overlay, passes instantly, and hands back a screen that is about to be covered. That is exactly
 * how this file failed twice - once on #instCount, then again on the date box a few lines further on.
 *
 * Waiting for the RESPONSES is deterministic: when both have landed the overlay is down for good and
 * renderGuarantorBlocks() has already redrawn the panel, so nothing is still moving underneath.
 */
const openPlanPanel = () => {
  cy.intercept('GET', '**/guarantorsRequired*').as('gReq')
  cy.intercept('GET', '**/recentGuarantors*').as('gRecent')

  // Pin the seeded instalment count before the panel opens: toggleInstallmentPanel() reads
  // (posInstallmentCount || 6) and this spec must not inherit whatever a tenant chose.
  cy.window().then((w) => { w.posInstallmentCount = 6 })
  cy.get('#sellOnInstallment').check({ force: true })

  cy.wait('@gReq', { timeout: 20000 })
  cy.wait('@gRecent', { timeout: 20000 })
}

const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

describe('INST-1 — the sale screen sells on terms', () => {
  /*
   * THE CAPABILITY, NOT THE SETTING - and they are two different switches.
   *
   * `pos.installment.enabled` (below, per case) decides whether the PANEL is drawn. The capability
   * `org.cap.installments` decides whether the SERVER will accept a plan at all: SellController asserts it
   * before writing, and the refusal is HTTP 200 with {success:false, "This is not switched on for your
   * business."} - a sale that looks completed to a careless assertion.
   *
   * Set here rather than assumed. This tenant was found carrying an explicit `false` on the auth side
   * (which mints the JWT) while the business-side row said `true` - a disagreement that refuses every
   * financed sale on this tenant no matter what the panel shows.
   *
   * MUST be set BEFORE the login that runs the case: CapabilityService.resolveEffective reads the
   * capabilities out of the TOKEN first, so a capability granted after login is invisible until the token
   * is minted again. before() + beforeEach(login) is the order capability-enforcement.cy.js uses, and the
   * reason it works.
   */
  before(() => {
    cy.loginAsOwner()
    cy.setCapability('installments', true)
  })

  beforeEach(() => {
    cy.loginAsOwner()
  })

  after(() => {
    // Leave no server state behind: a setting left ON changes the sale screen for every later spec.
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'false')
    setConfig('pos.entry.showSerial', 'true')          // catalog defaults; this file sets them explicitly
    setConfig('pos.sale.confirmOnComplete', 'true')
    // ON is the baseline for a tenant whose specs sell on terms - the same restore
    // capability-enforcement.cy.js makes for the capabilities it needs.
    cy.setCapability('installments', true)
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
      cy.get('#sellQuantity').clear().type('1')
      cy.get('#addInviceItem').click({ force: true })   // sic: the app's id carries the typo
      overlayGone()

      openPlanPanel()

      /*
       * LET THE PANEL FINISH SEEDING BEFORE OVERWRITING IT.
       *
       * toggleInstallmentPanel() fills an EMPTY count box with (posInstallmentCount || 6) when the panel
       * opens. clear() empties it, so a clear that lands just before that seeding gets a 6 written back
       * and the typed 6 goes in FRONT of it: the box reads 66, the server builds 66 installments, and the
       * failure surfaces as "Too many elements found. Found '66', expected '6'" - a row count, nowhere
       * near the field that caused it.
       *
       * Waiting for the box to be non-empty makes the seeding a precondition rather than a competitor, and
       * the value assertion afterwards means this can never again be diagnosed from a row count.
       */
      /*
       * DO NOT clear() THIS BOX - pin what it will be seeded with instead.
       *
       * toggleInstallmentPanel() seeds #instCount with (posInstallmentCount || 6), and the guard is
       * `if (!$('#instCount').val())` - it fires only when the box is EMPTY. clear() creates exactly
       * that condition, so the seed can land in the gap between clear() and type(), and the typed 6
       * goes in FRONT of the seeded one: the box reads 66, the server builds 66 installments, and it
       * surfaces as "Too many elements found. Found '66', expected '6'" - a row count, nowhere near
       * the field that caused it.
       *
       * Waiting for the seed before clearing does NOT fix it (tried: still 66) and cannot, because the
       * seed is re-armed by the clear itself. The only race-free answer is to never empty the box:
       * pin the tenant default in the browser, as this file already does for every other pos* flag,
       * and assert what the panel put there.
       */
      cy.get('#instCount').should('have.value', '6')
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
      cy.get('#sellQuantity').clear().type('1')
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
    // SER-3c made the serial box hideable per tenant. This case TYPES into it, so it sets the field on
    // rather than inheriting whatever a previous spec left. ON is the catalog default, so this restores
    // the default rather than departing from it - and after() puts it back regardless.
    setConfig('pos.entry.showSerial', 'true')
    // Same reasoning for the till's confirm dialog: this case CLICKS Complete Sale, so it pins the
    // setting instead of inheriting it. 'true' is the catalog default.
    setConfig('pos.sale.confirmOnComplete', 'true')

    cy.seedProduct({ name: `UIS_${run}`, sellingPrice: 60000, stock: 5 }).then(({ productId }) => {
      // The handset has to be IN the register before it can be sold out of it.
      receiveSerial(productId, `IMEI${run}`)

      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.get('#sellType').select('sellDiv', { force: true })

      cy.get('#sellItemDD', { timeout: 10000 }).select(String(productId), { force: true })

      /*
       * SER-3b - the IMEI is typed on the LINE, before Add to Cart, and there is no second field for it.
       *
       * This case used to type into #instAssetRef, a box on the plan panel that asked for the same number
       * a second time. That input is gone, so the case was red against a field that no longer exists.
       *
       * Typing a serial also forces the quantity to 1 and locks it: a serialled line is ONE unit. Asserted
       * rather than assumed, because that lock is what makes `serials` a single value a plan can carry.
       */
      cy.get('#sellSerials').clear().type(`IMEI${run}`)
      cy.get('#sellQuantity').should('have.value', '1').and('have.attr', 'readonly')

      cy.get('#addInviceItem').click({ force: true })   // sic: the app's id carries the typo
      overlayGone()

      /*
       * THE MECHANISM THIS CASE EXISTS FOR.
       *
       * Add to Cart runs the generic resetForm(), which empties the entry box. So the plan CANNOT read
       * #sellSerials at submit time - it did, and every financed sale was built with assetRef = null.
       * Asserting the box is empty here pins why installment.js reads the serial off the CART instead:
       * delete cartSerial() and the assetRef assertion at the bottom of this case goes red again.
       */
      cy.get('#sellSerials').should('have.value', '')

      // A financed sale needs a named customer — it is chased for months.
      cy.get('#btnModeManual').click({ force: true })
      cy.get('#sellCN').clear().type(buyer)
      cy.get('#sellCC').clear().type(`0300U${run}`)

      // On account: the whole balance is the plan.
      cy.get('#sellPayMethod').select('CREDIT', { force: true })

      openPlanPanel()
      // Seeded first, then overwritten - see the case above for why a bare clear() yields 66.
      // Seeded from posInstallmentCount, pinned above - never cleared. See the case above for why a
      // clear() here yields 66.
      cy.get('#instCount').should('have.value', '6')
      cy.get('#instFirstDueDateText').clear().type(ddmmyyyy(monthsOut(1))).blur()
      cy.get('#instCount').trigger('change')
      cy.get('#instScheduleTable tbody tr', { timeout: 10000 }).should('have.length', 6)

      cy.intercept('POST', '**/addSell').as('sale')
      cy.get('#addSell').click({ force: true })

      /*
       * ANSWER THE TILL'S CONFIRM DIALOG - Complete Sale posts NOTHING until a cashier does.
       *
       * Not optional: this case pins pos.sale.confirmOnComplete to true, so a missing dialog is a real
       * failure rather than a setting. Skipping it is how this case failed the first time it got this
       * far - cy.wait below died with "No request ever occurred", which reads like a broken sale rather
       * than an unanswered question. See cy.confirmSale in commands.js.
       */
      cy.confirmSale()

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
