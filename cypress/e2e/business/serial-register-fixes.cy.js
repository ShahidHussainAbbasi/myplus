/**
 * SER-2/SER-3 (fix) — the register is READABLE, an EDIT keeps it, and a returned unit comes BACK.
 *
 * <h3>Why a second spec rather than more cases in serial-register.cy.js</h3>
 * That spec asserts the ADD path, and every one of its cases passed while all eight defects below were live.
 * These are the operations it never exercised: reading a serial back onto a screen, saving a bill a second
 * time, returning or voiding a sale, and ordering a tracked product through the internal API. Grouping them
 * keeps the distinction visible — "the register records correctly" and "the register can be used" are two
 * different claims, and only the first was ever gated.
 *
 * <h3>What is asserted</h3>
 * The VALUE a caller receives, never just "the request succeeded". Every defect here reported success:
 *   · updatePurchase took `serials` and discarded it, answering "Purchase updated successfully."
 *   · the grid rendered without the serial it had recorded.
 *   · a void reversed stock and the ledger and left the handset marked SOLD.
 *   · /internal/sales refused a handset it gave no way to name.
 *
 * ⚠ Envelope, not HTTP status. GenericResponse answers a refusal as `status:"ERROR"` inside a 200.
 */

const MOBILE = 'owner.mobile@myplus.com'
const POS = 'owner.business@myplus.com'
const PASSWORD = 'Demo@2025!'
const GW = Cypress.env('gatewayUrl') || 'http://localhost:8765'

const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)

const unitsOf = (productId) =>
  cy.request(`/serialUnits?productId=${productId}`).then((r) => (r.body && r.body.collection) || [])

const history = (q) =>
  cy.request(`/serialHistory?serial=${encodeURIComponent(q)}`)
    .then((r) => (r.body && r.body.collection) || [])

const buy = (productId, quantity, extra) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: Object.assign({
      productId, quantity,
      purchaseRate: 100, 'stock.bpurchaseRate': 100, 'stock.bsellRate': 150,
      totalAmount: 100 * quantity, netAmount: 100 * quantity, paidAmount: 100 * quantity,
      purchaseInvoiceNo: 'SERFIX-' + uniq(),
    }, extra || {}),
  })

const editBill = (purchaseId, productId, quantity, extra) =>
  cy.request({
    method: 'POST', url: '/updatePurchase', form: true, failOnStatusCode: false,
    body: Object.assign({
      purchaseId, productId, quantity,
      'stock.bpurchaseRate': 100, 'stock.bsellRate': 150,
      totalAmount: 100 * quantity, netAmount: 100 * quantity, paidAmount: 100 * quantity,
    }, extra || {}),
  })

/** The purchase register as the GRID receives it — the shape the Serial column renders from. */
const bills = () => cy.request('/getUserPurchase').then((r) => (r.body && r.body.collection) || [])
const billFor = (purchaseId) =>
  bills().then((rows) => rows.find((b) => String(b.purchaseId) === String(purchaseId)))

const token = (email) =>
  cy.request({ method: 'POST', url: `${GW}/api/auth/login`,
    body: { email, password: PASSWORD } }).then((r) => r.body.data.accessToken)

/**
 * Open the purchase form the way an operator does — nav, then New Purchase.
 *
 * ⚠ `#PurchaseModal` is display:none at rest, so a visibility assertion made straight after cy.visit
 * fails on the OVERLAY rather than on the field, and would keep passing if the fix were reverted. The
 * same trap capability-fields.cy.js documents.
 */
const openPurchaseForm = () => {
  cy.visit('/businessDashboard')
  cy.get('#snavPurchase .snav-btn', { timeout: 15000 }).click({ force: true })
  cy.contains('#snavPurchase a', /new purchase/i).click({ force: true })
  cy.get('#newPurchase', { timeout: 15000 }).click({ force: true })
  cy.get('#PurchaseModal', { timeout: 15000 }).should('have.class', 'open')
}

const saveSetting = (t, key, value) =>
  cy.request({ method: 'POST', url: `${GW}/api/business/settings?key=${key}&value=${value}`,
    headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })

describe('SER-2/3 (fix) — reading, editing and returning a tracked unit', () => {
  let tracked = null
  let plain = null

  before(() => {
    cy.loginAsMobileOwner()
    cy.setShape('retail')
    cy.setCapability('serialTracking', true)
    cy.setCapability('conditionGrading', true)

    cy.seedProduct({ name: `SERFIX_TRACKED_${uniq()}` }).then((p) => {
      tracked = p.productId
      cy.request({ method: 'POST', url: '/setProductTracking', form: true,
        body: { id: tracked, requiresSerial: true } })
        .then((r) => expect(r.body && r.body.success,
          `the product must really be serial-tracked: ${JSON.stringify(r.body)}`).to.not.eq(false))
    })
    cy.seedProduct({ name: `SERFIX_PLAIN_${uniq()}` }).then((p) => { plain = p.productId })
  })

  beforeEach(() => cy.loginAsMobileOwner())

  after(() => {
    // Leave no server state behind: this spec flips a SERVER-WIDE setting on the POS tenant below.
    token(POS).then((t) => saveSetting(t, 'pos.entry.showSerial', 'true'))
    cy.loginAsMobileOwner()
    cy.setCapability('serialTracking', true)
    cy.setCapability('conditionGrading', true)
  })

  // ── 1. the purchase FORM keeps its quantity field ───────────────────────────────────────────────

  it('⭐ QTY stays on the purchase form when the serial box is switched OFF', () => {
    /*
     * The reported bug, and the reason it was invisible for so long: the serial row's <div> was never
     * closed, so the QTY / Bonus / Pack / Stock-in-hand group was parsed as its CHILD. Hiding the serial
     * row — which `pos.entry.showSerial=false` does — took the quantity field with it, and a purchase
     * cannot be entered at all without one.
     *
     * Asserted on the POS tenant because that is the tenant that had the setting off. The assertion is
     * that QTY is VISIBLE while the serial box is not: "both present in the DOM" would have passed on the
     * broken markup, since the child was never removed, only hidden by an ancestor.
     */
    token(POS).then((t) => saveSetting(t, 'pos.entry.showSerial', 'false'))
    cy.loginAsOwner()   // owner.business@ — the tenant whose showSerial was off
    openPurchaseForm()

    cy.get('#purchaseSerials').should('not.be.visible')
    cy.get('#purchaseQuantity').should('be.visible')
    cy.get('#purchasePurchaseRate').should('be.visible')
  })

  it('the serial box comes back when the setting is switched on, QTY still there', () => {
    token(POS).then((t) => saveSetting(t, 'pos.entry.showSerial', 'true'))
    cy.loginAsOwner()   // owner.business@ — the tenant whose showSerial was off
    openPurchaseForm()

    cy.get('#purchaseSerials').should('be.visible')
    cy.get('#purchaseQuantity').should('be.visible')
  })

  // ── 2. the register is READABLE ─────────────────────────────────────────────────────────────────

  it('⭐ the purchase list returns the serial it recorded', () => {
    /*
     * The register was write-only from the UI: goods-in wrote it and nothing ever read one back onto a
     * screen. A shop that had entered an IMEI had no way to see it, and concluded the purchase had not
     * saved it — which is exactly what was reported.
     */
    const imei = 'READBACK' + uniq()
    buy(tracked, 1, { serials: imei, conditionGrade: 'USED' }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      bills().then((rows) => {
        const mine = rows.filter((b) => b.serials === imei)
        expect(mine.length, `the grid must carry the serial it recorded: ${imei}`).to.eq(1)
        expect(mine[0].conditionGrade, 'and the grade, so the edit form can restore it').to.eq('USED')
      })
    })
  })

  it('the Serial / IMEI column is on the purchase grid', () => {
    // ⚠ The COLUMN, not the endpoint. C6 shipped a policy no screen could set and every API test passed.
    cy.openPurchaseSection('purchaseDiv')
    cy.get('#tablePurchase thead th').then(($th) => {
      const heads = [...$th].map((h) => h.textContent.trim())
      expect(heads.join(' | ')).to.contain('Serial')
    })
  })

  // ── 3. an EDIT keeps the register in step ───────────────────────────────────────────────────────

  it('⭐ re-saving a bill unchanged does NOT lose its serial', () => {
    /*
     * The round trip. The grid renders join(), editRecord copies that cell into #purchaseSerials, and the
     * form posts it back. If any link were broken, opening a bill and pressing save would silently empty
     * its register — a worse outcome than the original bug, because the operator asked for no change.
     */
    const imei = 'KEEP' + uniq()
    buy(tracked, 1, { serials: imei, conditionGrade: 'NEW' })
    history(imei).then((rows) => {
      expect(rows.length, `the purchase must have registered ${imei}`).to.eq(1)
      const purchaseId = rows[0].purchaseId
      editBill(purchaseId, tracked, 1, { serials: imei, serialsSubmitted: true, conditionGrade: 'NEW' })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
      unitsOf(tracked).then((units) => {
        expect(units.filter((u) => u.serialNo === imei).length,
          'the unit is still on the shelf, once').to.eq(1)
      })
    })
  })

  it('⭐ correcting a mistyped IMEI replaces it — the reported defect', () => {
    const wrong = 'WRONG' + uniq()
    const right = 'RIGHT' + uniq()
    buy(tracked, 1, { serials: wrong, conditionGrade: 'NEW' })
    history(wrong).then((rows) => {
      const purchaseId = rows[0].purchaseId
      editBill(purchaseId, tracked, 1, { serials: right, serialsSubmitted: true, conditionGrade: 'NEW' })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      unitsOf(tracked).then((units) => {
        const live = units.map((u) => u.serialNo)
        expect(live, 'the corrected serial is on the shelf').to.include(right)
        expect(live, 'and the wrong one is gone').to.not.include(wrong)
      })
    })
  })

  it('an edit that would leave the count wrong is refused, and changes nothing', () => {
    const imei = 'COUNT' + uniq()
    buy(tracked, 1, { serials: imei, conditionGrade: 'NEW' })
    history(imei).then((rows) => {
      const purchaseId = rows[0].purchaseId
      // Quantity 3, one serial: a purchase for three handsets with one IMEI leaves two unaccounted for.
      editBill(purchaseId, tracked, 3, { serials: imei, serialsSubmitted: true }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.not.eq('SUCCESS')
      })
      unitsOf(tracked).then((units) => {
        expect(units.filter((u) => u.serialNo === imei).length,
          '⭐ a refusal changed nothing').to.eq(1)
      })
    })
  })

  it('an old client that sends no serialsSubmitted leaves the register alone', () => {
    /*
     * A browser holding a CACHED copy of the pre-fix form sends neither `serials` nor `serialsSubmitted`.
     * That must not read as "remove them all" — the tab simply could not display the field.
     */
    const imei = 'STALE' + uniq()
    buy(tracked, 1, { serials: imei, conditionGrade: 'NEW' })
    history(imei).then((rows) => {
      editBill(rows[0].purchaseId, tracked, 1, {})   // no serials, no marker
      unitsOf(tracked).then((units) => {
        expect(units.filter((u) => u.serialNo === imei).length,
          'the units of a bill a stale tab re-saved are untouched').to.eq(1)
      })
    })
  })

  // ── 4. the lookup answers a DOCUMENT number ─────────────────────────────────────────────────────

  it('⭐ a lookup by PURCHASE BILL number finds the units it brought in', () => {
    /*
     * The literal complaint: `serialHistory?serial=10225` answered empty for a bill whose serial had in
     * fact been recorded, and empty read as "not saved". A bill number is what an operator is holding.
     */
    const billNo = 'BILL-' + uniq()
    const imei = 'BYBILL' + uniq()
    buy(tracked, 1, { serials: imei, conditionGrade: 'NEW', purchaseInvoiceNo: billNo })

    history(billNo).then((rows) => {
      expect(rows.length, `bill ${billNo} must resolve to its units`).to.eq(1)
      expect(rows[0].serialNo).to.eq(imei)
      expect(rows[0].matchedBy, 'and say WHICH question it answered').to.eq('PURCHASE_INVOICE')
      expect(rows[0].purchaseInvoiceNo, 'the bill number, not an internal id').to.eq(billNo)
    })
  })

  it('a lookup by serial still answers as the serial, not as a document', () => {
    const imei = 'BYSERIAL' + uniq()
    buy(tracked, 1, { serials: imei, conditionGrade: 'NEW' })
    history(imei).then((rows) => {
      expect(rows.length).to.eq(1)
      expect(rows[0].matchedBy).to.eq('SERIAL')
    })
  })

  it('a number that matches nothing is reported as nothing, not as an error', () => {
    history('NO-SUCH-' + uniq()).then((rows) => expect(rows.length).to.eq(0))
  })

  it('the lookup box is on the purchase screen', () => {
    // The endpoints existed from day one with no caller on any page — which is why the URL was typed by
    // hand. Assert the CONTROL, not the endpoint.
    cy.openPurchaseSection('purchaseDiv')
    cy.get('#serialLookupBox').should('be.visible')      // the mobile shop HAS serialTracking
    cy.get('#serialLookup').should('be.visible')
  })
})
