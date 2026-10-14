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
const ctx = { reps: [], outlets: [] }

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
      ['**Rep saved.** Its status reads **inactive** and the button now says **Reactivate**.'])
    cy.get(`#srBody tr[data-rep-id="${ctx.irfan}"] [data-sr-toggle]`).click()
    cy.get('#srNotice').should('contain.text', 'Rep saved')
    cy.get(`#srBody tr[data-rep-id="${ctx.irfan}"]`).should('contain.text', 'inactive')
    g.snap(a1, 'deactivated')
    const a2 = g.act(`Open **Assign to**; then find **SRA Labaik Pharmacy ${RUN}** in the outlet table.`,
      [`SRA Irfan ${RUN} is **not** offered in Assign to.`, `The outlet still reads **Covered by: SRA Irfan ${RUN} (inactive)**.`])
    cy.get(`#terrRep option[value="${ctx.irfan}"]`).should('not.exist')
    cy.get('#terrFilter').type(`SRA Labaik Pharmacy ${RUN}`)
    cy.contains('#terrBody tr', `SRA Labaik Pharmacy ${RUN}`).should('contain.text', `SRA Irfan ${RUN} (inactive)`)
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
    cy.get('#terrFilter').clear().type(`SRA `)
    cy.get('#terrFilter').type(RUN)
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
})
