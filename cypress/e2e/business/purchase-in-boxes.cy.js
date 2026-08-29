/**
 * U5 — buying in boxes.
 *
 * Design: microservices/docs/slices/u5-buying-in-boxes.md
 *
 * A shop buys a **box of 10 packs for 1000**. The form asks for a quantity and a rate, so the buyer types
 * `10` and `1000` — and the system believes a pack costs **1000 instead of 100**.
 *
 * ⚠ That number is not cosmetic. It is `lastPurchaseRate`, which U2 reads as unit COGS, which the margin
 * guard reads to decide whether to refuse a sale. A tenfold cost error hides behind a fat pack margin and
 * starts refusing sales the moment thin per-piece margins sit beside it.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/purchase-in-boxes.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const product = (name, price) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false, body: { name, sellingPrice: price, unit: 'pack' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored').to.exist
      return p
    })
  })

/**
 * Goods in. Form-encoded with flat `stock.*` fields — copied from `product-last-rates.cy.js`, which is green.
 * `purchaseUnit` / `packsPerBox` are the two U5 adds; the monolith forwards every request parameter, so they
 * reach business-service without a DTO change there.
 */
const goodsIn = (productId, opts) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: Object.assign({
      productId,
      quantity: opts.qty,
      'stock.batchNo': `BX${uniq()}`,
      'stock.bpurchaseRate': opts.cost,
      'stock.bsellRate': opts.sell,
      totalAmount: opts.qty * opts.cost,
      netAmount: opts.qty * opts.cost,
      purchaseInvoiceNo: `BOX-${uniq()}`,
    }, opts.unit ? { purchaseUnit: opts.unit } : {},
       opts.packsPerBox != null ? { packsPerBox: opts.packsPerBox } : {}),
  })

const storedPurchase = (productId) =>
  cy.request('/getUserPurchase').then((r) => {
    const mine = list(r.body).filter((p) => p.productId === productId)
    expect(mine.length, `a purchase for product ${productId}`).to.be.greaterThan(0)
    return mine[mine.length - 1]
  })

const productRow = (name) =>
  cy.request('/getUserProduct?q=-1').then((r) => {
    const p = list(r.body).find((x) => x.name === name)
    expect(p, `product ${name}`).to.exist
    return p
  })

const onHand = (productId) =>
  cy.request('/productStockLevels').then((r) => {
    expect(r.body.success, 'productStockLevels').to.eq(true)
    const row = (r.body.levels || {})[String(productId)]
    return row ? Number(row.onHand || 0) : 0
  })

describe('U5 — buying in boxes', () => {
  beforeEach(() => cy.loginAsOwner())

  // ── ⭐ the case the slice exists for ──────────────────────────────────────────────────────────────────

  it('⭐ 10 boxes of 10 at 1000.00 becomes 100 packs at 100.00 each', () => {
    const name = `Box_${uniq()}`

    product(name, 150).then((p) => {
      goodsIn(p.id, { qty: 10, cost: 1000, sell: 150, unit: 'BOX', packsPerBox: 10 })
        .then((r) => {
          expect(r.body.status, `goods in: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq('SUCCESS')

          storedPurchase(p.id).then((row) => {
            expect(Number(row.quantity), '100 packs on the shelf, not 10').to.eq(100)
            const cost = Number(row.bpurchaseRate != null ? row.bpurchaseRate
              : (row.stock || {}).bpurchaseRate)
            expect(cost, 'cost PER PACK').to.eq(100)
          })
        })
    })
  })

  it('⭐ the product\'s last purchase rate becomes 100.00, not 1000.00', () => {
    // THE DEFECT THIS SLICE EXISTS FOR. This is the number U2 reads as unit COGS and the margin guard reads
    // to decide whether to refuse a sale.
    const name = `BoxRate_${uniq()}`

    product(name, 150).then((p) => {
      goodsIn(p.id, { qty: 10, cost: 1000, sell: 150, unit: 'BOX', packsPerBox: 10 })
        .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))

      productRow(name).then((row) => {
        expect(Number(row.lastPurchaseRate), 'cost per pack').to.eq(100)
      })
    })
  })

  it('⭐ on-hand rises by 100 packs, not 10', () => {
    const name = `BoxStock_${uniq()}`

    product(name, 150).then((p) => {
      onHand(p.id).then((before) => {
        goodsIn(p.id, { qty: 10, cost: 1000, sell: 150, unit: 'BOX', packsPerBox: 10 })
          .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))
        onHand(p.id).then((after) => {
          expect(after - before, 'ten boxes of ten').to.be.closeTo(100, 0.0001)
        })
      })
    })
  })

  // ── ⭐ the selling price must not be converted ────────────────────────────────────────────────────────

  it('⭐ the SELLING price is left alone', () => {
    // A purchase restamps the product's selling price from bsellRate. Converting it would reprice the
    // product to a tenth of its shelf price — the same error this slice prevents, running backwards.
    const name = `BoxSell_${uniq()}`

    product(name, 150).then((p) => {
      goodsIn(p.id, { qty: 10, cost: 1000, sell: 150, unit: 'BOX', packsPerBox: 10 })
        .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))

      productRow(name).then((row) => {
        expect(Number(row.sellingPrice), 'a shop prices its shelf in packs').to.eq(150)
      })
    })
  })

  // ── the ordinary purchase — every purchase until a shop uses this ────────────────────────────────────

  it('an ordinary pack purchase is completely unchanged', () => {
    const name = `Plain_${uniq()}`

    product(name, 150).then((p) => {
      goodsIn(p.id, { qty: 10, cost: 100, sell: 150 })
        .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))

      storedPurchase(p.id).then((row) => {
        expect(Number(row.quantity), '10 packs').to.eq(10)
      })
      productRow(name).then((row) => {
        expect(Number(row.lastPurchaseRate), 'unchanged').to.eq(100)
        expect(Number(row.sellingPrice)).to.eq(150)
      })
    })
  })

  // ── refusals, server-side ────────────────────────────────────────────────────────────────────────────

  it('BOX with no packs-per-box is refused, with a reason', () => {
    const name = `NoPpb_${uniq()}`

    product(name, 150).then((p) => {
      goodsIn(p.id, { qty: 10, cost: 1000, sell: 150, unit: 'BOX' }).then((r) => {
        expect(r.body.status, 'must be refused').to.not.eq('SUCCESS')
        expect(`${r.body.message || ''} ${r.body.error || ''}`)
          .to.match(/how many packs are in a box/i)
      })
      // ...and nothing was written.
      onHand(p.id).then((after) => expect(after, 'no stock moved').to.eq(0))
    })
  })

  it('an absurd packs-per-box is refused rather than creating a warehouse', () => {
    const name = `BigPpb_${uniq()}`

    product(name, 150).then((p) => {
      goodsIn(p.id, { qty: 10, cost: 1000, sell: 150, unit: 'BOX', packsPerBox: 100000 }).then((r) => {
        expect(r.body.status, 'must be refused').to.not.eq('SUCCESS')
        expect(`${r.body.message || ''} ${r.body.error || ''}`).to.match(/typo/i)
      })
    })
  })

  it('a zero box cost is refused — it would make every later sale look like pure profit', () => {
    const name = `ZeroBox_${uniq()}`

    product(name, 150).then((p) => {
      goodsIn(p.id, { qty: 10, cost: 0, sell: 150, unit: 'BOX', packsPerBox: 10 }).then((r) => {
        expect(r.body.status, 'must be refused').to.not.eq('SUCCESS')
        expect(`${r.body.message || ''} ${r.body.error || ''}`).to.match(/cost of one box/i)
      })
    })
  })

  // ── ⭐ the end-to-end proof ───────────────────────────────────────────────────────────────────────────

  it('⭐ the margin after a box purchase is right — the whole point', () => {
    // Bought at 100/pack (via a 1000 box of 10), sold at 150 → margin 50.
    // With the old tenfold error the recorded cost would be 1000 and the margin −850, which the guard reads.
    const name = `BoxMargin_${uniq()}`

    product(name, 150).then((p) => {
      goodsIn(p.id, { qty: 10, cost: 1000, sell: 150, unit: 'BOX', packsPerBox: 10 })
        .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))

      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        failOnStatusCode: false,
        body: {
          customer: { name: `BoxCust_${uniq()}`, contact: '03009999999' },
          sales: [{ productId: p.id, quantity: 1, sellRate: 150, totalAmount: 150, netAmount: 150 }],
          tenders: [{ method: 'CASH', amount: 150 }],
          paidAmount: 150, grandTotal: 150, idempotencyKey: `cy-boxm-${uniq()}`,
        },
      }).then((r) => {
        expect(r.body.status, `a profitable sale must NOT be refused: ${JSON.stringify(r.body).slice(0, 220)}`)
          .to.eq('SUCCESS')
      })

      cy.request('/getUserSell').then((r) => {
        const line = list(r.body).filter((x) => String(x.productId) === String(p.id)).pop()
        expect(line, 'the sale line').to.exist
        const margin = Number(line.netAmount) - Number(line.costPrice) * Number(line.quantity)
        expect(margin, '150 − 100 × 1').to.be.closeTo(50, 0.01)
      })
    })
  })
})
