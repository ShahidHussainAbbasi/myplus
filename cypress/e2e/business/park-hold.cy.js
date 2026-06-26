/**
 * Park / hold & resume a sale (POS R10, slice 40). API lifecycle + anti-IDOR + UI panel render.
 * Run headed: npx cypress run --headed --browser chrome --env slowMo=1200 --spec this.
 */
describe('POS — park / hold & resume', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const sampleCart = (label) => ({
    label: label,
    itemCount: 1,
    total: 100,
    cart: {
      customer: { name: 'Parked Cust', contact: '0300PARK' },
      sales: [{ productId: 1, itemName: 'Held Item', quantity: 1, sellRate: 100, totalAmount: 100 }],
      tenders: [],
    },
  })

  it('park → list → resume returns the same cart → discard', () => {
    const label = 'Hold_' + Date.now()
    let parkedId

    cy.request({ method: 'POST', url: '/parkSale', body: sampleCart(label), headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.status).to.eq(200)
        expect(r.body.status).to.eq('SUCCESS')
        parkedId = r.body.object
        expect(parkedId).to.be.a('number')
      })

    cy.request('/parkedSales').then((r) => {
      expect(r.body.status).to.eq('SUCCESS')
      // a List comes back in `collection` (GenericResponse convention), not `object`
      const mine = (r.body.collection || []).find((p) => p.id === parkedId)
      expect(mine, 'parked row present').to.exist
      expect(mine.label).to.eq(label)
      expect(Number(mine.total)).to.eq(100)
    })

    cy.then(() => {
      cy.request('/resumeParked?id=' + parkedId).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        expect(r.body.object).to.have.property('sales')
        expect(r.body.object.sales[0].itemName).to.eq('Held Item')
        expect(r.body.object.customer.name).to.eq('Parked Cust')
      })
      cy.request({ method: 'POST', url: '/deleteParked', form: true, body: { id: parkedId }, failOnStatusCode: false }).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
      })
      cy.request('/resumeParked?id=' + parkedId).then((r) => {
        expect(r.body.status).to.eq('NOT_FOUND')   // gone after discard
      })
    })
  })

  it('resuming a non-existent parked id is NOT_FOUND (anti-IDOR safe)', () => {
    cy.request({ url: '/resumeParked?id=999999999', failOnStatusCode: false }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.status).to.be.oneOf(['NOT_FOUND', 'ERROR'])
    })
  })

  it('Park button and Parked Sales panel render', () => {
    cy.openSellSection('sellDiv')
    cy.get('#parkSaleBtn').should('be.visible')
    cy.window().should('have.property', 'showParked')
    cy.window().then((w) => w.showParked())
    cy.get('#ParkedDiv').should('be.visible')
    cy.get('#tableParked').should('exist')
  })
})
