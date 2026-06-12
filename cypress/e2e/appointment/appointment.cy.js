/**
 * P3 runtime verification — the monolith appointment screens proxy to appointment-service (no myplusdb).
 *
 * Driven at the proxy/HTTP level (not the legacy DOM, which slice-18 rebuilds): register a hospital,
 * register a doctor under it, book a public appointment, and read the org-scoped appointments list —
 * all through the monolith's proxy controllers, which now talk to appointment-service via the gateway.
 *
 * Requires the full stack up: eureka, config, gateway, auth-service, appointment-service, monolith.
 * Any LOGIN_PRIVILEGE user works (appointment data is org-scoped by the JWT); we reuse the seeded
 * education super user.
 */
describe('P3 — appointment proxies to appointment-service', () => {
  const stamp = Date.now()
  const hospitalName = 'CyHospital ' + stamp
  const doctorName = 'CyDoctor ' + stamp
  const patientName = 'CyPatient ' + stamp

  beforeEach(() => {
    cy.loginAs('super@edu.com', 'super', '/getDashboardData')
  })

  // Pull the option value (id) for a name out of a rendered <select> page.
  const idFromOptions = (html, name) => {
    const re = new RegExp('<option[^>]*value=["\']?(\\d+)["\']?[^>]*>\\s*' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    const m = re.exec(html)
    return m ? m[1] : null
  }

  it('registers a hospital through the proxy', () => {
    cy.request({
      method: 'POST', url: '/registerHospital', form: true,
      body: {
        name: hospitalName, phone: '03001234567', email: `h${stamp}@test.com`,
        countryCode: 'PK', state: 'Sindh', geoId: 'Karachi', hours: '24',
      },
    }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.error, 'no RegisterFailed error').to.not.eq('RegisterFailed')
    })
  })

  it('registers a doctor, books anonymously, and lists the appointment', () => {
    // 1) find the hospital id from the public booking page (renders hospital options)
    cy.request('/appointment').then((res) => {
      const hospitalId = idFromOptions(res.body, hospitalName)
      expect(hospitalId, 'hospital id resolved from /appointment').to.not.be.null

      // 2) register a doctor under it
      cy.request({
        method: 'POST', url: '/registerDoctor', form: true,
        body: {
          hospitalId, name: doctorName, speciality: 'General', email: `d${stamp}@test.com`,
          mobile: '03007654321', address: 'Clinic St', availabe: 'All',
          dayFrom: 'Monday', dayTo: 'Friday', timeIn: '09:00', timeOut: '17:00',
          appointmentOfferType: 'count', appointmentOfferValue: '20',
        },
      }).then((dres) => {
        expect(dres.status).to.eq(200)
        expect(dres.body.error).to.not.eq('RegisterFailed')
      })

      // 3) resolve the doctor id via the proxy, then book a public appointment
      cy.request(`/loadDoctorsByHospital?hospitalId=${hospitalId}`).then((lres) => {
        const doctorId = idFromOptions(lres.body, doctorName)
        expect(doctorId, 'doctor id resolved from /loadDoctorsByHospital').to.not.be.null

        cy.request({
          method: 'POST', url: '/appointmentReq', form: true,
          body: { hospitalId, doctorId, name: patientName, mobile: '03009998888', address: 'Patient St', email: `p${stamp}@test.com` },
        }).then((bres) => {
          expect(bres.status).to.eq(200)
          // success message carries the appointment token text
          expect(JSON.stringify(bres.body)).to.match(/appointment number|registered/i)
        })

        // 4) the org-scoped list comes back from appointment-service (not myplusdb)
        cy.request('/loadAppointments').then((ares) => {
          expect(ares.status).to.be.oneOf([200, 204])
          if (ares.status === 200) {
            expect(ares.body).to.be.an('array')
            expect(ares.body.length).to.be.greaterThan(0)
          }
        })
      })
    })
  })
})
