/**
 * CACHE-2 — categories, tax codes and manufacturers are cache-aside, tenant-scoped, and evicted after commit.
 *
 * Design: microservices/docs/slices/cache-1-tenant-cache-aside.md §8 · Standard: SAAS-BUILD-STANDARDS.md §1d
 *
 * ⚠ EVERY INVALIDATION CASE WARMS THE CACHE FIRST (standard K6): read the list, THEN write, THEN read again. A case
 * that writes before its first read runs against a cold cache and passes identically with no eviction at all.
 *
 * Accounts: owner.business@ in the SESSION for org A (all org-A writes through the monolith). Two things the monolith
 * has no route for go through the GATEWAY as admin.business@ — org A too, ADMIN, holds DELETE_PRIVILEGE: renaming and
 * deleting a category. Never a second owner.business@ login (it would expire this session). owner.pharma@ is org B.
 *
 * Cleanup — every row a case creates it removes: tax codes via /deleteTaxCode; products via /removeProducts TWICE
 * (the first deactivates, the second deletes an already-deactivated product for good — and the manufacturers list
 * counts inactive products, so only the second call drops the name); categories via the gateway DELETE.
 *
 * The "it fires" case reads each cache's counter from inside the catalog container (`docker exec … wget`).
 * ⚠ cy.exec launches through $SHELL — under Git Bash start Cypress with SHELL unset (`env -u SHELL npx cypress run …`).
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/catalog-refs-cache.cy.js
 */

const GW = 'http://localhost:8765/api/catalog'

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const byId = (list, id) => list.find((x) => String(x.id) === String(id))

/** Org A's categories, the way the Product form reads them (monolith → catalog). */
const categories = () =>
  cy.request('/getUserCategories').then((r) => {
    expect(r.body && r.body.success, `/getUserCategories: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    return r.body.categories || []
  })

/** Org A's tax codes — /catalogTaxCodes answers a RAW ARRAY (possibly as a string). Only an array is a list. */
const taxCodes = () =>
  cy.request('/catalogTaxCodes').then((r) => {
    const body = typeof r.body === 'string' ? JSON.parse(r.body) : r.body
    expect(Array.isArray(body), `/catalogTaxCodes must answer an array: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    return body
  })

/** Org A's distinct manufacturer names. */
const manufacturers = () =>
  cy.request('/manufacturers').then((r) => {
    expect(r.body && r.body.success, `/manufacturers: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    return r.body.manufacturers || []
  })

/** A monolith write that must really happen — a refusal here would make every later assertion meaningless. */
const post = (url, body) =>
  cy.request({ method: 'POST', url, headers: { 'Content-Type': 'application/json' }, body, failOnStatusCode: false })
    .then((r) => {
      expect(r.body && r.body.success, `${url} must succeed: ${JSON.stringify(r.body).slice(0, 300)}`).to.eq(true)
      return r
    })

/** Create or update a tax code. The catalog answers the saved code itself (no `success` flag), so the id IS the proof. */
const saveTaxCode = (body) =>
  cy.request({ method: 'POST', url: '/saveTaxCode', headers: { 'Content-Type': 'application/json' }, body,
    failOnStatusCode: false }).then((r) => {
    expect(r.status, 'saveTaxCode').to.eq(200)
    expect(r.body && r.body.id, `saveTaxCode must answer the saved code: ${JSON.stringify(r.body).slice(0, 300)}`)
      .to.be.a('number')
    return r.body
  })

/** Org A through the gateway as its ADMIN — for the two category writes the monolith does not route. */
const asAdmin = (fn) => cy.asOtherTenant(fn, 'admin.business@myplus.com')

const renameCategory = (id, name) =>
  asAdmin((headers) => cy.request({ method: 'PUT', url: `${GW}/categories/${id}`, headers, body: { name },
    failOnStatusCode: false }).then((r) => {
    expect(r.status, `rename category ${id}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
  }))

const deleteCategory = (id) =>
  asAdmin((headers) => cy.request({ method: 'DELETE', url: `${GW}/categories/${id}`, headers,
    failOnStatusCode: false }).then((r) => {
    expect([200, 204], `delete category ${id}: ${JSON.stringify(r.body).slice(0, 200)}`).to.include(r.status)
  }))

/** Deactivate, then delete for good (PROD-DEL: the second call deletes an already-deactivated product). */
const removeForGood = (productId) => {
  post('/removeProducts', { checked: String(productId) })
  post('/removeProducts', { checked: String(productId) }).then((r) => {
    expect(r.body.kept || [], `product ${productId} must be deleted for good, not kept`).to.have.length(0)
  })
}

/** Org A's product id by exact name — for a row the CSV import created. */
const productIdByName = (name) =>
  cy.request('/catalogProductPicker?page=0&size=2000').then((r) => {
    const page = (r.body && r.body.data) ? r.body.data : r.body
    const row = ((page && page.content) || []).find((p) => p.name === name)
    expect(row, `imported product "${name}" must exist`).to.exist
    return row.id
  })

/** One cache's hit counter (Micrometer cache.gets{cache=<name>,result=hit}). */
const hits = (name) =>
  cy.exec('docker exec myplus-catalog wget -qO- '
    + `"http://localhost:8092/actuator/metrics/cache.gets?tag=cache:${name}&tag=result:hit"`,
  { failOnNonZeroExit: false }).then((res) => {
    expect(res.code, `the ${name} metric must exist — is CACHE-2 deployed? ${res.stderr || res.stdout}`).to.eq(0)
    return JSON.parse(res.stdout).measurements[0].value
  })

const CSV_HEADERS =
  'sku,name,description,categoryName,unit,manufacturer,sellingPrice,taxRate,barcode,rxRequired,controlledSubstance,'
  + 'packSize,looseUnit,looseUnitPlural,allowLoose'

describe('CACHE-2 — categories, tax codes and manufacturers are cache-aside and evicted after commit', () => {
  beforeEach(() => {
    cy.loginAsOwner()
  })

  it('⭐⭐ 1 — a category added, renamed and deleted after the list was cached shows in the very next read', () => {
    const name = `CACHE2 cat ${uniq()}`
    categories()   // WARM
    post('/addCategory', { name }).then((r) => {
      const id = r.body.data.id
      categories().then((list) => expect(byId(list, id),
        `"${name}" must be in the next read — a stale list would hide it for up to 5 minutes`).to.exist)

      renameCategory(id, `${name} renamed`)
      categories().then((list) => expect(byId(list, id).name, 'the rename shows at once').to.eq(`${name} renamed`))

      deleteCategory(id)
      categories().then((list) => expect(byId(list, id), 'a deleted category leaves the next read').to.not.exist)
    })
  })

  it('⭐ 2 — a product saved with a NEW free-text category puts that category in the next read', () => {
    const cat = `CACHE2 formcat ${uniq()}`
    categories()   // WARM
    cy.seedProduct({ name: `CACHE2 formprod ${uniq()}`, category: cat }).then(({ productId }) => {
      categories().then((list) => {
        const c = list.find((x) => x.name === cat)
        expect(c, 'the Product form created the category — it must be offered at once').to.exist
        removeForGood(productId)
        deleteCategory(c.id)
      })
    })
  })

  it('⭐ 3 — a CSV import naming a NEW category (no transaction of its own) puts it in the next read', () => {
    const run = uniq()
    const cat = `CACHE2 impcat ${run}`
    const name = `CACHE2 import ${run}`
    const csv = `${CSV_HEADERS}\nC2IMP${run},${name},,${cat},pcs,,15,0,,,,,,,\n`

    categories()   // WARM
    cy.request({ method: 'POST', url: '/import/product/commit', body: { csv }, failOnStatusCode: false }).then((r) => {
      expect(r.status, `import commit: ${JSON.stringify(r.body).slice(0, 300)}`).to.eq(200)
    })
    categories().then((list) => {
      const c = list.find((x) => x.name === cat)
      expect(c, 'the import created the category — it must be offered at once').to.exist
      productIdByName(name).then((productId) => removeForGood(productId))
      deleteCategory(c.id)
    })
  })

  it('⭐⭐ 4 — a tax code created, re-rated and deleted after the list was cached shows in the very next read', () => {
    const name = `CACHE2 tax ${uniq()}`
    taxCodes()   // WARM
    saveTaxCode({ name, rate: 3, isDefault: false }).then(({ id }) => {
      taxCodes().then((list) => expect(byId(list, id), 'created → in the next read').to.exist)

      saveTaxCode({ id, name, rate: 4, isDefault: false })
      taxCodes().then((list) => expect(Number(byId(list, id).rate),
        'the product form must offer the NEW rate at once — the cached row carries it').to.eq(4))

      post('/deleteTaxCode', { id })
      taxCodes().then((list) => expect(byId(list, id), 'deleted → gone from the next read').to.not.exist)
    })
  })

  it('⭐ 5 — a new manufacturer is in the next read; deleting its only product removes it', () => {
    const mfr = `CACHE2 Mfr ${uniq()}`
    manufacturers()   // WARM
    cy.seedProduct({ name: `CACHE2 mfrprod ${uniq()}`, manufacturer: mfr }).then(({ productId }) => {
      manufacturers().then((list) => expect(list, 'a product write evicts the manufacturers').to.include(mfr))
      removeForGood(productId)
      manufacturers().then((list) => expect(list,
        'the only product carrying it is gone for good — so is the name').to.not.include(mfr))
    })
  })

  it('⭐⭐ 6 — another tenant never sees this tenant\'s category, tax code or manufacturer, cached or not', () => {
    const run = uniq()
    const mfr = `CACHE2 tenancy Mfr ${run}`
    post('/addCategory', { name: `CACHE2 tenancy cat ${run}` }).then((rc) => {
      const catId = rc.body.data.id
      saveTaxCode({ name: `CACHE2 tenancy tax ${run}`, rate: 1, isDefault: false }).then(({ id: taxId }) => {
        cy.seedProduct({ name: `CACHE2 tenancy prod ${run}`, manufacturer: mfr }).then(({ productId }) => {
          cy.asOtherTenant((headers) => {
            // Twice: the first read fills org B's entry, the second is served FROM the cache — both must be B's own.
            [1, 2].forEach((n) => {
              cy.request({ url: `${GW}/categories`, headers }).then((r) => {
                expect(r.status, `org B categories read ${n}`).to.eq(200)
                expect(byId(r.body.data || [], catId), `org B read ${n}: no org-A category`).to.not.exist
              })
              cy.request({ url: `${GW}/tax-codes`, headers }).then((r) => {
                expect(Array.isArray(r.body), `org B tax codes read ${n} is a list`).to.eq(true)
                expect(byId(r.body, taxId), `org B read ${n}: no org-A tax code`).to.not.exist
              })
              cy.request({ url: `${GW}/products/manufacturers`, headers }).then((r) => {
                expect(r.status, `org B manufacturers read ${n}`).to.eq(200)
                expect(r.body.data || [], `org B read ${n}: no org-A manufacturer`).to.not.include(mfr)
              })
            })
          }, 'owner.pharma@myplus.com')

          removeForGood(productId)
          post('/deleteTaxCode', { id: taxId })
          deleteCategory(catId)
        })
      })
    })
  })

  it('⭐ 7 — all three caches FIRE: a repeat read is counted as a hit, not merely declared', () => {
    [['catalog.categories', categories], ['catalog.tax-codes', taxCodes], ['catalog.manufacturers', manufacturers]]
      .forEach(([name, read]) => {
        hits(name).then((before) => {
          read()
          read()
          hits(name).then((after) => {
            expect(after, `${name}: repeat reads must be served from the cache (hits ${before} → ${after})`)
              .to.be.greaterThan(before)
          })
        })
      })
  })

  it('⭐⭐ 8 — REAL UI: a category quick-added on the Product form is SELECTED in its dropdown', () => {
    // The path a stale list breaks silently: addCategoryInline reloads the list and selects the new id — if the
    // reload is served the OLD cached list, .val(id) matches no option, nothing is selected, and the product saves
    // uncategorised without a word.
    const name = `CACHE2 ui ${uniq()}`
    categories()   // WARM — the list the form will reload is cached
    cy.intercept('POST', '**/addCategory').as('addCategory')
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showProducts())
    cy.waitForAppReady()
    cy.get('#newProduct').click({ timeout: 20000 })
    cy.get('#ProductModal').should('have.class', 'open')

    cy.get('#prodCategoryNew').should('be.visible').type(name)
    cy.get('button[onclick="addCategoryInline()"]').click()

    cy.wait('@addCategory').then(({ response }) => {
      expect(response.body && response.body.success, JSON.stringify(response.body)).to.eq(true)
      const id = response.body.data.id
      cy.get('#prodCategory', { timeout: 10000 }).should('have.value', String(id))
      cy.get('#prodCategory option:selected').should('have.text', name)
      deleteCategory(id)
    })
  })
})
