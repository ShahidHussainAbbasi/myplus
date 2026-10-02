/**
 * EX-3 — a till pay-out reaches the books.
 *
 * Design: microservices/docs/slices/ex-3-till-pay-outs.md §4.
 *
 * <h3>Two truths that must agree</h3>
 * The SHIFT REPORT says what left the drawer; the TRIAL BALANCE says what the books know. Before EX-3 only the
 * first moved. Every money case here measures both, and the shift is closed again in after() (leave no state).
 */

const PW = 'Demo@2025!'
const OWNER = { email: 'owner.business@myplus.com', check: '/getBusinessDashboardStats' }
const CAP = 'org.cap.expenseManagement'

const signIn = (fresh) => cy.loginAs(OWNER.email, PW, OWNER.check, fresh ? 'ex3-' + Date.now() : undefined)
const key = () => 'ex3-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)

const closeIfOpen = () => cy.request({ url: '/currentShift', failOnStatusCode: false }).then((r) => {
  if (r.body && r.body.status === 'SUCCESS') {
    cy.request({ method: 'POST', url: '/closeShift', form: true, body: { countedCash: 0 }, failOnStatusCode: false })
  }
})
const openShift = () => {
  closeIfOpen()
  cy.request({ method: 'POST', url: '/openShift', form: true, body: { openingFloat: 5000 } })
    .its('body.status').should('eq', 'SUCCESS')
}
const payOut = (body) => cy.request({ method: 'POST', url: '/cashMovement', form: true, failOnStatusCode: false,
  body: Object.assign({ type: 'PAY_OUT', reason: 'EX3' }, body) })
const payOuts = () => cy.request('/shiftReport').then((r) => Number(r.body.object.payOuts || 0))

/** Net (debit − credit) per account from the live trial balance. */
const net = () => cy.request('/gl/trialBalance').then((r) => {
  const m = {}
  const body = typeof r.body === 'string' ? JSON.parse(r.body) : r.body
  ;(body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
  return m
})
const d = (a, b, code) => Math.round(((b[code] || 0) - (a[code] || 0)) * 100) / 100

/** The drawer voucher for a movement id, polled until the ledger has answered (bounded). */
const drawerVoucher = (movementId, tries = 25) =>
  cy.request('/expense/vouchers?size=200').then((r) => {
    const v = (r.body.data.content || []).find((x) => x.source === 'DRAWER' && String(x.sourceRef) === String(movementId))
    if ((v && v.postingStatus === 'POSTED_GL') || tries <= 0) return v
    cy.wait(1000)
    return drawerVoucher(movementId, tries - 1)
  })

describe('EX-3 — till pay-outs reach the books', () => {
  after(() => {
    signIn()
    closeIfOpen()
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: CAP }, failOnStatusCode: false })
  })

  it('1 — module OFF: a pay-out records exactly as before; no expense is created', () => {
    signIn()
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: CAP }, failOnStatusCode: false })
    signIn(true)
    openShift()
    payOuts().then((before) => {
      payOut({ amount: 40, idempotencyKey: key() }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const id = r.body.object.id
        payOuts().should('eq', before + 40)
        cy.wait(2000)
        cy.request({ url: '/expense/vouchers?size=200', failOnStatusCode: false }).then((v) => {
          const list = (v.body && v.body.data && v.body.data.content) || []
          expect(list.some((x) => x.source === 'DRAWER' && String(x.sourceRef) === String(id)), 'no expense when OFF').to.eq(false)
        })
      })
    })
  })

  describe('module ON', () => {
    before(() => {
      signIn()
      cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: CAP, enabled: 'true' } })
        .its('body.success').should('eq', true)
    })

    it('2 — a pay-out without a category is refused', () => {
      signIn(true)
      openShift()
      payOut({ amount: 10, idempotencyKey: key() }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).not.to.eq('SUCCESS')
        expect(r.body.message).to.match(/category/i)
      })
    })

    it('3 — pay-out 1,200 Utilities: the drawer AND the books move by exactly that', () => {
      signIn(true)
      openShift()
      cy.request('/expense/categories').its('body.data').then((cats) => {
        const util = cats.find((c) => c.accountCode === '6100')
        net().then((tbBefore) => {
          payOuts().then((drawerBefore) => {
            payOut({ amount: 1200, categoryId: util.id, reason: 'Electricity bill', idempotencyKey: key() }).then((r) => {
              expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
              const movementId = r.body.object.id
              payOuts().should('eq', drawerBefore + 1200)
              drawerVoucher(movementId).then((v) => {
                expect(v, 'a DRAWER voucher for this movement').to.exist
                expect(v.voucherNo).to.match(/^EXP-\d{6}$/)
                expect(v.paidFrom).to.eq('DRAWER')
                expect(v.postingStatus, v.postingError || '').to.eq('POSTED_GL')
                net().then((tbAfter) => {
                  expect(d(tbBefore, tbAfter, '6100'), '6100 Utilities debited').to.eq(1200)
                  expect(d(tbBefore, tbAfter, '1000'), '1000 Cash credited — the drawer and the books agree').to.eq(-1200)
                })
              })
            })
          })
        })
      })
    })

    it('4 — the same Idempotency-Key twice: one movement, one voucher', () => {
      signIn(true)
      openShift()
      cy.request('/expense/categories').its('body.data').then((cats) => {
        const k = key()
        const body = { amount: 77, categoryId: cats[0].id, idempotencyKey: k }
        payOuts().then((before) => {
          payOut(body).then((a) => {
            payOut(body).then((b) => {
              expect(b.body.status, JSON.stringify(b.body)).to.eq('SUCCESS')
              expect(b.body.object.id, 'the replay returns the first movement').to.eq(a.body.object.id)
              payOuts().should('eq', before + 77)
              drawerVoucher(a.body.object.id).then((v) => expect(v, 'one voucher').to.exist)
              cy.request('/expense/vouchers?size=200').then((r) => {
                const n = r.body.data.content.filter((x) => x.source === 'DRAWER' && String(x.sourceRef) === String(a.body.object.id))
                expect(n, 'exactly one voucher for the movement').to.have.length(1)
              })
            })
          })
        })
      })
    })

    it('5 — a drawer expense cannot be voided from Expenses (correct it at the till)', () => {
      signIn(true)
      openShift()
      cy.request('/expense/categories').its('body.data').then((cats) => {
        payOut({ amount: 15, categoryId: cats[0].id, idempotencyKey: key() }).then((r) => {
          drawerVoucher(r.body.object.id).then((v) => {
            cy.request({ method: 'POST', url: `/expense/vouchers/${v.id}/void`, body: { reason: 'try' }, failOnStatusCode: false })
              .then((vr) => {
                expect(vr.body.success, JSON.stringify(vr.body)).to.eq(false)
                expect(vr.body.message).to.match(/till/i)
              })
          })
        })
      })
    })

    it('6 — the till offers a Category for a pay-out when the module is on', () => {
      signIn(true)
      openShift()
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.get('#snavTill').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavTill .snav-btn').click() })
      cy.contains('#snavTill a', 'Cash Drawer').click()
      cy.get('#tillMoveType').select('PAY_OUT', { force: true })
      cy.get('#tillMoveCategoryGroup').should('be.visible')
      cy.get('#tillMoveCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
    })
  })
})
