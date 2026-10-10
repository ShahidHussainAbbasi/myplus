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
const g = guideCapture({ outDir: OUT_DIR, section: 'Selling price', keepShots: true })   // P = PR-1, Q = PR-2, R = PR-3a, X = PR-3b, S = PR-3c
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
  newPurchaseHere()
}
/**
 * TP-2 — New Purchase on the page as it is, NO reload. cy.openPurchaseSection VISITS (a reload), so a step that says
 * "without reloading" and used newPurchase() photographed a claim it never tested — and the claim was false: the
 * pickers kept the old price until a reload (TP-1).
 */
const newPurchaseHere = () => {
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
  // The form fills itself AFTER it opens (product, tax codes, barcodes, categories) and then moves the focus to Name.
  // Typing before that lands in the wrong box ("200" + "220" = 200220; "30" → 3 + a 0 on the name). Wait for the last
  // fill and the product's own name first.
  cy.intercept('GET', '**/productBarcodes*').as('formFilled')
  cy.contains('#tableProduct tr', name, { timeout: 15000 }).find('.js-edit-row').click({ force: true })
  cy.get('#ProductModal').should('have.class', 'open')
  cy.wait('@formFilled', { timeout: 15000 })
  cy.get('#prodName').should('have.value', name)
  cy.get('#prodPrice').should('not.have.value', '')
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
    cy.window().then((w) => { w.__noReload = 'P2-a4' })   // the page that cached 200; a reload wipes it — asserted at step 4
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
    newPurchaseHere()
    cy.intercept('GET', '/productStock*').as('prefill2')
    cy.then(() => cy.get('#purchaseItemDD').select(String(pid), { force: true }))
    cy.wait('@prefill2', { timeout: 15000 })
    cy.get('#purchaseSellRate').should('have.value', '250')
    cy.get('#purchasePriceEffect').should('have.attr', 'data-effect', 'same').and('contain', '250.00')
    snap(a4, 'next-bill', '#PurchaseModal .crud-box')
    cy.window().its('__noReload').should('eq', 'P2-a4')   // steps 1-4 ran on ONE page: never reloaded
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
    // The Products grid is server-paged with a 400 ms search debounce: tick only after the FULL term's page has drawn,
    // or that page redraws the rows and drops the tick (Delete then has nothing to confirm).
    // And after that page's on-hand fill: a tick made while it was in flight was seen lost (2026-10-10, "1 SELECTED"
    // over an unticked box) — recorded in the design doc §12.7, not chased here.
    cy.intercept({ method: 'GET', url: '**/getProductPage*', query: { q: pname } }).as('fullTerm')
    cy.intercept('GET', '**/productStockLevels*').as('onHand')
    gridSearch('ProductDiv', pname)
    cy.wait('@fullTerm', { timeout: 15000 })
    cy.wait('@onHand', { timeout: 15000 })
    cy.wait(500)
    cy.contains('#tableProduct tr', pname).find('input[type="checkbox"]').first().check({ force: true })
    cy.contains('#tableProduct tr', pname).find('input[type="checkbox"]').first().should('be.checked')
    cy.get('#ProductDiv button[onclick="confirmBulkDelete(\'Product\')"]').click({ force: true })
    cy.get('[data-ui-confirm="ok"]').click()
    cy.contains('#tableProduct tr', pname, { timeout: 15000 }).should('not.exist')
    snap(c1, 'deleted', '#ProductDiv')
  })

  // ── P6 · the till, same page ─────────────────────────────────────────────────────────────────────────
  // TP-1 (reported 2026-10-09 on owner.pharma@, sale 5823): the till kept the price it loaded with, and charged it.
  caseIt('P6', 'The till offers the price a purchase just set — without reloading the page', () => {
    asLifecycle()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY }, failOnStatusCode: false })
    const pname = `PRG Till ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-T-${run}`
    testCase('P6', 'The till offers the price a purchase just set — without reloading the page', {
      covers: ['TP-1'], slice: 'PR-1', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner',
      purpose: 'A shop buys stock between two sales without reloading the page. The purchase moves the selling price (Latest), and the very next sale must be offered the NEW price — before this fix the till kept the price it had loaded with, and charged it.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', '**How a purchase affects the selling price** is **Latest** (P1).',
        `A product **${pname}** selling at **200** and a supplier **${vname}** — made through the same requests the Product and Supplier forms send.`],
      data: [`Bill **${inv}**, quantity **2**, P/U price **210**, S/U price **250**`],
      rollback: 'Nothing is sold. The bill is voided on screen (stock and payment reversed); the product keeps 250 and is deactivated after the run.',
    })
    let pid
    supplier(vname); product(pname, 200, asLifecycle).then((id) => { pid = id }); SAFETY.push(() => voidIfStanding(inv))
    const section = (sel, val, div) => {
      cy.window().then((w) => w.$(sel).val(val).trigger('change'))   // as the menu does it
      cy.get('#' + div).should('be.visible')
    }
    const pickOnTill = () => {
      cy.intercept('GET', '/productStock*').as('tillStock')
      cy.then(() => cy.get(`#sellItemDD option[value="${pid}"]`, { timeout: 20000 }).should('exist'))
      cy.get('#sellItemDD').select('', { force: true })
      cy.then(() => cy.get('#sellItemDD').select(String(pid), { force: true }))
      cy.wait('@tillStock', { timeout: 15000 })
    }

    const a1 = act(`**Sale → New Sale**, pick **${pname}**. Do not add it to the cart.`,
      ['The rate shows **200** — the product’s price now.'])
    openDashboard()
    cy.window().then((w) => { w.__noReload = 'P6' })   // a reload wipes it — asserted at the last step
    section('#sellType', 'sellDiv', 'sellDiv')
    pickOnTill()
    cy.get('#sellSellRate').should(($i) => expect(Number($i.val())).to.eq(200))
    snap(a1, 'till-before', '#sellDiv')

    const a2 = act(`Without reloading: **Purchase → New Purchase**. Supplier **${vname}**, bill **${inv}**, product **${pname}**, quantity **2**, P/U **210**, S/U **250**, then **Save & Close**.`,
      ['The bill is saved and the form closes. (The selling price is now **250** — P2.)'])
    section('#purchaseType', 'purchaseDiv', 'purchaseDiv')
    newPurchaseHere()
    cy.then(() => fillLine(vname, inv, pid, 210))
    cy.get('#purchaseSellRate').clear().type('250')
    savePurchase()
    cy.then(() => catalogProduct(pid)).then((p) => expect(Number(p.sellingPrice)).to.eq(250))
    snap(a2, 'bought', '#purchaseDiv')

    const a3 = act(`Without reloading: **Sale → New Sale**, pick **${pname}** again.`,
      ['The rate shows **250** — the price the purchase just set, not the 200 the page loaded with.'])
    section('#sellType', 'sellDiv', 'sellDiv')
    pickOnTill()
    cy.get('#sellSellRate').should(($i) => expect(Number($i.val())).to.eq(250))
    cy.window().its('__noReload').should('eq', 'P6')   // steps 1-3 ran on ONE page: never reloaded
    snap(a3, 'till-after', '#sellDiv')

    const c1 = act(`Cleanup: **Purchase**, search **${inv}**, click **Void**, reason **guide test bill**, confirm.`,
      ['The bill leaves the list; with **Show voided** it is listed, marked **VOID**.'], { cleanup: true })
    voidBill(inv, pname)
    snap(c1, 'voided', '#purchaseDiv')
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
      prereq: ['Markup % **14.5** for the business, rule **Suggest** (Q1).', `A product **${pname}** at **200** in a category **${cat}**, and the supplier **${vname}** — made through the forms’ own requests.`],
      data: [`Category **${cat}**: Markup % **20**; P/U price **210** (the bill is not saved)`],
      rollback: 'The category’s markup is cleared on screen at the end. The product is deactivated after the run; the category stays (categories are not deleted).',
    })
    // Suggest, set HERE: Q5 leaves Auto on until the run's cleanup, and in Auto the suggestion line is (rightly) hidden —
    // run after Q5 in one go this case failed on inherited state, not on the product (2026-10-10).
    saveCfg('pos.pricing.markupPct', '14.5'); saveCfg('pos.pricing.markupMode', 'suggest')
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

  // ══ PR-3 · each purchase sells at its own price (Per batch) ═══════════════════════════════════════════════
  //
  // R = PR-3a (a sale's batches belong to its LINE), X = PR-3b (a purchase prices its own batch), S = PR-3c (the
  // sale is priced from the batches it takes). All on owner.lifecycle@: purchases and sales move money, so every
  // bill and invoice is VOIDED on screen as cleanup and the purchase mode is put back.

  /** The id of a supplier made by supplier(), from the list the Purchase form loads. */
  const vendorIdOf = (vname) => cy.request('/getUserVenders').then((vr) => {
    const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
    const id = (new RegExp('<option value=(\\d+)[^>]*>' + vname.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).exec(html) || [])[1]
    expect(id, 'supplier ' + vname).to.exist
    return cy.wrap(id)
  })
  /** A bill through the Purchase form's own request (setup, not under test). */
  const billByRequest = (vname, productId, qty, cost, sell, inv, batchNo) => vendorIdOf(vname).then((v) =>
    cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false, body: {
      productId, venderId: v, quantity: qty, purchaseRate: cost, 'stock.bpurchaseRate': cost, 'stock.bsellRate': sell,
      'stock.batchNo': batchNo, totalAmount: qty * cost, netAmount: qty * (sell - cost), purchaseInvoiceNo: inv } })
      .then((r) => expect(r.body.status, `bill ${inv}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')))
  /**
   * Remember the purchase mode AND the markup settings; after() puts them back exactly. The markup rule is then reset
   * to its defaults (Suggest, no %), so a batch sells at the S/U typed on its bill — the figures these cases state.
   * (In Auto, PR-3b gives a new batch the markup rule's price instead: that is Q4's subject, not these cases'.)
   */
  const guardMode = () => {
    guardPricing()
    cy.then(() => MK.filter((k) => k !== KEY).forEach((k) => cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: k }, failOnStatusCode: false })))
    modeEntry().then((e) => {
      const was = e && e.isDefault === false ? e.value : null
      SAFETY.push(() => {
        asLifecycle()
        if (was) cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: KEY, value: was }, failOnStatusCode: false })
        else cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY }, failOnStatusCode: false })
      })
    })
  }
  /** after(): void a guide invoice a failed case left standing. */
  const voidSaleIfStanding = (inv) => {
    asLifecycle()
    cy.then(() => inv.value && cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo: inv.value, reason: 'guide cleanup' }, failOnStatusCode: false }))
  }
  const receiptLines = (invoiceNo, productId) => cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo))
    .then((r) => ((r.body.object || r.body.data || {}).sales || []).filter((l) => Number(l.productId) === Number(productId)))
  const lineBatches = (l) => (l.batches || []).map((b) => `${b.batchNo || '—'}×${Number(b.quantity)}`).join(', ')

  // ── the sale screen ──
  const openSale = () => {
    cy.visitSaleScreen()
    cy.get('#sellItemDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)
  }
  const pickItem = (productId) => {
    cy.intercept('GET', '/productStock*').as('stock')
    cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 15000 }).should('exist')
    cy.get('#sellItemDD').select(String(productId), { force: true })
    cy.wait('@stock', { timeout: 15000 })
  }
  const addLine = (qty, perBatch) => {
    cy.get('#sellQuantity', { timeout: 20000 }).should('not.be.disabled').clear().type(String(qty))
    if (perBatch) cy.intercept('POST', '/batchPricePreview').as('pv')
    cy.get('#addInviceItem').click()
    if (perBatch) cy.wait('@pv')
  }
  const cartRows = () => cy.get('#tablesi tbody tr')
  const cashAndComplete = (perBatch) => {
    cy.get('#sellPayMethod').select('CASH', { force: true })
    cy.get('#sellRec').clear().type('99999')
    if (perBatch) cy.intercept('POST', '/batchPricePreview').as('check')
    cy.intercept('POST', '/addSell').as('sale')
    cy.get('#addSell').click({ timeout: 30000 })
    if (perBatch) cy.wait('@check')
  }
  /** Answer "Complete this sale?" and yield the recorded invoice number. */
  const confirmAndRecord = (holder) => {
    cy.confirmSale({ optional: true })
    return cy.wait('@sale').its('response.body').then((b) => {
      expect(b.status, JSON.stringify(b).slice(0, 300)).to.eq('SUCCESS')
      holder.value = b.object
      cy.get('#saleSuccess').should('be.visible').and('contain', b.object)
      return cy.wrap(b.object)
    })
  }
  const voidInvoice = (holder) => {
    cy.then(() => {
      const inv = holder.value
      openSale()
      gridSearch('sellDiv', inv)
      // One row per sale line, each with the invoice's Void button: any of them voids the whole invoice.
      cy.get(`button[onclick="openVoidSell(this)"][data-invoice="${inv}"]`, { timeout: 15000 }).first().click({ force: true })
      cy.get('.uiC-card .uiC-input').type('guide test sale')
      cy.intercept('POST', '**/voidSell').as('voidSell')
      cy.get('[data-ui-confirm="ok"]').click()
      cy.wait('@voidSell').its('response.body.status').should('eq', 'SUCCESS')
      // A voided SALE keeps no line rows (its lines are reversed and removed; the Audit Log keeps the record), so the
      // invoice leaves the list for good — unlike a bill, there is no "Show voided" for sales.
      gridSearch('sellDiv', inv)
      cy.get('#tableSell tbody tr', { timeout: 15000 }).should('have.length', 1).first().find('td').should('have.length', 1)
      cy.get(`button[onclick="openVoidSell(this)"][data-invoice="${inv}"]`).should('not.exist')
    })
  }

  // ── R1 · PR-3a ──
  caseIt('R1', 'One product on two lines: each line keeps its own batches, and stock is checked for both together', () => {
    asLifecycle(); guardMode()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY }, failOnStatusCode: false })
    const pname = `PRG Lines ${run}`
    const sale = { value: null }
    testCase('R1', 'One product on two lines: each line keeps its own batches, and stock is checked for both together', {
      covers: ['PR3a-1', 'PR3a-2'], slice: 'PR-3a', tenant: `${LIFECYCLE} (sacrificial — a sale posts to the ledger)`, role: 'Owner (any cashier)',
      purpose: 'A cashier can ring the same item twice (2, then 3 more). Each line must record only ITS units’ batches — returns and edits cost from them — and the stock check must count both lines together, so 4 + 4 cannot pass against 5 on the shelf.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', `A product **${pname}** at **100** with **10** in stock — made through the Product form’s own request.`],
      data: ['Lines **2** and **3** of the same product; then **4** and **4** against the **5** left'],
      rollback: 'The invoice is voided on screen (stock, balance and books reversed); the refused cart is cleared. The product is deactivated after the run.',
    })
    let pid
    cy.seedProduct({ name: pname, sellingPrice: 100, stock: 10 }).then((p) => {
      pid = p.productId
      SAFETY.push(() => { asLifecycle(); cy.request({ method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' }, body: { checked: String(p.productId) }, failOnStatusCode: false }) })
    })
    SAFETY.push(() => voidSaleIfStanding(sale))

    const a1 = act(`**Sale**: pick **${pname}**, quantity **2**, **Add to Cart**. Pick it again, quantity **3**, **Add to Cart**.`,
      ['The cart shows **two lines** of the product: **2** and **3**, each at **100**.'])
    openSale()
    cy.then(() => { pickItem(pid); addLine(2); pickItem(pid); addLine(3) })
    cartRows().should('have.length', 2)
    cartRows().eq(0).should('contain', pname).and('contain', '2')
    cartRows().eq(1).should('contain', pname).and('contain', '3')
    snap(a1, 'two-lines', '#sellDiv')

    const a2 = act('Payment **Cash**, amount received **99999**, click **Complete Sale**, then **Complete Sale** in the dialog.',
      ['“**Sale recorded successfully. Invoice INV-…**” at the top right.'])
    cashAndComplete(false)
    confirmAndRecord(sale)
    snap(a2, 'recorded')

    const a3 = act('Open the invoice’s receipt data (the **Print** button’s own request) and read each line’s batches.',
      ['The **2**-line records **2** units of stock and the **3**-line records **3** — **5** in all, not 10.'], { via: 'run' })
    cy.then(() => receiptLines(sale.value, pid)).then((ls) => {
      const got = ls.map((l) => [Number(l.quantity), (l.batches || []).reduce((n, b) => n + Number(b.quantity || 0), 0)]).sort((x, y) => x[0] - y[0])
      expect(got, JSON.stringify(ls.map(lineBatches))).to.deep.eq([[2, 2], [3, 3]])
    })
    cy.then(() => { gridSearch('sellDiv', sale.value) })
    cy.then(() => cy.contains('#tableSell tr', sale.value, { timeout: 15000 }).as('row'))
    snap(a3, 'invoice-row', '@row')

    const a4 = act(`Pick **${pname}** with quantity **4**, **Add to Cart**; again quantity **4**, **Add to Cart**; **Cash**, **Complete Sale**, confirm.`,
      [`The sale is refused: “**Not enough sellable stock — '${pname}': only 5 sellable, 8 requested.** …”`, 'Nothing is recorded; the cart is kept so the cashier can fix it.'])
    cy.then(() => { pickItem(pid); addLine(4); pickItem(pid); addLine(4) })
    cashAndComplete(false)
    cy.confirmSale({ optional: true })
    cy.wait('@sale').its('response.body').then((b) => {
      expect(b.status).to.not.eq('SUCCESS')
      expect(b.message).to.contain('only 5 sellable, 8 requested')
    })
    cy.get('#globalError, .uiC-card, .error-toast, #errorToast').filter(':visible').first().should('contain', 'only 5 sellable')
    cartRows().should('have.length', 2)
    snap(a4, 'refused')

    const c1 = act('Cleanup: **Clear Cart**.', ['The cart is empty.'], { cleanup: true })
    cy.contains('#sellDiv button', 'Clear Cart').click({ force: true })
    cy.confirmSale({ optional: true })
    cy.get('#tablesi tbody tr td').should('have.length', 1)   // DataTables' empty-table row
    snap(c1, 'cleared', '#sellDiv')

    const c2 = act('Cleanup: in the invoice list on the Sale screen, search the invoice number, click **Void**, reason **guide test sale**, confirm.',
      ['Searching the invoice number now finds nothing: a voided sale’s lines are reversed (stock, customer balance, books) and leave the list; the Audit Log keeps the record.'], { cleanup: true })
    voidInvoice(sale)
    snap(c2, 'voided', '#tableSell')
  })

  // ── X1 · PR-3b ──
  caseIt('X1', 'Per batch: a purchase prices its own batch; the product’s price and older stock are untouched', () => {
    asLifecycle()
    guardMode()
    const pname = `PRG PerBatch ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-B-${run}`, batch = `PB-${run}`
    testCase('X1', 'Per batch: a purchase prices its own batch; the product’s price and older stock are untouched', {
      covers: ['PR3b-1', 'PR3b-2'], slice: 'PR-3b', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner',
      purpose: 'For shops whose old stock must keep its old price (medicines with a printed price, FMCG at the old rate). In Per batch, a purchase’s selling rate belongs to the stock it brought in. The product’s price is not moved, and stock already on the shelf keeps selling at the product’s price.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', `A product **${pname}** selling at **200** with **3** already in stock, and a supplier **${vname}** — made through the forms’ own requests.`],
      data: [`Setting **Per batch**; bill **${inv}**, batch **${batch}**, quantity **4**, P/U **210**, S/U **250**`],
      rollback: 'The bill is voided on screen and the setting is put back to Latest on screen. The product is deactivated after the run.',
    })
    let pid
    supplier(vname)
    cy.seedProduct({ name: pname, sellingPrice: 200, stock: 3, purchasePrice: 150 }).then((p) => {
      pid = p.productId
      SAFETY.push(() => { asLifecycle(); cy.request({ method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' }, body: { checked: String(p.productId) }, failOnStatusCode: false }) })
    })
    SAFETY.push(() => voidIfStanding(inv))

    const a1 = act('**Settings → Configuration → Purchasing**: set **How a purchase affects the selling price** to **Per batch — each purchase’s stock sells at its own price**.',
      ['The message at the top says **Saved**.'])
    openDashboard(); openConfiguration()
    cy.intercept('POST', '**/saveBusinessConfig').as('saveMode')
    cy.get(`#businessConfigBody [data-key="${KEY}"]`).select('per_batch', { force: true })
    cy.wait('@saveMode').its('response.body.success').should('eq', true)
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    modeRow().closest('.cfg-group').as('grp')
    snap(a1, 'per-batch-saved', '@grp')

    const a2 = act(`Reload. **Purchase → New Purchase**: supplier **${vname}**, bill **${inv}**, product **${pname}**, quantity **4**, P/U **210**, S/U **250**, batch **${batch}**.`,
      ['Under the rates: “**This purchase’s stock will sell at 250.00. Earlier stock keeps its own price, and the product’s price stays 200.00.**”'])
    openDashboard(); newPurchase()
    cy.then(() => fillLine(vname, inv, pid, 210))
    cy.get('#purchaseQuantity').clear().type('4')
    cy.get('#purchaseSellRate').clear().type('250')
    cy.get('#purchaseBatchNo').then(($b) => { if ($b.length) cy.wrap($b).clear({ force: true }).type(batch, { force: true }) })
    cy.get('#purchasePriceEffect').should('have.attr', 'data-effect', 'batch').and('contain', '250.00').and('contain', '200.00')
    snap(a2, 'batch-effect', '#PurchaseModal .crud-box')

    const a3 = act('Click **Save & Close**. Then **Register → Products**, search the product, **Edit**, **Price history**.',
      ['Sell Price is still **200**; **Last purchase rate: 210.00**.', 'One history row only (the product’s creation): the purchase moved no price.'])
    savePurchase()
    openProduct(pname)
    cy.get('#prodPrice').should('have.value', '200')
    openHistory()
    cy.get('#priceHistoryNow [data-k=lastPurchaseRate]').should('have.text', '210.00')
    cy.get('#priceHistoryTable tbody tr').should('have.length', 1)
    snap(a3, 'history', '#PriceHistoryDialog > div')
    closeHistory(); closeProduct()

    const a4 = act(`**Sale**: pick **${pname}** and open the **Batch** list.`,
      [`The new stock: **${batch} · 4 @ 250.00 · exp …** — listed first, because it carries an expiry date and the list is earliest-expiry-first.`,
        'The stock already on the shelf: **no batch no. · 3 @ 200.00** — the product’s price.'])
    openSale()
    cy.then(() => pickItem(pid))
    cy.get('#sellBatchPickRow').should('be.visible')
    cy.get('#sellBatchPick option').should('have.length', 3)
    cy.contains('#sellBatchPick option', batch).should('contain', '4 @ 250.00')
    cy.contains('#sellBatchPick option', '3 @ 200.00').should('exist')
    cy.get('#sellBatchPickRow').as('bp')
    snap(a4, 'batches-on-till', '#sellDiv')

    const c1 = act(`Cleanup: **Purchase**, search **${inv}**, **Void**, reason **guide test bill**.`, ['The bill leaves the list; with **Show voided** it is listed, marked **VOID**.'], { cleanup: true })
    voidBill(inv, pname)
    snap(c1, 'voided', '#purchaseDiv')

    const c2 = act('Cleanup: **Settings → Configuration → Purchasing**: click **Reset** on the purchase-mode row.', ['It shows **Latest (default)** again.'], { cleanup: true })
    openDashboard(); openConfiguration()
    cy.intercept('POST', '**/resetBusinessConfig').as('reset')
    modeRow().find('.cfg-row__reset').click()
    cy.wait('@reset')
    cy.revealSetting(KEY)
    cy.get(`#businessConfigBody [data-key="${KEY}"]`).should('have.value', 'latest')
    modeRow().closest('.cfg-group').as('grp2')
    snap(c2, 'reset', '@grp2')
  })

  // ── S · PR-3c ──
  /** Per batch on, and abc-123's two purchases: OLD 7 selling at 200, NEW 10 selling at 250 (product price 240). */
  const twoBatches = (tag) => {
    const pname = `PRG Batches ${tag} ${run}`, vname = `PRG Supplier ${run}`
    const out = { pname, old: `OLD-${tag}-${run}`, neu: `NEW-${tag}-${run}`, bills: [`PRG-${tag}O-${run}`, `PRG-${tag}N-${run}`] }
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: KEY, value: 'per_batch' } })
    supplier(vname)
    cy.seedProduct({ name: pname, sellingPrice: 240 }).then((p) => {
      out.pid = p.productId
      SAFETY.push(() => { asLifecycle(); cy.request({ method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' }, body: { checked: String(p.productId) }, failOnStatusCode: false }) })
      billByRequest(vname, p.productId, 7, 150, 200, out.bills[0], out.old)
      billByRequest(vname, p.productId, 10, 210, 250, out.bills[1], out.neu)
    })
    out.bills.forEach((b) => SAFETY.push(() => voidIfStanding(b)))
    return out
  }
  const voidBills = (t) => t.bills.forEach((b) => voidBill(b, t.pname))
  const resetModeOnScreen = (c) => {
    openDashboard(); openConfiguration()
    cy.intercept('POST', '**/resetBusinessConfig').as('reset')
    modeRow().find('.cfg-row__reset').click()
    cy.wait('@reset')
    cy.revealSetting(KEY)
    cy.get(`#businessConfigBody [data-key="${KEY}"]`).should('have.value', 'latest')
    modeRow().closest('.cfg-group').as('grpS')
    snap(c, 'reset', '@grpS')
  }

  caseIt('S1', 'Per batch sale: 10 across two batches is charged 7 × 200 and 3 × 250, shown before payment', () => {
    asLifecycle(); guardMode()
    const sale = { value: null }
    const t = twoBatches('S1')
    testCase('S1', 'Per batch sale: 10 across two batches is charged 7 × 200 and 3 × 250, shown before payment', {
      covers: ['PR3c-1', 'PR3c-2', 'PR3c-3'], slice: 'PR-3c', tenant: `${LIFECYCLE} (sacrificial — sales and purchases post to the ledger)`, role: 'Owner (any cashier)',
      purpose: 'The analysis’s example. abc-123 was bought 7 to sell at 200, later 10 to sell at 250. A customer takes 10: the shop’s pick rule takes the 7 old first, so the bill is 7 at 200 plus 3 at 250 — and the cashier sees that split in the cart before taking the money.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', '**How a purchase affects the selling price** is **Per batch** (X1; set by the run through the setting’s own request).',
        `A product **${t.pname}** (its own price **240**) with two bills through the Purchase form’s own request: **${t.old}** — 7 at cost 150, S/U **200**; **${t.neu}** — 10 at cost 210, S/U **250**.`],
      data: ['Quantity **10**, the price the till offers (not typed)'],
      rollback: 'The invoice and both bills are voided on screen and the setting is put back to Latest on screen. The product is deactivated after the run.',
    })
    SAFETY.push(() => voidSaleIfStanding(sale))

    const a1 = act(`**Sale** (reload first). Pick **${t.pname}**.`,
      ['A **Batch** list appears: **Earliest expiry first (FEFO)**, then **' + t.old + ' · 7 @ 200.00** and **' + t.neu + ' · 10 @ 250.00**.',
        'S/U Price shows **200** — the price of the batch the sale will take first, not the product’s 240.'])
    openSale()
    cy.then(() => pickItem(t.pid))
    cy.get('#sellBatchPickRow').should('be.visible')
    cy.get('#sellBatchPick option').should('have.length', 3)
    cy.get('#sellBatchPick option').eq(1).should('contain', t.old).and('contain', '7 @ 200.00')
    cy.get('#sellBatchPick option').eq(2).should('contain', t.neu).and('contain', '10 @ 250.00')
    cy.get('#sellSellRate').should('have.value', '200')
    snap(a1, 'batch-list', '#sellDiv')

    const a2 = act('Quantity **10**, **Add to Cart**.',
      ['The cart shows **one line** for the product, quantity **10**, with its batches underneath: **' + t.old + ' · 7 × 200.00** and **' + t.neu + ' · 3 × 250.00**. (The invoice still records them as two lines.)',
        'A green note: “**Priced by batch: 7 @ 200.00 (' + t.old + ') + 3 @ 250.00 (' + t.neu + ')**”.', 'The total is **2150.00**.'])
    addLine(10, true)
    // CART-3: one row, the two batches as sub-lines
    cartRows().should('have.length', 1)
    cy.get('#tablesi tbody .pb-sub').should('have.length', 2)
    cy.get('#tablesi tbody .pb-sub').eq(0).should('contain', t.old).and('have.attr', 'data-qty', '7').and('contain', '200.00')
    cy.get('#tablesi tbody .pb-sub').eq(1).should('contain', t.neu).and('have.attr', 'data-qty', '3').and('contain', '250.00')
    cy.get('#sellBatchNote').should('be.visible').and('contain', '7 @ 200.00').and('contain', '3 @ 250.00')
    cy.get('#sellTotal').should('contain', '2150')
    snap(a2, 'cart-split', '#sellDiv')

    const a3 = act('Payment **Cash**, received **99999**, **Complete Sale**, confirm.',
      ['The dialog asks to complete **Total 2150.00**.', '“**Sale recorded successfully. Invoice INV-…**”.'])
    cashAndComplete(true)
    cy.get('.uiC-card').should('be.visible').and('contain', '2150.00')
    snap(a3, 'confirm', '.uiC-card')
    confirmAndRecord(sale)

    const a4 = act('Open the invoice’s receipt data (the **Print** button’s own request).',
      ['Two lines: **7 at 200.00** recording batch **' + t.old + '** ×7, and **3 at 250.00** recording **' + t.neu + '** ×3.'], { via: 'run' })
    cy.then(() => receiptLines(sale.value, t.pid)).then((ls) => {
      expect(ls.map((l) => [Number(l.quantity), Number(l.sellRate), lineBatches(l)]))
        .to.deep.eq([[7, 200, `${t.old}×7`], [3, 250, `${t.neu}×3`]])
    })
    cy.then(() => gridSearch('sellDiv', sale.value))
    cy.then(() => cy.contains('#tableSell tr', sale.value, { timeout: 15000 }).as('row'))
    snap(a4, 'invoice-row', '@row')

    const c1 = act('Cleanup: void the invoice (Sale screen list → search → **Void**, reason **guide test sale**).', ['Searching the invoice number now finds nothing — the sale is reversed and leaves the list.'], { cleanup: true })
    voidInvoice(sale)
    snap(c1, 'voided', '#tableSell')
    const c2 = act(`Cleanup: void both bills **${t.bills[0]}** and **${t.bills[1]}** (Purchase → search → **Void**).`, ['Both leave the list; with **Show voided** they are marked **VOID**.'], { cleanup: true })
    voidBills(t)
    snap(c2, 'bills-voided', '#purchaseDiv')
    const c3 = act('Cleanup: **Settings → Configuration → Purchasing**, **Reset** the purchase mode.', ['It shows **Latest (default)** again.'], { cleanup: true })
    resetModeOnScreen(c3)
  })

  caseIt('S2', 'The cashier may choose the batch, or type a price — both are respected', () => {
    asLifecycle(); guardMode()
    const sale = { value: null }
    const t = twoBatches('S2')
    testCase('S2', 'The cashier may choose the batch, or type a price — both are respected', {
      covers: ['PR3c-4', 'PR3c-5'], slice: 'PR-3c', tenant: `${LIFECYCLE} (sacrificial)`, role: 'Owner (any cashier)',
      purpose: 'The pick rule is only the default. A customer may ask for the fresh stock, so the cashier can choose a batch; and a price the cashier types (a bargain, a correction) is theirs — it is never re-priced from the batches.',
      prereq: ['As S1: Per batch, the product with OLD (7 at 200) and NEW (10 at 250).'],
      data: ['Line 1: batch **NEW**, quantity **3**', 'Line 2: S/U typed **230**, quantity **2**'],
      rollback: 'The invoice and both bills are voided on screen; the setting is put back to Latest on screen.',
    })
    SAFETY.push(() => voidSaleIfStanding(sale))

    const a1 = act(`**Sale**: pick **${t.pname}**, choose **${t.neu} · 10 @ 250.00** in **Batch**, quantity **3**, **Add to Cart**.`,
      ['S/U changes to **250** when the batch is chosen.', 'The cart shows **3 × 250** (' + t.neu + ').'])
    openSale()
    cy.then(() => pickItem(t.pid))
    cy.get('#sellBatchPick option').eq(2).then(($o) => cy.get('#sellBatchPick').select($o.val(), { force: true }))
    cy.get('#sellSellRate').should('have.value', '250.00')
    snap(a1, 'batch-chosen', '#sellDiv')
    addLine(3, true)
    cartRows().should('have.length', 1)
    cartRows().eq(0).should('contain', t.neu).and('contain', '250')

    const a2 = act(`Pick **${t.pname}** again, type **230** in **S/U Price**, quantity **2**, **Add to Cart**.`,
      ['The cart adds **2 × 230** — the typed price stands; no batch re-prices it.'])
    cy.then(() => pickItem(t.pid))
    cy.get('#sellSellRate').should('have.value', '200')
    cy.get('#sellSellRate').clear().type('230')
    addLine(2, true)
    cartRows().should('have.length', 2)
    cartRows().eq(1).should('contain', '230')
    snap(a2, 'typed-price', '#sellDiv')

    const a3 = act('**Cash**, **Complete Sale**, confirm. Then open the receipt data (the Print button’s own request).',
      ['Recorded: **3 at 250.00** from **' + t.neu + '**, and **2 at 230.00** from **' + t.old + '** (the pick rule’s first batch).'])
    cashAndComplete(true)
    confirmAndRecord(sale)
    cy.then(() => receiptLines(sale.value, t.pid)).then((ls) => {
      expect(ls.map((l) => [Number(l.quantity), Number(l.sellRate), lineBatches(l)]))
        .to.deep.eq([[3, 250, `${t.neu}×3`], [2, 230, `${t.old}×2`]])
    })
    snap(a3, 'recorded')

    const c1 = act('Cleanup: void the invoice, both bills, and **Reset** the purchase mode — as in S1.', ['The invoice leaves the sale list; with **Show voided** both bills are marked **VOID**; the setting is **Latest (default)**.'], { cleanup: true })
    voidInvoice(sale)
    voidBills(t)
    resetModeOnScreen(c1)
  })

  caseIt('S3', 'If the batches change a price after a line was added, Complete shows it first and charges nothing yet', () => {
    asLifecycle(); guardMode()
    const sale = { value: null }
    const t = twoBatches('S3')
    testCase('S3', 'If the batches change a price after a line was added, Complete shows it first and charges nothing yet', {
      covers: ['PR3c-6'], slice: 'PR-3c', tenant: `${LIFECYCLE} (sacrificial)`, role: 'Owner (any cashier)',
      purpose: 'Each Add prices its own line. Ring 7, then 3 more, and the second line was priced as if the 7 were still on the shelf (200). Complete Sale checks the whole cart against the batches first: the cart is corrected to 3 × 250 and shown, and nothing is charged until the cashier presses Complete again. What the customer pays is always what they were shown.',
      prereq: ['As S1: Per batch, the product with OLD (7 at 200) and NEW (10 at 250).'],
      data: ['Quantity **7**, then quantity **3** as a second line'],
      rollback: 'The invoice and both bills are voided on screen; the setting is put back to Latest on screen.',
    })
    SAFETY.push(() => voidSaleIfStanding(sale))

    const a1 = act(`**Sale**: pick **${t.pname}**, quantity **7**, **Add to Cart**. Pick it again, quantity **3**, **Add to Cart**.`,
      ['The cart shows **7 × 200** and **3 × 200** — each line was priced on its own.'])
    openSale()
    cy.then(() => { pickItem(t.pid); addLine(7, true); pickItem(t.pid); addLine(3, true) })
    cartRows().should('have.length', 2)
    cartRows().eq(1).should('contain', '3').and('contain', '200')
    snap(a1, 'priced-alone', '#sellDiv')

    const a2 = act('**Cash**, **Complete Sale**.',
      ['No “Complete this sale?” dialog. Instead: “**The batches this sale takes have changed its prices — the cart now shows what will be charged. Check it, then Complete Sale again.**”',
        'The second line now reads **3 × 250** (' + t.neu + '). Nothing has been recorded.'])
    let posted = 0
    cy.intercept('POST', '/addSell', () => { posted++ })
    cashAndComplete(true)
    cy.get('#sellBatchNote').should('be.visible').and('contain', 'changed its prices')
    cartRows().eq(1).should('contain', t.neu).and('contain', '250')
    cy.then(() => expect(posted, 'nothing posted').to.eq(0))
    snap(a2, 'corrected', '#sellDiv')

    const a3 = act('**Complete Sale** again, confirm.', ['The dialog asks for **Total 2150.00**; the sale is recorded as **7 at 200.00** and **3 at 250.00**.'])
    cy.intercept('POST', '/addSell').as('sale')
    cy.intercept('POST', '/batchPricePreview').as('check2')
    cy.get('#addSell').click()
    cy.wait('@check2')
    cy.get('.uiC-card').should('be.visible').and('contain', '2150.00')
    confirmAndRecord(sale)
    cy.then(() => receiptLines(sale.value, t.pid)).then((ls) => {
      expect(ls.map((l) => [Number(l.quantity), Number(l.sellRate)])).to.deep.eq([[7, 200], [3, 250]])
    })
    snap(a3, 'recorded')

    const c1 = act('Cleanup: void the invoice, both bills, and **Reset** the purchase mode — as in S1.', ['The invoice leaves the sale list; with **Show voided** both bills are marked **VOID**; the setting is **Latest (default)**.'], { cleanup: true })
    voidInvoice(sale)
    voidBills(t)
    resetModeOnScreen(c1)
  })

  // ── S4 · PB-OLD: stock bought BEFORE the switch to Per batch (owner.pharma's Desora, 2026-10-10) ──
  caseIt('S4', 'Stock bought before switching to Per batch sells at its own bill’s rate: 9 × 297.70 + 1 × 309.15', () => {
    asLifecycle(); guardMode(); guardPricing()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY }, failOnStatusCode: false })
    const sale = { value: null }
    const pname = `PRG Desora ${run}`, vname = `PRG Supplier ${run}`
    const t1 = `T1-${run}`, t2 = `T2-${run}`, bills = [`PRG-D1-${run}`, `PRG-D2-${run}`]
    testCase('S4', 'Stock bought before switching to Per batch sells at its own bill’s rate: 9 × 297.70 + 1 × 309.15', {
      covers: ['PB-OLD'], slice: 'PR-3c', tenant: `${LIFECYCLE} (sacrificial — sales and purchases post to the ledger)`, role: 'Owner (any cashier)',
      purpose: 'A pharmacy bought two batches while on Latest (one price for all stock), then switched to Per batch. Those batches were booked with no price of their own; each now sells at the sale rate on ITS OWN bill — not at the product’s single price. Decided with the owner on 10 Oct 2026 (Desora: T25791 at 297.70, T25792 at 309.15). The oldest delivery is taken first when the expiry dates are the same.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', '**How a purchase affects the selling price** is **Latest** (the run resets it).',
        `A product **${pname}** and two bills through the Purchase form’s own request, in Latest: **${t1}** — 9 at cost 260, S/U **297.70**; then **${t2}** — 9 at cost 270, S/U **309.15** (Latest makes the product’s price **309.15**).`],
      data: ['Quantity **10**, the price the till offers (not typed)'],
      rollback: 'The invoice and both bills are voided on screen and the setting is put back to Latest on screen. The product is deactivated after the run.',
    })
    supplier(vname)
    cy.seedProduct({ name: pname, sellingPrice: 260 }).then((p) => {
      const pid = p.productId
      SAFETY.push(() => { asLifecycle(); cy.request({ method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' }, body: { checked: String(pid) }, failOnStatusCode: false }) })
      bills.forEach((b) => SAFETY.push(() => voidIfStanding(b)))
      SAFETY.push(() => voidSaleIfStanding(sale))
      billByRequest(vname, pid, 9, 260, 297.70, bills[0], t1)
      billByRequest(vname, pid, 9, 270, 309.15, bills[1], t2)
      cy.then(() => catalogProduct(pid)).then((pr) => expect(Number(pr.sellingPrice), 'Latest: the last bill set the price').to.eq(309.15))

      const a1 = act('**Settings → Configuration → Purchasing**: set **How a purchase affects the selling price** to **Per batch**.',
        ['The message at the top says **Saved**.'])
      openDashboard(); openConfiguration()
      cy.intercept('POST', '**/saveBusinessConfig').as('saveMode')
      cy.get(`#businessConfigBody [data-key="${KEY}"]`).select('per_batch', { force: true })
      cy.wait('@saveMode').its('response.body.success').should('eq', true)
      cy.get('#businessConfigMsg').should('contain', 'Saved')
      modeRow().closest('.cfg-group').as('grpS4')
      snap(a1, 'per-batch-saved', '@grpS4')

      const a2 = act(`**Sale** (reload first). Pick **${pname}**.`,
        [`The **Batch** list shows **${t1} · 9 @ 297.70** and **${t2} · 9 @ 309.15** — each batch at its OWN bill’s rate, although both were bought before the switch.`,
          'S/U Price shows **297.70** — the batch the sale takes first (the oldest delivery, their expiry being the same).'])
      openSale()
      cy.then(() => pickItem(pid))
      cy.get('#sellBatchPickRow').should('be.visible')
      cy.contains('#sellBatchPick option', t1).should('contain', '9 @ 297.70')
      cy.contains('#sellBatchPick option', t2).should('contain', '9 @ 309.15')
      cy.get('#sellSellRate').should(($i) => expect(Number($i.val())).to.eq(297.7))
      snap(a2, 'batch-list', '#sellDiv')

      const a3 = act('Quantity **10**, **Add to Cart**.',
        [`One line, quantity **10**, with its batches underneath: **${t1} · 9 × 297.70** and **${t2} · 1 × 309.15**.`, 'The total is **2988.45** (= 2679.30 + 309.15) — not 10 × 309.15 = 3091.50.'])
      addLine(10, true)
      cartRows().should('have.length', 1)
      cy.get('#tablesi tbody .pb-sub').should('have.length', 2)
      cy.get('#tablesi tbody .pb-sub').eq(0).should('contain', t1).and('have.attr', 'data-qty', '9').and('contain', '297.70')
      cy.get('#tablesi tbody .pb-sub').eq(1).should('contain', t2).and('have.attr', 'data-qty', '1').and('contain', '309.15')
      cy.get('#sellTotal').should('contain', '2988.45')
      snap(a3, 'cart-split', '#sellDiv')

      const a4 = act('Payment **Cash**, received **99999**, **Complete Sale**, confirm. Then open the receipt data (the **Print** button’s own request).',
        ['The dialog asks to complete **Total 2988.45**.', `The invoice records **9 at 297.70** from **${t1}** and **1 at 309.15** from **${t2}**.`], { via: 'run' })
      cashAndComplete(true)
      cy.get('.uiC-card').should('be.visible').and('contain', '2988.45')
      snap(a4, 'confirm', '.uiC-card')
      confirmAndRecord(sale)
      cy.then(() => receiptLines(sale.value, pid)).then((ls) => {
        expect(ls.map((l) => [Number(l.quantity), Number(l.sellRate), lineBatches(l)]))
          .to.deep.eq([[9, 297.7, `${t1}×9`], [1, 309.15, `${t2}×1`]])
      })

      const c1 = act('Cleanup: void the invoice (Sale screen list → search → **Void**, reason **guide test sale**).', ['The sale is reversed and leaves the list.'], { cleanup: true })
      voidInvoice(sale)
      snap(c1, 'voided', '#tableSell')
      const c2 = act(`Cleanup: void both bills **${bills[0]}** and **${bills[1]}**.`, ['Both are marked **VOID** under **Show voided**.'], { cleanup: true })
      voidBills({ bills, pname })
      snap(c2, 'bills-voided', '#purchaseDiv')
      const c3 = act('Cleanup: **Settings → Configuration → Purchasing**, **Reset** the purchase mode.', ['It shows **Latest (default)** again.'], { cleanup: true })
      resetModeOnScreen(c3)
    })
  })

  // ══ PR-4 · the owner approves a price before customers see it ═══════════════════════════════════════

  const MODE = 'pos.pricing.markupMode'
  /** Purchase → Price approvals, on the given filter. The menu closes itself after the click. */
  const openApprovals = (status) => {
    openMenu('snavPurchase'); cy.get('#navPriceApprovals').click()
    cy.get('#PriceApprovalsDiv').should('be.visible')
    if (status != null) {
      cy.intercept('GET', '/priceApprovals*').as('paList')
      cy.get(`#PriceApprovalsDiv [data-pa-status="${status}"]`).click()
      cy.wait('@paList')
    }
  }
  const paRow = (pid) => cy.get(`#tablePriceApprovals tbody tr[data-product="${pid}"]`, { timeout: 15000 })
  /** A bill in Approval mode, entered and saved on screen (the case under test is what happens AFTER it). */
  const approvalBill = (vname, inv, pidRef, cost) => {
    openDashboard(); newPurchase()
    cy.then(() => fillLine(vname, inv, pidRef.value, cost))
    cy.get('#purchasePriceEffect', { timeout: 10000 }).should('have.attr', 'data-effect')
    savePurchase()
  }
  const resetRuleOnScreen = (step, alias) => {
    openConfiguration()
    cy.revealSetting(MODE)
    cy.intercept('POST', '**/resetBusinessConfig').as('cfgReset')
    cfgRow(MODE).find('.cfg-row__reset').click()
    cy.wait('@cfgReset')
    cy.revealSetting(MODE)
    cy.get(`#businessConfigBody [data-key="${MODE}"]`).should('have.value', 'suggest')
    cfgRow(MODE).closest('.cfg-group').as(alias)
    snap(step, 'rule-reset', '@' + alias)
  }
  /** after(): a proposal a failed case left waiting is rejected, so the next run's list starts clean for it. */
  const rejectIfWaiting = (pidRef) => SAFETY.push(() => {
    if (!pidRef.value) return
    asLifecycle()
    cy.request({ url: '/priceApprovals?status=PENDING', failOnStatusCode: false }).then((r) => {
      list(r.body && (r.body.data || r.body)).filter((x) => Number(x.productId) === Number(pidRef.value))
        .forEach((x) => cy.request({ method: 'POST', url: '/rejectPriceChange', form: true, failOnStatusCode: false, body: { id: x.id, note: 'guide cleanup' } }))
    })
  })

  caseIt('T1', 'Approval: a purchase proposes the price, and the owner approves it on screen', () => {
    guardPricing()
    const pname = `PRG Approve ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-T1-${run}`
    testCase('T1', 'Approval: a purchase proposes the price, and the owner approves it on screen', {
      covers: ['PR4-1', 'PR4-2', 'PR4-3'], slice: 'PR-4', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner (only owner/admin approve)',
      purpose: 'For a shop where nobody but the owner sets prices: a purchase never moves the selling price by itself. It proposes the price it would set; the owner sees a count on the Purchase menu, reviews the change (old, new, %, why, which bill) and approves it. Only then do customers pay the new price, and the history says it was approved.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', 'Markup % **14.5** (set by the run through the settings’ own request).',
        `A product **${pname}** selling at **200** and the supplier **${vname}** — made through the same requests the forms send.`],
      data: [`Rule **Approval**; bill **${inv}**, quantity **2**, P/U **210**`],
      rollback: 'The bill is voided on screen and the rule is Reset to Suggest on screen. The product keeps the approved 240.45 (a void does not undo a price decision) and is deactivated after the run.',
    })
    saveCfg('pos.pricing.markupPct', '14.5')
    const pidRef = { value: null }
    supplier(vname); product(pname, 200, asLifecycle).then((id) => { pidRef.value = id }); SAFETY.push(() => voidIfStanding(inv)); rejectIfWaiting(pidRef)

    const a1 = act('**Settings → Configuration → Purchasing**: set **Price from the purchase cost (markup rule)** to **Approval — each new price waits for you**.',
      ['The message at the top says **Saved**.', 'The help under the setting says a new price waits in **Purchase → Price approvals** until an owner or admin approves it.'])
    openDashboard(); openConfiguration()
    cfgSet(MODE, 'approval')
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    cfgRow(MODE).should('contain', 'Price approvals')
    cfgRow(MODE).closest('.cfg-group').as('t1g')
    snap(a1, 'approval-saved', '@t1g')

    const a2 = act(`Reload. **Purchase → New Purchase**: supplier **${vname}**, bill **${inv}**, product **${pname}**, quantity **2**, P/U **210**.`,
      ['Under the rates: “**Saving sends 240.45 for approval; the selling price stays 200.00 until an owner or admin approves it (Purchase → Price approvals).**”',
        'No **Use** button: in Approval the purchase form decides nothing.'])
    openDashboard(); newPurchase()
    cy.then(() => fillLine(vname, inv, pidRef.value, 210))
    cy.get('#purchasePriceEffect', { timeout: 10000 }).should('have.attr', 'data-effect', 'approval').and('contain', '240.45').and('contain', '200.00')
    cy.get('#purchaseSuggestApply').should('not.exist')
    snap(a2, 'will-wait', '#PurchaseModal .crud-box')

    const a3 = act('Click **Save & Close**.',
      ['The bill is saved and the form closes.', 'The **Purchase** menu button now carries a red count — the number of prices waiting.', 'The product still sells at **200**.'])
    savePurchase()
    cy.get('#paCountTop', { timeout: 15000 }).should('be.visible').invoke('text').then((n) => expect(Number(n)).to.be.greaterThan(0))
    cy.then(() => catalogProduct(pidRef.value)).then((p) => expect(Number(p.sellingPrice), 'not moved yet').to.eq(200))
    snap(a3, 'badge', '#snavPurchase')

    const a4 = act('Open **Purchase → Price approvals**.',
      [`The **Waiting** list has a row for **${pname}**: Now **200.00**, New **240.45**, Change **+20.2%**, Why **14.5% on cost, the business rate**, Bill **${inv}**, with **Approve** and **Reject** buttons.`])
    openApprovals()
    cy.then(() => paRow(pidRef.value).as('t1row'))
    cy.get('@t1row').should('contain', '200.00').and('contain', '240.45').and('contain', '+20.2%').and('contain', '14.5% on cost').and('contain', inv)
    cy.get('@t1row').find('.pa-approve').should('be.visible')
    snap(a4, 'waiting', '#PriceApprovalsDiv')

    const a5 = act('Click **Approve** on that row.',
      [`A dialog asks “**Approve this price?** Set the selling price of ${pname} from 200.00 to 240.45. Customers are charged the new price from now on.”`])
    cy.get('@t1row').find('.pa-approve').click()
    cy.get('.uiC-card').should('be.visible').and('contain', 'Approve this price?').and('contain', 'from 200.00 to 240.45')
    snap(a5, 'confirm', '.uiC-card')

    const a6 = act('Click **Approve** in the dialog.',
      ['“**Price approved.**” The row leaves the Waiting list.', 'Under **Approved**, the row is listed with the label **Approved**.'])
    cy.intercept('POST', '/approvePriceChange').as('approve')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@approve').its('response.body.success').should('eq', true)
    cy.then(() => cy.get(`#tablePriceApprovals tbody tr[data-product="${pidRef.value}"]`).should('not.exist'))
    cy.intercept('GET', '/priceApprovals*').as('paList')
    cy.get('#PriceApprovalsDiv [data-pa-status="APPROVED"]').click(); cy.wait('@paList')
    cy.then(() => paRow(pidRef.value).first().should('contain', 'Approved').and('contain', '240.45'))
    snap(a6, 'approved', '#PriceApprovalsDiv')

    const a7 = act(`**Register → Products**, search **${pname}**, **Edit**, then **Price history**.`,
      ['Sell Price is **240.45**.', `The newest history row: **200.00 → 240.45 · Approved · ${inv}** — the bill that proposed it.`])
    openProduct(pname)
    cy.get('#prodPrice').should('have.value', '240.45')
    openHistory()
    cy.get('#priceHistoryTable tbody tr').eq(0).should('have.attr', 'data-source', 'APPROVAL').and('contain', 'Approved').find('[data-k=ref]').should('have.text', inv)
    snap(a7, 'history', '#PriceHistoryDialog > div')
    closeHistory(); closeProduct()

    const c1 = act(`Cleanup: **Purchase**, search **${inv}**, **Void**, reason **guide test bill**.`, ['The bill leaves the list; with **Show voided** it is listed, marked **VOID**.'], { cleanup: true })
    voidBill(inv, pname)
    snap(c1, 'voided', '#purchaseDiv')
    const c2 = act('Cleanup: **Settings → Configuration → Purchasing**, click **Reset** on the markup rule.', ['It shows **Suggest (default)** again.'], { cleanup: true })
    resetRuleOnScreen(c2, 't1c')
  })

  caseIt('T2', 'Reject: the price stays, and the reason is kept with the proposal', () => {
    guardPricing()
    const pname = `PRG Reject ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-T2-${run}`, why = 'too high for this street'
    testCase('T2', 'Reject: the price stays, and the reason is kept with the proposal', {
      covers: ['PR4-4'], slice: 'PR-4', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner',
      purpose: 'Not every new cost should reach the shelf. Rejecting leaves the price exactly as it was, records who decided and why, and the decision stays visible under Rejected.',
      prereq: ['Rule **Approval**, Markup % **14.5** (set by the run through the settings’ own request).', `A product **${pname}** at **200** and the supplier **${vname}**.`,
        `A saved bill **${inv}** (quantity 2, P/U 210) — entered on the Purchase form as in T1.`],
      data: [`Reason **${why}**`],
      rollback: 'The bill is voided on screen; the rule goes back to Suggest after the run. The product is deactivated after the run.',
    })
    saveCfg('pos.pricing.markupPct', '14.5'); saveCfg(MODE, 'approval')
    const pidRef = { value: null }
    supplier(vname); product(pname, 200, asLifecycle).then((id) => { pidRef.value = id }); SAFETY.push(() => voidIfStanding(inv)); rejectIfWaiting(pidRef)
    approvalBill(vname, inv, pidRef, 210)

    const a1 = act(`**Purchase → Price approvals**. On the row for **${pname}** (200.00 → 240.45), click **Reject**.`,
      ['A dialog asks “**Reject this price?** The price stays 200.00. The decision is kept with the purchase.” with a **Reason (optional)** box.'])
    openApprovals()
    cy.then(() => paRow(pidRef.value).as('t2row'))
    cy.get('@t2row').should('contain', '240.45').find('.pa-reject').click()
    cy.get('.uiC-card').should('be.visible').and('contain', 'Reject this price?').and('contain', 'The price stays 200.00')
    cy.get('.uiC-card .uiC-input').type(why)
    snap(a1, 'reject-dialog', '.uiC-card')

    const a2 = act(`Type **${why}** as the reason and click **Reject**.`,
      ['“**Price change rejected.**” The row leaves the Waiting list.', `Under **Rejected** it is listed with the label **Rejected** and the reason **${why}**.`])
    cy.intercept('POST', '/rejectPriceChange').as('reject')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@reject').its('response.body.success').should('eq', true)
    cy.then(() => cy.get(`#tablePriceApprovals tbody tr[data-product="${pidRef.value}"]`).should('not.exist'))
    cy.intercept('GET', '/priceApprovals*').as('paList')
    cy.get('#PriceApprovalsDiv [data-pa-status="REJECTED"]').click(); cy.wait('@paList')
    cy.then(() => paRow(pidRef.value).first().should('contain', 'Rejected').and('contain', why))
    snap(a2, 'rejected', '#PriceApprovalsDiv')

    const a3 = act(`**Register → Products**, search **${pname}**, **Edit**, then **Price history**.`,
      ['Sell Price is still **200**; **Last purchase rate: 210.00**; one history row only (the product’s creation) — a rejected change leaves no price row.'])
    openProduct(pname)
    cy.get('#prodPrice').should('have.value', '200')
    openHistory()
    cy.get('#priceHistoryNow [data-k=lastPurchaseRate]').should('have.text', '210.00')
    cy.get('#priceHistoryTable tbody tr').should('have.length', 1)
    snap(a3, 'history', '#PriceHistoryDialog > div')
    closeHistory(); closeProduct()

    const c1 = act(`Cleanup: **Purchase**, search **${inv}**, **Void**, reason **guide test bill**.`, ['The bill leaves the list; with **Show voided** it is listed, marked **VOID**.'], { cleanup: true })
    voidBill(inv, pname)
    snap(c1, 'voided', '#purchaseDiv')
  })

  caseIt('T3', 'A price changed by someone else meanwhile is never overwritten: Approve is refused', () => {
    guardPricing()
    const pname = `PRG Stale ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-T3-${run}`
    testCase('T3', 'A price changed by someone else meanwhile is never overwritten: Approve is refused', {
      covers: ['PR4-5'], slice: 'PR-4', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner',
      purpose: 'The owner decides on what the screen showed. If the price moves after the list was opened — an admin re-prices the product by hand in another tab — Approve must not silently replace that price. It is refused, the list shows the price as it is now, and the owner decides again.',
      prereq: ['Rule **Approval**, Markup % **14.5** (set through the settings’ own request).', `A product **${pname}** at **200**, the supplier **${vname}**, and a saved bill **${inv}** (quantity 2, P/U 210) entered on the Purchase form.`],
      data: ['The other tab’s new price **205**'],
      rollback: 'The waiting proposal is rejected on screen; the bill is voided on screen; the rule goes back to Suggest after the run. The product keeps 205 and is deactivated after the run.',
    })
    saveCfg('pos.pricing.markupPct', '14.5'); saveCfg(MODE, 'approval')
    const pidRef = { value: null }
    supplier(vname); product(pname, 200, asLifecycle).then((id) => { pidRef.value = id }); SAFETY.push(() => voidIfStanding(inv)); rejectIfWaiting(pidRef)
    approvalBill(vname, inv, pidRef, 210)

    const a1 = act('Open **Purchase → Price approvals** and leave it open.', [`The row for **${pname}** reads Now **200.00** → New **240.45**.`])
    openApprovals()
    cy.then(() => paRow(pidRef.value).should('contain', '200.00').and('contain', '240.45'))
    snap(a1, 'listed', '#PriceApprovalsDiv')

    const a2 = act(`Meanwhile, in another tab, someone opens **${pname}** on **Register → Products** and saves Sell Price **205**. (The run sends the Product form’s own save request.)`,
      ['The product now sells at **205**. The approvals screen still shows 200.00 — it was opened before.'])
    cy.then(() => catalogProduct(pidRef.value)).then((p) => cy.request({ method: 'POST', url: '/updateProduct', headers: { 'Content-Type': 'application/json' },
      body: Object.assign({}, p, { sellingPrice: 205 }) }))
    cy.then(() => catalogProduct(pidRef.value)).then((p) => expect(Number(p.sellingPrice)).to.eq(205))
    cy.then(() => paRow(pidRef.value).should('contain', '200.00'))

    const a3 = act('Click **Approve** on the row, then **Approve** in the dialog.',
      ['A **Not saved** message: “**The price is now 205.00 — it changed since this was proposed. Reload and decide again.**”',
        'Nothing changed: the product still sells at **205**.'])
    cy.then(() => paRow(pidRef.value).find('.pa-approve').click())
    cy.intercept('POST', '/approvePriceChange').as('approve')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@approve').its('response.body.success').should('eq', false)
    // The confirm card is still leaving as the alert arrives: name the alert, not "every card".
    cy.contains('.uiC-card', 'Not saved').should('be.visible').and('contain', 'The price is now 205.00').as('t3alert')
    cy.then(() => catalogProduct(pidRef.value)).then((p) => expect(Number(p.sellingPrice), 'not overwritten').to.eq(205))
    snap(a3, 'refused', '@t3alert')

    const a4 = act('Click **OK**.', ['The list has reloaded: the row now reads Now **205.00** → New **240.45**, still waiting for a decision.'])
    cy.get('@t3alert').find('[data-ui-confirm="ok"]').click()
    cy.then(() => paRow(pidRef.value).should('contain', '205.00').and('contain', '240.45').find('.pa-approve').should('be.visible'))
    snap(a4, 'reloaded', '#PriceApprovalsDiv')

    const c1 = act('Cleanup: click **Reject** on the row, reason **guide cleanup**, confirm.', ['The row leaves the Waiting list; the product still sells at **205**.'], { cleanup: true })
    cy.then(() => paRow(pidRef.value).find('.pa-reject').click())
    cy.get('.uiC-card .uiC-input').type('guide cleanup')
    cy.intercept('POST', '/rejectPriceChange').as('reject')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@reject').its('response.body.success').should('eq', true)
    cy.then(() => cy.get(`#tablePriceApprovals tbody tr[data-product="${pidRef.value}"]`).should('not.exist'))
    snap(c1, 'rejected', '#PriceApprovalsDiv')
    const c2 = act(`Cleanup: **Purchase**, search **${inv}**, **Void**, reason **guide test bill**.`, ['The bill leaves the list; with **Show voided** it is listed, marked **VOID**.'], { cleanup: true })
    voidBill(inv, pname)
    snap(c2, 'voided', '#purchaseDiv')
  })

  caseIt('T4', 'Auto: a change Auto holds back is sent for approval instead of being lost', () => {
    guardPricing()
    const pname = `PRG Held ${run}`, vname = `PRG Supplier ${run}`, inv = `PRG-T4-${run}`
    testCase('T4', 'Auto: a change Auto holds back is sent for approval instead of being lost', {
      covers: ['PR4-6'], slice: 'PR-4', tenant: `${LIFECYCLE} (sacrificial — a purchase posts to the ledger)`, role: 'Owner',
      purpose: 'Auto never lowers a price by itself (Q5), but a cheaper cost may still deserve a lower price. Instead of dropping that change, the purchase sends it to Price approvals with the reason it was held, so the owner can still choose it.',
      prereq: ['Rule **Auto**, Markup % **14.5**, “Auto never lowers a price” on (set through the settings’ own request).', `A product **${pname}** at **300** and the supplier **${vname}**.`],
      data: [`Bill **${inv}**, quantity **2**, P/U **210**`],
      rollback: 'The proposal is rejected on screen and the bill voided on screen; the rule goes back to Suggest after the run. The product keeps 300 and is deactivated after the run.',
    })
    saveCfg('pos.pricing.markupPct', '14.5'); saveCfg(MODE, 'auto'); resetCfg('pos.pricing.markupNeverLower')
    const pidRef = { value: null }
    supplier(vname); product(pname, 300, asLifecycle).then((id) => { pidRef.value = id }); SAFETY.push(() => voidIfStanding(inv)); rejectIfWaiting(pidRef)

    const a1 = act(`**Purchase → New Purchase**: supplier **${vname}**, bill **${inv}**, product **${pname}**, quantity **2**, P/U **210**.`,
      ['“**The selling price stays 300.00: your markup rule gives 240.45, and Auto never lowers a price. It waits for approval in Purchase → Price approvals.**”'])
    openDashboard(); newPurchase()
    cy.then(() => fillLine(vname, inv, pidRef.value, 210))
    cy.get('#purchasePriceEffect', { timeout: 10000 }).should('have.attr', 'data-effect', 'held').and('contain', '300.00').and('contain', '240.45').and('contain', 'Price approvals')
    snap(a1, 'held-queued', '#PurchaseModal .crud-box')

    const a2 = act('Click **Save & Close**, then open **Purchase → Price approvals**.',
      [`The product still sells at **300**. The Waiting list has **${pname}**: Now **300.00** → New **240.45**, Change **-19.9%** (in red), Why **Auto never lowers a price**.`])
    savePurchase()
    cy.then(() => catalogProduct(pidRef.value)).then((p) => expect(Number(p.sellingPrice)).to.eq(300))
    openApprovals()
    cy.then(() => paRow(pidRef.value).should('contain', '300.00').and('contain', '240.45').and('contain', '-19.9%').and('contain', 'Auto never lowers a price'))
    snap(a2, 'queued', '#PriceApprovalsDiv')

    const c1 = act('Cleanup: click **Reject** on the row, reason **guide cleanup**, confirm.', ['The row leaves the Waiting list; the price stays **300**.'], { cleanup: true })
    cy.then(() => paRow(pidRef.value).find('.pa-reject').click())
    cy.get('.uiC-card .uiC-input').type('guide cleanup')
    cy.intercept('POST', '/rejectPriceChange').as('reject')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@reject').its('response.body.success').should('eq', true)
    snap(c1, 'rejected', '#PriceApprovalsDiv')
    const c2 = act(`Cleanup: **Purchase**, search **${inv}**, **Void**, reason **guide test bill**.`, ['The bill leaves the list; with **Show voided** it is listed, marked **VOID**.'], { cleanup: true })
    voidBill(inv, pname)
    snap(c2, 'voided', '#purchaseDiv')
  })

  caseIt('T5', 'Only owners and admins decide prices — a user does not see the screen, and is refused', () => {
    cy.loginAsOwner()
    const code = "fetch('/priceApprovals?status=PENDING').then(r => r.json()).then(j => console.log(j.success, j.message || ''))"
    testCase('T5', 'Only owners and admins decide prices — a user does not see the screen, and is refused', {
      covers: ['PR4-7'], slice: 'PR-4', tenant: 'owner.business@myplus.com with its admin.business@ and user.business@ members', role: 'Admin, then User',
      purpose: 'Approving a price is an owner’s decision. An admin gets the same screen as the owner; a user sees neither the menu item nor the count, and the server refuses them even when asked directly.',
      prereq: ['Passwords `Demo@2025!`.'],
      data: ['None saved'],
      rollback: 'Nothing changes.',
    })

    const a1 = act('Sign in as **admin.business@myplus.com**. Open the **Purchase** menu and click **Price approvals**.',
      ['The menu has **Price approvals**; the screen opens on **Waiting** with the filters **Waiting · Approved · Rejected · All**.'])
    cy.loginAsTier('admin', 'business'); openDashboard()
    openApprovals()
    cy.get('#PriceApprovalsDiv [data-pa-status]').should('have.length', 4)
    cy.get('#PriceApprovalsDiv [data-pa-status="PENDING"]').should('have.class', 'active')
    snap(a1, 'admin', '#PriceApprovalsDiv')

    const a2 = act('Sign in as **user.business@myplus.com**. Open the **Purchase** menu.',
      ['There is **no Price approvals** item and no red count — for a user they are not on the page at all.'])
    cy.loginAsTier('user', 'business'); openDashboard()
    openMenu('snavPurchase')
    cy.get('#navPriceApprovals').should('not.exist')
    cy.get('#paCountTop').should('not.exist')
    cy.get('#PriceApprovalsDiv').should('not.exist')
    snap(a2, 'user-no-item', '#snavPurchase')

    const a3 = act('Still as the user, press **F12 → Console**, paste the command and press **Enter**.',
      ['The console prints **false** and a refusal — no proposals come back.'], { via: 'console', code })
    cy.then(() => g.runInPage(code, 'GET', 'priceApprovals')).then((body) => {
      expect(body && body.success, JSON.stringify(body).slice(0, 200)).to.eq(false)
      expect(list(body && body.data), 'no rows in a refusal').to.have.length(0)
      a3.expect.push(`This run’s answer: “${(body.message || '').slice(0, 140)}”.`)
    })
  })
})
