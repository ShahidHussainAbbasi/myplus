/**
 * #27 — a booker sees only the quotes they raised; owner and admin see the whole org.
 *
 * ⚠ WRITTEN BEFORE THE IMPLEMENTATION (cadence). These cases are the requirement.
 * Design: microservices/docs/slices/quotes-visibility.md
 *
 * <h3>What is wrong today</h3>
 * `SalesQuoteRepo.SCOPE` is TENANT-level only — org, plus a NULL-org fallback. So `list()` returns every
 * quote in the organisation and `load(id)` opens any of them. Every user with `dealerPricing` sees every
 * rep's pricing, including what discount another rep offered which account.
 *
 * <h3>The rule, ruled by the user</h3>
 * A booker sees only their own rows. Owner and admin see all. This is the SAME rule `visibleSells()` and
 * `visiblePurchases()` already apply — `requestUtil.callerSeesWholeOrg()` — so this slice applies an existing
 * policy to a screen that was missed, rather than inventing one.
 *
 * <h3>⚠ The case that matters most is #3</h3>
 * A list that filters while a by-id read does not is not a restriction — it is an inconvenience. `getQuote`
 * must refuse a foreign quote by ID, not merely omit it from a listing.
 */

const OWNER = 'owner.marketplace@myplus.com'

/** Raise a quote as whoever is currently signed in, and yield its id. */
function raiseQuote() {
  return cy.request({ url: '/getUserCustomer' }).then((c) => {
    const customers = (c.body && c.body.collection) || []
    expect(customers.length, 'the tenant has a customer to quote').to.be.greaterThan(0)

    return cy.request({ url: '/getUserProduct' }).then((p) => {
      const products = (p.body && p.body.collection) || []
      expect(products.length, 'the tenant has a product to quote').to.be.greaterThan(0)

      return cy.request({
        method: 'POST', url: '/addQuote',
        headers: { 'Content-Type': 'application/json' },
        body: {
          customerId: customers[0].customerId,
          lines: [{ productId: products[0].id, quantity: 2, unitPrice: 100 }],
        },
        failOnStatusCode: false,
      }).then((r) => {
        // Refusals arrive as HTTP 200 with an error envelope — the status code proves nothing.
        expect(r.body.status, `addQuote: ${JSON.stringify(r.body).slice(0, 200)}`).to.not.eq('ERROR')
        const q = r.body.object || r.body.data
        expect(q, 'the quote was created').to.exist
        return cy.wrap(q.id || q)
      })
    })
  })
}

describe('#27 — quote visibility', () => {

  it('⭐ 1. a booker sees ONLY the quotes they raised', () => {
    /*
     * The defect. Seeded as the OWNER, then listed as the BOOKER: the owner's quote must not appear.
     * Seeding through the product's own endpoint rather than the database, so the row is written exactly as a
     * real quote is — a fixture that takes a shortcut proves the shortcut works.
     */
    cy.loginAsMarketplaceOwner()
    raiseQuote().then((ownerQuoteId) => {
      cy.loginAsOrderBooker()
      cy.request({ url: '/getUserQuotes' }).then((r) => {
        const rows = (r.body && r.body.collection) || []
        const ids = rows.map((q) => String(q.id))
        expect(ids, 'the owner\'s quote is not in the booker\'s list')
          .to.not.include(String(ownerQuoteId))
      })
    })
  })

  it('⭐ 2. an owner sees quotes raised by a booker', () => {
    // The other direction, and the reason this is a VISIBILITY rule rather than ownership: management must
    // still see the pipeline. A rule that hid a rep's quotes from their manager would be worse than none.
    cy.loginAsOrderBooker()
    raiseQuote().then((bookerQuoteId) => {
      cy.loginAsMarketplaceOwner()
      cy.request({ url: '/getUserQuotes' }).then((r) => {
        const ids = ((r.body && r.body.collection) || []).map((q) => String(q.id))
        expect(ids, 'the owner sees the booker\'s quote').to.include(String(bookerQuoteId))
      })
    })
  })

  it('⭐⭐ 3. a booker cannot open another user\'s quote BY ID', () => {
    /*
     * THE CASE THAT MATTERS MOST.
     *
     * Filtering a list while leaving the by-id read open is not a restriction — anyone can count upwards. The
     * same lesson as the credit-note endpoints in #15: the scope predicate has to be in EVERY read, not the
     * one that happens to render a screen.
     */
    cy.loginAsMarketplaceOwner()
    raiseQuote().then((ownerQuoteId) => {
      cy.loginAsOrderBooker()
      cy.request({ url: '/getQuote?id=' + ownerQuoteId, failOnStatusCode: false }).then((r) => {
        expect(r.body.status, 'a foreign quote reads as NOT FOUND, never as data').to.not.eq('SUCCESS')
        expect(r.body.object, 'and no quote comes back').to.not.exist
      })
    })
  })

  it('4. a booker can still raise, see and send their OWN quote', () => {
    /*
     * The guard against over-correcting. Restricting visibility must not leave a rep unable to see the quote
     * they just raised — that would be the #23 mistake (blocking the person doing the work) on another screen.
     */
    cy.loginAsOrderBooker()
    raiseQuote().then((myQuoteId) => {
      cy.request({ url: '/getUserQuotes' }).then((r) => {
        const ids = ((r.body && r.body.collection) || []).map((q) => String(q.id))
        expect(ids, 'a booker sees their own quote').to.include(String(myQuoteId))
      })
      cy.request({ url: '/getQuote?id=' + myQuoteId }).then((r) => {
        expect(r.body.status, 'and can open it').to.eq('SUCCESS')
      })
      cy.request({ method: 'POST', url: '/sendQuote?id=' + myQuoteId, failOnStatusCode: false })
        .then((r) => {
          expect(r.body.status, 'and can send it to the customer').to.not.eq('ERROR')
        })
    })
  })

  it('5. approval remains owner/admin — a booker cannot approve their own discount', () => {
    // Already enforced (@PreAuthorize on approveQuote) and asserted here so this slice cannot loosen it:
    // approving your own discount is the one thing that was correctly locked before any of this.
    cy.loginAsOrderBooker()
    raiseQuote().then((id) => {
      cy.request({ method: 'POST', url: '/approveQuote?id=' + id, failOnStatusCode: false }).then((r) => {
        const refused = r.status === 403 || r.body.success === false
          || ['ERROR', 'FAILED', 'NOT_FOUND'].includes(r.body.status)
        expect(refused, `a booker must not approve: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
      })
    })
  })

  it('6. the Quotes screen still works for a booker', () => {
    /*
     * Reachability. A visibility rule implemented only on the server would leave a booker staring at a screen
     * that renders and never fills — indistinguishable from having no quotes. Asserted as a real screen.
     */
    cy.loginAsOrderBooker()
    cy.visitDashboardSettled()
    cy.get('body').then(($b) => {
      if (!$b.find('#navQuotes, [onclick*="showQuotes"]').length) return   // capability off for this tenant
      cy.get('[onclick*="showQuotes"]').first().click({ force: true })
      cy.get('#QuoteDiv', { timeout: 20000 }).should('be.visible')
    })
  })
})
