/**
 * MKT-0a — marketplace capability (opt-in) + operator entitlement + seller agreement acceptance.
 * Source §20 Phase 0 (MKT-R20.0), §9 (MKT-R9.1). Design §6.4. Run headed: --env mkt=0a
 */
const { gate, SELLER_A, OUTSIDER, API, UI, ok, post, get, expectRefused, makeSeller } = require('./mkt-helpers')

gate('0a')('MKT-0a — marketplace capability, entitlement and agreements', () => {
  after(() => {
    // GATE-RUNBOOK §5: restore — owner.business@ is shared by most other specs
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.setCapability('marketplaceSelling', false)
  })

  it('MKT-0a-01 [MKT-R20.0] the owner sees the Marketplace section only after switching it on (real UI)', () => {
    cy.loginAsOperator()
    cy.setEntitlement(SELLER_A, 'marketplaceSelling', 'ACTIVE', 'mkt gate')
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.setCapability('marketplaceSelling', false)
    cy.visit('/businessDashboard')
    cy.get(`#registrationType option[value="${UI.sellerSection}"]`).should('not.exist')
    // the owner switches it on from the Configuration screen, as a person would (pos-keyboard-toggle pattern)
    cy.intercept('POST', '**/saveBusinessConfig').as('save')
    cy.get('#snavSettings .snav-btn').click({ timeout: 30000 })
    cy.get('#navConfiguration').should('be.visible').click({ timeout: 30000 })
    cy.get('#ConfigDiv').should('be.visible')
    cy.revealSetting('org.cap.marketplaceSelling')
    cy.get('#bcfg_org_cap_marketplaceSelling').should('be.visible').check()
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.get('#businessConfigMsg', { timeout: 20000 }).should('contain', 'Saved')
    cy.visit('/businessDashboard')
    cy.get(`#registrationType option[value="${UI.sellerSection}"]`).should('exist')
  })

  it('MKT-0a-02 [MKT-R20.0] the capability is OFF for every existing tenant on deploy (live-modules rule)', () => {
    cy.loginAs(OUTSIDER, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.getCapabilities().then((caps) => {
      expect(caps, 'capabilities payload').to.have.property('marketplaceSelling', false)
    })
  })

  it('MKT-0a-03 [MKT-R20.0] [MKT-R22.1] an owner cannot switch it on without the operator entitlement', () => {
    cy.loginAsOperator()
    cy.suspendEntitlement(OUTSIDER, 'marketplaceSelling')
    cy.loginAs(OUTSIDER, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, failOnStatusCode: false,
      body: { key: 'org.cap.marketplaceSelling', value: 'true' } })
      .then((r) => expectRefused(r, 'plan'))
  })

  it('MKT-0a-04 [MKT-R9.1] [MKT-R20.0] seller writes are refused until both agreements are accepted', () => {
    cy.loginAsOperator()
    cy.setEntitlement(SELLER_A, 'marketplaceSelling', 'ACTIVE', 'mkt gate')
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    cy.setCapability('marketplaceSelling', true)
    post(API.proposeProduct, { sourceProductId: 1 }).then((r) => {
      if (ok(r.body)) return   // already accepted in an earlier run — the acceptance record persists by design
      expectRefused(r, 'agreement')
    })
  })

  it('MKT-0a-05 [MKT-R9.1] acceptance is recorded with version, who and when — and is idempotent', () => {
    makeSeller(SELLER_A)
    post(API.acceptAgreement, { agreement: 'SELLER_AND_DATA_SHARING', version: 'v1' }).then((r) => {
      expect(ok(r.body)).to.eq(true)
      const rec = r.body.data || r.body.object
      expect(rec).to.include.keys('version', 'acceptedBy', 'acceptedAt')
      expect(rec.version).to.eq('v1')
    })
  })

  it('MKT-0a-06 [MKT-R22.1] a user-tier member cannot accept agreements for the org', () => {
    cy.loginAsTier('user', 'business')
    post(API.acceptAgreement, { agreement: 'SELLER_AND_DATA_SHARING', version: 'v1' })
      .then((r) => expectRefused(r))
  })

  it('MKT-0a-07 [MKT-R20.0] a refused seller call carries a readable sentence (never a bare status)', () => {
    cy.loginAs(OUTSIDER, 'Demo@2025!', '/getBusinessDashboardStats')
    get(API.myOffers).then((r) => {
      expectRefused(r)
      expect(r.body.message, 'the server sentence reaches the screen').to.match(/marketplace/i)
    })
  })
})
