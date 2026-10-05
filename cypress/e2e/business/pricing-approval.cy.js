/**
 * PR-4 — the owner approves a price before customers see it.
 *
 * Design: microservices/docs/selling-price-per-purchase-analysis.md §11. Written BEFORE the implementation.
 *
 *   A1  the markup rule offers Approval; the default stays Suggest
 *   A2  Approval: a purchase does NOT move the price; it proposes the rule's price (14.5% on 210 = 240.45); cost stamped
 *   A3  Approval with no %: the bill's S/U becomes the proposal (only when it differs from the price now)
 *   A4  a newer purchase supersedes the older proposal — one pending change per product
 *   A5  Approve sets the price, history source APPROVAL naming the bill; the proposal is no longer pending
 *   A6  Reject changes nothing and records the decision
 *   A7  Approve against a price that moved since it was proposed is refused (stale), and nothing changes
 *   A8  Auto: a change the "never lower" guard held back is proposed, with the guard as its reason
 *   A9  Keep never proposes; Per batch never proposes
 *   A10 a plain member cannot approve or reject; another tenant's proposal is not found
 *   A11 the screen: Purchase → Price approvals lists the proposal with its badge; Approve on screen sets the price
 *   A12 the purchase form says the price will wait for approval, before saving
 *
 * Tenant: owner.business@ (members admin./user.business@ for A10), owner.pharma@ for the other-tenant half.
 * Server state: every pricing setting this spec touches is put back EXACTLY in after().
 *
 * Run:  npx cypress run --spec cypress/e2e/business/pricing-approval.cy.js
 */
const KEYS = ['pos.pricing.purchaseMode', 'pos.pricing.markupMode', 'pos.pricing.markupPct', 'pos.pricing.markupBasis',
  'pos.pricing.markupRounding', 'pos.pricing.markupNeverLower', 'pos.pricing.markupMaxRisePct']
const STAMP = Date.now()
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return Array.isArray(b) ? b : [] }

const set = (key, value) => cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value: String(value) }, failOnStatusCode: false })
  .then((r) => expect(r.body && r.body.success, `save ${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))
const reset = (key) => cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key }, failOnStatusCode: false })
const product = (id) => cy.request('/getCatalogProduct?id=' + id).then((r) => cy.wrap(r.body.data))
const history = (id) => cy.request('/productPriceHistory?productId=' + id).then((r) => cy.wrap(r.body.data.history))
const seed = (price) => cy.seedProduct({ name: 'PRA_' + uniq(), sellingPrice: price }).then((p) => cy.wrap(p.productId))
const proposals = (status) => cy.request({ url: '/priceApprovals' + (status ? '?status=' + status : ''), failOnStatusCode: false })
  .then((r) => { expect(r.status, JSON.stringify(r.body).slice(0, 200)).to.eq(200); return cy.wrap(list(r.body.data || r.body)) })
const pendingFor = (productId) => proposals('PENDING').then((ps) => cy.wrap(ps.filter((p) => Number(p.productId) === Number(productId))))
const approve = (id, expectedCurrent) => cy.request({ method: 'POST', url: '/approvePriceChange', form: true, failOnStatusCode: false,
  body: { id, expectedCurrent } })
const reject = (id, note) => cy.request({ method: 'POST', url: '/rejectPriceChange', form: true, failOnStatusCode: false, body: { id, note } })

let vendorId = null
const ensureVendor = () => {
  if (vendorId) return cy.wrap(vendorId)
  const vname = 'PRA_V_' + STAMP
  return cy.ensureCompany().then((companyId) => {
    cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
      body: { name: vname, companyId, mobile: '0305' + String(STAMP).slice(-7), email: 'pra' + STAMP + '@t.com' } })
    return cy.request('/getUserVenders').then((vr) => {
      const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
      vendorId = (new RegExp('<option value=(\\d+)[^>]*>' + vname).exec(html) || [])[1]
      expect(vendorId, 'spec vendor').to.exist
      return cy.wrap(vendorId)
    })
  })
}
const purchase = (productId, cost, sell, inv) => ensureVendor().then((venderId) =>
  cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, venderId, quantity: 1, purchaseRate: cost, 'stock.bpurchaseRate': cost, 'stock.bsellRate': sell,
            totalAmount: cost, netAmount: sell - cost, purchaseInvoiceNo: inv } })
    .then((p) => expect(p.body.status, `addPurchase ${inv}: ${JSON.stringify(p.body).slice(0, 300)}`).to.eq('SUCCESS')))

describe('PR-4 — the owner approves a price before customers see it', () => {
  let saved = null

  before(() => {
    cy.loginAsOwner()
    cy.request('/getBusinessConfig').then((r) => {
      const all = list(r.body)
      saved = KEYS.map((k) => { const e = all.find((x) => x.key === k); return { k, chosen: !!e && e.isDefault === false, v: e && e.value } })
    })
  })
  beforeEach(() => {
    cy.loginAsOwner()
    KEYS.forEach(reset)
  })
  after(() => {
    cy.loginAsOwner()
    cy.then(() => saved.forEach((s) => (s.chosen ? set(s.k, s.v) : reset(s.k))))
  })

  it('A1 the markup rule offers Approval; the default stays Suggest', () => {
    cy.request('/getBusinessConfig').then((r) => {
      const e = list(r.body).find((x) => x.key === 'pos.pricing.markupMode')
      expect(e.value).to.eq('suggest')
      expect((e.options || []).map((o) => o.value)).to.deep.eq(['off', 'suggest', 'auto', 'approval'])
    })
  })

  it('A2 Approval: the price does not move; the rule price is proposed; the cost is stamped', () => {
    set('pos.pricing.markupMode', 'approval'); set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRA-2-' + STAMP)
      product(id).then((p) => {
        expect(Number(p.sellingPrice), 'not moved').to.eq(200)
        expect(Number(p.lastPurchaseRate), 'cost stamped').to.eq(210)
      })
      pendingFor(id).then((ps) => {
        expect(ps, JSON.stringify(ps)).to.have.length(1)
        expect(Number(ps[0].currentPrice)).to.eq(200)
        expect(Number(ps[0].proposedPrice)).to.eq(240.45)
        expect(ps[0].source).to.eq('MARKUP')
        expect(ps[0].ref).to.eq('PRA-2-' + STAMP)
        expect(ps[0].detail).to.contain('14.5%')
      })
      history(id).then((h) => expect(h.map((x) => x.source), 'no price row').to.deep.eq(['MANUAL']))
    })
  })

  it('A3 Approval with no %: the bill S/U is proposed — and nothing when it equals the price now', () => {
    set('pos.pricing.markupMode', 'approval')
    seed(200).then((id) => {
      purchase(id, 150, 200, 'PRA-3a-' + STAMP)
      pendingFor(id).then((ps) => expect(ps, 'same price: nothing to approve').to.have.length(0))
      purchase(id, 150, 230, 'PRA-3b-' + STAMP)
      pendingFor(id).then((ps) => {
        expect(ps).to.have.length(1)
        expect(Number(ps[0].proposedPrice)).to.eq(230)
        expect(ps[0].source).to.eq('PURCHASE')
      })
      product(id).then((p) => expect(Number(p.sellingPrice)).to.eq(200))
    })
  })

  it('A4 a newer purchase supersedes the older proposal', () => {
    set('pos.pricing.markupMode', 'approval'); set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRA-4a-' + STAMP)
      purchase(id, 220, 260, 'PRA-4b-' + STAMP)
      pendingFor(id).then((ps) => {
        expect(ps).to.have.length(1)
        expect(Number(ps[0].proposedPrice), '14.5% on 220').to.eq(251.9)
        expect(ps[0].ref).to.eq('PRA-4b-' + STAMP)
      })
      proposals('SUPERSEDED').then((ps) => expect(ps.some((p) => p.ref === 'PRA-4a-' + STAMP)).to.eq(true))
    })
  })

  it('A5 Approve sets the price; history says APPROVAL with the bill', () => {
    set('pos.pricing.markupMode', 'approval'); set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRA-5-' + STAMP)
      pendingFor(id).then((ps) => approve(ps[0].id, 200).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true)))
      product(id).then((p) => expect(Number(p.sellingPrice)).to.eq(240.45))
      history(id).then((h) => {
        expect(h[0].source).to.eq('APPROVAL')
        expect(h[0].ref).to.eq('PRA-5-' + STAMP)
        expect(Number(h[0].newPrice)).to.eq(240.45)
      })
      pendingFor(id).then((ps) => expect(ps).to.have.length(0))
      proposals('APPROVED').then((ps) => expect(ps.some((p) => p.ref === 'PRA-5-' + STAMP && p.decidedBy)).to.eq(true))
    })
  })

  it('A6 Reject changes nothing and records the decision', () => {
    set('pos.pricing.markupMode', 'approval'); set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRA-6-' + STAMP)
      pendingFor(id).then((ps) => reject(ps[0].id, 'too high for this street').then((r) => expect(r.body.success).to.eq(true)))
      product(id).then((p) => expect(Number(p.sellingPrice)).to.eq(200))
      proposals('REJECTED').then((ps) => {
        const p = ps.find((x) => x.ref === 'PRA-6-' + STAMP)
        expect(p.decisionNote).to.eq('too high for this street')
      })
      pendingFor(id).then((ps) => expect(ps).to.have.length(0))
    })
  })

  it('A7 Approve against a price that moved since is refused; nothing changes', () => {
    set('pos.pricing.markupMode', 'approval'); set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRA-7-' + STAMP)
      // Someone re-prices by hand on the product form meanwhile.
      product(id).then((p) => cy.request({ method: 'POST', url: '/updateProduct', headers: { 'Content-Type': 'application/json' },
        body: Object.assign({}, p, { sellingPrice: 205 }) }))
      pendingFor(id).then((ps) => approve(ps[0].id, 200).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.match(/price is now 205\.00/i)
      }))
      product(id).then((p) => expect(Number(p.sellingPrice)).to.eq(205))
      pendingFor(id).then((ps) => expect(ps, 'still pending, to be decided again').to.have.length(1))
    })
  })

  it('A8 Auto: a change the "never lower" guard held back is proposed, with the guard as its reason', () => {
    set('pos.pricing.markupMode', 'auto'); set('pos.pricing.markupPct', '14.5')
    seed(300).then((id) => {
      purchase(id, 210, 250, 'PRA-8-' + STAMP)
      product(id).then((p) => expect(Number(p.sellingPrice), 'held back').to.eq(300))
      pendingFor(id).then((ps) => {
        expect(ps).to.have.length(1)
        expect(Number(ps[0].proposedPrice)).to.eq(240.45)
        expect(ps[0].reason).to.eq('NEVER_LOWER')
      })
    })
  })

  it('A9 Keep never proposes; Per batch never proposes', () => {
    set('pos.pricing.markupMode', 'approval'); set('pos.pricing.markupPct', '14.5')
    set('pos.pricing.purchaseMode', 'keep')
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRA-9a-' + STAMP)
      pendingFor(id).then((ps) => expect(ps).to.have.length(0))
    })
    set('pos.pricing.purchaseMode', 'per_batch')
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRA-9b-' + STAMP)
      pendingFor(id).then((ps) => expect(ps).to.have.length(0))
    })
  })

  it('A10 a plain member cannot approve; another tenant cannot see or decide this proposal', () => {
    set('pos.pricing.markupMode', 'approval'); set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRA-10-' + STAMP)
      pendingFor(id).then((ps) => {
        const pid = ps[0].id
        cy.loginAsTier('user', 'business')
        approve(pid, 200).then((r) => expect(r.body.success, JSON.stringify(r.body).slice(0, 200)).to.not.eq(true))
        cy.loginAsPharmaOwner()
        proposals('PENDING').then((all) => expect(all.some((p) => p.id === pid), 'not listed to another tenant').to.eq(false))
        approve(pid, 200).then((r) => expect(r.body.success).to.not.eq(true))
        cy.loginAsOwner()
        product(id).then((p) => expect(Number(p.sellingPrice)).to.eq(200))
        pendingFor(id).then((left) => expect(left).to.have.length(1))
      })
    })
  })

  it('A11 the screen: Price approvals lists it, with its badge; Approve on screen sets the price', () => {
    set('pos.pricing.markupMode', 'approval'); set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      purchase(id, 210, 250, 'PRA-11-' + STAMP)
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      // The count is on the Purchase button itself — visible with the menu closed — and again beside the menu item.
      cy.get('#paCountTop', { timeout: 15000 }).should('be.visible').invoke('text').then((t) => expect(Number(t)).to.be.greaterThan(0))
      cy.get('#snavPurchase .snav-btn').click()
      cy.get('#navPriceApprovals').should('be.visible').find('#paCount').should('be.visible')
      cy.get('#navPriceApprovals').click()
      cy.get('#PriceApprovalsDiv').should('be.visible')
      cy.get(`#tablePriceApprovals tr[data-product="${id}"]`, { timeout: 15000 }).as('row')
      cy.get('@row').should('contain', '200.00').and('contain', '240.45').and('contain', '+20.2%').and('contain', 'PRA-11-' + STAMP)
      cy.intercept('POST', '**/approvePriceChange').as('ok')
      cy.get('@row').find('.pa-approve').click()
      cy.get('[data-ui-confirm="ok"]').click()
      cy.wait('@ok').its('response.body.success').should('eq', true)
      cy.get(`#tablePriceApprovals tr[data-product="${id}"]`).should('not.exist')
      product(id).then((p) => expect(Number(p.sellingPrice)).to.eq(240.45))
    })
  })

  it('A12 the purchase form says the price will wait for approval', () => {
    set('pos.pricing.markupMode', 'approval'); set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      cy.visit('/businessDashboard')
      cy.openPurchaseSection('purchaseDiv')
      cy.get('#newPurchase').click()
      cy.get('#PurchaseModal').should('have.class', 'open')
      cy.settled('#purchaseInvoiceNo')
      cy.intercept('GET', '/productStock*').as('prefill')
      cy.get('#purchaseItemDD').select(String(id), { force: true })
      cy.wait('@prefill', { timeout: 15000 })
      cy.get('#purchasePurchaseRate').click().clear().type('210')
      // Assert what was typed landed where it was typed: the form can move focus while it finishes opening.
      cy.get('#purchasePurchaseRate').should('have.value', '210')
      cy.get('#purchasePriceEffect', { timeout: 10000 }).should('have.attr', 'data-effect', 'approval')
        .and('contain', '240.45').and('contain', '200.00')
    })
  })
})
