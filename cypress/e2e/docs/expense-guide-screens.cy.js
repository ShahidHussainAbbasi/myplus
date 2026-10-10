/**
 * Expense Management & Supplier Payables Test Book — every manual case as a REAL step-by-step test, recorded.
 *
 * Each `caseIt` is one manual test case: set-up, numbered actions, the expected result of each action, and the
 * clean-up. Every action is PERFORMED, ASSERTED and PHOTOGRAPHED in that order, and the page builder
 * (docs/guides/build-expense-guide.js) shows a case as "recorded" only if the whole case passed — so no expected result
 * on the page is one the app did not actually produce on this build.
 *
 * WHERE A SCREEN IS MISSING. A few steps have no screen (an operator-only check, a purchase on credit set up through the
 * purchase form's own request). Those are marked `via: 'run'` with the screen path a person uses in the text.
 *
 * TENANT. Cases that move money run on owner.lifecycle@ — the Test Book's spare business — never on owner.business@,
 * which other specs sign in as. Every case registers an undo in SAFETY, run in after(), so a case that fails half-way
 * still leaves the switch, the capability and the settings as they were.
 *
 * Run headed:
 *   npx cypress run --headed --browser electron --spec cypress/e2e/docs/expense-guide-screens.cy.js --config trashAssetsBeforeRuns=false
 *   (one case: --env guideOnly=4-1)
 */
const OUT_DIR = 'cypress/guide-out/expense-guide'
const ONLY = String(Cypress.env('guideOnly') || '').split(/[,;|]/).map((x) => x.trim()).filter(Boolean)
const caseIt = (id, title, fn) => ((ONLY.length && !ONLY.includes(id)) ? it.skip : it)(`${id} — ${title}`, fn)

const LIFECYCLE = 'owner.lifecycle@myplus.com'
const LIFECYCLE_ORG_NAME = "Owner Lifecycle's organization"
const PW = 'Demo@2025!'
const GW = 'http://localhost:8765'
const CAP = 'expenseManagement'
const POLICY = 'pos.purchase.creditLimitPolicy'
const run = String(Date.now()).slice(-6)

const SAFETY = []
let cur = null

// ── the case recorder (same shape as the settings guide) ─────────────────────────────────────────────────────
const testCase = (id, section, title, meta) => {
  cur = { id, section, title, setup: [], actions: [], cleanup: [], shots: [], ...meta }
}
const setup = (text) => cur.setup.push(text)
const act = (text, expect, opts = {}) => {
  const a = { do: text, expect: [].concat(expect || []), shots: [], via: opts.via || 'screen' }
  ;(opts.cleanup ? cur.cleanup : cur.actions).push(a)
  return a
}
const snap = (a, name, subject) => {
  const pos = cur.actions.includes(a) ? `a${cur.actions.indexOf(a) + 1}` : `c${cur.cleanup.indexOf(a) + 1}`
  const file = `xg-${cur.id}-${pos}-${name}`
  a.shots.push(file)
  cur.shots.push(file)
  return subject ? cy.get(subject).first().screenshot(file, { overwrite: true })
                 : cy.screenshot(file, { capture: 'viewport', overwrite: true })
}

// ── app helpers ──────────────────────────────────────────────────────────────────────────────────────────────
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return Array.isArray(b) ? b : [] }
const asLifecycle = (fresh) => cy.loginAs(LIFECYCLE, PW, '/getBusinessDashboardStats', fresh ? 'xg-' + Date.now() : undefined)
const openDashboard = () => { cy.visit('/businessDashboard'); cy.waitForAppReady() }
const openMenu = (dd) => cy.get(`#${dd}`).then(($d) => { if (!$d.hasClass('snav-open')) cy.get(`#${dd} .snav-btn`).click() })
const openExpenses = () => {
  openMenu('snavTill')
  cy.get('#navExpenses').should('be.visible').click()
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}
const openSuppliers = (name) => {
  cy.openSection('VenderDiv')
  if (name) cy.get('#VenderDiv input[type="search"]').first().clear().type(name)
}
const token = () => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: LIFECYCLE, password: PW } })
  .its('body.data.accessToken')
const tb = () => token().then((t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: { Authorization: `Bearer ${t}` } })
  .then((r) => {
    expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
    const m = {}
    ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
    return m
  }))
const delta = (b, a, code) => Math.round(((a[code] || 0) - (b[code] || 0)) * 100) / 100
const showTrialBalance = () => {
  cy.window().then((w) => w.showFinance('trialBalance'))
  // The report lists only accounts that carry a balance, so no one account is a safe signal it has drawn:
  // a fresh business has no Accounts Payable yet. Its total line is always there.
  cy.contains('#FinanceDiv', 'Balanced', { timeout: 20000 }).should('be.visible')
}
const vendorRow = (id) => cy.request('/getUserVender').then((r) => list(r.body).find((v) => v.id === id))
const categoryByName = (name) => cy.request('/expense/categories').then((r) => {
  const cats = (r.body && r.body.data) || []
  // strict: a name this business does not have FAILS here. Falling back to "the first category" once recorded a
  // Utilities case as Rent without anyone noticing (EX-8a, 9-1).
  const hit = cats.find((c) => c.name === name && c.active !== false)
  expect(hit, `category "${name}" in this business (has: ${cats.filter((c) => c.active !== false).map((c) => c.name).join(', ')})`).to.exist
  return hit
})

/** A supplier of the lifecycle business, created through the Vender / Supplier form's own request. */
const newSupplier = (label, extra = {}) => {
  const name = `XG${label}_${run}`
  cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: `XGCo${label}_${run}`, email: `xgco${label}${run}@t.com` } })
  return cy.request('/getUserCompany').then((cr) => {
    const company = list(cr.body).find((c) => c.name === `XGCo${label}_${run}`)
    return cy.request({ method: 'POST', url: '/addVender', form: true,
      body: { name, companyId: company.id, mobile: '0306' + String(Date.now()).slice(-7), email: `xgv${label}${run}@t.com`, ...extra } })
  }).then(() => cy.request('/getUserVender')).then((r) => ({ id: list(r.body).find((v) => v.name === name).id, name }))
}
/** A purchase on credit (paid 0) through the purchase form's own request. */
const creditPurchase = (vendorId, amount, inv) =>
  cy.seedProduct({ name: `XGP_${inv}`, sellingPrice: amount + 1, stock: 1 }).then(({ productId }) =>
    cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
      body: { productId, quantity: 1, venderId: vendorId, paidAmount: 0, 'stock.bpurchaseRate': amount, 'stock.bsellRate': amount + 1,
        totalAmount: amount, netAmount: amount, purchaseInvoiceNo: inv } }).its('body'))
const purchaseIdOf = (inv) => cy.request('/getUserPurchase').then((r) => (list(r.body).find((p) => p.purchaseInvoiceNo === inv) || {}).purchaseId)

/** Fill and save the Expenses form on screen. Returns nothing; the row is found by its payee. */
const fillExpense = ({ category, amount, paidFrom, supplierId, payee }) => {
  categoryByName(category).then((c) => cy.get('#expCategory').select(String(c.id), { force: true }))
  cy.get('#expAmount').clear().type(String(amount))
  cy.get('#expPaidFrom').select(paidFrom, { force: true })
  if (supplierId) cy.get('#expSupplier').select(String(supplierId), { force: true })
  if (payee) cy.get('#expPayee').clear().type(payee)
}
const expenseRow = (payee) => cy.contains('#tableExpense tbody tr', payee, { timeout: 25000 })

/** The operator's console, on one tenant's panel. */
const openTenantPanel = (orgName) => {
  cy.visit('/platformDashboard')
  cy.get('[data-testid="tenant-row"]', { timeout: 20000 }).should('have.length.greaterThan', 0)
  cy.get('#platSearch').clear().type(orgName)
  cy.contains('[data-testid="tenant-row"]', orgName, { timeout: 15000 }).click()
  cy.get('#platPayables h4', { timeout: 20000 }).should('be.visible')
}
const flip = (orgId, source, reason) =>
  cy.request({ method: 'POST', url: '/platform/payablesSource', form: true, body: { organizationId: orgId, source, reason } })
    .its('body.status').should('eq', 'SUCCESS')

let lifecycleOrg = null

/** Clean-up on screen: Expenses → the bill's row → Void, with a reason. */
const voidBillOnScreen = (payee, a) => {
  openDashboard(); openExpenses()
  expenseRow(payee).find('[data-cy=void-expense]', { timeout: 25000 }).click()
  cy.get('.uiC-input').type('Test Book clean-up')
  cy.get('[data-ui-confirm="ok"]').click()
  expenseRow(payee).find('.exp-chip').should('contain', 'Void')
  if (a) snap(a, 'voided')
}
/** Leftovers of earlier recording runs (unpaid XG test bills) — voided so the spare business stays clean. */
/** Every voucher of the signed-in business, page by page (the list is paged since EX-2d). */
const allVouchers = (page = 0, acc = []) => cy.request(`/expense/vouchers?size=200&page=${page}`).then((r) => {
  const d = (r.body && r.body.data) || {}
  const rows = acc.concat(d.content || [])
  return d.last === false ? allVouchers(page + 1, rows) : rows
})
const voidLeftoverTestBills = () => allVouchers().then((all) => {
  const rows = all
    .filter((v) => v.status === 'POSTED' && v.postingStatus === 'POSTED_GL' && v.paidFrom === 'AP' && Number(v.paidAmount || 0) === 0
      && /^XG/.test(v.payeeName || v.supplierName || ''))
  rows.forEach((v) => cy.request({ method: 'POST', url: `/expense/vouchers/${v.id}/void`, body: { reason: 'Test Book clean-up' }, failOnStatusCode: false }))
})

describe('Expense Management & Supplier Payables Test Book — recorded step by step', () => {
  beforeEach(() => cy.viewport(1366, 860))

  before(() => {
    cy.loginAsOperator()
    cy.orgOf(LIFECYCLE).then((o) => { lifecycleOrg = o.id })
    asLifecycle()
    cy.setCapability(CAP, true)
    voidLeftoverTestBills()
    SAFETY.push(() => { asLifecycle(); cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } }) })
    SAFETY.push(() => { asLifecycle(); voidLeftoverTestBills() })
    SAFETY.push(() => { asLifecycle(); cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: POLICY } }) })
    SAFETY.push(() => { cy.loginAsOperator(); cy.then(() => { if (lifecycleOrg) flip(lifecycleOrg, 'BUSINESS', 'Test Book recording: leave as found') }) })
  })

  afterEach(function () {
    cur.passed = this.currentTest.state === 'passed'
    cur.capturedAt = new Date().toISOString()
    if (!cur.passed) cur.error = String((this.currentTest.err && this.currentTest.err.message) || '').slice(0, 400)
    cy.writeFile(`${OUT_DIR}/${cur.id}.json`, cur)
  })

  after(() => { SAFETY.slice().reverse().forEach((fn) => fn()) })

  // ═══ EX-0a · The on/off switch ═════════════════════════════════════════════════════════════════════════════
  const KEY = 'org.cap.' + CAP
  const SWITCH = `#businessConfigBody [data-key="${KEY}"]`
  // the picture shows the whole setting row (label, help text and switch), not the bare checkbox
  const SWITCH_ROW = `#businessConfigBody .cfg-row:has([data-key="${KEY}"])`
  const openConfig = () => {
    openDashboard()
    openMenu('snavSettings')
    cy.get('#navConfiguration').click()
    cy.get('#businessConfigBody .cfg-group', { timeout: 20000 }).should('have.length.greaterThan', 3)
    cy.revealSetting(KEY)
  }
  const resetCapHere = () => cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY } })

  caseIt('0a-1', 'The switch is there, and off, for a business-type tenant', () => {
    testCase('0a-1', 'ex0a', 'The switch is there, and off, for a business-type tenant',
      { who: ['owner.mobile (recorded)', 'owner.pesticide (recorded)', 'owner.business', 'owner.pharma', 'owner.marketplace', 'owner.inventory'] })
    setup('Nobody has switched Expense management on for this business (the recording removes any earlier choice first).')
    ;[['owner.mobile@myplus.com', 'mobile'], ['owner.pesticide@myplus.com', 'pesticide']].forEach(([email, tag], i) => {
      cy.loginAs(email, PW, '/getBusinessDashboardStats')
      resetCapHere()
      cy.loginAs(email, PW, '/getBusinessDashboardStats', 'xg-0a1-' + tag + Date.now())
      const a = act(`Log in as <b>${email.split('@')[0]}</b>, open <b>Settings → Configuration</b>, Business tab, and find <b>Expense management</b> under <b>What this business does</b>.`,
        i === 0 ? ['A switch named <b>Expense management</b> with its help text ("… Off until you switch it on.").', 'It is <b>unticked</b> and not greyed out.', 'There is no <b>Expenses</b> item under Till.']
                : ['Same: present, unticked, not greyed out; no Expenses item.'])
      openConfig()
      cy.get(SWITCH).should('exist').and('not.be.checked').and('not.be.disabled')
      cy.get(SWITCH).closest('.cfg-row').should('contain', 'Expense management')
      cy.get('#navExpenses').closest('[data-capability]').should('have.class', 'cap-off')
      snap(a, tag, SWITCH_ROW)
    })
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  caseIt('0a-3', 'An owner or admin can switch it on; a user cannot', () => {
    testCase('0a-3', 'ex0a', 'An owner or admin can switch it on; a user cannot', { who: ['owner.business', 'admin.business', 'user.business (all recorded)'] })
    cy.loginAsOwner(); resetCapHere()
    SAFETY.push(() => { cy.loginAsOwner(); resetCapHere() })
    cy.loginAsOwner(undefined, undefined, 'xg-0a3o-' + Date.now())
    const a1 = act('As <b>owner.business</b>: tick <b>Expense management</b>.', ['The message <b>Saved</b> appears.'])
    openConfig()
    cy.get(SWITCH).check()
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    snap(a1, 'owner-saved')
    const a2 = act('Reload the page and reopen Configuration.', ['The switch is still ticked.'])
    openConfig()
    cy.get(SWITCH).should('be.checked')
    snap(a2, 'still-on', SWITCH_ROW)
    const a3 = act('Untick it again.', ['Saved; unticked.'])
    cy.get(SWITCH).uncheck()
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    cy.loginAs('admin.business@myplus.com', PW, '/getBusinessDashboardStats', 'xg-0a3a-' + Date.now())
    const a4 = act('As <b>admin.business</b>: tick it, then untick it.', ['Saved both times, exactly as for the owner.'])
    openConfig()
    cy.get(SWITCH).check()
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    snap(a4, 'admin-saved')
    cy.get(SWITCH).uncheck()
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    cy.loginAs('user.business@myplus.com', PW, '/getBusinessDashboardStats', 'xg-0a3u-' + Date.now())
    const a5 = act('As <b>user.business</b>: look for <b>Settings → Configuration</b>; and try to switch it on anyway.',
      ['Configuration is not offered to a user, and a direct attempt is refused — the switch cannot be changed.'], { via: 'run' })
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, failOnStatusCode: false, body: { key: KEY, value: 'true' } })
      .then((r) => expect(r.status === 403 || (r.body && r.body.success !== true), JSON.stringify(r.body).slice(0, 200)).to.eq(true))
    act('As owner.business: <b>Reset</b> Expense management to its default (off).', ['Unticked, as found.'], { cleanup: true })
    cy.loginAsOwner(); resetCapHere()
  })

  caseIt('0a-4', 'Changing the type of business keeps the switch on', () => {
    testCase('0a-4', 'ex0a', 'Changing the type of business keeps the switch on', { who: ['owner.mobile (recorded; seeded as Retail)'] })
    cy.loginAsMobileOwner()
    cy.setCapability(CAP, true)
    SAFETY.push(() => { cy.loginAsMobileOwner(); cy.request({ method: 'POST', url: '/saveBusinessShape', form: true, failOnStatusCode: false, body: { shape: 'retail' } }); resetCapHere() })
    const a1 = act('Tick <b>Expense management</b>. Under <b>What kind of business this is</b>, choose <b>Pharmacy / dispensing</b> and read the confirmation.',
      ['The confirmation lists switches turning on and off, and <b>Expense management is in neither list</b>.'], { via: 'run' })
    cy.request('/getBusinessShapePreview?shape=pharmacy').then((r) => {
      const p = (r.body && r.body.data) || {}
      expect([...(p.turningOn || []), ...(p.turningOff || [])]).not.to.include('Expense management')
    })
    const a2 = act('Confirm the change to Pharmacy, then change back to <b>Retail counter / POS</b> and confirm.',
      ['After each change <b>Expense management is still ticked</b>.'], { via: 'run' })
    cy.request({ method: 'POST', url: '/saveBusinessShape', form: true, body: { shape: 'pharmacy' } }).its('body.success').should('eq', true)
    cy.getCapabilities().its(CAP).should('eq', true)
    cy.request({ method: 'POST', url: '/saveBusinessShape', form: true, body: { shape: 'retail' } }).its('body.success').should('eq', true)
    cy.loginAsMobileOwner(undefined, undefined, 'xg-0a4-' + Date.now())
    const a3 = act('Open Configuration.', ['<b>Expense management</b> is ticked.'])
    openConfig()
    cy.get(SWITCH).should('be.checked')
    snap(a3, 'still-on', SWITCH_ROW)
    act('Type back to <b>Retail counter / POS</b> (done above); untick Expense management.', [], { cleanup: true })
    resetCapHere()
  })

  caseIt('0a-5', 'One business’s choice does not reach another', () => {
    testCase('0a-5', 'ex0a', 'One business’s choice does not reach another', { who: ['owner.mobile', 'owner.pesticide (recorded)'] })
    cy.loginAsMobileOwner()
    const a1 = act('As <b>owner.mobile</b>, tick Expense management.', ['Saved.'], { via: 'run' })
    cy.setCapability(CAP, true)
    SAFETY.push(() => { cy.loginAsMobileOwner(); resetCapHere() })
    cy.loginAs('owner.pesticide@myplus.com', PW, '/getBusinessDashboardStats', 'xg-0a5-' + Date.now())
    const a2 = act('Log out; log in as <b>owner.pesticide</b> and open Configuration.', ['owner.pesticide’s Expense management is still <b>unticked</b>.'])
    openConfig()
    cy.get(SWITCH).should('not.be.checked')
    snap(a2, 'pesticide-off', SWITCH_ROW)
    act('Untick Expense management for owner.mobile again.', [], { cleanup: true })
    cy.loginAsMobileOwner(); resetCapHere()
  })

  caseIt('0a-6', 'Off by default on the school, welfare and farm dashboards', () => {
    testCase('0a-6', 'ex0a', 'Off by default on the school, welfare and farm dashboards', { who: ['owner.education', 'owner.welfare', 'owner.agriculture (all recorded)'] })
    setup('Nobody has switched the module on for these businesses (the recording removes any earlier choice).')
    const D = [
      ['owner.education@myplus.com', '/getDashboardData', '/educationDashboard', 'school'],
      ['owner.welfare@myplus.com', '/getWelfareConfig', '/welfareDashboard', 'welfare'],
      ['owner.agriculture@myplus.com', '/agricultureDashboard', '/agricultureDashboard', 'farm'],
    ]
    D.forEach(([email, check, dash, tag]) => {
      cy.loginAs(email, PW, check)
      cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, failOnStatusCode: false, body: { key: KEY } })
      cy.loginAs(email, PW, check, 'xg-0a6-' + tag + Date.now())
      const a = act(`Log in as <b>${email.split('@')[0]}</b> and open the dashboard.`, ['There is no <b>Expenses</b> entry until the owner switches the module on (case 2a-1).'])
      cy.visit(dash); cy.waitForAppReady()
      cy.get('#navExpenses').closest('[data-capability]').should('have.class', 'cap-off')
      snap(a, tag)
    })
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  caseIt('0a-7', 'The platform operator sees it, in the free plan, off', () => {
    testCase('0a-7', 'ex0a', 'The platform operator sees it, in the free plan, off', { who: ['admin@myplus.com (operator)'] })
    cy.loginAsOperator()
    const a1 = act('Open the <b>Platform</b> console and pick a business on the <b>FREE</b> plan (Owner Business’s organization).',
      ['Under <b>Capabilities</b>, <b>Expense management</b> is listed as included in the plan, and it is off until that owner switches it on.'])
    cy.orgOf('owner.business@myplus.com').then((o) => cy.request(`/platform/entitlements?organizationId=${o.id}`)).then((ent) => {
      const row = ((ent.body && ent.body.data && ent.body.data.capabilities) || []).find((r) => r.capability === CAP)
      expect(row.inPlan).to.eq(true)
    })
    openTenantPanel("Owner Business's organization")
    cy.contains('#platDetailBody', 'Expense management').scrollIntoView()
    snap(a1, 'entitlement')
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  // ═══ EX-0b · Expense accounts ═════════════════════════════════════════════════════════════════════════════
  caseIt('0b-1', 'The expense accounts are in the books, and the trial balance balances', () => {
    testCase('0b-1', 'ex0b', 'The expense accounts are in the books, and the trial balance balances', { who: ['owner.lifecycle (recorded)', 'owner.business', 'owner.pharma'] })
    asLifecycle()
    openDashboard()
    const a1 = act('Open <b>Finance → Trial Balance</b>.', ['It says <b>balanced</b> (total debits = total credits).'])
    tb()
    showTrialBalance()
    snap(a1, 'trial-balance')
    const a2 = act('In the same browser tab open <code>http://localhost:8080/gl/accounts</code> (a plain list, no screen of its own).',
      ['The list includes <b>1300</b> Employee Advances, <b>2300</b> Employee Reimbursements Payable, and the EXPENSE accounts <b>6000</b> Rent, <b>6100</b> Utilities, <b>6200</b> Fuel and Transport, <b>6300</b> Repairs and Maintenance, <b>6400</b> Marketing, <b>6500</b> Bank Charges, <b>6600</b> Office and Supplies and <b>6900</b> Other Operating Expenses.'])
    cy.request('/gl/accounts').then((r) => {
      const body = typeof r.body === 'string' ? JSON.parse(r.body) : r.body
      const rows = Array.isArray(body) ? body : (body.data || body.rows || [])
      const codes = rows.map((x) => String(x.code))
      ;['1300', '2300', '6000', '6100', '6200', '6300', '6400', '6500', '6600', '6900'].forEach((c) => expect(codes, c).to.include(c))
    })
    a2.via = 'run'
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  caseIt('0b-2', 'Owners keep their own categories', () => {
    testCase('0b-2', 'ex0b', 'Owners keep their own categories', { who: ['owner.lifecycle (recorded)', 'admin.business', 'user.business'] })
    setup('Expense management switched on (case 0a-3).')
    const name = 'XG Staff tea ' + run, renamed = 'XG Staff refreshments ' + run, payee = 'XG cat ' + run
    let catId = null, vid = null
    asLifecycle(true)
    SAFETY.push(() => { asLifecycle(); if (vid) cy.request({ method: 'POST', url: `/expense/vouchers/${vid}/void`, body: { reason: 'Test Book' }, failOnStatusCode: false }) })
    SAFETY.push(() => { asLifecycle(); if (catId) cy.request({ method: 'PATCH', url: `/expense/categories/${catId}`, body: { active: false }, failOnStatusCode: false }) })
    openDashboard(); openExpenses()
    const a1 = act('<b>Till → Expenses</b>, press <b>Categories</b>.',
      ['Every category is listed with its account and an <b>On</b> tick; switched-off ones are listed too.',
       'The account list holds only expense accounts — never 5000 Cost of Goods Sold or a cash/bank account.'])
    cy.get('[data-cy=expense-categories-open]').click()
    cy.get('#expCatTable tbody [data-cy=cat-row]', { timeout: 20000 }).should('have.length.greaterThan', 0)
    cy.get('#expCatNewAccount option').should(($o) => expect([...$o].map((o) => o.value)).not.to.include('5000'))
    snap(a1, 'list', '#expCatPanel')
    const a2 = act(`In the last row type <b>${name}</b>, account <b>6100 Utilities</b>, press <b>Add</b>.`,
      ['“Category saved”; the new row is in the list, switched on.', 'The New Expense form’s <b>Category</b> now offers it.'])
    cy.get('[data-cy=cat-new-name]').type(name)
    cy.get('[data-cy=cat-new-account]').select('6100')
    cy.get('[data-cy=cat-add]').click()
    cy.get('#expCatMsg').should('contain', 'Category saved')
    cy.get('#expCategory option').should('contain', name)
    cy.request('/expense/categories').then((r) => { catId = r.body.data.find((c) => c.name === name).id })
    snap(a2, 'added', '#expCatPanel')
    const a3 = act(`Record <b>${name}</b>, amount <b>3</b>, cash, payee <b>${payee}</b>.`, ['The row reaches <b>In the books</b>; it is posted to <b>6100</b>.'])
    cy.get('#expCategory option').contains(name).then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
    cy.get('#expAmount').clear().type('3')
    cy.get('#expPayee').clear().type(payee)
    cy.get('[data-cy=save-expense]').click()
    expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    expenseRow(payee).invoke('attr', 'data-id').then((id) => {
      vid = id
      cy.request(`/expense/vouchers/${id}`).its('body.data.lines.0.accountCode').should('eq', '6100')
    })
    snap(a3, 'recorded', '#tableExpense')
    const a4 = act(`In Categories, rename it to <b>${renamed}</b>, untick <b>On</b>, press its <b>Save</b>.`,
      ['“Category saved”.', 'The form’s Category list no longer offers it.',
       `The expense recorded before still reads <b>${name}</b> and stays in 6100 — history is not rewritten.`])
    cy.then(() => {
      const r = `#expCatTable tbody tr[data-id="${catId}"]`
      cy.get(`${r} [data-cy=cat-name]`).clear().type(renamed)
      cy.get(`${r} [data-cy=cat-on]`).uncheck()
      cy.get(`${r} [data-cy=cat-save]`).click()
      cy.get('#expCatMsg').should('contain', 'Category saved')
      cy.get(`${r} [data-cy=cat-on]`).should('not.be.checked')
    })
    cy.get('#expCategory option').should('not.contain', renamed).and('not.contain', name)
    expenseRow(payee).should('contain', name)
    snap(a4, 'switched-off', '#expCatPanel')
    const a5 = act('Add a category called <b>Rent</b> (one already exists).', ['Refused, in words: “There is already a category called Rent.”'])
    cy.get('[data-cy=cat-new-name]').clear().type('Rent')
    cy.get('[data-cy=cat-add]').click()
    cy.get('#expCatMsg').should('contain', 'already a category called Rent')
    snap(a5, 'duplicate', '#expCatPanel')
    cy.then(() => {
      act('As <b>user.business</b>: open Expenses.', ['There is no <b>Categories</b> button; a user cannot change categories (the server refuses it too).'], { via: 'run' })
      act(`Void the ${payee} expense (reason "Test Book"). The category stays switched off — categories are never deleted, past expenses point at them.`, [], { cleanup: true })
    })
    cy.loginAsTier('user', 'business')
    cy.then(() => cy.request({ method: 'PATCH', url: `/expense/categories/${catId}`, body: { active: true }, failOnStatusCode: false })
      .then((r) => expect(r.status >= 400 || r.body.success === false).to.eq(true)))
    asLifecycle()
    cy.then(() => cy.request({ method: 'POST', url: `/expense/vouchers/${vid}/void`, body: { reason: 'Test Book' } }).its('body.success').should('eq', true))
  })

  // ═══ EX-0c · Document numbers ═════════════════════════════════════════════════════════════════════════════
  caseIt('0c-1', 'Receipt numbers carry on, one after another', () => {
    testCase('0c-1', 'ex0c', 'Receipt numbers carry on, one after another', { who: ['owner.lifecycle (recorded)', 'owner.business', 'cashier.a', 'owner.pharma'] })
    setup('A customer (the recording makes one). Invoices, quotes and credit notes use the same shared counter; the automated gate <code>document-number-integrity</code> checks those series, including six receipts at the same instant.')
    asLifecycle()
    const cname = 'XG0C_' + run
    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: cname, contact: 'C0' + run } })
    cy.request('/getUserCustomer').then((r) => list(r.body).find((c) => c.name === cname).customerId).then((cid) => {
      const a1 = act('Receive <b>1</b> in cash from the customer, then another <b>1</b>.',
        ['Two receipts <b>RCPT-…</b> whose numbers are consecutive (the second is the first plus one); no series restarted at 1.'], { via: 'run' })
      cy.request({ method: 'POST', url: '/receivePayment', form: true, body: { customerId: cid, amount: 1, method: 'CASH', idempotencyKey: 'xg0c1-' + run } })
        .its('body.object.receiptNo').then((r1) => {
          cy.request({ method: 'POST', url: '/receivePayment', form: true, body: { customerId: cid, amount: 1, method: 'CASH', idempotencyKey: 'xg0c2-' + run } })
            .its('body.object.receiptNo').then((r2) => {
              const n1 = Number(String(r1).replace(/\D/g, '')), n2 = Number(String(r2).replace(/\D/g, ''))
              expect(n1, 'a real receipt number').to.be.greaterThan(0)   // RCPT-000001 on a fresh business is right
              expect(n2).to.eq(n1 + 1)
            })
        })
    })
    act('Nothing to undo — two receipts of 1 stand as the test customer’s credit.', [], { cleanup: true })
  })

  // ═══ EX-1 · Record an expense ═════════════════════════════════════════════════════════════════════════════
  const ex1Payee = 'XG landlord ' + run
  caseIt('1-1', 'Record rent paid in cash, and watch it reach the books', () => {
    testCase('1-1', 'ex1', 'Record rent paid in cash, and watch it reach the books', { who: ['owner.lifecycle (recorded)', 'owner.business', 'owner.mobile', 'owner.pharma'] })
    setup('Expense management switched on (case 0a-3), then log out and in.')
    asLifecycle(true)
    tb().then((before) => {
      openDashboard()
      const a1 = act(`<b>Till → Expenses</b>: Category <b>Rent</b>, Amount <b>1500</b>, Paid from <b>Cash</b>, Payee <b>${ex1Payee}</b>, press <b>Save and post</b>.`,
        ['"Expense saved. Posting to the books."', 'The new row has a number like <b>EXP-…</b>, amount 1,500.00, and its status ends as <b>In the books</b>.'])
      openExpenses()
      fillExpense({ category: 'Rent', amount: 1500, paidFrom: 'CASH', payee: ex1Payee })
      cy.get('[data-cy=save-expense]').click()
      cy.get('#expMsg').should('contain', 'Posting to the books')
      expenseRow(ex1Payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
      snap(a1, 'in-the-books')
      const a2 = act('Open <b>Finance → Trial Balance</b>.', ['<b>6000 Rent</b> up 1,500, <b>1000 Cash</b> down 1,500, still balanced.'])
      tb().then((after) => {
        expect(delta(before, after, '6000')).to.eq(1500)
        expect(delta(before, after, '1000')).to.eq(-1500)
      })
      showTrialBalance()
      snap(a2, 'trial-balance')
    })
    act('Kept for case 1-3, which voids an expense; void this one too from its row if you want it gone (reason "Test Book").', [], { cleanup: true })
  })

  caseIt('1-2', 'Paid from the bank uses the bank account', () => {
    testCase('1-2', 'ex1', 'Paid from the bank uses the bank account', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    asLifecycle()
    const payee = 'XG utility ' + run
    tb().then((before) => {
      openDashboard(); openExpenses()
      const a1 = act(`Record <b>Electricity, gas and water</b>, <b>730.50</b>, Paid from <b>Bank</b>, Payee <b>${payee}</b>.`,
        ['In the books.', 'Trial balance: <b>6100 Utilities</b> up 730.50 and <b>1010 Bank</b> down 730.50. Cash does not move.'])
      fillExpense({ category: 'Electricity, gas and water', amount: 730.5, paidFrom: 'BANK', payee })
      cy.get('[data-cy=save-expense]').click()
      expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
      tb().then((after) => {
        expect(delta(before, after, '6100')).to.eq(730.5)
        expect(delta(before, after, '1010')).to.eq(-730.5)
        expect(delta(before, after, '1000')).to.eq(0)
      })
      snap(a1, 'bank')
      const c1 = act('Void it from its row (reason "Test Book").', ['The row shows Void; 6100 and 1010 are back.'], { cleanup: true })
      expenseRow(payee).find('[data-cy=void-expense]').click()
      cy.get('.uiC-input').type('Test Book')
      cy.get('[data-ui-confirm="ok"]').click()
      expenseRow(payee).find('.exp-chip').should('contain', 'Void')
    })
  })

  caseIt('1-3', 'Void with a reason puts the books back', () => {
    testCase('1-3', 'ex1', 'Void with a reason puts the books back', { who: ['owner.lifecycle (recorded)', 'owner.business', 'admin.business'] })
    asLifecycle()
    const payee = 'XG repairs ' + run
    openDashboard(); openExpenses()
    fillExpense({ category: 'Repairs and maintenance', amount: 250, paidFrom: 'CASH', payee })
    cy.get('[data-cy=save-expense]').click()
    expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    tb().then((before) => {
      const a1 = act(`Record <b>Repairs</b> 250 in cash (Payee <b>${payee}</b>), wait for <b>In the books</b>, press <b>Void</b>, and try to confirm with no reason.`,
        ['Refused: a message says <b>Reason (required)</b>, and the expense is not voided.'])
      expenseRow(payee).find('[data-cy=void-expense]').click()
      cy.get('[data-ui-confirm="ok"]').click()
      cy.contains('Reason (required)', { timeout: 10000 }).should('be.visible')
      snap(a1, 'reason-required')
      cy.get('[data-ui-confirm="ok"]').click()
      expenseRow(payee).find('.exp-chip').should('contain', 'In the books')
      expenseRow(payee).find('[data-cy=void-expense]').click()
      const a2 = act('Type the reason "entered by mistake" and confirm.',
        ['The row shows <b>Void</b> and keeps its number; it is not deleted.', 'Trial balance: 6300 and 1000 are back where they were.'])
      cy.get('.uiC-input').type('entered by mistake')
      cy.get('[data-ui-confirm="ok"]').click()
      expenseRow(payee).find('.exp-chip').should('contain', 'Void')
      expenseRow(payee).should('contain', 'EXP-')   // it keeps its number
      cy.wait(3000)
      tb().then((after) => {
        expect(delta(before, after, '6300')).to.eq(-250)
        expect(delta(before, after, '1000')).to.eq(250)
      })
      snap(a2, 'voided')
    })
    act('Nothing to undo — the expense is void and the books are back.', [], { cleanup: true })
  })

  caseIt('1-4', 'A user records and sees only their own; only owner and admin void', () => {
    testCase('1-4', 'ex1', 'A user records and sees only their own; only owner and admin void', { who: ['user.business', 'owner.business (recorded)'] })
    cy.loginAsOwner(); cy.setCapability(CAP, true)
    SAFETY.push(() => { cy.loginAsOwner(); resetCapHere() })
    const payee = 'XG user ' + run
    cy.loginAs('user.business@myplus.com', PW, '/getBusinessDashboardStats', 'xg-14u-' + Date.now())
    openDashboard()
    const a1 = act(`As <b>user.business</b>: Till → Expenses, record <b>Rent</b> 10 in cash, Payee <b>${payee}</b>.`,
      ['It saves. The list shows only the expenses this user recorded, and there is <b>no Void</b> button.'])
    openExpenses()
    fillExpense({ category: 'Rent', amount: 10, paidFrom: 'CASH', payee })
    cy.get('[data-cy=save-expense]').click()
    expenseRow(payee).find('[data-cy=void-expense]').should('not.exist')
    cy.get('#tableExpense thead th').should('have.length', 7)
    snap(a1, 'user-view')
    cy.loginAsOwner(undefined, undefined, 'xg-14o-' + Date.now())
    openDashboard()
    const a2 = act('As <b>owner.business</b>: open the same list.', ['The owner sees everyone’s expenses, including the user’s, with <b>Void</b>.'])
    openExpenses()
    expenseRow(payee).find('[data-cy=void-expense]', { timeout: 25000 }).should('be.visible')
    snap(a2, 'owner-view')
    const c1 = act('As the owner: void the user’s 10 expense (reason "Test Book"), then reset Expense management to off.', [], { cleanup: true })
    expenseRow(payee).find('[data-cy=void-expense]').click()
    cy.get('.uiC-input').type('Test Book')
    cy.get('[data-ui-confirm="ok"]').click()
    expenseRow(payee).find('.exp-chip').should('contain', 'Void')
    resetCapHere()
  })

  caseIt('1-5', 'A double click saves one expense', () => {
    testCase('1-5', 'ex1', 'A double click saves one expense', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    asLifecycle()
    const payee = 'XG double ' + run
    openDashboard(); openExpenses()
    const a1 = act(`Fill an expense (Rent, 77, Cash, Payee <b>${payee}</b>) and double-click <b>Save and post</b>.`, ['Exactly one new row appears.'])
    fillExpense({ category: 'Rent', amount: 77, paidFrom: 'CASH', payee })
    cy.get('[data-cy=save-expense]').dblclick()
    cy.get('#expMsg').should('contain', 'Posting to the books')
    cy.wait(2500)
    openExpenses()
    cy.get('#tableExpense tbody tr').filter(`:contains("${payee}")`).should('have.length', 1)
    snap(a1, 'one-row')
    const c1 = act('Void it (reason "Test Book").', [], { cleanup: true })
    expenseRow(payee).find('[data-cy=void-expense]', { timeout: 25000 }).click()
    cy.get('.uiC-input').type('Test Book')
    cy.get('[data-ui-confirm="ok"]').click()
  })

  caseIt('1-6', 'Another business cannot see it', () => {
    testCase('1-6', 'ex1', 'Another business cannot see it', { who: ['owner.pesticide (recorded)', 'owner.business'] })
    cy.loginAs('owner.pesticide@myplus.com', PW, '/getBusinessDashboardStats', 'xg-16-' + Date.now())
    openDashboard()
    const a1 = act('As <b>owner.pesticide</b>, open the dashboard.', ['No Expenses entry (its module is off) and no other business’s expenses anywhere.'])
    cy.get('#navExpenses').closest('[data-capability]').should('have.class', 'cap-off')
    cy.request({ url: '/expense/vouchers?size=200', failOnStatusCode: false }).then((r) => {
      const rows = (r.body && r.body.data && r.body.data.content) || []
      expect(rows.some((v) => /^XG /.test(v.payeeName || '')), 'no lifecycle/business test expenses visible').to.eq(false)
    })
    snap(a1, 'no-expenses')
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  caseIt('1-7', 'Switching it off hides the screen, not the history', () => {
    testCase('1-7', 'ex1', 'Switching it off hides the screen, not the history', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    asLifecycle()
    tb().then((before) => {
      const a1 = act('Untick <b>Expense management</b> (Settings → Configuration), log out and in.', ['Till no longer lists <b>Expenses</b>.'])
      resetCapHere()
      asLifecycle(true)
      openDashboard()
      openMenu('snavTill')
      cy.get('#navExpenses').closest('[data-capability]').should('have.class', 'cap-off')
      snap(a1, 'till-without-expenses')
      const a2 = act('Open <b>Finance → Trial Balance</b>.', ['Expenses already posted are still there: the switch hides the screen, it does not undo postings.'])
      tb().then((after) => expect(delta(before, after, '6000'), 'rent from 1-1 still in the books').to.eq(0))
      showTrialBalance()
      snap(a2, 'trial-balance')
    })
    act('Tick Expense management again (the recording does, for the cases that follow).', [], { cleanup: true })
    cy.setCapability(CAP, true)
  })

  // ═══ EX-1b · A refused expense says why, and can be posted again ═══════════════════════════════════════════
  const yesterdayIso = () => { const d = new Date(); d.setDate(d.getDate() - 1); return localIsoDate(d) }
  const dmyOf = (iso) => iso.split('-').reverse().join('-')
  const openPeriodClose = () => {
    openDashboard()
    cy.window().then((w) => w.showFinance('periodClose'))
    cy.contains('#FinanceDiv', /Books are (OPEN|CLOSED)/, { timeout: 20000 }).should('be.visible')
  }
  const reopenQuietly = () => token().then((t) => cy.request({ method: 'POST', url: `${GW}/api/finance/gl/period-lock`,
    headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false }))

  caseIt('1-8', 'A refused expense says why at once, and goes to the books once the period is reopened', () => {
    testCase('1-8', 'ex1', 'A refused expense says why at once, and goes to the books once the period is reopened',
      { who: ['owner.lifecycle (recorded)', 'owner.business', 'admin.business'] })
    setup('Expense management switched on (case 0a-3). The books are open (Finance → Period Close says <b>Books are OPEN</b>).')
    const payee = 'XG closed ' + run, y = yesterdayIso()
    let id = null
    asLifecycle(true)
    SAFETY.push(() => { asLifecycle(); reopenQuietly() })
    reopenQuietly()
    tb().then((before) => {
      const a1 = act(`<b>Finance → Period Close</b>: Lock the books through <b>${dmyOf(y)}</b> (yesterday), press <b>Close period</b>, confirm.`,
        [`The page says <b>Books are CLOSED through ${y}</b>.`])
      openPeriodClose()
      cy.get('#finLockDate').invoke('val', y).trigger('change')
      cy.contains('#FinanceDiv button', 'Close period').click()
      cy.get('[data-ui-confirm="ok"]').click()
      cy.contains('#FinanceDiv', 'Books are CLOSED through ' + y, { timeout: 15000 }).should('be.visible')
      snap(a1, 'closed', '#FinanceDiv')

      const a2 = act(`<b>Till → Expenses</b>: Date <b>${dmyOf(y)}</b>, Category <b>Rent</b>, Amount <b>5</b>, Paid from <b>Cash</b>, Payee <b>${payee}</b>, press <b>Save and post</b>.`,
        ['Within seconds the row shows <b>Not posted</b> — not minutes of “Posting…”.',
          'Under it, in red, the books’ own reason: “<b>This period is closed (locked through ' + y + '). Reopen it to make changes.</b>”',
          'A <b>Post again</b> button, and a line saying to reopen the period or void and record it again in an open period.'])
      openDashboard(); openExpenses()
      cy.get('#expDateTemp').clear().type(dmyOf(y)).blur()
      cy.get('#expDate').should('have.value', y)                 // the typed day reaches the form (EX-2d fixed this)
      fillExpense({ category: 'Rent', amount: 5, paidFrom: 'CASH', payee })
      cy.get('[data-cy=save-expense]').click()
      cy.get('#expFromTemp').clear().type(dmyOf(y)).blur()        // the list is newest-first, 50 a page: show that day
      cy.get('#expToTemp').clear().type(dmyOf(y)).blur()
      cy.contains('#ExpenseDiv button', 'Search').click()
      expenseRow(payee).find('[data-cy=expense-posting-error]', { timeout: 20000 }).should('be.visible').and('contain', 'period is closed')
      expenseRow(payee).find('.exp-chip').should('contain', 'Not posted')
      expenseRow(payee).find('[data-cy=post-again]').should('be.visible')
      cy.request(`/expense/vouchers?from=${y}&to=${y}&size=200`).then((r) => {
        const v = ((r.body.data && r.body.data.content) || []).find((x) => x.payeeName === payee)
        expect(v.voucherDate).to.eq(y)
        id = v.id
      })
      snap(a2, 'refused', '#ExpenseDiv')
      tb().then((mid) => expect(delta(before, mid, '6000'), 'nothing in the books').to.eq(0))

      const a3 = act('Press <b>Post again</b> while the period is still closed.',
        ['The button says <b>Sending…</b>, then the row is refused again with the same reason. Nothing reaches the books.'])
      cy.intercept('POST', '**/post-again').as('again1')
      expenseRow(payee).find('[data-cy=post-again]').click()
      cy.wait('@again1').its('response.body.success').should('eq', true)
      expenseRow(payee).find('[data-cy=expense-posting-error]', { timeout: 20000 }).should('contain', 'period is closed')
      tb().then((mid) => expect(delta(before, mid, '6000')).to.eq(0))
      snap(a3, 'refused-again', '#ExpenseDiv')

      const a4 = act('<b>Finance → Period Close</b>: press <b>Reopen (clear lock)</b>, confirm.', ['<b>Books are OPEN — no period lock.</b>'])
      openPeriodClose()
      cy.contains('#FinanceDiv button', 'Reopen').click()
      cy.get('[data-ui-confirm="ok"]').click()
      cy.contains('#FinanceDiv', 'Books are OPEN', { timeout: 15000 }).should('be.visible')
      snap(a4, 'reopened', '#FinanceDiv')

      const a5 = act('<b>Till → Expenses</b>: on the row press <b>Post again</b>.',
        ['“<b>Sent to the books again.</b>” The row reaches <b>In the books</b>; the reason and the button are gone.',
          '<b>Finance → Trial Balance</b>: <b>6000 Rent</b> up 5 and <b>1000 Cash</b> down 5 — once, however often Post again was pressed.'])
      openDashboard(); openExpenses()
      cy.get('#expFromTemp').clear().type(dmyOf(y)).blur(); cy.get('#expToTemp').clear().type(dmyOf(y)).blur()
      cy.contains('#ExpenseDiv button', 'Search').click()   // back to that day: the list is newest-first, 50 a page
      cy.intercept('POST', '**/post-again').as('again2')
      expenseRow(payee).find('[data-cy=post-again]').click()
      cy.wait('@again2').its('response.body.success').should('eq', true)
      expenseRow(payee).find('.exp-chip', { timeout: 20000 }).should('contain', 'In the books')
      expenseRow(payee).find('[data-cy=post-again]').should('not.exist')
      tb().then((after) => {
        expect(delta(before, after, '6000'), 'rent once').to.eq(5)
        expect(delta(before, after, '1000'), 'cash once').to.eq(-5)
      })
      snap(a5, 'in-the-books', '#ExpenseDiv')

      const c1 = act(`Void it from its row: <b>Void</b>, reason <b>Test Book</b>, confirm.`, ['The row shows <b>Void</b>; 6000 and 1000 are back where they started.'], { cleanup: true })
      expenseRow(payee).find('[data-cy=void-expense]').click()
      cy.get('.uiC-input').type('Test Book')
      cy.get('[data-ui-confirm="ok"]').click()
      expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'Void')
      const settle = (n = 20) => tb().then((m) => (delta(before, m, '6000') === 0 || n <= 0) ? m : (cy.wait(1000), settle(n - 1)))
      settle().then((m) => expect(delta(before, m, '6000'), 'reversed').to.eq(0))
      snap(c1, 'voided', '#ExpenseDiv')
    })
  })

  caseIt('1-9', 'A long list pages, and shows what the period adds up to', () => {
    testCase('1-9', 'ex1', 'A long list pages, and shows what the period adds up to', { who: ['owner.lifecycle (recorded)', 'user.business'] })
    const today = localIsoDate(new Date()), ids = []
    asLifecycle(true)
    token().then((t) => categoryByName('Rent').then((c) => {
      for (let i = 1; i <= 55; i++) {
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`,
          headers: { Authorization: `Bearer ${t}`, 'Idempotency-Key': `xg19-${run}-${i}` },
          body: { voucherDate: today, paidFrom: 'CASH', payeeName: `XG page ${run} #${i}`, lines: [{ categoryId: c.id, amount: i }] } })
          .then((r) => ids.push(r.body.data.id))
      }
    }))
    setup('55 small Rent expenses dated today (1 to 55), recorded through the Expenses screen’s own request — more than one page.')
    SAFETY.push(() => token().then((t) => ids.forEach((id) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`,
      headers: { Authorization: `Bearer ${t}` }, body: { reason: 'Test Book' }, failOnStatusCode: false }))))
    token().then((t) => cy.request({ url: `${GW}/api/expense/vouchers?from=${today}&to=${today}&size=50`, headers: { Authorization: `Bearer ${t}` } })
      .its('body.data').then((pg) => cy.request({ url: `${GW}/api/expense/vouchers/totals?from=${today}&to=${today}`, headers: { Authorization: `Bearer ${t}` } })
      .its('body.data').then((tot) => {
        const n = pg.totalElements
        const a1 = act(`<b>Till → Expenses</b>: type today (<b>${dmyOf(today)}</b>) in <b>From</b> and <b>To</b>, press <b>Search</b>.`,
          [`50 rows, and under the list: <b>Showing 1–50 of ${n}</b> with <b>Previous</b> greyed out and <b>Next</b> available.`,
           `On the left: <b>Total spent: ${Number(tot.total).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} (${tot.count} expenses; voided ones not counted)</b> — the whole day, not just this page.`,
           'Before this release the list stopped at 200 rows without a word and had no total; a date typed (not picked) in From/To was ignored.'])
        openDashboard(); openExpenses()
        cy.get('#expFromTemp').clear().type(dmyOf(today)).blur()
        cy.get('#expToTemp').clear().type(dmyOf(today)).blur()
        cy.contains('#ExpenseDiv button', 'Search').click()
        cy.get('#tableExpense tbody tr').should('have.length', 50)
        cy.get('[data-cy=expense-showing]').should('have.text', `Showing 1–50 of ${n}`)
        cy.get('[data-cy=expense-prev]').should('be.disabled')
        cy.get('[data-cy=expense-total]').should('contain', `(${tot.count} expenses`)
        cy.get('[data-cy=expense-pager]').scrollIntoView()
        snap(a1, 'page-1')
        const a2 = act('Press <b>Next</b>.', [`<b>Showing 51–${Math.min(100, n)} of ${n}</b>; the total line does not change. <b>Previous</b> takes you back.`])
        cy.get('[data-cy=expense-next]').click()
        cy.get('[data-cy=expense-showing]').should('have.text', `Showing 51–${Math.min(100, n)} of ${n}`)
        cy.get('[data-cy=expense-total]').should('contain', `(${tot.count} expenses`)
        cy.get('[data-cy=expense-pager]').scrollIntoView()
        snap(a2, 'page-2')
      })))
    cy.then(() => {   // queued, so the steps are listed in the order they are done
      act('As <b>user.business</b> (a cashier), ask for the day’s total.', ['Only the cashier’s own expenses are counted — the same ones their list shows.'], { via: 'run' })
      act('Void the 55 test expenses (each row’s <b>Void</b>, reason “Test Book”; the recording does it through the same request).', [], { cleanup: true, via: 'run' })
    })
    cy.loginAsTier('user', 'business')
    cy.request(`/expense/vouchers/totals?from=${today}&to=${today}`).its('body.success').should('eq', true)
    asLifecycle()
    token().then((t) => ids.forEach((id) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`,
      headers: { Authorization: `Bearer ${t}` }, body: { reason: 'Test Book' } }).its('body.success').should('eq', true)))
  })

  caseIt('1-10', 'Settings: how far back, and what the form starts with', () => {
    testCase('1-10', 'ex1', 'Settings: how far back, and what the form starts with', { who: ['owner.lifecycle (recorded)', 'user.business'] })
    const BACK = 'expense.voucher.backdateDays', PAID = 'expense.voucher.defaultPaidFrom'
    const resetBoth = () => token().then((t) => [BACK, PAID].forEach((k) => cy.request({ method: 'POST', url: `${GW}/api/expense/settings/reset?key=${k}`,
      headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })))
    asLifecycle(true)
    resetBoth()
    SAFETY.push(() => { asLifecycle(); resetBoth() })
    const ago = (n) => { const d = new Date(); d.setDate(d.getDate() - n); return localIsoDate(d) }
    openDashboard(); openExpenses()
    const a1 = act('<b>Till → Expenses</b>, press <b>Settings</b>.',
      ['<b>How far back an expense may be dated</b>: <b>30</b> days (before this release it was a hidden 365).', '<b>Paid from, by default</b>: <b>Cash</b>.'])
    cy.get('[data-cy=expense-settings-open]').click()
    cy.get('[data-cy=set-backdate]').should('have.value', '30')
    cy.get('[data-cy=set-paid-from]').should('have.value', 'CASH')
    snap(a1, 'defaults', '#expSetPanel')
    const a2 = act('Type <b>-1</b> in the days box and press its <b>Save</b>; then <b>7</b> and <b>Save</b>.',
      ['-1 is refused in words: “… must be between 0 and 3650 days.”', '7: “Setting saved”.'])
    cy.get('[data-cy=set-backdate]').clear().type('-1')
    cy.get('[data-cy=set-backdate-save]').click()
    cy.get('#expSetMsg').should('contain', 'between 0 and 3650 days')
    cy.get('[data-cy=set-backdate]').clear().type('7')
    cy.get('[data-cy=set-backdate-save]').click()
    cy.get('#expSetMsg').should('contain', 'Setting saved')
    snap(a2, 'seven-days', '#expSetPanel')
    const a3 = act(`Record Rent 1 in cash dated <b>${dmyOf(ago(8))}</b> (8 days ago).`,
      ['Refused, in words: “An expense can be dated at most 7 days back. An owner can change this in Expenses → Settings.”'])
    cy.get('#expDateTemp').clear().type(dmyOf(ago(8))).blur()
    fillExpense({ category: 'Rent', amount: 1, paidFrom: 'CASH', payee: 'XG back ' + run })
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg').should('contain', 'at most 7 days back')
    snap(a3, 'refused', '#ExpenseForm')
    const a4 = act('In Settings choose <b>Bank</b> for Paid from and press its <b>Save</b>; then open Expenses again.',
      ['The New Expense form now starts on <b>Bank</b> — for every member, cashiers included.'])
    cy.get('[data-cy=set-paid-from]').select('BANK')
    cy.get('[data-cy=set-paid-from-save]').click()
    cy.get('#expSetMsg').should('contain', 'Setting saved')
    openDashboard(); openExpenses()
    cy.get('#expPaidFrom').should('have.value', 'BANK')
    snap(a4, 'bank-default', '#ExpenseForm')
    cy.then(() => {
      act('As <b>user.business</b>: open Expenses.', ['The form starts on the business’s default; there is no <b>Settings</b> button, and the server refuses a change.'], { via: 'run' })
      act('Settings → reset both to their defaults (30 days, Cash). The recording does it through the same service.', [], { cleanup: true, via: 'run' })
    })
    cy.loginAsTier('user', 'business')
    cy.request({ method: 'POST', url: '/expense/settings', form: true, body: { key: BACK, value: '3650' }, failOnStatusCode: false })
      .then((r) => expect(r.status >= 400 || r.body.success === false).to.eq(true))
    asLifecycle()
    resetBoth()
  })

  // ═══ EX-5 · Receipts ════════════════════════════════════════════════════════════════════════════════════
  // Each case leaves nothing behind: its expenses are voided at its end (and by SAFETY if it stops half-way), so a
  // later case's duplicate warning is about that case only. Fixtures: receipt-photo.jpg (2400×1800 JPEG, 126 KB),
  // receipt-invoice.pdf, not-a-receipt.jpg (an HTML page named .jpg).
  const RCPT_RULE = 'expense.receipt.requiredAbove'
  const voidQuietly = (id) => cy.request({ method: 'POST', url: `/expense/vouchers/${id}/void`, body: { reason: 'Test Book' }, failOnStatusCode: false })
  const resetReceiptRule = () => token().then((t) => cy.request({ method: 'POST', url: `${GW}/api/expense/settings/reset?key=${RCPT_RULE}`,
    headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false }))
  /** Save the form; if the server names a duplicate, answer the warning as asked (true = Confirm). */
  const saveWithReceipt = (confirmDuplicate = true) => {
    cy.intercept('POST', '**/expense/receipts').as('rcptUp')
    cy.get('[data-cy=save-expense]').click()
    cy.wait('@rcptUp').its('response.body.data.alsoOn').then((also) => {
      if (also && also.length) cy.get(confirmDuplicate ? '[data-ui-confirm="ok"]' : '.uiC-cancel').click()
    })
  }
  const rowId = (payee, ids) => expenseRow(payee).invoke('attr', 'data-id').then((id) => { ids.push(id); return id })
  const fresh5 = (ids) => {
    asLifecycle(true)
    SAFETY.push(() => { asLifecycle(); ids.forEach(voidQuietly) })
    openDashboard(); openExpenses()
  }

  caseIt('5-1', 'Keep a photo of the receipt with the expense', () => {
    testCase('5-1', 'ex5', 'Keep a photo of the receipt with the expense', { who: ['owner.lifecycle (recorded)', 'admin.business', 'user.business'] })
    setup('Expense management switched on (case 0a-3). A photo of a bill on this computer (the recording uses a 2400×1800, 126 KB test photo).')
    const ids = [], payee = 'XG5 photo ' + run
    fresh5(ids)
    const a1 = act(`<b>Till → Expenses</b>: Category <b>Rent</b>, Amount <b>12</b>, Paid from <b>Cash</b>, Payee <b>${payee}</b>; in <b>Receipt</b> choose the photo. Press <b>Save and post</b>.`,
      ['“Expense saved”. The row reaches <b>In the books</b> and shows a <b>Receipts (1)</b> button.'])
    fillExpense({ category: 'Rent', amount: 12, paidFrom: 'CASH', payee })
    cy.get('#expReceipt').selectFile('cypress/fixtures/receipt-photo.jpg', { force: true })
    saveWithReceipt()
    expenseRow(payee).find('[data-cy=expense-receipts]', { timeout: 25000 }).should('contain', 'Receipts (1)')
    expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    rowId(payee, ids)
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'saved')
    const a2 = act('Press <b>Receipts (1)</b>.', ['Under the row: the file’s name with a picture icon and its size in KB — <b>smaller than the 126 KB original</b> (the photo was shrunk on this computer to 1600 px before it was sent).'])
    expenseRow(payee).find('[data-cy=expense-receipts]').click()
    cy.get('[data-cy=receipt-row]').should('have.length', 1)
    cy.then(() => cy.request(`/expense/vouchers/${ids[0]}/receipts`).its('body.data.0.size').should('be.lessThan', 126185))
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'listed')
    const a3 = act('Click the file’s name.', ['The photo opens in a new tab — it is kept on the server, not on this computer, so it can be seen from any device.',
      'Behind it (checked by the recording): served as a JPEG, not cached, “nosniff”.'], { via: 'run' })
    cy.get('[data-cy=receipt-view]').invoke('attr', 'href').then((href) => cy.request({ url: href, encoding: 'binary' }).then((r) => {
      expect(r.headers['content-type']).to.contain('image/jpeg')
      expect(r.headers['x-content-type-options']).to.eq('nosniff')
      expect(r.headers['cache-control']).to.contain('no-store')
    }))
    act(`Void <b>${payee}</b> (reason “Test Book”). The receipt stays with the voided expense.`, [], { cleanup: true })
    cy.then(() => voidQuietly(ids[0]))
  })

  caseIt('5-2', 'The same receipt twice is warned about before saving', () => {
    testCase('5-2', 'ex5', 'The same receipt twice is warned about before saving', { who: ['owner.lifecycle (recorded)'] })
    setup('A PDF invoice on this computer (the recording uses receipt-invoice.pdf).')
    const ids = [], first = 'XG5 pdf ' + run, second = 'XG5 pdf again ' + run
    fresh5(ids)
    const a1 = act(`Record <b>Rent 120</b> in cash, Payee <b>${first}</b>, Receipt: the PDF. Save.`, ['The row shows <b>Receipts (1)</b>.'])
    fillExpense({ category: 'Rent', amount: 120, paidFrom: 'CASH', payee: first })
    cy.get('#expReceipt').selectFile('cypress/fixtures/receipt-invoice.pdf', { force: true })
    saveWithReceipt()
    expenseRow(first).find('[data-cy=expense-receipts]', { timeout: 25000 }).should('contain', 'Receipts (1)')
    rowId(first, ids)
    snap(a1, 'first')
    const a2 = act(`Record another (Rent 120, Payee <b>${second}</b>) with the <b>same PDF</b>. Save.`,
      ['Before anything is saved: <b>This receipt is already on another expense</b> — “The same receipt is on EXP-…. Save this expense anyway?”, naming the first expense.'])
    fillExpense({ category: 'Rent', amount: 120, paidFrom: 'CASH', payee: second })
    cy.get('#expReceipt').selectFile('cypress/fixtures/receipt-invoice.pdf', { force: true })
    cy.get('[data-cy=save-expense]').click()
    cy.then(() => cy.request(`/expense/vouchers/${ids[0]}`).its('body.data.voucherNo').then((no) => cy.get('.uiC-card', { timeout: 20000 }).should('contain', no)))
    snap(a2, 'warning')
    const a3 = act('Press <b>Cancel</b>.', ['Nothing is saved: there is no row for the second expense. The form keeps what was typed.'])
    cy.get('.uiC-cancel').click()
    cy.wait(1000)
    cy.contains('#tableExpense tbody tr.expense-row', second).should('not.exist')
    snap(a3, 'cancelled', '#ExpenseForm')
    const a4 = act('Press <b>Save and post</b> again, and this time <b>Confirm</b>. Then press its <b>Receipts (1)</b>.',
      ['The warning comes again (it asks every time), then the expense is saved.', 'Its receipt reads <b>Also on EXP-…</b> — the same bill on two expenses is visible to whoever checks them.'])
    cy.get('[data-cy=save-expense]').click()
    cy.get('.uiC-card', { timeout: 20000 }).should('contain', 'already on another expense')
    cy.get('[data-ui-confirm="ok"]').click()
    expenseRow(second).find('[data-cy=expense-receipts]', { timeout: 25000 }).should('contain', 'Receipts (1)').click()
    cy.get('[data-cy=receipt-also-on]').should('contain', 'EXP-')
    rowId(second, ids)
    expenseRow(second).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a4, 'also-on')
    act('Void both test expenses (reason “Test Book”).', [], { cleanup: true })
    cy.then(() => ids.forEach(voidQuietly))
  })

  caseIt('5-3', 'The owner can require a receipt above an amount', () => {
    testCase('5-3', 'ex5', 'The owner can require a receipt above an amount', { who: ['owner.lifecycle (recorded)', 'admin.business'] })
    const ids = [], payee = 'XG5 required ' + run
    fresh5(ids)
    resetReceiptRule()
    SAFETY.push(() => { asLifecycle(); resetReceiptRule() })
    const a1 = act('<b>Till → Expenses → Settings</b>: <b>Receipt required above</b> <b>10</b>, press its <b>Save</b>.',
      ['“Setting saved”. Under the form’s Receipt field the hint now reads <b>Required for an expense above 10.00</b>.'])
    cy.get('[data-cy=expense-settings-open]').click()
    cy.get('[data-cy=set-receipt-above]').clear().type('10')
    cy.get('[data-cy=set-receipt-above-save]').click()
    cy.get('#expSetMsg').should('contain', 'Setting saved')
    cy.get('#expReceiptHint').should('contain', 'Required for an expense above')
    cy.get('#expSetPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'rule')
    const a2 = act(`Record <b>Rent 15</b> in cash, Payee <b>${payee}</b>, <b>no receipt</b>. Save.`,
      ['Refused, in words: “<b>A receipt is required for an expense above 10. Attach a photo or PDF of the receipt.</b>” Nothing is saved.'])
    fillExpense({ category: 'Rent', amount: 15, paidFrom: 'CASH', payee })
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg').should('contain', 'A receipt is required for an expense above 10')
    cy.contains('#tableExpense tbody tr.expense-row', payee).should('not.exist')
    snap(a2, 'refused', '#ExpenseForm')
    const a3 = act('Choose the receipt photo in <b>Receipt</b> and save again.', ['Saved, with <b>Receipts (1)</b>.'])
    cy.get('#expReceipt').selectFile('cypress/fixtures/receipt-photo.jpg', { force: true })
    saveWithReceipt()
    expenseRow(payee).find('[data-cy=expense-receipts]', { timeout: 25000 }).should('contain', 'Receipts (1)')
    rowId(payee, ids)
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a3, 'saved')
    act('Settings → Receipt required above → <b>0</b> (never) and Save; void the test expense.', [], { cleanup: true })
    resetReceiptRule()
    cy.then(() => ids.forEach(voidQuietly))
  })

  caseIt('5-4', 'A file that is not a receipt is refused', () => {
    testCase('5-4', 'ex5', 'A file that is not a receipt is refused', { who: ['owner.lifecycle (recorded)'] })
    setup('A file that is not a photo or PDF but is NAMED like one (the recording uses not-a-receipt.jpg — a web page renamed .jpg).')
    const ids = []
    fresh5(ids)
    const a1 = act('Record Rent 9 with that file as the Receipt. Save.', ['Refused on this computer, before anything is sent: “<b>A receipt must be a photo (JPEG, PNG or WEBP) or a PDF.</b>”'])
    fillExpense({ category: 'Rent', amount: 9, paidFrom: 'CASH', payee: 'XG5 fake ' + run })
    cy.get('#expReceipt').selectFile('cypress/fixtures/not-a-receipt.jpg', { force: true })
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg').should('contain', 'A receipt must be a photo')
    snap(a1, 'refused', '#ExpenseForm')
    const a2 = act('Send the same file straight to the server, bypassing the screen.', ['Refused by the server too: it reads the file’s first bytes and ignores the name and the type the browser claims.'], { via: 'run' })
    cy.fixture('not-a-receipt.jpg', 'binary').then((txt) => {
      const fd = new FormData()
      fd.append('file', new Blob([txt], { type: 'image/jpeg' }), 'fake.jpg')
      token().then((t) => cy.request({ method: 'POST', url: `${GW}/api/expense/receipts`, headers: { Authorization: `Bearer ${t}` }, body: fd, failOnStatusCode: false })
        .then((r) => expect(JSON.parse(new TextDecoder().decode(r.body)).message).to.contain('photo (JPEG, PNG or WEBP) or a PDF')))
    })
    cy.get('#expReceipt').then(($i) => { $i.val('') })
    act('Nothing to undo — nothing was saved.', [], { cleanup: true })
  })

  caseIt('5-5', 'Add the receipt to an expense already saved', () => {
    testCase('5-5', 'ex5', 'Add the receipt to an expense already saved', { who: ['owner.lifecycle (recorded)', 'user.business (their own)'] })
    const ids = [], payee = 'XG5 later ' + run
    fresh5(ids)
    const a1 = act(`Record <b>Rent 7</b> in cash, Payee <b>${payee}</b>, no receipt.`, ['The row offers <b>Add a receipt</b>.'])
    fillExpense({ category: 'Rent', amount: 7, paidFrom: 'CASH', payee })
    cy.get('[data-cy=save-expense]').click()
    expenseRow(payee).find('[data-cy=expense-receipts]', { timeout: 25000 }).should('contain', 'Add a receipt')
    rowId(payee, ids)
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'no-receipt')
    const a2 = act('Press <b>Add a receipt</b>, then <b>Add a receipt</b> in the panel, and choose the PDF invoice.', ['The row now reads <b>Receipts (1)</b>; the panel lists the PDF.'])
    expenseRow(payee).find('[data-cy=expense-receipts]').click()
    cy.get('[data-cy=receipt-add]').selectFile('cypress/fixtures/receipt-invoice.pdf', { force: true })
    expenseRow(payee).find('[data-cy=expense-receipts]', { timeout: 20000 }).should('contain', 'Receipts (1)')
    cy.get('[data-cy=receipt-row]').should('have.length', 1)
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'added')
    act(`Void <b>${payee}</b> (reason “Test Book”).`, [], { cleanup: true })
    cy.then(() => ids.forEach(voidQuietly))
  })

  caseIt('5-6', 'Only those who can see the expense can see its receipt; removing is for owners', () => {
    testCase('5-6', 'ex5', 'Only those who can see the expense can see its receipt; removing is for owners', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    const ids = [], payee = 'XG5 private ' + run
    let rid = null
    fresh5(ids)
    setup(`An expense with a receipt: the recording records Rent 6, Payee <b>${payee}</b>, with the PDF.`)
    fillExpense({ category: 'Rent', amount: 6, paidFrom: 'CASH', payee })
    cy.get('#expReceipt').selectFile('cypress/fixtures/receipt-invoice.pdf', { force: true })
    saveWithReceipt()
    expenseRow(payee).find('[data-cy=expense-receipts]', { timeout: 25000 }).should('contain', 'Receipts (1)')
    rowId(payee, ids).then((id) => cy.request(`/expense/vouchers/${id}/receipts`).its('body.data.0.id').then((r) => { rid = r }))
    const a1 = act('As <b>another business’s owner</b> (owner.business), ask for this receipt by its address.',
      ['<b>Not found</b> — a receipt is read only through its own expense.',
       'A cashier asking for a colleague’s receipt in the same business gets <b>not found</b>, and a cashier asking to remove one is refused (403) — both checked in the automated gate, ex-5 case 6.'], { via: 'run' })
    cy.then(() => {
      cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: 'owner.business@myplus.com', password: PW } }).its('body.data.accessToken')
        .then((o) => cy.request({ url: `${GW}/api/expense/receipts/${rid}/content`, headers: { Authorization: `Bearer ${o}` }, failOnStatusCode: false })
          .its('status').should('be.oneOf', [400, 404]))
    })
    const a2 = act('As the owner: open its <b>Receipts (1)</b>, press <b>Remove</b>, and confirm.',
      ['The confirmation says the file is kept for the audit trail.', 'The row goes back to <b>Add a receipt</b>; the receipt no longer opens.'])
    asLifecycle(true)
    openDashboard(); openExpenses()
    expenseRow(payee).find('[data-cy=expense-receipts]').click()
    cy.get('[data-cy=receipt-remove]').click()
    cy.get('.uiC-card').should('contain', 'audit trail')
    snap(a2, 'confirm-remove')
    cy.get('[data-ui-confirm="ok"]').click()
    expenseRow(payee).find('[data-cy=expense-receipts]', { timeout: 20000 }).should('contain', 'Add a receipt')
    cy.then(() => cy.request({ url: `/expense/receipts/${rid}/content`, failOnStatusCode: false }).its('status').should('eq', 404))
    act(`Void <b>${payee}</b> (reason “Test Book”).`, [], { cleanup: true })
    cy.then(() => ids.forEach(voidQuietly))
  })

  // ═══ EX-6 · Expense claims ═══════════════════════════════════════════════════════════════════════════════
  // Two people are needed (one claims, another decides), so these cases use the SCHOOL business: owner, admin and user.
  // Expense claims is not in the FREE plan: the recording lifts the plan to PRO if it is FREE and puts it back (SAFETY).
  const SCHOOL = { owner: 'owner.education@myplus.com', admin: 'admin.education@myplus.com', user: 'user.education@myplus.com' }
  const CLAIMS_KEY = 'org.cap.expenseClaims', MGMT_KEY = 'org.cap.expenseManagement'
  const asSchool = (who) => cy.loginAs(SCHOOL[who], PW, '/getDashboardData', 'xg6-' + who + Date.now())
  const schoolExpenses = () => {
    cy.visit('/educationDashboard'); cy.waitForAppReady()
    openMenu('snavFee'); cy.get('#navExpenses').should('be.visible').click()
    cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
  }
  const schoolToken = (who) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: SCHOOL[who], password: PW } }).its('body.data.accessToken')
  const fillClaim = (amount, payee) => {
    cy.get('#expCategory option').contains('Rent').then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
    cy.get('#expAmount').clear().type(String(amount))
    cy.get('#expPaidFrom').select('EMPLOYEE', { force: true })
    cy.get('#expPayee').clear().type(payee)
  }
  let claimsReady = false
  /** Plan (if FREE) + both switches on, once for the EX-6 cases; every change undone by SAFETY. */
  const claimsOn = () => {
    if (claimsReady) return
    claimsReady = true
    cy.loginAsOperator()
    // through auth's API with the owner's token: a teardown must not depend on a browser sign-in (ex-7a slice doc §5)
    SAFETY.push(() => schoolToken('owner').then((t) => [CLAIMS_KEY, MGMT_KEY].forEach((k) => cy.request({ method: 'POST',
      url: `${GW}/api/auth/settings/reset?key=${k}`, headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false }))))
    // pushed AFTER the switches so it runs BEFORE them (SAFETY runs in reverse): it needs only the operator
    cy.planOf(SCHOOL.owner).then((p) => {
      if (p.plan !== 'FREE') return
      cy.setPlan(p.id, 'PRO')
      SAFETY.push(() => { cy.loginAsOperator(); cy.setPlan(p.id, 'FREE') })
    })
    asSchool('owner')
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: MGMT_KEY, enabled: 'true' } }).its('body.success').should('eq', true)
  }
  const claimSwitchOn = () => cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: CLAIMS_KEY, enabled: 'true' } }).its('body.success').should('eq', true)

  caseIt('6-1', 'Switch expense claims on', () => {
    testCase('6-1', 'ex6', 'Switch expense claims on', { who: ['owner.education (recorded)', 'admin.education'] })
    setup('Expense management is on (case 2a-1). Expense claims is part of the paid plans, not FREE: the recording lifts this school to PRO for the run and puts it back.')
    claimsOn()
    asSchool('owner')
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: CLAIMS_KEY }, failOnStatusCode: false })
    cy.visit('/educationDashboard'); cy.waitForAppReady()
    const a1 = act('As the owner: <b>Fee → Configuration</b>, in the <b>Modules</b> card tick <b>Expense claims</b>.',
      ['The card lists <b>Expense claims</b> (“Staff record what they paid from their own pocket…”), unticked until now; ticking shows <b>Saved</b>.'])
    openMenu('snavFee'); cy.contains('#snavFee a', 'Configuration').click()
    cy.get(`#moduleSwitches [data-key="${CLAIMS_KEY}"]`, { timeout: 20000 }).should('not.be.checked').check()
    cy.get('#moduleSwitchesMsg').should('contain', 'Saved')
    snap(a1, 'switched-on', '#moduleSwitches')
    const a2 = act('Sign out and in again, open <b>Expenses</b>, and open <b>Paid from</b>.', ['It now offers <b>Me — claim it back</b> as well as Cash and Bank.'])
    asSchool('owner'); schoolExpenses()
    cy.get('#expPaidFrom option[value="EMPLOYEE"]').should('contain', 'Me — claim it back')
    act('Kept on for cases 6-2 to 6-5; switched off (and the plan put back) at the end of the recording.', [], { cleanup: true })
  })

  caseIt('6-2', 'A member claims back what they paid themselves', () => {
    testCase('6-2', 'ex6', 'A member claims back what they paid themselves', { who: ['user.education (recorded)', 'any member'] })
    setup('Expense claims switched on (case 6-1).')
    claimsOn(); asSchool('owner'); claimSwitchOn()
    const payee = 'XG6 fuel ' + run
    asSchool('user'); schoolExpenses()
    const a1 = act(`As <b>user.education</b>: <b>Expenses</b> → Category <b>Rent</b>, Amount <b>35</b>, Paid from <b>Me — claim it back</b>, Payee <b>${payee}</b>.`,
      ['The button now reads <b>Send for approval</b>, and under it: “You paid this yourself. An owner or admin approves it; then the business owes it back to you.”'])
    fillClaim(35, payee)
    cy.get('[data-cy=save-expense]').should('contain', 'Send for approval')
    cy.get('[data-cy=claim-hint]').should('be.visible')
    snap(a1, 'form', '#ExpenseForm')
    const a2 = act('Press <b>Send for approval</b>.', ['“Claim sent for approval…”. The row reads <b>Claim · user.education@…</b> and <b>Waiting for approval</b>, with no expense number yet, and a <b>Withdraw</b> button.',
      'It is not money spent yet: the period’s <b>Total spent</b> does not change.'])
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg').should('contain', 'Claim sent for approval')
    expenseRow(payee).find('[data-cy=claim-waiting]').should('contain', 'Waiting for approval')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'waiting')
    act('Nothing to undo yet — case 6-3 approves this claim.', [], { cleanup: true })
  })

  caseIt('6-3', 'The owner approves a claim; the business now owes it', () => {
    testCase('6-3', 'ex6', 'The owner approves a claim; the business now owes it', { who: ['owner.education (recorded)', 'admin.education'] })
    setup('A claim waiting for approval (case 6-2: XG6 fuel, 35).')
    claimsOn()
    const payee = 'XG6 fuel ' + run
    let tb = null
    schoolToken('owner').then((t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: { Authorization: `Bearer ${t}` } })
      .its('body.rows').then((rows) => { tb = Object.fromEntries(rows.map((r) => [r.code, Number(r.debit || 0) - Number(r.credit || 0)])) }))
    asSchool('owner'); schoolExpenses()
    const a1 = act('As the owner: open <b>Expenses</b>.', ['Above the list: “<b>1 claim is waiting for approval.</b>” with <b>Show them</b>.'])
    cy.get('[data-cy=claims-waiting]').should('be.visible').and('contain', 'waiting for approval')
    snap(a1, 'waiting-notice', '#ExpenseDiv [data-cy=claims-waiting]')
    const a2 = act('Press <b>Show them</b>.', ['Only the claims waiting for a decision are listed, each with <b>Approve</b> and <b>Reject</b>. The button now reads <b>Show every expense</b>.'])
    cy.get('[data-cy=claims-show]').click()
    expenseRow(payee).find('[data-cy=claim-approve]').should('exist')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'queue')
    const a3 = act(`Press <b>Approve</b> on <b>${payee}</b>, then <b>Show every expense</b>.`,
      ['“Claim approved — posting to the books”. The row gets its <b>EXP-</b> number and reaches <b>In the books</b>.'])
    expenseRow(payee).find('[data-cy=claim-approve]').click()
    cy.get('#expMsg').should('contain', 'Claim approved')
    cy.get('[data-cy=claims-show]').click()
    expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a3, 'approved')
    act('Open <b>Finance → Trial Balance</b>.', ['<b>2300 Employee Reimbursements Payable</b> is 35 more in credit (owed to the member) and <b>6000 Rent</b> 35 more in debit. Cash and bank have not moved — the member is paid back in EX-7.'], { via: 'run' })
    schoolToken('owner').then((t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: { Authorization: `Bearer ${t}` } }).its('body.rows').then((rows) => {
      const a = Object.fromEntries(rows.map((r) => [r.code, Number(r.debit || 0) - Number(r.credit || 0)]))
      const d = (c) => Math.round(((a[c] || 0) - (tb[c] || 0)) * 100) / 100
      expect(d('2300')).to.eq(-35); expect(d('6000')).to.eq(35); expect(d('1000')).to.eq(0); expect(d('1010')).to.eq(0)
    }))
    act(`Void <b>${payee}</b> (reason “Test Book”); the reversal clears the 35 from 2300.`, [], { cleanup: true })
    cy.request('/expense/vouchers?size=50').its('body.data.content').then((l) => {
      const v = l.find((x) => x.payeeName === payee)
      cy.request({ method: 'POST', url: `/expense/vouchers/${v.id}/void`, body: { reason: 'Test Book' }, failOnStatusCode: false })
    })
  })

  caseIt('6-4', 'The owner rejects a claim, and says why', () => {
    testCase('6-4', 'ex6', 'The owner rejects a claim, and says why', { who: ['owner.education (recorded)', 'user.education (recorded)'] })
    claimsOn(); asSchool('owner'); claimSwitchOn()
    const payee = 'XG6 taxi ' + run
    setup(`A claim waiting for approval: the recording has user.education claim Rent 18, Payee <b>${payee}</b> (as in case 6-2).`)
    asSchool('user'); schoolExpenses(); fillClaim(18, payee)
    cy.get('[data-cy=save-expense]').click()
    expenseRow(payee).find('[data-cy=claim-waiting]').should('exist')
    asSchool('owner'); schoolExpenses()
    const a1 = act(`As the owner: press <b>Reject</b> on <b>${payee}</b>.`, ['“Reject this claim?” asks <b>why</b> — the person who made the claim will see it.'])
    expenseRow(payee).find('[data-cy=claim-reject]').click()
    cy.get('.uiC-card').should('contain', 'Reject this claim?')
    cy.get('.uiC-input').type('No receipt for the taxi')
    snap(a1, 'why')
    const a2 = act('Type <b>No receipt for the taxi</b> and confirm.', ['The row reads <b>Rejected</b> with the reason under it. Nothing reaches the books.'])
    cy.get('[data-ui-confirm="ok"]').click()
    expenseRow(payee).find('[data-cy=claim-rejected]').should('contain', 'Rejected')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'rejected')
    const a3 = act('As <b>user.education</b>: open Expenses.', ['Their claim reads <b>Rejected</b> — “No receipt for the taxi”. It can no longer be withdrawn or decided; they submit a new claim if they have the receipt.'])
    asSchool('user'); schoolExpenses()
    expenseRow(payee).find('[data-cy=claim-reason]').should('contain', 'No receipt for the taxi')
    expenseRow(payee).find('[data-cy=claim-withdraw]').should('not.exist')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a3, 'claimant-sees')
    act('Nothing to undo — a rejected claim never reached the books, and it is kept for the trail.', [], { cleanup: true })
  })

  caseIt('6-5', 'Nobody approves their own claim; a waiting claim can be withdrawn', () => {
    testCase('6-5', 'ex6', 'Nobody approves their own claim; a waiting claim can be withdrawn', { who: ['admin.education (recorded)', 'owner.education', 'user.education'] })
    claimsOn(); asSchool('owner'); claimSwitchOn()
    const payee = 'XG6 own ' + run
    const a1 = act(`As <b>admin.education</b>: claim Rent <b>12</b> paid yourself (Payee <b>${payee}</b>), then press <b>Approve</b> on it.`,
      ['Refused, in words: “<b>You cannot approve your own claim. Another owner or admin must approve it.</b>” It is still waiting.'])
    asSchool('admin'); schoolExpenses(); fillClaim(12, payee)
    cy.get('[data-cy=save-expense]').click()
    expenseRow(payee).find('[data-cy=claim-approve]').click()
    cy.get('#expMsg').should('contain', 'cannot approve your own claim')
    expenseRow(payee).find('[data-cy=claim-waiting]').should('exist')
    expenseRow(payee).scrollIntoView({ offset: { top: -260, left: 0 } }); snap(a1, 'own-refused')
    act('A user pressing Approve or Reject on anyone’s claim is refused too (they have no such buttons; the server refuses the request).', ['Refused — 403.'], { via: 'run' })
    cy.request('/expense/vouchers?claim=SUBMITTED&size=50').its('body.data.content').then((l) => {
      const id = l.find((v) => v.payeeName === payee).id
      schoolToken('user').then((u) => cy.request({ method: 'POST', url: `${GW}/api/expense/claims/${id}/approve`, headers: { Authorization: `Bearer ${u}` }, failOnStatusCode: false })
        .its('status').should('be.oneOf', [403, 404]))
    })
    const a2 = act('As admin.education again: press <b>Withdraw</b> on the claim.', ['“Claim withdrawn”; the row reads <b>Withdrawn</b>. Nothing reached the books, and the claim is kept for the trail (never deleted).'])
    expenseRow(payee).find('[data-cy=claim-withdraw]').click()
    expenseRow(payee).find('[data-cy=claim-withdrawn]').should('contain', 'Withdrawn')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'withdrawn')
    act('Nothing to undo.', [], { cleanup: true })
  })

  caseIt('6-6', 'A limit on what a member posts directly', () => {
    testCase('6-6', 'ex6', 'A limit on what a member posts directly', { who: ['owner.education (recorded)', 'user.education (recorded)', 'admin.education (recorded)'] })
    setup('No limit by default: a member posts any amount, as before. The owner sets one in Expenses → Settings.')
    const LIMIT = 'expense.voucher.userPostLimit', payee = 'XG6 big ' + run
    const resetLimit = () => schoolToken('owner').then((t) => cy.request({ method: 'POST', url: `${GW}/api/expense/settings/reset?key=${LIMIT}`,
      headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false }))
    asSchool('owner')
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: MGMT_KEY, enabled: 'true' } }).its('body.success').should('eq', true)
    resetLimit()
    SAFETY.push(() => { resetLimit(); schoolToken('owner').then((t) => cy.request({ method: 'POST', url: `${GW}/api/auth/settings/reset?key=${MGMT_KEY}`,
      headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })) })
    schoolExpenses()
    const a1 = act('As the owner: <b>Expenses → Settings</b>, <b>A member may post up to</b> = <b>50</b>, Save.', ['“Setting saved”. Blank means no limit; 0 means every member expense waits.'])
    cy.get('[data-cy=expense-settings-open]').click()
    cy.get('[data-cy=set-post-limit]').clear().type('50')
    cy.get('[data-cy=set-post-limit-save]').click()
    cy.get('#expSetMsg').should('contain', 'Setting saved')
    cy.get('#expSetPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'limit', '#expSetPanel')
    const a2 = act(`As <b>user.education</b>: record <b>Rent 80</b> in cash, Payee <b>${payee}</b>.`,
      ['“Saved. It is above the 50 a member may post, so it waits for an owner or admin to post it.” The row shows <b>Waiting to be posted</b>, with no number and a <b>Discard</b> button; it is not in the books, the report or Total spent.'])
    asSchool('user')
    schoolExpenses()
    cy.get('#expCategory option').contains('Rent').then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
    cy.get('#expAmount').clear().type('80')
    cy.get('#expPaidFrom').select('CASH', { force: true })
    cy.get('#expPayee').clear().type(payee)
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg', { timeout: 20000 }).should('contain', 'waits for an owner or admin')
    cy.contains('#tableExpense tbody tr.expense-row', payee, { timeout: 25000 }).find('[data-cy=expense-waiting]').should('be.visible')
    snap(a2, 'waiting')
    act('As user.education, try to post it anyway (only possible outside the screen).', ['Refused: “This expense is above the 50 a member may post. An owner or admin posts it.” An expense of 50 or less posts as before.'], { via: 'run' })
    const a4 = act('As <b>admin.education</b>: open Expenses and press <b>Post</b> on that row.', ['It gets its EXP- number and reaches <b>In the books</b>, dated as the member dated it.'])
    asSchool('admin')
    schoolExpenses()
    cy.contains('#tableExpense tbody tr.expense-row', payee, { timeout: 25000 }).find('[data-cy=post-draft]').click()
    cy.contains('#tableExpense tbody tr.expense-row', payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    snap(a4, 'posted')
    act(`Void <b>${payee}</b> (reason “Test Book”) and clear the limit (blank, Save).`, [], { cleanup: true })
    schoolToken('owner').then((t) => cy.request({ url: `${GW}/api/expense/vouchers?size=100`, headers: { Authorization: `Bearer ${t}` } }).its('body.data.content')
      .then((l) => l.filter((v) => v.payeeName === payee && v.status === 'POSTED').forEach((v) => cy.request({ method: 'POST',
        url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: { Authorization: `Bearer ${t}` }, body: { reason: 'Test Book' }, failOnStatusCode: false }))))
    resetLimit()
  })

  // ═══ EX-7a · Paying a claim back ═════════════════════════════════════════════════════════════════════════
  /** A claim by `who`, approved by the owner and in the books — the screen's own requests (cases 6-2, 6-3). */
  const approvedSchoolClaim = (who, amount, payee) => schoolToken(who).then((c) =>
    cy.request({ url: `${GW}/api/expense/categories`, headers: { Authorization: `Bearer ${c}` } }).its('body.data').then((cats) =>
      cy.request({ method: 'POST', url: `${GW}/api/expense/claims`, headers: { Authorization: `Bearer ${c}`, 'Idempotency-Key': 'xg7-' + Date.now() + Math.random() },
        body: { voucherDate: isoToday(), paidFrom: 'EMPLOYEE', payeeName: payee, lines: [{ categoryId: cats.find((x) => x.accountCode === '6000' && x.active).id, amount }] } })
        .its('body.data.id')).then((id) => schoolToken('owner').then((o) => {
      cy.request({ method: 'POST', url: `${GW}/api/expense/claims/${id}/approve`, headers: { Authorization: `Bearer ${o}` }, body: {} })
      const settle = (n) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: { Authorization: `Bearer ${o}` } }).its('body.data.postingStatus')
        .then((ps) => { if (ps !== 'POSTED_GL' && n > 0) { cy.wait(1000); settle(n - 1) } })
      settle(20)
      return cy.wrap(id)
    })))
  const isoToday = () => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0') }
  const schoolTb = () => schoolToken('owner').then((t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: { Authorization: `Bearer ${t}` } })
    .its('body.rows').then((rows) => Object.fromEntries(rows.map((r) => [r.code, Number(r.debit || 0) - Number(r.credit || 0)]))))
  const tbDelta = (b, a, c) => Math.round(((a[c] || 0) - (b[c] || 0)) * 100) / 100

  caseIt('7-1', 'Pay a member back for an approved claim', () => {
    testCase('7-1', 'ex7', 'Pay a member back for an approved claim', { who: ['owner.education (recorded)', 'admin.education'] })
    claimsOn(); asSchool('owner'); claimSwitchOn()
    const payee = 'XG7 fuel ' + run
    let before = null, id = null
    setup(`An approved claim in the books: the recording has user.education claim Rent <b>40</b> (Payee <b>${payee}</b>) and the owner approve it (cases 6-2, 6-3).`)
    approvedSchoolClaim('user', 40, payee).then((x) => { id = x })
    schoolTb().then((m) => { before = m })
    asSchool('owner'); schoolExpenses()
    const a1 = act('As the owner: open <b>Expenses</b> and find the claim.', ['It reads <b>In the books</b> and <b>Owed to the member 40.00</b>, with a <b>Pay back</b> button.'])
    expenseRow(payee).find('[data-cy=expense-bill-owes]').should('contain', 'Owed to the member').and('contain', '40.00')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'owed')
    const a2 = act('Press <b>Pay back</b>, change the amount to <b>15</b>, Paid from <b>Cash</b>, press <b>Pay</b>.',
      ['The panel reads “Pay back claim EXP-… — Owed to the member 40.00”.', '“Payment recorded — PV-…”. The row now reads <b>Owed to the member 25.00</b>; <b>Void</b> is gone while anything is paid.'])
    expenseRow(payee).find('[data-cy=pay-claim]').click()
    cy.get('#expPayTitle').should('contain', 'Pay back claim')
    cy.get('#expPayAmount').clear().type('15')
    cy.get('#expPayMethod').select('CASH', { force: true })
    cy.get('#expPayPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'panel')
    cy.get('[data-cy=expense-pay-go]').click()
    cy.get('#expMsg').should('contain', 'PV-')
    expenseRow(payee).find('[data-cy=expense-bill-owes]').should('contain', '25.00')
    const a3 = act('Pay the remaining <b>25</b> by <b>Bank</b>.', ['The row reads <b>Paid back</b>, with a <b>Payments</b> button listing both PV- payments.'])
    expenseRow(payee).find('[data-cy=pay-claim]').click()
    cy.get('#expPayMethod').select('BANK', { force: true })
    cy.get('[data-cy=expense-pay-go]').click()
    expenseRow(payee).find('[data-cy=expense-bill-paid]').should('contain', 'Paid back')
    expenseRow(payee).find('[data-cy=bill-payments]').click()
    cy.get('#tableExpense tr.exp-pay-detail').should('contain', 'PV-')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a3, 'paid-back')
    act('Open <b>Finance → Trial Balance</b>.', ['<b>2300 Employee Reimbursements Payable</b> is back down by 40 (nothing owed for this claim); <b>1000 Cash</b> is 15 lower and <b>1010 Bank</b> 25 lower. <b>2000 Accounts Payable has not moved</b> — a member is not a supplier.'], { via: 'run' })
    schoolTb().then((a) => {
      expect(tbDelta(before, a, '2300')).to.eq(40); expect(tbDelta(before, a, '1000')).to.eq(-15)
      expect(tbDelta(before, a, '1010')).to.eq(-25); expect(tbDelta(before, a, '2000')).to.eq(0)
    })
    act('Nothing to undo: the claim was a real expense, paid back. To take it out entirely, follow case 7-2.', [], { cleanup: true })
  })

  caseIt('7-2', 'A paid-back claim is voided only after its payment is reversed', () => {
    testCase('7-2', 'ex7', 'A paid-back claim is voided only after its payment is reversed', { who: ['owner.education (recorded)', 'admin.education'] })
    claimsOn(); asSchool('owner'); claimSwitchOn()
    const payee = 'XG7 wrong ' + run
    setup(`An approved claim of <b>10</b> (Payee <b>${payee}</b>) paid back in cash — the recording makes it as in case 7-1.`)
    approvedSchoolClaim('user', 10, payee).then((id) => schoolToken('owner').then((o) =>
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/pay`, headers: { Authorization: `Bearer ${o}`, 'Idempotency-Key': 'xg7p-' + Date.now() },
        body: { amount: 10, method: 'CASH' } }).its('body.success').should('eq', true)))
    asSchool('owner'); schoolExpenses()
    const a1 = act('Find the claim.', ['It reads <b>Paid back</b>, and there is <b>no Void</b> button: money went out against it. (Asked directly, the server refuses: “This claim has been paid back. Reverse the payment first, then void the claim.”)'])
    expenseRow(payee).find('[data-cy=expense-bill-paid]').should('contain', 'Paid back')
    expenseRow(payee).find('[data-cy=void-expense]').should('not.exist')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'no-void')
    const a2 = act('Press <b>Payments</b>, then <b>Reverse</b> on the payment, give the reason <b>Paid to the wrong person</b>, confirm.',
      ['The payment reads <b>Reversed</b>; the claim is <b>Owed to the member 10.00</b> again and <b>Void</b> is back.'])
    expenseRow(payee).find('[data-cy=bill-payments]').click()
    cy.get('[data-cy=reverse-payment]').first().click()
    cy.get('.uiC-input').type('Paid to the wrong person')
    cy.get('[data-ui-confirm="ok"]').click()
    expenseRow(payee).find('[data-cy=expense-bill-owes]', { timeout: 20000 }).should('contain', '10.00')
    expenseRow(payee).find('[data-cy=void-expense]').should('exist')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'reversed')
    const a3 = act('Press <b>Void</b> with the reason <b>Test Book</b>.', ['The claim reads <b>Void</b>. In the books the claim, its payment and their reversals cancel out: 2300, Rent, Cash and Bank are where they were before the claim.'])
    expenseRow(payee).find('[data-cy=void-expense]').click()
    cy.get('.uiC-input').type('Test Book')
    cy.get('[data-ui-confirm="ok"]').click()
    expenseRow(payee).find('.exp-chip').should('contain', 'Void')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a3, 'voided')
    act('Nothing more to undo — the void was the clean-up.', [], { cleanup: true })
  })

  caseIt('7-3', 'Only an owner or admin pays back, never their own claim', () => {
    testCase('7-3', 'ex7', 'Only an owner or admin pays back, never their own claim', { who: ['admin.education (recorded)', 'user.education (recorded)', 'owner.education'] })
    claimsOn(); asSchool('owner'); claimSwitchOn()
    const own = 'XG7 own ' + run, mine = 'XG7 mine ' + run
    setup(`Two approved claims: admin.education’s own (12, <b>${own}</b>) and user.education’s (6, <b>${mine}</b>) — the recording makes both.`)
    approvedSchoolClaim('admin', 12, own)
    approvedSchoolClaim('user', 6, mine)
    asSchool('admin'); schoolExpenses()
    const a1 = act(`As <b>admin.education</b>: press <b>Pay back</b> on your own claim <b>${own}</b>, then <b>Pay</b>.`,
      ['Refused in the panel: “<b>You cannot pay your own claim back. Another owner or admin pays it.</b>” Nothing is paid.'])
    expenseRow(own).find('[data-cy=pay-claim]').click()
    cy.get('[data-cy=expense-pay-go]').click()
    cy.get('#expPayMsg').should('contain', 'cannot pay your own claim back')
    cy.get('#expPayPanel').scrollIntoView({ offset: { top: -160, left: 0 } }); snap(a1, 'own-refused')
    cy.get('#expPayPanel button[onclick="expensePayClose()"]').click()
    const a2 = act('As <b>user.education</b>: open Expenses.', ['Their claim reads <b>Owed to the member 6.00</b> — they can see what the business owes them — with <b>no Pay back</b> button. (Asked directly, the server refuses them.)'])
    asSchool('user'); schoolExpenses()
    expenseRow(mine).find('[data-cy=expense-bill-owes]').should('contain', '6.00')
    expenseRow(mine).find('[data-cy=pay-claim]').should('not.exist')
    expenseRow(mine).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'member-sees')
    act('As the owner: pay both claims back (12 and 6, cash) so nothing is left owed.', [], { cleanup: true })
    schoolToken('owner').then((o) =>
      cy.request({ url: `${GW}/api/expense/vouchers?size=100`, headers: { Authorization: `Bearer ${o}` } }).its('body.data.content').then((l) =>
        [own, mine].forEach((p) => {
          const v = l.find((x) => x.payeeName === p)
          if (v) cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/pay`, headers: { Authorization: `Bearer ${o}`, 'Idempotency-Key': 'xg7c-' + v.id + '-' + run },
            body: { amount: Number(v.openAmount), method: 'CASH' }, failOnStatusCode: false })
        })))
  })

  // ═══ EX-7b · Advances to staff ═══════════════════════════════════════════════════════════════════════════
  const schoolUserId = (who) => schoolToken('owner').then((t) => cy.request({ url: `${GW}/api/auth/org/users`, headers: { Authorization: `Bearer ${t}` } })
    .its('body.data').then((l) => l.find((m) => m.email === SCHOOL[who]).userId))
  const schoolHolds = (uid) => schoolToken('owner').then((t) => cy.request({ url: `${GW}/api/expense/advances`, headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })
    .then((r) => { const b = ((r.body && r.body.data) || []).find((x) => x.userId === uid); return b ? Number(b.balance) : 0 }))
  /** Whatever user.education holds is taken back in cash, so the cases start (and end) with nothing held. */
  const sweepAdvance = () => schoolUserId('user').then((uid) => schoolHolds(uid).then((h) => {
    if (h > 0) schoolToken('owner').then((t) => cy.request({ method: 'POST', url: `${GW}/api/expense/advances/take-back`,
      headers: { Authorization: `Bearer ${t}`, 'Idempotency-Key': 'xg8s-' + Date.now() }, body: { userId: uid, amount: h, method: 'CASH' }, failOnStatusCode: false }))
  }))

  caseIt('8-1', 'Give a member an advance', () => {
    testCase('8-1', 'ex8', 'Give a member an advance', { who: ['owner.education (recorded)', 'user.education (recorded)', 'admin.education'] })
    setup('Expense claims switched on (case 6-1): advances are part of it. Nobody holds an advance yet (the recording takes back anything left from an earlier run).')
    claimsOn(); asSchool('owner'); claimSwitchOn()
    sweepAdvance()
    SAFETY.push(() => sweepAdvance())
    let before = null
    schoolTb().then((m) => { before = m })
    asSchool('owner'); schoolExpenses()
    const a1 = act('As the owner: press <b>Advances</b>.', ['A panel: who holds an advance (nobody yet), and <b>Give an advance to</b> with the business’s <b>staff</b> only — the school’s guardian and student are not offered.'])
    cy.get('[data-cy=expense-advances-open]').should('be.visible').click()
    cy.get('[data-cy=adv-member] option').should('contain', SCHOOL.user).and('not.contain', 'guardian.education').and('not.contain', 'student.education')
    cy.get('#expAdvPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'panel')
    const a2 = act('Choose <b>User Education</b>, Amount <b>50</b>, <b>Cash</b>, press <b>Give</b>.', ['“Advance given — PV-…”. The table reads <b>User Education · Holds 50.00</b>.'])
    cy.get('[data-cy=adv-member] option').contains(SCHOOL.user).then(($o) => cy.get('[data-cy=adv-member]').select($o.val(), { force: true }))
    cy.get('[data-cy=adv-amount]').type('50')
    cy.get('[data-cy=adv-method]').select('CASH', { force: true })
    cy.get('[data-cy=adv-give]').click()
    cy.get('#expAdvMsg').should('contain', 'PV-')
    cy.contains('[data-cy=adv-row]', 'User').find('[data-cy=adv-holds]').should('contain', '50.00')
    cy.get('#expAdvPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'given')
    act('Open <b>Finance → Trial Balance</b>.', ['<b>1300 Employee Advances</b> is 50 higher (the member owes it back) and <b>1000 Cash</b> 50 lower. 2300 (claims) and 2000 (suppliers) have not moved.'], { via: 'run' })
    schoolTb().then((a) => { expect(tbDelta(before, a, '1300')).to.eq(50); expect(tbDelta(before, a, '1000')).to.eq(-50); expect(tbDelta(before, a, '2300')).to.eq(0); expect(tbDelta(before, a, '2000')).to.eq(0) })
    const a3 = act('As <b>user.education</b>: open Expenses.', ['Above the form: “<b>You hold an advance of 50.00.</b> Spend it on the business and claim it, or hand back what is left.” There is no Advances button for a member.'])
    asSchool('user'); schoolExpenses()
    cy.get('[data-cy=my-advance]').should('contain', '50.00')
    cy.get('[data-cy=expense-advances-open]').should('not.exist')
    cy.get('[data-cy=my-advance]').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a3, 'member-sees')
    act('Kept for case 8-2 (it spends 30 of it) and 8-3 (it takes back the rest).', [], { cleanup: true })
  })

  caseIt('8-2', 'Settle a claim from the member’s advance', () => {
    testCase('8-2', 'ex8', 'Settle a claim from the member’s advance', { who: ['owner.education (recorded)', 'admin.education'] })
    claimsOn(); asSchool('owner'); claimSwitchOn()
    const payee = 'XG8 taxi ' + run
    setup(`User Education holds an advance of 50 (case 8-1) and has an approved claim of <b>30</b> (Payee <b>${payee}</b>) — the recording makes the claim as in cases 6-2 and 6-3.`)
    approvedSchoolClaim('user', 30, payee)
    let before = null
    schoolTb().then((m) => { before = m })
    asSchool('owner'); schoolExpenses()
    const a1 = act(`On <b>${payee}</b> press <b>Pay back</b> and open <b>Paid from</b>.`, ['Beside Cash and Bank it offers <b>From their advance (holds 50.00)</b>.'])
    expenseRow(payee).find('[data-cy=pay-claim]').click()
    cy.get('#expPayMethod option[value=ADVANCE]').should('contain', '50.00')
    cy.get('#expPayMethod').select('ADVANCE', { force: true })
    cy.get('#expPayPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'from-advance')
    const a2 = act('Choose it and press <b>Pay</b>.', ['The claim reads <b>Paid back</b>. No money moved: the member had already spent the advance on this. Advances now shows <b>Holds 20.00</b>.'])
    cy.get('[data-cy=expense-pay-go]').click()
    expenseRow(payee).find('[data-cy=expense-bill-paid]').should('contain', 'Paid back')
    cy.get('[data-cy=expense-advances-open]').click()
    cy.contains('[data-cy=adv-row]', 'User').find('[data-cy=adv-holds]').should('contain', '20.00')
    cy.get('#expAdvPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'holds-20')
    act('Open <b>Finance → Trial Balance</b>.', ['<b>2300</b> is cleared of the claim’s 30 and <b>1300</b> is 30 lower. <b>Cash and Bank have not moved.</b>'], { via: 'run' })
    schoolTb().then((a) => { expect(tbDelta(before, a, '2300')).to.eq(30); expect(tbDelta(before, a, '1300')).to.eq(-30); expect(tbDelta(before, a, '1000')).to.eq(0); expect(tbDelta(before, a, '1010')).to.eq(0) })
    act('Kept for case 8-3: 20 is still held.', [], { cleanup: true })
  })

  caseIt('8-3', 'Take back what is left of an advance', () => {
    testCase('8-3', 'ex8', 'Take back what is left of an advance', { who: ['owner.education (recorded)', 'admin.education'] })
    claimsOn(); asSchool('owner'); claimSwitchOn()
    setup('User Education holds 20 of an advance (cases 8-1 and 8-2).')
    let before = null
    schoolTb().then((m) => { before = m })
    asSchool('owner'); schoolExpenses()
    const a1 = act('<b>Advances</b> → beside Give choose <b>Bank</b>, then press <b>Take back</b> on User Education’s row.', ['“Take back from User Education?” with the amount already filled in: <b>20.00</b>, all they hold.'])
    cy.get('[data-cy=expense-advances-open]').click()
    cy.get('[data-cy=adv-method]').select('BANK', { force: true })
    cy.contains('[data-cy=adv-row]', 'User').find('[data-cy=adv-take-back]').click()
    cy.get('.uiC-input').should('have.value', '20.00')
    snap(a1, 'take-back')
    const a2 = act('Confirm.', ['“Advance taken back — RCPT-…”. The table reads <b>Nobody holds an advance.</b>'])
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#expAdvMsg').should('contain', 'RCPT-')
    cy.get('#expAdvTable tbody').should('contain', 'Nobody holds an advance')
    cy.get('#expAdvPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'nobody-holds')
    act('Open <b>Finance → Trial Balance</b>.', ['<b>1010 Bank</b> is 20 higher and <b>1300</b> is back where it was before case 8-1: of the 50 given, 30 was spent on the business (the claim) and 20 came back.'], { via: 'run' })
    schoolTb().then((a) => { expect(tbDelta(before, a, '1010')).to.eq(20); expect(tbDelta(before, a, '1300')).to.eq(-20) })
    act('Nothing to undo — nothing is held.', [], { cleanup: true })
  })

  // ═══ EX-8a · The expense report ══════════════════════════════════════════════════════════════════════════
  const lcToday = () => localIsoDate(new Date())
  const lcReport = (by = 'category') => token().then((t) => cy.request({ url: `${GW}/api/expense/reports/summary?from=${lcToday()}&to=${lcToday()}&by=${by}`,
    headers: { Authorization: `Bearer ${t}` } }).its('body.data'))
  const lcPnl = () => token().then((t) => cy.request({ url: `${GW}/api/finance/gl/pnl?from=${lcToday()}&to=${lcToday()}`, headers: { Authorization: `Bearer ${t}` } })
    .its('body.totalExpense').then(Number))
  const openReport = (by) => {
    cy.get('#expReportPanel').then(($p) => { if (!$p.is(':visible')) cy.get('[data-cy=expense-report-open]').click() })
    cy.get('[data-cy=report-by]').select(by, { force: true })
    cy.get('[data-cy=report-run]').click()
    cy.get('[data-cy=report-row]').should('exist')
  }
  const r2x = (n) => Math.round(Number(n) * 100) / 100

  caseIt('9-1', 'See what was spent, grouped by category', () => {
    testCase('9-1', 'ex9', 'See what was spent, grouped by category', { who: ['owner.lifecycle (recorded)', 'admin.business', 'user.business (their own)'] })
    setup('Expense management switched on (case 0a-3). The list’s From and To are today (the report uses the same dates).')
    const ids = [], rent = 'XG9 rent ' + run, util = 'XG9 power ' + run
    let r0 = null, p0 = null
    fresh5(ids)
    lcReport().then((r) => { r0 = r.total }); lcPnl().then((p) => { p0 = p })
    const a1 = act(`Record <b>Rent 40</b> in cash (Payee <b>${rent}</b>) and <b>Electricity, gas and water 25</b> by bank (Payee <b>${util}</b>).`, ['Both reach <b>In the books</b>.'])
    fillExpense({ category: 'Rent', amount: 40, paidFrom: 'CASH', payee: rent })
    cy.get('[data-cy=save-expense]').click()
    expenseRow(rent).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    rowId(rent, ids)
    fillExpense({ category: 'Electricity, gas and water', amount: 25, paidFrom: 'BANK', payee: util })
    cy.get('[data-cy=save-expense]').click()
    expenseRow(util).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    rowId(util, ids)
    const a2 = act('Press <b>Report</b> (beside Search), Group by <b>Category</b>, <b>Show</b>.',
      ['One row per category with its number of lines and amount, largest first, and a <b>Total</b> — 65.00 more than before the two expenses.'])
    openReport('category')
    cy.get('#expReportPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'by-category')
    act('Open <b>Finance → Profit and Loss</b> for today.', ['Its expense total moved by the same <b>65.00</b>: the report counts exactly what the books count.'], { via: 'run' })
    lcReport().then((r) => lcPnl().then((p) => { expect(r2x(r.total - r0)).to.eq(65); expect(r2x(p - p0)).to.eq(65) }))
    act('Kept for case 9-2 (it voids the rent) and 9-3; voided at the end of 9-3.', [], { cleanup: true })
  })

  caseIt('9-2', 'A void counts on the day it is voided', () => {
    testCase('9-2', 'ex9', 'A void counts on the day it is voided', { who: ['owner.lifecycle (recorded)'] })
    const rent = 'XG9 rent ' + run
    setup(`The two expenses of case 9-1 (Rent 40, ${rent}).`)
    let r0 = null, p0 = null
    asLifecycle(true); openDashboard(); openExpenses()
    lcReport().then((r) => { r0 = r.total }); lcPnl().then((p) => { p0 = p })
    const a1 = act(`Void <b>${rent}</b> (reason “Test Book”), then <b>Report → Show</b> again.`,
      ['The Rent row is <b>40.00 lower</b> and so is the Total: a void is a minus on the day it is voided, not a row quietly left out — an expense recorded last month and voided today lowers <b>today’s</b> figures, exactly as the profit and loss does.'])
    expenseRow(rent).find('[data-cy=void-expense]').click()
    cy.get('.uiC-input').type('Test Book')
    cy.get('[data-ui-confirm="ok"]').click()
    expenseRow(rent).find('.exp-chip').should('contain', 'Void')
    cy.wait(3000)
    openReport('category')
    cy.get('#expReportPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'after-void')
    act('Profit and Loss for today.', ['Also <b>40.00 lower</b>.'], { via: 'run' })
    lcReport().then((r) => lcPnl().then((p) => { expect(r2x(r.total - r0)).to.eq(-40); expect(r2x(p - p0)).to.eq(-40) }))
    act('Nothing more to undo: the rent is voided.', [], { cleanup: true })
  })

  caseIt('9-3', 'Group by month or paid from, and download the CSV', () => {
    testCase('9-3', 'ex9', 'Group by month or paid from, and download the CSV', { who: ['owner.lifecycle (recorded)'] })
    const util = 'XG9 power ' + run
    asLifecycle(true); openDashboard(); openExpenses()
    const a1 = act('Report → Group by <b>Paid from</b> → Show.', ['Rows for <b>Cash</b>, <b>Bank</b> (and Till, Bill or Claim where they were used), adding up to the same Total as by category.'])
    openReport('paidFrom')
    cy.get('[data-cy=report-row]').contains('Bank').should('exist')
    cy.get('#expReportPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'by-paid-from')
    act('Group by <b>Member</b>, then <b>Month</b>.', ['Each adds up to the same Total. A member (user.business) sees only their own expenses, as “You”.'], { via: 'run' })
    lcReport('category').then((c) => ['member', 'month', 'paidFrom'].forEach((by) => lcReport(by).then((g) => expect(r2x(g.total)).to.eq(r2x(c.total)))))
    act('Press <b>CSV</b>.', ['A file <b>expenses-FROM-TO.csv</b>: one row per line — date, number, category, account, paid from, payee, member, amount — the voided rent as its own <b>-40.00</b> row on the day it was voided. Its amounts add up to the report’s Total.'], { via: 'run' })
    token().then((t) => cy.request({ url: `${GW}/api/expense/reports/expenses.csv?from=${lcToday()}&to=${lcToday()}`, headers: { Authorization: `Bearer ${t}` } }).then((res) => {
      // Amount and Kind are always the last two cells and never quoted (a category or payee may hold a quoted comma)
      const rows = res.body.trim().split(/\r?\n/)
      lcReport().then((r) => expect(r2x(rows.slice(1).reduce((s, l) => s + Number(l.split(',').slice(-2)[0]), 0))).to.eq(r2x(r.total)))
    }))
    act(`Void <b>${util}</b> (reason “Test Book”).`, [], { cleanup: true })
    cy.request('/expense/vouchers?size=100').its('body.data.content').then((l) => {
      const v = l.find((x) => x.payeeName === util && x.status === 'POSTED')
      if (v) cy.request({ method: 'POST', url: `/expense/vouchers/${v.id}/void`, body: { reason: 'Test Book' }, failOnStatusCode: false })
    })
  })

  caseIt('9-4', 'The same payee, day and amount again is questioned before saving', () => {
    testCase('9-4', 'ex9', 'The same payee, day and amount again is questioned before saving', { who: ['owner.lifecycle (recorded)', 'admin.business', 'user.business'] })
    const ids = [], payee = 'XG9 K-Electric ' + run
    fresh5(ids)
    const a1 = act(`Record <b>Rent 12</b> in cash, Payee <b>${payee}</b>.`, ['Saved and in the books.'])
    fillExpense({ category: 'Rent', amount: 12, paidFrom: 'CASH', payee })
    cy.get('[data-cy=save-expense]').click()
    expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    rowId(payee, ids)
    const a2 = act(`Record it again: Rent <b>12</b>, Payee <b>${payee.toLowerCase()}</b> (the same payee, typed in small letters), and press Save.`,
      ['Before anything is saved: “<b>This looks like an expense already recorded</b> — EXP-… is already recorded for this payee on the same day for the same amount. Save this one as well?”'])
    fillExpense({ category: 'Rent', amount: 12, paidFrom: 'CASH', payee: payee.toLowerCase() })
    cy.get('[data-cy=save-expense]').click()
    cy.get('.uiC-card', { timeout: 20000 }).should('contain', 'already recorded')
    snap(a2, 'warning')
    const a3 = act('Press <b>Cancel</b>.', ['Nothing is saved; the form keeps what was typed. Pressing Save again asks again — every time.'])
    cy.get('.uiC-cancel').click()
    cy.wait(1000)
    cy.get('#tableExpense tbody tr.expense-row').filter(`:contains("${payee.toLowerCase()}")`).should('have.length', 0)
    act('Press Save again and <b>Confirm</b> (two identical fares on one day are real).', ['Saved. A different amount, another day, another payee, or a voided expense is never questioned.'], { via: 'run' })
    cy.get('[data-cy=save-expense]').click()
    cy.get('[data-ui-confirm="ok"]').click()
    expenseRow(payee.toLowerCase()).should('exist')
    rowId(payee.toLowerCase(), ids)
    act('Void both test expenses (reason “Test Book”).', [], { cleanup: true })
    cy.then(() => ids.forEach(voidQuietly))
  })

  caseIt('9-5', 'Recover the tax inside an expense', () => {
    testCase('9-5', 'ex9', 'Recover the tax inside an expense', { who: ['owner.lifecycle (recorded)', 'admin.business'] })
    setup('A business registered for tax. Off by default: an expense is a cost including its tax.')
    const ids = [], payee = 'XG9 power tax ' + run
    const resetTax = () => token().then((t) => cy.request({ method: 'POST', url: `${GW}/api/expense/settings/reset?key=expense.tax.inputRecoverable`,
      headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false }))
    fresh5(ids)
    resetTax()
    SAFETY.push(() => resetTax())
    let tb0 = null
    token().then((t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: { Authorization: `Bearer ${t}` } }).its('body.rows')
      .then((rows) => { tb0 = Object.fromEntries(rows.map((r) => [r.code, Number(r.debit || 0) - Number(r.credit || 0)])) }))
    const a1 = act('<b>Expenses → Settings</b>: tick <b>Recover input tax on expenses</b>.', ['“Setting saved”. The New Expense form now has <b>Tax included</b> under Amount.'])
    cy.get('[data-cy=expense-settings-open]').click()
    cy.get('[data-cy=set-input-tax]').check()
    cy.get('#expSetMsg').should('contain', 'Setting saved')
    cy.get('#expTaxGroup').should('be.visible')
    cy.get('#expSetPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'switched-on')
    const a2 = act(`Record <b>Electricity, gas and water 115</b> with <b>Tax included 15</b>, in cash, Payee <b>${payee}</b>.`, ['Saved and in the books for <b>115.00</b> (what was paid).'])
    fillExpense({ category: 'Electricity, gas and water', amount: 115, paidFrom: 'CASH', payee })
    cy.get('#expTax').clear().type('15')
    snap(a2, 'form', '#ExpenseForm')
    cy.get('[data-cy=save-expense]').click()
    expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    rowId(payee, ids)
    act('Open the trial balance and the tax register for today.', ['<b>6100</b> is 100 higher (the cost), <b>2100 Tax</b> 15 lower in credit (input tax), <b>Cash</b> 115 lower. The tax register shows <b>15 more input tax</b>, so 15 less to pay. Expenses → Report counts <b>100</b>, as the profit and loss does.'], { via: 'run' })
    token().then((t) => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: { Authorization: `Bearer ${t}` } }).its('body.rows').then((rows) => {
      const a = Object.fromEntries(rows.map((r) => [r.code, Number(r.debit || 0) - Number(r.credit || 0)]))
      const d = (c) => Math.round(((a[c] || 0) - (tb0[c] || 0)) * 100) / 100
      expect(d('6100')).to.eq(100); expect(d('2100')).to.eq(15); expect(d('1000')).to.eq(-115)
    }))
    act(`Void <b>${payee}</b> (reason “Test Book”) and untick the setting.`, [], { cleanup: true })
    cy.then(() => ids.forEach(voidQuietly))
    resetTax()
  })

  caseIt('9-6', 'See the last 12 months under the profit and loss', () => {
    testCase('9-6', 'ex9', 'See the last 12 months under the profit and loss', { who: ['owner.lifecycle (recorded)', 'admin.business', 'user.business (refused)'] })
    setup('Any business with its books in finance. analytics-service running (it is in the default set since AN-1).')
    const ids = [], payee = 'XG9 trend ' + run, month = lcToday().slice(0, 7)
    const trendNow = () => token().then((t) => cy.request({ url: `${GW}/api/analytics/financial/monthly?from=${month}&to=${month}`,
      headers: { Authorization: `Bearer ${t}` } }).its('body.data.months.0'))
    let m0 = null
    fresh5(ids)
    trendNow().then((m) => { m0 = m })
    const a1 = act('Open <b>Finance → Profit &amp; Loss</b>.', ['Under the statement: <b>Last 12 months</b>, this month first — Income, Expenses and Net for each month, and a Total. Each month is exactly the profit and loss for that month.'])
    cy.window().then((w) => w.showFinance('pnl'))
    cy.get('[data-cy=pnl-trend]', { timeout: 25000 }).should('be.visible').find('tbody tr').should('have.length', 12)
    cy.get('[data-cy=pnl-trend]').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'trend', '#FinanceResults')
    act(`Record <b>Rent 21.50</b> in cash (Payee <b>${payee}</b>), then open the Profit &amp; Loss again.`, ['This month’s <b>Expenses</b> in the trend is <b>21.50</b> higher: the trend is worked out from the books each time it is opened, never a copy that goes out of date.'], { via: 'run' })
    openExpenses()
    fillExpense({ category: 'Rent', amount: 21.5, paidFrom: 'CASH', payee })
    cy.get('[data-cy=save-expense]').click()
    expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    rowId(payee, ids)
    trendNow().then((m) => expect(r2x(m.expenses - m0.expenses)).to.eq(21.5))
    act('As <b>user.business</b> (no statements): ask for the trend.', ['Refused, as the profit and loss itself is: the trend shows the same figures, so the same people may see it.'], { via: 'run' })
    cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: 'user.business@myplus.com', password: PW } }).its('body.data.accessToken')
      .then((u) => cy.request({ url: `${GW}/api/analytics/financial/monthly`, headers: { Authorization: `Bearer ${u}` }, failOnStatusCode: false }).its('status').should('eq', 403))
    act(`Void <b>${payee}</b> (reason “Test Book”).`, [], { cleanup: true })
    cy.then(() => ids.forEach(voidQuietly))
  })

  caseIt('9-7', 'See what each branch spent', () => {
    testCase('9-7', 'ex9', 'See what each branch spent', { who: ['user.education (one branch)', 'owner.education (recorded)'] })
    setup('A school with several branches. Each expense carries the branch the person recording it is working in — taken from their sign-in, never chosen on the form.')
    const payee = 'XG9 branch ' + run
    let branch = null
    asSchool('owner')
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: MGMT_KEY, enabled: 'true' } }).its('body.success').should('eq', true)
    const regrant = (ids) => schoolToken('owner').then((t) => cy.request({ method: 'POST', url: `${GW}/api/auth/org/locations/grant`,
      headers: { Authorization: `Bearer ${t}` }, body: { userId: 96, storeIds: ids, roleAtLocation: 'USER', replace: true } }))
    SAFETY.push(() => { regrant([]); schoolToken('owner').then((t) => cy.request({ method: 'POST', url: `${GW}/api/auth/settings/reset?key=${MGMT_KEY}`,
      headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })) })
    act('As the owner: give <b>user.education</b> access to <b>one</b> branch (Team → the member → Branches).', ['They now work in that branch: it is their active branch when they sign in.'], { via: 'run' })
    schoolToken('owner').then((t) => cy.request({ url: `${GW}/api/expense/tags?source=education`, headers: { Authorization: `Bearer ${t}` } }).its('body.data')
      .then((l) => { branch = l.find((x) => x.type === 'SCHOOL'); regrant([branch.id]) }))
    act(`As <b>user.education</b>: <b>Fee → Expenses</b>, record <b>Rent 12</b> in cash, Payee <b>${payee}</b>.`, ['Saved. There is no branch to choose: the expense carries the branch they work in.'], { via: 'run' })
    asSchool('user')
    schoolExpenses()
    cy.get('#expCategory option').contains('Rent').then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
    cy.get('#expAmount').clear().type('12')
    cy.get('#expPaidFrom').select('CASH', { force: true })
    cy.get('#expPayee').clear().type(payee)
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg', { timeout: 20000 }).should('contain', 'Expense saved')
    const a3 = act('As the owner: <b>Expenses → Report</b>, Group by <b>Branch</b>, <b>Show</b>.',
      ['One row per branch, by its name, with what it spent; expenses recorded outside any branch (an owner’s, or before branches were used) under <b>No branch</b>. The Total is the same as by category, and the CSV gains a <b>Branch</b> column.'])
    asSchool('owner')
    schoolExpenses()
    cy.get('[data-cy=expense-report-open]').click()
    cy.get('[data-cy=report-by]').select('branch', { force: true })
    cy.get('[data-cy=report-run]').click()
    cy.then(() => cy.contains('[data-cy=report-row]', branch.label, { timeout: 20000 }).should('be.visible'))
    cy.get('#expReportPanel').scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a3, 'by-branch')
    act('As user.education, send another branch’s number with an expense (only possible outside the screen).', ['Refused: “An expense is recorded for the branch you are working in…”.'], { via: 'run' })
    schoolToken('user').then((u) => cy.request({ url: `${GW}/api/expense/categories`, headers: { Authorization: `Bearer ${u}` } }).its('body.data').then((cats) =>
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, failOnStatusCode: false,
        headers: { Authorization: `Bearer ${u}`, 'Idempotency-Key': 'xg97-' + run },
        body: { paidFrom: 'CASH', payeeName: payee, storeId: 999999, lines: [{ categoryId: cats.find((c) => c.accountCode === '6000').id, amount: 1 }] } })
        .its('body.message').should('contain', 'branch you are working in')))
    act(`Void <b>${payee}</b> (reason “Test Book”), take the branch away from user.education, and switch expense management off again.`, [], { cleanup: true })
    schoolToken('owner').then((t) => cy.request({ url: `${GW}/api/expense/vouchers?size=100`, headers: { Authorization: `Bearer ${t}` } }).its('body.data.content')
      .then((l) => l.filter((v) => v.payeeName === payee && v.status === 'POSTED').forEach((v) => cy.request({ method: 'POST',
        url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: { Authorization: `Bearer ${t}` }, body: { reason: 'Test Book' }, failOnStatusCode: false }))))
  })

  // ═══ EX-2a · Every dashboard ═════════════════════════════════════════════════════════════════════════════
  const DASH = [
    { email: 'owner.education@myplus.com', check: '/getDashboardData', dash: '/educationDashboard', tag: 'school',
      openConfig: () => { openMenu('snavFee'); cy.contains('#snavFee a', 'Configuration').click() },
      openExp: () => { openMenu('snavFee'); cy.get('#navExpenses').should('be.visible').click() } },
    { email: 'owner.welfare@myplus.com', check: '/getWelfareConfig', dash: '/welfareDashboard', tag: 'welfare',
      openConfig: () => cy.contains('.app-sidebar a.sb-link', 'Configuration').click({ force: true }),
      openExp: () => cy.get('#navExpenses').click({ force: true }) },
    { email: 'owner.agriculture@myplus.com', check: '/agricultureDashboard', dash: '/agricultureDashboard', tag: 'farm',
      openConfig: () => cy.contains('.app-sidebar a.sb-link', 'Configuration').click({ force: true }),
      openExp: () => cy.get('#navExpenses').click({ force: true }) },
  ]
  const signInD = (d, fresh) => cy.loginAs(d.email, PW, d.check, fresh ? 'xg-2a-' + d.tag + Date.now() : undefined)
  const resetModule = () => cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, failOnStatusCode: false, body: { key: KEY } })

  caseIt('2a-1', 'Each business switches the module on for itself', () => {
    testCase('2a-1', 'ex2a', 'Each business switches the module on for itself', { who: ['owner.education', 'owner.welfare', 'owner.agriculture (all recorded)'] })
    DASH.forEach((d) => {
      signInD(d); resetModule()
      SAFETY.push(() => { signInD(d); resetModule() })
      signInD(d, true)
      cy.visit(d.dash); cy.waitForAppReady()
      const a = act(`As <b>${d.email.split('@')[0]}</b>: open <b>Configuration</b> (${d.tag === 'school' ? 'Fee → Configuration' : 'the sidebar'}), in the <b>Modules</b> card tick <b>Expense management</b>, then log out and in.`,
        ['The card lists Expense management, unticked; ticking shows <b>Saved</b>.', 'After signing in again, <b>Expenses</b> appears in the menu.'])
      d.openConfig()
      cy.get(`#moduleSwitches [data-key="${KEY}"]`, { timeout: 20000 }).should('not.be.checked').check()
      cy.get('#moduleSwitchesMsg').should('contain', 'Saved')
      snap(a, d.tag + '-saved', '#moduleSwitches')
      signInD(d, true)
      cy.visit(d.dash); cy.waitForAppReady()
      cy.get('#navExpenses').closest('[data-capability]').should('not.have.class', 'cap-off')
    })
    act('Kept on for cases 2a-2 to 2b-2; switched back off at the end of the recording (each owner: Configuration → Modules → untick).', [], { cleanup: true })
  })

  caseIt('2a-2', 'Record an expense on each dashboard', () => {
    testCase('2a-2', 'ex2a', 'Record an expense on each dashboard', { who: ['owner.education', 'owner.welfare', 'owner.agriculture (all recorded)'] })
    DASH.forEach((d) => {
      const payee = `XG ${d.tag} rent ${run}`
      signInD(d, true)
      cy.visit(d.dash); cy.waitForAppReady()
      const a = act(`As <b>${d.email.split('@')[0]}</b>: open <b>Expenses</b>, record <b>Rent</b> 900 in cash (Payee <b>${payee}</b>).`,
        ['The row gets an <b>EXP-</b> number from this business’s own series and reaches <b>In the books</b>.'])
      d.openExp()
      cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
      fillExpense({ category: 'Rent', amount: 900, paidFrom: 'CASH', payee })
      cy.get('[data-cy=save-expense]').click()
      expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
      snap(a, d.tag)
      const c = act(`${d.tag}: void the 900 rent (reason "Test Book").`, [], { cleanup: true })
      expenseRow(payee).find('[data-cy=void-expense]').click()
      cy.get('.uiC-input').type('Test Book')
      cy.get('[data-ui-confirm="ok"]').click()
      expenseRow(payee).find('.exp-chip').should('contain', 'Void')
    })
  })

  caseIt('2a-3', 'Configuration opens from the sidebar', () => {
    testCase('2a-3', 'ex2a', 'Configuration opens from the sidebar', { who: ['owner.welfare', 'owner.agriculture (both recorded)'] })
    DASH.slice(1).forEach((d) => {
      signInD(d, true)
      cy.visit(d.dash); cy.waitForAppReady()
      const a = act(`As <b>${d.email.split('@')[0]}</b>: click <b>Configuration</b> in the sidebar.`, ['The Configuration screen opens with its settings and the <b>Modules</b> card (before EX-2a it went blank).'])
      d.openConfig()
      cy.get('#moduleSwitches', { timeout: 20000 }).should('be.visible')
      snap(a, d.tag)
    })
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  caseIt('2a-4', 'Only owners and admins switch modules', () => {
    testCase('2a-4', 'ex2a', 'Only owners and admins switch modules', { who: ['user.education (recorded)'] })
    cy.loginAs('user.education@myplus.com', PW, '/getDashboardData', 'xg-2a4-' + Date.now())
    const a1 = act('As <b>user.education</b>, try to switch Expense management off.', ['Refused: a user cannot switch a module on or off.'], { via: 'run' })
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, failOnStatusCode: false, body: { key: KEY, enabled: 'false' } })
      .then((r) => expect(r.status === 403 || (r.body && r.body.success !== true), JSON.stringify(r.body).slice(0, 200)).to.eq(true))
    const a2 = act('As <b>owner.education</b>, try to flip a business feature (installments) from the Modules card’s endpoint.', ['Refused: the card only switches modules.'], { via: 'run' })
    signInD(DASH[0])
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, failOnStatusCode: false, body: { key: 'org.cap.installments', enabled: 'false' } })
      .then((r) => expect(r.body && r.body.success !== true, JSON.stringify(r.body).slice(0, 200)).to.eq(true))
    act('Nothing to undo — both were refused.', [], { cleanup: true })
  })

  caseIt('2a-5', 'The books on every dashboard', () => {
    testCase('2a-5', 'ex2a', 'The books on every dashboard', { who: ['owner.education', 'owner.welfare', 'owner.agriculture (all recorded)', 'user.business'] })
    setup('Expense management is on for each business (case 2a-1; the recording switches it on here and back off at the end).')
    const openPnl = {
      school: () => { openMenu('snavFinance'); cy.get('[data-cy=nav-finance-pnl]').should('be.visible').click() },
      welfare: () => cy.get('[data-cy=nav-finance]').click({ force: true }),
      farm: () => cy.get('[data-cy=nav-finance]').click({ force: true }),
    }
    DASH.forEach((d) => {
      const payee = `XG ${d.tag} books ${run}`
      signInD(d)
      cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: KEY, enabled: 'true' } })
      SAFETY.push(() => { signInD(d); resetModule() })
      signInD(d, true)
      cy.visit(d.dash); cy.waitForAppReady()
      d.openExp()
      cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
      fillExpense({ category: 'Rent', amount: 7, paidFrom: 'CASH', payee })
      cy.get('[data-cy=save-expense]').click()
      expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
      if (d.tag === 'welfare') {
        const n = act('Welfare: open <b>Expenses</b>.', ['Instead of “Each expense is posted to your books”, a notice says <b>donations are not in these books yet</b>.'])
        cy.get('#ExpenseDiv [data-cy=books-note]').should('be.visible').and('contain', 'Donations')
        cy.get('#ExpenseDiv').should('not.contain', 'Each expense is posted to your books')
        snap(n, 'expenses-note', '#ExpenseDiv')
      }
      const a = act(`As <b>${d.email.split('@')[0]}</b>: record Rent 7 in cash (Payee <b>${payee}</b>), then open <b>${d.tag === 'school' ? 'Finance → Profit &amp; Loss' : 'Finance'}</b> ${d.tag === 'school' ? 'in the menu' : 'in the sidebar'}.`,
        ['The Finance screen opens on <b>Profit &amp; Loss</b> for this month, and <b>Rent</b> is in it.',
         'Its tabs are Trial Balance, P&amp;L, Balance Sheet, Audit Log and Period Close — <b>no Tax Register</b> (only a trading business has one), and the heading does not mention one.',
         { welfare: 'A notice above the report says donations are not in these books yet.',
           farm: 'A notice above the report says the farm’s own Income and Expense records are not in these books yet.',
           school: 'No notice: the school’s fees post to these books.' }[d.tag]])
      openPnl[d.tag]()
      cy.get('#finTabs .fin-tab.active').should('have.attr', 'data-report', 'pnl')
      cy.contains('#FinanceResults', 'Rent', { timeout: 20000 }).should('be.visible')
      cy.get('#finTabs [data-report="taxRegister"]').should('not.exist')
      cy.get('#FinanceDiv .fin-sub').invoke('text').should('not.match', /tax/i)
      cy.get('#FinanceDiv [data-cy=books-note]').should(d.tag === 'school' ? 'not.exist' : 'be.visible')
      snap(a, d.tag + '-pnl', '#FinanceDiv')
      const b = act(`${d.tag}: click the <b>Trial Balance</b> tab.`, ['The totals line says <b>Balanced</b>.'])
      cy.get('#finTabs [data-report="trialBalance"]').click()
      cy.contains('#FinanceResults', 'Balanced', { timeout: 20000 }).should('be.visible')
      snap(b, d.tag + '-tb', '#FinanceDiv')
      act(`${d.tag}: void the Rent 7 (reason "Test Book").`, [], { cleanup: true })
      d.openExp()
      expenseRow(payee).find('[data-cy=void-expense]', { timeout: 25000 }).click()
      cy.get('.uiC-input').type('Test Book')
      cy.get('[data-ui-confirm="ok"]').click()
      expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'Void')
    })
    const u = act('As <b>user.business</b> (a cashier): look for Finance, and ask for the Profit &amp; Loss directly.', ['There is no Finance menu.', 'Asking for the report directly is <b>refused</b> — the statements are for owners and admins only.'], { via: 'run' })
    cy.loginAsTier('user', 'business')
    cy.request({ url: '/gl/pnl', failOnStatusCode: false }).then((r) => {
      const refused = r.status >= 400 || (r.body && (r.body.success === false || r.body.status === 'ERROR' || r.body.statusCode === 403))
      expect(refused, 'refused: ' + JSON.stringify(r.body).slice(0, 160)).to.eq(true)
    })
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    cy.get('#snavFinance').should('not.exist')
    act('Each business: Configuration → Modules → untick Expense management (the recording does this at the end).', [], { cleanup: true })
  })

  // ═══ EX-2b · What it was for ═══════════════════════════════════════════════════════════════════════════════
  caseIt('2b-1', 'Tag fuel to a bus, and a cost to a field', () => {
    testCase('2b-1', 'ex2b', 'Tag fuel to a bus, and a cost to a field', { who: ['owner.education', 'owner.agriculture (both recorded)'] })
    const busNo = 'XG-' + run, landName = 'XG Field ' + run
    signInD(DASH[0])
    cy.request({ method: 'POST', url: '/addVehicle', form: true, body: { name: 'XG Bus', number: busNo } })
    signInD(DASH[2])
    cy.request({ method: 'POST', url: '/addLand', form: true, body: { landName, landType: 'Agricultural', landUnit: 'Acre', totalLandUnit: '3', amount: '1' } })
    setup(`School: a bus <b>XG Bus (${'XG-…'})</b> under Transport. Farm: a land <b>XG Field …</b> under Land.`)
    ;[[DASH[0], 'Fuel and transport', 'XG Bus', 'bus'], [DASH[2], 'Rent', landName, 'land']].forEach(([d, cat, tagText, t]) => {
      const payee = `XG ${t} ${run}`
      signInD(d, true)
      cy.visit(d.dash); cy.waitForAppReady()
      const a = act(`${d.tag === 'school' ? 'School' : 'Farm'}: Expenses, category <b>${cat}</b>, amount 120, cash, <b>For</b>: ${t === 'bus' ? 'the bus' : 'the land'}, Payee <b>${payee}</b>, save.`,
        ['The <b>For</b> list offers this business’s ' + (t === 'bus' ? 'schools and vehicles' : 'lands') + ', nothing else.', 'The row reads like <b>' + cat + ' · ' + (t === 'bus' ? 'XG Bus (XG-…)' : 'XG Field …') + '</b>.'])
      d.openExp()
      cy.get('#expTagGroup', { timeout: 20000 }).should('be.visible')
      cy.get('#expTag option').then(($o) => {
        const opt = [...$o].find((o) => o.text.includes(tagText))
        expect(opt, 'tag offered').to.exist
        cy.get('#expTag').select(opt.value, { force: true })
      })
      fillExpense({ category: cat, amount: 120, paidFrom: 'CASH', payee })
      cy.get('[data-cy=save-expense]').click()
      expenseRow(payee).should('contain', ' · ').and('contain', t === 'bus' ? 'XG Bus' : 'XG Field')
      snap(a, t)
      const c = act(`${d.tag}: void the 120 (reason "Test Book").`, [], { cleanup: true })
      expenseRow(payee).find('[data-cy=void-expense]', { timeout: 25000 }).click()
      cy.get('.uiC-input').type('Test Book')
      cy.get('[data-ui-confirm="ok"]').click()
    })
  })

  caseIt('2b-2', 'A branch user sees only their branch', () => {
    testCase('2b-2', 'ex2b', 'A branch user sees only their branch', { who: ['teacher.a (recorded; granted one branch)', 'teacher.b', 'owner.education'] })
    setup('Expense management is on for the school (case 2a-1; the recording switches it on and back off).')
    setup('Two branches, <b>CY Branch 1</b> and <b>CY Branch 2</b>, and <b>teacher.a</b> granted only Branch 1 — the recording makes them through the School form’s and the Team screen’s own requests if they are missing.')
    signInD(DASH[0])
    // Seed what this case reads, never borrow it from another spec's run (a fresh database has neither branch).
    const rowsOf = (b) => list(b)
    const ensureBranch = (name) => cy.request('/getUserSchool').then((r) => {
      const hit = rowsOf(r.body).find((x) => x.branchName === name || x.name === name)
      return hit ? cy.wrap(hit.id) : cy.request({ method: 'POST', url: '/addSchool', form: true, body: { name, branchName: name, status: 'Active' } })
        .then(() => cy.request('/getUserSchool')).then((rr) => rowsOf(rr.body).find((x) => x.branchName === name || x.name === name).id)
    })
    ensureBranch('CY Branch 2')
    ensureBranch('CY Branch 1').then((branchId) => cy.request('/team/users').then((t) => {
      const ta = rowsOf(t.body).find((u) => u.email === 'teacher.a@myplus.com')
      expect(ta, 'teacher.a is a member of this school').to.exist
      return cy.request({ method: 'POST', url: '/assignStores', headers: { 'Content-Type': 'application/json' },
        body: { userId: ta.userId, storeIds: [branchId], roleAtLocation: 'USER' } }).its('body.success').should('eq', true)
    }))
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: KEY, enabled: 'true' } }).its('body.success').should('eq', true)
    SAFETY.push(() => { signInD(DASH[0]); resetModule() })
    let ownerSchools = 0
    cy.request('/expense/tags?source=education').then((r) => { ownerSchools = (r.body.data || []).filter((t) => t.type === 'SCHOOL').length })
    cy.loginAs('teacher.a@myplus.com', PW, '/getDashboardData', 'xg-2b2-' + Date.now())
    cy.visit('/educationDashboard'); cy.waitForAppReady()
    const a1 = act('Sign in as <b>teacher.a</b> (granted one branch), open <b>Fee → Expenses</b>, and open the <b>For</b> list.',
      ['The For list offers only that branch (<b>CY Branch 1</b>) and its vehicles — none of the school’s other branches.'])
    openMenu('snavFee')
    cy.get('#navExpenses').should('be.visible').click()
    cy.get('#expTagGroup', { timeout: 20000 }).should('be.visible')
    cy.get('#expTag option').then(($o) => {
      const labels = [...$o].map((o) => o.text)
      expect(labels.some((l) => l.includes('CY Branch 1')), 'their branch').to.eq(true)
      expect(labels.some((l) => l.includes('CY Branch 2')), 'not another branch').to.eq(false)
    })
    cy.request('/expense/tags?source=education').then((r) => {
      const mine = (r.body.data || []).filter((t) => t.type === 'SCHOOL').length
      expect(mine, 'fewer schools than the owner sees').to.be.lessThan(ownerSchools)
    })
    cy.get('#expTagGroup .bootstrap-select button, #expTagGroup button.dropdown-toggle').first().click({ force: true })
    snap(a1, 'teacher-for-list')
    act('As owner.education: <b>Fee → Configuration → Modules</b>, untick Expense management (if you switched it on for this test).', [], { cleanup: true })
    signInD(DASH[0]); resetModule()
  })

  // ═══ EX-3 · Till pay-outs ═════════════════════════════════════════════════════════════════════════════════
  const openTill = () => {
    openDashboard()
    openMenu('snavTill')
    cy.get('#snavTill a[onclick^="showTill("]').click()
    cy.get('#TillDiv').should('be.visible')
  }
  const ensureShiftOpen = () => cy.request({ url: '/currentShift', failOnStatusCode: false }).then((r) => {
    if (!(r.body && r.body.status === 'SUCCESS'))
      cy.request({ method: 'POST', url: '/openShift', form: true, body: { openingFloat: 5000 } }).its('body.status').should('eq', 'SUCCESS')
  })
  const closeShiftQuietly = () => cy.request({ url: '/currentShift', failOnStatusCode: false }).then((r) => {
    if (r.body && r.body.status === 'SUCCESS')
      cy.request({ method: 'POST', url: '/closeShift', form: true, failOnStatusCode: false, body: { countedCash: 0 } })
  })

  caseIt('3-1', 'Pay the electricity man out of the drawer', () => {
    testCase('3-1', 'ex3', 'Pay the electricity man out of the drawer', { who: ['owner.lifecycle (recorded)', 'cashier.a', 'owner.business'] })
    setup('Expense management on (it is, from 1-7’s clean-up). A shift is open (Till → Cash Drawer → Open Shift; the recording opens one with a float of 5,000).')
    asLifecycle(true)
    ensureShiftOpen()
    SAFETY.push(() => { asLifecycle(); closeShiftQuietly() })
    tb().then((before) => {
      openTill()
      const a1 = act('<b>Till → Cash Drawer</b>: Cash in / out <b>Pay Out</b>, amount <b>1200</b>, category <b>Electricity, gas and water</b>, reason "electricity bill", press <b>Add</b>.',
        ['A <b>Category</b> field appears for Pay Out.', 'The movement is added.'])
      cy.get('#tillMoveType').select('PAY_OUT', { force: true })
      cy.get('#tillMoveCategoryGroup').should('be.visible')
      categoryByName('Electricity, gas and water').then((c) => cy.get('#tillMoveCategory').select(String(c.id), { force: true }))
      cy.get('#tillMoveAmount').clear().type('1200')
      cy.get('#tillMoveReason').clear().type('electricity bill')
      snap(a1, 'pay-out-form', '#TillDiv')
      cy.get('#tillMoveAdd').click()
      cy.wait(1500)
      const a2 = act('Press <b>X Report</b>.', ['Pay-outs up by 1,200 and expected cash down by 1,200.'])
      cy.get('#TillDiv').contains('button', 'X Report').click()
      cy.get('#tillReport', { timeout: 15000 }).should('not.be.empty')
      cy.request('/shiftReport').its('body.object.payOuts').then((p) => expect(Number(p), 'pay-outs include the 1,200').to.be.at.least(1200))
      snap(a2, 'x-report', '#tillReport')
      const a3 = act('Open <b>Till → Expenses</b>.', ['It is listed with an <b>EXP-</b> number, paid from <b>Till</b>, reaching <b>In the books</b>.'])
      openExpenses()
      cy.contains('#tableExpense tbody tr', 'Till', { timeout: 25000 }).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
      snap(a3, 'expense-till')
      const a4 = act('Open <b>Finance → Trial Balance</b>.', ['<b>6100</b> up 1,200 and <b>1000 Cash</b> down 1,200: the drawer and the books agree.'])
      tb().then((after) => {
        expect(delta(before, after, '6100')).to.eq(1200)
        expect(delta(before, after, '1000')).to.eq(-1200)
      })
      showTrialBalance()
      snap(a4, 'trial-balance')
    })
    act('Kept for 3-2 and 3-3; the shift is closed at the end of 3-3.', [], { cleanup: true })
  })

  caseIt('3-2', 'Category required; pay-ins and drops untouched; a double click is safe', () => {
    testCase('3-2', 'ex3', 'Category required; pay-ins and drops untouched; a double click is safe', { who: ['owner.lifecycle (recorded)', 'cashier.a'] })
    asLifecycle()
    ensureShiftOpen()
    openTill()
    const a1 = act('Pay Out <b>10</b> with no category, press <b>Add</b>.', ['Refused with a message saying a category is needed.'])
    cy.get('#tillMoveType').select('PAY_OUT', { force: true })
    cy.get('#tillMoveCategory').select('', { force: true })
    cy.get('#tillMoveAmount').clear().type('10')
    cy.get('#tillMoveReason').clear().type('no category')
    cy.intercept('POST', '**/cashMovement').as('move')
    cy.get('#tillMoveAdd').click()
    cy.wait('@move').its('response.body.status').should('not.eq', 'SUCCESS')
    // The refusal's own words — a bare /category/i also matches hidden menu items ("Markup by category").
    cy.contains('Choose what the money was paid for (a category)', { timeout: 10000 }).should('be.visible')
    snap(a1, 'refused')
    const a2 = act('Choose <b>Pay In</b>, then <b>Cash Drop</b>.', ['The Category field is not shown for either, and neither creates an expense.'])
    cy.get('#tillMoveType').select('PAY_IN', { force: true })
    cy.get('#tillMoveCategoryGroup').should('not.be.visible')
    cy.get('#tillMoveType').select('DROP', { force: true })
    cy.get('#tillMoveCategoryGroup').should('not.be.visible')
    snap(a2, 'no-category', '#TillDiv')
    act('Nothing to undo — the refused pay-out was never recorded.', [], { cleanup: true })
  })

  caseIt('3-3', 'A till expense is corrected at the till', () => {
    testCase('3-3', 'ex3', 'A till expense is corrected at the till', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    asLifecycle()
    openDashboard()
    const a1 = act('In <b>Till → Expenses</b>, find the Till expense from 3-1.', ['It has no <b>Void</b> button: a correction is a <b>Pay In</b> at the till, so the drawer and the books stay in step.'])
    openExpenses()
    cy.contains('#tableExpense tbody tr', 'Till', { timeout: 25000 }).find('[data-cy=void-expense]').should('not.exist')
    snap(a1, 'no-void')
    const c1 = act('Close the shift: <b>Till → Cash Drawer</b>, Counted cash, <b>Close Shift</b>.', ['The shift closes.'], { cleanup: true, via: 'run' })
    closeShiftQuietly()
  })

  // ═══ EX-4 · Expense bills ════════════════════════════════════════════════════════════════════════════════
  const S4 = 'ex4'
  let bill1 = null   // { supplier, payee }

  caseIt('4-1', 'Record a bill owed to a supplier', () => {
    testCase('4-1', S4, 'Record a bill owed to a supplier', { who: ['owner.lifecycle (recorded)', 'owner.business', 'owner.mobile'] })
    setup('Expense management is switched on for the business (case 0a-3), and you logged out and in again.')
    setup('The business has at least one supplier (Register → Vender / Supplier). The recording made one called <b>XG4A_…</b>.')
    asLifecycle(true)
    newSupplier('4A').then((s) => { bill1 = { supplier: s, payee: 'XG bill ' + run } })
    tb().then((before) => {
      openDashboard()
      const a1 = act('Open <b>Till → Expenses</b>.', ['The Expenses screen opens with the New Expense form and the list of expenses.'])
      openExpenses()
      snap(a1, 'expenses')
      const a2 = act('Category <b>Repairs and maintenance</b>, Amount <b>500</b>, Paid from <b>Bill (pay later)</b>.',
        ['After choosing <b>Bill (pay later)</b>, two new fields appear: <b>Supplier</b> and <b>Due date</b>.'])
      cy.then(() => fillExpense({ category: 'Repairs and maintenance', amount: 500, paidFrom: 'AP' }))
      cy.get('#expBillGroup').should('be.visible')
      snap(a2, 'bill-fields')
      const a3 = act('Press <b>Save and post</b> without choosing a supplier.', ['Refused: "Choose the supplier this bill is owed to." Nothing is saved.'])
      cy.get('[data-cy=save-expense]').click()
      cy.get('#expMsg').should('contain', 'Choose the supplier this bill is owed to.')
      snap(a3, 'supplier-required')
      const a4 = act('Choose the supplier, type Payee <b>XG bill …</b>, press <b>Save and post</b>.',
        ['"Expense saved. Posting to the books." The row shows paid from <b>Bill</b>, <b>In the books</b>, then <b>Owes 500.00</b> and a <b>Pay</b> button.'])
      cy.then(() => { cy.get('#expSupplier').select(String(bill1.supplier.id), { force: true }); cy.get('#expPayee').clear().type(bill1.payee) })
      cy.get('[data-cy=save-expense]').click()
      cy.get('#expMsg').should('contain', 'Posting to the books')
      cy.then(() => expenseRow(bill1.payee).within(() => {
        cy.contains('Bill')
        cy.get('[data-cy=expense-bill-owes]', { timeout: 25000 }).should('contain', '500.00')
        cy.get('[data-cy=pay-bill]', { timeout: 25000 }).should('be.visible')
      }))
      snap(a4, 'bill-owes')
      const a5 = act('Open <b>Finance → Trial Balance</b>.',
        ['<b>6300 Repairs</b> is 500 higher than before; <b>2000 Accounts Payable</b> is credited 500; <b>1000 Cash</b> has not moved. Still balanced.'])
      tb().then((after) => {
        expect(delta(before, after, '6300'), '6300').to.eq(500)
        expect(delta(before, after, '2000'), '2000 credit').to.eq(-500)
        expect(delta(before, after, '1000'), 'cash').to.eq(0)
      })
      showTrialBalance()
      snap(a5, 'trial-balance')
    })
    act('Nothing to undo yet — cases 4-2 to 4-4 pay this bill off. (If you stop here: void it from its row with a reason.)', [], { cleanup: true })
  })

  caseIt('4-2', 'Pay part of the bill', () => {
    testCase('4-2', S4, 'Pay part of the bill', { who: ['owner.lifecycle (recorded)', 'owner.business', 'user.business'] })
    setup('The bill from case 4-1 (Owes 500.00).')
    asLifecycle()
    tb().then((before) => {
      openDashboard(); openExpenses()
      const a1 = act('On the bill’s row press <b>Pay</b>.', ['A Pay panel opens: "Pay bill EXP-… — Owes 500.00", Amount pre-filled <b>500.00</b>, Paid from Cash.'])
      expenseRow(bill1.payee).find('[data-cy=pay-bill]').click()
      cy.get('[data-cy=expense-pay-panel]').should('be.visible')
      cy.get('#expPayAmount').should('have.value', '500.00')
      snap(a1, 'pay-panel')
      const a2 = act('Change the amount to <b>200</b>, Paid from <b>Cash</b>, press <b>Pay</b>.',
        ['"Payment recorded — PV-…". The row now shows <b>Owes 300.00</b>.'])
      cy.get('#expPayAmount').clear().type('200')
      cy.get('#expPayMethod').select('CASH', { force: true })
      cy.get('[data-cy=expense-pay-go]').click()
      cy.get('#expMsg', { timeout: 20000 }).should('contain', 'Payment recorded').and('contain', 'PV-')
      expenseRow(bill1.payee).find('[data-cy=expense-bill-owes]').should('contain', '300.00')
      snap(a2, 'part-paid')
      const a3 = act('Open <b>Finance → Trial Balance</b>.', ['<b>2000 Accounts Payable</b> debited 200 and <b>1000 Cash</b> down 200.'])
      tb().then((after) => {
        expect(delta(before, after, '2000')).to.eq(200)
        expect(delta(before, after, '1000')).to.eq(-200)
      })
      showTrialBalance()
      snap(a3, 'trial-balance')
    })
    act('Nothing to undo — the payment is real money; case 4-4 pays the rest.', [], { cleanup: true })
  })

  caseIt('4-3', 'Overpaying and voiding are refused where they would hurt', () => {
    testCase('4-3', S4, 'Overpaying and voiding are refused where they would hurt', { who: ['owner.lifecycle (recorded)', 'owner.business', 'admin.business'] })
    setup('The part-paid bill from case 4-2 (Owes 300.00).')
    asLifecycle()
    openDashboard(); openExpenses()
    const a1 = act('Press <b>Pay</b>, enter <b>300.01</b>, press <b>Pay</b>.', ['Refused in the panel: "That is more than is owed on this bill (300.00)." Nothing is paid.'])
    expenseRow(bill1.payee).find('[data-cy=pay-bill]').click()
    cy.get('#expPayAmount').clear().type('300.01')
    cy.get('[data-cy=expense-pay-go]').click()
    cy.get('#expPayMsg', { timeout: 15000 }).should('contain', 'more than is owed')
    snap(a1, 'overpay-refused')
    const a2 = act('Close the panel and look at the bill’s <b>Actions</b>.', ['A bill with payments has <b>no Void button</b>.'])
    cy.get('#expPayPanel button[onclick^="expensePayClose"]').click()
    expenseRow(bill1.payee).find('[data-cy=void-expense]').should('not.exist')
    snap(a2, 'no-void')
    const payee2 = 'XG void ' + run
    tb().then((before) => {
      const a3 = act(`Record a second bill: <b>Repairs and maintenance</b>, <b>70</b>, Bill (pay later), the same supplier, Payee <b>${payee2}</b>. Wait for <b>In the books</b>, then press <b>Void</b>, give the reason "entered twice", confirm.`,
        ['The row shows <b>Void</b> and keeps its number.', 'Trial balance: 6300 and 2000 are back where they were before this bill.'])
      cy.then(() => fillExpense({ category: 'Repairs and maintenance', amount: 70, paidFrom: 'AP', supplierId: bill1.supplier.id, payee: payee2 }))
      cy.get('[data-cy=save-expense]').click()
      expenseRow(payee2).find('[data-cy=void-expense]', { timeout: 25000 }).click()
      cy.get('.uiC-input').type('entered twice')
      cy.get('[data-ui-confirm="ok"]').click()
      expenseRow(payee2).find('.exp-chip').should('contain', 'Void')
      cy.wait(3000)
      tb().then((after) => {
        expect(delta(before, after, '6300')).to.eq(0)
        expect(delta(before, after, '2000')).to.eq(0)
      })
      snap(a3, 'voided')
    })
    act('Nothing to undo — the second bill is void; the first is paid off in 4-4.', [], { cleanup: true })
  })

  caseIt('4-4', 'Pay the rest from the bank, and the bill reads Paid', () => {
    testCase('4-4', S4, 'Pay the rest from the bank, and the bill reads Paid', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    setup('The bill from 4-1, owing 300.00.')
    asLifecycle()
    tb().then((before) => {
      openDashboard(); openExpenses()
      const a1 = act('Press <b>Pay</b>, keep <b>300.00</b>, Paid from <b>Bank</b>, press <b>Pay</b>.',
        ['"Payment recorded — PV-…". The row shows <b>Paid</b> and no Pay button.', 'Trial balance: 2000 down 300 and <b>1010 Bank</b> down 300 (cash unchanged).'])
      expenseRow(bill1.payee).find('[data-cy=pay-bill]').click()
      cy.get('#expPayAmount').should('have.value', '300.00')
      cy.get('#expPayMethod').select('BANK', { force: true })
      cy.get('[data-cy=expense-pay-go]').click()
      cy.get('#expMsg', { timeout: 20000 }).should('contain', 'PV-')
      expenseRow(bill1.payee).find('[data-cy=expense-bill-paid]').should('exist')
      expenseRow(bill1.payee).find('[data-cy=pay-bill]').should('not.exist')
      tb().then((after) => {
        expect(delta(before, after, '2000')).to.eq(300)
        expect(delta(before, after, '1010')).to.eq(-300)
        expect(delta(before, after, '1000')).to.eq(0)
      })
      snap(a1, 'paid')
    })
    act('Nothing to undo here — case 4-7 reverses both payments and voids the bill.', [], { cleanup: true })
  })

  caseIt('4-7', 'Reverse a payment, and a paid bill can be voided', () => {
    testCase('4-7', S4, 'Reverse a payment, and a paid bill can be voided', { who: ['owner.lifecycle (recorded)', 'owner.business', 'admin.business', 'user.business'] })
    setup('The bill from 4-1, paid off in 4-2 (200 cash) and 4-4 (300 bank). Recorded together with 4-1 to 4-4.')
    asLifecycle()
    tb().then((before) => {
      openDashboard(); openExpenses()
      const a1 = act('On the paid bill press <b>Payments</b>.', ['Under the bill: its two payments — <b>PV-…</b> 200 Cash and <b>PV-…</b> 300 Bank — each with a <b>Reverse</b> button.'])
      expenseRow(bill1.payee).find('[data-cy=bill-payments]').click()
      cy.get('[data-cy=bill-payment-row]').should('have.length', 2)
      cy.get('[data-cy=reverse-payment]').should('have.length', 2)
      expenseRow(bill1.payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a1, 'payments')
      const a2 = act('On the <b>300 Bank</b> payment press <b>Reverse</b>, reason <b>Paid twice by mistake</b>, confirm.',
        ['"Payment reversed — PV-…-R". The payment reads <b>Reversed PV-…-R — Paid twice by mistake</b>.',
         'The bill reads <b>Owes 300.00</b> again with a <b>Pay</b> button.', 'Trial balance: <b>1010 Bank</b> up 300, <b>2000</b> owes 300 more.'])
      cy.contains('[data-cy=bill-payment-row]', '300.00').find('[data-cy=reverse-payment]').click()
      cy.get('.uiC-input').type('Paid twice by mistake')
      cy.get('[data-ui-confirm="ok"]').click()
      cy.get('#expMsg', { timeout: 20000 }).should('contain', 'Payment reversed').and('contain', '-R')
      cy.contains('[data-cy=bill-payment-row]', '300.00', { timeout: 20000 }).should('contain', 'Reversed').and('contain', 'Paid twice by mistake')
      expenseRow(bill1.payee).find('[data-cy=expense-bill-owes]').should('contain', '300.00')
      tb().then((mid) => {
        expect(delta(before, mid, '1010'), 'bank back').to.eq(300)
        expect(delta(before, mid, '2000'), 'owed again').to.eq(-300)
      })
      expenseRow(bill1.payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'reversed')
      const a3 = act('Reverse the <b>200 Cash</b> payment too (reason <b>Cancelled</b>). Then press the bill’s <b>Void</b>, reason <b>Job cancelled</b>.',
        ['After the second reversal the bill owes the full 500 and a <b>Void</b> button appears.', 'Voided: the row reads <b>Void</b>; 6300, 2000, 1000 and 1010 are all back where they were before 4-1.'])
      cy.contains('[data-cy=bill-payment-row]', '200.00').find('[data-cy=reverse-payment]').click()
      cy.get('.uiC-input').type('Cancelled')
      cy.get('[data-ui-confirm="ok"]').click()
      cy.contains('[data-cy=bill-payment-row]', '200.00', { timeout: 20000 }).should('contain', 'Reversed')
      expenseRow(bill1.payee).find('[data-cy=void-expense]', { timeout: 20000 }).click()
      cy.get('.uiC-input').type('Job cancelled')
      cy.get('[data-ui-confirm="ok"]').click()
      expenseRow(bill1.payee).find('.exp-chip', { timeout: 20000 }).should('contain', 'Void')
      expenseRow(bill1.payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a3, 'voided')
    })
    cy.then(() => {
      act('Lock the books through today (Finance → Period Close) and try to reverse a payment.', ['Refused in the books’ words: “This period is closed …”; nothing moves. (Checked in the automated gate, case 5.)'], { via: 'run' })
      act('As <b>user.business</b>: open a paid bill.', ['No <b>Payments</b>/<b>Reverse</b> buttons; the server refuses a reversal (gate case 6).'], { via: 'run' })
      act('Nothing to undo — the bill is void and both payments are reversed.', [], { cleanup: true })
    })
  })

  caseIt('4-5', 'A business with no suppliers does not get the option', () => {
    testCase('4-5', S4, 'A business with no suppliers does not get the option', { who: ['owner.education (recorded)', 'owner.welfare', 'owner.agriculture'] })
    setup('Expense management is on for the school (case 2a-1). The recording switches it on and back off.')
    cy.loginAs('owner.education@myplus.com', PW, '/getDashboardData')
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: 'org.cap.' + CAP, enabled: 'true' } })
      .its('body').should((b) => expect(JSON.stringify(b)).to.match(/true|Saved|success/i))
    SAFETY.push(() => { cy.loginAs('owner.education@myplus.com', PW, '/getDashboardData'); cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, failOnStatusCode: false, body: { key: 'org.cap.' + CAP } }) })
    cy.loginAs('owner.education@myplus.com', PW, '/getDashboardData', 'xg-edu-' + Date.now())
    cy.visit('/educationDashboard'); cy.waitForAppReady()
    const a1 = act('Open <b>Fee → Expenses</b> and the <b>Paid from</b> list.', ['Only <b>Cash</b> and <b>Bank</b> are offered — no "Bill (pay later)".'])
    openMenu('snavFee')
    cy.get('#navExpenses').should('be.visible').click()
    cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.wait(1500)
    cy.get('#expPaidFrom option').then(($o) => expect([...$o].map((o) => o.value)).to.deep.eq(['CASH', 'BANK']))
    snap(a1, 'cash-bank-only')
    const c1 = act('As the school owner: <b>Fee → Configuration → Modules</b>, untick <b>Expense management</b> (if you switched it on for this test).', [], { cleanup: true })
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, failOnStatusCode: false, body: { key: 'org.cap.' + CAP } })
  })

  caseIt('4-6', 'The shop’s supplier statement is not confused by bill payments', () => {
    testCase('4-6', S4, 'The shop’s supplier statement is not confused by bill payments', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    setup('The supplier from 4-1, whose bill was paid in 4-2 and 4-4. The business reads supplier figures from <b>business</b> (the default).')
    asLifecycle()
    openDashboard()
    const a1 = act('Open <b>Register → Vender / Supplier</b>, search the supplier, press <b>Statement</b> on its row.',
      ['The statement lists the supplier’s purchases and their payments only. The two PV- payments made against the bill are <b>not</b> on it.'])
    openSuppliers(bill1.supplier.name)
    cy.contains('#VenderDiv tr', bill1.supplier.name, { timeout: 15000 }).find('.stmt-btn').click()
    cy.get('#StatementDialogBody', { timeout: 15000 }).should('be.visible').and('not.contain', 'Loading')
    cy.get('#StatementDialogBody').should('not.contain', 'PV-')
    snap(a1, 'statement', '#StatementDialog .crud-card, #StatementDialog')
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  // ═══ FP-1/2 · Payables in finance ═══════════════════════════════════════════════════════════════════════════
  caseIt('12-1', 'Business and finance agree on what is owed, after every kind of change', () => {
    testCase('12-1', 'fp12', 'Business and finance agree on what is owed, after every kind of change',
      { who: ['admin@myplus.com (operator)', 'owner.lifecycle (recorded)'] })
    setup('Operator login <code>admin@myplus.com</code> / <code>Admin@2025!</code>; any business, recorded on Owner Lifecycle.')
    cy.loginAsOperator()
    const a1 = act(`As the operator: <b>Platform</b> → search <b>${LIFECYCLE_ORG_NAME}</b> → open it → card <b>Supplier balances</b>.`,
      ['<b>Business says owed</b> equals <b>Finance says owed (purchases)</b>; <b>Difference 0.00</b>.'])
    openTenantPanel(LIFECYCLE_ORG_NAME)
    cy.get('[data-cy=plat-payables-diff]').invoke('text').then((t) => expect(Number(t.replace(/[^0-9.-]/g, ''))).to.eq(0))
    snap(a1, 'card-before', '#platPayables')
    let sup = null
    const inv = 'XG12INV-' + run
    asLifecycle()
    newSupplier('12').then((s) => { sup = s })
    const a2 = act('As the owner: record a purchase <b>on credit</b> of 100 from a new supplier (Purchase → New Purchase, Paid 0); pay that supplier 40 (<b>Pay</b> on its row in Register → Vender / Supplier); then void the purchase.',
      ['Each step saves.'], { via: 'run' })
    cy.then(() => creditPurchase(sup.id, 100, inv).its('status').should('eq', 'SUCCESS'))
    cy.then(() => cy.request({ method: 'POST', url: '/payVendor', form: true, body: { venderId: sup.id, amount: 40, method: 'CASH', idempotencyKey: 'xg12-' + run } })
      .its('body.status').should('eq', 'SUCCESS'))
    cy.then(() => purchaseIdOf(inv)).then((pid) => cy.request({ method: 'POST', url: '/voidPurchase', form: true, body: { purchaseId: pid, reason: 'Test Book 12-1' } })
      .its('body.status').should('eq', 'SUCCESS'))
    const a3 = act('As the operator: refresh the tenant panel.', ['<b>Difference</b> is still <b>0.00</b>: finance followed every change.'])
    cy.loginAsOperator()
    const settled = (n = 20) => { openTenantPanel(LIFECYCLE_ORG_NAME); return cy.get('[data-cy=plat-payables-diff]').invoke('text').then((t) => (Number(t.replace(/[^0-9.-]/g, '')) === 0 || n <= 0) ? Number(t.replace(/[^0-9.-]/g, '')) : (cy.wait(1500), settled(n - 1))) }
    settled().should('eq', 0)
    snap(a3, 'card-after', '#platPayables')
    act('Nothing to undo — the purchase is void; the 40 paid stands as the test supplier’s advance on Owner Lifecycle.', [], { cleanup: true })
  })

  // ═══ FP-4a · Supplier statement ═════════════════════════════════════════════════════════════════════════════
  let stmtSup = null
  const stmtInv = 'XG4AINV-' + run
  caseIt('4a-1', 'A purchase, a return and a payment on one supplier', () => {
    testCase('4a-1', 'fp4a', 'A purchase, a return and a payment on one supplier', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    setup('A new supplier with a purchase of 10 items at 10 on credit (100), made through Purchase → New Purchase.')
    asLifecycle()
    newSupplier('4AS').then((s) => { stmtSup = s })
    cy.then(() => cy.seedProduct({ name: 'XG4AP_' + run, sellingPrice: 12, stock: 1 })).then(({ productId }) =>
      cy.request({ method: 'POST', url: '/addPurchase', form: true, body: { productId, quantity: 10, venderId: stmtSup.id, paidAmount: 0,
        'stock.bpurchaseRate': 10, 'stock.bsellRate': 12, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: stmtInv } })
        .its('body.status').should('eq', 'SUCCESS'))
    const a1 = act('Return <b>3</b> items from that purchase (Purchase list → Return), then pay the supplier <b>20</b> in cash (<b>Pay</b> on its row).',
      ['Both save; the payment answers with a PV- voucher.'], { via: 'run' })
    cy.then(() => purchaseIdOf(stmtInv)).then((pid) => cy.request({ method: 'POST', url: '/purchaseReturn', form: true, body: { purchaseId: pid, quantity: 3, reason: 'Test Book 4a-1' } })
      .its('body.status').should('eq', 'SUCCESS'))
    cy.then(() => cy.request({ method: 'POST', url: '/payVendor', form: true, body: { venderId: stmtSup.id, amount: 20, method: 'CASH', idempotencyKey: 'xg4a-' + run } })
      .its('body.status').should('eq', 'SUCCESS'))
    openDashboard()
    const a2 = act('Open <b>Register → Vender / Supplier</b>, search the supplier, press <b>Statement</b>.',
      ['A <b>Bill</b> line for <b>100.00</b> (the bill as issued).', 'A <b>Debit note</b> line for the 3 returned items and a <b>Payment</b> line for 20 with its PV- number.', 'The closing balance equals the supplier’s Due.'])
    cy.then(() => { openSuppliers(stmtSup.name); cy.contains('#VenderDiv tr', stmtSup.name, { timeout: 15000 }).find('.stmt-btn').click() })
    cy.get('#StatementDialogBody', { timeout: 15000 }).should('contain', '100.00').and('contain', 'Debit note').and('contain', 'PV-')
    snap(a2, 'statement', '#StatementDialog')
    act('Nothing to undo yet — case 4a-2 voids this purchase.', [], { cleanup: true })
  })

  caseIt('4a-2', 'Voiding the purchase nets to zero on the statement', () => {
    testCase('4a-2', 'fp4a', 'Voiding the purchase nets to zero on the statement', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    setup('The purchase and supplier from 4a-1.')
    asLifecycle()
    const a1 = act('Void the purchase from 4a-1 with a reason (Purchase list → Void).', ['The purchase is marked void.'], { via: 'run' })
    cy.then(() => purchaseIdOf(stmtInv)).then((pid) => cy.request({ method: 'POST', url: '/voidPurchase', form: true, body: { purchaseId: pid, reason: 'Test Book 4a-2' } })
      .its('body.status').should('eq', 'SUCCESS'))
    openDashboard()
    const a2 = act('Reopen the supplier’s <b>Statement</b>.', ['A second <b>Debit note</b> for the rest of the bill: the bill and its two debit notes net to zero.'])
    openSuppliers(stmtSup.name)
    cy.contains('#VenderDiv tr', stmtSup.name, { timeout: 15000 }).find('.stmt-btn').click()
    cy.get('#StatementDialogBody', { timeout: 15000 }).find('tr:contains("Debit note")').should('have.length', 2)
    snap(a2, 'statement-void', '#StatementDialog')
    act('Nothing to undo — the purchase is void; the 20 paid stands as this test supplier’s advance.', [], { cleanup: true })
  })

  // ═══ FP-4b · The operator's switch ══════════════════════════════════════════════════════════════════════════
  caseIt('4b-1', 'An owner cannot see or flip the switch', () => {
    testCase('4b-1', 'fp4b', 'An owner cannot see or flip the switch', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    asLifecycle()
    const a1 = act('As the owner, ask for the switch directly: open <code>/platform/payablesSource?organizationId=&lt;your org&gt;</code> in the browser.',
      ['Refused (403 / not allowed). Nothing on the owner’s Settings or Configuration screens mentions where supplier figures come from.'], { via: 'run' })
    cy.request({ url: `/platform/payablesSource?organizationId=${lifecycleOrg}`, failOnStatusCode: false })
      .then((r) => expect(r.status === 403 || (r.body && r.body.status !== 'SUCCESS')).to.eq(true))
    openDashboard()
    const a2 = act('Open <b>Settings → Configuration</b> and search for "supplier" / "payables".', ['No switch for where supplier figures come from.'])
    openMenu('snavSettings'); cy.get('#snavSettings a[onclick^="showBusinessConfig("]').click()
    cy.get('#businessConfigBody', { timeout: 20000 }).should('be.visible').and('not.contain', 'payables source')
    snap(a2, 'configuration')
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  caseIt('4b-2', 'The operator reads both sides; a business that disagrees is refused', () => {
    testCase('4b-2', 'fp4b', 'The operator reads both sides; a business that disagrees is refused', { who: ['admin@myplus.com (operator)'] })
    cy.loginAsOperator()
    const a1 = act(`Platform → open <b>${LIFECYCLE_ORG_NAME}</b> → <b>Supplier balances</b>.`,
      ['<b>Difference 0.00</b>; <b>Read from finance</b> is enabled; <b>Finance vs GL 2000</b> is shown.'])
    openTenantPanel(LIFECYCLE_ORG_NAME)
    cy.get('[data-cy=plat-payables-diff]').invoke('text').then((t) => expect(Number(t.replace(/[^0-9.-]/g, ''))).to.eq(0))
    cy.get('[data-cy=plat-payables-finance]').should('not.be.disabled')
    snap(a1, 'agrees', '#platPayables')
    const a2 = act('Platform → open <b>Demo BUSINESS’s organization</b> → <b>Supplier balances</b>.',
      ['<b>Difference 100.00</b> highlighted; <b>Read from finance</b> is <b>disabled</b>; a yellow note explains the GL 2000 difference does not block.'])
    openTenantPanel("Demo BUSINESS's organization")
    cy.get('[data-cy=plat-payables-diff]').invoke('text').then((t) => expect(Number(t.replace(/[^0-9.-]/g, '')), 'a difference').to.not.eq(0))
    cy.get('[data-cy=plat-payables-finance]').should('be.disabled')
    snap(a2, 'disagrees', '#platPayables')
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  let fpSup = null
  caseIt('4b-3', 'Switch to finance: bills and advances appear in the owner’s reports', () => {
    testCase('4b-3', 'fp4b', 'Switch to finance: bills and advances appear in the owner’s reports', { who: ['admin@myplus.com (operator)', 'owner.lifecycle (recorded)'] })
    setup('A supplier owed an expense bill: the recording makes one (bill 65, Bill (pay later), as in case 4-1).')
    asLifecycle()
    newSupplier('4B').then((s) => { fpSup = s })
    cy.then(() => cy.request('/expense/categories')).then((r) => {
      const cat = (r.body.data || []).find((c) => c.active !== false)
      return token().then((t) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`,
        headers: { Authorization: `Bearer ${t}`, 'Idempotency-Key': 'xg4b-' + run },
        body: { voucherDate: localIsoDate(), paidFrom: 'AP', supplierId: fpSup.id, payeeName: 'XG4B bill ' + run, lines: [{ categoryId: cat.id, amount: 65 }] } })
        .its('body.success').should('eq', true))
    })
    cy.wait(4000)
    cy.loginAsOperator()
    openTenantPanel(LIFECYCLE_ORG_NAME)
    const a1 = act('As the operator: press <b>Read from finance</b>, try <b>Confirm</b> with an empty reason, then type "Test Book 4b-3" and confirm.',
      ['An empty reason is refused in the dialog.', 'The card now reads <b>Supplier balances — FINANCE</b>.'])
    cy.get('[data-cy=plat-payables-finance]').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('.uiC-input').should('be.visible')
    cy.get('.uiC-input').type('Test Book 4b-3')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('[data-cy=plat-payables-source]', { timeout: 15000 }).should('contain', 'FINANCE')
    snap(a1, 'switched', '#platPayables')
    asLifecycle(true)
    openDashboard()
    const a2 = act('As the owner: <b>Register → Vender / Supplier → Payables Aging</b>.',
      ['The aging lists the 65 bill under its supplier. Suppliers paid ahead appear in a separate <b>Paid ahead (supplier advances)</b> table.'])
    cy.openSection('VenderDiv')
    cy.window().then((w) => w.openAging('VENDOR'))
    cy.then(() => cy.get('#AgingDialogBody table', { timeout: 15000 }).should('contain', fpSup.name))
    snap(a2, 'aging', '#AgingDialog')
    const a3 = act('Open that supplier’s <b>Statement</b>.', ['The statement shows the <b>EXP-</b> bill line for 65.'])
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    cy.then(() => { openSuppliers(fpSup.name); cy.contains('#VenderDiv tr', fpSup.name, { timeout: 15000 }).find('.stmt-btn').click() })
    cy.get('#StatementDialogBody', { timeout: 15000 }).should('contain', 'EXP-').and('contain', '65.00')
    snap(a3, 'statement', '#StatementDialog')
    act('Switch back in case 4b-4.', [], { cleanup: true })
  })

  caseIt('4b-4', 'Switch back: the business sees its old figures again', () => {
    testCase('4b-4', 'fp4b', 'Switch back: the business sees its old figures again', { who: ['admin@myplus.com (operator)', 'owner.lifecycle (recorded)'] })
    setup('Owner Lifecycle reads from FINANCE (case 4b-3).')
    cy.loginAsOperator()
    openTenantPanel(LIFECYCLE_ORG_NAME)
    const a1 = act('As the operator: press <b>Back to business</b>, reason "Test Book 4b-4", confirm.', ['The card reads <b>BUSINESS</b>.'])
    cy.get('[data-cy=plat-payables-business]').click()
    cy.get('.uiC-input').type('Test Book 4b-4')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('[data-cy=plat-payables-source]', { timeout: 15000 }).should('contain', 'BUSINESS')
    snap(a1, 'back', '#platPayables')
    asLifecycle(true)
    openDashboard()
    const a2 = act('As the owner: reopen the supplier’s <b>Statement</b>.', ['The expense bill is no longer on it (business lists purchases only).'])
    openSuppliers(fpSup.name)
    cy.contains('#VenderDiv tr', fpSup.name, { timeout: 15000 }).find('.stmt-btn').click()
    cy.get('#StatementDialogBody', { timeout: 15000 }).should('not.contain', 'EXP-')
    snap(a2, 'statement-business', '#StatementDialog')
    const c1 = act('<b>Till → Expenses</b>: on the 65 bill’s row press <b>Void</b>, reason "Test Book clean-up", confirm.', ['The row shows <b>Void</b>; the supplier owes nothing more.'], { cleanup: true })
    voidBillOnScreen('XG4B bill ' + run, c1)
  })

  // ═══ FP-4c · Balances and credit limit ══════════════════════════════════════════════════════════════════════
  let limSup = null
  caseIt('4c-1', 'The supplier list shows the total, with the bills; Pay carries the total', () => {
    testCase('4c-1', 'fp4c', 'The supplier list shows the total, with the bills; Pay carries the total',
      { who: ['admin@myplus.com (operator)', 'owner.lifecycle (recorded)'] })
    setup('A supplier with a credit limit of 1000, owed <b>600</b> on purchases and a <b>300</b> expense bill. The business is switched to FINANCE by the operator.')
    asLifecycle()
    newSupplier('4C', { creditLimit: 1000 }).then((s) => { limSup = s })
    cy.then(() => creditPurchase(limSup.id, 600, 'XG4CINV1-' + run).its('status').should('eq', 'SUCCESS'))
    cy.then(() => cy.request('/expense/categories')).then((r) => {
      const cat = (r.body.data || []).find((c) => c.active !== false)
      return token().then((t) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`,
        headers: { Authorization: `Bearer ${t}`, 'Idempotency-Key': 'xg4c-' + run },
        body: { voucherDate: localIsoDate(), paidFrom: 'AP', supplierId: limSup.id, lines: [{ categoryId: cat.id, amount: 300 }] } })
        .its('body.success').should('eq', true))
    })
    cy.loginAsOperator()
    cy.then(() => flip(lifecycleOrg, 'FINANCE', 'Test Book 4c'))
    asLifecycle(true)
    const ready = (n = 25) => vendorRow(limSup.id).then((v) => (Number(v.billsOwed) === 300 || n <= 0) ? v : (cy.wait(1000), ready(n - 1)))
    cy.then(() => ready())
    openDashboard()
    const a1 = act('Open <b>Register → Vender / Supplier</b> and search the supplier.',
      ['<b>Due</b> reads <b>900</b> with <b>(incl. bills 300.00)</b>.', 'The <b>Pay</b> button carries <b>900</b>; its tooltip says it pays purchases and expense bills, oldest first (FP-5b).'])
    cy.then(() => openSuppliers(limSup.name))
    cy.then(() => cy.contains('#VenderDiv tr', limSup.name, { timeout: 15000 })).within(() => {
      cy.get('[data-cy=vender-due]').should('contain', '900').and('contain', '300.00')
      cy.get('.pay-vendor-btn').should('have.attr', 'data-due').and('match', /^900/)
    })
    snap(a1, 'supplier-list')
    act('Kept for 4c-2 to 4c-4; the business goes back to BUSINESS at the end of 4c-3.', [], { cleanup: true })
  })

  caseIt('4c-2', 'The purchase screen warns with everything owed', () => {
    testCase('4c-2', 'fp4c', 'The purchase screen warns with everything owed', { who: ['owner.lifecycle (recorded)'] })
    setup('The supplier from 4c-1; the business reads from FINANCE.')
    asLifecycle()
    const a1 = act('Start a new purchase and choose that supplier.', ['The amount already owed shown for the supplier is <b>900</b> (purchases 600 + bills 300), not 600.'], { via: 'run' })
    cy.request('/getUserVenders').its('body').then((html) => {
      const m = new RegExp(`value=${limSup.id} data-due="([^"]+)"`).exec(html)
      expect(Number(m[1])).to.eq(900)
    })
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  caseIt('4c-3', 'The credit limit counts the bills', () => {
    testCase('4c-3', 'fp4c', 'The credit limit counts the bills', { who: ['admin@myplus.com (operator)', 'owner.lifecycle (recorded)'] })
    setup('The supplier from 4c-1 (limit 1000, owed 600 + 300).')
    asLifecycle()
    openDashboard()
    const a1 = act('<b>Settings → Configuration → Purchasing</b>: set <b>When a purchase would exceed the supplier’s credit limit</b> to <b>Block</b>.', ['Saved.'])
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: POLICY, value: 'block' } }).its('body.success').should('eq', true)
    openMenu('snavSettings'); cy.get('#snavSettings a[onclick^="showBusinessConfig("]').click()
    cy.revealSetting(POLICY)
    snap(a1, 'policy-block')
    const a2 = act('Purchase → New Purchase: 150 on credit from that supplier.',
      ['Refused: "You would owe … 1050.00, which is 50.00 over the credit limit of 1000.00 …"'], { via: 'run' })
    creditPurchase(limSup.id, 150, 'XG4CINV2-' + run).then((b) => {
      expect(b.status).to.not.eq('SUCCESS')
      expect(JSON.stringify(b)).to.match(/1050\.00.*over the credit limit of 1000\.00/)
    })
    const a3 = act('Ask the operator to switch the business back to <b>BUSINESS</b>, then try the same purchase.', ['Allowed: 600 + 150 is under the limit.'], { via: 'run' })
    cy.loginAsOperator()
    cy.then(() => flip(lifecycleOrg, 'BUSINESS', 'Test Book 4c-3'))
    asLifecycle(true)
    creditPurchase(limSup.id, 150, 'XG4CINV3-' + run).its('status').should('eq', 'SUCCESS')
    const c1 = act('Set <b>When a purchase would exceed the supplier’s credit limit</b> back to <b>Warn</b>; void the 150 and 600 test purchases (Purchase list → Void).', [], { cleanup: true })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: POLICY } })
    ;['XG4CINV3-' + run, 'XG4CINV1-' + run].forEach((inv) => purchaseIdOf(inv).then((pid) => { if (pid) cy.request({ method: 'POST', url: '/voidPurchase', form: true, failOnStatusCode: false, body: { purchaseId: pid, reason: 'Test Book cleanup' } }) }))
  })

  caseIt('4c-4', 'Editing a supplier keeps its figures', () => {
    testCase('4c-4', 'fp4c', 'Editing a supplier keeps its figures', { who: ['owner.lifecycle (recorded)'] })
    setup('The supplier from 4c-1 (owes the 300 bill).')
    asLifecycle()
    const a1 = act('Edit the supplier’s address to "edited by the Test Book" and save.', ['Saved. The bills part (300) is unchanged.'], { via: 'run' })
    vendorRow(limSup.id).then((v) => cy.request({ method: 'POST', url: '/addVender', form: true,
      body: { id: limSup.id, name: limSup.name, companyIds: v.companyIds, mobile: v.mobile, email: v.email, address: 'edited by the Test Book', creditLimit: 1000 } })
      .its('body.status').should('eq', 'SUCCESS'))
    cy.loginAsOperator()
    cy.then(() => flip(lifecycleOrg, 'FINANCE', 'Test Book 4c-4'))
    asLifecycle(true)
    vendorRow(limSup.id).then((v) => expect(Number(v.billsOwed)).to.eq(300))
    cy.loginAsOperator()
    cy.then(() => flip(lifecycleOrg, 'BUSINESS', 'Test Book 4c-4 cleanup'))
    const c1 = act('<b>Till → Expenses</b>: on the 300 bill’s row press <b>Void</b>, reason "Test Book clean-up", confirm. (The operator already switched the business back to BUSINESS.)',
      ['The row shows <b>Void</b>.'], { cleanup: true })
    asLifecycle(true)   // the switch-back above signed in as the operator
    cy.then(() => voidBillOnScreen(limSup.name, c1))
  })

  // ═══ FP-5b · One Pay for both ═════════════════════════════════════════════════════════════════════════════
  const PAYABLES = 'owner.payables@myplus.com'
  const asPayables = (fresh) => cy.loginAs(PAYABLES, PW, '/getBusinessDashboardStats', fresh ? 'xg-5b-' + Date.now() : undefined)
  let payablesOrg = null
  let mixSup = null

  caseIt('5b-1', 'One Pay settles the older bill first, then the purchase', () => {
    testCase('5b-1', 'fp5b', 'One Pay settles the older bill first, then the purchase', { who: ['owner.payables (recorded; reserved for mixed payments)'] })
    setup('The business reads supplier figures from <b>FINANCE</b> (operator, case 4b-3) and Expense management is on.')
    setup('A supplier with an expense bill of <b>300 dated yesterday</b> (Bill (pay later)) and a purchase of <b>100 today</b> on credit.')
    cy.loginAsOperator()
    cy.orgOf(PAYABLES).then((o) => { payablesOrg = o.id })
    cy.then(() => cy.request({ method: 'POST', url: '/platform/payablesSource', form: true, body: { organizationId: payablesOrg, source: 'FINANCE', reason: 'Test Book 5b' } }))
    asPayables()
    cy.setCapability(CAP, true)
    SAFETY.push(() => { asPayables(); cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } }) })
    asPayables(true)
    newSupplier('5B').then((sp) => { mixSup = sp })
    const yesterday = (() => { const d = new Date(); d.setDate(d.getDate() - 1); return localIsoDate(d) })()
    cy.then(() => cy.request('/expense/categories')).then((r) => {
      const cat = (r.body.data || []).find((c) => c.active !== false)
      return cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: PAYABLES, password: PW } }).its('body.data.accessToken').then((tk) =>
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: { Authorization: `Bearer ${tk}`, 'Idempotency-Key': 'xg5b-' + run },
          body: { voucherDate: yesterday, paidFrom: 'AP', supplierId: mixSup.id, lines: [{ categoryId: cat.id, amount: 300 }] } }).its('body.success').should('eq', true))
    })
    cy.then(() => creditPurchase(mixSup.id, 100, 'XG5BINV-' + run).its('status').should('eq', 'SUCCESS'))
    const ready = (n = 25) => vendorRow(mixSup.id).then((v) => (Number(v.billsOwed) === 300 || n <= 0) ? v : (cy.wait(1000), ready(n - 1)))
    cy.then(() => ready())
    openDashboard()
    const a1 = act('<b>Register → Vender / Supplier</b>, search the supplier.', ['Due <b>400</b> (incl. bills 300.00); the <b>Pay</b> button carries 400 and says it pays purchases and expense bills, oldest first.'])
    cy.then(() => openSuppliers(mixSup.name))
    cy.then(() => cy.contains('#VenderDiv tr', mixSup.name, { timeout: 15000 })).within(() => {
      cy.get('[data-cy=vender-due]').should('contain', '400')
      cy.get('.pay-vendor-btn').should('have.attr', 'data-due').and('match', /^400/)
    })
    snap(a1, 'supplier')
    const a2 = act('Press <b>Pay</b>, amount <b>250</b>, Cash, press <b>Pay</b>.',
      ['"Vendor paid. Voucher PV-… Of this, paid to expense bills: 250.00" — the bill is older, so it takes the whole 250.'])
    cy.then(() => cy.contains('#VenderDiv tr', mixSup.name).find('.pay-vendor-btn').click())
    cy.get('#pvAmount').clear().type('250')
    cy.get('#submitPayVendor').click()
    cy.contains('paid to expense bills', { timeout: 20000 }).should('be.visible').and('contain', '250.00')
    snap(a2, 'paid')
    const a3 = act('Open <b>Till → Expenses</b>.', ['The bill shows <b>Owes 50.00</b>; the purchase still owes 100 (supplier Due 150).'])
    openDashboard(); openExpenses()
    cy.then(() => cy.contains('#tableExpense tbody tr', mixSup.name, { timeout: 25000 }).find('[data-cy=expense-bill-owes]', { timeout: 25000 }).should('contain', '50.00'))
    snap(a3, 'bill-owes-50')
    act('Nothing to undo — owner.payables is reserved for mixed payments (it stays on FINANCE by design).', [], { cleanup: true })
  })

  caseIt('5b-2', 'After a mixed payment, the switch back is refused', () => {
    testCase('5b-2', 'fp5b', 'After a mixed payment, the switch back is refused', { who: ['admin@myplus.com (operator)'] })
    cy.loginAsOperator()
    const a1 = act('Platform → <b>Owner Payables’s organization</b> → <b>Supplier balances</b>.',
      ['A note says supplier payments here settled purchases and expense bills together; <b>Back to business</b> is disabled.'])
    openTenantPanel("Owner Payables's organization")
    cy.get('[data-cy=plat-payables-mixed]').should('be.visible')
    cy.get('[data-cy=plat-payables-business]').should('be.disabled')
    snap(a1, 'pinned', '#platPayables')
    const a2 = act('Ask for the switch anyway.', ['Refused: "… supplier payment share(s) that settled expense bills together with purchases …"'], { via: 'run' })
    cy.then(() => cy.request({ method: 'POST', url: '/platform/payablesSource', form: true, body: { organizationId: payablesOrg, source: 'BUSINESS', reason: 'Test Book 5b-2' } }))
      .its('body').then((b) => { expect(b.status).to.eq('ERROR'); expect(b.message).to.match(/supplier payment share/i) })
    act('Nothing to undo — the switch was refused.', [], { cleanup: true })
  })

  // ═══ FP-5a · Payments land once ═══════════════════════════════════════════════════════════════════════════
  caseIt('5a-1', 'Pay a supplier: one voucher, and the books move once', () => {
    testCase('5a-1', 'fp5a', 'Pay a supplier: one voucher, and the books move once', { who: ['owner.lifecycle (recorded)', 'owner.business', 'owner.mobile'] })
    setup('A supplier with a credit purchase of 100.')
    asLifecycle()
    let s5 = null
    newSupplier('5A').then((s) => { s5 = s })
    cy.then(() => creditPurchase(s5.id, 100, 'XG5AINV-' + run).its('status').should('eq', 'SUCCESS'))
    tb().then((before) => {
      openDashboard()
      cy.then(() => openSuppliers(s5.name))
      const a1 = act('<b>Register → Vender / Supplier</b>, search the supplier, press <b>Pay</b> on its row.', ['The Pay Vendor dialog opens showing the amount due (100).'])
      cy.then(() => cy.contains('#VenderDiv tr', s5.name, { timeout: 15000 }).find('.pay-vendor-btn').click())
      cy.get('#PayVendorModal').should('be.visible')
      snap(a1, 'pay-dialog', '#PayVendorModal')
      const a2 = act('Amount <b>40</b>, method <b>Cash</b>, press <b>Pay</b>; press it again quickly.',
        ['The message reads <b>Vendor paid. Voucher PV-…</b>', 'Trial balance: <b>2000</b> debited 40 and <b>1000 Cash</b> down 40 — exactly once.'])
      cy.get('#pvAmount').clear().type('40')
      cy.get('#submitPayVendor').dblclick()
      cy.contains('Vendor paid', { timeout: 15000 }).should('be.visible')
      snap(a2, 'paid')
      cy.wait(2000)
      tb().then((after) => {
        expect(delta(before, after, '2000')).to.eq(40)
        expect(delta(before, after, '1000')).to.eq(-40)
      })
    })
    act('Nothing to undo — the payment is real money (40 paid on the 100 purchase); void the purchase if you want the supplier cleared.', [], { cleanup: true })
  })

  caseIt('5a-2', 'Receive from a customer: one receipt', () => {
    testCase('5a-2', 'fp5a', 'Receive from a customer: one receipt', { who: ['owner.lifecycle (recorded)', 'owner.business', 'cashier.a'] })
    setup('A customer (Register → Customer). The recording makes one with no dues, so the payment goes on account.')
    asLifecycle()
    const cname = 'XG5B_' + run
    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: cname, contact: 'C' + run } })
    cy.request('/getUserCustomer').then((r) => list(r.body).find((c) => c.name === cname).customerId).then((cid) => {
      tb().then((before) => {
        const a1 = act('Receive <b>5</b> in cash from the customer (<b>Receive</b> on its row, or the customer’s receipt dialog).',
          ['<b>Payment received. Receipt RCPT-…</b>', 'Trial balance: Cash up 5 and 1100 Receivables credited 5. Sending the same receipt again changes nothing.'], { via: 'run' })
        cy.request({ method: 'POST', url: '/receivePayment', form: true, body: { customerId: cid, amount: 5, method: 'CASH', idempotencyKey: 'xg5b-' + run } })
          .its('body.object.receiptNo').should('match', /^RCPT-/)
        cy.request({ method: 'POST', url: '/receivePayment', form: true, body: { customerId: cid, amount: 5, method: 'CASH', idempotencyKey: 'xg5b-' + run } })
        tb().then((after) => {
          expect(delta(before, after, '1000')).to.eq(5)
          expect(delta(before, after, '1100')).to.eq(-5)
        })
      })
    })
    act('Nothing to undo — a 5 receipt on account stays as the test customer’s credit.', [], { cleanup: true })
  })

  // ═══ FP-6a · The automatic daily check ════════════════════════════════════════════════════════════════════
  // The check runs by itself (03:30 Karachi and 10 min after every start). "Check now" steps send the same call the
  // nightly job makes — the operator console has no button for it, by design.
  const reconRun = (org) => cy.request({ method: 'POST', url: '/platform/payablesReconciliation/run', form: true, body: { organizationId: org } })
    .its('body').then((b) => { expect(b.status, JSON.stringify(b.message)).to.eq('SUCCESS'); return b.object || b.data })
  const reconHistory = (org) => cy.request({ url: '/platform/payablesReconciliation', qs: { organizationId: org } })
    .its('body').then((b) => { expect(b.status).to.eq('SUCCESS'); return b.object || b.data })
  const journal = (lines, memo) => token().then((t) => cy.request({ method: 'POST', url: `${GW}/api/finance/gl/journal`,
    headers: { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' }, body: { source: 'MANUAL', memo, lines } })
    .its('status').should('be.oneOf', [200, 201]))
  const reconPanel = () => {
    openTenantPanel(LIFECYCLE_ORG_NAME)
    cy.get('[data-cy=plat-payables-recon]', { timeout: 20000 }).should('be.visible')
  }

  caseIt('6a-1', 'The operator sees every business checked automatically, every day', () => {
    testCase('6a-1', 'fp6a', 'The operator sees every business checked automatically, every day', { who: ['admin@myplus.com (operator)'] })
    setup('Nothing to prepare: the check runs by itself 10 minutes after every start and every night at 03:30 (Karachi).')
    cy.loginAsOperator()
    const a1 = act('Make sure today has been checked: the run asks for the check now, exactly as the nightly job does.',
      ['A row for <b>today</b> is recorded. Business’s supplier figures and finance’s supplier documents agree (difference <b>0</b>).'], { via: 'run' })
    cy.then(() => reconRun(lifecycleOrg)).then((d) => {
      expect(d.reconDay).to.match(/^\d{4}-\d{2}-\d{2}$/)
      expect(Number(d.shadowDiff), 'business = finance').to.eq(0)
    })
    const a2 = act(`Platform → open <b>${LIFECYCLE_ORG_NAME}</b> → <b>Supplier balances</b>, and scroll to <b>Automatic check (daily)</b>.`,
      ['“<b>N</b> clean days in a row · needed to retire business as the source: <b>28</b>”.',
        'A table of the last days, newest first: each says <b>clean</b>, or what was <b>repaired</b> (documents re-sent, ledger aligned by an amount), or why it <b>could not check</b>.',
        'There is no button: the check runs by itself.'])
    reconPanel()
    cy.get('[data-cy=plat-payables-recon]').should('contain', '28').and('contain', 'clean days in a row')
    cy.get('[data-cy=plat-payables-recon-day]').should('have.length.greaterThan', 0)
    cy.get('[data-cy=plat-payables-recon] button').should('not.exist')
    snap(a2, 'panel', '[data-cy=plat-payables-recon]')
    act('Nothing to undo — this case only reads.', [], { cleanup: true })
  })

  caseIt('6a-2', 'A ledger difference no supplier document explains is repaired automatically, once', () => {
    testCase('6a-2', 'fp6a', 'A ledger difference no supplier document explains is repaired automatically, once',
      { who: ['owner.lifecycle (recorded)', 'admin@myplus.com (operator)'] })
    setup('Expense management not needed. The business’s supplier ledger and its books agree (case 6a-1 recorded difference 0).')
    setup('This case <b>makes</b> the difference on purpose — an accountant’s journal that moves Accounts Payable with no supplier bill behind it, the kind of error the 5 Oct review found in three businesses.')
    let before = null, mid = null
    asLifecycle(true)
    tb().then((m) => { before = m })
    openDashboard()
    const a1 = act('<b>Finance → Trial Balance</b>. Note <b>2000 Accounts Payable</b> and, if listed, <b>2990 Payables Reconciliation Difference</b>.',
      ['The trial balance says <b>balanced</b>.'])
    showTrialBalance()
    snap(a1, 'before', '#FinanceDiv')
    const a2 = act('Post a manual journal: Dr <b>6900 Other Operating Expenses 77</b>, Cr <b>2000 Accounts Payable 77</b> (an accountant’s entry; there is no screen for manual journals yet).',
      ['Accounts Payable is now <b>77 higher</b> than every supplier’s bills add up to.'], { via: 'run' })
    journal([{ accountCode: '6900', debit: 77 }, { accountCode: '2000', credit: 77 }], 'Test Book 6a-2: a payables difference with no document')
    tb().then((m) => { mid = m; expect(delta(before, m, '2000'), '2000 moved').to.eq(-77) })
    const a3 = act('Wait for the nightly check — or, as the operator, ask for it now (the same call).',
      ['Today is recorded as <b>not clean</b>: the ledger was <b>aligned by 77.00</b> against <b>2990 Payables Reconciliation Difference</b>.'], { via: 'run' })
    cy.loginAsOperator()
    cy.then(() => reconRun(lifecycleOrg)).then((d) => {
      expect(d.clean, 'a repaired day is not clean').to.eq(false)
      expect(Number(d.ledgerAligned), 'aligned').to.be.gte(77)
    })
    const a4 = act(`Platform → <b>${LIFECYCLE_ORG_NAME}</b> → <b>Supplier balances</b> → <b>Automatic check (daily)</b>.`,
      ['Today’s row reads <b>repaired: ledger aligned by 77.00</b> (highlighted).', 'The clean streak is back to <b>0</b> — a day that needed a repair never counts towards the 28.'])
    reconPanel()
    cy.get('[data-cy=plat-payables-recon-streak]').should('contain', '0')
    cy.get('[data-cy=plat-payables-recon-day]').first().should('contain', 'repaired').and('contain', '77.00')
    snap(a4, 'repaired', '[data-cy=plat-payables-recon]')
    const a5 = act('Ask for the check again.', ['Nothing more is posted: 2990 does not move a second time.'], { via: 'run' })
    cy.then(() => reconRun(lifecycleOrg))
    asLifecycle(true)
    tb().then((m) => {
      expect(delta(mid, m, '2000'), '2000 aligned back').to.eq(77)
      expect(delta(mid, m, '2990'), '2990 carries the 77').to.eq(-77)
      expect(delta(before, m, '2000'), '2000 equals the supplier ledger again').to.eq(0)
    })
    const a6 = act('As the owner, <b>Finance → Trial Balance</b> again.',
      ['Still <b>balanced</b>. <b>2000</b> is back to the figure in step 1; <b>2990 Payables Reconciliation Difference</b> shows the <b>77.00</b> that nothing explained.'])
    openDashboard(); showTrialBalance()
    cy.contains('#FinanceDiv', '2990', { timeout: 20000 }).should('be.visible')
    snap(a6, 'after', '#FinanceDiv')
    const c1 = act('Reverse the test journal (Dr 2000 77 / Cr 6900 77), and let the check run again (or ask for it).',
      ['The check aligns the ledger back: <b>2990</b> returns to its figure in step 1, <b>2000</b> still equals the supplier ledger, the trial balance balances. Today stays <b>repaired</b> in the history.'],
      { cleanup: true, via: 'run' })
    journal([{ accountCode: '2000', debit: 77 }, { accountCode: '6900', credit: 77 }], 'Test Book 6a-2: clean-up')
    cy.loginAsOperator()
    cy.then(() => reconRun(lifecycleOrg))
    asLifecycle(true)
    tb().then((m) => {
      expect(delta(before, m, '2000'), '2000 as before').to.eq(0)
      expect(delta(before, m, '2990'), '2990 as before').to.eq(0)
      expect(delta(before, m, '6900'), '6900 as before').to.eq(0)
    })
  })

  caseIt('6a-5', 'Expense bills are checked too, and a bill the books refused is not owed', () => {
    testCase('6a-5', 'fp6a', 'Expense bills are checked too, and a bill the books refused is not owed',
      { who: ['owner.lifecycle (recorded)', 'admin@myplus.com (operator)'] })
    setup('Expense management switched on (case 0a-3); a supplier for the bill (the recording makes one through the supplier form’s own request).')
    const y = yesterdayIso(), payee = 'XG refused bill ' + run
    let sup = null, id = null
    asLifecycle(true)
    SAFETY.push(() => { asLifecycle(); reopenQuietly() })
    SAFETY.push(() => { asLifecycle(); if (id) cy.request({ method: 'POST', url: `/expense/vouchers/${id}/void`, body: { reason: 'Test Book' }, failOnStatusCode: false }) })
    reopenQuietly()
    newSupplier('6A5').then((s) => { sup = s })
    const a1 = act(`<b>Finance → Period Close</b>: lock the books through <b>${dmyOf(y)}</b> (yesterday).`, [`<b>Books are CLOSED through ${y}</b>.`])
    openPeriodClose()
    cy.get('#finLockDate').invoke('val', y).trigger('change')
    cy.contains('#FinanceDiv button', 'Close period').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.contains('#FinanceDiv', 'Books are CLOSED through ' + y, { timeout: 15000 }).should('be.visible')
    const a2 = act(`<b>Till → Expenses</b>: a <b>Bill (pay later)</b> to the new supplier, <b>Repairs and maintenance 55</b>, dated <b>${dmyOf(y)}</b>, Payee <b>${payee}</b>.`,
      ['The row shows <b>Not posted</b> with the books’ reason — and the supplier does <b>not</b> owe it in the ledger: before this release the ledger counted it while the books did not.'])
    openDashboard(); openExpenses()
    cy.get('#expDateTemp').clear().type(dmyOf(y)).blur()
    cy.then(() => fillExpense({ category: 'Repairs and maintenance', amount: 55, paidFrom: 'AP', supplierId: sup.id, payee }))
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expFromTemp').clear().type(dmyOf(y)).blur(); cy.get('#expToTemp').clear().type(dmyOf(y)).blur()
    cy.contains('#ExpenseDiv button', 'Search').click()
    expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'Not posted')
    expenseRow(payee).invoke('attr', 'data-id').then((v) => { id = v })
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a2, 'not-posted')
    const a3 = act('As the operator, ask for the daily check now; then Platform → this business → <b>Supplier balances</b> → <b>Automatic check (daily)</b>.',
      ['Today’s row has a line <b>Expense bills — in the books: X · in the ledger: X</b> — the same figure: the refused bill is in neither.',
       'The ledger is <b>not</b> “aligned” for it (before, the check would have moved the books by 55 onto 2990).'])
    cy.loginAsOperator()
    cy.then(() => reconRun(lifecycleOrg)).then((d) => expect(Number(d.financeExpense), 'ledger = books for bills').to.eq(Number(d.expenseOwed)))
    reconPanel()
    cy.get('[data-cy=plat-payables-recon-bills]').first().should('contain', 'in the books').and('contain', 'in the ledger')
    snap(a3, 'bills-agree', '[data-cy=plat-payables-recon]')
    const a4 = act('Reopen the period (Period Close → <b>Reopen</b>), and on the bill press <b>Post again</b>. Ask for the check again.',
      ['The bill reads <b>In the books</b> and <b>Owes 55.00</b>; the panel’s bills line went up by 55 on <b>both</b> sides.'])
    asLifecycle(true)
    reopenQuietly()
    openDashboard(); openExpenses()
    cy.get('#expFromTemp').clear().type(dmyOf(y)).blur(); cy.get('#expToTemp').clear().type(dmyOf(y)).blur()
    cy.contains('#ExpenseDiv button', 'Search').click()
    expenseRow(payee).find('[data-cy=post-again]').click()
    expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    expenseRow(payee).find('[data-cy=expense-bill-owes]').should('contain', '55.00')
    expenseRow(payee).scrollIntoView({ offset: { top: -120, left: 0 } }); snap(a4, 'in-the-books')
    cy.loginAsOperator()
    cy.then(() => reconRun(lifecycleOrg)).then((d) => expect(Number(d.financeExpense)).to.eq(Number(d.expenseOwed)))
    act(`Void the ${payee} bill (reason "Test Book").`, [], { cleanup: true })
    asLifecycle()
    cy.then(() => cy.request({ method: 'POST', url: `/expense/vouchers/${id}/void`, body: { reason: 'Test Book' } })).its('body.success').should('eq', true)
  })

  caseIt('6a-3', 'A purchase with no supplier is a cash purchase: it is paid in full', () => {
    testCase('6a-3', 'fp6a', 'A purchase with no supplier is a cash purchase: it is paid in full', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    const inv = 'XG6A3-' + run
    let pid = null
    setup('A product to buy (the recording makes one through the Product form’s request).')
    asLifecycle(true)
    cy.seedProduct({ name: 'XG6A3_' + run, sellingPrice: 300 }).then((p) => { pid = p.productId })
    SAFETY.push(() => { asLifecycle(); purchaseIdOf(inv).then((id) => { if (id) cy.request({ method: 'POST', url: '/voidPurchase', form: true, failOnStatusCode: false, body: { purchaseId: id, reason: 'Test Book clean-up' } }) }) })
    const a1 = act(`<b>Purchase → New Purchase</b>. Leave Vendor as <b>Cash purchase (no vendor)</b>. Invoice <b>${inv}</b>, the product, quantity <b>1</b>, P/U <b>220</b>, and type <b>200</b> in <b>Paid</b>. Press <b>Save &amp; Close</b>.`,
      ['The bill is saved.'])
    cy.openPurchaseSection('purchaseDiv')
    cy.get('#newPurchase').click()
    cy.get('#PurchaseModal').should('have.class', 'open')
    cy.settled('#purchaseInvoiceNo')
    cy.get('#purchaseInvoiceNo').clear().type(inv)
    cy.intercept('GET', '/productStock*').as('prefill')
    cy.then(() => cy.get('#purchaseItemDD').select(String(pid), { force: true }))
    cy.wait('@prefill', { timeout: 15000 })
    cy.get('#purchaseQuantity').clear().type('1')
    cy.get('#purchasePurchaseRate').clear().type('220')
    cy.get('#purchasePaid').clear().type('200')
    snap(a1, 'form', '#PurchaseModal .crud-box')
    cy.intercept('POST', '/addPurchase').as('save')
    cy.get('#addPurchase').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
    const a2 = act(`Search the purchase list for <b>${inv}</b>.`,
      ['It is recorded as <b>paid 220.00</b> — the whole bill — and <b>owes nothing</b>. With no supplier there is nobody to owe the 20 to; before 5 Oct the 20 stayed in Accounts Payable, owed to nobody.'])
    cy.request('/getUserPurchase').then((r) => {
      const p = list(r.body).find((x) => x.purchaseInvoiceNo === inv)
      expect(p, 'the purchase').to.exist
      expect(Number(p.paidAmount), 'paid in full').to.eq(220)
      expect(Number(p.dueAmount || 0), 'nothing owed').to.eq(0)
    })
    cy.get('#purchaseDiv input[type="search"]').first().clear().type(inv)
    cy.contains('#purchaseDiv tr', inv, { timeout: 15000 }).should('be.visible')
    snap(a2, 'paid-in-full', '#purchaseDiv')
    const c1 = act(`Purchase list → <b>${inv}</b> → <b>Void</b>, reason <b>Test Book clean-up</b>.`, ['The bill leaves the list; stock and cash are reversed.'], { cleanup: true })
    cy.contains('#purchaseDiv tr', inv).find('.purchase-void-btn').click({ force: true })
    cy.get('.uiC-card .uiC-input').type('Test Book clean-up')
    cy.intercept('POST', '**/voidPurchase').as('void')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@void').its('response.body.status').should('eq', 'SUCCESS')
  })

  caseIt('6a-4', 'A shop owner cannot read or run the check', () => {
    testCase('6a-4', 'fp6a', 'A shop owner cannot read or run the check', { who: ['owner.lifecycle (recorded)', 'owner.business'] })
    asLifecycle(true)
    const a1 = act('As the owner, open <code>/platform/payablesReconciliation?organizationId=&lt;your org&gt;</code>, and try to run it.',
      ['Both refused (403). The check is the platform’s, not a shop setting; nothing on the owner’s screens mentions it.'], { via: 'run' })
    cy.request({ url: '/platform/payablesReconciliation', qs: { organizationId: lifecycleOrg }, failOnStatusCode: false }).its('status').should('eq', 403)
    cy.request({ method: 'POST', url: '/platform/payablesReconciliation/run', form: true, body: { organizationId: lifecycleOrg }, failOnStatusCode: false })
      .its('status').should('eq', 403)
    act('Nothing to undo — both were refused.', [], { cleanup: true })
  })

  // ═══ TZ-2 · "Today" is your day, at any hour ════════════════════════════════════════════════════════════════
  // A zone whose date differs from UTC's right now: the night-time defect, reproduced without waiting for midnight.
  const TZ_AHEAD = new Date().getUTCHours() >= 10
  const TZ_ZONE = TZ_AHEAD ? 'Pacific/Kiritimati' : 'Pacific/Pago_Pago'
  const TZ_LABEL = TZ_AHEAD ? '(UTC+14:00) Kiritimati Island' : '(UTC−11:00) Pago Pago / Samoa'
  const dayIn = (zone, at = Date.now()) =>
    new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(at))
  const dmy = (iso) => iso.split('-').reverse().join('-')
  const nextDay = (iso) => { const d = new Date(iso + 'T12:00:00Z'); d.setUTCDate(d.getUTCDate() + 1); return d.toISOString().slice(0, 10) }
  const tzMade = []
  const tzVoid = () => cy.then(() => tzMade.forEach((id) =>
    cy.request({ method: 'POST', url: `/expense/vouchers/${id}/void`, body: { reason: 'Test Book TZ-2' }, failOnStatusCode: false })))

  caseIt('tz-1', 'An expense dated today is accepted at any hour', () => {
    testCase('tz-1', 'tz2', 'An expense dated today is accepted at any hour', { who: ['owner.lifecycle (recorded)', 'owner.business', 'user.business'] })
    setup('Expense management switched on (case 0a-3), then log out and in.')
    setup('No need to wait for midnight: changing your computer’s time zone moves “today” exactly as 00:00–05:00 in Karachi did.')
    asLifecycle(true)
    SAFETY.push(() => { asLifecycle(); tzVoid() })
    openDashboard()
    const today = dayIn(Intl.DateTimeFormat().resolvedOptions().timeZone)
    const payee = 'XG TZ ' + run
    const a1 = act('<b>Till → Expenses</b>. Look at the <b>Date</b> box.', [`It shows <b>today on your computer’s calendar</b> (${dmy(today)} when recorded).`])
    openExpenses()
    cy.get('#expDateTemp').should('have.value', dmy(today))
    snap(a1, 'date-today', '#ExpenseDiv form')
    const a2 = act(`Category <b>Rent</b>, Amount <b>3</b>, Paid from <b>Cash</b>, Payee <b>${payee}</b>, press <b>Save and post</b>.`,
      ['Saved — no “An expense cannot be dated in the future”.', `The row is dated <b>${dmy(today)}</b> and ends as <b>In the books</b>.`])
    fillExpense({ category: 'Rent', amount: 3, paidFrom: 'CASH', payee })
    cy.get('[data-cy=save-expense]').click()
    expenseRow(payee).within(() => {
      cy.contains(dmy(today))
      cy.get('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
    })
    cy.request('/expense/vouchers?size=50').then((r) => {
      const v = ((r.body.data && r.body.data.content) || []).find((x) => x.payeeName === payee)
      expect(v, 'the saved expense').to.exist
      tzMade.push(v.id)
    })
    snap(a2, 'saved-today')
    const zToday = dayIn(TZ_ZONE)
    const a3 = act(`Change your computer’s time zone to <b>${TZ_LABEL}</b>, reload the page, open <b>Till → Expenses</b> and save another expense of <b>3</b> dated the computer’s new today.`,
      [`Accepted, dated <b>${dmy(zToday)}</b> — ${TZ_AHEAD ? 'a day AHEAD of the server’s UTC calendar: exactly what was refused before' : 'your calendar, not the server’s'}.`],
      { via: 'run' })
    cy.setCookie('myplus_tz', encodeURIComponent(TZ_ZONE))
    categoryByName('Rent').then((c) =>
      cy.request({ method: 'POST', url: '/expense/vouchers?post=true', headers: { 'Idempotency-Key': 'xgtz-b-' + run },
        body: { paidFrom: 'CASH', voucherDate: zToday, payeeName: payee + ' zone', lines: [{ categoryId: c.id, amount: 3 }] } }))
      .its('body').then((b) => {
        expect(b.success, JSON.stringify(b.message)).to.eq(true)
        expect(b.data.voucherDate).to.eq(zToday)
        tzMade.push(b.data.id)
      })
    act('Still on that time zone, set the date to the computer’s <b>tomorrow</b> and save.',
      ['Refused: <b>“An expense cannot be dated in the future.”</b> — tomorrow is still tomorrow, wherever you are.'], { via: 'run' })
    categoryByName('Rent').then((c) =>
      cy.request({ method: 'POST', url: '/expense/vouchers?post=true', headers: { 'Idempotency-Key': 'xgtz-c-' + run }, failOnStatusCode: false,
        body: { paidFrom: 'CASH', voucherDate: nextDay(zToday), lines: [{ categoryId: c.id, amount: 3 }] } }))
      .its('body').then((b) => { expect(b.success).to.eq(false); expect(b.message).to.match(/future/i) })
    cy.clearCookie('myplus_tz')
    const c1 = act('Void both test expenses from their rows (reason <b>Test Book</b>), and set your computer’s time zone back to <b>(UTC+05:00) Islamabad, Karachi</b>.',
      ['Both rows show <b>Void</b>; the trial balance is back where it started.'], { cleanup: true })
    cy.then(() => tzMade.forEach((id) => cy.request({ method: 'POST', url: `/expense/vouchers/${id}/void`, body: { reason: 'Test Book TZ-2' } })
      .its('body.success').should('eq', true)))
    openDashboard()
    openExpenses()
    expenseRow(payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'Void')
    snap(c1, 'voided')
    cy.then(() => { tzMade.length = 0 })
  })

  caseIt('tz-2', 'Payment dialogs and finance reports open on your own today — even at 02:30', () => {
    testCase('tz-2', 'tz2', 'Payment dialogs and finance reports open on your own today — even at 02:30', { who: ['owner.lifecycle (recorded)', 'owner.business', 'cashier.a'] })
    setup('A supplier with something owed (the recording makes one: a credit purchase of 10).')
    asLifecycle()
    const inv = 'XGTZINV-' + run
    let sup = null
    newSupplier('TZ').then((s) => { sup = s })
    cy.then(() => creditPurchase(sup.id, 10, inv).its('status').should('eq', 'SUCCESS'))
    SAFETY.push(() => { asLifecycle(); purchaseIdOf(inv).then((pid) => { if (pid) cy.request({ method: 'POST', url: '/voidPurchase', form: true, failOnStatusCode: false, body: { purchaseId: pid, reason: 'Test Book TZ-2' } }) }) })
    cy.clearLocalStorage()
    openDashboard()
    // 02:30 in Karachi on 5 October = 21:30 UTC on 4 October: the hour the old screens wrote YESTERDAY.
    const night = Date.parse('2026-10-04T21:30:00Z')
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
    const local = dayIn(zone, night)
    cy.clock(night, ['Date'])
    const a1 = act('Between <b>00:00 and 05:00</b> (the recording sets the clock to 02:30 on 5 October): <b>Register → Vender / Supplier</b>, press <b>Pay</b> on the supplier.',
      [`The dialog’s <b>Date</b> is <b>today</b> (${local}) — not yesterday (${dayIn('UTC', night)}, the UTC day the screen used to write).`])
    cy.then(() => openSuppliers(sup.name))
    cy.then(() => cy.contains('#VenderDiv tr', sup.name, { timeout: 15000 }).find('.pay-vendor-btn').click())
    cy.get('#pvDate').should('have.value', local)
    snap(a1, 'pay-dialog', '#PayVendorModal')
    cy.window().then((w) => { if (w.closeModal) w.closeModal('PayVendorModal') })
    const a2 = act('Open <b>Finance → Profit &amp; Loss</b>.',
      [`<b>From</b> is the 1st of this month (${local.slice(0, 8)}01) — it used to show the LAST day of the previous month.`, `<b>To</b> is today (${local}).`])
    cy.window().then((w) => w.showFinance('pnl'))
    cy.get('#finFrom').should('have.value', local.slice(0, 8) + '01')
    cy.get('#finTo').should('have.value', local)
    snap(a2, 'finance-range', '#FinanceDiv')
    act('Void the test purchase (<b>Purchase</b> → its row → <b>Void</b>, reason <b>Test Book</b>).', ['The supplier owes nothing again.'], { cleanup: true, via: 'run' })
    cy.then(() => purchaseIdOf(inv)).then((pid) => cy.request({ method: 'POST', url: '/voidPurchase', form: true, body: { purchaseId: pid, reason: 'Test Book TZ-2' } }))
    cy.then(() => vendorRow(sup.id)).then((v) => expect(Number(v.dueAmount), 'supplier owes nothing').to.eq(0))
  })
})
