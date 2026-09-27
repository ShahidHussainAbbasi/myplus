/**
 * UI-CFG-1 — Settings → Configuration as a CATEGORY RAIL, with "changed from default" markers and Reset to default.
 *
 * What the defect / feature breaks, asserted directly:
 *   - a category shows ONLY its own settings (one pane at a time) and the rail counts add up to the whole catalogue;
 *   - search spans EVERY category — an owner must not have to guess where "receipt" lives;
 *   - a group the rail does not name lands in "Other" — a setting added on the server can never go missing;
 *   - Reset REMOVES the override (isDefault true afterwards), not saves the default — a saved default pins the
 *     setting against the shop preset (SET-CERT B-020); for a capability it goes through auth-service;
 *   - a staff user cannot reset (server refuses, not just the menu);
 *   - WAI-ARIA tabs keyboard, phone chips (no sideways page scroll), RTL puts the rail on the right.
 *
 * Tenant: owner.business@myplus.com (org 13). Every setting touched is put back — untouched ones by RESET.
 *
 * Run headed:  npx cypress run --headed --browser electron --spec cypress/e2e/business/config-rail.cy.js
 */
const BODY = '#businessConfigBody'
const TEXT_KEY = 'pos.customer.walkInName'
const CAP_KEY = 'org.cap.bonusSchemes'

const findEntry = (body, key) => {
  const find = (o) => { if (!o || typeof o !== 'object') return null; if (o.key === key) return o; for (const v of Object.values(o)) { const f = find(v); if (f) return f } return null }
  return find(body)
}
const entry = (key) => cy.request('/getBusinessConfig').then((r) => {
  const e = findEntry(r.body, key)
  expect(e, `setting ${key} is in the catalogue`).to.exist
  expect(e, `setting ${key} says whether it is overridden`).to.have.property('isDefault')
  return e
})
const setCfg = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value: String(value) } })
    .its('body.success').should('eq', true)
const resetCfg = (key) =>
  cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key } })
    .its('body.success').should('eq', true)

const ORIGINAL = {}
const remember = (key) => entry(key).then((e) => { ORIGINAL[key] = { isDefault: e.isDefault === true, value: String(e.value) } })
const restore = (key) => (ORIGINAL[key].isDefault ? resetCfg(key) : setCfg(key, ORIGINAL[key].value))

const openConfig = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  // The menu's own handler — at phone width the sidebar is collapsed and its links are off screen.
  cy.window().then((w) => w.showBusinessConfig())
  cy.get('#ConfigDiv').should('be.visible')
  cy.get(`${BODY} .cfg-rail__item`, { timeout: 20000 }).should('have.length.greaterThan', 3)
}
const active = () => cy.get(`${BODY} .cfg-rail__item[aria-selected="true"]`)

describe('UI-CFG-1 — Configuration category rail + Reset to default', () => {
  before(() => {
    cy.loginAsOwner()
    remember(TEXT_KEY)
    remember(CAP_KEY)
  })
  beforeEach(() => { cy.viewport(1366, 860); cy.loginAsOwner() })
  after(() => {
    cy.loginAsOwner()
    Object.keys(ORIGINAL).forEach(restore)
  })

  it('shows one category at a time, and the rail counts add up to the whole catalogue', () => {
    openConfig()
    cy.get(`${BODY} .cfg-rail`).should('have.attr', 'role', 'tablist')
    const ids = []
    cy.get(`${BODY} .cfg-rail__item`).each(($c) => { ids.push($c.attr('data-cat')) }).then(() => {
      expect(ids, 'the categories an owner recognises').to.include.members(['business', 'selling', 'documents'])
      expect(new Set(ids).size, 'no category twice').to.eq(ids.length)
    })
    active().should('have.length', 1)

    // Σ rail counts = every row on the page = every setting the server sent — nothing lost, nothing counted twice.
    cy.request('/getBusinessConfig').then((r) => {
      const items = r.body.data || r.body.collection || r.body.object
      cy.get(`${BODY} .cfg-rail__n`).then(($n) => {
        const sum = [...$n].reduce((a, el) => a + parseInt(el.textContent, 10), 0)
        expect(sum, 'rail counts add up to the catalogue').to.eq(items.length)
      })
      cy.get(`${BODY} .cfg-row`).should('have.length', items.length)
    })

    // Every category: its pane shows ONLY its own groups, and its title names it.
    cy.get(`${BODY} .cfg-rail__item`).each(($c) => {
      const cat = $c.attr('data-cat')
      const label = $c.find('.cfg-rail__label').text().trim()
      const n = parseInt($c.find('.cfg-rail__n').text(), 10)
      cy.wrap($c).click()
      cy.wrap($c).should('have.attr', 'aria-selected', 'true')
      cy.get(`${BODY} .cfg-pane__title`).should('have.text', label)
      cy.get(`${BODY} .cfg-group:visible`).should('have.length.greaterThan', 0).each(($g) => {
        expect($g.attr('data-cat'), `a group on the ${cat} pane belongs to ${cat}`).to.eq(cat)
      })
      cy.get(`${BODY} .cfg-row:visible`).should('have.length', n)
    })
  })

  it('search spans every category and shows each category’s matches; clearing it returns to the category', () => {
    openConfig()
    cy.get(`${BODY} .cfg-rail__item[data-cat="business"]`).click()
    cy.get(`${BODY} .cfg-search input`).type('receipt')
    cy.get(BODY).should('have.class', 'cfg-searching')
    // "receipt" lives in Receipts & documents — NOT the open Business category — and is found anyway.
    cy.get(`${BODY} .cfg-group:visible`).then(($g) => {
      const cats = [...$g].map((g) => g.getAttribute('data-cat'))
      expect(cats, 'matches from a category that is not open').to.include('documents')
    })
    cy.get(`${BODY} .cfg-rail__item[data-cat="documents"] .cfg-rail__hits`).invoke('text').then((t) => {
      expect(parseInt(t, 10)).to.be.greaterThan(0)
    })
    cy.get(`${BODY} .cfg-rail__item.is-empty`).should('have.length.greaterThan', 0)
    cy.get(`${BODY} .cfg-search input`).clear()
    cy.get(BODY).should('not.have.class', 'cfg-searching')
    active().should('have.attr', 'data-cat', 'business')
    cy.get(`${BODY} .cfg-group:visible`).each(($g) => expect($g.attr('data-cat')).to.eq('business'))
    cy.get(`${BODY} .cfg-rail__hits`).each(($h) => expect($h.text()).to.eq(''))
  })

  it('a group no category names lands in “Other” — nothing goes missing', () => {
    cy.intercept('GET', '**/getBusinessConfig*', (req) => {
      req.continue((res) => {
        const list = res.body.data || res.body.collection || res.body.object
        list.push({ key: 'zz.test.unmapped', label: 'Unmapped test setting', help: 'Only in this test', type: 'BOOLEAN',
          value: 'false', defaultValue: 'false', isDefault: true, group: 'A group added on the server later' })
      })
    })
    openConfig()
    cy.get(`${BODY} .cfg-rail__item[data-cat="other"]`).should('exist').click()
    cy.get(`${BODY} [data-key="zz.test.unmapped"]`).should('be.visible')
  })

  it('arrow keys, Home and End move between categories (WAI-ARIA tabs)', () => {
    openConfig()
    cy.get(`${BODY} .cfg-rail__item`).then(($items) => {
      const ids = [...$items].map((el) => el.getAttribute('data-cat'))
      cy.wrap($items[0]).click().focus()
      cy.focused().trigger('keydown', { key: 'ArrowDown' })
      active().should('have.attr', 'data-cat', ids[1])
      cy.focused().should('have.attr', 'data-cat', ids[1])
      cy.focused().trigger('keydown', { key: 'End' })
      active().should('have.attr', 'data-cat', ids[ids.length - 1])
      cy.focused().trigger('keydown', { key: 'ArrowDown' })
      active().should('have.attr', 'data-cat', ids[0])       // wraps
      cy.focused().trigger('keydown', { key: 'ArrowUp' })
      active().should('have.attr', 'data-cat', ids[ids.length - 1])
      cy.focused().trigger('keydown', { key: 'Home' })
      active().should('have.attr', 'data-cat', ids[0])
      // Only the active tab is in the Tab order.
      cy.get(`${BODY} .cfg-rail__item[tabindex="0"]`).should('have.length', 1).and('have.attr', 'data-cat', ids[0])
    })
  })

  it('a changed setting is marked on its row and its category; Reset REMOVES the override', () => {
    resetCfg(TEXT_KEY)
    openConfig()
    cy.revealSetting(TEXT_KEY)
    cy.get(`${BODY} [data-key="${TEXT_KEY}"]`).closest('.cfg-row').find('.cfg-row__reset').should('not.exist')

    setCfg(TEXT_KEY, 'Counter guest')
    openConfig()
    cy.revealSetting(TEXT_KEY)
    cy.get(`${BODY} [data-key="${TEXT_KEY}"]`).should('have.value', 'Counter guest')
      .closest('.cfg-row').as('row').should('have.class', 'cfg-row--changed')
    cy.get('@row').find('.cfg-row__changed').should('have.attr', 'title').and('contain', 'Walk-in Customer')
    active().should('have.attr', 'data-cat', 'selling').find('.cfg-rail__changed').should('exist')

    cy.intercept('POST', '**/resetBusinessConfig').as('reset')
    cy.get('@row').find('.cfg-row__reset').should('be.visible').click()
    cy.wait('@reset').its('response.body.success').should('eq', true)
    cy.get('#businessConfigMsg').should('be.visible')
    // The screen re-renders on the SAME category, now on the default.
    active().should('have.attr', 'data-cat', 'selling')
    cy.get(`${BODY} [data-key="${TEXT_KEY}"]`).should('have.value', 'Walk-in Customer')
      .closest('.cfg-row').should('not.have.class', 'cfg-row--changed').find('.cfg-row__reset').should('not.exist')
    entry(TEXT_KEY).its('isDefault').should('eq', true)
  })

  it('Reset of a CAPABILITY goes through auth-service and removes the override too', () => {
    setCfg(CAP_KEY, 'false')
    entry(CAP_KEY).its('isDefault').should('eq', false)
    resetCfg(CAP_KEY)
    entry(CAP_KEY).its('isDefault').should('eq', true)
  })

  it('resetting a setting that was never changed is harmless; an unknown key is refused', () => {
    resetCfg(TEXT_KEY)
    resetCfg(TEXT_KEY)
    entry(TEXT_KEY).its('isDefault').should('eq', true)
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, failOnStatusCode: false, body: { key: 'no.such.key' } })
      .its('body.success').should('not.eq', true)
  })

  it('a staff user cannot reset a setting — the server refuses', () => {
    // First prove the endpoint exists and works for the OWNER — otherwise a 404 would pass as a "refusal".
    setCfg(TEXT_KEY, 'Counter guest')
    resetCfg(TEXT_KEY)
    setCfg(TEXT_KEY, 'Counter guest')
    cy.loginAsTier('user', 'business')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, failOnStatusCode: false, body: { key: TEXT_KEY } })
      .then((r) => expect(r.body && r.body.success, 'refused for a staff user').to.not.eq(true))
    cy.loginAsOwner()
    entry(TEXT_KEY).its('isDefault').should('eq', false)     // still overridden — the refusal changed nothing
  })

  it('on a phone the rail becomes a row of chips and the page never scrolls sideways', () => {
    cy.viewport(390, 844)
    openConfig()
    cy.get(`${BODY} .cfg-rail__item`).then(($i) => {
      const a = $i[0].getBoundingClientRect(), b = $i[1].getBoundingClientRect()
      expect(Math.abs(a.top - b.top), 'chips sit side by side').to.be.lessThan(4)
    })
    cy.get(`${BODY} .cfg-rail`).then(($r) => {
      const pane = Cypress.$(`${BODY} .cfg-pane`)[0].getBoundingClientRect()
      expect($r[0].getBoundingClientRect().bottom, 'chips above the settings').to.be.at.most(pane.top + 1)
    })
    cy.document().then((d) => expect(d.documentElement.scrollWidth, 'no sideways page scroll').to.be.at.most(d.documentElement.clientWidth))
  })

  it('right-to-left languages put the rail on the right', () => {
    openConfig()
    cy.document().then((d) => d.documentElement.setAttribute('dir', 'rtl'))
    cy.get(`${BODY} .cfg-rail`).then(($r) => {
      const rail = $r[0].getBoundingClientRect()
      const pane = Cypress.$(`${BODY} .cfg-pane`)[0].getBoundingClientRect()
      expect(rail.left, 'rail right of the pane').to.be.greaterThan(pane.left)
    })
    cy.document().then((d) => d.documentElement.removeAttribute('dir'))
  })
})
