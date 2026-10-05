/**
 * FP-6a — the automatic payables reconciliation: every tenant, every day and after every deploy, with no operator.
 *
 * Design: microservices/docs/slices/fp-6-retire-business-source.md. User ruling 2026-10-04: everything fixed
 * automatically, no manual step, nothing allowed to go wrong on production; 28 clean days retire business as the source.
 *
 * The run is triggered here through the operator's "check now" (the same call the daily job makes) so the gate does
 * not wait for the night. The dirt is MADE here, deterministically: a manual journal that moves GL 2000 by 77 with no
 * supplier document behind it — exactly the kind of difference the review found (a 10x purchase journal, tax-only
 * postings) — on owner.payables, the tenant reserved for payables gates.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const PAYABLES = 'owner.payables@myplus.com'

const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })
const recon = (t) => cy.request({ url: `${GW}/api/finance/payables/reconciliation`, headers: hdr(t) }).its('body')
const tb = (t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).its('body')
const bal = (b, code) => (b.rows || []).filter((r) => r.code === code).reduce((s, r) => s + Number(r.credit || 0) - Number(r.debit || 0), 0)
const run = (org) => cy.request({ method: 'POST', url: '/platform/payablesReconciliation/run', form: true, body: { organizationId: org } })
  .its('body').then((b) => { expect(b.status, JSON.stringify(b.message)).to.eq('SUCCESS'); return b.object || b.data })
const history = (org) => cy.request({ url: '/platform/payablesReconciliation', qs: { organizationId: org } })
  .its('body').then((b) => { expect(b.status).to.eq('SUCCESS'); return b.object || b.data })
const round2 = (n) => Math.round(n * 100) / 100

describe('FP-6a — the automatic payables reconciliation', () => {
  let org = null

  before(() => {
    cy.loginAsOperator()
    cy.orgOf(PAYABLES).then((o) => { org = o.id })
    cy.then(() => run(org))   // settle whatever earlier gates left, so the cases below start from a known state
  })

  it('1 — a check records today, measures business ⇄ finance ⇄ GL, and leaves GL 2000 equal to the supplier ledger', () => {
    cy.loginAsOperator()
    cy.then(() => run(org)).then((d) => {
      expect(d.reconDay, 'today is recorded').to.match(/^\d{4}-\d{2}-\d{2}$/)
      expect(Number(d.shadowDiff), 'business purchases = finance documents').to.eq(0)
    })
    token(PAYABLES).then((t) => recon(t).then((r) => expect(Number(r.difference), 'GL 2000 − ledger').to.eq(0)))
  })

  it('⭐ 2 — GL 2000 moved by 77 with no document → the check aligns it ONCE against 2990; the books still balance', () => {
    token(PAYABLES).then((t) => {
      tb(t).then((before) => {
        // the dirt: Dr 6900 / Cr 2000 77 — Payables up by 77 that no supplier document explains
        cy.request({ method: 'POST', url: `${GW}/api/finance/gl/journal`, headers: hdr(t),
          body: { source: 'MANUAL', memo: 'FP-6a gate: a GL-only payables difference', lines: [
            { accountCode: '6900', debit: 77 }, { accountCode: '2000', credit: 77 }] } }).its('status').should('be.oneOf', [200, 201])
        recon(t).then((r) => expect(Number(r.difference), 'the planted difference').to.eq(77))
        cy.loginAsOperator()
        cy.then(() => run(org)).then((d) => {
          expect(d.clean, 'a day that needed a repair is not clean').to.eq(false)
          expect(Number(d.ledgerAligned), 'aligned today (this run adds 77)').to.be.gte(77)
        })
        recon(t).then((r) => expect(Number(r.difference), 'GL 2000 equals the supplier ledger again').to.eq(0))
        tb(t).then((after) => {
          expect(after.balanced, 'trial balance balanced').to.eq(true)
          expect(round2(bal(after, '2000') - bal(before, '2000')), '2000 net: +77 dirt −77 alignment').to.eq(0)
          expect(round2(bal(after, '2990') - bal(before, '2990')), '2990 carries the explained difference').to.eq(77)
        })
      })
    })
  })

  it('3 — checking again posts nothing more (idempotent per ledger state)', () => {
    token(PAYABLES).then((t) => {
      tb(t).then((before) => {
        cy.loginAsOperator()
        cy.then(() => run(org))
        tb(t).then((after) => {
          expect(round2(bal(after, '2990') - bal(before, '2990')), 'no second alignment').to.eq(0)
          expect(round2(bal(after, '2000') - bal(before, '2000'))).to.eq(0)
        })
      })
    })
  })

  it('4 — the history shows today as repaired; the clean streak restarts at 0; 28 are required', () => {
    cy.loginAsOperator()
    cy.then(() => history(org)).then((h) => {
      expect(h.required).to.eq(28)
      expect(h.cleanStreak, 'a repaired day ends the streak').to.eq(0)
      const today = (h.days || [])[0]
      expect(today, "today's row").to.exist
      expect(today.clean).to.eq(false)
      expect(Number(today.ledgerAligned)).to.be.gte(77)
    })
  })

  it('5 — the operator sees it on the console: "Automatic check (daily)" with today repaired', () => {
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.get('[data-testid="tenant-row"]', { timeout: 20000 }).should('have.length.greaterThan', 0)
    cy.get('#platSearch').clear().type("Owner Payables's organization")
    cy.contains('[data-testid="tenant-row"]', "Owner Payables's organization", { timeout: 15000 }).click()
    cy.get('[data-cy=plat-payables-recon]', { timeout: 20000 }).should('be.visible')
    cy.get('[data-cy=plat-payables-recon-streak]').should('contain', '0')
    cy.get('[data-cy=plat-payables-recon-day]').first().should('contain', 'repaired')
  })

  it('6 — a shop owner cannot read or run it (operator only)', () => {
    cy.loginAs(PAYABLES, PW, '/getBusinessDashboardStats', 'fp6a-' + Date.now())
    cy.request({ url: '/platform/payablesReconciliation', qs: { organizationId: org || 0 }, failOnStatusCode: false })
      .its('status').should('eq', 403)
    cy.request({ method: 'POST', url: '/platform/payablesReconciliation/run', form: true, body: { organizationId: org || 0 }, failOnStatusCode: false })
      .its('status').should('eq', 403)
  })
})
