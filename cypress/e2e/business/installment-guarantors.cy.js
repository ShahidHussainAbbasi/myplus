/**
 * R4 — two guarantors on an installment plan.
 *
 * Design: microservices/docs/slices/r4-installment-guarantors-design.md
 *
 * ── What this gates ─────────────────────────────────────────────────────────────────────────────
 * A shop that finances a handset, a motorcycle or a fridge needs the people who stand behind the debt
 * recorded — with their CNIC — and still readable two years later. 211 live plans, 0 guarantors, because
 * there was nowhere to put one.
 *
 * ── ⭐⭐ Cases 6b and 6c do not test the feature at all, and they are the most important ones ─────
 * Measured: 43 organisations, 6 with a chosen business type. The other 37 fall back to GENERAL, whose preset
 * includes installments, plus 3 on retail. A requirement DEFAULTING to 2 would stop **40 of 43 tenants**
 * completing an installment sale on the day it deployed, for a rule not one of them asked for.
 *
 * So the shipped default is 0 and the panel does not render. 6b proves a tenant that never touched the
 * setting sells exactly as before; 6c proves another trade sees nothing. A gate that only asserted "two are
 * required" would pass a build that broke forty shops.
 *
 * ── ⭐ Case 2 is why the row is a stamped copy, not a pointer ────────────────────────────────────
 * The shop's evidence must be what the guarantor signed — not what that person's contact record says after
 * two years of edits by three different staff.
 *
 * ── Tenants ─────────────────────────────────────────────────────────────────────────────────────
 *   owner.mobile@   the feature's own tenant — retail + installments. The subject.
 *   owner.pharma@   another trade entirely. The no-surprise control.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/installment-guarantors.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
  return []
}
const payload = (body) => (body && (body.object || body.data)) || null

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

/**
 * One setting's row from the Configuration catalog.
 *
 * ⚠ Returns `null` rather than `undefined` when it finds nothing. A `.then()` that returns undefined makes
 * Cypress yield the PREVIOUS subject — so the first cut of this helper handed the whole HTTP response to the
 * assertion, which then reported a 40KB settings payload as the value of one key.
 *
 * `/getBusinessConfig` answers with `data` as an ARRAY of {key,value,defaultValue,isDefault} rows, not a map.
 */
const readConfig = (key) =>
  cy.request({ url: '/getBusinessConfig', failOnStatusCode: false }).then((r) => {
    const rows = (r.body && r.body.data) || []
    if (!Array.isArray(rows)) return null
    return rows.find((x) => x && x.key === key) || null
  })

/** ISO date n months out, from LOCAL components — toISOString() is UTC and shifts the day at +05:00. */
const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const customerNamed = (name) =>
  cy.request('/getUserCustomer?q=-1').then((r) => list(r.body).find((c) => c.name === name))

/**
 * Sell one product on a plan, optionally naming guarantors.
 *
 * Returns the raw response so a case can assert either SUCCESS or the refusal — the refusal IS the feature
 * in half of these, and `failOnStatusCode:false` keeps a 200-with-`status:ERROR` envelope readable.
 */
const sellOnPlan = (run, guarantors, opts = {}) =>
  cy.seedProduct({ name: `GRT_${run}`, sellingPrice: 50000, stock: 5 }).then(({ productId }) => {
    const body = {
      customer: { name: opts.buyerName || `Buyer ${run}`, contact: opts.buyerContact || `0300G${run}`,
                  paidAmount: 20000, dueAmount: 0 },
      sales: [{ productId, quantity: 1, sellRate: 50000, totalAmount: 50000, netAmount: 50000 }],
      paidAmount: 20000, dueAmount: 0, grandTotal: 50000,
      /*
       * ⚠ THE DEPOSIT IS THE TENDER, not `paidAmount`.
       *
       * SellController refuses the whole sale when the down payment exceeds what is being TENDERED — and its
       * javadoc says in as many words that a fixture already learned this the hard way "by asserting against
       * a figure the server never used". This spec's first run repeated it exactly: fourteen cases red on
       * "the down payment is 20000 but only 0 is being received", none of them about guarantors.
       *
       * paidAmount is what the invoice records; tenders are what settle it. Only one of them is money.
       */
      tenders: [{ method: 'CASH', amount: 20000, reference: '' }],
      installmentPlan: {
        cashPrice: 50000, downPayment: 20000, installmentCount: 3,
        frequency: 'monthly', firstDueDate: monthsOut(1),
      },
    }
    if (opts.buyerCnic) body.customer.cnic = opts.buyerCnic
    if (guarantors !== null) body.installmentPlan.guarantors = guarantors
    return cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body, failOnStatusCode: false,
    })
  })

const planOf = (name) =>
  customerNamed(name).then((c) => {
    expect(c, `customer ${name} exists`).to.be.an('object')
    return cy.request(`/installmentPlans?customerId=${c.customerId || c.id}`)
      .then((r) => { const p = list(r.body); expect(p.length, 'a plan was created').to.be.greaterThan(0); return p[0] })
  })

/**
 * ⚠ A refused plan does NOT refuse the sale.
 *
 * `createInstallmentPlan` has returned a MESSAGE and left the sale standing since INST-1 — for bad terms, an
 * uncollected deposit and an unnamed customer alike, so the shop has a paid invoice to reconcile rather than
 * a silent mismatch. A guarantor shortfall follows that same contract rather than inventing a second one:
 * the screen is what stops the cashier, and this is the backstop behind it.
 *
 * So "refused" means: the sale succeeded, the MESSAGE says why, and NO PLAN EXISTS.
 */
const expectPlanRefused = (res, buyerName, why) => {
  const body = JSON.stringify(res.body)
  expect(res.body.status, `the sale itself still stands: ${body}`).to.eq('SUCCESS')
  expect(body, `the message says why the plan was not created (${why})`).to.match(/guarantor/i)
  return customerNamed(buyerName).then((c) => {
    if (!c) return
    cy.request(`/installmentPlans?customerId=${c.customerId || c.id}`).then((r) => {
      expect(list(r.body).length, `no plan may exist: ${JSON.stringify(r.body)}`).to.eq(0)
    })
  })
}

const guarantorsOf = (planId) =>
  cy.request({ url: `/planGuarantors?planId=${planId}`, failOnStatusCode: false })
    .then((r) => { expect(r.body, `planGuarantors: ${JSON.stringify(r.body)}`).to.be.an('object'); return list(r.body) })

const G1 = (run) => ({ name: `Imran ${run}`, cnic: '35201-1234567-8', contact: `0300I${run}`,
                       address: '12 Mall Road, Lahore' })
const G2 = (run) => ({ name: `Nadia ${run}`, cnic: '35202-7654321-2', contact: `0333N${run}`,
                       address: '44 Ferozepur Road' })

describe('R4 — guarantors on an installment plan', () => {
  const REQ = 'installments.guarantorsRequired'

  before(() => {
    // ESTABLISH what this spec needs; do not inherit it. installment-plan.cy.js records what it cost when a
    // sibling spec left `serialRequired` on: 6/6 to 1/6, for a reason nothing in the file could explain.
    cy.loginAsMobileOwner()
    setConfig('pos.installment.enabled', 'true')
    setConfig('pos.installment.serialRequired', 'false')
    setConfig(REQ, '2')
  })

  beforeEach(() => cy.loginAsMobileOwner())

  after(() => {
    // Back to the SHIPPED default, not to what this spec wanted. A tenant left requiring guarantors would
    // refuse every installment sale in every other spec that touches this tenant.
    cy.loginAsMobileOwner()
    setConfig(REQ, '0')
  })

  // ── the record ──────────────────────────────────────────────────────────────────────────────────

  it('⭐ 1 — two guarantors are saved with name, CNIC, mobile and address', () => {
    const run = uniq()
    sellOnPlan(run, [G1(run), G2(run)]).then((r) => {
      expect(r.body.status, `the sale completed: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })

    planOf(`Buyer ${run}`).then((plan) => {
      guarantorsOf(plan.id).then((rows) => {
        expect(rows.length, 'both guarantors reached business-service').to.eq(2)
        const imran = rows.find((g) => /^Imran/.test(g.name))
        expect(imran, 'the first guarantor by name').to.be.an('object')
        // Every field, not just the name: a partial save looks identical to a complete one on a list screen.
        expect(imran.cnic, 'CNIC').to.eq('35201-1234567-8')
        expect(imran.contact, 'mobile').to.contain('0300I')
        expect(imran.address, 'address').to.contain('Mall Road')
      })
    })
  })

  it('⭐ 2 — editing that person\'s contact record later does NOT change the stamped guarantor', () => {
    /*
     * The reason the row is a stamped copy rather than a pointer. The shop's evidence is what was signed;
     * a contact record edited two years later by three different staff is not evidence of anything.
     */
    const run = uniq()
    const g = G1(run)
    sellOnPlan(run, [g, G2(run)]).then((r) => expect(r.body.status).to.eq('SUCCESS'))

    planOf(`Buyer ${run}`).then((plan) => {
      // Change the customer record that shares this guarantor's phone number, the de-dup key.
      cy.request({
        method: 'POST', url: '/addCustomer', headers: { 'Content-Type': 'application/json' },
        body: { name: `Imran RENAMED ${run}`, contact: g.contact, address: 'Somewhere else entirely' },
        failOnStatusCode: false,
      })

      guarantorsOf(plan.id).then((rows) => {
        const imran = rows.find((x) => x.cnic === g.cnic)
        expect(imran, 'the guarantor is still there').to.be.an('object')
        expect(imran.name, 'the name AS SIGNED, not as later edited').to.eq(g.name)
        expect(imran.address, 'and the address as signed').to.contain('Mall Road')
      })
    })
  })

  // ── the rule, and whose rule it is ──────────────────────────────────────────────────────────────

  it('⭐ 3 — one guarantor is refused when the shop requires two, and the message says so', () => {
    const run = uniq()
    setConfig(REQ, '2')
    sellOnPlan(run, [G1(run)]).then((r) => {
      // The number, in the message. "Invalid" tells a cashier nothing they can act on.
      expect(JSON.stringify(r.body), 'the refusal names how many are needed').to.match(/2|two/i)
      expectPlanRefused(r, `Buyer ${run}`, 'one of two')
    })
  })

  it('⭐ 5 — set the requirement to 1, and a one-guarantor sale completes', () => {
    // It is a POLICY, not a constant. `if (size < 2)` is `if (organizationId == 24)` with the number moved.
    const run = uniq()
    setConfig(REQ, '1')
    sellOnPlan(run, [G1(run)]).then((r) => {
      expect(r.body.status, `one is enough when one is the rule: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })
    setConfig(REQ, '2')
  })

  it('⭐ 6 — set it to 0, and a sale with no guarantors completes', () => {
    const run = uniq()
    setConfig(REQ, '0')
    sellOnPlan(run, []).then((r) => {
      expect(r.body.status, `zero means the question is not asked: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })
    setConfig(REQ, '2')
  })

  it('⭐⭐ 6b — a tenant that has NEVER touched the setting sells exactly as before', () => {
    /*
     * THE CASE THAT PROTECTS FORTY SHOPS. 43 organisations, 6 with a chosen business type; the rest fall back
     * to GENERAL, whose preset includes installments. A requirement defaulting to 2 would refuse an
     * installment sale for 40 of them on deploy day, naming guarantors they have never heard of.
     *
     * Asserted by CLEARING the tenant's own override and reading what the platform then answers.
     */
    const run = uniq()
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true,
                 body: { key: REQ, value: '' }, failOnStatusCode: false })

    readConfig(REQ).then((row) => {
      expect(row, 'the setting is registered and reachable in Configuration').to.be.an('object')
      // THE assertion of this slice: what the platform ships, before any tenant has an opinion.
      expect(String(row.defaultValue), 'the SHIPPED default asks for NO guarantors').to.eq('0')
      const effective = Number(row.value || 0)
      expect(effective, 'and a tenant that cleared its override is back to none').to.eq(0)
    })

    sellOnPlan(run, null).then((r) => {
      expect(r.body.status,
        `a sale that names no guarantors at all must still complete: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })
    setConfig(REQ, '2')
  })

  it('⭐ 6c — another trade sees nothing: a pharmacy sells on terms with no guarantor rule', () => {
    // "It should not be a surprise for other type of business users." Asserted as another tenant entirely.
    const run = uniq()
    cy.loginAsPharmaOwner()
    setConfig('pos.installment.enabled', 'true')
    setConfig('pos.installment.serialRequired', 'false')
    sellOnPlan(run, null).then((r) => {
      expect(r.body.status,
        `a pharmacy must not inherit a mobile shop's rule: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })
  })

  it('6d — an identifier that is not CNIC-shaped still saves', () => {
    // CNIC is Pakistani; this product ships in six languages. The format is advice, never a refusal —
    // only the NAME is ever mandatory.
    const run = uniq()
    const odd = { name: `Foreign ${run}`, cnic: 'AB-99887766', contact: `0311F${run}`, address: 'Dubai' }
    setConfig(REQ, '1')
    sellOnPlan(run, [odd]).then((r) => {
      expect(r.body.status, `an unusual identifier must not block a sale: ${JSON.stringify(r.body)}`)
        .to.eq('SUCCESS')
    })
    planOf(`Buyer ${run}`).then((plan) => guarantorsOf(plan.id).then((rows) => {
      expect(rows[0].cnic, 'stored exactly as typed').to.eq('AB-99887766')
    }))
    setConfig(REQ, '2')
  })

  it('⭐ 7 — the rule is enforced on the SERVER, not only in the form', () => {
    /*
     * A rule that lives in JavaScript is a rule until somebody posts the endpoint directly. This case IS
     * that post: no browser, no form, one guarantor, requirement of two.
     */
    const run = uniq()
    setConfig(REQ, '2')
    sellOnPlan(run, [G1(run)]).then((r) => expectPlanRefused(r, `Buyer ${run}`, 'posted directly'))
  })

  it('⭐ 8 — plans that predate the rule still open, list and take a receipt', () => {
    /*
     * 211 live plans carry zero guarantors. A rule applied backwards would make every one of them unopenable
     * and unpayable — the feature would take the shop's collections screen away.
     */
    /*
     * ⚠ SEED the plan that predates the rule; do not assume one exists. owner.mobile@ holds ZERO open plans
     * (measured: openPlans 0, outstanding 0), so a case that asserted `> 0` against whatever the tenant
     * happened to have would report the feature broken because the FIXTURE was wrong.
     * GATE-RUNBOOK §7: existence is not eligibility.
     */
    const run = uniq()
    setConfig(REQ, '0')
    sellOnPlan(run, []).then((r) => expect(r.body.status, 'a plan with no guarantors, as 211 live ones are')
      .to.eq('SUCCESS'))

    // NOW turn the rule on. The plan created a moment ago must be unaffected by it.
    setConfig(REQ, '2')
    cy.request({ url: '/installmentPlansOpen', failOnStatusCode: false }).then((r) => {
      const plans = list(r.body)
      expect(plans.length, 'the guarantor-less plan is still listed while the rule is ON').to.be.greaterThan(0)
      // And it opens in detail, which is what a collections call actually does.
      cy.request({ url: `/planGuarantors?planId=${plans[0].id}`, failOnStatusCode: false })
        .then((g) => expect(g.status, 'an older plan opens rather than erroring').to.eq(200))
    })
  })

  // ── the two easy slips ──────────────────────────────────────────────────────────────────────────

  it('⭐ 9 — the buyer cannot guarantee himself, and the same person cannot be both guarantors', () => {
    const run = uniq()
    const dup = G1(run)

    // Same CNIC twice.
    sellOnPlan(run, [dup, { ...G2(run), cnic: dup.cnic }])
      .then((r) => expectPlanRefused(r, `Buyer ${run}`, 'the same person twice'))

    // The buyer as his own guarantor — worth precisely nothing, and the easiest mistake this form can make.
    /*
     * WHY THIS CASE ASSERTS ON THE PHONE AS WELL AS THE CNIC.
     *
     * The first run passed a matching CNIC and the plan was created anyway: the sale path does not
     * persist `customer.cnic`, so a buyer created during the sale has none to compare against, and
     * platform-wide only 10 of 2,545 customers carry one. A guard keyed on CNIC alone fires for 0.4%
     * of customers, which is indistinguishable from not existing.
     *
     * The phone is NOT NULL on every customer, so it is the signal that actually protects anyone.
     * Both are sent below: whichever the server matches on, it must refuse.
     */
    const run2 = uniq()
    const selfCnic = '35201-5556667-1'
    const selfPhone = `0300S${run2}`
    sellOnPlan(run2, [{ name: `Self ${run2}`, cnic: selfCnic, contact: selfPhone }, G2(run2)],
               { buyerName: `Self ${run2}`, buyerContact: selfPhone, buyerCnic: selfCnic })
      .then((r) => expectPlanRefused(r, `Self ${run2}`, 'the buyer guaranteeing himself'))
  })

  // ── recall ──────────────────────────────────────────────────────────────────────────────────────

  it('⭐ 10 — a COMPLETE CNIC recalls a guarantor used before; a partial one recalls nobody', () => {
    /*
     * Exact match only, and only within this shop. A prefix search would let staff type `352` and walk a list
     * of national identifiers; a complete 13-digit match cannot be walked — you already hold the card.
     */
    const run = uniq()
    const g = G1(run)
    sellOnPlan(run, [g, G2(run)]).then((r) => expect(r.body.status).to.eq('SUCCESS'))

    cy.request({ url: `/guarantorRecall?cnic=${encodeURIComponent(g.cnic)}`, failOnStatusCode: false })
      .then((r) => {
        const hit = payload(r.body)
        expect(hit, `a full CNIC recalls the person: ${JSON.stringify(r.body)}`).to.be.an('object')
        expect(hit.name).to.contain('Imran')
      })

    cy.request({ url: '/guarantorRecall?cnic=352', failOnStatusCode: false }).then((r) => {
      const hit = payload(r.body)
      expect(hit === null || hit === undefined || Object.keys(hit || {}).length === 0,
        `a partial CNIC must recall NOBODY: ${JSON.stringify(r.body)}`).to.eq(true)
    })
  })

  it('11 — recent guarantors are this shop\'s own, and nobody else\'s', () => {
    cy.request({ url: '/recentGuarantors', failOnStatusCode: false }).then((r) => {
      expect(r.status, `recentGuarantors answers: ${JSON.stringify(r.body)}`).to.eq(200)
      const rows = list(r.body)
      rows.forEach((g) => expect(g.name, 'every row is a named person').to.be.a('string').and.not.be.empty)
    })
  })

  // ── afterwards ──────────────────────────────────────────────────────────────────────────────────

  it('12 — an existing plan can gain a guarantor afterwards', () => {
    const run = uniq()
    setConfig(REQ, '0')
    sellOnPlan(run, []).then((r) => expect(r.body.status).to.eq('SUCCESS'))

    planOf(`Buyer ${run}`).then((plan) => {
      const g = G1(run)
      cy.request({
        method: 'POST', url: '/savePlanGuarantor', form: true, failOnStatusCode: false,
        body: { planId: plan.id, name: g.name, cnic: g.cnic, contact: g.contact, address: g.address },
      }).then((r) => expect(r.body && (r.body.success || r.body.status === 'SUCCESS'),
        `retrospective add: ${JSON.stringify(r.body)}`).to.not.eq(false))

      guarantorsOf(plan.id).then((rows) => {
        expect(rows.length, '211 plans with none must be able to gain one').to.eq(1)
      })
    })
    setConfig(REQ, '2')
  })

  // ── tenancy and privilege ───────────────────────────────────────────────────────────────────────

  it('⭐ 13 — a plan id belonging to another tenant is refused', () => {
    const run = uniq()
    setConfig(REQ, '0')
    sellOnPlan(run, []).then((r) => expect(r.body.status).to.eq('SUCCESS'))

    planOf(`Buyer ${run}`).then((plan) => {
      cy.loginAsPharmaOwner()
      cy.request({ url: `/planGuarantors?planId=${plan.id}`, failOnStatusCode: false }).then((r) => {
        const rows = list(r.body)
        expect(rows.length, `another tenant's guarantors must not be readable: ${JSON.stringify(r.body)}`).to.eq(0)
      })
    })
    cy.loginAsMobileOwner()
    setConfig(REQ, '2')
  })

  // ── the screen ──────────────────────────────────────────────────────────────────────────────────

  it('⭐ 14 — only ONE control on the sale screen says "customer"', () => {
    /*
     * The owner's catch. An earlier design put a second "From an existing customer" select inside each
     * guarantor block, under the "Select Customer" that already opens the sale. Two dropdowns, same word,
     * two different people — and a cashier who picks the wrong one records a plan guaranteed by its debtor.
     *
     * An API gate cannot see this. It has to be the screen.
     */
    setConfig(REQ, '2')
    cy.visitSaleScreen()
    cy.get('#sellCustomerDD').should('exist')
    cy.get('#sellDiv select, #sellDiv input').then(($els) => {
      const labelled = $els.toArray().filter((el) => {
        const id = (el.id || '').toLowerCase()
        return /customer/.test(id) && !/guarantor/.test(id)
      })
      expect(labelled.length, `exactly one customer control, found: ${labelled.map((e) => e.id).join(', ')}`)
        .to.eq(1)
    })
  })

  it('15 — at 360px every guarantor control is inside the viewport', () => {
    /*
     * ⚠ Check the LEFT edge, not only the right. A scrollWidth assertion only ever detects overflow to the
     * right, and fifteen sale-screen controls sat off the LEFT of a phone screen through 49 green runs.
     */
    setConfig(REQ, '2')
    cy.viewport(360, 800)
    cy.visitSaleScreen()
    cy.get('body').then(() => {
      cy.get('[data-guarantor-panel]').then(($p) => {
        if (!$p.length) return           // panel renders only once the installment box is ticked
        $p.find('input, select, button').each((_, el) => {
          const r = el.getBoundingClientRect()
          if (r.width === 0 && r.height === 0) return
          expect(r.left, `${el.id || el.name || el.tagName} off the LEFT edge`).to.be.at.least(0)
          expect(r.right, `${el.id || el.name || el.tagName} off the right edge`).to.be.at.most(360)
        })
      })
    })
  })
})
