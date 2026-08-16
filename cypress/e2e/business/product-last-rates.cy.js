/**
 * Product list — last purchase & last sale rate.
 *
 * The rates are STAMPED ONTO THE PRODUCT by the purchase flow (Option B, extended): adding or editing a purchase
 * writes what the product was bought at and what it is to be sold at onto the catalog master. Nothing is derived
 * from purchase history when the screen opens — the Product list renders both straight off the product row.
 *
 * These tests therefore prove the STAMP (add + edit + the guards) and then that the screen actually shows it.
 * Run headed.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

/** Record a purchase of `productId` at cost/sell, asserting it landed — an unasserted fixture makes the rest vacuous. */
const purchase = (productId, cost, sell, inv) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: {
      productId, quantity: 5, purchaseRate: cost,
      'stock.bpurchaseRate': cost, 'stock.bsellRate': sell,
      totalAmount: cost * 5, netAmount: cost * 5, paidAmount: cost * 5,
      purchaseInvoiceNo: inv,
    },
  }).then((r) => {
    expect(r.body.status, `purchase at cost ${cost} / sell ${sell}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
  })

/** The product row exactly as the Product list receives it. */
const productRow = (productId) =>
  cy.request('/getUserProduct').then((r) => {
    const row = (r.body.collection || []).find((p) => p.id === productId)
    expect(row, `product ${productId} is in /getUserProduct`).to.exist
    return row
  })

describe('Product list — last purchase & sale rate', () => {
  beforeEach(() => cy.loginAsBusiness())

  it('a purchase stamps both rates onto the product', () => {
    cy.seedProduct({ name: 'LR_' + uniq(), sellingPrice: 50 }).then(({ productId }) => {
      // Before any purchase there is nothing to stamp — null, NOT zero (the screen must show a dash).
      productRow(productId).then((row) => {
        expect(row.lastPurchaseRate, 'never purchased → no cost known').to.be.oneOf([null, undefined])
        expect(row.lastSaleRate, 'never purchased → no stamped sale rate').to.be.oneOf([null, undefined])
      })

      purchase(productId, 40, 65, 'LR-' + uniq())

      productRow(productId).then((row) => {
        expect(Number(row.lastPurchaseRate), 'what the bill paid').to.eq(40)
        expect(Number(row.lastSaleRate), 'what the bill set the price to').to.eq(65)
        expect(Number(row.sellingPrice), 'the live master price moved with it').to.eq(65)
        expect(row.lastRateAt, 'stamped with a date').to.not.be.oneOf([null, undefined])
      })
    })
  })

  it('a later purchase replaces both rates', () => {
    cy.seedProduct({ name: 'LR2_' + uniq(), sellingPrice: 50 }).then(({ productId }) => {
      purchase(productId, 40, 65, 'LR2A-' + uniq())
      purchase(productId, 45, 70, 'LR2B-' + uniq())

      productRow(productId).then((row) => {
        expect(Number(row.lastPurchaseRate), 'the newest bill wins').to.eq(45)
        expect(Number(row.lastSaleRate)).to.eq(70)
      })
    })
  })

  it('EDITING a purchase re-stamps the product — a mistyped rate is correctable', () => {
    cy.seedProduct({ name: 'LR3_' + uniq(), sellingPrice: 50 }).then(({ productId }) => {
      const inv = 'LR3-' + uniq()
      purchase(productId, 40, 65, inv)

      cy.request('/getUserPurchase').then((r) => {
        const list = r.body.collection || r.body.data || []
        const mine = list.find((p) => p.productId === productId)
        expect(mine, 'the purchase just recorded').to.exist

        // The rate was entered wrong; correcting the BILL must correct what the Product screen shows.
        cy.request({
          method: 'POST', url: '/updatePurchase', form: true, failOnStatusCode: false,
          body: {
            purchaseId: mine.purchaseId, productId, quantity: 5, purchaseRate: 38,
            'stock.bpurchaseRate': 38, 'stock.bsellRate': 62,
            totalAmount: 190, netAmount: 190, paidAmount: 190, purchaseInvoiceNo: inv,
          },
        }).then((u) => expect(u.body.status, JSON.stringify(u.body)).to.eq('SUCCESS'))

        productRow(productId).then((row) => {
          expect(Number(row.lastPurchaseRate), 'the correction reached the product').to.eq(38)
          expect(Number(row.lastSaleRate)).to.eq(62)
        })
      })
    })
  })

  it('a bill carrying no sell rate updates the cost WITHOUT re-pricing the shop', () => {
    cy.seedProduct({ name: 'LR4_' + uniq(), sellingPrice: 50 }).then(({ productId }) => {
      purchase(productId, 40, 65, 'LR4A-' + uniq())
      // Bought again, sell rate left blank on the bill (sent as 0) — the guard must keep the price at 65.
      purchase(productId, 42, 0, 'LR4B-' + uniq())

      productRow(productId).then((row) => {
        expect(Number(row.lastPurchaseRate), 'the new cost lands').to.eq(42)
        expect(Number(row.sellingPrice), 'a cost-only bill must never silently re-price the shop').to.eq(65)
        expect(Number(row.lastSaleRate), 'and the stamped sale rate holds too').to.eq(65)
      })
    })
  })

  it('the Product screen shows both rates in their columns (UI)', () => {
    const pname = 'LRUI_' + uniq()
    cy.seedProduct({ name: pname, sellingPrice: 50 }).then(({ productId }) => {
      purchase(productId, 40, 65, 'LRUI-' + uniq())

      cy.visit('/businessDashboard')
      cy.window().should('have.property', 'showProducts')
      cy.window().then((w) => w.showProducts())

      // The row array must stay exactly as long as the header, or every later column shifts and DataTables throws
      // "Requested unknown parameter" — assert that directly rather than trusting a column index.
      //
      // Compare ALL headers against ALL cells — one basis, the one the hazard is about.
      //
      // The hazard is a length mismatch between the header array and the row array: DataTables then throws
      // "Requested unknown parameter" and every later column shifts. CSS visibility is irrelevant to that,
      // so mixing bases only manufactures noise. Two wrong versions were tried before this one:
      //   • `th:visible` vs every `td`  → "Found 13, expected 12"
      //   • `th:visible` vs `td:visible` → "Found 8, expected 12"
      // Both compared different populations. All-vs-all is the only coherent question.
      //
      // ⚠ On this environment all-vs-all reports **header 14, row 13**, and that is NOT explained: the row
      // builder in business.js pushes exactly 14 entries, the deployed business.js is byte-identical to the
      // tree, and the grid renders without the DataTables error a genuine shift causes. Left asserting the
      // honest comparison rather than tuned until green — if it fails, it is reporting something real that
      // needs devtools on a headed run, not a looser assertion.
      cy.get('#tableProduct thead th').its('length').then((cols) => {
        cy.contains('#tableProduct tbody tr', pname, { timeout: 10000 }).within(() => {
          cy.get('td').should('have.length', cols)
          // Purchase cell then sale cell — the two the renderer emits, in header order.
          cy.get('.prod-lastrate').eq(0).should('have.text', '40.00')
          cy.get('.prod-lastrate').eq(1).should('have.text', '65.00')
        })
      })
    })
  })

  it('a never-purchased product shows a dash, not a zero', () => {
    const pname = 'LRNP_' + uniq()
    cy.seedProduct({ name: pname, sellingPrice: 50 }).then(() => {
      cy.visit('/businessDashboard')
      cy.window().should('have.property', 'showProducts')
      cy.window().then((w) => w.showProducts())

      cy.contains('#tableProduct tbody tr', pname, { timeout: 10000 }).within(() => {
        // "0.00" here would claim the shop bought it for nothing.
        cy.get('.prod-lastrate').eq(0).should('have.text', '—')
        cy.get('.prod-lastrate').eq(1).should('have.text', '—')
      })
    })
  })
})
