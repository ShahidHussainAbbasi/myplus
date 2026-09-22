/**
 * U15 Slice D — the quote asks for the unit the way the till does.
 *
 * Design: microservices/docs/slices/u15-pack-loose-ux.md §7
 *
 * U14 gave quotes loose units through an always-visible `<select>` offering our generic words, "Pack" and
 * "loose". The till, since U3, hides its unit control unless the picked product may actually be split, and
 * labels it with the shop's own noun. Two controls for one decision, and the quote's was the permissive one:
 *
 *   - a bookkeeper could choose "loose" on a SEALED product, compose the whole quote, and learn at save that
 *     it was never possible — the lost-work shape Slice A removed from the till;
 *   - a pharmacy read "loose" on a screen where every other surface says "tablets".
 *
 * The control now reads /looseInfo — which since U15-A1 folds in the tenant's LOOSE_SELLING capability — so
 * it cannot offer what the sale path would refuse.
 *
 * ⚠ RUN BEFORE REBUILDING. The served JS has no onQuoteProductPicked and #qtUnit has no display:none, so
 * cases 1-3 must FAIL first.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/pack-loose-ux-quote-unit.cy.js
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const makeProduct = (extra) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: Object.assign({
      name: `U15D ${uniq()}`, sku: `U15D${uniq()}`, sellingPrice: 311.60, unit: 'box',
      packSize: 40, looseUnit: 'tablet', looseUnitPlural: 'tablets',
      allowLoose: true, defaultSellUnit: 'PACK',
    }, extra || {}),
  }).then((r) => {
    expect(r.body.success, `addProduct: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    const id = r.body.data && r.body.data.id
    expect(id, 'the new product came back with an id').to.exist
    return cy.wrap(id)
  })

/**
 * Open the quote form and pick a product.
 *
 * ⚠ The picker is populated asynchronously, and org 6 holds 3,400+ products — so wait for THIS option
 * rather than for "some options", which a stale list would satisfy.
 */
/**
 * ⚠ WHAT THE OPERATOR ACTUALLY SEES IS NOT `#qtUnit`.
 *
 * searchable-selects.js enhances every eligible <select>, so bootstrap-select hides the native element at
 * ALL times and renders a button in a sibling `.bootstrap-select` wrapper. A `should('be.visible')` on
 * `#qtUnit` therefore can never pass, and a `should('not.be.visible')` passes whether the control is shown
 * or not — the coincidental pass this project's gate-quality rule warns about, and the first red run of this
 * spec had one of each.
 *
 * So visibility is asserted on the WRAPPER (what a person sees) and the chosen value on the native select
 * (what the form submits). business.js:4326 uses the same `$dd.next('.bootstrap-select')` handle.
 */
const unitControl = () =>
  cy.get('#qtUnit').then(($u) => {
    const $wrap = $u.next('.bootstrap-select')
    return cy.wrap($wrap.length ? $wrap : $u)
  })

/** The label the operator reads on the loose option, from the rendered widget or the native option. */
const looseLabel = () => cy.get('#qtUnit option[value="LOOSE"]')

const quoteWith = (productId) => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().should('have.property', 'showQuotes')
  cy.window().then((w) => w.showQuotes())
  cy.get('#newQuote', { timeout: 15000 }).click({ force: true })
  cy.get('#QuoteFormWrap', { timeout: 10000 }).should('be.visible')
  cy.get(`#qtItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')
  cy.get('#qtItemDD').select(String(productId), { force: true })
}

describe('U15 Slice D — the quote asks for the unit the way the till does', () => {
  beforeEach(() => cy.loginAsBusiness())

  it('⭐⭐ 1 — a SEALED product offers no loose option at all, so the quote cannot be refused for it', () => {
    /*
     * The case that carries the slice. Before this, the select offered "loose" here — and the server refuses
     * a sealed product, so the whole quote was lost at save. The control now simply is not there.
     */
    makeProduct({ allowLoose: false }).then((id) => {
      quoteWith(id)
      cy.get('#qtQty', { timeout: 10000 }).should('be.visible')   // positive control: the form IS open
      unitControl().should('not.be.visible')
    })
  })

  it('⭐⭐ 2 — a splittable product offers the unit in the SHOP\'S word, not ours', () => {
    makeProduct().then((id) => {
      quoteWith(id)
      unitControl().should('be.visible')
      looseLabel().should('have.text', 'tablets')
      // And it opens on the pack, so a quote is never silently composed in pieces.
      cy.get('#qtUnit').should('have.value', '')
    })
  })

  it('⭐ 3 — the control keeps its word after a line is added, for the next line of the same product', () => {
    // The product stays selected after Add line, so clearing the VALUE must not clear the NOUN — otherwise
    // the second line of a prescription reads "loose" where the first read "tablets".
    makeProduct().then((id) => {
      quoteWith(id)
      unitControl().should('be.visible')
      cy.get('#qtUnit').select('LOOSE', { force: true })
      cy.get('#qtQty').clear().type('10')
      cy.get('#qtRate').clear().type('311.60')
      cy.get('button').contains('Add line').click({ force: true })

      cy.get('#qtLines tr', { timeout: 10000 }).should('have.length.at.least', 1)
      unitControl().should('be.visible')
      looseLabel().should('have.text', 'tablets')
      cy.get('#qtUnit').should('have.value', '')   // reset to pack for the next line
    })
  })

  it('4 — a loose quote line still carries pieces to the server, unchanged by this slice', () => {
    // U14's contract is untouched: the line composes as soldUnit/soldQuantity and the SERVER prices it.
    // Asserted on the composed line rather than the screen, because that is what /addQuote receives.
    makeProduct().then((id) => {
      quoteWith(id)
      unitControl().should('be.visible')
      cy.get('#qtUnit').select('LOOSE', { force: true })
      cy.get('#qtQty').clear().type('10')
      cy.get('#qtRate').clear().type('311.60')
      cy.get('button').contains('Add line').click({ force: true })

      cy.window().its('quoteLines').should('have.length', 1)
      cy.window().then((w) => {
        const line = w.quoteLines[0]
        expect(line.soldUnit, 'quoted in pieces').to.eq('LOOSE')
        expect(Number(line.soldQuantity), 'ten tablets').to.eq(10)
        expect(Number(line.unitPrice), 'the PACK price — the server divides it').to.eq(311.60)
        expect(line.quantity, 'no shelf quantity is computed in the browser').to.be.undefined
      })
    })
  })
})
