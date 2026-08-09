/**
 * POS P2 — queue throughput: the scan-box quantity multiplier and the action keys.
 *
 * Same two-halves rule as the P1 gate, and for the same reason: every existing tenant has this OFF,
 * so "nothing changed for a shop that never asked" is the assertion that protects them. With the
 * feature off a '*' is just a character in a barcode and no function key does anything.
 *
 * The multiplier's edge cases are exercised against the PURE PARSER (window.parseScanEntry) rather
 * than only through the UI: "0*ABC is refused" is a statement about the parser, and proving it
 * through a network round-trip would be slower and would fail for reasons unrelated to parsing.
 *
 * Run headed.
 */

function openSell(opts) {
  var o = opts || {}
  cy.visit('/businessDashboard')
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellDiv').should('be.visible')
  // Assert the modules loaded — a silent guard here turned one honest failure into seven confusing
  // ones during the P1 gate, and the lesson is cheap to keep.
  cy.window().should((w) => {
    expect(w.parseScanEntry, 'business.js exposes parseScanEntry').to.be.a('function')
    expect(w.posExactCash, 'pos-keyboard.js is loaded').to.be.a('function')
  })
  cy.window().then((w) => {
    w.posShortcutsEnabled = o.shortcuts === true
    w.posKeyboardEnabled = o.keyboard === true
    if (typeof w.applyPosKeyboard === 'function') w.applyPosKeyboard()
  })
}

/** Fire a key at the document the way a real keyboard does. */
function pressKey(key, alt) {
  cy.document().then((doc) => {
    const ev = new doc.defaultView.KeyboardEvent('keydown', {
      key: key, altKey: alt === true, bubbles: true, cancelable: true
    })
    doc.dispatchEvent(ev)
  })
}

/**
 * Type into the scan box, tolerating the global AJAX overlay.
 *
 * `/js/common/ajax-overlay.js` shows `.ao-box` on jQuery's ajaxStart and hides it on ajaxStop, and
 * this screen fires several requests as it settles (flags, customers, price rules). A `.type()` that
 * arrives mid-request is refused as "covered by another element" — or, while the overlay fades, as
 * "could not determine actionability" because the element is animating. Both are the spinner doing
 * its job, not a defect.
 *
 * The long timeout is the fix sell.cy.js already documents: Cypress re-checks continuously and acts
 * the instant the overlay lifts. Deliberately NOT `{force:true}` — forcing would type THROUGH a
 * spinner a real cashier cannot type through, so a genuinely stuck overlay would pass this gate.
 */
function scan(entry) {
  cy.get('#sellScan', { timeout: 30000 }).should('be.visible').type(entry, { timeout: 30000 })
}

describe('P2 — the quantity-multiplier parser', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    openSell({ shortcuts: true })
  })

  it('accepts a count, a star, then the code', () => {
    cy.window().then((w) => {
      expect(w.parseScanEntry('12*ABC123')).to.deep.eq({ qty: 12, code: 'ABC123' })
      expect(w.parseScanEntry('  3 * XYZ  '), 'spaces around the parts are tolerated')
        .to.deep.eq({ qty: 3, code: 'XYZ' })
      expect(w.parseScanEntry('1*A')).to.deep.eq({ qty: 1, code: 'A' })
    })
  })

  it('a plain code is unchanged — qty 1, exactly as before P2', () => {
    cy.window().then((w) => {
      expect(w.parseScanEntry('ABC123')).to.deep.eq({ qty: 1, code: 'ABC123' })
      expect(w.parseScanEntry('  8901234567890  ')).to.deep.eq({ qty: 1, code: '8901234567890' })
    })
  })

  /**
   * REFUSES rather than guesses. Every case below could be "helpfully" read as qty 1 — and every one
   * would then put a line the cashier never intended onto a real invoice. An error they can see and
   * correct in one keystroke is the cheaper failure.
   */
  it('refuses a quantity that is not a positive whole number', () => {
    cy.window().then((w) => {
      expect(w.parseScanEntry('0*ABC').error, 'zero').to.eq('badQty')
      expect(w.parseScanEntry('-3*ABC').error, 'negative is a return, not a sale').to.eq('badQty')
      expect(w.parseScanEntry('1.5*ABC').error, 'fractional units').to.eq('badQty')
      expect(w.parseScanEntry('12abc*XYZ').error, 'parseInt would have returned 12').to.eq('badQty')
      expect(w.parseScanEntry('abc*def').error, 'not a number at all').to.eq('badQty')
    })
  })

  it('refuses a star with nothing on one side of it', () => {
    cy.window().then((w) => {
      expect(w.parseScanEntry('*ABC').error).to.eq('noQty')
      expect(w.parseScanEntry('12*').error).to.eq('noCode')
      expect(w.parseScanEntry('').error).to.eq('empty')
    })
  })
})

describe('P2 — OFF (default): nothing changed', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('a star in a scanned code is taken literally, not as a multiplier', () => {
    cy.intercept('GET', '**/lookupProduct*').as('lookup')
    openSell({ shortcuts: false })
    scan('12*NOSUCHCODE{enter}')
    // The WHOLE string is sent as the code — a shop whose barcodes contain '*' is unaffected.
    cy.wait('@lookup').its('request.query.code').should('eq', '12*NOSUCHCODE')
  })

  it('no action key does anything', () => {
    cy.seedProduct({ name: 'ShOff_' + Date.now(), sellingPrice: 50, stock: 5 }).then(({ productId }) => {
      openSell({ shortcuts: false, keyboard: true })
      cy.get('#sellItemDD').select(String(productId), { force: true })
      cy.get('#sellSellRate', { timeout: 10000 }).should('not.have.value', '')
      cy.get('#sellItems').clear().type('1')
      cy.get('#addInviceItem').click({ timeout: 30000 })
      cy.window().its('data').should('have.length', 1)

      pressKey('F8')                                   // would tender the exact amount when ON
      cy.get('#sellRec').should('have.value', '')
      pressKey('F9')                                   // would ask to clear the cart when ON
      cy.window().its('data').should('have.length', 1)
    })
  })
})

describe('P2 — ON', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('scanning 12*<sku> adds twelve in one action', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'ShMul_' + stamp, sku: 'MUL' + stamp, sellingPrice: 20, stock: 50 })
      .then(({ productId, sku }) => {
        openSell({ shortcuts: true })
        scan('12*' + sku + '{enter}')
        cy.window().its('data').should('have.length', 1)
        cy.window().its('data').then((d) => {
          expect(String(d[0].productId)).to.eq(String(productId))
          expect(Number(d[0].quantity), 'twelve units from one scan').to.eq(12)
        })
      })
  })

  it('scanning the same code again ADDS to the existing line rather than replacing it', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'ShAdd_' + stamp, sku: 'ADD' + stamp, sellingPrice: 20, stock: 50 })
      .then(({ sku }) => {
        openSell({ shortcuts: true })
        scan('3*' + sku + '{enter}')
        // Wait on the QUANTITY, not the array length: both scans hit the same product, so length is 1
        // either way and would pass before the second lookup returned. `.should()` retries; `.then()`
        // does not — the first draft used `.then()` and read quantity 3 mid-flight.
        cy.window().its('data.0.quantity').should('eq', 3)
        scan('2*' + sku + '{enter}')
        cy.window().its('data.0.quantity').should('eq', 5)      // 3 then 2 accumulate
        cy.window().its('data').should('have.length', 1)         // and stay ONE line
      })
  })

  it('a bad quantity is refused, keeps what was typed, and adds nothing', () => {
    openSell({ shortcuts: true })
    scan('0*ABC{enter}')
    cy.window().its('data').should('have.length', 0)
    // The entry survives so the fix is one keystroke, not a re-scan.
    cy.get('#sellScan').should('have.value', '0*ABC')
    cy.get('#sellScanMsg').should('not.have.text', '')
  })

  /**
   * REGRESSION — a scanned line used to contribute NOTHING to the cart total.
   *
   * scanAddToCart pushed '' into the grid's Total column and never set line.totalAmount. #sellTotal is
   * that column's footer sum, so a scanned-only cart totalled zero — and calculateChange() derives
   * Change and "Due (this sale)" from #sellTotal, so a cashier scanning with no customer selected saw
   * Due 0.00 on a sale that was owed. requoteSellCart() filled the column in, but only once a customer
   * was chosen, which is how it survived: the B2B path masked it. Found by F8 refusing to tender.
   */
  it('a scanned line contributes its money to the cart total', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'ShTot_' + stamp, sku: 'TOT' + stamp, sellingPrice: 25, stock: 20 })
      .then(({ sku }) => {
        openSell({ shortcuts: true })
        scan('4*' + sku + '{enter}')     // 4 x 25 = 100
        cy.window().its('data.0.quantity').should('eq', 4)
        cy.window().its('data.0.totalAmount').should('not.be.oneOf', [undefined, null, ''])
        // The footer the whole checkout reads. NO customer is selected — that is the case that broke.
        cy.get('#sellTotal').should('contain', '100')
      })
  })

  it('F8 tenders the exact cart total; change and due come out at zero', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'ShCash_' + stamp, sku: 'CSH' + stamp, sellingPrice: 25, stock: 20 })
      .then(({ sku }) => {
        openSell({ shortcuts: true })
        scan('4*' + sku + '{enter}')     // 4 x 25 = 100
        cy.window().its('data.0.quantity').should('eq', 4)

        pressKey('F8')
        cy.get('#sellRec').should('have.value', '100.00')
        // The two settlement fields are formatted DIFFERENTLY by calculateChange, on purpose:
        //   #sellCh      <- val(change)              a raw number, so "0" — it is submitted as
        //                                           customer.dueAmount and must stay numeric
        //   #sellDueThis <- val(dueThis.toFixed(2))  a display string, so "0.00"
        // Asserting both in the same format is what failed the first run. Read the source, don't
        // assume two adjacent money fields agree.
        cy.get('#sellCh').should('have.value', '0')
        cy.get('#sellDueThis').should('have.value', '0.00')
      })
  })

  it('Alt+E is an alias for F8', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'ShAlt_' + stamp, sku: 'ALT' + stamp, sellingPrice: 30, stock: 20 })
      .then(({ sku }) => {
        openSell({ shortcuts: true })
        scan('2*' + sku + '{enter}')      // 60
        cy.window().its('data').should('have.length', 1)
        pressKey('e', true)
        cy.get('#sellRec').should('have.value', '60.00')
      })
  })

  it('F8 on an empty cart does nothing — no zero tender', () => {
    openSell({ shortcuts: true })
    pressKey('F8')
    cy.get('#sellRec').should('have.value', '')
  })

  /**
   * F9 confirms even though the Clear Cart BUTTON does not. A mis-hit on a function key next to F8
   * would otherwise destroy a part-rung sale with a queue waiting; someone who aims at a red button
   * has already expressed intent. The asymmetry is deliberate.
   */
  it('F9 asks before clearing the cart', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'ShClr_' + stamp, sku: 'CLR' + stamp, sellingPrice: 10, stock: 20 })
      .then(({ sku }) => {
        openSell({ shortcuts: true })
        scan('2*' + sku + '{enter}')
        cy.window().its('data').should('have.length', 1)

        pressKey('F9')
        cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).should('be.visible')
        cy.get('[data-ui-confirm="ok"]').click()
        cy.window().its('data').should('have.length', 0)
      })
  })

  it('cancelling that prompt leaves the cart intact', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'ShKeep_' + stamp, sku: 'KEP' + stamp, sellingPrice: 10, stock: 20 })
      .then(({ sku }) => {
        openSell({ shortcuts: true })
        scan('3*' + sku + '{enter}')
        cy.window().its('data').should('have.length', 1)

        pressKey('F9')
        // Only the OK button carries a data-ui-confirm hook; the cancel button is identified by its
        // own class (confirm-dialog.js: 'uiC-btn uiC-cancel'). Asserting it is visible first so a
        // renamed class fails as "cancel button not found" rather than as a silent no-op click.
        cy.get('.uiC-cancel', { timeout: 10000 }).should('be.visible').click()
        cy.window().its('data').should('have.length', 1)   // the sale in progress survived
      })
  })

  it('F9 on an empty cart does not raise a prompt about nothing', () => {
    openSell({ shortcuts: true })
    pressKey('F9')
    cy.wait(300)
    cy.get('[data-ui-confirm="ok"]').should('not.exist')
  })

  it('F3 parks the sale, and it comes back in the parked list', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'ShPark_' + stamp, sku: 'PRK' + stamp, sellingPrice: 15, stock: 20 })
      .then(({ sku }) => {
        cy.intercept('POST', '**/parkSale').as('park')
        openSell({ shortcuts: true })
        scan('2*' + sku + '{enter}')
        cy.window().its('data').should('have.length', 1)

        pressKey('F3')
        cy.wait('@park').its('response.statusCode').should('eq', 200)
        cy.window().its('data').should('have.length', 0)     // parking clears the till for the next customer
      })
  })

  it('a shop that switched parking off has no park key either', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'ShNoPark_' + stamp, sku: 'NPK' + stamp, sellingPrice: 15, stock: 20 })
      .then(({ sku }) => {
        cy.intercept('POST', '**/parkSale', () => {
          throw new Error('F3 parked a sale for a tenant that disabled parking')
        }).as('deadPark')
        openSell({ shortcuts: true })
        cy.window().then((w) => { w.posFields = { park: false }; w.applyPosFieldVisibility() })
        scan('1*' + sku + '{enter}')
        cy.window().its('data').should('have.length', 1)

        pressKey('F3')
        cy.wait(300)
        cy.window().its('data').should('have.length', 1)     // still there — nothing was parked
      })
  })

  it('action keys are ignored while a modal is open', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'ShModal_' + stamp, sku: 'MDL' + stamp, sellingPrice: 25, stock: 20 })
      .then(({ sku }) => {
        openSell({ shortcuts: true })
        scan('2*' + sku + '{enter}')
        cy.window().its('data').should('have.length', 1)

        cy.window().then((w) => {
          w.$('body').append('<div class="crud-overlay open" id="fakeOverlay2"></div>')
        })
        pressKey('F8')
        cy.get('#sellRec').should('have.value', '')          // suppressed behind the dialog
        cy.window().then((w) => { w.$('#fakeOverlay2').remove() })
      })
  })

  it('action keys are ignored when the sell screen is not the one on show', () => {
    cy.visit('/businessDashboard')
    cy.window().should((w) => { expect(w.posExactCash).to.be.a('function') })
    cy.window().then((w) => { w.posShortcutsEnabled = true })
    // Never opened #sellDiv: the dashboard is showing.
    cy.get('#sellDiv').should('not.be.visible')
    pressKey('F8')
    cy.get('#sellRec').should('have.value', '')
  })
})
