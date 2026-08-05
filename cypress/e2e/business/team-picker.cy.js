/**
 * Team & Users — the location chip picker (#4).
 *
 * Manage Users used to assign stores/branches with a native <select multiple> (ctrl-click, undiscoverable,
 * unstyled). It is now a set of toggle CHIPS in /js/common/team.js (#teamStores.locpick), shared by the
 * commerce and education dashboards. The screen behaviour was only ever exercised through the endpoints; this
 * gives it durable UI coverage so a future change to the shared picker can't silently break it.
 *
 * The picker is populated from the caller's workable locations (getMyStores / getMySchools), so the fixture
 * ensures the owner has at least two, independent of other specs' run order.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const rows = (body) => {
  for (const key of ['collection', 'object', 'data']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

// Open the Team screen and wait for the picker's chips to render (loadTeamLocations is async).
const openTeam = () => {
  cy.window().then((w) => w.showTeam())
  cy.get('#TeamDiv').should('be.visible')
  cy.get('#teamStores.locpick', { timeout: 10000 }).should('exist')
  cy.get('#teamStores .locpick__chip', { timeout: 10000 }).should('have.length.greaterThan', 0)
}

describe('Team & Users: location chip picker', () => {
  before(() => {
    // Ensure the owner has >= 2 stores so the picker has chips regardless of which specs ran first.
    cy.loginAsOwner()
    cy.request('/getStores').then((r) => {
      const have = rows(r.body).length
      for (let i = have; i < 2; i++) {
        const tag = `${uniq()}${i}`
        cy.request({
          method: 'POST', url: '/addStore', headers: { 'Content-Type': 'application/json' },
          body: { name: `CY Picker Store ${tag}`, code: `PS${tag}` }, failOnStatusCode: false,
        })
      }
    })
  })

  beforeEach(() => {
    cy.loginAsOwner()
    cy.visit('/businessDashboard')
  })

  it('renders toggle chips, not a native multi-select', () => {
    openTeam()
    cy.get('#teamStores select').should('not.exist')               // no ctrl-click <select>
    cy.get('.locpick__chip').its('length').should('be.greaterThan', 0)
    cy.get('.locpick__chip').each(($c) => {                          // everything starts unselected
      cy.wrap($c).should('have.attr', 'aria-pressed', 'false')
    })
    cy.get('.locpick__count').should('exist')                       // the live-count line is present
  })

  it('toggling a chip flips aria-pressed and updates the live count', () => {
    openTeam()
    cy.get('.locpick__chip').first().click()
    cy.get('.locpick__chip').first().should('have.attr', 'aria-pressed', 'true')
    cy.get('.locpick__count').invoke('text').should('match', /1\s+store/i)   // "1 store selected"

    cy.get('.locpick__chip').first().click()                        // toggle back off
    cy.get('.locpick__chip').first().should('have.attr', 'aria-pressed', 'false')
    cy.get('.locpick__count').invoke('text').should('match', /No store selected|inherit/i)
  })

  it('Select all selects every chip; Clear deselects them all', () => {
    openTeam()
    cy.get('.locpick__chip').then(($chips) => {
      const n = $chips.length
      cy.contains('.locpick__action', 'Select all').click()
      cy.get('.locpick__chip[aria-pressed="true"]').should('have.length', n)
      cy.contains('.locpick__action', 'Clear').click()
      cy.get('.locpick__chip[aria-pressed="true"]').should('have.length', 0)
    })
  })

  it('a member created with chips selected gets those store grants', () => {
    openTeam()
    const email = `cy.picker.${uniq()}@myplus.com`
    // Slice 106: break the chain. Clicking a chip re-renders the picker, so the element the chain is holding
    // detaches and cy.should() cannot requery it. Alias, click, then re-query — Cypress's own prescribed fix.
    cy.get('.locpick__chip').first().as('firstChip')
    cy.get('@firstChip').click()
    cy.get('.locpick__chip').first().should('have.attr', 'aria-pressed', 'true')
    cy.get('.locpick__chip').first().invoke('text').then((chipLabel) => {
      cy.get('#teamFirstName').clear().type('CY')
      cy.get('#teamLastName').clear().type('Picker')
      cy.get('#teamEmail').clear().type(email)
      cy.get('#addTeamUser').click()
      // The member lands in the team table with the store it was assigned.
      cy.get('#tableTeam tbody', { timeout: 10000 }).should('contain', email)
      cy.get('#tableTeam tbody tr').contains('td', email).parent()
        .should('contain', chipLabel.trim())
    })
  })
})

describe('Team & Users: education uses the same picker (branches)', () => {
  before(() => {
    // Ensure the education owner has >= 1 branch so the picker has chips.
    cy.loginAsEduOwner()
    cy.request('/getUserSchool').then((r) => {
      if (rows(r.body).length === 0) {
        const tag = uniq()
        cy.request({
          method: 'POST', url: '/addSchool', form: true,
          body: { name: `CY Picker Branch ${tag}`, branchName: `CY Picker Branch ${tag}`, status: 'ACTIVE' },
          failOnStatusCode: false,
        })
      }
    })
  })

  it('the education Manage Users screen shows the same chip picker for branches', () => {
    cy.loginAsEduOwner()
    cy.visit('/educationDashboard')
    cy.window().then((w) => w.showTeam())
    cy.get('#TeamDiv').should('be.visible')
    cy.get('#teamStores.locpick', { timeout: 10000 }).should('exist')
    cy.get('#teamStores select').should('not.exist')
    cy.get('#teamStores .locpick__chip', { timeout: 10000 }).should('have.length.greaterThan', 0)
  })
})
