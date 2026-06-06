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
