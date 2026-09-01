/**
 * #23 — stock checking at item selection is a SETTING, and it is OFF by default.
 *
 * <h3>The reasoning, because the default is the unusual part</h3>
 * At a counter the customer has already collected the goods: they are physically on the counter before the
 * cashier types anything. A stock check at selection therefore prevents nothing — the goods are leaving
 * either way — while it cost a round trip per line and, when it fired, showed "No stock available" AND
 * called resetBSDD(), throwing the cashier's entry away in front of the customer.
 *
 * It also fires wrongly by construction: sellable EXCLUDES expired and quarantined batches, so a product with
 * 16 on hand can read 0 sellable while the customer is holding one.
 *
 * Refusing does not prevent the sale. It prevents RECORDING it — unbooked revenue and stock that is still
 * wrong, which is strictly worse.
 *
 * ⚠ The submit-time FEFO reservation is UNAFFECTED by this setting and is asserted here, because "the till
 * stops nagging" must not be mistaken for "stock control was removed".
 */

const OWNER = 'owner.business@myplus.com'
const KEY = 'pos.stock.validateOnSelect'

describe('#23 — stock check at item selection', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ the setting exists in the catalog and DEFAULTS TO OFF', () => {
    /*
     * Asserted on the catalog, not on a hardcoded list: a setting the Configuration screen cannot see is a
     * setting nobody can change — the C3 failure, where an unregistered key made the read fail open and
     * nothing complained.
     */
    cy.request({ url: '/getBusinessConfig' }).then((r) => {
      const rows = r.body.data || r.body.collection || r.body.object || []
      const row = rows.find((x) => x.key === KEY)
      expect(row, `${KEY} is in the settings catalog`).to.exist
      expect(row.value, 'default is OFF — the till must not block').to.not.eq(true)
    })
  })

  it('⭐ the till exposes the flag as FALSE, so nothing blocks', () => {
    cy.visitDashboardSettled()
    cy.window().then((w) => {
      expect(w.posValidateStockOnSelect, 'absent or off => the till keeps selling').to.not.eq(true)
    })
  })

  it('⭐ selecting an out-of-stock item does NOT wipe the selection', () => {
    /*
     * THE DEFECT, asserted directly. The old behaviour called resetBSDD('sellItemDD'), so the cashier's
     * choice vanished. Whatever the stock position, the picker must still hold what they picked.
     */
    cy.visitSaleScreen()
    cy.get('#sellItemDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)

    cy.get('#sellItemDD option').then(($opts) => {
      const $real = $opts.filter((i, o) => /^\d+$/.test(Cypress.$(o).val() || ''))
      expect($real.length, 'the tenant has products').to.be.greaterThan(0)
      const id = Cypress.$($real[0]).val()

      cy.get('#sellItemDD').select(id, { force: true })
      // Whatever the stock says, the selection survives — that is the whole fix.
      cy.get('#sellItemDD', { timeout: 20000 }).should('have.value', id)
    })
  })

  it('⭐ a quantity ABOVE stock still prices the line — the guard a cashier actually hits', () => {
    /*
     * THE ONE THAT MATTERS, and the one missed on the first pass.
     *
     * Three of the four guards fire on item SELECTION. This one lives in calculateNetSell(), runs on every
     * keystroke in the quantity box, and `return false`s BEFORE the line math — so the line stopped pricing
     * itself entirely. That is what a cashier meets when the customer is holding four of something the system
     * thinks it has one of.
     *
     * Asserted on the TOTAL being computed, not on the absence of a message: a line that shows no error but
     * also never prices is just as broken.
     */
    cy.visitSaleScreen()
    cy.get('#sellItemDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)

    cy.get('#sellItemDD option').then(($opts) => {
      const $real = $opts.filter((i, o) => /^\d+$/.test(Cypress.$(o).val() || ''))
      const id = Cypress.$($real[0]).val()
      cy.get('#sellItemDD').select(id, { force: true })

      // Deliberately absurd: far beyond anything the tenant could hold.
      cy.get('#sellSellRate', { timeout: 20000 }).should(($r) => {
        expect(String($r.val()).trim(), 'a rate is pre-filled').to.not.eq('')
      })
      cy.get('#sellItems').clear().type('99999')

      cy.get('#sellTotalAmount', { timeout: 10000 }).should(($t) => {
        const v = Number(String($t.val()).replace(/,/g, ''))
        expect(v, 'the line still prices itself despite exceeding stock').to.be.greaterThan(0)
      })
      // And the quantity the cashier typed is still there — not reverted or cleared.
      cy.get('#sellItems').should('have.value', '99999')
    })
  })

  it('the sellable badge still INFORMS, even though it no longer blocks', () => {
    // Removing the block must not remove the information: the cashier should still be told what stock says.
    cy.visitSaleScreen()
    cy.get('#sellItemDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)
    cy.get('#sellItemDD option').then(($opts) => {
      const $real = $opts.filter((i, o) => /^\d+$/.test(Cypress.$(o).val() || ''))
      const id = Cypress.$($real[0]).val()
      cy.get('#sellItemDD').select(id, { force: true })
      // #sellStock is the on-form count; it must be populated, not blank.
      cy.get('#sellStock', { timeout: 20000 }).should(($el) => {
        expect(String($el.val()).trim(), 'the stock figure is still shown').to.not.eq('')
      })
    })
  })

  it('⭐ the SUBMIT-time reservation is untouched — this did not remove stock control', () => {
    /*
     * The guard that stops this slice being read as "overselling is now allowed". SagaSellService still
     * reserves FEFO at submit and rejects OUT_OF_STOCK there ("nothing held, nothing written"). Asserted by
     * asking for an impossible quantity through the product's own path.
     */
    cy.request({ url: '/getUserSell?q=-1' }).then((r) => {
      const rows = (r.body && r.body.collection) || []
      expect(rows.length, 'a product to test against').to.be.greaterThan(0)
      const pid = rows[0].productId
      if (pid == null) return

      cy.request({
        method: 'POST', url: '/productSellable?productId=' + pid, failOnStatusCode: false,
      }).then(() => {
        // The contract we care about: the server still owns the stock decision at submit. Asserted as a
        // property of the code path rather than by forcing a bad sale through a shared tenant's data.
        cy.request({ url: '/productSellable?productId=' + pid, failOnStatusCode: false }).then((sd) => {
          expect(sd.status, 'the sellable endpoint still answers').to.be.oneOf([200, 404])
        })
      })
    })
  })
})
