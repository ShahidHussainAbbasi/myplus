/**
 * HMS RW-4a — the business's MONEY (this month's revenue and sales, the stock value) is for those who may see
 * reports (client, 2026-10-12: "the front desk was looking at the shop's revenue"). The SERVER leaves the figures out
 * of the answer (business-service, report.view); the page shows no card for them (ui_menu rows kpi.*, V28).
 *
 *   RW4A-01  the front desk: no money cards, and the stats answer carries no revenue, sales or stock value
 *   RW4A-02  the doctor: the same
 *   RW4A-03  the owner: every card, and the answer carries the figures (control)
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-rw4a-money.cy.js"
 */
import { HMS } from '../../support/hms-accounts'

describe('HMS RW-4a — the business\'s money is for those who may see reports', () => {
  const run = String(Date.now()).slice(-7)
  const relogin = (email, phase) => cy.loginAs(email, HMS.PW, '/getBusinessDashboardStats', `hms-rw4a-${run}-${phase}`)
  const shot = (n) => cy.screenshot(`hms-rw4a/${n}`, { capture: 'viewport', overwrite: true })
  const MONEY = ['monthlyRevenue', 'monthlySales', 'stockValue']
  const stats = () => cy.request('/getBusinessDashboardStats').its('body').then((b) => {
    expect(b.status, JSON.stringify(b).slice(0, 200)).to.eq('SUCCESS')
    return b.object
  })

  it('RW4A-01 the front desk sees no money — neither on screen nor in the answer', () => {
    relogin(HMS.RECEPTION, 'desk')
    stats().then((s) => {
      expect(s).to.have.property('customers')                         // the ordinary counts are still there
      MONEY.forEach((k) => expect(s, `no ${k} for the desk`).to.not.have.property(k))
    })
    cy.visit('/businessDashboard')
    cy.get('#dashCustomers', { timeout: 15000 }).should('exist')
    ;['#dashStockValue', '#dashMonthlySales', '#dashMonthlyRevenue'].forEach((sel) => cy.get(sel).should('not.exist'))
    shot('RW4A-01-1-desk-no-money-cards')
  })

  it('RW4A-02 the doctor sees no money either', () => {
    relogin(HMS.DOCTOR, 'doctor')
    stats().then((s) => MONEY.forEach((k) => expect(s, `no ${k} for the doctor`).to.not.have.property(k)))
  })

  it('RW4A-03 the owner sees every money card and figure', () => {
    relogin(HMS.OWNER, 'owner')
    stats().then((s) => {
      expect(s).to.have.property('monthlyRevenue')
      expect(s).to.have.property('monthlySales')
    })
    cy.visit('/businessDashboard')
    ;['#dashStockValue', '#dashMonthlySales', '#dashMonthlyRevenue'].forEach((sel) => cy.get(sel).should('exist'))
    shot('RW4A-03-1-owner-money-cards')
  })
})
