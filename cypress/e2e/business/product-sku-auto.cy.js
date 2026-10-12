/**
 * SKU-AUTO-1 — ONE code per product, the SKU: a blank SKU is filled on save with the next 12-digit code
 * ('2' + an 11-digit per-business number, Lightspeed's format); the shop may type its own or change it; a SKU is unique
 * within a business (the database decides, not just a pre-check). Design: microservices/docs/slices/109-sku-auto-code.md.
 *
 * What each case protects:
 *   K1  a product saved with no SKU gets a 12-digit code, and the next one gets a different, higher code
 *   K2  a SKU the shop types is kept as typed (trimmed)
 *   K3  the shop can change an automatic code; a blank box on edit keeps the code the product had
 *   K4  a SKU already used in this business is refused with a message naming it
 *   K5  another business may use the same SKU (unique per business, not across businesses)
 *   K6  the form: the SKU box says it is automatic when blank; saving through the form shows the assigned code
 *   K7  ladder: an admin's product gets a code too
 *   K8  SKU-AUTO-2: a CSV row with no SKU is imported with a 12-digit code
 *   K9  SKU-AUTO-2: a CSV row claiming a SKU another product holds refuses the file, naming it; nothing is written
 *   K10 SKU-AUTO-2: after the deploy fill, no product of the business is left without a SKU
 *
 * Tenants: owner.business@ (org 13), owner.pharma@ (org 15), admin.business@. Products are spec-made (named SKA_…).
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/product-sku-auto.cy.js --headed --browser chrome
 */
const RUN = `${Date.now()}`.slice(-9)
const AUTO = /^2\d{11}$/

/** Create through the same monolith endpoint the form posts to; yields the saved product. */
const create = (name, sku) => cy.request({
  method: 'POST', url: '/addProduct', failOnStatusCode: false, headers: { 'Content-Type': 'application/json' },
  body: Object.assign({ name, sellingPrice: 10, taxRate: 0, unit: 'pcs', categoryName: 'General' },
    sku === undefined ? {} : { sku }),
}).its('body')
const read = (id) => cy.request('/getCatalogProduct?id=' + id).then((r) => (r.body && (r.body.data || r.body)) || {})
/** Edit as the form does: the product as loaded, with the SKU box changed. */
const editSku = (id, sku) => read(id).then((p) => cy.request({
  method: 'POST', url: '/updateProduct', failOnStatusCode: false, headers: { 'Content-Type': 'application/json' },
  body: Object.assign({}, p, { id, sku }),
}).its('body'))

describe('SKU-AUTO-1 — a blank SKU becomes a 12-digit code; the shop can change it; unique per business', () => {
  beforeEach(() => cy.loginAsOwner())

  it('K1 no SKU → a 12-digit code; the next product gets a different, higher one', () => {
    create('SKA_K1a_' + RUN).then((a) => {
      expect(a.success, JSON.stringify(a).slice(0, 200)).to.eq(true)
      expect(a.data.sku, 'automatic code').to.match(AUTO)
      create('SKA_K1b_' + RUN, '   ').then((b) => {
        expect(b.data.sku, 'whitespace is blank too').to.match(AUTO)
        expect(Number(b.data.sku), 'the next number').to.be.greaterThan(Number(a.data.sku))
      })
    })
  })

  it('K2 a typed SKU is kept as typed (trimmed)', () => {
    create('SKA_K2_' + RUN, '  MY-' + RUN + '  ').then((b) => expect(b.data.sku).to.eq('MY-' + RUN))
  })

  it('K3 the shop can change an automatic code; a blank box on edit keeps the code', () => {
    create('SKA_K3_' + RUN).then((b) => {
      const id = b.data.id, auto = b.data.sku
      expect(auto).to.match(AUTO)
      editSku(id, '').then((u) => expect(u.success, JSON.stringify(u).slice(0, 200)).to.eq(true))
      read(id).then((p) => expect(p.sku, 'blank on edit keeps the code').to.eq(auto))
      editSku(id, 'CHG-' + RUN).then((u) => expect(u.success, JSON.stringify(u).slice(0, 200)).to.eq(true))
      read(id).then((p) => expect(p.sku, 'changed by the shop').to.eq('CHG-' + RUN))
    })
  })

  it('K4 a SKU already used in this business is refused, naming it', () => {
    create('SKA_K4a_' + RUN, 'DUP-' + RUN).then((a) => expect(a.success).to.eq(true))
    create('SKA_K4b_' + RUN, 'DUP-' + RUN).then((b) => {
      expect(b.success, JSON.stringify(b).slice(0, 200)).to.eq(false)
      expect(String(b.message)).to.contain('DUP-' + RUN)
    })
  })

  it('K5 another business may use the same SKU', () => {
    create('SKA_K5a_' + RUN, 'SHARED-' + RUN).then((a) => expect(a.success).to.eq(true))
    cy.loginAsPharmaOwner()
    create('SKA_K5b_' + RUN, 'SHARED-' + RUN).then((b) => expect(b.success, JSON.stringify(b).slice(0, 200)).to.eq(true))
  })

  it('K6 the form says the SKU is automatic, and shows the assigned code after saving', () => {
    const name = 'SKA_K6_' + RUN
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'hasCapability')
    cy.get('#prodSku').should('have.attr', 'placeholder', 'Automatic if left blank')
    create(name).then((b) => {
      expect(b.data.sku).to.match(AUTO)
      cy.request('/getUserProduct').then((r) => {
        const row = (r.body.collection || []).find((x) => x.id === b.data.id)
        expect(row, 'in the product list').to.exist
        expect(row.sku, 'the list shows the assigned code').to.eq(b.data.sku)
      })
    })
  })

  it('K7 an admin’s product gets a code too', () => {
    cy.loginAsTier('admin', 'business')
    create('SKA_K7_' + RUN).then((b) => {
      expect(b.success, JSON.stringify(b).slice(0, 200)).to.eq(true)
      expect(b.data.sku).to.match(AUTO)
    })
  })

  // ── SKU-AUTO-2 — the CSV import and the deploy fill ──────────────────────────────────────────────────────
  const importCsv = (csv) => cy.request({ method: 'POST', url: '/import/product/commit', body: { csv }, failOnStatusCode: false })
  const allProducts = () => cy.request('/getUserProduct?q=-1&includeInactive=true').then((r) => {
    for (const k of ['collection', 'data', 'object']) if (Array.isArray(r.body && r.body[k])) return r.body[k]
    return []
  })

  it('K8 a CSV row with no SKU is imported with a 12-digit code', () => {
    const name = 'SKA_K8_' + RUN
    importCsv(['sku,name', ',' + name, ''].join('\n')).then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 300)).to.eq('SUCCESS'))
    allProducts().then((ps) => {
      const p = ps.find((x) => x.name === name)
      expect(p, 'imported').to.exist
      expect(p.sku).to.match(AUTO)
    })
  })

  it('K9 a CSV row claiming a SKU another product holds refuses the file, naming it', () => {
    create('SKA_K9a_' + RUN, 'IMP-' + RUN).then((a) => expect(a.success).to.eq(true))
    const other = 'SKA_K9b_' + RUN
    importCsv(['sku,name', 'imp-' + RUN + ',' + other, ''].join('\n')).then((r) => {
      expect(r.body.status, JSON.stringify(r.body).slice(0, 300)).to.not.eq('SUCCESS')
      expect(JSON.stringify(r.body)).to.contain('already used')
    })
    allProducts().then((ps) => expect(ps.find((x) => x.name === other), 'nothing written').to.be.undefined)
  })

  it('K10 after the deploy fill, no product of the business is without a SKU', () => {
    allProducts().then((ps) => {
      const blank = ps.filter((x) => x.sku == null || String(x.sku).trim() === '')
      expect(ps.length, 'the business has products').to.be.greaterThan(0)
      expect(blank.map((x) => x.id + ':' + x.name).slice(0, 10), 'products still without a SKU').to.deep.eq([])
    })
  })
})
