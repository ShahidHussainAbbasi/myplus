/**
 * Sell edit — end-to-end (Phases 1 & 2). Steps are written out explicitly in each test for review.
 *
 *  P1: a sale persists its invoice link (customer_history_id); getSellInvoice loads the full invoice.
 *  P2: clicking a row in the Sell report (tableSell) loads THAT invoice back into the cart (tablesi /
 *      iDiv) + the Sell form, in an "editing INV-xxxx" state. Saving then routes to updateSell (P3).
 *
 * Tenant-scoped via demo.business. Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/sell-edit.cy.js
 */

function rows(res) { return res.body.collection || res.body.data || [] }

// Pull the INV-###### invoice number out of an addSell response (object field or success message).
const invNo = (body) => {
  const fromObj = (body && typeof body.object === 'string') ? body.object : null
  if (fromObj && /^INV-\d{6}$/.test(fromObj)) return fromObj
  const m = ((fromObj || '') + ' ' + (body && body.message || '')).match(/INV-\d{6}/)
  return m ? m[0] : null
}

/**
 * Create a sale through the REAL UI flow (the sequence you described):
 *   pick item -> "Add Item" (cart) -> fill the iDiv customer/payment fields -> "Add Sell".
 */
function createSaleViaUI(cust) {
  // Step A1: open the New Sale section (sellDiv).
  cy.openSellSection('sellDiv')
  // Step A2: select the first sellable item and fire its change (loads its stock + rates).
  cy.get('#sellItemDD option').then(($opts) => {
    const opt = [...$opts].find(o => o.value && o.value !== '')
    expect(opt, 'an item with stock is available to sell').to.exist
    cy.get('#sellItemDD').select(opt.value, { force: true }).trigger('change', { force: true })
  })
  cy.wait(1000) // let the onChange -> loadStock AJAX populate the rate fields
  // Step A3: enter quantity and click "Add Item" -> the line lands in the cart (tablesi).
  cy.get('#sellItems').clear({ force: true }).type('1', { force: true })
  cy.get('#addInviceItem').click({ force: true })
  cy.get('#tablesi tbody tr', { timeout: 8000 }).should('have.length.greaterThan', 0)
  // Step A4: fill the iDiv customer/payment fields (manual customer, full payment).
  cy.get('#btnModeManual').click({ force: true })
  cy.get('#sellCN').clear({ force: true }).type(cust, { force: true })
  cy.get('#sellCC').clear({ force: true }).type('03001234567', { force: true })
  cy.get('#sellRec').clear({ force: true }).type('100', { force: true })
  // Step A5: click "Add Sell" to save.
  cy.get('#addSell').click({ force: true })
}

describe('Sell edit — Phase 1: link + getSellInvoice', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('a UI sale links its line to the invoice, and getSellInvoice returns the full invoice', () => {
    const cust = `P1Cust_${Date.now()}`
    // Step 1: intercept the save, then create a sale through the UI.
    cy.intercept('POST', '/addSell').as('addSell')
    createSaleViaUI(cust)
    // Step 2: the save succeeds and returns an invoice number.
    cy.wait('@addSell').then(({ response }) => {
      expect(response.statusCode).to.eq(200)
      expect(response.body.status, JSON.stringify(response.body)).to.eq('SUCCESS')
      const invoiceNo = invNo(response.body)
      // Step 3: the new sale line is LINKED to its invoice (customer_history_id persisted).
      cy.request('/getUserSell').then((sres) => {
        const mine = rows(sres).find(x => x.customerHistory && x.customerHistory.invoiceNo === invoiceNo)
        expect(mine, 'sale line linked to its invoice').to.exist
        // Step 4: getSellInvoice returns that whole invoice (for editing).
        cy.request(`/getSellInvoice?sellId=${mine.sellId}`).then((ir) => {
          expect(ir.body.status).to.eq('SUCCESS')
          expect(ir.body.object.invoiceNo).to.eq(invoiceNo)
          expect(ir.body.object.sales.length, 'at least one line').to.be.gte(1)
        })
      })
    })
  })

  it('rejects an unknown sellId without leaking (anti-IDOR)', () => {
    // Step 1: ask for an invoice by a sellId that isn't ours/doesn't exist.
    cy.request({ url: '/getSellInvoice?sellId=999999999', failOnStatusCode: false }).then((res) => {
      // Step 2: it must not return SUCCESS (no data leak).
      expect(res.status).to.eq(200)
      expect(res.body.status).to.not.eq('SUCCESS')
    })
  })
})

describe('Sell edit — Phase 2: row-click loads the invoice into the cart', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('loading a sale repopulates the cart + iDiv customer + edit banner', () => {
    const cust = `P2Cust_${Date.now()}`
    // Step 1: create a sale via the UI and capture its invoice number.
    cy.intercept('POST', '/addSell').as('addSell')
    createSaleViaUI(cust)
    cy.wait('@addSell').then(({ response }) => {
      expect(response.body.status).to.eq('SUCCESS')
      const invoiceNo = invNo(response.body)
      // Step 2: find THIS sale's sellId.
      cy.request('/getUserSell').then((sres) => {
        const mine = rows(sres).find(x => x.customerHistory && x.customerHistory.invoiceNo === invoiceNo)
        expect(mine, 'created sale present in the sell data').to.exist
        // Step 3: drive the Phase-2 loader for that sale (clicking its tableSell row calls exactly this
        // in main.js — verified separately; here we assert the population is correct & deterministic).
        cy.window().then((win) => win.loadSellForEdit(mine.sellId))
        // Step 4: the cart (tablesi) is rebuilt from the invoice's line item(s).
        cy.get('#tablesi tbody tr', { timeout: 12000 }).should('have.length.greaterThan', 0)
        // Step 5: the iDiv customer field is filled with this invoice's customer.
        cy.get('#sellCN').invoke('val').should('eq', cust)
        // Step 6: the edit banner shows this invoice number.
        cy.get('#sellEditBanner').should('be.visible').and('contain', invoiceNo)
        // Step 7: "Cancel edit" clears the edit state + banner.
        cy.get('#cancelSellEdit').click({ force: true })
        cy.get('#sellEditBanner').should('not.exist')
      })
    })
  })

  it('clicking a sell-data row triggers the edit load (row-click wiring)', () => {
    // Step 1: ensure a sale exists, then open the New Sale section (shows the sell data tableSell).
    cy.intercept('POST', '/addSell').as('addSell')
    createSaleViaUI(`P2Wire_${Date.now()}`)
    cy.wait('@addSell').its('response.body.status').should('eq', 'SUCCESS')
    cy.openSellSection('sellDiv')
    cy.get('#tableSell tbody tr', { timeout: 15000 }).should('have.length.greaterThan', 0)
    // Step 2: clicking a row rebuilds the cart (proves the row-click -> loadSellForEdit binding).
    cy.get('#tableSell tbody tr').first().click({ force: true })
    cy.get('#tablesi tbody tr', { timeout: 12000 }).should('have.length.greaterThan', 0)
  })
})

/**
 * Phase 3 — editing a sale IN PLACE through the real, form-driven flow (the UX the owner described):
 *
 *   row-click  -> loadSellForEdit: the item form (Id Sell) + the cart are repopulated, edit mode is on
 *                 (banner shows the invoice; the cart-add button becomes "Update Item").
 *   change line -> pick the item again in the form, set the new qty, click "Update Item": the matching
 *                 cart line is REPLACED in place (no duplicate). (A brand-new item would be appended.)
 *   change cust -> sellCN / sellCC / sellRec can be (re)entered; due recomputes from sellRec.
 *   save        -> "Add Sell" routes to updateSell (same invoice #, stock & dues adjusted by deltas).
 *
 * The cart is NOT edited directly — everything goes through the form, exactly as a cashier would.
 */
describe('Sell edit — Phase 3: updateSell via the real form-driven edit flow', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  // Current on-hand qty of an item, from getStock (the endpoint the sell form reads when an item is
  // picked). It returns the item's StockDTO directly; .stock is the quantity.
  const stockQty = (itemId) => cy.request('/getStock?itemId=' + itemId).then((r) =>
    (r.body && typeof r.body.stock === 'number') ? r.body.stock : null)

  it('editing a 2-qty line down to 1 keeps the invoice #, returns 1 to stock, leaves one line', () => {
    const cust = `P3Cust_${Date.now()}`
    cy.intercept('POST', '/addSell').as('addSell')
    cy.intercept('POST', '/updateSell').as('updateSell')
    cy.intercept('GET', '/getSellInvoice*').as('getInvoice')

    // ── Step 1: create a sale of QTY 2 through the UI, remembering which item we used. ──────────────
    let itemValue
    cy.openSellSection('sellDiv')
    cy.get('#sellItemDD option').then(($opts) => {
      const opt = [...$opts].find(o => o.value && o.value !== '')
      expect(opt, 'an item with stock is available to sell').to.exist
      itemValue = opt.value
      cy.get('#sellItemDD').select(opt.value, { force: true }).trigger('change', { force: true })
    })
    cy.wait(1000) // let onChange -> loadStock populate the rate fields
    cy.get('#sellItems').clear({ force: true }).type('2', { force: true })
    cy.get('#addInviceItem').click({ force: true })
    cy.get('#tablesi tbody tr', { timeout: 8000 }).should('have.length', 1)
    cy.get('#btnModeManual').click({ force: true })
    cy.get('#sellCN').clear({ force: true }).type(cust, { force: true })
    cy.get('#sellCC').clear({ force: true }).type('03001234567', { force: true })
    cy.get('#sellRec').clear({ force: true }).type('200', { force: true })
    cy.get('#addSell').click({ force: true })

    cy.wait('@addSell').then(({ response }) => {
      expect(response.body.status, JSON.stringify(response.body)).to.eq('SUCCESS')
      const invoiceNo = invNo(response.body)
      expect(invoiceNo, 'invoice number returned by addSell').to.match(/^INV-\d{6}$/)

      // ── Step 2: locate this sale's row + item id, and record stock right after the sale. ──────────
      cy.request('/getUserSell').then((sres) => {
        const mine = rows(sres).find(x => x.customerHistory && x.customerHistory.invoiceNo === invoiceNo)
        expect(mine, 'created sale present in the sell data').to.exist
        const itemId = mine.itemId
        stockQty(itemId).then((before) => {
          expect(before, 'stock qty right after the sale').to.be.a('number')

          // ── Step 3: open the invoice for editing (this is what a row-click runs). Wait on
          //     getSellInvoice so edit mode is fully established before we touch the form. ──────────
          cy.window().then((win) => win.loadSellForEdit(mine.sellId))
          cy.wait('@getInvoice').its('response.body.status').should('eq', 'SUCCESS')
          // edit mode is ON: banner shows the invoice, cart-add button reads "Update Item", one line,
          // and the line is auto-loaded into the form with the ITEM LOCKED (dropdown disabled).
          cy.get('#sellEditBanner', { timeout: 12000 }).should('be.visible').and('contain', invoiceNo)
          cy.get('#addInviceItem').should('contain', 'Update Item')
          cy.get('#tablesi tbody tr').should('have.length', 1)
          cy.get('#sellItemDD').should('be.disabled').and('have.value', String(itemValue))
          // the CUSTOMER is also locked: this was a manual customer (not in the dropdown when the section
          // loaded), so Manual mode is active and the name is filled but disabled.
          cy.get('#btnModeManual').should('have.class', 'active')
          cy.get('#sellCN').should('be.disabled').and('have.value', cust)

          // ── Step 4: the item can't change (dropdown locked) — only adjust the qty (2 -> 1), then
          //     click "Update Item". ─────────────────────────────────────────────────────────────
          cy.wait(1000) // let loadStock finish populating the rate fields for the locked item
          cy.get('#sellItems').clear({ force: true }).type('1', { force: true })
          cy.get('#addInviceItem').click({ force: true }) // "Update Item" -> replaces the line in place
          // the line was REPLACED, not duplicated: still exactly one line.
          cy.get('#tablesi tbody tr').should('have.length', 1)

          // ── Step 5: customer is locked & already filled — just save -> routes to updateSell. ──────
          cy.get('#addSell').click({ force: true })

          cy.wait('@updateSell').then(({ response }) => {
            // same invoice number, success.
            expect(response.body.status, JSON.stringify(response.body)).to.eq('SUCCESS')
            expect(invNo(response.body), 'invoice number is unchanged by the edit').to.eq(invoiceNo)

            // ── Step 6: stock went UP by 1 (old 2 sold, new 1 sold => +1 returned to stock). ───────
            stockQty(itemId).then((after) => {
              expect(after, 'stock after edit = before + 1').to.eq(before + 1)
            })
            // ── Step 7: the edit PERSISTED — the invoice now has exactly one line, and that line's
            //     quantity is the new value (1), proving the qty change reached the DB. ─────────────
            cy.request('/getUserSell').then((s2) => {
              const lines = rows(s2).filter(x => x.customerHistory && x.customerHistory.invoiceNo === invoiceNo)
              expect(lines.length, 'invoice has exactly one line after edit').to.eq(1)
              expect(Number(lines[0].quantity), 'persisted quantity is the edited value').to.eq(1)
            })
          })
        })
      })
    })
  })
})
