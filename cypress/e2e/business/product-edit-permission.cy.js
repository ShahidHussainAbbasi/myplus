/**
 * PERM-1 — "Edit a product" is a permission that actually restricts.
 *
 * ── The defect ──────────────────────────────────────────────────────────────────────────────────
 * product.edit sat in the permission matrix ("Edit a product") and governed nothing: PermissionInterceptor had
 * no rule for POST /updateProduct, unmapped paths are allowed, and the product grid drew an Edit button for
 * every member. An owner who withheld "Edit a product" from a set had changed nothing at all.
 *
 * ── How the fixture is made to test something ───────────────────────────────────────────────────
 * The member is SEEDED, not inherited: a new set holding product.view + product.create and NOT product.edit,
 * assigned to user.business@, and asserted to lack product.edit before any case relies on it. A fresh
 * sign-in with a unique session key follows, because the member's authorities are fixed when they sign in:
 * a cached session would still carry the old set and the refusal would never fire. after() puts the member
 * back on Standard (permission-sets.cy.js's convention) and deletes the set.
 *
 * Needs the monolith rebuilt with the interceptor rule, the template flag and the grid hook.
 *   npx cypress run --headed --spec cypress/e2e/business/product-edit-permission.cy.js
 */

const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)
const PW = 'Demo@2025!'
const run = uniq()

const body = (r) => (r.body && (r.body.data || r.body.object)) || {}
const perms = () => cy.request({ url: '/team/permissions', failOnStatusCode: false }).then(body)

/** Sign in as the plain member with a session key unique to this run, so the new set is what they carry. */
const asNoEditMember = () =>
  cy.loginAs('user.business@myplus.com', PW, '/getBusinessDashboardStats', `noedit-${run}`)

const addProduct = (name) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    body: { name, sellingPrice: 100, idempotencyKey: `perm-edit-${uniq()}` }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.body && r.body.success, `addProduct ${name}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    return r.body.data
  })

const update = (p, name) =>
  cy.request({
    method: 'POST', url: '/updateProduct', headers: { 'Content-Type': 'application/json' },
    body: { id: p.id, name, sellingPrice: 100, version: p.version }, failOnStatusCode: false,
  })

/*
 * ⚠ HELD (2026-09-14) — describe.skip on purpose, not a forgotten test. The product.edit interceptor rule this
 * gates was pulled before build: pharmacy staff (org type PHARMA) carry no PERM-1 codes, so the rule would take
 * product editing from every non-owner pharmacy member. Remove `.skip` in the same change that re-adds the rule.
 */
describe.skip('PERM-1 — "Edit a product" restricts what it names', () => {
  let userId = null
  let setId = null

  before(() => {
    cy.loginAsOwner()
    cy.request({
      method: 'POST', url: '/team/permissions/sets', failOnStatusCode: false,
      body: { name: `NoEdit ${run}`, scope: 'ALL', codes: ['product.view', 'product.create'] },
    }).then((r) => {
      const saved = body(r)
      setId = saved.id
      expect(setId, `the set was created: ${JSON.stringify(r.body).slice(0, 200)}`).to.exist
      // ⚠ Existence is not eligibility: the case below proves nothing unless this set really lacks it.
      expect(saved.codes || [], 'the seeded set may create products').to.include('product.create')
      expect(saved.codes || [], '⭐ and may NOT edit them').to.not.include('product.edit')
    })
    cy.request({ url: '/team/users', failOnStatusCode: false }).then((r) => {
      const rows = body(r)
      const member = (Array.isArray(rows) ? rows : []).find((u) => String(u.email || '').startsWith('user.business@'))
      expect(member, 'the plain member is in this tenant').to.exist
      userId = member.userId || member.id
    })
    cy.then(() =>
      cy.request({ method: 'POST', url: '/team/permissions/assign', failOnStatusCode: false, body: { userId, setId } })
        .then((r) => expect(r.body && r.body.success, `assign: ${JSON.stringify(r.body)}`).to.eq(true)))
  })

  after(() => {
    // Leave no server state behind: the member goes back to Standard, and the seeded set is removed.
    cy.loginAsOwner()
    perms().then((d) => {
      const std = (d.sets || []).find((s) => s.name === 'Standard')
      cy.request({ method: 'POST', url: '/team/permissions/assign', failOnStatusCode: false,
        body: { userId, setId: std && std.id } })
      if (setId) cy.request({ method: 'DELETE', url: `/team/permissions/sets/${setId}`, failOnStatusCode: false })
    })
  })

  it('⭐⭐ 1 — a member WITHOUT "Edit a product" is refused the edit, told why, and the product is untouched', () => {
    asNoEditMember()
    // Data populates at this tier: the member CAN create (they hold product.create) and see what they made.
    addProduct(`PermEdit ${run}`).then((p) => {
      update(p, `PermEdit ${run} CHANGED`).then((r) => {
        expect(r.status, `the edit is refused: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(403)
        expect(String(r.body && r.body.message), '⭐ and the refusal names the action')
          .to.match(/not allowed to edit products/i)
      })
      cy.request(`/getCatalogProduct?id=${p.id}`).then((g) => {
        expect(body(g).name, 'the refused edit changed nothing').to.eq(`PermEdit ${run}`)
      })
    })
  })

  it('⭐ 2 — and the SCREEN offers them no Edit button (a button that always refuses is not an affordance)', () => {
    asNoEditMember()
    cy.visit('/businessDashboard')
    cy.window().its('showProducts').should('be.a', 'function')
    cy.window().then((w) => {
      expect(w.canEditProduct, 'the server did not mark this member as able to edit products').to.not.eq(true)
      w.showProducts()
    })
    // Rows load for this tier (data populates), and not one of them carries an Edit button.
    cy.get('#tableProduct tbody tr td', { timeout: 60000 }).should('have.length.greaterThan', 1)
    cy.get('#tableProduct .js-edit-row').should('not.exist')
  })

  it('⭐⭐ 3 — the OWNER still edits, and still sees the button (the rule restricts only who lacks it)', () => {
    cy.loginAsOwner()
    addProduct(`PermEdit Owner ${run}`).then((p) => {
      update(p, `PermEdit Owner ${run} CHANGED`).then((r) => {
        expect(r.body && r.body.success, `the owner's edit lands: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
      })
    })
    cy.visit('/businessDashboard')
    cy.window().its('showProducts').should('be.a', 'function')
    cy.window().then((w) => {
      expect(w.canEditProduct, 'the owner holds product.edit').to.eq(true)
      w.showProducts()
    })
    cy.get('#tableProduct .js-edit-row', { timeout: 60000 }).should('have.length.greaterThan', 0)
  })
})
