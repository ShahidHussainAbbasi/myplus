/**
 * UI-FORM-1 — purchase and product form layout.
 *
 * Design: microservices/docs/slices/ui-form-1-purchase-product-layout.md
 *
 * What the review found broken, asserted at desktop / tablet / phone:
 *   P1  the QTY row summed to 20 of 12 Bootstrap columns and threw the Pack/Box toggle outside the form's columns;
 *   P2  Amount Paid sat on the same line as the action buttons;
 *   P3  bill fields (kept for the next line) and item fields (cleared) were interleaved;
 *   R1  the product form had no title, so New and Edit looked the same.
 *
 * GEOMETRY, NOT CLASS NAMES: every OPTIONAL cell (vendor dues, tax, the unit toggle, packs-per-box, the box hint,
 * barcode, the loose row) is forced visible first, so "nothing overflows, nothing overlaps" is tested with the
 * form at its FULLEST — the configuration in which P1 happened — rather than whatever this tenant happens to show.
 *
 * Run headed.
 */

const SIZES = [
  { name: 'desktop', w: 1366, h: 768 },
  { name: 'tablet', w: 1024, h: 768 },
  { name: 'narrow tablet', w: 820, h: 1000 },
  { name: 'phone', w: 390, h: 844 },
]

const PURCHASE_OPTIONAL = ['#purchaseVendorDuesWrap', '#purchaseTaxRow', '.purchase-unit-wrap', '#purchasePpbWrap']
const PRODUCT_OPTIONAL = ['#prodBarcodeWrap', '#prodBarcodeLabel', '#prodLooseWrap']

const rect = (el) => el.getBoundingClientRect()
const visible = (el) => el.offsetParent !== null && rect(el).width > 0 && rect(el).height > 0

/**
 * Every visible CONTROL lies inside the modal and no two overlap. Markup-independent on purpose: a check written
 * against the new classes (.fg-grid) went red on the old form only because the classes were missing — which proves
 * nothing about the defects. This reads the screen, so it judges the old and the new layout by the same rule.
 * A bootstrap-select is judged by its visible WRAPPER (the <select> itself is hidden).
 */
function controlsOf(w, formSel) {
  const form = w.document.querySelector(formSel)
  const out = []
  form.querySelectorAll('input:not([type=hidden]), select, textarea, .btn-group').forEach((el) => {
    let v = el
    if (el.tagName === 'SELECT' && el.classList.contains('selectpicker')) v = el.nextElementSibling
    if (!v || !visible(v)) return
    if (v.tagName === 'INPUT' && v.type === 'checkbox') return          // judged with its label row, not alone
    if (v.closest('.btn-group') && v !== el.closest('.btn-group') && v.tagName !== 'DIV') return
    out.push({ el: v, id: el.id || v.id || el.className })
  })
  return out
}

function assertControlsSound(w, formSel) {
  const box = rect(w.document.querySelector(formSel).closest('.crud-box'))
  const cs = controlsOf(w, formSel)
  expect(cs.length, `${formSel} has visible controls`).to.be.greaterThan(5)
  cs.forEach(({ el, id }) => {
    const r = rect(el)
    expect(r.left, `${id}: inside the modal (left)`).to.be.at.least(box.left - 1)
    expect(r.right, `${id}: inside the modal (right)`).to.be.at.most(box.right + 1)
  })
  for (let i = 0; i < cs.length; i++) {
    for (let j = i + 1; j < cs.length; j++) {
      const a = rect(cs[i].el), b = rect(cs[j].el)
      if (cs[i].el.contains(cs[j].el) || cs[j].el.contains(cs[i].el)) continue
      const overlap = a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1
      expect(overlap, `${cs[i].id} and ${cs[j].id} do not overlap`).to.be.false
    }
  }
  const boxEl = w.document.querySelector(formSel).closest('.crud-box')
  expect(boxEl.scrollWidth, 'no horizontal overflow in the modal').to.be.at.most(boxEl.clientWidth + 1)
}

/** The save buttons are on screen without scrolling, and the given field is never on a line beside them. */
function assertActionsReachable(w, formSel, fieldId, buttonIds) {
  const field = w.document.getElementById(fieldId)
  const btns = buttonIds.map((id) => w.document.getElementById(id))
  btns.forEach((b) => {
    expect(rect(b).bottom, `${b.id} is visible without scrolling`).to.be.at.most(w.innerHeight + 1)
    expect(rect(b).top, `${b.id} is on screen`).to.be.at.least(0)
  })
  // P2: scroll the field into view — every button must still be BELOW it, never beside it.
  field.scrollIntoView({ block: 'center' })
  btns.forEach((b) => {
    expect(rect(field).bottom, `${fieldId} is above ${b.id}, not beside it`).to.be.at.most(rect(b).top + 1)
  })
}

function openPurchase() {
  cy.openPurchaseSection('purchaseDiv')
  cy.get('#newPurchase').click()
  cy.get('#PurchaseModal').should('have.class', 'open')
  cy.settled('#purchaseInvoiceNo')
}

function openNewProduct() {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().then((w) => w.showProducts())
  cy.get('#newProduct').click()
  cy.get('#ProductModal').should('have.class', 'open')
}

describe('UI-FORM-1 — purchase and product form layout', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  SIZES.forEach(({ name, w: width, h }) => {
    it(`purchase form, ${name} ${width}px: at its fullest nothing overflows or overlaps; Paid is not beside the buttons; the buttons are reachable`, () => {
      cy.viewport(width, h)
      openPurchase()
      cy.window().then((w) => {
        PURCHASE_OPTIONAL.forEach((sel) => w.$(sel).show())
        w.$('#purchaseBoxHint').text('10 boxes = 120 packs · 100.00 per pack · 12,000.00').show()
        w.$('#purchasePaidHint').text('Due on this line: 60.00').show()
      })
      cy.window().then((w) => {
        assertControlsSound(w, '#Purchase')
        // P1: the Pack/Box toggle is ONE control — its two halves side by side, not stacked at the form's edge.
        expect(Math.abs(rect(w.document.getElementById('purchaseUnitPack')).top
          - rect(w.document.getElementById('purchaseUnitBox')).top), 'Pack and Box sit side by side').to.be.below(2)
        assertActionsReachable(w, '#Purchase', 'purchasePaid', ['addPurchaseAnother', 'addPurchase'])
      })
    })

    it(`product form, ${name} ${width}px: at its fullest nothing overflows or overlaps; the buttons are reachable`, () => {
      cy.viewport(width, h)
      openNewProduct()
      cy.window().then((w) => { PRODUCT_OPTIONAL.forEach((sel) => w.$(sel).show()) })
      cy.window().then((w) => {
        assertControlsSound(w, '#Product')
        assertActionsReachable(w, '#Product', 'prodDesc', ['addProductAnother', 'addProduct'])
      })
    })
  })

  it('P3 — the purchase form reads BILL then ITEM: what is kept is grouped apart from what is cleared', () => {
    openPurchase()
    cy.window().then((w) => {
      const [bill, item] = w.document.querySelectorAll('#Purchase .fg-section')
      const inside = (section, id) => section.contains(w.document.getElementById(id))
      ;['purchaseInvoiceNo', 'purchaseVenderDD', 'purchaseDate'].forEach((id) =>
        expect(inside(bill, id), `${id} is in the Bill section`).to.be.true)
      ;['purchaseItemDD', 'purchaseQuantity', 'purchaseBatchNo', 'purchasePurchaseRate', 'purchaseTaxRate'].forEach((id) =>
        expect(inside(item, id), `${id} is in the Item section`).to.be.true)
      expect(bill.textContent, 'the Bill caption says it is kept').to.match(/kept for the next line/i)
    })
  })

  it('R1 — the product form says whether it is ADDING or EDITING', () => {
    cy.seedProduct({ name: `UIF1_${Date.now()}`, sellingPrice: 12 }).then(({ productId }) => {
      openNewProduct()
      cy.get('#ProductModalTitle').should('be.visible').and('have.text', 'New Product')
      cy.get('#addProduct').should('contain', 'Save & Close')
      cy.get('#resetProduct').click()
      cy.get('#ProductModal').should('not.have.class', 'open')
      cy.window().then((w) => w.editProduct(productId))
      cy.get('#ProductModal', { timeout: 15000 }).should('have.class', 'open')
      cy.get('#ProductModalTitle').should('be.visible').and('have.text', 'Edit Product')
      // The name was FILLED by the edit, not typed: the panel stays one line (an unfolded panel excluding this very
      // product was an empty 230px box that scrolled this title out of view).
      cy.get('#prodExistingWrap').should('have.class', 'is-idle')
    })
  })

  /*
   * Found by busy-controls case 8 on the first green build: the panel unfolded when the SEARCH landed, which moved
   * Save & Add Another between the pointer going down and the click — the click missed and nothing was saved. The
   * unfold now happens on the keystroke, and the unfolded panel reserves its height.
   */
  it('typing a name never moves the Save buttons once the search results land', () => {
    cy.intercept('GET', '**/getProductPage*').as('panel')
    openNewProduct()
    cy.get('#prodName').type('zz')                                  // the unfold happens here, on the keystroke
    cy.get('#prodExistingWrap').should('not.have.class', 'is-idle')
    cy.get('#addProductAnother').then(($b) => {
      const before = $b[0].getBoundingClientRect().top
      cy.get('#prodName').type(`_no_such_${Date.now()}`)
      cy.wait('@panel')
      cy.get('#prodExistingMsg').should('be.visible')             // the "no match" answer has landed
      cy.get('#addProductAnother').should(($b2) => {
        expect(Math.round($b2[0].getBoundingClientRect().top), 'Save & Add Another did not move')
          .to.eq(Math.round(before))
      })
    })
  })

  it('"Already registered" is ONE line until something is typed, then lists the matches', () => {
    cy.seedProduct({ name: `UIF1Fold_${Date.now()}`, sellingPrice: 9 }).then(({ productId, name }) => {
      openNewProduct()
      cy.get('#prodExistingWrap', { timeout: 10000 }).should('be.visible').and('have.class', 'is-idle')
      cy.get('#prodExistingCount').should('not.have.text', '')          // the count is still shown
      cy.get('#prodExistingList').should('not.be.visible')              // the list is folded...
      cy.get('#prodExistingList .crud-existing-row').should('have.length.greaterThan', 0)   // ...not emptied
      cy.get('#prodExistingWrap').invoke('outerHeight').should('be.lessThan', 60)

      cy.get('#prodName').type(name)
      cy.get('#prodExistingWrap').should('not.have.class', 'is-idle')
      cy.get(`#prodExistingList .crud-existing-row[data-id="${productId}"]`).should('be.visible')
      cy.get('#prodName').clear()
      cy.get('#prodExistingWrap').should('have.class', 'is-idle')
    })
  })
})
