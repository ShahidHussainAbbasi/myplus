/**
 * U8 — dispensing loose against a prescription.
 *
 * Design: microservices/docs/slices/u8-loose-dispense-record.md
 *
 * ⚠ WHAT THIS GUARDS IS A CLINICAL RECORD, NOT A TILL.
 *
 * The sale was always right: fifteen tablets left the counter, stock fell 1.5 packs, the customer paid for
 * fifteen. What was wrong was what got written against the SCRIPT. A loose cart line carries
 * `quantity = 1.5` (packs) and `dispensedQuantity` is an `int`, so a script for 15 recorded **1** — leaving
 * 14 apparently owed, the prescription open, and a repeat dispense permitted.
 *
 * For a controlled substance that is a register understating what left the counter by 93%.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/pharmacy/loose-dispense-record.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const post = (url, body) =>
  cy.request({ method: 'POST', url, body, headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false })

/** A divisible product: packs of 10 at 120.00, sellable by the tablet. */
const packProduct = (name) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name, sellingPrice: 120, unit: 'pack', packSize: 10,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose: true, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored').to.exist
      return p
    })
  })

/** ⚠ A purchase RESTAMPS the selling price — pass the intended price (U2 §13.4c). */
const stockIn = (productId, qty) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: qty, 'stock.batchNo': `DX${uniq()}`,
      'stock.bpurchaseRate': 100, 'stock.bsellRate': 120,
      totalAmount: qty * 100, netAmount: qty * 100, purchaseInvoiceNo: `DSP-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).substring(0, 200)}`).to.eq('SUCCESS'))

/** A script for `pieces` TABLETS — the unit a prescription is written in. */
const prescribe = (productId, name, pieces) =>
  post('/addPrescription', {
    patientName: `Pat_${uniq()}`,
    items: [{ productId, medicineName: name, quantity: pieces }],
  }).then((r) => {
    expect(r.body.success, `prescription: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq(true)
    return r.body.data.id
  })

const readRx = (rxId) =>
  cy.request(`/getPrescription?id=${rxId}`).then((r) => {
    const rx = r.body.data || r.body.object || r.body
    expect(rx, `prescription ${rxId}`).to.exist
    return rx
  })

const itemOf = (rx, productId) => {
  const items = rx.items || []
  const it = items.find((i) => String(i.productId) === String(productId))
  expect(it, 'the prescribed line').to.exist
  return it
}

describe('U8 — what a loose dispense records against the script', () => {
  beforeEach(() => cy.loginAsOwner())

  // ── ⭐ the case the fix exists for ────────────────────────────────────────────────────────────────────

  it('⭐ dispensing 15 tablets against a 15-tablet script records 15, not 1', () => {
    const name = `Dsp_${uniq()}`

    packProduct(name).then((p) => {
      stockIn(p.id, 10)
      prescribe(p.id, name, 15).then((rxId) => {
        // What the till now sends: PIECES, because a script is written in pieces. Before the fix this was
        // `quantity: 1.5` (packs), which an int field received as 1.
        post('/dispensePrescription', {
          prescriptionId: rxId, invoiceNo: `INV-U8-${uniq()}`,
          items: [{ productId: p.id, quantity: 15 }],
        }).then((d) => {
          expect(d.body.success, `dispense: ${JSON.stringify(d.body).slice(0, 250)}`).to.eq(true)
        })

        readRx(rxId).then((rx) => {
          const it = itemOf(rx, p.id)
          expect(Number(it.dispensedQuantity), 'the whole script was filled').to.eq(15)
          expect(Number(it.quantity) - Number(it.dispensedQuantity), 'nothing still owed').to.eq(0)
        })
      })
    })
  })

  it('⭐ and the script does not stay open for a repeat dispense', () => {
    // The consequence of the defect, asserted directly: 1 recorded against 15 left the script open, so the
    // same prescription could be filled again. A filled script must have no room left.
    const name = `Repeat_${uniq()}`

    packProduct(name).then((p) => {
      stockIn(p.id, 10)
      prescribe(p.id, name, 15).then((rxId) => {
        post('/dispensePrescription', {
          prescriptionId: rxId, invoiceNo: `INV-R1-${uniq()}`,
          items: [{ productId: p.id, quantity: 15 }],
        }).then((d) => expect(d.body.success, JSON.stringify(d.body).slice(0, 200)).to.eq(true))

        // A second attempt must add nothing — refused outright, or accepted while recording zero.
        post('/dispensePrescription', {
          prescriptionId: rxId, invoiceNo: `INV-R2-${uniq()}`,
          items: [{ productId: p.id, quantity: 15 }],
        }).then((d2) => {
          cy.log('second dispense: ' + JSON.stringify(d2.body).slice(0, 200))
          readRx(rxId).then((rx) => {
            expect(Number(itemOf(rx, p.id).dispensedQuantity),
              'a filled script cannot be filled twice — 30 tablets against a script for 15').to.eq(15)
          })
        })
      })
    })
  })

  // ── partial ──────────────────────────────────────────────────────────────────────────────────────────

  it('a partial loose dispense leaves the right amount owed', () => {
    // 5 tablets against 15 leaves TEN owed. Before the fix it left fourteen, because 0.5 packs recorded 0.
    const name = `Partial_${uniq()}`

    packProduct(name).then((p) => {
      stockIn(p.id, 10)
      prescribe(p.id, name, 15).then((rxId) => {
        post('/dispensePrescription', {
          prescriptionId: rxId, invoiceNo: `INV-P-${uniq()}`,
          items: [{ productId: p.id, quantity: 5 }],
        }).then((d) => expect(d.body.success, JSON.stringify(d.body).slice(0, 200)).to.eq(true))

        readRx(rxId).then((rx) => {
          const it = itemOf(rx, p.id)
          expect(Number(it.dispensedQuantity), 'five given').to.eq(5)
          expect(Number(it.quantity) - Number(it.dispensedQuantity), 'ten still owed, not fourteen').to.eq(10)
        })
      })
    })
  })

  it('a dispense is still capped at what the script allows', () => {
    // The server's existing guard, re-asserted because U8 changed the UNIT reaching it: a bigger number in
    // the right unit must not become a way past the cap.
    const name = `Cap_${uniq()}`

    packProduct(name).then((p) => {
      stockIn(p.id, 10)
      prescribe(p.id, name, 15).then((rxId) => {
        post('/dispensePrescription', {
          prescriptionId: rxId, invoiceNo: `INV-C-${uniq()}`,
          items: [{ productId: p.id, quantity: 40 }],
        }).then((d) => cy.log('over-dispense: ' + JSON.stringify(d.body).slice(0, 200)))

        readRx(rxId).then((rx) => {
          expect(Number(itemOf(rx, p.id).dispensedQuantity), 'capped at the prescribed 15').to.eq(15)
        })
      })
    })
  })

  // ── the regressions ──────────────────────────────────────────────────────────────────────────────────

  it('an indivisible product is completely unaffected', () => {
    // Pieces and packs are the same number, so nothing about this changed — and this is most of the
    // catalogue, and every product that existed before pack sizes did.
    const name = `Plain_${uniq()}`

    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { name, sellingPrice: 50, unit: 'tablet' },
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product exists').to.exist
      stockIn(p.id, 20)
      prescribe(p.id, name, 6).then((rxId) => {
        post('/dispensePrescription', {
          prescriptionId: rxId, invoiceNo: `INV-I-${uniq()}`,
          items: [{ productId: p.id, quantity: 6 }],
        }).then((d) => expect(d.body.success, JSON.stringify(d.body).slice(0, 200)).to.eq(true))
        readRx(rxId).then((rx) => {
          expect(Number(itemOf(rx, p.id).dispensedQuantity)).to.eq(6)
        })
      })
    })
  })

  it('⚠ a PACK dispense records packs — the case still awaiting a decision', () => {
    /*
     * Two packs of ten against a 15-tablet script record 2, NOT 20.
     *
     * This predates loose selling and turns on whether this shop writes scripts in tablets or in packs.
     * The gate asserts TODAY'S behaviour so the answer is visible and any future change is deliberate — it
     * is NOT an endorsement. See u8-loose-dispense-record.md §4.
     */
    const name = `PackDsp_${uniq()}`

    packProduct(name).then((p) => {
      stockIn(p.id, 10)
      prescribe(p.id, name, 15).then((rxId) => {
        post('/dispensePrescription', {
          prescriptionId: rxId, invoiceNo: `INV-PK-${uniq()}`,
          items: [{ productId: p.id, quantity: 2 }],   // the till sends packs on a PACK line
        }).then((d) => expect(d.body.success, JSON.stringify(d.body).slice(0, 200)).to.eq(true))

        readRx(rxId).then((rx) => {
          expect(Number(itemOf(rx, p.id).dispensedQuantity),
            'records 2 today; if scripts are written in tablets this should be 20 — an open question')
            .to.eq(2)
        })
      })
    })
  })
})
