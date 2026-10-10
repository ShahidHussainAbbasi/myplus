/**
 * TP-5 — editing a bill keeps what only the server writes. The bill's "as issued" total, stamped by its first return,
 * survives an edit: the supplier statement still shows the bill as it was issued, not net of the return.
 *
 * Before: updatePurchase rebuilt the bill from the form and the mapper ignores issuedTotal, so an edit saved it NULL;
 * the statement and the payables feed then fell back to the REDUCED bill. Doc selling-price-per-purchase-analysis.md
 * §12.9. The other half — an OPENING bill refused — is covered by PurchaseEditBatchTest (an opening balance locks the
 * tenant's cutover, which a gate must not leave behind, and the grid never offers one to edit).
 *
 * E2 — after a return, an edit that changes the batch's EXPIRY must not re-cost the batch: it paid 1000 for 10, the bill
 *      now says 8. The first TP-3 cut sent the bill's rate × quantity (800) and inventory divided it by 10 received
 *      = 80 a unit for goods that cost 100. Inventory now re-derives the cost from the batch's own billed units.
 *      Tenant owner.pharma@ (tracks expiry, so /productStock lists the batch with its paidTotal).
 *
 * Note: E1 was deployed BEFORE it was written, so it was not seen red; the unit tests carry the before/after.
 * E2 is red on the TP-3 deploy (2026-10-10 10:33) and green once inventory + business carry TP-5.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/purchase-edit-keeps-issued.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const num = (v) => Number(v || 0)
const list = (body) => {
  for (const key of ['collection', 'data', 'object']) if (Array.isArray(body && body[key])) return body[key]
  return Array.isArray(body) ? body : []
}
const billLine = (venderId, invNo) => cy.request('/vendorStatement?venderId=' + venderId).then((sr) => {
  expect(sr.body.status, JSON.stringify(sr.body).slice(0, 200)).to.eq('SUCCESS')
  const line = list(sr.body).find((l) => l.docNo === invNo && l.type === 'BILL')
  expect(line, `a BILL line for ${invNo}`).to.exist
  return cy.wrap(num(line.debit))
})

describe('TP-5 — a bill edit keeps the bill as issued', () => {
  beforeEach(() => cy.loginAsOwner())

  it('E1 purchase 10 → return 2 → edit the bill: the statement still shows the bill as issued', () => {
    const stamp = uniq(), invNo = 'TP5-' + stamp
    cy.request({ method: 'POST', url: '/addCompany', form: true, failOnStatusCode: false,
      body: { name: 'TP5Co_' + stamp, email: `tp5co${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = list(cr.body).find((x) => x.name === 'TP5Co_' + stamp)
      const vName = 'TP5Ven_' + stamp
      cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
        body: { name: vName, companyId: company.id, mobile: '0300' + String(stamp).slice(-7), email: `tp5v${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        const vendor = list(lr.body).find((x) => x.name === vName)
        expect(vendor, 'vendor created').to.exist
        cy.seedProduct({ name: 'TP5P_' + stamp, sellingPrice: 120 }).then(({ productId }) => {
          cy.request({ method: 'POST', url: '/addPurchase', form: true,
            body: { productId, quantity: 10, venderId: vendor.id, paidAmount: 0,
                    'stock.bpurchaseRate': 100, 'stock.bsellRate': 120,
                    totalAmount: 1000, netAmount: 1000, purchaseInvoiceNo: invNo } })
            .its('body.status').should('eq', 'SUCCESS')

          cy.request('/getUserPurchase').then((r) => {
            const p = list(r.body).find((x) => x.purchaseInvoiceNo === invNo)
            cy.request({ method: 'POST', url: '/purchaseReturn', form: true,
              body: { purchaseId: p.purchaseId, quantity: 2, reason: 'short-shipped' } })
              .its('body.status').should('eq', 'SUCCESS')
          })

          billLine(vendor.id, invNo).then((issued) => {
            cy.request('/getUserPurchase').then((r) => {
              const p = list(r.body).find((x) => x.purchaseInvoiceNo === invNo)
              expect(issued, 'the statement shows the bill as ISSUED, more than the bill after the return')
                .to.be.greaterThan(num(p.totalAmount))

              // The edit: what the purchase form posts for this bill as it stands now, a note changed.
              cy.request({ method: 'POST', url: '/updatePurchase', form: true,
                body: { purchaseId: p.purchaseId, productId, quantity: p.quantity, venderId: vendor.id,
                        paidAmount: p.paidAmount, 'stock.bpurchaseRate': p.stock.bpurchaseRate,
                        'stock.bsellRate': p.stock.bsellRate, totalAmount: p.totalAmount, netAmount: p.netAmount,
                        purchaseInvoiceNo: invNo, description: 'TP-5 edit' } })
                .then((u) => expect(u.body.status, JSON.stringify(u.body).slice(0, 300)).to.eq('SUCCESS'))

              billLine(vendor.id, invNo).then((after) =>
                expect(after, 'after the edit: still the bill as issued, not net of the return').to.be.closeTo(issued, 0.01))
            })
          })
        })
      })
    })
  })

  it('E2 purchase 10 @100 → return 2 → edit the expiry: the batch still paid 1000 (100 a unit), never 800', () => {
    cy.loginAsPharmaOwner()
    const stamp = uniq(), invNo = 'TP5B-' + stamp, batchNo = 'TP5B-' + stamp
    cy.ensureCompany().then((companyId) => {
      const vName = 'TP5BVen_' + stamp
      cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
        body: { name: vName, companyId, mobile: '0301' + String(stamp).slice(-7), email: `tp5b${stamp}@t.com` } })
      cy.request('/getUserVenders').then((vr) => {
        const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
        const venderId = (new RegExp('<option value=(\\d+)[^>]*>' + vName).exec(html) || [])[1]
        expect(venderId, 'vendor').to.exist
        cy.seedProduct({ name: 'TP5BP_' + stamp, sellingPrice: 150 }).then(({ productId }) => {
          cy.request({ method: 'POST', url: '/addPurchase', form: true,
            body: { productId, quantity: 10, venderId, purchaseRate: 100, 'stock.bpurchaseRate': 100, 'stock.bsellRate': 150,
                    'stock.batchNo': batchNo, 'stock.bexpDate': '2027-12-31', totalAmount: 1000, netAmount: 500, purchaseInvoiceNo: invNo } })
            .its('body.status').should('eq', 'SUCCESS')
          const batch = () => cy.request('/productStock?productId=' + productId).then((r) => {
            const b = (r.body.batches || []).find((x) => x.batchNo === batchNo)
            expect(b, 'the batch is listed: ' + JSON.stringify(r.body.batches)).to.exist
            return cy.wrap(b)
          })
          batch().then((b) => {
            expect(String(b.expiryDate)).to.eq('2027-12-31')
            expect(num(b.paidTotal), 'paid for 10 at 100').to.eq(1000)
          })
          cy.request('/getUserPurchase').then((r) => {
            const p = list(r.body).find((x) => x.purchaseInvoiceNo === invNo)
            cy.request({ method: 'POST', url: '/purchaseReturn', form: true,
              body: { purchaseId: p.purchaseId, quantity: 2, reason: 'TP-5 gate' } }).its('body.status').should('eq', 'SUCCESS')
          })
          cy.request('/getUserPurchase').then((r) => {
            const p = list(r.body).find((x) => x.purchaseInvoiceNo === invNo)
            expect(num(p.quantity), 'the bill after the return').to.eq(8)
            cy.request({ method: 'POST', url: '/updatePurchase', form: true,
              body: { purchaseId: p.purchaseId, productId, quantity: p.quantity, venderId, paidAmount: p.paidAmount,
                      'stock.bpurchaseRate': 100, 'stock.bsellRate': 150, 'stock.batchNo': batchNo,
                      'stock.bexpDate': '2028-06-30', totalAmount: p.totalAmount, netAmount: p.netAmount, purchaseInvoiceNo: invNo } })
              .then((u) => expect(u.body.status, JSON.stringify(u.body).slice(0, 300)).to.eq('SUCCESS'))
          })
          batch().then((b) => {
            expect(String(b.expiryDate), 'the corrected expiry reached the batch').to.eq('2028-06-30')
            expect(num(b.paidTotal), 'still what the batch paid for its 10 units — not 100 × 8').to.eq(1000)
          })
          // leave no stock behind on the pharmacy: void the bill (reverses the remaining 8)
          cy.request('/getUserPurchase').then((r) => {
            const p = list(r.body).find((x) => x.purchaseInvoiceNo === invNo)
            cy.request({ method: 'POST', url: '/voidPurchase', form: true, body: { purchaseId: p.purchaseId, reason: 'TP-5 gate' } })
              .then((v) => expect(v.body.status, 'cleanup void: ' + JSON.stringify(v.body).slice(0, 200)).to.eq('SUCCESS'))
          })
        })
      })
    })
  })
})
