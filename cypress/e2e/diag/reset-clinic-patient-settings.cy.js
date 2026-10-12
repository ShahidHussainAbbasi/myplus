/**
 * One-off cleanup (2026-10-12): owner.pharma's clinic still has the two settings the S1 spec switches ON and is meant
 * to reset in its after() — "CNIC required" and "family on one phone" (rows dated 2026-10-11 21:29 / 21:32: an S1 run
 * whose cleanup did not complete). Every spec that registers a patient without a CNIC fails on them. Puts both back on
 * the clinic's defaults through the app's own reset, and checks it.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/diag/reset-clinic-patient-settings.cy.js"
 */
describe('cleanup — owner.pharma clinic patient settings back on their defaults', () => {
  it('resets cnicRequired and familyOnOnePhone', () => {
    cy.loginAsPharmaOwner()
    ;['clinic.patient.cnicRequired', 'clinic.patient.familyOnOnePhone'].forEach((k) =>
      cy.request({ method: 'POST', url: `/clinic/settings/reset?key=${k}` }).its('body.success').should('eq', true))
    cy.request('/clinic/settings').its('body.data').then((list) => {
      ;['clinic.patient.cnicRequired', 'clinic.patient.familyOnOnePhone'].forEach((k) => {
        const e = (list || []).find((x) => x.key === k)
        expect(e, k).to.exist
        expect(e.isDefault !== false, `${k} back on the default (value ${e.value})`).to.eq(true)
      })
    })
  })
})
