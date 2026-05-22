/**
 * Business Dashboard — full page, navigation, and section rendering tests
 */

describe('Business Dashboard — Page Load', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('should load the business dashboard', () => {
    cy.url().should('include', '/businessDashboard')
    cy.get('body').should('be.visible')
  })

  it('should show the Register dropdown with Company, Vender, Customer options', () => {
    cy.get('#registrationType').should('exist')
    cy.get('#registrationType option[value="CompanyDiv"]').should('exist')
    cy.get('#registrationType option[value="VenderDiv"]').should('exist')
    cy.get('#registrationType option[value="CustomerDiv"]').should('exist')
  })

  it('should show the Purchase and Sale dropdowns in nav', () => {
    cy.get('#purchaseType').should('exist')
    cy.get('#sellType').should('exist')
  })

  it('should start with all formDivs hidden', () => {
    cy.get('#CompanyDiv').should('not.be.visible')
    cy.get('#VenderDiv').should('not.be.visible')
    cy.get('#CustomerDiv').should('not.be.visible')
  })
})

describe('Business Dashboard — Navigation Sections', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('selecting Company from Register dropdown shows CompanyDiv', () => {
    cy.get('#registrationType').select('CompanyDiv')
    cy.get('#CompanyDiv').should('be.visible')
    cy.get('#companyName').should('be.visible')
  })

  it('selecting Vender shows VenderDiv', () => {
    cy.get('#registrationType').select('VenderDiv')
    cy.get('#VenderDiv').should('be.visible')
    cy.get('#venderName').should('be.visible')
  })

  it('selecting Customer shows CustomerDiv', () => {
    cy.get('#registrationType').select('CustomerDiv')
    cy.get('#CustomerDiv').should('be.visible')
    cy.get('#name').should('be.visible')
    cy.get('#contact').should('be.visible')
  })

  it('selecting Purchase section shows purchaseDiv', () => {
    cy.get('#purchaseType').select('purchaseDiv')
    cy.get('#purchaseDiv').should('be.visible')
  })

  it('selecting Sale section shows sellDiv', () => {
    cy.get('#sellType').select('sellDiv')
    cy.get('#sellDiv').should('be.visible')
  })

  it('switching between sections hides previous and shows new', () => {
    cy.get('#registrationType').select('CompanyDiv')
    cy.get('#CompanyDiv').should('be.visible')

    cy.get('#registrationType').select('CustomerDiv')
    cy.get('#CustomerDiv').should('be.visible')
    cy.get('#CompanyDiv').should('not.be.visible')
  })
})

describe('Business Dashboard — Table Rendering', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('Company table has thead with column headers', () => {
    cy.get('#registrationType').select('CompanyDiv')
    cy.get('#tableCompany thead').should('exist')
    cy.get('#tableCompany thead th').should('have.length.above', 0)
  })

  it('Vender table has thead', () => {
    cy.get('#registrationType').select('VenderDiv')
    cy.get('#tableVender thead').should('exist')
  })

  it('Customer table has thead', () => {
    cy.get('#registrationType').select('CustomerDiv')
    cy.get('#tableCustomer thead').should('exist')
  })

  it('DataTables wrapper is rendered for sell table after section loads', () => {
    cy.get('#sellType').select('sellDiv')
    // DataTables wraps every initialized table in a <div id="<tableId>_wrapper">
    cy.get('#tableSell_wrapper', { timeout: 10000 }).should('exist')
  })
})

describe('Business Dashboard — AJAX Data Load', () => {
  it('opening CompanyDiv triggers getUserCompany AJAX call', () => {
    // Set up intercept BEFORE visiting the page
    cy.loginAsBusiness()
    cy.intercept('GET', /\/getUserCompany/).as('getCompany')
    cy.visit('/businessDashboard')
    cy.get('#registrationType').select('CompanyDiv')
    cy.wait('@getCompany', { timeout: 10000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })

  it('opening VenderDiv triggers getUserVender AJAX call', () => {
    cy.loginAsBusiness()
    cy.intercept('GET', /\/getUserVender/).as('getVender')
    cy.visit('/businessDashboard')
    cy.get('#registrationType').select('VenderDiv')
    cy.wait('@getVender', { timeout: 10000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })

  it('opening CustomerDiv triggers getUserCustomer AJAX call', () => {
    cy.loginAsBusiness()
    cy.intercept('GET', /\/getUserCustomer/).as('getCustomer')
    cy.visit('/businessDashboard')
    cy.get('#registrationType').select('CustomerDiv')
    cy.wait('@getCustomer', { timeout: 10000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })
})

describe('Business Dashboard — Form Reset Buttons', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('Company Reset button clears form fields', () => {
    cy.get('#registrationType').select('CompanyDiv')
    cy.get('#companyName').type('Temp Name')
    cy.get('#resetCompanyItem').click()
    cy.get('#companyName').should('have.value', '')
  })

  it('Vender Reset button clears form', () => {
    cy.get('#registrationType').select('VenderDiv')
    cy.get('#venderName').type('Temp Vender')
    cy.get('#resetVender').click()
    cy.get('#venderName').should('have.value', '')
  })

  it('Customer Reset button clears form', () => {
    cy.get('#registrationType').select('CustomerDiv')
    cy.get('#name').type('Temp Customer')
    cy.get('#resetCustomer').click()
    cy.get('#name').should('have.value', '')
  })
})

describe('Business Dashboard — Global Error Element', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('globalError div is hidden on page load', () => {
    cy.get('#globalError').should('have.css', 'display', 'none')
  })
})
