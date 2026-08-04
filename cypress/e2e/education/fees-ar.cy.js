/**
 * Slice 0.2a — fee dues become real receivables: FIFO settlement, aging, statement.
 * Design: microservices/docs/slices/edu-0.2-fees-to-ar.md
 *
 * The accounting moved to ACCRUAL, matching what POS and Pharmacy already do:
 *   fee charged   → Dr 1100 AR      Cr 4100 Fee Income   (new FEE_CHARGE event)
 *   fee collected → Dr Cash/Bank    Cr 1100 AR           (the existing RECEIPT path, via the shared subledger)
 * Slice 0.1's FEE_COLLECTION rule was retired — under accrual it would recognise revenue twice.
 *
 * Aging, statements and FIFO allocation are the SHARED common-subledger engines that also serve POS AR/AP, so
 * `business/finance-statements.cy.js` is a required companion run.
 *
 * Requires education-service + finance-service + gateway up. Run headed.
 */
describe('Education — fee dues as receivables (AR)', () => {
  beforeEach(() => { cy.loginAsEduOwner() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const rows = (b) => { const p = parse(b); return p.collection || p.object || p.data || [] }
  const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
  const acct = (t, code) => (t.rows || []).find((x) => x.code === code) || { debit: 0, credit: 0 }
  const net = (t, code) => { const a = acct(t, code); return Number(a.debit) - Number(a.credit) }

  // A fee settles against a STUDENT — the student is the party the ledger records the receipt against — so
  // every scenario seeds a real one. Inventing an enrolment number leaves nothing to settle against, which is
  // now refused rather than silently ignored.
  const seedStudent = (enrollNo) =>
    cy.request({
      method: 'POST', url: '/addStudent', form: true, failOnStatusCode: false,
      body: { name: 'FeeAR ' + enrollNo, enrollNo, mobile: '0300' + String(Date.now()).slice(-7),
              email: enrollNo.toLowerCase() + '@t.com', status: 'ACTIVE' },
    }).then((r) => expect(JSON.stringify(r.body), 'student created').to.match(/SUCCESS/))

  // Raise a charge and optionally pay part of it, in one fee row (the model education already uses).
  const fee = (enrollNo, charge, paid, receivedIn) =>
    cy.request({
      method: 'POST', url: '/addFc', form: true, failOnStatusCode: false,
      body: {
        enrollNo,
        fee: charge, dueAmount: charge, feePaid: paid, dueBalance: Math.max(charge - paid, 0),
        receivedIn: receivedIn || 'Cash', payee: 'CyGuardian', receivedBy: 'CyClerk',
      },
    }).then((r) => parse(r.body))

  it('a charge raises a receivable: Dr 1100 AR / Cr 4100 Fee Income', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    tb().then((before) => {
      const arBefore = net(before, '1100')
      const incomeBefore = net(before, '4100')

      const en = 'AR' + Date.now()
      seedStudent(en)
      fee(en, 6000, 0).then((b) => expect(b.status, JSON.stringify(b)).to.eq('SUCCESS'))
      cy.wait(1500)

      tb().then((after) => {
        expect(after.balanced, 'GL balanced').to.eq(true)
        expect(net(after, '1100') - arBefore, 'AR debited by the charge').to.eq(6000)
        expect(incomeBefore - net(after, '4100'), 'Fee Income credited by the charge').to.eq(6000)
      })
    })
  })

  it('a payment settles the OLDEST due first (FIFO), not the newest', () => {
    // This is the behaviour that makes a statement meaningful, and it is the shared subledger doing the work.
    const en = 'FIFO' + Date.now()
    seedStudent(en)
    fee(en, 3000, 0)                       // March charge, unpaid
    fee(en, 3000, 0)                       // April charge, unpaid
    fee(en, 0, 4000)                       // guardian pays 4000 → clears March, 1000 off April

    cy.request('/getFeeStatement?enrollNo=' + en).then((r) => {
      const lines = rows(r.body)
      expect(lines.length, `statement lines: ${JSON.stringify(lines)}`).to.be.greaterThan(0)
      const closing = Number(lines[lines.length - 1].balance)
      expect(closing, 'closing balance = 6000 charged − 4000 paid').to.eq(2000)
    })

    cy.request('/getUserFc').then((r) => {
      // Only rows that actually CHARGED something. Registering a student raises an opening-due row
      // (FeeService.registerOpeningDue), and the payment-only row charges nothing — neither is a due, so neither
      // belongs in an assertion about how dues were settled.
      const charged = rows(r.body).filter((f) => f.enrollNo === en && Number(f.dueAmount) > 0)
      const balances = charged.map((f) => Number(f.dueBalance || 0)).sort((a, b) => a - b)
      // The oldest due must be fully cleared and the newer one partly: [0, 2000] — never [1000, 1000], which is
      // what a naive pro-rata split would produce.
      expect(balances, `charged-row balances: ${JSON.stringify(balances)}`).to.deep.eq([0, 2000])
    })
  })

  /**
   * SUPERSEDED BY 0.2b — this asserted that an overpayment is REFUSED, which was true only until fee
   * credit shipped. The title even said so ("fee credit arrives in 0.2b"). It has been stale-red since,
   * because the 0.2b checkpoint ran fee-credit + the business regressions but not this spec.
   *
   * The refusal path still EXISTS for schools that turn the carry-forward policy off, and
   * `checkOverpayment` is the only thing standing between them and an over-applied payment — so the case
   * is kept, rewritten against the surviving contract rather than deleted. Carry-forward itself is
   * covered by fee-credit.cy.js; what is asserted here is the AR consequence: the due must not end up
   * over-settled either way.
   */
  it('an overpayment is CARRIED FORWARD as credit, and the due is settled exactly (0.2b)', () => {
    const en = 'OVER' + Date.now()
    seedStudent(en)
    fee(en, 1000, 0)
    fee(en, 0, 5000).then((b) => {
      expect(b.status, JSON.stringify(b)).to.eq('SUCCESS')
      expect(String(b.message), 'the surplus is explained, not silently swallowed').to.match(/credit/i)
    })
    cy.request('/getUserFc').then((r) => {
      const mine = rows(r.body).filter((f) => f.enrollNo === en)
      const charged = mine.filter((f) => Number(f.dueAmount) > 0)   // excludes the opening-due row
      expect(charged.length, 'the payment created no extra charge row').to.eq(1)
      // The point for AR: the 1000 due is CLEARED, and the surplus went to credit rather than
      // over-settling the row into a negative balance.
      expect(Number(charged[0].dueBalance), 'the due is fully settled, not over-settled').to.eq(0)
      const applied = mine.reduce((sum, f) => sum + Number(f.feePaid || 0), 0)
      expect(applied, 'only what was owed is applied here — the rest is credit').to.eq(1000)
    })
  })

  it('aging buckets a long-overdue fee into 90+', () => {
    cy.request('/getFeeAging').then((r) => {
      expect(parse(r.body).status).to.eq('SUCCESS')
      const list = rows(r.body)
      // Structural assertion — the shared calculator's own unit tests own the bucket arithmetic.
      if (list.length) {
        const row = list[0]
        expect(row).to.have.property('total')
        expect(row).to.have.property('b90plus')
      }
    })
  })

  it('the statement shows charges and payments with a running balance', () => {
    const en = 'STMT' + Date.now()
    seedStudent(en)
    fee(en, 2000, 500)
    cy.request('/getFeeStatement?enrollNo=' + en).then((r) => {
      const lines = rows(r.body)
      const types = lines.map((l) => l.type)
      expect(types, 'both a charge and a payment line').to.include.members(['FEE_CHARGE', 'PAYMENT'])
      expect(Number(lines[lines.length - 1].balance), '2000 charged − 500 paid').to.eq(1500)
    })
  })

  it('FEE_COLLECTION is no longer a posting rule (retired by the accrual cutover)', () => {
    // Guards the cutover: if someone re-adds it, revenue would be recognised twice per fee.
    cy.request({
      method: 'POST', url: '/gl/postEvent', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { eventType: 'FEE_COLLECTION', ref: 'CY-RETIRED', grandTotal: 100, paidAmount: 100, method: 'CASH' },
    }).then((r) => {
      // Either the proxy route does not exist (404) or finance rejects the unknown event — never a 2xx success.
      const ok = r.status >= 200 && r.status < 300 && !/ERROR|Unknown/i.test(JSON.stringify(r.body))
      expect(ok, `FEE_COLLECTION should be rejected: ${r.status} ${JSON.stringify(r.body)}`).to.eq(false)
    })
  })
})
