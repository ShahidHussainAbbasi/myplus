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

  /*
   * ⚠ RESTORE THE MEMBER IN A HOOK, not at the end of a case.
   *
   * Case 15 moves user.business@ onto a throwaway set and put them back with a trailing cy.then — which never ran
   * when an assertion in the case failed, because Cypress abandons the rest of a failing test. A run on 2026-09-17
   * therefore left that member on "Scope <run>" with scope ALL, i.e. seeing the whole shop, for every later spec
   * and every later run. An after() hook runs whatever the cases did.
   */
  after(() => {
    cy.loginAsOwner()
    cy.request({ url: '/team/users', failOnStatusCode: false }).then((r) => {
      const member = ((r.body && (r.body.data || r.body.object)) || [])
        .find((u) => String(u.email || '').startsWith('user.business@'))
      if (!member) return
      perms().then((d) => {
        const std = (d.sets || []).find((x) => x.name === 'Standard')
        if (!std) return
        cy.request({ method: 'POST', url: '/team/permissions/assign', failOnStatusCode: false,
          body: { userId: member.userId || member.id, setId: std.id } })
      })
    })
  })

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
      /*
       * ⚠ START FROM A CLEAN STATE. grant() returns what it ADDED, and adding a permission already held
       * adds nothing — so asserting the cascade against whichever set happens to load first proved
       * nothing the moment that set was Administrator, which holds everything. It returned [] and the
       * case failed against a cascade that works perfectly.
       *
       * Revoking first is not tidying: it is what makes the next line an actual observation.
       */
      w.PermissionMatrix.revoke('sale.create')
      w.PermissionMatrix.revoke('product.view')
      w.PermissionMatrix.revoke('customer.view')

      const added = w.PermissionMatrix.grant('sale.create')
      expect(added, 'ticking an action pulls in what it needs').to.include('product.view')
      expect(added, 'and the customers it sells to').to.include('customer.view')

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
    /*
     * ⚠ A PLAIN MEMBER, and choosing the wrong one made this case a false alarm.
     *
     * cy.loginAsEducation() signs in demo.education@, whose DEMO_ROLE carries SUPER_PRIVILEGE — and the
     * interceptor treats that as holding everything, deliberately: a super could do all of this before
     * PERM-1 existed, and taking it away on deploy is exactly the G-5 failure the built-in sets exist to
     * prevent. So the demo account passes, correctly, and proves nothing about module isolation.
     *
     * user.education@ holds ROLE_EDUCATION_USER and nothing else. Verified by hand before this was
     * written: the plain member gets 403 and the demo account gets 200, which is both rules working.
     */
    cy.loginAs('user.education@myplus.com', 'Demo@2025!', '/getDashboardData')
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

    /*
     * THE OTHER HALF, pinned so nobody later "fixes" it.
     *
     * A SUPER_PRIVILEGE holder passes every permission check, and that is deliberate rather than an
     * oversight: a super could do all of this before PERM-1 existed, and removing that on the deploy
     * that introduces permissions is precisely the failure the built-in sets exist to prevent. Without
     * this assertion the bypass looks like a hole and somebody removes it, taking access away from every
     * super in the product on the morning they do.
     */
    cy.loginAsEducation()   // demo.education@ — DEMO_ROLE, which carries SUPER_PRIVILEGE
    cy.request({
      method: 'POST', url: '/addSell', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { customer: { name: 'Super Probe', contact: '03001111111' }, sales: [],
              paidAmount: 0, dueAmount: 0, grandTotal: 0 },
    }).then((r) => {
      expect(r.status, 'a SUPER is not stopped by the matrix, by design').to.not.eq(403)
    })
  })

  // ── the three controls on the screen: picker, name, scope ───────────────────────────────────────

  it('⭐ 9 — a set ROUND-TRIPS: name, scope and codes come back as saved', () => {
    /*
     * The screen has three controls beside the matrix -- permSetPicker, permSetName, permScope -- and a
     * control that does not survive a reload is worse than no control: the owner believes they set
     * something. Asserted by reading the set BACK rather than trusting the save response, because a
     * response can echo what it was handed without ever storing it.
     */
    const run = uniq()
    const name = `RoundTrip ${run}`
    cy.request({
      method: 'POST', url: '/team/permissions/sets', failOnStatusCode: false,
      body: { name, scope: 'ALL', codes: ['purchase.create'] },
    }).then((r) => {
      const id = ((r.body && (r.body.data || r.body.object)) || {}).id
      expect(id, 'the set was created').to.exist

      perms().then((d) => {
        const back = (d.sets || []).find((x) => String(x.id) === String(id))
        expect(back, 'and it is in the picker').to.exist
        expect(back.name, 'permSetName survived').to.eq(name)
        expect(back.scope, 'permScope survived').to.eq('ALL')
        expect(back.codes, 'and the closure was stored, not just the tick')
          .to.include.members(['purchase.create', 'purchase.view', 'product.view', 'supplier.view'])
      })
    })
  })

  it('⭐ 10 — editing a set UPDATES it rather than making a second one', () => {
    /*
     * The failure this catches is quiet: a save that inserts instead of updating leaves the owner with
     * two sets of the same name, some members on the old one, and no way to tell which is live.
     */
    const run = uniq()
    cy.request({
      method: 'POST', url: '/team/permissions/sets', failOnStatusCode: false,
      body: { name: `Editable ${run}`, scope: 'OWN', codes: ['sale.view'] },
    }).then((r) => {
      const id = ((r.body && (r.body.data || r.body.object)) || {}).id
      cy.request({
        method: 'POST', url: '/team/permissions/sets', failOnStatusCode: false,
        body: { id, name: `Renamed ${run}`, scope: 'ALL', codes: ['purchase.create'] },
      }).then(() => {
        perms().then((d) => {
          const mine = (d.sets || []).filter((x) => x.name.endsWith(String(run)))
          expect(mine.length, 'one set, renamed - not two').to.eq(1)
          expect(mine[0].scope, 'and the scope changed with it').to.eq('ALL')
          expect(mine[0].codes, 'and the old codes are gone').to.not.include('sale.view')
        })
      })
    })
  })

  // ── ⭐⭐ 11. the cross-tenant write this probing actually found ──────────────────────────────────

  it('⭐⭐ 11 — a set cannot be assigned to somebody in ANOTHER business', () => {
    /*
     * A REAL DEFECT, found by asking the running system to do it and being told "Saved."
     *
     * assign() scoped the SET from the start, so an owner could not borrow another tenant's set. Nothing
     * scoped the USER -- so an owner could post any userId at all and rewrite the permissions of a member
     * of a completely different business. A cross-tenant WRITE, the same class the organizationIdFor
     * ruling was made about, and it wrote a real row before this test existed.
     *
     * user id 1 is deliberately somebody outside this tenant. If the refusal ever regresses, this fails
     * loudly here rather than silently in somebody else's shop.
     */
    perms().then((d) => {
      const anySet = (d.sets || []).find((x) => x.name === 'Standard')
      cy.request({
        method: 'POST', url: '/team/permissions/assign', failOnStatusCode: false,
        body: { userId: 1, setId: anySet.id },
      }).then((r) => {
        const msg = JSON.stringify(r.body).toLowerCase()
        expect(msg, `an outsider must be refused: ${msg}`).to.contain('not in this business')
        expect(msg, 'and it must NOT report success').to.not.contain('"success":true')
      })
    })
  })

  // ── ⭐ 12. a refusal must arrive as a sentence, not as "internal server error" ───────────────────

  it('⭐ 12 — a refusal reaches the owner intact, quotes and all', () => {
    /*
     * Also found by probing. Every deliberate refusal here reached the screen as
     * "Internal server error: ..." because auth-service's own advice turned ValidationException into a
     * 500, and the built-in one arrived as a single BACKSLASH because the proxy pulled the sentence out
     * with a regex that stops at the first quote -- and that message names the set in quotes.
     *
     * Two faults compounding, and the casualty was the only part of a refusal worth reading: what to do
     * instead. Asserted on the WORDS, because that is what was lost.
     */
    perms().then((d) => {
      const builtin = (d.sets || []).find((x) => x.builtin && x.name === 'Cashier')
      cy.request({
        method: 'POST', url: '/team/permissions/sets', failOnStatusCode: false,
        body: { id: builtin.id, name: 'Cashier', scope: 'OWN', codes: ['sale.view'] },
      }).then((r) => {
        const msg = String((r.body && r.body.message) || '')
        expect(msg, `the sentence survived: "${msg}"`).to.contain('built-in')
        expect(msg, 'and names the way forward').to.contain('uplicate')
        expect(msg.toLowerCase(), 'and is not dressed up as a server fault')
          .to.not.contain('internal server error')
      })
    })
  })

  it('⭐ 13 — a set somebody is on cannot be deleted', () => {
    // Deleting it would strip those members silently. The refusal names how many and what to do.
    perms().then((d) => {
      const std = (d.sets || []).find((x) => x.name === 'Standard')
      cy.request({ method: 'DELETE', url: `/team/permissions/sets/${std.id}`, failOnStatusCode: false })
        .then((r) => {
          const msg = String((r.body && r.body.message) || '').toLowerCase()
          expect(msg, `refused: ${msg}`).to.match(/built-in|member/)
        })
    })
  })

  // ── the "Sees" control: whose records, as opposed to which actions ──────────────────────────────

  it('⭐⭐ 14 — a member on ALL sees the shop; a member on OWN sees only their own', () => {
    /*
     * THE CONTROL THAT USED TO GOVERN NOTHING.
     *
     * `permScope` was stored on the set and minted into the token from the day the matrix shipped, and
     * read by NOBODY. An owner set a member to "All records in this shop" and watched nothing change,
     * because the ROLE decided: owner/super/admin saw the whole shop, everyone else saw only rows they
     * had created. Two answers to one question, and the dropdown was the one nobody consulted.
     *
     * It surfaced as a shop that could not trade. A member holding customer.view AND sale.create, set to
     * ALL, opened a sale screen with an EMPTY customer picker — and a sale that leaves a balance requires
     * a named customer, so they could not ring one up at all.
     *
     * ⚠ BOTH DIRECTIONS, because either alone is satisfiable by a build that is simply broken. A test
     * that only checked ALL would pass against a build that showed everybody everything — which is the
     * failure the control exists to prevent.
     */
    let ownRows = null

    // OWN: user.business@ is on Standard, whose scope is OWN, and has created nothing here.
    // ⚠ cacheKeyExtra — otherwise a session cached by an earlier case (or an earlier RUN, where this member was
    // left on a widened set) is reused and this reads the authorities of whatever that session held.
    cy.loginAsTier('user', 'business', undefined, `own-${uniq()}`)
    cy.request({ url: '/getUserCustomer?q=-1', failOnStatusCode: false }).then((r) => {
      ownRows = ((r.body && (r.body.collection || r.body.data || r.body.object)) || []).length
    })

    // The owner's count is the shop's total — the number a widened member should reach.
    cy.loginAsOwner()
    cy.request({ url: '/getUserCustomer?q=-1', failOnStatusCode: false }).then((r) => {
      const all = ((r.body && (r.body.collection || r.body.data || r.body.object)) || []).length
      expect(all, 'the shop has customers to be scoped away from somebody').to.be.greaterThan(0)
      expect(ownRows, 'a member on OWN does NOT see the whole shop').to.be.lessThan(all)
    })
  })

  it('⭐⭐ 15 — switching a set to ALL widens the member, and back to OWN narrows them', () => {
    /*
     * The round trip, which is what makes case 14 a control rather than a coincidence. A build that
     * ignored the set entirely would give the same answer to both halves of case 14 if the member simply
     * had no records; this one CHANGES the set and requires the answer to change with it.
     *
     * ⚠ A NEW SET, never one of the built-ins. Standard and Administrator are the contract that this
     * feature's deploy changed nothing for anybody, and a gate that edited them in place would rewrite
     * that contract for every member already migrated onto them.
     */
    const run = uniq()
    let setId = null
    let userId = null

    cy.loginAsOwner()
    cy.request({
      method: 'POST', url: '/team/permissions/sets', failOnStatusCode: false,
      body: { name: `Scope ${run}`, scope: 'OWN', codes: ['customer.view', 'sale.view'] },
    }).then((r) => {
      setId = ((r.body && (r.body.data || r.body.object)) || {}).id
      expect(setId, 'the set was created').to.exist
    })

    // Find the plain member to move. Their own row in the team list carries the id.
    cy.request({ url: '/team/users', failOnStatusCode: false }).then((r) => {
      const rows = (r.body && (r.body.data || r.body.object)) || []
      const member = rows.find((u) => String(u.email || '').startsWith('user.business@'))
      expect(member, 'the plain member is in this tenant').to.exist
      userId = member.userId || member.id
    })

    cy.then(() => {
      // ── OWN ──────────────────────────────────────────────────────────────────────────────────
      cy.request({ method: 'POST', url: '/team/permissions/assign', failOnStatusCode: false,
        body: { userId, setId } })
        .then((r) => expect(r.body && r.body.success, JSON.stringify(r.body)).to.eq(true))

      /*
       * ⚠ THE cacheKeyExtra IS THE WHOLE CASE. Authorities are minted at login and held in the session, and
       * cy.session caches on email+password+validatePath — so without a key that changes with the grant, this
       * "fresh sign-in" returns the SAME session and the scope just assigned is never in the token. Measured
       * 2026-09-17: both halves answered 157 (the whole shop), so the case compared a number with itself.
       */
      cy.loginAsTier('user', 'business', undefined, `own-${run}`)
      cy.request({ url: '/getUserCustomer?q=-1', failOnStatusCode: false }).then((r) => {
        const narrow = ((r.body && (r.body.collection || r.body.data || r.body.object)) || []).length

        // ── ALL ────────────────────────────────────────────────────────────────────────────────
        cy.loginAsOwner()
        cy.request({ method: 'POST', url: '/team/permissions/sets', failOnStatusCode: false,
          body: { id: setId, name: `Scope ${run}`, scope: 'ALL', codes: ['customer.view', 'sale.view'] } })
          .then((r2) => expect(r2.body && r2.body.success, JSON.stringify(r2.body)).to.eq(true))

        cy.loginAsTier('user', 'business', undefined, `all-${run}`)   // a DIFFERENT key: re-mint with ALL
        cy.request({ url: '/getUserCustomer?q=-1', failOnStatusCode: false }).then((r3) => {
          const wide = ((r3.body && (r3.body.collection || r3.body.data || r3.body.object)) || []).length
          expect(wide, `ALL widened the member: ${narrow} -> ${wide}`).to.be.greaterThan(narrow)
        })
      })
    })

    // Leave no server state behind: the member goes back to Standard, whatever this case did.
    cy.then(() => {
      cy.loginAsOwner()
      perms().then((d) => {
        const std = (d.sets || []).find((x) => x.name === 'Standard')
        cy.request({ method: 'POST', url: '/team/permissions/assign', failOnStatusCode: false,
          body: { userId, setId: std.id } })
      })
    })
  })

  it('⭐ 16 — widening READS grants no ACTION', () => {
    /*
     * The line the control must not cross. `scope.ALL` answers "whose records?" and never "what may they
     * do?" — a member set to ALL still cannot ring up a sale without sale.create.
     *
     * Worth pinning because the implementation carries the scope as an AUTHORITY, and an authority is
     * exactly the shape that could be mistaken for a permission by a later reader. This case fails the
     * moment somebody widens what scope.ALL is allowed to unlock.
     */
    cy.loginAsTier('user', 'business')     // Standard: holds sale.create but NOT team.create
    cy.request({
      method: 'POST', url: '/team/users', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { firstName: 'Scope', lastName: 'Probe', email: `scope${uniq()}@t.com`, role: 'USER' },
    }).then((r) => {
      expect(r.status, 'seeing more of the shop is not permission to do more in it').to.eq(403)
    })
  })

})
