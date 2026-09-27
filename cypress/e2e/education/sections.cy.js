/**
 * Education / School Management System — registration sections.
 * Data-driven (one spec for every section) to keep the suite DRY and consistent
 * with the single shared UI. Requires the monolith at http://localhost:8080 in
 * auth.mode=server with auth-service + gateway + education-service up.
 *
 * All these sections live behind the off-screen #registrationType <select> on
 * /educationDashboard and call flat endpoints returning GenericResponse.
 */

const SECTIONS = [
  { div: 'OwnerDiv',    list: '/getUserOwner',    all: '/getAllOwner',    opts: '/getUserOwners' },
  { div: 'SchoolDiv',   list: '/getUserSchool',   all: '/getAllSchool',   opts: '/getUserSchools' },
  { div: 'GradeDiv',    list: '/getUserGrade',    all: '/getAllGrade',    opts: '/getUserGrades' },
  { div: 'SubjectDiv',  list: '/getUserSubject',  all: '/getAllSubject',  opts: '/getUserSubjects' },
  { div: 'StaffDiv',    list: '/getUserStaff',    all: '/getAllStaff',    opts: '/getUserStaffs' },
  { div: 'GuardianDiv', list: '/getUserGuardian', all: '/getAllGuardian', opts: '/getUserGuardians' },
  { div: 'StudentDiv',  list: '/getUserStudent',  all: '/getAllStudent',  opts: '/getUserStudents' },
  { div: 'VehicleDiv',  list: '/getUserVehicle',  all: '/getAllVehicle',  opts: '/getUserVehicles' },
  { div: 'DiscountDiv', list: '/getUserDiscount', all: '/getAllDiscount', opts: '/getUserDiscounts' },
]

// The visible sub-nav menus are privilege-gated server-side (sec:authorize ADMIN/SUPER). A company
// owner is the SUPER user of their tenant (ROLE_OWNER), and the demo account holds the same super
// privilege set, so these menus MUST list their items. (A plain ROLE_*_USER would see them empty —
// that's by design; finer roles are managed by the owner. The section tests below drive the off-screen
// select directly, so they would NOT catch an empty menu.)
describe('Education — sub-nav menus list their items (owner/super privilege visibility)', () => {
  beforeEach(() => {
    cy.loginAsEducation()
    cy.visit('/educationDashboard')
  })

  /*
   * ⚠ `snavRegister` IS GONE. It held 25 entries, and sidebar.css capped an open group at 520px with
   * `overflow:hidden`, so 12 of them — Subjects, Manage Students and Manage Users among them — were
   * clipped with no scrollbar. EDU-MENU split it into four themed groups, none taller than 9 entries,
   * and made an open group scroll. Every entry survived the move; see education/menu-reachable.cy.js,
   * which asserts all 25 are REACHABLE rather than merely present.
   */
  const MENUS = [
    { id: 'snavStudents', min: 5 },
    { id: 'snavStaff', min: 5 },
    { id: 'snavAcademics', min: 8 },
    { id: 'snavSchool', min: 3 },
    { id: 'snavFee', min: 4 },
    { id: 'snavReport', min: 1 },
    { id: 'snavAttendance', min: 1 },
    { id: 'snavAlerts', min: 1 },
  ]

  MENUS.forEach((m) => {
    it(`${m.id} renders its menu items (not an empty dropdown)`, () => {
      cy.get(`#${m.id} .snav-menu li`).its('length').should('be.gte', m.min)
    })
  })
})

describe('Education — registration sections', () => {
  before(() => {
    cy.loginAsEducation()
  })

  SECTIONS.forEach((s) => {
    describe(s.div, () => {
      beforeEach(() => {
        cy.loginAsEducation()
        cy.openSection(s.div, '/educationDashboard')
      })

      it('renders the section', () => {
        cy.get(`#${s.div}`).should('be.visible')
      })

      it(`${s.list} returns SUCCESS or NOT_FOUND`, () => {
        cy.request(s.list).then((res) => {
          expect(res.status).to.eq(200)
          expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
        })
      })

      it(`${s.all} returns SUCCESS or NOT_FOUND`, () => {
        cy.request(s.all).then((res) => {
          expect(res.status).to.eq(200)
          expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
        })
      })

      it(`${s.opts} responds 200 (option list; empty when no data)`, () => {
        cy.request(s.opts).then((res) => {
          expect(res.status).to.eq(200)
          expect(res.body).to.be.a('string')
        })
      })
    })
  })
})
