/**
 * Business Dashboard navigation tests
 * Tests the subnav menu clicks and section switching via snavGo() inline onclick calls
 */

describe('Subnav — Register Menu', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('Register subnav exists and has dropdown list', () => {
    cy.get('#snavRegister').should('exist')
    cy.get('#snavRegister .snav-menu').should('exist')
  })

  it('clicking Register > Company shows CompanyDiv', () => {
    cy.get('#registrationType').select('CompanyDiv', { force: true })
    cy.get('#CompanyDiv').should('be.visible')
  })

  it('clicking Register > Vender shows VenderDiv', () => {
    cy.get('#registrationType').select('VenderDiv', { force: true })
    cy.get('#VenderDiv').should('be.visible')
  })

  it('clicking Register > Customer shows CustomerDiv', () => {
    cy.get('#registrationType').select('CustomerDiv', { force: true })
    cy.get('#CustomerDiv').should('be.visible')
  })

  it('clicking Register > Item shows itemDiv', () => {
    cy.get('#registrationType').select('itemDiv', { force: true })
    cy.get('#itemDiv').should('be.visible')
  })
})

describe('Subnav — Purchase Menu', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('Purchase subnav exists', () => {
    cy.get('#snavPurchase').should('exist')
    cy.get('#snavPurchase .snav-menu').should('exist')
  })

  it('clicking Purchase > New Purchase shows purchaseDiv', () => {
    cy.get('#purchaseType').select('purchaseDiv', { force: true })
    cy.get('#purchaseDiv').should('be.visible')
  })
})

describe('Subnav — Sale Menu', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('Sale subnav exists', () => {
    cy.get('#snavSell').should('exist')
    cy.get('#snavSell .snav-menu').should('exist')
  })

  it('clicking Sale > New Sale shows sellDiv', () => {
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
  })

  it('clicking Sale > Sale Detail Report shows SRDiv', () => {
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#SRDiv').should('be.visible')
  })
})

describe('Subnav — Dashboard Home Button', () => {
  it('clicking Dashboard button shows DashboardDiv', () => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#registrationType').select('CompanyDiv', { force: true })
    cy.get('#CompanyDiv').should('be.visible')

    // Dashboard home button (glyphicon-dashboard closest a)
    cy.get('.snav-home-btn').click()
    cy.get('#DashboardDiv').should('be.visible')
    cy.get('#CompanyDiv').should('not.be.visible')
  })
})

describe('Section Isolation — Only One FormDiv Visible', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('only one formDiv is visible at a time — CompanyDiv', () => {
    cy.get('#registrationType').select('CompanyDiv', { force: true })
    cy.get('.formDiv:visible').should('have.length', 1)
    cy.get('#CompanyDiv').should('be.visible')
  })

  it('only one formDiv is visible at a time — sellDiv', () => {
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('.formDiv:visible').should('have.length', 1)
    cy.get('#sellDiv').should('be.visible')
  })

  it('only one formDiv is visible at a time — purchaseDiv', () => {
    cy.get('#purchaseType').select('purchaseDiv', { force: true })
    cy.get('.formDiv:visible').should('have.length', 1)
    cy.get('#purchaseDiv').should('be.visible')
  })
})
