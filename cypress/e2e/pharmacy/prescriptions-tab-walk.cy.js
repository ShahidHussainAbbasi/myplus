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
 * RX-FILL (2026-10-09): Dispense fills the cart from the script; the counter adjusts with + / −.
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
 * RX-FILL — after Dispense the till fills the cart itself, with what is still owed on the script. Wait for that,
 * not for a request: the fill reads three things per medicine and adds the lines one at a time.
 */
const waitFilled = () => {
  cy.get('#sellDiv').should('be.visible')
  cy.window({ timeout: 30000 }).its('dispensingFilled').should('eq', true)
  cy.waitForAppReady()
}

/** The cart line for a product (window.data is the cart). */
const cartLine = (productId) =>
  cy.window().its('data').then((d) => {
    const l = d.find((x) => String(x.productId) === String(productId))
    expect(l, `cart line for product ${productId}`).to.exist
    return l
  })

/** Pieces on a line, in the unit the script is written in — the same mapping the dispense uses. */
const piecesOf = (l) => (String(l.soldUnit || '').toUpperCase() === 'LOOSE' ? Number(l.soldQuantity) : Number(l.quantity))

/** Press + or − on a cart line `times` times — the buttons the counter uses after asking the patient. */
const step = (productId, delta, times) => {
  for (let k = 0; k < times; k++) {
    cy.get(`.ctr-step[data-pid="${productId}"][data-d="${delta}"]`, { timeout: 10000 }).first().click()
  }
}

/** Name the patient, take exactly the payable, Complete Sale, confirm. `prefix` names a screenshot per step. */
const completeFilled = (customer, prefix) => {
  const snap = (n) => { if (prefix) shot(`${prefix}-${n}`) }
  cy.get('#btnModeManual').click()
  cy.get('#sellCN').should('be.visible').clear().type(customer)
  cy.window().then((w) => {
    const pay = typeof w.sellPayable === 'function'
      ? Number(w.sellPayable())
      : w.data.reduce((a, l) => a + (Number(l.totalAmount) || 0), 0)
    cy.get('#sellRec').clear().type(pay.toFixed(2))
  })
  snap('c-customer-and-cash')
  cy.get('#addSell').click()
  cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).should('be.visible')
  snap('d-confirm')
  cy.get('[data-ui-confirm="ok"]').click({ force: true })
}

/** A divisible product: packs of 10, sellable by the tablet, stocked through a purchase (copied from U8's spec). */
const loosePackProduct = (name, packs) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: { name, sellingPrice: 120, unit: 'pack', packSize: 10, looseUnit: 'tablet', looseUnitPlural: 'tablets',
      allowLoose: true, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    const productId = r.body.data.id
    return cy.request({
      method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
      body: { productId, quantity: packs, 'stock.batchNo': `RXL${uniq()}`, 'stock.bpurchaseRate': 100,
        'stock.bsellRate': 120, totalAmount: packs * 100, netAmount: packs * 100, purchaseInvoiceNo: `RXL-${uniq()}` },
    }).then((p) => {
      expect(p.body.status, `stock in: ${JSON.stringify(p.body).substring(0, 200)}`).to.eq('SUCCESS')
      return productId
    })
  })

/** Pharmacy → Prescriptions through the sidebar WITHOUT reloading — so a cart built before it survives. */
const toPrescriptionsNoReload = () => {
  cy.get('#snavPharmacy').then(($g) => {
    if (!$g.find('a:contains("Prescriptions")').is(':visible')) cy.wrap($g).find('.snav-btn').click()
  })
  cy.get('#snavPharmacy').contains('a', 'Prescriptions').should('be.visible').click()
  cy.get('#PrescriptionDiv').should('be.visible')
  cy.waitForAppReady()
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
  it('RX-07 Dispense opens the till with the script already in the cart; "cancel" drops both', () => {
    const name = `RxHand_${uniq()}`, patient = `Hand_${uniq()}`
    cy.seedProduct({ name, unit: 'tablet', stock: 10, sellingPrice: 10 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 4 }]).then((rx) => {
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        cy.get('#PrescriptionDiv').should('not.be.visible')
        cy.get('#dispenseBanner').should('be.visible')
        cy.get('#dispenseRxLabel').should('have.text', `Rx #${rx.id} — ${patient}`)
        cy.window().its('dispensingPrescriptionId').should('eq', rx.id)
        waitFilled()
        // RX-FILL-1: the prescribed medicine is in the cart at what is owed, with + / − on its line.
        cy.window().its('data').should('have.length', 1)
        cartLine(productId).then((l) => expect(piecesOf(l)).to.eq(4))
        cy.get(`.ctr-step[data-pid="${productId}"]`).should('have.length', 2)
        cy.get('#dispenseFillNote').should('contain.text', '4 left on the script · 4 in this sale')
        shot('RX-07-1-till-with-banner')

        // "cancel" on the banner drops the link AND the lines it brought.
        cy.get('#dispenseBanner').contains('a', /cancel/i).click()
        cy.get('#dispenseBanner').should('not.be.visible')
        cy.window().its('dispensingPrescriptionId').should('be.null')
        cy.window().its('data').should('have.length', 0)
        shot('RX-07-2-banner-cleared')
        readRx(rx.id).its('status').should('eq', 'PENDING')
      })
    })
  })

  // ── RX-08 ⭐ ─────────────────────────────────────────────────────────────────────────────────────
  /**
   * ⭐ Complete Sale at the till → the dispense recorded against the script. The cart was filled by Dispense;
   * nothing is picked by hand. (RX-DISP-1: main.js must take the lines BEFORE it clears the cart.)
   */
  it('RX-08 ⭐ the filled cart, completed as it is, marks the script FULLY_DISPENSED', () => {
    const stamp = uniq(), name = `RxFull_${stamp}`, patient = `Full_${stamp}`
    cy.seedProduct({ name, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 6 }]).then((rx) => {
        cy.intercept('POST', '**/addSell').as('sale')
        cy.intercept('POST', '**/dispensePrescription').as('disp')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        waitFilled()
        cartLine(productId).then((l) => expect(piecesOf(l)).to.eq(6))
        shot('RX-08-1-till-filled')
        completeFilled(patient, 'RX-08-2')

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
  it('RX-09 − lowers a line; the rest stays owed and the next visit fills only the remainder', () => {
    const stamp = uniq(), name = `RxPart_${stamp}`, patient = `Part_${stamp}`
    cy.seedProduct({ name, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 10 }]).then((rx) => {
        cy.intercept('POST', '**/dispensePrescription').as('disp')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        waitFilled()
        cartLine(productId).then((l) => expect(piecesOf(l)).to.eq(10))
        // The patient takes 4 today.
        step(productId, -1, 6)
        cartLine(productId).then((l) => expect(piecesOf(l)).to.eq(4))
        cy.get('#dispenseFillNote').should('contain.text', '4 in this sale — 6 left for another day')
        shot('RX-09-1-minus-to-4')
        completeFilled(patient)
        cy.wait('@disp', { timeout: 30000 }).then((i) => {
          expect(dispenseBody(i).items, 'the till sends the 4 it sold').to.deep.eq([{ productId, quantity: 4 }])
        })
        readRx(rx.id).then((after) => {
          expect(after.status).to.eq('PARTIALLY_DISPENSED')
          expect(after.items[0].dispensedQuantity).to.eq(4)
        })

        // Next visit: Dispense again fills only the 6 still owed.
        openPrescriptionsTab()
        rowOf(patient).within(() => cy.get('td').eq(3).should('have.text', 'PARTIALLY_DISPENSED'))
        rowOf(patient).contains('button', 'Dispense').click()
        waitFilled()
        cartLine(productId).then((l) => expect(piecesOf(l), 'only the remainder').to.eq(6))
        shot('RX-09-2-second-visit-remainder')
        cy.get('#dispenseBanner').contains('a', /cancel/i).click()
      })
    })
  })

  // ── RX-10 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-10 + above the script is shown before the sale, and only the prescribed amount is recorded', () => {
    const stamp = uniq(), name = `RxOver_${stamp}`, patient = `Over_${stamp}`
    cy.seedProduct({ name, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 5 }]).then((rx) => {
        cy.intercept('POST', '**/dispensePrescription').as('disp')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        waitFilled()
        step(productId, 1, 3)
        cartLine(productId).then((l) => expect(piecesOf(l)).to.eq(8))
        cy.get('#dispenseFillNote .rx-over').should('contain.text', '3 more than prescribed')
        shot('RX-10-1-plus-above-script')
        completeFilled(patient)
        cy.wait('@disp', { timeout: 30000 }).then((i) => {
          expect(dispenseBody(i).items, 'the till sends the 8 it sold').to.deep.eq([{ productId, quantity: 8 }])
        })
        cy.get('#globalError').should('contain.text', '8 sold but only 5 was still outstanding')
        readRx(rx.id).then((after) => {
          expect(after.status).to.eq('FULLY_DISPENSED')
          expect(after.items[0].dispensedQuantity, 'capped at the script').to.eq(5)
        })
      })
    })
  })

  // ── RX-11 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-11 a prescription-only medicine is refused at a plain sale, sold through Dispense', () => {
    const stamp = uniq(), name = `RxOnly_${stamp}`, patient = `RxOnly_${stamp}`
    cy.seedProduct({ name, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
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

      // 2. Same medicine, started from a script: the cart is filled and the sale goes through.
      seedScript(patient, [{ productId, medicineName: name, quantity: 2 }]).then(() => {
        cy.intercept('POST', '**/addSell').as('sale2')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        waitFilled()
        completeFilled(patient)
        cy.wait('@sale2', { timeout: 30000 }).its('response.body.status').should('eq', 'SUCCESS')
        shot('RX-11-2-dispensed-sale-ok')
      })
    })
  })

  // ── RX-12 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-12 a SEVERE interaction must be acknowledged; declining backs out and fills nothing', () => {
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
          cy.window().its('data').should('have.length', 0)
          shot('RX-12-2-declined')

          // Acknowledging fills both medicines.
          openPrescriptionsTab()
          rowOf(patient).contains('button', 'Dispense').click()
          cy.get('[data-ui-confirm="ok"]', { timeout: 15000 }).should('contain.text', 'Dispense anyway').click()
          cy.get('#dispenseBanner').should('be.visible')
          cy.window().its('dispensingPrescriptionId').should('eq', rx.id)
          waitFilled()
          cy.window().its('data').should('have.length', 2)
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
    const stamp = uniq(), name = `RxCtl_${stamp}`, patient = `Ctl_${stamp}`
    cy.seedProduct({ name, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      flag(productId, name, true, true)
      seedScript(patient, [{ productId, medicineName: name, quantity: 3 }],
        { doctorName: 'Dr. Saleem', doctorLicense: 'PMC-12345' }).then(() => {
        cy.intercept('POST', '**/addSell').as('sale')
        cy.intercept('POST', '**/dispensePrescription').as('disp')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        waitFilled()
        completeFilled(patient)
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

  // ── RX-15 ────────────────────────────────────────────────────────────────────────────────────────
  /**
   * A medicine sold by the tablet: the script's 15 tablets fill as a LOOSE line of 15, and + / − step in TABLETS.
   * counter.js used to skip loose lines entirely — the commonest pharmacy line had no + / − at all.
   */
  it('RX-15 a medicine sold loose fills in tablets, and − steps one tablet', () => {
    const stamp = uniq(), name = `RxLoose_${stamp}`, patient = `Loose_${stamp}`
    loosePackProduct(name, 5).then((productId) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 15 }]).then((rx) => {
        cy.intercept('POST', '**/dispensePrescription').as('disp')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        waitFilled()
        cartLine(productId).then((l) => {
          expect(String(l.soldUnit).toUpperCase(), 'a loose line').to.eq('LOOSE')
          expect(Number(l.soldQuantity), '15 tablets, not 15 packs').to.eq(15)
        })
        shot('RX-15-1-loose-15-tablets')
        step(productId, -1, 1)
        cartLine(productId).then((l) => expect(Number(l.soldQuantity)).to.eq(14))
        cy.get('#dispenseFillNote').should('contain.text', '14 in this sale — 1 left for another day')
        shot('RX-15-2-minus-one-tablet')
        completeFilled(patient)
        cy.wait('@disp', { timeout: 30000 }).then((i) => {
          expect(dispenseBody(i).items, 'recorded in tablets').to.deep.eq([{ productId, quantity: 14 }])
        })
        readRx(rx.id).then((after) => {
          expect(after.items[0].dispensedQuantity).to.eq(14)
          expect(after.status).to.eq('PARTIALLY_DISPENSED')
        })
      })
    })
  })

  // ── RX-16 ────────────────────────────────────────────────────────────────────────────────────────
  /**
   * Park keeps the prescription with the basket; the till is then a plain till again. Resume brings the dispense
   * back. Before RX-FILL-0 the link stayed on the till after Park — the NEXT customer's sale was charged to it —
   * and the resumed basket came back as an ordinary sale.
   */
  it('RX-16 Park takes the dispense with it; Resume brings it back and completes it', () => {
    const stamp = uniq(), name = `RxPark_${stamp}`, patient = `Park_${stamp}`
    cy.seedProduct({ name, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 6 }]).then((rx) => {
        cy.intercept('POST', '**/dispensePrescription').as('disp')
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        waitFilled()
        cy.get('#btnModeManual').click()
        cy.get('#sellCN').clear().type(patient)          // the parked basket is listed by this name
        cy.get('#parkSaleBtn').click()
        cy.get('#saleSuccess').should('be.visible')
        cy.get('#dispenseBanner').should('not.be.visible')
        cy.window().its('dispensingPrescriptionId').should('be.null')
        cy.window().its('data').should('have.length', 0)
        shot('RX-16-1-parked-till-is-plain')

        cy.window().then((w) => w.showParked())
        cy.contains('#tableParked tr', patient, { timeout: 15000 }).contains('button', 'Resume').click()
        cy.get('#dispenseBanner').should('be.visible')
        cy.window().its('dispensingPrescriptionId').should('eq', rx.id)
        cartLine(productId).then((l) => expect(piecesOf(l)).to.eq(6))
        cy.get('#dispenseFillNote').should('contain.text', '6 left on the script · 6 in this sale')
        shot('RX-16-2-resumed-as-dispense')
        completeFilled(patient)
        cy.wait('@disp', { timeout: 30000 }).then((i) => {
          expect(dispenseBody(i).prescriptionId).to.eq(rx.id)
          expect(dispenseBody(i).items).to.deep.eq([{ productId, quantity: 6 }])
        })
        readRx(rx.id).its('status').should('eq', 'FULLY_DISPENSED')
      })
    })
  })

  // ── RX-17 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-17 Clear Cart during a dispense drops the lines, the link and the banner', () => {
    const stamp = uniq(), name = `RxClear_${stamp}`, patient = `Clear_${stamp}`
    cy.seedProduct({ name, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
      seedScript(patient, [{ productId, medicineName: name, quantity: 3 }]).then((rx) => {
        openPrescriptionsTab()
        rowOf(patient).contains('button', 'Dispense').click()
        waitFilled()
        cy.get('#resetSellItem').click()
        cy.window().its('data').should('have.length', 0)
        cy.window().its('dispensingPrescriptionId').should('be.null')
        cy.get('#dispenseBanner').should('not.be.visible')
        shot('RX-17-1-cleared')
        readRx(rx.id).its('status').should('eq', 'PENDING')
      })
    })
  })

  // ── RX-18 ────────────────────────────────────────────────────────────────────────────────────────
  it('RX-18 a cart that already holds goods is never mixed into a dispense: Replace or Cancel', () => {
    const stamp = uniq(), other = `RxOther_${stamp}`, name = `RxRepl_${stamp}`, patient = `Repl_${stamp}`
    cy.seedProduct({ name: other, unit: 'tablet', stock: 30, sellingPrice: 15 }).then((po) => {
      cy.seedProduct({ name, unit: 'tablet', stock: 30, sellingPrice: 20 }).then(({ productId }) => {
        seedScript(patient, [{ productId, medicineName: name, quantity: 2 }]).then((rx) => {
          // A basket for someone else is on the till.
          cy.visit('/businessDashboard')
          cy.get('#sellType').select('sellDiv', { force: true })
          cy.waitForAppReady()
          cy.get(`#sellItemDD option[value="${po.productId}"]`, { timeout: 20000 }).should('exist')
          cy.get('#sellItemDD').select(String(po.productId), { force: true })
          cy.get('#sellQuantity').clear().type('1')
          cy.get('#addInviceItem').click()
          cy.window().its('data').should('have.length', 1)

          toPrescriptionsNoReload()
          rowOf(patient).contains('button', 'Dispense').click()
          cy.get('.uiC-title', { timeout: 10000 }).should('contain.text', 'Replace the cart?')
          shot('RX-18-1-replace-prompt')
          cy.get('.uiC-cancel').click()
          cy.window().its('dispensingPrescriptionId').should('not.eq', rx.id)
          cy.window().its('data').should('have.length', 1)
          cartLine(po.productId)

          rowOf(patient).contains('button', 'Dispense').click()
          cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).should('contain.text', 'Replace').click()
          waitFilled()
          cy.window().its('data').should('have.length', 1)
          cartLine(productId).then((l) => expect(piecesOf(l)).to.eq(2))
          shot('RX-18-2-replaced')
          cy.get('#dispenseBanner').contains('a', /cancel/i).click()
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
      // EXPIRED is DERIVED from validUntil — RX-13's script is still stored PENDING, so it is cancelled too.
      if (s === 'PENDING' || s === 'PARTIALLY_DISPENSED' || s === 'EXPIRED') post('/cancelPrescription', { prescriptionId: id })
    })
  })
  created.flagged.forEach(({ productId, name }) =>
    post('/saveClinical', { productId, medicineName: name, rxRequired: false, controlledSubstance: false }))
}
