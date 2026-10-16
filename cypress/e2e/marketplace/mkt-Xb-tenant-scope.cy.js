/**
 * MKT-Xb — every file and report says whose it is, and carries no one else's rows.
 * Source R21.2 (organization_id on every tenant-owned entity, plus owner / custodian / seller / fulfiller org), R21.3
 * (every cache key, event, file, report and job carries the org scope), R22.1 (the tenant comes from the login, never
 * from what the browser sends). Contract: microservices/docs/slices/mkt-Xb-tenant-scope.md
 *
 * Two sellers download their period summary from Sale → Marketplace → Statement: each file is named for its own
 * organisation and opens with the seller's name and organisation id; neither holds the other's. A seller that asks for
 * another organisation's report by adding it to the request still gets its own. The operator's file of every seller is
 * named "all-sellers", and every line in it starts with its seller's organisation id. R21.2 is the unit test
 * TenantScopeTest (every entity classified); the entity rows are not visible from a browser.
 */
const { gate, SELLER_A, SELLER_B, UI, ok, data, get, makeSeller, openMarketplace } = require('./mkt-helpers')

const MINE = '/mkt/settlementReport'
const ALL = '/platform/mkt/settlementReport'
const NAMES = { [SELLER_A]: 'Shahzad Mobile Shop', [SELLER_B]: 'Mobile Distributor' }

gate('Xb')('MKT-Xb — tenant scope on every file and report', () => {
  const S = {}
  const as = (email) => cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
  const lines = (text) => text.replace(/^﻿/, '').trim().split(/\r\n/)

  /** The seller downloads its summary in the real screen; yields {api, text}. */
  const download = (email) => {
    const out = {}
    as(email)
    get(MINE).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); out.api = data(r.body) })
    openMarketplace()
    cy.intercept('GET', '**/mkt/settlementReport*').as('mine')
    cy.get('#mktStatementTab').click()
    cy.wait('@mine')
    cy.get('#mktMyRepCsv').scrollIntoView().should('not.be.disabled')
    cy.then(() => cy.get('#mktMyRepScope').should('have.text', `For ${NAMES[email]}, organisation ${out.api.organizationId}`))
    cy.get('#mktMyRepCsv').click()
    return cy.then(() => cy.readFile(`cypress/downloads/marketplace-statement-org${out.api.organizationId}-${out.api.from}-to-${out.api.to}.csv`, 'utf8'))
      .then((text) => { out.text = text; return out })
  }

  before(() => {
    makeSeller(SELLER_A)
    makeSeller(SELLER_B)
    cy.loginAsOperator()
    cy.orgOf(SELLER_A).then((o) => { S.a = o.id })
    cy.orgOf(SELLER_B).then((o) => { S.b = o.id })
    cy.then(() => expect(S.a, 'two different sellers').to.not.eq(S.b))
  })

  it('MKT-Xb-01 [MKT-R21.3] [MKT-R22.1] a seller\'s statement file is named for its organisation and opens with its name and id (real UI)', () => {
    download(SELLER_A).then(({ api, text }) => {
      expect(api.organizationId, 'the report is scoped to the login').to.eq(S.a)
      expect(api.scope).to.eq('Shahzad Mobile Shop')
      expect(api.rows.map((x) => x.organizationId)).to.deep.eq([S.a])
      const l = lines(text)
      expect(l.slice(0, 4)).to.deep.eq(['Seller,Shahzad Mobile Shop', `Organisation ID,${S.a}`, `Period,${api.from} to ${api.to}`, 'Line,Amount'])
      expect(l).to.include(`Owed to you at the end,${Number(api.rows[0].closing).toFixed(2)}`)
      expect(l[l.length - 1]).to.eq(`Sale lines settled,${api.rows[0].lines}`)
    })
  })

  it('MKT-Xb-02 [MKT-R21.3] [MKT-R22.1] the other seller\'s file is its own and never holds the first one\'s; naming another org in the request changes nothing', () => {
    download(SELLER_B).then(({ api, text }) => {
      expect(api.organizationId).to.eq(S.b)
      const l = lines(text)
      expect(l.slice(0, 2)).to.deep.eq(['Seller,Mobile Distributor', `Organisation ID,${S.b}`])
      expect(text, 'no trace of the first seller').to.not.contain('Shahzad Mobile Shop')
      expect(l.filter((x) => x.startsWith('Organisation ID,'))).to.deep.eq([`Organisation ID,${S.b}`])
    })
    for (const q of [`organizationId=${S.a}`, `org=${S.a}`, `orgId=${S.a}`, `sellerOrganizationId=${S.a}`]) {
      get(`${MINE}?${q}`).then((r) => {
        expect(ok(r.body), q).to.eq(true)
        expect(data(r.body).organizationId, `${q}: still its own`).to.eq(S.b)
        expect(data(r.body).rows.map((x) => x.organizationId), q).to.deep.eq([S.b])
      })
    }
  })

  it('MKT-Xb-03 [MKT-R21.3] the operator\'s file of every seller is named "all-sellers" and each line names its seller\'s organisation (real UI)', () => {
    cy.loginAsOperator()
    get(ALL).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      S.all = data(r.body)
      expect(S.all.organizationId).to.eq(null)
      expect(S.all.scope).to.eq('All sellers')
    })
    cy.visit(UI.operatorPage)
    cy.get('#platMktPayoutsBtn').click()
    cy.get('#mktRepCsv').should('not.be.disabled').click()
    cy.then(() => cy.readFile(`cypress/downloads/settlement-report-all-sellers-${S.all.from}-to-${S.all.to}.csv`, 'utf8')).then((text) => {
      const l = lines(text)
      expect(l[0].startsWith('Seller ID,Seller,')).to.eq(true)
      const body = l.slice(1, -1)
      expect(body.length).to.eq(S.all.rows.length)
      body.forEach((x, i) => expect(x.startsWith(`${S.all.rows[i].organizationId},`), x).to.eq(true))
      expect(l[l.length - 1].startsWith(',All sellers,')).to.eq(true)
    })
  })
})
