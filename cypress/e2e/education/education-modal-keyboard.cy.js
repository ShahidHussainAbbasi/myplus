/**
 * P7.3 — keyboard-first registration on the EDUCATION modals.
 *
 * All nine already matched P7.2's convention (`#<Entity>Modal` + `#add<Entity>` + a `<form>`), so the
 * whole template change was two <script> tags and not one modal needed an attribute. What education
 * DOES add is variety business never had — multi-selects, four date pickers on one form, twenty-seven
 * stops on another — and two of those broke the engine the moment the chain was switched on. Those two
 * rules, and the touch/narrow rule the design has claimed since §3 but nothing ever implemented, are
 * asserted here alongside the per-modal walk.
 *
 * Two habits this file keeps deliberately:
 *
 *  - ids are ALWAYS scoped to the modal. The register DataTables render `<div id=ownerName>` per row,
 *    so a bare `#ownerName` is ambiguous the moment a grid has data — it happens to resolve to the form
 *    field only because the modal precedes the table in the DOM. That is luck, not a contract.
 *  - the walk is driven from the first NON-dropdown stop. Enter on an unanswered picker OPENS it rather
 *    than advancing (that is the rule, see §9.3), and four of these forms START on an ajax-filled
 *    dropdown whose answered-ness depends on the org's data. Launching from a text field asserts the
 *    same property without making the test's outcome depend on a fixture it did not seed.
 *
 * Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/education/education-modal-keyboard.cy.js
 */

const STAMP = Date.now()
const TAG = `P73_${STAMP}`

// Every .crud-overlay[id$="Modal"] on educationDashboard.html, in DOM order, with the field its walk
// must START on. The start field is stated rather than derived: if a form's first field changes, that
// should surface here as a decision to confirm, not slide through silently.
const MODALS = [
  { entity: 'Owner',    section: 'OwnerDiv',    open: '#newOwner',    first: 'ownerName' },
  { entity: 'School',   section: 'SchoolDiv',   open: '#newSchool',   first: 'schoolOwnerDD' },
  { entity: 'Grade',    section: 'GradeDiv',    open: '#newGrade',    first: 'gradeSchoolDD' },
  { entity: 'Staff',    section: 'StaffDiv',    open: '#newStaff',    first: 'staffName' },
  { entity: 'Guardian', section: 'GuardianDiv', open: '#newGuardian', first: 'guardianName' },
  { entity: 'Student',  section: 'StudentDiv',  open: '#newStudent',  first: 'studentYS' },
  { entity: 'Subject',  section: 'SubjectDiv',  open: '#newSubject',  first: 'subjectGradeDD' },
  { entity: 'Vehicle',  section: 'VehicleDiv',  open: '#newVehicle',  first: 'vehicleSchoolDD' },
  { entity: 'Discount', section: 'DiscountDiv', open: '#newDiscount', first: 'discountNameDD' },
]

/** The id of the field that currently has focus.
 *
 * bootstrap-select hides the real <select> and focuses the BUTTON it renders (or, once the menu is up,
 * its live-search box), so asking the focused element for its id answers with nothing. Resolve back to
 * the <select> those stand for. */
function focusedFieldId($el) {
  return Cypress.$($el).attr('id')
    || Cypress.$($el).closest('.bootstrap-select').prev('select').attr('id')
}

/** Normalise the monolith's proxied education responses — some routes forward a raw JSON string. */
function asBody(res) {
  return typeof res.body === 'string' ? JSON.parse(res.body) : res.body
}

function rows(res) {
  return (res.body && (res.body.collection || res.body.data)) || []
}

function openDash() {
  cy.intercept('GET', '**/getConfig').as('cfg')
  cy.visit('/educationDashboard')
  cy.window({ timeout: 30000 }).its('EnterChain').should('exist')
  cy.window().its('KeyboardForms').should('exist')

  // The settings read is a ONE-SHOT that races anything a test sets afterwards: cy.wait resolves at the
  // RESPONSE, which is before jQuery's success callback has assigned the globals. Waiting and then
  // reading would read `undefined` and, worse, a later test's `w.kbdEnterSubmits = false` would be
  // overwritten by the callback landing a moment afterwards.
  //
  // So assert the APPLIED value instead — a retrying assertion is the only thing that proves the
  // callback ran, and it doubles as the check that both policies default ON.
  cy.wait('@cfg')
  cy.window().its('kbdFormNavEnabled').should('eq', true)
  cy.window().its('kbdEnterSubmits').should('eq', true)
}

/** A section's "+ New" button lives inside its .formDiv, which is display:none until the section is
 *  shown — so the section must be opened before the button can be clicked. */
function openSection(section) {
  cy.get('#registrationType').select(section, { force: true })
  cy.get('#' + section).should('be.visible')
}

/** Click a toolbar "+ New", allowing for the section's data load.
 *
 * #appAjaxOverlay covers the toolbar while a section loads, so the click can land on the overlay.
 * Asserting the overlay is absent first does NOT help — at that instant it has not appeared yet, so the
 * check passes vacuously and the click still hits it. Cypress already retries actionability; just give
 * it long enough to outlast the load. */
function clickNew(selector) {
  cy.get(selector, { timeout: 30000 }).click({ timeout: 30000 })
}

/**
 * The chain positions that are a plain text box — not a dropdown, and not a date/time field.
 *
 * Dropdowns are excluded because Enter on an UNANSWERED picker opens it instead of advancing, and four
 * of these forms start on an ajax-filled one whose answered-ness depends on the org's data. Date fields
 * are excluded because focusing one opens its calendar, and an open calendar legitimately owns Escape —
 * so pressing Esc there would close the calendar, not the form, and the Esc case would be asserting the
 * wrong thing. Both exclusions keep the test's outcome about the CHAIN rather than about a fixture or a
 * plugin.
 */
function plainStopIndex(w, entity, chain) {
  return chain.findIndex((id) => {
    const el = w.document.querySelector(`#${entity}Modal #${id}`)
    return el && el.tagName !== 'SELECT'
      && !el.classList.contains('datePicker')
      && !el.classList.contains('timepicker')
  })
}

/** Guardian is the workhorse for the submit cases: three required fields, all plain text, and a
 *  single-select Status that defaults to Active — so a filled form is genuinely submittable and the
 *  last stop is deterministic. */
function fillGuardian(kind) {
  cy.get('#GuardianModal #guardianName').clear().type(`${TAG}_${kind}`)
  cy.get('#GuardianModal #guardianMobile').clear().type('03001234567')
  cy.get('#GuardianModal #guardianPermAddress').clear().type('P7.3 gate')
}

describe('P7.3 — education registration modals, keyboard-first', () => {
  beforeEach(() => { cy.loginAsEducation() })

  // ── the convention ────────────────────────────────────────────────────────

  it('all nine education modals are wired by convention — none carries a field list', () => {
    openDash()
    cy.window().then((w) => {
      const bound = w.KeyboardForms.boundModals()
      MODALS.forEach(({ entity }) => {
        expect(bound, `${entity}Modal is bound`).to.include(`${entity}Modal`)
      })
      // The COUNT is the property. Including the nine would still pass if a tenth modal appeared and
      // quietly opted itself out with data-kbd-custom; this makes that a decision someone has to make.
      expect(bound.length, 'exactly the nine modals on this dashboard, no more').to.eq(9)
    })
  })

  MODALS.forEach(({ entity, section, open, first }) => {
    it(`${entity}: the chain is the form's own layout order, and Enter walks it`, () => {
      openDash()
      openSection(section)
      clickNew(open)
      cy.get(`#${entity}Modal`).should('have.class', 'open')

      cy.window().then((w) => {
        const chain = w.EnterChain.fieldsIn(`#${entity}Modal`)
        expect(chain.length, `${entity} has a chain with somewhere to go`).to.be.greaterThan(1)
        expect(chain[0], `${entity} starts on its first typeable field`).to.eq(first)

        // The derived chain must be a SUBSEQUENCE of document order. That is what "layout order" means,
        // and it is the property every hand-written list in this codebase eventually got wrong.
        const ids = Array.from(w.document.querySelectorAll(
          `#${entity}Modal input, #${entity}Modal select, #${entity}Modal textarea`))
          .map((el) => el.id).filter(Boolean)
        let at = -1
        chain.forEach((id) => {
          const pos = ids.indexOf(id)
          expect(pos, `${id} keeps its place in the DOM`).to.be.greaterThan(at)
          at = pos
        })

        // Every modal carries its id (and most a datedStr) in a display:none wrapper. They are NOT
        // disabled and NOT removed — either would drop them from FormData and break every edit — so
        // they are exactly the "present, submitting, but not worth a cursor" case.
        // `el.id` filters out bootstrap-select's own live-search boxes, which are also invisible
        // text inputs inside the modal but are the plugin's, not the form's.
        const invisible = Array.from(w.document.querySelectorAll(`#${entity}Modal input[type="text"]`))
          .filter((el) => el.id && el.offsetWidth === 0 && el.offsetHeight === 0)
        expect(invisible.length,
          `${entity} has at least one invisible-but-submitting field to skip`).to.be.greaterThan(0)
        invisible.forEach((el) => {
          expect(el.disabled, `${el.id} must stay enabled or FormData drops it`).to.be.false
          expect(chain, `${el.id} is invisible, so it is not a stop`).to.not.include(el.id)
        })
      })

      // Drive it, launching from a plain text box — see plainStopIndex for why that is not a dodge.
      cy.window().then((w) => {
        const chain = w.EnterChain.fieldsIn(`#${entity}Modal`)
        const i = plainStopIndex(w, entity, chain)
        expect(i, `${entity} has a typeable stop to launch the walk from`).to.be.greaterThan(-1)
        expect(i, `${entity} has a stop after ${chain[i]}`).to.be.lessThan(chain.length - 1)

        const from = chain[i]
        const to = chain[i + 1]
        cy.get(`#${entity}Modal #${from}`).focus().type('{enter}')
        cy.focused().should(($el) => {
          expect(focusedFieldId($el), `Enter moved ${from} -> ${to}`).to.eq(to)
        })
        cy.focused().type('{shift}{enter}')
        cy.focused().should(($el) => {
          expect(focusedFieldId($el), `Shift+Enter returned ${to} -> ${from}`).to.eq(from)
        })
      })
    })

    it(`${entity}: Esc closes the form without saving`, () => {
      let posted = false
      cy.intercept('POST', `/add${entity}`, (req) => { posted = true; req.continue() })
      openDash()
      openSection(section)
      clickNew(open)
      cy.get(`#${entity}Modal`).should('have.class', 'open')

      cy.window().then((w) => {
        const chain = w.EnterChain.fieldsIn(`#${entity}Modal`)
        const i = plainStopIndex(w, entity, chain)
        expect(i, `${entity} has a typeable stop to press Esc from`).to.be.greaterThan(-1)
        cy.get(`#${entity}Modal #${chain[i]}`).focus().type('{esc}')
      })

      cy.get(`#${entity}Modal`).should('not.have.class', 'open')
      cy.then(() => expect(posted, 'Esc must never save').to.be.false)
    })
  })

  // ── hidden fields, both directions ────────────────────────────────────────

  it('an invisible field is not a stop — and unhiding it makes it one', () => {
    openDash()
    openSection('OwnerDiv')
    clickNew('#newOwner')
    cy.get('#OwnerModal').should('have.class', 'open')

    cy.window().then((w) => {
      const el = w.document.querySelector('#OwnerModal #ownerId')
      // POSITIVE CONTROL. Without these four lines, "ownerId is not in the chain" would pass just as
      // happily if the input had been deleted, disabled, or made readonly — three changes that each
      // silently break saving an edit. The assertion below is only about VISIBILITY if this holds.
      expect(el, '#ownerId is present in the modal').to.not.be.null
      expect(el.tagName, 'and it is a real <input>').to.eq('INPUT')
      expect(el.disabled, 'and enabled — FormData excludes disabled controls').to.be.false
      expect(el.readOnly, 'and not readonly').to.be.false

      const wrap = el.closest('.form-group')
      expect(wrap.style.display, 'its wrapper is hidden with an inline style').to.eq('none')
      expect(w.EnterChain.fieldsIn('#OwnerModal'), 'invisible ⇒ not a stop').to.not.include('ownerId')

      // Unhide it: the chain is recomputed from the live DOM on every keystroke, so a field a tenant's
      // configuration reveals becomes a stop with no code change anywhere. Remove the inline rule
      // rather than adding one — the wrapper is hidden inline, so a class could not beat it.
      wrap.style.removeProperty('display')
      const shown = w.EnterChain.fieldsIn('#OwnerModal')
      expect(shown, 'visible ⇒ a stop').to.include('ownerId')
      expect(shown[0], 'and it takes its DOM position, first').to.eq('ownerId')

      wrap.style.display = 'none'
      expect(w.EnterChain.fieldsIn('#OwnerModal'), 'hidden again ⇒ gone again').to.not.include('ownerId')
    })
  })

  it('no education modal contains a <textarea> — the rule that would need its own case', () => {
    // §7 point 1 (Enter inserts a newline in a textarea and does not advance) is NOT APPLICABLE to
    // P7.3: none of the nine modals has one. Rather than leave that as a silent gap, pin it — the day
    // somebody adds a description box to Guardian or Student, this fails and names the case to write.
    openDash()
    cy.window().then((w) => {
      MODALS.forEach(({ entity }) => {
        expect(w.document.querySelectorAll(`#${entity}Modal textarea`).length,
          `${entity}Modal has no textarea — if this fails, add the "Enter is a newline" case`).to.eq(0)
      })
      // Positive control: the selector does find textareas elsewhere on this page (#pam, #alertMessage,
      // #ntBody), so the zeros above are a fact about the modals and not a query that matches nothing.
      expect(w.document.querySelectorAll('textarea').length,
        'the page really does contain textareas, outside the modals').to.be.greaterThan(0)
    })
  })

  // ── submitting ────────────────────────────────────────────────────────────

  it('Ctrl+Enter saves from the middle of a form', () => {
    cy.intercept('POST', '/addGuardian').as('save')
    openDash()
    openSection('GuardianDiv')
    clickNew('#newGuardian')
    fillGuardian('Ctrl')
    // NOT the last field — that is the whole point of Ctrl+Enter.
    cy.get('#GuardianModal #guardianEmail').focus().type('{ctrl}{enter}')
    cy.wait('@save').its('response.statusCode').should('eq', 200)
  })

  it('Enter on the LAST field saves — exactly once', () => {
    let posts = 0
    cy.intercept('POST', '/addGuardian', (req) => { posts += 1; req.continue() }).as('save')
    openDash()
    openSection('GuardianDiv')
    clickNew('#newGuardian')
    fillGuardian('Last')

    cy.window().then((w) => {
      const chain = w.EnterChain.fieldsIn('#GuardianModal')
      // Named, not derived: the last stop being the Status picker is what makes this test a test of
      // "an ANSWERED dropdown advances, and past the end that means submit".
      expect(chain[chain.length - 1], 'the last stop is the Status picker').to.eq('guardianStatus')
    })
    cy.get('#GuardianModal #guardianStatus').should('have.value', 'Active')   // answered by default
    cy.get('#GuardianModal #guardianStatus').next('.bootstrap-select')
      .find('button').first().focus().type('{enter}')

    cy.wait('@save')
    // A double-submit creates two records from one keystroke — the failure a naive "Enter submits"
    // ships with, because the button takes the Enter as well.
    cy.wait(600)
    cy.then(() => expect(posts, 'exactly one POST').to.eq(1))
  })

  it('enterSubmits=off → Enter on the last field does nothing; Ctrl+Enter still saves', () => {
    let posted = false
    cy.intercept('POST', '/addGuardian', (req) => { posted = true; req.continue() }).as('save')
    openDash()                                     // ...which has already proved the config read landed
    cy.window().then((w) => { w.kbdEnterSubmits = false })
    openSection('GuardianDiv')
    clickNew('#newGuardian')
    fillGuardian('NoSub')

    cy.get('#GuardianModal #guardianStatus').next('.bootstrap-select')
      .find('button').first().focus().type('{enter}')
    cy.wait(600)
    cy.then(() => expect(posted, 'Enter must not save when the tenant turned that off').to.be.false)
    cy.get('#GuardianModal').should('have.class', 'open')

    // The escape hatch survives: a form is never un-submittable from the keyboard. WAIT for the request
    // rather than reading the flag in a .then() — .then() runs as soon as type() finishes, which is
    // before the click's AJAX has left the browser.
    cy.get('#GuardianModal #guardianName').focus().type('{ctrl}{enter}')
    cy.wait('@save', { timeout: 20000 }).its('response.statusCode').should('eq', 200)
  })

  it('formNav=off → the chain is inert and the form behaves as it did before P7.3', () => {
    openDash()
    cy.window().then((w) => { w.kbdFormNavEnabled = false })
    openSection('GuardianDiv')
    clickNew('#newGuardian')

    cy.get('#GuardianModal #guardianName').focus().type('{enter}')
    cy.focused().should('have.id', 'guardianName')       // Enter is nobody's business on this form now
    // Esc belongs to the same chain, so it is off too. Nothing else in the app binds Escape, so the
    // modal closes only by its own controls — which is exactly "as it was before P7.3".
    cy.get('#GuardianModal #guardianName').focus().type('{esc}')
    cy.get('#GuardianModal').should('have.class', 'open')
  })

  // ── education's own variety: the two engine fixes P7.3 paid for ───────────

  it('a MULTI-select does not carry the chain away after the first choice', () => {
    // SEED, never assert-or-skip: staffGradeDD is filled from /getUserGrades, and the property under
    // test needs something to choose. Assert the fixture response — a helper that fails quietly is how
    // a test ends up proving nothing.
    cy.request({ method: 'POST', url: '/addGrade', form: true, body: { name: `${TAG}_Class`, code: 'P73' } })
      .then((res) => {
        expect(asBody(res).status, 'class fixture created').to.be.oneOf(['SUCCESS', 'FOUND'])
      })

    openDash()
    openSection('StaffDiv')
    clickNew('#newStaff')
    cy.get('#StaffModal').should('have.class', 'open')

    // Preconditions: it really is a multi-select, and it really has something to pick.
    cy.get('#StaffModal #staffGradeDD', { timeout: 20000 }).should('have.attr', 'multiple')
    cy.get('#StaffModal #staffGradeDD option').should('have.length.greaterThan', 0)

    cy.get('#StaffModal #staffGradeDD').next('.bootstrap-select').find('button').first().focus()
    cy.focused().should(($el) => expect(focusedFieldId($el)).to.eq('staffGradeDD'))

    // Report a choice the way the plugin reports one. bootstrap-select fires this per TOGGLE and leaves
    // the menu open, because that is what choosing several things looks like.
    cy.get('#StaffModal #staffGradeDD').then(($s) => {
      $s.val([$s.find('option').eq(0).val()]).trigger('changed.bs.select')
    })
    // The advance would be scheduled on a 0ms timeout, so give it room to happen if it were going to.
    cy.wait(300)
    cy.focused().should(($el) => {
      expect(focusedFieldId($el), 'still choosing — the cursor must not have moved on').to.eq('staffGradeDD')
    })

    // POSITIVE CONTROL, same form, same event: a SINGLE select DOES advance. Without this the
    // assertion above would pass just as well if changed.bs.select had stopped advancing entirely.
    cy.get('#StaffModal #staffGender').next('.bootstrap-select').find('button').first().focus()
    cy.get('#StaffModal #staffGender').then(($s) => {
      $s.val('Male').trigger('changed.bs.select')
    })
    cy.focused().should(($el) => {
      expect(focusedFieldId($el), 'a single select is answered by one choice, so it advances').to.eq('staffDOB')
    })
  })

  it('Esc closes an open dropdown, not the record behind it', () => {
    openDash()
    openSection('GuardianDiv')
    clickNew('#newGuardian')
    cy.get('#GuardianModal').should('have.class', 'open')
    cy.get('#GuardianModal #guardianName').clear().type(`${TAG}_Esc`)

    // Open a picker the way a mouse user does.
    cy.get('#GuardianModal #relationDD').next('.bootstrap-select').find('button').first().click()
    cy.get('#GuardianModal #relationDD').next('.bootstrap-select')
      .should('have.class', 'open')                       // precondition: the menu really is up

    cy.focused().type('{esc}')

    cy.get('#GuardianModal #relationDD').next('.bootstrap-select')
      .should('not.have.class', 'open')                   // the innermost thing closed…
    cy.get('#GuardianModal').should('have.class', 'open')  // …and the record behind it survived
    cy.get('#GuardianModal #guardianName').should('have.value', `${TAG}_Esc`)

    // POSITIVE CONTROL: with nothing smaller listening, Esc still closes the form. An assertion that
    // passes under both branches would prove neither.
    cy.get('#GuardianModal #guardianName').focus().type('{esc}')
    cy.get('#GuardianModal').should('not.have.class', 'open')
  })

  // ── the dropdown must be OPERABLE, not just walked past ───────────────────

  it('Enter OPENS an unanswered dropdown, and Shift+Enter can still reverse out of one', () => {
    openDash()
    openSection('StudentDiv')
    clickNew('#newStudent')
    cy.get('#StudentModal').should('have.class', 'open')

    // studentReligion's first option carries value="" and nothing fills it, so it is DETERMINISTICALLY
    // unanswered — unlike the ajax-filled campus/class pickers, whose state depends on the org's data.
    cy.get('#StudentModal #studentReligion').should('have.value', '')
    cy.window().then((w) => {
      const chain = w.EnterChain.fieldsIn('#StudentModal')
      const at = chain.indexOf('studentReligion')
      expect(at, 'religion is a stop').to.be.greaterThan(0)
      expect(chain[at - 1], 'and gender is the stop before it').to.eq('studentGender')
    })

    cy.get('#StudentModal #studentReligion').next('.bootstrap-select')
      .find('button').first().focus().type('{enter}')
    cy.get('#StudentModal #studentReligion').next('.bootstrap-select')
      .should('have.class', 'open')                        // the list is on screen, not stepped over
    cy.focused().should(($el) => expect(focusedFieldId($el)).to.eq('studentReligion'))

    // Shift+Enter is exempt from the open rule: "go back" must be able to reverse OUT of an empty
    // picker, or the chain has a one-way door in it.
    cy.focused().type('{shift}{enter}')
    cy.focused().should(($el) => {
      expect(focusedFieldId($el), 'Shift+Enter reversed out of the empty picker').to.eq('studentGender')
    })
  })

  // ── touch / narrow screens ────────────────────────────────────────────────

  it('below 992px the walk is inert — a soft keyboard already owns Enter', () => {
    openDash()
    openSection('GuardianDiv')
    clickNew('#newGuardian')
    cy.get('#GuardianModal').should('have.class', 'open')

    // POSITIVE CONTROL at the width the suite runs at (1280): Enter DOES advance here, so the assertion
    // after the resize is about the WIDTH and not about the chain being broken.
    cy.get('#GuardianModal #guardianName').focus().type('{enter}')
    cy.focused().should(($el) => expect(focusedFieldId($el)).to.eq('guardianMobile'))

    cy.viewport(600, 800)
    cy.window().its('FocusFlow').invoke('mayAutoFocus').should('eq', false)
    cy.get('#GuardianModal #guardianName').focus().type('{enter}')
    cy.focused().should('have.id', 'guardianName')

    cy.viewport(1280, 800)
  })

  // ── the guard that matters most ───────────────────────────────────────────

  it('the mouse path is untouched — a form still saves by clicking its button', () => {
    cy.intercept('POST', '/addGuardian').as('save')
    openDash()
    openSection('GuardianDiv')
    clickNew('#newGuardian')
    fillGuardian('Mouse')
    cy.get('#addGuardian').click()                  // no Enter anywhere in this path
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.get('#GuardianModal').should('not.have.class', 'open')
  })

  // ── cleanup ───────────────────────────────────────────────────────────────
  // Best-effort: after() does NOT run when a case fails, so nothing above may depend on this having
  // happened. Everything created here is tagged with this run's STAMP, so leftovers are inert.
  after(() => {
    cy.loginAsEducation()
    cy.request({ url: '/getUserGuardian', failOnStatusCode: false }).then((res) => {
      rows(res).filter((x) => String(x.name || '').startsWith(TAG))
        .forEach((x) => cy.request({
          method: 'POST', url: '/deleteGuardian', form: true, body: { checked: x.id }, failOnStatusCode: false,
        }))
    })
    cy.request({ url: '/getUserGrade', failOnStatusCode: false }).then((res) => {
      rows(res).filter((x) => String(x.name || '').startsWith(TAG))
        .forEach((x) => cy.request({
          method: 'POST', url: '/deleteGrade', form: true, body: { checked: x.id }, failOnStatusCode: false,
        }))
    })
  })
})
