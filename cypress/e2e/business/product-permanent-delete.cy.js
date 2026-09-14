/**
 * PROD-DEL — Delete deactivates an ACTIVE product; Delete on a DEACTIVATED product removes it permanently.
 *
 * The user's ruling (2026-09-14): permanent delete is the OWNER's alone; a product still referenced by anything
 * (stock, sales, orders, dispensing, a price rule, a bonus scheme) is kept and the reason given; a mixed selection is
 * handled product by product and reported; a repeat answers "already removed".
 * Design: microservices/docs/slices/prod-del-product-permanent-delete.md
 *
 * ⚠ What makes these cases test something: every "deleted" case reads the product back and requires it GONE; every
 * "kept" case reads it back and requires it STILL THERE. A report alone could say "deleted" over a row that stayed.
 *
 * Needs commerce-contracts + common-service installed, and catalog, business, inventory and the monolith rebuilt.
 * Run SOLO as owner.business@ (maximumSessions(1)).
 *   npx cypress run --headed --spec cypress/e2e/business/product-permanent-delete.cy.js
 */

const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)

const addProduct = (name) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    body: { name, sellingPrice: 100, idempotencyKey: `prod-del-${uniq()}` }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.body && r.body.success, `addProduct ${name}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    return r.body.data
  })

const remove = (ids) =>
  cy.request({
    method: 'POST', url: '/removeProducts', headers: { 'Content-Type': 'application/json' },
    body: { checked: [].concat(ids).join(',') }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `removeProducts: ${JSON.stringify(r.body).slice(0, 300)}`).to.eq(200)
    expect(r.body && r.body.success, `removeProducts answered: ${JSON.stringify(r.body).slice(0, 300)}`).to.eq(true)
    return r.body
  })

/** The product as catalog holds it now, or null once it is gone. */
const read = (id) =>
  cy.request({ url: `/getCatalogProduct?id=${id}`, failOnStatusCode: false })
    .then((r) => (r.body && r.body.data) || null)

describe('PROD-DEL — deactivate first, then delete permanently', () => {
  beforeEach(() => cy.loginAsOwner())

  it('⭐ 1 — Delete on an ACTIVE product deactivates it, and it still exists', () => {
    addProduct(`ProdDel Active ${uniq()}`).then((p) => {
      remove(p.id).then((res) => {
        expect(res.deactivated, JSON.stringify(res)).to.eq(1)
        expect(res.deleted, 'nothing is deleted permanently from an active product').to.eq(0)
      })
      read(p.id).then((now) => {
        expect(now, 'the product still exists').to.be.an('object')
        expect(now.isActive, 'and is deactivated').to.eq(false)
      })
    })
  })

  it('⭐⭐ 2 — Delete on a DEACTIVATED, unused product removes it permanently; a repeat answers "already removed"', () => {
    addProduct(`ProdDel Unused ${uniq()}`).then((p) => {
      remove(p.id)                                   // active → deactivated
      remove(p.id).then((res) => {                   // deactivated → deleted
        expect(res.deleted, JSON.stringify(res)).to.eq(1)
        expect(res.kept, 'nothing was kept').to.have.length(0)
      })
      read(p.id).then((now) => expect(now, '⭐ the product is GONE').to.eq(null))
      remove(p.id).then((res) => {
        expect(res.alreadyRemoved, `a repeat is idempotent: ${JSON.stringify(res)}`).to.eq(1)
        expect(res.kept, 'and is not reported as kept').to.have.length(0)
      })
    })
  })

  it('⭐⭐ 3 — a deactivated product WITH STOCK is kept, and the message says why', () => {
    addProduct(`ProdDel Stocked ${uniq()}`).then((p) => {
      cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId: p.id, quantity: 5, batchNo: `PD${uniq()}`, purchasePrice: 60 }, failOnStatusCode: false,
      }).then((s) => expect(s.status, `seeding stock: ${JSON.stringify(s.body).slice(0, 200)}`).to.eq(200))

      remove(p.id)                                   // deactivate
      remove(p.id).then((res) => {
        expect(res.deleted, JSON.stringify(res)).to.eq(0)
        expect(res.kept, 'the product is kept').to.have.length(1)
        expect(String(res.kept[0].reason), '⭐ and the reason names the stock').to.match(/stock/i)
      })
      read(p.id).then((now) => expect(now, '⭐ the product still exists').to.be.an('object'))
    })
  })

  it('⭐ 4 — a MIXED selection is handled product by product, and reported', () => {
    addProduct(`ProdDel Mix A ${uniq()}`).then((stillActive) => {
      addProduct(`ProdDel Mix B ${uniq()}`).then((toDelete) => {
        remove(toDelete.id)                          // B is now deactivated
        remove([stillActive.id, toDelete.id]).then((res) => {
          expect(res.deactivated, JSON.stringify(res)).to.eq(1)
          expect(res.deleted).to.eq(1)
          expect(String(res.message), 'one sentence reports both').to.match(/deactivated/).and.to.match(/deleted/)
        })
        read(stillActive.id).then((now) => expect(now && now.isActive).to.eq(false))
        read(toDelete.id).then((now) => expect(now).to.eq(null))
      })
    })
  })

  it('⭐⭐ 5 — a NON-OWNER deactivates, but cannot delete permanently', () => {
    const run = uniq()
    let parked = null
    addProduct(`ProdDel Parked ${run}`).then((p) => { parked = p })
    cy.then(() => remove(parked.id))                 // the owner deactivates it

    cy.loginAsTier('admin', 'business')
    addProduct(`ProdDel Admin ${run}`).then((mine) => {
      remove(mine.id).then((res) => expect(res.deactivated, 'the admin can still deactivate').to.eq(1))
    })
    cy.then(() => remove(parked.id)).then((res) => {
      expect(res.deleted, JSON.stringify(res)).to.eq(0)
      expect(res.kept).to.have.length(1)
      expect(String(res.kept[0].reason), '⭐ only the owner may delete permanently').to.match(/owner/i)
    })
    cy.then(() => read(parked.id)).then((now) => expect(now, 'the product was not deleted').to.be.an('object'))
  })
})
