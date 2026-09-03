/**
 * Task #15 — return documents: the CREDIT NOTE and the DEBIT NOTE.
 *
 * <h3>What this proves, and why each assertion is shaped the way it is</h3>
 *
 * <b>1. The enrichment, not the endpoint.</b> A {@code SaleReturn} row holds a {@code productId} but no
 * product name, and no customer at all — the customer lives on the original {@code Sell} → CustomerHistory.
 * So the assertion that matters is that the document comes back with a product NAME and a party NAME. An
 * endpoint that answered 200 with ids in those slots would be useless and would still pass a status check.
 *
 * <b>2. It must be REACHABLE.</b> There is no returns list screen, so the only place a note number surfaces
 * is the moment a return is taken. This codebase has shipped unreachable features four times (C1, C3, C6,
 * PERF-4), so the UI test asserts the operator is actually offered the document after taking a return.
 *
 * <b>3. Tenant isolation, asserted across two REAL tenants.</b> A note number is sequential, so
 * {@code CRN-000001} exists in almost every tenant. The scope predicate is the only thing stopping one shop
 * reading another's — proved by seeding as one tenant and asking as a different one.
 *
 * ⚠ Refusals arrive as HTTP 200 with an error ENVELOPE, so every negative case asserts {@code status}, never
 * the HTTP code. An HTTP-status assertion would pass against a live data leak.
 */

const OWNER = 'owner.business@myplus.com'
const OTHER_TENANT = 'owner.mobile@myplus.com'

/** Take a real return through the product's own endpoint and yield the credit-note number it allocates. */
function seedCreditNote() {
  return cy
    .request({ method: 'GET', url: '/getUserSell?q=-1' })
    .then((r) => {
      const rows = (r.body && r.body.collection) || []
      // SEED, never assert-or-skip: a gate that quietly passes on an empty shop is a gate that tests nothing.
      expect(rows.length, 'the tenant has at least one sale to return').to.be.greaterThan(0)

      // A line with quantity > 1 so returning 1 cannot exceed what was sold.
      const line = rows.find((s) => Number(s.quantity) > 1) || rows[0]
      return cy.request({
        method: 'POST',
        url: '/saleReturn',
        form: true,
        body: { sellId: line.sellId, quantity: 1, reason: 'cypress: return document gate' },
      })
    })
    .then((r) => {
      // GenericResponse: refusals are 200 + status ERROR/FAILED, so the envelope IS the assertion.
      expect(r.body.status, `saleReturn: ${r.body.message}`).to.eq('SUCCESS')
      expect(r.body.object, 'the server allocates a credit note number').to.be.a('string')
      return cy.wrap(r.body.object)
    })
}

describe('Return documents — credit note and debit note', () => {
  it('⭐ a credit note comes back ASSEMBLED, not as a row of ids', () => {
    cy.loginAsOwner(OWNER)

    seedCreditNote().then((noteNo) => {
      cy.request({ url: `/creditNote?no=${encodeURIComponent(noteNo)}` }).then((r) => {
        expect(r.body.status, r.body.message).to.eq('SUCCESS')
        const doc = r.body.object

        expect(doc.documentType, 'a credit note, not an invoice').to.eq('CREDIT_NOTE')
        expect(doc.documentNo, 'its OWN number').to.eq(noteNo)
        expect(doc.referenceNo, 'the invoice it reverses').to.be.a('string').and.not.be.empty

        // THE ENRICHMENT. These are the fields the row does not hold and cannot draw without.
        expect(doc.lines, 'lines[] — a list even though one note is one line today').to.have.length(1)
        expect(doc.lines[0].productName, 'the product NAME, resolved from the catalog — not an id')
          .to.be.a('string').and.not.be.empty

        // The FACE VALUE, which is creditAmount and never refundAmount (refundAmount is zero on a credit
        // sale, so a document valued from it would print 0.00 for a real return).
        expect(Number(doc.totalAmount), 'the note carries its face value').to.be.greaterThan(0)
      })
    })
  })

  it('⭐ another tenant cannot read this tenant\'s credit note', () => {
    /*
     * The scope predicate, proved rather than argued. Note numbers are sequential, so the number seeded below
     * very likely also exists in the other tenant's own series — which is exactly why the query filters on
     * org rather than relying on the key being hard to guess.
     */
    cy.loginAsOwner(OWNER)
    seedCreditNote().then((noteNo) => {
      cy.loginAsOwner(OTHER_TENANT)
      cy.request({ url: `/creditNote?no=${encodeURIComponent(noteNo)}`, failOnStatusCode: false })
        .then((r) => {
          // NOT the HTTP status — a refusal here is 200 with an envelope, so asserting the code would pass
          // against a leak.
          expect(r.body.status, 'another tenant must not read this note').to.not.eq('SUCCESS')
          expect(r.body.object, 'and must receive no document at all').to.not.exist
        })
    })
  })

  it('an unknown note number is refused, in the same shape', () => {
    cy.loginAsOwner(OWNER)
    cy.request({ url: '/creditNote?no=CRN-999999', failOnStatusCode: false }).then((r) => {
      expect(r.body.status).to.not.eq('SUCCESS')
      expect(r.body.object).to.not.exist
    })
    // A missing parameter must refuse too, rather than 500 on a null key.
    cy.request({ url: '/creditNote?no=', failOnStatusCode: false }).then((r) => {
      expect(r.body.status).to.not.eq('SUCCESS')
    })
  })

  it('⭐ the operator is OFFERED the document after taking a return', () => {
    /*
     * The reachability half. Endpoints that work but that nobody can reach is the failure this codebase has
     * shipped four times, and it is especially easy here: there is no returns list screen, so this prompt is
     * currently the ONLY route to a credit note.
     */
    cy.loginAsOwner(OWNER)
    cy.visitSaleScreen()

    cy.get('#tableSell tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)

    /*
     * The dialog opens from a PER-ROW Return button, not from clicking the row — a first version of this test
     * clicked the row and waited 15s for a dialog nothing had opened.
     *
     * Selected by the handler the app itself binds rather than by colour or label: `.btn-warning` also matches
     * the dialog's own Confirm button, and the text is translated in six locales. Filtering on the button ALSO
     * skips voided invoices for free — a voided row renders a VOID badge and no Return button at all, so
     * `.first()` here can only land on a returnable row.
     */
    /*
     * A row that can actually give back ONE unit.
     *
     * `.first()` was wrong: the grid's first returnable row is often a LOOSE sale — half a pack, sold as five
     * tablets — and returning 1 of 0.5 is correctly refused ("Cannot return more than the sold quantity
     * (0.5)"). The dialog then stays open with an error and no print offer, which read as a missing feature
     * when the product was right and the fixture was not.
     *
     * The button carries the sold quantity, so the row is chosen on that rather than on position.
     */
    cy.get('#tableSell tbody button[onclick*="openSaleReturn"]', { timeout: 30000 })
      .filter((i, el) => Number(Cypress.$(el).attr('data-qty')) >= 1)
      .first()
      .click()

    // The dialog is built on demand by openSaleReturn().
    cy.get('#saleReturnDialog', { timeout: 15000 }).should('be.visible')
    cy.get('#srQty').clear().type('1')
    cy.get('#srReason').type('cypress: reachability')
    cy.get('#srSubmit').click()

    /*
     * Scoped to the CONFIRM DIALOG (.uiC-card), not to any element containing the words.
     *
     * A bare `cy.contains(/credit note/i)` was wrong twice over. It yields the first match in DOM order, which
     * is the success toast — the server's own message is "Sale returned. Credit note CRN-000007" — and that
     * toast is transient, so the assertion failed on a working build. Worse, it could have PASSED on a broken
     * one: the toast carries those words whether or not any print route exists, so the test would not have
     * been testing reachability at all.
     *
     * The dialog title and its confirm button are the actual route to a printed document, so they are what is
     * asserted.
     */
    cy.get('.uiC-card', { timeout: 20000 }).should('be.visible')
    cy.get('.uiC-card .uiC-title').invoke('text').should('match', /credit note\s+CRN-/i)
    cy.get('.uiC-card button').contains(new RegExp('print', 'i')).should('be.visible')
  })

  it('the renderer knows both presets and binds their own labels', () => {
    /*
     * A credit note printed under an "Invoice #" heading is the confusion the note numbers exist to end, so
     * the LABEL fields are asserted, not just the presets' presence.
     */
    cy.loginAsOwner(OWNER)
    cy.visitSaleScreen()
    cy.window().then((w) => {
      const DR = w.DocumentRenderer
      expect(DR, 'the renderer is loaded').to.exist
      expect(DR.PRESETS.CREDIT_NOTE_A4, 'credit note preset').to.exist
      expect(DR.PRESETS.DEBIT_NOTE_A4, 'debit note preset').to.exist

      // Every key a preset binds must exist in the whitelist, or the renderer silently drops the field.
      const header = DR.FIELD_WHITELIST.header
      ;['creditNoteNo', 'debitNoteNo', 'referenceNo', 'returnReason', 'supplierName'].forEach((k) => {
        expect(header[k], `${k} is bound in FIELD_WHITELIST`).to.exist
      })

      expect(typeof w.printReturnDocument, 'the print entry point is exposed').to.eq('function')
    })
  })
  it('⭐ the credit note RENDERS its lines — not a blank page', () => {
    /*
     * THE DEFECT THIS EXISTS FOR, found by a user printing CRN-000054 and getting a document with no data.
     *
     * `toInvoiceShape()` handed the renderer a `lines` collection. `buildContext` reads `inv.sales`. So the
     * note printed with a correct header, correct totals and NOT ONE ROW — a blank document handed to a
     * customer as the record of what they returned.
     *
     * It shipped that way because this file only asserted `typeof printReturnDocument === 'function'`. The
     * endpoint was tested, the entry point was tested, and the PAGE was never looked at.
     *
     * Rendered through DocumentRenderer.buildHtml — the same function the printer calls — so this cannot pass
     * against a preview that differs from the paper.
     */
    cy.loginAsOwner(OWNER)
    cy.visitSaleScreen()

    seedCreditNote().then((noteNo) => {
      cy.request({ url: `/creditNote?no=${encodeURIComponent(noteNo)}` }).then((r) => {
        expect(r.body.status, r.body.message).to.eq('SUCCESS')
        const doc = r.body.object

        cy.window().then((w) => {
          const DR = w.DocumentRenderer
          expect(DR, 'the renderer is loaded').to.exist
          expect(DR.PRESETS.CREDIT_NOTE_A4, 'the credit note preset').to.exist

          /*
           * ⚠ RENDERED THROUGH PRODUCTION'S OWN SHAPING, not a copy of it.
           *
           * This block used to rebuild the `doc → invoice shape` mapping here, inside the test. That is a
           * gate that cannot fail: when production's mapping was wrong, the test's own correct mapping still
           * rendered a perfect page. It is why this case was green on the day CRN-000054 printed blank, and
           * green again while every credit note printed with no customer name.
           *
           * `buildReturnDocumentHtml` is the function the Print button calls. Assert on that, and a mapping
           * mistake fails here instead of on a customer's desk.
           */
          expect(typeof w.buildReturnDocumentHtml, 'production exposes the shared shaping').to.eq('function')
          const html = w.buildReturnDocumentHtml(doc, false)

          // The number, so we know we rendered THIS note.
          expect(html, 'the note carries its own number').to.contain(doc.documentNo)

          // THE ASSERTION THAT WAS MISSING: the product name from the line must appear on the page. A blank
          // document still contains the header and the totals — only a row proves the lines rendered.
          const name = (doc.lines && doc.lines[0] && doc.lines[0].productName) || ''
          expect(name, 'the note has a line with a product name').to.not.be.empty
          expect(html, 'the product line appears on the printed page').to.contain(name)

          /*
           * ⭐ AND THE CUSTOMER — a SEPARATE failure mode, and one that was live on every credit note this
           * app has ever printed.
           *
           * `toInvoiceShape` set a flat `customerName`. `buildContext` does `var cust = inv.customer || {}`
           * and the resolver reads `c.cust.name`, so the flat property was silently dropped and the note
           * printed with an empty party block. The debit note's supplier was fine — `supplierName` resolves
           * flat off `c.inv` — which is exactly why nobody noticed: the two documents are asymmetric.
           *
           * Found in #28 when the quote document reproduced the identical empty block. Same root as the
           * `lines`/`sales` defect this case already guards: the renderer's contract is a SHAPE, and a
           * property in the wrong place fails silently instead of loudly.
           *
           * ⚠ This case previously asserted the product line and never the party, which is the only reason
           * it stayed green while a customer-facing document went out unnamed.
           */
          expect(doc.partyName, 'the note names a party').to.not.be.empty
          expect(html, 'the customer appears on the printed credit note').to.contain(doc.partyName)
        })
      })
    })
  })

})
