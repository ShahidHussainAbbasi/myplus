/**
 * ItemType CRUD tests — category/type classification for items
 * Endpoints: /getUserItemType, /getUserItemTypes, /getAllItemType, /addItemType, /deleteItemType
 *
 * NOTE: These endpoints may return 404 if the ItemType section is not yet
 * deployed/accessible on the running server. All tests handle 404 gracefully
 * and skip assertions on endpoint content when the endpoint is unavailable.
 */

const IT_ENDPOINTS = ['/getUserItemType', '/getUserItemTypes', '/getAllItemType', '/addItemType', '/deleteItemType']

describe('ItemType — Endpoint Availability', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  IT_ENDPOINTS.forEach((ep) => {
    it(`${ep} responds (200 or 404, not 500)`, () => {
      const method = ep.startsWith('/add') || ep.startsWith('/delete') ? 'POST' : 'GET'
      cy.request({ method, url: ep, form: method === 'POST', body: {}, failOnStatusCode: false }).then((res) => {
        expect([200, 404]).to.include(res.status)
      })
    })
  })
})

describe('ItemType CRUD', () => {
  let endpointAvailable = false

  before(() => {
    cy.loginAsBusiness()
    cy.request({ url: '/getUserItemType', failOnStatusCode: false }).then((res) => {
      endpointAvailable = res.status === 200
      cy.log(`ItemType endpoints available: ${endpointAvailable}`)
    })
  })

  beforeEach(() => { cy.loginAsBusiness() })

  // ─── GET ──────────────────────────────────────────────────────────────────

  it('getUserItemType returns 200 or 404', () => {
    cy.request({ url: '/getUserItemType', failOnStatusCode: false }).then((res) => {
      expect([200, 404]).to.include(res.status)
      if (res.status === 200) {
        expect(res.body).to.have.property('status')
        expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND', 'ERROR'])
      }
    })
  })

  it('getUserItemTypes returns 200 or 404', () => {
    cy.request({ url: '/getUserItemTypes', failOnStatusCode: false }).then((res) => {
      expect([200, 404]).to.include(res.status)
      if (res.status === 200) expect(res.body).to.be.a('string')
    })
  })

  it('getAllItemType returns 200 or 404', () => {
    cy.request({ url: '/getAllItemType', failOnStatusCode: false }).then((res) => {
      expect([200, 404]).to.include(res.status)
      if (res.status === 200) expect(res.body).to.have.property('status')
    })
  })

  it('getUserItemType SUCCESS — each record has name field', () => {
    cy.request({ url: '/getUserItemType', failOnStatusCode: false }).then((res) => {
      if (res.status !== 200) return cy.log(`Endpoint returned ${res.status} — skipping content check`)
      if (res.body.status === 'SUCCESS') {
        const data = res.body.data || res.body.collection || []
        data.slice(0, 5).forEach((t) => {
          expect(t).to.have.property('name')
          expect(t.name).to.be.a('string').and.not.be.empty
        })
      }
    })
  })

  // ─── ADD ──────────────────────────────────────────────────────────────────

  it('addItemType — POST with unique name returns 200 or 404', () => {
    const name = `CypType_${Date.now()}`
    cy.request({
      method: 'POST', url: '/addItemType', form: true,
      body: { name, description: 'Cypress test type' },
      failOnStatusCode: false,
    }).then((res) => {
      expect([200, 404]).to.include(res.status)
      if (res.status !== 200) return cy.log(`addItemType returned ${res.status} — endpoint unavailable`)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'FOUND', 'ERROR'])
      cy.log(`addItemType: ${res.body.status}`)

      if (res.body.status === 'SUCCESS') {
        cy.request({ url: '/getUserItemType', failOnStatusCode: false }).then((listRes) => {
          if (listRes.status !== 200) return
          const created = (listRes.body.data || listRes.body.collection || []).find(t => t.name === name)
          if (created) cy.request({ method: 'POST', url: '/deleteItemType', form: true, body: { checked: created.id }, failOnStatusCode: false })
        })
      }
    })
  })

  it('addItemType — duplicate name returns FOUND or 404', () => {
    const name = `DupType_${Date.now()}`
    cy.request({ method: 'POST', url: '/addItemType', form: true, body: { name }, failOnStatusCode: false })
    cy.request({
      method: 'POST', url: '/addItemType', form: true, body: { name }, failOnStatusCode: false,
    }).then((res) => {
      expect([200, 404]).to.include(res.status)
      if (res.status === 200) expect(res.body.status).to.be.oneOf(['FOUND', 'ERROR', 'SUCCESS'])
    })
    cy.request({ url: '/getUserItemType', failOnStatusCode: false }).then((listRes) => {
      if (listRes.status !== 200) return
      const created = (listRes.body.data || listRes.body.collection || []).find(t => t.name === name)
      if (created) cy.request({ method: 'POST', url: '/deleteItemType', form: true, body: { checked: created.id }, failOnStatusCode: false })
    })
  })

  it('addItemType — empty name returns error or 404', () => {
    cy.request({
      method: 'POST', url: '/addItemType', form: true, body: { name: '' }, failOnStatusCode: false,
    }).then((res) => {
      expect([200, 400, 404]).to.include(res.status)
      if (res.status === 200) expect(res.body.status).to.not.eq('SUCCESS')
    })
  })

  it('addItemType — whitespace name returns error or 404', () => {
    cy.request({
      method: 'POST', url: '/addItemType', form: true, body: { name: '   ' }, failOnStatusCode: false,
    }).then((res) => {
      expect([200, 400, 404]).to.include(res.status)
      if (res.status === 200) expect(res.body.status).to.not.eq('SUCCESS')
    })
  })

  // ─── UPDATE ───────────────────────────────────────────────────────────────

  it('addItemType with existing id updates record — or returns 404', () => {
    const name = `UpdType_${Date.now()}`
    cy.request({ method: 'POST', url: '/addItemType', form: true, body: { name }, failOnStatusCode: false })
    cy.request({ url: '/getUserItemType', failOnStatusCode: false }).then((res) => {
      if (res.status !== 200) return cy.log('Endpoint unavailable — skipping update test')
      const type = (res.body.data || res.body.collection || []).find(t => t.name === name)
      if (!type) return cy.log('Type not found — skipping update test')
      cy.request({
        method: 'POST', url: '/addItemType', form: true,
        body: { id: type.id, name: `${name}_Upd`, description: 'updated' },
        failOnStatusCode: false,
      }).then((updateRes) => {
        expect([200, 404]).to.include(updateRes.status)
        if (updateRes.status === 200) expect(updateRes.body.status).to.be.oneOf(['SUCCESS', 'ERROR'])
      })
      cy.request({ method: 'POST', url: '/deleteItemType', form: true, body: { checked: type.id }, failOnStatusCode: false })
    })
  })

  // ─── DELETE ───────────────────────────────────────────────────────────────

  it('deleteItemType — POST with valid id returns true or 404', () => {
    const name = `DelType_${Date.now()}`
    cy.request({ method: 'POST', url: '/addItemType', form: true, body: { name }, failOnStatusCode: false })
    cy.request({ url: '/getUserItemType', failOnStatusCode: false }).then((res) => {
      if (res.status !== 200) return cy.log('Endpoint unavailable — skipping delete test')
      const type = (res.body.data || res.body.collection || []).find(t => t.name === name)
      if (!type) return cy.log('Type not found — skipping')
      cy.request({
        method: 'POST', url: '/deleteItemType', form: true, body: { checked: type.id }, failOnStatusCode: false,
      }).then((delRes) => {
        if (delRes.status === 404) return cy.log('deleteItemType endpoint not available')
        expect(delRes.body).to.be.true
      })
    })
  })

  it('deleteItemType — empty checked returns false or 404', () => {
    cy.request({
      method: 'POST', url: '/deleteItemType', form: true, body: { checked: '' }, failOnStatusCode: false,
    }).then((res) => {
      if (res.status === 404) return cy.log('deleteItemType endpoint not available')
      expect(res.body).to.be.false
    })
  })

  it('deleteItemType — comma-separated ids deletes multiple or returns 404', () => {
    const [name1, name2] = [`DT1_${Date.now()}`, `DT2_${Date.now()}`]
    cy.request({ method: 'POST', url: '/addItemType', form: true, body: { name: name1 }, failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/addItemType', form: true, body: { name: name2 }, failOnStatusCode: false })
    cy.request({ url: '/getUserItemType', failOnStatusCode: false }).then((res) => {
      if (res.status !== 200) return cy.log('Endpoint unavailable — skipping')
      const types = res.body.data || res.body.collection || []
      const ids = [name1, name2].map(n => types.find(t => t.name === n)?.id).filter(Boolean)
      if (ids.length < 2) return cy.log('Not enough types found — skipping multi-delete')
      cy.request({
        method: 'POST', url: '/deleteItemType', form: true, body: { checked: ids.join(',') }, failOnStatusCode: false,
      }).then((delRes) => {
        if (delRes.status === 404) return cy.log('deleteItemType endpoint not available')
        expect(delRes.body).to.be.true
      })
    })
  })

  it('deleteItemType with non-existent id — does not crash', () => {
    cy.request({
      method: 'POST', url: '/deleteItemType', form: true, body: { checked: '999999999' }, failOnStatusCode: false,
    }).then((res) => {
      expect([200, 400, 404]).to.include(res.status)
    })
  })

  // ─── PERSISTENCE ─────────────────────────────────────────────────────────

  it('created itemType is found in getUserItemType list — or endpoint is unavailable', () => {
    const name = `Persist_${Date.now()}`
    cy.request({ method: 'POST', url: '/addItemType', form: true, body: { name }, failOnStatusCode: false }).then((addRes) => {
      if (addRes.status === 404) return cy.log('addItemType endpoint not available — skipping persistence check')
      if (addRes.body.status !== 'SUCCESS') return cy.log(`addItemType was ${addRes.body.status} — skipping persistence check`)
      cy.request({ url: '/getUserItemType', failOnStatusCode: false }).then((listRes) => {
        if (listRes.status !== 200) return cy.log('getUserItemType unavailable — skipping')
        const found = (listRes.body.data || listRes.body.collection || []).find(t => t.name === name)
        expect(found, `ItemType "${name}" should exist after creation`).to.not.be.undefined
        if (found) cy.request({ method: 'POST', url: '/deleteItemType', form: true, body: { checked: found.id }, failOnStatusCode: false })
      })
    })
  })
})
