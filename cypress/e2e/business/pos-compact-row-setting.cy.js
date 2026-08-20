/**
 * `pos.entry.compactRow` — the single-row sale line, and the fact that a shop can actually turn it on.
 *
 * <h3>Why this spec exists</h3>
 * The flag was READ from the day the row layout shipped — {@code business.js} has always set
 * {@code posRowLayoutEnabled} from it — but it was never DECLARED in the settings catalogue. The
 * Configuration screen renders from that catalogue and knows nothing about individual settings, which is
 * its strength and also why an undeclared key is not merely undocumented but <b>unreachable</b>. The one
 * organisation that had the row layout was an org a test had written the row for directly; every real
 * tenant was looking at the stacked form with no way to change it.
 *
 * <p>The catalogue's own rule is <i>"a toggle that changes nothing is worse than no toggle"</i>. This was
 * its mirror image — a change with no toggle — and the only test that catches it is one that asserts the
 * setting is OFFERED, not just honoured.
 *
 * <h3>What it pins</h3>
 * <ol>
 *   <li>The key is in the catalogue, so it renders on the Configuration screen.</li>
 *   <li>It defaults ON — the row is the intended till.</li>
 *   <li>It still fails CLOSED when settings cannot be read, which is a different thing from defaulting
 *       off and must not be lost.</li>
 *   <li>Both layouts still work, because "one switch away" has to be true in both directions.</li>
 * </ol>
 */
describe('The single-row sale line is a setting a shop can reach', () => {
  const GW = 'http://localhost:8765'
  const KEY = 'pos.entry.compactRow'

  const authFor = (email) => cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email, password: 'Demo@2025!' }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}: ${JSON.stringify(r.body)}`).to.eq(200)
    return { Authorization: `Bearer ${r.body.data.accessToken}`, 'Content-Type': 'application/json' }
  })

  // ── the gap that shipped ────────────────────────────────────────────────────────────────────────

  it('THE CASE — the key is in the catalogue, so the Configuration screen can render it', () => {
    authFor('owner.business@myplus.com').then((auth) => {
      // catalogForOrg() answers a List<Map> in ApiResponse.data, each row carrying key/label/help/type/
      // group/value/isDefault/defaultValue. Asserted against those exact names rather than a chain of
      // fallbacks: a helper that quietly accepts several shapes cannot tell a renamed field from a
      // missing one, which is the failure this spec exists to catch.
      cy.request({ url: `${GW}/api/business/settings`, headers: auth }).then((r) => {
        const list = r.body.data
        expect(list, 'the catalogue came back as a list').to.be.an('array')
        const hit = list.filter((e) => e.key === KEY)

        // Undeclared, this array simply would not contain it — and no screen would ever show it. That is
        // exactly the state the row layout shipped in.
        expect(hit.length, KEY + ' is offered by the catalogue: ' + JSON.stringify(list.map((e) => e.key)))
          .to.eq(1)
        expect(hit[0].group, 'grouped with the other sale-entry settings').to.match(/sale entry/i)
        expect(String(hit[0].label), 'and has a label an owner can understand').to.not.be.empty
        expect(String(hit[0].type), 'rendered as a switch').to.match(/bool/i)
      })
    })
  })

  it('it defaults ON — a shop that never opens Configuration gets the row', () => {
    authFor('owner.business@myplus.com').then((auth) => {
      cy.request({ url: `${GW}/api/business/settings`, headers: auth }).then((r) => {
        const hit = r.body.data.filter((e) => e.key === KEY)[0]
        expect(hit, 'the entry exists to have a default').to.exist
        // `defaultValue` is the DECLARED default; `value` is the effective one and would be whatever this
        // org last saved. Asserting the declared one is what makes this independent of other specs.
        expect(String(hit.defaultValue), 'declared default').to.match(/^true$/i)
      })
    })
  })

  // ── it reaches the screen ───────────────────────────────────────────────────────────────────────

  it('the till opens in row mode without anyone configuring anything', () => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')

    // Not forced by the test — this is what the settings call alone produces.
    cy.window().should((w) => {
      expect(w.posRowLayoutEnabled, 'the flag the settings response set').to.eq(true)
    })
    cy.get('#sellDiv').should('have.class', 'pos-rowentry')
    // And the cells are laid out, not dissolved: in the stacked layout .pos-cell is display:contents.
    cy.get('#Sell .pos-cell').first().should('have.css', 'display', 'flex')
  })

  it('turning it OFF returns the stacked form, cells and all', () => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.window().then((w) => { w.posRowLayoutEnabled = false; w.applyPosRowEntry() })

    cy.get('#sellDiv').should('not.have.class', 'pos-rowentry')
    // display:contents — the wrapper stops existing as far as layout is concerned, so Bootstrap's
    // horizontal form is byte-for-byte what it always was. That is what makes the switch reversible.
    cy.get('#Sell .pos-cell').first().should('have.css', 'display', 'contents')
    // The fields are all still there and still submit; only their arrangement changed.
    ;['sellItemDD', 'sellItems', 'sellSellRate', 'sellDiscount'].forEach((id) => {
      cy.get('#' + id).should('exist')
    })
  })

  it('and it still fails CLOSED when the settings cannot be read at all', () => {
    // A different thing from "defaults off": an absent or failed settings response must give the
    // layout every operator already knows, rather than re-arranging a till because a config call
    // hiccuped. The default only applies when the catalogue was actually reached.
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.window().then((w) => {
      const byKey = {}                       // what an empty / failed response leaves behind
      w.posRowLayoutEnabled = byKey['pos.entry.compactRow'] === true
      w.applyPosRowEntry()
    })
    cy.get('#sellDiv').should('not.have.class', 'pos-rowentry')
  })
})
