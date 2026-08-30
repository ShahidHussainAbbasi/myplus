/**
 * U6 — counting the shelf, and taking tablets back.
 *
 * Design: microservices/docs/slices/u6-counting-and-giving-back.md
 *
 * ⚠ WRITTEN TO DISCOVER, NOT TO CONFIRM — AND IT FOUND SOMETHING.
 *
 * U6's review predicted loose returns already worked: a return is an edit of the invoice, `updateSell`
 * shares `buildLines`, and U2 put the loose conversion there. **It was half right.**
 *
 * The MONEY was already correct — the credit note, the refund, the invoice total, all of them, first run.
 * The STOCK was not: the delta was computed from the raw DTO, where `SellDTO.quantity` defaults to 1F, so a
 * loose return took −1 PACK instead of −0.2 and on-hand went 9.5 → 9.0 instead of 9.8. Only the shelf was
 * wrong, which is the kind of error a shop finds weeks later at a count with no way to trace it.
 *
 * The last four cases are U10's: they read PER-BATCH on-hand, because total on-hand cannot see a lot defect —
 * a returned unit lands in *a* batch either way.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/sell-loose-return.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}`).to.eq(true))

const packProduct = (name, price, packSize, allowLoose = true) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name, sellingPrice: price, unit: 'pack', packSize,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored').to.exist
      return p
    })
  })

/** ⚠ A purchase RESTAMPS the selling price — pass the intended price (U2 §13.4c). */
const stockIn = (productId, qty, cost, sellPrice) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: qty, 'stock.batchNo': `RT${uniq()}`,
      'stock.bpurchaseRate': cost, 'stock.bsellRate': sellPrice,
      totalAmount: qty * cost, netAmount: qty * cost, purchaseInvoiceNo: `RET-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).substring(0, 200)}`).to.eq('SUCCESS'))

const sellLoose = (productId, pieces, total) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customer: { name: `Ret_${uniq()}`, contact: '03009999999' },
      sales: [{ productId, soldUnit: 'LOOSE', soldQuantity: pieces }],
      tenders: [{ method: 'CASH', amount: total }],
      paidAmount: total, grandTotal: total, idempotencyKey: `cy-ret-${uniq()}`,
    },
  }).then((r) => {
    expect(r.body.status, `sale: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
    return r.body.object
  })

const invoiceOf = (invoiceNo) =>
  cy.request('/getUserSell').then((r) => {
    const rows = list(r.body).filter((x) => (x.customerHistory || {}).invoiceNo === invoiceNo)
    expect(rows.length, `invoice ${invoiceNo}`).to.be.greaterThan(0)
    return rows
  })

/** A return: re-submit the invoice with a REDUCED piece count. The difference becomes a credit note. */
const returnPieces = (invoiceNo, productId, keepPieces, newTotal) =>
  invoiceOf(invoiceNo).then((rows) => {
    const ch = rows[0].customerHistory
    return cy.request({
      method: 'POST', url: '/updateSell', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: {
        customer_history_id: ch.customer_history_id,
        customer: ch.customer,
        sales: [{ productId, soldUnit: 'LOOSE', soldQuantity: keepPieces }],
        tenders: [{ method: 'CASH', amount: newTotal }],
        paidAmount: newTotal, grandTotal: newTotal,
      },
    })
  })

/** Stock in against a NAMED batch, so a return can be traced back to it. */
const stockInBatch = (productId, qty, batch) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: qty, 'stock.batchNo': batch,
      'stock.bpurchaseRate': 100, 'stock.bsellRate': 120,
      totalAmount: qty * 100, netAmount: qty * 100, purchaseInvoiceNo: `BR-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).substring(0, 200)}`).to.eq('SUCCESS'))

/**
 * On-hand for ONE batch — the whole point of U10.
 *
 * Total on-hand cannot see this defect: a returned unit lands in *a* batch either way, so the total is right
 * whether the lot is right or not. Only a per-batch read distinguishes "back where it came from" from "in a
 * fresh, undated entry".
 */
const batchOnHand = (productId, batch) =>
  cy.request(`/getStockByBatch?batchNo=${batch}&productId=${productId}`)
    .then((r) => Number((r.body && r.body.stock) || 0))

const onHand = (productId) =>
  cy.request('/productStockLevels').then((r) => {
    expect(r.body.success, 'productStockLevels').to.eq(true)
    const row = (r.body.levels || {})[String(productId)]
    return row ? Number(row.onHand || 0) : 0
  })

describe('U6 — counting the shelf, and taking tablets back', () => {
  beforeEach(() => cy.loginAsOwner())

  after(() => { cy.loginAsOwner(); setConfig('pos.sale.looseMarkupPct', '0') })

  // ── B · the shelf, in the counter's language (pure logic) ────────────────────────────────────────────

  it('⭐ on-hand reads "9 + 5 tablets", not 9.5', () => {
    // 9.5 is arithmetically true and operationally useless: nobody counts half a pack.
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => {
      expect(w.shelfText, 'loose-format.js exposes the shelf renderer').to.be.a('function')

      expect(w.shelfText(9.5, 10, 'tablets').text).to.eq('9 + 5 tablets')
      expect(w.shelfText(9.8, 10, 'tablets').text).to.eq('9 + 8 tablets')
      expect(w.shelfText(10, 10, 'tablets').text).to.eq('10')
      expect(w.shelfText(0, 10, 'tablets').text).to.eq('0')
    })
  })

  it('an ordinary product still reads as a plain number', () => {
    // The regression that protects every shop that never breaks a pack.
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => {
      expect(w.shelfText(10, null, '').text).to.eq('10')
      expect(w.shelfText(7, 1, '').text, 'a pack of one is not divisible either').to.eq('7')
    })
  })

  it('⭐ the rounding residue does not become a phantom tablet', () => {
    // Stock is kept in SELLING units, so a third of a pack stores as 0.3333 and three single sales from a
    // pack of 3 leave 0.0001 behind. Rendered naively that reads "0 + 0.0003 tablets" — worse than the
    // number it replaces, because it looks like a defect.
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => {
      expect(w.shelfText(0.0001, 3, 'tablets').text).to.eq('0')
      expect(w.shelfText(9.99999, 10, 'tablets').text, 'not "9 + 10 tablets"').to.eq('10')
      expect(w.shelfText(9.3333, 3, 'tablets').text).to.eq('9 + 1 tablets')
    })
  })

  // ── A · does the return already work? ───────────────────────────────────────────────────────────────

  it('⭐ sell 5 tablets, take 3 back — credited at 36.00, not 360.00', () => {
    const name = `Ret_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sellLoose(p.id, 5, 60).then((invoiceNo) => {
        returnPieces(invoiceNo, p.id, 2, 24).then((r) => {
          expect(r.body.status, `the return: ${JSON.stringify(r.body).slice(0, 250)}`).to.eq('SUCCESS')

          invoiceOf(invoiceNo).then((rows) => {
            const line = rows[0]
            expect(Number(line.soldQuantity), 'two tablets remain on the invoice').to.eq(2)
            expect(Number(line.netAmount), '2 × 12.00 — the customer keeps two').to.eq(24)
            // The credit is the difference: 60.00 − 24.00 = 36.00, i.e. 3 × 12.00.
            expect(Number(line.quantity), 'and the shelf sees 0.2 of a pack').to.be.closeTo(0.2, 0.0001)
          })
        })
      })
    })
  })

  it('⭐ stock comes back as 0.3 of a pack, not 3 packs', () => {
    const name = `RetStock_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sellLoose(p.id, 5, 60).then((invoiceNo) => {
        onHand(p.id).then((afterSale) => {
          expect(afterSale, 'half a pack gone').to.be.closeTo(9.5, 0.0001)
          returnPieces(invoiceNo, p.id, 2, 24).then((r) => {
            expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
            onHand(p.id).then((afterReturn) => {
              expect(afterReturn - afterSale, 'three tablets back').to.be.closeTo(0.3, 0.0001)
            })
          })
        })
      })
    })
  })

  it('a larger partial return puts back exactly what came back', () => {
    /*
     * 5 tablets sold, 4 returned, 1 kept. The bigger sibling of the case above, and it exists because a
     * conversion bug that happens to be right at one ratio is not fixed.
     *
     * ⚠ NOT "return everything by editing the line to zero". That is an ambiguous instruction — a line with
     * no quantity is not obviously a return rather than a mistake — and a shop returning a whole sale VOIDS
     * it. Asserting behaviour for an input the server may rightly refuse would be testing my guess about it.
     */
    const name = `RetAll_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sellLoose(p.id, 5, 60).then((invoiceNo) => {
        onHand(p.id).then((afterSale) => {
          returnPieces(invoiceNo, p.id, 1, 12).then((r) => {
            expect(r.body.status, JSON.stringify(r.body).slice(0, 220)).to.eq('SUCCESS')
            onHand(p.id).then((after) => {
              expect(after - afterSale, 'four tablets back = 0.4 of a pack').to.be.closeTo(0.4, 0.0001)
            })
            invoiceOf(invoiceNo).then((rows) => {
              expect(Number(rows[0].soldQuantity), 'one tablet kept').to.eq(1)
              expect(Number(rows[0].netAmount), '1 × 12.00').to.eq(12)
            })
          })
        })
      })
    })
  })

  // ── C · what cannot be given back ───────────────────────────────────────────────────────────────────

  it('⭐ a sealed pack cannot be handed back as loose tablets', () => {
    /*
     * The refund arbitrage: buy a sealed pack of 10 for 120.00, hand back 7 tablets "loose" at 13.20 =
     * 92.40, and keep 3 tablets for 27.60 — which the shop itself sells for 39.60. The gap is the shop's
     * loss, on every such transaction, and nothing on the invoice looks wrong.
     */
    const name = `PackRet_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '10')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        failOnStatusCode: false,
        body: {
          customer: { name: `PackCust_${uniq()}`, contact: '03009999999' },
          sales: [{ productId: p.id, quantity: 1, sellRate: 120, totalAmount: 120, netAmount: 120 }],
          tenders: [{ method: 'CASH', amount: 120 }],
          paidAmount: 120, grandTotal: 120, idempotencyKey: `cy-pr-${uniq()}`,
        },
      }).then((sr) => {
        expect(sr.body.status, JSON.stringify(sr.body).slice(0, 200)).to.eq('SUCCESS')
        returnPieces(sr.body.object, p.id, 3, 39.6).then((r) => {
          expect(r.body.status, 'the loose return of a sealed pack must be refused').to.eq('ERROR')
          expect(`${r.body.message || ''} ${r.body.error || ''}`)
            .to.match(/sold as a whole pack/i)
        })
      })
    })
  })

  it('half a tablet cannot be returned', () => {
    const name = `HalfRet_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sellLoose(p.id, 5, 60).then((invoiceNo) => {
        returnPieces(invoiceNo, p.id, 2.5, 30).then((r) => {
          expect(r.body.status, 'a fraction of a piece is refused').to.eq('ERROR')
          expect(`${r.body.message || ''} ${r.body.error || ''}`).to.match(/whole tablets/i)
        })
      })
    })
  })

  it('a pack sale still edits exactly as it always did', () => {
    // The regression: U6 added a refusal to the edit path, and it must not touch an ordinary invoice.
    const name = `PlainEdit_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        failOnStatusCode: false,
        body: {
          customer: { name: `PlainC_${uniq()}`, contact: '03009999999' },
          sales: [{ productId: p.id, quantity: 3, sellRate: 120, totalAmount: 360, netAmount: 360 }],
          tenders: [{ method: 'CASH', amount: 360 }],
          paidAmount: 360, grandTotal: 360, idempotencyKey: `cy-pe-${uniq()}`,
        },
      }).then((sr) => {
        expect(sr.body.status, JSON.stringify(sr.body).slice(0, 200)).to.eq('SUCCESS')
        invoiceOf(sr.body.object).then((rows) => {
          const ch = rows[0].customerHistory
          cy.request({
            method: 'POST', url: '/updateSell', headers: { 'Content-Type': 'application/json' },
            failOnStatusCode: false,
            body: {
              customer_history_id: ch.customer_history_id, customer: ch.customer,
              sales: [{ productId: p.id, quantity: 2, sellRate: 120, totalAmount: 240, netAmount: 240 }],
              tenders: [{ method: 'CASH', amount: 240 }], paidAmount: 240, grandTotal: 240,
            },
          }).then((u) => {
            expect(u.body.status, `an ordinary pack return: ${JSON.stringify(u.body).slice(0, 220)}`)
              .to.eq('SUCCESS')
          })
        })
      })
    })
  })

  // ── U10: a returned tablet goes back to the batch it came from ───────────────────────────────────────

  it('⭐ a loose return restocks the ORIGINAL batch, not a fresh one', () => {
    /*
     * Before U10 an edit-based return called importStock with a bare line — a new StockEntry with no lot and
     * no expiry. The QUANTITY was right, which is why nothing looked wrong; what was lost was FEFO order and
     * recall traceability.
     *
     * Total on-hand cannot prove this. Only the batch can.
     */
    const name = `BatchRet_${uniq()}`
    const batch = `BA${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockInBatch(p.id, 10, batch)
      batchOnHand(p.id, batch).then((before) => {
        expect(before, 'ten packs in this batch').to.be.closeTo(10, 0.0001)

        sellLoose(p.id, 5, 60).then((invoiceNo) => {
          batchOnHand(p.id, batch).then((afterSale) => {
            expect(afterSale, 'half a pack came out of THIS batch').to.be.closeTo(9.5, 0.0001)

            returnPieces(invoiceNo, p.id, 2, 24).then((r) => {
              expect(r.body.status, JSON.stringify(r.body).slice(0, 220)).to.eq('SUCCESS')
              batchOnHand(p.id, batch).then((afterReturn) => {
                expect(afterReturn, 'three tablets went BACK INTO THE SAME BATCH — 9.5 + 0.3')
                  .to.be.closeTo(9.8, 0.0001)
              })
            })
          })
        })
      })
    })
  })

  it('repeated partial returns never over-restore the batch', () => {
    // Inventory caps each return at `picked − alreadyReturned`. Asserted through the EDIT path, because that
    // is the path U10 changed and the cap lives somewhere else.
    const name = `BatchCap_${uniq()}`
    const batch = `BC${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockInBatch(p.id, 10, batch)
      sellLoose(p.id, 5, 60).then((invoiceNo) => {
        returnPieces(invoiceNo, p.id, 3, 36)
          .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))
        returnPieces(invoiceNo, p.id, 1, 12)
          .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))

        // Sold 5, kept 1 -> 4 returned. The batch can never hold more than the 10 it started with.
        batchOnHand(p.id, batch).then((after) => {
          expect(after, 'never more than the batch ever held').to.be.lte(10.0001)
          expect(after, 'four tablets back out of five sold').to.be.closeTo(9.9, 0.0001)
        })
      })
    })
  })

  it('the TOTAL on-hand is unchanged by any of this', () => {
    // The regression: U10 moved where stock lands, not how much. If the total ever differs from U6's
    // behaviour, the batch routing has created or destroyed stock.
    const name = `BatchTotal_${uniq()}`
    const batch = `BT${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockInBatch(p.id, 10, batch)
      sellLoose(p.id, 5, 60).then((invoiceNo) => {
        onHand(p.id).then((afterSale) => {
          returnPieces(invoiceNo, p.id, 2, 24).then(() => {
            onHand(p.id).then((afterReturn) => {
              expect(afterReturn - afterSale, 'three tablets, wherever they landed').to.be.closeTo(0.3, 0.0001)
            })
          })
        })
      })
    })
  })

  it('a PACK return also goes back to its batch — this is not loose-specific', () => {
    const name = `BatchPack_${uniq()}`
    const batch = `BP${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockInBatch(p.id, 10, batch)
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        failOnStatusCode: false,
        body: {
          customer: { name: `BP_${uniq()}`, contact: '03009999999' },
          sales: [{ productId: p.id, quantity: 3, sellRate: 120, totalAmount: 360, netAmount: 360 }],
          tenders: [{ method: 'CASH', amount: 360 }],
          paidAmount: 360, grandTotal: 360, idempotencyKey: `cy-bp-${uniq()}`,
        },
      }).then((sr) => {
        expect(sr.body.status, JSON.stringify(sr.body).slice(0, 200)).to.eq('SUCCESS')
        batchOnHand(p.id, batch).then((afterSale) => {
          expect(afterSale, 'three packs out').to.be.closeTo(7, 0.0001)
          invoiceOf(sr.body.object).then((rows) => {
            const ch = rows[0].customerHistory
            cy.request({
              method: 'POST', url: '/updateSell', headers: { 'Content-Type': 'application/json' },
              failOnStatusCode: false,
              body: {
                customer_history_id: ch.customer_history_id, customer: ch.customer,
                sales: [{ productId: p.id, quantity: 1, sellRate: 120, totalAmount: 120, netAmount: 120 }],
                tenders: [{ method: 'CASH', amount: 120 }], paidAmount: 120, grandTotal: 120,
              },
            }).then((u) => expect(u.body.status, JSON.stringify(u.body).slice(0, 220)).to.eq('SUCCESS'))
            batchOnHand(p.id, batch).then((afterReturn) => {
              expect(afterReturn, 'two packs back into the same batch').to.be.closeTo(9, 0.0001)
            })
          })
        })
      })
    })
  })
})
