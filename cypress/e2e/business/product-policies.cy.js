/**
 * C6 — per-product policy, and the two-level rule that governs it.
 *
 *     tenant capability   org.cap.serialTracking     may this shop use serial tracking at all?
 *     product policy      products.requires_serial   does THIS product require it?
 *     enforcement         capability AND policy
 *
 * One level is not enough, which is the whole reason this slice exists: a mobile shop sells handsets that are
 * IMEI-tracked AND chargers that are not, so a tenant-wide switch cannot express what the shop actually does.
 *
 * <h3>This also exercises C3c across a service boundary</h3>
 * catalog-service holds no settings store. It answers "does this tenant have serialTracking?" from the JWT
 * claim alone, via CurrentUser — so a refusal here proves the capability reached a service that has no way to
 * look it up, which is the property the whole JWT design was chosen for.
 */

const OWNER = 'owner.business@myplus.com'
const CAP = 'serialTracking'

/** Read a product back as the sell path sees it — the ProductRef, not the edit DTO. */
const refFor = (sku) => cy.request(`/lookupProduct?code=${encodeURIComponent(sku)}`).then((r) => r.body)

/**
 * Set the per-product tracking policy.
 *
 * ⚠ ASSERT ON THE ENVELOPE, NEVER ON THE HTTP STATUS.
 *
 * A refusal comes back as **200 with `success:false`**. That is deliberate and documented in `ProxyErrors`:
 * "a refusal is an ANSWER, not a failure" — the monolith carries the downstream service's sentence to the
 * person who can act on it instead of flattening it into a status code.
 *
 * The first version of this spec asserted `status !== 200` and failed against a refusal that was working
 * perfectly. Worse, the "can still clear" case asserted only `status === 200`, so it would have PASSED on a
 * refusal — the assertion could not tell the two outcomes apart in either direction.
 */
const setTracking = (id, flags) =>
  cy.request({
    method: 'POST',
    url: '/setProductTracking',
    form: true,
    failOnStatusCode: false,
    body: Object.assign({ id: id }, flags),
  })

/** The call was accepted. `success` is the field this envelope actually carries. */
const expectAccepted = (r, why) =>
  expect(r.body && r.body.success, `${why}: ${JSON.stringify(r.body)}`).to.not.eq(false)

/** The call was refused, and the message is fit for a shopkeeper. */
const expectRefused = (r, why) => {
  const body = JSON.stringify(r.body)
  expect(r.body && r.body.success, `${why}: ${body}`).to.eq(false)
  // Same rule the anti-IDOR reads follow: a refusal never describes the tenant's configuration.
  expect(body, 'the refusal must not name the settings key').to.not.contain('org.cap')
}

describe('C6 — per-product tracking policy', () => {
  let productId = null
  let sku = null

  before(() => {
    cy.loginAsOwner(OWNER)
    // ESTABLISH the state rather than inherit it: a capability left off by an earlier spec would make every
    // assertion below pass for the wrong reason.
    cy.setCapability(CAP, true)
    cy.seedProduct({ name: `C6_${Date.now()}` }).then((p) => {
      productId = p.productId
      sku = p.sku
    })
  })

  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  after(() => {
    // Leave no server state behind — a capability left off changes later specs.
    cy.loginAsOwner(OWNER)
    cy.setCapability(CAP, true)
  })

  // ── the default ─────────────────────────────────────────────────────────────────────────────────

  it('a product is untracked until somebody says otherwise', () => {
    /*
     * The migration promise. requires_serial defaults 0, so every product already in every tenant is
     * untracked on the deploy that introduces this. If it ever defaults true, every existing product in the
     * product silently starts demanding an IMEI at the till.
     */
    cy.setCapability(CAP, true)
    refFor(sku).then((ref) => {
      expect(ref.requiresSerial, 'a new product tracks no serial').to.not.eq(true)
      expect(ref.tracksBatch, 'and no batch').to.not.eq(true)
    })
  })

  // ── reachability ────────────────────────────────────────────────────────────────────────────────

  it('⭐ an owner can actually REACH the policy — the checkbox is on the product form', () => {
    /*
     * This case exists because C6 shipped without it and nobody would have noticed.
     *
     * The column, the endpoint, the guard and the API gate were all correct and all green — and there was no
     * checkbox anywhere in the product form, so no shopkeeper could set the policy at all. A capability with
     * no reachable control is the same "shipped unreachable" failure that hit C1 (a service nothing wired),
     * C3 (a catalog nothing registered) and the settings resolver before them.
     *
     * An API-driven gate cannot catch it: cy.request reaches the endpoint whether a UI exists or not. The
     * assertion has to be about the SCREEN.
     */
    cy.setCapability(CAP, true)
    cy.visit('/businessDashboard')
    cy.get('#prodRequiresSerial').should('exist').and('not.have.class', 'cap-off')
    // The row wrapping it is capability-gated too — if that were hidden, the box would be unreachable even
    // though the box itself is not marked.
    cy.get('[data-capability="serialTracking"]').should('exist').and('not.have.class', 'cap-off')
  })

  it('the control disappears for a tenant without the capability', () => {
    // The other half. Without this, a build that showed the checkbox to everyone would pass the case above,
    // and a hardware shop would be offered an IMEI setting it has no use for.
    cy.setCapability(CAP, false)
    cy.visit('/businessDashboard')
    cy.get('[data-capability="serialTracking"]').should('have.class', 'cap-off').and('not.be.visible')
  })

  // ── capability ON: the policy can be set ────────────────────────────────────────────────────────

  it('ON — a tenant with the capability can mark a product serial-tracked', () => {
    cy.setCapability(CAP, true)
    setTracking(productId, { requiresSerial: 'true' }).then((r) => {
      expectAccepted(r, 'a tenant with the capability may set the policy')
    })

    // Asserted on the REF, because that is the object the sell and purchase paths actually read. A DTO that
    // round-trips while the ref stays false would be a policy nobody enforces — the two builders in
    // ProductService are a standing drift risk exactly here.
    refFor(sku).then((ref) => {
      expect(ref.requiresSerial, 'the policy reaches the ref the tills read').to.eq(true)
    })
  })

  // ── capability OFF: the policy cannot be set ────────────────────────────────────────────────────

  it('OFF — ⭐ a tenant without the capability CANNOT set the policy', () => {
    /*
     * §4c's rule, and the reason it is a rule: without it, a hardware shop's admin could mark a product
     * serial-tracked and the tills would then demand an IMEI for a hammer, refusing sales for a reason
     * nothing on the product screen explains.
     *
     * The refusal comes from catalog-service, which has NO settings store — it reads the capability from the
     * JWT claim. So this is also the proof that C3c carries capabilities to a service that could not
     * otherwise know them.
     */
    /*
     * ESTABLISH a known-false starting state while the capability is still ON.
     *
     * Without this the assertion below is unprovable: the previous test left the flag TRUE, so "the refused
     * write was not applied" would read true whether the write was refused or applied. An after-state
     * assertion is only evidence when the before-state is the opposite.
     */
    cy.setCapability(CAP, true)
    setTracking(productId, { requiresSerial: 'false' }).then((r) => {
      expectAccepted(r, 'clear the flag first, so the refusal below is observable')
    })
    refFor(sku).then((ref) => {
      expect(ref.requiresSerial, 'precondition: the product starts untracked').to.not.eq(true)
    })

    cy.setCapability(CAP, false)

    setTracking(productId, { requiresSerial: 'true' }).then((r) => {
      expectRefused(r, 'setting the policy must be REFUSED')
    })

    // And the refusal is REAL: the stored policy did not change behind the message. A service that answered
    // "refused" and wrote anyway would pass every assertion above this line.
    refFor(sku).then((ref) => {
      expect(ref.requiresSerial, 'a refused write must not have been applied').to.not.eq(true)
    })
  })

  it('OFF — but turning a policy OFF is still allowed', () => {
    /*
     * The un-sticking rule. If withdrawing a capability also froze the flags already set, a product would be
     * stuck requiring a serial the tenant is no longer permitted to record — unsellable, with no way back
     * except a DBA. So the guard applies only to switching something ON.
     */
    cy.setCapability(CAP, false)
    setTracking(productId, { requiresSerial: 'false' }).then((r) => {
      expectAccepted(r, 'clearing a policy must stay possible')
    })
    refFor(sku).then((ref) => {
      expect(ref.requiresSerial, 'the product is free again').to.not.eq(true)
    })
  })

  it('OFF — an ordinary product edit is unaffected', () => {
    // The positive control. Without it, a build that refused EVERY write to this product would pass the two
    // cases above perfectly.
    cy.setCapability(CAP, false)
    setTracking(productId, {}).then((r) => {
      expectAccepted(r, 'a call that sets no policy is not a policy change')
    })
  })
})
