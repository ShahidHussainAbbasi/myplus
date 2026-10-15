/**
 * HC-1c — the clinic ↔ pharmacy link, the refusals (the screen cannot show these: they are what a hand-sent request
 * would try). Design: microservices/docs/slices/hc-1cd-clinic-pharmacy-link.md. Screen walk: docs/hc-1c-link-guide-screens.
 *
 *   A1  a SHOP (owner.business@, type BUSINESS) cannot search pharmacies, nor send a request      → refused
 *   A2  a pharmacy that has NOT opted in is not found, and a request to it is refused               → refused
 *   A3  the clinic may not accept its own request; ANOTHER pharmacy may not accept it               → not found
 *   A4  a second request while one is waiting or active is refused (one pharmacy per clinic, D1)    → refused
 *   A5  ending needs a reason                                                                       → refused
 *   A6  CONTROL: the addressed pharmacy accepts, then ends with a reason                            → ok
 *
 * Refusals answer 200 + success:false (the platform's convention for a business refusal) or 4xx — either is a refusal;
 * the assertion is that NOTHING changed (the link list is read back).
 *
 * Run: npx cypress run --spec cypress/e2e/healthcare/hc-1c-link-api.cy.js
 */
const RUN = String(Date.now()).slice(-6)
const MAILPIT = 'http://localhost:8025'
const PW = 'Kiwi#Lamp58'
const T = {
  clinic: { org: `HC1C API Clinic ${RUN}`, email: `hc1c.api.clinic.${RUN}@myplus.test`, type: 'hospital_clinic' },
  open:   { org: `HC1C API Open ${RUN}`, email: `hc1c.api.open.${RUN}@myplus.test`, type: 'pharmacy' },
  other:  { org: `HC1C API Other ${RUN}`, email: `hc1c.api.other.${RUN}@myplus.test`, type: 'pharmacy' },
  closed: { org: `HC1C API Closed ${RUN}`, email: `hc1c.api.closed.${RUN}@myplus.test`, type: 'pharmacy' },
}
const S = {}

const refused = (r) => r.status >= 400 || (r.body && r.body.success === false)
const setPasswordLink = (email) => {
  const look = (tries) => cy.request(`${MAILPIT}/api/v1/messages?limit=100`).then((r) => {
    const m = (r.body.messages || []).find((x) => (x.To || []).some((a) => a.Address === email))
    if (m) return cy.request(`${MAILPIT}/api/v1/message/${m.ID}`).its('body').then((b) => /token=([A-Za-z0-9-]+)/.exec(b.Text || b.HTML || '')[1])
    if (tries <= 0) throw new Error(`no set-password e-mail to ${email}`)
    return cy.wait(1000).then(() => look(tries - 1))
  })
  return look(20)
}
const setPassword = (email) => setPasswordLink(email).then((token) => {
  cy.clearCookies()
  cy.visit(`/user/changePassword?token=${token}`)
  cy.get('#password').type(PW, { log: false })
  cy.get('#matchPassword').type(PW, { log: false })
  cy.contains('button', /update|save|submit/i).click()
  cy.location('pathname', { timeout: 15000 }).should('include', '/login')
})
// validate on a page that answers 200 for that tenant (a redirect would force a fresh login every time)
const as = (t) => cy.loginAs(t.email, PW, t.type === 'hospital_clinic' ? '/clinicHome' : '/businessDashboard', `hc1c-api-${RUN}-${t.email}`)
const myLinks = () => cy.request('/links').its('body').then((b) => (b && (b.data || b.collection)) || [])

describe('HC-1c link — refusals', () => {
  before(() => {
    cy.loginAsOperator()
    Object.values(T).forEach((t) => cy.request({ method: 'POST', url: '/platform/provisionTenant', form: true, body: {
      organizationName: t.org, email: t.email, firstName: 'Api', lastName: `Test ${RUN}`, plan: 'PRO', businessType: t.type } })
      .its('body.success').should('eq', true))
    Object.values(T).forEach((t) => setPassword(t.email))
    // two pharmacies opt in; "closed" does not
    ;[T.open, T.other].forEach((t) => {
      as(t)
      cy.request({ method: 'POST', url: '/links/settings', form: true, body: { acceptClinics: true } }).its('body.success').should('eq', true)
    })
  })

  after(() => {
    cy.loginAsOperator()
    Object.values(T).forEach((t) =>
      cy.request(`/platform/organizations?page=0&size=20&q=${encodeURIComponent(t.org)}`).then((r) => {
        const d = (r.body && (r.body.data || r.body.object || r.body)) || {}
        ;(d.rows || []).filter((o) => o && o.name === t.org && o.status === 'ACTIVE').forEach((o) =>
          cy.request({ method: 'POST', url: '/platform/status', form: true, failOnStatusCode: false,
            body: { organizationId: o.id, status: 'SUSPENDED', reason: 'gate cleanup' } }))
      }))
  })

  it('A1 a shop cannot search pharmacies or send a request', () => {
    cy.loginAsOwner()   // owner.business@ — type BUSINESS
    cy.request({ url: `/links/pharmacies?q=${RUN}`, failOnStatusCode: false }).then((r) => expect(refused(r), JSON.stringify(r.body)).to.eq(true))
  })

  it('A2 an un-opted pharmacy is not found, and a request to it is refused', () => {
    as(T.clinic)
    cy.request(`/links/pharmacies?q=${RUN}`).its('body').then((b) => {
      const rows = (b && (b.data || b.collection)) || []
      const names = rows.map((x) => x.name)
      expect(names, 'the opted-in pharmacies').to.include.members([T.open.org, T.other.org])
      expect(names, 'the pharmacy that did not opt in').not.to.include(T.closed.org)
      expect(Object.keys(rows[0] || {}).sort(), 'only an id and a name cross').to.deep.eq(['id', 'name'])
      S.openId = rows.find((x) => x.name === T.open.org).id
    })
    // the closed pharmacy's id, read as the operator (the clinic cannot learn it — that is the point)
    cy.loginAsOperator()
    cy.request(`/platform/organizations?page=0&size=20&q=${encodeURIComponent(T.closed.org)}`).then((r) => {
      const d = (r.body && (r.body.data || r.body.object || r.body)) || {}
      S.closedId = (d.rows || []).find((o) => o.name === T.closed.org).id
    })
    as(T.clinic)
    cy.then(() => cy.request({ method: 'POST', url: '/links', form: true, body: { pharmacyOrgId: S.closedId }, failOnStatusCode: false })
      .then((r) => expect(refused(r), JSON.stringify(r.body)).to.eq(true)))
    myLinks().should('have.length', 0)
  })

  it('A3 the clinic cannot accept its own request; another pharmacy cannot accept it', () => {
    as(T.clinic)
    cy.then(() => cy.request({ method: 'POST', url: '/links', form: true, body: { pharmacyOrgId: S.openId } }).its('body.success').should('eq', true))
    myLinks().then((rows) => { expect(rows).to.have.length(1); S.linkId = rows[0].id; expect(rows[0].status).to.eq('REQUESTED') })
    cy.then(() => cy.request({ method: 'POST', url: `/links/${S.linkId}/accept`, failOnStatusCode: false }).then((r) => expect(refused(r)).to.eq(true)))
    as(T.other)
    cy.then(() => cy.request({ method: 'POST', url: `/links/${S.linkId}/accept`, failOnStatusCode: false }).then((r) => expect(refused(r)).to.eq(true)))
    myLinks().should('have.length', 0)   // the other pharmacy does not even see it
    as(T.clinic)
    myLinks().then((rows) => expect(rows[0].status, 'still waiting').to.eq('REQUESTED'))
  })

  it('A4 a second request while one is waiting is refused', () => {
    as(T.clinic)
    cy.request(`/links/pharmacies?q=${RUN}`).its('body').then((b) => {
      const otherId = ((b && (b.data || b.collection)) || []).find((x) => x.name === T.other.org).id
      cy.request({ method: 'POST', url: '/links', form: true, body: { pharmacyOrgId: otherId }, failOnStatusCode: false })
        .then((r) => expect(refused(r), JSON.stringify(r.body)).to.eq(true))
    })
    myLinks().should('have.length', 1)
  })

  it('A5 ending needs a reason', () => {
    as(T.clinic)
    cy.then(() => cy.request({ method: 'POST', url: `/links/${S.linkId}/end`, form: true, body: { reason: ' ' }, failOnStatusCode: false })
      .then((r) => expect(refused(r)).to.eq(true)))
    myLinks().then((rows) => expect(rows[0].status).to.eq('REQUESTED'))
  })

  it('A6 CONTROL — the addressed pharmacy accepts, then ends with a reason', () => {
    as(T.open)
    cy.then(() => cy.request({ method: 'POST', url: `/links/${S.linkId}/accept` }).its('body.success').should('eq', true))
    myLinks().then((rows) => { expect(rows[0].status).to.eq('ACTIVE'); expect(rows[0].otherName).to.eq(T.clinic.org) })
    cy.then(() => cy.request({ method: 'POST', url: `/links/${S.linkId}/end`, form: true, body: { reason: 'gate cleanup' } }).its('body.success').should('eq', true))
    as(T.clinic)
    myLinks().then((rows) => expect(rows[0].status).to.eq('ENDED'))
  })
})
