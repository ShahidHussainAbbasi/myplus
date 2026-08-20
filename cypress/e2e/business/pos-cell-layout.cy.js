/**
 * UI/UX — the sale line as a TABLE: one `.pos-cell` per field, caption above input.
 *
 * <h3>What changed, and what deliberately did not</h3>
 * Each label and its field were siblings held beside each other by negative margins — the CSS said so
 * itself: <i>"Flex has no 'keep these two adjacent' primitive."</i> They are now wrapped in a single
 * `.pos-cell`, and `data-pos-field` moved from BOTH the label and the column onto that one element.
 *
 * <b>No id, no name, no type, and no DOM ORDER changed.</b> That last one is the contract: the Enter chain
 * is resolved from ids, and `FocusFlow.fields()` sorts by document order. So the tests below assert the
 * chain is untouched as firmly as they assert the layout is new — a redesign of the till that quietly
 * re-sequenced the keyboard would be a worse outcome than the layout it replaced.
 *
 * <h3>The two properties worth having a test for</h3>
 * <ol>
 *   <li><b>The captions form one band.</b> That is the whole visual claim, and it is measurable: every
 *       visible caption in the strip shares one {@code offsetTop}. The old layout only looked aligned
 *       while every label happened to be one line of similar width.</li>
 *   <li><b>Hiding a field takes its caption with it, and removes it from the keyboard.</b> A tenant
 *       switching fields off is the normal state of nearly every installation, not an edge case.</li>
 * </ol>
 *
 * Run headed.
 */

function openTill() {
  cy.visit('/businessDashboard')
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellDiv').should('be.visible')
  cy.window().should((w) => {
    expect(w.posGoToCheckout, 'pos-keyboard.js loaded').to.be.a('function')
  })
  cy.window().then((w) => {
    w.posKeyboardEnabled = true
    w.posShortcutsEnabled = true
    w.applyPosKeyboard()
    // The row layout is its OWN flag, not a consequence of the keyboard one — applyPosRowEntry() reads
    // posRowLayoutEnabled and nothing else. Two separate tenant settings, deliberately: a shop can want
    // keyboard-driven entry on today's stacked form, or the compact row without the shortcuts.
    w.posRowLayoutEnabled = true
    w.applyPosRowEntry()
  })
  // The class lands on #sellDiv — that is the whole mechanism, one class on one element.
  cy.get('#sellDiv').should('have.class', 'pos-rowentry')
}

/** Apply a tenant field configuration the way the settings screen does. */
function setFields(cfg) {
  cy.window().then((w) => {
    w.posFields = cfg
    w.applyPosFieldVisibility()
  })
}

describe('POS line entry — one cell per field', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  // ── structure ───────────────────────────────────────────────────────────────────────────────────

  it('every entry field sits in a cell that also holds its own caption', () => {
    openTill()
    cy.get('#sellDiv .pos-cell').should('have.length.at.least', 8)

    // The property the negative-margin layout could not guarantee: caption and field are ONE element's
    // children, so no CSS rule and no edit can separate them.
    ;['sellItemDD', 'sellItems', 'sellSellRate', 'sellDiscount', 'sellDiscountTypeDD',
      'sellTotalAmount', 'sellStock', 'bexpDate'].forEach((id) => {
      cy.get('#' + id).closest('.pos-cell').as('cell')
      cy.get('@cell').should('have.length', 1)
      cy.get('@cell').find('label.control-label').should('have.length', 1)
    })
  })

  it('the two fields that had no label at all now have one', () => {
    // bexpDate and sellDiscountTypeDD were unlabelled — under a caption band they would have sat beneath
    // blank cells, and an unlabelled input was its own usability bug before that.
    openTill()
    cy.get('#bexpDate').closest('.pos-cell').find('label').should('not.be.empty')
    cy.get('#sellDiscountTypeDD').closest('.pos-cell').find('label').should('not.be.empty')
  })

  it('data-pos-field is on the CELL, never left behind on a label or a column', () => {
    openTill()
    // Scoped to the FORM, not the screen. The checkout area below carries its own hooks —
    // tradeDiscount, customerBalance, park — which are whole blocks rather than line-entry fields and
    // are legitimately not cells. This is about the entry line.
    cy.get('#Sell [data-pos-field]').each(($el) => {
      cy.wrap($el).should('have.class', 'pos-cell')
    })
    // The old shape, explicitly absent.
    cy.get('#Sell label[data-pos-field]').should('not.exist')
    cy.get('#Sell div[class*="col-sm-"][data-pos-field]').should('not.exist')
  })

  // ── the visual claim, measured ──────────────────────────────────────────────────────────────────

  it('THE LAYOUT — every visible caption sits on one band', () => {
    openTill()
    setFields({})                                   // nothing switched off: the widest strip there is

    cy.get('#Sell .pos-cell:visible').then(($cells) => {
      const top = (el) => Math.round(el.getBoundingClientRect().top)
      const capBands = new Set(), fieldBands = new Set()
      ;[...$cells].forEach((c) => {
        const lab = c.querySelector('label.control-label')
        // The VISIBLE control. A `selectpicker` hides its real <select> behind a generated
        // .bootstrap-select button, and a hidden element reports top:0 — which showed up as a phantom
        // band at zero and made the Item cell look misaligned when it was not. Measure what the cashier
        // actually sees, which is the button.
        const fld = c.querySelector('.bootstrap-select') || c.querySelector('input, select')
        if (lab) capBands.add(top(lab))
        if (fld) fieldBands.add(top(fld))
      })
      /*
       * The property, stated so it does not depend on how many cells happen to fit the viewport: the
       * captions form exactly as many horizontal bands as the FIELDS do. A strip that wraps onto three
       * lines has three caption bands and three field bands — still a table, just a taller one.
       *
       * A ragged layout fails this immediately, because a caption sitting at its own height adds a band
       * the fields do not have. That is precisely what the old negative-margin row did as soon as one
       * label was longer than its neighbours.
       */
      expect([...capBands].length,
        'caption bands ' + JSON.stringify([...capBands]) + ' vs field bands ' + JSON.stringify([...fieldBands]))
        .to.eq([...fieldBands].length)
      expect(capBands.size, 'there is at least one band to speak of').to.be.at.least(1)
    })
  })

  /**
   * IS IT ACTUALLY ONE ROW?
   *
   * The assertion this suite was missing, and the reason it reported 10/10 on a screen that rendered as
   * four separate bands with ~170px of blank space between them. Every other test here measures a CELL —
   * caption over field, bands consistent, hiding works — and all of that was true while the layout was
   * plainly broken. Structure is not layout.
   *
   * What broke it: two `.pos-fullrow` wrappers (the sellable-stock and FEFO batch notices) sat BETWEEN the
   * field groups with an unconditional `display:block`, so each claimed a full-width flex line even when
   * empty — which they are on a fresh screen and for every shop that does not track batches.
   */
  it('THE DESIGN — the entry fields are on ONE row, not stacked into bands', () => {
    // A WIDE till. The design is explicit that the strip WRAPS rather than switching off on a small
    // screen — "one layout from a 24in till down to a phone" — so asserting a single band at Cypress's
    // default 1280px would be testing the viewport, not the layout. At 1600px the full field set fits,
    // and anything that still breaks the flow is a real defect.
    cy.viewport(1600, 900)
    openTill()
    setFields({})

    cy.get('#Sell .pos-cell:visible').then(($cells) => {
      const tops = [...$cells].map((c) => Math.round(c.getBoundingClientRect().top))
      const bands = [...new Set(tops)].sort((a, b) => a - b)

      // The failure this catches: an empty full-width notice wrapper sitting BETWEEN the field groups
      // cut the strip into four bands with ~170px of dead space, while the fields themselves occupied
      // only 732px of a 1300px form. Cells not fitting is wrapping; cells not fitting when there is
      // room to spare is a break.
      expect(bands.length, 'cell tops: ' + JSON.stringify(tops)).to.eq(1)

      // And the strip is one row tall. Four bands measured ~600px; one row is well under 200.
      const form = $cells.closest('#Sell')[0].getBoundingClientRect()
      expect(form.height, 'the strip is one row tall, not several').to.be.lessThan(200)
    })
  })

  it('an EMPTY batch notice takes no line — it is a message, not a spacer', () => {
    openTill()
    setFields({})
    // Both alerts start hidden (loadStock shows them per product). Their wrappers must not reserve a
    // full-width line while they have nothing to say.
    cy.get('#sellSellableInfo').should('not.be.visible')
    cy.get('#sellBatchInfo').should('not.be.visible')
    cy.get('#sellBatchInfo').closest('.pos-fullrow').should('have.css', 'display', 'none')
  })

  it('a caption and its input stay in the same column when the strip wraps', () => {
    openTill()
    cy.get('#sellItems').closest('.pos-cell').then(($cell) => {
      const cell = $cell[0].getBoundingClientRect()
      const cap = $cell.find('label')[0].getBoundingClientRect()
      const inp = $cell.find('input')[0].getBoundingClientRect()
      // Caption directly above its field, both within the cell — the thing negative margins were
      // approximating and could lose.
      expect(cap.bottom, 'caption sits above the input').to.be.at.most(inp.top + 1)
      expect(cap.left, 'and shares its left edge').to.be.closeTo(inp.left, 2)
      expect(cap.top, 'both inside the cell').to.be.at.least(cell.top - 1)
    })
  })

  // ── tenant configuration ────────────────────────────────────────────────────────────────────────

  it('switching a field off hides its caption WITH it', () => {
    openTill()
    setFields({})
    cy.get('#sellStock').should('be.visible')
    cy.get('#sellStock').closest('.pos-cell').find('label').should('be.visible')

    setFields({ stock: false })
    cy.get('#sellStock').should('not.be.visible')
    // The orphaned-caption failure, asserted directly: before the cell, this needed the attribute to be
    // present and correct on TWO elements.
    cy.get('#sellStock').closest('.pos-cell').find('label').should('not.be.visible')
  })

  it('a switched-off field DROPS OUT of the keyboard chain', () => {
    openTill()
    setFields({})
    // FocusFlow skips anything not visible, and visibility is computed from layout — so hiding the CELL
    // must remove the field from the chain. If it did not, Enter would stop on an invisible control and
    // the till would appear to freeze.
    cy.window().then((w) => {
      const before = w.EnterChain.fieldsIn('#Sell')
      expect(before, 'discount is in the chain while it is on').to.include('sellDiscount')
    })
    setFields({ lineDiscount: false })
    cy.window().then((w) => {
      const after = w.EnterChain.fieldsIn('#Sell')
      expect(after, 'and gone once switched off').to.not.include('sellDiscount')
      // Its dependent chooser goes too — a control whose only job is to modify a field that is no
      // longer on screen is a dead stop the cashier must Enter past on every line.
      expect(after, 'the type chooser follows it').to.not.include('sellDiscountTypeDD')
    })
  })

  // ── the chain is UNCHANGED ──────────────────────────────────────────────────────────────────────

  it('the Enter sequence is exactly what it was — Item → Qty → Price → Type → Discount', () => {
    cy.seedProduct({ name: 'CellChain_' + Date.now(), sellingPrice: 25, stock: 20 })
      .then(({ productId }) => {
        openTill()
        setFields({})
        // The picker fills from PagedFetch across every page of the catalogue, so a product seeded
        // moments ago may not be in the <select> yet. Wait for the option, or cy.select() fails with
        // "could not find a single <option>" — a race that reads like a missing product.
        cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')
        cy.get('#sellItemDD').select(String(productId), { force: true })
        cy.get('#sellSellRate', { timeout: 10000 }).should('not.have.value', '')

        cy.get('#sellItems').focus().type('{enter}')
        cy.focused().should('have.id', 'sellSellRate')
        cy.focused().type('{enter}')
        // The chooser before the amount — the order pos-keyboard.js has always used. Wrapping the
        // fields in cells must not have re-sequenced it.
        cy.focused().closest('.pos-cell').find('select, input').first()
          .should('have.id', 'sellDiscountTypeDD')
      })
  })

  it('read-only figures are still skipped — Enter never stops on Total or Stock', () => {
    openTill()
    setFields({})
    cy.window().then((w) => {
      const chain = w.EnterChain.fieldsIn('#Sell')
      // A computed total is a real input that submits its value, but stopping on it wastes a keystroke
      // on every single line. DEAD_STOPS and readOnly both keep them out.
      ;['sellTotalAmount', 'sellStock', 'bexpDate', 'sellrm', 'sellItemDesc'].forEach((id) => {
        expect(chain, id + ' is not a stop').to.not.include(id)
      })
    })
  })

  // ── mouse-free, end to end ──────────────────────────────────────────────────────────────────────

  it('MOUSE FREE — scan, quantity, Enter, and the line is in the cart', () => {
    const name = 'CellFree_' + Date.now()
    cy.seedProduct({ name, sellingPrice: 40, stock: 30, sku: 'CF' + Date.now() })
      .then(({ sku }) => {
        openTill()
        setFields({})
        // A wedge scanner types the code and presses Enter. From here the operator's hands never leave
        // the keyboard — which is the entire point of the row layout.
        cy.get('#sellScan').should('be.visible').type(sku + '{enter}')
        cy.get('#sellItems', { timeout: 15000 }).should('be.visible')
        cy.focused().type('3{enter}')
        // #tablesi is the cart (Item id · Name/Code · QTY · Price · Disc · Total · Action).
        cy.get('#tablesi tbody tr', { timeout: 15000 }).should('have.length.at.least', 1)
        cy.get('#tablesi').should('contain.text', name)
      })
  })
})
