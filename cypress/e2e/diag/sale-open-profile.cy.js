/**
 * DIAGNOSTIC — not a gate. Untracked on purpose: delete it once its numbers are read; never stage it.
 *
 * Plan item #1, the New Sale freeze. diag-sale-open MEASURED the cost (one bootstrap-select 1.6.2 refresh of
 * #sellItemDD = 3.4–7.0 s of main thread); this measures its COMPOSITION, because the fix must make one refresh
 * cheap — moving it again only moves the freeze again. Nothing here changes product code.
 *
 *   A — CPU profile from "New Sale asked" until both sale pickers are rebuilt and the app is quiet.
 *   B — CPU profile of ONE line reset (#resetInviceItem → form reset). Tests myplus-f9's lead: main.js:304-314
 *       refreshes EVERY picker in form#Sell on reset, i.e. a full product-picker rebuild per cleared line.
 *
 * Also: every $.fn.selectpicker call ≥ 20 ms with its duration and the APP frames that made it (instrumented from
 * onBeforeLoad — cy.intercept never saw the cached bootstrap-select script), and the long tasks.
 *
 * Output per account: cypress/results/sale-open-profile-<account>.json (summary) and
 * cypress/results/sale-open-profile-<account>-A.cpuprofile / -B.cpuprofile (Chrome DevTools → Performance →
 * "Load profile" to see the flame chart).
 *
 * Chrome only (CDP). Run SOLO, headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/diag/sale-open-profile.cy.js
 */

const cdp = (command, params = {}) => Cypress.automation('remote:debugger:protocol', { command, params })

/** Time every $.fn.selectpicker call and name the app code that made it. */
function instrument(win, state) {
  state.calls = []
  state.longtasks = []
  try {
    new win.PerformanceObserver((list) => list.getEntries().forEach((e) =>
      state.longtasks.push({ start: Math.round(e.startTime), dur: Math.round(e.duration) })))
      .observe({ type: 'longtask', buffered: true })
  } catch (e) { /* long tasks unsupported: the profile still answers */ }

  // The frames that MADE the call — library and harness frames stripped, or every entry reads "jquery".
  const callerOf = (stack) => String(stack || '').split('\n').slice(2)
    .map((l) => l.trim())
    .filter((l) => l && !/jquery|bootstrap-select|searchable-selects|__cypress|cypress_runner/i.test(l))
    .slice(0, 3)
    .map((l) => l.replace(/^at\s+/, '').replace(/https?:\/\/[^/)]+/g, ''))

  const wrap = (orig) => {
    const w = function (...args) {
      const cmd = typeof args[0] === 'string' ? args[0] : 'construct'
      const t0 = win.performance.now()
      try {
        return orig.apply(this, args)
      } finally {
        const dur = win.performance.now() - t0
        if (dur >= 20) {
          state.calls.push({
            t: Math.round(t0), dur: Math.round(dur), cmd, n: this.length,
            ids: this.toArray().slice(0, 6).map((el) => el.id || el.tagName),
            by: callerOf(new Error().stack),
          })
        }
      }
    }
    Object.keys(orig).forEach((k) => { w[k] = orig[k] })
    return w
  }

  // Wrap the PLUGIN (the first assignment) exactly once. searchable-selects later decorates $.fn.selectpicker
  // itself; that assignment is kept as-is and calls through this wrapper, so nothing is timed twice.
  const hookFn = (jq) => {
    if (!jq || !jq.fn || jq.fn.__diagHooked) return
    let current = jq.fn.selectpicker ? wrap(jq.fn.selectpicker) : undefined
    let wrapped = !!current
    Object.defineProperty(jq.fn, 'selectpicker', {
      configurable: true, enumerable: true,
      get() { return current },
      set(v) { if (!wrapped) { current = wrap(v); wrapped = true } else { current = v } },
    })
    jq.fn.__diagHooked = true
  }
  let jq = win.jQuery
  Object.defineProperty(win, 'jQuery', { configurable: true, get() { return jq }, set(v) { jq = v; hookFn(v) } })
}

/** Self and inclusive time per function, from a CDP Profiler profile. */
function summarise(profile) {
  const nodes = new Map(profile.nodes.map((n) => [n.id, n]))
  const parent = new Map()
  profile.nodes.forEach((n) => (n.children || []).forEach((c) => parent.set(c, n.id)))

  const self = new Map()
  const { samples, timeDeltas } = profile
  for (let i = 0; i < samples.length; i++) {
    const dt = (timeDeltas[i + 1] != null ? timeDeltas[i + 1] : 0) / 1000   // ms until the next sample
    self.set(samples[i], (self.get(samples[i]) || 0) + dt)
  }

  const short = (u) => (u || '').replace(/^https?:\/\/[^/]+/, '').replace(/\?.*$/, '')
  const keyOf = (n) => `${n.callFrame.functionName || '(anonymous)'} ${short(n.callFrame.url)}`
    + `:${n.callFrame.lineNumber + 1}:${n.callFrame.columnNumber + 1}`

  // Inclusive per node, post-order, iteratively (a recursive walk can exceed the stack on a deep profile).
  const incl = new Map()
  const roots = profile.nodes.filter((n) => !parent.has(n.id)).map((n) => n.id)
  const post = []
  const stack = roots.map((id) => [id, false])
  while (stack.length) {
    const [id, done] = stack.pop()
    if (done) { post.push(id); continue }
    stack.push([id, true])
    ;(nodes.get(id).children || []).forEach((c) => stack.push([c, false]))
  }
  post.forEach((id) => {
    const kids = nodes.get(id).children || []
    incl.set(id, (self.get(id) || 0) + kids.reduce((a, c) => a + (incl.get(c) || 0), 0))
  })

  // Per function: self summed; inclusive summed only where no ancestor is the same function (recursion).
  const selfByKey = new Map()
  const inclByKey = new Map()
  profile.nodes.forEach((n) => {
    const k = keyOf(n)
    selfByKey.set(k, (selfByKey.get(k) || 0) + (self.get(n.id) || 0))
    let p = parent.get(n.id)
    let nested = false
    while (p != null) { if (keyOf(nodes.get(p)) === k) { nested = true; break } p = parent.get(p) }
    if (!nested) inclByKey.set(k, (inclByKey.get(k) || 0) + (incl.get(n.id) || 0))
  })

  const top = (m, keep, n = 40) => [...m.entries()].filter(([k]) => keep(k)).sort((a, b) => b[1] - a[1])
    .slice(0, n).map(([fn, ms]) => ({ fn, ms: Math.round(ms) }))
  const bucket = (prefix) => Math.round([...selfByKey.entries()].filter(([k]) => k.startsWith(prefix))
    .reduce((a, [, v]) => a + v, 0))

  return {
    totalMs: Math.round([...self.values()].reduce((a, b) => a + b, 0)),
    idleMs: bucket('(idle)'),
    programMs: bucket('(program)'),      // native work V8 cannot attribute to a JS frame
    gcMs: bucket('(garbage collector)'),
    topSelf: top(selfByKey, (k) => !/^\((idle|program|root)\)/.test(k)),
    topInclusiveApp: top(inclByKey, (k) => /\/(js|bootstrap)\//.test(k)),
    bootstrapSelect: top(inclByKey, (k) => /bootstrap-select/.test(k), 25),
    samplingUs: profile.timeDeltas.length ? Math.round(
      profile.timeDeltas.reduce((a, b) => a + b, 0) / profile.timeDeltas.length) : null,
  }
}

/** Options vs rendered rows for a sale picker (bootstrap-select 1.6.2 puts its widget beside the <select>). */
const pickerState = (win, id) => {
  const $s = win.jQuery('#' + id)
  const $bs = $s.next('.bootstrap-select').length ? $s.next('.bootstrap-select') : $s.closest('.bootstrap-select')
  return { id, options: $s.find('option').length, lis: $bs.find('ul li').length }
}

const ACCOUNTS = [
  { tag: 'demo-business', login: () => cy.loginAsBusiness() },    // 2,390 products — the 7 s case
  { tag: 'owner-business', login: () => cy.loginAsOwner() },      // 1,857 products + 1,615 customers
]

describe('DIAG — where a New Sale picker rebuild spends its time', () => {
  ACCOUNTS.forEach(({ tag, login }) => {
    it(`profiles New Sale opening and one line reset — ${tag}`, () => {
      const state = {}
      const out = { account: tag, when: new Date().toISOString() }
      login()
      cy.visit('/businessDashboard', { onBeforeLoad: (win) => instrument(win, state) })
      cy.waitForAppReady()

      // ── A: New Sale opens ────────────────────────────────────────────────────────────────────────────
      cy.wrap(null).then(() => cdp('Profiler.enable'))
      cy.wrap(null).then(() => cdp('Profiler.setSamplingInterval', { interval: 250 }))
      cy.wrap(null).then(() => cdp('Profiler.start'))
      cy.window().then((w) => { out.saleAskedAt = Math.round(w.performance.now()) })
      cy.get('#sellType').select('sellDiv', { force: true })
      cy.get('#sellDiv', { timeout: 30000 }).should('be.visible')
      cy.window({ timeout: 90000 }).should((w) => {
        const item = pickerState(w, 'sellItemDD')
        const cust = pickerState(w, 'sellCustomerDD')
        expect(item.options > 1 && item.lis >= item.options, `sellItemDD rebuilt ${JSON.stringify(item)}`).to.eq(true)
        expect(cust.options > 1 && cust.lis >= cust.options, `sellCustomerDD rebuilt ${JSON.stringify(cust)}`).to.eq(true)
      })
      cy.waitForAppReady()
      cy.wait(1500)   // trailing tasks belong in the profile too
      cy.wrap(null).then({ timeout: 120000 }, () => cdp('Profiler.stop')).then((res) => {
        out.A = summarise(res.profile)
        cy.writeFile(`cypress/results/sale-open-profile-${tag}-A.cpuprofile`, res.profile)
      })
      cy.window().then((w) => {
        out.A.pickers = [pickerState(w, 'sellItemDD'), pickerState(w, 'sellCustomerDD')]
        out.A.calls = state.calls.filter((c) => c.t >= out.saleAskedAt)
        out.A.longtasks = state.longtasks.filter((l) => l.start >= out.saleAskedAt)
        out.resetAt = Math.round(w.performance.now())
      })

      // ── B: one line reset ────────────────────────────────────────────────────────────────────────────
      cy.wrap(null).then(() => cdp('Profiler.start'))
      cy.window().then((w) => {
        const btn = w.document.getElementById('resetInviceItem')
        out.resetButton = btn ? { type: btn.type, visible: btn.offsetParent !== null } : null
        if (btn) btn.click()   // native type=reset → form reset → main.js's reset handler
      })
      cy.wait(4000)
      cy.waitForAppReady()
      cy.wrap(null).then({ timeout: 120000 }, () => cdp('Profiler.stop')).then((res) => {
        out.B = summarise(res.profile)
        cy.writeFile(`cypress/results/sale-open-profile-${tag}-B.cpuprofile`, res.profile)
      })
      cy.window().then((w) => {
        out.B.calls = state.calls.filter((c) => c.t >= out.resetAt)
        out.B.longtasks = state.longtasks.filter((l) => l.start >= out.resetAt)
        out.B.pickers = [pickerState(w, 'sellItemDD'), pickerState(w, 'sellCustomerDD')]
      })
      cy.wrap(null).then(() => cdp('Profiler.disable'))
      cy.then(() => cy.writeFile(`cypress/results/sale-open-profile-${tag}.json`, out))
    })
  })
})
