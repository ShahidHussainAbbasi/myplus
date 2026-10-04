/**
 * EX-2b — tag an expense to what it was for: a school or vehicle (school), a land (farm).
 *
 * Design: microservices/docs/slices/ex-2b-expense-tags.md §4.
 *
 * <h3>The server confirms every tag through the module that owns it</h3>
 * So the refusals are the point of this gate as much as the happy path: an id from another business, or a tag
 * type that belongs to another module, must be refused — never stored with a label the browser made up.
 *
 * <h3>Fixtures are SEEDED, not assumed</h3>
 * "Existence is not eligibility": a vehicle and a land are created here, by name, and found again by that name.
 */

const PW = 'Demo@2025!'
const KEY = 'org.cap.expenseManagement'
const SCHOOL = { email: 'owner.education@myplus.com', check: '/getDashboardData', dash: '/educationDashboard' }
const FARM = { email: 'owner.agriculture@myplus.com', check: '/agricultureDashboard', dash: '/agricultureDashboard' }
const SHOP = { email: 'owner.business@myplus.com', check: '/getBusinessDashboardStats', dash: '/businessDashboard' }

const signIn = (d, fresh) => cy.loginAs(d.email, PW, d.check, fresh ? 'ex2b-' + Date.now() : undefined)
const moduleOn = (d) => {
  signIn(d)
  cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: KEY, enabled: 'true' } })
    .its('body.success').should('eq', true)
}
const moduleReset = (d) => {
  signIn(d)
  cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: KEY }, failOnStatusCode: false })
}
const tags = (source) =>
  cy.request(`/expense/tags?source=${source}`).then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    return r.body.data
  })
const categories = () => cy.request('/expense/categories').its('body.data')
const record = (body) =>
  cy.request({ method: 'POST', url: '/expense/vouchers?post=true', failOnStatusCode: false,
    headers: { 'Idempotency-Key': 'ex2b-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8) }, body })
const today = () => localIsoDate()

describe('EX-2b — expense tags', () => {
  const vehicleNo = 'EX2B-' + Date.now() % 1000000
  const landName = 'EX2B Field ' + Date.now()
  let vehicle = null
  let land = null

  before(() => {
    // seed one vehicle (school) and one land (farm), then switch the module on for both
    signIn(SCHOOL)
    cy.request({ method: 'POST', url: '/addVehicle', form: true, body: { name: 'EX2B Bus', number: vehicleNo } })
      .its('body.status').should('be.oneOf', ['SUCCESS', 'FOUND'])
    signIn(FARM)
    cy.request({ method: 'POST', url: '/addLand', form: true,
      body: { landName, landType: 'Agricultural', landUnit: 'Acre', totalLandUnit: '3', amount: '1' } })
      .its('body.status').should('be.oneOf', ['SUCCESS', 'FOUND'])
    moduleOn(SCHOOL)
    moduleOn(FARM)
  })

  after(() => { moduleReset(SCHOOL); moduleReset(FARM) })

  it('1 — the school is offered its schools and vehicles; a fuel expense carries the bus', () => {
    signIn(SCHOOL, true)
    tags('education').then((list) => {
      expect(list.some((t) => t.type === 'SCHOOL'), 'a school is offered').to.eq(true)
      vehicle = list.find((t) => t.type === 'VEHICLE' && String(t.label).includes(vehicleNo))
      expect(vehicle, 'the seeded bus is offered').to.exist
      categories().then((cats) => {
        const fuel = cats.find((c) => c.accountCode === '6200')
        record({ voucherDate: today(), paidFrom: 'CASH',
          lines: [{ categoryId: fuel.id, amount: 450, tagType: 'VEHICLE', tagId: vehicle.id }] })
          .then((r) => {
            expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
            const line = r.body.data.lines[0]
            expect(line.tagType).to.eq('VEHICLE')
            expect(line.tagId).to.eq(vehicle.id)
            expect(line.tagLabel, 'label confirmed by education, not typed by the browser').to.eq(vehicle.label)
            expect(line.accountCode, 'a tag never changes the account').to.eq('6200')
          })
      })
    })
  })

  it('2 — the farm tags a land', () => {
    signIn(FARM, true)
    tags('agriculture').then((list) => {
      land = list.find((t) => t.type === 'LAND' && t.label === landName)
      expect(land, 'the seeded land is offered').to.exist
      categories().then((cats) => {
        record({ voucherDate: today(), paidFrom: 'CASH',
          lines: [{ categoryId: cats[0].id, amount: 120, tagType: 'LAND', tagId: land.id }] })
          .then((r) => {
            expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
            expect(r.body.data.lines[0].tagLabel).to.eq(landName)
          })
      })
    })
  })

  it('3 — refusals: a foreign id, a type from the wrong module, an unknown type', () => {
    signIn(SCHOOL, true)
    categories().then((cats) => {
      const line = (tagType, tagId) => ({ voucherDate: today(), paidFrom: 'CASH',
        lines: [{ categoryId: cats[0].id, amount: 1, tagType, tagId }] })
      // the farm's land, sent by the school
      record(line('LAND', land.id)).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
        expect(r.body.message).to.match(/not found|not one of/i)
      })
      record(line('BOGUS', 1)).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.match(/cannot be tagged/i)
      })
      record(line('VEHICLE', 999999999)).then((r) => expect(r.body.success).to.eq(false))
    })
    signIn(FARM, true)
    categories().then((cats) => {
      record({ voucherDate: today(), paidFrom: 'CASH',
        lines: [{ categoryId: cats[0].id, amount: 1, tagType: 'VEHICLE', tagId: vehicle.id }] })
        .then((r) => expect(r.body.success, "the school's bus, sent by the farm").to.eq(false))
    })
  })

  it('4 — the screen offers "For" on the school dashboard and not on the shop', () => {
    signIn(SCHOOL, true)
    cy.visit(SCHOOL.dash)
    cy.waitForAppReady()
    cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
    cy.get('#navExpenses').click()
    cy.get('#expTagGroup').should('be.visible')
    cy.get('#expTag option', { timeout: 20000 }).should('contain', vehicleNo)

    signIn(SHOP)
    cy.request('/expense/categories')   // shop needs no module state for this assertion: the field is markup-driven
    cy.visit(SHOP.dash)
    cy.waitForAppReady()
    cy.get('#expTagGroup').should('not.be.visible')
  })
})
