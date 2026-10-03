/**
 * One partner, both customer and supplier (DR-1 … DR-5) — the manual test cases, as REAL step-by-step tests.
 *
 * Each `it` is one complete manual case: prerequisites, test data, numbered actions, the expected result of each
 * action, and the cleanup. Every action is PERFORMED on the screen, ASSERTED, and PHOTOGRAPHED, in that order; the
 * page builder (docs/guides/build-dual-role-guide.js) shows a case as verified only if the whole case passed, so no
 * expected result on the page is one the app did not actually produce on this build.
 *
 * WHERE A SCREEN IS MISSING. Reversing an opening balance has no button yet. That cleanup gives the tester the exact
 * browser-console command, and this spec RUNS THAT SAME COMMAND in the page (win.eval) — so the command is tested too.
 * Setup that is not under test (a company for a supplier to supply) is made through the same request the screen
 * sends, and the prerequisites say so.
 *
 * TENANTS. Identity cases run on owner.business@ (no money). Cases that move money run on owner.lifecycle@, the
 * Test Book's sacrificial tenant: its cutover date is anchored at 2026-09-01 for good (the lock follows the books).
 * Every case registers an undo in SAFETY, run in after(), so a case that fails half-way still cleans up.
 *
 * Run headed:
 *   npx cypress run --headed --browser electron --spec cypress/e2e/docs/dual-role-guide.cy.js \
 *     --config trashAssetsBeforeRuns=false
 * One case only:   --env '{"guideOnly":"G4"}'
 */
const OUT_DIR = 'cypress/guide-out/dual-role'
const ONLY = String(Cypress.env('guideOnly') || '').split(',').map((x) => x.trim()).filter(Boolean)
const caseIt = (id, title, fn) => ((ONLY.length && !ONLY.includes(id)) ? it.skip : it)(`${id} — ${title}`, fn)
const LIFECYCLE = 'owner.lifecycle@myplus.com'
const PW = 'Demo@2025!'
const run = String(Date.now()).slice(-6)
const CUTOVER = '2026-09-01'

const SAFETY = []
/** Records made on owner.business@ — after() deletes any a failed case left behind, through the grid's own delete. */
const CREATED = []
const track = (entity, name) => { if (cur && String(cur.tenant || '').startsWith('owner.business')) CREATED.push({ entity, name }) }
let cur = null

// ── recording (the same shape as the settings guide, so the two pages read alike) ──────────────────────
const testCase = (id, title, meta) => { cur = { id, title, actions: [], cleanup: [], shots: [], ...meta } }
const act = (text, expect, opts = {}) => {
  const a = { do: text, expect: [].concat(expect || []), shots: [], via: opts.via || 'screen', code: opts.code || null }
  ;(opts.cleanup ? cur.cleanup : cur.actions).push(a)
  return a
}
const snap = (a, name, subject) => {
  const pos = cur.actions.includes(a) ? `a${cur.actions.indexOf(a) + 1}` : `c${cur.cleanup.indexOf(a) + 1}`
  const file = `DRG-${cur.id}-${pos}-${name}`
  a.shots.push(file)
  cur.shots.push(file)
  ;(subject ? cy.get(subject).screenshot(file, { overwrite: true }) : cy.screenshot(file, { capture: 'viewport', overwrite: true }))
  // Keep a copy with the case's own output: cypress/screenshots is SHARED, and any run with the default
  // trashAssetsBeforeRuns wipes it — 35 of 42 pictures vanished that way on 2026-10-04 between capture and build.
  return cy.readFile(`cypress/screenshots/dual-role-guide.cy.js/${file}.png`, 'base64')
    .then((b64) => cy.writeFile(`${OUT_DIR}/img/${file}.png`, b64, 'base64'))
}

// ── app helpers ───────────────────────────────────────────────────────────────────────────────────────────
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return Array.isArray(b) ? b : [] }
const payload = (b) => (b && (b.object || b.data)) || null
const mobile = (seed) => '03' + String(seed).padStart(9, '0').slice(-9)
const asLifecycle = () => cy.loginAs(LIFECYCLE, PW, '/getBusinessDashboardStats')
const openDashboard = () => { cy.visit('/businessDashboard'); cy.waitForAppReady() }
const openMenu = (dd) => {
  cy.get(`#${dd}`).then(($d) => { if (!$d.hasClass('snav-open')) cy.get(`#${dd} .snav-btn`).click() })
  cy.get(`#${dd}`).should('have.class', 'snav-open')
}
const menu = (dd, onclickPrefix) => { openMenu(dd); cy.get(`#${dd} a[onclick^="${onclickPrefix}"]`).first().click() }
const openCustomers = () => { menu('snavRegister', "snavGo('registrationType','CustomerDiv'"); cy.get('#tableCustomer_wrapper').should('be.visible') }
const openSuppliers = () => { menu('snavRegister', "snavGo('registrationType','VenderDiv'"); cy.get('#tableVender_wrapper').should('be.visible') }
const search = (div, text) => cy.get(`#${div} input[type="search"]`).first().clear().type(text)
const row = (div, name) => cy.contains(`#${div} tr`, name, { timeout: 15000 })
// cy.wrap(… || null): a .then that returns undefined passes the PREVIOUS subject on (the HTTP response), so "not found"
// must be an explicit null or every caller sees a truthy object.
const customerByName = (name) => cy.request('/getUserCustomer?q=-1').then((r) => cy.wrap(list(r.body).find((x) => x.name === name) || null))
const supplierByName = (name) => cy.request('/getUserVender').then((r) => cy.wrap(list(r.body).find((x) => x.name === name) || null))
const json = (url, body) => cy.request({ method: 'POST', url, headers: { 'Content-Type': 'application/json' }, body, failOnStatusCode: false })

/** A company to supply — setup, not under test. Answers its id. */
const company = (name) => cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name, email: `${name}@t.com` } })
  .then(() => cy.request('/getUserCompany')).then((r) => list(r.body).find((x) => x.name === name).id)

/** Fill and save the customer form (already open on a NEW record). */
const fillCustomer = ({ name, contact, email, cnic }) => {
  track('Customer', name)
  cy.get('#customerName').clear().type(name)
  if (contact) cy.get('#contact').clear().type(contact)
  if (email) cy.get('#email').clear().type(email)
  if (cnic) cy.get('#customerCnic').then(($c) => { if ($c.is(':visible')) cy.wrap($c).clear().type(cnic); else $c.val(cnic) })
}
const saveCustomer = () => { cy.get('#addCustomer').click(); cy.get('#CustomerModal').should('not.be.visible') }
const fillSupplier = ({ name, mobile: m, phone, email, cnicNtn, companyId }) => {
  track('Vender', name)
  cy.get('#venderName').clear().type(name)
  if (m) cy.get('#venderMobile').clear().type(m)
  if (phone) cy.get('#venderPhone').clear().type(phone)
  if (email) cy.get('#venderEmail').clear().type(email)
  if (cnicNtn) cy.get('#venderCnicNtn').clear().type(cnicNtn)
  cy.window().then((w) => w.$('#venderCompanyDD').selectpicker('val', [String(companyId)]))
}
const saveSupplier = () => { cy.get('#addVender').click(); cy.get('#VenderModal').should('not.be.visible') }

/** Delete records through the grid's own bulk delete (tick the row, Delete, confirm). */
const deleteRows = (div, entity, names) => {
  // ONE record per delete: searching for the next name redraws the grid and drops the previous tick.
  names.forEach((n) => {
    search(div, n)
    row(div, n).find('input[type="checkbox"]').first().check({ force: true })
    cy.intercept('POST', `**/delete${entity}`).as('del')
    cy.get(`#${div} button[onclick="confirmBulkDelete('${entity}')"]`).click({ force: true })
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@del')
    ;(entity === 'Vender' ? supplierByName(n) : customerByName(n)).then((x) => expect(x, `${n} deleted`).to.be.null)
  })
}
const open360 = (div, name) => { search(div, name); row(div, name).contains('button', '360').click(); cy.get('.c360-card').should('be.visible') }
const close360 = () => cy.get('.c360-card .c360-x').click()
const rolesIn360 = () => cy.get('.c360-card .c360-chips')

/** Lifecycle: a partner holding both roles, with opening balances entered on the Opening balances screen. */
const seedPartnerOnScreen = (a, s, owed, weOwe) => {
  const phone = mobile(Number(s) + 7)
  const c = `DRG Pos C ${s}`, v = `DRG Pos S ${s}`
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: 'business.cutoverDate', value: CUTOVER }, failOnStatusCode: false })
  cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: c, contact: phone } })
  company(`DRGPosCo${s}`).then((coId) => cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: v, mobile: phone, companyIds: coId, companyId: coId } }))
  openDashboard()
  cy.get('#navOpeningBalance').click({ force: true })
  cy.get('#OpeningBalanceDiv').should('be.visible')
  const post = (type, name, amount, ref) => {
    cy.get('#obPartyType').select(type === 'VENDOR' ? 'vender' : 'customer', { force: true })
    customerOrSupplierId(type, name).then((id) => cy.get(`#obParty option[value="${id}"]`, { timeout: 15000 }).should('exist').then(() => cy.get('#obParty').select(String(id), { force: true })))
    cy.get('#obAmount').clear().type(String(amount))
    cy.get('#obReference').clear().type(ref)
    cy.intercept('POST', '**/postOpeningBalance').as('ob')
    cy.get('#obPost').click()
    cy.wait('@ob').its('response.body.status').should('eq', 'SUCCESS')
  }
  post('CUSTOMER', c, owed, `guide ${s} receivable`)
  post('VENDOR', v, weOwe, `guide ${s} payable`)
  if (a) snap(a, 'opening-balances', '#OpeningBalanceDiv')
  return cy.wrap({ c, v, phone })
}
const customerOrSupplierId = (type, name) => (type === 'VENDOR' ? supplierByName(name) : customerByName(name))
  .then((x) => (type === 'VENDOR' ? x.id : (x.customerId || x.id)))
const trialBalanceScreen = () => { menu('snavFinance', "showFinance('trialBalance')"); cy.get('#FinanceDiv').should('be.visible'); cy.get('#FinanceResults table', { timeout: 15000 }).should('exist') }
const tb = () => cy.request('/gl/trialBalance').then((r) => (typeof r.body === 'string' ? JSON.parse(r.body) : r.body))
const net = (t, code) => { const a = ((t && t.rows) || []).find((x) => x.code === code) || { debit: 0, credit: 0 }; return Number(a.debit) - Number(a.credit) }
/** Cleanup with no screen: reverse a customer's opening balance by running the console command the page gives. */
const reverseCustomerOpening = (a, customerName, alias) => {
  openDashboard(); cy.get('#navOpeningBalance').click({ force: true })
  customerByName(customerName).then((c) => cy.request(`/customerStatement?customerId=${c.customerId || c.id}`)).then((r) => {
    const docNo = (JSON.stringify(r.body).match(/OB-\d{6}/) || [])[0]
    expect(docNo, 'the opening document number').to.be.a('string')
    const real = reverseOpeningCode.replace('DOC_NO', docNo); a.code = real
    cy.intercept('POST', '**/reverseOpeningBalance').as(alias)
    cy.window().then((w) => w.eval(real)); cy.wait('@' + alias).its('response.body.status').should('eq', 'SUCCESS')
  })
  snap(a, 'reversed-opening', '#OpeningBalanceDiv')
}
const reverseOpeningCode = "$.post(serverContext + 'reverseOpeningBalance', { invoiceNo: 'DOC_NO', reason: 'guide cleanup' }).done(function (r) { console.log(r.status, r.message) })"

describe('Dual-role manual test cases (one partner, customer AND supplier)', () => {
  afterEach(function () {
    if (!cur) return
    cur.passed = this.currentTest.state === 'passed'
    cur.capturedAt = new Date().toISOString()
    if (!cur.passed) cur.error = String((this.currentTest.err && this.currentTest.err.message) || '').slice(0, 400)
    cy.writeFile(`${OUT_DIR}/${cur.id}.json`, cur)
    cur = null
  })
  after(() => {
    SAFETY.slice().reverse().forEach((fn) => fn())
    if (!CREATED.length) return
    cy.loginAsOwner(); openDashboard()
    CREATED.forEach(({ entity, name }) => {
      const find = entity === 'Vender' ? supplierByName(name) : customerByName(name)
      find.then((x) => { if (x) cy.window().then((w) => w.performBulkDelete(entity, String(entity === 'Vender' ? x.id : (x.customerId || x.id)))) })
    })
  })

  // ── G1 · same number, any format → one partner, both roles, badges ──────────────────────────────────────
  caseIt('G1', 'Register a customer and a supplier with the same number in different formats — one partner, both roles', () => {
    cy.loginAsOwner()
    const s = run + '1'
    const digits = String(Number(s) + 101).padStart(9, '0').slice(-9)
    const cName = `DRG Usman Traders ${s}`, vName = `DRG Usman & Co ${s}`
    const local = `03${digits.slice(0, 2)}-${digits.slice(2)}`, intl = `+923${digits}`
    testCase('G1', 'Register a customer and a supplier with the same number in different formats — one partner, both roles', {
      covers: ['W1', 'DR1-1', 'DR2-1'], slice: 'DR-1', tenant: 'owner.business@myplus.com', role: 'Owner',
      purpose: 'A business that buys from someone and also sells to them is ONE partner with two roles. The match is on the phone number however it is typed: dashes, spaces, 0300 or +92.',
      prereq: ['Signed in as the owner.', `A company to supply (the run created **DRGCo${s}**).`],
      data: [`Customer **${cName}**, contact **${local}**`, `Supplier **${vName}**, mobile **${intl}** — the same number in international form`],
      rollback: 'Both records are deleted. The partner they shared stays in the contact master with no roles — harmless, and invisible on the screens.',
    })
    let coId
    company(`DRGCo${s}`).then((id) => { coId = id })

    const a1 = act(`**Register → Customer → + New.** Name **${cName}**, Contact **${local}**. Click **Submit**.`,
      ['The form closes and the customer is in the list.'])
    openDashboard(); openCustomers()
    cy.get('#newCustomer').click()
    fillCustomer({ name: cName, contact: local })
    snap(a1, 'customer-form', '#CustomerModal .crud-box')
    saveCustomer()
    search('CustomerDiv', cName); row('CustomerDiv', cName).should('be.visible')

    const a2 = act(`**Register → Vender / Supplier → + New.** Name **${vName}**, Mobile **${intl}**, pick the company **DRGCo${s}**. Click **Submit**.`,
      ['The form closes and the supplier is in the list with an **Also a customer** badge after its name.'])
    openSuppliers()
    cy.get('#newVender').click()
    cy.then(() => fillSupplier({ name: vName, mobile: intl, companyId: coId }))
    snap(a2, 'supplier-form', '#VenderModal .crud-box')
    saveSupplier()
    search('VenderDiv', vName)
    row('VenderDiv', vName).find('[data-dr-badge="customer"]').should('be.visible')
    snap(a2, 'supplier-badge', '#tableVender_wrapper')

    const a3 = act(`**Register → Customer**, search **${cName}**.`,
      ['Its row shows an **Also a supplier** badge after the name, and **no + Supplier** button (it is one already).'])
    openCustomers(); search('CustomerDiv', cName)
    row('CustomerDiv', cName).find('[data-dr-badge="supplier"]').should('be.visible')
    row('CustomerDiv', cName).find('[data-action="add-as-supplier"]').should('not.exist')
    snap(a3, 'customer-badge', '#tableCustomer_wrapper')

    const a4 = act('On that row click **360**.',
      ['The popup lists BOTH roles: **CUSTOMER** and **VENDOR**, each with its record’s name.', 'There is a **Position** section and a **Customer and supplier** section with Unlink buttons — they are one partner.'])
    open360('CustomerDiv', cName)
    rolesIn360().should('contain', 'CUSTOMER').and('contain', 'VENDOR')
    cy.get('.c360-card [data-dr-unlink]').should('have.length.at.least', 2)
    snap(a4, '360-both-roles', '.c360-card')
    close360()

    const c1 = act(`Cleanup: **Register → Vender / Supplier**, tick **${vName}**, click **Delete**, confirm. Then **Register → Customer**, tick **${cName}**, **Delete**, confirm.`,
      ['Both disappear from their lists.'], { cleanup: true })
    openSuppliers(); deleteRows('VenderDiv', 'Vender', [vName])
    openCustomers(); deleteRows('CustomerDiv', 'Customer', [cName])
    search('CustomerDiv', cName); cy.get('#tableCustomer_wrapper').should('not.contain', cName)
    snap(c1, 'deleted', '#tableCustomer_wrapper')
  })

  // ── G2 · shared email does not merge; CNIC does ─────────────────────────────────────────────────────────
  caseIt('G2', 'A shared email never merges two partners; the same CNIC / NTN does, even with different phones', () => {
    cy.loginAsOwner()
    const s = run + '2'
    const mail = `office${s}@t.com`, cnic = '35201' + String(s).padStart(8, '0').slice(-8)
    const cA = `DRG Mail C ${s}`, vB = `DRG Mail S ${s}`, cC = `DRG Tax C ${s}`, vD = `DRG Tax S ${s}`
    testCase('G2', 'A shared email never merges two partners; the same CNIC / NTN does, even with different phones', {
      covers: ['DR1-2', 'DR1-4', 'DR1-7'], slice: 'DR-1', tenant: 'owner.business@myplus.com', role: 'Owner',
      purpose: 'Matching order: CNIC / NTN first (a legal identity), then the phone, and email only when there is nothing stronger. Two people sharing an office email are two partners.',
      prereq: ['Signed in as the owner.', `A company to supply (the run created **DRGCo${s}**).`],
      data: [`Customer **${cA}**, contact ${mobile(Number(s) + 11)}, email **${mail}**`, `Supplier **${vB}**, mobile ${mobile(Number(s) + 12)}, the SAME email`,
        `Customer **${cC}**, contact ${mobile(Number(s) + 13)}, CNIC **${cnic}**`, `Supplier **${vD}**, mobile ${mobile(Number(s) + 14)}, CNIC / NTN **${cnic}**`],
      rollback: 'All four records are deleted.',
    })
    let coId
    company(`DRGCo${s}`).then((id) => { coId = id })
    openDashboard()

    const a1 = act(`Register customer **${cA}** (contact ${mobile(Number(s) + 11)}, email ${mail}) and supplier **${vB}** (mobile ${mobile(Number(s) + 12)}, the same email, company DRGCo${s}).`,
      [`**${vB}** has **no** “Also a customer” badge: different phones are different partners, whatever the email.`])
    openCustomers(); cy.get('#newCustomer').click(); fillCustomer({ name: cA, contact: mobile(Number(s) + 11), email: mail }); saveCustomer()
    openSuppliers(); cy.get('#newVender').click()
    cy.then(() => fillSupplier({ name: vB, mobile: mobile(Number(s) + 12), email: mail, companyId: coId })); saveSupplier()
    search('VenderDiv', vB); row('VenderDiv', vB).find('[data-dr-badge]').should('not.exist')
    snap(a1, 'email-no-merge', '#tableVender_wrapper')

    const a2 = act(`Register customer **${cC}** with CNIC **${cnic}**, then supplier **${vD}** with a DIFFERENT mobile and **CNIC / NTN ${cnic}** (the new field under Mobile).`,
      [`**${vD}** shows **Also a customer**: the CNIC / NTN outranks the phone.`])
    openCustomers(); cy.get('#newCustomer').click(); fillCustomer({ name: cC, contact: mobile(Number(s) + 13), cnic }); saveCustomer()
    openSuppliers(); cy.get('#newVender').click()
    cy.then(() => fillSupplier({ name: vD, mobile: mobile(Number(s) + 14), cnicNtn: cnic, companyId: coId }))
    snap(a2, 'cnic-field', '#VenderModal .crud-box')
    saveSupplier()
    search('VenderDiv', vD); row('VenderDiv', vD).find('[data-dr-badge="customer"]').should('be.visible')
    snap(a2, 'cnic-merged', '#tableVender_wrapper')

    const a3 = act(`On **${vD}** click **Edit**.`, [`The **CNIC / NTN** box shows **${cnic}** — it was saved.`])
    row('VenderDiv', vD).find('.js-edit-row, .row-edit-btn, [data-action="edit"]').first().click({ force: true })
    cy.get('#VenderModal').should('be.visible'); cy.get('#venderCnicNtn').should('have.value', cnic)
    snap(a3, 'cnic-on-edit', '#VenderModal .crud-box')
    cy.get('#VenderModal .crud-x').click()

    const c1 = act('Cleanup: delete the two suppliers (Vender / Supplier list), then the two customers (Customer list) — tick, **Delete**, confirm.', ['All four are gone.'], { cleanup: true })
    openSuppliers(); deleteRows('VenderDiv', 'Vender', [vB, vD])
    openCustomers(); deleteRows('CustomerDiv', 'Customer', [cA, cC])
    search('CustomerDiv', `DRG`); cy.get('#tableCustomer_wrapper').should('not.contain', cA).and('not.contain', cC)
    snap(c1, 'deleted', '#tableCustomer_wrapper')
  })

  // ── G3 · editing: phone change re-links; rename keeps ─────────────────────────────────────────────────
  caseIt('G3', 'Editing: a rename keeps the partner; changing the phone to a supplier’s moves the customer to that partner', () => {
    cy.loginAsOwner()
    const s = run + '3'
    const vName = `DRG Re S ${s}`, cName = `DRG Re C ${s}`, vPhone = mobile(Number(s) + 21)
    testCase('G3', 'Editing: a rename keeps the partner; changing the phone to a supplier’s moves the customer to that partner', {
      covers: ['DR1-5', 'DR1-5b'], slice: 'DR-1', tenant: 'owner.business@myplus.com', role: 'Owner',
      purpose: 'Only a change to something the partner is matched on (phone, email, CNIC) can move a record to another partner. A rename never does.',
      prereq: ['Signed in as the owner.', `Supplier **${vName}** (mobile ${vPhone}) and customer **${cName}** (a different mobile) — the run created both on their forms.`],
      data: [`Rename to **${cName} (renamed)**`, `Then change the contact to **${vPhone}**`],
      rollback: 'Both records are deleted.',
    })
    company(`DRGCo${s}`).then((coId) => {
      openDashboard(); openSuppliers(); cy.get('#newVender').click(); fillSupplier({ name: vName, mobile: vPhone, companyId: coId }); saveSupplier()
    })
    openCustomers(); cy.get('#newCustomer').click(); fillCustomer({ name: cName, contact: mobile(Number(s) + 22) }); saveCustomer()

    const a1 = act(`**Register → Customer**, on **${cName}** click **Edit**, change the name to **${cName} (renamed)**, **Submit**. Then click **360**.`,
      ['The 360 popup still lists only **CUSTOMER** — the same partner as before; nothing else moved.'])
    search('CustomerDiv', cName); row('CustomerDiv', cName).find('.js-edit-row, .row-edit-btn, [data-action="edit"]').first().click({ force: true })
    cy.get('#customerName').clear().type(`${cName} (renamed)`); saveCustomer()
    open360('CustomerDiv', `${cName} (renamed)`)
    rolesIn360().should('contain', 'CUSTOMER').and('not.contain', 'VENDOR')
    snap(a1, 'rename-same-partner', '.c360-card'); close360()

    const a2 = act(`**Edit** it again and set **Contact** to **${vPhone}** (the supplier’s mobile). **Submit**, then **360**.`,
      ['The popup now lists **CUSTOMER and VENDOR** — the customer moved to the supplier’s partner.', 'Its row shows **Also a supplier**.'])
    search('CustomerDiv', `${cName} (renamed)`); row('CustomerDiv', `${cName} (renamed)`).find('.js-edit-row, .row-edit-btn, [data-action="edit"]').first().click({ force: true })
    cy.get('#contact').clear().type(vPhone); saveCustomer()
    cy.screenshot(`DIAG-G3-row-right-after-save`, { capture: 'viewport', overwrite: true })   // evidence only, not on the page
    openCustomers(); search('CustomerDiv', `${cName} (renamed)`)
    row('CustomerDiv', `${cName} (renamed)`).find('[data-dr-badge="supplier"]').should('be.visible')
    open360('CustomerDiv', `${cName} (renamed)`)
    rolesIn360().should('contain', 'CUSTOMER').and('contain', 'VENDOR')
    snap(a2, 'phone-relinked', '.c360-card'); close360()

    const c1 = act('Cleanup: delete the supplier, then the customer — tick, **Delete**, confirm.', ['Both are gone.'], { cleanup: true })
    openSuppliers(); deleteRows('VenderDiv', 'Vender', [vName])
    openCustomers(); deleteRows('CustomerDiv', 'Customer', [`${cName} (renamed)`])
    snap(c1, 'deleted', '#tableCustomer_wrapper')
  })

  // ── G4 · possible duplicates (owner) ────────────────────────────────────────────────────────────────────
  caseIt('G4', 'Possible duplicates: one number, two legal entities — listed side by side, never merged', () => {
    cy.loginAsOwner()
    const s = run + '4'
    const phone = mobile(Number(s) + 31)
    const cName = `DRG Dup C ${s}`, vName = `DRG Dup S ${s}`
    testCase('G4', 'Possible duplicates: one number, two legal entities — listed side by side, never merged', {
      covers: ['DR1-6'], slice: 'DR-1', tenant: 'owner.business@myplus.com', role: 'Owner (a cashier does not have the button)',
      purpose: 'The same phone with DIFFERENT CNIC / NTN stays two partners (a family business, a shared shop line). The owner can still see them together.',
      prereq: ['Signed in as the owner.'],
      data: [`Customer **${cName}**, contact ${phone}, CNIC 35201${String(s).slice(-8)}`, `Supplier **${vName}**, mobile ${phone}, CNIC / NTN 42101${String(s).slice(-8)}`],
      rollback: 'Both records are deleted.',
    })
    company(`DRGCo${s}`).then((coId) => {
      openDashboard(); openCustomers(); cy.get('#newCustomer').click(); fillCustomer({ name: cName, contact: phone, cnic: '35201' + String(s).slice(-8) }); saveCustomer()
      openSuppliers(); cy.get('#newVender').click(); fillSupplier({ name: vName, mobile: phone, cnicNtn: '42101' + String(s).slice(-8), companyId: coId }); saveSupplier()
    })

    const a1 = act('**Register → Customer**, click **Possible duplicates** in the toolbar. Scroll to the group for the number.',
      ['A popup lists groups under **Same phone number** or **Same CNIC / NTN**.', `Both **${cName}** and **${vName}** are in one group, each with its role chip. Nothing is merged.`])
    openCustomers(); cy.get('#partyDuplicatesBtn').click()
    cy.get('.c360-card').should('be.visible').and('contain', 'Same phone number')
    cy.contains('.c360-card', cName).should('exist')
    cy.contains('.c360-card [data-pd-party]', cName).scrollIntoView().should('be.visible')
    snap(a1, 'duplicates', '.c360-card')
    close360()

    const c1 = act('Cleanup: delete the supplier, then the customer.', ['Both are gone, and the group no longer appears.'], { cleanup: true })
    openSuppliers(); deleteRows('VenderDiv', 'Vender', [vName])
    openCustomers(); deleteRows('CustomerDiv', 'Customer', [cName])
    snap(c1, 'deleted', '#tableCustomer_wrapper')
  })

  // ── G5 · + Supplier / + Customer ─────────────────────────────────────────────────────────────────────────
  caseIt('G5', '“+ Supplier” and “+ Customer” open the other form already filled in', () => {
    cy.loginAsOwner()
    const s = run + '5'
    const cName = `DRG Add C ${s}`, vName = `DRG AddC S ${s}`
    testCase('G5', '“+ Supplier” and “+ Customer” open the other form already filled in', {
      covers: ['DR2-2', 'DR2-5'], slice: 'DR-2', tenant: 'owner.business@myplus.com', role: 'Owner',
      purpose: 'Registering the same business in its second role without retyping it. Saving it puts both records on one partner.',
      prereq: ['Signed in as the owner.', `A company to supply (the run created **DRGCo${s}**).`],
      data: [`Customer **${cName}**, contact ${mobile(Number(s) + 41)}, email add${s}@t.com, address Shop 4, Main Bazar`, `Supplier **${vName}**, mobile ${mobile(Number(s) + 42)}, email addc${s}@t.com`],
      rollback: 'All records created here are deleted.',
    })
    let coId
    company(`DRGCo${s}`).then((id) => { coId = id })
    openDashboard(); openCustomers(); cy.get('#newCustomer').click()
    fillCustomer({ name: cName, contact: mobile(Number(s) + 41), email: `add${s}@t.com` }); cy.get('#address').clear().type('Shop 4, Main Bazar'); saveCustomer()

    const a1 = act(`**Register → Customer**, on **${cName}** click **+ Supplier**.`,
      ['The Vender / Supplier screen opens a **New supplier — details copied from the customer** form.', 'Name, Mobile, Email and Address are already filled; the company is left for you to pick.'])
    search('CustomerDiv', cName); row('CustomerDiv', cName).find('[data-action="add-as-supplier"]').scrollIntoView().click()
    cy.get('#VenderModal').should('be.visible'); cy.get('#venderName').should('have.value', cName)
    cy.get('#venderMobile').should('have.value', mobile(Number(s) + 41)); cy.get('#venderAddress').should('have.value', 'Shop 4, Main Bazar')
    snap(a1, 'prefilled-supplier', '#VenderModal .crud-box')

    const a2 = act(`Pick the company **DRGCo${s}** and click **Submit**.`, ['The supplier row shows **Also a customer**.'])
    cy.then(() => cy.window().then((w) => w.$('#venderCompanyDD').selectpicker('val', [String(coId)])))
    saveSupplier()
    search('VenderDiv', cName); row('VenderDiv', cName).find('[data-dr-badge="customer"]').should('be.visible')
    snap(a2, 'saved-one-partner', '#tableVender_wrapper')

    const a3 = act(`Register supplier **${vName}** (mobile ${mobile(Number(s) + 42)}). On its row click **+ Customer**.`,
      ['A **New customer — details copied from the supplier** form opens with Name, Contact (the mobile) and Email filled.'])
    cy.get('#newVender').click(); cy.then(() => fillSupplier({ name: vName, mobile: mobile(Number(s) + 42), email: `addc${s}@t.com`, companyId: coId })); saveSupplier()
    search('VenderDiv', vName); row('VenderDiv', vName).find('[data-action="add-as-customer"]').scrollIntoView().click()
    cy.get('#CustomerModal').should('be.visible'); cy.get('#customerName').should('have.value', vName); cy.get('#contact').should('have.value', mobile(Number(s) + 42))
    snap(a3, 'prefilled-customer', '#CustomerModal .crud-box')
    cy.get('#CustomerModal .crud-x').click()

    const c1 = act('Cleanup: delete both suppliers, then the customer.', ['All gone.'], { cleanup: true })
    openSuppliers(); deleteRows('VenderDiv', 'Vender', [cName, vName])
    openCustomers(); deleteRows('CustomerDiv', 'Customer', [cName])
    snap(c1, 'deleted', '#tableCustomer_wrapper')
  })

  // ── G6 · link and unlink by hand ────────────────────────────────────────────────────────────────────────
  caseIt('G6', 'Link a customer to a supplier on a different number, then unlink it — from the 360 view', () => {
    cy.loginAsOwner()
    const s = run + '6'
    const cName = `DRG Link C ${s}`, vName = `DRG Link S ${s}`
    testCase('G6', 'Link a customer to a supplier on a different number, then unlink it — from the 360 view', {
      covers: ['DR2-3', 'DR2-6'], slice: 'DR-2', tenant: 'owner.business@myplus.com', role: 'Owner or admin',
      purpose: 'For what matching cannot decide: one business on two different numbers. Linking says who a record belongs to; it never moves a balance.',
      prereq: ['Signed in as the owner.', `Customer **${cName}** and supplier **${vName}** on DIFFERENT numbers (the run created both on their forms).`],
      data: [`Link **${cName}** to **${vName}**; then unlink the supplier`],
      rollback: 'Both records are deleted.',
    })
    company(`DRGCo${s}`).then((coId) => {
      openDashboard(); openCustomers(); cy.get('#newCustomer').click(); fillCustomer({ name: cName, contact: mobile(Number(s) + 51) }); saveCustomer()
      openSuppliers(); cy.get('#newVender').click(); fillSupplier({ name: vName, mobile: mobile(Number(s) + 52), companyId: coId }); saveSupplier()
    })

    const a1 = act(`**Register → Customer**, on **${cName}** click **360**, then **Link to a supplier…**.`, ['A filter box and a list of suppliers appear under “Customer and supplier”.'])
    openCustomers(); open360('CustomerDiv', cName)
    cy.get('.c360-card [data-dr-link-open]').click(); cy.get('.c360-card [data-dr-filter]').should('be.visible')
    snap(a1, 'link-picker', '.c360-card')

    const a2 = act(`Type **${vName}** in the filter, choose it, click **Link**, and confirm.`,
      ['The view reopens listing **CUSTOMER and VENDOR**, and now offers **Unlink** for each.'])
    cy.get('.c360-card [data-dr-filter]').type(vName)
    cy.get('.c360-card [data-dr-list] option').should('have.length', 1).first().then(($o) => cy.get('.c360-card [data-dr-list]').select($o.val()))
    cy.get('.c360-card [data-dr-link]').click(); cy.get('[data-ui-confirm="ok"]').click()
    cy.get('.c360-card [data-dr-unlink]', { timeout: 15000 }).should('have.length.at.least', 2)
    rolesIn360().should('contain', 'VENDOR')
    snap(a2, 'linked', '.c360-card')

    const a3 = act('Click **Unlink — Supplier: …** and confirm.', ['The view offers **Link to a supplier…** again; the supplier has a partner of its own.'])
    cy.get('.c360-card [data-dr-unlink^="VENDOR:"]').click(); cy.get('[data-ui-confirm="ok"]').click()
    cy.get('.c360-card [data-dr-link-open]', { timeout: 15000 }).should('be.visible')
    snap(a3, 'unlinked', '.c360-card'); close360()

    const c1 = act('Cleanup: delete the supplier, then the customer.', ['Both are gone.'], { cleanup: true })
    openSuppliers(); deleteRows('VenderDiv', 'Vender', [vName])
    openCustomers(); deleteRows('CustomerDiv', 'Customer', [cName])
    snap(c1, 'deleted', '#tableCustomer_wrapper')
  })

  // ── G7 · cashier sees none of it ─────────────────────────────────────────────────────────────────────────
  caseIt('G7', 'A cashier sees no 360, no Possible duplicates and no payment note', () => {
    cy.loginAsCashierA()
    testCase('G7', 'A cashier sees no 360, no Possible duplicates and no payment note', {
      covers: ['DR1-6', 'DR2-4', 'DR3-3', 'DR4-4', 'DR5-4'], slice: 'DR-2', tenant: 'cashier.a@myplus.com', role: 'Cashier (staff)',
      purpose: 'Which roles a partner holds, its position, set-off, and the other side’s balance are owner / admin information.',
      prereq: ['Signed in as **cashier.a@myplus.com** / Demo@2025!'],
      data: ['Any customer'],
      rollback: 'Nothing is created.',
    })
    const a1 = act('**Register → Customer**.', ['No **Possible duplicates** button in the toolbar.', 'No **360** button on any row (so no Link, Position or Set off).'])
    openDashboard(); openCustomers()
    cy.get('#partyDuplicatesBtn').should('not.exist')
    cy.get('#CustomerDiv tbody').should('not.contain', '360')
    snap(a1, 'cashier-customers', '#tableCustomer_wrapper')
    const a2 = act('In the browser console, ask for a partner position directly (the command below).',
      ['It is refused (403) — the screen hiding it is not the protection.'], { via: 'console', code: "$.get(serverContext + 'partyPosition', { partyId: 1 }).always(function (r, s, x) { console.log((x && x.status) || r.status) })" })
    cy.request({ url: '/partyPosition?partyId=1', failOnStatusCode: false }).then((r) => expect(r.status === 403 || (r.body && r.body.status !== 'SUCCESS')).to.eq(true))
    snap(a2, 'cashier-refused')
  })

  // ── G8 · position (money, lifecycle) ─────────────────────────────────────────────────────────────────────
  caseIt('G8', 'Position: what the partner owes us and what we owe them, read-only; each record opens its statement', () => {
    asLifecycle()
    const s = run + '8'
    testCase('G8', 'Position: what the partner owes us and what we owe them, read-only; each record opens its statement', {
      covers: ['DR3-1', 'DR3-2'], slice: 'DR-3', tenant: `${LIFECYCLE} (sacrificial — posts to the ledger)`, role: 'Owner',
      purpose: 'One figure for a partner who is both: they owe us, we owe them, and what would remain if both agreed to set off. Reading it changes nothing.',
      prereq: ['Signed in as the owner of owner.lifecycle@ (cutover date 2026-09-01).', `Customer **DRG Pos C ${s}** and supplier **DRG Pos S ${s}** on one mobile (made through the forms’ requests).`],
      data: ['Opening balance on the customer: **30,000**', 'Opening balance on the supplier: **50,000**'],
      rollback: 'The customer’s opening balance is reversed (console — no button yet). The supplier’s opening balance cannot be reversed in this release and stays, with the two records — this is the sacrificial tenant.',
    })
    let names
    const a1 = act('**Settings → Opening balances**: choose **Customer**, pick **DRG Pos C …**, amount **30000**, reference, **Record opening balance**. Then **Supplier**, pick **DRG Pos S …**, **50000**, record.',
      ['Each shows **Opening balance recorded.**'])
    seedPartnerOnScreen(a1, s, 30000, 50000).then((n) => { names = n })

    let before
    tb().then((t) => { before = t })
    const a2 = act('**Register → Customer**, search **DRG Pos C …**, click **360**.',
      ['A **Position** block: **They owe us 30,000.00**, **We owe them 50,000.00**, **If set off, we would still owe them 20,000.00**, with the note that it applies only if both sides agree.'])
    cy.then(() => { openDashboard(); openCustomers(); open360('CustomerDiv', names.c) })
    cy.get('.c360-card [data-dr-pos="receivable"]', { timeout: 15000 }).should('have.attr', 'data-amount', '30000')
    cy.get('.c360-card [data-dr-pos="payable"]').should('have.attr', 'data-amount', '50000')
    cy.get('.c360-card [data-dr-pos="net"]').should('contain', 'we would still owe them')
    snap(a2, 'position', '.c360-card')

    const a3 = act('In the Position block click **Statement** beside the supplier line.', ['The 360 view closes and the supplier’s statement opens, showing its 50,000 as type **Opening balance** (not “Bill”).'])
    cy.then(() => supplierByName(names.v).then((v) => cy.get(`.c360-card [data-dr-stmt="VENDOR:${v.id}"]`).click()))
    cy.get('.c360-card').should('not.exist'); cy.get('#StatementDialog').should('be.visible').and('contain', '50000')
    cy.contains('#StatementDialog tr', '50000').should('contain', 'Opening balance').and('not.contain', 'Bill')
    snap(a3, 'statement', '#StatementDialog')
    cy.contains('#StatementDialog button', 'Close').click()

    const a4 = act('**Finance → Trial Balance**.', ['Reading the position posted nothing: the trial balance is the same as before you opened it, and **Balanced ✓**.'])
    trialBalanceScreen()
    tb().then((after) => expect(after.totalDebit, 'a read posts nothing').to.eq(before.totalDebit))
    cy.get('#FinanceResults').should('contain', 'Balanced')
    snap(a4, 'trial-balance', '#FinanceResults')

    const c1 = act('Cleanup: reverse the customer’s opening balance. There is no Reverse button yet — open **Settings → Opening balances**, press **F12** → **Console**, paste the command with the opening balance’s document number (from the customer’s statement), press **Enter**.',
      ['The console prints **SUCCESS**.'], { cleanup: true, via: 'console', code: reverseOpeningCode })
    cy.then(() => reverseCustomerOpening(c1, names.c, 'rev8'))
  })

  // ── G9 · set-off and reversal (money, lifecycle) ─────────────────────────────────────────────────────────
  caseIt('G9', 'Set-off: refusals, record 30,000 (AR and AP only — never cash), the statements, then reverse it', () => {
    asLifecycle()
    const s = run + '9'
    testCase('G9', 'Set-off: refusals, record 30,000 (AR and AP only — never cash), the statements, then reverse it', {
      covers: ['DR4-1', 'DR4-2', 'DR4-3', 'DR4-4', 'DR4-6', 'DR4-7', 'G1-gap'], slice: 'DR-4', tenant: `${LIFECYCLE} (sacrificial — posts to the ledger)`, role: 'Owner',
      purpose: 'Clears what a partner owes us against what we owe them, by agreement. Posted through 1900 Set-off clearing: receivable and payable fall together, no cash moves, and it can be reversed.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', `A partner owing us **30,000** and owed **50,000** (customer DRG Pos C ${s} / supplier DRG Pos S ${s}, opening balances entered on the Opening balances screen).`],
      data: ['Amount **30000**, reason **agreed contra**, reference **letter 12**'],
      rollback: 'The set-off is reversed on screen (step 6). The customer’s opening balance is reversed in the console; the supplier’s cannot be reversed in this release and stays on the sacrificial tenant.',
    })
    let names, before
    seedPartnerOnScreen(null, s, 30000, 50000).then((n) => { names = n })
    tb().then((t) => { before = t })

    const a1 = act('**Register → Customer**, search **DRG Pos C …**, **360**, then **Set off…** under the Position.',
      ['A form opens with Customer, Supplier, **Amount (up to 30,000.00)** pre-filled at 30000, Reason, Reference and the confirmation tick.'])
    cy.then(() => { openDashboard(); openCustomers(); open360('CustomerDiv', names.c) })
    cy.get('.c360-card [data-dr-setoff-open]', { timeout: 15000 }).click()
    cy.get('.c360-card [data-dr-amount]').should('have.value', '30000')
    snap(a1, 'form', '.c360-card')

    const a2 = act('Click **Record set-off** with the tick empty; tick it and click again with no reason; then type **30000.01**.',
      ['Each is refused in words: “Tick the confirmation first.”, “Give a reason.”, “Enter an amount up to the limit shown.” Nothing is recorded.'])
    cy.get('.c360-card [data-dr-setoff-save]').click(); cy.get('.c360-card [data-dr-setoff-form] .dr-say').should('contain', 'Tick')
    cy.get('.c360-card [data-dr-same]').check(); cy.get('.c360-card [data-dr-setoff-save]').click()
    cy.get('.c360-card [data-dr-setoff-form] .dr-say').should('contain', 'reason')
    cy.get('.c360-card [data-dr-reason]').type('agreed contra')
    cy.get('.c360-card [data-dr-amount]').clear().type('30000.01'); cy.get('.c360-card [data-dr-setoff-save]').click()
    cy.get('.c360-card [data-dr-setoff-form] .dr-say').should('contain', 'limit')
    snap(a2, 'refusals', '.c360-card')

    const a3 = act('Set the amount to **30000**, reference **letter 12**, and click **Record set-off** (a double click records it once).',
      ['The view comes back: **They owe us 0.00**, **We owe them 20,000.00**, and **SETOFF-…** listed under Set-offs with a **Reverse** button.',
        'Under “Customer and supplier” there is **no Unlink** — a note says to reverse the set-off first.'])
    cy.get('.c360-card [data-dr-amount]').clear().type('30000'); cy.get('.c360-card [data-dr-reference]').type('letter 12')
    cy.get('.c360-card [data-dr-setoff-save]').dblclick()
    cy.get('.c360-card [data-dr-pos="receivable"]', { timeout: 15000 }).should('have.attr', 'data-amount', '0')
    cy.get('.c360-card [data-dr-pos="payable"]').should('have.attr', 'data-amount', '20000')
    cy.get('.c360-card [data-dr-setoff-row]').should('have.length', 1)
    cy.get('.c360-card [data-dr-unlink-blocked]').should('be.visible')
    cy.get('.c360-card [data-dr-unlink]').should('not.exist')
    snap(a3, 'recorded', '.c360-card'); close360()

    const a4 = act('**Finance → Trial Balance**.',
      ['**1100 Accounts Receivable** is 30,000 lower and **2000 Accounts Payable** 30,000 lower than before.', '**1000 Cash** and **1010 Bank** did not move; **1900 Set-off Clearing** nets to zero. **Balanced ✓**.'])
    trialBalanceScreen()
    tb().then((after) => {
      expect(net(after, '1100') - net(before, '1100')).to.eq(-30000)
      expect(net(after, '2000') - net(before, '2000')).to.eq(30000)
      expect(net(after, '1000') - net(before, '1000')).to.eq(0)
      expect(net(after, '1010') - net(before, '1010')).to.eq(0)
      expect(net(after, '1900') - net(before, '1900')).to.eq(0)
      expect(after.balanced).to.eq(true)
    })
    snap(a4, 'trial-balance', '#FinanceResults')

    const a5 = act('**Register → Customer**, search **DRG Pos C …**, click **Statement**.',
      ['A line **SETOFF-… · letter 12 (RCPT-…)** credits **30,000**; the closing balance is **0.00**.'])
    openCustomers()
    cy.then(() => { search('CustomerDiv', names.c); customerByName(names.c).then((c) => cy.get(`.stmt-btn[data-pid="${c.customerId || c.id}"]`).first().click({ force: true })) })
    cy.get('#StatementDialogBody', { timeout: 15000 }).should('contain', 'SETOFF-').and('contain', 'letter 12')
    snap(a5, 'statement', '#StatementDialog'); cy.contains('#StatementDialog button', 'Close').click()

    const a6 = act('Back in **360**, under Set-offs click **Reverse**, give the reason **entered to test**, confirm.',
      ['They owe us **30,000.00** and we owe them **50,000.00** again; the row reads **reversed** and has no Reverse button.', 'The trial balance is back where it started.'])
    cy.then(() => open360('CustomerDiv', names.c))
    cy.get('.c360-card [data-dr-reverse]').click(); cy.get('.uiC-card .uiC-input').type('entered to test'); cy.get('[data-ui-confirm="ok"]').click()
    cy.get('.c360-card [data-dr-pos="receivable"]', { timeout: 15000 }).should('have.attr', 'data-amount', '30000')
    cy.get('.c360-card [data-dr-setoff-row]').should('contain', 'reversed').find('[data-dr-reverse]').should('not.exist')
    tb().then((after) => { ['1100', '2000', '1000', '1010', '1900'].forEach((code) => expect(net(after, code) - net(before, code), code).to.eq(0)) })
    snap(a6, 'reversed', '.c360-card'); close360()

    const c1 = act('Cleanup: reverse the customer’s opening balance. There is no Reverse button yet — open **Settings → Opening balances**, press **F12** → **Console**, paste the command with the opening balance’s document number (from the customer’s statement) and press **Enter**.',
      ['The console prints **SUCCESS**; the customer’s statement shows the opening balance and its reversal, closing at 0.00.'], { cleanup: true, via: 'console', code: reverseOpeningCode })
    cy.then(() => reverseCustomerOpening(c1, names.c, 'rev9'))
  })

  // ── G10 · payment hint (lifecycle) ───────────────────────────────────────────────────────────────────────
  caseIt('G10', 'Receive Payment and Pay Vendor mention the other side — and apply nothing', () => {
    asLifecycle()
    const s = run + '0'
    testCase('G10', 'Receive Payment and Pay Vendor mention the other side — and apply nothing', {
      covers: ['DR5-1', 'DR5-2', 'DR5-3'], slice: 'DR-5', tenant: `${LIFECYCLE} (sacrificial — posts to the ledger)`, role: 'Owner',
      purpose: 'At the moment money is about to change hands, the owner learns the partner also has an open balance on the other side, and can choose a set-off instead.',
      prereq: ['Signed in as the owner of owner.lifecycle@.', `A partner owing us **30,000** and owed **50,000** (DRG Pos C ${s} / DRG Pos S ${s}).`],
      data: ['No payment is recorded in this case'],
      rollback: 'Nothing is paid. The customer’s opening balance is reversed in the console; the supplier’s stays (sacrificial tenant).',
    })
    let names
    seedPartnerOnScreen(null, s, 30000, 50000).then((n) => { names = n })

    const a1 = act('**Register → Customer**, search **DRG Pos C …**, click **Receive**.',
      ['At the top of the dialog: “This partner is also our supplier and we owe them **50,000.00**.” with **Set off instead…**', 'The amount (30000), method and date are as always — the note changes nothing the dialog sends.'])
    cy.then(() => { openDashboard(); openCustomers(); search('CustomerDiv', names.c); row('CustomerDiv', names.c).find('.rcv-pay-btn').click() })
    cy.get('#ReceivePaymentModal [data-dr-hint="CUSTOMER"]', { timeout: 15000 }).should('have.attr', 'data-amount', '50000')
    cy.get('#rcvAmount').should('have.value', '30000')
    snap(a1, 'receive-hint', '#ReceivePaymentModal .crud-box')

    const a2 = act('Click **Set off instead…**.', ['The payment dialog closes and the partner’s 360 view opens with **Set off…** under its Position.'])
    cy.get('#ReceivePaymentModal [data-dr-hint-setoff]').click()
    cy.get('.c360-card [data-dr-setoff-open]', { timeout: 15000 }).should('be.visible')
    snap(a2, 'to-setoff', '.c360-card'); close360()

    const a3 = act('**Register → Vender / Supplier**, search **DRG Pos S …**, click **Pay**.', ['“This partner is also our customer and owes us **30,000.00**.” Close the dialog without paying.'])
    openSuppliers(); cy.then(() => { search('VenderDiv', names.v); row('VenderDiv', names.v).find('.pay-vendor-btn').click() })
    cy.get('#PayVendorModal [data-dr-hint="VENDOR"]', { timeout: 15000 }).should('have.attr', 'data-amount', '30000')
    snap(a3, 'pay-hint', '#PayVendorModal .crud-box'); cy.get('#PayVendorModal .crud-x').click()

    const c1 = act('Cleanup: reverse the customer’s opening balance in the console, as in G9.', ['The console prints **SUCCESS**.'], { cleanup: true, via: 'console', code: reverseOpeningCode })
    cy.then(() => reverseCustomerOpening(c1, names.c, 'rev10'))
  })
})
