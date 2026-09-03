/**
 * ONB-2 — a capability must never hide money already owed.
 *
 * Design: microservices/docs/slices/onb-2-assign-business-type-design.md §5
 *
 * ── The defect ──────────────────────────────────────────────────────────────────────────────────
 * `BusinessDashboardController` gated the `installmentsDue` stat on the INSTALLMENTS capability, while
 * `InstallmentController`'s seven endpoints gate on nothing at all.
 *
 * The two halves disagreed, and the disagreement costs money. Switch a shop away from installments — because
 * it changed trade, as Shahzad is about to — and its open plans stay collectable while the amount outstanding
 * **vanishes from its dashboard**. Nothing reminds it to chase what it is owed.
 *
 * ── The rule ────────────────────────────────────────────────────────────────────────────────────
 * A capability governs what a tenant may DO NEXT. It must never govern what they may SEE about what they have
 * already done. This spec is the rule's only enforcement, so it is worth stating in full here: the tile keeps
 * its `data-capability` (a shop that never sold on terms sees nothing), but the NUMBER is computed for anyone
 * who has open plans, whatever their capability now says.
 *
 * ── ⚠ Not the opposite mistake either ───────────────────────────────────────────────────────────
 * C5 established that a hidden tile must not fetch its data — the installments query was gated in the first
 * place for a real performance reason. Case 12 pins that: a tenant with NO plans still gets no number, so this
 * is not a blanket "always compute it".
 */

const OWNER = 'owner.business@myplus.com'

const stats = () =>
  cy.request({ url: '/getBusinessDashboardStats', failOnStatusCode: false }).then((r) => {
    expect(r.status, `stats HTTP: ${JSON.stringify(r.body)}`).to.eq(200)
    return (r.body && (r.body.object || r.body.data)) || {}
  })

describe('ONB-2 — a withdrawn capability does not hide an existing debt', () => {
  after(() => {
    // Leave no server state behind — owner.business@ is the tenant most other specs run on.
    cy.loginAsOwner(OWNER)
    cy.setCapability('installments', true)
  })

  it('⭐ 11 — with installments OFF and an open plan, the amount due is still reported', () => {
    /*
     * The before-state is established as the OPPOSITE, which is the only arrangement that proves anything:
     * the capability is switched ON, a plan is confirmed to exist, and only THEN is the capability withdrawn.
     * Asserting "the number is present" against a tenant that never had plans would pass on the bug.
     */
    cy.loginAsOwner(OWNER)
    cy.setCapability('installments', true)

    stats().then((s) => {
      expect(s, 'precondition: the tenant reports an installments figure while the capability is on')
        .to.have.property('installmentsDue')
      const open = Number(s.installmentsDue)
      expect(open, 'precondition: this tenant HAS open plans, or the case proves nothing').to.be.greaterThan(0)

      cy.setCapability('installments', false)
      stats().then((after) => {
        expect(after, 'the figure must survive the capability being withdrawn')
          .to.have.property('installmentsDue')
        expect(Number(after.installmentsDue), 'and it must be the same debt').to.eq(open)
      })
    })
  })

  it('12 — a tenant with no open plans still gets no number', () => {
    /*
     * The other half of the rule, and the reason this is not simply "always compute it". C5's finding was that
     * a hidden tile whose data was fetched anyway gates only appearance — that screen was tuned 3s → 0.27s by
     * removing exactly this kind of work.
     *
     * owner.pesticide@ is a pharmacy that has never sold on terms.
     */
    cy.loginAsPesticideOwner()
    stats().then((s) => {
      const due = s.installmentsDue
      expect(due === undefined || Number(due) === 0,
        `a tenant with no plans must not be given a figure: ${JSON.stringify(due)}`).to.eq(true)
    })
  })
})
