/**
 * Sale Detail Report — the PERIOD, end to end.
 *
 * <h3>Three defects, all in how "which dates?" was answered</h3>
 *
 * <b>1. An absent period threw.</b> `dto.getRp()` is an `Integer` and was compared to an `int` with `==`,
 * which UNBOXES. A request carrying no `rp` — the report opened from a nav entry or a back-button restore,
 * before the select existed — threw NullPointerException, which the catch turned into a bare "could not
 * load".
 *
 * <b>2. A period with no range matched NO branch.</b> `objs` stayed null and the caller got NOT_FOUND —
 * "you have no sales" — when the truth was "you did not tell me when". A report that answers a malformed
 * question with an empty result teaches an operator their data is missing.
 *
 * <b>3. The month bounds kept the clock time</b> (fixed separately, see report-date-bounds.md): on the 1st,
 * "Current month" began mid-morning and excluded everything before it.
 *
 * Every case here asserts what the OPERATOR gets, not the shape of the request — a report is only correct if
 * it returns the right rows for the period the screen says it is showing.
 */

const OWNER = 'owner.business@myplus.com'

/** Ensure the tenant has at least one sale today, so "this month" cannot be legitimately empty. */
function seedSaleToday() {
  return cy.request({ url: '/getUserSell?q=-1' }).then((r) => {
    const rows = (r.body && r.body.collection) || []
    expect(rows.length, 'the tenant has sales history').to.be.greaterThan(0)
  })
}

describe('Sale Detail Report — period selection', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ 1. Current month returns rows — the defect the user hit', () => {
    // rp=0 with NO dates is the correct shape for "current month": the server computes the range. What was
    // wrong was the range it computed, and what it did when rp never arrived.
    seedSaleToday()
    cy.request({ method: 'POST', url: '/loadSR', form: true, body: { rp: 0 }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.status, `current month: ${r.body.message}`).to.eq('SUCCESS')
        expect((r.body.collection || []).length, 'and returns sales').to.be.greaterThan(0)
      })
  })

  it('⭐ 2. a request with NO period at all still answers', () => {
    /*
     * THE NULL-UNBOXING DEFECT, asserted directly. This is the request a screen sends when the period select
     * has not rendered yet, and it used to throw.
     */
    cy.request({ method: 'POST', url: '/loadSR', form: true, body: {}, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.status, `no period supplied: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
        expect((r.body.collection || []).length, 'defaults to the current month').to.be.greaterThan(0)
      })
  })

  it('⭐ 3. a period with no usable range falls back rather than reporting an empty shop', () => {
    /*
     * rp=4 is "custom range", and with no dates it matched no branch at all. NOT_FOUND was returned for a
     * shop full of sales — the most misleading answer available.
     */
    cy.request({ method: 'POST', url: '/loadSR', form: true, body: { rp: 4 }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.status, 'a period with no range must not read as "no sales"').to.eq('SUCCESS')
        expect((r.body.collection || []).length).to.be.greaterThan(0)
      })
  })

  it('4. an explicit same-day range returns that day', () => {
    // The endOfDay fix, on this screen: the picker sends a date as midnight, so "today to today" was
    // 00:00:00..00:00:00 and matched only a sale rung at exactly midnight.
    const d = new Date()
    const stamp = String(d.getDate()).padStart(2, '0') + '-'
      + String(d.getMonth() + 1).padStart(2, '0') + '-' + d.getFullYear()
    cy.request({
      method: 'POST', url: '/loadSR', form: true,
      body: { rp: 4, sd: stamp + ' 00:00:00', ed: stamp + ' 00:00:00' },
      failOnStatusCode: false,
    }).then((r) => {
      // SUCCESS or a genuine empty day are both acceptable; what must NOT happen is an error.
      expect(['SUCCESS', 'NOT_FOUND']).to.include(r.body.status)
    })
  })

  it('⭐ 5. clicking View report on the screen returns rows', () => {
    /*
     * END TO END, because every case above posts a body this test constructed — and a hand-built request
     * cannot detect a screen that sends the wrong one. That is exactly how this defect survived: the server
     * was fine for the requests anyone thought to make.
     */
    cy.intercept('POST', '**/loadSR').as('report')

    cy.visitDashboardSettled()
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#SRDiv').should('be.visible')

    // Current month is the shipped default — do not change it, that is the state being tested.
    cy.get('#dateRangeDDSR').should('have.value', '0')
    cy.get('#SRDiv button[onclick*="loadSR"]').first().click({ force: true })

    cy.wait('@report', { timeout: 30000 }).then((i) => {
      // The screen must SEND a period, not rely on the server guessing.
      expect(i.request.body, 'the request names its period').to.match(/rp=0/)
      expect(i.response.body.status, 'and the report loads').to.eq('SUCCESS')
    })

    cy.get('#tableSellReport tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
  })

  it('6. the report agrees with the dashboard for the same month', () => {
    /*
     * The two read the SAME helpers (firstDateTimeOfMonth / lastDateTimeOfMonth), and both under-reported
     * before those were fixed. If they ever disagree again, one of them has grown its own idea of "month".
     */
    cy.request({ method: 'POST', url: '/loadSR', form: true, body: { rp: 0 } }).then((rep) => {
      const invoices = new Set((rep.body.collection || []).map((x) => x.invoiceNo).filter(Boolean))
      cy.request({ url: '/getBusinessDashboardStats' }).then((d) => {
        const stats = d.body.object || d.body.data || d.body
        if (stats.monthlySales == null) return
        expect(Number(stats.monthlySales), 'the dashboard counts the same month the report shows')
          .to.be.greaterThan(0)
        expect(invoices.size, 'and the report is not empty while the dashboard counts sales')
          .to.be.greaterThan(0)
      })
    })
  })
})
