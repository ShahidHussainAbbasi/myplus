/**
 * PERF-4 gate — export libraries load on demand, not on every page load.
 *
 * Design: microservices/docs/perf4-lazy-export-design.md
 *
 * TWO SLICES, BOTH COVERED HERE
 *   4a — jspdf.min.js + jspdf.plugin.autotable.js (304KB raw / 88KB gz) were loaded by the business and
 *        agriculture dashboards and called by NOTHING. Removed there; education keeps them because
 *        education.js:1062 genuinely uses jsPDF.
 *   4b — pdfmake + vfs_fonts + jszip (2,121,616 raw / 932,627 gz) no longer load eagerly. They now arrive
 *        on the first click of the Excel/PDF export button.
 *
 * ⚠️ THE REGRESSION THIS GATE EXISTS TO CATCH
 * DataTables hides pdfHtml5/excelHtml5 entirely when their library is missing AT TABLE INIT — it does not
 * draw a disabled button. So the naive version of this change ships a dashboard whose export buttons have
 * silently vanished, while every "page loads fine" check stays green. Test 2 asserts the buttons are
 * VISIBLE before anything is clicked; it is the single most important case in this file.
 */

const PDFMAKE = '**/pdfmake.min.js'
const VFS = '**/vfs_fonts.js'
const JSZIP = '**/jszip.min.js'

describe('PERF-4 — lazy export libraries', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    // Counters, so we can assert "never fetched" and "fetched exactly once".
    // NB: alias ONLY via .as(). An earlier version also set `req.alias` inside a handler, which records
    // the same request under the alias twice and made a single fetch count as 2.
    cy.intercept('GET', PDFMAKE).as('pdfmake')
    cy.intercept('GET', VFS).as('vfs')
    cy.intercept('GET', JSZIP).as('jszip')
    cy.visit('/businessDashboard')
    // The dashboard renders one section at a time. #tableSellReport EXISTS in the DOM from page load,
    // but its section (#SRDiv) is display:none until navigated to — so its buttons are present and
    // correctly labelled yet not "visible" to Cypress. Open the section before asserting on them.
    cy.get('#tableSellReport', { timeout: 20000 }).should('exist')
    cy.openSellSection('SRDiv')
    cy.get('#SRDiv').should('be.visible')
  })

  it('does NOT fetch pdfmake / vfs_fonts / jszip on page load — this is the saving', () => {
    // The whole point of the slice: ~903KB gzipped that used to be on the critical path is not requested.
    cy.get('@pdfmake.all').should('have.length', 0)
    cy.get('@vfs.all').should('have.length', 0)
    cy.get('@jszip.all').should('have.length', 0)
    // ...and the globals they define are genuinely absent, not merely served from cache.
    cy.window().should((win) => {
      expect(win.pdfMake, 'window.pdfMake must be undefined before any export click').to.eq(undefined)
      expect(win.JSZip, 'window.JSZip must be undefined before any export click').to.eq(undefined)
    })
  })

  it('STILL RENDERS the PDF and Excel buttons — the DataTables availability trap', () => {
    // With the built-in buttons and no library present, DataTables omits them and this fails.
    cy.get('#tableSellReport_wrapper .dt-button').then(($btns) => {
      const labels = [...$btns].map((b) => b.textContent.trim())
      expect(labels, `buttons rendered: ${JSON.stringify(labels)}`).to.include('PDF')
      expect(labels, `buttons rendered: ${JSON.stringify(labels)}`).to.include('Excel')
    })
    cy.contains('#tableSellReport_wrapper .dt-button', 'PDF').should('be.visible')
    cy.contains('#tableSellReport_wrapper .dt-button', 'Excel').should('be.visible')
  })

  it('clicking PDF fetches pdfmake AND vfs_fonts, in that order', () => {
    cy.contains('#tableSellReport_wrapper .dt-button', 'PDF').click()

    cy.wait('@pdfmake', { timeout: 60000 }).its('response.statusCode').should('eq', 200)
    cy.wait('@vfs', { timeout: 60000 }).its('response.statusCode').should('eq', 200)

    // vfs_fonts assigns into pdfMake.vfs. If the two loaded out of order the object exists but the font
    // table is missing, and every generated PDF comes out with no glyphs — so assert the RESULT, not just
    // that both files arrived.
    cy.window().should((win) => {
      expect(win.pdfMake, 'pdfMake global').to.be.an('object')
      expect(win.pdfMake.vfs, 'pdfMake.vfs — font table from vfs_fonts.js').to.be.an('object')
      expect(Object.keys(win.pdfMake.vfs).length, 'font table must not be empty').to.be.greaterThan(0)
    })
  })

  it('a second PDF click does NOT re-download the library', () => {
    cy.contains('#tableSellReport_wrapper .dt-button', 'PDF').click()
    cy.wait('@pdfmake', { timeout: 60000 })
    cy.window().its('pdfMake').should('be.an', 'object')

    cy.contains('#tableSellReport_wrapper .dt-button', 'PDF').click()
    // Give a second request the chance to appear before asserting it did not.
    cy.wait(1500)
    cy.get('@pdfmake.all').should('have.length', 1)

    // Corroborate with the BROWSER's own resource timeline, which is independent of Cypress's alias
    // bookkeeping. Two sources disagreeing would mean the count above is an artefact of the harness
    // rather than evidence about the app — which is exactly what an earlier double-alias bug caused.
    cy.window().then((win) => {
      const hits = win.performance
        .getEntriesByType('resource')
        .filter((r) => /pdfmake\.min\.js/.test(r.name))
      expect(hits.length, `browser resource timeline entries for pdfmake: ${hits.length}`).to.eq(1)
    })
  })

  it('clicking Excel fetches jszip only — not the 900KB pdfmake pair', () => {
    cy.contains('#tableSellReport_wrapper .dt-button', 'Excel').click()
    cy.wait('@jszip', { timeout: 60000 }).its('response.statusCode').should('eq', 200)
    cy.window().its('JSZip').should('exist')
    // Excel must not drag pdfmake along — they are separately memoised for exactly this reason.
    cy.get('@pdfmake.all').should('have.length', 0)
  })

  it('CSV still exports with NO library fetched at all', () => {
    // csv/copy/print never needed a library. Proves the untouched paths stayed untouched.
    cy.contains('#tableSellReport_wrapper .dt-button', 'CSV').should('be.visible').click()
    cy.wait(1000)
    cy.get('@pdfmake.all').should('have.length', 0)
    cy.get('@jszip.all').should('have.length', 0)
  })
})

describe('PERF-4a — dead jsPDF removed from the dashboards that never called it', () => {
  const jsPdfTags = (doc) =>
    [...doc.querySelectorAll('script[src]')]
      .map((s) => s.getAttribute('src'))
      .filter((s) => /jspdf/i.test(s))

  it('business dashboard no longer loads jsPDF (nothing on it called jsPDF)', () => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#tableSellReport', { timeout: 20000 }).should('exist')   // no section nav needed: DOM-only check
    cy.document().then((doc) => {
      expect(jsPdfTags(doc), 'jsPDF script tags on businessDashboard').to.have.length(0)
    })
    cy.window().should((win) => {
      expect(win.jsPDF, 'window.jsPDF should be gone from the business dashboard').to.eq(undefined)
    })
  })

  it('education dashboard STILL loads jsPDF — education.js genuinely uses it', () => {
    // The other direction matters just as much: this slice must not have stripped a live dependency.
    // If the education login differs in your fixtures, this is the case to adjust — not to delete.
    cy.loginAsEducation()
    cy.visit('/educationDashboard')
    cy.document().then((doc) => {
      expect(jsPdfTags(doc), 'jsPDF must remain on educationDashboard').to.have.length(2)
    })
  })
})
