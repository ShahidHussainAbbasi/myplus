/**
 * RUM — does the measurement instrument actually measure anything.
 *
 * <h3>Why this spec exists</h3>
 * The collector shipped and produced <b>zero</b> beacons. Everything looked right — the script was served, the
 * controller was present, the route was `permitAll` — and `POST /rum` answered **302, a redirect to login**.
 * `navigator.sendBeacon` cannot set request headers by design (it is sent after the page is gone, with no
 * document left to read a token from), so CSRF rejected it and the beacon, which discards responses, reported
 * nothing at all.
 *
 * A monitoring feature that silently collects nothing is worse than none: it answers "are we measuring?" with
 * a confident yes. So the instrument gets a gate of its own, and the gate asserts a beacon is really SENT with
 * real numbers in it — not that the file was loaded.
 */

const OWNER = 'owner.business@myplus.com'

describe('RUM — the beacon actually fires', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ leaving the page sends a beacon carrying real timings', () => {
    cy.intercept('POST', '**/rum').as('beacon')

    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 30000 }).should('exist')

    // Navigating away is what a cashier closing the till does, and it is what fires pagehide /
    // visibilitychange. Triggering the collector directly would test the function and not the wiring.
    cy.visit('/businessDashboard?rumProbe=1')

    cy.wait('@beacon', { timeout: 20000 }).then((i) => {
      // A beacon is fire-and-forget, so the SERVER's answer is not the assertion — the request is.
      const body = i.request.body
      expect(body, 'the beacon carries a payload').to.be.an('object')

      expect(body.nav, 'navigation timings').to.be.an('object')
      expect(body.nav.load, 'a load time that is a real number, not 0 or undefined').to.be.greaterThan(0)
      expect(body.nav.requests, 'the request COUNT — the audit finding that survives caching')
        .to.be.greaterThan(10)

      // Per-endpoint API timings are the half Core Web Vitals cannot give: which call was slow, for this
      // tenant, on this connection.
      expect(body.api, 'per-endpoint timings').to.be.an('object')

      // No PII, ever. A monitoring pipeline is a copy of your data in a second place.
      const flat = JSON.stringify(body)
      expect(flat, 'no email in telemetry').to.not.match(/@myplus\.com/)
      expect(flat, 'no password field').to.not.match(/password/i)
    })
  })

  it('the beacon endpoint accepts the post rather than redirecting it', () => {
    /*
     * The specific defect, asserted directly: a 302 here means CSRF or auth is intercepting again, and the
     * client-side test above could still pass while every beacon in production was discarded.
     *
     * 204 is the contract — sendBeacon ignores the body, so anything else is wasted bytes.
     */
    cy.request({
      method: 'POST',
      url: '/rum',
      body: { page: '/probe', nav: { load: 1, requests: 1 }, api: {}, marks: {}, vitals: {} },
      failOnStatusCode: false,
    }).then((r) => {
      expect(r.status, 'the beacon must be accepted, not redirected to login').to.eq(204)
    })
  })

  it('the sale screen reports tillReady — the metric the complaint was about', () => {
    /*
     * Core Web Vitals does not measure this. LCP says when the page LOOKED loaded; this says when the cashier
     * could actually start selling, which is the number the original "customer is waiting" complaint meant.
     */
    cy.intercept('POST', '**/rum').as('tillBeacon')

    cy.visitSaleScreen()
    cy.get('#sellItems', { timeout: 30000 }).should('be.visible')
    cy.visit('/businessDashboard?rumProbe=2')

    cy.wait('@tillBeacon', { timeout: 20000 }).then((i) => {
      const marks = i.request.body.marks || {}
      expect(marks.tillReady, 'the till-ready mark is present').to.be.a('number')
      expect(marks.tillReady, 'and is a plausible elapsed time from navigation start').to.be.greaterThan(0)
    })
  })

  it('⭐ LCP reports too — the metrics that finalise ON page hide', () => {
    /*
     * The defect this guards. The first version gathered every vital into ONE end-of-page beacon, and
     * measurement showed the split precisely: TTFB and FCP arrived (they settle early) while LCP, CLS and INP
     * produced ZERO rows across every page load — those three are finalised BY the page hiding, so the single
     * beacon was racing the event that produces them.
     *
     * Each metric now beacons as it finalises. LCP is asserted because it is the one that always has a value
     * on a rendered page; CLS and INP legitimately may not (no shift, no interaction), so asserting them here
     * would be a flaky test dressed as a strict one.
     */
    const seen = []
    cy.intercept('POST', '**/rum', (req) => {
      if (req.body && req.body.vital) seen.push(req.body.vital)
    }).as('vitals')

    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 30000 }).should('exist')
    cy.visit('/businessDashboard?rumProbe=3')

    cy.wrap(null, { timeout: 20000 }).should(() => {
      expect(seen, 'vitals seen: ' + (seen.join(',') || 'none')).to.include('LCP')
    })
  })
})
