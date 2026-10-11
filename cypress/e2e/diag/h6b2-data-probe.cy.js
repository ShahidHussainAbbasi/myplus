/**
 * H6 B2 — READ-ONLY probe: which demo account has rows on each screen whose times moved to the shared formatter,
 * and what the raw time value looks like. Writes nothing on the server; results to cypress/results/h6b2-probe.json.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/diag/h6b2-data-probe.cy.js"
 */
describe('H6 B2 data probe (read-only)', () => {
  const out = []
  const firstTime = (o) => {
    if (!o || typeof o !== 'object') return null
    for (const k of Object.keys(o)) {
      if (/(At|dated|Time)$/.test(k) && o[k]) return { field: k, value: o[k] }
    }
    return null
  }
  const rowsOf = (b) => {
    if (!b) return []
    const cands = [b.data, b.collection, b.object, b.items, b.data && b.data.items, b.data && b.data.content, b.content, b]
    for (const c of cands) if (Array.isArray(c)) return c
    if (b.data && typeof b.data === 'object') return [b.data]
    return []
  }
  const probe = (who, url) => cy.request({ url, failOnStatusCode: false }).then((r) => {
    const rows = rowsOf(r.body)
    out.push({ who, url, status: r.status, rows: rows.length, sample: rows.length ? firstTime(rows[0]) : null })
  })

  after(() => cy.writeFile('cypress/results/h6b2-probe.json', out))

  it('business owner', () => {
    cy.loginAsBusiness()
    ;['/parkedSales', '/currentShift', '/installmentReminders', '/priceApprovals', '/getAuditLog', '/getSupportSessions',
      '/getDriverSettlements?size=15', '/getOrders'].forEach((u) => probe('business', u))
  })
  it('pharmacy owner', () => {
    cy.loginAsPharmaOwner()
    ;['/parkedSales', '/currentShift', '/installmentReminders', '/priceApprovals', '/getAuditLog', '/getSupportSessions'].forEach((u) => probe('pharma', u))
  })
  it('marketplace owner', () => {
    cy.loginAsMarketplaceOwner()
    ;['/getOrders', '/mkt/incomingOrders?size=50', '/mkt/myProposals?size=100'].forEach((u) => probe('marketplace', u))
  })
  it('platform operator', () => {
    cy.loginAsOperator()
    ;['/platform/mkt/cases?size=100', '/platform/organizations'].forEach((u) => probe('operator', u))
  })
})
