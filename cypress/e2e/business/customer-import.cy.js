/**
 * Slice I1 — CSV template + import, first entity: Customer.
 * Design: microservices/docs/slices/import-I1-customer-csv.md
 *
 * WHAT THIS SPEC IS FOR. The feature's whole promise is negative: a bad file changes nothing. So almost
 * every case below asserts a COUNT OR A VALUE IN THE MASTER, never that a response said SUCCESS. The
 * distinction is not pedantry — this programme has five recorded incidents of a green gate that asserted the
 * artefact (a record came back, a field was populated) while the property it stood for (money moved, a row
 * exists) was false. The central case here, "one bad row is refused and the customer count is unchanged",
 * passes trivially under a correct implementation and passes under NO partial-write bug, which is exactly
 * why it is the one that carries the slice.
 *
 * Run headed:
 *   npx cypress open --e2e        (pick business/customer-import.cy.js)
 *   npx cypress run  --spec cypress/e2e/business/customer-import.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

/** The report object travels in GenericResponse.object (lists go in `collection`; this is not a list). */
const report = (body) => (body && body.object) || null

const HEADERS =
  'name,contact,email,address,city,cnic,licenseNo,licenseExpiry,customerType,creditLimit,paymentTermsDays'

/** One CSV line with everything after `contact` blank. */
const row = (name, contact, rest = ',,,,,,,,') => `${name},${contact},${rest}`

const csvOf = (...rows) => [HEADERS, ...rows].join('\n') + '\n'

const customers = () => cy.request('/getUserCustomer?q=-1').then((r) => list(r.body))

const countCustomers = () => customers().then((cs) => cs.length)

const validate = (csv) =>
  cy.request({ method: 'POST', url: '/import/customer/validate', body: { csv }, failOnStatusCode: false })

const commit = (csv) =>
  cy.request({ method: 'POST', url: '/import/customer/commit', body: { csv }, failOnStatusCode: false })

describe('I1 — Customer CSV import', () => {
  beforeEach(() => {
    // testIsolation clears the session between tests, so the login belongs in beforeEach, not before.
    // Owner rather than demo.business: the import is ADMIN_PRIVILEGE-gated, and demo carries DEMO_ROLE=SUPER
    // which would make an authority assertion prove nothing.
    cy.loginAsOwner()
  })

  // ── the template, and the round-trip contract ─────────────────────────────────────────────────────────

  it('the template is offered, and its header is exactly the columns the parser accepts', () => {
    cy.request('/import/customer/template.csv').then((r) => {
      expect(r.status).to.eq(200)
      expect(r.headers['content-disposition'], 'served as a download').to.contain('attachment')

      const header = String(r.body).split(/\r?\n/)[0].trim()

      // THE round-trip contract: one list generates the template AND validates the upload, so the header
      // cannot drift from the parser. Asserting the exact string is the point — a "contains name" check
      // would pass while the order or the optional columns silently changed.
      expect(header).to.eq(HEADERS)
    })
  })

  it('the template offers no balance column — balances are documents, not cells', () => {
    cy.request('/import/customer/template.csv').then((r) => {
      const header = String(r.body).split(/\r?\n/)[0]
      // dueAmount is owned by recomputeDue and creditBalance by the store-credit ledger. A number typed
      // into a spreadsheet with no invoices behind it is how a master and its ledger start disagreeing.
      expect(header).to.not.contain('dueAmount')
      expect(header).to.not.contain('creditBalance')
      expect(header).to.not.contain('customerId')
    })
  })

  it('customer is offered as importable, and the entity list is what draws the buttons', () => {
    cy.request('/import/entities').then((r) => {
      const names = list(r.body).map((e) => e.entity)
      expect(names, 'the grid draws its buttons from this').to.include('customer')
      // Sell/Purchase/Orders are numbered documents whose creation moves stock and posts to the ledger.
      // A row inserted behind the sale path is a row the books disagree with — so no spec, no button.
      expect(names).to.not.include('sell')
      expect(names).to.not.include('purchase')
      expect(names).to.not.include('order')
    })
  })

  // ── the dry run writes nothing ────────────────────────────────────────────────────────────────────────

  it('a dry run reports what it WOULD create and creates nothing', () => {
    const run = uniq()
    const csv = csvOf(row(`Dry A ${run}`, `9${run}1`), row(`Dry B ${run}`, `9${run}2`))

    countCustomers().then((before) => {
      validate(csv).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const rep = report(r.body)
        expect(rep.toCreate).to.eq(2)
        expect(rep.committed, 'a dry run is never a commit').to.eq(false)

        // The property, not the report: nothing reached the master.
        countCustomers().then((after) => expect(after, 'a dry run must write nothing').to.eq(before))
      })
    })
  })

  // ── the happy path ────────────────────────────────────────────────────────────────────────────────────

  it('a clean file imports every row, with the values from the file', () => {
    const run = uniq()
    const nameA = `Import A ${run}`
    const contactA = `8${run}1`
    const csv = csvOf(
      `${nameA},${contactA},a@x.com,Main Bazaar,Lahore,35202-1,DL-9,2027-12-31,RETAILER,50000,30`,
      row(`Import B ${run}`, `8${run}2`))

    countCustomers().then((before) => {
      commit(csv).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        expect(report(r.body).toCreate).to.eq(2)

        countCustomers().then((after) =>
          expect(after, 'exactly the file, no more and no fewer').to.eq(before + 2))

        // Values, not just a row count — a row that arrives blank is not an import.
        customers().then((cs) => {
          const c = cs.find((x) => x.name === nameA)
          expect(c, 'the imported customer is readable').to.exist
          expect(c.contact).to.eq(contactA)
          expect(c.customerType, 'the trade channel survives the round trip').to.eq('RETAILER')
          expect(Number(c.creditLimit)).to.eq(50000)
        })
      })
    })
  })

  it('a blank customerType lands on WALK_IN, exactly as the registration screen does', () => {
    const run = uniq()
    const name = `Untyped ${run}`

    commit(csvOf(row(name, `7${run}1`))).then(() => {
      customers().then((cs) => {
        const c = cs.find((x) => x.name === name)
        expect(c, 'imported').to.exist
        // "No type" must mean the same thing however the row was created, or every downstream consumer
        // needs its own null rule — the reason V29 backfilled rather than leaving NULLs.
        expect(c.customerType).to.eq('WALK_IN')
      })
    })
  })

  // ── ⭐ THE CASE THAT CARRIES THE SLICE ─────────────────────────────────────────────────────────────────

  it('one bad row refuses the WHOLE file — the good rows are NOT written', () => {
    const run = uniq()
    // Two perfectly good rows around one missing its required contact.
    const csv = csvOf(
      row(`Good One ${run}`, `6${run}1`),
      row(`Bad Row ${run}`, ''),
      row(`Good Two ${run}`, `6${run}2`))

    countCustomers().then((before) => {
      commit(csv).then((r) => {
        expect(r.body.status, 'refused').to.not.eq('SUCCESS')
        expect(report(r.body).committed).to.eq(false)

        // THE assertion. "The response said FAILED" passes under a partial commit too — the only thing
        // that distinguishes all-or-nothing from most-of-it is the count.
        countCustomers().then((after) =>
          expect(after, 'not even the two valid rows may be written').to.eq(before))

        // And the good rows must be absent by name, not merely absent from a count that could coincide.
        customers().then((cs) => {
          expect(cs.find((x) => x.name === `Good One ${run}`), 'valid row 1 was not created').to.be.undefined
          expect(cs.find((x) => x.name === `Good Two ${run}`), 'valid row 3 was not created').to.be.undefined
        })
      })
    })
  })

  it('the refusal names the row number and the reason, so the operator can fix the file', () => {
    const run = uniq()
    const csv = csvOf(row(`Fine ${run}`, `5${run}1`), row(`Broken ${run}`, ''))

    validate(csv).then((r) => {
      const rows = report(r.body).rows
      const bad = rows.find((x) => x.status === 'ERROR')
      expect(bad, 'the bad row is reported').to.exist
      // Header is line 1, so the second data row is line 3 — the number the operator sees in their sheet.
      expect(bad.rowNumber, 'numbered as the spreadsheet numbers it').to.eq(3)
      expect(bad.message).to.contain('contact')
    })
  })

  // ── create-only is what makes a re-import safe ────────────────────────────────────────────────────────

  it('re-importing the same file creates nothing and reports every row as already present', () => {
    const run = uniq()
    const csv = csvOf(row(`Repeat A ${run}`, `4${run}1`), row(`Repeat B ${run}`, `4${run}2`))

    commit(csv).then((first) => {
      expect(report(first.body).toCreate).to.eq(2)

      countCustomers().then((afterFirst) => {
        commit(csv).then((second) => {
          const rep = report(second.body)
          expect(rep.toCreate, 'nothing new').to.eq(0)
          expect(rep.skipped, 'both already exist').to.eq(2)
          expect(rep.refused, 'already existing is not a failure').to.eq(0)

          countCustomers().then((afterSecond) =>
            expect(afterSecond, 'create-only: a replayed file is inert').to.eq(afterFirst))
        })
      })
    })
  })

  it('a contact repeated inside one file is created once and the repeat is reported', () => {
    const run = uniq()
    const contact = `3${run}1`
    const csv = csvOf(row(`Twice A ${run}`, contact), row(`Twice B ${run}`, contact))

    countCustomers().then((before) => {
      commit(csv).then((r) => {
        const rep = report(r.body)
        expect(rep.toCreate).to.eq(1)
        expect(rep.skipped, 'reported, never silently collapsed').to.eq(1)
        countCustomers().then((after) => expect(after).to.eq(before + 1))
      })
    })
  })

  // ── balances cannot enter through a spreadsheet ───────────────────────────────────────────────────────

  it('a file carrying a dueAmount column is refused whole, not quietly stripped', () => {
    const run = uniq()
    const csv = `name,contact,dueAmount\nBalance Smuggler ${run},2${run}1,5000\n`

    countCustomers().then((before) => {
      commit(csv).then((r) => {
        expect(r.body.status).to.not.eq('SUCCESS')
        const rep = report(r.body)
        // Ignoring the column would let an operator believe the balances went in. Being told is the only
        // honest answer available.
        expect(rep && rep.fileError, JSON.stringify(r.body)).to.contain('dueAmount')
        countCustomers().then((after) => expect(after).to.eq(before))
      })
    })
  })

  it('a text cell a spreadsheet would run as a formula is refused', () => {
    const run = uniq()
    const csv = csvOf(row(`=cmd|'/c calc'!A1`, `1${run}1`))

    validate(csv).then((r) => {
      const rep = report(r.body)
      expect(rep.refused).to.eq(1)
      expect(rep.rows[0].message).to.contain('formula')
    })
  })

  it('a negative number is NOT mistaken for a formula', () => {
    const run = uniq()
    // The guard is on TEXT columns only. Blanket neutralisation would have broken every negative figure.
    const csv = csvOf(row(`Negative ${run}`, `1${run}9`, ',,,,,,,-250,'))

    validate(csv).then((r) => {
      const rep = report(r.body)
      expect(rep.refused, JSON.stringify(rep.rows)).to.eq(0)
      expect(rep.toCreate).to.eq(1)
    })
  })

  // ── authority ─────────────────────────────────────────────────────────────────────────────────────────

  it('a non-admin is refused by the SERVER, not by a hidden button', () => {
    const run = uniq()
    // user.business carries neither ADMIN nor SUPER — demo.business would prove nothing (DEMO_ROLE=SUPER).
    cy.loginAsTier('user', 'business')

    countCustomers().then((before) => {
      commit(csvOf(row(`Sneaky ${run}`, `0${run}1`))).then((r) => {
        expect(r.body.status, 'the write is refused').to.not.eq('SUCCESS')
        countCustomers().then((after) => expect(after, 'nothing was written').to.eq(before))
      })
    })
  })

  // ── tenancy ───────────────────────────────────────────────────────────────────────────────────────────

  it('an imported customer belongs to the importing tenant only', () => {
    const run = uniq()
    const name = `Tenant Scoped ${run}`

    commit(csvOf(row(name, `11${run}`))).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')

      // POSITIVE CONTROL FIRST. An absence assertion is not evidence until the mechanism is shown live —
      // the D2 false pass went green against a 404, proving nothing about scoping at all.
      customers().then((mine) => {
        expect(mine.find((c) => c.name === name), 'the owner can read what they imported').to.exist
      })

      cy.asOtherTenant((headers) =>
        cy.request({
          method: 'GET',
          url: 'http://localhost:8765/api/business/getUserCustomer?q=-1',
          headers,
          failOnStatusCode: false,
        }).then((other) => {
          const theirs = list(other.body)
          expect(theirs.find((c) => c.name === name), 'another tenant cannot see it').to.be.undefined
        }))
    })
  })

  // ── the screen ────────────────────────────────────────────────────────────────────────────────────────

  it('the Customer grid offers both buttons, and the Vendor grid offers neither', () => {
    // POSITIVE CONTROL FIRST: prove the buttons can appear at all, so the absence assertion below is
    // evidence rather than a screen that simply never drew a toolbar.
    cy.openSection('CustomerDiv')
    cy.get('.btn-import-template', { timeout: 10000 }).should('exist')
    cy.get('.btn-import-csv').should('exist')

    // NEGATIVE CONTROL: Vendor, deliberately — NOT the sale screen.
    //
    // `#sellDiv` is the till, which builds no grid through loadDataTable, so "no import buttons" would be
    // true there for a reason that has nothing to do with the registry — an absence that proves nothing.
    // Vendor goes through the SAME loadDataTable with the SAME buttons array as Customer, so the only
    // variable between these two assertions is whether the server has an ImportSpec for the entity. It is
    // also a real master and a plausible import candidate that was scoped out on purpose (D-3), which is
    // what makes its emptiness a decision rather than an accident.
    cy.openSection('VenderDiv')
    cy.get('.btn-import-template').should('not.exist')
    cy.get('.btn-import-csv').should('not.exist')
  })

  it('the confirm button counts what it will CREATE, not the rows in the file', () => {
    const run = uniq()
    const existing = `Preexisting ${run}`
    const contact = `12${run}`

    // Seed one, then upload a file of three rows of which only two are new.
    commit(csvOf(row(existing, contact))).then(() => {
      const csv = csvOf(row(existing, contact), row(`New A ${run}`, `13${run}`), row(`New B ${run}`, `14${run}`))

      validate(csv).then((r) => {
        const rep = report(r.body)
        expect(rep.total, 'three rows were read').to.eq(3)
        expect(rep.skipped).to.eq(1)
        // The number the button must show. "Import 3" that creates 2 is how an operator concludes the
        // feature is broken — and it is a count, so a wrong one cannot hide.
        expect(rep.toCreate, 'the button says 2, not 3').to.eq(2)
      })
    })
  })
})
