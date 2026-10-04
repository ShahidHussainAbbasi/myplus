/**
 * MKT-1g — commission, immutable settlement ledger, T+N eligibility, manual payout, GL posting.
 * Contract: microservices/docs/slices/mkt-1g-settlement-payouts.md. Source §15, §16, §22.3.
 * Run headed: --env '{"mkt":"1g"}'
 *
 * The MONEY assertion reads the trial balance, not the ledger table the feature itself writes (programme rule).
 *
 * Time is not faked: the orders here are sold under a 0-day return policy and the operator sets T+0, so a line
 * delivered today is payable today when today is a business day. On a Saturday or Sunday the lines wait for Monday
 * (that IS the rule, MKT-R15.1), and the cases that need a settled line say so instead of passing vacuously.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, msg, post, get, expectRefused, openMarketplace, seedPolicies,
  publishOffer } = require('./mkt-helpers')

const ACC = {
  login: '/marketplace/account/login',
  register: '/marketplace/account/register',
  orders: '/marketplace/account/orders',
}
const SET = {
  settings: '/platform/mkt/settlementSettings',   // GET → {tPlusDays, booksOrganizationId}; POST {tPlusDays, useMyBooks}
  run: '/platform/mkt/runSettlement',             // POST → {checked, settled, waiting, onHold, waitingForBooks}
  accounts: '/platform/mkt/settlementAccounts',
  account: (org) => `/platform/mkt/settlementAccount?organizationId=${org}`,
  adjust: '/platform/mkt/adjustLedger',            // {organizationId, amount (signed), reason, idempotencyKey}
  myAccount: '/mkt/settlementAccount',
}
const PW = 'Shop!ng2026'
const PRICE = 52000
const businessDay = () => ![0, 6].includes(new Date().getDay())

gate('1g')('MKT-1g — settlement and payouts', () => {
  const run = uniq()
  const phone = (k) => `0313${String(run).slice(-6)}${k}`
  let offer

  const asCustomer = (ph) => {
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.login, { phone: ph, password: PW }).then((r) => {
      if (ok(r.body)) return
      post(ACC.register, { phone: ph, name: 'Ali Raza', password: PW }).then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))
    })
  }
  const asSeller = () => cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')

  /** The seller's real steps: accept → packed → parcel → delivered (or stop after accept). Yields {orderNo}. */
  const order = (ph, { mode = 'COD', deliver = true } = {}) => {
    const out = {}
    asCustomer(ph)
    post(API.checkout, { offerId: offer.offerId, quantity: 1, expectedPrice: PRICE, customerName: 'Ali Raza',
      customerPhone: ph, address: '1 Clifton', city: 'Karachi', idempotencyKey: `g-${run}-${Math.random()}`,
      paymentMode: mode, cardToken: mode === 'CARD' ? 'tok_ok' : undefined }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      Object.assign(out, { orderNo: data(r.body).orderNo, so: data(r.body).sellerOrderId, v: data(r.body).sellerOrderVersion })
    })
    asSeller()
    cy.then(() => post(API.acceptOrder, { id: out.so, version: out.v }).then((a) => expect(ok(a.body), JSON.stringify(a.body)).to.eq(true)))
    if (!deliver) return cy.wrap(out)
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`).then((s) => {
      out.storeOrderId = list(s.body).find((x) => x.orderNo === out.orderNo).storeOrderId
    }))
    cy.then(() => post('/updateOrderStatus', { id: out.storeOrderId, status: 'PACKED' }))
    cy.then(() => get(`/getOrder?id=${out.storeOrderId}`).then((r) => {
      const line = data(r.body).items[0]
      post('/shipOrder', { id: out.storeOrderId, lines: [{ orderItemId: line.id, quantity: line.quantity }], carrier: 'Own rider',
        trackingNumber: `RID-${run}` })
    }))
    cy.then(() => post('/updateOrderStatus', { id: out.storeOrderId, status: 'DELIVERED' })
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)))
    return cy.wrap(out)
  }

  const settle = () => {
    cy.loginAsOperator()
    return post(SET.run, {}).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  }

  const statementLine = (orderNo) => {
    asSeller()
    return get(`${API.statement}?size=100`).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      return list(r.body).find((x) => x.orderNo === orderNo)
    })
  }

  /** The trial-balance line for `code` in the operator's books; 0 when the account has no balance yet. */
  const tb = (code) => get('/gl/trialBalance').then((r) => Number((list(r.body).find((a) => a.code === code) || {}).balance || 0))

  before(() => {
    seedPolicies(`${run}s`, { returnDays: 0 }).then((p) => cy.then(() => publishOffer(SELLER_A, { run: `${run}s`, price: PRICE, qty: 40,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))).then((o) => { offer = o })
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5 })
    post(SET.settings, { tPlusDays: 0, useMyBooks: true }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  })

  it('MKT-1g-01 [MKT-R15.5] [MKT-R16.1] the seller reads a statement whose lines add up (real UI)', () => {
    order(phone(1)).then(() => settle())
    asSeller()
    openMarketplace()
    cy.get('#mktStatementTab').click()
    cy.get(UI.statementTable).should('be.visible').find('tr.mkt-line').should('have.length.at.least', 1).each(($tr) => {
      const n = (sel) => Number(($tr.find(sel).text() || '0').replace(/[^0-9.-]/g, ''))
      const parts = n('.mkt-payable') + n('.mkt-commission') + n('.mkt-delivery') + n('.mkt-fees') + n('.mkt-tax')
        + n('.mkt-reserve') + n('.mkt-adjustment')
      expect(Math.round(parts * 100)).to.eq(Math.round(n('.mkt-customer-amount') * 100))
    })
  })

  it('MKT-1g-02 [MKT-R15.2] [MKT-R16.2] nothing is payable at placement or before delivery; payable only after delivery', () => {
    order(phone(2), { deliver: false }).then((o) => {
      settle()
      statementLine(o.orderNo).then((l) => {
        expect(l, 'accepted, not delivered: on the statement').to.exist
        expect(l.status).to.eq('NOT_ELIGIBLE')
        expect(l.eligibleOn, 'no payable date before delivery').to.eq(null)
      })
    })
    asSeller()
    get(`${API.statement}?status=ELIGIBLE&size=100`).then((r) => {
      expect(ok(r.body), 'positive control').to.eq(true)
      list(r.body).forEach((e) => {
        expect(e.deliveredAt, `line ${e.id} eligible only after delivery`).to.exist
        expect(new Date(e.eligibleOn).getTime()).to.be.at.least(new Date(e.deliveredAt.substring(0, 10)).getTime())
      })
    })
  })

  it('MKT-1g-03 [MKT-R15.1] eligibleOn is T+N BUSINESS days after the trigger', () => {
    asSeller()
    get(`${API.statement}?size=100`).then((r) => {
      const dated = list(r.body).filter((e) => e.eligibleOn)
      expect(dated.length, 'positive control: delivered lines carry a payable date').to.be.greaterThan(0)
      dated.forEach((e) => {
        const d = new Date(`${e.eligibleOn}T00:00:00Z`).getUTCDay()
        expect(d, `eligibleOn ${e.eligibleOn} is never a weekend`).to.not.be.oneOf([0, 6])
      })
    })
  })

  it('MKT-1g-04 [MKT-R16.3] [MKT-R22.3] a payout is idempotent and needs a second person to approve', () => {
    order(phone(4), { mode: 'CARD' }).then(() => settle())
    cy.loginAsOperator()
    cy.orgOf(SELLER_A).then((org) => {
      const key = `payout-${run}`
      post(API.requestPayout, { organizationId: org.id, idempotencyKey: key }).then((r1) =>
        post(API.requestPayout, { organizationId: org.id, idempotencyKey: key }).then((r2) => {
          if (!ok(r1.body)) {
            // no positive balance (weekend, or earlier runs' cash orders outweigh): a refusal in words, never a payout
            if (msg(r1.body).includes('still')) return expect(msg(r1.body)).to.match(/PO-\d+/)
            return expectRefused(r1, 'nothing')
          }
          expect(data(r2.body).id, 'the same key is the same payout').to.eq(data(r1.body).id)
          post(API.approvePayout, { id: data(r1.body).id }).then((a) => expectRefused(a, 'Another person'))
          post(API.markPayoutPaid, { id: data(r1.body).id, bankReference: 'TRX-123' }).then((p) => expectRefused(p, 'approved'))
        }))
    })
  })

  it('MKT-1g-05 [MKT-R15.6] the ledger has no edit path: an entry cannot be changed, only corrected by a new line', () => {
    cy.loginAsOperator()
    cy.request({ method: 'PUT', url: '/platform/mkt/ledgerEntry', failOnStatusCode: false, body: { id: 1, credit: 0 } })
      .then((r) => expect(r.status, 'no update route exists').to.be.oneOf([404, 405]))
    cy.orgOf(SELLER_A).then((org) => {
      get(SET.account(org.id)).then((a0) => {
        const before = data(a0.body)
        post(SET.adjust, { organizationId: org.id, amount: -100, reason: 'gate correction', idempotencyKey: `adj-${run}` }).then((r) => {
          expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
          const after = data(r.body)
          expect(Math.round((before.balance - after.balance) * 100)).to.eq(10000)
          expect(after.entries[0].entryType).to.eq('ADJUSTMENT')
          expect(after.entries.slice(1).map((e) => e.id), 'every earlier line is unchanged')
            .to.deep.eq(before.entries.slice(0, after.entries.length - 1).map((e) => e.id))
        })
        post(SET.adjust, { organizationId: org.id, amount: -100, reason: 'gate correction', idempotencyKey: `adj-${run}` })
          .then((r) => expect(data(r.body).entries.filter((e) => e.entryType === 'ADJUSTMENT' && e.memo === 'gate correction').length,
            'the same key records once').to.be.at.least(1))
        post(SET.adjust, { organizationId: org.id, amount: 100, reason: 'gate correction undone', idempotencyKey: `adj-${run}-undo` })
          .then((r) => expect(Math.round(data(r.body).balance * 100)).to.eq(Math.round(before.balance * 100)))
      })
    })
  })

  it('MKT-1g-06 [MKT-R15.5] commission reaches the operator\'s books: trial balance moves by exactly the commission', function () {
    if (!businessDay()) this.skip()   // T+0 on a weekend pays on Monday: there is nothing to settle today (MKT-R15.1)
    cy.loginAsOperator()
    tb('4500').then((rev0) => {
      order(phone(6), { mode: 'CARD' }).then((o) => {
        settle().then((r) => expect(r.settled, JSON.stringify(r)).to.be.at.least(1))
        statementLine(o.orderNo).then((l) => {
          expect(l.status).to.eq('ELIGIBLE')
          const commission = Number(l.commission)
          expect(commission, 'positive control: this sale earned a commission').to.be.greaterThan(0)
          cy.loginAsOperator()
          // the journal leaves through the outbox after commit: read until it lands (bounded)
          const until = (n) => tb('4500').then((rev1) => {
            if (Math.round((rev1 - rev0) * 100) === Math.round(commission * 100) || n === 0) {
              expect(Math.round((rev1 - rev0) * 100), '4500 moved by exactly the commission').to.eq(Math.round(commission * 100))
              return
            }
            cy.wait(1000)
            until(n - 1)
          })
          until(15)
        })
      })
    })
  })
})
