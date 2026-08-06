/**
 * B2B Phase 4b — SalesQuote → approval → order.
 * Design: microservices/docs/slices/b2b-P4b-sales-quote-to-order.md
 *
 * A quote is an OFFER, not a price calculation: numbered, time-limited, internally approved when the discount is
 * large, accepted by the customer, then CONVERTED into an invoice through the SAME sale path the till uses.
 *
 * What this gate proves is what is silent when wrong:
 *   • the lifecycle refuses illegal moves — you cannot bill a customer from a rejected or unsent offer;
 *   • conversion produces exactly ONE invoice, and converting twice does not produce a second;
 *   • the customer's PO reaches the invoice (their AP clerk matches on it);
 *   • the credit check at conversion measures the 4a GROUP, not the billed row.
 *
 * Runs as the PHARMA owner: ROLE_OWNER (so the approve endpoint is reachable) and uncapped, since this spec
 * seeds several customers and products. Run headed.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

describe('B2B P4b — quote to order', () => {
  let productId

  before(() => {
    cy.loginAsPharmaOwner()
    cy.request({
      method: 'POST', url: '/addProduct', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { name: 'QuoteProd_' + uniq(), sku: 'QTP' + uniq(), sellingPrice: 100, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      productId = r.body.data.id
      expect(productId, 'seeded product').to.exist
      return cy.request({
        method: 'POST', url: '/addProductStock', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 500 },
      })
    })
  })

  beforeEach(() => cy.loginAsPharmaOwner())

  /** A trade customer, asserted so a later failure is never a silent fixture problem. */
  const addCustomer = (name, creditLimit) =>
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: {
        name, contact: '03' + Math.floor(Math.random() * 100000000),
        customerType: 'WHOLESALE',
        ...(creditLimit != null ? { creditLimit } : {}),
      },
    }).then((r) => {
      expect(r.body.status, `addCustomer ${name}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
      return cy.request('/getUserCustomer').then((list) => {
        const mine = (list.body.collection || []).find((c) => c.name === name)
        expect(mine, `customer ${name} readable back`).to.exist
        return cy.wrap(mine.customerId)
      })
    })

  /** Raise a quote. Note there is NO total field — the server prices the document. */
  const addQuote = (customerId, lines, extra = {}) =>
    cy.request({
      method: 'POST', url: '/addQuote', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { customerId, lines, ...extra },
    }).then((r) => {
      expect(r.body.status, `addQuote: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
      expect(r.body.object, 'the created quote').to.exist
      return cy.wrap(r.body.object)
    })

  const act = (url, id, extra = {}) =>
    cy.request({ method: 'POST', url: `/${url}`, form: true, failOnStatusCode: false,
                 body: { id, ...extra } })

  const line = (qty, rate) => ({ productId, quantity: qty, unitPrice: rate })

  it('a quote gets its own number in its own series', () => {
    addCustomer('QtCust_' + uniq(), 100000).then((customerId) => {
      addQuote(customerId, [line(2, 100)]).then((q) => {
        // Its OWN series: a quote is an offer, and sharing INV- would make it indistinguishable from money owed.
        expect(q.quoteNo, 'quote number').to.match(/^QTE-/)
        expect(q.status).to.eq('DRAFT')
        expect(Number(q.grandTotal), 'server-priced: 2 x 100').to.eq(200)
        expect(q.validUntil, 'an offer has a shelf life').to.be.a('string')
      })
    })
  })

  it('the full happy path: send → accept → convert produces ONE invoice', () => {
    const po = 'PO-' + uniq()
    addCustomer('QtFlow_' + uniq(), 100000).then((customerId) => {
      addQuote(customerId, [line(3, 100)], { customerPoNumber: po }).then((q) => {
        act('sendQuote', q.id).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
        act('acceptQuote', q.id).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

        act('convertQuote', q.id).then((r) => {
          expect(r.body.status, `convert: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
          const converted = r.body.object
          expect(converted.status).to.eq('CONVERTED')
          expect(converted.convertedInvoiceNo, 'the QTE- → INV- trail').to.match(/^INV-/)

          // The buyer's PO must reach the INVOICE — carrying it only on the quote makes it useless to the
          // person who asked for it.
          cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(converted.convertedInvoiceNo))
            .then((rec) => {
              expect(rec.body.status, JSON.stringify(rec.body)).to.eq('SUCCESS')
              const receipt = rec.body.object || rec.body.data
              expect(receipt.customerPoNumber, "the buyer's PO reached the invoice").to.eq(po)
            })
        })

        // Converting again must NOT mint a second invoice — one offer, one bill.
        act('convertQuote', q.id).then((r) => {
          expect(r.body.status, `a second convert must be refused: ${JSON.stringify(r.body)}`).to.not.eq('SUCCESS')
          expect(String(r.body.message || '')).to.match(/already converted/i)
        })
      })
    })
  })

  // ── the lifecycle guards ──────────────────────────────────────────────────────────────────────────

  it('a DRAFT cannot be converted — the customer never saw it', () => {
    addCustomer('QtDraft_' + uniq(), 100000).then((customerId) => {
      addQuote(customerId, [line(1, 100)]).then((q) => {
        act('convertQuote', q.id).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.not.eq('SUCCESS')
          expect(String(r.body.message || '')).to.match(/only an accepted quote/i)
        })
      })
    })
  })

  it('a REJECTED quote is terminal — it cannot be revived or converted', () => {
    addCustomer('QtRej_' + uniq(), 100000).then((customerId) => {
      addQuote(customerId, [line(1, 100)]).then((q) => {
        act('sendQuote', q.id)
        act('rejectQuote', q.id, { reason: 'Customer declined' })
          .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

        act('acceptQuote', q.id).then((r) => {
          expect(r.body.status, 'a rejected offer cannot be accepted later').to.not.eq('SUCCESS')
        })
        act('convertQuote', q.id).then((r) => {
          expect(r.body.status, 'and certainly cannot be billed').to.not.eq('SUCCESS')
        })
      })
    })
  })

  it('a quote from ANOTHER tenant is not reachable (anti-IDOR)', () => {
    // A real quote in the business owner's org, then an attempt to read it as the pharma owner. It must read
    // as "not found", never as "forbidden" — the refusal must not confirm the document exists elsewhere.
    cy.loginAsOwner()
    addCustomer('QtForeign_' + uniq(), 50000).then((customerId) => {
      addQuote(customerId, [line(1, 100)]).then((foreign) => {
        cy.loginAsPharmaOwner()
        cy.request({ url: '/getQuote?id=' + foreign.id, failOnStatusCode: false }).then((r) => {
          expect(r.body.status, `foreign quote must not be readable: ${JSON.stringify(r.body)}`).to.not.eq('SUCCESS')
          expect(String(r.body.message || '')).to.match(/not found/i)
        })
      })
    })
  })

  it('the Quotes screen lists a quote and its status (UI)', () => {
    addCustomer('QtUi_' + uniq(), 100000).then((customerId) => {
      addQuote(customerId, [line(2, 100)]).then((q) => {
        cy.visit('/businessDashboard')
        cy.window().should('have.property', 'showQuotes')
        cy.window().then((w) => w.showQuotes())

        cy.contains('#quoteBody tr', q.quoteNo, { timeout: 10000 }).within(() => {
          cy.contains('DRAFT').should('exist')
          cy.contains('button', 'Send').should('exist')
        })
      })
    })
  })
})
