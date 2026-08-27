/**
 * U3 — selling a broken pack from the till.
 *
 * Design: microservices/docs/slices/u3-loose-at-the-till.md
 *
 * U2 made a loose sale correct; it was reachable only through the API. This is the slice that puts it in
 * front of a cashier, so these cases drive the SCREEN — picker, toggle, keyboard, scan box — and then check
 * the books, because a till that looks right and bills wrong is the failure that matters.
 *
 * ⚠ THE CASE THAT PROTECTS EVERYTHING ELSE is "#sellItems never contains a letter". The quantity box is read
 * numerically in seven places, every one shaped `val()*1 > 0 ? val() : 1` — so a letter becomes NaN and the
 * line SILENTLY becomes one pack. That is why the unit is a keystroke (F7 / Alt+L) and never a character.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/sell-loose-till.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success,
      `saveBusinessConfig ${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))

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
      return p
    })
  })

/** ⚠ A purchase RESTAMPS the product's selling price from bsellRate — pass the intended price (U2 §13.4c). */
const stockIn = (productId, qty, cost, sellPrice) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: qty, 'stock.batchNo': `TB${uniq()}`,
      'stock.bpurchaseRate': cost, 'stock.bsellRate': sellPrice,
      totalAmount: qty * cost, netAmount: qty * cost, purchaseInvoiceNo: `TILL-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).substring(0, 200)}`).to.eq('SUCCESS'))

/*
 * Both helpers are COPIED FROM A GREEN SPEC (contract-price-charged.cy.js), not composed from memory.
 * `cy.openSellSection` is the established command and there is no `showSell` on window — an invented
 * helper would have failed all nine cases before U3 ran, which is exactly how the last three gate runs
 * were lost.
 */
const openSale = () => {
  cy.openSellSection('sellDiv')
  cy.get('#sellItemDD', { timeout: 15000 }).should('exist')
}

/** Pick a product on the sale line, then wait for its pack rules to arrive from /looseInfo. */
const pickProduct = (productId) => {
  // Wait for THIS option, not merely for "some options" — a stale list would satisfy the weaker wait.
  cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 10000 }).should('exist')
  cy.get('#sellItemDD').select(String(productId), { force: true })
  cy.get('#sellSellRate').should('not.have.value', '')
}

describe('U3 — selling a broken pack at the till', () => {
  beforeEach(() => cy.loginAsOwner())

  after(() => {
    cy.loginAsOwner()
    setConfig('pos.sale.looseMarkupPct', '0')   // org-wide: leave no server state behind
  })

  // ── ⭐ the case the slice exists for ──────────────────────────────────────────────────────────────────

  it('⭐ a cashier sells five tablets without doing arithmetic', () => {
    const name = `Till_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      openSale()
      pickProduct(p.id)

      cy.get('#sellUnitWrap').should('be.visible')
      cy.get('#sellUnitLoose').should('contain.text', 'tablet')

      cy.get('#sellItems').clear().type('5')
      cy.get('#sellUnitLoose').click({ force: true })

      // The hint line — the actual feature. The cashier sees the price BEFORE committing.
      cy.get('#sellLooseHint').should('be.visible')
        .and('contain.text', '5 tablets')
        .and('contain.text', '12.00')
        .and('contain.text', '0.5')

      cy.get('#addInviceItem').click({ force: true })
      cy.get('#tablesi tbody tr', { timeout: 10000 }).should('have.length.at.least', 1)

      /*
       * ⭐ WHAT THE TILL COMPOSED, not merely what it drew.
       *
       * `data[]` is submitted verbatim as `sales`, so this is the payload the server will receive. It is the
       * integration this slice actually introduces: U2 already proved the ARITHMETIC through the API, and a
       * screen that moves correctly while composing the wrong line would pass every visual assertion above
       * and still charge the customer for a whole pack.
       */
      cy.window().its('data').then((cart) => {
        const line = cart.find((l) => String(l.productId) === String(p.id))
        expect(line, 'the line reached the cart').to.exist
        expect(line.soldUnit, 'the customer bought pieces').to.eq('LOOSE')
        expect(Number(line.soldQuantity), 'five tablets').to.eq(5)
        expect(Number(line.quantity), 'half a pack leaves the shelf').to.be.closeTo(0.5, 0.0001)
      })
    })
  })

  // ── ⭐ the case that protects the other seven read sites ──────────────────────────────────────────────

  it('⭐ the quantity box NEVER contains a letter', () => {
    // Seven places read this box as a number, shaped `val()*1 > 0 ? val() : 1`. A letter becomes NaN and the
    // line silently becomes ONE PACK — the customer charged 120.00 for five tablets, with no error anywhere.
    const name = `NoLetter_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      openSale()
      pickProduct(p.id)

      cy.get('#sellItems').clear().type('5')
      cy.get('#sellItems').trigger('keydown', { key: 'F7', code: 'F7', force: true })
      cy.get('#sellItems').should('have.value', '5')

      cy.get('#sellItems').trigger('keydown', { key: 'l', code: 'KeyL', altKey: true, force: true })
      cy.get('#sellItems').should('have.value', '5')

      // and the value is still a usable number
      cy.get('#sellItems').invoke('val').then((v) => expect(Number(v)).to.eq(5))
    })
  })

  it('F7 and Alt+L both reach the same state as the toggle', () => {
    const name = `Keys_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      openSale()
      pickProduct(p.id)
      cy.get('#sellItems').clear().type('5')

      cy.get('#sellItems').trigger('keydown', { key: 'F7', code: 'F7', force: true })
      cy.get('#sellUnitLoose').should('have.class', 'active')
      cy.get('#sellLooseHint').should('be.visible')

      cy.get('#sellItems').trigger('keydown', { key: 'F7', code: 'F7', force: true })
      cy.get('#sellUnitPack').should('have.class', 'active')
      cy.get('#sellLooseHint').should('not.be.visible')

      cy.get('#sellItems').trigger('keydown', { key: 'l', code: 'KeyL', altKey: true, force: true })
      cy.get('#sellUnitLoose').should('have.class', 'active')
    })
  })

  // ── the ordinary till is untouched ───────────────────────────────────────────────────────────────────

  it('an ordinary product shows no toggle and ignores the keys', () => {
    // The commonest till in the country must look exactly as it does today. Absent, not disabled.
    const name = `Plain_${uniq()}`

    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { name, sellingPrice: 50, unit: 'pcs' },
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product exists').to.exist
      stockIn(p.id, 10, 30, 50)
      openSale()
      pickProduct(p.id)

      cy.get('#sellUnitWrap').should('not.be.visible')
      cy.get('#sellItems').clear().type('2')
      cy.get('#sellItems').trigger('keydown', { key: 'F7', code: 'F7', force: true })
      cy.get('#sellUnitWrap').should('not.be.visible')
      cy.get('#sellLooseHint').should('not.be.visible')
      cy.get('#sellItems').should('have.value', '2')
    })
  })

  it('the Enter chain is unchanged, with F7 pressed mid-run', () => {
    // That chain has been broken twice by well-meaning changes. U3 adds no field to it, and this case is
    // what proves the new key did not disturb it.
    const name = `Chain_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      openSale()
      cy.window().then((w) => { if (w.posKeyboardEnabled !== true) w.posKeyboardEnabled = true })
      pickProduct(p.id)

      cy.get('#sellItems').focus().clear().type('5')
      cy.get('#sellItems').trigger('keydown', { key: 'F7', code: 'F7', force: true })
      cy.get('#sellItems').type('{enter}', { force: true })
      cy.focused().should('have.id', 'sellSellRate')
    })
  })

  // ── the scanner ──────────────────────────────────────────────────────────────────────────────────────

  it('the scan grammar gains one suffix and loses nothing', () => {
    // parseScanEntry is pure and exported, so the grammar is checked directly — no browser round trip, and
    // every rejection is asserted, not just the happy path.
    openSale()
    cy.window().then((w) => {
      expect(w.parseScanEntry, 'parseScanEntry is exported').to.be.a('function')

      expect(w.parseScanEntry('ABC')).to.deep.eq({ qty: 1, code: 'ABC', unit: 'PACK' })
      expect(w.parseScanEntry('12*ABC')).to.deep.eq({ qty: 12, code: 'ABC', unit: 'PACK' })
      expect(w.parseScanEntry('5L*ABC')).to.deep.eq({ qty: 5, code: 'ABC', unit: 'LOOSE' })
      expect(w.parseScanEntry('5l*ABC'), 'lower case too').to.deep.eq({ qty: 5, code: 'ABC', unit: 'LOOSE' })

      expect(w.parseScanEntry('L*ABC').error, 'a marker with no count').to.eq('noQty')
      expect(w.parseScanEntry('0*ABC').error).to.eq('badQty')
      expect(w.parseScanEntry('12x*ABC').error, 'still digits-only').to.eq('badQty')
      expect(w.parseScanEntry('*ABC').error).to.eq('noQty')
      expect(w.parseScanEntry('12*').error).to.eq('noCode')
    })
  })

  // ── refusals ─────────────────────────────────────────────────────────────────────────────────────────

  it('a fractional piece count is refused before it reaches the cart', () => {
    const name = `Half_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      openSale()
      pickProduct(p.id)

      cy.get('#sellItems').clear().type('2.5')
      cy.get('#sellUnitLoose').click({ force: true })
      cy.get('#addInviceItem').click({ force: true })

      cy.get('#sellItems').should('have.class', 'alert-danger')
      cy.get('#tablesi tbody').should('not.contain.text', name)
    })
  })

  it('the markup reaches the hint — a tablet at 13.20, not 12.00', () => {
    // The hint must show what will actually be CHARGED. If it read the catalog price and divided, it would
    // quote 12.00 while the receipt said 13.20 — the exact drift the design refuses.
    const name = `Markup_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '10')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      openSale()
      pickProduct(p.id)

      cy.get('#sellItems').clear().type('5')
      cy.get('#sellUnitLoose').click({ force: true })
      cy.get('#sellLooseHint').should('be.visible').and('contain.text', '13.20')
    })
  })

  it('the pack rules come from the server, and the endpoint answers plainly', () => {
    // /looseInfo is the ONE source of the per-piece rate — the browser multiplies, it never rounds.
    const loose = `Info_${uniq()}`, plain = `InfoPlain_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(loose, 100, 3).then((p) => {
      cy.request(`/looseInfo?productId=${p.id}`).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        const d = r.body.object
        expect(d.allowLoose).to.eq(true)
        expect(d.packSize).to.eq(3)
        expect(Number(d.looseRate), '100/3 rounded UP — the shop never loses on a broken pack').to.eq(33.34)
      })
    })

    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { name: plain, sellingPrice: 50, unit: 'pcs' },
    }).then(() => cy.request('/getUserProduct?q=-1').then((pr) => {
      const p2 = list(pr.body).find((x) => x.name === plain)
      cy.request(`/looseInfo?productId=${p2.id}`).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        expect(r.body.object.allowLoose, 'an ordinary product says so plainly').to.eq(false)
      })
    }))
  })
})
