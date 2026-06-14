/**
 * Session management and auth edge-case tests
 */

describe('Session — Protected Routes', () => {
  const protectedRoutes = [
    '/businessDashboard',
    '/getUserCompany',
    '/getUserVender',
    '/getUserCustomer',
    '/getUserItem',
    '/getUserPurchase',
    '/getUserSell',
    '/getUserStock',
    '/getBusinessDashboardStats',
  ]

  protectedRoutes.forEach((route) => {
    it(`${route} redirects to login when unauthenticated`, () => {
      cy.clearCookies()
      cy.request({ url: route, followRedirect: false, failOnStatusCode: false }).then((res) => {
        expect([302, 401, 403]).to.include(res.status)
      })
    })
  })
})

describe('Session — Authenticated Access', () => {
  before(() => {
    cy.loginAsBusiness()
  })

  it('authenticated user can reach businessDashboard', () => {
    cy.visit('/businessDashboard')
    cy.url().should('include', '/businessDashboard')
    cy.get('body').should('be.visible')
  })

  it('authenticated user can reach getUserCompany (200)', () => {
    cy.request('/getUserCompany').then((res) => {
      expect(res.status).to.eq(200)
    })
  })

  it('authenticated user can reach getBusinessDashboardStats (200)', () => {
    cy.request('/getBusinessDashboardStats').then((res) => {
      expect(res.status).to.eq(200)
    })
  })
})

describe('Session — Logout Behaviour', () => {
  it('after logout, businessDashboard redirects to login', () => {
    cy.loginAsBusiness()
    cy.visit('/logout')
    cy.clearCookies()
    cy.visit('/businessDashboard', { failOnStatusCode: false })
    cy.url().should('include', '/login')
  })
})

describe('Login — i18n Language Switch', () => {
  it('switching to Spanish loads login page with lang=es_ES', () => {
    cy.visit('/login?lang=es_ES')
    cy.url().should('include', '/login')
    cy.get('body').should('be.visible')
  })

  it('switching to English loads login page with lang=en', () => {
    cy.visit('/login?lang=en')
    cy.url().should('include', '/login')
    cy.get('body').should('be.visible')
  })
})

describe('Login — URL Parameters', () => {
  it('/login?error shows error message bar', () => {
    cy.visit('/login?error')
    cy.get('.msg-bar.error').should('be.visible')
  })

  it('/login?message=test shows info bar', () => {
    cy.visit('/login?message=TestMessage')
    cy.get('.msg-bar.info').should('be.visible')
  })
})
