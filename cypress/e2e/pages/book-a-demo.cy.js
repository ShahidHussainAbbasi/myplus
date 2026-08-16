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

  /** Fill and submit the lead form for a given interest. */
  const submitDemoRequest = (interest) => {
    cy.intercept('POST', '**/api/demo-request').as('demo')
    cy.get('.nav-cta').contains('Book a Demo').click()
    cy.get('#dmName').type('Jane Global')
    cy.get('#dmEmail').type('email2uncer@gmail.com')
    cy.get('#dmCompany').type('Acme Worldwide Ltd')
    cy.get('#dmCountry').select('United Kingdom')
    cy.get('#dmInterest').select(interest)
    cy.get('#dmConsent').check()
    cy.get('#dmSubmit').click()
    cy.wait('@demo', { timeout: 20000 }).then((i) => {
      expect(i.response.statusCode).to.eq(200)
      expect(i.response.body.success).to.eq(true)
    })
    // On success the modal stays OPEN and swaps in a panel chosen by showDemoReady(interest).
    cy.get('#overlay').should('have.class', 'open')
    cy.get('#toast').should('have.class', 'on')         // success toast shown
  }

  /**
   * The CONVERSION path, and the one worth guarding hardest: an interest we have a demo tenant for
   * hands the visitor a working login on the spot instead of a promise of an email.
   *
   * This case used to pick "Online Marketplace" precisely BECAUSE it had no demo account, and asserted
   * the "Request received" panel. A marketplace demo tenant has since been seeded
   * (DEMO_ACCOUNTS in maxtheservice_dashboard.html), so the branch flipped under the test and it has
   * been red since. Both branches are real product behaviour, so both are now covered — and the
   * interest each one uses is chosen from the CURRENT map, not from a comment about it.
   */
  it('an interest WITH a demo tenant hands over working credentials', () => {
    submitDemoRequest('Online Marketplace')
    cy.get('#modal').should('contain', 'Marketplace demo').and('contain', 'ready')
    // The credentials themselves — a panel that renders without them converts nobody.
    cy.get('#dcEmail').should('contain', 'demo.marketplace@myplus.com')
    cy.get('#dcPw').should('not.be.empty')
    cy.get('#modal').contains('Open the demo').should('be.visible')
  })

  it('an interest with NO demo tenant falls back to the "Request received" acknowledgement', () => {
    // "Other / Not sure" is deliberate: a visitor who cannot name their vertical is exactly who has no
    // demo tenant waiting, and it is the branch that must not silently show an empty panel.
    submitDemoRequest('Other / Not sure')
    cy.get('#modal').should('contain', 'Request received')
    cy.get('#modal').contains('Done').should('be.visible')
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
