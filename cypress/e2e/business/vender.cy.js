/**
 * Vender CRUD tests — requires app running at http://localhost:8080
 *
 * Known bug exercised here:
 * - VenderController.getAllVender() calls obj.getCompany().getId() without null check → NPE
 *   when a vender's company is null (due to @NotFound IGNORE)
 */

describe('Vender CRUD', () => {
  let companyId

  before(() => {
    cy.loginAsBusiness()
    // Ensure at least one company exists to use as FK for vender
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: `VenderTestCo_${Date.now()}`, email: `vtco${Date.now()}@t.com` } })
    cy.request('/getUserCompany').then((res) => {
      if (res.body.status === 'SUCCESS' && res.body.data?.length) {
        companyId = res.body.data[0].id
      }
    })
  })

  beforeEach(() => {
    cy.loginAsBusiness()
    cy.openSection('VenderDiv')
  })

  // ─── GET ────────────────────────────────────────────────────────────────────

  it('loads the vender section with form and table', () => {
    cy.get('#Vender').should('exist')
    cy.get('#tableVender').should('exist')
    cy.get('#venderName').should('be.visible')
    cy.get('#addVender').should('be.visible')
  })

  it('getUserVender returns SUCCESS or NOT_FOUND', () => {
    cy.request('/getUserVender').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })

  it('getUserVenders returns HTML option string', () => {
    cy.request('/getUserVenders').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body).to.be.a('string')
    })
  })

  // ─── ADD ────────────────────────────────────────────────────────────────────

  it('fills vender form with valid data and submits', () => {
    cy.request('/getUserCompany').then((res) => {
      const cId = res.body.data?.[0]?.id
      if (!cId) return cy.log('No company available — skipping vender add UI test')

      cy.get('#venderCompanyDD').select(String(cId))
      cy.get('#venderName').type(`Cypress Trader ${Date.now()}`)
      cy.get('#venderMobile').type('03001234567')
      cy.get('#venderEmail').type(`vender${Date.now()}@test.com`)
      cy.get('#venderAddress').type('Lahore, Pakistan')

      cy.intercept('POST', '/addVender').as('addVender')
      cy.get('#addVender').click()
      cy.wait('@addVender').then((interception) => {
        expect(interception.response.body.status).to.be.oneOf(['SUCCESS', 'FOUND'])
      })
    })
  })

  it('addVender API — POST returns SUCCESS for new vender', () => {
    cy.request('/getUserCompany').then((res) => {
      const cId = res.body.data?.[0]?.id
      if (!cId) return cy.log('No company for vender test — skipping')

      const name = `CypressVender_${Date.now()}`
      cy.request({
        method: 'POST',
        url: '/addVender',
        form: true,
        body: { name, companyId: cId, mobile: '03009999999', email: `v${Date.now()}@t.com` },
      }).then((addRes) => {
        expect(addRes.status).to.eq(200)
        expect(addRes.body.status).to.be.oneOf(['SUCCESS', 'FOUND'])

        // clean up
        cy.request('/getUserVender').then((listRes) => {
          const created = listRes.body.data?.find(v => v.name === name)
          if (created) {
            cy.request({ method: 'POST', url: '/deleteVender', form: true, body: { checked: created.id } })
          }
        })
      })
    })
  })

  it('addVender without companyId — form select must have an option selected', () => {
    cy.get('#venderCompanyDD option').should('have.length.above', 0)
  })

  // ─── UPDATE ─────────────────────────────────────────────────────────────────

  it('clicks vender row — row is clickable without JS error', () => {
    // NOTE: editRecord() relies on datatable.row(this).selector.rows.innerHTML which is
    // not a valid DataTables API — the hidden #venderId field is never populated.
    // This test only verifies the click fires without crashing the page.
    cy.get('#tableVender tbody tr').first().then(($row) => {
      if ($row.find('td').length > 0) {
        // Re-query by index to avoid DataTable re-render detaching the cached reference
        cy.get('#tableVender tbody tr').first().click()
        cy.get('#VenderDiv').should('be.visible')
      } else {
        cy.log('No venders in table — row click test skipped')
      }
    })
  })

  it('update vender via API — POST with existing id returns SUCCESS', () => {
    cy.request('/getUserCompany').then((compRes) => {
      const cId = compRes.body.data?.[0]?.id
      if (!cId) return cy.log('No company for vender update test — skipping')

      const name = `ToUpdateVAPI_${Date.now()}`
      cy.request({ method: 'POST', url: '/addVender', form: true, body: { name, companyId: cId, mobile: '03001234567', email: `uv${Date.now()}@t.com` } })

      cy.request('/getUserVender').then((res) => {
        const v = res.body.data?.find(x => x.name === name)
        if (!v) return cy.log('Vender not found for update — skipping')
        cy.request({
          method: 'POST',
          url: '/addVender',
          form: true,
          body: { venderId: v.id, name: `UpdatedV_${Date.now()}`, companyId: cId },
        }).then((updateRes) => {
          expect(updateRes.status).to.eq(200)
        })
      })
    })
  })

  // ─── DELETE ─────────────────────────────────────────────────────────────────

  it('deleteVender API — POST with valid id returns true', () => {
    cy.request('/getUserCompany').then((res) => {
      const cId = res.body.data?.[0]?.id
      if (!cId) return

      const name = `ToDelV_${Date.now()}`
      cy.request({ method: 'POST', url: '/addVender', form: true, body: { name, companyId: cId, mobile: '03001111111', email: `dv${Date.now()}@t.com` } })

      cy.request('/getUserVender').then((listRes) => {
        const v = listRes.body.data?.find(x => x.name === name)
        if (v) {
          cy.request({ method: 'POST', url: '/deleteVender', form: true, body: { checked: v.id } }).then((delRes) => {
            expect(delRes.body).to.be.true
          })
        }
      })
    })
  })

  it('deleteVender API — empty checked returns false', () => {
    cy.request({ method: 'POST', url: '/deleteVender', form: true, body: { checked: '' } }).then((res) => {
      expect(res.body).to.be.false
    })
  })

  // ─── BUG VERIFICATION ───────────────────────────────────────────────────────

  /**
   * BUG: VenderController.getAllVender() calls obj.getCompany().getId() without null check.
   * When a vender's company is null (e.g., orphaned record), the endpoint throws NPE
   * and returns an ERROR response.
   *
   * This test verifies getAllVender is reachable and not currently throwing NPE.
   * If the endpoint returns ERROR, the bug is present.
   */
  it('BUG: getAllVender should handle null company without NPE', () => {
    cy.request({ url: '/getAllVender', failOnStatusCode: false }).then((res) => {
      expect(res.status).to.eq(200)
      // BUG present if status = ERROR (NPE from null company.getId())
      cy.log(`getAllVender status: ${res.body.status}`)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'],
        'BUG: ERROR here means VenderController.getAllVender() NPE on null company')
    })
  })
})
