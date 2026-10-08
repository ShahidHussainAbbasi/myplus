/**
 * MKT-2d — cash-on-delivery reconciliation: what a seller owes MaxTheService for cash orders, since when, and the money
 * it pays. Source R20.3 ("COD reconciliation"), ruling R-MKT-2 (the seller's rider collects cash and owes the commission).
 * Contract: microservices/docs/slices/mkt-2d-cod-reconciliation.md
 *
 * TWO PHASES, because "overdue" needs days to pass and nothing in the product moves the clock:
 *   1. --env '{"mkt":"2d"}'              cases 2d-01..07 on today's stack. 2d-07 leaves Seller A owing one commission.
 *   2. restart the WHOLE stack (every service and the monolith) under a clock 12 days later
 *      (FAKETIME=+12d for start.sh and the monolith), then
 *      --env '{"mkt":"2d","later":1}'   cases 2d-08..10: that debt is now overdue (7 days to pay).
 *   Then restart the stack on its usual clock. Every service moves together, so tokens, holds and dates agree.
 *
 * Time is otherwise not faked: as in MKT-1g the offer is sold under a 0-day return policy and settlement runs T+0, so a
 * line delivered on a business day settles the same day (the stack runs on a business day).
 *
 * The money assertion reads the operator's trial balance (account 2400), not the ledger the feature writes.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, msg, post, get, expectRefused, openMarketplace, seedPolicies,
  publishOffer } = require('./mkt-helpers')

const COD = {
  list: '/platform/mkt/codReconciliation',
  pay: '/platform/mkt/recordRemittance',      // {organizationId, amount, reference, note, idempotencyKey}
  settings: '/platform/mkt/settlementSettings',
  account: (org) => `/platform/mkt/settlementAccount?organizationId=${org}`,
  adjust: '/platform/mkt/adjustLedger',
  run: '/platform/mkt/runSettlement',
}
const ACC = { login: '/marketplace/account/login', register: '/marketplace/account/register' }
const PW = 'Shop!ng2026'
const PRICE = 52000
const STOPPED = 'This seller cannot take cash on delivery right now. Please pay online or choose another offer.'
const LATER = !!Cypress.env('later')
const now = LATER ? it.skip : it
const later = LATER ? it : it.skip

gate('2d')('MKT-2d — cash orders: what sellers owe, and the money they pay', () => {
  const run = uniq()
  const phone = (k) => `0315${String(run).slice(-6)}${k}`
  let offer, org

  const asSeller = () => cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
  const asCustomer = (ph) => {
    cy.clearCookies()
    cy.visit(UI.publicPage)
    post(ACC.login, { phone: ph, password: PW }).then((r) => {
      if (ok(r.body)) return
      post(ACC.register, { phone: ph, name: 'Ali Raza', password: PW }).then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))
    })
  }
  const checkout = (ph, mode = 'COD') => post(API.checkout, { offerId: offer.offerId, quantity: 1, expectedPrice: PRICE,
    customerName: 'Ali Raza', customerPhone: ph, address: '1 Clifton', city: 'Karachi', idempotencyKey: `d-${run}-${Math.random()}`,
    paymentMode: mode, cardToken: mode === 'CARD' ? 'tok_ok' : undefined })
  /** Placed, accepted and delivered by the seller's real steps; yields {orderNo}. */
  const delivered = (ph) => {
    const out = {}
    asCustomer(ph)
    checkout(ph).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      Object.assign(out, { orderNo: data(r.body).orderNo, so: data(r.body).sellerOrderId, v: data(r.body).sellerOrderVersion })
    })
    asSeller()
    cy.then(() => post(API.acceptOrder, { id: out.so, version: out.v }).then((a) => expect(ok(a.body), JSON.stringify(a.body)).to.eq(true)))
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`).then((s) => {
      out.store = list(s.body).find((x) => x.orderNo === out.orderNo).storeOrderId
    }))
    cy.then(() => post('/updateOrderStatus', { id: out.store, status: 'PACKED' }))
    cy.then(() => get(`/getOrder?id=${out.store}`).then((r) => {
      const l = data(r.body).items[0]
      post('/shipOrder', { id: out.store, lines: [{ orderItemId: l.id, quantity: l.quantity }], carrier: 'Own rider', trackingNumber: `RD-${run}` })
    }))
    cy.then(() => post('/updateOrderStatus', { id: out.store, status: 'DELIVERED' }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)))
    return cy.wrap(out)
  }
  /** A cash order delivered and settled: yields the commission Seller A now owes for it. */
  const owing = (ph) => delivered(ph).then((o) => {
    cy.loginAsOperator()
    post(COD.run, {}).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    asSeller()
    return get(`${API.statement}?size=100`).then((r) => {
      const l = list(r.body).find((x) => x.orderNo === o.orderNo)
      expect(l.status, 'settled today: the stack runs on a business day').to.eq('ELIGIBLE')
      expect(Number(l.commission), 'positive control: the sale earned a commission').to.be.greaterThan(0)
      return Number(l.commission)
    })
  })
  const row = () => get(COD.list).then((r) => {
    expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
    return list(r.body).find((x) => x.organizationId === org)
  })
  const account = () => get(COD.account(org)).then((r) => data(r.body))
  /** Seller A starts square: pays what it owes, or a correction takes a positive balance to zero. */
  const square = () => {
    cy.loginAsOperator()
    return account().then((a) => {
      const bal = Number(a.balance)
      if (bal < 0) return post(COD.pay, { organizationId: org, amount: -bal, reference: 'GATE-SQUARE', idempotencyKey: `sq-${run}-${Math.random()}` })
        .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
      if (bal > 0) return post(COD.adjust, { organizationId: org, amount: -bal, reason: 'gate: start from zero', idempotencyKey: `sq-${run}-${Math.random()}` })
        .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
      return null
    }).then(() => account()).then((a) => expect(Number(a.balance), 'Seller A starts square').to.eq(0))
  }
  /** The CREDIT balance of `code` in the operator's trial balance. */
  const tb = (code) => get('/gl/trialBalance').then((r) => {
    const rows = ((data(r.body) || r.body || {}).rows) || []
    const a = rows.find((x) => x.code === code) || {}
    return Number(a.credit || 0) - Number(a.debit || 0)
  })
  const rejectAs = (orderNo) => {
    asSeller()
    return get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => {
      const so = list(r.body).find((x) => x.orderNo === orderNo)
      expect(so, `${orderNo} waits for the seller`).to.exist
      return post(API.rejectOrder, { id: so.id, version: so.version, reason: 'gate cleanup' })
    }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
  }
  const payouts = () => {
    cy.loginAsOperator()
    cy.visit('/platformDashboard')
    cy.get('#platMktPayoutsBtn').click()
    cy.get('#mktCodDays').should(($i) => expect($i.val()).to.not.eq(''))
    return cy.get(`#mktCodList tr.mkt-cod-row[data-org="${org}"]`, { timeout: 15000 })
  }
  const statementBanner = () => {
    asSeller()
    openMarketplace()
    cy.get('#mktStatementTab').scrollIntoView().click()
    return cy.get('#mktCodStanding', { timeout: 15000 })
  }

  before(() => {
    seedPolicies(`${run}d`, { returnDays: 0 }).then((p) => cy.then(() => publishOffer(SELLER_A, { run: `${run}d`, price: PRICE, qty: 40,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))).then((o) => { offer = o })
    cy.loginAsOperator()
    post(API.acceptWindow, { minutes: 5 })
    post(COD.settings, { tPlusDays: 0, useMyBooks: true, codRemitDays: 7, codStopWhenOverdue: false })
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.orgOf(SELLER_A).then((o) => { org = o.id })
  })

  after(() => {
    cy.loginAsOperator()
    post(COD.settings, { codRemitDays: 7, codStopWhenOverdue: false })
  })

  now('MKT-2d-01 [MKT-R20.3] a delivered cash order: the seller owes exactly its commission, from today, to pay within 7 days', () => {
    square()
    row().then((before) => {
      const cash0 = before ? Number(before.cashCollected) : 0
      owing(phone(1)).then((commission) => {
        cy.loginAsOperator()
        row().then((r) => {
          expect(Number(r.balance)).to.eq(-commission)
          expect(Number(r.standing.owed), 'owed = the commission of the one cash order').to.eq(commission)
          expect(Number(r.cashCollected) - cash0, 'the rider collected what the customer paid').to.eq(PRICE)
          expect(r.standing.overdue).to.eq(false)
          account().then((a) => {
            const today = a.entries[0].effectiveAt.substring(0, 10)          // the server's date, not the browser's
            expect(r.standing.owedSince).to.eq(today)
            const payBy = new Date(`${today}T00:00:00Z`)
            payBy.setUTCDate(payBy.getUTCDate() + 7)
            expect(r.standing.payBy).to.eq(payBy.toISOString().substring(0, 10))
            expect(a.cod.owed, 'the seller\'s own account says the same').to.eq(r.standing.owed)
          })
        })
      })
    })
  })

  now('MKT-2d-02 [MKT-R20.3] the seller reads what it owes and by when on its statement (real UI)', () => {
    cy.loginAsOperator()
    row().then((r) => {
      statementBanner().should('be.visible').and('have.class', 'alert-warning')
        .and('contain', `Please pay MaxTheService Rs ${Number(r.standing.owed).toLocaleString('en-US', { maximumFractionDigits: 2 })} for your cash orders by ${r.standing.payBy}.`)
    })
  })

  now('MKT-2d-03 [MKT-R20.3] a payment is refused, in a sentence: more than owed, less without a note, no reference, no amount; nothing is recorded', () => {
    cy.loginAsOperator()
    row().then((r) => {
      const owed = Number(r.standing.owed)
      const rs = owed.toLocaleString('en-US', { minimumFractionDigits: 2 })
      post(COD.pay, { organizationId: org, amount: owed + 1, reference: 'HBL-1', idempotencyKey: `x1-${run}` })
        .then((x) => expectRefused(x, `The seller owes Rs ${rs}. Enter at most that`))
      post(COD.pay, { organizationId: org, amount: 1, reference: 'HBL-1', idempotencyKey: `x2-${run}` })
        .then((x) => expectRefused(x, `The seller owes Rs ${rs} and paid Rs 1.00. Say why it paid less`))
      post(COD.pay, { organizationId: org, amount: owed, reference: ' ', idempotencyKey: `x3-${run}` })
        .then((x) => expectRefused(x, 'Enter the bank\'s reference, or the receipt number for cash.'))
      post(COD.pay, { organizationId: org, amount: 0, reference: 'HBL-1', idempotencyKey: `x4-${run}` })
        .then((x) => expectRefused(x, 'Enter the amount the seller paid.'))
      row().then((after) => expect(after.standing.owed, 'nothing recorded').to.eq(r.standing.owed))
    })
  })

  now('MKT-2d-04 [MKT-R20.3] [MKT-R15.6] the operator records a part payment with a note, then the rest: REMITTANCE lines, the books move by exactly what was paid (real UI)', () => {
    cy.loginAsOperator()
    tb('2400').then((c0) => row().then((r) => {
      const owed = Number(r.standing.owed)
      const part = Math.floor(owed / 2)
      payouts().within(() => {
        cy.get('.mkt-cod-amount').clear().type(String(part))
        cy.get('.mkt-cod-ref').type(`HBL-${run}-1`)
        cy.get('.mkt-cod-record').click()
        cy.get('.mkt-cod-msg').should('contain', 'Say why it paid less')       // the note is needed: refused, in words
        cy.get('.mkt-cod-note').type('Rider still holds one order\'s cash')
        cy.get('.mkt-cod-record').click()
      })
      cy.get('#mktCodMsg').should('contain', 'Payment recorded for')
      cy.get(`#mktCodList tr.mkt-cod-row[data-org="${org}"] .mkt-cod-owed`)
        .should(($td) => expect(Number($td.text().replace(/[^0-9.]/g, ''))).to.eq(Math.round((owed - part) * 100) / 100))
      cy.get(`#mktCodList tr.mkt-cod-row[data-org="${org}"]`).within(() => {
        cy.get('.mkt-cod-ref').type(`HBL-${run}-2`)                          // the amount box is filled with the rest
        cy.get('.mkt-cod-record').click()
      })
      cy.get('#mktCodMsg').should('contain', 'Payment recorded for')
      cy.get(`#mktCodList tr.mkt-cod-row[data-org="${org}"] .mkt-cod-owed`).should('have.text', '—')
      account().then((a) => {
        expect(Number(a.balance)).to.eq(0)
        const rem = a.entries.filter((e) => e.entryType === 'REMITTANCE').slice(0, 2)
        expect(rem.map((e) => Number(e.credit)).reduce((x, y) => x + y, 0)).to.eq(owed)
        expect(rem[1].memo, 'the part payment carries its note').to.contain('Rider still holds one order\'s cash')
        expect(rem[0].ref).to.match(/^RM-\d{6}$/)
      })
      // the journal leaves through the outbox after commit: read until it lands (bounded)
      const until = (n) => tb('2400').then((c1) => {
        if (Math.round((c1 - c0) * 100) === Math.round(owed * 100) || n === 0) {
          expect(Math.round((c1 - c0) * 100), '2400 came back by exactly what was paid').to.eq(Math.round(owed * 100))
          return
        }
        cy.wait(1000)
        until(n - 1)
      })
      until(15)
    }))
  })

  now('MKT-2d-05 [MKT-R20.3] the same payment key records once; nothing owed refuses a payment', () => {
    owing(phone(5)).then((commission) => {
      cy.loginAsOperator()
      const k = `once-${run}`
      const ref = `HBL-ONCE-${run}`                    // unique per run: the ledger keeps every earlier run's lines
      post(COD.pay, { organizationId: org, amount: commission, reference: ref, idempotencyKey: k }).then((r1) => {
        expect(ok(r1.body), JSON.stringify(r1.body)).to.eq(true)
        post(COD.pay, { organizationId: org, amount: commission, reference: ref, idempotencyKey: k }).then((r2) => {
          expect(ok(r2.body), 'a double click is the same payment').to.eq(true)
          expect(data(r2.body).entries.filter((e) => e.memo && e.memo.includes(ref)).length).to.eq(1)
          expect(Number(data(r2.body).balance)).to.eq(0)
        })
      })
      post(COD.pay, { organizationId: org, amount: 1, reference: 'HBL-X', idempotencyKey: `none-${run}` })
        .then((r) => expectRefused(r, 'This seller owes nothing for cash orders.'))
    })
  })

  now('MKT-2d-06 [MKT-R20.3] [MKT-R22.1] days to pay are 1 to 60; the stop switch stops only a seller that is late; the screens are the operator\'s', () => {
    cy.loginAsOperator()
    post(COD.settings, { codRemitDays: 0 }).then((r) => expectRefused(r, 'Days to pay for cash orders are 1 to 60.'))
    post(COD.settings, { codRemitDays: 61 }).then((r) => expectRefused(r, 'Days to pay for cash orders are 1 to 60.'))
    post(COD.settings, { codStopWhenOverdue: true }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).codStopWhenOverdue).to.eq(true)
      expect(data(r.body).codRemitDays, 'a field not sent is left as it is').to.eq(7)
    })
    asCustomer(phone(6))
    checkout(phone(6)).then((r) => {
      expect(ok(r.body), `owes nothing, or not late: cash still accepted: ${JSON.stringify(r.body)}`).to.eq(true)
      return rejectAs(data(r.body).orderNo)
    })
    cy.loginAsOperator()
    post(COD.settings, { codStopWhenOverdue: false })
    asSeller()
    get(COD.list).then((r) => expect(r.status === 403 || !ok(r.body), `refused for a tenant: ${r.status}`).to.eq(true))
    post(COD.pay, { organizationId: org, amount: 1, reference: 'x', idempotencyKey: `t-${run}` })
      .then((r) => expect(r.status === 403 || !ok(r.body), `refused for a tenant: ${r.status}`).to.eq(true))
    post(COD.settings, { codStopWhenOverdue: true })
      .then((r) => expect(r.status === 403 || !ok(r.body), `refused for a tenant: ${r.status}`).to.eq(true))
  })

  now('MKT-2d-07 [MKT-R20.3] (for phase 2) Seller A is left owing one cash order\'s commission', () => {
    square()
    owing(phone(7)).then((commission) => {
      cy.loginAsOperator()
      row().then((r) => expect(Number(r.standing.owed)).to.eq(commission))
    })
  })

  // ── phase 2: the stack restarted 12 days later ──────────────────────────────────────────────────────────

  later('MKT-2d-08 [MKT-R20.3] 12 days later the debt is overdue: first in the operator\'s list, and the seller is told (real UI)', () => {
    cy.loginAsOperator()
    row().then((r) => {
      expect(Number(r.standing.owed), 'phase 1 left Seller A owing').to.be.greaterThan(0)
      expect(r.standing.overdue).to.eq(true)
      expect(r.standing.codStopped, 'the stop switch is off').to.eq(false)
      get(COD.list).then((x) => expect(list(x.body)[0].standing.overdue, 'overdue sellers come first').to.eq(true))
      payouts().should('have.class', 'danger').find('.mkt-cod-payby').should('contain', 'Overdue. Owed since')
      statementBanner().should('be.visible').and('have.class', 'alert-danger')
        .and('contain', 'Overdue: please pay MaxTheService Rs').and('contain', `It was due by ${r.standing.payBy}.`)
        .and('not.contain', 'Customers cannot choose cash on delivery')
    })
  })

  later('MKT-2d-09 [MKT-R20.3] with the stop switch on, the late seller takes no cash orders, before anything is held; paying online still works', () => {
    cy.loginAsOperator()
    post(COD.settings, { codStopWhenOverdue: true }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    asCustomer(phone(8))
    checkout(phone(8)).then((r) => expectRefused(r, STOPPED))
    checkout(phone(8), 'CARD').then((r) => {
      expect(ok(r.body), `a card needs no seller cash: ${JSON.stringify(r.body)}`).to.eq(true)
      return rejectAs(data(r.body).orderNo)
    })
    statementBanner().should('contain', 'Customers cannot choose cash on delivery from you until you pay.')
    cy.loginAsOperator()
    row().then((r) => expect(r.standing.codStopped).to.eq(true))
  })

  later('MKT-2d-10 [MKT-R20.3] the seller pays (real UI): it is no longer late, and cash on delivery works again', () => {
    payouts().within(() => {
      cy.get('.mkt-cod-ref').type(`HBL-${run}-L`)
      cy.get('.mkt-cod-record').click()
    })
    cy.get('#mktCodMsg').should('contain', 'Payment recorded for')
    cy.get(`#mktCodList tr.mkt-cod-row[data-org="${org}"]`).should('not.have.class', 'danger').find('.mkt-cod-owed').should('have.text', '—')
    asCustomer(phone(9))
    checkout(phone(9)).then((r) => {
      expect(ok(r.body), `cash accepted again: ${JSON.stringify(r.body)}`).to.eq(true)
      return rejectAs(data(r.body).orderNo)
    })
    cy.loginAsOperator()
    post(COD.settings, { codStopWhenOverdue: false })
  })
})
