/**
 * PR-1 — how a purchase affects the selling price (pos.pricing.purchaseMode) + the product's price history.
 *
 * Design: microservices/docs/selling-price-per-purchase-analysis.md (§4.1, §4.4, slice PR-1)
 *
 * Before this slice every purchase carrying a sell rate re-priced ALL stock of the product, with no setting and no
 * trace. Now:
 *   latest (default) — today's behaviour, unchanged, and every change is recorded
 *   keep             — a purchase never moves the selling price; the cost (last purchase rate) is still stamped
 *
 * What each case protects, in the order a defect would cost money:
 *   G2  LATEST still re-prices (no existing tenant changes behaviour) — and the history names the bill
 *   G4  KEEP leaves the price alone but still stamps the cost
 *   G3  a repeat purchase at the same price is NOT a price change (no noise rows)
 *   G5  a manual edit is recorded too — the history is every writer, not just purchases
 *   G6/G7  the history (it carries cost) is owner/admin only, and never crosses tenants
 *   G8–G10 the screen SAYS what saving will do, before it is done; the dialog shows the record
 *
 * Tenant: owner.business@ (POS / retail — the shop shape whose default is LATEST). Ladder: admin./user.business@.
 * Cross-tenant: owner.pharma@. Server state: the setting is put back EXACTLY in after() (reset when it was never
 * chosen, else its old value). Products, vendor and purchases are this spec's own, named PRM_*.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/pricing-purchase-mode.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const KEY = 'pos.pricing.purchaseMode'
const GW = 'http://localhost:8765/api/catalog'
const STAMP = Date.now()
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

const configEntry = (key) =>
  cy.request('/getBusinessConfig').then((r) => cy.wrap(list(r.body).find((e) => e.key === key) || null))

const setMode = (value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: KEY, value }, failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${KEY}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))

const resetMode = () =>
  cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY }, failOnStatusCode: false })

const product = (id) =>
  cy.request('/getCatalogProduct?id=' + id).then((r) => {
    expect(r.body && r.body.data, `getCatalogProduct ${id}: ${JSON.stringify(r.body).slice(0, 200)}`).to.be.an('object')
    return cy.wrap(r.body.data)
  })

const history = (id) =>
  cy.request({ url: '/productPriceHistory?productId=' + id, failOnStatusCode: false }).then((r) => {
    expect(r.status, 'productPriceHistory').to.eq(200)
    expect(r.body && r.body.success, `productPriceHistory: ${JSON.stringify(r.body).slice(0, 300)}`).to.eq(true)
    return cy.wrap(r.body.data)
  })

let vendorId = null
const ensureVendor = () => {
  if (vendorId) return cy.wrap(vendorId)
  const vname = 'PRM_V_' + STAMP
  return cy.ensureCompany().then((companyId) => {
    cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
      body: { name: vname, companyId, mobile: '0300' + String(STAMP).slice(-7), email: 'prm' + STAMP + '@t.com' } })
      .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.be.oneOf(['SUCCESS', 'FOUND']))
    return cy.request('/getUserVenders').then((vr) => {
      const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
      const id = (new RegExp('<option value=(\\d+)[^>]*>' + vname).exec(html) || [])[1]
      expect(id, 'the spec vendor is in the dropdown').to.exist
      vendorId = id
      return cy.wrap(id)
    })
  })
}

/** One purchase line over the API — the same form fields the purchase screen posts. Paid blank = paid in full. */
const purchase = (productId, cost, sell, invoiceNo) =>
  ensureVendor().then((venderId) =>
    cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
      body: { productId, venderId, quantity: 2, purchaseRate: cost,
              'stock.bpurchaseRate': cost, 'stock.bsellRate': sell,
              totalAmount: 2 * cost, netAmount: 2 * (sell - cost), purchaseInvoiceNo: invoiceNo } })
      .then((p) => expect(p.body.status, `addPurchase ${invoiceNo}: ${JSON.stringify(p.body).slice(0, 300)}`).to.eq('SUCCESS')))

const seed = (price) => cy.seedProduct({ name: 'PRM_' + uniq(), sellingPrice: price }).then((p) => cy.wrap(p.productId))

/** Open the purchase form fresh and pick a product; the picker's data-price is the product's price at page load. */
function openPurchaseFor(productId) {
  cy.visit('/businessDashboard')
  cy.openPurchaseSection('purchaseDiv')
  cy.get('#newPurchase').click()
  cy.get('#PurchaseModal').should('have.class', 'open')
  cy.settled('#purchaseInvoiceNo')
  cy.intercept('GET', '/productStock*').as('prefill')
  cy.get('#purchaseItemDD').select(String(productId), { force: true })
  cy.wait('@prefill', { timeout: 15000 })
}

describe('PR-1 — purchase price mode + price history', () => {
  let before0 = null   // the setting as this tenant had it: { chosen, value }

  before(() => {
    cy.loginAsOwner()
    configEntry(KEY).then((e) => {
      before0 = e ? { chosen: e.isDefault === false, value: e.value } : { chosen: false, value: null }
      cy.log('setting before: ' + JSON.stringify(before0))
    })
  })

  beforeEach(() => { cy.loginAsOwner() })

  after(() => {
    cy.loginAsOwner()
    // Put it back EXACTLY: a never-chosen key goes back to never-chosen (the row is deleted), not to "latest saved".
    cy.then(() => {
      if (before0 && before0.chosen) setMode(before0.value)
      else resetMode()
    })
    configEntry(KEY).then((e) => {
      if (before0 && before0.chosen) expect(e.value, 'restored to the tenant\'s own choice').to.eq(before0.value)
      else expect(e.isDefault, 'restored to never-chosen').to.eq(true)
    })
  })

  it('G1 the setting is offered under Purchasing, default latest, choices latest | keep | per_batch', () => {
    resetMode()
    configEntry(KEY).then((e) => {
      expect(e, KEY + ' is in the business settings catalog').to.be.an('object')
      expect(e.group).to.eq('Purchasing')
      expect(e.type).to.eq('SELECT')
      expect(String(e.value).toLowerCase(), 'default').to.eq('latest')
      expect((e.options || []).map((o) => o.value), 'PR-3b added per_batch').to.deep.eq(['latest', 'keep', 'per_batch'])
    })
  })

  it('G2 LATEST: the purchase re-prices the product, stamps the cost, and the history names the bill', () => {
    resetMode()
    const inv = 'PRM-L-' + STAMP
    seed(200).then((id) => {
      purchase(id, 210, 250, inv)
      product(id).then((p) => {
        expect(Number(p.sellingPrice), 'selling price follows the purchase (today\'s behaviour)').to.eq(250)
        expect(Number(p.lastPurchaseRate), 'cost stamped').to.eq(210)
      })
      history(id).then((h) => {
        expect(Number(h.sellingPrice)).to.eq(250)
        expect(h.history.length, JSON.stringify(h.history)).to.eq(2)
        const [latest, opening] = h.history   // newest first
        expect(latest.source).to.eq('PURCHASE')
        expect(Number(latest.oldPrice)).to.eq(200)
        expect(Number(latest.newPrice)).to.eq(250)
        expect(latest.ref, 'the history names the bill that moved the price').to.eq(inv)
        expect(opening.source).to.eq('MANUAL')
        expect(opening.oldPrice, 'creation: there was no price before').to.eq(null)
        expect(Number(opening.newPrice)).to.eq(200)
      })
    })
  })

  it('G3 a repeat purchase at the SAME sell rate is not a price change — no new row; the cost still updates', () => {
    resetMode()
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRM-R1-' + STAMP)
      purchase(id, 215, 250, 'PRM-R2-' + STAMP)
      product(id).then((p) => {
        expect(Number(p.sellingPrice)).to.eq(250)
        expect(Number(p.lastPurchaseRate), 'the newer cost').to.eq(215)
      })
      history(id).then((h) => expect(h.history.map((r) => r.source), JSON.stringify(h.history)).to.deep.eq(['PURCHASE', 'MANUAL']))
    })
  })

  it('G4 KEEP: the purchase leaves the selling price alone, but still stamps the cost', () => {
    setMode('keep')
    configEntry(KEY).then((e) => expect(e.value).to.eq('keep'))
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRM-K-' + STAMP)
      product(id).then((p) => {
        expect(Number(p.sellingPrice), 'price NOT moved by the purchase').to.eq(200)
        expect(Number(p.lastPurchaseRate), 'cost still stamped').to.eq(210)
      })
      history(id).then((h) => {
        expect(h.history.map((r) => r.source), 'only the creation row — no PURCHASE row').to.deep.eq(['MANUAL'])
      })
    })
  })

  it('G5 a manual edit of the price is recorded (MANUAL 200 → 220)', () => {
    resetMode()
    seed(200).then((id) => {
      product(id).then((p) => {
        // Round-trip the product exactly as the form does (all fields + the version it loaded), changing the price.
        const body = Object.assign({}, p, { sellingPrice: 220 })
        cy.request({ method: 'POST', url: '/updateProduct', body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
          .then((r) => expect(r.body && r.body.success, `updateProduct: ${JSON.stringify(r.body).slice(0, 300)}`).to.eq(true))
      })
      history(id).then((h) => {
        const [latest] = h.history
        expect(latest.source).to.eq('MANUAL')
        expect(Number(latest.oldPrice)).to.eq(200)
        expect(Number(latest.newPrice)).to.eq(220)
        expect(h.history.length).to.eq(2)
      })
    })
  })

  it('G6 ladder: admin sees the history, a user is refused (the answer carries cost); the link follows the role', () => {
    resetMode()
    seed(200).then((id) => {
      cy.loginAsTier('admin', 'business')
      cy.request({ url: '/productPriceHistory?productId=' + id, failOnStatusCode: false }).then((r) => {
        expect(r.body && r.body.success, 'admin: ' + JSON.stringify(r.body).slice(0, 200)).to.eq(true)
        expect(r.body.data.history.length).to.be.greaterThan(0)
      })
      cy.loginAsTier('user', 'business')
      cy.request({ url: '/productPriceHistory?productId=' + id, failOnStatusCode: false }).then((r) => {
        const refused = r.status === 403 || (r.body && r.body.success === false)
        expect(refused, 'user refused: ' + r.status + ' ' + JSON.stringify(r.body).slice(0, 200)).to.eq(true)
        expect(r.body && r.body.data, 'no history leaks in a refusal').to.not.exist
      })
      cy.visit('/businessDashboard')
      cy.get('#prodPrice').should('exist')
      cy.get('#prodPriceHistoryBtn').should('not.exist')   // sec:authorize removed it for a user
      cy.loginAsOwner()
      cy.visit('/businessDashboard')
      cy.get('#prodPriceHistoryBtn').should('exist')
    })
  })

  it('G7 another tenant cannot read this product\'s history (catalog answers not-found, never the rows)', () => {
    seed(200).then((id) => {
      cy.asOtherTenant((auth) => {
        cy.request({ url: `${GW}/products/${id}/price-history`, headers: auth, failOnStatusCode: false }).then((r) => {
          expect(r.status, 'cross-tenant: ' + JSON.stringify(r.body).slice(0, 200)).to.be.oneOf([403, 404])
          expect(JSON.stringify(r.body || {})).to.not.contain('"history"')
        })
      }, 'owner.pharma@myplus.com')
    })
  })

  it('G8 LATEST: the purchase form says the price WILL change, with both numbers — and "stays" at the same rate', () => {
    resetMode()
    seed(200).then((id) => {
      openPurchaseFor(id)
      cy.get('#purchaseSellRate').clear().type('250')
      cy.get('#purchasePriceEffect').should('be.visible')
        .and('have.attr', 'data-effect', 'change')
        .and('contain', '200.00').and('contain', '250.00')
      cy.get('#purchaseSellRate').clear().type('200')
      cy.get('#purchasePriceEffect').should('have.attr', 'data-effect', 'same').and('contain', '200.00')
      cy.get('#purchaseSellRate').clear()
      cy.get('#purchasePriceEffect').should('not.be.visible')
    })
  })

  it('G9 KEEP: the purchase form says the price STAYS', () => {
    setMode('keep')
    seed(200).then((id) => {
      openPurchaseFor(id)
      cy.window().its('posPurchasePriceMode').should('eq', 'keep')
      cy.get('#purchaseSellRate').clear().type('250')
      cy.get('#purchasePriceEffect').should('be.visible')
        .and('have.attr', 'data-effect', 'keep')
        .and('contain', '200.00').and('not.contain', '250.00')
    })
  })

  it('G10 the product form\'s Price history shows the purchase that moved the price, newest first', () => {
    resetMode()
    const inv = 'PRM-D-' + STAMP
    seed(200).then((id) => {
      purchase(id, 210, 250, inv)
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.editProduct(id))
      cy.get('#ProductModal').should('have.class', 'open')
      cy.get('#prodPriceHistoryBtn').should('be.visible').click()
      cy.get('#PriceHistoryDialog').should('be.visible')
      cy.get('#priceHistoryNow [data-k=sellingPrice]').should('have.text', '250.00')
      cy.get('#priceHistoryNow [data-k=lastPurchaseRate]').should('have.text', '210.00')
      cy.get('#priceHistoryTable tbody tr').should('have.length', 2)
      cy.get('#priceHistoryTable tbody tr').first().should('have.attr', 'data-source', 'PURCHASE')
        .within(() => {
          cy.get('[data-k=old]').should('have.text', '200.00')
          cy.get('[data-k=new]').should('have.text', '250.00')
          cy.get('[data-k=ref]').should('have.text', inv)
          // The time is THIS browser's local time (the server sends an instant). A wall-clock string from a UTC
          // container read as local was 5 hours off in Pakistan.
          cy.get('td').first().invoke('text').then((shown) => {
            const ms = new Date(shown.replace(' ', 'T')).getTime()   // no zone in the text → parsed as local
            expect(Math.abs(Date.now() - ms), `"${shown}" is local now (±10 min)`).to.be.lessThan(10 * 60 * 1000)
          })
        })
      cy.get('#PriceHistoryDialog button').contains(/close/i).click()
      cy.get('#PriceHistoryDialog').should('not.be.visible')
    })
  })
})
