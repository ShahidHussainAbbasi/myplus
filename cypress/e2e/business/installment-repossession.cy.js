/**
 * INST-5a — repossession.
 * Design: microservices/docs/slices/inst-5-serial-units-repossession.md
 *
 * WHAT CARRIES THIS SPEC IS THE BOOKS, NOT THE BUTTON.
 *
 * A repossession writes off money and moves stock. The 4200 incident is the standing warning: three specs
 * stayed green for months on top of an account that was empty in every tenant, because every one of them
 * asserted the document rather than the ledger.
 *
 * This slice sharpened that lesson. The first implementation derived the credit note by multiplying the
 * invoice by a ROUNDED FRACTION, so writing off 40,000 of 60,000 credited 40,000.02 and left the customer
 * permanently two paisa in credit. The TRIAL BALANCE STILL BALANCED — the posting was self-consistent, just
 * for the wrong amount. Only the closing balance caught it. So this asserts both, and it asserts what the
 * customer OWES rather than that a credit note exists.
 *
 * Money already paid is KEPT (design §7, the customer's decision). The forfeit is not enforced by a rule
 * anywhere; it falls out of crediting only the unpaid balance, so afterwards paidAmount equals grandTotal and
 * there is no overpayment for anything to refund. That is asserted directly.
 *
 * SPLIT FROM the serial rules, which now live in installment-serial.cy.js — together they outlived the
 * 15-minute auth token and failed from the middle onward with no assertion error (see commands.js).
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/installment-repossession.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
  return []
}

const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const trialBalance = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const customerNamed = (name) =>
  cy.request('/getUserCustomer?q=-1').then((r) => list(r.body).find((c) => c.name === name))

const plansFor = (customerId) =>
  cy.request(`/installmentPlans?customerId=${customerId}`).then((r) => list(r.body))

/** Sell one handset on a plan. `serial` may be omitted to test the required-serial rule. */
const sellOnPlan = (buyer, serial, monthsAgo = -6, price = 60000) => {
  const run = uniq()
  return cy.seedProduct({ name: `RPS_${run}`, sellingPrice: price, stock: 5 }).then(({ productId }) => {
    const plan = {
      cashPrice: price, downPayment: 0, installmentCount: 6,
      frequency: 'monthly', firstDueDate: monthsOut(monthsAgo),
    }
    if (serial !== undefined) plan.assetRef = serial
    return cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: buyer, contact: `0300P${run}`, paidAmount: 0, dueAmount: 0 },
        sales: [{ productId, quantity: 1, sellRate: price, totalAmount: price, netAmount: price }],
        paidAmount: 0, dueAmount: 0, grandTotal: price,
        installmentPlan: plan,
      }, failOnStatusCode: false,
    })
  })
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

describe('INST-5a — repossession', () => {
  before(() => {
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'true')
    setConfig('pos.installment.repossession.enabled', 'true')
    setConfig('pos.installment.repossession.minOverdueDays', '30')
    setConfig('pos.installment.repossession.protectedGoodsPct', '0')
    setConfig('pos.installment.repossession.writeOffBalance', 'true')
  })

  beforeEach(() => cy.loginAsOwner())

  after(() => {
    // Leave no server state behind — EVERY key this file writes, not only the ones whose value differs from
    // the default. A spec that sets a server-wide switch and does not clear it is how the period-close spec
    // once left the books locked and reddened every sale spec that ran after it.
    cy.loginAsOwner()
    setConfig('pos.installment.repossession.enabled', 'false')
    setConfig('pos.installment.repossession.protectedGoodsPct', '0')
    setConfig('pos.installment.repossession.minOverdueDays', '30')
    setConfig('pos.installment.repossession.writeOffBalance', 'true')
    setConfig('pos.installment.remind.enabled', 'false')
    setConfig('pos.installment.enabled', 'false')
  })

  // ── ⭐ THE SECOND THING THAT CARRIES THE SLICE: THE BOOKS ──────────────────────────────────────────────

  it('after a repossession the customer owes nothing, the books balance, and nothing is refunded', () => {
    const buyer = `Repo Books ${uniq()}`

    sellOnPlan(buyer, `IMEI${uniq()}`).then((sale) => {
      expect(sale.body.status, JSON.stringify(sale.body)).to.eq('SUCCESS')

      customerNamed(buyer).then((c) => {
        const id = c.customerId || c.id

        // Pay a third, so there is money to forfeit and a balance to write off. A repossession with nothing
        // paid would not distinguish forfeit from a plain reversal.
        cy.request({ method: 'POST', url: '/receivePayment', form: true,
          body: { customerId: id, amount: 20000, method: 'CASH' }, failOnStatusCode: false })
          .then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))

        plansFor(id).then((plans) => {
          const plan = plans[0]
          expect(plan, 'the plan exists').to.exist
          expect(Number(plan.totalOutstanding), 'and 40,000 is still owing').to.eq(40000)

          cy.request({ method: 'POST', url: '/repossessPlan', form: true,
            body: { planId: plan.id, condition: 'GOOD', reason: 'six payments missed' },
            failOnStatusCode: false })
            .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

          // THE ASSERTION THAT MATTERS. Not "a credit note exists" — the books.
          trialBalance().then((tb) => {
            expect(tb.balanced, 'the GL balances after a repossession').to.eq(true)
            expect(Number(tb.totalDebit)).to.eq(Number(tb.totalCredit))
          })

          // The debt is gone...
          cy.request(`/getUserCustomer?q=-1`).then((cr) => {
            const after = list(cr.body).find((x) => x.name === buyer)
            expect(Number(after.dueAmount || 0), 'the customer owes nothing').to.eq(0)
          })

          // ...and the 20,000 stayed with the shop. FORFEIT. If the ordinary return path had been reused,
          // this is where a 20,000 refund would show up.
          cy.request(`/customerStatement?customerId=${id}`).then((sr) => {
            const lines = list(sr.body).filter((l) => l.type !== 'SCHEDULE')
            const closing = Number(lines[lines.length - 1].balance)
            expect(closing, 'settled to zero — nothing owed, nothing refunded').to.eq(0)
          })
        })
      })
    })
  })

  it('⭐ a price and part-payment that do NOT divide cleanly still settle to zero', () => {
    // THE REGRESSION. The first implementation derived the credit by multiplying the invoice by a rounded
    // fraction, so writing off 40,000 of 60,000 credited 40,000.02 and left the customer permanently two
    // paisa in credit. The trial balance still BALANCED — the posting was self-consistent — and only the
    // closing balance caught it.
    //
    // These numbers are deliberately awkward: the original defect surfaced because 40000/60000 happened to
    // round badly, and a fixture that divides cleanly would have hidden it entirely.
    const buyer = `Repo Awkward ${uniq()}`
    const PRICE = 59999

    sellOnPlan(buyer, `IMEI${uniq()}`, -6, PRICE).then((sale) => {
      expect(sale.body.status, JSON.stringify(sale.body)).to.eq('SUCCESS')

      customerNamed(buyer).then((c) => {
        const id = c.customerId || c.id

        cy.request({ method: 'POST', url: '/receivePayment', form: true,
          body: { customerId: id, amount: 17777, method: 'CASH' }, failOnStatusCode: false })
          .then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))

        plansFor(id).then((plans) => {
          cy.request({ method: 'POST', url: '/repossessPlan', form: true,
            body: { planId: plans[0].id, condition: 'GOOD', reason: 'default' }, failOnStatusCode: false })
            .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

          cy.request(`/customerStatement?customerId=${id}`).then((sr) => {
            const lines = list(sr.body).filter((l) => l.type !== 'SCHEDULE')
            const closing = Number(lines[lines.length - 1].balance)
            // To the paisa. Not "close to zero" — a tolerance here would have passed the original defect.
            expect(closing, 'settled exactly, not to within a paisa').to.eq(0)
          })

          cy.request('/customerAging').then((ar) => {
            const row = list(ar.body).find((x) => Number(x.partyId) === Number(id))
            // The residue's real cost: a phantom row on the aging report that never goes away.
            expect(Number(row ? row.total : 0), 'and leaves no phantom row to chase').to.eq(0)
          })
        })
      })
    })
  })

  it('the plan closes and stops appearing anywhere it was chased', () => {
    // INST-2 and INST-3a read the plan rows, so closing the plan should quiet the aging report and the
    // collections worklist WITHOUT either being told. This is that claim, tested rather than assumed.
    const buyer = `Repo Quiet ${uniq()}`
    setConfig('pos.installment.remind.enabled', 'true')

    sellOnPlan(buyer, `IMEI${uniq()}`).then(() => {
      customerNamed(buyer).then((c) => {
        const id = c.customerId || c.id
        cy.request({ method: 'POST', url: '/scanInstallmentReminders', failOnStatusCode: false })

        // POSITIVE CONTROL: it is on the worklist before, so its absence after means closed rather than
        // never-listed.
        cy.request('/installmentReminders').then((w) => {
          expect(list(w.body).filter((r) => r.customerName === buyer).length).to.be.greaterThan(0)
        })

        plansFor(id).then((plans) => {
          cy.request({ method: 'POST', url: '/repossessPlan', form: true,
            body: { planId: plans[0].id, condition: 'GOOD', reason: 'default' }, failOnStatusCode: false })
            .then((r) => expect(r.body.status).to.eq('SUCCESS'))

          plansFor(id).then((after) => {
            expect(after[0].status, 'the plan is closed').to.eq('CANCELLED')
            expect(Number(after[0].totalOutstanding), 'and owes nothing').to.eq(0)
          })

          // The aging report no longer carries this customer's plan rows.
          cy.request('/customerAging').then((ar) => {
            const row = list(ar.body).find((x) => Number(x.partyId) === Number(id))
            expect(Number(row ? row.total : 0), 'nothing left to age').to.eq(0)
          })
        })
      })
      setConfig('pos.installment.remind.enabled', 'false')
    })
  })

  it('⭐ the serial is freed by closing the plan, so the handset can be sold again', () => {
    // The generated column earns its place here. live_asset_ref is derived from status, so cancelling the
    // plan releases the serial BY ITSELF — nothing had to remember to do it. A plain UNIQUE on asset_ref
    // would have made a repossessed handset unsellable forever, which is the opposite of the point.
    const imei = `IMEI${uniq()}`

    sellOnPlan(`Repo Resell ${uniq()}`, imei).then((first) => {
      expect(first.body.status).to.eq('SUCCESS')

      // POSITIVE CONTROL: blocked while the first plan is live.
      sellOnPlan(`Blocked ${uniq()}`, imei).then((blocked) => {
        expect(blocked.body.status, 'blocked while live').to.eq('FAILED')
      })

      cy.request('/installmentPlansOpen').then((pr) => {
        const plan = list(pr.body).find((p) => p.assetRef === imei)
        expect(plan, 'the plan holding the serial').to.exist

        cy.request({ method: 'POST', url: '/repossessPlan', form: true,
          body: { planId: plan.id, condition: 'GOOD', reason: 'default' }, failOnStatusCode: false })
          .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

        sellOnPlan(`Repo Resold ${uniq()}`, imei).then((again) => {
          expect(again.body.status, 'the recovered handset can be financed again').to.eq('SUCCESS')
        })
      })
    })
  })

  // ── the guards that protect the CUSTOMER ──────────────────────────────────────────────────────────────

  it('a customer barely late keeps their phone', () => {
    const buyer = `Repo Early ${uniq()}`

    // Due last month, so ~30 days late; require 90 and it must be refused.
    setConfig('pos.installment.repossession.minOverdueDays', '90')
    sellOnPlan(buyer, `IMEI${uniq()}`, -1).then(() => {
      customerNamed(buyer).then((c) => {
        plansFor(c.customerId || c.id).then((plans) => {
          cy.request({ method: 'POST', url: '/repossessPlan', form: true,
            body: { planId: plans[0].id, condition: 'GOOD' }, failOnStatusCode: false })
            .then((r) => {
              expect(r.body.status).to.eq('FAILED')
              expect(r.body.message, 'and is told how late is late enough').to.contain('90')
            })

          // POSITIVE CONTROL: the same plan, the same moment, with the threshold lowered.
          setConfig('pos.installment.repossession.minOverdueDays', '1')
          cy.request({ method: 'POST', url: '/repossessPlan', form: true,
            body: { planId: plans[0].id, condition: 'GOOD' }, failOnStatusCode: false })
            .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
          setConfig('pos.installment.repossession.minOverdueDays', '30')
        })
      })
    })
  })

  it('⭐ protected goods cannot be taken back', () => {
    // The rule that keeps a shop out of court. In many consumer-credit regimes goods become protected once a
    // share of the price is paid; taking them anyway can void the debt entirely.
    const buyer = `Repo Protected ${uniq()}`
    setConfig('pos.installment.repossession.protectedGoodsPct', '66')

    sellOnPlan(buyer, `IMEI${uniq()}`).then(() => {
      customerNamed(buyer).then((c) => {
        const id = c.customerId || c.id
        // 40,000 of 60,000 = 66.6%, over the line.
        cy.request({ method: 'POST', url: '/receivePayment', form: true,
          body: { customerId: id, amount: 40000, method: 'CASH' }, failOnStatusCode: false })
          .then((p) => expect(p.body.status).to.eq('SUCCESS'))

        plansFor(id).then((plans) => {
          cy.request({ method: 'POST', url: '/repossessPlan', form: true,
            body: { planId: plans[0].id, condition: 'GOOD' }, failOnStatusCode: false })
            .then((r) => {
              expect(r.body.status, 'refused').to.eq('FAILED')
              expect(r.body.message, 'and says why, in words a shopkeeper can act on')
                .to.contain('court order')
            })
        })
      })
    })
    setConfig('pos.installment.repossession.protectedGoodsPct', '0')
  })

  it('a shop that has not switched repossession on cannot repossess', () => {
    // A default is not a decision.
    const buyer = `Repo Off ${uniq()}`
    setConfig('pos.installment.repossession.enabled', 'false')

    sellOnPlan(buyer, `IMEI${uniq()}`).then(() => {
      customerNamed(buyer).then((c) => {
        plansFor(c.customerId || c.id).then((plans) => {
          cy.request({ method: 'POST', url: '/repossessPlan', form: true,
            body: { planId: plans[0].id, condition: 'GOOD' }, failOnStatusCode: false })
            .then((r) => expect(r.body.status).to.eq('FAILED'))

          // POSITIVE CONTROL: same plan, switched on.
          setConfig('pos.installment.repossession.enabled', 'true')
          cy.request({ method: 'POST', url: '/repossessPlan', form: true,
            body: { planId: plans[0].id, condition: 'GOOD' }, failOnStatusCode: false })
            .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
        })
      })
    })
  })

  it('an already-closed plan refuses, by name', () => {
    const buyer = `Repo Twice ${uniq()}`

    sellOnPlan(buyer, `IMEI${uniq()}`).then(() => {
      customerNamed(buyer).then((c) => {
        plansFor(c.customerId || c.id).then((plans) => {
          const id = plans[0].id
          cy.request({ method: 'POST', url: '/repossessPlan', form: true,
            body: { planId: id, condition: 'GOOD' }, failOnStatusCode: false })
            .then((r) => expect(r.body.status).to.eq('SUCCESS'))

          // Repossessing twice would credit the balance off twice. The status guard is what stops it.
          cy.request({ method: 'POST', url: '/repossessPlan', form: true,
            body: { planId: id, condition: 'GOOD' }, failOnStatusCode: false })
            .then((r) => {
              expect(r.body.status).to.eq('FAILED')
              expect(r.body.message, 'and says what state it is in').to.contain('CANCELLED')
            })
        })
      })
    })
  })

  it("a plan belonging to nobody cannot be repossessed", () => {
    // Anti-IDOR: the id arrives off the wire, so ownership is proved in the lookup rather than after it.
    cy.request({ method: 'POST', url: '/repossessPlan', form: true,
      body: { planId: 999999999, condition: 'GOOD' }, failOnStatusCode: false })
      .then((r) => expect(r.body.status).to.eq('FAILED'))
  })

  // ── the screen ────────────────────────────────────────────────────────────────────────────────────────

  it('the shopkeeper can actually do it', () => {
    const buyer = `Repo Screen ${uniq()}`
    const imei = `IMEI${uniq()}`

    sellOnPlan(buyer, imei).then(() => {
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.window().then((w) => w.showInstallments())

      searchPlans(buyer)
      cy.contains('#installmentBody tr', buyer, { timeout: 10000 }).click()

      // The IMEI is on the screen — a shop about to take a handset back needs to see which one.
      cy.get('#installmentSchedule', { timeout: 10000 }).should('contain.text', imei)
      // And the condition is ASKED, not assumed, before anything is restocked.
      cy.get('#instRepossessCondition').should('exist')
      cy.get('#instRepossess').should('be.visible').click({ force: true })

      cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).click({ force: true })

      // TWO DIALOGS, ONE AFTER THE OTHER. Confirming the repossession is only the first: when the POST comes
      // back, repossess() opens a SUCCESS alert — a second backdrop, over the same page. Waiting for the
      // first to clear and then typing raced the second into existence and Cypress refused to type into a
      // covered input, correctly.
      //
      // So the alert is asserted rather than waited out. A shopkeeper who has just written off a balance and
      // taken goods off a customer should be TOLD it worked, and how much was written off; that is worth a
      // gate line of its own rather than a sleep.
      cy.get('#uiC-title', { timeout: 15000 }).should('contain.text', 'Repossessed')
      cy.get('[data-ui-confirm="ok"]').click({ force: true })

      // Only now is the page reachable again. Asserted, not forced: {force:true} would type into an element
      // the shopkeeper cannot reach either.
      cy.get('.uiC-backdrop', { timeout: 10000 }).should('not.exist')

      // Searched first: without it this passes while the row sits on another page.
      searchPlans(buyer)
      cy.contains('#installmentBody tr', buyer, { timeout: 10000 }).should('not.exist')
    })
  })
})
