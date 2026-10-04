/**
 * TZ-2 — "today" is the CLIENT's day, not the UTC server's.
 *
 * Design: microservices/docs/slices/tz-1-business-time-zone.md §TZ-2.
 * The defect: at 02:38 in Karachi an expense dated today was refused, "An expense cannot be dated in the future",
 * because every container runs on UTC and LocalDate.now() there was still yesterday.
 *
 * <h3>Deterministic at ANY hour</h3>
 * The run picks a zone whose date differs from UTC's date right now:
 *   UTC hour ≥ 10 → Pacific/Kiritimati (UTC+14): its today is UTC's TOMORROW. The old server refused it — the defect.
 *   UTC hour < 10 → Pacific/Pago_Pago (UTC−11): its today is UTC's YESTERDAY, so the old server ACCEPTED the
 *                   client's tomorrow (= UTC today). The fixed server refuses it.
 * Either way, a server still deciding in UTC fails case 1.
 *
 * <h3>⚠ Server state, all restored in after()</h3>
 * owner.business: Expense management back to its seeded default; every voucher this spec made is VOIDED.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = 'owner.business@myplus.com'
const USER = 'user.business@myplus.com'
const CAP = 'expenseManagement'
const KARACHI = 'Asia/Karachi'

const AHEAD = new Date().getUTCHours() >= 10
const ZONE = AHEAD ? 'Pacific/Kiritimati' : 'Pacific/Pago_Pago'

/** yyyy-MM-dd of an instant in a zone (en-CA formats as ISO). */
const dayIn = (zone, at = Date.now()) =>
  new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(at))
const plusDays = (iso, n) => { const d = new Date(iso + 'T12:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10) }

const token = (email) =>
  cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, zone, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json',
  ...(zone ? { 'X-Client-Tz': zone } : {}), ...extra })
const key = () => `tz2-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

const made = []   // [token-owner email, voucher id] — voided in after()

describe('TZ-2 — the server decides "today" in the browser\'s zone', () => {
  let cat = null

  const record = (t, zone, body) =>
    cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(t, zone, { 'Idempotency-Key': key() }),
      body: { paidFrom: 'CASH', lines: [{ categoryId: cat.id, amount: 7 }], payeeName: 'TZ-2 probe', ...body }, failOnStatusCode: false })
      .then((r) => { if (r.body && r.body.success && r.body.data) made.push(r.body.data.id); return r })

  before(() => {
    expect(dayIn(ZONE), `precondition: ${ZONE}'s day differs from UTC's`).to.not.eq(dayIn('UTC'))
    cy.loginAsOwner(undefined, undefined, 'tz2-' + Date.now())
    cy.setCapability(CAP, true)
    token(OWNER).then((t) =>
      cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).then((r) => {
        cat = (r.body.data || []).find((c) => c.active !== false && /rent/i.test(c.name)) || (r.body.data || []).find((c) => c.active !== false)
        expect(cat, 'an active expense category').to.exist
      }))
  })

  after(() => {
    token(OWNER).then((t) => {
      made.forEach((id) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`, headers: hdr(t),
        body: { reason: 'TZ-2 gate cleanup' }, failOnStatusCode: false }))
    })
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it(`1 — dated the client's today is accepted; the client's tomorrow is refused (${ZONE}, ${AHEAD ? 'the reported defect' : 'the reverse'})`, () => {
    const today = dayIn(ZONE)
    token(OWNER).then((t) => {
      record(t, ZONE, { voucherDate: today }).then((r) => {
        expect(r.body.success, `client's today ${today}: ${JSON.stringify(r.body.message)}`).to.eq(true)
        expect(r.body.data.voucherDate).to.eq(today)
      })
      record(t, ZONE, { voucherDate: plusDays(today, 1) }).then((r) => {
        expect(r.body.success, `client's tomorrow ${plusDays(today, 1)}`).to.eq(false)
        expect(r.body.message).to.match(/future/i)
      })
    })
  })

  it('2 — an undated expense is dated the client\'s today — owner and user alike', () => {
    ;[OWNER, USER].forEach((who) => token(who).then((t) =>
      record(t, ZONE, {}).then((r) => {
        expect(r.body.success, `${who}: ${JSON.stringify(r.body.message)}`).to.eq(true)
        expect(r.body.data.voucherDate, who).to.eq(dayIn(ZONE))
      })))
  })

  // ⚠ Case 3 separates Karachi from UTC only between 19:00 and 24:00 UTC (the red run at 02:09 UTC passed it on the
  // OLD server). It guards the fallback, not the defect; TenantClockTest proves the fallback at a fixed instant.
  it('3 — no zone, or one that is not a zone, falls back to Asia/Karachi — never a guess', () => {
    token(OWNER).then((t) => {
      ;[null, 'Mars/Olympus', '<b>'].forEach((z) => record(t, z, {}).then((r) => {
        expect(r.body.success, `zone ${z}`).to.eq(true)
        expect(r.body.data.voucherDate, `zone ${z}`).to.eq(dayIn(KARACHI))
      }))
    })
  })

  it('4 — through the screen\'s own door: the monolith turns the myplus_tz cookie into X-Client-Tz', () => {
    cy.loginAsOwner(undefined, undefined, 'tz2-' + Date.now())
    cy.setCookie('myplus_tz', encodeURIComponent(ZONE))
    const today = dayIn(ZONE)
    cy.request({ method: 'POST', url: '/expense/vouchers?post=true', headers: { 'Idempotency-Key': key() },
      body: { paidFrom: 'CASH', voucherDate: today, lines: [{ categoryId: cat.id, amount: 7 }], payeeName: 'TZ-2 cookie' } })
      .its('body').then((b) => {
        expect(b.success, JSON.stringify(b.message)).to.eq(true)
        made.push(b.data.id)
        expect(b.data.voucherDate).to.eq(today)
      })
    cy.request({ method: 'POST', url: '/expense/vouchers?post=true', headers: { 'Idempotency-Key': key() }, failOnStatusCode: false,
      body: { paidFrom: 'CASH', voucherDate: plusDays(today, 1), lines: [{ categoryId: cat.id, amount: 7 }] } })
      .its('body').then((b) => { expect(b.success).to.eq(false); expect(b.message).to.match(/future/i) })
  })

  it('5 — the browser reports its own zone, and fills "today" from LOCAL components (02:30 in Karachi = yesterday in UTC)', () => {
    cy.loginAsOwner(undefined, undefined, 'tz2-' + Date.now())
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((win) => {
      const zone = win.Intl.DateTimeFormat().resolvedOptions().timeZone
      cy.getCookie('myplus_tz').its('value').should('eq', encodeURIComponent(zone))
      // 02:30 on 5 Oct in Karachi; in the browser's own zone whatever that is — the screen must show THAT day
      const night = Date.parse('2026-10-04T21:30:00Z')
      const local = dayIn(zone, night)
      cy.clock(night, ['Date'])
      cy.window().then((w) => {
        w.openReceivePayment(1, 'TZ-2 probe', 0)
        cy.get('#rcvDate').should('have.value', local)
        w.closeModal && w.closeModal('ReceivePaymentModal')
        w.openPayVendor(1, 'TZ-2 probe', 0)
        cy.get('#pvDate').should('have.value', local)
        w.closeModal && w.closeModal('PayVendorModal')
        expect(w.finToday(), 'finance report "to" default').to.eq(local)
        expect(w.finMonthStart(), 'finance report "from" default — was the LAST day of the previous month east of UTC')
          .to.eq(local.slice(0, 8) + '01')
      })
    })
  })

  it('6 — the books: the trial balance, read in the client\'s zone, holds every client-dated journal and balances', () => {
    token(OWNER).then((t) => {
      const tb = () => cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t, ZONE) }).its('body')
      const cash = (b) => (b.rows || []).filter((r) => r.code === '1000').reduce((s, r) => s + Number(r.debit || 0) - Number(r.credit || 0), 0)
      tb().then((before) => {
        expect(before.balanced, 'balanced before').to.eq(true)
        record(t, ZONE, { voucherDate: dayIn(ZONE) }).then((r) => {
          expect(r.body.success).to.eq(true)
          const settle = (n = 25) => tb().then((after) => {
            const moved = Math.round((cash(after) - cash(before)) * 100) / 100
            if (moved !== -7 && n > 0) { cy.wait(1000); return settle(n - 1) }
            expect(after.balanced, 'balanced after').to.eq(true)
            expect(moved, 'Cash 1000 down by the expense, in the client-dated period').to.eq(-7)
          })
          settle()
        })
      })
    })
  })
})
