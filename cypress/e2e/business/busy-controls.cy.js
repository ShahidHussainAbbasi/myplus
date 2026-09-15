/**
 * BLK-2 — the clicked control says what it is doing; only the risky action is blocked.
 * Design: microservices/docs/slices/blk-2-busy-controls.md
 *
 * ⭐ WRITTEN BEFORE THE IMPLEMENTATION. Against the code as it stood, every ⭐ case was red (there was no busy
 * state, no label, and several forms had no lock at all); the REGRESSION cases pin behaviour that must survive.
 *
 * Run 1 (2026-09-14 10:53) failed 7, 8, 9, 12 — see the design doc §6. What it taught, now built in:
 *   • case 12 found a REAL defect pair: a sale-return refusal was shown as a success (fixed in business.js), and
 *     a success handler that THROWS skips jQuery's ajaxComplete, stranding the control (fixed: submit-once.js's
 *     last-resort sweep, pinned by case 14);
 *   • case 9 opened a modal inside a hidden section — cases now open the section that hosts the control;
 *   • case 7's watcher got 2 samples in 1.2 s: the page's main thread was busy. The watcher now RECORDS long
 *     tasks, so a low sample count names its cause instead of reading as a product failure.
 *
 * ── ⚠ Why each case RECORDS the control inside the page ─────────────────────────────────────────────
 * The busy state lives only while the request is in flight. An assertion made after `cy.wait` races the command
 * queue (the BLK-1 case-7 lesson) and a single `should` can pass before the state ever appears. So a watcher
 * samples the control and the veil every 25 ms while the request is HELD OPEN, and the assertions run on what it saw.
 *
 * ── No case writes data ─────────────────────────────────────────────────────────────────────────────
 * Every request is answered by cy.intercept — a probe reply or a refusal (FAILED / 4xx / 500) — so the screens'
 * real submit paths run end to end on the client while nothing reaches a server.
 *
 * ── ⚠ Run it ALONE ──────────────────────────────────────────────────────────────────────────────────
 * It logs in as owner.business@ and demo.education@. The monolith is maximumSessions(1): a second Cypress run as
 * the same user expires this one's session mid-case (~37 s stalls, waitForAppReady timeouts, random reds).
 *
 * Requires: the monolith rebuilt with BLK-2's static JS + messages (case 0 says so if stale). Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/busy-controls.cy.js
 */

const HOLD_MS = 1200   // well past ajax-overlay.js's 220 ms show-delay, so "no veil" means something
const SAVING = 'Saving…'
const POSTING = 'Posting…'

/**
 * Watch one control and the veil every 25 ms. Captures the control's label and width BEFORE the request, and the
 * main-thread LONG TASKS during it — a timer cannot fire while the thread is blocked, so a watcher that saw
 * little must be able to say why.
 */
const watch = (selector) => cy.window().then((win) => {
  const el0 = win.document.querySelector(selector)
  expect(el0, `${selector} exists before the request`).to.exist
  const s = {
    samples: 0, html0: el0.innerHTML, width0: el0.getBoundingClientRect().width,
    busy: false, disabled: false, labels: [], spinner: false, shrank: false, veil: false, blockedMs: 0,
  }
  let observer = null
  try {
    observer = new win.PerformanceObserver((list) => {
      list.getEntries().forEach((e) => { s.blockedMs += Math.round(e.duration) })
    })
    observer.observe({ type: 'longtask', buffered: false })
  } catch (e) { /* longtask unsupported — the sample count still guards */ }
  const id = win.setInterval(() => {
    const d = win.document
    const veil = d.getElementById('appAjaxOverlay')
    if (veil && veil.classList.contains('show')) s.veil = true
    const el = d.querySelector(selector)
    if (el && el.getAttribute('aria-busy') === 'true') {
      s.busy = true
      if (el.disabled) s.disabled = true
      const text = el.textContent.replace(/\s+/g, ' ').trim()
      if (s.labels.indexOf(text) < 0) s.labels.push(text)
      if (el.querySelector('.busy-spin')) s.spinner = true
      if (el.getBoundingClientRect().width + 1 < s.width0) s.shrank = true
    }
    s.samples++
  }, 25)
  win.__blk2 = { s, stop: () => { win.clearInterval(id); if (observer) observer.disconnect() } }
})

const seen = () => cy.window().then((win) => {
  win.__blk2.stop()
  const s = win.__blk2.s
  expect(s.samples, `the watcher sampled while the request was open — a gate must not pass on no data `
    + `(main thread blocked ${s.blockedMs} ms by long tasks while it watched)`).to.be.greaterThan(10)
  return s
})

/** After the request: the control is back EXACTLY as it was — enabled, not busy, same content. */
const expectRestored = (selector, html0) =>
  cy.get(selector).should(($el) => {
    const el = $el[0]
    expect(el.getAttribute('aria-busy'), `${selector} is no longer busy`).to.eq(null)
    expect(el.disabled, `${selector} is enabled again`).to.eq(false)
    expect(el.innerHTML, `${selector} shows its own label again, not "Saving…"`).to.eq(html0)
  })

/** A button the page did not have, for the rule itself — no screen, no data. */
const probeButton = (id, cls) => cy.window().then((win) => {
  const old = win.document.getElementById(id)
  if (old) old.remove()
  const b = win.document.createElement('button')
  b.type = 'button'
  b.id = id
  b.className = cls || 'btn btn-success'
  b.innerHTML = '<span class="glyphicon glyphicon-ok"></span> <span>Submit</span>'
  b.style.cssText = 'position:fixed;left:24px;bottom:24px;z-index:5'
  win.document.body.appendChild(b)
})

/** Hold a POST open and answer it without reaching a server. */
const holdPost = (path, alias, reply, ms) =>
  cy.intercept({ method: 'POST', url: `**/${path}*` },
    Object.assign({ delay: ms || HOLD_MS, statusCode: 200 },
      reply || { body: { status: 'FAILED', message: 'held by the gate' } }))
    .as(alias)

/** Open a business section through its real picker, then let it settle. */
const openSection = (value) => {
  cy.get('#registrationType').select(value, { force: true })   // the nav select is off-screen
  cy.get(`#${value}`).should('be.visible')
  cy.waitForAppReady()
}

describe('BLK-2 — the pressed control carries the wait', () => {
  beforeEach(() => {
    cy.loginAsOwner()   // owner: the permission-set and opening-balance cases need it
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
  })

  it('0 — the served build has BusyControl and its last-resort sweep (a stale monolith fails HERE)', () => {
    cy.window().its('BusyControl').should('exist')
    cy.window().then((win) => {
      expect(typeof win.BusyControl.hold, 'BusyControl.hold').to.eq('function')
      expect(typeof win.BusyControl.heldCount, 'the sweep fix is in the served build').to.eq('function')
    })
  })

  // ── the rule, on a probe control ─────────────────────────────────────────────────────────────────

  it('⭐⭐ 1 — a generic save labels the PRESSED control, keeps its width, raises no veil, and restores it exactly', () => {
    probeButton('blk2Btn')
    holdPost('blk2ProbeSave', 'save')
    watch('#blk2Btn')
    // The exact call the generic #add<Entity> handler makes: callAjax invoked ON the pressed button.
    cy.window().then((win) => win.jQuery('#blk2Btn').callAjax('blk2ProbeSave', 'probe=1'))
    cy.wait('@save')
    seen().then((s) => {
      expect(s.busy, 'aria-busy while in flight').to.eq(true)
      expect(s.disabled, 'disabled while in flight').to.eq(true)
      expect(s.labels, 'says what it is doing').to.include(SAVING)
      expect(s.spinner, 'shows a spinner').to.eq(true)
      expect(s.shrank, 'keeps its width — nothing jumps under the pointer').to.eq(false)
      expect(s.veil, 'callAjax is nonBlocking: the control carries the wait, not the screen').to.eq(false)
      expectRestored('#blk2Btn', s.html0)
    })
  })

  it('⭐⭐ 2 — an HTTP failure restores the control; it is never left reading "Saving…"', () => {
    probeButton('blk2Btn')
    holdPost('blk2ProbeFail', 'fail', { statusCode: 500, body: 'boom' })
    watch('#blk2Btn')
    cy.window().then((win) => win.jQuery('#blk2Btn').callAjax('blk2ProbeFail', 'probe=2'))
    cy.wait('@fail')
    seen().then((s) => {
      expect(s.busy, 'it was busy').to.eq(true)
      expectRestored('#blk2Btn', s.html0)
    })
  })

  it('⭐ 3 — a CONFIRM releases the control while the dialog is open; the resubmit holds the SAME control again', () => {
    probeButton('blk2Btn')
    let n = 0
    cy.intercept({ method: 'POST', url: '**/blk2ProbeConfirm*' }, (req) => {
      n += 1
      req.reply({ delay: 600, statusCode: 200,
        body: n === 1 ? { status: 'CONFIRM', message: 'Over the credit limit (gate probe).' }
                      : { status: 'FAILED', message: 'held by the gate' } })
    }).as('confirm')
    cy.window().then((win) => {
      win.__blk2html = win.document.getElementById('blk2Btn').innerHTML
      win.jQuery('#blk2Btn').callAjax('blk2ProbeConfirm', 'probe=3')
    })
    cy.wait('@confirm')
    cy.get('.uiC-card').should('be.visible')
    // While a person decides, nothing is in flight — the control must not still claim to be saving.
    cy.window().then((win) => expectRestored('#blk2Btn', win.__blk2html))

    watch('#blk2Btn')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@confirm').its('request.body').should('contain', 'creditAcknowledged=true')
    seen().then((s) => {
      expect(s.labels, 'the resubmit is held on the same control').to.include(SAVING)
    })
    cy.window().then((win) => expectRestored('#blk2Btn', win.__blk2html))
  })

  it('REGRESSION 4 — $(document).callAjax (the bulk-delete path) labels nothing and throws nothing', () => {
    holdPost('blk2ProbeDoc', 'doc')
    cy.window().then((win) => win.jQuery(win.document).callAjax('blk2ProbeDoc', 'probe=4'))
    cy.window().should((win) => {
      expect(win.document.querySelectorAll('[aria-busy="true"]').length, 'no control claims this request').to.eq(0)
    })
    cy.wait('@doc')
  })

  it('REGRESSION 5 — DUP-1: a double invoke while in flight is ONE request, and the control ends enabled', () => {
    probeButton('blk2Btn')
    let calls = 0
    cy.intercept({ method: 'POST', url: '**/blk2ProbeTwice*' }, (req) => {
      calls += 1
      req.reply({ delay: HOLD_MS, statusCode: 200, body: { status: 'FAILED', message: 'held' } })
    }).as('twice')
    cy.window().then((win) => {
      win.__blk2html = win.document.getElementById('blk2Btn').innerHTML
      win.jQuery('#blk2Btn').callAjax('blk2ProbeTwice', 'probe=5')
      win.jQuery('#blk2Btn').callAjax('blk2ProbeTwice', 'probe=5')   // identical — layer 2 coalesces it
    })
    cy.wait('@twice')
    cy.window().then((win) => expectRestored('#blk2Btn', win.__blk2html))
    cy.then(() => expect(calls, 'the identical second press never left the browser').to.eq(1))
  })

  it('REGRESSION 6 — an app relabel mid-request survives, and an already-disabled control is never touched', () => {
    probeButton('blk2Btn')
    probeButton('blk2Off')
    cy.window().then((win) => { win.document.getElementById('blk2Off').disabled = true })
    holdPost('blk2ProbeRelabel', 'relabel')
    cy.window().then((win) => {
      win.jQuery.ajax({ type: 'POST', url: win.serverContext + 'blk2ProbeRelabel', data: { p: 6 },
        busyControl: '#blk2Off' })
      win.jQuery('#blk2Btn').callAjax('blk2ProbeRelabel', 'probe=6b')
    })
    cy.get('#blk2Btn').should('have.attr', 'aria-busy', 'true')
    cy.get('#blk2Off').should('not.have.attr', 'aria-busy')   // nobody could have pressed a disabled control
    cy.window().then((win) => { win.document.getElementById('blk2Btn').textContent = 'Update' })   // app changed it
    cy.wait('@relabel')
    cy.get('#blk2Btn').should(($b) => {
      expect($b[0].getAttribute('aria-busy')).to.eq(null)
      expect($b.text(), 'the app relabel is not overwritten by a stale restore').to.eq('Update')
      expect($b[0].disabled).to.eq(false)
    })
    cy.get('#blk2Off').should('be.disabled')   // still locked by whoever locked it
  })

  it('⭐⭐ 14 — a success handler that THROWS still releases the control (jQuery skips ajaxComplete then)', () => {
    /*
     * jQuery 3.3.1 runs success callbacks with no try/catch and triggers ajaxComplete only after them, so a throw
     * skips it — the exact path run 1's case 12 found. submit-once.js's last-resort sweep must release the control
     * anyway, within about a second of the request finishing.
     */
    cy.on('uncaught:exception', (err) => !String(err && err.message).includes('BLK-2 gate probe'))
    probeButton('blk2Btn')
    holdPost('blk2ProbeThrow', 'thrower', { body: { status: 'SUCCESS' } }, 300)
    cy.window().then((win) => {
      win.__blk2html = win.document.getElementById('blk2Btn').innerHTML
      win.jQuery.ajax({ type: 'POST', url: win.serverContext + 'blk2ProbeThrow', data: { p: 14 }, dataType: 'json',
        // nonBlocking: this case tests the CONTROL's release. A veil raised by a request whose handler throws is
        // stranded the same way — that is ajax-overlay.js's fix (myplus-5f), not something this case should need.
        nonBlocking: true,
        busyControl: '#blk2Btn',
        success: () => { throw new Error('BLK-2 gate probe: an app success handler threw') } })
    })
    cy.wait('@thrower')
    cy.get('#blk2Btn').should('have.attr', 'aria-busy', 'true')   // stranded for a moment — ajaxComplete never ran
    cy.window({ timeout: 4000 }).should((win) => {
      const b = win.document.getElementById('blk2Btn')
      expect(b.getAttribute('aria-busy'), 'released by the sweep').to.eq(null)
      expect(b.disabled, 'usable again').to.eq(false)
      expect(b.innerHTML, 'its own label back').to.eq(win.__blk2html)
      expect(win.BusyControl.heldCount(), 'nothing left holding a control').to.eq(0)
    })
  })

  // ── the real forms ───────────────────────────────────────────────────────────────────────────────

  it('⭐⭐ 7 — POS sale: #addSell says "Posting…" and the screen stays usable', () => {
    cy.visitSaleScreen()
    cy.waitForAppReady()   // run 1: the sale screen was still busy on the main thread — let it settle first
    holdPost('addSell', 'sell', null, 2500)
    watch('#addSell')
    cy.window().then((win) => win.jsonPost('addSell', {}))   // the sale submit path itself
    cy.wait('@sell')
    seen().then((s) => {
      expect(s.labels, 'the sale button says it is posting').to.include(POSTING)
      expect(s.disabled, 'and cannot be pressed twice').to.eq(true)
      expect(s.veil, 'the sale was already off the veil (PERF-13) and stays off').to.eq(false)
      expectRestored('#addSell', s.html0)
    })
  })

  it('⭐⭐ 8 — product: the PRESSED "Save & Add Another" is labelled, #addProduct is locked but unlabelled, no veil', () => {
    cy.window().then((win) => win.showProducts())
    cy.get('#ProductDiv').should('be.visible')
    cy.window().then((win) => win.newProduct())
    cy.get('#ProductModal').should('have.class', 'open')
    cy.waitForAppReady()
    cy.get('#prodName').should('be.visible')
    cy.settled('#prodName')
    cy.get('#prodName').clear().type(`BLK2 probe ${Date.now()}`)

    holdPost('addProduct', 'prod', { body: { success: false, message: 'held by the gate' } })
    cy.window().then((win) => {
      win.__blk2save = win.document.getElementById('addProduct').innerHTML
      win.__blk2saveSeen = { disabled: false, labelled: false }
      win.__blk2saveId = win.setInterval(() => {
        const b = win.document.getElementById('addProduct')
        if (b.disabled) win.__blk2saveSeen.disabled = true
        if (b.textContent.indexOf('Saving') >= 0) win.__blk2saveSeen.labelled = true
      }, 25)
    })
    watch('#addProductAnother')
    cy.get('#addProductAnother').click()
    cy.wait('@prod')
    seen().then((s) => {
      expect(s.labels, 'the button the operator pressed says so').to.include(SAVING)
      expect(s.veil, 'product save has a server key (DUP-1): the veil is off').to.eq(false)
      expectRestored('#addProductAnother', s.html0)
    })
    cy.window().then((win) => {
      win.clearInterval(win.__blk2saveId)
      expect(win.__blk2saveSeen.disabled, '#addProduct was locked too — no second submit through it').to.eq(true)
      expect(win.__blk2saveSeen.labelled, 'but only the pressed control is labelled').to.eq(false)
      expectRestored('#addProduct', win.__blk2save)
    })
  })

  it('⭐⭐ 9 — receive payment: "Posting…" on the submit, and no veil (it has a server key)', () => {
    openSection('CustomerDiv')   // run 1: the dialog lives inside #CustomerDiv, which was hidden
    cy.window().then((win) => win.openReceivePayment(999999, 'BLK2 probe customer', 10))
    cy.get('#ReceivePaymentModal').should('have.class', 'open')
    cy.settled('#rcvAmount')
    holdPost('receivePayment', 'rcv')
    watch('#submitReceivePayment')
    cy.get('#submitReceivePayment').click()
    cy.wait('@rcv')
    seen().then((s) => {
      expect(s.labels).to.include(POSTING)
      expect(s.disabled).to.eq(true)
      expect(s.veil, 'Audit #5 key + server replay: the veil is off').to.eq(false)
      expectRestored('#submitReceivePayment', s.html0)
    })
  })

  it('⭐⭐ 15 — pay vendor: "Posting…" on the submit, and no veil (it has a server key)', () => {
    openSection('VenderDiv')
    cy.window().then((win) => win.openPayVendor(999999, 'BLK2 probe vendor', 10))
    cy.get('#PayVendorModal').should('have.class', 'open')
    cy.settled('#pvAmount')
    holdPost('payVendor', 'pv')
    watch('#submitPayVendor')
    cy.get('#submitPayVendor').click()
    cy.wait('@pv')
    seen().then((s) => {
      expect(s.labels).to.include(POSTING)
      expect(s.disabled).to.eq(true)
      expect(s.veil, 'Audit #5 key + server replay: the veil is off').to.eq(false)
      expectRestored('#submitPayVendor', s.html0)
    })
  })

  it('⭐ 10 — stock correction: a compact spinner that does not resize the row button, and NO veil (BLK-5 key)', () => {
    cy.window().then((win) => {
      const d = win.document
      ;['addstk_990001', 'lessstkbtn_990001'].forEach((id) => { const o = d.getElementById(id); if (o) o.remove() })
      const wrap = d.createElement('div')
      wrap.style.cssText = 'position:fixed;left:24px;bottom:60px;z-index:5'
      wrap.innerHTML = '<input id="addstk_990001" value="1" style="width:60px"> '
        + '<button type="button" id="lessstkbtn_990001" class="btn btn-xs btn-warning">−</button>'
      d.body.appendChild(wrap)
    })
    holdPost('adjustProductStock', 'adj', { body: { success: false, message: 'held by the gate' } })
    watch('#lessstkbtn_990001')
    cy.window().then((win) => win.adjustProductStock(990001))
    // BLK-5 — a correction asks WHY before anything is sent. Answer it; then the held request starts.
    // (The dialog's backdrop is not the veil: `watch` reads #appAjaxOverlay.show only.)
    cy.get('#uiC-input', { timeout: 10000 }).type('Damaged')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@adj')
    seen().then((s) => {
      expect(s.spinner, 'a spinner').to.eq(true)
      expect(s.disabled).to.eq(true)
      expect(s.labels.join(' '), 'no visible text that would widen a table row').not.to.contain('Saving')
      expect(s.shrank).to.eq(false)
      expect(s.veil, '⭐ BLK-5: the correction now has a server key — the row button carries the wait, not the screen')
        .to.eq(false)
      expectRestored('#lessstkbtn_990001', s.html0)
    })
  })

  it('⭐ 16 — stock add: the row + button carries the wait, and the veil is KEPT (no key)', () => {
    cy.window().then((win) => {
      const d = win.document
      ;['addstk_990002', 'addstkbtn_990002'].forEach((id) => { const o = d.getElementById(id); if (o) o.remove() })
      const wrap = d.createElement('div')
      wrap.style.cssText = 'position:fixed;left:24px;bottom:96px;z-index:5'
      wrap.innerHTML = '<input id="addstk_990002" value="1" style="width:60px"> '
        + '<button type="button" id="addstkbtn_990002" class="btn btn-xs btn-success">+</button>'
      d.body.appendChild(wrap)
    })
    holdPost('addProductStock', 'addstk', { body: { success: false, message: 'held by the gate' } })
    watch('#addstkbtn_990002')
    cy.window().then((win) => win.addProductStock(990002))
    cy.wait('@addstk')
    seen().then((s) => {
      expect(s.spinner, 'a spinner').to.eq(true)
      expect(s.disabled).to.eq(true)
      expect(s.veil, 'REGRESSION: no server key — the veil stays').to.eq(true)
      expectRestored('#addstkbtn_990002', s.html0)
    })
  })

  it('⭐ 11 — the permission-set save is LOCKED (it had no lock at all), and keeps the veil', () => {
    cy.get('#permSave', { timeout: 15000 }).should('exist')
    cy.window().then((win) => { win.document.getElementById('permSave').disabled = false })
    holdPost('team/permissions/sets', 'perm', { statusCode: 400, body: { message: 'held by the gate' } })
    watch('#permSave')
    cy.window().then((win) => win.savePermissionSet())
    cy.wait('@perm')
    seen().then((s) => {
      expect(s.disabled, 'cannot be pressed twice').to.eq(true)
      expect(s.labels).to.include(SAVING)
      expect(s.veil, 'no version check yet (BLK-7): the veil stays').to.eq(true)
      expectRestored('#permSave', s.html0)
    })
  })

  it('⭐ 17 — a member\'s permission-set picker is locked while the change posts (it had no lock)', () => {
    cy.window().then((win) => {
      const old = win.document.getElementById('blk2PermSel')
      if (old) old.remove()
      const sel = win.document.createElement('select')
      sel.id = 'blk2PermSel'
      sel.className = 'form-control input-sm js-permset'
      sel.setAttribute('data-user-id', '990001')
      sel.innerHTML = '<option value="1">Probe A</option><option value="2">Probe B</option>'
      sel.style.cssText = 'position:fixed;left:24px;bottom:132px;z-index:5;width:160px'
      win.document.body.appendChild(sel)
    })
    holdPost('team/permissions/assign', 'assign', { statusCode: 400, body: { message: 'held by the gate' } })
    watch('#blk2PermSel')
    cy.window().then((win) => win.jQuery('#blk2PermSel').val('2').trigger('change'))   // team.js's own handler
    cy.wait('@assign')
    seen().then((s) => {
      expect(s.busy, 'aria-busy while the change posts').to.eq(true)
      expect(s.disabled, 'a second pick cannot race the first').to.eq(true)
      expectRestored('#blk2PermSel', s.html0)
    })
  })

  it('⭐ 12 — sale return: "Posting…", the veil KEPT, and a REFUSAL is shown as a refusal (not a success)', () => {
    cy.window().then((win) => {
      const b = win.document.createElement('button')
      b.setAttribute('data-qty', '1'); b.setAttribute('data-sellid', '990001'); b.setAttribute('data-stockid', '')
      b.setAttribute('data-invoice', 'BLK2-PROBE'); b.setAttribute('data-item', 'probe')
      win.openSaleReturn(b)
    })
    cy.get('#srSubmit').should('be.visible')
    // A refusal WITH a message — exactly what the server sends ("Cannot return more than the sold quantity…").
    holdPost('saleReturn', 'sr', { body: { status: 'FAILED', message: 'held by the gate' } })
    watch('#srSubmit')
    cy.get('#srSubmit').click()
    cy.wait('@sr')
    seen().then((s) => {
      expect(s.labels).to.include(POSTING)
      expect(s.disabled).to.eq(true)
      expect(s.veil, 'REGRESSION: sale return keeps the veil until BLK-13').to.eq(true)
      expectRestored('#srSubmit', s.html0)
    })
    // ⭐ Run 1 found this: the refusal used to be shown as a SUCCESS and the dialog closed.
    cy.get('#saleReturnDialog').should('be.visible')
    cy.get('#srError').should('contain', 'held by the gate')
  })

  it('⭐ 18 — purchase return: #prSubmit (it had no lock) says "Posting…", and the veil is KEPT', () => {
    cy.window().then((win) => win.openPurchaseReturn(990001, 1, 'BLK2-PROBE'))
    cy.get('#prSubmit').should('be.visible')
    holdPost('purchaseReturn', 'pr')
    watch('#prSubmit')
    cy.get('#prSubmit').click()
    cy.wait('@pr')
    seen().then((s) => {
      expect(s.labels).to.include(POSTING)
      expect(s.disabled).to.eq(true)
      expect(s.veil, 'REGRESSION: no server de-duplication yet (BLK-13) — the veil stays').to.eq(true)
      expectRestored('#prSubmit', s.html0)
    })
    cy.get('#prError').should('contain', 'held by the gate')
  })

  it('⭐ 19 — void sale: the row\'s Void button (it had no lock) carries the wait, and the veil is KEPT', () => {
    cy.window().then((win) => {
      const old = win.document.getElementById('blk2VoidBtn')
      if (old) old.remove()
      const b = win.document.createElement('button')
      b.type = 'button'
      b.id = 'blk2VoidBtn'
      b.className = 'btn btn-xs btn-danger'
      b.textContent = 'Void'
      b.setAttribute('data-chid', '990001')
      b.setAttribute('data-invoice', 'BLK2-PROBE')
      b.style.cssText = 'position:fixed;left:24px;bottom:168px;z-index:5'
      win.document.body.appendChild(b)
      win.openVoidSell(b)
    })
    cy.get('.uiC-card').should('be.visible')
    holdPost('voidSell', 'void')
    watch('#blk2VoidBtn')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@void')
    seen().then((s) => {
      expect(s.spinner, 'a compact row button gets a spinner').to.eq(true)
      expect(s.disabled).to.eq(true)
      expect(s.veil, 'REGRESSION: a void has no key — the veil stays').to.eq(true)
      expectRestored('#blk2VoidBtn', s.html0)
    })
  })

  it('⭐ 20 — opening balance: #obPost says "Posting…", and the veil is KEPT (the screen sends no key)', () => {
    cy.window().then((win) => win.showOpeningBalances())
    cy.get('#OpeningBalanceDiv').should('be.visible')
    cy.waitForAppReady()   // the party list loads async and would overwrite the probe option below
    cy.window().then((win) => {
      win.jQuery('#obParty').append(new win.Option('BLK2 probe party', '990001')).val('990001')
      win.jQuery('#obAmount').val('5')
    })
    holdPost('postOpeningBalance', 'ob')
    watch('#obPost')
    cy.get('#obPost').click()
    cy.wait('@ob')
    seen().then((s) => {
      expect(s.labels).to.include(POSTING)
      expect(s.disabled).to.eq(true)
      expect(s.veil, 'REGRESSION: no key sent yet (BLK-13) — the veil stays').to.eq(true)
      expectRestored('#obPost', s.html0)
    })
  })
})

describe('BLK-2 — the education fee form, which had no guard at all', () => {
  beforeEach(() => {
    cy.loginAsEducation()
    cy.visit('/educationDashboard')
    cy.waitForAppReady()
  })

  it('⭐⭐ 13 — #addFc is locked and labelled while the fee posts', () => {
    cy.get('#addFc', { timeout: 15000 }).should('exist')
    holdPost('addFc', 'fc')
    watch('#addFc')
    // The generic #add<Entity> handler's own call: callAjax ON the pressed button.
    cy.window().then((win) => win.jQuery('#addFc').callAjax('addFc', 'enrollNo=BLK2-PROBE'))
    cy.wait('@fc')
    seen().then((s) => {
      expect(s.disabled, '⭐ the fee button can no longer be pressed again mid-flight').to.eq(true)
      expect(s.labels).to.include(SAVING)
      expectRestored('#addFc', s.html0)
    })
  })
})
