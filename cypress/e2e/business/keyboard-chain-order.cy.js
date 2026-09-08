/**
 * The keyboard chains obey the two rules, and a person cannot forget to check.
 *
 * Reference: microservices/docs/keyboard-first-reference.md
 * Manual walk: MyPlus Test Book §11 "Working without a mouse"
 *
 * ── WHY THIS SPEC EXISTS ────────────────────────────────────────────────────────────────────────
 * Three keyboard defects shipped in one week, and every one was a rule that read correctly in the
 * source while behaving wrongly on screen:
 *
 *   1. `sellSerials` was moved in front of QTY on screen and left out of the chain — a field the
 *      server REQUIRES that a keyboard-only cashier could not reach at all.
 *   2. Skipping an empty customer jumped to the Received box, past the whole goods phase, on an
 *      empty cart. Correct when written (the customer used to live in checkout), never revisited.
 *   3. The checkout chain claimed in its own comment to follow the form's field order and did not:
 *      Enter left the payment method, jumped past Received to a discount box further down the page,
 *      then back up.
 *
 * None of them was catchable by reading. Each needed either a person pushing the mouse away, or this.
 *
 * ── THE TWO RULES ───────────────────────────────────────────────────────────────────────────────
 *   RULE 1  THE CHAIN ORDER IS THE SCREEN ORDER. One thing, not two kept in step.
 *   RULE 2  A NAMED FIELD IS A PREFERENCE. Check it, and move to the next available one — never
 *           focus nothing, and never name a field that is not there.
 *
 * ── ⚠ THIS IS A STATIC AUDIT, NOT A WALK ────────────────────────────────────────────────────────
 * It reads the shipped chain arrays out of the running page and compares them with the DOM. It does
 * NOT press Enter — the behaviour is section 11 of the Test Book, walked by hand. What it does catch
 * is the whole class of defect above: a chain that disagrees with the screen, and a chain that names
 * a field nobody can see. Those are structural, so they can be proven rather than observed.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/keyboard-chain-order.cy.js --headed --no-exit
 */

/** Read a module-private array out of the page by re-parsing the shipped source. */
const chainFrom = (src, name) => {
  const m = new RegExp('var ' + name + ' = \\[([\\s\\S]*?)\\];').exec(src)
  expect(m, `${name} is declared in pos-keyboard.js`).to.not.eq(null)
  // Ids only: strip the comments that document each entry.
  return (m[1].replace(/\/\/[^\n]*/g, '').match(/'([\w]+)'/g) || [])
    .map((s) => s.replace(/'/g, ''))
}

describe('Keyboard chains follow the screen', () => {
  let src = null

  before(() => {
    cy.loginAsMobileOwner()
    // The SHIPPED file, not a copy of it in this spec. A test that restated the chain would pass
    // against its own restatement and prove nothing about what the till loads.
    cy.request('/js/business/pos-keyboard.js').then((r) => { src = r.body })
  })

  beforeEach(() => {
    cy.loginAsMobileOwner()
    cy.visitSaleScreen()
  })

  /** Where each id sits in the document, or null when it is not rendered at all. */
  const domOrder = (ids) =>
    cy.document().then((doc) => {
      const all = Array.from(doc.querySelectorAll('[id]')).map((e) => e.id)
      return ids.map((id) => ({ id, at: all.indexOf(id) }))
    })

  it('⭐ 1 — RULE 1: the sale line chain is in screen order', () => {
    const chain = chainFrom(src, 'CHAIN')
    expect(chain, 'the chain was parsed').to.include('sellItemDD')

    domOrder(chain).then((rows) => {
      const present = rows.filter((r) => r.at >= 0)
      const sorted = present.slice().sort((a, b) => a.at - b.at)
      expect(present.map((r) => r.id).join(' -> '),
        'the chain walks the fields in the order they appear on screen')
        .to.eq(sorted.map((r) => r.id).join(' -> '))
    })
  })

  it('⭐ 2 — RULE 1: the checkout chain is in screen order', () => {
    /*
     * The one that was actually wrong: on screen the order is payMethod, store credit, Received,
     * trade discount, due date — and the chain listed trade discount and store credit BEFORE
     * Received, so Enter jumped down the page and back up.
     */
    const chain = chainFrom(src, 'CHECKOUT')
    expect(chain, 'the chain was parsed').to.include('sellRec')

    domOrder(chain).then((rows) => {
      const present = rows.filter((r) => r.at >= 0)
      const sorted = present.slice().sort((a, b) => a.at - b.at)
      expect(present.map((r) => r.id).join(' -> '),
        'the checkout walks the fields in the order they appear on screen')
        .to.eq(sorted.map((r) => r.id).join(' -> '))
    })
  })

  it('⭐ 3 — RULE 2: no chain names a field that is not on the page', () => {
    /*
     * `sellInsured` sat in the checkout chain for weeks after the field was REMOVED from the screen
     * (P12, slice 59). usable() made it harmless — a missing element is never focusable — but a
     * chain that names a field nobody can see is a chain nobody can read, and the audit that found
     * it had to prove an absence rather than see one.
     *
     * ⚠ Asserted against the page with EVERY optional field switched on, so a field this tenant
     * merely hid is not mistaken for one that does not exist.
     */
    const all = [].concat(chainFrom(src, 'CHAIN'), chainFrom(src, 'CHECKOUT'),
                          chainFrom(src, 'CHECKOUT_LANDING'), chainFrom(src, 'PICKERS'))
    const unique = Array.from(new Set(all))

    domOrder(unique).then((rows) => {
      const ghosts = rows.filter((r) => r.at < 0).map((r) => r.id)
      expect(ghosts.join(', '),
        'every id named in a chain exists in the DOM — a ghost entry is a rule nobody can read')
        .to.eq('')
    })
  })

  it('⭐ 4 — RULE 2: every landing preference and tender target is a real checkout field', () => {
    // A landing or a routing rule that pointed outside the chain would strand the cursor at exactly
    // the moment the sale is being finished.
    const checkout = chainFrom(src, 'CHECKOUT')
    const landing = chainFrom(src, 'CHECKOUT_LANDING')
    landing.forEach((id) => {
      expect(checkout, `landing preference ${id} belongs to the checkout chain`).to.include(id)
    })

    const after = (/var AFTER_METHOD = \{([\s\S]*?)\};/.exec(src) || [])[1] || ''
    const targets = (after.match(/'([\w]+)'/g) || []).map((s) => s.replace(/'/g, ''))
    expect(targets.length, 'the tender routing table was parsed').to.be.greaterThan(0)
    targets.forEach((id) => {
      expect(checkout, `tender target ${id} belongs to the checkout chain`).to.include(id)
    })
  })

  it('⭐ 5 — RULE 2: every picker belongs to a chain', () => {
    // A picker outside both chains has no keyboard way out of it — the dead end that shipped twice,
    // once on the discount type and again one field along on the payment method.
    const chain = chainFrom(src, 'CHAIN')
    const checkout = chainFrom(src, 'CHECKOUT')
    chainFrom(src, 'PICKERS').forEach((id) => {
      expect(chain.concat(checkout), `picker ${id} is in a chain, so Enter can leave it`).to.include(id)
    })
  })

  it('6 — the serial box is in the line chain, between the item and the quantity', () => {
    /*
     * The specific defect, kept as its own case rather than left to rule 1: the serial is REQUIRED by
     * the server for a serial-tracked product, so its absence from the chain was not a nuisance but a
     * dead end — and it must sit BEFORE the quantity, because entering a serial locks the quantity
     * to 1. After it, the cashier would type a number the next field overwrites.
     */
    const chain = chainFrom(src, 'CHAIN')
    expect(chain, 'the serial box is reachable by keyboard').to.include('sellSerials')
    expect(chain.indexOf('sellSerials'), 'and it comes after the item picker')
      .to.be.greaterThan(chain.indexOf('sellItemDD'))
    expect(chain.indexOf('sellSerials'), 'and before the quantity it locks')
      .to.be.lessThan(chain.indexOf('sellQuantity'))
  })

  // ── ⭐⭐ 7. the direction the other six could not see ───────────────────────────────────────────

  it('⭐⭐ 7 — RULE 1: every field the cashier can TYPE INTO is in the line chain', () => {
    /*
     * THE CASE THIS FILE WAS MISSING, and the defect that proved it.
     *
     * #sellBonus sits between Qty and Price on screen and was absent from CHAIN entirely, so a
     * distributor with free-goods on watched Enter jump the quantity straight to the price, past a
     * field they then had to reach for the mouse to fill. Reported from the counter.
     *
     * All six cases above were green throughout, and none of them could have caught it:
     *   • 1 and 2 check that the fields IN a chain appear in screen order — a field that is not in the
     *     chain is not in the comparison at all;
     *   • 3 checks that no chain names a field missing from the PAGE — the opposite direction;
     *   • 4, 5 and 6 check named preferences and one specific field.
     *
     * Order without completeness is half a rule: a chain can be perfectly ordered and still leave a
     * field unreachable from the keyboard, which is the one thing this module exists to prevent.
     *
     * ⚠ ASKED OF THE APP, NOT RESTATED HERE. The check uses the page's own FocusFlow.skip — the single
     * definition of "is this worth putting a cursor in?" that EnterChain.usable() calls at runtime. A
     * list of expected ids in this spec would be a second opinion that drifts, and a hand-rolled
     * "visible and editable" test here would disagree with the engine on some field nobody thought to
     * check. Readonly display badges (sellStock, bexpDate, sellTotalAmount) are excluded by that same
     * function, which is exactly why they must NOT be in the chain.
     *
     * ⚠ SCOPED TO .pos-cell, THE LINE STRIP - not to <form id="Sell">.
     *
     * The form is the wrong boundary in both directions. It does NOT contain the customer controls
     * (the markup says so: "STILL OUTSIDE <form id='Sell'>, deliberately" - they are serialised into a
     * cart LINE and a customer is not a line), and it DOES contain things the line chain has no
     * business walking: the scan box, which focusGoodsEntry() targets directly and which
     * pos-keyboard.js says is "deliberately not in it", and the installment panel.
     *
     * `.pos-cell` is precisely the set of fields the line chain exists to walk - one cell per field,
     * caption and input together - so scoping here needs no allow-list of exceptions, and an allow-list
     * is the thing that would quietly grow until it covered the next real defect.
     *
     * The customer half of CHAIN is covered by cases 1 and 3; the checkout by case 2.
     */
    const chain = chainFrom(src, 'CHAIN')

    cy.window().should((w) => {
      expect(w.FocusFlow, 'focus-flow.js is loaded').to.be.an('object')
    })

    cy.window().then((w) => {
      const cells = w.document.querySelectorAll('#sellDiv .pos-cell')
      expect(cells.length, 'the line strip is on the page').to.be.greaterThan(4)

      const typeable = Array.from(cells)
        .flatMap((c) => Array.from(c.querySelectorAll('input, select, textarea')))
        .filter((el) => el.id)                 // an id is how a chain names a field
        .filter((el) => !w.FocusFlow.skip(el)) // the app's own answer, never a copy of it

      const missing = typeable.map((el) => el.id).filter((id) => chain.indexOf(id) < 0)

      expect(missing.join(', '),
        'every typeable field on the sale line is reachable by keyboard — a field on screen and absent '
        + 'from CHAIN is unreachable, and looks from the outside exactly like a deliberate skip')
        .to.eq('')
    })
  })
})
