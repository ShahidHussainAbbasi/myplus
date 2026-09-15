/**
 * CACHE-1 — the catalog product picker is cached per tenant + user + page, and evicted AFTER every committed write.
 *
 * Design: microservices/docs/slices/cache-1-tenant-cache-aside.md · Standard: SAAS-BUILD-STANDARDS.md §1d
 *
 * ⚠ EVERY INVALIDATION CASE WARMS THE CACHE FIRST (standard K6): read the picker, THEN write, THEN read again. A case
 * that writes before its first read runs against a cold cache and passes identically with no eviction at all — it
 * would test nothing. The warm read is the line that makes each case able to fail.
 *
 * Accounts: owner.business@ in the SESSION for org A — every org-A write goes through the monolith, so the owner is
 * never logged in a second time (one session per user would expire this one). owner.pharma@ is the second tenant,
 * through the GATEWAY with a Bearer token (cy.asOtherTenant).
 *
 * The "it fires" case reads the cache's own counter from inside the catalog container — actuator is exposed on the
 * compose network only, so it is `docker exec … wget`, which needs Docker on the machine running Cypress.
 * ⚠ cy.exec launches through $SHELL. Under Git Bash that is "C:\Program Files\Git\...\bash.exe", and the space breaks
 * the launch (exit 127, "/c/Program: Files…: No such file or directory" — first run, 2026-09-15). Run from PowerShell
 * or cmd, or start Cypress with SHELL unset (`env -u SHELL npx cypress run …`).
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/catalog-cache-aside.cy.js
 */

const PICKER = '/catalogProductPicker?page=0&size=2000'
const GATEWAY_PICKER = 'http://localhost:8765/api/catalog/products/picker?page=0&size=2000'

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const rows = (body) => {
  const page = (body && body.data) ? body.data : body
  return (page && Array.isArray(page.content)) ? page.content : []
}

/** Org A's picker, the way the till reads it (monolith proxy → catalog). */
const picker = () =>
  cy.request(PICKER).then((r) => {
    expect(r.status, `picker: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
    return rows(r.body)
  })

const rowOf = (list, id) => list.find((p) => String(p.id) === String(id))

/** A monolith write that must really happen — a refusal here would make every later assertion meaningless. */
const post = (url, body) =>
  cy.request({ method: 'POST', url, headers: { 'Content-Type': 'application/json' }, body, failOnStatusCode: false })
    .then((r) => {
      expect(r.body && r.body.success, `${url} must succeed: ${JSON.stringify(r.body).slice(0, 300)}`).to.eq(true)
      return r
    })

/** The picker cache's hit counter (Micrometer cache.gets{cache=catalog.picker,result=hit}). */
const pickerHits = () =>
  cy.exec('docker exec myplus-catalog wget -qO- '
    + '"http://localhost:8092/actuator/metrics/cache.gets?tag=cache:catalog.picker&tag=result:hit"',
  { failOnNonZeroExit: false }).then((res) => {
    expect(res.code, `the catalog.picker metric must exist — is CACHE-1 deployed? ${res.stderr || res.stdout}`).to.eq(0)
    return JSON.parse(res.stdout).measurements[0].value
  })

const CSV_HEADERS =
  'sku,name,description,categoryName,unit,manufacturer,sellingPrice,taxRate,barcode,rxRequired,controlledSubstance,'
  + 'packSize,looseUnit,looseUnitPlural,allowLoose'

describe('CACHE-1 — the product picker is cache-aside, tenant-scoped, and evicted after commit', () => {
  beforeEach(() => {
    cy.loginAsOwner()
  })

  it('⭐⭐ 1 — a product created AFTER the page was cached is in the very next read', () => {
    picker()   // WARM — org A's page 0 is now cached
    cy.seedProduct({ name: `CACHE1 new ${uniq()}`, sellingPrice: 11 }).then(({ productId, name }) => {
      picker().then((list) => {
        expect(rowOf(list, productId),
          `"${name}" must be in the next read — a stale cached page would hide it for up to 5 minutes`).to.exist
      })
    })
  })

  it('⭐⭐ 2 — an edit that changes the price shows the NEW price on the next read', () => {
    cy.seedProduct({ name: `CACHE1 price ${uniq()}`, sellingPrice: 20 }).then(({ productId, name }) => {
      picker().then((list) => {   // WARM, with the old price in the cached row
        expect(Number(rowOf(list, productId).sellingPrice), 'cached at the old price').to.eq(20)
      })
      cy.request(`/getCatalogProduct?id=${productId}`).then((r) => {
        const version = r.body && r.body.data && r.body.data.version
        post('/updateProduct', { id: productId, name, sellingPrice: 27, version })
      })
      picker().then((list) => {
        expect(Number(rowOf(list, productId).sellingPrice),
          'the till must quote the new price at once — the cached row carries sellingPrice').to.eq(27)
      })
    })
  })

  it('⭐ 3 — deactivate drops it from the next read; reactivate brings it back', () => {
    cy.seedProduct({ name: `CACHE1 active ${uniq()}`, sellingPrice: 30 }).then(({ productId }) => {
      picker().then((list) => expect(rowOf(list, productId), 'warm: listed while active').to.exist)

      post('/deactivateProduct', { checked: String(productId) })
      picker().then((list) => expect(rowOf(list, productId), 'a deactivated product must leave the picker').to.not.exist)

      post('/activateProduct', { id: productId })
      picker().then((list) => expect(rowOf(list, productId), 'reactivated → back in the picker').to.exist)
    })
  })

  it('⭐ 4 — a CSV import (the writer with NO transaction of its own) is in the next read', () => {
    const run = uniq()
    const sku = `C1IMP${run}`
    const name = `CACHE1 import ${run}`
    const csv = `${CSV_HEADERS}\n${sku},${name},,General,pcs,,15,0,,,,,,,\n`

    picker()   // WARM
    cy.request({ method: 'POST', url: '/import/product/commit', body: { csv }, failOnStatusCode: false }).then((r) => {
      expect(r.status, `import commit: ${JSON.stringify(r.body).slice(0, 300)}`).to.eq(200)
    })
    picker().then((list) => {
      expect(list.some((p) => p.name === name),
        'an imported product must be in the next read — import commits via saveAll with no surrounding transaction')
        .to.eq(true)
    })
  })

  it('⭐⭐ 5 — another tenant never sees this tenant\'s product, cached or not', () => {
    cy.seedProduct({ name: `CACHE1 tenancy ${uniq()}`, sellingPrice: 40 }).then(({ productId }) => {
      picker().then((list) => expect(rowOf(list, productId), 'org A sees its own product').to.exist)

      cy.asOtherTenant((headers) => {
        // Twice: the first read fills org B's page, the second is served FROM the cache — both must be B's own.
        [1, 2].forEach((n) => {
          cy.request({ url: GATEWAY_PICKER, headers }).then((r) => {
            expect(r.status, `org B picker read ${n}`).to.eq(200)
            expect(rowOf(rows(r.body), productId), `org B read ${n} must not contain org A's product`).to.not.exist
          })
        })
      }, 'owner.pharma@myplus.com')
    })
  })

  it('⭐ 6 — the cache FIRES: a repeat read is counted as a hit, not merely declared', () => {
    pickerHits().then((before) => {
      picker()
      picker()
      pickerHits().then((after) => {
        expect(after, `repeat reads must be served from the cache (hits ${before} → ${after})`).to.be.greaterThan(before)
      })
    })
  })

  it('⭐⭐ 7 — REAL UI: a product created after the cache was warm is offered on the sale screen', () => {
    picker()   // WARM — the server-side page the sale screen will read is cached
    cy.seedProduct({ name: `CACHE1 till ${uniq()}`, sellingPrice: 55 }).then(({ productId, name }) => {
      cy.visitSaleScreen()
      cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 })
        .should('exist')
        .and('contain.text', name)
    })
  })
})
