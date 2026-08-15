/**
 * P7.2 — keyboard-first registration on the BUSINESS modals.
 *
 * Every <Entity>Modal is bound by convention (keyboard-forms.js), not by a per-form field list. These
 * tests therefore check two different things, and the distinction matters:
 *
 *   - the CONVENTION holds        (every modal is wired, and the walk is the form's own layout order)
 *   - the EXCEPTIONS are declared (Purchase owns its chain; the two payment dialogs have no <form>
 *                                  and no #add<Entity>, so they point at their submit with an attribute)
 *
 * The assertion that matters most is the last one: these forms all worked with a mouse before P7.2,
 * and the failure mode to guard against is breaking that to serve the keyboard.
 *
 * Run headed.
 */

const STAMP = Date.now()

// Every registration modal on this dashboard, with the field its walk should START on. The start
// field is stated explicitly rather than derived, so a change to the form's first field shows up
// here as a decision rather than passing silently.
const MODALS = [
  { entity: 'Company',  section: 'CompanyDiv',  open: '#newCompany',  first: 'companyName' },
  { entity: 'Customer', section: 'CustomerDiv', open: '#newCustomer', first: 'customerName' },
  { entity: 'Vender',   section: 'VenderDiv',   open: '#newVender',   first: 'venderName' },
  // Product is NOT on #registrationType — its screen is opened by showProducts(), the catalog
  // master's own entry point. Same modal convention, different way in.
  { entity: 'Product',  section: null,          open: '#newProduct',  first: 'prodName' },
]

/** The id of the field that currently has focus.
 *
 * bootstrap-select hides the real <select> and focuses the BUTTON it renders, so asking the focused
 * element for its id answers with the button's (usually nothing). Resolve back to the <select> the
 * button stands for. Written once here because three assertions below need it and a fourth got it
 * wrong by restating it.
 */
function focusedFieldId($el) {
  return Cypress.$($el).attr('id')
    || Cypress.$($el).closest('.bootstrap-select').prev('select').attr('id')
}

function openDash() {
  cy.visit('/businessDashboard')
  cy.window({ timeout: 30000 }).its('EnterChain').should('exist')
  cy.window().its('KeyboardForms').should('exist')
}

/** A toolbar "+ New" button lives inside its section's .formDiv, which is display:none until that
 *  section is shown — so the section has to be opened before the button can be clicked. */
function openSectionFor(section) {
  if (section) {
    cy.get('#registrationType').select(section, { force: true })
    cy.get('#' + section).should('be.visible')
  } else {
    cy.window().then((w) => w.showProducts())
    cy.get('#ProductDiv').should('be.visible')
  }
}

/** Click a toolbar "+ New" button, allowing for the section's data load.
 *
 * #appAjaxOverlay covers the toolbar while a section loads, so the click can land on the overlay.
 * Two things that do NOT work: asserting the overlay is absent first (it has not appeared yet at
 * that instant, so the check passes vacuously and the click still hits it), and waiting on a named
 * request (each section loads a different one). Cypress already retries actionability — just give it
 * long enough to outlast the load, and let it click when the button is genuinely clickable.
 */
function clickNew(selector) {
  cy.get(selector, { timeout: 30000 }).click({ timeout: 30000 })
}

describe('P7.2 — business registration modals, keyboard-first', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  // ── the convention ────────────────────────────────────────────────────────

  it('every modal on the dashboard is wired, and Purchase is excluded as declaring its own', () => {
    openDash()
    cy.window().then((w) => {
      const bound = w.KeyboardForms.boundModals()
      ;['CompanyModal', 'CustomerModal', 'VenderModal', 'ProductModal',
        'ReceivePaymentModal', 'PayVendorModal'].forEach((m) => {
        expect(bound, `${m} is bound by convention`).to.include(m)
      })
      // Purchase carries data-kbd-custom — its Save-&-Add-Another end behaviour is its own.
      expect(bound, 'Purchase keeps its own chain').to.not.include('PurchaseModal')
    })
  })

  MODALS.forEach(({ entity, section, open, first }) => {
    it(`${entity}: Enter walks the form in layout order, Shift+Enter reverses`, () => {
      openDash()
      openSectionFor(section)
      clickNew(open)
      cy.get(`#${entity}Modal`).should('have.class', 'open')

      cy.window().then((w) => {
        const chain = w.EnterChain.fieldsIn(`#${entity}Modal`)
        expect(chain.length, `${entity} has fields in its chain`).to.be.greaterThan(1)
        expect(chain[0], `${entity} starts on the first typeable field`).to.eq(first)

        // The derived chain must be a SUBSEQUENCE of document order — that is what "layout order"
        // means, and it is the property a hand-written list kept getting wrong.
        const ids = Array.from(
          w.document.querySelectorAll(`#${entity}Modal input, #${entity}Modal select, #${entity}Modal textarea`)
        ).map((el) => el.id).filter(Boolean)
        let at = -1
        chain.forEach((id) => {
          const pos = ids.indexOf(id)
          expect(pos, `${id} follows ${chain[chain.indexOf(id) - 1] || 'the start'} in the DOM`).to.be.greaterThan(at)
          at = pos
        })
      })

      // Drive it: first field -> second, and back again.
      cy.window().then((w) => {
        const chain = w.EnterChain.fieldsIn(`#${entity}Modal`)
        const second = chain[1]
        cy.get(`#${first}`).focus().type('{enter}')
        cy.focused().should(($el) => {
          expect(focusedFieldId($el), `Enter reached ${second}`).to.eq(second)
        })
        cy.focused().type('{shift}{enter}')
        cy.focused().should(($el) => {
          expect(focusedFieldId($el), `Shift+Enter returned to ${first}`).to.eq(first)
        })
      })
    })

    it(`${entity}: Esc closes without saving`, () => {
      let posted = false
      cy.intercept('POST', `/add${entity}`, (req) => { posted = true; req.continue() })
      openDash()
      openSectionFor(section)
      clickNew(open)
      cy.get(`#${entity}Modal`).should('have.class', 'open')
      cy.get(`#${first}`).focus().type('{esc}')
      cy.get(`#${entity}Modal`).should('not.have.class', 'open')
      cy.then(() => expect(posted, 'Esc must not save').to.be.false)
    })
  })

  // ── submitting ────────────────────────────────────────────────────────────

  it('Ctrl+Enter saves from the middle of a form', () => {
    cy.intercept('POST', '/addCompany').as('save')
    openDash()
    openSectionFor('CompanyDiv')
    clickNew('#newCompany')
    cy.get('#companyName').clear().type(`P72Co_${STAMP}`)
    cy.get('#companyEmail').clear().type(`p72co${STAMP}@test.com`)
    // NOT the last field — that is the point of Ctrl+Enter.
    cy.get('#companyPhone').focus().type('{ctrl}{enter}')
    cy.wait('@save').its('response.statusCode').should('eq', 200)
  })

  it('Enter on the LAST field saves — exactly once', () => {
    let posts = 0
    cy.intercept('POST', '/addCompany', (req) => { posts += 1; req.continue() }).as('save')
    openDash()
    openSectionFor('CompanyDiv')
    clickNew('#newCompany')
    cy.get('#companyName').clear().type(`P72Last_${STAMP}`)
    cy.window().then((w) => {
      const chain = w.EnterChain.fieldsIn('#CompanyModal')
      const last = chain[chain.length - 1]
      cy.get(`#${last}`).focus().type('{enter}')
    })
    cy.wait('@save')
    // A double-submit would create two records from one keystroke — the failure a naive "Enter
    // submits" implementation ships with, because the button ALSO takes the Enter.
    cy.wait(600)
    cy.then(() => expect(posts, 'exactly one POST').to.eq(1))
  })

  it('enterSubmits=off → Enter on the last field does nothing, Ctrl+Enter still saves', () => {
    let posted = false
    cy.intercept('POST', '/addCompany', (req) => { posted = true; req.continue() }).as('save')
    openDash()
    openSectionFor('CompanyDiv')
    cy.window().then((w) => { w.kbdEnterSubmits = false })
    clickNew('#newCompany')
    cy.get('#companyName').clear().type(`P72NoSub_${STAMP}`)
    cy.window().then((w) => {
      const chain = w.EnterChain.fieldsIn('#CompanyModal')
      cy.get(`#${chain[chain.length - 1]}`).focus().type('{enter}')
    })
    cy.wait(600)
    cy.then(() => expect(posted, 'Enter must not save when the tenant turned that off').to.be.false)
    cy.get('#CompanyModal').should('have.class', 'open')
    // The escape hatch survives — a form is never un-submittable from the keyboard.
    //
    // WAIT for the request rather than reading a flag in a .then(): .then() runs as soon as the type
    // command finishes, which is before the click's AJAX has left the browser. Reading the flag there
    // passes or fails on timing, and it did both across runs.
    cy.get('#companyName').focus().type('{ctrl}{enter}')
    cy.wait('@save', { timeout: 20000 }).its('response.statusCode').should('eq', 200)
  })

  it('formNav=off → the chain is inert and the form behaves as it did before P7.2', () => {
    openDash()
    openSectionFor('CompanyDiv')
    cy.window().then((w) => { w.kbdFormNavEnabled = false })
    clickNew('#newCompany')
    cy.get('#companyName').focus().type('{enter}')
    // Focus does not move: Enter is nobody's business on this form now.
    cy.focused().should('have.id', 'companyName')
  })

  // ── the exceptions ────────────────────────────────────────────────────────

  it('a dialog with NO <form> still gets a chain, via data-kbd-submit', () => {
    openDash()
    // #ReceivePaymentModal is nested INSIDE #CustomerDiv, so the section has to be showing before its
    // fields have any size at all. Opening the modal alone yields an empty chain — correctly, since
    // a field inside a display:none ancestor is not something you can put a cursor in.
    openSectionFor('CustomerDiv')
    // Drive the modal directly: the customer-row journey is covered elsewhere and this test is about
    // the CHAIN, not the route in.
    cy.window().then((w) => { w.openModal('ReceivePaymentModal') })
    cy.get('#ReceivePaymentModal').should('have.class', 'open')
    cy.window().then((w) => {
      const chain = w.EnterChain.fieldsIn('#ReceivePaymentModal')
      expect(chain, 'amount is the first stop').to.include('rcvAmount')
      expect(chain, 'reference is in the chain').to.include('rcvReference')
      expect(chain, 'the hidden customer id is not a stop').to.not.include('rcvCustomerId')
    })
    cy.get('#rcvAmount').focus().type('{enter}')
    // rcvMethod is a bootstrap-select at runtime, so focus lands on its button, not the <select>.
    cy.focused().should(($el) => expect(focusedFieldId($el)).to.eq('rcvMethod'))
  })

  it('the OTHER form-less dialog is wired the same way', () => {
    openDash()
    openSectionFor('VenderDiv')            // #PayVendorModal is nested inside #VenderDiv
    cy.window().then((w) => { w.openModal('PayVendorModal') })
    cy.get('#PayVendorModal').should('have.class', 'open')
    cy.window().then((w) => {
      expect(w.EnterChain.fieldsIn('#PayVendorModal')).to.include('pvAmount')
    })
    cy.get('#pvAmount').focus().type('{enter}')
    cy.focused().should(($el) => expect(focusedFieldId($el)).to.eq('pvMethod'))
    // Esc still closes a dialog that has no <form> and no #add<Entity>.
    cy.focused().type('{esc}')
    cy.get('#PayVendorModal').should('not.have.class', 'open')
  })

  // ── dropdowns must be OPERABLE, not just walked past ──────────────────────

  it('Enter OPENS an unanswered dropdown instead of stepping over it', () => {
    // The reported bug: Enter walked past #venderCompanyDD without ever showing its 71 companies,
    // leaving a REQUIRED field empty with no keyboard way to set it. An unanswered picker now opens.
    openDash()
    openSectionFor('VenderDiv')
    clickNew('#newVender')
    cy.get('#VenderModal').should('have.class', 'open')

    cy.get('#venderName').focus().type(`P72Kbd_${STAMP}{enter}`)
    cy.focused().should(($el) => expect(focusedFieldId($el)).to.eq('venderCompanyDD'))

    cy.get('#venderCompanyDD').should('have.value', '')      // unanswered
    cy.focused().type('{enter}')
    cy.get('#venderCompanyDD').next('.bootstrap-select')
      .should('have.class', 'open')                          // the list is on screen
    cy.focused().should(($el) => expect(focusedFieldId($el)).to.eq('venderCompanyDD'))
  })

  it('choosing from an open dropdown sets the value AND moves on', () => {
    openDash()
    openSectionFor('VenderDiv')
    clickNew('#newVender')
    cy.get('#venderCompanyDD').next('.bootstrap-select').find('button').first().focus().type('{enter}')
    cy.get('#venderCompanyDD').next('.bootstrap-select').should('have.class', 'open')
    // Now make a selection the way the PLUGIN reports one, and assert what this code owns: that a
    // recorded choice carries the chain onward. Navigating the open menu with arrow keys is
    // bootstrap-select's own behaviour, not ours, and driving it headlessly tests the plugin.
    cy.get('#venderCompanyDD option').eq(1).then(($o) => {
      cy.get('#venderCompanyDD').then(($s) => {
        $s.val($o.val()).selectpicker('refresh').trigger('changed.bs.select')
      })
    })
    cy.get('#venderCompanyDD').should('not.have.value', '')   // the choice was RECORDED
    cy.focused().should(($el) => expect(focusedFieldId($el)).to.not.eq('venderCompanyDD'))
  })

  it('an ANSWERED dropdown advances on Enter — it does not re-open', () => {
    openDash()
    openSectionFor('VenderDiv')
    clickNew('#newVender')
    // Set a value directly, then Enter must move on rather than showing the list again.
    cy.get('#venderCompanyDD option').eq(1).then(($o) => {
      cy.get('#venderCompanyDD').then(($s) => {
        $s.val($o.val()).selectpicker('refresh')
      })
    })
    cy.get('#venderCompanyDD').next('.bootstrap-select').find('button').first().focus().type('{enter}')
    cy.get('#venderCompanyDD').next('.bootstrap-select').should('not.have.class', 'open')
    cy.focused().should(($el) => expect(focusedFieldId($el)).to.not.eq('venderCompanyDD'))
  })

  // ── the guard that matters most ───────────────────────────────────────────

  it('the mouse path is untouched — a form still saves by clicking its button', () => {
    cy.intercept('POST', '/addCustomer').as('save')
    openDash()
    openSectionFor('CustomerDiv')
    clickNew('#newCustomer')
    cy.get('#customerName').clear().type(`P72Mouse_${STAMP}`)
    cy.get('#contact').clear().type('03001234567')
    cy.get('#addCustomer').click()                    // no keyboard involved at all
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.get('#CustomerModal').should('not.have.class', 'open')
  })
})
