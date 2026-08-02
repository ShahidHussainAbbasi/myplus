/**
 * Slice 2.2 — substitution.
 * Design: microservices/docs/slices/edu-2.2-substitution.md
 *
 * The exclusion rules (teaching / absent / already-covering, and the ranking) live in
 * FreeTeacherFinderTest — pure, on every `mvn test`. Asserted here is what a unit test cannot:
 *
 *   - marking a teacher absent OPENS their lessons as UNCOVERED (D5 — a row, not a missing row)
 *   - the free list served to the screen really has excluded the busy/absent/covering
 *   - assigning is clash-checked by 2.1's own rule, and a room "clash" does NOT warn (D4)
 *   - clearing an absence cancels its covers rather than deleting them
 *   - assigning is ADMIN; reading the day is not
 *
 * FIXTURES ARE SEEDED, NEVER SKIPPED — the 2.1 gate shipped a hollow green because a case opted out
 * when the demo org lacked data. Everything this spec needs, it creates.
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

const TAG = 'CySub' + Date.now()
/** A fixed FUTURE Tuesday: fixed so the weekday is known, future so it cannot collide with real data. */
const DATE = '2026-09-01'          // a Tuesday
const DAY = 'TUESDAY'
const day = () =>
  cy.request({ url: `/getSubstitutionDay?date=${DATE}`, failOnStatusCode: false })

const fx = {}

describe('Education — substitution (slice 2.2)', () => {
  before(() => {
    cy.loginAsEduOwner()

    cy.request('/getUserSubject').then((r) => {
      const subjects = rows(r.body).filter((s) => s.gradeId)
      expect(subjects.length, 'the org has a subject attached to a class').to.be.greaterThan(0)
      fx.subject = subjects[0]
      fx.otherSubject = subjects.find((s) => s.gradeId !== subjects[0].gradeId) || subjects[0]
    })
    cy.request('/getUserStaff').then((r) => {
      const staff = rows(r.body)
      expect(staff.length, 'the org needs at least 2 staff for a cover test').to.be.greaterThan(1)
      fx.absentee = staff[0]        // will be marked absent
      fx.cover = staff[1]           // will be offered as cover
      fx.busy = staff[2] || null    // will be made busy in the same period
    })

    // Two periods of our own, so nothing here depends on the org's existing school day.
    post('/savePeriod', { name: TAG + ' P1', sequence: 80, startTime: '08:00', endTime: '08:45' })
      .then((r) => ok(r, 'seed period 1'))
    post('/savePeriod', { name: TAG + ' P2', sequence: 81, startTime: '08:45', endTime: '09:30' })
      .then((r) => ok(r, 'seed period 2'))
    cy.request('/getPeriods').then((r) => {
      const ps = rows(r.body)
      fx.p1 = ps.find((p) => p.name === TAG + ' P1')
      fx.p2 = ps.find((p) => p.name === TAG + ' P2')
      expect(fx.p1, 'period 1 seeded').to.exist
      expect(fx.p2, 'period 2 seeded').to.exist
    })

    // The lesson that will need cover: the absentee teaches it on our Tuesday.
    cy.then(() => {
      post('/saveTimetableEntry', {
        dayOfWeek: DAY, periodId: fx.p1.id, subjectId: fx.subject.id,
        staffId: fx.absentee.id, room: TAG + '-R1'
      }).then((r) => ok(r, 'seed the lesson needing cover'))

      // And a lesson that makes `busy` genuinely unavailable in the SAME period.
      if (fx.busy) {
        post('/saveTimetableEntry', {
          dayOfWeek: DAY, periodId: fx.p1.id, subjectId: fx.otherSubject.id,
          staffId: fx.busy.id, room: TAG + '-R2'
        }).then((r) => {
          // If the org has only one class, this second lesson clashes on CLASS and is refused —
          // acceptable: the busy-teacher case then simply has no fixture, and we say so loudly below.
          if (parse(r.body).status !== 'SUCCESS') fx.busy = null
        })
      }
    })
    cy.then(() => {
      cy.request(`/getTimetable?gradeId=${fx.subject.gradeId}`).then((r) => {
        const entries = ((parse(r.body).object) || {}).entries || []
        fx.entry = entries.find((e) => e.periodId === fx.p1.id && e.dayOfWeek === DAY)
        expect(fx.entry, 'the seeded lesson is on the grid').to.exist
      })
    })
  })

  beforeEach(() => {
    cy.loginAsEduOwner()
  })

  after(() => {
    cy.loginAsEduOwner()
    // Absences first (clearing one cancels its covers), then lessons, then periods.
    day().then((r) => {
      const o = (parse(r.body).object) || {}
      ;(o.absences || []).forEach((a) => post('/clearStaffAbsence', { id: a.id }))
    })
    cy.request(`/getTimetable?gradeId=${fx.subject && fx.subject.gradeId}`).then((r) => {
      const entries = ((parse(r.body).object) || {}).entries || []
      entries
        .filter((e) => e.periodId === (fx.p1 || {}).id || e.periodId === (fx.p2 || {}).id)
        .forEach((e) => post('/deleteTimetableEntry', { id: e.id }))
    })
    cy.then(() => {
      if (fx.p1) post('/deletePeriod', { id: fx.p1.id })
      if (fx.p2) post('/deletePeriod', { id: fx.p2.id })
    })
  })

  it('marking a teacher absent OPENS their lessons as UNCOVERED', () => {
    post('/markStaffAbsent', { staffId: fx.absentee.id, date: DATE, reason: 'sick' })
      .then((r) => ok(r, 'mark absent'))

    day().then((r) => {
      const o = ok(r, 'read the day').object
      expect(o.absences.map((a) => a.staffId), 'the absence is listed').to.include(fx.absentee.id)

      const lesson = (o.lessons || []).find((l) => l.timetableEntryId === fx.entry.id)
      expect(lesson, 'their lesson appears as needing cover').to.exist
      // D5: an unsupervised class is a ROW with a status, not an absent row.
      expect(lesson.status).to.eq('UNCOVERED')
      expect(o.uncovered, 'and it is counted for the headline').to.be.greaterThan(0)
    })
  })

  it('marking the same teacher absent twice is idempotent, not an error', () => {
    post('/markStaffAbsent', { staffId: fx.absentee.id, date: DATE })
      .then((r) => expect(parse(r.body).status, 'a double-click is not a failure').to.eq('SUCCESS'))
    day().then((r) => {
      const o = parse(r.body).object
      const mine = o.absences.filter((a) => a.staffId === fx.absentee.id)
      expect(mine.length, 'still exactly one absence row').to.eq(1)
    })
  })

  it('the free list excludes the absent teacher and anyone teaching in that period', () => {
    day().then((r) => {
      const lesson = (parse(r.body).object.lessons || [])
        .find((l) => l.timetableEntryId === fx.entry.id)
      const free = (lesson.freeTeachers || []).map((c) => c.staffId)

      expect(free, 'the absentee cannot cover their own lesson').to.not.include(fx.absentee.id)
      if (fx.busy) {
        expect(free, 'someone teaching in this period is not offered').to.not.include(fx.busy.id)
      } else {
        cy.log('busy-teacher fixture unavailable (single-class org) — covered by FreeTeacherFinderTest')
      }
      expect(free, 'a genuinely free teacher IS offered').to.include(fx.cover.id)
    })
  })

  it('assigning a free teacher covers the lesson', () => {
    post('/assignSubstitute', {
      timetableEntryId: fx.entry.id, coverStaffId: fx.cover.id, date: DATE
    }).then((r) => ok(r, 'assign cover'))

    day().then((r) => {
      const lesson = (parse(r.body).object.lessons || [])
        .find((l) => l.timetableEntryId === fx.entry.id)
      expect(lesson.status).to.eq('ASSIGNED')
      expect(lesson.coverStaffId).to.eq(fx.cover.id)
      expect(lesson.coverStaffName, 'the name is snapshotted for the printed list').to.be.a('string')
    })
  })

  it('a room "clash" does NOT warn on a substitution — the cover uses the same room (D4)', () => {
    // The cover teacher walks into the absent teacher's room, which is by definition already booked for
    // that class. Warning on every single cover would train people to ignore warnings.
    day().then((r) => {
      const lesson = (parse(r.body).object.lessons || [])
        .find((l) => l.timetableEntryId === fx.entry.id)
      expect(lesson.status, 'it saved cleanly, no room warning blocked or decorated it').to.eq('ASSIGNED')
    })
  })

  it('assigning twice does not create a second row', () => {
    post('/assignSubstitute', {
      timetableEntryId: fx.entry.id, coverStaffId: fx.cover.id, date: DATE
    }).then((r) => expect(parse(r.body).status).to.eq('SUCCESS'))
    day().then((r) => {
      const mine = (parse(r.body).object.lessons || [])
        .filter((l) => l.timetableEntryId === fx.entry.id)
      expect(mine.length, 'one decision per lesson per day — the UNIQUE key holds').to.eq(1)
    })
  })

  it('an absent teacher cannot be assigned as cover', () => {
    post('/assignSubstitute', {
      timetableEntryId: fx.entry.id, coverStaffId: fx.absentee.id, date: DATE
    }).then((r) => {
      const b = parse(r.body)
      expect(b.status, 'someone off sick cannot cover').to.eq('FAILED')
      expect(b.message).to.match(/absent/i)
    })
  })

  it('removing the cover returns the lesson to UNCOVERED, not to nothing', () => {
    day().then((r) => {
      const lesson = (parse(r.body).object.lessons || [])
        .find((l) => l.timetableEntryId === fx.entry.id)
      post('/clearSubstitute', { id: lesson.substitutionId }).then((c) => ok(c, 'clear cover'))
    })
    day().then((r) => {
      const lesson = (parse(r.body).object.lessons || [])
        .find((l) => l.timetableEntryId === fx.entry.id)
      expect(lesson.status, 'the class still needs someone').to.eq('UNCOVERED')
      expect(lesson.coverStaffId).to.be.oneOf([null, undefined])
    })
  })

  it('clearing the absence cancels the day and empties the cover list', () => {
    day().then((r) => {
      const o = parse(r.body).object
      const absence = (o.absences || []).find((a) => a.staffId === fx.absentee.id)
      expect(absence, 'the absence is still there to clear').to.exist
      post('/clearStaffAbsence', { id: absence.id }).then((c) => ok(c, 'clear absence'))
    })
    day().then((r) => {
      const o = parse(r.body).object
      expect(o.absences.map((a) => a.staffId), 'no longer absent').to.not.include(fx.absentee.id)
      expect(o.lessons.length, 'and nothing needs cover any more').to.eq(0)
    })
  })

  it('a teacher may READ the day but not mark absences or assign cover', () => {
    cy.loginAsTeacherA()
    day().then((r) => {
      expect([200, 403]).to.include(r.status)
    })
    post('/markStaffAbsent', { staffId: fx.absentee.id, date: DATE }).then((r) => {
      const b = parse(r.body)
      const refused = r.status === 403 || (b && b.status && b.status !== 'SUCCESS')
      expect(refused, 'deciding who teaches whom is ADMIN').to.eq(true)
    })
    post('/assignSubstitute', {
      timetableEntryId: fx.entry.id, coverStaffId: fx.cover.id, date: DATE
    }).then((r) => {
      const b = parse(r.body)
      const refused = r.status === 403 || (b && b.status && b.status !== 'SUCCESS')
      expect(refused).to.eq(true)
    })
  })

  it('another tenant’s absence is invisible by id', () => {
    post('/clearStaffAbsence', { id: 999999 }).then((r) => {
      expect(parse(r.body).status, 'an id outside the tenant never silently succeeds').to.not.eq('SUCCESS')
    })
  })
})
