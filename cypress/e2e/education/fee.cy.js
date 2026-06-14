/**
 * Education — fee settings / report / voucher / ledger (slice 14).
 * Headed + slowed so the flow is visible. Requires the full stack + a student (ENR-001/ENR-002).
 */

describe('Education — fee report / voucher / ledger / settings', () => {
  const SLOW = 1000

  beforeEach(() => {
    cy.loginAsEducation()
    cy.visit('/educationDashboard')
    cy.wait(SLOW)
  })

  it('fee settings load and save', () => {
    cy.get('#feeType').select('FeeSettingDiv', { force: true })
    cy.wait(SLOW)
    cy.get('#FeeSettingDiv').should('be.visible')
    cy.get('#fsPaymentMode').should('exist')
    const alertStub = cy.stub().as('settingsAlert')
    cy.on('window:alert', alertStub)
    cy.contains('button', 'Save Settings').click()
    cy.wait(SLOW).then(() => expect(alertStub).to.have.been.called)
  })

  it('fee report shows rows and totals', () => {
    cy.get('#feeType').select('FRDiv', { force: true })
    cy.wait(SLOW)
    cy.get('#FRDiv').should('be.visible')
    cy.contains('button', 'View').click()
    cy.wait(SLOW)
    cy.get('#frBody tr').should('have.length.greaterThan', 0)
    cy.get('#frTotals').should('contain', 'Totals')
  })

  it('fee voucher computes total payable (aging)', () => {
    cy.get('#feeType').select('FVDiv', { force: true })
    cy.wait(SLOW)
    cy.get('#FVDiv').should('be.visible')
    cy.get('#fvEnroll').clear().type('ENR-002')
    cy.contains('button', 'Generate Voucher').click()
    cy.wait(SLOW)
    cy.get('#fvVoucher').should('be.visible').and('contain', 'Total payable')
  })

  it('fee ledger lists payments', () => {
    cy.get('#feeType').select('FVDiv', { force: true })
    cy.wait(SLOW)
    cy.get('#fvEnroll').clear().type('ENR-001')
    cy.contains('button', 'Show Ledger').click()
    cy.wait(SLOW)
    cy.get('#flLedgerWrap').should('be.visible')
    cy.get('#flBody tr').should('have.length.greaterThan', 0)
  })
})
