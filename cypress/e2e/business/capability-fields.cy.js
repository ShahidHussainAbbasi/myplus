/**
 * ONB-2 — a capability gates the FIELD, not only the section.
 *
 * Design: microservices/docs/slices/onb-2-assign-business-type-design.md
 *
 * ── The complaint this gates ────────────────────────────────────────────────────────────────────
 * A mobile shop's purchase form showed "Batch #". Not because the tenant was mis-configured — `owner.mobile@`
 * is correctly on `retail` with batchTracking OFF — but because the FIELD was never gated.
 *
 * `businessDashboard.html` carries 34 `data-capability` attributes and every one of them is on a SECTION or a
 * nav item. Nobody had gated a field INSIDE a form that every tenant legitimately uses: a mobile shop does have
 * a purchase form, it just has no batches. A scripted sweep found 20 such fields.
 *
 * ── ⭐ Case 10 asserts a FALSE POSITIVE stays visible ────────────────────────────────────────────
 * `customerLicenseExpiry` is a customer's TRADE LICENCE expiry and has nothing to do with `expiryTracking`,
 * which is about stock. The sweep flagged it on the word "expiry". Gating it would hide a field every business
 * needs — so the test pins it visible, because the next person running that sweep will hit it too.
 *
 * ── ⚠ The purchase form lives in a MODAL ────────────────────────────────────────────────────────
 * `#PurchaseModal` is `display:none` until `newPurchase()` opens it, so asserting `be.visible` on a field
 * straight after `cy.visit` fails on the overlay rather than on the gate — which is what the first run did.
 * `openPurchaseForm()` below navigates and opens it, so every visibility assertion is made on a form a person
 * could actually be looking at.
 *
 * ── The tenants ─────────────────────────────────────────────────────────────────────────────────
 *   owner.mobile@     retail   — batch/expiry/loose/rx all OFF
 *   owner.pesticide@  pharmacy — batch/expiry/loose ON, rx per its own switch
 *
 * Two tenants, deliberately: "hidden for A" proves nothing without a B where it is shown. A build that hid
 * these fields from everybody would pass every negative case here.
 */

const DASH = '/businessDashboard'

/**
 * Open the purchase form the way an operator does — through the nav, then the New Purchase button.
 *
 * Not by calling newPurchase() directly: the point of these cases is what a person SEES, and a form opened by
 * a route no menu offers would prove the gate works somewhere nobody goes.
 */
const openPurchaseForm = () => {
  cy.visit(DASH)
  // Nav items live inside a collapsed sub-menu; force:true because a real click on a closed item is not what
  // is under test here (project convention for these menus).
  cy.get('#snavPurchase .snav-btn', { timeout: 15000 }).click({ force: true })
  cy.contains('#snavPurchase a', /new purchase/i).click({ force: true })
  cy.get('#newPurchase', { timeout: 15000 }).click({ force: true })
  cy.get('#PurchaseModal', { timeout: 15000 }).should('have.class', 'open')
}

/**
 * The gate fired: capabilities.js added .cap-off, which is `display:none !important`.
 *
 * ⚠ Deliberately NOT asserting `not.be.visible` here. Most of these fields live in modals that are closed at
 * rest, so an invisibility assertion would pass because the OVERLAY is hidden and would keep passing if the
 * gate were deleted entirely — a check that cannot fail is worse than no check. Use `hiddenOnScreen` where the
 * container is actually open.
 */
const gatedOff = (sel) => cy.get(sel).should('have.class', 'cap-off')

/** Both halves: the gate fired AND a person genuinely cannot see it. Only valid with the container OPEN. */
const hidden = (sel) => cy.get(sel).should('have.class', 'cap-off').and('not.be.visible')

const shown = (sel) => cy.get(sel).should('exist').and('not.have.class', 'cap-off')

/** The enclosing group carries the gate; the input is what an operator actually sees. */
const GROUP = {
  purchaseBatch: '[data-capability="batchTracking"]:has(#purchaseBatchNo)',
  purchaseExpiry: '[data-capability="expiryTracking"]:has(#purchaseExpiry)',
  sellBatch: '[data-capability="batchTracking"]:has(#sellBatchNo)',
  loose: '#prodLooseWrap',
  allowLoose: '[data-capability="looseSelling"]:has(#prodAllowLoose)',
  rx: '[data-capability="rxRequired"]:has(#clRx)',
}

describe('ONB-2 — capability gates the field, not only the section', () => {
  after(() => {
    // Leave no server state behind: both tenants go back to their seeded shapes, which is what
    // SetupDataLoader and capability-shapes.cy.js both expect to find.
    cy.loginAsMobileOwner()
    cy.clearCapabilityOverrides()
    cy.setShape('retail')
    cy.loginAsPesticideOwner()
    cy.clearCapabilityOverrides()
    cy.setShape('pharmacy')
  })

  // ── ⭐ the reported complaint ────────────────────────────────────────────────────────────────────

  it('⭐ 6 — a RETAIL tenant sees no Batch # and no Expiry on the purchase form', () => {
    /*
     * THE CASE. A mobile shop buys handsets: it has a purchase form, an invoice number, a vendor and a price.
     * It has no batches and no expiry dates, and being asked for them on every goods-in is the complaint that
     * started this slice.
     */
    cy.loginAsMobileOwner()
    cy.clearCapabilityOverrides()
    cy.setShape('retail')
    openPurchaseForm()

    cy.get('#purchaseBatchNo').should('not.be.visible')
    cy.get('#purchaseExpiry').should('not.be.visible')
    hidden(GROUP.purchaseBatch)
    hidden(GROUP.purchaseExpiry)
    // Both HALVES of each field. The first run gated only the labels, which would have left a retail tenant
    // with an unlabelled date box — worse than the original bug, because nothing on screen says what it is.
    cy.get('label[for="purchaseBatchNo"]').should('not.be.visible')
    cy.get('label[for="purchaseExpiry"]').should('not.be.visible')
  })

  it('⭐ 7 — a PHARMACY tenant DOES see both', () => {
    // The positive control. Without it, a build that hid batch and expiry from every tenant on the platform
    // would pass case 6 and look like a fix — while breaking every pharmacy's goods-in.
    cy.loginAsPesticideOwner()
    cy.clearCapabilityOverrides()
    cy.setShape('pharmacy')
    openPurchaseForm()

    cy.get('#purchaseBatchNo').should('be.visible')
    cy.get('#purchaseExpiry').should('be.visible')
    cy.get('label[for="purchaseBatchNo"]').should('be.visible')
    cy.get('label[for="purchaseExpiry"]').should('be.visible')
    shown(GROUP.purchaseBatch)
    shown(GROUP.purchaseExpiry)
  })

  it('8 — loose-selling fields follow the same rule on the product form', () => {
    /*
     * prodLooseWrap carries style="display:none" and is toggled by JS. `.cap-off` uses
     * `display:none !important` deliberately so it wins over an inline style — the capability design records
     * that decision after module-theme.js fought exactly this. When the capability IS on, the JS toggle
     * resumes control, which is why the pharmacy half asserts the ATTRIBUTE rather than visibility.
     */
    cy.loginAsMobileOwner()
    cy.setShape('retail')
    cy.visit(DASH)
    gatedOff(GROUP.allowLoose)   // product form is a closed modal — see gatedOff

    cy.loginAsPesticideOwner()
    cy.setShape('pharmacy')
    cy.visit(DASH)
    shown(GROUP.allowLoose)
  })

  it('9 — the prescription checkbox is hidden without rxRequired', () => {
    // A hardware shop must not be able to mark a product prescription-only. The WRITE is already refused by
    // C6's clinical-flags guard; this is the half that stops the control being offered at all.
    cy.loginAsMobileOwner()
    cy.setShape('retail')
    cy.visit(DASH)
    gatedOff(GROUP.rx)           // clinical flags live in a closed modal — see gatedOff
  })

  // ── ⭐ the false positive ────────────────────────────────────────────────────────────────────────

  it('⭐ 10 — a customer\'s TRADE LICENCE expiry is not stock expiry, and stays visible', () => {
    /*
     * The sweep that found the 20 fields flagged `customerLicenseExpiry` on the word "expiry". It is a
     * customer's trade licence — every business records one, and `expiryTracking` is about STOCK.
     *
     * Pinned visible for a tenant with expiry tracking OFF, because that is the configuration in which a
     * careless gate would hide it, and because the next person running that sweep will hit the same name.
     */
    cy.loginAsMobileOwner()
    cy.setShape('retail')
    cy.visit(DASH)
    cy.get('#customerLicenseExpiry', { timeout: 15000 })
      .should('exist')
      .and('not.have.class', 'cap-off')
    cy.get('#customerLicenseExpiry').closest('[data-capability]').should('have.length', 0)
  })
})
