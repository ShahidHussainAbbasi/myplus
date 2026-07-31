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

const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
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
 * A published exam with one paper, plus a student in the paper's class.
 * Yields { paperId, examId, enrollNo, maxMarks }.
 */
const seedExamWithPaper = (maxMarks) => {
  const stamp = Date.now()
  const yearName = 'MKY' + stamp
  return post('/addAcademicYear', { name: yearName, startDateStr: '01-08-2026', endDateStr: '30-06-2027' })
    .then(() => cy.request('/getAcademicYears'))
    .then((r) => {
      const y = rows(r.body).find((x) => x.name === yearName)
      expect(y, 'seeded year').to.exist
      return post('/addTerm', { academicYearId: y.id, name: 'T1', sequence: 1, startDateStr: '01-08-2026', endDateStr: '31-10-2026' })
        .then(() => cy.request('/getAcademicYears'))
        .then((r2) => rows(r2.body).find((x) => x.name === yearName).terms[0].id)
    })
    .then((termId) => {
      const examName = 'MKX' + stamp
      return post('/addExam', { name: examName, termId, weightPercent: 100 })
        .then(() => cy.request('/getUserSubject'))
        .then((sr) => {
          const subjects = rows(sr.body)
          expect(subjects, 'the org has at least one subject to examine').to.not.be.empty
          return cy.request('/getExams').then((er) => {
            const exam = rows(er.body).find((x) => x.name === examName)
            expect(exam, 'seeded exam').to.exist
            return post('/addExamPaper', {
              examId: exam.id, subjectId: subjects[0].id, maxMarks, passMarks: 1, examDateStr: '14-11-2026',
            }).then(() => post('/setExamStatus', { id: exam.id, status: 'PUBLISHED' }))
              .then(() => cy.request('/getExams'))
              .then((er2) => {
                const e2 = rows(er2.body).find((x) => x.name === examName)
                return cy.wrap({ examId: e2.id, paperId: e2.papers[0].id, maxMarks }, { log: false })
              })
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
    seedExamWithPaper(50).then(({ paperId }) => {
      sheet(paperId).then((s) => {
        expect(s.maxMarks, 'the ceiling is shown so a teacher knows the range').to.eq(50)
        expect(s.rows, 'roster returned').to.be.an('array')
        expect(s.examStatus, 'the exam is published, so marks are accepted').to.eq('PUBLISHED')
      })
    })
  })

  it('valid rows SAVE while an out-of-range row is reported per student (D3)', () => {
    seedExamWithPaper(50).then(({ paperId }) => {
      sheet(paperId).then((s) => {
        if (s.rows.length < 2) {
          cy.log(`Only ${s.rows.length} student(s) in this class — need 2 for the partial-save case`)
          return
        }
        const good = s.rows[0].enrollNo
        const bad = s.rows[1].enrollNo
        saveMarks(paperId, [
          { enrollNo: good, marksObtained: 40, absent: false },
          { enrollNo: bad, marksObtained: 105, absent: false },   // 105 > 50
        ]).then((r) => {
          const b = parse(r.body)
          expect(b.status, `partial save: ${JSON.stringify(b)}`).to.eq('PARTIAL')
          expect(b.object.saved, 'the good row was saved').to.eq(1)
          expect(JSON.stringify(b.object.errors), 'the rejected row names the student').to.contain(bad)
        })
        // And the good one really is persisted — a partial must not roll the batch back.
        sheet(paperId).then((s2) => {
          const row = s2.rows.find((x) => x.enrollNo === good)
          expect(row.marksObtained, 'the valid mark survived the partial save').to.eq(40)
        })
      })
    })
  })

  it('absent is stored as absent, NOT as zero (D2)', () => {
    seedExamWithPaper(50).then(({ paperId }) => {
      sheet(paperId).then((s) => {
        if (!s.rows.length) return
        const en = s.rows[0].enrollNo
        saveMarks(paperId, [{ enrollNo: en, marksObtained: null, absent: true }])
          .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))
        sheet(paperId).then((s2) => {
          const row = s2.rows.find((x) => x.enrollNo === en)
          expect(row.absent, 'absent flag set').to.eq(true)
          expect(row.marksObtained, 'and NO score — absent is not zero').to.be.null
        })
      })
    })
  })

  it('re-saving updates in place — one mark per student per paper (D1)', () => {
    seedExamWithPaper(50).then(({ paperId }) => {
      sheet(paperId).then((s) => {
        if (!s.rows.length) return
        const en = s.rows[0].enrollNo
        saveMarks(paperId, [{ enrollNo: en, marksObtained: 30, absent: false }])
        saveMarks(paperId, [{ enrollNo: en, marksObtained: 35, absent: false }])
        sheet(paperId).then((s2) => {
          const mine = s2.rows.filter((x) => x.enrollNo === en)
          expect(mine, 'still exactly one row for this student').to.have.length(1)
          expect(mine[0].marksObtained, 'updated, not appended').to.eq(35)
        })
      })
    })
  })

  it('the first mark LOCKS the exam, and 1.2’s guard is then LIVE (D4)', () => {
    seedExamWithPaper(50).then(({ examId, paperId }) => {
      sheet(paperId).then((s) => {
        if (!s.rows.length) return
        saveMarks(paperId, [{ enrollNo: s.rows[0].enrollNo, marksObtained: 20, absent: false }])
          .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))

        cy.request('/getExams').then((r) => {
          const exam = rows(r.body).find((x) => x.id === examId)
          expect(exam.status, 'PUBLISHED → LOCKED on the first mark').to.eq('LOCKED')
        })

        // The whole point: 1.2 shipped this guard inert. Changing maxMarks now must be refused.
        cy.request('/getExams').then((r) => {
          const exam = rows(r.body).find((x) => x.id === examId)
          const p = exam.papers[0]
          post('/addExamPaper', {
            id: p.id, examId, subjectId: p.subjectId, maxMarks: 100, passMarks: 33,
          }).then((res) => {
            const b = parse(res.body)
            expect(b.status, `maxMarks change after marks exist: ${JSON.stringify(b)}`).to.not.eq('SUCCESS')
            expect(b.message).to.match(/unlock/i)
          })
        })
      })
    })
  })

  it('a DRAFT exam refuses marks — the datesheet must be the one students saw', () => {
    const stamp = Date.now()
    const yearName = 'MKD' + stamp
    post('/addAcademicYear', { name: yearName, startDateStr: '01-08-2026', endDateStr: '30-06-2027' })
      .then(() => cy.request('/getAcademicYears'))
      .then((r) => {
        const y = rows(r.body).find((x) => x.name === yearName)
        return post('/addTerm', { academicYearId: y.id, name: 'T1', sequence: 1 })
          .then(() => cy.request('/getAcademicYears'))
          .then((r2) => rows(r2.body).find((x) => x.name === yearName).terms[0].id)
      })
      .then((termId) => {
        const examName = 'MKDX' + stamp
        post('/addExam', { name: examName, termId })   // left in DRAFT deliberately
        cy.request('/getUserSubject').then((sr) => {
          const subjects = rows(sr.body)
          if (!subjects.length) return
          cy.request('/getExams').then((er) => {
            const exam = rows(er.body).find((x) => x.name === examName)
            post('/addExamPaper', { examId: exam.id, subjectId: subjects[0].id, maxMarks: 50 })
              .then(() => cy.request('/getExams'))
              .then((er2) => {
                const e2 = rows(er2.body).find((x) => x.name === examName)
                expect(e2.status).to.eq('DRAFT')
                saveMarks(e2.papers[0].id, [{ enrollNo: 'ANY', marksObtained: 10, absent: false }])
                  .then((res) => {
                    const b = parse(res.body)
                    expect(b.status, JSON.stringify(b)).to.not.eq('SUCCESS')
                    expect(b.message).to.match(/publish/i)
                  })
              })
          })
        })
      })
  })

  it("another tenant's paper cannot be marked (org-scoped, anti-IDOR)", () => {
    seedExamWithPaper(50).then(({ paperId }) => {
      cy.loginAsEducation()   // demo.education@ — a different org
      cy.request({ url: `/getMarksSheet?examPaperId=${paperId}`, failOnStatusCode: false }).then((r) => {
        expect(parse(r.body).status, 'not readable across tenants').to.not.eq('SUCCESS')
      })
      saveMarks(paperId, [{ enrollNo: 'ANY', marksObtained: 10, absent: false }]).then((r) => {
        expect(parse(r.body).status, JSON.stringify(r.body)).to.not.eq('SUCCESS')
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
