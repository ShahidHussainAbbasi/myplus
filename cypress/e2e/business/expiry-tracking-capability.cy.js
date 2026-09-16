/**
 * EXP-1 — `org.cap.expiryTracking` decides whether dated stock is a thing at all, not just whether a label shows.
 *
 * Design: microservices/docs/slices/exp-1-expiry-tracking-capability.md.
 *
 * WHAT THE USER ASKED: "if org.cap.expiryTracking is off, do not show the 'N expired' label on the product
 * datatable's on-hand column". The live case: org 44 (shape `retail`, whose preset has no expiry) was shown
 * "5 expired" for stock it does not date.
 *
 * WHY THIS GATE DOES NOT JUST LOOK FOR THE LABEL. The column shows SELLABLE stock, and sellable excluded expired
 * stock. Hiding only the badge would have left a lower number with no explanation, while the allocator still
 * refused the goods at the till. So the property is three things together, and a gate on any one of them alone
 * could go green on a broken build:
 *   1. the badge is gone,
 *   2. the number beside it is no longer reduced, and
 *   3. the till's own single-product read agrees with the grid.
 * Plus the floor: a pharmacy may not switch the capability off.
 *
 * ⚠ What this spec does NOT prove, stated rather than implied: that a SALE of the dated stock goes through. The
 * allocator half — findForFefo returning a dated batch when tracking is off, and excluding it when on — is proven
 * against a real MySQL in ReservationServiceTest (the two EXP-1 cases, inventory-service 64/64). A UI sale here
 * would add the confirm dialog, tenders and a customer to a case whose question is about stock.
 *
 * ⚠ NEVER owner.business@ — the account the user works in. This spec flips a tenant-wide capability and the shape.
 *
 * ⚠ THE FIXTURE IS owner.pesticide@ (org 45), AND WHY NOT owner.mobile@ — existence is not eligibility.
 * owner.mobile@ is org 44, the live tenant from the report, and the obvious choice. It was tried first and went
 * red on 3 of 5 for a reason that had nothing to do with EXP-1: an operator has SUSPENDED expiryTracking for org
 * 44 (org_entitlement status=SUSPENDED, source=ADMIN_OVERRIDE, with batchTracking and fefoAllocation). So the
 * owner could not switch it ON (the plan write guard refused), and the pharmacy floor correctly could not hand it
 * back, because a revoked capability outranks the floor by design. Both were the product being right.
 *   That same run is still useful evidence: on org 44, before the fixture failure, /productStockLevels returned
 * expired 0 / sellable 7 and the till's /productSellable agreed — the fix working on the tenant that reported it.
 *   owner.pesticide@ is a BUSINESS-type ROLE_OWNER on a PHARMACY-shaped org with expiryTracking ACTIVE, so it can
 * run every case: the toggles under `general`, the floor under `pharmacy`. Its shape is snapshotted and restored.
 *
 * ⚠ LEAVE NO SERVER STATE. The capability override and the shape are snapshotted in before() and put back in
 * after(). commerce-gaps.cy.js leaving a 17% tax rate switched on is what re-priced another spec's sale on
 * 2026-09-16; a capability left off here would do the same to every expiry spec that runs after it.
 *
 * Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/expiry-tracking-capability.cy.js
 */

const PAST = '2026-01-31'     // dated before any run of this spec — expired
const QTY = 7

/** The grid's own endpoint for the rows on screen: productId → {onHand, sellable, expired, held}. */
const levelsFor = (productId) =>
  cy.request({ url: `/productStockLevels?ids=${productId}`, failOnStatusCode: false }).then((r) => {
    expect(r.status, 'productStockLevels HTTP').to.eq(200)
    expect(r.body && r.body.success, `productStockLevels: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    const d = r.body.levels && r.body.levels[productId]
    expect(d, `a stock row for product ${productId}: ${JSON.stringify(r.body.levels)}`).to.be.an('object')
    return d
  })

describe('EXP-1 — expiry tracking OFF means dated stock is ordinary stock', () => {
  const run = String(Date.now()).slice(-7)
  let productId
  // What this tenant had before we touched it: { absent: boolean, value: string|null } per key.
  const original = {}

  before(() => {
    cy.loginAsPesticideOwner()

    /*
     * Snapshot BEFORE changing anything, so after() restores what was really there rather than a guess.
     *
     * ⚠ `data` is a LIST of catalog entries, not a map. Reading it as a map yields undefined for every key —
     * silently — and after() would then "restore" a real override to absent. So each key is looked up in the
     * list and the lookup FAILS LOUDLY if the entry is missing.
     *
     * ⚠ A row whose value is NULL is PRESENT in the override map (isDefault=false) but resolves exactly like an
     * absent one (overrideFor → Optional.ofNullable → empty → the shape preset decides). So "absent" here means
     * isDefault OR a null value; restoring such a row as the literal string "null" would switch it OFF.
     */
    cy.request('/getBusinessConfig').then((r) => {
      const list = r.body && r.body.data
      expect(list, `getBusinessConfig must return the entry list: ${JSON.stringify(r.body).slice(0, 160)}`)
        .to.be.an('array')
      ;['org.shape', 'org.cap.expiryTracking', 'org.cap.rxRequired'].forEach((key) => {
        const e = list.find((x) => x.key === key)
        expect(e, `settings entry ${key} must exist to be snapshotted`).to.be.an('object')
        original[key] = { absent: e.isDefault === true || e.value == null, value: e.value }
      })
    })

    cy.seedProduct({ name: `EXP1_${run}` }).then((p) => { productId = p.productId })
    // Dated stock, already past — the exact state org 44 holds. Seeded through the product's own path.
    cy.then(() => cy.request({
      method: 'POST', url: '/addProductStock', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { productId, quantity: QTY, batchNo: `EXP1B_${run}`, expiryDate: PAST, purchasePrice: 10 },
    })).then((r) => expect(r.status, `seed dated stock: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(200))
  })

  beforeEach(() => cy.loginAsPesticideOwner())

  after(() => {
    cy.loginAsPesticideOwner()
    /*
     * Put the tenant back EXACTLY, and ASSERT each restore — a restore that fails silently is how a spec
     * leaves state behind while believing it did not.
     *
     * An absent override is restored as ABSENT: not "false", and not an empty string, which resolve() reads as
     * false (see clearCapabilityOverrides in commands.js). Omitting `value` is what makes the proxy store NULL
     * and hand the decision back to the shape preset.
     */
    Object.keys(original).forEach((key) => {
      const o = original[key]
      cy.request({
        method: 'POST', url: '/saveBusinessConfig', form: true,
        body: o.absent ? { key } : { key, value: String(o.value) },
      }).then((res) => {
        expect(res.body && res.body.success, `restore ${key}: ${JSON.stringify(res.body)}`).to.eq(true)
      })
    })
  })

  it('⭐⭐ 1 — tracking ON: the stock is withheld, and the badge says why (the positive control)', () => {
    /*
     * Without this case a build that had simply broken the expiry split — reporting 0 expired for everyone —
     * would pass cases 2 and 3 below. ON must still behave exactly as it always has.
     */
    cy.setShape('general')
    cy.setCapability('expiryTracking', true)

    levelsFor(productId).then((d) => {
      expect(Number(d.expired), 'tracking ON: the dated units are counted as expired').to.eq(QTY)
      expect(Number(d.sellable), 'tracking ON: and none of them is sellable').to.eq(0)
    })

    // Products are FUNCTION-navigated (showProducts()), not select-navigated — neither #sellType nor
    // #registrationType has a ProductDiv option. pos-barcode-default.cy.js records the same trap. The grid is
    // newest-first (sort id,desc) and nothing creates a product between the seed and here, so ours is row 1.
    cy.visitDashboardSettled()
    cy.get('[onclick*="showProducts"]').first().click({ force: true })
    cy.get('#ProductDiv', { timeout: 20000 }).should('be.visible')
    cy.get(`#stk_${productId}`, { timeout: 20000 }).should('contain.text', `${QTY} expired`)
  })

  it('⭐⭐ 2 — tracking OFF: NO expired badge, and the number is no longer reduced', () => {
    cy.setShape('general')
    cy.setCapability('expiryTracking', false)

    levelsFor(productId).then((d) => {
      expect(Number(d.expired), 'tracking OFF: nothing is "expired" for a shop that does not date stock')
        .to.eq(0)
      // THE HALF THAT MAKES THIS HONEST. Hiding only the label would have left sellable at 0 with no reason.
      expect(Number(d.sellable), 'tracking OFF: the dated units are ordinary sellable stock').to.eq(QTY)
    })

    // Products are FUNCTION-navigated (showProducts()), not select-navigated — neither #sellType nor
    // #registrationType has a ProductDiv option. pos-barcode-default.cy.js records the same trap. The grid is
    // newest-first (sort id,desc) and nothing creates a product between the seed and here, so ours is row 1.
    cy.visitDashboardSettled()
    cy.get('[onclick*="showProducts"]').first().click({ force: true })
    cy.get('#ProductDiv', { timeout: 20000 }).should('be.visible')
    cy.get(`#stk_${productId}`, { timeout: 20000 })
      .should('not.contain.text', 'expired')
      .and(($el) => expect($el.text(), 'the cell shows the full shelf figure').to.contain(String(QTY)))
  })

  it('⭐⭐ 3 — tracking OFF: the TILL agrees — the single-product read the sell screen uses', () => {
    /*
     * The product grid and the sell screen read DIFFERENT endpoints (/productStockLevels vs /sellable/{id}).
     * If only one learned about the capability, the grid would say 7 sellable and the till would refuse —
     * two screens disagreeing about one product is a worse defect than the label this slice removes.
     */
    cy.setShape('general')
    cy.setCapability('expiryTracking', false)

    // CatalogController.productSellable → inventory /stock/sellable/{id}. A FLAT body — {onHand, sellable,
    // expired, success} — with no `data` wrapper. No fallback: this route exists, so a 404 is a real failure,
    // and a case that quietly asserts something else when its route is missing proves nothing.
    cy.request({ url: `/productSellable?productId=${productId}`, failOnStatusCode: false }).then((r) => {
      expect(r.status, 'productSellable HTTP').to.eq(200)
      expect(r.body && r.body.success, `productSellable: ${JSON.stringify(r.body)}`).to.eq(true)
      expect(Number(r.body.sellable), 'the till sees the same units as the grid').to.eq(QTY)
      expect(Number(r.body.expired || 0), 'and no expired units').to.eq(0)
    })
  })

  it('⭐⭐ 4 — the FLOOR: a pharmacy cannot switch expiry tracking off', () => {
    /*
     * With EXP-1 the capability decides whether expired stock is SELLABLE. For a dispensary that checkbox
     * would otherwise be a way to start selling expired medicine, so Shape.PHARMACY floors it: the owner's
     * "off" is saved, and then outvoted.
     */
    cy.setShape('pharmacy')
    cy.setCapability('expiryTracking', false)

    cy.getCapabilities().then((caps) => {
      expect(caps.expiryTracking, 'a pharmacy keeps expiry tracking ON whatever it saved').to.eq(true)
    })
    // And the stock engine obeys the floor, not the saved value.
    levelsFor(productId).then((d) => {
      expect(Number(d.expired), 'floored ON: the dated units are expired again').to.eq(QTY)
      expect(Number(d.sellable), 'and not sellable').to.eq(0)
    })
  })

  it('⭐ 5 — the floor is not a blanket override: a pharmacy still chooses its other capabilities', () => {
    // A veterinary or agri-chem counter is the same shape and often not prescription-controlled.
    cy.setShape('pharmacy')
    cy.setCapability('rxRequired', false)
    cy.getCapabilities().then((caps) => {
      expect(caps.rxRequired, 'rxRequired is NOT floored — the owner may switch it off').to.eq(false)
    })
    // No cleanup here: after() restores rxRequired from the snapshot, exactly as it found it.
  })
})
