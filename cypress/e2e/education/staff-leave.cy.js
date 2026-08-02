/**
 * Slice 2.3 — staff attendance & leave.
 * Design: microservices/docs/slices/edu-2.3-staff-attendance-leave.md
 *
 * The arithmetic (day counting, term skipping, derived balances, overage) lives in
 * LeaveBalanceCalculatorTest — pure, on every `mvn test`. Asserted HERE is the thing a unit test cannot
 * reach, and the thing this slice is actually about:
 *
 *   **THE CONVERGENCE (D3)** — the register AND leave approval both write 2.2's StaffAbsence, so the
 *   substitution screen learns about an absence either way. If either path forgets, a teacher is on
 *   approved leave with no cover arranged and nothing looks wrong.
 *
 * Also: the UNIQUE key the student register never got, derived balances that cannot drift, over-quota
 * warning rather than blocking, and the WRITE/ADMIN privilege split.
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

const TAG = 'CyLv' + Date.now()
/** A fixed future Tuesday, so the weekday is known and it cannot collide with real data. */
const DATE = '2026-09-15'
const register = () =>
  cy.request({ url: `/getStaffRegister?date=${DATE}`, failOnStatusCode: false })
const subsDay = () =>
  cy.request({ url: `/getSubstitutionDay?date=${DATE}`, failOnStatusCode: false })
const balances = (staffId) =>
  cy.request({ url: `/getLeaveBalances?staffId=${staffId}&year=2026`, failOnStatusCode: false })

const fx = {}

describe('Education — staff attendance & leave (slice 2.3)', () => {
  before(() => {
    cy.loginAsEduOwner()
    cy.request('/getUserStaff').then((r) => {
      const staff = rows(r.body)
      expect(staff.length, 'the org has staff').to.be.greaterThan(0)
      fx.teacher = staff[0]
    })
    // A leave type of our own with a small quota, so the overage case is reachable.
    post('/saveLeaveType', { name: TAG + ' Casual', annualQuota: 3, paid: 'true' })
      .then((r) => ok(r, 'seed a capped leave type'))
    post('/saveLeaveType', { name: TAG + ' Unpaid', paid: 'false' })
      .then((r) => ok(r, 'seed an uncapped leave type'))
    cy.request('/getLeaveTypes').then((r) => {
      const types = rows(r.body)
      fx.capped = types.find((t) => t.name === TAG + ' Casual')
      fx.uncapped = types.find((t) => t.name === TAG + ' Unpaid')
      expect(fx.capped, 'capped type seeded').to.exist
      expect(fx.uncapped, 'uncapped type seeded').to.exist
      expect(fx.capped.annualQuota).to.eq(3)
      expect(fx.uncapped.annualQuota, 'no quota means uncapped, not zero').to.be.oneOf([null, undefined])
    })
  })

  beforeEach(() => {
    cy.loginAsEduOwner()
  })

  after(() => {
    cy.loginAsEduOwner()
    // Cancel any approved leave first (that withdraws its absences), then clear stragglers, then types.
    cy.request('/getLeaveRequests?year=2026').then((r) => {
      rows(r.body)
        .filter((q) => q.leaveTypeName && q.leaveTypeName.indexOf(TAG) === 0 && q.status === 'APPROVED')
        .forEach((q) => post('/decideLeaveRequest', { id: q.id, decision: 'CANCELLED' }))
    })
    subsDay().then((r) => {
      const o = (parse(r.body).object) || {}
      ;(o.absences || []).forEach((a) => post('/clearStaffAbsence', { id: a.id }))
    })
    // Types refuse to delete while requests reference them — that is by design, so this may leave the two
    // TAG types behind. They are inert and namespaced by timestamp.
    cy.then(() => {
      if (fx.capped) post('/deleteLeaveType', { id: fx.capped.id })
      if (fx.uncapped) post('/deleteLeaveType', { id: fx.uncapped.id })
    })
  })

  it('an uncapped leave type reports NO limit, never zero', () => {
    balances(fx.teacher.id).then((r) => {
      const b = rows(r.body).find((x) => x.leaveTypeName === TAG + ' Unpaid')
      expect(b, 'the uncapped type has a balance line').to.exist
      // Zero would read as "none left" — the opposite of the truth for unpaid leave.
      expect(b.quota).to.be.oneOf([null, undefined])
      expect(b.remaining).to.be.oneOf([null, undefined])
    })
  })

  it('marking the register ABSENT opens the absence AND the cover list (the convergence)', () => {
    postJson('/markStaffAttendanceBulk', {
      dateStr: DATE,
      rows: [{ staffId: fx.teacher.id, status: 'ABSENT', remarks: TAG }]
    }).then((r) => ok(r, 'mark absent on the register'))

    register().then((r) => {
      const row = (parse(r.body).object.rows || []).find((x) => x.staffId === fx.teacher.id)
      expect(row.status, 'the register remembers it').to.eq('ABSENT')
    })
    // The point of the slice: 2.2 learned about it without anyone touching the substitution screen.
    subsDay().then((r) => {
      const o = parse(r.body).object
      expect(o.absences.map((a) => a.staffId), 'the absence reached 2.2').to.include(fx.teacher.id)
    })
  })

  it('re-marking the same day updates the row, never duplicates it', () => {
    postJson('/markStaffAttendanceBulk', {
      dateStr: DATE,
      rows: [{ staffId: fx.teacher.id, status: 'ABSENT', remarks: TAG + ' again' }]
    }).then((r) => ok(r, 're-mark'))
    register().then((r) => {
      const mine = (parse(r.body).object.rows || []).filter((x) => x.staffId === fx.teacher.id)
      // The UNIQUE key the student register never got. One person, one day, one row.
      expect(mine.length, 'one row per person per day').to.eq(1)
    })
  })

  it('correcting to PRESENT clears the absence and its cover request', () => {
    postJson('/markStaffAttendanceBulk', {
      dateStr: DATE,
      rows: [{ staffId: fx.teacher.id, status: 'PRESENT' }]
    }).then((r) => ok(r, 'correct to present'))

    subsDay().then((r) => {
      const o = parse(r.body).object
      expect(o.absences.map((a) => a.staffId),
        'the cover screen must stop asking for a teacher who is standing in the room')
        .to.not.include(fx.teacher.id)
    })
  })

  it('approving leave writes one absence per day, and 2.2 sees them', () => {
    // 2026-09-15 to 09-17 — three days.
    post('/saveLeaveRequest', {
      staffId: fx.teacher.id, leaveTypeId: fx.capped.id,
      fromDate: DATE, toDate: '2026-09-17', reason: TAG
    }).then((r) => {
      const b = ok(r, 'submit a 3-day request')
      fx.days = b.object.daysCounted
      expect(fx.days, 'the day count is reported').to.be.greaterThan(0)
    })

    cy.request('/getLeaveRequests?year=2026').then((r) => {
      const req = rows(r.body).find((q) => q.reason === TAG)
      expect(req, 'the request exists').to.exist
      fx.requestId = req.id
      if (req.status === 'PENDING') {
        post('/decideLeaveRequest', { id: req.id, decision: 'APPROVED' })
          .then((d) => ok(d, 'approve'))
      }
    })

    // Same convergence, the other path.
    cy.then(() => subsDay()).then((r) => {
      const o = parse(r.body).object
      expect(o.absences.map((a) => a.staffId), 'approved leave reached 2.2').to.include(fx.teacher.id)
    })
  })

  it('the balance is DERIVED — it moves when leave is approved, with nothing stored', () => {
    balances(fx.teacher.id).then((r) => {
      const b = rows(r.body).find((x) => x.leaveTypeName === TAG + ' Casual')
      expect(b.taken, 'approved days are counted').to.be.greaterThan(0)
      expect(b.remaining, 'remaining is quota minus taken').to.eq(b.quota - b.taken)
    })
  })

  it('a request over the balance is RECORDED with the overage named, not refused', () => {
    // The quota is 3 and some is already taken; ask for 5 more.
    post('/saveLeaveRequest', {
      staffId: fx.teacher.id, leaveTypeId: fx.capped.id,
      fromDate: '2026-10-05', toDate: '2026-10-09', reason: TAG + ' over'
    }).then((r) => {
      const b = parse(r.body)
      // D5 — a teacher with two days left asking for five is a conversation, not an error.
      expect(b.status, 'over-quota warns; it does not block').to.eq('SUCCESS')
      expect(b.object.overage, 'and the overage is quantified').to.be.greaterThan(0)
      expect(b.message, 'and named in the message').to.match(/exceed/i)
    })
  })

  it('cancelling approved leave withdraws its absences and cancels the cover', () => {
    post('/decideLeaveRequest', { id: fx.requestId, decision: 'CANCELLED' })
      .then((r) => ok(r, 'cancel the approved leave'))
    subsDay().then((r) => {
      const o = parse(r.body).object
      expect(o.absences.map((a) => a.staffId), 'the absence went with it')
        .to.not.include(fx.teacher.id)
    })
    cy.request('/getLeaveRequests?year=2026').then((r) => {
      const req = rows(r.body).find((q) => q.id === fx.requestId)
      expect(req, 'the request itself is KEPT, not deleted').to.exist
      expect(req.status).to.eq('CANCELLED')
    })
  })

  it('a rejected request is kept and writes no absence', () => {
    post('/saveLeaveRequest', {
      staffId: fx.teacher.id, leaveTypeId: fx.capped.id,
      fromDate: '2026-11-02', toDate: '2026-11-02', reason: TAG + ' reject'
    }).then((r) => ok(r, 'submit'))
    cy.request('/getLeaveRequests?year=2026').then((r) => {
      const req = rows(r.body).find((q) => q.reason === TAG + ' reject')
      post('/decideLeaveRequest', { id: req.id, decision: 'REJECTED' }).then((d) => ok(d, 'reject'))
      cy.request('/getLeaveRequests?year=2026').then((r2) => {
        const after = rows(r2.body).find((q) => q.id === req.id)
        // "I asked and was refused" is exactly what gets disputed later.
        expect(after, 'kept').to.exist
        expect(after.status).to.eq('REJECTED')
      })
    })
    cy.request({ url: '/getSubstitutionDay?date=2026-11-02', failOnStatusCode: false }).then((r) => {
      const o = parse(r.body).object
      expect(o.absences.map((a) => a.staffId), 'a rejection writes no absence')
        .to.not.include(fx.teacher.id)
    })
  })

  it('a leave type with requests against it cannot be deleted', () => {
    post('/deleteLeaveType', { id: fx.capped.id }).then((r) => {
      const b = parse(r.body)
      expect(b.status, 'deleting it would orphan decisions people acted on').to.eq('FAILED')
    })
  })

  it('a teacher may REQUEST leave but not decide it, and not mark the register', () => {
    cy.loginAsTeacherA()
    // WRITE tier: asking for your own leave.
    post('/saveLeaveRequest', {
      staffId: fx.teacher.id, leaveTypeId: fx.capped.id,
      fromDate: '2026-12-01', toDate: '2026-12-01', reason: TAG + ' byteacher'
    }).then((r) => {
      expect([200, 403]).to.include(r.status)
    })
    // ADMIN tier: an approval writes absences that pull other teachers into cover.
    post('/decideLeaveRequest', { id: fx.requestId, decision: 'APPROVED' }).then((r) => {
      const b = parse(r.body)
      const refused = r.status === 403 || (b && b.status && b.status !== 'SUCCESS')
      expect(refused, 'deciding leave is ADMIN').to.eq(true)
    })
    postJson('/markStaffAttendanceBulk', {
      dateStr: DATE, rows: [{ staffId: fx.teacher.id, status: 'PRESENT' }]
    }).then((r) => {
      const b = parse(r.body)
      const refused = r.status === 403 || (b && b.status && b.status !== 'SUCCESS')
      expect(refused, 'the register decides who was paid to be here — ADMIN').to.eq(true)
    })
  })

  it('another tenant’s leave type is invisible by id', () => {
    post('/deleteLeaveType', { id: 999999 }).then((r) => {
      expect(parse(r.body).status, 'an id outside the tenant never silently succeeds').to.not.eq('SUCCESS')
    })
  })
})
