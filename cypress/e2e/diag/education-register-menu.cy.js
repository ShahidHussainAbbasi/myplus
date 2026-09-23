/**
 * EDU-MENU — every entry the education sidebar offers must be REACHABLE.
 *
 * <h3>The defect this records</h3>
 * The user reported that Subjects and Manage Students were missing under Register for
 * owner.education@ and demo.education@. It was never a permissions fault: both accounts hold
 * ADMIN_PRIVILEGE and SUPER_PRIVILEGE (checked in myplusdb_auth AND in the live login response),
 * both entries were in the DOM, and both passed their guard.
 *
 * `sidebar.css` capped an open group at `max-height:520px` against a `.snav-menu` whose collapsed rule
 * sets `overflow:hidden`. Education's Register group had grown to 25 entries (~975px), so 12 of them
 * were clipped with NO scrollbar — Subjects was #21, Manage Students #24, Manage Users #25. The
 * sidebar's own `overflow-y:auto` could not compensate, because clipped content adds nothing to
 * scrollHeight: it offered 30px of scroll against a 265px shortfall.
 *
 * Two fixes, both gated here:
 *   1. an open group now scrolls — `max-height:min(520px,60vh); overflow-y:auto`
 *   2. the 25-entry Register group became four themed groups, none taller than 9 entries
 *
 * ⚠ THE ASSERTION IS REACHABILITY, NOT PRESENCE. A DOM-presence check passed throughout the entire
 * period the feature was unusable. What is asserted is that each entry's box lies inside the menu's
 * own painted box, or that the menu can scroll to it — which is what "the operator can click it"
 * actually means.
 *
 * Run:  npx cypress run --spec cypress/e2e/diag/education-register-menu.cy.js --env diag=1
 */
const run = Cypress.env('diag') ? describe : describe.skip

const GROUPS = ['snavStudents', 'snavStaff', 'snavAcademics', 'snavSchool']

run('EDU-MENU — the education sidebar is reachable', () => {
  const walk = (who) => {
    cy.loginAs(who, 'Demo@2025!', '/educationDashboard')
    cy.visit('/educationDashboard')
    cy.waitForAppReady()
    cy.viewport(1280, 800)

    const seen = []

    GROUPS.forEach((gid) => {
      cy.get(`#${gid}`, { timeout: 20000 }).should('exist')
      cy.get(`#${gid}`).invoke('addClass', 'snav-open')
      cy.wait(250)
      cy.get(`#${gid}`).then(($g) => {
        const menu = $g.find('ul.snav-menu')[0]
        const box = menu.getBoundingClientRect()
        const rows = $g.find('li a').toArray().map((a) => ({
          text: Cypress.$(a).text().replace(/\s+/g, ' ').trim(),
          bottom: Math.round(a.getBoundingClientRect().bottom),
        }))
        rows.forEach((r) => seen.push(r.text))

        // Clipped = painted below the menu's own visible box. That is only acceptable when the menu
        // can actually scroll to it (scrollHeight > clientHeight); otherwise it is unreachable.
        const canScroll = menu.scrollHeight > menu.clientHeight
        const clipped = rows.filter((r) => r.bottom > Math.round(box.bottom))

        expect(canScroll || clipped.length === 0,
          `${gid}: ${clipped.length} of ${rows.length} entries sit below the menu box ` +
          `(${Math.round(box.height)}px) and it cannot scroll — ` +
          clipped.map((r) => r.text).join(' | ')).to.eq(true)
      })
      cy.get(`#${gid}`).invoke('removeClass', 'snav-open')
    })

    cy.then(() => {
      const has = (t) => seen.some((x) => new RegExp(t, 'i').test(x))
      // The three the user named, plus the sections behind them.
      expect(has('subject'), `${who}: Subjects is offered`).to.eq(true)
      expect(has('manage students'), `${who}: Manage Students is offered`).to.eq(true)
      expect(has('manage users'), `${who}: Manage Users is offered`).to.eq(true)
      expect(seen.length, `${who}: all 25 entries survived the regrouping`).to.eq(25)
    })

    cy.get('#SubjectDiv').should('exist')
    cy.get('#StudentDiv').should('exist')
    cy.get('#TeamDiv').should('exist')
  }

  it('⭐⭐ owner.education@ can reach every entry, Subjects and Manage Students included', () =>
    walk('owner.education@myplus.com'))

  it('⭐ demo.education@ too — the report named both accounts', () =>
    walk('demo.education@myplus.com'))

  it('⭐ Manage Users opens the team screen for the owner', () => {
    // The user asked for this one by name. Its guard already admitted ROLE_OWNER and ADMIN_PRIVILEGE —
    // it was simply entry #25 of 25, the deepest into the clipped tail.
    cy.loginAs('owner.education@myplus.com', 'Demo@2025!', '/educationDashboard')
    cy.visit('/educationDashboard')
    cy.waitForAppReady()
    cy.get('#snavStaff').invoke('addClass', 'snav-open')
    cy.contains('#snavStaff a', /manage users/i).should('be.visible').click()
    cy.get('#TeamDiv', { timeout: 15000 }).should('be.visible')
  })
})
