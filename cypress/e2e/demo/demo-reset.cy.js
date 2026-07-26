/**
 * "Reset demo" — clears the trial end to end: the banner button calls /demo/reset, which (1) clears the gateway
 * write counters and (2) purges the caller's own org data in EVERY purge-capable service (shared common-service
 * DemoPurgeController, org-scoped, privilege-guarded). Requires the stack up.
 *
 * ⚠ The second test genuinely WIPES the owner.business@myplus.com org (that is the feature). That account is a
 * dev test fixture and every other spec builds its own data at runtime, but run this one on its own if you have
 * hand-made data sitting in the owner org.
 */
describe('Reset demo clears data + counter', () => {
  it('purges the demo account data and resets the cap from the banner button', () => {
    cy.loginAs('demo.appointment@myplus.com', 'Demo@2025!', '/appointmentDashboard')
    cy.visit('/appointmentDashboard')

    // The Reset demo button lives in the demo banner (demo accounts only).
    cy.intercept('POST', '**/demo/reset').as('reset')
    cy.contains('Reset demo').should('be.visible').click()   // Cypress auto-accepts the confirm()
    cy.wait('@reset').its('response.statusCode').should('eq', 200)

    // demo.js reloads the page; after the purge the appointments table is empty.
    cy.get('#apptTableBody', { timeout: 15000 }).should('contain', 'No appointments')
  })

  it('lets the OWNER demo account reset too, and clears data beyond its own module', () => {
    // owner.business is demo=false (uncapped) and carries DEMO_RESET_PRIVILEGE via its own role — so it gets the
    // button without ROLE_OWNER granting a real customer's owner a one-click "delete my organisation".
    cy.loginAs('owner.business@myplus.com', 'Demo@2025!', '/businessDashboard')

    const stamp = Date.now()
    const cname = 'ResetCust_' + stamp
    const list = (b) => b.collection || b.data || []

    // Data in TWO services: a customer (business-service) and a product (catalog-service). The old reset only
    // ever cleared the user's own module, so the product would have survived.
    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: cname, contact: '03' + String(stamp).slice(-9) }, failOnStatusCode: false })
      .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.seedProduct({ name: 'ResetProd_' + stamp }).then(({ productId }) => {
      expect(productId, 'product seeded').to.exist

      cy.visit('/businessDashboard')
      cy.intercept('POST', '**/demo/reset').as('reset')
      cy.contains('Reset demo data').should('be.visible').click()
      cy.wait('@reset').then((i) => {
        expect(i.response.statusCode, 'owner is allowed to reset').to.eq(200)
        expect(String(i.response.body.message), 'reports what it cleared').to.contain('services')
      })

      // Both services are empty for this org afterwards.
      cy.request('/getUserCustomer').then((cr) => {
        expect(list(cr.body).find((x) => x.name === cname), 'customer purged (business-service)').to.not.exist
      })
      cy.request({ url: '/catalogProducts?size=1000', failOnStatusCode: false }).then((pr) => {
        const items = (pr.body && pr.body.data && pr.body.data.content) || list(pr.body)
        expect(items.find((x) => x.id === productId), 'product purged (catalog-service — beyond the old own-module reset)').to.not.exist
      })
    })
  })
})
