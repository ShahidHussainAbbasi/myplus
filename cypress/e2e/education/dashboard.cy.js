/**
 * Education owner analytics dashboard (slice 22).
 * Verifies the page renders the analytics layout, the org-scoped /getDashboardAnalytics
 * endpoint returns the expected shape, and the KPI cards + chart canvases are drawn.
 */

describe('Education — analytics dashboard', () => {
  beforeEach(() => {
    cy.loginAsEducation()
  })

  it('educationDashboard renders the analytics layout', () => {
    cy.visit('/educationDashboard')
    cy.get('#container').should('exist')
    cy.get('#DashboardDiv').should('exist')
    // The analytics panel is one of the dashboard sections (hidden until shown), so assert it's present;
    // its live rendering + endpoint are verified by the KPI/chart and /getDashboardAnalytics tests below.
    cy.contains('.an-title', 'School Analytics').should('exist')
  })

  it('legacy /getDashboardData still returns counts', () => {
    cy.request('/getDashboardData').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.eq('SUCCESS')
      expect(res.body.object).to.have.property('allStudent')
      expect(res.body.object).to.have.property('freshStudent')
    })
  })

  it('/getDashboardAnalytics returns KPIs and chart series', () => {
    cy.request('/getDashboardAnalytics').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.eq('SUCCESS')
      const o = res.body.object
      expect(o).to.have.property('kpis')
      expect(o.kpis).to.have.property('totalStudents')
      expect(o.kpis).to.have.property('collectionRate')
      expect(o.kpis).to.have.property('attendanceRate')
      // chart-ready series with labels/data
      ;['enrollTrend', 'feeTrend', 'attendanceTrend', 'studentsByClass', 'genderSplit', 'staffByDesignation'].forEach((k) => {
        expect(o, k).to.have.property(k)
        expect(o[k]).to.have.property('labels')
      })
      // 12-month trend windows
      expect(o.enrollTrend.labels).to.have.length(12)
      expect(o.feeTrend.labels).to.have.length(12)
    })
  })

  it('renders KPI cards and draws chart canvases', () => {
    cy.intercept('GET', '**/getDashboardAnalytics').as('analytics')
    cy.visit('/educationDashboard')
    cy.wait('@analytics').its('response.statusCode').should('eq', 200)

    // KPI headlines populated by educationDashboard.js
    cy.get('#anKpis .an-kpi').should('have.length.greaterThan', 4)
    cy.contains('.an-kpi-l', 'Students').should('exist')
    cy.contains('.an-kpi-l', 'Collection rate').should('exist')

    // Chart.js loaded and at least the enrollment-trend chart instance exists
    cy.window().should((win) => {
      expect(win.Chart, 'Chart.js loaded').to.exist
    })
    cy.get('#chEnrollTrend').should('exist')
  })
})
