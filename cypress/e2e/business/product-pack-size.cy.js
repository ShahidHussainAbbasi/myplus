/**
 * U1 — a product can say how many pieces its unit holds.
 * Design: microservices/docs/pack-and-loose-selling-design.md · slices/u1-product-pack-fields.md
 *
 * WHAT THIS SLICE MUST AND MUST NOT DO.
 *
 * It must let a shop record that a pack holds 10 tablets, and it must round-trip — because the save path
 * treats a null as "not supplied" rather than "clear it", a form that failed to read the values back would
 * silently strip a product's pack rules on the next unrelated edit.
 *
 * And it must change NOTHING for the shop that never breaks a pack. Four extra boxes on the most-used screen
 * in the app, asking what one tablet is called about a product nobody sells by the tablet, is a worse outcome
 * than the arithmetic this feature exists to remove.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/product-pack-size.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const openProducts = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().should('have.property', 'showProducts')
  cy.window().then((w) => w.showProducts())
  cy.get('#tableProduct', { timeout: 15000 }).should('exist')
}

/**
 * Read the product list, ASSERTING THE ENVELOPE before searching it.
 *
 * ⚠ Written after a failure that pointed at the wrong layer. `/getUserProduct` returns
 * `{status:"ERROR"}` with NO `collection` key when its call to catalog-service fails, and
 * `{status:"NOT_FOUND", collection:[]}` when the list is empty. The obvious read —
 * `r.body.collection || []` — turns BOTH into an empty array, so `.find()` yields undefined and the
 * assertion reads "the product was stored: expected undefined to exist".
 *
 * That sentence accuses the SAVE. The save was correct: the row was in the database, with the right
 * pack size, the right unit and the right permission to split. A read that had failed one hop away
 * spent the debugging on the write path instead.
 *
 * So the envelope is checked first, and each failure says which one it was.
 */
const productList = () =>
  cy.request('/getUserProduct?q=-1').then((r) => {
    expect(r.body.status, `/getUserProduct returned ${JSON.stringify(r.body).slice(0, 200)}`)
      .to.eq('SUCCESS')
    expect(r.body.collection, 'the list came back with no rows at all — a READ failure, not a save failure')
      .to.be.an('array').and.not.be.empty
    return r.body.collection
  })

const newProductForm = () => {
  cy.get('#newProduct', { timeout: 10000 }).click({ force: true })
  cy.get('#ProductModal', { timeout: 10000 }).should('be.visible')
}

describe('U1 — pack size on a product', () => {
  beforeEach(() => cy.loginAsOwner())

  // ── the shop that never breaks a pack ─────────────────────────────────────────────────────────────────

  it('the loose fields are HIDDEN until the unit holds more than one', () => {
    // The negative control for the whole slice, and the case that protects every existing shop. Without it,
    // "the fields appear" would be satisfied by four boxes that are always there.
    openProducts()
    newProductForm()

    cy.get('#prodPackSize').should('exist').and('have.value', '')
    cy.get('#prodLooseWrap').should('not.be.visible')

    // A pack of one is not divisible either — 1 and blank must behave the same.
    cy.get('#prodPackSize').clear().type('1')
    cy.get('#prodLooseWrap').should('not.be.visible')
  })

  it('they appear once it does', () => {
    openProducts()
    newProductForm()

    cy.get('#prodPackSize').clear().type('10')
    cy.get('#prodLooseWrap').should('be.visible')
    cy.get('#prodLooseUnit').should('be.visible')
    cy.get('#prodAllowLoose').should('be.visible')
  })

  it('clearing the pack size withdraws permission to split', () => {
    // A product that is no longer divisible must not keep "may be sold by the piece" set — the server would
    // then have to refuse a combination the form itself created.
    openProducts()
    newProductForm()

    cy.get('#prodPackSize').clear().type('10')
    cy.get('#prodAllowLoose').check({ force: true })
    cy.get('#prodAllowLoose').should('be.checked')

    cy.get('#prodPackSize').clear().trigger('keyup')

    cy.get('#prodLooseWrap').should('not.be.visible')
    cy.get('#prodAllowLoose').should('not.be.checked')
  })

  // ── ⭐ THE CASE THAT CARRIES THE SLICE ─────────────────────────────────────────────────────────────────

  it('⭐ the pack rules save and come back — a round trip', () => {
    /*
     * The save path treats null as "NOT SUPPLIED", never "clear it", because callers written before U1 — the
     * CSV import, the storefront admin, any integration — omit these fields entirely and must not strip them.
     *
     * That guard is also what makes this case load-bearing: if the FORM failed to read the values back, every
     * subsequent edit would post null, the guard would keep the old values, and the two would drift apart
     * with the screen showing something the product does not have.
     */
    const run = uniq()
    const name = `PackProd_${run}`

    openProducts()
    newProductForm()

    cy.get('#prodName').clear().type(name)
    cy.get('#prodPrice').clear().type('120')
    cy.get('#prodUnit').clear().type('pack')
    cy.get('#prodPackSize').clear().type('10')
    cy.get('#prodLooseUnit').clear().type('tablet')
    cy.get('#prodLooseUnitPlural').clear().type('tablets')
    cy.get('#prodAllowLoose').check({ force: true })
    cy.get('#prodDefaultSellUnit').select('LOOSE', { force: true })

    cy.get('#addProduct').click({ force: true })   // sic: the save button's id is #addProduct
    cy.get('#ProductModal', { timeout: 15000 }).should('not.be.visible')

    // Read it back THROUGH THE API — the screen could render from memory and prove nothing about storage.
    productList().then((list) => {
      const saved = list.find((p) => p.name === name)
      expect(saved, `the product was stored (${list.length} rows read)`).to.exist
      expect(saved.packSize, 'pack size').to.eq(10)
      expect(saved.looseUnit, 'singular').to.eq('tablet')
      expect(saved.looseUnitPlural, 'plural — "5 tablet" is wrong in every language here').to.eq('tablets')
      expect(saved.allowLoose, 'permission to split').to.eq(true)
      expect(saved.defaultSellUnit, 'which unit a line starts in').to.eq('LOOSE')
    })
  })

  it('reopening the product shows what was saved, with the loose row already open', () => {
    // The other half of the round trip: the form must REHYDRATE. A blank pack size on reopen would post null
    // on the next save and the values would quietly diverge from the screen.
    const run = uniq()
    const name = `Rehydrate_${run}`

    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: { name, sellingPrice: 90, unit: 'strip', packSize: 6, looseUnit: 'capsule',
        looseUnitPlural: 'capsules', allowLoose: true, defaultSellUnit: 'PACK' },
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    openProducts()
    cy.get('#tableProduct_filter input', { timeout: 15000 }).clear().type(name, { delay: 0 })
    cy.contains('#tableProduct tbody tr', name).find('.js-edit-row').click({ force: true })

    cy.get('#ProductModal', { timeout: 10000 }).should('be.visible')
    cy.get('#prodPackSize').should('have.value', '6')
    cy.get('#prodLooseUnit').should('have.value', 'capsule')
    cy.get('#prodLooseUnitPlural').should('have.value', 'capsules')
    cy.get('#prodAllowLoose').should('be.checked')
    // Opened by itself, because the product IS divisible — the operator should not have to make it appear.
    cy.get('#prodLooseWrap').should('be.visible')
  })

  it('an ordinary product is completely unaffected', () => {
    // Nothing becomes divisible because a column appeared. A default is not a decision.
    const run = uniq()
    const name = `Plain_${run}`

    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: { name, sellingPrice: 50, unit: 'pcs' },
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    productList().then((list) => {
      const saved = list.find((p) => p.name === name)
      expect(saved, `the product exists (${list.length} rows read)`).to.exist
      expect(saved.packSize, 'not divisible').to.be.oneOf([null, undefined])
      expect(saved.allowLoose, 'and cannot be split').to.eq(false)
      expect(saved.defaultSellUnit, 'lines start whole').to.eq('PACK')
    })
  })
})
