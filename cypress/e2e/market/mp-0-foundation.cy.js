/**
 * MP-0 — marketplace foundation: the opt-in capability (MP-0a), versioned policies and seller onboarding (MP-0b).
 *
 * Design: microservices/docs/slices/mp-0-marketplace-foundation.md §4 (cases listed there first).
 * Programme: microservices/docs/platform-marketplace-design.md.
 *
 * <h3>Tenants</h3>
 *   seller A  owner.mobile@     — applies, is approved, suspended, reinstated, withdraws (left WITHDRAWN)
 *   seller B  owner.pesticide@  — the cross-tenant case: never sees A's profile
 *   operator  admin@myplus.com  — policies and seller decisions
 *   ladder    owner.business@ switches selling on; user.business@ (same org, non-owner tier) is refused
 *
 * <h3>Re-runnable</h3>
 * Marketplace rows are never deleted (agreements are evidence). Every run publishes a fresh version of each
 * seller policy and ends with seller A WITHDRAWN — the one state from which applying again is allowed — so the
 * next run starts from an eligible state (GATE-RUNBOOK §7). Plans are restored with cy.planOf / cy.setPlan.
 *
 * <h3>Capabilities ride the token</h3>
 * A gateway token carries the capabilities resolved when it was MINTED, so the capability is switched first
 * (monolith session) and only then is a gateway token taken.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OP_PW = Cypress.env('adminPassword') || 'Admin@2025!'
const OPERATOR = 'admin@myplus.com'
const SELLER_A = 'owner.mobile@myplus.com'
const SELLER_B = 'owner.pesticide@myplus.com'
const LADDER_OWNER = 'owner.business@myplus.com'
const NON_OWNER = 'user.business@myplus.com'
const CAP = 'marketplaceSelling'
const KEY = 'org.cap.' + CAP
const SWITCH = `#businessConfigBody [data-key="${KEY}"]`
const SELLER_TYPES = ['SELLER_AGREEMENT', 'DATA_SHARING', 'COMMISSION', 'RETURNS_REFUNDS']
const run = Date.now()

const token = (email, password = PW) =>
  cy.request({ method: 'POST', url: `${GW}/api/auth/login`, headers: { 'Content-Type': 'application/json' },
    body: { email, password }, failOnStatusCode: false })
    .then((r) => { expect(r.status, `login ${email}: ${JSON.stringify(r.body)}`).to.eq(200); return r.body.data.accessToken })

const hdr = (t) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })
const api = (t, method, path, body) =>
  cy.request({ method, url: `${GW}/api/market${path}`, headers: hdr(t), body, failOnStatusCode: false })

const ok = (r, what) => { expect(r.body && r.body.success, `${what}: ${JSON.stringify(r.body)}`).to.eq(true); return r.body.data }

const application = (accepted) => ({
  displayName: `Gate Mobile ${run}`, contactPhone: '03001234567', contactEmail: 'owner.mobile@myplus.com',
  city: 'Karachi', pickupAddress: 'Shop 4, Saddar', serviceRadiusKm: 10, acceptedPolicyIds: accepted,
})

/** Publish a fresh version of every seller policy; returns {type: id}. */
const publishSellerPolicies = (op) => {
  const ids = {}
  return cy.wrap(SELLER_TYPES).each((type) =>
    api(op, 'POST', '/admin/policies', { policyType: type, title: `${type} (gate ${run})`,
      summary: `What a seller agrees to under ${type}. Gate run ${run}.` })
      .then((r) => ok(r, `create ${type}`))
      .then((p) => api(op, 'POST', `/admin/policies/${p.id}/publish`))
      .then((r) => { const p = ok(r, `publish ${type}`); ids[type] = p.id }))
    .then(() => ids)
}

const openConfig = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.get('#snavSettings').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavSettings .snav-btn').click() })
  cy.get('#navConfiguration').click()
  cy.get('#ConfigDiv').should('be.visible')
  cy.get('#businessConfigBody .cfg-group', { timeout: 20000 }).should('have.length.greaterThan', 3)
}

describe('MP-0 — marketplace foundation: opt-in selling, policies, seller onboarding', () => {
  const plans = {}
  let policyIds = null
  let sellerAId = null

  before(() => {
    // Selling is NOT in the FREE plan (ruling R-3): lift both sellers to PRO for the run, restore in after().
    cy.loginAsOperator()
    ;[SELLER_A, SELLER_B, LADDER_OWNER].forEach((e) => cy.planOf(e).then((p) => { plans[e] = p; cy.setPlan(p.id, 'PRO') }))
    cy.loginAsMobileOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY } })
  })

  after(() => {
    // Leave seller A WITHDRAWN (re-applicable next run), capability overrides removed, plans restored.
    token(OPERATOR, OP_PW).then((op) => {
      if (!sellerAId) return
      api(op, 'GET', '/admin/sellers').then((r) => {
        const a = (r.body.data || []).find((s) => s.id === sellerAId)
        if (a && a.status === 'SUSPENDED') api(op, 'POST', `/admin/sellers/${sellerAId}/reinstate`)
      })
    })
    cy.loginAsMobileOwner()
    cy.setCapability(CAP, true)
    token(SELLER_A).then((t) => api(t, 'POST', '/seller/withdraw', { reason: 'gate cleanup' }))
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY } })
    cy.loginAsPesticideOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY } })
    cy.loginAsOperator()
    cy.then(() => Object.values(plans).forEach((p) => cy.setPlan(p.id, p.plan)))
  })

  it('1 — real UI: the Configuration screen offers "Sell on the MaxTheService marketplace", unticked, and switching it on takes', () => {
    cy.loginAsMobileOwner()
    openConfig()
    cy.revealSetting(KEY)
    cy.get(SWITCH).should('exist').and('not.be.checked').and('not.be.disabled')
    cy.get(SWITCH).closest('.cfg-row').should('contain', 'Sell on the MaxTheService marketplace')
    cy.get(SWITCH).check()
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    cy.getCapabilities().its(CAP).should('eq', true)
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY } })
  })

  it('2 — capability OFF: reading policies and applying are refused, nothing is written', () => {
    cy.loginAsMobileOwner()
    cy.getCapabilities().then((caps) => {
      expect(caps[CAP], 'opt-in: OFF with no override').to.eq(false)
      expect(caps.installments, 'POSITIVE CONTROL: a preset capability is still ON').to.eq(true)
    })
    token(SELLER_A).then((t) => {
      api(t, 'GET', '/seller/policies/current').then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.contain('not switched on')
      })
      api(t, 'POST', '/seller/apply', application([])).then((r) => expect(r.body.success).to.eq(false))
    })
  })

  it('3 — a tenant owner is not the operator: every /admin endpoint answers 403', () => {
    token(SELLER_A).then((t) => {
      api(t, 'GET', '/admin/policies').its('status').should('eq', 403)
      api(t, 'POST', '/admin/policies', { policyType: 'COD', title: 'x', summary: 'y' }).its('status').should('eq', 403)
      api(t, 'GET', '/admin/sellers').its('status').should('eq', 403)
    })
  })

  it('4 — the operator publishes a version of each seller policy; v(n+1) supersedes v(n)', () => {
    token(OPERATOR, OP_PW).then((op) => {
      api(op, 'GET', '/admin/policies?type=COMMISSION').then((r) => {
        const before = ok(r, 'list').filter((p) => p.status === 'PUBLISHED')
        publishSellerPolicies(op).then((ids) => {
          policyIds = ids
          api(op, 'GET', '/admin/policies?type=COMMISSION').then((r2) => {
            const rows = ok(r2, 'list')
            const published = rows.filter((p) => p.status === 'PUBLISHED')
            expect(published, 'exactly one published COMMISSION version').to.have.length(1)
            expect(published[0].id).to.eq(ids.COMMISSION)
            before.forEach((old) => expect(rows.find((p) => p.id === old.id).status, `v${old.versionNo}`).to.eq('SUPERSEDED'))
          })
        })
      })
    })
  })

  it('5 — with selling ON, seller A sees the four current versions and must accept all of them', () => {
    cy.loginAsMobileOwner()
    cy.setCapability(CAP, true)
    token(SELLER_A).then((t) => {
      api(t, 'GET', '/seller/policies/current').then((r) => {
        const rows = ok(r, 'current')
        expect(rows.map((p) => p.policyType)).to.deep.eq(SELLER_TYPES)
        expect(rows.map((p) => p.id)).to.deep.eq(SELLER_TYPES.map((x) => policyIds[x]))
      })
      const missing = SELLER_TYPES.filter((x) => x !== 'COMMISSION').map((x) => policyIds[x])
      api(t, 'POST', '/seller/apply', application(missing)).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message, 'names what is missing').to.contain('COMMISSION')
      })
    })
  })

  it('6 — the privilege ladder: a non-owner member cannot apply (403)', () => {
    // The capability is the owner's switch; the user tier shares the owner's org, so its token carries it.
    cy.loginAsOwner()
    cy.setCapability(CAP, true)
    token(NON_OWNER).then((t) => {
      api(t, 'POST', '/seller/apply', application(Object.values(policyIds))).its('status').should('eq', 403)
    })
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY } })
  })

  it('7 — seller A applies → PENDING_REVIEW, its own org, four agreement rows of the current versions', () => {
    token(SELLER_A).then((t) => {
      api(t, 'POST', '/seller/apply', application(SELLER_TYPES.map((x) => policyIds[x]))).then((r) => {
        const s = ok(r, 'apply')
        sellerAId = s.id
        expect(s.status).to.eq('PENDING_REVIEW')
        const current = s.agreements.filter((a) => Object.values(policyIds).includes(a.policyId))
        expect(current.map((a) => a.policyType).sort()).to.deep.eq([...SELLER_TYPES].sort())
      })
      // double submit: still one profile (UNIQUE seller_organization_id), still PENDING_REVIEW
      api(t, 'POST', '/seller/apply', application(SELLER_TYPES.map((x) => policyIds[x]))).then((r) => {
        expect(ok(r, 're-submit').id).to.eq(sellerAId)
      })
    })
  })

  it('8 — cross-tenant: seller B sees no profile, not seller A\'s', () => {
    cy.loginAsPesticideOwner()
    cy.setCapability(CAP, true)
    token(SELLER_B).then((t) => api(t, 'GET', '/seller/profile').then((r) => {
      const p = ok(r, 'B profile')
      if (p) expect(p.id, 'B never receives A').not.to.eq(sellerAId)
    }))
  })

  it('9 — a policy republished while A waits blocks approval until A re-applies', () => {
    token(OPERATOR, OP_PW).then((op) => {
      api(op, 'POST', '/admin/policies', { policyType: 'SELLER_AGREEMENT', title: `SELLER_AGREEMENT (gate ${run} v+1)`,
        summary: 'Revised while an application waited.' })
        .then((r) => api(op, 'POST', `/admin/policies/${ok(r, 'create').id}/publish`))
        .then((r) => { policyIds.SELLER_AGREEMENT = ok(r, 'publish').id })
      cy.then(() => api(op, 'POST', `/admin/sellers/${sellerAId}/approve`)).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.contain('re-apply')
      })
    })
    token(SELLER_A).then((t) => api(t, 'POST', '/seller/apply', application(SELLER_TYPES.map((x) => policyIds[x])))
      .then((r) => expect(ok(r, 're-apply').status).to.eq('PENDING_REVIEW')))
  })

  it('10 — operator approves → ACTIVE; suspend needs a reason; reinstate; a REJECTED-only move is refused', () => {
    token(OPERATOR, OP_PW).then((op) => {
      api(op, 'POST', `/admin/sellers/${sellerAId}/approve`).then((r) => expect(ok(r, 'approve').status).to.eq('ACTIVE'))
      api(op, 'POST', `/admin/sellers/${sellerAId}/reject`, { reason: 'too late' }).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.contain('cannot be moved')
      })
      api(op, 'POST', `/admin/sellers/${sellerAId}/suspend`, { reason: ' ' }).then((r) => expect(r.body.success).to.eq(false))
      api(op, 'POST', `/admin/sellers/${sellerAId}/suspend`, { reason: 'gate: late deliveries' })
        .then((r) => expect(ok(r, 'suspend').status).to.eq('SUSPENDED'))
    })
    token(SELLER_A).then((t) => {
      api(t, 'GET', '/seller/profile').then((r) => {
        const p = ok(r, 'A profile')
        expect(p.status).to.eq('SUSPENDED')
        expect(p.statusReason, 'the seller sees why').to.eq('gate: late deliveries')
      })
      api(t, 'POST', '/seller/withdraw', {}).then((r) => expect(r.body.message).to.contain('suspended'))
    })
    token(OPERATOR, OP_PW).then((op) => api(op, 'POST', `/admin/sellers/${sellerAId}/reinstate`)
      .then((r) => expect(ok(r, 'reinstate').status).to.eq('ACTIVE')))
  })

  it('11 — every decision is on the audit trail of the SELLER\'s org, marked as a platform operator', () => {
    // Delivery is after commit through the outbox, so read with a bounded retry rather than once.
    const read = (t, tries = 10) =>
      cy.request({ url: `${GW}/api/audit?limit=200`, headers: hdr(t) }).then((r) => {
        const rows = (r.body || []).filter((x) => x.entityRef === `SELLER-${sellerAId}`)
        const actions = rows.map((x) => x.action)
        if (actions.includes('MARKET_SELLER_REINSTATE') || tries <= 0) return rows
        cy.wait(1000)
        return read(t, tries - 1)
      })
    token(SELLER_A).then((t) => read(t).then((rows) => {
      const actions = rows.map((x) => x.action)
      ;['MARKET_SELLER_APPLY', 'MARKET_SELLER_APPROVE', 'MARKET_SELLER_SUSPEND', 'MARKET_SELLER_REINSTATE']
        .forEach((a) => expect(actions, a).to.include(a))
      const approve = rows.find((x) => x.action === 'MARKET_SELLER_APPROVE')
      expect(approve.actorType, 'the operator acted on this tenant').to.eq('PLATFORM_OPERATOR')
      const apply = rows.find((x) => x.action === 'MARKET_SELLER_APPLY')
      expect(apply.actorType, 'the owner acted on their own tenant').to.eq('MEMBER')
    }))
  })
})
