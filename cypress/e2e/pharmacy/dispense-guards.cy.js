/**
 * Pharmacy — dispense guards (review step 2: B2 dispensability, B5 intake validation, B3 idempotency,
 * B4 capping feedback).
 *
 * Before this slice a prescription could be filled after it expired or after it was cancelled, a retried sale
 * counted the same dispense twice (and double-listed it on the controlled register), a zero-quantity line could
 * be prescribed at all, and anything the prescription could not account for was dropped in silence.
 *
 * Goes through the monolith proxies (the screens' own path), so it also proves the service's message survives
 * the proxy hop — that relay is the difference between "This prescription expired on <date>" and a bare
 * "could not save". Run headed.
 */
describe('Pharmacy — dispense guards', () => {
  beforeEach(() => { cy.loginAsPharma() })

  const rx = (productId, name, extra) => Object.assign({
    patientName: 'Guard_' + Date.now(),
    items: [{ productId: productId, medicineName: name, quantity: 10 }],
  }, extra || {})

  const post = (url, body) =>
    cy.request({ method: 'POST', url: url, body: body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

  // ── B5: intake validation ──────────────────────────────────────────────────────────────────────────
  it('a prescribed line with no quantity is refused, with the reason', () => {
    cy.seedProduct({ name: 'GuardQty_' + Date.now(), sku: 'GQ' + Date.now(), unit: 'tablet', stock: 20 }).then(({ productId }) => {
      post('/addPrescription', { patientName: 'ZeroQty_' + Date.now(), items: [{ productId: productId, medicineName: 'x', quantity: 0 }] })
        .then((r) => {
          expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
          expect(String(r.body.message || ''), 'the service reason survives the proxy').to.match(/quantity/i)
        })
    })
  })

  // ── B2: only a live prescription can be dispensed ──────────────────────────────────────────────────
  it('an expired prescription cannot be dispensed and reads as EXPIRED', () => {
    const name = 'GuardExp_' + Date.now()
    cy.seedProduct({ name: name, sku: 'GE' + Date.now(), unit: 'tablet', stock: 20 }).then(({ productId }) => {
      // lapsed yesterday
      const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10)
      const dayBefore = new Date(Date.now() - 2 * 86400000).toISOString().slice(0, 10)
      post('/addPrescription', rx(productId, name, { prescribedDate: dayBefore, validUntil: yesterday })).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        const rxId = r.body.data.id
        expect(r.body.data.status, 'expiry is derived, no nightly job').to.eq('EXPIRED')

        post('/dispensePrescription', { prescriptionId: rxId, invoiceNo: 'INV-EXP-' + Date.now(), items: [{ productId: productId, quantity: 5 }] })
          .then((d) => {
            expect(d.body.success, JSON.stringify(d.body)).to.eq(false)
            expect(String(d.body.message || '')).to.match(/expired/i)
          })
      })
    })
  })

  it('a cancelled prescription cannot be dispensed', () => {
    const name = 'GuardCan_' + Date.now()
    cy.seedProduct({ name: name, sku: 'GC' + Date.now(), unit: 'tablet', stock: 20 }).then(({ productId }) => {
      post('/addPrescription', rx(productId, name)).then((r) => {
        const rxId = r.body.data.id
        post('/cancelPrescription', { prescriptionId: rxId }).then((c) => {
          expect(c.body.success, JSON.stringify(c.body)).to.eq(true)
          expect(c.body.data.status).to.eq('CANCELLED')
        })
        post('/dispensePrescription', { prescriptionId: rxId, invoiceNo: 'INV-CAN-' + Date.now(), items: [{ productId: productId, quantity: 5 }] })
          .then((d) => {
            expect(d.body.success, JSON.stringify(d.body)).to.eq(false)
            expect(String(d.body.message || '')).to.match(/cancel/i)
          })
      })
    })
  })

  // ── B3: a retried sale must not count twice ────────────────────────────────────────────────────────
  it('re-posting the same invoice does not double-count the dispense', () => {
    const name = 'GuardDup_' + Date.now()
    const invoiceNo = 'INV-DUP-' + Date.now()
    cy.seedProduct({ name: name, sku: 'GD' + Date.now(), unit: 'tablet', stock: 40 }).then(({ productId }) => {
      post('/addPrescription', rx(productId, name)).then((r) => {
        const rxId = r.body.data.id
        const line = { prescriptionId: rxId, invoiceNo: invoiceNo, items: [{ productId: productId, quantity: 4 }] }

        post('/dispensePrescription', line).then((first) => {
          expect(first.body.data.items[0].dispensedQuantity).to.eq(4)
        })
        post('/dispensePrescription', line).then((second) => {
          expect(second.body.data.items[0].dispensedQuantity, 'still 4 — the repeat was ignored').to.eq(4)
          expect((second.body.data.warnings || []).join(' ')).to.match(/already dispensed/i)
        })
        // and the controlled/dispensing record was written once, not twice
        cy.request('/getPrescription?id=' + rxId).then((g) => {
          expect(g.body.data.items[0].dispensedQuantity).to.eq(4)
        })
      })
    })
  })

  // ── B4: capping and off-script items are reported ──────────────────────────────────────────────────
  it('selling more than prescribed records only the outstanding quantity, and says so', () => {
    const name = 'GuardCap_' + Date.now()
    cy.seedProduct({ name: name, sku: 'GK' + Date.now(), unit: 'tablet', stock: 60 }).then(({ productId }) => {
      post('/addPrescription', rx(productId, name)).then((r) => {
        const rxId = r.body.data.id
        post('/dispensePrescription', { prescriptionId: rxId, invoiceNo: 'INV-CAP-' + Date.now(), items: [{ productId: productId, quantity: 25 }] })
          .then((d) => {
            expect(d.body.success, JSON.stringify(d.body)).to.eq(true)
            expect(d.body.data.items[0].dispensedQuantity, 'capped at the prescribed 10').to.eq(10)
            expect((d.body.data.warnings || []).join(' '), 'the cap is not silent').to.match(/outstanding|recorded/i)
          })
      })
    })
  })
})
