/**
 * Sale discount → bill. A line discount must reduce the invoice's TAXABLE BASE (subTotal), so the discount is
 * never carried as due. Assertions use subTotal (the discounted NET) which is TAX-AGNOSTIC — the demo org has a
 * default tax, so the tax-inclusive due varies, but subTotal must equal the discounted base regardless.
 * (Original bug: the saga taxed the pre-discount total, so subTotal kept the full line amount and the discount
 * showed up as due.) Run headed.
 */
describe('Sale with discount — discount reduces the taxable base (subTotal), never shown as due', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const receiptOf = (invoiceNo) =>
    cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((rc) => rc.body.object || rc.body.data)

  it('amount discount: 120 line − 10 → subTotal 110; grandTotal = net + tax', () => {
    cy.seedProduct({ name: 'DP_' + Date.now(), sellingPrice: 120, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'DISC_' + Date.now(), contact: '0300DISC', paidAmount: 110, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 120, totalAmount: 120, netAmount: 110, stock: { bsellDiscount: 10, bsellDiscountType: '0' } }],
          tenders: [{ method: 'CASH', amount: 110 }], paidAmount: 110, dueAmount: 0, grandTotal: 110,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        receiptOf(r.body.object).then((inv) => {
          expect(Number(inv.subTotal), 'discount reduced the taxable base 120→110').to.eq(110)
          expect(Number(inv.grandTotal), 'grandTotal = net + tax').to.eq(Number(inv.subTotal) + Number(inv.taxTotal || 0))
        })
      })
    })
  })

  it('percent discount: 200 line − 10% (20) → subTotal 180', () => {
    cy.seedProduct({ name: 'DPP_' + Date.now(), sellingPrice: 200, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'DISCP_' + Date.now(), contact: '0300DP', paidAmount: 180, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 200, totalAmount: 200, netAmount: 180, stock: { bsellDiscount: 10, bsellDiscountType: '1' } }],
          tenders: [{ method: 'CASH', amount: 180 }], paidAmount: 180, dueAmount: 0, grandTotal: 180,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        receiptOf(r.body.object).then((inv) => {
          expect(Number(inv.subTotal), 'percent discount reduced the taxable base 200→180').to.eq(180)
        })
      })
    })
  })

  it('EDIT is authoritative: subTotal recomputed to the discounted base, prior payment preserved', () => {
    const custName = 'EDT_' + Date.now()
    cy.seedProduct({ name: 'ED_' + Date.now(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      // sale: 1 @ 100, discount 10 → net 90, pay 90
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: custName, contact: '0300EDT', paidAmount: 90, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 90, stock: { bsellDiscount: 10, bsellDiscountType: '0' } }],
          tenders: [{ method: 'CASH', amount: 90 }], paidAmount: 90, dueAmount: 0, grandTotal: 90,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const invoiceNo = r.body.object
        receiptOf(invoiceNo).then((inv0) => expect(Number(inv0.subTotal), 'sale net 90').to.eq(90))

        cy.request('/getUserSell').then((gs) => {
          const line = (gs.body.collection || gs.body.data || []).find((s) => s.productId === productId)
          expect(line, 'sell line').to.exist
          cy.request('/getSellInvoice?sellId=' + line.sellId).then((gi) => {
            const chId = gi.body.object.customer_history_id
            // EDIT: qty 1→2 @ 100, flat discount 10 → net = 200 − 10 = 190; NO new payment.
            cy.request({
              method: 'POST', url: '/updateSell', headers: { 'Content-Type': 'application/json' },
              body: {
                customer_history_id: chId,
                customer: { name: custName, contact: '0300EDT' },
                sales: [{ productId, quantity: 2, sellRate: 100, totalAmount: 200, netAmount: 190, stock: { bsellDiscount: 10, bsellDiscountType: '0' } }],
              }, failOnStatusCode: false,
            }).then((u) => expect(u.body.status, JSON.stringify(u.body)).to.eq('SUCCESS'))

            receiptOf(invoiceNo).then((inv) => {
              expect(Number(inv.subTotal), 'edit recomputed net to discounted 190 (SF-1/SF-2)').to.eq(190)
              expect(Number(inv.paidAmount), 'prior payment preserved on edit').to.eq(90)
              expect(Number(inv.grandTotal), 'grandTotal = net + tax').to.eq(Number(inv.subTotal) + Number(inv.taxTotal || 0))
            })
          })
        })
      })
    })
  })
})
