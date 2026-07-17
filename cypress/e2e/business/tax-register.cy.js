/**
 * Tax-filing register (Phase A — output tax). With tax enabled, a taxed sale adds output tax to the register (from the
 * GL TAX account); voiding it records an adjustment that nets the output back down. Requires finance-service (GL) +
 * business + gateway up. Run headed.
 */
describe('Tax register — output tax', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const today = new Date().toISOString().slice(0, 10)
  const reg = () => cy.request(`/taxRegister?from=${today}&to=${today}`).then((r) => parse(r.body))

  it('a taxed sale adds output tax; voiding it records an adjustment', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    // Enable tax (output) at 10% so sales compute tax — the configurable per-org policy.
    cy.request({ method: 'POST', url: '/saveTaxSetting', form: true, body: { enabled: true, defaultRate: 10, taxMode: 'EXCLUSIVE' }, failOnStatusCode: false })
      .then((s) => expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS'))

    reg().then((before) => {
      const outBefore = Number(before.outputTax || 0)
      const adjBefore = Number(before.outputAdjusted || 0)

      cy.seedProduct({ name: 'TAXP_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          body: {
            customer: { name: 'TAXC_' + Date.now(), contact: '0300TAX', paidAmount: 0, dueAmount: 0 },
            sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
            paidAmount: 0, dueAmount: 0, grandTotal: 100,
          }, failOnStatusCode: false,
        }).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          const invoiceNo = r.body.object

          // Output tax rose by the sale's tax (10% of 100 = 10), and a SALE credit line exists for the invoice.
          reg().then((afterSale) => {
            expect(Number(afterSale.outputTax), 'output tax increased').to.be.greaterThan(outBefore)
            expect(Number(afterSale.netPayable), 'net payable moved up').to.be.greaterThan(Number(before.netPayable || 0) - 0.001)
            const line = (afterSale.lines || []).find((l) => l.ref === invoiceNo && l.source === 'SALE')
            expect(line, 'SALE line for the invoice').to.exist
            expect(Number(line.credit), 'tax credited (~10)').to.be.greaterThan(0)
          })

          // Void → an adjustment (Dr TAX) appears and the net output drops back.
          cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'CY tax' }, failOnStatusCode: false })
            .then((v) => expect(v.body.status, JSON.stringify(v.body)).to.eq('SUCCESS'))
          reg().then((afterVoid) => {
            expect(Number(afterVoid.outputAdjusted), 'adjustment recorded').to.be.greaterThan(adjBefore)
            const adj = (afterVoid.lines || []).find((l) => l.ref === invoiceNo && l.source === 'SALE_RETURN')
            expect(adj, 'SALE_RETURN adjustment line for the invoice').to.exist
            expect(Number(adj.debit), 'tax debited back').to.be.greaterThan(0)
          })
        })
      })
    })
  })

  it('with Purchase tax enabled, a taxed purchase adds input tax and lowers net payable (Phase B)', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    // Enable BOTH toggles (independent): Sales tax + Purchase tax, 10%.
    cy.request({ method: 'POST', url: '/saveTaxSetting', form: true, body: { enabled: true, inputTaxEnabled: true, defaultRate: 10, taxMode: 'EXCLUSIVE' }, failOnStatusCode: false })
      .then((s) => expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS'))

    reg().then((before) => {
      const inBefore = Number(before.inputTax || 0)
      const payBefore = Number(before.netPayable || 0)
      const stamp = Date.now()
      cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'TXCo_' + stamp, email: `txco${stamp}@t.com` } })
      cy.request('/getUserCompany').then((cr) => {
        const companyId = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'TXCo_' + stamp).id
        cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: 'TXVEN_' + stamp, companyId, mobile: '03001234567', email: `txv${stamp}@t.com` } })
        cy.request('/getUserVender').then((lr) => {
          const venderId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'TXVEN_' + stamp).id
          cy.seedProduct({ name: 'TXP_' + stamp, sellingPrice: 100, stock: 0 }).then(({ productId }) => {
            const inv = 'TXPINV-' + stamp
            cy.request({
              method: 'POST', url: '/addPurchase', form: true,
              body: { productId, quantity: 10, venderId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: inv, taxRate: 10 },
              failOnStatusCode: false,
            }).then((pr) => expect(pr.body.status, JSON.stringify(pr.body)).to.eq('SUCCESS'))

            // Input tax rose by the purchase's tax (10% of 100 = 10); net payable fell by ~that (input is a credit).
            reg().then((after) => {
              expect(Number(after.inputTax) - inBefore, 'input tax posted (~10)').to.be.greaterThan(0)
              expect(Number(after.netPayable), 'net payable dropped by the input credit').to.be.lessThan(payBefore + 0.001)
              const line = (after.lines || []).find((l) => l.ref === inv && l.source === 'PURCHASE')
              expect(line, 'PURCHASE input-tax line').to.exist
              expect(Number(line.debit), 'input tax debited (~10)').to.be.greaterThan(0)
              const inputAfterBuy = Number(after.inputTax)
              const netInputAfterBuy = Number(after.netInput)

              // Void the taxed bill → the input tax is reversed (PURCHASE_RETURN credits TAX; net input drops back).
              cy.request('/getUserPurchase').then((pl) => {
                const p = (pl.body.collection || pl.body.data || []).find((x) => x.purchaseInvoiceNo === inv)
                expect(p, 'purchase row').to.exist
                cy.request({ method: 'POST', url: '/voidPurchase', form: true, body: { purchaseId: p.purchaseId, reason: 'CY tax void' }, failOnStatusCode: false })
                  .then((v) => expect(v.body.status, JSON.stringify(v.body)).to.eq('SUCCESS'))
                reg().then((afterVoid) => {
                  expect(Number(afterVoid.inputAdjusted), 'input tax reversed on void').to.be.greaterThan(0)
                  expect(Number(afterVoid.netInput), 'net input dropped after void').to.be.lessThan(netInputAfterBuy)
                  const rev = (afterVoid.lines || []).find((l) => l.ref === inv && l.source === 'PURCHASE_RETURN')
                  expect(rev, 'PURCHASE_RETURN reversal line').to.exist
                  expect(Number(rev.credit), 'input tax credited back (~10)').to.be.greaterThan(0)
                })
              })
            })
          })
        })
      })
    })
  })

  it('editing a taxed purchase updates the input tax (Phase B edit)', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/saveTaxSetting', form: true, body: { enabled: true, inputTaxEnabled: true, defaultRate: 10, taxMode: 'EXCLUSIVE' }, failOnStatusCode: false })
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'TXECo_' + stamp, email: `txeco${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const companyId = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'TXECo_' + stamp).id
      cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: 'TXEVEN_' + stamp, companyId, mobile: '03005556666', email: `txev${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        const venderId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'TXEVEN_' + stamp).id
        cy.seedProduct({ name: 'TXEP_' + stamp, sellingPrice: 100, stock: 0 }).then(({ productId }) => {
          const inv = 'TXEINV-' + stamp
          cy.request({
            method: 'POST', url: '/addPurchase', form: true,
            body: { productId, quantity: 10, venderId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: inv, taxRate: 10 },
            failOnStatusCode: false,
          }).then((pr) => expect(pr.body.status, JSON.stringify(pr.body)).to.eq('SUCCESS'))

          reg().then((afterBuy) => {
            const netInputBuy = Number(afterBuy.netInput)   // input tax for a 100 bill @10% = 10
            cy.request('/getUserPurchase').then((pl) => {
              const p = (pl.body.collection || pl.body.data || []).find((x) => x.purchaseInvoiceNo === inv)
              expect(p, 'purchase row').to.exist
              // Edit: double the goods to 200 → input tax should become 20 (reverse-old 10 + repost-new 20).
              cy.request({
                method: 'POST', url: '/updatePurchase', form: true,
                body: { purchaseId: p.purchaseId, productId, quantity: 20, venderId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12, totalAmount: 200, netAmount: 200, purchaseInvoiceNo: inv, taxRate: 10 },
                failOnStatusCode: false,
              }).then((u) => expect(u.body.status, JSON.stringify(u.body)).to.eq('SUCCESS'))
              reg().then((afterEdit) => {
                expect(Number(afterEdit.netInput), 'net input tax rose with the edit (10 → 20)').to.be.greaterThan(netInputBuy)
              })
            })
          })
        })
      })
    })
  })
})
