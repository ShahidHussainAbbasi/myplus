/**
 * GL correctness: voiding a DISCOUNTED sale must net Accounts Receivable and Sales exactly back to where they were —
 * the void must reverse the SAME (post-discount) amounts the sale posted, not the pre-discount line totals. This
 * reproduces the reported drift (a 1500 invoice with a 50 discount, part-paid, then voided → AR left 50 short).
 * Requires finance (GL) + business + gateway up. Run headed.
 */
describe('GL — voiding a discounted sale nets AR + Sales to zero', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
  const net = (t, code) => { const r = (t.rows || []).find((x) => x.code === code); return r ? Number(r.debit || 0) - Number(r.credit || 0) : 0 }

  it('discounted sale (1500 − 50, pay 1000) then void → AR and Sales return to baseline', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    // Tax off so the numbers are clean (the discount bug is independent of tax).
    cy.request({ method: 'POST', url: '/saveTaxSetting', form: true, body: { enabled: false, defaultRate: 0, taxMode: 'EXCLUSIVE' }, failOnStatusCode: false })

    tb().then((before) => {
      const arBefore = net(before, '1100'), salesBefore = net(before, '4000')
      const stamp = Date.now()

      cy.seedProduct({ name: 'VDG_' + stamp, sellingPrice: 1500, stock: 5 }).then(({ productId }) => {
        // Net = 1500 − 50 discount = 1450; pay 1000 → due 450 → AR should rise by 450.
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          body: {
            customer: { name: 'VDGC_' + stamp, contact: '0300' + String(stamp).slice(-7), dueDate: '2027-01-01' },
            sales: [{ productId, quantity: 1, sellRate: 1500, stock: { bsellDiscount: 50, bsellDiscountType: '0' } }],
            tenders: [{ method: 'CASH', amount: 1000 }], grandTotal: 1450,
          }, failOnStatusCode: false,
        }).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          const invoiceNo = r.body.object

          tb().then((afterSale) => {
            expect(net(afterSale, '1100') - arBefore, 'AR rose by the 450 due (post-discount)').to.be.closeTo(450, 0.5)

            // Void the invoice → AR and Sales must return to exactly the baseline (net delta 0).
            cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'GL discount void' }, failOnStatusCode: false })
              .then((v) => expect(v.body.status, JSON.stringify(v.body)).to.eq('SUCCESS'))

            tb().then((afterVoid) => {
              expect(afterVoid.balanced, 'GL balanced after void').to.eq(true)
              expect(net(afterVoid, '1100') - arBefore, 'AR back to baseline (no discount drift)').to.be.closeTo(0, 0.5)
              expect(net(afterVoid, '4000') - salesBefore, 'Sales back to baseline (no discount drift)').to.be.closeTo(0, 0.5)
            })
          })
        })
      })
    })
  })
})
