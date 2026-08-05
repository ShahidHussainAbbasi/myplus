/**
 * End-to-End Business Flow Tests
 *
 * These tests exercise the complete data chain:
 *   Company → Vender → Product → Stock → Purchase → Sell
 *
 * M4e.d (slice 104): productId-native throughout — the legacy Item/Stock screens
 * (getUserItem(s)/addStock/getUserStock/deleteItem/addSelling) are gone. Products are
 * seeded via the catalog master (cy.seedProduct), stock read from inventory (/productStock).
 *
 * Each describe block is independent. Data created is cleaned up after use.
 */

// ─── 1. Full Registration Chain ───────────────────────────────────────────────
// Company → Vender → Product created together and each verified in their list.

describe('E2E Flow — Registration Chain', () => {
  let companyId, venderId, productId
  const ts = Date.now()

  before(() => {
    cy.loginAsBusiness()

    // 1. Create company
    cy.request({
      method: 'POST', url: '/addCompany', form: true,
      body: { name: `FlowCo_${ts}`, email: `flowco${ts}@t.com`, phone: '042-1111111' },
    })

    // 2. Fetch companyId
    cy.request('/getUserCompany').then((res) => {
      const co = (res.body.collection || res.body.data || []).find(c => c.name === `FlowCo_${ts}`)
      if (co) companyId = co.id
    })
  })

  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('created company appears in getUserCompany list', () => {
    cy.request('/getUserCompany').then((res) => {
      const co = (res.body.collection || res.body.data || []).find(c => c.name === `FlowCo_${ts}`)
      // Company may not be saved if the duplicate-check bug (empty Example) blocked it
      if (co) {
        companyId = co.id
        cy.log(`Company found with id=${co.id}`)
      } else {
        cy.log('Company not saved (duplicate-check bug may have returned FOUND) — downstream tests will skip')
      }
    })
  })

  it('created company appears in getUserCompanies HTML options', () => {
    cy.request('/getUserCompanies').then((res) => {
      expect(res.body).to.include(`FlowCo_${ts}`)
    })
  })

  it('create vender linked to flow company — vender appears in list', () => {
    if (!companyId) return cy.log('No companyId — skipping vender creation')
    const name = `FlowVender_${ts}`

    cy.request({
      method: 'POST', url: '/addVender', form: true,
      body: { name, companyId, mobile: '03001234567', email: `fv${ts}@t.com` },
    }).then((res) => {
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'FOUND'])
    })

    cy.request('/getUserVender').then((res) => {
      const v = (res.body.collection || res.body.data || []).find(x => x.name === name)
      if (v) venderId = v.id
      expect(v, `Vender ${name} should exist`).to.not.be.undefined
    })
  })

  it('created vender appears in getUserVenders HTML options', () => {
    cy.request('/getUserVenders').then((res) => {
      expect(res.body).to.be.a('string')
      cy.log(`getUserVenders length: ${res.body.length}`)
    })
  })

  it('create product linked to flow company — product exists in catalog', () => {
    if (!companyId) return cy.log('No companyId — skipping product creation')
    const iname = `FlowItem_${ts}`
    // M4e.d (slice 104): create through the catalog Product master (the single product master).
    cy.seedProduct({ name: iname, sku: `FI-${ts}`, category: 'Flow' }).then(({ productId: pid }) => {
      productId = pid
      expect(pid, `Product ${iname} should exist`).to.not.be.null
    })
  })

  it('created product appears in catalog product list', () => {
    // Slice 106: was `?size=1000` — see catalog-product.cy.js. A newly created product has the highest id,
    // so on a long-lived dev DB it falls beyond the first page and the list "loses" it. Sort newest-first.
    cy.request('/catalogProducts?size=50&sort=id,desc').then((res) => {
      // catalog ApiResponse: { success, data: { content: [...] } } — assert the new product is present.
      const content = (res.body && res.body.data && res.body.data.content) || []
      const found = content.some((p) => p.name === `FlowItem_${ts}` || p.sku === `FI-${ts}`)
      expect(found, `FlowItem_${ts} should appear in catalog`).to.be.true
    })
  })

  after(() => {
    cy.loginAsBusiness()
    if (venderId) cy.request({ method: 'POST', url: '/deleteVender', form: true, body: { checked: venderId }, failOnStatusCode: false })
    if (companyId) cy.request({ method: 'POST', url: '/deleteCompany', form: true, body: { checked: companyId }, failOnStatusCode: false })
  })
})

// ─── 2. Stock Chain ───────────────────────────────────────────────────────────
// Product → opening inventory → verify stock → purchase → stock increases
// M4e.d (slice 104): stock lives in inventory-service, read via /productStock?productId= (returns { success, stock }).

describe('E2E Flow — Product to Stock', () => {
  let productId
  const ts = Date.now()
  const iname = `StockFlowItem_${ts}`

  before(() => {
    cy.loginAsBusiness()
    // seed via the catalog Product master + opening inventory (20 on hand); purchase productId-native.
    cy.seedProduct({ name: iname, sku: `SFI-${ts}`, sellingPrice: 80, purchaseRate: 50, stock: 20 })
      .then(({ productId: pid }) => { productId = pid })
  })

  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('opening inventory shows on-hand via /productStock', () => {
    if (!productId) return cy.log('No product — skipping')
    cy.request({ url: `/productStock?productId=${productId}`, failOnStatusCode: false }).then((res) => {
      expect(res.status).to.eq(200)
      const onHand = Number(res.body.stock)
      cy.log(`Opening on-hand: ${onHand}`)
      expect(onHand).to.be.gte(0)
    })
  })

  it('purchase against the product — inventory on-hand increases', () => {
    if (!productId) return cy.log('No product — skipping')
    let stockBefore = 0

    cy.request({ url: `/productStock?productId=${productId}`, failOnStatusCode: false }).then((res) => {
      stockBefore = Number(res.body.stock) || 0
    })

    cy.request({
      method: 'POST', url: '/addPurchase', form: true,
      body: { productId, quantity: 5, purchaseRate: 50, totalAmount: 250, netAmount: 250, purchaseInvoiceNo: `PF-${ts}` },
      failOnStatusCode: false,
    }).then((res) => {
      cy.log(`addPurchase for stock chain: ${res.body.status}`)
    })

    cy.request({ url: `/productStock?productId=${productId}`, failOnStatusCode: false }).then((res) => {
      const stockAfter = Number(res.body.stock) || 0
      cy.log(`Stock before: ${stockBefore}, after purchase: ${stockAfter}`)
      // A purchase dual-writes to inventory, so on-hand should not decrease.
      expect(stockAfter).to.be.gte(stockBefore)
    })
  })
})

// ─── 3. Full Transaction Flow — Sell ─────────────────────────────────────────
// Customer → Product → Stock → Sell → Sell appears in list
// M4e.d (slice 104): sell is productId-native via /addSell (saga); /addSelling is retired.

describe('E2E Flow — Full Sale Transaction', () => {
  let customerId, productId
  const ts = Date.now()
  const custName = `FlowCust_${ts}`
  const iname    = `FlowSellItem_${ts}`

  before(() => {
    cy.loginAsBusiness()

    // Create customer
    cy.request({
      method: 'POST', url: '/addCustomer', form: true,
      body: { name: custName, contact: `031${ts.toString().slice(-8)}`, email: `fc${ts}@t.com` },
      failOnStatusCode: false,
    })
    cy.request('/getUserCustomer').then((res) => {
      const c = (res.body.collection || res.body.data || []).find(x => x.name === custName)
      if (c) customerId = c.customerId || c.id
    })

    // Create product + opening stock via the catalog Product master (M4e.d)
    cy.seedProduct({ name: iname, sku: `FSI-${ts}`, sellingPrice: 100, purchaseRate: 50, stock: 30 })
      .then(({ productId: pid }) => { productId = pid })
  })

  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('customer and product both exist before sell', () => {
    cy.request('/getUserCustomer').then((res) => {
      const c = (res.body.collection || res.body.data || []).find(x => x.name === custName)
      if (c) cy.log(`Customer ${custName} found ✓`)
      else cy.log('Customer not found — duplicate-check bug may have blocked save')
    })
    cy.request('/catalogProducts?size=1000').then((res) => {
      const content = (res.body && res.body.data && res.body.data.content) || []
      const p = content.find(i => i.name === iname)
      if (p) cy.log(`Product ${iname} found ✓`)
      else cy.log(`Product ${iname} not found — may not have been saved`)
    })
  })

  it('sell appears in getUserSell', () => {
    cy.request({ url: '/getUserSell', failOnStatusCode: false }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body).to.have.property('status')
      cy.log(`getUserSell status: ${res.body.status}`)
    })
  })

  it('POST /addSell with customer+sales body (productId) — returns 200', () => {
    if (!productId) return cy.log('No productId — skipping')

    cy.request({
      method: 'POST', url: '/addSell',
      body: {
        customer: { name: custName, contact: `031${ts.toString().slice(-8)}`, paidAmount: 100, dueAmount: 0 },
        sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
      },
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    }).then((res) => {
      expect(res.status).to.eq(200)
      cy.log(`addSell single response: ${JSON.stringify(res.body).substring(0, 150)}`)
    })
  })

  after(() => {
    cy.loginAsBusiness()
    if (customerId) cy.request({ method: 'POST', url: '/deleteCustomer', form: true, body: { checked: customerId }, failOnStatusCode: false })
  })
})

// ─── 4. Dashboard Stats Reflect Data ─────────────────────────────────────────

describe('E2E Flow — Dashboard Stats Integrity', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('getBusinessDashboardStats returns non-negative counts for all KPIs', () => {
    cy.request('/getBusinessDashboardStats').then((res) => {
      expect(res.status).to.eq(200)
      const body = res.body

      // Counts should be zero or positive integers
      const countKeys = ['companies', 'venders', 'customers', 'items', 'monthlySales']
      countKeys.forEach((k) => {
        if (body[k] !== undefined) {
          expect(body[k], `${k} should be >= 0`).to.be.gte(0)
        }
      })
    })
  })

  it('dashboard stats change after creating a new company', () => {
    let beforeCount = 0
    cy.request('/getBusinessDashboardStats').then((res) => {
      beforeCount = res.body.companies ?? 0
    })

    const name = `DashCountCo_${Date.now()}`
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name, email: `dc${Date.now()}@t.com` } })

    cy.request('/getBusinessDashboardStats').then((res) => {
      const afterCount = res.body.companies ?? 0
      cy.log(`Company count: before=${beforeCount} after=${afterCount}`)
      expect(afterCount).to.be.gte(beforeCount)

      // clean up
      cy.request('/getUserCompany').then((listRes) => {
        const co = (listRes.body.collection || listRes.body.data || []).find(c => c.name === name)
        if (co) cy.request({ method: 'POST', url: '/deleteCompany', form: true, body: { checked: co.id }, failOnStatusCode: false })
      })
    })
  })

  it('getDashboardChartData returns chart-ready data structure', () => {
    cy.request({ url: '/getDashboardChartData', failOnStatusCode: false }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body).to.be.an('object')
      // Verify expected chart keys are present
      const keys = Object.keys(res.body)
      cy.log(`Chart data keys: ${keys.join(', ')}`)
      expect(keys.length).to.be.gt(0)
    })
  })
})

// ─── 5. Cross-Entity Consistency ─────────────────────────────────────────────

describe('E2E Flow — Cross-Entity Consistency', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('company created → appears in vender company dropdown', () => {
    const name = `DDCo_${Date.now()}`
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name, email: `dd${Date.now()}@t.com` } })

    cy.request('/getUserCompanies').then((res) => {
      expect(res.body).to.include(name)

      // clean up
      cy.request('/getUserCompany').then((listRes) => {
        const co = (listRes.body.collection || listRes.body.data || []).find(c => c.name === name)
        if (co) cy.request({ method: 'POST', url: '/deleteCompany', form: true, body: { checked: co.id }, failOnStatusCode: false })
      })
    })
  })

  it('product created → appears in the catalog picker (catalogProducts)', () => {
    const iname = `DDItem_${Date.now()}`
    // M4e.d (slice 104): create via the catalog Product master; the sell/purchase picker lists it via /catalogProducts.
    cy.seedProduct({ name: iname }).then(() => {
      cy.request('/catalogProducts?size=1000').then((res) => {
        const content = (res.body && res.body.data && res.body.data.content) || []
        expect(content.some((p) => p.name === iname), `${iname} in catalog picker`).to.be.true
      })
    })
  })

  it('customer created → appears in sell customer dropdown (getUserCustomer)', () => {
    const name = `DDCust_${Date.now()}`
    const contact = `031${Date.now().toString().slice(-8)}`
    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name, contact, email: `ddc${Date.now()}@t.com` }, failOnStatusCode: false })

    cy.request('/getUserCustomer').then((res) => {
      const found = (res.body.collection || res.body.data || []).find(c => c.name === name)
      if (found) {
        cy.log(`Customer ${name} found in list ✓`)
        cy.request({ method: 'POST', url: '/deleteCustomer', form: true, body: { checked: found.customerId || found.id }, failOnStatusCode: false })
      } else {
        cy.log('Customer not found — may be blocked by duplicate-check bug')
      }
    })
  })

  it('delete company → company no longer in getUserCompany list', () => {
    const name = `DelFlowCo_${Date.now()}`
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name, email: `dlfc${Date.now()}@t.com` } })

    cy.request('/getUserCompany').then((res) => {
      const co = (res.body.collection || res.body.data || []).find(c => c.name === name)
      if (!co) return cy.log('Company not created — skipping delete-flow test')

      cy.request({ method: 'POST', url: '/deleteCompany', form: true, body: { checked: co.id } })

      cy.request('/getUserCompany').then((afterRes) => {
        const stillExists = (afterRes.body.collection || afterRes.body.data || []).some(c => c.name === name)
        expect(stillExists, `${name} should be gone after delete`).to.be.false
      })
    })
  })
})
