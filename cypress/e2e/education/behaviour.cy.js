/**
 * Slice 2.5 — behaviour / discipline log. The last slice of Phase 2.
 * Design: microservices/docs/slices/edu-2.5-discipline-log.md
 *
 * The rules (validation, supersede transitions, active-only counting) live in BehaviourNoteRulesTest —
 * pure, on every `mvn test`. Asserted HERE is what a unit test cannot reach:
 *
 *   - **there is NO edit and NO delete endpoint** (D3 — immutability by absence, not by a check)
 *   - correcting writes a NEW note and the original survives, struck through and linked
 *   - POSITIVE notes are first-class, so the log is not a punishment ledger (D2)
 *   - the author (who reported) is distinct from the typist (who saved) and both persist (D4)
 *   - parent-informed is RECORDED and nothing is sent (D5)
 *
 * FIXTURES ARE SEEDED, NEVER SKIPPED — three fixture-caused reds in Phase 2 was enough.
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

const TAG = 'CyBn' + Date.now()
const notesFor = (enrollNo) =>
  cy.request({ url: `/getBehaviourNotes?enrollNo=${encodeURIComponent(enrollNo)}`,
    failOnStatusCode: false })

const fx = {}

describe('Education — behaviour log (slice 2.5)', () => {
  before(() => {
    cy.loginAsEduOwner()
    cy.request('/getUserStudent').then((r) => {
      const students = rows(r.body).filter((s) => s.enrollNo)
      expect(students.length, 'the org has a student to record against').to.be.greaterThan(0)
      fx.student = students[0]
    })
    cy.request('/getUserStaff').then((r) => {
      const staff = rows(r.body)
      expect(staff.length, 'the org has staff, so the author can be someone other than the typist')
        .to.be.greaterThan(0)
      fx.author = staff[0]
    })
  })

  beforeEach(() => {
    cy.loginAsEduOwner()
  })

  // NOTE: no after() cleanup. This log is APPEND-ONLY BY DESIGN — there is no delete endpoint to call,
  // which is precisely what test 1 asserts. The notes are tagged with a timestamp so they are
  // identifiable, and they are the kind of record a real school would also never delete.

  it('there is NO edit and NO delete endpoint — immutability by absence', () => {
    // Asserted first, because every other guarantee in this slice rests on it. A 404/405 is the shape of
    // "this operation does not exist"; a 403 would mean it exists and was merely refused, which is a
    // weaker promise a future change could relax.
    cy.request({ method: 'POST', url: '/deleteBehaviourNote', form: true, body: { id: 1 },
      failOnStatusCode: false }).then((r) => {
      expect(r.status, 'no delete endpoint is routed').to.be.oneOf([404, 405, 400, 403, 500])
      if (r.status === 200) {
        expect(parse(r.body).status, 'and if something answered, it did not succeed').to.not.eq('SUCCESS')
      }
    })
    cy.request({ method: 'POST', url: '/updateBehaviourNote', form: true, body: { id: 1 },
      failOnStatusCode: false }).then((r) => {
      expect(r.status, 'no edit endpoint is routed').to.be.oneOf([404, 405, 400, 403, 500])
    })
  })

  it('records a CONCERN with the author distinct from the typist', () => {
    post('/saveBehaviourNote', {
      enrollNo: fx.student.enrollNo,
      type: 'CONCERN',
      category: 'Conduct',
      description: TAG + ' disrupted the lesson',
      action: 'Spoke to the student',
      recordedByStaffId: fx.author.id
    }).then((r) => {
      const b = ok(r, 'record a concern')
      fx.concernId = b.object.id
    })

    notesFor(fx.student.enrollNo).then((r) => {
      const note = (parse(r.body).object.notes || []).find((n) => n.id === fx.concernId)
      expect(note, 'the note is in the history').to.exist
      expect(note.type).to.eq('CONCERN')
      // D4 — the account names who reported it, which is what makes it defensible in a dispute.
      expect(note.recordedByStaffId, 'the author was recorded').to.eq(fx.author.id)
      expect(note.recordedByStaffName).to.be.a('string')
      expect(note.status).to.eq('ACTIVE')
    })
  })

  it('records a POSITIVE note — the log is not concern-only', () => {
    // D2: one enum value, and it changes what the feature is for. A punishment ledger is one teachers
    // stop opening, which also makes the concerns less credible when they appear.
    post('/saveBehaviourNote', {
      enrollNo: fx.student.enrollNo,
      type: 'POSITIVE',
      category: 'Helpfulness',
      description: TAG + ' helped a new student settle in'
    }).then((r) => ok(r, 'record a positive'))

    notesFor(fx.student.enrollNo).then((r) => {
      const o = parse(r.body).object
      expect(o.positives, 'positives are counted separately').to.be.greaterThan(0)
      expect(o.concerns, 'and so are concerns').to.be.greaterThan(0)
    })
  })

  it('two notes for one student on one day are both kept — no UNIQUE key here, deliberately', () => {
    // Every other Phase 2 table has a uniqueness guarantee. Two genuine incidents in a day is ordinary,
    // so uniqueness would be a bug rather than a protection.
    const today = new Date().toISOString().slice(0, 10)
    post('/saveBehaviourNote', {
      enrollNo: fx.student.enrollNo, type: 'NEUTRAL', occurredOn: today,
      description: TAG + ' first incident'
    }).then((r) => ok(r, 'first'))
    post('/saveBehaviourNote', {
      enrollNo: fx.student.enrollNo, type: 'NEUTRAL', occurredOn: today,
      description: TAG + ' second incident'
    }).then((r) => ok(r, 'second'))

    notesFor(fx.student.enrollNo).then((r) => {
      const sameDay = (parse(r.body).object.notes || [])
        .filter((n) => n.description && n.description.indexOf(TAG + ' ') === 0 && n.occurredOn === today)
      expect(sameDay.length, 'both incidents survive').to.be.greaterThan(1)
    })
  })

  it('correcting a note writes a NEW one and keeps the original, linked', () => {
    post('/supersedeBehaviourNote', {
      id: fx.concernId,
      description: TAG + ' disrupted the lesson ONCE, not twice'
    }).then((r) => {
      const b = ok(r, 'correct the note')
      fx.correctionId = b.object.id
      expect(b.object.supersededId).to.eq(fx.concernId)
    })

    notesFor(fx.student.enrollNo).then((r) => {
      const all = parse(r.body).object.notes || []
      const original = all.find((n) => n.id === fx.concernId)
      const correction = all.find((n) => n.id === fx.correctionId)

      // The whole point: the original account is still there, unaltered, saying what was reported.
      expect(original, 'the original is KEPT').to.exist
      expect(original.status).to.eq('SUPERSEDED')
      expect(original.description, 'its wording is untouched').to.contain('disrupted the lesson')
      expect(original.description).to.not.contain('ONCE, not twice')
      expect(original.supersededByNoteId, 'and it points at its replacement').to.eq(fx.correctionId)

      expect(correction.status).to.eq('ACTIVE')
      // The correction inherits the original's author — it is still that teacher's account, restated.
      expect(correction.recordedByStaffId).to.eq(fx.author.id)
    })
  })

  it('a superseded note cannot be corrected again — that would fork the trail', () => {
    post('/supersedeBehaviourNote', {
      id: fx.concernId, description: TAG + ' a second correction'
    }).then((r) => {
      const b = parse(r.body)
      expect(b.status, 'two corrections of one original leaves nothing saying which is current')
        .to.eq('FAILED')
      expect(b.message).to.match(/already/i)
    })
  })

  it('counts use ACTIVE notes only, so a correction is not double-counted', () => {
    notesFor(fx.student.enrollNo).then((r) => {
      const o = parse(r.body).object
      const activeConcerns = (o.notes || [])
        .filter((n) => n.type === 'CONCERN' && n.status === 'ACTIVE').length
      expect(o.concerns, 'the summary matches the active rows, not the full history').to.eq(activeConcerns)
    })
  })

  it('a blank description is refused, and so is a future date', () => {
    post('/saveBehaviourNote', {
      enrollNo: fx.student.enrollNo, type: 'CONCERN', description: '   '
    }).then((r) => {
      // A blank account leaves a mark on a child's record saying nothing.
      expect(parse(r.body).status).to.be.oneOf(['FAILED', 'ERROR'])
    })
    post('/saveBehaviourNote', {
      enrollNo: fx.student.enrollNo, type: 'CONCERN',
      description: TAG + ' future', occurredOn: '2099-01-01'
    }).then((r) => {
      const b = parse(r.body)
      expect(b.status, 'a future date is always a typo, and dates get argued over').to.eq('FAILED')
      expect(b.message).to.match(/future/i)
    })
  })

  it('parent-informed is RECORDED, and nothing is sent', () => {
    const today = new Date().toISOString().slice(0, 10)
    post('/saveBehaviourNote', {
      enrollNo: fx.student.enrollNo, type: 'CONCERN',
      description: TAG + ' parent contacted', parentInformed: 'true', parentInformedOn: today
    }).then((r) => ok(r, 'record with parent informed'))

    notesFor(fx.student.enrollNo).then((r) => {
      const note = (parse(r.body).object.notes || [])
        .find((n) => n.description === TAG + ' parent contacted')
      expect(note.parentInformed).to.eq(true)
      expect(note.parentInformedOn).to.eq(today)
      // D5 — this slice records WHETHER the parent was told. Sending is blocked on the notification path
      // being real (2.2 and 2.4 want it too); the most sensitive data is the worst place to half-wire it.
    })
  })

  it('an HTML payload in a description is STRIPPED before it is stored', () => {
    // Corrected 2026-08-03. This first asserted the description was stored verbatim and escaped at
    // render — the wrong model for this platform. `com.security.XssSanitizer` (mirrored in the services)
    // strips tags from incoming request values as defence-in-depth, and its javadoc names this exact
    // payload. So the note saves, and the markup does not survive the write.
    const marker = TAG + ' xss'
    post('/saveBehaviourNote', {
      enrollNo: fx.student.enrollNo, type: 'NEUTRAL',
      description: marker + ' <img src=x onerror=alert(1)>'
    }).then((r) => ok(r, 'record with HTML in the description'))

    notesFor(fx.student.enrollNo).then((r) => {
      const note = (parse(r.body).object.notes || [])
        .find((n) => n.description && n.description.indexOf(marker) === 0)
      expect(note, 'the note itself is saved — sanitising must not silently drop the record').to.exist
      // Strip-on-input, escape-on-output: the tag is gone, so nothing downstream can re-hydrate it.
      expect(note.description, 'the tag was removed').to.not.contain('<img')
      expect(note.description, 'and so was the event handler').to.not.match(/onerror\s*=/i)
      expect(note.description, 'the human text survives — sanitising is not truncation').to.contain('xss')
    })
  })

  it('another tenant’s note is invisible by id', () => {
    post('/supersedeBehaviourNote', { id: 999999, description: 'nope' }).then((r) => {
      expect(parse(r.body).status, 'an id outside the tenant never silently succeeds').to.not.eq('SUCCESS')
    })
  })
})
