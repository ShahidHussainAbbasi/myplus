/**
 * "Reset demo" — clears the trial end to end: the banner button calls /demo/reset, which (1) clears the gateway
 * write counters and (2) purges the caller's own org data in EVERY purge-capable service (shared common-service
 * DemoPurgeController, org-scoped, privilege-guarded). Requires the stack up.
 *
 * ⚠ THE OWNER CASE IS OPT-IN, because it genuinely WIPES the owner.business@myplus.com org — org 13, the shared
 * dev tenant and the account the developer signs in with. This file used to carry that as a warning in prose:
 * "run this one on its own if you have hand-made data sitting in the owner org". On 2026-09-22 a full-suite
 * sweep ran it in a batch of ten. It passed — clearing the org IS the feature — and the damage landed on the
 * specs that ran afterwards: return-documents, returns-list, returns-parity and sale-report-period all failed
 * preconditions that read like broken returns and reporting features. Nothing was wrong with any of them.
 *
 * A comment cannot stop a spec runner, so the gate is now executable, per the opt-in pattern cypress.config.js
 * documents (excludeSpecPattern would make it unrunnable even by name):
 *
 *     npm run test:e2e:demo-reset          # or: npx cypress run --spec … --env destructive=1
 *
 * The first two cases stay in the ordinary run: they purge demo.appointment@, a throwaway demo tenant that
 * exists to be purged, and the "Cancel" case writes nothing at all.
 */
describe('Reset demo clears data + counter', () => {
  it('purges the demo account data and resets the cap from the banner button', () => {
    cy.loginAs('demo.appointment@myplus.com', 'Demo@2025!', '/appointmentDashboard')
    cy.visit('/appointmentDashboard')

    // The Reset demo button lives in the demo banner (demo accounts only), and now opens the app's themed
    // confirm dialog instead of window.confirm — so the test has to confirm it explicitly.
    cy.intercept('POST', '**/demo/reset').as('reset')
    cy.contains('Reset demo').should('be.visible').click()
    cy.get('[data-ui-confirm="ok"]').should('be.visible').click()
    cy.wait('@reset').its('response.statusCode').should('eq', 200)

    // demo.js reloads the page; after the purge the appointments table is empty.
    cy.get('#apptTableBody', { timeout: 15000 }).should('contain', 'No appointments')
  })

  it('does NOT reset when the confirmation is dismissed', () => {
    // The whole point of the dialog: "Cancel" must leave the data alone. Guards the shared uiConfirm component.
    cy.loginAs('demo.appointment@myplus.com', 'Demo@2025!', '/appointmentDashboard')
    cy.visit('/appointmentDashboard')

    cy.intercept('POST', '**/demo/reset').as('reset')
    cy.contains('Reset demo').should('be.visible').click()
    cy.contains('button', 'Keep my data').should('be.visible').click()
    cy.get('.uiC-backdrop').should('not.exist')
    cy.wait(500)
    cy.get('@reset.all').should('have.length', 0)
  })

  // ⚠ DESTRUCTIVE — wipes org 13. Opt in with --env destructive=1; see the header.
  const destructive = Cypress.env('destructive') ? it : it.skip

  destructive('lets the OWNER demo account reset too, and clears data beyond its own module', () => {
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
      cy.get('[data-ui-confirm="ok"]').should('be.visible').click()
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
