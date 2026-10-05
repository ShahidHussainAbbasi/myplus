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
const g = guideCapture({ outDir: OUT_DIR, section: 'Selling price', keepShots: true })
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
        'Opening the list shows exactly two choices: **Latest** and **Keep**.'])
    openDashboard(); openConfiguration()
    cy.get(`#businessConfigBody [data-key="${KEY}"]`).should('have.value', 'latest')
      .find('option').then(($o) => expect([...$o].map((o) => o.value)).to.deep.eq(['latest', 'keep']))
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
})
