/**
 * E2E — one pack-ruled product, all the way through: REGISTER → PURCHASE → SELL (pack) → SELL (loose) → FINANCE.
 *
 * Why this exists when U1-U4, purchase and gl-posting each have their own green gate: every one of those proves its
 * own stage in isolation. Nothing walked a SINGLE product the whole way and then asked the books whether they agree.
 * `flow.cy.js` comes closest (Company → Vender → Product → Stock → Purchase → Sell) and asserts no finance at all and
 * no pack rules. The failures this shape catches are the ones between stages: a purchase that stocks packs but posts
 * nothing to Inventory, a loose sale that moves half a pack off the shelf but bills a whole one, a sale that reaches
 * the invoice but never the ledger.
 *
 * THE FIXTURE, chosen so every number is checkable by hand:
 *   pack of 10 tablets · cost 80.00 / pack · price 120.00 / pack · tax 0 (so an assertion is arithmetic, not policy)
 *   buy 10 packs → sell 2 packs (240.00) → sell 5 tablets (60.00, half a pack) → 7.5 packs left, 300.00 taken.
 *
 * ⚠ THE BOOKS ARE NOT CLEANED UP, deliberately. A sale posts to the ledger, and deleting a financial record to tidy a
 * test is the one cleanup that must never happen — the product is deactivated at the end and the invoices stay, as
 * they would for any real sale. Stock, company and vendor rows are left for the same reason: they are referenced by
 * the postings.
 *
 * ⚠ GL is posted through an OUTBOX, so the trial balance is POLLED rather than read once. Asserting it immediately is
 * how a spec like this flakes and then gets "fixed" by deleting the assertion.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/e2e-pack-purchase-sell-finance.cy.js
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const PACK_SIZE = 10
const COST = 80          // per pack, paid to the vendor
const PRICE = 120        // per pack, charged to the customer
const PACKS_IN = 10
const PACKS_SOLD = 2
const TABLETS_SOLD = 5   // half a pack

const PACK_SALE = PACKS_SOLD * PRICE           // 240.00
const LOOSE_SALE = (TABLETS_SOLD / PACK_SIZE) * PRICE   // 60.00 — half a pack at the pack price
const TAKEN = PACK_SALE + LOOSE_SALE           // 300.00
const LEFT = PACKS_IN - PACKS_SOLD - TABLETS_SOLD / PACK_SIZE   // 7.5 packs

const COGS_TOTAL = (PACKS_SOLD + TABLETS_SOLD / PACK_SIZE) * COST   // 2.5 packs × 80 = 200.00
const STOCK_VALUE = PACKS_IN * COST - COGS_TOTAL                    // 800 − 200 = 600.00 = 7.5 packs × 80

/** GL accounts this flow must move. */
const CASH = '1000'
const INVENTORY = '1200'
const SALES = '4000'
const COGS = '5000'

const state = {}

// ── the books ─────────────────────────────────────────────────────────────────────────────────────

const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
const acct = (rows, code) => (rows || []).find((x) => x.code === code) || { debit: 0, credit: 0 }
/**
 * The SIGNED net of an account (debit − credit).
 *
 * ⚠ Never assert "debit > 0" on a shared org: the trial balance nets each account to one side, and other specs credit
 * these same accounts (returns, refunds, purchase returns). What this flow owns is the MOVEMENT, so every assertion
 * below is a delta.
 */
const net = (rows, code) => {
  const a = acct(rows, code)
  return Number(a.debit || 0) - Number(a.credit || 0)
}

/** Poll the trial balance until `ready(rows)` holds — GL arrives via the outbox, not with the HTTP response. */
const tbUntil = (ready, what, tries = 20) =>
  tb().then((snap) => {
    if (ready(snap) || tries <= 0) {
      expect(ready(snap), `the ledger never showed: ${what}`).to.eq(true)
      return snap
    }
    return cy.wait(500).then(() => tbUntil(ready, what, tries - 1))
  })

const expectBalanced = (snap, when) => {
  expect(Number(snap.totalDebit), `Σdebit must equal Σcredit ${when}`).to.be.closeTo(Number(snap.totalCredit), 0.01)
  if (snap.balanced !== undefined) expect(snap.balanced, `the trial balance reports balanced ${when}`).to.eq(true)
}

// ── the shop floor ────────────────────────────────────────────────────────────────────────────────

const onHand = (productId) =>
  cy.request(`/productStock?productId=${productId}`).then((r) => Number((r.body && r.body.stock) || 0))

/** Wait for the inventory saga to land — a purchase answers before the stock row exists. */
const settleStock = (productId, atLeast, tries = 12) =>
  onHand(productId).then((have) => {
    if (have >= atLeast || tries <= 0) {
      expect(have, `stock never appeared for product ${productId}`).to.be.gte(atLeast)
      return have
    }
    return cy.wait(500).then(() => settleStock(productId, atLeast, tries - 1))
  })

/**
 * A sale. ⚠ These endpoints answer with a GenericResponse, which has NO `success` field — `r.body.success` is always
 * undefined, so `.to.not.eq(true)` would pass for a sale that was wrongly ACCEPTED. Status is the only truth here.
 */
const sell = (lines, total, note) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customer: { name: `E2EWalkin_${uniq()}`, contact: '03009999999' },
      sales: lines,
      tenders: [{ method: 'CASH', amount: total }],
      paidAmount: total, dueAmount: 0, grandTotal: total,
      idempotencyKey: `cy-e2e-${uniq()}`,
    },
  }).then((r) => {
    expect(r.body.status, `${note}: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq('SUCCESS')
    return r
  })

/** This product's stored sale LINES. /getUserSell is FLAT — each row IS a line, carrying its customerHistory. */
const linesFor = (productId) =>
  cy.request('/getUserSell?q=-1').then((r) => {
    const rows = r.body.collection || r.body.data || []
    return rows.filter((row) => String(row.productId || (row.product && row.product.id)) === String(productId))
  })

describe('E2E — pack product: register → purchase → sell pack → sell loose → finance', () => {
  /*
   * ⚠ EVERY test, not just the first. Cypress 13's testIsolation clears cookies between tests, so a single login in
   * before() leaves case 2 onwards UNAUTHENTICATED — and the monolith answers those with the LOGIN PAGE, i.e. HTML.
   * The symptom is "SyntaxError: Unexpected token '<', "<!DOCTYPE"" from whatever tries to parse it, which reads like
   * a broken endpoint rather than a missing session. cy.session caches, so this costs nothing after the first.
   *
   * ⚠ NOT owner.business@ — that is the account a person actually works in, and every login mints a refresh-token row
   * against a cap of five, so a spec that signs in as it evicts their live session and their till dies about fifteen
   * minutes later. That is the production defect AUTH-SESS-1 was raised for; a gate must not recreate it.
   * demo.business@ is what gl-posting, gl-outbox and finance-reports already use for this kind of flow, and the GL
   * endpoints carry no @PreAuthorize, so nothing here needs an owner.
   */
  beforeEach(() => {
    cy.loginAsBusiness()
  })

  before(() => {
    cy.loginAsBusiness()
    // The chart of accounts must exist before anything can post to it.
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })

    const stamp = uniq()
    state.name = `E2E Pack ${stamp}`

    cy.request({ method: 'POST', url: '/addCompany', form: true,
      body: { name: `E2ECo_${stamp}`, email: `e2eco${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = (cr.body.collection || cr.body.data || []).find((c) => c.name === `E2ECo_${stamp}`)
      expect(company, 'the company must exist before a vendor can belong to it').to.exist
      state.companyId = company.id

      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: `E2EVen_${stamp}`, companyId: state.companyId, mobile: '03004445555',
          email: `e2even${stamp}@t.com` } })
      cy.request('/getUserVender').then((vr) => {
        const vendor = (vr.body.collection || vr.body.data || []).find((v) => v.name === `E2EVen_${stamp}`)
        expect(vendor, 'the vendor must exist before a purchase can name it').to.exist
        state.venderId = vendor.id
      })
    })
  })

  after(() => {
    // Deactivate the product; leave every financial row alone (see the header).
    if (state.productId) {
      cy.request({ method: 'POST', url: '/removeProducts', headers: { 'Content-Type': 'application/json' },
        body: { checked: String(state.productId) }, failOnStatusCode: false })
    }
  })

  it('⭐ 1 — REGISTER: the product is stored WITH its pack rules, read back from the catalog', () => {
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        name: state.name, sku: `E2E${uniq()}`, sellingPrice: PRICE, taxRate: 0, unit: 'pack',
        packSize: PACK_SIZE, looseUnit: 'tablet', looseUnitPlural: 'tablets',
        allowLoose: true, defaultSellUnit: 'PACK',
      },
    }).then((r) => {
      expect(r.status, `addProduct: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
      expect(r.body && r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
      state.productId = r.body.data.id
    })

    // Read it back from the master rather than trusting the create response.
    cy.then(() => cy.request(`/getCatalogProduct?id=${state.productId}`)).then((r) => {
      const p = r.body.data || r.body.object || r.body
      expect(Number(p.packSize), 'a pack holds ten').to.eq(PACK_SIZE)
      expect(p.looseUnit, 'the singular a receipt prints').to.eq('tablet')
      expect(p.looseUnitPlural, '"5 tablet" is wrong in every language here').to.eq('tablets')
      expect(p.allowLoose, 'this pack may be split').to.eq(true)
      expect(Number(p.sellingPrice), 'priced per PACK').to.eq(PRICE)
    })
  })

  it('⭐⭐ 2 — PURCHASE: ten packs arrive, and Inventory moves in the ledger', () => {
    tb().then((before) => {
      const invBefore = net(before.rows, INVENTORY)
      state.invBefore = invBefore
      expectBalanced(before, 'before the purchase')

      cy.request({
        method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
        body: {
          productId: state.productId, quantity: PACKS_IN, venderId: state.venderId, paidAmount: PACKS_IN * COST,
          'stock.batchNo': `E2EB${uniq()}`, 'stock.bpurchaseRate': COST, 'stock.bsellRate': PRICE,
          totalAmount: PACKS_IN * COST, netAmount: PACKS_IN * COST, purchaseInvoiceNo: `E2E-${uniq()}`,
        },
      }).then((r) => expect(r.body.status, `purchase: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq('SUCCESS'))

      settleStock(state.productId, PACKS_IN).then((have) => {
        expect(have, 'ten packs on the shelf').to.be.gte(PACKS_IN)
      })

      tbUntil((s) => net(s.rows, INVENTORY) > invBefore,
        `Inventory ${INVENTORY} rising by the purchase`).then((after) => {
        expectBalanced(after, 'after the purchase')
        expect(net(after.rows, INVENTORY), 'goods bought are an asset, not an expense').to.be.greaterThan(invBefore)
      })
    })
  })

  it('⭐⭐ 3 — SELL A PACK: two packs for 240.00, and Sales + Cash move by exactly that', () => {
    tb().then((before) => {
      state.salesBefore = Number(acct(before.rows, SALES).credit)
      state.cashBefore = net(before.rows, CASH)
      state.cogsBefore = net(before.rows, COGS)

      sell([{ productId: state.productId, quantity: PACKS_SOLD, sellRate: PRICE,
        totalAmount: PACK_SALE, netAmount: PACK_SALE }], PACK_SALE, 'pack sale')

      settleStock(state.productId, 0).then(() => onHand(state.productId)).then((have) => {
        expect(have, 'two packs left the shelf').to.be.closeTo(PACKS_IN - PACKS_SOLD, 0.001)
      })

      tbUntil((s) => Number(acct(s.rows, SALES).credit) >= state.salesBefore + PACK_SALE - 0.01,
        `Sales ${SALES} rising by ${PACK_SALE}`).then((after) => {
        expectBalanced(after, 'after the pack sale')
        expect(Number(acct(after.rows, SALES).credit) - state.salesBefore,
          'revenue is exactly what was charged').to.be.closeTo(PACK_SALE, 0.01)
        expect(net(after.rows, CASH) - state.cashBefore,
          'a cash sale puts the money in Cash, not Receivables').to.be.closeTo(PACK_SALE, 0.01)
      })
    })
  })

  it('⭐⭐ 4 — SELL LOOSE: five tablets bill 60.00 and take HALF a pack off the shelf', () => {
    tb().then((before) => {
      const salesBefore = Number(acct(before.rows, SALES).credit)
      const cashBefore = net(before.rows, CASH)

      sell([{ productId: state.productId, soldUnit: 'LOOSE', soldQuantity: TABLETS_SOLD }], LOOSE_SALE, 'loose sale')

      linesFor(state.productId).then((rows) => {
        const loose = rows.find((l) => l.soldUnit === 'LOOSE')
        expect(loose, 'the loose line was stored').to.exist
        expect(Number(loose.soldQuantity), 'five tablets, as the customer asked').to.eq(TABLETS_SOLD)
        expect(Number(loose.quantity), 'half a pack in the selling unit every report sums').to.be.closeTo(0.5, 0.0001)
        expect(Number(loose.sellRate), 'the rate stays per PACK — the identity the books rely on').to.eq(PRICE)
        // The invariant an earlier draft of U2 broke: quantity × rate must BE the line total.
        expect(Number(loose.quantity) * Number(loose.sellRate),
          'quantity × rate = net, or the receipt and the ledger disagree').to.be.closeTo(Number(loose.netAmount), 0.01)
        expect(Number(loose.netAmount), 'five of ten tablets is half the pack price').to.be.closeTo(LOOSE_SALE, 0.01)
      })

      onHand(state.productId).then((have) => {
        expect(have, 'seven and a half packs left — not seven, and not two and a half').to.be.closeTo(LEFT, 0.001)
      })

      tbUntil((s) => Number(acct(s.rows, SALES).credit) >= salesBefore + LOOSE_SALE - 0.01,
        `Sales ${SALES} rising by ${LOOSE_SALE}`).then((after) => {
        expectBalanced(after, 'after the loose sale')
        expect(Number(acct(after.rows, SALES).credit) - salesBefore,
          'half a pack earns half the pack price').to.be.closeTo(LOOSE_SALE, 0.01)
        expect(net(after.rows, CASH) - cashBefore, 'and the cash drawer agrees').to.be.closeTo(LOOSE_SALE, 0.01)
      })
    })
  })

  it('⭐⭐ 5 — FINANCE: the whole flow reconciles, and the trial balance still balances', () => {
    tbUntil((s) => Number(acct(s.rows, SALES).credit) >= state.salesBefore + TAKEN - 0.01,
      `Sales ${SALES} up by the full ${TAKEN}`).then((after) => {
      expectBalanced(after, 'at the end of the flow')

      // Revenue across BOTH sales, measured from before the first one.
      expect(Number(acct(after.rows, SALES).credit) - state.salesBefore,
        `two packs (${PACK_SALE}) plus five tablets (${LOOSE_SALE}) is ${TAKEN} of revenue, and nothing else`)
        .to.be.closeTo(TAKEN, 0.01)

      // Cash taken across both sales — the drawer and the ledger tell the same story.
      expect(net(after.rows, CASH) - state.cashBefore,
        'every rupee billed was collected in cash').to.be.closeTo(TAKEN, 0.01)

      // Σdebit = Σcredit is the one property that must hold no matter what else this org did meanwhile.
      expect(Number(after.totalDebit), 'the books balance').to.be.closeTo(Number(after.totalCredit), 0.01)
    })

    /*
     * ⭐⭐ THE COST SIDE — and the reason a balanced trial balance is not enough.
     *
     * COGS-1: the batch's unit cost used to be paid ÷ what was LEFT, so the loose sale (the second from this batch)
     * was costed at 800 ÷ 8 = 100.00 a pack instead of 80.00 — COGS 210 instead of 200, Inventory 590 instead of 600.
     * Both wrong legs sat in the same journal, so Σdebit = Σcredit held and every assertion above stayed green.
     * Only these two lines can see it. COGS posts via the outbox a moment after the sale, so it is polled too.
     */
    tbUntil((s) => net(s.rows, COGS) - state.cogsBefore >= COGS_TOTAL - 0.01,
      `COGS ${COGS} rising by ${COGS_TOTAL}`).then((after) => {
      expect(net(after.rows, COGS) - state.cogsBefore,
        `2 packs + half a pack, at the 80.00 the batch cost, is ${COGS_TOTAL} of cost — however many were left`)
        .to.be.closeTo(COGS_TOTAL, 0.01)
      expect(net(after.rows, INVENTORY) - state.invBefore,
        `7.5 packs left at 80.00 is ${STOCK_VALUE} on the books — the shelf and the ledger value the same goods`)
        .to.be.closeTo(STOCK_VALUE, 0.01)
    })

    // And the shelf agrees with the ledger: 10 bought − 2 sold − half a pack = 7.5.
    onHand(state.productId).then((have) => {
      expect(have, 'stock, sales and the books all describe the same ten packs').to.be.closeTo(LEFT, 0.001)
    })
  })
})
