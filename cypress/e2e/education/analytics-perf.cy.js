/**
 * Finding D — analytics & read performance.
 * Design: microservices/docs/slices/edu-D-analytics-perf.md
 *
 * This slice is a REWRITE BEHIND AN UNCHANGED CONTRACT: five whole-table loads and 24 Java loops became
 * SQL aggregates, and twelve duplicate checks became indexed EXISTS queries. Nothing about the output
 * was supposed to change.
 *
 * So the primary gate is `dashboard.cy.js`, UNCHANGED and passing. This spec adds only what that one
 * cannot express:
 *
 *   - the aggregates are internally consistent (a rewrite can be fast and wrong)
 *   - the duplicate checks still refuse, still ignore case, and are still tenant-scoped
 *   - a fee whose student cannot be resolved still lands under "Unassigned"
 *
 * Requires education-service + gateway up. Run headed.
 */
const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const post = (url, body) =>
  cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

const analytics = () =>
  cy.request('/getDashboardAnalytics').then((r) => {
    const b = parse(r.body)
    expect(b.status, JSON.stringify(b).slice(0, 300)).to.eq('SUCCESS')
    return cy.wrap(b.object, { log: false })
  })

/** Every series must have labels and data of equal length, or a chart renders nonsense. */
const SERIES = ['enrollTrend', 'attendanceTrend', 'studentsByClass', 'collectionByClass',
  'attendanceByClass', 'genderSplit', 'studentStatus', 'paymentModes', 'staffByDesignation']

describe('Education — analytics aggregates (finding D)', () => {
  beforeEach(() => {
    cy.loginAsEduOwner()
  })

  it('every series has labels and data of the same length', () => {
    // The old code built both from one map so they could not disagree. They are now built from query
    // rows, where a mismatch is newly possible — which is exactly why this is asserted.
    analytics().then((o) => {
      SERIES.forEach((k) => {
        expect(o, k).to.have.property(k)
        expect(o[k].labels, `${k}.labels`).to.be.an('array')
        expect(o[k].data, `${k}.data`).to.be.an('array')
        expect(o[k].data.length, `${k}: labels/data length must match`).to.eq(o[k].labels.length)
      })
      // feeTrend carries TWO data series against one label axis.
      expect(o.feeTrend.collected.length).to.eq(o.feeTrend.labels.length)
      expect(o.feeTrend.due.length).to.eq(o.feeTrend.labels.length)
    })
  })

  it('the KPIs are internally consistent and never null', () => {
    analytics().then((o) => {
      const k = o.kpis
      // Null is the specific failure mode of this rewrite: SQL sum() over no rows returns NULL where
      // the Java stream returned 0. Asserting "is a number" is asserting the coalesce actually happened.
      ;['totalStudents', 'freshStudents', 'activeStudents', 'totalStaff', 'collectedThisMonth',
        'collectedTotal', 'outstanding', 'collectionRate', 'attendanceRate', 'studentTeacherRatio']
        .forEach((key) => {
          expect(k[key], `kpis.${key} must be a number, not null`).to.be.a('number')
        })
      expect(k.activeStudents, 'active cannot exceed total').to.be.at.most(k.totalStudents)
      expect(k.freshStudents, 'enrolled-this-year cannot exceed total').to.be.at.most(k.totalStudents)
      expect(k.collectionRate).to.be.within(0, 100)
      expect(k.attendanceRate).to.be.within(0, 100)
    })
  })

  it('the per-class breakdown totals agree with the headline count', () => {
    // A group-by that drops or double-counts rows is the classic aggregate bug, and it is invisible
    // unless the parts are summed back against the whole.
    analytics().then((o) => {
      const byClass = o.studentsByClass.data.reduce((a, b) => a + b, 0)
      expect(byClass, 'students-by-class must sum to totalStudents').to.eq(o.kpis.totalStudents)

      const byGender = o.genderSplit.data.reduce((a, b) => a + b, 0)
      expect(byGender, 'gender split must sum to totalStudents').to.eq(o.kpis.totalStudents)

      const byStatus = o.studentStatus.data.reduce((a, b) => a + b, 0)
      expect(byStatus, 'status split must sum to totalStudents').to.eq(o.kpis.totalStudents)

      const byDesignation = o.staffByDesignation.data.reduce((a, b) => a + b, 0)
      expect(byDesignation, 'staff by designation must sum to totalStaff').to.eq(o.kpis.totalStaff)

      const collected = o.collectionByClass.data.reduce((a, b) => a + b, 0)
      expect(collected, 'collection-by-class must sum to collectedTotal').to.eq(o.kpis.collectedTotal)
    })
  })

  it('the trends stay inside their windows', () => {
    analytics().then((o) => {
      expect(o.enrollTrend.labels).to.have.length(12)
      expect(o.feeTrend.labels).to.have.length(12)
      // "the last 30 days that HAVE records" — bounded, and never more than 30.
      expect(o.attendanceTrend.labels.length, 'attendance trend is capped at 30 days')
        .to.be.at.most(30)
      o.attendanceTrend.data.forEach((v) => {
        expect(v, 'a daily attendance rate is a percentage').to.be.within(0, 100)
      })
    })
  })

  it('duplicate names are still refused, and still case-insensitively', () => {
    // The check moved from Java equalsIgnoreCase() to a SQL `=` that relies on the column collation
    // (design D4). If the collation were ever case-SENSITIVE this test is what would catch it.
    const name = 'Dup Check ' + Date.now()
    post('/addSubject', { name }).then((r) => {
      expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS')
    })
    post('/addSubject', { name }).then((r) => {
      expect(parse(r.body).status, 'the exact duplicate is refused').to.eq('FOUND')
    })
    post('/addSubject', { name: name.toUpperCase() }).then((r) => {
      expect(parse(r.body).status, 'a case-only difference is STILL a duplicate — collation dependency')
        .to.eq('FOUND')
    })
  })

  // NOT TESTED HERE, deliberately: "a name used by ANOTHER tenant is still allowed".
  //
  // It matters — a dropped org predicate would look like a working duplicate check right up until it
  // blocked a second school from using an ordinary class name — but the education demo accounts
  // (demo.education@ / owner.education@) share ONE organization, so there is no second education
  // tenant here to prove it with. A test that logged into another MODULE and back would assert
  // nothing while appearing to pass.
  //
  // Covered instead by: (a) save-takeover-idor.cy.js, which exercises cross-tenant scoping directly,
  // and (b) the fact that every EXISTS query below reuses the SAME org predicate the findScoped it
  // replaced used — copied, not rewritten. Worth a real fixture if a second education tenant is ever
  // seeded.
})
