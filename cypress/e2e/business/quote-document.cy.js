/**
 * #28 — the quote DOCUMENT: print/download at any stage, carrying its status.
 *
 * ⚠ WRITTEN BEFORE THE IMPLEMENTATION (cadence). These cases ARE the requirement.
 * Design: microservices/docs/slices/quote-document.md
 *
 * <h3>What is missing today</h3>
 * The quote lifecycle is built and correct — DRAFT → PENDING_APPROVAL → SENT → ACCEPTED → CONVERTED, with the
 * discount gate on the way out of DRAFT and a real invoice at the end. What does not exist is anything a
 * customer can hold: `grep printQuote|downloadQuote|QUOTE_A4` returns nothing, and `sendQuote` only writes a
 * status. So a rep can mark a quote "sent" that was never sent, because there is nothing to send.
 *
 * <h3>The rule, ruled by the user</h3>
 * *"agreed with current 'The five states' and user should be able to print or download at any stage with
 * status and details"*. No state changes. Print at EVERY stage, and the sheet says which stage it is.
 *
 * <h3>⚠ Why these cases render through the PRODUCTION shaping function</h3>
 * `return-documents.cy.js` case 6 renders the credit note by re-doing the `doc → sales[]` mapping INSIDE the
 * test. That cannot catch the very defect it was written for: if production's mapping is wrong, the test's own
 * correct mapping still renders fine. It passed the day CRN-000054 printed blank in the user's hands.
 *
 * So every render case here calls `window.buildQuoteDocumentHtml(doc)` — the SAME function print and download
 * both go through. If production maps `lines` where the renderer reads `sales`, these cases go red.
 */

const OWNER = 'owner.marketplace@myplus.com'

/** Raise a quote as whoever is signed in, and yield the created row. */
/**
 * Ensure the tenant has a customer, SEEDING one if not, and yield the list.
 *
 * ⚠ Seeds rather than asserts. `expect(customers.length).to.be.greaterThan(0)` failed on the first three
 * cases of a run in which later cases raised quotes for the same tenant perfectly well — a service that had
 * only just restarted was answering early reads with an empty collection. An assert-or-fail fixture turns
 * that into three red cases that say nothing about the feature; existence is not eligibility, so the fixture
 * creates what it needs.
 */
/** Customers this slice can actually quote: a document has to print a NAME. */
function usable(list) {
  return (list || []).filter((c) => c && c.name && String(c.name).trim())
}

function ensureCustomer() {
  return cy.request({ url: '/getUserCustomer' }).then((c) => {
    const found = usable((c.body && c.body.collection) || [])
    if (found.length) return cy.wrap(found)

    /*
     * ⚠ SEEDED, and FILTERED FOR ELIGIBILITY — two separate fixture lessons, both learned here.
     *
     * 1. Existence is not eligibility. This took `customers[0]` blindly and drew customer 4663, a legacy row
     *    with a BLANK NAME, then failed eleven cases reporting that the quote stamped no customer. The
     *    product was right; the fixture picked a customer no document could print. (Blank names are no longer
     *    creatable — `/addCustomer` is `@Validated` with `@NotBlank` — so this is old data that will sit there
     *    forever, first in the list, waiting for the next fixture that assumes row 0 is usable.)
     *
     * 2. Seeding must survive a SECOND run. `contact` is UNIQUE and the create path rejects a duplicate name
     *    among the caller's own customers, so a fixed name and number work exactly once and then fail with a
     *    message about duplicates that has nothing to do with the feature under test.
     */
    const stamp = Date.now().toString().slice(-9)
    return cy.request({
      method: 'POST', url: '/addCustomer', form: true,
      body: {
        name: `Quote Doc Customer ${stamp}`,
        contact: `03${stamp}`,
        address: '12 Test Road',
      },
      failOnStatusCode: false,
    }).then((r) => {
      // Assert the seed took. A silently-failed create would leave the next read empty and every assertion
      // downstream testing nothing at all.
      expect(r.body && (r.body.success || r.body.status === 'SUCCESS'),
        `seeding a customer: ${JSON.stringify(r.body).slice(0, 250)}`).to.be.ok

      return cy.request({ url: '/getUserCustomer' }).then((again) => {
        const seeded = usable((again.body && again.body.collection) || [])
        expect(seeded.length, 'a customer with a name is readable back').to.be.greaterThan(0)
        return cy.wrap(seeded)
      })
    })
  })
}

function raiseQuote() {
  return ensureCustomer().then((customers) => {
    return cy.request({ url: '/getUserProduct' }).then((p) => {
      const products = (p.body && p.body.collection) || []
      expect(products.length, 'the tenant has a product to quote').to.be.greaterThan(0)

      return cy.request({
        method: 'POST',
        url: '/addQuote',
        headers: { 'Content-Type': 'application/json' },
        body: {
          customerId: customers[0].customerId,
          lines: [{ productId: products[0].id, productName: products[0].name, quantity: 3, unitPrice: 250 }],
        },
        failOnStatusCode: false,
      }).then((r) => {
        // A refusal arrives as HTTP 200 with an error envelope — the status code proves nothing.
        expect(r.body.status, `addQuote: ${JSON.stringify(r.body).slice(0, 200)}`).to.not.eq('ERROR')
        const q = r.body.object || r.body.data
        expect(q && q.id, 'the quote was created').to.exist

        /*
         * ⚠ LOCALISES A DEFECT THAT OTHERWISE SURFACES ON THE PRINTED PAGE.
         *
         * `create()` stamps customerName from the customer record, but only if `inMyTenant(c)` passes — and
         * that guard's NULL-organisation branch additionally requires the customer's userId to match the
         * CALLER's. An owner lists the whole org, so they can quote a customer another user created; if that
         * row has no organizationId (legacy or seeded data), the name is silently not stamped.
         *
         * Asserting it HERE rather than only on the document means the failure names the layer that broke:
         * creation, not rendering. The document can only print what the quote stored.
         */
        expect(q.customerName,
          `the quote stamped a customer name at creation (customerId=${customers[0].customerId}, `
          + `expected "${customers[0].name}")`).to.not.be.empty

        return cy.wrap(q)
      })
    })
  })
}

/** Fetch the assembled document, asserting the envelope so a refusal cannot masquerade as an empty sheet. */
function fetchDocument(id) {
  return cy.request({ url: `/quoteDocument?id=${id}`, failOnStatusCode: false }).then((r) => {
    expect(r.body.status, `quoteDocument(${id}): ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
    expect(r.body.object, 'the document came back').to.exist
    return cy.wrap(r.body.object)
  })
}

/** Render through the SAME function the print path uses. Yields the HTML. */
function renderDocument(doc) {
  return cy.window().then((w) => {
    expect(w.DocumentRenderer, 'the renderer is loaded').to.exist
    expect(w.DocumentRenderer.PRESETS.QUOTE_A4, 'the QUOTE_A4 preset exists').to.exist
    expect(typeof w.buildQuoteDocumentHtml, 'production exposes the shared shaping').to.eq('function')
    return w.buildQuoteDocumentHtml(doc)
  })
}

/** Drive a quote to a status through the real endpoints, asserting each hop. */
function transition(route, id) {
  return cy.request({ method: 'POST', url: `/${route}?id=${id}`, failOnStatusCode: false }).then((r) => {
    expect(r.body.status, `${route}(${id}): ${JSON.stringify(r.body).slice(0, 160)}`).to.not.eq('ERROR')
  })
}

describe('#28 — the quote document', () => {
  beforeEach(() => {
    cy.loginAsMarketplaceOwner()
  })

  it('⭐ 1. a DRAFT quote prints, and the sheet SAYS DRAFT', () => {
    /*
     * THE SAFETY PROPERTY OF THIS SLICE.
     *
     * Paper outlives the screen it came from, and a quote is a priced commitment. A printed DRAFT that does
     * not say DRAFT is indistinguishable from a firm offer — so "it printed" is only half the requirement.
     */
    cy.visitDashboardSettled()
    raiseQuote().then((q) => {
      fetchDocument(q.id).then((doc) => {
        expect(doc.effectiveStatus, 'the document carries a status').to.eq('DRAFT')
        renderDocument(doc).then((html) => {
          expect(html, 'the sheet names the quote').to.contain(doc.quoteNo)
          expect(html, 'and declares it is a DRAFT').to.contain('DRAFT')
        })
      })
    })
  })

  it('⭐ 2. the document renders LINE ROWS, not just a header', () => {
    /*
     * THE #15 TRAP, on a new document.
     *
     * `toInvoiceShape` returned `lines:` while `buildContext` reads `inv.sales`, so the credit note printed a
     * correct header, correct totals and NOT ONE ROW. It shipped because the gate asserted only that a print
     * function existed. A blank document still contains its number and its total — only a ROW proves the
     * lines rendered.
     */
    cy.visitDashboardSettled()
    raiseQuote().then((q) => {
      fetchDocument(q.id).then((doc) => {
        const line = (doc.lines || [])[0]
        expect(line, 'the document has a line').to.exist
        expect(line.productName, 'and the line names a product').to.not.be.empty

        renderDocument(doc).then((html) => {
          expect(html, 'the product line appears on the printed page').to.contain(line.productName)

          /*
           * ⭐ AND THE CUSTOMER, which is a separate failure mode from the lines and was separately broken.
           *
           * `buildContext` reads `inv.customer.name`; a flat `customerName` is silently ignored. The first
           * build of this document rendered a correct number, correct lines and correct totals with an EMPTY
           * customer block — and the same bug turned out to be live on every CREDIT NOTE, unnoticed since #15,
           * because that gate asserted the product line and never the party.
           *
           * A quote with no customer on it is not a quote, it is a price list.
           */
          expect(doc.customerName, 'the document names a customer').to.not.be.empty
          expect(html, 'and the customer appears on the printed page').to.contain(doc.customerName)
        })
      })
    })
  })

  it('⭐ 3a. the document publishes the DERIVED status, not the stored one', () => {
    /*
     * THE DERIVED-STATUS TRAP, asserted at the half that is reachable from here.
     *
     * ⚠ AN EXPIRED QUOTE CANNOT BE SEEDED THROUGH THE API, and deliberately so. `create()` always server-sets
     * `validUntil = now + validityDays`, and `validityDays()` clamps a non-positive setting back to 30 (a
     * zero-day validity would expire every quote instantly). Both guards are correct, and neither should be
     * weakened to make a test reachable — that would be breaking the product to satisfy the gate.
     *
     * So the trap is covered in three places, each where it actually lives:
     *   - the DERIVATION      → QuoteEffectiveStatusTest (JUnit, runs on every `mvn test`)
     *   - the ENDPOINT        → this case: it publishes `effectiveStatus`, and it agrees with /getQuote
     *   - the RENDERER        → case 3b: it prints whatever status it is handed
     *
     * What this case rules out is a document endpoint that quietly serialises `status` instead — which would
     * look identical on every live quote and be wrong on exactly the ones that matter.
     */
    cy.visitDashboardSettled()
    raiseQuote().then((q) => {
      transition('sendQuote', q.id)

      fetchDocument(q.id).then((doc) => {
        expect(doc, 'the document carries an effectiveStatus field').to.have.property('effectiveStatus')

        cy.request({ url: `/getQuote?id=${q.id}` }).then((r) => {
          const stored = r.body.object
          // The document and the quote must give ONE answer to "what is this". They agree here because the
          // quote is live; the assertion is that the document reads the DERIVED field, which is the only one
          // that stays correct once it is not.
          expect(doc.effectiveStatus, 'the document agrees with the quote it was built from')
            .to.eq(stored.effectiveStatus)
        })
      })
    })
  })

  it('⭐ 3b. an EXPIRED quote prints EXPIRED, and is watermarked', () => {
    /*
     * The renderer half. Driven through the PRODUCTION shaping function with an expired document, so this
     * asserts what a real expired quote would print — the one state a customer must never be handed as a live
     * offer, because the price on it is one the shop will refuse to honour.
     *
     * The watermark is asserted separately from the status line on purpose: the header states the status for
     * someone reading the sheet, the watermark is what stops it being mistaken for current at arm's length.
     */
    cy.visitDashboardSettled()
    raiseQuote().then((q) => {
      fetchDocument(q.id).then((doc) => {
        // Same document, with the status a lapsed quote would carry. Everything else is real server data.
        const expired = Object.assign({}, doc, { effectiveStatus: 'EXPIRED' })

        renderDocument(expired).then((html) => {
          expect(html, 'the sheet declares it EXPIRED').to.contain('EXPIRED')
          // ⚠ The DIV, not the class name. `dc-wm` also appears in the stylesheet, which is emitted on
          // every document — asserting the bare string passed whether or not a watermark was drawn.
          expect(html, 'and carries the watermark that makes it unmissable')
            .to.contain('<div class="dc-wm">EXPIRED</div>')
        })
      })
    })
  })

  it('3c. a LIVE quote is NOT watermarked', () => {
    // The other side of 3b, and the guard against watermarking everything: a watermark that appears on a live
    // offer teaches people to ignore it, which is exactly how a DRAFT gets mistaken for firm.
    cy.visitDashboardSettled()
    raiseQuote().then((q) => {
      transition('sendQuote', q.id)
      fetchDocument(q.id).then((doc) => {
        expect(doc.effectiveStatus, 'this quote is live').to.eq('SENT')
        renderDocument(doc).then((html) => {
          // Same correction as 3b: the CSS rule is always present, so only the ELEMENT can prove a
          // live quote was left unmarked.
          expect(html, 'a live offer prints clean').to.not.contain('<div class="dc-wm">')
        })
      })
    })
  })

  it('⭐ 4. a quote prints at EVERY stage', () => {
    /*
     * The user's actual ask. A rep prints a DRAFT to check it, sends the SENT one, files the ACCEPTED one
     * against the customer's PO, and reprints the CONVERTED one when the invoice is queried months later.
     * Each hop is driven through the real endpoint, so a stage that cannot be reached fails here rather than
     * being quietly skipped.
     */
    cy.visitDashboardSettled()
    raiseQuote().then((q) => {
      const stages = ['DRAFT']

      fetchDocument(q.id).then((d) => expect(d.effectiveStatus).to.eq('DRAFT'))

      transition('sendQuote', q.id)
      fetchDocument(q.id).then((d) => {
        expect(d.effectiveStatus, 'prints while SENT').to.eq('SENT')
        stages.push('SENT')
      })

      transition('acceptQuote', q.id)
      fetchDocument(q.id).then((d) => {
        expect(d.effectiveStatus, 'prints while ACCEPTED').to.eq('ACCEPTED')
        stages.push('ACCEPTED')
      })

      transition('convertQuote', q.id)
      fetchDocument(q.id).then((d) => {
        expect(d.effectiveStatus, 'prints once CONVERTED').to.eq('CONVERTED')
        stages.push('CONVERTED')
      })

      cy.then(() => {
        expect(stages, 'every stage produced a document').to.have.length(4)
      })
    })
  })

  it('5. a CONVERTED quote shows the invoice it became', () => {
    // The sheet then explains itself — this was quoted, and it became that. Without it, a converted quote and
    // a live offer look identical on paper, which is how a customer ends up billed twice for one agreement.
    cy.visitDashboardSettled()
    raiseQuote().then((q) => {
      transition('sendQuote', q.id)
      transition('acceptQuote', q.id)
      transition('convertQuote', q.id)

      fetchDocument(q.id).then((doc) => {
        expect(doc.convertedInvoiceNo, 'the document carries the invoice number').to.not.be.empty
        renderDocument(doc).then((html) => {
          expect(html, 'and it is printed on the sheet').to.contain(doc.convertedInvoiceNo)
        })
      })
    })
  })

  it('6. the sheet totals EQUAL the quote totals', () => {
    /*
     * Asserted as a RELATIONSHIP, never as a predicted figure. Predicting money defeated three earlier gates
     * because FEFO allocation decides the real numbers — what matters is that the document does not invent
     * its own arithmetic, so it is compared against what the quote itself stored.
     */
    cy.visitDashboardSettled()
    raiseQuote().then((q) => {
      fetchDocument(q.id).then((doc) => {
        cy.request({ url: `/getQuote?id=${q.id}` }).then((r) => {
          const stored = r.body.object
          expect(Number(doc.grandTotal), 'the document total is the quote total')
            .to.eq(Number(stored.grandTotal))
          expect(Number(doc.subTotal), 'and so is the sub-total').to.eq(Number(stored.subTotal))
        })
      })
    })
  })

  it('⭐ 7. a booker cannot print ANOTHER user\'s quote', () => {
    /*
     * #27's scope, on the new route.
     *
     * Quote ids are sequential. An assemble endpoint that reads by id directly would reopen the exact IDOR
     * that #27 closed, in a route nobody would think to re-check — and a document endpoint is the worst place
     * to leave it open, because it returns the whole quote formatted for reading.
     */
    raiseQuote().then((q) => {
      cy.loginAsOrderBooker()
      cy.request({ url: `/quoteDocument?id=${q.id}`, failOnStatusCode: false }).then((r) => {
        expect(r.body.status, 'a foreign quote yields no document').to.not.eq('SUCCESS')
        expect(r.body.object, 'and no data comes back').to.not.exist
      })
    })
  })

  it('8. download produces a PDF  ⚠ depends on #25', () => {
    /*
     * ⚠ EXPECTED RED until task #25 is fixed — `LazyExport.ensurePdfMake()` currently fails, which is why
     * "Download PDF" does not work anywhere in the app. The case is written now because it states the
     * requirement; it is not skipped, because a skipped case is an invisible gap.
     */
    cy.visitDashboardSettled()
    raiseQuote().then((q) => {
      cy.window().then((w) => {
        expect(typeof w.downloadQuote, 'the download entry point is exposed').to.eq('function')
        return w.LazyExport.ensurePdfMake()
      }).then(() => {
        cy.window().then((w) => {
          expect(w.pdfMake, 'pdfMake loaded, so a quote can be emitted as PDF').to.exist
        })
      })
    })
  })

  it('⭐ 9. the Print and Download buttons are VISIBLE on the Quotes screen', () => {
    /*
     * REACHABILITY — nine "shipped but unreachable" defects are now tallied in SAAS-BUILD-STANDARDS.md, and
     * the `.pos-more` rule alone has swallowed two shipped controls (#sellSerial, #sellBonus). A feature the
     * user cannot click has not been delivered, whatever the API cases say.
     *
     * Asserted FROM THE SCREEN — not by calling the function, which is exactly how the previous nine passed.
     */
    cy.visitDashboardSettled()
    raiseQuote()

    cy.get('[onclick*="showQuotes"]').first().click({ force: true })
    cy.get('#QuoteDiv', { timeout: 20000 }).should('be.visible')

    cy.get('#QuoteDiv [onclick*="printQuote"]', { timeout: 20000 })
      .first().should('be.visible')
    cy.get('#QuoteDiv [onclick*="downloadQuote"]')
      .first().should('be.visible')
  })
})
