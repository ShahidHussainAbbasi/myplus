/**
 * Period close / lock. finance-service is the single source of truth: closing the books through a date freezes
 * everything dated on/before it — new sales dated today, plus edits/voids of documents in the closed period, are all
 * rejected until the books are reopened. business-service reads the lock (short per-org cache, default 15s TTL) and
 * gates its ops; the GL is the hard backstop. Requires finance + business + gateway up, and an ADMIN_PRIVILEGE user
 * (the demo business account). Run headed.
 *
 * NOTE the waits: the business guard caches the lock for ~15s to keep the finance read off the hot path, so the test
 * pauses just over the TTL after each lock change for the new state to take effect. That makes this a slow spec.
 */
describe('Period close / lock', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const today = new Date().toISOString().slice(0, 10)
  const TTL_WAIT = 16000 // just over the default 15s per-org lock cache TTL

  const setLock = (through) =>
    cy.request({ method: 'POST', url: '/gl/periodLock', form: true, body: through ? { lockedThrough: through } : {}, failOnStatusCode: false })
  const readLock = () => cy.request('/gl/periodLock').then((r) => (typeof r.body === 'string' ? JSON.parse(r.body) : r.body))

  const sellOnce = () =>
    cy.seedProduct({ name: 'PCP_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) =>
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'PCC_' + Date.now(), contact: '0300PC', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }))

  it('locks the books: rejects new sales + back-dated void, then reopening allows the void', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })

    // Start OPEN so the first sale succeeds and gives us an invoice to void later.
    setLock(null)
    cy.wait(TTL_WAIT)
    readLock().then((l) => expect(l.lockedThrough || null, 'starts open').to.be.null)

    sellOnce().then((r) => {
      expect(r.body.status, 'sale on an open period ' + JSON.stringify(r.body)).to.eq('SUCCESS')
      const invoiceNo = r.body.object

      // Close the books through TODAY.
      setLock(today).then((s) => {
        const body = typeof s.body === 'string' ? JSON.parse(s.body) : s.body
        expect(body.lockedThrough, 'lock saved ' + JSON.stringify(body)).to.eq(today)
      })
      cy.wait(TTL_WAIT)

      // A NEW sale dated today is now rejected with the period-closed reason.
      sellOnce().then((blocked) => {
        expect(blocked.body.status, 'new sale blocked ' + JSON.stringify(blocked.body)).to.eq('FAILED')
        expect(String(blocked.body.message).toLowerCase(), 'period-closed message').to.contain('closed')
      })

      // Voiding the earlier invoice (dated today, in the closed period) is rejected too.
      cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'PC void' }, failOnStatusCode: false })
        .then((v) => {
          expect(v.body.status, 'void blocked while closed ' + JSON.stringify(v.body)).to.eq('FAILED')
          expect(String(v.body.message).toLowerCase()).to.contain('closed')
        })

      // Reopen — the void is allowed again.
      setLock(null)
      cy.wait(TTL_WAIT)
      readLock().then((l) => expect(l.lockedThrough || null, 'reopened').to.be.null)
      cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'PC void after reopen' }, failOnStatusCode: false })
        .then((v) => expect(v.body.status, 'void after reopen ' + JSON.stringify(v.body)).to.eq('SUCCESS'))
    })
  })
})
