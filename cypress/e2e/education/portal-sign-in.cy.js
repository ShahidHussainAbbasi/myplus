/**
 * Slice 3.1b — portal sign-in, and the deny rule that ships with it.
 * Design: microservices/docs/slices/edu-3.1b-portal-sign-in.md
 *
 * The allowlist logic lives in PortalScopeFilterTest — pure, on every `mvn test`. Asserted HERE is the
 * thing no unit test can reach: **what a REAL signed-in guardian session can and cannot do.**
 *
 *   - a guardian session reads its own children through /portal/**
 *   - **the SAME session gets 404 from /getUserStudent** ← the case this slice exists for
 *   - staff are completely unaffected (the inverse regression)
 *   - revoking closes it
 *
 * Until this slice, education's ~74 read endpoints carried no @PreAuthorize and that was SAFE, because
 * every authenticated education user was staff. Sign-in ended that. Test 3 is the one that must never be
 * waved through: if it fails, a guardian can read every student, mark and fee record in the school.
 *
 * FIXTURES ARE SEEDED, NEVER SKIPPED. The guardian LOGIN is seeded dev-only in auth-service
 * (guardian.education@myplus.com); the Guardian RECORD with the matching email is created here, because
 * guardians live in education-service.
 *
 * Requires education-service + auth-service + monolith up, all rebuilt (common-security changed).
 * Run headed.
 */
const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const rows = (body) => {
  const b = parse(body) || {}
  if (Array.isArray(b.collection)) return b.collection
  const k = Object.keys(b).find((x) => Array.isArray(b[x]))
  return k ? b[k] : []
}
const post = (url, body) =>
  cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

const ok = (r, what) => {
  const b = parse(r.body)
  expect(b.status, `${what}: ${JSON.stringify(b).slice(0, 300)}`).to.be.oneOf(['SUCCESS', 'PARTIAL'])
  return b
}

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveConfig', form: true, body: { key, value }, failOnStatusCode: false })
    .then((r) => expect(JSON.stringify(r.body), `saveConfig ${key}=${value}`).to.match(/SUCCESS/))

const GUARDIAN_EMAIL = 'guardian.education@myplus.com'
const TAG = 'CyPSI' + Date.now()
const fx = {}

/** A raw request as whatever session is active — used to probe what a guardian may reach. */
const probe = (url) => cy.request({ url, failOnStatusCode: false })

describe('Education — portal sign-in (slice 3.1b)', () => {
  before(() => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')

    // The Guardian RECORD must carry the same email as the seeded login — that address is the only link
    // between the two systems (3.1's ChildResolver resolves a guardian by the session's email).
    cy.request('/getUserGuardian').then((r) => {
      fx.guardian = rows(r.body).find((g) => (g.email || '').toLowerCase() === GUARDIAN_EMAIL)
      if (fx.guardian) return
      post('/addGuardian', { name: TAG + ' Portal', email: GUARDIAN_EMAIL, cnic: 'CN' + Date.now(), status: 'ACTIVE' })
        .then((res) => ok(res, 'seed the guardian record'))
    })
    cy.then(() => {
      if (fx.guardian) return
      cy.request('/getUserGuardian').then((r) => {
        fx.guardian = rows(r.body).find((g) => (g.email || '').toLowerCase() === GUARDIAN_EMAIL)
        expect(fx.guardian, 'the guardian record exists and carries the portal email').to.exist
      })
    })

    // A child of theirs, so "my children" is a real set and not an empty one.
    cy.then(() => {
      cy.request('/getUserStudent').then((r) => {
        fx.child = rows(r.body).find((s) => s.guardianId === fx.guardian.id && s.enrollNo)
        if (fx.child) return
        const enrollNo = 'EN' + Date.now()
        post('/addStudent', { name: TAG + ' Child', enrollNo, status: 'ACTIVE', guardianId: fx.guardian.id })
          .then((res) => ok(res, 'seed a child for the portal guardian'))
        fx.child = { enrollNo }
      })
      // Somebody else's child — the one a guardian must never reach.
      cy.request('/getUserStudent').then((r) => {
        fx.otherChild = rows(r.body).find((s) => s.enrollNo && s.guardianId !== fx.guardian.id)
      })
    })

    // Grant portal access. This also provisions/links the sign-in account (3.1b).
    cy.then(() => {
      post('/invitePortalAccess', { guardianId: fx.guardian.id }).then((r) => {
        const b = ok(r, 'invite the portal guardian')
        fx.inviteAccount = b.object && b.object.account
      })
    })
    cy.request('/getPortalAccess').then((r) => {
      fx.access = rows(r.body).find((a) => a.guardianId === fx.guardian.id)
      expect(fx.access, 'the access row exists').to.exist
    })
  })

  after(() => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')
    if (fx.access) post('/invitePortalAccess', { guardianId: fx.guardian.id })   // leave it usable for re-runs
  })

  // ── provisioning ────────────────────────────────────────────────────────────────────────────────

  it('inviting provisions the sign-in account, and says so', () => {
    cy.loginAsEduOwner()
    // The response distinguishes "invited" from "invited but they cannot log in" — a school must not be
    // left assuming a guardian can sign in when the account was never created.
    expect(fx.inviteAccount, 'the invite reports the account outcome').to.eq('OK')
  })

  // ── THE DENY RULE ───────────────────────────────────────────────────────────────────────────────

  it('a signed-in guardian reads their OWN children through the portal', () => {
    cy.loginAsPortalGuardian()
    probe('/portal/children').then((r) => {
      const b = parse(r.body)
      expect(b.status, `portal children: ${JSON.stringify(b).slice(0, 200)}`).to.eq('SUCCESS')
      const mine = (b.object || b.collection || [])
      expect(mine.length, 'the guardian sees at least one child').to.be.greaterThan(0)
    })
  })

  it('THE CASE: the same session gets 404 from a staff read', () => {
    // Without PortalScopeFilter this returns every student in the school. Education's reads carry no
    // @PreAuthorize — the filter is the only control, so this assertion is the whole slice.
    //
    // Each URL is probed as STAFF first. A 404 only proves the filter if the route exists; without this
    // control, renaming an endpoint would turn this test into a green that proves nothing — the same
    // hollow-green shape as 2.1's skipped clash test and 2.4's empty class.
    const STAFF_READS = ['/getUserStudent', '/getUserGuardian', '/getMarksSheet', '/getUserFc']

    cy.loginAsEduOwner()
    STAFF_READS.forEach((url) => {
      probe(url).then((r) => {
        expect(r.status, `${url} exists and answers staff — else the 404 below proves nothing`).to.eq(200)
      })
    })

    cy.loginAsPortalGuardian()
    STAFF_READS.forEach((url) => {
      probe(url).then((r) => {
        expect(r.status, `${url} must be refused for a portal session`).to.eq(404)
      })
    })
  })

  it('the refusal is 404, never 403 — a guardian learns nothing about what exists', () => {
    cy.loginAsPortalGuardian()
    probe('/getUserStudent').then((r) => {
      expect(r.status).to.eq(404)
      expect(r.status, 'a 403 would confirm the endpoint is real and merely forbidden').to.not.eq(403)
    })
  })

  it('path traversal does not walk past the allowlist', () => {
    cy.loginAsPortalGuardian()
    probe('/portal/../getUserStudent').then((r) => {
      expect(r.status, 'a staff read wearing a portal prefix is still refused').to.be.oneOf([404, 400])
    })
  })

  it('a guardian cannot write, either', () => {
    cy.loginAsPortalGuardian()
    post('/addStudent', { name: 'CY should never exist', enrollNo: 'EN' + Date.now(), status: 'ACTIVE' })
      .then((r) => {
        expect(r.status, 'writes are outside the allowlist like everything else').to.eq(404)
      })
  })

  // ── the inverse regression: staff must be untouched ─────────────────────────────────────────────

  it('staff are completely unaffected — the filter must not narrow the people who already had access', () => {
    // A shared filter that wrongly matched would lock every module out, which is worse than the hole it
    // closes. This is the case that would catch it.
    cy.loginAsEduOwner()
    probe('/getUserStudent').then((r) => {
      expect(r.status, 'staff still read the roster').to.eq(200)
      expect(parse(r.body).status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
    probe('/getDashboardData').then((r) => {
      expect(r.status, 'and the dashboard still loads').to.eq(200)
    })
  })

  // ── withdrawal and the kill switch ──────────────────────────────────────────────────────────────

  it('turning the portal OFF closes it even for a valid signed-in account', () => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'false')
    cy.loginAsPortalGuardian()
    probe('/portal/children').then((r) => {
      // 3.1's kill switch still wins over a working login — the two controls are independent.
      expect(parse(r.body).status).to.eq('NOT_FOUND')
    })
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')
  })

  it('revoking access stops the portal reads', () => {
    cy.loginAsEduOwner()
    post('/revokePortalAccess', { id: fx.access.id }).then((r) => ok(r, 'revoke'))
    cy.loginAsPortalGuardian()
    probe('/portal/children').then((r) => {
      expect(parse(r.body).status, 'a revoked guardian reads nothing').to.eq('NOT_FOUND')
    })
    // Restore for re-runs.
    cy.loginAsEduOwner()
    post('/invitePortalAccess', { guardianId: fx.guardian.id })
  })
})
