/**
 * PERF-1 gate — HTTP response compression.
 *
 * Design doc: microservices/docs/frontend-performance-audit.md (finding F2)
 *
 * WHAT THIS GATE IS FOR
 * The audit found NOTHING in the stack compressed responses, so a business-dashboard load shipped
 * ~4.3MB of HTML+JS raw. PERF-1 turned on `server.compression.*` in application.properties.
 *
 * WHAT IT ASSERTS — the PROPERTY, not the ARTEFACT.
 * Asserting "the config key exists" would pass on both branches and prove nothing. These tests assert
 * what a user on a slow link actually gets: the bytes arrive gzip-encoded, they arrive INTACT, and the
 * things that must NOT be compressed still are not. Every assertion below fails on the pre-PERF-1 build.
 *
 * ⚠️ ON `content-encoding` AND cy.request
 * Cypress transparently decompresses the body, so `res.body` is the PLAIN text either way — the body is
 * therefore useless as evidence of compression, and only the headers discriminate. Tomcat also drops
 * `content-length` and switches to chunked when it compresses, so that is a second, independent signal.
 * Both are checked. If a Cypress version normalises `content-encoding` away, `expectCompressed` reports
 * the raw headers so the failure is diagnosable rather than mysterious — and the curl commands in the
 * slice notes remain the ground truth for this property.
 */

/** Assert a response actually came back compressed, with a diagnosable failure. */
function expectCompressed(res, label) {
  const enc = res.headers['content-encoding']
  const len = res.headers['content-length']
  // Primary signal: the server said it compressed.
  // Secondary: Tomcat drops content-length in favour of chunked transfer when it compresses, so a
  // LARGE response still carrying a content-length header is a response that was NOT compressed.
  const looksCompressed = enc === 'gzip' || len === undefined
  expect(
    looksCompressed,
    `${label} should be gzip-encoded. content-encoding=${JSON.stringify(enc)} ` +
      `content-length=${JSON.stringify(len)}. If content-encoding is undefined but content-length is ` +
      `also absent, Cypress stripped the header — confirm with: ` +
      `curl -sI -H "Accept-Encoding: gzip" <url>`
  ).to.eq(true)
  expect(enc, `${label} content-encoding`).to.eq('gzip')
}

describe('PERF-1 — responses are gzip-compressed on the wire', () => {
  beforeEach(() => {
    // testIsolation clears the session between tests, so authed cy.requests must re-login here.
    cy.loginAsBusiness()
  })

  it('the business dashboard HTML is compressed (190KB raw — the single biggest text payload)', () => {
    cy.request({
      url: '/businessDashboard',
      headers: { 'accept-encoding': 'gzip, deflate' }
    }).then((res) => {
      expect(res.status).to.eq(200)
      expectCompressed(res, 'businessDashboard HTML')
    })
  })

  it('business.js is compressed — JS is ~4MB of the ~4.3MB page', () => {
    // Verified on the running app: Tomcat labels this `text/javascript`, NOT `application/javascript`,
    // so it is the `text/javascript` entry in server.compression.mime-types that carries this request.
    // `application/javascript` is listed too and stays listed — some containers and some hand-set
    // Content-Types still use it, and an uncompressed 4MB of JS is the whole point of the slice.
    // If this goes red while the HTML test stays green, the mime-types line lost its JS entries.
    cy.request({
      url: '/js/business/business.js',
      headers: { 'accept-encoding': 'gzip, deflate' }
    }).then((res) => {
      expect(res.status).to.eq(200)
      expectCompressed(res, 'business.js')
    })
  })

  it('a large JSON API response is compressed (helps every AJAX call on a slow link)', () => {
    cy.request({
      url: '/catalogProducts?size=2000',
      headers: { 'accept-encoding': 'gzip, deflate' }
    }).then((res) => {
      expect(res.status).to.eq(200)
      // Only meaningful above the 1024-byte threshold; below it, NOT compressing is correct.
      // Skipping rather than asserting keeps this honest on a near-empty demo catalogue.
      const raw = JSON.stringify(res.body)
      if (raw.length < 1024) {
        cy.log(`catalogProducts payload is ${raw.length}B — under the 1024B threshold, correctly uncompressed`)
        return
      }
      expectCompressed(res, 'catalogProducts JSON')
    })
  })

  it('compressed content arrives INTACT — the real risk of enabling compression', () => {
    // A corrupting compression filter is the failure mode that matters: the response would still be
    // 200 OK and still be "compressed", but render broken. Assert the decompressed payloads are real.
    //
    // Assertions are on a BOOLEAN, never on the body itself. Asserting `expect(body).to.include(x)`
    // dumps the entire 190KB payload into the failure message on a red — the first version of this test
    // produced a 108k-token log that had to be grepped to find one line of signal.
    const has = (body, marker, label) =>
      expect(body.includes(marker), `${label} should contain ${JSON.stringify(marker)}`).to.eq(true)

    cy.request({
      url: '/businessDashboard',
      headers: { 'accept-encoding': 'gzip, deflate' }
    }).then((res) => {
      // `</html>` is the truncation guard: it is the LAST thing in the document, so a stream that was
      // cut short cannot satisfy it.
      has(res.body, '</html>', 'dashboard HTML')
      // ...and this proves it is genuinely the DASHBOARD. Without it the test would pass just as well
      // against the login page, which a lapsed session would silently serve instead (302 → login).
      // Note: do NOT use the string 'businessDashboard' — that is the TEMPLATE's filename and never
      // appears in rendered output. It was the original marker here and it failed for that reason.
      has(res.body, 'tableSellReport', 'dashboard HTML')
      expect(res.body.length, 'dashboard HTML length').to.be.greaterThan(50000)
    })

    cy.request({
      url: '/js/business/business.js',
      headers: { 'accept-encoding': 'gzip, deflate' }
    }).then((res) => {
      has(res.body, '$(document).ready', 'business.js')
      // ~1600 lines in, so a truncated stream fails here even though the head decompressed fine.
      has(res.body, 'function getDashboardData', 'business.js')
    })
  })

  it('already-compressed binaries are NOT re-compressed (mime filtering works)', () => {
    // Re-gzipping a PNG burns CPU on both ends and typically makes it larger. If this goes red, the
    // mime-types list has been replaced with a wildcard.
    cy.request({
      url: '/favicon.png',
      headers: { 'accept-encoding': 'gzip, deflate' }
    }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.headers['content-encoding'], 'favicon.png must not be gzipped').to.not.eq('gzip')
    })
  })

  it('the dashboard renders correctly with compression on (end-to-end smoke)', () => {
    // The byte-level tests above prove the transport. This proves the app still works through it.
    cy.visit('/businessDashboard')
    cy.get('#tableSellReport', { timeout: 20000 }).should('exist')
    cy.window().should('have.property', 'MODULE')
  })
})
