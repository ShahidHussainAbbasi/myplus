/**
 * PERF-3 gate — one jQuery, minified.
 *
 * Design doc: microservices/docs/frontend-performance-audit.md (finding F4)
 *
 * WHAT CHANGED
 * fragments/header.html loaded TWO jQuery tags back to back — 1.11.2 (96KB), immediately overwritten by
 * 3.3.1 as the UNMINIFIED 282KB dev build. The 1.x tag is gone and the remaining one points at the
 * minified 3.3.1 that was already sitting unused in the same folder. login.html carries its own tag
 * (it does not use fragments/header) and was fixed too.
 *
 * WHAT THIS GATE ASSERTS
 * The change is meant to be behaviourally INVISIBLE — same library, same version, fewer bytes. So the
 * gate's job is not to prove bytes moved (that is arithmetic on file sizes); it is to prove the app is
 * still running the SAME jQuery and that every plugin still bound to it. A missing plugin is the real
 * failure mode: it would leave `$.fn.DataTable` undefined and every register screen blank, which a
 * "page returns 200" test would sail straight past.
 */

const JQ_VERSION = '3.3.1'

describe('PERF-3 — a single, minified jQuery', () => {
  describe('business dashboard (via fragments/header)', () => {
    beforeEach(() => {
      cy.loginAsBusiness()
      cy.visit('/businessDashboard')
    })

    it('runs jQuery 3.3.1 — the version in play is unchanged', () => {
      // The point of the slice: fewer bytes, SAME library. A different version here would mean the
      // change was an upgrade in disguise, with a whole different risk profile.
      cy.window().its('jQuery.fn.jquery').should('eq', JQ_VERSION)
    })

    it('loads exactly ONE jQuery tag, and it is the minified build', () => {
      cy.document().then((doc) => {
        const srcs = [...doc.querySelectorAll('script[src]')].map((s) => s.getAttribute('src'))
        // jQuery CORE only. The pattern deliberately anchors on the filename so plugins that merely
        // start with "jquery" (jquery.dataTables.min.js, jquery.timepicker.min.js) are not counted.
        const jqCore = srcs.filter((s) => /\/jquery(-[\d.]+)?(\.min)?\.js$/i.test(s))
        expect(jqCore, `expected exactly one jQuery core tag, got: ${JSON.stringify(jqCore)}`)
          .to.have.length(1)
        expect(jqCore[0], 'the jQuery tag must be the minified build').to.match(/jquery-3\.3\.1\.min\.js$/)
        // Both removed tags checked by name, so a future re-add is caught even if the count still reads 1.
        expect(srcs.some((s) => /\/jquery\.min\.js$/.test(s)), 'jQuery 1.11.2 tag must be gone').to.eq(false)
        expect(srcs.some((s) => /\/jquery-3\.3\.1\.js$/.test(s)), 'unminified 3.3.1 tag must be gone').to.eq(false)
      })
    })

    it('every jQuery plugin still bound — the real failure mode', () => {
      // These all load AFTER jQuery in fragments/header. If the tag order or the file had broken, they
      // would be silently undefined and whole screens would render empty while the page still 200s.
      cy.window().then((win) => {
        const $ = win.jQuery
        expect($.fn.DataTable, 'DataTables').to.be.a('function')
        expect($.fn.selectpicker, 'bootstrap-select').to.be.a('function')
        expect($.fn.datetimepicker, 'bootstrap-datetimepicker').to.be.a('function')
        expect($.fn.timepicker, 'jquery.timepicker').to.be.a('function')
        expect($.fn.modal, 'bootstrap modal').to.be.a('function')
      })
    })

    it('a real DataTable actually initialised and rendered', () => {
      // Plugin presence is necessary but not sufficient — assert one is genuinely driving the DOM.
      cy.get('#tableSellReport', { timeout: 20000 }).should('exist')
      cy.window().then((win) => {
        expect(win.jQuery.fn.dataTable.isDataTable('#tableSellReport'), '#tableSellReport is a DataTable')
          .to.eq(true)
      })
    })
  })

  describe('login page (carries its own jQuery tag)', () => {
    it('runs the same minified jQuery 3.3.1 on the first page a user ever loads', () => {
      // login.html does NOT use fragments/header, so it was a separate fix and can regress separately.
      cy.visit('/login')
      cy.window().its('jQuery.fn.jquery').should('eq', JQ_VERSION)
      cy.document().then((doc) => {
        const srcs = [...doc.querySelectorAll('script[src]')].map((s) => s.getAttribute('src'))
        const jqCore = srcs.filter((s) => /\/jquery(-[\d.]+)?(\.min)?\.js$/i.test(s))
        expect(jqCore, `login jQuery tags: ${JSON.stringify(jqCore)}`).to.have.length(1)
        expect(jqCore[0]).to.match(/jquery-3\.3\.1\.min\.js$/)
      })
    })

    it('the login form still works end to end', () => {
      // The cheapest possible proof that swapping the build did not break the one page that must work.
      cy.loginAsBusiness()
      cy.visit('/businessDashboard')
      cy.get('#tableSellReport', { timeout: 20000 }).should('exist')
    })
  })
})
