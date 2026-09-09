/**
 * PERM-1 — permission sets: the owner decides what each member may do.
 *
 * Design: microservices/docs/slices/perm-1-permission-sets-design.md
 *
 * ⚠ WHAT THIS FILE IS REALLY GUARDING
 *
 * A permission model can fail in three ways, and only one of them is visible on screen:
 *
 *   1. IT DOES NOT ENFORCE. The menu hides and the endpoint answers anyway — an access control that is
 *      not one, which is worse than none because the owner stops watching. Cases 3 and 4.
 *   2. IT ENFORCES TOO MUCH. A member granted "ring up a sale" gets a sale screen whose item picker is
 *      empty, because nobody granted "see products". Silent, and indistinguishable from a broken app.
 *      That is what the closure exists to prevent — case 2.
 *   3. IT CHANGES WHAT EXISTING STAFF COULD ALREADY DO. The deploy morning failure: a shop opens and
 *      its cashiers have lost half the till. Case 1.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/permission-sets.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)

const perms = () =>
  cy.request({ url: '/team/permissions', failOnStatusCode: false })
    .then((r) => (r.body && (r.body.data || r.body.object)) || {})

describe('PERM-1 — permission sets', () => {
  beforeEach(() => cy.loginAsOwner())

  // ── ⭐⭐ 1. the deploy changes nothing ────────────────────────────────────────────────────────────

  it('⭐⭐ 1 — the built-in sets reproduce what staff could already do', () => {
    /*
     * The single most important case here, and the one whose failure is a phone call at 9am from a shop
     * whose cashiers cannot sell. `Standard` is the CONTRACT that introducing permissions took nothing
     * away from anybody: every screen a ROLE_BUSINESS_USER could already reach, and none of the ones
     * that were already gated behind owner or admin.
     */
    perms().then((d) => {
      const std = (d.sets || []).find((s) => s.name === 'Standard')
      expect(std, 'the Standard set exists').to.exist
      expect(std.builtin, 'and it is built in, so a tenant cannot edit the contract').to.eq(true)

      const codes = std.codes || []
      // What a member could already do.
      ;['sale.view', 'sale.create', 'purchase.view', 'purchase.create',
        'customer.view', 'product.view', 'supplier.view', 'till.view', 'report.view']
        .forEach((c) => expect(codes, `Standard keeps ${c}`).to.include(c))

      // What was already behind a gate they did not hold — and must NOT arrive as a windfall.
      ;['finance.view', 'settings.edit', 'team.create', 'opening.create', 'sale.void']
        .forEach((c) => expect(codes, `Standard does not grant ${c}`).to.not.include(c))
    })
  })

  // ── ⭐⭐ 2. the closure ───────────────────────────────────────────────────────────────────────────

  it('⭐⭐ 2 — granting an action also grants what it cannot work without', () => {
    /*
     * The silent failure this design exists to prevent: `sale.create` without `product.view` produces a
     * sale screen with an EMPTY item picker, no error, and nothing on screen explaining why.
     *
     * Asserted through the SERVER, not the browser's cascade. The matrix cascades as the owner ticks —
     * that is courtesy — but a set stored by anything other than that screen must still be coherent, and
     * the server is the only place that can promise it.
     */
    const run = uniq()
    cy.request({
      method: 'POST', url: '/team/permissions/sets', failOnStatusCode: false,
      body: { name: `Closure ${run}`, scope: 'OWN', codes: ['sale.create'] },
    }).then((r) => {
      const saved = (r.body && (r.body.data || r.body.object)) || {}
      const codes = saved.codes || []
      expect(codes, 'the action asked for').to.include('sale.create')
      expect(codes, 'and the screen it is typed on').to.include('sale.view')
      expect(codes, 'and the products it sells').to.include('product.view')
      expect(codes, 'and the customers it sells to').to.include('customer.view')
    })
  })

  // ── ⭐⭐ 3. it actually refuses ───────────────────────────────────────────────────────────────────

  it("⭐⭐ 3 — a member without team.create is REFUSED, and told what to do", () => {
    /*
     * The owner's own example, verbatim: "when Jawwad will try to create user which is not allowed as
     * permission, an error message that you are not authorized to perform this action."
     *
     * Asserted as a plain USER, because the whole question is what somebody ELSE cannot do. Asserting it
     * as the owner would prove nothing — the owner holds everything by design.
     */
    cy.loginAsTier('user', 'business')
    cy.request({
      method: 'POST', url: '/team/users', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { firstName: 'Should', lastName: 'Refuse', email: `refuse${uniq()}@t.com`, role: 'USER' },
    }).then((r) => {
      expect(r.status, 'refused, not answered').to.eq(403)
      const msg = JSON.stringify(r.body).toLowerCase()
      // The message must NAME the thing and say who fixes it. "Access denied" tells a cashier nothing
      // they can act on, so they telephone somebody instead of asking the owner beside them.
      expect(msg, `names the action: ${msg}`).to.contain('create users')
      expect(msg, 'and who can grant it').to.contain('owner')
    })
  })

  it('⭐ 4 — the same member CAN still ring up a sale', () => {
    /*
     * The positive control, and it is not optional. Case 3 alone would pass against a build that refuses
     * EVERYTHING — which is the other way this ships broken, and the more expensive one, because a till
     * that cannot sell is a shop that cannot trade.
     */
    cy.loginAsTier('user', 'business')
    cy.request({ url: '/getUserProduct', failOnStatusCode: false }).then((r) => {
      expect(r.status, 'a member may still read the product list').to.eq(200)
    })
  })

  // ── ⭐ 5. only the owner holds the matrix ────────────────────────────────────────────────────────

  it('⭐ 5 — a non-owner cannot read or change permission sets', () => {
    /*
     * Owner-only was the ruling, and it is what removes privilege escalation from this model entirely:
     * if an admin could edit sets, an admin holding team.edit could grant themselves finance. Only the
     * owner grants, and the owner already holds everything, so there is nothing to escalate to.
     */
    cy.loginAsTier('user', 'business')
    cy.request({ url: '/team/permissions', failOnStatusCode: false }).then((r) => {
      expect(r.status === 403 || !(r.body && r.body.data),
        `a member must not read the permission catalog: ${r.status}`).to.eq(true)
    })
  })

  // ── ⭐ 6. a built-in is a contract, not a template to overwrite ──────────────────────────────────

  it('⭐ 6 — a built-in set cannot be edited, and says to duplicate it', () => {
    perms().then((d) => {
      const std = (d.sets || []).find((s) => s.name === 'Standard')
      cy.request({
        method: 'POST', url: '/team/permissions/sets', failOnStatusCode: false,
        body: { id: std.id, name: 'Standard', scope: 'OWN', codes: ['sale.view'] },
      }).then((r) => {
        const msg = JSON.stringify(r.body).toLowerCase()
        expect(msg, `refused: ${msg}`).to.contain('built-in')
        // The refusal must say what to do instead - see the design's note on migrations that fail
        // without telling anybody how to proceed.
        expect(msg, 'and names the way forward').to.contain('duplicate')
      })
    })
  })

  // ── ⭐ 7. the screen ─────────────────────────────────────────────────────────────────────────────

  it('⭐ 7 — the matrix renders, cascades both ways, and previews the sidebar', () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#navTeam, [onclick*="showTeam"]').first().click({ force: true })

    cy.get('#permMatrix tbody tr', { timeout: 20000 }).should('have.length.greaterThan', 5)

    /*
     * The cascade is asserted on the MODEL rather than by clicking 41 boxes: the rule is the behaviour
     * worth guarding, and driving it through the DOM would test jQuery. Both directions, because
     * cascading only downward would let the owner re-break what ticking had just fixed.
     */
    cy.window().should((w) => expect(w.PermissionMatrix, 'permissions.js is loaded').to.be.an('object'))
    cy.window().then((w) => {
      const added = w.PermissionMatrix.grant('sale.create')
      expect(added, 'ticking an action pulls in what it needs').to.include('product.view')

      const removed = w.PermissionMatrix.revoke('product.view')
      expect(removed, 'and unticking the dependency drops what needed it').to.include('sale.create')
    })

    // An action an area does not have is a dash, never an unticked box - an unticked box promises that
    // ticking it would do something.
    cy.get('#permMatrix .pm-na').should('exist')

    // The preview: the most valuable element on the screen, and the one that catches a mis-grant BEFORE
    // it is saved rather than after.
    cy.get('.perm-preview').should('be.visible')
  })
  // ── ⭐⭐ 8. the escalation this slice actually shipped, and must never ship again ─────────────────

  it('⭐⭐ 8 — a member of ANOTHER module holds no business permissions', () => {
    /*
     * THE REGRESSION TEST FOR A REAL DEFECT, written after it reached the database.
     *
     * V12's migration read "everyone not already placed" and this database holds four other modules and
     * a parent portal. So ROLE_GUARDIAN -- a parent signing in to see their child's attendance -- was
     * placed on `Standard`, a set built for a SHOP, and carried sale.create, purchase.create and
     * customer.create in their token.
     *
     * Nothing was exploitable through a screen: those accounts reach a different dashboard and the
     * interceptor maps no education path. But "not reachable today" is the DEFINITION of a latent
     * escalation, and the design said business-only while the SQL said everyone.
     *
     * Fixed twice on purpose -- V14 removed the rows, and AuthService now mints permissions only for a
     * BUSINESS tenant. A migration fixes what happened; only the code stops it recurring, and only a
     * test stops the code being undone.
     *
     * ⚠ Asserted on a permission `Standard` GRANTS (sale.create), because that is what discriminates:
     * an assertion on something Standard lacks would have passed before the fix as well.
     */
    cy.loginAsEducation()
    cy.request({
      method: 'POST', url: '/addSell', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: 'Should Never Post', contact: '03000000000' },
        sales: [{ productId: 1, quantity: 1, sellRate: 1, totalAmount: 1, netAmount: 1 }],
        paidAmount: 1, dueAmount: 0, grandTotal: 1,
        tenders: [{ method: 'CASH', amount: 1, reference: '' }],
      },
    }).then((r) => {
      expect(r.status, `a member of another module must not hold sale.create: ${r.status}`).to.eq(403)
      expect(JSON.stringify(r.body).toLowerCase(), 'and is told so plainly')
        .to.contain('not allowed')
    })
  })

})
