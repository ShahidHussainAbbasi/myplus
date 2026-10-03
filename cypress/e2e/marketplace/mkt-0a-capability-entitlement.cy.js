/**
 * MKT-0a — marketplace capability (opt-in) + operator entitlement + agreements + operator-approved seller account.
 * Source §9 (MKT-R9.1), §20 Phase 0/1 (MKT-R20.0, MKT-R20.1). Slice: microservices/docs/slices/mkt-0a-seller-onboarding.md
 * Run headed: npx cypress run --headed --env mkt=0a --spec cypress/e2e/marketplace/mkt-0a-capability-entitlement.cy.js
 *
 * Tenants (GATE-RUNBOOK §1): owner.business@ is the seller and holds the admin./user. ladder; owner.pharma@ is the
 * same-kind tenant that never opts in (the cross-tenant control); admin@myplus.com is MaxTheService.
 */
const { gate, SELLER_A, OUTSIDER, API, UI, ok, data, list, msg, post, get, expectRefused, openMarketplace } =
  require('./mkt-helpers')

const relogin = (email) => cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats', 'mkt-' + Date.now())
const decide = (email, decision, reason) => cy.loginAsOperator()
  .then(() => cy.orgOf(email))
  .then((org) => post(API.decideSeller, { organizationId: org.id, decision, reason }))

gate('0a')('MKT-0a — marketplace capability, agreements and seller approval', () => {
  before(() => {
    cy.loginAsOperator()
    cy.setEntitlement(SELLER_A, 'marketplaceSelling', 'ACTIVE', 'mkt gate')
  })

  after(() => {
    // GATE-RUNBOOK §5: owner.business@ is shared by most other specs — leave the module OFF as found
    relogin(SELLER_A)
    cy.setCapability('marketplaceSelling', false)
  })

  it('MKT-0a-01 [MKT-R20.0] [MKT-R9.1] [MKT-R20.1] the owner switches it on, reads and accepts the agreements, and waits for review (real UI)', () => {
    relogin(SELLER_A)
    cy.setCapability('marketplaceSelling', false)
    relogin(SELLER_A)
    cy.visit('/businessDashboard')
    cy.get(`li[data-capability="marketplaceSelling"]`).should('have.class', 'cap-off')
    // the owner switches it on from Configuration, as a person would (pos-keyboard-toggle pattern)
    cy.intercept('POST', '**/saveBusinessConfig').as('save')
    cy.get('#snavSettings .snav-btn').click({ timeout: 30000 })
    cy.get('#navConfiguration').should('be.visible').click({ timeout: 30000 })
    cy.get('#ConfigDiv').should('be.visible')
    cy.revealSetting('org.cap.marketplaceSelling')
    cy.get('#bcfg_org_cap_marketplaceSelling').should('be.visible').check()
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.get('#businessConfigMsg', { timeout: 20000 }).should('contain', 'Saved')
    relogin(SELLER_A)   // the capability rides in the token
    openMarketplace()
    cy.get('#mktStatusCapability').should('contain', 'switched on')
    cy.get('#mktAgreementBox').should('be.visible')
      .and('contain', 'Never asked for')   // source §9.3 is on the screen, not only in a document
    cy.get('#mktAcceptBtn').should('be.disabled')
    cy.get('#mktDisplayName').should('be.visible').clear().type('Shahzad Mobile Shop')
    cy.get('#mktAgreeChk').check()
    cy.get('#mktAcceptBtn').should('be.enabled').click()
    cy.get('#mktStatusAgreements', { timeout: 15000 }).should('contain', 'accepted (version v1)')
    cy.get('#mktStatusAccount').should('satisfy', ($el) =>
      /reviewing your seller account|Approved by MaxTheService/.test($el.text()))
  })

  it('MKT-0a-02 [MKT-R20.0] the capability is OFF for every tenant that never opted in (live-modules rule)', () => {
    relogin(OUTSIDER)
    cy.getCapabilities().then((caps) => expect(caps, 'capabilities payload').to.have.property('marketplaceSelling', false))
    cy.visit('/businessDashboard')
    cy.get(`li[data-capability="marketplaceSelling"]`).should('have.class', 'cap-off')
  })

  it('MKT-0a-03 [MKT-R20.0] a seller write with the module OFF is refused with a sentence, and nothing is recorded', () => {
    relogin(OUTSIDER)
    post(API.acceptAgreement, { version: 'v1', displayName: 'Should not exist' }).then((r) => expectRefused(r, 'not switched on'))
    get(API.sellerStatus).then((r) => {
      expect(ok(r.body), 'positive control: the status read answers').to.eq(true)
      expect(data(r.body).account, 'no account was created by the refused write').to.eq(null)
    })
  })

  it('MKT-0a-04 [MKT-R22.1] a user-tier member cannot accept agreements for the business', () => {
    cy.loginAsTier('user', 'business')
    post(API.acceptAgreement, { version: 'v1', displayName: 'x' }).then((r) => {
      // OFF for this token is also a valid refusal; either way nothing is accepted by a user-tier member
      expectRefused(r)
      expect(msg(r.body)).to.match(/Only the owner or an admin|not switched on/)
    })
  })

  it('MKT-0a-05 [MKT-R9.1] [MKT-R22.3] acceptance carries version, who and when — and repeating it changes nothing', () => {
    relogin(SELLER_A)
    cy.setCapability('marketplaceSelling', true)
    relogin(SELLER_A)
    post(API.acceptAgreement, { version: 'v1', displayName: 'Shahzad Mobile Shop' }).then((r1) => {
      expect(ok(r1.body), JSON.stringify(r1.body)).to.eq(true)
      expect(data(r1.body)).to.include.keys('version', 'acceptedBy', 'acceptedAt')
      expect(data(r1.body).version).to.eq('v1')
      post(API.acceptAgreement, { version: 'v1', displayName: 'Shahzad Mobile Shop' }).then((r2) =>
        expect(data(r2.body).acceptedAt, 'same acceptance, not a second one').to.eq(data(r1.body).acceptedAt))
    })
    post(API.acceptAgreement, { version: 'v0', displayName: 'x' }).then((r) => expectRefused(r, 'Reload the page'))
  })

  it('MKT-0a-06 [MKT-R20.1] [MKT-R22.1] only MaxTheService approves: a tenant cannot, the operator can, and suspension shows its reason', () => {
    relogin(SELLER_A)
    cy.orgOf(SELLER_A).then((org) => post(API.decideSeller, { organizationId: org.id, decision: 'APPROVE' }))
      .then((r) => expect(ok(r.body), 'a tenant owner is not the operator').to.eq(false))
    decide(SELLER_A, 'APPROVE').then((r) => { if (!ok(r.body)) expect(msg(r.body)).to.match(/approved cannot be moved/) })
    relogin(SELLER_A)
    get(API.sellerStatus).then((r) => expect(data(r.body).canSell, 'approved + agreed + switched on').to.eq(true))
    decide(SELLER_A, 'SUSPEND', ' ').then((r) => expectRefused(r, 'Give the seller a reason'))
    decide(SELLER_A, 'SUSPEND', 'documents expired').then((r) => expect(ok(r.body)).to.eq(true))
    relogin(SELLER_A)
    get(API.sellerStatus).then((r) => {
      expect(data(r.body).canSell).to.eq(false)
      expect(data(r.body).account.statusReason).to.eq('documents expired')
    })
    openMarketplace()
    cy.get('#mktStatusAccount').should('contain', 'Suspended: documents expired')
    decide(SELLER_A, 'REINSTATE').then((r) => expect(ok(r.body)).to.eq(true))
  })

  it('MKT-0a-07 [MKT-R20.1] the operator console lists the seller and its decision buttons follow the lifecycle (real UI)', () => {
    cy.loginAsOperator()
    cy.visit(UI.operatorPage)
    cy.get('#platMktSellersBtn').should('be.visible').click()
    cy.get('#platMktSellers').should('be.visible')
    cy.contains('#platMktStatus button', 'Approved').click()
    cy.orgOf(SELLER_A).then((org) => {
      cy.get(`#platMktSellerList [data-org="${org.id}"]`, { timeout: 15000 }).within(() => {
        cy.get('[data-decision="SUSPEND"]').should('be.visible')
        cy.get('[data-decision="APPROVE"]').should('not.exist')   // no move the server would refuse
      })
    })
    get(API.sellers + '?status=APPROVED').then((r) => {
      expect(ok(r.body)).to.eq(true)
      expect(list(r.body).map((a) => a.displayName)).to.include('Shahzad Mobile Shop')
    })
  })

  it('MKT-0a-08 [MKT-R22.1] a tenant cannot read the operator\'s seller list (with a positive control)', () => {
    cy.loginAsOperator()
    get(API.sellers).then((r) => expect(ok(r.body), 'positive control: the operator can').to.eq(true))
    relogin(SELLER_A)
    get(API.sellers).then((r) => expect(r.status === 403 || !ok(r.body), 'refused for a tenant').to.eq(true))
  })
})
