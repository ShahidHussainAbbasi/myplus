/**
 * BLK-5 — a stock correction is recorded ONCE, and says who, which shop and why.
 *
 * Design: microservices/docs/slices/blk-5-stock-adjust-guard.md (§5 lists every case and the defect it catches).
 * Written BEFORE the implementation: each case states a requirement, not a description of what was built.
 *
 * ── The defect ──────────────────────────────────────────────────────────────────────────────────
 * The Product grid's − and the stock-count sheet corrected stock with NO key (a retried save removed the stock
 * twice), a reason nobody gave (hard-coded "Manual stock correction"), and a record that named neither the person
 * (adjusted_by NULL on 34 of 34 rows) nor the shop (stock_adjustments had no organization_id at all).
 *
 * ── Who runs it ─────────────────────────────────────────────────────────────────────────────────
 * owner.business@ — the POS shop that corrects its shelves (GATE-RUNBOOK §1). admin./user. (the ladder) and
 * owner.pharma@ (a second tenant) act through the GATEWAY with Bearer tokens (cy.asOtherTenant), so this spec's own
 * monolith session survives maximumSessions(1).
 *
 * ⚠ Run SOLO:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/stock-adjust-guard.cy.js
 * Needs inventory-service (V11) AND the monolith rebuilt with BLK-5. Case 0 names the stale half.
 */

const GW = 'http://localhost:8765'
const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)
const key = (tag) => `blk5-${tag}-${uniq()}`

/** POST a correction through the monolith proxy the screens use. Returns the body; never throws on a refusal. */
const adjust = (body) =>
  cy.request({
    method: 'POST', url: '/adjustProductStock', headers: { 'Content-Type': 'application/json' },
    body, failOnStatusCode: false,
  }).then((r) => r.body)

/** On-hand, asserting the ENVELOPE first — a failed read must not look like a number. */
const onHand = (productId) =>
  cy.request(`/productStock?productId=${productId}`).then((r) => {
    expect(r.body && r.body.success, `productStock ${productId}: ${JSON.stringify(r.body).slice(0, 160)}`).to.eq(true)
    return Number(r.body.stock)
  })

/** The product's corrections in the owner's shop. A failed read must not look like "no rows". */
const history = (productId) =>
  cy.request(`/productStockAdjustments?productId=${productId}`).then((r) => {
    expect(r.body && r.body.success, `productStockAdjustments ${productId}: ${JSON.stringify(r.body).slice(0, 200)}`)
      .to.eq(true)
    expect(r.body.data, 'the history is a list').to.be.an('array')
    return r.body.data
  })

const seeded = (stock) => cy.seedProduct({ name: `BLK5 ${uniq()}`, stock })

/**
 * The Product screen, filtered to one seeded product, with its stock cell painted — and SETTLED.
 *
 * ⚠ Run 1 (2026-09-15): cases 8 and 9 failed "subject detached" typing into #addstk_. The grid lists NEWEST first
 * (catalog-products.js `order: [[0, 'desc']]`) and searches on the SERVER 400 ms after the last keystroke
 * (`searchDelay: 400`), so the freshly seeded product was already on the UNFILTERED first page with on-hand 10. Both
 * waits passed on that draw, and then the search answer redrew the body under the input being typed into — a race in
 * this helper, not in the product (an operator waits for the results before typing).
 *
 * So wait for the FILTERED draw — exactly one row, carrying this name — and for no read to be in flight.
 */
const openProduct = (name, productId) => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().then((w) => w.showProducts())          // function-navigated, NOT a #registrationType option (BLK-3)
  cy.get('#ProductDiv', { timeout: 15000 }).should('be.visible')
  cy.get('#tableProduct_filter input', { timeout: 15000 }).clear()
  cy.get('#tableProduct_filter input').type(name)
  cy.get('#tableProduct tbody tr', { timeout: 20000 })
    .should('have.length', 1)                        // the SEARCH answer, not the unfiltered newest-first page
    .and('contain.text', name)
  cy.get(`#stk_${productId}`, { timeout: 20000 }).should('have.text', '10')
  cy.waitForAppReady()                               // the stock fill has landed; nothing left to redraw the row
  cy.get(`#lessstkbtn_${productId}`).should('exist')
}

describe('BLK-5 — a stock correction is recorded once, and says who, which shop and why', () => {
  beforeEach(() => cy.loginAsOwner())

  it('0 — the served build is BLK-5, both halves', () => {
    // A stale monolith or inventory would make every case below fail for a reason unrelated to BLK-5.
    cy.request('/js/business/catalog-products.js').its('body').should('contain', "'stockAdjust:'")
    cy.request('/js/common/confirm-dialog.js').its('body').should('contain', 'data-ui-suggestion')
    cy.request('/js/common/submit-once.js').its('body').should('contain', 'retirePrefix')
    seeded(1).then(({ productId }) => history(productId))   // the new read answers through proxy + inventory
  })

  it('⭐⭐ 1 — one key sent twice moves the stock ONCE, and the second answer says it was a replay', () => {
    seeded(10).then(({ productId }) => {
      const k = key('twice')
      const body = { productId, adjustmentType: 'DECREASE', quantity: 3, reason: 'Damaged', idempotencyKey: k }
      adjust(body).then((a) => {
        expect(a.success, JSON.stringify(a)).to.eq(true)
        expect(a.replayed, 'the first answer is the real write').to.not.eq(true)
        expect(Number(a.stock), 'the write reports the on-hand it produced').to.eq(7)
      })
      adjust(body).then((a) => {
        expect(a.success, `a repeat is not an error: ${JSON.stringify(a)}`).to.eq(true)
        expect(a.replayed, '⭐ the repeat is a REPLAY — nothing moved again').to.eq(true)
      })
      onHand(productId).then((s) => expect(s, '⭐⭐ moved once: 10 − 3').to.eq(7))
      history(productId).then((rows) => {
        expect(rows.filter((x) => x.idempotencyKey === k), 'exactly one adjustment carries the key').to.have.length(1)
      })
    })
  })

  it('⭐⭐ 2 — six requests racing with ONE key record one adjustment and move the stock once', () => {
    /*
     * The case a pre-check alone cannot pass: all six ask "is this key used?" before any of them commits, so only the
     * unique index separates them. Fired through the app's own $.ajax (session + CSRF) with dedupe:false — without it
     * submit-once's layer 2 coalesces the racers into one request and this passes with the server fix DELETED
     * (DUP-1 trap 2). Polled with cy.window().should, never a cross-realm promise (DUP-1 trap 1).
     */
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    seeded(10).then(({ productId }) => {
      const k = key('race')
      cy.window().then((win) => {
        win.__blk5 = { done: 0, ok: 0, replayed: 0, bodies: [] }
        for (let i = 0; i < 6; i++) {
          win.$.ajax({
            type: 'POST', url: win.serverContext + 'adjustProductStock', contentType: 'application/json',
            dataType: 'json', dedupe: false, nonBlocking: true,
            data: JSON.stringify({ productId, adjustmentType: 'DECREASE', quantity: 2, reason: 'Counting error',
              idempotencyKey: k }),
          }).done((r) => {
            win.__blk5.bodies.push(r)
            if (r && r.success) win.__blk5.ok++
            if (r && r.replayed) win.__blk5.replayed++
          }).always(() => { win.__blk5.done++ })
        }
      })
      cy.window({ timeout: 30000 }).should((w) => expect(w.__blk5.done, 'all six answered').to.eq(6))
      cy.window().then((w) => {
        expect(w.__blk5.ok, `every racer is answered with success: ${JSON.stringify(w.__blk5.bodies).slice(0, 300)}`)
          .to.eq(6)
        expect(w.__blk5.replayed, 'five of them replay the one that did the work').to.eq(5)
      })
      onHand(productId).then((s) => expect(s, '⭐⭐ moved ONCE: 10 − 2').to.eq(8))
      history(productId).then((rows) => {
        expect(rows.filter((x) => x.idempotencyKey === k), 'one adjustment carries the key').to.have.length(1)
      })
    })
  })

  it('⭐ 3 — a correction with no reason is refused, and moves nothing', () => {
    seeded(10).then(({ productId }) => {
      ;[undefined, '', '    '].forEach((reason, i) => {
        const body = { productId, adjustmentType: 'DECREASE', quantity: 1, idempotencyKey: key(`noreason${i}`) }
        if (reason !== undefined) body.reason = reason
        adjust(body).then((a) => {
          expect(a.success, `no reason (${JSON.stringify(reason)}) must be refused: ${JSON.stringify(a)}`).to.eq(false)
          expect(String(a.message || ''), 'the refusal says what is missing').to.match(/reason/i)
        })
      })
      onHand(productId).then((s) => expect(s, 'nothing moved').to.eq(10))
      history(productId).then((rows) => expect(rows, 'no record was written').to.have.length(0))
    })
  })

  it('⭐ 4 — the same key with a DIFFERENT quantity is refused, never silently replayed', () => {
    seeded(10).then(({ productId }) => {
      const k = key('mismatch')
      adjust({ productId, adjustmentType: 'DECREASE', quantity: 3, reason: 'Damaged', idempotencyKey: k })
        .then((a) => expect(a.success, JSON.stringify(a)).to.eq(true))
      adjust({ productId, adjustmentType: 'DECREASE', quantity: 5, reason: 'Damaged', idempotencyKey: k })
        .then((a) => {
          expect(a.success, `a different correction under a used key: ${JSON.stringify(a)}`).to.eq(false)
          expect(String(a.message || ''), 'it says why').to.match(/already recorded/i)
        })
      onHand(productId).then((s) => expect(s, 'only the first moved: 10 − 3').to.eq(7))
    })
  })

  it('⭐⭐ 5 — REGRESSION: the record names WHO made it and WHICH SHOP, with the reason and key as sent', () => {
    seeded(10).then(({ productId }) => {
      const k = key('who')
      adjust({ productId, adjustmentType: 'DECREASE', quantity: 1, reason: '  Expired  ', idempotencyKey: k })
        .then((a) => expect(a.success, JSON.stringify(a)).to.eq(true))
      history(productId).then((rows) => {
        expect(rows).to.have.length(1)
        const r = rows[0]
        expect(r.adjustedBy, '⭐⭐ WHO — NULL on 34 of 34 rows before BLK-5').to.be.a('number')
        expect(r.organizationId, '⭐⭐ WHICH SHOP — the table had no such column').to.be.a('number')
        expect(r.reason, 'the reason, trimmed').to.eq('Expired')
        expect(r.idempotencyKey).to.eq(k)
        expect(r.adjustmentType).to.eq('DECREASE')
        expect(Number(r.quantity)).to.eq(1)
      })
    })
  })

  it('⭐⭐ 6 — the ladder: admin. and user. may correct the owner\'s stock, and each record names its own person', () => {
    /*
     * WHO MAY adjust is unchanged by BLK-5: /adjustProductStock is unmapped in PermissionInterceptor and inventory's
     * /adjust carries no permission — BLK-11's ruling. This pins today's answer (all three may) and that every row is
     * attributable. The body claims adjustedBy 999999 to prove the server ignores it.
     */
    seeded(10).then(({ productId }) => {
      adjust({ productId, adjustmentType: 'DECREASE', quantity: 1, reason: 'Damaged', idempotencyKey: key('owner') })
        .then((a) => expect(a.success, JSON.stringify(a)).to.eq(true))
      ;['admin.business@myplus.com', 'user.business@myplus.com'].forEach((email) => {
        cy.asOtherTenant((auth) => {
          cy.request({
            method: 'POST', url: `${GW}/api/inventory/stock/adjust`, failOnStatusCode: false,
            headers: Object.assign({ 'Content-Type': 'application/json' }, auth),
            body: { productId, adjustmentType: 'DECREASE', quantity: 1, reason: 'Counting error',
              idempotencyKey: key(email.split('.')[0]), adjustedBy: 999999 },
          }).then((r) => {
            expect(r.status, `${email} corrects the owner's product: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
            expect(r.body.data.adjustedBy, 'the body\'s adjustedBy is ignored — the token decides').to.not.eq(999999)
          })
        }, email)
      })
      onHand(productId).then((s) => expect(s, 'three corrections of one: 10 − 3').to.eq(7))
      history(productId).then((rows) => {
        expect(rows, 'the owner sees all three').to.have.length(3)
        expect(new Set(rows.map((r) => r.adjustedBy)).size, '⭐⭐ three different people').to.eq(3)
        expect(new Set(rows.map((r) => r.organizationId)).size, 'one shop').to.eq(1)
      })
    })
  })

  it('⭐⭐ 7 — another shop reusing the same key gets its OWN record, and cannot read or touch this one', () => {
    seeded(10).then(({ productId }) => {
      const k = key('shared')
      adjust({ productId, adjustmentType: 'DECREASE', quantity: 2, reason: 'Damaged', idempotencyKey: k })
        .then((a) => expect(a.success, JSON.stringify(a)).to.eq(true))

      cy.asOtherTenant((auth) => {
        const h = Object.assign({ 'Content-Type': 'application/json' }, auth)
        // The pharmacy's OWN product, created in its own catalog.
        cy.request({
          method: 'POST', url: `${GW}/api/catalog/products`, headers: h, failOnStatusCode: false,
          body: { name: `BLK5 Rx ${uniq()}`, sellingPrice: 50, idempotencyKey: key('rxprod') },
        }).then((p) => {
          expect(p.status, `the pharmacy's product: ${JSON.stringify(p.body).slice(0, 200)}`).to.eq(200)
          const rxId = p.body.data.id
          cy.request({
            method: 'POST', url: `${GW}/api/inventory/stock/adjust`, headers: h, failOnStatusCode: false,
            body: { productId: rxId, adjustmentType: 'INCREASE', quantity: 4, reason: 'Counting error', idempotencyKey: k },
          }).then((r) => {
            expect(r.status, `the pharmacy's own correction: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
            expect(r.body.data.replayed, '⭐⭐ the SAME key in another shop is a NEW record, not the business\'s row')
              .to.eq(false)
            expect(r.body.data.productId).to.eq(rxId)
          })
        })
        // It cannot READ the business product's corrections…
        cy.request({ url: `${GW}/api/inventory/stock/adjustments?productId=${productId}`, headers: auth,
          failOnStatusCode: false }).then((r) => {
          expect(r.status, JSON.stringify(r.body).slice(0, 200)).to.eq(200)
          expect(r.body.data, '⭐ another shop sees none of this product\'s corrections').to.have.length(0)
        })
        // …nor CORRECT the business product: catalog does not know it in the pharmacy's tenant.
        cy.request({
          method: 'POST', url: `${GW}/api/inventory/stock/adjust`, headers: h, failOnStatusCode: false,
          body: { productId, adjustmentType: 'INCREASE', quantity: 100, reason: 'Counting error', idempotencyKey: key('foreign') },
        }).then((r) => {
          expect(r.status, `a correction for another shop's product is refused: ${JSON.stringify(r.body).slice(0, 200)}`)
            .to.eq(400)
          expect(String(r.body.message)).to.match(/not found/i)
        })
      }, 'owner.pharma@myplus.com')

      onHand(productId).then((s) => expect(s, 'the business stock is untouched by the pharmacy: 10 − 2').to.eq(8))
      history(productId).then((rows) => expect(rows, 'only the business\'s own row').to.have.length(1))
    })
  })

  it('⭐⭐ 8 — the SCREEN asks for a reason, sends a key, and does not veil the page', () => {
    seeded(10).then(({ productId, name }) => {
      // Held open 800 ms, past the overlay's 220 ms show-delay: a veil raised by this write would be SEEN. A quick
      // answer would pass "no veil" on the old blocking code too.
      cy.intercept('POST', '**/adjustProductStock', (req) => {
        req.on('response', (res) => { res.setDelay(800) })
      }).as('adj')
      openProduct(name, productId)

      cy.get(`#addstk_${productId}`).clear()
      cy.get(`#addstk_${productId}`).type('2')          // re-queried: never type into a subject a draw may replace
      cy.get(`#lessstkbtn_${productId}`).click()

      // The dialog asks WHY, with common answers one click away.
      cy.get('#uiC-input', { timeout: 10000 }).should('exist')
      cy.get('.uiC-chip').should('have.length.at.least', 5)

      // An empty reason is refused IN the dialog — nothing is sent.
      cy.get('[data-ui-confirm="ok"]').click()
      cy.get('.uiC-err').invoke('text').should('not.be.empty')
      cy.get('@adj.all').should('have.length', 0)

      cy.get('.uiC-chip').first().invoke('text').then((chip) => {
        cy.get('.uiC-chip').first().click()
        cy.get('#uiC-input').should('have.value', chip)

        cy.window().then((w) => {
          w.__veilSeen = false
          w.__veilWatch = w.setInterval(() => {
            if (w.jQuery('#appAjaxOverlay').hasClass('show')) w.__veilSeen = true
          }, 20)
        })
        cy.get('[data-ui-confirm="ok"]').click()

        cy.wait('@adj').then((x) => {
          const b = typeof x.request.body === 'string' ? JSON.parse(x.request.body) : x.request.body
          expect(b.reason, 'the reason the operator chose').to.eq(chip)
          expect(b.idempotencyKey, '⭐⭐ the SCREEN sends a key').to.be.a('string').and.not.be.empty
          expect(Number(b.quantity)).to.eq(2)
          expect(x.response.body.success, JSON.stringify(x.response.body)).to.eq(true)
        })
      })

      cy.get(`#stk_${productId}`).should('have.text', '8')
      cy.window().then((w) => {
        w.clearInterval(w.__veilWatch)
        expect(w.__veilSeen, '⭐ the veil never covered the page — the row button carries the wait (BLK-2)').to.eq(false)
        expect(w.FormKeys.peek(`stockAdjust:${productId}`), 'the key rotated after success').to.eq(null)
      })
      history(productId).then((rows) => {
        expect(rows).to.have.length(1)
        expect(rows[0].adjustedBy, 'the screen\'s correction is attributable').to.be.a('number')
      })
    })
  })

  it('⭐ 9 — Cancel sends nothing and mints no key', () => {
    seeded(10).then(({ productId, name }) => {
      cy.intercept('POST', '**/adjustProductStock').as('adj')
      openProduct(name, productId)
      cy.get(`#addstk_${productId}`).clear()
      cy.get(`#addstk_${productId}`).type('3')          // re-queried: never type into a subject a draw may replace
      cy.get(`#lessstkbtn_${productId}`).click()
      cy.get('#uiC-input', { timeout: 10000 }).type('Damaged')
      cy.get('.uiC-cancel').click()
      cy.wait(500)
      cy.get('@adj.all').should('have.length', 0)
      cy.window().then((w) => expect(w.FormKeys.peek(`stockAdjust:${productId}`), 'no key minted').to.eq(null))
      onHand(productId).then((s) => expect(s, 'nothing moved').to.eq(10))
    })
  })

  it('⭐ 10 — the stock-count sheet applies with a key and a reason, and the record is attributable', () => {
    seeded(10).then(({ productId }) => {
      cy.intercept('POST', '**/adjustProductStock').as('adj')
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.window().should('have.property', 'showStockCount')
      cy.window().then((w) => w.showStockCount())
      cy.get('#StockCountDiv', { timeout: 15000 }).should('be.visible')
      cy.get(`#cntPacks_${productId}`, { timeout: 20000 }).clear().type('9')   // counted 9 against a system 10
      cy.get('#cntApply').should('not.be.disabled').click()
      cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).click()

      cy.wait('@adj').then((x) => {
        const b = typeof x.request.body === 'string' ? JSON.parse(x.request.body) : x.request.body
        expect(b.idempotencyKey, 'the sheet sends a key per row').to.be.a('string').and.not.be.empty
        expect(String(b.reason), 'the count is its reason').to.match(/\d{4}-\d{2}-\d{2}/)
      })
      onHand(productId).then((s) => expect(s, 'the shelf now holds what was counted').to.eq(9))
      history(productId).then((rows) => {
        expect(rows).to.have.length(1)
        expect(rows[0].adjustedBy, 'who').to.be.a('number')
        expect(rows[0].organizationId, 'which shop').to.be.a('number')
      })
    })
  })
})
