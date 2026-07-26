/**
 * common-settings — agriculture-service as the 4th consumer of the shared engine.
 *
 * Proves the rollout end to end via the monolith proxy: the catalog is served, an owner override persists, an
 * unknown key is refused, and a toggle CHANGES BEHAVIOUR. Drives agri.entry.requireLand: with it on, an expense
 * with no land/plot is rejected; with it off (default), it's accepted. Restores defaults so reruns are clean.
 * demo.agriculture has SUPER_PRIVILEGE so it can write config. Run headed.
 */
const REQUIRE_LAND = 'agri.entry.requireLand'

const rows = (body) => {
  for (const k of ['collection', 'object', 'data']) if (Array.isArray(body && body[k])) return body[k]
  return []
}
const entry = (body, key) => rows(body).find((e) => e.key === key)

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveAgricultureConfig', form: true, body: { key, value }, failOnStatusCode: false })
    .then((r) => expect(JSON.stringify(r.body), `saveConfig ${key}=${value}`).to.match(/SUCCESS/))

describe('common-settings: agriculture consumes the shared engine', () => {
  beforeEach(() => { cy.loginAsAgriculture() })

  after(() => { cy.loginAsAgriculture(); setConfig(REQUIRE_LAND, 'false') })   // leave clean

  it('serves the agriculture catalog with effective values (default off)', () => {
    cy.request('/getAgricultureConfig').then((r) => {
      expect(r.status).to.eq(200)
      const rl = entry(r.body, REQUIRE_LAND)
      expect(rl, 'requireLand registered').to.exist
      expect(rl.type).to.eq('BOOL')
      expect(String(rl.value), 'defaults off').to.eq('false')
      expect(rl.group).to.eq('Entries')
    })
  })

  it('an owner override persists per-org (override else default)', () => {
    setConfig(REQUIRE_LAND, 'true')
    cy.request('/getAgricultureConfig').then((r) => {
      const rl = entry(r.body, REQUIRE_LAND)
      expect(String(rl.value), 'override reflected').to.eq('true')
      expect(rl.isDefault, 'now an org override').to.eq(false)
    })
    setConfig(REQUIRE_LAND, 'false')
  })

  it('rejects an unknown setting key', () => {
    cy.request({ method: 'POST', url: '/saveAgricultureConfig', form: true,
      body: { key: 'agri.not.a.real.key', value: 'true' }, failOnStatusCode: false })
      .then((r) => expect(JSON.stringify(r.body), 'unknown key refused').to.not.match(/SUCCESS/))
  })

  it('requireLand ON → an expense with no plot is refused; OFF → accepted', () => {
    // ON: expense without a landId is rejected.
    setConfig(REQUIRE_LAND, 'true')
    cy.request({ method: 'POST', url: '/addAgricultureExpense', form: true,
      body: { expenseName: `CY_NoLand_${Date.now()}`, expenseType: 'Seeds', amount: '500' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.status).to.eq(200)
        expect(String(r.body.status), 'no-plot expense refused when required').to.eq('INVALID')
      })

    // OFF (default): the same expense is accepted.
    setConfig(REQUIRE_LAND, 'false')
    cy.request({ method: 'POST', url: '/addAgricultureExpense', form: true,
      body: { expenseName: `CY_NoLand_${Date.now()}`, expenseType: 'Seeds', amount: '500' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.status).to.eq(200)
        expect(String(r.body.status), 'no-plot expense accepted when not required').to.eq('SUCCESS')
      })
  })
})
