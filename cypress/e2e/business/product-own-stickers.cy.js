/**
 * U7 follow-up — a shop's OWN sticker codes are available for EVERY saved product, not only divisible ones.
 *
 * The stickers panel lived inside the loose-selling row, so it appeared only for a product sold by the piece — yet
 * the server (ProductBarcodeService.register) has always accepted a PACK sticker for ANY product: a grocer labelling
 * an unbarcoded tin, a hardware shop's own shelf codes. Only a LOOSE sticker needs "may be sold by the piece".
 *
 * So: the panel is its own cell; "Piece" is offered only while the product may be sold by the piece; a Pack sticker
 * saves for a plain product. Runs as demo.business@ (org 6: looseSelling on).
 *
 * Run headed.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const product = (body) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: Object.assign({ sellingPrice: 50, unit: 'pack' }, body),
  }).then((r) => {
    expect(r.body.success, `seed ${body.name}: ${JSON.stringify(r.body)}`).to.eq(true)
    return r.body.data.id
  })

const openForEdit = (id) => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().then((w) => w.showProducts())
  cy.intercept('GET', '**/productBarcodes*').as('stickers')
  cy.window().then((w) => w.editProduct(id))
  cy.get('#ProductModal', { timeout: 15000 }).should('have.class', 'open')
  cy.wait('@stickers')
}

describe('U7 — own stickers on any product', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('⭐⭐ a product NOT sold by the piece still offers stickers — Pack only — and a Pack sticker saves', () => {
    const name = `StickPlain_${uniq()}`
    const code = `SP${uniq()}`
    product({ name }).then((id) => {
      openForEdit(id)
      cy.get('#prodStickerWrap').should('be.visible')
      cy.get('#prodStickerUnit option[value="LOOSE"]').should('be.disabled')    // the server would refuse it
      cy.get('#prodStickerUnit').should('have.value', 'PACK')

      cy.intercept('POST', '**/addProductBarcode').as('add')
      cy.get('#prodStickerCode').clear().type(code)
      cy.get('#prodStickerAdd').click()
      cy.wait('@add').then((x) => {
        expect(x.request.body.soldUnit, 'sent as a PACK sticker').to.eq('PACK')
        expect(x.response.body.success, JSON.stringify(x.response.body)).to.eq(true)
      })
      cy.get('#prodStickerList').should('contain', code)
    })
  })

  it('a product sold by the piece offers Piece, and starts on it', () => {
    product({ name: `StickLoose_${uniq()}`, packSize: 10, looseUnit: 'tablet', looseUnitPlural: 'tablets',
      allowLoose: true, defaultSellUnit: 'PACK' }).then((id) => {
      openForEdit(id)
      cy.get('#prodStickerWrap').should('be.visible')
      cy.get('#prodStickerUnit option[value="LOOSE"]').should('not.be.disabled')
      cy.get('#prodStickerUnit').should('have.value', 'LOOSE')
      // Untick "may be sold by the piece" and Piece is withdrawn at once — the form never offers what the server refuses.
      cy.get('#prodAllowLoose').uncheck({ force: true })
      cy.get('#prodStickerUnit option[value="LOOSE"]').should('be.disabled')
      cy.get('#prodStickerUnit').should('have.value', 'PACK')
    })
  })
})
