/**
 * DIAGNOSTIC + GATE — every education section must actually become VISIBLE when picked, for both accounts.
 *
 * Reported: "StudentDiv and others were not visible; after inspecting, different menus start displaying."
 *
 * Sections are hidden by CSS (theme.css `.formDiv{display:none}`) and revealed by main.js's dropdown
 * handler calling .show() on the chosen one. So "not visible" means either the change handler did not run,
 * or it showed something else. This walks EVERY option in the section dropdown and asserts the matching
 * div is the one on screen — which pins both halves at once.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/education/section-visibility.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */

// Every value offered by #registrationType, in template order.
const SECTIONS = [
  'OwnerDiv', 'SchoolDiv', 'VehicleDiv', 'GradeDiv',
  'AcademicYearDiv', 'ExamDiv', 'MarksDiv', 'GradingDiv', 'ReportCardDiv', 'PromotionDiv',
  'TimetableDiv', 'SubstitutionDiv', 'StaffRegisterDiv', 'LeaveDiv', 'HomeworkDiv',
  'MeetingsDiv', 'NoticesDiv', 'BehaviourDiv', 'PortalAccessDiv',
  'StaffDiv', 'SubjectDiv', 'DiscountDiv', 'GuardianDiv', 'StudentDiv',
]

// Both accounts the user works with. DEMO_ROLE is seeded from superSet, so both hold ADMIN+SUPER — any
// difference between them would itself be the finding.
const ACCOUNTS = [
  ['owner.education@myplus.com', 'loginAsEduOwner'],
  ['demo.education@myplus.com',  'loginAsEducation'],
]

ACCOUNTS.forEach(([email, cmd]) => {
  describe(`Education sections are reachable — ${email}`, () => {
    beforeEach(() => { cy[cmd]() })

    it('every section in the dropdown becomes visible when picked', () => {
      cy.visit('/educationDashboard')

      SECTIONS.forEach((div) => {
        // The option must exist, or .select() throws with a message that does not name the real problem.
        cy.get('#registrationType option[value="' + div + '"]', { timeout: 10000 })
          .should('exist');

        cy.get('#registrationType').select(div, { force: true })

        // THE assertion: the chosen section is on screen.
        cy.get('#' + div, { timeout: 10000 }).should('be.visible')

        // ...and it is the ONLY one. "Different menus start displaying" is precisely the failure where
        // more than one panel is left showing, so count them rather than trusting the one we asked for.
        cy.get('.formDiv:visible').then(($v) => {
          const ids = [...$v].map((e) => e.id)
          expect(ids, `only ${div} should be visible, saw: ${ids.join(', ')}`).to.deep.eq([div])
        })
      })
    })

    it('StudentDiv specifically — the reported screen — opens and shows its grid', () => {
      cy.visit('/educationDashboard')
      cy.get('#registrationType').select('StudentDiv', { force: true })
      cy.get('#StudentDiv').should('be.visible')
      cy.get('#tableStudent').should('exist')
    })

    it('the sidebar link reaches StudentDiv too (snavGo path, not just the select)', () => {
      // snavGo() sets #registrationType and fires change. If the option were missing or the value did not
      // match, .val() would silently no-op and the previous section would stay on screen.
      cy.visit('/educationDashboard')
      cy.window().then((w) => w.snavGo('registrationType', 'StudentDiv', 'snavRegister'))
      cy.get('#StudentDiv').should('be.visible')
    })
  })
})
