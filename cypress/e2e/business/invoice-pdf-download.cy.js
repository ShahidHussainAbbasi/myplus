/**
 * Download PDF — a batch of invoices arrives as ONE file.
 *
 * ── The defect this gates ───────────────────────────────────────────────────────────────────────
 * The Sale Detail Report fired one download per invoice, 400 ms apart. Its own comment said browsers
 * "throttle or silently drop a burst of simultaneous downloads" — and they do something worse and more
 * specific: Chrome and Edge treat the SECOND automatic download from a page as a permission decision,
 * show "allow multiple downloads?", and **silently drop every file after the first** if it is dismissed,
 * or if the origin was ever denied. A shopkeeper selecting twenty invoices got one file, or none, with no
 * error anywhere.
 *
 * That is the whole of the long-standing "Download PDF does nothing" report, and **no amount of spacing
 * could have fixed it, because the limit is on the COUNT rather than the rate.**
 *
 * ── ⚠ What was NOT wrong, established by probing before changing anything ──────────────────────
 * The recorded theory was that the ~2 MB pdfmake bundle failed to load silently. It does not:
 * `ensurePdfMake()` resolves, `pdfMake.vfs` is present, `createPdf()` builds a document, and `.download()`
 * writes a real 3,888-byte file. The library was never the problem — the browser's multiple-download rule was.
 *
 * ── ⚠ Why these assertions are NOT on a file on disk ────────────────────────────────────────────
 * They were, and both download cases timed out with an EMPTY `cypress/downloads` — including the
 * single-invoice case, which exercises `downloadInvoicePdf`, the long-standing path this slice did not touch
 * and which works in the product. A build where the batch fix was broken could not have failed that case.
 * So what the empty folder measured was the test browser's handling of a `blob:` download, not the product.
 *
 * Asserting there would have gated the harness. These cases assert the REAL pdfmake output instead: how many
 * documents were created, how many pages they carry, and that the bytes are a PDF. That is not a spy — a spy
 * on `downloadInvoicesPdf` would pass on a build that called it and produced nothing, which is exactly the
 * failure being fixed. This runs the library and reads what it built.
 */

const OWNER = 'owner.business@myplus.com'

describe('Download PDF — one file for a batch of invoices', () => {
  /** Real invoices this tenant owns. Read from the product, never hard-coded. */
  let invoices = []

  before(() => {
    cy.loginAsOwner(OWNER)
    /*
     * `/loadSR` is the Sale Detail Report itself: the same screen the Download PDF button lives on, reading
     * the same rows the operator selects. If it cannot name three invoices there is genuinely nothing to
     * download, and no assertion about downloading them would mean anything.
     */
    cy.request({
      method: 'POST', url: '/loadSR', form: true,
      body: { rp: '4', sd: '01-01-2020 00:00:00', ed: '31-12-2030 00:00:00' },
    }).then((r) => {
      expect(r.body.status, `the report answers: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq('SUCCESS')
      // Distinct, in report order: one invoice with three lines is ONE document, not three.
      invoices = [...new Set((r.body.collection || []).map((x) => x.invoiceNo).filter(Boolean))].slice(0, 3)
      expect(invoices, 'three real invoices to download').to.have.length(3)
    })
  })

  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  /**
   * Open the dashboard with pdfmake already loaded, and record every document the library is asked to build.
   * The real `createPdf` still runs — this observes it, it does not replace it.
   */
  const withRecorder = () => {
    cy.visit('/businessDashboard')
    return cy.window()
      .then((win) => cy.wrap(win.LazyExport.ensurePdfMake(), { timeout: 60000 }).then(() => win))
      .then((win) => {
        const built = []
        const real = win.pdfMake.createPdf.bind(win.pdfMake)
        win.pdfMake.createPdf = function (docDefinition) {
          built.push(docDefinition)
          return real(docDefinition)
        }
        win.__built = built
        return cy.wrap(win, { log: false })
      })
  }

  /** Pages in a pdfmake definition = 1 + every explicit page break before a block. */
  const pageCount = (def) =>
    1 + (def.content || []).filter((b) => b && b.pageBreak === 'before').length

  it('⭐ 1 — three invoices produce ONE document of three pages, not three documents', () => {
    /*
     * THE CASE. Three separate downloads is exactly what the browser refuses to do, so "one document" is not
     * a tidiness preference — it is the difference between the feature working and silently not.
     */
    withRecorder().then((win) => {
      expect(typeof win.downloadInvoicesPdf, 'the batch emitter is on the page').to.eq('function')
      win.downloadInvoicesPdf(invoices, null, 'invoices')

      cy.wrap(null, { timeout: 40000 }).should(() => {
        expect(win.__built.length, 'exactly ONE document is created for three invoices').to.eq(1)
      }).then(() => {
        const def = win.__built[0]
        expect(pageCount(def), 'one page per invoice').to.eq(3)
        expect(def.pageSize, 'a real page size').to.be.oneOf(['A4', 'A5'])
      })
    })
  })

  it('⭐ 2 — the document really is a PDF, not just a well-shaped definition', () => {
    /*
     * The page count above is read from the definition. This runs the library over that same definition and
     * checks the BYTES, so a build that produced a definition the library could not render cannot pass.
     */
    withRecorder().then((win) => {
      win.downloadInvoicesPdf(invoices, null, 'invoices')
      cy.wrap(null, { timeout: 40000 })
        .should(() => { expect(win.__built.length).to.eq(1) })
        .then(() => new Cypress.Promise((resolve) => {
          win.pdfMake.createPdf(win.__built[0]).getBuffer((buf) => resolve(buf))
        }))
        .then((buf) => {
          const bytes = new Uint8Array(buf)
          expect(bytes.length, 'a real PDF has bytes').to.be.greaterThan(1000)
          const head = String.fromCharCode.apply(null, bytes.subarray(0, 5))
          expect(head, 'it is actually a PDF').to.eq('%PDF-')
        })
    })
  })

  it('⭐ 3 — a single invoice still downloads on its own', () => {
    /*
     * The single-document path shares the batch's block builders now. Without this case, a refactor that
     * broke one document while the batch still worked would ship — and the single download is the one a
     * shopkeeper uses most.
     */
    withRecorder().then((win) => {
      expect(typeof win.downloadInvoicePdf, 'the single emitter is still there').to.eq('function')
      win.downloadInvoicePdf(invoices[0])
      cy.wrap(null, { timeout: 40000 }).should(() => {
        expect(win.__built.length, 'one invoice, one document').to.eq(1)
      }).then(() => {
        expect(pageCount(win.__built[0]), 'and one page — no stray page break').to.eq(1)
      })
    })
  })

  it('4 — an empty selection is refused with a message, not silence', () => {
    // The old failure was silence. A batch of nothing must say so rather than appear to work.
    cy.visit('/businessDashboard')
    cy.window().then((win) => {
      let said = null
      const original = win.showFormError
      win.showFormError = (m) => { said = m }
      win.downloadInvoicesPdf([], null, 'invoices')
      win.showFormError = original
      expect(said, 'an empty batch reports rather than doing nothing').to.be.a('string')
    })
  })
})
