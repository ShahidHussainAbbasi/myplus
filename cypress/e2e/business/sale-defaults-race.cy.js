/**
 * SALE-DEF — the shop's sale-screen DEFAULTS must never overwrite a choice the cashier has already made.
 *
 * Found 2026-09-15 by sell.cy.js on the PSEL-1 build (2 reds, traced independently by both sessions):
 * loadPosFeatureFlags → applyPosFieldVisibility applies the tenant's default tender and customer mode whenever
 * /getBusinessConfig lands. Its guard (`saleInProgress`: a cart line or an invoice edit) protects a part-rung sale but
 * not choices made on an EMPTY cart — so a cashier who picked Credit, Enter Manually, or a customer before the
 * settings arrived had it silently undone, and onCustomerModeChange() also ERASES a typed walk-in name. The code dates
 * from 43a4ba82 (08-09) and 916d5f3a (08-10); the 7-17 s New Sale freeze used to guarantee the settings landed before
 * any click, and PSEL-1 removed the freeze. (Not a money loss: an unpaid sale still records its due — myplus-11's
 * correction. It is a wrong tender label and an erased name.)
 *
 * DETERMINISTIC, NOT TIMING LUCK: the settings reply is HELD for DELAY ms by the intercept, so every choice is made
 * before the defaults land — and each case first ASSERTS the settings have not landed yet, or it would pass without
 * testing anything. The defaults are rewritten in the BROWSER to values that differ from the choice (browser-only;
 * the tenant's saved configuration is never touched). Case 4 is the control: an untouched sale still gets them.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/sale-defaults-race.cy.js
 */

const DELAY = 5000

/**
 * Open New Sale with the settings reply held back and carrying these defaults.
 * Shape of the reply: { data: [{ key, value, isDefault }] } (business.js posSettingRaw).
 */
function openSaleWithLateSettings(defaults) {
  cy.intercept('GET', '**/getBusinessConfig', (req) => {
    req.continue((res) => {
      const rows = (res.body && res.body.data) || []
      Object.keys(defaults).forEach((key) => {
        const row = rows.find((r) => r.key === key)
        if (row) row.value = defaults[key]
        else rows.push({ key, value: defaults[key], isDefault: false })
      })
      if (res.body) res.body.data = rows
      res.setDelay(DELAY)
    })
  }).as('lateSettings')
  cy.visit('/businessDashboard')
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellDiv').should('be.visible')
}

/** The precondition that makes a case able to fail: the choice below is made BEFORE the defaults are applied. */
function settingsHaveNotLanded() {
  cy.window().then((w) => {
    expect(w.posDefaultTender,
      'the settings must NOT have landed yet — otherwise the choice is made after the defaults and survives trivially')
      .to.be.undefined
  })
}

/** …and then let them land, and prove they did before asserting anything about them. */
function settingsLand(expectedTender) {
  cy.wait('@lateSettings', { timeout: DELAY + 30000 })
  cy.window().should((w) => {
    expect(w.posDefaultTender, 'the settings have now been applied').to.eq(expectedTender)
  })
}

describe('SALE-DEF — a late settings load never undoes the cashier\'s choice', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('⭐⭐ 1 — Credit chosen before the settings land stays Credit', () => {
    openSaleWithLateSettings({ 'pos.tender.default': 'CARD' })
    settingsHaveNotLanded()
    cy.get('#sellPayMethod').select('CREDIT', { force: true })
    settingsLand('CARD')
    cy.get('#sellPayMethod').should('have.value', 'CREDIT')
  })

  it('⭐⭐ 2 — Enter Manually + a typed walk-in name survive the settings landing', () => {
    openSaleWithLateSettings({ 'pos.customer.defaultMode': 'select' })
    settingsHaveNotLanded()
    cy.get('#btnModeManual').click()
    cy.get('#sellCN').should('be.visible').type('Walk-in Ali')
    settingsLand('CASH')
    cy.get('#customerManualMode').should('be.visible')
    cy.get('#sellCN').should('have.value', 'Walk-in Ali')   // onCustomerModeChange would have erased it
  })

  it('⭐⭐ 3 — a customer chosen in Select mode is not wiped by a Manual default', () => {
    openSaleWithLateSettings({ 'pos.customer.defaultMode': 'manual' })
    cy.get('#sellCustomerDD option:not([value=""])', { timeout: DELAY - 1000 }).should('have.length.greaterThan', 0)
    settingsHaveNotLanded()
    cy.get('#sellCustomerDD option:not([value=""])').first().then(($o) => {
      const id = String($o.val())
      cy.get('#sellCustomerDD').select(id, { force: true })
      settingsLand('CASH')
      cy.get('#customerSelectMode').should('be.visible')
      cy.get('#sellCustomerDD').should('have.value', id)
    })
  })

  it('⭐ 4 — CONTROL: an untouched sale still gets the shop\'s defaults', () => {
    openSaleWithLateSettings({ 'pos.tender.default': 'CARD', 'pos.customer.defaultMode': 'manual' })
    settingsHaveNotLanded()
    settingsLand('CARD')
    cy.get('#sellPayMethod').should('have.value', 'CARD')
    cy.get('#customerManualMode').should('be.visible')
  })
})
