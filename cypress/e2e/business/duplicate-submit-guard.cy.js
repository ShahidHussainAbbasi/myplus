/**
 * DUP-1 — duplicate-submit guard. Run headed.
 *
 * THE DEFECT THIS PINS: a production shop registered **148 products from a single submit**. Holding Enter
 * auto-repeats at ~30/s; `keyboard-forms.js` turns Enter-past-the-last-field into a click on the save button;
 * `saveProduct()` had no in-flight guard and the modal stays open for the whole round trip (closeModal runs in
 * the success callback); and the server accepted every one, because a product name is deliberately not unique
 * and a blank SKU skips the only duplicate check.
 *
 * Three layers, asserted separately ON PURPOSE. Any one of them alone leaves a hole, and a spec that only
 * proved "one product exists at the end" would pass with two of the three deleted:
 *
 *   A  the EVENT    auto-repeat never becomes a click            (and ordinary typing still works)
 *   B  the CLIENT   identical in-flight writes coalesce to one POST, with no false error
 *   C  the SERVER   one idempotency key = one product, including when the requests RACE
 *   D  end to end   the real form, and the key rotation that stops this fix becoming a worse bug
 *
 * ⚠ Case C-2 is the one that actually fixes the reported incident. All 148 of those requests were in flight
 * at once, so the service's pre-check found nothing for any of them — only the V16 unique index can separate
 * concurrent twins. It fires through the app's own transport with layer B's documented `dedupe:false` opt-out,
 * so the eight requests really do race instead of being coalesced into one.
 */
const uniq = () => `${Date.now()}_${Math.floor(Math.random() * 1e4)}`

/**
 * Products in this tenant whose name starts with `prefix` — the count this whole spec is about.
 *
 * ⚠ IT ASSERTS THE READ BEFORE COUNTING. This endpoint goes monolith → api-gateway → catalog-service, and a
 * gateway 503 (catalog restarted, Eureka not yet reconverged — which happened on the first run of this spec)
 * returns an envelope with no `collection` at all. Silently treating that as zero rows would report
 * "expected 0 to equal 1" and send the reader hunting through the guard for a defect that is not there.
 */
const countNamed = (prefix) =>
  cy.request({ url: '/getUserProduct?includeInactive=true', failOnStatusCode: false }).then((r) => {
    expect(r.status, 'the product list read itself succeeded').to.eq(200)
    expect(r.body && r.body.collection, `product list returned rows (body: ${JSON.stringify(r.body).slice(0, 160)})`)
      .to.be.an('array')
    return r.body.collection.filter((p) => String(p.name || '').indexOf(prefix) === 0).length
  })

/** Dispatch one keydown and report whether a guard cancelled it. */
/**
 * Open the product form and WAIT UNTIL IT HAS STOPPED MOVING.
 *
 * ⚠ The four-step sequence is not ceremony, and skipping it is what failed D-1/D-2/D-3 on the first run
 * ("cy.clear() could not be issued because this element is currently animating"). `catalog-product.cy.js`
 * documents why: the overlay hides when the last request completes, and that SAME ajaxComplete is when
 * searchable-selects.js rebuilds every picker in the modal — bootstrap-select swaps each <select> for a
 * taller button, the content grows, and a vertically-centred modal slides. So the field drifts AFTER the
 * page already looks ready. Quiet is not still.
 *
 * ⚠ And NOT {force:true}, NOT {waitForAnimations:false}. Both type into a field a real operator cannot hit
 * yet, so a modal that genuinely jitters under the cursor would pass this gate and fail every day in the shop.
 */
const openProductForm = () => {
  cy.window().then((w) => w.showProducts())
  cy.get('#ProductDiv').should('be.visible')
  cy.window().then((w) => w.newProduct())
  cy.get('#ProductModal').should('have.class', 'open')
  settleProductForm()
}

/**
 * Wait until the form can actually be typed into: no request in flight, no overlay on top, nothing moving.
 *
 * ⚠ ORDER IS LOAD-BEARING — waitForAppReady FIRST, and that was my second mistake on this spec.
 *
 * `should('be.visible')` is a POSITION check, and Cypress fails it when something covers the element. The
 * product screen's own load is still finishing when the modal opens, so #appAjaxOverlay is on top and
 * "visible" fails with "it's being covered by another element" — which reads like a missing field rather
 * than a timing problem. waitForAppReady is the helper that waits out BOTH the overlay and its box plus
 * 300 ms of quiet, so it has to come before any assertion about what can be seen or reached.
 *
 * It surfaced only in the LAST case of the file: each earlier case adds products, so D-3's list load is the
 * slowest of the run. A helper whose correctness depends on how much test data happens to exist is not
 * correct yet — hence the fixed order rather than a longer timeout.
 */
const settleProductForm = () => {
  cy.waitForAppReady()
  cy.get('#prodName').should('be.visible')
  cy.settled('#prodName')
}

const keydown = (win, el, key, repeat) => {
  const ev = new win.KeyboardEvent('keydown', { key, repeat, bubbles: true, cancelable: true })
  el.dispatchEvent(ev)
  return ev.defaultPrevented
}

describe('DUP-1 — one submit per intent', () => {
  beforeEach(() => {
    // testIsolation: every test re-logs in, because the cy.requests below are authenticated.
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showProducts')
  })

  // ── A · the event layer ─────────────────────────────────────────────────────────────────────────

  it('⭐ A-1 — auto-repeat Enter on a submit button is dropped; the first press is not', () => {
    /*
     * THE EXACT MECHANISM OF THE 148.
     *
     * Note what this case must use: the BUTTON. When focus sits on the submit control, `enter-chain.js` never
     * sees the key — the button's id is not in its chain, so it returns early and the BROWSER fires the
     * repeated `click` itself. A guard added only to enter-chain would pass a spec that pressed Enter in a
     * FIELD and still let this through, which is why the suppressor lives on a window-capture listener.
     */
    cy.window().then((w) => {
      const btn = w.document.getElementById('addProduct')
      expect(btn, 'the product save button is in the DOM').to.exist

      expect(keydown(w, btn, 'Enter', false), 'a FIRST press must still work — this is the operator').to.eq(false)
      expect(keydown(w, btn, 'Enter', true), 'a REPEAT press is the keyboard, not the operator').to.eq(true)
      expect(keydown(w, btn, ' ', true), 'and a held Space activates a button just as Enter does').to.eq(true)
    })
  })

  it('⭐ A-2 — the guard does not touch ordinary typing', () => {
    /*
     * The other half, and the half that makes A-1 safe to ship. A suppressor that swallowed every repeat would
     * stop an operator holding Space in a description box or Enter in a textarea — a worse bug than the one
     * being fixed, and one nobody would connect to a "duplicate submit" change weeks later.
     */
    cy.window().then((w) => {
      const name = w.document.getElementById('prodName')
      const desc = w.document.getElementById('prodDesc')
      expect(name, '#prodName exists').to.exist

      expect(keydown(w, name, ' ', true), 'a held Space in a text field is TYPING').to.eq(false)
      if (desc && desc.tagName === 'TEXTAREA') {
        expect(keydown(w, desc, 'Enter', true), 'a textarea keeps its Enter — that is what it is for').to.eq(false)
      }
      expect(keydown(w, name, 'a', true), 'and no other key is affected at all').to.eq(false)
    })
  })

  // ── B · the client transport layer ──────────────────────────────────────────────────────────────

  it('⭐ B-1 — two identical writes in one tick cost ONE request, and both callers see success', () => {
    /*
     * Coalescing, which is what covers a genuine double-click (no `repeat` flag on those — two real presses).
     *
     * ⚠ THE SECOND ASSERTION IS THE WHOLE REASON THIS IS A DECORATOR AND NOT AN `$.ajaxPrefilter`. A prefilter
     * can only cancel by abort(), and an abort runs the call site's `error` handler — so the operator would be
     * shown "Could not save the product." immediately after a save that SUCCEEDED. There are 66 direct $.ajax
     * POST sites in this codebase and not one of them can be told that a failure is fake. So: the duplicate
     * must see SUCCESS, and must never see an error.
     */
    const prefix = `DupB_${uniq()}`
    cy.intercept('POST', '**/addProduct').as('save')

    // The tally is kept ON THE PAGE, not in a closure: the counters are incremented by the app's own callbacks,
    // and reading them back through cy.window() is what proves the CALLERS were notified rather than merely
    // that one row appeared.
    cy.window().then((w) => {
      w.__dupSeen = { ok: 0, err: 0 }
      const opts = {
        type: 'POST', url: '/addProduct', contentType: 'application/json', dataType: 'json',
        data: JSON.stringify({ name: prefix, sellingPrice: 5, unit: 'pcs' }),
      }
      // ⚠ w.$ — the APPLICATION's jQuery. A bare `$` here is the spec runner's own copy, which carries none of
      // the decoration under test, so the case would bypass the very thing it is asserting.
      const before = w.SubmitOnce.coalescedCount()
      w.__dupBefore = before
      // Fired in the SAME tick, so the second lands while the first is still on the wire.
      w.$.ajax(w.$.extend({}, opts, { success: () => w.__dupSeen.ok++, error: () => w.__dupSeen.err++ }))
      w.$.ajax(w.$.extend({}, opts, { success: () => w.__dupSeen.ok++, error: () => w.__dupSeen.err++ }))
    })

    cy.wait('@save')
    cy.wait(600)            // let the shared promise notify both callers

    cy.window().then((w) => {
      expect(w.__dupSeen.ok, 'BOTH callers were told the save succeeded').to.eq(2)
      expect(w.__dupSeen.err, 'and NEITHER was shown a false error').to.eq(0)
      expect(w.SubmitOnce.coalescedCount() - w.__dupBefore, 'exactly one duplicate was dropped').to.eq(1)
      expect(w.SubmitOnce.inFlightCount(), 'and nothing is left registered once it settles').to.eq(0)
    })

    // Exactly one request reached the server, and exactly one product exists.
    cy.get('@save.all').should('have.length', 1)
    countNamed(prefix).should('eq', 1)
  })

  it('B-2 — a GET is never de-duplicated', () => {
    /*
     * The boundary on the other side. Coalescing identical READS would be a cache, not a guard — and a wrong
     * one: two screens asking the same question at the same moment must each get their own answer, because one
     * of them may be about to render into a DOM the other has replaced. Only writes are at risk of being
     * duplicated, so only writes are touched.
     */
    cy.intercept('GET', '**/getUserProduct*').as('read')
    cy.window().then((w) => {
      w.$.ajax({ type: 'GET', url: '/getUserProduct', dataType: 'json' })
      w.$.ajax({ type: 'GET', url: '/getUserProduct', dataType: 'json' })
    })
    cy.wait('@read')
    cy.wait('@read')        // the second must arrive too — a single one would time out here
  })

  // ── C · the server layer ────────────────────────────────────────────────────────────────────────

  it('⭐ C-1 — the same key posted twice SEQUENTIALLY yields one product (fast path)', () => {
    const prefix = `DupC1_${uniq()}`
    const key = `k-${uniq()}`
    const body = { name: prefix, sellingPrice: 7, unit: 'pcs', idempotencyKey: key }
    let firstId = null

    cy.request({ method: 'POST', url: '/addProduct', body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        firstId = r.body.data.id
      })

    // A retry of the same form-fill — the shape of a user pressing save again after a timeout.
    cy.then(() => cy.request({ method: 'POST', url: '/addProduct', body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.success, 'the repeat is answered as success, not as an error').to.eq(true)
        expect(r.body.data.id, 'and it is the SAME product, not a second one').to.eq(firstId)
      }))

    countNamed(prefix).should('eq', 1)
  })

  it('⭐⭐ C-2 — eight CONCURRENT posts of one key yield one product (the race path)', () => {
    /*
     * ⭐ THE CASE THAT PINS THE REPORTED DEFECT. The 148 products were created by requests that were ALL IN
     * FLIGHT AT ONCE: every pre-check ran before any of them had committed, so every pre-check found nothing.
     * Only the V16 unique index can arbitrate, with the loser's replay in ProductController.create.
     *
     * Driven with `fetch` rather than jQuery DELIBERATELY: jQuery would be coalesced by layer B, and this case
     * would then pass with layer C entirely absent — a gate going green while testing nothing.
     *
     * Also note it asserts the ANSWERS, not just the row count: eight callers must each be handed the same
     * product id. A server that answered "failed" to seven of them would leave one row and still be wrong,
     * because the operator's screen would fill with errors after a save that worked.
     */
    const prefix = `DupC2_${uniq()}`
    const key = `k-${uniq()}`

    cy.window().then((w) => {
      w.__c2 = { ids: [], failures: [] }
      const opts = {
        type: 'POST', url: '/addProduct', contentType: 'application/json', dataType: 'json',
        data: JSON.stringify({ name: prefix, sellingPrice: 11, unit: 'pcs', idempotencyKey: key }),
        /*
         * ⚠ dedupe:false — WITHOUT THIS THE CASE PROVES NOTHING. Layer B would coalesce all eight into a
         * single POST, one product would exist, the case would pass, and it would pass just as happily with
         * layer C deleted entirely. This is the opt-out layer B documents, used here for the one purpose it
         * exists for: letting identical writes genuinely race.
         */
        dedupe: false,
        success: (r) => w.__c2.ids.push(r && r.data && r.data.id),
        error: (x) => w.__c2.failures.push(x && x.status),
      }
      for (let i = 0; i < 8; i++) w.$.ajax(w.$.extend({}, opts))
    })

    /*
     * A RETRYING wait, not a promise the runner has to resolve. The first version of this case returned
     * `w.Promise.all([...w.fetch...])` to cy.then() and timed out at 5 s — during a run where the api-gateway
     * was answering 503 because catalog-service had just restarted and Eureka had not reconverged. Measured
     * afterwards against a settled stack: eight concurrent creates answer in 34-56 ms each. So the shape below
     * is what makes this case report on the PRODUCT rather than on infrastructure weather: it polls, it waits
     * long enough for a cold service, and a genuine failure is reported by the assertion that follows.
     */
    cy.window({ timeout: 30000 }).should((w) => {
      expect(w.__c2.ids.length + w.__c2.failures.length, 'all eight requests settled').to.eq(8)
    })

    cy.window().then((w) => {
      expect(w.__c2.failures, `none of the eight was refused (statuses: ${JSON.stringify(w.__c2.failures)})`)
        .to.deep.eq([])
      const ids = Array.from(new Set(w.__c2.ids))
      expect(ids.length, `every caller got the SAME product id (got ${JSON.stringify(ids)})`).to.eq(1)
    })

    countNamed(prefix).should('eq', 1)
  })

  it('C-3 — a DIFFERENT key with identical values still creates a second product', () => {
    /*
     * The boundary. Two products legitimately named the same, with no SKU, is ordinary in a shop — and it is
     * exactly what the duplicate burst looked like. The guard must separate them by INTENT (the key), never by
     * field values, or it would start silently refusing real work. A guard that cannot be shown to still allow
     * the legitimate case has not been tested, only asserted.
     */
    const prefix = `DupC3_${uniq()}`
    const mk = () => ({ name: prefix, sellingPrice: 3, unit: 'pcs', idempotencyKey: `k-${uniq()}` })

    cy.request({ method: 'POST', url: '/addProduct', body: mk(), headers: { 'Content-Type': 'application/json' } })
      .then((r) => expect(r.body.success).to.eq(true))
    cy.then(() => cy.request({ method: 'POST', url: '/addProduct', body: mk(), headers: { 'Content-Type': 'application/json' } })
      .then((r) => expect(r.body.success).to.eq(true)))

    countNamed(prefix).should('eq', 2)
  })

  // ── D · the real form ───────────────────────────────────────────────────────────────────────────

  it('⭐⭐ D-1 — a held Enter on the product form registers ONE product', () => {
    /*
     * The incident, reproduced through the actual UI: one real press followed by a burst of auto-repeats, which
     * is what a held key delivers. Pre-fix this wrote one product per repeat.
     */
    const prefix = `DupD1_${uniq()}`

    openProductForm()
    cy.get('#prodName').clear().type(prefix)
    cy.get('#prodPrice').clear().type('6')

    cy.window().then((w) => {
      const btn = w.document.getElementById('addProduct')
      btn.focus()
      btn.click()                                            // the operator's real press
      for (let i = 0; i < 29; i++) keydown(w, btn, 'Enter', true)   // ~1 second of holding it
    })

    cy.wait(1500)
    countNamed(prefix).should('eq', 1)
  })

  it('⭐⭐ D-2 — Save & Add Another twice registers TWO products, because the key rotates', () => {
    /*
     * ⚠ THE REGRESSION THAT WOULD MAKE THIS SLICE WORSE THAN THE BUG.
     *
     * "Save & Add Another" keeps the modal open on purpose (rapid cataloguing). If the idempotency key were not
     * retired after a successful save, the SECOND product would carry the FIRST one's key — and the server
     * would correctly replay the first product. The operator catalogues twenty items, the shop gets one, and
     * every save reports success. Invisible until somebody counts rows, which is how the original defect got
     * to production in the first place.
     *
     * So this case asserts BOTH the rotation (FormKeys.peek is empty after a save) and its consequence (two
     * distinct products, not one replayed twice).
     */
    const run = uniq()
    const a = `DupD2a_${run}`
    const b = `DupD2b_${run}`

    openProductForm()
    cy.get('#prodName').clear().type(a)
    cy.get('#prodPrice').clear().type('6')
    cy.get('#addProductAnother').click()

    // The modal stays open — that is the feature — and the key must be gone.
    cy.get('#ProductModal').should('have.class', 'open')
    cy.get('#prodName').should('have.value', '')
    cy.window().should((w) => {
      expect(w.FormKeys.peek('product'), 'the key was retired on success, so the next product gets a new one')
        .to.eq(null)
    })

    // The run repaints the panel and the pickers, so the modal can slide a second time.
    settleProductForm()
    cy.get('#prodName').clear().type(b)
    cy.get('#prodPrice').clear().type('8')
    cy.get('#addProduct').click()

    cy.wait(1000)
    countNamed(`DupD2a_${run}`).should('eq', 1)
    countNamed(`DupD2b_${run}`).should('eq', 1)
  })

  it('D-3 — the submit control is disabled while the save is on the wire', () => {
    /*
     * The affordance. Coalescing is invisible, and an operator who sees nothing happen presses again — so the
     * button must visibly refuse. It disables a BUTTON and never a field: `disabled` inputs are dropped from
     * FormData, which this codebase has already paid for once on the name fields.
     */
    const prefix = `DupD3_${uniq()}`
    cy.intercept('POST', '**/addProduct', (req) => { req.on('response', (res) => res.setDelay(800)) }).as('slowSave')

    openProductForm()
    cy.get('#prodName').clear().type(prefix)
    cy.get('#prodPrice').clear().type('4')

    cy.get('#addProduct').click()
    cy.get('#addProduct').should('be.disabled')        // while in flight
    cy.wait('@slowSave')
    cy.get('#addProduct', { timeout: 6000 }).should('not.be.disabled')   // and released afterwards, always

    countNamed(prefix).should('eq', 1)
  })
})
