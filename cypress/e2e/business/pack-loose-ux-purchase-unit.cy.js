/**
 * U15 Slice C — the shop names the multiple it BUYS in.
 *
 * Design: microservices/docs/slices/u15-pack-loose-ux.md §5
 *
 * The purchase screen offered "Pack | Box", where Pack meant the shop's own BOX and Box meant a CARTON of N
 * of them — a level the product record had never heard of. One word, two meanings, on one form. The user
 * refused the obvious fix (rename our "Box" to "Carton") with the right question: that still leaves a word
 * every shop must learn. So the shop names this level too, exactly as U1 already lets it name the piece.
 *
 * ⚠ THE CASE THAT CARRIES THE SLICE is 4: the factor input must be EMPTY even though the product knows the
 * usual count. U5 requires it typed on every delivery — "box sizes vary by shipment, and a stale default
 * would be silently wrong for this delivery with the confidence of a pre-filled field behind it". A hint is
 * help; a pre-filled field is the tenfold cost error U5 exists to prevent. If a later change "helpfully"
 * fills it in, this case is what stops it.
 *
 * ⚠ RUN BEFORE REBUILDING. purchase_unit_name does not exist on the deployed catalog-service and the form
 * row is not in the served HTML, so every case must FAIL first.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/pack-loose-ux-purchase-unit.cy.js
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

/**
 * A product, created through the API with its purchase vocabulary.
 *
 * ⚠ Read back through /getCatalogProduct, not the list: org 6 holds 3,400+ products and the picker/list
 * endpoints are PAGED, so a name beginning with a late letter falls on a page this helper never fetches.
 * That is the defect that made three other specs look like cache failures (2026-09-22) — a single-product
 * read cannot have it.
 */
const makeProduct = (body) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false, body,
  }).then((r) => {
    expect(r.body.success, `addProduct: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    const id = r.body.data && r.body.data.id
    expect(id, 'the new product came back with an id').to.exist
    return cy.wrap(id)
  })

const readProduct = (id) =>
  cy.request({ url: `/getCatalogProduct?id=${id}`, failOnStatusCode: false }).then((r) => {
    expect(r.status, '/getCatalogProduct is reachable').to.eq(200)
    const p = r.body && r.body.data
    expect(p, `product ${id} read back: ${JSON.stringify(r.body).slice(0, 200)}`).to.exist
    return p
  })

const aBox = (extra) => Object.assign({
  name: `U15C ${uniq()}`, sku: `U15C${uniq()}`, sellingPrice: 311.60, unit: 'box',
  packSize: 40, looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose: true, defaultSellUnit: 'PACK',
}, extra || {})

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

/**
 * Pick a product on the PURCHASE line and wait for its unit vocabulary to arrive.
 *
 * ⚠ THE ENTRY FORM IS A MODAL. `#PurchaseModal` is display:none until `newPurchase()` opens it, so opening
 * the section alone leaves every field inside it invisible — and an assertion like "the toggle is not
 * visible" then PASSES because the form is shut, not because the rule under test works. The first red run
 * of this spec did exactly that (case 5 passed its visibility check for the wrong reason, case 4 failed
 * saying the toggle was not visible when the real answer was "no form is open"). capability-fields.cy.js
 * records the same trap: "#PurchaseModal is display:none until newPurchase() opens it".
 *
 * Opened by CLICKING the button rather than calling newPurchase(), for the reason that spec gives too —
 * these cases are about what a person sees, and a form opened by hand is not the form they get.
 */
const pickOnPurchase = (productId) => {
  cy.openPurchaseSection('purchaseDiv')
  cy.get('#newPurchase', { timeout: 15000 }).click({ force: true })
  cy.get('#PurchaseModal', { timeout: 10000 }).should('be.visible')
  cy.get(`#purchaseItemDD option[value="${productId}"]`, { timeout: 15000 }).should('exist')
  cy.get('#purchaseItemDD').select(String(productId), { force: true })
}

describe('U15 Slice C — the shop names the unit it buys in', () => {
  beforeEach(() => cy.loginAsBusiness())

  // ── the record ───────────────────────────────────────────────────────────────────────────────────────

  it('⭐ 1 — a shop can name the multiple it buys in, and it round-trips', () => {
    makeProduct(aBox({ purchaseUnitName: 'peti', purchasePackCount: 12 })).then((id) => {
      readProduct(id).then((p) => {
        expect(p.purchaseUnitName, 'the shop\'s own word').to.eq('peti')
        expect(Number(p.purchasePackCount), 'what it usually holds').to.eq(12)
        // Three levels, three separate answers — the collision this slice ends.
        expect(p.unit, 'the shelf unit is untouched').to.eq('box')
        expect(p.looseUnit, 'and so is the piece').to.eq('tablet')
      })
    })
  })

  it('⭐⭐ 2 — a shop that stops buying in multiples can CLEAR it', () => {
    /*
     * The pack rules beside it use "null means not supplied" so a partial payload cannot wipe them. This
     * field is assigned unconditionally, because here a blank IS the answer. Without that a shop could set
     * a purchase unit and never remove it — a one-way door nobody finds until they try to undo it.
     */
    makeProduct(aBox({ purchaseUnitName: 'peti', purchasePackCount: 12 })).then((id) => {
      readProduct(id).then((p) => {
        cy.request({
          method: 'POST', url: '/updateProduct', headers: { 'Content-Type': 'application/json' },
          failOnStatusCode: false,
          body: Object.assign({}, p, { purchaseUnitName: '', purchasePackCount: null }),
        }).then((r) => expect(r.body.success, `updateProduct: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true))

        readProduct(id).then((after) => {
          expect(after.purchaseUnitName, 'blank means: this shop no longer buys in multiples').to.be.oneOf([null, ''])
          expect(after.packSize, 'and the pack rules are untouched by the clearing').to.eq(40)
        })
      })
    })
  })

  // ── the product form ─────────────────────────────────────────────────────────────────────────────────

  it('3 — the product form offers the row, and it is optional', () => {
    openProducts()
    newProductForm()
    cy.get('#prodPurchaseUnit').should('exist').and('have.value', '')
    cy.get('#prodPurchasePackCount').should('exist').and('have.value', '')
  })

  // ── the purchase screen: the words, and the empty factor ─────────────────────────────────────────────

  it('⭐⭐ 4 — THE ONE THAT MATTERS: the usual count is a HINT, and the factor stays EMPTY', () => {
    makeProduct(aBox({ purchaseUnitName: 'peti', purchasePackCount: 12 })).then((id) => {
      pickOnPurchase(id)

      cy.get('.purchase-unit-wrap', { timeout: 15000 }).should('be.visible')
      cy.get('#purchaseUnitBox').should('have.text', 'peti')
      cy.get('#purchaseUnitPack').should('have.text', 'box')

      cy.get('#purchaseUnitBox').click({ force: true })
      cy.get('#purchasePpbHint', { timeout: 10000 }).should('be.visible').and('contain.text', '12')
      /*
       * ⭐ U5's ruling, enforced. The product KNOWS it is usually 12 and the screen SAYS so — and the input
       * is still empty, so the buyer types what THIS delivery actually contained.
       */
      cy.get('#purchasePacksPerBox').should('have.value', '')
    })
  })

  it('⭐ 5 — a shop that buys in single units sees NO toggle at all', () => {
    // Blank is the common answer, and this is what makes the screen simpler than before: the toggle used to
    // render for every tenant whether or not they had ever bought a carton.
    makeProduct(aBox()).then((id) => {
      pickOnPurchase(id)
      cy.get('#purchaseItemDD').should('have.value', String(id))
      /*
       * ⚠ A POSITIVE CONTROL FIRST, or this case proves nothing. "The toggle is hidden" passes trivially on
       * a form that never opened — which is how the first red run of this spec lied to me. Asserting that a
       * field which should ALWAYS be visible is visible pins the form open, so the absence below is a real
       * absence.
       */
      cy.get('#purchaseQuantity').should('be.visible')
      cy.get('.purchase-unit-wrap').should('not.be.visible')
      cy.get('#purchasePpbHint').should('not.be.visible')
    })
  })

  it('⭐ 6 — the hint reads as a sentence in the shop\'s words, not four bare numbers', () => {
    makeProduct(aBox({ purchaseUnitName: 'peti', purchasePackCount: 12 })).then((id) => {
      pickOnPurchase(id)
      cy.get('#purchaseUnitBox', { timeout: 15000 }).click({ force: true })
      /*
       * ⚠ ASSERT THE DEFAULT HAS LANDED BEFORE TYPING. Picking a product fires /productStock, whose handler
       * writes `$("#purchaseQuantity").val(1)` — asynchronously. A `.clear().type('10')` that runs before it
       * lands is overwritten, and one that races it produces "101": the green run read
       * "101 peti = 1212 box" because clear() emptied the box, "10" was typed, and the default's "1" arrived
       * after. Waiting for the 1 makes the sequence deterministic instead of hoping the request is slow.
       */
      cy.get('#purchaseQuantity', { timeout: 15000 }).should('have.value', '1')
      cy.get('#purchaseQuantity').clear().type('10').should('have.value', '10')
      cy.get('#purchasePacksPerBox').clear().type('12').should('have.value', '12')
      cy.get('#purchasePurchaseRate').clear().type('1200').should('have.value', '1200')

      /*
       * "10 peti = 120 box · 100.00 per box · 12000.00" — the line the buyer reads to catch a wrong carton
       * size, which is the whole reason U5 refuses to default the factor.
       *
       * ⚠ contain.text, NOT be.visible. #purchaseBoxHint is a `.pos-cell`, and in the modal's grid it
       * measures 0x0 — so a visibility assertion tests the layout, not the wording, and could never go
       * green because this slice changes only what the element SAYS. Cypress reads text from a zero-sized
       * element perfectly well. Exactly the trap #sellSellableInfo set in Slice B; the second time it has
       * cost a red run, so it is written down here rather than re-learned.
       */
      cy.get('#purchaseBoxHint', { timeout: 10000 })
        .should('contain.text', '10 peti')
        .and('contain.text', '120 box')
    })
  })
})
