/**
 * U15 Slice A — the till tells the truth about a broken pack.
 *
 * Design: microservices/docs/slices/u15-pack-loose-ux.md §3
 *
 * Four defects, one screen. The books were never wrong — the server derives a loose line from `soldQuantity` —
 * but the CART was, and on the scan path that reached the customer's money:
 *
 *   A1  the Pack|Piece toggle was offered from the product's `allowLoose` alone, while the saga asserts the
 *       LOOSE_SELLING capability at submit. Offered → rung up → whole basket refused at Complete Sale.
 *   A2  the cart's Price column showed the PACK price beside "10 tablets" and a loose total: 10 × 311.60 = 77.90.
 *   A3  "Sales start as pieces" (`defaultSellUnit`) was stored and read by nobody.
 *   A4  ⚠ MONEY. A `5L*CODE` scan priced pieces × PACK price, and `calculateChange()` reads that column's
 *       footer (#sellTotal) to compute change — which `addSell` submits as customer.dueAmount.
 *
 * ⚠ A1's capability half is gated in LooseInfoOfferTest (unit), NOT here: flipping a tenant's capability is
 * server-wide state, and a spec that left it flipped would break every later run on this machine. What this
 * spec gates of A1 is the SHAPE of the answer the toggle is drawn from.
 *
 * ⚠ RUN THIS BEFORE REBUILDING. The monolith serves its JS from target/classes, so until a rebuild these
 * cases run against the OLD till and must FAIL — that red run is the evidence U13 and U14 never got.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/pack-loose-ux-till.cy.js
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const PACK = 10
const PRICE = 120        // → 12.00 a tablet at markup 0, and a whole pack still costs 120.00

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

/** A splittable product. `defaultSellUnit` is what case 2 turns on — every other case leaves it PACK. */
const packProduct = (name, defaultSellUnit = 'PACK') =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name, sku: `U15${uniq()}`, sellingPrice: PRICE, unit: 'pack', packSize: PACK,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose: true, defaultSellUnit },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored and is readable back').to.exist
      return p
    })
  })

/** ⚠ A purchase RESTAMPS the selling price from bsellRate — pass the intended price (U2 §13.4c). */
const stockIn = (productId, qty) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: qty, 'stock.batchNo': `U15B${uniq()}`,
      'stock.bpurchaseRate': 100, 'stock.bsellRate': PRICE,
      totalAmount: qty * 100, netAmount: qty * 100, purchaseInvoiceNo: `U15-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS'))

const openSale = () => {
  cy.openSellSection('sellDiv')
  cy.get('#sellItemDD', { timeout: 15000 }).should('exist')
}

const pickProduct = (productId) => {
  cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 10000 }).should('exist')
  cy.get('#sellItemDD').select(String(productId), { force: true })
  cy.get('#sellSellRate').should('not.have.value', '')
}

/**
 * The scan box, with the `5L*` grammar PROVEN live first.
 *
 * Asserting the flag here rather than trusting before() is the difference between a case that fails saying
 * "the multiplier grammar is off" and one that fails saying "No product for 5L*SKU" — which reads like a
 * missing product and sent the first red run chasing the wrong thing.
 */
const scanBox = () => {
  cy.window().then((w) => {
    expect(w.posShortcutsEnabled, 'pos.keyboard.shortcuts.enabled must be live for the 5L* grammar').to.eq(true)
  })
  return cy.enableScanBox()
}

/** The cart row for a product, as the CUSTOMER reads it: [id, name, qty, price, disc, total, action]. */
const cartRow = (productId) =>
  cy.get('#tablesi tbody tr', { timeout: 10000 }).should('have.length.at.least', 1)
    .then(() => cy.window().its('tablesi').then((t) => {
      const row = t.rows().data().toArray().find((r) => String(r[0]) === String(productId))
      expect(row, 'the line is in the cart grid').to.exist
      return row
    }))

/*
 * ⚠ TWO ORG-WIDE SWITCHES, SNAPSHOTTED AND PUT BACK.
 *
 * `pos.keyboard.shortcuts.enabled` is the one this spec learned the hard way. The `5L*CODE` grammar is part
 * of P2 and `sellScanAdd` only parses it when `window.posShortcutsEnabled === true` (business.js:376) — with
 * the flag off, "5L*SKU" goes to the server verbatim as a barcode and answers "No product for …". The first
 * red run (2026-09-21) failed cases 5-7 on exactly that, which would have been a spec that proved NOTHING
 * about the defect it was written for.
 *
 * Both are restored in after() rather than a trailing cy.then: Cypress abandons the rest of a failing test,
 * so a trailing restore never runs on the run that matters (the permission-sets lesson).
 */
const CONFIG_KEYS = ['pos.sale.looseMarkupPct', 'pos.keyboard.shortcuts.enabled']

describe('U15 Slice A — the till tells the truth about a broken pack', () => {
  const before_ = {}

  before(() => {
    cy.loginAsBusiness()
    cy.request({ url: '/getBusinessConfig', failOnStatusCode: false }).then((r) => {
      const rows = (r.body && r.body.data) || []
      CONFIG_KEYS.forEach((k) => {
        const row = rows.find((x) => x.key === k)
        before_[k] = row ? row.value : null
      })
    })
    setConfig('pos.sale.looseMarkupPct', '0')
    setConfig('pos.keyboard.shortcuts.enabled', 'true')
  })

  after(() => {
    cy.loginAsBusiness()
    CONFIG_KEYS.forEach((k) => {
      const was = before_[k]
      setConfig(k, was == null ? (k === 'pos.sale.looseMarkupPct' ? '0' : 'false') : String(was))
    })
  })

  beforeEach(() => cy.loginAsBusiness())

  // ── A1: what the toggle is drawn from ────────────────────────────────────────────────────────────────

  it('⭐ 1 — /looseInfo answers with the unit a line should OPEN in', () => {
    packProduct(`U15 shape ${uniq()}`, 'LOOSE').then((p) => {
      cy.request(`/looseInfo?productId=${p.id}`).then((r) => {
        const d = r.body.object || r.body.data
        expect(d, `looseInfo answered: ${JSON.stringify(r.body).slice(0, 200)}`).to.exist
        expect(d.allowLoose, 'the tenant may split this product').to.eq(true)
        // A3: the field that was stored by the form and returned to nobody.
        expect(d.defaultSellUnit, 'the unit the till should open in').to.eq('LOOSE')
        expect(Number(d.looseRate), 'per tablet at markup 0').to.eq(12)
      })
    })
  })

  // ── A3: the keystroke that disappears ────────────────────────────────────────────────────────────────

  it('⭐⭐ 2 — "Sales start as pieces" opens the line in pieces, with no keystroke', () => {
    packProduct(`U15 opens ${uniq()}`, 'LOOSE').then((p) => {
      stockIn(p.id, 5)
      openSale()
      pickProduct(p.id)

      // Nothing is pressed. The line must already be in pieces.
      cy.get('#sellUnitLoose', { timeout: 10000 }).should('have.class', 'active')
      cy.get('#sellUnitPack').should('not.have.class', 'active')
      cy.window().then((w) => {
        expect(w.LooseSell.isLoose(), 'the composed line is loose before any keystroke').to.eq(true)
      })
    })
  })

  it('3 — a product that starts whole still opens whole', () => {
    packProduct(`U15 whole ${uniq()}`, 'PACK').then((p) => {
      stockIn(p.id, 5)
      openSale()
      pickProduct(p.id)

      cy.get('#sellUnitPack').should('have.class', 'active')
      cy.window().then((w) => expect(w.LooseSell.isLoose()).to.eq(false))
    })
  })

  // ── A2: the row a customer reads ─────────────────────────────────────────────────────────────────────

  it('⭐⭐ 4 — the cart Price column shows the PER-PIECE rate, so the row reconciles', () => {
    packProduct(`U15 cart ${uniq()}`).then((p) => {
      stockIn(p.id, 5)
      openSale()
      pickProduct(p.id)
      cy.get('#sellQuantity').clear().type('5')
      cy.get('#sellUnitLoose').click({ force: true })
      cy.get('#addInviceItem').click({ force: true })

      cartRow(p.id).then((row) => {
        expect(String(row[2]), 'quantity in what the customer bought').to.contain('5 tablets')
        expect(Number(row[3]), 'the PER-PIECE rate, not the 120.00 pack price').to.eq(12)
        expect(Number(row[5]), 'and 5 × 12 is the total beside it').to.eq(60)
      })
    })
  })

  // ── A4: ⚠ the money ──────────────────────────────────────────────────────────────────────────────────

  it('⭐⭐ 5 — MONEY: a 5L* scan bills 60.00, and the change is computed from 60.00', () => {
    packProduct(`U15 scan ${uniq()}`).then((p) => {
      stockIn(p.id, 5)
      openSale()
      scanBox().type(`5L*${p.sku}{enter}`)

      cy.window().its('data.0.soldQuantity', { timeout: 15000 }).should('eq', 5)
      cy.window().then((w) => {
        const line = w.data[0]
        expect(Number(line.totalAmount), 'five tablets at 12.00 — NOT 5 × the 120.00 pack').to.eq(60)
        expect(Number(line.quantity), 'half a pack leaves the shelf').to.be.closeTo(0.5, 0.0001)
      })
      cartRow(p.id).then((row) => {
        expect(Number(row[3]), 'per tablet').to.eq(12)
        expect(Number(row[5]), 'the line total the footer sums').to.eq(60)
      })

      /*
       * ⭐ THE CASE THIS SLICE EXISTS FOR. #sellTotal is the footer of that column, and calculateChange()
       * derives Change and Due from it — then addSell submits #sellCh as customer.dueAmount. At 600.00 the
       * cashier handed back 540.00 too little and the customer's balance was wrong by the same amount.
       */
      cy.get('#sellTotal').invoke('text').then((t) => expect(Number(t)).to.eq(60))
      cy.get('#sellRec').clear().type('100')
      cy.get('#sellCh').invoke('val').then((v) => {
        expect(Number(v), 'change from a 100 note: 40.00, not −500.00').to.eq(40)
      })
    })
  })

  it('⭐ 6 — scanning the same loose product twice adds TABLETS, and a whole pack costs the pack price', () => {
    packProduct(`U15 twice ${uniq()}`).then((p) => {
      stockIn(p.id, 5)
      openSale()
      scanBox().type(`5L*${p.sku}{enter}`)
      cy.window().its('data.0.soldQuantity', { timeout: 15000 }).should('eq', 5)
      cy.get('#sellScan').type(`5L*${p.sku}{enter}`)
      cy.window().its('data.0.soldQuantity', { timeout: 15000 }).should('eq', 10)

      cy.window().then((w) => {
        expect(w.data, 'still ONE line').to.have.length(1)
        expect(Number(w.data[0].quantity), 'ten tablets of a pack of ten IS one pack').to.eq(1)
        // The rule the server applies too: a whole pack is never charged the broken-pack rate.
        expect(Number(w.data[0].totalAmount), 'one whole pack at the pack price').to.eq(120)
      })
    })
  })

  it('⭐ 7 — pieces scanned onto a PACK line are REFUSED, never merged', () => {
    packProduct(`U15 mixed ${uniq()}`).then((p) => {
      stockIn(p.id, 5)
      openSale()
      scanBox().type(`${p.sku}{enter}`)              // a plain barcode: one PACK
      cy.window().its('data.0.quantity', { timeout: 15000 }).should('eq', 1)

      cy.get('#sellScan').type(`5L*${p.sku}{enter}`)          // now five tablets onto that pack line

      cy.get('#sellScanMsg', { timeout: 10000 }).should('contain.text', 'different unit')
      cy.window().then((w) => {
        expect(w.data, 'still one line').to.have.length(1)
        expect(Number(w.data[0].quantity), 'the pack line is untouched — NOT 6 packs').to.eq(1)
        expect(w.data[0].soldUnit, 'and it is still a pack line').to.not.eq('LOOSE')
      })
    })
  })
})
