/**
 * PERF-2 gate — static assets are cached for a year AND bustable on deploy.
 *
 * Design: microservices/docs/frontend-performance-audit.md §4e (finding F1)
 *
 * WHAT CHANGED
 * MvcConfig now serves /js, /css, /images, /img, /bootstrap, /jQExp and /webjars through a resource chain
 * with a VersionResourceResolver (content md5 in the URL) and Cache-Control: max-age=31536000, immutable.
 * Everything else keeps a short, revalidating hour. A ResourceUrlEncodingFilter bean makes Thymeleaf's
 * @{...} links emit the hashed form.
 *
 * ⚠️ WHY A GATE AND NOT A CONFIG REVIEW — THIS IS THE WHOLE OF F1
 * `spring.web.resources.cache.period=3600` sat in application-prod.properties for the entire life of
 * prod-hardening P3 and did NOTHING: @EnableWebMvc disables the auto-configuration that reads it. A config
 * property is only live if its auto-configuration is still enabled, and you cannot tell by reading the
 * .properties file. So every case below asserts what arrives ON THE WIRE or what is rendered INTO THE
 * PAGE — never that a setting exists.
 *
 * ⚠️ THE FAILURE MODE THIS GATE MUST CATCH FIRST
 * ResourceHttpRequestHandler resolves the path *within its mapping*. A handler at "/js/**" whose location
 * is classpath:/static/ looks for classpath:/static/<name> instead of classpath:/static/js/<name>, and
 * every script on the site 404s. The page still returns 200 and still renders markup, so a status-code
 * test sails past it. Case 1 is the positive control for exactly that.
 *
 * ⚠️ HEADERS ARE THE ONLY EVIDENCE
 * Cypress decompresses transparently and honours its own cache rules, so a response BODY proves nothing
 * about caching or compression. Assertions here are on headers, on rendered URLs, and on booleans with
 * descriptive messages — never on a large body (one earlier red dumped 190KB into a failure message and
 * produced a 108k-token log).
 */

const HASH = '[0-9a-f]{32}'
const HASHED = new RegExp(`-${HASH}\\.(js|css|png|gif|jpg|jpeg|svg|ico)$`)
// The prefixes MvcConfig versions. /main.css and the root-level images are deliberately NOT in here.
const VERSIONED_PREFIX = /^\/(js|css|images|img|bootstrap|jQExp|webjars)\//

const sameOriginAssets = (doc) =>
  [...doc.querySelectorAll('script[src], link[rel="stylesheet"][href]')]
    .map((el) => el.getAttribute('src') || el.getAttribute('href'))
    .filter((u) => u && !/^(https?:)?\/\//i.test(u) && !u.startsWith('data:'))

describe('PERF-2 — static caching + content-hash versioning', () => {
  beforeEach(() => {
    // testIsolation clears the session between tests, so authed pages need a login in every one.
    cy.loginAsBusiness()
  })

  it('POSITIVE CONTROL — the dashboard still boots, so its assets are genuinely being served', () => {
    // Must run before anything else here. If the per-directory handlers were mis-located, every script
    // 404s: the HTML is still 200 and still full of markup, and every header assertion below would be
    // asserting about files nobody can load. This case is what makes the rest of the file mean something.
    cy.visit('/businessDashboard')
    cy.window({ timeout: 20000 }).should((win) => {
      expect(typeof win.jQuery, 'jQuery loaded from a hashed URL').to.eq('function')
      expect(win.$ && win.$.fn && typeof win.$.fn.dataTable, 'DataTables loaded from a hashed URL')
        .to.eq('function')
      expect(typeof win.lazyPdfButton, 'lazy-export.js loaded from a hashed URL').to.eq('function')
    })
    cy.get('#tableSellReport', { timeout: 20000 }).should('exist')
    cy.window().then((win) => {
      expect(win.jQuery.fn.dataTable.isDataTable('#tableSellReport'), 'a real DataTable is driving the DOM')
        .to.eq(true)
    })
  })

  it('the rendered page emits CONTENT-HASHED urls for every versioned prefix', () => {
    // This is the half that @EnableWebMvc silently removes: without the ResourceUrlEncodingFilter bean the
    // server would still SERVE hashed URLs while no page ever GENERATED one, and the slice would degrade to
    // an unbustable one-year cache. Nothing in the config would look wrong.
    cy.visit('/businessDashboard')
    cy.document().then((doc) => {
      const assets = sameOriginAssets(doc)
      const versioned = assets.filter((u) => VERSIONED_PREFIX.test(u))

      expect(versioned.length, `assets under a versioned prefix: ${versioned.length}`).to.be.greaterThan(10)

      const unhashed = versioned.filter((u) => !HASHED.test(u))
      expect(
        unhashed.length === 0,
        `every versioned-prefix asset must carry a content hash; these do not: ${JSON.stringify(unhashed)}`
      ).to.eq(true)
    })
  })

  it('root-level assets are deliberately NOT hashed — the security-shape guard', () => {
    // SecSecurityConfig permits "/main.css", "/*.png", "/*.ico", "/*.jpeg" by EXACT single-segment
    // patterns. A hashed "/main-<md5>.css" matches none of them, falls through to
    // anyRequest().hasAuthority("LOGIN_PRIVILEGE") and 302s anonymous visitors to /login. That is the
    // pwstrength.js defect verbatim, and it is why root-level files are excluded from versioning.
    cy.visit('/businessDashboard')
    cy.document().then((doc) => {
      const root = sameOriginAssets(doc).filter((u) => /^\/[^/]+\.(css|png|ico|jpeg)$/.test(u))
      expect(root.length, `root-level assets found: ${JSON.stringify(root)}`).to.be.greaterThan(0)
      const hashed = root.filter((u) => HASHED.test(u))
      expect(
        hashed.length === 0,
        `root-level assets must stay unhashed to keep matching the permitAll patterns: ${JSON.stringify(hashed)}`
      ).to.eq(true)
    })
  })

  it('a hashed asset arrives with max-age=31536000 and immutable — F1, proven on the wire', () => {
    cy.visit('/businessDashboard')
    cy.document()
      .then((doc) => sameOriginAssets(doc).find((u) => /^\/js\/.*-[0-9a-f]{32}\.js$/.test(u)))
      .then((url) => {
        expect(url, 'a hashed /js/ url must be present in the page to request').to.be.a('string')
        cy.request({ method: 'GET', url, failOnStatusCode: false }).then((res) => {
          const cc = String(res.headers['cache-control'] || '')
          expect(res.status, `GET ${url}`).to.eq(200)
          expect(
            /max-age=31536000/.test(cc),
            `Cache-Control must carry the one-year max-age; got: "${cc}"`
          ).to.eq(true)
          expect(
            /immutable/.test(cc),
            `Cache-Control must be immutable so the browser does not even revalidate; got: "${cc}"`
          ).to.eq(true)
        })
      })
  })

  it('an UNHASHED asset gets a short, revalidating cache — never immutable', () => {
    // /main.css cannot be busted by a URL change, so it must never be given a period it cannot recover
    // from. This is the reason for two handler tiers rather than one.
    cy.request({ method: 'GET', url: '/main.css', failOnStatusCode: false }).then((res) => {
      const cc = String(res.headers['cache-control'] || '')
      expect(res.status, 'GET /main.css').to.eq(200)
      expect(/max-age=3600/.test(cc), `expected the one-hour period; got: "${cc}"`).to.eq(true)
      expect(
        /immutable/.test(cc),
        `an unhashed asset must NOT be immutable — it could never be updated; got: "${cc}"`
      ).to.eq(false)
    })
  })

  it('the hash is VALIDATED, not decorative — a forged hash 404s', () => {
    // If the resolver simply stripped anything that looked like a version and served the file, the URL
    // would not be a cache key at all and a deploy still could not reach cached browsers. A wrong hash
    // must be refused.
    const forged = `/js/main-${'0'.repeat(32)}.js`
    cy.request({ method: 'GET', url: forged, failOnStatusCode: false, followRedirect: false })
      .then((res) => {
        expect(res.status, `GET ${forged} — a hash that matches nothing must not be served`).to.eq(404)
      })
  })

  it('the hash is STABLE across loads — it is content-derived, not per-startup or random', () => {
    // A FixedVersionStrategy seeded from a startup timestamp would also produce "versioned" URLs and would
    // also pass every case above, while invalidating the entire cache on every restart — the exact
    // opposite of the slice's purpose. Two loads must agree.
    const grab = () =>
      cy.document().then((doc) => sameOriginAssets(doc).find((u) => /^\/js\/main-[0-9a-f]{32}\.js$/.test(u)))

    cy.visit('/businessDashboard')
    grab().then((first) => {
      expect(first, '/js/main.js must render as a hashed url').to.be.a('string')
      cy.reload()
      grab().then((second) => {
        expect(second, `hash must not change between loads (${first} vs ${second})`).to.eq(first)
      })
    })
  })

  it('the UNVERSIONED path still resolves — bookmarks and the PERF-1 gate keep working', () => {
    // VersionResourceResolver tries the literal request path before it tries stripping a version, so
    // /js/business/business.js is still a 200. PERF-1's compression gate requests exactly that.
    cy.request({ method: 'GET', url: '/js/business/business.js', failOnStatusCode: false })
      .then((res) => {
        expect(res.status, 'GET /js/business/business.js (unversioned)').to.eq(200)
      })
  })

  it('hashed assets are still GZIPPED — PERF-1 and PERF-2 compose', () => {
    // Cypress decompresses the body, so only the header discriminates. Never assert on res.body here.
    cy.visit('/businessDashboard')
    cy.document()
      .then((doc) => sameOriginAssets(doc).find((u) => /^\/js\/.*-[0-9a-f]{32}\.js$/.test(u)))
      .then((url) => {
        cy.request({ method: 'GET', url, headers: { 'Accept-Encoding': 'gzip' } }).then((res) => {
          const enc = String(res.headers['content-encoding'] || '')
          expect(/gzip/.test(enc), `expected gzip on ${url}; got Content-Encoding: "${enc}"`).to.eq(true)
        })
      })
  })

  it("lazy-export's RUNTIME urls are versioned too — the one hole a filter cannot reach", () => {
    // lazy-export.js builds its three URLs in JavaScript at click time, where no response filter runs.
    // fragments/header.html resolves them through @{...} into window.__ASSETS for precisely this reason.
    // Unhashed, they would be served under the one-year immutable header with no way to bust them.
    cy.visit('/businessDashboard')
    cy.window({ timeout: 20000 }).should((win) => {
      expect(win.__ASSETS, 'window.__ASSETS published by fragments/header.html').to.be.an('object')
    })
    cy.window().then((win) => {
      const urls = [win.__ASSETS.pdfmake, win.__ASSETS.vfsFonts, win.__ASSETS.jszip]
      const unhashed = urls.filter((u) => !/-[0-9a-f]{32}\.js$/.test(String(u)))
      expect(
        unhashed.length === 0,
        `lazy-export asset urls must be content-hashed; these are not: ${JSON.stringify(unhashed)}`
      ).to.eq(true)
      // And they must actually resolve — a hashed URL that 404s would break the export button entirely.
      cy.request({ method: 'GET', url: urls[0], failOnStatusCode: false }).then((res) => {
        expect(res.status, `GET ${urls[0]}`).to.eq(200)
      })
    })
  })
})

describe('PERF-2 — anonymous reachability (the pwstrength bug class)', () => {
  // No login in this block on purpose. Versioning changes the SHAPE of asset URLs, and Spring Security
  // matches on shape. pwstrength.js was 302'd to /login for years because a root-level *.js matched no
  // permitAll pattern, and the only symptom was a password-strength meter that never appeared.
  // followRedirect:false so a 302 shows up as a 302 instead of a 200 for the login page.

  it('a hashed /js/ url is still permitAll for an anonymous visitor', () => {
    cy.visit('/registration.html')
    cy.document()
      .then((doc) => sameOriginAssets(doc).find((u) => /^\/js\/.*-[0-9a-f]{32}\.js$/.test(u)))
      .then((url) => {
        expect(url, 'the anonymous registration page must link at least one hashed /js/ asset').to.be.a('string')
        cy.request({ method: 'GET', url, failOnStatusCode: false, followRedirect: false }).then((res) => {
          expect(res.status, `anonymous GET ${url} — 302 here means the hashed shape left /js/**`).to.eq(200)
        })
      })
  })

  it('the anonymous password-strength meter still binds — end-to-end proof of the above', () => {
    // The property, not the artefact: the script TAG was always present; only "did the plugin bind?"
    // caught the original defect. If versioning broke /js/** for anonymous users, this is what dies.
    cy.visit('/registration.html')
    cy.window().then((win) => {
      expect(win.jQuery, 'jQuery on the anonymous signup page').to.be.a('function')
      expect(win.jQuery.fn.pwstrength, 'pwstrength plugin bound').to.be.a('function')
    })
  })

  it('root-level /main.css is reachable anonymously and is not redirected', () => {
    cy.request({ method: 'GET', url: '/main.css', failOnStatusCode: false, followRedirect: false })
      .then((res) => {
        expect(res.status, 'anonymous GET /main.css').to.eq(200)
      })
  })
})
