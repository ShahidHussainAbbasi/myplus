/**
 * H6 C3 — a recorded transaction belongs to the BUSINESS's day (client ruling 2026-10-11; QuickBooks / Xero / Odoo).
 * docs/hms-phase1-design.md §4l.
 *
 * The defect: the accounting readers took a purchase's UTC calendar day, so a bill recorded 00:00–05:00 Karachi was
 * dated the day BEFORE on the supplier statement, in aging, in the period lock and in the payables document sent to
 * finance. Fixture: demo.business's purchase PVINV-1791063442854, recorded 2026-10-04 02:37 Karachi
 * (= 2026-10-03 21:37 UTC) — one of 21 night-recorded bills in that tenant.
 *
 *   C3-01  the supplier statement dates that bill on its Karachi day. Expected = its UTC time (read with the zone
 *          cookie at UTC) + 5 h, computed HERE. RED on the old code: 2026-10-03.
 *   C3-02  the same statement on screen (Register → Vendor → Statement).
 *
 * Writes nothing on the server. The zone cookie is cleared at the end.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/business/h6c3-business-day.cy.js"
 */
import { rowsOf, localDay } from '../../support/time'
describe('H6 C3 — a recorded bill belongs to the business\'s day', () => {
  const INVOICE = 'PVINV-1791063442854'
  let bill   // { venderId, vendorName, utcText, expectedDay }


  it('C3-01 the supplier statement dates a bill recorded at 02:37 Karachi on that Karachi day', () => {
    cy.loginAsBusiness()
    cy.setCookie('myplus_tz', 'UTC')
    cy.request('/getUserPurchase').its('body').then((b) => {
      const p = rowsOf(b).find((r) => r.purchaseInvoiceNo === INVOICE)
      expect(p, `purchase ${INVOICE} in the list (fixture)`).to.exist
      bill = { venderId: p.venderId, vendorName: p.venderName || p.vendorName, utcText: p.dated }
      bill.expectedDay = localDay(p.dated, 'Asia/Karachi')
      expect(bill.expectedDay, 'fixture is a night bill: its Karachi day is after its UTC day')
        .to.not.eq(String(p.dated).replace(/^(\d{2})-(\d{2})-(\d{4}).*$/, '$3-$2-$1'))
    })
    cy.then(() => cy.request('/vendorStatement?venderId=' + bill.venderId)).its('body').then((b) => {
      const line = rowsOf(b).find((l) => l.docNo === INVOICE)
      expect(line, `${INVOICE} on the supplier statement`).to.exist
      expect(line.date, `recorded ${bill.utcText} UTC → the Karachi day`).to.eq(bill.expectedDay)
    })
  })

  it('C3-02 the statement on screen shows the bill on its Karachi day', () => {
    expect(bill, 'C3-01 found the bill').to.exist
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.openStatement('VENDOR', bill.venderId, bill.vendorName))
    cy.contains('#StatementDialogBody tr', INVOICE, { timeout: 20000 }).find('td').first()
      .should('have.text', bill.expectedDay)
      .then(($td) => { $td.closest('tr').css('outline', '3px solid #d97706'); $td[0].scrollIntoView({ block: 'center' }) })
    cy.screenshot('h6c3/C3-02-supplier-statement', { capture: 'viewport' })
  })

  after(() => { cy.clearCookie('myplus_tz') })
})
