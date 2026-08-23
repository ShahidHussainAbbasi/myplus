/**
 * INST-3a — the due-date scanner and the collections worklist. Requirement R4, "remind".
 * Design: microservices/docs/slices/inst-3a-reminder-scanner.md
 *
 * WHY THE FIRST CASE IS ABOUT TENANCY AND NOT ABOUT REMINDERS.
 *
 * The scanner is the first thing in this service that reads ACROSS tenants. It has to: a @Scheduled thread has
 * no authenticated user, so it cannot ask "which org am I?" — it enumerates tenants and stamps each reminder
 * from the plan it read. Every other query in business-service is scoped through a user that, here, does not
 * exist.
 *
 * That licence has to stop dead at the scanner. If it leaks into the worklist read, one shop is handed another
 * shop's debtor list — names, phone numbers and what they owe. Nothing would error and no other case here would
 * fail. So it is asserted first, with a positive control, rather than assumed because the query says findScoped.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/installment-reminders.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
  return []
}

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

/** ISO date n months out, from LOCAL components — toISOString() is UTC and shifts the day at +05:00. */
const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const scan = () =>
  cy.request({ method: 'POST', url: '/scanInstallmentReminders', failOnStatusCode: false })
    .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

const worklist = (stage) =>
  cy.request(`/installmentReminders${stage ? `?stage=${stage}` : ''}`).then((r) => list(r.body))

/** Sell one handset on a plan whose first payment fell due `monthsAgo` months back. */
const sellOnPlan = (buyer, monthsAgo, count = 6, price = 60000) => {
  const run = uniq()
  return cy.seedProduct({ name: `RMD_${run}`, sellingPrice: price, stock: 5 }).then(({ productId }) =>
    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: buyer, contact: `0300R${run}`, paidAmount: 0, dueAmount: 0 },
        sales: [{ productId, quantity: 1, sellRate: price, totalAmount: price, netAmount: price }],
        paidAmount: 0, dueAmount: 0, grandTotal: price,
        installmentPlan: {
          cashPrice: price, downPayment: 0, installmentCount: count,
          frequency: 'monthly', firstDueDate: monthsOut(monthsAgo), assetRef: `IMEI${run}`,
        },
      }, failOnStatusCode: false,
    }).then((r) => {
      // Assert the fixture, loudly. A plan that silently failed to exist would make every assertion below
      // pass or fail for a reason that has nothing to do with the scanner.
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      expect(r.body.message, 'the fixture plan exists').to.contain('PLN-')
      return cy.wrap(`0300R${run}`)
    }))
}

describe('INST-3a — the collections worklist', () => {
  before(() => {
    cy.loginAsOwner()
    setConfig('pos.installment.enabled', 'true')
    setConfig('pos.installment.remind.enabled', 'true')
  })

  beforeEach(() => {
    cy.loginAsOwner()
  })

  after(() => {
    // Leave no server state behind — both settings change behaviour for every later spec.
    cy.loginAsOwner()
    setConfig('pos.installment.remind.enabled', 'false')
    setConfig('pos.installment.enabled', 'false')
  })

  // ── ⭐ THE CASE THAT CARRIES THE SLICE ─────────────────────────────────────────────────────────────────

  it('the worklist shows THIS tenant only — the scanner sweeps every tenant, the read does not', () => {
    const mine = `Chase Mine ${uniq()}`

    sellOnPlan(mine, -3).then(() => {
      scan()

      worklist().then((rows) => {
        // POSITIVE CONTROL. Without it, "no other tenant's rows" is satisfied by an empty worklist, and the
        // case would pass just as happily against a scanner that recorded nothing at all.
        const ours = rows.filter((r) => r.customerName === mine)
        expect(ours.length, 'our own overdue customer IS on the list').to.be.greaterThan(0)

        // The real assertion. The scan that produced these rows enumerated EVERY tenant; the read must not.
        // A single row belonging elsewhere is a debtor list handed to a competitor.
        cy.request('/getUserCustomer?q=-1').then((cr) => {
          const visible = new Set(list(cr.body).map((c) => c.name))
          const foreign = rows.filter((r) => r.customerName && !visible.has(r.customerName))
          expect(foreign.map((r) => r.customerName), 'nobody from another tenant').to.deep.eq([])
        })
      })
    })
  })

  // ── the scanner ───────────────────────────────────────────────────────────────────────────────────────

  it('a plan that is not yet due is not chased', () => {
    // The negative control for the whole feature. Without it, "the overdue customer appears" is satisfied by
    // a scanner that lists every instalment of every plan the day it is sold — which is a worklist nobody
    // reads by the end of the first week.
    const future = `Chase Future ${uniq()}`

    sellOnPlan(future, 6).then(() => {
      scan()
      worklist().then((rows) => {
        expect(rows.filter((r) => r.customerName === future).length,
          'a payment due in six months is not on the list').to.eq(0)
      })
    })
  })

  it('scanning twice does not chase the same person twice', () => {
    // The scanner is a timer: it runs again in fifteen minutes, after a restart, and twice at once during a
    // rolling deploy. Correctness comes from the UNIQUE dedupe_key, not from the scanner remembering.
    const twice = `Chase Twice ${uniq()}`

    sellOnPlan(twice, -3).then(() => {
      scan()
      worklist().then((first) => {
        const before = first.filter((r) => r.customerName === twice).length
        expect(before, 'on the list after the first scan').to.be.greaterThan(0)

        scan()
        scan()

        worklist().then((after) => {
          const now = after.filter((r) => r.customerName === twice).length
          expect(now, 'three scans, still the same rows').to.eq(before)
        })
      })
    })
  })

  it('a late payment and a payment falling due are told apart', () => {
    const late = `Chase Late ${uniq()}`

    sellOnPlan(late, -3).then(() => {
      scan()

      worklist('OVERDUE').then((rows) => {
        const ours = rows.filter((r) => r.customerName === late)
        expect(ours.length, 'the late instalments are OVERDUE').to.be.greaterThan(0)
        ours.forEach((r) => {
          expect(r.stage).to.eq('OVERDUE')
          expect(r.daysOverdue, 'and are actually in the past').to.be.greaterThan(0)
          // The number the shopkeeper rings. A chase list without one sends them to another screen per row.
          expect(r.contact, 'with a phone number on the row').to.be.ok
          // The LIVE balance of that instalment, not the plan's whole outstanding.
          expect(Number(r.amountDue)).to.eq(10000)
        })
      })

      // The filter distinguishes rather than just narrowing: nothing due-soon is hiding in the late list.
      worklist('DUE_SOON').then((rows) => {
        rows.forEach((r) => expect(r.stage).to.eq('DUE_SOON'))
      })
    })
  })

  it('a settled instalment is not chased', () => {
    const paid = `Chase Paid ${uniq()}`

    sellOnPlan(paid, -3).then(() => {
      scan()
      worklist().then((before) => {
        const n = before.filter((r) => r.customerName === paid).length
        expect(n, 'on the list while owing').to.be.greaterThan(0)

        cy.request('/getUserCustomer?q=-1').then((r) => {
          const c = list(r.body).find((x) => x.name === paid)
          // Clear the whole plan. Nothing further can legitimately be chased.
          cy.request({ method: 'POST', url: '/receivePayment', form: true,
            body: { customerId: c.customerId || c.id, amount: 60000, method: 'CASH' },
            failOnStatusCode: false })
            .then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))

          // Rows already recorded stay — they are a record of a real chase — but no NEW ones appear.
          scan()
          worklist().then((after) => {
            expect(after.filter((r) => r.customerName === paid).length,
              'a paid-off plan generates no further chases').to.eq(n)
          })
        })
      })
    })
  })

  // ── ⭐ the half that makes it collections rather than a list ───────────────────────────────────────────

  it('recording a call survives, and the row stops looking outstanding', () => {
    // Without this the shop rings the same customer three times and never rings another — which is the whole
    // reason this is a stored record and not a derived "who is overdue" query.
    const called = `Chase Called ${uniq()}`

    sellOnPlan(called, -3).then(() => {
      scan()
      worklist().then((rows) => {
        const row = rows.find((r) => r.customerName === called)
        expect(row, 'there is someone to ring').to.exist
        expect(row.actioned, 'nobody has rung them yet').to.eq(false)

        cy.request({ method: 'POST', url: '/installmentReminderAction', form: true,
          body: { id: row.id, outcome: 'CALLED', note: 'promised Friday' }, failOnStatusCode: false })
          .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

        worklist().then((after) => {
          const again = after.find((r) => r.id === row.id)
          expect(again.actioned, 'the call is remembered').to.eq(true)
          expect(again.outcome).to.eq('CALLED')
          expect(again.note, 'including what they said').to.eq('promised Friday')
          expect(again.actedAt).to.be.ok
        })
      })
    })
  })

  it('a reminder belonging to nobody cannot be updated', () => {
    // Anti-IDOR. The id arrives off the wire, so the write must prove ownership IN the query rather than
    // after it. A guessable number is how one tenant annotates another's records.
    cy.request({ method: 'POST', url: '/installmentReminderAction', form: true,
      body: { id: 999999999, outcome: 'CALLED', note: 'should not stick' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.status, 'refused').to.eq('FAILED')
      })
  })

  // ── the screen ────────────────────────────────────────────────────────────────────────────────────────

  it('the shopkeeper can actually reach it', () => {
    // A capability the UI cannot reach is review finding R7, which this programme has now hit three times —
    // each one a feature built, tested, and reachable by nobody.
    const onScreen = `Chase Screen ${uniq()}`

    sellOnPlan(onScreen, -3).then(() => {
      scan()

      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.window().then((w) => w.showInstallments())

      // Opens on the plans tab, whatever was used last.
      cy.get('#InstallmentCollections').should('not.be.visible')

      cy.get('#instTabCollections').click({ force: true })
      cy.get('#InstallmentCollections', { timeout: 10000 }).should('be.visible')

      cy.contains('#instChaseBody tr', onScreen, { timeout: 10000 }).should('exist')
      cy.contains('#instChaseBody tr', onScreen).within(() => {
        cy.get('td').eq(1).invoke('text').should('match', /\d/)   // the number to ring
      })

      // And back again, without the empty message appearing over a table full of plans.
      cy.get('#instTabPlans').click({ force: true })
      cy.get('#InstallmentCollections').should('not.be.visible')
      cy.get('#installmentBody tr', { timeout: 10000 }).should('have.length.greaterThan', 0)
      cy.get('#installmentEmpty').should('not.be.visible')
    })
  })

  it('a shop that has not asked for this gets no worklist', () => {
    // A default is not a decision. The grocery on the same code must see its screens unchanged.
    setConfig('pos.installment.remind.enabled', 'false')

    const off = `Chase Off ${uniq()}`
    sellOnPlan(off, -3).then(() => {
      scan()
      worklist().then((rows) => {
        expect(rows.filter((r) => r.customerName === off).length,
          'the scanner is inert for a tenant that has not switched it on').to.eq(0)
      })

      // POSITIVE CONTROL: the same customer, the same scan, with the setting on. Without this the case
      // above is satisfied by a scanner that never records anything for anyone.
      setConfig('pos.installment.remind.enabled', 'true')
      scan()
      worklist().then((rows) => {
        expect(rows.filter((r) => r.customerName === off).length,
          'and fires as soon as it is').to.be.greaterThan(0)
      })
    })
  })
})
