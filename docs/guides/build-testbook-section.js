/**
 * Builds one Test Book section of step-by-step cases from what a guide spec captured.
 *
 *   node docs/guides/build-testbook-section.js <section> <out.html>        section: eduidor | sched2 | pricemode
 *
 * Input:  cypress/guide-out/<dir>/<ID>.json   one file per case (actions, expected results, cleanup, pictures),
 *         written by the guide spec through cypress/support/guide-capture.js
 * Output: the <section> HTML, using only the Test Book's own classes (.ck, .note, .tag, .exp, .tscroll), and
 *         pictures referenced as <dir>/<name>.png — published alongside the page as supporting files.
 *
 * A case is shown as passed only if the whole case passed on its run; a failed case is shown with its error,
 * never with expected results the app did not produce.
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..', '..')

/** Per section: where its cases are, their order, and the words above them (raw HTML in the page's classes). */
const SECTIONS = {
  eduidor: {
    dir: 'edu-idor', anchor: 'eduidor', number: 22, prefix: 'idor',
    order: ['E1', 'E2', 'E3', 'E4', 'E5', 'E6', 'E7'],
    title: "One school cannot touch another school's records",
    spec: 'cypress/e2e/docs/edu-idor-guide-screens.cy.js',
    notes: [
      ['red', '⚠ What was broken (EDU-IDOR-2, fixed 3 Oct)', `Saving a <strong>student, class or vehicle</strong> with another school's id <strong>took that record
        over</strong>: it was rewritten and moved into the caller's school. A new student could also be put in
        another school's class or vehicle — and the class sets the fee, so it was billed from the other school.
        The only check looked at the <em>branch</em>, never the organisation, and it lets an owner through.
        Proven on the running system before the fix (6 of 6 attacks succeeded); all refused after it.`],
      ['blue', 'Why most attack steps are console commands', `No screen can do this, and that is the point: a school's pickers only list its own classes, vehicles
        and campuses, and its edit form only opens its own rows. An attacker sends the request by hand. Each
        such step gives the exact command for <strong>F12 → Console</strong>, and the run executed that same
        command — so the command on this page is tested too. Ids are read off the grid's <strong>Id</strong>
        column, which the run checked matches the server's own id.`],
      ['amber', 'Two accounts', `<code>demo.education@myplus.com</code> is the school being attacked; <code>owner.education@myplus.com</code>
        is a different school. Use two browser profiles, or sign out between steps. Only records a case creates
        itself are attacked, and every case ends by deleting them.`],
    ],
  },
  sched2: {
    dir: 'sched2', anchor: 'sched2', number: 23, prefix: 'sched',
    order: ['P1', 'P2', 'P3', 'P4', 'D1'],
    title: "Parents' evenings, and where development email goes",
    spec: 'cypress/e2e/docs/sched2-guide-screens.cy.js',
    notes: [
      ['red', '⚠ What was broken (SCHED-2 / MAIL-DEV-2, fixed 4 Oct)', `<strong>The Teacher list on School → Parents' evenings was empty</strong>, so no school could publish an
        evening by hand — every automated check used the API and stayed green. <strong>Publishing could say ERROR for
        slots it had made</strong>: one database transaction per slot meant about one call in three ran past the
        3-second limit. And <strong>test emails went through the production Gmail account</strong> until Gmail locked
        it out.`],
      ['blue', 'What changed', `Publishing is now one read and one write. A call that still gets no answer is asked once more,
        and then the screen says the slots <em>may already be published</em> — never a bare error. On a development
        machine every email goes to Mailpit (<code>http://localhost:8025</code>); production is unchanged and needs a
        sender account of its own.`],
      ['amber', 'Evenings cannot be deleted', `Close booking is the only reversible step, so these cases use far-future times no real
        evening uses, and each ends by closing its evening.`],
    ],
  },
  pricemode: {
    dir: 'price-mode', anchor: 'pricemode', number: Number(process.env.TB_NUMBER || 27), prefix: 'prm',
    order: ['P1', 'P2', 'P3', 'P4', 'P5', 'P6', 'Q1', 'Q2', 'Q3', 'Q4', 'Q5', 'Q6', 'R1', 'X1', 'S1', 'S2', 'S3', 'S4', 'T1', 'T2', 'T3', 'T4', 'T5'],
    title: 'Selling price from purchases: Latest, Keep, markup, Per batch and Approval (PR-1 to PR-4)',
    spec: 'cypress/e2e/docs/price-mode-guide.cy.js',
    notes: [
      ['blue', 'What is new', `<strong>Settings → Configuration → Purchasing → How a purchase affects the selling price</strong>:
        <strong>Latest</strong> (default — the purchase's sell rate becomes the price of all stock), <strong>Keep</strong>
        (a purchase never changes the price) or <strong>Per batch</strong> (each purchase's stock sells at its own price).
        The <strong>markup rule</strong> suggests — or on Auto sets — a price from the cost, by business, category or product.
        In Per batch the till shows a <strong>Batch</strong> list, and a sale of 10 across a 200 and a 250 batch is charged
        <strong>7 × 200 + 3 × 250</strong>, shown in the cart before payment. With the markup rule on <strong>Approval</strong>
        a purchase never moves the price: it proposes one, the <strong>Purchase</strong> menu shows a red count, and an owner
        or admin approves or rejects it in <strong>Purchase → Price approvals</strong>. Approve is refused if the price
        moved since the list was opened (T3).`],
      ['red', '⚠ Found by the walks (all fixed 5 Oct)', `The price history's time was <strong>5 hours early</strong> (UTC sent without its zone).
        <strong>Use 240.45</strong> did nothing when clicked straight from the P/U box. Every <strong>Markup by category</strong>
        save was refused (403). One product on two sale lines <strong>recorded both lines' batches</strong> on each, and 5 + 5
        against 7 on the shelf was <strong>sold</strong>. In Per batch a <strong>typed price was re-priced to the batch's</strong>
        at Complete Sale (S2). Opening a form could <strong>pull the cursor out of the field being typed</strong> back to
        its first box a moment later ("10" landed in Invoice #, "2" in P/U); and the Price approvals count did not move
        until the page was reloaded (T1).`],
      ['amber', 'A void does not undo a price', `Voiding a bill reverses its stock and payment, not the price it set. Cases that save purchases or
        sales run on <code>owner.lifecycle@myplus.com</code> and void them on screen; a voided <em>sale</em> leaves the sale list
        entirely. Run the cases one at a time — a pricing setting one case changes is put back only at its end.`],
    ],
  },
  verifysweep: {
    dir: 'verify-sweep', anchor: 'verifysweep', number: Number(process.env.TB_NUMBER || 28), prefix: 'vs',
    order: ['V1', 'V2', 'V3', 'V4', 'V5', 'V6', 'V7'],
    title: 'Verify sweep: the “not yet verified” list, walked on screen',
    spec: 'cypress/e2e/docs/verify-sweep-guide.cy.js',
    notes: [
      ['blue', 'What this section is', `Section 13 listed areas that were built but never checked. Each case below was performed on the
        real screens of a freshly deployed stack, checked step by step and photographed. The automated gates for the same
        areas were run too — results and every fix are in <code>microservices/docs/slices/verify-sweep-2026-10.md</code>.`],
      ['red', '⚠ Found by the sweep (5 Oct)', `<strong>A session that ended showed “parsererror”</strong> on the next click — fixed (V7).
        <strong>Notification sending never started on a fresh install</strong>: its database was never created or granted — fixed.
        Six automated checks were red only because they assumed rows a long-lived database happens to have (a supplier, a sale this
        month, three invoices) — they now create what they need.`],
      ['amber', 'Money moves', `V1–V3 sell, buy and return on <code>owner.lifecycle@myplus.com</code>, the sacrificial business. V5–V6 run
        on <code>owner.mobile@myplus.com</code>, the shop the serial feature belongs to; V6 resets its setting on screen.`],
    ],
  },
  teamsignin: {
    dir: 'team-signin', anchor: 'teamsignin', number: Number(process.env.TB_NUMBER || 29), prefix: 'tsi',
    order: ['T1', 'T2', 'T3', 'T4', 'T5', 'L1'],
    title: 'A team member after you add them: invite, remove, reactivate, sign-in and lockout',
    spec: 'cypress/e2e/docs/team-signin-guide-screens.cy.js',
    notes: [
      ['red', '⚠ What was broken (TEAM-1 / LOCK-1, fixed 13–14 Oct)', `An owner could add a member but <strong>never see who had
        not set a password, resend the invitation, or remove anyone</strong>. A member removed by hand was told <strong>“Invalid
        Credentials”</strong> — the real reason was lost twice on the way (an HTTP client that could not read a 401 body, then
        string-matching on English text). And <strong>the account lock never engaged</strong>: five wrong passwords were saved and
        rolled back by the refusal itself, so a password could be guessed without limit.`],
      ['blue', 'What changed', `Team shows <strong>Invited / Active / Removed</strong>, with Resend, Remove (a reason is required)
        and Reactivate. Removing is reversible and deletes nothing. Sign-in refusals now travel as codes
        (<code>ACCESS_REMOVED</code>, <code>ACCOUNT_LOCKED</code>…) and the login page shows each in your language.`],
      ['amber', 'Use throwaway members only', `Every case adds its own member (<code>t1.&lt;run&gt;@myplus.test</code> —
        <code>.test</code> never delivers) on <code>owner.pharma@myplus.com</code> and removes it at the end. Never try L1 on a
        shared demo account: it locks that account for 30 minutes.`],
    ],
  },
  fixes1014: {
    dir: 'fixes-1014', anchor: 'fixes1014', number: Number(process.env.TB_NUMBER || 30), prefix: 'f14',
    order: ['R1', 'B1', 'N1'],
    title: 'Walked on 14 Oct: a return in tablets, a bill keyed twice, a school notice’s record',
    spec: 'cypress/e2e/docs/fixes-1014-guide-screens.cy.js',
    notes: [
      ['blue', 'What this section is', `Three fixes whose automated checks went green on 14 Oct (U13, DOC-INT, notifications Part A),
        walked on the real screens. N1’s record has no screen yet, so its reading step is the console command shown — the run executed
        that same command.`],
      ['amber', 'Shared settings are pinned', `R1 and B1 run on <code>demo.business@myplus.com</code>, whose tax and loose markup are
        changed by other tests and people. The run switches tax off and the markup to 0 for its own steps, and puts both back after.`],
      ['red', '⚠ Found while recording', `The <strong>confirm dialog could stay invisible</strong> when the window was not in front
        (its fade waited for an animation frame the browser pauses) — fixed 14 Oct. The <strong>System Alerts</strong> screen still
        reports through the browser’s own message box, in English only.`],
    ],
  },
  sra: {
    dir: 'sra', anchor: 'sra', number: Number(process.env.TB_NUMBER || 29), prefix: 'sra',
    order: ['A1', 'A2', 'A3', 'F2', 'B1', 'B3', 'B4', 'T1', 'B5', 'C1', 'C2', 'C3', 'D1', 'D2', 'D3', 'D4', 'D5'],
    title: 'Sales by rep and area: City / Area, sales reps, territory, the rep on every invoice, and the report by rep and area, its Excel and PDF downloads (SRA-1 – SRA-4c)',
    spec: 'cypress/e2e/docs/sra-guide-screens.cy.js',
    notes: [
      ['red', '⚠ What was broken (found while building SRA-1 / SRA-2, fixed 14 Oct)', `<strong>Another business could take
        over a customer or a supplier by id</strong> — an edit carrying a foreign id saved that record under the caller's
        business. <strong>Editing a customer wiped City, CNIC and the licence</strong> (or copied the previous customer's),
        <strong>moved a grouped branch off its company's credit limit</strong>, and <strong>unassigned an outlet from its
        rep</strong>. And <strong>the territory gate was red from 16 Sep</strong>: every territory decision asked the permission
        set's “Sees: All” <em>row</em> scope, so a booker whose set saw all rows was never narrowed and could assign outlets.
        <strong>An order invoiced at dispatch named the DISPATCHER as “Booked By”</strong> and credited no rep (fixed 15 Oct, SRA-3).`],
      ['blue', 'What is new', `<strong>City and Area</strong> come from the business's own list; <strong>+ New city / + New
        area</strong> open a small form over the customer or supplier form (the purchase form's “+ New product” pattern) and come
        back selected. A <strong>sales rep</strong> is a person on the business's list, with or without a login; a customer has a
        <strong>usual rep</strong>; a rep whose login is linked sees their own outlets plus every unassigned one. Managing reps
        is the “Add and change sales reps” permission — owners and admins have it.`],
      ['amber', 'Proven by the automated gates, no screen to walk', `Importing customers with an <code>area</code> column
        (“1 area created (1st Sector)”), another business's area, rep or outlet refused, the takeover by id refused, a branch kept
        on its company's credit account, an outlet kept with its rep after an edit, one login not two reps, a rep refused at the
        server when changing a usual rep or managing reps: <code>sra-areas.cy.js</code> 9/9, <code>sra-reps.cy.js</code> 6/6,
        <code>territory-assignment.cy.js</code> 8/8, <code>territory-screen.cy.js</code> 5/5 — 14 Oct. SRA-3 (15 Oct): a counter
        sale has no rep (COUNTER); a foreign rep id is refused, never credited to nobody; a rep cannot book in a colleague's name
        (their choice is dropped); correcting the rep on an edit needs “Add and change sales reps” and is written to the audit
        trail; a rep never learns which colleague covers a shop; an online order is PORTAL (unit test <code>SalesRepAttributionTest</code>, written, not yet run) — <code>sra-rep-on-sale.cy.js</code>
        6/6 and the 21-spec sale / keyboard / order regression 192/192. SRA-4a–4c (16 Oct): a member on “Sees: Own” sees only their own sales; nested groups add up level to level; the
        Excel file is a real workbook with the screen's filters (<code>sra-sales-analysis.cy.js</code> 13/13). Print invoices and
        Download PDF take every invoice across all pages, and a cached invoice is never stale — an edit and a payment show on the
        very next print (<code>sale-report-invoices.cy.js</code> 6/6).`],
      ['amber', 'Clean-up', `Every name carries the run's stamp. The run deactivates its reps, returns its shops to the shared
        pool and <strong>unlinks the booker's login</strong> from its rep — a linked booker narrows every booking test. The C-cases'
        sales and dispatched orders have their invoices voided.`],
    ],
  },
  hc1a: {
    dir: 'hc-1a', anchor: 'hc1a', number: Number(process.env.TB_NUMBER || 31), prefix: 'hc1a',
    order: ['C1', 'C2', 'C3', 'C4', 'C5'],
    title: 'Healthcare: a Hospital / Clinic tenant and a Pharmacy & Pharmaceutical tenant, and the Clinic home (HC-1a)',
    spec: 'cypress/e2e/docs/hc-1a-healthcare-guide-screens.cy.js',
    notes: [
      ['blue', 'What is new (15 Oct)', `A clinic and a pharmacy are now <strong>two separate businesses</strong>; the clinic inside a
        pharmacy is dropped. The operator's <strong>New tenant</strong> form asks <strong>Product line → Business type</strong>
        (Healthcare → Hospital / Clinic, or Pharmacy &amp; Pharmaceutical); the list comes from the platform's own rows, not the
        screen. A Hospital / Clinic signs in to its own <strong>Clinic home</strong> (Front desk · Doctor · Administration — never
        Pharmacy or Stock). The pharmacy's “Clinic reception” menu is removed.`],
      ['amber', 'Chosen once', `A Hospital / Clinic business type is set when the tenant is created and cannot be changed to a
        shop type later (or a shop to a clinic): the console shows it without a change control, and the server refuses it.`],
      ['amber', 'Proven by unit tests, no screen to walk', `The clinic always has its clinic module (it cannot be switched off);
        an unknown or not-yet-offered business type is refused; the clinic uses the business permission list:
        <code>BusinessTypeServiceTest</code>, <code>OptInCapabilityTest</code>, <code>PermissionModuleTest</code>,
        <code>WorkspaceOrgTypeTest</code>, <code>ModuleRouterTest</code> — 15 Oct.`],
      ['amber', 'Clean-up', `Each run creates its own two tenants (names carry the run stamp, owner e-mails end in .test, the
        set-password link is read from Mailpit) and suspends them on screen. Tenants are never deleted.`],
    ],
  },
  hc1b: {
    dir: 'hc-1b', anchor: 'hc1b', number: Number(process.env.TB_NUMBER || 33), prefix: 'hc1b',
    order: ['K1', 'K2', 'K3', 'K4', 'K5', 'K6'],
    title: 'A new clinic, set up and seeing its first patient: Clinic home, doctors, Team, Reception, My queue (HC-1b)',
    spec: 'cypress/e2e/docs/hc-1b-clinic-setup-guide-screens.cy.js',
    notes: [
      ['blue', 'What is new (15 Oct)', `The <strong>Clinic home</strong> opens on <strong>Today</strong> (tokens, waiting, doctors on duty)
        and, for the owner, <strong>Set up your clinic</strong>: add your doctors, add your staff &mdash; each ticks itself from what
        the clinic actually has, and the list goes once both are done. <strong>Team</strong> is now on the clinic's own page (until
        now it lived only on the shop and school dashboards, so a clinic owner could add nobody). The clinic's
        <strong>Administration</strong> tile opens Team.`],
      ['red', '⚠ Found while building', `Registering a patient on a Hospital / Clinic tenant had never been run (K4 is the first time).
        The school's Team table had no <em>Permission set</em> header while its rows had the column, and its permission matrix had no
        stylesheet &mdash; both fixed by sharing one Team block across the shop, the school and the clinic.`],
      ['amber', 'Not on this page', `The shop's Team screen after the sharing change: <code>team-invite-first-login</code> 10/10 and
        <code>team-members-manage</code> 5/5. The school's Team screen has no automated case &mdash; walk it once by hand. The clinic
        owner's permission matrix still lists shop areas (a clinic uses the business permission list).`],
      ['amber', 'Clean-up', `The run provisions its own clinic (name carries the run stamp, e-mails end in .test, set-password links
        are read from Mailpit) and suspends it on screen at the end. Tenants are never deleted.`],
    ],
  },
  hc1c: {
    dir: 'hc-1c', anchor: 'hc1c', number: Number(process.env.TB_NUMBER || 34), prefix: 'hc1c',
    order: ['L1', 'L2', 'L3', 'L4', 'L5', 'L6'],
    title: 'A clinic links to its pharmacy: opt in, find, request, accept, one at a time, end (HC-1c)',
    spec: 'cypress/e2e/docs/hc-1c-link-guide-screens.cy.js',
    notes: [
      ['blue', 'What is new (15 Oct)', `A Hospital / Clinic and a Pharmacy &amp; Pharmaceutical are separate businesses; this <strong>link</strong>
        is the only thing that joins them. The pharmacy turns on <strong>Clinics may send us prescriptions</strong> (Pharmacy &rarr;
        Linked clinics); the clinic finds it by name (<strong>Linked pharmacy</strong>), sends a request, the pharmacy accepts; either side
        ends it with a reason. One pharmacy per clinic, today. Prescriptions across the link come next (HC-1d) &mdash; patient name, phone
        and MRN, the medicines and the prescriber; <strong>never the diagnosis</strong>.`],
      ['amber', 'Refusals &mdash; no screen can try these, so check them in the browser console', `Signed in as the account named,
        <strong>F12 &rarr; Console</strong>, paste, press Enter. Each must print <code>false</code> and a reason code (gate
        <code>healthcare/hc-1c-link-api.cy.js</code> 6/6, 15 Oct):
        <br>&bull; <strong>A shop cannot search</strong> &mdash; as <code>owner.business@myplus.com</code>:
        <code>$.get(serverContext+'links/pharmacies?q=ph',r=&gt;console.log(r.success,r.reason))</code> &rarr; <code>false LINK_NOT_CLINIC</code>
        <br>&bull; <strong>A pharmacy that has not opted in is not found</strong> &mdash; as the clinic owner, search its name on Linked pharmacy: not listed.
        <br>&bull; <strong>Another pharmacy cannot accept</strong> &mdash; as a DIFFERENT pharmacy's owner, with the link's number:
        <code>$.post(serverContext+'links/&lt;id&gt;/accept',r=&gt;console.log(r.success,r.reason))</code> &rarr; <code>false LINK_NOT_FOUND</code>
        (the clinic itself gets the same answer).
        <br>&bull; <strong>A second request is refused</strong> &mdash; as the clinic owner while one is waiting or linked:
        <code>$.post(serverContext+'links',{pharmacyOrgId:&lt;id&gt;},r=&gt;console.log(r.success,r.reason))</code> &rarr; <code>false LINK_ALREADY</code>
        <br>&bull; <strong>Ending needs a reason</strong>:
        <code>$.post(serverContext+'links/&lt;id&gt;/end',{reason:' '},r=&gt;console.log(r.success,r.reason))</code> &rarr; <code>false LINK_REASON_REQUIRED</code>
        <br>Read the link number with <code>$.get(serverContext+'links',r=&gt;console.table(r.data))</code>.`],
      ['amber', 'What each side sees of the other', `Only the other business's <strong>name</strong> and the link's status and dates.
        The clinic never sees the pharmacy's stock, prices or customers; the pharmacy never sees the clinic's patients. Every step is in
        both businesses' audit trails.`],
      ['amber', 'Clean-up', `The run provisions its own clinic and two pharmacies (names carry the run stamp, e-mails end in .test,
        set-password links come from Mailpit). L6 ends the link on screen; the three tenants are suspended after the run. A link is never
        deleted &mdash; it stays as Ended.`],
    ],
  },
}

const which = process.argv[2]
const cfg = SECTIONS[which]
if (!cfg) throw new Error(`usage: build-testbook-section.js <${Object.keys(SECTIONS).join('|')}> <out.html>`)
const IN = path.join(ROOT, 'cypress', 'guide-out', cfg.dir)
const out = process.argv[3] || path.join(ROOT, 'cypress', 'guide-out', `${cfg.dir}-section.html`)

const esc = (s) => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
/** The specs write **bold**, *italic* and `code`; nothing else. */
const md = (s) => esc(s)
  .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  .replace(/\*(.+?)\*/g, '<em>$1</em>')
  .replace(/`([^`]+)`/g, '<code>$1</code>')

const missing = cfg.order.filter((id) => !fs.existsSync(path.join(IN, `${id}.json`)))
if (missing.length) throw new Error(`missing captured cases in ${IN}: ${missing.join(', ')} — run ${cfg.spec} first`)
const cases = cfg.order.map((id) => JSON.parse(fs.readFileSync(path.join(IN, `${id}.json`), 'utf8')))

const passed = cases.filter((c) => c.passed).length
const when = cases.map((c) => c.capturedAt).filter(Boolean).sort().pop() || ''
const day = when ? new Date(when).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) : ''

const step = (a, n, prefix) => {
  const where = a.via === 'console' ? ' <span class="tag blue">console</span>'
    : a.via === 'run' ? ' <span class="tag amber">recorded by the run</span>' : ''
  const exp = a.expect.map((e) => `<li>${md(e)}</li>`).join('')
  const code = a.code ? `<div class="exp">${esc(a.code)}</div>` : ''
  const pics = a.shots.map((s) =>
    `<figure class="shot"><img loading="lazy" src="${cfg.dir}/${esc(s)}.png" alt="${esc(prefix)}${n}: ${esc(a.do.replace(/\*|`/g, '').slice(0, 90))}"><figcaption>${esc(prefix)}${n}</figcaption></figure>`).join('')
  return `<li class="step"><div><strong>${prefix}${n}.</strong> ${md(a.do)}${where}</div>${code}
          <div class="expect"><span class="hd">Expected</span><ul>${exp}</ul></div>${pics}</li>`
}

const caseHtml = (c) => {
  const tag = c.passed ? '<span class="tag green">passed on this build</span>' : '<span class="tag red">failed on this build</span>'
  const fail = c.passed ? '' : `<div class="note red"><span class="hd">This case failed on the last run</span><p>${esc(c.error || '')}</p></div>`
  const li = (xs) => (xs || []).map((x) => `<li>${md(x)}</li>`).join('')
  return `
    <h3 id="${cfg.prefix}-${esc(c.id.toLowerCase())}">${esc(c.id)} · ${md(c.title)} ${tag}</h3>
    ${fail}
    <div class="tscroll"><table><tbody>
      <tr><th>Why it matters</th><td>${md(c.purpose)}</td></tr>
      <tr><th>Accounts</th><td>${md(c.tenant)}<br><span class="muted">${md(c.role)}</span></td></tr>
      <tr><th>Before you start</th><td><ul class="plain">${li(c.prereq)}</ul></td></tr>
      <tr><th>Test data</th><td><ul class="plain">${li(c.data)}</ul></td></tr>
      <tr><th>What changes</th><td>${md(c.rollback)}</td></tr>
    </tbody></table></div>
    <ol class="steps">${c.actions.map((a, i) => step(a, i + 1, '')).join('')}</ol>
    ${c.cleanup.length ? `<p class="cleanup-hd"><strong>Cleanup</strong></p><ol class="steps">${c.cleanup.map((a, i) => step(a, i + 1, 'C')).join('')}</ol>` : ''}`
}

const S = `#${cfg.anchor}`
const notes = cfg.notes.map(([tone, hd, body]) => `
    <div class="note ${tone}">
      <span class="hd">${hd}</span>
      <p>${body}</p>
    </div>`).join('')

const html = `
  <!-- ─────────────── ${cfg.number} · built by docs/guides/build-testbook-section.js ${which} ─────────────── -->
  <section class="mod" id="${cfg.anchor}">
    <style>
      ${S} ol.steps{margin:6px 0 4px; padding-left:0; list-style:none; display:flex; flex-direction:column; gap:14px}
      ${S} li.step{border:1px solid var(--line); border-radius:6px; background:var(--card); padding:12px 14px;
        display:flex; flex-direction:column; gap:8px; font-size:15.5px; line-height:1.5}
      ${S} .expect{background:var(--good-wash); border-radius:4px; padding:8px 12px}
      ${S} .expect .hd{font-family:Archivo,sans-serif; font-weight:600; font-size:11px; letter-spacing:.06em;
        text-transform:uppercase; color:var(--good)}
      ${S} .expect ul, ${S} ul.plain{margin:4px 0 0; padding-left:18px}
      ${S} .exp{margin:0; white-space:pre-wrap; word-break:break-word}
      ${S} figure.shot{margin:0; border:1px solid var(--line-2); border-radius:4px; overflow:hidden; max-width:100%}
      ${S} figure.shot img{display:block; width:100%; height:auto}
      ${S} figure.shot figcaption{font-family:"JetBrains Mono",monospace; font-size:11px; color:var(--ink-3);
        padding:4px 8px; border-top:1px solid var(--line)}
      ${S} .muted{color:var(--ink-3); font-size:14px}
      ${S} .cleanup-hd{margin:10px 0 0}
      ${S} th{width:150px; white-space:nowrap}
      @media (max-width:600px){ ${S} th{width:auto; white-space:normal} }
    </style>
    <h2>${cfg.number} · ${esc(cfg.title)} <span class="tag amber">gated</span></h2>
    <p>Every step below was <strong>performed, checked and photographed</strong> by the run on ${esc(day)}
      (<code>${cfg.spec}</code>) — <strong>${passed} of ${cases.length}</strong>
      cases passed. A case is marked passed only if every one of its steps produced exactly what it says here.</p>${notes}
    ${cases.map(caseHtml).join('\n')}
  </section>
`
fs.writeFileSync(out, html)
console.log(`wrote ${out}: ${which} — ${cases.length} cases, ${passed} passed, ${cases.reduce((n, c) => n + c.shots.length, 0)} pictures`)
