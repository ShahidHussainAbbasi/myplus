/**
 * U13 — the Sale Return asks for what the customer bought: "10 tablets", not "0.25".
 *
 * Reported by the user (2026-09-17, owner.pharma@, INV-000040): ten tablets of a box of forty were sold, and the
 * Return dialog offered 0.25 to return. The stored line was right — quantity 0.25 (the shelf), soldQuantity 10 and
 * soldRate 7.79 (the customer) — but two screens read the shelf figure and asked a pharmacist to think in fractions
 * of a box. A sale-return screen that is hard to read is a screen where the wrong number gets typed.
 *
 * WHAT THIS PINS
 *  1. the grid shows the customer's quantity;
 *  2. the dialog asks in pieces, and says what that is on the shelf;
 *  3. ⭐ the SERVER converts — returnUnit=LOOSE with quantity in pieces — so money and stock never depend on
 *     arithmetic done in a browser;
 *  4. ⭐ a FULL return closes the line exactly, leaving no sliver of a box behind;
 *  5. a partial return shrinks BOTH views of the line, so the invoice cannot say ten tablets and hold seven;
 *  6. the old contract (no returnUnit = shelf units) still works — that is what every earlier caller sends.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/sale-return-loose-units.cy.js
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const PACK = 40          // tablets in a box, as in the reported invoice
const PRICE = 311.60     // per box
const COST = 100

/** A box product that may be split. */
const packProduct = (name) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      name, sku: `U13${uniq()}`, sellingPrice: PRICE, taxRate: 0, unit: 'box',
      packSize: PACK, looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose: true, defaultSellUnit: 'PACK',
    },
  }).then((r) => {
    expect(r.body && r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
    return r.body.data.id
  })

const stockIn = (productId, boxes) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: boxes, 'stock.batchNo': `U13B${uniq()}`,
      'stock.bpurchaseRate': COST, 'stock.bsellRate': PRICE,
      totalAmount: boxes * COST, netAmount: boxes * COST, purchaseInvoiceNo: `U13-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS'))

/** Sell `pieces` tablets; returns the invoice number. */
const sellLoose = (productId, pieces, total) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customer: { name: `U13_${uniq()}`, contact: '03009999999' },
      sales: [{ productId, soldUnit: 'LOOSE', soldQuantity: pieces }],
      tenders: [{ method: 'CASH', amount: total }],
      paidAmount: total, dueAmount: 0, grandTotal: total,
      idempotencyKey: `cy-u13-${uniq()}`,
    },
  }).then((r) => {
    expect(r.body.status, `the sale: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq('SUCCESS')
    return r.body.object || r.body.message
  })

/**
 * This product's line, read back from the sale history (a FLAT list: each row IS a line), or NULL when there is none.
 *
 * ⚠ NULL, never undefined. A Cypress `.then` that returns undefined does not yield undefined — it yields the PREVIOUS
 * subject, here the whole cy.request response. So "the line is gone" came back as a response object, and case 1's
 * `to.be.undefined` failed on a line that really had been deleted (first run, 2026-09-17).
 */
const lineOf = (productId) =>
  cy.request('/getUserSell?q=-1').then((r) => {
    const rows = (r.body && (r.body.collection || r.body.data)) || []
    return rows.filter((x) => String(x.productId) === String(productId))[0] || null
  })

const onHand = (productId) =>
  cy.request(`/productStock?productId=${productId}`).then((r) => Number((r.body && r.body.stock) || 0))

/** A return in PIECES — the new contract. */
const returnPieces = (sellId, pieces) =>
  cy.request({
    method: 'POST', url: '/saleReturn', form: true, failOnStatusCode: false,
    body: { sellId, quantity: pieces, returnUnit: 'LOOSE', reason: 'u13', quarantine: false, refundAs: 'CASH' },
  })

describe('U13 — a sale return is taken in the unit the customer bought', () => {
  beforeEach(() => cy.loginAsBusiness())

  it('⭐⭐ 1 — SERVER: 10 tablets of a 40-box are returned as 10, and the line closes exactly', () => {
    const name = `U13 full ${uniq()}`
    packProduct(name).then((productId) => {
      stockIn(productId, 5)
      // 10 of 40 = a quarter of a box: 311.60 / 4 = 77.90, the reported invoice exactly.
      sellLoose(productId, 10, 77.90)

      onHand(productId).then((afterSale) => {
        lineOf(productId).then((line) => {
          expect(Number(line.soldQuantity), 'the sale stored ten tablets').to.eq(10)
          expect(Number(line.quantity), 'and a quarter of a box left the shelf').to.be.closeTo(0.25, 0.0001)

          // ⭐ TEN, not 0.25 — the number the pharmacist is looking at.
          returnPieces(line.sellId, 10).then((r) => {
            expect(r.body.status, `the return: ${JSON.stringify(r.body).slice(0, 250)}`).to.eq('SUCCESS')

            onHand(productId).then((afterReturn) => {
              expect(afterReturn - afterSale,
                'a quarter of a box goes back on the shelf — not ten boxes, and not nothing')
                .to.be.closeTo(0.25, 0.0001)
            })
            lineOf(productId).then((gone) => {
              expect(gone, 'a full return removes the line — no sliver of a box left behind').to.be.null
            })
          })
        })
      })
    })
  })

  it('⭐⭐ 2 — a PARTIAL return shrinks both views: 3 of 10 back leaves 7 tablets, not 10', () => {
    const name = `U13 partial ${uniq()}`
    packProduct(name).then((productId) => {
      stockIn(productId, 5)
      sellLoose(productId, 10, 77.90)

      lineOf(productId).then((line) => {
        returnPieces(line.sellId, 3).then((r) => {
          expect(r.body.status, JSON.stringify(r.body).slice(0, 220)).to.eq('SUCCESS')

          lineOf(productId).then((kept) => {
            expect(Number(kept.soldQuantity),
              'the customer keeps seven tablets — the line used to keep claiming ten').to.eq(7)
            expect(Number(kept.quantity), 'and the shelf view agrees: 7/40 of a box')
              .to.be.closeTo(0.175, 0.0001)
            expect(Number(kept.soldRate), 'the per-tablet price it was sold at does not change').to.eq(7.79)
          })
        })
      })
    })
  })

  it('⭐ 3 — pieces are refused when they exceed what was sold, in the SAME unit', () => {
    const name = `U13 over ${uniq()}`
    packProduct(name).then((productId) => {
      stockIn(productId, 5)
      sellLoose(productId, 10, 77.90)

      lineOf(productId).then((line) => {
        returnPieces(line.sellId, 11).then((r) => {
          expect(r.body.status, 'eleven of ten is refused').to.eq('FAILED')
          expect(String(r.body.message || ''),
            'and the refusal counts in tablets — "0.25" would mean nothing to whoever typed 11')
            .to.match(/\(10\)/)
        })
        // The line is untouched by a refusal.
        lineOf(productId).then((still) => expect(Number(still.soldQuantity)).to.eq(10))
      })
    })
  })

  it('⭐ 4 — a sealed pack cannot be returned as loose pieces', () => {
    const name = `U13 sealed ${uniq()}`
    packProduct(name).then((productId) => {
      stockIn(productId, 5)
      // An ordinary pack sale: no soldUnit at all.
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: {
          customer: { name: `U13P_${uniq()}`, contact: '03009999999' },
          sales: [{ productId, quantity: 1, sellRate: PRICE, totalAmount: PRICE, netAmount: PRICE }],
          tenders: [{ method: 'CASH', amount: PRICE }],
          paidAmount: PRICE, dueAmount: 0, grandTotal: PRICE, idempotencyKey: `cy-u13p-${uniq()}`,
        },
      }).then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))

      lineOf(productId).then((line) => {
        returnPieces(line.sellId, 10).then((r) => {
          // Refused rather than reinterpreted: treating "10" as ten BOXES would restock forty times what left.
          expect(r.body.status, 'a line never sold loose cannot be returned in pieces').to.eq('FAILED')
          expect(String(r.body.message || '')).to.match(/not sold in loose units/i)
        })
      })
    })
  })

  it('⭐ 5 — the OLD contract still works: no returnUnit means shelf units, as every earlier caller sends', () => {
    const name = `U13 compat ${uniq()}`
    packProduct(name).then((productId) => {
      stockIn(productId, 5)
      sellLoose(productId, 10, 77.90)

      onHand(productId).then((afterSale) => {
        lineOf(productId).then((line) => {
          cy.request({
            method: 'POST', url: '/saleReturn', form: true, failOnStatusCode: false,
            body: { sellId: line.sellId, quantity: 0.25, reason: 'u13-compat', quarantine: false, refundAs: 'CASH' },
          }).then((r) => {
            expect(r.body.status, `shelf-unit return: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq('SUCCESS')
            onHand(productId).then((afterReturn) => {
              expect(afterReturn - afterSale, 'the same quarter box goes back').to.be.closeTo(0.25, 0.0001)
            })
          })
        })
      })
    })
  })

  it('⭐⭐ 6 — REAL UI: the grid and the dialog both read in tablets', () => {
    const name = `U13 ui ${uniq()}`
    packProduct(name).then((productId) => {
      stockIn(productId, 5)
      sellLoose(productId, 10, 77.90).then(() => {
        // The same navigation return-documents.cy.js uses to reach this grid and this very button.
        cy.visitSaleScreen()
        cy.get('#tableSell tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)

        /*
         * ⚠ SEARCH FOR THE ROW — do not assume it is on page 1. The grid shows FIVE rows and orders on the `updated`
         * STRING, so a sale made seconds ago is not reliably on the first page of a shared tenant (first run,
         * 2026-09-17: "never found 'U13 ui …'"). The search box is how a pharmacist finds an invoice anyway.
         */
        cy.get('#tableSell_filter input').clear().type(name)

        // The grid cell: "10 tablets", never the bare 0.25 a pharmacist cannot act on.
        cy.contains('#tableSell tbody tr', name, { timeout: 30000 }).within(() => {
          cy.get('#sellItems').should('contain.text', '10').and('contain.text', 'tablet')
          cy.get('button[onclick="openSaleReturn(this)"]').click()
        })

        // The dialog: sold shown in tablets WITH the shelf figure, and the input pre-filled in tablets.
        cy.get('#saleReturnDialog').should('be.visible')
        cy.get('#srSold').should('contain.text', '10').and('contain.text', '0.25')
        cy.get('#srQty').should('have.value', '10')
      })
    })
  })
})
