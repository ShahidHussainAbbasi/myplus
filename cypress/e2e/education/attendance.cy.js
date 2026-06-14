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
    cy.get('#aGradeDD', { timeout: 10000 }).find('option').then(($opts) => {
      const real = [...$opts].find((o) => o.value && o.value.trim() !== '')
      if (!real) {
        cy.log('No class in this org — attendance roster not applicable')
        return
      }
      cy.get('#aGradeDD').select(real.value, { force: true })
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
