/**
 * F3a — General Ledger double-entry core. Seeds the chart of accounts, posts a BALANCED journal, verifies the
 * trial balance balances (Σdebit = Σcredit — the GL's self-check), and confirms an UNBALANCED journal is rejected.
 * Requires finance-service (GL) + gateway + monolith up. Run headed.
 */
describe('F3a — General Ledger (double-entry core)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)

  it('seeds CoA, posts a balanced journal, trial balance balances; rejects unbalanced', () => {
    // ensure the default chart of accounts
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false }).then((r) => {
      const accts = parse(r.body)
      expect(Array.isArray(accts), JSON.stringify(r.body)).to.be.true
      expect(accts.length, 'default chart of accounts seeded').to.be.greaterThan(5)
      const cash = accts.find((a) => a.code === '1000')
      const sales = accts.find((a) => a.code === '4000')
      expect(cash, 'Cash account').to.exist
      expect(sales, 'Sales account').to.exist

      // post a balanced journal: Dr Cash 100 / Cr Sales 100
      cy.request({
        method: 'POST', url: '/gl/journal', headers: { 'Content-Type': 'application/json' },
        body: { source: 'MANUAL', memo: 'CY test', lines: [{ accountId: cash.id, debit: 100 }, { accountId: sales.id, credit: 100 }] },
        failOnStatusCode: false,
      }).then((jr) => {
        const j = parse(jr.body)
        expect(j.entryId, 'balanced journal posted, entry id returned').to.exist
      })

      // trial balance must balance (invariant: total debits == total credits)
      cy.request('/gl/trialBalance').then((tr) => {
        const tb = parse(tr.body)
        expect(tb.balanced, 'trial balance balanced flag').to.eq(true)
        expect(Number(tb.totalDebit), 'ΣDr == ΣCr').to.eq(Number(tb.totalCredit))
        expect(Number(tb.totalDebit), 'has movement').to.be.greaterThan(0)
      })

      // an UNBALANCED journal (100 vs 90) must be rejected — no entry id
      cy.request({
        method: 'POST', url: '/gl/journal', headers: { 'Content-Type': 'application/json' },
        body: { lines: [{ accountId: cash.id, debit: 100 }, { accountId: sales.id, credit: 90 }] },
        failOnStatusCode: false,
      }).then((br) => {
        let b = {}
        try { b = parse(br.body) } catch (e) { /* non-JSON error body is also acceptable */ }
        expect(b.entryId, 'unbalanced journal must NOT post').to.not.exist
      })
    })
  })
})
