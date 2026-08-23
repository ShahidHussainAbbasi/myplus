/**
 * The deposit on a financed sale — one number, entered once, reaching both the schedule and the till.
 *
 * WHAT WAS WRONG.
 *
 * "Down payment" (plan panel) and "Amount Received" (checkout) were two independent fields for one sum.
 * The schedule finances `price − down payment`; the invoice records what was actually paid. Nothing
 * compared them, so typing one and forgetting the other left the two describing different debts:
 *
 *   down 5,000 / received 0   →  40,000 scheduled against a 45,000 bill: 5,000 no instalment covers and
 *                                no reminder chases.
 *   down 0 / received 5,000   →  45,000 scheduled against a 40,000 bill: the customer is billed for money
 *                                already handed over, and paying every instalment leaves them in credit.
 *
 * Neither errors. Neither is visible on any screen. The books simply drift.
 *
 * THE PROPERTY, and it is one line: the instalments must add up to what the invoice says is owed.
 * Everything downstream — statement, aging, chase list, repossession — reads one or the other and
 * silently assumes they agree.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/installment-down-payment.cy.js --headed --no-exit
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

const ddmmyyyy = (iso) => `${iso.slice(8, 10)}-${iso.slice(5, 7)}-${iso.slice(0, 4)}`

const customerNamed = (name) =>
  cy.request('/getUserCustomer?q=-1').then((r) => list(r.body).find((c) => c.name === name))

/** Sell one handset on terms through the API. `received` is what actually reached the till. */
const sellOnTerms = (buyer, price, down, received, count = 8) => {
  const run = uniq()
  return cy.seedProduct({ name: `DEP_${run}`, sellingPrice: price, stock: 5 }).then(({ productId }) =>
    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: buyer, contact: `0300D${run}`, paidAmount: received, dueAmount: received - price },
        sales: [{ productId, quantity: 1, sellRate: price, totalAmount: price, netAmount: price }],
        paidAmount: received, dueAmount: received - price, grandTotal: price,
        installmentPlan: {
          cashPrice: price, downPayment: down, installmentCount: count,
          frequency: 'monthly', firstDueDate: monthsOut(1),
        },
      }, failOnStatusCode: false,
    }))
}

describe('The deposit on a financed sale', () => {
  before(() => {
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'true')
  })

  beforeEach(() => cy.loginAsOwner())

  after(() => {
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'false')
  })

  // ── ⭐ THE INVARIANT ───────────────────────────────────────────────────────────────────────────────────

  it('⭐ the instalments add up to exactly what the invoice says is owed', () => {
    const buyer = `Deposit Buyer ${uniq()}`

    // 45,000 handset, 5,000 deposit taken at the counter, 40,000 financed over 8.
    sellOnTerms(buyer, 45000, 5000, 5000).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      expect(r.body.message, 'the plan was created').to.contain('PLN-')

      customerNamed(buyer).then((c) => {
        cy.request(`/installmentPlans?customerId=${c.customerId || c.id}`).then((pr) => {
          const plan = list(pr.body)[0]
          expect(plan, 'the plan is stored').to.exist

          expect(Number(plan.financedAmount), 'financed = price − deposit').to.eq(40000)
          expect(Number(plan.totalOutstanding), 'and that is what is scheduled').to.eq(40000)

          const scheduled = plan.installments.reduce((t, i) => t + Number(i.amount), 0)
          expect(scheduled, 'the rows themselves add up to it').to.eq(40000)

          // THE ASSERTION THAT MATTERS. Customer.dueAmount is the running balance every other screen
          // reads. If it and the schedule disagree, one of them is lying to the shop.
          expect(Number(c.dueAmount), 'the customer owes exactly what is scheduled').to.eq(40000)
        })
      })
    })
  })

  it('a deposit that never reached the till is refused', () => {
    // The browser now mirrors the deposit into Amount Received, so this state is hard to reach by hand —
    // which is exactly why the check lives on the SERVER too. A browser is a convenience, not a control.
    const buyer = `No Deposit ${uniq()}`

    sellOnTerms(buyer, 45000, 5000, 0).then((r) => {
      // The SALE still stands — the long-standing contract for a plan that cannot be created — so the
      // shop is left with an invoice to reconcile rather than a silent mismatch nobody can see.
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      expect(r.body.message, 'and it says why, naming both figures').to.contain('NOT created')
      expect(r.body.message).to.contain('5000')

      customerNamed(buyer).then((c) => {
        cy.request(`/installmentPlans?customerId=${c.customerId || c.id}`).then((pr) => {
          expect(list(pr.body).length, 'no plan was written').to.eq(0)
        })
      })
    })
  })

  it('taking MORE than the deposit is allowed — that is change, not a mismatch', () => {
    // The guard refuses too LITTLE, never too much. A customer handing 6,000 for a 5,000 deposit is
    // ordinary; a rule that refused it would break the till to protect an invariant it does not threaten.
    const buyer = `Over Tender ${uniq()}`

    sellOnTerms(buyer, 45000, 5000, 6000).then((r) => {
      expect(r.body.status).to.eq('SUCCESS')
      expect(r.body.message, 'the plan still exists').to.contain('PLN-')
    })
  })

  // ── ⭐ the screen: one number, typed once ──────────────────────────────────────────────────────────────

  it('⭐ typing the deposit fills the till, and the two amounts are named separately', () => {
    // The UI half of the same defect. The till used to show ONE figure — "40,000 due" — which is
    // precisely the number the cashier must NOT type into Amount Received.
    const run = uniq()

    cy.seedProduct({ name: `DEPUI_${run}`, sellingPrice: 45000, stock: 5 }).then(({ productId }) => {
      cy.visitSaleScreen()
      cy.get('#sellType').select('sellDiv', { force: true })

      cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
      cy.get('#sellItems').clear().type('1')
      cy.get('#addInviceItem').click({ force: true })   // sic: the app's id carries the typo

      // Hidden on an ordinary sale — the negative control for the assertion below.
      cy.get('#sellDueTodayWrap').should('not.be.visible')

      cy.get('#sellOnInstallment').check({ force: true })
      cy.get('#instCount').clear().type('8')
      cy.get('#instFirstDueDateText').clear().type(ddmmyyyy(monthsOut(1))).blur()
      cy.get('#instDownPayment').clear().type('5000').trigger('change')

      // ONE NUMBER, TYPED ONCE: the deposit reaches the till by itself.
      cy.get('#sellRec').should('have.value', '5000')

      // And the two amounts are now named, instead of one unlabelled figure.
      cy.get('#sellDueTodayWrap').should('be.visible')
      cy.get('#sellOnPlan').should('have.value', '40000.00')
      cy.get('#sellDueToday').should('have.value', '0.00')   // the deposit is covered

      // Clear the till and the shortfall is visible rather than silent.
      cy.get('#sellRec').clear().trigger('keyup')
      cy.get('#sellDueToday').should('have.value', '5000.00')
    })
  })
})
