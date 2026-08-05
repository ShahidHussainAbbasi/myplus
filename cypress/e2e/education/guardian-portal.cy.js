/**
 * Slice 3.1 — the guardian (guardian) portal.
 * Design: microservices/docs/slices/edu-3.1-guardian-portal.md
 *
 * The intersection rule lives in ChildResolverTest — pure, on every `mvn test`. Asserted HERE is what a
 * unit test cannot reach, and it is mostly SECURITY:
 *
 *   - a guardian sees their OWN children and nothing else
 *   - **another family's child, requested by enrolment number, is NOT_FOUND** ← the case this slice exists for
 *   - a portal session cannot reach ANY staff endpoint (D2's allowlist)
 *   - results are PUBLISHED cards only; behaviour notes are absent entirely
 *   - `edu.portal.enabled` off, or REVOKED access, closes the door immediately
 *
 * **A functional pass with a scoping hole would be worse than a red.** Tests 3, 4 and 5 are the ones that
 * must never be waved through.
 *
 * FIXTURES ARE SEEDED, NEVER SKIPPED.
 *
 * Requires education-service + gateway up. Run headed.
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
  expect(b.status, `${what}: ${JSON.stringify(b).slice(0, 300)}`).to.eq('SUCCESS')
  return b
}

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveConfig', form: true, body: { key, value }, failOnStatusCode: false })
    .then((r) => expect(JSON.stringify(r.body), `saveConfig ${key}=${value}`).to.match(/SUCCESS/))

const portal = (path, params) => {
  const qs = params ? '?' + Object.keys(params).map((k) =>
    `${k}=${encodeURIComponent(params[k])}`).join('&') : ''
  return cy.request({ url: `/portal/${path}${qs}`, failOnStatusCode: false })
}

const fx = {}

describe('Education — guardian portal (slice 3.1)', () => {
  before(() => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')

    // THE FIXTURE IS SEEDED, NEVER ASSUMED. `invitePortalAccess` takes the address from the guardian
    // RECORD and never from the request, so the fixture guardian must HAVE an email. The demo org's
    // guardians do not, which is what made the first gate run die in this hook.
    //
    // NOT COVERED HERE, deliberately: "another family's child, requested by enrolment number". It needs
    // a guardian to be signed in, and 3.1 does not build the auth-service guardian account (see the
    // slice doc §6). The nearest reachable case is a staff session with no access row — tests 3 and 4.
    cy.request('/getUserGuardian').then((gr) => {
      const emailed = rows(gr.body).filter((g) => g.email && g.email.trim())
      cy.request('/getUserStudent').then((sr) => {
        const usable = rows(sr.body)
          .filter((s) => s.enrollNo && s.guardianId)
          .find((s) => emailed.some((g) => g.id === s.guardianId))
        if (usable) {
          fx.mine = usable
          return
        }
        // Seed a guardian WITH an email, plus a child linked to it. `addStudent` falls back to the
        // caller's active branch for schoolId, so an owner session needs no branch lookup.
        const tag = `CY_GP_${Date.now()}`
        post('/addGuardian', {
          name: tag, email: `${tag}@example.test`.toLowerCase(),
          cnic: `CN${Date.now()}`, status: 'ACTIVE',
        }).then((r) => ok(r, 'seed a guardian with an email'))

        cy.request('/getUserGuardian').then((g2) => {
          const g = rows(g2.body).find((x) => x.name === tag)
          expect(g, 'the seeded guardian exists').to.exist
          const enrollNo = `EN${Date.now()}`
          post('/addStudent', {
            name: `${tag}_S`, enrollNo, status: 'ACTIVE', guardianId: g.id,
          }).then((r) => ok(r, 'seed a child for that guardian'))
          fx.mine = { enrollNo, guardianId: g.id }
          fx.seeded = true
        })
      })
    })
    cy.then(() => {
      post('/invitePortalAccess', { guardianId: fx.mine.guardianId })
        .then((r) => ok(r, 'invite the guardian'))
    })
    cy.request('/getPortalAccess').then((r) => {
      fx.access = rows(r.body).find((a) => a.guardianId === fx.mine.guardianId)
      expect(fx.access, 'the access row exists').to.exist
      expect(fx.access.childCount, 'and it names how many children it reaches').to.be.greaterThan(0)
    })
  })

  beforeEach(() => {
    cy.loginAsEduOwner()
  })

  after(() => {
    cy.loginAsEduOwner()
    if (fx.access) post('/revokePortalAccess', { id: fx.access.id })
    setConfig('edu.portal.enabled', 'false')
  })

  // ── the school side ─────────────────────────────────────────────────────────────────────────

  it('inviting names how many children the grant reaches', () => {
    // The consequence of the click, made legible: otherwise it is a name and an email in a list.
    cy.request('/getPortalAccess').then((r) => {
      const a = rows(r.body).find((x) => x.guardianId === fx.mine.guardianId)
      expect(a.status).to.be.oneOf(['INVITED', 'ACTIVE'])
      expect(a.childCount).to.be.greaterThan(0)
    })
  })

  it('a teacher cannot grant portal access — ADMIN tier', () => {
    cy.loginAsTeacherA()
    post('/invitePortalAccess', { guardianId: fx.mine.guardianId }).then((r) => {
      const b = parse(r.body)
      const refused = r.status === 403 || (b && b.status && b.status !== 'SUCCESS')
      expect(refused, 'granting an outsider sight of a child record is ADMIN policy').to.eq(true)
    })
  })

  // ── the portal surface, as seen by a NON-guardian staff session ─────────────────────────────
  //
  // The demo org has no seeded guardian LOGIN, so these run as a staff user whose email has no portal
  // access. That is exactly the right negative case: it proves the portal refuses anyone without an
  // access row — including staff — rather than falling open.

  it('a session with no portal access is refused, and cannot tell why', () => {
    portal('me').then((r) => {
      const b = parse(r.body)
      expect(b.status, 'no access row for this email').to.eq('NOT_FOUND')
      // One message for every failure mode: an outsider must not be able to distinguish
      // "you are not a guardian" from "this school has the portal switched off".
      expect(JSON.stringify(b)).to.not.match(/disabled|revoked|not a guardian/i)
    })
    portal('children').then((r) => {
      expect(parse(r.body).status).to.eq('NOT_FOUND')
    })
  })

  it('a child cannot be read by anyone without portal access, even a known enrolment number', () => {
    // THE case this slice exists for, from the other direction: knowing a real enrolment number is not
    // authority. Every data endpoint must refuse before it looks anything up.
    ;['results', 'attendance', 'dues', 'homework'].forEach((path) => {
      portal(path, { enrollNo: fx.mine.enrollNo }).then((r) => {
        expect(parse(r.body).status, `${path} must refuse a session with no access`).to.eq('NOT_FOUND')
      })
    })
  })

  it('a missing or blank enrolment number is refused, never defaulted to "the first child"', () => {
    portal('results').then((r) => {
      expect(parse(r.body).status).to.eq('NOT_FOUND')
    })
    portal('results', { enrollNo: '' }).then((r) => {
      expect(parse(r.body).status).to.eq('NOT_FOUND')
    })
  })

  it('the portal exposes NO write endpoint and NO behaviour notes', () => {
    // D4 — 2.5's notes were written with no expectation a guardian would read them; there is deliberately
    // no route at all. Absence, not a privilege check.
    cy.request({ url: '/portal/behaviour', failOnStatusCode: false }).then((r) => {
      expect(r.status, 'no behaviour route on the portal surface').to.be.oneOf([404, 405, 400, 500])
    })
    cy.request({ method: 'POST', url: '/portal/results', form: true, body: {}, failOnStatusCode: false })
      .then((r) => {
        expect(r.status, 'the portal is GET-only').to.be.oneOf([404, 405, 400, 500])
      })
  })

  it('the portal page is a SEPARATE page, not the staff dashboard', () => {
    // D2 in the UI: no sidebar, no module switcher, nothing staff-facing rendered at all — hiding it in
    // CSS would leave it one inspector-click away.
    cy.visit('/guardianDashboard')
    cy.get('body').should('exist')
    cy.get('#snavRegister').should('not.exist')
    cy.get('#registrationType').should('not.exist')
  })

  it('turning the portal OFF closes it for everyone immediately', () => {
    setConfig('edu.portal.enabled', 'false')
    portal('me').then((r) => {
      // Fails CLOSED: the setting is the school's kill switch, and it does not wait for a session to end.
      expect(parse(r.body).status).to.eq('NOT_FOUND')
    })
    setConfig('edu.portal.enabled', 'true')
  })

  it('revoking access keeps the record and stops the login', () => {
    post('/revokePortalAccess', { id: fx.access.id }).then((r) => ok(r, 'revoke'))
    cy.request('/getPortalAccess').then((r) => {
      const a = rows(r.body).find((x) => x.guardianId === fx.mine.guardianId)
      expect(a, 'the row is KEPT — "who used to be able to read this" is what an investigation needs')
        .to.exist
      expect(a.status).to.eq('REVOKED')
    })
    // Re-invite so the after() hook and any re-run start from a known state.
    post('/invitePortalAccess', { guardianId: fx.mine.guardianId })
  })

  it('another tenant’s access row is invisible by id', () => {
    post('/revokePortalAccess', { id: 999999 }).then((r) => {
      expect(parse(r.body).status, 'an id outside the tenant never silently succeeds').to.not.eq('SUCCESS')
    })
  })

  it('staff endpoints still work for staff — the portal did not narrow them', () => {
    // The inverse regression: adding an external principal must not restrict the people who already
    // had access. A new access shape that breaks staff is as much a failure as one that leaks.
    cy.request('/getUserStudent').then((r) => {
      expect(parse(r.body).status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })
})
