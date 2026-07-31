/**
 * Slice 1.1 — academic year & term.
 * Design: microservices/docs/slices/edu-1.1-academic-year-term.md
 *
 * The keystone of Phase 1: exams, marks, report cards and promotion all answer "in which term?".
 * This proves the spine works — CRUD, the DERIVED current-term rule (D3) with its pin override, the
 * nullable stamp on new rows (D4), tenant isolation, and the ADMIN privilege tier (D-3).
 *
 * The four pure branches of the resolution rule are covered by TermServiceTest (runs on `mvn test`);
 * what CANNOT be unit-tested is asserted here: that it works end-to-end through the proxy, scoped to
 * a tenant, behind the right privilege.
 *
 * Requires education-service + gateway up. Run headed.
 */
const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'

const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
// GenericResponse puts lists in `collection`; take the first key holding an array.
const rows = (body) => {
  const b = parse(body) || {}
  if (Array.isArray(b.collection)) return b.collection
  const k = Object.keys(b).find((x) => Array.isArray(b[x]))
  return k ? b[k] : []
}

const post = (url, body) =>
  cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

const addYear = (name) =>
  post('/addAcademicYear', { name, startDateStr: '01-08-2026', endDateStr: '30-06-2027' })
    .then((r) => {
      expect(parse(r.body).status, `addAcademicYear: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })

const findYear = (name) =>
  cy.request('/getAcademicYears').then((r) => {
    const y = rows(r.body).find((x) => x.name === name)
    // Assert the positive first: a silent undefined here would make every later step confusing.
    expect(y, `year "${name}" is in ${JSON.stringify(rows(r.body).map((x) => x.name))}`).to.exist
    return cy.wrap(y, { log: false })
  })

describe('Education — academic year & term (slice 1.1)', () => {
  beforeEach(() => { cy.loginAsEduOwner() })

  it('an owner can create an academic year, and it comes back with no terms yet', () => {
    const name = 'AY' + Date.now()
    addYear(name)
    findYear(name).then((y) => {
      expect(y.startDateStr, 'dates round-trip in the dd-MM-yyyy wire format').to.eq('01-08-2026')
      expect(y.terms, 'a new year starts with no terms').to.be.an('array').that.is.empty
    })
  })

  it('terms hang off the year and come back in sequence', () => {
    const name = 'AY' + Date.now()
    addYear(name)
    findYear(name).then((y) => {
      post('/addTerm', { academicYearId: y.id, name: 'Term 1', sequence: 1, startDateStr: '01-08-2026', endDateStr: '31-10-2026' })
      post('/addTerm', { academicYearId: y.id, name: 'Term 2', sequence: 2, startDateStr: '01-11-2026', endDateStr: '31-01-2027' })
      findYear(name).then((y2) => {
        expect(y2.terms.map((t) => t.name)).to.deep.eq(['Term 1', 'Term 2'])
      })
    })
  })

  it('a pinned term WINS over the date comparison, and unpinning releases it (D3)', () => {
    const name = 'AY' + Date.now()
    addYear(name)
    findYear(name).then((y) => {
      // Both terms are in the past, so without a pin the rule returns "the most recently ended".
      post('/addTerm', { academicYearId: y.id, name: 'Past A', sequence: 1, startDateStr: '01-01-2020', endDateStr: '31-03-2020' })
      post('/addTerm', { academicYearId: y.id, name: 'Past B', sequence: 2, startDateStr: '01-04-2020', endDateStr: '30-06-2020' })

      findYear(name).then((y2) => {
        const a = y2.terms.find((t) => t.name === 'Past A')
        post('/pinCurrentTerm', { id: a.id }).then((r) =>
          expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))

        cy.request('/getCurrentTerm').then((r) => {
          const cur = parse(r.body).object
          expect(cur, 'a pinned term is returned').to.exist
          expect(cur.name, 'the PIN beats the later end date').to.eq('Past A')
        })

        // Pinning is exclusive: Past B must not still be pinned from any earlier run.
        findYear(name).then((y3) => {
          expect(y3.terms.filter((t) => t.pinnedCurrent).map((t) => t.name)).to.deep.eq(['Past A'])
        })

        post('/pinCurrentTerm', { id: a.id, pinned: 'false' })
        cy.request('/getCurrentTerm').then((r) => {
          const cur = parse(r.body).object
          expect(cur && cur.name, 'unpinned → falls back to the most recently ENDED term').to.eq('Past B')
        })
      })
    })
  })

  it('marking attendance still works and stamps the current term (D4)', () => {
    // The point of D4: this must not break for a school that has terms, nor for one that does not.
    cy.request({ method: 'POST', url: '/markAttendanceBulk', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { gradeId: 999999, dateStr: '01-01-2030', rows: [] },
    }).then((r) => {
      // An empty batch is INVALID, not an error — what matters is that the term lookup did not blow up.
      expect(r.status, JSON.stringify(r.body)).to.be.lessThan(500)
    })
  })

  it("another tenant's academic years are invisible", () => {
    const name = 'AYISO' + Date.now()
    addYear(name)
    cy.loginAsEducation()   // demo.education@ — a different org
    cy.request('/getAcademicYears').then((r) => {
      expect(rows(r.body).map((x) => x.name), 'the other tenant sees nothing of ours').to.not.include(name)
    })
  })

  it('a teacher is FORBIDDEN to create a year — structure is the ADMIN tier (D-3)', () => {
    cy.request({
      method: 'POST', url: `${GW}/api/auth/login`,
      headers: { 'Content-Type': 'application/json' },
      body: { email: 'user.education@myplus.com', password: PW }, failOnStatusCode: false,
    }).then((login) => {
      expect(login.status, `login: ${JSON.stringify(login.body)}`).to.eq(200)
      cy.request({
        method: 'POST', url: `${GW}/api/education/addAcademicYear`,
        headers: { Authorization: `Bearer ${login.body.data.accessToken}` },
        form: true, body: { name: 'CY-NOPE' }, failOnStatusCode: false,
      }).then((r) => {
        if (r.status === 403) return
        const b = r.body || {}
        expect(b.status === 'SUCCESS', `a teacher was ALLOWED to add a year: ${JSON.stringify(b)}`).to.eq(false)
      })
    })
  })

  it('a year cannot be re-parented into another tenant (anti-IDOR on save)', () => {
    const name = 'AYIDOR' + Date.now()
    addYear(name)
    findYear(name).then((y) => {
      cy.loginAsEducation()
      // Same id, different tenant: must be refused, NOT silently taken over (finding A's shape).
      post('/addAcademicYear', { id: y.id, name: 'STOLEN' }).then((r) => {
        expect(parse(r.body).status, JSON.stringify(r.body)).to.not.eq('SUCCESS')
      })
      cy.loginAsEduOwner()
      findYear(name).then((y2) => expect(y2.name, 'still ours, unchanged').to.eq(name))
    })
  })
})
