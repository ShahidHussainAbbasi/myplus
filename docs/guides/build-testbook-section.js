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
    dir: 'price-mode', anchor: 'pricemode', number: Number(process.env.TB_NUMBER || 24), prefix: 'prm',
    order: ['P1', 'P2', 'P3', 'P4', 'P5'],
    title: 'What a purchase does to the selling price (PR-1)',
    spec: 'cypress/e2e/docs/price-mode-guide.cy.js',
    notes: [
      ['blue', 'What is new', `<strong>Settings → Configuration → Purchasing → How a purchase affects the selling price</strong>:
        <strong>Latest</strong> (default, as before — the purchase's sell rate becomes the price of all stock) or <strong>Keep</strong>
        (a purchase never changes the price; the cost is still recorded). The purchase form now says what saving will do,
        and the product form has a <strong>Price history</strong> link (owner and admin only — it shows the cost).`],
      ['red', '⚠ Found by the walk (fixed 5 Oct)', `The history's time was <strong>5 hours early</strong> — a 13:00 change showed 08:00. The servers keep
        UTC and the time went out without its zone. It is now shown in your own time, and the gate checks it.`],
      ['amber', 'A void does not undo a price', `Voiding a bill reverses its stock and payment, not the price it set: the price was a decision and its
        history keeps it. Cases P2–P3 save purchases, so they run on <code>owner.lifecycle@myplus.com</code> and void their bills.`],
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
