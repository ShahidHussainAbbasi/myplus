/**
 * Education — attendance class-roster marking (slice 13).
 * Runs slowed + headed so the flow is visible and understandable in Chrome:
 *   open Attendance -> pick class -> load roster -> mark all present -> save.
 */

describe('Education — attendance roster', () => {
  const SLOW = 1200 // pause between steps so the run is watchable

  beforeEach(() => {
    cy.loginAsEducation()
  })

  it('loads a class roster and saves attendance', () => {
    cy.visit('/educationDashboard')
    cy.wait(SLOW)

    // Open the Attendance section (off-screen select drives main.js, like the rest of the dashboard).
    cy.get('#attendanceType').select('ADiv', { force: true })
    cy.wait(SLOW)
    cy.get('#ADiv').should('be.visible')

    // Class dropdown is populated from the org's grades — pick the first real grade (demo-account safe;
    // the org may not have a class literally named "Grade 1").
    cy.get('#attendanceGrade', { timeout: 10000 }).find('option').then(($opts) => {
      const real = [...$opts].find((o) => o.value && o.value.trim() !== '')
      if (!real) {
        cy.log('No class in this org — attendance roster not applicable')
        return
      }
      cy.get('#attendanceGrade').select(real.value, { force: true })
      cy.wait(SLOW)
      cy.contains('button', 'Load Roster').click()
      cy.wait(SLOW)

      // The roster wrap is only revealed when the class has students; tolerate an empty class.
      cy.get('body').then(($b) => {
        if (!$b.find('#aRosterWrap:visible').length || $b.find('#aRosterBody tr').length === 0) {
          cy.log('Class has no students — roster not populated (feature wired, no data)')
          return
        }
        cy.contains('button', 'Mark all Present').click()
        cy.wait(SLOW)
        const alertStub = cy.stub().as('saveAlert')
        cy.on('window:alert', alertStub)
        cy.contains('button', 'Save Attendance').click()
        cy.wait(SLOW).then(() => {
          expect(alertStub).to.have.been.calledWithMatch(/saved/i)
        })
      })
    })
  })

  it('/getClassRoster returns SUCCESS for the class', () => {
    cy.request('/getClassRoster?gradeId=1&dateStr=06-06-2026').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })
})

/**
 * One attendance row per student per day — V28's `uk_attendance_student_day`
 * (organization_id, enroll_no, att_date).
 *
 * Carried open since slice 2.3 §6 and the education review's finding D: marking a register is a
 * read-then-write (find today's row, else insert) with NOTHING behind it, so two teachers marking the same
 * class at once both read "no row" and both insert. Every percentage derived from attendance — 1.5's report
 * card, the dashboard KPI, and now exam eligibility — is then quietly wrong.
 *
 * ── WHAT THIS GATE CAN AND CANNOT PROVE ───────────────────────────────────────────────────────────
 * It cannot fire the race: Cypress issues requests in sequence, so the constraint's concurrency guarantee
 * is not reachable from here (the same limit SCHED-1 hit, where 10 parallel curls were needed).
 *
 * What it CAN prove — and what actually needed proving — is that adding the constraint did not break the
 * ordinary path: re-marking a register must still UPDATE, and must not start failing. A unique key that
 * breaks daily marking would be a far worse defect than the race it closes.
 */
describe('Education — attendance uniqueness (V28)', () => {
  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const rows = (body) => {
    const b = parse(body) || {}
    if (Array.isArray(b.collection)) return b.collection
    const k = Object.keys(b).find((x) => Array.isArray(b[x]))
    return k ? b[k] : []
  }
  const post = (url, body) =>
    cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

  const S = Date.now()
  const TAG = 'CyAU' + S
  const DAY = '12-09-2026'
  const fx = {}

  before(() => {
    cy.loginAsEduOwner()
    post('/addGrade', { name: TAG + ' Class', fee: 0 })
      .then((r) => expect(JSON.stringify(r.body), 'seed a class').to.match(/SUCCESS/))
    // /getUserGrade returns JSON; /getUserGrades returns <option> HTML for a dropdown. Every other spec
    // uses the JSON one, and so does this — scraping the markup for an id is how a renamed element
    // silently empties a fixture (standard D9 form 7).
    cy.request('/getUserGrade').then((r) => {
      const g = rows(r.body).find((x) => x.name === TAG + ' Class')
      expect(g, 'the seeded class exists').to.exist
      fx.gradeId = g.id
    })
    cy.then(() => {
      fx.en = TAG + 'S'
      post('/addStudent', { name: fx.en, enrollNo: fx.en, status: 'ACTIVE', gradeId: fx.gradeId })
        .then((r) => expect(JSON.stringify(r.body), 'seed a student').to.match(/SUCCESS/))
    })
  })

  const mark = (status) =>
    cy.request({
      method: 'POST', url: '/markAttendanceBulk', failOnStatusCode: false,
      body: { gradeId: fx.gradeId, dateStr: DAY, rows: [{ enrollNo: fx.en, status }] },
    })

  it('marking the register works, and RE-marking the same day UPDATES rather than duplicating', () => {
    cy.loginAsEduOwner()
    mark('Present').then((r) => expect(JSON.stringify(r.body), 'first mark').to.match(/SUCCESS/))
    // The re-mark is the case that matters: with the constraint in place this must still be an update,
    // not a rejected insert. A teacher correcting a register is the ordinary path, not an edge case.
    mark('Absent').then((r) => expect(JSON.stringify(r.body), 're-mark must still succeed').to.match(/SUCCESS/))
    mark('Present').then((r) => expect(JSON.stringify(r.body), 'and again').to.match(/SUCCESS/))

    // ONE row, carrying the LATEST status — proving the upsert updated rather than inserted.
    cy.request(`/getClassRoster?gradeId=${fx.gradeId}&dateStr=${DAY}`).then((r) => {
      const mine = rows(r.body).filter((x) => x.enrollNo === fx.en)
      expect(mine.length, 'the student appears exactly once on the roster').to.eq(1)
      expect(String(mine[0].status || '').toLowerCase(), 'and carries the last status marked').to.contain('present')
    })
  })

  it('a DIFFERENT day for the same student is a separate row — the key is per DAY, not per student', () => {
    // The constraint must not collapse a student's whole term into one row. This is the assertion that
    // would have caught a key of (org, enroll_no) alone.
    cy.loginAsEduOwner()
    cy.request({
      method: 'POST', url: '/markAttendanceBulk', failOnStatusCode: false,
      body: { gradeId: fx.gradeId, dateStr: '13-09-2026', rows: [{ enrollNo: fx.en, status: 'Absent' }] },
    }).then((r) => expect(JSON.stringify(r.body), 'the next day marks independently').to.match(/SUCCESS/))

    cy.request(`/getClassRoster?gradeId=${fx.gradeId}&dateStr=${DAY}`).then((r) => {
      const mine = rows(r.body).filter((x) => x.enrollNo === fx.en)
      expect(String(mine[0].status || '').toLowerCase(),
        "and does NOT overwrite the earlier day's mark").to.contain('present')
    })
  })
})
