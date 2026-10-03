/**
 * Builds the Test Book section "One school cannot touch another school's records" (EDU-IDOR-2) from the cases
 * captured by cypress/e2e/docs/edu-idor-guide-screens.cy.js.
 *
 *   node docs/guides/build-edu-idor-section.js <out.html>
 *
 * Input:  cypress/guide-out/edu-idor/E*.json   one file per case (actions, expected results, cleanup, pictures)
 *         cypress/screenshots/edu-idor-guide-screens.cy.js/*.png
 * Output: the <section> HTML, using only the Test Book's own classes (.ck, .note, .tag, .exp, .tscroll), and
 *         pictures referenced as edu-idor/<name>.png — published alongside the page as supporting files.
 *
 * A case is shown as passed only if the whole case passed on this run; a failed case is shown with its error,
 * never with expected results the app did not produce.
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..', '..')
const IN = path.join(ROOT, 'cypress', 'guide-out', 'edu-idor')
const out = process.argv[2] || path.join(ROOT, 'cypress', 'guide-out', 'edu-idor-section.html')

const esc = (s) => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
/** The spec writes **bold**, *italic* and `code`; nothing else. */
const md = (s) => esc(s)
  .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  .replace(/\*(.+?)\*/g, '<em>$1</em>')
  .replace(/`([^`]+)`/g, '<code>$1</code>')

const cases = fs.readdirSync(IN).filter((f) => /^E\d+\.json$/.test(f))
  .sort((a, b) => parseInt(a.slice(1), 10) - parseInt(b.slice(1), 10))
  .map((f) => JSON.parse(fs.readFileSync(path.join(IN, f), 'utf8')))
if (!cases.length) throw new Error(`no cases in ${IN} — run the spec first`)

const passed = cases.filter((c) => c.passed).length
const when = cases.map((c) => c.capturedAt).filter(Boolean).sort().pop() || ''
const day = when ? new Date(when).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) : ''

const step = (a, n, prefix) => {
  const where = a.via === 'console' ? ' <span class="tag blue">console</span>'
    : a.via === 'run' ? ' <span class="tag amber">recorded by the run</span>' : ''
  const exp = a.expect.map((e) => `<li>${md(e)}</li>`).join('')
  const code = a.code ? `<div class="exp">${esc(a.code)}</div>` : ''
  const pics = a.shots.map((s) =>
    `<figure class="shot"><img loading="lazy" src="edu-idor/${esc(s)}.png" alt="${esc(prefix)}${n}: ${esc(a.do.replace(/\*|`/g, '').slice(0, 90))}"><figcaption>${esc(prefix)}${n}</figcaption></figure>`).join('')
  return `<li class="step"><div><strong>${prefix}${n}.</strong> ${md(a.do)}${where}</div>${code}
          <div class="expect"><span class="hd">Expected</span><ul>${exp}</ul></div>${pics}</li>`
}

const caseHtml = (c) => {
  const tag = c.passed ? '<span class="tag green">passed on this build</span>' : '<span class="tag red">failed on this build</span>'
  const fail = c.passed ? '' : `<div class="note red"><span class="hd">This case failed on the last run</span><p>${esc(c.error || '')}</p></div>`
  const li = (xs) => (xs || []).map((x) => `<li>${md(x)}</li>`).join('')
  return `
    <h3 id="idor-${esc(c.id.toLowerCase())}">${esc(c.id)} · ${md(c.title)} ${tag}</h3>
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

const html = `
  <!-- ─────────────── 22 · EDU-IDOR-2 — built by docs/guides/build-edu-idor-section.js ─────────────── -->
  <section class="mod" id="eduidor">
    <style>
      #eduidor ol.steps{margin:6px 0 4px; padding-left:0; list-style:none; display:flex; flex-direction:column; gap:14px}
      #eduidor li.step{border:1px solid var(--line); border-radius:6px; background:var(--card); padding:12px 14px;
        display:flex; flex-direction:column; gap:8px; font-size:15.5px; line-height:1.5}
      #eduidor .expect{background:var(--good-wash); border-radius:4px; padding:8px 12px}
      #eduidor .expect .hd{font-family:Archivo,sans-serif; font-weight:600; font-size:11px; letter-spacing:.06em;
        text-transform:uppercase; color:var(--good)}
      #eduidor .expect ul, #eduidor ul.plain{margin:4px 0 0; padding-left:18px}
      #eduidor .exp{margin:0; white-space:pre-wrap; word-break:break-word}
      #eduidor figure.shot{margin:0; border:1px solid var(--line-2); border-radius:4px; overflow:hidden; max-width:100%}
      #eduidor figure.shot img{display:block; width:100%; height:auto}
      #eduidor figure.shot figcaption{font-family:"JetBrains Mono",monospace; font-size:11px; color:var(--ink-3);
        padding:4px 8px; border-top:1px solid var(--line)}
      #eduidor .muted{color:var(--ink-3); font-size:14px}
      #eduidor .cleanup-hd{margin:10px 0 0}
      #eduidor th{width:150px; white-space:nowrap}
      @media (max-width:600px){ #eduidor th{width:auto; white-space:normal} }
    </style>
    <h2>22 · One school cannot touch another school's records <span class="tag amber">gated</span></h2>
    <p>Every step below was <strong>performed, checked and photographed</strong> by the run on ${esc(day)}
      (<code>cypress/e2e/docs/edu-idor-guide-screens.cy.js</code>) — <strong>${passed} of ${cases.length}</strong>
      cases passed. A case is marked passed only if every one of its steps produced exactly what it says here.</p>
    <div class="note red">
      <span class="hd">⚠ What was broken (EDU-IDOR-2, fixed 3 Oct)</span>
      <p>Saving a <strong>student, class or vehicle</strong> with another school's id <strong>took that record
        over</strong>: it was rewritten and moved into the caller's school. A new student could also be put in
        another school's class or vehicle — and the class sets the fee, so it was billed from the other school.
        The only check looked at the <em>branch</em>, never the organisation, and it lets an owner through.
        Proven on the running system before the fix (6 of 6 attacks succeeded); all refused after it.</p>
    </div>
    <div class="note blue">
      <span class="hd">Why most attack steps are console commands</span>
      <p>No screen can do this, and that is the point: a school's pickers only list its own classes, vehicles
        and campuses, and its edit form only opens its own rows. An attacker sends the request by hand. Each
        such step gives the exact command for <strong>F12 → Console</strong>, and the run executed that same
        command — so the command on this page is tested too. Ids are read off the grid's <strong>Id</strong>
        column, which the run checked matches the server's own id.</p>
    </div>
    <div class="note amber">
      <span class="hd">Two accounts</span>
      <p><code>demo.education@myplus.com</code> is the school being attacked; <code>owner.education@myplus.com</code>
        is a different school. Use two browser profiles, or sign out between steps. Only records a case creates
        itself are attacked, and every case ends by deleting them.</p>
    </div>
    ${cases.map(caseHtml).join('\n')}
  </section>
`
fs.writeFileSync(out, html)
console.log(`wrote ${out}: ${cases.length} cases, ${passed} passed, ${cases.reduce((n, c) => n + c.shots.length, 0)} pictures`)
