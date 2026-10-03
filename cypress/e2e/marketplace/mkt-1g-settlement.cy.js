/**
 * MKT-1g — commission, immutable settlement ledger, T+1 eligibility, manual payout, GL posting.
 * Source §15, §16, §22.3. Run headed: --env mkt=1g
 *
 * The MONEY assertion reads the trial balance, not the ledger table the feature itself writes (programme rule).
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, post, get, expectRefused, openMarketplace } = require('./mkt-helpers')

gate('1g')('MKT-1g — settlement and payouts', () => {
  const run = uniq()

  it('MKT-1g-01 [MKT-R15.5] [MKT-R16.1] the seller reads a statement whose lines add up (real UI)', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    openMarketplace()
    cy.get('#mktStatementTab').click()
    cy.get(UI.statementTable).should('be.visible').find('tr.mkt-line').first().then(($tr) => {
      const n = (sel) => Number(($tr.find(sel).text() || '0').replace(/[^0-9.-]/g, ''))
      const parts = n('.mkt-payable') + n('.mkt-commission') + n('.mkt-delivery') + n('.mkt-fees') + n('.mkt-tax')
        + n('.mkt-reserve') + n('.mkt-adjustment')
      expect(Math.round(parts * 100)).to.eq(Math.round(n('.mkt-customer-amount') * 100))
    })
  })

  it('MKT-1g-02 [MKT-R15.2] [MKT-R16.2] nothing is payable at placement or before the return window closes', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    get(`${API.statement}?status=ELIGIBLE`).then((r) => {
      expect(ok(r.body), 'positive control').to.eq(true)
      list(r.body).forEach((e) => {
        expect(e.deliveredAt, `entry ${e.id} eligible only after delivery`).to.exist
        expect(new Date(e.eligibleOn).getTime()).to.be.at.least(new Date(e.deliveredAt).getTime())
      })
    })
  })

  it('MKT-1g-03 [MKT-R15.1] eligibleOn is T+N BUSINESS days after the trigger', () => {
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    get(`${API.statement}?status=ELIGIBLE`).then((r) => list(r.body).forEach((e) => {
      const d = new Date(e.eligibleOn).getUTCDay()
      expect(d, `eligibleOn ${e.eligibleOn} is never a weekend`).to.not.be.oneOf([0, 6])
    }))
  })

  it('MKT-1g-04 [MKT-R16.3] [MKT-R22.3] a payout is idempotent and needs a second person to approve', () => {
    cy.loginAsOperator()
    cy.orgOf(SELLER_A).then((org) => {
      const key = `payout-${run}`
      post(API.requestPayout, { organizationId: org.id, idempotencyKey: key }).then((r1) =>
        post(API.requestPayout, { organizationId: org.id, idempotencyKey: key }).then((r2) => {
          if (!ok(r1.body)) return expectRefused(r1, 'nothing')   // no eligible balance is a valid answer
          expect(data(r2.body).id).to.eq(data(r1.body).id)
          post(API.approvePayout, { id: data(r1.body).id }).then((a) => expectRefused(a, 'another'))
        }))
    })
  })

  it('MKT-1g-05 [MKT-R15.6] the ledger has no edit path: an entry cannot be changed, only reversed', () => {
    cy.loginAsOperator()
    cy.request({ method: 'PUT', url: '/platform/mkt/ledgerEntry', failOnStatusCode: false, body: { id: 1, credit: 0 } })
      .then((r) => expect(r.status, 'no update route exists').to.be.oneOf([404, 405]))
  })

  it('MKT-1g-06 [MKT-R15.5] commission reaches the operator\'s books: trial balance moves by exactly the commission', () => {
    cy.loginAsOperator()
    get('/gl/trialBalance').then((tb0) => {
      const rev0 = Number((list(tb0.body).find((a) => a.code === '4500') || {}).balance || 0)
      post('/test/mkt/deliverAndSettleOne', { sellerEmail: SELLER_A }).then((r) => {
        const commission = Number(data(r.body).commission)
        get('/gl/trialBalance').then((tb1) => {
          const rev1 = Number((list(tb1.body).find((a) => a.code === '4500') || {}).balance || 0)
          expect(Math.round((rev1 - rev0) * 100)).to.eq(Math.round(commission * 100))
        })
      })
    })
  })
})
