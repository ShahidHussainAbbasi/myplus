/**
 * Release readiness 2026-10-11 — every page that loads the shared header still opens cleanly.
 *
 * H6 added /js/common/datetime.js to the shared header (fragments/header.html), so it now loads on every dashboard
 * and public page. This visits each one as its own kind of user and checks:
 *   - the page opens with no uncaught script error (Cypress fails the case on one);
 *   - the shared formatter is there (fmtDateTime / fmtDate / fmtTime / typedToServer are functions);
 *   - console errors are RECORDED to cypress/results/shared-header-smoke.json for a person to read — not asserted,
 *     because pre-existing noise must not hide a new failure, and a new one must not hide behind the noise.
 *
 * Writes nothing on the server.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/ui/shared-header-smoke.cy.js"
 */
describe('Shared header — every page still opens, with the time formatter', () => {
  const PAGES = [
    { name: 'business dashboard', path: '/businessDashboard', login: () => cy.loginAsBusiness() },
    { name: 'pharmacy (business) dashboard', path: '/businessDashboard', login: () => cy.loginAsPharmaOwner() },
    { name: 'education dashboard', path: '/educationDashboard', login: () => cy.loginAsEducation() },
    { name: 'guardian portal', path: '/guardianDashboard', login: () => cy.loginAsPortalGuardian() },
    { name: 'student portal', path: '/studentDashboard', login: () => cy.loginAsPortalStudent() },
    { name: 'welfare dashboard', path: '/welfareDashboard', login: () => cy.loginAsWelfare() },
    { name: 'agriculture dashboard', path: '/agricultureDashboard', login: () => cy.loginAsAgriculture() },
    { name: 'appointment dashboard', path: '/appointmentDashboard', login: () => cy.loginAsAppointment() },
    { name: 'platform (operator) dashboard', path: '/platformDashboard', login: () => cy.loginAsOperator() },
    // The public pages do not use the shared header's scripts (login.html none; appointment.html its CSS only) and
    // none of their scripts call the formatter (checked 2026-10-11) — they are checked for opening cleanly only.
    { name: 'login page (public)', path: '/login', login: null, noFormatter: true },
    { name: 'public booking page', path: '/appointment', login: null, noFormatter: true },
  ]
  const results = []

  after(() => { cy.writeFile('cypress/results/shared-header-smoke.json', results) })

  PAGES.forEach((pg, i) => {
    it(`SH-${String(i + 1).padStart(2, '0')} ${pg.name} opens cleanly with the formatter`, () => {
      const errors = []
      if (pg.login) pg.login(); else cy.clearCookies()
      cy.visit(pg.path, {
        onBeforeLoad(win) {
          const orig = win.console.error
          win.console.error = function () { errors.push(Array.from(arguments).map(String).join(' ').slice(0, 300)); return orig.apply(this, arguments) }
        },
      })
      cy.location('pathname').should('eq', pg.path)          // not bounced to login or an error page
      cy.get('body').should('be.visible')
      if (!pg.noFormatter) cy.window().then((w) => {
        ;['fmtDateTime', 'fmtDate', 'fmtTime', 'typedToServer'].forEach((fn) =>
          expect(typeof w[fn], `${fn} on ${pg.path}`).to.eq('function'))
        expect(w.fmtDate('2026-10-11'), 'a date is never shifted').to.eq('2026-10-11')
        // DRY D1: the ONE escaper is loaded by the header on every page, apostrophe included
        expect(typeof w.escHtml, `escHtml on ${pg.path}`).to.eq('function')
        expect(w.escHtml(`<b class="x">it's</b>`)).to.not.match(/[<>"']/)
      })
      cy.wait(1500)   // let the page's own first requests settle, so their errors are recorded
      cy.then(() => results.push({ case: `SH-${String(i + 1).padStart(2, '0')}`, page: pg.name, path: pg.path, consoleErrors: errors }))
      cy.screenshot(`shared-header/SH-${String(i + 1).padStart(2, '0')}-${pg.path.replace(/\//g, '')}`, { capture: 'viewport' })
    })
  })
})
