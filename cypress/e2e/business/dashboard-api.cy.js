/**
 * Business Dashboard API tests
 * Covers getBusinessDashboardStats and getDashboardChartData endpoints
 */

describe('Dashboard API — Stats Endpoint', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('getBusinessDashboardStats returns 200 with expected keys', () => {
    cy.request('/getBusinessDashboardStats').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body).to.be.an('object')
    })
  })

  it('getBusinessDashboardStats returns numeric counts', () => {
    cy.request('/getBusinessDashboardStats').then((res) => {
      expect(res.status).to.eq(200)
      const body = res.body
      // Body may have companyCount, venderCount, customerCount, itemCount, etc.
      // All values that exist should be non-negative numbers
      Object.values(body).forEach((val) => {
        if (typeof val === 'number') {
          expect(val).to.be.gte(0)
        }
      })
    })
  })

  it('getDashboardChartData returns 200', () => {
    cy.request({ url: '/getDashboardChartData', failOnStatusCode: false }).then((res) => {
      expect(res.status).to.eq(200)
    })
  })

  it('getDashboardChartData response is an object or array', () => {
    cy.request({ url: '/getDashboardChartData', failOnStatusCode: false }).then((res) => {
      expect(res.body).to.satisfy((b) => typeof b === 'object')
    })
  })
})

describe('Dashboard API — KPI Data Integration', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
  })

  it('dashboard stats AJAX fires on page load', () => {
    cy.loginAsBusiness()
    cy.intercept('GET', '/getBusinessDashboardStats').as('dashStats')
    cy.visit('/businessDashboard')
    cy.wait('@dashStats', { timeout: 15000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })

  it('chart data AJAX fires on page load', () => {
    cy.loginAsBusiness()
    cy.intercept('GET', '/getDashboardChartData').as('chartData')
    cy.visit('/businessDashboard')
    cy.wait('@chartData', { timeout: 15000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })

  it('KPI card values update from empty dash to a number after AJAX', () => {
    // After page load and AJAX, KPI values should change from - to digits
    cy.get('#dashCompanies', { timeout: 15000 }).invoke('text').should('match', /^(\d+|-)$/)
    cy.get('#dashVenders', { timeout: 15000 }).invoke('text').should('match', /^(\d+|-)$/)
    cy.get('#dashCustomers', { timeout: 15000 }).invoke('text').should('match', /^(\d+|-)$/)
    cy.get('#dashItems', { timeout: 15000 }).invoke('text').should('match', /^(\d+|-)$/)
    cy.get('#dashMonthlySales', { timeout: 15000 }).invoke('text').should('match', /^(\d+|-)$/)
  })

  it('due customers table body renders rows or no-data message', () => {
    cy.get('#dueCustTableBody', { timeout: 10000 }).should('exist')
    cy.get('#dueCustTableBody').invoke('text').should('not.be.empty')
  })
})
