/**
 * INST-2 — the statement carries the forward schedule.
 * Design: microservices/docs/installment-dues-reminders-design.md (D10)
 *
 * WHY THIS IS THE RISKIEST-LOOKING SMALL CHANGE IN THE SLICE.
 *
 * A statement is the document a shop hands a customer when the customer disputes what they owe. The BILL line
 * already carries the plan invoice's whole financed amount, so an installment added to the ledger as a debit
 * would count the handset TWICE — and it would do it silently, on the one document whose entire purpose is to
 * be believed.
 *
 * The implementation makes that arithmetically impossible rather than merely avoided: the ledger is built and
 * balanced FIRST, and the schedule is appended afterwards, so nothing here can reach the running balance. That
 * is the property these cases assert — not that the block renders, but that the closing balance is the same
 * number it would have been with no plan in existence.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/installment-statement.cy.js --headed --no-exit
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

/** ISO date n months out, from LOCAL components — toISOString() is UTC and shifts the day at +05:00. */
const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const customerNamed = (name) =>
  cy.request('/getUserCustomer?q=-1').then((r) => list(r.body).find((c) => c.name === name))

const statementFor = (customerId) =>
  cy.request(`/customerStatement?customerId=${customerId}`).then((r) => list(r.body))

/** Sell one item, on a plan or for plain credit — the two shapes these cases compare. */
const sell = (name, run, price, plan) =>
  cy.seedProduct({ name: `STM_${run}`, sellingPrice: price, stock: 5 }).then(({ productId }) => {
    const body = {
      customer: { name, contact: `0300S${run}`, paidAmount: 0, dueAmount: 0 },
      sales: [{ productId, quantity: 1, sellRate: price, totalAmount: price, netAmount: price }],
      paidAmount: 0, dueAmount: 0, grandTotal: price,
    }
    if (plan) {
      body.installmentPlan = {
        cashPrice: price, downPayment: 0, installmentCount: 6,
        frequency: 'monthly', firstDueDate: monthsOut(1), assetRef: `IMEI${run}`,
      }
    }
    return cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body, failOnStatusCode: false }).then((r) => {
      // Assert the fixture, loudly. A plan that silently failed to exist would make every assertion below
      // pass for a reason that has nothing to do with the statement.
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      if (plan) expect(r.body.message, 'the fixture plan exists').to.contain('PLN-')
    })
  })

describe('INST-2 — the statement carries the schedule', () => {
  before(() => {
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'true')
  })

  beforeEach(() => {
    cy.loginAsOwner()
  })

  after(() => {
    // Leave no server state behind — a setting left ON changes the sale screen for every later spec.
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'false')
  })

  // ── ⭐ THE CASE THAT CARRIES THE CHANGE ────────────────────────────────────────────────────────────────

  it('the schedule does NOT move the closing balance — a financed sale and a credit sale agree', () => {
    const run = uniq()
    const financed = `Stmt Financed ${run}`
    const credit = `Stmt Credit ${run}9`
    const PRICE = 60000

    // The SAME sale twice, once on terms and once on plain credit. Design D5 says a plan is a structure over
    // the receivable rather than a second one; if that is true these two customers owe the identical amount,
    // and a statement that double-counts the schedule shows 120,000 against one of them.
    sell(financed, run, PRICE, true)
    sell(credit, `${run}9`, PRICE, false)

    customerNamed(financed).then((f) => {
      customerNamed(credit).then((c) => {
        statementFor(f.customerId || f.id).then((withPlan) => {
          statementFor(c.customerId || c.id).then((withoutPlan) => {
            const sched = withPlan.filter((l) => l.type === 'SCHEDULE')
            const ledger = withPlan.filter((l) => l.type !== 'SCHEDULE')

            // POSITIVE CONTROL. Without it "the balance did not move" is satisfied by a schedule that was
            // never added at all, and this case would go green against a feature that does not exist.
            expect(sched.length, 'the schedule block IS on the statement').to.eq(6)

            const closingFinanced = Number(ledger[ledger.length - 1].balance)
            const closingCredit = Number(withoutPlan[withoutPlan.length - 1].balance)
            expect(closingFinanced, 'financed and credit owe the same').to.eq(closingCredit)
            expect(closingFinanced, 'and it is the price, not twice the price').to.eq(PRICE)
          })
        })
      })
    })
  })

  it('the schedule reconciles to what is owed, and counts DOWN to zero', () => {
    const run = uniq()
    const buyer = `Stmt Recon ${run}`
    const PRICE = 60000

    sell(buyer, run, PRICE, true)

    customerNamed(buyer).then((c) => {
      statementFor(c.customerId || c.id).then((lines) => {
        const sched = lines.filter((l) => l.type === 'SCHEDULE')
        expect(sched.length).to.eq(6)

        // What is still owed on each installment must add up to the balance — otherwise the two halves of
        // the document disagree with each other in front of the customer.
        const sum = sched.reduce((t, l) => t + Number(l.debit), 0)
        expect(sum, 'the schedule adds up to the balance').to.eq(PRICE)

        // The balance column on these rows answers "pay this, and you will owe that" — so it must run down
        // to nothing, not repeat the closing balance six times.
        expect(Number(sched[sched.length - 1].balance), 'the last installment clears it').to.eq(0)
        expect(Number(sched[0].balance)).to.be.greaterThan(Number(sched[5].balance))

        // Every schedule row is dated, and in due order. An undated row sorts to the top of any grid the
        // customer sorts, and a plan is read as a sequence or not at all.
        sched.forEach((l) => expect(l.date, 'every installment shows its due date').to.be.ok)
      })
    })
  })

  it('a settled installment leaves the schedule — its payment is already on the ledger', () => {
    const run = uniq()
    const buyer = `Stmt Paid ${run}`
    const PRICE = 60000

    sell(buyer, run, PRICE, true)

    customerNamed(buyer).then((c) => {
      const id = c.customerId || c.id

      // POSITIVE CONTROL: six before the payment, so five afterwards means SETTLED rather than never-shown.
      statementFor(id).then((before) => {
        expect(before.filter((l) => l.type === 'SCHEDULE').length).to.eq(6)
      })

      cy.request({ method: 'POST', url: '/receivePayment', form: true,
        body: { customerId: id, amount: 10000, method: 'CASH' }, failOnStatusCode: false })
        .then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))

      statementFor(id).then((after) => {
        const sched = after.filter((l) => l.type === 'SCHEDULE')
        // The cleared installment drops off; listing it alongside the PAYMENT line that cleared it would
        // show the same 10,000 twice in two different ways on one document.
        expect(sched.length, 'the settled installment is gone').to.eq(5)
        expect(sched.reduce((t, l) => t + Number(l.debit), 0), 'and the rest still reconcile').to.eq(50000)

        // And the ledger still balances: the payment reduced it, the schedule did not touch it.
        const ledger = after.filter((l) => l.type !== 'SCHEDULE')
        expect(Number(ledger[ledger.length - 1].balance), 'the closing balance took the payment').to.eq(50000)
      })
    })
  })

  it('a customer with no plan gets the statement they always got', () => {
    // The negative control for the whole change. Every other customer in the tenant — the grocery on the
    // same code — must see a statement with no schedule block and no behaviour change at all.
    const run = uniq()
    const buyer = `Stmt Plain ${run}`

    sell(buyer, run, 5000, false)

    customerNamed(buyer).then((c) => {
      statementFor(c.customerId || c.id).then((lines) => {
        expect(lines.length, 'the statement still renders').to.be.greaterThan(0)
        expect(lines.filter((l) => l.type === 'SCHEDULE').length, 'and carries no schedule').to.eq(0)
        expect(Number(lines[lines.length - 1].balance)).to.eq(5000)
      })
    })
  })
})
