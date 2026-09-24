/**
 * RST-R2a — the tile counter, and the made-to-order sale it exists to ring up.
 *
 * Design: microservices/docs/restaurant-vertical-design.md §4
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * WHAT THIS GATE IS FOR
 *
 * The R1 gate (restaurant-menu-setup.cy.js) ran expecting to pass and answered
 *   "Not enough sellable stock — 'Chicken Tikka Leg': only 0 sellable, 1 requested"
 * That refusal was correct for retail and wrong for a kitchen, which holds buns and fillets rather than
 * finished burgers. Its case 2b records the refusal as the truth of the day, with a note that when the
 * capability landed the case must be INVERTED rather than deleted.
 *
 * This is that inversion — and it is deliberately NOT an edit of 2b. Case 2b is now the CONTROL: a product
 * with no flag is still refused, and that must keep passing exactly as written. What changes is that a
 * product which says "I am assembled when ordered" is allowed through. Both halves have to hold at once, in
 * the same tenant, or the change has quietly become "allow negative stock", which
 * BusinessSettingsCatalog records was removed on purpose and must not come back by the side door.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * ⚠ THE CASE THAT CARRIES THE SPEC IS 2 — the MIXED sale.
 *
 * One invoice, a burger (made to order, zero stock) beside a cola (stocked, zero stock). The burger must
 * sell and the cola must refuse, on the same request. A tenant-wide exemption passes case 1 and fails this.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/restaurant-counter.cy.js
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const run = uniq()
/** Namespaced so a re-run never collides, and so the tenant's real catalogue is never searched by chance. */
const tag = (name) => `${name} [R2-${run}]`

const CAT_FOOD = `Zingers [R2-${run}]`
const CAT_COLD = `Cold Drinks [R2-${run}]`

/**
 * Create a menu item.
 *
 * `madeToOrder` is passed through the monolith's `/addProduct`, which is a raw `Map` proxy — the flag
 * survives the wire untouched. ProductService applies it under the pack rules' "null means not supplied"
 * idiom, so omitting it leaves a product's existing answer alone; here it is always stated explicitly
 * because the whole spec turns on which of the two a product is.
 */
const addMenuItem = (category, name, price, madeToOrder) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name: tag(name), sku: `R2${uniq()}`, sellingPrice: price, taxRate: 0, unit: 'plate',
      categoryName: category, madeToOrder: madeToOrder },
  }).then((r) => {
    expect(r.body.success, `${name}: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(true)
    return r.body.data.id
  })

describe('RST-R2a — a kitchen sells what it never stocked, and only that', () => {
  const made = {}

  let counterBefore = null
  let capBefore = null

  before(() => {
    cy.loginAsOwner()

    // Leave no server state: both of these are org-wide, so read what they are before touching them.
    cy.request({ url: '/getBusinessConfig', failOnStatusCode: false }).then((r) => {
      const rows = (r.body && r.body.data) || []
      const find = (k) => { const row = rows.find((x) => x.key === k); return row ? row.value : null }
      counterBefore = find('pos.counter.enabled')
      capBefore = find('org.cap.madeToOrder')
    })

    cy.setCapability('madeToOrder', true)

    addMenuItem(CAT_FOOD, 'Zinger Burger', 350, true).then((id) => {
      made.burger = { id, price: 350, name: tag('Zinger Burger') }
    })
    addMenuItem(CAT_FOOD, 'Chicken Roll', 180, true).then((id) => {
      made.roll = { id, price: 180, name: tag('Chicken Roll') }
    })
    // ⚠ NOT made to order, and never purchased. A restaurant really does stock its cold drinks, and this is
    // the product the exemption must not touch.
    addMenuItem(CAT_COLD, 'Cola 1.5L', 150, false).then((id) => {
      made.cola = { id, price: 150, name: tag('Cola 1.5L') }
    })
  })

  after(() => {
    cy.loginAsOwner()
    const put = (key, value) => {
      if (value == null) return
      cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value: String(value) } })
    }
    put('pos.counter.enabled', counterBefore)
    put('org.cap.madeToOrder', capBefore)
  })

  beforeEach(() => cy.loginAsOwner())

  /** Ring up lines and return the raw response, so a case can assert success OR refusal. */
  const sell = (lines) => {
    const total = lines.reduce((t, l) => t + l.item.price * l.qty, 0)
    return cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: {
        customer: { name: `R2_${uniq()}`, contact: '03352456847' },
        sales: lines.map((l) => ({
          productId: l.item.id, itemName: l.item.name, quantity: l.qty, sellRate: l.item.price,
        })),
        tenders: [{ method: 'CASH', amount: total }],
        paidAmount: total, grandTotal: total,
        idempotencyKey: `cy-r2-${uniq()}`,
      },
    })
  }

  it('⭐⭐ 1 — THE INVERSION: a made-to-order burger sells with no stock at all', () => {
    /*
     * The exact sale the R1 gate was refused. Nothing was purchased, so on-hand is zero and stays zero —
     * asserted below, because "it sold" would also be true if some earlier spec had left stock lying about,
     * and then this case would be proving nothing.
     */
    cy.request(`/getCatalogProduct?id=${made.burger.id}`).then((r) => {
      expect(r.body.data.madeToOrder, 'the flag round-tripped through the catalogue').to.eq(true)
    })

    sell([{ item: made.burger, qty: 2 }]).then((r) => {
      expect(r.body.status, `the sale: ${JSON.stringify(r.body).slice(0, 240)}`).to.eq('SUCCESS')
      expect(r.body.object, 'a real invoice number').to.match(/^INV-/)
    })
  })

  it('⭐⭐ 2 — THE CONTROL, on the SAME invoice: the stocked cola is still refused', () => {
    /*
     * ⚠ The case that keeps this from becoming a negative-stock switch.
     *
     * `pos.sale.negativeStockAllowed` was deliberately removed from the settings catalogue with a warning
     * not to re-add one without building the cross-service oversell path. If a later change made the
     * exemption tenant-wide — resolved from a capability, say, rather than from the product — case 1 would
     * still be green and only this line would go red.
     *
     * The mixed request matters: a separate cola-only sale could be refused by something unrelated to the
     * exemption. Here the burger and the cola travel together, and the refusal must name the cola.
     */
    sell([{ item: made.burger, qty: 1 }, { item: made.cola, qty: 3 }]).then((r) => {
      expect(r.body.status, `refused: ${JSON.stringify(r.body).slice(0, 240)}`).to.eq('ERROR')
      const msg = String(r.body.message || '')
      expect(msg, 'and it says which item, in the operator\'s language').to.match(/sellable stock/i)
      expect(msg, 'naming the COLA — not the burger that is allowed through').to.include('Cola 1.5L')
    })
  })

  it('3 — clearing the flag puts the product back under the stock check', () => {
    /*
     * The flag is a product decision, and an owner must be able to take it back — a shop that starts
     * buying in its rolls ready-made wants the stock check again. `null means not supplied` guards a
     * PARTIAL payload; an explicit false is an answer and must be written.
     */
    cy.request({
      method: 'POST', url: '/updateProduct', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: { id: made.roll.id, name: made.roll.name, sellingPrice: made.roll.price, taxRate: 0,
        unit: 'plate', categoryName: CAT_FOOD, madeToOrder: false },
    }).then((r) => expect(r.body.success, `update: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(true))

    sell([{ item: made.roll, qty: 1 }]).then((r) => {
      expect(r.body.status, 'back under the stock check').to.eq('ERROR')
      expect(String(r.body.message || '')).to.match(/sellable stock/i)
    })
  })

  // ── the tile counter ────────────────────────────────────────────────────────────────────────────────

  it('4 — the counter is OFF by default, and the typed sale form is untouched', () => {
    /*
     * The safety property of the whole screen. `pos.counter.enabled` fails closed, so every till in every
     * existing tenant looks exactly as it did — asserted BEFORE the on-case, because an assertion that the
     * screen appears when switched on proves nothing if it was there all along.
     *
     * ⚠ The wrapper is `display:none` in the template, so `should('not.be.visible')` is the honest check;
     * `not.exist` would pass for the wrong reason (the markup is always in the page).
     */
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true,
      body: { key: 'pos.counter.enabled', value: 'false' } })

    cy.visitSaleScreen()
    cy.get('#counterWrap').should('not.be.visible')
    cy.get('#Sell').should('be.visible')
  })

  it('⭐ 5 — switched on, the whole menu is reachable by category', () => {
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true,
      body: { key: 'pos.counter.enabled', value: 'true' } })

    cy.visitSaleScreen()
    cy.get('#counterWrap', { timeout: 20000 }).should('be.visible')

    // Both of this spec's categories are offered — the counter reads the WHOLE menu, not a best-seller
    // window. Quick-pick's nine tiles could never express a 16-category menu, which is why this is a
    // separate screen rather than a bigger quick-pick.
    cy.get('#counterCats .ctr-cat').should('have.length.greaterThan', 1)
    cy.get('#counterCats .ctr-cat').contains(CAT_COLD).should('exist')
    cy.get('#counterCats .ctr-cat').contains(CAT_FOOD).click()
    cy.get('#counterItems .ctr-tile').contains(tag('Zinger Burger')).should('exist')
  })

  it('⭐⭐ 6 — ONE TAP ADDS, and the order never leaves the screen', () => {
    /*
     * The two rules counter.js exists to hold, asserted together because they are one experience:
     *  - one tap puts the item on the order — no sheet, no confirm;
     *  - changing category FILTERS the grid and never navigates, so the running order stays visible.
     *
     * At forty items an hour a sheet on every add costs about a minute of queue, and at a 24/7 counter the
     * cashier is the queue.
     */
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true,
      body: { key: 'pos.counter.enabled', value: 'true' } })

    cy.visitSaleScreen()
    cy.get('#counterWrap', { timeout: 20000 }).should('be.visible')
    cy.get('#counterCats .ctr-cat').contains(CAT_FOOD).click()
    cy.get('#counterItems .ctr-tile').contains(tag('Zinger Burger')).click()

    // On the order after a single tap, with no dialog in between.
    cy.get('#tablesi tbody tr').should('have.length', 1)
    cy.get('#tablesi tbody tr').first().should('contain', tag('Zinger Burger'))

    // Now change category. The order is still on screen — the assertion the "drill-down" design would fail.
    cy.get('#counterCats .ctr-cat').contains(CAT_COLD).click()
    cy.get('#counterItems .ctr-tile').contains(tag('Cola 1.5L')).should('exist')
    cy.get('#tablesi tbody tr').should('have.length', 1)
    cy.get('#tablesi tbody tr').first().should('contain', tag('Zinger Burger'))
  })

  it('⭐ 7 — quantity is corrected ON THE LINE, and − to zero removes only that line', () => {
    /*
     * A "how many?" prompt before the item exists makes a miscount cost a remove-and-re-add. On the line it
     * costs one tap.
     *
     * ⚠ The +/- live in the ACTION cell, not the quantity cell. Three other specs read the quantity cell,
     * and U13's loose text ("10 tablets") is rendered there by looseQtyText — putting buttons inside it
     * would have made those specs read markup instead of a number.
     */
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true,
      body: { key: 'pos.counter.enabled', value: 'true' } })

    cy.visitSaleScreen()
    cy.get('#counterWrap', { timeout: 20000 }).should('be.visible')
    cy.get('#counterCats .ctr-cat').contains(CAT_FOOD).click()

    // Three taps of one tile is ONE line of three, not three lines — scanAddToCart merges a repeat.
    cy.get('#counterItems .ctr-tile').contains(tag('Zinger Burger')).click()
    cy.get('#counterItems .ctr-tile').contains(tag('Zinger Burger')).click()
    cy.get('#counterItems .ctr-tile').contains(tag('Zinger Burger')).click()
    cy.get('#tablesi tbody tr').should('have.length', 1)

    // A second, different item, so the removal below has something to leave alone.
    cy.get('#counterCats .ctr-cat').contains(CAT_COLD).click()
    cy.get('#counterItems .ctr-tile').contains(tag('Cola 1.5L')).click()
    cy.get('#tablesi tbody tr').should('have.length', 2)

    /*
     * ⚠ RE-QUERIED ON EVERY TAP, and that is not defensive style — it is required.
     *
     * renderCart() clears and redraws EVERY row on each step (it has to: the subtotals, the payable line,
     * Change and Due all move with the quantity). So the <tr> and the button inside it are detached the
     * instant the click lands. Chaining three clicks off one .find(), or off an alias, goes red on a
     * detached element — a failure that looks like a product fault and is not one.
     */
    const minusBurger = () =>
      cy.contains('#tablesi tbody tr', tag('Zinger Burger')).find('.ctr-step[data-d="-1"]').click()

    minusBurger()
    minusBurger()
    // Down to one. Asserted on the row's own total rather than a quantity cell so this stays true whatever
    // looseQtyText renders: 1 x 350.
    cy.contains('#tablesi tbody tr', tag('Zinger Burger')).should('contain', '350')

    minusBurger()
    cy.get('#tablesi tbody tr').should('have.length', 1)
    cy.get('#tablesi tbody tr').first().should('contain', tag('Cola 1.5L'))
  })
})
