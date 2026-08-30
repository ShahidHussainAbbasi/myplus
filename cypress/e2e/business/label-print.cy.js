/**
 * U12 — printing the shop's own labels.
 *
 * Design: microservices/docs/slices/u12-printing-labels.md
 *
 * U7 lets a shop register `LP-4471` to mean "one tablet". Nothing could PRINT that label, so the code had to
 * be written by hand — and a hand-written code cannot be scanned, which was the whole point of it.
 *
 * THE BARCODE ENCODER IS VENDORED (`js/business/JsBarcode.all.min.js`, 66 KB, CODE128).
 *
 * ⚠ Before it landed, the second case asserted that the screen REFUSED to print — because rendering empty
 * barcode boxes would let a shop spend a sheet of stationery on stickers that cannot be scanned, and find
 * out at the counter. That case was PINNED at the behaviour of the day with a comment saying it would flip.
 *
 * It has flipped, and it now asserts the barcode is DRAWN — `svg.lbl-code` with `rect, path` children.
 * An empty `<svg>` satisfies "the element exists" and prints as a blank box, which is precisely the failure
 * the refusal existed to prevent: **asserting an element is present is not asserting the encoder ran.**
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/label-print.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const packProduct = (name) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name, sellingPrice: 120, unit: 'pack', packSize: 10,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose: true, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored').to.exist
      return p
    })
  })

const addSticker = (productId, barcode, soldUnit, quantity) =>
  cy.request({
    method: 'POST', url: '/addProductBarcode', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false, body: { productId, barcode, soldUnit, quantity },
  }).then((r) => {
    expect(r.body.success, `sticker: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    return r.body
  })

const openLabels = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().should('have.property', 'showLabels')
  cy.window().then((w) => w.showLabels())
  cy.get('#LabelsDiv', { timeout: 15000 }).should('be.visible')
  /*
   * ⚠ Wait for an actual STICKER ROW, not merely "a row".
   *
   * While the list is still loading, render() writes a placeholder row ("no stickers yet") — which satisfies
   * `tr >= 1` perfectly well. Five of these cases then do `cy.contains(tr, code)`, which retries until the
   * real row arrives and so never noticed; the one case that toggles select-all instead found a table with
   * no checkboxes in it and a button that correctly stayed disabled.
   *
   * A wait that a loading state can satisfy is not a wait.
   */
  cy.get('#lblBody .lbl-pick', { timeout: 15000 }).should('have.length.at.least', 1)
}

describe('U12 — printing the shop\'s own labels', () => {
  beforeEach(() => cy.loginAsOwner())

  // ── the layout presets, as pure data ─────────────────────────────────────────────────────────────────

  it('⭐ a layout preset decides how many labels fit a page', () => {
    // Presets as DATA, not a hard-coded grid: every shop's stationery differs, and a fixed layout fits
    // exactly one of them.
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => {
      expect(w.labelsPerPage, 'exported for the gate').to.be.a('function')
      expect(w.labelsPerPage('a4-3x8'), 'A4 three across, eight down').to.eq(24)
      expect(w.labelsPerPage('a4-2x7')).to.eq(14)
      expect(w.labelsPerPage('roll-40'), 'a roll prints one at a time').to.eq(1)
      expect(w.labelsPerPage('nope'), 'an unknown preset fits nothing').to.eq(0)
    })
  })

  // ── ⭐ the refusal that protects a sheet of stationery ────────────────────────────────────────────────

  it('⭐ the encoder is present, and a label renders a real barcode', () => {
    /*
     * ⚠ THIS CASE WAS PINNED THE OTHER WAY. Before `JsBarcode.all.min.js` was vendored it asserted that the
     * screen REFUSED to print — because rendering empty barcode boxes would have let a shop spend a sheet of
     * stationery on stickers that cannot be scanned, and discover it at the counter.
     *
     * The file has landed, so the case flips to asserting the thing that now matters: that the sheet contains
     * an actual drawn barcode, not merely that printing was permitted.
     */
    const name = `Lbl_${uniq()}`
    const code = `LP-${uniq()}`

    packProduct(name).then((p) => {
      addSticker(p.id, code, 'LOOSE', 1)
      openLabels()

      cy.window().its('labelBarcodeReady').should('be.a', 'function')
      cy.window().then((w) => {
        expect(w.labelBarcodeReady(), 'the vendored encoder is loaded').to.eq(true)
        // Stub the print dialog: the sheet must be built and inspectable without a printer prompt.
        cy.stub(w, 'print').as('printed')
      })

      cy.contains('#lblBody tr', code).find('.lbl-pick').check()
      cy.get('#lblPrint').should('not.be.disabled').click()
      cy.get('@printed').should('have.been.calledOnce')

      cy.get('#lblSheet .lbl').should('have.length', 1).within(() => {
        // ⭐ A DRAWN barcode: JsBarcode fills the <svg> with rect/path children. An empty <svg> would satisfy
        // "the element exists" and print as a blank box, which is the failure this whole guard exists for.
        cy.get('svg.lbl-code').should('exist')
          .find('rect, path').should('have.length.greaterThan', 0)

        // And the code as TEXT beneath it, so a bad print is still keyable.
        cy.contains('.lbl-text', code).should('exist')
        cy.contains('.lbl-name', name).should('exist')
      })
    })
  })

  it('a copies box of 3 renders three labels', () => {
    // A shop labelling a shelf needs many copies of ONE code, not one copy of many.
    const name = `LblCopies_${uniq()}`
    const code = `LX-${uniq()}`

    packProduct(name).then((p) => {
      addSticker(p.id, code, 'LOOSE', 1)
      openLabels()
      cy.window().then((w) => cy.stub(w, 'print').as('printed3'))

      cy.contains('#lblBody tr', code).find('.lbl-pick').check()
      cy.contains('#lblBody tr', code).find('.lbl-qty').clear().type('3')
      cy.get('#lblPrint').click()
      cy.get('@printed3').should('have.been.calledOnce')
      cy.get('#lblSheet .lbl').should('have.length', 3)
    })
  })

  // ── the sheet ────────────────────────────────────────────────────────────────────────────────────────

  it('⭐ a sticker lists its code, product and what it MEANS', () => {
    const name = `LblRow_${uniq()}`
    const code = `LR-${uniq()}`

    packProduct(name).then((p) => {
      addSticker(p.id, code, 'LOOSE', 1)
      openLabels()
      cy.contains('#lblBody tr', code).within(() => {
        cy.contains(name).should('exist')
        cy.contains('tablet').should('exist')   // "1 tablets" — what this code means, not just its id
      })
    })
  })

  it('a PACK sticker reads in packs, not pieces', () => {
    const name = `LblPack_${uniq()}`
    const code = `LB-${uniq()}`

    packProduct(name).then((p) => {
      addSticker(p.id, code, 'PACK', 12)
      openLabels()
      cy.contains('#lblBody tr', code).should('contain.text', '12')
    })
  })

  it('nothing prints when nothing is selected', () => {
    // The same rule as U11's Apply button: a destructive-of-stationery action does not arm by default.
    const name = `LblNone_${uniq()}`
    packProduct(name).then((p) => {
      addSticker(p.id, `LN-${uniq()}`, 'LOOSE', 1)
      openLabels()
      cy.get('#lblPrint').should('be.disabled')
    })
  })

  it('select-all arms the button, clearing it disarms again', () => {
    const name = `LblAll_${uniq()}`
    packProduct(name).then((p) => {
      addSticker(p.id, `LA-${uniq()}`, 'LOOSE', 1)
      openLabels()
      cy.get('#lblAll').check()
      cy.get('#lblPrint').should('not.be.disabled')
      cy.get('#lblAll').uncheck()
      cy.get('#lblPrint').should('be.disabled')
    })
  })

  // ── the no-dependency path, useful on its own ────────────────────────────────────────────────────────

  it('every sticker is readable in one request, for the CSV export', () => {
    // Option C from the design, shipped alongside: a shop whose printer this layout does not suit hands the
    // codes to the tool it already owns. Asserted through the endpoint the export reads.
    const name = `LblCsv_${uniq()}`
    const code = `LC-${uniq()}`

    packProduct(name).then((p) => {
      addSticker(p.id, code, 'LOOSE', 1)
      cy.request('/allProductBarcodes').then((r) => {
        const rows = list(r.body)
        expect(rows.length, 'the whole shop in ONE request, not one per product').to.be.greaterThan(0)
        const mine = rows.find((x) => x.barcode === code)
        expect(mine, 'the sticker just registered').to.exist
        expect(mine.soldUnit).to.eq('LOOSE')
        expect(Number(mine.quantity)).to.eq(1)
      })
    })
  })
})
