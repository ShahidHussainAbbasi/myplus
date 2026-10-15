/**
 * EX-5 — receipts: kept on the server, photos made smaller on the device, the same receipt twice warned about, the
 * owner's "required above" rule enforced at save, and a receipt read only through its own expense.
 *
 * Design: microservices/docs/slices/ex-5-receipts.md §4. School tenant (owner.education, user.education).
 * Fixtures: receipt-photo.jpg (2400×1800 JPEG), receipt-invoice.pdf, not-a-receipt.jpg (HTML named .jpg).
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const KEY = 'org.cap.expenseManagement'
const OWNER = 'owner.education@myplus.com', USER = 'user.education@myplus.com', OTHER = 'owner.welfare@myplus.com'
const RCPT = 'expense.receipt.requiredAbove'
const run = String(Date.now()).slice(-6)

const signIn = (email, fresh) => cy.loginAs(email, PW, '/getDashboardData', fresh ? 'ex5-' + Date.now() : undefined)
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t) => ({ Authorization: `Bearer ${t}` })
const openExpenses = () => {
  cy.visit('/educationDashboard'); cy.waitForAppReady()
  cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
  cy.get('#navExpenses').should('be.visible').click()
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}
const fill = (amount, payee, file) => {
  cy.get('#expCategory option').contains('Rent').then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
  cy.get('#expAmount').clear().type(String(amount))
  cy.get('#expPaidFrom').select('CASH', { force: true })
  cy.get('#expPayee').clear().type(payee)
  if (file) cy.get('#expReceipt').selectFile(`cypress/fixtures/${file}`, { force: true })
}
const row = (payee) => cy.contains('#tableExpense tbody tr.expense-row', payee, { timeout: 25000 })
const voucherOf = (payee) => cy.request('/expense/vouchers?size=50').its('body.data.content').then((l) => l.find((v) => v.payeeName === payee))

describe('EX-5 — receipts', () => {
  const made = []

  before(() => {
    signIn(OWNER)
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: KEY, enabled: 'true' } }).its('body.success').should('eq', true)
    token(OWNER).then((t) => {
      cy.request({ method: 'POST', url: `${GW}/api/expense/settings/reset?key=${RCPT}`, headers: hdr(t), failOnStatusCode: false })
      // a run that stopped half-way leaves its expenses (and their receipts) standing: void them, so this run's
      // duplicate warnings are about this run only (a voided expense's receipt is not counted as a duplicate)
      const sweep = (page = 0) => cy.request({ url: `${GW}/api/expense/vouchers?page=${page}&size=200`, headers: hdr(t) }).its('body.data').then((d) => {
        d.content.filter((v) => /^EX5 /.test(v.payeeName || '') && v.status === 'POSTED').forEach((v) =>
          cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: { ...hdr(t), 'Content-Type': 'application/json' },
            body: { reason: 'EX-5 gate: leftover of an earlier run' }, failOnStatusCode: false }))
        if (!d.last) sweep(page + 1)
      })
      sweep()
    })
  })

  after(() => {
    token(OWNER).then((t) => {
      cy.request({ method: 'POST', url: `${GW}/api/expense/settings/reset?key=${RCPT}`, headers: hdr(t), failOnStatusCode: false })
      made.forEach((id) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`, headers: { ...hdr(t), 'Content-Type': 'application/json' },
        body: { reason: 'EX-5 gate' }, failOnStatusCode: false }))
    })
    signIn(OWNER)
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: KEY }, failOnStatusCode: false })
  })

  it('⭐ 1 — a photo saved with the expense is kept on the server, made smaller on the device, and opens from the row', () => {
    const payee = `EX5 photo ${run}`
    signIn(OWNER, true)
    openExpenses()
    fill(55, payee, 'receipt-photo.jpg')
    cy.get('[data-cy=save-expense]').click()
    row(payee).find('[data-cy=expense-receipts]').should('contain', 'Receipts (1)')
    voucherOf(payee).then((v) => {
      made.push(v.id)
      expect(v.receipts, 'one receipt on the expense').to.eq(1)
      cy.request(`/expense/vouchers/${v.id}/receipts`).its('body.data.0').then((rc) => {
        expect(rc.contentType).to.eq('image/jpeg')
        expect(rc.size, 'made smaller than the 126 KB original').to.be.lessThan(126185)
        cy.request({ url: `/expense/receipts/${rc.id}/content`, encoding: 'binary' }).then((r) => {
          expect(r.headers['content-type']).to.contain('image/jpeg')
          expect(r.headers['x-content-type-options']).to.eq('nosniff')
          expect(r.headers['cache-control']).to.contain('no-store')
          expect(r.body.charCodeAt(0)).to.eq(0xFF)              // a real JPEG
          expect(r.body.charCodeAt(1)).to.eq(0xD8)
        })
      })
    })
    row(payee).find('[data-cy=expense-receipts]').click()
    cy.get('[data-cy=receipt-row]').should('have.length', 1).find('[data-cy=receipt-view]').should('have.attr', 'href').and('contain', '/expense/receipts/')
  })

  it('⭐ 2 — the same receipt on a second expense is warned about by number; Cancel saves nothing, OK saves it', () => {
    const first = `EX5 pdf ${run}`, second = `EX5 pdf again ${run}`
    signIn(OWNER, true)
    openExpenses()
    fill(120, first, 'receipt-invoice.pdf')
    cy.get('[data-cy=save-expense]').click()
    row(first).find('[data-cy=expense-receipts]').should('contain', 'Receipts (1)')
    voucherOf(first).then((v) => {
      made.push(v.id)
      fill(120, second, 'receipt-invoice.pdf')
      cy.get('[data-cy=save-expense]').click()
      cy.get('.uiC-card').should('contain', v.voucherNo)
      cy.get('.uiC-cancel').click()
      cy.wait(1500)
      cy.contains('#tableExpense tbody tr.expense-row', second).should('not.exist')
      cy.get('[data-cy=save-expense]').click()
      cy.get('[data-ui-confirm="ok"]').click()
      row(second).find('[data-cy=expense-receipts]').should('contain', 'Receipts (1)').click()
      cy.get('[data-cy=receipt-also-on]').should('contain', v.voucherNo)
      voucherOf(second).then((v2) => made.push(v2.id))
    })
  })

  it('⭐ 3 — the owner requires a receipt above 100: 150 without one is refused in words, with one it saves', () => {
    const payee = `EX5 required ${run}`
    signIn(OWNER, true)
    openExpenses()
    cy.get('[data-cy=expense-settings-open]').click()
    cy.get('[data-cy=set-receipt-above]').clear().type('100')
    cy.get('[data-cy=set-receipt-above-save]').click()
    cy.get('#expSetMsg').should('contain', 'Setting saved')
    cy.get('#expReceiptHint').should('contain', 'Required for an expense above')
    fill(150, payee)
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg').should('contain', 'A receipt is required for an expense above 100')
    cy.contains('#tableExpense tbody tr.expense-row', payee).should('not.exist')
    cy.get('#expReceipt').selectFile('cypress/fixtures/receipt-photo.jpg', { force: true })
    cy.get('[data-cy=save-expense]').click()
    // the same photo as case 1 (still on that expense): the duplicate warning comes once it is uploaded
    cy.get('.uiC-card', { timeout: 20000 }).should('contain', 'already on another expense')
    cy.get('[data-ui-confirm="ok"]').click()
    row(payee).find('[data-cy=expense-receipts]').should('contain', 'Receipts (1)')
    voucherOf(payee).then((v) => made.push(v.id))
  })

  it('4 — a file that is not a receipt is refused: on the device, and by the server whatever its name says', () => {
    signIn(OWNER, true)
    openExpenses()
    fill(9, `EX5 fake ${run}`, 'not-a-receipt.jpg')
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg').should('contain', 'A receipt must be a photo')
    cy.fixture('not-a-receipt.jpg', 'binary').then((txt) => {
      const fd = new FormData()
      fd.append('file', new Blob([txt], { type: 'image/jpeg' }), 'fake.jpg')
      token(OWNER).then((t) => cy.request({ method: 'POST', url: `${GW}/api/expense/receipts`, headers: hdr(t), body: fd, failOnStatusCode: false })
        .then((r) => {
          const body = JSON.parse(new TextDecoder().decode(r.body))
          expect(body.success).to.eq(false)
          expect(body.message).to.contain('photo (JPEG, PNG or WEBP) or a PDF')
        }))
    })
  })

  it('5 — a receipt added to an expense already saved', () => {
    const payee = `EX5 later ${run}`
    signIn(OWNER, true)
    openExpenses()
    fill(7, payee)
    cy.get('[data-cy=save-expense]').click()
    row(payee).find('[data-cy=expense-receipts]').should('contain', 'Add a receipt').click()
    cy.get('[data-cy=receipt-add]').selectFile('cypress/fixtures/receipt-invoice.pdf', { force: true })
    row(payee).find('[data-cy=expense-receipts]', { timeout: 20000 }).should('contain', 'Receipts (1)')
    voucherOf(payee).then((v) => made.push(v.id))
  })

  it('⭐ 6 — security: a colleague and another business cannot open it; a user cannot remove it; removed, it is gone', () => {
    signIn(OWNER)
    voucherOf(`EX5 photo ${run}`).then((v) => cy.request(`/expense/vouchers/${v.id}/receipts`).its('body.data.0.id').then((rid) => {
      token(USER).then((u) => {
        cy.request({ url: `${GW}/api/expense/receipts/${rid}/content`, headers: hdr(u), failOnStatusCode: false }).its('status').should('eq', 404)
        cy.request({ method: 'DELETE', url: `${GW}/api/expense/receipts/${rid}`, headers: hdr(u), failOnStatusCode: false }).its('status').should('eq', 403)
      })
      token(OTHER).then((o) => cy.request({ url: `${GW}/api/expense/receipts/${rid}/content`, headers: hdr(o), failOnStatusCode: false })
        .its('status').should('be.oneOf', [400, 404]))
      signIn(OWNER, true)
      openExpenses()
      row(`EX5 photo ${run}`).find('[data-cy=expense-receipts]').click()
      cy.get('[data-cy=receipt-remove]').first().click()
      cy.get('[data-ui-confirm="ok"]').click()
      row(`EX5 photo ${run}`).find('[data-cy=expense-receipts]', { timeout: 20000 }).should('contain', 'Add a receipt')
      cy.request({ url: `/expense/receipts/${rid}/content`, failOnStatusCode: false }).its('status').should('eq', 404)
    }))
  })
})
