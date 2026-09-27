/**
 * SET-GUIDE — Settings SECURITY and TENANT ISOLATION, every settings screen, every tier.
 *
 * Screens (their monolith routes): business Configuration · Order settings · education · welfare · agriculture.
 * Tiers (dev-test-accounts.md): user. / admin. / owner. of one module share ONE organization; demo. is ANOTHER one.
 *
 * Per screen — each case asserts what a defect would break, and every write is checked on the VICTIM, not only by
 * the response:
 *   1 owner  may read, change and reset a setting (the positive control — without it every refusal below proves
 *            nothing).
 *   2 admin  may change it too (ADMIN_PRIVILEGE is the documented second key).
 *   3 user   may READ (the till needs its settings) but may NOT change or reset — refused, and the value is untouched.
 *   4 an owner naming ANOTHER organization (organizationId=<demo org>) changes only their OWN — the demo org's value
 *            is read afterwards through the demo account and must be unchanged.
 *   5 signed out, nothing is readable.
 * Platform-wide:
 *   6 a spoofed X-Org-Id header at the gateway is stripped — the token's org decides.
 *   7 a tenant owner cannot reach the operator console's plan / entitlement endpoints.
 *
 * Leaves no server state: every setting it changes is snapshotted (value + isDefault) and put back — by RESET when it
 * had no override, so nothing is pinned.
 */
const GW = 'http://localhost:8765'
const DEMO_PW = 'Demo@2025!'
const RUN = String(Date.now()).slice(-6)
const OUT = 'cypress/guide-out/settings-security.json'

const list = (b) => (b && (b.data || b.collection || b.object)) || []
const ok = (b) => !!b && (b.success === true || b.status === 'SUCCESS')

const SCREENS = [
  { id: 'business', name: 'Business → Configuration', module: 'business', get: '/getBusinessConfig', save: '/saveBusinessConfig', reset: '/resetBusinessConfig' },
  { id: 'orders', name: 'Business → Order settings', module: 'business', get: '/getOrderConfig', save: '/saveOrderConfig', reset: '/resetOrderConfig' },
  { id: 'education', name: 'Education → Configuration', module: 'education', get: '/getConfig', save: '/saveConfig', reset: '/resetConfig' },
  { id: 'welfare', name: 'Welfare → Configuration', module: 'welfare', get: '/getWelfareConfig', save: '/saveWelfareConfig', reset: '/resetWelfareConfig' },
  { id: 'agriculture', name: 'Agriculture → Configuration', module: 'agriculture', get: '/getAgricultureConfig', save: '/saveAgricultureConfig', reset: '/resetAgricultureConfig' },
]

const gwLogin = (email) =>
  cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: DEMO_PW }, failOnStatusCode: false })
    .then((r) => {
      expect(r.status, `gateway login ${email}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
      return r.body.data.accessToken
    })
const claims = (token) => JSON.parse(atob(token.split('.')[1]))

const read = (s) => cy.request({ url: s.get, failOnStatusCode: false }).then((r) => {
  expect(r.status, `${s.get} answered`).to.eq(200)
  expect(ok(r.body), `${s.get}: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(true)
  return list(r.body)
})
const entry = (s, key) => read(s).then((items) => {
  const e = items.find((x) => x.key === key)
  expect(e, `${key} on ${s.id}`).to.exist
  return e
})
const post = (url, body) => cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

/**
 * A setting this spec can change harmlessly: TEXT first, else INT, else a switch (welfare and agriculture have only
 * switches) — never a capability, the business type or the language.
 */
const pickKey = (items) => {
  const safe = items.filter((e) => !/^org\.(cap|shape|locale)/.test(e.key) && !e.locked)
  return safe.find((e) => e.type === 'TEXT') || safe.find((e) => e.type === 'INT') || safe.find((e) => e.type === 'BOOL')
}
const newValue = (e) => (e.type === 'INT' ? String((parseInt(e.value, 10) || 0) + 1)
  : e.type === 'BOOL' ? String(String(e.value) !== 'true') : `SEC-${RUN}`)

const results = []
const record = (screen, check, passed, detail) => results.push({ screen, check, passed, detail })

describe('SET-GUIDE — Settings security and tenant isolation', () => {
  after(() => cy.writeFile(OUT, { run: RUN, at: new Date().toISOString(), results }))

  SCREENS.forEach((s) => {
    describe(s.name, () => {
      let key = null
      let orig = null

      before(() => {
        cy.loginAsTier('owner', s.module)
        read(s).then((items) => {
          const e = pickKey(items)
          expect(e, `a TEXT, INT or switch setting to exercise on ${s.id}`).to.exist
          key = e.key
          orig = { value: String(e.value), isDefault: e.isDefault === true, type: e.type }
        })
      })

      const restore = () => {
        cy.loginAsTier('owner', s.module)
        if (orig.isDefault) post(s.reset, { key }).its('body').then((b) => expect(ok(b), `restore ${key} by reset: ${JSON.stringify(b)}`).to.eq(true))
        else post(s.save, { key, value: orig.value }).its('body').then((b) => expect(ok(b), `restore ${key}`).to.eq(true))
        entry(s, key).then((e) => {
          expect(String(e.value), `${key} is back`).to.eq(orig.value)
          expect(e.isDefault === true, `${key} override state is back`).to.eq(orig.isDefault)
        })
      }

      it(`1 owner reads, changes and resets a setting (positive control)`, () => {
        cy.loginAsTier('owner', s.module)
        const v = newValue({ type: orig.type, value: orig.value })
        post(s.save, { key, value: v }).its('body').then((b) => expect(ok(b), `owner save: ${JSON.stringify(b)}`).to.eq(true))
        entry(s, key).then((e) => { expect(String(e.value)).to.eq(v); expect(e.isDefault).to.eq(false) })
        post(s.reset, { key }).its('body').then((b) => expect(ok(b), `owner reset: ${JSON.stringify(b)}`).to.eq(true))
        entry(s, key).then((e) => expect(e.isDefault, 'reset removed the override').to.eq(true))
        restore()
        cy.then(() => record(s.id, 'owner read/change/reset', true, key))
      })

      it(`2 admin may change it too`, () => {
        cy.loginAsTier('admin', s.module)
        const v = newValue({ type: orig.type, value: orig.value })
        post(s.save, { key, value: v }).its('body').then((b) => expect(ok(b), `admin save: ${JSON.stringify(b)}`).to.eq(true))
        entry(s, key).then((e) => expect(String(e.value)).to.eq(v))
        restore()
        cy.then(() => record(s.id, 'admin may change', true, key))
      })

      it(`3 user may read but NOT change or reset — refused, value untouched`, () => {
        cy.loginAsTier('user', s.module)
        read(s).then((items) => expect(items.length, 'the till can read its settings').to.be.greaterThan(0))
        post(s.save, { key, value: newValue({ type: orig.type, value: orig.value }) }).then((r) =>
          expect(ok(r.body), `user save must be refused: ${r.status} ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(false))
        post(s.reset, { key }).then((r) =>
          expect(ok(r.body), `user reset must be refused: ${r.status} ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(false))
        cy.loginAsTier('owner', s.module)
        entry(s, key).then((e) => {
          expect(String(e.value), 'unchanged').to.eq(orig.value)
          expect(e.isDefault === true, 'override state unchanged').to.eq(orig.isDefault)
        })
        cy.then(() => record(s.id, 'user refused (save + reset)', true, key))
      })

      it(`4 an owner naming ANOTHER organization changes only their own`, () => {
        gwLogin(`demo.${s.module}@myplus.com`).then((demoTok) => {
          const demoOrg = claims(demoTok).activeOrgId
          cy.loginAsTier('demo', s.module)
          entry(s, key).then((demoBefore) => {
            cy.loginAsTier('owner', s.module)
            const v = newValue({ type: orig.type, value: orig.value })
            post(s.save, { key, value: v, organizationId: demoOrg }).its('body').then((b) => expect(ok(b)).to.eq(true))
            entry(s, key).then((e) => expect(String(e.value), 'the parameter is IGNORED: the owner’s own org changed').to.eq(v))
            cy.loginAsTier('demo', s.module)
            entry(s, key).then((demoAfter) => {
              expect(String(demoAfter.value), `org ${demoOrg} (demo) is untouched`).to.eq(String(demoBefore.value))
              expect(demoAfter.isDefault, 'and gained no override').to.eq(demoBefore.isDefault)
            })
            restore()
            cy.then(() => record(s.id, 'cross-tenant parameter ignored', true, `demo org ${demoOrg}`))
          })
        })
      })

      it(`5 signed out, nothing is readable`, () => {
        cy.clearAllCookies()
        cy.request({ url: s.get, failOnStatusCode: false, followRedirect: false }).then((r) => {
          const leaked = r.status === 200 && list(r.body).length > 0
          expect(leaked, `anonymous ${s.get} → ${r.status}`).to.eq(false)
          record(s.id, 'anonymous refused', true, `HTTP ${r.status}`)
        })
      })
    })
  })

  describe('Platform-wide', () => {
    it('6 a spoofed X-Org-Id header at the gateway is stripped — the token decides', () => {
      gwLogin('demo.business@myplus.com').then((demoTok) => {
        const demoOrg = claims(demoTok).activeOrgId
        gwLogin('owner.business@myplus.com').then((ownerTok) => {
          const auth = { Authorization: `Bearer ${ownerTok}` }
          cy.request({ url: `${GW}/api/business/settings`, headers: auth }).then((mine) => {
            cy.request({ url: `${GW}/api/business/settings`, headers: { ...auth, 'X-Org-Id': String(demoOrg) } }).then((spoof) => {
              expect(list(spoof.body), 'the spoofed header changed nothing — same org, same answer').to.deep.eq(list(mine.body))
            })
          })
          const key = 'pos.customer.walkInName'
          cy.request({ url: `${GW}/api/business/settings`, headers: { Authorization: `Bearer ${demoTok}` } }).then((before) => {
            const b = list(before.body).find((e) => e.key === key)
            cy.request({ method: 'POST', url: `${GW}/api/business/settings?key=${key}&value=SPOOF-${RUN}`,
              headers: { ...auth, 'X-Org-Id': String(demoOrg) }, failOnStatusCode: false })
            cy.request({ url: `${GW}/api/business/settings`, headers: { Authorization: `Bearer ${demoTok}` } }).then((after) => {
              const a = list(after.body).find((e) => e.key === key)
              expect(a.value, `org ${demoOrg} untouched by a spoofed header`).to.eq(b.value)
            })
            // the write landed in the OWNER's org (the token's) — put it back
            cy.request({ method: 'POST', url: `${GW}/api/business/settings/reset?key=${key}`, headers: auth, failOnStatusCode: false })
          })
          cy.then(() => record('platform', 'X-Org-Id spoof stripped', true, `demo org ${demoOrg}`))
        })
      })
    })

    it('7 a tenant owner cannot reach the operator console (plans, entitlements, tenants)', () => {
      cy.loginAsOwner()
      ;['/platform/organizations?size=5', '/platform/entitlements?organizationId=6'].forEach((url) => {
        cy.request({ url, failOnStatusCode: false }).then((r) => {
          expect(r.status === 200 && ok(r.body), `${url} refused for a tenant owner: ${r.status}`).to.eq(false)
        })
      })
      cy.request({ method: 'POST', url: '/platform/entitlement', form: true, failOnStatusCode: false,
        body: { organizationId: 13, capability: 'orderTypes', status: 'ACTIVE', reason: 'self-grant attempt' } }).then((r) => {
        expect(r.status === 200 && ok(r.body), `a tenant cannot grant itself a capability: ${r.status}`).to.eq(false)
      })
      cy.then(() => record('platform', 'operator console refused to a tenant', true, ''))
    })
  })
})
