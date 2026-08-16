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
 *
 * ⚠️ WHY THE GLOBS CARRY `*` BEFORE `.js`
 * PERF-2 serves these files at content-hashed URLs (/jQExp/pdfmake.min-<md5>.js). A glob anchored on the
 * exact filename would stop matching the moment PERF-2 lands and every case here would fail for a reason
 * that has nothing to do with PERF-4. The patterns below match both shapes.
 */

const PDFMAKE = '**/pdfmake.min*.js'
const VFS = '**/vfs_fonts*.js'
const JSZIP = '**/jszip.min*.js'

describe('PERF-4 — lazy export libraries', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    // Counters, so we can assert "never fetched" and "fetched exactly once".
    // NB: alias ONLY via .as(). An earlier version also set `req.alias` inside a handler, which records
    // the same request under the alias twice and made a single fetch count as 2.
    cy.intercept('GET', PDFMAKE).as('pdfmake')
    cy.intercept('GET', VFS).as('vfs')
    cy.intercept('GET', JSZIP).as('jszip')
    // openSellSection() does its own cy.visit('/businessDashboard'), so calling cy.visit here as well
    // would load the dashboard TWICE and double every intercept count. One load only.
    // The dashboard renders one section at a time: #tableSellReport exists in the DOM from page load but
    // its section (#SRDiv) is display:none until navigated to, so its buttons are present and correctly
    // labelled yet not "visible" to Cypress.
    cy.openSellSection('SRDiv')
    cy.get('#tableSellReport', { timeout: 20000 }).should('exist')
  })

  it('does NOT fetch pdfmake / vfs_fonts / jszip on page load — this is the saving', () => {
    // POSITIVE CONTROL FIRST. "Nothing was fetched" also passes on a page that failed to load anything at
    // all, so prove the script pipeline is alive before claiming these three are absent from it.
    cy.window().should((win) => {
      expect(typeof win.jQuery, 'jQuery must have loaded — control for the absence assertions below')
        .to.eq('function')
      expect(win.$ && win.$.fn && typeof win.$.fn.dataTable, 'DataTables must have loaded — same control')
        .to.eq('function')
    })

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

  it('clicking PDF fetches pdfmake AND vfs_fonts, and leaves a usable font table', () => {
    cy.contains('#tableSellReport_wrapper .dt-button', 'PDF').click()

    cy.wait('@pdfmake', { timeout: 60000 }).its('response.statusCode').should('eq', 200)
    cy.wait('@vfs', { timeout: 60000 }).its('response.statusCode').should('eq', 200)

    // vfs_fonts assigns into pdfMake.vfs. If the two loaded out of order the object exists but the font
    // table is missing, and every generated PDF comes out with no glyphs — so assert the RESULT, not just
    // that both files arrived. (Two cy.waits on two different aliases do NOT prove ordering; this does.)
    cy.window().should((win) => {
      expect(win.pdfMake, 'pdfMake global').to.be.an('object')
      expect(win.pdfMake.vfs, 'pdfMake.vfs — font table from vfs_fonts.js').to.be.an('object')
      expect(Object.keys(win.pdfMake.vfs).length, 'font table must not be empty').to.be.greaterThan(0)
    })
  })

  it('the click actually PRODUCES a PDF — delegation to the built-in action really runs', () => {
    // The case above proves the LIBRARY arrived. That is the artefact. The property is that the button
    // still exports: lazyPdfButton() hands the caller's config to $.fn.dataTable.ext.buttons.pdfHtml5,
    // and if that merge is wrong the action throws, lazy-export swallows it into a uiAlert, and every
    // other assertion in this file still passes. Two independent checks close that hole.
    cy.contains('#tableSellReport_wrapper .dt-button', 'PDF').click()
    // Wait on the LIBRARY BEING THERE, not on a network request.
    //
    // PERF-2 serves hashed assets `immutable, max-age=31536000`, so once pdfmake/vfs_fonts have been
    // fetched in an earlier case the browser satisfies the next page load FROM CACHE and no request is
    // issued — `cy.wait('@vfs')` then times out after 60s on a page where the library loaded perfectly.
    // Caching working as designed is not a lazy-loading failure. The case above already proves the fetch
    // happens on a cold load; here the property is that the library is USABLE, and `getBuffer()` below is
    // the strongest proof that vfs_fonts arrived (without it pdfmake throws "Roboto-Regular.ttf not found").
    cy.window({ timeout: 60000 }).its('pdfMake').should('be.an', 'object')

    // 1 — pdfmake + its font table genuinely render bytes. Without vfs_fonts this throws
    //     "File 'Roboto-Regular.ttf' not found in virtual file system", so it is also the strongest
    //     available proof that the two files loaded in the right order.
    cy.window()
      .then((win) => new Cypress.Promise((resolve, reject) => {
        try {
          win.pdfMake.createPdf({ content: 'perf-4 gate' }).getBuffer(resolve)
        } catch (e) {
          reject(e)
        }
      }))
      .then((buf) => {
        const bytes = new Uint8Array(buf)
        const magic = String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3], bytes[4])
        expect(magic, 'generated file must start with the PDF magic number').to.eq('%PDF-')
        expect(bytes.length, `generated PDF byte length: ${bytes.length}`).to.be.greaterThan(1000)
      })

    // 2 — the BUTTON drives that same path. Library is memoised now, so the second click goes straight
    //     through the delegation. Spy (not stub): the real export still happens.
    cy.window().then((win) => {
      cy.spy(win.pdfMake, 'createPdf').as('createPdf')
    })
    cy.contains('#tableSellReport_wrapper .dt-button', 'PDF').click()
    cy.get('@createPdf', { timeout: 20000 }).should('have.been.called')
  })

  it('a second PDF click does NOT re-download the library', () => {
    cy.contains('#tableSellReport_wrapper .dt-button', 'PDF').click()
    // Not `cy.wait('@pdfmake')`: with PERF-2's `immutable` caching the first click may be satisfied from
    // the browser cache and issue NO request at all, which is the caching doing its job. Wait for the
    // library to exist instead.
    cy.window({ timeout: 60000 }).its('pdfMake').should('be.an', 'object')

    // Then assert the count does not GROW on a second click. Comparing against a captured baseline works
    // whether the first fetch went to the network (1) or came from cache (0) — a hard `have.length 1`
    // asserted the transport rather than the behaviour, and failed on a warm cache.
    cy.get('@pdfmake.all').then((before) => {
      const baseline = before.length
      cy.contains('#tableSellReport_wrapper .dt-button', 'PDF').click()
      // Give a second request the chance to appear before asserting it did not.
      cy.wait(1500)
      cy.get('@pdfmake.all').should('have.length', baseline)
    })

    // Corroborate with the BROWSER's own resource timeline, which is independent of Cypress's alias
    // bookkeeping. Two sources disagreeing would mean the count above is an artefact of the harness
    // rather than evidence about the app — which is exactly what an earlier double-alias bug caused.
    cy.window().then((win) => {
      const hits = win.performance
        .getEntriesByType('resource')
        .filter((r) => /pdfmake\.min[-.]/.test(r.name))
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
      // jspdf.min.js sets BOTH window.jspdf (outer UMD) and window.jsPDF (inner, `e.jsPDF=r`).
      // education.js calls `new jsPDF(...)`, so jsPDF is the one that matters — but assert both, or the
      // absence check passes vacuously against whichever name the bundle did not use.
      expect(win.jsPDF, 'window.jsPDF should be gone from the business dashboard').to.eq(undefined)
      expect(win.jspdf, 'window.jspdf should be gone from the business dashboard').to.eq(undefined)
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
    // The tag being present is the artefact; the library having actually executed is the property.
    // pwstrength.js taught this exact lesson — its tag was always there and the file never arrived.
    cy.window({ timeout: 20000 }).should((win) => {
      expect(typeof win.jsPDF, 'window.jsPDF must be a constructor — education.js does `new jsPDF(...)`')
        .to.eq('function')
    })
  })
})
