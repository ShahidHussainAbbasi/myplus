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
    // Wait for a LOADED row, not merely for a `<tr>`.
    //
    // DataTables' empty-table placeholder IS a `<tr>` carrying one cell ("No data available"), so
    // `tbody tr` length > 0 is satisfied while the grid is still fetching — and the row-actions wrapper
    // does not exist yet, because main.js only builds it for rows that have a checkbox. The failure then
    // reads "Expected to find element: `.row-actions`, but never found it", which points at the wrapper
    // rather than at the wait that let an unloaded grid through. A real row has more than one cell.
    //
    // ORDER MATTERS: let the fetch finish FIRST, then look at the row.
    //
    // The wrapper is added by the DataTables drawCallback, which runs when the fetch completes. Asserting
    // the row before waiting for the overlay just races the same load from a different direction — the
    // first attempt at this fix did exactly that and still timed out, on an account that demonstrably has
    // ten customers, so an empty grid was never the explanation.
    cy.waitForAppReady()
    cy.get('#tableCustomer tbody tr:first td', { timeout: 20000 }).should('have.length.greaterThan', 1)
  }

  beforeEach(() => {
    cy.loginAsOwner()
    // SEED a row — do not hope one exists.
    //
    // Every case here inspects the FIRST row of #tableCustomer, so the grid must contain a real row.
    // `/getUserCustomer` scopes by `Customer.userId`, which the entity documents as an AUDIT field, so
    // this owner sees only customers THEY created — fine on this environment (it has ten) but not
    // guaranteed on a fresh one, where DataTables would render its "No data available" placeholder. That
    // placeholder IS a `<tr>`, so a bare `tbody tr` length > 0 wait passes on an empty grid and the
    // failure surfaces later and misleadingly as "Expected to find element: `.row-actions`" — main.js
    // only builds that wrapper for a row that has a checkbox.
    //
    // NOTE: seeding was NOT what fixed the failure seen here — the account already had customers, and the
    // real cause was asserting the row before the fetch completed (see openCustomers). It stays because
    // the precondition should be established rather than inherited.
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name: 'RowAct_' + Date.now(), contact: '0300' + String(Date.now()).slice(-6) },
    }).then((r) => {
      expect(r.body && r.body.status, `seed customer: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })
  })

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
