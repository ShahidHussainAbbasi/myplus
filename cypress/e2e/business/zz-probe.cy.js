describe('probe', () => {
  it('one Enter: customer vs item picker', () => {
    cy.viewport(1600, 900)
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
    cy.wait(4000)

    const out = {}
    // instrument: record open-state as OUR document handler sees it
    cy.window().then((w) => {
      w.__trace = []
      w.jQuery(w.document).on('keydown', '.bootstrap-select > button', function (e) {
        const $bs = w.jQuery(this).closest('.bootstrap-select')
        w.__trace.push({
          id: $bs.prev('select').attr('id'),
          openAtDocHandler: $bs.hasClass('open'),
          defaultPrevented: e.isDefaultPrevented(),
        })
      })
    })

    cy.get('#sellCustomerDD').next('.bootstrap-select').find('button').focus().type('{enter}')
    cy.wait(500)
    cy.window().then((w) => {
      out.cust_openAfter1Enter = w.jQuery('#sellCustomerDD').next('.bootstrap-select').hasClass('open')
      out.cust_focus = w.document.activeElement.id || w.document.activeElement.className
    })

    cy.get('#sellItemDD').next('.bootstrap-select').find('button').focus().type('{enter}')
    cy.wait(500)
    cy.window().then((w) => {
      out.item_openAfter1Enter = w.jQuery('#sellItemDD').next('.bootstrap-select').hasClass('open')
      out.trace = w.__trace
      throw new Error('PROBE ' + JSON.stringify(out))
    })
  })
})
