/**
 * DRY D3 — the time and list helpers the H6 specs each wrote for themselves. Pure functions (no cy.*): import them.
 *
 *   import { localText, localDay, rowsOf, serverTextToMs, msToServerText } from '../../support/time'
 *
 * Every expectation is computed HERE with Intl, independently of the page's own formatter (datetime.js) — a gate that
 * asked the page how to format would pass on the page's own defect.
 */

/** "yyyy-MM-dd HH:mm" of a server instant (zone-less = UTC, or with Z/offset) in `zone`. */
export function localText(raw, zone) {
  let iso = String(raw).replace(' ', 'T')
  if (!/([zZ]|[+-]\d{2}:?\d{2})$/.test(iso)) iso = iso.replace(/(\.\d+)?$/, '') + 'Z'
  const p = {}
  new Intl.DateTimeFormat('en-GB', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
    .formatToParts(new Date(iso)).forEach((x) => { p[x.type] = x.value })
  return `${p.year}-${p.month}-${p.day} ${p.hour === '24' ? '00' : p.hour}:${p.minute}`
}

/** "dd-MM-yyyy HH:mm:ss" (server-formatted text) → epoch ms, read as UTC (compare differences only). */
export function serverTextToMs(s) {
  const m = /^(\d{2})-(\d{2})-(\d{4}) (\d{2}):(\d{2}):(\d{2})$/.exec(String(s || ''))
  if (!m) throw new Error(`not a server-formatted date-time: ${s}`)
  return Date.UTC(+m[3], +m[2] - 1, +m[1], +m[4], +m[5], +m[6])
}

/** epoch ms (as-if-UTC) → "dd-MM-yyyy HH:mm:ss". */
export function msToServerText(ms) {
  const d = new Date(ms), p = (n) => String(n).padStart(2, '0')
  return `${p(d.getUTCDate())}-${p(d.getUTCMonth() + 1)}-${d.getUTCFullYear()} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`
}

/** The calendar day "yyyy-MM-dd" in `zone` of a server-formatted UTC text "dd-MM-yyyy HH:mm:ss". */
export function localDay(serverUtcText, zone) {
  return new Date(serverTextToMs(serverUtcText)).toLocaleDateString('sv-SE', { timeZone: zone })   // sv-SE = yyyy-MM-dd
}

/** The list inside any of the app's response envelopes (collection / data / object / data.content / data.items). */
export function rowsOf(b) {
  for (const c of [b && b.collection, b && b.data, b && b.object, b && b.rows, b && b.data && b.data.content, b && b.data && b.data.items]) {
    if (Array.isArray(c)) return c
  }
  return []
}
