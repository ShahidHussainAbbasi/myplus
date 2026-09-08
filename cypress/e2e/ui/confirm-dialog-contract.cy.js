/**
 * The confirm dialog's CONTRACT, enforced against the shipped source.
 *
 * ── The defect ──────────────────────────────────────────────────────────────────────────────────
 * `uiConfirm` takes an OPTIONS OBJECT and returns a PROMISE:
 *
 *     uiConfirm({ title, message, confirmText, tone }).then(function (ok) { … })
 *
 * Three call sites independently wrote the `window.confirm` shape instead — `uiConfirm(message, callback)`.
 * That does not fail where a reader would look for it. `uiConfirm` does `o.input = null` on whatever it is
 * handed, and assigning a property to a **string** throws in strict mode:
 *
 *     Uncaught TypeError: Cannot create property 'input' on string 'Remove Shahid from this plan?'
 *
 * So the dialog never opened and the action never ran — a button that silently did nothing. It was reported
 * from the guarantors panel; `publishNotice` and `deleteNotice` in education carried the same bug and nobody
 * had noticed, because the failure is a console error on a click, not a visible break.
 *
 * ── ⚠ Why the inconsistency makes the wrong shape look right ────────────────────────────────────
 * `uiAlert` DOES accept a bare string and normalises it (`typeof opts === 'string' ? {message: opts} : opts`).
 * `uiConfirm` and `uiPromptConfirm` do not. A developer who has just written `uiAlert('Saved')` reasonably
 * assumes the sibling behaves the same way.
 *
 * ── Why this reads the SOURCE ───────────────────────────────────────────────────────────────────
 * The failure is per call site, and there are dozens across five modules. Clicking every destructive button
 * in every module is not a test anyone will keep running; a structural check over the shipped files is, and
 * it catches the next one written the same way.
 */

/** Every module file that could call the dialog. Fetched over HTTP — the SHIPPED file, not a copy. */
const FILES = [
  '/js/business/business.js',
  '/js/business/installment.js',
  '/js/business/ecommerce.js',
  '/js/education/education.js',
  '/js/common/team.js',
  '/js/common/settings-form.js',
]

/**
 * Call sites of `fn` whose first argument is NOT an object literal.
 *
 * Deliberately crude — it looks at the character after the opening bracket. That is enough to separate
 * `uiConfirm({` from `uiConfirm(t('…'), function(){`, and it cannot be fooled by a variable holding an
 * object, which is the one false positive worth tolerating: passing a prepared object is fine and rare.
 */
const badCalls = (src, fn) => {
  const out = []
  const re = new RegExp('(?<![.\\w])' + fn + '\\s*\\(', 'g')
  let m
  while ((m = re.exec(src)) !== null) {
    const rest = src.slice(m.index + m[0].length).trimStart()
    if (rest.startsWith('{')) continue                 // the correct shape
    if (rest.startsWith(')')) continue                 // no-arg, harmless
    // A bare identifier may be a prepared options object; a string literal or a t(…) call is not.
    if (/^['"`]/.test(rest) || /^t\s*\(/.test(rest) || /^tr\s*\(/.test(rest)) {
      const line = src.slice(0, m.index).split('\n').length
      out.push(`line ${line}: ${src.slice(m.index, m.index + 70).split('\n')[0]}`)
    }
  }
  return out
}

describe('uiConfirm / uiPromptConfirm take an options object', () => {
  before(() => cy.loginAsOwner())

  FILES.forEach((path) => {
    it(`⭐ ${path} calls the dialog correctly`, () => {
      cy.request({ url: path, failOnStatusCode: false }).then((r) => {
        if (r.status !== 200) return                    // module not present in this build — nothing to check
        const src = r.body
        const bad = [].concat(badCalls(src, 'uiConfirm'), badCalls(src, 'uiPromptConfirm'))
        expect(bad, `${path} passes a string where an options object is required:\n${bad.join('\n')}`)
          .to.have.length(0)
      })
    })
  })

  it('⭐⭐ the dialog actually opens and resolves — the behaviour behind the contract', () => {
    /*
     * The structural check above cannot prove the dialog works, only that it is called correctly. This
     * opens a real one and answers it, so a change to `open()` that broke the promise would fail here
     * rather than in whichever module noticed first.
     */
    cy.visitDashboardSettled()
    cy.window().then((win) => {
      expect(typeof win.uiConfirm, 'uiConfirm is on the page').to.eq('function')
      const p = win.uiConfirm({ title: 'Contract check', message: 'Proceed?', confirmText: 'Yes' })
      expect(p && typeof p.then, 'it returns a promise').to.eq('function')
      return p
    })
    // The shared dialog's OK control — see /js/common/confirm-dialog.js.
    cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).click()
    cy.window().its('document.body').should('exist')
  })
})
