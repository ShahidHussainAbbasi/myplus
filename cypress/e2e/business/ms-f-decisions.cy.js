/**
 * MS-F1 / MS-F2 / MM-2 (complete) — three things the MapStruct oracle surfaced, decided by the user on 4 Oct 2026.
 *
 * Design: microservices/docs/slices/ms-1-mapstruct-migration.md §6.
 * - MS-F2: a record with no time showed the CURRENT time (the old mapper ran its converters on nulls). Now: blank.
 *   PREVENTIVE — on 4 Oct no row these screens list had a null (the 61 null-`updated` purchases are opening bills,
 *   which the purchase list skips: no product), so no screen can show the red. The proof is the unit test
 *   MapStructOracleTest#nullDatesAreBlank (old mapper → "now", new → blank); this spec guards the grid's rendering.
 * - MS-F1: "All vendors" showed a supplier's stored advance even on BUSINESS payables. Now it builds the same row as
 *   the vendor list.
 * - MM-2: all seven getAll* endpoints return DTOs — five more (Company, Customer, ItemType, ItemUnit, Vender) returned
 *   raw entities, found while fixing MS-F1.
 * Read-only: nothing here writes.
 */

const PW = 'Demo@2025!'
const LIFECYCLE = 'owner.lifecycle@myplus.com'
const rowsOf = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return [] }

describe('MS-F2 — a purchase with no time shows blank, not "now"', () => {
  // a fresh session key: a cached lifecycle session can hold a refresh token the 5-session cap already removed
  beforeEach(() => cy.loginAs(LIFECYCLE, PW, '/getBusinessDashboardStats', 'msf-' + Date.now()))

  it('the purchase grid never prints "null" as a purchase date', () => {
    cy.openPurchaseSection('purchaseDiv')
    cy.get('#tablePurchase tbody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)
    cy.get('#tablePurchase #purchaseDate').should('have.length.greaterThan', 0).each(($d) => {
      expect($d.text().trim()).to.not.eq('null')
    })
  })
})

describe('MS-F1 — "all vendors" builds the same row as the vendor list', () => {
  beforeEach(() => cy.loginAsOwner())

  it('advance, bills, total and source agree for every supplier', () => {
    cy.request('/getUserVender').then((u) => {
      const user = {}
      rowsOf(u.body).forEach((v) => { user[v.id] = v })
      expect(Object.keys(user).length, 'suppliers to compare').to.be.greaterThan(0)
      cy.request('/getAllVender').then((a) => {
        const all = rowsOf(a.body)
        expect(all.length).to.eq(Object.keys(user).length)
        all.forEach((v) => {
          const w = user[v.id]
          ;['advance', 'billsOwed', 'totalOwed', 'payablesSource'].forEach((k) =>
            expect(String(v[k]), `supplier ${v.id} ${k}`).to.eq(String(w[k])))
          if (v.payablesSource === 'BUSINESS') expect(Number(v.advance), `supplier ${v.id}: no advance on BUSINESS`).to.eq(0)
        })
      })
    })
  })
})

describe('MM-2 — every getAll* endpoint returns DTOs, never database entities', () => {
  beforeEach(() => cy.loginAsOwner())

  // An entity carries the tenant column; a DTO never does. One field, present on all seven entities, absent from all DTOs.
  ;['/getAllCompany', '/getAllCustomer', '/getAllItemType', '/getAllItemUnit', '/getAllVender', '/getAllPurchase', '/getAllSell']
    .forEach((url) => it(`${url} — no organizationId, dates in the screen format`, () => {
      cy.request({ url, failOnStatusCode: false }).then((r) => {
        // /getAllItemType is not routed through the monolith at all (its own spec allows the 404); nothing to check there
        if (url === '/getAllItemType' && r.status === 404) { cy.log('getAllItemType: not exposed by the monolith'); return }
        expect(r.status).to.eq(200)
        const rows = rowsOf(r.body)
        if (r.body.status === 'NOT_FOUND') return
        expect(rows.length, `${url} has rows to check`).to.be.greaterThan(0)
        rows.forEach((x) => {
          expect(x, `${url} row ${x.id || ''}`).to.not.have.property('organizationId')
          if (x.dated != null && typeof x.dated === 'string') expect(x.dated).to.not.match(/^\d{4}-\d{2}-\d{2}T/)
        })
      })
    }))
})
