/**
 * PUR-INLINE — register a product without leaving the bill. Run headed.
 *
 * THE COMPLAINT: receiving a delivery that contains an unregistered product cost the operator the whole bill —
 * leave Purchase, go to Products, save, come back, retype vendor / invoice no / date, find the product.
 *
 * ⚠ WHAT THIS SLICE IS: opening the REAL ProductModal over the purchase form, through the same newProduct(),
 * and wiring the RETURN JOURNEY — which is the only part that did not already exist. An earlier proposal drew a
 * cut-down four-field panel instead; it was rejected because it would have been a second product-creation UI
 * with its own validation and none of the optional fields. Case 1 pins that decision: it asserts the form that
 * opens is the real one, by looking for a field only the real one has.
 *
 * Four of these cases exist because stacking two .crud-overlays had never happened before in this app, and each
 * edge was found by tracing rather than by it failing in front of someone:
 *   case 5  one Escape used to close BOTH forms
 *   case 6  loadDataTable() resets the SHARED `edit` flag
 *   case 7  the duplicate guard must still hold through this path
 *   case 10 the Products screen must behave exactly as it did before
 */
const uniq = () => `${Date.now()}_${Math.floor(Math.random() * 1e4)}`

const countNamed = (prefix) =>
  cy.request({ url: '/getUserProduct?includeInactive=true', failOnStatusCode: false }).then((r) => {
    expect(r.status, 'the product list read itself succeeded').to.eq(200)
    expect(r.body && r.body.collection, `product list returned rows (${JSON.stringify(r.body).slice(0, 140)})`)
      .to.be.an('array')
    return r.body.collection.filter((p) => String(p.name || '').indexOf(prefix) === 0).length
  })

/**
 * Open the Purchase screen and a fresh bill — EXACTLY the house pattern, nothing added.
 *
 * ⚠ The first version appended cy.waitForAppReady() + cy.settled() here, borrowed from the PRODUCT form's
 * documented sequence. That is what hung five cases for 30 s apiece ("the app never went quiet"). The purchase
 * form is not the product form: purchase-rapid-entry.cy.js opens it with these three lines and nothing else,
 * and it walks the whole Enter chain afterwards without trouble — which is what proved the screen was fine and
 * the extra waits were mine.
 *
 * The lesson is narrower than "don't wait": a settle sequence is a property of the SCREEN it was measured on.
 * The product modal earns one (its pickers rebuild and the dialog slides); this one does not.
 */
const openPurchase = () => {
  cy.openPurchaseSection('purchaseDiv')
  cy.get('#newPurchase').click()
  cy.get('#PurchaseModal', { timeout: 20000 }).should('have.class', 'open')
  // Position only — the pickers upgrade after the modal opens and slide it. NOT waitForAppReady; see above.
  cy.settled('#purchaseInvoiceNo')
}

/** The product form, settled, once it has been opened from somewhere. */
const settleProductForm = () => {
  cy.waitForAppReady()
  cy.get('#prodName').should('be.visible')
  cy.settled('#prodName')
}

/** A bill header we can prove survived the round trip. */
const HEADER = { invoice: 'PUR-INLINE-', date: null }

describe('PUR-INLINE — register a product from the bill', () => {
  beforeEach(() => {
    // testIsolation: re-login each test, because the cy.requests below are authenticated.
    // No cy.visit here — openPurchaseSection() visits, and case 10 visits for itself.
    cy.loginAsBusiness()
  })

  it('⭐ 1 — the button opens the REAL product form, and the bill header survives', () => {
    /*
     * ⚠ ASSERTS THE FORM IS THE REAL ONE, not merely that "a form opened". The rejected design was a
     * four-field panel, and a gate that only checked for a name box would pass against it — so this looks for
     * #prodPackSize, a pack/loose field no cut-down panel would carry. That is the decision being pinned.
     */
    const inv = HEADER.invoice + uniq()

    openPurchase()
    cy.get('#purchaseInvoiceNo').clear().type(inv)

    cy.get('#newProductFromPurchase').should('be.visible').click()
    cy.get('#ProductModal').should('have.class', 'open')
    cy.get('#PurchaseModal').should('have.class', 'open')        // the bill is still there, underneath

    settleProductForm()
    cy.get('#prodPackSize').should('exist')                      // the REAL form, not a reduced copy
    cy.get('#prodTaxCode').should('exist')

    // And the header the operator typed is untouched behind it.
    cy.get('#purchaseInvoiceNo').should('have.value', inv)
  })

  it('⭐⭐ 2 — registering returns to the bill with the product selected and Quantity focused', () => {
    const name = `PInline_${uniq()}`
    const inv = HEADER.invoice + uniq()

    openPurchase()
    cy.get('#purchaseInvoiceNo').clear().type(inv)
    cy.get('#newProductFromPurchase').click()
    settleProductForm()

    cy.get('#prodName').clear().type(name)
    cy.get('#prodPrice').clear().type('250')
    cy.get('#addProduct').click()

    // Back on the bill: product form gone, bill still open, product chosen.
    cy.get('#ProductModal', { timeout: 15000 }).should('not.have.class', 'open')
    cy.get('#PurchaseModal').should('have.class', 'open')
    cy.get('#purchaseInvoiceNo').should('have.value', inv)       // nothing about the bill was rebuilt

    cy.get('#purchaseItemDD option:selected').should('contain.text', name)

    // The cursor is where the operator was heading. Without this the feature still "works" and still makes
    // the operator reach for the mouse, which is the friction the slice is about.
    cy.focused().should('have.id', 'purchaseQuantity')
  })

  it('⭐ 3 — the appended option carries data-product, the id the save actually submits', () => {
    /*
     * main.js:699 reads `$("#purchaseItemDD :selected").data('product')` and APPENDS it to the form string.
     * An option without it submits productId=null — a purchase that reports success, is skipped by
     * getUserPurchase and never stocks anything. That exact defect has been paid for here before, so the
     * attribute is asserted rather than assumed.
     */
    const name = `PAttr_${uniq()}`

    openPurchase()
    cy.get('#newProductFromPurchase').click()
    settleProductForm()
    cy.get('#prodName').clear().type(name)
    cy.get('#prodPrice').clear().type('99')
    cy.get('#addProduct').click()

    cy.get('#ProductModal', { timeout: 15000 }).should('not.have.class', 'open')
    cy.get('#purchaseItemDD option:selected').then(($o) => {
      const pid = $o.attr('data-product')
      expect(pid, 'the option carries data-product').to.match(/^\d+$/)
      expect($o.val(), 'and it agrees with the option value').to.eq(pid)
    })
  })

  it('⭐ 4 — "Stock In Hand" fills, proving the change event fired', () => {
    /*
     * Setting .val() programmatically fires nothing, and `.onChangeSelect` in main.js is what calls loadStock.
     * Without an explicit trigger the line looks chosen while the stock box beside it stays blank — a
     * half-filled form an operator reasonably distrusts, and a silent difference between a registered product
     * and a picked one.
     */
    const name = `PStock_${uniq()}`

    openPurchase()
    cy.get('#newProductFromPurchase').click()
    settleProductForm()
    cy.get('#prodName').clear().type(name)
    cy.get('#prodPrice').clear().type('75')
    cy.get('#addProduct').click()

    cy.get('#ProductModal', { timeout: 15000 }).should('not.have.class', 'open')
    // A brand-new product has no stock, so the honest answer is "0" — what must NOT happen is an empty box,
    // which is what a missing change event looks like.
    cy.get('#purchaseStock', { timeout: 15000 }).should(($el) => {
      expect(String($el.val()), 'stock in hand was computed, not left blank').to.not.eq('')
    })
  })

  it('⭐⭐ 5 — Escape closes ONLY the product form; the bill stays open', () => {
    /*
     * THE TRAP THAT MADE STACKING UNSAFE. Both Enter-chains were active at once, `onEscape` calls
     * preventDefault but never stopPropagation, and each EnterChain.bind() adds its own document listener —
     * so one Escape ran BOTH handlers and closed the product form AND the bill behind it. Fixed with
     * isTopModal(): a chain is active only while its modal is the top-most open overlay.
     */
    const inv = HEADER.invoice + uniq()

    openPurchase()
    cy.get('#purchaseInvoiceNo').clear().type(inv)
    cy.get('#newProductFromPurchase').click()
    settleProductForm()
    cy.get('#prodName').clear().type('Abandoned_' + uniq())

    cy.get('body').type('{esc}')

    cy.get('#ProductModal').should('not.have.class', 'open')
    cy.get('#PurchaseModal').should('have.class', 'open')        // ⭐ the bill survived
    cy.get('#purchaseInvoiceNo').should('have.value', inv)

    // And the keyboard belongs to the bill again: a second Escape closes IT, which is the normal rule.
    cy.get('body').type('{esc}')
    cy.get('#PurchaseModal').should('not.have.class', 'open')
  })

  it('⭐⭐ 6 — registering a product does not knock a purchase line out of edit mode', () => {
    /*
     * ⚠ THE SHARPEST EDGE, AND AN INVISIBLE ONE. Saving a product calls loadDataTable(), which sets
     * `edit = false` (business.js:1901) — a flag SHARED by every screen. With a purchase line open for editing
     * behind the modal, that silently turns the edit into an add: the operator corrects a line, saves, and gets
     * a SECOND line at the new quantity while the original stands. Stock and payables both drift.
     *
     * The return path skips loadDataTable() for exactly this reason. Asserted through the flag itself, because
     * there is nothing on screen that would show it.
     */
    openPurchase()
    cy.window().then((w) => { w.edit = true })                   // stand in for "a line is being edited"

    cy.get('#newProductFromPurchase').click()
    settleProductForm()
    cy.get('#prodName').clear().type(`PEdit_${uniq()}`)
    cy.get('#prodPrice').clear().type('10')
    cy.get('#addProduct').click()

    cy.get('#ProductModal', { timeout: 15000 }).should('not.have.class', 'open')
    cy.window().should((w) => {
      expect(w.edit, 'the shared edit flag was NOT reset behind the operator').to.eq(true)
    })
  })

  it('⭐ 7 — the whole journey registers ONE product (DUP-1 still holds here)', () => {
    const name = `POnce_${uniq()}`

    openPurchase()
    cy.get('#newProductFromPurchase').click()
    settleProductForm()
    cy.get('#prodName').clear().type(name)
    cy.get('#prodPrice').clear().type('60')

    // Two real presses, as an impatient operator produces. DUP-1's coalescing plus the server's idempotency
    // key must still collapse them on THIS path, where the success handler now takes a different branch.
    cy.get('#addProduct').click()
    cy.get('#addProduct').click({ force: true })                 // the modal may already be closing

    cy.wait(1500)
    countNamed(name).should('eq', 1)
  })

  it('⭐ 8 — the button is shown EXACTLY when the server would allow the POST', () => {
    /*
     * THE INVARIANT, not a fixture assumption — and the first version of this case got that wrong.
     *
     * It asserted "a member holding product.create sees the button", reasoning that V12 put every existing
     * member on the built-in Standard set, which includes product.create. True of the migration, false of this
     * database: user.business@ sits on permission set 16 while every other plain member is on set 1, left there
     * by an earlier permission-sets run. The case failed against a gate that was working perfectly.
     *
     * So ask the SERVER what this caller may do, then require the UI to agree. That is the property worth
     * pinning anyway: an affordance shown to someone the interceptor will refuse is a button that always fails,
     * and an affordance hidden from someone allowed is a feature they cannot reach.
     *
     * The probe posts a product with NO NAME, deliberately: PermissionInterceptor runs before the controller,
     * so a refusal is 403 while a permitted caller falls through to validation and is refused for a different
     * reason. Either way nothing is created, so the case leaves no rows behind.
     */
    const agree = (label) => {
      let allowed = null
      cy.request({
        method: 'POST', url: '/addProduct', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' }, body: {},
      }).then((r) => {
        allowed = r.status !== 403
        cy.log(`${label}: server ${allowed ? 'ALLOWS' : 'refuses'} product creation (HTTP ${r.status})`)
      })

      cy.request({ url: '/businessDashboard', failOnStatusCode: false }).then((r) => {
        const shown = String(r.body || '').indexOf('id="newProductFromPurchase"') >= 0
        expect(shown, `${label}: the button is shown iff the POST is allowed (allowed=${allowed})`).to.eq(allowed)
      })
    }

    // The owner holds everything (AuthService mints everything() for ROLE_OWNER), so this half also proves
    // the markup renders at all — a gate that hid it from everyone would pass a one-sided check.
    cy.loginAsOwner()
    cy.then(() => agree('owner'))

    cy.loginAsTier('user', 'business')
    cy.then(() => agree('plain member'))
  })

  it('9 — on a line being edited the button refuses, and says why', () => {
    /*
     * EDIT mode disables the item picker (main.js:1483) because the line's product is fixed. Registering a
     * product to select into a disabled picker would either do nothing or silently re-point the line, so it is
     * refused with a sentence instead of failing quietly.
     */
    openPurchase()
    cy.window().then((w) => { w.$('#purchaseItemDD').prop('disabled', true) })

    cy.get('#newProductFromPurchase').click()
    cy.get('#ProductModal').should('not.have.class', 'open')     // nothing opened
    cy.get('#formErrorToast', { timeout: 10000 }).should('be.visible')
      .invoke('text').then((t) => expect(t.toLowerCase()).to.contain('cancel'))

    // Leave no state behind: the picker goes back to how the form renders it.
    cy.window().then((w) => { w.$('#purchaseItemDD').prop('disabled', false) })
  })

  it('⭐ 10 — opened from the Products screen, the product form behaves exactly as before', () => {
    /*
     * THE REGRESSION THIS SLICE COULD CAUSE ELSEWHERE. The return journey is driven by a callback stored when
     * the form is opened; if it were ever left set, a product registered from the Products screen would jump
     * to the purchase picker, and the Products grid would stop refreshing after a save.
     *
     * So: open it the ordinary way and require the ordinary behaviour — closes, no purchase form involved, and
     * the new product appears in the grid it was saved from.
     */
    const name = `PNormal_${uniq()}`

    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showProducts')
    cy.window().then((w) => w.showProducts())
    cy.get('#ProductDiv').should('be.visible')
    cy.window().then((w) => w.newProduct())
    cy.get('#ProductModal').should('have.class', 'open')
    settleProductForm()

    cy.get('#prodName').clear().type(name)
    cy.get('#prodPrice').clear().type('30')
    cy.get('#addProduct').click()

    cy.get('#ProductModal', { timeout: 15000 }).should('not.have.class', 'open')
    cy.get('#PurchaseModal').should('not.have.class', 'open')    // the bill was never involved
    countNamed(name).should('eq', 1)
  })
})
