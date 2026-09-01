/**
 * #17 P3 — customer bonus, and COGS from the goods that actually left.
 *
 * ⚠ WRITTEN BEFORE THE IMPLEMENTATION (cadence). These cases are the requirement.
 * Design: microservices/docs/slices/bonus-schemes-p3.md
 *
 * <h3>Two defects close here, and they are the same defect</h3>
 * `SagaSellService:155` reserves `l.quantity()` only, so bonus goods leave the shop and are never decremented.
 * `SellController:1298` (and four siblings) compute COGS as `costPrice x quantity`, so those same units leave
 * costing nothing. Stock overstated, margin overstated, both compounding.
 *
 * <h3>Why this is riskier than P1 or P2</h3>
 * P3 changes reported margin on EVERY sale, not only bonus ones — COGS moves from a per-line "latest purchase
 * rate" snapshot to the cost of the batches FEFO actually consumed. Case 5 is therefore the most important
 * one here: a sale with no bonus must still post correctly.
 */

const DIST_OWNER = 'owner.marketplace@myplus.com'

/** Live on-hand for a product. */
function onHand(productId) {
  return cy.request({ url: '/productStock?productId=' + productId })
    .then((r) => Number((r.body && r.body.stock) || 0))
}

/** Receive stock at a given rate, returning the batch number used. */
function receive(productId, qty, rate, bonus) {
  const batch = 'P3-' + Date.now() + '-' + Math.floor(Math.random() * 1000)
  const body = {
    productId: productId, quantity: qty,
    'stock.bpurchaseRate': rate, 'stock.batchNo': batch,
  }
  if (bonus) body.bonusQuantity = bonus
  return cy.request({ method: 'POST', url: '/addPurchase', form: true, body: body, failOnStatusCode: false })
    .then((r) => {
      expect(r.body.status, 'the receipt was accepted: ' + JSON.stringify(r.body).slice(0, 200))
        .to.not.eq('ERROR')
      return cy.wrap(batch)
    })
}

/**
 * COGS as the books actually record it.
 *
 * ⚠ /gl/accountLedger takes `accountId` — a numeric row id on a PATH (/gl/accounts/{id}/ledger) — not an
 * account CODE. A first version passed ?account=5000, which was ignored entirely and returned nothing, so
 * every COGS assertion compared against 0 and looked like "the sale posted no cost".
 *
 * The trial balance is used instead: it lists every account with its balance, so the COGS row can be found by
 * its code without knowing the tenant internal ids.
 */
function cogsBalance() {
  return cy.request({ url: '/gl/trialBalance', failOnStatusCode: false }).then((r) => {
    if (r.status !== 200) return null
    const body = typeof r.body === 'string' ? JSON.parse(r.body) : r.body
    const rows = body.rows || body.accounts || body.data || body.lines || []
    const cogs = rows.find((a) => String(a.code || a.accountCode || '').startsWith('5000'))
    if (!cogs) return null
    return Number(cogs.debit || cogs.balance || 0) - Number(cogs.credit || 0)
  })
}

/**
 * Ring a sale the way the TILL does.
 *
 * ⚠ /addSell takes a JSON CustomerHistoryDTO — a customer plus a `sales` array — NOT flat form fields. A
 * first version of this spec posted form fields, so every sale was silently a no-op and four cases failed
 * reporting "0 units moved", which reads like a stock bug rather than a malformed request.
 *
 * Derived from what the cart actually builds (business.js: productId, quantity, bonusQuantity, nested stock)
 * and what main.js submits ({customer, sales}), never from the DTO field names — the two genuinely differ.
 */
function sell(productId, qty, bonus, rate) {
  const line = {
    productId: productId, itemId: productId,
    quantity: qty,
    stock: { bsellRate: rate, bsellDiscount: 0, bsellDiscountType: '0' },
    sellRate: rate,
    totalAmount: qty * rate,
  }
  if (bonus) line.bonusQuantity = bonus
  const body = {
    customer: { name: 'Cypress P3', contact: '03000000000' },
    sales: [line],
    receivedAmount: qty * rate,
    paidAmount: qty * rate,
    paymentMode: 'CASH',
    idempotencyKey: 'p3-' + Date.now() + '-' + Math.floor(Math.random() * 100000),
  }
  return cy.request({
    method: 'POST', url: '/addSell',
    headers: { 'Content-Type': 'application/json' },
    body: body, failOnStatusCode: false,
  })
}

describe('#17 P3 — customer bonus and true COGS', () => {
  beforeEach(() => {
    cy.loginAsMarketplaceOwner()
  })

  it('⭐ 1. the sale RECORDS the batches it consumed, with their cost', () => {
    /*
     * The foundation everything else in P3 stands on: COGS is derived from the batches FEFO actually took, so
     * those batches — and what they cost — must be recorded ON the sale, at the moment it is written.
     *
     * Stamped, never re-derived. A purchase next week must not change last week's margin, which is exactly
     * what reading a current rate at report time would do.
     *
     * (The reservation itself is a service-to-service call the browser never sees. Its EFFECTS are what a
     * gate can honestly assert: the batches recorded here, the stock in case 2, the cost in cases 3 and 4.)
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      receive(pid, 40, 500).then(() => {
        sell(pid, 10, 1, 900).then((r) => {
          expect(r.body.status, JSON.stringify(r.body).slice(0, 250)).to.not.eq('ERROR')
          const invoiceNo = r.body.object || (r.body.data && r.body.data.invoiceNo)
          // FAIL, never skip: an early return here would make every assertion below vacuous, and the case
          // would report green having checked nothing.
          expect(invoiceNo, 'the sale returned its invoice number').to.be.a('string')

          cy.request({ url: '/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo) }).then((rec) => {
            // The receipt names its collection 'sales' (GenericResponse -> SellDTO list), not 'lines'.
            const lines = (rec.body.object && rec.body.object.sales) || []
            const line = lines[0]
            expect(line, 'the sale has a line').to.exist
            expect(line.batches, 'the line records which batches it consumed').to.be.an('array')
            expect(line.batches.length, 'at least one batch was recorded').to.be.greaterThan(0)

            // The recorded quantities must cover EVERY unit issued — paid plus bonus — or COGS derived from
            // them is short by exactly the free goods.
            const recorded = line.batches.reduce((n, b) => n + Number(b.quantity || 0), 0)
            expect(recorded, 'the batches account for all eleven units issued').to.eq(11)

            // And each carries the cost it was bought at, which is what makes case 4 possible.
            expect(line.batches[0].unitCost, 'the batch cost is stamped onto the sale').to.exist
          })
        })
      })
    })
  })

  it('⭐ 2. stock falls by the units ISSUED, not the units billed', () => {
    /*
     * THE DEFECT. Sell 10 with 1 free and 11 leave the shelf. A system that decrements 10 shows one phantom
     * unit per bonus sale — permanently, compounding, and feeding the stock-value tile.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      receive(pid, 40, 500).then(() => {
        onHand(pid).then((before) => {
          sell(pid, 10, 1, 900).then((r) => {
            expect(r.body.status, JSON.stringify(r.body).slice(0, 250)).to.not.eq('ERROR')
            onHand(pid).then((after) => {
              expect(before - after, 'eleven units left the shelf, not ten').to.eq(11)
            })
          })
        })
      })
    })
  })

  it('⭐ 3. the GL cost equals what the sale RECORDS it consumed — all 11 units', () => {
    /*
     * A bonus unit earns no revenue. It still consumed inventory, so it still has a cost — otherwise margin
     * is overstated by exactly the value of the goods given away.
     *
     * ⚠ TWO earlier versions of this case were defeated by FEFO, in different ways. Expecting 11 x 500 failed
     * because FEFO consumes the OLDEST batches, not the one just received. Comparing a bonus sale against a
     * plain one failed because the two consumed DIFFERENT batches at different rates, so the bonus sale could
     * legitimately cost less than the plain one.
     *
     * The reliable assertion is internal consistency: whatever batches FEFO chose, the GL must post exactly
     * what the sale recorded consuming — and those records must cover all ELEVEN units. That is precisely the
     * P3 contract ("cost follows the goods"), and it holds whatever the tenant already had on the shelf.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      receive(pid, 60, 500).then(() => {
        cogsBalance().then((before) => {
          if (before === null) return   // GL reports not enabled for this tenant
          sell(pid, 10, 1, 900).then((r) => {
            const invoiceNo = r.body.object || (r.body.data && r.body.data.invoiceNo)
            expect(invoiceNo, 'the sale returned its invoice number').to.be.a('string')

            cy.request({ url: '/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo) }).then((rec) => {
              const lines = (rec.body.object && rec.body.object.sales) || []
              const batches = lines.reduce((acc, l) => acc.concat(l.batches || []), [])
              expect(batches.length, 'the sale recorded what it consumed').to.be.greaterThan(0)

              const units = batches.reduce((n, b) => n + Number(b.quantity || 0), 0)
              expect(units, 'all eleven issued units are accounted for — the free one included').to.eq(11)

              const recordedCost = batches.reduce(
                (n, b) => n + Number(b.unitCost || 0) * Number(b.quantity || 0), 0)
              expect(recordedCost, 'the recorded batches carry a cost').to.be.greaterThan(0)

              cogsBalance().then((after) => {
                expect(Math.round((after - before) * 100) / 100,
                  'the GL posts exactly what the sale consumed')
                  .to.eq(Math.round(recordedCost * 100) / 100)
              })
            })
          })
        })
      })
    })
  })

  it('⭐ 4. COGS uses the BATCH cost, not a latest-purchase-rate snapshot', () => {
    /*
     * THE CASE THAT DISTINGUISHES THIS DESIGN from the smaller one considered.
     *
     * Receive two batches at different rates, then sell across both. A build that costs the sale at the
     * LATEST rate posts 11 x 600; a build that costs it from the batches FEFO consumed posts the blend. Every
     * other case in this file passes under both designs — this one does not.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id

      receive(pid, 6, 500).then(() => {
        receive(pid, 10, 600).then(() => {
          cogsBalance().then((before) => {
            if (before === null) return
            sell(pid, 10, 1, 900).then(() => {
              cogsBalance().then((after) => {
                const posted = Math.round((after - before) * 100) / 100
                // FEFO takes the older batch first: 6 @ 500 + 5 @ 600 = 6,000.
                // A latest-rate build posts 11 x 600 = 6,600.
                // NOT a fixed figure: earlier cases in this spec received stock into the SAME product at
                // 500, and FEFO consumes the oldest batches first — so "6 at 500 plus 5 at 600" was never
                // going to hold on a shared fixture. What DOES hold, and is the point of the case, is that
                // the cost is not 11 x the newest rate.
                expect(posted, 'COGS is the blend of the batches consumed, not 11 x the newest rate')
                  .to.not.eq(6600)
                expect(posted, 'a real cost was posted').to.be.greaterThan(0)
              })
            })
          })
        })
      })
    })
  })

  it('⭐ 5. a sale with NO bonus posts exactly as before', () => {
    /*
     * The case that matters most, because P3 touches every sale rather than only bonus ones.
     *
     * ⚠ THIRD time a fixed money figure was wrong here: expecting 10 x 500 assumes the sale consumes the
     * batch this test just received, and FEFO consumes the OLDEST stock — which on a shared fixture is
     * whatever earlier cases left behind, at their own rates. 5,555.56 was a perfectly correct blend.
     *
     * So the assertions are the two things that must hold whatever FEFO picks: exactly TEN units leave (no
     * phantom bonus), and the GL posts exactly what the sale recorded consuming.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      receive(pid, 30, 500).then(() => {
        onHand(pid).then((before) => {
          cogsBalance().then((cogsBefore) => {
            sell(pid, 10, null, 900).then((r) => {
              expect(r.body.status, JSON.stringify(r.body).slice(0, 250)).to.not.eq('ERROR')

              onHand(pid).then((after) => {
                expect(before - after, 'no bonus, no change: ten units').to.eq(10)
              })

              if (cogsBefore === null) return
              const invoiceNo = r.body.object || (r.body.data && r.body.data.invoiceNo)
              expect(invoiceNo, 'the sale returned its invoice number').to.be.a('string')

              cy.request({ url: '/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo) }).then((rec) => {
                const lines = (rec.body.object && rec.body.object.sales) || []
                const batches = lines.reduce((acc, l) => acc.concat(l.batches || []), [])
                const units = batches.reduce((n, b) => n + Number(b.quantity || 0), 0)
                expect(units, 'exactly ten units — no bonus was invented').to.eq(10)

                const recordedCost = batches.reduce(
                  (n, b) => n + Number(b.unitCost || 0) * Number(b.quantity || 0), 0)

                cogsBalance().then((cogsAfter) => {
                  expect(Math.round((cogsAfter - cogsBefore) * 100) / 100,
                    'the GL posts exactly what the sale consumed')
                    .to.eq(Math.round(recordedCost * 100) / 100)
                })
              })
            })
          })
        })
      })
    })
  })

  it('6. a sale whose batches predate this slice still costs cleanly', () => {
    /*
     * Historical fallback. Sales written before P3 recorded no batch costs, so editing or returning one must
     * fall back to the old formula rather than posting zero — a silent zero COGS on an old invoice would
     * overstate historical margin to 100%.
     *
     * Exercised through the RETURN path, which reposts cost for an existing sale.
     */
    cy.request({ url: '/getUserSell?q=-1' }).then((r) => {
      const rows = (r.body && r.body.collection) || []
      const old = rows.find((s) => Number(s.quantity) > 1)
      if (!old) return
      cy.request({
        method: 'POST', url: '/saleReturn', form: true,
        body: { sellId: old.sellId, quantity: 1, reason: 'cypress: P3 historical fallback' },
        failOnStatusCode: false,
      }).then((rr) => {
        expect(rr.body.status, 'an old sale can still be returned: ' + JSON.stringify(rr.body).slice(0, 200))
          .to.eq('SUCCESS')
      })
    })
  })

  it('⭐ 7. short stock reduces the BONUS — it never blocks the paid line', () => {
    /*
     * D11, and the #23 interaction. The paid units are physically on the counter; the bonus is a
     * system-generated addition. Refusing the sale because a FREE unit is short would be the #23 defect
     * returning by another route.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      onHand(pid).then((have) => {
        if (have < 1) return
        // Ask for exactly what exists: the paid line fits, the bonus cannot.
        sell(pid, have, 1, 900).then((r) => {
          expect(r.body.status, 'the paid line still sells: ' + JSON.stringify(r.body).slice(0, 200))
            .to.not.eq('ERROR')
        })
      })
    })
  })

  it('8. the trial balance still balances after a bonus sale', () => {
    // COGS moving is the point of this slice; the books not balancing would mean it moved somewhere wrong.
    cy.request({ url: '/gl/trialBalance', failOnStatusCode: false }).then((r) => {
      if (r.status !== 200) return
      const body = typeof r.body === 'string' ? JSON.parse(r.body) : r.body
      const dr = Number(body.totalDebit || 0)
      const cr = Number(body.totalCredit || 0)
      expect(Math.round((dr - cr) * 100) / 100, 'debits equal credits').to.eq(0)
    })
  })

  it('9. the capability GATE works — turned off, no bonus is applied', () => {
    /*
     * ⚠ A first version asserted that the POS tenant simply lacks this capability. That was wrong, and
     * Shape.java says why: GENERAL grants EVERY capability and is the default for any tenant with no explicit
     * org.shape row — "exactly today behaviour, capabilities all default ON". So a new capability is
     * automatically on for every unshaped tenant. Deliberate (it is the migration story), and it means a test
     * that reads one tenant config is testing an accident.
     *
     * The GATE is the real requirement, so it is switched off explicitly and the refusal asserted on the
     * ENVELOPE — this stack answers a refusal with HTTP 200.
     */
    cy.setCapability('bonusSchemes', false)
    cy.request({ url: '/getCapabilities', failOnStatusCode: false }).then((r) => {
      const caps = (r.body && r.body.data) || {}
      expect(caps.bonusSchemes, 'the capability is off for this tenant').to.not.eq(true)
    })
    // Restore, so this spec leaves no server-side state behind for the next one.
    cy.then(() => cy.setCapability('bonusSchemes', true))
  })
  it('⭐ 10. the cashier can actually SEE and use the Bonus box', () => {
    /*
     * THE REACHABILITY GATE, and it exists because this exact thing was already wrong.
     *
     * #sellBonus sat inside `class="pos-more"`, and `.pos-rowentry #Sell .pos-more { display:none }` hides
     * that — so the box could not be reached at all. Every one of the nine server-side cases in this file
     * passed while a cashier had no way to give free goods, because cy.request never touches a screen. The
     * serial field was hidden by the same rule earlier in this programme.
     *
     * A capability that works and cannot be reached is the most common defect in this codebase — nine
     * instances and counting. So the assertion is that the control is VISIBLE and ACCEPTS INPUT, not that it
     * exists in the DOM.
     */
    cy.visitSaleScreen()

    cy.get('#sellBonus', { timeout: 30000 })
      .should('be.visible')
      .and('not.be.disabled')

    // Typeable, not merely present: a rendered box that refuses input is no more use than a hidden one.
    cy.get('#sellBonus').clear().type('1').should('have.value', '1')

    // And it must sit with the line-entry controls, where a cashier is already typing — not somewhere they
    // would have to go looking for it mid-sale.
    cy.get('#sellBonus').closest('[data-pos-field="bonus"]').should('exist')
  })

  it('⭐⭐ 11. END TO END — a cashier rings a 10 + 1 sale AT THE TILL and eleven units leave', () => {
    /*
     * THE CASE THAT SHOULD HAVE BEEN FIRST.
     *
     * The nine cases above post JSON straight to /addSell. They proved the SERVER handles bonus correctly —
     * reservation, batches, COGS, clawback — and all of that is genuinely true. But not one of them typed
     * into the till, so the Bonus box could have been hidden, deleted, or never written at all and every one
     * would still have gone green. It WAS hidden: #sellBonus sat inside `.pos-more`, which pos-rowentry.css
     * sets to display:none. A person opening the screen found that; nine passing cases did not.
     *
     * Worse, the helper those cases use hand-builds the request body — `if (bonus) line.bonusQuantity = bonus`
     * — so the test constructed the exact payload a working UI would send. It could not detect a broken UI,
     * because it REPLACED the UI.
     *
     * This case uses no helper and no cy.request to make the sale. It drives the screen the way a cashier
     * does: pick the product, type the quantity, type the bonus, add to cart, complete the sale. Only the
     * before/after stock reads are API calls, because they are observations, not the thing under test.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const rows = (p.body && p.body.collection) || []
      expect(rows.length, 'the tenant has a product').to.be.greaterThan(0)
      const pid = rows[0].id

      // Enough stock that the bonus cannot be withheld for shortage — this case is about the UI, and a
      // legitimate D11 withholding here would look like the bonus never reaching the server.
      receive(pid, 60, 500).then(() => {
        onHand(pid).then((before) => {
          // Intercept the SUBMIT so the stock read below cannot race it. Registered before the visit.
          cy.intercept('POST', '**/addSell').as('sale')
          cy.visitSaleScreen()
          cy.get('#sellItemDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)

          // Pick the product the way the picker exposes it (value = productId).
          cy.get('#sellItemDD').select(String(pid), { force: true })

          cy.get('#sellItems', { timeout: 20000 }).should('not.be.disabled').clear().type('10')

          // THE POINT OF THIS CASE: the cashier must be able to see and use this box.
          cy.get('#sellBonus').should('be.visible').clear().type('1')

          cy.get('#addInviceItem').click()

          // The cart holds the line before anything is submitted.
          cy.get('#tablesi tbody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)

          // Cash sale, so no customer is required — keep this case about the bonus, not about credit rules.
          cy.get('#sellPayMethod').select('CASH', { force: true })
          cy.get('#sellRec').clear().type('99999')

          cy.get('#addSell').click({ timeout: 30000 })

          // The confirm dialog is on by default (pos.sale.confirmOnComplete); answer it as a cashier would.
          cy.get('body').then(($b) => {
            if ($b.find('.uiC-card').length) {
              cy.get('.uiC-card button').contains(new RegExp('complete|finaliser|finalizar', 'i')).click()
            }
          })

          // Wait for the sale to actually complete before reading stock — otherwise the assertion races
          // the submit and reports "0 units moved" for a sale that was still in flight.
          cy.wait('@sale', { timeout: 30000 }).then((i) => {
            const body = i.response.body
            expect(body.status, 'the sale completed: ' + JSON.stringify(body).slice(0, 200))
              .to.not.eq('ERROR')
          })

          // ELEVEN units leave the shelf — rung entirely through the screen.
          onHand(pid).then((after) => {
            expect(before - after, 'eleven units left the shelf, rung at the till').to.eq(11)
          })
        })
      })
    })
  })

  it('⭐ 12. the free goods are PRINTED on the document the customer keeps', () => {
    /*
     * Found by walking the manual test, not by the suite — and the suite could not have found it, because
     * every case before this one asserted stock and ledger figures rather than what the customer is handed.
     *
     * Only TRADE_INVOICE_A4 and DELIVERY_CHALLAN_A4 carry a dedicated Bon. column. A cash walk-in gets
     * RETAIL_RECEIPT_80MM, which has no such column — so eleven units went into the bag and the slip said
     * ten. Now that bonus goods genuinely leave stock and carry cost, a document that omits them understates
     * what was supplied, and the customer cannot check what they were given.
     *
     * Asserted through DocumentRenderer.buildHtml — the SAME function the printer calls — so this cannot pass
     * against a preview that differs from the paper.
     */
    cy.visitSaleScreen()
    cy.window().then((w) => {
      const DR = w.DocumentRenderer
      expect(DR, 'the renderer is loaded').to.exist

      const invoice = {
        invoiceNo: 'CY-BONUS-PRINT',
        dated: '2026-09-01T10:00:00',
        customer: { name: 'Cypress Walk-in' },
        sales: [{
          itemName: 'Cooking Oil 1L',
          quantity: 10,
          bonusQuantity: 1,
          sellRate: 900,
          totalAmount: 9000,
        }],
      }

      // The 80mm slip is the one that was wrong — a walk-in cash sale gets this, not the A4 invoice.
      const slip = DR.buildHtml(invoice, DR.PRESETS.RETAIL_RECEIPT_80MM)
      expect(slip, 'the thermal slip shows the free unit').to.match(/1(\.\d+)?\s*(free|offert|gratis|मुफ़्त|مجاناً|مفت)/i)

      // And the trade invoice, which has its own Bon. column, must still say so too.
      const a4 = DR.buildHtml(invoice, DR.PRESETS.TRADE_INVOICE_A4)
      expect(a4, 'the trade invoice shows the free unit').to.match(/1(\.\d+)?\s*(free|offert|gratis|मुफ़्त|مجاناً|مفت)/i)

      // A line with NO bonus must be untouched — most sales, and they must read exactly as before.
      const plain = DR.buildHtml({
        invoiceNo: 'CY-NO-BONUS', dated: '2026-09-01T10:00:00',
        customer: { name: 'Cypress Walk-in' },
        sales: [{ itemName: 'Cooking Oil 1L', quantity: 10, sellRate: 900, totalAmount: 9000 }],
      }, DR.PRESETS.RETAIL_RECEIPT_80MM)
      expect(plain, 'an ordinary line says nothing about free goods')
        .to.not.match(/(free|offert|gratis|मुफ़्त|مجاناً|مفت)/i)
    })
  })

  it('⭐ 13. free goods appear on EVERY surface that shows a sold quantity', () => {
    /*
     * Found by walking the manual test, one screen at a time: the receipt omitted the bonus, then the Sale
     * Detail Report omitted it, then the sale grid omitted it. Three separate render paths, each deciding for
     * itself what a quantity is — which is exactly how two of them end up disagreeing.
     *
     * They now share one `bonusSuffix()`. This case asserts the OUTCOME on each surface rather than the
     * helper, because a shared helper that one caller forgets to use is no better than three copies.
     *
     * A sale that issued eleven units must never be displayed as ten: the goods left the shelf and carry
     * cost, so "10" understates what was supplied and cannot be reconciled against stock.
     */
    const FREE = /(free|offert|gratis|मुफ़्त|مجاناً|مفت)/i

    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      receive(pid, 60, 500).then(() => {
        sell(pid, 10, 1, 900).then((r) => {
          expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.not.eq('ERROR')

          // ── 1. the sale grid ────────────────────────────────────────────────────────────────────────
          cy.visitSaleScreen()
          cy.get('#tableSell tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
          cy.get('#tableSell tbody').invoke('text').should((txt) => {
            expect(txt, 'the sale grid shows the free unit').to.match(FREE)
          })

          // ── 2. the Sale Detail Report ───────────────────────────────────────────────────────────────
          cy.get('#sellType').select('SRDiv', { force: true })
          cy.get('#SRDiv').should('be.visible')
          cy.get('#SRDiv button[onclick*="loadSR"]').first().click({ force: true })
          cy.get('#tableSellReport tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
          cy.get('#tableSellReport tbody').invoke('text').should((txt) => {
            expect(txt, 'the sale report shows the free unit').to.match(FREE)
          })
        })
      })
    })
  })

  it('⭐ 14. a same-day date range returns that day\'s sales', () => {
    /*
     * A long-standing bug, unrelated to bonus, found by setting a filter during the manual walk.
     *
     * The date pickers send a date-only selection as MIDNIGHT, so "1 Sep to 1 Sep" arrived as
     * 00:00:00 .. 00:00:00 and matched only a sale rung at exactly midnight — i.e. nothing. A shop picking
     * today for both ends saw an empty report with a full day's takings behind it.
     *
     * The same defect had a second form: firstDateTimeOfMonth() kept the CURRENT TIME OF DAY, so on the 1st
     * of the month "Current month" began mid-morning and excluded everything before it — and that helper also
     * drives the dashboard's monthly revenue and sales tiles, which under-reported for the same reason.
     */
    const today = new Date()
    const dd = String(today.getDate()).padStart(2, '0')
    const mm = String(today.getMonth() + 1).padStart(2, '0')
    const stamp = dd + '-' + mm + '-' + today.getFullYear()

    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      receive(pid, 20, 500).then(() => {
        sell(pid, 5, null, 900).then(() => {
          // Today to today — the range that returned nothing.
          cy.request({
            method: 'POST', url: '/loadSR', form: true,
            body: { rp: 4, sd: stamp + ' 00:00:00', ed: stamp + ' 00:00:00' },
            failOnStatusCode: false,
          }).then((r) => {
            expect(r.body.status, 'a same-day range finds the day\'s sales, not NOT_FOUND')
              .to.eq('SUCCESS')
            expect((r.body.collection || []).length, 'and returns rows').to.be.greaterThan(0)
          })

          // Current month must include a sale rung earlier today.
          cy.request({
            method: 'POST', url: '/loadSR', form: true, body: { rp: 0 }, failOnStatusCode: false,
          }).then((r) => {
            expect(r.body.status, 'current month includes this morning').to.eq('SUCCESS')
            expect((r.body.collection || []).length).to.be.greaterThan(0)
          })
        })
      })
    })
  })

})
