/**
 * MKT-2f — settlement reports and the bank-holiday calendar. Source R20.3 ("settlement reports"), R15.1 (payable on a
 * BUSINESS day), R15.6 (the ledger is the only record of money), R22.1 (operator only).
 * Contract: microservices/docs/slices/mkt-2f-settlement-reports.md
 *
 * Every report figure is checked against the ledger it is read from (the account balance, the entries) or as a
 * DIFFERENCE from the report read just before the action, because the stack's sellers carry every earlier gate's money.
 * Every holiday the gate adds is removed again; before the first case, any left by a failed earlier run is removed.
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, post, get, expectRefused, seedPolicies, publishOffer, openMarketplace } =
  require('./mkt-helpers')

const REP = {
  operator: (q = '') => `/platform/mkt/settlementReport${q}`,
  mine: (q = '') => `/mkt/settlementReport${q}`,
  accounts: '/platform/mkt/settlementAccounts',
  adjust: '/platform/mkt/adjustLedger',          // {organizationId, amount, reason, idempotencyKey}
  holidays: '/platform/mkt/holidays',
  addHoliday: '/platform/mkt/addHoliday',        // {date, name}
  removeHoliday: '/platform/mkt/removeHoliday',  // {date}
}
const COLS = ['opening', 'sales', 'commission', 'feesAndTax', 'reserve', 'refunds', 'corrections', 'collectedBySeller',
  'remitted', 'paidOut']
const PRICE = 52000
const NAME = 'Gate 2f'

/** ISO yyyy-mm-dd of a Date, in UTC (the dates below are built in UTC). */
const iso = (d) => d.toISOString().substring(0, 10)
const addDays = (s, n) => { const d = new Date(`${s}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return iso(d) }
const weekend = (s) => [0, 6].includes(new Date(`${s}T00:00:00Z`).getUTCDay())
const nextWeekday = (s) => { let d = s; while (weekend(d)) d = addDays(d, 1); return d }
const cents = (v) => Math.round(Number(v || 0) * 100)
const sum = (r) => COLS.reduce((t, c) => t + cents(r[c]), 0)

gate('2f')('MKT-2f — settlement reports and bank holidays', () => {
  const run = uniq()
  let today, orgA

  const asSeller = () => cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
  const report = (q = '') => {
    cy.loginAsOperator()
    return get(REP.operator(q)).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  }
  const rowOf = (rep, org) => rep.rows.find((x) => x.organizationId === org)
  const holidays = () => {
    cy.loginAsOperator()
    return get(REP.holidays).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  }

  before(() => {
    report().then((v) => { today = v.to })
    asSeller()
    get(REP.mine()).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      orgA = data(r.body).rows[0].organizationId
    })
    holidays().then((hs) => hs.filter((h) => h.removable && h.name.startsWith(NAME))
      .forEach((h) => post(REP.removeHoliday, { date: h.date })))
  })

  after(() => {
    holidays().then((hs) => hs.filter((h) => h.removable && h.name.startsWith(NAME))
      .forEach((h) => post(REP.removeHoliday, { date: h.date })))
  })

  it('MKT-2f-01 [MKT-R20.3] the operator opens the report: this month, one row per seller, every row adds up (real UI)', () => {
    let api
    report().then((v) => { api = v })
    cy.visit(UI.operatorPage)
    cy.intercept('GET', '**/platform/mkt/settlementReport*').as('rep')
    cy.get('#platMktPayoutsBtn').should('be.visible').click()
    cy.wait('@rep').its('response.body.data.from').should('match', /-01$/)
    cy.get('#mktReportBox').scrollIntoView().should('be.visible')
    cy.get('#mktRepFrom').should('have.value', `${today.substring(0, 8)}01`)
    cy.get('#mktRepTo').should('have.value', today)
    cy.then(() => {
      expect(api.rows.length, 'a positive control: the stack has sellers with a ledger').to.be.at.least(1)
      cy.get('#mktReportList .mkt-rep-row').should('have.length', api.rows.length)
      api.rows.forEach((r) => {
        expect(sum(r), `seller ${r.organizationId}: opening + the columns = closing`).to.eq(cents(r.closing))
        cy.get(`#mktReportList .mkt-rep-row[data-org="${r.organizationId}"]`).within(() => {
          const shown = (v) => (cents(v) ? Number(v).toLocaleString('en-US', { maximumFractionDigits: 2 }) : '—')
          cy.get('.mkt-rep-closing').should('have.text', shown(r.closing))
          cy.get('.mkt-rep-sales').should('have.text', shown(r.sales))
          cy.get('.mkt-rep-commission').should('have.text', shown(r.commission))
          cy.get('.mkt-rep-lines').should('have.text', String(r.lines))
        })
      })
      COLS.concat(['closing']).forEach((c) => {
        expect(api.rows.reduce((t, r) => t + cents(r[c]), 0), `the total of ${c}`).to.eq(cents(api.totals[c]))
      })
      cy.get('#mktReportList .mkt-rep-total').should('contain', 'All sellers')
      // Closing is inside the visible part of the table, not past its scroll edge
      cy.get('#mktReportList .mkt-rep-total .mkt-rep-closing').should(($c) => {
        const box = $c.closest('.table-responsive')[0].getBoundingClientRect()
        expect($c[0].getBoundingClientRect().right, 'Closing is on screen').to.be.at.most(box.right)
      })
    })
  })

  it('MKT-2f-02 [MKT-R20.3] [MKT-R15.6] the report agrees with the ledger: closing at the end = each seller\'s balance', () => {
    // to a day after every entry on this stack (earlier runs wrote some under a later clock), so nothing is cut off
    let bal
    cy.loginAsOperator()
    get(REP.accounts).then((r) => { expect(ok(r.body)).to.eq(true); bal = data(r.body) })
    cy.then(() => report(`?from=${addDays(today, -300)}&to=${addDays(today, 60)}`)).then((v) => {
      expect(bal.length, 'a positive control: sellers with a balance').to.be.at.least(1)
      bal.forEach((a) => {
        const r = rowOf(v, a.organizationId)
        expect(r, `seller ${a.organizationId} is on the report`).to.exist
        expect(cents(r.closing), `${a.sellerName}: closing = balance`).to.eq(cents(a.balance))
      })
    })
    // and the period joins up: one period's closing is the next one's opening
    report(`?from=${addDays(today, -20)}&to=${addDays(today, -10)}`).then((p1) => {
      report(`?from=${addDays(today, -9)}&to=${today}`).then((p2) => {
        p1.rows.forEach((r) => expect(cents(rowOf(p2, r.organizationId).opening), `seller ${r.organizationId}`).to.eq(cents(r.closing)))
      })
    })
  })

  it('MKT-2f-03 [MKT-R20.3] a correction moves exactly Corrections and Closing, by its amount, and nothing else', () => {
    let before, after
    report().then((v) => { before = rowOf(v, orgA) })
    cy.loginAsOperator()
    post(REP.adjust, { organizationId: orgA, amount: 12.34, reason: `gate 2f ${run}`, idempotencyKey: `2f-${run}-a` })
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    report().then((v) => { after = rowOf(v, orgA) })
    cy.then(() => {
      expect(cents(after.corrections) - cents(before.corrections)).to.eq(1234)
      expect(cents(after.closing) - cents(before.closing)).to.eq(1234)
      COLS.filter((c) => c !== 'corrections').forEach((c) => expect(cents(after[c]), c).to.eq(cents(before[c])))
      expect(after.lines).to.eq(before.lines)
    })
    // cleanup: the opposite correction, a new line as every correction is (R15.6)
    post(REP.adjust, { organizationId: orgA, amount: -12.34, reason: `gate 2f cleanup ${run}`, idempotencyKey: `2f-${run}-b` })
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    report().then((v) => expect(cents(rowOf(v, orgA).closing), 'back where it was').to.eq(cents(before.closing)))
  })

  it('MKT-2f-04 [MKT-R20.3] a period backwards, or longer than 366 days, is refused in a sentence (real UI)', () => {
    cy.loginAsOperator()
    get(REP.operator(`?from=${addDays(today, -400)}&to=${today}`)).then((r) => expectRefused(r, 'Choose a period of at most 366 days.'))
    get(REP.operator(`?from=${addDays(today, -365)}&to=${today}`)).then((r) => expect(ok(r.body), '366 days exactly is allowed').to.eq(true))
    cy.visit(UI.operatorPage)
    cy.get('#platMktPayoutsBtn').click()
    cy.get('#mktReportList .mkt-rep-row').should('have.length.at.least', 1)
    cy.get('#mktRepFrom').clear().type(today)
    cy.get('#mktRepTo').clear().type(addDays(today, -1))
    cy.get('#mktRepShow').click()
    cy.get('#mktRepMsg').should('have.text', 'The start of the period is after its end.')
    cy.get('#mktReportList .mkt-rep-row').should('have.length', 0)
    cy.get('#mktRepCsv').should('be.disabled')
  })

  it('MKT-2f-05 [MKT-R20.3] [MKT-R22.1] a seller reads only its own row, the operator\'s figures; the operator report is refused to it (real UI)', () => {
    let op
    report().then((v) => { op = rowOf(v, orgA) })
    asSeller()
    get(REP.mine()).then((r) => {
      expect(ok(r.body)).to.eq(true)
      const v = data(r.body)
      expect(v.rows.map((x) => x.organizationId)).to.deep.eq([orgA])
      COLS.concat(['closing']).forEach((c) => expect(cents(v.rows[0][c]), c).to.eq(cents(op[c])))
      expect(cents(v.totals.closing), 'its totals are its own').to.eq(cents(op.closing))
    })
    get(REP.operator()).then((r) => expectRefused(r))
    get(REP.holidays).then((r) => expectRefused(r))
    openMarketplace()
    cy.intercept('GET', '**/mkt/settlementReport*').as('mine')
    cy.get('#mktStatementTab').click()
    cy.wait('@mine')
    cy.get('#mktMyReport').scrollIntoView().should('be.visible')
    cy.then(() => {
      // the end is said as who owes whom, with the amount unsigned
      cy.get('#mktMyReport .mkt-myrep-closing td').eq(0).should('have.text', Number(op.closing) < 0 ? 'You owe MaxTheService at the end' : 'Owed to you at the end')
      cy.get('#mktMyReport .mkt-myrep-closing td').eq(1).should('have.text', Math.abs(Number(op.closing)).toLocaleString('en-US', { maximumFractionDigits: 2 }))
      cy.get('#mktMyReport .mkt-myrep-opening td').eq(0).should('have.text', Number(op.opening) < 0 ? 'You owed MaxTheService at the start' : 'Owed to you at the start')
      cy.get('#mktMyRepMsg').should('have.text', `${op.lines} sale line(s) settled in this period.`)
    })
  })

  it('MKT-2f-06 [MKT-R20.3] Download CSV gives the table on screen: one line per seller and the total', () => {
    let api
    report().then((v) => { api = v })
    cy.visit(UI.operatorPage)
    cy.get('#platMktPayoutsBtn').click()
    cy.get('#mktRepCsv').should('not.be.disabled').click()
    cy.then(() => cy.readFile(`cypress/downloads/settlement-report-${api.from}-to-${api.to}.csv`, 'utf8')).then((text) => {
      const lines = text.replace(/^﻿/, '').trim().split(/\r\n/)
      expect(lines[0]).to.eq('Seller ID,Seller,Closing,Opening,Sales,Commission,Fees and tax,Reserve,Refunds,Corrections,'
        + 'Cash kept by seller,Paid in by seller,Paid out,Lines')
      expect(lines.length, 'a header, a line per seller, the total').to.eq(api.rows.length + 2)
      const mine = lines.find((l) => l.startsWith(`${orgA},`))
      const r = rowOf(api, orgA)
      const cells = (l) => l.split(',')
      // no seller name in the seeded data holds a comma, so a plain split is exact here
      expect([cells(mine)[2], cells(mine)[13]]).to.deep.eq([Number(r.closing).toFixed(2), String(r.lines)])
      const tot = cells(lines[lines.length - 1])
      expect([tot[2], tot[13]]).to.deep.eq([Number(api.totals.closing).toFixed(2), String(api.totals.lines)])
    })
  })

  it('MKT-2f-07 [MKT-R15.1] [MKT-R22.1] the operator lists a future bank holiday; today, a weekend, no name, twice are refused (real UI)', () => {
    const day = nextWeekday(addDays(today, 200 + Math.floor(Math.random() * 100)))
    let sat = addDays(today, 1)
    while (new Date(`${sat}T00:00:00Z`).getUTCDay() !== 6) sat = addDays(sat, 1)
    cy.loginAsOperator()
    post(REP.addHoliday, { date: today, name: NAME }).then((r) =>
      expectRefused(r, 'A holiday can be added only for a day after today: lines already payable keep their day.'))
    post(REP.addHoliday, { date: sat, name: NAME }).then((r) => expectRefused(r, 'That day is a weekend: nothing is paid on it anyway.'))
    asSeller()
    post(REP.addHoliday, { date: day, name: NAME }).then((r) => expectRefused(r))
    cy.loginAsOperator()
    cy.visit(UI.operatorPage)
    cy.get('#platMktPayoutsBtn').click()
    cy.get('#mktHolidayBox').scrollIntoView().should('be.visible')
    cy.get('#mktHolDate').type(day)
    cy.get('#mktHolAdd').click()
    cy.get('#mktHolMsg').should('have.text', 'Give the holiday a name, for example "Eid ul-Fitr".')
    cy.get('#mktHolName').type(`${NAME} ${run}`)
    cy.get('#mktHolAdd').click()
    cy.get('#mktHolMsg').should('have.text', 'Holiday added. Lines due that day are paid on the next business day.')
    cy.get(`#mktHolidayList .mkt-holiday[data-date="${day}"]`).should('contain', `${NAME} ${run}`).find('.mkt-holiday-remove').should('be.visible')
    cy.get('#mktHolDate').type(day)
    cy.get('#mktHolName').type(NAME)
    cy.get('#mktHolAdd').click()
    cy.get('#mktHolMsg').should('have.text', 'That day is already a holiday.')
    cy.get(`#mktHolidayList .mkt-holiday[data-date="${day}"] .mkt-holiday-remove`).click()
    cy.get('#mktHolMsg').should('have.text', 'Holiday removed.')
    cy.get(`#mktHolidayList .mkt-holiday[data-date="${day}"]`).should('not.exist')
    holidays().then((hs) => expect(hs.map((h) => h.date)).not.to.include(day))
  })

  it('MKT-2f-08 [MKT-R15.1] a holiday on a line\'s payable day moves it to the next business day; removing it moves it back', () => {
    let offer, orderNo, payable
    seedPolicies(`${run}h`, { returnDays: 3 }).then((p) =>
      publishOffer(SELLER_A, { run: `${run}h`, price: PRICE, qty: 5, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
      .then((o) => { offer = o })
    cy.then(() => {
      cy.clearCookies()
      cy.visit(UI.publicPage)
      post(API.checkout, { lines: [{ offerId: offer.offerId, quantity: 1, expectedPrice: PRICE }], customerName: 'Ali',
        customerPhone: `0316${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`, address: '1 Clifton', city: 'Karachi',
        idempotencyKey: `m2f-${run}` }).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); orderNo = data(r.body).orderNo })
    })
    asSeller()
    cy.then(() => get(`${API.incomingOrders}?status=OFFERED&size=100`)).then((r) => {
      const so = list(r.body).find((x) => x.orderNo === orderNo)
      return post(API.acceptOrder, { id: so.id, version: so.version })
    }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`)).then((r) => {
      const store = list(r.body).find((x) => x.orderNo === orderNo).storeOrderId
      post('/updateOrderStatus', { id: store, status: 'PACKED' })
      get(`/getOrder?id=${store}`).then((g) => {
        const l = data(g.body).items[0]
        post('/shipOrder', { id: store, lines: [{ orderItemId: l.id, quantity: l.quantity }], carrier: 'Own rider', trackingNumber: `RD-${run}` })
      })
      post('/updateOrderStatus', { id: store, status: 'DELIVERED' }).then((u) => expect(ok(u.body), JSON.stringify(u.body)).to.eq(true))
    })
    const line = () => {
      asSeller()
      return get(`${API.statement}?size=100`).then((r) => list(r.body).find((x) => x.orderNo === orderNo))
    }
    line().then((l) => {
      expect(l, 'the delivered line is on the statement').to.exist
      expect(l.eligibleOn > today, `its payable day ${l.eligibleOn} is after today, ${today}: 3 return days`).to.eq(true)
      payable = l.eligibleOn
    })
    cy.loginAsOperator()
    cy.then(() => post(REP.addHoliday, { date: payable, name: `${NAME} payable ${run}` }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    line().then((l) => expect(l.eligibleOn, 'the next business day').to.eq(nextWeekday(addDays(payable, 1))))
    cy.loginAsOperator()
    cy.then(() => post(REP.removeHoliday, { date: payable })).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    line().then((l) => expect(l.eligibleOn, 'back to its day').to.eq(payable))
    // cleanup: none needed. The holiday is removed above; the line stays on the statement as every delivered sale does.
  })
})
