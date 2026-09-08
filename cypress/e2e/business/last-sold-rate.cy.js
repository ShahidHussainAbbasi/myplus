/**
 * LR-1 — what THIS customer last paid, on the sale line.
 *
 * A negotiating aid for a counter that haggles: as each item is added, the cart shows the rate this
 * customer was last charged for it. The question used to require leaving the sale to go and look.
 *
 * ⚠ WHAT THIS FILE IS REALLY GUARDING is the two ways the feature could quote a WRONG price, because a
 * confident wrong number beside a rate box is worse than no number at all:
 *
 *   1. ANOTHER CUSTOMER'S price. The owner ruled "this customer only" — a general last rate is a
 *      different fact, and `Product.lastSaleRate` is a third one again (it is stamped by the PURCHASE
 *      flow and contains no customer whatsoever). Case 2 buys the same product as a second customer at
 *      a different rate and proves the first customer's hint does not move.
 *   2. A price from a sale that was UNDONE. Voided invoices and returned lines are excluded, because
 *      quoting from them argues for a figure nobody ever settled at. Cases 3 and 4.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/last-sold-rate.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

/** Ring up a completed sale of one product to one customer, at a named rate. */
const sellTo = (customerId, productId, rate, qty = 1) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      customer: { customerId },
      sales: [{ productId, quantity: qty, sellRate: rate, totalAmount: rate * qty, netAmount: rate * qty }],
      paidAmount: rate * qty, dueAmount: 0, grandTotal: rate * qty,
      tenders: [{ method: 'CASH', amount: rate * qty, reference: '' }],
    },
  }).then((r) => {
    expect(r.body.status, `sell at ${rate}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
    return cy.wrap(String(r.body.object || ''))
  })

const seedCustomer = (name) =>
  cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: { name, contact: '03' + String(Date.now()).slice(-9) } })
    .then((r) => {
      expect(r.body.status, `seed ${name}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
      return cy.request('/getUserCustomer?q=-1').then((c) => {
        const row = ((c.body && c.body.collection) || []).find((x) => x.name === name)
        expect(row, 'the customer was created').to.exist
        return cy.wrap(row.customerId || row.id)
      })
    })

/** The endpoint, asked the way the till asks it. */
const lastRates = (customerId, productIds) =>
  cy.request({
    url: `/lastSoldRates?customerId=${customerId}`
       + productIds.map((p) => `&productIds=${p}`).join(''),
    failOnStatusCode: false,
  }).then((r) => (r.body && (r.body.object || r.body.data)) || {})

describe('LR-1 — the last price this customer paid', () => {
  before(() => {
    cy.loginAsOwner()
    setConfig('pos.entry.showLastRate', 'true')
  })

  beforeEach(() => cy.loginAsOwner())

  after(() => {
    // Leave no server state behind - ON is the catalogue default, so this restores rather than departs.
    cy.loginAsOwner()
    setConfig('pos.entry.showLastRate', 'true')
  })

  // ── ⭐⭐ 1. the claim ─────────────────────────────────────────────────────────────────────────────

  it('⭐⭐ 1 — the rate a customer was last charged comes back, with its date', () => {
    const run = uniq()
    cy.seedProduct({ name: `LR_${run}`, sellingPrice: 500, stock: 20 }).then(({ productId }) => {
      seedCustomer(`LR Buyer ${run}`).then((cid) => {
        // Two sales at different rates: the LATER one is the answer.
        sellTo(cid, productId, 420).then(() => {
          sellTo(cid, productId, 460).then(() => {
            lastRates(cid, [productId]).then((map) => {
              const hit = map[String(productId)]
              expect(hit, 'the product has history for this customer').to.exist
              expect(Number(hit.rate), 'the LAST rate, not the first').to.eq(460)
              expect(hit.dated, 'and when it was').to.be.a('string')
            })
          })
        })
      })
    })
  })

  // ── ⭐⭐ 2. never another customer's price ────────────────────────────────────────────────────────

  it("⭐⭐ 2 — another customer's price is NEVER shown for this one", () => {
    const run = uniq()
    cy.seedProduct({ name: `LRX_${run}`, sellingPrice: 500, stock: 40 }).then(({ productId }) => {
      seedCustomer(`LR Alice ${run}`).then((alice) => {
        seedCustomer(`LR Bob ${run}`).then((bob) => {
          sellTo(alice, productId, 400).then(() => {
            sellTo(bob, productId, 300).then(() => {
              // Bob bought it cheaper and LATER. Alice's hint must not move.
              lastRates(alice, [productId]).then((map) => {
                expect(Number(map[String(productId)].rate),
                  "Alice sees Alice's price, never Bob's").to.eq(400)
              })
              lastRates(bob, [productId]).then((map) => {
                expect(Number(map[String(productId)].rate), 'and Bob sees his own').to.eq(300)
              })
            })
          })
        })
      })
    })
  })

  // ── ⭐ 3. a price from a sale that was undone is not a price ─────────────────────────────────────

  it('⭐ 3 — a VOIDED sale is not used', () => {
    const run = uniq()
    cy.seedProduct({ name: `LRV_${run}`, sellingPrice: 500, stock: 20 }).then(({ productId }) => {
      seedCustomer(`LR Void ${run}`).then((cid) => {
        sellTo(cid, productId, 450).then(() => {
          sellTo(cid, productId, 999).then((invoiceNo) => {
            cy.request({ method: 'POST', url: '/voidSell', form: true, failOnStatusCode: false,
              body: { invoiceNo, reason: 'LR gate' } })
              .then((v) => expect(v.body.status, JSON.stringify(v.body)).to.eq('SUCCESS'))

            // The void means "this never happened", so the answer falls back to the sale that did.
            lastRates(cid, [productId]).then((map) => {
              expect(Number(map[String(productId)].rate),
                'the voided 999 is ignored; the real last price stands').to.eq(450)
            })
          })
        })
      })
    })
  })

  // ── ⭐ 4. nothing to show is shown as nothing ────────────────────────────────────────────────────

  it('⭐ 4 — a product this customer has never bought is ABSENT, not zero', () => {
    const run = uniq()
    cy.seedProduct({ name: `LRN_${run}`, sellingPrice: 500, stock: 5 }).then(({ productId }) => {
      seedCustomer(`LR New ${run}`).then((cid) => {
        lastRates(cid, [productId]).then((map) => {
          /*
           * ABSENT, deliberately. A zero would render as "0.00" beside the rate box and read as free -
           * the one number that must never appear here. The till draws nothing for a missing key.
           */
          expect(map[String(productId)], 'no history means no key at all').to.be.undefined
        })
      })
    })
  })

  // ── ⭐ 5. the screen, and the switch ─────────────────────────────────────────────────────────────

  it('⭐ 5 — the hint renders in the cart, and the setting switches it off', () => {
    const run = uniq()
    cy.seedProduct({ name: `LRUI_${run}`, sellingPrice: 500, stock: 20 }).then(({ productId }) => {
      seedCustomer(`LR Screen ${run}`).then((cid) => {
        sellTo(cid, productId, 480).then(() => {
          cy.visitSaleScreen()
          cy.window().should((w) => expect(w.LastRate, 'last-rate.js is loaded').to.be.an('object'))

          /*
           * The flag is pinned in the BROWSER, not inherited.
           *
           * loadPosFeatureFlags() writes window.posShowLastRate from the settings call, and that call is
           * still in flight while this case sets up. Whatever it lands on is the tenant's saved value -
           * this case must assert the FEATURE, not a configuration it happens to find. Same reason
           * every other pos* flag is pinned in these specs.
           */
          cy.window().then((w) => { w.posShowLastRate = true; w.LastRate.bind() })

          cy.get('#btnModeSelect').click({ force: true })
          cy.get('#sellCustomerDD', { timeout: 20000 }).select(String(cid), { force: true })
          cy.get('#sellItemDD', { timeout: 20000 }).select(String(productId), { force: true })
          cy.settled('#sellQuantity')
          cy.get('#sellQuantity').clear().type('1')

          // Watch the lookup, so a failure says WHICH step broke: no request at all (the till never
          // asked), an empty answer (scoping or exclusions), or a request that answered and did not
          // render (the annotate path). Without this the case can only report "element never found".
          cy.intercept('GET', '**/lastSoldRates*').as('lr')
          cy.get('#addInviceItem').click({ force: true })

          cy.wait('@lr', { timeout: 20000 }).then((i) => {
            const map = (i.response.body && (i.response.body.object || i.response.body.data)) || {}
            expect(map[String(productId)], `the till asked and got history: ${JSON.stringify(map)}`)
              .to.exist
          })

          // The hint is drawn on the line, carrying the rate this customer actually paid.
          cy.get('#tablesi tbody .lr-hint', { timeout: 20000 }).should('be.visible')
            .and('contain.text', '480')

          // And the tenant can switch it off - it is a setting, not a fact of the product.
          cy.window().then((w) => {
            w.posShowLastRate = false
            w.LastRate.annotate()
          })
          cy.get('#tablesi tbody .lr-hint').should('not.exist')
        })
      })
    })
  })
  // ── ⭐⭐ 6. the hint REFLECTS whoever is buying, and whatever is chosen ──────────────────────────

  it('⭐⭐ 6 — changing the customer re-reads the cart for the NEW customer', () => {
    /*
     * Not merely cleared - REFLECTED. Clearing alone stops the previous customer's price being shown
     * under a new name, but the cart lines are still there and now belong to somebody else, so the
     * honest answer is that customer's history for those products. And the cashier may never add
     * another line - they may just take the money - so "the next commit will fix it" would leave the
     * hint wrong-by-omission for the rest of the sale.
     *
     * Alice and Bob bought the SAME product at different prices, so a build that failed to re-read
     * would leave Alice's number visible under Bob's name - which is the wrong-price hazard, on screen.
     */
    const run = uniq()
    cy.seedProduct({ name: `LRC_${run}`, sellingPrice: 500, stock: 40 }).then(({ productId }) => {
      seedCustomer(`LR Ann ${run}`).then((ann) => {
        seedCustomer(`LR Ben ${run}`).then((ben) => {
          sellTo(ann, productId, 470).then(() => {
            sellTo(ben, productId, 380).then(() => {
              cy.visitSaleScreen()
              cy.window().then((w) => { w.posShowLastRate = true; w.LastRate.bind() })

              cy.get('#btnModeSelect').click({ force: true })
              cy.get('#sellCustomerDD', { timeout: 20000 }).select(String(ann), { force: true })
              cy.get('#sellItemDD', { timeout: 20000 }).select(String(productId), { force: true })
              cy.settled('#sellQuantity')
              cy.get('#sellQuantity').clear().type('1')
              cy.get('#addInviceItem').click({ force: true })

              cy.get('#tablesi tbody .lr-hint', { timeout: 20000 }).should('contain.text', '470')

              // Switch the account. The line in the cart is now Ben's.
              cy.get('#sellCustomerDD').select(String(ben), { force: true })

              cy.get('#tablesi tbody .lr-hint', { timeout: 20000 }).should('contain.text', '380')
              cy.get('#tablesi tbody .lr-hint').should('not.contain.text', '470')
            })
          })
        })
      })
    })
  })

  // ── ⭐ 7. the hint is there while the price is being DECIDED ─────────────────────────────────────

  it('⭐ 7 — choosing a product shows its last rate in the ENTRY row, before any line is committed', () => {
    /*
     * Where the number actually earns its keep. The cart hint is a review - the price is already typed.
     * Beside the rate box it is answering the question the cashier is being asked across the counter,
     * at the moment they are being asked it.
     *
     * The cart is deliberately EMPTY here: this must work on the first line of a sale, which is the
     * only line many sales have.
     */
    const run = uniq()
    cy.seedProduct({ name: `LRE_${run}`, sellingPrice: 500, stock: 20 }).then(({ productId }) => {
      seedCustomer(`LR Entry ${run}`).then((cid) => {
        sellTo(cid, productId, 455).then(() => {
          cy.visitSaleScreen()
          cy.window().then((w) => { w.posShowLastRate = true; w.LastRate.bind() })

          cy.get('#btnModeSelect').click({ force: true })
          cy.get('#sellCustomerDD', { timeout: 20000 }).select(String(cid), { force: true })
          cy.get('#sellItemDD', { timeout: 20000 }).select(String(productId), { force: true })

          // Nothing committed - the hint is beside the rate box, not in the cart.
          cy.get('#sellSellRate').closest('.pos-cell').find('.lr-entry', { timeout: 20000 })
            .should('be.visible').and('contain.text', '455')
          cy.window().its('data').should('have.length', 0)
        })
      })
    })
  })

})
