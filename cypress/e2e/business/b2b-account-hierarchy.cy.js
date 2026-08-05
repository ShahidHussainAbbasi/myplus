/**
 * B2B Phase 4a — account hierarchy (company → branch → contact), SHARED POOL credit.
 * Design: microservices/docs/slices/b2b-P4a-account-hierarchy.md
 *
 * A company sets ONE credit limit; its branches all draw on it. What this gate proves is the behaviour that
 * did not exist before and is silent when wrong:
 *
 *   • a branch is capped by the COMPANY's limit, not its own blank one;
 *   • the balance measured against that limit is the POOL — otherwise three branches each spend the full limit;
 *   • a standalone customer is untouched (every existing customer in every existing shop is one);
 *   • the invariants hold: no cycles, no self-parent, no cross-tenant parent.
 *
 * Needs ROLE_OWNER — restructuring accounts is owner/admin-gated server-side. Uses the PHARMA owner: it carries
 * ROLE_OWNER with NO write cap (unlike the demo.* accounts, capped at 50 writes per module — this spec seeds
 * ~12 customers, and the cap surfaces as an arbitrary later write failing rather than as a quota message), and
 * PHARMA reuses the trade backend, so customers, credit and the account panel are the same code under test.
 * Run headed.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

/** Create a trade customer and return its id, asserting it landed — an unasserted fixture makes the rest vacuous. */
const addCustomer = (name, creditLimit) =>
  cy.request({
    method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: {
      name, contact: '03' + Math.floor(Math.random() * 100000000),
      customerType: 'WHOLESALE',
      ...(creditLimit != null ? { creditLimit } : {}),
    },
  }).then((r) => {
    expect(r.body.status, `addCustomer ${name}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    return cy.request('/getUserCustomer').then((list) => {
      const rows = list.body.collection || []
      const mine = rows.find((c) => c.name === name)
      expect(mine, `customer ${name} is readable back`).to.exist
      return cy.wrap(mine.customerId)
    })
  })

const setParent = (customerId, parentCustomerId, accountLevel = 'BRANCH') =>
  cy.request({
    method: 'POST', url: '/setCustomerAccountParent', form: true, failOnStatusCode: false,
    body: { customerId, parentCustomerId: parentCustomerId == null ? '' : parentCustomerId, accountLevel },
  })

/**
 * A successful attach/detach that re-stamped NOTHING is the failure mode that matters: the hierarchy moves but
 * the credit account does not, so a branch keeps drawing on its own limit. The endpoint reports the row count in
 * its message, so assert on that rather than on `status` alone — otherwise a silent no-op surfaces three
 * assertions later as an unexplained id mismatch.
 */
const expectRestamped = (r, label) => {
  expect(r.body.status, `${label}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
  expect(String(r.body.message || ''), `${label} re-stamped at least one row: ${JSON.stringify(r.body)}`)
    .to.match(/[1-9]\d* row/)
}

/**
 * The account group payload. NOTE the field: GenericResponse has no `data` — a Map payload arrives in `object`
 * (a List would arrive in `collection`). Asserting the payload itself, not just the status, is what makes a
 * wrong field name fail HERE with a clear message instead of surfacing as `undefined` four assertions later.
 */
const group = (customerId) =>
  cy.request('/customerAccountGroup?customerId=' + customerId).then((r) => {
    expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
    expect(r.body.object, `account group payload for ${customerId}: ${JSON.stringify(r.body)}`).to.exist
    return r.body.object
  })

describe('B2B P4a — account hierarchy + shared-pool credit', () => {
  beforeEach(() => cy.loginAsPharmaOwner())

  it('a branch joins its company and the group pools their dues under ONE limit', () => {
    const co = 'AK_Co_' + uniq()
    const br = 'AK_Lahore_' + uniq()

    addCustomer(co, 100000).then((companyId) => {
      addCustomer(br).then((branchId) => {
        // Before joining, the branch is its own single-member account.
        group(branchId).then((g) => {
          expect(g.accountCustomerId, 'standalone customer heads its own account').to.eq(branchId)
        })

        setParent(branchId, companyId, 'BRANCH').then((r) => expectRestamped(r, 'attach branch to company'))

        // After joining, BOTH rows report the company as the credit account.
        group(branchId).then((g) => {
          expect(g.accountCustomerId, 'the branch now draws on the company').to.eq(companyId)
          expect(Number(g.creditLimit), "the COMPANY's limit governs").to.eq(100000)
          const names = (g.members || []).map((m) => m.name)
          expect(names, 'both rows are in the group').to.include(co).and.to.include(br)
        })
      })
    })
  })

  it('detaching a branch returns it to its own account', () => {
    const co = 'DT_Co_' + uniq()
    const br = 'DT_Br_' + uniq()

    addCustomer(co, 50000).then((companyId) => {
      addCustomer(br).then((branchId) => {
        // Assert the fixture: an unasserted setParent turns a refusal into a confusing id mismatch later.
        setParent(branchId, companyId, 'BRANCH').then((r) => expectRestamped(r, 'attach before detach'))
        group(branchId).then((g) => expect(g.accountCustomerId).to.eq(companyId))

        setParent(branchId, null, 'COMPANY').then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        })
        group(branchId).then((g) => {
          expect(g.accountCustomerId, 'detached → its own account again').to.eq(branchId)
        })
      })
    })
  })

  it('a CONTACT under a branch draws on the COMPANY, not on the branch (depth 3)', () => {
    // The pool must not split at the middle level. Deriving the account from the immediate parent put contacts
    // on their branch's id, quietly giving a three-level group two separate credit limits.
    const co = 'D3_Co_' + uniq()
    const br = 'D3_Br_' + uniq()
    const ct = 'D3_Contact_' + uniq()

    addCustomer(co, 80000).then((companyId) => {
      addCustomer(br).then((branchId) => {
        setParent(branchId, companyId, 'BRANCH').then((r) => expectRestamped(r, 'branch under company'))

        addCustomer(ct).then((contactId) => {
          setParent(contactId, branchId, 'CONTACT').then((r) => expectRestamped(r, 'contact under branch'))

          group(contactId).then((g) => {
            expect(g.accountCustomerId, 'the contact draws on the COMPANY, not the branch').to.eq(companyId)
            expect(Number(g.creditLimit), "the company's limit reaches three levels down").to.eq(80000)
            const names = (g.members || []).map((m) => m.name)
            expect(names, 'all three share one pool').to.include(co).and.to.include(br).and.to.include(ct)
          })
        })
      })
    })
  })

  // ── the invariants ────────────────────────────────────────────────────────────────────────────────

  it('an account cannot be its own parent', () => {
    addCustomer('Self_' + uniq(), 1000).then((id) => {
      setParent(id, id, 'BRANCH').then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.not.eq('SUCCESS')
        expect(String(r.body.message || '')).to.match(/own parent|descendant of itself/i)
      })
    })
  })

  it('a CYCLE is refused — re-parenting a company under its own branch', () => {
    const co = 'CY_Co_' + uniq()
    const br = 'CY_Br_' + uniq()

    addCustomer(co, 1000).then((companyId) => {
      addCustomer(br).then((branchId) => {
        setParent(branchId, companyId, 'BRANCH').then((r) => expectRestamped(r, 'attach before cycle attempt'))
        // Now try to close the loop: company under its own branch.
        setParent(companyId, branchId, 'BRANCH').then((r) => {
          expect(r.body.status, `a cycle must be refused: ${JSON.stringify(r.body)}`).to.not.eq('SUCCESS')
          expect(String(r.body.message || '')).to.match(/descendant of itself|own parent/i)
        })
        // And the tree is unchanged — a rejected edit must not half-apply.
        group(branchId).then((g) => expect(g.accountCustomerId).to.eq(companyId))
      })
    })
  })

  it('a parent from ANOTHER tenant is refused (anti-IDOR)', () => {
    // A REAL row in a different tenant, not a made-up id — otherwise this only proves "unknown id is rejected",
    // which a broken scope check would also pass. owner.business is a separate organization from owner.pharma.
    const foreign = 'Foreign_' + uniq()

    cy.loginAsOwner()
    addCustomer(foreign, 100000).then((foreignId) => {
      cy.loginAsPharmaOwner()
      addCustomer('Iso_' + uniq(), 1000).then((mine) => {
        setParent(mine, foreignId, 'BRANCH').then((r) => {
          expect(r.body.status, `a cross-tenant parent must be refused: ${JSON.stringify(r.body)}`)
            .to.not.eq('SUCCESS')
          // "not found", never "forbidden" — the refusal must not confirm that the row exists elsewhere.
          expect(String(r.body.message || ''), 'indistinguishable from a missing row')
            .to.match(/not found/i)
        })
        // And nothing half-applied: the customer still heads its own account.
        group(mine).then((g) => expect(g.accountCustomerId).to.eq(mine))
      })
    })
  })

  // ── the regression that matters most ──────────────────────────────────────────────────────────────

  it('a standalone customer is completely unaffected by the hierarchy', () => {
    const solo = 'Solo_' + uniq()
    addCustomer(solo, 5000).then((id) => {
      group(id).then((g) => {
        expect(g.accountCustomerId, 'its own account head').to.eq(id)
        expect(Number(g.creditLimit), 'its own limit').to.eq(5000)
        expect((g.members || []).length, 'a single-member group').to.eq(1)
      })
    })
  })

  it('the Account groups panel renders for an owner and lists the group (UI)', () => {
    const co = 'UI_Co_' + uniq()
    const br = 'UI_Br_' + uniq()

    addCustomer(co, 25000).then((companyId) => {
      addCustomer(br).then((branchId) => {
        setParent(branchId, companyId, 'BRANCH').then((r) => expectRestamped(r, 'attach before rendering'))

        cy.visit('/businessDashboard')
        cy.get('#registrationType').select('CustomerDiv', { force: true })
        cy.get('#AccountGroupCard', { timeout: 10000 }).should('be.visible')

        cy.get('#agChild').select(String(branchId), { force: true })
        cy.get('#agGroupSummary', { timeout: 10000 }).should('be.visible')
        cy.get('#agMembers').should('contain', co).and('contain', br)
        cy.get('#agLimitNote').should('contain', '25000.00')
      })
    })
  })
})
