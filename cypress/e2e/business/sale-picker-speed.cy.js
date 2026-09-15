/**
 * PSEL-1 — a sale picker is redrawn in milliseconds, not seconds.
 *
 * Design: microservices/docs/slices/psel-1-picker-render-speed.md (§5 lists every case and the defect it catches).
 * Written BEFORE the implementation: each case states a requirement.
 *
 * Measured before (CDP profile, 2026-09-15, demo.business, 2,511 products): one #sellItemDD refresh 8.8 s; New Sale
 * one 18 s long task (two refreshes); one line reset two more refreshes (16.8 s). Cause: bootstrap-select 1.6.2
 * render() matches every row by attribute once per option — O(n²).
 *
 * ⚠ Run SOLO — it logs in as owner.business@ and demo.business@:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/sale-picker-speed.cy.js
 * Needs the monolith rebuilt with PSEL-1 (case 0 names it when it is not).
 */

/**
 * Record every selectpicker 'refresh' (any duration) and anything else ≥ 20 ms, plus the long tasks.
 * From onBeforeLoad, because cy.intercept never sees the cached bootstrap-select script. Only the PLUGIN (the first
 * assignment) is wrapped; searchable-selects' own decoration calls through it, so nothing is counted twice.
 */
function instrument(win) {
  const rec = { calls: [], longtasks: [] }
  win.__psel = rec
  try {
    new win.PerformanceObserver((list) => list.getEntries().forEach((e) =>
      rec.longtasks.push({ start: e.startTime, dur: e.duration })))
      .observe({ type: 'longtask', buffered: true })
  } catch (e) { /* long tasks unsupported: the per-call durations still answer */ }
  const wrap = (orig) => {
    const w = function (...args) {
      const cmd = typeof args[0] === 'string' ? args[0] : 'construct'
      const t0 = win.performance.now()
      try {
        return orig.apply(this, args)
      } finally {
        const dur = win.performance.now() - t0
        if (cmd === 'refresh' || dur >= 20) {
          rec.calls.push({ t: t0, dur, cmd, ids: this.toArray().map((el) => el.id || el.tagName) })
        }
      }
    }
    Object.keys(orig).forEach((k) => { w[k] = orig[k] })
    return w
  }
  const hook = (jq) => {
    if (!jq || !jq.fn || jq.fn.__pselHooked) return
    let current = jq.fn.selectpicker ? wrap(jq.fn.selectpicker) : undefined
    let wrapped = !!current
    Object.defineProperty(jq.fn, 'selectpicker', {
      configurable: true, enumerable: true,
      get() { return current },
      set(v) { if (!wrapped) { current = wrap(v); wrapped = true } else { current = v } },
    })
    jq.fn.__pselHooked = true
  }
  let jq = win.jQuery
  Object.defineProperty(win, 'jQuery', { configurable: true, get() { return jq }, set(v) { jq = v; hook(v) } })
}

/** What happened since t0: refreshes of one picker, and the longest main-thread block. */
const since = (win, t0) => ({
  refreshes: (id) => win.__psel.calls.filter((c) => c.t >= t0 && c.cmd === 'refresh' && c.ids.includes(id)),
  longest: () => Math.max(0, ...win.__psel.longtasks.filter((l) => l.start >= t0).map((l) => l.dur)),
})

/** A picker's options vs rendered rows, its button's disabled look and label (bootstrap-select 1.6.2 markup). */
const pickerState = (win, id) => {
  const $s = win.jQuery('#' + id)
  const $bs = $s.next('.bootstrap-select')
  return {
    options: $s.find('option').length,
    lis: $bs.find('ul li').length,
    btnDisabled: $bs.find('button.dropdown-toggle').hasClass('disabled'),
    label: $bs.find('.filter-option').text().trim(),
    rowsDisabled: $bs.find('li[data-original-index]').filter('.disabled').length,
    rows: $bs.find('li[data-original-index]').length,
  }
}

/** Open New Sale on an instrumented page; yields the time the sale was asked for. */
const openSale = () => {
  cy.visit('/businessDashboard', { onBeforeLoad: instrument })
  cy.waitForAppReady()
  let t0 = 0
  cy.window().then((w) => { t0 = w.performance.now() })
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellDiv', { timeout: 30000 }).should('be.visible')
  cy.window({ timeout: 90000 }).should((w) => {
    const s = pickerState(w, 'sellItemDD')
    expect(s.options > 1 && s.lis >= s.options, `#sellItemDD rebuilt ${JSON.stringify(s)}`).to.eq(true)
  })
  cy.waitForAppReady()
  return cy.then(() => t0)
}

describe('PSEL-1 — a sale picker is redrawn in milliseconds, not seconds', () => {
  it('0 — the served build carries PSEL-1', () => {
    cy.loginAsOwner()
    cy.request('/js/common/searchable-selects.js').its('body')
      .should('contain', 'repaintSearchableSelect').and('contain', '__ssFast')
    cy.request('/js/main.js').its('body').should('contain', 'function repaintPicker')
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => {
      const P = w.jQuery.fn.selectpicker.Constructor.prototype
      expect(P.render.__ssFast, 'the one-pass render is installed on bootstrap-select 1.6.2').to.eq(true)
      expect(P.render.__ssOriginal, 'the original stays reachable for case 1').to.be.a('function')
    })
  })

  it('⭐⭐ 1 — the fast render draws EXACTLY what the original did', () => {
    /*
     * The regression a "faster but different" render would introduce. Two identical pickers; after the same change,
     * one is redrawn by the library's own O(n²) render and one by the fast one — their menus must be identical.
     * Options include disabled ones, a disabled optgroup and a normal one; single and multiple selects.
     */
    cy.loginAsOwner()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => {
      const $ = w.jQuery
      const P = $.fn.selectpicker.Constructor.prototype
      const build = (id, multiple) => {
        let h = ''
        for (let i = 0; i < 300; i++) h += `<option value="v${i}"${i % 7 === 3 ? ' disabled' : ''}>Option ${i}</option>`
        h += '<optgroup label="Locked" disabled><option value="g1">G1</option><option value="g2">G2</option></optgroup>'
        h += '<optgroup label="Open"><option value="g3">G3</option></optgroup>'
        const $s = $(`<select id="${id}"${multiple ? ' multiple' : ''}>${h}</select>`).appendTo(w.document.body)
        $s.selectpicker()
        return $s
      }
      const scenario = (label, change) => {
        ;[false, true].forEach((multiple) => {
          const $a = build('pselEqA', multiple)
          const $b = build('pselEqB', multiple)
          change($a)
          change($b)
          const ia = $a.data('selectpicker')
          const ib = $b.data('selectpicker')
          P.render.__ssOriginal.call(ia)   // the library's own render
          ib.render()                      // the one-pass render
          const norm = (html) => html.replace(/pselEq[AB]/g, 'X')
          expect(norm(ib.$menu.html()), `${label} (multiple=${multiple}): the rows are identical`)
            .to.eq(norm(ia.$menu.html()))
          expect(ib.$button.text(), `${label} (multiple=${multiple}): the button label is identical`)
            .to.eq(ia.$button.text())
          $a.selectpicker('remove')
          $b.selectpicker('remove')
        })
      }
      scenario('a selection changed', ($s) => {
        $s.find('option[value="v10"]').prop('selected', true)
        $s.find('option[value="g3"]').prop('selected', true)
      })
      scenario('options disabled and enabled after build', ($s) => {
        $s.find('option[value="v11"]').prop('disabled', true)
        $s.find('option[value="v3"]').prop('disabled', false)
      })
      scenario('the whole select disabled', ($s) => { $s.prop('disabled', true) })
    })
  })

  ;[
    ['owner.business', () => cy.loginAsOwner()],
    ['demo.business', () => cy.loginAsBusiness()],   // the largest catalogue — the 18 s case
  ].forEach(([who, login]) => {
    it(`⭐⭐ 2 — New Sale opens without a long freeze (${who})`, () => {
      login()
      openSale().then((t0) => {
        cy.window().then((w) => {
          const s = since(w, t0)
          const r = s.refreshes('sellItemDD')
          const item = pickerState(w, 'sellItemDD')
          cy.log(`#sellItemDD ${item.options} options · refreshes ${r.map((c) => Math.round(c.dur) + ' ms').join(', ') || 'none'}`
            + ` · longest task ${Math.round(s.longest())} ms`)
          expect(r.length, 'at most ONE rebuild of the product picker when New Sale opens (was two)').to.be.at.most(1)
          r.forEach((c) => expect(c.dur, `a product-picker rebuild took ${Math.round(c.dur)} ms (was 8,815 ms)`)
            .to.be.lessThan(2000))
          expect(s.longest(), `the longest main-thread block was ${Math.round(s.longest())} ms (was 17,971 ms)`)
            .to.be.lessThan(3000)
        })
      })
    })
  })

  it('⭐⭐ 3 — clearing a line repaints the pickers instead of rebuilding them', () => {
    cy.loginAsOwner()
    openSale()
    let product = ''
    let t0 = 0
    cy.window().then((w) => {
      const $ = w.jQuery
      const $opt = $('#sellItemDD option').filter((i, o) => /^\d+$/.test(o.value)).first()
      product = $opt.text().trim()
      $('#sellItemDD').val($opt.val()).selectpicker('render')
      expect(pickerState(w, 'sellItemDD').label, 'a product is on the button before the reset').to.contain(product)
      t0 = w.performance.now()
      w.document.getElementById('resetInviceItem').click()   // what Esc and Cancel do
    })
    cy.wait(600)
    cy.waitForAppReady()
    cy.window().then((w) => {
      const s = since(w, t0)
      const st = pickerState(w, 'sellItemDD')
      expect(s.refreshes('sellItemDD'), '⭐⭐ a reset rebuilds nothing (it rebuilt the product picker twice, 16.8 s)')
        .to.have.length(0)
      expect(s.longest(), `the longest main-thread block was ${Math.round(s.longest())} ms`).to.be.lessThan(1000)
      expect(st.lis, 'every row is still there').to.eq(st.options)
      expect(st.label, '⭐ the button shows the POST-reset selection — the old handler drew the pre-reset one')
        .to.not.contain(product)
    })
  })

  it('⭐ 4 — the calls a cart add makes no longer rebuild the product picker', () => {
    cy.loginAsOwner()
    openSale()
    let t0 = 0
    cy.window().then((w) => {
      t0 = w.performance.now()
      // business.js:264-265 after a line is added, then the reset buttons' own handler (main.js:1184).
      w.resetForm()
      w.resetBSDD('sellItemDD')
      w.updateReadOnly(false)
    })
    cy.wait(600)
    cy.waitForAppReady()
    cy.window().then((w) => {
      const s = since(w, t0)
      expect(s.refreshes('sellItemDD'), 'no rebuild (each of the three calls used to rebuild it)').to.have.length(0)
      expect(s.longest(), `the longest main-thread block was ${Math.round(s.longest())} ms`).to.be.lessThan(1000)
      const st = pickerState(w, 'sellItemDD')
      expect(st.lis, 'every row is still there').to.eq(st.options)
    })
  })

  it('⭐⭐ 5 — a picker whose OPTIONS changed is still rebuilt', () => {
    /*
     * The regression a repaint-only shortcut would introduce — and the microtask trap: the MutationObserver reports on
     * a microtask, so resetBSDD runs in the SAME tick as the append, before any report. It must still rebuild.
     */
    cy.loginAsOwner()
    openSale()
    cy.window().then((w) => {
      const $ = w.jQuery
      const t0 = w.performance.now()
      $('#sellItemDD').append('<option value="99999991">PSEL probe option</option>')
      w.resetBSDD('sellItemDD')
      expect(since(w, t0).refreshes('sellItemDD'), '⭐⭐ options changed ⇒ a real rebuild').to.have.length(1)
      const rows = $('#sellItemDD').next('.bootstrap-select').find('li a')
        .filter((i, a) => /PSEL probe option/.test(a.textContent)).length
      expect(rows, 'the new option has its row').to.eq(1)
      $('#sellItemDD option[value="99999991"]').remove()   // leave the picker as it was found
      w.resetBSDD('sellItemDD')
    })
  })

  it('⭐ 6 — locking and unlocking the product picker repaints it, with no rebuild', () => {
    /*
     * bootstrap-select 1.6.2 applies the select-level disabled look in checkDisabled(), which only refresh() used to
     * call — so a repaint that skipped it would leave a locked picker looking enabled (flagged by myplus-f9).
     */
    cy.loginAsOwner()
    openSale()
    cy.window().then((w) => {
      const t0 = w.performance.now()
      w.updateReadOnly(true)
      let st = pickerState(w, 'sellItemDD')
      expect(st.btnDisabled, 'locked: the button looks disabled').to.eq(true)
      expect(st.rowsDisabled, 'locked: every row is disabled').to.eq(st.rows)
      w.updateReadOnly(false)
      st = pickerState(w, 'sellItemDD')
      expect(st.btnDisabled, 'unlocked: the button is enabled again').to.eq(false)
      expect(st.rowsDisabled, 'unlocked: no row is left disabled').to.eq(0)
      expect(since(w, t0).refreshes('sellItemDD'), 'a lock toggle rebuilds nothing').to.have.length(0)
    })
  })
})
