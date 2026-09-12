/**
 * M1 (slice 42) — catalog Product master CRUD from the monolith (Item→Product convergence, strangler step).
 * Additive: the Item screen still works; this proves the single product master is registrable + listable via the
 * existing catalog-service through the monolith. Run headed.
 */
describe('Catalog Product master (M1)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('registers a catalog Product and lists it', () => {
    const name = 'Prod_' + Date.now()
    let productId

    cy.request({
      method: 'POST', url: '/addProduct',
      body: { name: name, sku: 'SKU' + Date.now(), sellingPrice: 9.5, taxRate: 17, unit: 'pcs', categoryName: 'General' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data).to.have.property('id')
      productId = r.body.data.id
    })

    // Slice 106: was `?size=500`, which assumed the whole catalog fitted in one page. On a dev DB that has
    // accumulated thousands of products from months of runs, the row just created has the HIGHEST id and
    // therefore lands on the LAST page — so the assertion failed while creation was working perfectly.
    // Sorting newest-first makes this independent of how big the catalog has grown.
    cy.request('/catalogProducts?size=50&sort=id,desc').then((r) => {
      expect(r.body.success).to.eq(true)
      const content = (r.body.data && r.body.data.content) ? r.body.data.content : []
      const mine = content.find((p) => p.id === productId)
      expect(mine, 'product appears in the catalog list (newest page)').to.exist
      expect(mine.name).to.eq(name)
    })
  })

  it('Product master screen renders', () => {
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showProducts')
    cy.window().then((w) => w.showProducts())
    cy.get('#ProductDiv').should('be.visible')
    // The Product form lives in a modal now — open it before asserting its fields render.
    cy.window().then((w) => w.newProduct())
    cy.get('#ProductModal').should('have.class', 'open')
    cy.get('#prodName').should('be.visible')
  })

  // The duplicate-SKU 409 from catalog-service must reach the user. Previously the message was written to
  // #globalError, which sits behind the fixed modal overlay → invisible. Now it surfaces as a toast (and the
  // client pre-check blocks it before submit). Either way the user sees "already …" and the modal stays open.
  it('duplicate SKU is rejected with a visible error and the modal stays open', () => {
    const sku = 'DUP' + Date.now()

    // The proxy relays the catalog 409 as a friendly {success:false, message} body (HTTP 200), not a 5xx.
    cy.request({
      method: 'POST', url: '/addProduct',
      body: { name: 'Seed_' + Date.now(), sku: sku, sellingPrice: 1, taxRate: 0, unit: 'pcs', categoryName: 'General' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).its('body.success').should('eq', true)

    cy.request({
      method: 'POST', url: '/addProduct',
      body: { name: 'Dupe_' + Date.now(), sku: sku, sellingPrice: 1, taxRate: 0, unit: 'pcs', categoryName: 'General' },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.success).to.eq(false)
      expect(String(r.body.message).toLowerCase()).to.contain('already')
    })

    // UI: entering the same SKU in the modal shows a visible error toast and does NOT close the modal.
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showProducts())
    cy.get('#ProductDiv').should('be.visible')
    cy.window().then((w) => w.newProduct())
    cy.get('#ProductModal').should('have.class', 'open')
    /*
     * ⭐ GETTING TO ONE FIELD IN THIS MODAL TOOK FOUR SEPARATE WAITS, each found by a different red run
     * with a different Cypress message. None of them substitutes for another, so none can be dropped:
     *
     *   1. `.open` is added when the transition STARTS       → "currently animating"
     *      Waiting on the class alone races the modal sliding in.
     *
     *   2. VISIBLE IS NOT REACHABLE                          → "covered by another element"
     *      The shared overlay (#appAjaxOverlay) sits on top: the modal's own loads are still in flight
     *      when it opens, and jQuery's ajaxStart raises that overlay over the whole page. Cypress names
     *      it as a layout fault; it is a race.
     *
     *   3. ONE SAMPLE OF THE OVERLAY IS NOT ENOUGH — IT COMES BACK.
     *      A bare `should('not.be.visible')` passed, and the next command still failed with the overlay
     *      `class="show"`. The loads arrive in WAVES: one finishes, ajaxStop drops the overlay, a success
     *      handler starts the next request, ajaxStart raises it again — the assertion caught the gap
     *      between waves. waitForAppReady cannot be fooled by a gap: it needs jQuery.active to stay at 0
     *      for 300ms, longer than any gap between chained requests, and re-checks the overlay after.
     *
     *   4. QUIET IS NOT STILL                                → "currently animating", again
     *      Uncovered and visible, the field is STILL MOVING. The overlay hides when the last request
     *      completes, and that same ajaxComplete is when searchable-selects.js rebuilds every picker in
     *      the modal — bootstrap-select swaps each <select> for a taller button+dropdown, the content
     *      grows, and a vertically-centred modal slides. The field drifts AFTER the page looks ready.
     *
     * ⚠ NOT {force:true} and NOT waitForAnimations:false, at any of the four. Both would type into a
     * field a real operator cannot hit yet — so a modal that genuinely jitters under the cursor, or one
     * left under a stuck spinner, would pass this gate and fail every day in the shop.
     */
    cy.get('#prodName').should('be.visible')
    cy.waitForAppReady()
    cy.settled('#prodName')
    cy.get('#prodName').type('Another_' + Date.now())
    cy.get('#prodSku').type(sku).blur()
    cy.get('#addProduct').click()
    cy.get('#formErrorToast', { timeout: 10000 }).should('be.visible')
      .invoke('text').then((t) => expect(t.toLowerCase()).to.contain('already'))
    cy.get('#ProductModal').should('have.class', 'open')   // save was blocked, form not lost
  })
})
