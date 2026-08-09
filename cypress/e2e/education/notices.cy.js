/**
 * Slice 3.5 — school notices & circulars.
 * Design: microservices/docs/slices/edu-3.5-notices.md
 *
 * The audience rule is pure and lives in NoticeAudienceResolverTest, on every `mvn test`. Asserted HERE is
 * what no unit test can reach: **what real signed-in guardian and student sessions actually see.**
 *
 *   - a published notice reaches both portals, and a DRAFT reaches neither
 *   - **a GUARDIANS notice is invisible to a student, and a STUDENTS notice to a guardian** ← the disclosure
 *   - a ONE_CLASS notice reaches only that class
 *   - publishing QUEUES; it does not send on the request thread
 *   - `edu.notify.notices=false` stops the EMAIL but the notice is still readable ← finding C, gated
 *   - alerts still work after being migrated onto the outbox (finding A's regression)
 *
 * FIXTURES ARE SEEDED, NEVER SKIPPED, and both portal logins are the dev-only seeded accounts
 * (guardian.education@ / student.education@) that 3.1b and 3.3 established.
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

const probe = (url) => cy.request({ url, failOnStatusCode: false })

/** Publish a notice and return its title — the portal map carries no id, so the title is the handle. */
const publish = (title, audience, gradeId) => {
  const body = { title, body: title + ' — body text', audience }
  if (gradeId) body.gradeId = gradeId
  return post('/saveNotice', body).then((r) => {
    const b = ok(r, `save ${audience} notice`)
    return post('/publishNotice', { id: b.object.id }).then((p) => {
      ok(p, `publish ${audience} notice`)
      return title
    })
  })
}

const TAG = 'CyNote' + Date.now()
const fx = {}

describe('Education — notices & circulars (slice 3.5)', () => {
  before(() => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')
    setConfig('edu.portal.students.enabled', 'true')
    setConfig('edu.notify.notices', 'true')

    // The seeded student's own class — a ONE_CLASS notice needs a real class to target, and asserting
    // against a class nobody is in would be a green that proves nothing.
    //
    // SEEDED, NOT ASSUMED. 3.3 created this student with no class at all, which this spec's guard caught
    // on its first run: `fixture student has no class`. That is the standing rule paying off — a fixture's
    // EXISTENCE is not its ELIGIBILITY, and the case that needs a class must make one rather than skip.
    cy.request('/getUserStudent').then((r) => {
      const all = rows(r.body)
      const me = all.find((s) => (s.email || '').toLowerCase() === 'student.education@myplus.com')
      expect(me, 'the 3.3 student fixture exists').to.exist
      fx.studentId = me.id

      // Two distinct classes that real students are already in — so ONE_CLASS has something to include
      // AND something to exclude. Without the second, "reaches only that class" proves only "reaches".
      const used = [...new Set(all.map((s) => s.gradeId).filter(Boolean))]
      expect(used.length, 'the tenant has at least two classes in use, so exclusion is testable')
        .to.be.greaterThan(1)
      fx.myGrade = me.gradeId || used[0]
      fx.otherGrade = used.find((g) => g !== fx.myGrade)

      if (!me.gradeId) {
        // Put the fixture student in a class, once. addStudent doubles as the edit path when an id is sent.
        post('/addStudent', {
          id: me.id, name: me.name, enrollNo: me.enrollNo, status: 'ACTIVE',
          email: me.email, gradeId: fx.myGrade,
        }).then((res) => ok(res, 'place the fixture student in a class'))
      }
    })
    cy.then(() => {
      cy.request('/getUserStudent').then((r) => {
        const me = rows(r.body).find((s) => s.id === fx.studentId)
        expect(me.gradeId, 'the fixture student is now in a class').to.eq(fx.myGrade)
      })
    })
  })

  after(() => {
    cy.loginAsEduOwner()
    setConfig('edu.notify.notices', 'true')
  })

  // ── the record, and who sees it ─────────────────────────────────────────────────────────────────

  it('publishing states the REACH, and reports queued — never "sent"', () => {
    cy.loginAsEduOwner()
    // The count BEFORE the act: "you are about to tell N families something" is the fact the person
    // clicking cannot otherwise see (D1, same reasoning as 3.1's childCount on the invite).
    cy.request('/getNoticeReach?audience=WHOLE_SCHOOL&gradeId=').then((r) => {
      const b = parse(r.body)
      expect(b.status).to.eq('SUCCESS')
      expect(b.object.recipients, 'the school is told how many addresses this reaches').to.be.a('number')
    })
    post('/saveNotice', { title: TAG + ' Reach', body: 'body', audience: 'WHOLE_SCHOOL' }).then((r) => {
      const saved = ok(r, 'save')
      post('/publishNotice', { id: saved.object.id }).then((p) => {
        const b = ok(p, 'publish')
        expect(b.object).to.have.property('queued')
        expect(b.object).to.have.property('recipients')
        // ASSERT THE COUNT, not just the key. Checking only that `queued` EXISTS is satisfied by zero —
        // and on 2026-08-09 slice 105's gate proved it had been zero all along: queueAll() applied the
        // switch and then delegated per recipient with a NULL key, which enabled() read as "off". This
        // notice reached nobody while every assertion here passed.
        expect(b.object.queued, 'the notice actually queued somebody').to.be.greaterThan(0)
        expect(b.object.queued, 'and queued every resolved recipient').to.eq(b.object.recipients)
        // The WORD matters: the relay sends, this request queues. Reporting "sent" here would be the
        // same defect this slice fixes one layer up in sendAlerts (finding A).
        expect(b.message, 'the message says queued, not sent').to.match(/queued/i)
        expect(b.message, 'and never claims it was sent').to.not.match(/\bsent to\b/i)
      })
    })
  })

  it('a WHOLE_SCHOOL notice reaches BOTH portals', () => {
    cy.loginAsEduOwner()
    publish(TAG + ' All', 'WHOLE_SCHOOL')
    cy.then(() => {
      cy.loginAsPortalGuardian()
      probe('/portal/notices').then((r) => {
        expect(rows(r.body).map((n) => n.title), 'the guardian sees it').to.include(TAG + ' All')
      })
      cy.loginAsPortalStudent()
      probe('/portal/my/notices').then((r) => {
        expect(rows(r.body).map((n) => n.title), 'and so does the student').to.include(TAG + ' All')
      })
    })
  })

  it('a DRAFT reaches NEITHER portal — the one boundary this entity has', () => {
    cy.loginAsEduOwner()
    const title = TAG + ' Draft'
    post('/saveNotice', { title, body: 'not published', audience: 'WHOLE_SCHOOL' })
      .then((r) => ok(r, 'save a draft'))   // saved, deliberately NOT published
    cy.then(() => {
      cy.loginAsPortalGuardian()
      probe('/portal/notices').then((r) => {
        expect(rows(r.body).map((n) => n.title), 'a draft is invisible to a guardian').to.not.include(title)
      })
      cy.loginAsPortalStudent()
      probe('/portal/my/notices').then((r) => {
        expect(rows(r.body).map((n) => n.title), 'and to a student').to.not.include(title)
      })
    })
  })

  it('THE DISCLOSURE CASE: a GUARDIANS notice is invisible to a student, and vice versa', () => {
    // A GUARDIANS notice is where a school puts fee deadlines; a STUDENTS notice is where it puts exam
    // instructions. Neither is meant for the other, and the audience filter is the ONLY thing enforcing
    // that — reading a notice is not privilege-gated (D5).
    cy.loginAsEduOwner()
    publish(TAG + ' GuardOnly', 'GUARDIANS')
    publish(TAG + ' StudOnly', 'STUDENTS')

    cy.then(() => {
      cy.loginAsPortalStudent()
      probe('/portal/my/notices').then((r) => {
        const titles = rows(r.body).map((n) => n.title)
        expect(titles, 'a student must NOT see a guardians-only notice').to.not.include(TAG + ' GuardOnly')
        expect(titles, 'but does see their own').to.include(TAG + ' StudOnly')
      })
      cy.loginAsPortalGuardian()
      probe('/portal/notices').then((r) => {
        const titles = rows(r.body).map((n) => n.title)
        expect(titles, 'a guardian must NOT see a students-only notice').to.not.include(TAG + ' StudOnly')
        expect(titles, 'but does see their own').to.include(TAG + ' GuardOnly')
      })
    })
  })

  it('a ONE_CLASS notice reaches only that class', () => {
    cy.loginAsEduOwner()
    // Both fixtures are guaranteed by before(), so there is no conditional here: a case that quietly
    // skips its own most important assertion is the hollow-green shape 2.1 was caught by.
    cy.then(() => {
      publish(TAG + ' MyClass', 'ONE_CLASS', fx.myGrade)
      publish(TAG + ' OtherClass', 'ONE_CLASS', fx.otherGrade)
    })
    cy.then(() => {
      cy.loginAsPortalStudent()
      probe('/portal/my/notices').then((r) => {
        const titles = rows(r.body).map((n) => n.title)
        expect(titles, "the student's own class notice arrives").to.include(TAG + ' MyClass')
        expect(titles, "and another class's does NOT — this is the assertion that matters")
          .to.not.include(TAG + ' OtherClass')
      })
    })
  })

  it('a published notice cannot be edited — families have already been told', () => {
    cy.loginAsEduOwner()
    post('/saveNotice', { title: TAG + ' Frozen', body: 'v1', audience: 'WHOLE_SCHOOL' }).then((r) => {
      const id = ok(r, 'save').object.id
      post('/publishNotice', { id }).then((p) => ok(p, 'publish'))
      post('/saveNotice', { id, title: TAG + ' Frozen EDITED', body: 'v2', audience: 'WHOLE_SCHOOL' })
        .then((e) => {
          const b = parse(e.body)
          expect(b.status, 'editing a published notice is refused').to.not.eq('SUCCESS')
          expect(b.message, 'and the refusal names the alternative').to.match(/publish a new/i)
        })
    })
  })

  // ── the switch: it governs the SEND, not the RECORD (C2, finding C) ─────────────────────────────

  it('edu.notify.notices=false stops the email but the notice is STILL READABLE', () => {
    // The whole of finding C in one case. If this ever fails by the notice disappearing, someone has
    // wired the switch to visibility — which would make a school choosing "do not email" also silently
    // hide its own notices.
    cy.loginAsEduOwner()
    setConfig('edu.notify.notices', 'false')
    const title = TAG + ' Silent'
    post('/saveNotice', { title, body: 'no email', audience: 'WHOLE_SCHOOL' }).then((r) => {
      const id = ok(r, 'save').object.id
      post('/publishNotice', { id }).then((p) => {
        const b = ok(p, 'publish with delivery off')
        // Only meaningful because the ON case above now proves a non-zero count. Before that, this
        // "switch off => 0" case passed against a system that queued 0 unconditionally — a control
        // asserting the value it would have had anyway proves nothing.
        expect(b.object.queued, 'nothing was queued for delivery').to.eq(0)
      })
    })
    cy.then(() => {
      cy.loginAsPortalGuardian()
      probe('/portal/notices').then((r) => {
        expect(rows(r.body).map((n) => n.title),
          'but the notice is still published and still readable').to.include(title)
      })
    })
    cy.loginAsEduOwner()
    setConfig('edu.notify.notices', 'true')
  })

  // ── privilege + regression ──────────────────────────────────────────────────────────────────────

  it('a teacher cannot publish — ADMIN tier', () => {
    cy.loginAsEduOwner()
    post('/saveNotice', { title: TAG + ' TeacherTry', body: 'x', audience: 'WHOLE_SCHOOL' }).then((r) => {
      const id = ok(r, 'save as owner').object.id
      cy.loginAsTeacherA()
      post('/publishNotice', { id }).then((p) => {
        const b = parse(p.body)
        const refused = p.status === 403 || !(b && ['SUCCESS', 'PARTIAL'].includes(b.status))
        expect(refused, 'addressing the whole school is a policy act, not teacher work').to.eq(true)
      })
    })
  })

  it('a portal session cannot reach the STAFF notice endpoints', () => {
    // The deny rule covers this (/getNotices is not under /portal/**), but the case is cheap and it is
    // the one that would catch a future notice endpoint being added under the wrong prefix.
    cy.loginAsPortalGuardian()
    probe('/getNotices').then((r) => expect(r.status, 'staff list is refused').to.eq(404))
    cy.loginAsPortalStudent()
    probe('/getNotices').then((r) => expect(r.status, 'for both audiences').to.eq(404))
  })

  it('ALERTS still work after being migrated onto the outbox — finding A regression', () => {
    // sendAlerts moved off a direct, synchronous emailService.send() onto N1's outbox. The observable
    // change is deliberate: it reports QUEUED, not SENT. This asserts both that it still works and that
    // it no longer claims to have sent anything.
    cy.loginAsEduOwner()
    post('/sendAlerts', { ah: TAG + ' Alert', am: 'body', c: 'Guardians' }).then((r) => {
      const b = ok(r, 'send an alert')
      expect(b.message, 'the alert screen now reports queued').to.match(/queued/i)
      expect(b.object, 'and returns the counts').to.have.property('queued')
      // "Queued for 0 of 40 recipient(s)" matches /queued/i and has the property. It is also a broadcast
      // that told nobody anything, which is exactly what this smoke was supposed to catch.
      expect(b.object.queued, 'the alert reached a real audience').to.be.greaterThan(0)
    })
  })
})
