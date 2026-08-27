/**
 * U2 — selling a broken pack, end to end through the real sale saga.
 *
 * Design: microservices/docs/slices/u2-loose-sale-arithmetic.md
 *
 * WHAT THIS GATE IS FOR.
 *
 * The failure this slice can produce is not a crash. It is a line priced per pack instead of per piece — a
 * customer charged 120.00 for five tablets, or 12.00 for a whole pack — and **nothing in the system would
 * object**, because both are perfectly well-formed sales that post cleanly to the books.
 *
 * So these cases assert the MONEY and the STOCK, never merely that the new columns hold something.
 *
 * There is no till UI yet (that is U3). Driving it through the API is deliberate: the arithmetic gets proved
 * before a screen makes it convenient.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/sell-loose.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => body.collection || body.data || []

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success,
      `saveBusinessConfig ${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))

/** Create a product with pack rules, and assert it — a fixture that fails silently fails the wrong test. */
const packProduct = (name, price, packSize, allowLoose = true) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name, sellingPrice: price, unit: 'pack', packSize,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      expect(pr.body.status, 'product list read').to.eq('SUCCESS')
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored').to.exist
      expect(p.packSize, 'pack size came back — U1 must be deployed for this gate to mean anything').to.eq(packSize)
      return p
    })
  })

/**
 * On-hand for one product, straight off the screen's own endpoint.
 *
 * `/productStockLevels` answers `{success, levels: { "<productId>": {onHand, sellable, held, expired} }}` —
 * a MAP keyed by product id, not a list of rows. Verified against the running service rather than assumed;
 * guessing this shape is how a stock assertion silently reads 0 for every product and passes.
 *
 * onHand rather than sellable: a hold placed by a sale in flight subtracts from `sellable`, which would make
 * this measure the reservation rather than the movement.
 */
const onHand = (productId) =>
  cy.request('/productStockLevels').then((r) => {
    expect(r.body.success, `productStockLevels: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    const row = (r.body.levels || {})[String(productId)]
    return row ? Number(row.onHand || 0) : 0
  })

/**
 * Stock in, so the sale has something to reserve. Loose selling reserves from ordinary stock.
 *
 * ⚠ `/addPurchase` is FORM-encoded with flat `stock.*` fields and answers `{status:"SUCCESS"}` — it is not a
 * JSON `purchases[]` payload and it has no `success` flag. Copied from `purchase-batch-prefill.cy.js`, which
 * is green, rather than guessed: the first version of this helper failed all 12 cases with
 * "quantity: Blank/Null Not Allowed" before a single line of U2 was exercised.
 *
 * Then POLLED, because the stock-in settles through the inventory service and is not visible the instant the
 * purchase returns. Asserting on-hand immediately would make every stock case flaky in a way that reads like
 * a pricing bug.
 *
 * ⚠⚠ `sellPrice` IS NOT DECORATION — A PURCHASE REPRICES THE PRODUCT.
 *
 * `PurchaseService.stampRatesOnProduct` pushes `stock.bsellRate` into the catalog as the product's SELLING
 * PRICE (the "last rates" feature — stamp at write, don't derive on read). So a stock-in fixture silently
 * overwrites whatever price the test just set up.
 *
 * The first version of this helper derived it as `cost * 1.2`. **That was wrong in all twelve cases and
 * eleven of them passed anyway**, because cost 100 x 1.2 happened to equal the 120.00 the tests wanted. Only
 * the pack-of-3 case — cost 80, price 100 — diverged, and it failed with a tablet priced at 32.00 (96/3)
 * instead of 33.34 (100/3). A coincidence in the fixture was holding up eleven green assertions.
 *
 * So the price is passed EXPLICITLY and must match the product's, which makes the restamp a no-op and the
 * fixture's intent visible at every call site.
 */
const stockIn = (productId, qty, cost, sellPrice) => {
  const batch = `LB${uniq()}`
  expect(sellPrice, 'stockIn needs the product price — a purchase RESTAMPS it').to.be.a('number')
  return cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: qty, 'stock.batchNo': batch,
      'stock.bpurchaseRate': cost, 'stock.bsellRate': sellPrice,
      totalAmount: qty * cost, netAmount: qty * cost, purchaseInvoiceNo: `LOOSE-${uniq()}` },
  }).then((r) => {
    expect(r.body.status, `stock in: ${JSON.stringify(r.body).substring(0, 200)}`).to.eq('SUCCESS')
    const settle = (tries) => onHand(productId).then((have) => {
      if (have >= qty || tries <= 0) {
        expect(have, `stock never appeared for product ${productId}`).to.be.gte(qty)
        return have
      }
      return cy.wait(500).then(() => settle(tries - 1))
    })
    return settle(10)
  })
}

/**
 * ⚠ THE SALE ENDPOINTS ANSWER WITH A `GenericResponse`, WHICH HAS NO `success` FIELD.
 *
 *   { message, error, status: "SUCCESS" | "ERROR", object, collection }
 *
 * `r.body.success` is therefore ALWAYS `undefined`, and that cuts both ways: `.to.eq(true)` fails on a
 * perfectly good sale, and — far worse — `.to.not.eq(true)` **passes for every possible outcome**, including
 * a sale that completed when it should have been refused. Three refusal cases in this spec reported green
 * while asserting nothing at all.
 *
 * So the envelope is read through these two helpers and nowhere else. Same family as the `collection`/`data`
 * trap: GenericResponse puts lists in `collection`, and a helper that quietly tolerates the wrong shape
 * reports a passing test instead of a broken one.
 */
const expectSale = (r) => {
  expect(r.body.status, `sale should have succeeded: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq('SUCCESS')
  return r
}

const expectRefused = (r, pattern) => {
  expect(r.body.status, `sale should have been REFUSED but was recorded: ${JSON.stringify(r.body).slice(0, 220)}`)
    .to.eq('ERROR')
  expect(`${r.body.message || ''} ${r.body.error || ''}`, 'the refusal must say why').to.match(pattern)
  return r
}

/** Sell `lines` to a walk-in and return the response body. */
const sell = (lines, opts = {}) => {
  const total = opts.total
  return cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      customer: { name: `Walkin_${uniq()}`, contact: '03009999999' },
      sales: lines,
      tenders: [{ method: 'CASH', amount: total }],
      paidAmount: total, grandTotal: total,
      idempotencyKey: opts.key || `cy-loose-${uniq()}`,
    },
  })
}

/**
 * The stored LINES of an invoice, read back from the sale history — the books, not the response.
 *
 * ⚠ `/getUserSell` returns a FLAT list: each row IS a sale line, carrying its `customerHistory` (and so its
 * invoiceNo) alongside. It is NOT a list of invoices with a nested `sales[]`. Verified against the running
 * service — reading it the other way yields `undefined` and every money assertion silently compares NaN.
 */
const linesOf = (invoiceNo) =>
  cy.request('/getUserSell').then((r) => {
    const rows = list(r.body).filter((row) => {
      const ch = row.customerHistory || {}
      return ch.invoiceNo === invoiceNo
    })
    expect(rows.length, `invoice ${invoiceNo} has lines in the sale history`).to.be.greaterThan(0)
    return rows
  })

/** The single line of a one-line invoice. */
const lineOf = (invoiceNo) => linesOf(invoiceNo).then((rows) => {
  expect(rows.length, 'a one-line invoice').to.eq(1)
  return rows[0]
})

/** addSell answers with the invoice number in `object` (GenericResponse), not `invoiceNo`. */
const invoiceFrom = (r) => {
  const no = r.body.object || r.body.invoiceNo
  expect(no, `addSell returned no invoice number: ${JSON.stringify(r.body).slice(0, 250)}`).to.be.a('string')
  return no
}

describe('U2 — selling a broken pack', () => {
  beforeEach(() => cy.loginAsOwner())

  after(() => {
    // Leave no server state behind: looseMarkupPct is ORG-WIDE, and a spec that leaves it at 10 silently
    // reprices every later loose sale in the suite. (The period-close incident: a server-wide switch left set
    // reddened every sale spec that followed it.)
    cy.loginAsOwner()
    setConfig('pos.sale.looseMarkupPct', '0')
  })

  // ── ⭐ the case the slice exists for ──────────────────────────────────────────────────────────────────

  it('⭐ five tablets of a 120.00 pack of 10 cost 60.00 — and the books agree', () => {
    const name = `Loose_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)

      sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 5 }], { total: 60 }).then((r) => {
        expectSale(r)
        lineOf(invoiceFrom(r)).then((line) => {
          expect(Number(line.netAmount), 'THE CUSTOMER PAYS SIXTY').to.eq(60)
          expect(Number(line.quantity), 'half a pack left the shelf').to.be.closeTo(0.5, 0.0001)
          expect(Number(line.sellRate), 'per SELLING unit — the identity every report sums').to.eq(120)

          // The invariant. An earlier draft of the design stored quantity 0.5 WITH rate 12.00, which totals
          // 6.00 — a tenfold variance in every invoice, report, tax return and audit export.
          expect(Number(line.quantity) * Number(line.sellRate)).to.be.closeTo(Number(line.netAmount), 0.01)

          // The customer's version of the same sale.
          expect(line.soldUnit, 'what the customer bought').to.eq('LOOSE')
          expect(Number(line.soldQuantity), 'five tablets').to.eq(5)
          expect(Number(line.soldRate), 'per tablet, for the receipt').to.eq(12)
          expect(Number(line.packSizeSnapshot), 'frozen at the sale').to.eq(10)
        })
      })
    })
  })

  it('⭐ stock falls by half a pack, not by five packs', () => {
    // The other way this slice can be wrong: the money right and the shelf destroyed. Five packs removed
    // instead of half means the shop is 4.5 packs short and will not find out until it counts.
    const name = `LooseStock_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      onHand(p.id).then((before) => {
        sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 5 }], { total: 60 }).then((r) => {
          expectSale(r)
          onHand(p.id).then((after) => {
            expect(before - after, 'exactly half a pack').to.be.closeTo(0.5, 0.0001)
          })
        })
      })
    })
  })

  // ── nothing changes for a shop that never breaks a pack ──────────────────────────────────────────────

  it('an ordinary pack sale is unchanged, and records no unit at all', () => {
    // A default is not a decision. `soldUnit` must stay NULL on a line nobody described, so a historical row
    // is never mistaken for one that was explicitly sold as a pack.
    const name = `PlainSale_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sell([{ productId: p.id, quantity: 2, sellRate: 120, totalAmount: 240, netAmount: 240 }], { total: 240 })
        .then((r) => {
          expectSale(r)
          lineOf(invoiceFrom(r)).then((line) => {
            expect(Number(line.quantity)).to.eq(2)
            expect(Number(line.sellRate)).to.eq(120)
            expect(Number(line.netAmount)).to.eq(240)
            expect(line.soldUnit, 'an undescribed line stays undescribed').to.be.oneOf([null, undefined, ''])
          })
        })
    })
  })

  // ── whole packs are priced as packs ──────────────────────────────────────────────────────────────────

  it('⭐ ten tablets of a ten-pack costs the pack price, even with a markup set', () => {
    // With markup 10 the naive answer is 10 × 13.20 = 132.00 for goods on the shelf at 120.00 — and the
    // customer can see both prices. This is the case that makes a shop look like it is overcharging.
    const name = `FullPack_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '10')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 10 }], { total: 120 }).then((r) => {
        expectSale(r)
        lineOf(invoiceFrom(r)).then((line) => {
          expect(Number(line.netAmount), 'the pack price, markup NOT applied').to.eq(120)
          expect(Number(line.quantity)).to.be.closeTo(1, 0.0001)
        })
      })
    })
  })

  it('25 tablets of a ten-pack is 2 packs + 5 loose', () => {
    const name = `Mixed_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '10')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      // 2 × 120.00 (packs, no markup) + 5 × 13.20 (loose, marked up) = 306.00
      sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 25 }], { total: 306 }).then((r) => {
        expectSale(r)
        lineOf(invoiceFrom(r)).then((line) => {
          expect(Number(line.netAmount)).to.eq(306)
          expect(Number(line.quantity)).to.be.closeTo(2.5, 0.0001)
          expect(Number(line.quantity) * Number(line.sellRate)).to.be.closeTo(306, 0.01)
        })
      })
    })
  })

  it('the markup lifts the loose price only', () => {
    const name = `Markup_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '10')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 5 }], { total: 66 }).then((r) => {
        expectSale(r)
        lineOf(invoiceFrom(r)).then((line) => {
          expect(Number(line.netAmount), '5 × 13.20').to.eq(66)
          expect(Number(line.soldRate), 'per tablet, marked up').to.eq(13.2)
        })
      })
    })
  })

  // ── rounding: where money leaks quietly ──────────────────────────────────────────────────────────────

  it('⭐ a pack of 3 rounds in the shop\'s favour — one tablet is 33.34, not 33.33', () => {
    // 100 / 3 = 33.333... Rounding DOWN means three sold singly return 99.99 for goods priced 100.00 — a
    // loss on every broken pack, invisible, on the fastest-moving lines in the shop.
    const name = `Thirds_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 100, 3).then((p) => {
      stockIn(p.id, 10, 80, 100)
      sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 1 }], { total: 33.34 }).then((r) => {
        expectSale(r)
        lineOf(invoiceFrom(r)).then((line) => {
          expect(Number(line.netAmount), 'rounded UP to the paisa').to.eq(33.34)
          // The residue lands on `quantity`, where nothing is charged from it — not on the bill.
          expect(Number(line.quantity) * Number(line.sellRate)).to.be.closeTo(33.34, 0.01)
        })
      })
    })
  })

  // ── refusals, all server-side ────────────────────────────────────────────────────────────────────────

  it('a product that may not be split refuses a loose line', () => {
    // The permission is the whole control, and it is enforced here rather than in a screen — U3 does not
    // exist yet, and an API caller or import must meet the same rule.
    const name = `NoSplit_${uniq()}`

    packProduct(name, 120, 10, false).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 5 }], { total: 60 }).then((r) => {
        expectRefused(r, /not sold by the piece/i)
      })
    })
  })

  it('half a tablet is refused, not rounded', () => {
    const name = `HalfTab_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 2.5 }], { total: 30 }).then((r) => {
        expectRefused(r, /whole tablets/i)
      })
    })
  })

  it('a refusal leaves NOTHING behind — no invoice, no stock moved', () => {
    // A business refusal must not half-happen. It is thrown before anything is reserved or written, and it
    // must not poison the surrounding transaction either — a tidy "not sold by the piece" turning into
    // "Transaction silently rolled back" is a defect this codebase has already paid for once.
    const name = `Clean_${uniq()}`

    packProduct(name, 120, 10, false).then((p) => {
      stockIn(p.id, 10, 100, 120)
      onHand(p.id).then((before) => {
        sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 5 }], { total: 60 }).then((r) => {
          expectRefused(r, /not sold by the piece/i)
          expect(JSON.stringify(r.body), 'a clean refusal, not a rollback message')
            .to.not.match(/rollback-only/i)
          onHand(p.id).then((after) => {
            expect(after, 'no stock was touched').to.be.closeTo(before, 0.0001)
          })
        })
      })
    })
  })

  // ── the guards that must not have been fooled by the unit change ─────────────────────────────────────

  it('the margin guard still sees a loose line for what it is', () => {
    // assertMarginPolicy compares MONEY to MONEY (netAmount vs costPrice × quantity), so a loose line needs
    // no special case: 60.00 against 100.00 × 0.5 = 50.00. This case exists because a design that stored the
    // rate per piece WOULD have broken it — it would compare 12 against 100 and refuse every loose sale.
    const name = `Margin_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)     // cost 100/pack, sell 120/pack -> a loose sale is still profitable
      sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 5 }], { total: 60 }).then((r) => {
        expect(r.body.status, 'a profitable loose sale must NOT be refused: ' + JSON.stringify(r.body))
          .to.eq('SUCCESS')
      })
    })
  })

  it('a mixed basket — one pack and five tablets of the same product on one invoice', () => {
    const name = `Basket_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      onHand(p.id).then((before) => {
        sell([
          { productId: p.id, quantity: 1, sellRate: 120, totalAmount: 120, netAmount: 120 },
          { productId: p.id, soldUnit: 'LOOSE', soldQuantity: 5 },
        ], { total: 180 }).then((r) => {
          expectSale(r)
          linesOf(invoiceFrom(r)).then((rows) => {
            expect(rows.length, 'two lines on one invoice').to.eq(2)
            const sum = rows.reduce((a, l) => a + Number(l.netAmount || 0), 0)
            expect(sum, '120 + 60').to.eq(180)
            expect(rows.filter((l) => l.soldUnit === 'LOOSE').length, 'one of them is loose').to.eq(1)
          })
          onHand(p.id).then((after) => {
            expect(before - after, 'one and a half packs').to.be.closeTo(1.5, 0.0001)
          })
        })
      })
    })
  })
})
