/**
 * PERF-3b gate — no third-party CDN jQuery, no mixed content.
 *
 * Design doc: microservices/docs/frontend-performance-audit.md (§4d)
 *
 * THE BUG THIS FIXES IS CORRECTNESS, NOT SPEED.
 * Eight templates pulled jQuery 1.11.2 from `ajax.googleapis.com`, six of them over plain `http://`.
 * Browsers BLOCK mixed content on an https deployment, so on a real HTTPS install those pages received
 * no jQuery at all — and three of them (changePassword, updatePassword, forgetPassword) are live
 * account-management screens. They now load the local `/js/jquery.min.js`, which is the SAME version
 * 1.11.2, so nothing about their behaviour changes.
 *
 * WHY NOT 3.3.1 HERE: these pages load `pwstrength.js`, a jQuery-1-era plugin that leans on
 * `$.isFunction` (deprecated in 3.x) and Bootstrap-2 popover APIs. The defect was the transport, not the
 * version; bundling an upgrade into a mixed-content fix would put unrelated risk on account screens.
 *
 * COVERAGE — stated honestly.
 * Only three of the eight pages are reachable unauthenticated (verified by probe): registration.html,
 * registrationCaptcha.html, forgetPassword.html. The others 302 to login (badUser, console, appointment2)
 * or need a valid one-time reset token (changePassword, updatePassword) and are NOT gated here — the
 * edit was identical and mechanical across all eight, but this file only claims what it actually proves.
 */

const PAGES = [
  { url: '/registration.html', label: 'registration' },
  { url: '/registrationCaptcha.html', label: 'registration (captcha)' },
  { url: '/forgetPassword.html', label: 'forgot password' }
]

describe('PERF-3b — local jQuery, no CDN, no mixed content', () => {
  PAGES.forEach(({ url, label }) => {
    describe(label, () => {
      beforeEach(() => cy.visit(url))

      it('actually HAS jQuery — the thing mixed-content blocking took away', () => {
        // The whole point. On an https deployment the old http:// CDN tag was blocked and this was
        // undefined, silently breaking every script on the page.
        cy.window().its('jQuery').should('be.a', 'function')
        cy.window().its('jQuery.fn.jquery').should('eq', '1.11.2')
      })

      it('serves jQuery from THIS origin, not a third party', () => {
        cy.document().then((doc) => {
          const srcs = [...doc.querySelectorAll('script[src]')].map((s) => s.getAttribute('src'))
          const jq = srcs.filter((s) => /jquery(-[\d.]+)?(\.min)?\.js/i.test(s))
          expect(jq, `jQuery tags on ${url}: ${JSON.stringify(jq)}`).to.have.length(1)
          expect(jq[0], 'must not be an absolute third-party URL').to.not.match(/^https?:\/\//i)
          expect(jq[0], 'must be the local jQuery').to.match(/\/js\/jquery\.min\.js$/)
        })
      })

      it('loads NO plain http:// subresource (the mixed-content guard)', () => {
        // Generalised past jQuery on purpose: any http:// subresource re-introduces the same class of
        // bug on an https deployment, so this fails on the next one anyone adds, not just this one.
        cy.document().then((doc) => {
          const bad = [...doc.querySelectorAll('script[src], link[href], img[src]')]
            .map((el) => el.getAttribute('src') || el.getAttribute('href'))
            .filter((u) => u && /^http:\/\//i.test(u))
          expect(bad, `plain http:// subresources on ${url}: ${JSON.stringify(bad)}`).to.have.length(0)
        })
      })
    })
  })

  it('the password-strength plugin still binds on registration', () => {
    // pwstrength.js is the reason these pages stayed on 1.11.2. If jQuery failed to arrive, or a version
    // swap had crept in, this plugin would be the first casualty — and it guards password quality on a
    // live signup form, so its silence would be expensive.
    cy.visit('/registration.html')
    cy.window().then((win) => {
      expect(win.jQuery.fn.pwstrength, 'pwstrength plugin').to.be.a('function')
    })
  })
})
