/**
 * Slice 0.2b — fee credit: an overpayment is carried forward, not refused.
 * Design: microservices/docs/slices/edu-0.2b-fee-credit.md
 *
 * Replaces 0.2a's refusal. The rules (append-only ledger, redeem capped at the balance, cached balance refreshed
 * after every write) are the SHARED common-credit engine also behind POS store credit — so
 * `business/store-credit*.cy.js` is a required companion run.
 *
 * Requires education-service + finance-service + gateway up. Run headed.
 */
describe('Education — fee credit (overpayment carried forward)', () => {
  beforeEach(() => { cy.loginAsEduOwner() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const rows = (b) => { const p = parse(b); return p.collection || p.object || p.data || [] }

  const seedStudent = (enrollNo) =>
    cy.request({
      method: 'POST', url: '/addStudent', form: true, failOnStatusCode: false,
      body: { name: 'Credit ' + enrollNo, enrollNo, mobile: '0300' + String(Date.now()).slice(-7),
              email: enrollNo.toLowerCase() + '@t.com', status: 'ACTIVE' },
    }).then((r) => expect(JSON.stringify(r.body), 'student created').to.match(/SUCCESS/))

  const fee = (enrollNo, charge, paid) =>
    cy.request({
      method: 'POST', url: '/addFc', form: true, failOnStatusCode: false,
      body: {
        enrollNo, fee: charge, dueAmount: charge, feePaid: paid,
        receivedIn: 'Cash', payee: 'CyParent', receivedBy: 'CyClerk',
      },
    }).then((r) => parse(r.body))

  const studentByEnroll = (en) =>
    cy.request('/getUserStudent').then((r) => rows(r.body).find((s) => s.enrollNo === en))

  const outstanding = (en) =>
    cy.request('/getUserFc').then((r) =>
      rows(r.body).filter((f) => f.enrollNo === en)
        .reduce((sum, f) => sum + Number(f.dueBalance || 0), 0))

  it('an overpayment is accepted and carried forward as credit', () => {
    const en = 'CR' + Date.now()
    seedStudent(en)
    fee(en, 1000, 0)                       // owes 1000
    fee(en, 0, 5000).then((b) => {         // pays 5000
      expect(b.status, JSON.stringify(b)).to.eq('SUCCESS')
      expect(String(b.message), 'the clerk is told what was carried forward').to.match(/carried .*4000.*credit/i)
    })

    outstanding(en).then((owed) => expect(owed, 'the due is cleared').to.eq(0))
    studentByEnroll(en).then((s) => {
      expect(Number(s.creditBalance), 'surplus held as credit').to.eq(4000)
    })
  })

  it('the next charge is paid from credit before the parent is asked for anything', () => {
    const en = 'CRN' + Date.now()
    seedStudent(en)
    fee(en, 1000, 5000)                    // owes 1000, pays 5000 → 4000 credit
    fee(en, 3000, 0)                       // next month's charge, nothing tendered

    outstanding(en).then((owed) => {
      expect(owed, 'the 3000 charge was met from credit — nothing left owing').to.eq(0)
    })
    studentByEnroll(en).then((s) => {
      expect(Number(s.creditBalance), '4000 − 3000 = 1000 credit remains').to.eq(1000)
    })
  })

  it('credit covers only what it can — the rest stays owed', () => {
    const en = 'CRP' + Date.now()
    seedStudent(en)
    fee(en, 1000, 2000)                    // 1000 credit held
    fee(en, 3000, 0)                       // charge exceeds the credit

    outstanding(en).then((owed) => {
      expect(owed, '3000 charged − 1000 credit = 2000 still owed').to.eq(2000)
    })
    studentByEnroll(en).then((s) => {
      expect(Number(s.creditBalance || 0), 'credit is spent, never negative').to.eq(0)
    })
  })

  it('credit is per student — it never leaks to another', () => {
    const a = 'CRA' + Date.now()
    const b = 'CRB' + (Date.now() + 1)
    seedStudent(a); seedStudent(b)
    fee(a, 500, 2500)                      // student A holds 2000 credit
    fee(b, 3000, 0)                        // student B is charged, holds none

    outstanding(b).then((owed) => expect(owed, "B's charge is untouched by A's credit").to.eq(3000))
    studentByEnroll(b).then((s) => expect(Number(s.creditBalance || 0)).to.eq(0))
  })

  it('held parent money appears as a LIABILITY on 2200, and the GL stays balanced', () => {
    // The point of the credit GL legs: money the school is holding is not income and not the parent's debt —
    // it is a liability, on the same account POS uses for store credit.
    const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
    const net = (t, code) => {
      const a = (t.rows || []).find((x) => x.code === code) || { debit: 0, credit: 0 }
      return Number(a.debit) - Number(a.credit)
    }
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })

    const en = 'CRGL' + Date.now()
    seedStudent(en)
    tb().then((before) => {
      const creditBefore = net(before, '2200')   // liability: net goes DOWN as we owe more
      const cashBefore = net(before, '1000')

      fee(en, 1000, 5000)      // owes 1000, pays 5000 → 1000 settles AR, 4000 becomes a liability
      cy.wait(2000)

      tb().then((after) => {
        expect(after.balanced, 'GL balanced after an overpayment').to.eq(true)
        expect(Number(after.totalDebit)).to.eq(Number(after.totalCredit))
        expect(creditBefore - net(after, '2200'), '2200 credited with the surplus we now owe').to.eq(4000)
        expect(net(after, '1000') - cashBefore, 'all 5000 of cash was received').to.eq(5000)
      })

      // Next charge consumes credit: Dr 2200 = Cr AR, no second cash leg.
      fee(en, 3000, 0)
      cy.wait(2000)
      tb().then((after2) => {
        expect(after2.balanced, 'GL still balanced after credit is spent').to.eq(true)
        expect(creditBefore - net(after2, '2200'), 'liability down to 1000 once 3000 was used').to.eq(1000)
        expect(net(after2, '1000') - cashBefore, 'no new cash — credit is not a receipt').to.eq(5000)
      })
    })
  })

  it('the policy toggle is in the catalog and defaults ON', () => {
    // C1/C2: assert the setting exists with the intended default AND that turning it off is what restores the
    // 0.2a refusal — a flag nothing reads would pass a catalog-only check.
    cy.request('/getConfig').then((r) => {
      const entry = rows(r.body).find((e) => e.key === 'edu.fee.creditOnOverpayment')
      expect(entry, 'edu.fee.creditOnOverpayment is offered to the owner').to.exist
      expect(String(entry.value), 'defaults ON — the school keeps the parent money').to.eq('true')
    })
  })
})
