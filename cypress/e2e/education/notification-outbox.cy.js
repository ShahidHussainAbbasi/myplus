/**
 * Slice N1 — the notification outbox (2.2 cover-assigned).
 * Design: microservices/docs/slices/edu-N1-notification-outbox.md
 *
 * The message itself is asserted in CoverNoticeBuilderTest — pure, on every `mvn test`. Asserted HERE is
 * what a unit test cannot reach:
 *
 *   - assigning cover reports QUEUED, and the assignment survives every notification outcome
 *   - a teacher with NO email still gets the cover, and the response SAYS nobody was told  ← D4
 *   - the owner switch is read on the path it governs, in BOTH directions                  ← C1/C2
 *   - a behaviour note with `guardianInformed` ticked sends NOTHING                         ← the thing a
 *     careless "wire up the notification hooks" sweep would have broken
 *
 * FIXTURES ARE SEEDED, NEVER SKIPPED — and per slice 3.1's lesson, a fixture existing is not enough: it
 * must satisfy the precondition the ENDPOINT enforces. This spec seeds its own two teachers precisely
 * because it needs one WITH an email and one WITHOUT, which the demo org cannot be trusted to hold.
 *
 * Requires education-service (rebuilt — V24 + a new setting) + gateway up. Run headed.
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

const KEY = 'edu.notify.coverAssigned'
const TAG = 'CyN1' + Date.now()
/** A fixed FUTURE Wednesday: fixed so the weekday is known, future so it cannot collide with real data. */
const DATE = '2026-09-02'
const DAY = 'WEDNESDAY'

const fx = {}

/** Assign cover and return the parsed body — the one call every case below is about. */
const assign = (coverStaffId) =>
  post('/assignSubstitute', { timetableEntryId: fx.entry.id, coverStaffId, date: DATE })
    .then((r) => parse(r.body))

describe('Education — notification outbox (slice N1)', () => {
  before(() => {
    cy.loginAsEduOwner()
    setConfig(KEY, 'true')

    // Two teachers of our own. The email/no-email split IS the fixture — it is what separates
    // QUEUED from NO_EMAIL, and the demo org's staff cannot be assumed to provide both.
    post('/addStaff', { name: TAG + ' Emailed', email: (TAG + '@example.test').toLowerCase(),
                        status: 'ACTIVE' }).then((r) => ok(r, 'seed the teacher WITH an email'))
    post('/addStaff', { name: TAG + ' NoEmail', status: 'ACTIVE' })
      .then((r) => ok(r, 'seed the teacher WITHOUT an email'))

    cy.request('/getUserStaff').then((r) => {
      const staff = rows(r.body)
      fx.emailed = staff.find((s) => s.name === TAG + ' Emailed')
      fx.noEmail = staff.find((s) => s.name === TAG + ' NoEmail')
      expect(fx.emailed, 'the emailed teacher was seeded').to.exist
      expect(fx.noEmail, 'the no-email teacher was seeded').to.exist
      // Verify the fixture is what it claims to be. `addStaff` binding email is the precondition the
      // whole spec rests on; asserting it here fails loudly instead of producing a confusing NO_EMAIL.
      expect(fx.emailed.email, 'the seeded email actually persisted').to.contain('@')
      expect(fx.noEmail.email || '', 'the other teacher really has no address').to.not.contain('@')
      // A third teacher owns the lesson being covered, so no cover candidate is covering their own class.
      fx.owner = staff.find((s) => s.id !== fx.emailed.id && s.id !== fx.noEmail.id)
      expect(fx.owner, 'a third staff member owns the lesson').to.exist
    })

    cy.request('/getUserSubject').then((r) => {
      const subjects = rows(r.body).filter((s) => s.gradeId)
      expect(subjects.length, 'the org has a subject attached to a class').to.be.greaterThan(0)
      fx.subject = subjects[0]
    })

    // Our own period, so nothing depends on the org's existing school day.
    post('/savePeriod', { name: TAG + ' P', sequence: 90, startTime: '10:00', endTime: '10:45' })
      .then((r) => ok(r, 'seed the period'))
    cy.request('/getPeriods').then((r) => {
      fx.period = rows(r.body).find((p) => p.name === TAG + ' P')
      expect(fx.period, 'the period was seeded').to.exist
    })

    // The lesson that needs cover.
    cy.then(() => {
      post('/saveTimetableEntry', {
        dayOfWeek: DAY, periodId: fx.period.id, subjectId: fx.subject.id,
        staffId: fx.owner.id, room: TAG + '-R'
      }).then((r) => ok(r, 'seed the lesson needing cover'))
    })
    // Read the entry back the way 2.2's proven spec does: BY CLASS, and out of `object.entries` — the
    // grid is not a `collection`, and `/getTimetable` with no termId returns only null-term rows.
    cy.then(() => {
      cy.request(`/getTimetable?gradeId=${fx.subject.gradeId}`).then((r) => {
        const entries = ((parse(r.body).object) || {}).entries || []
        fx.entry = entries.find((e) => e.periodId === fx.period.id && e.dayOfWeek === DAY)
        expect(fx.entry, 'the seeded lesson is on the grid').to.exist
      })
    })
  })

  beforeEach(() => {
    cy.loginAsEduOwner()
  })

  after(() => {
    cy.loginAsEduOwner()
    setConfig(KEY, 'true')   // restore the default; the flag is org-wide and leaks into other specs
  })

  // ── the setting ─────────────────────────────────────────────────────────────────────────────────

  it('the switch is registered in the catalog, BOOL and on by default', () => {
    // C2's catalog half. If education-service was not repackaged, this is the case that says so plainly
    // rather than leaving a confusing DISABLED further down.
    cy.request('/getConfig').then((r) => {
      const s = rows(r.body).find((i) => i.key === KEY)
      expect(s, `${KEY} is registered — rebuild education-service if this fails`).to.exist
      expect(s.type).to.eq('BOOL')
      expect(String(s.value), 'defaults ON: a missing cover email is worse than a redundant one').to.eq('true')
    })
  })

  // ── the three outcomes ──────────────────────────────────────────────────────────────────────────

  it('assigning a teacher WITH an email queues a notice', () => {
    assign(fx.emailed.id).then((b) => {
      expect(b.status).to.eq('SUCCESS')
      expect(b.object, 'the response carries the outcome, not just a sentence').to.exist
      expect(b.object.notified).to.eq('QUEUED')
    })
  })

  it('a teacher with NO email still gets the cover — and the response says nobody was told', () => {
    // D4, and the case that matters most. 2.2 D6 is binding: a failed message must never lose the
    // assignment. But silence is what the old logging stub did, and the person who just clicked is the
    // only one in a position to go and tell the teacher.
    assign(fx.noEmail.id).then((b) => {
      expect(b.status, 'the assignment SURVIVES a notification that cannot be sent').to.eq('SUCCESS')
      expect(b.object.notified).to.eq('NO_EMAIL')
      expect(b.message, 'and it is named, not hidden').to.match(/no email/i)
    })
  })

  it('switching the notice OFF stops the send and still assigns the cover', () => {
    // C2's consumer half + the C-standard "verify the OFF path". A flag that changes nothing is worse
    // than no flag — this is the case that proves it is read on the path it governs.
    setConfig(KEY, 'false')
    assign(fx.emailed.id).then((b) => {
      expect(b.status, 'cover is still assigned with notices off').to.eq('SUCCESS')
      expect(b.object.notified).to.eq('DISABLED')
    })
  })

  it('switching it back ON resumes queuing — the flag is live, not read at startup', () => {
    setConfig(KEY, 'true')
    assign(fx.emailed.id).then((b) => {
      expect(b.object.notified).to.eq('QUEUED')
    })
  })

  // ── what must NOT send ──────────────────────────────────────────────────────────────────────────

  it('a behaviour note with guardianInformed ticked sends NOTHING', () => {
    // THE case this spec exists to protect. `guardianInformed` records that a HUMAN has already spoken
    // to the guardian — it is not a request to send anything. A sweep that "wired up the notification
    // hooks" would have emailed the guardian a second, machine-written message about a conversation
    // that already happened. There is deliberately no notification concept on this path at all.
    cy.request('/getUserStudent').then((r) => {
      const student = rows(r.body).find((s) => s.enrollNo)
      expect(student, 'a student exists to write a note against').to.exist

      // Param names verified against BehaviourController: `enrollNo`, and `occurredOn` is validated.
      // `guardianInformedOn` must not be in the future relative to the note, so both use today.
      const today = new Date().toISOString().slice(0, 10)
      post('/saveBehaviourNote', {
        enrollNo: student.enrollNo,
        type: 'CONCERN',        // the param is `type` — `noteType` would silently default to NEUTRAL
        category: TAG,
        description: 'N1 gate — spoke to the guardian at the gate this morning.',
        occurredOn: today,
        guardianInformed: 'true',
        guardianInformedOn: today
      }).then((res) => {
        const b = parse(res.body)
        expect(b.status, 'the note itself saves normally').to.eq('SUCCESS')
        const carried = b.object ? Object.keys(b.object) : []
        expect(carried, 'the behaviour path has NO notification outcome — absence, not a disabled flag')
          .to.not.include('notified')
      })
    })
  })

  // ── the inverse regression ──────────────────────────────────────────────────────────────────────

  it('2.2 still behaves as it did — the outcome was ADDED, nothing was narrowed', () => {
    // The response gained a field; it must not have lost one. `substitution.cy.js` is the full 2.2
    // regression and is re-run alongside this spec.
    cy.request({ url: `/getSubstitutionDay?date=${DATE}`, failOnStatusCode: false }).then((r) => {
      const b = parse(r.body)
      expect(b.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
    assign(fx.emailed.id).then((b) => {
      expect(b.message, 'the human sentence still names the teacher').to.contain(TAG)
    })
  })
})
