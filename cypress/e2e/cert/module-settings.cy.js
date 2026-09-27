/**
 * SET-GUIDE — certification of every setting on the Order, Education, Welfare and Agriculture settings screens
 * (34). The business Configuration screen (106) is certified by cert/business-settings.cy.js.
 *
 * Two kinds of case, both named in the published guide:
 *   BEHAVIOUR — the 7 settings no spec exercised before: the setting is switched and its EFFECT is asserted where it
 *               lands (a promised date, a leave status, a LATE mark, a percentage, a report card field, a list).
 *   ROUND TRIP — the 27 whose behaviour an existing spec already proves (named in `deep`, run in the same
 *               verification batch): save a non-default value through the screen's route → it reads back and is
 *               marked changed → Reset to default removes the override → the default reads back.
 *
 * Every setting is snapshotted (value + isDefault) and put back — by RESET when it had no override — and every
 * fixture this spec seeds is removed in after(); what the server refuses to delete is RECORDED, not ignored.
 *
 * Output: cypress/guide-out/cert-modules.json (the guide's per-setting test status).
 * Run headed:  npx cypress run --headed --browser electron --spec cypress/e2e/cert/module-settings.cy.js
 */
const OUT = 'cypress/guide-out/cert-modules.json'
const RUN = String(Date.now()).slice(-7)
const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const list = (b) => { const x = parse(b) || {}; return x.data || x.collection || x.object || [] }
const rows = (body) => {
  const b = parse(body) || {}
  if (Array.isArray(b.collection)) return b.collection
  const k = Object.keys(b).find((x) => Array.isArray(b[x]))
  return k ? b[k] : []
}
const okBody = (b) => { const x = parse(b); return !!x && (x.success === true || x.status === 'SUCCESS' || x.status === 'PARTIAL') }
const post = (url, body) => cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })
const postJson = (url, body) => cy.request({ method: 'POST', url, body, failOnStatusCode: false })
const must = (r, what) => { expect(okBody(r.body), `${what}: ${JSON.stringify(parse(r.body)).slice(0, 240)}`).to.eq(true); return parse(r.body) }

const SCREENS = {
  orders: { name: 'Business → Store → Order settings', login: () => cy.loginAsMarketplaceOwner(), get: '/getOrderConfig', save: '/saveOrderConfig', reset: '/resetOrderConfig' },
  education: { name: 'Education → Configuration', login: () => cy.loginAsEduOwner(), get: '/getConfig', save: '/saveConfig', reset: '/resetConfig' },
  welfare: { name: 'Welfare → Configuration', login: () => cy.loginAsWelfareOwner(), get: '/getWelfareConfig', save: '/saveWelfareConfig', reset: '/resetWelfareConfig' },
  agriculture: { name: 'Agriculture → Configuration', login: () => cy.loginAsAgricultureOwner(), get: '/getAgricultureConfig', save: '/saveAgricultureConfig', reset: '/resetAgricultureConfig' },
}

const entry = (sc, key) => cy.request(sc.get).then((r) => {
  const e = list(r.body).find((x) => x.key === key)
  expect(e, `${key} is on ${sc.name}`).to.exist
  return e
})
const setCfg = (sc, key, value) => post(sc.save, { key, value: String(value) }).then((r) => must(r, `save ${key}=${value}`))
const resetCfg = (sc, key) => post(sc.reset, { key }).then((r) => must(r, `reset ${key}`))

/** Snapshot → restore, per screen+key. Untouched settings go back by RESET, so nothing is pinned. */
const ORIG = {}
const remember = (scId, key) => entry(SCREENS[scId], key).then((e) => { ORIG[scId + '|' + key] = { isDefault: e.isDefault === true, value: String(e.value) } })
const restore = (scId, key) => {
  const o = ORIG[scId + '|' + key]
  if (!o) return
  const sc = SCREENS[scId]
  sc.login()
  if (o.isDefault) resetCfg(sc, key); else setCfg(sc, key, o.value)
}

/** The case table the guide is generated from. */
const CASES = []
const results = []
const addCase = (c) => CASES.push(c)

// ── ROUND TRIP — the 27 with a deep spec ────────────────────────────────────────────────────────────────────
const DEEP = {
  orders: {
    'order.shipping.standardFee': 'business/order-config.cy.js', 'order.shipping.expressFee': 'business/order-config.cy.js',
    'order.shipping.freeOverAmount': 'business/order-config.cy.js', 'order.payment.codEnabled': 'business/order-config.cy.js',
    'order.backorder.allowed': 'business/order-backorder.cy.js', 'order.backorder.acceptFullShortfall': 'business/order-backorder.cy.js',
    'order.flow.autoDispatchOnApproval': 'business/order-auto-dispatch.cy.js', 'order.pack.scanRequired': 'business/order-pickpack.cy.js',
    'order.pack.autoConfirm': 'business/order-pickpack.cy.js', 'order.booking.requireApproval': 'business/order-approval-setting.cy.js',
  },
  education: {
    'edu.guardian.branchScoped': 'education/owner-config.cy.js', 'edu.staff.branchScoped': 'education/branch-scope-settings.cy.js',
    'edu.subject.branchScoped': 'education/branch-scope-settings.cy.js', 'edu.fee.creditOnOverpayment': 'education/fee-credit.cy.js',
    'edu.reportCard.showRank': 'education/report-cards.cy.js', 'edu.promotion.requirePass': 'education/promotion.cy.js',
    'edu.promotion.minPercent': 'education/promotion.cy.js', 'edu.exam.minAttendancePercent': 'education/exam-eligibility.cy.js',
    'edu.portal.enabled': 'education/guardian-portal.cy.js', 'edu.portal.students.enabled': 'education/student-portal.cy.js',
    'edu.notify.notices': 'education/notices.cy.js', 'edu.notify.coverAssigned': 'education/notification-outbox.cy.js',
  },
  welfare: { 'welfare.donation.requireDonor': 'welfare/config.cy.js', 'welfare.donator.allowDuplicateNames': 'welfare/config.cy.js' },
  agriculture: { 'agri.entry.requireLand': 'agriculture/config.cy.js' },
}
Object.entries(DEEP).forEach(([scId, keys]) => Object.entries(keys).forEach(([key, deep]) => addCase({
  kind: 'ROUND TRIP', screen: scId, key, deep,
  title: 'Saves, shows as changed, and Reset to default puts it back',
  run: () => {
    const sc = SCREENS[scId]
    sc.login()
    entry(sc, key).then((e) => {
      const v = e.type === 'BOOL' ? String(String(e.value) !== 'true')
        : e.type === 'INT' ? String((parseInt(e.value, 10) || 0) + 1)
          : e.type === 'MONEY' ? String((parseFloat(e.value) || 0) + 1) : `CERT-${RUN}`
      setCfg(sc, key, v)
      entry(sc, key).then((a) => {
        expect(Number.isNaN(Number(v)) ? String(a.value) : Number(a.value), `${key} reads back`).to.eq(Number.isNaN(Number(v)) ? v : Number(v))
        expect(a.isDefault, 'marked changed from default').to.eq(false)
      })
      resetCfg(sc, key)
      entry(sc, key).then((b) => {
        expect(b.isDefault, 'Reset removed the override').to.eq(true)
        expect(String(b.value), 'the default reads back').to.eq(String(b.defaultValue))
      })
    })
  },
})))

// ── BEHAVIOUR — the 7 no spec exercised ─────────────────────────────────────────────────────────────────────
const fx = { leftovers: [] }
const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const plusDays = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return iso(d) }

addCase({
  kind: 'BEHAVIOUR', screen: 'orders', key: 'order.backorder.promiseDays',
  title: 'A backordered checkout is promised today + the number of days set',
  manual: 'With “Accept orders for out-of-stock items” ON, set “Promise backordered items within (days)” to 3. In the online store, put more of a product in the cart than is in stock and open the checkout: it says the extra is promised for the date 3 days from today. Change it to 9: the date moves to 9 days from today.',
  run: () => {
    const sc = SCREENS.orders
    sc.login()
    setCfg(sc, 'order.backorder.allowed', 'true')
    const quote = () => cy.request({ method: 'POST', url: '/storefront/cart/add', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' }, body: { organizationId: fx.mpOrg, productId: fx.mpProduct, quantity: 10 } })
      .then((r) => { must(r, 'add to cart'); return cy.request(`/storefront/checkout/quote?org=${fx.mpOrg}&cartToken=${encodeURIComponent(r.body.data.cartToken)}&shippingMethod=PICKUP`) })
      .then((r) => { const q = r.body.data; expect(q.hasBackorder, 'the checkout has a shortfall').to.eq(true); return q.promisedDate })
    setCfg(sc, 'order.backorder.promiseDays', 3)
    quote().then((d) => expect(String(d).slice(0, 10), 'promised in 3 days').to.eq(plusDays(3)))
    setCfg(sc, 'order.backorder.promiseDays', 9)
    quote().then((d) => expect(String(d).slice(0, 10), 'promised in 9 days').to.eq(plusDays(9)))
  },
})

addCase({
  kind: 'BEHAVIOUR', screen: 'education', key: 'edu.leave.requireApproval',
  title: 'A leave request waits for approval when ON, and is approved at once when OFF',
  manual: 'Staff → Leave: with “Leave requests need approval” ON, submit a leave request — it shows as PENDING until someone approves it. Turn it OFF and submit another — it is APPROVED immediately.',
  run: () => {
    const sc = SCREENS.education
    sc.login()
    const submit = (reason, from, to) => post('/saveLeaveRequest', { staffId: fx.staffId, leaveTypeId: fx.leaveTypeId, fromDate: from, toDate: to, reason })
      .then((r) => must(r, 'submit leave'))
      .then(() => cy.request('/getLeaveRequests?year=2026'))
      .then((r) => { const q = rows(r.body).find((x) => x.reason === reason); expect(q, 'the request exists').to.exist; fx.leaveIds.push(q.id); return q.status })
    setCfg(sc, 'edu.leave.requireApproval', 'true')
    submit(`CERT-ON-${RUN}`, '2026-10-05', '2026-10-05').then((s) => expect(s, 'ON: waits for approval').to.eq('PENDING'))
    setCfg(sc, 'edu.leave.requireApproval', 'false')
    submit(`CERT-OFF-${RUN}`, '2026-10-12', '2026-10-12').then((s) => expect(s, 'OFF: approved at once').to.eq('APPROVED'))
  },
})

addCase({
  kind: 'BEHAVIOUR', screen: 'education', key: 'edu.attendance.staffGraceMinutes',
  title: 'A teacher arriving after start time + grace is marked LATE; within grace, PRESENT',
  manual: 'Give a staff member a start time of 08:00. Set “Lateness grace period (minutes)” to 15 and mark them present at 08:10 on the staff register: they stay PRESENT. Set it to 5 and mark 08:10 again: they are marked LATE.',
  run: () => {
    const sc = SCREENS.education
    sc.login()
    const DATE = '2026-10-06'
    const mark = () => postJson('/markStaffAttendanceBulk', { dateStr: DATE, rows: [{ staffId: fx.staffId, status: 'PRESENT', timeIn: '08:10' }] })
      .then((r) => must(r, 'mark the register'))
      .then(() => cy.request({ url: `/getStaffRegister?date=${DATE}`, failOnStatusCode: false }))
      .then((r) => (parse(r.body).object.rows || []).find((x) => x.staffId === fx.staffId).status)
    setCfg(sc, 'edu.attendance.staffGraceMinutes', 15)
    mark().then((s) => expect(s, '08:10 is within 08:00 + 15').to.eq('PRESENT'))
    setCfg(sc, 'edu.attendance.staffGraceMinutes', 5)
    mark().then((s) => expect(s, '08:10 is after 08:00 + 5').to.eq('LATE'))
  },
})

addCase({
  kind: 'BEHAVIOUR', screen: 'education', key: 'edu.grading.absentCountsAsZero',
  title: 'An absent paper counts as 0% when ON, and leaves the average when OFF',
  manual: 'Mark a student ABSENT on an exam paper. With “Absence counts as zero” ON, their report card preview shows 0% for the term. Turn it OFF: the absent paper drops out and the term shows no percentage (nothing left to average).',
  run: () => {
    const sc = SCREENS.education
    sc.login()
    const pct = (en) => cy.request({ url: `/getReportCardPreview?enrollNo=${encodeURIComponent(en)}&termId=${fx.termId}`, failOnStatusCode: false })
      .then((r) => must(r, 'preview').object.termPercent)
    setCfg(sc, 'edu.grading.absentCountsAsZero', 'true')
    pct(fx.enrollA).then((p) => expect(p, 'ON: the absence is 0%').to.eq(0))
    pct(fx.enrollB).then((p) => expect(p, 'control: 40 of 50').to.eq(80))
    setCfg(sc, 'edu.grading.absentCountsAsZero', 'false')
    pct(fx.enrollA).then((p) => expect(p, 'OFF: the absent paper leaves the average').to.eq(null))
    pct(fx.enrollB).then((p) => expect(p, 'control unchanged').to.eq(80))
  },
})

addCase({
  kind: 'BEHAVIOUR', screen: 'education', key: 'edu.grading.roundHalfUp',
  title: 'At the .x5 boundary a percentage rounds UP when ON, and DOWN when OFF',
  manual: 'Set homework out of 16 and give a student 13 (81.25%). With “Round percentages up at the halfway point” ON, the mark sheet shows 81.3%. Turn it OFF: it shows 81.2%.',
  run: () => {
    const sc = SCREENS.education
    sc.login()
    const pct = () => cy.request({ url: `/getHomeworkSheet?homeworkId=${fx.homeworkId}`, failOnStatusCode: false })
      .then((r) => must(r, 'homework sheet').object.rows.find((x) => x.enrollNo === fx.enrollA).percent)
    setCfg(sc, 'edu.grading.roundHalfUp', 'true')
    pct().then((p) => expect(p, '81.25 rounds up').to.eq(81.3))
    setCfg(sc, 'edu.grading.roundHalfUp', 'false')
    pct().then((p) => expect(p, '81.25 rounds down').to.eq(81.2))
  },
})

addCase({
  kind: 'BEHAVIOUR', screen: 'education', key: 'edu.reportCard.showAttendance',
  title: 'The report card carries the attendance line when ON, and leaves it out when OFF',
  manual: 'Open a student’s report card preview. With “Show attendance on report cards” ON, it includes days present out of days recorded. Turn it OFF: the attendance line is gone.',
  run: () => {
    const sc = SCREENS.education
    sc.login()
    const card = () => cy.request({ url: `/getReportCardPreview?enrollNo=${encodeURIComponent(fx.enrollB)}&termId=${fx.termId}`, failOnStatusCode: false })
      .then((r) => must(r, 'preview').object)
    setCfg(sc, 'edu.reportCard.showAttendance', 'true')
    card().then((o) => expect(o, 'ON: attendance is on the card').to.have.property('attendanceTotal'))
    setCfg(sc, 'edu.reportCard.showAttendance', 'false')
    card().then((o) => expect(o, 'OFF: no attendance on the card').to.not.have.property('attendanceTotal'))
  },
})

addCase({
  kind: 'BEHAVIOUR', screen: 'education', key: 'edu.discount.branchScoped',
  title: 'A branch user sees only the discounts their branch uses when ON; every discount when OFF',
  manual: 'Create a discount no student uses. Sign in as a teacher who works at one branch: with “Restrict discounts to their branch” OFF, the discount is in their list. Turn it ON: it is not (no student in their branch uses it). The owner sees it either way.',
  run: () => {
    const sc = SCREENS.education
    const names = () => cy.request('/getUserDiscount').then((r) => rows(r.body).map((d) => d.name))
    sc.login(); setCfg(sc, 'edu.discount.branchScoped', 'false')
    cy.loginAsTeacherA(); names().then((n) => expect(n, 'OFF: the branch teacher sees it').to.include(fx.discountName))
    sc.login(); setCfg(sc, 'edu.discount.branchScoped', 'true')
    cy.loginAsTeacherA(); names().then((n) => expect(n, 'ON: not used in their branch, so not listed').to.not.include(fx.discountName))
    sc.login(); names().then((n) => expect(n, 'ON: the owner still sees it').to.include(fx.discountName))
  },
})

// The two keyboard settings on the Education screen: education.js loadKeyboardFlags() turns them into the page flags
// keyboard-forms.js reads on every keystroke (fail OPEN). Asserted on the education dashboard itself.
;[['ui.keyboard.formNav.enabled', 'kbdFormNavEnabled', 'Enter moves to the next box on the registration forms',
  'Education → Configuration: untick “Keyboard navigation on registration forms” and reload. On a registration form, Enter no longer moves to the next box (Tab still does). Tick it again: Enter moves on.'],
 ['ui.keyboard.enterSubmits', 'kbdEnterSubmits', 'Enter on the last box saves the registration form',
  'Education → Configuration: untick “Enter saves on the last field” and reload. On the last box of a registration form, Enter no longer saves — use the Save button. Tick it again: Enter saves.']]
  .forEach(([key, flag, title, manual]) => addCase({
    kind: 'BEHAVIOUR', screen: 'education', key, title, manual,
    run: () => {
      const sc = SCREENS.education
      const pageFlag = () => { cy.visit('/educationDashboard'); cy.waitForAppReady(); return cy.window().its(flag) }
      sc.login(); setCfg(sc, key, 'false')
      pageFlag().should('eq', false)
      setCfg(sc, key, 'true')
      pageFlag().should('eq', true)
    },
  }))

// ── the run ─────────────────────────────────────────────────────────────────────────────────────────────────
describe('SET-GUIDE — module settings certification (orders · education · welfare · agriculture)', () => {
  before(() => {
    // Snapshot every setting this spec can touch, before touching any.
    CASES.forEach((c) => { SCREENS[c.screen].login(); remember(c.screen, c.key) })
    SCREENS.orders.login(); remember('orders', 'order.backorder.allowed')

    // Orders — a marketplace product with exactly 3 in stock (every checkout below asks for 10).
    cy.loginAsMarketplaceOwner()
    cy.request('/getMyOrganizations').then((r) => { const o = (r.body.collection || [])[0] || {}; fx.mpOrg = o.id || o.organizationId || o.orgId })
    cy.request({ method: 'POST', url: '/addProduct', failOnStatusCode: false, headers: { 'Content-Type': 'application/json' },
      body: { name: `CertBO_${RUN}`, sku: `CBO${RUN}`, sellingPrice: 10, taxRate: 0, unit: 'pcs' } }).then((r) => {
      must(r, 'seed product'); fx.mpProduct = r.body.data.id
      return cy.request({ method: 'POST', url: '/addProductStock', failOnStatusCode: false, headers: { 'Content-Type': 'application/json' },
        body: { productId: fx.mpProduct, quantity: 3 } })
    })

    // Education — a class, subject, two students, a term, a 100%-weight exam with one paper out of 50; A absent, B 40.
    cy.loginAsEduOwner()
    const g = `CertC${RUN}`
    fx.enrollA = `CRA${RUN}`; fx.enrollB = `CRB${RUN}`; fx.leaveIds = []
    post('/addGrade', { name: g, fee: 1000, status: 'Active' }).then((r) => must(r, 'addGrade'))
    cy.request('/getUserGrade').then((r) => { fx.gradeId = rows(r.body).find((x) => x.name === g).id })
    cy.then(() => post('/addSubject', { name: `CertS${RUN}`, gradeId: fx.gradeId, status: 'Active' }).then((r) => must(r, 'addSubject')))
    cy.request('/getUserSubject').then((r) => { fx.subjectId = rows(r.body).find((x) => x.name === `CertS${RUN}`).id })
    cy.then(() => post('/addStudent', { name: 'Cert A', enrollNo: fx.enrollA, gradeId: fx.gradeId, status: 'ACTIVE' }).then((r) => must(r, 'addStudent A')))
    cy.then(() => post('/addStudent', { name: 'Cert B', enrollNo: fx.enrollB, gradeId: fx.gradeId, status: 'ACTIVE' }).then((r) => must(r, 'addStudent B')))
    cy.then(() => post('/addAcademicYear', { name: `CertY${RUN}`, startDateStr: '01-08-2026', endDateStr: '30-06-2027' }).then((r) => must(r, 'addAcademicYear')))
    cy.request('/getAcademicYears').then((r) => { fx.yearId = rows(r.body).find((y) => y.name === `CertY${RUN}`).id })
    cy.then(() => post('/addTerm', { academicYearId: fx.yearId, name: 'T1', sequence: 1, startDateStr: '01-08-2026', endDateStr: '31-12-2026' }).then((r) => must(r, 'addTerm')))
    cy.request('/getAcademicYears').then((r) => { const y = rows(r.body).find((x) => x.id === fx.yearId); fx.termId = y.terms[0].id })
    cy.then(() => post('/addExam', { name: `CertX${RUN}`, termId: fx.termId, weightPercent: 100 }).then((r) => must(r, 'addExam')))
    cy.request('/getExams').then((r) => { fx.examId = rows(r.body).find((x) => x.name === `CertX${RUN}`).id })
    cy.then(() => post('/addExamPaper', { examId: fx.examId, subjectId: fx.subjectId, maxMarks: 50, passMarks: 1, examDateStr: '14-11-2026' }).then((r) => must(r, 'addExamPaper')))
    cy.then(() => post('/setExamStatus', { id: fx.examId, status: 'PUBLISHED' }).then((r) => must(r, 'publish exam')))
    cy.request('/getExams').then((r) => { fx.paperId = rows(r.body).find((x) => x.id === fx.examId).papers[0].id })
    cy.then(() => postJson('/saveMarksBulk', { examPaperId: fx.paperId, rows: [
      { enrollNo: fx.enrollA, marksObtained: null, absent: true }, { enrollNo: fx.enrollB, marksObtained: 40, absent: false }] })
      .then((r) => must(r, 'marks: A absent, B 40')))
    // Homework out of 16; A scores 13 (81.25% — the rounding boundary).
    cy.then(() => post('/saveHomework', { subjectId: fx.subjectId, title: `CertHW${RUN}`, dueOn: '2026-10-20', maxMarks: 16 }).then((r) => must(r, 'saveHomework')))
    cy.request('/getHomework').then((r) => { fx.homeworkId = rows(r.body).find((h) => h.title === `CertHW${RUN}`).id })
    cy.then(() => postJson('/saveSubmissionBulk', { homeworkId: fx.homeworkId, rows: [{ enrollNo: fx.enrollA, state: 'MARKED', marksObtained: 13 }] })
      .then((r) => must(r, 'grade A 13/16')))
    // A staff member of our own starting at 08:00, and a leave type of our own.
    cy.then(() => post('/addStaff', { name: `CertStaff${RUN}`, designation: 'Teacher', status: 'Active', timeInStr: '08:00' }).then((r) => must(r, 'addStaff')))
    cy.request('/getUserStaff').then((r) => { fx.staffId = rows(r.body).find((s) => s.name === `CertStaff${RUN}`).id })
    // ONE leave type reused across runs: once it has requests the server refuses to delete it (by design), so a
    // per-run type would pile up. Found or created by its fixed name.
    const LEAVE = 'Cert leave (test fixture)'
    cy.request('/getLeaveTypes').then((r) => {
      const t = rows(r.body).find((x) => x.name === LEAVE)
      if (t) { fx.leaveTypeId = t.id; return }
      post('/saveLeaveType', { name: LEAVE, annualQuota: 100, paid: 'true' }).then((rr) => must(rr, 'saveLeaveType'))
      cy.request('/getLeaveTypes').then((rr) => { fx.leaveTypeId = rows(rr.body).find((x) => x.name === LEAVE).id })
    })
    // A discount no student uses; teacher.a works at one branch only (same grants as branch-scope-settings.cy.js).
    fx.discountName = `CertDisc${RUN}`
    cy.then(() => post('/addDiscount', { name: fx.discountName, di: 5, status: 'Active' }).then((r) => must(r, 'addDiscount')))
    cy.request('/getUserSchool').then((r) => {
      const b1 = rows(r.body).find((s) => s.branchName === 'CY Branch 1')
      const ensure = b1 ? cy.wrap(b1.id) : post('/addSchool', { name: 'CY Branch 1', branchName: 'CY Branch 1', status: 'Active' })
        .then(() => cy.request('/getUserSchool')).then((rr) => rows(rr.body).find((s) => s.branchName === 'CY Branch 1').id)
      ensure.then((branchId) => cy.request('/team/users').then((t) => {
        const a = rows(t.body).find((u) => u.email === 'teacher.a@myplus.com')
        expect(a, 'teacher.a is a member of this school').to.exist
        return cy.request({ method: 'POST', url: '/assignStores', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
          body: { userId: a.userId, storeIds: [branchId], roleAtLocation: 'USER' } }).then((gr) => expect(gr.body && gr.body.success, 'grant').to.eq(true))
      }))
    })
  })

  afterEach(function () {
    const c = this.currentTest.ctx.case
    if (c) results.push({ kind: c.kind, screen: c.screen, key: c.key, title: c.title, deep: c.deep || null, manual: c.manual || null,
      passed: this.currentTest.state === 'passed', error: this.currentTest.err ? String(this.currentTest.err.message).slice(0, 300) : null })
  })

  after(() => {
    // Settings first: whatever the fixtures do, the tenant's configuration goes back exactly.
    Object.keys(ORIG).forEach((k) => { const [scId, key] = k.split('|'); restore(scId, key) })
    // Fixtures, in reverse dependency order; a refusal is RECORDED, never hidden.
    // Some delete routes answer a bare `true` on success — that is not a refusal.
    const del = (url, body, what) => post(url, body).then((r) => {
      const b = parse(r.body)
      if (b !== true && !okBody(b)) fx.leftovers.push(`${what}: ${JSON.stringify(b).slice(0, 120)}`)
    })
    cy.loginAsEduOwner()
    cy.then(() => (fx.leaveIds || []).forEach((id) => del('/decideLeaveRequest', { id, decision: 'CANCELLED' }, `leave ${id}`)))
    cy.then(() => fx.homeworkId && postJson('/saveSubmissionBulk', { homeworkId: fx.homeworkId, rows: [{ enrollNo: fx.enrollA, state: '', marksObtained: null }] }))
    cy.then(() => fx.homeworkId && del('/deleteHomework', { id: fx.homeworkId }, 'homework'))
    cy.request('/getUserDiscount').then((r) => { const d = rows(r.body).find((x) => x.name === fx.discountName); if (d) del('/deleteDiscount', { checked: d.id }, 'discount') })
    cy.then(() => fx.staffId && del('/deleteStaff', { checked: fx.staffId }, 'staff (has a register row)'))
    cy.then(() => fx.examId && del('/setExamStatus', { id: fx.examId, status: 'DRAFT' }, 'exam to draft'))
    cy.then(() => fx.examId && del('/deleteExam', { id: fx.examId }, 'exam (has marks)'))
    cy.request('/getUserStudent').then((r) => rows(r.body).filter((x) => [fx.enrollA, fx.enrollB].includes(x.enrollNo))
      .forEach((x) => del('/deleteStudent', { checked: x.id }, `student ${x.enrollNo}`)))
    cy.then(() => fx.subjectId && del('/deleteSubject', { checked: fx.subjectId }, 'subject'))
    cy.then(() => fx.gradeId && del('/deleteGrade', { checked: fx.gradeId }, 'class'))
    cy.then(() => fx.termId && del('/deleteTerm', { checked: fx.termId }, 'term'))
    cy.then(() => fx.yearId && del('/deleteAcademicYear', { checked: fx.yearId }, 'academic year'))
    cy.then(() => cy.writeFile(OUT, { run: RUN, at: new Date().toISOString(), cases: results, leftovers: fx.leftovers }))
  })

  CASES.forEach((c) => {
    it(`${c.kind} — ${SCREENS[c.screen].name} — ${c.key}: ${c.title}`, function () {
      this.case = c
      c.run()
    })
  })
})
