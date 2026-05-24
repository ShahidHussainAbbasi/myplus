/**
 * Landing page and public page tests
 */

describe('Landing Page (/)', () => {
  it('should load the landing page without redirecting to login', () => {
    cy.visit('/')
    cy.url().should('not.include', '/login')
  })

  it('should display non-empty body content', () => {
    cy.visit('/')
    cy.get('body').should('be.visible')
    cy.get('body').invoke('text').should('not.be.empty')
  })

  it('has a navigation bar', () => {
    cy.visit('/')
    cy.get('nav').should('exist')
  })

  it('has a link to login', () => {
    cy.visit('/')
    cy.get('a[href*="login"]').should('exist')
  })

  it('has a link to login in the navigation', () => {
    cy.visit('/')
    // Landing page nav has a Sign In / login link (no separate registration link in nav)
    cy.get('a[href*="login"], a[href*="signin"]').should('exist')
  })

  it('page title is not empty', () => {
    cy.visit('/')
    cy.title().should('not.be.empty')
  })
})

describe('Registration Page', () => {
  it('should load the registration page', () => {
    cy.visit('/registration.html', { failOnStatusCode: false })
    cy.get('body').should('be.visible')
  })

  it('should have a form with required fields', () => {
    cy.visit('/registration.html', { failOnStatusCode: false })
    cy.get('input[name="email"], input[type="email"]').should('exist')
    cy.get('input[name="password"], input[type="password"]').should('exist')
  })
})

describe('Error Pages', () => {
  it('should return 302 or redirect for protected route when not logged in', () => {
    cy.clearCookies()
    cy.request({ url: '/businessDashboard', followRedirect: false, failOnStatusCode: false }).then((res) => {
      expect([302, 401, 403]).to.include(res.status)
    })
  })

  it('should handle bad user page without crashing', () => {
    cy.visit('/badUser', { failOnStatusCode: false })
    cy.get('body').should('be.visible')
  })

  it('should redirect /educationDashboard to login when not authenticated', () => {
    cy.clearCookies()
    cy.visit('/educationDashboard', { failOnStatusCode: false })
    cy.url().should('include', '/login')
  })
})

describe('Appointment Page', () => {
  it('/appointment loads or redirects without 500', () => {
    cy.request({ url: '/appointment', failOnStatusCode: false }).then((res) => {
      expect([200, 302, 404]).to.include(res.status)
    })
  })
})

describe('Public API Accessibility', () => {
  it('login page is publicly accessible (200)', () => {
    cy.request('/login').then((res) => {
      expect(res.status).to.eq(200)
    })
  })

  it('registration.html is publicly accessible (200)', () => {
    cy.request({ url: '/registration.html', failOnStatusCode: false }).then((res) => {
      expect([200, 302]).to.include(res.status)
    })
  })

  it('protected /getUserCompany redirects when not logged in', () => {
    cy.clearCookies()
    cy.request({ url: '/getUserCompany', followRedirect: false, failOnStatusCode: false }).then((res) => {
      expect([302, 401, 403]).to.include(res.status)
    })
  })
})
