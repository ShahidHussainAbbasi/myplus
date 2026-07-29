/**
 * Pharmacy — the business cases, end to end.
 *
 * The other pharmacy specs test mechanics one guard at a time (dispense-guards, rx-enforcement,
 * safety, alerts). This one walks the JOURNEYS a pharmacist actually describes — record a script,
 * collect it in parts over two visits, cancel one, and answer a regulator — through the real sell
 * path, so it fails if any layer in between breaks.
 *
 * Doc: microservices/docs/pharmacy-prescriptions-use-case.md
 * Unit-level equivalents: pharma-service · PrescriptionUseCaseTest
 *
 * Run headed:
 *   npx cypress open --e2e        (pick pharmacy/prescription-use-cases.cy.js)
 *   npx cypress run  --spec cypress/e2e/pharmacy/prescription-use-cases.cy.js
 */
describe('Pharmacy — prescription business cases', () => {
  beforeEach(() => { cy.loginAsPharma() })

  const post = (url, body) =>
    cy.request({ method: 'POST', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

  const stamp = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

  /** A real sale through the same path the till uses, declaring the prescription it satisfies. */
  const sell = (productId, qty, rate, prescriptionId) =>
    post('/addSell', {
      customer: { name: 'Pat_' + stamp(), contact: '0300', paidAmount: qty * rate, dueAmount: 0 },
      sales: [{ productId, quantity: qty, sellRate: rate, totalAmount: qty * rate, netAmount: qty * rate }],
      tenders: [{ method: 'CASH', amount: qty * rate, reference: '' }],
      idempotencyKey: 'cy-uc-' + stamp(),
      prescriptionId: prescriptionId || null,
    })

  const recordScript = (patient, items, extra = {}) =>
    post('/addPrescription', Object.assign({ patientName: patient, items }, extra)).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      return r.body.data
    })

  const readScript = (rxId) =>
    cy.request(`/getPrescription?id=${rxId}`).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      return r.body.data
    })

  const dispense = (rxId, invoiceNo, lines) =>
    post('/dispensePrescription', { prescriptionId: rxId, invoiceNo, items: lines })

  const outstandingOf = (rx, productId) => {
    const line = rx.items.find((i) => Number(i.productId) === Number(productId))
    expect(line, `product ${productId} is on the script`).to.exist
    return line.quantity - (line.dispensedQuantity || 0)
  }

  // ── Example 1 ────────────────────────────────────────────────────────────────

  describe('Example 1 — Ayesha collects the whole script at once', () => {

    it('recording the script moves no stock and takes no money', () => {
      const name = 'Azithro_' + stamp()
      cy.seedProduct({ name, unit: 'tablet', stock: 100, sellingPrice: 100 }).then(({ productId }) => {

        // On-hand before the clinical entry (stock lives in inventory-service, read via /productStock).
        cy.request(`/productStock?productId=${productId}`).then((before) => {
          const stockBefore = before.body.stock

          recordScript('Ayesha_' + stamp(), [
            { productId, medicineName: name, quantity: 6, dosage: '1', frequency: 'BD', duration: '3d' },
          ]).then((rx) => {
            expect(rx.status, 'nothing handed over yet').to.eq('PENDING')
            expect(rx.items[0].dispensedQuantity || 0).to.eq(0)

            // The whole reason the two are separate: a clinical entry cannot touch stock or cash.
            cy.request(`/productStock?productId=${productId}`).then((after) => {
              expect(after.body.stock, 'stock is untouched by recording a prescription')
                .to.eq(stockBefore)
            })
          })
        })
      })
    })

    it('selling the full quantity against it completes the script', () => {
      const name = 'AzithroFull_' + stamp()
      cy.seedProduct({ name, unit: 'tablet', stock: 100, sellingPrice: 100 }).then(({ productId }) => {
        recordScript('Ayesha_' + stamp(), [
          { productId, medicineName: name, quantity: 6 },
        ]).then((rx) => {

          // The real sale — stock, money, invoice.
          sell(productId, 6, 100, rx.id).then((s) => {
            expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
            const invoiceNo = s.body.object
            expect(invoiceNo, 'the sale produced an invoice').to.be.a('string')

            // …then the clinical record of what that sale satisfied.
            dispense(rx.id, invoiceNo, [{ productId, quantity: 6 }]).then((d) => {
              expect(d.body.success, JSON.stringify(d.body)).to.eq(true)
              expect(d.body.data.status).to.eq('FULLY_DISPENSED')
              expect(d.body.data.items[0].dispensedQuantity).to.eq(6)
            })
          })
        })
      })
    })
  })

  // ── Example 2 ────────────────────────────────────────────────────────────────

  describe('Example 2 — "only give me 2 now, the third next week"', () => {

    it('remembers what is still outstanding across two separate visits', () => {
      const a = 'MedA_' + stamp(), b = 'MedB_' + stamp(), c = 'MedC_' + stamp()

      cy.seedProduct({ name: a, unit: 'tablet', stock: 50, sellingPrice: 100 }).then((pa) => {
        cy.seedProduct({ name: b, unit: 'tablet', stock: 50, sellingPrice: 100 }).then((pb) => {
          cy.seedProduct({ name: c, unit: 'tablet', stock: 50, sellingPrice: 100 }).then((pc) => {

            recordScript('Bilal_' + stamp(), [
              { productId: pa.productId, medicineName: a, quantity: 6 },
              { productId: pb.productId, medicineName: b, quantity: 10 },
              { productId: pc.productId, medicineName: c, quantity: 14 },
            ]).then((rx) => {

              // ── Visit 1: he takes two of the three medicines.
              sell(pa.productId, 6, 100, rx.id).then((s1) => {
                const inv1 = s1.body.object
                dispense(rx.id, inv1, [{ productId: pa.productId, quantity: 6 }])
              })
              sell(pb.productId, 10, 100, rx.id).then((s2) => {
                const inv2 = s2.body.object
                dispense(rx.id, inv2, [{ productId: pb.productId, quantity: 10 }]).then((d) => {
                  expect(d.body.data.status, 'one medicine still outstanding')
                    .to.eq('PARTIALLY_DISPENSED')
                })
              })

              // ── A week later: the pharmacist opens the SAME script and it knows what is left.
              cy.then(() => readScript(rx.id)).then((later) => {
                expect(outstandingOf(later, pa.productId), 'already collected').to.eq(0)
                expect(outstandingOf(later, pb.productId), 'already collected').to.eq(0)
                expect(outstandingOf(later, pc.productId), 'still owed to the patient').to.eq(14)
              })

              // ── Visit 2: the last medicine completes it.
              cy.then(() => sell(pc.productId, 14, 100, rx.id)).then((s3) => {
                const inv3 = s3.body.object
                dispense(rx.id, inv3, [{ productId: pc.productId, quantity: 14 }]).then((d) => {
                  expect(d.body.data.status).to.eq('FULLY_DISPENSED')
                })
              })
            })
          })
        })
      })
    })

    it('a part quantity of a single medicine is tracked too', () => {
      const name = 'PartQty_' + stamp()
      cy.seedProduct({ name, unit: 'tablet', stock: 50, sellingPrice: 100 }).then(({ productId }) => {
        recordScript('Bilal_' + stamp(), [{ productId, medicineName: name, quantity: 6 }]).then((rx) => {

          sell(productId, 4, 100, rx.id).then((s) => {
            dispense(rx.id, s.body.object, [{ productId, quantity: 4 }]).then((d) => {
              expect(d.body.data.status).to.eq('PARTIALLY_DISPENSED')
              expect(d.body.data.items[0].dispensedQuantity).to.eq(4)
            })
          })

          cy.then(() => sell(productId, 2, 100, rx.id)).then((s2) => {
            dispense(rx.id, s2.body.object, [{ productId, quantity: 2 }]).then((d) => {
              expect(d.body.data.status).to.eq('FULLY_DISPENSED')
            })
          })
        })
      })
    })
  })

  // ── Example 3 ────────────────────────────────────────────────────────────────

  describe('Example 3 — the safety lock on a prescription-only medicine', () => {

    it('a walk-in cannot buy it, but the same medicine sells against a script', () => {
      const name = 'Augmentin_' + stamp()
      cy.seedProduct({ name, unit: 'tablet', stock: 50, sellingPrice: 100 }).then(({ productId }) => {

        post('/saveClinical', { productId, medicineName: name, rxRequired: true, controlledSubstance: false })
          .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

        // Counter sale, no script → refused, and the message says why.
        sell(productId, 1, 100, null).then((s) => {
          expect(s.body.status, JSON.stringify(s.body)).to.not.eq('SUCCESS')
          expect(String(s.body.message || '')).to.match(/prescription-only/i)
        })

        // Same medicine, same till — allowed once a prescription is declared.
        cy.then(() => recordScript('Legit_' + stamp(), [
          { productId, medicineName: name, quantity: 5 },
        ])).then((rx) => {
          sell(productId, 1, 100, rx.id).then((s) => {
            expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
          })
        })
      })
    })

    it('ordinary OTC stock is completely unaffected', () => {
      const name = 'Brufen_' + stamp()
      cy.seedProduct({ name, unit: 'tablet', stock: 50, sellingPrice: 50 }).then(({ productId }) => {
        // No flag set — the guard must not fire on the busiest path in the system.
        sell(productId, 1, 50, null).then((s) => {
          expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
        })
      })
    })
  })

  // ── Example 4 ────────────────────────────────────────────────────────────────

  describe('Example 4 — script withdrawn or entered by mistake', () => {

    it('cancelling blocks future dispensing but keeps what was already handed over', () => {
      const name = 'Cancelled_' + stamp()
      cy.seedProduct({ name, unit: 'tablet', stock: 50, sellingPrice: 100 }).then(({ productId }) => {
        recordScript('Withdrawn_' + stamp(), [
          { productId, medicineName: name, quantity: 10 },
        ]).then((rx) => {

          // Yesterday: 4 tablets were genuinely handed over.
          sell(productId, 4, 100, rx.id).then((s) => {
            dispense(rx.id, s.body.object, [{ productId, quantity: 4 }])
          })

          // Today: the doctor changes the treatment.
          cy.then(() => post('/cancelPrescription', { prescriptionId: rx.id })).then((c) => {
            expect(c.body.success, JSON.stringify(c.body)).to.eq(true)
          })

          cy.then(() => readScript(rx.id)).then((after) => {
            expect(after.status).to.eq('CANCELLED')
            expect(after.items[0].dispensedQuantity,
              'cancelling is about the FUTURE — the audit trail is not rewritten').to.eq(4)
          })

          // Nothing further may be charged to it.
          cy.then(() => dispense(rx.id, 'INV-AFTER-CANCEL', [{ productId, quantity: 1 }])).then((d) => {
            expect(d.body.success, 'a cancelled script cannot be dispensed').to.not.eq(true)
            expect(String(d.body.message || '')).to.match(/cancel/i)
          })
        })
      })
    })
  })

  // ── Example 5 ────────────────────────────────────────────────────────────────

  describe('Example 5 — the regulator asks about controlled substances', () => {

    it('a controlled dispense lands on the register, tied to its invoice', () => {
      const name = 'Tramadol_' + stamp()
      const patient = 'Kamran_' + stamp()

      cy.seedProduct({ name, unit: 'tablet', stock: 50, sellingPrice: 100 }).then(({ productId }) => {
        post('/saveClinical', { productId, medicineName: name, rxRequired: true, controlledSubstance: true })
          .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

        recordScript(patient, [{ productId, medicineName: name, quantity: 10 }],
          { doctorName: 'Dr. Saleem', doctorLicense: 'PMC-12345' }).then((rx) => {

          sell(productId, 10, 100, rx.id).then((s) => {
            const invoiceNo = s.body.object
            dispense(rx.id, invoiceNo, [{ productId, quantity: 10 }]).then((d) => {
              expect(d.body.success, JSON.stringify(d.body)).to.eq(true)
            })

            cy.then(() => cy.request('/controlledRegister')).then((reg) => {
              const rows = reg.body.data || reg.body.collection || []
              const mine = rows.filter((x) => x.medicineName === name)
              expect(mine, 'the controlled dispense is on the register').to.have.length(1)
              expect(mine[0].quantity).to.eq(10)
              expect(mine[0].patientName).to.eq(patient)
              expect(mine[0].invoiceNo, 'traceable back to the sale').to.eq(invoiceNo)
            })
          })
        })
      })
    })

    it('an ordinary medicine never reaches the controlled register', () => {
      const name = 'Plain_' + stamp()
      cy.seedProduct({ name, unit: 'tablet', stock: 50, sellingPrice: 50 }).then(({ productId }) => {
        recordScript('Ord_' + stamp(), [{ productId, medicineName: name, quantity: 5 }]).then((rx) => {
          sell(productId, 5, 50, rx.id).then((s) => {
            dispense(rx.id, s.body.object, [{ productId, quantity: 5 }])
          })
          cy.then(() => cy.request('/controlledRegister')).then((reg) => {
            const rows = reg.body.data || reg.body.collection || []
            expect(rows.filter((x) => x.medicineName === name),
              'only controlled substances are registrable').to.have.length(0)
          })
        })
      })
    })

    /**
     * DOCUMENTS A KNOWN GAP — and is meant to FAIL the day it is closed.
     *
     * The business write-up claims the register answers a regulator "with doctor and patient CNIC".
     * It does not: a row carries no prescriber, no prescription id and no CNIC (a prescription has no
     * CNIC field at all). Tracked as pharmacy review item E. When E ships, this test fails — that is
     * the signal to update microservices/docs/pharmacy-prescriptions-use-case.md §8.
     */
    it('KNOWN GAP: the register carries no prescriber, prescription id or patient CNIC', () => {
      const name = 'GapCheck_' + stamp()
      cy.seedProduct({ name, unit: 'tablet', stock: 20, sellingPrice: 100 }).then(({ productId }) => {
        post('/saveClinical', { productId, medicineName: name, rxRequired: false, controlledSubstance: true })

        recordScript('GapPat_' + stamp(), [{ productId, medicineName: name, quantity: 5 }],
          { doctorName: 'Dr. Recorded', doctorLicense: 'PMC-99999' }).then((rx) => {
          sell(productId, 5, 100, rx.id).then((s) => {
            dispense(rx.id, s.body.object, [{ productId, quantity: 5 }])
          })

          cy.then(() => cy.request('/controlledRegister')).then((reg) => {
            const rows = reg.body.data || reg.body.collection || []
            const row = rows.find((x) => x.medicineName === name)
            expect(row, 'the dispense reached the register').to.exist

            // The prescriber WAS recorded on the script — it simply does not travel to the register.
            const keys = Object.keys(row)
            expect(keys, 'no prescriber on the register row')
              .to.not.include.members(['doctorName', 'doctorLicense'])
            expect(keys, 'no prescription id or patient identity on the register row')
              .to.not.include.members(['prescriptionId', 'patientCnic', 'batchNo'])
          })
        })
      })
    })
  })

  // ── The UI journey ───────────────────────────────────────────────────────────

  describe('The screens a pharmacist actually drives', () => {

    it('Prescriptions is reachable, lists the script, and Dispense hands off to the till', () => {
      const name = 'UiFlow_' + stamp()
      const patient = 'UiPat_' + stamp()

      cy.seedProduct({ name, unit: 'tablet', stock: 30, sellingPrice: 100 }).then(({ productId }) => {
        recordScript(patient, [{ productId, medicineName: name, quantity: 5 }]).then((rx) => {

          cy.visit('/businessDashboard')
          cy.window().then((w) => w.showPrescriptions())
          cy.get('#PrescriptionDiv').should('be.visible')

          // The script the pharmacist just took is on screen.
          cy.get('#prescriptionBody', { timeout: 10000 }).should('contain.text', patient)

          // Dispense hands off to the sell screen, with the banner naming what is being dispensed.
          cy.window().then((w) => w.dispenseFromPrescription(rx.id))
          cy.get('#sellDiv').should('be.visible')
          cy.get('#dispenseBanner').should('be.visible')
          cy.get('#dispenseRxLabel').should('contain.text', patient)
          cy.window().its('dispensingPrescriptionId').should('eq', rx.id)

          // Backing out must clear the link, or the next ordinary sale would be charged to this script.
          cy.window().then((w) => w.cancelDispense())
          cy.get('#dispenseBanner').should('not.be.visible')
          cy.window().its('dispensingPrescriptionId').should('be.null')
        })
      })
    })

    it('the clinical + register screens render for a PHARMA user', () => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showClinical())
      cy.get('#ClinicalDiv').should('be.visible')
      cy.get('#clItem').should('exist')          // medicine picker
      cy.get('#clRx').should('exist')            // prescription-required flag
      cy.get('#clControlled').should('exist')    // controlled-substance flag
      cy.get('#clInterA').should('exist')        // interaction pair
      cy.get('#clSeverity').should('exist')

      cy.window().then((w) => w.showPharmAlerts())
      cy.get('#PharmAlertsDiv').should('be.visible')
      cy.get('#tableControlled').should('exist')
    })
  })
})
