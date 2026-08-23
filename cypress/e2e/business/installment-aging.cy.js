/**
 * INST-2 — a financed invoice ages by its SCHEDULE, not as one lump.
 * Design: microservices/docs/installment-dues-reminders-design.md (D10)
 *
 * WHY THIS HAS ITS OWN GATE RATHER THAN RIDING ALONG WITH INST-1.
 *
 * This is the quietest risk in the whole feature. Nothing errors, no test fails, no exception is logged — the
 * number on the aging report is simply wrong. Aged as a single row, a six-month plan puts its ENTIRE remaining
 * balance in whichever bucket the sale date falls into, so by month four a customer who has paid every
 * installment on time reads as a 90+ delinquent.
 *
 * That is wrong in the direction that costs a shop money in both directions: it chases good customers, and it
 * hides the ones who owe a single late payment inside a balance that looks uniformly bad.
 *
 * `AgingCalculator` is NOT changed by this slice — its arithmetic was already right, including counting a
 * future-dated row as current. What changes is the ROW SUPPLIER. These cases assert the supplier.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/installment-aging.cy.js --headed --no-exit
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

/** ISO date n months from today, from LOCAL components — toISOString() is UTC and shifts the day at +05:00. */
const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const customerNamed = (name) =>
  cy.request('/getUserCustomer?q=-1').then((r) => list(r.body).find((c) => c.name === name))

/** This customer's row on the AR aging report. */
const agingFor = (customerId) =>
  cy.request('/customerAging').then((r) =>
    list(r.body).find((row) => Number(row.partyId) === Number(customerId)))

describe('INST-2 — a plan ages by its schedule', () => {
  before(() => {
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'true')

    // ⚠ ESTABLISH the state this spec needs; do not inherit it. Every sale here omits a serial, so a tenant
    // left with serialRequired ON refuses all of them. That is not hypothetical — installment-serial.cy.js
    // turns the setting on and clears it in after(), and when an auth token expired mid-run that hook failed
    // with the rest of the file and left the switch on. installment-plan.cy.js then dropped to 1/6 for a
    // reason nothing inside it could explain.
    //
    // Cleanup is courtesy; SETUP is correctness.
    setConfig('pos.installment.serialRequired', 'false')
  })

  beforeEach(() => {
    cy.loginAsOwner()
  })

  after(() => {
    // Leave no server state behind — a setting left ON changes the sale screen for every later spec.
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'false')
  })

  // ── ⭐ THE CASE THAT CARRIES THE SLICE ─────────────────────────────────────────────────────────────────

  it('a plan with ONE late installment ages only THAT installment — not the whole balance', () => {
    const run = uniq()
    const name = `Aging Buyer ${run}`
    // Six monthly payments of 10,000, the first due FOUR MONTHS AGO. That makes four of them late and two
    // not yet due (#5 falls on today, which is not yet overdue; #6 is next month).
    //
    // Back-dated deliberately rather than waiting four months: the schedule anchors on firstDueDate, so a
    // plan starting in the past reaches exactly the state a real plan is in at month four — which is the
    // month the design names as the point the old behaviour goes visibly wrong.
    const PRICE = 60000

    cy.seedProduct({ name: `AGP_${run}`, sellingPrice: PRICE, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name, contact: `0300A${run}`, paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: PRICE, totalAmount: PRICE, netAmount: PRICE }],
          paidAmount: 0, dueAmount: 0, grandTotal: PRICE,
          installmentPlan: {
            cashPrice: PRICE, downPayment: 0, installmentCount: 6,
            frequency: 'monthly', firstDueDate: monthsOut(-4),
          },
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        expect(r.body.message, 'the plan exists — otherwise this tests nothing').to.contain('PLN-')
      })

      customerNamed(name).then((c) => {
        const customerId = c.customerId || c.id

        agingFor(customerId).then((row) => {
          expect(row, 'the customer appears on the aging report').to.exist

          const buckets = [Number(row.b0_30), Number(row.b31_60), Number(row.b61_90), Number(row.b90plus)]
          const total = Number(row.total)

          // The total is unchanged — this slice moves money BETWEEN buckets; it invents and loses none.
          expect(total, 'the whole balance is still reported').to.eq(60000)

          // ⭐ THE assertion, and it is deliberately about the SPREAD rather than exact figures.
          //
          // Under the old single-row behaviour the entire 60,000 sat in exactly ONE bucket, aged from the
          // sale date. Aged by schedule it must land in several, because the installments have different
          // due dates. "More than one bucket is non-zero" is precisely the difference between the two
          // behaviours, and unlike an exact distribution it does not depend on which day the suite runs.
          const nonEmpty = buckets.filter((b) => b > 0).length
          expect(nonEmpty, 'aged by schedule, the balance spreads across buckets — not one lump')
            .to.be.greaterThan(1)

          // The two not-yet-due installments are ALWAYS current, whatever day this runs: #5 falls on today
          // (age 0) and #6 is next month (bucketize counts a future date as current).
          expect(buckets[0], 'the not-yet-due installments are CURRENT').to.be.at.least(20000)

          // And the balance is not dumped in 90+, which is the specific failure the design names.
          expect(buckets[3], 'the whole balance is NOT in 90+').to.be.lessThan(60000)

          // Exact bucket edges are deliberately NOT asserted: an installment three months old sits within a
          // day or two of the 90-day boundary, so pinning it would make this spec fail on a calendar rather
          // than on a defect.
        })
      })
    })
  })

  it('an ordinary credit invoice still ages exactly as before', () => {
    // The negative control. If plan-awareness had leaked into the general path, this is what would move —
    // and a slice that changes behaviour it was not asked to change is the harder kind of regression to spot.
    const run = uniq()
    const name = `Plain Buyer ${run}`

    cy.seedProduct({ name: `AGX_${run}`, sellingPrice: 5000, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name, contact: `0300X${run}`, paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 5000, totalAmount: 5000, netAmount: 5000 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 5000,
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status).to.eq('SUCCESS'))

      customerNamed(name).then((c) => {
        agingFor(c.customerId || c.id).then((row) => {
          expect(row, 'the customer is on the report').to.exist
          // Sold today with no plan: one row, aged from today, entirely current.
          expect(Number(row.b0_30), 'a sale made today is current').to.eq(5000)
          expect(Number(row.total)).to.eq(5000)
        })
      })
    })
  })

  it('a paid installment leaves the aging report entirely', () => {
    // outstanding() is half the overdue predicate. A settled installment must contribute nothing, however
    // old it is — otherwise a shop chases money it has already received.
    const run = uniq()
    const name = `Paid Buyer ${run}`

    cy.seedProduct({ name: `AGD_${run}`, sellingPrice: 30000, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name, contact: `0300D${run}`, paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 30000, totalAmount: 30000, netAmount: 30000 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 30000,
          installmentPlan: {
            cashPrice: 30000, downPayment: 0, installmentCount: 3,
            frequency: 'monthly', firstDueDate: monthsOut(-3),
          },
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status).to.eq('SUCCESS'))

      customerNamed(name).then((c) => {
        const customerId = c.customerId || c.id

        agingFor(customerId).then((before) => {
          const totalBefore = Number(before.total)
          expect(totalBefore, 'all three installments are owed').to.eq(30000)

          // Settle the first one exactly.
          cy.request({
            method: 'POST', url: '/receivePayment', form: true,
            body: { customerId, amount: 10000, method: 'CASH' }, failOnStatusCode: false,
          }).then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))

          agingFor(customerId).then((after) => {
            expect(Number(after.total), 'the settled installment is gone from the report')
              .to.eq(totalBefore - 10000)
          })
        })
      })
    })
  })
})
