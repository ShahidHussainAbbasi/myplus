/**
 * DIAG — a fast Enter on the Price field while the line strip re-renders after a product pick.
 *
 * The question (raised by session myplus-11, 2026-09-15 — UNVERIFIED): pos-keyboard.js:862 COMMITS the line when
 * `nextField('sellSellRate')` returns null. After a pick, loadStock re-renders the strip, and "for a few frames
 * afterwards the fields behind this picker can all read as not-yet-usable, so walk() answers null for a MOMENT"
 * (pos-keyboard.js:1013-1016). The picker path guards that moment; the text-input Enter does not. So with the
 * quantity already filled, would a fast Enter on the price ADD the line and skip the Discount stop?
 * (pos-cell-layout's intermittent 11/12 was the same moment with an EMPTY qty — commitLine refused and focused Qty.)
 *
 * Asserts NOTHING about the product. It records, per account run:
 *   window     every animation frame for 4 s after a pick: would Enter on the price find a next field, and — for each
 *              field after the price — usable / disabled / readOnly / on-screen (FocusFlow.skip's own inputs)
 *   attempts   5 fast attempts: pick → qty 1 → Enter → Enter, no waits; did the line commit, where did focus land,
 *              and what did walk() answer at the instant of the price Enter
 * and writes cypress/results/diag-fast-enter.json.
 *
 * SKIPPED unless run with --env diag=1 (not via excludeSpecPattern — Cypress 13 applies that to --spec too; see
 * sale-open-profile.cy.js):
 *   npx cypress run --headed --browser chrome --env diag=1 --spec cypress/e2e/diag/fast-enter-price.cy.js
 */

const run = Cypress.env('diag') ? describe : describe.skip

// The line chain after the price, in pos-keyboard.js CHAIN order (bonus pinned off below, as pos-cell-layout does).
const AFTER_PRICE = ['sellSellRate', 'sellDiscountTypeDD', 'sellDiscount']

function fieldState(win, id) {
  const el = win.document.getElementById(id)
  if (!el) return { id, missing: true }
  let shown = el
  if (el.classList.contains('selectpicker')) {
    const wrap = el.nextElementSibling
    shown = (wrap && wrap.querySelector('button')) || el
  }
  return {
    id,
    usable: win.EnterChain.usable(id),
    disabled: !!el.disabled,
    readOnly: !!el.readOnly,
    onScreen: shown.getClientRects().length > 0,
  }
}

function nextAfterPrice(win) {
  return win.EnterChain.walk(AFTER_PRICE, 'sellSellRate', 1)
}

/** Sample every animation frame for `ms` and keep the frames where Enter on the price would find nothing. */
function startSampler(win, ms) {
  const out = { frames: 0, nullFrames: [], startedAt: win.performance.now() }
  const until = out.startedAt + ms
  const tick = () => {
    const t = Math.round(win.performance.now() - out.startedAt)
    out.frames++
    if (nextAfterPrice(win) === null) {
      out.nullFrames.push({ t, fields: AFTER_PRICE.slice(1).map((id) => fieldState(win, id)) })
    }
    if (win.performance.now() < until) win.requestAnimationFrame(tick)
  }
  win.requestAnimationFrame(tick)
  return out
}

/** At every Enter on the price, record what walk() answered at that instant (capture phase, before pos-keyboard). */
function recordPriceEnters(win, sink) {
  win.document.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' || !e.target || e.target.id !== 'sellSellRate') return
    sink.push({
      next: nextAfterPrice(win),
      qty: win.document.getElementById('sellQuantity').value,
      fields: AFTER_PRICE.slice(1).map((id) => fieldState(win, id)),
    })
  }, true)
}

function openTill() {
  cy.viewport(1600, 900)
  cy.visitSaleScreen()
  // The page's own settings must have landed before anything is pinned (see cy.enableScanBox).
  cy.window({ timeout: 30000 }).should((w) => expect(w.posDefaultTender, 'settings applied').to.not.be.undefined)
  cy.window().then((w) => {
    w.posKeyboardEnabled = true
    w.applyPosKeyboard()
  })
  cy.setPosFields({ bonus: false })
}

run('DIAG — fast Enter on the price during the post-pick re-render', () => {
  const report = { window: null, attempts: [] }

  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('1 — how long, after a pick, would Enter on the price find no next field?', () => {
    cy.seedProduct({ name: 'DiagFE_' + Date.now(), sellingPrice: 30, stock: 50 }).then(({ productId }) => {
      openTill()
      cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')
      cy.window().then((w) => {
        report.window = startSampler(w, 4000)
        report.window.fieldsInSell = w.EnterChain.fieldsIn('#Sell')
      })
      cy.get('#sellItemDD').select(String(productId), { force: true })
      cy.wait(4500)
      cy.then(() => {
        const n = report.window.nullFrames
        Cypress.log({
          name: 'window',
          message: `${report.window.frames} frames, ${n.length} with NO next field after the price`
            + (n.length ? ` (t=${n[0].t}..${n[n.length - 1].t} ms)` : ''),
        })
      })
    })
  })

  for (let i = 1; i <= 5; i++) {
    it(`2.${i} — pick → qty 1 → Enter → Enter at once: committed? where is the cursor?`, () => {
      cy.seedProduct({ name: `DiagFE${i}_` + Date.now(), sellingPrice: 30, stock: 50 }).then(({ productId }) => {
        openTill()
        cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should('exist')
        const enters = []
        cy.window().then((w) => recordPriceEnters(w, enters))
        cy.get('#sellItemDD').select(String(productId), { force: true })
        // No waits between these — as fast as a cashier who knows the till.
        cy.get('#sellQuantity').clear().type('1{enter}')
        cy.focused().type('{enter}')
        cy.wait(1500)
        cy.window().then((w) => {
          const f = w.document.activeElement
          const focused = (f && (f.id || (f.closest('.bootstrap-select') && f.closest('.bootstrap-select').previousElementSibling.id))) || ''
          const attempt = {
            n: i,
            committedLines: (w.data || []).length,
            focusedAfter: focused,
            priceEnters: enters,
          }
          report.attempts.push(attempt)
          Cypress.log({
            name: `attempt ${i}`,
            message: `cart lines ${attempt.committedLines}, focus ${focused}, walk at price Enter = `
              + JSON.stringify(enters.map((e) => e.next)),
          })
        })
      })
    })
  }

  after(() => {
    cy.writeFile('cypress/results/diag-fast-enter.json', report)
  })
})
