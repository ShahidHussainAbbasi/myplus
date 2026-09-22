/**
 * U15 Slice B — say the unit, and say it in the shop's language.
 *
 * Design: microservices/docs/slices/u15-pack-loose-ux.md §4
 *
 * Slice A made the till's NUMBERS right. This slice is about the words beside them, which were wrong in four
 * ways a shopkeeper meets on their first day:
 *
 *   B1  "Sell Price" is the price of ONE PACK and nothing said so. An owner registering a box of 40 tablets
 *       at 311.60 had no way to check they had not typed the tablet price — a 40x error that reaches the
 *       till, the receipt and the margin guard.
 *   B2  the till's stock badge read "0.25" while the cashier was typing tablets.
 *   B3  purchase return said "purchased qty 10" with NO UNIT AT ALL. Packs? Cartons?
 *   B4  both return dialogs were built in hardcoded English, in a product shipping six languages — while the
 *       error messages a few lines below them were already translated.
 *
 * ⚠ B1 IS THE ONE THAT MATTERS, and its case is not "a hint appears". It is that the hint EQUALS what the
 * till will charge. ceil(packRate × (1 + markup/100) / packSize) living in two places is exactly how a shop
 * comes to quote one price on the product screen and charge another at the counter, so the case reads the
 * form's figure and /looseInfo's and asserts they are the same number.
 *
 * ⚠ RUN BEFORE REBUILDING. The monolith serves its JS from target/classes and /looseRatePreview does not
 * exist on the deployed business-service, so these must FAIL first.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/pack-loose-ux-labels.cy.js
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const PACK = 40
const PRICE = 311.60      // → 7.79 a tablet at markup 0

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

const packProduct = (name) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name, sku: `U15B${uniq()}`, sellingPrice: PRICE, unit: 'box', packSize: PACK,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose: true, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      expect(pr.body.status, '/getUserProduct envelope').to.eq('SUCCESS')
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored and is readable back').to.exist
      return p
    })
  })

const stockIn = (productId, qty) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: qty, 'stock.batchNo': `U15BB${uniq()}`,
      'stock.bpurchaseRate': 100, 'stock.bsellRate': PRICE,
      totalAmount: qty * 100, netAmount: qty * 100, purchaseInvoiceNo: `U15B-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS'))

const openProducts = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().should('have.property', 'showProducts')
  cy.window().then((w) => w.showProducts())
  cy.get('#tableProduct', { timeout: 15000 }).should('exist')
}

const newProductForm = () => {
  cy.get('#newProduct', { timeout: 10000 }).click({ force: true })
  cy.get('#ProductModal', { timeout: 10000 }).should('be.visible')
}

const openSale = () => {
  cy.openSellSection('sellDiv')
  cy.get('#sellItemDD', { timeout: 15000 }).should('exist')
}

const pickProduct = (productId) => {
  cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 10000 }).should('exist')
  cy.get('#sellItemDD').select(String(productId), { force: true })
  cy.get('#sellSellRate').should('not.have.value', '')
}

describe('U15 Slice B — say the unit, in the shop\'s language', () => {
  let markupBefore = null

  before(() => {
    cy.loginAsBusiness()
    cy.request({ url: '/getBusinessConfig', failOnStatusCode: false }).then((r) => {
      const row = ((r.body && r.body.data) || []).find((x) => x.key === 'pos.sale.looseMarkupPct')
      markupBefore = row ? row.value : null
    })
    setConfig('pos.sale.looseMarkupPct', '0')
  })

  after(() => {
    cy.loginAsBusiness()
    setConfig('pos.sale.looseMarkupPct', markupBefore == null ? '0' : String(markupBefore))
  })

  beforeEach(() => cy.loginAsBusiness())

  // ── B1 · the price the owner is actually deciding ────────────────────────────────────────────────────

  it('⭐⭐ 1 — the product form says what ONE TABLET costs, in the shop\'s own nouns', () => {
    openProducts()
    newProductForm()
    /*
     * ⚠ PACK SIZE BEFORE THE LOOSE UNIT. `#prodLooseUnit` lives inside `#prodLooseWrap`, which U1 keeps
     * display:none until the unit holds more than one — so typing into it first fails with "this element is
     * not visible", which reads like a broken form rather than a spec in the wrong order. (It did, on the
     * first red run of this file.)
     */
    cy.get('#prodPrice').clear().type(String(PRICE))
    cy.get('#prodUnit').clear().type('box')
    cy.get('#prodPackSize').clear().type(String(PACK)).trigger('keyup')
    cy.get('#prodLooseWrap').should('be.visible')
    cy.get('#prodLooseUnit').clear().type('tablet')
    cy.get('#prodPrice').trigger('keyup')        // re-price now that both nouns are known

    cy.get('#prodPricePerPiece', { timeout: 10000 }).should('be.visible')
      .and('contain.text', '311.60').and('contain.text', 'box')
      .and('contain.text', '7.79').and('contain.text', 'tablet')
  })

  it('2 — a product that cannot be split says nothing, rather than dividing by one', () => {
    openProducts()
    newProductForm()
    cy.get('#prodPrice').clear().type('50')
    cy.get('#prodPackSize').clear().type('1').trigger('keyup')

    cy.get('#prodPricePerPiece').should('not.be.visible')
  })

  it('⭐⭐ 3 — THE ONE THAT MATTERS: the form\'s per-piece price EQUALS what the till will charge', () => {
    /*
     * Not "a hint appeared" — that would pass on a hint computed by a second, drifting copy of the rule.
     * The form's figure and the till's own /looseInfo must be the SAME NUMBER, at a markup that is not zero
     * so a naive packRate ÷ packSize cannot coincide with the right answer.
     */
    setConfig('pos.sale.looseMarkupPct', '10')
    packProduct(`U15B agree ${uniq()}`).then((p) => {
      // failOnStatusCode:false so the RED run reports "404 — the endpoint does not exist yet" as an
      // assertion rather than as a Cypress request error, which is the defect stated plainly.
      cy.request({ url: `/looseRatePreview?packRate=${PRICE}&packSize=${PACK}`, failOnStatusCode: false })
        .then((form) => {
        expect(form.status, '/looseRatePreview is reachable').to.eq(200)
        const f = form.body.object || form.body.data
        expect(f, `looseRatePreview: ${JSON.stringify(form.body).slice(0, 200)}`).to.exist
        cy.request(`/looseInfo?productId=${p.id}`).then((till) => {
          const t = till.body.object || till.body.data
          expect(Number(f.looseRate), 'the form and the till price a tablet identically')
            .to.eq(Number(t.looseRate))
          // And it is the MARKED-UP figure, not the plain division: 311.60 × 1.10 ÷ 40 = 8.569 → 8.57.
          expect(Number(f.looseRate), 'rounded UP to the paisa, as the server does').to.eq(8.57)
          expect(Number(f.looseRate), 'not the un-marked-up 7.79').to.not.eq(7.79)
        })
      })
    })
  })

  // ── B2 · the shelf, counted the way a person counts it ───────────────────────────────────────────────

  it('⭐ 4 — the till reads "2 + 5 tablets", not "2.125"', () => {
    packProduct(`U15B shelf ${uniq()}`).then((p) => {
      stockIn(p.id, 3)
      /*
       * Sell 35 tablets of a 40-box: 3 boxes − 0.875 leaves 2 boxes and 5 tablets on the shelf.
       *
       * ⚠ The full envelope, copied from sell-loose.cy.js. A bare {sales:[…]} is refused with "An unexpected
       * error occurred" — /addSell wants a customer, tenders and an idempotency key — and that refusal reads
       * like a stock or pricing failure rather than a malformed request.
       */
      const total = 35 * 7.79
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        failOnStatusCode: false,
        body: {
          customer: { name: `U15B_${uniq()}`, contact: '03009999999' },
          sales: [{ productId: p.id, itemName: 'U15B', soldUnit: 'LOOSE', soldQuantity: 35,
            quantity: 0.875, sellRate: PRICE }],
          tenders: [{ method: 'CASH', amount: total }],
          paidAmount: total, grandTotal: total,
          idempotencyKey: `cy-u15b-${uniq()}`,
        },
      }).then((r) => expect(r.body.status, `the sale: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS'))

      openSale()
      pickProduct(p.id)
      /*
       * ⚠ `contain.text`, NOT `be.visible` — the pattern pos-cell-layout.cy.js:285 proved. The badge's
       * wrapper is hidden by `.pos-fullrow.pos-notice-empty{display:none}` in pos-rowentry layout mode, so
       * a visibility assertion here tests which POS layout the tenant is in, not what the badge says. (My
       * first red run failed on exactly that, which would have read as a badge defect.)
       */
      cy.get('#sellSellableInfo', { timeout: 15000 }).should('contain.text', '2 + 5 tablets')
      /*
       * ⚠ The field must stay NUMERIC — it is read back as `val()*ONE` when an invoice is edited, so a word
       * here becomes NaN and the line silently becomes one pack. That is the property under test, and it is
       * asserted as such: parses as a finite number, and carries no unit noun.
       *
       * It deliberately does NOT assert 2.125. The stored figure comes back as 2.12 — dev stock columns are
       * DECIMAL(38,2) although V8 declares (19,4), a known drift recorded before this slice. Pinning the
       * third decimal would make this case fail for a schema reason that has nothing to do with the badge,
       * and "expected 2.12 to be close to 2.125" reads like a rounding defect in U15. (It did, on the first
       * green run.) shelfText is unaffected: floor(2.12)=2, round(0.12×40)=5 → "2 + 5 tablets".
       */
      cy.get('#sellStock').invoke('val').then((v) => {
        expect(Number.isFinite(Number(v)), `#sellStock must parse as a number, got "${v}"`).to.eq(true)
        expect(String(v), '#sellStock carries no unit noun').to.not.match(/[a-z]/i)
        expect(Number(v), 'and it is the shelf figure in packs').to.be.greaterThan(2).and.to.be.lessThan(3)
      })
    })
  })

  // ── B3/B4 · the two return dialogs ───────────────────────────────────────────────────────────────────

  /*
   * Both dialogs are driven DIRECTLY with a synthetic button / call, the pattern commerce-gaps.cy.js proved
   * green. The alternative — seed a sale, open the report, filter the grid, find the row's Return button —
   * makes a case about DIALOG LABELS depend on paging, sort order and a grid filter, and a failure in any of
   * those reads as a labelling defect. My first draft of this file did exactly that, with three selectors
   * (#showSellReport, .sale-return-btn, #showPurchaseReport) that do not exist anywhere in the product.
   */
  it('⭐ 5 — the sale return dialog asks in TABLETS and its button says what it will do', () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().should('have.property', 'openSaleReturn')
    cy.window().then((win) => {
      const btn = win.document.createElement('button')
      btn.setAttribute('data-sellid', '0')
      btn.setAttribute('data-stockid', '')
      btn.setAttribute('data-qty', '0.25')        // the SHELF figure — what the dialog used to ask for
      btn.setAttribute('data-invoice', 'INV-U15B')
      btn.setAttribute('data-item', 'U15B tablets')
      btn.setAttribute('data-soldunit', 'LOOSE')
      btn.setAttribute('data-soldqty', '10')      // what the CUSTOMER bought
      btn.setAttribute('data-unitlabel', 'tablets')
      win.openSaleReturn(btn)
    })

    cy.get('#saleReturnDialog', { timeout: 10000 }).should('be.visible')
    cy.get('#srQtyLabel').should('contain.text', 'tablets')       // "How many tablets are coming back?"
    cy.get('#srSold').should('contain.text', '10 tablets')
    cy.get('#srSubmitText').should('contain.text', '10 tablets')  // the button names its own action
    cy.get('#srQty').should('have.value', '10')
    // B4: three labels that were hardcoded English until this slice.
    cy.get('#saleReturnDialog').should('not.contain.text', 'Quarantine returned stock')
    cy.get('#srSubmitText').should('not.contain.text', 'Confirm Return')
    cy.window().then((win) => win.closeSaleReturn())
  })

  it('⭐ 6 — purchase return names the unit instead of a bare number', () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().should('have.property', 'openPurchaseReturn')
    cy.window().then((win) => win.openPurchaseReturn('0', 4, 'PUR-U15B'))

    cy.get('#purchaseReturnDialog', { timeout: 10000 }).should('be.visible')
    cy.get('#prSold').should('contain.text', '4 packs')        // was a bare "4"
    cy.get('#prQtyLabel').should('contain.text', 'packs')      // "How many packs go back to the supplier?"
    cy.get('#prSubmitText').should('contain.text', '4 packs')
    cy.get('#purchaseReturnDialog').should('not.contain.text', 'Return to Vendor')
  })
})
