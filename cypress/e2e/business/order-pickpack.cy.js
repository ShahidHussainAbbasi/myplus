/**
 * OMS O7 D3 — the PACK workbench, and the two settings it makes honourable.
 * Design: microservices/docs/slices/oms-O5d-pick-pack.md · oms-O7-distribution-presales.md §11
 *
 * O5b made a dispatch recordable; it did not make it verifiable. The Ship form defaults to "everything
 * outstanding", so the fastest correct-LOOKING action is to accept the defaults whether or not that is what
 * physically went in the box — and the one error that matters, packing the WRONG GOODS, is invisible to every
 * guard O5b added, because they all check arithmetic.
 *
 * The case that carries the slice is **"scanning something that is not on this order is refused"**.
 *
 * ⚠️ Both settings default OFF and are restored to that state by this spec, because leaving `scanRequired` ON
 * would refuse every hand-typed dispatch in every OTHER order spec that runs after it.
 */
describe('OMS O7 D3 — a packer scans items into the box', () => {
  const run = String(Date.now()).slice(-6)
  let productId, otherId, orderId, lineId
  const SKU = 'PK' + run
  const OTHER_SKU = 'PKX' + run

  const setCfg = (key, value) => cy.request({
    method: 'POST', url: '/saveOrderConfig', form: true, failOnStatusCode: false, body: { key, value },
  })

  before(() => {
    cy.loginAsMarketplaceOwner()
    // The product that IS on the order, and one that is not — the second is the whole point of scanning.
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'PackProd_' + run, sku: SKU, barcode: SKU, sellingPrice: 20, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 50 }, failOnStatusCode: false,
      })
    })
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: 'PackOther_' + run, sku: OTHER_SKU, barcode: OTHER_SKU, sellingPrice: 15, taxRate: 0, unit: 'pcs' },
    }).then((r) => { otherId = r.body.data.id })
  })

  beforeEach(() => {
    cy.loginAsMarketplaceOwner()
    // Reset BOTH policies to their defaults at the START of every case, not only in after().
    //
    // These are per-tenant settings — global, persistent state. A case that fails before its trailing reset
    // (or a whole run that dies mid-suite) leaves them ON, and every later case then runs against a shop that
    // requires scanning or auto-dispatches. That is exactly what happened here: an earlier failed run left
    // scanRequired ON, and six cases failed for a reason that had nothing to do with what they test.
    // Establish the state you need; never inherit it.
    setCfg('order.pack.scanRequired', 'false')
    setCfg('order.pack.autoConfirm', 'false')
    // A confirmed order with 5 units outstanding — the state a packer actually meets.
    cy.request({
      method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: {
        customerName: 'PackOutlet ' + run + '-' + Date.now(), customerContact: '0300' + run,
        items: [{ productId, quantity: 5, price: 20, productName: 'PackProd_' + run }],
      },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      orderId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
        body: { id: orderId }, failOnStatusCode: false,
      })
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    // cy.then() so the URL is built when this RUNS, not when it is queued. Unwrapped, `orderId` is still
    // undefined at enqueue time (the .then above has not executed yet) and this fetches `?id=undefined`.
    cy.then(() => cy.request('/getOrder?id=' + orderId))
      .then((r) => { lineId = r.body.data.items[0].id })
  })

  after(() => {
    // Leave both OFF — the default, and what every other order spec assumes.
    cy.loginAsMarketplaceOwner()
    setCfg('order.pack.scanRequired', 'false')
    setCfg('order.pack.autoConfirm', 'false')
  })

  const openPack = () => {
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'openPackWorkbench')
    cy.window().then((w) => w.openPackWorkbench(orderId))
    cy.get('#PackDiv').should('be.visible')
    // The pick list has rendered...
    cy.get('#packBody tr').should('have.length.greaterThan', 0)
    // ...and the app's AJAX overlay has cleared. It is a real full-screen element (shown on ajaxStart, hidden
    // on ajaxStop), so typing into the scan box while it is up genuinely could not happen for a human either.
    // Asserting it rather than forcing the type: `{force:true}` would let this spec pass on a screen a packer
    // cannot actually use.
    cy.get('#appAjaxOverlay', { timeout: 15000 }).should('not.be.visible')
  }

  it('the pick list shows what is outstanding on this order', () => {
    openPack()
    cy.get('#packBody tr').should('have.length', 1)
    cy.get('#packBody tr td').eq(1).should('have.text', '5')     // to pick
    cy.get('#packConfirm').should('be.disabled')                  // nothing in the box yet
  })

  it('THE CASE — scanning something that is NOT on this order is refused', () => {
    // Right shelf, wrong order. Every O5b guard checks arithmetic and would have accepted this happily,
    // because the packer would simply have typed "5" against the correct line.
    openPack()
    cy.get('#packScan').type(OTHER_SKU + '{enter}')
    cy.get('#packMsg').should('be.visible').and('have.class', 'alert-danger')
    cy.get('#packBody tr td input.pack-qty').should('have.value', '0')
    cy.get('#packConfirm').should('be.disabled')
  })

  it('scanning past what is owed is refused — the box cannot hold more than the order', () => {
    openPack()
    cy.get('#packScan').type('5*' + SKU + '{enter}')              // the multiplier idiom, reused from the till
    cy.get('#packBody tr td input.pack-qty').should('have.value', '5')
    cy.get('#packScan').type(SKU + '{enter}')                     // one more than outstanding
    cy.get('#packMsg').should('have.class', 'alert-danger')
    cy.get('#packBody tr td input.pack-qty').should('have.value', '5')
  })

  it('a scanned parcel dispatches, and the order follows from what went out', () => {
    openPack()
    cy.get('#packScan').type('3*' + SKU + '{enter}')
    cy.get('#packConfirm').should('not.be.disabled').click()
    cy.request('/getOrder?id=' + orderId).then((r) => {
      expect(r.body.data.items[0].quantityShipped, 'three went out').to.eq(3)
      // O5b: the status is DERIVED from the lines, never typed.
      expect(r.body.data.fulfilmentStatus).to.eq('PARTIALLY_SHIPPED')
    })
  })

  it('typing a quantity marks the line UNVERIFIED — the system never claims a scan it did not make', () => {
    openPack()
    cy.get('#packBody input.pack-qty').clear().type('2')
    cy.get('#packBody tr').should('contain', 'typed')
    cy.get('#packConfirm').should('not.be.disabled')              // allowed while scanning is not required
  })

  it('scanRequired ON: a typed quantity cannot be dispatched — server-side, not just hidden', () => {
    // C2, both halves. The BUTTON is disabled, and the SERVER refuses the same request — otherwise this is a
    // hidden button, which is the thing O4 spent a slice removing.
    setCfg('order.pack.scanRequired', 'true')
    openPack()
    cy.get('#packBody input.pack-qty').clear().type('2')
    cy.get('#packBlocked').should('be.visible')
    cy.get('#packConfirm').should('be.disabled')

    cy.request({
      method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { id: orderId, lines: [{ orderItemId: lineId, quantity: 2 }], carrier: 'X' },
    }).then((s) => {
      expect(s.body.success, 'the endpoint refuses it too').to.not.eq(true)
      expect(JSON.stringify(s.body)).to.match(/scan/i)
    })
    setCfg('order.pack.scanRequired', 'false')
  })

  it('scanRequired ON: a SCANNED parcel still goes through', () => {
    // The positive control. Without it, the case above passes for a shop that simply cannot dispatch at all.
    setCfg('order.pack.scanRequired', 'true')
    openPack()
    cy.get('#packScan').type('2*' + SKU + '{enter}')
    cy.get('#packBlocked').should('not.be.visible')
    cy.get('#packConfirm').should('not.be.disabled').click()
    cy.request('/getOrder?id=' + orderId)
      .then((r) => expect(r.body.data.items[0].quantityShipped).to.eq(2))
    setCfg('order.pack.scanRequired', 'false')
  })

  it('autoConfirm ON: the parcel dispatches the moment the last unit is scanned', () => {
    // The setting that was read NOWHERE when O5d shipped it. It is now honoured on the path it governs —
    // asserted by the packer never touching Confirm.
    setCfg('order.pack.autoConfirm', 'true')
    openPack()
    cy.get('#packScan').type('5*' + SKU + '{enter}')
    // No click on #packConfirm anywhere in this case.
    cy.request('/getOrder?id=' + orderId).then((r) => {
      expect(r.body.data.items[0].quantityShipped, 'dispatched without a confirm click').to.eq(5)
      expect(r.body.data.fulfilmentStatus).to.eq('SHIPPED')
    })
    setCfg('order.pack.autoConfirm', 'false')
  })

  it('autoConfirm OFF (default): a fully-packed parcel WAITS for a human', () => {
    // The other half of C2 — and the reason the default is off: somebody gets a final look in the box.
    openPack()
    cy.get('#packScan').type('5*' + SKU + '{enter}')
    cy.get('#packConfirm').should('not.be.disabled')
    cy.request('/getOrder?id=' + orderId)
      .then((r) => expect(r.body.data.items[0].quantityShipped, 'nothing has gone yet').to.eq(0))
  })
})
