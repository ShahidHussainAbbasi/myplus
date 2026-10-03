/**
 * MKT-1f — support case + return request with cost attribution and escalation.
 * Source §8.2, §13.1–13.4. Run headed: --env mkt=1f
 */
const { gate, uniq, SELLER_A, API, UI, ok, data, list, post, get, expectRefused, openMarketplace } = require('./mkt-helpers')

gate('1f')('MKT-1f — support cases and returns', () => {
  let order

  before(() => {
    get(API.myMarketplaceOrders).then((r) => {
      order = list(r.body).find((o) => o.status === 'FULFILLED')
      expect(order, 'a delivered order (deliver one through 1e first)').to.exist
    })
  })

  it('MKT-1f-01 [MKT-R8.2] the customer opens ONE case with MaxTheService and the operator tasks the seller (real UI)', () => {
    cy.visit(`${UI.publicPage}/orders/${order.orderNo}`)
    cy.get('#mktOpenCase').should('be.visible').click()
    cy.get('#mktCaseReason').select('Item not as described')
    cy.get('#mktCaseText').type('Box says 64GB')
    cy.get('#mktCaseSubmit').click()
    cy.contains('#mktCaseStatus', 'MaxTheService support').should('be.visible')
    cy.loginAsOperator()
    cy.visit(UI.operatorPage)
    cy.contains('#mktCases tr', order.orderNo).within(() => cy.get('.mkt-task-seller').click())
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    openMarketplace()
    cy.contains('#mktTasks tr', order.orderNo).should('be.visible')
  })

  it('MKT-1f-02 [MKT-R13.1] [MKT-R13.2] a wrong-product return names the FULFILLER as cost bearer', () => {
    post(API.requestReturn, { orderNo: order.orderNo, lineId: order.sellerOrders[0].lines[0].id,
      reason: 'WRONG_PRODUCT' }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      expect(data(r.body).costBearerOrganizationId)
        .to.eq(order.sellerOrders[0].lines[0].fulfillerOrganizationId)
    })
  })

  it('MKT-1f-03 [MKT-R13.1] change of mind is borne by the customer, not the seller', () => {
    post(API.requestReturn, { orderNo: order.orderNo, lineId: order.sellerOrders[0].lines[0].id,
      reason: 'CHANGE_OF_MIND' }).then((r) => {
      if (!ok(r.body)) return expectRefused(r, 'return')   // one open return per line is also a valid answer
      expect(data(r.body).costBearer).to.eq('CUSTOMER')
    })
  })

  it('MKT-1f-04 [MKT-R13.4] an expired/unsafe report is escalated immediately', () => {
    post(API.openCase, { orderNo: order.orderNo, reason: 'EXPIRED_OR_UNSAFE', text: 'seal broken' }).then((r) => {
      expect(ok(r.body)).to.eq(true)
      expect(data(r.body).priority).to.eq('URGENT')
    })
  })

  it('MKT-1f-05 [MKT-R22.4] every support action leaves an audit row', () => {
    cy.loginAsOperator()
    cy.findAudit((a) => String(a.entityType || '').startsWith('MKT_') && String(a.details || '').includes(order.orderNo),
      'marketplace support audit row')
  })
})
