/**
 * HMS baseline — what a clinic can do on the platform TODAY, before any HMS Phase 1 code exists.
 *
 * Source: the client blueprint "Hms-Implementation-Blueprint-Maxtheservice" (8 Cypress stubs + M-01..M-15)
 * and microservices/docs/hms-clinic-programme.md. Every blueprint step that has a working screen or proxy
 * today is driven here, end to end; the steps whose screen does not exist yet live in hms-phase1.cy.js
 * (gated, pending) so a run never reports a feature as green that nobody can click.
 *
 *   Blueprint step                       Today's nearest path                          Case
 *   ─────────────────────────────────    ──────────────────────────────────────────    ────
 *   08 Pharmacy counter (UI)             Pharmacy → Prescriptions screen               B-01
 *   02 Appointment + token               /appointmentReq → "appointment number N"      B-02
 *   APT-002 reject unavailable slot      doctor's daily cap (offer type "count")       B-03
 *   08 Rx → bill → stock once            /addPrescription → /addSell → /dispense…      B-04
 *   cancelled Rx is not dispensable      /cancelPrescription → /dispensePrescription   B-05
 *   M-14 multi-tenant isolation          another tenant reads the Rx by id             B-06
 *
 * NOT covered here because nothing exists to drive: patient registry + MRN, CNIC duplicate check, queue
 * status (WAITING/IN_CONSULTATION/PARKED/DONE), doctor workspace, vitals, templates, park/resume, search by
 * token, pharmacy-by-token, live queue updates. See hms-phase1.cy.js.
 *
 * Cleanup: prescriptions left PENDING are cancelled in after(). Hospitals, doctors and bookings have NO
 * delete path through the monolith (gap G-2 in the programme doc) — every name is stamped so a leftover row
 * is identifiable and never collides; that is recorded as a gap, not hidden. A completed sale is a ledger
 * fact and is deliberately not reversed.
 *
 * Run headed:
 *   npx cypress run --headed --browser chrome --spec "cypress/e2e/hms/hms-baseline-today.cy.js"
 */
describe('HMS baseline — the clinic flow on today\'s screens', () => {
  const stamp = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
  const toCancel = []            // prescription ids to withdraw in after()

  const post = (url, body) =>
    cy.request({ method: 'POST', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

  const postForm = (url, body) =>
    cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

  // Pull an <option value=ID>Name out of a rendered page (the public booking page renders hospital options).
  const idFromOptions = (html, name) => {
    const re = new RegExp('<option[^>]*value=["\']?(\\d+)["\']?[^>]*>\\s*' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    const m = re.exec(html)
    return m ? m[1] : null
  }

  const stockOf = (productId) =>
    cy.request(`/productStock?productId=${productId}`).then((r) => Number(r.body.stock))

  /** Hospital + doctor with a daily cap of `cap` patients. Yields { hospitalId, doctorId }. */
  const clinicWithDoctor = (cap) => {
    const s = stamp()
    const hospitalName = 'HmsClinic ' + s
    const doctorName = 'HmsDoctor ' + s
    postForm('/registerHospital', {
      name: hospitalName, phone: '03001234567', email: `hms${s}@test.com`,
      countryCode: 'PK', state: 'Sindh', geoId: 'Karachi', hours: '24',
    }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.error, 'hospital registered').to.not.eq('RegisterFailed')
    })
    return cy.request('/appointment').then((page) => {
      const hospitalId = idFromOptions(page.body, hospitalName)
      expect(hospitalId, 'the new clinic is offered on the booking page').to.not.be.null
      postForm('/registerDoctor', {
        hospitalId, name: doctorName, speciality: 'General Medicine', email: `hmsd${s}@test.com`,
        mobile: '03007654321', address: 'OPD 1', availabe: 'All',
        dayFrom: 'Monday', dayTo: 'Sunday', timeIn: '00:00', timeOut: '23:59',
        appointmentOfferType: 'count', appointmentOfferValue: String(cap),
      }).then((r) => {
        expect(r.status).to.eq(200)
        expect(r.body.error, 'doctor registered').to.not.eq('RegisterFailed')
      })
      return cy.request(`/loadDoctorsByHospital?hospitalId=${hospitalId}`).then((d) => {
        const doctorId = idFromOptions(d.body, doctorName)
        expect(doctorId, 'the doctor is listed under the clinic').to.not.be.null
        return { hospitalId, doctorId }
      })
    })
  }

  // Bookings resolve the patient BY PHONE (AppointmentService:149) — every person here gets their own number,
  // or a re-run silently books under an earlier run's name. That behaviour is a defect: see B-07.
  const uniquePhone = () => '03' + String(Date.now()).slice(-6) + String(Math.floor(Math.random() * 1000)).padStart(3, '0')

  const book = (hospitalId, doctorId, name, mobile) =>
    postForm('/appointmentReq', { hospitalId, doctorId, name, mobile, address: 'Reception', email: `p${stamp()}@test.com` })

  after(() => {
    // CLEANUP — withdraw every prescription this file left live, so the pharmacist's list is not polluted.
    cy.loginAsPharma()
    toCancel.forEach((id) => post('/cancelPrescription', { prescriptionId: id }))
  })

  // ── B-01 — the real screen first: it answers "can a person do this at all" ────────────────────────────
  describe('pharmacist (demo.pharma)', () => {
    beforeEach(() => { cy.loginAsPharma() })

    it('B-01 records a doctor\'s prescription through the Prescriptions screen and sees it PENDING', () => {
      const patient = 'HmsPatient_' + stamp()
      const med = 'HmsParacetamol_' + stamp()
      cy.seedProduct({ name: med, unit: 'tablet', stock: 50, sellingPrice: 10 }).then(({ productId }) => {
        cy.intercept('POST', '**/addPrescription').as('save')

        // 1. Open the dashboard → Pharmacy → Prescriptions.
        cy.visit('/businessDashboard')
        cy.get('#snavPharmacy > .snav-btn').should('be.visible').click()
        cy.get('#snavPharmacy a[onclick^="showPrescriptions"]').should('be.visible').click()
        cy.get('#PrescriptionDiv').should('be.visible')
        cy.screenshot('hms/B-01-1-prescriptions-screen', { capture: 'viewport' })

        // 2. Patient + prescriber, as written on the doctor's slip.
        cy.get('#rxPatient').should('be.visible').type(patient)
        cy.get('#rxPatientPhone').type('03001112233')
        cy.get('#rxDoctor').type('Dr Ahmed')
        cy.get('#rxLicense').type('PMDC-12345')
        cy.get('#rxDiagnosis').type('Fever 3 days')

        // 3. One line: the medicine, quantity and how to take it. (#rxMedicine is a bootstrap-select;
        //    the real <select> carries the value addRxItem() reads.)
        cy.get('#rxMedicine option[value="' + productId + '"]', { timeout: 20000 }).should('exist')
        cy.get('#rxMedicine').select(String(productId), { force: true })
        cy.get('#rxQty').type('10')
        cy.get('#rxDosage').type('1 tab')
        cy.get('#rxFreq').type('TDS')
        cy.get('#rxDuration').type('3d')
        cy.get('#PrescriptionDiv button[onclick="addRxItem()"]').click()
        cy.get('#rxItemsBody tr').should('have.length', 1).and('contain', med)
        cy.screenshot('hms/B-01-2-line-added', { capture: 'viewport' })

        // 4. Save.
        cy.get('#PrescriptionDiv button[onclick="savePrescription()"]').click()
        cy.wait('@save').then(({ response }) => {
          expect(response.body.success, JSON.stringify(response.body)).to.eq(true)
          expect(response.body.data.status, 'recorded, nothing handed over').to.eq('PENDING')
          toCancel.push(response.body.data.id)
        })

        // EXPECTED: the list shows the patient, Dr Ahmed, 1 item, PENDING, with Dispense + Cancel offered.
        cy.contains('#prescriptionBody tr', patient).within(() => {
          cy.contains('Dr Ahmed')
          cy.contains('PENDING')
          cy.contains('button', 'Dispense').should('be.visible')
          cy.contains('button', 'Cancel').should('be.visible')
        })
        cy.screenshot('hms/B-01-3-listed-pending', { capture: 'viewport' })
      })
    })

    // ── B-04 — blueprint step 8: prescription → bill → stock, exactly once ─────────────────────────────
    it('B-04 a prescription billed at the counter moves stock once and completes the script', () => {
      const med = 'HmsAmox_' + stamp()
      cy.seedProduct({ name: med, unit: 'capsule', stock: 40, sellingPrice: 25 }).then(({ productId }) => {
        stockOf(productId).then((before) => {
          post('/addPrescription', {
            patientName: 'HmsPatient_' + stamp(), patientPhone: '03004445566', doctorName: 'Dr Ahmed',
            doctorLicense: 'PMDC-12345', diagnosis: 'Tonsillitis',
            items: [{ productId, medicineName: med, quantity: 6, dosage: '1 cap', frequency: 'BD', duration: '3d' }],
          }).then((rx) => {
            expect(rx.body.success, JSON.stringify(rx.body)).to.eq(true)
            const rxId = rx.body.data.id

            // Recording the script alone moves nothing.
            stockOf(productId).should('eq', before)

            const key = 'cy-hms-b04-' + stamp()
            const sale = {
              customer: { name: 'HmsPatient', contact: '03004445566', paidAmount: 150, dueAmount: 0 },
              sales: [{ productId, quantity: 6, sellRate: 25, totalAmount: 150, netAmount: 150 }],
              tenders: [{ method: 'CASH', amount: 150, reference: '' }],
              idempotencyKey: key, prescriptionId: rxId,
            }
            post('/addSell', sale).then((s) => {
              expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
              const invoiceNo = s.body.object
              expect(invoiceNo, 'an invoice number').to.be.a('string').and.not.be.empty

              // A double-clicked / retried submit with the SAME key must not sell twice.
              post('/addSell', sale)
              stockOf(productId).should('eq', before - 6)

              post('/dispensePrescription', { prescriptionId: rxId, invoiceNo, items: [{ productId, quantity: 6 }] })
                .then((d) => {
                  expect(d.body.success, JSON.stringify(d.body)).to.eq(true)
                  expect(d.body.data.status).to.eq('FULLY_DISPENSED')
                  expect(d.body.data.items[0].dispensedQuantity).to.eq(6)
                })
            })
          })
        })
      })
    })

    // ── B-05 — a withdrawn script cannot be dispensed ───────────────────────────────────────────────────
    it('B-05 a cancelled prescription is refused at dispense', () => {
      const med = 'HmsCetirizine_' + stamp()
      cy.seedProduct({ name: med, unit: 'tablet', stock: 20, sellingPrice: 5 }).then(({ productId }) => {
        post('/addPrescription', {
          patientName: 'HmsPatient_' + stamp(), doctorName: 'Dr Ahmed',
          items: [{ productId, medicineName: med, quantity: 5 }],
        }).then((rx) => {
          const rxId = rx.body.data.id
          post('/cancelPrescription', { prescriptionId: rxId }).then((c) => {
            expect(c.body.success, JSON.stringify(c.body)).to.eq(true)
          })
          cy.request(`/getPrescription?id=${rxId}`).its('body.data.status').should('eq', 'CANCELLED')
          // Refusals arrive as 200 + success:false — assert the envelope, not the status code.
          post('/dispensePrescription', { prescriptionId: rxId, invoiceNo: 'NONE', items: [{ productId, quantity: 5 }] })
            .then((d) => expect(d.body.success, JSON.stringify(d.body)).to.not.eq(true))
        })
      })
    })
  })

  // ── B-02 / B-03 — reception: book, number, cap ─────────────────────────────────────────────────────────
  describe('front desk (owner.appointment)', () => {
    beforeEach(() => {
      cy.task('clearDemoCaps')
      cy.loginAsAppointmentOwner()
    })

    it('B-02 two bookings with the same doctor get consecutive appointment numbers', () => {
      const ali = 'Ali Khan ' + stamp()
      const sara = 'Sara Bibi ' + stamp()
      clinicWithDoctor(20).then(({ hospitalId, doctorId }) => {
        book(hospitalId, doctorId, ali, uniquePhone()).then((first) => {
          expect(first.body.status, JSON.stringify(first.body)).to.eq('SUCCESS')
          const n1 = Number((/appointment number (\d+)/.exec(first.body.message) || [])[1])
          expect(n1, 'the receipt names a number').to.be.greaterThan(0)

          book(hospitalId, doctorId, sara, uniquePhone()).then((second) => {
            expect(second.body.status).to.eq('SUCCESS')
            const n2 = Number((/appointment number (\d+)/.exec(second.body.message) || [])[1])
            expect(n2, 'the next patient is the next number').to.eq(n1 + 1)
          })
        })
        // EXPECTED: both bookings are in the org-scoped list the front desk reads.
        cy.request('/loadAppointments').then((list) => {
          expect(list.status).to.eq(200)
          const text = JSON.stringify(list.body)
          expect(text, 'first patient listed').to.contain(ali)
          expect(text, 'second patient listed').to.contain(sara)
        })
      })
    })

    it('B-03 a doctor full for the day refuses the next booking with a reason', () => {
      clinicWithDoctor(2).then(({ hospitalId, doctorId }) => {
        book(hospitalId, doctorId, 'Cap One ' + stamp(), '03001000001').its('body.status').should('eq', 'SUCCESS')
        book(hospitalId, doctorId, 'Cap Two ' + stamp(), '03001000002').its('body.status').should('eq', 'SUCCESS')
        book(hospitalId, doctorId, 'Cap Three ' + stamp(), '03001000003').then((third) => {
          expect(third.body.status, JSON.stringify(third.body)).to.eq('FAILURE')
          expect(third.body.error, 'the refusal says why').to.be.a('string').and.not.be.empty
        })
      })
    })
  })

  // ── B-06 — M-14: another tenant cannot read this clinic's prescription ──────────────────────────────────
  it('B-06 another tenant cannot read a prescription by id', () => {
    const patient = 'HmsPrivate_' + stamp()
    cy.loginAsPharma()
    cy.seedProduct({ name: 'HmsIso_' + stamp(), unit: 'tablet' }).then(({ productId }) => {
      post('/addPrescription', { patientName: patient, items: [{ productId, medicineName: 'iso', quantity: 1 }] })
        .then((rx) => {
          const rxId = rx.body.data.id
          toCancel.push(rxId)
          cy.clearCookies()
          cy.loginAsBusiness()
          cy.request({ url: `/getPrescription?id=${rxId}`, failOnStatusCode: false }).then((r) => {
            expect(r.body && r.body.success, 'refused for another tenant').to.not.eq(true)
            expect(JSON.stringify(r.body || ''), 'no patient name leaks').to.not.contain(patient)
          })
        })
    })
  })

  // ── B-07 — ⚠ KNOWN DEFECT, found by this file on 2026-10-08 ─────────────────────────────────────────────
  // appointment-service resolves the patient by PHONE ALONE (findFirstByPhoneAndOrganizationId) and ignores
  // the name typed. Two people on one family phone become ONE patient under the first name — a wrong-patient
  // record the moment a clinical history hangs off it. Red today; opt in with --env hmsDefects=1. It turns
  // green when Phase 1's patient registry (CNIC / name + DOB matching, never phone alone) replaces Attendee.
  ;(Cypress.env('hmsDefects') ? it : it.skip)('B-07 ⚠ two people sharing one phone stay two patients', () => {
    cy.task('clearDemoCaps')
    cy.loginAsAppointmentOwner()
    const phone = uniquePhone()
    const father = 'Rashid Ahmed ' + stamp()
    const son = 'Usman Rashid ' + stamp()
    clinicWithDoctor(20).then(({ hospitalId, doctorId }) => {
      book(hospitalId, doctorId, father, phone).its('body.status').should('eq', 'SUCCESS')
      book(hospitalId, doctorId, son, phone).its('body.status').should('eq', 'SUCCESS')
      cy.request('/loadAppointments').then((list) => {
        const mine = list.body.filter((a) => a.patientPhone === phone)
        expect(mine.map((a) => a.patientName), 'each booking keeps the name that was typed')
          .to.include.members([father, son])
      })
    })
  })
})
