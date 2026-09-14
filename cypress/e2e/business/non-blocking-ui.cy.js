/**
 * BLK-1 — a READ never freezes the screen; a WRITE still does (until BLK-2 / BLK-13).
 *
 * Design: microservices/docs/blocking-ui-and-backend-guards-design.md §4.3.1
 * Ruling: "Block the risky action, not the whole user interface."
 *
 * ── What this exists to catch ───────────────────────────────────────────────────────────────────
 * Until BLK-1 every jQuery request raised a full-viewport veil (#appAjaxOverlay, z-index 99999) after 220 ms,
 * and ~200 of them were READS — filling a grid, a dropdown, a chart. A cashier could not type while a screen
 * populated. The rule now lives in ajax-overlay.js and is decided from the request's method:
 *
 *   read                    → no veil, a thin progress bar (#appAjaxProgress) that cannot be clicked
 *   write                   → the veil, as before — several money/stock writes have no server key yet
 *   nonBlocking: true       → no veil (PERF-13 saves)            blocking: true → the veil (opt-in)
 *   global: false (bgJson)  → nothing
 *
 * ── ⚠ Why every case RECORDS the screen for the whole request instead of asserting once ─────────────
 * `cy.get('#appAjaxOverlay').should('not.be.visible')` passes at t = 0, before the 220 ms show-delay — on the
 * OLD code too. A gate that cannot fail proves nothing. So a watcher samples both indicators, and what sits at
 * the centre of the viewport, every 25 ms while the request is HELD OPEN well past the show-delay, and the
 * assertions run on what it saw.
 *
 * ── ⚠ And why it NAMES what raised the veil ─────────────────────────────────────────────────────
 * "The veil rose" is a symptom. The watcher also records every request that would raise it (a write, or an
 * explicit blocking:true read) while it runs, and a failing "no veil" assertion prints them — so a case that
 * fails because a screen fires an unrelated write names that write instead of blaming the rule.
 *
 * Synthetic probe paths (blk1Probe*) are answered by cy.intercept and never reach a server, so cases 1–7 test
 * the RULE with no data and no side effects. Case 8 holds a real screen's own read open.
 *
 * Requires: the monolith rebuilt with the BLK-1 ajax-overlay.js (case 0 says so if it is stale). Run headed.
 */

const HOLD_MS = 1200          // comfortably past the 220 ms show-delay

const idle = () => cy.get('#appAjaxOverlay').should('not.have.class', 'show')
  .then(() => cy.get('#appAjaxProgress').should('not.have.class', 'show'))

/** Would this request hold the veil? Mirrors ajax-overlay.js's rule, for the diagnostic only. */
const veilCause = (settings) => {
  if (!settings) return null
  const m = String(settings.type || settings.method || 'GET').toUpperCase()
  const write = m !== 'GET' && m !== 'HEAD'
  if (settings.blocking === true || (write && settings.nonBlocking !== true)) {
    return `${m} ${String(settings.url || '?').split('?')[0]}`
  }
  return null
}

/** Sample both indicators + the element at the viewport's centre every 25 ms, until `seen()` stops it. */
const watch = () => cy.window().then((win) => {
  const seen = { veil: false, bar: false, covered: false, samples: 0, causes: [] }
  const id = win.setInterval(() => {
    const d = win.document
    const veil = d.getElementById('appAjaxOverlay')
    const bar = d.getElementById('appAjaxProgress')
    if (veil && veil.classList.contains('show')) seen.veil = true
    if (bar && bar.classList.contains('show')) seen.bar = true
    const hit = d.elementFromPoint(win.innerWidth / 2, win.innerHeight / 2)
    if (hit && hit.closest && hit.closest('#appAjaxOverlay')) seen.covered = true
    seen.samples++
  }, 25)
  const onSend = (evt, jqXHR, settings) => {
    const c = veilCause(settings)
    if (c) seen.causes.push(c)
  }
  win.jQuery(win.document).on('ajaxSend', onSend)
  win.__blk1 = {
    seen,
    stop: () => { win.clearInterval(id); win.jQuery(win.document).off('ajaxSend', onSend) },
  }
})

const seen = () => cy.window().then((win) => {
  win.__blk1.stop()
  const s = win.__blk1.seen
  // Fails loudly if the watcher never ran long enough to see anything — a gate must not pass on no data.
  expect(s.samples, 'the watcher sampled the screen while the request was open').to.be.greaterThan(10)
  return s
})

/** "No veil, nothing covered" — with the requests that WOULD raise it named in the failure message. */
const expectNoVeil = (s, what) => {
  const named = s.causes.length ? s.causes.join(', ') : 'none recorded'
  expect(s.veil, `⭐ ${what}: the veil must never rise (veil-raising requests seen: ${named})`).to.eq(false)
  expect(s.covered, `⭐ ${what}: nothing may cover the centre of the screen`).to.eq(false)
}

/** Fire a jQuery request from the page, exactly as app code does — through the same global events. */
const fire = (path, opts) => cy.window().then((win) => {
  win.jQuery.ajax(Object.assign({ url: win.serverContext + path }, opts || {}))
})

const hold = (method, path, alias, ms) =>
  cy.intercept({ method, url: `**/${path}*` }, { delay: ms == null ? HOLD_MS : ms, statusCode: 200,
    body: { status: 'SUCCESS' } }).as(alias)

describe('BLK-1 — a read never freezes the screen', () => {
  beforeEach(() => {
    cy.loginAsOwner()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
  })

  it('0 — the served overlay is the BLK-1 build, and its bar can never be a click target', () => {
    // A stale monolith serves the old file under a correct-looking URL (static assets served STALE). Say so
    // here, once, rather than as seven confusing failures below.
    cy.get('#appAjaxProgress', { timeout: 10000 })
      .should('exist')
      .and('have.css', 'pointer-events', 'none')
  })

  it('⭐⭐ 1 — a slow READ never raises the veil and never covers the screen; it shows the bar', () => {
    hold('GET', 'blk1ProbeRead', 'read')
    watch()
    fire('blk1ProbeRead')
    cy.wait('@read')
    idle()
    seen().then((s) => {
      expectNoVeil(s, 'a read')
      expect(s.bar, 'the user still sees that something is loading').to.eq(true)
    })
  })

  it('⭐⭐ 2 — a slow WRITE still raises the veil (its only guard until BLK-13 gives it a key)', () => {
    hold('POST', 'blk1ProbeWrite', 'write')
    watch()
    fire('blk1ProbeWrite', { type: 'POST', data: { probe: Date.now() } })
    cy.wait('@write')
    idle()
    // No assertion on the bar: an unrelated background poll may legitimately show it during the window.
    seen().then((s) => expect(s.veil, '⭐ a write keeps the veil — see design §4.3.1').to.eq(true))
  })

  it('⭐ 3 — a PERF-13 write (nonBlocking) shows the bar, not the veil — PERF-13 is not undone', () => {
    hold('POST', 'blk1ProbeNonBlocking', 'nb')
    watch()
    fire('blk1ProbeNonBlocking', { type: 'POST', nonBlocking: true, data: { probe: Date.now() } })
    cy.wait('@nb')
    idle()
    seen().then((s) => {
      expectNoVeil(s, 'a nonBlocking save')
      expect(s.bar, 'and is still visibly in progress').to.eq(true)
    })
  })

  it('⭐ 4 — `blocking: true` opts a read INTO the veil', () => {
    hold('GET', 'blk1ProbeBlockingRead', 'br')
    watch()
    fire('blk1ProbeBlockingRead', { blocking: true })
    cy.wait('@br')
    idle()
    seen().then((s) => expect(s.veil, 'the explicit opt-in is honoured').to.eq(true))
  })

  it('⭐ 5 — a background read (bgJson, global:false) shows NOTHING', () => {
    hold('GET', 'blk1ProbeBg', 'bg')
    watch()
    cy.window().then((win) => { win.bgJson(win.serverContext + 'blk1ProbeBg') })
    cy.wait('@bg')
    idle()
    seen().then((s) => {
      expectNoVeil(s, 'a background read')
      expect(s.bar, 'and no bar — background population is not the user\'s wait').to.eq(false)
    })
  })

  it('⭐ 6 — a quick read (under the 220 ms show-delay) flashes nothing', () => {
    hold('GET', 'blk1ProbeQuick', 'quick', 0)
    watch()
    fire('blk1ProbeQuick')
    cy.wait('@quick')
    cy.wait(400)   // past the show-delay, so a stranded timer would have fired by now
    idle()
    seen().then((s) => {
      expectNoVeil(s, 'a quick read')
      expect(s.bar, 'no flicker of the bar either').to.eq(false)
    })
  })

  it('⭐ 7 — a finished WRITE lifts the veil even while a READ is still running (two independent counters)', () => {
    /*
     * ⚠ THE STATE IS RECORDED INSIDE THE PAGE, AT THE MOMENT THE WRITE COMPLETES — not asserted afterwards.
     *
     * The first cut asserted after `cy.wait('@shortWrite')` and went red on a loaded machine: the read had ~2 s of
     * slack, Cypress's command queue took longer than that to reach the assertion, the read had already finished,
     * and the bar had correctly gone. A timing flake about the TEST, not the rule. Reading the classes inside an
     * ajaxComplete handler removes the command queue from the measurement entirely.
     *
     * ajax-overlay.js registers its own ajaxComplete handler at page load, so it runs BEFORE this one (jQuery
     * fires a document's handlers in registration order): what is recorded is the state after the overlay has
     * already processed the write's completion. The read is held 6 s so it is certainly still open at that moment.
     */
    hold('GET', 'blk1ProbeLongRead', 'longRead', 6000)
    hold('POST', 'blk1ProbeShortWrite', 'shortWrite', 800)
    cy.window().then((win) => {
      const d = win.document
      win.__blk1AtWriteDone = null
      const onComplete = (evt, jqXHR, settings) => {
        if (String((settings && settings.url) || '').indexOf('blk1ProbeShortWrite') < 0) return
        win.__blk1AtWriteDone = {
          veil: d.getElementById('appAjaxOverlay').classList.contains('show'),
          bar: d.getElementById('appAjaxProgress').classList.contains('show'),
        }
        win.jQuery(d).off('ajaxComplete', onComplete)
      }
      win.jQuery(d).on('ajaxComplete', onComplete)
    })
    fire('blk1ProbeLongRead')
    fire('blk1ProbeShortWrite', { type: 'POST', data: { probe: Date.now() } })
    cy.wait('@shortWrite')
    cy.window().should((win) => {
      expect(win.__blk1AtWriteDone, 'the write\'s completion was recorded in the page').to.be.an('object')
    }).then((win) => {
      const at = win.__blk1AtWriteDone
      // Under a single shared counter the veil would stay up until the READ finished.
      expect(at.veil, '⭐ the veil lifted the moment the write finished, although a read was still open').to.eq(false)
      expect(at.bar, '⭐ and the still-running read kept its bar').to.eq(true)
    })
    cy.wait('@longRead', { timeout: 15000 })
    idle()
  })

  it('⭐ 9 — a write whose success handler THROWS does not leave the veil up for ever', () => {
    /*
     * jQuery 3.3.1 sets readyState = 4 and then runs the success callbacks (jquery-3.3.1.js:9244 / 9305). A throw
     * there skips ajaxComplete AND --jQuery.active (9311-9329), so the overlay's old sweep — which waited for
     * jQuery.active === 0 — never fired again and the veil stayed up for the rest of the session. The fix tracks
     * each request and releases one that FINISHED without completing. The sweep runs every 3 s; allow two.
     */
    cy.on('uncaught:exception', (err) => (/blk1-deliberate-throw/.test(err.message) ? false : undefined))
    hold('POST', 'blk1ProbeThrow', 'thr', 600)
    fire('blk1ProbeThrow', { type: 'POST', data: { probe: Date.now() },
      success: function () { throw new Error('blk1-deliberate-throw') } })
    cy.wait('@thr')
    cy.get('#appAjaxOverlay', { timeout: 8000 }).should('not.have.class', 'show')
  })

  it('⭐⭐ 8 — the REAL screen: opening Customers while its own read is held open never veils the page', () => {
    // loadAccountGroups() → GET getUserCustomers, fired by selecting the section (owner/admin only — the
    // account-group card is rendered for them). Waiting on it BY NAME is the eligibility check: if this
    // screen ever stops issuing the read, the case fails here instead of passing with nothing held open.
    cy.intercept('GET', '**/getUserCustomers*', (req) => {
      req.on('response', (res) => { res.setDelay(HOLD_MS) })
    }).as('custOpts')
    watch()
    cy.get('#registrationType').select('CustomerDiv', { force: true })
    cy.get('#CustomerDiv').should('be.visible')
    cy.wait('@custOpts', { timeout: 15000 })
    cy.waitForAppReady()
    seen().then((s) => {
      expectNoVeil(s, 'opening a section')
      expect(s.bar, 'the load is still visible').to.eq(true)
    })
  })
})
