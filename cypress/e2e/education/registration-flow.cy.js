/**
 * Education — full registration lifecycle (E2E flow) on the educationDashboard "registered items".
 *
 * Walks a single registered item (Grade) through its COMPLETE lifecycle, then adds a second
 * registered item type (Subject) and tears everything down:
 *
 *   REGISTER (create) → verify in list → duplicate guard → MODIFY (update) → verify the change →
 *   ADD a second registered item (Subject) → verify both exist → DELETE both → verify they're gone.
 *
 * Mirrors business/flow.cy.js. Org-scoped via the seeded demo.education account. The monolith
 * proxies these flat endpoints (GenericResponse) to the education-service; addX returns the JSON
 * as a forwarded string in some cases, so `asBody()` normalizes string|object.
 *
 * Run headed:  npx cypress run --headed --browser chrome --spec cypress/e2e/education/registration-flow.cy.js
 */

const ts      = Date.now()
const GRADE    = `FlowGrade_${ts}`
const GRADE_ED = `FlowGrade_${ts}_edited`
const SUBJECT  = `FlowSubject_${ts}`

// The monolith forwards the education-service response; depending on the route it may arrive as a
// raw JSON string (ResponseEntity<String>) or an already-parsed object. Normalize to an object.
function asBody(res) {
  return typeof res.body === 'string' ? JSON.parse(res.body) : res.body
}

// getUserX list endpoints return GenericResponse(status, message, Collection) — the rows land in
// `collection` (not `data`). Keep a `data` fallback in case a route uses the Object constructor.
function rows(res) {
  return res.body.collection || res.body.data || []
}

describe('Education — registration full lifecycle (register → modify → add → delete)', () => {
  let gradeId
  let subjectId

  before(() => {
    cy.loginAsEducation()
  })

  beforeEach(() => {
    cy.loginAsEducation()
  })

  // ─── REGISTER ───────────────────────────────────────────────────────────────
  it('REGISTER — addGrade creates a new registered item (SUCCESS)', () => {
    cy.request({
      method: 'POST', url: '/addGrade', form: true,
      body: { name: GRADE, code: 'G-1', section: 'A' },
    }).then((res) => {
      expect(res.status).to.eq(200)
      expect(asBody(res).status).to.eq('SUCCESS')
    })
  })

  it('REGISTER — the new grade appears in getUserGrade with the values sent', () => {
    cy.request('/getUserGrade').then((res) => {
      expect(res.body.status).to.eq('SUCCESS')
      const g = rows(res).find((x) => x.name === GRADE)
      expect(g, `grade "${GRADE}" should be listed`).to.not.be.undefined
      gradeId = g.id
      expect(g.section).to.eq('A')
      expect(g.code).to.eq('G-1')
    })
  })

  // ─── DUPLICATE GUARD ────────────────────────────────────────────────────────
  it('GUARD — re-registering the same grade returns FOUND (no duplicate)', () => {
    cy.request({
      method: 'POST', url: '/addGrade', form: true,
      body: { name: GRADE, code: 'G-1', section: 'A' },
    }).then((res) => {
      expect(asBody(res).status).to.eq('FOUND')
    })
  })

  // ─── MODIFY ─────────────────────────────────────────────────────────────────
  it('MODIFY — addGrade with the id updates the name, code & section (SUCCESS)', () => {
    expect(gradeId, 'need gradeId captured at REGISTER').to.exist
    cy.request({
      method: 'POST', url: '/addGrade', form: true,
      body: { id: gradeId, name: GRADE_ED, code: 'G-1B', section: 'B' },
    }).then((res) => {
      expect(asBody(res).status).to.eq('SUCCESS')
    })
  })

  it('MODIFY — the list reflects the edit (same id, new values, old name gone)', () => {
    cy.request('/getUserGrade').then((res) => {
      const data = rows(res)
      const edited = data.find((x) => x.id === gradeId)
      expect(edited, 'the grade row should still exist after an edit').to.not.be.undefined
      expect(edited.name).to.eq(GRADE_ED)
      expect(edited.code).to.eq('G-1B')
      expect(edited.section).to.eq('B')
      expect(data.some((x) => x.name === GRADE), 'the old name should no longer appear').to.be.false
    })
  })

  // ─── ADDITION (second registered item type) ─────────────────────────────────
  it('ADD — register a second item type (Subject) alongside the grade', () => {
    cy.request({
      method: 'POST', url: '/addSubject', form: true,
      body: { name: SUBJECT, code: 'S-1' },
    }).then((res) => {
      expect(asBody(res).status).to.eq('SUCCESS')
    })
    cy.request('/getUserSubject').then((res) => {
      expect(res.body.status).to.eq('SUCCESS')
      const s = rows(res).find((x) => x.name === SUBJECT)
      expect(s, `subject "${SUBJECT}" should be listed`).to.not.be.undefined
      subjectId = s.id
    })
  })

  it('ADD — both registered items now coexist (grade edited + subject)', () => {
    cy.request('/getUserGrade').then((res) => {
      expect(rows(res).some((x) => x.id === gradeId), 'grade still present').to.be.true
    })
    cy.request('/getUserSubject').then((res) => {
      expect(rows(res).some((x) => x.id === subjectId), 'subject still present').to.be.true
    })
  })

  // ─── DELETE ─────────────────────────────────────────────────────────────────
  it('DELETE — removing the grade drops it from getUserGrade', () => {
    expect(gradeId, 'need gradeId').to.exist
    cy.request({ method: 'POST', url: '/deleteGrade', form: true, body: { checked: gradeId } })
    cy.request('/getUserGrade').then((res) => {
      const data = res.body.status === 'SUCCESS' ? rows(res) : []
      expect(data.some((x) => x.id === gradeId), 'grade should be gone after delete').to.be.false
    })
  })

  it('DELETE — removing the subject drops it from getUserSubject', () => {
    expect(subjectId, 'need subjectId').to.exist
    cy.request({ method: 'POST', url: '/deleteSubject', form: true, body: { checked: subjectId } })
    cy.request('/getUserSubject').then((res) => {
      const data = res.body.status === 'SUCCESS' ? rows(res) : []
      expect(data.some((x) => x.id === subjectId), 'subject should be gone after delete').to.be.false
    })
  })

  // ─── SAFETY CLEANUP ─────────────────────────────────────────────────────────
  // If an assertion aborts the flow mid-way, make sure nothing this spec created is left behind.
  after(() => {
    cy.loginAsEducation()
    cy.request({ url: '/getUserGrade', failOnStatusCode: false }).then((res) => {
      rows(res)
        .filter((x) => x.name === GRADE || x.name === GRADE_ED)
        .forEach((x) => cy.request({ method: 'POST', url: '/deleteGrade', form: true, body: { checked: x.id }, failOnStatusCode: false }))
    })
    cy.request({ url: '/getUserSubject', failOnStatusCode: false }).then((res) => {
      rows(res)
        .filter((x) => x.name === SUBJECT)
        .forEach((x) => cy.request({ method: 'POST', url: '/deleteSubject', form: true, body: { checked: x.id }, failOnStatusCode: false }))
    })
  })
})
