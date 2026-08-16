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
    // Was `super@edu.com` — a monolith-era login that no longer exists since auth moved to
    // auth-service, so this spec failed at the session with /login?error=true. The appointment OWNER
    // is the right replacement rather than demo.appointment@: this case CREATES a hospital and a
    // doctor, and a demo account is capped at 50 writes per module — a cap that surfaces as some
    // arbitrary later write failing, not as a quota message.
    cy.loginAsAppointmentOwner()
  })

  it('registers a hospital + doctor via the cards and renders the appointments table', () => {
    cy.visit('/appointmentDashboard')
    // SELECTOR REPAIRED 2026-08-07 (slice SCHED-1). The assertion's INTENT is unchanged — "the dashboard
    // chrome rendered" — but the element that represents it was renamed by the UI redesign
    // (cb4d6abb, 2026-07-09): appointmentDashboard.html now carries <nav id="app-sidebar">, and
    // #nav-subheader survives only in businessDashboard.html. This spec was last touched 2026-06-12, so
    // it has been RED since July and nobody ran it. Found while using it as SCHED-1's baseline.
    cy.get('#app-sidebar').should('be.visible')
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
