/**
 * EX-2d — the expense list is paged, says where you are, and shows what the period adds up to (E4).
 *
 * Design: microservices/docs/slices/ex-2d-list-paging-and-total.md §4.
 * Runs on the SCHOOL tenant (owner.education) so it never disturbs the business specs. Every number the screen shows
 * is checked against the API's own answer, never against a constant: other specs add expenses to the same day.
 * Clean-up voids what this spec recorded (posted vouchers are voidable; the void reverses the books).
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const KEY = 'org.cap.expenseManagement'
const OWNER = 'owner.education@myplus.com', USER = 'user.education@myplus.com'
const run = String(Date.now()).slice(-6)
const N = 55                                    // more than a page (50)

const pad = (n) => String(n).padStart(2, '0')
const today = new Date()
const ISO = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`
const DMY = `${pad(today.getDate())}-${pad(today.getMonth() + 1)}-${today.getFullYear()}`

const signIn = (email, fresh) => cy.loginAs(email, PW, '/getDashboardData', fresh ? 'ex2d-' + Date.now() : undefined)
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })
const rentOf = (t) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).then((r) => (r.body.data || []).find((c) => c.accountCode === '6000'))
const record = (t, catId, amount, i) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`,
  headers: { ...hdr(t), 'Idempotency-Key': `ex2d-${run}-${i}` },
  body: { voucherDate: ISO, paidFrom: 'CASH', payeeName: `EX2D ${run} #${i}`, lines: [{ categoryId: catId, amount }] } }).its('body.data')
const totalsOf = (t) => cy.request({ url: `${GW}/api/expense/vouchers/totals?from=${ISO}&to=${ISO}`, headers: hdr(t) }).its('body.data')
const pageOf = (t, page, size = 50) => cy.request({ url: `${GW}/api/expense/vouchers?from=${ISO}&to=${ISO}&page=${page}&size=${size}`, headers: hdr(t) }).its('body.data')
/** Every row the filter holds, page by page (200 a page) — the gate never assumes one page is enough. */
const allOf = (t, page = 0, acc = []) => pageOf(t, page, 200).then((p) => {
  const rows = acc.concat(p.content)
  return p.last ? rows : allOf(t, page + 1, rows)
})
const money = (n) => Number(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

const openExpenses = () => {
  cy.visit('/educationDashboard'); cy.waitForAppReady()
  cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
  cy.get('#navExpenses').should('be.visible').click()
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}
const filterToday = () => {
  cy.get('#expFromTemp').clear().type(DMY).blur()
  cy.get('#expToTemp').clear().type(DMY).blur()
  cy.contains('#ExpenseDiv button', 'Search').click()
}

describe('EX-2d — the expense list: pages, position and the period total', () => {
  const mine = []

  before(() => {
    signIn(OWNER)
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: KEY, enabled: 'true' } }).its('body.success').should('eq', true)
    token(OWNER).then((t) => rentOf(t).then((c) => {
      for (let i = 1; i <= N; i++) record(t, c.id, i, i).then((v) => mine.push(v.id))
    }))
  })

  after(() => {
    token(OWNER).then((t) => mine.forEach((id) =>
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`, headers: hdr(t), body: { reason: 'EX-2d gate clean-up' }, failOnStatusCode: false })))
    signIn(OWNER)
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: KEY }, failOnStatusCode: false })
  })

  it('⭐ 1 — more than a page: "Showing 1–50 of N", Next shows the rest, Previous comes back', () => {
    token(OWNER).then((t) => pageOf(t, 0).then((p0) => {
      expect(p0.totalElements, 'the filter holds more than a page').to.be.greaterThan(50)
      signIn(OWNER, true)
      openExpenses()
      filterToday()
      cy.get('#tableExpense tbody tr').should('have.length', 50)
      cy.get('[data-cy=expense-showing]').should('have.text', `Showing 1–50 of ${p0.totalElements}`)
      cy.get('[data-cy=expense-prev]').should('be.disabled')
      cy.get('[data-cy=expense-next]').should('be.enabled').click()
      const rest = Math.min(50, p0.totalElements - 50)
      cy.get('[data-cy=expense-showing]').should('have.text', `Showing 51–${50 + rest} of ${p0.totalElements}`)
      cy.get('#tableExpense tbody tr').should('have.length', rest)
      pageOf(t, 1).then((p1) => cy.get('#tableExpense tbody tr').first().should('have.attr', 'data-id', String(p1.content[0].id)))
      cy.get('[data-cy=expense-prev]').should('be.enabled').click()
      cy.get('[data-cy=expense-showing]').should('have.text', `Showing 1–50 of ${p0.totalElements}`)
      cy.get('#tableExpense tbody tr').first().should('have.attr', 'data-id', String(p0.content[0].id))
    }))
  })

  it('⭐ 2 — the total is the whole filter, not the page: it equals the sum of every posted row on every page', () => {
    token(OWNER).then((t) => totalsOf(t).then((tot) => allOf(t).then((all) => {
      const posted = all.filter((v) => v.status === 'POSTED')
      expect(tot.count, 'count of posted').to.eq(posted.length)
      expect(Number(tot.total), 'sum of posted').to.be.closeTo(posted.reduce((s, v) => s + Number(v.total), 0), 0.001)
      signIn(OWNER, true)
      openExpenses()
      filterToday()
      cy.get('[data-cy=expense-total]').should('have.text', `Total spent: ${money(tot.total)} (${tot.count} expenses; voided ones not counted)`)
    })))
  })

  it('3 — voiding a row on page 2 keeps you on page 2 and takes it out of the total', () => {
    token(OWNER).then((t) => totalsOf(t).then((before) => {
      const id = mine[0]                                     // the oldest of ours → on the last page
      signIn(OWNER, true)
      openExpenses()
      filterToday()
      cy.get('[data-cy=expense-next]').click()
      cy.get('[data-cy=expense-showing]').should('contain', 'Showing 51–')
      cy.get(`#tableExpense tbody tr[data-id="${id}"] [data-cy=void-expense]`).click()
      cy.get('.uiC-input').type('EX-2d gate')
      cy.get('[data-ui-confirm="ok"]').click()
      cy.get(`#tableExpense tbody tr[data-id="${id}"] .exp-chip`, { timeout: 20000 }).should('contain', 'Void')
      cy.get('[data-cy=expense-showing]').should('contain', 'Showing 51–')
      cy.get('[data-cy=expense-total]').should('have.text',
        `Total spent: ${money(Number(before.total) - 1)} (${before.count - 1} expenses; voided ones not counted)`)
    }))
  })

  it('4 — a typed date filters (an impossible one does not); an empty period says so, with no footer', () => {
    signIn(OWNER, true)
    openExpenses()
    // A TYPED date reaches the filter (before EX-2d only a calendar click did — the typed one was silently ignored);
    // an impossible date is not quietly read as another day.
    cy.get('#expFromTemp').clear().type('31-02-2001').blur()
    cy.get('#expFrom').invoke('val').should('not.eq', '2001-03-03')
    cy.get('#expFromTemp').clear().type('01-01-2001').blur()
    cy.get('#expFrom').should('have.value', '2001-01-01')
    cy.get('#expToTemp').clear().type('02-01-2001').blur()
    cy.get('#expTo').should('have.value', '2001-01-02')
    cy.contains('#ExpenseDiv button', 'Search').click()
    cy.contains('#tableExpense tbody', 'No expenses in this period.').should('be.visible')
    cy.get('[data-cy=expense-pager]').should('not.be.visible')
  })

  it('⭐ 5 — security: a user\'s total covers only their own expenses, like their list', () => {
    token(USER).then((u) => rentOf(u).then((c) => record(u, c.id, 3, 'user').then((v) => {
      mine.push(v.id)
      totalsOf(u).then((tot) => allOf(u).then((list) => {
        const posted = list.filter((x) => x.status === 'POSTED')
        expect(tot.count, 'user count = their own posted rows').to.eq(posted.length)
        expect(Number(tot.total)).to.be.closeTo(posted.reduce((s, x) => s + Number(x.total), 0), 0.001)
        token(OWNER).then((o) => totalsOf(o).its('count').should('be.greaterThan', tot.count))
      }))
    })))
    // and the monolith relays the same scope
    cy.loginAs(USER, PW, '/getDashboardData', 'ex2d-u-' + Date.now())
    cy.request(`/expense/vouchers/totals?from=${ISO}&to=${ISO}`).its('body.success').should('eq', true)
  })
})
