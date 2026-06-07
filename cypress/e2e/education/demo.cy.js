/**
 * Education module — end-to-end USER MANUAL walkthrough (video demo).
 *
 * This is NOT a regression test — it is a single, continuous, narrated walkthrough recorded as one
 * MP4 for documentation. It reuses the proven selectors from the education specs (dashboard,
 * sections, attendance, fee, student-import, alerts, organization) so it stays in sync with them.
 *
 * Record it with:
 *   npm run test:e2e:education:demo
 *   (= cypress run --browser chrome --headed --config video=true --spec cypress/e2e/education/demo.cy.js)
 *
 * The MP4 lands in cypress/videos/education/demo.cy.js.mp4.
 *
 * Prerequisites: full stack up (monolith :8080 in auth.mode=server + config/eureka/gateway/auth +
 * education-service), seeded education data (a school + "Grade 1" + students ENR-001/ENR-002), and
 * the fixtures cypress/fixtures/students-import.csv and contacts-import.csv.
 */

describe('Education — end-to-end user-manual walkthrough', () => {
  const STEP = 1200 // pause between actions so the recording is watchable
  const READ = 2200 // pause long enough to read a caption banner

  // On-screen "narration": a fixed banner injected into the live app window. It is recreated after
  // each navigation (cy.visit wipes the DOM), so just call caption() whenever the step changes.
  const caption = (text, ms = READ) => {
    cy.window({ log: false }).then((win) => {
      const doc = win.document
      let el = doc.getElementById('demoCaption')
      if (!el) {
        el = doc.createElement('div')
        el.id = 'demoCaption'
        el.style.cssText = [
          'position:fixed', 'left:0', 'right:0', 'bottom:0', 'z-index:2147483647',
          'background:rgba(12,35,64,.93)', 'color:#fff', 'padding:16px 24px',
          'font:600 21px/1.45 Arial,Helvetica,sans-serif', 'text-align:center',
          'letter-spacing:.2px', 'box-shadow:0 -3px 14px rgba(0,0,0,.45)',
          // Visual only — let clicks (and Cypress's actionability checks) pass through to the app.
          'pointer-events:none',
        ].join(';')
        doc.body.appendChild(el)
      }
      el.textContent = text
    })
    cy.wait(ms)
  }

  before(() => {
    cy.loginAsEducation()
  })

  it('walks the full education domain', () => {
    // ── 1. Dashboard ────────────────────────────────────────────────────────────────────────
    cy.visit('/educationDashboard')
    cy.get('#container').should('exist')
    caption('MyPlus — Education / School Management System')
    caption('The active organization (tenant) is shown in the top navigation')
    cy.get('#orgSwitcher').should('exist').scrollIntoView()
    cy.wait(STEP)

    // ── 2. Registration tour — every org-scoped registration screen ──────────────────────────
    const sections = [
      ['OwnerDiv', 'Owners'],
      ['SchoolDiv', 'Schools / Campuses'],
      ['GradeDiv', 'Classes'],
      ['SubjectDiv', 'Subjects'],
      ['StaffDiv', 'Staff'],
      ['GuardianDiv', 'Guardians'],
      ['StudentDiv', 'Students'],
      ['VehicleDiv', 'Vehicles'],
      ['DiscountDiv', 'Discounts'],
    ]
    caption('Registration — the school setup screens')
    sections.forEach(([div, label]) => {
      caption('Registration  ▸  ' + label, STEP)
      cy.get('#registrationType').select(div, { force: true })
      cy.get('#' + div).should('be.visible')
      cy.wait(STEP)
    })

    // ── 2b. Create a real record through the UI: a new Class ──────────────────────────────────
    caption('Creating a new Class through the form')
    cy.get('#registrationType').select('GradeDiv', { force: true })
    cy.get('#GradeDiv').should('be.visible')
    // Pick the first real campus from the dropdown (skip any empty placeholder option).
    cy.get('#gradeSchoolDD').find('option').then(($opts) => {
      const real = [...$opts].find((o) => o.value && o.value.trim() !== '')
      if (real) cy.get('#gradeSchoolDD').select(real.value, { force: true })
    })
    const className = 'Demo Class ' + new Date().toISOString().slice(11, 19)
    cy.get('#gradeName').clear().type(className)
    cy.get('#gradeFee').clear().type('5000')
    cy.wait(STEP)
    const gradeAlert = cy.stub().as('gradeAlert')
    cy.on('window:alert', gradeAlert)
    cy.get('#addGrade').click()
    cy.wait(READ)

    // ── 3. Attendance — class roster marking ─────────────────────────────────────────────────
    caption('Operations  ▸  Attendance: load the class roster and mark present')
    cy.get('#attendanceType').select('ADiv', { force: true })
    cy.get('#ADiv').should('be.visible')
    cy.get('#aGradeDD', { timeout: 10000 }).find('option').should('have.length.greaterThan', 1)
    cy.get('#aGradeDD').select('Grade 1', { force: true })
    cy.wait(STEP)
    cy.contains('button', 'Load Roster').click()
    cy.get('#aRosterWrap').should('be.visible')
    cy.get('#aRosterBody tr').should('have.length.greaterThan', 0)
    caption('Mark every student present, then save')
    cy.contains('button', 'Mark all Present').click()
    cy.wait(STEP)
    const attAlert = cy.stub().as('attAlert')
    cy.on('window:alert', attAlert)
    cy.contains('button', 'Save Attendance').click()
    cy.wait(READ)

    // ── 4. Fee — settings, voucher (aging), ledger, report ───────────────────────────────────
    caption('Operations  ▸  Fee: runtime settings')
    cy.get('#feeType').select('FeeSettingDiv', { force: true })
    cy.get('#FeeSettingDiv').should('be.visible')
    cy.get('#fsPaymentMode').should('exist')
    const feeSetAlert = cy.stub().as('feeSetAlert')
    cy.on('window:alert', feeSetAlert)
    cy.contains('button', 'Save Settings').click()
    cy.wait(STEP)

    caption('Fee  ▸  generate a voucher (multi-month aging)')
    cy.get('#feeType').select('FVDiv', { force: true })
    cy.get('#FVDiv').should('be.visible')
    cy.get('#fvEnroll').clear().type('ENR-002')
    cy.contains('button', 'Generate Voucher').click()
    cy.get('#fvVoucher').should('be.visible').and('contain', 'Total payable')
    cy.wait(READ)

    caption('Fee  ▸  a student fee ledger')
    cy.get('#fvEnroll').clear().type('ENR-001')
    cy.contains('button', 'Show Ledger').click()
    cy.get('#flLedgerWrap').should('be.visible')
    cy.get('#flBody tr').should('have.length.greaterThan', 0)
    cy.wait(READ)

    caption('Fee  ▸  the collection report with totals')
    cy.get('#feeType').select('FRDiv', { force: true })
    cy.get('#FRDiv').should('be.visible')
    cy.contains('button', 'View').click()
    cy.get('#frBody tr').should('have.length.greaterThan', 0)
    cy.get('#frTotals').should('contain', 'Totals')
    cy.wait(READ)

    // ── 5. Student bulk import ───────────────────────────────────────────────────────────────
    caption('Students  ▸  bulk import from a CSV file')
    cy.get('#registrationType').select('StudentDiv', { force: true })
    cy.get('#StudentDiv').should('be.visible')
    cy.get('#impStudentsFile').selectFile('cypress/fixtures/students-import.csv', { force: true })
    cy.wait(STEP)
    cy.contains('button', 'Import').click()
    cy.get('#impStudentsSummary', { timeout: 15000 }).should('contain', 'Created')
    cy.wait(READ)

    // ── 6. Communication — system alert + public alert (real email) ──────────────────────────
    caption('Communication  ▸  create a System Alert')
    cy.get('#communicationType').select('AlertsDiv', { force: true })
    cy.get('#AlertsDiv').should('be.visible')
    cy.get('#acdd').select(['Students'], { force: true })
    cy.get('#atdd').select(['Notice Board'], { force: true })
    cy.get('#adcdd').select(['Email'], { force: true })
    cy.get('#adpdd').select(['Daily'], { force: true })
    cy.get('#adtdd').select('Manual', { force: true })
    cy.get('#ah').clear().type('Holiday Notice')
    cy.get('#am').clear().type('School closed Friday.')
    cy.intercept('POST', '**/addAlerts').as('addAlerts')
    cy.get('#addAlerts').click()
    cy.wait('@addAlerts').its('response.statusCode').should('eq', 200)
    cy.get('#tableAlerts tbody tr', { timeout: 10000 }).should('have.length.greaterThan', 0)
    cy.wait(STEP)

    caption('Communication  ▸  Public Alert: import contacts and send a real email')
    cy.intercept('POST', '**/importCSV').as('importCSV')
    cy.intercept('POST', '**/sendPA').as('sendPA')
    cy.get('#communicationType').select('PADiv', { force: true })
    cy.get('#PADiv').should('be.visible')
    cy.get('#csvFile').selectFile('cypress/fixtures/contacts-import.csv', { force: true })
    cy.get('#paBtn').click()
    cy.wait('@importCSV').its('response.statusCode').should('eq', 200)
    cy.get('#tablePA tbody tr', { timeout: 10000 }).should('have.length.greaterThan', 0)
    cy.get('#pah').clear().type('Test Notice')
    cy.get('#pam').clear().type('This is a demo public alert from MyPlus.')
    cy.get('#sendPA').click()
    cy.wait('@sendPA', { timeout: 30000 }).its('response.statusCode').should('eq', 200)
    cy.wait(READ)

    // ── 7. Organization switcher ─────────────────────────────────────────────────────────────
    caption('Multi-tenant: switch the active organization')
    cy.get('#orgSwitcher').scrollIntoView().should('be.visible')
    cy.get('#orgSwitcher option').should('have.length.greaterThan', 0)
    cy.wait(READ)

    caption('End of walkthrough — MyPlus Education module')
  })
})
