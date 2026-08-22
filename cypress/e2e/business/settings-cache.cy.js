/**
 * PERF-C1 — the tenant-settings cache, asserted through the running stack.
 *
 * <h3>What this adds over the unit tests</h3>
 * `SettingsCacheTest` proves the cache against a counting fake store: fewer queries, exact invalidation,
 * per-tenant keys. What it cannot prove is that the SAME object serves the HTTP read and the HTTP write —
 * a request-scoped bean, a second SettingsService instance per service, or a proxy sitting between the
 * controller and the cache would each satisfy every unit test and still leave an owner staring at a
 * setting they just saved and a screen that has not changed.
 *
 * So this asserts the one thing only a live stack can: **save a setting, read it back over HTTP, get the
 * new value.**
 *
 * <h3>The tenancy half</h3>
 * Two organisations read the SAME key and must get their OWN answers. This is the failure that would
 * matter — a cache keyed without the org serves one shop's configuration to another, silently, with
 * nothing in the logs. Every other test in this suite runs as a single org, so nothing else would catch
 * it. `demo.business@` and `owner.business@` are seeded into different organizations, which is what makes
 * them useful here.
 *
 * <h3>Leave no server state behind</h3>
 * These tests WRITE tenant configuration, and restore each tenant's original VALUE in an `after` — the
 * rule learned when a period-close spec left the books locked and reddened every sale spec after it.
 *
 * <p>One honest limit: the settings API has no DELETE. `SettingsService.set` always upserts, so a key
 * that was on its catalogue default before this spec ran is left holding an explicit override of the
 * same value, and its `isDefault` flag stays false. The EFFECTIVE configuration is identical and no
 * behaviour changes; only the Configuration screen's "this is a default" marker differs. Restoring that
 * properly needs a delete endpoint, which is a change to the product and not something a test should
 * quietly introduce.
 */
describe('Tenant settings survive the cache — write wins, tenants stay apart', () => {
  const GW = 'http://localhost:8765'

  /*
   * A TEXT setting nothing on the sale screen branches on.
   *
   * Deliberately not a behavioural flag: writing one of those would reconfigure the till for whatever
   * spec runs next, and a cache test has no business changing how a sale works. The receipt footer is
   * free text that only ever gets printed.
   */
  const KEY = 'pos.document.footerText'

  const auth = (email) => cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email, password: 'Demo@2025!' }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
    return { Authorization: `Bearer ${r.body.data.accessToken}`, 'Content-Type': 'application/json' }
  })

  /** Read one setting's effective value straight from the catalogue the Configuration screen consumes. */
  const read = (headers, key) =>
    cy.request({ url: `${GW}/api/business/settings`, headers }).then((r) => {
      expect(r.body, 'the settings catalogue came back').to.have.property('data')
      const hit = r.body.data.filter((e) => e.key === key)
      expect(hit.length, `${key} is in the catalogue`).to.eq(1)
      return hit[0].value
    })

  const write = (headers, key, value) =>
    cy.request({
      method: 'POST',
      url: `${GW}/api/business/settings?key=${encodeURIComponent(key)}&value=${encodeURIComponent(value)}`,
      headers, failOnStatusCode: false,
    }).then((r) => {
      // Assert the WRITE was accepted. A save that quietly 403s would make the read below fail and look
      // like a cache bug — the wrong diagnosis for a permissions problem.
      expect(r.status, 'save accepted').to.eq(200)
      expect(r.body.success, `save rejected: ${JSON.stringify(r.body).slice(0, 200)}`).to.not.eq(false)
    })

  /*
   * Remember each tenant's ORIGINAL value — once, and only once.
   *
   * A first version recorded it per test, so the second test captured what the FIRST had already
   * written and the restore faithfully put a test marker back into the tenant's configuration. The
   * cleanup ran, reported nothing wrong, and left the shop with a receipt footer reading
   * "CACHE_GATE_1787429118728". A restore that restores the wrong value is worse than none, because it
   * looks like it worked.
   */
  const original = {}
  const remember = (email, value) => {
    if (!Object.prototype.hasOwnProperty.call(original, email)) original[email] = value
  }

  after(() => {
    Object.keys(original).forEach((email) => {
      auth(email).then((h) => write(h, KEY, original[email] == null ? '' : original[email]))
    })
  })

  it('THE POINT — a saved setting is visible to the very next read', () => {
    /*
     * The regression this guards is precise: the cache is invalidated in SettingsService.set(), and if
     * that eviction were missed, dropped, or applied to a different instance than the one serving reads,
     * this read would return the OLD value while the database held the new one. An owner would change a
     * setting, see nothing happen, and change it again.
     */
    auth('owner.business@myplus.com').then((h) => {
      read(h, KEY).then((before) => {
        remember('owner.business@myplus.com', before)

        const marker = 'CACHE_GATE_' + Date.now()
        write(h, KEY, marker)
        read(h, KEY).should('eq', marker)

        // Twice, because the second read is the one served FROM the cache the write just repopulated.
        // A stale entry written back by a racing reader would show up here and not on the first.
        read(h, KEY).should('eq', marker)
      })
    })
  })

  it('THE TENANCY GATE — two organisations reading one key get their own values', () => {
    /*
     * A cache keyed on the setting name alone passes every other assertion in this file and leaks one
     * tenant's configuration to another. Nothing else in the suite would notice, because everything else
     * runs as one organisation.
     */
    const a = 'owner.business@myplus.com'
    const b = 'demo.business@myplus.com'
    const markA = 'ORG_A_' + Date.now()
    const markB = 'ORG_B_' + Date.now()

    auth(a).then((ha) => {
      read(ha, KEY).then((beforeA) => {
        remember(a, beforeA)
        write(ha, KEY, markA)

        auth(b).then((hb) => {
          read(hb, KEY).then((beforeB) => {
            remember(b, beforeB)
            write(hb, KEY, markB)

            // Interleaved on purpose: B wrote last, so if the cache were keyed without the org, A would
            // now read B's value. Reading A AFTER B's write is what makes that visible.
            read(ha, KEY).should('eq', markA)
            read(hb, KEY).should('eq', markB)
            // ...and again, both now served from cache.
            read(ha, KEY).should('eq', markA)
            read(hb, KEY).should('eq', markB)
          })
        })
      })
    })
  })

  it('an unset key still reports the catalogue default, and says so', () => {
    // Clearing an override must fall back to the declared default rather than to an empty string — and
    // `isDefault` must agree with `value`, since the Configuration screen renders the two together.
    /*
     * Asserted on a key THESE TESTS NEVER WRITE, picked at run time as any entry still reporting
     * isDefault. The first version asked about KEY behind `if (entry.isDefault === true)` — and by the
     * time it ran, the earlier tests had made that false, so the condition was never entered and the
     * test asserted nothing at all while reporting green.
     */
    auth('owner.business@myplus.com').then((h) => {
      cy.request({ url: `${GW}/api/business/settings`, headers: h }).then((r) => {
        const untouched = r.body.data.filter((e) => e.isDefault === true && e.key !== KEY)
        expect(untouched.length, 'at least one setting is still on its default').to.be.greaterThan(0)
        untouched.slice(0, 10).forEach((e) => {
          expect(e.value, `${e.key} reads as its declared default`).to.eq(e.defaultValue)
        })
      })
    })
  })
})
