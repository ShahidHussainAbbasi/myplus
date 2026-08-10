/**
 * Who can reach the Configuration screen (UI/UX D-6).
 *
 * The POS behaviour toggles (P1/P2/P3) were asked to be settable by "owner/admin", but the screen was
 * gated ROLE_OWNER alone — while the shared SettingsController has always permitted
 * `ROLE_OWNER or ADMIN_PRIVILEGE` to write an override. So an admin could change these settings by API
 * and not see them in the UI. This widens the SCREEN to match the authorisation that already existed;
 * it grants nothing the server was refusing.
 *
 * The four other Settings entries (Stores, Tax Settings, Price Rules, Document Designer) stay
 * owner-only. That is the point of the per-item gating and the thing most likely to regress silently:
 * widening the menu wholesale would have handed an admin four unrelated owner screens.
 *
 * FIXTURES — both seeded by auth-service SetupDataLoader behind its dev-only fixtures flag:
 *   owner.business@myplus.com   ROLE_OWNER
 *   admin.store@myplus.com      ADMIN_ROLE, a member of the OWNER'S organization
 *
 * `admin.store` is deliberately used rather than `demo.business`. The demo account carries DEMO_ROLE
 * (= superSet + demo privileges) in its OWN org — it happens to include ADMIN_PRIVILEGE, but it is a
 * super account, not an admin, so it would only approximate the case under test. `admin.store` is a
 * real admin inside the owner's org, which is exactly the person this change is for.
 *
 * Each role asserts both what it CAN and what it CANNOT see, so a change to the seeded roles fails
 * loudly rather than passing vacuously.
 *
 * Run headed.
 */

const DEMO_PW = 'Demo@2025!'

/** A real ADMIN (not owner) in the owner's organization. */
function loginAsStoreAdmin() {
  cy.loginAs('admin.store@myplus.com', DEMO_PW, '/getBusinessDashboardStats')
}

/**
 * Walk the real user path to the Configuration screen.
 *
 * The Settings group is an ACCORDION, not a plain list: `.snav-menu` sits at `max-height:0` until its
 * button adds `.snav-open` (sidebar.css). So the entry exists in the DOM but is not clickable until
 * the group is expanded — which is why the first draft, clicking the item straight away, failed.
 *
 * Deliberately not `{force:true}` and not calling showBusinessConfig() directly: expanding the group
 * and clicking the entry IS the thing under test. Forcing past a closed menu would let this pass even
 * if the entry were unreachable for the role.
 */
function openConfiguration() {
  cy.get('#snavSettings').should('exist')
  cy.get('#snavSettings .snav-btn').click({ timeout: 30000 })
  cy.get('#snavSettings').should('have.class', 'snav-open')
  cy.get('#navConfiguration').should('be.visible').click({ timeout: 30000 })
}

describe('Configuration screen access — owner', () => {
  beforeEach(() => { cy.loginAsOwner() })

  it('an owner sees Configuration and every other Settings entry', () => {
    cy.visit('/businessDashboard')
    cy.get('#snavSettings').should('exist')
    cy.get('#navConfiguration').should('exist')
    // Still owner-only, and still present for the owner — the widening must not have moved these.
    cy.get('#navPriceRules').should('exist')
    cy.get('#navDocumentDesigner').should('exist')
  })

  it('an owner can open the Configuration screen and it renders the POS settings', () => {
    cy.visit('/businessDashboard')
    openConfiguration()
    cy.get('#ConfigDiv').should('be.visible')
    // Self-rendered from the service catalog, so seeing a P1/P2/P3 key proves the whole chain:
    // catalog entry -> /getBusinessConfig -> settings-form.js.
    cy.get('#businessConfigBody', { timeout: 30000 }).should('contain', 'Sale entry')
  })
})

describe('Configuration screen access — admin (not owner)', () => {
  beforeEach(() => { loginAsStoreAdmin() })

  it('an admin reaches Configuration', () => {
    cy.visit('/businessDashboard')
    cy.get('#snavSettings').should('exist')
    cy.get('#navConfiguration').should('exist')
  })

  /**
   * The regression this file exists for. Before the per-item gating the whole Settings menu was
   * owner-only; the lazy fix — widening the menu — would have given an admin Stores, Tax Settings,
   * Price Rules and the Document Designer as well. None of those were asked for.
   */
  it('an admin does NOT get the owner-only Settings entries', () => {
    cy.visit('/businessDashboard')
    cy.get('#navConfiguration').should('exist')          // ...so the menu really did render
    cy.get('#navPriceRules').should('not.exist')
    cy.get('#navDocumentDesigner').should('not.exist')
  })

  it('an admin can open Configuration and change a POS setting', () => {
    cy.visit('/businessDashboard')
    openConfiguration()
    cy.get('#ConfigDiv').should('be.visible')
    cy.get('#businessConfigBody', { timeout: 30000 }).should('contain', 'Sale entry')

    // The server has always allowed this; only the screen was narrower. Save through the same proxy
    // the Configuration screen uses, then put it back so the suite leaves no state behind.
    cy.request({
      method: 'POST', url: '/saveBusinessConfig',
      form: true, body: { key: 'pos.keyboard.enabled', value: 'false' },
      failOnStatusCode: false
    }).then((r) => {
      expect(r.status, 'an admin may write a setting').to.eq(200)
    })
  })
})

/**
 * Saving a setting must CHANGE THE LIVE PAGE, not just the database.
 *
 * saveBusinessConfigToggle used to apply exactly three keys by name (pos.barcode.enabled,
 * pos.receipt.autoPrint, pharmacy.interaction.blockSevere). Every setting added since — all ~35
 * sale-screen ones, pos.keyboard.shortcuts.enabled among them — saved correctly and then did nothing
 * until the page was reloaded, because window.pos* is only populated at load. The screen said
 * "Saved." and the till behaved as before: a failure that looks exactly like success.
 *
 * These assert the FLAG ON THE LIVE PAGE, never the response body — the response was always fine.
 */
describe('Configuration — a saved setting takes effect without a reload', () => {
  beforeEach(() => { cy.loginAsOwner() })

  // Leave the tenant as we found it. These restore each key to its CATALOG DEFAULT, which is not the
  // same for both: pos.keyboard.enabled now ships ON (the compact row is the standard sale screen),
  // while the shortcut keys still ship OFF. Writing 'false' to both would have left the row layout
  // switched off for the tenant — a test tidying up by changing the product's behaviour.
  afterEach(() => {
    const defaults = { 'pos.keyboard.enabled': 'true', 'pos.keyboard.shortcuts.enabled': 'false' }
    Object.keys(defaults).forEach((k) => {
      cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true,
                   body: { key: k, value: defaults[k] }, failOnStatusCode: false })
    })
  })

  it('toggling shortcuts ON sets the live flag, and OFF clears it', () => {
    cy.visit('/businessDashboard')
    openConfiguration()
    cy.get('#businessConfigBody', { timeout: 30000 }).should('contain', 'Sale entry')

    // The checkbox the renderer generated for this key — ids are the key with dots replaced.
    const box = '#bcfg_pos_keyboard_shortcuts_enabled'
    cy.get(box).should('exist').check()
    cy.get('#businessConfigMsg', { timeout: 20000 }).should('contain', 'Saved')
    // The point: no reload happened, and the flag the F-keys read is now true.
    cy.window({ timeout: 20000 }).its('posShortcutsEnabled').should('eq', true)

    cy.get(box).uncheck()
    cy.get('#businessConfigMsg', { timeout: 20000 }).should('contain', 'Saved')
    cy.window({ timeout: 20000 }).its('posShortcutsEnabled').should('eq', false)
  })

  it('a non-checkbox setting also re-applies (the chain never covered these at all)', () => {
    cy.visit('/businessDashboard')
    openConfiguration()
    cy.get('#businessConfigBody', { timeout: 30000 }).should('contain', 'Sale entry')

    cy.get('#bcfg_pos_entry_defaultQty').should('exist').clear().type('4').trigger('change')
    cy.get('#businessConfigMsg', { timeout: 20000 }).should('contain', 'Saved')
    cy.window({ timeout: 20000 }).its('posDefaultQty').should('eq', 4)

    cy.get('#bcfg_pos_entry_defaultQty').clear().type('1').trigger('change')
    cy.get('#businessConfigMsg', { timeout: 20000 }).should('contain', 'Saved')
  })
})
