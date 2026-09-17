/**
 * PERM-2 — a PHARMACY member holds the same permissions a shop member holds.
 *
 * Design: microservices/docs/slices/perm-2-pharma-permission-codes.md
 *
 * THE DEFECT (live, found 2026-09-14, raised again by the user on 2026-09-17 as "the New product button is not
 * visible to my pharma user"): PERM-1 codes were minted only for an organisation of type BUSINESS. A pharmacy is
 * type PHARMA yet reuses the commerce core entirely — /businessDashboard, the same till, the same purchase screen,
 * the same PermissionInterceptor map. So every non-owner pharmacy member carried ZERO codes: refused every mapped
 * action, and blind to every affordance gated on one.
 *
 * ⚠ WHY NO GATE CAUGHT IT: every pharmacy spec signs in as an OWNER (passes the server check via ROLE_OWNER) or a
 * DEMO account (DEMO_ROLE carries SUPER_PRIVILEGE, which bypasses the matrix). This spec deliberately signs in as
 * NEITHER — user.pharma@ (ROLE_PHARMA_USER) and admin.pharma@ (ADMIN_ROLE), org 15 — because the hole is exactly
 * the shape of the accounts nobody tested with.
 *
 * ⚠ THE BUTTON AND THE SERVER DISAGREE BY DESIGN, and case 3 pins it: PermissionInterceptor lets ROLE_OWNER and
 * SUPER_PRIVILEGE through whatever the matrix says, but the template check is a literal
 * sec:authorize="hasAuthority('product.create')" with no such bypass. Before this fix that made "Register a new
 * product" invisible to the pharmacy's OWNER as well — a button hidden from the one person who could have used it.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/pharmacy/pharma-permissions.cy.js
 */

const PW = 'Demo@2025!'
const STAFF = 'user.pharma@myplus.com'     // ROLE_PHARMA_USER — a plain pharmacy member
const ADMIN = 'admin.pharma@myplus.com'    // ADMIN_ROLE — should land on Administrator
const OWNER = 'owner.pharma@myplus.com'    // ROLE_OWNER — holds no SET, but must still SEE the button

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

/** What the gateway actually mints for this account — the token is where the defect lives. */
const privilegesOf = (email) =>
  cy.request({
    method: 'POST', url: 'http://localhost:8765/api/auth/login',
    headers: { 'Content-Type': 'application/json' },
    body: { email, password: PW }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
    const d = (r.body && r.body.data) || {}
    expect(d.privileges, 'the login response carries the privilege list the monolith builds its session from')
      .to.be.an('array')
    return d.privileges
  })

describe('PERM-2 — a pharmacy member can do what a shop member can do', () => {
  it('⭐⭐ 1 — a plain pharmacy member holds product.create at all', () => {
    privilegesOf(STAFF).then((privs) => {
      expect(privs, 'a pharmacy member was minted NO permission codes before PERM-2 — this held zero dotted codes')
        .to.include('product.create')
      expect(privs, 'and the rest of a shop member\'s reach comes with it').to.include.members(
        ['sale.create', 'purchase.create', 'product.view'])
    })
  })

  it('⭐ 2 — a pharmacy ADMIN holds the wider Administrator reach, not merely Standard', () => {
    privilegesOf(ADMIN).then((privs) => {
      expect(privs, 'Administrator mirrors what ADMIN_PRIVILEGE already opened').to.include.members(
        ['product.create', 'settings.edit'])
    })
  })

  it('⭐⭐ 3 — the OWNER sees the affordance too: the template check has no owner bypass', () => {
    privilegesOf(OWNER).then((privs) => {
      expect(privs,
        'sec:authorize is a literal authority check, so without this the owner could not SEE "Register a new '
        + 'product" even though the server would have allowed the POST').to.include('product.create')
    })
  })

  it('⭐⭐ 4 — REAL UI: a pharmacy member sees "Register a new product" on the purchase screen', () => {
    cy.loginAs(STAFF, PW, '/getBusinessDashboardStats')
    // Same navigation PUR-INLINE's own gate uses, so this opens the screen the way that spec proved works.
    cy.openPurchaseSection('purchaseDiv')
    cy.get('#newPurchase').click()
    cy.get('#PurchaseModal', { timeout: 20000 }).should('have.class', 'open')
    // The element renders only when the session holds product.create — its absence IS what the user reported.
    cy.get('#newProductFromPurchase', { timeout: 20000 }).should('be.visible')
  })

  it('⭐⭐ 5 — and the action itself is allowed, not merely offered', () => {
    cy.loginAs(STAFF, PW, '/getBusinessDashboardStats')
    const name = `PharmaPerm ${uniq()}`
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: `PP${uniq()}`, sellingPrice: 10, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      // Before PERM-2 this answered the interceptor's refusal: "You are not allowed to add products".
      expect(`${r.body && r.body.message} ${r.body && r.body.status}`,
        `a pharmacy member must not be refused: ${JSON.stringify(r.body).slice(0, 200)}`)
        .to.not.match(/not allowed/i)
      expect(r.body && r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)

      // Leave no product behind: deactivate, then delete the (now inactive) row for good.
      const id = r.body.data.id
      cy.request({ method: 'POST', url: '/removeProducts', headers: { 'Content-Type': 'application/json' },
        body: { checked: String(id) }, failOnStatusCode: false })
      cy.request({ method: 'POST', url: '/removeProducts', headers: { 'Content-Type': 'application/json' },
        body: { checked: String(id) }, failOnStatusCode: false })
    })
  })

  it('⭐ 6 — a NON-trading module is still refused: the GUARDIAN keeps no shop permissions (the V14 scar)', () => {
    // This is the exact account V14 was written for: V12 placed every user on a shop set, so a parent signing in
    // to see their child's attendance carried sale.create. PERM-2 widens the mint by exactly one vertical, and
    // this case is what stops it widening any further.
    privilegesOf('guardian.education@myplus.com').then((privs) => {
      expect(privs, 'a parent holds no shop permissions').to.not.include('product.create')
      expect(privs, 'nor the till').to.not.include('sale.create')
    })
  })
})
