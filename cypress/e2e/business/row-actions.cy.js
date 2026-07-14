/**
 * Register screens: the row checkbox and the Edit button are one aligned control.
 *
 * They used to be loose siblings in the same cell — appended with a bare space between them — so they sat on
 * different baselines, drifted apart as the column resized, and wrapped onto separate lines on a phone.
 * main.js now wraps them in .row-actions and /css/crud-modal.css keeps them together.
 *
 * Alignment is asserted as geometry (their vertical centres line up, they stay on one line), not by eyeballing
 * a screenshot — that is the only way a layout regression fails a test rather than a code review.
 */
describe('Register screens: row checkbox + Edit button', () => {
  const openCustomers = () => {
    cy.visit('/businessDashboard')
    cy.get('#registrationType', { timeout: 10000 }).select('CustomerDiv', { force: true })
    cy.get('#CustomerDiv').should('be.visible')
    cy.get('#tableCustomer tbody tr', { timeout: 10000 }).should('have.length.greaterThan', 0)
  }

  beforeEach(() => cy.loginAsOwner())

  it('checkbox and Edit sit in one wrapper, vertically centred on each other', () => {
    openCustomers()

    cy.get('#tableCustomer tbody tr').first().within(() => {
      cy.get('.row-actions').should('exist')
      cy.get('.row-actions input[type="checkbox"]').should('exist')
      cy.get('.row-actions .js-edit-row').should('be.visible')
    })

    // Geometry: their centre lines must agree (this is what "not aligned" actually meant).
    cy.get('#tableCustomer tbody tr').first().find('.row-actions input[type="checkbox"]')
      .then(($cb) => {
        const cb = $cb[0].getBoundingClientRect()
        cy.get('#tableCustomer tbody tr').first().find('.js-edit-row').then(($b) => {
          const btn = $b[0].getBoundingClientRect()
          const cbMid = cb.top + cb.height / 2
          const btnMid = btn.top + btn.height / 2
          expect(Math.abs(cbMid - btnMid), 'checkbox and Edit share a centre line').to.be.lessThan(3)
          expect(btn.left, 'Edit sits after the checkbox, on the same row').to.be.greaterThan(cb.right - 1)
        })
      })
  })

  it('stays on one line on a phone (label drops, icon remains)', () => {
    cy.viewport('iphone-x')
    openCustomers()

    cy.get('#tableCustomer tbody tr').first().find('.row-actions').then(($w) => {
      const wrap = $w[0].getBoundingClientRect()
      const cb = $w.find('input[type="checkbox"]')[0].getBoundingClientRect()
      const btn = $w.find('.js-edit-row')[0].getBoundingClientRect()
      // One line: the wrapper is no taller than a single control (it would roughly double if they wrapped).
      expect(wrap.height, 'checkbox and button did not wrap onto separate lines')
        .to.be.lessThan(Math.max(cb.height, btn.height) + 12)
    })

    // The word goes away, the pencil (and the accessible name) stay.
    cy.get('#tableCustomer tbody tr').first().find('.js-edit-row .row-actions__label').should('not.be.visible')
    cy.get('#tableCustomer tbody tr').first().find('.js-edit-row .glyphicon-pencil').should('be.visible')
    cy.get('#tableCustomer tbody tr').first().find('.js-edit-row').should('have.attr', 'aria-label', 'Edit record')
  })

  it('the Edit button still opens the record, and the checkbox still selects it', () => {
    openCustomers()

    // The wrapper must not have broken either behaviour — it moved the same checkbox element, not a clone.
    cy.get('#tableCustomer tbody tr').first().find('.row-actions input[type="checkbox"]').check({ force: true })
    cy.get('#tableCustomer tbody tr').first().find('.row-actions input[type="checkbox"]').should('be.checked')

    cy.get('#tableCustomer tbody tr').first().find('.js-edit-row').click({ force: true })
    cy.get('#CustomerModal').should('be.visible')
  })
})
