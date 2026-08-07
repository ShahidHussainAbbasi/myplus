/**
 * Slice 108 — self-loading sections must NOT fire the generic /getUser<Section> call.
 *
 * THE DEFECT: main.js serves every registration screen from one convention — <Name>Div holds a
 * #table<Name> fed by GET /getUser<Name>. The education Phase 1/2 screens are a different shape (a year
 * with nested terms, a marks roster, a timetable grid), so each ships its OWN loader and correctly has no
 * /getUser<Name> endpoint. The generic handler fired for them anyway: 15 screens, a guaranteed 404 each.
 * Until slice 107 that 404 was turned into a redirect to /login, so it presented as
 * "clicking Academic Year logs me out" — an auth bug, in the wrong part of the system entirely.
 *
 * Screens now opt out with data-self-load="true" on the div.
 *
 * Design: microservices/docs/slices/108-phantom-getuser-section-loads.md
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/education/section-load.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */

// Every self-loading education section, and the phantom endpoint it used to hit.
const SELF_LOADING = [
  ['AcademicYearDiv', 'getUserAcademicYear'],
  ['ExamDiv',         'getUserExam'],
  ['MarksDiv',        'getUserMarks'],
  ['GradingDiv',      'getUserGrading'],
  ['TimetableDiv',    'getUserTimetable'],
  ['HomeworkDiv',     'getUserHomework'],
]

describe('Education — sections load without a phantom getUser call (slice 108)', () => {
  beforeEach(() => { cy.loginAsEduOwner() })

  it('opening Academic Year makes NO /getUser* request, and still loads its own data', () => {
    // Catch ANY getUser* — asserting on the specific name would pass if the convention produced a
    // differently-wrong URL, which is the failure mode this whole slice is about.
    cy.intercept('GET', '**/getUser*').as('phantom')
    cy.intercept('GET', '**/getAcademicYears*').as('own')

    cy.visit('/educationDashboard')
    cy.get('#registrationType').select('AcademicYearDiv', { force: true })

    // The screen's OWN loader must fire — proving we skipped the generic path without breaking the page.
    cy.wait('@own').its('response.statusCode').should('eq', 200)
    cy.get('#AcademicYearDiv').should('be.visible')

    // ...and the phantom must never have been sent. cy.get on an alias with no calls yields null.
    cy.get('@phantom.all').should('have.length', 0)
  })

  it('the reported screens — Exam and Marks — behave the same way', () => {
    cy.intercept('GET', '**/getUser*').as('phantom')
    cy.visit('/educationDashboard')

    cy.get('#registrationType').select('ExamDiv', { force: true })
    cy.get('#ExamDiv').should('be.visible')
    cy.get('#registrationType').select('MarksDiv', { force: true })
    cy.get('#MarksDiv').should('be.visible')

    cy.get('@phantom.all').should('have.length', 0)
  })

  it('every self-loading section opens without a getUser call', () => {
    cy.intercept('GET', '**/getUser*').as('phantom')
    cy.visit('/educationDashboard')
    SELF_LOADING.forEach(([div]) => {
      cy.get('#registrationType').select(div, { force: true })
      cy.get('#' + div).should('be.visible')
    })
    cy.get('@phantom.all').should('have.length', 0)
  })

  /**
   * THE TEST THAT WOULD CATCH THIS FIX BEING WRONG.
   *
   * Skipping the generic branch also skips $switchInputs, which sets the tableV/getAll globals. A legacy
   * register opened AFTERWARDS must still work: it calls $switchInputs itself, so the globals re-point.
   * If that ordering were broken, the legacy grid would silently render against a stale table name — and
   * every other test here would still pass.
   */
  it('a LEGACY register opened after a self-loading one still calls getUser and fills its grid', () => {
    cy.intercept('GET', '**/getUserOwner*').as('owner')
    cy.visit('/educationDashboard')

    cy.get('#registrationType').select('AcademicYearDiv', { force: true })   // self-loading first
    cy.get('#AcademicYearDiv').should('be.visible')

    cy.get('#registrationType').select('OwnerDiv', { force: true })          // then a legacy register
    cy.wait('@owner').its('response.statusCode').should('eq', 200)
    cy.get('#OwnerDiv').should('be.visible')
    cy.get('#tableOwner').should('exist')
  })

  it('no error banner or toast is shown on a healthy self-loading screen', () => {
    // handleAjaxFailure (slice 107) surfaces failures inline now, so a lingering phantom 404 would be
    // VISIBLE to the user rather than silent. Absence of the banner is the user-facing proof.
    cy.visit('/educationDashboard')
    cy.get('#registrationType').select('AcademicYearDiv', { force: true })
    cy.get('#AcademicYearDiv').should('be.visible')
    cy.get('#globalError').should('not.be.visible')
    cy.get('#formErrorToast').should('not.exist')
  })
})
