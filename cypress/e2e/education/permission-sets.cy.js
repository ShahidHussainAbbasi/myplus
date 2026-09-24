/**
 * EDU-PERM-1 — which words a token is minted with, and whose.
 *
 * <h3>Why this is asserted on the TOKEN and not on a screen</h3>
 * The codes ride the `privileges` claim, which `sec:authorize` and `@PreAuthorize` both resolve against.
 * A screen can only show what a menu happens to render today; the claim is what every guard in the
 * platform will read tomorrow. So this reads the claim straight from the gateway.
 *
 * <h3>⚠ The defect this exists to stop coming back a THIRD time</h3>
 * V12 placed permission sets by "everyone not already placed" and a ROLE_GUARDIAN — a parent signing in
 * to see their child's attendance — ended up holding `sale.create`. V14 cleaned it up and wrote the
 * lesson down.
 *
 * V16 then placed education sets by ORGANISATION TYPE, and a school's organisation contains more than
 * its staff: `guardian.education@` (a parent) and `student.education@` (a child) landed on **Teacher**,
 * which holds `marks.enter`, `attendance.mark` and `student.view`. V17 is the fix-forward.
 *
 * The lesson is narrower than "remember V12": a set is placed by WHAT SOMEBODY DOES, never by which
 * organisation row they appear in. Case 1 is that rule, asserted.
 *
 * Run:  npx cypress run --spec cypress/e2e/education/permission-sets.cy.js
 */
const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'

/** The privileges claim exactly as auth-service mints it. */
const codesFor = (email) =>
  cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email, password: PW }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(200)
    // `scope.ALL` is the ROW-scope claim, not a permission — see AuthService's dataScope block. It
    // contains a dot, so it would otherwise read as an action code and make every count wrong.
    return (r.body.data.privileges || []).filter((p) => p !== 'scope.ALL')
  })

const areas = (codes) => new Set(codes.filter((c) => c.includes('.')).map((c) => c.split('.')[0]))

describe('EDU-PERM-1 — a school speaks its own permission vocabulary', () => {
  const EDU_AREAS = ['student', 'guardian', 'staff', 'attendance', 'class', 'subject', 'timetable',
    'exam', 'marks', 'reportcard', 'homework', 'behaviour', 'communication', 'fee', 'school', 'transport']
  const BIZ_AREAS = ['sale', 'purchase', 'product', 'customer', 'supplier', 'till', 'stock', 'opening', 'finance']

  it('⭐⭐ 1 — a PARENT holds no staff permission at all (V12, and then V16, both failed here)', () => {
    codesFor('guardian.education@myplus.com').then((codes) => {
      /*
       * ACTION CODES only — the lowercase `area.action` ones. A guardian rightly keeps their ROLE
       * privileges (LOGIN_PRIVILEGE, CHANGE_PASSWORD_PRIVILEGE): V14's whole fail-back is that a member
       * of a module with no catalogue is minted "exactly the role privileges it minted before PERM-1
       * existed". Asserting an empty claim would demand they cannot sign in.
       */
      const actions = codes.filter((c) => c.includes('.'))
      expect(actions, `a guardian was minted staff codes: ${actions.join(' ')}`).to.have.length(0)
      expect([...areas(codes)], 'a parent is not staff and holds no area of the school').to.deep.eq([])
    })
  })

  it('⭐⭐ 2 — a TEACHER may enter marks and may NOT touch money', () => {
    codesFor('teacher.a@myplus.com').then((codes) => {
      expect(codes, 'marks.enter').to.include('marks.enter')
      expect(codes, 'and the view it is useless without').to.include('marks.view')
      expect(codes, 'attendance').to.include('attendance.mark')

      // The separation the whole slice exists for.
      expect(codes, 'a teacher cannot collect a fee').to.not.include('fee.collect')
      expect(codes, 'nor change what a family is charged').to.not.include('fee.structure')
      expect(codes, 'nor release report cards to parents').to.not.include('reportcard.publish')
      expect(codes, 'nor change the school settings').to.not.include('settings.edit')
    })
  })

  it('⭐⭐ 3 — an education OWNER is minted the school catalogue and NO shop codes', () => {
    codesFor('owner.education@myplus.com').then((codes) => {
      const held = areas(codes)
      EDU_AREAS.forEach((a) => expect(held.has(a), `owner holds the ${a} area`).to.eq(true))
      BIZ_AREAS.forEach((a) => expect(held.has(a), `owner must NOT hold the shop area ${a}`).to.eq(false))
      expect(codes, 'the shop code V12 leaked').to.not.include('sale.create')
    })
  })

  it('⭐ 4 — and the mirror: a shop owner is minted no school codes', () => {
    // Asserted in BOTH directions deliberately. A filter applied on one side only looks correct from
    // whichever side you happen to check, which is how the first version of this shipped.
    codesFor('owner.business@myplus.com').then((codes) => {
      const held = areas(codes)
      expect(held.has('sale'), 'a shop owner still trades').to.eq(true)
      expect(codes, 'and cannot edit a child record').to.not.include('student.edit')
      expect(codes, 'nor enter marks').to.not.include('marks.enter')
    })
  })

  it('⭐ 5 — the COMMON codes reach both, because a report is a report', () => {
    // report.*, settings.* and team.* exist once for the whole platform: `permission.code` is the
    // primary key. Duplicating them per module would give one concept two authority strings.
    const common = ['report.view', 'settings.view', 'team.view']
    codesFor('owner.education@myplus.com').then((edu) => {
      common.forEach((c) => expect(edu, `school holds ${c}`).to.include(c))
      codesFor('owner.business@myplus.com').then((biz) => {
        common.forEach((c) => expect(biz, `shop holds ${c}`).to.include(c))
      })
    })
  })

  // ── slice 2: the owner can now SEE and USE the matrix on their own screen ──────────────────────
  describe('the matrix is on the school screen', () => {
    const openTeam = (who) => {
      cy.loginAs(who, PW, '/educationDashboard')
      cy.visit('/educationDashboard')
      cy.waitForAppReady()
      cy.window().then((w) => w.showTeam())
      cy.get('#TeamDiv', { timeout: 15000 }).should('be.visible')
    }

    it('⭐⭐ 7 — the owner gets the matrix, drawn from the SCHOOL catalogue', () => {
      openTeam('owner.education@myplus.com')
      cy.get('#permWrap', { timeout: 15000 }).should('be.visible')

      // Rows are one per area, labelled. The failure this catches is a matrix drawn from the shop
      // catalogue, which would read Sale / Purchase / Till on a school screen.
      cy.get('#permMatrix tbody tr', { timeout: 15000 }).should('have.length.greaterThan', 8)
      cy.get('#permMatrix tbody').should('contain.text', 'Students')
      cy.get('#permMatrix tbody').should('contain.text', 'Marks')
      cy.get('#permMatrix tbody').should('contain.text', 'Fees')
      cy.get('#permMatrix tbody').should('not.contain.text', 'Purchase')
      cy.get('#permMatrix tbody').should('not.contain.text', 'Till & shifts')

      // ⚠ and NOT the raw slug. AREA_LABEL had no education entries, so every row would have read
      // "reportcard" and "behaviour" — the screen looking unfinished rather than broken.
      cy.get('#permMatrix tbody').should('not.contain.text', 'reportcard')
    })

    it('⭐⭐ 8 — the set picker offers the SCHOOL sets and none of the shop ones', () => {
      openTeam('owner.education@myplus.com')
      cy.get('#permSetPicker option', { timeout: 15000 }).should('have.length.greaterThan', 3)
      cy.get('#permSetPicker').then(($sel) => {
        const names = $sel.find('option').toArray().map((o) => o.textContent.trim())
        ;['Teacher', 'Class Teacher', 'Accountant', 'Front Office', 'Principal']
          .forEach((n) => expect(names.join(' | '), `offers ${n}`).to.contain(n))
        // Built-ins carry organization_id IS NULL, so without the module filter EVERY built-in shows.
        ;['Cashier', 'Storekeeper'].forEach((n) =>
          expect(names.join(' | '), `must not offer the shop set ${n}`).to.not.contain(n))
      })
    })

    it('⭐ 9 — the Sees control is fixed to ALL and hidden, not offered and ignored', () => {
      openTeam('owner.education@myplus.com')
      cy.get('#permWrap').should('have.attr', 'data-scope-fixed', 'ALL')
      // Present in the DOM because savePermissionSet() reads it — but never shown, because the token
      // mints EDUCATION as ALL whatever it says.
      cy.get('#permScope').should('exist').and('not.be.visible')
      cy.get('#permScope').should('have.value', 'ALL')
      cy.get('#permPreviewScope').should('not.contain.text', 'only the records they create')
    })

    it('⭐⭐ 10b — the owner can actually SAVE a set, and it comes back with its codes', () => {
      /*
       * ⚠ THE CASE MY FIRST GATE DID NOT HAVE, and the business suite had to find for me.
       *
       * Adding a NOT NULL `module` field to PermissionSet turned every create into a failed insert:
       * Hibernate writes a mapped field as NULL rather than omitting it, so the column DEFAULT never
       * applied. On screen that reads as "the set saved but has no permissions in it" — not as an error.
       *
       * Cases 7-10 all READ the matrix, so every one of them passed throughout. A gate that only reads
       * cannot see a broken write.
       */
      const name = `EduSet ${Date.now()}`
      cy.loginAs('owner.education@myplus.com', PW, '/educationDashboard')
      cy.request({
        method: 'POST', url: '/team/permissions/sets', failOnStatusCode: false,
        body: { name, scope: 'ALL', codes: ['marks.enter'] },
      }).then((r) => {
        const saved = (r.body && (r.body.data || r.body.object)) || {}
        const codes = saved.codes || []
        expect(codes, `the set saved with no codes: ${JSON.stringify(r.body).slice(0, 200)}`)
          .to.include('marks.enter')
        // and the closure the server owes it — marks.enter is useless without these
        expect(codes, 'the marks screen it is typed on').to.include('marks.view')
        expect(codes, 'the exam it belongs to').to.include('exam.view')
        expect(codes, 'the student it is about').to.include('student.view')
        // it must never pick up a code from the other module's vocabulary
        expect(codes.filter((c) => c.startsWith('sale.') || c.startsWith('purchase.')),
          'a school set holding shop codes').to.have.length(0)
      })
    })

    it('⭐⭐ 10 — an ADMIN does not get the matrix: only an owner grants', () => {
      // Guarded in the MARKUP, so for an admin the block is absent from the DOM entirely. An admin who
      // could edit sets while holding team.edit could grant themselves fees.
      openTeam('admin.education@myplus.com')
      cy.get('#permWrap').should('not.exist')
    })
  })

  it('⭐⭐ 6b — NOBODY sits on a set from another module, whatever road they arrived by', () => {
    /*
     * ⚠ THIS DEFECT HAS NOW ARRIVED THREE WAYS. V12 placed "everyone not already placed" (a guardian got
     * sale.create). V16 placed by ORGANISATION TYPE (a parent and a pupil got marks.enter). And
     * createOrgUser placed by a hardcoded set NAME — "Administrator"/"Standard", both BUSINESS built-ins
     * — so every teacher created in a school landed on the SHOP's Standard set and was minted
     * sale.create, purchase.create, till.create and twenty more. Five accounts were in that state.
     *
     * Asserted on the TOKEN of a real school member rather than on a table, because the token is what
     * every guard in the platform reads. `assign()` now refuses a foreign set outright, so this is the
     * property that catches a fourth road before anyone has to think of it.
     *
     * ⚠ PHARMA reads the BUSINESS catalogue on purpose (moduleOf maps it there, V15 placed it there), so
     * the test is "no SHOP codes in a SCHOOL token", never "org type equals set module".
     */
    const SHOP = ['sale.', 'purchase.', 'till.', 'supplier.', 'stock.', 'customer.', 'product.', 'opening.', 'finance.']
    ;['teacher.a@myplus.com', 'user.education@myplus.com', 'admin.education@myplus.com',
      'owner.education@myplus.com'].forEach((who) => {
      codesFor(who).then((codes) => {
        const leaked = codes.filter((c) => SHOP.some((p) => c.startsWith(p)))
        expect(leaked, `${who} holds shop codes: ${leaked.join(' ')}`).to.have.length(0)
      })
    })
  })

  it('⭐ 6 — a module with no catalogue is minted no codes, exactly as before PERM-1', () => {
    // V14: "Until another module has a catalog of its own, its members hold NO set, and AuthService
    // then mints exactly the role privileges it minted before PERM-1 existed." Welfare has none.
    codesFor('owner.welfare@myplus.com').then((codes) => {
      expect(codes.filter((c) => c.includes('.')),
        `a welfare tenant was minted action codes: ${codes.join(' ')}`).to.have.length(0)
    })
  })
})
