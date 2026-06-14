/**
 * Landing page — Book a Demo (public lead capture). Headed-friendly.
 * Covers: modal opens from the nav CTA, client validation blocks empty submits, a valid submit
 * reaches POST /api/demo-request (200), and the endpoint is publicly reachable without auth.
 */

describe('Landing — Book a Demo', () => {
  beforeEach(() => {
    cy.viewport(1280, 800) // nav CTA is hidden on narrow viewports
    cy.visit('/')
  })

  it('nav CTA opens the demo modal with a global country list', () => {
    cy.get('.nav-cta').contains('Book a Demo').click()
    cy.get('#demoForm', { timeout: 8000 }).should('be.visible')
    cy.get('#dmName').should('exist')
    cy.get('#dmEmail').should('exist')
    // Global use: full country list, not a PK-only set.
    cy.get('#dmCountry option').its('length').should('be.greaterThan', 150)
    cy.get('#dmTz').invoke('val').should('not.be.empty') // timezone captured client-side
  })

  it('blocks an empty submit with inline errors and makes no request', () => {
    cy.intercept('POST', '**/api/demo-request').as('demo')
    cy.get('.nav-cta').contains('Book a Demo').click()
    cy.get('#demoForm').should('be.visible')
    cy.get('#dmSubmit').click()
    cy.get('#err-fullName').should('not.be.empty')
    cy.get('#err-consent').should('contain', 'consent')
    cy.get('@demo.all').should('have.length', 0)
  })

  it('submits a valid demo request', () => {
    cy.intercept('POST', '**/api/demo-request').as('demo')
    cy.get('.nav-cta').contains('Book a Demo').click()
    cy.get('#dmName').type('Jane Global')
    cy.get('#dmEmail').type('email2uncer@gmail.com')
    cy.get('#dmCompany').type('Acme Worldwide Ltd')
    cy.get('#dmCountry').select('United Kingdom')
    cy.get('#dmInterest').select('Online Marketplace')
    cy.get('#dmConsent').check()
    cy.get('#dmSubmit').click()
    cy.wait('@demo', { timeout: 20000 }).then((i) => {
      expect(i.response.statusCode).to.eq(200)
      expect(i.response.body.success).to.eq(true)
    })
    // On success the modal stays OPEN and swaps in a confirmation panel (slice-19 demo quota):
    // for an interest with no demo account ("Online Marketplace") that's the "Request received" view.
    cy.get('#overlay').should('have.class', 'open')
    cy.get('#modal').should('contain', 'Request received')
    cy.get('#toast').should('have.class', 'on')         // success toast shown
  })

  it('endpoint is publicly accessible without authentication', () => {
    cy.clearCookies()
    cy.request('POST', '/api/demo-request', {
      fullName: 'Test Lead', workEmail: 'email2uncer@gmail.com',
      company: 'CI Co', country: 'India', consent: true, source: 'cypress'
    }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.success).to.eq(true)
    })
  })
})
