/**
 * B2B Phase 0.5 — ONE login reaches every module.
 *
 * Routing used to key off `User.userType`, a single string on the *person*, so a customer running a shop
 * and a school needed two accounts. It now follows the ACTIVE ORGANISATION's type, with `userType` as the
 * fallback for every tenant whose `Organization.type` predates the column.
 *
 * This spec closes the gap `org-switcher.cy.js` documented in its own scope note: the seeded accounts each
 * belonged to ONE org, so the two-org hop was never exercised. `multi.module@myplus.com` is seeded into the
 * commerce org AND the education org for exactly this — and its `userType` is deliberately BUSINESS, so
 * landing on the education dashboard proves the ORG won, not the person's type.
 *
 * Design: microservices/docs/slices/b2b-P05-org-type-routing.md
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/org-type-routing.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const PW = 'Demo@2025!'
const MULTI = 'multi.module@myplus.com'

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

const orgs = () =>
  cy.request('/getMyOrganizations').then((r) => {
    expect(r.body.status, `getMyOrganizations: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    return list(r.body)
  })

const switchTo = (orgId) =>
  cy.request({ method: 'POST', url: '/switchOrganization', form: true,
    body: { organizationId: orgId }, failOnStatusCode: false })
    .then((r) => {
      expect(r.body.status, `switchOrganization ${orgId}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
      return r.body
    })

/** Where /dashboard sends this session — read the 302 rather than following it, so the answer is exact. */
const dashboardRedirect = () =>
  cy.request({ url: '/dashboard', followRedirect: false, failOnStatusCode: false })
    .then((r) => {
      expect(r.status, `/dashboard should redirect, got ${r.status}`).to.be.oneOf([301, 302])
      return String(r.headers.location || '')
    })

const orgOfType = (all, type) => all.find((o) => String(o.type || '').toUpperCase() === type)

describe('B2B P0.5 — one login reaches every module', () => {

  describe('the multi-module user', () => {
    // No cy.session here: switching org mutates server-side session state, and a cached session would
    // carry one test's active org into the next. Each test logs in fresh and sets the org it needs.
    beforeEach(() => {
      cy.visit('/login')
      cy.get('input[name="username"]').type(MULTI)
      cy.get('input[name="password"]').type(PW)
      cy.get('#loginSubmit').click()
      cy.url().should('not.include', '/login')
    })

    it('the fixture is real: two orgs, two DIFFERENT modules', () => {
      // Assert the fixture before trusting any test built on it — a silently missing seed would otherwise
      // make everything below pass vacuously.
      orgs().then((all) => {
        expect(all.length, 'member of at least two organizations').to.be.greaterThan(1)
        all.forEach((o) => {
          expect(o, `org ${o.name} carries its type (R2)`).to.have.property('type')
        })
        const types = new Set(all.map((o) => String(o.type || '').toUpperCase()))
        expect(types.has('BUSINESS'), 'a commerce org').to.eq(true)
        expect(types.has('EDUCATION'), 'a school').to.eq(true)
      })
    })

    it('switching to the school lands on the EDUCATION dashboard', () => {
      orgs().then((all) => {
        const school = orgOfType(all, 'EDUCATION')
        expect(school, 'education org in the list').to.exist

        switchTo(school.id).then((res) => {
          expect(String(res.activeOrgType).toUpperCase(), 'switch reports the new module').to.eq('EDUCATION')
        })
        dashboardRedirect().then((loc) => {
          // The user's own userType is BUSINESS — if that were still deciding, this would be the
          // commerce dashboard. This assertion IS the slice.
          expect(loc, 'the ORG decides, not the person').to.contain('/educationDashboard')
        })
      })
    })

    it('switching back to the shop lands on the COMMERCE dashboard', () => {
      orgs().then((all) => {
        const school = orgOfType(all, 'EDUCATION')
        const shop = orgOfType(all, 'BUSINESS')

        switchTo(school.id)
        dashboardRedirect().then((loc) => expect(loc).to.contain('/educationDashboard'))

        switchTo(shop.id)
        dashboardRedirect().then((loc) => expect(loc).to.contain('/businessDashboard'))
      })
    })

    it('the active org survives the round trip', () => {
      orgs().then((all) => {
        const school = orgOfType(all, 'EDUCATION')
        switchTo(school.id)
        orgs().then((again) => {
          const active = again.filter((o) => o.active)
          expect(active.length, 'exactly one active org').to.eq(1)
          expect(Number(active[0].id), 'still in the school we switched to').to.eq(Number(school.id))
        })
      })
    })

    it('the switcher labels each org with its module', () => {
      // Two orgs both called "Springfield" are indistinguishable without this, and picking the wrong one
      // silently drops the user into the wrong module.
      cy.visit('/businessDashboard')
      cy.get('#orgSwitcher option', { timeout: 10000 }).should('have.length.greaterThan', 1)
      cy.get('#orgSwitcher option').then(($opts) => {
        const labelled = [...$opts].filter((o) => o.textContent.indexOf('—') !== -1)
        expect(labelled.length, 'options carry "Name — Module"').to.be.greaterThan(0)
      })
    })

    it('cannot switch into an org it does not belong to', () => {
      // Existing server-side protection (AuthService.switchOrganization → isMember). Asserted because
      // this slice moves code next to it, and because routing now depends on the active org being real.
      orgs().then((before) => {
        const activeBefore = before.find((o) => o.active)

        cy.request({ method: 'POST', url: '/switchOrganization', form: true,
          body: { organizationId: 99999999 }, failOnStatusCode: false })
          .then((r) => {
            expect(r.body.status, `refused: ${JSON.stringify(r.body)}`).to.not.eq('SUCCESS')
          })

        orgs().then((after) => {
          const activeAfter = after.find((o) => o.active)
          expect(Number(activeAfter.id), 'active org unchanged after a refused switch')
            .to.eq(Number(activeBefore.id))
        })
      })
    })
  })

  describe('single-org users are unaffected (the regression guard)', () => {

    it('a commerce owner still lands on the commerce dashboard', () => {
      cy.loginAsOwner()
      dashboardRedirect().then((loc) => expect(loc).to.contain('/businessDashboard'))
    })

    it('an education owner still lands on the education dashboard', () => {
      cy.loginAs('owner.education@myplus.com', PW, '/educationDashboard')
      dashboardRedirect().then((loc) => expect(loc).to.contain('/educationDashboard'))
    })

    it('/dashboard and the post-login landing agree', () => {
      // R5: these were two hand-maintained copies of the same map and had already drifted — an APPOINTMENT
      // user landed on their dashboard at login but was bounced to "/" through /dashboard. One router now.
      cy.loginAsOwner()
      cy.visit('/dashboard')
      cy.url().should('include', '/businessDashboard')
      dashboardRedirect().then((loc) => expect(loc).to.contain('/businessDashboard'))
    })
  })
})
