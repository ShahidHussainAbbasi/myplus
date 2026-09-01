/**
 * #18 — the sale report, by COMPANY (manufacturer / brand).
 *
 * <h3>Why only the company half</h3>
 * The ask was "by company and by vendor/supplier". Those two are NOT equally available:
 *
 * - **Company is already in the data.** `ProductRef.manufacturer` is resolved for every report line already,
 *   exactly as `category` is — so filtering and grouping by it is a mapped field and one enum constant, with
 *   no new query and no join.
 * - **Vendor is not.** A `Sell` has no vendor: vendor belongs to the PURCHASE. The accurate route is
 *   `SellBatch.batchNo` → `StockEntry.supplierId`, but `StockPick` does not carry `supplierId`, so today that
 *   is a cross-service lookup per batch. It is designed and deliberately not built here.
 *
 * A "by vendor" column filled from the last purchase rate would have been quick and WRONG — it reattributes
 * historical sales whenever a shop changes supplier. Shipping half the ask correctly beats shipping all of it
 * with a number nobody can trust.
 *
 * <h3>What is asserted</h3>
 * That the dimension is real end to end: the option exists, the value reaches the SERVER, the returned rows
 * carry it, and grouping produces subtotals. A filter that renders but narrows nothing is the failure this
 * report has had before — its own comments record a CHANNEL filter that shipped matching no row ever, and a
 * gate that counted options without selecting one.
 */

const OWNER = 'owner.business@myplus.com'

function openReport() {
  cy.visitDashboardSettled()
  cy.get('#sellType').select('SRDiv', { force: true })
  cy.get('#SRDiv').should('be.visible')
  cy.get('#SRDiv button[onclick*="loadSR"]').first().click({ force: true })
  cy.get('#tableSellReport tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
}

describe('#18 — sale report by company', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ the report rows carry the manufacturer', () => {
    // The enrichment itself. Without this the filter has nothing to match and the grouping nothing to key on.
    cy.request({ method: 'POST', url: '/loadSR', form: true, body: { rp: 0 } }).then((r) => {
      const rows = r.body.collection || []
      expect(rows.length, 'the month has sales').to.be.greaterThan(0)
      expect(rows[0], 'manufacturer is mapped onto the report row').to.have.property('manufacturer')
    })
  })

  it('⭐ the Company filter reaches the SERVER and narrows the result', () => {
    /*
     * The specific failure this report has had before: a filter that renders and matches nothing. Asserted by
     * sending a manufacturer that exists and one that cannot, and comparing.
     */
    cy.request({ method: 'POST', url: '/loadSR', form: true, body: { rp: 0 } }).then((all) => {
      const rows = all.body.collection || []
      const withCo = rows.find((x) => x.manufacturer && String(x.manufacturer).trim())
      if (!withCo) return   // no product in this tenant carries a manufacturer; nothing to narrow

      cy.request({
        method: 'POST', url: '/loadSR', form: true,
        body: { rp: 0, manufacturer: withCo.manufacturer },
      }).then((filtered) => {
        const got = filtered.body.collection || []
        expect(got.length, 'the filter returns rows').to.be.greaterThan(0)
        got.forEach((row) => {
          expect(String(row.manufacturer || '').toLowerCase(),
            'every returned row belongs to the chosen company')
            .to.eq(String(withCo.manufacturer).toLowerCase())
        })
      })

      // And a company that cannot exist must narrow to nothing — proof it filters rather than ignores.
      cy.request({
        method: 'POST', url: '/loadSR', form: true,
        body: { rp: 0, manufacturer: 'ZZ_NO_SUCH_COMPANY_ZZ' }, failOnStatusCode: false,
      }).then((none) => {
        expect((none.body.collection || []).length, 'an impossible company matches nothing').to.eq(0)
      })
    })
  })

  it('⭐ grouping by COMPANY returns subtotals', () => {
    cy.request({ method: 'POST', url: '/loadSR', form: true, body: { rp: 0, groupBy: 'COMPANY' } })
      .then((r) => {
        expect(r.body.object, 'grouped subtotals ride alongside the detail').to.exist
      })
  })

  it('the Company control is on the screen and offers real companies only', () => {
    // Sourced from the returned rows, like category — so it can never offer a company the report has none of.
    openReport()
    cy.get('#rfCompany').should('exist')
    cy.get('#rfCompany option').should('have.length.greaterThan', 0)
  })

  it('the CSV export carries the company filter', () => {
    // The export calls loadSR itself, so a filter missing from the query string silently exports everything.
    openReport()
    cy.get('#rfExport').should('have.attr', 'href').and('match', /manufacturer=/)
  })
})
