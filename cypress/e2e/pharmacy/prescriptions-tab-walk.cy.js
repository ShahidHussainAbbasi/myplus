/**
 * RX-WALK — the Prescriptions tab, driven the way a pharmacist drives it.
 *
 * The other pharmacy specs post straight to /addPrescription and /dispensePrescription, so they prove the
 * SERVER. Not one of them presses Save Prescription, Dispense, Cancel or Complete Sale on the screen — and
 * the till hand-off (cart → main.js → dispensePrescription) is exactly the seam no API call can reach.
 * Every case here clicks, types and reads the screen, and checks the server afterwards.
 *
 * One screenshot per step (cypress/screenshots/prescriptions-tab-walk.cy.js/RX-xx-n-*.png) — the Test Book
 * walk is written from these.
 *
 * Doc:      microservices/docs/pharmacy-prescriptions-use-case.md
 * Run headed:
 *   npx cypress run --spec cypress/e2e/pharmacy/prescriptions-tab-walk.cy.js --headed --browser chrome
 *
 * Signs in as owner.pharma (PHARMA owner, no demo write cap). Cleanup (after): every script this run
 * left live is cancelled, and the clinical flags it set are switched back off — see `cleanup()`.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const iso = (d) => d.toISOString().slice(0, 10)
const daysFromToday = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return iso(d) }

const post = (url, body) =>
  cy.request({ method: 'POST', url, body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

const shot = (name) => cy.screenshot(name, { capture: 'viewport', overwrite: true })

// What the run created, so after() can leave the tenant as it found it.
const created = { rxIds: [], flagged: [] }

const readRx = (rxId) =>
  cy.request(`/getPrescription?id=${rxId}`).then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    return r.body.data
  })

/** Script seeded through the API — for cases whose subject is NOT intake. */
const seedScript = (patient, items, extra = {}) =>
  post('/addPrescription', Object.assign({ patientName: patient, items }, extra)).then((r) => {
    expect(r.body.success, `seed script: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq(true)
    created.rxIds.push(r.body.data.id)
    return r.body.data
  })

const flag = (productId, name, rxRequired, controlledSubstance) =>
  post('/saveClinical', { productId, medicineName: name, rxRequired, controlledSubstance }).then((r) => {
    expect(r.body.success, `flag ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    created.flagged.push({ productId, name })
  })

/** Pharmacy → Prescriptions, through the sidebar like a person. */
const openPrescriptionsTab = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.get('#snavPharmacy').should('be.visible')
  // The group may already be open (it remembers the active section); clicking it again would CLOSE it.
  cy.get('#snavPharmacy').then(($g) => {
    if (!$g.find('a:contains("Prescriptions")').is(':visible')) cy.wrap($g).find('.snav-btn').click()
  })
  cy.get('#snavPharmacy').contains('a', 'Prescriptions').should('be.visible').click()
  cy.waitForAppReady()
  cy.get('#PrescriptionDiv').should('be.visible')
  cy.get('#prescriptionBody').should('exist')
}

/** The Recent-prescriptions row for a patient. */
const rowOf = (patient) => cy.contains('#prescriptionBody tr', patient, { timeout: 15000 })

/** Pick a medicine in the Add-item row (a bootstrap-select over #rxMedicine) and add the line. */
const addLine = (productId, qty, dosage, freq, dur) => {
  cy.get(`#rxMedicine option[value="${productId}"]`, { timeout: 20000 }).should('exist')
  cy.get('#rxMedicine').select(String(productId), { force: true })
  cy.get('#rxQty').clear().type(String(qty))
  if (dosage) cy.get('#rxDosage').clear().type(dosage)
  if (freq) cy.get('#rxFreq').clear().type(freq)
  if (dur) cy.get('#rxDuration').clear().type(dur)
  cy.get('#PrescriptionDiv').contains('button', /^Add$/).click()
}

/** The body the till posted to /dispensePrescription — the evidence for what it recorded. */
const dispenseBody = (interception) => {
  const b = interception.request.body
  return typeof b === 'string' ? JSON.parse(b) : b
}


/**
 * The sale, the way a pharmacist does it with the mouse — no barcode box, no function keys (scanning ships OFF
 * for every tenant). `prefix` names a screenshot per step, for the guide on the walk page.
 */
const sellByMouse = (productId, qty, rate, customer, prefix) => {
  const snap = (n) => { if (prefix) shot(`${prefix}-${n}`) }
  cy.get('#sellDiv').should('be.visible')
  cy.waitForAppReady()
  // 1. Item: pick the medicine; its price fills in.
  cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')
  cy.get('#sellItemDD').select(String(productId), { force: true })
  cy.get('#sellSellRate').should('not.have.value', '')
  // 2. Qty, then Add to Cart.
  cy.get('#sellQuantity').clear().type(String(qty))
  snap('a-item-and-qty')
  cy.get('#addInviceItem').click()
  cy.window().its('data').should('have.length', 1)
  // the mouse path keeps the typed quantity as text ('6'); the scan box stored a number
  cy.window().its('data.0.quantity').then((q) => expect(Number(q)).to.eq(qty))
  snap('b-in-cart')
  // 3. Customer: Enter Manually, type the patient's name.
  cy.get('#btnModeManual').click()
  cy.get('#sellCN').should('be.visible').clear().type(customer)
  // 4. Amount Received = the total, then Complete Sale.
  cy.get('#sellRec').clear().type(String(qty * rate))
  snap('c-customer-and-cash')
  cy.get('#addSell').click()
  // 5. "Complete this sale?" -> Complete Sale.
  cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).should('be.visible')
  snap('d-confirm')
  cy.get('[data-ui-confirm="ok"]').click({ force: true })
}

describe('RX-WALK — the Prescriptions tab, end to end on the screen', () => {
  beforeEach(() => { cy.loginAsPharmaOwner() })

  after(() => cleanup())

  // ── RX-01 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-01 Pharmacy → Prescriptions opens the intake form and the Recent list', () => {
    openPrescriptionsTab()
    cy.window().its('MODULE').should('eq', 'PHARMA')
    ;['#rxPatient', '#rxPatientPhone', '#rxDoctor', '#rxLicense', '#rxDiagnosis', '#rxValidUntil', '#rxQty']
      .forEach((s) => cy.get(s).should('be.visible'))
    cy.get('#PrescriptionDiv').contains('button', 'Save Prescription').should('be.visible')
    cy.get('#tablePrescription thead').should('contain.text', 'Patient').and('contain.text', 'Status')
    shot('RX-01-1-tab-open')
  })

  // ── RX-02 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-02 the form refuses an incomplete script before it reaches the server', () => {
    cy.seedProduct({ name: `RxVal_${uniq()}`, unit: 'tablet', stock: 10, sellingPrice: 10 }).then(({ productId }) => {
      cy.intercept('POST', '**/addPrescription').as('add')
      openPrescriptionsTab()

      // 1. Nothing typed → patient is required.
      cy.contains('button', 'Save Prescription').click()
      cy.get('#globalError').should('be.visible').and('contain.text', 'Patient name is required')
      shot('RX-02-1-no-patient')

      // 2. Patient but no lines → at least one item.
      cy.get('#rxPatient').type('Val_' + uniq())
      cy.contains('button', 'Save Prescription').click()
      cy.get('#globalError').should('contain.text', 'Add at least one prescribed item')
      shot('RX-02-2-no-items')

      // 3. Add with no medicine picked.
      cy.get('#rxQty').type('5')
      cy.get('#PrescriptionDiv').contains('button', /^Add$/).click()
      cy.get('#globalError').should('contain.text', 'Pick a medicine')

      // 4. Medicine but quantity 0.
      cy.get(`#rxMedicine option[value="${productId}"]`, { timeout: 20000 }).should('exist')
      cy.get('#rxMedicine').select(String(productId), { force: true })
      cy.get('#rxQty').clear().type('0')
      cy.get('#PrescriptionDiv').contains('button', /^Add$/).click()
      cy.get('#globalError').should('contain.text', 'Enter a quantity')
      cy.get('#rxItemsBody tr').should('have.length', 0)
      shot('RX-02-3-qty-zero')

      // None of the four ever posted.
      cy.get('@add.all').should('have.length', 0)
    })
  })

  // ── RX-03 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-03 lines can be added and removed before saving', () => {
    const a = `RxLineA_${uniq()}`, b = `RxLineB_${uniq()}`
    cy.seedProduct({ name: a, unit: 'tablet', stock: 10, sellingPrice: 10 }).then((pa) => {
      cy.seedProduct({ name: b, unit: 'tablet', stock: 10, sellingPrice: 10 }).then((pb) => {
        openPrescriptionsTab()
        addLine(pa.productId, 6, '1 tab', 'BD', '3d')
        addLine(pb.productId, 10, '1 tab', 'TDS', '5d')
        cy.get('#rxItemsBody tr').should('have.length', 2)
        cy.get('#rxItemsBody tr').eq(0).should('contain.text', a).and('contain.text', '6').and('contain.text', 'BD')
        // the line inputs clear for the next line; the medicine stays picked
        cy.get('#rxQty').should('have.value', '')
        shot('RX-03-1-two-lines')

        cy.get('#rxItemsBody tr').eq(0).find('button.btn-danger').click()
        cy.get('#rxItemsBody tr').should('have.length', 1).and('contain.text', b)
        shot('RX-03-2-line-removed')
      })
    })
  })

  // ── RX-04 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-04 Save Prescription records a PENDING script and moves no stock', () => {
    const name = `RxSave_${uniq()}`, patient = `Ayesha_${uniq()}`
    cy.seedProduct({ name, unit: 'tablet', stock: 40, sellingPrice: 20 }).then(({ productId }) => {
      cy.request(`/productStock?productId=${productId}`).its('body.stock').then((stockBefore) => {
        cy.intercept('POST', '**/addPrescription').as('add')
        openPrescriptionsTab()
        cy.get('#rxPatient').type(patient)
        cy.get('#rxPatientPhone').type('03001234567')
        cy.get('#rxDoctor').type('Dr. Saleem')
        cy.get('#rxLicense').type('PMC-12345')
        cy.get('#rxDiagnosis').type('Chest infection')
        cy.get('#rxValidUntil').type(daysFromToday(30))
        addLine(productId, 6, '1 tab', 'BD', '3d')
        shot('RX-04-1-filled')

        cy.contains('button', 'Save Prescription').click()
        cy.wait('@add').its('response.body').then((b) => {
          expect(b.success, JSON.stringify(b)).to.eq(true)
          created.rxIds.push(b.data.id)
          expect(b.data.status).to.eq('PENDING')
          expect(b.data.doctorLicense).to.eq('PMC-12345')
          expect(b.data.validUntil).to.eq(daysFromToday(30))
          expect(b.data.items[0]).to.include({ productId, quantity: 6, dosage: '1 tab', frequency: 'BD', duration: '3d' })
        })
        cy.get('#saleSuccess').should('contain.text', 'Prescription recorded')
        // the form is reset for the next patient
        cy.get('#rxPatient').should('have.value', '')
        cy.get('#rxItemsBody tr').should('have.length', 0)

        rowOf(patient).within(() => {
          cy.get('td').eq(1).should('have.text', 'Dr. Saleem')
          cy.get('td').eq(2).should('have.text', '1')
          cy.get('td').eq(3).should('have.text', 'PENDING')
          cy.contains('button', 'Dispense').should('be.visible')
          cy.contains('button', 'Cancel').should('be.visible')
        })
        shot('RX-04-2-saved-in-list')

        // A clinical entry never touches stock or money.
        cy.request(`/productStock?productId=${productId}`).its('body.stock').should('eq', stockBefore)
      })
    })
  })

  // ── RX-05 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-05 a "Valid until" in the past is refused by the server, with the reason', () => {
    const name = `RxPast_${uniq()}`, patient = `Past_${uniq()}`
    cy.seedProduct({ name, unit: 'tablet', stock: 10, sellingPrice: 10 }).then(({ productId }) => {
      openPrescriptionsTab()
      cy.get('#rxPatient').type(patient)
      cy.get('#rxValidUntil').type(daysFromToday(-1))
      addLine(productId, 5)
      cy.contains('button', 'Save Prescription').click()
      cy.get('#globalError').should('be.visible').and('contain.text', "'Valid until' cannot be before the prescribed date")
      // nothing was recorded, and the typed script is still on screen to correct
      cy.get('#rxItemsBody tr').should('have.length', 1)
      cy.get('#prescriptionBody').should('not.contain.text', patient)
      shot('RX-05-1-past-validity-refused')
    })
  })

  // ── RX-06 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-06 Cancel asks first; declining changes nothing, confirming makes it CANCELLED', () => {
    const name = `RxCancel_${uniq()}`, patient = `Withdrawn_${uniq()}`
    cy.seedProduct({ name, unit: 'tablet', stock: 10, sellingPrice: 10 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 10 }]).then((rx) => {
        openPrescriptionsTab()

        rowOf(patient).contains('button', 'Cancel').click()
        cy.get('.uiC-title').should('contain.text', 'Cancel this prescription?')
        shot('RX-06-1-confirm-dialog')
        cy.get('.uiC-cancel').click()
        rowOf(patient).find('td').eq(3).should('have.text', 'PENDING')
        readRx(rx.id).its('status').should('eq', 'PENDING')

        rowOf(patient).contains('button', 'Cancel').click()
        cy.get('[data-ui-confirm="ok"]').click()
        cy.get('#saleSuccess').should('contain.text', 'Prescription cancelled')
        rowOf(patient).within(() => {
          cy.get('td').eq(3).should('have.text', 'CANCELLED')
          // the ACTION cell — the patient cell keeps its Contact-360 button
          cy.get('td').eq(5).should('have.text', 'cancelled').find('button').should('not.exist')
        })
        shot('RX-06-2-cancelled')
        readRx(rx.id).its('status').should('eq', 'CANCELLED')

        // the server holds the line too: a cancelled script cannot be dispensed
        post('/dispensePrescription', { prescriptionId: rx.id, invoiceNo: 'INV-X', items: [{ productId, quantity: 1 }] })
          .its('body.message').should('match', /cancel/i)
      })
    })
  })

  // ── RX-07 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-07 Dispense hands off to the till with a banner; "cancel" un-links it', () => {
    const name = `RxHand_${uniq()}`, patient = `Hand_${uniq()}`
    cy.seedProduct({ name, unit: 'tablet', stock: 10, sellingPrice: 10 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 4 }]).then((rx) => {
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        cy.get('#sellDiv').should('be.visible')
        cy.get('#PrescriptionDiv').should('not.be.visible')
        cy.get('#dispenseBanner').should('be.visible')
        cy.get('#dispenseRxLabel').should('have.text', `Rx #${rx.id} — ${patient}`)
        cy.window().its('dispensingPrescriptionId').should('eq', rx.id)
        cy.waitForAppReady()
        shot('RX-07-1-till-with-banner')

        cy.get('#dispenseBanner').contains('a', /cancel/i).click()
        cy.get('#dispenseBanner').should('not.be.visible')
        cy.window().its('dispensingPrescriptionId').should('be.null')
        shot('RX-07-2-banner-cleared')
        readRx(rx.id).its('status').should('eq', 'PENDING')
      })
    })
  })

  // ── RX-08 ⭐ ─────────────────────────────────────────────────────────────────────────────────────
  /**
   * ⭐ THE SEAM NO OTHER SPEC REACHES: Complete Sale at the till → the dispense recorded against the script.
   *
   * main.js clears the cart (resetCart → `data = []`) to close the checkout, and only THEN calls
   * dispensePrescription(), which builds its lines from that same cart. If the order is wrong the
   * dispense posts no lines: the sale and stock are right, the script stays PENDING, and the pharmacist
   * is still told "Dispense recorded".
   */
  it('RX-08 ⭐ a full sale at the till marks the script FULLY_DISPENSED', () => {
    const stamp = uniq(), name = `RxFull_${stamp}`, sku = `RXF${stamp}`, patient = `Full_${stamp}`
    cy.seedProduct({ name, sku, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 6 }]).then((rx) => {
        cy.intercept('POST', '**/addSell').as('sale')
        cy.intercept('POST', '**/dispensePrescription').as('disp')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        shot('RX-08-1-till-with-banner')
        sellByMouse(productId, 6, 20, patient, 'RX-08-2')

        cy.wait('@sale', { timeout: 30000 }).its('response.body.status').should('eq', 'SUCCESS')
        cy.wait('@disp', { timeout: 30000 }).then(({ request, response }) => {
          const body = typeof request.body === 'string' ? JSON.parse(request.body) : request.body
          expect(body.prescriptionId).to.eq(rx.id)
          expect(body.invoiceNo, 'tied to the sale invoice').to.be.a('string').and.not.be.empty
          expect(body.items, 'the till sends the lines it sold').to.deep.eq([{ productId, quantity: 6 }])
          expect(response.body.data.status).to.eq('FULLY_DISPENSED')
        })
        cy.get('#saleSuccess').should('contain.text', `Dispense recorded against Rx #${rx.id}`)
        cy.get('#dispenseBanner').should('not.be.visible')
        shot('RX-08-3-sale-done')

        readRx(rx.id).then((after) => {
          expect(after.status).to.eq('FULLY_DISPENSED')
          expect(after.items[0].dispensedQuantity).to.eq(6)
        })
        openPrescriptionsTab()
        rowOf(patient).within(() => {
          cy.get('td').eq(3).should('have.text', 'FULLY_DISPENSED')
          cy.get('td').eq(5).should('have.text', 'dispensed')
        })
        shot('RX-08-4-list-dispensed')
      })
    })
  })

  // ── RX-09 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-09 a part sale leaves it PARTIALLY_DISPENSED and still dispensable', () => {
    const stamp = uniq(), name = `RxPart_${stamp}`, sku = `RXP${stamp}`, patient = `Part_${stamp}`
    cy.seedProduct({ name, sku, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 10 }]).then((rx) => {
        cy.intercept('POST', '**/dispensePrescription').as('disp')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        sellByMouse(productId, 4, 20, patient)
        cy.wait('@disp', { timeout: 30000 }).then((i) => {
          expect(dispenseBody(i).items, 'the till sends the 4 it sold').to.deep.eq([{ productId, quantity: 4 }])
        })
        readRx(rx.id).then((after) => {
          expect(after.status).to.eq('PARTIALLY_DISPENSED')
          expect(after.items[0].dispensedQuantity).to.eq(4)
        })
        openPrescriptionsTab()
        rowOf(patient).within(() => {
          cy.get('td').eq(3).should('have.text', 'PARTIALLY_DISPENSED')
          cy.contains('button', 'Dispense').should('be.visible')
        })
        shot('RX-09-1-partial')
      })
    })
  })

  // ── RX-10 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-10 selling more than outstanding records only the remainder, and says so', () => {
    const stamp = uniq(), name = `RxOver_${stamp}`, sku = `RXO${stamp}`, patient = `Over_${stamp}`
    cy.seedProduct({ name, sku, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 5 }]).then((rx) => {
        cy.intercept('POST', '**/dispensePrescription').as('disp')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        sellByMouse(productId, 8, 20, patient)
        cy.wait('@disp', { timeout: 30000 }).then((i) => {
          expect(dispenseBody(i).items, 'the till sends the 8 it sold').to.deep.eq([{ productId, quantity: 8 }])
        })
        cy.get('#globalError').should('contain.text', '8 sold but only 5 was still outstanding')
        shot('RX-10-1-over-warning')
        readRx(rx.id).then((after) => {
          expect(after.status).to.eq('FULLY_DISPENSED')
          expect(after.items[0].dispensedQuantity, 'capped at the script').to.eq(5)
        })
      })
    })
  })

  // ── RX-11 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-11 a prescription-only medicine is refused at a plain sale, sold through Dispense', () => {
    const stamp = uniq(), name = `RxOnly_${stamp}`, sku = `RXR${stamp}`, patient = `RxOnly_${stamp}`
    cy.seedProduct({ name, sku, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      flag(productId, name, true, false)
      cy.intercept('POST', '**/addSell').as('sale')

      // 1. A walk-in sale, no script.
      cy.visit('/businessDashboard')
      cy.get('#sellType').select('sellDiv', { force: true })
      cy.waitForAppReady()
      cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')
      cy.get('#sellItemDD').select(String(productId), { force: true })
      cy.get('#sellQuantity').clear().type('1')
      cy.get('#addInviceItem').click()
      // the courtesy notice, as soon as it goes into the cart — before any money is involved
      cy.get('#globalError').should('contain.text', 'is prescription-only')
      cy.get('#btnModeManual').click()
      cy.get('#sellCN').clear().type('Walk-in ' + stamp)
      cy.get('#sellRec').clear().type('20')
      cy.get('#addSell').click()
      cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).click({ force: true })
      cy.wait('@sale', { timeout: 30000 }).its('response.body').then((b) => {
        expect(b.status, 'the server refuses it').to.not.eq('SUCCESS')
        expect(String(b.message)).to.match(/prescription-only/i)
      })
      shot('RX-11-1-walk-in-refused')

      // 2. Same medicine, started from a script.
      seedScript(patient, [{ productId, medicineName: name, quantity: 2 }]).then((rx) => {
        cy.intercept('POST', '**/addSell').as('sale2')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        sellByMouse(productId, 2, 20, patient)
        cy.wait('@sale2', { timeout: 30000 }).its('response.body.status').should('eq', 'SUCCESS')
        shot('RX-11-2-dispensed-sale-ok')
      })
    })
  })

  // ── RX-12 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-12 a SEVERE interaction on the script must be acknowledged; declining backs out', () => {
    const stamp = uniq(), a = `RxIntA_${stamp}`, b = `RxIntB_${stamp}`, patient = `Inter_${stamp}`
    cy.seedProduct({ name: a, unit: 'tablet', stock: 10, sellingPrice: 10 }).then((pa) => {
      cy.seedProduct({ name: b, unit: 'tablet', stock: 10, sellingPrice: 10 }).then((pb) => {
        post('/addInteraction', { productId1: pa.productId, productId2: pb.productId, severity: 'SEVERE',
          description: 'Bleeding risk' }).its('body.success').should('eq', true)
        seedScript(patient, [
          { productId: pa.productId, medicineName: a, quantity: 2 },
          { productId: pb.productId, medicineName: b, quantity: 2 },
        ]).then((rx) => {
          openPrescriptionsTab()
          rowOf(patient).contains('button', 'Dispense').click()
          cy.get('.uiC-title', { timeout: 15000 }).should('contain.text', 'Severe drug interaction')
          cy.get('.uiC-card, [role="dialog"]').first().should('contain.text', 'Bleeding risk')
          shot('RX-12-1-severe-dialog')
          cy.get('.uiC-cancel').click()
          cy.get('#dispenseBanner').should('not.be.visible')
          cy.window().its('dispensingPrescriptionId').should('be.null')
          shot('RX-12-2-declined')

          // Acknowledging keeps the dispense going.
          openPrescriptionsTab()
          rowOf(patient).contains('button', 'Dispense').click()
          cy.get('[data-ui-confirm="ok"]', { timeout: 15000 }).should('contain.text', 'Dispense anyway').click()
          cy.get('#dispenseBanner').should('be.visible')
          cy.window().its('dispensingPrescriptionId').should('eq', rx.id)
          cy.get('#dispenseBanner').contains('a', /cancel/i).click()
        })
      })
    })
  })

  // ── RX-13 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-13 a lapsed script reads EXPIRED, offers no Dispense, and the server refuses it', () => {
    const name = `RxExp_${uniq()}`, patient = `Expired_${uniq()}`
    cy.seedProduct({ name, unit: 'tablet', stock: 10, sellingPrice: 10 }).then(({ productId }) => {
      // A real lapsed script: written 30 days ago, valid until yesterday.
      seedScript(patient, [{ productId, medicineName: name, quantity: 3 }],
        { prescribedDate: daysFromToday(-30), validUntil: daysFromToday(-1) }).then((rx) => {
        openPrescriptionsTab()
        rowOf(patient).within(() => {
          cy.get('td').eq(3).should('have.text', 'EXPIRED')
          cy.get('td').eq(5).should('have.text', 'expired').find('button').should('not.exist')
        })
        shot('RX-13-1-expired-row')
        post('/dispensePrescription', { prescriptionId: rx.id, invoiceNo: 'INV-EXP', items: [{ productId, quantity: 1 }] })
          .its('body.message').should('match', /expired/i)
      })
    })
  })

  // ── RX-14 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-14 a controlled dispense lands on Alerts & Register with its invoice', () => {
    const stamp = uniq(), name = `RxCtl_${stamp}`, sku = `RXC${stamp}`, patient = `Ctl_${stamp}`
    cy.seedProduct({ name, sku, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      flag(productId, name, true, true)
      seedScript(patient, [{ productId, medicineName: name, quantity: 3 }],
        { doctorName: 'Dr. Saleem', doctorLicense: 'PMC-12345' }).then(() => {
        cy.intercept('POST', '**/addSell').as('sale')
        cy.intercept('POST', '**/dispensePrescription').as('disp')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        sellByMouse(productId, 3, 20, patient)
        cy.wait('@sale', { timeout: 30000 }).its('response.body.object').then((invoiceNo) => {
          cy.wait('@disp', { timeout: 30000 })
          cy.get('#snavPharmacy').then(($g) => {
            if (!$g.find('a:contains("Alerts")').is(':visible')) cy.wrap($g).find('.snav-btn').click()
          })
          cy.get('#snavPharmacy').contains('a', 'Alerts').click()
          cy.get('#PharmAlertsDiv').should('be.visible')
          cy.contains('#controlledBody tr', name, { timeout: 15000 }).within(() => {
            cy.get('td').eq(2).should('have.text', '3')
            cy.get('td').eq(3).should('have.text', patient)
            cy.get('td').eq(4).should('have.text', invoiceNo)
          })
          shot('RX-14-1-controlled-register')
        })
      })
    })
  })
})

/**
 * CLEANUP — leave the tenant as the run found it.
 * - every script this run created that is still live (PENDING / PARTIALLY_DISPENSED) is cancelled, so the
 *   Recent list does not fill with dispensable test scripts;
 * - every clinical flag this run switched on is switched back off.
 * Prescriptions and dispenses themselves cannot be deleted (no endpoint, by design: they are a clinical
 * record) — cancelled is the terminal state a person would leave them in. Seeded products stay (they carry
 * sales); their names are prefixed `Rx…_<stamp>`.
 */
function cleanup() {
  cy.loginAsPharmaOwner()
  created.rxIds.forEach((id) => {
    cy.request({ url: `/getPrescription?id=${id}`, failOnStatusCode: false }).then((r) => {
      const s = r.body && r.body.data && r.body.data.status
      if (s === 'PENDING' || s === 'PARTIALLY_DISPENSED') post('/cancelPrescription', { prescriptionId: id })
    })
  })
  created.flagged.forEach(({ productId, name }) =>
    post('/saveClinical', { productId, medicineName: name, rxRequired: false, controlledSubstance: false }))
}
