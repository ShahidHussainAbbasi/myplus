/**
 * `pos.entry.preset` — the sale line per kind of shop, and the override rule that makes it safe.
 *
 * <h3>The problem it answers</h3>
 * Nine independent `pos.entry.*` booleans describe 512 possible tills, none of them designed, and every
 * one defaults ON — so a corner shop opens the busiest screen in the product and must switch nine things
 * off to reach the four fields it uses. "I run a pharmacy" is a question a shopkeeper can answer; "should
 * Receivable be visible?" is not.
 *
 * <h3>The contract, which is the whole of this spec</h3>
 * <pre>
 *   1. every field starts SHOWN            (platform default, and today's behaviour)
 *   2. the preset turns off what that trade does not use
 *   3. anything the tenant explicitly SAVED wins over both
 * </pre>
 * Step 3 is what makes a preset safe to offer at all. Without it, choosing "Pharmacy" would silently
 * destroy a deliberate choice, and the only safe advice would be "never touch the preset once configured"
 * — which is not a setting, it is a trap.
 *
 * <h3>Why most of this is asserted against the resolver directly</h3>
 * {@code posFieldsFor} is pure: preset + settings in, field map out. Testing it through the screen would
 * be testing CSS as well, and would not distinguish "the preset is wrong" from "the layout is wrong" —
 * two failures with very different fixes. The last two cases do drive the screen, because reaching the
 * till is the part a unit test cannot prove.
 */
describe('Sale-line presets — one choice instead of nine switches', () => {
  const GW = 'http://localhost:8765'
  const KEY = 'pos.entry.preset'

  const auth = () => cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email: 'owner.business@myplus.com', password: 'Demo@2025!' }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login: ${JSON.stringify(r.body)}`).to.eq(200)
    return { Authorization: `Bearer ${r.body.data.accessToken}`, 'Content-Type': 'application/json' }
  })

  /** Load the till so posFieldsFor and POS_PRESETS are on the page. */
  const openTill = () => {
    cy.viewport(1600, 900)
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
  }

  // ── it is reachable ─────────────────────────────────────────────────────────────────────────────

  it('THE CASE — the preset is offered by the catalogue, so a shop can actually choose one', () => {
    auth().then((h) => {
      cy.request({ url: `${GW}/api/business/settings`, headers: h }).then((r) => {
        const hit = r.body.data.filter((e) => e.key === KEY)
        expect(hit.length, KEY + ' is in the catalogue').to.eq(1)
        expect(hit[0].type, 'rendered as a dropdown, not a switch').to.eq('SELECT')
        expect(hit[0].group).to.match(/sale entry/i)

        const values = (hit[0].options || []).map((o) => o.value)
        expect(values, 'the trades this product actually serves')
          .to.include.members(['CUSTOM', 'RETAIL', 'PHARMACY', 'DISTRIBUTION', 'RESTAURANT'])
        // CUSTOM by default: a new setting must not move a screen somebody already configured.
        expect(hit[0].defaultValue, 'defaults to Custom').to.eq('CUSTOM')
      })
    })
  })

  // ── each trade gets the fields it uses ──────────────────────────────────────────────────────────

  it('RETAIL is the corner-shop till: item, quantity, price, total', () => {
    openTill()
    cy.window().then((w) => {
      const f = w.posFieldsFor('RETAIL', {}, {})
      // Everything a counter sale does not need is off.
      ;['description', 'bonus', 'stock', 'expiry', 'lineDiscount', 'discountType', 'receivable']
        .forEach((k) => expect(f[k], k + ' is off for retail').to.eq(false))
    })
  })

  it('PHARMACY keeps BATCH and EXPIRY — traceability, not decoration', () => {
    openTill()
    cy.window().then((w) => {
      const f = w.posFieldsFor('PHARMACY', {}, {})
      // The two that matter on a medicine, and the reason this is its own preset.
      expect(f.expiry, 'expiry stays').to.eq(true)
      expect(f.stock, 'stock stays — dispensing against what is on the shelf').to.eq(true)
      expect(f.bonus, 'bonus goods are a distribution idea, not a pharmacy counter one').to.eq(false)
      expect(f.description).to.eq(false)
    })
  })

  it('DISTRIBUTION keeps BONUS and the line discount a rep negotiates', () => {
    openTill()
    cy.window().then((w) => {
      const f = w.posFieldsFor('DISTRIBUTION', {}, {})
      expect(f.bonus, 'free goods: 20 billed, 2 free').to.eq(true)
      expect(f.lineDiscount, 'a rep negotiates per product').to.eq(true)
      expect(f.discountType, 'and per product means amount or percent').to.eq(true)
      expect(f.expiry, 'expiry is the pharmacy case, not this one').to.eq(false)
    })
  })

  it('RESTAURANT is the shortest line there is: item and quantity', () => {
    openTill()
    cy.window().then((w) => {
      const f = w.posFieldsFor('RESTAURANT', {}, {})
      ;['description', 'bonus', 'stock', 'expiry', 'lineDiscount', 'discountType', 'receivable']
        .forEach((k) => expect(f[k], k + ' is off').to.eq(false))
    })
  })

  // ── the override rule ───────────────────────────────────────────────────────────────────────────

  it('THE CONTRACT — a saved switch beats the preset, so choosing one never destroys a decision', () => {
    openTill()
    cy.window().then((w) => {
      // A pharmacy that ALSO gives bonus goods. Without this rule they would have to pick the nearest
      // wrong preset, or never touch presets again.
      const f = w.posFieldsFor(
        'PHARMACY',
        { 'pos.entry.showBonus': true },
        { 'pos.entry.showBonus': true })       // saved => chosen
      expect(f.bonus, 'the shop said yes, and the shop wins').to.eq(true)
      expect(f.description, 'everything they did NOT set still follows the preset').to.eq(false)
    })
  })

  it('a value that merely EQUALS the catalogue default does not count as a choice', () => {
    openTill()
    cy.window().then((w) => {
      // byKey carries every catalogue entry with its effective value, so presence proves nothing. If the
      // resolver keyed on presence instead of on `chosen`, the preset would never once apply — it would
      // look implemented and do nothing.
      const f = w.posFieldsFor('RETAIL', { 'pos.entry.showBonus': true }, {})
      expect(f.bonus, 'not saved by the tenant => the preset governs').to.eq(false)
    })
  })

  it('CUSTOM changes nothing — which is what every existing tenant needs', () => {
    openTill()
    cy.window().then((w) => {
      const f = w.posFieldsFor('CUSTOM', {}, {})
      // Fails OPEN, exactly as before this feature existed.
      Object.keys(f).forEach((k) => expect(f[k], k + ' still shown').to.eq(true))
    })
  })

  it('an unknown preset behaves as CUSTOM rather than blanking the screen', () => {
    openTill()
    cy.window().then((w) => {
      // A value from a newer build, or a typo written straight to the database. A till that lost its
      // fields because of an unrecognised string would be a far worse failure than one that ignored it.
      const f = w.posFieldsFor('WHOLESALE_BAKERY_2031', {}, {})
      Object.keys(f).forEach((k) => expect(f[k], k + ' still shown').to.eq(true))
    })
  })

  // ── it reaches the screen ───────────────────────────────────────────────────────────────────────

  it('a preset applied to the real till hides the right cells', () => {
    openTill()
    cy.window().then((w) => {
      w.posFields = w.posFieldsFor('RETAIL', {}, {})
      w.applyPosFieldVisibility()
    })
    // The corner-shop line: item, quantity, price, total.
    //
    // #sellItemDD is a bootstrap-select: the plugin sets the real <select> to display:none and renders a
    // button beside it, so asserting the <select> itself is visible fails on every till whatever the
    // preset says. The visible control is the generated wrapper — the same rule FocusFlow follows when
    // it decides what to put a cursor in.
    cy.get('#sellItemDD').next('.bootstrap-select').should('be.visible')
    cy.get('#sellItems').should('be.visible')
    cy.get('#sellSellRate').should('be.visible')
    cy.get('#sellTotalAmount').should('be.visible')
    // And nothing else. Asserted on the CELL, because that is what the preset hides — checking the input
    // alone would pass even if its caption were left stranded on the row.
    ;['#sellStock', '#bexpDate', '#sellDiscount', '#sellBonus'].forEach((sel) => {
      cy.get(sel).should('not.be.visible')
      cy.get(sel).closest('.pos-cell').should('not.be.visible')
    })
  })

  it('and the fields a preset hides leave the keyboard chain with them', () => {
    openTill()
    cy.window().then((w) => {
      w.posFields = w.posFieldsFor('RETAIL', {}, {})
      w.applyPosFieldVisibility()
      const chain = w.EnterChain.fieldsIn('#Sell')
      // Enter must not stop on a control the operator cannot see — that reads as a frozen till.
      expect(chain, 'discount is gone').to.not.include('sellDiscount')
      expect(chain, 'and so is its chooser').to.not.include('sellDiscountTypeDD')
      expect(chain, 'while the fields the shop DOES use remain').to.include('sellItems')
    })
  })
})
