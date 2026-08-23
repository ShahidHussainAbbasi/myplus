/**
 * A supplier can represent more than one brand.
 *
 * THE LIMITATION BEING REMOVED.
 *
 * `vender.company_id` was a single foreign key, so "Shahzad Mobile Shop" could be registered as the Nokia
 * distributor or the Samsung one — never both. The shop's workaround was to create the same supplier twice,
 * which splits their payables across two rows and makes the statement understate what is owed to one business.
 *
 * WHAT CARRIES THIS SPEC IS THE ROUND TRIP, NOT THE SAVE.
 *
 * Saving two brands is the easy half. Reopening the supplier and getting both back is where this breaks, and
 * it very nearly did: `editRecord` already split the grid cell on commas and looped the labels, so it LOOKED
 * multi-select aware — but the per-option branch set `selected = false` on every non-match, so each label
 * deselected everything the one before it had selected. Only the last would have survived, and the operator
 * would have silently lost a brand every time they edited anything else on the form.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/vendor-multi-company.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const mobile = () => `03${Math.floor(100000000 + Math.random() * 899999999)}`

const list = (body) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
  return []
}

/** A company with a known name, so assertions can name it rather than match "some company". */
const makeCompany = (label) =>
  cy.request({ method: 'POST', url: '/addCompany', form: true, failOnStatusCode: false,
    body: { name: label, phone: '042-1234567', email: `c${uniq()}@t.com`, address: 'Lahore' } })
    .then((r) => {
      expect(r.body.status, `addCompany ${label}: ${JSON.stringify(r.body)}`).to.be.oneOf(['SUCCESS', 'FOUND'])
      return cy.request('/getUserCompany')
    })
    .then((r) => {
      const made = list(r.body).find((c) => c.name === label)
      // Assert the fixture, loudly. A company that silently failed to exist would make every assertion
      // below pass or fail for a reason that has nothing to do with the feature.
      expect(made, `company ${label} exists`).to.exist
      return cy.wrap(made.id)
    })

const vendorNamed = (name) =>
  cy.request('/getUserVender?q=-1').then((r) => list(r.body).find((v) => v.name === name))

describe('A supplier can represent several brands', () => {
  beforeEach(() => cy.loginAsOwner())

  // ── ⭐ THE CASE THAT CARRIES THE SLICE ─────────────────────────────────────────────────────────────────

  it('one supplier, two brands — saved and read back as both', () => {
    const run = uniq()
    const nokia = `Nokia_${run}`
    const samsung = `Samsung_${run}`
    const supplier = `Shahzad Mobile Shop ${run}`

    makeCompany(nokia).then((nokiaId) => {
      makeCompany(samsung).then((samsungId) => {
        cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
          body: { name: supplier, companyIds: `${nokiaId},${samsungId}`, mobile: mobile(),
            email: `v${run}@t.com` } })
          .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

        vendorNamed(supplier).then((v) => {
          expect(v, 'the supplier exists').to.exist

          // BOTH ids come back, which is what the form re-selects from.
          const ids = String(v.companyIds).split(',').map(Number)
          expect(ids, 'both brands are stored').to.have.members([Number(nokiaId), Number(samsungId)])

          // And both names, which is what the grid shows.
          expect(v.companyNames).to.contain(nokia)
          expect(v.companyNames).to.contain(samsung)
        })
      })
    })
  })

  it('editing REPLACES the set, so a brand can be dropped', () => {
    // Merge would be the easy implementation and the wrong one: the form shows the current set and the
    // operator edits it, so an unticked brand means "no longer represents them". If saving merged, removal
    // would be impossible from the only screen that offers it.
    const run = uniq()
    const supplier = `Drop Brand ${run}`

    makeCompany(`Keep_${run}`).then((keepId) => {
      makeCompany(`Drop_${run}`).then((dropId) => {
        cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
          body: { name: supplier, companyIds: `${keepId},${dropId}`, mobile: mobile() } })
          .then((r) => expect(r.body.status).to.eq('SUCCESS'))

        vendorNamed(supplier).then((v) => {
          // POSITIVE CONTROL: two before, so one afterwards means REPLACED rather than never-saved.
          expect(String(v.companyIds).split(',')).to.have.length(2)

          cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
            body: { id: v.id, name: supplier, companyIds: `${keepId}`, mobile: v.mobile } })
            .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

          vendorNamed(supplier).then((after) => {
            expect(String(after.companyIds).split(',').map(Number), 'only the kept brand remains')
              .to.deep.eq([Number(keepId)])
            expect(after.companyNames).to.not.contain(`Drop_${run}`)
          })
        })
      })
    })
  })

  it('a supplier representing nobody is refused', () => {
    // The form marks the field required; the server has to agree. A supplier with no brand is invisible on
    // every screen that groups by brand, so accepting one creates a record nobody can find.
    cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
      body: { name: `No Brand ${uniq()}`, companyIds: '', mobile: mobile() } })
      .then((r) => {
        expect(r.body.status).to.eq('FAILED')
        expect(r.body.message.toLowerCase()).to.contain('at least one')
      })
  })

  it('the original single-brand call still works', () => {
    // Widening an endpoint must not break the callers it already has: four specs and any integration outside
    // this repo post `companyId` (singular). Without this case, that regression ships silently.
    const run = uniq()
    const supplier = `Legacy Caller ${run}`

    makeCompany(`Legacy_${run}`).then((companyId) => {
      cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
        body: { name: supplier, companyId, mobile: mobile() } })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      vendorNamed(supplier).then((v) => {
        expect(String(v.companyIds).split(',').map(Number)).to.deep.eq([Number(companyId)])
        expect(v.companyNames).to.contain(`Legacy_${run}`)
      })
    })
  })

  // ── ⭐ the screen — where the round trip actually breaks ───────────────────────────────────────────────

  it('⭐ the form shows both brands ticked when the supplier is reopened', () => {
    // THE REGRESSION GUARD. Everything above passes through the API and would still have passed while the
    // operator lost a brand on every edit, because the losing happened in the browser.
    const run = uniq()
    const a = `Alpha_${run}`
    const b = `Beta_${run}`
    const supplier = `Reopen Me ${run}`

    makeCompany(a).then((aId) => {
      makeCompany(b).then((bId) => {
        cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
          body: { name: supplier, companyIds: `${aId},${bId}`, mobile: mobile() } })
          .then((r) => expect(r.body.status).to.eq('SUCCESS'))

        cy.visit('/businessDashboard')
        cy.waitForAppReady()
        // The nav select the sidebar drives, matching how every other spec opens a registration section.
        cy.get('#registrationType', { timeout: 15000 }).select('VenderDiv', { force: true })

        // Find the supplier's row and open it for edit.
        cy.contains('#tableVender tbody tr', supplier, { timeout: 15000 }).should('exist')
        cy.contains('#tableVender tbody tr', supplier).within(() => {
          // The grid itself must show both — this is the cell editRecord reads back.
          cy.contains(a).should('exist')
          cy.contains(b).should('exist')
        })
        // ⚠ WAIT FOR THE PICKER BEFORE OPENING THE RECORD, not after.
        //
        // The company options arrive by AJAX when the section opens (loadUserCompanies, fired from the grid's
        // success handler). editRecord() can only tick options that EXIST, so clicking the row while that call
        // is still in flight silently selects nothing — and the assertion then reads [] and looks like the
        // brands were never saved, which is a different defect entirely.
        //
        // This is also what a person does: they wait for the dropdown to fill before touching the row.
        cy.get('#venderCompanyDD option', { timeout: 15000 }).should('have.length.greaterThan', 1)

        // The per-row EDIT BUTTON, not the row. On register screens with a modal the app deliberately ignores
        // a stray row click and opens the record only through this button — "explicit-edit UX" in main.js.
        // Clicking the row does nothing at all, which reads as an empty form rather than as a refused action.
        cy.contains('#tableVender tbody tr', supplier).find('.js-edit-row').click({ force: true })

        // The record is genuinely open before anything is asserted about the select. Without this, an empty
        // select is ambiguous — "the brands did not come back" and "the row click never opened the record"
        // are different defects with different fixes.
        cy.get('#venderName', { timeout: 10000 }).should('have.value', supplier)

        // BOTH options selected, not just the last one. Before the editRecord fix this returned one value.
        // Two separate chains on purpose: `.should('have.attr', ...)` YIELDS THE ATTRIBUTE VALUE as the new
        // subject, so chaining `.invoke('val')` onto it runs against a string and fails with "the property
        // val does not exist on your subject" — which reads like a broken element rather than a broken chain.
        cy.get('#venderCompanyDD', { timeout: 10000 }).should('have.attr', 'multiple')

        cy.get('#venderCompanyDD').invoke('val').then((vals) => {
          expect(vals, 'both brands come back ticked').to.have.members([String(aId), String(bId)])
        })
      })
    })
  })
})
