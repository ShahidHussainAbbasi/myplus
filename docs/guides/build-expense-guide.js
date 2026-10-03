#!/usr/bin/env node
/**
 * Builds the Expense Management & Supplier Payables Test Book from what the recording run actually did.
 *
 *   docs/guides/expense-guide-template.html      the page (status, logins, every hand-written case, gates, decisions)
 *   cypress/guide-out/expense-guide/<case>.json  one recorded case: set-up, actions + expected results, clean-up, shots
 *   cypress/screenshots/expense-guide-screens.cy.js/*.png   the pictures that run took
 *
 * A case whose recording PASSED replaces its hand-written version: numbered steps, each with what you should see and
 * the screenshot the run took at that moment, then the clean-up. A case not recorded (or not passed) keeps its
 * hand-written text. Nothing on a recorded case is typed from memory.
 *
 *   node docs/guides/build-expense-guide.js   →  cypress/guide-out/expense-guide-page/{index.html, img/*.png}
 */
const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const TEMPLATE = path.join(__dirname, 'expense-guide-template.html')
const CASES = path.join(ROOT, 'cypress', 'guide-out', 'expense-guide')
const SHOTS = path.join(ROOT, 'cypress', 'screenshots', 'expense-guide-screens.cy.js')
const OUT = path.join(ROOT, 'cypress', 'guide-out', 'expense-guide-page')

const sh = (c) => { try { return execSync(c, { cwd: ROOT }).toString().trim() } catch (e) { return '' } }
const esc = (s) => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const commit = sh('git rev-parse --short HEAD')

fs.rmSync(OUT, { recursive: true, force: true })
fs.mkdirSync(path.join(OUT, 'img'), { recursive: true })

const recorded = fs.existsSync(CASES)
  ? fs.readdirSync(CASES).filter((f) => f.endsWith('.json')).map((f) => JSON.parse(fs.readFileSync(path.join(CASES, f), 'utf8')))
  : []
const missing = []
const img = (name) => {
  const src = path.join(SHOTS, name + '.png')
  if (!fs.existsSync(src)) { missing.push(name); return null }
  fs.copyFileSync(src, path.join(OUT, 'img', name + '.png'))
  return 'img/' + name + '.png'
}

const VIA = { screen: '', run: '<span class="via" title="No screen for this step: the run sent the same request the screen sends">form request</span>' }

const step = (a, n) => {
  const shots = a.shots.map(img).filter(Boolean)
    .map((src) => `<button type="button" class="shot" data-full="${src}" aria-label="Enlarge screenshot"><img src="${src}" loading="lazy" alt=""></button>`).join('')
  const exp = a.expect.length
    ? `<ul class="expect">${a.expect.map((e) => `<li><label><input type="checkbox"><span>${e}</span></label></li>`).join('')}</ul>`
    : ''
  return `<li class="step"><div class="step-do"><span class="sn">${n}</span><div>${a.do} ${VIA[a.via] || ''}</div></div>${exp}${shots ? `<div class="shots">${shots}</div>` : ''}</li>`
}

const caseHtml = (c) => {
  const when = new Date(c.capturedAt).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })
  const who = (c.who || []).map((w) => `<code>${esc(w)}</code>`).join('')
  const setup = c.setup.length ? `<div class="setup"><h4>Before you start</h4><ul>${c.setup.map((s) => `<li>${s}</li>`).join('')}</ul></div>` : ''
  const cleanup = c.cleanup.length
    ? `<div class="cleanup"><h4>Clean up</h4><ol>${c.cleanup.map((a, i) => step(a, i + 1)).join('')}</ol></div>` : ''
  return `      <div class="case recorded" data-case="${esc(c.id)}">
        <div class="case-head"><span class="cid">${esc(c.id)}</span><h3>${esc(c.title)}</h3><span class="pill pill-ok" title="Every step below was performed, checked and photographed by the recording run">Recorded ${esc(when)}</span></div>
        <div class="case-body">
          <div class="who">${who}</div>
          ${setup}
          <div><h4 class="steps-h">Steps — do each, then check what you should see</h4><ol class="steps">${c.actions.map((a, i) => step(a, i + 1)).join('')}</ol></div>
          ${cleanup}
        </div>
      </div>
`
}

let html = fs.readFileSync(TEMPLATE, 'utf8')
let replaced = 0
const passed = recorded.filter((c) => c.passed)
for (const c of passed) {
  // a hand-written case runs from its opening <div class="case" data-case="ID"> to the line before the next case or </section>
  const re = new RegExp(`      <div class="case" data-case="${c.id.replace(/[-]/g, '\\-')}">[\\s\\S]*?\\r?\\n      </div>\\r?\\n(?=\\s*(?:<div class="case"|</section>))`)
  if (re.test(html)) { html = html.replace(re, caseHtml(c)); replaced++ }
}

const css = `
<style>
.case.recorded{border-color:var(--ok)}
.setup,.cleanup{background:var(--bg2);border-radius:8px;padding:10px 14px}
.setup h4,.cleanup h4,.steps-h{margin:0 0 6px;font-size:11.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--txt3)}
.setup ul{margin:0;padding-left:20px}
ol.steps,.cleanup ol{list-style:none;margin:0;padding:0;display:grid;gap:14px}
.step{display:grid;gap:8px;min-width:0}
.step-do{display:flex;gap:10px;align-items:flex-start}
.sn{flex:none;width:24px;height:24px;border-radius:50%;background:var(--brand);color:var(--on-head);font:700 12px/24px var(--f-body);text-align:center}
.via{display:inline-block;font-size:11px;font-weight:700;border-radius:999px;padding:1px 8px;background:var(--warn-soft);color:var(--warn);margin-left:4px}
.step ul.expect{margin-left:34px}
.shots{display:flex;flex-wrap:wrap;gap:10px;margin-left:34px}
.shot{border:1px solid var(--border2);border-radius:8px;padding:0;background:var(--surface);cursor:zoom-in;max-width:100%;overflow:hidden}
.shot img{display:block;width:460px;max-width:100%;height:auto}
dialog.lb{border:0;padding:0;background:transparent;max-width:96vw;max-height:94vh}
dialog.lb::backdrop{background:rgba(5,12,24,.78)}
dialog.lb img{max-width:96vw;max-height:88vh;display:block;border-radius:8px}
dialog.lb button{position:absolute;top:8px;right:8px;font:700 14px var(--f-body);border:0;border-radius:6px;padding:6px 10px;background:var(--surface);color:var(--txt);cursor:pointer}
</style>`
const lightbox = `
<dialog class="lb" id="lightbox"><button type="button" id="lbClose">Close</button><img id="lbImg" alt=""></dialog>
<script>
(function(){
  var d=document.getElementById('lightbox'), im=document.getElementById('lbImg');
  document.querySelectorAll('.shot').forEach(function(b){ b.addEventListener('click',function(){ im.src=b.getAttribute('data-full'); if(d.showModal) d.showModal(); }); });
  document.getElementById('lbClose').addEventListener('click',function(){ d.close(); });
  d.addEventListener('click',function(e){ if(e.target===d) d.close(); });
})();
</script>`
html = html.replace(/<\/style>(\r?\n)+<header/, () => '</style>' + css + '\n\n<header')
const banner = `<div class="info" style="margin-top:14px"><b>${replaced} of ${html.match(/data-case="/g).length} cases are recorded</b> (green border): every step was performed, checked and photographed by <code>cypress/e2e/docs/expense-guide-screens.cy.js</code> on build <code>${esc(commit)}</code>. Steps marked <span class="via">form request</span> have no screen of their own; the run sent the same request the screen sends. The other cases are hand-written and are being recorded next.</div>`
html = html.replace('<h3>Leave things as you found them</h3>', banner + '\n      <h3>Leave things as you found them</h3>')
html = html.replace('</main>', '</main>' + lightbox)
fs.writeFileSync(path.join(OUT, 'index.html'), html)
console.log(JSON.stringify({ out: OUT, recorded: passed.length, replaced, images: fs.readdirSync(path.join(OUT, 'img')).length, missing }))
