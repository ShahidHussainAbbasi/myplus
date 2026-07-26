/**
 * common-settings — welfare-service as the 3rd consumer of the shared engine.
 *
 * Proves the rollout end to end via the monolith proxy: the catalog is served, an owner override persists, an
 * unknown key is refused, and — crucially — a toggle CHANGES BEHAVIOUR. Drives welfare.donation.requireDonor: with
 * it on, a donation with no donor is rejected; with it off (default), it's accepted. Restores defaults so reruns
 * are clean. demo.welfare has SUPER_PRIVILEGE so it can write config. Run headed.
 */
const REQUIRE_DONOR = 'welfare.donation.requireDonor'
const DUP_NAMES = 'welfare.donator.allowDuplicateNames'

const rows = (body) => {
  for (const k of ['collection', 'object', 'data']) if (Array.isArray(body && body[k])) return body[k]
  return []
}
const entry = (body, key) => rows(body).find((e) => e.key === key)

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveWelfareConfig', form: true, body: { key, value }, failOnStatusCode: false })
    .then((r) => expect(JSON.stringify(r.body), `saveConfig ${key}=${value}`).to.match(/SUCCESS/))

describe('common-settings: welfare consumes the shared engine', () => {
  beforeEach(() => { cy.loginAsWelfare() })

  after(() => { cy.loginAsWelfare(); setConfig(REQUIRE_DONOR, 'false') })   // leave clean

  it('serves the welfare catalog with effective values (defaults off)', () => {
    cy.request('/getWelfareConfig').then((r) => {
      expect(r.status).to.eq(200)
      const rd = entry(r.body, REQUIRE_DONOR)
      const dup = entry(r.body, DUP_NAMES)
      expect(rd, 'requireDonor registered').to.exist
      expect(rd.type).to.eq('BOOL')
      expect(String(rd.value), 'defaults off').to.eq('false')
      expect(dup, 'allowDuplicateNames registered').to.exist
    })
  })

  it('an owner override persists per-org (override else default)', () => {
    setConfig(REQUIRE_DONOR, 'true')
    cy.request('/getWelfareConfig').then((r) => {
      const rd = entry(r.body, REQUIRE_DONOR)
      expect(String(rd.value), 'override reflected').to.eq('true')
      expect(rd.isDefault, 'now an org override').to.eq(false)
    })
    setConfig(REQUIRE_DONOR, 'false')
  })

  it('rejects an unknown setting key', () => {
    cy.request({ method: 'POST', url: '/saveWelfareConfig', form: true,
      body: { key: 'welfare.not.a.real.key', value: 'true' }, failOnStatusCode: false })
      .then((r) => expect(JSON.stringify(r.body), 'unknown key refused').to.not.match(/SUCCESS/))
  })

  it('requireDonor ON → a donation with no donor is refused; OFF → accepted', () => {
    // ON: donation without a donatorId is rejected.
    setConfig(REQUIRE_DONOR, 'true')
    cy.request({ method: 'POST', url: '/addDonation', form: true,
      body: { amount: '150', receivedBy: 'Cypress' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.status).to.eq(200)
        expect(String(r.body.status), 'no-donor donation refused when required').to.eq('INVALID')
      })

    // OFF (default): the same donation is accepted.
    setConfig(REQUIRE_DONOR, 'false')
    cy.request({ method: 'POST', url: '/addDonation', form: true,
      body: { amount: '150', receivedBy: 'Cypress' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.status).to.eq(200)
        expect(String(r.body.status), 'no-donor donation accepted when not required').to.eq('SUCCESS')
      })
  })
})
