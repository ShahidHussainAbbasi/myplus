/**
 * searchable-selects.js — a background fetch must not shut the dropdown you are using.
 *
 * <h3>The bug</h3>
 * Every AJAX response on any page ran `$('.selectpicker').selectpicker('refresh')`. bootstrap-select's
 * refresh() calls reloadLi(), which REBUILDS the option list — so an open menu closes, a half-typed
 * search is wiped, and focus falls out of the destroyed subtree onto <body>. Screens here poll while
 * they are open, so this fired repeatedly with a dropdown open in front of the user: the list shut by
 * itself, mid-selection, for no reason the user could see.
 *
 * <h3>Why this spec is on EDUCATION and not the till</h3>
 * The fix lives in `/js/common/searchable-selects.js`, which every module loads. It was found on the
 * sale screen and gated there, and a shared file proven on one screen is a shared file proven nowhere —
 * the education dashboard carries 92 selects and was the screen whose behaviour prompted searchable
 * pickers in the first place.
 *
 * <h3>The two halves, and why the second one matters</h3>
 * It would be trivial to "fix" this by never refreshing, which would silently reintroduce the problem
 * refresh existed to solve: dropdowns are filled by AJAX after load, and several populate helpers append
 * <option>s without refreshing themselves. So this asserts BOTH:
 *
 *   1. a picker the user is USING survives a background response untouched
 *   2. an IDLE picker is still brought in sync by that same response
 *
 * Assert the property, not the artefact: "the menu is still open AND still holds what I typed", not
 * "the handler ran".
 */
describe('Pickers survive background fetches — shared searchable-selects behaviour', () => {
  beforeEach(() => {
    cy.loginAsEducation()
    cy.viewport(1600, 900)
    cy.visit('/educationDashboard')
  })

  /**
   * The first eligible bootstrap-select on the page, whatever it happens to be.
   *
   * Deliberately not a named id. The property under test belongs to the shared initialiser, not to any
   * one dropdown, and naming one would tie this spec to a screen someone may reorganise.
   */
  const anyPicker = () =>
    cy.get('.bootstrap-select', { timeout: 20000 })
      .filter((i, el) => Cypress.$(el).prev('select').length > 0 && Cypress.$(el).is(':visible'))
      .first()

  /**
   * Pin the picker by the id of its <select>, once, and address it by that id thereafter.
   *
   * A DOM alias in Cypress re-runs its original query on every `cy.get('@alias')`, and this page has 92
   * selects whose visibility changes as panels open. `.first()` is therefore not stable across a
   * background fetch: a first draft appended an option to one picker and then asserted against a
   * different one, and reported a working refresh as broken.
   */
  const pinPicker = (alias) =>
    anyPicker().then(($bs) => {
      const id = Cypress.$($bs).prev('select').attr('id')
      expect(id, 'the picker under test has an id to pin it by').to.be.a('string').and.not.be.empty
      cy.wrap(id).as(alias)
    })

  /** The wrapper for a pinned id — always the same element, however the page changes around it. */
  const wrapperFor = (id) => cy.get(`#${id}`).next('.bootstrap-select')

  /** Fire a real request so jQuery's global ajaxComplete runs, exactly as the page's own polling does. */
  const backgroundFetch = () => {
    cy.window().then((w) => {
      w.__fetchDone = false
      w.jQuery.get('/educationDashboard').always(() => { w.__fetchDone = true })
    })
    cy.window().its('__fetchDone', { timeout: 20000 }).should('eq', true)
  }

  it('THE BUG — an OPEN picker stays open, and keeps what was typed into it', () => {
    anyPicker().as('picker')
    cy.get('@picker').find('button').click()
    cy.get('@picker').should('have.class', 'open')

    // Type into the live-search box: this is the state the old code destroyed most visibly, because
    // reloadLi() rebuilds the menu and the typed filter goes with it.
    cy.get('@picker').find('.bs-searchbox input').type('a')
    cy.get('@picker').find('.bs-searchbox input').should('have.value', 'a')

    backgroundFetch()

    // Still open, still filtered. Before the fix this closed and cleared.
    cy.get('@picker').should('have.class', 'open')
    cy.get('@picker').find('.bs-searchbox input').should('have.value', 'a')
  })

  it('a picker holding FOCUS is left alone even with its menu shut', () => {
    // The guard is "in use", not merely "open". A user tabbing through a form has a picker focused
    // with no menu showing; rebuilding it there throws the cursor out of the form.
    anyPicker().as('picker')
    cy.get('@picker').find('button').focus()
    cy.focused().should('exist')
    cy.get('@picker').find('button').then(($btn) => {
      backgroundFetch()
      cy.focused().should(($f) => {
        expect($f[0], 'the cursor is still on the picker it was on').to.eq($btn[0])
      })
    })
  })

  /**
   * Remember the exact DOM node bootstrap-select is currently rendering the option list into.
   *
   * "Was this picker rebuilt?" is the real question, and node identity answers it directly:
   * reloadLi() replaces the list, so a surviving node means the picker was left alone and a replaced
   * one means it was refreshed.
   *
   * The first draft asked it by appending an <option> and looking for it afterwards. That measured the
   * wrong thing — the dashboard REPOPULATES its org switcher on ajaxComplete, so the injected option
   * was wiped by application code and a perfectly working refresh looked broken. Node identity cannot
   * be confused that way, and it does not care what any screen does with its own data.
   */
  const stampMenu = (id, key) =>
    cy.window().then((w) => {
      const li = w.jQuery(`#${id}`).next('.bootstrap-select').find('.dropdown-menu li')[0]
      expect(li, 'the picker has a rendered option list to stamp').to.exist
      w[key] = li
    })

  /** Is the stamped node still the one on screen? */
  const menuWasRebuilt = (id, key) =>
    cy.window().then((w) => {
      const li = w.jQuery(`#${id}`).next('.bootstrap-select').find('.dropdown-menu li')[0]
      return li !== w[key]
    })

  it('THE OTHER HALF — an IDLE picker IS rebuilt, so options never go stale', () => {
    /*
     * Without this, "never refresh" would pass every test above while quietly reintroducing the problem
     * the refresh exists to solve: dropdowns are filled by AJAX after load, and several populate helpers
     * append <option>s without refreshing themselves.
     */
    pinPicker('id')
    cy.get('@id').then((id) => {
      cy.get('body').click(5, 5)            // not in use: menu shut, focus elsewhere
      stampMenu(id, '__idle')
      backgroundFetch()
      menuWasRebuilt(id, '__idle').should('eq', true)
    })
  })

  it('a picker that WAS in use catches up once the user closes it', () => {
    // The deferred half. Marked stale while open, rebuilt on close — so "leave it alone" never turns
    // into "leave it wrong".
    pinPicker('id')
    cy.get('@id').then((id) => {
      wrapperFor(id).find('button').click()
      wrapperFor(id).should('have.class', 'open')
      stampMenu(id, '__deferred')

      backgroundFetch()
      // Untouched while open — that is the whole point.
      wrapperFor(id).should('have.class', 'open')
      menuWasRebuilt(id, '__deferred').should('eq', false)

      cy.get('body').click(5, 5)
      wrapperFor(id).should('not.have.class', 'open')
      // ...and caught up on close.
      menuWasRebuilt(id, '__deferred').should('eq', true)
    })
  })
})
