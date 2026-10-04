/**
 * SCHED-2 / MAIL-DEV-2 — "Parents' evenings" and "where dev mail goes", as REAL manual test cases for the Test Book.
 *
 * Every action is PERFORMED on the screen, ASSERTED and PHOTOGRAPHED (see support/guide-capture.js). Built by
 * docs/guides/build-testbook-section.js into Test Book section 23.
 *
 *   P1  publish a teacher's evening from the screen          (the Teacher list was EMPTY before SCHED-2)
 *   P2  publish it again — nothing is doubled, the screen says so
 *   P3  extend the evening — only the new part is added
 *   P4  a long evening (48 slots) answers at once             (one read + one write; it used to time out)
 *   D1  on a dev machine, an email the app sends lands in Mailpit, not in a real inbox
 *
 * CLEANUP. Parents' evenings have no delete — Close booking is the only reversible act, and the cases end with
 * it. Their slots stay in the scheduling core for good, which is why every window is a far-future hour owned by
 * this run alone. D1 deletes its own messages from Mailpit.
 *
 * Run headed:
 *   npx cypress run --headed --browser electron --spec cypress/e2e/docs/sched2-guide-screens.cy.js
 */
import { guideCapture } from '../../support/guide-capture'

const g = guideCapture({ outDir: 'cypress/guide-out/sched2', section: "Parents' evenings and dev mail" })
const OWNER = 'owner.education@myplus.com'
const MAILPIT = 'http://localhost:8025'
const RUN = Date.now()
const TAG = `GuideME${String(RUN).slice(-6)}`

// Windows unique to this run (see meetings.cy.js for why): a 16-hour block per run second, on a calendar that
// starts in 2200 so it can never meet meetings.cy.js's (2030+). Offsets: P1/P2 +0 (1 h) · P3 +2 (2 h) · P4 +5 (4 h).
const RUN_SECOND = Math.floor(RUN / 1000)
const at = (offsetHours, hours) => {
  const start = new Date(Date.UTC(2200, 0, 1) + ((RUN_SECOND * 16 + offsetHours) % 4_000_000) * 3_600_000)
  const iso = (d) => d.toISOString().slice(0, 19)
  return { from: iso(start), to: iso(new Date(start.getTime() + hours * 3_600_000)) }
}

const openEvenings = () => {
  cy.openSection('MeetingsDiv', '/educationDashboard')
  cy.get('#meStaffDD option[value!=""]', { timeout: 20000 }).should('have.length.greaterThan', 0)   // real teachers, not the placeholder
}
/** Create an evening on the screen and leave it selected. */
const newEvening = (title) => {
  cy.get('#meTitle').clear().type(title)
  cy.get('#meDate').invoke('val', '15-06-2030').trigger('change')
  cy.intercept('POST', '**/saveMeetingEvent').as('saveEvening')
  cy.get('#MeetingsDiv button[onclick="saveMeetingEvent()"]').click()
  cy.wait('@saveEvening').its('response.body.status').should('eq', 'SUCCESS')
  cy.contains('#tableMeetingEvents tr', title).should('be.visible')
}
const selectEvening = (title) => cy.contains('#tableMeetingEvents tr', title).find('button').first().click()
/** Fill the Publish slots panel and press Publish; yields the response body. */
const publish = ({ from, to }, minutes) => {
  // The first option is the empty "Nothing Selected" placeholder — choose the first real teacher.
  cy.get('#meStaffDD option').filter((i, o) => o.value !== '').first()
    .then(($o) => cy.get('#meStaffDD').select($o.val(), { force: true }))
  cy.get('#meFrom').clear().type(from)
  cy.get('#meTo').clear().type(to)
  cy.get('#meMinutes').clear().type(String(minutes))
  // Time the REQUEST, from the moment it leaves to its answer — not the typing above it.
  cy.intercept('POST', '**/publishMeetingSlots', (req) => {
    const sent = Date.now()
    req.on('response', (res) => { res.headers['x-cy-elapsed-ms'] = String(Date.now() - sent) })
  }).as('publish')
  cy.get('#MeetingsDiv button[onclick="publishMeetingSlots()"]').click()
  return cy.wait('@publish')
}
const closeBooking = (title) => {
  cy.intercept('POST', '**/setMeetingEventStatus').as('close')
  cy.contains('#tableMeetingEvents tr', title).contains('button', /close booking/i).click()
  cy.wait('@close').its('response.body.status').should('eq', 'SUCCESS')
  cy.contains('#tableMeetingEvents tr', title).should('contain', 'closed')
}

describe("Test Book — SCHED-2 Parents' evenings and MAIL-DEV-2 dev mail (captured)", () => {
  beforeEach(() => cy.viewport(1366, 900))
  afterEach(g.write)

  // A case that fails before its cleanup step must not leave its evening OPEN: the guardian portal offers the
  // open evening with the LATEST date, and on 4 Oct four leftover 2030 evenings from a failed run of this spec
  // took that place and broke meetings.cy.js's booking cases. Close every evening this run created, whatever happened.
  after(() => {
    cy.loginAsEduOwner()
    cy.request('/getMeetingEvents').then((r) => {
      (r.body.collection || []).filter((e) => (e.title || '').startsWith(TAG) && e.status === 'OPEN')
        .forEach((e) => cy.request({ method: 'POST', url: '/setMeetingEventStatus', form: true, body: { id: e.id, status: 'CLOSED' } }))
    })
  })

  // ── P1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('P1', "Publish a teacher's evening from the screen", () => {
    const title = `${TAG} P1 Evening`
    const w = at(0, 1)
    g.testCase('P1', "Publish a teacher's evening from the screen — the Teacher list is filled and the slots appear", {
      tenant: OWNER, role: 'Owner (publishing needs the communication.publish permission)',
      purpose: "A school publishes each teacher's ten-minute slots for an evening; families then book them from the portal. Until 4 Oct the Teacher list on this screen was EMPTY — the loader filled a differently named list — so no school could publish by hand, while every automated check used the API and stayed green.",
      prereq: [`Signed in as ${OWNER}.`, 'The school has at least one member of staff.'],
      data: [`Evening **${title}**`, `From **${w.from}** to **${w.to}**, **10**-minute slots (six slots)`],
      rollback: 'Cleanup presses Close booking. An evening and its slots cannot be deleted — closing stops new bookings and is the one reversible step, so use a far-future time that no real evening uses.',
    })
    cy.loginAsEduOwner()

    const a1 = g.act("Open **School → Parents' evenings**. Look at **Publish slots → Teacher**.",
      ['The **Teacher** list names the school\'s staff — it is not empty.'])
    openEvenings()
    cy.get('#meStaffDD option').filter((i, o) => o.value !== '').its('length').then((n) => a1.expect.push(`On this run it lists **${n}** members of staff.`))
    g.snap(a1, 'teacher-list', '#MeetingsDiv')

    const a2 = g.act(`Type the title **${title}**, pick a date, and press **Save**.`,
      ['A green note says the evening was saved, and it appears in the list of evenings, status **open**.'])
    newEvening(title)
    g.snap(a2, 'evening-saved')

    const a3 = g.act(`Choose a **Teacher**, type **From** \`${w.from}\` and **To** \`${w.to}\`, leave **10** minutes, and press **Publish**.`,
      ['A green note reads **Slots published — {created=6, alreadyExisted=0}**.',
        'The slot list below shows six slots, each with the **teacher\'s name** and its times.'])
    publish(w, 10).its('response.body.status').should('eq', 'SUCCESS')
    cy.get('#meMsg').should('have.class', 'alert-success').and('contain', 'created=6').and('contain', 'alreadyExisted=0')
    cy.get('#tableMeetingSlots tbody tr').should('have.length', 6)
    cy.get('#tableMeetingSlots tbody tr').first().find('td').first().invoke('text').should('not.be.empty')
    g.snap(a3, 'published')

    const c1 = g.act(`In the list of evenings, press **Close booking** on **${title}**.`,
      ['Its status reads **closed**. Nothing is deleted — closing only stops new bookings.'], { cleanup: true })
    closeBooking(title)
    g.snap(c1, 'closed')
  })

  // ── P2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('P2', 'Publish the same evening again — nothing is doubled', () => {
    const title = `${TAG} P2 Evening`
    const w = at(1, 1)
    g.testCase('P2', 'Publish the same evening again — nothing is doubled, and the screen says so', {
      tenant: OWNER, role: 'Owner',
      purpose: 'Pressing Publish twice — or again after a slow answer — must never give a family two copies of one slot. The second press reports what already exists instead of failing.',
      prereq: [`Signed in as ${OWNER}.`],
      data: [`Evening **${title}**`, `From **${w.from}** to **${w.to}**, 10-minute slots`],
      rollback: 'Cleanup presses Close booking (evenings cannot be deleted).',
    })
    cy.loginAsEduOwner()
    openEvenings()

    const a1 = g.act(`Create the evening **${title}** and publish one teacher from \`${w.from}\` to \`${w.to}\` at 10 minutes.`,
      ['**Slots published — {created=6, alreadyExisted=0}**; six slots listed.'])
    newEvening(title)
    publish(w, 10).its('response.body.status').should('eq', 'SUCCESS')
    cy.get('#meMsg').should('contain', 'created=6')
    g.snap(a1, 'first-publish')

    const a2 = g.act('Without changing anything, press **Publish** again.',
      ['The note now reads **Slots published — {created=0, alreadyExisted=6}**.', 'The slot list still shows **six** slots, not twelve.'])
    publish(w, 10).its('response.body.status').should('eq', 'SUCCESS')
    cy.get('#meMsg').should('contain', 'created=0').and('contain', 'alreadyExisted=6')
    cy.get('#tableMeetingSlots tbody tr').should('have.length', 6)
    g.snap(a2, 'republish')

    const c1 = g.act(`Press **Close booking** on **${title}**.`, ['Its status reads **closed**.'], { cleanup: true })
    closeBooking(title)
    g.snap(c1, 'closed')
  })

  // ── P3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('P3', 'Extend an evening — only the new part is added', () => {
    const title = `${TAG} P3 Evening`
    const first = at(2, 1)
    const longer = at(2, 2)
    g.testCase('P3', 'Extend an evening — only the new part is added', {
      tenant: OWNER, role: 'Owner',
      purpose: 'A school that adds an hour to an evening re-publishes the whole longer window. It must get the extra hour, not an error about the hour it already published.',
      prereq: [`Signed in as ${OWNER}.`],
      data: [`Evening **${title}**`, `First \`${first.from}\` → \`${first.to}\`, then \`${longer.from}\` → \`${longer.to}\``],
      rollback: 'Cleanup presses Close booking.',
    })
    cy.loginAsEduOwner()
    openEvenings()

    const a1 = g.act(`Create **${title}** and publish \`${first.from}\` → \`${first.to}\` at 10 minutes.`,
      ['**{created=6, alreadyExisted=0}**.'])
    newEvening(title)
    publish(first, 10)
    cy.get('#meMsg').should('contain', 'created=6')
    g.snap(a1, 'first-hour')

    const a2 = g.act(`Change **To** to \`${longer.to}\` (an hour later) and press **Publish**.`,
      ['**{created=6, alreadyExisted=6}** — the six new slots added, the six existing ones left alone.', 'The slot list shows **twelve** slots.'])
    publish(longer, 10)
    cy.get('#meMsg').should('contain', 'created=6').and('contain', 'alreadyExisted=6')
    cy.get('#tableMeetingSlots tbody tr').should('have.length', 12)
    g.snap(a2, 'extended')

    const c1 = g.act(`Press **Close booking** on **${title}**.`, ['Its status reads **closed**.'], { cleanup: true })
    closeBooking(title)
    g.snap(c1, 'closed')
  })

  // ── P4 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('P4', 'A long evening (48 slots) answers at once', () => {
    const title = `${TAG} P4 Evening`
    const w = at(5, 4)
    g.testCase('P4', 'A long evening — 48 slots — publishes at once, and again at once', {
      tenant: OWNER, role: 'Owner',
      purpose: '⚠ Found 4 Oct: publishing took a separate database transaction per slot. One call in three ran past the 3-second limit and the screen said ERROR — for slots that HAD been made. It is now one read and one write; if the scheduling service still does not answer, the app asks once more and then says the slots may already be published, never a bare error.',
      prereq: [`Signed in as ${OWNER}.`],
      data: [`Evening **${title}**`, `\`${w.from}\` → \`${w.to}\` at **5** minutes (48 slots)`],
      rollback: 'Cleanup presses Close booking.',
    })
    cy.loginAsEduOwner()
    openEvenings()

    const a1 = g.act(`Create **${title}** and publish \`${w.from}\` → \`${w.to}\` at **5** minutes.`,
      ['Within a second or two: **{created=48, alreadyExisted=0}**, and 48 slots listed.', 'Never **ERROR**, and never a message mentioning "I/O error" or "timed out".'])
    newEvening(title)
    publish(w, 5).then((x) => {
      const ms = Number(x.response.headers['x-cy-elapsed-ms'])
      expect(x.response.body.status).to.eq('SUCCESS')
      expect(ms, 'Publish answered well inside the 3-second limit').to.be.lessThan(2500)
      a1.expect.push(`On this run the answer came back in **${(ms / 1000).toFixed(1)} s**.`)
    })
    cy.get('#meMsg').should('contain', 'created=48').and('not.contain', 'I/O error')
    cy.get('#tableMeetingSlots tbody tr').should('have.length', 48)
    g.snap(a1, 'published-48')

    const a2 = g.act('Press **Publish** again.', ['**{created=0, alreadyExisted=48}** — at once, nothing doubled.'])
    publish(w, 5)
    cy.get('#meMsg').should('contain', 'created=0').and('contain', 'alreadyExisted=48')
    g.snap(a2, 'republish-48')

    const c1 = g.act(`Press **Close booking** on **${title}**.`, ['Its status reads **closed**.'], { cleanup: true })
    closeBooking(title)
    g.snap(c1, 'closed')
  })

  // ── D1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('D1', 'On a dev machine, email goes to Mailpit, never to a real inbox', () => {
    const heading = `${TAG} dev mail check`
    let queued = 0
    g.testCase('D1', 'On a development machine, an email the app sends lands in Mailpit — never in a real inbox', {
      tenant: `${OWNER} on the DEVELOPMENT stack`, role: 'Owner (System Alerts needs ADMIN or the SYSTEM_ALERTS permission)',
      purpose: '⚠ Found 3 Oct: test runs sent hundreds of real emails through the Gmail account the PRODUCTION app also sends from, and Gmail locked it out. Development now sends every email to a local catcher, Mailpit. This case proves it on your machine. Production is not affected — it never has the Mailpit setting.',
      prereq: ['A development machine with Mailpit running (`docker compose up -d mailpit`).', `Signed in as ${OWNER}.`],
      data: [`Alert heading **${heading}**`, 'Audience **Employees**'],
      rollback: 'Cleanup deletes the test emails in Mailpit. The alert itself is a message already sent; there is nothing else to undo.',
    })
    cy.request(`${MAILPIT}/api/v1/info`).its('status').should('eq', 200)
    cy.loginAsEduOwner()

    const a1 = g.act(`Open **Alerts → System Alerts**. Type the heading **${heading}** and a short message, choose **Employees** under consumers, and press **Send**.`,
      ['A box reads **Queued for N of N recipient(s)** — one for every member of staff with an email address.'])
    cy.visit('/educationDashboard')
    cy.get('#communicationType').select('AlertsDiv', { force: true })
    cy.get('#AlertsDiv').should('be.visible')
    cy.get('#alertHeading').clear().type(heading)
    cy.get('#alertMessage').clear().type('Checking that development mail stays on this machine.')
    cy.get('#alertConsumers').select(['Employees'], { force: true })
    g.snap(a1, 'alert-form', '#AlertsDiv')
    cy.intercept('POST', '**/sendAlerts').as('send')
    cy.get('#sendAlerts').click()
    // The confirmation box shows the server's message verbatim (education.js sendAlert → alert(res.message)).
    cy.wait('@send').its('response.body').then((b) => {
      expect(b.status).to.eq('SUCCESS')
      expect(b.message, 'the confirmation').to.match(/Queued for [1-9]\d* of \d+ recipient/)
      queued = Number(b.message.match(/Queued for (\d+)/)[1])
      a1.expect.push(`On this run it said **${b.message}**.`)
    })

    const a2 = g.act('Open **http://localhost:8025** (Mailpit) in another tab.',
      [`Within a minute the inbox lists the alert, subject **${heading}**, once per recipient.`, 'Nothing was sent to the real addresses — Mailpit is a catcher on this machine.'])
    const search = () => cy.request(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`subject:"${heading}"`)}`)
    // Wait for EVERY queued copy, not the first: the relay delivers them over a few seconds.
    const poll = (n = 0) => search().then((r) => {
      if (r.body.messages_count >= queued) {
        a2.expect.push(`On this run all **${r.body.messages_count}** copies arrived.`)
        return
      }
      expect(n, `all ${queued} copies reached Mailpit within ~90 s (have ${r.body.messages_count})`).to.be.lessThan(45)
      cy.wait(2000, { log: false }).then(() => poll(n + 1))
    })
    poll()
    cy.origin(MAILPIT, { args: { heading } }, ({ heading: h }) => {
      cy.visit('/')
      cy.contains(h, { timeout: 20000 }).should('be.visible')
    })
    g.snap(a2, 'mailpit-inbox')

    const c1 = g.act('In Mailpit, select the test emails and press **Delete**.', ['They are gone from the inbox.'], { cleanup: true })
    search().then((r) => cy.request({ method: 'DELETE', url: `${MAILPIT}/api/v1/messages`, body: { IDs: r.body.messages.map((m) => m.ID) } }))
    search().its('body.messages_count').should('eq', 0)
    cy.origin(MAILPIT, () => { cy.visit('/') })
    g.snap(c1, 'mailpit-cleaned')
  })
})
