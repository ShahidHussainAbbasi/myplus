/**
 * PH-FORMULA — a medicine's formula (generic / salt composition) on the product form.
 * Design: microservices/docs/slices/pharma-formula.md
 *
 *   1 the Pharmacy preset shows the Formula row on the product form; Retail hides it (opt-in field)
 *   2 hidden ≠ deleted: saving a product while the row is hidden keeps its formula
 *   3 one formula typed two ways is ONE suggestion
 *   4 at the till, typing a formula in the item picker finds every brand with it
 *   5 a tenant never sees another tenant's formulas
 *
 * ⚠ Settings are org-wide. This spec changes only the PRESET (never the explicit formula switch, which has no
 * reset and would outrank the preset forever) and puts the tenant's original preset back in after().
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/pharma-formula.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value } })
    .its('body.success').should('eq', true)

const settingValue = (key) => cy.request('/getBusinessConfig').then((r) => {
  const e = ((r.body && r.body.data) || []).find((x) => x.key === key)
  return e ? e.value : null
})

/** A product with a formula, through the same /addProduct the form posts to. Yields its id. */
const addProduct = (name, formula) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    body: { name, sku: `F${uniq()}`, sellingPrice: 50, taxRate: 0, unit: 'pcs', formula },
  }).then((r) => {
    expect(r.body && r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
    return r.body.data.id
  })

/** The product exactly as the edit form loads it (GET /getCatalogProduct). */
const productFormula = (id) =>
  cy.request(`/getCatalogProduct?id=${id}`).then((r) => {
    const p = (r.body && (r.body.data || r.body.object || r.body.product)) || {}
    expect(p.id, `getCatalogProduct: ${JSON.stringify(r.body).slice(0, 160)}`).to.exist
    return p.formula
  })

const openProductForm = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().its('posFields', { timeout: 15000 }).should('be.an', 'object')
}

describe('PH-FORMULA — the medicine formula field', () => {
  let originalPreset
  before(() => {
    cy.loginAsOwner()
    settingValue('pos.entry.preset').then((v) => { originalPreset = v || 'CUSTOM' })
  })
  after(() => {
    cy.loginAsOwner()
    setConfig('pos.entry.preset', originalPreset)
  })
  beforeEach(() => cy.loginAsOwner())

  it('⭐ 1 — Pharmacy preset shows the Formula row; Retail hides it', () => {
    setConfig('pos.entry.preset', 'PHARMACY')
    openProductForm()
    cy.window().its('posFields.formula').should('eq', true)
    cy.get('#ProductModal [data-pos-field="formula"]').should('not.have.class', 'pos-hidden')

    setConfig('pos.entry.preset', 'RETAIL')
    openProductForm()
    cy.window().its('posFields.formula').should('eq', false)
    cy.get('#ProductModal [data-pos-field="formula"]').should('have.class', 'pos-hidden')
  })

  it('⭐⭐ 2 — saving a product while the row is HIDDEN keeps its formula', () => {
    setConfig('pos.entry.preset', 'PHARMACY')
    const name = `FormA_${uniq()}`
    addProduct(name, 'Paracetamol 500mg').then((id) => {
      setConfig('pos.entry.preset', 'RETAIL')              // the row is now hidden
      openProductForm()
      cy.window().then((w) => {
        // Save through the form's own code path, exactly as the Save button does.
        cy.intercept('POST', '**/updateProduct').as('upd')
        w.editProduct(id)
      })
      cy.get('#ProductModal', { timeout: 15000 }).should('have.class', 'open')
      cy.get('#prodName').should('have.value', name)
      cy.get('#ProductModal [data-pos-field="formula"]').should('have.class', 'pos-hidden')
      cy.get('#addProduct').click({ force: true })   // the modal's Save (saveProduct)
      cy.wait('@upd').then((i) => {
        expect(i.request.body, 'a hidden field is NOT sent').not.to.have.property('formula')
      })
      productFormula(id).should('eq', 'Paracetamol 500mg')
    })
  })

  it('⭐ 3 — one formula typed two ways is ONE suggestion', () => {
    const f = `Zinc${uniq()} 20mg`
    addProduct(`BrandA_${uniq()}`, f)
    addProduct(`BrandB_${uniq()}`, `  ${f.toLowerCase().replace(' ', '   ')} `)
    cy.request('/formulas').then((r) => {
      expect(r.body.success).to.eq(true)
      const hits = r.body.formulas.filter((x) => x.toLowerCase() === f.toLowerCase())
      expect(hits, 'case and spacing do not split a formula').to.have.length(1)
    })
  })

  it('⭐⭐ 4 — at the till, typing a formula lists every brand with it', () => {
    setConfig('pos.entry.preset', 'PHARMACY')
    const tag = uniq()
    const f = `Cetirizine${tag} 10mg`
    addProduct(`Zyrtec_${tag}`, f)
    addProduct(`Rigix_${tag}`, f)
    openProductForm()
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellItemDD').parent().find('button.dropdown-toggle').click({ force: true })
    cy.get('#sellItemDD').parent().find('.bs-searchbox input').type(`cetirizine${tag}`, { force: true })
    cy.get('#sellItemDD').parent().find('ul.dropdown-menu li:not(.hide)')
      .should(($li) => {
        const text = $li.text()
        expect(text, 'both brands').to.contain(`Zyrtec_${tag}`).and.to.contain(`Rigix_${tag}`)
      })
  })

  it('⭐ 5 — another tenant never sees this tenant\'s formulas', () => {
    const secret = `Private${uniq()} 5mg`
    addProduct(`Mine_${uniq()}`, secret)
    cy.loginAsPharmaOwner()
    cy.request('/formulas').its('body.formulas').should('not.include', secret)
  })
})
