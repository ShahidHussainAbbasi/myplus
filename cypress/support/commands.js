// Login helper used by all business module tests
Cypress.Commands.add('loginAsBusiness', (email = 'sameerfaisal29@gmail.com', password = '03453176525') => {
  cy.session([email, password], () => {
    cy.visit('/login')
    cy.get('input[name="username"]').type(email)
    cy.get('input[name="password"]').type(password)
    // Use click on the submit button — form.submit() fails because the input has name="submit"
    // which shadows the native form.submit() method
    cy.get('input[type="submit"]').click()
    cy.url().should('not.include', '/login')
  })
})

// Navigate to business dashboard and show a specific registration section
Cypress.Commands.add('openSection', (sectionValue) => {
  cy.visit('/businessDashboard')
  cy.get('#registrationType').select(sectionValue)
  cy.get(`#${sectionValue}`).should('be.visible')
})
