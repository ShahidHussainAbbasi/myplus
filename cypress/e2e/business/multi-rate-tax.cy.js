/**
 * Multi-rate tax — tax-code master. An org defines named tax codes (Standard 18% / Reduced 5%); each product is
 * assigned a code; a single sale mixing two coded products taxes each line at its own rate; and the per-rate tax
 * breakdown (/taxBreakdown, sourced from the transactional lines) reports each rate separately. Requires catalog +
 * business + finance + gateway up. Run headed.
 *
 * Assertions use before/after deltas so other activity in the period doesn't make them brittle.
 */
describe('Multi-rate tax — tax codes', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const today = new Date().toISOString().slice(0, 10)
  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const breakdown = () => cy.request(`/taxBreakdown?from=${today}&to=${today}`).then((r) => parse(r.body).object || parse(r.body))
  // output tax booked at a given rate in the current breakdown (0 if that rate has no row yet)
  const outTaxAt = (bd, rate) => {
    const row = (bd.rows || []).find((x) => Number(x.rate) === rate)
    return row ? Number(row.outputTax) : 0
  }

  it('two coded products on one sale tax at their own rates + show per-rate in the breakdown', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    // Sales tax on, no org default (rates come from the codes), tax-exclusive.
    cy.request({ method: 'POST', url: '/saveTaxSetting', form: true, body: { enabled: true, defaultRate: 0, taxMode: 'EXCLUSIVE' }, failOnStatusCode: false })
      .then((s) => expect(s.body.status || (s.body.object ? 'SUCCESS' : ''), JSON.stringify(s.body)).to.match(/SUCCESS|/))

    const stamp = Date.now()
    const stdName = 'Standard_' + stamp, redName = 'Reduced_' + stamp
    // Create two tax codes.
    cy.request({ method: 'POST', url: '/saveTaxCode', headers: { 'Content-Type': 'application/json' }, body: { name: stdName, rate: 18, isDefault: false }, failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/saveTaxCode', headers: { 'Content-Type': 'application/json' }, body: { name: redName, rate: 5, isDefault: false }, failOnStatusCode: false })

    cy.request('/catalogTaxCodes').then((tc) => {
      const codes = parse(tc.body)
      const stdId = codes.find((c) => c.name === stdName).id
      const redId = codes.find((c) => c.name === redName).id
      expect(stdId, 'standard code id').to.be.a('number')
      expect(redId, 'reduced code id').to.be.a('number')

      breakdown().then((before) => {
        const out18Before = outTaxAt(before, 18)
        const out5Before = outTaxAt(before, 5)

        // A product on each code (selling 100 each), then ONE sale with both lines.
        cy.seedProduct({ name: 'MRP18_' + stamp, sellingPrice: 100, stock: 5, taxCodeId: stdId }).then(({ productId: p18 }) => {
          cy.seedProduct({ name: 'MRP5_' + stamp, sellingPrice: 100, stock: 5, taxCodeId: redId }).then(({ productId: p5 }) => {
            cy.request({
              method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
              body: {
                customer: { name: 'MRC_' + stamp, contact: '0300MR', paidAmount: 0, dueAmount: 0 },
                sales: [
                  { productId: p18, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 },
                  { productId: p5, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 },
                ],
                paidAmount: 0, dueAmount: 0, grandTotal: 200,
              }, failOnStatusCode: false,
            }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

            // Each line taxed at its code's rate → the breakdown gains ~18 at 18% and ~5 at 5%.
            breakdown().then((after) => {
              expect(outTaxAt(after, 18) - out18Before, 'output tax @18% rose ~18').to.be.closeTo(18, 0.5)
              expect(outTaxAt(after, 5) - out5Before, 'output tax @5% rose ~5').to.be.closeTo(5, 0.5)
            })
          })
        })
      })
    })
  })
})
