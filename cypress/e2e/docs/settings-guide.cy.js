/**
 * Settings & Configuration — the MANUAL TEST GUIDE, captured from the real app.
 *
 * Each `it` is one manual step: it performs exactly what the published guide tells a tester to do, ASSERTS the
 * expected result, then screenshots the screen. afterEach records pass/fail into the manifest, and the page
 * builder (docs/guides/build-settings-guide.js) embeds ONLY the steps that passed — so the guide can never show
 * a screen as "expected" that the app did not actually produce. Re-run this spec and rebuild the page whenever
 * the UI changes: the guide then matches the app by construction.
 *
 * Tenant: owner.business@myplus.com (org 13). Every switch a step flips is put back in the same step AND in
 * after() — leave no server state.
 *
 * Run headed:  npx cypress run --headed --browser electron --spec cypress/e2e/docs/settings-guide.cy.js
 */
const OUT = 'cypress/guide-out/settings-guide.json'
const steps = []
let current = null

/** Declare the step's guide text; the screenshot is named after its id. */
const step = (id, section, title, how, expected) => { current = { id, section, title, how, expected, shots: [] } }
const shot = (name, subject) => {
  const file = `${current.id}-${name}`
  current.shots.push(file)
  // No subject = what the tester sees on screen (the VIEWPORT). cy.root().screenshot() ignores `capture` and
  // stitched the whole 13,000px document — one picture of everything is a picture of nothing.
  return subject ? cy.get(subject).screenshot(file, { overwrite: true })
                 : cy.screenshot(file, { capture: 'viewport', overwrite: true })
}

const openConfig = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.get('#snavSettings').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavSettings .snav-btn').click() })
  cy.get('#navConfiguration').click()
  cy.get('#ConfigDiv').should('be.visible')
  cy.get('#businessConfigBody .cfg-group', { timeout: 20000 }).should('have.length.greaterThan', 10)
  cy.get('#businessConfigBody .cfg-rail__item', { timeout: 20000 }).should('have.length.greaterThan', 3)
}
/** UI-CFG-1: a row is only on screen inside its own category — reveal it (cy.revealSetting) before touching it. */
const row = (key) => { cy.revealSetting(key); return cy.get(`#businessConfigBody [data-key="${key}"]`).closest('.cfg-row') }
const setCfg = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value: String(value) } })
    .its('body.success').should('eq', true)
/** Reset to default — REMOVES the override, so the preset / business type decides again (UI-CFG-1). */
const resetCfg = (key) =>
  cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key } })
    .its('body.success').should('eq', true)

/*
 * What each setting was BEFORE this run, restored in after().
 *
 * ⚠ A setting the owner never touched has NO override, and the only honest way back to that is Reset to default.
 * Saving the default value instead PINS it: the shop preset and the business type can no longer move it (SET-CERT
 * B-020 — a pinned showFormula=false kept the Pharmacy preset from showing Formula). So remember `isDefault` too:
 * untouched → reset; overridden → save the override that was there.
 */
const ORIGINAL = {}
const remember = (key) => cy.request('/getBusinessConfig').then((r) => {
  const find = (o) => { if (!o || typeof o !== 'object') return null; if (o.key === key) return o; for (const v of Object.values(o)) { const f = find(v); if (f) return f } return null }
  const e = find(r.body)
  expect(e, `setting ${key} exists in the catalogue`).to.exist
  expect(e, `setting ${key} says whether it is overridden`).to.have.property('isDefault')
  ORIGINAL[key] = { isDefault: e.isDefault === true, value: String(e.value) }
})
const restore = (key) => (ORIGINAL[key].isDefault ? resetCfg(key) : setCfg(key, ORIGINAL[key].value))
/** Open the Settings dropdown — only if it is closed: after a page load it may already be open, and a click closes it. */
const openSettingsMenu = () => {
  cy.get('#snavSettings').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavSettings .snav-btn').click() })
  cy.get('#snavSettings').should('have.class', 'snav-open')
}

describe('Settings & Configuration — manual test guide (captured)', () => {
  before(() => {
    cy.loginAsOwner()
    ;['pos.park.enabled', 'pos.tender.default', 'org.cap.bonusSchemes', 'pos.entry.preset', 'pos.customer.required',
      'pos.customer.walkInName']
      .forEach(remember)
  })
  beforeEach(() => { cy.viewport(1366, 860); cy.loginAsOwner() })
  afterEach(function () {
    current.passed = this.currentTest.state === 'passed'
    steps.push(current)
  })
  after(() => {
    cy.loginAsOwner()
    Object.keys(ORIGINAL).forEach(restore)
    // The settings TABLE on the page comes from the same run as the screenshots — never from a hand-kept list.
    const flat = (b) => { const out = []; const walk = (o) => { if (!o || typeof o !== 'object') return
      if (o.key && o.label && o.type) out.push({ key: o.key, label: o.label, help: o.help, type: o.type, group: o.group,
        def: o.defaultValue, options: (o.options || []).map((x) => (x && x.value) || x),
        optionsFull: (o.options || []).map((x) => (x && typeof x === 'object' ? { value: x.value, label: x.label } : { value: x, label: x })),
        locked: !!o.locked })
      else Object.values(o).forEach(walk) }; walk(b); return out }
    const cat = {}
    cy.request('/getBusinessConfig').then((r) => { cat.configuration = flat(r.body) })
    // Order settings as the business that can actually reach them: the Store menu is marketplace-only.
    cy.loginAsMarketplaceOwner(); cy.request('/getOrderConfig').then((r) => { cat.orders = flat(r.body) })
    // Every module's settings screen, read as its own owner, in the same run.
    cy.loginAsEduOwner(); cy.request('/getConfig').then((r) => { cat.education = flat(r.body) })
    cy.loginAsWelfareOwner(); cy.request('/getWelfareConfig').then((r) => { cat.welfare = flat(r.body) })
    cy.loginAsAgricultureOwner(); cy.request('/getAgricultureConfig').then((r) => { cat.agriculture = flat(r.body) })
    cy.then(() => cy.writeFile(OUT, { capturedAt: new Date().toISOString(), tenant: 'owner.business@myplus.com', steps, catalog: cat }))
  })

  // ── A. Getting there ────────────────────────────────────────────────────────────────────────────────
  it('A1 — open Settings → Configuration', () => {
    step('A1', 'Getting there', 'Open Settings → Configuration',
      ['Log in as the business owner.', 'In the left menu, click **Settings**, then **Configuration**.'],
      ['The Configuration screen opens with a list of categories on the left — **Business**, **Selling**, **Receipts & documents** and so on — and the settings of the first category on the right.',
       'Each category shows how many settings it holds; a dot marks a category where something was changed from the default.',
       'On a phone the categories become a row of chips above the settings.'])
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    openSettingsMenu()
    shot('menu', '#snavSettings')
    cy.get('#navConfiguration').click()
    cy.get('#businessConfigBody .cfg-group', { timeout: 20000 }).should('have.length.greaterThan', 10)
    cy.get('#businessConfigBody .cfg-search input').should('be.visible')
    cy.get('#businessConfigBody .cfg-rail__item[aria-selected="true"]').should('have.length', 1)
    // The picture must show what this step describes — the category rail and the first category. The support-session
    // card above it can fill the first screen on a tenant with many sessions (it did on the review run).
    cy.get('#businessConfigBody .cfg-layout').scrollIntoView()
    shot('screen')
  })

  it('A2 — search narrows the list', () => {
    step('A2', 'Getting there', 'Find a setting by searching',
      ['Type **receipt** in the search box at the top of Configuration.', 'Clear the box.'],
      ['The search looks through **every** category, not only the one that is open.',
       'Each category shows how many of its settings match; categories with no match are dimmed.',
       'Only matching settings stay on screen, under their category headings.',
       'Clearing the box brings back the category you were on.'])
    openConfig()
    cy.get('#businessConfigBody .cfg-search input').type('receipt')
    cy.get('#businessConfigBody').should('have.class', 'cfg-searching')
    cy.get('#businessConfigBody .cfg-row:visible').should('have.length.greaterThan', 0).each(($r) => {
      // label + help on screen, or the setting's key (the search matches keys too, for support notes)
      const hay = ($r.text() + ' ' + ($r.find('[data-key]').attr('data-key') || '')).toLowerCase()
      expect(hay, 'every row left on screen matches').to.contain('receipt')
    })
    cy.get('#businessConfigBody .cfg-rail__item[data-cat="documents"] .cfg-rail__hits').invoke('text').then((t) => {
      expect(parseInt(t, 10), 'the Receipts & documents category counts its matches').to.be.greaterThan(0)
    })
    shot('search')
    cy.get('#businessConfigBody .cfg-search input').clear()
    cy.get('#businessConfigBody').should('not.have.class', 'cfg-searching')
  })

  // ── B. Every group, as it looks ─────────────────────────────────────────────────────────────────────
  it('B1 — every settings category renders', () => {
    step('B1', 'The settings, category by category', 'Review each settings category',
      ['Click each category on the left in turn, from **Business** to the last one.'],
      ['Each category below opens on the right with its settings, labels and help text as pictured.',
       'Switches show on/off, lists show the chosen option, and numbers show their value.',
       'A setting changed from the default wears a dot and a **Reset to default** link.'])
    openConfig()
    cy.get('#businessConfigBody .cfg-rail__item').each(($c, i) => {
      const name = $c.find('.cfg-rail__label').text().trim()
      const file = `${current.id}-cat-${$c.attr('data-cat')}`
      current.groups = current.groups || []
      current.groups.push({ name, file })
      current.shots.push(file)
      cy.wrap($c).click()
      cy.wrap($c).should('have.attr', 'aria-selected', 'true')
      cy.get('#businessConfigBody .cfg-pane__title').should('contain', name)
      cy.get('#businessConfigBody .cfg-row:visible').should('have.length.greaterThan', 0)
      // The PANE, not the whole layout: the rail is sticky, so a stitched capture of a tall category repeated it in
      // every frame (Selling showed it five times). The rail is pictured in A1.
      cy.get('#businessConfigBody .cfg-pane').screenshot(file, { overwrite: true })
    })
  })

  // ── C. A switch takes effect ────────────────────────────────────────────────────────────────────────
  it('C1 — switching a setting OFF saves and takes effect (Park a sale)', () => {
    step('C1', 'Changing a setting', 'Turn a switch off and see the effect',
      ['In **Selling → Workflow**, untick **Allow parking a sale to serve the next customer**.',
       'Open **Sale → New Sale**.', 'Go back and tick it again.'],
      ['A green “Saved” mark appears on the row as soon as you untick it.',
       'On New Sale, the **Park** button is gone.', 'After ticking it again, **Park** is back.'])
    setCfg('pos.park.enabled', true)
    openConfig()
    row('pos.park.enabled').scrollIntoView()
    cy.get('#businessConfigBody [data-key="pos.park.enabled"]').uncheck()
    cy.get('#businessConfigMsg').should('be.visible').and('contain', 'Saved')
    row('pos.park.enabled').closest('.cfg-group').as('grp')
    shot('saved', '@grp')
    cy.openSellSection('sellDiv')
    cy.get('#parkSaleBtn').should('not.be.visible')
    shot('sale-no-park')
    restore('pos.park.enabled')
    cy.openSellSection('sellDiv')
    cy.get('#parkSaleBtn').should('be.visible')
  })

  it('C2 — a list setting (default payment method)', () => {
    step('C2', 'Changing a setting', 'Change a list setting',
      ['In **Selling → Payment**, set **Payment method selected by default** to **CARD**.', 'Open **Sale → New Sale**.',
       'Click **Reset to default** on that row afterwards.'],
      ['The payment method on a new sale starts on **CARD**.'])
    openConfig()
    row('pos.tender.default').scrollIntoView()
    cy.get('#businessConfigBody [data-key="pos.tender.default"]').select('CARD', { force: true })
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    row('pos.tender.default').closest('.cfg-group').as('grp')
    shot('setting', '@grp')
    cy.openSellSection('sellDiv')
    cy.get('#sellPayMethod', { timeout: 15000 }).should('have.value', 'CARD')
    shot('sale-card')
    restore('pos.tender.default')
  })

  it('C3 — Reset to default puts a changed setting back', () => {
    step('C3', 'Changing a setting', 'Put a changed setting back to its default',
      ['In **Selling → Customer & credit**, change **Name to use for a walk-in sale** to **Counter guest**.',
       'Look at the row and at the **Selling** category on the left.', 'Click **Reset to default** on the row.'],
      ['The changed row shows a dot and a **Reset to default** link; the **Selling** category shows a dot too.',
       'After **Reset to default** the row shows the default again, and the dot and link are gone.',
       'Reset is not the same as typing the default back in: a reset setting follows the shop preset and business type again.'])
    const KEY = 'pos.customer.walkInName'
    resetCfg(KEY)
    openConfig()
    row(KEY).as('r')
    cy.get('@r').find('.cfg-row__reset').should('not.exist')
    cy.get(`#businessConfigBody [data-key="${KEY}"]`).clear().type('Counter guest').blur()
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    // The save re-renders nothing; the marker comes with the next render (a reopen), which is what a tester sees.
    openConfig()
    row(KEY).as('r')
    cy.get('@r').find('.cfg-row__changed').should('exist')
    cy.get('@r').find('.cfg-row__reset').should('be.visible')
    cy.get('#businessConfigBody .cfg-rail__item[data-cat="selling"] .cfg-rail__changed').should('exist')
    row(KEY).closest('.cfg-group').as('grpChanged')
    shot('changed', '@grpChanged')   // the group, not the whole (stitched) layout
    cy.get('@r').find('.cfg-row__reset').click()
    cy.get('#businessConfigMsg').should('contain', 'default')
    row(KEY).find('.cfg-row__reset').should('not.exist')
    cy.request('/getBusinessConfig').then((res) => {
      const find = (o) => { if (!o || typeof o !== 'object') return null; if (o.key === KEY) return o; for (const v of Object.values(o)) { const f = find(v); if (f) return f } return null }
      expect(find(res.body).isDefault, 'the override is REMOVED, not saved as the default').to.eq(true)
    })
    row(KEY).closest('.cfg-group').as('grpReset')
    shot('reset', '@grpReset')
    restore(KEY)
  })

  // ── D. What this business does (capabilities) ───────────────────────────────────────────────────────
  it('D1 — a capability switch adds or removes a whole feature', () => {
    step('D1', 'What this business does', 'Switch a feature off and on',
      ['In **Business → What this business does**, untick **Bonus and free-goods offers**.', 'Open the **Settings** menu.',
       'Tick it again and reopen the menu.'],
      ['With it off, **Bonus Schemes** disappears from the Settings menu.', 'With it on, the menu item is back.'])
    setCfg('org.cap.bonusSchemes', true)
    openConfig()
    row('org.cap.bonusSchemes').scrollIntoView()
    cy.get('#businessConfigBody [data-key="org.cap.bonusSchemes"]').uncheck()
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    row('org.cap.bonusSchemes').closest('.cfg-group').as('grp')
    shot('off', '@grp')
    // Asserted on the CAPABILITY state of the menu item (cap-off), with the menu open — "not visible" alone would
    // pass just as happily on a closed menu, which is how the first draft of this step read.
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    openSettingsMenu()
    cy.get('#navBonusSchemes').closest('li').should('have.class', 'cap-off')
    cy.get('#navConfiguration').should('be.visible')
    shot('menu-without', '#snavSettings')
    setCfg('org.cap.bonusSchemes', true)
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    openSettingsMenu()
    cy.get('#navBonusSchemes').closest('li').should('not.have.class', 'cap-off')
    cy.get('#navBonusSchemes').should('be.visible')
    shot('menu-with', '#snavSettings')
    restore('org.cap.bonusSchemes')
  })

  it('D2 — a feature outside the plan is locked', () => {
    step('D2', 'What this business does', 'See a feature your plan does not include',
      ['In **Business → What this business does**, find a row marked **🔒 Not in plan** (for example *Dine-in, take-away and delivery*).',
       'Hover over the lock.'],
      ['The switch is greyed out and cannot be changed.',
       'The tooltip says the feature is not in the current plan and to contact MaxTheService.'])
    openConfig()
    // A PLAN lock specifically. The opening-balance cutover row is locked too, for an accounting reason — since
    // SET-CERT F3 it says "Locked", not "Not in plan" (data-lock tells the two apart).
    cy.get('#businessConfigBody .cfg-row--locked').filter((i, r) => /plan/i.test(Cypress.$(r).find('.cfg-row__locked').attr('title') || ''))
      .should('have.length.greaterThan', 0).first().find('[data-key]').invoke('attr', 'data-key').then((k) => {
        row(k).as('locked')
      })
    cy.get('@locked').scrollIntoView().find('input,select').should('be.disabled')
    cy.get('@locked').find('.cfg-row__locked').should('have.attr', 'title').and('match', /not included in your current plan/i)
    shot('locked', '@locked')
  })

  it('D3 — changing the business type warns before resetting switches', () => {
    step('D3', 'What kind of business this is', 'Preview a business-type change, then cancel',
      ['In **Business → What kind of business this is**, change **Type of business** to **pharmacy**.', 'Read the dialog, then click **Cancel**.'],
      ['A dialog lists exactly which switches will turn **ON** and **OFF**.',
       'After **Cancel**, the type goes back to what it was and nothing changes.'])
    openConfig()
    row('org.shape')
    cy.get('#businessConfigBody [data-key="org.shape"]').then(($s) => {
      const before = $s.val()
      const target = before === 'pharmacy' ? 'retail' : 'pharmacy'
      cy.wrap($s).scrollIntoView().select(target, { force: true })
      cy.get('.uiC-btn.uiC-cancel', { timeout: 15000 }).should('be.visible')
      cy.contains(/Turning (ON|OFF)|No switches change/).should('be.visible')
      // The impact lines are built with t(key, …args). Passing an English fallback as the 2nd argument substituted it
      // into {0} and printed "open installment plans ({1})" a second time — so: no raw placeholder, no sentence twice.
      cy.get('.uiC-body').invoke('text').then((txt) => {
        expect(txt, 'no untranslated {n} placeholder').to.not.match(/\{\d\}/)
        const lines = txt.split(/(?<=\.)\s+/).map((x) => x.trim()).filter(Boolean)
        const dup = lines.find((l, i) => /installment plans|serial number/i.test(l) && lines.indexOf(l) !== i)
        expect(dup, 'no impact sentence repeated').to.eq(undefined)
        expect((txt.match(/stay collectable/g) || []).length, 'the open-plans line appears once').to.be.at.most(1)
      })
      shot('dialog')
      cy.get('.uiC-btn.uiC-cancel').click()
      cy.get('#businessConfigBody [data-key="org.shape"]').should('have.value', before)
    })
  })

  // ── E. Sale entry presets ───────────────────────────────────────────────────────────────────────────
  it('E1 — the Pharmacy preset shows the Formula field on the product form', () => {
    step('E1', 'Sale entry', 'Apply a shop preset',
      ['In **Selling → Sale entry**, set **What kind of shop is this?** to **PHARMACY**.', 'Open **Register → Products → + New**.',
       'Click **Reset to default** on the preset row afterwards.'],
      ['The product form now shows a **Formula** field.', 'Switches you set yourself are not changed by the preset.'])
    setCfg('pos.entry.preset', 'PHARMACY')
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    cy.window().then((w) => { w.showProducts(); w.newProduct() })
    cy.get('#ProductModal').should('have.class', 'open')
    cy.get('#ProductModal [data-pos-field="formula"]').should('be.visible')
    shot('formula', '#ProductModal .crud-box')
    restore('pos.entry.preset')
  })

  // ── F. The other settings screens ───────────────────────────────────────────────────────────────────
  // Moved to settings-guide-screens.cy.js: each screen is now a full manual test case (prerequisites, test data,
  // numbered actions with expected results and pictures, cleanup), not a single picture of the screen.

  // ── G. Who may change settings ──────────────────────────────────────────────────────────────────────
  it('G1 — a staff user cannot open Settings', () => {
    step('G1', 'Who may change settings', 'Log in as a staff user',
      ['Log out, then log in as a **user** (cashier) of the same business.', 'Look for **Settings** in the left menu.'],
      ['There is no **Settings** menu.', 'Only the owner (or a user with admin privilege) can change settings.'])
    cy.loginAsTier('user', 'business')
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    cy.get('#snavSettings').should('not.exist')
    shot('no-settings', '.app-sidebar, aside')
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, failOnStatusCode: false,
      body: { key: 'pos.park.enabled', value: 'false' } }).then((r) => {
      expect(r.body && r.body.success, 'a staff user is refused by the server too').to.not.eq(true)
    })
  })

  it('C4 — a value that does not fit is refused, with a reason', () => {
    step('C4', 'Changing a setting', 'Enter a value the setting cannot take',
      ['In **Selling → Sale entry**, clear the **default quantity** box so it is empty, then click elsewhere.'],
      ['The row says **Save failed** and the message at the top explains why (it must be a whole number).',
       'The value in force is unchanged — an empty box is never saved.'])
    const KEY = 'pos.entry.defaultQty'
    openConfig()
    row(KEY).scrollIntoView()
    cy.intercept('POST', '**/saveBusinessConfig').as('save')
    cy.get(`#businessConfigBody [data-key="${KEY}"]`).clear().blur()
    cy.wait('@save').its('response.body.success').should('eq', false)
    cy.get('#businessConfigMsg').should('be.visible').invoke('text').should('match', /whole number/i)
    shot('reason', '#businessConfigMsg')
    row(KEY).closest('.cfg-group').as('grpRefused')
    shot('refused', '@grpRefused')
  })

  // ── H. Platform operator: plan and entitlements ─────────────────────────────────────────────────────
  it('H1 — the operator sees each tenant’s plan and entitlements', () => {
    step('H1', 'Plans and entitlements (MaxTheService operator)', 'Review a tenant’s plan and capabilities',
      ['Log in as the platform operator.', 'Open **Tenants** and click the business.'],
      ['Two cards: **Plan** and **Capabilities**.',
       'Each capability shows **Entitled**, **Not in plan** or **Revoked**. **Grant** (with a reason) makes a feature available without changing the plan.'])
    cy.loginAsOperator()
    cy.visit('/platformDashboard'); cy.waitForAppReady()
    cy.intercept('GET', '**/platform/organizations*').as('orgs')
    cy.get('#platSearch', { timeout: 20000 }).clear().type('Owner Business')
    cy.wait('@orgs')
    cy.get('[data-testid="tenant-row"][data-org="13"]', { timeout: 20000 }).should('be.visible').click()
    cy.contains(/Capabilities/i, { timeout: 15000 }).should('be.visible')
    cy.contains(/Entitled|Not in plan/).should('exist')
    // The header names the operator; it used to print the user record's toString ("User [id=59, firstName=...").
    cy.get('.plat__who').invoke('text').should('not.match', /User \[id=|isUsing2FA/)
    shot('tenant')
  })

  it('H2 — without a support session the operator sees only the platform’s own actions', () => {
    step('H2', 'Plans and entitlements (MaxTheService operator)', 'Read a business’s Activity without opening a support session',
      ['As the operator, open **Tenants** and click the business (do not open a support session).', 'Scroll to **Activity**.'],
      ['A note says only the platform’s own actions are shown, and that the business’s staff activity needs an open support session.',
       'Every entry is a platform action on THIS business (plan, capability, business type, support sessions) — never another business’s history.'])
    cy.loginAsOperator()
    cy.visit('/platformDashboard'); cy.waitForAppReady()
    cy.intercept('GET', '**/platform/organizations*').as('orgs2')
    cy.get('#platSearch', { timeout: 20000 }).clear().type('Owner Business')
    cy.wait('@orgs2')
    cy.get('[data-testid="tenant-row"][data-org="13"]', { timeout: 20000 }).should('be.visible').click()
    cy.get('[data-testid="activity"]', { timeout: 15000 }).should('be.visible')
    cy.get('[data-testid="activity-platform-only"]').should('be.visible')
    cy.request('/platform/activity?organizationId=13').then((r) => {
      r.body.data.rows.forEach((e) => { expect(e.organizationId).to.eq(13); expect(e.actorType).to.eq('PLATFORM_OPERATOR') })
    })
    shot('activity', '[data-testid="activity"]')
  })

  // ── I. The other modules' settings screens ─────────────────────────────────────────────────────────
  /*
   * One step per module screen: open it, change one setting, see it marked as changed, Reset to default. The
   * setting is snapshotted and put back (by reset when it had no override), so nothing is pinned.
   */
  const moduleStep = (id, title, how, expected, o) => it(`${id} — ${title}`, () => {
    step(id, 'The other settings screens', title, how, expected)
    o.login()
    cy.request(o.get).then((r) => {
      const list = r.body.data || r.body.collection || r.body.object
      const e = list.find((x) => x.key === o.key)
      expect(e, `${o.key} on the screen`).to.exist
      const orig = { isDefault: e.isDefault === true, value: String(e.value) }
      cy.visit(o.url); cy.waitForAppReady()
      cy.window().then((w) => o.open(w))
      cy.get(`${o.body} .cfg-row`, { timeout: 20000 }).should('have.length.greaterThan', 0)
      // The screen as a person sees it (viewport): an element capture of a tall screen was stitched with its top
      // repeated three times (education, on the review run).
      cy.get(o.screen).scrollIntoView({ offset: { top: -90, left: 0 } })
      shot('screen')
      const ctl = `${o.body} [data-key="${o.key}"]`
      cy.get(ctl).then(($c) => {
        if ($c.attr('type') === 'checkbox') cy.wrap($c).click({ force: true })
        else cy.wrap($c).clear().type(String(o.value)).blur()
      })
      cy.get(o.msg).should('be.visible').and('contain', 'Saved')
      // Reopening re-fetches the settings and redraws the screen; wait for that fetch, or the picture below is
      // taken mid-redraw (education came out 1px high twice).
      cy.intercept('GET', `**${o.get}*`).as('reload')
      cy.window().then((w) => o.open(w))
      cy.wait('@reload')
      cy.get(ctl, { timeout: 20000 }).closest('.cfg-row').as('row')
      cy.get('@row').find('.cfg-row__reset').should('be.visible')
      // Re-found and REQUIRED to be on the page with a real height: education re-renders once more after reopening,
      // and a group captured mid-render came out 1px high (review run).
      cy.get(ctl).closest('.cfg-group').should(($g) => {
        expect($g[0].isConnected, 'the group is on the page').to.eq(true)
        expect($g[0].getBoundingClientRect().height, 'the group has rendered').to.be.greaterThan(40)
      }).as('grp')
      // A VIEWPORT picture with the changed row's group in view — an element capture of this group came out 1px high
      // on the education page (three runs), whatever the wait; the viewport capture has no such geometry step.
      // Bring the changed row on screen the way a tester would: search for it by name (the screen's own search box,
      // where the screen has one). Scrolling to the group did not move the education page.
      cy.get('body').then(($b) => {
        const box = $b.find(`${o.body} .cfg-search input`)
        if (box.length) cy.wrap(box).clear().type(e.label.slice(0, 24))
      })
      cy.get(ctl).should('be.visible')
      shot('changed')
      cy.get('body').then(($b) => { const box = $b.find(`${o.body} .cfg-search input`); if (box.length) cy.wrap(box).clear() })
      cy.get('@row').find('.cfg-row__reset').click()
      cy.get(o.msg).should('be.visible')
      cy.request(o.get).then((rr) => {
        const a = (rr.body.data || rr.body.collection || rr.body.object).find((x) => x.key === o.key)
        expect(a.isDefault, 'Reset removed the change').to.eq(true)
      })
      // put back exactly what was there
      cy.then(() => { if (!orig.isDefault) cy.request({ method: 'POST', url: o.save, form: true, body: { key: o.key, value: orig.value } }) })
    })
  })
  moduleStep('I1', 'Order settings (online store)',
    ['Log in as the marketplace owner (the **Store** menu is shown only for a marketplace business) and open **Store → Order settings**.', 'Change **Promise backordered items within (days)** to 5 and click elsewhere.', 'Reopen the screen, then click **Reset to default** on that row.'],
    ['Delivery fees, cash on delivery, backorders, packing and approval rules are listed.', 'The change is saved and marked with a dot and a **Reset to default** link.', 'Reset puts it back to the default (7 days).'],
    { login: () => cy.loginAsMarketplaceOwner(), url: '/businessDashboard', open: (w) => w.showOrderConfig(), screen: '#OrderConfigDiv', body: '#orderConfigBody',
      msg: '#orderConfigMsg', key: 'order.backorder.promiseDays', value: 5, get: '/getOrderConfig', save: '/saveOrderConfig' })
  moduleStep('I2', 'Education → Configuration',
    ['Log in as the school owner and open **Configuration**.', 'Untick **Show attendance on report cards**.', 'Reopen the screen, then click **Reset to default** on that row.'],
    ['Branch policy, fees, grading, report card, promotion, staff attendance, portals and notices are listed.', 'The change is saved and marked as changed.', 'Reset puts it back to ON (the default).'],
    { login: () => cy.loginAsEduOwner(), url: '/educationDashboard', open: (w) => w.showConfig(), screen: '#configBody', body: '#configBody',
      msg: '#configMsg', key: 'edu.reportCard.showAttendance', get: '/getConfig', save: '/saveConfig' })
  moduleStep('I3', 'Welfare → Configuration',
    ['Log in as the welfare owner and open **Configuration**.', 'Tick **Require a donor on every donation**.', 'Reopen the screen, then click **Reset to default**.'],
    ['Two settings: donations and donors.', 'The change is saved and marked as changed.', 'Reset puts it back to OFF (the default).'],
    { login: () => cy.loginAsWelfareOwner(), url: '/welfareDashboard', open: (w) => w.showConfig(), screen: '#welfareConfigBody', body: '#welfareConfigBody',
      msg: '#welfareConfigMsg', key: 'welfare.donation.requireDonor', get: '/getWelfareConfig', save: '/saveWelfareConfig' })
  moduleStep('I4', 'Agriculture → Configuration',
    ['Log in as the farm owner and open **Configuration**.', 'Tick **Require a land/plot on every income & expense**.', 'Reopen the screen, then click **Reset to default**.'],
    ['One setting: whether every entry must name a land or plot.', 'The change is saved and marked as changed.', 'Reset puts it back to OFF (the default).'],
    { login: () => cy.loginAsAgricultureOwner(), url: '/agricultureDashboard', open: (w) => w.showConfig(), screen: '#agriConfigBody', body: '#agriConfigBody',
      msg: '#agriConfigMsg', key: 'agri.entry.requireLand', get: '/getAgricultureConfig', save: '/saveAgricultureConfig' })
})
