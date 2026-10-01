/**
 * Settings & Configuration guide — section F, "The other settings screens", as REAL manual tests.
 *
 * The first version of section F only OPENED each screen and took one picture. A tester could not use it: it
 * said nothing about what to type, what should happen, or how to leave the tenant as it was. Each `it` here is
 * now one complete manual test case — prerequisites, test data, numbered actions, the expected result of each
 * action, and the cleanup — and every action is PERFORMED, ASSERTED and PHOTOGRAPHED in that order. The page
 * builder shows a case only if the whole case passed, so no expected result on the page is one the app did
 * not actually produce on this build.
 *
 * WHERE A SCREEN IS MISSING. Some cleanups have no screen (reversing an opening balance; removing your own
 * store access). Those steps give the tester the exact browser-console command, and this spec RUNS THAT SAME
 * COMMAND in the page (`win.eval`) — so the command on the page is tested too. (Deleting a bonus offer and
 * deactivating a store were console steps until L14/L15 gave them buttons.)
 *
 * TENANTS. Cases that move money or add records that cannot be removed (a sale that must be voided, a store,
 * an opening balance) run on owner.lifecycle@ — the Test Book's sacrificial tenant — never on owner.business@,
 * which most other specs sign in as. Every case also registers an undo in SAFETY, run in after(), so a case
 * that fails half-way still leaves nothing behind.
 *
 * Run headed:
 *   npx cypress run --headed --browser electron --spec cypress/e2e/docs/settings-guide-screens.cy.js \
 *     --config trashAssetsBeforeRuns=false
 */
// One file per case, so re-running ONE case (--env guideOnly=F2) replaces only that case on the page.
const OUT_DIR = 'cypress/guide-out/settings-screens'
const ONLY = String(Cypress.env('guideOnly') || '').split(',').map((x) => x.trim()).filter(Boolean)
const caseIt = (id, title, fn) => ((ONLY.length && !ONLY.includes(id)) ? it.skip : it)(`${id} — ${title}`, fn)
const LIFECYCLE = 'owner.lifecycle@myplus.com'
const PW = 'Demo@2025!'
const run = String(Date.now()).slice(-5)

const steps = []
const SAFETY = []
let cur = null

/** Declare one manual test case. */
const testCase = (id, title, meta) => {
  cur = { id, section: 'The other settings screens', title, how: [], expected: [], shots: [], actions: [], cleanup: [], ...meta }
}
/**
 * One numbered action with its expected results. `via: 'console'` marks a step with no screen (the tester runs
 * `code` in the browser console); `via: 'run'` marks what the run did through the same request a screen sends,
 * with the screen path for a person given in the text.
 */
const act = (text, expect, opts = {}) => {
  const a = { do: text, expect: [].concat(expect || []), shots: [], via: opts.via || 'screen', code: opts.code || null }
  ;(opts.cleanup ? cur.cleanup : cur.actions).push(a)
  return a
}
/** The picture for action `a`: an element, or the viewport (what the tester sees). */
const snap = (a, name, subject) => {
  const pos = cur.actions.includes(a) ? `a${cur.actions.indexOf(a) + 1}` : `c${cur.cleanup.indexOf(a) + 1}`
  const file = `${cur.id}-${pos}-${name}`
  a.shots.push(file)
  cur.shots.push(file)
  return subject ? cy.get(subject).screenshot(file, { overwrite: true })
                 : cy.screenshot(file, { capture: 'viewport', overwrite: true })
}

const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return Array.isArray(b) ? b : [] }
const asLifecycle = () => cy.loginAs(LIFECYCLE, PW, '/getBusinessDashboardStats')
const openDashboard = () => { cy.visit('/businessDashboard'); cy.waitForAppReady() }
const openMenu = (dd) => {
  cy.get(`#${dd}`).then(($d) => { if (!$d.hasClass('snav-open')) cy.get(`#${dd} .snav-btn`).click() })
  cy.get(`#${dd}`).should('have.class', 'snav-open')
}
/** Settings menu entry by the function it calls — the label is translated, the handler is not. */
const settingsItem = (fn) => { openMenu('snavSettings'); cy.get(`#snavSettings a[onclick^="${fn}("]`).click() }
const menuText = (fn) => cy.get(`#snavSettings a[onclick^="${fn}("]`).invoke('text').then((s) => s.trim())

const configRow = (key) => {
  cy.revealSetting(key)
  return cy.get(`#businessConfigBody [data-key="${key}"]`).closest('.cfg-row')
}
const openConfiguration = () => {
  settingsItem('showBusinessConfig')
  cy.get('#businessConfigBody .cfg-rail__item', { timeout: 20000 }).should('have.length.greaterThan', 3)
}
const saveBiz = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, failOnStatusCode: false, body: { key, value: String(value) } })
const resetBiz = (key) =>
  cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, failOnStatusCode: false, body: { key } })
const bizSetting = (key) => cy.request('/getBusinessConfig').then((r) => {
  const find = (o) => { if (!o || typeof o !== 'object') return null; if (o.key === key) return o; for (const v of Object.values(o)) { const f = find(v); if (f) return f } return null }
  return find(r.body)
})

describe('Settings guide — F. the other settings screens, step by step (captured)', () => {
  beforeEach(() => cy.viewport(1366, 860))

  afterEach(function () {
    cur.passed = this.currentTest.state === 'passed'
    cur.capturedAt = new Date().toISOString()
    if (!cur.passed) cur.error = String((this.currentTest.err && this.currentTest.err.message) || '').slice(0, 400)
    steps.push(cur)
    cy.writeFile(`${OUT_DIR}/${cur.id}.json`, cur)
  })

  after(() => {
    // Each case was already written in afterEach, so an undo that fails cannot lose what the run proved.
    // Undo in reverse order, each as its own tenant; every undo tolerates "already undone".
    SAFETY.slice().reverse().forEach((fn) => fn())
  })

  // ── F1. Tax Settings ─────────────────────────────────────────────────────────────────────────────────
  caseIt('F1', 'Tax Settings: change the policy, see it on a purchase, add and remove a tax code', () => {
    cy.loginAsOwner()
    let before
    cy.snapshotTaxSetting().then((s) => {
      before = s
      SAFETY.push(() => { cy.loginAsOwner(); cy.restoreTaxSetting(before) })
    })
    // A leftover "Guide 5%" from an interrupted run would make step 5 add a second one.
    cy.request('/catalogTaxCodes').then((r) => {
      const codes = Array.isArray(r.body) ? r.body : (typeof r.body === 'string' ? JSON.parse(r.body || '[]') : [])
      codes.filter((c) => c.name === 'Guide 5%').forEach((c) => cy.request({ method: 'POST', url: '/deleteTaxCode', headers: { 'Content-Type': 'application/json' }, body: { id: c.id }, failOnStatusCode: false }))
    })
    SAFETY.push(() => {
      cy.loginAsOwner()
      cy.request('/catalogTaxCodes').then((r) => {
        const codes = Array.isArray(r.body) ? r.body : (typeof r.body === 'string' ? JSON.parse(r.body || '[]') : [])
        codes.filter((c) => c.name === 'Guide 5%').forEach((c) => cy.request({ method: 'POST', url: '/deleteTaxCode', headers: { 'Content-Type': 'application/json' }, body: { id: c.id }, failOnStatusCode: false }))
      })
    })

    testCase('F1', 'Tax Settings — change the policy, see it on a purchase, add and remove a tax code', {
      tenant: 'owner.business@myplus.com (Owner Business)', role: 'Owner (only the owner sees Tax Settings)',
      purpose: 'How the business charges and records tax: sales tax on/off, purchase (input) tax on/off, whether prices include tax, the default rate, the label printed on receipts, and named tax codes for products with a different rate.',
      prereq: ['Signed in as the owner.', 'No tax code called **Guide 5%** exists (delete it first if an earlier test left one).'],
      data: ['Default rate **17**', 'Tax label **GUIDE-VAT**', 'Tax code **Guide 5%** at **5**%'],
      rollback: 'Cleanup puts back the values written down in action 1 and deletes the test tax code. Nothing else changes: no sale or purchase is saved.',
    })

    const a1 = act('Click **Settings → Tax Settings**.',
      ['The form shows **Sales tax (output)**, **Purchase tax (input credit)**, **Price mode**, **Default rate (%)**, **Tax label** and **Tax reg. no**, filled with the values in force.',
        'Below it is the **Tax codes** table.',
        '**Write the values down** — the cleanup puts them back.'])
    cy.intercept('GET', '**/getTaxSetting').as('tax')
    openDashboard()
    settingsItem('showTaxSettings')
    cy.wait('@tax')
    cy.get('#TaxSettingDiv').should('be.visible')
    cy.get('#taxLabel').should(($i) => expect($i.val(), 'the form is filled from the server').to.not.eq(''))
    cy.get('#taxCodeTable').should('be.visible')
    snap(a1, 'screen')

    const a2 = act('Tick **Purchase tax (input credit)**. Set **Default rate (%)** to **17** and **Tax label** to **GUIDE-VAT**. Click **Save**.',
      ['A green message appears at the top right saying the tax settings were saved.'])
    cy.get('#taxInputEnabled').check({ force: true })
    cy.get('#taxDefaultRate').clear().type('17')
    cy.get('#taxLabel').clear().type('GUIDE-VAT')
    cy.intercept('POST', '**/saveTaxSetting').as('saveTax')
    cy.get('#TaxSettingDiv button[onclick="saveTaxSetting()"]').click()
    cy.wait('@saveTax').its('response.body.status').should('eq', 'SUCCESS')
    cy.get('#saleSuccess').should('be.visible').invoke('text').then((msg) => {
      a2.expect = [`A green message **${msg.trim()}** appears at the top right.`]
    })
    snap(a2, 'saved')

    const a3 = act('Reload the page (**F5**) and open **Settings → Tax Settings** again.',
      ['**Purchase tax** is ticked, the rate is **17** and the label **GUIDE-VAT** — the change was saved, not just shown.'])
    cy.intercept('GET', '**/getTaxSetting').as('tax2')
    openDashboard()
    settingsItem('showTaxSettings')
    cy.wait('@tax2')
    cy.get('#taxInputEnabled').should('be.checked')
    cy.get('#taxDefaultRate').should('have.value', '17')
    cy.get('#taxLabel').should('have.value', 'GUIDE-VAT')
    snap(a3, 'reopened', '#TaxSetting')

    const a4 = act('Open **Purchase → New Purchase** and click **+ New Purchase**. Then close the form with **×** without saving.',
      ['The bill form has a **Tax %** box — it is shown only while *Purchase tax* is on.'])
    cy.openPurchaseSection('purchaseDiv')
    cy.get('#newPurchase').click()
    cy.get('#PurchaseModal').should('have.class', 'open')
    cy.get('#purchaseTaxRow', { timeout: 10000 }).should('be.visible')
    snap(a4, 'purchase-tax-box', '#PurchaseModal .crud-box')
    cy.get('#PurchaseModal .crud-x').click()
    cy.get('#PurchaseModal').should('not.have.class', 'open')

    const a5 = act('Back in **Settings → Tax Settings**, under **Tax codes** type **Guide 5%** as the name and **5** as the rate. Leave **Default** unticked. Click **Add code**.',
      ['A row **Guide 5%** with rate **5** appears in the table, with no *default* label.'])
    openDashboard()
    settingsItem('showTaxSettings')
    cy.get('#tcName').clear().type('Guide 5%')
    cy.get('#tcRate').clear().type('5')
    cy.get('#tcDefault').should('not.be.checked')
    cy.get('#TaxSettingDiv button[onclick="saveTaxCode()"]').click()
    cy.contains('#taxCodeTable tbody tr', 'Guide 5%', { timeout: 10000 }).as('code').should('contain', '5')
    cy.get('@code').should('not.contain', 'default')
    snap(a5, 'code-added', '#taxCodeTable')

    const c1 = act('Click **Delete** on the **Guide 5%** row, then confirm in the dialog.',
      ['The dialog warns that products using the code fall back to their own rate.', 'After confirming, the **Guide 5%** row is gone.'], { cleanup: true })
    cy.contains('#taxCodeTable tbody tr', 'Guide 5%').find('button.btn-danger').click()
    cy.get('[data-ui-confirm="ok"]').should('be.visible')
    snap(c1, 'confirm')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#taxCodeTable tbody').should('not.contain', 'Guide 5%')

    const c2 = act('Put back the values you wrote down in action 1 and click **Save**. Reload and reopen **Tax Settings**.',
      ['The form shows the original values again.'], { cleanup: true })
    cy.then(() => {
      cy.get('#taxEnabled')[before.enabled === true ? 'check' : 'uncheck']({ force: true })
      cy.get('#taxInputEnabled')[before.inputTaxEnabled === true ? 'check' : 'uncheck']({ force: true })
      cy.get('#taxMode').select(before.taxMode === 'INCLUSIVE' ? 'INCLUSIVE' : 'EXCLUSIVE', { force: true })
      cy.get('#taxDefaultRate').clear(); if (before.defaultRate != null) cy.get('#taxDefaultRate').type(String(before.defaultRate))
      cy.get('#taxLabel').clear().type(before.taxLabel || 'Tax')
      cy.get('#taxRegNo').clear(); if (before.taxRegNo) cy.get('#taxRegNo').type(before.taxRegNo)
    })
    cy.intercept('POST', '**/saveTaxSetting').as('saveBack')
    cy.get('#TaxSettingDiv button[onclick="saveTaxSetting()"]').click()
    cy.wait('@saveBack').its('response.body.status').should('eq', 'SUCCESS')
    cy.intercept('GET', '**/getTaxSetting').as('tax3')
    openDashboard()
    settingsItem('showTaxSettings')
    cy.wait('@tax3')
    cy.then(() => {
      cy.get('#taxInputEnabled').should(before.inputTaxEnabled === true ? 'be.checked' : 'not.be.checked')
      cy.get('#taxLabel').should('have.value', before.taxLabel || 'Tax')
      if (before.inputTaxEnabled !== true) {
        c2.expect.push('**Purchase → + New Purchase** no longer shows the **Tax %** box.')
      }
    })
    snap(c2, 'restored', '#TaxSetting')
    cy.then(() => {
      if (before.inputTaxEnabled !== true) {
        cy.openPurchaseSection('purchaseDiv')
        cy.get('#newPurchase').click()
        cy.get('#PurchaseModal').should('have.class', 'open')
        cy.get('#purchaseTaxRow').should('not.be.visible')
        snap(c2, 'purchase-no-tax-box', '#PurchaseModal .crud-box')
        cy.get('#PurchaseModal .crud-x').click()
      }
    })
  })

  // ── F2. Order settings ───────────────────────────────────────────────────────────────────────────────
  caseIt('F2', 'Order settings: a delivery fee reaches the online store checkout', () => {
    const KEY = 'order.shipping.standardFee'
    const FREE = 'order.shipping.freeOverAmount'
    let orgId, orig, freeOver
    // The Store menu is data-vertical-only="MARKETPLACE": only a marketplace business reaches Order settings
    // from the menu (found by this case — it first ran as owner.business@, whose menu has no Store at all).
    cy.loginAsMarketplaceOwner()
    cy.request('/getMyOrganizations').then((r) => {
      const o = (r.body.collection || [])[0] || {}
      orgId = o.id || o.organizationId || o.orgId
      expect(orgId, 'the owner has an organization').to.exist
    })
    cy.request('/getOrderConfig').then((r) => {
      const items = list(r.body)
      const e = items.find((x) => x.key === KEY)
      expect(e, KEY).to.exist
      orig = { isDefault: e.isDefault === true, value: String(e.value), def: e.defaultValue }
      freeOver = Number((items.find((x) => x.key === FREE) || {}).value || 0)
      SAFETY.push(() => {
        cy.loginAsMarketplaceOwner()
        if (orig.isDefault) cy.request({ method: 'POST', url: '/resetOrderConfig', form: true, body: { key: KEY }, failOnStatusCode: false })
        else cy.request({ method: 'POST', url: '/saveOrderConfig', form: true, body: { key: KEY, value: orig.value }, failOnStatusCode: false })
      })
    })

    testCase('F2', 'Order settings — a delivery fee reaches the online store checkout', {
      tenant: 'owner.marketplace@myplus.com (a marketplace business — the Store menu, and so Order settings, is shown only for a marketplace)', role: 'Owner',
      purpose: 'What the business charges for online orders — standard and express delivery fees, free delivery over an amount, cash on delivery, backorders, packing and approval. The storefront checkout reads these on every quote.',
      prereq: ['Signed in as the owner.', 'The business’s online store has at least one product in stock.', '**Free delivery over** is 0 (off), or higher than the product’s price — otherwise the checkout shows free delivery.'],
      data: ['Standard delivery fee **250**'],
      rollback: 'Cleanup returns the fee to what it was (Reset to default when it was never changed). The test cart is not an order and is never submitted.',
    })

    const a1 = act('Click **Store → Order settings**.',
      ['Delivery fees, free delivery threshold, cash on delivery, backorders, packing and approval rules are listed, each with its value.',
        '**Write down Standard delivery fee** and **Free delivery over**.'])
    openDashboard()
    openMenu('snavStore')
    cy.get('#snavStore a[onclick^="showOrderConfig("]').click()
    cy.get(`#orderConfigBody [data-key="${KEY}"]`, { timeout: 20000 }).should('be.visible')
    cy.get(`#orderConfigBody [data-key="${KEY}"]`).closest('.cfg-group').scrollIntoView()
    snap(a1, 'screen')

    const a2 = act('Change **Standard delivery fee** to **250** and click anywhere outside the box.',
      ['The row confirms **Saved** and the message at the top says **Saved.**', 'The row is now marked as changed, with **Reset to default**.'])
    cy.intercept('POST', '**/saveOrderConfig').as('saveOrder')
    cy.get(`#orderConfigBody [data-key="${KEY}"]`).clear().type('250').blur()
    cy.wait('@saveOrder').its('response.body.success').should('eq', true)
    cy.get('#orderConfigMsg').should('be.visible').and('contain', 'Saved')
    cy.get(`#orderConfigBody [data-key="${KEY}"]`).closest('.cfg-group').as('grp')
    snap(a2, 'saved', '@grp')

    const a3 = act('Open the online store: **/store?org=** followed by your organisation number (shown in the address when you open *Store → View store*). Click **Add** on any product, then in the cart choose **Standard delivery**.',
      ['The totals show **Shipping 250.00**, and **Total** = Subtotal + Tax + 250.00.'])
    cy.then(() => { a3.do = `Open the online store at **/store?org=${orgId}** (this business’s store). Click **Add** on any product, then in the cart choose **Standard delivery**.` })
    cy.then(() => cy.visit('/store?org=' + orgId))
    cy.get('.card button.add', { timeout: 20000 }).first().click()
    cy.get('#cartLines .line', { timeout: 10000 }).should('have.length.greaterThan', 0)
    cy.get('#checkout', { timeout: 10000 }).should('be.visible')
    cy.get('#cShip').select('STANDARD', { force: true })
    cy.get('#qSub').invoke('text').then((sub) => {
      if (freeOver > 0 && Number(sub) >= freeOver) throw new Error(`precondition: free delivery over ${freeOver} covers this ${sub} cart — pick a cheaper product or set the threshold to 0`)
    })
    cy.get('#qShip', { timeout: 10000 }).should('have.text', '250.00')
    cy.get('#qSub').invoke('text').then((sub) => cy.get('#qTax').invoke('text').then((tax) =>
      cy.get('#qTotal').invoke('text').then((tot) => expect(Number(tot)).to.be.closeTo(Number(sub) + Number(tax) + 250, 0.01))))
    cy.get('#checkout').scrollIntoView()
    snap(a3, 'store-250')

    const c1 = act('Back in **Store → Order settings**, put the Standard delivery fee back.',
      ['The fee shows its original value again and the row is no longer marked as changed.'], { cleanup: true })
    cy.then(() => {
      c1.do = orig.isDefault
        ? 'Back in **Store → Order settings**, click **Reset to default** on the **Standard delivery fee** row.'
        : `Back in **Store → Order settings**, type the fee you wrote down (**${Number(orig.value).toFixed(2)}**) into **Standard delivery fee** and click outside the box.`
    })
    cy.loginAsMarketplaceOwner()
    openDashboard()
    openMenu('snavStore')
    cy.get('#snavStore a[onclick^="showOrderConfig("]').click()
    cy.get(`#orderConfigBody [data-key="${KEY}"]`, { timeout: 20000 }).should('be.visible')
    cy.then(() => {
      if (orig.isDefault) {
        cy.get(`#orderConfigBody [data-key="${KEY}"]`).closest('.cfg-row').find('.cfg-row__reset').click()
        cy.get('#orderConfigMsg').should('be.visible')
      } else {
        cy.intercept('POST', '**/saveOrderConfig').as('saveBack')
        cy.get(`#orderConfigBody [data-key="${KEY}"]`).clear().type(orig.value).blur()
        cy.wait('@saveBack').its('response.body.success').should('eq', true)
      }
    })
    cy.request('/getOrderConfig').then((r) => {
      const e = list(r.body).find((x) => x.key === KEY)
      expect(Number(e.value), 'the fee is back').to.eq(Number(orig.value))
      expect(e.isDefault === true, 'and it is as untouched as it was').to.eq(orig.isDefault)
    })
    cy.get(`#orderConfigBody [data-key="${KEY}"]`).closest('.cfg-group').as('grpBack')
    snap(c1, 'restored', '@grpBack')

    const c2 = act('Open the store page again. If the cart is empty, click **Add** on a product; choose **Standard delivery**.',
      ['**Shipping** shows the original fee again.'], { cleanup: true })
    cy.then(() => { c2.expect = [`**Shipping** shows **${Number(orig.value).toFixed(2)}** again.`]; cy.visit('/store?org=' + orgId) })
    // Signing in again (cleanup step 1) starts a fresh browser session, so the cart token is gone — as it
    // would be for a tester who closed the tab. Add a product if the cart is empty.
    cy.get('.card button.add', { timeout: 20000 }).should('have.length.greaterThan', 0)
    cy.get('#cartLines').then(($c) => { if (!$c.find('.line').length) cy.get('.card button.add').first().click() })
    cy.get('#checkout', { timeout: 15000 }).should('be.visible')
    cy.get('#cShip').select('STANDARD', { force: true })
    cy.then(() => cy.get('#qShip', { timeout: 10000 }).should('have.text', Number(orig.value).toFixed(2)))
    cy.get('#checkout').scrollIntoView()
    snap(c2, 'store-back')
  })

  // ── F3. Price Rules ──────────────────────────────────────────────────────────────────────────────────
  caseIt('F3', 'Price Rules: a customer’s contract price is charged on a sale', () => {
    const pname = `Guide Tea ${run}`
    const cname = `Guide Rule Buyer ${run}`
    let productId, customer, ruleId, invoiceNo
    asLifecycle()
    cy.seedProduct({ name: pname, sellingPrice: 200, stock: 10 }).then((p) => { productId = p.productId })
    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: cname, contact: '03' + String(Date.now()).slice(-9), customerType: 'RETAILER' } })
      .its('body.status').should('eq', 'SUCCESS')
    cy.request('/getUserCustomer?q=-1').then((r) => { customer = list(r.body).find((c) => c.name === cname); expect(customer, 'test customer').to.exist })
    SAFETY.push(() => {
      asLifecycle()
      cy.request({ url: '/priceRules', failOnStatusCode: false }).then((r) => {
        const rules = typeof r.body === 'string' ? JSON.parse(r.body || '[]') : (r.body || [])
        ;[].concat(rules).filter((x) => customer && x.customerId === customer.customerId)
          .forEach((x) => cy.request({ method: 'POST', url: '/deletePriceRule', headers: { 'Content-Type': 'application/json' }, body: { id: x.id }, failOnStatusCode: false }))
      })
    })

    testCase('F3', 'Price Rules — a customer’s contract price is charged on a sale', {
      tenant: `${LIFECYCLE} (the Test Book’s sacrificial business — this case makes and voids a sale)`, role: 'Owner',
      purpose: 'Special prices: for one customer or a customer type, on a product or a category — a fixed price or a percentage. Exactly one rule wins per line (customer beats type, product beats category); the table says which one is overridden.',
      prereq: ['Signed in as the owner.', `A product priced **200** with stock (the run created **${pname}**, 10 in stock).`, `A customer (the run created **${cname}**, type Retailer).`],
      data: ['Rule: that customer + that product, **Fixed price 150**', 'Sale: 1 unit, paid cash 150'],
      rollback: 'Cleanup voids the test sale (stock and the customer’s balance are reversed) and deletes the rule. The voided invoice stays in the books as VOID, as every voided invoice does; the test product and customer remain on this sacrificial business.',
    })

    const a1 = act('Click **Settings → Price Rules**.',
      ['The rules table (empty or with existing rules, most specific first) and the **Add rule** form below it.'])
    openDashboard()
    settingsItem('showPriceRules')
    cy.get('#PriceRuleDiv', { timeout: 10000 }).should('be.visible')
    cy.get('#tablePriceRule').should('be.visible')
    snap(a1, 'screen')

    const a2 = act('In the form choose **Applies to** = one customer and pick the customer; **Target** = product and pick the product; **Mode** = fixed price; **Value** = **150**. Click **Add rule**.',
      ['The rule is listed with the customer’s name and **150**, ranked as the most specific kind (customer + product).'])
    cy.get('#prScope option[value="CUSTOMER"]').invoke('text').then((scope) =>
      cy.get('#prTarget option[value="PRODUCT"]').invoke('text').then((target) =>
        cy.get('#prMode option[value="FIXED"]').invoke('text').then((mode) => {
          a2.do = `In the form set **Applies to** = *${scope.trim()}* and pick **${cname}**; **Target** = *${target.trim()}* and pick **${pname}**; **Mode** = *${mode.trim()}*; **Value** = **150**. Click **Add rule**.`
        })))
    cy.get('#prScope').select('CUSTOMER', { force: true })
    cy.then(() => cy.get(`#prCustomerId option[value="${customer.customerId}"]`, { timeout: 10000 }).should('exist'))
    cy.then(() => cy.get('#prCustomerId').select(String(customer.customerId), { force: true }))
    cy.get('#prTarget').select('PRODUCT', { force: true })
    cy.then(() => cy.get(`#prProductId option[value="${productId}"]`, { timeout: 10000 }).should('exist'))
    cy.then(() => cy.get('#prProductId').select(String(productId), { force: true }))
    cy.get('#prMode').select('FIXED', { force: true })
    cy.get('#prValue').clear().type('150')
    cy.intercept('POST', '**/savePriceRule').as('saveRule')
    cy.contains('#PriceRuleForm button', /add rule/i).click()
    cy.contains('#tablePriceRule tbody tr', cname, { timeout: 10000 }).as('rule').should('contain', '150')
    cy.request('/priceRules').then((r) => {
      const rules = typeof r.body === 'string' ? JSON.parse(r.body || '[]') : r.body
      const mine = [].concat(rules).find((x) => x.customerId === customer.customerId)
      expect(mine, 'the rule is stored').to.exist
      ruleId = mine.id
    })
    snap(a2, 'rule-listed', '#tablePriceRule')

    const a3 = act('Sell **1** of the product to that customer on **Sale → New Sale** and take **150** in cash. Do not type a price.',
      ['The sale completes at **150** — the contract price, not the catalogue 200.'],
      { via: 'run' })
    cy.then(() => {
      a3.do = `Sell **1 × ${pname}** to **${cname}** on **Sale → New Sale** and take **150** cash. Do not type a price. *(The run sends the same request New Sale sends — no rate given, so the server prices the line.)*`
    })
    cy.then(() => cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: { customer: { customerId: customer.customerId, name: customer.name, contact: customer.contact },
        sales: [{ productId, quantity: 1 }], tenders: [{ method: 'CASH', amount: 150 }], paidAmount: 150, grandTotal: 150,
        idempotencyKey: 'guide-f3-' + run },
    })).then((s) => {
      expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
      invoiceNo = s.body.object
      SAFETY.push(() => {
        asLifecycle()
        cy.request('/getUserSell?q=-1').then((r) => {
          const row = list(r.body).find((x) => x.customerHistory && x.customerHistory.invoiceNo === invoiceNo && x.customerHistory.status !== 'VOID')
          if (row) cy.request({ method: 'POST', url: '/voidSell', form: true, failOnStatusCode: false, body: { customerHistoryId: row.customerHistory.customer_history_id, reason: 'guide cleanup' } })
        })
      })
      return cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo))
    }).then((r) => {
      const inv = r.body.object || r.body.data
      expect(Number(inv.sales[0].sellRate), 'priced by the rule').to.be.closeTo(150, 0.01)
    })

    const a4 = act('Open **Sale → Sale Detail Report**, click **Search**, and type the invoice number into the table’s search box.',
      ['The line shows the product at **150.00**, with **Return** and **Void** buttons.'])
    cy.openSellSection('SRDiv')
    cy.get('#SRDiv button[onclick*="loadSR"]').first().click({ force: true })
    cy.get('#tableSellReport tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
    cy.then(() => cy.get('#tableSellReport_filter input').clear().type(invoiceNo))
    cy.then(() => cy.contains('#tableSellReport tbody tr', invoiceNo, { timeout: 15000 }).should('contain', '150'))
    cy.then(() => { a4.do = `Open **Sale → Sale Detail Report**, click **Search**, and type **${invoiceNo}** into the table’s search box.` })
    snap(a4, 'report-150', '#tableSellReport')

    const c1 = act('Open **Sale → New Sale**. In the sales list under the form, type the invoice number into the search box, click the red **Void** button on its row, enter the reason **guide cleanup**, and confirm.',
      ['A message says the invoice was voided.'], { cleanup: true })
    cy.then(() => { c1.do = c1.do.replace('the invoice number', `**${invoiceNo}**`) })
    cy.visitSaleScreen()
    cy.get('#tableSell tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
    cy.then(() => cy.get('#tableSell_filter input').clear().type(invoiceNo))
    cy.then(() => cy.get(`#tableSell button.btn-danger[data-invoice="${invoiceNo}"]`, { timeout: 15000 }).first().click())
    cy.get('#uiC-input', { timeout: 10000 }).type('guide cleanup')
    snap(c1, 'void-dialog')
    cy.intercept('POST', '**/voidSell').as('void')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@void').its('response.body.status').should('eq', 'SUCCESS')
    cy.get('#saleSuccess').should('be.visible').invoke('text').then((m) => { c1.expect[0] = `A green message **${m.trim()}** appears.` })
    cy.then(() => cy.get('#tableSell_filter input').clear().type(invoiceNo))
    cy.then(() => cy.get(`#tableSell button.btn-danger[data-invoice="${invoiceNo}"]`).should('not.exist'))
    cy.get('#tableSell tbody').invoke('text').then((txt) => {
      c1.expect.push(txt.includes(invoiceNo)
        ? 'The row stays in the list marked **VOID**, with no Return or Void button.'
        : 'The invoice leaves the sales list — a voided invoice has no live lines.')
    })
    snap(c1, 'voided')
    // The books say the same: the statement carries the bill and its VOID, closing at 0.
    cy.then(() => cy.request('/customerStatement?customerId=' + customer.customerId)).then((r) => {
      const lines = list(r.body)
      expect(lines.map((l) => l.type), 'bill then void').to.include.members(['BILL', 'VOID'])
      expect(Number(lines[lines.length - 1].balance), 'closing balance').to.eq(0)
      c1.expect.push(`The customer’s **Statement** (Customers → Statement) shows the bill and its **VOID** line, closing at **0.00**; the product’s stock is back to 10.`)
    })

    const c2 = act('In **Settings → Price Rules**, click **Delete** on the rule and confirm.',
      ['The rule is gone from the table.'], { cleanup: true })
    openDashboard()
    settingsItem('showPriceRules')
    cy.contains('#tablePriceRule tbody tr', cname, { timeout: 10000 }).contains('button', /delete/i).click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#tablePriceRule tbody').should('not.contain', cname)
    snap(c2, 'rule-deleted', '#tablePriceRule')
  })

  // ── F4. Bonus Offers ─────────────────────────────────────────────────────────────────────────────────
  caseIt('F4', 'Bonus Offers: the offer states what it earns, and is listed', () => {
    const CODE = `GUIDE-10+1-${run}`
    let capBefore
    cy.loginAsOwner()
    bizSetting('org.cap.bonusSchemes').then((e) => {
      capBefore = { isDefault: e.isDefault === true, value: String(e.value) }
      if (String(e.value) !== 'true') saveBiz('org.cap.bonusSchemes', true).its('body.success').should('eq', true)
      SAFETY.push(() => {
        cy.loginAsOwner()
        if (capBefore.isDefault) resetBiz('org.cap.bonusSchemes'); else saveBiz('org.cap.bonusSchemes', capBefore.value)
      })
    })
    SAFETY.push(() => {
      cy.loginAsOwner()
      cy.request({ url: '/bonusSchemes', failOnStatusCode: false }).then((r) => {
        list(r.body).filter((s) => s.code === CODE).forEach((s) => cy.request({ method: 'DELETE', url: '/bonusScheme/' + s.id, failOnStatusCode: false }))
      })
    })

    testCase('F4', 'Bonus Offers — the offer states what it earns, and is listed', {
      tenant: 'owner.business@myplus.com (Owner Business)', role: 'Owner or admin',
      purpose: 'Buy-and-get offers (“buy 10, get 1 free”) from a supplier to the shop, or from the shop to a customer or customer type. Exclusive offers add the free unit on top; inclusive ones count it inside the quantity. The screen is shown only while *Bonus and free-goods offers* is switched on (Configuration → Business → What this business does).',
      prereq: ['Signed in as the owner.', '**Bonus and free-goods offers** is on (the run switches it on if needed and puts it back).', 'At least one product exists.'],
      data: [`Code **${CODE}**`, 'Applies to: customer type **Retailer (trade)**', 'Offer **10 + 1**'],
      rollback: 'Cleanup deletes the offer with its own **Delete** button.',
    })

    const a1 = act('Click **Settings → Bonus Offers**.',
      ['The offers table, and below it an **Add offer** form (heading and button both read *Add offer*, not a raw key).'])
    openDashboard()
    menuText('showBonusSchemes').then((label) => { a1.do = `Click **Settings → ${label}**.` })
    settingsItem('showBonusSchemes')
    cy.get('#BonusSchemeDiv').should('be.visible')
    cy.get('#bsFormTitle').invoke('text').should('not.match', /ui\./)
    cy.get('#bsTriggerProductId option', { timeout: 15000 }).should('have.length.greaterThan', 1)
    snap(a1, 'screen')

    const a2 = act('Fill the form: **Code**, **Applies to** = a customer type → **Retailer (trade)**, **Buy this** = any product, **Offer** = **10** + **1**, **Counts** = *Every block*. Then switch **Counts** to *Once*.',
      ['With *Every block* the note beside the quantities reads **30 paid earns 3 free**.', 'With *Once* it changes to **30 paid earns 1 free** — the server’s own calculation, the same one the till uses.'])
    cy.then(() => { a2.do = a2.do.replace('**Code**', `**Code** = **${CODE}**`) })
    cy.get('#bsCode').clear().type(CODE)
    cy.get('#bsScope').select('CUSTOMER_TYPE', { force: true })
    // L17 — ONE picker beside "Applies to", the one the scope uses. Asserted on the bootstrap-select BUTTONS a
    // person sees: the native <select>s are always hidden by the plugin, so asserting them proves nothing.
    cy.get('#bsCustomerTypeSlot .bootstrap-select').should('be.visible')
    cy.get('#bsVendorIdSlot').should('not.be.visible')
    cy.get('#bsCustomerIdSlot').should('not.be.visible')
    cy.get('#bsScope').closest('.form-group').find('.bootstrap-select:visible').should('have.length', 2) // scope + its one picker
    cy.get('#bsCustomerType').select('RETAILER', { force: true })
    cy.get('#bsTriggerProductId option').eq(1).then(($o) => cy.get('#bsTriggerProductId').select($o.val(), { force: true }))
    // L16 — the preview is a POST sent outside the global hooks; it used to 403 on the missing CSRF token.
    cy.intercept('POST', '**/bonusScheme/preview').as('preview')
    cy.get('#bsPaidQuantity').clear().type('10')
    cy.get('#bsBonusQuantity').clear().type('1')
    cy.get('#bsQualificationMode').select('REPEATING', { force: true })
    cy.wait('@preview').its('response.statusCode').should('eq', 200)
    cy.get('#bsPreview', { timeout: 10000 }).invoke('text').should('match', /30\D+3\b/)
    cy.get('#bsPreview').invoke('text').then((t) => { a2.expect[0] = `With *Every block* the note beside the quantities reads **${t.trim()}**.` })
    snap(a2, 'preview-repeating', '#BonusSchemeForm')
    cy.get('#bsQualificationMode').select('ONE_TIME', { force: true })
    cy.get('#bsPreview', { timeout: 10000 }).invoke('text').should('match', /30\D+1\b/)
    cy.get('#bsPreview').invoke('text').then((t) => { a2.expect[1] = `With *Once* it changes to **${t.trim()}** — the server’s own calculation, the same one the till uses.` })
    snap(a2, 'preview-once', '#BonusSchemeForm')

    const a3 = act('Set **Counts** back to *Every block* and click **Add offer**.',
      ['A green message says the offer was saved and the form clears.', 'The table lists the code with **Every 10 + 1**, type **Exclusive**, status **Active**, and **Edit** and **Delete** buttons.'])
    cy.get('#bsQualificationMode').select('REPEATING', { force: true })
    cy.intercept('POST', '**/bonusScheme').as('saveBonus')
    cy.get('#BonusSchemeForm button[onclick="saveBonusScheme()"]').click()
    cy.wait('@saveBonus').its('response.body.success').should('not.eq', false)
    cy.get('#saleSuccess').should('be.visible')
    cy.contains('#tableBonusScheme tbody tr', CODE, { timeout: 10000 }).as('offer')
    cy.get('@offer').should('contain', '10 + 1').and('contain', 'Active').and('contain', 'Exclusive')
    // L14 — names, not ids or raw codes: "Applies to" names the customer type, and "For" is not a bare #id.
    cy.get('@offer').find('td').eq(1).invoke('text').should('match', /Retailer/)
    cy.get('@offer').find('td').eq(2).invoke('text').should('not.match', /^#\d+$/)
    cy.get('@offer').find('td').eq(3).invoke('text').then((t) => { a3.expect[1] = `The table lists **${CODE}** with **${t.trim()}**, type **Exclusive**, status **Active**, and **Edit** and **Delete** buttons.` })
    cy.get('@offer').find('button').should('have.length', 2)
    snap(a3, 'listed', '#tableBonusScheme')

    const a4 = act('Click **Edit** on the offer. Change the free quantity from **1** to **2** and click **Edit offer**.',
      ['The form fills with the offer, its heading reads **Edit offer**, and beside **Applies to** only the customer-type picker shows, set to **Retailer (trade)**.',
       'After saving, the row reads **Every 10 + 2**. It is the same row — editing does not add a second offer.'])
    cy.contains('#tableBonusScheme tbody tr', CODE).contains('button', /^Edit$/).click()
    cy.get('#bsFormTitle').should('contain', 'Edit offer')
    cy.get('#bsCode').should('have.value', CODE)
    cy.get('#bsCustomerTypeSlot .bootstrap-select').should('be.visible').and('contain', 'Retailer')
    cy.get('#bsVendorIdSlot').should('not.be.visible')
    cy.get('#bsBonusQuantity').invoke('val').then((v) => expect(Number(v), 'loaded free qty').to.eq(1))
    cy.get('#bsBonusQuantity').clear().type('2')
    snap(a4, 'editing', '#BonusSchemeForm')
    cy.intercept('PUT', '**/bonusScheme/*').as('updBonus')
    cy.get('#BonusSchemeForm button[onclick="saveBonusScheme()"]').click()
    cy.wait('@updBonus').its('response.body.success').should('not.eq', false)
    cy.contains('#tableBonusScheme tbody tr', CODE, { timeout: 10000 }).should('contain', '10 + 2')
    cy.get('#tableBonusScheme tbody tr').filter(`:contains("${CODE}")`).should('have.length', 1)
    snap(a4, 'edited', '#tableBonusScheme')

    const c1 = act('Click **Delete** on the offer and confirm.',
      [`The **${CODE}** row disappears from the table.`], { cleanup: true })
    cy.intercept('DELETE', '**/bonusScheme/*').as('delBonus')
    cy.contains('#tableBonusScheme tbody tr', CODE).contains('button', /^Delete$/).click()
    cy.get('[data-ui-confirm="ok"]').should('be.visible').click()
    cy.wait('@delBonus').its('response.body.success').should('not.eq', false)
    cy.get('#tableBonusScheme tbody', { timeout: 10000 }).should('not.contain', CODE)
    snap(c1, 'deleted', '#tableBonusScheme')
    cy.request('/bonusSchemes').then((r) => expect(list(r.body).filter((s) => s.code === CODE), 'gone on the server').to.have.length(0))
  })

  // ── F5. Document Designer ────────────────────────────────────────────────────────────────────────────
  caseIt('F5', 'Document Designer: design a layout, save, edit and delete it', () => {
    const NAME = `Guide layout ${run}`
    cy.loginAsOwner()
    SAFETY.push(() => {
      cy.loginAsOwner()
      cy.request({ url: '/documentTemplates', failOnStatusCode: false }).then((r) => {
        list(r.body).filter((t) => t.name === NAME).forEach((t) => cy.request({ method: 'POST', url: '/deleteDocumentTemplate', form: true, body: { id: t.id }, failOnStatusCode: false }))
      })
    })

    testCase('F5', 'Document Designer — design a layout, save, edit and delete it', {
      tenant: 'owner.business@myplus.com (Owner Business)', role: 'Owner',
      purpose: 'The printed look of sale documents: paper, title style, logo, which columns print and in what order, header fields, total rows and footer. The preview is drawn by the same code that prints, so what you see is what prints. A layout is used only when **Use this layout for this channel** is ticked.',
      prereq: ['Signed in as the owner.'],
      data: [`Layout name **${NAME}**`, 'One column switched off, a different title style', '**Use this layout for this channel**: left unticked, so real receipts do not change'],
      rollback: 'Cleanup deletes the layout. Because it was never made the one in use, no receipt or invoice changed at any point.',
    })

    const a1 = act('Click **Settings → Document Designer**.',
      ['**Saved layouts** (a table), the **New layout** form — name, channel, paper, title style, logo, columns, header fields, totals, footer — and a live **preview**.',
        'Every label is real text (no “Document Designer0” or “Paper8”).'])
    openDashboard()
    settingsItem('showDocumentDesigner')
    cy.get('#DocumentDesignerDiv').should('be.visible')
    cy.get('#tableDocColumns tbody tr', { timeout: 15000 }).should('have.length.greaterThan', 1)
    cy.get('#docPreviewFrame').its('0.contentDocument.body').should('not.be.empty')
    cy.get('#DocumentDesignerDiv').invoke('text').should('not.match', /\b(Document Designer|Paper|Columns)[0-9]\b/)
    snap(a1, 'screen')

    const a2 = act('Type the layout name. In **Columns**, untick the second column. Change **Title style**.',
      ['The preview redraws immediately: that column is no longer printed and the title changes style.'])
    cy.get('#dtName').clear().type(NAME)
    cy.get('#tableDocColumns tbody tr').eq(1).find('td').eq(1).invoke('text').then((colName) => {
      const col = colName.trim()
      cy.get('#dtTitleStyle option').then(($opts) => {
        const cur = cy.$$('#dtTitleStyle').val()
        const other = [...$opts].find((o) => o.value !== cur)
        a2.do = `Type **${NAME}** as the name. In **Columns**, untick **${col}**. Set **Title style** to **${other.text.trim()}**.`
        a2.expect = [`The preview redraws immediately: **${col}** is no longer a column, and the title changes style.`]
        cy.get('#docPreviewFrame').its('0.contentDocument.body.innerText').should('contain', col)
        cy.get('#tableDocColumns tbody tr').eq(1).find('.dtColOn').uncheck()
        cy.get('#dtTitleStyle').select(other.value, { force: true })
        cy.get('#docPreviewFrame').its('0.contentDocument.body.innerText').should('not.contain', col)
      })
    })
    snap(a2, 'preview', '#DocumentDesignerDiv')

    const a3 = act('Leave **Use this layout for this channel** unticked. Click **Save**, then **OK** on the confirmation.',
      ['A dialog confirms the layout was saved.', 'The layout appears in **Saved layouts** with its channel, paper and number of columns, and is not marked *in use*.'])
    cy.get('#dtIsDefault').should('not.be.checked')
    cy.intercept('POST', '**/saveDocumentTemplate').as('saveDoc')
    cy.get('#DocumentDesignerDiv button[onclick="saveDocumentTemplate()"]').click()
    cy.wait('@saveDoc').its('response.body.status').should('eq', 'SUCCESS')
    cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).should('be.visible')
    snap(a3, 'saved-dialog')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.contains('#tableDocumentTemplate tbody tr', NAME, { timeout: 10000 }).as('layout')
    cy.get('@layout').find('td').eq(4).invoke('text').should('eq', '')
    snap(a3, 'listed', '#tableDocumentTemplate')

    const a4 = act('Click **Edit** on the layout.',
      ['The form heading changes to edit mode and loads the name; the column you switched off is still off.'])
    cy.get('@layout').find('.dtEdit').click()
    cy.get('#dtName', { timeout: 10000 }).should('have.value', NAME)
    cy.get('#dtFormTitle').invoke('text').then((t) => { a4.expect[0] = `The form heading changes to **${t.trim()}** and loads **${NAME}**; the column you switched off is still off.` })
    cy.get('#tableDocColumns tbody .dtColOn:not(:checked)').should('have.length.greaterThan', 0)
    snap(a4, 'edit')

    const c1 = act('Click **Delete** on the layout and confirm.',
      ['The layout is gone from **Saved layouts**.'], { cleanup: true })
    cy.contains('#tableDocumentTemplate tbody tr', NAME).find('.dtDelete').click()
    cy.get('[data-ui-confirm="ok"]').should('be.visible').click()
    cy.get('#tableDocumentTemplate tbody', { timeout: 10000 }).should('not.contain', NAME)
    snap(c1, 'deleted', '#tableDocumentTemplate')
  })

  // ── F6. Stores ───────────────────────────────────────────────────────────────────────────────────────
  caseIt('F6', 'Stores: add a branch and see the store switcher', () => {
    const NAME = `Guide Branch ${run}`
    let storeId, activeBefore
    // The SECOND active store the switcher needs. Before L15 the switcher counted INACTIVE stores, so this case
    // "saw" a switcher on a tenant whose only active store was the one it had just added — a coincidental pass.
    let second = null   // { id, name, reactivated }
    asLifecycle()
    cy.request('/getMyStores').then((r) => { activeBefore = (list(r.body).find((s) => s.active) || {}).id })
    // addStore grants the NEW store to the owner who created it (StoreController, "zero-touch"). The lifecycle
    // owner holds no store grants before this case, so the cleanup's complete set is the empty one.
    SAFETY.push(() => {
      asLifecycle()
      cy.request({ method: 'POST', url: '/assignStores', headers: { 'Content-Type': 'application/json' }, body: { storeIds: [], replace: true }, failOnStatusCode: false })
    })
    SAFETY.push(() => {
      asLifecycle()
      cy.request('/getStores').then((r) => {
        list(r.body).filter((s) => (s.name === NAME || (second && s.id === second.id)) && s.status !== 'INACTIVE').forEach((s) =>
          cy.request({ method: 'POST', url: '/updateStore', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
            body: { id: s.id, name: s.name, code: s.code, address: s.address, phone: s.phone, status: 'INACTIVE' } }))
      })
    })

    testCase('F6', 'Stores — add a branch and see the store switcher', {
      tenant: `${LIFECYCLE} (sacrificial — a store cannot be deleted)`, role: 'Owner',
      purpose: 'The business’s branches. Sales, purchases and shifts are stamped with the store they were made in; with two or more stores a switcher appears in the top bar, and staff can be limited to their own stores (Team).',
      prereq: ['Signed in as the owner.'],
      data: [`Store **${NAME}**, code **GB${run}**, address **Test Road 1**`],
      rollback: 'A store cannot be deleted. Cleanup deactivates it with its **Deactivate** button — it stays in this list (to be reactivated) but leaves the switcher — and removes the store access that adding it gave you (browser-console command).',
    })

    const a1 = act('Click **Settings → Stores**.',
      ['The stores table (name, code, address, phone, status) with an **Add store** row above it.'])
    openDashboard()
    settingsItem('showStores')
    cy.get('#StoresDiv').should('be.visible')
    cy.get('#tableStores tbody tr', { timeout: 10000 }).should('have.length.greaterThan', 0)
    snap(a1, 'screen')

    const a2 = act('Type the store name, code and address, and click **Add store**.',
      ['The message says **Store created. Pick it in the store switcher to sell from it.**', 'The store is listed with status **Active** and a **Deactivate** button.'])
    cy.then(() => { a2.do = `Type **${NAME}** as the name, **GB${run}** as the code and **Test Road 1** as the address. Click **Add store**.` })
    cy.get('#storeName').type(NAME)
    cy.get('#storeCode').type('GB' + run)
    cy.get('#storeAddress').type('Test Road 1')
    cy.intercept('POST', '**/addStore').as('addStore')
    cy.get('#StoresDiv button[onclick="saveStore()"]').click()
    cy.wait('@addStore').then((i) => { storeId = (i.response.body.object || i.response.body.data || {}).id })
    cy.get('#storeMsg').should('be.visible').and('contain', 'Store created')
    cy.contains('#tableStores tbody tr', NAME, { timeout: 10000 }).should('contain', 'Active').and('not.contain', 'Inactive')
    snap(a2, 'added')

    // A second ACTIVE store, so there is something to switch between. Reuse an old inactive branch through its
    // Reactivate button (L15) — stores cannot be deleted, so adding a fresh one every run would only pile up.
    const a3 = act('Click **Reactivate** on an older, inactive branch.',
      ['The message says **Store reactivated.**, the row is no longer grey, its status reads **Active** and its button **Deactivate**.'])
    cy.request('/getStores').then((r) => {
      const old = list(r.body).find((x) => x.status === 'INACTIVE' && x.name !== NAME)
      if (old) {
        second = { id: old.id, name: old.name, reactivated: true }
        a3.do = `Click **Reactivate** on an older, inactive branch — on this run **${old.name}**.`
        cy.intercept('POST', '**/updateStore').as('react')
        cy.contains('#tableStores tbody tr', old.name).contains('button', /^Reactivate$/).click()
        cy.wait('@react').its('response.body.status').should('eq', 'SUCCESS')
        cy.get('#storeMsg').should('be.visible').and('contain', 'Store reactivated')
        cy.contains('#tableStores tbody tr', old.name).should('not.have.class', 'text-muted')
          .and('contain', 'Active').find('button').should('contain', 'Deactivate')
      } else {
        // A fresh tenant has no old branch: add a second one instead, with the same form as a2.
        const n2 = `${NAME} B`
        second = { name: n2, reactivated: false }
        a3.do = `There is no older branch to reactivate: add a second store, **${n2}**, the same way as above.`
        a3.expect = ['The second store is listed with status **Active**.']
        cy.intercept('POST', '**/addStore').as('add2')
        cy.get('#storeName').clear().type(n2)
        cy.get('#StoresDiv button[onclick="saveStore()"]').click()
        cy.wait('@add2').then((i) => { second.id = (i.response.body.object || i.response.body.data || {}).id })
        cy.contains('#tableStores tbody tr', n2, { timeout: 10000 }).should('contain', 'Active')
      }
    })
    snap(a3, 'second-store')

    const a4 = act('Reload the page and look at the top bar.',
      ['A **store switcher** is shown (it appears once a business has two or more ACTIVE stores) and lists both stores.', 'The store you were working in is still the selected one — adding a store does not switch you to it.'])
    openDashboard()
    cy.get('#storeSwitcherWrap', { timeout: 15000 }).should('be.visible')
    cy.then(() => cy.get(`#storeSwitcher option[value="${storeId}"]`).should('exist'))
    cy.then(() => cy.get(`#storeSwitcher option[value="${second.id}"]`).should('exist'))
    cy.then(() => { if (activeBefore) cy.get('#storeSwitcher').should('have.value', String(activeBefore)) })
    // Open the switcher so the picture shows the list, not a closed box. It is a bootstrap-select (every
    // <select> is), so the list is the wrapper's menu; a plain select cannot be photographed open.
    cy.get('#storeSwitcherWrap').then(($w) => {
      const btn = $w.find('.bootstrap-select .dropdown-toggle')
      if (btn.length) {
        cy.wrap(btn).click()
        cy.get('#storeSwitcherWrap .dropdown-menu').should('be.visible').and('contain', NAME)
      }
    })
    snap(a4, 'switcher')
    cy.get('body').type('{esc}')

    const c1 = act('Open **Settings → Stores** and click **Deactivate** on the new store’s row. Confirm.',
      ['The message says **Store deactivated.**, the row turns grey with status **Inactive**, and its button now reads **Reactivate**.',
       'The store switcher no longer offers it (with one active store left, the switcher disappears).'], { cleanup: true })
    openDashboard()
    settingsItem('showStores')
    // L15 — the store you are WORKING in has a Deactivate button too, but the server refuses it; that refusal
    // is unit-tested (StoreStatusRuleTest). This case deactivates the new store, which the owner is not in.
    cy.intercept('POST', '**/updateStore').as('upd')
    cy.contains('#tableStores tbody tr', NAME, { timeout: 10000 }).contains('button', /^Deactivate$/).click()
    cy.get('[data-ui-confirm="ok"]').should('be.visible').click()
    cy.wait('@upd').its('response.body.status').should('eq', 'SUCCESS')
    cy.get('#storeMsg').should('be.visible').and('contain', 'Store deactivated')
    cy.contains('#tableStores tbody tr', NAME, { timeout: 10000 }).should('contain', 'Inactive')
      .and('have.class', 'text-muted').find('button').should('contain', 'Reactivate')
    // The fields the button did not mean to change survive it — updateStore overwrites whatever it is sent.
    cy.contains('#tableStores tbody tr', NAME).should('contain', 'GB' + run).and('contain', 'Test Road 1')
    snap(c1, 'inactive')
    cy.request('/getMyStores').then((r) => expect(list(r.body).map((s) => s.id), 'switcher list').not.to.include(storeId))
    // What a person SEES: the switcher is gone (one active store left), or it no longer lists this store.
    // Not the <option>s alone — loadMyStores hides the switcher without emptying it when fewer than two remain.
    cy.get('#storeSwitcherWrap').should(($w) => {
      const offered = $w.is(':visible') && $w.find(`#storeSwitcher option[value="${storeId}"]`).length > 0
      expect(offered, 'the deactivated store is not offered in the switcher').to.eq(false)
    })

    const c1b = act('Click **Deactivate** on the older branch you reactivated (or the second store you added). Confirm.',
      ['Its status is back to **Inactive**, and the store switcher disappears — no active store is left to switch between.'], { cleanup: true })
    cy.then(() => {
      cy.intercept('POST', '**/updateStore').as('upd2')
      cy.contains('#tableStores tbody tr', second.name).contains('button', /^Deactivate$/).click()
      cy.get('[data-ui-confirm="ok"]').should('be.visible').click()
      cy.wait('@upd2').its('response.body.status').should('eq', 'SUCCESS')
      cy.contains('#tableStores tbody tr', second.name).should('contain', 'Inactive')
    })
    cy.get('#storeSwitcherWrap').should('not.be.visible')
    snap(c1b, 'second-inactive')

    const revoke = `$.ajax({ type: 'POST', url: serverContext + 'assignStores', contentType: 'application/json', data: JSON.stringify({ storeIds: [], replace: true }) }).done(function (r) { console.log(r.message) })`
    const c2 = act('Adding a store also gave **you** access to it. Remove that access with the command below, then sign out and in again.',
      ['The console prints **Access updated.**', 'Only when you had **no** store access before this test — that is the case on this business. If you already worked in named stores, remove just the new store from your own row in **Team** instead.'],
      { cleanup: true, via: 'console', code: revoke })
    cy.intercept('POST', '**/assignStores').as('revoke')
    cy.window().then((w) => w.eval(revoke))
    cy.wait('@revoke').its('response.body.success').should('eq', true)
  })

  // ── F7. Opening Balances ─────────────────────────────────────────────────────────────────────────────
  caseIt('F7', 'Opening Balances: set the cutover date, record a balance, see it locked, reverse it', () => {
    const CUT = 'business.cutoverDate'
    const LOCK = 'business.cutoverLocked'
    const cname = `OB Guide ${run}`
    let cid, docNo, custTotal0
    asLifecycle()
    // Precondition: no cutover date and no lock, exactly as a business that has never migrated.
    saveBiz(LOCK, 'false'); resetBiz(LOCK); resetBiz(CUT)
    bizSetting(CUT).then((e) => expect(String(e.value || ''), 'precondition: no cutover date').to.eq(''))
    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: cname, contact: '03' + String(Date.now()).slice(-9), customerType: 'RETAILER' } })
      .its('body.status').should('eq', 'SUCCESS')
    cy.request('/getUserCustomer?q=-1').then((r) => { const c = list(r.body).find((x) => x.name === cname); expect(c).to.exist; cid = c.customerId || c.id })
    SAFETY.push(() => {
      asLifecycle()
      cy.then(() => {
        if (docNo) cy.request({ method: 'POST', url: '/reverseOpeningBalance', form: true, failOnStatusCode: false, body: { invoiceNo: docNo, reason: 'guide cleanup (safety net)' } })
      })
      saveBiz(LOCK, 'false'); resetBiz(LOCK); resetBiz(CUT)
    })

    testCase('F7', 'Opening Balances — set the cutover date, record a balance, see it locked, reverse it', {
      tenant: `${LIFECYCLE} (sacrificial — this case posts to the general ledger)`, role: 'Owner (or admin)',
      purpose: 'What each customer and supplier owed on the day the business started using MaxTheService. Each balance is a document dated the cutover day: it shows in the customer’s balance, statement, aging and credit limit, and posts Dr Receivables / Cr Owner’s Equity — never Sales. The cutover date locks on the first balance.',
      prereq: ['Signed in as the owner.', 'The cutover date is **not set** yet (the business has never migrated).', `A customer who owes nothing (the run created **${cname}**).`],
      data: ['Cutover date **2026-09-01**', 'Opening balance **45,000**, reference **notebook p.12**'],
      rollback: 'Cleanup reverses the balance (no screen yet — console command), then unlocks and clears the cutover date. The balance stays on the customer’s statement with its reversal beneath it, and the reversal posts the mirror journal (Dr 3000 Equity / Cr 1100 Receivables), so the books net to zero.',
    })

    const a1 = act('Click **Settings → Opening Balances**.',
      ['A blue box says what is **not** migrated (cash, bank, stock, loans, tax, fixed assets).',
        'Under it, in bold: **Set the cutover date before recording any balance.**',
        'The form: who owes (customer or supplier), amount owed, reference, and **Record opening balance**.'])
    openDashboard()
    settingsItem('showOpeningBalances')
    cy.get('#OpeningBalanceDiv').should('be.visible')
    cy.get('#openingBalanceScope').invoke('text').should('match', /cash|stock|tax/i)
    cy.get('#openingBalanceState b', { timeout: 10000 }).should('be.visible')
    cy.get('#openingBalanceState').invoke('text').then((t) => { a1.expect[1] = `Under it, in bold: **${t.trim()}**` })
    snap(a1, 'no-cutover')

    const a2 = act('Pick the customer, enter **45000** and click **Record opening balance** — before any cutover date is set.',
      ['It is **refused**, and the red message names the cutover date. Nothing is recorded.'])
    cy.then(() => cy.get(`#obParty option[value="${cid}"]`, { timeout: 10000 }).should('exist'))
    cy.then(() => cy.get('#obParty').select(String(cid), { force: true }))
    cy.get('#obAmount').clear().type('45000')
    cy.intercept('POST', '**/postOpeningBalance').as('post0')
    cy.get('#obPost').click()
    cy.wait('@post0').its('response.body.status').should('not.eq', 'SUCCESS')
    cy.get('#openingBalanceMsg').should('have.class', 'alert-danger').invoke('text').should('match', /cutover/i)
    cy.get('#openingBalanceMsg').invoke('text').then((t) => { a2.expect[0] = `It is **refused** with the red message: “${t.trim()}”. Nothing is recorded.` })
    snap(a2, 'refused')

    const a3 = act('Click **Set the cutover date**. In Configuration (**Accounts** category), type **2026-09-01** into the cutover date and click outside the box.',
      ['The row confirms **Saved**.'])
    cy.get('#obCutoverLink').click()
    cy.get('#ConfigDiv').should('be.visible')
    cy.get('#businessConfigBody .cfg-rail__item', { timeout: 20000 }).should('have.length.greaterThan', 3)
    configRow(CUT).scrollIntoView()
    cy.intercept('POST', '**/saveBusinessConfig').as('saveCut')
    cy.get(`#businessConfigBody [data-key="${CUT}"]`).clear().type('2026-09-01').blur()
    cy.wait('@saveCut').its('response.body.success').should('eq', true)
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    configRow(CUT).closest('.cfg-group').as('cutGrp')
    snap(a3, 'cutover-set', '@cutGrp')

    cy.request('/openingBalanceSummary').then((r) => { custTotal0 = Number(((r.body.object || r.body.data) || {}).customerTotal || 0) })
    const a4 = act('Back in **Settings → Opening Balances**: choose **Customer**, pick the customer, enter **45000**, reference **notebook p.12**, click **Record opening balance**.',
      ['Green: **Opening balance recorded.**', 'The line above now reads the cutover date, **Locked — balances have been recorded against this date**, and the business’s total entered so far, which has risen by **45,000**.'])
    openDashboard()
    settingsItem('showOpeningBalances')
    cy.get('#openingBalanceState', { timeout: 10000 }).should('contain', '2026-09-01')
    cy.then(() => cy.get(`#obParty option[value="${cid}"]`, { timeout: 10000 }).should('exist'))
    cy.then(() => cy.get('#obParty').select(String(cid), { force: true }))
    cy.get('#obAmount').clear().type('45000')
    cy.get('#obReference').clear().type('notebook p.12')
    cy.intercept('POST', '**/postOpeningBalance').as('post1')
    cy.get('#obPost').click()
    cy.wait('@post1').then((i) => {
      expect(i.response.body.status, JSON.stringify(i.response.body)).to.eq('SUCCESS')
      docNo = (i.response.body.object || i.response.body.data || {}).invoiceNo
      expect(docNo, 'the balance has a document number').to.be.a('string')
    })
    cy.get('#openingBalanceMsg').should('have.class', 'alert-success')
    cy.get('#openingBalanceState', { timeout: 10000 }).invoke('text').should('match', /lock/i)
    cy.request('/openingBalanceSummary').then((r) => {
      const d = (r.body.object || r.body.data) || {}
      expect(d.locked, 'locked by the first balance').to.eq(true)
      expect(Number(d.customerTotal) - custTotal0, 'the customer total rose by exactly the amount').to.eq(45000)
    })
    cy.get('#openingBalanceState').invoke('text').then((t) => {
      a4.expect[1] = `The line above now reads: “${t.trim()}” — the customer total is the whole business’s, and it rose by exactly **45,000** (from ${custTotal0.toLocaleString('en-US')}).`
    })
    snap(a4, 'recorded')

    const a5 = act('Open **Customers**, find the customer, and click **Statement** on its row.',
      ['The statement lists one document dated **2026-09-01** of type **Opening balance** (not “Bill”) with **45,000.00** as debit and a closing balance of **45,000.00**.', 'Write down its **Doc #** — the cleanup needs it.'])
    cy.openSection('CustomerDiv')
    cy.get('#CustomerDiv input[type="search"]').first().clear().type(cname)
    cy.then(() => cy.get(`.stmt-btn[data-pid="${cid}"]`, { timeout: 15000 }).first().click({ force: true }))
    cy.get('#StatementDialogBody table', { timeout: 15000 }).should('contain', '45000.00')
    cy.then(() => cy.get('#StatementDialogBody').should('contain', docNo))
    // L18 — named for what it is.
    cy.then(() => cy.contains('#StatementDialogBody tr', docNo).should('contain', 'Opening balance').and('not.contain', 'Bill'))
    cy.then(() => { a5.expect[1] = `Its **Doc #** is **${docNo}** on this run — write yours down; the cleanup needs it.` })
    snap(a5, 'statement')
    cy.get('body').type('{esc}')

    const a6 = act('Open **Settings → Configuration → Accounts** and look at the cutover date row.',
      ['The cutover date is greyed out and marked **Locked** — it cannot be changed while balances are recorded against it.'])
    openDashboard()
    openConfiguration()
    configRow(CUT).as('cutRow').scrollIntoView()
    cy.get(`#businessConfigBody [data-key="${CUT}"]`).should('be.disabled')
    cy.get('@cutRow').should('have.class', 'cfg-row--locked')
    cy.get('@cutRow').find('.cfg-row__locked').invoke('attr', 'title').then((t) => { a6.expect.push(`Hovering **Locked** says: “${t}”.`) })
    configRow(CUT).closest('.cfg-group').as('lockGrp')
    snap(a6, 'locked', '@lockGrp')

    const code = "$.post(serverContext + 'reverseOpeningBalance', { invoiceNo: 'DOC_NO', reason: 'guide cleanup' }).done(function (r) { console.log(r.status, r.message) })"
    const c1 = act('Reverse the balance. There is no Reverse button yet: open **Settings → Opening Balances**, press **F12**, open **Console**, paste the command below with your **Doc #**, and press **Enter**.',
      ['The console prints **SUCCESS**.', 'Reopen the customer’s **Statement**: the opening balance is still listed, followed by an **Opening balance reversed** line crediting **45,000.00**, and the **Closing balance** is **0.00**.'], { cleanup: true, via: 'console', code })
    openDashboard()
    settingsItem('showOpeningBalances')
    cy.then(() => {
      const real = code.replace('DOC_NO', docNo)
      c1.code = real
      cy.intercept('POST', '**/reverseOpeningBalance').as('rev')
      cy.window().then((w) => w.eval(real))
      cy.wait('@rev').its('response.body.status').should('eq', 'SUCCESS')
    })
    cy.openSection('CustomerDiv')
    cy.get('#CustomerDiv input[type="search"]').first().clear().type(cname)
    cy.then(() => cy.get(`.stmt-btn[data-pid="${cid}"]`, { timeout: 15000 }).first().click({ force: true }))
    // L18 — the pair, not a blank statement: the balance as entered, then its reversal, closing at 0.00.
    cy.get('#StatementDialogBody table', { timeout: 15000 }).should('contain', 'Opening balance reversed')
    cy.then(() => cy.get('#StatementDialogBody tbody tr').filter(`:contains("${docNo}")`).should('have.length', 2))
    cy.get('#StatementDialogBody').invoke('text').should('match', /Closing balance\s*0\.00/)
    cy.then(() => cy.request('/getUserCustomer?q=-1')).then((r) => {
      const c = list(r.body).find((x) => (x.customerId || x.id) === cid)
      expect(Number(c.dueAmount || 0), 'the customer owes nothing').to.eq(0)
    })
    snap(c1, 'reversed-statement')
    cy.get('body').type('{esc}')

    const c2 = act('In **Settings → Configuration → Accounts**, untick **Opening balances: cutover date locked**, then click **Reset to default** on the cutover date row.',
      ['Both rows are back to their defaults (not locked, no date).', '**Settings → Opening Balances** says **Set the cutover date before recording any balance.** again.'], { cleanup: true })
    openDashboard()
    openConfiguration()
    configRow(LOCK).scrollIntoView()
    cy.intercept('POST', '**/saveBusinessConfig').as('unlock')
    cy.get(`#businessConfigBody [data-key="${LOCK}"]`).uncheck({ force: true })
    cy.wait('@unlock').its('response.body.success').should('eq', true)
    openDashboard()
    openConfiguration()
    configRow(LOCK).find('.cfg-row__reset').then(($r) => { if ($r.length) cy.wrap($r).click() })
    configRow(CUT).find('.cfg-row__reset').click()
    cy.get('#businessConfigMsg').should('be.visible')
    bizSetting(CUT).then((e) => { expect(e.isDefault, 'cutover date is back to untouched').to.eq(true); expect(String(e.value || '')).to.eq('') })
    bizSetting(LOCK).then((e) => expect(String(e.value), 'unlocked').to.eq('false'))
    openDashboard()
    openConfiguration()
    configRow(CUT).closest('.cfg-group').as('backGrp')
    snap(c2, 'config-back', '@backGrp')
    settingsItem('showOpeningBalances')
    cy.get('#openingBalanceState b', { timeout: 10000 }).should('be.visible')
    snap(c2, 'ob-back')
  })
})
