/**
 * BLK-0 — the finance ledger write is reachable only from inside, and every payment says who took it.
 *
 * Design:  microservices/docs/blocking-ui-and-backend-guards-design.md §8 (§8.5 = as built)
 * Manual:  microservices/docs/manual-test-blk0-ledger-guard.md
 *
 * ── What this exists to catch ───────────────────────────────────────────────────────────────────
 * `POST /api/finance/payments` was routed publicly (`Path=/api/finance/**`) with no `@PreAuthorize`, no audit
 * trail and no `@Version`. So any holder of a valid JWT could write rows straight into the ledger: bypassing
 * AR/AP allocation, bypassing the idempotency that protects the real screens, leaving no record of who did it.
 * BLK-0 moved the write to `/internal/finance/payments`, which no gateway route matches.
 *
 * ⚠ TWO THINGS THIS IS **NOT** ABOUT:
 *  1. **Not a duplicate-payment defect.** The screens were always guarded — the client sends an idempotencyKey
 *     and holds a submit-lock, and the server replays the prior result for a repeat key. Cases 3–4 pin that,
 *     because a security fix that broke it would be far worse than the gap it closes.
 *  2. **Not exploitable from an ordinary browser session.** The web app keeps tokens server-side and the
 *     monolith proxies no route to /payments. It needs a JWT — i.e. an API client — so case 1 uses a Bearer
 *     token, as an attacker would.
 *
 * ── ⚠ The DEPLOY TRAP this gate is half of ────────────────────────────────────────────────────────
 * A service still on the OLD contract jar posts to the old path, gets 405, and `SubledgerService` SWALLOWS it
 * (a ledger hiccup must never refuse a customer's money): the receipt "succeeds", the balance moves, and the
 * ledger row is simply missing. So case 2 (business) and case 7 (education) read the LEDGER, never the
 * response. `education/fees-to-gl.cy.js` case 2 (Cash must move by the fee) catches the same thing from the
 * GL side.
 *
 * ── ⚠ Why every number here is READ BACK, not trusted ─────────────────────────────────────────────
 * The first draft of this spec could not have gone green on correct code, and one case could have gone green
 * on broken code:
 *  - it asserted `newDue` from a REPLAY, which carries none (`CustomerService.replayPayment`) → NaN;
 *  - on a customer who owed NOTHING, so no payment could be seen to move the due anyway;
 *  - it read `.collection` off `/getAuditLog`, which answers a RAW ARRAY → always empty;
 *  - its audit match had `|| 'payment'`, true of any vendor payment in the last 200 events;
 *  - its probe sent `direction: 'IN'`, not a PaymentDirection — an OPEN endpoint would have answered 400 on
 *    deserialisation and the case would have passed. The probe below is a VALID body, so only the closed
 *    path can refuse it.
 * Each case now asserts what the defect would break: a ledger row count, a due read back, an audit row keyed
 * by the receipt number.
 *
 * Requires: monolith, gateway, business, finance, audit (+ education for the last block). Run headed.
 */

const GW = 'http://localhost:8765'
const BIZ_OWNER = 'owner.business@myplus.com'
const EDU_OWNER = 'owner.education@myplus.com'
const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const stamp = () => Date.now().toString().slice(-8)

/**
 * A customer who OWES money — a credit sale — so a payment has something to move. The same shape
 * receive-payment.cy.js is green on; the positive assertion on the due is what makes it a fixture that
 * exercises the rule rather than one that merely exists.
 */
const seedDebtor = (tag) => {
  const name = `BLK0 ${tag} ${stamp()}`
  return cy.seedProduct({ name: `BLK0P ${tag} ${stamp()}`, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name, contact: '0300BLK0', paidAmount: 0, dueAmount: 100 },
        sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
        paidAmount: 0, dueAmount: 100, grandTotal: 100,
      }, failOnStatusCode: false,
    }).then((r) => expect(r.body.status, `credit sale: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))

    return cy.request('/getUserCustomer?q=-1').then((r) => {
      const mine = (r.body.collection || r.body.data || []).find((c) => c.name === name)
      expect(mine, `seeded debtor "${name}"`).to.exist
      const due = Number(mine.dueAmount || 0)
      expect(due, 'the credit sale left a due — without one no payment can be seen to move it').to.be.greaterThan(0)
      return { customerId: mine.customerId || mine.id, due }
    })
  })
}

const dueOf = (customerId) =>
  cy.request('/getUserCustomer?q=-1').then((r) => {
    const mine = (r.body.collection || r.body.data || [])
      .find((c) => c.customerId === customerId || c.id === customerId)
    expect(mine, `customer ${customerId} still listed`).to.exist
    return Number(mine.dueAmount || 0)
  })

/**
 * The finance LEDGER's rows for one reference — read through the gateway, because that is the book itself,
 * not a response from the service that wrote to it. `asOtherTenant` is used for the SAME tenant here: it is
 * a stateless Bearer read, so the browser session this spec runs in is left untouched.
 */
const ledgerRows = (customerId, reference) =>
  cy.asOtherTenant((auth) =>
    cy.request({
      url: `${GW}/api/finance/payments?partyType=CUSTOMER&partyId=${customerId}`,
      headers: auth, failOnStatusCode: false,
    }).then((r) => {
      expect(r.status, `ledger read: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
      const rows = parse(r.body)
      expect(rows, 'the ledger answers a list').to.be.an('array')
      return rows.filter((p) => p.reference === reference)
    }), BIZ_OWNER)

const receive = (customerId, amount, reference, idempotencyKey) =>
  cy.request({
    method: 'POST', url: '/receivePayment', form: true, failOnStatusCode: false,
    body: { customerId, amount, method: 'CASH', reference, idempotencyKey },
  }).then((r) => {
    expect(r.body.status, `receivePayment: ${JSON.stringify(r.body).slice(0, 300)}`).to.eq('SUCCESS')
    return r.body.object || {}
  })

describe('BLK-0 — the finance ledger write is internal-only and audited', () => {
  beforeEach(() => cy.loginAsOwner())

  it('⭐⭐ 1 — a token holder CANNOT write to the ledger directly, and nothing lands', () => {
    /*
     * Asserted as "not a success", not one specific status: the fix answers 405 (the path now carries only
     * GETs), but a future fix refusing with 403 would be equally correct, and a gate that pins 405 tests its
     * implementation rather than its requirement.
     */
    const ref = `BLK0-PROBE-${stamp()}`
    cy.asOtherTenant((auth) =>
      cy.request({
        method: 'POST', url: `${GW}/api/finance/payments`,
        headers: Object.assign({ 'Content-Type': 'application/json' }, auth),
        body: {
          partyType: 'CUSTOMER', partyId: 1, direction: 'RECEIPT',   // a VALID body — see the header
          amount: 999999, paidOn: '2026-01-01', reference: ref,
        },
        failOnStatusCode: false,
      }).then((r) => {
        expect(r.status, `⭐ a direct ledger write must be refused, got ${r.status}: ` +
          `${JSON.stringify(r.body).slice(0, 200)}`).to.be.oneOf([401, 403, 404, 405])
      }), BIZ_OWNER)

    // A status can mislead; the book cannot. Nothing may have been written under the probe's reference.
    ledgerRows(1, ref).then((rows) => expect(rows, '⭐ no ledger row for the probe').to.have.length(0))
  })

  it('⭐⭐ 2 — the REAL payment screen still reaches the ledger (the deploy-trap detector)', () => {
    seedDebtor('Pay').then(({ customerId, due }) => {
      const ref = `BLK0-OK-${stamp()}`
      receive(customerId, 10, ref, `blk0-ok-${stamp()}`).then((o) => {
        expect(o.receiptNo,
          '⭐ a receipt NUMBER — a null one means the ledger write failed and was swallowed').to.match(/^RCPT-/)
        ledgerRows(customerId, ref).then((rows) => {
          expect(rows, '⭐ exactly ONE ledger row — zero is the deploy trap (old contract → 405 → swallowed)')
            .to.have.length(1)
          expect(rows[0].receiptNo, 'the ledger row is the receipt the screen was given').to.eq(o.receiptNo)
          expect(Number(rows[0].amount)).to.eq(10)
        })
      })
      dueOf(customerId).then((d) => expect(d, 'and the due moved by the payment').to.be.closeTo(due - 10, 0.01))
    })
  })

  it('⭐ 3 — a retried payment returns the SAME receipt, not a second one', () => {
    seedDebtor('Retry').then(({ customerId }) => {
      const run = stamp()
      const ref = `BLK0-RETRY-${run}`
      const key = `blk0-retry-${run}`
      receive(customerId, 20, ref, key).then((first) => {
        expect(first.receiptNo, 'first submit issued a receipt').to.match(/^RCPT-/)
        receive(customerId, 20, ref, key).then((second) => {
          expect(second.replay, 'the retry is recognised as a replay').to.eq(true)
          expect(second.receiptNo, '⭐ the SAME receipt — a retry must never issue a second one')
            .to.eq(first.receiptNo)
        })
      })
    })
  })

  it('⭐ 4 — the MONEY moves exactly once, not just the receipt number', () => {
    /*
     * Case 3 trusts the receipt number. A replayed number over a doubled ledger row would pass it and still be a
     * double credit — so this counts the ledger's rows and reads the due back.
     */
    seedDebtor('Once').then(({ customerId, due }) => {
      const run = stamp()
      const ref = `BLK0-ONCE-${run}`
      const key = `blk0-once-${run}`
      receive(customerId, 40, ref, key)
      receive(customerId, 40, ref, key)
      ledgerRows(customerId, ref).then((rows) =>
        expect(rows, '⭐ ONE ledger row after two submits with one key').to.have.length(1))
      dueOf(customerId).then((d) =>
        expect(d, `⭐ the due moved by 40 once, not 80 (was ${due})`).to.be.closeTo(due - 40, 0.01))
    })
  })

  it('⭐ 5 — the payment is attributable: the audit row names who took it', () => {
    seedDebtor('Audit').then(({ customerId }) => {
      receive(customerId, 15, `BLK0-AUDIT-${stamp()}`, `blk0-audit-${stamp()}`).then((o) => {
        expect(o.receiptNo).to.match(/^RCPT-/)
        cy.findAudit((x) => x.action === 'RECEIPT' && x.entityRef === o.receiptNo && Number(x.amount) === 15,
          `RECEIPT ${o.receiptNo}`).then((row) => {
          expect(row.entityType, 'a customer receipt').to.eq('CUSTOMER')
          // Written by the PRODUCER, not by finance — the BLK-0b ruling (design §8.5.1).
          expect(row.sourceService).to.eq('business')
          expect(row.userId, 'WHO — the actor is stamped').to.not.be.null
          expect(row.actorEmail, '⭐ and named, so the trail survives the user row').to.eq(BIZ_OWNER)
        })
      })
    })
  })
})

describe('BLK-0b — a school fee receipt says who took the money', () => {
  /*
   * The one payer of the ledger that recorded nobody. Shaped like business-service's receipt
   * (RECEIPT + the party type), so one trail filter shows both verticals. FeeReceiptAuditTest pins the shape
   * on `mvn test`; this proves it is WIRED into addFc and actually reaches the owner's trail.
   */
  beforeEach(() => cy.loginAsEduOwner())

  const seedStudent = (en) =>
    cy.request({
      method: 'POST', url: '/addStudent', form: true, failOnStatusCode: false,
      body: { name: 'BLK0 ' + en, enrollNo: en, status: 'ACTIVE' },
    }).then((r) => expect(JSON.stringify(r.body), `addStudent ${en}`).to.match(/SUCCESS/))

  // Tender == due, deliberately: the LEDGER records min(tender, owed) — a surplus goes to credit, not the
  // ledger — so only an exact tender makes "the audit amount" and "the ledger amount" the same number.
  const collectFee = (en, amount) =>
    cy.request({
      method: 'POST', url: '/addFc', form: true, failOnStatusCode: false,
      body: {
        enrollNo: en, fee: amount, dueAmount: amount, feePaid: amount, dueBalance: 0,
        receivedIn: 'Cash', payee: 'CyGuardian', receivedBy: 'CyClerk',
      },
    }).then((r) => expect(parse(r.body).status, `addFc: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))

  it('⭐⭐ 7 — a fee receipt reaches the LEDGER (the education half of the deploy-trap detector)', () => {
    /*
     * education-service calls the same moved write through its OWN FinanceClientConfig. Left on the old
     * contract jar it would get 405 and SubledgerService would swallow it: the fee screen says SUCCESS, the
     * student's dues clear, and the ledger never hears of it. So read the ledger — the settlement runs inside
     * addFc's request, so the row exists by the time addFc answers; no polling.
     *
     * addFc returns no fee id, so the ledger is read by PARTY (the student) rather than by reference; a
     * freshly registered student has exactly one receipt or none.
     */
    const en = 'BLK0LDG' + Date.now()
    seedStudent(en)
    collectFee(en, 1500)

    cy.request('/getUserStudent').then((r) => {
      const st = ((parse(r.body) || {}).collection || []).find((s) => s.enrollNo === en)
      expect(st, `student ${en} is listed with an id`).to.exist

      cy.asOtherTenant((auth) =>
        cy.request({
          url: `${GW}/api/finance/payments?partyType=STUDENT&partyId=${st.id}`,
          headers: auth, failOnStatusCode: false,
        }).then((lr) => {
          expect(lr.status, `ledger read: ${JSON.stringify(lr.body).slice(0, 200)}`).to.eq(200)
          const rows = parse(lr.body)
          expect(rows, 'the ledger answers a list').to.be.an('array')
          expect(rows, '⭐ exactly ONE ledger receipt for the fee — zero is the deploy trap').to.have.length(1)
          expect(rows[0].direction).to.eq('RECEIPT')
          expect(Number(rows[0].amount), 'the settled amount').to.eq(1500)
        }), EDU_OWNER)
    })
  })

  it('⭐ 6 — a fee collection leaves a RECEIPT/STUDENT audit row naming the clerk', () => {
    const en = 'BLK0FEE' + Date.now()
    seedStudent(en)
    collectFee(en, 1500)

    cy.findAudit((x) => x.action === 'RECEIPT' && x.entityType === 'STUDENT'
                     && String(x.details || '').indexOf(`enrollNo=${en}`) >= 0,
      `fee RECEIPT for ${en}`).then((row) => {
      expect(row.sourceService).to.eq('education')
      expect(Number(row.amount), 'the full tender').to.eq(1500)
      expect(row.userId, 'WHO — the actor is stamped').to.not.be.null
      expect(row.actorEmail, '⭐ and named').to.eq(EDU_OWNER)
    })
  })
})
