/**
 * EX-1b — a refused posting says why at once, and can be posted again.
 *
 * Design: microservices/docs/slices/ex-1b-post-again.md §4 (finding E1 of the 5 Oct review).
 *
 * Tenant: owner.lifecycle (sacrificial) — closing the books is tenant-wide, so it happens there and the lock it found is
 * put back EXACTLY in after(). Case 5 uses owner.business / user.business / owner.pesticide for the scoping half only.
 * Money is measured on the trial balance, never on the voucher.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const LIFECYCLE = 'owner.lifecycle@myplus.com'
const CAP = 'expenseManagement'
const run = String(Date.now()).slice(-6)

const token = (email) =>
  cy.request({ method: 'POST', url: `${GW}/api/auth/login`, headers: { 'Content-Type': 'application/json' },
    body: { email, password: PW }, failOnStatusCode: false })
    .then((r) => { expect(r.status, `login ${email}`).to.eq(200); return r.body.data.accessToken })
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `ex1b-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

const netByCode = (t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).then((r) => {
  expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
  const m = {}
  ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
  return m
})
const delta = (b, a, code) => Math.round(((a[code] || 0) - (b[code] || 0)) * 100) / 100

const dayOffset = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return localIsoDate(d) }
const YESTERDAY = () => dayOffset(-1)
const lock = (t, through) => cy.request({ method: 'POST', url: `${GW}/api/finance/gl/period-lock${through ? '?lockedThrough=' + through : ''}`,
  headers: hdr(t) }).its('status').should('eq', 200)
const lockNow = (t) => cy.request({ url: `${GW}/api/finance/gl/period-lock`, headers: hdr(t) }).then((r) => r.body.lockedThrough || null)   // null = open

const rent = (t) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) })
  .then((r) => (r.body.data || []).find((c) => c.accountCode === '6000'))
const record = (t, body) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`,
  headers: hdr(t, { 'Idempotency-Key': key() }), body, failOnStatusCode: false })
const voucher = (t, id) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t), failOnStatusCode: false })
const postAgain = (t, id) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/post-again`, headers: hdr(t), failOnStatusCode: false })
/** Poll until the ledger answers. A refusal must arrive in seconds now, so the bound is short on purpose. */
const settled = (t, id, tries = 15) => voucher(t, id).then((r) => {
  const v = r.body.data
  if (v.postingStatus === 'POSTED_GL' || v.postingStatus === 'FAILED' || tries <= 0) return v
  cy.wait(1000)
  return settled(t, id, tries - 1)
})

describe('EX-1b — a refused posting says why, and can be posted again', () => {
  let t = null, rentCat = null, lockWas = null, refusedId = null, billId = null
  const made = []

  before(() => {
    cy.loginAs(LIFECYCLE, PW, '/getBusinessDashboardStats', 'ex1b-' + Date.now())
    cy.setCapability(CAP, true)
    // a supplier for the bill case, through the Supplier form's own requests
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: `EX1BCo_${run}`, email: `ex1bco${run}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const rows = (cr.body.collection || cr.body.data || cr.body.object || cr.body)
      const co = rows.find((c) => c.name === `EX1BCo_${run}`)
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: `EX1B_${run}`, companyId: co.id, mobile: '0307' + run.padStart(7, '0'), email: `ex1b${run}@t.com` } })
    })
    token(LIFECYCLE).then((tk) => { t = tk })
    cy.then(() => lockNow(t)).then((w) => { lockWas = w || null })
    cy.then(() => lock(t, null))                                   // start open
    cy.then(() => rent(t)).then((c) => { rentCat = c })
    // a bill dated two days ago, posted while the period is open
    cy.then(() => cy.request({ url: `${GW}/api/expense/suppliers`, headers: hdr(t) })).then((r) => {
      const sup = (r.body.data || []).find((s) => String(s.label).includes(`EX1B_${run}`))
      expect(sup, 'the test supplier is offered').to.exist
      return record(t, { voucherDate: dayOffset(-2), paidFrom: 'AP', supplierId: sup.id, payeeName: `EX1B bill ${run}`,
        lines: [{ categoryId: rentCat.id, amount: 30 }] })
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      billId = r.body.data.id; made.push(billId)
      return settled(t, billId)
    }).then((v) => expect(v.postingStatus, 'bill in the books').to.eq('POSTED_GL'))
  })

  // Every case starts with the books closed through yesterday; a case that needs them open opens them itself. Set per
  // case (not left over from the previous one) so a case that fails half-way cannot leave the next one on open books.
  beforeEach(() => { cy.then(() => lock(t, YESTERDAY())) })

  after(() => {
    cy.then(() => { if (t) lock(t, lockWas) })                     // the lock exactly as found
    cy.then(() => made.forEach((id) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`, headers: hdr(t),
      body: { reason: 'EX-1b gate clean-up' }, failOnStatusCode: false })))
    cy.loginAs(LIFECYCLE, PW, '/getBusinessDashboardStats', 'ex1b-end-' + Date.now())
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('1 — a closed period refuses at once with its reason; the books do not move', () => {
    netByCode(t).then((before) => {
      record(t, { voucherDate: YESTERDAY(), paidFrom: 'CASH', payeeName: `EX1B rent ${run}`, lines: [{ categoryId: rentCat.id, amount: 7 }] })
        .then((r) => {
          expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
          refusedId = r.body.data.id; made.push(refusedId)
          return settled(t, refusedId)
        }).then((v) => {
          expect(v.postingStatus, 'refused within seconds, not after 20 retries').to.eq('FAILED')
          expect(v.postingError, 'the books’ own reason').to.match(/period is closed/i)
        })
      netByCode(t).then((after) => {
        expect(delta(before, after, '6000')).to.eq(0)
        expect(delta(before, after, '1000')).to.eq(0)
      })
    })
  })

  it('2 — post again while still closed: refused again, same reason, nothing in the books', () => {
    netByCode(t).then((before) => {
      postAgain(t, refusedId).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.postingStatus).to.eq('PENDING')
      })
      settled(t, refusedId).then((v) => {
        expect(v.postingStatus).to.eq('FAILED')
        expect(v.postingError).to.match(/period is closed/i)
      })
      netByCode(t).then((after) => expect(delta(before, after, '6000')).to.eq(0))
    })
  })

  it('3 — reopen, post again: in the books exactly once; then nothing is waiting', () => {
    lock(t, null)
    netByCode(t).then((before) => {
      postAgain(t, refusedId).its('body.success').should('eq', true)
      settled(t, refusedId).then((v) => {
        expect(v.postingStatus).to.eq('POSTED_GL')
        expect(v.postingError, 'the old reason is cleared').to.be.oneOf([null, undefined, ''])
      })
      netByCode(t).then((after) => {
        expect(delta(before, after, '6000'), 'rent').to.eq(7)
        expect(delta(before, after, '1000'), 'cash').to.eq(-7)
      })
      postAgain(t, refusedId).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.match(/nothing is waiting/i)
      })
      netByCode(t).then((again) => expect(delta(before, again, '6000'), 'never twice').to.eq(7))
    })
  })

  it('⭐ 4 — paying a bill into a closed period is REFUSED (not "no answer"): nothing pending, still owed', () => {
    // finance dates a payment's journal TODAY whatever its paidOn (design §4b F6), so it meets a closed period only when
    // today itself is closed — close through today for this case.
    lock(t, dayOffset(0))
    cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${billId}/pay`, headers: hdr(t, { 'Idempotency-Key': key() }),
      body: { amount: 10, method: 'CASH', paidOn: dayOffset(0) }, failOnStatusCode: false }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
      expect(r.body.message).to.match(/refused/i)
      expect(r.body.message).to.match(/period is closed/i)
      expect(r.body.message, 'not mistaken for a lost answer').to.not.match(/did not answer/i)
    })
    cy.request({ url: `${GW}/api/expense/vouchers/${billId}/payments`, headers: hdr(t) }).then((r) => {
      const rows = r.body.data || []
      expect(rows.filter((p) => p.status === 'PENDING'), 'nothing left pending').to.have.length(0)
    })
    voucher(t, billId).then((r) => expect(Number(r.body.data.openAmount), 'still owes all of it').to.eq(30))
  })

  it('5 — scoped: another business, or a colleague’s expense, is not found', () => {
    // the other business has the module ON, so the refusal is the scope, not the switch
    cy.loginAs('owner.pesticide@myplus.com', PW, '/getBusinessDashboardStats', 'ex1b-pest-' + Date.now())
    cy.setCapability(CAP, true)
    token('owner.pesticide@myplus.com').then((other) => postAgain(other, refusedId).then((r) => {
      expect(r.body.success, 'another business').to.not.eq(true)
      expect(r.status).to.eq(404)
    }))
    cy.loginAs('owner.pesticide@myplus.com', PW, '/getBusinessDashboardStats', 'ex1b-pest2-' + Date.now())
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
    cy.loginAsOwner()
    cy.setCapability(CAP, true)
    token('owner.business@myplus.com').then((ob) => rent(ob).then((c) =>
      record(ob, { paidFrom: 'CASH', payeeName: `EX1B scope ${run}`, lines: [{ categoryId: c.id, amount: 1 }] })).then((r) => {
      const id = r.body.data.id
      token('user.business@myplus.com').then((ub) => postAgain(ub, id).then((rr) => {
        expect(rr.body.success, 'a colleague’s expense').to.not.eq(true)
        expect(rr.status).to.eq(404)
      }))
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`, headers: hdr(ob), body: { reason: 'EX-1b scope' }, failOnStatusCode: false })
    }))
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('6 — on screen: the reason is on the row, Post again is offered, and it lands once reopened', () => {
    let id = null
    record(t, { voucherDate: YESTERDAY(), paidFrom: 'CASH', payeeName: `EX1B screen ${run}`, lines: [{ categoryId: rentCat.id, amount: 3 }] })
      .then((r) => { id = r.body.data.id; made.push(id); return settled(t, id) })
      .then((v) => expect(v.postingStatus).to.eq('FAILED'))
    cy.loginAs(LIFECYCLE, PW, '/getBusinessDashboardStats', 'ex1b-ui-' + Date.now())
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    cy.get('#snavTill').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavTill .snav-btn').click() })
    cy.get('#navExpenses').click()
    cy.contains('#tableExpense tbody tr', `EX1B screen ${run}`, { timeout: 20000 }).as('row')
    cy.get('@row').find('[data-cy=expense-posting-error]').should('be.visible').and('contain', 'period is closed')
    cy.get('@row').find('[data-cy=post-again]').should('be.visible')
    cy.contains('#tableExpense tbody tr', `EX1B rent ${run}`).find('[data-cy=post-again]').should('not.exist')   // in the books: no button
    cy.then(() => lock(t, null))
    cy.intercept('POST', '**/post-again').as('again')
    cy.get('@row').find('[data-cy=post-again]').click()
    cy.wait('@again').its('response.body.success').should('eq', true)
    cy.contains('#tableExpense tbody tr', `EX1B screen ${run}`).find('.exp-chip', { timeout: 20000 }).should('contain', 'In the books')
    cy.contains('#tableExpense tbody tr', `EX1B screen ${run}`).find('[data-cy=post-again]').should('not.exist')
  })
})
