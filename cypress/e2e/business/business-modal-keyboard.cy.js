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
  { entity: 'Company',  open: '#newCompany',  first: 'companyName' },
  { entity: 'Customer', open: '#newCustomer', first: 'customerName' },
  { entity: 'Vender',   open: '#newVender',   first: 'venderName' },
  { entity: 'Product',  open: '#newProduct',  first: 'prodName' },
]

function openDash() {
  cy.visit('/businessDashboard')
  cy.window({ timeout: 30000 }).its('EnterChain').should('exist')
  cy.window().its('KeyboardForms').should('exist')
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

  MODALS.forEach(({ entity, open, first }) => {
    it(`${entity}: Enter walks the form in layout order, Shift+Enter reverses`, () => {
      openDash()
      cy.get(open).click()
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
          const id = Cypress.$($el).attr('id')
            || Cypress.$($el).closest('.bootstrap-select').prev('select').attr('id')
          expect(id, `Enter reached ${second}`).to.eq(second)
        })
        cy.focused().type('{shift}{enter}')
        cy.focused().should(($el) => {
          const id = Cypress.$($el).attr('id')
            || Cypress.$($el).closest('.bootstrap-select').prev('select').attr('id')
          expect(id, `Shift+Enter returned to ${first}`).to.eq(first)
        })
      })
    })

    it(`${entity}: Esc closes without saving`, () => {
      let posted = false
      cy.intercept('POST', `/add${entity}`, (req) => { posted = true; req.continue() })
      openDash()
      cy.get(open).click()
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
    cy.get('#newCompany').click()
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
    cy.get('#newCompany').click()
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
    cy.intercept('POST', '/addCompany', (req) => { posted = true; req.continue() })
    openDash()
    cy.window().then((w) => { w.kbdEnterSubmits = false })
    cy.get('#newCompany').click()
    cy.get('#companyName').clear().type(`P72NoSub_${STAMP}`)
    cy.window().then((w) => {
      const chain = w.EnterChain.fieldsIn('#CompanyModal')
      cy.get(`#${chain[chain.length - 1]}`).focus().type('{enter}')
    })
    cy.wait(600)
    cy.then(() => expect(posted, 'Enter must not save when the tenant turned that off').to.be.false)
    cy.get('#CompanyModal').should('have.class', 'open')
    // The escape hatch survives — a form is never un-submittable from the keyboard.
    cy.get('#companyName').focus().type('{ctrl}{enter}')
    cy.then(() => expect(posted, 'Ctrl+Enter still saves').to.be.true)
  })

  it('formNav=off → the chain is inert and the form behaves as it did before P7.2', () => {
    openDash()
    cy.window().then((w) => { w.kbdFormNavEnabled = false })
    cy.get('#newCompany').click()
    cy.get('#companyName').focus().type('{enter}')
    // Focus does not move: Enter is nobody's business on this form now.
    cy.focused().should('have.id', 'companyName')
  })

  // ── the exceptions ────────────────────────────────────────────────────────

  it('a dialog with NO <form> still gets a chain, via data-kbd-submit', () => {
    openDash()
    // Receive Payment is opened from a customer row; drive the modal directly, since the row journey
    // is covered elsewhere and this test is about the CHAIN, not the journey.
    cy.window().then((w) => {
      w.openModal('ReceivePaymentModal')
      const chain = w.EnterChain.fieldsIn('#ReceivePaymentModal')
      expect(chain, 'amount is the first stop').to.include('rcvAmount')
      expect(chain, 'reference is in the chain').to.include('rcvReference')
      expect(chain, 'the hidden customer id is not a stop').to.not.include('rcvCustomerId')
    })
    cy.get('#rcvAmount').focus().type('{enter}')
    cy.focused().should('have.id', 'rcvMethod')
  })

  // ── the guard that matters most ───────────────────────────────────────────

  it('the mouse path is untouched — a form still saves by clicking its button', () => {
    cy.intercept('POST', '/addCustomer').as('save')
    openDash()
    cy.get('#newCustomer').click()
    cy.get('#customerName').clear().type(`P72Mouse_${STAMP}`)
    cy.get('#contact').clear().type('03001234567')
    cy.get('#addCustomer').click()                    // no keyboard involved at all
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.get('#CustomerModal').should('not.have.class', 'open')
  })
})
