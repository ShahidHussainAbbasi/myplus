/**
 * RST-R2a — an order carries HOW it was served, from the till to the day's takings.
 *
 * Design: microservices/docs/slices/rst-r2a-order-types.md
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * ⚠ THE TWO CASES THIS SPEC EXISTS FOR
 *
 * Case 2 — EXISTING INVOICES STAY BLANK. The tempting convenience when adding this column was to default it
 * to TAKE_AWAY, because "most counter sales are take-away". That would retro-label every retail and pharmacy
 * invoice ever written, and then feed those invented numbers into the split the owner reads at close of
 * business. A migration that defaults is the failure; this case is what refuses it.
 *
 * Case 6 — THE SPLIT RECONCILES. Not "the report groups by type" — that it SUMS to the day's take. This
 * platform has twice shipped a figure that looked right and did not agree with an independent source: a sale
 * that posted cost 350 against a true 600 with the trial balance balancing to the penny, and a cost divided
 * by the remaining quantity instead of the purchased quantity, inflating it twenty-fold. A split that does
 * not add up is worse than no split, because somebody will manage the business by it.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * ⚠ WHY THIS TOUCHES NEITHER THE PLAN NOR AN ENTITLEMENT
 *
 * `ORDER_TYPES` is deliberately NOT in `Plan.FREE`. Two earlier versions of this gate tried to lift the
 * ceiling for it — first by granting an entitlement row (which cannot be deleted, and broke another gate),
 * then by swapping the tenant's plan (which collided with a peer's platform run and, being un-invalidated
 * for 60s, did not even work). Both were solving the wrong problem.
 *
 * E1's READ path applies the shape preset and subtracts only REVOCATIONS — it never consults the plan. So a
 * GENERAL-shaped tenant with no licensing row already resolves this capability ON, and every case below
 * works with the ceiling untouched. Only a WRITE is plan-bounded, so the gate simply never writes `true`.
 *
 * (`MADE_TO_ORDER` was the opposite case and the opposite fix: without it a kitchen cannot complete a sale
 * at all, so it belongs in FREE and the fix was to the plan, not to the spec.)
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/restaurant-order-types.cy.js
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const OWNER = 'owner.business@myplus.com'
const run = uniq()
const tag = (name) => `${name} [R2A-${run}]`

describe('RST-R2a — an order carries how it was served', () => {
  const made = {}
  let capBefore = null

  before(() => {
    /*
     * No ceiling manipulation. Assert the precondition instead, and fail with an explanation rather than
     * letting twelve assertions collapse for a reason none of them mention.
     */
    cy.loginAsOwner(OWNER, undefined, 'r2a-gate')
    cy.request({ url: '/getCapabilities', failOnStatusCode: false }).then((r) => {
      const caps = (r.body && (r.body.data || r.body)) || {}
      expect(caps.orderTypes,
        'this tenant must resolve orderTypes ON (GENERAL preset, no revocation) — it is NOT in Plan.FREE, '
        + 'so the gate cannot switch it on itself; check the shape and the entitlement ceiling')
        .to.eq(true)
    })

    cy.request({ url: '/getBusinessConfig', failOnStatusCode: false }).then((r) => {
      const rows = (r.body && r.body.data) || []
      const row = rows.find((x) => x.key === 'org.cap.orderTypes')
      capBefore = row ? row.value : null   // read for the record; case 7 is what may write one
    })
    // madeToOrder IS in Plan.FREE, so this write is allowed — the asymmetry is the whole point above.
    cy.setCapability('madeToOrder', true)

    // A menu item that needs no stock, so these cases test order types and not the allocator.
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: { name: tag('Chicken Roll'), sku: `R2A${uniq()}`, sellingPrice: 180, taxRate: 0,
        unit: 'plate', categoryName: `Rolls [R2A-${run}]`, madeToOrder: true },
    }).then((r) => {
      expect(r.body.success, `fixture product: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(true)
      made.roll = { id: r.body.data.id, price: 180, name: tag('Chicken Roll') }
    })
  })

  after(() => {
    /*
     * The only state this gate leaves is an `org.cap.orderTypes` override, written by case 7. RESET removes
     * it so the preset decides again — the one restore that puts back an ABSENCE rather than pinning a value.
     *
     * ⚠ Reset is what makes case 7 survivable at all. Writing `true` to undo it is refused (not in plan),
     * and until L13 was fixed a reset was refused too — which made switching this capability off a ONE-WAY
     * DOOR for any FREE tenant, discovered by this gate doing exactly that to org 13. If this teardown ever
     * starts failing with "going back to the default would switch on…", that fix has been reverted.
     */
    cy.loginAsOwner(OWNER, undefined, 'r2a-gate')
    cy.request({ url: '/getBusinessConfig', failOnStatusCode: false }).then((r) => {
      const rows = (r.body && r.body.data) || []
      const row = rows.find((x) => x.key === 'org.cap.orderTypes')
      if (!row || row.isDefault !== false) return       // nothing of ours to undo
      cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, failOnStatusCode: false,
        body: { key: 'org.cap.orderTypes' } })
        .then((res) => expect(res.body && res.body.success,
          `reset org.cap.orderTypes: ${JSON.stringify(res.body)}`).to.eq(true))
    })
  })

  // ⚠ A per-spec session key. A cached owner session whose refresh token the 5-per-user cap has evicted
  // still VALIDATES, but capability changes never reach it — the failure looks like the capability did not
  // apply rather than like a dead session. (microservices-1f, 2026-09-27.)
  beforeEach(() => cy.loginAsOwner(OWNER, undefined, 'r2a-gate'))

  /** Ring up one roll with the given order type; returns the raw response so a case can assert a refusal. */
  const sell = (orderType, contact) => cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      customer: { name: `R2A_${uniq()}`, contact: contact },
      sales: [{ productId: made.roll.id, itemName: made.roll.name, quantity: 1, sellRate: made.roll.price }],
      tenders: [{ method: 'CASH', amount: made.roll.price }],
      paidAmount: made.roll.price, grandTotal: made.roll.price,
      orderType: orderType,
      idempotencyKey: `cy-r2a-${uniq()}`,
    },
  })

  const invoiceOf = (res) => {
    expect(res.body.status, `the sale: ${JSON.stringify(res.body).slice(0, 220)}`).to.eq('SUCCESS')
    expect(res.body.object).to.match(/^INV-/)
    return res.body.object
  }

  /**
   * The Sale Detail Report — the screen the owner actually reads, and the one that must split.
   *
   * ⚠ `sd`/`ed` are `dd-MM-yyyy HH:mm:ss` — NOT ISO, and NOT date-only.
   *
   * `AppUtil.getDateTime` parses with exactly that pattern, so a date with no time throws
   * DateTimeParseException, the controller's broad handler turns it into an error response, and the spec
   * receives an EMPTY COLLECTION. The failure therefore reads as "this tenant has no sales" rather than as
   * a malformed request — which is precisely how the first run of this gate lost two cases, and the same
   * shape as the recorded incident where a same-day filter returned nothing because three readers assumed
   * ISO.
   *
   * `rp=4` is CUSTOM; any other code computes its own range and ignores sd/ed entirely.
   */
  const ddmmyyyy = (d, time = '00:00:00') => `${String(d.getDate()).padStart(2, '0')}-`
    + `${String(d.getMonth() + 1).padStart(2, '0')}-${d.getFullYear()} ${time}`

  const saleReportToday = () => {
    const now = new Date()
    return cy.request({
      method: 'POST', url: '/loadSR', form: true, failOnStatusCode: false,
      body: { rp: 4, sd: ddmmyyyy(now), ed: ddmmyyyy(now, '23:59:59') },
    }).then((r) => list(r.body).filter((s) => String(JSON.stringify(s)).indexOf(`R2A-${run}`) >= 0))
  }

  it('⭐⭐ 1 — the type reaches the invoice and survives the round trip', () => {
    /*
     * The wire is the risk here, not the column. The monolith binds a TYPED CustomerHistoryDTO and
     * re-serialises it, so a field without a twin there is dropped silently — the sale would save
     * perfectly and land with no type at all. That is what this reads back to prove.
     */
    sell('DINE_IN', '0335-2456847').then((res) => {
      const no = invoiceOf(res)
      cy.request({ url: `/getReceipt?invoiceNo=${encodeURIComponent(no)}`, failOnStatusCode: false })
        .then((r) => {
          const inv = (r.body && (r.body.data || r.body.object)) || {}
          expect(String(inv.orderType), `${no} kept its type across the proxy`).to.eq('DINE_IN')
        })
    })
  })

  it('⭐⭐ 2 — EXISTING invoices stay blank; nothing was retro-labelled', () => {
    /*
     * THE CASE THAT GUARDS EVERY HISTORIC SALE. V68 adds the column nullable with no default and no
     * backfill; if anyone later adds `DEFAULT 'TAKE_AWAY'` or a backfill UPDATE, this goes red.
     *
     * Reads invoices raised BEFORE this run — identified by not carrying this run's tag — and asserts the
     * field is empty on them. It also asserts there ARE some, because "no rows to check" would make this
     * pass forever while proving nothing (existence is not eligibility).
     */
    /*
     * ⚠ "HISTORIC" MEANS BEFORE THE FEATURE EXISTED, NOT "NOT FROM THIS RUN".
     *
     * The first version of this case filtered on `R2A-${run}` and went red naming INV-000209=TAKE_AWAY —
     * invoices from an EARLIER run of this very gate, which are supposed to carry a type. The case was
     * accusing the product of retro-labelling when it was reading its own fixtures.
     *
     * Two changes make it mean what it says: the window ENDS YESTERDAY, so nothing this gate has ever
     * created today is in scope, and every R2A fixture from any run is excluded regardless of date.
     */
    const old = new Date(); old.setFullYear(old.getFullYear() - 1)
    const yesterday = new Date(); yesterday.setDate(yesterday.getDate() - 1)
    cy.request({
      method: 'POST', url: '/loadSR', form: true, failOnStatusCode: false,
      body: { rp: 4, sd: ddmmyyyy(old), ed: ddmmyyyy(yesterday, '23:59:59') },
    }).then((r) => {
      const rows = list(r.body)
      const older = rows.filter((s) => String(JSON.stringify(s)).indexOf('R2A-') < 0)
      // Existence is not eligibility: with no historic rows this case would pass forever proving nothing.
      expect(older.length, 'there are pre-existing invoices to check against').to.be.greaterThan(0)

      const labelled = older.filter((s) => s.orderType != null && String(s.orderType) !== '')
      expect(labelled.length,
        `no historic invoice was given a service mode it never had (found: ${labelled.slice(0, 3)
          .map((s) => s.invoiceNo + '=' + s.orderType).join(', ')})`).to.eq(0)
    })
  })

  it('⭐⭐ 3 — DELIVERY with no contact is refused, before anything is written', () => {
    sell('DELIVERY', '').then((res) => {
      expect(res.body.status, `refused: ${JSON.stringify(res.body).slice(0, 220)}`).to.eq('ERROR')
      expect(String(res.body.message || '')).to.match(/contact/i)
    })
  })

  it('⭐ 4 — THE CONTROL: dine-in and take-away need no customer contact', () => {
    // Without this, case 3 passes just as well if the server demands a contact for EVERY typed order —
    // which would put a required field in front of a walk-in and be reported as "the till got slower".
    sell('DINE_IN', '').then(invoiceOf)
    sell('TAKE_AWAY', '').then(invoiceOf)
  })

  it('⭐ 5 — owner corrects a mis-tapped type, and the change is on the record', () => {
    /*
     * The ruling (2026-09-25) was the most permissive of three options: owner/admin may change it at any
     * time. That is only defensible WITH an audit row, because the day's split can move after somebody has
     * read it — so the audit entry is asserted here, not just the new value.
     */
    sell('TAKE_AWAY', '0335-2456847').then((res) => {
      const no = invoiceOf(res)
      cy.request({ method: 'POST', url: '/changeOrderType', form: true, failOnStatusCode: false,
        body: { invoiceNo: no, orderType: 'DINE_IN' } })
        .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))

      // ⚠ getReceipt, NOT getSellInvoice — that one takes a sellId, and passing invoiceNo returns a
      // payload with no orderType, so the case failed reading `undefined` and looked like the change had
      // silently done nothing. Case 1 had the same bug; this was the second copy of it.
      cy.request({ url: `/getReceipt?invoiceNo=${encodeURIComponent(no)}`, failOnStatusCode: false })
        .then((r) => {
          const inv = (r.body && (r.body.data || r.body.object)) || {}
          expect(String(inv.orderType), `${no} after the correction`).to.eq('DINE_IN')
        })

      // The OLD value is the point: "changed to dine-in" cannot reconstruct yesterday's split on its own.
      cy.findAudit((a) => a.action === 'SALE_ORDER_TYPE_CHANGE' && a.entityRef === no)
        .then((a) => expect(String(a.details)).to.contain('TAKE_AWAY').and.to.contain('DINE_IN'))
    })
  })

  it('⭐⭐ 6 — THE SPLIT RECONCILES: the parts sum to the day\'s take', () => {
    /*
     * Asserted against an INDEPENDENT source — the same invoices totalled without reference to the split.
     * "It groups" is not the property worth having; "the groups add up to what was actually taken" is.
     */
    saleReportToday().then((mine) => {
      expect(mine.length, 'this run raised invoices to reconcile').to.be.greaterThan(0)

      const round = (n) => Math.round(Number(n || 0) * 100) / 100

      /*
       * ⚠ DEDUPE BY INVOICE FIRST. loadSR returns LINE rows and grandTotal is the INVOICE total repeated on
       * every line of that invoice, so summing the rows directly double-counts any sale with more than one
       * line. This fixture rings one line per sale, so the naive version would pass — and would be wrong the
       * first time anyone added a second line, which is exactly the kind of green that means nothing.
       */
      const byInvoice = {}
      mine.forEach((s) => {
        const no = String(s.invoiceNo || '')
        if (no && byInvoice[no] === undefined) {
          byInvoice[no] = {
            total: Number(s.grandTotal || 0),
            type: s.orderType == null || s.orderType === '' ? 'NONE' : String(s.orderType),
          }
        }
      })
      const invoices = Object.keys(byInvoice).map((no) => byInvoice[no])
      expect(invoices.length, 'invoices resolved from the report rows').to.be.greaterThan(0)

      const total = round(invoices.reduce((t, i) => t + i.total, 0))
      const byType = {}
      invoices.forEach((i) => { byType[i.type] = round((byType[i.type] || 0) + i.total) })
      const summed = round(Object.keys(byType).reduce((t, k) => t + byType[k], 0))

      expect(summed, `split ${JSON.stringify(byType)} must equal the day's take`).to.eq(total)
      expect(Object.keys(byType).length, 'more than one service mode was actually used').to.be.greaterThan(1)
    })
  })

  it('⭐ 7 — a tenant WITHOUT the capability can still sell, and sees no chooser', () => {
    /*
     * The safety property of the whole slice, and the one that would hurt most if it broke: almost every
     * tenant on the platform has no order types. Switching the capability off must hide a feature, never
     * break a till.
     *
     * The untyped sale is asserted FIRST, because that is the one every other shop makes.
     */
    cy.setCapability('orderTypes', false)

    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
      body: {
        customer: { name: `R2A_off_${uniq()}`, contact: '' },
        sales: [{ productId: made.roll.id, itemName: made.roll.name, quantity: 1, sellRate: made.roll.price }],
        tenders: [{ method: 'CASH', amount: made.roll.price }],
        paidAmount: made.roll.price, grandTotal: made.roll.price,
        idempotencyKey: `cy-r2a-off-${uniq()}`,
      },
    }).then((r) => {
      expect(r.body.status, 'a shop without the capability sells exactly as before').to.eq('SUCCESS')
    })

    // And a sale that DOES claim a type is refused rather than quietly recorded.
    sell('DINE_IN', '0335-2456847').then((r) => {
      expect(r.body.status, 'a type the tenant may not use is refused').to.eq('ERROR')
    })

    // The chooser is markup with [data-capability="orderTypes"], which capabilities.js hides via .cap-off.
    cy.visitSaleScreen()
    cy.get('#orderTypeWrap').should('not.be.visible')

    // ⚠ NOT `setCapability(true)` — that is refused for a capability outside the plan. Reset removes the
    // override so the preset grants it again, which is also what the teardown relies on.
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, failOnStatusCode: false,
      body: { key: 'org.cap.orderTypes' } })
      .then((r) => expect(r.body && r.body.success,
        `restore orderTypes by reset: ${JSON.stringify(r.body)}`).to.eq(true))
  })
})
