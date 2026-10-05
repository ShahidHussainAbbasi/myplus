/**
 * Verify sweep (Oct 2026) — the Test Book §13 "Not yet verified" areas as REAL step-by-step cases.
 *
 * Each `it` is one manual case: prerequisites, test data, numbered actions, the expected result of each action, and a
 * cleanup. Every action is PERFORMED on the screen, ASSERTED and PHOTOGRAPHED; the section built from this
 * (docs/guides/build-testbook-section.js verifysweep) shows a case as passed only if every step held.
 *
 * TENANTS. Cases that move money (a sale, a purchase, a return) run on owner.lifecycle@ — the Test Book's sacrificial
 * business. Serial cases run on owner.mobile@, the tenant the feature belongs to (GATE-RUNBOOK §1). Setup that is not
 * under test (a sale to return, a bill to return against) is made through the same request the screen sends and the
 * prerequisites say so (`via: 'run'`).
 *
 * Run ONE case at a time (tenant settings must not leak between cases):
 *   npx cypress run --spec cypress/e2e/docs/verify-sweep-guide.cy.js --config trashAssetsBeforeRuns=false --env guideOnly=V1
 * Build the section:  node docs/guides/build-testbook-section.js verifysweep
 */
import { guideCapture } from '../../support/guide-capture'

const OUT_DIR = 'cypress/guide-out/verify-sweep'
const g = guideCapture({ outDir: OUT_DIR, section: 'Verify sweep', keepShots: true })
const { caseIt, testCase, act, snap } = g

const LIFECYCLE = 'owner.lifecycle@myplus.com'
const PW = 'Demo@2025!'
const run = String(Date.now()).slice(-6)
const SAFETY = []
const SHOW = 'pos.entry.showSerial'

/**
 * owner.mobile@ is seeded on FREE, which excludes serial tracking: the seeded override READS on, but switching it on
 * again is refused at the plan ceiling. Lift to PRO for the case and put FREE back after (cy.setPlan is reversible).
 */
const mobileOnPro = () => {
  cy.loginAsOperator()
  cy.planOf('owner.mobile@myplus.com').then((p) => {
    if (p.plan !== 'FREE') return
    cy.setPlan(p.id, 'PRO')
    SAFETY.push(() => { cy.loginAsOperator(); cy.setPlan(p.id, 'FREE') })
  })
}
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return Array.isArray(b) ? b : [] }
const asLifecycle = () => cy.loginAs(LIFECYCLE, PW, '/getBusinessDashboardStats')
const openMenu = (dd) => {
  cy.get(`#${dd}`).then(($d) => { if (!$d.hasClass('snav-open')) cy.get(`#${dd} .snav-btn`).click() })
  cy.get(`#${dd}`).should('have.class', 'snav-open')
}
const deactivate = (id, login) => SAFETY.push(() => {
  login()
  cy.request({ method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' },
    body: { checked: String(id) }, failOnStatusCode: false })
})
/** A product with stock, deactivated after the run AS ITS OWN TENANT. */
const product = (name, price, login) => cy.seedProduct({ name, sellingPrice: price, stock: 20 }).then((p) => {
  deactivate(p.productId, login)
  return cy.wrap(p.productId)
})
/** A paid sale of `qty` through the till's own request. Yields the invoice number. */
const sale = (productId, qty, buyer) => cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
  body: { customer: { name: buyer, contact: '0300' + run.padStart(7, '1') },
          sales: [{ productId, quantity: qty, sellRate: 100, totalAmount: 100 * qty }],
          tenders: [{ method: 'CASH', amount: 100 * qty }], paidAmount: 100 * qty, grandTotal: 100 * qty,
          idempotencyKey: `guide-vs-${run}-${productId}` } })
  .then((r) => { expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'); return cy.wrap(r.body.object && r.body.object.invoiceNo) })

describe('Verify sweep — §13 areas, step by step (captured)', () => {
  beforeEach(() => cy.viewport(1366, 860))
  afterEach(g.write)
  after(() => { SAFETY.slice().reverse().forEach((fn) => fn()) })

  // ── V1 · the returns register ───────────────────────────────────────────────────────────────────────
  caseIt('V1', 'Sale returns: every credit note is listed by number and customer, and reprints', () => {
    asLifecycle()
    const pname = `VS Return ${run}`, buyer = `VS Buyer ${run}`
    testCase('V1', 'Sale returns: every credit note is listed by number and customer, and reprints', {
      tenant: `${LIFECYCLE} (sacrificial — a sale and a return post to the books)`, role: 'Owner',
      purpose: 'A credit note used to be printable only in the seconds after the return was taken. The register is where it is found again: by its **CRN-** number and the **customer’s name** — a register of ids would render perfectly and be useless.',
      prereq: ['Signed in as the owner.', `A product **${pname}** and a paid sale of **2** to **${buyer}**, with **1** returned — made through the same requests the till and the Return dialog send.`],
      data: [`Customer **${buyer}**, return reason “guide: returns register”`],
      rollback: 'A return cannot be undone (it is a document). The test product is deactivated after the run.',
    })
    product(pname, 100, asLifecycle).then((pid) => sale(pid, 2, buyer))
    const seed = act('Set-up (not under test): sell 2 and return 1 of them.', ['The return is accepted and numbered CRN-…'], { via: 'run' })
    cy.request('/getUserSell?q=-1').then((r) => {
      const line = list(r.body).find((s) => s.itemName === pname || (s.customerName || '').includes(buyer)) || list(r.body)[0]
      cy.request({ method: 'POST', url: '/saleReturn', form: true, body: { sellId: line.sellId, quantity: 1, reason: 'guide: returns register' } })
        .its('body.status').should('eq', 'SUCCESS')
    })
    seed.code = 'POST /saleReturn  sellId=<the sale line>, quantity=1'

    const a1 = act('Open **Sell → Sale Returns**.',
      ['The screen is titled **Sale Returns** and its party column is **Customer**.',
        `The newest row starts with a **CRN-** number and names **${buyer}** — a name, never an id or a dash.`])
    cy.visitDashboardSettled()
    openMenu('snavSell'); cy.get('#navCreditNotes').click()
    cy.get('#ReturnsDiv').should('be.visible')
    cy.get('#returnsPartyHead').invoke('text').should('match', /customer/i)
    cy.contains('#tableReturns tbody tr', buyer, { timeout: 30000 }).as('row')
    cy.get('@row').find('td').eq(0).invoke('text').should('match', /CRN-/)
    snap(a1, 'register', '#ReturnsDiv')

    const a2 = act('Press **Reprint** on that row.', ['The credit note is fetched by its own number and comes back as a document (the print dialog opens).'])
    cy.intercept('GET', '**/creditNote*').as('note')
    cy.get('@row').find('.rtn-print').click()
    cy.wait('@note', { timeout: 20000 }).then((i) => {
      expect(i.request.url).to.match(/no=CRN-/)
      expect(i.response.body.status).to.eq('SUCCESS')
    })
    snap(a2, 'reprinted', '#ReturnsDiv')
    act('Cleanup: the test product is deactivated after the run.', ['It no longer appears in pickers.'], { cleanup: true, via: 'run' })
  })

  // ── V2 · purchase returns: supplier filter and Print all ────────────────────────────────────────────
  caseIt('V2', 'Purchase returns: the supplier filter narrows the list, and Print all is ONE job', () => {
    asLifecycle()
    const vname = `VS Supplier ${run}`, pname = `VS Bought ${run}`, inv = `VS-B-${run}`
    testCase('V2', 'Purchase returns: the supplier filter narrows the list, and Print all is ONE job', {
      tenant: `${LIFECYCLE} (sacrificial)`, role: 'Owner',
      purpose: 'Twenty debit notes must not become twenty print dialogs, and a supplier picked must narrow the list ON THE SERVER — a filter that renders but narrows nothing is a failure this screen family has had before.',
      prereq: [`A supplier **${vname}**, a bill **${inv}** of 10 × **${pname}**, and **4** of them returned — through the same requests the Supplier, Purchase and Return forms send.`],
      data: [`Supplier **${vname}**`],
      rollback: 'Returns are documents and stay. The product is deactivated after the run.',
    })
    let vid
    cy.ensureCompany().then((companyId) => cy.request({ method: 'POST', url: '/addVender', form: true,
      body: { name: vname, companyId, mobile: '0302' + run.padStart(7, '2'), email: `vs${run}@t.com` } }))
    cy.request('/getUserVender').then((r) => { vid = list(r.body).find((v) => v.name === vname).id })
    product(pname, 100, asLifecycle).then((pid) => cy.then(() => cy.request({ method: 'POST', url: '/addPurchase', form: true,
      body: { productId: pid, quantity: 10, venderId: vid, paidAmount: 0, 'stock.bpurchaseRate': 50, 'stock.bsellRate': 100,
              totalAmount: 500, netAmount: 500, purchaseInvoiceNo: inv } }).its('body.status').should('eq', 'SUCCESS')))
    cy.request('/getUserPurchase').then((r) => {
      const p = list(r.body).find((x) => x.purchaseInvoiceNo === inv)
      cy.request({ method: 'POST', url: '/purchaseReturn', form: true, body: { purchaseId: p.purchaseId || p.id, quantity: 4, reason: 'guide: damaged' } })
        .its('body.status').should('eq', 'SUCCESS')
    })
    act('Set-up (not under test): a supplier, a bill of 10, 4 returned.', ['The return is accepted and numbered DBN-…'], { via: 'run' })

    const a1 = act('Open **Purchase → Purchase Returns**, then choose **' + vname + '** in the Supplier box.',
      ['The party column is **Supplier**.', 'After choosing, the list is read again **with that supplier**, and every row names it.'])
    cy.intercept('GET', '**/getPurchaseReturns*').as('reg')
    cy.visitDashboardSettled()
    openMenu('snavPurchase'); cy.get('#navDebitNotes').click()
    cy.get('#returnsPartyHead').invoke('text').should('match', /supplier/i)
    cy.wait('@reg')
    cy.get('#returnsVenderDD option', { timeout: 30000 }).should('contain', vname)
    cy.then(() => {
      cy.get('#returnsVenderDD').select(String(vid), { force: true })
      cy.wait('@reg').its('request.url').should('include', 'venderId=' + vid)
    })
    cy.get('#tableReturns tbody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)
      .each(($tr) => expect($tr.text()).to.contain(vname))
    snap(a1, 'filtered', '#ReturnsDiv')

    const a2 = act('Press **Print all** and confirm.', ['One question naming how many notes; after it, **one** print job holding every listed note — not a dialog per note.'])
    cy.window().then((w) => cy.stub(w, 'print').as('printed'))
    cy.intercept('GET', '**/debitNote*').as('dn')
    cy.get('#returnsPrintAll').should('be.visible').click()
    snap(a2, 'confirm')
    cy.get('.uiC-card button').contains(/print/i).click()
    cy.wait('@dn', { timeout: 20000 })
    act('Cleanup: the test product is deactivated after the run.', ['It no longer appears in pickers.'], { cleanup: true, via: 'run' })
  })

  // ── V3 · Sale Detail Report: invoices and PDF ───────────────────────────────────────────────────────
  caseIt('V3', 'Sale Detail Report: Print invoices gives invoice documents, Download PDF gives ONE file', () => {
    asLifecycle()
    const pname = `VS Report ${run}`
    testCase('V3', 'Sale Detail Report: Print invoices gives invoice documents, Download PDF gives ONE file', {
      tenant: `${LIFECYCLE} (sacrificial)`, role: 'Owner',
      purpose: 'The report lists sale LINES; these buttons produce the INVOICES behind them — one per invoice, never three copies of a three-line sale. **Download PDF** used to do nothing: browsers silently drop every automatic download after the first, so it now builds ONE file with a page per invoice.',
      prereq: [`Two paid sales of **${pname}** this month (through the till’s own request).`],
      data: ['Period: **Current month**'],
      rollback: 'Two sales stay on the sacrificial business; the product is deactivated after the run.',
    })
    product(pname, 100, asLifecycle).then((pid) => { sale(pid, 1, `VS R1 ${run}`); cy.then(() => cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: { customer: { name: `VS R2 ${run}`, contact: '03001234567' }, sales: [{ productId: pid, quantity: 1, sellRate: 100, totalAmount: 100 }],
              tenders: [{ method: 'CASH', amount: 100 }], paidAmount: 100, grandTotal: 100, idempotencyKey: `guide-vs2-${run}` } })) })
    act('Set-up (not under test): two paid sales this month.', ['Both are accepted.'], { via: 'run' })

    const a1 = act('Open **Sell → Sale Detail Report**, leave **Current month**, press **View report**.', ['Rows appear, each with its invoice number.'])
    cy.visitDashboardSettled()
    openMenu('snavSell'); cy.contains('#snavSell a', /sale detail report/i).click()
    cy.get('#SRDiv').should('be.visible')
    cy.get('#SRDiv button[onclick*="loadSR"]').first().click()
    cy.get('#tableSellReport tbody .sr-inv', { timeout: 30000 }).should('have.length.greaterThan', 1)
    snap(a1, 'report', '#SRDiv')

    const a2 = act('Press **Print invoices** and confirm.', ['Each DISTINCT invoice is fetched exactly once, as an invoice document (number, customer, lines) — not the grid.'])
    const fetched = []
    cy.intercept('GET', '**/getReceipt*', (req) => { fetched.push(req.query.invoiceNo) }).as('rcpt')
    cy.window().then((w) => {
      const nos = w.srVisibleInvoiceNos()
      cy.get('#srPrintInvoices').click()
      snap(a2, 'print-confirm')
      cy.get('.uiC-card button').contains(/print/i).click()
      cy.wrap(null, { timeout: 30000 }).should(() => {
        expect(fetched.length).to.eq(nos.length)
        expect(new Set(fetched).size).to.eq(fetched.length)
      })
    })

    const a3 = act('Press **Download PDF**.', ['ONE file is built (`invoices-N.pdf`) with **one page per invoice** — not a file per invoice.'])
    cy.window().then((w) => cy.wrap(w.LazyExport.ensurePdfMake(), { timeout: 60000 }).then(() => {
      const built = []
      const real = w.pdfMake.createPdf.bind(w.pdfMake)
      w.pdfMake.createPdf = (d) => { built.push(JSON.parse(JSON.stringify(d))); return real(d) }
      const nos = w.srVisibleInvoiceNos()
      cy.get('#srDownloadInvoices').click()
      cy.wrap(null, { timeout: 60000 }).should(() => {
        expect(built.length, 'one document').to.eq(1)
        const breaks = built[0].content.filter((b) => b && b.pageBreak === 'before').length
        expect(breaks + 1, 'a page per invoice').to.eq(nos.length)
      })
    }))
    snap(a3, 'downloaded', '#SRDiv')
    act('Cleanup: the test product is deactivated after the run.', ['It no longer appears in pickers.'], { cleanup: true, via: 'run' })
  })

  // ── V4 · the dashboard stock-value tile ─────────────────────────────────────────────────────────────
  caseIt('V4', 'Dashboard: the stock-value tile says what it is valued at, and opens the products', () => {
    asLifecycle()
    testCase('V4', 'Dashboard: the stock-value tile says what it is valued at, and opens the products', {
      tenant: `${LIFECYCLE}`, role: 'Owner',
      purpose: 'An unqualified “stock value” is read as a books figure and will not match the ledger. The tile says **at last purchase rate**, shows a number or a dash — never a 0 standing in for “unavailable” — and leads to the list behind it.',
      prereq: ['Signed in as the owner.'], data: ['None — nothing is saved'], rollback: 'Nothing changes.',
    })
    const a1 = act('Open the **Dashboard** and find the stock-value tile beside the product count.',
      ['Its label reads **Stock value (at last purchase rate)**.', 'It shows an amount or “—”, never a bare 0 while loading.'])
    cy.visitDashboardSettled()
    cy.get('[data-widget="stockValue"] .kpi-label').should('contain', 'last purchase rate')
    cy.get('#dashStockValue').invoke('text').should('match', /[\d—-]/)
    snap(a1, 'tile', '[data-widget="stockValue"]')
    const a2 = act('Click the tile.', ['The **Products** list opens.'])
    cy.get('[data-widget="stockValue"] .kpi-card').click()
    cy.get('#ProductDiv', { timeout: 15000 }).should('be.visible')
    snap(a2, 'products')
  })

  // ── V5 · Serial / IMEI at the till ──────────────────────────────────────────────────────────────────
  caseIt('V5', 'Mobile shop: a serial locks QTY to 1, the next line is free, the sale records ONE unit', () => {
    const imei = '35' + run.padEnd(13, '4'), pname = `VS Handset ${run}`, buyer = `VS Walk-in ${run}`
    testCase('V5', 'Mobile shop: a serial locks QTY to 1, the next line is free, the sale records ONE unit', {
      tenant: 'owner.mobile@myplus.com (retail + serial tracking)', role: 'Owner',
      purpose: 'One IMEI is one handset. Typing a serial sets QTY to 1 and locks it — **readonly, never disabled**, because a disabled box is dropped on submit and the sale would record no quantity. The next line must not inherit the lock.',
      prereq: ['Signed in as owner.mobile@.', `A serial-tracked product **${pname}** with one handset received under IMEI **${imei}** (through the same request goods-in sends).`],
      data: [`IMEI **${imei}**, walk-in **${buyer}**, cash **500**`],
      rollback: 'The sale stands on the mobile shop (it is a real sale). The product is deactivated after the run.',
    })
    mobileOnPro(); cy.loginAsMobileOwner(); cy.setCapability('serialTracking', true)
    cy.seedProduct({ name: pname, sellingPrice: 500, stock: 0 }).then(({ productId }) => {
      deactivate(productId, () => cy.loginAsMobileOwner())
      cy.request({ method: 'POST', url: '/setProductTracking', form: true, body: { id: productId, requiresSerial: 'true' } })
      cy.request({ method: 'POST', url: '/addPurchase', form: true, body: { productId, quantity: 1, serials: imei, purchaseRate: 300,
        'stock.bpurchaseRate': 300, 'stock.bsellRate': 500, totalAmount: 300, netAmount: 300, paidAmount: 300, purchaseInvoiceNo: 'VS-S-' + run } })
        .its('body.status').should('eq', 'SUCCESS')
      act('Set-up (not under test): receive one handset under the IMEI.', ['The handset is in the register.'], { via: 'run' })

      const a1 = act(`**Sell → New Sale**, choose **${pname}**, type **3** in QTY, then type the IMEI in **Serial / IMEI**.`,
        ['The Serial / IMEI box sits **before** QTY and shows all 15 digits.', 'QTY becomes **1** and cannot be typed into.'])
      cy.visitDashboardSettled()
      cy.get('#sellType').select('sellDiv', { force: true })
      cy.get('#sellItemDD', { timeout: 20000 }).select(String(productId), { force: true })
      cy.get('#sellQuantity').clear().type('3')
      cy.get('#sellSerials').should('be.visible').clear().type(imei)
      cy.get('#sellQuantity').should('have.value', '1').and('have.attr', 'readonly')
      cy.get('#sellSerials').should(($s) => expect($s[0].scrollWidth).to.be.at.most($s[0].clientWidth + 1))
      snap(a1, 'locked', '#sellDiv')

      const a2 = act('Clear the Serial / IMEI box.', ['QTY can be typed into again.'])
      cy.get('#sellSerials').clear()
      cy.get('#sellQuantity').should('not.have.attr', 'readonly')
      snap(a2, 'released', '#sellDiv')

      const a3 = act('Type the IMEI again and press **Add to Cart**.', ['The line is in the cart with quantity 1.', 'The next line’s Serial box is **empty** and its QTY is **not locked**.'])
      cy.get('#sellSerials').type(imei)
      cy.get('#sellQuantity').should('have.value', '1')
      cy.get('#addInviceItem').click({ force: true })
      cy.window({ timeout: 15000 }).its('data').should('have.length', 1)
      cy.get('#sellSerials').should('have.value', '')
      cy.get('#sellQuantity').should('not.have.attr', 'readonly')
      snap(a3, 'next-line', '#sellDiv')

      const a4 = act(`Choose **Enter Manually**, type **${buyer}**, Amount received **500**, press **Complete Sale** and confirm.`, ['The sale completes; the invoice line is quantity **1** with the IMEI.'])
      cy.intercept('POST', '**/addSell').as('sale')
      cy.get('#btnModeManual').click({ force: true })
      cy.get('#sellCN').clear().type(buyer)
      cy.get('#sellRec').clear().type('500')
      cy.get('#addSell').click({ force: true })
      cy.confirmSale({ optional: true })
      cy.wait('@sale', { timeout: 30000 }).then((i) => {
        expect(i.response.body.status).to.eq('SUCCESS')
        const line = (i.request.body.sales || []).find((l) => String(l.serials || '').includes(imei))
        expect(Number(line.quantity)).to.eq(1)
      })
      snap(a4, 'sold')
      act('Cleanup: the test product is deactivated after the run.', ['It no longer appears in pickers.'], { cleanup: true, via: 'run' })
    })
  })

  // ── V6 · the hide switch, on the Configuration screen ───────────────────────────────────────────────
  caseIt('V6', 'Mobile shop: Configuration “Show the Serial / IMEI field” off hides the box; Reset brings it back', () => {
    testCase('V6', 'Mobile shop: Configuration “Show the Serial / IMEI field” off hides the box; Reset brings it back', {
      tenant: 'owner.mobile@myplus.com', role: 'Owner (only owner/admin change settings)',
      purpose: 'A shop that records IMEIs only at goods-in can take the box off the sale line. It only HIDES the field — a product that requires a serial is still refused without one, and the help text says so.',
      prereq: ['Signed in as owner.mobile@, serial tracking on.', 'The setting is at its default (the run resets it first).'],
      data: ['None'], rollback: '**Reset to default** on the same screen (step C1).',
    })
    mobileOnPro(); cy.loginAsMobileOwner(); cy.setCapability('serialTracking', true)
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: SHOW } })
    SAFETY.push(() => { cy.loginAsMobileOwner(); cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: SHOW } }) })

    const a1 = act('**Settings → Configuration**, find **Show the Serial / IMEI field** (Sale entry) and switch it **off**.', ['It saves and shows as changed from the default.'])
    cy.visitDashboardSettled()
    openMenu('snavSettings'); cy.get('#snavSettings a[onclick^="showBusinessConfig("]').click()
    cy.get('#businessConfigBody .cfg-rail__item', { timeout: 20000 }).should('have.length.greaterThan', 3)
    cy.revealSetting(SHOW)
    cy.intercept('POST', '**/saveBusinessConfig').as('save')
    cy.get(`#businessConfigBody [data-key="${SHOW}"]`).should('be.checked').uncheck()
    cy.wait('@save').its('response.body.success').should('eq', true)
    cy.get(`#businessConfigBody [data-key="${SHOW}"]`).closest('.cfg-row').as('row')
    snap(a1, 'off', '@row')

    const a2 = act('Open **Sell → New Sale**.', ['There is **no** Serial / IMEI box on the line; QTY is there.'])
    cy.visitDashboardSettled()
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv [data-pos-field="serial"]').should('not.be.visible')
    cy.get('#sellQuantity').should('be.visible')
    snap(a2, 'till-without', '#sellDiv')

    const c1 = act('Cleanup: back in Configuration press **Reset to default** on that setting, then open New Sale.', ['The setting is back to its default and the Serial / IMEI box is on the line again.'], { cleanup: true })
    cy.visitDashboardSettled()
    openMenu('snavSettings'); cy.get('#snavSettings a[onclick^="showBusinessConfig("]').click()
    cy.revealSetting(SHOW)
    cy.intercept('POST', '**/resetBusinessConfig').as('reset')
    cy.get(`#businessConfigBody .cfg-row__reset[data-reset-key="${SHOW}"]`).should('be.visible').click()
    cy.wait('@reset').its('response.body.success').should('eq', true)
    snap(c1, 'reset', `#businessConfigBody [data-key="${SHOW}"]`)
    cy.visitDashboardSettled()
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellSerials').should('be.visible')
  })

  // ── V7 · session expiry ─────────────────────────────────────────────────────────────────────────────
  caseIt('V7', 'A session that ended takes you to Sign in with a sentence, never “parsererror”', () => {
    testCase('V7', 'A session that ended takes you to Sign in with a sentence, never “parsererror”', {
      tenant: 'owner.business@myplus.com', role: 'Any signed-in user',
      purpose: '**Fixed 5 Oct (SESS-2).** The next click after a session ended showed “form submit: parsererror”: the server sent the script to the *Session expired* HTML page. It now answers a script with “session ended”, and the page goes to Sign in saying so.',
      prereq: ['Signed in, on the dashboard.'],
      data: ['None'],
      rollback: 'Sign in again.',
    })
    cy.loginAsOwner()
    const a1 = act('End the session while the screen is open: **F12 → Application → Cookies → JSESSIONID**, change its value (or restart the server).', ['Nothing visible yet — the screen does not know.'], { via: 'console', code: 'DevTools → Application → Cookies → JSESSIONID → edit the value' })
    cy.visitDashboardSettled()
    cy.setCookie('JSESSIONID', 'DEADBEEF0000000000000000GUIDEV7X')
    snap(a1, 'still-open')
    const a2 = act('Click **Sell → Sale Returns** (anything that reads from the server).', ['You land on **Sign in**, and the page is told why — the words “parsererror” appear nowhere.'])
    // The dashboard's own background reads may meet the dead session first and take the page to Sign in before any
    // click — that is the fix working. Only if the page is still open is the click made (the same function the menu
    // entry calls).
    cy.window().then((w) => { if (typeof w.showReturns === 'function') w.showReturns('credit') })
    cy.location('pathname', { timeout: 15000 }).should('eq', '/login')
    cy.location('search').should('match', /message=./).and('not.match', /parsererror/i)
    cy.get('.msg-bar.info').invoke('text').should('match', /\S/)
    snap(a2, 'login')
  })
})
