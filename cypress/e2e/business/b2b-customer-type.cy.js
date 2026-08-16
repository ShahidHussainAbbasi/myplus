/**
 * B2B-P0 — the customer channel flag (#1), the whole-invoice margin policy (#3), vendor dues on the
 * purchase screen (#8), and the opt-in promo footer (#13).
 * Design: microservices/docs/slices/b2b-P0-customer-type.md
 *
 * ALL MODULES ARE LIVE, so the headline assertions are the boring ones: an existing customer reads back
 * as WALK_IN rather than blank, the margin policy defaults to *warn* (never blocks a shop that did not ask
 * for it), and the promo footer is OFF unless the org opted in. Each behaviour is then proved by changing
 * its setting — standard C2, both halves: the catalog serves the key with the right default, AND the
 * behaviour actually changes.
 *
 * Run headed:
 *   npx cypress open --e2e        (pick business/b2b-customer-type.cy.js)
 *   npx cypress run  --spec cypress/e2e/business/b2b-customer-type.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

/** Owner-only settings write. /saveBusinessConfig proxies the shared common-settings endpoint. */
const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success,
      `saveBusinessConfig ${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))

const configEntry = (key) =>
  cy.request('/getBusinessConfig').then((r) => list(r.body).find((e) => e.key === key))

/**
 * A vendor must belong to a company (Vender.company is @ManyToOne(optional=false)), so get one first.
 * Reuse before create: addCompany has a known duplicate-check bug (see the header of company.cy.js) that
 * blocks the insert once ANY company exists, so the create path may only run when the org has none.
 */
const ensureCompany = () =>
  cy.request('/getUserCompany').then((r) => {
    // NB: GenericResponse has no `data` field — a List lands in `collection` (the Collection<?> constructor
    // overload wins over Object). Read it through list(), never r.body.data, or this silently sees nothing.
    const found = list(r.body)
    if (found.length && found[0].id) return cy.wrap(found[0].id)
    return cy.request({ method: 'POST', url: '/addCompany', form: true, failOnStatusCode: false,
      body: { name: 'B2B_Co_' + uniq(), phone: '042-1234567', email: 'b2b' + uniq() + '@t.com',
        address: 'Lahore' } })
      .then((c) => {
        expect(c.body.status, `addCompany: ${JSON.stringify(c.body)}`).to.be.oneOf(['SUCCESS', 'FOUND'])
        return cy.request('/getUserCompany')
      })
      .then((r2) => {
        const made = list(r2.body)
        expect(made.length, 'a company exists for the vendor to belong to').to.be.greaterThan(0)
        return cy.wrap(made[0].id)
      })
  })

/** @ValidMobileNumber wants ^((\+923)|(00923)|(03))-?\d{2}\d{7}$ — i.e. "03" followed by exactly 9 digits. */
const mobile = () => '03' + Math.floor(1e8 + Math.random() * 9e8)

/** Create a vendor and PROVE it was created — an unasserted fixture makes the test that follows vacuous. */
const addVendor = (name) =>
  ensureCompany().then((companyId) =>
    cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
      body: { name, companyId, mobile: mobile(), email: 'v' + uniq() + '@t.com' } })
      .then((r) => {
        expect(r.body.status, `addVender ${name}: ${JSON.stringify(r.body)}`).to.be.oneOf(['SUCCESS', 'FOUND'])
      }))

/** A product costed at 100/unit: seeded, then purchased at 100 so findRecentCosts has a rate to read. */
const costedProduct = (costRate = 100) =>
  cy.seedProduct({ name: 'Margin_' + uniq(), sellingPrice: 150, stock: 0 }).then(({ productId }) =>
    cy.request({
      method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
      body: {
        productId, quantity: 10, purchaseRate: costRate,
        'stock.bpurchaseRate': costRate, 'stock.bsellRate': 150,
        totalAmount: costRate * 10, netAmount: costRate * 10, paidAmount: costRate * 10,
        purchaseInvoiceNo: 'MP-' + uniq(),
      },
    }).then((p) => {
      expect(p.body.status, `purchase at ${costRate} (this is what makes the cost known): ${JSON.stringify(p.body)}`)
        .to.eq('SUCCESS')
      return cy.wrap(productId)
    }))

const sell = (productId, qty, rate) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customer: { name: 'B2BCust_' + uniq(), contact: '0300' + uniq(), paidAmount: qty * rate, dueAmount: 0 },
      sales: [{ productId, quantity: qty, sellRate: rate, totalAmount: qty * rate, netAmount: qty * rate }],
      tenders: [{ method: 'CASH', amount: qty * rate }],
      paidAmount: qty * rate, dueAmount: 0, grandTotal: qty * rate,
    },
  })

const receiptOf = (invoiceNo) =>
  cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo))
    .then((r) => r.body.object || r.body.data)

describe('B2B-P0 — customer type, margin policy, vendor dues, promo footer', () => {

  // The owner account: settings writes are @PreAuthorize'd, and the owner also sells.
  beforeEach(() => { cy.loginAsOwner() })

  after(() => {
    // Restore the shipped defaults, whatever the run did — a later spec inheriting "block" would fail
    // somewhere confusing and far away.
    cy.loginAsOwner()
    setConfig('pos.sale.marginPolicy', 'warn')
    setConfig('pos.receipt.showPromo', 'false')
  })

  // ── Both settings exist, with the defaults that keep a live shop unchanged ──────────────────────

  it('the catalog serves both new keys with safe defaults', () => {
    configEntry('pos.sale.marginPolicy').then((e) => {
      expect(e, 'pos.sale.marginPolicy is offered to the owner').to.exist
      expect(e.type).to.eq('SELECT')
      expect(String(e.value), 'defaults to warn — a live shop is never blocked unasked').to.eq('warn')
    })
    configEntry('pos.receipt.showPromo').then((e) => {
      expect(e, 'pos.receipt.showPromo is offered to the owner').to.exist
      expect(e.type).to.eq('BOOL')
      expect(String(e.value), "OFF by default — this prints on a paying client's own invoices").to.eq('false')
    })
  })

  // ── #1 customer type ───────────────────────────────────────────────────────────────────────────

  describe('Customer type', () => {

    it('a customer saved with no type reads back as WALK_IN, not blank', () => {
      const name = 'CT_Default_' + uniq()
      cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { name, contact: 'C' + uniq() } })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getUserCustomer').then((r) => {
        const c = list(r.body).find((x) => x.name === name)
        expect(c, 'customer created').to.exist
        expect(c.customerType, 'an unset type resolves to WALK_IN — todays behaviour, not "unknown"')
          .to.eq('WALK_IN')
      })
    })

    it('a trade type round-trips through save and reload', () => {
      const name = 'CT_Trade_' + uniq()
      cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { name, contact: 'C' + uniq(), customerType: 'WHOLESALE' } })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getUserCustomer').then((r) => {
        const c = list(r.body).find((x) => x.name === name)
        expect(c, 'customer created').to.exist
        expect(c.customerType, 'the trade channel survives the round trip').to.eq('WHOLESALE')
      })
    })

    it('an edit that omits the type does NOT demote a trade account back to walk-in', () => {
      // The realistic failure: an integration or an older form PUTs a name/phone change with no
      // customerType, and a WHOLESALE account silently becomes WALK_IN — losing its pricing and terms.
      const name = 'CT_Keep_' + uniq()
      const contact = 'C' + uniq()
      cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { name, contact, customerType: 'RETAILER' } })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getUserCustomer').then((r) => {
        const c = list(r.body).find((x) => x.name === name)
        expect(c, 'customer created').to.exist
        const id = c.customerId != null ? c.customerId : c.id

        // Edit the phone only — no customerType in the payload at all.
        cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
          body: { customerId: id, name, contact: contact + '9' } })
          .then((u) => expect(u.body.status, JSON.stringify(u.body)).to.eq('SUCCESS'))

        cy.request('/getUserCustomer').then((r2) => {
          const after = list(r2.body).find((x) => x.name === name)
          expect(after.customerType, 'the trade channel survives an unrelated edit').to.eq('RETAILER')
        })
      })
    })

    it('the form offers all four types and defaults to Walk-in', () => {
      cy.openSection('CustomerDiv')
      // The form lives in a crud-overlay modal — assert on what the shopkeeper actually sees, not just on
      // DOM presence, or a field hidden by a broken modal would still pass.
      cy.get('#newCustomer').click()
      cy.get('#CustomerModal').should('have.class', 'open')

      // #customerType is a bootstrap-select. The plugin sets the real <select> to display:none and
      // renders a button in its place, so asking the <select> whether it is :visible always answers
      // NO — the assertion could never pass once the field was enhanced. Judge visibility by the
      // WRAPPER the plugin actually shows, which is also what this case says it wants to check
      // ("what the shopkeeper actually sees").
      cy.get('#customerType', { timeout: 10000 }).should('exist')
      cy.get('#customerType').next('.bootstrap-select').should('be.visible')

      cy.get('#customerType option').should('have.length', 4)
      cy.get('#customerType option').then(($o) => {
        // .toArray() rather than spreading: a jQuery object is array-LIKE, and relying on the
        // iterator protocol is what made auth/signup.cy.js die with "$opts is not iterable".
        expect($o.toArray().map((o) => o.value)).to.deep.eq(['WALK_IN', 'RETAILER', 'WHOLESALE', 'VIP'])
      })
      cy.get('#customerType').should('have.value', 'WALK_IN')

      // The list must render the column too — editRecord() refills the form from the rendered row, so a
      // missing column silently resets the type on every edit.
      cy.get('#tableCustomer th[data-field="customerType"]').should('exist')
    })
  })

  // ── #3 whole-invoice margin policy ─────────────────────────────────────────────────────────────

  describe('Margin policy (#3)', () => {

    it('warn (the default): a below-cost sale is RECORDED and the cashier is told', () => {
      setConfig('pos.sale.marginPolicy', 'warn')
      costedProduct(100).then((productId) => {
        sell(productId, 1, 80).then((r) => {
          expect(r.body.status, 'warn must never stop the sale').to.eq('SUCCESS')
          expect(String(r.body.message), 'and the cashier must be told, not just the log')
            .to.match(/no profit/i)
        })
      })
    })

    it('block: the same sale is REFUSED, with the reason', () => {
      setConfig('pos.sale.marginPolicy', 'block')
      costedProduct(100).then((productId) => {
        sell(productId, 1, 80).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.not.eq('SUCCESS')
          expect(String(r.body.message)).to.match(/no profit|blocked/i)
        })
      })
    })

    it('off: no check at all', () => {
      setConfig('pos.sale.marginPolicy', 'off')
      costedProduct(100).then((productId) => {
        sell(productId, 1, 80).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          expect(String(r.body.message), 'nothing to say when the check is off').to.not.match(/no profit/i)
        })
      })
    })

    it('a profitable sale is silent even under block', () => {
      setConfig('pos.sale.marginPolicy', 'block')
      costedProduct(100).then((productId) => {
        sell(productId, 1, 150).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          expect(String(r.body.message)).to.not.match(/no profit/i)
        })
      })
    })

    it('a product with NO recorded cost still sells under block', () => {
      // The guard must not punish a shop that has never recorded a purchase — unknown cost is excluded from
      // both sides, and when nothing is costed there is nothing to judge.
      setConfig('pos.sale.marginPolicy', 'block')
      cy.seedProduct({ name: 'NoCost_' + uniq(), sellingPrice: 50, stock: 10 }).then(({ productId }) => {
        sell(productId, 1, 5).then((r) => {
          expect(r.body.status, 'no cost anywhere = nothing to judge: ' + JSON.stringify(r.body)).to.eq('SUCCESS')
        })
      })
    })
  })

  // ── #8 vendor dues on the purchase screen ──────────────────────────────────────────────────────

  describe('Vendor dues on purchase (#8)', () => {

    it('the vendor dropdown carries the outstanding payable', () => {
      // Guarantee at least one vendor exists so this asserts something. Without the assertion inside
      // addVendor, a broken /addVender would leave this test passing on vendors other specs happened to seed.
      addVendor('DueVend_' + uniq())

      cy.request('/getUserVenders').then((r) => {
        const html = String(r.body && r.body.object != null ? r.body.object : r.body)
        expect(html, 'vendor options rendered').to.contain('<option')
        expect(html, 'each option carries its dues, so the screen needs no second round trip')
          .to.contain('data-due=')
      })
    })

    it('picking a vendor reveals what they were already owed; a cash purchase shows nothing', () => {
      // A vendor carrying a real payable: bill 100, pay 0.
      const vname = 'DueVend_' + uniq()
      addVendor(vname)

      cy.request('/getUserVenders').then((vr) => {
        const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
        const id = (new RegExp('<option value=(\\d+)[^>]*>' + vname).exec(html) || [])[1]
        expect(id, `vendor ${vname} is in the dropdown`).to.exist

        cy.seedProduct({ name: 'DueProd_' + uniq(), sellingPrice: 20 }).then(({ productId }) => {
          cy.request({
            method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
            body: {
              productId, venderId: id, quantity: 10, purchaseRate: 10,
              'stock.bpurchaseRate': 10, 'stock.bsellRate': 20,
              totalAmount: 100, netAmount: 100, paidAmount: 0,   // on credit → the vendor is owed 100
              purchaseInvoiceNo: 'DUE-' + uniq(),
            },
          }).then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))

          // Purchase hangs off the #purchaseType nav select, not #registrationType — hence the dedicated command.
          cy.openPurchaseSection('purchaseDiv')
          cy.get('#newPurchase').click()
          cy.get('#PurchaseModal').should('have.class', 'open')

          // A cash purchase has no payable to report, so the row stays hidden until a vendor is chosen.
          cy.get('#purchaseVendorDuesWrap', { timeout: 10000 }).should('exist').and('not.be.visible')

          // #purchaseVenderDD is a bootstrap-select, so the real <select> is hidden — force the change.
          cy.get('#purchaseVenderDD', { timeout: 10000 })
            .find(`option[value="${id}"]`).should('exist')
          cy.get('#purchaseVenderDD').select(String(id), { force: true })

          cy.get('#purchaseVendorDuesWrap').should('be.visible')
          cy.get('#purchaseVendorDues').should(($b) => {
            expect(Number($b.val()), 'the payable this vendor already carries').to.be.closeTo(100, 0.01)
          })

          // Back to "no vendor" (cash) → nothing to report, so the row goes away again.
          cy.get('#purchaseVenderDD').select('', { force: true })
          cy.get('#purchaseVendorDuesWrap').should('not.be.visible')
        })
      })
    })
  })

  // ── #13 promo footer ───────────────────────────────────────────────────────────────────────────

  describe('Promo footer (#13)', () => {

    it('the receipt payload follows the org setting, and is off by default', () => {
      setConfig('pos.sale.marginPolicy', 'off')       // keep this test about the promo flag only
      cy.seedProduct({ name: 'Promo_' + uniq(), sellingPrice: 20, stock: 5 }).then(({ productId }) => {
        sell(productId, 1, 20).then((s) => {
          expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
          const invoiceNo = s.body.object

          setConfig('pos.receipt.showPromo', 'false')
          receiptOf(invoiceNo).then((inv) => {
            expect(inv.showPromo, 'off → receipt.js prints no promo line').to.not.eq(true)
          })

          setConfig('pos.receipt.showPromo', 'true')
          receiptOf(invoiceNo).then((inv) => {
            expect(inv.showPromo, 'opted in → the footer is allowed').to.eq(true)
          })
        })
      })
    })
  })
})
