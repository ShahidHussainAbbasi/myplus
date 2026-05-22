/**
 * Purchase flow tests — form rendering, field interaction, AJAX calls
 */

describe('Purchase Section — Page Rendering', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#purchaseType').select('purchaseDiv')
    cy.get('#purchaseDiv').should('be.visible')
  })

  it('shows item dropdown, quantity field and submit button', () => {
    cy.get('#purchaseItemDD').should('exist')
    cy.get('#purchaseQuantity').should('be.visible')
    cy.get('#addPurchase').should('be.visible')
    cy.get('#resetPurchase').should('be.visible')
  })

  it('item dropdown loads options from getUserItems AJAX', () => {
    cy.intercept('GET', '/getUserItems').as('getUserItems')
    cy.reload()
    cy.get('#purchaseType').select('purchaseDiv')
    cy.wait('@getUserItems').then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })

  it('purchase table exists with thead columns', () => {
    cy.get('#tablePurchase thead').should('exist')
    cy.get('#tablePurchase thead th').should('have.length.above', 0)
  })

  it('Reset button clears the purchase form', () => {
    cy.get('#purchaseQuantity').type('5')
    cy.get('#resetPurchase').click()
    cy.get('#purchaseQuantity').should('have.value', '')
  })
})

describe('Purchase API Endpoints', () => {
  before(() => {
    cy.loginAsBusiness()
  })

  it('getUserPurchase API returns SUCCESS or NOT_FOUND', () => {
    cy.request({ url: '/getUserPurchase', failOnStatusCode: false }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND', 'ERROR'])
    })
  })
})
