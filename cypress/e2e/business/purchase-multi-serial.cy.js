/**
 * SER-7 — many serials on one purchase line: comma, space, or new line.
 *
 * ── Why this reverses SER-3c, and what stops it reverting the pain with it ──────────────────────
 * The box began as a textarea holding one IMEI per line. The server refuses a purchase whose serial COUNT
 * differs from its QUANTITY — rightly: three handsets with two IMEIs means one unit nobody can identify.
 * But the operator kept those two numbers in step by hand and found out at submit if they had not, so
 * SER-3c made a line one unit: enter a serial, the quantity locks to 1, ten handsets are ten lines.
 *
 * Correct, and slow — ten trips through the form for a carton that arrived as one carton.
 *
 * ⭐ SER-7 brings the box back and removes the miscount instead of re-accepting it: **the quantity is
 * DERIVED from the number of serials**, live and read-only. The two cannot disagree, so the server's count
 * rule stops being something a shop trips over and goes back to being a backstop against a malformed
 * request. That derivation is what cases 1–3 exist to prove; without it this slice would just be SER-3c's
 * problem again.
 */

const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)

/** A product the CATALOG says is serial-tracked — the policy the server actually reads. */
const seedTracked = () => {
  const run = uniq()
  return cy.seedProduct({ name: `SER7_${run}`, sellingPrice: 50000, stock: 0 })
    .then(({ productId }) =>
      cy.request({
        method: 'POST', url: '/setProductTracking', form: true,
        body: { id: productId, requiresSerial: 'true' }, failOnStatusCode: false,
      }).then((r) => {
        // SEED, never assume: on an unflagged product the count rule never fires and every case is vacuous.
        expect(JSON.stringify(r.body), `product ${productId} is serial-tracked`).to.not.match(/error/i)
        return cy.wrap({ productId, run })
      }))
}

/**
 * The purchase form is a MODAL reached through the nav, not a panel on a select — the same four steps
 * serial-register-fixes.cy.js uses. Copied rather than re-invented: an opener that half-works produces
 * failures about the field under test.
 */
const openPurchase = () => {
  cy.visit('/businessDashboard')
  cy.get('#snavPurchase .snav-btn', { timeout: 15000 }).click({ force: true })
  cy.contains('#snavPurchase a', /new purchase/i).click({ force: true })
  cy.get('#newPurchase', { timeout: 15000 }).click({ force: true })
  cy.get('#PurchaseModal', { timeout: 15000 }).should('have.class', 'open')
  cy.get('#purchaseSerials', { timeout: 20000 }).should('be.visible')
}

/**
 * Type into the serial box and let the input handler run.
 *
 * ⚠ parseSpecialCharSequences is left ON (the default), because {enter} MUST become a real newline in the
 * textarea — turning it off types the eight literal characters and cases 3 and 4 would assert against a
 * separator the box never received. No serial here contains a brace, so nothing else is reinterpreted.
 */
const typeSerials = (text) =>
  cy.get('#purchaseSerials').clear({ force: true }).type(text, { force: true })

describe('SER-7 — several serials on one purchase line', () => {
  beforeEach(() => {
    // The mobile shop: the vertical that actually has serial tracking, so the row is rendered.
    cy.loginAsMobileOwner()
    /*
     * ⚠ ENABLE THE CAPABILITY THIS SPEC NEEDS — it used to inherit it from whatever ran first.
     *
     * The serial box lives in a [data-capability="serialTracking"] group, and org 44's shape is RETAIL,
     * whose preset does NOT include serialTracking. So the row renders only while an explicit override says
     * so. serial-register.cy.js sets that override; this spec did not, and simply relied on having run
     * after it.
     *
     * capability-fields.cy.js then made that dependency bite: it deliberately restores both tenants to
     * "seeded shape, no overrides" in its after(), and alphabetically it runs before every purchase-* spec.
     * All 8 cases here failed with the textarea "not visible because its parent has display:none" — the
     * cap-off class — which reads like a UI defect and is a missing precondition. Worse, whether this spec
     * passed depended on run ORDER, so a green run proved nothing.
     *
     * Set here rather than in a before(): testIsolation clears the browser session between cases, and the
     * capability write is cheap and idempotent.
     */
    cy.setCapability('serialTracking', true)
  })

  it('⭐ 1 — COMMA separated: three IMEIs make a quantity of three', () => {
    const run = uniq()
    openPurchase()
    typeSerials(`IMEI${run}A,IMEI${run}B,IMEI${run}C`)
    // ⭐ The derivation, which is the whole slice. It read 1 before SER-7, for any non-empty box.
    cy.get('#purchaseQuantity').should('have.value', '3').and('have.attr', 'readonly')
  })

  it('⭐ 2 — SPACE separated', () => {
    /*
     * The separator the server did NOT accept before this slice: split() was `[\r\n,]+`, so
     * "IMEI1 IMEI2 IMEI3" arrived as ONE serial 47 characters long — either refused for length or, worse,
     * registered as a unit under a serial nobody could ever search for.
     */
    const run = uniq()
    openPurchase()
    typeSerials(`IMEI${run}A IMEI${run}B`)
    cy.get('#purchaseQuantity').should('have.value', '2')
  })

  it('⭐ 3 — NEW LINE separated, which an <input> could not even hold', () => {
    const run = uniq()
    openPurchase()
    typeSerials(`IMEI${run}A{enter}IMEI${run}B{enter}IMEI${run}C`)
    cy.get('#purchaseQuantity').should('have.value', '3')
  })

  it('4 — mixed separators, and stray spacing, still count correctly', () => {
    // A real paste from a supplier's list is rarely tidy.
    const run = uniq()
    openPurchase()
    typeSerials(`  IMEI${run}A ,  IMEI${run}B{enter}  IMEI${run}C ,,  `)
    cy.get('#purchaseQuantity').should('have.value', '3')
  })

  it('⭐⭐ 5 — a duplicate is named WHILE TYPING, not at submit', () => {
    /*
     * ⭐ The original objection to the multi box was that a mistake "was found only at submit, after
     * everything had been typed". Working down a carton, scanning the same handset twice is the likeliest
     * error on this screen, and the server can only answer it by refusing the whole receipt.
     */
    const run = uniq()
    openPurchase()
    typeSerials(`IMEI${run}A,IMEI${run}B,IMEI${run}A`)
    cy.get('#purchaseSerialHint')
      .should('have.class', 'text-danger')
      .and('contain.text', `IMEI${run}A`)
  })

  it('6 — clearing the box releases the quantity and clears the hint', () => {
    // The lock owns the hint, so every path that clears the line (P6 Save & Add Another, editRecord)
    // clears both. A stale "3 serials" under an empty box is exactly what this guards.
    const run = uniq()
    openPurchase()
    typeSerials(`IMEI${run}A,IMEI${run}B`)
    cy.get('#purchaseQuantity').should('have.value', '2')
    cy.get('#purchaseSerials').clear({ force: true })
    cy.get('#purchaseQuantity').should('not.have.attr', 'readonly')
    cy.get('#purchaseSerialHint').should('have.text', '')
  })

  it('⭐ 7 — the SERVER accepts all three separators, posted directly', () => {
    /*
     * The screen is one client. This is the contract underneath it: a rule that lives only in JavaScript is
     * a rule until somebody posts the endpoint. Each shape must register exactly as many units as it names.
     */
    seedTracked().then(({ productId }) => {
      const run = uniq()
      const cases = [
        { label: 'comma', serials: `C${run}A,C${run}B`, qty: 2 },
        { label: 'space', serials: `S${run}A S${run}B`, qty: 2 },
        { label: 'newline', serials: `N${run}A\nN${run}B`, qty: 2 },
      ]
      cases.forEach((c) => {
        // ⚠ The parameter names the endpoint actually binds — `stock.bpurchaseRate`, not `bpurchaseRate`.
        // Taken from serial-register.cy.js, which posts this endpoint successfully.
        cy.request({
          method: 'POST', url: '/addPurchase', form: true,
          body: {
            productId, quantity: c.qty,
            purchaseRate: 100, 'stock.bpurchaseRate': 100, 'stock.bsellRate': 150,
            totalAmount: 100 * c.qty, netAmount: 100 * c.qty, paidAmount: 100 * c.qty,
            purchaseInvoiceNo: 'SER7-' + uniq(),
            serials: c.serials, serialsSubmitted: 'true', conditionGrade: 'NEW',
          },
          failOnStatusCode: false,
        }).then((r) => {
          expect(String(r.body.status), `${c.label}-separated is accepted: ${JSON.stringify(r.body)}`)
            .to.not.eq('ERROR')
        })
      })
    })
  })

  it('⭐ 8 — the count rule still bites when the two genuinely disagree', () => {
    /*
     * The backstop must survive. Deriving the quantity on screen means a cashier cannot trip this, but a
     * malformed request still must — otherwise a unit goes unaccounted for and the register is silently short.
     */
    seedTracked().then(({ productId }) => {
      const run = uniq()
      cy.request({
        method: 'POST', url: '/addPurchase', form: true,
        body: {
          productId, quantity: 3,
          purchaseRate: 100, 'stock.bpurchaseRate': 100, 'stock.bsellRate': 150,
          totalAmount: 300, netAmount: 300, paidAmount: 300,
          purchaseInvoiceNo: 'SER7-' + uniq(),
          serials: `X${run}A X${run}B`, serialsSubmitted: 'true', conditionGrade: 'NEW',
        },
        failOnStatusCode: false,
      }).then((r) => {
        expect(String(r.body.status), `two IMEIs for three units must be REFUSED: ${JSON.stringify(r.body)}`)
          .to.eq('ERROR')
        expect(String(r.body.message)).to.match(/serial|unit/i)
      })
    })
  })
})
