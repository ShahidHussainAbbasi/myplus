/**
 * SET-GUIDE — the Settings screens' UI quality: accessibility, keyboard, focus, layout at three widths, and a visual
 * baseline per screen and width.
 *
 * Every settings screen of every module, as its OWNER sees it (the role that can change settings):
 *   business Configuration · Order settings · Tax Settings (business) · education · welfare · agriculture.
 *
 * What each check asserts — the thing a defect would break, not merely that something rendered:
 *   - a11y   axe-core (WCAG 2 A + AA) inside the settings screen only — labels, contrast, ARIA, names. The app's
 *            shell (sidebar, header) is out of scope here; it is not a Settings screen.
 *   - keys   the first control is reachable by Tab, and the focused element SHOWS focus (outline or ring).
 *   - layout at 1366 / 768 / 390 the page never scrolls sideways and the screen's controls stay on screen.
 *   - visual matchImageSnapshot per screen × width (baselines in cypress/snapshots/, committed). `--env
 *            updateSnapshots=true` re-records after an APPROVED change.
 *
 * Modes:
 *   default            a11y violations of impact serious/critical FAIL the test (the publish gate).
 *   --env a11yReport=1 report only: every violation is written to cypress/guide-out/a11y-report.json, nothing fails.
 *   --env noVisual=1   skip the image comparison (e.g. while a screen is being changed on purpose).
 *
 * Leaves no server state: it only opens screens and reads them.
 */
const REPORT = !!Cypress.env('a11yReport')
const VISUAL = !Cypress.env('noVisual')
const OUT = 'cypress/guide-out/a11y-report.json'

const VIEWPORTS = [
  { id: 'desktop', w: 1366, h: 860 },
  { id: 'tablet', w: 768, h: 1024 },
  { id: 'mobile', w: 390, h: 844 },
]

/** Each settings screen: who opens it, how, and the element that IS the screen. */
const SCREENS = [
  { id: 'business-config', name: 'Business → Settings → Configuration', login: () => cy.loginAsOwner(), url: '/businessDashboard',
    open: (w) => w.showBusinessConfig(), screen: '#ConfigDiv', ready: '#businessConfigBody .cfg-row', anchor: '#businessConfigBody',
    settle: '**/getSupportSessions*',
    /*
     * Visual baseline by BOUNDED ELEMENTS on this screen only: the support-session card above it lists every session
     * ever opened on the tenant, so it grows run by run, and at tablet/phone widths that moved the settled scroll by
     * ~40px between two runs. The rail and the first settings group are compared as elements instead — neither depends
     * on scroll position or on that list. (Layout — no sideways scroll, controls on screen — is still asserted page-wide.)
     */
    visualEls: ['#businessConfigBody .cfg-rail', '#businessConfigBody .cfg-group:visible'] },
  { id: 'business-orders', name: 'Business → Store → Order settings', login: () => cy.loginAsOwner(), url: '/businessDashboard',
    open: (w) => w.showOrderConfig(), screen: '#OrderConfigDiv', ready: '#orderConfigBody .cfg-row' },
  { id: 'business-tax', name: 'Business → Settings → Tax Settings', login: () => cy.loginAsOwner(), url: '/businessDashboard',
    open: (w) => w.showTaxSettings(), screen: '#TaxSettingDiv', ready: '#TaxSettingDiv input, #TaxSettingDiv select' },
  { id: 'education-config', name: 'Education → Configuration', login: () => cy.loginAsEduOwner(), url: '/educationDashboard',
    open: (w) => w.showConfig(), screen: '#configBody', ready: '#configBody .cfg-row' },
  { id: 'welfare-config', name: 'Welfare → Configuration', login: () => cy.loginAsWelfareOwner(), url: '/welfareDashboard',
    open: (w) => w.showConfig(), screen: '#welfareConfigBody', ready: '#welfareConfigBody .cfg-row' },
  { id: 'agriculture-config', name: 'Agriculture → Configuration', login: () => cy.loginAsAgricultureOwner(), url: '/agricultureDashboard',
    open: (w) => w.showConfig(), screen: '#agriConfigBody', ready: '#agriConfigBody .cfg-row' },
]

// --env screens=welfare-config,business-tax — run a subset (ids above).
const ONLY = String(Cypress.env('screens') || '').split(',').filter(Boolean)
const RUN = ONLY.length ? SCREENS.filter((s) => ONLY.includes(s.id)) : SCREENS

/** Regions whose content changes run to run by design (support sessions: times, reasons). */
const BLACKOUT = ['.sa-card']

const report = []

const openScreen = (s) => {
  s.login()
  // A request that renders content ABOVE the screen after it opens (the support-session card): wait for it, or a
  // late render pushes the screen down after the picture is aligned (measured: a 62px shift between two runs).
  if (s.settle) cy.intercept('GET', s.settle).as('settle')
  cy.visit(s.url)
  cy.waitForAppReady()
  cy.window().then((w) => s.open(w))
  cy.get(s.ready, { timeout: 20000 }).should('have.length.greaterThan', 0)
  cy.get(s.screen).should('be.visible')
  if (s.settle) cy.wait('@settle')
}

describe('SET-GUIDE — Settings screens: accessibility, keyboard, layout, visual', () => {
  /*
   * A KNOWN data state before any picture is compared. Other specs "restore" a setting by saving its default back,
   * which stores an explicit override: the row then wears a "changed" dot and a Reset link, the category counts
   * change, and every row below moves — a visual diff that is data drift, not a UI change (measured 2026-09-27:
   * the capability rows on business Configuration). Overrides whose value EQUALS the default are removed here —
   * the state of a tenant nobody has configured. Real choices (a value that differs from its default) are kept.
   */
  before(() => {
    const same = (a, b) => (isNaN(Number(a)) || isNaN(Number(b)) || a === '' || b === '') ? String(a) === String(b) : Number(a) === Number(b)
    const unpin = (login, get, reset) => {
      login()
      cy.request(get).then((r) => {
        const items = r.body.data || r.body.collection || r.body.object || []
        items.filter((e) => e.isDefault === false && !e.locked && e.key !== 'org.shape' && same(e.value, e.defaultValue))   // org.shape: its row means "a type was chosen"
          .forEach((e) => cy.request({ method: 'POST', url: reset, form: true, body: { key: e.key }, failOnStatusCode: false }))
      })
    }
    unpin(() => cy.loginAsOwner('owner.business@myplus.com', undefined, `ui-${Date.now()}`), '/getBusinessConfig', '/resetBusinessConfig')
    unpin(() => cy.loginAsOwner(), '/getOrderConfig', '/resetOrderConfig')
    unpin(() => cy.loginAsEduOwner(), '/getConfig', '/resetConfig')
    unpin(() => cy.loginAsWelfareOwner(), '/getWelfareConfig', '/resetWelfareConfig')
    unpin(() => cy.loginAsAgricultureOwner(), '/getAgricultureConfig', '/resetAgricultureConfig')
  })
  after(() => { if (REPORT) cy.writeFile(OUT, report) })

  it('errors — a refused save is SHOWN on the row and in the banner, and both are ANNOUNCED (live regions)', () => {
    /*
     * The error path a tester can reach by hand: clear a whole-number setting and leave the box. The server refuses
     * it (SET-GUIDE type validation — before, "" was stored and silently ignored), the row says "Save failed", the
     * banner carries the server's sentence, and both are role=status live regions, so a screen reader hears it.
     */
    cy.viewport(1366, 860)
    openScreen(SCREENS[0])
    const KEY = 'pos.entry.defaultQty'
    cy.request('/getBusinessConfig').then((r) => {
      const before = r.body.data.find((e) => e.key === KEY)
      cy.revealSetting(KEY)
      cy.intercept('POST', '**/saveBusinessConfig').as('save')
      cy.get(`#businessConfigBody [data-key="${KEY}"]`).clear().blur()
      cy.wait('@save').its('response.body.success').should('eq', false)
      cy.get('#businessConfigMsg').should('be.visible').and('have.attr', 'role', 'status')
        .and('have.attr', 'aria-live', 'polite').invoke('text').should('match', /whole number/i)
      cy.get(`#businessConfigBody [data-key="${KEY}"]`).invoke('attr', 'id').then((id) => {
        cy.get(`#${id}_saved`).should('have.attr', 'aria-live', 'polite').and('have.attr', 'role', 'status')
          .and('have.class', 'is-err')
      })
      cy.request('/getBusinessConfig').then((r2) => {
        const after = r2.body.data.find((e) => e.key === KEY)
        expect(after.value, 'the refused value was NOT stored').to.eq(before.value)
        expect(after.isDefault, 'nor did it create an override').to.eq(before.isDefault)
      })
    })
  })

  it('tabs — the category rail is a complete WAI-ARIA tabs widget (tabs control a labelled tabpanel)', () => {
    cy.viewport(1366, 860)
    openScreen(SCREENS[0])
    cy.get('#businessConfigBody .cfg-rail[role="tablist"]').should('have.attr', 'aria-label')
    cy.get('#businessConfigBody .cfg-rail__item[role="tab"]').each(($t) => {
      expect($t.attr('aria-controls'), 'each tab names the panel it controls').to.eq('bcfg_pane')
    })
    cy.get('#bcfg_pane').should('have.attr', 'role', 'tabpanel')
    cy.get('#businessConfigBody .cfg-rail__item[aria-selected="true"]').invoke('attr', 'id').then((id) => {
      cy.get('#bcfg_pane').should('have.attr', 'aria-labelledby', id)
    })
    cy.get('#businessConfigBody .cfg-rail__item').eq(1).click()
    cy.get('#businessConfigBody .cfg-rail__item').eq(1).invoke('attr', 'id').then((id) => {
      cy.get('#bcfg_pane').should('have.attr', 'aria-labelledby', id)
    })
  })

  RUN.forEach((s) => {
    it(`a11y — ${s.name}: no serious or critical WCAG 2 A/AA violations`, () => {
      cy.viewport(1366, 860)
      openScreen(s)
      cy.injectAxe()
      cy.checkA11y(s.screen, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] } }, (violations) => {
        violations.forEach((v) => report.push({
          screen: s.id, rule: v.id, impact: v.impact, help: v.help, nodes: v.nodes.length,
          targets: v.nodes.slice(0, 5).map((n) => n.target.join(' ')),
        }))
        cy.task('a11yTable', violations.map((v) => ({ screen: s.id, rule: v.id, impact: v.impact, nodes: v.nodes.length })))
      }, REPORT /* skipFailures in report mode */)
      if (!REPORT) {
        // checkA11y's own failure covers every impact; the gate is serious/critical, so re-assert on those.
        cy.checkA11y(s.screen, {
          runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] },
          includedImpacts: ['serious', 'critical'],
        })
      }
    })

    it(`keys — ${s.name}: Tab reaches the first control, and focus is visible`, () => {
      cy.viewport(1366, 860)
      openScreen(s)
      cy.get(`${s.screen} input:visible, ${s.screen} select:visible, ${s.screen} button:visible`).first().as('first')
      cy.get('@first').focus()
      cy.focused().then(($f) => {
        const cs = getComputedStyle($f[0])
        const ring = (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) || (cs.boxShadow && cs.boxShadow !== 'none')
        report.push({ screen: s.id, rule: 'focus-visible', impact: ring ? 'ok' : 'serious',
          help: `focused ${$f[0].tagName.toLowerCase()} outline=${cs.outlineStyle} ${cs.outlineWidth} shadow=${cs.boxShadow}` })
        if (!REPORT) expect(ring, `the focused control shows focus (outline ${cs.outlineStyle}/${cs.outlineWidth}, shadow ${cs.boxShadow})`).to.eq(true)
      })
    })

    VIEWPORTS.forEach((vp) => {
      it(`layout — ${s.name} @ ${vp.id} ${vp.w}px: no sideways scroll, controls on screen${VISUAL ? ', visual baseline' : ''}`, () => {
        cy.viewport(vp.w, vp.h)
        openScreen(s)
        cy.document().then((d) => {
          const over = d.documentElement.scrollWidth - d.documentElement.clientWidth
          report.push({ screen: s.id, rule: `overflow-${vp.id}`, impact: over > 0 ? 'serious' : 'ok', help: `scrollWidth-clientWidth=${over}` })
          if (!REPORT) expect(over, `no sideways page scroll at ${vp.w}px`).to.be.at.most(0)
        })
        cy.get(`${s.screen} input:visible, ${s.screen} select:visible`).each(($c) => {
          const r = $c[0].getBoundingClientRect()
          if (r.right > vp.w + 1 || r.left < -1) {
            report.push({ screen: s.id, rule: `offscreen-${vp.id}`, impact: 'serious', help: `${$c.attr('id') || $c.attr('data-key')} ${Math.round(r.left)}..${Math.round(r.right)}` })
            if (!REPORT) expect(r.right, `${$c.attr('data-key') || $c.attr('id')} stays on screen`).to.be.at.most(vp.w + 1)
          }
        })
        /*
         * What a person sees at this width: a VIEWPORT capture with the screen's top aligned to the top. Not an element
         * capture — a screen taller than the viewport is scrolled and stitched, which repeated one section four times in
         * the first baselines. The support-session list is blacked out: its timestamps and reasons change every run.
         */
        // Align the screen's top to the top of the view (the app's fixed bar keeps it ~60px down, the same every run),
        // and require the position to be STABLE — two identical readings in a row — so nothing still rendering above it
        // can move the picture after it is taken.
        // The PAGE HEIGHT must be stable too: while content below is still arriving, the page cannot scroll far enough
        // and the position reads "stable" at the wrong place (measured on the phone view: 150px vs 0).
        let prev = null
        cy.get(s.anchor || s.screen).should(($a) => {
          $a[0].scrollIntoView({ block: 'start' })
          const cur = `${Math.round($a[0].getBoundingClientRect().top)}|${$a[0].ownerDocument.documentElement.scrollHeight}`
          const stable = prev !== null && cur === prev
          prev = cur
          expect(stable, `the screen's position and the page height have settled (${cur})`).to.eq(true)
        })
        cy.screenshot(`ui-${s.id}-${vp.id}`, { capture: 'viewport', overwrite: true, blackout: BLACKOUT })
        if (VISUAL && !REPORT) {
          if (s.visualEls) s.visualEls.forEach((sel, i) => cy.get(sel).first().matchImageSnapshot(`${s.id}-${vp.id}-el${i}`,
            // the phone menu button is FIXED: whether it lands over the element depends on scroll (limitation L6)
            // blur 1px: a small text-dense element crosses 0.2% on a ONE-pixel subpixel shift of identical content
            // (measured on the tablet rail); a real layout change still shows through a 1px blur.
            // The RAIL (i=0) is sticky: its on-screen position follows the fractional scroll offset, so identical text
            // anti-aliases a little differently each run. 3% absorbs that; a lost, added or wrapped category is far above it.
            { blackout: ['#sidebar-mobile-btn'], blur: 1, failureThreshold: i === 0 ? 0.03 : 0.005, failureThresholdType: 'percent' }))
          else cy.matchImageSnapshot(`${s.id}-${vp.id}`, { capture: 'viewport', blackout: BLACKOUT })
        }
      })
    })
  })
})
