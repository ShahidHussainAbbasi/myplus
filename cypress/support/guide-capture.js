/**
 * Shared capture for the Test Book's step-by-step cases (EDU-IDOR-2, SCHED-2, …).
 *
 * A guide spec declares a case, then for every numbered action PERFORMS it, ASSERTS it and PHOTOGRAPHS it. The
 * case is written to `<outDir>/<id>.json` after it runs, with `passed` true only if every step held, so the page
 * built from it never shows an expected result the app did not produce on this build.
 *
 *   const g = guideCapture({ outDir: 'cypress/guide-out/sched2', section: 'Parents\' evenings' })
 *   g.caseIt('P1', 'title', () => {
 *     g.testCase('P1', 'title', { tenant, role, purpose, prereq: [], data: [], rollback })
 *     const a1 = g.act('Do this.', ['Expect that.'])            // { via: 'console', code } for a no-screen step
 *     …perform + assert…
 *     g.snap(a1, 'name')                                        // or g.snap(a1, 'name', '#element')
 *   })
 *   afterEach(g.write)
 *
 * Run one case: --env guideOnly=P2
 */
export const guideCapture = ({ outDir, section, keepShots = false }) => {
  const ONLY = String(Cypress.env('guideOnly') || '').split(',').map((x) => x.trim()).filter(Boolean)
  let cur = null

  const caseIt = (id, title, fn) => ((ONLY.length && !ONLY.includes(id)) ? it.skip : it)(`${id} — ${title}`, fn)

  const testCase = (id, title, meta) => {
    cur = { id, section, title, shots: [], actions: [], cleanup: [], ...meta }
  }

  /** One numbered action with its expected results. `via: 'console'` = no screen; `via: 'run'` = the same request a screen sends. */
  const act = (text, expect, opts = {}) => {
    const a = { do: text, expect: [].concat(expect || []), shots: [], via: opts.via || 'screen', code: opts.code || null }
    ;(opts.cleanup ? cur.cleanup : cur.actions).push(a)
    return a
  }

  /** The picture for action `a`: an element, or the viewport (what the tester sees). */
  const snap = (a, name, subject) => {
    const pos = cur.actions.includes(a) ? `a${cur.actions.indexOf(a) + 1}` : `c${cur.cleanup.indexOf(a) + 1}`
    const file = `${cur.id}-${pos}-${name}`
    a.shots.push(file)
    cur.shots.push(file)
    const shot = subject ? cy.get(subject).screenshot(file, { overwrite: true })
                         : cy.screenshot(file, { capture: 'viewport', overwrite: true })
    if (!keepShots) return shot
    // keepShots: copy each picture next to the case JSON. cypress/screenshots is SHARED and any run with the default
    // trashAssetsBeforeRuns wipes it (35 of 42 dual-role pictures vanished that way between capture and build).
    return cy.readFile(`cypress/screenshots/${Cypress.spec.name}/${file}.png`, 'base64')
      .then((b64) => cy.writeFile(`${outDir}/img/${file}.png`, b64, 'base64'))
  }

  /** afterEach: record the outcome and write the case — even a failed one, so the page can say so. */
  function write() {
    if (!cur) return
    cur.passed = this.currentTest.state === 'passed'
    cur.capturedAt = new Date().toISOString()
    if (!cur.passed) cur.error = String((this.currentTest.err && this.currentTest.err.message) || '').slice(0, 400)
    cy.writeFile(`${outDir}/${cur.id}.json`, cur)
    cur = null
  }

  /** Run `code` in the page (what the tester pastes in the console) and yield the JSON its request returned. */
  const runInPage = (code, method, path) => {
    const alias = `cmd${Math.floor(Math.random() * 1e9)}`
    cy.intercept(method, `**/${path}*`).as(alias)
    cy.window().then((w) => w.eval(code))
    return cy.wait(`@${alias}`).its('response.body')
  }

  return { caseIt, testCase, act, snap, write, runInPage, current: () => cur }
}
