/**
 * Slice 1.3 — marks entry.
 * Design: microservices/docs/slices/edu-1.3-marks-entry.md
 *
 * The first data on this platform that is a claim about a CHILD which follows them for years. What is
 * asserted here is what a unit test cannot: that the rules are WIRED IN end to end — per-row partial
 * success, absent-is-not-zero, the automatic lock, and that 1.2's guard is now live rather than inert.
 *
 * The validation MATRIX (bounds × absent × blank) lives in MarksValidatorTest, pure, on `mvn test`.
 *
 * Requires education-service + gateway up. Run headed.
 */
const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'

/**
 * Parse a response body, but fail with what the SERVER actually said rather than a bare SyntaxError.
 * The monolith answers an expired session with the login page (HTML) or Spring Security's
 * "This session has been expired…" text — both of which make JSON.parse throw a token error that
 * says nothing about the real cause.
 */
const parse = (b) => {
  if (typeof b !== 'string') return b
  try {
    return JSON.parse(b)
  } catch (e) {
    const head = b.slice(0, 160).replace(/\s+/g, ' ')
    if (/<!DOCTYPE|<html/i.test(b) || /session has been expired/i.test(b)) {
      throw new Error(`Not authenticated — the server returned a login/expired-session page: "${head}"`)
    }
    throw new Error(`Expected JSON, got: "${head}"`)
  }
}
const rows = (body) => {
  const b = parse(body) || {}
  if (Array.isArray(b.collection)) return b.collection
  const k = Object.keys(b).find((x) => Array.isArray(b[x]))
  return k ? b[k] : []
}
const post = (url, body) =>
  cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

const saveMarks = (examPaperId, marksRows) =>
  cy.request({
    method: 'POST', url: '/saveMarksBulk', failOnStatusCode: false,
    headers: { 'Content-Type': 'application/json' },
    body: { examPaperId, rows: marksRows },
  })

/**
 * Seeds EVERYTHING this spec needs, so no case depends on what happens to be in the demo org:
 * a class, a subject in that class, TWO students in it, a term, and a published exam with one paper.
 *
 * The earlier version skipped when the org had no subjects or fewer than two students — which turns a
 * missing fixture into a PASS. A spec that silently skips its own assertions is worse than a failing
 * one, because the green is indistinguishable from a real green.
 *
 * Yields { examId, paperId, gradeId, maxMarks, enrollA, enrollB }.
 */
const seedExamWithPaper = (maxMarks) => {
  const stamp = Date.now()
  const gradeName = 'MKC' + stamp
  const enrollA = 'MKA' + stamp
  const enrollB = 'MKB' + stamp
  const out = { maxMarks, enrollA, enrollB }

  // 1. a class of our own — never reuse an existing one, whose roster we do not control
  return post('/addGrade', { name: gradeName, fee: 1000, status: 'Active' })
    .then((r) => expect(parse(r.body).status, `addGrade: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))
    .then(() => cy.request('/getUserGrade'))
    .then((r) => {
      const g = rows(r.body).find((x) => x.name === gradeName)
      expect(g, `class "${gradeName}" was created`).to.exist
      out.gradeId = g.id

      // 2. a subject IN that class — this is what makes the paper's class derivable (1.2 D2)
      return post('/addSubject', { name: 'MKSubj' + stamp, gradeId: g.id, status: 'Active' })
    })
    .then((r) => expect(parse(r.body).status, `addSubject: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))
    .then(() => cy.request('/getUserSubject'))
    .then((r) => {
      const subj = rows(r.body).find((x) => x.name === 'MKSubj' + stamp)
      expect(subj, 'the seeded subject exists').to.exist
      out.subjectId = subj.id

      // 3. TWO students in that class — the partial-save case needs a good row AND a bad one
      return post('/addStudent', { name: 'Marks A', enrollNo: enrollA, gradeId: out.gradeId, status: 'ACTIVE' })
        .then((r2) => expect(parse(r2.body).status, `addStudent A: ${JSON.stringify(r2.body)}`).to.match(/SUCCESS/))
        .then(() => post('/addStudent', { name: 'Marks B', enrollNo: enrollB, gradeId: out.gradeId, status: 'ACTIVE' }))
        .then((r3) => expect(parse(r3.body).status, `addStudent B: ${JSON.stringify(r3.body)}`).to.match(/SUCCESS/))
    })
    // 4. a term for the exam to sit in (1.2 D3 — an exam cannot exist without one)
    .then(() => post('/addAcademicYear', { name: 'MKY' + stamp, startDateStr: '01-08-2026', endDateStr: '30-06-2027' }))
    .then(() => cy.request('/getAcademicYears'))
    .then((r) => {
      const y = rows(r.body).find((x) => x.name === 'MKY' + stamp)
      expect(y, 'seeded year').to.exist
      return post('/addTerm', { academicYearId: y.id, name: 'T1', sequence: 1, startDateStr: '01-08-2026', endDateStr: '31-10-2026' })
        .then(() => cy.request('/getAcademicYears'))
        .then((r2) => {
          const y2 = rows(r2.body).find((x) => x.name === 'MKY' + stamp)
          expect(y2.terms, 'seeded term').to.have.length(1)
          return y2.terms[0].id
        })
    })
    // 5. a PUBLISHED exam with one paper on our subject
    .then((termId) => {
      const examName = 'MKX' + stamp
      return post('/addExam', { name: examName, termId, weightPercent: 100 })
        .then(() => cy.request('/getExams'))
        .then((er) => {
          const exam = rows(er.body).find((x) => x.name === examName)
          expect(exam, 'seeded exam').to.exist
          return post('/addExamPaper', {
            examId: exam.id, subjectId: out.subjectId, maxMarks, passMarks: 1, examDateStr: '14-11-2026',
          })
            .then((r) => expect(parse(r.body).status, `addExamPaper: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))
            .then(() => post('/setExamStatus', { id: exam.id, status: 'PUBLISHED' }))
            .then(() => cy.request('/getExams'))
            .then((er2) => {
              const e2 = rows(er2.body).find((x) => x.name === examName)
              expect(e2.papers, 'the exam has its paper').to.have.length(1)
              out.examId = e2.id
              out.paperId = e2.papers[0].id
              return cy.wrap(out, { log: false })
            })
        })
    })
}

/** The roster the server says is examinable for this paper. */
const sheet = (paperId) =>
  cy.request({ url: `/getMarksSheet?examPaperId=${paperId}`, failOnStatusCode: false })
    .then((r) => {
      const b = parse(r.body)
      expect(b.status, `getMarksSheet: ${JSON.stringify(b).slice(0, 300)}`).to.eq('SUCCESS')
      return cy.wrap(b.object, { log: false })
    })

describe('Education — marks entry (slice 1.3)', () => {
  beforeEach(() => { cy.loginAsEduOwner() })

  it('a marksheet loads with the roster, the maximum and the pass mark', () => {
    seedExamWithPaper(50).then(({ paperId, enrollA, enrollB }) => {
      sheet(paperId).then((s) => {
        expect(s.maxMarks, 'the ceiling is shown so a teacher knows the range').to.eq(50)
        expect(s.examStatus, 'the exam is published, so marks are accepted').to.eq('PUBLISHED')
        // The roster is DERIVED: subject → grade → the students in that class (1.2 D2).
        const got = s.rows.map((x) => x.enrollNo)
        expect(got, 'both seeded students are on the sheet').to.include(enrollA).and.to.include(enrollB)
      })
    })
  })

  it('valid rows SAVE while an out-of-range row is reported per student (D3)', () => {
    seedExamWithPaper(50).then(({ paperId, enrollA, enrollB }) => {
      saveMarks(paperId, [
        { enrollNo: enrollA, marksObtained: 40, absent: false },
        { enrollNo: enrollB, marksObtained: 105, absent: false },   // 105 > 50
      ]).then((r) => {
        const b = parse(r.body)
        expect(b.status, `partial save: ${JSON.stringify(b)}`).to.eq('PARTIAL')
        expect(b.object.saved, 'the good row was saved').to.eq(1)
        expect(JSON.stringify(b.object.errors), 'the rejected row names the student').to.contain(enrollB)
      })
      // And the good one really is persisted — a partial must not roll the batch back.
      sheet(paperId).then((s2) => {
        const good = s2.rows.find((x) => x.enrollNo === enrollA)
        expect(good.marksObtained, 'the valid mark survived the partial save').to.eq(40)
        const bad = s2.rows.find((x) => x.enrollNo === enrollB)
        expect(bad.marksObtained, 'and the rejected one was NOT written').to.be.null
      })
    })
  })

  it('absent is stored as absent, NOT as zero (D2)', () => {
    seedExamWithPaper(50).then(({ paperId, enrollA, enrollB }) => {
      // Zero and absent side by side: the pair is the whole point, so assert them together.
      saveMarks(paperId, [
        { enrollNo: enrollA, marksObtained: null, absent: true },
        { enrollNo: enrollB, marksObtained: 0, absent: false },
      ]).then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      sheet(paperId).then((s2) => {
        const absent = s2.rows.find((x) => x.enrollNo === enrollA)
        expect(absent.absent, 'absent flag set').to.eq(true)
        expect(absent.marksObtained, 'and NO score — absent is not zero').to.be.null

        const zero = s2.rows.find((x) => x.enrollNo === enrollB)
        expect(zero.absent, 'a zero is NOT an absence').to.eq(false)
        expect(zero.marksObtained, 'zero is a real score and must survive as 0').to.eq(0)
      })
    })
  })

  it('re-saving updates in place — one mark per student per paper (D1)', () => {
    seedExamWithPaper(50).then(({ paperId, enrollA }) => {
      saveMarks(paperId, [{ enrollNo: enrollA, marksObtained: 30, absent: false }])
      saveMarks(paperId, [{ enrollNo: enrollA, marksObtained: 35, absent: false }])
      sheet(paperId).then((s2) => {
        const mine = s2.rows.filter((x) => x.enrollNo === enrollA)
        expect(mine, 'still exactly one row for this student').to.have.length(1)
        expect(mine[0].marksObtained, 'updated, not appended').to.eq(35)
      })
    })
  })

  it('the first mark LOCKS the exam, and 1.2’s guard is then LIVE (D4)', () => {
    seedExamWithPaper(50).then(({ examId, paperId, enrollA }) => {
      cy.request('/getExams').then((r) => {
        const before = rows(r.body).find((x) => x.id === examId)
        expect(before.status, 'starts PUBLISHED, so the lock below is caused by the mark').to.eq('PUBLISHED')
      })

      saveMarks(paperId, [{ enrollNo: enrollA, marksObtained: 20, absent: false }])
        .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getExams').then((r) => {
        const exam = rows(r.body).find((x) => x.id === examId)
        expect(exam.status, 'PUBLISHED → LOCKED on the first mark').to.eq('LOCKED')

        // The whole point: 1.2 shipped this guard inert. Changing maxMarks now must be refused.
        const p = exam.papers[0]
        post('/addExamPaper', {
          id: p.id, examId, subjectId: p.subjectId, maxMarks: 100, passMarks: 33,
        }).then((res) => {
          const b = parse(res.body)
          expect(b.status, `maxMarks change after marks exist: ${JSON.stringify(b)}`).to.not.eq('SUCCESS')
          expect(b.message).to.match(/unlock/i)
        })

        // Rescheduling stays allowed even locked (1.2 D5) — proves the lock is targeted, not a blanket freeze.
        post('/addExamPaper', {
          id: p.id, examId, subjectId: p.subjectId,
          maxMarks: p.maxMarks, passMarks: p.passMarks, examDateStr: '21-11-2026',
        }).then((res) => expect(parse(res.body).status, `reschedule: ${JSON.stringify(res.body)}`).to.eq('SUCCESS'))
      })
    })
  })

  it('a DRAFT exam refuses marks — the datesheet must be the one students saw', () => {
    // Reuses the full fixture, then puts the exam BACK to DRAFT: seeding a second parallel exam by hand
    // was how this case ended up skipping when the org had no subjects.
    seedExamWithPaper(50).then(({ examId, paperId, enrollA }) => {
      post('/setExamStatus', { id: examId, status: 'DRAFT' })
        .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      saveMarks(paperId, [{ enrollNo: enrollA, marksObtained: 10, absent: false }])
        .then((res) => {
          const b = parse(res.body)
          expect(b.status, `marks on a DRAFT exam: ${JSON.stringify(b)}`).to.not.eq('SUCCESS')
          expect(b.message, 'the refusal names the fix').to.match(/publish/i)
        })

      // And nothing was written — a refusal must not half-save.
      post('/setExamStatus', { id: examId, status: 'PUBLISHED' })
      sheet(paperId).then((s) => {
        const row = s.rows.find((x) => x.enrollNo === enrollA)
        expect(row.marksObtained, 'the refused mark was never persisted').to.be.null
      })
    })
  })

  it("another tenant's paper cannot be marked (org-scoped, anti-IDOR)", () => {
    // Bearer token, not a session switch — see cy.asOtherTenant for why (maximumSessions(1)).
    seedExamWithPaper(50).then(({ paperId }) => {
      cy.asOtherTenant((auth) => {
        cy.request({
          url: `${GW}/api/education/getMarksSheet?examPaperId=${paperId}`,
          headers: auth, failOnStatusCode: false,
        }).then((r) => {
          const status = (r.body || {}).status
          expect(status, `another tenant read our marksheet: ${JSON.stringify(r.body)}`).to.not.eq('SUCCESS')
        })

        cy.request({
          method: 'POST', url: `${GW}/api/education/saveMarksBulk`,
          headers: { ...auth, 'Content-Type': 'application/json' },
          body: { examPaperId: paperId, rows: [{ enrollNo: 'ANY', marksObtained: 10, absent: false }] },
          failOnStatusCode: false,
        }).then((r) => {
          const status = (r.body || {}).status
          expect(status, `another tenant wrote to our paper: ${JSON.stringify(r.body)}`).to.not.eq('SUCCESS')
        })
      })
    })
  })

  it('a teacher IS allowed to enter marks — WRITE tier, not ADMIN (D6)', () => {
    // The gate must not lock out the role it exists to serve: teachers are who enters marks.
    cy.request({
      method: 'POST', url: `${GW}/api/auth/login`,
      headers: { 'Content-Type': 'application/json' },
      body: { email: 'user.education@myplus.com', password: PW }, failOnStatusCode: false,
    }).then((login) => {
      expect(login.status, `login: ${JSON.stringify(login.body)}`).to.eq(200)
      cy.request({
        method: 'POST', url: `${GW}/api/education/saveMarksBulk`,
        headers: { Authorization: `Bearer ${login.body.data.accessToken}`, 'Content-Type': 'application/json' },
        body: { examPaperId: 999999, rows: [] }, failOnStatusCode: false,
      }).then((r) => {
        // A junk payload is fine — the point is it gets PAST @PreAuthorize, not that it saves.
        expect(r.status, `a teacher was blocked from marks entry: ${JSON.stringify(r.body)}`).to.not.eq(403)
      })
    })
  })
})
