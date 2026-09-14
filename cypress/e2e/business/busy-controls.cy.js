/**
 * BLK-2 — the clicked control says what it is doing; only the risky action is blocked.
 * Design: microservices/docs/slices/blk-2-busy-controls.md
 *
 * ⭐ WRITTEN BEFORE THE IMPLEMENTATION. Against the code as it stood, every ⭐ case is red (there is no busy
 * state, no label, and several forms have no lock at all); the REGRESSION cases pin behaviour that must survive.
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
 * Requires: the monolith rebuilt with BLK-2's static JS + messages (case 0 says so if stale). Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/busy-controls.cy.js
 */

const HOLD_MS = 1200   // well past ajax-overlay.js's 220 ms show-delay, so "no veil" means something
const SAVING = 'Saving…'
const POSTING = 'Posting…'

/** Watch one control and the veil every 25 ms. Captures the control's label and width BEFORE the request. */
const watch = (selector) => cy.window().then((win) => {
  const el0 = win.document.querySelector(selector)
  expect(el0, `${selector} exists before the request`).to.exist
  const s = {
    samples: 0, html0: el0.innerHTML, width0: el0.getBoundingClientRect().width,
    busy: false, disabled: false, labels: [], spinner: false, shrank: false, veil: false,
  }
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
  win.__blk2 = { s, stop: () => win.clearInterval(id) }
})

const seen = () => cy.window().then((win) => {
  win.__blk2.stop()
  const s = win.__blk2.s
  expect(s.samples, 'the watcher sampled while the request was open — a gate must not pass on no data')
    .to.be.greaterThan(10)
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
const holdPost = (path, alias, reply) =>
  cy.intercept({ method: 'POST', url: `**/${path}*` },
    Object.assign({ delay: HOLD_MS, statusCode: 200 }, reply || { body: { status: 'FAILED', message: 'held by the gate' } }))
    .as(alias)

describe('BLK-2 — the pressed control carries the wait', () => {
  beforeEach(() => {
    cy.loginAsOwner()   // owner: the permission-set case needs it
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
  })

  it('0 — the served build has BusyControl (a stale monolith fails HERE, not as a mystery below)', () => {
    cy.window().its('BusyControl').should('exist')
    cy.window().then((win) => expect(typeof win.BusyControl.hold, 'BusyControl.hold').to.eq('function'))
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

  // ── the real forms ───────────────────────────────────────────────────────────────────────────────

  it('⭐⭐ 7 — POS sale: #addSell says "Posting…" and the screen stays usable', () => {
    cy.visitSaleScreen()
    holdPost('addSell', 'sell')
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

  it('⭐ 10 — stock correction: a compact spinner that does not resize the row button, and the veil KEPT (no key)', () => {
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
    cy.wait('@adj')
    seen().then((s) => {
      expect(s.spinner, 'a spinner').to.eq(true)
      expect(s.disabled).to.eq(true)
      expect(s.labels.join(' '), 'no visible text that would widen a table row').not.to.contain('Saving')
      expect(s.shrank).to.eq(false)
      expect(s.veil, '⭐ REGRESSION: stock adjustment has no server key (BLK-5) — the veil stays').to.eq(true)
      expectRestored('#lessstkbtn_990001', s.html0)
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

  it('⭐ 12 — sale return: #srSubmit says "Posting…", and the veil is KEPT (no server de-duplication yet)', () => {
    cy.window().then((win) => {
      const b = win.document.createElement('button')
      b.setAttribute('data-qty', '1'); b.setAttribute('data-sellid', '990001'); b.setAttribute('data-stockid', '')
      b.setAttribute('data-invoice', 'BLK2-PROBE'); b.setAttribute('data-item', 'probe')
      win.openSaleReturn(b)
    })
    cy.get('#srSubmit').should('be.visible')
    holdPost('saleReturn', 'sr')
    watch('#srSubmit')
    cy.get('#srSubmit').click()
    cy.wait('@sr')
    seen().then((s) => {
      expect(s.labels).to.include(POSTING)
      expect(s.disabled).to.eq(true)
      expect(s.veil, 'REGRESSION: sale return keeps the veil until BLK-13').to.eq(true)
      expectRestored('#srSubmit', s.html0)
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
