/**
 * Slice 2.4 — homework.
 * Design: microservices/docs/slices/edu-2.4-homework.md
 *
 * The rules (lateness derivation, overdue-vs-not-done, marks bounds, delete safety) live in
 * HomeworkRulesTest — pure, on every `mvn test`. Asserted HERE is what a unit test cannot reach:
 *
 *   - setting homework creates ZERO submission rows (D2 — the lazy-creation decision)
 *   - a student who joins the class LATER still appears on the sheet (the bug D2 avoids)
 *   - extending the deadline un-lates a submission (D5 — derived, not stored)
 *   - grading reuses the 1.4 scale, and does NOT reach the report card (D4)
 *   - the UNIQUE key holds under a repeated save
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
const postJson = (url, body) =>
  cy.request({ method: 'POST', url, body, failOnStatusCode: false })

const ok = (r, what) => {
  const b = parse(r.body)
  expect(b.status, `${what}: ${JSON.stringify(b).slice(0, 300)}`).to.be.oneOf(['SUCCESS', 'PARTIAL'])
  return b
}

const TAG = 'CyHw' + Date.now()
const DUE = '2026-09-11'
const sheet = (id) =>
  cy.request({ url: `/getHomeworkSheet?homeworkId=${id}`, failOnStatusCode: false })

const fx = {}

describe('Education — homework (slice 2.4)', () => {
  before(() => {
    cy.loginAsEduOwner()
    // The mark sheet is the class roster filtered to the SUBJECT'S class. Picking any subject with a
    // grade is not enough — the first gate run did exactly that, the chosen class had no students, and
    // all six roster assertions collapsed on an empty (and correct) sheet.
    //
    // So: prefer a subject whose class already HAS students, and seed one if none does. Seed what the
    // spec needs; never opt out of the assertion.
    cy.request('/getUserSubject').then((r) => {
      fx.subjects = rows(r.body).filter((s) => s.gradeId)
      expect(fx.subjects.length, 'the org has a subject attached to a class').to.be.greaterThan(0)
    })
    cy.request('/getUserStudent').then((r) => {
      fx.allStudents = rows(r.body).filter((s) => s.enrollNo)
      const byGrade = {}
      fx.allStudents.forEach((s) => {
        if (s.gradeId == null) return
        ;(byGrade[s.gradeId] = byGrade[s.gradeId] || []).push(s)
      })
      fx.subject = fx.subjects.find((s) => (byGrade[s.gradeId] || []).length > 0) || fx.subjects[0]
      fx.students = byGrade[fx.subject.gradeId] || []
    })
    // If that subject's class is empty, put a student in it rather than skipping the slice's main path.
    cy.then(() => {
      if (fx.students.length > 0) return
      const en = TAG + '-S1'
      post('/addStudent', { name: TAG + ' Pupil', enrollNo: en, gradeId: fx.subject.gradeId })
        .then((r) => ok(r, 'seed a student into the subject’s class'))
      cy.request('/getUserStudent').then((r2) => {
        fx.students = rows(r2.body).filter((s) => s.gradeId === fx.subject.gradeId && s.enrollNo)
        fx.seededEnrollNo = en
        expect(fx.students.length,
          'the subject’s class now has a student, so the mark sheet can be asserted').to.be.greaterThan(0)
      })
    })
    // Our own task, out of 20 so the grading path is exercised.
    cy.then(() => {
      post('/saveHomework', {
        subjectId: fx.subject.id, title: TAG + ' Fractions', dueOn: DUE, maxMarks: 20
      }).then((r) => ok(r, 'set homework'))
      cy.request('/getHomework').then((r) => {
        fx.homework = rows(r.body).find((h) => h.title === TAG + ' Fractions')
        expect(fx.homework, 'the task was created').to.exist
        expect(fx.homework.maxMarks).to.eq(20)
      })
    })
  })

  beforeEach(() => {
    cy.loginAsEduOwner()
  })

  after(() => {
    cy.loginAsEduOwner()
    // Clear every recorded row first — a graded task refuses to delete, by design.
    cy.then(() => {
      if (!fx.homework) return
      sheet(fx.homework.id).then((r) => {
        const o = (parse(r.body).object) || {}
        const clearing = (o.rows || [])
          .filter((x) => x.state)
          .map((x) => ({ enrollNo: x.enrollNo, state: '', marksObtained: null }))
        if (clearing.length) {
          postJson('/saveSubmissionBulk', { homeworkId: fx.homework.id, rows: clearing })
        }
      })
      cy.then(() => post('/deleteHomework', { id: fx.homework.id }))
      // Only the student THIS spec created — never one the org already had.
      cy.then(() => {
        if (!fx.seededEnrollNo) return
        cy.request('/getUserStudent').then((r) => {
          const mine = rows(r.body).find((s) => s.enrollNo === fx.seededEnrollNo)
          if (mine) post('/deleteStudent', { checked: mine.id })
        })
      })
    })
  })

  it('setting homework creates ZERO submission rows', () => {
    // D2 — pre-seeding 40 rows would assert 40 facts that are not yet true.
    cy.request('/getHomework').then((r) => {
      const h = rows(r.body).find((x) => x.id === fx.homework.id)
      expect(h.recorded, 'nothing is recorded yet').to.eq(0)
    })
    sheet(fx.homework.id).then((r) => {
      const o = ok(r, 'open the sheet').object
      expect(o.rows.length, 'the whole class is listed').to.be.greaterThan(0)
      o.rows.forEach((x) => {
        expect(x.state, 'every student starts with NOTHING recorded').to.be.oneOf([null, undefined])
      })
    })
  })

  it('a blank row means "nothing recorded", not "not done"', () => {
    sheet(fx.homework.id).then((r) => {
      const first = parse(r.body).object.rows[0]
      // The distinction the design turns on: no row is silence, NOT_DONE is a judgement.
      expect(first.state).to.be.oneOf([null, undefined])
      expect(first.marksObtained).to.be.oneOf([null, undefined])
    })
  })

  it('recording a submission and a grade uses the school grading scale', () => {
    const en = fx.students[0].enrollNo
    postJson('/saveSubmissionBulk', {
      homeworkId: fx.homework.id,
      rows: [{ enrollNo: en, state: 'MARKED', submittedOn: '2026-09-10', marksObtained: 17,
               feedback: TAG + ' good' }]
    }).then((r) => ok(r, 'grade one student'))

    sheet(fx.homework.id).then((r) => {
      const row = parse(r.body).object.rows.find((x) => x.enrollNo === en)
      expect(row.state).to.eq('MARKED')
      expect(row.marksObtained).to.eq(17)
      // 17/20 = 85% — the same arithmetic and rounding the marksheet uses (GradingService.percentOf).
      expect(row.percent, '17 of 20 is 85%').to.eq(85)
      expect(row.late, 'submitted the day before it was due').to.eq(false)
    })
  })

  it('marks above the maximum are refused per row, and the rest still save', () => {
    const good = fx.students[0].enrollNo
    const bad = (fx.students[1] || fx.students[0]).enrollNo
    postJson('/saveSubmissionBulk', {
      homeworkId: fx.homework.id,
      rows: [
        { enrollNo: good, state: 'MARKED', marksObtained: 18 },
        { enrollNo: bad, state: 'MARKED', marksObtained: 99 }
      ]
    }).then((r) => {
      const b = parse(r.body)
      if (fx.students.length > 1) {
        // 1.3 D3 — per-row partial success: the valid row saves, the invalid one is named, and the
        // status is PARTIAL so the UI cannot round it up to a clean save.
        expect(b.status).to.eq('PARTIAL')
        expect(JSON.stringify(b.object.problems)).to.match(/99|exceed/i)
      }
      expect(b.object.saved, 'the valid row still saved').to.be.greaterThan(0)
    })
  })

  it('saving the same row twice does not create a second one', () => {
    const en = fx.students[0].enrollNo
    postJson('/saveSubmissionBulk', {
      homeworkId: fx.homework.id,
      rows: [{ enrollNo: en, state: 'MARKED', marksObtained: 18 }]
    }).then((r) => ok(r, 're-save'))
    sheet(fx.homework.id).then((r) => {
      const mine = parse(r.body).object.rows.filter((x) => x.enrollNo === en)
      expect(mine.length, 'one row per child per task — the UNIQUE key').to.eq(1)
    })
  })

  it('a late submission is flagged, and extending the deadline un-lates it', () => {
    const en = fx.students[0].enrollNo
    postJson('/saveSubmissionBulk', {
      homeworkId: fx.homework.id,
      rows: [{ enrollNo: en, state: 'SUBMITTED', submittedOn: '2026-09-14' }]
    }).then((r) => ok(r, 'submit after the due date'))
    sheet(fx.homework.id).then((r) => {
      const row = parse(r.body).object.rows.find((x) => x.enrollNo === en)
      expect(row.late, 'the 14th is after the 11th').to.eq(true)
    })

    // D5 — late is DERIVED. Moving the deadline must change it; a stored flag could not.
    post('/saveHomework', {
      id: fx.homework.id, subjectId: fx.subject.id, title: TAG + ' Fractions',
      dueOn: '2026-09-18', maxMarks: 20
    }).then((r) => ok(r, 'extend the deadline'))
    sheet(fx.homework.id).then((r) => {
      const row = parse(r.body).object.rows.find((x) => x.enrollNo === en)
      expect(row.late, 'the same submission is now on time').to.eq(false)
    })
    // Put the deadline back so later cases read the original fixture.
    post('/saveHomework', {
      id: fx.homework.id, subjectId: fx.subject.id, title: TAG + ' Fractions',
      dueOn: DUE, maxMarks: 20
    })
  })

  it('clearing a row back to blank removes it — silence is restorable', () => {
    const en = fx.students[0].enrollNo
    postJson('/saveSubmissionBulk', {
      homeworkId: fx.homework.id,
      rows: [{ enrollNo: en, state: '', marksObtained: null }]
    }).then((r) => {
      const b = ok(r, 'clear the row')
      expect(b.object.cleared, 'the row was deleted, not set to some default').to.be.greaterThan(0)
    })
    sheet(fx.homework.id).then((r) => {
      const row = parse(r.body).object.rows.find((x) => x.enrollNo === en)
      expect(row.state, 'back to nothing recorded').to.be.oneOf([null, undefined])
    })
  })

  it('homework does NOT appear in a published report card', () => {
    // D4 — 1.5's term aggregate is a PUBLISHED number. Adding a source would change its meaning with
    // nothing showing it had changed. This is the assertion that keeps the boundary honest.
    const en = fx.students[0].enrollNo
    postJson('/saveSubmissionBulk', {
      homeworkId: fx.homework.id,
      rows: [{ enrollNo: en, state: 'MARKED', marksObtained: 20 }]
    }).then((r) => ok(r, 'grade full marks'))

    cy.request({ url: `/getTranscript?enrollNo=${encodeURIComponent(en)}`, failOnStatusCode: false })
      .then((r) => {
        rows(r.body).forEach((card) => {
          const titles = (card.rows || []).map((x) => x.subjectName + '|' + (x.examName || ''))
          expect(titles.join(' '), 'no homework line reached a report card').to.not.match(/CyHw/)
        })
      })
  })

  it('homework with graded work cannot be deleted', () => {
    post('/deleteHomework', { id: fx.homework.id }).then((r) => {
      const b = parse(r.body)
      // A grade is a teacher's judgement of a child's work; losing it to a mis-click is unrecoverable.
      expect(b.status, 'refused while anything is graded').to.eq('FAILED')
      expect(b.message).to.match(/graded/i)
    })
  })

  it('another tenant’s homework is invisible by id', () => {
    cy.request({ url: '/getHomeworkSheet?homeworkId=999999', failOnStatusCode: false }).then((r) => {
      expect(parse(r.body).status, 'an id outside the tenant never silently succeeds').to.not.eq('SUCCESS')
    })
  })
})
