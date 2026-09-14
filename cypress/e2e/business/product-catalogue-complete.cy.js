/**
 * PS-2 + PS-1f — a product list that stops early must never look complete.
 *
 * ── The defect ──────────────────────────────────────────────────────────────────────────────────
 * `/getUserProduct` asked catalog for `size=1000` and returned whatever came back. Past a thousand
 * products the rest were dropped with no error, no flag and no log line, and FIVE screens believed the
 * short list absolutely: barcode label sheets, stock-count sheets, the report product filter, and the two
 * bonus-scheme pickers. The same shape a third time in the grid, where "All" meant `ALL_ROWS = 1000` —
 * DataTables drew 1,000 rows under a label reading "of 1,632".
 *
 * ⚠ PS-1f is NOT cosmetic, and this is the case that says why: `exportAll()` switches the grid to "All"
 * before handing rows to Excel/PDF/Print — precisely so a 50-row page does not become a 50-row spreadsheet.
 * With the ceiling in place it produced exactly that file, only bigger. **A stock-take done from that
 * export would have been short.**
 *
 * ── What this gate refuses to accept ────────────────────────────────────────────────────────────
 * Not "the list is big enough". A cap at 5,000 would pass that and be the same bug. Every case here pins a
 * list against the server's OWN count of what exists, so the only way to go green is to actually return
 * everything — or to say out loud that you did not.
 */

describe('PS-2 + PS-1f — the catalogue is complete, or it says it is not', () => {
  beforeEach(() => cy.loginAsOwner())

  /**
   * The tenant's real product count, and a refusal to run on a tenant too small to prove anything.
   *
   * ⚠ Existence is not eligibility. On a 900-product tenant every case below passes without exercising a
   * single line of the fix, and a gate that goes green having tested nothing is worse than a red one.
   */
  const withBigTenant = (fn) => {
    cy.request('/productCount').then((r) => {
      const total = Number(r.body.count)
      expect(r.body.success, 'productCount answered').to.eq(true)
      if (total <= 1000) {
        throw new Error(
          `This gate proves a 1000-row cap is gone, so it needs a tenant with MORE than 1000 products. ` +
          `This one has ${total} — it would pass without testing anything. Seed more, or run it as a ` +
          `tenant that has them.`)
      }
      fn(total)
    })
  }

  it('⭐⭐ 1 — /getUserProduct returns EVERY product, not the first 1000', () => {
    withBigTenant((total) => {
      cy.request('/getUserProduct?includeInactive=true').then((r) => {
        const rows = r.body.collection || []

        // ⭐ The assertion that matters. Pinned to the server's count — "> 1000" would pass on a cap of 1500.
        expect(rows.length, `⭐ every one of the tenant's ${total} products`).to.eq(total)
        expect(rows.length, 'and specifically NOT the old 1000-row cap').to.not.eq(1000)

        // Ids must be distinct: concatenating pages wrongly (re-reading page 0 each time, which a
        // repeated-parameter collapse would cause) yields the right COUNT built from the wrong rows.
        const ids = new Set(rows.map((p) => String(p.id)))
        expect(ids.size, 'the rows are distinct products, not one page repeated').to.eq(rows.length)
      })
    })
  })

  it('⭐ 2 — the OLDEST products are present, not just the newest thousand', () => {
    /*
     * The cap used `sort=id,desc`, so the oldest were the ones that vanished — a shop's staples, added
     * first. An earlier fix had introduced that sort after NEW products started disappearing; it inverted
     * which rows were lost without stopping the loss. This case pins the end that is currently missing.
     */
    withBigTenant(() => {
      cy.request('/getProductPage?page=0&size=5&sort=id,asc&includeInactive=true').then((oldestResp) => {
        const oldest = (oldestResp.body.collection || []).map((p) => String(p.id))
        expect(oldest.length, 'a sample of the oldest products to look for').to.be.greaterThan(0)

        cy.request('/getUserProduct?includeInactive=true').then((r) => {
          const have = new Set((r.body.collection || []).map((p) => String(p.id)))
          const missing = oldest.filter((id) => !have.has(id))
          expect(missing, `⭐ the oldest products must be in the list; missing: ${missing.join(', ')}`)
            .to.have.length(0)
        })
      })
    })
  })

  it('⭐ 3 — the response says whether it is complete', () => {
    /*
     * ⭐ THE PRINCIPLE, not just the number. A ceiling still exists (20,000); what changed is that passing
     * it sets `truncated: true` and logs a warning naming the real count, so a caller can say so on screen.
     *
     * A cap nobody is told about is a lie, not a limit. This case fails if the flag disappears — which is
     * exactly what a future "simplification" would do to it.
     */
    withBigTenant((total) => {
      cy.request('/getUserProduct?includeInactive=true').then((r) => {
        expect(r.body, 'the response carries a completeness flag').to.have.property('truncated')
        expect(r.body.truncated, 'and this catalogue is well under the ceiling').to.eq(false)
        expect(Number(r.body.total), 'and reports the real total, so a caller can say "N of M"').to.eq(total)
      })
    })
  })

  it('⭐⭐ 4 — the grid\'s "All" draws every row — the case the EXPORT depends on', () => {
    /*
     * ⭐ exportAll() switches to "All" and hands DataTables' rows to Excel/PDF/Print. If "All" is capped,
     * the spreadsheet is short and nothing says so — the defect exportAll() was written to prevent, which
     * its own comment calls "the whole class of defect this codebase keeps paying for".
     *
     * Asserting the DRAWN ROW COUNT is the point: the label already said "of 1,632" while 1,000 rows were
     * on screen, so the label was never the thing that was wrong.
     */
    withBigTenant((total) => {
      cy.visitDashboardSettled()
      cy.waitForAppReady()
      cy.window().then((w) => w.showProducts())
      cy.get('#ProductDiv').should('be.visible')
      cy.get('#tableProduct tbody tr:first td', { timeout: 20000 }).should('have.length.greaterThan', 1)

      /*
       * ⚠ DRIVEN THROUGH THE DataTables API, and that is the FAITHFUL driver here, not a shortcut.
       *
       * The grid is built with `dom: 'Bfrtip'` — there is no length <select> on the page at all; page
       * length is a Buttons collection. More to the point, exportAll() itself does exactly this:
       *
       *     dt.page.len(-1).draw(false)
       *
       * before handing the rows to Excel/PDF/Print. Driving the same call is testing the export's real
       * path; clicking through the button collection would test DataTables' dropdown markup instead.
       */
      cy.window().then((w) => { w.jQuery('#tableProduct').DataTable().page.len(-1).draw(false) })

      // Paging through a big catalogue is several parallel requests — allow for it, then count the rows.
      cy.get('#tableProduct tbody tr', { timeout: 120000 })
        .should('have.length', total)

      cy.get('#tableProduct tbody tr').then(($rows) => {
        expect($rows.length, '⭐ "All" must mean all, not the old 1000 ceiling').to.not.eq(1000)
      })
    })
  })

  it('5 — an ordinary page is still ONE request of 50 rows', () => {
    /*
     * The guard on the fix. Making "All" complete must not turn every ordinary page into a full-catalogue
     * read — that would trade a correctness bug for the performance bug PS-1 just removed. 474 KB went to
     * 24 KB on this screen and it has to stay there.
     */
    let pageRequests = 0
    let biggestSize = 0
    cy.intercept('GET', '**/getProductPage*', (req) => {
      pageRequests++
      const size = Number(new URL(req.url).searchParams.get('size') || 0)
      if (size > biggestSize) biggestSize = size
      req.continue()
    })

    cy.visitDashboardSettled()
    cy.waitForAppReady()
    cy.then(() => { pageRequests = 0; biggestSize = 0 })
    cy.window().then((w) => w.showProducts())
    cy.get('#ProductDiv').should('be.visible')
    cy.get('#tableProduct tbody tr:first td', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.waitForAppReady()

    cy.then(() => {
      expect(pageRequests, 'the default view is a single page request').to.eq(1)
      expect(biggestSize, 'asking for 50 rows, not the catalogue').to.be.at.most(50)
    })
    cy.get('#tableProduct tbody tr').should('have.length', 50)
  })
})
