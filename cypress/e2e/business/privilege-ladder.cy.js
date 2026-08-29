/**
 * The privilege ladder, walked end to end for one tenant.
 *
 * Every module seeds four accounts, and `admin.` / `user.` are MEMBERS OF THE OWNER'S ORG — deliberately. If
 * they sat in separate organizations a refusal would prove org scoping worked, not that the privilege gate
 * did, and those are different guarantees with different bugs.
 *
 * This runs on the MOBILE SHOP tenant. Not because the ladder is mobile-specific, but because the runbook rule
 * is to use the tenant a feature belongs to rather than whichever account is convenient — and the ladder is
 * exactly where "convenient" hides things, since owner sees everything and is therefore the account least
 * likely to reveal a broken gate.
 *
 * <h3>Two assertions at every level, never one</h3>
 *   1. **Data populates** — the screens load for this role.
 *   2. **Privileges differ** — the owner-only and admin-only sections appear or do not.
 *
 * Checking only (2) would pass against a build where every role is refused everything: nothing renders, so
 * nothing wrongly renders. Checking only (1) would pass against a build where a cashier can reach the
 * Finance reports.
 *
 * See `microservices/docs/GATE-RUNBOOK.md`.
 */

const PW = 'Demo@2025!'

/**
 * What each level should see. Both sections are real `sec:authorize` gates in businessDashboard.html:
 *   #FinanceDiv  hasAuthority('ROLE_OWNER')                        — org-wide GL statements
 *   #ConfigDiv   hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')   — the tenant's Configuration
 *
 * Three levels, and the two sections together tell them apart: owner has both, admin has one, a user has
 * neither. A single gated section could not distinguish admin from owner at all.
 */
const LADDER = [
  { role: 'owner', email: 'owner.mobile@myplus.com', finance: true,  config: true },
  { role: 'admin', email: 'admin.mobile@myplus.com', finance: false, config: true },
  { role: 'user',  email: 'user.mobile@myplus.com',  finance: false, config: false },
]

describe('Privilege ladder — owner / admin / user on one tenant', () => {
  LADDER.forEach((level) => {
    describe(`${level.role} (${level.email})`, () => {
      beforeEach(() => {
        // testIsolation clears the session, so the login belongs here rather than in before().
        cy.loginAs(level.email, PW, '/getBusinessDashboardStats')
      })

      it('the dashboard populates', () => {
        /*
         * Asserts the read SUCCEEDS and is well formed — NOT that rows exist.
         *
         * Visibility is hierarchical here (USER sees own, ADMIN own + managed, OWNER all org), so a plain
         * user legitimately has an empty list until they have created something. Asserting a row count would
         * fail for a correct system, and the failure would look like a permissions bug — which is worse than
         * no assertion, because somebody would "fix" the permissions.
         *
         * What must hold at every level is that the screen LOADS: an endpoint answering ERROR renders exactly
         * like an endpoint answering an empty list, and only one of those is acceptable.
         */
        cy.request('/getBusinessDashboardStats').then((r) => {
          expect(r.status, 'the dashboard read is not refused for this role').to.eq(200)
          expect(String(r.body && r.body.status), `stats for ${level.role}: ${JSON.stringify(r.body)}`)
            .to.not.eq('ERROR')
          expect(r.body.object, 'and it carries the counters the tiles read').to.be.an('object')
        })

        cy.request('/getUserProduct').then((r) => {
          expect(r.status, 'the product list loads for this role').to.eq(200)
          expect(String(r.body && r.body.status)).to.not.eq('ERROR')
        })
      })

      it(`sees the right sections — finance:${level.finance} config:${level.config}`, () => {
        cy.visit('/businessDashboard')
        // The dashboard renders every section it is allowed to and omits the rest server-side, so presence in
        // the DOM IS the privilege decision — sec:authorize removes the element, it does not merely hide it.
        cy.get('body').then(($b) => {
          const has = (sel) => $b.find(sel).length > 0
          expect(has('#FinanceDiv'), `${level.role}: Finance reports (owner-only)`).to.eq(level.finance)
          expect(has('#ConfigDiv'), `${level.role}: Configuration (owner or admin)`).to.eq(level.config)
          // The control: a section every role has. Without it, a build that rendered NOTHING would satisfy
          // both expectations above for admin and user, and the ladder would be measuring a blank page.
          expect(has('#sellDiv'), `${level.role}: the sale screen is available to everyone`).to.eq(true)
        })
      })
    })
  })

  it('⭐ the three levels are genuinely different, not three names for one role', () => {
    /*
     * The summary assertion, and the one that fails loudest if privileges stop being applied at all.
     *
     * If sec:authorize were bypassed — a misconfigured filter, an authority set rebuilt wrongly from the JWT —
     * every level would render every section and each case above would still pass its own `finance:true`
     * expectation for the owner. Comparing the levels to EACH OTHER is what catches that.
     */
    const seen = []
    cy.wrap(LADDER).each((level) => {
      cy.loginAs(level.email, PW, '/getBusinessDashboardStats')
      cy.visit('/businessDashboard')
      cy.get('body').then(($b) => {
        seen.push({
          role: level.role,
          finance: $b.find('#FinanceDiv').length > 0,
          config: $b.find('#ConfigDiv').length > 0,
        })
      })
    }).then(() => {
      const shape = seen.map((s) => `${s.role}:${s.finance ? 'F' : '-'}${s.config ? 'C' : '-'}`).join(' ')
      expect(shape, 'owner > admin > user, each strictly narrower than the last')
        .to.eq('owner:FC admin:-C user:--')
    })
  })
})
