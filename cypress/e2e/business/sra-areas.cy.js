/**
 * SRA-1 — a business's own City / Area list, the inline address form on the customer and supplier screens, and the
 * four customer/supplier edit defects found while building it (owner-approved, fixed in the same slice).
 * Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §4 · Test Book section 29 (A1–A5).
 *
 * Tenant: owner.marketplace@ — the distributor (Shafeeq Medicine) the feature is for. The OTHER business is
 * owner.business@: a different organisation for certain (whether demo.marketplace@ is one is unconfirmed).
 *
 * Every narrowing / refusal assertion is paired with a POSITIVE CONTROL that must still hold, so an endpoint that
 * answered nothing at all could not turn a case green.
 *
 * Leaves no server state that changes another spec: everything it makes carries this run's stamp; customers and the
 * supplier it makes are its own; the outlet it assigns is unassigned in after(). Cities and areas are never deleted
 * (by design) — they are stamped, so they collide with nothing.
 */
const run = String(Date.now()).slice(-6)
const mobile = () => `03${Math.floor(100000000 + Math.random() * 899999999)}`
const list = (body) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
  return []
}

const CITY = `Khanpur ${run}`
const CITY2 = `Rahim Yar Khan ${run}`
const AREA = `Zahir Pir ${run}`
const AREA2 = `Model Town ${run}`
const ctx = {}

const addCity = (name) =>
  cy.request({ method: 'POST', url: '/addCity', form: true, failOnStatusCode: false, body: { name } })
const addArea = (cityId, name) =>
  cy.request({ method: 'POST', url: '/addArea', form: true, failOnStatusCode: false, body: { cityId, name } })
const areas = () => cy.request('/getAreas').then((r) => {
  expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
  return list(r.body)
})
// `?? null`, not undefined: a Cypress .then() that returns undefined passes the PREVIOUS subject (the response) on,
// so "this customer does not exist" would be asserted against the HTTP response and fail for the wrong reason.
const customerNamed = (name) => cy.request('/getUserCustomer').then((r) => list(r.body).find((c) => c.name === name) ?? null)
/** Edits open ONLY from a row's own Edit button (main.js "Explicit-edit UX"), never from a click on the row. */
const editCustomerRow = (name) => {
  cy.get('#tableCustomer_filter input, .dataTables_filter input').first().clear().type(name)
  cy.contains('#tableCustomer tbody tr', name, { timeout: 15000 }).find('.js-edit-row').click({ force: true })
  cy.get('#CustomerModal').should('be.visible')
}
const saveCustomer = (body) =>
  cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false, body })

describe('SRA-1 — areas and the inline address form', () => {
  before(() => {
    cy.loginAsMarketplaceOwner()
    addCity(CITY).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      ctx.cityId = r.body.object.id
      return addArea(ctx.cityId, AREA)
    }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      ctx.areaId = r.body.object.id
      expect(r.body.object.cityName, 'an area carries its city').to.eq(CITY)
      return addCity(CITY2)
    }).then((r) => {
      ctx.city2Id = r.body.object.id
      return addArea(ctx.city2Id, AREA2)
    }).then((r) => { ctx.area2Id = r.body.object.id })
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  // ── A1 · the inline form, on the real screen ─────────────────────────────────────────────────────────────
  it('A1 — a city and an area are added without leaving the customer form, saved, and come back on edit', () => {
    const name = `SRA Sarwar ${run}`
    const uiCity = `Khanpur UI ${run}`
    const uiArea = `Zahir Pir UI ${run}`
    cy.openSection('CustomerDiv')
    cy.get('#newCustomer').click()
    cy.get('#customerName').type(name)
    cy.get('#contact').type(mobile())

    // "+ New area" opens a small form OVER the customer form (the purchase form's "+ New product" pattern), and a
    // city not on the list yet is typed in the same step.
    cy.get('[data-sra-address="customer"] [data-sra-new-for="area"]').click()
    cy.get('#SraAddressModal').should('be.visible')
    cy.get('#sraDlgNewCity').type(uiCity)
    cy.get('#sraDlgName').type(uiArea)
    cy.get('#sraDlgSave').click()
    cy.get('#SraAddressModal').should('not.be.visible')
    cy.get('#CustomerModal').should('be.visible')                       // the customer form never closed…
    cy.get('#customerName').should('have.value', name)                  // …and kept what was typed
    cy.get('#customerCityId option:selected').should('have.text', uiCity) // both came back SELECTED
    cy.get('#customerAreaId option:selected').should('have.text', uiArea)

    cy.intercept('POST', '**/addCustomer').as('save')
    cy.get('#addCustomer').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')

    areas().then((all) => {
      const a = all.find((x) => x.name === uiArea)
      expect(a, 'the area made inline exists').to.exist
      customerNamed(name).then((c) => {
        expect(c, 'the customer saved').to.exist
        expect(c.areaId, 'filed under the new area').to.eq(a.id)
        expect(c.city, 'the printed city follows the picked city').to.eq(uiCity)
      })
      // The EDIT round trip: the stored area comes back on the form, with its city.
      cy.openSection('CustomerDiv')
      editCustomerRow(name)
      cy.get('#customerAreaId').should('have.value', String(a.id))
      cy.get('#customerCityId option:selected').should('have.text', uiCity)
    })
  })

  // ── A2 · uniqueness and filtering ────────────────────────────────────────────────────────────────────────
  it('A2 — a duplicate area is refused case-insensitively, and the Area list follows the City', () => {
    addArea(ctx.cityId, AREA.toLowerCase()).then((r) => {
      expect(r.body.status, 'the same name in lower case is the same area').to.eq('FOUND')
      expect(r.body.object.id, 'and the existing row is handed back').to.eq(ctx.areaId)
    })
    areas().then((all) => {
      expect(all.filter((a) => a.cityId === ctx.cityId && a.name.toLowerCase() === AREA.toLowerCase()).length,
        'still exactly one').to.eq(1)
    })
    // The same name in ANOTHER city is a different area (positive control: the key is per city, not global).
    addArea(ctx.city2Id, AREA).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

    cy.openSection('CustomerDiv')
    cy.get('#newCustomer').click()
    // Both selects are searchable pickers (searchable-selects.js hides the native one): drive the native select.
    cy.get('#customerCityId').select(CITY2, { force: true })
    cy.get(`#customerAreaId option[value="${ctx.area2Id}"]`).should('exist')    // this city's area is offered…
    cy.get(`#customerAreaId option[value="${ctx.areaId}"]`).should('not.exist') // …another city's is not
    cy.get('#customerCityId').select(CITY, { force: true })
    cy.get(`#customerAreaId option[value="${ctx.areaId}"]`).should('exist')     // and back again

    // A duplicate typed into "+ New area" selects the EXISTING one and says so; Esc closes only the small form.
    cy.get('[data-sra-address="customer"] [data-sra-new-for="area"]').click()
    cy.get('#sraDlgCity').select(String(ctx.cityId))
    cy.get('#sraDlgName').type(AREA.toUpperCase())
    cy.get('#sraDlgSave').click()
    cy.get('#customerAreaId').should('have.value', String(ctx.areaId))
    cy.get('[data-sra-address="customer"] [data-sra-new-for="city"]').click()
    cy.get('#SraAddressModal').should('be.visible')
    cy.get('#sraDlgNewCity').type('{esc}')
    cy.get('#SraAddressModal').should('not.be.visible')
    cy.get('#CustomerModal').should('be.visible')
  })

  // ── A3 · the supplier form ───────────────────────────────────────────────────────────────────────────────
  it('A3 — the supplier form has the same City / Area, and a supplier is saved under an area', () => {
    cy.openSection('VenderDiv')
    cy.get('#newVender').click()
    cy.get('#venderCityId').should('exist')   // a searchable picker sits over it; its wrapping is the picker's business
    cy.get('#venderAreaId').should('exist')
    cy.get('[data-sra-address="vender"] [data-sra-new-for="city"]').should('be.visible')
    cy.get('[data-sra-address="vender"] [data-sra-new-for="area"]').should('be.visible')

    const supplier = `SRA Davis Supplier ${run}`
    const co = `SRA Davis ${run}`
    cy.request({ method: 'POST', url: '/addCompany', form: true, failOnStatusCode: false,
      body: { name: co, phone: '068-1234567', address: 'Khanpur' } })
    cy.request('/getUserCompany').then((r) => {
      const company = list(r.body).find((c) => c.name === co)
      expect(company, 'fixture company').to.exist
      return cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
        body: { name: supplier, companyIds: String(company.id), mobile: mobile(), areaId: ctx.areaId } })
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.request('/getUserVender?q=-1').then((r) => {
      const v = list(r.body).find((x) => x.name === supplier)
      expect(v, 'the supplier saved').to.exist
      expect(v.areaId, 'filed under the area').to.eq(ctx.areaId)
      ctx.supplierId = v.id
    })
  })

  // ── A4 · import ──────────────────────────────────────────────────────────────────────────────────────────
  it('A4 — importing customers files them under areas, makes the new ones, and says which', () => {
    const newArea = `1st Sector ${run}`
    const csv = 'name,contact,city,area\n'
      + `SRA Labaik ${run},${mobile()},${CITY},${newArea}\n`
      + `SRA Model Chemist ${run},${mobile()},${CITY2},${AREA2}\n`
    cy.request({ method: 'POST', url: '/import/customer/commit', body: { csv }, failOnStatusCode: false }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body).slice(0, 400)).to.eq('SUCCESS')
      expect(r.body.message, 'only the NEW area is reported as created').to.contain(`1 area created (${newArea})`)
      expect(r.body.message, 'no city was new').to.not.contain('city created')
    })
    areas().then((all) => {
      const made = all.find((a) => a.name === newArea)
      expect(made, 'the new area exists').to.exist
      customerNamed(`SRA Labaik ${run}`).then((c) => expect(c.areaId).to.eq(made.id))
      customerNamed(`SRA Model Chemist ${run}`).then((c) => expect(c.areaId, 'an existing area is reused').to.eq(ctx.area2Id))
    })
  })

  // ── A5 · another business ────────────────────────────────────────────────────────────────────────────────
  it('A5 — another business cannot see or use these areas', () => {
    cy.loginAsOwner()   // owner.business@ — a different organisation
    areas().then((all) => expect(all.map((a) => a.id), 'not in their list').to.not.include(ctx.areaId))
    const name = `SRA Intruder ${run}`
    saveCustomer({ name, contact: mobile(), areaId: ctx.areaId }).then((r) => {
      expect(r.body.status, 'our area id is refused').to.eq('FAILED')
      expect(r.body.message).to.contain('area was not found')
    })
    customerNamed(name).then((c) => expect(c, 'nothing was saved').to.not.exist)
    // Positive control: their OWN save without an area still works.
    saveCustomer({ name: name + ' ok', contact: mobile() }).then((r) => expect(r.body.status).to.eq('SUCCESS'))
  })
})

describe('SRA-1 — edits no longer wipe or move what the form does not carry', () => {
  beforeEach(() => cy.loginAsMarketplaceOwner())

  after(() => {
    // Return the outlet this spec assigned to the shared pool (other specs read /outlets).
    cy.loginAsMarketplaceOwner()
    cy.then(() => ctx.assignedId && cy.request({ method: 'POST', url: '/assignOutlets', form: true,
      failOnStatusCode: false, body: { customerIds: String(ctx.assignedId) } }))
    cy.then(() => ctx.f4RepId && cy.request({ method: 'POST', url: '/updateSalesRep', form: true,
      failOnStatusCode: false, body: { id: ctx.f4RepId, status: 'INACTIVE' } }))
  })

  it('an edit on the SCREEN keeps City, CNIC and the licence (they used to be saved blank)', () => {
    const name = `SRA Keep ${run}`
    saveCustomer({ name, contact: mobile(), city: 'Khanpur', cnic: '31301-1234567-1', licenseNo: 'DL-777',
      licenseExpiry: '2027-06-30' }).then((r) => expect(r.body.status).to.eq('SUCCESS'))
    cy.openSection('CustomerDiv')
    editCustomerRow(name)
    cy.get('#customerCnic').should('have.value', '31301-1234567-1')   // the form now HAS them on edit
    cy.get('#customerName').clear().type(name + ' Ltd')
    cy.intercept('POST', '**/addCustomer').as('save')
    cy.get('#addCustomer').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
    customerNamed(name + ' Ltd').then((c) => {
      expect(c, 'positive control: the rename landed').to.exist
      expect(c.cnic).to.eq('31301-1234567-1')
      expect(c.licenseNo).to.eq('DL-777')
      expect(String(c.licenseExpiry)).to.contain('2027-06-30')
      expect(c.city).to.eq('Khanpur')
    })
  })

  it('an edit keeps the outlet assigned to its rep (it used to unassign it)', () => {
    const name = `SRA Outlet ${run}`
    saveCustomer({ name, contact: mobile() })
    customerNamed(name).then((c) => {
      ctx.assignedId = c.customerId
      // SRA-2: an outlet is covered by a REP on the business's list — this case makes its own (no login).
      return cy.request({ method: 'POST', url: '/addSalesRep', form: true, body: { name: `SRA F4 Rep ${run}` } }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        ctx.f4RepId = r.body.object.id
        return cy.request({ method: 'POST', url: '/assignOutlets', form: true,
          body: { repId: ctx.f4RepId, customerIds: String(c.customerId) } })
      }).then(() => saveCustomer({ customerId: c.customerId, name, contact: c.contact, version: c.version, address: 'edited' }))
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.request('/outletAssignments').then((r) => {
      const row = list(r.body).find((x) => x.id === ctx.assignedId)   // OutletAssignmentDTO names it `id`
      expect(row, 'listed').to.exist
      expect(row.repId, 'still covered by the same rep after the edit').to.eq(ctx.f4RepId)
    })
  })

  it('an edit keeps a branch on its company\'s credit account (it used to fall back onto its own)', () => {
    const co = `SRA Co ${run}`, br = `SRA Branch ${run}`
    saveCustomer({ name: co, contact: mobile(), creditLimit: 100000 })
    saveCustomer({ name: br, contact: mobile() })
    customerNamed(co).then((company) => customerNamed(br).then((branch) => {
      cy.request({ method: 'POST', url: '/setCustomerAccountParent', form: true, failOnStatusCode: false,
        body: { customerId: branch.customerId, parentCustomerId: company.customerId, accountLevel: 'BRANCH' } })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
      cy.request('/customerAccountGroup?customerId=' + branch.customerId).its('body.object.accountCustomerId')
        .should('eq', company.customerId)   // positive control: grouped before the edit
      customerNamed(br).then((fresh) => saveCustomer({ customerId: fresh.customerId, name: br, contact: fresh.contact,
        version: fresh.version, address: 'edited' })).its('body.status').should('eq', 'SUCCESS')
      cy.request('/customerAccountGroup?customerId=' + branch.customerId).its('body.object.accountCustomerId')
        .should('eq', company.customerId)
    }))
  })

  it('another business cannot take over a customer or a supplier by id (it used to move them)', () => {
    const name = `SRA Victim ${run}`
    saveCustomer({ name, contact: mobile() })
    customerNamed(name).then((victim) => {
      ctx.victimId = victim.customerId
      cy.loginAsOwner()   // owner.business@ — a different organisation
      saveCustomer({ customerId: victim.customerId, name: 'HIJACKED ' + run, contact: mobile() }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('NOT_FOUND')
      })
      if (ctx.supplierId) {
        cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
          body: { id: ctx.supplierId, name: 'HIJACKED ' + run, companyIds: '1', mobile: mobile() } })
          .its('body.status').should('eq', 'NOT_FOUND')
      }
      customerNamed('HIJACKED ' + run).then((c) => expect(c, 'nothing landed in the attacker\'s business').to.not.exist)
    })
    cy.loginAsMarketplaceOwner()
    customerNamed(name).then((c) => {
      expect(c, 'the victim still has its customer').to.exist
      expect(c.customerId).to.eq(ctx.victimId)
    })
    // Positive control: the owner's OWN edit by id still works.
    customerNamed(name).then((c) => saveCustomer({ customerId: c.customerId, name, contact: c.contact, version: c.version,
      address: 'own edit' })).its('body.status').should('eq', 'SUCCESS')
  })
})
