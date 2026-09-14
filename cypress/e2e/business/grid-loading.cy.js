/**
 * BLK-3 — a grid that is loading looks like it is loading, and never says "no records" before the answer.
 *
 * Design: microservices/docs/blocking-ui-and-backend-guards-design.md §4.3.2
 *
 * ── What this exists to catch ───────────────────────────────────────────────────────────────────
 * DataTables 1.10.19 (the build this app serves) opened every module grid on ONE English "Loading..." line, and
 * any second draw before the data arrived — the "Toggle column" links do that — printed "No data available in
 * table" over a grid that was still loading. business.js also wrote an English "No Data Found" into the FIRST
 * empty cell on the whole page, which is not necessarily the grid being loaded.
 *
 * ── ⚠ How each case is made to test something ───────────────────────────────────────────────────
 * Every loading case HOLDS the grid's own read open (a response delay), so the assertions run inside the
 * window that used to lie. Each waits on that read BY NAME afterwards — if a screen ever stops issuing it, the
 * case fails on the wait instead of passing with nothing held. A skeleton assertion alone would pass on a grid
 * that never loads; so every hold case also asserts the skeleton is GONE afterwards.
 *
 * ⚠ Sections are opened the way the APP opens them. Products and Installment plans are function-navigated
 * (showProducts()), not options on #registrationType — case 5's first cut selected 'ProductDiv' there and failed
 * before testing anything (dashboard-kpi-drill.cy.js:50 and pos-barcode-default.cy.js:101 already warned).
 *
 * Holds are on reads the page does not wait for, so `waitForAppReady` is never called while one is open.
 *
 * Requires: the monolith rebuilt with grid-loading.js (case 0) — and case 11 with the a11y/translation polish.
 * Run headed, SOLO: two runs as the same user expire each other's session (maximumSessions(1)).
 */

const HOLD_MS = 4000

/** A held response: the real server answer, delivered late. */
const holdRead = (url, alias, ms) =>
  cy.intercept({ method: 'GET', url }, (req) => {
    req.on('response', (res) => { res.setDelay(ms == null ? HOLD_MS : ms) })
  }).as(alias)

/** The text a screen must never show while loading, in the language this suite runs in (English). */
const EMPTY_LIE = /no data|no matching records|no data found/i

const openCustomers = () => {
  cy.get('#registrationType').select('CustomerDiv', { force: true })
  cy.get('#CustomerDiv').should('be.visible')
}

const openProducts = () => {
  cy.window().then((win) => win.showProducts())
  cy.get('#ProductDiv').should('be.visible')
}

describe('BLK-3 — placeholder rows while a grid loads', () => {
  beforeEach(() => {
    cy.loginAsOwner()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
  })

  it('0 — the served build carries grid-loading.js and its DataTables defaults', () => {
    // A stale monolith serves the old files under a correct-looking URL. Say so once, here.
    cy.window().its('GridLoading').should('be.an', 'object')
    cy.window().then((win) => {
      expect(win.jQuery.fn.dataTable.defaults.oLanguage.sLoadingRecords,
        'every DataTables grid loads on skeleton rows by default').to.contain('grid-skeleton')
    })
  })

  it('⭐⭐ 1 — a module grid shows skeleton rows while its read is held, then real rows', () => {
    holdRead('**/getUserCustomer*', 'grid')
    openCustomers()

    // Inside the held window.
    cy.get('#tableCustomer .grid-skeleton', { timeout: HOLD_MS - 500 }).should('be.visible')
    cy.get('#tableCustomer tbody').invoke('text').should('not.match', EMPTY_LIE)
    // The existing specs' convention: a placeholder is ONE row with ONE cell, so it is never read as data.
    cy.get('#tableCustomer tbody tr').should('have.length', 1)
    cy.get('#tableCustomer tbody tr').first().find('td').should('have.length', 1)
    // It holds the grid's height instead of collapsing to one text line.
    cy.get('#tableCustomer tbody').invoke('outerHeight').should('be.greaterThan', 80)

    cy.wait('@grid', { timeout: 20000 })
    cy.get('#tableCustomer .grid-skeleton').should('not.exist')
    cy.get('#tableCustomer tbody tr').first().find('td').should('have.length.greaterThan', 1)
  })

  it('⭐⭐ 2 — toggling a column mid-load keeps the skeleton (the draw that used to print "No data available")', () => {
    holdRead('**/getUserCustomer*', 'grid')
    openCustomers()
    cy.get('#tableCustomer .grid-skeleton', { timeout: HOLD_MS - 500 }).should('be.visible')

    // A second draw while the read is open — DataTables' own counter would now choose the "empty" text.
    cy.get('#CustomerDiv a.toggle-vis[data-column="3"]').click()
    cy.get('#tableCustomer .grid-skeleton').should('be.visible')
    cy.get('#tableCustomer tbody').invoke('text').should('not.match', EMPTY_LIE)

    cy.wait('@grid', { timeout: 20000 })
    cy.get('#tableCustomer .grid-skeleton').should('not.exist')
    // Put the column back; column choices are not persisted, but the next case must start from the default.
    cy.get('#CustomerDiv a.toggle-vis[data-column="3"]').click()
  })

  it('⭐ 3 — an EMPTY answer shows the translated empty text in THIS grid, and no skeleton', () => {
    cy.intercept({ method: 'GET', url: '**/getUserCustomer*' },
      { statusCode: 200, body: { status: 'SUCCESS', collection: [] } }).as('empty')
    openCustomers()
    cy.wait('@empty')

    cy.window().then((win) => {
      const expected = win.t('ui.js.noDataYet')
      cy.get('#tableCustomer td.dataTables_empty').should('have.text', expected)
    })
    cy.get('#tableCustomer .grid-skeleton').should('not.exist')
    // The retired hack wrote this into the first empty cell on the PAGE, in English.
    cy.contains('No Data Found').should('not.exist')
  })

  it('⭐ 4 — a FAILED read says so, rather than showing a skeleton for ever', () => {
    cy.on('window:alert', () => true)   // DataTables' own "Ajax error" warning, if it raises one
    cy.intercept({ method: 'GET', url: '**/getUserCustomer*' }, { statusCode: 500, body: 'boom' }).as('fail')
    openCustomers()
    cy.wait('@fail')

    cy.window().then((win) => {
      const expected = win.t('ui.js.anErrorOccurredPleaseTryAgain')
      cy.get('#tableCustomer td.dataTables_empty', { timeout: 10000 }).should('have.text', expected)
    })
    cy.get('#tableCustomer .grid-skeleton').should('not.exist')
  })

  it('⭐⭐ 5 — the server-paged Product grid shows skeleton rows on its first load, then real rows', () => {
    holdRead('**/getProductPage*', 'page')
    openProducts()

    cy.get('#tableProduct .grid-skeleton', { timeout: HOLD_MS - 500 }).should('be.visible')
    cy.get('#tableProduct tbody').invoke('text').should('not.match', EMPTY_LIE)

    cy.wait('@page', { timeout: 20000 })
    cy.get('#tableProduct .grid-skeleton').should('not.exist')
    cy.get('#tableProduct tbody tr').first().find('td').should('have.length.greaterThan', 1)
  })

  it('⭐ 6 — a plain table (Stores) shows skeleton rows while re-fetching, never the previous answer', () => {
    holdRead('**/getStores*', 'stores', 3000)
    cy.window().then((win) => { win.showStores() })
    cy.get('#StoresDiv').should('be.visible')

    cy.get('#tableStores .grid-skeleton', { timeout: 2500 }).should('be.visible')
    cy.get('#tableStores tbody tr').should('have.length', 1)

    cy.wait('@stores', { timeout: 20000 })
    cy.get('#tableStores .grid-skeleton').should('not.exist')
    cy.get('#tableStores tbody tr').should('have.length.greaterThan', 0)
  })

  it('⭐⭐ 7 — Product SEARCH: after a zero-result search, the next search shows skeleton rows — not the stale "No data yet"', () => {
    /*
     * The design's "Search products" row. A server-paged grid keeps its current page while a new page loads —
     * correct when it is showing data. The lie is the OTHER state: the grid shows the previous search's empty
     * answer, the operator types a new term, and until it answers the screen still says there is nothing.
     */
    cy.intercept('GET', '**/getProductPage*').as('first')
    openProducts()
    cy.wait('@first', { timeout: 20000 })
    cy.get('#tableProduct tbody tr').first().find('td').should('have.length.greaterThan', 1)

    const none = 'zzqBLK3none' + Date.now()
    cy.intercept('GET', '**/getProductPage*').as('zero')
    cy.get('#tableProduct_filter input').clear().type(none)
    cy.wait('@zero', { timeout: 20000 })
    cy.window().then((win) => {
      cy.get('#tableProduct td.dataTables_empty').should('have.text', win.t('ui.js.noDataYet'))
    })

    // Now hold the NEXT search, and clear the term so the whole catalogue comes back.
    holdRead('**/getProductPage*', 'again')
    cy.get('#tableProduct_filter input').clear()
    cy.get('#tableProduct .grid-skeleton', { timeout: HOLD_MS - 500 }).should('be.visible')
    cy.get('#tableProduct tbody').invoke('text').should('not.match', EMPTY_LIE)

    cy.wait('@again', { timeout: 20000 })
    cy.get('#tableProduct .grid-skeleton').should('not.exist')
    cy.get('#tableProduct tbody tr').first().find('td').should('have.length.greaterThan', 1)
  })

  it('⭐ 8 — a plain table (Team) shows skeleton rows while loading', () => {
    holdRead('**/team/users*', 'team', 3000)
    cy.window().then((win) => { win.showTeam() })
    cy.get('#TeamDiv').should('be.visible')

    cy.get('#tableTeam .grid-skeleton', { timeout: 10000 }).should('be.visible')
    cy.get('#tableTeam tbody tr').should('have.length', 1)

    cy.wait('@team', { timeout: 20000 })
    cy.get('#tableTeam .grid-skeleton').should('not.exist')
    cy.get('#tableTeam tbody tr').should('have.length.greaterThan', 0)
  })

  it('⭐ 9 — a plain table (Tax codes) shows skeleton rows while loading', () => {
    holdRead('**/catalogTaxCodes*', 'tax', 3000)
    cy.window().then((win) => { win.showTaxSettings() })
    cy.get('#TaxSettingDiv').should('be.visible')

    cy.get('#taxCodeTable .grid-skeleton', { timeout: 2500 }).should('be.visible')
    cy.get('#taxCodeTable tbody tr').should('have.length', 1)

    cy.wait('@tax', { timeout: 20000 })
    cy.get('#taxCodeTable .grid-skeleton').should('not.exist')
    cy.get('#taxCodeTable tbody tr').should('have.length.greaterThan', 0)
  })

  it('⭐ 11 — the skeleton is ANNOUNCED to a screen reader, and the processing box is translated (needs the polish rebuild)', () => {
    cy.window().then((win) => {
      expect(win.jQuery.fn.dataTable.defaults.oLanguage.sProcessing,
        'the Product grid\'s floating box is no longer English "Processing..."').to.eq(win.t('ui.js.loading'))
    })
    holdRead('**/getUserCustomer*', 'grid')
    openCustomers()
    cy.window().then((win) => {
      // Real (visually hidden) text in the status region: an aria-label on an empty role=status is not read.
      cy.get('#tableCustomer .grid-skeleton[role="status"] .sr-only', { timeout: HOLD_MS - 500 })
        .should('have.text', win.t('ui.js.loading'))
    })
    cy.wait('@grid', { timeout: 20000 })
  })
})

describe('BLK-3 — the same hook on another dashboard (education)', () => {
  beforeEach(() => {
    cy.loginAsEduOwner()
    cy.visit('/educationDashboard')
    cy.waitForAppReady()
  })

  it('⭐⭐ 10 — the Students grid shows skeleton rows while held, then its answer (rows or the empty text), never a skeleton for ever', () => {
    // EXACTLY the grid read. `getUserStudents` (dropdowns) and `getUserStudentMap` share the prefix and are not it.
    holdRead(/\/getUserStudent(\?|$)/, 'students')
    cy.get('#registrationType').select('StudentDiv', { force: true })
    cy.get('#StudentDiv').should('be.visible')

    cy.get('#tableStudent .grid-skeleton', { timeout: HOLD_MS - 500 }).should('be.visible')
    cy.get('#tableStudent tbody').invoke('text').should('not.match', EMPTY_LIE)

    cy.wait('@students', { timeout: 20000 })
    // Education's success handler returns early when there is no collection — settled by grid-loading.js.
    cy.get('#tableStudent .grid-skeleton', { timeout: 10000 }).should('not.exist')
    cy.get('#tableStudent tbody tr').should('have.length.greaterThan', 0)
  })
})
