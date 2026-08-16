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

      // Re-read BOTH sides on every retry — the header count is NOT stable at first paint.
      //
      // `loadDataTable` calls `datatable.columns([0]).visible(false)` (column 0 is the internal row id),
      // and DataTables then REMOVES that `<th>`. So the header is 14 before it settles and 13 after,
      // while a data row is 13 throughout. The old form snapshotted the header with
      // `cy.get('thead th').then(...)` and retried only the `td` side against that frozen number — which
      // is the entire "header 14, row 13" puzzle. A "-1" correction merely moved the failure to
      // "expected 12, found 13" when the read landed late instead. Never a grid defect.
      //
      // A self-describing failure settled it in ONE run by printing both lists: the row began at "Edit"
      // where the header began at "ID", and every remaining cell sat under its correct heading
      // (Edit→checkbox, name→NAME, sku→SKU, unit→UNIT, price→PRICE, 40.00→LAST PURCHASE,
      // 65.00→LAST SALE, tax→TAX %, General→CATEGORY, →MANUFACTURER, …→ON HAND, →ADD STOCK,
      // ACTIVE→STATUS). Name both sides before theorising about a count.
      //
      // `should()` re-runs the whole callback, so both numbers are re-read until DataTables settles. The
      // guard is unchanged: a renderer emitting the wrong number of cells shifts every later column and
      // DataTables throws "Requested unknown parameter".
      cy.get('#tableProduct').should(($table) => {
        const th = $table.find('thead th').length
        const row = $table.find('tbody tr').filter((i, tr) => (tr.innerText || '').includes(pname)).first()
        const td = row.find('td').length
        expect(row.length, `the seeded row ${pname} is rendered`).to.eq(1)
        expect(td, `row cells (${td}) vs header cells (${th})`).to.eq(th)
      })
      cy.contains('#tableProduct tbody tr', pname, { timeout: 10000 }).within(() => {
        // Purchase cell then sale cell — the two the renderer emits, in header order.
        cy.get('.prod-lastrate').eq(0).should('have.text', '40.00')
        cy.get('.prod-lastrate').eq(1).should('have.text', '65.00')
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
