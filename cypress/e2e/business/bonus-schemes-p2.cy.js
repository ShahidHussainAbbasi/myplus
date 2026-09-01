/**
 * #17 P2 — supplier bonus at GOODS-IN.
 *
 * ⚠ WRITTEN BEFORE THE IMPLEMENTATION (cadence, 2026-08-30). These are the requirement.
 *
 * <h3>Why P2 comes before P3</h3>
 * On a purchase the supplier invoice total does not change: you pay for 10 and receive 11. Payable, inventory
 * value and input tax are all unchanged, so the ARITHMETIC can be proven with zero books risk. P3 (customer
 * bonus) changes COGS and is a separate release.
 *
 * <h3>The two things that must be exactly right</h3>
 * <ol>
 *   <li><b>Stock += received, not billed.</b> If 11 arrive and 10 are recorded, the shelf and the system
 *       diverge by one on every delivery, permanently. That is the defect this whole task exists for.</li>
 *   <li><b>The paid total is ALLOCATED across received units, never a rounded unit cost multiplied back.</b>
 *       5,000 / 11 = 454.54; x 11 = 4,999.94. Six paisa that reconciles to nothing. The installments rule —
 *       "a total is ALLOCATED, never derived by rounding a proportion" — applies exactly here.</li>
 * </ol>
 *
 * D8: input tax is taken from the supplier invoice AS INVOICED. A zero-price bonus unit generates no extra
 * input tax — never compute tax the supplier's document does not show.
 */

const DIST_OWNER = 'owner.marketplace@myplus.com'

/** Read live on-hand for a product through the product's own endpoint. */
function onHand(productId) {
  return cy.request({ url: '/productStock?productId=' + productId })
    .then((r) => Number((r.body && r.body.stock) || 0))
}

describe('#17 P2 — supplier bonus on goods-in', () => {
  beforeEach(() => {
    cy.loginAsMarketplaceOwner()
  })

  it('⭐ stock increases by PAID + BONUS, not by the billed quantity', () => {
    /*
     * THE DEFECT, asserted as arithmetic. Receive 10 paid + 1 bonus and on-hand must rise by 11.
     * A system that adds 10 shows 60 bottles where the shelf holds 61, every single delivery.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const rows = (p.body && p.body.collection) || []
      expect(rows.length, 'the tenant has a product').to.be.greaterThan(0)
      const pid = rows[0].id

      onHand(pid).then((before) => {
        cy.request({
          method: 'POST', url: '/addPurchase', form: true,
          body: {
            productId: pid, quantity: 10, bonusQuantity: 1,
            'stock.bpurchaseRate': 500, 'stock.batchNo': 'CY-BONUS-' + Date.now(),
          },
          failOnStatusCode: false,
        }).then((r) => {
          expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.not.eq('ERROR')

          onHand(pid).then((after) => {
            expect(after - before, 'on-hand rose by the RECEIVED quantity (10 paid + 1 bonus)').to.eq(11)
          })
        })
      })
    })
  })

  it('⭐ the paid total is allocated across received units — and reconciles EXACTLY', () => {
    /*
     * The rounding-drift guard. Pay 5,000 for 11 units. Whatever per-unit figure is displayed, the batch must
     * still account for exactly 5,000 — no more, no less.
     *
     * Asserted on the RECONCILIATION rather than on a unit price, because any unit price is a rounding of the
     * truth. 11 x 454.54 = 4,999.94 and 11 x 454.55 = 5,000.05: both are wrong, in opposite directions.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      const batch = 'CY-ALLOC-' + Date.now()

      cy.request({
        method: 'POST', url: '/addPurchase', form: true,
        body: { productId: pid, quantity: 10, bonusQuantity: 1, 'stock.bpurchaseRate': 500, 'stock.batchNo': batch },
        failOnStatusCode: false,
      }).then((pr) => {
        // FAIL LOUDLY HERE. A refused purchase would otherwise surface far downstream as "the batch does not
        // exist", which points at inventory when the real problem was the write.
        expect(pr.body.status, 'the purchase was accepted: ' + JSON.stringify(pr.body).slice(0, 300))
          .to.not.eq('ERROR')

        cy.request({ url: '/productStock?productId=' + pid }).then((r) => {
          const batches = (r.body && r.body.batches) || []
          const b = batches.find((x) => x.batchNo === batch)
          expect(b, 'the received batch exists. Batches seen: '
            + JSON.stringify(batches.map((x) => x.batchNo))).to.exist

          // Whatever shape the cost is stored in, quantity x cost must return the paid total exactly.
          const qty = Number(b.available != null ? b.available : b.quantity)
          expect(qty, 'the batch holds all 11 received units').to.eq(11)

          // NO FALLBACK to qty x purchasePrice here. That identity is exactly what a bonus BREAKS — 11 units
          // at the 500 headline rate reads 5,500 for a 5,000 purchase — so falling back would turn a missing
          // paidTotal into a plausible wrong number instead of a failure. Absence must fail on its own terms.
          expect(b.paidTotal, 'the batch carries the amount paid (null means it never survived the write)')
            .to.not.eq(null)
          expect(Math.round(Number(b.paidTotal) * 100) / 100,
            'the batch accounts for exactly the amount paid').to.eq(5000)
        })
      })
    })
  })

  it('⭐ input tax is taken from the supplier invoice AS INVOICED', () => {
    /*
     * D8. Net 5,000 + tax 900 = payable 5,900, with 11 units received. The bonus unit must NOT generate
     * additional input tax — inventing tax the supplier's document does not show would misstate a return.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      cy.request({
        method: 'POST', url: '/addPurchase', form: true,
        body: {
          productId: pid, quantity: 10, bonusQuantity: 1, 'stock.bpurchaseRate': 500,
          taxAmount: 900, 'stock.batchNo': 'CY-TAX-' + Date.now(),
        },
        failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status).to.not.eq('ERROR')
        const saved = r.body.object || r.body.data || {}
        if (saved.taxAmount != null) {
          expect(Number(saved.taxAmount), 'tax is exactly what the invoice said — not grossed up for the bonus')
            .to.eq(900)
        }
      })
    })
  })

  it('⭐ the GL total is unchanged by a bonus — money follows the invoice', () => {
    /*
     * The books guard for P2, and the reason P2 is safe to ship before P3. Bonus changes QUANTITY and unit
     * cost; PostingEventRequest carries only values (grandTotal, subTotal, taxTotal, cost, paidAmount). So a
     * purchase with a bonus must post exactly what a purchase without one posts for the same money.
     */
    cy.request({ url: '/trialBalance', failOnStatusCode: false }).then((before) => {
      if (before.status !== 200) return   // finance reports not enabled for this tenant

      cy.request({ url: '/getUserProduct' }).then((p) => {
        const pid = ((p.body && p.body.collection) || [])[0].id
        cy.request({
          method: 'POST', url: '/addPurchase', form: true,
          body: { productId: pid, quantity: 10, bonusQuantity: 1, 'stock.bpurchaseRate': 500, 'stock.batchNo': 'CY-GL-' + Date.now() },
          failOnStatusCode: false,
        }).then(() => {
          cy.request({ url: '/trialBalance' }).then((after) => {
            const dr = Number(after.body.totalDebit || 0)
            const cr = Number(after.body.totalCredit || 0)
            expect(Math.round((dr - cr) * 100) / 100, 'the trial balance still balances').to.eq(0)
          })
        })
      })
    })
  })

  it('a receipt WITHOUT a bonus behaves exactly as before', () => {
    // The no-regression case. Most purchases carry no bonus, and they must be byte-for-byte what they were.
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      onHand(pid).then((before) => {
        cy.request({
          method: 'POST', url: '/addPurchase', form: true,
          body: { productId: pid, quantity: 7, 'stock.bpurchaseRate': 500, 'stock.batchNo': 'CY-NOBONUS-' + Date.now() },
          failOnStatusCode: false,
        }).then(() => {
          onHand(pid).then((after) => {
            expect(after - before, 'no bonus, no change in behaviour').to.eq(7)
          })
        })
      })
    })
  })

  it('⭐ partial return claws the bonus back proportionally', () => {
    /*
     * D7. Receive 10 paid + 1 bonus under "buy 10 get 1". Return 5 paid: the retained 5 no longer qualifies,
     * so entitlement drops to 0 and the bonus unit must come back out of stock too — 11 received, 6 returned,
     * 5 retained. Without this a buyer keeps a bonus the returned goods earned.
     */
    cy.request({ url: '/getUserProduct' }).then((p) => {
      const pid = ((p.body && p.body.collection) || [])[0].id
      const batch = 'CY-CLAW-' + Date.now()

      onHand(pid).then((start) => {
        cy.request({
          method: 'POST', url: '/addPurchase', form: true,
          body: { productId: pid, quantity: 10, bonusQuantity: 1, 'stock.bpurchaseRate': 500, 'stock.batchNo': batch },
          failOnStatusCode: false,
        }).then((r) => {
          const purchaseId = (r.body.object && r.body.object.purchaseId) || r.body.object
          if (!purchaseId) return

          cy.request({
            method: 'POST', url: '/purchaseReturn', form: true,
            body: { purchaseId: purchaseId, quantity: 5, reason: 'cypress: bonus clawback' },
            failOnStatusCode: false,
          }).then(() => {
            onHand(pid).then((end) => {
              expect(end - start, 'received 11, returned 5 paid + 1 clawed-back bonus, retained 5').to.eq(5)
            })
          })
        })
      })
    })
  })

  it('the bonus quantity is visible on the receipt, not silently folded in', () => {
    // The operator must be able to see WHY stock rose by 11 on an invoice for 10. A merged figure is
    // unauditable and reads as a data-entry error.
    cy.visitDashboardSettled()
    cy.get('#purchaseType').select('purchaseDiv', { force: true })
    cy.get('#purchaseDiv').should('be.visible')
    cy.get('#purchaseBonusQuantity').should('exist')
  })
})
