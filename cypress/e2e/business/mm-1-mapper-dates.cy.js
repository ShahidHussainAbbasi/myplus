/**
 * MM-1 — every business screen gets its dates in ONE format, whichever screen was opened first after a deploy.
 *
 * Design: microservices/docs/slices/mm-1-mapper-profiles.md.
 * The defect: controllers added ModelMapper's date converters inside request handlers, so e.g. "all purchases" printed
 * 2026-10-04T10:20:30 if it was the first purchase call after a restart, and 04-10-2026 10:20:30 afterwards.
 *
 * <h3>Where the red run lives</h3>
 * A cold-start red needs the OLD service freshly restarted, so the deterministic proof is the unit characterization
 * MapperProfilesTest#coldWasDifferent (old cold ≠ old warm = new). This gate guards the screens: every endpoint the
 * profiles serve answers dd-MM-yyyy[ HH:mm:ss], never ISO, and asking in a different ORDER changes nothing.
 */

const DISPLAY = /^\d{2}-\d{2}-\d{4}( \d{2}:\d{2}(:\d{2})?)?$/

const rowsOf = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return [] }

/** Every non-empty `dated` the endpoint returns; at least one, or the case would test nothing. */
const datesOf = (url) => cy.request(url).then((r) => {
  const ds = rowsOf(r.body).map((x) => x && x.dated).filter((d) => d != null && d !== '')
  expect(ds.length, `${url} returns rows with a date to check`).to.be.greaterThan(0)
  return ds
})

// Every endpoint the profiles serve. /getAllPurchase and /getAllSell returned the raw ENTITIES (ISO dates) until MM-2
// — found by this gate's first run; they now return the mapped DTOs like the rest.
const ENDPOINTS = ['/getAllPurchase', '/getUserPurchase', '/getAllSell', '/getUserSell', '/getUserCustomer?q=-1']

describe('MM-1 — one date format on every business screen, in any order', () => {
  beforeEach(() => cy.loginAsOwner())

  ENDPOINTS.forEach((url) => {
    it(`${url} — dates are dd-MM-yyyy[ HH:mm:ss], never ISO`, () => {
      datesOf(url).then((ds) => {
        const bad = ds.filter((d) => !DISPLAY.test(String(d)))
        expect(bad, `${url}: dates not in the display format`).to.deep.eq([])
      })
    })
  })

  it('asking in the reverse order gives the same answers (no per-request mapper state)', () => {
    const first = {}
    cy.wrap(ENDPOINTS).each((u) => datesOf(u).then((ds) => { first[u] = ds.slice(0, 5) }))
    cy.wrap(ENDPOINTS.slice().reverse()).each((u) => datesOf(u).then((ds) => {
      expect(ds.slice(0, 5), `${u} the same either way`).to.deep.eq(first[u])
    }))
  })
})
