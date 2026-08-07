/**
 * Slice 3.1b — portal sign-in, and the deny rule that ships with it.
 * Design: microservices/docs/slices/edu-3.1b-portal-sign-in.md
 *
 * The allowlist logic lives in PortalScopeFilterTest — pure, on every `mvn test`. Asserted HERE is the
 * thing no unit test can reach: **what a REAL signed-in guardian session can and cannot do.**
 *
 *   - a guardian session reads its own children through /portal/**
 *   - **the SAME session gets 404 from /getUserStudent** ← the case this slice exists for
 *   - and inside the portal it still cannot reach another family's child (the filter is not the only control)
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
      // Somebody else's child — the one a guardian must never reach. SEEDED, never hoped for: the last
      // case asserts against it, and "assert only if one happens to exist" is how a gate goes hollow.
      cy.request('/getUserStudent').then((r) => {
        fx.otherChild = rows(r.body).find((s) => s.enrollNo && s.guardianId && s.guardianId !== fx.guardian.id)
        if (fx.otherChild) return
        post('/addGuardian', { name: TAG + ' Other', cnic: 'CO' + Date.now(), status: 'ACTIVE' })
          .then((res) => ok(res, 'seed a second guardian'))
        cy.request('/getUserGuardian').then((g) => {
          const other = rows(g.body).find((x) => x.name === TAG + ' Other')
          expect(other, 'the second guardian was seeded').to.exist
          const enrollNo = 'EO' + Date.now()
          post('/addStudent', { name: TAG + ' OtherChild', enrollNo, status: 'ACTIVE', guardianId: other.id })
            .then((res) => ok(res, "seed another guardian's child"))
          fx.otherChild = { enrollNo }
        })
      })
    })

    // Grant portal access. This also provisions/links the sign-in account (3.1b).
    //
    // REVOKE FIRST, deliberately, so the invite takes the FULL path every run. `ChildResolver` flips the
    // access row INVITED → ACTIVE on the guardian's first portal read, and `invitePortalAccess` EARLY-RETURNS
    // on an ACTIVE row ("already has portal access") with **no `object`** — so a run that aborted after case
    // 5 leaves state that makes case 1 report `undefined` and read as a product defect. It is fixture state,
    // not a defect, and the fix belongs here rather than in a lenient assertion.
    //
    // Safe to do: revoke disables the sign-in, and the invite that follows re-enables it
    // (`AuthService.createOrLinkPortalUser` sets `enabled=true` on the link branch), so the account the
    // cases below log in with is left exactly as it was found.
    cy.then(() => {
      cy.request('/getPortalAccess').then((r) => {
        const existing = rows(r.body).find((a) => a.guardianId === fx.guardian.id)
        if (existing) post('/revokePortalAccess', { id: existing.id })
      })
    })
    cy.then(() => {
      post('/invitePortalAccess', { guardianId: fx.guardian.id }).then((r) => {
        const b = ok(r, 'invite the portal guardian')
        fx.inviteAccount = b.object && b.object.account
      })
    })
    cy.then(() => {
      cy.request('/getPortalAccess').then((r) => {
        fx.access = rows(r.body).find((a) => a.guardianId === fx.guardian.id)
        expect(fx.access, 'the access row exists').to.exist
      })
    })
  })

  after(() => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')
    // Guarded on the GUARDIAN, not the access row: if `before` failed before resolving one, reading
    // `fx.guardian.id` throws a TypeError in the after-hook that buries the real failure underneath it.
    if (fx.guardian) post('/invitePortalAccess', { guardianId: fx.guardian.id })  // leave it usable for re-runs
  })

  // ── provisioning: INVITATION IS THE ONLY PATH (decision D-6 / slice §11) ────────────────────────

  it('inviting reports the account outcome — the school is never left assuming', () => {
    cy.loginAsEduOwner()
    // THE CONTRACT this asserts (§8): an access row whose sign-in was never created is a guardian who
    // cannot log in, and the school must be told NOW rather than when the family calls. The outcome is
    // therefore always reported, as OK or FAILED — never omitted, never silently assumed.
    expect(fx.inviteAccount,
      'the invite always reports an account outcome. `undefined` here means the invite took the ' +
      '"already has portal access" early return, which carries no object — the fixture revokes first ' +
      'precisely so that cannot happen, so check that revoke succeeded rather than the invite contract')
      .to.be.oneOf(['OK', 'FAILED'])

    if (fx.inviteAccount === 'FAILED') {
      // Known ENVIRONMENT limitation, not a product defect, and it is documented: INTERNAL_SECRET
      // defaults to empty locally, so PortalAccountController fails closed (slice §10, security
      // findings F18 → F2). Provisioning works where the secret is set — production requires it.
      // The slice's own requirement is that the failure is SURFACED, which is what is asserted above.
      cy.log('account=FAILED — expected locally until F18/F2; the contract (surfacing it) still holds')
    }
  })

  it('a guardian with NO email cannot be invited — invitation needs an address', () => {
    // The BOUNDARY of decision D-6 (invitation-only), asserted so the limitation is visible in the gate
    // rather than discovered by a school. `invitePortalAccess` takes the address from the guardian
    // RECORD, never the request (3.1 D3), so a record without one cannot be invited at all.
    //
    // This is also the TRIGGER for option C (school-issued join codes): if schools do not hold guardian
    // emails at scale, invitation does not scale and codes become worth building. §11.
    cy.loginAsEduOwner()
    // A STABLE name, so a re-run reuses this row instead of leaving a fresh junk guardian behind every
    // time the gate is run — the fixture is deterministic either way, and the tenant stays clean.
    const NAME = 'CyNoMail Portal'
    cy.request('/getUserGuardian').then((r) => {
      if (rows(r.body).find((x) => x.name === NAME)) return
      post('/addGuardian', { name: NAME, cnic: 'CNNOMAIL', status: 'ACTIVE' })
        .then((res) => ok(res, 'seed a guardian with no email'))
    })
    cy.request('/getUserGuardian').then((r) => {
      const g = rows(r.body).find((x) => x.name === NAME)
      expect(g, 'the email-less guardian was seeded').to.exist
      // Eligibility, not existence: if this row ever acquires an address the case below would pass for
      // the wrong reason — it would be asserting a refusal that never happened.
      expect(g.email || '', 'and it genuinely has no address').to.eq('')
      post('/invitePortalAccess', { guardianId: g.id }).then((res) => {
        const b = parse(res.body)
        expect(b.status, 'inviting without an address is refused, not half-done').to.not.eq('SUCCESS')
        expect(b.message, 'and the refusal names the fix').to.match(/email/i)
      })
    })
  })

  it('re-inviting is a RESEND, not an error — that is what the button means', () => {
    // Invitation-only makes "resend" the school's normal recovery when a family never received or acted
    // on the first message. It must therefore be idempotent rather than refusing as a duplicate.
    cy.loginAsEduOwner()
    post('/invitePortalAccess', { guardianId: fx.guardian.id }).then((r) => {
      const b = parse(r.body)
      expect(b.status, 'a second invite is accepted').to.be.oneOf(['SUCCESS', 'PARTIAL'])
      // A STATUS ALONE CANNOT TELL A RESEND FROM A NO-OP: the "already has portal access" early return is
      // also SUCCESS, and it re-sends nothing. The object is the discriminator — only the full path builds
      // it — so assert that, not the status. (Case 5's lesson, applied to a green rather than a red.)
      expect(b.object, 'the resend took the full invite path, it did not short-circuit').to.exist
    })
    cy.request('/getPortalAccess').then((r) => {
      const all = rows(r.body).filter((a) => a.guardianId === fx.guardian.id)
      expect(all.length, 'and it does NOT create a second access row').to.eq(1)
    })
  })

  // ── THE DENY RULE ───────────────────────────────────────────────────────────────────────────────

  it('the guardian session is CONFINED — the precondition every case below depends on', () => {
    // THIS TEST EXISTS BECAUSE ITS ABSENCE COST SIX GATE RUNS.
    //
    // The portal resolves a guardian by EMAIL (3.1 D1), but the deny rule keys on the ROLE in the JWT.
    // Those can disagree: a cy.session cached before the seeded role changed replays an old token, so
    // /portal/** works while PortalScopeFilter sees a role it does not confine and waves staff reads
    // through. Every failure then looks like a broken filter, and no amount of rebuilding fixes it.
    //
    // So: prove the session is confined FIRST, with a message that names the real cause. If this fails,
    // nothing below is meaningful — do not debug the server until it passes.
    cy.loginAsPortalGuardian()
    probe('/getUserStudent').then((r) => {
      expect(r.status,
        'the guardian session must be CONFINED. A 200 here means the session is carrying a role the ' +
        'service does not confine — almost always a STALE cy.session (bump the cache key in ' +
        'loginAsPortalGuardian) or auth-service not restarted since the role was seeded. It does NOT ' +
        'mean the filter is broken.').to.eq(404)
    })
  })

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
    // followRedirect:false ON PURPOSE. Run 1 asserted a bare status and got 200, which a plain status
    // check cannot distinguish between "a redirect was followed to some HTML page" and "a guardian just
    // read the roster" — two findings that could not be further apart. Assert the SECURITY property
    // instead, and log the real status so the next run explains the mechanism rather than restating it.
    cy.request({ url: '/portal/../getUserStudent', failOnStatusCode: false, followRedirect: false })
      .then((r) => {
        cy.log(`traversal → ${r.status} ${String((r.headers && r.headers.location) || '')}`)
        const body = JSON.stringify(r.body || '')
        expect(body, 'NO student data reaches a portal session, whatever the status')
          .to.not.match(/enrollNo/)
        expect(body, 'and no roster collection either').to.not.match(/"collection"\s*:\s*\[\s*\{/)
        expect(r.status, 'a staff read wearing a portal prefix is not a successful read').to.not.eq(200)
      })
  })

  it('a guardian cannot write, either', () => {
    cy.loginAsPortalGuardian()
    const doomed = 'CY_NEVER_' + Date.now()
    post('/addStudent', { name: doomed, enrollNo: 'EN' + Date.now(), status: 'ACTIVE' })
      .then((r) => {
        // REPORT before asserting. Three theories about this status died because I reasoned about it
        // instead of measuring it; the body names which handler produced the response:
        //   "Error Occurred" / InternalError  → the monolith catch-all won (advice ordering)
        //   {"success":false,...}             → education's refusal relayed
        cy.log(`write → ${r.status} :: ${JSON.stringify(r.body).slice(0, 200)}`)

        // THE SECURITY PROPERTY, independent of status code: the write must not happen.
        expect(r.status, 'a write by a guardian is never a success').to.not.be.oneOf([200, 201])
      })
    // And prove it for real — the student must not exist. A refusal that still wrote the row would be
    // the only genuinely serious outcome here, and no status assertion can detect it.
    cy.loginAsEduOwner()
    cy.request('/getUserStudent').then((r) => {
      const names = rows(r.body).map((s) => s.name)
      expect(names, 'nothing was created by the guardian').to.not.include(doomed)
    })
  })

  it("a REAL session cannot reach another guardian's child — the deny rule is not the only control", () => {
    // 3.1 proved child-scoping with a session that had no portal ROLE, because none existed yet. This is
    // the same property asserted with the principal that actually ships: `PortalScopeFilter` lets
    // /portal/** through, so from here on the ONLY thing standing between this guardian and another
    // family's record is ChildResolver. Both halves have to hold, and only this case tests the second one.
    cy.loginAsPortalGuardian()
    cy.request({ url: '/portal/attendance?enrollNo=' + encodeURIComponent(fx.otherChild.enrollNo),
      failOnStatusCode: false }).then((r) => {
      const b = parse(r.body)
      expect(b.status, "another guardian's child is NOT_FOUND, never FORBIDDEN — 3.1 D2").to.eq('NOT_FOUND')
      expect(JSON.stringify(b), 'and no attendance figures leak with the refusal').to.not.match(/"recent"/)
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
