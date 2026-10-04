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
/**
 * A window no other run can have — ⚠ the old rule was not that (SCHED-2, 2026-10-04).
 *
 * Slots are keyed by uk_slot_provider_time (org, provider, startsAt) and are never deleted, so every run leaves
 * its windows behind for good. The old rule took the hour from RUN % 22 and the minute from the run's second on
 * ONE fixed date: 1,320 windows, filled up over weeks of runs, and on 2026-10-04 a run's FIRST publish answered
 * `created: 0` because an older run already owned that hour — read at first as the timeout defect it was not.
 *
 * Now each run second owns its own HOUR on a calendar from 2030: two runs collide only if they start in the same
 * second.
 */
const RUN_SECOND = Math.floor(RUN / 1000)
// Each run second owns a 16-hour block; window k starts at a fixed offset inside it and never overlaps another
// (k=0: 1 h at +0 · k=1..3: 4 h at +1, +5, +9 · k=4: 1 h at +13 → the block ends at +14, short of the next run's +16).
const OFFSET_HOURS = [0, 1, 5, 9, 13]
const windowAt = (k, hours) => {
  const start = new Date(Date.UTC(2030, 0, 1) + ((RUN_SECOND * 16 + OFFSET_HOURS[k]) % 4_000_000) * 3_600_000)
  const iso = (d) => d.toISOString().slice(0, 19)           // 2030-…T…:00:00 — the zone-less form the API takes
  return { from: iso(start), to: iso(new Date(start.getTime() + hours * 3_600_000)) }
}
const FIRST = windowAt(0, 1)
const FROM = FIRST.from
const TO = FIRST.to
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
      // Dated far beyond any other spec's evenings: the guardian portal offers the OPEN evening with the LATEST
      // date, so a leftover open evening dated later than this one used to take its place (4 Oct, SCHED-2).
      post('/saveMeetingEvent', { title: TAG + ' Evening', eventDateStr: '15-06-2999' })
        .then((r) => { fx.eventId = ok(r, 'create the evening').object.id })
    })
  })

  after(() => {
    cy.loginAsEduOwner()
    setConfig('edu.portal.enabled', 'true')
    // CLOSE this run's evening. Every run creates its own, so leaving it OPEN bought nothing — and every run
    // left one more open evening on the school's Parents' evenings screen (SCHED-2 review, 2026-10-04).
    // Closing stops new bookings only; it deletes nothing, and no other spec relies on an open evening.
    if (fx.eventId) post('/setMeetingEventStatus', { id: fx.eventId, status: 'CLOSED' })
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

  /*
   * SCHED-2 — a big evening is ONE read and ONE write in the scheduling core.
   *
   * Publishing used to be a separate transaction per slot (and, on a re-publish, a failed INSERT per slot). On a
   * cold call that overran education's 3 s read timeout, and the school saw ERROR for slots that had been made —
   * this spec's first case failed exactly so on 2026-10-04. 48 slots is a long evening at five minutes each; both
   * the first publish and the re-publish must answer well inside the timeout.
   */
  it('SCHED-2: three 48-slot evenings publish and re-publish — all six answers succeed, each well inside 3 s', () => {
    // Measured on the unfixed build (2026-10-04, probe): 4 of 6 such calls took ~630 ms and 2 ran past 3000 ms
    // and FAILED — two pooled connections per publish (an outer transaction plus a REQUIRES_NEW per slot) stall
    // whenever the pool is busy. One call proves little; six in a row is what the old shape could not do.
    cy.loginAsEduOwner()
    ;[1, 2, 3].forEach((k) => {
      const { from, to } = windowAt(k, 4)   // four hours, its own block — never this run's first evening
      post('/publishMeetingSlots', { eventId: fx.eventId, staffId: fx.staffId, from, to, minutes: 5 }).then((r) => {
        const b = ok(r, `publish evening ${k}`)
        expect(b.object.created, 'four hours in five-minute slots').to.eq(48)
        expect(r.duration, `evening ${k} answered in ${r.duration} ms — the client gives up at 3000`).to.be.lessThan(2500)
      })
      post('/publishMeetingSlots', { eventId: fx.eventId, staffId: fx.staffId, from, to, minutes: 5 }).then((r) => {
        const b = ok(r, `re-publish evening ${k}`)
        expect(b.object.created, 'nothing doubled').to.eq(0)
        expect(b.object.alreadyExisted, 'every slot reported as already there').to.eq(48)
        expect(r.duration, `re-publish ${k} answered in ${r.duration} ms`).to.be.lessThan(2500)
      })
    })
  })

  /*
   * SCHED-2 — the SCREEN, not just the endpoint. Every case above publishes through the API, and all of them
   * stayed green while the Teacher dropdown on School → Parents' evenings was EMPTY: the loader filled
   * #meStaffDD and the screen's select was #meStaff. No school could publish an evening by hand.
   */
  it("SCHED-2: the Parents' evenings SCREEN lists the teachers and publishes slots from the form", () => {
    cy.loginAsEduOwner()
    cy.openSection('MeetingsDiv', '/educationDashboard')
    cy.get('#meStaffDD option[value!=""]', { timeout: 20000 }).should('have.length.greaterThan', 0)   // real teachers, not the placeholder
    // Select this run's evening the way a person does: its row's Slots button.
    cy.contains('#tableMeetingEvents tr', TAG + ' Evening').find('button').first().click()
    cy.get('#meStaffDD').select(String(fx.staffId), { force: true })
    const { from, to } = windowAt(4, 1)
    cy.get('#meFrom').clear().type(from)
    cy.get('#meTo').clear().type(to)
    cy.get('#meMinutes').clear().type('10')
    cy.intercept('POST', '**/publishMeetingSlots').as('publish')
    cy.get('#MeetingsDiv button[onclick="publishMeetingSlots()"]').click()
    cy.wait('@publish').its('response.body.status').should('eq', 'SUCCESS')
    cy.get('#meMsg').should('be.visible').and('have.class', 'alert-success').and('contain', 'created=6')
    cy.get('#tableMeetingSlots tbody tr').should('have.length.at.least', 6)
    cy.get('#tableMeetingSlots tbody tr').first().find('td').first().invoke('text').should('not.be.empty')
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
