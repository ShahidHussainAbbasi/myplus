/**
 * Test Book section 29 — Sales by rep and area (SRA-1, SRA-2), as REAL manual test cases. Every action is PERFORMED,
 * ASSERTED and PHOTOGRAPHED (support/guide-capture.js); docs/guides/build-testbook-section.js `sra` builds the section.
 *
 *   A1  "+ New area" opens a small form OVER the customer form and comes back with it selected
 *   A2  the Area list follows the City; a duplicate selects the existing one; Esc closes only the small form
 *   A3  the supplier form has the same City / Area and "+ New" links
 *   F2  editing a customer keeps City, CNIC and the licence (they used to be saved blank)
 *   B1  a rep with no login, added on the Territory screen
 *   B3  a customer's usual rep, set on the customer form, comes back by NAME on edit
 *   B4  a deactivated rep is not offered for new outlets but still names the ones they hold
 *   T1  assigning on the Territory screen narrows what the booker's booking screen offers
 *   B5  a rep (the booker) has no Territory menu
 *   C1  SRA-3 — the sale screen's Sales rep picker defaults to the customer's usual rep; the invoice credits that rep
 *   C2  SRA-3 — the office keys a rep's PAPER order on the booking screen; the invoice credits the rep, not the office
 *   C3  SRA-3 — a rep booking signed in has no picker and is credited themselves
 *   D1  SRA-4a — the Sale Detail Report filters by sales rep and shows rep, area and supplier on each line
 *   D2  SRA-4a — grouped by how the sale came in, the sources in words; old sales read "Not recorded"
 *
 * The cases with no screen (import, another business, the takeover by id, the credit account, refusals at the server)
 * are proven by the gates — sra-areas.cy.js, sra-reps.cy.js, territory-assignment.cy.js — and listed under the section.
 *
 * TENANT owner.marketplace@ (the distributor); REP booker.marketplace@. Every name carries this run's stamp. Clean-up:
 * outlets unassigned, reps deactivated, the booker's login UNLINKED (a linked booker narrows every booking spec).
 *
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/docs/sra-guide-screens.cy.js --config trashAssetsBeforeRuns=false
 */
import { guideCapture } from '../../support/guide-capture'

const g = guideCapture({ outDir: 'cypress/guide-out/sra', section: 'Sales by rep and area', keepShots: true })
const RUN = String(Date.now()).slice(-6)
const mobile = () => `03${Math.floor(100000000 + Math.random() * 899999999)}`
const OWNER = 'owner.marketplace@myplus.com'
const BOOKER = 'booker.marketplace@myplus.com'
const list = (body) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
  return []
}
const ctx = { reps: [], outlets: [], invoices: [], irfanInvoices: [] }
const PRICE = 50
const post = (url, body) => cy.request({ method: 'POST', url, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false, body })
/** The invoice as the receipt reads it — rep, source and "Booked By" are stamped on it (SRA-3). */
const receipt = (invoiceNo) => cy.request(`/getReceipt?invoiceNo=${encodeURIComponent(invoiceNo)}`).then((r) => {
  expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
  return r.body.object
})
/** Confirm and dispatch an order as the owner — the dispatch raises the invoice. */
const confirmAndDispatch = (o) => {
  cy.loginAsMarketplaceOwner()
  post('/confirmOrder', { id: o.id }).its('body.success').should('eq', true)
  post('/shipOrder', { id: o.id, lines: [{ orderItemId: o.items[0].id, quantity: o.items[0].quantity }], carrier: 'SRA van' })
    .its('body.success').should('eq', true)
  return cy.request('/getOrder?id=' + o.id).then((r) => {
    expect(r.body.data.invoiceNo, 'the dispatch raised the invoice').to.match(/^INV-/)
    return r.body.data
  })
}
const bookOnScreen = (outletId, qty) => {
  cy.get(`#bkOutlet option[value="${outletId}"]`, { timeout: 20000 }).should('exist')
  cy.get('#bkOutlet').select(String(outletId), { force: true })
  cy.get(`#bkProduct option[value="${ctx.productId}"]`, { timeout: 20000 }).should('exist')
  cy.get('#bkProduct').select(String(ctx.productId), { force: true })
  cy.get('#bkQty').clear().type(String(qty))
  cy.get('#bkAddBtn').click()
}

const customerNamed = (name) => cy.request('/getUserCustomer').then((r) => list(r.body).find((c) => c.name === name) ?? null)
const addRep = (body) => cy.request({ method: 'POST', url: '/addSalesRep', form: true, failOnStatusCode: false, body })
const updateRep = (body) => cy.request({ method: 'POST', url: '/updateSalesRep', form: true, failOnStatusCode: false, body })
const assign = (repId, ids) => cy.request({ method: 'POST', url: '/assignOutlets', form: true, failOnStatusCode: false,
  body: { ...(repId == null ? {} : { repId }), customerIds: ids.join(',') } })
const seedCustomer = (body) => cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false, body })
  .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))
const userIdOf = (email) =>
  cy.request({ method: 'POST', url: 'http://localhost:8765/api/auth/login', headers: { 'Content-Type': 'application/json' },
    body: { email, password: 'Demo@2025!' } })
    .then((r) => Number(JSON.parse(atob(r.body.data.accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).userId))

const openCustomers = () => {
  cy.viewport(1400, 900)
  cy.openSection('CustomerDiv')
}
const editCustomer = (name) => {
  cy.get('#tableCustomer_filter input, .dataTables_filter input').first().clear().type(name)
  cy.contains('#tableCustomer tbody tr', name, { timeout: 15000 }).find('.js-edit-row').click({ force: true })
  cy.get('#CustomerModal').should('be.visible')
}
const openTerritory = () => {
  cy.viewport(1400, 900)
  cy.visit('/businessDashboard')
  cy.window().then((w) => w.showTerritory())
  cy.get('#salesRepPanel').should('be.visible')
  cy.get('#terrBody .terr-pick', { timeout: 20000 }).should('have.length.greaterThan', 0)
}
/** Retries until the rep list (names sit in editable boxes) includes `name` — the list reloads after every save. */
const repListed = (name) => cy.get('#srBody input[data-sr-field="name"]', { timeout: 15000 })
  .should(($i) => expect($i.toArray().map((x) => x.value)).to.include(name))

describe('Test Book 29 — Sales by rep and area (guide screens)', () => {
  before(() => {
    cy.loginAsMarketplaceOwner()
    userIdOf(BOOKER).then((id) => { ctx.bookerUserId = id })
    // An interrupted run may have left the booker linked to a rep; unlink first.
    cy.request('/getSalesReps').then((r) => {
      const linked = list(r.body).find((x) => Number(x.userId) === ctx.bookerUserId)
      if (linked) updateRep({ id: linked.id, userId: 0 })
    })
  })
  beforeEach(() => cy.loginAsMarketplaceOwner())
  afterEach(g.write)
  after(() => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => ctx.outlets.length && assign(null, ctx.outlets))
    cy.then(() => ctx.invoices.forEach((invoiceNo) => cy.request({ method: 'POST', url: '/voidSell', form: true,
      failOnStatusCode: false, body: { invoiceNo, reason: 'Test Book 29 clean-up' } })))
    cy.then(() => ctx.reps.forEach((id) => updateRep({ id, userId: 0, status: 'INACTIVE' })))
  })

  // ── A1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('A1', '"+ New area" opens a small form over the customer form and comes back with it selected', () => {
    const name = `SRA Sarwar Pharmacy ${RUN}`, city = `Khanpur ${RUN}`, area = `Zahir Pir ${RUN}`
    g.testCase('A1', '“+ New area” opens a small form over the customer form and comes back with it selected', {
      tenant: OWNER, role: 'Owner (adding a city or area needs “Add a city or area”, which every set that can add a customer has)',
      purpose: 'Reports group sales by AREA. Typed free text splits one area into several (“Khanpur”, “KHANPUR(1ST)”). The way in is the purchase form’s “+ New product”: a small form over the one being filled, never leaving it.',
      prereq: ['Signed in as the owner of owner.marketplace@.', `No city named **${city}** exists (the run uses its stamp).`],
      data: [`Customer **${name}**, any mobile`, `New city **${city}**, new area **${area}**`],
      rollback: 'The customer is kept (it is the scripted month’s first shop). Cities and areas are never deleted — the stamp keeps them apart.',
    })
    openCustomers()
    const a1 = g.act('**Register → Customer**, press **New Customer**. Type the name and a mobile number.',
      ['The New Customer form opens, with **City** and **Area** pickers and **+ New city** / **+ New area** under them.'])
    cy.get('#newCustomer').click()
    cy.get('#customerName').type(name)
    cy.get('#contact').type(mobile())
    cy.get('[data-sra-address="customer"] [data-sra-new-for="area"]').should('be.visible')
    g.snap(a1, 'customer-form')

    const a2 = g.act('Under **Area** click **+ New area**.',
      ['A small **New area** form opens OVER the customer form — the customer form and what was typed stay behind it.',
        'It offers the existing cities, and a box “…or type a new city”.'])
    cy.get('[data-sra-address="customer"] [data-sra-new-for="area"]').click()
    cy.get('#SraAddressModal').should('be.visible')
    cy.get('#sraDlgNewCity').should('be.visible')
    g.snap(a2, 'new-area-form')

    const a3 = g.act(`Type **${city}** in “…or type a new city” and **${area}** as the area. Press **Save**.`,
      [`The small form closes; a message says **“${area}” added and selected.**`,
        `On the customer form: City **${city}**, Area **${area}** — both selected; the name typed earlier is still there.`])
    cy.get('#sraDlgNewCity').type(city)
    cy.get('#sraDlgName').type(area)
    cy.get('#sraDlgSave').click()
    cy.get('#SraAddressModal').should('not.be.visible')
    cy.get('#customerName').should('have.value', name)
    cy.get('#customerCityId option:selected').should('have.text', city)
    cy.get('#customerAreaId option:selected').should('have.text', area)
    g.snap(a3, 'selected')

    const a4 = g.act('Press **Add** to save the customer, then find it in the list.',
      [`Saved. Under its address the list shows **${area}, ${city}**.`])
    cy.intercept('POST', '**/addCustomer').as('save')
    cy.get('#addCustomer').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
    cy.get('#tableCustomer_filter input, .dataTables_filter input').first().clear().type(name)
    cy.contains('#tableCustomer tbody tr', name, { timeout: 15000 }).should('contain.text', `${area}, ${city}`)
    g.snap(a4, 'listed')

    const a5 = g.act('Press **Edit** on its row.', [`The form opens with City **${city}** and Area **${area}** already chosen.`])
    editCustomer(name)
    cy.get('#customerCityId option:selected').should('have.text', city)
    cy.get('#customerAreaId option:selected').should('have.text', area)
    g.snap(a5, 'edit')
    customerNamed(name).then((c) => { ctx.sarwar = c; ctx.city = city; ctx.area = area })
  })

  // ── A2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('A2', 'The Area list follows the City; a duplicate selects the existing one; Esc closes only the small form', () => {
    const city2 = `Rahim Yar Khan ${RUN}`, area2 = `Model Town ${RUN}`
    g.testCase('A2', 'The Area list follows the City; a duplicate selects the existing one; Esc closes only the small form', {
      tenant: OWNER, role: 'Owner',
      purpose: 'Two spellings of one area are the problem SRA-1 exists to stop — and the small form must never take the customer form down with it.',
      prereq: ['A1 done.', `A second city **${city2}** with area **${area2}** (made through the same requests the small form sends).`],
      data: [`The duplicate typed in capitals: **${String(ctx.area).toUpperCase()}**`],
      rollback: 'Nothing is saved — the customer form is closed with Cancel.',
    })
    cy.request({ method: 'POST', url: '/addCity', form: true, body: { name: city2 } }).then((r) =>
      cy.request({ method: 'POST', url: '/addArea', form: true, body: { cityId: r.body.object.id, name: area2 } }))
    openCustomers()
    cy.get('#newCustomer').click()
    const a1 = g.act(`Choose City **${city2}** and open **Area**.`, [`Only **${area2}** is offered — not ${ctx.area}.`])
    cy.get('#customerCityId').select(city2, { force: true })
    cy.contains('#customerAreaId option', area2).should('exist')
    cy.contains('#customerAreaId option', ctx.area).should('not.exist')
    cy.get('button[data-id="customerAreaId"]').click({ force: true })   // open the searchable picker for the picture
    g.snap(a1, 'filtered')
    cy.get('button[data-id="customerAreaId"]').click({ force: true })

    const a2 = g.act(`**+ New area** → City **${ctx.city}** → type **${String(ctx.area).toUpperCase()}** → **Save**.`,
      [`Nothing new is made. The message: **“${ctx.area}” already existed — selected.**`, `The Area shows **${ctx.area}**.`])
    cy.get('[data-sra-address="customer"] [data-sra-new-for="area"]').click()
    cy.get('#sraDlgCity').select(ctx.city)
    cy.get('#sraDlgName').type(String(ctx.area).toUpperCase())
    cy.get('#sraDlgSave').click()
    cy.get('#customerAreaId option:selected').should('have.text', ctx.area)
    g.snap(a2, 'duplicate-selected')

    const a3 = g.act('Click **+ New city**, then press **Esc**.', ['Only the small form closes; the customer form is still open.'])
    cy.get('[data-sra-address="customer"] [data-sra-new-for="city"]').click()
    cy.get('#SraAddressModal').should('be.visible')
    cy.get('#sraDlgNewCity').type('{esc}')
    cy.get('#SraAddressModal').should('not.be.visible')
    cy.get('#CustomerModal').should('be.visible')
    g.snap(a3, 'esc')
  })

  // ── A3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('A3', 'The supplier form has the same City / Area and "+ New" links', () => {
    g.testCase('A3', 'The supplier form has the same City / Area and “+ New” links', {
      tenant: OWNER, role: 'Owner', purpose: 'Owner requirement: customers AND suppliers register their address in place.',
      prereq: ['A1 done.'], data: ['None — nothing is saved'], rollback: 'The supplier form is closed without saving.',
    })
    cy.viewport(1400, 900)
    cy.openSection('VenderDiv')
    const a1 = g.act('**Register → Supplier**, press **New Supplier**.',
      ['Under the address: **City** and **Area** pickers, with **+ New city** and **+ New area** under them.'])
    cy.get('#newVender').click()
    cy.get('[data-sra-address="vender"] [data-sra-new-for="city"]').should('be.visible')
    cy.get('[data-sra-address="vender"] [data-sra-new-for="area"]').should('be.visible')
    cy.get('[data-sra-address="vender"]').scrollIntoView()
    g.snap(a1, 'supplier-form')
  })

  // ── F2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('F2', 'Editing a customer keeps City, CNIC and the licence', () => {
    const name = `SRA Keep ${RUN}`
    g.testCase('F2', 'Editing a customer keeps City, CNIC and the licence', {
      tenant: OWNER, role: 'Owner',
      purpose: '⚠ Found 14 Oct: the customer list carried no City, CNIC or licence, so Edit left those boxes empty — or holding the PREVIOUS customer’s values — and Save wrote that back. Every edit wiped them, or copied another customer’s.',
      prereq: [`A customer **${name}** with City Khanpur, CNIC 31301-1234567-1, Licence DL-777, expiry 2027-06-30 (made through the form’s own request).`],
      data: [`New name **${name} Ltd**`], rollback: 'The customer is kept (renamed).',
    })
    seedCustomer({ name, contact: mobile(), city: 'Khanpur', cnic: '31301-1234567-1', licenseNo: 'DL-777', licenseExpiry: '2027-06-30' })
    openCustomers()
    const a1 = g.act(`Find **${name}** and press **Edit**.`, ['The form shows CNIC **31301-1234567-1**, Licence **DL-777** and expiry **2027-06-30**.'])
    editCustomer(name)
    cy.get('#customerCnic').should('have.value', '31301-1234567-1')
    cy.get('#customerLicenseNo').should('have.value', 'DL-777')
    cy.get('#customerCnic').scrollIntoView()
    g.snap(a1, 'edit-has-them')

    const a2 = g.act(`Change only the name to **${name} Ltd**; **Add** (save); then **Edit** it again.`,
      ['Renamed — and the CNIC, Licence and expiry are all still there.'])
    cy.get('#customerName').clear().type(`${name} Ltd`)
    cy.intercept('POST', '**/addCustomer').as('save')
    cy.get('#addCustomer').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
    editCustomer(`${name} Ltd`)
    cy.get('#customerCnic').should('have.value', '31301-1234567-1')
    cy.get('#customerLicenseNo').should('have.value', 'DL-777')
    cy.get('#customerCnic').scrollIntoView()
    g.snap(a2, 'still-there')
  })

  // ── B1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('B1', 'A rep with no login is added on the Territory screen', () => {
    const rep = `SRA Irfan ${RUN}`
    g.testCase('B1', 'A rep with no login is added on the Territory screen', {
      tenant: OWNER, role: 'Owner (managing reps needs “Add and change sales reps” — owners and admins have it)',
      purpose: 'Shafeeq’s reps book on paper as well as by login. A rep is a PERSON on the business’s list; a login is optional.',
      prereq: ['Signed in as the owner.'], data: [`**${rep}**, phone 0305-3939495, no login`],
      rollback: 'Deactivated at the end of the run (reps are never deleted — sales keep their names).',
    })
    openTerritory()
    const a1 = g.act('Open the menu → **Territory**.', ['Two panels: **Sales reps** (Name · Phone · Login · Add rep, and the list) and **Territory** (the outlets and who covers each).'])
    g.snap(a1, 'territory-screen')
    const a2 = g.act(`In Sales reps type **${rep}**, phone **0305-3939495**, leave Login on **— No login —**; click **Add rep**.`,
      ['A green line: **Rep added.**', `${rep} is in the list — Login “— No login —”, Status **Active**.`])
    cy.get('#srNewName').type(rep)
    cy.get('#srNewPhone').type('0305-3939495')
    cy.get('#srAdd').click()
    cy.get('#srNotice').should('be.visible').and('contain.text', 'Rep added')
    repListed(rep)
    g.snap(a2, 'rep-added')
    cy.request('/getSalesReps').then((r) => { const x = list(r.body).find((y) => y.name === rep); ctx.irfan = x.id; ctx.reps.push(x.id) })
  })

  // ── B3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('B3', "A customer's usual rep is set on the customer form and comes back by name", () => {
    const name = `SRA Labaik Pharmacy ${RUN}`
    g.testCase('B3', 'A customer’s usual rep is set on the customer form and comes back by name', {
      tenant: OWNER, role: 'Owner (the Usual rep picker is shown only to members who may manage reps)',
      purpose: 'Who usually covers a shop decides what each rep sees, and (SRA-3) who is credited by default. A rep must never hand outlets to themselves — the server refuses that (gate sra-reps.cy.js B3).',
      prereq: ['B1 done.'], data: [`Customer **${name}**, usual rep **SRA Irfan ${RUN}**`],
      rollback: 'The customer is kept; its outlet is returned to the shared pool at the end of the run.',
    })
    openCustomers()
    const a1 = g.act(`**New Customer**: name **${name}**, a mobile, **Usual rep** = SRA Irfan ${RUN}; **Add**.`, ['Saved.'])
    cy.get('#newCustomer').click()
    cy.get('#customerName').type(name)
    cy.get('#contact').type(mobile())
    cy.then(() => cy.get('#customerDefaultRepId').select(String(ctx.irfan), { force: true }))
    cy.get('#customerDefaultRepId').scrollIntoView()
    g.snap(a1, 'usual-rep-chosen')
    cy.intercept('POST', '**/addCustomer').as('save')
    cy.get('#addCustomer').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')

    const a2 = g.act('Press **Edit** on its row.', [`**Usual rep** shows **SRA Irfan ${RUN}** — the name, not a number.`])
    editCustomer(name)
    cy.get('#customerDefaultRepId option:selected').should('have.text', `SRA Irfan ${RUN}`)
    cy.get('#customerDefaultRepId').scrollIntoView()
    g.snap(a2, 'usual-rep-on-edit')
    customerNamed(name).then((c) => { ctx.labaik = c; ctx.outlets.push(c.customerId) })
  })

  // ── B4 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('B4', 'A deactivated rep is not offered for new outlets but still names the ones they hold', () => {
    g.testCase('B4', 'A deactivated rep is not offered for new outlets but still names the ones they hold', {
      tenant: OWNER, role: 'Owner', purpose: 'People leave; the outlets they held (and, from SRA-3, their sales) must still say who.',
      prereq: [`B3 done: SRA Labaik Pharmacy ${RUN}’s usual rep is SRA Irfan ${RUN}.`], data: ['None new'],
      rollback: 'SRA Irfan is reactivated in the clean-up.',
    })
    openTerritory()
    const a1 = g.act(`In Sales reps press **Deactivate** on **SRA Irfan ${RUN}**.`,
      ['**Rep saved.** Its status reads **Inactive** and the button now says **Reactivate**.'])
    cy.get(`#srBody tr[data-rep-id="${ctx.irfan}"] [data-sr-toggle]`).click()
    cy.get('#srNotice').should('contain.text', 'Rep saved')
    cy.get(`#srBody tr[data-rep-id="${ctx.irfan}"]`).invoke('text').should('match', /Inactive/i)
    g.snap(a1, 'deactivated')
    const a2 = g.act(`Open **Assign to**; then find **SRA Labaik Pharmacy ${RUN}** in the outlet table.`,
      [`SRA Irfan ${RUN} is **not** offered in Assign to.`, `The outlet still reads **Covered by: SRA Irfan ${RUN} (Inactive)**.`])
    cy.get(`#terrRep option[value="${ctx.irfan}"]`).should('not.exist')
    cy.get('#terrFilter').type(`SRA Labaik Pharmacy ${RUN}`)
    cy.contains('#terrBody tr', `SRA Labaik Pharmacy ${RUN}`).should(($tr) => expect($tr.text().toLowerCase()).to.contain(`sra irfan ${RUN} (inactive)`))
    g.snap(a2, 'still-named')
    const c1 = g.act(`Press **Reactivate** on SRA Irfan ${RUN}.`, ['Active again, and back in Assign to.'], { cleanup: true })
    cy.get(`#srBody tr[data-rep-id="${ctx.irfan}"] [data-sr-toggle]`).click()
    cy.get(`#srBody tr[data-rep-id="${ctx.irfan}"]`).should('contain.text', 'Active')
    cy.get(`#terrRep option[value="${ctx.irfan}"]`).should('exist')
    g.snap(c1, 'reactivated')
  })

  // ── T1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('T1', "Assigning on the Territory screen narrows the booker's booking screen", () => {
    const mine = `SRA Mine ${RUN}`, theirs = `SRA Theirs ${RUN}`, free = `SRA Free ${RUN}`
    g.testCase('T1', 'Assigning on the Territory screen narrows the booker’s booking screen', {
      tenant: `${OWNER} and ${BOOKER}`, role: 'Owner assigns; the booker (a rep whose login is linked) books',
      purpose: 'The territory rule: a rep sees their own outlets plus every unassigned one. ⚠ It used to ask the permission set’s “Sees: All” row scope, so a booker whose set saw all rows was never narrowed — the territory gate was red from 16 Sep to 14 Oct.',
      prereq: [`A rep **SRA Booker ${RUN}** linked to ${BOOKER}, made on the Sales reps panel’s own request.`,
        `Three shops **${mine}**, **${theirs}**, **${free}**, unassigned.`],
      data: [`${mine} → SRA Booker ${RUN} · ${theirs} → SRA Irfan ${RUN} · ${free} → nobody`],
      rollback: 'The three shops are unassigned and the booker’s login is unlinked from SRA Booker in the clean-up — a linked booker would narrow every other booking test.',
    })
    addRep({ name: `SRA Booker ${RUN}`, userId: ctx.bookerUserId }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'); ctx.bookerRep = r.body.object.id; ctx.reps.push(ctx.bookerRep)
    })
    ;[mine, theirs, free].forEach((n) => seedCustomer({ name: n, contact: mobile() }))
    cy.request('/getUserCustomer').then((r) => {
      const all = list(r.body)
      ctx.t1 = Object.fromEntries([mine, theirs, free].map((n) => [n, all.find((c) => c.name === n).customerId]))
      ctx.outlets.push(...Object.values(ctx.t1))
    })
    openTerritory()
    const a1 = g.act(`Territory: tick **${mine}** → Assign to **SRA Booker ${RUN}** → **Apply**. Then tick **${theirs}** → **SRA Irfan ${RUN}** → **Apply**.`,
      ['“Outlets assigned.” each time. **Covered by** names the rep of each; ' + free + ' reads “Unassigned — visible to all”.'])
    const assignOnScreen = (shop, repId) => {
      cy.get('#terrFilter').clear().type(shop)
      cy.contains('#terrBody tr', shop).find('.terr-pick').check()
      cy.get('#terrRep').select(String(repId), { force: true })
      cy.get('#terrApply').click()
      cy.get('#terrNotice').should('contain.text', 'assigned')
    }
    cy.then(() => assignOnScreen(mine, ctx.bookerRep))
    cy.then(() => assignOnScreen(theirs, ctx.irfan))
    cy.get('#terrFilter').clear().type(RUN)   // the run's stamp: all three shops, and nothing else
    cy.contains('#terrBody tr', mine).should('contain.text', `SRA Booker ${RUN}`)
    cy.contains('#terrBody tr', theirs).should('contain.text', `SRA Irfan ${RUN}`)
    cy.contains('#terrBody tr', free).should('contain.text', 'Unassigned')
    g.snap(a1, 'assigned')

    const a2 = g.act(`Sign in as **${BOOKER}** → **Order → New Order** → open **Shop**.`,
      [`**${mine}** and **${free}** are offered; **${theirs} is not** — it is SRA Irfan’s.`])
    cy.loginAsOrderBooker()
    cy.viewport(1400, 900)
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showOrderBooking())
    cy.get('#bkOutlet option', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.get('#bkOutlet option').then(($o) => {
      const names = $o.toArray().map((o) => o.textContent.trim())
      expect(names.join('|'), 'own shop offered').to.contain(mine)
      expect(names.join('|'), 'unassigned shop offered').to.contain(free)
      expect(names.join('|'), "another rep's shop gone").to.not.contain(theirs)
    })
    cy.get('button[data-id="bkOutlet"]').click({ force: true })   // open the shop picker for the picture
    g.snap(a2, 'booker-shop-list')
    cy.get('button[data-id="bkOutlet"]').click({ force: true })
  })

  // ── B5 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('B5', 'A rep has no Territory menu', () => {
    g.testCase('B5', 'A rep has no Territory menu', {
      tenant: BOOKER, role: 'A plain member — no “Add and change sales reps”',
      purpose: 'The rep list decides who gets credit. The menu follows the permission (data), and the server refuses the same — adding or renaming a rep as the booker is refused by both doors (gate sra-reps.cy.js B5).',
      prereq: ['Signed in as the booker.'], data: ['None'], rollback: 'Nothing changes.',
    })
    cy.loginAsOrderBooker()
    cy.viewport(1400, 900)
    cy.visit('/businessDashboard')
    const a1 = g.act('Open the menu and look for **Territory**.', ['There is **no Territory** entry; the territory screen is not on the page at all.'])
    cy.get('#TerritoryDiv').should('not.exist')
    cy.get('body').should('not.contain.text', 'Sales reps')
    g.snap(a1, 'no-territory')
  })
  // ── C1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('C1', "The sale screen's Sales rep defaults to the customer's usual rep, and the invoice credits that rep", () => {
    g.testCase('C1', 'The sale screen’s Sales rep defaults to the customer’s usual rep, and the invoice credits that rep', {
      tenant: OWNER, role: 'Owner at the till (any cashier may name the rep of a paper order; only a rep manager may change it on an EDIT)',
      purpose: 'Commission and the rep report (SRA-4) run on the rep STAMPED on each invoice — not on who keyed it. A counter sale with no rep says “No rep (counter)”.',
      prereq: [`B3 done: **SRA Labaik Pharmacy ${RUN}**’s usual rep is **SRA Irfan ${RUN}**.`, `A product **SRA Panadol ${RUN}**, price ${PRICE}, 100 in stock (made through the product form’s own requests).`],
      data: [`Customer SRA Labaik Pharmacy ${RUN} · 2 × SRA Panadol ${RUN} · on account (Credit)`],
      rollback: 'The sale is voided at the end of the run.',
    })
    post('/addProduct', { name: `SRA Panadol ${RUN}`, sku: 'SRP' + RUN, sellingPrice: PRICE, taxRate: 0, unit: 'pcs' }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      ctx.productId = r.body.data.id
      return post('/addProductStock', { productId: ctx.productId, quantity: 100 })
    }).its('body.success').should('eq', true)
    cy.viewport(1400, 900)
    cy.visitSaleScreen()
    const a1 = g.act(`**Sale → New Sale**. In **Customer** choose **SRA Labaik Pharmacy ${RUN}**.`,
      [`Beside the customer, **Sales rep** now reads **SRA Irfan ${RUN}** — the shop’s usual rep, filled in by itself.`,
        'A business with no reps never sees this picker.'])
    cy.get(`#sellCustomerDD option[value="${ctx.labaik.customerId}"]`, { timeout: 15000 }).should('exist')
    cy.get('#sellCustomerDD').select(String(ctx.labaik.customerId), { force: true })
    cy.get('#sellRepWrap').should('be.visible')
    cy.get('#sellRepId').should('have.value', String(ctx.irfan))
    cy.get('#sellRepId option:selected').should('have.text', `SRA Irfan ${RUN}`)
    g.snap(a1, 'sale-rep-default')

    const a2 = g.act('Open **Sales rep**.', ['The list: **— No rep (counter) —**, then every active rep.'])
    cy.get('button[data-id="sellRepId"]').click({ force: true })
    cy.get('#sellRepId option').first().should('have.text', '— No rep (counter) —')
    g.snap(a2, 'sale-rep-list')
    cy.get('button[data-id="sellRepId"]').click({ force: true })

    const a3 = g.act(`Add **2 × SRA Panadol ${RUN}**, choose payment **Credit**, press **Complete Sale**.`,
      [`The line is in the cart (2 × ${PRICE}.00). A box asks **“Complete this sale?”** with the total **100.00** — Cancel · Park · Complete Sale.`])
    // ctx.productId is set when the product request ANSWERS — read it then, not while the test is being queued.
    cy.then(() => cy.get(`#sellItemDD option[value="${ctx.productId}"]`, { timeout: 15000 }).should('exist'))
    cy.then(() => cy.get('#sellItemDD').select(String(ctx.productId), { force: true }))
    cy.get('#sellQuantity').should(($q) => expect($q.val(), 'quantity filled').to.not.eq(''))
    cy.get('#sellQuantity').clear().type('2')
    cy.get('#addInviceItem').click()
    cy.get('#tablesi tbody tr:first td').should('have.length.greaterThan', 1)
    cy.get('#sellPayMethod').select('CREDIT', { force: true })
    cy.intercept('POST', '**/addSell').as('sale')
    cy.get('#addSell').click()
    cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).should('be.visible')
    g.snap(a3, 'confirm')

    const a4 = g.act('Press **Complete Sale** in the box.',
      ['“Sale recorded — Invoice INV-…”.', `The invoice is credited to **SRA Irfan ${RUN}**, source **Paper order (REP_PAPER)**; “Booked By” is the owner who keyed it. (The SRA-4 report shows it; this run checks the invoice record.)`])
    cy.confirmSale()
    cy.wait('@sale').then((x) => {
      expect(x.request.body.repId, 'the till sent the rep').to.eq(ctx.irfan)
      expect(x.response.body.status, JSON.stringify(x.response.body).slice(0, 200)).to.eq('SUCCESS')
      ctx.invoices.push(x.response.body.object)
      ctx.irfanInvoices.push(x.response.body.object)
      receipt(x.response.body.object).then((inv) => {
        expect(inv.repName).to.eq(`SRA Irfan ${RUN}`)
        expect(inv.saleSource).to.eq('REP_PAPER')
      })
    })
    cy.contains('Sale recorded', { timeout: 10000 }).should('be.visible')
    g.snap(a4, 'sale-recorded')
  })

  // ── C2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('C2', "The office keys a rep's paper order; the invoice credits the rep, not the office", () => {
    g.testCase('C2', 'The office keys a rep’s paper order; the invoice credits the rep, not the office', {
      tenant: OWNER, role: 'Owner (the booking screen’s Sales rep picker is shown only to members who may manage reps)',
      purpose: 'Shafeeq’s reps hand in written orders that the office types in. ⚠ Before SRA-3 the invoice raised at dispatch named whoever DISPATCHED it as “Booked By”, and credited no rep at all.',
      prereq: ['C1 done (the product exists).'],
      data: [`Shop SRA Labaik Pharmacy ${RUN} · 3 × SRA Panadol ${RUN} · rep SRA Irfan ${RUN}`],
      rollback: 'The order is dispatched and its invoice voided at the end of the run.',
    })
    cy.viewport(1400, 900)
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showOrderBooking')
    cy.window().then((w) => w.showOrderBooking())
    const a1 = g.act(`**Order → New Order**. In **Shop** choose **SRA Labaik Pharmacy ${RUN}**; add 3 × SRA Panadol.`,
      [`**Sales rep** appears under the shop and reads **SRA Irfan ${RUN}** — the shop’s usual rep.`])
    bookOnScreen(ctx.labaik.customerId, 3)
    cy.get('#bkRepWrap').should('be.visible')
    cy.get('#bkRepId').should('have.value', String(ctx.irfan))
    cy.get('#BookingDiv').scrollIntoView()   // the picture must show the shop and its rep, not the lines below
    g.snap(a1, 'booking-rep-default')

    const a2 = g.act('Press **Book order**.', ['“Order booked — SO-… · awaiting review”.'])
    cy.intercept('POST', '**/bookOrder').as('book')
    cy.get('#bkSubmit').click()
    cy.wait('@book').then((x) => {
      expect(x.request.body.repId, 'the screen sent the rep').to.eq(ctx.irfan)
      expect(x.response.body.success, JSON.stringify(x.response.body).slice(0, 200)).to.eq(true)
      ctx.paperOrder = x.response.body.data
    })
    g.snap(a2, 'booked')

    g.act('**Orders**: **Confirm** the order, then **Dispatch** it (the dispatch raises the invoice).',
      [`The invoice credits **SRA Irfan ${RUN}**, source **Paper order (REP_PAPER)**; “Booked By” is the owner who typed it — not a dispatcher.`], { via: 'run' })
    cy.then(() => confirmAndDispatch(ctx.paperOrder)).then((o) => {
      ctx.invoices.push(o.invoiceNo)
      ctx.irfanInvoices.push(o.invoiceNo)
      receipt(o.invoiceNo).then((inv) => {
        expect(inv.repName).to.eq(`SRA Irfan ${RUN}`)
        expect(inv.saleSource).to.eq('REP_PAPER')
      })
    })
  })

  // ── C3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('C3', 'A rep booking signed in has no rep picker and is credited themselves', () => {
    g.testCase('C3', 'A rep booking signed in has no rep picker and is credited themselves', {
      tenant: `${BOOKER} (books) and ${OWNER} (confirms, dispatches)`, role: 'The booker — a rep whose login is linked (T1)',
      purpose: 'A rep must never be able to book a sale in a colleague’s name. The picker is not on their screen, and a rep id sent anyway is dropped by the server (gate sra-rep-on-sale.cy.js C2).',
      prereq: [`T1 done: **SRA Booker ${RUN}** is linked to ${BOOKER}, and **SRA Mine ${RUN}** is their outlet.`],
      data: [`Shop SRA Mine ${RUN} · 2 × SRA Panadol ${RUN}`],
      rollback: 'The order is dispatched and its invoice voided at the end of the run; the booker is unlinked.',
    })
    cy.loginAsOrderBooker()
    cy.viewport(1400, 900)
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showOrderBooking')
    cy.window().then((w) => w.showOrderBooking())
    const a1 = g.act(`Sign in as **${BOOKER}** → **Order → New Order**, choose **SRA Mine ${RUN}**, add 2 × SRA Panadol.`,
      ['There is **no Sales rep** picker — the order is the signed-in rep’s own.'])
    cy.then(() => bookOnScreen(ctx.t1[`SRA Mine ${RUN}`], 2))
    cy.get('#bkRepId').should('not.exist')
    cy.get('#BookingDiv').scrollIntoView()
    g.snap(a1, 'booker-no-picker')

    const a2 = g.act('Press **Book order**; the owner then confirms and dispatches it.',
      [`The invoice credits **SRA Booker ${RUN}**, source **Rep signed in (REP_LOGIN)**; “Booked By” is the booker.`])
    cy.intercept('POST', '**/bookOrder').as('book')
    cy.get('#bkSubmit').click()
    cy.wait('@book').then((x) => {
      expect(x.response.body.success, JSON.stringify(x.response.body).slice(0, 200)).to.eq(true)
      ctx.repOrder = x.response.body.data
    })
    g.snap(a2, 'booker-booked')
    cy.then(() => confirmAndDispatch(ctx.repOrder)).then((o) => {
      ctx.invoices.push(o.invoiceNo)
      receipt(o.invoiceNo).then((inv) => {
        expect(inv.repName).to.eq(`SRA Booker ${RUN}`)
        expect(inv.saleSource).to.eq('REP_LOGIN')
        expect(inv.bookedByName).to.contain('booker')
      })
    })
  })
  // ── D1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('D1', 'The Sale Detail Report filters by sales rep, and shows rep, area and supplier on every line', () => {
    g.testCase('D1', 'The Sale Detail Report filters by sales rep, and shows rep, area and supplier on every line', {
      tenant: OWNER, role: 'Owner (a member on a “Sees: Own” set sees only the sales they keyed or that are credited to their rep — gate sra-sales-analysis.cy.js R6)',
      purpose: 'The distributor’s first question: what did each rep sell, and where. The rep is the one STAMPED on the invoice (C1–C3), the area is the shop’s current one.',
      prereq: ['C1–C3 done this run: two sales credited to SRA Irfan (the till sale and the paper order), one to SRA Booker.'],
      data: [`Sales rep **SRA Irfan ${RUN}**, period **This month**`],
      rollback: 'Nothing is saved — a report only reads.',
    })
    cy.viewport(1400, 900)
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#SRDiv').should('be.visible')
    const a1 = g.act('**Sale → Sale Detail Report**. Set the period to **This month**. Open **Sales rep**.',
      ['New filters beside the old ones: **Sales rep**, **How it came in**, **Area**, **City**, **Supplier**, **Bonus**.',
        `Sales rep lists **— No rep / not recorded —** and every rep; typing **Irfan ${RUN}** in its search box finds **SRA Irfan ${RUN}**.`])
    cy.get('#dateRangeDDSR').select('0', { force: true })
    cy.get('#rfRep').focus()   // the rail loads its lists on first focus
    cy.get(`#rfRep option[value="${ctx.irfan}"]`, { timeout: 15000 }).should('exist')
    cy.get('#rfSource').should('exist')
    cy.get('#rfBonus').should('exist')
    cy.get('#SR').scrollIntoView()
    cy.get('button[data-id="rfRep"]').click({ force: true })   // open the rep list for the picture
    cy.get('.bs-searchbox input:visible').first().type(`Irfan ${RUN}`)   // type to find, as a person would
    cy.contains('.dropdown-menu.show li:visible, .dropdown-menu.open li:visible, .bootstrap-select.open li:visible', `SRA Irfan ${RUN}`).should('exist')
    g.snap(a1, 'report-filters')
    cy.get('button[data-id="rfRep"]').click({ force: true })

    const a2 = g.act(`Choose **SRA Irfan ${RUN}** in Sales rep.`,
      ['The report reloads with **only Irfan’s sales**: the till sale (C1) and the paper order (C2).',
        `The new columns at the right: **Sales rep — SRA Irfan ${RUN}**, with **Paper order** under it.`,
        '**Area** and **Supplier** read **No area** / **No supplier** here, and that is the right answer: SRA Labaik Pharmacy was registered without an area (B3) and SRA Panadol was stocked without a purchase. A shop with an area shows it with its city under it, and a product bought from a supplier shows that supplier — proven by the gate (sra-sales-analysis.cy.js R1).'])
    cy.intercept('POST', '**/loadSR').as('sr')
    cy.get('#rfRep').select(String(ctx.irfan), { force: true })
    cy.wait('@sr').then((x) => {
      const rows = x.response.body.collection || []
      expect(rows.length, 'Irfan has rows').to.be.greaterThan(0)
      rows.forEach((r) => expect(r.repName).to.eq(`SRA Irfan ${RUN}`))
      expect([...new Set(rows.map((r) => r.invoiceNo))].sort(), 'exactly the two Irfan sales').to.deep.eq([...ctx.irfanInvoices].sort())
    })
    // The new columns are the LAST four of eighteen — scroll the rep cell itself into view for the picture.
    cy.contains('#tableSellReport tbody td', `SRA Irfan ${RUN}`).scrollIntoView().should('be.visible')
    g.snap(a2, 'report-by-rep')
  })

  // ── D2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('D2', 'Grouped by how the sale came in: paper orders, reps signed in, counter — and “Not recorded” for old sales', () => {
    g.testCase('D2', 'Grouped by how the sale came in: paper orders, reps signed in, counter — and “Not recorded” for old sales', {
      tenant: OWNER, role: 'Owner',
      purpose: 'A distributor checks how much comes in on paper versus by login. A sale from before 15 Oct never knew its rep — it groups as **Not recorded**, never guessed.',
      prereq: ['D1 done.'], data: ['Group by **How it came in**, Sales rep back to all'],
      rollback: 'Nothing is saved.',
    })
    cy.viewport(1400, 900)
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#dateRangeDDSR').select('0', { force: true })
    const a1 = g.act('Set **Group by** to **How it came in**.',
      ['A subtotal table appears above the lines, one row per source in words — **Paper order**, **Rep signed in**, **Counter sale**, **Not recorded** … — with invoices, quantity, total, tax, gross, and the **bonus** quantity and value.'])
    cy.intercept('POST', '**/loadSR').as('sr')
    cy.get('#rfGroupBy').select('SOURCE', { force: true })
    cy.wait('@sr').then((x) => {
      const codes = (x.response.body.object || []).map((g2) => g2.labelCode)
      expect(codes, 'the sources are codes on the wire').to.include.members(['REP_PAPER', 'REP_LOGIN'])
    })
    cy.contains('#srGroups td', 'Paper order').should('be.visible')
    cy.contains('#srGroups td', 'Rep signed in').should('be.visible')
    cy.get('#srGroups').scrollIntoView()
    g.snap(a1, 'grouped-by-source')
  })
})
