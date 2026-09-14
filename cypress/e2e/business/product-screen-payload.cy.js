/**
 * PS-1 — the Product screen loads only what is on screen.
 *
 * ── What this exists to catch ───────────────────────────────────────────────────────────────────
 * Opening this screen fetched the WHOLE catalogue (/getUserProduct, 384 KB measured) and EVERY product's
 * stock levels (/productStockLevels, 69.5 KB) to render a 50-row grid needing 19.3 KB.
 *
 * The payload was the smaller half. That catalogue fetch was capped at `size=1000` and **the cap was
 * silent**: on a 1,632-product tenant the newest 1,000 came back and 632 products (39%) were invisible to
 * the duplicate-SKU check, the "already registered" panel, the manufacturer dropdown and the count badge.
 * A taken SKU was reported free. The badge showed "1000 registered" to a tenant with 1,632.
 *
 * ⚠ The screen already had a guard for this shape and it could not see it: `renderExisting()` refuses to
 * render an empty list as "nothing is registered", because that is the opposite of the truth. Truncation
 * produced exactly that false reassurance — the guard told a FAILED fetch from a successful one, and this
 * fetch SUCCEEDED. A partial success is neither.
 *
 * ⚠ So cases 3 and 4 are the ones that matter. A gate that only counted bytes would have gone green on the
 * version that lied.
 */

describe('PS-1 — the Product screen loads only what is on screen', () => {
  beforeEach(() => cy.loginAsOwner())

  /**
   * Open the dashboard, let it settle, THEN start counting.
   *
   * ⚠ The dashboard itself calls /getUserProduct (business.js bgJson) — that is a different screen's
   * business and out of scope here. Counting from page load would fail on traffic this slice never
   * touched, so the counters are armed only once the dashboard is quiet and reset immediately before the
   * Product screen opens. What is asserted is what THIS screen asks for.
   */
  const openProductScreen = (counters) => {
    cy.visitDashboardSettled()
    cy.waitForAppReady()
    cy.then(() => { counters.legacy = 0; counters.unscopedLevels = 0; counters.scopedLevels = [] })
    cy.window().then((w) => w.showProducts())
    cy.get('#ProductDiv').should('be.visible')
    cy.get('#tableProduct tbody tr:first td', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.waitForAppReady()
  }

  const armCounters = (counters) => {
    cy.intercept('GET', '**/getUserProduct*', (req) => { counters.legacy++; req.continue() })
    cy.intercept('GET', '**/productStockLevels*', (req) => {
      const ids = new URL(req.url).searchParams.get('ids')
      if (ids) counters.scopedLevels.push(ids.split(',').length)
      else counters.unscopedLevels++
      req.continue()
    })
  }

  it('⭐ 1 — opening the screen fetches NO catalogue and NO unscoped stock', () => {
    const c = { legacy: 0, unscopedLevels: 0, scopedLevels: [] }
    armCounters(c)
    openProductScreen(c)

    cy.then(() => {
      expect(c.legacy, '/getUserProduct — the 384 KB whole-catalogue fetch').to.eq(0)
      expect(c.unscopedLevels, '/productStockLevels with no ids — the 69.5 KB whole-tenant fetch').to.eq(0)
    })
  })

  it('⭐ 2 — stock is asked for BY ID, for exactly the rows drawn', () => {
    const c = { legacy: 0, unscopedLevels: 0, scopedLevels: [] }
    armCounters(c)
    openProductScreen(c)

    cy.get('#tableProduct tbody tr').its('length').then((drawn) => {
      expect(c.scopedLevels.length, 'the stock call was made, and carried ids').to.be.greaterThan(0)
      const asked = c.scopedLevels.reduce((a, b) => a + b, 0)
      // Chunked at 100, so a 50-row page is one request and an "All" page several — either way the ids
      // asked for must add up to exactly the rows on screen, never the catalogue.
      expect(asked, `ids asked for (${c.scopedLevels.join('+')}) vs rows drawn`).to.eq(drawn)
      c.scopedLevels.forEach((n) => expect(n, 'no chunk exceeds the 100 ceiling').to.be.at.most(100))
    })
  })

  it('⭐⭐ 3 — the "N registered" badge counts the TENANT, not the fetch', () => {
    /*
     * ⭐ THE DEFECT THAT WAS LIVE ON SCREEN. The badge rendered the length of the client's catalogue array,
     * which the 1,000-row cap made simply wrong: a tenant with 1,632 products was shown "1000 registered".
     *
     * ⚠ Asserting "not 1000" alone would be a gate that passes by luck on a tenant with 999 products. It is
     * pinned against the server's own count, and the case is skipped unless the tenant is big enough for
     * the cap to bite — existence is not eligibility.
     */
    cy.request('/productCount').then((r) => {
      const total = Number(r.body.count)
      expect(r.body.success, 'productCount answered').to.eq(true)

      if (total <= 1000) {
        // Not a pass — say so loudly rather than going green having tested nothing.
        throw new Error(
          `This gate needs a tenant with MORE than 1000 products to prove the cap is gone; this one has ` +
          `${total}. Seed more, or run it as a tenant that has them.`)
      }

      const c = { legacy: 0, unscopedLevels: 0, scopedLevels: [] }
      armCounters(c)
      openProductScreen(c)
      cy.window().then((w) => w.newProduct())
      cy.get('#ProductModal').should('have.class', 'open')

      cy.get('#prodExistingCount', { timeout: 15000 })
        .should(($el) => {
          const text = $el.text()
          expect(text, 'the badge says something').to.not.eq('')
          const n = Number(String(text).replace(/[^\d]/g, ''))
          expect(n, `⭐ the badge (${text}) must equal the tenant's real count`).to.eq(total)
          expect(n, 'and must NOT be the old 1000-row cap').to.not.eq(1000)
        })
    })
  })

  it('⭐⭐ 4 — a SKU on one of the OLDEST products is reported as taken', () => {
    /*
     * ⭐ THE CASE THE WHOLE SLICE EXISTS FOR.
     *
     * The old index held the NEWEST 1,000 products (`sort=id,desc`), so the oldest were the ones missing.
     * Their SKUs were reported FREE and the operator learned otherwise on submit. The oldest product is
     * therefore the exact probe: if this passes, the index is genuinely gone rather than merely resized.
     *
     * ⚠ Take the SKU from the API, never from a screenshot or a truncated log line. My own first check of
     * this fix clipped the SKU to 16 characters for display and then tested the clipped value — it returned
     * "not found" and looked like the fix had failed, when the probe was what was broken.
     */
    cy.request('/getProductPage?page=0&size=5&sort=id,asc&includeInactive=true').then((r) => {
      const oldest = (r.body.collection || []).find((p) => p.sku && String(p.sku).trim())
      expect(oldest, 'an oldest product carrying a SKU to probe with').to.be.an('object')
      const sku = String(oldest.sku)

      // The server half: it can see a product the old client index could not.
      cy.request(`/productSkuCheck?sku=${encodeURIComponent(sku)}`).then((chk) => {
        expect(chk.body.exists, `⭐ "${sku}" (product ${oldest.id}) must be reported TAKEN`).to.eq(true)
        expect(String(chk.body.id), 'and name the product that owns it').to.eq(String(oldest.id))
      })

      // Editing that product must NOT flag its own SKU as a duplicate of itself.
      cy.request(`/productSkuCheck?sku=${encodeURIComponent(sku)}&excludeId=${oldest.id}`)
        .then((own) => expect(own.body.exists, 'keeping your own SKU is not a duplicate').to.eq(false))

      // The SCREEN half: the form actually asks, and shows the answer. Without this the endpoint could be
      // perfect while nothing on the form called it.
      const c = { legacy: 0, unscopedLevels: 0, scopedLevels: [] }
      armCounters(c)
      openProductScreen(c)

      /*
       * ⭐ WAIT FOR THE MODAL'S OWN REQUESTS BY NAME — not for the app to go globally quiet.
       *
       * Settling the SCREEN is not settling the MODAL: newProduct() fires three fresh requests
       * (productCount, manufacturers, and the debounced panel search) and animates in while they land, so
       * cy.clear() was refused with "could not determine the actionability of this element".
       *
       * ⚠ The obvious repair — another waitForAppReady() here — was WRONG, and its own diagnostic said so:
       * it ran the full 30s reporting one request started and none in flight. Global quiet is a blunt
       * proxy for what this case actually needs, and it is only as reliable as every other thing on the
       * page. The modal is ready when ITS requests have answered, which is a statement about this test.
       *
       * Wait on the exact three, then settle the field. Specific beats global — and it fails with a name
       * when it fails at all.
       */
      cy.intercept('GET', '**/productCount*').as('psCount')
      cy.intercept('GET', '**/manufacturers*').as('psMakers')
      cy.intercept('GET', '**/getProductPage*').as('psPanel')

      cy.window().then((w) => w.newProduct())
      cy.get('#ProductModal').should('have.class', 'open')
      cy.wait('@psCount', { timeout: 20000 })
      cy.wait('@psMakers', { timeout: 20000 })
      cy.wait('@psPanel', { timeout: 20000 })   // debounced 200ms after the panel is asked to refresh

      cy.get('#prodSku').should('be.visible')
      cy.settled('#prodSku')
      // NOT {force:true}. If the field is still unreachable after waiting for the modal's own three
      // requests and for it to stop moving, that is a real defect in the screen and this case should
      // say so — forcing would type into something an operator cannot reach and call it a pass.
      cy.get('#prodSku').clear().type(sku).blur()
      cy.get('#prodSku', { timeout: 15000 }).should('have.class', 'alert-danger')
    })
  })

  it('⭐ 5 — a stock request that FAILS shows "—", never "0"', () => {
    /*
     * ⭐ "Absent" stopped meaning "no stock row" the moment we started asking for specific ids.
     *
     * While this fetched the whole tenant, a product missing from the response genuinely had no stock. Now
     * a failed request would paint 0 across rows whose inventory is real — and 0 reads as OUT OF STOCK, a
     * number the shop acts on. An unknown must look unknown.
     *
     * The placeholder before any answer is "…" and the failure state is "—"; neither may be "0".
     */
    cy.intercept('GET', '**/productStockLevels*', { statusCode: 500, body: {} }).as('levelsDown')

    cy.visitDashboardSettled()
    cy.window().then((w) => w.showProducts())
    cy.get('#ProductDiv').should('be.visible')
    cy.get('#tableProduct tbody tr:first td', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.wait('@levelsDown')

    cy.get('#tableProduct tbody tr .prod-onhand', { timeout: 15000 })
      .should('have.length.greaterThan', 0)
      .each(($cell) => {
        const v = $cell.text().trim()
        expect(v, '⭐ an unanswered stock request must never render 0').to.not.eq('0')
        expect(['—', '…'], `unknown stock shows "—" or "…", got "${v}"`).to.include(v)
      })
  })
})
