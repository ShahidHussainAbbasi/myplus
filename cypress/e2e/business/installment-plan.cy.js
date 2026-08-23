/**
 * INST-1 — selling a handset on installment, for Shahzad Mobile Shop.
 * Design: microservices/docs/installment-dues-reminders-design.md
 *
 * WHAT THIS GATE IS REALLY FOR, and why the headline case is a trial balance.
 *
 * The whole design rests on ONE claim: an installment plan is a STRUCTURE OVER the existing receivable, not a
 * second one. It adds no GL account, no posting event and no gl_outbox column, so an installment sale must
 * post EXACTLY what the same sale on plain credit posts. If that is true, months of receipts are safe. If it
 * is false, the books drift slowly and nobody notices for a quarter.
 *
 * An invoice assertion cannot see that. `4200 Sales Discount` sat empty in every tenant for months while three
 * specs stayed green, because they all checked the invoice. So the first case here sells the SAME basket twice
 * — once on credit, once on a plan — and asserts the two GL movements are identical.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/installment-plan.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
const acct = (rows, code) => (rows || []).find((x) => x.code === code) || { debit: 0, credit: 0 }
/** The trial balance nets each account to one side, so assert the SIGNED movement, not an absolute. */
const net = (rows, code) => { const a = acct(rows, code); return Number(a.debit) - Number(a.credit) }

const list = (body) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
  return []
}

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

/** ISO date n months out, built from LOCAL components — toISOString() is UTC and shifts the day at +05:00. */
const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const customerNamed = (name) =>
  cy.request('/getUserCustomer?q=-1').then((r) => list(r.body).find((c) => c.name === name))

describe('INST-1 — selling on installment', () => {
  before(() => {
    // The feature is OFF by default, deliberately — a default is not a decision. Turn it on once for the
    // suite; the final case turns it off again and asserts the refusal, which doubles as the cleanup.
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'true')

    // ⚠ ESTABLISH the state this spec needs; do not inherit it. Every sale below omits a serial, so a
    // tenant left with serialRequired ON refuses all of them — and that is not hypothetical: it happened.
    // installment-serial.cy.js turns the setting on and clears it in after(), but an after() hook is not a
    // guarantee. When the auth token expired mid-run, that hook failed with the rest of the file and the
    // setting stayed on, so this spec went from 6/6 to 1/6 for a reason nothing in it could explain.
    //
    // Cleanup is courtesy; SETUP is correctness. A spec that depends on a switch should set it.
    setConfig('pos.installment.serialRequired', 'false')
  })

  beforeEach(() => {
    // testIsolation clears the session between tests, so the login belongs here, not in before().
    cy.loginAsOwner()
  })

  after(() => {
    // Leave no server state behind: a tenant setting left ON would change the sale screen for every later
    // spec in the run. (period-close once left the books locked and reddened every sale spec after it.)
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'false')
  })

  // ── ⭐ THE CASE THAT CARRIES THE SLICE ─────────────────────────────────────────────────────────────────

  it('an installment sale posts the SAME journal as the same sale on plain credit', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })

    const run = uniq()
    const PRICE = 60000

    cy.seedProduct({ name: `INSTP_${run}`, sellingPrice: PRICE, stock: 5 }).then(({ productId }) => {
      // ── leg 1: the SAME basket, sold on ordinary credit ──────────────────────────────────────────────
      tb().then((before1) => {
        const arBefore = net(before1.rows, '1100')
        const salesBefore = net(before1.rows, '4000')

        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          body: {
            customer: { name: `Credit Buyer ${run}`, contact: `0300C${run}`, paidAmount: 0, dueAmount: 0 },
            sales: [{ productId, quantity: 1, sellRate: PRICE, totalAmount: PRICE, netAmount: PRICE }],
            paidAmount: 0, dueAmount: 0, grandTotal: PRICE,
          }, failOnStatusCode: false,
        }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

        tb().then((after1) => {
          const creditAr = net(after1.rows, '1100') - arBefore
          const creditSales = net(after1.rows, '4000') - salesBefore

          // ── leg 2: the SAME basket, sold on a PLAN ────────────────────────────────────────────────────
          const arBefore2 = net(after1.rows, '1100')
          const salesBefore2 = net(after1.rows, '4000')

          cy.request({
            method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
            body: {
              customer: { name: `Plan Buyer ${run}`, contact: `0300P${run}`, paidAmount: 0, dueAmount: 0 },
              sales: [{ productId, quantity: 1, sellRate: PRICE, totalAmount: PRICE, netAmount: PRICE }],
              paidAmount: 0, dueAmount: 0, grandTotal: PRICE,
              installmentPlan: {
                cashPrice: PRICE, downPayment: 0, installmentCount: 6,
                frequency: 'monthly', firstDueDate: monthsOut(1), assetRef: `IMEI${run}`,
              },
            }, failOnStatusCode: false,
          }).then((r) => {
            expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
            expect(r.body.message, 'the plan was created, not silently dropped — F2 would show here')
              .to.contain('PLN-')
          })

          tb().then((after2) => {
            const planAr = net(after2.rows, '1100') - arBefore2
            const planSales = net(after2.rows, '4000') - salesBefore2

            // THE assertion. Not "an invoice exists" and not "the GL balanced" — both are true under a
            // design that quietly books financing to the wrong account. The two journals must MATCH.
            expect(planAr, 'AR moves identically to the plain credit sale').to.eq(creditAr)
            expect(planSales, 'Sales moves identically to the plain credit sale').to.eq(creditSales)
            expect(after2.balanced, 'GL still balanced').to.eq(true)

            // And no account the design says it never touches has appeared.
            expect(Number(acct(after2.rows, '4400').debit) + Number(acct(after2.rows, '4400').credit),
              '4400 Finance Income is INST-6 — a zero-markup plan must not touch it').to.eq(0)
          })
        })
      })
    })
  })

  // ── the schedule ──────────────────────────────────────────────────────────────────────────────────────

  it('the schedule sums to the financed amount, to the cent', () => {
    const run = uniq()
    // 50,000 over 3 with 20,000 down = 30,000 financed = 10,000.00 × 3 exactly.
    // The awkward-division rule is proven over 115 combinations in ScheduleGeneratorTest; this proves the
    // arithmetic survives the round trip through the sale path and the database.
    cy.seedProduct({ name: `INSTS_${run}`, sellingPrice: 50000, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: `Sched Buyer ${run}`, contact: `0300S${run}`, paidAmount: 20000, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 50000, totalAmount: 50000, netAmount: 50000 }],
          paidAmount: 20000, dueAmount: 0, grandTotal: 50000,
          installmentPlan: {
            cashPrice: 50000, downPayment: 20000, installmentCount: 3,
            frequency: 'monthly', firstDueDate: monthsOut(1),
          },
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      customerNamed(`Sched Buyer ${run}`).then((c) => {
        cy.request(`/installmentPlans?customerId=${c.customerId || c.id}`).then((pr) => {
          const plans = list(pr.body)
          expect(plans.length, 'the plan is readable').to.be.greaterThan(0)

          const plan = plans[0]
          expect(Number(plan.financedAmount), 'price − down payment').to.eq(30000)
          expect(plan.installments.length).to.eq(3)

          const total = plan.installments.reduce((s, i) => s + Number(i.amount), 0)
          expect(total, 'Σ installments === financed, exactly').to.eq(30000)
          expect(plan.installments.map((i) => i.seqNo)).to.deep.eq([1, 2, 3])
        })
      })
    })
  })

  // ── a receipt lands on the plan ───────────────────────────────────────────────────────────────────────

  it('a receipt of two and a half installments leaves PAID, PAID, PARTIAL with the exact residual', () => {
    const run = uniq()

    cy.seedProduct({ name: `INSTR_${run}`, sellingPrice: 30000, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: `Pay Buyer ${run}`, contact: `0300R${run}`, paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 30000, totalAmount: 30000, netAmount: 30000 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 30000,
          installmentPlan: {
            cashPrice: 30000, downPayment: 0, installmentCount: 3,
            frequency: 'monthly', firstDueDate: monthsOut(1),
          },
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      customerNamed(`Pay Buyer ${run}`).then((c) => {
        const customerId = c.customerId || c.id

        // 25,000 against 3 × 10,000 → 10,000 + 10,000 + 5,000
        cy.request({
          method: 'POST', url: '/receivePayment', form: true,
          body: { customerId, amount: 25000, method: 'CASH' }, failOnStatusCode: false,
        }).then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))

        cy.request(`/installmentPlans?customerId=${customerId}`).then((pr) => {
          const inst = list(pr.body)[0].installments

          expect(inst[0].status).to.eq('PAID')
          expect(inst[1].status).to.eq('PAID')
          expect(inst[2].status, 'the third is part paid').to.eq('PARTIAL')
          expect(Number(inst[2].outstanding), 'the exact residual').to.eq(5000)
          expect(Number(inst[2].paidAmount)).to.eq(5000)
        })
      })
    })
  })

  it('a receipt does NOT over-clear when the plan invoice is also an open invoice', () => {
    // The plan and its invoice describe ONE debt. If the plan invoice were left in the ordinary invoice
    // stream the allocator would be offered the same money twice, and a single payment would clear the
    // balance twice over. This asserts the customer's due falls by the amount paid — no more.
    const run = uniq()

    cy.seedProduct({ name: `INSTO_${run}`, sellingPrice: 30000, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: `Once Buyer ${run}`, contact: `0300O${run}`, paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 30000, totalAmount: 30000, netAmount: 30000 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 30000,
          installmentPlan: {
            cashPrice: 30000, downPayment: 0, installmentCount: 3,
            frequency: 'monthly', firstDueDate: monthsOut(1),
          },
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status).to.eq('SUCCESS'))

      customerNamed(`Once Buyer ${run}`).then((c) => {
        const customerId = c.customerId || c.id
        const dueBefore = Number(c.dueAmount || 0)
        expect(dueBefore, 'the financed amount is owed').to.eq(30000)

        cy.request({
          method: 'POST', url: '/receivePayment', form: true,
          body: { customerId, amount: 10000, method: 'CASH' }, failOnStatusCode: false,
        }).then((p) => expect(p.body.status).to.eq('SUCCESS'))

        customerNamed(`Once Buyer ${run}`).then((after) => {
          expect(Number(after.dueAmount), 'fell by exactly what was paid, not twice')
            .to.eq(dueBefore - 10000)
        })
      })
    })
  })

  // ── refusals ──────────────────────────────────────────────────────────────────────────────────────────

  it('a markup is refused — it is finance income and needs its own account (INST-6)', () => {
    const run = uniq()

    cy.seedProduct({ name: `INSTM_${run}`, sellingPrice: 30000, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: `Markup Buyer ${run}`, contact: `0300M${run}`, paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 30000, totalAmount: 30000, netAmount: 30000 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 30000,
          installmentPlan: {
            cashPrice: 30000, downPayment: 0, installmentCount: 3,
            frequency: 'monthly', firstDueDate: monthsOut(1), markupAmount: 3000,
          },
        }, failOnStatusCode: false,
      }).then((r) => {
        // The SALE stands — the money moved and the customer has the handset. Only the plan is refused,
        // and the refusal is reported rather than swallowed.
        expect(r.body.status, 'the sale is not failed by a refused plan').to.eq('SUCCESS')
        expect(r.body.message, 'and the shopkeeper is told why').to.contain('NOT created')
      })
    })
  })

  // NOTE — there is no case here for "a sale with no customer block at all".
  //
  // The guard in createInstallmentPlan ("a plan needs a named customer") is correct and stays, but it cannot
  // be reached through this API: `addSell` with no `customer` object NPEs first, inside
  // CustomerService.saveUpdateCustomer:252, which dereferences dto.getCustomer() with no null check. That is
  // PRE-EXISTING behaviour — every real client sends the block (main.js assembles customerHistory{customer,
  // sales, tenders}), so nothing has ever exercised it.
  //
  // A gate case asserting a refusal it cannot legitimately trigger would be testing the NPE, not the rule.
  // Recorded in the slice doc as a separate robustness finding rather than smuggled in here.

  it('with the setting OFF, a plan block is refused and no plan is created', () => {
    // A default is not a decision: a shop that never turned this on must not silently acquire plans
    // because a client sent the block. Also restores the OFF default for the rest of the suite.
    const run = uniq()
    setConfig('pos.installment.enabled', 'false')

    cy.seedProduct({ name: `INSTF_${run}`, sellingPrice: 30000, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: `Off Buyer ${run}`, contact: `0300F${run}`, paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 30000, totalAmount: 30000, netAmount: 30000 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 30000,
          installmentPlan: {
            cashPrice: 30000, downPayment: 0, installmentCount: 3,
            frequency: 'monthly', firstDueDate: monthsOut(1),
          },
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        expect(r.body.message).to.contain('switched off')
      })

      customerNamed(`Off Buyer ${run}`).then((c) => {
        cy.request(`/installmentPlans?customerId=${c.customerId || c.id}`).then((pr) => {
          expect(list(pr.body).length, 'no plan was created').to.eq(0)
        })
      })
    })
  })
})
