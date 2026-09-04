/**
 * OB-1 — what customers and suppliers owed BEFORE the shop started using MaxTheService.
 *
 * Design:   microservices/docs/slices/ob-1-opening-balances-design.md
 * Analysis: microservices/docs/slices/ob-1-opening-balances-analysis.md
 *
 * ── What this gates ─────────────────────────────────────────────────────────────────────────────
 * A shop that switches over on a Tuesday already had money owed to it on the Monday, and until now there
 * was nowhere to put it. `Customer.dueAmount` is DERIVED — recomputeDue() sums the invoice headers and
 * overwrites the column on every sale and every receipt — so a figure typed into it survives until that
 * customer's next transaction and then vanishes silently. An opening balance therefore has to be a
 * DOCUMENT, which is also the shape where the statement, the aging, the FIFO allocator and the credit
 * limit all already work.
 *
 * ── ⭐⭐ Case 1 is the headline, and it is a TRIAL BALANCE ────────────────────────────────────────
 * The whole slice rests on one claim: an opening balance is Dr AR / Cr Owner's Equity and touches nothing
 * else. If that is true the books survive a migration. If it is false they drift from day one, and the
 * screens all still look right — which is exactly how a shop "migrating" by back-dating invoices breaks
 * its own P&L without noticing.
 *
 * A case asserting only that the balance appeared would pass a build posting through 4000 Sales.
 *
 * ── ⭐⭐ Case 10 is the sharpest edge ─────────────────────────────────────────────────────────────
 * A PARTIALLY PAID opening document must REFUSE reversal, naming OB-4. A full reversal would unpick a
 * payment that has already been allocated and posted. The owner's ruling was explicit, and refusing is
 * the feature: OB-1 does the part it can do correctly rather than approximating the part it cannot.
 *
 * ── ⚠ This spec MOVES MONEY and sets a tenant-wide, LOCKING switch ──────────────────────────────
 * It posts to the general ledger of a real demo tenant and sets `business.cutoverDate`, which locks on
 * the first posting. `owner.lifecycle@` is used deliberately — the Test Book's sacrificial tenant — so a
 * locked cutover cannot strand `owner.business@`, which most other specs log in as.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/opening-balances.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (b) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]
  return []
}
const payload = (b) => (b && (b.object || b.data)) || null

/**
 * ⚠ ASSERTS. A settings helper that returns quietly on failure makes every case after it fail for a reason
 * that has nothing to do with the case — which is exactly how this spec's first run reported nine
 * "FAILED instead of SUCCESS" errors whose real cause was two lines earlier.
 */
const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => {
      // A LOCKED cutover is refused on purpose (case 8 asserts that), so this tolerates a refusal whose
      // message explains itself and fails only on the silent kind.
      const body = JSON.stringify(r.body)
      const ok = r.body && (r.body.success === true || r.body.status === 'SUCCESS')
      const explained = /lock|posted|recorded/i.test(body)
      expect(ok || explained, `saveBusinessConfig ${key}=${value}: ${body}`).to.eq(true)
      return r
    })

/**
 * The trial balance, as the finance reports read it.
 *
 * ⚠ It answers an OBJECT — `{rows:[...], totalDebit, totalCredit, balanced}` — not a bare array. The first
 * cut of this spec treated it as an array and died on `rows.reduce is not a function`, which said nothing
 * about the books either way. Verified against the live endpoint rather than assumed.
 *
 * `balanced` is the endpoint's own verdict and is what case 1 asserts: the report already answers the exact
 * question the slice turns on, so re-deriving it here would be a second implementation to disagree with.
 */
const trialBalance = () =>
  cy.request({ url: '/gl/trialBalance', failOnStatusCode: false })
    .then((r) => {
      const b = (typeof r.body === 'string') ? JSON.parse(r.body) : r.body
      expect(b, `trialBalance: ${JSON.stringify(r.body).slice(0, 200)}`).to.be.an('object')
      expect(b.rows, 'the trial balance carries its rows').to.be.an('array')
      return b
    })

const acct = (tb, code) => ((tb && tb.rows) || []).find((x) => x.code === code) || { debit: 0, credit: 0 }
/** The trial balance nets each account to ONE side, so assert the SIGNED movement, never an absolute. */
const net = (tb, code) => Number(acct(tb, code).debit) - Number(acct(tb, code).credit)

const AR = '1100', AP = '2000', EQUITY = '3000', SALES = '4000'

const postOpening = (body) =>
  cy.request({ method: 'POST', url: '/postOpeningBalance', form: true, failOnStatusCode: false, body })

const customerNamed = (name) =>
  cy.request('/getUserCustomer?q=-1').then((r) => list(r.body).find((c) => c.name === name))

/**
 * ⚠ FORM-encoded, not JSON. `/addCustomer` proxies `request.getParameterMap()`, so a JSON body is ignored
 * entirely and the server answers "name is required" for a request that plainly contains a name. The first
 * cut of this spec sent JSON and every downstream case failed on a message about the cutover date.
 *
 * Copied from b2b-account-hierarchy.cy.js, which already does this correctly — the convention existed.
 */
const seedCustomer = (name) =>
  cy.request({
    method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: { name, contact: '03' + String(Date.now()).slice(-9), customerType: 'RETAILER' },
  }).then((r) => {
    expect(r.body.status, `addCustomer ${name}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    return customerNamed(name).then((c) => {
      expect(c, `seeded customer ${name} is readable back`).to.be.an('object')
      return c.customerId || c.id
    })
  })

describe('OB-1 — opening balances at cutover', () => {
  const CUTOVER = 'business.cutoverDate'
  const LOCKED = 'business.cutoverLocked'
  // A date safely in the past and inside an open period. Stated, never defaulted — that is Q3's whole point.
  const CUTOVER_DATE = '2026-09-01'

  before(() => {
    /*
     * owner.lifecycle@ — the Test Book's sacrificial tenant. This spec LOCKS a tenant-wide cutover date on
     * its first posting, and a locked cutover on owner.business@ would sit under every other business spec
     * for the rest of the run.
     */
    cy.loginAs('owner.lifecycle@myplus.com', 'Demo@2025!', '/getBusinessDashboardStats')
  })

  beforeEach(() => cy.loginAs('owner.lifecycle@myplus.com', 'Demo@2025!', '/getBusinessDashboardStats'))

  // ── the rule before the feature ─────────────────────────────────────────────────────────────────

  it('⭐ 7 — posting with NO cutover date is refused, and the message names the setting', () => {
    /*
     * Ordered first because it must be provable BEFORE the date is set — once locked there is no way back
     * to this state within a run, and a case that can only run first is a case that has to run first.
     */
    setConfig(CUTOVER, '')
    postOpening({ customerId: 1, amount: 5000, reference: 'no cutover' }).then((r) => {
      const body = JSON.stringify(r.body)
      expect(r.body.status, `must be refused: ${body}`).to.not.eq('SUCCESS')
      expect(body, 'the refusal names the cutover date, so the operator knows what to do')
        .to.match(/cutover/i)
    })
  })

  // ── the headline ────────────────────────────────────────────────────────────────────────────────

  it('⭐⭐ 1 — the TRIAL BALANCE still balances after an opening balance is posted', () => {
    /*
     * THE CASE. Everything else could pass while the books quietly drift.
     */
    const run = uniq()
    setConfig(CUTOVER, CUTOVER_DATE)

    trialBalance().then((before) => {
      const dr0 = Number(before.totalDebit || 0)
      seedCustomer(`OB Buyer ${run}`).then((cid) => {
        postOpening({ customerId: cid, amount: 45000, reference: `notebook ${run}` })
          .then((r) => expect(r.body.status, `posted: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))

        /*
         * The GL is posted through the OUTBOX and delivered AFTER the business transaction commits, so the
         * journal lands a moment behind the document. Polled rather than slept on: a fixed wait is either
         * too short on a loaded machine or wasted on a fast one, and this spec has to be run repeatedly.
         *
         * cypress-wait-until is NOT installed in this project — verified, not assumed — so the retry is
         * written out. It fails on its own assertion after the last attempt rather than silently carrying
         * on with a stale reading.
         */
        const awaitPosting = (attempt) => trialBalance().then((tb) => {
          if (Number(tb.totalDebit || 0) > dr0 || attempt >= 10) return tb
          return cy.wait(1000).then(() => awaitPosting(attempt + 1))
        })
        awaitPosting(0)

        trialBalance().then((after) => {
          expect(after.balanced, `debits equal credits AFTER the migration: ${JSON.stringify(after)}`)
            .to.eq(true)
          expect(Number(after.totalDebit || 0), 'and something actually posted').to.be.greaterThan(dr0)
        })
      })
    })
  })

  it('⭐ 2 — it posts Dr 1100 AR / Cr 3000 Equity, and NOTHING to 4000 Sales', () => {
    /*
     * The damaging mistake this slice exists to prevent. Posting an opening balance through Sales books
     * last year's trade as this month's revenue and carries it into the tax register — which is exactly
     * what a shop does today when it "migrates" by back-dating invoices.
     */
    const run = uniq()
    setConfig(CUTOVER, CUTOVER_DATE)

    trialBalance().then((before) => {
      const ar0 = net(before, AR), eq0 = net(before, EQUITY), s0 = net(before, SALES)
      seedCustomer(`OB GL ${run}`).then((cid) => {
        postOpening({ customerId: cid, amount: 12000, reference: `gl ${run}` })
          .then((r) => expect(r.body.status).to.eq('SUCCESS'))

        trialBalance().then((after) => {
          expect(net(after, AR) - ar0, 'Accounts Receivable is DEBITED by the amount owed').to.eq(12000)
          expect(net(after, EQUITY) - eq0, "Owner's Equity is CREDITED by the same").to.eq(-12000)
          expect(net(after, SALES) - s0, 'and Sales is untouched — this is not revenue').to.eq(0)
        })
      })
    })
  })

  // ── the five things the DOCUMENT shape gives for free ───────────────────────────────────────────

  it('⭐ 3 — the amount appears in the customer balance', () => {
    const run = uniq()
    setConfig(CUTOVER, CUTOVER_DATE)
    seedCustomer(`OB Bal ${run}`).then((cid) => {
      postOpening({ customerId: cid, amount: 7500, reference: `bal ${run}` })
        .then((r) => expect(r.body.status).to.eq('SUCCESS'))
      customerNamed(`OB Bal ${run}`).then((c) => {
        expect(Number(c.dueAmount), 'recomputeDue sees the opening document').to.eq(7500)
      })
    })
  })

  it('⭐ 4 — and in the CREDIT EXPOSURE: an opening balance alone can breach the limit', () => {
    /*
     * Q4, which the document shape answers with no code: exposure sums Customer.dueAmount, and
     * recomputeDue derives that from the invoice headers. A build that stored the opening figure anywhere
     * else would let a shop sell on credit to a customer who already owes it a fortune.
     */
    const run = uniq()
    setConfig(CUTOVER, CUTOVER_DATE)
    setConfig('pos.sale.creditLimitPolicy', 'block')

    seedCustomer(`OB Credit ${run}`).then((cid) => {
      // FORM again — an edit posts the same way a create does. Asserted, so a silently ignored credit
      // limit cannot make this case pass for the wrong reason: with no limit set there is nothing to
      // breach, and the sale below would be allowed for a reason that has nothing to do with the opening
      // balance.
      cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { customerId: cid, name: `OB Credit ${run}`,
                contact: '03' + String(Date.now()).slice(-9), creditLimit: 10000 },
      }).then((r) => expect(r.body.status, `set a credit limit: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))
      postOpening({ customerId: cid, amount: 9000, reference: `credit ${run}` })
        .then((r) => expect(r.body.status).to.eq('SUCCESS'))

      // A small credit sale that only breaches the limit BECAUSE of the opening balance.
      cy.seedProduct({ name: `OBP_${run}`, sellingPrice: 5000, stock: 5 }).then(({ productId }) => {
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          body: {
            customer: { customerId: cid, name: `OB Credit ${run}` },
            sales: [{ productId, quantity: 1, sellRate: 5000, totalAmount: 5000, netAmount: 5000 }],
            paidAmount: 0, dueAmount: 5000, grandTotal: 5000, tenders: [],
          }, failOnStatusCode: false,
        }).then((r) => {
          const body = JSON.stringify(r.body)
          expect(r.body.status, `9,000 owed + 5,000 new against a 10,000 limit must be refused: ${body}`)
            .to.not.eq('SUCCESS')
          expect(body, 'and the refusal is about the credit limit').to.match(/credit limit|limit/i)
        })
      })
    })
    setConfig('pos.sale.creditLimitPolicy', 'warn')
  })

  it('⭐ 6 — a receipt allocates against it, and the balance falls', () => {
    // Without this the opening debt is visible and uncollectable, which is worse than not recording it.
    const run = uniq()
    setConfig(CUTOVER, CUTOVER_DATE)
    seedCustomer(`OB Pay ${run}`).then((cid) => {
      postOpening({ customerId: cid, amount: 20000, reference: `pay ${run}` })
        .then((r) => expect(r.body.status).to.eq('SUCCESS'))

      cy.request({
        method: 'POST', url: '/receivePayment', form: true, failOnStatusCode: false,
        body: { customerId: cid, amount: 8000, method: 'CASH', idempotencyKey: `obpay-${run}` },
      }).then((r) => expect(r.body.status, `receipt: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))

      customerNamed(`OB Pay ${run}`).then((c) => {
        expect(Number(c.dueAmount), '20,000 opening less an 8,000 receipt').to.eq(12000)
      })
    })
  })

  // ── the cutover lock ────────────────────────────────────────────────────────────────────────────

  it('⭐ 8 — the cutover date cannot be changed once a balance has been posted', () => {
    /*
     * Q3's lock. Changing it afterwards would re-date documents already sitting in the ledger, which is
     * what period close exists to prevent.
     */
    const run = uniq()
    setConfig(CUTOVER, CUTOVER_DATE)
    seedCustomer(`OB Lock ${run}`).then((cid) => {
      postOpening({ customerId: cid, amount: 1000, reference: `lock ${run}` })
        .then((r) => expect(r.body.status).to.eq('SUCCESS'))

      cy.request({ url: '/getBusinessConfig', failOnStatusCode: false }).then((r) => {
        const row = ((r.body && r.body.data) || []).find((x) => x && x.key === LOCKED)
        expect(row, 'the lock setting is registered').to.be.an('object')
        expect(String(row.value), 'posting locked the cutover date').to.eq('true')
      })

      setConfig(CUTOVER, '2026-01-01').then((r) => {
        const body = JSON.stringify(r.body)
        // Asserted on the REFUSAL and its REMEDY, not on words this spec guessed. The first cut matched
        // /lock|posted/ and failed against a message that was entirely correct — "recorded against the
        // current date … reverse the opening balances first" — which would have had somebody weaken a good
        // message to satisfy a test. What matters is that the write is refused and the operator is told how
        // to proceed.
        expect(r.body && r.body.success, `a locked cutover date must be refused: ${body}`).to.eq(false)
        expect(body, 'and the refusal says what to do about it').to.match(/revers/i)
      })
    })
  })

  // ── correction ──────────────────────────────────────────────────────────────────────────────────

  it('⭐ 9 — reversing an UNPAID opening balance returns the customer to zero', () => {
    const run = uniq()
    setConfig(CUTOVER, CUTOVER_DATE)
    seedCustomer(`OB Rev ${run}`).then((cid) => {
      postOpening({ customerId: cid, amount: 45000, reference: `wrong ${run}` }).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        const ref = (payload(r.body) || {}).invoiceNo
        expect(ref, 'the posted document has a number to reverse').to.be.a('string')

        cy.request({
          method: 'POST', url: '/reverseOpeningBalance', form: true, failOnStatusCode: false,
          body: { invoiceNo: ref, reason: 'entered 45,000, the notebook says 40,000' },
        }).then((rr) => expect(rr.body.status, `reversal: ${JSON.stringify(rr.body)}`).to.eq('SUCCESS'))

        customerNamed(`OB Rev ${run}`).then((c) => {
          expect(Number(c.dueAmount || 0), 'the reversed balance is gone').to.eq(0)
        })
      })
    })
  })

  it('⭐⭐ 10 — reversing a PARTIALLY PAID opening balance is REFUSED, naming what to use instead', () => {
    /*
     * THE SHARPEST EDGE. A full reversal would unpick a payment that has already been allocated and
     * posted to the ledger. The owner ruled that this case needs a controlled net adjustment (OB-4), and
     * refusing is the feature: OB-1 does the part it can do correctly rather than approximating the part
     * it cannot. A build that reversed anyway would leave a receipt allocated to a document that no
     * longer exists.
     */
    const run = uniq()
    setConfig(CUTOVER, CUTOVER_DATE)
    seedCustomer(`OB Part ${run}`).then((cid) => {
      postOpening({ customerId: cid, amount: 45000, reference: `part ${run}` }).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        const ref = (payload(r.body) || {}).invoiceNo

        cy.request({
          method: 'POST', url: '/receivePayment', form: true, failOnStatusCode: false,
          body: { customerId: cid, amount: 15000, method: 'CASH', idempotencyKey: `obpart-${run}` },
        }).then((pr) => expect(pr.body.status).to.eq('SUCCESS'))

        cy.request({
          method: 'POST', url: '/reverseOpeningBalance', form: true, failOnStatusCode: false,
          body: { invoiceNo: ref, reason: 'trying to reverse a part-paid document' },
        }).then((rr) => {
          const body = JSON.stringify(rr.body)
          expect(rr.body.status, `a part-paid reversal must be refused: ${body}`).to.not.eq('SUCCESS')
          expect(body, 'and the refusal must say a payment is already allocated')
            .to.match(/paid|payment|allocat/i)
        })

        // And the money is untouched by the refusal — 45,000 less the 15,000 receipt.
        customerNamed(`OB Part ${run}`).then((c) => {
          expect(Number(c.dueAmount), 'the refused reversal changed nothing').to.eq(30000)
        })
      })
    })
  })

  // ── idempotency ─────────────────────────────────────────────────────────────────────────────────

  it('⭐ 11 — posting the same balance twice returns the FIRST document, not a second one', () => {
    // Control 4. A double-click or a timed-out retry must not double a shop's opening receivables.
    const run = uniq()
    const key = `ob-idem-${run}`
    setConfig(CUTOVER, CUTOVER_DATE)

    seedCustomer(`OB Idem ${run}`).then((cid) => {
      postOpening({ customerId: cid, amount: 6000, reference: `idem ${run}`, idempotencyKey: key })
        .then((first) => {
          expect(first.body.status).to.eq('SUCCESS')
          const ref1 = (payload(first.body) || {}).invoiceNo

          postOpening({ customerId: cid, amount: 6000, reference: `idem ${run}`, idempotencyKey: key })
            .then((second) => {
              expect(second.body.status, 'the replay succeeds rather than erroring').to.eq('SUCCESS')
              expect((payload(second.body) || {}).invoiceNo, 'and returns the SAME document').to.eq(ref1)
            })

          customerNamed(`OB Idem ${run}`).then((c) => {
            expect(Number(c.dueAmount), 'the balance was not doubled').to.eq(6000)
          })
        })
    })
  })

  // ── the supplier half ───────────────────────────────────────────────────────────────────────────

  it('⭐ 12 — a supplier opening posts Cr 2000 AP / Dr 3000 Equity and shows in the payable', () => {
    const run = uniq()
    setConfig(CUTOVER, CUTOVER_DATE)

    /*
     * ⚠ `name`, not `venderName`, and a COMPANY is required.
     *
     * The first cut sent venderName and got "name is required" — a message that reads like a missing field
     * when the field was simply called something else. Copied from b2b-customer-type.cy.js, which already
     * seeds vendors correctly: the convention existed both times I got this wrong today.
     */
    cy.ensureCompany().then((companyId) => {
    cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
      body: { name: `OB Supplier ${run}`, companyId,
              mobile: '03' + String(Date.now()).slice(-9), email: `obv${run}@t.com` },
    }).then((sr) => {
      expect(sr.body.status, `addVender: ${JSON.stringify(sr.body)}`).to.be.oneOf(['SUCCESS', 'FOUND'])
      cy.request('/getUserVender?q=-1').then((vr) => {
        const v = list(vr.body).find((x) => (x.venderName || x.name) === `OB Supplier ${run}`)
        expect(v, 'the supplier was seeded').to.be.an('object')
        const vid = v.venderId || v.id

        trialBalance().then((before) => {
          const ap0 = net(before, AP), eq0 = net(before, EQUITY)
          postOpening({ venderId: vid, amount: 30000, reference: `ap ${run}` })
            .then((r) => expect(r.body.status, `supplier opening: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))

          trialBalance().then((after) => {
            expect(net(after, AP) - ap0, 'Accounts Payable is CREDITED').to.eq(-30000)
            expect(net(after, EQUITY) - eq0, "Owner's Equity is DEBITED by the same").to.eq(30000)
          })
        })
      })
    })
    })
  })

  // ── it is not a sale ────────────────────────────────────────────────────────────────────────────

  it('13 — an opening balance is NOT counted as a sale', () => {
    /*
     * The doc_type discriminator earning its place. An opening balance in the Sale Detail Report would
     * overstate the month's trading, and in the tax register it would invent output tax on money that was
     * never a sale here.
     */
    const run = uniq()
    setConfig(CUTOVER, CUTOVER_DATE)
    seedCustomer(`OB NotSale ${run}`).then((cid) => {
      postOpening({ customerId: cid, amount: 99000, reference: `notsale ${run}` })
        .then((r) => expect(r.body.status).to.eq('SUCCESS'))

      cy.request({ url: '/getUserSell?q=-1', failOnStatusCode: false }).then((r) => {
        const hit = list(r.body).find((s) => String(s.grandTotal) === '99000')
        expect(hit, 'the opening document does not appear among sales').to.eq(undefined)
      })
    })
  })

  // ── who may do it ───────────────────────────────────────────────────────────────────────────────

  it('⭐ 14 — a plain USER cannot post an opening balance', () => {
    // It writes to the general ledger, and it is the easiest place in the product to hide a fabricated
    // receivable. Q5: the tenant's owner or finance admin, never everybody.
    // (tier, module) — the module is not optional; omitting it looks up a validate path for "undefined".
    cy.loginAsTier('user', 'business')
    postOpening({ customerId: 1, amount: 1000, reference: 'should be refused' }).then((r) => {
      expect(r.status === 403 || (r.body && r.body.status !== 'SUCCESS'),
        `a plain user must be refused: ${r.status} ${JSON.stringify(r.body)}`).to.eq(true)
    })
  })

  // ── the screen states its own limits ────────────────────────────────────────────────────────────

  it('15 — the screen says what is NOT migrated', () => {
    /*
     * Control 3, and an API test cannot see it. A shop that loads its customer dues and believes its whole
     * books came across will discover otherwise at its first stock valuation or tax return.
     */
    cy.loginAs('owner.lifecycle@myplus.com', 'Demo@2025!', '/getBusinessDashboardStats')
    cy.visit('/businessDashboard')
    cy.get('#OpeningBalanceDiv, [data-screen="openingBalance"]').should('exist')
    cy.get('#openingBalanceScope').should('exist')
      .invoke('text')
      .should('match', /cash|bank|stock|tax|not migrate/i)
  })

  after(() => {
    // Leave no server state behind. The cutover LOCK is the one that would outlive the run, so it is
    // cleared first — a locked tenant refuses every later opening-balance run with a message about a
    // migration nobody remembers starting.
    cy.loginAs('owner.lifecycle@myplus.com', 'Demo@2025!', '/getBusinessDashboardStats')
    setConfig(LOCKED, 'false')
    setConfig(CUTOVER, '')
    setConfig('pos.sale.creditLimitPolicy', 'warn')
  })
})
