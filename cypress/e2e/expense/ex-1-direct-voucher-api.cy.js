/**
 * EX-1 — direct expense voucher, BACKEND gate (gateway-direct, Bearer token).
 *
 * Design: microservices/docs/slices/ex-1-direct-expense-voucher.md §4. The UI half is ex-1-direct-voucher-ui.cy.js.
 *
 * <h3>The money assertion is the TRIAL BALANCE, never the voucher</h3>
 * A voucher saying "POSTED_GL" is what expense-service believes. The ledger is what the shop knows — three
 * specs once went green while 4200 Sales Discount stayed empty (project memory: GL outbox drops new fields). So
 * every money case measures each account's NET movement in finance-service's trial balance.
 *
 * <h3>Capability state and tokens</h3>
 * A gateway token carries the capabilities resolved when it was MINTED. The capability is switched through the
 * monolith session (cy.setCapability → auth-service), and only then is a gateway token taken. after() removes the
 * override so owner.business is left exactly as seeded (OFF).
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = 'owner.business@myplus.com'
const USER = 'user.business@myplus.com'
const OTHER_TENANT = 'owner.pesticide@myplus.com'
const CAP = 'expenseManagement'

const token = (email) =>
  cy.request({ method: 'POST', url: `${GW}/api/auth/login`, headers: { 'Content-Type': 'application/json' },
    body: { email, password: PW }, failOnStatusCode: false })
    .then((r) => { expect(r.status, `login ${email}: ${JSON.stringify(r.body)}`).to.eq(200); return r.body.data.accessToken })

const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `ex1-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

/** Net (debit − credit) per account code from the live trial balance. */
const netByCode = (t) =>
  cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).then((r) => {
    expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
    const m = {}
    ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
    return m
  })
const delta = (before, after, code) => Math.round(((after[code] || 0) - (before[code] || 0)) * 100) / 100

const categories = (t) =>
  cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    return r.body.data
  })

const record = (t, body, idem = key()) =>
  cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(t, { 'Idempotency-Key': idem }),
    body, failOnStatusCode: false })

/** Poll the voucher until the LEDGER has answered (bounded — a hung outbox fails the case, it does not hang it). */
const untilInBooks = (t, id, tries = 20) =>
  cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t) }).then((r) => {
    const v = r.body.data
    if (v.postingStatus === 'POSTED_GL' || v.postingStatus === 'FAILED' || tries <= 0) return v
    cy.wait(1000)
    return untilInBooks(t, id, tries - 1)
  })

const today = () => localIsoDate()

describe('EX-1 — direct expense voucher (backend, gateway-direct)', () => {
  after(() => {
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('1 — capability OFF: a voucher is refused, nothing is written', () => {
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
    token(OWNER).then((t) => {
      categories(t).then((cats) => {
        record(t, { voucherDate: today(), paidFrom: 'CASH', lines: [{ categoryId: cats[0].id, amount: 10 }] })
          .then((r) => {
            expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
            expect(r.body.message).to.match(/not switched on|not enabled/i)
          })
      })
    })
  })

  describe('with Expense management ON', () => {
    before(() => {
      cy.loginAsOwner()
      cy.setCapability(CAP, true)
    })

    it('2 — rent paid in cash: 6000 Rent up and 1000 Cash down by exactly the amount', () => {
      token(OWNER).then((t) => {
        categories(t).then((cats) => {
          const rent = cats.find((c) => c.accountCode === '6000')
          expect(rent, 'a default Rent category mapped to 6000').to.exist
          netByCode(t).then((before) => {
            record(t, { voucherDate: today(), paidFrom: 'CASH', payeeName: 'Landlord', note: 'EX-1 gate',
              lines: [{ categoryId: rent.id, amount: 2500, description: 'October rent' }] })
              .then((r) => {
                expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
                const v = r.body.data
                expect(v.voucherNo, 'per-org number').to.match(/^EXP-\d{6}$/)
                expect(v.total).to.eq(2500)
                expect(v.status).to.eq('POSTED')
                expect(v.postingStatus, 'never claims the books before the ledger answers').to.be.oneOf(['PENDING', 'POSTED_GL'])
                untilInBooks(t, v.id).then((done) => {
                  expect(done.postingStatus, `posting: ${done.postingError || ''}`).to.eq('POSTED_GL')
                  netByCode(t).then((after) => {
                    expect(delta(before, after, '6000'), '6000 Rent debited').to.eq(2500)
                    expect(delta(before, after, '1000'), '1000 Cash credited').to.eq(-2500)
                    expect(delta(before, after, '1010'), 'Bank untouched').to.eq(0)
                  })
                })
              })
          })
        })
      })
    })

    it('3 — paid from bank credits 1010, not 1000', () => {
      token(OWNER).then((t) => {
        categories(t).then((cats) => {
          const util = cats.find((c) => c.accountCode === '6100')
          netByCode(t).then((before) => {
            record(t, { voucherDate: today(), paidFrom: 'BANK', lines: [{ categoryId: util.id, amount: 730.5 }] })
              .then((r) => untilInBooks(t, r.body.data.id))
              .then((done) => {
                expect(done.postingStatus).to.eq('POSTED_GL')
                netByCode(t).then((after) => {
                  expect(delta(before, after, '6100')).to.eq(730.5)
                  expect(delta(before, after, '1010')).to.eq(-730.5)
                  expect(delta(before, after, '1000'), 'Cash untouched').to.eq(0)
                })
              })
          })
        })
      })
    })

    it('4 — void with a reason restores both accounts; the voucher is kept as VOIDED', () => {
      token(OWNER).then((t) => {
        categories(t).then((cats) => {
          const fuel = cats.find((c) => c.accountCode === '6200')
          netByCode(t).then((before) => {
            record(t, { voucherDate: today(), paidFrom: 'CASH', lines: [{ categoryId: fuel.id, amount: 1200 }] })
              .then((r) => untilInBooks(t, r.body.data.id))
              .then((posted) => {
                cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${posted.id}/void`, headers: hdr(t),
                  body: {}, failOnStatusCode: false })
                  .then((noReason) => expect(noReason.body.success, 'a void needs a reason').to.eq(false))
                cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${posted.id}/void`, headers: hdr(t),
                  body: { reason: 'entered twice' }, failOnStatusCode: false })
                  .then((vr) => {
                    expect(vr.body.success, JSON.stringify(vr.body)).to.eq(true)
                    expect(vr.body.data.status).to.eq('VOIDED')
                  })
                untilInBooks(t, posted.id).then(() => {
                  // the reversal is its own outbox event — poll the ledger, not the voucher
                  const settle = (n) => netByCode(t).then((after) => {
                    if ((delta(before, after, '6200') !== 0) && n > 0) { cy.wait(1000); return settle(n - 1) }
                    expect(delta(before, after, '6200'), '6200 back where it was').to.eq(0)
                    expect(delta(before, after, '1000'), '1000 back where it was').to.eq(0)
                  })
                  settle(20)
                })
                cy.request({ url: `${GW}/api/expense/vouchers/${posted.id}`, headers: hdr(t) })
                  .its('body.data.status').should('eq', 'VOIDED')
              })
          })
        })
      })
    })

    it('5 — the same Idempotency-Key twice makes ONE voucher', () => {
      token(OWNER).then((t) => {
        categories(t).then((cats) => {
          const k = key()
          const body = { voucherDate: today(), paidFrom: 'CASH', lines: [{ categoryId: cats[0].id, amount: 11 }] }
          record(t, body, k).then((a) => {
            record(t, body, k).then((b) => {
              expect(b.body.success, JSON.stringify(b.body)).to.eq(true)
              expect(b.body.data.id, 'the replay returns the first voucher').to.eq(a.body.data.id)
              expect(b.body.data.voucherNo).to.eq(a.body.data.voucherNo)
            })
          })
        })
      })
    })

    it('6 — THE BOUNDARY: a category mapped to 1200 Inventory is refused at save', () => {
      token(OWNER).then((t) => {
        cy.request({ method: 'POST', url: `${GW}/api/expense/categories`, headers: hdr(t), failOnStatusCode: false,
          body: { code: 'STOCKBUY' + Date.now() % 100000, name: 'Stock for resale', accountCode: '1200' } })
          .then((r) => {
            expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
            expect(r.body.message).to.match(/not an expense account/i)
          })
      })
    })

    it('7 — a user records and sees only their own; cannot void; another tenant cannot read it', () => {
      token(OWNER).then((ownerT) => {
        categories(ownerT).then((cats) => {
          record(ownerT, { voucherDate: today(), paidFrom: 'CASH', lines: [{ categoryId: cats[0].id, amount: 12 }] })
            .then((ownerV) => {
              const ownersId = ownerV.body.data.id
              token(USER).then((userT) => {
                record(userT, { voucherDate: today(), paidFrom: 'CASH', lines: [{ categoryId: cats[0].id, amount: 13 }] })
                  .then((uv) => {
                    expect(uv.body.success, `user records: ${JSON.stringify(uv.body)}`).to.eq(true)
                    const usersId = uv.body.data.id
                    cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(userT) }).then((lr) => {
                      const ids = lr.body.data.content.map((v) => v.id)
                      expect(ids, 'own voucher listed').to.include(usersId)
                      expect(ids, "the owner's voucher is not").not.to.include(ownersId)
                    })
                    cy.request({ url: `${GW}/api/expense/vouchers/${ownersId}`, headers: hdr(userT), failOnStatusCode: false })
                      .its('status').should('eq', 404)
                    untilInBooks(userT, usersId).then(() => {
                      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${usersId}/void`, headers: hdr(userT),
                        body: { reason: 'x' }, failOnStatusCode: false })
                        .its('status').should('eq', 403)
                    })
                  })
              })
              token(OTHER_TENANT).then((otherT) => {
                cy.request({ url: `${GW}/api/expense/vouchers/${ownersId}`, headers: hdr(otherT), failOnStatusCode: false })
                  .its('status').should('eq', 404)
              })
            })
        })
      })
    })
  })
})
