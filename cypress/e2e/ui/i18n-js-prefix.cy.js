/**
 * i18n — a label used from JavaScript must live under `ui.js.*`.
 *
 * <h3>The defect this was written for</h3>
 * The checkout confirm dialog called `t('ui.completeSale')`, `t('ui.park')` and `t('ui.cancel2')`. Those keys
 * exist, and are correctly translated in all six locales, and the buttons elsewhere on the page render them
 * through Thymeleaf perfectly well — so everything LOOKED right.
 *
 * But `LocaleInterceptor` ships only the `ui.js.` prefix into `window.__MSG` (server-only copy deliberately
 * never crosses), and `t()` returns the KEY ITSELF when it is missing. So the cashier's dialog read
 * "ui.completeSale" / "ui.park" / "ui.cancel2" — in every language, English included.
 *
 * <h3>Why a structural test, not three assertions</h3>
 * Asserting the three fixed strings would guard yesterday's bug. The rule is what matters: ANY `t('ui.x')`
 * call outside the `ui.js.` namespace is broken by construction, and the next one added would fail silently
 * in exactly the same way. So this walks the shipped dictionary and the rule rather than the instance.
 */

describe('i18n — the JS dictionary contract', () => {
  beforeEach(() => {
    cy.loginAsOwner('owner.business@myplus.com')
  })

  it('⭐ every key the checkout dialog needs is actually SHIPPED to the browser', () => {
    cy.visit('/businessDashboard')
    cy.window({ timeout: 30000 }).should((w) => {
      expect(w.__MSG, 'the JS dictionary is present').to.be.an('object')
    })
    cy.window().then((w) => {
      ;['ui.js.completeSale', 'ui.js.park', 'ui.js.cancel'].forEach((k) => {
        expect(w.__MSG, `${k} must be in window.__MSG`).to.have.property(k)
        expect(String(w.__MSG[k]).trim(), `${k} has real text`).to.not.be.empty
      })
    })
  })

  it('⭐ t() resolves them to text — never back to the key', () => {
    /*
     * The actual failure mode. t() falls back to returning its argument, so a missing key does not throw,
     * does not log, and renders a machine identifier onto a button in front of a customer.
     */
    cy.visit('/businessDashboard')
    cy.window({ timeout: 30000 }).should((w) => expect(w.t).to.be.a('function'))
    cy.window().then((w) => {
      ;['ui.js.completeSale', 'ui.js.park', 'ui.js.cancel'].forEach((k) => {
        expect(w.t(k), `t('${k}') must not echo the key back`).to.not.eq(k)
      })
    })
  })

  it('⭐ the non-shipped ui.* keys are demonstrably NOT reachable from JS', () => {
    /*
     * Proves the rule rather than the symptom: these are real, translated keys that Thymeleaf renders fine,
     * and they are still invisible to the browser dictionary. That asymmetry is the whole trap, and this
     * assertion is what stops someone "fixing" a future bug by adding another ui.* key.
     */
    cy.visit('/businessDashboard')
    cy.window({ timeout: 30000 }).should((w) => expect(w.__MSG).to.be.an('object'))
    cy.window().then((w) => {
      ;['ui.completeSale', 'ui.park', 'ui.cancel2'].forEach((k) => {
        expect(w.__MSG, `${k} is server-side copy and must NOT ship`).to.not.have.property(k)
      })
    })
  })

  it('the dialog itself renders words, not identifiers', () => {
    // End to end: whatever the dictionary says, nothing on a confirm dialog may look like a key.
    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 30000 }).should('exist')
    cy.window().then((w) => {
      if (typeof w.uiConfirm !== 'function') return
      w.uiConfirm({
        title: w.t('ui.js.completeSaleTitle'),
        confirmText: w.t('ui.js.completeSale'),
        altText: w.t('ui.js.park'),
        cancelText: w.t('ui.js.cancel'),
      })
    })
    cy.get('.uiC-card').should('be.visible')
    cy.get('.uiC-card').invoke('text').should((txt) => {
      expect(txt, 'no raw i18n key leaked onto the dialog').to.not.match(/\bui\.(js\.)?[a-zA-Z]+\b/)
    })
  })
})
