/**
 * Does toggling "Keyboard sale entry" (#bcfg_pos_keyboard_enabled) actually take effect?
 *
 * The reported symptom is "Sale entry toggle change not reflect". This drives that ONE checkbox the way
 * a person does — click it on the Configuration screen — and then checks three things in order, so a
 * failure says WHERE the chain breaks rather than just "it didn't work":
 *
 *   1. the value was SAVED            (the server accepted it)
 *   2. the live FLAG changed          (loadPosFeatureFlags re-read it, no reload)
 *   3. the SALE SCREEN changed        (applyPosKeyboard acted on it)
 *
 * Run headed.
 */

const BOX = '#bcfg_pos_keyboard_enabled'

function openConfiguration() {
  cy.get('#snavSettings').should('exist')
  cy.get('#snavSettings .snav-btn').click({ timeout: 30000 })
  cy.get('#snavSettings').should('have.class', 'snav-open')
  cy.get('#navConfiguration').should('be.visible').click({ timeout: 30000 })
  cy.get('#ConfigDiv').should('be.visible')
  cy.get('#businessConfigBody', { timeout: 30000 }).should('contain', 'Sale entry')
}

describe('Configuration — the Keyboard sale entry toggle', () => {
  beforeEach(() => { cy.loginAsOwner() })

  // Leave the tenant on the catalog default (ON) whatever the test did.
  afterEach(() => {
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true,
                 body: { key: 'pos.keyboard.enabled', value: 'true' }, failOnStatusCode: false })
  })

  it('the setting is rendered on the Configuration screen', () => {
    cy.visit('/businessDashboard')
    openConfiguration()
    // If this fails, business-service is serving an older catalog — the setting does not exist yet.
    cy.get(BOX).should('exist')
  })

  it('unticking it saves, clears the live flag, and the sale screen follows', () => {
    cy.intercept('POST', '**/saveBusinessConfig').as('save')
    cy.visit('/businessDashboard')
    openConfiguration()

    cy.get(BOX).uncheck()

    // 1. saved
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.get('#businessConfigMsg', { timeout: 20000 }).should('contain', 'Saved')

    // 2. the live flag — no reload happened
    cy.window({ timeout: 20000 }).its('posKeyboardEnabled').should('eq', false)

    // 3. the sale screen actually followed: the keyboard hint is hidden and the read-only
    //    display fields are back in the tab order.
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
    cy.get('#sellKbdHint').should('not.be.visible')
    cy.get('#sellStock').should('not.have.attr', 'tabindex')
  })

  it('ticking it back on restores the flag and the sale screen', () => {
    cy.intercept('POST', '**/saveBusinessConfig').as('save2')
    cy.visit('/businessDashboard')
    openConfiguration()

    // Start from a known OFF state so the tick is a real change.
    cy.get(BOX).uncheck()
    cy.wait('@save2')
    cy.window({ timeout: 20000 }).its('posKeyboardEnabled').should('eq', false)

    cy.get(BOX).check()
    cy.wait('@save2').its('response.statusCode').should('eq', 200)
    cy.window({ timeout: 20000 }).its('posKeyboardEnabled').should('eq', true)

    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
    cy.get('#sellStock').should('have.attr', 'tabindex', '-1')   // dead stops removed again
  })

  it('the saved value SURVIVES a reload — it is stored, not just in memory', () => {
    cy.intercept('POST', '**/saveBusinessConfig').as('save3')
    cy.visit('/businessDashboard')
    openConfiguration()
    cy.get(BOX).uncheck()
    cy.wait('@save3')

    // A stored override must beat the catalog default (which is ON) on the next page load.
    cy.visit('/businessDashboard')
    cy.window({ timeout: 30000 }).its('posKeyboardEnabled').should('eq', false)
  })
})
