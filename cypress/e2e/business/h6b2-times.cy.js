/**
 * H6 phase B2 — POS, e-commerce and price screens show times in the VIEWER's zone (docs/hms-phase1-design.md §4j).
 *
 * Each case reads the RAW value the screen's own list request returned, works out the expected text here with Intl
 * (not with the page's formatter), and compares the screen — in two zones: Asia/Karachi and Pacific/Kiritimati
 * (UTC+14), so "add 5 hours" cannot pass.
 *
 *   B2-01/02  Parked sales (business owner)  — parkedAt, zone-less UTC. RED on the old code (it cut the UTC text).
 *   B2-03/04  E-commerce orders (marketplace owner) — createdAt, zone-less UTC. RED on the old code.
 *   B2-05     Price approvals (pharmacy owner) — proposedAt already arrives WITH Z, so the old code was already right;
 *             this case proves B2 kept it right (it is expected to pass on the old code too).
 * Screens with no rows for any demo account (till shift, installments, audit log, support sessions, driver
 * settlements, marketplace cases — probe 2026-10-11) are not gated here.
 *
 * Writes nothing on the server. The zone emulation is reset after each case.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/business/h6b2-times.cy.js"
 */
describe('H6 B2 — POS, e-commerce and price screens in the viewer\'s zone', () => {
  const emulateZone = (timezoneId) => cy.wrap(Cypress.automation('remote:debugger:protocol',
    { command: 'Emulation.setTimezoneOverride', params: { timezoneId } }))
  /** "yyyy-MM-dd HH:mm" in `zone` for a server value (zone-less = UTC), computed here with Intl. */
  const expected = (raw, zone) => {
    let iso = String(raw).replace(' ', 'T')
    if (!/([zZ]|[+-]\d{2}:?\d{2})$/.test(iso)) iso = iso.replace(/(\.\d+)?$/, '') + 'Z'
    const p = {}
    new Intl.DateTimeFormat('en-GB', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
      .formatToParts(new Date(iso)).forEach((x) => { p[x.type] = x.value })
    return `${p.year}-${p.month}-${p.day} ${p.hour === '24' ? '00' : p.hour}:${p.minute}`
  }
  const shot = (name) => cy.screenshot(`h6b2/${name}`, { capture: 'viewport' })
  const mark = ($td) => { $td.css('outline', '3px solid #d97706'); $td[0].scrollIntoView({ block: 'center' }) }

  afterEach(() => { emulateZone('') })

  ;['Asia/Karachi', 'Pacific/Kiritimati'].forEach((zone, i) => {
    it(`B2-0${i + 1} parked sales show when each was parked, in ${zone}`, () => {
      cy.loginAsBusiness()
      emulateZone(zone)
      cy.intercept('GET', '**/parkedSales*').as('parked')
      cy.visit('/businessDashboard')
      cy.window().its('viewerZone').then((vz) => expect(vz()).to.eq(zone))
      cy.get('#snavSell > .snav-btn').click()
      cy.get('#snavSell a[onclick^="showParked"]').click()
      cy.wait('@parked').then(({ response }) => {
        const first = (response.body.collection || [])[0]
        expect(first, 'a parked sale to read').to.exist
        const want = expected(first.parkedAt, zone)
        expect(want, 'converted differs from the raw UTC slice')
          .to.not.eq(String(first.parkedAt).replace('T', ' ').substring(0, 16))
        cy.get('#parkedBody tr').first().find('td').eq(3).should('have.text', want).then(mark)
      })
      shot(`B2-0${i + 1}-parked-${zone.replace('/', '-')}`)
    })
  })

  ;['Asia/Karachi', 'Pacific/Kiritimati'].forEach((zone, i) => {
    it(`B2-0${i + 3} e-commerce orders show when each was placed, in ${zone}`, () => {
      cy.loginAsMarketplaceOwner()
      emulateZone(zone)
      cy.intercept('GET', '**/getOrders*').as('orders')
      cy.visit('/businessDashboard')
      cy.get('#snavStore > .snav-btn').click()
      cy.get('#snavStore a[onclick^="showOrders"]').click()
      cy.wait('@orders').then(({ response }) => {
        const b = response.body
        const list = (b.data && (b.data.content || b.data.items)) || b.content || b.collection || b.data || []
        const first = list[0]
        expect(first, 'an order to read').to.exist
        cy.get('#ordersBody tr').first().find('td').eq(-2).should('have.text', expected(first.createdAt, zone)).then(mark)
      })
      shot(`B2-0${i + 3}-orders-${zone.replace('/', '-')}`)
    })
  })

  it('B2-05 price approvals (already sent with Z) still read in the viewer\'s zone', () => {
    cy.loginAsPharmaOwner()
    emulateZone('Pacific/Kiritimati')
    cy.intercept('GET', '**/priceApprovals*').as('pa')
    cy.visit('/businessDashboard')
    cy.get('#snavPurchase > .snav-btn').click()
    cy.get('#navPriceApprovals').click()
    cy.wait('@pa').then(({ response }) => {
      const b = response.body
      const list = b.collection || (b.data && (b.data.content || b.data.items || b.data)) || []
      const first = list[0]
      expect(first, 'an approval to read').to.exist
      expect(String(first.proposedAt), 'sent with its offset').to.match(/Z$/)
      // the screen keeps its own look (the browser's locale format) — computed here for the emulated zone
      const want = new Date(first.proposedAt).toLocaleString(undefined, { timeZone: 'Pacific/Kiritimati' })
      cy.get(`#tablePriceApprovals tbody tr[data-id="${first.id}"] td`).eq(6).should('have.text', want).then(mark)
    })
    shot('B2-05-price-approvals-Kiritimati')
  })
})
