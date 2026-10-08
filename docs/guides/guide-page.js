/**
 * Shared page builder for the step-by-step manual test guides captured by Cypress (dual-role, price mode, …).
 *
 *   build({ caseDir, shotPrefix, outDir, title, heading, design, slices, beforeYouStart, storageKey, coverMap })
 *
 *   inputs   <caseDir>/*.json                       one file per case, written by the guide spec
 *            <caseDir>/img/*.png, cypress/screenshots/**   the pictures (the run's own copies win)
 *   output   <outDir>/index.html + <outDir>/img/*.png
 *
 * Nothing on the page is typed from memory: a case is marked Verified only if every action and expected result passed
 * in the run on this build, and its screenshots are shown only then. A failing case says so, with the run's message.
 *
 * `beforeYouStart` items and `slices` labels are trusted HTML/text from the builder script, never from the run.
 */
const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')

const sh = (c) => { try { return execSync(c, { cwd: ROOT, encoding: 'utf8' }).trim() } catch (e) { return '' } }
const esc = (s) => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const md = (s) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/\*(.+?)\*/g, '<em>$1</em>')
const fmtDate = (d) => new Date(d).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })

const VIA = {
  console: '<span class="pill pill-warn" title="There is no button for this on the screen yet">No screen yet — browser console</span>',
  run: '<span class="pill pill-info">Through the screen’s own request</span>',
}

const STYLE = `<style>
:root{--paper:#f5f7fb;--card:#fff;--ink:#14213d;--ink2:#4a5672;--ink3:#6b7790;--line:#dfe5ef;--brand:#1565C0;--brand-wash:#e8f1fb;
  --ok:#0f7a4f;--ok-wash:#e5f4ec;--warn:#8a5200;--warn-wash:#fdf2df;--bad:#b42318;--bad-wash:#fdecea;--code:#f0f3f8}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--paper:#0e1420;--card:#151d2b;--ink:#e6ecf6;--ink2:#a3b1c6;--ink3:#8291a8;--line:#273246;
  --brand:#7fb0ec;--brand-wash:#18263b;--ok:#6fcf9d;--ok-wash:#13291f;--warn:#e6ad62;--warn-wash:#2c2214;--bad:#f08a80;--bad-wash:#301a18;--code:#101825}}
:root[data-theme="dark"]{--paper:#0e1420;--card:#151d2b;--ink:#e6ecf6;--ink2:#a3b1c6;--ink3:#8291a8;--line:#273246;
  --brand:#7fb0ec;--brand-wash:#18263b;--ok:#6fcf9d;--ok-wash:#13291f;--warn:#e6ad62;--warn-wash:#2c2214;--bad:#f08a80;--bad-wash:#301a18;--code:#101825}
*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:15px/1.55 "Segoe UI",system-ui,-apple-system,sans-serif}
.wrap{max-width:1040px;margin:0 auto;padding:28px 16px 80px;display:flex;flex-direction:column;gap:22px}
header.top{background:linear-gradient(135deg,#1565C0,#0d47a1);color:#fff;border-radius:14px;padding:22px 24px}
header.top h1{margin:0 0 6px;font-size:clamp(22px,4vw,30px)}header.top p{margin:0;opacity:.92;max-width:70ch}
.meta-line{display:flex;flex-wrap:wrap;gap:8px 16px;margin-top:12px;font-size:13px;opacity:.95}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 18px}
h2{font-size:19px;margin:0 0 8px}h3{font-size:16.5px;margin:0}h4{font-size:13px;text-transform:uppercase;letter-spacing:.05em;color:var(--ink2);margin:16px 0 6px}
.muted{color:var(--ink3)}code{background:var(--code);padding:1px 5px;border-radius:4px;font-size:.88em}
.pill{display:inline-block;font-size:11.5px;font-weight:600;padding:2px 9px;border-radius:999px;white-space:nowrap}
.pill-ok{background:var(--ok-wash);color:var(--ok)}.pill-bad{background:var(--bad-wash);color:var(--bad)}.pill-warn{background:var(--warn-wash);color:var(--warn)}.pill-info{background:var(--brand-wash);color:var(--brand)}
.tag{display:inline-block;font:600 11px/1.6 ui-monospace,Consolas,monospace;background:var(--brand-wash);color:var(--brand);padding:0 7px;border-radius:5px}
.filters{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.filters button{border:1px solid var(--line);background:var(--card);color:var(--ink);border-radius:999px;padding:5px 12px;font:inherit;font-size:13px;cursor:pointer}
.filters button[aria-pressed="true"]{background:var(--brand);border-color:var(--brand);color:#fff}
.progress{margin-left:auto;font-size:13px;color:var(--ink2)}
.case{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 18px}
.case-head{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.case-id{font:700 13px ui-monospace,Consolas,monospace;background:var(--brand);color:#fff;border-radius:6px;padding:2px 8px}
.case-head h3{flex:1;min-width:220px}.covers{margin:8px 0 0;font-size:13px}
.case-fail{margin-top:10px;background:var(--bad-wash);color:var(--bad);border-radius:8px;padding:8px 10px;font-size:13.5px}
dl.meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:10px 18px;margin:12px 0 0}
dl.meta dt{font-size:11.5px;text-transform:uppercase;letter-spacing:.05em;color:var(--ink3)}dl.meta dd{margin:2px 0 0}dl.meta .wide{grid-column:1/-1}
dl.meta ul{margin:0;padding-left:18px}
ol.acts{margin:0;padding-left:22px;display:flex;flex-direction:column;gap:12px}
li.act{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,300px);gap:14px;align-items:start}
@media (max-width:760px){li.act{grid-template-columns:1fr}}
.act-do{margin:0 0 4px}ul.expect{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:4px}
ul.expect label{display:flex;gap:8px;align-items:flex-start;cursor:pointer;font-size:14px}
pre.cmd{background:var(--code);border:1px solid var(--line);border-radius:8px;padding:8px 10px;overflow-x:auto;font-size:12px;margin:4px 0 6px}
.shots{display:flex;flex-direction:column;gap:6px}.shot{border:1px solid var(--line);border-radius:8px;padding:0;background:none;cursor:zoom-in;overflow:hidden}
.shot img{display:block;width:100%;height:auto}
.signoff{display:flex;gap:8px;align-items:center;margin-top:12px;padding:10px 12px;border:1px dashed var(--brand);border-radius:8px;font-weight:600}
table.map{width:100%;border-collapse:collapse;font-size:13.5px}table.map td,table.map th{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left}
#lightbox{position:fixed;inset:0;background:rgba(0,0,0,.82);display:none;align-items:center;justify-content:center;padding:16px;z-index:9}
#lightbox img{max-width:100%;max-height:100%;border-radius:8px}#lightbox.open{display:flex}
:focus-visible{outline:2px solid var(--brand);outline-offset:2px}
</style>`

const script = (storageKey) => `<script>
(function(){
  var KEY='${storageKey}', st={};
  try{ st=JSON.parse(localStorage.getItem(KEY)||'{}') }catch(e){}
  var boxes=[].slice.call(document.querySelectorAll('[data-chk]'));
  function save(){ try{ localStorage.setItem(KEY, JSON.stringify(st)) }catch(e){} }
  function progress(){ var n=boxes.filter(function(b){return b.checked}).length; var so=document.querySelectorAll('[id^="so-"]:checked').length;
    document.getElementById('progress').textContent=n+' of '+boxes.length+' ticked · '+so+' cases signed off'; }
  boxes.forEach(function(b){ b.checked=!!st[b.id]; b.addEventListener('change',function(){ st[b.id]=b.checked; save(); progress(); }); });
  progress();
  [].forEach.call(document.querySelectorAll('.filters button'),function(btn){ btn.addEventListener('click',function(){
    [].forEach.call(document.querySelectorAll('.filters button'),function(x){x.setAttribute('aria-pressed', x===btn?'true':'false')});
    var f=btn.getAttribute('data-f');
    [].forEach.call(document.querySelectorAll('.case'),function(c){ c.hidden = !(f==='all' || c.getAttribute('data-slice')===f); }); }); });
  var lb=document.getElementById('lightbox'), li=lb.querySelector('img');
  [].forEach.call(document.querySelectorAll('.shot'),function(s){ s.addEventListener('click',function(){ li.src=s.getAttribute('data-full'); lb.classList.add('open'); }); });
  lb.addEventListener('click',function(){ lb.classList.remove('open') });
  document.addEventListener('keydown',function(e){ if(e.key==='Escape') lb.classList.remove('open') });
})();
</script>`

function build(cfg) {
  const outDir = path.resolve(cfg.outDir)
  const CASE_DIR = path.resolve(ROOT, cfg.caseDir)
  const SLICES = cfg.slices || {}
  const prefixes = [].concat(cfg.shotPrefix)
  const isShot = (f) => prefixes.some((x) => f.startsWith(x))
  const version = { branch: sh('git rev-parse --abbrev-ref HEAD'), commit: sh('git rev-parse --short HEAD') }

  const cases = fs.existsSync(CASE_DIR)
    ? fs.readdirSync(CASE_DIR).filter((f) => f.endsWith('.json')).map((f) => JSON.parse(fs.readFileSync(path.join(CASE_DIR, f), 'utf8')))
        .sort((a, b) => a.id.localeCompare(b.id, 'en', { numeric: true }))   // P2 before P10, all P before Q
    : []

  // ── screenshots ───────────────────────────────────────────────────────────────────────────────────────────
  const shotIndex = {}
  ;(function walk(dir) {
    if (!fs.existsSync(dir)) return
    for (const f of fs.readdirSync(dir)) {
      const p = path.join(dir, f)
      if (fs.statSync(p).isDirectory()) walk(p)
      else if (isShot(f) && f.endsWith('.png') && !/\(failed\)/.test(f)) shotIndex[f.replace(/\.png$/, '')] = p
    }
  })(path.join(ROOT, 'cypress', 'screenshots'))
  // The run's own copies win: cypress/screenshots is shared and can be wiped by any other run.
  ;(function own(dir) {
    if (!fs.existsSync(dir)) return
    for (const f of fs.readdirSync(dir)) if (isShot(f) && f.endsWith('.png')) shotIndex[f.replace(/\.png$/, '')] = path.join(dir, f)
  })(path.join(CASE_DIR, 'img'))
  fs.rmSync(path.join(outDir, 'img'), { recursive: true, force: true })
  fs.mkdirSync(path.join(outDir, 'img'), { recursive: true })
  const missing = []
  const img = (name) => {
    const src = shotIndex[name]
    if (!src) { missing.push(name); return null }
    fs.copyFileSync(src, path.join(outDir, 'img', name + '.png'))
    return 'img/' + name + '.png'
  }

  const actHtml = (c, a, key, i) => {
    const shots = c.passed ? a.shots.map((n) => ({ n, src: img(n) })).filter((x) => x.src) : []
    return `<li class="act">
      <div class="act-text">
        <p class="act-do">${md(a.do)} ${VIA[a.via] || ''}</p>
        ${a.code ? `<pre class="cmd"><code>${esc(a.code)}</code></pre>` : ''}
        <ul class="expect">${a.expect.map((e, j) => `<li><label><input type="checkbox" data-chk id="chk-${esc(c.id)}-${key}${i + 1}-${j}"> <span>${md(e)}</span></label></li>`).join('')}</ul>
      </div>
      ${shots.length ? `<div class="shots">${shots.map((x) => `<button class="shot" type="button" data-full="${x.src}" aria-label="Enlarge screenshot"><img src="${x.src}" alt="${esc(c.title)} — ${key === 'a' ? 'step' : 'cleanup'} ${i + 1}" loading="lazy"></button>`).join('')}</div>` : ''}
    </li>`
  }
  const caseHtml = (c) => `
  <article class="case" id="case-${esc(c.id)}" data-slice="${esc(c.slice)}">
    <header class="case-head"><span class="case-id">${esc(c.id)}</span><h3>${md(c.title)}</h3>${c.passed
      ? '<span class="pill pill-ok" title="Every action and its expected result passed in the capture run on this build">Verified</span>'
      : '<span class="pill pill-bad">Fails on this build</span>'}</header>
    ${c.passed ? '' : `<div class="case-fail"><b>Not passing on this build.</b> <span class="muted">Run message: ${esc((c.error || '').split('\n')[0].slice(0, 240))}</span></div>`}
    <p class="covers">Covers: ${(c.covers || []).map((x) => `<span class="tag">${esc(x)}</span>`).join(' ')} · <span class="muted">${esc(SLICES[c.slice] || c.slice)}</span></p>
    <dl class="meta">
      <div><dt>Business</dt><dd>${esc(c.tenant)}</dd></div>
      <div><dt>Role</dt><dd>${esc(c.role)}</dd></div>
      <div class="wide"><dt>What this proves</dt><dd>${md(c.purpose)}</dd></div>
      <div><dt>Before you start</dt><dd><ul>${(c.prereq || []).map((x) => `<li>${md(x)}</li>`).join('')}</ul></dd></div>
      <div><dt>Test data</dt><dd><ul>${(c.data || []).map((x) => `<li>${md(x)}</li>`).join('')}</ul></dd></div>
    </dl>
    <h4>Steps — do each, then tick what you see</h4>
    <ol class="acts">${c.actions.map((a, i) => actHtml(c, a, 'a', i)).join('')}</ol>
    <h4>Cleanup — leave the business as you found it</h4>
    <ol class="acts cleanup">${c.cleanup.map((a, i) => actHtml(c, a, 'c', i)).join('')}</ol>
    <p class="muted"><b>What stays behind:</b> ${md(c.rollback)}</p>
    <label class="signoff"><input type="checkbox" data-chk id="so-${esc(c.id)}"> Human sign-off: I ran this case end to end and got every expected result</label>
  </article>`

  const passed = cases.filter((c) => c.passed).length
  const covered = [...new Set(cases.flatMap((c) => c.covers || []))].sort()
  const ranAt = cases.length ? fmtDate(cases.map((c) => c.capturedAt).sort().slice(-1)[0]) : '—'
  const casesHtml = cases.map(caseHtml).join('\n')

  const coverMap = cfg.coverMap === false ? '' : `<section class="card">
    <h2>Which earlier case each step-by-step case covers</h2>
    <p class="muted">The page used to list ${covered.length} short cases. Each is now inside a full case:</p>
    <table class="map"><thead><tr><th>Earlier case</th><th>Now in</th></tr></thead><tbody>
      ${covered.map((x) => `<tr><td><span class="tag">${esc(x)}</span></td><td>${cases.filter((c) => (c.covers || []).includes(x)).map((c) => `<a href="#case-${esc(c.id)}">${esc(c.id)}</a>`).join(', ')}</td></tr>`).join('')}
    </tbody></table>
  </section>`

  const html = `<title>${esc(cfg.title)}</title>
${STYLE}
<div class="wrap">
  <header class="top">
    <h1>${esc(cfg.heading)}</h1>
    <p>Each case below was <strong>performed, checked and photographed</strong> by an automated run on this build, step by step. Do the same steps
      yourself, tick what you see, and sign off each case. A case marked <em>Fails on this build</em> shows why, and has no pictures.</p>
    <div class="meta-line"><span>${passed} of ${cases.length} cases verified</span><span>Run: ${esc(ranAt)}</span><span>Build ${esc(version.branch)} @ ${esc(version.commit)}</span>
      <span>Design: <code>${esc(cfg.design)}</code></span></div>
  </header>

  <section class="card">
    <h2>Before you start</h2>
    <ul>
${(cfg.beforeYouStart || []).map((x) => `      <li>${x}</li>`).join('\n')}
    </ul>
    <div class="filters" role="group" aria-label="Show cases for">
      <button type="button" data-f="all" aria-pressed="true">All</button>
      ${Object.keys(SLICES).map((k) => `<button type="button" data-f="${k}" aria-pressed="false">${k}</button>`).join('')}
      <span class="progress" id="progress"></span>
    </div>
  </section>

  ${casesHtml || '<p class="card">No capture run found. Run the spec first.</p>'}

  ${coverMap}
  <p class="muted">Generated from the capture run — nothing here is typed from memory.${missing.length ? ' Missing screenshots: ' + esc(missing.join(', ')) : ''}</p>
</div>
<div id="lightbox" role="dialog" aria-label="Screenshot"><img alt=""></div>
${script(cfg.storageKey)}`

  fs.mkdirSync(outDir, { recursive: true })
  fs.writeFileSync(path.join(outDir, 'index.html'), html)
  console.log(`built ${path.join(outDir, 'index.html')}: ${passed}/${cases.length} cases verified, ${Object.keys(shotIndex).length} screenshots indexed, missing ${missing.length}`)
  return { passed, total: cases.length, missing }
}

module.exports = { build, ROOT }
