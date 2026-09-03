/**
 * Barcode scanning is OFF by default, for every tenant.
 *
 * <h3>The defect</h3>
 * The user reported: *"Barcode scanning is off but I can see sellScan"*, then ruled *"sellScan should be off
 * by default for all tenants"*.
 *
 * FOUR places defaulted it ON, and each one was enough on its own:
 *
 * | Where | Was | Now |
 * |---|---|---|
 * | `BusinessSettingsCatalog` | catalog default `true` | `false` |
 * | `business.js` — absent key | `: true` | `: false` |
 * | `business.js` — config call failed | `= true` | `= false` (fail CLOSED) |
 * | `applyPosBarcodeVisibility` | `!== false` (undefined ⇒ ON) | `=== true` |
 * | `businessDashboard.html` | `#sellScanRow` rendered visible | `style="display:none"` |
 *
 * `!== false` is the one worth naming: it reads an UNSET flag as enabled, so the box appeared on any screen
 * reached before the settings call returned — which is why it showed up for a tenant who had switched it off.
 *
 * <h3>Why OFF is the right default</h3>
 * The scan box is the first field on the sale screen and the first place focus lands. A shop without a scanner
 * pays a keystroke on every sale for a field it can never use. A shop that scans turns it on once.
 *
 * ⚠ A tenant who EXPLICITLY enabled it keeps it — an org_setting override outranks the catalog default. This
 * changes what an unconfigured tenant gets, not what a configured one chose.
 */

const OWNER = 'owner.business@myplus.com'
const KEY = 'pos.barcode.enabled'

/** Write the setting, asserting the envelope — `/saveBusinessConfig` answers 200 with {success:false}. */
function setBarcode(value) {
  return cy.request({
    method: 'POST', url: '/saveBusinessConfig', form: true,
    body: { key: KEY, value: String(value) },
  }).then((r) => {
    expect(r.body && r.body.success, `set ${KEY}=${value}: ${JSON.stringify(r.body)}`).to.eq(true)
  })
}

describe('barcode scanning defaults OFF', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  after(() => {
    // ⚠ Leave no server state behind: this is a tenant-wide switch, and a spec that left it ON would put a
    // scan box on the sale screen for every spec that runs afterwards.
    cy.loginAsOwner(OWNER)
    setBarcode(false)
  })

  it('⭐ 1. the CATALOG default is off — an unconfigured tenant gets no scan box', () => {
    /*
     * Asserted at the source. The four client-side reads all defer to this value, so if the catalog ever goes
     * back to true the whole slice silently reverts and every case below still passes on the override.
     */
    cy.request({ url: '/getBusinessConfig' }).then((r) => {
      const items = (r.body && r.body.data) || []
      expect(items.length, 'the settings catalog loaded').to.be.greaterThan(0)

      const entry = items.find((i) => i.key === KEY)
      expect(entry, `${KEY} is in the catalog`).to.exist
      // `defaultValue` is the SHIPPED value — what a tenant with no override receives. Asserting `value`
      // instead would pass on any override this suite happened to leave behind.
      expect(String(entry.defaultValue), 'the shipped default is off').to.eq('false')
    })
  })

  it('⭐ 2. the sale screen shows NO scan box when barcode is off', () => {
    /*
     * THE DEFECT THE USER SAW. Asserted from the SCREEN — the flag being false proves nothing about what is
     * on the page, and the whole bug was that the two disagreed.
     */
    setBarcode(false)
    cy.visitSaleScreen()

    cy.get('#sellScanRow').should('not.be.visible')
    cy.get('#sellScan').should('not.be.visible')
  })

  it('⭐ 3. focus does NOT land in the scan box when barcode is off', () => {
    /*
     * The cost the user was actually paying. A hidden field that still takes focus leaves a cashier typing
     * into nothing — worse than a visible one, because there is no clue where the keystrokes went.
     */
    setBarcode(false)
    cy.visitSaleScreen()

    cy.focused().then(($f) => {
      const id = $f.length ? $f.attr('id') : null
      expect(id, 'focus is anywhere but the scan box').to.not.eq('sellScan')
    })
  })

  it('4. the product form has no Barcode field when barcode is off', () => {
    // Same flag governs both. A tenant that does not scan should not be asked to type an EAN either.
    setBarcode(false)
    /*
     * ⚠ NOT cy.openSection('ProductDiv') — that drives #registrationType.select(), and ProductDiv has no
     * option on it. The product screen is opened by showProducts(), one of the two sections in this app that
     * are function-navigated rather than select-navigated (the other is Installment plans).
     *
     * The same distinction that dashboard-kpi-drill.cy.js case 4 exists to protect, missed here.
     */
    cy.visitDashboardSettled()
    cy.get('[onclick*="showProducts"]').first().click({ force: true })
    cy.get('#ProductDiv', { timeout: 20000 }).should('be.visible')

    cy.get('#prodBarcodeLabel').should('not.be.visible')
    cy.get('#prodBarcodeWrap').should('not.be.visible')
  })

  it('⭐ 5. turning it ON still works — the setting is a switch, not a removal', () => {
    /*
     * THE OVER-CORRECTION GUARD, and the case that matters most after #2.
     *
     * Defaulting a feature off is one line away from deleting it. A shop that scans must still get its scan
     * box, or this "fix" has broken barcode-first selling for every tenant that uses it.
     */
    setBarcode(true)
    cy.visitSaleScreen()

    cy.get('#sellScanRow').should('be.visible')
    cy.get('#sellScan').should('be.visible')
  })

  it('6. an explicit ON survives — a configured tenant keeps what it chose', () => {
    // The migration promise. Changing a catalog default must not silently switch off a tenant that
    // deliberately turned this on.
    setBarcode(true)
    cy.request({ url: '/getBusinessConfig' }).then((r) => {
      const entry = ((r.body && r.body.data) || []).find((i) => i.key === KEY)
      expect(String(entry.value), 'the tenant\'s own choice is what is served').to.eq('true')
    })
  })
})
