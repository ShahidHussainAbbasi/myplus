/**
 * Slice 1.2 — examinations.
 * Design: microservices/docs/slices/edu-1.2-examinations.md
 *
 * Exams are what marks (1.3) get recorded against. This proves the definition works end to end: the
 * exam/paper split, the class derived from the subject, the term requirement, the lock that stops a
 * definition change from silently restating results, tenant isolation, and the ADMIN tier.
 *
 * The lock TRUTH TABLE lives in ExamLockGuardTest (pure, runs on `mvn test`) — enumerating status ×
 * field in a browser would be slow and prove less. What is asserted here is what a unit test cannot:
 * that the guard is actually wired into the endpoints.
 *
 * Requires education-service + gateway up. Run headed.
 */
const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'

const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const rows = (body) => {
  const b = parse(body) || {}
  if (Array.isArray(b.collection)) return b.collection
  const k = Object.keys(b).find((x) => Array.isArray(b[x]))
  return k ? b[k] : []
}
const post = (url, body) =>
  cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

/** A term to hang exams off. Returns its id. */
const seedTerm = () => {
  const yearName = 'EXY' + Date.now()
  return post('/addAcademicYear', { name: yearName, startDateStr: '01-08-2026', endDateStr: '30-06-2027' })
    .then((r) => expect(parse(r.body).status, `addAcademicYear: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))
    .then(() => cy.request('/getAcademicYears'))
    .then((r) => {
      const y = rows(r.body).find((x) => x.name === yearName)
      expect(y, 'the seeded year exists').to.exist
      return post('/addTerm', { academicYearId: y.id, name: 'T1', sequence: 1, startDateStr: '01-08-2026', endDateStr: '31-10-2026' })
        .then(() => cy.request('/getAcademicYears'))
        .then((r2) => {
          const y2 = rows(r2.body).find((x) => x.name === yearName)
          expect(y2.terms, 'the seeded term exists').to.have.length(1)
          return cy.wrap(y2.terms[0].id, { log: false })
        })
    })
}

const findExam = (name) =>
  cy.request('/getExams').then((r) => {
    const e = rows(r.body).find((x) => x.name === name)
    expect(e, `exam "${name}" is in ${JSON.stringify(rows(r.body).map((x) => x.name))}`).to.exist
    return cy.wrap(e, { log: false })
  })

describe('Education — examinations (slice 1.2)', () => {
  beforeEach(() => { cy.loginAsEduOwner() })

  it('an exam is created against a term and starts in DRAFT', () => {
    const name = 'EX' + Date.now()
    seedTerm().then((termId) => {
      post('/addExam', { name, type: 'Mid-Term', termId, weightPercent: 100 })
        .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))
      findExam(name).then((e) => {
        expect(e.status, 'a new exam is a DRAFT').to.eq('DRAFT')
        expect(e.locked).to.eq(false)
        expect(e.papers, 'no papers yet').to.be.an('array').that.is.empty
      })
    })
  })

  it('an exam CANNOT be created without a term, and the refusal names the fix (D3)', () => {
    post('/addExam', { name: 'EXNOTERM' + Date.now(), type: 'Final' }).then((r) => {
      const b = parse(r.body)
      expect(b.status, JSON.stringify(b)).to.not.eq('SUCCESS')
      expect(b.message, 'the message tells the admin what to do').to.match(/term/i)
    })
  })

  it('a paper carries its subject, and the CLASS comes back derived from it (D2)', () => {
    const name = 'EXP' + Date.now()
    seedTerm().then((termId) => {
      post('/addExam', { name, termId, weightPercent: 100 })
      cy.request('/getUserSubject').then((sr) => {
        const subjects = rows(sr.body)
        if (!subjects.length) {
          cy.log('No subjects in this org — skipping the derived-class assertion')
          return
        }
        findExam(name).then((e) => {
          post('/addExamPaper', {
            examId: e.id, subjectId: subjects[0].id, maxMarks: 50, passMarks: 17, examDateStr: '14-11-2026',
          }).then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))

          findExam(name).then((e2) => {
            expect(e2.papers, 'the paper is attached').to.have.length(1)
            const p = e2.papers[0]
            expect(p.maxMarks).to.eq(50)
            // The paper stores no gradeId — the server resolves it through the subject.
            expect(p, 'gradeName is DERIVED, not stored').to.have.property('gradeName')
            expect(p.subjectName, 'subject name resolved').to.not.be.null
          })
        })
      })
    })
  })

  it('pass marks above maximum marks are refused', () => {
    const name = 'EXPM' + Date.now()
    seedTerm().then((termId) => {
      post('/addExam', { name, termId })
      cy.request('/getUserSubject').then((sr) => {
        const subjects = rows(sr.body)
        if (!subjects.length) return
        findExam(name).then((e) => {
          post('/addExamPaper', { examId: e.id, subjectId: subjects[0].id, maxMarks: 50, passMarks: 80 })
            .then((r) => {
              const b = parse(r.body)
              expect(b.status, JSON.stringify(b)).to.not.eq('SUCCESS')
              expect(b.message).to.match(/pass marks/i)
            })
        })
      })
    })
  })

  it('a LOCKED exam refuses a marks-restating change but still allows rescheduling (D5)', () => {
    const name = 'EXL' + Date.now()
    seedTerm().then((termId) => {
      post('/addExam', { name, termId })
      cy.request('/getUserSubject').then((sr) => {
        const subjects = rows(sr.body)
        if (!subjects.length) return
        findExam(name).then((e) => {
          post('/addExamPaper', { examId: e.id, subjectId: subjects[0].id, maxMarks: 50, passMarks: 17, examDateStr: '14-11-2026' })
          post('/setExamStatus', { id: e.id, status: 'LOCKED' })
            .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))

          findExam(name).then((e2) => {
            expect(e2.locked, 'the read model reports the lock so the UI can grey fields out').to.eq(true)
            const p = e2.papers[0]

            // Blocked: changing what a score MEANS.
            post('/addExamPaper', { id: p.id, examId: e2.id, subjectId: p.subjectId, maxMarks: 100, passMarks: 33 })
              .then((r) => {
                const b = parse(r.body)
                expect(b.status, `maxMarks change on a LOCKED exam: ${JSON.stringify(b)}`).to.not.eq('SUCCESS')
                expect(b.message).to.match(/unlock/i)
              })

            // Allowed: moving the paper harms nothing.
            post('/addExamPaper', {
              id: p.id, examId: e2.id, subjectId: p.subjectId,
              maxMarks: p.maxMarks, passMarks: p.passMarks, examDateStr: '20-11-2026',
            }).then((r) => expect(parse(r.body).status, `reschedule: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))

            // And the max marks really did not move.
            findExam(name).then((e3) => expect(e3.papers[0].maxMarks, 'unchanged').to.eq(50))
          })
        })
      })
    })
  })

  it('weights that do not total 100 are a WARNING, not a block (D4)', () => {
    const name = 'EXW' + Date.now()
    seedTerm().then((termId) => {
      post('/addExam', { name, termId, weightPercent: 30 }).then((r) => {
        const b = parse(r.body)
        expect(b.status, 'saved despite an incomplete total').to.eq('SUCCESS')
        expect(b.message, 'and the response says so').to.match(/total|30%/i)
      })
    })
  })

  it('deleting an exam takes its papers with it — no orphans', () => {
    const name = 'EXD' + Date.now()
    seedTerm().then((termId) => {
      post('/addExam', { name, termId })
      cy.request('/getUserSubject').then((sr) => {
        const subjects = rows(sr.body)
        findExam(name).then((e) => {
          if (subjects.length) {
            post('/addExamPaper', { examId: e.id, subjectId: subjects[0].id, maxMarks: 50 })
          }
          post('/deleteExam', { checked: String(e.id) })
            .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))
          cy.request('/getExams').then((r) => {
            expect(rows(r.body).map((x) => x.name), 'exam gone').to.not.include(name)
          })
          cy.request('/getDatesheet').then((r) => {
            expect(rows(r.body).filter((p) => p.examId === e.id), 'no orphan papers').to.be.empty
          })
        })
      })
    })
  })

  it("another tenant's exam is invisible and cannot be taken over", () => {
    const name = 'EXISO' + Date.now()
    seedTerm().then((termId) => {
      post('/addExam', { name, termId })
      findExam(name).then((e) => {
        // Bearer token, not a session switch: the monolith runs maximumSessions(1), so logging a second
        // identity in here would expire this test's own session (see cy.asOtherTenant).
        cy.asOtherTenant((auth) => {
          cy.request({ url: `${GW}/api/education/getExams`, headers: auth, failOnStatusCode: false })
            .then((r) => {
              expect(rows(r.body).map((x) => x.name), 'not visible across tenants').to.not.include(name)
            })
          // Same id, different tenant: refused, not silently re-parented.
          cy.request({
            method: 'POST', url: `${GW}/api/education/addExam`,
            headers: auth, form: true, body: { id: e.id, name: 'STOLEN', termId: 1 }, failOnStatusCode: false,
          }).then((r) => {
            expect((r.body || {}).status, `takeover attempt: ${JSON.stringify(r.body)}`).to.not.eq('SUCCESS')
          })
        })
        findExam(name).then((e2) => expect(e2.name, 'still ours, unchanged').to.eq(name))
      })
    })
  })

  it('a teacher is FORBIDDEN to define an exam — ADMIN tier (D-3)', () => {
    cy.request({
      method: 'POST', url: `${GW}/api/auth/login`,
      headers: { 'Content-Type': 'application/json' },
      body: { email: 'user.education@myplus.com', password: PW }, failOnStatusCode: false,
    }).then((login) => {
      expect(login.status, `login: ${JSON.stringify(login.body)}`).to.eq(200)
      cy.request({
        method: 'POST', url: `${GW}/api/education/addExam`,
        headers: { Authorization: `Bearer ${login.body.data.accessToken}` },
        form: true, body: { name: 'CY-NOPE', termId: 1 }, failOnStatusCode: false,
      }).then((r) => {
        if (r.status === 403) return
        expect((r.body || {}).status === 'SUCCESS',
          `a teacher was ALLOWED to define an exam: ${JSON.stringify(r.body)}`).to.eq(false)
      })
    })
  })
})
