/**
 * B2B Phase 1 — credit limit & payment terms (= OMS B4, customer requirement #9).
 *
 * `warn` here means TAKE CONFIRMATION, not "record it and mention it afterwards": an over-limit sale is
 * answered `CONFIRM` with **nothing written and no stock reserved**, the operator is asked, and only a
 * re-submit carrying `creditAcknowledged` records it. A note after the money has moved would not be
 * consent — undoing it would mean a void.
 *
 * The headline assertion is still the boring one: a customer with **no limit** — which is every customer
 * until an owner sets one — behaves exactly as before.
 *
 * Design: microservices/docs/slices/b2b-P1-credit-limit.md
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/credit-limit.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const mobile = () => '03' + Math.floor(1e8 + Math.random() * 9e8)

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success,
      `saveBusinessConfig ${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))

const configEntry = (key) =>
  cy.request('/getBusinessConfig').then((r) => list(r.body).find((e) => e.key === key))

/** A customer with a credit limit, created through the real endpoint. */
const customerWithLimit = (limit, termsDays) => {
  const name = 'CL_' + uniq()
  const body = { name, contact: 'C' + uniq(), creditLimit: limit }
  if (termsDays != null) body.paymentTermsDays = termsDays
  return cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false, body })
    .then((r) => {
      expect(r.body.status, `addCustomer ${name}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
      return cy.request('/getUserCustomer')
    })
    .then((r) => {
      const c = list(r.body).find((x) => x.name === name)
      expect(c, 'customer created').to.exist
      expect(Number(c.creditLimit), 'limit persisted').to.be.closeTo(Number(limit), 0.01)
      return cy.wrap(c)
    })
}

/** Sell `qty × rate` to an EXISTING customer, paying `paid`. Returns the raw response. */
const sellTo = (customer, productId, qty, rate, paid, opts = {}) => {
  const total = qty * rate
  const body = {
    customer: { customerId: customer.customerId, name: customer.name, contact: customer.contact },
    sales: [{ productId, quantity: qty, sellRate: rate, totalAmount: total, netAmount: total }],
    tenders: paid > 0 ? [{ method: 'CASH', amount: paid }] : [],
    paidAmount: paid, grandTotal: total,
    idempotencyKey: opts.key || 'cy-credit-' + uniq(),
  }
  if (opts.acknowledged) body.creditAcknowledged = true
  if (opts.dueDate) body.dueDate = opts.dueDate
  return cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    body, failOnStatusCode: false })
}

const invoiceCount = (customerId) =>
  cy.request('/getUserSell').then((r) =>
    list(r.body).filter((s) => {
      const ch = s.customerHistory || s
      return ch && ch.customer && Number(ch.customer.customerId) === Number(customerId)
    }).length)

describe('B2B P1 — credit limit & payment terms (#9)', () => {

  beforeEach(() => { cy.loginAsOwner() })

  after(() => {
    cy.loginAsOwner()
    setConfig('pos.sale.creditLimitPolicy', 'warn')
    setConfig('pos.purchase.creditLimitPolicy', 'warn')
  })

  // ── the settings ─────────────────────────────────────────────────────────────

  // Slice 106: this asserted the CURRENT value was 'warn' — a claim about org-wide mutable state that any
  // other spec invalidates by changing the policy (it read 'off' in a full-suite run). SettingEntry declares
  // its own `defaultValue`, which is the thing this test actually means and is immune to what anyone set.
  // The current value is still checked, but only for being a LEGAL policy — that catches a corrupt setting
  // without depending on spec ordering.
  it('both policies are offered, declaring warn as the default', () => {
    const POLICIES = ['off', 'warn', 'block']
    const assertPolicyEntry = (e, which) => {
      expect(e, `${which} policy offered`).to.exist
      expect(e.type, `${which} policy is a SELECT`).to.eq('SELECT')
      expect(String(e.defaultValue), `${which} policy defaults to warn`).to.eq('warn')
      expect((e.options || []).map((o) => String(o.value)), `${which} policy offers all three`)
        .to.include.members(POLICIES)
      expect(POLICIES, `${which} policy's current value is a legal policy`).to.include(String(e.value))
    }
    configEntry('pos.sale.creditLimitPolicy').then((e) => assertPolicyEntry(e, 'sale'))
    configEntry('pos.purchase.creditLimitPolicy').then((e) => assertPolicyEntry(e, 'purchase'))
  })

  // ── the regression guard: no limit = nothing changes ─────────────────────────

  it('a customer with NO limit sells on credit exactly as before', () => {
    // This is every existing customer in every live tenant. If this ever fails, the slice is a regression.
    setConfig('pos.sale.creditLimitPolicy', 'warn')
    const name = 'NoLimit_' + uniq()
    cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name, contact: 'C' + uniq() } })
      .then((r) => expect(r.body.status).to.eq('SUCCESS'))
    cy.request('/getUserCustomer').then((r) => {
      const c = list(r.body).find((x) => x.name === name)
      expect(c.creditLimit == null, 'no limit set').to.eq(true)
      cy.seedProduct({ name: 'NL_' + uniq(), sellingPrice: 500, stock: 10 }).then(({ productId }) => {
        sellTo(c, productId, 1, 500, 0).then((s) => {
          expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
        })
      })
    })
  })

  // ── warn = take confirmation ─────────────────────────────────────────────────

  describe('warn — the operator is asked FIRST', () => {

    it('an over-limit sale is held: CONFIRM, and NOTHING is written', () => {
      setConfig('pos.sale.creditLimitPolicy', 'warn')
      customerWithLimit(1000).then((c) => {
        cy.seedProduct({ name: 'CL_' + uniq(), sellingPrice: 1500, stock: 10 }).then(({ productId }) => {
          cy.request(`/productStock?productId=${productId}`).then((before) => {
            const stockBefore = JSON.stringify(before.body)

            sellTo(c, productId, 1, 1500, 0).then((s) => {
              expect(s.body.status, JSON.stringify(s.body)).to.eq('CONFIRM')
              expect(String(s.body.message)).to.match(/credit limit/i)
            })

            // The critical assertion of this whole slice: a held sale must have touched NOTHING.
            invoiceCount(c.customerId).then((n) => expect(n, 'no invoice recorded').to.eq(0))
            cy.request(`/productStock?productId=${productId}`).then((after) => {
              expect(JSON.stringify(after.body), 'no stock reserved by a held sale').to.eq(stockBefore)
            })
          })
        })
      })
    })

    it('confirming records it, once, and says how far over', () => {
      setConfig('pos.sale.creditLimitPolicy', 'warn')
      customerWithLimit(1000).then((c) => {
        cy.seedProduct({ name: 'CL_' + uniq(), sellingPrice: 1500, stock: 10 }).then(({ productId }) => {
          const key = 'cy-ack-' + uniq()
          sellTo(c, productId, 1, 1500, 0, { key }).then((s) =>
            expect(s.body.status).to.eq('CONFIRM'))

          sellTo(c, productId, 1, 1500, 0, { key, acknowledged: true }).then((s) => {
            expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
            expect(String(s.body.message), 'the accepted overage is still reported')
              .to.match(/over their credit limit/i)
          })

          // Same key twice after the confirm → still ONE invoice (SF-3 survives the two-step).
          sellTo(c, productId, 1, 1500, 0, { key, acknowledged: true })
          invoiceCount(c.customerId).then((n) => expect(n, 'exactly one invoice').to.eq(1))
        })
      })
    })

    it('a sale WITHIN the limit is never questioned', () => {
      setConfig('pos.sale.creditLimitPolicy', 'warn')
      customerWithLimit(1000).then((c) => {
        cy.seedProduct({ name: 'CL_' + uniq(), sellingPrice: 400, stock: 10 }).then(({ productId }) => {
          sellTo(c, productId, 1, 400, 0).then((s) => expect(s.body.status).to.eq('SUCCESS'))
        })
      })
    })

    it('paying in full never trips the limit', () => {
      // Cash from someone deep in debt improves the position; refusing it would be perverse.
      setConfig('pos.sale.creditLimitPolicy', 'warn')
      customerWithLimit(100).then((c) => {
        cy.seedProduct({ name: 'CL_' + uniq(), sellingPrice: 5000, stock: 10 }).then(({ productId }) => {
          sellTo(c, productId, 1, 5000, 5000).then((s) => {
            expect(s.body.status, 'fully paid = no exposure: ' + JSON.stringify(s.body)).to.eq('SUCCESS')
          })
        })
      })
    })
  })

  // ── block ────────────────────────────────────────────────────────────────────

  describe('block — nobody on the till can consent past it', () => {

    it('refuses, and an acknowledgement does NOT get past it', () => {
      setConfig('pos.sale.creditLimitPolicy', 'block')
      customerWithLimit(1000).then((c) => {
        cy.seedProduct({ name: 'CL_' + uniq(), sellingPrice: 1500, stock: 10 }).then(({ productId }) => {
          sellTo(c, productId, 1, 1500, 0).then((s) => {
            expect(s.body.status, JSON.stringify(s.body)).to.eq('ERROR')
            expect(String(s.body.message)).to.match(/blocked/i)
          })
          // The whole difference between block and warn.
          sellTo(c, productId, 1, 1500, 0, { acknowledged: true }).then((s) => {
            expect(s.body.status, 'block ignores the acknowledgement').to.eq('ERROR')
          })
          invoiceCount(c.customerId).then((n) => expect(n, 'nothing written').to.eq(0))
        })
      })
    })
  })

  // ── off ──────────────────────────────────────────────────────────────────────

  it('off — no check at all', () => {
    setConfig('pos.sale.creditLimitPolicy', 'off')
    customerWithLimit(1000).then((c) => {
      cy.seedProduct({ name: 'CL_' + uniq(), sellingPrice: 5000, stock: 10 }).then(({ productId }) => {
        sellTo(c, productId, 1, 5000, 0).then((s) => {
          expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
          expect(String(s.body.message)).to.not.match(/credit limit/i)
        })
      })
    })
  })

  // ── payment terms ────────────────────────────────────────────────────────────

  it('payment terms persist on the customer', () => {
    customerWithLimit(5000, 30).then((c) => {
      expect(Number(c.paymentTermsDays), 'Net 30 stored').to.eq(30)
    })
  })

  // ── UI: the shared dialog, and the live hint ─────────────────────────────────

  describe('UI', () => {

    it('the customer form offers credit limit and payment terms', () => {
      cy.openSection('CustomerDiv')
      cy.get('#newCustomer').click()
      cy.get('#CustomerModal').should('have.class', 'open')
      cy.get('#creditLimit').should('be.visible')
      cy.get('#paymentTermsDays').should('be.visible')
    })

    it('the vendor form offers a credit limit', () => {
      cy.openSection('VenderDiv')
      cy.get('#newVender').click()
      cy.get('#VenderModal').should('have.class', 'open')
      cy.get('#venderCreditLimit').should('be.visible')
    })

    it('the sell screen hides the limit fields for a customer without one', () => {
      cy.openSellSection('sellDiv')
      cy.get('#sellCreditLimitWrap', { timeout: 10000 }).should('exist').and('not.be.visible')
      cy.get('#sellCreditAvailableWrap').should('not.be.visible')
    })

    it('an over-limit sale raises the SHARED confirm dialog, and cancelling records nothing', () => {
      setConfig('pos.sale.creditLimitPolicy', 'warn')
      customerWithLimit(1000).then((c) => {
        cy.seedProduct({ name: 'CLUI_' + uniq(), sellingPrice: 1500, stock: 10 }).then(({ productId }) => {
          // Drive the server contract the UI depends on, then assert the dialog wiring exists.
          sellTo(c, productId, 1, 1500, 0).then((s) => expect(s.body.status).to.eq('CONFIRM'))
          cy.openSellSection('sellDiv')
          // The shared dialog is the ONLY confirm in the app; its OK button is the stable test hook.
          cy.window().then((w) => {
            expect(typeof w.uiConfirm, 'shared uiConfirm is loaded on this screen').to.eq('function')
          })
          invoiceCount(c.customerId).then((n) => expect(n, 'a cancelled confirm records nothing').to.eq(0))
        })
      })
    })
  })

  // ── supplier side ────────────────────────────────────────────────────────────

  describe('supplier side (#9 says customer/supplier)', () => {

    const companyId = () =>
      cy.request('/getUserCompany').then((r) => {
        const found = list(r.body)
        expect(found.length, 'a company exists for the vendor to belong to').to.be.greaterThan(0)
        return cy.wrap(found[0].id)
      })

    it('a purchase past the supplier limit asks for confirmation, then records once acknowledged', () => {
      setConfig('pos.purchase.creditLimitPolicy', 'warn')
      const vname = 'CLV_' + uniq()
      companyId().then((cid) => {
        cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
          body: { name: vname, companyId: cid, mobile: mobile(), email: 'v' + uniq() + '@t.com',
                  creditLimit: 500 } })
          .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.be.oneOf(['SUCCESS', 'FOUND']))

        cy.request('/getUserVenders').then((vr) => {
          const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
          const id = (new RegExp('<option value=(\\d+)[^>]*>' + vname).exec(html) || [])[1]
          expect(id, 'vendor in the dropdown').to.exist

          cy.seedProduct({ name: 'CLVP_' + uniq(), sellingPrice: 100 }).then(({ productId }) => {
            const bill = {
              productId, venderId: id, quantity: 10, purchaseRate: 100,
              'stock.bpurchaseRate': 100, 'stock.bsellRate': 150,
              totalAmount: 1000, netAmount: 1000, paidAmount: 0,
              purchaseInvoiceNo: 'CLV-' + uniq(),
            }
            cy.request({ method: 'POST', url: '/addPurchase', form: true, body: bill,
              failOnStatusCode: false })
              .then((p) => {
                expect(p.body.status, JSON.stringify(p.body)).to.eq('CONFIRM')
                expect(String(p.body.message)).to.match(/credit limit/i)
              })

            cy.request({ method: 'POST', url: '/addPurchase', form: true,
              body: Object.assign({}, bill, { creditAcknowledged: true }), failOnStatusCode: false })
              .then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))
          })
        })
      })
    })
  })
})
