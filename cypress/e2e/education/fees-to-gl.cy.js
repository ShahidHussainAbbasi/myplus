/**
 * Slice 0.1 — a school fee collection posts to the General Ledger.
 * Design: microservices/docs/slices/edu-0.1-fees-to-gl.md
 *
 * Before this slice, education-service wrote a FeeCollection row and stopped: a school's entire revenue was
 * invisible to the journal, trial balance, P&L and period close that finance-service already runs.
 *
 * NOTE — 0.2a moved education to ACCRUAL and RETIRED the FEE_COLLECTION posting rule this slice introduced.
 * A fee now posts in two legs: FEE_CHARGE (Dr 1100 AR = Cr 4100 Fee Income) and, when money is tendered, a
 * receipt (Dr Cash|Bank = Cr 1100 AR). These assertions still hold because each scenario charges AND pays in one
 * row, so the net movement on Cash and Fee Income is unchanged — but the mechanism is two events, not one.
 *
 * Asserts the SIGNED NET movement of each account rather than an absolute figure — the org is shared with other
 * specs, so an absolute "Fee Income = 5000" is brittle while "Fee Income moved by 5000" is not. Same convention
 * as cypress/e2e/business/gl-posting.cy.js.
 *
 * Requires education-service + finance-service + gateway up. Run headed.
 */
describe('Education — fee collection posts to the GL', () => {
  beforeEach(() => { cy.loginAsEduOwner() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
  const acct = (rows, code) => (rows || []).find((x) => x.code === code) || { debit: 0, credit: 0 }
  const net = (rows, code) => { const a = acct(rows, code); return Number(a.debit) - Number(a.credit) }

  // Slice 0.2a: a tendered payment settles against a STUDENT, so every scenario seeds one. This spec predates
  // that guard — it used invented enrolment numbers, which are now refused rather than silently unsettled.
  const seedStudent = (enrollNo) =>
    cy.request({
      method: 'POST', url: '/addStudent', form: true, failOnStatusCode: false,
      body: { name: 'GL ' + enrollNo, enrollNo, status: 'ACTIVE' },
    }).then((r) => expect(JSON.stringify(r.body), 'student created').to.match(/SUCCESS/))

  // The fee form posts as form params (the monolith proxy forwards them verbatim to education-service).
  // `/addFc` answers with a GenericResponse; Cypress auto-parses it when the content-type is JSON, so assert on
  // the parsed status and stringify for the failure message — String(obj) would just print "[object Object]".
  const collectFee = (enrollNo, amount, receivedIn) =>
    cy.request({
      method: 'POST', url: '/addFc', form: true, failOnStatusCode: false,
      body: {
        enrollNo: enrollNo,
        fee: amount, dueAmount: amount, feePaid: amount, dueBalance: 0,
        receivedIn: receivedIn || 'Cash',
        payee: 'CyGuardian', receivedBy: 'CyClerk',
      },
    }).then((r) => {
      const body = parse(r.body)
      const status = (body && body.status) || ''
      expect(status, `addFc: ${JSON.stringify(body)}`).to.eq('SUCCESS')
      return cy.wrap(body, { log: false })
    })

  it('account 4100 Fee Income exists (backfilled into an existing chart)', () => {
    // ensureDefaults is idempotent per account, so 4100 arrives for orgs seeded before it existed. Without this
    // the first posting would fail with "Account code not found".
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    cy.request('/gl/accounts').then((r) => {
      const codes = (parse(r.body).rows || parse(r.body) || []).map((a) => a.code)
      expect(codes, 'Fee Income is in the chart of accounts').to.include('4100')
    })
  })

  it('a cash fee collection posts Dr Cash / Cr Fee Income, and the GL stays balanced', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    tb().then((before) => {
      const feeIncomeBefore = net(before.rows, '4100')   // income is a credit account → net goes DOWN
      const cashBefore = net(before.rows, '1000')

      const en = 'CY' + Date.now()
      seedStudent(en)
      collectFee(en, 5000, 'Cash')   // asserts SUCCESS internally

      // Delivery is AFTER_COMMIT over HTTP — allow the journal a moment to land.
      cy.wait(1500)
      tb().then((after) => {
        expect(after.balanced, 'GL balanced after the fee').to.eq(true)
        expect(Number(after.totalDebit)).to.eq(Number(after.totalCredit))
        expect(net(after.rows, '1000') - cashBefore, 'Cash debited by the amount collected').to.eq(5000)
        expect(feeIncomeBefore - net(after.rows, '4100'), 'Fee Income credited by the same').to.eq(5000)
      })
    })
  })

  it('a cheque collection debits Bank (1010), not Cash — the Check→CHEQUE mapping works', () => {
    // Regression for a real trap: finance routes to Bank on startsWith("CHEQUE") but this module stores "Check".
    // Passing it through verbatim posts cheques to Cash — books that balance but are wrong.
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    tb().then((before) => {
      const bankBefore = net(before.rows, '1010')
      const cashBefore = net(before.rows, '1000')

      const enq = 'CYQ' + Date.now()
      seedStudent(enq)
      collectFee(enq, 2500, 'Check')
      cy.wait(1500)
      tb().then((after) => {
        expect(net(after.rows, '1010') - bankBefore, 'Bank debited').to.eq(2500)
        expect(net(after.rows, '1000') - cashBefore, 'Cash untouched').to.eq(0)
        expect(after.balanced).to.eq(true)
      })
    })
  })

  it('a zero payment is not an accounting event', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    tb().then((before) => {
      const feeIncomeBefore = net(before.rows, '4100')
      const enz = 'CYZ' + Date.now()
      seedStudent(enz)
      collectFee(enz, 0, 'Cash')
      cy.wait(1200)
      tb().then((after) => {
        expect(net(after.rows, '4100'), 'no journal for a zero collection').to.eq(feeIncomeBefore)
      })
    })
  })

  it("the school's income now appears on the P&L", () => {
    // The point of the whole slice: revenue that was previously invisible to the books.
    const enp = 'CYP' + Date.now()
    seedStudent(enp)
    collectFee(enp, 1500, 'Cash')
    cy.wait(1500)
    cy.request({ url: '/gl/pnl', failOnStatusCode: false }).then((r) => {
      expect(r.status).to.eq(200)
      const body = JSON.stringify(parse(r.body))
      expect(body, 'Fee Income is on the P&L').to.match(/4100|Fee Income/)
    })
  })
})
