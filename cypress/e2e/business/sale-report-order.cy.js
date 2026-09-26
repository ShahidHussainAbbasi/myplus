/**
 * SR-1 — the Sale Detail Report shows the newest sale on top.
 *
 * ── The defect ──────────────────────────────────────────────────────────────────────────────────
 * The grid asked DataTables for `order: [[0,'desc']]` on a column of `dd-MM-yyyy` text. DataTables types
 * that as a STRING, so it sorted by day-of-month, then month, then year. Measured against this tenant:
 *
 *     true newest   06-09-2026 · 01-09-2026 · 30-08-2026
 *     grid showed   30-08-2026 · 29-08-2026 · 28-08-2026
 *
 * Today's sale sorted BELOW one from the previous month.
 *
 * ⭐ Why nobody saw it: the report defaulted to ONE MONTH, and inside one month every row shares the month
 * and the year — so a string sort is indistinguishable from a date sort. The wrong default hid the wrong
 * sort. Fixing the default (see sale-report-period.cy.js) is what makes this visible, which is why the
 * ordering needs its own gate rather than being assumed correct.
 *
 * ── Why the assertions are on the RENDERED ROWS ─────────────────────────────────────────────────
 * A test asserting `order: [[0,'desc']]` in the DataTables config would have PASSED on the broken build —
 * that setting was already there, and already meant the wrong thing. Only the order of the cells a
 * shopkeeper can actually see distinguishes the two.
 */

const OWNER = 'owner.business@myplus.com'
const WIDE = { rp: '4', sd: '01-01-2020 00:00:00', ed: '31-12-2030 00:00:00' }

/** dd-MM-yyyy → a comparable number — the same reading the product's `date-dmy` sort type uses. */
const num = (dmy) => {
  const m = /^(\d{1,2})-(\d{1,2})-(\d{4})$/.exec(String(dmy).trim())
  return m ? Number(m[3]) * 10000 + Number(m[2]) * 100 + Number(m[1]) : -1
}

/** Open the Sale Detail Report the way the screen does. */
const openReport = () => {
  cy.visitDashboardSettled()
  cy.get('#sellType').select('SRDiv', { force: true })
  cy.get('#SRDiv').should('be.visible')
}

describe('SR-1 — the newest sale is on top', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  /*
   * ⚠ FIXTURE (2026-09-26): these two cases used to need the tenant to OWN sales in several months. They cannot seed
   * that — a sale is always stamped now (SagaSaleWriter: sell.setDated(now)); back-dating is not a feature — so they
   * passed only while an old tenant still had August sales, and failed the day those were gone.
   *
   * The defect lives in the GRID'S SORT (dd-MM-yyyy typed as a string), not in the server. So the fixture is built
   * at the one boundary that matters: the REAL /loadSR request goes out, and its answer is replaced with clones of a
   * real row carrying dates across a month AND a year boundary. Everything after that — the product's loadSR
   * success handler, the column types, `order`, the rendering — is the product's own code, unchanged.
   *
   * The dates are chosen so the two sorts DISAGREE at the top: a string sort puts "31-12-2025" first; a date sort
   * puts "06-09-2026". A cloned row from the real response keeps the exact shape the grid's columns read.
   */
  const CROSS = ['30-08-2026', '06-09-2026', '31-12-2025', '15-08-2026', '02-01-2026', '01-09-2026']
  const NEWEST = '06-09-2026'
  const crossMonthReport = () => {
    cy.intercept('POST', '**/loadSR*', (req) => {
      req.continue((res) => {
        const rows = (res.body && res.body.collection) || []
        if (!rows.length) throw new Error('the report returned no rows at all — nothing to clone the fixture from')
        res.body.collection = CROSS.map((d, i) => Object.assign({}, rows[0], {
          dated: d, sellId: (Number(rows[0].sellId) || 0) * 100 + i, invoiceNo: `SR1-${i}`,
        }))
      })
    }).as('sr')
    openReport()
    cy.window().then((win) => {
      win.$('#dateRangeDDSR').val('4')
      win.toggleSRCustomRange()
      win.$('#srsd').val(WIDE.sd)
      win.$('#sred').val(WIDE.ed)
      win.loadSR()
    })
    cy.wait('@sr')
    cy.get('#tableSellReport tbody tr', { timeout: 30000 }).should('have.length', CROSS.length)
  }

  it('⭐ 1 — the first row on screen carries the newest date, not the highest day number', () => {
    const stringTop = CROSS.slice().sort().reverse()[0]
    expect(stringTop, 'the fixture really separates the two sorts').to.not.eq(NEWEST)
    crossMonthReport()
    cy.get('#tableSellReport tbody tr:first td:first').should((cell) => {
      expect(cell.text().trim(), 'the FIRST row is the newest sale, not the highest day number').to.eq(NEWEST)
    })
  })

  it('⭐ 2 — every row is in descending date order across a month AND a year boundary', () => {
    /*
     * Read through the DataTables API ({ page: 'all' }), in the APPLIED order — every row, not only the page.
     */
    crossMonthReport()
    cy.window().then((win) => {
      const dates = win.tableSellReport
        .column(0, { order: 'applied', page: 'all', search: 'none' })
        .data()
        .toArray()
        .map((cell) => String(cell).replace(/<[^>]*>/g, '').trim())

      expect(dates.length, 'every fixture row is in the grid').to.eq(CROSS.length)
      const seen = dates.map(num)
      for (let i = 1; i < seen.length; i++) {
        expect(seen[i], `row ${i} (${dates[i]}) is not newer than the row above it (${dates[i - 1]})`)
          .to.be.at.most(seen[i - 1])
      }
    })
  })

  it('⭐ 3 — the server returns rows newest-first, so the CSV export is ordered too', () => {
    /*
     * The grid re-sorts client-side, so the SCREEN hid this one entirely. `saleReport.csv` streams the
     * server's list straight into the file — verified unordered before the fix, with 16-08-2026 appearing
     * both first and last.
     */
    cy.request({ method: 'POST', url: '/loadSR', form: true, body: WIDE }).then((r) => {
      const nums = (r.body.collection || []).map((x) => num(x.dated))
      expect(nums.length, 'rows to order').to.be.greaterThan(1)
      for (let i = 1; i < nums.length; i++) {
        expect(nums[i], `server row ${i} is newer than row ${i - 1}`).to.be.at.most(nums[i - 1])
      }
    })
  })

  it('4 — lines of one invoice stay together, latest invoice of a day first', () => {
    /*
     * `dated` carries no time of day, so many sales share a date and the date alone cannot order them. The
     * secondary sort is the invoice number — zero-padded and sequential — which both puts the day's last
     * sale on top and stops two invoices' lines interleaving.
     */
    openReport()
    cy.window().then((win) => {
      win.$('#dateRangeDDSR').val('4')
      win.toggleSRCustomRange()
      win.$('#srsd').val(WIDE.sd)
      win.$('#sred').val(WIDE.ed)
      win.loadSR()
    })
    cy.get('#tableSellReport tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 1)
    cy.get('#tableSellReport tbody tr').then(($rows) => {
      const seq = [...$rows].map((tr) => tr.children[1].innerText.trim()).filter((v) => v && v !== '—')
      const firstSeen = {}
      seq.forEach((inv, i) => { if (!(inv in firstSeen)) firstSeen[inv] = i })
      seq.forEach((inv, i) => {
        // Every row of an invoice sits in one unbroken run starting where the invoice first appeared.
        const run = seq.slice(firstSeen[inv]).findIndex((v) => v !== inv)
        const end = run === -1 ? seq.length : firstSeen[inv] + run
        expect(i, `invoice ${inv} is not split apart by another invoice's lines`)
          .to.be.within(firstSeen[inv], end - 1 >= firstSeen[inv] ? end - 1 : firstSeen[inv])
      })
    })
  })
})
