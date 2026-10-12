/**
 * H6 C2 — the "Dated" text the SERVER formats (dd-MM-yyyy HH:mm:ss) is written in the viewer's zone, and a report's
 * days are the viewer's days (docs/hms-phase1-design.md §4k).
 *
 * The browser's zone reaches the services as the myplus_tz cookie → X-Render-Tz. The same customer is read three
 * times with the cookie set to UTC, Asia/Karachi and Pacific/Kiritimati: its Dated text must move by exactly +5 h and
 * +14 h. RED on the old code: it ignored the zone, so all three readings were the same UTC text.
 *
 *   C2-01  customers list (API): UTC / Karachi / Kiritimati differ by exactly 5 h and 14 h
 *   C2-02  the Customers grid in a browser emulating Kiritimati shows the Kiritimati text
 *   C2-03  a same-day sales report (from = to = the newest sale's Karachi day) contains that sale — guards the
 *          defect found in review: converting the end date BEFORE ending the day made a same-day report EMPTY
 *
 * Writes nothing on the server. The zone emulation and the cookie are reset after each case.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/business/h6c2-dated-text.cy.js"
 */
import { rowsOf, serverTextToMs, msToServerText } from '../../support/time'
describe('H6 C2 — server-formatted Dated text and report days in the viewer\'s zone', () => {
  const customersIn = (zone) => cy.setCookie('myplus_tz', encodeURIComponent(zone))
    .then(() => cy.request('/getUserCustomer')).its('body').then(rowsOf)

  let sample   // { id, name, utc, karachi, kiritimati }

  afterEach(() => { cy.emulateZone('') })

  it('C2-01 the same customer\'s Dated text moves by exactly +5 h (Karachi) and +14 h (Kiritimati)', () => {
    cy.loginAsBusiness()
    customersIn('UTC').then((utcRows) => {
      // a customer with a Dated time and a name no other customer has, so the grid's search finds exactly it
      const names = utcRows.map((r) => r.name)
      const c = utcRows.find((r) => r.dated && r.name && names.filter((n) => n === r.name).length === 1)
      expect(c, 'a customer with a Dated time').to.exist
      sample = { id: c.customerId || c.id, name: c.name, utc: c.dated }
    })
    customersIn('Asia/Karachi').then((rows) => {
      sample.karachi = rows.find((r) => (r.customerId || r.id) === sample.id).dated
    })
    customersIn('Pacific/Kiritimati').then((rows) => {
      sample.kiritimati = rows.find((r) => (r.customerId || r.id) === sample.id).dated
      const h = (a, b) => (serverTextToMs(a) - serverTextToMs(b)) / 3600000
      expect(h(sample.karachi, sample.utc), `Karachi ${sample.karachi} vs UTC ${sample.utc}`).to.eq(5)
      expect(h(sample.kiritimati, sample.utc), `Kiritimati ${sample.kiritimati} vs UTC ${sample.utc}`).to.eq(14)
    })
  })

  it('C2-02 the Customers grid in a Kiritimati browser shows the Kiritimati text', () => {
    expect(sample, 'C2-01 found a customer').to.exist
    // expected = the UTC reading + 14 h, computed HERE — not the server's own Kiritimati reading, which on a
    // server that ignores the zone is the same UTC text and would make this case pass on the defect
    const want = msToServerText(serverTextToMs(sample.utc) + 14 * 3600000)
    cy.loginAsBusiness()
    cy.emulateZone('Pacific/Kiritimati')
    cy.visit('/businessDashboard')   // main.js sets myplus_tz from the (emulated) browser zone
    cy.getCookie('myplus_tz').its('value').then((v) => expect(decodeURIComponent(v)).to.eq('Pacific/Kiritimati'))
    cy.get('#snavRegister > .snav-btn').click()
    cy.get('#snavRegister a[onclick*="CustomerDiv"]').click()
    cy.get('#CustomerDiv #tableCustomer').should('be.visible')
    // 968 customers, 5 a page: find this one the way a person would — the grid's Search box
    cy.get('#CustomerDiv input[type="search"]').first().clear().type(sample.name)
    cy.get('#tableCustomer tbody tr').should('have.length', 1)
    // and show the Dated column if the screen has it switched off
    cy.get('#tableCustomer thead').then(($h) => {
      if (!/Dated/i.test($h.text())) cy.contains('#CustomerDiv a.toggle-vis', 'Dated').click()
    })
    cy.get('#tableCustomer thead').should('contain.text', 'Dated')
    // the Dated cell of the one row, by the Dated header's position — a mismatch names the text actually shown
    cy.get('#tableCustomer thead th').then(($ths) => {
      const col = [...$ths].findIndex((th) => /^\s*Dated\s*$/i.test(th.textContent))
      expect(col, 'the Dated column').to.be.at.least(0)
      cy.get('#tableCustomer tbody tr').first().find('td').eq(col).should('have.text', want)
        .then(($td) => { $td.css('outline', '3px solid #d97706'); $td[0].scrollIntoView({ block: 'center', inline: 'center' }) })
    })
    cy.screenshot('h6c2/C2-02-customers-dated-Kiritimati', { capture: 'viewport' })
  })

  it('C2-03 a same-day sales report in Karachi contains the newest sale of that Karachi day', () => {
    cy.loginAsBusiness()
    cy.setCookie('myplus_tz', encodeURIComponent('Asia/Karachi'))
    cy.request({ method: 'POST', url: '/loadSR', form: true, body: { rp: 0 } }).its('body').then((b) => {
      const sale = rowsOf(b).find((s) => s.dated)
      expect(sale, 'a sale in the default period').to.exist
      const day = String(sale.dated).substring(0, 10)                 // dd-MM-yyyy, Karachi
      const id = sale.sellId || sale.id
      cy.request({ method: 'POST', url: '/loadSR', form: true,
        body: { sd: day + ' 00:00:00', ed: day + ' 00:00:00' } }).its('body').then((same) => {
        const rows = rowsOf(same)
        expect(rows.length, `same-day report for ${day} is not empty`).to.be.greaterThan(0)
        expect(rows.map((r) => r.sellId || r.id), `contains the sale dated ${sale.dated}`).to.include(id)
      })
    })
  })

  after(() => { cy.clearCookie('myplus_tz') })
})
