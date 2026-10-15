/**
 * MKT-3c — a seller sends stock to the MaxTheService warehouse, and MaxTheService buys it. Source R20.4 (platform stock:
 * transfer), R4.2 (the warehouse owns what it holds). Contract: microservices/docs/slices/mkt-3c-stock-transfer.md
 *
 * Received, it is two documents in two tenants' books: a sale (invoice) in the seller's, a purchase owed to the seller
 * in the warehouse's; the IMEIs leave one shelf and arrive on the other. Cancelled or not taken, nothing is written.
 * Each run lists its own phone, so the two sellers share one canonical product only within the run.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, ok, data, list, msg, post, get, seedPolicies, publishOffer, makeSeller, openMarketplace } =
  require('./mkt-helpers')

const WAREHOUSE = 'owner.warehouse@myplus.com'
const T = { offer: '/mkt/transferOffer', cancel: '/mkt/transferCancel', reject: '/mkt/transferReject', receive: '/mkt/transferReceive' }
const SHOP = 'Shahzad Mobile Shop'

gate('3c')('MKT-3c — stock sent to the MaxTheService warehouse, which MaxTheService buys', () => {
  const run = uniq()
  const IMEI = (k) => `35${String(run).slice(-9)}${String(k).padStart(4, '0')}`
  const S = {}

  const as = (email) => cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
  const view = () => get('/mkt/transfers').then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  const row = (no) => `#mktTransfers tr[data-transfer-no="${no}"]`
  const shelf = (productId) => get(`/serialUnits?productId=${productId}`).then((r) => {
    const units = (r.body && r.body.collection) || []
    return (Array.isArray(units) ? units : []).map((u) => u.serialNo || u.serial || u)
  })
  /** Offer through the real form: product, quantity, price a unit. Yields the new transfer number. */
  const sendInUi = (qty, price, note) => {
    as(SELLER_A)
    openMarketplace()
    cy.get('#mktTransferBox').should('be.visible')
    cy.get('#mktTrProduct').select(String(S.mkt), { force: true })
    cy.get('#mktTrQty').clear().type(String(qty))
    cy.get('#mktTrPrice').clear().type(String(price))
    if (note) cy.get('#mktTrNote').clear().type(note)
    cy.get('#mktTrSend').click()
    cy.get('#mktTransferMsg').should('contain', 'Sent to MaxTheService as ST-')
    return cy.get('#mktTransferMsg').invoke('text').then((t) => t.match(/ST-\d+/)[0])
  }

  before(() => {
    seedPolicies(run).then((p) => {
      makeSeller(WAREHOUSE, 'Central Warehouse')
      cy.loginAsOperator()
      cy.orgOf(WAREHOUSE).then((o) => post('/platform/mkt/warehouse', { organizationId: o.id }))
      // the warehouse starts without IMEI tracking, as a new business does: 3c-05 proves a Receive its books refuse,
      // 3c-06 switches it on (an earlier run may have left it on)
      as(WAREHOUSE)
      cy.setCapability('serialTracking', false)
      cy.loginAsOperator()
      cy.then(() => publishOffer(WAREHOUSE, { run, price: 52000, qty: 1, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))
        .then((o) => { S.mkt = o.mktProductId; S.whProduct = o.sourceProductId })
      cy.then(() => publishOffer(SELLER_A, { run, price: 52000, qty: 1, mktProductId: S.mkt, warrantyPolicyId: p.warranty,
        returnPolicyId: p.returns })).then((o) => { S.shopProduct = o.sourceProductId })
      // a product only the shop sells: MaxTheService does not carry it
      cy.then(() => publishOffer(SELLER_A, { run: `${run}x`, price: 30000, qty: 1, warrantyPolicyId: p.warranty,
        returnPolicyId: p.returns })).then((o) => { S.notCarried = o.mktProductId })
      // the shop's phones: serial-tracked, no expiry date (Products → edit), and two arrive on a purchase with their IMEIs.
      // The warehouse's copy is set up in 3c-06, AFTER the Receive 3c-05 proves its books refuse.
      as(SELLER_A)
      cy.then(() => cy.request({ method: 'POST', url: '/setProductTracking', form: true, body: { id: S.shopProduct, requiresSerial: true, noExpiry: true } }))
        .then((r) => expect(r.body && r.body.success !== false, JSON.stringify(r.body)).to.eq(true))
      cy.then(() => cy.request({ method: 'POST', url: '/addPurchase', form: true, body: { productId: S.shopProduct, quantity: 2,
        purchaseRate: 38000, 'stock.bpurchaseRate': 38000, 'stock.bsellRate': 52000, totalAmount: 76000, netAmount: 76000,
        paidAmount: 76000, purchaseInvoiceNo: `G3C-${run}`, serials: `${IMEI(1)},${IMEI(2)}`, conditionGrade: 'NEW' } }))
        .then((r) => expect(r.body && r.body.status !== 'ERROR' && r.body.success !== false, JSON.stringify(r.body)).to.eq(true))
    })
  })

  it('MKT-3c-01 [MKT-R20.4] the seller sends a product MaxTheService carries, in the real form; one it does not carry is refused', () => {
    as(SELLER_A)
    cy.then(() => view()).then((v) => {
      expect(v.warehouse).to.eq(false)
      expect(v.products.map((p) => p.mktProductId), 'carried by MaxTheService').to.include(S.mkt)
      expect(v.products.map((p) => p.mktProductId), 'not carried').not.to.include(S.notCarried)
    })
    cy.then(() => post(T.offer, { mktProductId: S.notCarried, quantity: 1, unitPrice: 30000 }))
      .then((r) => expect(msg(r.body)).to.eq('MaxTheService does not carry this product yet, so it cannot receive it. Ask MaxTheService to add it first.'))
    cy.then(() => post(T.offer, { mktProductId: S.mkt, quantity: 0, unitPrice: 40000 }))
      .then((r) => expect(msg(r.body)).to.match(/^Enter how many you are sending/))
    sendInUi(2, 40000, 'two boxed A32s').then((no) => { S.main = no })
    cy.then(() => cy.get(row(S.main)).should('contain', 'Sent, waiting for the warehouse').and('contain', '80,000')
      .find('.mkt-tr-cancel').should('be.visible'))
  })

  it('MKT-3c-02 [MKT-R4.2] only the two parties can act on it: another seller and the sender cannot receive it', () => {
    as(SELLER_B)
    cy.then(() => post(T.cancel, { transferNo: S.main })).then((r) => expect(msg(r.body)).to.eq('Transfer not found.'))
    as(SELLER_A)
    cy.then(() => post(T.receive, { transferNo: S.main, serials: [IMEI(1), IMEI(2)] }))
      .then((r) => expect(msg(r.body)).to.eq('Transfer not found.'))
  })

  it('MKT-3c-03 [MKT-R20.4] the seller cancels one still waiting; the warehouse can no longer receive it (real UI)', () => {
    sendInUi(1, 41000).then((no) => { S.cancelled = no })
    cy.then(() => cy.get(row(S.cancelled)).find('.mkt-tr-cancel').click())
    cy.then(() => cy.get(row(S.cancelled)).should('contain', 'Cancelled').find('button').should('not.exist'))
    as(WAREHOUSE)
    cy.then(() => post(T.receive, { transferNo: S.cancelled, serials: [IMEI(1)] }))
      .then((r) => expect(msg(r.body)).to.eq('This transfer is already cancelled.'))
  })

  it('MKT-3c-04 [MKT-R20.4] the warehouse says why it does not take one; the seller reads the reason (real UI)', () => {
    sendInUi(1, 99000).then((no) => { S.rejected = no })
    as(WAREHOUSE)
    openMarketplace()
    cy.get('#mktTransferTitle').should('have.text', 'Stock sent to MaxTheService')
    cy.then(() => {
      cy.get(row(S.rejected)).should('contain', `From ${SHOP}`)
      cy.get(row(S.rejected)).find('.mkt-tr-reject').click()
      cy.get('#mktTransferMsg').should('have.text', 'Tell the seller why it is not taken.')
      cy.get(row(S.rejected)).find('.mkt-tr-reason').type('Price is above what we pay for an A32')
      cy.get(row(S.rejected)).find('.mkt-tr-reject').click()
      cy.get('#mktTransferMsg').should('have.text', 'Not taken. The seller sees your reason.')
    })
    as(SELLER_A)
    openMarketplace()
    cy.then(() => cy.get(row(S.rejected)).should('contain', 'Not taken').and('contain', 'Price is above what we pay for an A32'))
  })

  it('MKT-3c-05 [MKT-R20.4] a purchase the warehouse\'s books refuse leaves nothing behind: the seller\'s sale is voided, the phones stay with the shop', () => {
    as(WAREHOUSE)
    openMarketplace()
    cy.then(() => {
      cy.get(row(S.main)).find('.mkt-tr-receive').click()
      cy.get('#mktTransferMsg').should('contain', 'Scan the serial number (IMEI) of each unit received: 2 for')
      cy.get(row(S.main)).find('.mkt-tr-serials').type(`${IMEI(1)}\n${IMEI(2)}`)
      cy.get(row(S.main)).find('.mkt-tr-receive').click()
      // the warehouse has not switched on IMEI tracking yet, so its books cannot take phones with serial numbers
      cy.get('#mktTransferMsg').should('have.text', 'The warehouse\'s purchase could not be recorded, so nothing was received and the seller\'s sale was cancelled. Serial / IMEI tracking is not switched on for your business, so units with serial numbers cannot be received.')
      cy.get(row(S.main)).should('contain', 'Sent, waiting for the warehouse')
    })
    as(SELLER_A)
    cy.then(() => shelf(S.shopProduct)).then((s) => expect(s, 'back on the shop\'s shelf').to.include.members([IMEI(1), IMEI(2)]))
  })

  it('MKT-3c-06 [MKT-R20.4] [MKT-R4.2] received with its IMEIs: a sale in the seller\'s books, a purchase owed to the seller in the warehouse\'s, the phones move shelves (real UI)', () => {
    // the warehouse sets its phone up as a phone shop does: serial numbers tracked (its retail shape keeps no expiry dates)
    cy.loginAsOperator()
    cy.setEntitlement(WAREHOUSE, 'serialTracking', 'ACTIVE', 'mkt-3c gate')   // the operator adds it to the warehouse's plan
    as(WAREHOUSE)
    cy.setCapability('serialTracking', true)
    cy.loginAs(WAREHOUSE, 'Demo@2025!', '/getBusinessDashboardStats', 'serial-on')
    cy.then(() => cy.request({ method: 'POST', url: '/setProductTracking', form: true, body: { id: S.whProduct, requiresSerial: true } }))
      .then((r) => expect(r.body && r.body.success !== false, JSON.stringify(r.body)).to.eq(true))
    openMarketplace()
    cy.then(() => {
      cy.get(row(S.main)).find('.mkt-tr-serials').type(`${IMEI(1)}\n${IMEI(2)}`)
      cy.get(row(S.main)).find('.mkt-tr-receive').click()
      cy.get('#mktTransferMsg').should('have.text', `Received. It is in your stock, and you owe ${SHOP} Rs 80,000.00.`)
      cy.get(row(S.main)).should('contain', 'Received').and('contain', 'INV-')
    })
    cy.then(() => view()).then((v) => { S.invoice = v.transfers.find((t) => t.transferNo === S.main).saleInvoiceNo })
    cy.then(() => shelf(S.whProduct)).then((s) => expect(s, 'on the warehouse\'s shelf').to.include.members([IMEI(1), IMEI(2)]))
    cy.then(() => get('/getAllPurchase')).then((r) => {
      const rows = (r.body && (r.body.collection || r.body.data)) || []
      const p = (Array.isArray(rows) ? rows : []).find((x) => x.purchaseInvoiceNo === S.invoice)
      expect(p, `the warehouse's purchase, bill ${S.invoice}`).to.exist
      expect(Number(p.totalAmount), '2 x Rs 40,000').to.eq(80000)
      expect(Number(p.paidAmount), 'owed to the shop, nothing paid').to.eq(0)
      expect(Number(p.quantity)).to.eq(2)
      S.vendorId = p.venderId
    })
    // the supplier on that bill is the shop, by its trading name, in the warehouse's own supplier list
    cy.then(() => get('/getAllVender')).then((r) => {
      const rows = (r.body && (r.body.collection || r.body.data)) || []
      const v = (Array.isArray(rows) ? rows : []).find((x) => (x.venderId || x.id) === S.vendorId)
      expect(v, `supplier ${S.vendorId}`).to.exist
      expect(v.name, 'bought from the shop').to.eq(SHOP)
    })
    cy.then(() => post(T.receive, { transferNo: S.main, serials: [IMEI(1), IMEI(2)] }))
      .then((r) => expect(msg(r.body)).to.eq('This transfer is already received.'))
    as(SELLER_A)
    cy.then(() => shelf(S.shopProduct)).then((s) => expect(s, 'gone from the shop\'s shelf').not.to.include.members([IMEI(1)]))
    openMarketplace()
    cy.then(() => cy.get(row(S.main)).should('contain', 'Received').and('contain', S.invoice))
  })
})
