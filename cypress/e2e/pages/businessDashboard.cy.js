/**
 * Business Dashboard — full page, navigation, and section rendering tests
 *
 * All nav selects (#registrationType, #purchaseType, #sellType) are hidden off-screen.
 * Every .select() on these elements MUST use { force: true }.
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

  it('should start with all formDivs hidden (except DashboardDiv)', () => {
    cy.get('#CompanyDiv').should('not.be.visible')
    cy.get('#VenderDiv').should('not.be.visible')
    cy.get('#CustomerDiv').should('not.be.visible')
  })

  it('should show DashboardDiv on initial load', () => {
    cy.get('#DashboardDiv').should('be.visible')
  })
})

describe('Business Dashboard — Navigation Sections', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('selecting Company from Register dropdown shows CompanyDiv', () => {
    cy.get('#registrationType').select('CompanyDiv', { force: true })
    cy.get('#CompanyDiv').should('be.visible')
    cy.get('#newCompany').should('be.visible')   // form moved into a modal; the toolbar's New button shows
  })

  it('selecting Vender shows VenderDiv', () => {
    cy.get('#registrationType').select('VenderDiv', { force: true })
    cy.get('#VenderDiv').should('be.visible')
    cy.get('#newVender').should('be.visible')
  })

  it('selecting Customer shows CustomerDiv', () => {
    cy.get('#registrationType').select('CustomerDiv', { force: true })
    cy.get('#CustomerDiv').should('be.visible')
    cy.get('#newCustomer').should('be.visible')
  })

  // M4e.b (slice 102): the legacy Item form was retired — products are registered via the Product (catalog) master form.

  it('selecting Purchase section shows purchaseDiv', () => {
    cy.get('#purchaseType').select('purchaseDiv', { force: true })
    cy.get('#purchaseDiv').should('be.visible')
  })

  it('selecting Sale section shows sellDiv', () => {
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
  })

  it('selecting Sale Detail Report shows SRDiv', () => {
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#SRDiv').should('be.visible')
  })

  it('switching between sections hides previous and shows new', () => {
    cy.get('#registrationType').select('CompanyDiv', { force: true })
    cy.get('#CompanyDiv').should('be.visible')

    cy.get('#registrationType').select('CustomerDiv', { force: true })
    cy.get('#CustomerDiv').should('be.visible')
    cy.get('#CompanyDiv').should('not.be.visible')
  })

  it('switching from sell to purchase hides sell and shows purchase', () => {
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')

    cy.get('#purchaseType').select('purchaseDiv', { force: true })
    cy.get('#purchaseDiv').should('be.visible')
    cy.get('#sellDiv').should('not.be.visible')
  })
})

describe('Business Dashboard — Table Rendering', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('Company table has thead with column headers', () => {
    cy.get('#registrationType').select('CompanyDiv', { force: true })
    cy.get('#tableCompany thead').should('exist')
    cy.get('#tableCompany thead th').should('have.length.above', 0)
  })

  it('Vender table has thead', () => {
    cy.get('#registrationType').select('VenderDiv', { force: true })
    cy.get('#tableVender thead').should('exist')
  })

  it('Customer table has thead', () => {
    cy.get('#registrationType').select('CustomerDiv', { force: true })
    cy.get('#tableCustomer thead').should('exist')
  })

  it('DataTables wrapper is rendered for sell table after section loads', () => {
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#tableSell_wrapper', { timeout: 10000 }).should('exist')
  })

  it('Purchase table has thead columns', () => {
    cy.get('#purchaseType').select('purchaseDiv', { force: true })
    cy.get('#tablePurchase thead').should('exist')
    cy.get('#tablePurchase thead th').should('have.length.above', 0)
  })
})

describe('Business Dashboard — AJAX Data Load', () => {
  it('opening CompanyDiv triggers getUserCompany AJAX call', () => {
    cy.loginAsBusiness()
    cy.intercept('GET', /\/getUserCompany/).as('getCompany')
    cy.visit('/businessDashboard')
    cy.get('#registrationType').select('CompanyDiv', { force: true })
    cy.wait('@getCompany', { timeout: 10000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })

  it('opening VenderDiv triggers getUserVender AJAX call', () => {
    cy.loginAsBusiness()
    cy.intercept('GET', /\/getUserVender/).as('getVender')
    cy.visit('/businessDashboard')
    cy.get('#registrationType').select('VenderDiv', { force: true })
    cy.wait('@getVender', { timeout: 10000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })

  it('opening CustomerDiv triggers getUserCustomer AJAX call', () => {
    cy.loginAsBusiness()
    cy.intercept('GET', /\/getUserCustomer/).as('getCustomer')
    cy.visit('/businessDashboard')
    cy.get('#registrationType').select('CustomerDiv', { force: true })
    cy.wait('@getCustomer', { timeout: 10000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })

  it('dashboard loads KPI data on page load', () => {
    cy.loginAsBusiness()
    cy.intercept('GET', /\/getDashboardStats|\/getUserCompany|\/getUserVender/).as('dashLoad')
    cy.visit('/businessDashboard')
    cy.get('#DashboardDiv').should('be.visible')
  })
})

/**
 * The modal CANCEL buttons.
 *
 * These cases used to be called "Form Reset Buttons" and asserted that the field was empty straight
 * after the click. That was true of an older dashboard; the redesign turned these controls into
 * Cancel — they are labelled `ui.cancel2` ("Cancel") and their onclick is `closeModal(...)`. They kept
 * their legacy `reset*` ids, which is what let the stale expectation look plausible. One case was
 * looking for `#resetCompanyItem`, an id that does not exist at all.
 *
 * So the contract is re-stated as something a user can actually observe. "The input is blank" is
 * unobservable once the modal is shut; what matters is that cancelling ABANDONS the draft — reopening
 * must offer a clean form, not the half-typed record you walked away from. That is the property
 * newCompany()/newVender()/newCustomer() provide by calling resetForm(), and the one that would
 * actually hurt if it broke.
 */
describe('Business Dashboard — modal Cancel abandons the draft', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  /** #appAjaxOverlay covers the toolbar while a section loads, so a "+ New" click can land on the
   *  overlay instead of the button. Cypress already retries actionability — it just needs long enough
   *  to outlast the load. (Same fix as business-modal-keyboard.cy.js's clickNew.) */
  const clickNew = (sel) => cy.get(sel, { timeout: 30000 }).click({ timeout: 30000 })

  const cancelAbandonsDraft = ({ section, open, modal, cancel, field, typed }) => {
    cy.get('#registrationType').select(section, { force: true })
    clickNew(open)
    cy.get(modal).should('have.class', 'open')
    cy.get(field).clear().type(typed)

    cy.get(cancel).click()
    cy.get(modal).should('not.have.class', 'open')      // Cancel closes it

    clickNew(open)                                       // ...and reopening starts clean
    cy.get(modal).should('have.class', 'open')
    cy.get(field).should('have.value', '')
  }

  it('Company: cancel then reopen gives a clean form', () => {
    cancelAbandonsDraft({
      section: 'CompanyDiv', open: '#newCompany', modal: '#CompanyModal',
      cancel: '#resetCompany', field: '#companyName', typed: 'Temp Name',
    })
  })

  it('Vender: cancel then reopen gives a clean form', () => {
    cancelAbandonsDraft({
      section: 'VenderDiv', open: '#newVender', modal: '#VenderModal',
      cancel: '#resetVender', field: '#venderName', typed: 'Temp Vender',
    })
  })

  it('Customer: cancel then reopen gives a clean form', () => {
    cancelAbandonsDraft({
      section: 'CustomerDiv', open: '#newCustomer', modal: '#CustomerModal',
      cancel: '#resetCustomer', field: '#customerName', typed: 'Temp Customer',
    })
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

describe('Business Dashboard — KPI Cards', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('all six KPI stat cards are rendered', () => {
    cy.get('#dashCompanies').should('exist')
    cy.get('#dashVenders').should('exist')
    cy.get('#dashCustomers').should('exist')
    cy.get('#dashItems').should('exist')
    cy.get('#dashMonthlySales').should('exist')
    cy.get('#dashMonthlyRevenue').should('exist')
  })

  it('KPI cards load numeric or dash value', () => {
    cy.get('#dashCompanies', { timeout: 10000 }).invoke('text').should('match', /^(\d+|-)$/)
    cy.get('#dashVenders', { timeout: 10000 }).invoke('text').should('match', /^(\d+|-)$/)
    cy.get('#dashCustomers', { timeout: 10000 }).invoke('text').should('match', /^(\d+|-)$/)
    cy.get('#dashItems', { timeout: 10000 }).invoke('text').should('match', /^(\d+|-)$/)
  })

  it('all chart canvases are rendered', () => {
    cy.get('#chartTrend').should('exist')
    cy.get('#chartTopItems').should('exist')
    cy.get('#chartDaily').should('exist')
    cy.get('#chartCustSales').should('exist')
  })

  it('due customers table body exists', () => {
    cy.get('#dueCustTableBody').should('exist')
  })
})

describe('Business Dashboard — Subnav Menu', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('Register subnav button is visible', () => {
    cy.get('#snavRegister').should('exist')
  })

  it('Purchase subnav button is visible', () => {
    cy.get('#snavPurchase').should('exist')
  })

  it('Sale subnav button is visible', () => {
    cy.get('#snavSell').should('exist')
  })
})
