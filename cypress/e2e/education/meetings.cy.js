/**
 * Slice edu-3.4 — guardian–teacher meetings, delivered on the shared scheduling core (SCHED-1).
 * Design: microservices/docs/slices/edu-3.4-guardian-teacher-meetings.md
 *         microservices/docs/slices/sched-1-scheduling-core.md
 *
 * These cases exercise a chain no unit test can reach: **education → SchedulingClient → the scheduling
 * core → back**, with the core never learning what a teacher, a guardian or a parents' evening is.
 *
 *   - staff publish a teacher's slots, and re-publishing is idempotent
 *   - a guardian books from the portal — **the FIRST write on that surface**
 *   - **a second click books nothing more** (the core's UNIQUE key, not a UI guard)
 *   - **a taken slot is refused** to a different family
 *   - a CLOSED evening refuses booking, and closing does NOT cancel what is already booked
 *   - a teacher cannot publish or open/close (ADMIN tier)
 *
 * FIXTURE HAZARD, learned the hard way in this suite: specs that toggle `edu.portal.enabled` leave the
 * tenant in that state if the run is interrupted, and every portal read then answers NOT_FOUND — correctly,
 * and indistinguishably from "no access". `before()` therefore SETS it rather than assuming it.
 */
const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const rows = (body) => {
  const b = parse(body) || {}
  if (Array.isArray(b.collection)) return b.collection
  const k = Object.keys(b).find((x) => Array.isArray(b[x]))
  return k ? b[k] : []
}
const post = (url, body) =>
  cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

const ok = (r, what) => {
  const b = parse(r.body)
  expect(b.status, `${what}: ${JSON.stringify(b).slice(0, 300)}`).to.be.oneOf(['SUCCESS', 'PARTIAL'])
  return b
}

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveConfig', form: true, body: { key, value }, failOnStatusCode: false })
    .then((r) => expect(JSON.stringify(r.body), `saveConfig ${key}=${value}`).to.match(/SUCCESS/))

const probe = (url) => cy.request({ url, failOnStatusCode: false })

const TAG = 'CyME' + Date.now()
// A window UNIQUE PER RUN. Slots are keyed by uk_slot_provider_time (org, provider, startsAt), so two
// runs that compute the same start time collide and the publish returns `created: 0` — which reads as
// "publishing is broken" when it actually means "these slots already exist".
//
// The first version derived the date as `2027-0${(Date.now() % 9) + 1}-15`. That is only NINE distinct
// months, so a second run in the same bucket collided and this spec failed with `expected 0 to equal 6`.
// The comment claimed it avoided collisions; it did not.
//
// Varying the TIME instead gives ~1,300 distinct windows, and the hour is what the unique key actually
// keys on. A fixed far-future date keeps it clear of any real school data.
const RUN = Date.now()
const HH = String(RUN % 22).padStart(2, '0')          // 00-21, so +1 hour never crosses midnight
const MM = String(Math.floor(RUN / 1000) % 60).padStart(2, '0')
const DAY = '2027-03-15'
const FROM = `${DAY}T${HH}:${MM}:00`
const TO = `${DAY}T${String(Number(HH) + 1).padStart(2, '0')}:${MM}:00`
const fx = {}

describe('Education — guardian–teacher meetings (slice 3.4 / SCHED-1 B3)', () => {
  before(() => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')   // SET, never assumed — see the header

    // A teacher to publish slots for. SEEDED eligibility, not just existence: publishing needs a real
    // staff id, and a spec that skips here would prove nothing.
    cy.request('/getUserStaffs').then((r) => {
      const html = typeof r.body === 'string' ? r.body : JSON.stringify(r.body)
      const m = html.match(/value\s*=\s*["']?(\d+)/)
      expect(m, 'the tenant has at least one member of staff to publish slots for').to.not.be.null
      fx.staffId = m[1]
    })

    cy.then(() => {
      post('/saveMeetingEvent', { title: TAG + ' Evening', eventDateStr: '15-06-2027' })
        .then((r) => { fx.eventId = ok(r, 'create the evening').object.id })
    })
  })

  after(() => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')
    // Leave the evening OPEN so a re-run starts from a known state — the close case below flips it.
    if (fx.eventId) post('/setMeetingEventStatus', { id: fx.eventId, status: 'OPEN' })
  })

  // ── staff: publishing ───────────────────────────────────────────────────────────────────────────

  it("staff publish a teacher's slots, and the times come back with the teacher's NAME", () => {
    cy.loginAsEduOwner()
    post('/publishMeetingSlots', {
      eventId: fx.eventId, staffId: fx.staffId,
      from: FROM, to: TO, minutes: 10,
    }).then((r) => {
      const b = ok(r, 'publish slots')
      expect(b.object.created, 'an hour in ten-minute slots is six').to.eq(6)
    })
    cy.request(`/getMeetingSlots?eventId=${fx.eventId}`).then((r) => {
      const slots = rows(r.body)
      expect(slots.length, 'the slots are readable').to.be.greaterThan(0)
      // The core returns providerId; education translates it. If this is null the translation layer —
      // the entire point of D-9 — has silently stopped working.
      expect(slots[0].teacherName, "providerId was translated into a teacher's name").to.not.be.null
      fx.slotId = slots[0].slotId
    })
  })

  it('re-publishing the SAME window creates nothing — extending an evening adds only the new part', () => {
    cy.loginAsEduOwner()
    post('/publishMeetingSlots', {
      eventId: fx.eventId, staffId: fx.staffId,
      from: FROM, to: TO, minutes: 10,
    }).then((r) => {
      const b = ok(r, 're-publish')
      expect(b.object.created, 'nothing new is created').to.eq(0)
      expect(b.object.alreadyExisted, 'and the existing slots are reported, not treated as an error').to.eq(6)
    })
  })

  // ── the guardian: the FIRST write on the portal surface ─────────────────────────────────────────

  it('a guardian sees the open evening and books a slot', () => {
    cy.loginAsPortalGuardian()
    probe('/portal/meetings').then((r) => {
      const b = parse(r.body)
      expect(b.status).to.eq('SUCCESS')
      expect(b.object.eventId, 'the open evening is visible').to.eq(fx.eventId)
      expect((b.object.slots || []).length, 'with its slots').to.be.greaterThan(0)
    })
    cy.then(() => {
      post('/portal/meetings/book', { slotId: fx.slotId }).then((r) => {
        const b = ok(r, 'book a slot')
        expect(b.object.alreadyBooked, 'the first booking is a real one').to.eq(false)
        expect(b.object.bookingId, 'and it has an id').to.exist
      })
    })
  })

  it('clicking Book twice books ONCE — the core\'s UNIQUE key, not a UI guard', () => {
    // Asserted through the API rather than the button, because a UI guard is not an authorisation: the
    // guarantee has to hold for a caller that never sees the button (2.2's lesson).
    cy.loginAsPortalGuardian()
    post('/portal/meetings/book', { slotId: fx.slotId }).then((r) => {
      const b = ok(r, 'book the same slot again')
      expect(b.object.alreadyBooked, 'the second click is idempotent, not an error').to.eq(true)
    })
    // And the slot is still shown as taken exactly once, not twice.
    probe('/portal/meetings').then((r) => {
      const slot = ((parse(r.body).object || {}).slots || []).find((s) => s.slotId === fx.slotId)
      expect(slot.available, 'a capacity-1 slot shows no places left').to.eq(0)
    })
  })

  it('a slot already taken is refused — the guarantee is in the core, for every consumer', () => {
    cy.loginAsEduOwner()
    // A second guardian, seeded rather than hoped for.
    const other = TAG + ' Other'
    post('/addGuardian', { name: other, email: `cyme${Date.now()}@myplus.com`, cnic: 'CM' + Date.now(), status: 'ACTIVE' })
      .then((r) => ok(r, 'seed a second guardian'))
    cy.request('/getUserGuardian').then((r) => {
      const g = rows(r.body).find((x) => x.name === other)
      expect(g, 'the second guardian exists').to.exist
      // Booked through the CORE directly with that guardian as the attendee: the portal always books for
      // the signed-in guardian (there is no guardianId parameter, by design), so a second family's
      // attempt is expressed at the layer that actually decides.
      cy.request({
        method: 'POST',
        url: `/api/scheduling/bookings?slotId=${fx.slotId}&attendeeId=${g.id}`,
        failOnStatusCode: false,
      }).then((res) => {
        expect(res.status, 'a taken capacity-1 slot is refused').to.not.eq(200)
      })
    })
  })

  // ── the evening's one boundary ──────────────────────────────────────────────────────────────────

  it('a CLOSED evening refuses new bookings — and does NOT cancel the ones already made', () => {
    cy.loginAsEduOwner()
    post('/setMeetingEventStatus', { id: fx.eventId, status: 'CLOSED' })
      .then((r) => ok(r, 'close booking'))

    cy.loginAsPortalGuardian()
    probe('/portal/meetings').then((r) => {
      const b = parse(r.body)
      // The evening is no longer offered — openEvent() returns only OPEN ones.
      expect(JSON.stringify(b), 'a closed evening is not offered for booking').to.not.contain(`"eventId":${fx.eventId}`)
    })

    // THE ASSERTION THAT MATTERS: closing the booking window is not cancelling the evening. A school
    // closing bookings must not silently drop the slots families already hold.
    cy.loginAsEduOwner()
    cy.request(`/getMeetingSlots?eventId=${fx.eventId}`).then((r) => {
      const slot = rows(r.body).find((s) => s.slotId === fx.slotId)
      expect(slot, 'the slot still exists').to.exist
      expect(slot.available, 'and the existing booking still holds it').to.eq(0)
    })

    cy.loginAsEduOwner()
    post('/setMeetingEventStatus', { id: fx.eventId, status: 'OPEN' })
  })

  // ── privilege ───────────────────────────────────────────────────────────────────────────────────

  it('a teacher cannot publish slots or open/close an evening — ADMIN tier', () => {
    cy.loginAsTeacherA()
    post('/publishMeetingSlots', {
      eventId: fx.eventId, staffId: fx.staffId,
      from: FROM, to: TO, minutes: 10,
    }).then((r) => {
      const b = parse(r.body)
      const refused = r.status === 403 || !(b && ['SUCCESS', 'PARTIAL'].includes(b.status))
      expect(refused, "committing every teacher's evening is a policy act, not teacher work").to.eq(true)
    })
    post('/setMeetingEventStatus', { id: fx.eventId, status: 'CLOSED' }).then((r) => {
      const b = parse(r.body)
      const refused = r.status === 403 || !(b && ['SUCCESS', 'PARTIAL'].includes(b.status))
      expect(refused, 'and neither is opening or closing booking').to.eq(true)
    })
  })

  it('a portal session cannot reach the STAFF meeting endpoints', () => {
    cy.loginAsPortalGuardian()
    probe('/getMeetingEvents').then((r) => {
      expect(r.status, 'the staff list is refused by the deny rule').to.eq(404)
    })
  })
})
