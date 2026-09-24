/**
 * PARK-CLAIM-1 — a resumed parked sale is gone from the shelf, so it can never be completed twice.
 *
 * THE DEFECT: resume read the cart (GET /resumeParked) and then removed it with a SEPARATE, SILENT call to
 * /deleteParked — which needs DELETE_PRIVILEGE. A USER-role cashier does not hold it, so for an ordinary
 * cashier the parked sale stayed in the list after every resume. Resumed and completed again, it became a
 * second invoice for the same goods under a fresh idempotency key: a duplicate sale.
 *
 * Signed in as the USER tier on purpose — an owner holds DELETE_PRIVILEGE and would pass with the defect in.
 * Resume goes through the till's own resumeParked(), the function the Resume button calls.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/park-claim.cy.js --headed --browser chrome
 */
const list = (b) => (b && (b.collection || b.object)) || []

const cart = (label) => ({
  label, itemCount: 1, total: 100,
  cart: {
    customer: { name: 'Claim Cust', contact: '0300CLAIM' },
    sales: [{ productId: 1, itemName: 'Held Item', quantity: 1, sellRate: 100, totalAmount: 100 }],
    tenders: [],
  },
})

const park = (label) =>
  cy.request({ method: 'POST', url: '/parkSale', body: cart(label), headers: { 'Content-Type': 'application/json' } })
    .then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      return r.body.object
    })

const parkedIds = () => cy.request('/parkedSales').then((r) => {
  expect(r.body.status, 'parkedSales read').to.eq('SUCCESS')   // a failed read must not pass as "gone"
  return list(r.body).map((p) => p.id)
})

describe('PARK-CLAIM-1 — resuming a parked sale takes it, once', () => {
  beforeEach(() => cy.loginAsTier('user', 'business'))

  it('⭐⭐ 1 — a USER-role cashier resumes a parked sale and it is no longer parked', () => {
    park(`Claim_${Date.now()}`).then((id) => {
      parkedIds().should('include', id)                      // precondition: it IS parked

      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.intercept('POST', '**/claimParked').as('claim')
      cy.window().then((w) => w.resumeParked(id))
      cy.wait('@claim').its('response.body.status').should('eq', 'SUCCESS')
      cy.window().its('data').should('have.length', 1)       // the basket is back in the cart

      // THE ASSERTION: nothing left on the shelf to resume and sell a second time.
      parkedIds().should('not.include', id)
    })
  })

  it('⭐ 2 — a second claim of the same parked sale is refused', () => {
    park(`Claim2_${Date.now()}`).then((id) => {
      cy.request({ method: 'POST', url: '/claimParked', form: true, body: { id } })
        .its('body.status').should('eq', 'SUCCESS')
      cy.request({ method: 'POST', url: '/claimParked', form: true, body: { id } })
        .its('body.status').should('eq', 'NOT_FOUND')
    })
  })

  it('3 — another cashier cannot claim my parked sale (anti-IDOR)', () => {
    park(`Claim3_${Date.now()}`).then((id) => {
      cy.loginAsOwner()                                      // same tenant, different cashier
      cy.request({ method: 'POST', url: '/claimParked', form: true, body: { id } })
        .its('body.status').should('eq', 'NOT_FOUND')
      cy.loginAsTier('user', 'business')
      parkedIds().should('include', id)                      // untouched for its owner
      cy.request({ method: 'POST', url: '/claimParked', form: true, body: { id } })   // tidy up
    })
  })
})
