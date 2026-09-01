/**
 * C4 — a tenant's SHAPE seeds its capabilities, and each domain sees only its own screens.
 *
 * <h3>Why this needs several tenants and could not be done with one</h3>
 * The C3 gate proved the mechanism: a capability switched off hides a section and makes the API refuse. It
 * could not prove the thing that was actually asked for — that a mobile shop does not see prescriptions and a
 * pesticide dealer does not see driver settlements. Worse, a bug that hid a section for EVERY tenant would
 * pass a single-tenant suite perfectly, and "turning it off for A does not affect B" has no meaning without a
 * B. So this file uses separate ORGS, seeded in auth-service.
 *
 * <h3>These are not new modules, and that is the design</h3>
 * owner.mobile@ and owner.pesticide@ are both userType BUSINESS. They differ by `org.shape` and `org.cap.*`.
 * A userType per trade would hardcode a customer's business into the platform — `if ("MOBILE".equals(type))`
 * is `if (organizationId == 24)` with one level of indirection.
 *
 * <h3>What is asserted, and the one honest limitation</h3>
 * For a DENIED section: `have.class('cap-off')` AND `not.be.visible` — the property the operator experiences.
 * For an ALLOWED section: `not.have.class('cap-off')` only. Sections on this dashboard are `display:none`
 * until their nav entry is clicked, so "is visible" is not available to assert without driving the menu; what
 * matters here is that capability gating has not removed it. The C3 gate covers the visible path end to end.
 */

const DASH = '/businessDashboard'

/** Sections, by the capability that governs them. See businessDashboard.html [data-capability]. */
const PHARMACY_ONLY = ['#PrescriptionDiv', '#ClinicalDiv']
const DISTRIBUTION_ONLY = ['#TerritoryDiv', '#RoundSheetDiv', '#DriverSettlementDiv']
const BATCH_ONLY = ['#QuarantineDiv']
const INSTALLMENTS = '#InstallmentDiv'

const denied = (sel) => cy.get(sel).should('have.class', 'cap-off').and('not.be.visible')
const allowed = (sel) => cy.get(sel).should('exist').and('not.have.class', 'cap-off')

describe('C4 — shape presets give each domain its own screens', () => {
  after(() => {
    /*
     * Leave no server state behind — and here that is not a nicety. A shape is per-tenant SERVER state that
     * outlives the run, so a tenant left on `pharmacy` would change what every later spec sees on the sale
     * screen. period-close once left the books locked and reddened every sale spec after it; this is the same
     * failure with a different switch.
     */
    cy.loginAsMobileOwner()
    cy.clearCapabilityOverrides()
    cy.setShape('general')
    cy.loginAsPesticideOwner()
    cy.clearCapabilityOverrides()
    cy.setShape('general')
    cy.setCapability('rxRequired', true)
    cy.loginAsMarketplaceOwner()
    cy.setShape('general')
  })

  // ── the migration promise, on a real tenant ─────────────────────────────────────────────────────

  it('a tenant that has chosen no shape still sees everything', () => {
    /*
     * THE reason this rollout is safe, asserted against a real tenant rather than a unit test's fake store.
     * Every existing organization has no org.shape row, so every one resolves to GENERAL, whose preset is
     * every capability — which is exactly the behaviour before C4 existed.
     *
     * If this fails, tenants lose screens they were using on the deploy that introduces shapes.
     */
    cy.loginAsMobileOwner()
    /*
     * ESTABLISH THE PRECONDITION, do not assume it.
     *
     * This asserts what the GENERAL PRESET gives a tenant, and an explicit override BEATS the preset by
     * design (CapabilityService.resolve). setShape does not clear overrides, so without this the test is
     * asserting the preset against whatever the last spec to touch this tenant left behind — and
     * serial-register.cy.js leaves serialTracking/conditionGrading on. It passed only when it happened to
     * run first, which is not a gate.
     */
    cy.clearCapabilityOverrides()
    cy.setShape('general')
    cy.getCapabilities().then((caps) => {
      Object.entries(caps).forEach(([code, on]) => {
        expect(on, `${code} must stay ON for a tenant with no shape chosen`).to.eq(true)
      })
    })
  })

  // ── retail: the mobile shop ─────────────────────────────────────────────────────────────────────

  it('MOBILE (retail) — keeps installments, loses pharmacy and distribution screens', () => {
    cy.loginAsMobileOwner()
    cy.setShape('retail')
    cy.visit(DASH)

    // What a mobile shop actually uses: selling handsets on terms.
    allowed(INSTALLMENTS)

    // What it has no use for. This is the request in one assertion: a mobile shop should not be looking at
    // prescriptions, territories or driver settlements.
    PHARMACY_ONLY.forEach(denied)
    DISTRIBUTION_ONLY.forEach(denied)
  })

  // ── pharmacy shape, with the tenant's own correction on top ─────────────────────────────────────

  it('PESTICIDE (pharmacy shape) — gets batch screens, and an explicit override BEATS the preset', () => {
    cy.loginAsPesticideOwner()
    cy.setShape('pharmacy')
    cy.visit(DASH)

    // The pharmacy preset brings batch/expiry handling, which an agri-chem dealer genuinely needs.
    BATCH_ONLY.forEach(allowed)
    DISTRIBUTION_ONLY.forEach(denied)

    /*
     * ⭐ THE CASE THAT MAKES SHAPES SAFE TO OFFER AT ALL.
     *
     * The pharmacy preset switches rxRequired ON, but a pesticide counter is not prescription-controlled.
     * The owner turns it off — and that explicit choice must survive, including across a later shape change.
     *
     * Without this rule, picking a shape would silently destroy deliberate settings, and the only safe advice
     * would be "never change your profile" — which is not a setting, it is a trap. The resolution order exists
     * for exactly this: explicit override WINS, preset only fills the gap.
     */
    cy.setCapability('rxRequired', false)
    cy.visit(DASH)
    PHARMACY_ONLY.forEach(denied)

    // And re-applying the shape must not quietly resurrect it.
    cy.setShape('pharmacy')
    cy.visit(DASH)
    PHARMACY_ONLY.forEach(denied)
    // Positive control on the same tenant: the batch screens the preset granted are still there, so the
    // assertion above is not passing because everything happens to be hidden.
    BATCH_ONLY.forEach(allowed)
  })

  // ── distribution ────────────────────────────────────────────────────────────────────────────────

  it('MARKETPLACE (distribution) — gets field sales and collections, not prescriptions', () => {
    /*
     * Asserted through the capability MAP rather than the DOM. The marketplace tenant renders a relabelled
     * dashboard, and pinning this test to that template would make it fail for a reason that has nothing to
     * do with capabilities. The map is what the markup reads, so it is the honest layer to assert here.
     */
    cy.loginAsMarketplaceOwner()
    cy.setShape('distribution')
    cy.getCapabilities().then((caps) => {
      expect(caps.fieldSales, 'a distributor books orders in the field').to.eq(true)
      expect(caps.journeyPlanning, 'and plans routes').to.eq(true)
      expect(caps.collections, 'and collects cash on delivery').to.eq(true)
      expect(caps.dealerPricing, 'and prices per dealer tier').to.eq(true)

      // The other half of the same claim — a preset that granted everything would pass the four above.
      expect(caps.rxRequired, 'a distributor does not dispense prescriptions').to.eq(false)
      expect(caps.serialTracking, 'and does not track handset IMEIs').to.eq(false)
    })
  })

  // ── tenancy ─────────────────────────────────────────────────────────────────────────────────────

  it('one tenant\'s shape does not reach another', () => {
    /*
     * The assertion a single-tenant suite cannot make. Both tenants are userType BUSINESS on the same
     * dashboard, so if shape or capability resolution ever keyed on anything but organization_id — a static
     * field, a shared cache without the org in its key, a JVM-wide setting — this is what catches it.
     *
     * The per-tenant Caffeine cache in SettingsService makes that a live risk rather than a theoretical one.
     */
    // Same precondition as the migration test: these assert PRESETS, so leftover explicit overrides from
    // any earlier spec would decide the answer instead.
    cy.loginAsMobileOwner()
    cy.clearCapabilityOverrides()
    cy.setShape('retail')

    cy.loginAsPesticideOwner()
    cy.clearCapabilityOverrides()
    cy.setShape('pharmacy')

    // Pesticide is on pharmacy: batch tracking on, and the retail shape next door has not leaked in.
    cy.getCapabilities().then((caps) => {
      expect(caps.batchTracking, 'pesticide is on the pharmacy preset').to.eq(true)
      expect(caps.fieldSales, 'and pharmacy does not include field sales').to.eq(false)
    })

    // And mobile is unchanged by anything pesticide just did.
    cy.loginAsMobileOwner()
    cy.getCapabilities().then((caps) => {
      expect(caps.installments, 'mobile is still on the retail preset').to.eq(true)
      expect(caps.batchTracking, "and did not inherit pesticide's batch tracking").to.eq(false)
    })
  })
})
