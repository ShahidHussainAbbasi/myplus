/**
 * PERF — the business dashboard's first load does not freeze the page, AND its dropdowns still fill.
 *
 * Design: microservices/docs/blocking-ui-and-backend-guards-design.md §4.3.3
 *
 * ── What this exists to catch ───────────────────────────────────────────────────────────────────
 * A CPU profile of the first dashboard load (2026-09-14) found the main thread blocked for ~32.6 s in four
 * stretches (8.4, 5.8, 5.2 and 13.2 s) — 39.8 s of a 52.6 s profile inside searchable-selects.js's ajaxComplete
 * hook, which refreshed EVERY bootstrap-select picker after EVERY request. The pickers hold thousands of
 * products and customers, and that count grows every time a spec seeds data, so it only ever got worse. It
 * surfaced as "never went quiet" beforeEach timeouts in the BLK-2, BLK-3 and BLK-4 gates.
 *
 * ── ⚠ Why the second and third cases matter as much as the first ──────────────────────────────────
 * The cheapest way to pass case 1 is to stop refreshing pickers at all — and then "Select Customer" never fills,
 * which is the exact trap sale-nonblocking-load.cy.js already records. So a fix is only a fix if the pickers
 * still show their options (case 2) and still show a value set by code (case 3).
 *
 * Thresholds are deliberately generous: the before-fix numbers exceed them by a wide margin, so a slow machine
 * does not flake this, and a return of the defect cannot pass it.
 *
 * Run headed, SOLO, in Chrome (long-task timing is a Chromium API).
 */

const MAX_SINGLE_MS = 3000    // before the fix: 13,208 ms
const MAX_TOTAL_MS = 8000     // before the fix: ~32,600 ms

describe('PERF — the first dashboard load does not freeze, and the pickers still fill', () => {
  it('⭐⭐ 1 — no multi-second main-thread freeze on the first dashboard load', () => {
    cy.loginAsOwner()
    cy.visit('/businessDashboard', {
      onBeforeLoad (win) {
        const lt = []
        try {
          new win.PerformanceObserver((list) => {
            list.getEntries().forEach((e) => lt.push({ start: Math.round(e.startTime), dur: Math.round(e.duration) }))
          }).observe({ entryTypes: ['longtask'] })
        } catch (e) { lt.unsupported = e.message }
        win.__lt = lt
      },
    })
    cy.wait(15000)   // well past where the old freeze began (first block at 3.6 s, 8.4 s long)
    cy.window().then((win) => {
      const lt = win.__lt
      expect(lt, 'long-task timing was registered before the page ran').to.be.an('array')
      expect(lt.unsupported, 'this browser reports long tasks (run with --browser chrome)').to.be.undefined
      const total = lt.reduce((a, t) => a + t.dur, 0)
      const max = lt.reduce((a, t) => Math.max(a, t.dur), 0)
      const top = lt.slice().sort((a, b) => b.dur - a.dur).slice(0, 5).map((t) => `${t.dur}ms@${t.start}`).join(', ')
      Cypress.log({ name: 'longtasks', message: `max ${max} ms, total ${total} ms — ${top || 'none'}` })
      expect(max, `⭐ the longest main-thread block (top: ${top})`).to.be.lessThan(MAX_SINGLE_MS)
      expect(total, '⭐ all main-thread blocking on the first load, together').to.be.lessThan(MAX_TOTAL_MS)
    })
  })

  it('⭐⭐ 2 — the customer and product pickers still FILL (a fix that stopped refreshing would pass case 1)', () => {
    cy.loginAsOwner()
    cy.visitSaleScreen()
    // The DOM half, then the WIDGET half — the widget is what the cashier reads, and it is what a missing
    // refresh would leave empty while the <select> itself looked full.
    cy.get('#sellCustomerDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)
    cy.get('#sellCustomerDD').next('.bootstrap-select').find('li', { timeout: 30000 })
      .should('have.length.greaterThan', 1)
    cy.get('#sellItemDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)
    cy.get('#sellItemDD').next('.bootstrap-select').find('li', { timeout: 30000 })
      .should('have.length.greaterThan', 1)
  })

  it('⭐ 3 — a value set by CODE shows on the widget (the selection-only redraw)', () => {
    cy.loginAsOwner()
    cy.visitSaleScreen()
    cy.get('#sellCustomerDD option', { timeout: 30000 }).should('have.length.greaterThan', 2)
    cy.window().then((win) => {
      const $dd = win.jQuery('#sellCustomerDD')
      const $opt = $dd.find('option').filter(function () { return this.value }).eq(1)
      const text = $opt.text().trim()
      expect(text, 'a real customer option to pick').to.not.eq('')
      $dd.val($opt.val())                                     // what code does — no change event, no refresh call
      win.jQuery.get(win.serverContext + 'getCapabilities')   // any global request completes → the pass runs
      cy.get('#sellCustomerDD').next('.bootstrap-select').find('.filter-option', { timeout: 10000 })
        .should(($f) => { expect($f.text().trim(), 'the button shows the value code set').to.contain(text) })
    })
  })

  /*
   * ⚠ Cases 2 and 3 went red on the first fix, and a diagnostic measured why: New Sale puts focus on the customer
   * picker's BUTTON, the busy guard counted that as "in use", and a picker that is never opened is never closed —
   * so its refresh waited for ever (1,615 options, ONE row, for 33 s). Cases 4 and 5 cover the other two measured
   * defects of that first fix.
   */
  it('⭐ 4 — a picker its loader has just rebuilt is NOT rebuilt a second time by the next request', () => {
    // loadUserItems calls selectpicker('refresh') itself. The first fix never heard about that, kept the picker
    // "dirty", and rebuilt all 1,857 rows again on the next request (2.9 s, measured). A rebuild replaces every
    // <li>, so the SAME row element still being in the page afterwards is what "not rebuilt" looks like.
    cy.loginAsOwner()
    cy.visitSaleScreen()
    cy.get('#sellItemDD').next('.bootstrap-select').find('li', { timeout: 30000 }).should('have.length.greaterThan', 1)
    cy.wait(3000)   // let the screen's own loads settle
    cy.window().then((win) => {
      const el = win.document.getElementById('sellItemDD')
      const firstLi = win.jQuery(el).next('.bootstrap-select').find('li').get(1)
      const before = { n: el.options.length, first: el.options[1] && el.options[1].value }
      win.jQuery.get(win.serverContext + 'getCapabilities')   // a global request → the pass runs
      cy.wait(2500).then(() => {
        const after = { n: el.options.length, first: el.options[1] && el.options[1].value }
        expect(after, 'the product options themselves did not change (else a rebuild would be right)').to.deep.eq(before)
        expect(firstLi.isConnected, '⭐ the same row element is still in the list — it was not rebuilt again').to.eq(true)
      })
    })
  })

  it('⭐⭐ 5 — a picker on a HIDDEN screen is not rebuilt at load, and still fills when its screen opens', () => {
    // The Sale Detail Report's filter rail (#rfCustomer, #rfProduct) is filled on page load while its screen is
    // hidden. Rebuilding those two WAS the whole remaining freeze (3.2 s + 2.7 s, measured). They now wait until
    // they can be seen — so this proves both halves: nothing built while hidden, everything built once shown,
    // without the menu ever being opened.
    cy.loginAsOwner()
    cy.visit('/businessDashboard')
    cy.get('#rfCustomer option', { timeout: 30000 }).should('have.length.greaterThan', 1)
    cy.wait(2000)   // any pass the fill's own completion scheduled has run
    cy.get('#rfCustomer').next('.bootstrap-select').find('li')
      .should('have.length', 1)   // only the placeholder row: not rebuilt while nobody can see it
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#SRDiv').should('be.visible')
    cy.get('#rfCustomer').then(($s) => {
      const n = $s[0].options.length
      cy.get('#rfCustomer').next('.bootstrap-select').find('li', { timeout: 15000 })
        .should('have.length', n)   // every option has its row — built on coming on screen
      cy.get('#rfCustomer').next('.bootstrap-select').should('not.have.class', 'open')   // …without opening it
    })
  })
})
