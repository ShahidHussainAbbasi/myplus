/**
 * POS day-close (slice 39) — cashier shift + cash drawer + X/Z report.
 * Shift state is per-cashier global, so each test first clears any open shift, then drives a clean lifecycle.
 * Run headed: npx cypress run --headed --browser chrome --env slowMo=1200 --spec this.
 */
describe('POS day-close — cashier shift', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const closeIfOpen = () => cy.request({ url: '/currentShift', failOnStatusCode: false }).then((r) => {
    if (r.body && r.body.status === 'SUCCESS') {
      cy.request({ method: 'POST', url: '/closeShift', form: true, body: { countedCash: 0 }, failOnStatusCode: false })
    }
  })

  it('full lifecycle: open → cash movement → X report → close (Z) with variance', () => {
    closeIfOpen()

    cy.request({ method: 'POST', url: '/openShift', form: true, body: { openingFloat: 100 }, failOnStatusCode: false }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.status).to.eq('SUCCESS')
      expect(Number(r.body.object.openingFloat)).to.eq(100)
    })

    cy.request('/currentShift').then((r) => {
      expect(r.body.status).to.eq('SUCCESS')
      expect(r.body.object.status).to.eq('OPEN')
    })

    cy.request({ method: 'POST', url: '/cashMovement', form: true, body: { type: 'PAY_IN', amount: 25, reason: 'top-up' }, failOnStatusCode: false }).then((r) => {
      expect(r.body.status).to.eq('SUCCESS')
    })

    cy.request('/shiftReport').then((r) => {
      expect(r.body.status).to.eq('SUCCESS')
      // float 100 + pay-in 25, no cash sales → expected 125
      expect(Number(r.body.object.expectedCash)).to.eq(125)
    })

    cy.request({ method: 'POST', url: '/closeShift', form: true, body: { countedCash: 120, notes: 'test' }, failOnStatusCode: false }).then((r) => {
      expect(r.body.status).to.eq('SUCCESS')
      expect(r.body.object.status).to.eq('CLOSED')
      expect(Number(r.body.object.variance)).to.eq(-5)   // counted 120 − expected 125
    })
  })

  it('cannot open two shifts at once', () => {
    closeIfOpen()
    cy.request({ method: 'POST', url: '/openShift', form: true, body: { openingFloat: 50 }, failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/openShift', form: true, body: { openingFloat: 50 }, failOnStatusCode: false }).then((r) => {
      expect(r.body.status).to.eq('FAILED')
    })
    closeIfOpen()
  })

  it('Till panel renders and opens from the dashboard', () => {
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showTill')
    cy.window().then((w) => w.showTill())
    cy.get('#TillDiv').should('be.visible')
    // either the open-shift panel or the ops panel is shown depending on current state
    cy.get('#tillOpenPanel, #tillOpsPanel').should('exist')
  })
})
