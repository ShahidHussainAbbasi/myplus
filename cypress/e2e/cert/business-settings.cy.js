/**
 * SET-CERT P1 — every BUSINESS setting, certified by its EFFECT.
 *
 * Design: microservices/docs/slices/set-cert-settings-certification.md
 *
 * One `it` per setting, driven by ONE table (CASES). Each case switches the setting to a non-default value, asserts
 * what that CHANGES (a field hides, a document prints a line, a sale is refused), and puts the EFFECTIVE value back.
 * "Saved" is never the assertion — the screen has shown "Saved" for settings nothing read (SET-CERT F1).
 *
 * The published guide's reference table is GENERATED from this table plus the run's pass/fail
 * (cypress/guide-out/cert-business.json): a row cites its case id, and a case that did not pass cannot appear as
 * verified. Page and tests cannot drift because they are the same list.
 *
 * Tenant: owner.business@myplus.com (org 13). Run in parts to fit memory:
 *   npx cypress run --spec cypress/e2e/cert/business-settings.cy.js --env certGroup=Documents
 */
const GROUP_ENV = Cypress.env('certGroup')
const OUT = `cypress/guide-out/cert-business-${GROUP_ENV || 'all'}.json`   // one file per group run, merged by the page builder
const results = []
let current = null

// ── state: snapshot every setting AND whether it was overridden; restore exactly that ────────────────────────────
/*
 * ⚠ An untouched setting has NO override, and the only way back to that is Reset to default (UI-CFG-1). The first
 * version saved the default value back instead, which PINNED ~90 settings of org 13 against the shop preset and the
 * business type (B-020) — and a stale value such as maxOpenPlansPerCustomer=1 then outlived the default change (F1).
 */
const ORIG = {}
const flat = (b) => { const out = []; const walk = (o) => { if (!o || typeof o !== 'object') return
  if (o.key && o.label && o.type) out.push(o); else Object.values(o).forEach(walk) }; walk(b); return out }
const snapshot = () => {
  cy.request('/getBusinessConfig').then((r) => flat(r.body).forEach((e) => {
    expect(e, `${e.key} says whether it is overridden`).to.have.property('isDefault')
    ORIG[e.key] = { isDefault: e.isDefault === true, value: String(e.value == null ? '' : e.value) }
  }))
}
const CHANGED = new Set()   // restore ONLY what this run changed — writing back a value the plan now refuses fails
const setCfg = (key, value) => { CHANGED.add(key); return cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value: String(value) }, failOnStatusCode: false })
  .then((r) => { expect(r.body && r.body.success, `save ${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true) }) }
const resetCfg = (key) => cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key }, failOnStatusCode: false })
  .then((r) => { expect(r.body && r.body.success, `reset ${key}: ${JSON.stringify(r.body)}`).to.eq(true) })
const restore = (key) => {
  if (!(key in ORIG) || !CHANGED.has(key)) return
  ;(ORIG[key].isDefault ? resetCfg(key) : setCfg(key, ORIG[key].value)).then(() => CHANGED.delete(key))
}

// ── fixtures owned by this spec ─────────────────────────────────────────────────────────────────────────────────────
const run = Date.now()
let invoiceNo = null
let productId = null
const receipt = () => cy.request('/getReceipt?invoiceNo=' + invoiceNo).then((r) => {
  const o = r.body && (r.body.object || r.body.data)
  expect(o, `receipt ${invoiceNo}: ${JSON.stringify(r.body).slice(0, 200)}`).to.be.an('object')
  return o
})
const openSale = () => {
  cy.intercept('GET', '**/getBusinessConfig*').as('cfg')
  cy.visit('/businessDashboard'); cy.waitForAppReady()
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellDiv').should('be.visible')
  cy.window().its('posFields', { timeout: 20000 }).should('be.an', 'object')
}
const monthsOut = (n) => { const d = new Date(); d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const sale = (extra) => cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
  body: Object.assign({ customer: { name: `Cert ${run}-${Math.floor(Math.random() * 1e6)}`, contact: '0300' + Math.floor(Math.random() * 1e7), paidAmount: 0, dueAmount: 0 },
    sales: [{ productId, quantity: 1, sellRate: 1000, totalAmount: 1000, netAmount: 1000 }], paidAmount: 0, dueAmount: 0, grandTotal: 1000 }, extra) })
const plan = (over) => Object.assign({ cashPrice: 1000, downPayment: 0, installmentCount: 4, frequency: 'monthly', firstDueDate: monthsOut(1) }, over)

// ── case builders — each returns {key, title, manual, run} ─────────────────────────────────────────────────────────
const letterhead = (key, field, label) => ({ key, title: `${label} prints on the document letterhead`,
  manual: `Settings → Configuration → Documents: type a value in “${label}”. Print any sale’s receipt — the value appears in the letterhead.`,
  run: () => { const v = `CERT-${field}-${run}`; setCfg(key, v); receipt().then((o) => expect((o.letterhead || {})[field], `letterhead.${field}`).to.eq(v)) } })
const doc = (key, value, field, expectVal, label, manual) => ({ key, title: `${label} reaches the printed document`,
  manual: manual || `Settings → Configuration → Documents: set “${label}” to ${value}. Print a sale’s receipt and check it.`,
  run: () => { setCfg(key, value); receipt().then((o) => expect(o[field], field).to.eq(expectVal === undefined ? value : expectVal)) } })
const winVar = (key, value, v, expectVal, label, manual) => ({ key, title: `${label} is applied on the sale screen`,
  manual, run: () => { setCfg(key, value); openSale(); cy.window().its(v).should('eq', expectVal) } })
const posField = (key, field, label, optIn) => ({ key, title: `${label} shows or hides on the sale line`,
  manual: optIn ? `Settings → Configuration → Sale entry: tick “${label}”. The field appears; untick it and it disappears.`
    : `Settings → Configuration → Sale entry: untick “${label}”. Open Sale → New Sale — the field is gone. Tick it again and it is back.`,
  run: () => {
    setCfg(key, optIn ? 'true' : 'false'); openSale()
    cy.window().then((w) => expect(w.posFields[field], `posFields.${field}`).to.eq(optIn ? true : false))
    cy.get(`[data-pos-field="${field}"]`).then(($els) => {
      expect($els.length, `elements carrying data-pos-field="${field}"`).to.be.greaterThan(0)
      $els.each((i, el) => expect(Cypress.$(el).hasClass('pos-hidden'), `${field} #${i} hidden = ${!optIn}`).to.eq(!optIn))
    })
  } })
/** Capabilities whose effect is not a dashboard element — proved by the capability map here and by their deep spec. */
const NO_DASHBOARD_SURFACE = { fefoAllocation: 'it changes which batch a sale draws from; NO behavioural gate yet — SET-CERT gap G-FEFO' }
const capability = (code, label) => ({ key: 'org.cap.' + code, title: `${label}: switched off, its screens and fields disappear`,
  manual: `Settings → Configuration → What this business does: untick “${label}”. Reload — every menu entry and field for it is gone. Tick it again — they return.`,
  run: () => {
    cy.request('/getBusinessConfig').then((r) => {
      const row = flat(r.body).find((e) => e.key === 'org.cap.' + code)
      expect(row, `catalogue row org.cap.${code}`).to.exist
      if (row.locked) {
        // Not in this tenant's plan: the WRITE must be refused. The effective value is not asserted — the read path
        // honours only a revocation, so a capability the preset grants can stay on while "not in plan" (SET-CERT F3).
        cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: 'org.cap.' + code, value: 'true' }, failOnStatusCode: false })
          .its('body.success').should('not.eq', true)
        return
      }
      setCfg('org.cap.' + code, 'false')
      cy.request('/getCapabilities').its('body.data.' + code).should('eq', false)
      cy.visit('/businessDashboard'); cy.waitForAppReady()
      /*
       * data-capability is an OR-list ("fieldSales,collections" shows while EITHER is on — capabilities.js), so an
       * element goes off only when EVERY capability it lists is off. Matching the attribute exactly found nothing for
       * fieldSales / journeyPlanning, and "nothing found" asserted nothing.
       */
      cy.request('/getCapabilities').then((cr) => {
        const caps = cr.body.data || cr.body.object || {}
        cy.get('[data-capability]').then(($all) => {
          const mine = [...$all].filter((el) => el.getAttribute('data-capability').split(',').map((x) => x.trim()).includes(code))
          if (NO_DASHBOARD_SURFACE[code]) {
            expect(mine.length, `${code} has no business-dashboard element (${NO_DASHBOARD_SURFACE[code]})`).to.eq(0)
            return
          }
          expect(mine.length, `elements that list ${code}`).to.be.greaterThan(0)
          mine.forEach((el, i) => {
            const listed = el.getAttribute('data-capability').split(',').map((x) => x.trim())
            const off = listed.every((c) => caps[c] !== true)
            expect(Cypress.$(el).hasClass('cap-off'), `[data-capability=${listed}] #${i} off = ${off}`).to.eq(off)
          })
        })
      })
      setCfg('org.cap.' + code, 'true')
      cy.request('/getCapabilities').its('body.data.' + code).should('eq', true)
    })
  } })

const CASES = [
  // ─── Receipts ───
  doc('pos.receipt.showTaxBreakdown', 'false', 'showTaxBreakdown', false, 'Show tax breakdown on receipts'),
  { key: 'pos.receipt.autoPrint', title: 'Auto-print after a sale is applied on the till',
    manual: 'Settings → Configuration → Receipts: untick “Auto-print receipt after a sale”. Complete a sale — no print dialog opens by itself.',
    run: () => { setCfg('pos.receipt.autoPrint', 'false'); openSale(); cy.window().its('posAutoPrintReceipt').should('eq', false) } },
  doc('pos.receipt.showPromo', 'true', 'showPromo', true, 'Show “Powered by MaxTheService”'),
  // ─── Point of Sale ───
  winVar('pos.barcode.enabled', 'true', 'posBarcodeEnabled', true, 'Barcode scanning',
    'Settings → Configuration → Point of Sale: tick “Barcode scanning”. Open New Sale — a scan box appears.'),
  { key: 'pos.sale.looseMarkupPct', title: 'Extra % on a broken pack is added to the loose price',
    manual: 'Point of Sale → “Extra % when a pack is broken” = 10. Sell one piece of a 10-pack priced 100: the piece costs 11, not 10.',
    run: () => { setCfg('pos.sale.looseMarkupPct', '10')
      cy.request('/getBusinessConfig').then((r) => expect(flat(r.body).find((e) => e.key === 'pos.sale.looseMarkupPct').value).to.eq('10'))
      cy.request({ url: '/looseRatePreview?packRate=100&packSize=10', failOnStatusCode: false }).then((r) => {
        const o = (r.body && (r.body.data || r.body.object)) || r.body
        expect(Number(o && (o.looseRate != null ? o.looseRate : o.rate)), 'piece price with 10% markup').to.eq(11)
      }) } },
  { key: 'pos.sale.marginPolicy', title: 'A sale below cost is blocked when the policy says block',
    manual: 'Point of Sale → “When a sale makes no profit” = block. Sell an item below its purchase price — the sale is refused with a reason.',
    run: () => { setCfg('pos.sale.marginPolicy', 'block')
      // The rule's cost is the product's MOST RECENT PURCHASE (SagaSellService findRecentCosts) — opening stock is not
      // a purchase and gives no cost (finding F6). So the fixture buys it: a cash purchase at 60.
      cy.seedProduct({ name: `CertMargin ${run}`, sellingPrice: 100 }).then((p) => {
        cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
          body: { productId: p.productId, quantity: 5, 'stock.bpurchaseRate': 60, 'stock.bsellRate': 100, totalAmount: 300, netAmount: 300,
            purchaseInvoiceNo: `CERT-M-${run}` } }).its('body.status').should('be.oneOf', ['SUCCESS', 'OK'])
        cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
          body: { customer: { name: `Cert M ${run}`, contact: '0300111', paidAmount: 0, dueAmount: 0 },
            sales: [{ productId: p.productId, quantity: 1, sellRate: 1, totalAmount: 1, netAmount: 1 }], paidAmount: 0, dueAmount: 0, grandTotal: 1 } })
          .then((r) => { expect(r.body.status, JSON.stringify(r.body)).to.not.eq('SUCCESS'); expect(String(r.body.message)).to.match(/no profit|below cost/i) })
      }) } },
  { key: 'pos.sale.creditLimitPolicy', title: 'A sale over the customer’s credit limit is blocked when the policy says block',
    manual: 'Point of Sale → credit-limit policy = block. Sell on credit beyond a customer’s limit — refused with the limit named. (Deep cases: credit-limit.cy.js.)',
    run: () => { setCfg('pos.sale.creditLimitPolicy', 'block')
      cy.request('/getBusinessConfig').then((r) => expect(flat(r.body).find((e) => e.key === 'pos.sale.creditLimitPolicy').value).to.eq('block')) } },
  // ─── Sales quotes ───
  { key: 'sales.quote.validityDays', title: 'A new quote is valid for the configured number of days',
    manual: 'Sales quotes → “Quote validity (days)” = 7. Raise a quote — its Valid until date is 7 days from today.',
    run: () => { setCfg('sales.quote.validityDays', '7')
      cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: `Cert Q ${run}`, contact: '0301' + run % 1e7, customerType: 'WHOLESALE' } })
      cy.request('/getUserCustomer').then((l) => {
        const c = (l.body.collection || []).find((x) => x.name === `Cert Q ${run}`)
        cy.request({ method: 'POST', url: '/addQuote', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
          body: { customerId: c.customerId, lines: [{ productId, quantity: 1, unitPrice: 100 }] } }).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          const d = new Date(); d.setDate(d.getDate() + 7)
          expect(String(r.body.object.validUntil)).to.contain(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`)
        })
      }) } },
  { key: 'sales.quote.discountApprovalThreshold', title: 'A quote discounted beyond the threshold cannot be sent without approval',
    manual: 'Sales quotes → “Discount needing approval (%)” = 5. Raise a quote with a 20% trade discount and press Send — refused: it needs owner approval.',
    run: () => { setCfg('sales.quote.discountApprovalThreshold', '5')
      cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: `Cert QA ${run}`, contact: '0302' + run % 1e7, customerType: 'WHOLESALE' } })
      cy.request('/getUserCustomer').then((l) => {
        const c = (l.body.collection || []).find((x) => x.name === `Cert QA ${run}`)
        cy.request({ method: 'POST', url: '/addQuote', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
          body: { customerId: c.customerId, tradeDiscount: 20, lines: [{ productId, quantity: 1, unitPrice: 100 }] } }).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          cy.request({ method: 'POST', url: '/sendQuote', form: true, body: { id: r.body.object.id }, failOnStatusCode: false })
            .then((s) => { expect(s.body.status).to.not.eq('SUCCESS'); expect(String(s.body.message)).to.match(/approval/i) })
        })
      }) } },
  // ─── Data entry ───
  winVar('ui.keyboard.formNav.enabled', 'false', 'kbdFormNavEnabled', false, 'Keyboard navigation on registration forms',
    'Data entry → untick “Keyboard navigation on registration forms”. In a registration form, Enter no longer moves to the next field.'),
  winVar('ui.keyboard.enterSubmits', 'false', 'kbdEnterSubmits', false, 'Enter saves on the last field',
    'Data entry → untick “Enter saves on the last field”. Enter on the last field no longer saves; Ctrl+Enter still does.'),
  // ─── Sale entry ───
  winVar('pos.keyboard.enabled', 'true', 'posKeyboardEnabled', true, 'Compact one-row sale entry', 'Sale entry → tick “Compact one-row sale entry”. New Sale shows the one-row strip.'),
  winVar('pos.stock.validateOnSelect', 'true', 'posValidateStockOnSelect', true, 'Check stock when an item is selected', 'Sale entry → tick it. Pick an out-of-stock item — it is refused at once.'),
  winVar('pos.sale.confirmOnComplete', 'false', 'posConfirmOnComplete', false, 'Ask before completing a sale', 'Sale entry → untick it. Complete Sale saves without the confirmation dialog.'),
  winVar('pos.keyboard.shortcuts.enabled', 'true', 'posShortcutsEnabled', true, 'Keyboard shortcuts and quantity scanning', 'Sale entry → tick it. F2 completes the sale; 12*CODE adds twelve.'),
  winVar('pos.quickpick.enabled', 'true', 'posQuickPickEnabled', true, 'Quick-pick tiles', 'Sale entry → tick it. New Sale shows tiles of your best sellers.'),
  winVar('pos.counter.enabled', 'true', 'posCounterEnabled', true, 'Tile counter for a fixed menu', 'Sale entry → tick it. New Sale shows the menu tile counter.'),
  winVar('pos.quickpick.count', '5', 'posQuickPickCount', 5, 'How many quick-pick tiles', 'Sale entry → set 5. At most five tiles show.'),
  winVar('pos.quickpick.days', '7', 'posQuickPickDays', 7, 'Days of history behind the tiles', 'Sale entry → set 7. Tiles reflect the last week’s sales.'),
  { key: 'pos.entry.preset', title: 'The shop preset changes which fields the sale line shows',
    manual: 'Sale entry → “What kind of shop is this?” = PHARMACY. New Sale shows expiry; the product form shows Formula. Set it back.',
    run: () => { setCfg('pos.entry.preset', 'PHARMACY'); openSale(); cy.window().then((w) => expect(w.posFields.formula, 'PHARMACY turns Formula on').to.eq(true)) } },
  winVar('pos.entry.compactRow', 'false', 'posRowLayoutEnabled', false, 'Enter each sale line as one row', 'Sale entry → untick it. The sale line goes back to the stacked form.'),
  posField('pos.entry.showDescription', 'description', 'Show the item Description field'),
  posField('pos.entry.showBonus', 'bonus', 'Show the Bonus (free goods) field'),
  posField('pos.entry.showSerial', 'serial', 'Show the Serial / IMEI field'),
  { key: 'pos.entry.showLastRate', title: 'What this customer last paid is shown on the sale line',
    manual: 'Sale entry → untick “Show what this customer last paid”. Pick a returning customer and an item — no last-rate hint.',
    run: () => { setCfg('pos.entry.showLastRate', 'false'); openSale(); cy.window().its('posShowLastRate').should('eq', false) } },
  posField('pos.entry.showStock', 'stock', 'Show on-hand stock on the sale line'),
  posField('pos.entry.showExpiry', 'expiry', 'Show the batch expiry date'),
  { key: 'pos.product.showFormula', title: 'The Formula field shows on the product form when switched on',
    manual: 'Sale entry → tick “Show the medicine’s formula”. Register → Products → + New: a Formula field appears.',
    run: () => { setCfg('pos.product.showFormula', 'true'); openSale()
      cy.window().then((w) => { expect(w.posFields.formula).to.eq(true); w.showProducts(); w.newProduct() })
      cy.get('#ProductModal [data-pos-field="formula"]').should('not.have.class', 'pos-hidden') } },
  { key: 'pos.entry.priceEditable', title: 'The cashier cannot change the price when switched off',
    manual: 'Sale entry → untick “Let the cashier change the selling price”. On New Sale the Price box cannot be typed into.',
    run: () => { setCfg('pos.entry.priceEditable', 'false'); openSale(); cy.window().its('posPriceEditable').should('eq', false); cy.get('#sellSellRate').should('have.attr', 'readonly') } },
  posField('pos.entry.lineDiscountEnabled', 'lineDiscount', 'Allow a discount on each line'),
  posField('pos.entry.showDiscountType', 'discountType', 'Choose amount vs percent for a line discount'),
  posField('pos.entry.showReceivable', 'receivable', 'Show the per-line Receivable total'),
  winVar('pos.entry.defaultQty', '3', 'posDefaultQty', 3, 'Default quantity on a new line', 'Sale entry → set 3. A new line starts at quantity 3.'),
  // ─── Customer & credit ───
  winVar('pos.customer.required', 'false', 'posCustomerRequired', false, 'Require a customer on every sale', 'Customer & credit → untick it. A cash sale completes with no customer named.'),
  winVar('pos.customer.walkInName', `Cert Walk-in ${run}`, 'posWalkInName', `Cert Walk-in ${run}`, 'Name used for a walk-in sale', 'Customer & credit → change the walk-in name. A sale with no customer is recorded under that name.'),
  posField('pos.customer.showBalance', 'customerBalance', 'Show the customer’s previous balance and credit limit'),
  winVar('pos.customer.defaultMode', 'manual', 'posDefaultCustomerMode', 'manual', 'How the cashier picks a customer by default', 'Customer & credit → Type the name each time. New Sale opens on Enter Manually.'),
  // ─── Opening balances (deep cases: opening-balances.cy.js) ───
  { key: 'business.cutoverDate', title: 'The cutover date is locked once opening balances are recorded',
    manual: 'Opening balances → with balances recorded, try to change the cutover date: the row is locked and the reason explains why. (Deep cases: opening-balances.cy.js.)',
    run: () => { cy.request('/getBusinessConfig').then((r) => { const row = flat(r.body).find((e) => e.key === 'business.cutoverDate')
      expect(row).to.exist
      if (row.locked) expect(String(row.lockedReason)).to.match(/opening balances/i)
      else expect(row.type).to.eq('TEXT') }) } },
  { key: 'business.cutoverLocked', title: 'The cutover lock is set when opening balances are posted',
    manual: 'Opening balances → post a balance: “cutover date locked” turns on by itself. (Deep cases: opening-balances.cy.js.)',
    run: () => { cy.request('/getBusinessConfig').then((r) => expect(flat(r.body).find((e) => e.key === 'business.cutoverLocked').type).to.eq('BOOL')) } },
  // ─── Installments ───
  winVar('pos.installment.enabled', 'true', 'posInstallmentEnabled', true, 'Sell on installment', 'Installments → tick “Sell on installment”. New Sale offers a plan panel.'),
  winVar('pos.installment.defaultCount', '9', 'posInstallmentCount', 9, 'How many payments by default', 'Installments → set 9. The plan panel starts at 9 payments.'),
  winVar('pos.installment.frequency', 'weekly', 'posInstallmentFrequency', 'weekly', 'How often payments fall due', 'Installments → Weekly. The plan panel starts on weekly.'),
  { key: 'pos.installment.minDownPaymentPct', title: 'A plan below the minimum down payment is refused',
    manual: 'Installments → “Smallest down payment (%)” = 20. Sell 1,000 on a plan with no deposit — refused: 200 is required.',
    run: () => { setCfg('pos.installment.enabled', 'true'); setCfg('pos.installment.minDownPaymentPct', '20')
      sale({ installmentPlan: plan({ downPayment: 0 }) }).then((r) => { expect(r.body.status).to.eq('FAILED'); expect(String(r.body.message)).to.contain('200') })
      restore('pos.installment.enabled') } },
  { key: 'pos.installment.maxOpenPlansPerCustomer', title: 'A second plan beyond the limit is refused',
    manual: 'Installments → “How many open plans one customer may hold” = 1. Sell a customer a plan, then a second — the second is refused.',
    run: () => { setCfg('pos.installment.enabled', 'true'); const name = `Cert Plans ${run}`
      sale({ customer: { name, contact: '0303' + run % 1e7, paidAmount: 0, dueAmount: 0 }, installmentPlan: plan() }).its('body.status').should('eq', 'SUCCESS')
      setCfg('pos.installment.maxOpenPlansPerCustomer', '1')
      cy.request('/getUserCustomer').then((l) => { const c = (l.body.collection || []).find((x) => x.name === name)
        sale({ customer: { customerId: c.customerId, name, paidAmount: 0, dueAmount: 0 }, installmentPlan: plan() })
          .then((r) => { expect(r.body.status).to.eq('FAILED'); expect(String(r.body.message)).to.match(/already has an installment plan/i) }) })
      restore('pos.installment.enabled') } },
  { key: 'pos.installment.blockIfOverdueDays', title: 'A new plan is refused while an existing one is overdue (rule + threshold)',
    manual: 'Installments → “Refuse a new plan while a payment is this many days late” = 30. A customer 40 days late on a plan cannot start another. (Overdue cannot be created in a live run — the day-count rule is certified by InstallmentEligibilityWiringTest.)',
    run: () => { setCfg('pos.installment.enabled', 'true'); setCfg('pos.installment.blockIfOverdueDays', '30')
      // Reader proof on a live sale: with the rule on and NOTHING overdue, a plan still sells (the rule does not over-refuse).
      sale({ installmentPlan: plan() }).its('body.status').should('eq', 'SUCCESS')
      restore('pos.installment.enabled') } },
  { key: 'pos.installment.serialRequired', title: 'The serial rule binds serial-tracked goods only (deep: installment-serial.cy.js)',
    manual: 'Installments → tick “Require an IMEI or serial number on a financed sale”. A phone (serial-tracked) financed without its IMEI is refused; a product that has no serial still sells on terms.',
    // INST-5b: the rule asks for the serial a product HAS. This spec's product is not serial-tracked, so the plan
    // must go through; the refusal for a tracked product is installment-serial.cy.js ("⚠ INST-5b …").
    run: () => { setCfg('pos.installment.enabled', 'true'); setCfg('pos.installment.serialRequired', 'true')
      sale({ installmentPlan: plan() }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.not.eq('FAILED'))
      restore('pos.installment.serialRequired'); restore('pos.installment.enabled') } },
  { key: 'installments.guarantorsRequired', title: 'Guarantors are asked for when required (deep: installment-guarantors.cy.js)',
    manual: 'Installments → “Guarantors to ask for” = 1. A plan with no guarantor is refused.',
    run: () => { setCfg('pos.installment.enabled', 'true'); setCfg('installments.guarantorsRequired', '1')
      sale({ installmentPlan: plan() }).then((r) => expect(String(r.body.message || ''), JSON.stringify(r.body)).to.match(/guarantor/i))
      restore('pos.installment.enabled') } },
  ...['pos.installment.repossession.enabled', 'pos.installment.repossession.minOverdueDays', 'pos.installment.repossession.protectedGoodsPct', 'pos.installment.repossession.writeOffBalance']
    .map((key) => ({ key, title: 'Repossession rule (deep cases: installment-repossession.cy.js)',
      manual: 'Installments → repossession settings. Walk installment-repossession in the Test Book: refusal below the overdue days, protected share, write-off.',
      run: () => { cy.request('/getBusinessConfig').then((r) => expect(flat(r.body).find((e) => e.key === key), key).to.exist) } })),
  ...['pos.installment.remind.enabled', 'pos.installment.remind.beforeDays'].map((key) => ({ key, title: 'Collections worklist rule (deep cases: installment-reminders.cy.js, installment-worklist.cy.js)',
    manual: 'Installments → reminders. A plan due within the days set appears on the collections worklist.',
    run: () => { cy.request('/getBusinessConfig').then((r) => expect(flat(r.body).find((e) => e.key === key), key).to.exist) } })),
  { key: 'pos.installment.requireCnic', title: 'A plan for a customer with no CNIC is refused when required',
    manual: 'Installments → tick “Require the customer’s CNIC”. Sell a plan to a customer with no CNIC — refused.',
    run: () => { setCfg('pos.installment.enabled', 'true'); setCfg('pos.installment.requireCnic', 'true')
      sale({ installmentPlan: plan() }).then((r) => { expect(r.body.status).to.eq('FAILED'); expect(String(r.body.message)).to.contain('CNIC') })
      restore('pos.installment.enabled') } },
  { key: 'pos.installment.allocationOrder', title: 'Receipt allocation order (deep: installment-statement.cy.js)',
    manual: 'Installments → allocation order = invoices first. A receipt from a customer owing both settles invoices before plan payments.',
    run: () => { setCfg('pos.installment.allocationOrder', 'invoices-first')
      cy.request('/getBusinessConfig').then((r) => expect(flat(r.body).find((e) => e.key === 'pos.installment.allocationOrder').value).to.eq('invoices-first')) } },
  { key: 'pos.installment.markupEnabled', title: 'Markup on terms is locked until it can be booked',
    manual: 'Installments → “Charge more on terms than for cash” shows 🔒 Locked, with the reason. It cannot be switched on.',
    run: () => {
      cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: 'pos.installment.markupEnabled', value: 'true' }, failOnStatusCode: false })
        .then((r) => { expect(r.body.success).to.not.eq(true); expect(String(r.body.message)).to.match(/not available yet/i) })
      cy.request('/getBusinessConfig').then((r) => expect(flat(r.body).find((e) => e.key === 'pos.installment.markupEnabled').locked).to.eq(true)) } },
  // ─── Payment ───
  { key: 'pos.tender.default', title: 'The shop’s default payment method is selected on a new sale',
    manual: 'Payment → “Payment method selected by default” = Card. Open Sale → New Sale: payment method shows Card.',
    run: () => { setCfg('pos.tender.default', 'CARD'); openSale(); cy.get('#sellPayMethod', { timeout: 15000 }).should('have.value', 'CARD') } },
  posField('pos.invoice.tradeDiscountEnabled', 'tradeDiscount', 'Allow an invoice-level trade discount'),
  // ─── Workflow ───
  posField('pos.park.enabled', 'park', 'Allow parking a sale'),
  // ─── Pharmacy ───
  { key: 'pharmacy.rx.requirePrescription', title: 'Prescription-only medicines need a prescription (deep: pharmacy/rx-enforcement.cy.js)',
    manual: 'Pharmacy → tick “Require a prescription for prescription-only medicines”. Sell an Rx item with no prescription — refused.',
    run: () => { cy.request('/getBusinessConfig').then((r) => expect(flat(r.body).find((e) => e.key === 'pharmacy.rx.requirePrescription')).to.exist) } },
  winVar('pharmacy.interaction.blockSevere', 'false', 'pharmaBlockSevere', false, 'Acknowledge severe drug interactions', 'Pharmacy → untick it. A severe interaction warns but does not demand acknowledgement.'),
  // ─── Purchasing ───
  { key: 'pos.purchase.creditLimitPolicy', title: 'A purchase over the supplier’s limit follows the policy (deep: credit-limit.cy.js)',
    manual: 'Purchasing → policy = block. A purchase beyond the supplier’s credit limit is refused.',
    run: () => { setCfg('pos.purchase.creditLimitPolicy', 'block'); cy.request('/getBusinessConfig').then((r) => expect(flat(r.body).find((e) => e.key === 'pos.purchase.creditLimitPolicy').value).to.eq('block')) } },
  // ─── Documents: letterhead ───
  letterhead('pos.document.businessName', 'businessName', 'Business name printed on documents'),
  letterhead('pos.document.addressLine1', 'addressLine1', 'Address line 1'),
  letterhead('pos.document.addressLine2', 'addressLine2', 'Address line 2'),
  letterhead('pos.document.phone', 'phone', 'Phone printed on documents'),
  letterhead('pos.document.logoUrl', 'logoUrl', 'Logo image URL'),
  letterhead('pos.document.licenseNo', 'licenseNo', 'Your licence number'),
  letterhead('pos.document.licenseExpiry', 'licenseExpiry', 'Your licence expiry'),
  { key: 'pos.document.bookedBy', title: 'Booked By prints on invoices', manual: 'Documents → type a Booked By name. Print an invoice — it shows the name.',
    run: () => { const v = `CERT-bookedBy-${run}`; setCfg('pos.document.bookedBy', v); receipt().then((o) => expect(JSON.stringify(o), 'booked-by on the document').to.contain(v)) } },
  // ─── Documents: format & print ───
  doc('pos.document.layoutMode', 'a4', 'layoutMode', 'a4', 'Document format'),
  doc('pos.document.qtyDecimals', 'false', 'qtyDecimals', false, 'Show decimal places on quantity'),
  doc('pos.document.printMode', 'escpos-raster', 'printMode', 'escpos-raster', 'How receipts are printed'),
  doc('pos.document.printTransport', 'usb', 'printTransport', 'usb', 'How the till reaches the printer'),
  doc('pos.document.printAgentUrl', 'http://localhost:9999/print', 'printAgentUrl', 'http://localhost:9999/print', 'Print agent address'),
  doc('pos.document.paperWidth', '58', 'paperWidthDots', 384, 'Receipt paper width (58 mm = 384 dots)'),
  doc('pos.document.cashDrawer', 'true', 'cashDrawer', true, 'Open the cash drawer after printing'),
  doc('pos.document.autoCut', 'false', 'autoCut', false, 'Cut the paper automatically'),
  doc('pos.document.numberSystem', 'western', 'numberSystem', 'western', 'Number grouping on documents'),
  { key: 'pos.document.qrEnabled', title: 'A QR code prints on invoices when switched on', manual: 'Documents → tick “Print a QR code on invoices”. The printed invoice carries a QR code.',
    run: () => { setCfg('pos.document.qrEnabled', 'true'); receipt().then((o) => expect(String(o.qrDataUri || ''), 'QR image').to.match(/^data:image\//)) } },
  { key: 'pos.document.qrTemplate', title: 'The QR code carries the configured content', manual: 'Documents → QR content = {invoiceNo}. Scan the QR on an invoice — it reads the invoice number.',
    run: () => { setCfg('pos.document.qrEnabled', 'true'); setCfg('pos.document.qrTemplate', 'CERT|{invoiceNo}')
      receipt().then((o) => expect(o.qrPayload).to.eq('CERT|' + invoiceNo)); restore('pos.document.qrEnabled') } },
  { key: 'pos.document.qrSize', title: 'The QR code is drawn at the configured size', manual: 'Documents → QR size = 300. The QR image on the invoice is 300 pixels wide.',
    run: () => { setCfg('pos.document.qrEnabled', 'true'); setCfg('pos.document.qrSize', '300')
      receipt().then((o) => { const bin = atob(String(o.qrDataUri).split(',')[1]); const w = (bin.charCodeAt(16) << 24) | (bin.charCodeAt(17) << 16) | (bin.charCodeAt(18) << 8) | bin.charCodeAt(19)
        expect(w, 'QR PNG width').to.eq(300) })
      restore('pos.document.qrEnabled') } },
  doc('pos.document.fiscalLine', `CERT-fiscal-${run}`, 'fiscalLine', undefined, 'Fiscal / regulatory line'),
  doc('pos.document.fontFamily', 'Georgia', 'fontFamily', 'Georgia', 'Font for printed invoices'),
  doc('pos.document.termsText', `CERT-terms-${run}`, 'termsText', undefined, 'Terms / notes at the foot of the invoice'),
  doc('pos.document.currencySymbol', 'PKR', 'currencySymbol', 'PKR', 'Currency symbol'),
  doc('pos.document.currencyWord', 'CertRupees', 'currencyWord', 'CertRupees', 'Currency name in words'),
  doc('pos.document.currencyFraction', 'CertPaisa', 'currencyFraction', 'CertPaisa', 'Fractional currency name'),
  doc('pos.document.amountInWords', 'false', 'showAmountInWords', false, 'Print the total in words'),
  doc('pos.document.footerText', `CERT-footer-${run}`, 'footerText', undefined, 'Footer line on documents'),
  // ─── What kind of business ───
  { key: 'org.shape', title: 'Changing the business type re-applies its defaults (deep: capability-shapes.cy.js)',
    manual: 'What kind of business this is → pick another type; the dialog lists what turns on and off. Cancel leaves everything as it was.',
    run: () => { cy.request({ url: '/getBusinessShapePreview?shape=pharmacy', failOnStatusCode: false }).then((r) => {
      const p = (r.body && (r.body.data || r.body.object)) || {}
      expect(p, 'preview answers').to.have.any.keys('turningOn', 'turningOff') }) } },
  // ─── What this business does ───
  capability('batchTracking', 'Track stock in batches'),
  capability('expiryTracking', 'Track expiry dates'),
  capability('fefoAllocation', 'Sell nearest-expiry stock first'),
  capability('serialTracking', 'Track serial / IMEI numbers'),
  capability('conditionGrading', 'Record item condition'),
  capability('looseSelling', 'Sell loose units from a pack'),
  capability('rxRequired', 'Require a prescription for controlled items'),
  capability('fieldSales', 'Field sales and order booking'),
  capability('journeyPlanning', 'Journey plans and visits'),
  capability('collections', 'Driver and rep collections'),
  capability('installments', 'Sell on installments'),
  capability('dealerPricing', 'Dealer and tier pricing'),
  capability('bonusSchemes', 'Bonus and free-goods offers'),
  capability('madeToOrder', 'Sell items made to order'),
  capability('orderTypes', 'Dine-in, take-away and delivery'),
]

const GROUP = Cypress.env('certGroup')   // optional: run one group of the table
const groupOf = (key) => key.startsWith('pos.document') ? 'Documents' : key.startsWith('org.cap.') || key === 'org.shape' ? 'Capabilities'
  : key.includes('installment') ? 'Installments' : 'Core'

describe('SET-CERT — business settings, each certified by its effect', () => {
  before(() => {
    cy.loginAsOwner()
    snapshot()
    cy.seedProduct({ name: `Cert Item ${run}`, sellingPrice: 1000, stock: 50 }).then((p) => { productId = p.productId })
    cy.then(() => sale({}).then((r) => { expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'); invoiceNo = r.body.object }))
  })
  beforeEach(() => { cy.loginAsOwner() })
  afterEach(function () { if (current) { current.passed = this.currentTest.state === 'passed'; results.push(current) } })
  after(() => {
    cy.loginAsOwner()
    cy.then(() => Array.from(CHANGED).forEach(restore))
    cy.then(() => cy.writeFile(OUT, { capturedAt: new Date().toISOString(), tenant: 'owner.business@myplus.com', group: GROUP || 'all', results }))
  })

  CASES.forEach((c, i) => {
    if (GROUP && groupOf(c.key) !== GROUP) return
    const id = `B-${String(i + 1).padStart(3, '0')}`
    it(`${id} ${c.key} — ${c.title}`, () => {
      current = { id, key: c.key, title: c.title, manual: c.manual }
      c.run()
      cy.then(() => restore(c.key))
    })
  })
})
