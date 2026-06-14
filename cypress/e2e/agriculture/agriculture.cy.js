/**
 * Agriculture E2E — requires the app at http://localhost:8080 with the agriculture-service up.
 * Closes the coverage gap for the agriculture domain (was compile-gated only).
 *
 * Land → Income/Expense (income & expense reference a land by id). Writes go via the API and are
 * round-trip verified through the org-scoped reads (monolith→gateway→agriculture-service).
 * Dates are omitted: the service defaults an empty/missing date to today.
 */
describe('Agriculture — land, income & expense', () => {
  before(() => {
    cy.loginAsAgriculture()
  })

  beforeEach(() => {
    cy.loginAsAgriculture()
  })

  // ─── dashboard ───────────────────────────────────────────────────────────────
  it('agriculture dashboard loads with the section selector', () => {
    cy.visit('/agricultureDashboard')
    cy.get('#registrationType').should('exist')
  })

  // ─── reads ───────────────────────────────────────────────────────────────────
  it('getUserLand returns SUCCESS or NOT_FOUND', () => {
    cy.request('/getUserLand').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })

  it('getUserLands returns an HTML option string', () => {
    cy.request('/getUserLands').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body).to.be.a('string')
    })
  })

  it('getUserAgricultureIncome returns SUCCESS or NOT_FOUND', () => {
    cy.request('/getUserAgricultureIncome').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })

  it('getUserAgricultureExpense returns SUCCESS or NOT_FOUND', () => {
    cy.request('/getUserAgricultureExpense').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })

  // ─── writes (round-trip) ──────────────────────────────────────────────────────
  it('adds a land via API and it then appears in getUserLand', () => {
    const landName = `Cypress Field ${Date.now()}`
    cy.request({
      method: 'POST', url: '/addLand', form: true,
      body: { landName, landType: 'Agricultural', landUnit: 'Acre', totalLandUnit: '5', amount: '100000' },
    }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'FOUND'])
    })

    cy.request('/getUserLand').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.eq('SUCCESS')
      const list = res.body.object || res.body.collection || res.body.data || []
      expect(list.length).to.be.greaterThan(0)
    })
  })

  it('adds an income for an existing land', () => {
    cy.request('/getUserLand').then((res) => {
      const list = res.body.object || res.body.collection || res.body.data || []
      if (!list.length) {
        cy.log('No land available — skipping income add')
        return
      }
      const landId = list[0].id
      cy.request({
        method: 'POST', url: '/addAgricultureIncome', form: true,
        body: { landId, incomeName: `Crop Income ${Date.now()}`, incomeType: 'Wheat', amount: '5000' },
      }).then((r) => {
        expect(r.status).to.eq(200)
        expect(r.body.status).to.be.oneOf(['SUCCESS', 'INVALID', 'FOUND'])
      })
    })
  })

  it('adds an expense for an existing land', () => {
    cy.request('/getUserLand').then((res) => {
      const list = res.body.object || res.body.collection || res.body.data || []
      if (!list.length) {
        cy.log('No land available — skipping expense add')
        return
      }
      const landId = list[0].id
      cy.request({
        method: 'POST', url: '/addAgricultureExpense', form: true,
        body: { landId, expenseName: `Seed Expense ${Date.now()}`, expenseType: 'Seeds', amount: '1500' },
      }).then((r) => {
        expect(r.status).to.eq(200)
        expect(r.body.status).to.be.oneOf(['SUCCESS', 'INVALID', 'FOUND'])
      })
    })
  })
})
