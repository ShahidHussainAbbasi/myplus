/**
 * Product screen — Customer-parity refactor. The Product master now renders through the shared loadDataTable()
 * DataTable path (like Customer): /getUserProduct returns {status,collection}; the form supports add + edit(update);
 * Delete deactivates (drops off the active list); per-row Add-stock (addstkbtn_<id>) is preserved. Run headed.
 */
describe('Product screen — Customer parity (list/add/edit/deactivate + add-stock)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('getUserProduct returns a {status,collection} list with the seeded product (active only)', () => {
    cy.seedProduct({ name: 'PU_' + Date.now(), sellingPrice: 12, taxRate: 5 }).then(({ productId, name }) => {
      cy.request('/getUserProduct?q=-1').then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        const mine = (r.body.collection || []).find((p) => p.id === productId)
        expect(mine, 'seeded product in collection').to.exist
        expect(mine.name).to.eq(name)
        expect(mine).to.have.property('userId')   // loadDataTable bookkeeping
      })
    })
  })

  it('updateProduct edits the master; getCatalogProduct reflects it', () => {
    cy.seedProduct({ name: 'PE_' + Date.now(), sellingPrice: 10 }).then(({ productId, name, sku }) => {
      cy.request({
        method: 'POST', url: '/updateProduct', headers: { 'Content-Type': 'application/json' },
        body: { id: productId, name: name + '_edited', sku, sellingPrice: 33, taxRate: 0, unit: 'box', categoryName: 'General' },
        failOnStatusCode: false,
      }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

      cy.request('/getCatalogProduct?id=' + productId).then((r) => {
        expect(r.body.data.name).to.eq(name + '_edited')
        expect(Number(r.body.data.sellingPrice)).to.eq(33)
      })
    })
  })

  it('deactivateProduct removes the product from the active list', () => {
    cy.seedProduct({ name: 'PD_' + Date.now() }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' },
        body: { checked: String(productId) }, failOnStatusCode: false,
      }).then((r) => expect(r.body.success).to.eq(true))

      cy.request('/getUserProduct?q=-1').then((r) => {
        const still = (r.body.collection || []).find((p) => p.id === productId)
        expect(still, 'deactivated product is gone from the active list').to.not.exist
      })
    })
  })

  it('category round-trips on save + update (find-or-create by name)', () => {
    const cat = 'Cat_' + Date.now()
    cy.seedProduct({ name: 'PC_' + Date.now(), category: cat }).then(({ productId, sku }) => {
      // after create, the free-text category name persists on the product + the list column
      cy.request('/getCatalogProduct?id=' + productId).then((r) => {
        expect(r.body.data.categoryName, 'category persisted on create').to.eq(cat)
      })
      cy.request('/getUserProduct?q=-1').then((r) => {
        const mine = (r.body.collection || []).find((p) => p.id === productId)
        expect(mine.categoryName, 'category shows in the list').to.eq(cat)
      })
      // update to a new category name → also round-trips (so the edit form repopulates it)
      const cat2 = 'Cat2_' + Date.now()
      cy.request({
        method: 'POST', url: '/updateProduct', headers: { 'Content-Type': 'application/json' },
        body: { id: productId, name: 'PC_up', sku, sellingPrice: 5, taxRate: 0, unit: 'pcs', categoryName: cat2 },
        failOnStatusCode: false,
      }).then((r) => expect(r.body.success).to.eq(true))
      cy.request('/getCatalogProduct?id=' + productId).then((r) => {
        expect(r.body.data.categoryName, 'category persisted on update').to.eq(cat2)
      })
    })
  })

  it('category dropdown: quick-add a category, then create a product referencing it by id', () => {
    const cname = 'DDCat_' + Date.now()
    cy.request({
      method: 'POST', url: '/addCategory', headers: { 'Content-Type': 'application/json' },
      body: { name: cname }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const catId = r.body.data.id
      // it appears in the dropdown source
      cy.request('/getUserCategories').then((g) => {
        expect(g.body.success).to.eq(true)
        expect((g.body.categories || []).some((c) => c.id === catId && c.name === cname)).to.be.true
      })
      // a product created with categoryId gets that category name back (dropdown path)
      cy.request({
        method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
        body: { name: 'PCid_' + Date.now(), sku: 'PCID' + Date.now(), sellingPrice: 3, taxRate: 0, unit: 'pcs', categoryId: catId },
        failOnStatusCode: false,
      }).then((p) => {
        expect(p.body.success).to.eq(true)
        cy.request('/getCatalogProduct?id=' + p.body.data.id).then((gp) => {
          expect(gp.body.data.categoryId).to.eq(catId)
          expect(gp.body.data.categoryName).to.eq(cname)
        })
      })
    })
  })

  it('renders #tableProduct as a DataTable with the product row + add-stock control', () => {
    cy.seedProduct({ name: 'PT_' + Date.now(), stock: 7 }).then(({ productId }) => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showProducts())
      cy.get('#ProductDiv').should('be.visible')
      // DataTable render: the shared path builds the toolbar (length select) + the seeded row's add-stock button.
      cy.get('select[name="tableProduct_length"]', { timeout: 10000 }).should('exist')
      cy.get('#addstkbtn_' + productId, { timeout: 10000 }).should('exist')
      cy.get('#lessstkbtn_' + productId).should('exist')   // correct/reduce control
      cy.get('#stk_' + productId).should('exist')
    })
  })

  it('adjustProductStock decreases on-hand and refuses to go below zero', () => {
    cy.seedProduct({ name: 'PA_' + Date.now(), stock: 10 }).then(({ productId }) => {
      // decrease 4 → on-hand 6
      cy.request({
        method: 'POST', url: '/adjustProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, adjustmentType: 'DECREASE', quantity: 4, reason: 'cypress correction' }, failOnStatusCode: false,
      }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
      cy.request('/productStock?productId=' + productId).then((r) => expect(Number(r.body.stock)).to.eq(6))

      // decreasing beyond on-hand is rejected (guard) and leaves on-hand unchanged
      cy.request({
        method: 'POST', url: '/adjustProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, adjustmentType: 'DECREASE', quantity: 999, reason: 'cypress over-decrease' }, failOnStatusCode: false,
      }).then((r) => expect(r.body.success).to.not.eq(true))
      cy.request('/productStock?productId=' + productId).then((r) => expect(Number(r.body.stock)).to.eq(6))
    })
  })

  it('re-prices the product on receive (purchase sell rate → product sellingPrice), guarded', () => {
    cy.seedProduct({ name: 'PR_' + Date.now(), sellingPrice: 20 }).then(({ productId }) => {
      // a purchase carrying a new sell rate (35) re-prices the master
      cy.request({
        method: 'POST', url: '/addPurchase', form: true,
        body: { productId, quantity: 3, purchaseRate: 10, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 35, totalAmount: 30, netAmount: 30, purchaseInvoiceNo: 'RP-' + Date.now() },
        failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
      cy.request('/getCatalogProduct?id=' + productId).then((r) => expect(Number(r.body.data.sellingPrice)).to.eq(35))

      // GUARD: a purchase with sell rate 0 must NOT wipe the price (stays 35)
      cy.request({
        method: 'POST', url: '/addPurchase', form: true,
        body: { productId, quantity: 1, purchaseRate: 10, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 0, totalAmount: 10, netAmount: 10, purchaseInvoiceNo: 'RP0-' + Date.now() },
        failOnStatusCode: false,
      }).then((r) => expect(r.body.status).to.eq('SUCCESS'))
      cy.request('/getCatalogProduct?id=' + productId).then((r) => expect(Number(r.body.data.sellingPrice)).to.eq(35))
    })
  })

  it('editing a purchase reconciles inventory by the DELTA, keeping on-hand AND batches (sellable) in sync', () => {
    cy.seedProduct({ name: 'PUE_' + Date.now() }).then(({ productId }) => {
      const inv = 'PUE-' + Date.now()
      // NOTE: deliberately no batchNo — the reported bug: reconcile moved on-hand but not the (batch) sellable.
      const body = (qty, total) => ({
        productId, quantity: qty, purchaseRate: 10, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 20,
        totalAmount: total, netAmount: total, purchaseInvoiceNo: inv,
      })
      // master data (inventory) must agree on both metrics whichever side edited it
      const expectStock = (n) => {
        cy.request('/productStock?productId=' + productId).then((r) => expect(Number(r.body.stock), 'on-hand').to.eq(n))
        cy.request('/productSellable?productId=' + productId).then((r) => expect(Number(r.body.sellable), 'sellable (batches)').to.eq(n))
      }
      // receive 9 → on-hand 9, sellable 9
      cy.request({ method: 'POST', url: '/addPurchase', form: true, body: body(9, 90), failOnStatusCode: false })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
      expectStock(9)

      cy.request('/getUserPurchase').then((r) => {
        const list = r.body.collection || r.body.data || []
        const mine = list.find((p) => p.productId === productId)
        expect(mine, 'seeded purchase').to.exist
        const purchaseId = mine.purchaseId
        // edit 9 → 5 : BOTH on-hand and sellable DROP by 4 → 5 (not re-imported to 14, not level-only)
        cy.request({ method: 'POST', url: '/updatePurchase', form: true, body: { ...body(5, 50), purchaseId }, failOnStatusCode: false })
          .then((r2) => expect(r2.body.status, JSON.stringify(r2.body)).to.eq('SUCCESS'))
        expectStock(5)
        // increase 5 → 12 : BOTH rise by 7 → 12
        cy.request({ method: 'POST', url: '/updatePurchase', form: true, body: { ...body(12, 120), purchaseId }, failOnStatusCode: false })
          .then((r3) => expect(r3.body.status).to.eq('SUCCESS'))
        expectStock(12)
      })
    })
  })

  it('records the actual SOLD rate + the catalog price snapshot on a sale', () => {
    cy.seedProduct({ name: 'SP_' + Date.now(), sellingPrice: 20, stock: 10 }).then(({ productId }) => {
      // sell 1 at an OVERRIDDEN rate of 25 (catalog master is 20)
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'SPCust_' + Date.now(), contact: '0300SP', paidAmount: 25, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 25, totalAmount: 25, netAmount: 25 }],
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      // the line records sellRate=25 (what it sold at) + catalogPrice=20 (master at sale time)
      cy.request('/getUserSell').then((r) => {
        const list = r.body.collection || r.body.data || []
        const mine = list.find((s) => s.productId === productId)
        expect(mine, 'sale line for the seeded product').to.exist
        expect(Number(mine.sellRate), 'sold rate').to.eq(25)
        expect(Number(mine.catalogPrice), 'catalog price snapshot').to.eq(20)
      })
    })
  })

  it('on-hand reports SELLABLE and flags EXPIRED batches; sale follows sellable, not physical', () => {
    cy.seedProduct({ name: 'EX_' + Date.now() }).then(({ productId }) => {
      // stock a batch that is ALREADY expired → physically present but not sellable (G1)
      cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 5, batchNo: 'OLD', expiryDate: '2000-01-01' }, failOnStatusCode: false,
      }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

      // the screen's on-hand feed splits it: sellable 0, expired 5
      cy.request('/productStockLevels').then((r) => {
        const d = (r.body.levels || {})[productId]
        expect(d, 'detail entry for product').to.exist
        expect(Number(d.sellable), 'expired batch is NOT sellable').to.eq(0)
        expect(Number(d.expired), 'expired qty surfaced for the badge').to.eq(5)
      })
      // a sale is correctly refused — nothing sellable (this is the product-6 case) — with a FRIENDLY message
      // (name-resolved "not enough sellable stock"), not the generic "unexpected error".
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: { customer: { name: 'EXc_' + Date.now(), contact: '0300EX', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 10, totalAmount: 10, netAmount: 10 }] }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status).to.not.eq('SUCCESS')
        expect(r.body.message, 'friendly sellable message').to.match(/sellable/i)
        expect(r.body.message, 'not the generic swallow').to.not.match(/unexpected error/i)
      })

      // /productSellable (the sell-form feed) reports the same split for a single product
      cy.request('/productSellable?productId=' + productId).then((r) => {
        expect(r.body.success).to.eq(true)
        expect(Number(r.body.sellable)).to.eq(0)
        expect(Number(r.body.expired)).to.eq(5)
      })

      // add a FRESH (future-expiry) batch → sellable rises, expired unchanged, sale now succeeds
      cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 4, batchNo: 'NEW', expiryDate: '2099-12-31' }, failOnStatusCode: false,
      }).then((r) => expect(r.body.success).to.eq(true))
      cy.request('/productStockLevels').then((r) => {
        const d = (r.body.levels || {})[productId]
        expect(Number(d.sellable), 'fresh batch is sellable').to.eq(4)
        expect(Number(d.expired), 'expired still flagged').to.eq(5)
      })
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: { customer: { name: 'EXok_' + Date.now(), contact: '0300OK', paidAmount: 10, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 10, totalAmount: 10, netAmount: 10 }] }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    })
  })

  it('reactivate: deactivated product is hidden by default, shown with includeInactive, then reactivated', () => {
    cy.seedProduct({ name: 'RA_' + Date.now() }).then(({ productId }) => {
      // deactivate
      cy.request({ method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' }, body: { checked: String(productId) }, failOnStatusCode: false })
        .then((r) => expect(r.body.success).to.eq(true))
      // hidden from the default list
      cy.request('/getUserProduct?q=-1').then((r) => expect((r.body.collection || []).some((p) => p.id === productId)).to.be.false)
      // visible with the "Show inactive" toggle, flagged inactive
      cy.request('/getUserProduct?q=-1&includeInactive=true').then((r) => {
        const mine = (r.body.collection || []).find((p) => p.id === productId)
        expect(mine, 'inactive product visible with includeInactive').to.exist
        expect(mine.isActive).to.eq(false)
      })
      // reactivate → active + back in the default list
      cy.request({ method: 'POST', url: '/activateProduct', headers: { 'Content-Type': 'application/json' }, body: { id: productId }, failOnStatusCode: false })
        .then((r) => expect(r.body.success).to.eq(true))
      cy.request('/getUserProduct?q=-1').then((r) => expect((r.body.collection || []).some((p) => p.id === productId)).to.be.true)
    })
  })

  it('New opens the Product form modal; selecting a row shows the bulk-action bar', () => {
    cy.seedProduct({ name: 'PM_' + Date.now(), stock: 3 }).then(({ productId }) => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showProducts())
      cy.get('#ProductModal').should('not.have.class', 'open')
      // + New Product opens the form modal
      cy.get('#newProduct').click()
      cy.get('#ProductModal').should('have.class', 'open')
      cy.get('#prodName').should('be.visible')
      cy.get('#ProductModal .crud-x').click()
      cy.get('#ProductModal').should('not.have.class', 'open')
      // ticking a row reveals the contextual bulk-action bar
      cy.get('#addstkbtn_' + productId, { timeout: 10000 }).should('exist')
      cy.get('#tableProduct tbody').find("input[type='checkbox']").first().check()
      cy.get('#bulkBarProduct').should('be.visible').and('contain', 'selected')
    })
  })
})
