/**
 * Slice 3.3 — the student portal, and the two controls that keep it narrow.
 * Design: microservices/docs/slices/edu-3.3-student-portal.md
 *
 * The resolver's rule is pure and lives in StudentResolverTest, on every `mvn test`. Asserted HERE is what
 * no unit test can reach: **what a REAL signed-in student session can and cannot do.**
 *
 *   - a student session is CONFINED to /portal/** (case 1, and everything below depends on it)
 *   - it reads its own week, results, homework and attendance
 *   - **passing another student's enrolment number changes nothing** — the endpoints take no subject
 *   - **fee dues and behaviour notes are unreachable** — policy (D4), gated so a change must be deliberate
 *   - the guardian portal is UNAFFECTED by the extraction (finding B's regression)
 *   - both switches close it, and revoking closes it
 *
 * 3.1b proved that pure tests over this filter can be green while it is wide open, because they fed it a
 * header the gateway does not send. Case 1 is the answer to that, and it runs first.
 *
 * FIXTURES ARE SEEDED, NEVER SKIPPED. The student LOGIN is seeded dev-only in auth-service
 * (student.education@myplus.com); the Student RECORD carrying that address is created here, because
 * students live in education-service.
 *
 * Requires education-service + auth-service + monolith up, all rebuilt (common-security is unchanged by
 * this slice, but education-service and auth-service are not).
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

const STUDENT_EMAIL = 'student.education@myplus.com'
const TAG = 'CySP' + Date.now()
const fx = {}

/** A raw request as whatever session is active — used to probe what a student may reach. */
const probe = (url) => cy.request({ url, failOnStatusCode: false })

describe('Education — student portal (slice 3.3)', () => {
  before(() => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')
    setConfig('edu.portal.students.enabled', 'true')

    // The Student RECORD must carry the same address as the seeded login — that address is the only link
    // between the two systems (StudentResolver resolves by the session's email).
    cy.request('/getUserStudent').then((r) => {
      fx.student = rows(r.body).find((s) => (s.email || '').toLowerCase() === STUDENT_EMAIL)
      if (fx.student) return
      post('/addStudent', {
        name: TAG + ' Portal', enrollNo: 'SP' + Date.now(), status: 'ACTIVE', email: STUDENT_EMAIL,
      }).then((res) => ok(res, 'seed the student record'))
    })
    cy.then(() => {
      if (fx.student) return
      cy.request('/getUserStudent').then((r) => {
        fx.student = rows(r.body).find((s) => (s.email || '').toLowerCase() === STUDENT_EMAIL)
        expect(fx.student, 'the student record exists and carries the portal address').to.exist
      })
    })

    // Somebody else — the record a student must never reach, whatever they pass.
    cy.then(() => {
      cy.request('/getUserStudent').then((r) => {
        fx.other = rows(r.body).find((s) => s.enrollNo && s.enrollNo !== fx.student.enrollNo)
        if (fx.other) return
        const enrollNo = 'SO' + Date.now()
        post('/addStudent', { name: TAG + ' Other', enrollNo, status: 'ACTIVE' })
          .then((res) => ok(res, 'seed another student'))
        fx.other = { enrollNo }
      })
    })

    // Grant access. Revoke first so the invite always takes the full path — 3.1b §13's lesson: reading
    // the portal is what flips the row INVITED → ACTIVE, so an aborted run leaves state that makes the
    // invite short-circuit and report nothing.
    cy.then(() => {
      cy.request('/getPortalAccess').then((r) => {
        const existing = rows(r.body).find(
          (a) => a.subjectType === 'STUDENT' && a.subjectId === fx.student.id)
        if (existing) post('/revokePortalAccess', { id: existing.id })
      })
    })
    cy.then(() => {
      post('/invitePortalAccess', { subjectType: 'STUDENT', studentId: fx.student.id })
        .then((r) => {
          const b = ok(r, 'invite the portal student')
          fx.inviteAccount = b.object && b.object.account
        })
    })
    cy.then(() => {
      cy.request('/getPortalAccess').then((r) => {
        fx.access = rows(r.body).find((a) => a.subjectType === 'STUDENT' && a.subjectId === fx.student.id)
        expect(fx.access, 'the student access row exists').to.exist
      })
    })
  })

  after(() => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')
    setConfig('edu.portal.students.enabled', 'true')
    if (fx.student) post('/invitePortalAccess', { subjectType: 'STUDENT', studentId: fx.student.id })
  })

  // ── the precondition, FIRST ─────────────────────────────────────────────────────────────────────

  it('the student session is CONFINED — the precondition every case below depends on', () => {
    // THIS RUNS FIRST BECAUSE 3.1b's EQUIVALENT COST SIX GATE RUNS, AND THEN A FULL ROSTER DISCLOSURE.
    //
    // /portal/** resolves a student by EMAIL; the deny rule keys on the ROLE in the JWT. Those can
    // disagree — a stale cy.session, an unrestarted auth-service, or ROLE_STUDENT missing from
    // `myplus.portal.confined-roles`. Every such failure looks like a broken filter and is not one.
    cy.loginAsPortalStudent()
    probe('/getUserStudent').then((r) => {
      expect(r.status,
        'the student session must be CONFINED. A 200 here means the session carries a role the service ' +
        'does not confine — almost always ROLE_STUDENT missing from myplus.portal.confined-roles in ' +
        'education-service.yml, a stale cy.session (bump the cache key in loginAsPortalStudent), or ' +
        'auth-service not restarted since the role was seeded. It does NOT mean the filter is broken.')
        .to.eq(404)
    })
  })

  // ── my own record ───────────────────────────────────────────────────────────────────────────────

  it('a signed-in student reads their OWN week, results, homework and attendance', () => {
    cy.loginAsPortalStudent()
    const READS = ['/portal/my/timetable', '/portal/my/results', '/portal/my/homework', '/portal/my/attendance']
    READS.forEach((url) => {
      probe(url).then((r) => {
        const b = parse(r.body)
        expect(b.status, `${url}: ${JSON.stringify(b).slice(0, 200)}`).to.eq('SUCCESS')
      })
    })
    probe('/portal/my/me').then((r) => {
      const b = parse(r.body)
      expect(b.status).to.eq('SUCCESS')
      expect(b.object.enrollNo, 'and it is THEIR record, not just any').to.eq(fx.student.enrollNo)
    })
    // ELIGIBILITY, not existence — added 2026-08-07 after slice 3.5 found this fixture had NO class.
    // `timetable()` returns an empty list immediately when gradeId is null, so the SUCCESS assertion
    // above was passing against an empty week: green, and proving less than it looked. A fixture that
    // cannot exercise the read is a fixture that must fail here instead.
    cy.loginAsEduOwner()
    cy.request('/getUserStudent').then((r) => {
      const me = rows(r.body).find((s) => (s.email || '').toLowerCase() === STUDENT_EMAIL)
      expect(me.gradeId,
        'the fixture student must be IN a class, or the timetable read cannot return anything and this ' +
        'case proves nothing. Seed one (notices.cy.js does this in its before hook).').to.not.be.null
    })
  })

  it('an enrolment number passed by hand changes NOTHING — the endpoints take no subject', () => {
    // D2: a student's set has one member, so no endpoint reads a parameter. This is the assertion that
    // proves it rather than trusting the code to keep not reading one — the same shape of guarantee the
    // guardian portal needs a whole ChildResolver to provide.
    cy.loginAsPortalStudent()
    let bare
    probe('/portal/my/attendance').then((r) => { bare = JSON.stringify(parse(r.body)) })
    cy.then(() => {
      probe('/portal/my/attendance?enrollNo=' + encodeURIComponent(fx.other.enrollNo)).then((r) => {
        expect(JSON.stringify(parse(r.body)),
          "passing another student's number returns the caller's OWN answer, byte for byte").to.eq(bare)
      })
    })
  })

  it("another student's record is unreachable however it is asked for", () => {
    cy.loginAsPortalStudent()
    const OTHER = encodeURIComponent(fx.other.enrollNo)
    const attempts = [
      '/portal/my/results?enrollNo=' + OTHER,
      '/portal/my/homework?enrollNo=' + OTHER,
      '/portal/results?enrollNo=' + OTHER,        // the GUARDIAN surface, tried by a student
      '/portal/attendance?enrollNo=' + OTHER,
    ]
    attempts.forEach((url) => {
      probe(url).then((r) => {
        const body = JSON.stringify(parse(r.body) || '')
        expect(body, `${url} must not return another student's data`)
          .to.not.contain(fx.other.enrollNo)
      })
    })
  })

  // ── what a student may NOT see, by policy ───────────────────────────────────────────────────────

  it('fee dues and behaviour notes are unreachable to a student — D4, gated on purpose', () => {
    // A DOMAIN JUDGEMENT, not plumbing: a family's financial position is the guardian's business, and
    // 2.5's notes were written by staff with no expectation the child they are about would read them.
    // Gated so that adding either later has to be a deliberate act with a failing test in front of it.
    cy.loginAsPortalStudent()
    probe('/portal/my/dues').then((r) => {
      expect(r.status, 'there is no student dues endpoint at all').to.eq(404)
    })
    probe('/portal/dues?enrollNo=' + encodeURIComponent(fx.student.enrollNo)).then((r) => {
      const b = parse(r.body)
      // The guardian's dues endpoint exists; a student session must not resolve through it.
      expect(JSON.stringify(b), 'the guardian dues endpoint returns no figures to a student session')
        .to.not.match(/"outstanding"/)
    })
    // Probed as STAFF first: a 404 only proves the filter if the route exists. Without this control,
    // renaming the endpoint would turn the assertion below into a green that proves nothing — the hollow
    // shape 2.1's skipped clash test and 2.4's empty class both had.
    cy.loginAsEduOwner()
    probe('/getBehaviourNotes').then((r) => {
      expect(r.status, 'the behaviour log exists and answers staff').to.eq(200)
    })
    cy.loginAsPortalStudent()
    probe('/getBehaviourNotes').then((r) => {
      expect(r.status, 'and the staff behaviour log is not reachable by a student').to.eq(404)
    })
  })

  it('a student cannot write, and the row is never created', () => {
    cy.loginAsPortalStudent()
    const doomed = 'CY_SP_NEVER_' + Date.now()
    post('/addStudent', { name: doomed, enrollNo: 'EN' + Date.now(), status: 'ACTIVE' }).then((r) => {
      cy.log(`write → ${r.status} :: ${JSON.stringify(r.body).slice(0, 200)}`)
      expect(r.status, 'a write by a student is never a success').to.not.be.oneOf([200, 201])
    })
    // Prove it for real. A refusal that still wrote the row is the only genuinely serious outcome, and
    // no status assertion can detect it (3.1b §9's lesson).
    cy.loginAsEduOwner()
    cy.request('/getUserStudent').then((r) => {
      expect(rows(r.body).map((s) => s.name), 'nothing was created').to.not.include(doomed)
    })
  })

  // ── the inverse regressions ─────────────────────────────────────────────────────────────────────

  it('staff are completely unaffected', () => {
    cy.loginAsEduOwner()
    probe('/getUserStudent').then((r) => {
      expect(r.status, 'staff still read the roster').to.eq(200)
    })
    probe('/getDashboardData').then((r) => {
      expect(r.status, 'and the dashboard still loads').to.eq(200)
    })
  })

  it('the GUARDIAN portal still works — the extraction did not change 3.1', () => {
    // Finding B moved the portal reads into a shared PortalReadService. If that extraction changed
    // behaviour, this is where it shows: the guardian surface must answer exactly as before.
    cy.loginAsPortalGuardian()
    probe('/portal/children').then((r) => {
      const b = parse(r.body)
      expect(b.status, 'a guardian still reads their children').to.eq('SUCCESS')
      expect((b.object || b.collection || []).length, 'and the set is not empty').to.be.greaterThan(0)
    })
    probe('/portal/me').then((r) => {
      expect(parse(r.body).status, 'and their own identity read still answers').to.eq('SUCCESS')
    })
  })

  // ── the switches ────────────────────────────────────────────────────────────────────────────────

  it('edu.portal.students.enabled=false closes the STUDENT portal and leaves the guardian one open', () => {
    // C2 — both halves, both directions. A switch that closes everything is not a student switch, and a
    // switch that closes nothing is decorative; only asserting both tells them apart.
    cy.loginAsEduOwner()
    setConfig('edu.portal.students.enabled', 'false')

    cy.loginAsPortalStudent()
    probe('/portal/my/timetable').then((r) => {
      expect(parse(r.body).status, 'the student portal is closed').to.eq('NOT_FOUND')
    })

    cy.loginAsPortalGuardian()
    probe('/portal/children').then((r) => {
      expect(parse(r.body).status, 'and the GUARDIAN portal is untouched by it').to.eq('SUCCESS')
    })

    cy.loginAsEduOwner()
    setConfig('edu.portal.students.enabled', 'true')
  })

  it('edu.portal.enabled=false closes BOTH — the master switch outranks the student one', () => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'false')
    // students.enabled stays TRUE on purpose: the point is that it cannot resurrect a closed portal.
    cy.loginAsPortalStudent()
    probe('/portal/my/timetable').then((r) => {
      expect(parse(r.body).status, 'the master switch wins').to.eq('NOT_FOUND')
    })
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')
  })

  it('revoking access stops the student reads', () => {
    cy.loginAsEduOwner()
    post('/revokePortalAccess', { id: fx.access.id }).then((r) => ok(r, 'revoke'))
    cy.loginAsPortalStudent()
    probe('/portal/my/timetable').then((r) => {
      expect(parse(r.body).status, 'a revoked student reads nothing').to.eq('NOT_FOUND')
    })
    cy.loginAsEduOwner()
    post('/invitePortalAccess', { subjectType: 'STUDENT', studentId: fx.student.id })
  })

  // ── provisioning boundaries (D5 / D6) ───────────────────────────────────────────────────────────

  it('a student with NO address cannot be invited — and that is D-7 made visible', () => {
    // The BOUNDARY of the invitation model, asserted so the limitation is visible in the gate rather than
    // discovered by a school. If primary schools hit this at scale, it is the trigger for join codes
    // (D-7 option B) — not a bug to route around.
    cy.loginAsEduOwner()
    const NAME = 'CySPNoMail Portal'
    cy.request('/getUserStudent').then((r) => {
      if (rows(r.body).find((s) => s.name === NAME)) return
      post('/addStudent', { name: NAME, enrollNo: 'SPNOMAIL', status: 'ACTIVE' })
        .then((res) => ok(res, 'seed a student with no address'))
    })
    cy.request('/getUserStudent').then((r) => {
      const s = rows(r.body).find((x) => x.name === NAME)
      expect(s, 'the address-less student was seeded').to.exist
      expect(s.email || '', 'and genuinely has no address').to.eq('')
      post('/invitePortalAccess', { subjectType: 'STUDENT', studentId: s.id }).then((res) => {
        const b = parse(res.body)
        expect(b.status, 'inviting without an address is refused, not half-done').to.not.eq('SUCCESS')
        expect(b.message, 'and the refusal names the fix').to.match(/email|address/i)
      })
    })
  })

  it("an address that already belongs to a GUARDIAN is REFUSED, never linked — the severe case", () => {
    // D6. auth-service keys a User by email and deliberately LINKS an existing address, because one adult
    // may be a guardian at two schools. For two DIFFERENT people that same behaviour would sign a child in
    // as their guardian. auth-service cannot tell the difference; education, holding both records, can —
    // so the refusal lives there, and this is the case that proves it did not get lost.
    cy.loginAsEduOwner()
    const shared = 'cyshared' + Date.now() + '@myplus.com'
    const gTag = 'CySPGuard' + Date.now()
    const sTag = 'CySPKid' + Date.now()
    post('/addGuardian', { name: gTag, email: shared, cnic: 'CG' + Date.now(), status: 'ACTIVE' })
      .then((r) => ok(r, 'seed a guardian holding the address'))
    post('/addStudent', { name: sTag, enrollNo: 'SS' + Date.now(), status: 'ACTIVE', email: shared })
      .then((r) => ok(r, 'seed a student carrying the SAME address'))
    cy.request('/getUserStudent').then((r) => {
      const s = rows(r.body).find((x) => x.name === sTag)
      expect(s, 'the colliding student was seeded').to.exist
      post('/invitePortalAccess', { subjectType: 'STUDENT', studentId: s.id }).then((res) => {
        const b = parse(res.body)
        expect(b.status, 'a shared address is refused outright').to.not.be.oneOf(['SUCCESS', 'PARTIAL'])
        expect(b.message, 'and the refusal explains WHY, because the school must fix the data')
          .to.match(/guardian/i)
      })
    })
    // And no access row was created for them — a refusal that still granted access would be the real harm.
    cy.request('/getPortalAccess').then((r) => {
      const granted = rows(r.body).filter((a) => (a.email || '').toLowerCase() === shared)
      expect(granted.length, 'no access row was created by the refused invite').to.eq(0)
    })
  })
})
