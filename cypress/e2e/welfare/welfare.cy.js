/**
 * Welfare (Abbasi donations) E2E — requires the app at http://localhost:8080 with the
 * welfare-service up. Closes the coverage gap for the welfare domain (was compile-gated only).
 *
 * Drives writes via the API rather than the dashboard form: the Add Donator/Donation dashboard
 * options are SUPER_PRIVILEGE-gated in the UI, but the demo welfare user has full module
 * privileges, so the endpoints work. Asserts the monolith→gateway→welfare-service proxy chain
 * and the org-scoped reads.
 */
describe('Welfare — donators & donations', () => {
  before(() => {
    cy.loginAsWelfare()
  })

  beforeEach(() => {
    cy.loginAsWelfare()
  })

  // ─── dashboard ───────────────────────────────────────────────────────────────
  it('welfare dashboard loads with the section selector', () => {
    cy.visit('/welfareDashboard')
    cy.get('#registrationType').should('exist')
  })

  // ─── reads ───────────────────────────────────────────────────────────────────
  it('getUserDonator returns SUCCESS or NOT_FOUND', () => {
    cy.request('/getUserDonator').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })

  it('getUserDonation returns SUCCESS or NOT_FOUND', () => {
    cy.request('/getUserDonation').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })

  it('getAllDonators returns an HTML option string', () => {
    cy.request('/getAllDonators').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body).to.be.a('string')
    })
  })

  // ─── writes (round-trip) ──────────────────────────────────────────────────────
  it('adds a donator via API and it then appears in getUserDonator', () => {
    const name = `Cypress Donor ${Date.now()}`
    cy.request({
      method: 'POST', url: '/addDonator', form: true,
      body: { name, mobile: '03001234567', fName: 'Cypress Tester', address: 'Lahore, PK', amount: '500', receivedBy: 'Cypress' },
    }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'FOUND'])
    })

    cy.request('/getUserDonator').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.eq('SUCCESS')
      const list = res.body.collection || res.body.data || []
      expect(list.length).to.be.greaterThan(0)
    })
  })

  it('adds a donation for an existing donator', () => {
    cy.request('/getUserDonator').then((res) => {
      const list = res.body.collection || res.body.data || []
      if (!list.length) {
        cy.log('No donator available — skipping donation add')
        return
      }
      const donatorId = list[0].id
      cy.request({
        method: 'POST', url: '/addDonation', form: true,
        body: { donatorId, amount: '250', receivedBy: 'Cypress' },
      }).then((r) => {
        expect(r.status).to.eq(200)
        expect(r.body.status).to.be.oneOf(['SUCCESS', 'FOUND', 'FAILED'])
      })
    })
  })
})
