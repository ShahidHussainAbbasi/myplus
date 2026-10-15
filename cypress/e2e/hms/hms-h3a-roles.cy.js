/**
 * HMS H3a — who is the pharmacist, and which sets a business is offered (client ruling L-3; design §4h).
 *
 *   A-01  a clinic + pharmacy business: the Team screen offers "Clinic reception" and "Doctor"; the pharmacist code
 *         ("Dispense, change or cancel prescriptions") is in Standard and Administrator, NOT in Clinic reception or Doctor.
 *   A-02  a shop with neither the clinic nor prescriptions is offered none of them.
 *   A-03  a doctor registered by the owner lands on the Doctor set (not Standard): no selling, no dispensing.
 *
 * Roles: owner.pharma@ (clinic switched on) · owner.business@ (a shop: clinic OFF, prescriptions as it has them).
 *
 * Server state put back: clinic switch reset in after(); the shop's prescriptions switch reset in A-02. ⚠ RESIDUE: the login A-03 registers (no remove-member
 * route exists — recorded gap) and its doctor.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-h3a-roles.cy.js"
 */
describe('HMS H3a — the pharmacist permission and the clinic sets', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const SHOP = 'owner.business@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const S = {}

  const relogin = (email, phase = 'on') => cy.loginAs(email, PW, '/getBusinessDashboardStats', `hms-h3a-${run}-${phase}`)
  const permissions = () => cy.request('/team/permissions').its('body').then((b) => (b.data || b.object))
  const setNamed = (perms, name) => (perms.sets || []).find((s) => s.name === name)

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms h3a gate')
    relogin(OWNER, 'pre')
    cy.setCapability('clinic', true)
  })

  after(() => {
    relogin(OWNER)
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('A-01 [L-3] a clinic + pharmacy is offered Clinic reception and Doctor; only Standard and Administrator dispense', () => {
    relogin(OWNER)
    permissions().then((p) => {
      expect(p.catalog.map((c) => c.code), 'the pharmacist code is offered').to.include('rx.dispense')
      const standard = setNamed(p, 'Standard'); const admin = setNamed(p, 'Administrator')
      const reception = setNamed(p, 'Clinic reception'); const doctor = setNamed(p, 'Doctor')
      expect(reception, 'Clinic reception is offered').to.exist
      expect(doctor, 'Doctor is offered').to.exist
      expect(standard.codes, 'Standard keeps dispensing (nobody loses access)').to.include('rx.dispense')
      expect(admin.codes, 'Administrator keeps dispensing').to.include('rx.dispense')
      expect(reception.codes, 'the front desk does not dispense').to.not.include('rx.dispense')
      expect(reception.codes, '…but takes the consultation fee at the till (RW-1 D1)').to.include('sale.create')
      // RW-1 (D5): the front desk is not the pharmacy's back office — no stock, purchases, prices or reports
      ;['purchase.create', 'product.edit', 'stock.edit', 'supplier.create', 'report.view'].forEach((c) =>
        expect(reception.codes, `the front desk has no ${c}`).to.not.include(c))
      expect(reception.codes, 'the front desk gives tokens').to.include('clinic.token.issue')
      // RW-1 (D3): the pharmacist dispenses and sells but does not receive stock; the store keeper does
      expect(setNamed(p, 'Pharmacist').codes).to.include('rx.dispense').and.not.include('purchase.create')
      expect(setNamed(p, 'Storekeeper').codes).to.include('purchase.create').and.not.include('rx.dispense')
      // RW-1: the Doctor set also reads the queue and finds a patient — it still never dispenses or sells
      expect(doctor.codes, 'a doctor consults').to.include('clinic.consult')
      ;['rx.dispense', 'sale.create', 'purchase.create', 'clinic.token.issue', 'clinic.patient.edit'].forEach((c) =>
        expect(doctor.codes, `a doctor has no ${c}`).to.not.include(c))
      S.doctorSetId = doctor.id
    })
    // on the screen: the owner's set picker lists them
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#navTeam, [onclick*="showTeam"]').first().click({ force: true })
    cy.get('#permSetPicker option', { timeout: 20000 }).then(($o) => {
      const names = $o.toArray().map((o) => o.textContent)
      expect(names.join(' | ')).to.contain('Clinic reception').and.contain('Doctor')
    })
    cy.screenshot('hms-h3a/A-01-1-team-sets', { capture: 'viewport' })
  })

  it('A-02 [L-3] a shop without the clinic or prescriptions is offered none of them', () => {
    relogin(SHOP, 'shop')
    // both switches OFF for this case, explicitly — the effective value can also come from the type preset
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
    cy.setCapability('rxRequired', false)
    permissions().then((p) => {
      const codes = p.catalog.map((c) => c.code)
      expect(codes, 'no clinic code').to.not.include('clinic.consult')
      expect(codes, 'no pharmacist code').to.not.include('rx.dispense')
      const names = p.sets.map((s) => s.name)
      expect(names, 'no Doctor set').to.not.include('Doctor')
      expect(names, 'no Clinic reception set').to.not.include('Clinic reception')
      p.sets.forEach((s) => {
        expect(s.codes, s.name + ' shows no clinic code').to.not.include('clinic.consult')
        expect(s.codes, s.name + ' shows no pharmacist code').to.not.include('rx.dispense')
      })
    })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.rxRequired' }, failOnStatusCode: false })
  })

  it('A-03 [L-3] a doctor registered by the owner lands on the Doctor set — no selling, no dispensing', () => {
    relogin(OWNER)
    const email = `h3a.${run}@test.myplus.com`
    cy.postJson('/clinic/doctors/register', { name: 'Dr H3a ' + run, email, dailyLimit: 5 }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    })
    cy.request('/team/users').its('body').then((b) => {
      const m = (b.data || b.object || []).find((x) => x.email === email)
      expect(m, 'the new login').to.exist
      expect(m.permissionSetId, 'on the Doctor set, not Standard').to.eq(S.doctorSetId)
    })
  })
})
