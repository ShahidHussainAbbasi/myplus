/**
 * BLK-4 — a product edit made against a stale copy is refused, never silently written.
 *
 * ── The defect ──────────────────────────────────────────────────────────────────────────────────
 * An operator opens a product. Meanwhile a purchase is received and re-prices it (PurchaseService →
 * PUT /products/{id}/price). The operator's Save then writes the OLD selling price back over the purchase's.
 * Two staff editing one product lose an edit the same way. Nothing errored; both screens looked right.
 *
 * ── What is pinned ──────────────────────────────────────────────────────────────────────────────
 * Cases 1–5 drive the SERVER contract through the monolith proxy the screen uses. Case 6 proves the SCREEN
 * sends the version: without it the server falls back to last-write-wins and cases 1–5 would all pass while
 * the real form stayed unprotected.
 *
 * ⚠ Run SOLO as owner.business@: the monolith is maximumSessions(1), and a second run logged in as the same
 * user expires this one mid-case.
 *   npx cypress run --headed --spec cypress/e2e/business/product-concurrent-edit.cy.js
 * Needs catalog-service AND the monolith rebuilt with BLK-4.
 */

const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)

const addProduct = (name, price) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    body: { name, sellingPrice: price, idempotencyKey: `blk4-${uniq()}` }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.body && r.body.success, `addProduct ${name}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    return r.body.data
  })

const update = (body) =>
  cy.request({
    method: 'POST', url: '/updateProduct', headers: { 'Content-Type': 'application/json' },
    body, failOnStatusCode: false,
  })

const read = (id) =>
  cy.request(`/getCatalogProduct?id=${id}`).then((r) => {
    expect(r.body && r.body.data, `getCatalogProduct ${id}: ${JSON.stringify(r.body).slice(0, 200)}`).to.be.an('object')
    return r.body.data
  })

describe('BLK-4 — concurrent edits on one product', () => {
  beforeEach(() => cy.loginAsOwner())

  it('⭐ 1 — a product carries a version, and an edit returns the MOVED one', () => {
    /*
     * The precondition, and the saveAndFlush trap: with a plain save() the version moves at commit, after the
     * response is built, so the caller would be handed the OLD version and its next save would be refused
     * against its own edit.
     */
    const run = uniq()
    addProduct(`BLK4 ${run}`, 100).then((p) => {
      expect(p.version, '⭐ a created product carries a version').to.be.a('number')
      update({ id: p.id, name: `BLK4 ${run} edited`, sellingPrice: 110, version: p.version }).then((r) => {
        expect(r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
        expect(r.body.data.version, 'the update\'s own response carries the moved version')
          .to.be.greaterThan(p.version)
      })
    })
  })

  it('⭐⭐ 2 — the SECOND editor is refused, and told what to do', () => {
    const run = uniq()
    addProduct(`BLK4 Race ${run}`, 100).then((p) => {
      update({ id: p.id, name: `BLK4 Race ${run} A`, sellingPrice: 120, version: p.version }).then((a) => {
        expect(a.body.success, 'the first save wins').to.eq(true)

        update({ id: p.id, name: `BLK4 Race ${run} B`, sellingPrice: 130, version: p.version }).then((b) => {
          expect(b.body.success, `the stale save is REFUSED: ${JSON.stringify(b.body).slice(0, 200)}`).to.eq(false)
          expect(String(b.body.message).toLowerCase(), 'and the message says what to do')
            .to.match(/reload|someone else|changed/)
        })
      })
    })
  })

  it('⭐ 3 — the refused save changed NOTHING', () => {
    const run = uniq()
    addProduct(`BLK4 Intact ${run}`, 100).then((p) => {
      update({ id: p.id, name: `BLK4 Intact ${run} WINNER`, sellingPrice: 120, version: p.version }).then(() => {
        update({ id: p.id, name: `BLK4 Intact ${run} LOSER`, sellingPrice: 130, version: p.version }).then((l) => {
          expect(l.body.success).to.eq(false)
          read(p.id).then((now) => {
            expect(now.name, '⭐ the winner\'s edit survived').to.contain('WINNER')
            expect(Number(now.sellingPrice), 'and the loser wrote nothing').to.eq(120)
          })
        })
      })
    })
  })

  it('⭐⭐ 4 — a PURCHASE re-pricing the product meanwhile: the stale form is refused, the purchase price stays', () => {
    /*
     * ⭐ The defect as a shop meets it, through the REAL writer: /addPurchase stamps the sell rate onto the product
     * via PUT /products/{id}/price. The form below holds the version from BEFORE the purchase.
     */
    const run = uniq()
    addProduct(`BLK4 Reprice ${run}`, 100).then((loaded) => {
      cy.request({
        method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
        body: {
          productId: loaded.id, quantity: 1,
          purchaseRate: 80, 'stock.bpurchaseRate': 80, 'stock.bsellRate': 150,
          totalAmount: 80, netAmount: 80, paidAmount: 80,
          purchaseInvoiceNo: `BLK4-${run}`,
        },
      }).then((pr) => {
        expect(String(pr.body && pr.body.status), `addPurchase: ${JSON.stringify(pr.body).slice(0, 200)}`).to.eq('SUCCESS')
      })

      read(loaded.id).then((afterPurchase) => {
        // ⚠ The precondition that stops this case passing for nothing: the purchase must actually have re-priced
        // the product and moved its version. The stamp is best-effort in PurchaseService, so check, don't assume.
        expect(Number(afterPurchase.sellingPrice), 'the purchase re-priced the product').to.eq(150)
        expect(afterPurchase.version, 'and moved its version').to.be.greaterThan(loaded.version)

        update({ id: loaded.id, name: loaded.name, sellingPrice: 100, version: loaded.version }).then((r) => {
          expect(r.body.success, `the form loaded before the purchase is REFUSED: ${JSON.stringify(r.body).slice(0, 200)}`)
            .to.eq(false)
        })
        read(loaded.id).then((now) => {
          expect(Number(now.sellingPrice), '⭐ the purchase\'s price survived').to.eq(150)
        })
      })
    })
  })

  it('⭐ 5 — an older client that sends NO version still saves', () => {
    // V62's rule: a cached tab from before the deploy keeps last-write-wins rather than being bricked.
    const run = uniq()
    addProduct(`BLK4 Legacy ${run}`, 100).then((p) => {
      update({ id: p.id, name: `BLK4 Legacy ${run} A`, sellingPrice: 110, version: p.version })
      update({ id: p.id, name: `BLK4 Legacy ${run} edited`, sellingPrice: 120 }).then((r) => {
        expect(r.body.success, `no version supplied must still save: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(true)
      })
    })
  })

  it('⭐⭐ 6 — the SCREEN sends the version, so a real edit is protected', () => {
    /*
     * Open the product in the real form, let ANOTHER editor save it, then press Save. If the form did not send
     * its version the server would fall back to last-write-wins, this save would SUCCEED, and the other
     * editor's name would be gone. So a refusal here can only mean the browser carried the version.
     */
    const run = uniq()
    addProduct(`BLK4 Screen ${run}`, 100).then((p) => {
      cy.intercept('POST', '**/updateProduct').as('screenSave')
      cy.visitDashboardSettled()
      cy.waitForAppReady()
      cy.window().then((w) => w.showProducts())
      cy.get('#ProductDiv').should('be.visible')
      cy.window().then((w) => w.editProduct(p.id))
      cy.get('#productId', { timeout: 15000 }).should('have.value', String(p.id))
      cy.get('#prodName').should('have.value', `BLK4 Screen ${run}`)

      // Another editor saves the same product while the form is open.
      read(p.id).then((fresh) => {
        update({ id: p.id, name: `BLK4 Screen ${run} OTHER`, sellingPrice: 105, version: fresh.version })
          .then((o) => expect(o.body.success, 'the other editor\'s save lands').to.eq(true))
      })

      cy.get('#prodName').clear().type(`BLK4 Screen ${run} STALE`, { delay: 0 })
      cy.get('#addProduct').click()

      cy.wait('@screenSave').then((x) => {
        const sent = typeof x.request.body === 'string' ? JSON.parse(x.request.body) : x.request.body
        expect(sent.version, '⭐ the form sent the version it loaded').to.be.a('number')
      })
      cy.get('#formErrorToast', { timeout: 10000 }).should('be.visible')
        .invoke('text').should('match', /reload|someone else|changed/i)
      cy.get('.crud-overlay.open', { timeout: 5000 }).should('exist')   // the form stays, typing intact
      cy.get('#prodName').should('have.value', `BLK4 Screen ${run} STALE`)

      read(p.id).then((now) => {
        expect(now.name, '⭐ the other editor\'s save was NOT overwritten').to.eq(`BLK4 Screen ${run} OTHER`)
      })
    })
  })

  it('⭐ 7 — the ladder: an ADMIN\'s save beats the owner\'s stale form, and a plain USER is held to the same rule', () => {
    /*
     * "Two staff editing one product" is usually two DIFFERENT people, not one owner in two tabs. admin. and user.
     * are members of the owner's org (GATE-RUNBOOK §4), so the product is visible to all three and the only thing
     * deciding the race is the lock. Seniority must not win it: the owner's stale copy loses to the admin's save.
     *
     * The other two tiers act through the GATEWAY with Bearer tokens (cy.asOtherTenant). maximumSessions(1) is per
     * principal, but a second monolith login would still have to replace this spec's session to switch identity,
     * and a token needs no session at all.
     *
     * ⚠ The UI half of the ladder is NOT asserted here, on purpose: today every tier gets the same Edit button,
     * because /updateProduct is unmapped in PermissionInterceptor (product.edit exists and governs nothing). Pinning
     * "the button differs by role" would fail against the current product, and pinning "it does not" would codify
     * the gap. Recorded in the slice doc §6, for a ruling.
     */
    const GW = 'http://localhost:8765'
    const run = uniq()
    const put = (auth, id, body) =>
      cy.request({
        method: 'PUT', url: `${GW}/api/catalog/products/${id}`,
        headers: Object.assign({ 'Content-Type': 'application/json' }, auth), body, failOnStatusCode: false,
      })

    addProduct(`BLK4 Ladder ${run}`, 100).then((p) => {
      // ADMIN: data populates at this tier (the owner's product, with its version), then saves first.
      cy.asOtherTenant((auth) => {
        cy.request({ url: `${GW}/api/catalog/products/${p.id}`, headers: auth, failOnStatusCode: false }).then((r) => {
          expect(r.status, `the admin can read the owner's product: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(200)
          expect(r.body.data.version, 'with the version the owner loaded').to.eq(p.version)
        })
        put(auth, p.id, { name: `BLK4 Ladder ${run} ADMIN`, sellingPrice: 120, version: p.version }).then((r) => {
          expect(r.status, `the admin's save lands: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(200)
        })
      }, 'admin.business@myplus.com')

      // OWNER: the form still holds the version from before the admin saved.
      update({ id: p.id, name: `BLK4 Ladder ${run} OWNER`, sellingPrice: 130, version: p.version }).then((r) => {
        expect(r.body.success, `the owner's stale save is refused — seniority does not win a race: ` +
          `${JSON.stringify(r.body).slice(0, 160)}`).to.eq(false)
      })

      // USER: the same stale copy, the same answer. It is a lock, not a privilege, and a refusal is a 409, not a 500.
      cy.asOtherTenant((auth) => {
        put(auth, p.id, { name: `BLK4 Ladder ${run} USER`, sellingPrice: 140, version: p.version }).then((r) => {
          expect(r.status, `a stale USER save is a 409 conflict: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(409)
        })
      }, 'user.business@myplus.com')

      read(p.id).then((now) => {
        expect(now.name, '⭐ the admin\'s edit survived both stale saves').to.eq(`BLK4 Ladder ${run} ADMIN`)
        expect(Number(now.sellingPrice), 'price included').to.eq(120)
      })
    })
  })
})
