/**
 * V62 — two tills cannot silently overwrite each other's edits.
 *
 * ── What happened before ────────────────────────────────────────────────────────────────────────
 * Two cashiers open the same customer. The first corrects the phone number and saves. The second, holding a
 * copy loaded before that, saves an address change — and the OLD phone number goes back with it. Nothing
 * errored, nothing logged, and both screens looked correct until somebody reloaded.
 *
 * Last-write-wins, with no way to know a write had been lost. That was live behaviour, not a new risk —
 * but PERF-13 made saving fast and non-blocking, which widens exactly the window people work in.
 *
 * ── Why a version column and not `updated` ──────────────────────────────────────────────────────
 * `updated` is set BY the save being validated, has second fidelity in places, and two writes inside one
 * second compare equal. A counter JPA owns has none of those ambiguities: Hibernate puts it in the UPDATE's
 * WHERE clause, and the row count tells it whether anyone got there first.
 *
 * ⚠ Every case here drives the SERVER contract. A UI-only check would pass while two API clients still
 * overwrote each other, which is the failure being fixed.
 */

const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)

const save = (body) =>
  cy.request({ method: 'POST', url: '/addCustomer', form: true, body, failOnStatusCode: false })

const savedOf = (r) => (r.body && (r.body.object || r.body.data)) || null

describe('V62 — concurrent edits on one customer', () => {
  beforeEach(() => cy.loginAsOwner())

  it('⭐ 1 — a saved customer carries a version, and it increments on each edit', () => {
    /*
     * The precondition. Without a version on the response there is nothing for a second till to be stale
     * against, and every case below would pass vacuously.
     */
    const run = uniq()
    save({ name: `V62 ${run}`, contact: `0300${run}` }).then((r) => {
      const a = savedOf(r)
      expect(a, 'the write returns the row').to.be.an('object')
      expect(a.version, '⭐ carrying a version').to.be.a('number')

      save({ customerId: a.customerId, name: `V62 ${run} edited`, contact: `0300${run}`, version: a.version })
        .then((r2) => {
          const b = savedOf(r2)
          expect(r2.body.status, JSON.stringify(r2.body).slice(0, 200)).to.eq('SUCCESS')
          expect(b.version, 'the version moved on').to.be.greaterThan(a.version)
        })
    })
  })

  it('⭐⭐ 2 — the SECOND till is refused, and told what to do', () => {
    /*
     * ⭐ THE CASE. Both requests carry the SAME version — which is precisely what two screens loaded at the
     * same moment hold. The first wins; the second must be refused rather than quietly overwriting it.
     */
    const run = uniq()
    save({ name: `V62 Race ${run}`, contact: `0311${run}` }).then((r) => {
      const initial = savedOf(r)
      const id = initial.customerId
      const staleVersion = initial.version

      // Till A saves first and succeeds.
      save({ customerId: id, name: `V62 Race ${run} A`, contact: `0311${run}`, version: staleVersion })
        .then((a) => {
          expect(a.body.status, 'the first save wins').to.eq('SUCCESS')

          // Till B, holding the version from BEFORE A saved.
          save({ customerId: id, name: `V62 Race ${run} B`, contact: `0311${run}`, version: staleVersion })
            .then((b) => {
              expect(b.body.status, `the stale save is REFUSED: ${JSON.stringify(b.body).slice(0, 200)}`)
                .to.eq('CONFLICT')
              // The message must name the ACTION. "An unexpected error, contact support" — what this used
              // to produce — sends a cashier to a support queue for something they can fix in ten seconds.
              expect(String(b.body.message).toLowerCase(), 'and says what to do')
                .to.match(/reload|somebody else|changed/)
            })
        })
    })
  })

  it('⭐ 3 — the refused save changed NOTHING', () => {
    /*
     * A refusal that half-wrote would be worse than the overwrite it replaces. Asserted by reading the
     * record back: it must still hold the FIRST till's values, untouched.
     */
    const run = uniq()
    save({ name: `V62 Intact ${run}`, contact: `0322${run}` }).then((r) => {
      const initial = savedOf(r)
      save({ customerId: initial.customerId, name: `V62 Intact ${run} WINNER`,
             contact: `0322${run}`, version: initial.version }).then(() => {
        save({ customerId: initial.customerId, name: `V62 Intact ${run} LOSER`,
               contact: `0322${run}`, version: initial.version }).then((loser) => {
          expect(loser.body.status).to.eq('CONFLICT')

          cy.request('/getUserCustomer?q=-1').then((list) => {
            const rows = (list.body && list.body.collection) || []
            const row = rows.find((c) => String(c.customerId) === String(initial.customerId))
            expect(row, 'the customer still exists').to.be.an('object')
            expect(row.name, '⭐ the winner\'s value survived').to.contain('WINNER')
            expect(row.name, 'and the loser wrote nothing').to.not.contain('LOSER')
          })
        })
      })
    })
  })

  it('4 — a NEW customer needs no version', () => {
    // There is nothing to conflict with on an insert, so an absent version must not be treated as stale.
    const run = uniq()
    save({ name: `V62 New ${run}`, contact: `0333${run}` }).then((r) => {
      expect(r.body.status).to.eq('SUCCESS')
      expect(savedOf(r).customerId).to.be.ok
    })
  })

  it('⭐ 5 — an older client that sends NO version still saves', () => {
    /*
     * ⚠ THE COMPATIBILITY CASE, and a deliberate decision rather than an oversight.
     *
     * A browser holding a cached copy of the old form sends no version. Refusing those would break every
     * open tab the moment this deployed — to protect against a collision that is rare. So a missing version
     * falls back to the row's current one: today's last-write-wins, exactly the behaviour that client has
     * always had. New clients send it and get the protection.
     *
     * A shop unable to save a customer is a worse outcome than the overwrite this guards against.
     */
    const run = uniq()
    save({ name: `V62 Legacy ${run}`, contact: `0344${run}` }).then((r) => {
      const initial = savedOf(r)
      save({ customerId: initial.customerId, name: `V62 Legacy ${run} edited`, contact: `0344${run}` })
        .then((edit) => {
          expect(edit.body.status, `no version supplied must still save: ${JSON.stringify(edit.body).slice(0, 160)}`)
            .to.eq('SUCCESS')
        })
    })
  })

  it('⭐ 6 — the SCREEN sends the version, so a real edit is protected', () => {
    /*
     * Cases 1–5 post the endpoint directly. This one proves the browser participates: the version rides
     * hidden in the grid row, editRecord() copies it into the form, and the save carries it back. Without
     * that round trip the server always falls back and the whole column does nothing on the real screen.
     */
    const run = uniq()
    save({ name: `V62 Screen ${run}`, contact: `0355${run}` }).then(() => {
      cy.visitDashboardSettled()
      cy.openSection('CustomerDiv')
      cy.get('#tableCustomer_filter input', { timeout: 15000 }).clear().type(`V62 Screen ${run}`, { delay: 0 })

      /*
       * ⚠ CLICK THE ROW'S EDIT BUTTON, NOT THE ROW.
       *
       * Clicking the row is what a person used to do, and it is what this spec did — which is why it failed
       * with an empty version field and sent me hunting through editRecord(), the DOM order of the hidden
       * input, and the row span. All of those were correct. main.js opens with
       *
       *     if (crudHasModal && !$(e.target).closest('.js-edit-row').length) return;
       *
       * and Customer HAS a modal (#CustomerModal), so a bare row click returns before editRecord() is ever
       * reached. The form was never filled, by anything. Any screen with a modal needs the injected
       * per-row Edit button; row-click editing only survives on the non-modal grids.
       */
      cy.contains('#tableCustomer tbody tr', `V62 Screen ${run}`, { timeout: 15000 })
        .find('.js-edit-row').click({ force: true })

      /*
       * editRecord() fills the form from the row — including the hidden version span.
       *
       * ⚠ NOT `.should('have.value')` with no argument. That chainer REQUIRES the expected value, so with
       * none it compares against `undefined` and can never pass — it reported
       * "expected <input> to have value undefined, but the value was ''", which reads like a product fault
       * and is entirely the assertion's. A version of 0 is also perfectly valid (a customer saved once and
       * never edited), so this must test "present and numeric", never truthiness.
       */
      cy.get('#customerVersion', { timeout: 10000 }).should(($el) => {
        var v = String($el.val());
        expect(v, 'the form carries a version from the row').to.not.eq('')
        expect(Number.isNaN(Number(v)), 'and it is a number').to.eq(false)
      })
    })
  })
})
