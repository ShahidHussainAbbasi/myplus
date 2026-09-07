/**
 * SER-6 — the serial box appears for products that HAVE a serial, and only those.
 *
 * ── What was wrong ──────────────────────────────────────────────────────────────────────────────
 * The Serial / IMEI box was on every line of every till whose tenant had serial tracking. A shop selling
 * handsets and paracetamol from one screen was asked for an IMEI on the paracetamol.
 *
 * It is the visible half of the same mistake INST-5b fixed on the server, where a financed sale of Panadol
 * was REFUSED for want of a serial the product does not have. Fixing only the server would leave a cashier
 * looking at a box they must not fill; fixing only the screen would leave the refusal reachable by anyone
 * who typed into it.
 *
 * ── ⚠ Why hiding alone is not the fix ───────────────────────────────────────────────────────────
 * A hidden input is still submitted — `display:none` is kept by FormData, only `disabled` is dropped. A
 * serial left over from the previous line would ride along on a product that cannot take one, and the
 * server would refuse the line for a value the cashier could no longer see. Case 3 is that case.
 */

const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)

/** Seed a product and set its tracking policy — the fact the whole slice keys on. */
const seedTracked = (tracked) => {
  const run = uniq()
  return cy.seedProduct({ name: `SER6_${tracked ? 'IMEI' : 'PLAIN'}_${run}`, sellingPrice: 500, stock: 5 })
    .then(({ productId }) => {
      if (!tracked) return cy.wrap({ productId, run })
      return cy.request({
        method: 'POST', url: '/setProductTracking', form: true,
        body: { id: productId, requiresSerial: 'true' }, failOnStatusCode: false,
      }).then((r) => {
        // SEED, never assume: an unflagged product makes every case here vacuous and they would pass.
        expect(JSON.stringify(r.body), `product ${productId} is serial-tracked`).to.not.match(/error/i)
        return cy.wrap({ productId, run })
      })
    })
}

const openSale = () => {
  cy.visitDashboardSettled()
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellItemDD', { timeout: 20000 }).should('exist')
}

/**
 * Pick a product the way the screen does.
 *
 * ⚠ NO explicit .trigger('change'). #sellItemDD is a bootstrap-select, so the native <select> carries
 * display:none and a rendered button stands in for it — triggering on a hidden element fails outright, which
 * is how this spec first went red on all five cases. `.select(..., {force:true})` dispatches a real change
 * event, and main.js binds `$(".onChangeSelect").change(...)` directly to the native select, so the app
 * responds exactly as it does for a cashier. Every sibling spec selects this way for the same reason.
 */
const pick = (productId) => {
  cy.get('#sellItemDD').select(String(productId), { force: true })
}

describe('SER-6 — the serial box follows the product', () => {
  beforeEach(() => {
    // The mobile shop: the vertical that HAS serial tracking, so the cell exists to be toggled.
    cy.loginAsMobileOwner()
  })

  it('⭐ 1 — a serial-tracked product SHOWS the box', () => {
    seedTracked(true).then(({ productId }) => {
      openSale()
      pick(productId)
      cy.get('#sellDiv [data-pos-field="serial"]').should('not.have.class', 'serial-na')
      cy.get('#sellSerials').should('be.visible')
    })
  })

  it('⭐⭐ 2 — a product with no serial HIDES it — the Panadol case', () => {
    /*
     * THE REPORTED CASE. This shop sells handsets, so it has serial tracking on; the box was therefore
     * rendered for its medicines too, and the installment rule then demanded an IMEI they cannot have.
     */
    seedTracked(false).then(({ productId }) => {
      openSale()
      pick(productId)
      cy.get('#sellDiv [data-pos-field="serial"]').should('have.class', 'serial-na')
      cy.get('#sellSerials').should('not.be.visible')
    })
  })

  it('⭐⭐ 3 — switching to an untracked product CLEARS a serial already typed', () => {
    /*
     * ⚠ THE CASE THAT MAKES HIDING SAFE. A hidden input still submits, so a leftover IMEI would travel
     * with a product that cannot take one — and the server would refuse the line for a value nobody can
     * see. Asserted on the VALUE, because a visibility check passes while the value is still there.
     */
    seedTracked(true).then(({ productId: tracked }) => {
      seedTracked(false).then(({ productId: plain }) => {
        openSale()
        pick(tracked)
        cy.get('#sellSerials').should('be.visible').clear().type('355123456789012')
        cy.get('#sellSerials').should('have.value', '355123456789012')

        pick(plain)
        cy.get('#sellDiv [data-pos-field="serial"]').should('have.class', 'serial-na')
        cy.get('#sellSerials').should('have.value', '')
      })
    })
  })

  it('4 — with nothing selected the box stays available for a scanner-first cashier', () => {
    /*
     * The screen is built around scanning before picking: the IMEI is typed, and the product follows from
     * it. Hiding the box until a product is chosen would break that flow, so "nothing selected" shows.
     */
    openSale()
    cy.get('#sellItemDD').select('', { force: true })
    cy.get('#sellDiv [data-pos-field="serial"]').should('not.have.class', 'serial-na')
  })

  it('⭐ 5 — marking a product tracked reaches the picker WITHOUT a page reload', () => {
    /*
     * The cached picker (PERF-8) holds the option markup, and `requiresSerial` now rides on it. So
     * `setProductTracking` had to join the cache-invalidation list, or a till would keep hiding the box for
     * a product that had just started needing one — the exact rot that hook exists to prevent.
     *
     * ⚠ THE WRITE GOES THROUGH THE PAGE'S OWN jQuery, and that is the whole case.
     *
     * The hook is `$(document).ajaxComplete`, which only sees requests jQuery made. A `cy.request` here
     * would bypass it entirely — and because re-visiting the dashboard drops the cache anyway (it is a
     * variable in a page that no longer exists), the case would go green on a build with no invalidation
     * at all. It would test the reload, not the hook.
     *
     * So: no reload, and the write is issued from inside the page.
     */
    seedTracked(false).then(({ productId }) => {
      openSale()
      pick(productId)
      cy.get('#sellDiv [data-pos-field="serial"]').should('have.class', 'serial-na')

      // The write, issued BY THE PAGE so the ajaxComplete hook actually sees it.
      cy.window().then((win) => new Cypress.Promise((resolve) => {
        win.$.post(`${win.serverContext || '/'}setProductTracking`.replace(/\/\/+/g, '/'),
          { id: productId, requiresSerial: 'true' }).always(resolve)
      }))

      /*
       * Then ask the picker for the catalogue again, on the SAME page. If the hook dropped the cache this
       * re-fetches and the flag is there; if it did not, `load` answers from the cache it already holds and
       * the product is still unflagged. No reload anywhere in this case — a reload would drop the cache by
       * destroying the page, and the assertion would hold on a build with no invalidation at all.
       */
      cy.window().then((win) => new Cypress.Promise((resolve) => {
        win.ProductPicker.load((list) => resolve(list))
      })).then((list) => {
        const row = list.find((x) => Number(x.id) === Number(productId))
        expect(row, `product ${productId} is in the picker`).to.be.an('object')
        expect(row.requiresSerial,
          'the cache was dropped, so the picker re-read the product and sees the new flag').to.eq(true)
      })
    })
  })
})
