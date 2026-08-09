/**
 * Slice 105 — the notification delivery record.
 * Design: microservices/docs/slices/105-notification-multichannel-broadcast.md (G3, D3)
 *
 * The rules are asserted as pure unit tests (DeliveryRecorderTest, DeliveryDispatcherTest,
 * NotificationServiceTest, EmailServiceTest — 27 cases, every `mvn test`). Asserted HERE is only what a
 * unit test cannot reach: that the record survives the real hop across two services, a relay thread and a
 * database.
 *
 *   - sending an alert LEAVES A ROW, per recipient, readable afterwards          ← G3, the whole slice
 *   - it is recorded from the RELAY thread, which has no security context        ← the tenant must come
 *     from the outbox row; resolving it from CurrentUser there yields null and writes a row nobody can
 *     ever read — invisible loss dressed up as a successful send
 *   - the row names WHICH flow asked and what happened to that person            ← D3
 *   - re-sending the identical alert does NOT double the record                  ← the dedupe key
 *   - the read is TENANT-SCOPED: a business login cannot read a school's mail    ← anti-IDOR
 *
 * WHAT "SENT" MEANS: the mail server ACCEPTED the message — not that it reached an inbox. Bounces happen
 * after this point and are invisible without provider webhooks, which this slice does not build. So this
 * spec asserts a row EXISTS with a known outcome, and NEVER that mail arrived. Asserting SENT would make
 * the gate depend on a live SMTP server, which is the flakiness the slice exists to remove.
 *
 * FIXTURE IS SEEDED, NEVER SKIPPED. The spec creates its own staff member with a unique address, because
 * the delivery record is keyed by recipient and a shared demo address would collide across runs.
 *
 * Requires: notification-service rebuilt (new datasource + V1) and restarted, education-service rebuilt
 * (relay passes the tenant), monolith rebuilt (the new proxy), gateway up. Run headed.
 */
const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)

const rows = (body) => {
  const b = parse(body)
  if (Array.isArray(b)) return b
  if (b && Array.isArray(b.collection)) return b.collection
  const k = b && Object.keys(b).find((x) => Array.isArray(b[x]))
  return k ? b[k] : []
}

const post = (url, body) =>
  cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

const ok = (r, what) => {
  const b = parse(r.body)
  expect(b.status, `${what}: ${JSON.stringify(b).slice(0, 300)}`).to.eq('SUCCESS')
  return b
}

/** Deliveries recorded for one address. The proxy passes NO tenant — it comes from the JWT. */
const deliveriesFor = (email) =>
  cy.request({
    url: `/getNotificationDeliveries?recipient=${encodeURIComponent(email)}&limit=100`,
    failOnStatusCode: false
  })

/**
 * Poll until a row appears. The send is QUEUED (slice 3.5) and delivered by the relay just after commit,
 * so the row is written on another thread a moment later — a bare read would race it. Bounded, and it
 * FAILS rather than passing quietly when nothing ever arrives.
 */
const waitForDelivery = (email, attempt = 0) =>
  deliveriesFor(email).then((r) => {
    const list = r.status === 200 ? rows(r.body) : []
    if (list.length > 0) return cy.wrap(list, { log: false })
    if (attempt >= 12) {
      throw new Error(
        `No delivery recorded for ${email} after ~12s. ` +
        `Last status ${r.status}: ${String(r.body).slice(0, 300)}`
      )
    }
    return cy.wait(1000, { log: false }).then(() => waitForDelivery(email, attempt + 1))
  })

const TAG = 'CyN105' + Date.now()
/** Unique per run, so a re-run can never collide with the previous run's dedupe keys. */
const RECIPIENT = `${TAG}@example.test`.toLowerCase()
const HEADING = `${TAG} closure notice`

const sendAlert = (heading, message) =>
  post('/sendAlerts', { ah: heading, am: message, c: 'staff' })

describe('Education — notification delivery record (slice 105)', () => {
  before(() => {
    cy.loginAsEduOwner()
    // Our own staff member IS the fixture: the record is keyed by recipient, so the spec needs an address
    // it owns outright rather than one the demo org may or may not still have.
    post('/addStaff', { name: `${TAG} Staff`, email: RECIPIENT, status: 'ACTIVE' })
      .then((r) => ok(r, 'seed the staff member who will be alerted'))

    cy.request('/getUserStaff').then((r) => {
      const seeded = rows(r.body).find((s) => s.email === RECIPIENT)
      // Verify the fixture is what it claims to be — `addStaff` binding the email is the precondition the
      // entire spec rests on, and asserting it here fails loudly instead of producing an empty record.
      expect(seeded, `the staff member with ${RECIPIENT} was seeded`).to.exist
    })
  })

  beforeEach(() => {
    // testIsolation clears the session between tests, so every authed cy.request needs its own login.
    cy.loginAsEduOwner()
  })

  // ── the record exists at all ────────────────────────────────────────────────────────────────────

  it('sending an alert leaves a per-recipient delivery row', () => {
    // THE case the slice exists for. Before it, this address appeared NOWHERE on the platform, whether
    // the send worked or not — "did that family get the closure notice?" had no answer anywhere.
    sendAlert(HEADING, 'School closed Friday.').then((r) => {
      const b = ok(r, 'the alert is accepted')
      expect(b.object.queued, 'at least our seeded recipient was queued').to.be.greaterThan(0)
    })

    waitForDelivery(RECIPIENT).then((list) => {
      const row = list[0]
      expect(row.recipient).to.eq(RECIPIENT)
      expect(row.deliveryId, 'the row is addressable').to.exist
      expect(row.attempts, 'the attempt was counted').to.be.greaterThan(0)
      // Any settled outcome is a pass. The point is that it is WRITTEN DOWN, not that SMTP works here.
      expect(row.status, 'the outcome is recorded, whatever it was')
        .to.be.oneOf(['PENDING', 'SENT', 'FAILED'])
    })
  })

  it('the row is attributed to the tenant even though the relay has no logged-in user', () => {
    // The defect this case exists to catch: the relay sends on a SCHEDULER thread with no security
    // context. Resolving the tenant from CurrentUser there returns null, and because the read endpoints
    // are tenant-scoped, the row would be written and then be unreadable forever. It would look exactly
    // like a successful send — which is why only an end-to-end case can catch it.
    waitForDelivery(RECIPIENT).then((list) => {
      expect(list.length, 'the school can read back what its own relay sent').to.be.greaterThan(0)
    })
  })

  it('a failure records WHY, instead of losing it to a log line', () => {
    waitForDelivery(RECIPIENT).then((list) => {
      const row = list[0]
      if (row.status === 'SENT') {
        expect(row.sentAt, 'a success carries when it went').to.exist
      } else {
        // The honest-failure case, and what a school actually needs: not merely "it failed" but a reason
        // someone can act on. Before this slice, this was a log line and nothing else.
        expect(row.lastError, 'a failure carries its reason').to.be.a('string').and.not.be.empty
      }
    })
  })

  // ── idempotency ────────────────────────────────────────────────────────────────────────────────

  it('re-sending the identical alert does not double the record', () => {
    // Both sides retry — education's relay re-POSTs on a timeout — so a duplicate is a certainty, not a
    // risk. Without the dedupe key the school's staff get the same closure notice twice.
    waitForDelivery(RECIPIENT).then((before) => {
      const countBefore = before.length

      sendAlert(HEADING, 'School closed Friday.')
      cy.wait(3000)   // give the relay the same chance to write a duplicate that it had to write the first
      deliveriesFor(RECIPIENT).then((after) => {
        expect(rows(after.body).length,
          'the identical message to the same person is deduplicated, not recorded twice')
          .to.eq(countBefore)
      })
    })
  })

  it('a DIFFERENT message to the same person is recorded separately', () => {
    // The other half of the contract, and the more dangerous direction. A key that swallowed genuinely
    // new notices would be far worse than no key: the recipient would silently stop being told anything.
    deliveriesFor(RECIPIENT).then((before) => {
      const countBefore = rows(before.body).length

      sendAlert(`${HEADING} — UPDATED`, 'Correction: school reopens Monday.')
      cy.wait(3000)
      deliveriesFor(RECIPIENT).then((after) => {
        expect(rows(after.body).length, 'a genuinely new notice is still recorded')
          .to.be.greaterThan(countBefore)
      })
    })
  })

  // ── tenancy ────────────────────────────────────────────────────────────────────────────────────

  it('another tenant cannot read this school\'s delivery history', () => {
    // An email address is NOT unique across tenants — a parent may be a guardian at two schools on this
    // platform. An unscoped lookup would disclose one school's correspondence to another through a
    // support screen. The scope is applied in the QUERY, not filtered afterwards.
    cy.loginAsOwner()   // owner.business@myplus.com — a different tenant entirely
    deliveriesFor(RECIPIENT).then((r) => {
      const list = r.status === 200 ? rows(r.body) : []
      expect(list.filter((d) => d.recipient === RECIPIENT).length,
        'a different tenant sees none of the school\'s deliveries').to.eq(0)
    })
  })

  // ── the inverse regression ─────────────────────────────────────────────────────────────────────

  it('alerts still behave exactly as they did — recording was ADDED, nothing was narrowed', () => {
    // Slice 3.5's contract must be untouched: the send path gained a record, it did not change its answer.
    // `alerts.cy.js` is the full regression and is re-run alongside this spec.
    sendAlert(`${TAG} regression`, 'B').then((r) => {
      const b = ok(r, 'the alert still succeeds')
      expect(Object.keys(b.object), 'the counts callers already relied on are still there')
        .to.include.members(['queued', 'recipients'])
      expect(b.message, 'and it still reports QUEUED, not a send it did not make').to.match(/queued/i)
      // ASSERT THE COUNT, not just the shape. As first written this case checked that `queued` existed
      // and that the message said "queued" — both of which zero satisfies. It therefore passed against
      // "Queued for 0 of 40 recipient(s)" while alerts sent absolutely nothing, which is precisely how
      // that bug survived 3.5's gate, N1's gate and a six-spec regression list.
      expect(b.object.queued, 'the alert actually queued somebody').to.be.greaterThan(0)
      expect(b.object.queued, 'and it queued every resolved recipient, not a subset')
        .to.eq(b.object.recipients)
    })
  })
})
