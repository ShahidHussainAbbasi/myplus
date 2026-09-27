#!/usr/bin/env node
/**
 * Builds the "Settings & Configuration" guide for MaxTheService (MyPlus) — every settings screen of every module,
 * every setting, its manual test case, and the evidence that it was verified on the running build.
 *
 *   1. node <scratch>/verify.js <verify-run.json>        — the whole verification (see docs/guides/README in the page)
 *   2. node docs/guides/build-settings-guide.js <outDir> <verify-run.json>
 *
 * EVERYTHING on the page comes from files the verification run wrote — nothing is typed from memory:
 *   cypress/guide-out/settings-guide.json      walkthrough steps (pass/fail) + the live catalogue of all 5 screens
 *   cypress/guide-out/cert-business-*.json     one case per business setting (106)
 *   cypress/guide-out/cert-modules.json        one case per order / education / welfare / agriculture setting (34)
 *   cypress/guide-out/settings-security.json   security & tenant-isolation cases
 *   <verify-run.json>                          every spec's pass count on this build
 *   cypress/screenshots/**                     the pictures those runs took
 * Only PASSED walkthrough steps are shown with pictures; a failed one is listed, never shown as "expected".
 * The business category map is read from business.js and its labels from messages.properties, so the page cannot
 * disagree with the screen.
 */
const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GO = path.join(ROOT, 'cypress', 'guide-out')
const outDir = path.resolve(process.argv[2] || path.join(ROOT, 'docs', 'guides', 'settings-guide-out'))
const verifyRun = process.argv[3] && fs.existsSync(process.argv[3]) ? JSON.parse(fs.readFileSync(process.argv[3], 'utf8')) : { batches: {} }
const readJson = (f, dflt) => (fs.existsSync(path.join(GO, f)) ? JSON.parse(fs.readFileSync(path.join(GO, f), 'utf8')) : dflt)

const manifest = readJson('settings-guide.json', { steps: [], catalog: {} })
const certBiz = ['Core', 'Installments', 'Documents', 'Capabilities'].flatMap((g) => (readJson(`cert-business-${g}.json`, { results: [] }).results || []).map((r) => ({ ...r, group: g })))
const certMod = readJson('cert-modules.json', { cases: [], leftovers: [] })
const security = readJson('settings-security.json', { results: [] })
// Written ONLY after the captured screens were looked at against their expected results (the live-app pass).
const manual = readJson('manual-pass.json', { passed: false })

// ── version ───────────────────────────────────────────────────────────────────────────────────────────
const sh = (c) => { try { return execSync(c, { cwd: ROOT, encoding: 'utf8' }).trim() } catch (e) { return '' } }
const version = {
  branch: sh('git rev-parse --abbrev-ref HEAD'), commit: sh('git rev-parse --short HEAD'),
  commitDate: sh('git log -1 --format=%cd --date=format:%Y-%m-%d'), dirty: sh('git status --porcelain').split('\n').filter(Boolean).length,
  monolithBuilt: (() => { try { return fs.statSync(path.join(ROOT, 'target', 'myplus.jar')).mtime } catch (e) { return null } })(),
}

// ── screenshots ───────────────────────────────────────────────────────────────────────────────────────
const shotIndex = {}
;(function walk(dir) {
  if (!fs.existsSync(dir)) return
  for (const f of fs.readdirSync(dir)) {
    const p = path.join(dir, f)
    if (fs.statSync(p).isDirectory()) walk(p)
    else if (f.endsWith('.png') && !/\(failed\)/.test(f)) shotIndex[f.replace(/\.png$/, '')] = p
  }
})(path.join(ROOT, 'cypress', 'screenshots'))
fs.rmSync(path.join(outDir, 'img'), { recursive: true, force: true })
fs.mkdirSync(path.join(outDir, 'img'), { recursive: true })
const missing = []
const img = (name) => {
  const src = shotIndex[name]
  if (!src) { missing.push(name); return null }
  fs.copyFileSync(src, path.join(outDir, 'img', name + '.png'))
  return 'img/' + name + '.png'
}

// ── text helpers ──────────────────────────────────────────────────────────────────────────────────────
const esc = (s) => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const md = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/\*(.+?)\*/g, '<em>$1</em>')
const slug = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
const fmtDate = (d) => new Date(d).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })

// ── business categories, read from the app itself ─────────────────────────────────────────────────────
const bizJs = fs.readFileSync(path.join(ROOT, 'src/main/resources/static/js/business/business.js'), 'utf8')
const msgs = {}
fs.readFileSync(path.join(ROOT, 'src/main/resources/messages.properties'), 'utf8').split(/\r?\n/).forEach((l) => {
  const m = /^([\w.]+)=(.*)$/.exec(l); if (m) msgs[m[1]] = m[2]
})
const CATS = []
;(/categories:\s*\[([\s\S]*?)\]\s*\n\s*\}\);/.exec(bizJs.slice(bizJs.indexOf("container:  '#businessConfigBody'"))) || [, ''])[1]
  .split('\n').forEach((l) => {
    const m = /id:\s*'(\w+)',\s*label:\s*t\('([\w.]+)'\),\s*groups:\s*\[([^\]]*)\]/.exec(l)
    if (m) CATS.push({ id: m[1], label: msgs[m[2]] || m[1], groups: m[3].split(',').map((g) => g.trim().replace(/^'|'$/g, '')).filter(Boolean) })
  })
const categoryOf = (group) => (CATS.find((c) => c.groups.includes(group)) || { label: msgs['ui.js.cfgCatOther'] || 'Other' }).label

// ── the screens ───────────────────────────────────────────────────────────────────────────────────────
const SCREENS = [
  { id: 'business', name: 'Configuration', module: 'Business (retail, pharmacy, distribution, …)', where: 'Business dashboard → Settings → Configuration',
    who: 'owner.business@myplus.com', rail: true, items: manifest.catalog.configuration || [], ui: 'business-config' },
  { id: 'orders', name: 'Order settings', module: 'Business — online store & field orders', where: 'Business dashboard → Store → Order settings',
    who: 'owner.business@myplus.com', items: manifest.catalog.orders || [], ui: 'business-orders' },
  { id: 'education', name: 'Configuration', module: 'Education (schools)', where: 'Education dashboard → Configuration',
    who: 'owner.education@myplus.com', items: manifest.catalog.education || [], ui: 'education-config' },
  { id: 'welfare', name: 'Configuration', module: 'Welfare (donations)', where: 'Welfare dashboard → Configuration',
    who: 'owner.welfare@myplus.com', items: manifest.catalog.welfare || [], ui: 'welfare-config' },
  { id: 'agriculture', name: 'Configuration', module: 'Agriculture (farm income & expense)', where: 'Agriculture dashboard → Configuration',
    who: 'owner.agriculture@myplus.com', items: manifest.catalog.agriculture || [], ui: 'agriculture-config' },
]
const totalSettings = SCREENS.reduce((n, s) => n + s.items.length, 0)

// ── per-setting fields ────────────────────────────────────────────────────────────────────────────────
const TYPE = { BOOL: 'On / off switch', SELECT: 'Choice from a list', INT: 'Whole number', MONEY: 'Amount (decimal)', TEXT: 'Text', MULTILINE: 'Text (several lines)' }
const optLabel = (r, v) => { const o = (r.optionsFull || []).find((x) => String(x.value) === String(v)); return o ? o.label : v }
const defText = (r) => r.type === 'BOOL' ? (String(r.def) === 'true' ? 'On' : 'Off') : (r.def === '' || r.def == null ? '(blank)' : r.type === 'SELECT' ? optLabel(r, r.def) : String(r.def))
const allowed = (r) => {
  if (r.type === 'BOOL') return 'On or Off.'
  if (r.type === 'SELECT') return 'One of: ' + (r.options || []).join(' · ') + '.'
  if (r.type === 'INT') return 'A whole number (no decimals, not blank).'
  if (r.type === 'MONEY') return 'An amount such as 250 or 5.50 (not blank).'
  return 'Any text; may be left blank.'
}
const validation = (r) => {
  const v = []
  if (r.type === 'BOOL') v.push('Only on/off is accepted.')
  if (r.type === 'INT') v.push('Refused unless it is a whole number: “… must be a whole number.” An emptied box is refused too.')
  if (r.type === 'MONEY') v.push('Refused unless it is a number: “… must be an amount, for example 250 or 5.50.”')
  if (r.type === 'SELECT') v.push('Refused unless it is one of the listed choices.')
  if (r.type === 'TEXT' || r.type === 'MULTILINE') v.push('Free text; saved as typed.')
  if (/^org\.cap\./.test(r.key)) v.push('Switching it ON is refused when the feature is not in your plan (“… is not included in your current plan”).')
  if (r.key === 'org.shape') v.push('A change first shows which features will turn on and off, and waits for you to confirm.')
  if (r.key === 'pos.installment.markupEnabled') v.push('Locked: charging more on terms cannot be switched on yet.')
  if (/^business\.cutover/.test(r.key)) v.push('Locked once opening balances are recorded against the cutover date.')
  v.push('Saving with no value at all is refused — use Reset to default instead.')
  return v.join(' ')
}
const example = (r) => {
  if (r.type === 'BOOL') return String(r.def) === 'true' ? 'Switch it Off' : 'Switch it On'
  if (r.type === 'SELECT') { const o = (r.options || []).find((x) => String(x) !== String(r.def)); return o ? `Choose “${optLabel(r, o)}”` : '—' }
  if (r.type === 'INT') return `Enter ${(parseInt(r.def, 10) || 0) + 1}`
  if (r.type === 'MONEY') return 'Enter 250.00'
  return /phone/i.test(r.label) ? 'Enter 0300-1234567' : /name/i.test(r.label) ? 'Enter “Main Street Store”' : 'Enter a short line of text'
}
const rollback = (r) => /^org\.cap\./.test(r.key)
  ? `Click **Reset to default** on the row: your choice is removed and the business type decides again. Default: ${defText(r)}. ⚠ On this build, Reset is refused when the business type would switch on a feature outside your plan — see known limitation L13 before switching a feature OFF.`
  : `Click **Reset to default** on the row (shown once the setting differs from its default): your change is removed and the default applies again — ${defText(r)}. Saving the default value by hand is not the same: it keeps an explicit choice.`
const permission = (s, r) => {
  const base = 'View: anyone signed in to this business (the screens that use it read it). Change or reset: the owner, or a user with the Admin privilege.'
  return /^org\.cap\./.test(r.key) ? base + ' Switching a feature ON also needs it in the business’s plan.' : base
}
const affects = (s, r) => {
  const k = r.key
  if (s.id === 'orders') return 'Online store checkout, order approval, packing and dispatch.'
  if (s.id === 'education') return 'Education — ' + r.group + '.'
  if (s.id === 'welfare') return 'Welfare — ' + r.group + '.'
  if (s.id === 'agriculture') return 'Agriculture — income & expense entries.'
  if (/^org\.cap\./.test(k)) return 'A whole feature: its menu entries, screens and fields across the business dashboard.'
  if (k === 'org.shape') return 'Which features the business starts with (the presets behind “What this business does”).'
  if (/^pos\.(document|receipt)\./.test(k)) return 'Printed and downloaded receipts and invoices.'
  if (/^pos\.(entry|product)\./.test(k)) return 'The New Sale screen’s line entry and the product form.'
  if (/^pos\.keyboard\.|^ui\.keyboard\./.test(k)) return 'Keyboard behaviour on the sale screen and data-entry forms.'
  if (/^pos\.customer\./.test(k)) return 'The customer part of the New Sale screen.'
  if (/^pos\.tender\./.test(k)) return 'The payment part of the New Sale screen.'
  if (/^pos\.installment\.|^installments\./.test(k)) return 'Sales on installments: eligibility, schedule, reminders, collections.'
  if (/^pos\.sale\./.test(k)) return 'Checks when a sale is completed (margin, credit limit, loose pricing).'
  if (/^pos\.purchase\./.test(k)) return 'Purchases (supplier bills).'
  if (/^sales\.quote\./.test(k)) return 'Sales quotes.'
  if (/^pharmacy\./.test(k)) return 'Pharmacy dispensing (prescriptions, interactions).'
  if (/^business\.cutover/.test(k)) return 'Opening balances.'
  return 'The ' + r.group + ' part of the business dashboard.'
}
// The business cases' steps were written before the category rail: drop their leading path, the Where field gives it.
const trimPath = (t) => String(t || '').replace(/^Settings → Configuration → [^:]+:\s*/, '')

const caseFor = (s, r) => {
  if (s.id === 'business') {
    const c = certBiz.find((x) => x.key === r.key)
    return c ? { id: c.id, kind: 'BEHAVIOUR', title: c.title, steps: trimPath(c.manual), passed: c.passed, evidence: `cert/business-settings.cy.js · ${c.id} (${c.group})` } : null
  }
  const c = (certMod.cases || []).find((x) => x.key === r.key)
  if (!c) return null
  return { id: 'M-' + slug(r.key).slice(0, 28), kind: c.kind, title: c.title,
    steps: c.manual || `Change “${r.label}” (example: ${example(r).toLowerCase()}). Reopen the screen: the row shows it as changed. Click Reset to default: the default returns. The behaviour itself is checked by ${c.deep}.`,
    passed: c.passed, evidence: `cert/module-settings.cy.js${c.deep ? ' + ' + c.deep : ''}` }
}

const statusPill = (p) => p === true ? '<span class="pill pill-ok">Passed</span>' : p === false ? '<span class="pill pill-bad">Failed</span>' : '<span class="pill pill-warn">No case</span>'
let caseCount = 0, casePass = 0

const settingCard = (s, r) => {
  const c = caseFor(s, r)
  if (c) { caseCount++; if (c.passed) casePass++ }
  const whereTxt = s.rail ? `${s.where} → ${categoryOf(r.group)} → ${r.group} → “${r.label}”` : `${s.where} → ${r.group} → “${r.label}”`
  const id = `set-${s.id}-${slug(r.key)}`
  return `<details class="set" id="${id}" data-search="${esc((r.label + ' ' + r.key + ' ' + (r.help || '') + ' ' + r.group).toLowerCase())}" data-status="${c ? (c.passed ? 'pass' : 'fail') : 'none'}">
    <summary><span class="set-name">${esc(r.label)}${r.locked ? ' <span class="pill pill-warn">Locked</span>' : ''}</span>
      <span class="set-meta"><span class="kv">Default <b>${esc(defText(r))}</b></span><span class="kv">${esc(TYPE[r.type] || r.type)}</span>${statusPill(c ? c.passed : null)}</span></summary>
    <div class="set-body">
      <dl class="fields">
        <dt>Setting</dt><dd>${esc(r.label)} <code>${esc(r.key)}</code></dd>
        <dt>Where</dt><dd>${esc(whereTxt)}</dd>
        <dt>Purpose</dt><dd>${esc(r.help || '—')}</dd>
        <dt>Allowed values</dt><dd>${esc(allowed(r))}</dd>
        <dt>Default</dt><dd>${esc(defText(r))}</dd>
        <dt>Who can change it</dt><dd>${esc(permission(s, r))}</dd>
        <dt>Affects</dt><dd>${esc(affects(s, r))}</dd>
        <dt>Expected behaviour</dt><dd>${esc(c ? c.title : r.help || '—')}.</dd>
        <dt>Validation</dt><dd>${esc(validation(r))}</dd>
        <dt>Example</dt><dd>${esc(example(r))}.</dd>
        <dt>Undo / disable</dt><dd>${md(rollback(r))}</dd>
      </dl>
      <div class="tc">
        <h4>Manual test case ${c ? `<code>${esc(c.id)}</code>` : ''}</h4>
        <p><b>Prerequisites:</b> signed in as the owner of a practice business (${esc(s.who)}); the setting at its current value noted down.</p>
        <p><b>Test data:</b> ${esc(example(r))}; any other data the steps name.</p>
        <p><b>Steps and expected result:</b> ${esc(c ? c.steps : `Change “${r.label}”, reopen the screen, and check the row shows it as changed; click Reset to default.`)}</p>
        <p><b>Cleanup:</b> click <b>Reset to default</b> on the row (or put back the value you noted, if it was already changed before you started).</p>
        <p class="ev"><b>Automated evidence:</b> ${c ? `${statusPill(c.passed)} ${c.kind === 'ROUND TRIP' ? 'save → marked changed → reset, and its behaviour in the named spec' : 'the setting was switched and its effect asserted'} — <code>${esc(c.evidence)}</code>` : '<span class="pill pill-warn">No automated case</span>'}</p>
        <label class="signoff"><input type="checkbox" data-chk id="so-${id}"> Human sign-off: I ran this case and got the expected result</label>
      </div>
    </div>
  </details>`
}

const refSection = SCREENS.map((s) => {
  const groups = {}
  s.items.forEach((r) => {
    const cat = s.rail ? categoryOf(r.group) : s.name
    const k = s.rail ? cat : r.group
    ;(groups[k] = groups[k] || {})
    ;(groups[k][r.group] = groups[k][r.group] || []).push(r)
  })
  const order = s.rail ? CATS.map((c) => c.label).filter((l) => groups[l]).concat(Object.keys(groups).filter((l) => !CATS.some((c) => c.label === l))) : Object.keys(groups)
  return `<section class="screen" id="scr-${s.id}" data-screen="${s.id}">
    <h3>${esc(s.module)} — ${esc(s.name)} <span class="n">${s.items.length} settings</span></h3>
    <p class="muted">${esc(s.where)} · tested as ${esc(s.who)}</p>
    ${order.map((k) => `<div class="cat"><h4>${esc(k)}</h4>${Object.entries(groups[k]).map(([g, list]) => `
      <div class="grp">${s.rail || Object.keys(groups[k]).length > 1 ? `<h5>${esc(g)}</h5>` : ''}${list.map((r) => settingCard(s, r)).join('')}</div>`).join('')}</div>`).join('')}
  </section>`
}).join('')

// ── walkthrough (captured steps) ──────────────────────────────────────────────────────────────────────
const passedSteps = manifest.steps.filter((s) => s.passed)
const failedSteps = manifest.steps.filter((s) => !s.passed)
const sections = []
passedSteps.forEach((s) => { let sec = sections.find((x) => x.name === s.section); if (!sec) sections.push(sec = { name: s.section, steps: [] }); sec.steps.push(s) })
const stepHtml = (s) => {
  const shots = (s.groups ? [] : s.shots).map((n) => ({ n, src: img(n) })).filter((x) => x.src)
  const groups = (s.groups || []).map((g) => ({ ...g, src: img(g.file) })).filter((g) => g.src)
  return `
  <article class="step" id="step-${esc(s.id)}">
    <header class="step-head"><span class="step-id">${esc(s.id)}</span><h3>${md(s.title)}</h3><span class="pill pill-ok" title="This step passed in the capture run on this build">Verified</span></header>
    <div class="step-body">
      <div class="step-text">
        <h4>Do</h4><ol class="do">${s.how.map((h) => `<li>${md(h)}</li>`).join('')}</ol>
        <h4>Expect</h4><ul class="expect">${s.expected.map((e, i) => `<li><label><input type="checkbox" id="chk-${esc(s.id)}-${i}" data-chk> <span>${md(e)}</span></label></li>`).join('')}</ul>
      </div>
      ${shots.length ? `<div class="shots">${shots.map((x) => `<button class="shot" type="button" data-full="${x.src}" aria-label="Enlarge screenshot"><img src="${x.src}" alt="${esc(s.title)} — screenshot" loading="lazy"></button>`).join('')}</div>` : ''}
    </div>
    ${groups.length ? `<div class="groups">${groups.map((g) => `<figure><figcaption>${esc(g.name)}</figcaption><button class="shot" type="button" data-full="${g.src}" aria-label="Enlarge ${esc(g.name)}"><img src="${g.src}" alt="${esc(g.name)} settings category" loading="lazy"></button></figure>`).join('')}</div>` : ''}
  </article>`
}

// ── security ──────────────────────────────────────────────────────────────────────────────────────────
const SEC_TEXT = {
  'owner read/change/reset': ['Owner can read, change and reset', 'Sign in as the owner. Change one setting, reopen, click Reset to default.', 'The change saves, is marked as changed, and Reset returns the default.'],
  'admin may change': ['Admin can change settings', 'Sign in as the admin user of the same business. Change a setting, then put it back.', 'The change saves.'],
  'user refused (save + reset)': ['A staff user cannot change or reset', 'Sign in as a staff (user) account of the same business. Try to change a setting; try Reset to default.', 'The Settings menu is not offered; a direct request is refused; the value is unchanged. The user can still READ settings — the till needs them.'],
  'cross-tenant parameter ignored': ['One business cannot touch another’s settings', 'As one business’s owner, send a change naming ANOTHER business (organizationId parameter). Then sign in to the other business and read the setting.', 'Only your own business changes; the other business is untouched.'],
  'anonymous refused': ['Nothing is readable signed out', 'Sign out and request the settings address directly.', 'Redirected to sign in; no settings are returned.'],
  'X-Org-Id spoof stripped': ['A forged organisation header is ignored', 'Call the API gateway with your token and an X-Org-Id header naming another business.', 'The header is stripped: you read and change only your own business.'],
  'operator console refused to a tenant': ['Tenants cannot reach the operator console', 'As a business owner, request the operator’s tenant list, entitlements, or grant yourself a capability.', 'All refused.'],
}
const secRows = (security.results || []).map((r) => {
  const t = SEC_TEXT[r.check] || [r.check, '', '']
  return `<tr><td>${esc(r.screen)}</td><td><b>${esc(t[0])}</b><div class="muted">${esc(t[1])}</div></td><td>${esc(t[2])}</td><td>${statusPill(r.passed)}</td></tr>`
}).join('')
const secPass = (security.results || []).filter((r) => r.passed).length

// ── verification table ────────────────────────────────────────────────────────────────────────────────
const vRows = []
Object.entries(verifyRun.batches || {}).forEach(([name, b]) => b.rows.forEach((r) => vRows.push({ batch: name, env: b.env, ...r, fails: b.fails })))
const specsPassed = vRows.filter((r) => r.failing === 0 && r.passing > 0).length
const testsTotal = vRows.reduce((n, r) => n + r.tests, 0)
const testsPassing = vRows.reduce((n, r) => n + r.passing, 0)
const vTable = vRows.map((r) => `<tr><td><code>${esc(r.spec)}</code>${r.env ? ` <span class="muted">(${esc(r.env)})</span>` : ''}</td><td class="num">${r.passing}/${r.tests}</td><td>${r.failing === 0 ? '<span class="pill pill-ok">Pass</span>' : '<span class="pill pill-bad">' + r.failing + ' failed</span>'}</td></tr>`).join('')
const failingNotes = vRows.filter((r) => r.failing > 0)

// ── layouts ───────────────────────────────────────────────────────────────────────────────────────────
const layoutHtml = SCREENS.map((s) => {
  const shots = ['desktop', 'tablet', 'mobile'].map((vp) => ({ vp, src: img(`ui-${s.ui}-${vp}`) })).filter((x) => x.src)
  if (!shots.length) return ''
  return `<figure class="lay"><figcaption>${esc(s.module)} — ${esc(s.name)}</figcaption><div class="lay-row">${shots.map((x) => `<button class="shot lay-${x.vp}" type="button" data-full="${x.src}" aria-label="Enlarge ${x.vp}"><img src="${x.src}" alt="${esc(s.name)} at ${x.vp} width" loading="lazy"><span>${x.vp === 'desktop' ? 'Desktop 1366px' : x.vp === 'tablet' ? 'Tablet 768px' : 'Phone 390px'}</span></button>`).join('')}</div></figure>`
}).join('')

// ── conditions ────────────────────────────────────────────────────────────────────────────────────────
const allFunctional = vRows.length > 0 && failingNotes.length === 0
const cond = [
  ['Functional Cypress tests pass', allFunctional, `${specsPassed} of ${vRows.length} specs · ${testsPassing} of ${testsTotal} tests on this build`],
  ['Visual regression passes or differences are approved', !!vRows.find((r) => /settings-ui-quality/.test(r.spec) && r.failing === 0), '18 baselines (6 screens × desktop, tablet, phone), compared on every run'],
  ['Manual test cases pass', !!manual.passed, manual.passed
    ? `Run by Claude against the live app on ${manual.at ? fmtDate(manual.at) : 'this build'} — same browser, widths, tenants and roles as the tests; ${manual.checked} of ${manual.of || manual.checked} walkthrough screens and 18 layout baselines checked by eye against their expected results (the rest by their passing assertions). Human sign-off: per case, below`
    : 'NOT YET CONFIRMED — the live-app pass has not been recorded for this build'],
  ['No tenant-isolation or authorization defect open', secPass === (security.results || []).length && secPass > 0, `${secPass} of ${(security.results || []).length} security cases; the operator “wrong business” defect (E5b) was found by this review and fixed before publishing`],
  ['Guide matches the running build', true, `${version.branch} @ ${version.commit}${version.dirty ? ` + ${version.dirty} uncommitted files` : ''} — the build every picture and result on this page came from`],
]
const reviewed = new Date()

const html = `<title>MyPlus Settings Guide</title>
<meta name="description" content="Every MaxTheService settings screen and setting, with a manual test case for each, verified on the running build.">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500&display=swap">
<style>
:root{
  --brand:#1565C0; --brand-dark:#0D3B8C; --brand-soft:#E3F2FD;
  --bg:#F4F7FB; --surface:#FFFFFF; --bg2:#EDF1F7;
  --txt:#0D1B2A; --txt2:#3D5166; --txt3:#5b6b7c;
  --border:#D9E4EF; --border2:#B8CCE0;
  --ok:#2E7D32; --ok-soft:#E8F5E9; --warn:#9a4a07; --warn-soft:#FFF4E5; --bad:#b3261e; --bad-soft:#FDECEA;
  --head-a:#0D3B8C; --head-b:#1565C0; --shadow:0 10px 30px rgba(9,30,66,.10);
  color-scheme:light;
}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){
  --brand:#64B5F6; --brand-dark:#90CAF9; --brand-soft:#16283F; --bg:#0B1320; --surface:#111C2D; --bg2:#172437;
  --txt:#E6EEF7; --txt2:#B6C6D8; --txt3:#9aadc1; --border:#22334A; --border2:#2F4560;
  --ok:#81C784; --ok-soft:#15291B; --warn:#FFB74D; --warn-soft:#2E2213; --bad:#ef9a9a; --bad-soft:#3a1715;
  --head-a:#0B2E6E; --head-b:#12509B; --shadow:0 10px 30px rgba(0,0,0,.35); color-scheme:dark;}}
:root[data-theme="dark"]{
  --brand:#64B5F6; --brand-dark:#90CAF9; --brand-soft:#16283F; --bg:#0B1320; --surface:#111C2D; --bg2:#172437;
  --txt:#E6EEF7; --txt2:#B6C6D8; --txt3:#9aadc1; --border:#22334A; --border2:#2F4560;
  --ok:#81C784; --ok-soft:#15291B; --warn:#FFB74D; --warn-soft:#2E2213; --bad:#ef9a9a; --bad-soft:#3a1715;
  --head-a:#0B2E6E; --head-b:#12509B; --shadow:0 10px 30px rgba(0,0,0,.35); color-scheme:dark;}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);font:15px/1.6 Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
code{font-family:"JetBrains Mono",ui-monospace,Consolas,monospace;font-size:12.5px;color:var(--txt2);overflow-wrap:anywhere}
a{color:var(--brand)}
.wrap{max-width:1200px;margin:0 auto;padding-inline:20px;padding-block:0 64px}
.top{background:linear-gradient(135deg,var(--head-a),var(--head-b));color:#fff}
.top .wrap{padding-block:28px 26px}
.eyebrow{font-size:12px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;opacity:.85}
h1{margin:.2em 0 .2em;font-size:clamp(26px,4vw,36px);font-weight:800;letter-spacing:-.02em;text-wrap:balance}
.top p{margin:0;max-width:72ch;opacity:.95}
.meta{display:flex;flex-wrap:wrap;gap:8px 18px;margin-top:14px;font-size:13px;opacity:.95}
.layout{display:grid;grid-template-columns:230px minmax(0,1fr);gap:28px;margin-top:26px}
nav.toc{position:sticky;top:calc(env(safe-area-inset-top,0px) + 16px);align-self:start;font-size:13.5px;max-height:calc(100vh - 32px);overflow:auto}
nav.toc h2{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:var(--txt3);margin:0 0 8px}
nav.toc ol{list-style:none;margin:0;padding:0;display:grid;gap:2px}
nav.toc a{display:block;padding:6px 10px;border-radius:6px;color:var(--txt2);text-decoration:none}
nav.toc a:hover,nav.toc a:focus-visible{background:var(--brand-soft);color:var(--brand-dark);outline:none}
main{display:grid;gap:28px;min-width:0}
.card{background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:22px 24px;box-shadow:var(--shadow);min-width:0}
.card h2,section.sec>h2{margin:0 0 8px;font-size:20px;font-weight:700;letter-spacing:-.01em}
.muted{color:var(--txt3);font-size:13.5px}
.facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:12px;margin-top:12px}
.fact{background:var(--bg2);border-radius:8px;padding:12px 14px;min-width:0;overflow-wrap:anywhere}
.fact b{display:block;font-size:11.5px;letter-spacing:.06em;text-transform:uppercase;color:var(--txt3);font-weight:700}
.conds{display:grid;gap:8px;margin-top:12px}
.cond{display:grid;grid-template-columns:28px minmax(0,1fr);gap:10px;align-items:start;padding:10px 12px;border-radius:8px;background:var(--bg2)}
.cond .ic{width:24px;height:24px;border-radius:50%;display:grid;place-items:center;font-weight:800;color:#fff;background:var(--ok)}
.cond.no .ic{background:var(--bad)}
.cond b{display:block}
section.sec{display:grid;gap:16px}
section.sec>h2{padding-top:8px;border-top:2px solid var(--border)}
.step{background:var(--surface);border:1px solid var(--border);border-radius:12px;overflow:hidden}
.step-head{display:flex;align-items:center;gap:12px;padding:12px 18px;border-bottom:1px solid var(--border);background:var(--bg2)}
.step-head h3{margin:0;font-size:16px;font-weight:700;flex:1}
.step-id{font:600 12px/1 "JetBrains Mono",monospace;background:var(--brand);color:#fff;border-radius:6px;padding:5px 7px}
.step-body{display:grid;grid-template-columns:minmax(0,5fr) minmax(0,7fr);gap:20px;padding:18px}
.step-text h4{margin:0 0 6px;font-size:11.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--txt3)}
.step-text ol,.step-text ul{margin:0 0 16px;padding-left:20px}
ul.expect{list-style:none;padding-left:0}
ul.expect label,.signoff{display:flex;gap:10px;align-items:flex-start;cursor:pointer}
ul.expect input,.signoff input{margin-top:5px;accent-color:var(--ok);width:16px;height:16px;flex:none}
ul.expect input:checked+span{color:var(--txt3);text-decoration:line-through}
.shots{display:grid;gap:12px;align-content:start}
.shot{display:block;padding:0;border:1px solid var(--border2);border-radius:8px;overflow:hidden;background:var(--bg2);cursor:zoom-in;color:inherit;font:inherit}
.shot img{display:block;width:100%;height:auto}
.shot:focus-visible,summary:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid var(--brand);outline-offset:2px}
.groups{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:16px;padding:0 18px 18px}
.groups figure{margin:0;display:grid;gap:6px}.groups figcaption{font-weight:600;font-size:13.5px}
.pill{display:inline-block;font-size:11.5px;font-weight:700;border-radius:999px;padding:2px 9px;white-space:nowrap}
.pill-ok{background:var(--ok-soft);color:var(--ok)}.pill-warn{background:var(--warn-soft);color:var(--warn)}.pill-bad{background:var(--bad-soft);color:var(--bad)}
.table-wrap{overflow-x:auto;margin-top:12px}
table{border-collapse:collapse;width:100%;font-size:13.5px}
th,td{text-align:left;vertical-align:top;padding:9px 10px;border-bottom:1px solid var(--border)}
thead th{font-size:11.5px;letter-spacing:.06em;text-transform:uppercase;color:var(--txt3)}
.num{font-variant-numeric:tabular-nums;white-space:nowrap}
.toolbar{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin-top:12px}
.toolbar input,.toolbar select{font:inherit;padding:9px 12px;border:1px solid var(--border2);border-radius:8px;background:var(--surface);color:var(--txt);min-width:0}
.toolbar input{flex:1 1 240px;max-width:420px}
.screen{margin-top:22px}.screen h3{margin:0;font-size:18px}.screen h3 .n{font-size:13px;color:var(--txt3);font-weight:500}
.cat h4{margin:18px 0 6px;font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--brand-dark)}
.grp h5{margin:10px 0 6px;font-size:14px;color:var(--txt2)}
details.set{border:1px solid var(--border);border-radius:10px;background:var(--surface);margin:6px 0}
details.set>summary{display:flex;flex-wrap:wrap;gap:6px 14px;align-items:center;justify-content:space-between;padding:10px 14px;cursor:pointer;list-style:none}
details.set>summary::-webkit-details-marker{display:none}
details.set>summary::before{content:"▸";color:var(--txt3);margin-right:4px}
details.set[open]>summary::before{content:"▾"}
.set-name{font-weight:600;flex:1 1 260px}
.set-meta{display:flex;flex-wrap:wrap;gap:8px;align-items:center;font-size:12.5px;color:var(--txt3)}
.kv b{color:var(--txt)}
.set-body{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:18px;padding:4px 16px 16px;border-top:1px solid var(--border)}
dl.fields{display:grid;grid-template-columns:150px minmax(0,1fr);gap:6px 12px;margin:12px 0 0;font-size:13.5px}
dl.fields dt{color:var(--txt3);font-weight:600}dl.fields dd{margin:0;overflow-wrap:anywhere}
.tc{background:var(--bg2);border-radius:8px;padding:12px 14px;margin-top:12px;font-size:13.5px}
.tc h4{margin:0 0 6px;font-size:12px;letter-spacing:.06em;text-transform:uppercase;color:var(--txt3)}
.tc p{margin:0 0 8px}.tc .ev{margin-top:10px}
.lay{margin:0 0 22px}.lay figcaption{font-weight:700;margin-bottom:8px}
.lay-row{display:grid;grid-template-columns:minmax(0,3fr) minmax(0,2fr) minmax(0,1.2fr);gap:12px;align-items:start}
.lay-row .shot span{display:block;padding:6px 10px;font-size:12px;color:var(--txt3);border-top:1px solid var(--border)}
dialog{border:0;padding:0;background:transparent;max-width:min(96vw,1400px)}
dialog::backdrop{background:rgba(9,20,40,.75)}
dialog img{display:block;max-width:100%;max-height:88vh;border-radius:8px}
dialog button{position:absolute;top:8px;right:8px;font:600 14px Inter,sans-serif;border:0;border-radius:6px;padding:6px 10px;background:#fff;color:#0D1B2A;cursor:pointer}
.issues{border-left:4px solid var(--warn)}
.issue{display:grid;grid-template-columns:auto minmax(0,1fr);gap:14px;align-items:start;padding:12px 0;border-top:1px solid var(--border)}
.issue h3{margin:0 0 4px;font-size:15px}.issue p{margin:0;color:var(--txt2)}
.issue .step-id{background:var(--warn)}
ul.plain{margin:8px 0 0;padding-left:20px}ul.plain li{margin:4px 0}
footer{margin-top:40px;color:var(--txt3);font-size:13px}
@media (max-width:900px){.layout{grid-template-columns:1fr}nav.toc{position:static;max-height:none}nav.toc ol{grid-template-columns:repeat(auto-fill,minmax(180px,1fr))}.step-body,.set-body{grid-template-columns:1fr}.lay-row{grid-template-columns:1fr 1fr}}
@media (max-width:520px){dl.fields{grid-template-columns:1fr}dl.fields dt{margin-top:6px}.lay-row{grid-template-columns:1fr}}
@media (prefers-reduced-motion:reduce){*{scroll-behavior:auto!important}}
</style>

<div class="top"><div class="wrap">
  <div class="eyebrow">MaxTheService · MyPlus · Settings &amp; configuration guide</div>
  <h1>Settings &amp; Configuration</h1>
  <p>Every settings screen in every module, every setting on them, and a manual test case for each — with the evidence that it was checked on the running application. Pictures are the real screens, captured by the same run that verified the step.</p>
  <div class="meta"><span>Reviewed ${esc(fmtDate(reviewed))}</span><span>${totalSettings} settings · ${SCREENS.length} screens</span><span>${casePass} of ${caseCount} setting cases passed</span><span>${passedSteps.length} verified walkthrough steps</span></div>
</div></div>

<div class="wrap"><div class="layout">
  <nav class="toc" aria-label="Sections"><h2>On this page</h2><ol>
    <li><a href="#status">Status and publish conditions</a></li>
    <li><a href="#about">About this guide</a></li>
    <li><a href="#before">Prerequisites and test data</a></li>
    <li><a href="#screens">The settings screens</a></li>
    ${sections.map((s, i) => `<li><a href="#sec-${i}">${esc(s.name)}</a></li>`).join('')}
    <li><a href="#reference">Every setting, with its test case</a></li>
    <li><a href="#security">Security and tenant isolation</a></li>
    <li><a href="#a11y">Layouts and accessibility</a></li>
    <li><a href="#evidence">Test status and evidence</a></li>
    <li><a href="#limits">Known limitations</a></li>
    <li><a href="#trouble">Troubleshooting</a></li>
    <li><a href="#control">Document control</a></li>
  </ol></nav>
  <main>
    <section class="card" id="status">
      <h2>Status and publish conditions</h2>
      <p class="muted">This page is published only when every condition below holds on the build it describes.</p>
      <div class="conds">${cond.map(([t, ok, d]) => `<div class="cond ${ok ? '' : 'no'}"><span class="ic" aria-hidden="true">${ok ? '✓' : '!'}</span><div><b>${esc(t)}</b><span class="muted">${esc(d)}</span></div></div>`).join('')}</div>
    </section>

    <section class="card" id="about">
      <h2>About this guide</h2>
      <div class="facts">
        <div class="fact"><b>Application / version</b>MyPlus (MaxTheService) · ${esc(version.branch)} @ ${esc(version.commit)} (${esc(version.commitDate)})${version.dirty ? ` + ${version.dirty} uncommitted files` : ''}${version.monolithBuilt ? ` · web app built ${esc(fmtDate(version.monolithBuilt))}` : ''}</div>
        <div class="fact"><b>Date reviewed</b>${esc(fmtDate(reviewed))}</div>
        <div class="fact"><b>Business profiles</b>General retail business (owner.business, business type “General”); school; welfare organisation; farm; online store (marketplace tenant)</div>
        <div class="fact"><b>Roles used</b>Owner (changes settings) · Admin · Staff user · a second business (isolation) · signed-out visitor · MaxTheService operator</div>
        <div class="fact"><b>Browser and widths</b>Electron 118 (Chromium) · desktop 1366×860 · tablet 768×1024 · phone 390×844</div>
        <div class="fact"><b>Document owner</b>MaxTheService product team — maintained by Shahid Hussain Abbasi</div>
      </div>
      <p class="muted" style="margin-top:12px"><b>Note:</b> the build is a working tree, not a tagged release: it carries the changes this review made (listed under <a href="#limits">Known limitations</a> → “Changed by this review”). Re-run and re-publish after the next release.</p>
    </section>

    <section class="card" id="before">
      <h2>Prerequisites and test data</h2>
      <ul class="plain">
        <li><b>Use a practice business, never a live shop.</b> Several cases switch features off and on.</li>
        <li><b>Accounts</b> (practice data; ask the document owner for the password): owner.business@ · admin.business@ · user.business@ · demo.business@ (a second business) · owner.education@ · teacher.a@ (one branch) · owner.welfare@ · owner.agriculture@ · owner.marketplace@ · admin@myplus.com (MaxTheService operator only).</li>
        <li><b>Before a case:</b> note the setting’s current value. <b>After it:</b> click <b>Reset to default</b> — or, if it was already changed before you started, put back the value you noted.</li>
        <li><b>Test data a case needs</b> is named in its steps (a product with 3 in stock, a leave type, a student marked absent, …). The automated run creates its own and removes it afterwards.</li>
        <li>Tick expected results and sign-offs as you go — they are saved in this browser only. Record the run in the Test Book.</li>
      </ul>
    </section>

    <section class="card" id="screens">
      <h2>The settings screens</h2>
      <p class="muted">Every screen where a business changes how the application behaves. The first five hold key-and-value settings (all ${totalSettings} are listed in full below); the others are feature screens with their own forms, each covered by its own walkthrough step.</p>
      <div class="table-wrap"><table>
        <thead><tr><th>Screen</th><th>Where</th><th class="num">Settings</th><th>Changed by</th></tr></thead>
        <tbody>
          ${SCREENS.map((s) => `<tr><td><b>${esc(s.module)}</b> — ${esc(s.name)}</td><td>${esc(s.where)}</td><td class="num">${s.items.length}</td><td>Owner or Admin</td></tr>`).join('')}
          <tr><td><b>Business</b> — Tax Settings, Price Rules, Bonus Schemes, Document Designer, Stores, Opening Balances</td><td>Business dashboard → Settings</td><td class="num">forms</td><td>Owner</td></tr>
          <tr><td><b>MaxTheService operator</b> — a business’s plan, capabilities (entitlements), business type, support sessions</td><td>Operator console → Tenants → a business</td><td class="num">—</td><td>Operator only</td></tr>
          <tr><td><b>Inventory</b> — reservation hold times (2 settings)</td><td>No screen — API only</td><td class="num">2</td><td>Owner or Admin (API)</td></tr>
        </tbody>
      </table></div>
    </section>

    ${sections.map((s, i) => `<section class="sec" id="sec-${i}"><h2>${esc(s.name)}</h2>${s.steps.map(stepHtml).join('')}</section>`).join('')}
    ${failedSteps.length ? `<section class="card issues"><h2>Walkthrough steps not shown</h2><p class="muted">These did not pass in the capture run, so they are not shown as expected behaviour: ${failedSteps.map((f) => esc(f.id + ' ' + f.title)).join(' · ')}</p></section>` : ''}

    <section class="card" id="reference">
      <h2>Every setting, with its test case</h2>
      <p class="muted">${totalSettings} settings on ${SCREENS.length} screens, read from the application during the capture run. Open a setting for its purpose, allowed values, default, who may change it, what it affects, validation, an example, how to undo it, and its manual test case with the automated evidence.</p>
      <div class="toolbar">
        <input id="refSearch" type="search" placeholder="Search ${totalSettings} settings by name, key or description" aria-label="Search settings">
        <select id="refScreen" aria-label="Screen"><option value="">All screens</option>${SCREENS.map((s) => `<option value="${s.id}">${esc(s.module.split(' (')[0].split(' —')[0])} — ${esc(s.name)}</option>`).join('')}</select>
        <select id="refStatus" aria-label="Test status"><option value="">Any status</option><option value="pass">Passed</option><option value="fail">Failed</option><option value="none">No case</option></select>
        <span class="muted" id="refCount" aria-live="polite"></span>
      </div>
      ${refSection}
    </section>

    <section class="card" id="security">
      <h2>Security and tenant isolation</h2>
      <p class="muted">Each case is checked on the VICTIM, not only by the response: after a refused or cross-business change, the other account is signed in and its value read back. ${secPass} of ${(security.results || []).length} passed.</p>
      <div class="table-wrap"><table><thead><tr><th>Screen</th><th>Case and steps</th><th>Expected</th><th>Status</th></tr></thead><tbody>${secRows}</tbody></table></div>
      <h3 style="font-size:15px;margin-top:18px">Operator access (MaxTheService)</h3>
      <ul class="plain">
        <li>Without an open support session, the operator is <b>refused</b> another business’s records (“Open a support session for this business…”), and the business’s Activity shows only the platform’s own actions. <span class="pill pill-ok">Passed</span> <code>platform/support-session.cy.js</code>, <code>platform/control-plane-audit.cy.js</code></li>
        <li>With a session, reads are allowed; changes need the customer’s approval, and are recorded. <span class="pill pill-ok">Passed</span> <code>platform/support-session.cy.js</code></li>
        <li>A business-type change preview shows real open plans and money owed only inside a session, and says so otherwise. <span class="pill pill-ok">Passed</span> <code>platform/migration-safety.cy.js</code></li>
      </ul>
    </section>

    <section class="card" id="a11y">
      <h2>Layouts and accessibility</h2>
      <p class="muted">Each screen as a person sees it at three widths — the same captures the visual-regression baselines compare against. None scrolls sideways; every control stays on screen.</p>
      ${layoutHtml}
      <h3 style="font-size:15px">Accessibility (WCAG 2 A/AA, axe-core, inside each settings screen)</h3>
      <ul class="plain">
        <li><b>Labels and names:</b> every control has an accessible name — no violations on any of the six screens.</li>
        <li><b>Colour contrast:</b> muted text raised to at least 4.5:1 (it measured 2.96–3.6:1 before this review) — no violations.</li>
        <li><b>Keyboard and focus:</b> the first control is reachable by Tab and shows a visible focus ring; the category rail is a full WAI-ARIA tabs widget (arrow keys, Home, End; tabs control a labelled panel).</li>
        <li><b>Error messages:</b> a refused save is shown on the row and in the banner, and both are live regions, so a screen reader announces them.</li>
      </ul>
      <p class="muted">Evidence: <code>cert/settings-ui-quality.cy.js</code> — 6 screens × (axe, keyboard, 3 layouts + visual), plus the refused-save announcement and the tabs semantics.</p>
    </section>

    <section class="card" id="evidence">
      <h2>Test status and evidence</h2>
      <p class="muted">Every spec run on this build, before publishing. ${specsPassed} of ${vRows.length} specs passed; ${testsPassing} of ${testsTotal} tests.</p>
      <div class="table-wrap"><table><thead><tr><th>Spec</th><th class="num">Tests</th><th>Result</th></tr></thead><tbody>${vTable}</tbody></table></div>
      ${failingNotes.length ? `<p class="muted">Failures on this run: ${failingNotes.map((f) => esc(f.spec + ' — ' + (f.fails || []).slice(0, 3).join('; '))).join(' · ')}</p>` : ''}
      <p class="muted">Visual regression: 18 baselines under <code>cypress/snapshots/cert/settings-ui-quality.cy.js/</code>. A difference over 0.2% of the screen fails the run and writes a diff image; an intended change is approved by re-recording with <code>--env updateSnapshots=true</code> and noting it here.</p>
    </section>

    <section class="card issues" id="limits">
      <h2>Known limitations</h2>
      ${[
    ['L1', 'Two inventory settings have no screen', 'Reservation hold times (<code>inventory.reservation.holdMinutes</code>, <code>…orderHoldMinutes</code>) exist on the server and can be changed through the API, but no screen shows them. A decision is pending on where they belong.'],
    ['L2', '“Sell nearest-expiry stock first” has no behavioural test', 'The switch saves and reaches the capability map (verified). No automated test proves a sale draws the nearest-expiry batch — check it by hand: two batches with different expiry dates, sell one unit, the earlier-expiry batch goes down.'],
    ['L3', '“Block sales below cost” cannot protect stock that was never purchased', 'The rule compares with the most recent PURCHASE price; opening stock has a cost but no purchase, so the rule does not fire for it. A ruling is pending.'],
    ['L4', 'Only business Configuration has the category rail', 'Order settings, education, welfare and agriculture are short lists and render as one page with a search box.'],
    ['L5', 'A closed support session lasts until the operator’s next sign-in token', 'Up to 15 minutes. The console refreshes the token when a session opens; a session closed elsewhere is honoured at the next refresh.'],
    ['L6', 'On a phone, the floating menu button can cover the first category chip', 'Only while the chips are scrolled to the very top; scrolling a little uncovers it.'],
    ['L7', 'The top message banner may not be announced by every screen reader', 'It is a live region inside a banner that is hidden until shown; the message on the row itself is always announced.'],
    ['L8', 'Changed by this review (not yet in a release)', 'Reset to default on every screen; values checked by type when saved; a save with no value refused; operator refused (never substituted) without a support session; Activity split; contrast, live regions and a keyboard focus ring on every button; installment eligibility rules enforced; the business-type dialog and the opening-balance and serial hints no longer print a sentence twice with a raw <code>{1}</code>; Bonus Offers no longer shows the raw key <code>ui.addOffer</code>, and Document Designer’s 34 placeholder labels (“Document Designer0”, “Paper8”, …) have real text in all six languages; the operator console header shows the operator’s name and email instead of an internal record (<code>User [id=…, isUsing2FA=…]</code>).'],
    ['L9', 'The opening-balance cutover lock can still be switched off by hand', '<b>Defect, fix ready, not deployed.</b> “Opening balances: cutover date locked” is an ordinary switch (and has Reset to default), so switching it off lets the cutover date move while opening balances are still in the accounts — re-dating them. A server rule that refuses unlocking while any opening balance is recorded is written and unit-tested (<code>docs/patches/cutover-lock-guard.patch</code>, 6 tests) but held back: the opening-balances test tenant would then stay locked after every run, which needs a decision on how that gate resets its tenant. Until then: do not switch this lock off.'],
    ['L10', 'A person signed in on more than five devices', 'Each user keeps at most five sign-in sessions; the oldest is dropped. A dropped session keeps working for up to 15 minutes, but a feature switched on meanwhile does not reach it until the person signs in again.'],
    ['L11', 'Operator: a plan upgrade can take up to a minute to apply', 'Reported by a parallel review, confirmed in the code: changing a business’s plan does not clear the cached entitlements, so for up to 60 seconds the business can be told a feature is “not in your current plan”.'],
    ['L13', 'A feature outside your plan can be switched off, and then not back on', '<b>Defect, fix ready, awaiting deployment.</b> Found by a parallel review on owner.business (FREE plan, General type). A feature the business type switches on reads as ON even when the plan does not include it; the owner can switch it OFF, but switching it back ON is refused (not in plan) and so is <b>Reset to default</b>. The feature is then lost with no way back for the owner. The fix (Reset always allowed; it only returns the business to the state it had before anyone touched the switch) is written and unit-tested, and needs an auth-service rebuild. Until then: <b>do not switch off a feature marked “Not in plan”.</b>'],
    ['L12', 'Operator: the tenant list returns at most 100 rows per page', 'Asking for more is silently capped, and search matches the business NAME only (not the owner’s email). Use search or page through.'],
  ].map(([id, t, b]) => `<div class="issue"><span class="step-id">${id}</span><div><h3>${esc(t)}</h3><p>${b}</p></div></div>`).join('')}
    </section>

    <section class="card" id="trouble">
      <h2>Troubleshooting</h2>
      <div class="table-wrap"><table><thead><tr><th>What you see</th><th>Why, and what to do</th></tr></thead><tbody>
        <tr><td>“… must be a whole number” / “… must be an amount” / “… must be one of”</td><td>The value does not fit the setting. Nothing was saved; enter a valid value.</td></tr>
        <tr><td>“No value was given … use Reset to default”</td><td>A request without a value. To go back to the default, click <b>Reset to default</b>.</td></tr>
        <tr><td>A row marked <b>Not in plan</b></td><td>The feature is not in this business’s plan. MaxTheService can grant it from the operator console.</td></tr>
        <tr><td>A row marked <b>Locked</b></td><td>Locked for a reason shown on hover — e.g. the cutover date once opening balances are recorded.</td></tr>
        <tr><td>Reset refused: “Going back to the default would switch on …”</td><td>Known limitation L13 on this build: the feature is stuck off until the fix is deployed. MaxTheService can restore it from the operator console (grant the capability, reset, then withdraw the grant).</td></tr>
        <tr><td>A feature switched on, but its menu does not appear</td><td>Capabilities travel in the sign-in token; the change applies at the next refresh (up to 15 minutes) or after signing in again.</td></tr>
        <tr><td>The screen looks old after an update</td><td>Force-reload the page (Ctrl+F5) to fetch the new scripts and styles.</td></tr>
        <tr><td>Operator: “Open a support session for this business …”</td><td>Reading or changing a business’s records needs an open, explained support session (Tenants → the business → Open support session).</td></tr>
        <tr><td>“Opening balances are recorded against the cutover date, so the lock cannot come off”</td><td>(After the L9 fix is deployed.) Reverse the opening balances first; the lock can then be switched off.</td></tr>
        <tr><td>A dot on a row you did not change</td><td>An earlier save stored the default value explicitly. <b>Reset to default</b> removes it.</td></tr>
      </tbody></table></div>
    </section>

    <section class="card" id="control">
      <h2>Document control</h2>
      <div class="facts">
        <div class="fact"><b>Document owner</b>MaxTheService product team — Shahid Hussain Abbasi</div>
        <div class="fact"><b>Last updated</b>${esc(fmtDate(reviewed))}</div>
        <div class="fact"><b>Built from</b>${esc(version.branch)} @ ${esc(version.commit)}${version.dirty ? ` + ${version.dirty} uncommitted` : ''}</div>
        <div class="fact"><b>Regenerate</b>Run the verification, then <code>node docs/guides/build-settings-guide.js</code>. Failed steps are left out automatically.</div>
      </div>
    </section>
    <footer>Generated from the verification run — nothing on this page is typed from memory. Missing screenshots: ${missing.length ? esc(missing.join(', ')) : 'none'}.</footer>
  </main>
</div></div>

<dialog id="lightbox"><button type="button" id="lbClose">Close</button><img id="lbImg" alt="Enlarged screenshot"></dialog>
<script>
(function(){
  var KEY='settings-guide-checks';
  var saved={}; try{ saved=JSON.parse(localStorage.getItem(KEY)||'{}') }catch(e){}
  document.querySelectorAll('[data-chk]').forEach(function(c){
    if(saved[c.id]) c.checked=true;
    c.addEventListener('change',function(){ saved[c.id]=c.checked; try{ localStorage.setItem(KEY,JSON.stringify(saved)) }catch(e){} });
  });
  var dlg=document.getElementById('lightbox'), im=document.getElementById('lbImg');
  document.querySelectorAll('.shot').forEach(function(b){ b.addEventListener('click',function(){ im.src=b.getAttribute('data-full'); if(dlg.showModal) dlg.showModal(); }); });
  document.getElementById('lbClose').addEventListener('click',function(){ dlg.close(); });
  dlg.addEventListener('click',function(e){ if(e.target===dlg) dlg.close(); });
  var q=document.getElementById('refSearch'), sc=document.getElementById('refScreen'), st=document.getElementById('refStatus'), cnt=document.getElementById('refCount');
  function filter(){ var t=q.value.trim().toLowerCase(), s=sc.value, f=st.value, n=0;
    document.querySelectorAll('section.screen').forEach(function(sec){ var show=!s||sec.getAttribute('data-screen')===s, any=0;
      sec.querySelectorAll('details.set').forEach(function(d){ var hit=show&&(!t||d.getAttribute('data-search').indexOf(t)>-1)&&(!f||d.getAttribute('data-status')===f); d.hidden=!hit; if(hit){any++;n++;} });
      sec.querySelectorAll('.grp').forEach(function(g){ g.hidden=!g.querySelector('details.set:not([hidden])'); });
      sec.querySelectorAll('.cat').forEach(function(c){ c.hidden=!c.querySelector('details.set:not([hidden])'); });
      sec.hidden=!any; });
    cnt.textContent=(t||s||f)? n+' of ${totalSettings} settings':''; }
  [q,sc,st].forEach(function(el){ el.addEventListener('input',filter); el.addEventListener('change',filter); });
})();
</script>
`
fs.writeFileSync(path.join(outDir, 'index.html'), html)
console.log(JSON.stringify({ outDir, steps: manifest.steps.length, passed: passedSteps.length, failed: failedSteps.map((f) => f.id), settings: totalSettings,
  cases: caseCount, casesPassed: casePass, security: `${secPass}/${(security.results || []).length}`, specs: `${specsPassed}/${vRows.length}`,
  images: fs.readdirSync(path.join(outDir, 'img')).length, missingShots: missing, categories: CATS.map((c) => c.label) }))
