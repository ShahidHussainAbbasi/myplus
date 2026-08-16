/**
 * P6 — Purchase form: rapid line entry ("Save & Add Another").
 *
 * A delivery is many items on ONE bill: one vendor, one invoice number, one date, N items. The purchase
 * form inherited the REGISTER post-save behaviour (wipe the form, close the modal), so entering a 30-line
 * delivery cost 30 modal opens and 30 retyped headers.
 *
 * "Save & Add Another" keeps the modal open, clears only the LINE fields, and retains the header.
 * "Save & Close" (#addPurchase) is unchanged.
 *
 * The two assertions that matter most here are not the convenience ones:
 *   - #purchasePaid MUST clear between lines. It reads like a header field, but blank means "paid in
 *     full", so a sticky value posts the payment once per line and credits the vendor N times.
 *   - the Company screen must STILL close its modal — proof the shared handler in main.js was not broken
 *     for every other entity by giving Purchase its own behaviour.
 *
 * Run headed.
 */

const STAMP = Date.now()

// Fields that must be EMPTY after "Save & Add Another". Asserted one by one so a miss names itself
// instead of hiding inside a loop that reports only the first failure.
const LINE_FIELDS = [
  'purchaseItemDesc', 'purchaseQuantity', 'purchasePurchaseRate', 'purchaseSellRate',
  'purchaseBatchNo', 'purchaseExpiry', 'purchaseTaxRate', 'purchaseTotalAmount', 'purchaseNetAmount',
]

let productA, productB, productC, vendorName

function openFreshPurchaseModal() {
  cy.openPurchaseSection('purchaseDiv')
  cy.get('#newPurchase').click()
  cy.get('#PurchaseModal').should('have.class', 'open')
}

/** Fill the bill HEADER — vendor, invoice #, date. */
function fillHeader(invoiceNo) {
  // #purchaseVenderDD is a bootstrap-select, so the real <select> is hidden — force the change.
  cy.get('#purchaseVenderDD option', { timeout: 15000 })
    .contains(vendorName)
    .then(($o) => cy.get('#purchaseVenderDD').select($o.val(), { force: true }))
  cy.get('#purchaseInvoiceNo').clear().type(invoiceNo)
}

/**
 * Answer the vendor picker WITHOUT letting the answer move the cursor.
 *
 * Why the walk tests need this at all: an UNANSWERED bootstrap-select no longer advances on Enter —
 * it opens its menu (enter-chain.js, "closed, empty -> let the button's own click OPEN the menu"),
 * because Enter walking past 71 vendors left a required picker unfillable from the keyboard. The
 * order-of-the-chain tests below are not about that rule, so they answer the picker first and keep
 * their single subject; the rule itself has its own test.
 *
 * Native .select() fires `change` (which the vendor-dues row listens for) but NOT
 * `changed.bs.select`, and it is the latter the chain advances on — so focus stays exactly where the
 * test put it. The dues input it reveals is readonly, so it never becomes a chain stop.
 */
function answerVendor() {
  cy.get('#purchaseVenderDD option', { timeout: 15000 })
    .contains(vendorName)
    .then(($o) => cy.get('#purchaseVenderDD').select($o.val(), { force: true }))
  cy.get('#purchaseVenderDD').should('not.have.value', '')
}

/**
 * Answer the ITEM picker, silently — same reason as answerVendor(), plus the async pre-fill.
 *
 * Selecting an item fires /productStock, which writes qty and the rates. A walk that starts before
 * that lands is racing a handler that moves values under it.
 */
function answerItem(productId) {
  cy.intercept('GET', '/productStock*').as('itemPrefill')
  cy.get('#purchaseItemDD').select(String(productId), { force: true })
  cy.wait('@itemPrefill', { timeout: 15000 })
}

/**
 * Save the line and let the screen SETTLE before the next one.
 *
 * afterSavePurchase() kicks off datatable.ajax.reload() the moment the save responds, and the app's
 * global #appAjaxOverlay (position:fixed, inset:0, z-index:99999) covers the modal while it is in
 * flight — so the next line's click can land on the overlay instead of the button, with no request
 * and no error. Waiting for the reload is what makes rapid entry testable at all.
 *
 * The intercept is re-declared with a FRESH alias immediately before each click, so the wait can only
 * be satisfied by the reload THIS save triggered — an alias declared once would be consumed by the
 * section-open load and pass before the reload had even started.
 */
let gridSeq = 0
function saveLineAndSettle() {
  const alias = `grid${++gridSeq}`
  cy.intercept('GET', '**/getUserPurchase*').as(alias)
  cy.get('#addPurchaseAnother').click()
  cy.wait('@save').its('response.statusCode').should('eq', 200)
  cy.wait(`@${alias}`, { timeout: 20000 })
}

/** Fill ONE line. Selecting the item fires an async pre-fill that bumps qty, so wait for it first. */
function fillLine(productId, qty) {
  cy.intercept('GET', '/productStock*').as('prefill')
  cy.get('#purchaseItemDD').select(String(productId), { force: true })
  cy.wait('@prefill', { timeout: 15000 })
  cy.get('#purchaseQuantity').clear().type(String(qty))
  cy.get('#purchasePurchaseRate').clear().type('10')
  cy.get('#purchaseSellRate').clear().type('15')
}

describe('P6 — Purchase rapid line entry', () => {
  before(() => {
    cy.loginAsBusiness()
    vendorName = `P6Vendor_${STAMP}`
    // SEED, never assert-or-skip: the test owns its fixtures so an empty tenant cannot silently pass.
    //
    // companyId is NOT optional. VenderDTO's annotations do not say so — addVender's body ends with an
    // unguarded getReferenceById(dto.getCompanyId()), so omitting it throws and returns the generic
    // "An unexpected error occurred. Please contact support." A vendor belongs to a company.
    cy.ensureCompany().then((companyId) => {
      cy.request({
        method: 'POST', url: '/addVender', form: true,
        body: { name: vendorName, mobile: '03001234567', companyId }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.status, 'seed vendor').to.eq(200)
        expect(r.body.status, JSON.stringify(r.body)).to.be.oneOf(['SUCCESS', 'FOUND'])
      })
    })
    cy.seedProduct({ name: `P6ItemA_${STAMP}`, sellingPrice: 15, stock: 50 }).then((p) => { productA = p.productId })
    cy.seedProduct({ name: `P6ItemB_${STAMP}`, sellingPrice: 15, stock: 50 }).then((p) => { productB = p.productId })
    cy.seedProduct({ name: `P6ItemC_${STAMP}`, sellingPrice: 15, stock: 50 }).then((p) => { productC = p.productId })
  })

  beforeEach(() => { cy.loginAsBusiness() })   // testIsolation clears the session between tests

  // ── 1. no regression on the existing button ────────────────────────────────

  it('Save & Close still closes the modal', () => {
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(`P6-CLOSE-${STAMP}`)
    fillLine(productA, 2)
    cy.get('#addPurchase').click()
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.get('#PurchaseModal').should('not.have.class', 'open')
  })

  // ── 2-6. the sticky path ───────────────────────────────────────────────────

  it('Save & Add Another leaves the modal OPEN', () => {
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(`P6-OPEN-${STAMP}`)
    fillLine(productA, 2)
    cy.get('#addPurchaseAnother').click()
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.get('#PurchaseModal').should('have.class', 'open')
  })

  it('the HEADER survives — vendor, invoice # and date are retained', () => {
    const inv = `P6-HDR-${STAMP}`
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(inv)

    // Set a date that is NOT today, so a pass proves RETENTION rather than the field simply being
    // re-defaulted to today's date after the save — those are indistinguishable otherwise.
    // Re-read after blur: the date picker may reformat what was typed, and the contract is that
    // whatever the field ends up holding survives, not that the typed string does.
    cy.get('#purchaseDate').clear().type('01082026').blur()
    cy.get('#purchaseDate').should('have.value', '01-08-2026')
    cy.get('#purchaseDate').invoke('val').then((dateBefore) => {
      cy.get('#purchaseVenderDD').invoke('val').then((vendorBefore) => {
        fillLine(productA, 2)
        cy.get('#addPurchaseAnother').click()
        cy.wait('@save')
        cy.get('#purchaseInvoiceNo').should('have.value', inv)
        cy.get('#purchaseVenderDD').should('have.value', vendorBefore)
        cy.get('#purchaseDate').should('have.value', dateBefore)
      })
    })
  })

  it('every LINE field is cleared', () => {
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(`P6-LINE-${STAMP}`)
    fillLine(productA, 7)
    cy.get('#purchaseBatchNo').clear().type('BATCH-1')
    cy.get('#addPurchaseAnother').click()
    cy.wait('@save')

    // The item picker is a bootstrap-select: resetBSDD parks the real <select> on 'default'.
    cy.get('#purchaseItemDD').invoke('val').should('be.oneOf', ['default', '', null])
    LINE_FIELDS.forEach((id) => {
      cy.get(`#${id}`).should('have.value', '')
    })
  })

  it('#purchasePaid is cleared — a sticky payment would credit the vendor once per line', () => {
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(`P6-PAID-${STAMP}`)
    fillLine(productA, 3)
    cy.get('#purchasePaid').clear().type('500')
    cy.get('#addPurchaseAnother').click()
    cy.wait('@save')
    cy.get('#purchasePaid').should('have.value', '')
  })

  it('focus returns to the item picker, ready for the next line', () => {
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(`P6-FOCUS-${STAMP}`)
    fillLine(productA, 2)
    cy.get('#addPurchaseAnother').click()
    cy.wait('@save')
    // bootstrap-select hides the <select> behind a button, so the focused element is that button —
    // inside the .bootstrap-select wrapper that immediately follows #purchaseItemDD.
    cy.focused().should(($el) => {
      const inPicker = Cypress.$($el).closest('.bootstrap-select').prev('#purchaseItemDD').length > 0
      const isSelect = Cypress.$($el).attr('id') === 'purchaseItemDD'
      expect(inPicker || isSelect, 'focus is on the item picker').to.be.true
    })
  })

  // ── 7-8. the whole point: three lines, one bill ────────────────────────────

  it('the counter increments across three consecutive lines', () => {
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(`P6-COUNT-${STAMP}`)

    fillLine(productA, 1)
    saveLineAndSettle()
    cy.get('#purchaseLineCount').should('be.visible').and('contain', '1')

    fillLine(productB, 2)
    saveLineAndSettle()
    cy.get('#purchaseLineCount').should('contain', '2')

    fillLine(productC, 3)
    saveLineAndSettle()
    cy.get('#purchaseLineCount').should('contain', '3')
  })

  it('three lines land as three rows against ONE invoice number', () => {
    const inv = `P6-BILL-${STAMP}`
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(inv)

    const items = [productA, productB, productC]
    items.forEach((pid, i) => {
      fillLine(pid, i + 1)
      saveLineAndSettle()
    })

    // Assert against the SERVER, not the grid — three rows really persisted under one invoice.
    cy.request({ url: '/getUserPurchase', failOnStatusCode: false }).then((res) => {
      const rows = res.body.collection || res.body.data || []
      const mine = rows.filter((p) => p.purchaseInvoiceNo === inv)
      expect(mine.length, `rows saved under invoice ${inv}`).to.eq(3)
    })
  })

  // ── 9-10. the guards ───────────────────────────────────────────────────────

  it('EDITING an existing purchase closes the modal — "add another" has no meaning there', () => {
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(`P6-EDIT-${STAMP}`)
    fillLine(productA, 4)
    cy.get('#addPurchaseAnother').click()
    cy.wait('@save')
    cy.get('#PurchaseModal').should('have.class', 'open')

    // Put the form into EDIT state the way loading a row does, then submit again. This drives the
    // guard directly rather than the row-click journey, which is covered in purchase.cy.js.
    //
    // An edit does NOT post to /addPurchase — main.js switches the action to /updatePurchase once
    // #purchaseId holds a value. Watching only the create endpoint means waiting for a request that
    // by definition never comes.
    cy.intercept('POST', '/updatePurchase').as('update')
    cy.request({ url: '/getUserPurchase', failOnStatusCode: false }).then((res) => {
      const rows = res.body.collection || res.body.data || []
      expect(rows.length, 'a saved purchase to edit').to.be.greaterThan(0)
      const id = rows[rows.length - 1].purchaseId || rows[rows.length - 1].id
      expect(id, 'purchase id').to.exist
      fillLine(productB, 5)
      cy.get('#purchaseId').invoke('val', id)
      cy.get('#addPurchaseAnother').click()
      cy.wait('@update').its('response.statusCode').should('eq', 200)
      cy.get('#PurchaseModal').should('not.have.class', 'open')
    })
  })

  // ── 11-16. mouse-free: the Enter chain ─────────────────────────────────────

  it('opening the modal puts the cursor on the invoice # — a bill starts without a click', () => {
    openFreshPurchaseModal()
    // openModal() -> focusFirstField() lands here. P6 adds no second auto-focus; two things moving
    // the cursor is the bug, not the feature.
    cy.focused({ timeout: 5000 }).should('have.id', 'purchaseInvoiceNo')
  })

  // ── P7.1: the chain is DERIVED from the form, not hand-listed ──────────────

  it('the derived chain is exactly the fields a person can type into, in layout order', () => {
    // This is the whole P7.1 claim in one assertion. It used to be an explicit array of 11 ids in
    // business.js; computing it from the DOM must produce the SAME walk, or the migration silently
    // changed what the operator experiences.
    //
    // The three computed boxes (#purchaseStock, #purchaseTotalAmount, #purchaseNetAmount) fall out
    // on their own: they carry disabled="disabled", which skip() has always excluded.
    cy.intercept('GET', '**/getTaxSetting', {
      body: { status: 'SUCCESS', object: { inputTaxEnabled: false } },
    }).as('taxOff')
    openFreshPurchaseModal()
    cy.wait('@taxOff')
    cy.window().then((w) => {
      expect(w.EnterChain.fieldsIn('#Purchase')).to.deep.eq([
        'purchaseInvoiceNo', 'purchaseBatchNo', 'purchaseVenderDD',
        'purchaseItemDD', 'purchaseQuantity', 'purchasePurchaseRate', 'purchaseSellRate',
        'purchaseDate', 'purchaseExpiry', 'purchasePaid',
      ])
    })
  })

  it('computed boxes are not stops — they are disabled, and skip() has always excluded those', () => {
    openFreshPurchaseModal()
    cy.window().then((w) => {
      const chain = w.EnterChain.fieldsIn('#Purchase')
      expect(chain, 'stock is skipped').to.not.include('purchaseStock')
      expect(chain, 'total is skipped').to.not.include('purchaseTotalAmount')
      expect(chain, 'net is skipped').to.not.include('purchaseNetAmount')
    })
    cy.get('#purchaseTotalAmount').should('have.attr', 'disabled')
  })

  it('data-kbd-skip removes a field that is otherwise a perfectly good stop', () => {
    // The markup opt-out, exercised on a field that IS in the chain — the purchase form does not
    // need it (its computed boxes are disabled already), so adding it at runtime is the honest way
    // to prove the mechanism rather than decorating markup where it would do nothing.
    openFreshPurchaseModal()
    cy.window().then((w) => {
      expect(w.EnterChain.fieldsIn('#Purchase')).to.include('purchaseBatchNo')
      w.document.getElementById('purchaseBatchNo').setAttribute('data-kbd-skip', '')
      expect(w.EnterChain.fieldsIn('#Purchase')).to.not.include('purchaseBatchNo')
    })
    // ...and the walk really steps over it: invoice -> vendor, not invoice -> batch.
    cy.get('#purchaseInvoiceNo').focus().type('{enter}')
    cy.focused().should(($el) => {
      expect(Cypress.$($el).closest('.bootstrap-select').prev('#purchaseVenderDD').length,
             'batch was skipped').to.eq(1)
    })
  })

  it('the derived chain FOLLOWS configuration — the tax field joins it when the org uses tax', () => {
    cy.intercept('GET', '**/getTaxSetting', {
      body: { status: 'SUCCESS', object: { inputTaxEnabled: true } },
    }).as('taxOn')
    openFreshPurchaseModal()
    cy.wait('@taxOn')
    // Wait for the ROW, not just the response: refreshPurchaseTaxRow shows it in the $.get callback,
    // so reading the chain straight after the wait races the handler that makes the field visible.
    cy.get('#purchaseTaxRow').should('be.visible')
    cy.window().then((w) => {
      const chain = w.EnterChain.fieldsIn('#Purchase')
      expect(chain).to.include('purchaseTaxRate')
      // and in its LAYOUT position — after the vendor, before the item
      expect(chain.indexOf('purchaseTaxRate')).to.be.greaterThan(chain.indexOf('purchaseVenderDD'))
      expect(chain.indexOf('purchaseTaxRate')).to.be.lessThan(chain.indexOf('purchaseItemDD'))
    })
  })

  it('Enter walks the WHOLE chain in the order the form is laid out', () => {
    // Pin the tax setting rather than hiding the row by hand: refreshPurchaseTaxRow() re-decides the
    // row's visibility when /getTaxSetting lands, so a forced hide is a race it happens to win.
    cy.intercept('GET', '**/getTaxSetting', {
      body: { status: 'SUCCESS', object: { inputTaxEnabled: false } },
    }).as('taxOff')
    openFreshPurchaseModal()
    cy.wait('@taxOff')
    // BOTH pickers, not just the vendor: this test is about ORDER, and an unanswered picker legitimately
    // stops the walk to open its menu. Leaving the item empty put the cursor in bootstrap-select's
    // live-search box, which is the rule working — in the one test that is not about the rule.
    answerVendor()
    answerItem(productA)
    cy.get('#purchaseInvoiceNo').focus().type('{enter}')
    cy.focused().should('have.id', 'purchaseBatchNo')

    cy.focused().type('{enter}')
    cy.focused().should(($el) => {
      expect(Cypress.$($el).closest('.bootstrap-select').prev('#purchaseVenderDD').length,
             'reached the vendor picker').to.eq(1)
    })

    // Enter on a picker that is CLOSED and ALREADY ANSWERED advances like any other field — it does
    // not re-open the list to ask a question the operator has answered.
    cy.focused().type('{enter}')
    cy.focused().should(($el) => {
      expect(Cypress.$($el).closest('.bootstrap-select').prev('#purchaseItemDD').length,
             'Enter on the vendor picker reached the item picker').to.eq(1)
    })
    cy.focused().type('{enter}')
    cy.focused().should('have.id', 'purchaseQuantity')

    cy.focused().type('{enter}')
    cy.focused().should('have.id', 'purchasePurchaseRate')
    cy.focused().type('{enter}')
    cy.focused().should('have.id', 'purchaseSellRate')
    cy.focused().type('{enter}')
    cy.focused().should('have.id', 'purchaseDate')
    cy.focused().type('{enter}')
    cy.focused().should('have.id', 'purchaseExpiry')
    cy.focused().type('{enter}')
    cy.focused().should('have.id', 'purchasePaid')
  })

  it('Shift+Enter walks BACK up the chain', () => {
    openFreshPurchaseModal()
    cy.get('#purchaseBatchNo').focus().type('{shift}{enter}')
    cy.focused().should('have.id', 'purchaseInvoiceNo')
  })

  it('the tax rate is a chain stop only when the org uses purchase tax', () => {
    // Drive this from the SETTING, not from .invoke('show'/'hide'). newPurchase() calls
    // refreshPurchaseTaxRow(), which fetches /getTaxSetting and shows or hides the row when the
    // response lands — so a class forced on beforehand is silently undone a moment later, which is
    // exactly how the first version of this test failed. Stub the setting and the row's state is
    // whatever the org's configuration says, for any tenant.
    cy.intercept('GET', '**/getTaxSetting', {
      body: { status: 'SUCCESS', object: { inputTaxEnabled: true } },
    }).as('taxOn')
    openFreshPurchaseModal()
    cy.wait('@taxOn')
    cy.get('#purchaseTaxRow').should('be.visible')
    cy.window().then((w) => {
      expect(w.EnterChain.usable('purchaseTaxRate'), 'a visible tax rate is in the chain').to.be.true
    })
    // Vendor -> tax rate: with the row shown it is a real stop.
    answerVendor()                      // an unanswered picker would open instead of advancing
    cy.get('#purchaseBatchNo').focus().type('{enter}')
    cy.focused().type('{enter}')
    cy.focused().should('have.id', 'purchaseTaxRate')
  })

  it('a HIDDEN field is skipped — vendor goes straight to the item', () => {
    cy.intercept('GET', '**/getTaxSetting', {
      body: { status: 'SUCCESS', object: { inputTaxEnabled: false } },
    }).as('taxOff')
    openFreshPurchaseModal()
    cy.wait('@taxOff')
    cy.get('#purchaseTaxRow').should('not.be.visible')
    cy.window().then((w) => {
      expect(w.EnterChain.usable('purchaseTaxRate'), 'hidden tax rate drops out of the chain').to.be.false
    })
    // batch -> vendor -> ITEM, stepping over the tax rate entirely.
    answerVendor()                      // an unanswered picker would open instead of advancing
    cy.get('#purchaseBatchNo').focus().type('{enter}')
    cy.focused().type('{enter}')
    cy.focused().should(($el) => {
      expect(Cypress.$($el).closest('.bootstrap-select').prev('#purchaseItemDD').length,
             'the hidden tax rate was skipped').to.eq(1)
    })
  })

  // ── the picker rule itself, on its own ─────────────────────────────────────

  /**
   * The reported bug, in its purchase-form form: Enter walked straight past the vendor list without
   * ever showing it, so a picker could only be filled with the mouse. An UNANSWERED picker now opens.
   *
   * This is deliberately its own test rather than a step inside the walk tests above: those fail when
   * the ORDER is wrong, this one fails when the RULE is wrong, and a failure that names itself is
   * worth more than one fewer test.
   */
  it('Enter OPENS an unanswered vendor picker instead of stepping over it', () => {
    openFreshPurchaseModal()
    cy.get('#purchaseVenderDD').should('have.value', '')      // genuinely unanswered
    cy.get('#purchaseBatchNo').focus().type('{enter}')
    cy.focused().should(($el) => {
      expect(Cypress.$($el).closest('.bootstrap-select').prev('#purchaseVenderDD').length,
             'reached the vendor picker').to.eq(1)
    })

    cy.focused().type('{enter}')
    cy.get('#purchaseVenderDD').next('.bootstrap-select')
      .should('have.class', 'open')                           // the list is on screen
    // ...and the cursor stayed on the picker, so the next keystroke belongs to the open menu.
    cy.focused().should(($el) => {
      expect(Cypress.$($el).closest('.bootstrap-select').prev('#purchaseVenderDD').length,
             'focus stayed on the picker').to.eq(1)
    })
  })

  it('Shift+Enter reverses out of an UNANSWERED picker — it never opens it', () => {
    // The carve-out that keeps the chain two-way. Without it "go back" would open the menu instead,
    // and an empty required dropdown would be a one-way door.
    openFreshPurchaseModal()
    cy.get('#purchaseVenderDD').should('have.value', '')
    cy.get('#purchaseBatchNo').focus().type('{enter}')
    cy.focused().type('{shift}{enter}')
    cy.get('#purchaseVenderDD').next('.bootstrap-select').should('not.have.class', 'open')
    cy.focused().should('have.id', 'purchaseBatchNo')
  })

  // ── date fields must be TYPEABLE, not calendar-only ────────────────────────

  it('the purchase date can be typed — digits are masked into dd-MM-yyyy', () => {
    openFreshPurchaseModal()
    cy.get('#purchaseDate').clear().type('11082026')
    cy.get('#purchaseDate').should('have.value', '11-08-2026')
  })

  it('the expiry date can be typed too', () => {
    openFreshPurchaseModal()
    cy.get('#purchaseExpiry').clear().type('01122027')
    cy.get('#purchaseExpiry').should('have.value', '01-12-2027')
  })

  it('choosing an item does NOT wipe a back-dated purchase date', () => {
    // initDates() runs on every .onChangeSelect change and used to assign unconditionally, so picking
    // the item snapped the date back to today — a back-dated delivery could not be entered at all.
    openFreshPurchaseModal()
    cy.get('#purchaseDate').clear().type('01082026')
    cy.get('#purchaseDate').should('have.value', '01-08-2026')
    fillLine(productA, 2)
    cy.get('#purchaseDate').should('have.value', '01-08-2026')
  })

  it('Enter on the LAST field saves and stays open — a whole line without the mouse', () => {
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(`P6-KBD-${STAMP}`)
    fillLine(productA, 2)
    cy.get('#purchasePaid').focus().type('{enter}')
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.get('#PurchaseModal').should('have.class', 'open')
    cy.get('#purchaseLineCount').should('be.visible').and('contain', '1')
  })

  it('Ctrl+Enter finishes the bill from ANY field', () => {
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(`P6-CTRL-${STAMP}`)
    fillLine(productA, 2)
    // From the middle of the chain, not the end — that is the point of Ctrl+Enter.
    cy.get('#purchaseQuantity').focus().type('{ctrl}{enter}')
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.get('#PurchaseModal').should('not.have.class', 'open')
  })

  it('Escape cancels without saving', () => {
    let posted = false
    cy.intercept('POST', '/addPurchase', (req) => { posted = true; req.continue() })
    openFreshPurchaseModal()
    fillHeader(`P6-ESC-${STAMP}`)
    cy.get('#purchaseInvoiceNo').focus().type('{esc}')
    cy.get('#PurchaseModal').should('not.have.class', 'open')
    cy.then(() => expect(posted, 'Escape must not save').to.be.false)
  })

  it('the sale screen chain still works — the shared engine did not break P1-P3', () => {
    // pos-keyboard.js now delegates usable/walk/focusField to EnterChain. If that delegation is
    // wrong, the sale chain breaks silently, so assert it here rather than trusting the refactor.
    cy.openSellSection('sellDiv')
    cy.window().its('EnterChain').should('exist')
    cy.window().then((w) => {
      expect(w.EnterChain.usable('sellItemDD'), 'sale item picker is usable').to.be.true
      expect(w.EnterChain.walk(['sellItemDD', 'sellItems'], 'sellItemDD', 1)).to.eq('sellItems')
    })
  })

  it('the Company screen STILL closes its modal — the shared handler is intact', () => {
    cy.intercept('POST', '/addCompany').as('addCompany')
    cy.openSection('CompanyDiv')   // the option value is capitalised — 'companyDiv' matches nothing
    cy.get('#newCompany').click()
    cy.get('#CompanyModal').should('have.class', 'open')
    cy.get('#companyName').clear().type(`P6Co_${STAMP}`)
    cy.get('#addCompany').click()
    cy.wait('@addCompany').its('response.statusCode').should('eq', 200)
    cy.get('#CompanyModal').should('not.have.class', 'open')
  })
})
