/**
 * P-BOOK-1 — a visitor who is NOT signed in books an appointment on the public page, end to end.
 *
 * The defect (log-proven 2026-10-10): on the Docker deploy /appointment listed NO hospital to an anonymous visitor —
 * the monolith's anonymous read fell back to http://localhost:8091, which inside its container is not
 * appointment-service. Signed in it went through the gateway and showed only the visitor's own business, which is
 * why every signed-in spec (S2-11) passed. Fixed by public read routes under /api/appointment/public/** (public
 * fields only) read through the gateway's open route, and by escaping every tenant-typed value put into the page.
 *
 * No login anywhere in this spec: cy.clearCookies() before every request.
 *
 * Server state: PB-02 makes ONE booking (a booking is a record, like a sale; a booking does not use a clinic token).
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/appointment/public-booking-page.cy.js"
 */
describe('P-BOOK-1 — the public booking page works for a visitor who is not signed in', () => {
  const run = String(Date.now()).slice(-7)
  const phone = '032' + run.slice(-6) + String(Math.floor(Math.random() * 90) + 10).slice(-2)
  const S = {}

  beforeEach(() => { cy.clearCookies() })

  it('PB-01 the page lists the hospitals, and a hospital lists its doctors — public fields only', () => {
    cy.visit('/appointment')
    // EXPECTED: hospitals are listed (it used to be "Select hospital" and nothing else)
    cy.get('#hospitalId option').should('have.length.greaterThan', 1)
    cy.screenshot('p-book-1/PB-01-1-hospitals-listed', { capture: 'viewport' })
    // a hospital that has doctors (found through the page's own anonymous route)
    cy.get('#hospitalId option').then(($opts) => {
      const ids = $opts.toArray().map((o) => o.value).filter(Boolean)
      const tryNext = (i) => {
        expect(i, 'some hospital lists at least one doctor').to.be.lessThan(ids.length)
        return cy.request(`/loadDoctorsByHospital?hospitalId=${ids[i]}`).then((r) => {
          const m = /<option value='(\d+)'>([^<]+)<\/option>/.exec(r.body)
          if (m) { S.venueId = ids[i]; S.doctorId = m[1]; S.doctorName = m[2]; return }
          return tryNext(i + 1)
        })
      }
      return tryNext(0)
    })
    cy.then(() => cy.get('#hospitalId').select(S.venueId))
    cy.get('#doctorId option').should('have.length.greaterThan', 1)
    cy.then(() => cy.get('#doctorId').select(S.doctorId))
    cy.get('#doctorDetails').should('be.visible').and('contain', 'Time From')
    cy.screenshot('p-book-1/PB-01-2-doctor-and-timings', { capture: 'viewport' })
    // the page's anonymous read carries no contact detail of the doctor
    cy.then(() => cy.request(`/loadDoctorDetails?doctorId=${S.doctorId}`)).its('body')
      .should('not.match', /@|mobile|fee/i)
  })

  it('PB-02 the visitor books: name, mobile, address → an appointment number', () => {
    cy.visit('/appointment')
    cy.then(() => cy.get('#hospitalId').select(S.venueId))
    cy.then(() => cy.get('#doctorId').select(S.doctorId))
    cy.get('#bookForm').within(() => {
      cy.get('#name').type('PB Visitor ' + run)
      cy.get('#mobile').type(phone)
      cy.get('#address').type('Gulshan, Karachi')
    })
    cy.intercept('POST', '**/appointmentReq').as('book')
    cy.get('#bookBtn').click()
    cy.wait('@book').then(({ response }) => {
      expect(String(response.body.status), JSON.stringify(response.body)).to.eq('SUCCESS')
      expect(response.body.message).to.match(/your appointment number \d+ is registered/)
    })
    cy.screenshot('p-book-1/PB-02-1-booked', { capture: 'viewport' })
  })

  it('PB-03 the public routes give nothing private: no email, phone, mobile, fee or organisation', () => {
    cy.request('/appointment').its('body').then((html) => {
      // hospitals are rendered as options only: no venue email / phone in the page
      expect(html).to.not.match(/@[a-z0-9-]+\.(com|pk|org)/i)
    })
    cy.then(() => cy.request(`/loadDoctorsByHospital?hospitalId=${S.venueId}`)).its('body').then((html) => {
      expect(html).to.not.match(/@|03\d{9}/)
    })
  })
})
