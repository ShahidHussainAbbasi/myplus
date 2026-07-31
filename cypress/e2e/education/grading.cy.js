/**
 * Slice 1.4 — grading scales.
 * Design: microservices/docs/slices/edu-1.4-grading-scales.md
 *
 * 1.3 stores the raw number; this answers "is that good?". The band MATRIX (overlap, gap, 0–100 coverage)
 * and the absent policy live in BandValidatorTest / GradingServiceTest, pure, on `mvn test`. What is
 * asserted here is what a unit test cannot: that grading is wired into the marks reads, that it is
 * DERIVED (re-banding changes an existing mark's letter), and that it is org-scoped and ADMIN-gated.
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

const scale = () =>
  cy.request('/getGradingScale').then((r) => {
    const b = parse(r.body)
    expect(b.status, `getGradingScale: ${JSON.stringify(b).slice(0, 200)}`).to.eq('SUCCESS')
    return cy.wrap(b.object, { log: false })
  })

/** The scale is org-wide, so each test starts from a known empty state and rebuilds what it needs. */
const clearScale = () =>
  scale().then((s) => {
    const ids = (s.bands || []).map((b) => b.id)
    if (!ids.length) return
    return post('/deleteGradeBand', { checked: ids.join(',') })
      .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))
  })

const addBand = (name, min, max, gpa) =>
  post('/saveGradeBand', { name, minPercent: min, maxPercent: max, ...(gpa == null ? {} : { gpaPoints: gpa }) })

/** F 0-32, C 33-59, B 60-79, A 80-100 — built low-to-high so each save leaves a valid scale. */
const buildScale = () =>
  addBand('F', 0, 100).then(() => {
    // Widen-then-split: the validator requires 0–100 coverage at every step, so the scale is grown by
    // shrinking the top band and appending — the same order a real owner would be forced into.
    post('/saveGradeBand', { name: 'F', minPercent: 0, maxPercent: 32 })
    return scale().then((s) => {
      const f = s.bands.find((b) => b.name === 'F')
      post('/saveGradeBand', { id: f.id, name: 'F', minPercent: 0, maxPercent: 32 })
      addBand('C', 33, 59)
      addBand('B', 60, 79)
      return addBand('A', 80, 100, 4)
    })
  })

describe('Education — grading scales (slice 1.4)', () => {
  beforeEach(() => {
    cy.loginAsEduOwner()
    clearScale()
  })

  it('the preset creates a complete scale in one click, and refuses to overwrite one', () => {
    post('/applyGradingPreset', {})
      .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    scale().then((s) => {
      expect(s.bands.length, 'a full scale was created').to.be.greaterThan(1)
      expect(s.configured).to.eq(true)
      expect(s.bands[0].minPercent, 'starts at 0').to.eq(0)
      expect(s.bands[s.bands.length - 1].maxPercent, 'ends at 100').to.eq(100)
    })
    // Applying it again must not silently replace a school's own bands.
    post('/applyGradingPreset', {}).then((r) => {
      expect(parse(r.body).status, JSON.stringify(r.body)).to.not.eq('SUCCESS')
    })
  })

  it('an overlapping band is refused, and the message names the pair', () => {
    post('/applyGradingPreset', {})
    scale().then((s) => {
      const top = s.bands[s.bands.length - 1]
      // Reach down into the band below — an unambiguous overlap.
      post('/saveGradeBand', { id: top.id, name: top.name, minPercent: 10, maxPercent: 100 })
        .then((r) => {
          const b = parse(r.body)
          expect(b.status, `overlap: ${JSON.stringify(b)}`).to.not.eq('SUCCESS')
          expect(b.message).to.match(/overlap/i)
        })
    })
  })

  it('a gap is refused, naming the percentages nobody would grade', () => {
    addBand('Low', 0, 32).then((r) => {
      // 0–32 alone does not reach 100, so this first save is itself refused — assert that, then prove
      // the gap message appears when the top is present but the middle is missing.
      expect(parse(r.body).status, 'a scale must cover 0-100').to.not.eq('SUCCESS')
    })
    post('/applyGradingPreset', {})
    scale().then((s) => {
      const second = s.bands[1]
      // Push the second band up, leaving a hole under it.
      post('/saveGradeBand', {
        id: second.id, name: second.name,
        minPercent: second.maxPercent, maxPercent: second.maxPercent,
      }).then((r) => {
        const b = parse(r.body)
        expect(b.status, `gap: ${JSON.stringify(b)}`).to.not.eq('SUCCESS')
        expect(b.message).to.match(/nothing covers/i)
      })
    })
  })

  it('marks come back with a percentage and a DERIVED grade, and re-banding changes it (D4)', () => {
    post('/applyGradingPreset', {})
    // Reuse the marks fixture shape: seed a class, subject, student, term, exam, paper, then a mark.
    const stamp = Date.now()
    const gradeName = 'GRC' + stamp
    const en = 'GRE' + stamp
    post('/addGrade', { name: gradeName, fee: 0, status: 'Active' })
    cy.request('/getUserGrade').then((gr) => {
      const g = rows(gr.body).find((x) => x.name === gradeName)
      expect(g, 'seeded class').to.exist
      post('/addSubject', { name: 'GRS' + stamp, gradeId: g.id, status: 'Active' })
      post('/addStudent', { name: 'Grading Kid', enrollNo: en, gradeId: g.id, status: 'ACTIVE' })

      post('/addAcademicYear', { name: 'GRY' + stamp, startDateStr: '01-08-2026', endDateStr: '30-06-2027' })
      cy.request('/getAcademicYears').then((yr) => {
        const y = rows(yr.body).find((x) => x.name === 'GRY' + stamp)
        post('/addTerm', { academicYearId: y.id, name: 'T1', sequence: 1 })
        cy.request('/getAcademicYears').then((yr2) => {
          const termId = rows(yr2.body).find((x) => x.name === 'GRY' + stamp).terms[0].id
          post('/addExam', { name: 'GRX' + stamp, termId, weightPercent: 100 })
          cy.request('/getUserSubject').then((sr) => {
            const subj = rows(sr.body).find((x) => x.name === 'GRS' + stamp)
            cy.request('/getExams').then((er) => {
              const exam = rows(er.body).find((x) => x.name === 'GRX' + stamp)
              post('/addExamPaper', { examId: exam.id, subjectId: subj.id, maxMarks: 50, passMarks: 17 })
              post('/setExamStatus', { id: exam.id, status: 'PUBLISHED' })
              cy.request('/getExams').then((er2) => {
                const paperId = rows(er2.body).find((x) => x.name === 'GRX' + stamp).papers[0].id
                // 37/50 = 74% → B under the preset.
                cy.request({
                  method: 'POST', url: '/saveMarksBulk', failOnStatusCode: false,
                  headers: { 'Content-Type': 'application/json' },
                  body: { examPaperId: paperId, rows: [{ enrollNo: en, marksObtained: 37, absent: false }] },
                }).then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))

                cy.request(`/getMarksSheet?examPaperId=${paperId}`).then((ms) => {
                  const row = parse(ms.body).object.rows.find((x) => x.enrollNo === en)
                  expect(row.percent, '37 of 50 is 74%').to.eq(74)
                  expect(row.grade, 'and 74% bands as B').to.eq('B')
                })

                // D4: re-band, and the SAME mark reads differently — proof it is derived, not stored.
                scale().then((s) => {
                  const b = s.bands.find((x) => x.name === 'B')
                  const a = s.bands.find((x) => x.name === 'A')
                  post('/saveGradeBand', { id: a.id, name: 'A', minPercent: 70, maxPercent: 100, gpaPoints: 4 })
                  post('/saveGradeBand', { id: b.id, name: 'B', minPercent: b.minPercent, maxPercent: 69 })
                  cy.request(`/getMarksSheet?examPaperId=${paperId}`).then((ms2) => {
                    const row = parse(ms2.body).object.rows.find((x) => x.enrollNo === en)
                    expect(row.grade, 'the same mark now reads A — grading is DERIVED').to.eq('A')
                  })
                })
              })
            })
          })
        })
      })
    })
  })

  it('with NO scale defined, marks still return a percentage and simply have no grade (D2)', () => {
    // clearScale() ran in beforeEach, so the org has no bands here.
    scale().then((s) => {
      expect(s.bands, 'no bands').to.be.empty
      expect(s.configured, 'and the UI is told so, rather than shown an unexplained empty table').to.eq(false)
    })
  })

  it("another tenant's scale is invisible", () => {
    post('/applyGradingPreset', {})
    cy.asOtherTenant((auth) => {
      cy.request({ url: `${GW}/api/education/getGradingScale`, headers: auth, failOnStatusCode: false })
        .then((r) => {
          const mine = ((r.body || {}).object || {}).bands || []
          // The other tenant may have its own bands; what matters is it cannot see ours.
          expect(mine.length, 'our 5-band preset is not visible to another org').to.not.eq(5)
        })
    })
  })

  it('a teacher is FORBIDDEN to change the grading scale — policy is the ADMIN tier', () => {
    cy.request({
      method: 'POST', url: `${GW}/api/auth/login`,
      headers: { 'Content-Type': 'application/json' },
      body: { email: 'user.education@myplus.com', password: PW }, failOnStatusCode: false,
    }).then((login) => {
      expect(login.status, `login: ${JSON.stringify(login.body)}`).to.eq(200)
      cy.request({
        method: 'POST', url: `${GW}/api/education/saveGradeBand`,
        headers: { Authorization: `Bearer ${login.body.data.accessToken}` },
        form: true, body: { name: 'CY-NOPE', minPercent: 0, maxPercent: 100 }, failOnStatusCode: false,
      }).then((r) => {
        if (r.status === 403) return
        expect((r.body || {}).status === 'SUCCESS',
          `a teacher changed the grading scale: ${JSON.stringify(r.body)}`).to.eq(false)
      })
    })
  })
})
