/**
 * Slice-18 — visual walkthrough of the appointment module's own dashboard (/appointmentDashboard).
 * Drives the real UI: snav section toggle, register a hospital + doctor via the card forms (through the
 * proxy to appointment-service), and the appointments table render. Screenshots captured per step.
 * Requires the full stack up (monolith + gateway + auth-service + appointment-service).
 */
describe('Slice-18 — appointment dashboard UI', () => {
  const stamp = Date.now()
  const hosp = 'UIHospital ' + stamp
  const doc = 'UIDoctor ' + stamp

  beforeEach(() => {
    cy.loginAs('super@edu.com', 'super', '/getDashboardData')
  })

  it('registers a hospital + doctor via the cards and renders the appointments table', () => {
    cy.visit('/appointmentDashboard')
    cy.get('#nav-subheader').should('be.visible')
    cy.get('#AppointmentsDiv').should('be.visible')

    // ── Register hospital ───────────────────────────────────────────────
    cy.get('#apptNav').select('HospitalDiv', { force: true })
    cy.get('#HospitalDiv').should('be.visible')
    cy.get('#hName').type(hosp)
    cy.get('#hPhone').type('03001234567')
    cy.get('#hEmail').type(`uih${stamp}@test.com`)
    cy.get('#hCountry').select('PK', { force: true })
    cy.get('#hState').type('Sindh')
    cy.get('#hCity').type('Karachi')
    cy.get('#addHospital').click()
    cy.get('#apptGlobalMsg').should('be.visible').and('contain', 'Hospital registered')

    // ── Register doctor (dropdown now includes the new hospital) ─────────
    cy.get('#apptNav').select('DoctorDiv', { force: true })
    cy.get('#DoctorDiv').should('be.visible')
    cy.get('#dHospital').find('option').should('contain', hosp)
    cy.get('#dHospital').select(hosp, { force: true })
    cy.get('#dName').type(doc)
    cy.get('#dSpeciality').type('Cardiology')
    cy.get('#dMobile').type('03007654321')
    cy.get('#dOfferType').select('count', { force: true })
    cy.get('#dOfferValue').type('20')
    cy.get('#addDoctor').click()
    cy.get('#apptGlobalMsg').should('be.visible').and('contain', 'Doctor registered')

    // ── Appointments table renders ──────────────────────────────────────
    cy.get('#apptNav').select('AppointmentsDiv', { force: true })
    cy.get('#AppointmentsDiv').should('be.visible')
    cy.get('#apptTableBody tr').should('have.length.greaterThan', 0)
  })
})
