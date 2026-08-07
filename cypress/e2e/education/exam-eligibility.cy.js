/**
 * Exam eligibility by attendance — `edu.exam.minAttendancePercent` FINALLY WIRED (2026-08-07).
 *
 * The setting was registered in slice 1.6 and read by NOTHING for five slices (1.5 → 1.6 → 3.1 → N1 → 3.5),
 * a live standard-C1 violation in a shipped catalog: a flag nobody consumes is decorative. Its own catalog
 * text always named the consumer — "flagged as ineligible on the marksheet and the report card" — and 1.5
 * had already built the attendance aggregate it was waiting for.
 *
 * This gate asserts BOTH halves (standard C2): the catalog entry exists AND the marksheet acts on it, in
 * both directions of the switch. A setting proven only in the catalog is exactly how it stayed unwired.
 *
 *   - 0 (the default) shows NO eligibility at all — a school with no policy sees no judgement
 *   - above 0, a student below the line is flagged, and one above it is not
 *   - **a student with NO attendance recorded is NOT flagged** — unknown is not zero
 *   - it is a FLAG, never a block: the marks still save for an ineligible student
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

const sheet = (paperId) =>
  cy.request({ url: `/getMarksSheet?examPaperId=${paperId}`, failOnStatusCode: false })
    .then((r) => {
      const b = parse(r.body)
      expect(b.status, `getMarksSheet: ${JSON.stringify(b).slice(0, 250)}`).to.eq('SUCCESS')
      return cy.wrap(b.object, { log: false })
    })

const rowFor = (obj, enrollNo) => (obj.rows || []).find((r) => r.enrollNo === enrollNo)

const S = Date.now()
const TAG = 'CyEL' + S
// The TERM window every attendance row below sits inside. The eligibility read measures the EXAM'S TERM,
// so marking attendance outside these dates would prove nothing about the rule.
const TERM_FROM = '01-08-2026'
const TERM_TO = '31-10-2026'
const MARK_DAYS = ['05-08-2026', '06-08-2026', '07-08-2026', '10-08-2026']
const fx = {}

describe('Education — exam eligibility by attendance (edu.exam.minAttendancePercent)', () => {
  before(() => {
    cy.loginAsEduOwner()
    setConfig('edu.exam.minAttendancePercent', '0')   // SET, never assumed — a prior run may have left it

    // A class of its own, so this spec's attendance cannot be diluted by other specs' students.
    post('/addGrade', { name: TAG + ' Class', fee: 0 }).then((r) => ok(r, 'seed a class'))
    // /getUserGrade is the JSON read; /getUserGrades returns <option> HTML for a dropdown. Scraping the
    // markup for an id is how a renamed element silently empties a fixture (standard D9 form 7).
    cy.request('/getUserGrade').then((r) => {
      const g = rows(r.body).find((x) => x.name === TAG + ' Class')
      expect(g, 'the seeded class exists').to.exist
      fx.gradeId = g.id
    })

    // A subject on that class — the paper derives its class through the subject (1.2 D2).
    cy.then(() => {
      post('/addSubject', { name: TAG + ' Subj', gradeId: fx.gradeId }).then((r) => ok(r, 'seed a subject'))
      // SINGULAR = JSON, PLURAL = <option> HTML. The same trap as getUserGrade/getUserGrades, and it cost
      // this spec a run: /getUserSubjects returned markup and the fixture died parsing it.
      cy.request('/getUserSubject').then((r) => {
        const s = rows(r.body).find((x) => x.name === TAG + ' Subj')
        expect(s, 'the subject exists').to.exist
        fx.subjectId = s.id
      })
    })

    // THREE students, each seeded to prove one branch of the rule:
    //   GOOD    — present every day        → eligible
    //   POOR    — absent every day         → NOT eligible
    //   UNKNOWN — never marked at all      → NOT flagged either way
    cy.then(() => {
      fx.good = TAG + 'G'
      fx.poor = TAG + 'P'
      fx.unknown = TAG + 'U'
      ;[fx.good, fx.poor, fx.unknown].forEach((en) => {
        post('/addStudent', { name: en, enrollNo: en, status: 'ACTIVE', gradeId: fx.gradeId })
          .then((r) => ok(r, `seed student ${en}`))
      })
    })

    // Attendance INSIDE the term window. Seeded, never assumed: the whole rule is arithmetic over these
    // rows, and a spec that hoped for existing attendance would measure another spec's data.
    cy.then(() => {
      MARK_DAYS.forEach((d) => {
        cy.request({
          method: 'POST',
          url: '/markAttendanceBulk',
          body: {
            gradeId: Number(fx.gradeId),
            dateStr: d,
            rows: [
              { enrollNo: fx.good, status: 'Present' },
              { enrollNo: fx.poor, status: 'Absent' },
              // `unknown` is deliberately NOT marked on any day.
            ],
          },
          failOnStatusCode: false,
        }).then((r) => expect(JSON.stringify(r.body), `mark ${d}`).to.match(/SUCCESS/))
      })
    })

    // A published exam whose term is exactly the window above.
    cy.then(() => {
      post('/addAcademicYear', { name: TAG + 'Y', startDateStr: TERM_FROM, endDateStr: '30-06-2027' })
        .then((r) => ok(r, 'seed a year'))
      cy.request('/getAcademicYears').then((r) => {
        const y = rows(r.body).find((x) => x.name === TAG + 'Y')
        expect(y, 'the year exists').to.exist
        post('/addTerm', {
          academicYearId: y.id, name: 'T1', sequence: 1, startDateStr: TERM_FROM, endDateStr: TERM_TO,
        }).then((tr) => ok(tr, 'seed a term'))
        cy.request('/getAcademicYears').then((r2) => {
          const y2 = rows(r2.body).find((x) => x.name === TAG + 'Y')
          fx.termId = y2.terms[0].id
        })
      })
    })

    cy.then(() => {
      post('/addExam', { name: TAG + 'X', termId: fx.termId, weightPercent: 100 })
        .then((r) => ok(r, 'seed an exam'))
      cy.request('/getExams').then((r) => {
        const e = rows(r.body).find((x) => x.name === TAG + 'X')
        expect(e, 'the exam exists').to.exist
        post('/addExamPaper', {
          examId: e.id, subjectId: fx.subjectId, maxMarks: 100, passMarks: 33, examDateStr: '20-10-2026',
        }).then((pr) => ok(pr, 'seed a paper'))
        post('/setExamStatus', { id: e.id, status: 'PUBLISHED' })
        cy.request('/getExams').then((r2) => {
          const e2 = rows(r2.body).find((x) => x.name === TAG + 'X')
          expect(e2.papers, 'the exam has its paper').to.have.length(1)
          fx.paperId = e2.papers[0].id
        })
      })
    })
  })

  after(() => {
    cy.loginAsEduOwner()
    // Restore the DEFAULT. Leaving a threshold behind would flag other specs' students as ineligible —
    // the fixture hazard `meetings.cy.js` was bitten by with edu.portal.enabled.
    setConfig('edu.exam.minAttendancePercent', '0')
  })

  // ── half one of C2: the catalog ────────────────────────────────────────────────────────────────

  it('the setting is in the catalog, as an INT defaulting to 0', () => {
    cy.loginAsEduOwner()
    cy.request('/getConfig').then((r) => {
      const all = rows(r.body)
      const s = all.find((x) => x.key === 'edu.exam.minAttendancePercent')
      expect(s, 'the setting is registered').to.exist
      expect(s.type, 'as a whole number — a percentage has no minor units').to.eq('INT')
    })
  })

  // ── half two of C2, and the half that was missing for five slices: the CONSUMER ────────────────

  it('OFF (0) — the marksheet carries NO eligibility at all', () => {
    // A school that has set no policy must not see its students judged. The absence of the field is the
    // assertion: a `false` here would render as "ineligible" on any screen that trusts the key's presence.
    cy.loginAsEduOwner()
    setConfig('edu.exam.minAttendancePercent', '0')
    sheet(fx.paperId).then((obj) => {
      expect(obj.minAttendancePercent, 'the threshold is reported as off').to.eq(0)
      const g = rowFor(obj, fx.good)
      expect(g, 'the good student is on the sheet').to.exist
      expect(g).to.not.have.property('attendanceEligible')
      expect(rowFor(obj, fx.poor)).to.not.have.property('attendanceEligible')
    })
  })

  it('ON (75%) — a student below the line is flagged, one above it is not', () => {
    cy.loginAsEduOwner()
    setConfig('edu.exam.minAttendancePercent', '75')
    sheet(fx.paperId).then((obj) => {
      expect(obj.minAttendancePercent, 'the threshold reaches the screen so it can explain the flag').to.eq(75)

      const g = rowFor(obj, fx.good)
      expect(g.attendancePercent, 'present every day').to.eq(100)
      expect(g.attendanceEligible, 'and therefore eligible').to.eq(true)

      const p = rowFor(obj, fx.poor)
      expect(p.attendancePercent, 'absent every day').to.eq(0)
      expect(p.attendanceEligible, 'and therefore NOT eligible — the whole point of the setting').to.eq(false)
    })
  })

  it('a student with NO attendance recorded is NOT flagged — unknown is not zero', () => {
    // THE JUDGEMENT CALL THIS CASE EXISTS TO PIN. Treating "never marked" as 0% would flag a child for a
    // register the school never filled in — blaming them for the school's gap. Absent from the map, not
    // absent from class.
    cy.loginAsEduOwner()
    setConfig('edu.exam.minAttendancePercent', '75')
    sheet(fx.paperId).then((obj) => {
      const u = rowFor(obj, fx.unknown)
      expect(u, 'the unmarked student is still on the sheet').to.exist
      expect(u, 'and carries no eligibility verdict either way').to.not.have.property('attendanceEligible')
    })
  })

  it('it is a FLAG, never a block — an ineligible student can still be marked', () => {
    // 1.5 established this and it has not changed: students are not registered for papers individually,
    // so there is nothing for the system to refuse. If this ever fails, the flag has quietly become a
    // gate and a school will find out at the worst moment.
    cy.loginAsEduOwner()
    setConfig('edu.exam.minAttendancePercent', '75')
    cy.request({
      method: 'POST',
      url: '/saveMarksBulk',
      body: { examPaperId: fx.paperId, rows: [{ enrollNo: fx.poor, marksObtained: 55, absent: false }] },
      failOnStatusCode: false,
    }).then((r) => {
      expect(JSON.stringify(r.body), 'the ineligible student\'s marks still save').to.match(/SUCCESS|PARTIAL/)
    })
    sheet(fx.paperId).then((obj) => {
      const p = rowFor(obj, fx.poor)
      expect(p.marksObtained, 'and are readable').to.eq(55)
      expect(p.attendanceEligible, 'while still being flagged').to.eq(false)
    })
  })
})
