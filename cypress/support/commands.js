// Login helper used by all business module tests
Cypress.Commands.add('loginAsBusiness', (email = 'sameerfaisal29@gmail.com', password = '03453176525') => {
  cy.session([email, password], () => {
    cy.visit('/login')
    cy.get('input[name="username"]').type(email)
    cy.get('input[name="password"]').type(password)
    // Login redesign uses <button id="loginSubmit" type="submit"> (not <input type="submit">)
    cy.get('#loginSubmit').click()
    cy.url().should('not.include', '/login')
  })
})

// Navigate to business dashboard and show a specific registration section (Company, Vender, Customer, itemDiv)
Cypress.Commands.add('openSection', (sectionValue) => {
  cy.visit('/businessDashboard')
  // Nav selects are off-screen — force:true bypasses the viewport-center check
  cy.get('#registrationType').select(sectionValue, { force: true })
  cy.get(`#${sectionValue}`).should('be.visible')
})

// Navigate to a sell sub-section (sellDiv, SRDiv)
Cypress.Commands.add('openSellSection', (sectionValue) => {
  cy.visit('/businessDashboard')
  cy.get('#sellType').select(sectionValue, { force: true })
  cy.get(`#${sectionValue}`).should('be.visible')
})

// Navigate to a purchase sub-section (purchaseDiv)
Cypress.Commands.add('openPurchaseSection', (sectionValue) => {
  cy.visit('/businessDashboard')
  cy.get('#purchaseType').select(sectionValue, { force: true })
  cy.get(`#${sectionValue}`).should('be.visible')
})
