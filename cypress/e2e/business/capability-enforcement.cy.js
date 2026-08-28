/**
 * C3d — the remaining capabilities REFUSE, they do not merely hide.
 *
 * C3b closed four writes (installments, loose selling, collections, prescriptions). This closes the two of the
 * five stragglers that have a real write behind them:
 *
 *   * `dealerPricing`  → creating or editing a price rule (catalog-service)
 *   * `fieldSales`     → assigning outlets to a rep, i.e. a territory (business-service)
 *
 * <h3>The other three are not gated, and that is a finding rather than an omission</h3>
 *   * `journeyPlanning` — **no controller exists anywhere.** The capability is in the enum; the feature is not
 *     built. There is nothing to refuse.
 *   * `fefoAllocation`  — allocation BEHAVIOUR, not a user write. Nobody posts "do FEFO"; the reservation path
 *     either picks nearest-expiry or does not.
 *   * `batchTracking` / `expiryTracking` — a batch number reaches the server on the ordinary purchase path and
 *     is carried forward from previous stock, so a blind refusal risks breaking purchases in ways that need
 *     the purchase flow examined properly first. Deliberately not bolted on.
 *
 * ⚠ ASSERT ON THE ENVELOPE, NEVER THE HTTP STATUS. Refusals come back as **200 with `success:false`** for the
 * ApiResponse-shaped routes and `status:"ERROR"` for the GenericResponse-shaped ones — `ProxyErrors` carries
 * the downstream sentence rather than flattening it into a code. A `status === 200` check passes on a refusal.
 */

const OWNER = 'owner.business@myplus.com'

/**
 * A VALID price rule — the field names and values PriceRuleService.validate actually requires.
 *
 * Worth stating why this matters: the capability guard runs BEFORE validate(), so the OFF case would pass
 * with any old payload. The ON case would not — an invalid body would be refused by validation and the test
 * would report "refused" while proving nothing about the capability. A positive control has to be able to
 * succeed for the right reason.
 */
const savePriceRule = (productId) =>
  cy.request({
    method: 'POST',
    url: '/savePriceRule',
    headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      scope: 'TYPE',
      customerType: 'DEALER',
      target: 'PRODUCT',
      productId: productId,
      mode: 'PERCENT',
      value: 5,
      active: true,
    },
  })

const assignOutlets = (customerId) =>
  cy.request({
    method: 'POST',
    url: '/assignOutlets',
    form: true,
    failOnStatusCode: false,
    body: { customerIds: customerId },   // repUserId omitted = unassign, which is still a territory write
  })

describe('C3d — the remaining capabilities refuse', () => {
  let customerId = null
  let productId = null

  before(() => {
    cy.loginAsOwner(OWNER)
    cy.setCapability('dealerPricing', true)
    cy.setCapability('fieldSales', true)
    // A real product for the rule to target — seeded, because a rule against an id that happens to exist in
    // one database is not a fixture.
    cy.seedProduct({ name: `C3D_${Date.now()}` }).then((p) => { productId = p.productId })
    // A real outlet to assign. Existence is not eligibility — seed rather than hunt for one.
    cy.request('/getUserCustomer?q=-1').then((r) => {
      const list = (r.body && (r.body.collection || r.body.object)) || []
      expect(list.length, 'the tenant has at least one customer to use as an outlet').to.be.greaterThan(0)
      customerId = list[0].customerId || list[0].id
    })
  })

  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  after(() => {
    // Leave no server state behind: either capability left off changes later specs.
    cy.loginAsOwner(OWNER)
    cy.setCapability('dealerPricing', true)
    cy.setCapability('fieldSales', true)
  })

  // ── dealerPricing ───────────────────────────────────────────────────────────────────────────────

  it('dealerPricing ON — a price rule can be saved', () => {
    // The positive control. Without it, a build that refused EVERY price rule would pass the OFF case.
    cy.setCapability('dealerPricing', true)
    savePriceRule(productId).then((r) => {
      expect(r.body && r.body.success, `saving must be allowed: ${JSON.stringify(r.body)}`).to.not.eq(false)
    })
  })

  it('dealerPricing OFF — ⭐ the price rule is REFUSED, not just hidden', () => {
    /*
     * A rule written without the capability quietly changes what customers are charged: the pricing path
     * applies whatever rules exist and never asks whether the tenant was entitled to create them. Hiding the
     * Price Rules screen stopped nobody who had the URL.
     */
    cy.setCapability('dealerPricing', false)
    savePriceRule(productId).then((r) => {
      const body = JSON.stringify(r.body)
      expect(r.body && r.body.success, `the rule must be REFUSED: ${body}`).to.eq(false)
      // The refusal never describes the tenant's configuration — same rule the anti-IDOR reads follow.
      expect(body, 'no settings key in an operator-facing message').to.not.contain('org.cap')
    })
  })

  // ── fieldSales ──────────────────────────────────────────────────────────────────────────────────

  it('fieldSales ON — outlets can be assigned', () => {
    cy.setCapability('fieldSales', true)
    assignOutlets(customerId).then((r) => {
      expect(String(r.body && r.body.status), `assigning must be allowed: ${JSON.stringify(r.body)}`)
        .to.not.eq('ERROR')
    })
  })

  it('fieldSales OFF — ⭐ the territory assignment is REFUSED', () => {
    /*
     * A counter-only retailer's owner has every privilege there is and still has no reps. An assignment made
     * anyway narrows what those outlets' own users can see, for a reason nothing on their screen explains.
     *
     * This endpoint answers in the monolith's GenericResponse envelope, so the refusal reads status:"ERROR"
     * rather than success:false — the two envelopes are why asserting on HTTP status is wrong for both.
     */
    cy.setCapability('fieldSales', false)
    assignOutlets(customerId).then((r) => {
      const body = JSON.stringify(r.body)
      expect(String(r.body && r.body.status), `the assignment must be REFUSED: ${body}`).to.eq('ERROR')
      expect(body, 'no settings key in an operator-facing message').to.not.contain('org.cap')
    })
  })
})
