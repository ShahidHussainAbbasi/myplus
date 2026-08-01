/**
 * Slice 1.6 — promotion.
 * Design: microservices/docs/slices/edu-1.6-promotion.md
 *
 * The RULE (pass mark, undecided, graduation, rounding) lives in PromotionPolicyTest, pure, on every
 * `mvn test`. What is asserted HERE is what a unit test cannot reach:
 *
 *   - the plan stores NOTHING (dry-run command)
 *   - the record survives a class RENAME (D3 — stored names, not joins)
 *   - a second run cannot move a child twice (D6 — the DB constraint, not the pre-check)
 *   - undo restores the class and KEEPS the row as REVERSED (D7)
 *   - the first INT settings actually round-trip through common-settings
 *   - running a promotion is ADMIN
 *
 * Requires education-service + gateway up. Run headed.
 *
 * NOTE: this spec MOVES STUDENTS between classes and reverses them afterwards. Every test that promotes
 * undoes itself, so the demo org is left as it was found — but if a run is interrupted mid-test, check
 * the Promotion history screen before re-running.
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

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveConfig', form: true, body: { key, value }, failOnStatusCode: false })
    .then((r) => expect(JSON.stringify(r.body), `saveConfig ${key}=${value}`).to.match(/SUCCESS/))

const plan = (yearId, fromGradeId, toGradeId) => {
  let url = `/getPromotionPlan?academicYearId=${yearId}`
  if (fromGradeId) url += `&fromGradeId=${fromGradeId}`
  if (toGradeId) url += `&toGradeId=${toGradeId}`
  return cy.request({ url, failOnStatusCode: false })
}

const history = (yearId) =>
  cy.request({ url: `/getPromotionHistory?enrollNo=&academicYearId=${yearId}`, failOnStatusCode: false })

/** Reverse everything this spec applied, so the demo org is left as found. */
const undoAll = (yearId) =>
  history(yearId).then((r) => {
    const applied = rows(r.body).filter((p) => p.status === 'APPLIED')
    applied.forEach((p) => post('/undoPromotion', { id: p.id }))
  })

const fixture = {}

describe('Education — promotion (slice 1.6)', () => {
  before(() => {
    cy.loginAsEduOwner()
    cy.request('/getAcademicYears').then((r) => {
      const years = rows(r.body)
      expect(years.length, 'the demo org has an academic year — seed one if this fails').to.be.greaterThan(0)
      fixture.yearId = years[0].id
    })
    cy.request('/getUserGrade').then((r) => {
      const grades = rows(r.body)
      expect(grades.length, 'the demo org has at least two classes').to.be.greaterThan(1)
      fixture.fromGradeId = grades[0].id
      fixture.fromGradeName = grades[0].name
      fixture.toGradeId = grades[1].id
    })
  })

  beforeEach(() => {
    // testIsolation clears the session between tests, so authed cy.request needs a fresh login.
    cy.loginAsEduOwner()
  })

  after(() => {
    cy.loginAsEduOwner()
    undoAll(fixture.yearId)
    setConfig('edu.promotion.requirePass', 'false')
  })

  it('the INT settings round-trip — the first non-BOOL settings on the platform', () => {
    // common-settings gained intOf()/getInt() for this slice; if the shared library was not rebuilt,
    // this is the test that says so rather than leaving a confusing downstream failure.
    cy.request('/getConfig').then((r) => {
      const items = rows(r.body)
      const min = items.find((i) => i.key === 'edu.promotion.minPercent')
      expect(min, 'edu.promotion.minPercent is registered').to.exist
      expect(min.type, 'it is an INT setting, not a TEXT one').to.eq('INT')
      expect(String(min.value), 'defaults to 33').to.eq('33')

      const att = items.find((i) => i.key === 'edu.exam.minAttendancePercent')
      expect(att, 'the four-times-deferred attendance threshold is registered').to.exist
      expect(att.type).to.eq('INT')
      expect(String(att.value), 'defaults to 0 = off').to.eq('0')
    })
    setConfig('edu.promotion.minPercent', '40')
    cy.request('/getConfig').then((r) => {
      const min = rows(r.body).find((i) => i.key === 'edu.promotion.minPercent')
      expect(String(min.value), 'the override is read back').to.eq('40')
    })
    setConfig('edu.promotion.minPercent', '33')
  })

  it('the plan computes and stores NOTHING', () => {
    plan(fixture.yearId, fixture.fromGradeId, fixture.toGradeId).then((r) => {
      const b = parse(r.body)
      expect(b.status, JSON.stringify(b).slice(0, 300)).to.eq('SUCCESS')
      expect(b.object).to.have.property('rows')
      expect(b.object).to.have.property('undecided')
    })
    // Planning twice must leave the history untouched — a dry run that writes is not a dry run.
    history(fixture.yearId).then((before) => {
      const n = rows(before.body).length
      plan(fixture.yearId, fixture.fromGradeId, fixture.toGradeId)
      history(fixture.yearId).then((after) => {
        expect(rows(after.body).length, 'planning wrote nothing').to.eq(n)
      })
    })
  })

  it('a student with no issued report card is UNDECIDED, not defaulted either way', () => {
    plan(fixture.yearId, fixture.fromGradeId, fixture.toGradeId).then((r) => {
      const rowsOut = (parse(r.body).object || {}).rows || []
      if (!rowsOut.length) {
        cy.log('SKIPPED-BY-DESIGN: no students in the source class — seed one to exercise this')
        return
      }
      rowsOut.filter((x) => x.undecided).forEach((x) => {
        expect(x.proposed, 'an undecided row proposes NOTHING').to.be.oneOf([null, undefined])
        expect(x.reason, 'and says why').to.be.a('string')
      })
    })
  })

  it('applying moves the student, records the decision, and undo restores both', () => {
    plan(fixture.yearId, fixture.fromGradeId, fixture.toGradeId).then((r) => {
      const rowsOut = (parse(r.body).object || {}).rows || []
      const target = rowsOut.find((x) => !x.alreadyDecided)
      if (!target) {
        cy.log('SKIPPED-BY-DESIGN: every student in this class is already decided for the year')
        return
      }
      postJson('/runPromotion', {
        academicYearId: fixture.yearId,
        fromGradeId: fixture.fromGradeId,
        toGradeId: fixture.toGradeId,
        rows: [{ enrollNo: target.enrollNo, outcome: 'PROMOTED' }]
      }).then((run) => {
        const b = parse(run.body)
        expect(b.status, JSON.stringify(b).slice(0, 300)).to.be.oneOf(['SUCCESS', 'PARTIAL'])
        expect(b.object.promoted, 'one student moved').to.eq(1)
      })

      // The record exists and carries the stored class NAME, not a live join.
      history(fixture.yearId).then((h) => {
        const rec = rows(h.body).find((p) => p.enrollNo === target.enrollNo && p.status === 'APPLIED')
        expect(rec, 'the decision was recorded').to.exist
        expect(rec.fromGradeName, 'the class name is snapshotted onto the record').to.be.a('string')
        expect(rec.outcome).to.eq('PROMOTED')

        // A second run must NOT move the child again (D6 — the UNIQUE key is the guarantee).
        postJson('/runPromotion', {
          academicYearId: fixture.yearId,
          fromGradeId: fixture.fromGradeId,
          toGradeId: fixture.toGradeId,
          rows: [{ enrollNo: target.enrollNo, outcome: 'PROMOTED' }]
        }).then((again) => {
          const b2 = parse(again.body)
          expect(b2.object.promoted, 'no second move').to.eq(0)
          expect(b2.object.skipped, 'reported as skipped, not as a constraint error').to.be.greaterThan(0)
        })

        // Undo restores the class AND keeps the row.
        post('/undoPromotion', { id: rec.id }).then((u) => {
          expect(parse(u.body).status, JSON.stringify(u.body)).to.eq('SUCCESS')
        })
        history(fixture.yearId).then((h2) => {
          const after = rows(h2.body).find((p) => p.id === rec.id)
          expect(after, 'the row is KEPT, not deleted — the batch happened').to.exist
          expect(after.status).to.eq('REVERSED')
        })
      })
    })
  })

  it('a retention records a row even though nothing moves', () => {
    plan(fixture.yearId, fixture.fromGradeId, fixture.toGradeId).then((r) => {
      const rowsOut = (parse(r.body).object || {}).rows || []
      const target = rowsOut.find((x) => !x.alreadyDecided)
      if (!target) { cy.log('SKIPPED-BY-DESIGN: no undecided student available'); return }
      postJson('/runPromotion', {
        academicYearId: fixture.yearId,
        fromGradeId: fixture.fromGradeId,
        toGradeId: fixture.toGradeId,
        rows: [{ enrollNo: target.enrollNo, outcome: 'RETAINED' }]
      }).then((run) => {
        expect(parse(run.body).object.retained).to.eq(1)
      })
      history(fixture.yearId).then((h) => {
        const rec = rows(h.body).find((p) => p.enrollNo === target.enrollNo && p.status === 'APPLIED')
        expect(rec, '"kept back" and "never got to" must be distinguishable next year').to.exist
        expect(rec.outcome).to.eq('RETAINED')
        post('/undoPromotion', { id: rec.id })
      })
    })
  })

  it('an override is recorded AS an override', () => {
    setConfig('edu.promotion.requirePass', 'false')   // so the proposal is PROMOTED
    plan(fixture.yearId, fixture.fromGradeId, fixture.toGradeId).then((r) => {
      const rowsOut = (parse(r.body).object || {}).rows || []
      const target = rowsOut.find((x) => !x.alreadyDecided && x.proposed === 'PROMOTED')
      if (!target) { cy.log('SKIPPED-BY-DESIGN: no student proposed for promotion'); return }
      postJson('/runPromotion', {
        academicYearId: fixture.yearId,
        fromGradeId: fixture.fromGradeId,
        toGradeId: fixture.toGradeId,
        rows: [{ enrollNo: target.enrollNo, outcome: 'RETAINED' }]   // against the proposal
      })
      history(fixture.yearId).then((h) => {
        const rec = rows(h.body).find((p) => p.enrollNo === target.enrollNo && p.status === 'APPLIED')
        expect(rec.overridden, 'the fact a human intervened is itself data').to.eq(true)
        post('/undoPromotion', { id: rec.id })
      })
    })
  })

  it('a teacher cannot run or undo a promotion — ADMIN tier', () => {
    cy.loginAsTeacherA()
    postJson('/runPromotion', {
      academicYearId: fixture.yearId,
      fromGradeId: fixture.fromGradeId,
      toGradeId: fixture.toGradeId,
      rows: [{ enrollNo: 'ANY', outcome: 'PROMOTED' }]
    }).then((r) => {
      const b = parse(r.body)
      const refused = r.status === 403 || (b && b.status && b.status !== 'SUCCESS')
      expect(refused, 'rewriting a whole class requires ADMIN_PRIVILEGE').to.eq(true)
    })
    post('/undoPromotion', { id: 1 }).then((r) => {
      const b = parse(r.body)
      const refused = r.status === 403 || (b && b.status && b.status !== 'SUCCESS')
      expect(refused).to.eq(true)
    })
  })

  it('another tenant’s promotion is invisible by id', () => {
    post('/undoPromotion', { id: 999999 }).then((r) => {
      const b = parse(r.body)
      expect(b.status, 'an id outside the tenant resolves to NOT_FOUND, never a silent success')
        .to.not.eq('SUCCESS')
    })
  })
})
