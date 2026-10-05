/**
 * PR-1 — what a purchase does to the selling price, and the price history — the manual test cases as REAL
 * step-by-step tests.
 *
 * Each `it` is one complete manual case: prerequisites, test data, numbered actions, the expected result of each
 * action, and the cleanup. Every action is PERFORMED on the screen, ASSERTED, and PHOTOGRAPHED, in that order; the
 * page builder (docs/guides/build-price-mode-guide.js) marks a case Verified only if the whole case passed.
 *
 * TENANTS. Cases that save a purchase move money (cash out, stock in, the ledger), so they run on owner.lifecycle@,
 * the Test Book's sacrificial business, and VOID their bill on screen as cleanup. The role case runs on
 * owner.business@ (its admin./user. members) and saves nothing but a test product. Setup that is not under test (a
 * product to buy, a supplier to buy from) is made through the same request the screen sends, and the prerequisites
 * say so. Every case registers an undo in SAFETY, run in after(), so a case that fails half-way still cleans up.
 *
 * Run headed, ONE case at a time (memory):
 *   npx cypress run --headed --browser electron --spec cypress/e2e/docs/price-mode-guide.cy.js \
 *     --config trashAssetsBeforeRuns=false --env guideOnly=P2
 * Build the page:   node docs/guides/build-price-mode-guide.js <outDir>
 */
import { guideCapture } from '../../support/guide-capture'

const OUT_DIR = 'cypress/guide-out/price-mode'
const g = guideCapture({ outDir: OUT_DIR, section: 'Selling price', keepShots: true })   // P = PR-1, Q = PR-2
const { caseIt, testCase, act, snap } = g

const KEY = 'pos.pricing.purchaseMode'
const LIFECYCLE = 'owner.lifecycle@myplus.com'
const PW = 'Demo@2025!'
const run = String(Date.now()).slice(-6)
const SAFETY = []

const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return Array.isArray(b) ? b : [] }
const asLifecycle = () => cy.loginAs(LIFECYCLE, PW, '/getBusinessDashboardStats')
const openDashboard = () => { cy.visit('/businessDashboard'); cy.waitForAppReady() }
const openMenu = (dd) => {
  cy.get(`#${dd}`).then(($d) => { if (!$d.hasClass('snav-open')) cy.get(`#${dd} .snav-btn`).click() })
  cy.get(`#${dd}`).should('have.class', 'snav-open')
}

// ── setup through the screens' own requests ──────────────────────────────────────────────────────────────
/** A test product; after() deactivates it AS ITS OWN TENANT (`login`), whatever the run signed in as last. */
const product = (name, price, login) => cy.seedProduct({ name, sellingPrice: price }).then((p) => {
  const id = p.productId
  SAFETY.push(() => { login(); cy.request({ method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' }, body: { checked: String(id) }, failOnStatusCode: false }) })
  return cy.wrap(id)
})
/** after(): void a guide bill a failed case left standing (looked up by its number; a voided one is left alone). */
const voidIfStanding = (inv) => {
  asLifecycle()
  cy.request('/getUserPurchase').then((r) => {
    list(r.body).filter((x) => x.purchaseInvoiceNo === inv && x.status !== 'VOID')
      .forEach((x) => cy.request({ method: 'POST', url: '/voidPurchase', form: true, failOnStatusCode: false, body: { purchaseId: x.purchaseId, reason: 'guide cleanup' } }))
  })
}
const supplier = (name) => cy.ensureCompany().then((companyId) =>
  cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
    body: { name, companyId, mobile: '0301' + run.padStart(7, '0'), email: `prg${run}@t.com` } })
    .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.be.oneOf(['SUCCESS', 'FOUND'])))
const catalogProduct = (id) => cy.request('/getCatalogProduct?id=' + id).then((r) => r.body.data)
const modeEntry = () => cy.request('/getBusinessConfig').then((r) => list(r.body).find((e) => e.key === KEY))

// ── screens ──────────────────────────────────────────────────────────────────────────────────────────────
const openConfiguration = () => {
  openMenu('snavSettings'); cy.get('#snavSettings a[onclick^="showBusinessConfig("]').click()
  cy.get('#businessConfigBody .cfg-rail__item', { timeout: 20000 }).should('have.length.greaterThan', 3)
  cy.revealSetting(KEY)
}
const modeRow = () => cy.get(`#businessConfigBody [data-key="${KEY}"]`).closest('.cfg-row')

const newPurchase = () => {
  cy.openPurchaseSection('purchaseDiv')
  cy.get('#newPurchase').click()
  cy.get('#PurchaseModal').should('have.class', 'open')
  cy.settled('#purchaseInvoiceNo')
}
const fillLine = (vendorName, inv, productId, cost) => {
  cy.get('#purchaseVenderDD option', { timeout: 15000 }).contains(vendorName)
    .then(($o) => cy.get('#purchaseVenderDD').select($o.val(), { force: true }))
  cy.get('#purchaseInvoiceNo').clear().type(inv)
  cy.intercept('GET', '/productStock*').as('prefill')
  cy.get('#purchaseItemDD').select(String(productId), { force: true })
  cy.wait('@prefill', { timeout: 15000 })
  cy.get('#purchaseQuantity').clear().type('2')
  cy.get('#purchasePurchaseRate').clear().type(String(cost))
}
const savePurchase = () => {
  cy.intercept('POST', '/addPurchase').as('save')
  cy.get('#addPurchase').click()
  cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
  cy.get('#PurchaseModal').should('not.have.class', 'open')
}
/** Type into a grid's search box. Re-queried between clear and type: the grid redraws as the box empties. */
const gridSearch = (div, text) => {
  cy.get(`#${div} input[type="search"]`).first().clear()
  cy.get(`#${div} input[type="search"]`).first().type(text)
}
const purchaseRow = (inv) => {
  cy.openPurchaseSection('purchaseDiv')
  gridSearch('purchaseDiv', inv)
  return cy.get(`.purchase-void-btn[data-inv="${inv}"]`, { timeout: 15000 }).closest('tr')
}
const voidBill = (inv, pname) => {
  purchaseRow(inv).find('.purchase-void-btn').click({ force: true })
  cy.get('.uiC-card .uiC-input').type('guide test bill')
  cy.intercept('POST', '**/voidPurchase').as('void')
  cy.get('[data-ui-confirm="ok"]').click()
  cy.wait('@void').its('response.body.status').should('eq', 'SUCCESS')
  // The grid carries the bill number only on the Void button, which a void removes — so the stored bill is the proof,
  // and the grid must show a VOID label with no Void button left for this bill.
  cy.request('/getUserPurchase').then((r) => {
    const mine = list(r.body).filter((x) => x.purchaseInvoiceNo === inv)
    expect(mine.length, `bill ${inv}`).to.be.greaterThan(0)
    mine.forEach((x) => expect(x.status, `${inv} line ${x.purchaseId}`).to.eq('VOID'))
  })
  cy.get(`.purchase-void-btn[data-inv="${inv}"]`).should('not.exist')
  // Voided bills leave the list; "Show voided" brings them back, marked VOID.
  gridSearch('purchaseDiv', inv)
  cy.contains('#purchaseDiv', 'No matching records', { timeout: 15000 })
  cy.contains('#purchaseDiv button', 'Show voided').click()
  cy.contains('#purchaseDiv tr', inv, { timeout: 15000 }).should('contain', 'VOID')
}
const openProduct = (name) => {
  cy.window().then((w) => w.showProducts())
  cy.get('#ProductDiv').should('be.visible')
  gridSearch('ProductDiv', name)
  cy.contains('#tableProduct tr', name, { timeout: 15000 }).find('.js-edit-row').click({ force: true })
  cy.get('#ProductModal').should('have.class', 'open')
}
const closeProduct = () => cy.get('#ProductModal .crud-x, #ProductModal [data-dismiss], #ProductModal button:contains("Cancel")').first().click({ force: true })
const openHistory = () => {
  cy.get('#prodPriceHistoryBtn').should('be.visible').click()
  cy.get('#PriceHistoryDialog').should('be.visible')
  cy.get('#priceHistoryNow', { timeout: 15000 }).should('be.visible')
}
const closeHistory = () => cy.contains('#PriceHistoryDialog button', 'Close').click()

describe('Selling price — what a purchase does to it, step by step (captured)', () => {
  beforeEach(() => cy.viewport(1366, 860))
  afterEach(g.write)
  after(() => { SAFETY.slice().reverse().forEach((fn) => fn()) })

  // ── P1 · the setting ─────────────────────────────────────────────────────────────────────────────────
  caseIt('P1', 'The owner finds how a purchase affects the selling price — Latest by default', () => {
    asLifecycle()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY }, failOnStatusCode: false })
    testCase('P1', 'The owner finds how a purchase affects the selling price — Latest by default', {
      covers: ['PR1-1'], slice: 'PR-1', tenant: `${LIFECYCLE} (sacrificial)`, role: 'Owner (only owner/admin change settings)',
      purpose: 'One setting decides whether a purchase moves the product’s selling price. A shop that never touches it behaves exactly as before: the purchase’s sell rate becomes the price.',
      prereq: ['Signed in as the owner.', 'The setting has never been changed on this business (the run resets it first).'],
      data: ['None — nothing is saved'],
      rollback: 'Nothing changes.',
    })
    const a1 = act('Click **Settings → Configuration**, open the **Purchasing** group and find **How a purchase affects the selling price**.',
      ['The setting shows **Latest (default) — the purchase’s sell rate becomes the price**.',
        'The help says Keep never changes the selling price, and that every change is kept in the product’s price history.',
        'Opening the list shows three choices: **Latest**, **Keep** and **Per batch**.'])
    openDashboard(); openConfiguration()
    cy.get(`#businessConfigBody [data-key="${KEY}"]`).should('have.value', 'latest')
      .find('option').then(($o) => expect([...$o].map((o) => o.value)).to.deep.eq(['latest', 'keep', 'per_batch']))
    modeRow().should('contain', 'price history')
    modeRow().closest('.cfg-group').as('grp')
    snap(a1, 'setting', '@grp')
  })

  // ── P2 · LATEST ──────────────────────────────────────────────────────────────────────────────────────
  caseIt('P2', 'Latest: the purchase form says the price will change, and the history names the bill', () => {
    asLifecycle()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY }, failOnStatusCode: false })
    const pname = `PRG Latest ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-L-${run}`
    testCase('P2', 'Latest: the purchase form says the price will change, and the history names the bill', {
      covers: ['PR1-2', 'PR1-3', 'PR1-5'], slice: 'PR-1', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner',
      purpose: 'Today’s behaviour, made visible: the purchase form says **before saving** that the price of ALL stock moves from the old price to the new one, and afterwards the product’s Price history shows which bill moved it.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', '**How a purchase affects the selling price** is **Latest** (P1).',
        `A product **${pname}** selling at **200** and a supplier **${vname}** — made through the same requests the Product and Supplier forms send.`],
      data: [`Bill **${inv}**, quantity **2**, P/U price **210**, S/U price **250**`],
      rollback: 'The bill is voided on screen (stock and payment reversed). The product keeps 250 and its history — a void does not undo a price decision — and the product is deactivated after the run.',
    })
    let pid
    supplier(vname); product(pname, 200, asLifecycle).then((id) => { pid = id }); SAFETY.push(() => voidIfStanding(inv))

    const a1 = act(`**Purchase → New Purchase**. Choose supplier **${vname}**, bill number **${inv}**, product **${pname}**, quantity **2**, P/U price **210**.`,
      ['S/U price fills with the product’s current price, **200**.', 'Under the rates: “**The selling price stays 200.00.**”'])
    openDashboard(); newPurchase()
    cy.then(() => fillLine(vname, inv, pid, 210))
    cy.get('#purchaseSellRate').should('have.value', '200')
    cy.get('#purchasePriceEffect').should('be.visible').and('have.attr', 'data-effect', 'same').and('contain', '200.00')
    snap(a1, 'filled', '#PurchaseModal .crud-box')

    const a2 = act('Change S/U price to **250**.',
      ['The line under the rates now reads “**Saving changes this product’s selling price from 200.00 to 250.00, for all stock.**”'])
    cy.get('#purchaseSellRate').clear().type('250')
    cy.get('#purchasePriceEffect').should('have.attr', 'data-effect', 'change').and('contain', '200.00').and('contain', '250.00')
    snap(a2, 'will-change', '#PurchaseModal .crud-box')

    const a3 = act('Click **Save & Close**.', ['The bill is saved and the form closes.'])
    savePurchase()
    cy.then(() => catalogProduct(pid)).then((p) => expect(Number(p.sellingPrice)).to.eq(250))
    snap(a3, 'saved')

    const a4 = act(`Without reloading, open **New Purchase** again and pick **${pname}**.`,
      ['S/U price now fills with **250** — the price the last bill set — and the line says “**The selling price stays 250.00.**”'])
    newPurchase()
    cy.intercept('GET', '/productStock*').as('prefill2')
    cy.then(() => cy.get('#purchaseItemDD').select(String(pid), { force: true }))
    cy.wait('@prefill2', { timeout: 15000 })
    cy.get('#purchaseSellRate').should('have.value', '250')
    cy.get('#purchasePriceEffect').should('have.attr', 'data-effect', 'same').and('contain', '250.00')
    snap(a4, 'next-bill', '#PurchaseModal .crud-box')
    cy.get('#PurchaseModal .crud-x').first().click({ force: true })

    const a5 = act(`**Register → Products**, search **${pname}**, click **Edit**, then the **Price history** link under Sell Price.`,
      ['Sell Price on the form is **250**.',
        'The dialog shows **Selling price now: 250.00 · Last purchase rate: 210.00**.',
        `Two rows, newest first: **200.00 → 250.00 · Purchase · ${inv}**, then **— → 200.00 · Product edit** (when the product was created).`])
    openProduct(pname)
    cy.get('#prodPrice').should('have.value', '250')
    openHistory()
    cy.get('#priceHistoryNow [data-k=sellingPrice]').should('have.text', '250.00')
    cy.get('#priceHistoryNow [data-k=lastPurchaseRate]').should('have.text', '210.00')
    cy.get('#priceHistoryTable tbody tr').should('have.length', 2)
    cy.get('#priceHistoryTable tbody tr').eq(0).should('have.attr', 'data-source', 'PURCHASE').find('[data-k=ref]').should('have.text', inv)
    cy.get('#priceHistoryTable tbody tr').eq(1).should('have.attr', 'data-source', 'MANUAL').find('[data-k=old]').should('have.text', '')
    snap(a5, 'history', '#PriceHistoryDialog > div')
    closeHistory(); closeProduct()

    const c1 = act(`Cleanup: **Purchase**, search **${inv}**, click **Void**, reason **guide test bill**, confirm.`,
      ['The bill leaves the list; click **Show voided** and it is listed again, marked **VOID** — its stock and payment are reversed.', 'The product still sells at **250** — a void does not undo a price change; change it on the product if you need to.'],
      { cleanup: true })
    voidBill(inv, pname)
    cy.then(() => catalogProduct(pid)).then((p) => expect(Number(p.sellingPrice)).to.eq(250))
    snap(c1, 'voided', '#purchaseDiv')
  })

  // ── P3 · KEEP ────────────────────────────────────────────────────────────────────────────────────────
  caseIt('P3', 'Keep: a purchase leaves the selling price alone — and still records what it cost', () => {
    asLifecycle()
    const pname = `PRG Keep ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-K-${run}`
    modeEntry().then((e) => {
      const was = e && e.isDefault === false ? e.value : null
      SAFETY.push(() => {
        asLifecycle()
        if (was) cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: KEY, value: was }, failOnStatusCode: false })
        else cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY }, failOnStatusCode: false })
      })
    })
    testCase('P3', 'Keep: a purchase leaves the selling price alone — and still records what it cost', {
      covers: ['PR1-4', 'PR1-6'], slice: 'PR-1', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner',
      purpose: 'For a shop with fixed shelf prices: buying the same item at a new rate does not re-price the stock already on the shelf. The cost is still recorded, so margins stay honest.',
      prereq: ['Signed in as the owner of owner.lifecycle@.',
        `A product **${pname}** selling at **200** and the supplier **${vname}** — made through the same requests the forms send.`],
      data: [`Setting **Keep**; bill **${inv}**, quantity **2**, P/U price **210**, S/U price **250**`],
      rollback: 'The bill is voided and the setting is put back to **Latest** on screen. The product is deactivated after the run.',
    })
    let pid
    supplier(vname); product(pname, 200, asLifecycle).then((id) => { pid = id }); SAFETY.push(() => voidIfStanding(inv))

    const a1 = act('**Settings → Configuration → Purchasing**: set **How a purchase affects the selling price** to **Keep — a purchase never changes the selling price**.',
      ['The message at the top says **Saved**. (The next time Configuration opens, the row is marked as changed and has a **Reset** button.)'])
    openDashboard(); openConfiguration()
    cy.intercept('POST', '**/saveBusinessConfig').as('saveMode')
    cy.get(`#businessConfigBody [data-key="${KEY}"]`).select('keep', { force: true })
    cy.wait('@saveMode').its('response.body.success').should('eq', true)
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    modeRow().closest('.cfg-group').as('grp')
    snap(a1, 'keep-saved', '@grp')

    const a2 = act(`Reload the page. **Purchase → New Purchase**: supplier **${vname}**, bill **${inv}**, product **${pname}**, quantity **2**, P/U **210**, S/U **250**.`,
      ['Under the rates: “**The selling price stays 200.00 — purchases do not change prices (Settings › Purchasing).**”', 'Nothing on the line says 250 will become the price.'])
    openDashboard(); newPurchase()
    cy.then(() => fillLine(vname, inv, pid, 210))
    cy.get('#purchaseSellRate').clear().type('250')
    cy.get('#purchasePriceEffect').should('be.visible').and('have.attr', 'data-effect', 'keep').and('contain', '200.00').and('not.contain', '250.00')
    snap(a2, 'stays', '#PurchaseModal .crud-box')

    const a3 = act('Click **Save & Close**.', ['The bill is saved and the form closes.'])
    savePurchase()
    snap(a3, 'saved')

    const a4 = act(`**Register → Products**, search **${pname}**, **Edit**, then **Price history**.`,
      ['Sell Price is still **200**.', 'The dialog shows **Selling price now: 200.00 · Last purchase rate: 210.00** — the cost was recorded.',
        'One row only: **— → 200.00 · Product edit**. The purchase moved no price, so it left no price row.'])
    openProduct(pname)
    cy.get('#prodPrice').should('have.value', '200')
    openHistory()
    cy.get('#priceHistoryNow [data-k=sellingPrice]').should('have.text', '200.00')
    cy.get('#priceHistoryNow [data-k=lastPurchaseRate]').should('have.text', '210.00')
    cy.get('#priceHistoryTable tbody tr').should('have.length', 1).first().should('have.attr', 'data-source', 'MANUAL')
    snap(a4, 'history', '#PriceHistoryDialog > div')
    closeHistory(); closeProduct()

    const c1 = act(`Cleanup: **Purchase**, search **${inv}**, **Void** with reason **guide test bill**.`, ['The bill leaves the list; with **Show voided** it is listed, marked **VOID**.'], { cleanup: true })
    voidBill(inv, pname)
    snap(c1, 'voided', '#purchaseDiv')

    const c2 = act('Cleanup: open **Settings → Configuration → Purchasing** again: the row is marked as changed. Click its **Reset**.',
      ['The setting shows **Latest (default)** again and the Reset button is gone.'], { cleanup: true })
    openConfiguration()
    cy.intercept('POST', '**/resetBusinessConfig').as('reset')
    modeRow().find('.cfg-row__reset').click()
    cy.wait('@reset')
    cy.revealSetting(KEY)
    cy.get(`#businessConfigBody [data-key="${KEY}"]`).should('have.value', 'latest')
    modeEntry().then((e) => expect(e.isDefault, 'back to never-chosen').to.eq(true))
    modeRow().closest('.cfg-group').as('grp2')
    snap(c2, 'reset', '@grp2')
  })

  // ── P4 · manual edit ─────────────────────────────────────────────────────────────────────────────────
  caseIt('P4', 'A price changed by hand on the product is in the history too', () => {
    asLifecycle()
    const pname = `PRG Edit ${run}`
    testCase('P4', 'A price changed by hand on the product is in the history too', {
      covers: ['PR1-7'], slice: 'PR-1', tenant: `${LIFECYCLE} (sacrificial)`, role: 'Owner',
      purpose: 'The history is every change to the selling price, not only purchases: the product form, a purchase and a CSV import all record one.',
      prereq: ['Signed in as the owner.', `A product **${pname}** selling at **200** (made through the same request the Product form sends).`],
      data: ['New Sell Price **220**'],
      rollback: 'No money moves. The product is deactivated on screen.',
    })
    product(pname, 200, asLifecycle)

    const a1 = act(`**Register → Products**, search **${pname}**, **Edit**. Change **Sell Price** to **220** and click **Save & Close**.`, ['The form closes; the list shows **220**.'])
    openDashboard(); openProduct(pname)
    cy.get('#prodPrice').clear().type('220')
    cy.intercept('POST', '**/updateProduct').as('upd')
    cy.get('#addProduct').click()
    cy.wait('@upd').its('response.body.success').should('eq', true)
    cy.contains('#tableProduct tr', pname, { timeout: 15000 }).should('contain', '220')
    snap(a1, 'saved', '#ProductDiv')

    const a2 = act('Click **Edit** on it again, then **Price history**.',
      ['Two rows, newest first: **200.00 → 220.00 · Product edit**, then **— → 200.00 · Product edit**. Reference is empty for a hand edit.'])
    openProduct(pname)
    openHistory()
    cy.get('#priceHistoryTable tbody tr').should('have.length', 2)
    cy.get('#priceHistoryTable tbody tr').eq(0).should('have.attr', 'data-source', 'MANUAL').within(() => {
      cy.get('[data-k=old]').should('have.text', '200.00'); cy.get('[data-k=new]').should('have.text', '220.00')
    })
    snap(a2, 'history', '#PriceHistoryDialog > div')
    closeHistory(); closeProduct()

    const c1 = act(`Cleanup: in **Register → Products**, tick **${pname}**, click **Delete**, confirm.`, ['It leaves the list (it is deactivated; its history is kept).'], { cleanup: true })
    gridSearch('ProductDiv', pname)
    cy.contains('#tableProduct tr', pname).find('input[type="checkbox"]').first().check({ force: true })
    cy.get('#ProductDiv button[onclick="confirmBulkDelete(\'Product\')"]').click({ force: true })
    cy.get('[data-ui-confirm="ok"]').click()
    cy.contains('#tableProduct tr', pname, { timeout: 15000 }).should('not.exist')
    snap(c1, 'deleted', '#ProductDiv')
  })

  // ── P5 · who sees it ─────────────────────────────────────────────────────────────────────────────────
  caseIt('P5', 'Only the owner and admins see the price history — it shows the cost', () => {
    cy.loginAsOwner()
    const pname = `PRG Roles ${run}`
    const code = (id) => `fetch('/productPriceHistory?productId=${id}').then(r => r.json()).then(j => console.log(j.success, j.message || ''))`
    testCase('P5', 'Only the owner and admins see the price history — it shows the cost', {
      covers: ['PR1-8', 'PR1-9'], slice: 'PR-1', tenant: 'owner.business@myplus.com with its admin.business@ and user.business@ members', role: 'Admin, then User',
      purpose: 'The history carries the last purchase rate, which a cashier’s screens never show. An admin sees the link and the dialog; a user sees neither, and the server refuses them even when asked directly.',
      prereq: [`A product **${pname}** selling at **200** on owner.business@ (made through the Product form’s request).`, 'Passwords `Demo@2025!`.'],
      data: ['None saved'],
      rollback: 'The product is deactivated after the run. Nothing else changes.',
    })
    let pid
    product(pname, 200, () => cy.loginAsOwner()).then((id) => { pid = id })

    const a1 = act(`Sign in as **admin.business@myplus.com**. **Register → Products**, search **${pname}**, **Edit**, click **Price history**.`,
      ['The link is under Sell Price; the dialog opens with one row, **— → 200.00 · Product edit**.'])
    cy.loginAsTier('admin', 'business'); openDashboard(); openProduct(pname)
    openHistory()
    cy.get('#priceHistoryTable tbody tr').should('have.length', 1)
    snap(a1, 'admin', '#PriceHistoryDialog > div')
    closeHistory(); closeProduct()

    const a2 = act('Sign in as **user.business@myplus.com**. **Register → Products → New Product** (or Edit on any product you can see).',
      ['There is **no Price history link** under Sell Price — for a user it is not on the page at all.'])
    cy.loginAsTier('user', 'business'); openDashboard()
    cy.window().then((w) => { w.showProducts(); w.newProduct() })
    cy.get('#ProductModal').should('have.class', 'open')
    cy.get('#prodPrice').should('be.visible')
    cy.get('#prodPriceHistoryBtn').should('not.exist')
    snap(a2, 'user-no-link', '#ProductModal .crud-box')
    closeProduct()

    const a3 = act('Still as the user, press **F12 → Console**, paste the command (with the product’s number) and press **Enter**.',
      ['The console prints **false** and a refusal — no prices and no cost come back.'], { via: 'console', code: code('<productId>') })
    cy.then(() => g.runInPage(code(pid), 'GET', 'productPriceHistory')).then((body) => {
      expect(body && body.success, JSON.stringify(body).slice(0, 200)).to.eq(false)
      expect(body.data, 'no history in a refusal').to.not.exist
      a3.expect.push(`This run’s answer: “${(body.message || '').slice(0, 140)}”.`)
    })
  })

  // ══ PR-2 · the markup rule ═══════════════════════════════════════════════════════════════════════════

  const MK = ['pos.pricing.markupMode', 'pos.pricing.markupPct', 'pos.pricing.markupBasis', 'pos.pricing.markupRounding',
    'pos.pricing.markupNeverLower', 'pos.pricing.markupMaxRisePct', KEY]
  /** Remember the lifecycle tenant's pricing settings; after() puts them back exactly. */
  const guardPricing = () => {
    asLifecycle()
    cy.request('/getBusinessConfig').then((r) => {
      const all = list(r.body)
      const was = MK.map((k) => { const e = all.find((x) => x.key === k); return { k, chosen: !!e && e.isDefault === false, v: e && e.value } })
      SAFETY.push(() => {
        asLifecycle()
        was.forEach((w) => cy.request({ method: 'POST', url: w.chosen ? '/saveBusinessConfig' : '/resetBusinessConfig', form: true,
          body: w.chosen ? { key: w.k, value: w.v } : { key: w.k }, failOnStatusCode: false }))
      })
    })
  }
  const saveCfg = (key, value) => cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value } })
  const resetCfg = (key) => cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key }, failOnStatusCode: false })
  /** What an owner does on Configuration: change the box; the row saves on its own. */
  const cfgSet = (key, value) => {
    cy.revealSetting(key)
    cy.intercept('POST', '**/saveBusinessConfig').as('cfgSave')
    cy.get(`#businessConfigBody [data-key="${key}"]`).then(($el) => {
      if ($el.is('select')) cy.wrap($el).select(String(value), { force: true })
      else { cy.wrap($el).clear(); cy.get(`#businessConfigBody [data-key="${key}"]`).type(String(value)).blur() }
    })
    cy.wait('@cfgSave').its('response.body.success').should('eq', true)
  }
  const cfgRow = (key) => cy.get(`#businessConfigBody [data-key="${key}"]`).closest('.cfg-row')

  caseIt('Q1', 'The owner sets a markup: 14.5% on cost, and sees what that means', () => {
    guardPricing()
    testCase('Q1', 'The owner sets a markup: 14.5% on cost, and sees what that means', {
      covers: ['PR2-1'], slice: 'PR-2', tenant: `${LIFECYCLE} (sacrificial)`, role: 'Owner (only owner/admin change settings)',
      purpose: 'The rule that suggests a selling price from what a purchase cost. Nothing happens until a percentage is set: there is no platform default. The screen spells out the difference between a markup on cost and a margin of the price, because shops mean different things by “14.5%”.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', 'No markup set yet (the run resets it first).'],
      data: ['Markup % **14.5**'],
      rollback: 'Every pricing setting the case touched is put back as it was after the run.',
    })
    const a1 = act('**Settings → Configuration → Purchasing**. Find **Price from the purchase cost (markup rule)**, **Markup %**, **The markup % is**, **Round the suggested price**, **Auto never lowers a price** and **Auto raises a price by at most (%)**.',
      ['The rule is **Suggest (default)**, Markup % is **0**, the % is **On cost (markup) — 100 → 114.50 at 14.5%**, rounding **Exact**, never lower **on**, rise limit **0**.',
        'The other choice of “The markup % is” reads **Of the price (margin) — 100 → 116.96 at 14.5%**.'])
    cy.then(() => MK.forEach(resetCfg))
    openDashboard(); openConfiguration()
    cy.revealSetting('pos.pricing.markupMode')
    cy.get('#businessConfigBody [data-key="pos.pricing.markupMode"]').should('have.value', 'suggest')
    cy.get('#businessConfigBody [data-key="pos.pricing.markupBasis"] option[value="margin"]').should('contain', '116.96')
    cfgRow('pos.pricing.markupMode').closest('.cfg-group').as('g1')
    snap(a1, 'rule-settings', '@g1')

    const a2 = act('Type **14.5** into **Markup %** and click outside the box.', ['The message at the top says **Saved**.'])
    cfgSet('pos.pricing.markupPct', '14.5')
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    cfgRow('pos.pricing.markupPct').closest('.cfg-group').as('g2')
    snap(a2, 'pct-saved', '@g2')
  })

  caseIt('Q2', 'Suggest: the purchase form offers the rule’s price, and one click uses it', () => {
    guardPricing()
    const pname = `PRG Markup ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-M-${run}`
    testCase('Q2', 'Suggest: the purchase form offers the rule’s price, and one click uses it', {
      covers: ['PR2-2', 'PR2-3'], slice: 'PR-2', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner',
      purpose: 'With a markup set, the purchase form suggests a selling price from the cost being typed, says which percentage it used, and offers a button to use it. The owner still decides: nothing changes unless the button is pressed.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', 'Markup % **14.5**, rule **Suggest** (Q1).',
        `A product **${pname}** selling at **200**, supplier **${vname}** — made through the forms’ own requests.`],
      data: [`Bill **${inv}**, quantity **2**, P/U price **210**`],
      rollback: 'The bill is voided on screen; the price it set stays (a void does not undo a price). Settings are put back after the run.',
    })
    let pid
    saveCfg('pos.pricing.markupPct', '14.5')
    supplier(vname); product(pname, 200, asLifecycle).then((id) => { pid = id }); SAFETY.push(() => voidIfStanding(inv))

    const a1 = act(`**Purchase → New Purchase**: supplier **${vname}**, bill **${inv}**, product **${pname}**, quantity **2**, P/U price **210**.`,
      ['A line appears: “**Suggested selling price 240.45 (14.5% on cost, the business rate).**” with a **Use 240.45** button.',
        'S/U still shows **200**: nothing is applied by itself.'])
    openDashboard(); newPurchase()
    cy.then(() => fillLine(vname, inv, pid, 210))
    cy.get('#purchaseSuggest', { timeout: 10000 }).should('be.visible').and('have.attr', 'data-price', '240.45').and('contain', '14.5% on cost')
    cy.get('#purchaseSellRate').should('have.value', '200')
    snap(a1, 'suggested', '#PurchaseModal .crud-box')

    const a2 = act('Click **Use 240.45** (straight from the P/U box, without clicking elsewhere first).',
      ['S/U becomes **240.45** and the button goes.', 'The line under the rates: “**Saving changes this product’s selling price from 200.00 to 240.45, for all stock.**”'])
    cy.get('#purchaseSuggestApply').click()
    cy.get('#purchaseSellRate').should('have.value', '240.45')
    cy.get('#purchaseSuggestApply').should('not.exist')
    cy.get('#purchasePriceEffect').should('have.attr', 'data-effect', 'change').and('contain', '240.45')
    snap(a2, 'used', '#PurchaseModal .crud-box')

    const a3 = act('Click **Save & Close**, then open the product and its **Price history**.',
      [`Sell Price **240.45**; the newest history row is **200.00 → 240.45 · Purchase · ${inv}**.`])
    savePurchase()
    openProduct(pname)
    cy.get('#prodPrice').should('have.value', '240.45')
    openHistory()
    cy.get('#priceHistoryTable tbody tr').eq(0).should('have.attr', 'data-source', 'PURCHASE').find('[data-k=new]').should('have.text', '240.45')
    snap(a3, 'history', '#PriceHistoryDialog > div')
    closeHistory(); closeProduct()

    const c1 = act(`Cleanup: **Purchase**, search **${inv}**, **Void**, reason **guide test bill**.`, ['The bill leaves the list; with **Show voided** it is listed, marked **VOID**.'], { cleanup: true })
    voidBill(inv, pname)
    snap(c1, 'voided', '#purchaseDiv')
  })

  caseIt('Q3', 'A product’s own markup wins over the business’s', () => {
    guardPricing()
    const pname = `PRG Own ${run}`, vname = `PRG Supplier ${run}`
    testCase('Q3', 'A product’s own markup wins over the business’s', {
      covers: ['PR2-4'], slice: 'PR-2', tenant: `${LIFECYCLE} (sacrificial)`, role: 'Owner',
      purpose: 'Some products carry a different margin (accessories 30%, medicines 14.5%). A Markup % on the product overrides the business’s; blank means the business’s.',
      prereq: ['Markup % **14.5** for the business (Q1).', `A product **${pname}** at **200** and the supplier **${vname}**.`],
      data: ['Product Markup % **30**; P/U price **210** (the bill is not saved)'],
      rollback: 'No bill is saved. The product is deactivated after the run.',
    })
    saveCfg('pos.pricing.markupPct', '14.5')
    let pid
    supplier(vname); product(pname, 200, asLifecycle).then((id) => { pid = id })

    const a1 = act(`**Register → Products**, search **${pname}**, **Edit**. Type **30** into **Markup %** (under Sell Price) and click **Save & Close**.`,
      ['The form closes. Edit it again: **Markup %** shows **30**.'])
    openDashboard(); openProduct(pname)
    cy.get('#prodMarkupPct').should('have.value', '').type('30')
    cy.intercept('POST', '**/updateProduct').as('upd')
    cy.intercept('GET', '**/getProductPage*').as('reload')
    cy.get('#addProduct').click()
    cy.wait('@upd').its('response.body.success').should('eq', true)
    // Wait for the list's own reload to land before reopening it. Reopening mid-reload throws inside DataTables
    // (a reload delivered into a table that showProducts() just rebuilt) — a pre-existing defect, recorded, not PR-2.
    cy.wait('@reload')
    cy.get('#tableProduct_processing').should('not.be.visible')
    openProduct(pname)
    cy.get('#prodMarkupPct').should('have.value', '30')
    snap(a1, 'own-markup', '#ProductModal .crud-box')
    closeProduct()

    const a2 = act(`**Purchase → New Purchase**: supplier **${vname}**, product **${pname}**, quantity **2**, P/U price **210**. Then close the form without saving.`,
      ['“**Suggested selling price 273.00 (30% on cost, this product’s own).**”'])
    newPurchase()
    cy.then(() => fillLine(vname, `PRG-X-${run}`, pid, 210))
    cy.get('#purchaseSuggest', { timeout: 10000 }).should('have.attr', 'data-price', '273.00').and('contain', 'this product')
    snap(a2, 'suggested-own', '#PurchaseModal .crud-box')
    cy.get('#PurchaseModal .crud-x').first().click({ force: true })
  })

  caseIt('Q4', 'Auto: saving a purchase sets the price by the rule, and says so first', () => {
    guardPricing()
    const pname = `PRG Auto ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-A-${run}`
    testCase('Q4', 'Auto: saving a purchase sets the price by the rule, and says so first', {
      covers: ['PR2-5', 'PR2-6'], slice: 'PR-2', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner',
      purpose: 'In Auto the rule decides the selling price on every purchase, whatever is typed in S/U. The purchase form says what will be set before saving, and the history records it as the markup rule with the bill.',
      prereq: ['Markup % **14.5** (Q1).', `A product **${pname}** at **200** and the supplier **${vname}**.`],
      data: [`Rule **Auto**; bill **${inv}**, quantity **2**, P/U **210**, S/U typed **250**`],
      rollback: 'The bill is voided on screen and the rule is Reset to Suggest on screen. The product keeps 240.45 (a void does not undo a price) and is deactivated after the run.',
    })
    saveCfg('pos.pricing.markupPct', '14.5')
    let pid
    supplier(vname); product(pname, 200, asLifecycle).then((id) => { pid = id }); SAFETY.push(() => voidIfStanding(inv))

    const a1 = act('**Settings → Configuration → Purchasing**: set **Price from the purchase cost (markup rule)** to **Auto — saving a purchase sets the price**.', ['The message at the top says **Saved**.'])
    openDashboard(); openConfiguration()
    cfgSet('pos.pricing.markupMode', 'auto')
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    cfgRow('pos.pricing.markupMode').closest('.cfg-group').as('g4')
    snap(a1, 'auto-saved', '@g4')

    const a2 = act(`Reload. **Purchase → New Purchase**: supplier **${vname}**, bill **${inv}**, product **${pname}**, quantity **2**, P/U **210**, S/U **250**.`,
      ['Under the rates: “**Saving sets this product’s selling price from 200.00 to 240.45 by your markup rule (14.5% on cost, the business rate).**”', 'No Use button: in Auto there is nothing to choose.'])
    openDashboard(); newPurchase()
    cy.then(() => fillLine(vname, inv, pid, 210))
    cy.get('#purchaseSellRate').clear().type('250')
    cy.get('#purchasePriceEffect', { timeout: 10000 }).should('have.attr', 'data-effect', 'auto').and('contain', '240.45')
    cy.get('#purchaseSuggestApply').should('not.exist')
    snap(a2, 'will-set', '#PurchaseModal .crud-box')

    const a3 = act('Click **Save & Close**, then open the product and its **Price history**.',
      ['Sell Price **240.45**: the rule’s price, not the 250 typed on the bill.', `The newest history row: **200.00 → 240.45 · Markup rule · ${inv}**.`])
    savePurchase()
    openProduct(pname)
    cy.get('#prodPrice').should('have.value', '240.45')
    openHistory()
    cy.get('#priceHistoryTable tbody tr').eq(0).should('have.attr', 'data-source', 'MARKUP').and('contain', 'Markup rule').find('[data-k=ref]').should('have.text', inv)
    snap(a3, 'history', '#PriceHistoryDialog > div')
    closeHistory(); closeProduct()

    const c1 = act(`Cleanup: **Purchase**, search **${inv}**, **Void**, reason **guide test bill**.`, ['The bill leaves the list; with **Show voided** it is listed, marked **VOID**.'], { cleanup: true })
    voidBill(inv, pname)
    snap(c1, 'voided', '#purchaseDiv')
    const c2 = act('Cleanup: open **Settings → Configuration → Purchasing** again and click **Reset** on the markup rule.', ['It shows **Suggest (default)** again.'], { cleanup: true })
    openConfiguration()
    cy.revealSetting('pos.pricing.markupMode')
    cy.intercept('POST', '**/resetBusinessConfig').as('cfgReset')
    cfgRow('pos.pricing.markupMode').find('.cfg-row__reset').click()
    cy.wait('@cfgReset')
    cy.revealSetting('pos.pricing.markupMode')
    cy.get('#businessConfigBody [data-key="pos.pricing.markupMode"]').should('have.value', 'suggest')
    cfgRow('pos.pricing.markupMode').closest('.cfg-group').as('g4c')
    snap(c2, 'reset', '@g4c')
  })

  caseIt('Q5', 'Auto never lowers a price by itself', () => {
    guardPricing()
    const pname = `PRG Lower ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-N-${run}`
    testCase('Q5', 'Auto never lowers a price by itself', {
      covers: ['PR2-7'], slice: 'PR-2', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner',
      purpose: 'A cheaper purchase must not quietly cut the shelf price. With “Auto never lowers a price” on (the default), the form says the price stays and why, and saving leaves it alone. The cost is still recorded.',
      prereq: ['Markup % **14.5**, rule **Auto** (set by the run through the settings’ own request).', `A product **${pname}** at **300** and the supplier **${vname}**.`],
      data: [`Bill **${inv}**, quantity **2**, P/U **210**`],
      rollback: 'The bill is voided on screen; the rule goes back to Suggest after the run. The product is deactivated after the run.',
    })
    saveCfg('pos.pricing.markupPct', '14.5'); saveCfg('pos.pricing.markupMode', 'auto')
    let pid
    supplier(vname); product(pname, 300, asLifecycle).then((id) => { pid = id }); SAFETY.push(() => voidIfStanding(inv))

    const a1 = act(`**Purchase → New Purchase**: supplier **${vname}**, bill **${inv}**, product **${pname}**, quantity **2**, P/U **210**.`,
      ['“**The selling price stays 300.00: your markup rule gives 240.45, and Auto never lowers a price.**”'])
    openDashboard(); newPurchase()
    cy.then(() => fillLine(vname, inv, pid, 210))
    cy.get('#purchasePriceEffect', { timeout: 10000 }).should('have.attr', 'data-effect', 'held').and('contain', '300.00').and('contain', '240.45')
    snap(a1, 'held', '#PurchaseModal .crud-box')

    const a2 = act('Click **Save & Close**, then open the product and its **Price history**.',
      ['Sell Price is still **300**; **Last purchase rate: 210.00**; one history row only (the product’s creation).'])
    savePurchase()
    openProduct(pname)
    cy.get('#prodPrice').should('have.value', '300')
    openHistory()
    cy.get('#priceHistoryNow [data-k=lastPurchaseRate]').should('have.text', '210.00')
    cy.get('#priceHistoryTable tbody tr').should('have.length', 1)
    snap(a2, 'history', '#PriceHistoryDialog > div')
    closeHistory(); closeProduct()

    const c1 = act(`Cleanup: **Purchase**, search **${inv}**, **Void**, reason **guide test bill**.`, ['The bill leaves the list; with **Show voided** it is listed, marked **VOID**.'], { cleanup: true })
    voidBill(inv, pname)
    snap(c1, 'voided', '#purchaseDiv')
  })

  caseIt('Q6', 'Markup by category: a whole category gets its own percentage', () => {
    guardPricing()
    const cat = `PRG Cat ${run}`, pname = `PRG InCat ${run}`, vname = `PRG Supplier ${run}`
    testCase('Q6', 'Markup by category: a whole category gets its own percentage', {
      covers: ['PR2b-1', 'PR2b-2'], slice: 'PR-2', tenant: `${LIFECYCLE} (sacrificial)`, role: 'Owner or admin',
      purpose: 'Shops price by department: medicines at one margin, accessories at another. A category’s markup applies to every product in it that has no markup of its own; a blank category uses the business’s.',
      prereq: ['Markup % **14.5** for the business (Q1).', `A product **${pname}** at **200** in a category **${cat}**, and the supplier **${vname}** — made through the forms’ own requests.`],
      data: [`Category **${cat}**: Markup % **20**; P/U price **210** (the bill is not saved)`],
      rollback: 'The category’s markup is cleared on screen at the end. The product is deactivated after the run; the category stays (categories are not deleted).',
    })
    saveCfg('pos.pricing.markupPct', '14.5')
    let pid
    supplier(vname)
    cy.seedProduct({ name: pname, sellingPrice: 200, category: cat }).then((p) => {
      pid = p.productId
      SAFETY.push(() => { asLifecycle(); cy.request({ method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' }, body: { checked: String(p.productId) }, failOnStatusCode: false }) })
    })
    let cid
    cy.request('/getUserCategories').then((r) => { cid = r.body.categories.find((c) => c.name === cat).id })
    SAFETY.push(() => { asLifecycle(); cy.then(() => cy.request({ method: 'POST', url: '/setCategoryMarkup', headers: { 'Content-Type': 'application/json' }, body: { categoryId: cid, markupPct: null }, failOnStatusCode: false })) })

    const a1 = act('**Settings → Markup by category**.',
      ['A table of the business’s categories, each with a **Markup %** box, blank where none is set.', 'Above it: “**The business’s markup is 14.5% — a blank category uses it.**”'])
    openDashboard()
    openMenu('snavSettings'); cy.get('#navCategoryMarkup').click()
    cy.get('#CategoryMarkupDiv').should('be.visible')
    cy.get('#cmBiz').should('contain', '14.5')
    cy.then(() => cy.get(`#cmPct_${cid}`).should('have.value', ''))
    snap(a1, 'screen', '#CategoryMarkupDiv')

    const a2 = act(`Type **20** in the box for **${cat}** and press **Tab**.`, ['The row says **Saved**.'])
    cy.intercept('POST', '**/setCategoryMarkup').as('cm')
    cy.then(() => cy.get(`#cmPct_${cid}`).type('20').blur())
    cy.wait('@cm').its('response.body.success').should('eq', true)
    cy.then(() => cy.get(`#tableCategoryMarkup tr[data-category="${cid}"] .cm-state`).should('contain', 'Saved'))
    snap(a2, 'saved', '#CategoryMarkupDiv')

    const a3 = act(`**Purchase → New Purchase**: supplier **${vname}**, product **${pname}**, quantity **2**, P/U **210**. Close it without saving.`,
      ['“**Suggested selling price 252.00 (20% on cost, this category’s).**”'])
    newPurchase()
    cy.then(() => fillLine(vname, `PRG-Y-${run}`, pid, 210))
    cy.get('#purchaseSuggest', { timeout: 10000 }).should('have.attr', 'data-price', '252.00').and('contain', 'this category')
    snap(a3, 'suggested-category', '#PurchaseModal .crud-box')
    cy.get('#PurchaseModal .crud-x').first().click({ force: true })

    const c1 = act(`Cleanup: **Settings → Markup by category**, clear the box for **${cat}** and press **Tab**.`, ['The row says **Saved**; the box is blank (the business’s 14.5% applies again).'], { cleanup: true })
    openMenu('snavSettings'); cy.get('#navCategoryMarkup').click()
    cy.then(() => cy.get(`#cmPct_${cid}`).should('have.value', '20').clear().blur())
    cy.wait('@cm').its('response.body.success').should('eq', true)
    cy.then(() => cy.get(`#tableCategoryMarkup tr[data-category="${cid}"] .cm-state`).should('contain', 'Saved'))
    snap(c1, 'cleared', '#CategoryMarkupDiv')
  })
})
