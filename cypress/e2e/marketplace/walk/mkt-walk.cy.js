/**
 * MKT manual walk — RECORDED. Every manual test case of a built slice, done step by step on a live stack exactly as
 * the manual page tells a tester to do it: each step's action is performed, its expected result is ASSERTED, and the
 * screen is captured. The case (steps, expected results, cleanup, data, timestamps) is written to cypress/walk/<id>.json,
 * and the published manual page is BUILT from those files — so the page can only say what was actually walked.
 *
 * Opt-in (never part of `cypress run`): --env walk=1 --spec cypress/e2e/marketplace/walk/mkt-walk.cy.js
 * Before a recording, reset the never-applied shop: microservices/docs/marketplace/walk-reset.sql (MKT-0a's
 * agreement acceptance is a permanent record by design, so the product itself has no way to undo it).
 *
 * People (the same accounts the manual page names):
 *   OPERATOR  admin@myplus.com          MaxTheService staff
 *   SELLER_A  owner.business@myplus.com "Shahzad Mobile Shop" — has the admin./user. tier ladder
 *   SELLER_B  owner.mobile@myplus.com   "Mobile Distributor"
 *   SHOP_C    owner.audit@myplus.com    a shop that has never applied (reset before each recording)
 *   OUTSIDER  owner.pesticide@myplus.com a shop MaxTheService never entitled
 *   PHARMACY  owner.pharma@myplus.com   a pharmacy (prescription control on)
 *   Customer  no login (cookies cleared = an incognito window)
 */
const { uniq, SELLER_A, SELLER_B, OUTSIDER, PHARMACY, API, UI, ok, data, list, msg, post, get, makeSeller,
  openMarketplace, seedProduct, seedRxProduct, seedPolicies, publishOffer } = require('../mkt-helpers')

const SHOP_C = 'owner.audit@myplus.com'
const PW = 'Demo@2025!'
const OUT = 'cypress/walk'
const pad = (n) => String(n).padStart(2, '0')
const on = Cypress.env('walk') ? describe : describe.skip

/** A fresh login (new cache key) so capability changes that ride in the token are picked up, as "log out and in". */
const as = (email) => cy.loginAs(email, PW, '/getBusinessDashboardStats', 'walk-' + Date.now())
const asOperator = () => cy.loginAsOperator()
const customer = () => cy.clearCookies()

/** Shorten a response for the page: long lists keep their first 3 rows. */
const trim = (b) => {
  if (Array.isArray(b)) return b.length > 3 ? b.slice(0, 3).concat([`… ${b.length - 3} more`]) : b
  if (b && typeof b === 'object') {
    const o = {}
    Object.keys(b).forEach((k) => { o[k] = trim(b[k]) })
    return o
  }
  return b
}

/**
 * One manual case. `body(step, call, cleanup)`:
 *   step(do, expect, fn, {screen})  performs fn (actions + assertions of `expect`), then captures the screen
 *   call(label, chain)              records an API request/response as the step's evidence (developer-tools steps)
 *   cleanup(do, expect, fn)         the undo, performed and recorded the same way
 */
/** --env grep=M-1c records only the cases whose id starts with it (re-recording one slice after a fix). */
const only = (id) => !Cypress.env('grep') || new RegExp('^' + Cypress.env('grep')).test(id)
const walk = (meta, body) => (only(meta.id) ? it : it.skip)(`${meta.id} ${meta.title}`, () => {
  const steps = []
  const cleanups = []
  let cur = null
  const started = new Date().toISOString()
  const run = (bucket, prefix) => (doText, expectText, fn, opts = {}) => {
    cy.then(() => {
      cur = { n: bucket.length + 1, do: doText, expect: expectText, calls: [] }
      bucket.push(cur)
      cy.log(`${prefix} ${cur.n}: ${doText}`)
    })
    // snap(): capture NOW — the moment the person sees the proof — before any behind-the-scenes check that logs in
    // as someone else (a capture at the end of such a step showed a blank page: M-1e-04/05 in the first full run)
    const snap = () => cy.then(() => {
      const s = cur
      s.shot = `${meta.id}/${prefix === 'STEP' ? '' : 'c'}${pad(s.n)}.png`
      cy.screenshot(`${meta.id}/${prefix === 'STEP' ? '' : 'c'}${pad(s.n)}`, { capture: 'viewport', overwrite: true })
    })
    cy.then(() => fn(snap))
    cy.then(() => {
      const s = cur
      if (s.shot) return
      if (opts.screen === false || (opts.screen === undefined && s.calls.length && !opts.alsoScreen)) return
      s.shot = `${meta.id}/${prefix === 'STEP' ? '' : 'c'}${pad(s.n)}.png`
      cy.screenshot(`${meta.id}/${prefix === 'STEP' ? '' : 'c'}${pad(s.n)}`, { capture: 'viewport', overwrite: true })
    })
  }
  const step = run(steps, 'STEP')
  /** A step with NO screen yet: written from the implementation flow, recorded as such, proven by `proof`. */
  step.flow = (doText, expectText, source, proof) => cy.then(() => {
    steps.push({ n: steps.length + 1, do: doText, expect: expectText, calls: [], flow: source, proof })
  })
  const cleanup = run(cleanups, 'CLEANUP')
  const call = (label, chain) => chain.then((r) => {
    cur.calls.push({ call: label, status: r.status, body: trim(r.body) })
    return r
  })
  body(step, call, cleanup)
  cy.then(() => cy.writeFile(`${OUT}/${meta.id}.json`, Object.assign({}, meta, {
    steps, cleanup: cleanups, recordedAt: new Date().toISOString(), startedAt: started, result: 'passed',
  })))
})

/** Operator console: open a marketplace panel from the platform dashboard. */
const console_ = (btn) => {
  asOperator()
  cy.visit(UI.operatorPage)
  cy.get(btn).should('be.visible').click()
}
/** Settings → Configuration → the marketplace switch, as the owner does it. */
const configSwitch = () => {
  cy.visit('/businessDashboard')
  cy.get('#snavSettings .snav-btn').click({ timeout: 30000 })
  cy.get('#navConfiguration').should('be.visible').click({ timeout: 30000 })
  cy.get('#ConfigDiv').should('be.visible')
  cy.revealSetting('org.cap.marketplaceSelling')
  return cy.get('#bcfg_org_cap_marketplaceSelling').should('be.visible')
}
/** Operator → Platform → the tenant → a capability row's Grant / Revoke, through the shared reason dialog. */
const entitle = (email, verb, reason) => {
  asOperator()
  cy.orgOf(email).then((org) => {
    cy.visit(UI.operatorPage)
    cy.get('#platSearch').clear().type(org.name)
    cy.get(`#platTenantList .plat-row[data-org="${org.id}"]`, { timeout: 15000 }).click()
    cy.get('.plat-cap[data-cap="marketplaceSelling"]', { timeout: 15000 }).scrollIntoView()
      .find(verb === 'grant' ? '.js-grant' : '.js-revoke').click()
    cy.get('#uiC-input').type(reason)
    cy.get('.uiC-ok').click()
    cy.get('.plat-cap[data-cap="marketplaceSelling"]', { timeout: 15000 })   // the panel re-renders: query afresh
      .should('contain', verb === 'grant' ? 'Entitled' : 'Revoked')
    cy.get('.plat-cap[data-cap="marketplaceSelling"]').scrollIntoView()
  })
}

on('MKT manual walk — recorded', () => {
  const run = uniq()
  const M = 'W' + String(run).slice(-5)          // the walk's own suffix: unique names on a shared environment
  const S = {}                                     // what one case hands to the next (ids), like a tester's notes

  beforeEach(() => cy.viewport(1366, 900))

  // ──────────────────────────────── MKT-0a ────────────────────────────────

  walk({ id: 'M-0a-01', slice: 'MKT-0a', title: 'Marketplace is off for a shop that never opted in',
    persona: 'owner.pesticide@myplus.com — a shop MaxTheService never entitled', reqs: ['MKT-R20.0'],
    pre: 'Nobody has switched the marketplace on for this shop.', auto: ['MKT-0a-02'] }, (step, call, cleanup) => {
    step('Log in as owner.pesticide@myplus.com (password Demo@2025!). Open Sale in the left menu.',
      'The Sale menu has no "Marketplace" entry.', () => {
        as(OUTSIDER)
        cy.visit('/businessDashboard')
        cy.get('#snavSell .snav-btn').click()
        cy.get('li[data-capability="marketplaceSelling"]').should('have.class', 'cap-off')
        cy.get(UI.sellerNav).should('not.be.visible')
      })
    step('Open Settings → Configuration and find "Sell on the MaxTheService marketplace".',
      'The switch is OFF. Its help text reads "List your products beside other sellers on the MaxTheService marketplace. MaxTheService approves your seller account first. Off until you switch it on."', () => {
        configSwitch().should('not.be.checked')
        cy.contains('MaxTheService approves your seller account first. Off until you switch it on.').should('be.visible')
      })
    cleanup('Nothing was changed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-0a-02', slice: 'MKT-0a', title: 'The owner cannot switch it on before MaxTheService entitles the shop',
    persona: 'owner.pesticide@myplus.com', reqs: ['MKT-R20.0', 'MKT-R22.1'], pre: 'As M-0a-01: the shop is on the FREE plan and not entitled.',
    auto: ['MKT-0a-02', 'MKT-0a-03'] }, (step, call, cleanup) => {
    step('Settings → Configuration → try to tick "Sell on the MaxTheService marketplace".',
      'The switch is greyed out and cannot be ticked. Under it a "Not in plan" badge with a lock says why. No error page.', () => {
        as(OUTSIDER)
        configSwitch().should('be.disabled').and('not.be.checked')
        // the setting's own row: the nearest ancestor that holds its title
        cy.get('#bcfg_org_cap_marketplaceSelling').parentsUntil('#ConfigDiv')
          .filter(':contains("Sell on the MaxTheService marketplace")').first().should('contain', 'Not in plan')
      })
    step('Developer tools (or the API): POST /mkt/acceptAgreement {"version":"v1","displayName":"Should not exist"}, then GET /mkt/seller.',
      'The POST is refused: "… not switched on …". The GET answers and shows no seller account (account: null) — the refused write created nothing.', () => {
        call('POST /mkt/acceptAgreement', post(API.acceptAgreement, { version: 'v1', displayName: 'Should not exist' }))
          .then((r) => { expect(ok(r.body)).to.eq(false); expect(msg(r.body)).to.contain('not switched on') })
        call('GET /mkt/seller', get(API.sellerStatus)).then((r) => {
          expect(ok(r.body)).to.eq(true)
          expect(data(r.body).account).to.eq(null)
        })
      })
    cleanup('Nothing was saved.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-0a-03', slice: 'MKT-0a', title: 'The owner switches it on, reads the agreements and applies',
    persona: 'admin@myplus.com (operator), then owner.audit@myplus.com (a shop that has never applied)',
    reqs: ['MKT-R20.0', 'MKT-R9.1', 'MKT-R20.1', 'MKT-R9.2', 'MKT-R9.3'],
    pre: 'owner.audit@\'s shop has never applied (on a test environment run walk-reset.sql first) and is not entitled.',
    auto: ['MKT-0a-01', 'MKT-0a-05'] }, (step, call, cleanup) => {
    cy.then(() => {   // the precondition, made true the way the cleanup of M-0a-06 leaves it
      as(SHOP_C)
      get(API.sellerStatus).then((r) => expect((data(r.body) || {}).agreementsCurrent,
        `owner.audit@ has already accepted — run walk-reset.sql before recording (${JSON.stringify(r.body)})`).to.not.eq(true))
    })
    step('As admin@myplus.com: Platform → search "Owner Audit" → open the shop → "Sell on the MaxTheService marketplace" → Grant → reason "marketplace pilot" → Grant.',
      'The row reads "Entitled".', () => { entitle(SHOP_C, 'grant', 'marketplace pilot') })
    step('Log in as owner.audit@myplus.com. Settings → Configuration → tick "Sell on the MaxTheService marketplace".',
      '"Saved". The switch stays ON.', () => {
        as(SHOP_C)
        cy.intercept('POST', '**/saveBusinessConfig').as('save')
        configSwitch().check()
        cy.wait('@save').its('response.statusCode').should('eq', 200)
        cy.get('#businessConfigMsg', { timeout: 20000 }).should('contain', 'Saved')
      })
    step('Log out and back in (the switch travels with the login). Sale → Marketplace.',
      'Status lines read "Marketplace selling is switched on." and that the agreements are not yet accepted. The agreement box shows what is shared ("product identity, your marketplace price, availability, delivery area and time, warranty, return policy, and your business name") and what is never asked for ("what you paid suppliers, your margins, your other customers, your staff\'s data, or your full stock history"). "Accept and apply" is greyed out.', () => {
        as(SHOP_C)
        openMarketplace()
        cy.get('#mktStatusCapability').should('contain', 'switched on')
        cy.get('#mktAgreementBox').should('be.visible')
          .and('contain', 'product identity, your marketplace price')
          .and('contain', 'what you paid suppliers, your margins')
        cy.get('#mktAcceptBtn').should('be.disabled')
      })
    step('Type "Audit Electronics" as the name customers will see. Tick "I have read both agreements and accept them for this business."',
      '"Accept and apply" becomes active.', () => {
        cy.get('#mktDisplayName').clear().type('Audit Electronics')
        cy.get('#mktAgreeChk').check()
        cy.get('#mktAcceptBtn').should('be.enabled')
      })
    step('Click "Accept and apply".',
      'The lines read "Agreements accepted (version v1)." and "MaxTheService is reviewing your seller account." The agreement box closes.', () => {
        cy.get('#mktAcceptBtn').click()
        cy.get('#mktStatusAgreements', { timeout: 15000 }).should('contain', 'accepted (version v1)')
        cy.get('#mktStatusAccount').should('contain', 'reviewing your seller account')
        cy.get('#mktAgreementBox').should('not.be.visible')
      })
    cleanup('None yet: M-0a-06 continues with this application and undoes both switches at its end.', 'The application waits in the operator\'s review list.', () => {}, { screen: false })
  })

  walk({ id: 'M-0a-04', slice: 'MKT-0a', title: 'A user-tier member cannot accept agreements for the business',
    persona: 'user.business@myplus.com (a staff member of Shahzad Mobile Shop, user tier)', reqs: ['MKT-R22.1'],
    pre: 'Shahzad Mobile Shop (owner.business@) has the marketplace switched on.', auto: ['MKT-0a-04'] }, (step, call, cleanup) => {
    cy.then(() => makeSeller(SELLER_A, 'Shahzad Mobile Shop'))
    step('Developer tools: as user.business@myplus.com, POST /mkt/acceptAgreement {"version":"v1","displayName":"x"}.',
      'Refused: "Only the owner or an admin can accept these agreements for the business." Nothing is accepted.', () => {
        cy.loginAsTier('user', 'business', PW, 'walk-' + Date.now())
        call('POST /mkt/acceptAgreement', post(API.acceptAgreement, { version: 'v1', displayName: 'x' }))
          .then((r) => { expect(ok(r.body)).to.eq(false); expect(msg(r.body)).to.contain('Only the owner or an admin') })
      })
    cleanup('Nothing was changed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-0a-06', slice: 'MKT-0a', title: 'Only MaxTheService approves a seller, and a suspension says why',
    persona: 'admin@myplus.com (operator), then owner.audit@myplus.com', reqs: ['MKT-R20.1', 'MKT-R22.1', 'MKT-R19.1'],
    pre: 'owner.audit@ has applied as "Audit Electronics" (M-0a-03).', auto: ['MKT-0a-06', 'MKT-0a-07'] }, (step, call, cleanup) => {
    let org
    cy.then(() => { asOperator(); cy.orgOf(SHOP_C).then((o) => { org = o.id }) })
    const row = () => cy.get(`#platMktSellerList [data-org="${org}"]`, { timeout: 15000 })
    step('As admin@myplus.com: Platform → "Marketplace sellers" (opens on "Waiting for review").',
      '"Audit Electronics" is listed with Approve, Reject and a reason box.', () => {
        console_('#platMktSellersBtn')
        cy.get('#platMktSellers').should('be.visible')
        row().should('contain', 'Audit Electronics').find('[data-decision="APPROVE"]').should('be.visible')
      })
    step('Click Approve on "Audit Electronics", then open the "Approved" list.',
      'It has left "Waiting for review". On "Approved" its row offers only Suspend (never Approve or Reject).', () => {
        row().find('[data-decision="APPROVE"]').click()
        cy.get(`#platMktSellerList [data-org="${org}"]`).should('not.exist')
        cy.contains('#platMktStatus button', 'Approved').click()
        row().within(() => {
          cy.get('[data-decision="SUSPEND"]').should('be.visible')
          cy.get('[data-decision="APPROVE"]').should('not.exist')
          cy.get('[data-decision="REJECT"]').should('not.exist')
        })
      })
    step('Click Suspend with the reason box empty.',
      'Refused: "Give the seller a reason. They will see it as written." The seller stays Approved.', () => {
        row().find('[data-decision="SUSPEND"]').click()
        row().find('[role="status"]').should('contain', 'Give the seller a reason')
      })
    step('Type "documents expired" in the reason box and click Suspend.',
      'The row leaves "Approved" (it is now under "Suspended").', () => {
        row().find('input').type('documents expired')
        row().find('[data-decision="SUSPEND"]').click()
        cy.get(`#platMktSellerList [data-org="${org}"]`).should('not.exist')
      })
    step('As owner.audit@myplus.com: Sale → Marketplace.',
      'The account line reads "Suspended: documents expired".', () => {
        as(SHOP_C)
        openMarketplace()
        cy.get('#mktStatusAccount').should('contain', 'Suspended: documents expired')
      })
    step('As admin@myplus.com: Marketplace sellers → "Suspended" → Reinstate on "Audit Electronics".',
      'The row leaves "Suspended".', () => {
        console_('#platMktSellersBtn')
        cy.contains('#platMktStatus button', 'Suspended').click()
        row().find('[data-decision="REINSTATE"]').click()
        cy.get(`#platMktSellerList [data-org="${org}"]`).should('not.exist')
      })
    step('As owner.audit@myplus.com: Sale → Marketplace.',
      'The account line reads "Approved by MaxTheService. You can list products."', () => {
        as(SHOP_C)
        openMarketplace()
        cy.get('#mktStatusAccount').should('contain', 'Approved by MaxTheService')
      })
    cleanup('As owner.audit@: Settings → Configuration → untick "Sell on the MaxTheService marketplace".',
      '"Saved"; the Marketplace entry leaves the Sale menu at the next login.', () => {
        cy.intercept('POST', '**/saveBusinessConfig').as('save')
        configSwitch().uncheck()
        cy.wait('@save')
        cy.get('#businessConfigMsg', { timeout: 20000 }).should('contain', 'Saved')
      })
    cleanup('As admin@: Platform → Owner Audit → "Sell on the MaxTheService marketplace" → Revoke → reason "walk finished" → Revoke.',
      'The row reads "Revoked". The seller account and the accepted agreement stay: they are records, never deleted. To walk M-0a-03 again on a test environment, run walk-reset.sql.', () => {
        entitle(SHOP_C, 'revoke', 'walk finished')
      })
  })

  // ──────────────────────────────── MKT-1b ────────────────────────────────

  const key = (storage) => `SAMSUNG|GALAXY-A32-${M}|${storage}|BLACK|NEW`

  walk({ id: 'M-1b-01', slice: 'MKT-1b', title: 'A seller proposes a phone for the marketplace',
    persona: 'owner.business@myplus.com (Shahzad Mobile Shop)', reqs: ['MKT-R5.1', 'MKT-R6.2', 'MKT-R6.4', 'MKT-R5.2'],
    pre: `Shahzad Mobile Shop is an approved seller and has a product "Galaxy A32 128 Black ${M}" with 5 in stock (add it under Products if it is missing).`,
    data: { model: `Galaxy A32 ${M}` }, auto: ['MKT-1b-01', 'MKT-1b-02'] }, (step, call, cleanup) => {
    cy.then(() => makeSeller(SELLER_A, 'Shahzad Mobile Shop'))
    cy.then(() => seedProduct({ name: `Galaxy A32 128 Black ${M}` })).then((id) => { S.aProduct = id })
    step('Log in as owner.business@myplus.com. Sale → Marketplace → "Propose a product".',
      'The proposal form opens with: Your product, Brand, Model, Variant / storage, Colour, Condition and Warranty.', () => {
        as(SELLER_A)
        openMarketplace()
        cy.get('#mktProposeBtn').should('be.visible').click()
        cy.get('#mktProposeForm').should('be.visible')
        cy.get('#mktProposeProduct option').should('have.length.greaterThan', 1)
      })
    step(`Your product "Galaxy A32 128 Black ${M}". Brand "Samsung". Model "Galaxy A32 ${M}". Variant / storage "128 GB" (with the space). Colour "black" (lower case). Condition New. Click "Send for review".`,
      `"Sent to MaxTheService for review." A new row shows the identity ${key('128GB')}… (the space and the lower case are normalised by the server) and a yellow "Waiting for review" badge.`, () => {
        cy.get('#mktProposeProduct').select(String(S.aProduct), { force: true })
        cy.get('#mktBrand').clear().type('Samsung')
        cy.get('#mktModel').clear().type(`Galaxy A32 ${M}`)
        cy.get('#mktVariant').clear().type('128 GB')
        cy.get('#mktColour').clear().type('black')
        cy.get('#mktCondition').select('New', { force: true })
        cy.get('#mktProposeSubmit').click()
        cy.get('#mktProposeMsg', { timeout: 15000 }).should('contain', 'Sent to MaxTheService for review')
        cy.contains('#mktProposalsTable tr', key('128GB')).find('[data-status="PENDING_REVIEW"]').should('be.visible')
      })
    cleanup('None: M-1b-02 continues with this proposal.', 'It waits in the operator\'s "Product matching" queue.', () => {}, { screen: false })
  })

  walk({ id: 'M-1b-02', slice: 'MKT-1b', title: 'The operator matches it: one marketplace product is born',
    persona: 'admin@myplus.com (operator)', reqs: ['MKT-R6.4', 'MKT-R5.2', 'MKT-R6.5'], pre: 'M-1b-01 done.',
    auto: ['MKT-1b-03'] }, (step, call, cleanup) => {
    const row = () => cy.contains('#mktMatchQueue tr', key('128GB'), { timeout: 15000 })
    step('As admin@myplus.com: Platform → "Product matching".',
      `Shahzad Mobile Shop's proposal ${key('128GB')}… is listed. Its "Existing match" column reads "New marketplace product".`, () => {
        console_('#platMktMatchesBtn')
        row().should('contain', 'New marketplace product')
      })
    step('Click Match on that row.', 'The row leaves the queue.', () => {
      row().find('[data-decision="MATCHED"]').click()
      cy.contains('#mktMatchQueue tr', key('128GB')).should('not.exist')
    })
    step('Open the "Matched" list.', 'The proposal is listed there, now attached to a marketplace product.', () => {
      cy.contains('#platMktMatchStatus button', 'Matched').click()
      row().should('be.visible')
      get(API.matchQueue + '?status=MATCHED&size=100').then((r) => {
        const p = list(r.body).find((x) => x.proposedIdentityKey && x.proposedIdentityKey.startsWith(key('128GB')))
        expect(p && p.mktProductId, 'attached to a marketplace product').to.be.a('number')
        S.product = p.mktProductId
        S.aProposal = p
      })
    })
    cleanup('None: the matched product is what M-1b-03 and the offers in MKT-1c build on.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1b-03', slice: 'MKT-1b', title: 'The same phone from a second seller is suggested, never merged on its own',
    persona: 'owner.mobile@myplus.com (Mobile Distributor), then admin@myplus.com', reqs: ['MKT-R6.1', 'MKT-R5.4', 'MKT-R6.4'],
    pre: `M-1b-02 done. Mobile Distributor is an approved seller with a product "Samsung A-32 128GB blk ${M}" in stock.`,
    auto: ['MKT-1b-04'] }, (step, call, cleanup) => {
    cy.then(() => makeSeller(SELLER_B, 'Mobile Distributor'))
    cy.then(() => seedProduct({ name: `Samsung A-32 128GB blk ${M}` })).then((id) => { S.bProduct = id })
    step(`As owner.mobile@myplus.com: Sale → Marketplace → "Propose a product". Your product "Samsung A-32 128GB blk ${M}". Brand " samsung " (spaces), Model "galaxy a32 ${M.toLowerCase()}", Variant "128 gb", Colour "BLACK", Condition New. Send for review.`,
      `The row shows the SAME identity as Shahzad's, ${key('128GB')}…, and "Waiting for review". Nothing is live yet.`, () => {
        as(SELLER_B)
        openMarketplace()
        cy.get('#mktProposeBtn').click()
        cy.get('#mktProposeProduct').select(String(S.bProduct), { force: true })
        cy.get('#mktBrand').clear().type(' samsung ')
        cy.get('#mktModel').clear().type(`galaxy a32 ${M.toLowerCase()}`)
        cy.get('#mktVariant').clear().type('128 gb')
        cy.get('#mktColour').clear().type('BLACK')
        cy.get('#mktCondition').select('New', { force: true })
        cy.get('#mktProposeSubmit').click()
        cy.get('#mktProposeMsg', { timeout: 15000 }).should('contain', 'Sent to MaxTheService for review')
        cy.contains('#mktProposalsTable tr', key('128GB')).find('[data-status="PENDING_REVIEW"]').should('be.visible')
      })
    step('As admin@myplus.com: Platform → "Product matching".',
      `Mobile Distributor's row reads "Same identity as product #<the product from M-1b-02>" in "Existing match".`, () => {
        console_('#platMktMatchesBtn')
        cy.contains('#mktMatchQueue tr', key('128GB'), { timeout: 15000 }).should('contain', `Same identity as product #${S.product}`)
      })
    step('Click Match on it.',
      'Both sellers now point at ONE marketplace product (the "Matched" list shows both rows with the same product).', () => {
        cy.contains('#mktMatchQueue tr', key('128GB')).find('[data-decision="MATCHED"]').click()
        cy.contains('#mktMatchQueue tr', key('128GB')).should('not.exist')
        cy.contains('#platMktMatchStatus button', 'Matched').click()
        get(API.matchQueue + '?status=MATCHED&size=100').then((r) => {
          const both = list(r.body).filter((x) => (x.proposedIdentityKey || '').startsWith(key('128GB')))
          expect(both.map((x) => x.mktProductId)).to.deep.eq([S.product, S.product])
        })
      })
    cleanup('None: MKT-1c offers this product from both sellers.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1b-04', slice: 'MKT-1b', title: '64GB is never merged with 128GB',
    persona: 'owner.mobile@myplus.com, then admin@myplus.com', reqs: ['MKT-R6.6', 'MKT-R6.1'], pre: 'M-1b-02 done (the 128GB product exists).',
    auto: ['MKT-1b-05'] }, (step, call, cleanup) => {
    cy.then(() => { as(SELLER_B); seedProduct({ name: `Galaxy A32 64 Black ${M}` }).then((id) => { S.b64 = id }) })
    step(`As owner.mobile@myplus.com: propose "Galaxy A32 64 Black ${M}" with Brand Samsung, Model "Galaxy A32 ${M}", Variant "64GB", Colour Black, New.`,
      `The identity ends …|64GB|BLACK|NEW…; "Waiting for review".`, () => {
        as(SELLER_B)
        openMarketplace()
        cy.get('#mktProposeBtn').click()
        cy.get('#mktProposeProduct').select(String(S.b64), { force: true })
        cy.get('#mktBrand').clear().type('Samsung')
        cy.get('#mktModel').clear().type(`Galaxy A32 ${M}`)
        cy.get('#mktVariant').clear().type('64GB')
        cy.get('#mktColour').clear().type('Black')
        cy.get('#mktCondition').select('New', { force: true })
        cy.get('#mktProposeSubmit').click()
        cy.contains('#mktProposalsTable tr', key('64GB'), { timeout: 15000 }).find('[data-status="PENDING_REVIEW"]').should('be.visible')
      })
    step('As admin@myplus.com: Platform → "Product matching".',
      'The 64GB row reads "New marketplace product" — never "Same identity as product #…".', () => {
        console_('#platMktMatchesBtn')
        cy.contains('#mktMatchQueue tr', key('64GB'), { timeout: 15000 })
          .should('contain', 'New marketplace product').and('not.contain', 'Same identity')
      })
    cleanup('As admin@: on the 64GB row type "walk cleanup" in the note box and click Reject.',
      'The row leaves the queue; no marketplace product is created for it.', () => {
        cy.contains('#mktMatchQueue tr', key('64GB')).within(() => {
          cy.get('input').type('walk cleanup')
          cy.get('[data-decision="REJECTED"]').click()
        })
        cy.contains('#mktMatchQueue tr', key('64GB')).should('not.exist')
      })
  })

  walk({ id: 'M-1b-05', slice: 'MKT-1b', title: 'The operator corrects a bad match; the seller reads the note',
    persona: 'admin@myplus.com, then owner.business@myplus.com', reqs: ['MKT-R6.5', 'MKT-R6.4'], pre: 'M-1b-02 done.',
    auto: ['MKT-1b-06'] }, (step, call, cleanup) => {
    const row = () => cy.contains('#mktMatchQueue tr', `#${S.aProposal.organizationId} · Galaxy A32 128 Black ${M}`, { timeout: 15000 })
    step('As admin@myplus.com: Product matching → "Matched". On Shahzad Mobile Shop\'s row click "Needs correction" with the note box empty.',
      'Refused: "Tell the seller what is wrong…". The row stays Matched.', () => {
        console_('#platMktMatchesBtn')
        cy.contains('#platMktMatchStatus button', 'Matched').click()
        row().find('[data-decision="NEEDS_CORRECTION"]').click()
        row().find('[role="status"]').should('contain', 'Tell the seller what is wrong')
      })
    step('Type "colour is Blue on the box" in the note box and click "Needs correction".',
      'The row leaves "Matched".', () => {
        row().find('input').type('colour is Blue on the box')
        row().find('[data-decision="NEEDS_CORRECTION"]').click()
        row().should('not.exist')
      })
    step('As owner.business@myplus.com: Sale → Marketplace.',
      'The proposal shows a red "Needs correction" badge and the note exactly as typed: "colour is Blue on the box".', () => {
        as(SELLER_A)
        openMarketplace()
        cy.contains('#mktProposalsTable tr', `Galaxy A32 128 Black ${M}`).should('contain', 'colour is Blue on the box')
          .find('[data-status="NEEDS_CORRECTION"]').should('be.visible')
      })
    cleanup(`As owner.business@: propose "Galaxy A32 128 Black ${M}" again with the same details; as admin@: Product matching → Match it.`,
      'Shahzad Mobile Shop is attached to the product again, ready for MKT-1c.', () => {
        call('POST /mkt/proposeProduct', post(API.proposeProduct, { sourceProductId: S.aProduct, brand: 'Samsung',
          model: `Galaxy A32 ${M}`, variant: '128GB', colour: 'Black', condition: 'New', warrantyType: '12M' }))
          .then((r) => {
            expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
            asOperator()
            return call('POST /platform/mkt/decideMatch', post(API.decideMatch, { id: data(r.body).id, decision: 'MATCHED',
              mktProductId: S.product, version: data(r.body).version }))
          }).then((d) => expect(ok(d.body), JSON.stringify(d.body)).to.eq(true))
      })
  })

  walk({ id: 'M-1b-06', slice: 'MKT-1b', title: 'A product from another shop\'s catalogue cannot be proposed',
    persona: 'owner.mobile@myplus.com, with the browser\'s developer tools', reqs: ['MKT-R22.1'],
    pre: 'M-1b-01 done; Shahzad Mobile Shop\'s product id is known.', auto: ['MKT-1b-07'] }, (step, call, cleanup) => {
    step('As owner.mobile@myplus.com: POST /mkt/proposeProduct with sourceProductId = Shahzad Mobile Shop\'s product id, Brand Samsung, Model "Galaxy A32 X".',
      'Refused: "That product is not in your catalogue." — the same sentence an id that does not exist gets.', () => {
        as(SELLER_B)
        call('POST /mkt/proposeProduct (Shahzad\'s product)', post(API.proposeProduct, { sourceProductId: S.aProduct,
          brand: 'Samsung', model: 'Galaxy A32 X', variant: '128GB', colour: 'Black', condition: 'New' }))
          .then((r) => { expect(ok(r.body)).to.eq(false); expect(msg(r.body)).to.contain('not in your catalogue') })
        call('POST /mkt/proposeProduct (id 999999999)', post(API.proposeProduct, { sourceProductId: 999999999,
          brand: 'Samsung', model: 'Galaxy A32 X', variant: '128GB', colour: 'Black', condition: 'New' }))
          .then((r) => { expect(ok(r.body)).to.eq(false); expect(msg(r.body)).to.contain('not in your catalogue') })
      })
    cleanup('Nothing was created.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-1b-07', slice: 'MKT-1b', title: 'Pack sizes stay separate products (Panadol 10s and 20s)',
    persona: 'owner.pharma@myplus.com (a pharmacy, approved seller)', reqs: ['MKT-R6.6', 'MKT-R6.3'],
    pre: `The pharmacy has products "Panadol Extra 10s ${M}" and "Panadol Extra 20s ${M}" (not prescription-only).`,
    auto: ['ProductIdentityKeyTest'] }, (step, call, cleanup) => {
    cy.then(() => makeSeller(PHARMACY, 'Gate Pharmacy'))
    cy.then(() => seedProduct({ name: `Panadol Extra 10s ${M}`, manufacturer: 'GSK', price: 120 })).then((id) => { S.p10 = id })
    cy.then(() => seedProduct({ name: `Panadol Extra 20s ${M}`, manufacturer: 'GSK', price: 230 })).then((id) => { S.p20 = id })
    const propose = (pid, pack) => {
      cy.get('#mktProposeForm').then(($f) => { if (!$f.is(':visible')) cy.get('#mktProposeBtn').click() })
      cy.get('#mktProposeProduct').select(String(pid), { force: true })
      cy.get('#mktBrand').clear().type('GSK')
      cy.get('#mktModel').clear().type(`Panadol Extra ${M}`)
      cy.get('#mktVariant').clear()
      cy.get('#mktColour').clear()
      cy.get('#mktSize').clear().type('500 mg')
      cy.get('#mktPackSize').clear().type(pack)
      cy.get('#mktProposeSubmit').click()
      cy.get('#mktProposeMsg', { timeout: 15000 }).should('contain', 'Sent to MaxTheService for review')
    }
    step(`As owner.pharma@myplus.com: Sale → Marketplace → Propose a product → "Panadol Extra 10s ${M}", Brand GSK, Model "Panadol Extra ${M}", Size "500 mg", Pack size "10". Send for review.`,
      'Sent. The identity ends in the pack size 10.', () => {
        as(PHARMACY)
        openMarketplace()
        propose(S.p10, '10')
        cy.contains('#mktProposalsTable tr', `PANADOL-EXTRA-${M}`).invoke('text').should('match', /\|10(\||$|\s)/)
      })
    step(`Propose "Panadol Extra 20s ${M}" the same way with Pack size "20".`,
      'A second row with a DIFFERENT identity ending in 20. The two are never suggested as the same product.', () => {
        propose(S.p20, '20')
        get(API.myProposals + '?size=100').then((r) => {
          const keys = list(r.body).filter((p) => (p.proposedIdentityKey || '').includes(`PANADOL-EXTRA-${M}`)).map((p) => p.proposedIdentityKey)
          expect(keys).to.have.length(2)
          expect(keys[0]).to.not.eq(keys[1])
        })
      })
    cleanup('As admin@: Product matching → Reject both Panadol rows with the note "walk cleanup".', 'Both leave the queue.', () => {
      asOperator()
      get(API.matchQueue + '?status=PENDING_REVIEW&size=100').then((r) => {
        list(r.body).filter((p) => (p.proposedIdentityKey || '').includes(`PANADOL-EXTRA-${M}`)).forEach((p) =>
          call('POST /platform/mkt/decideMatch', post(API.decideMatch, { id: p.id, decision: 'REJECTED', note: 'walk cleanup', version: p.version }))
            .then((d) => expect(ok(d.body), JSON.stringify(d.body)).to.eq(true)))
      })
    })
  })

  // ──────────────────────────────── MKT-1c ────────────────────────────────

  const policyNamed = (name) => get(API.policies).then((r) => list(r.body).find((x) => x.name === name))
  const W12 = () => `12 months — authorised distributor ${M}`
  const R7 = () => `7 days ${M}`

  walk({ id: 'M-1c-00', slice: 'MKT-1c', title: 'The operator creates the policies sellers choose from',
    persona: 'admin@myplus.com (operator)', reqs: ['MKT-R14.1', 'MKT-R14.2', 'MKT-R7.4'],
    pre: 'MKT-1c is deployed.', auto: ['MKT-1c-09', 'MKT-1c-10'] }, (step, call, cleanup) => {
    step('As admin@myplus.com: Platform → "Marketplace policies".',
      'The policy form (Kind, Name sellers see, …, Create policy) and the policy list are shown, with the line "Policies are never edited. To change terms, create a new policy and deactivate the old one, so nothing already sold changes."', () => {
        console_('#platMktPoliciesBtn')
        cy.get('#platMktPolicies').should('be.visible').and('contain', 'Policies are never edited')
      })
    step('Kind "Warranty", leave "Warranty provider" EMPTY, Name "No provider", Months 12. Click "Create policy".',
      'Refused: "Name the warranty provider. MaxTheService is never assumed to be it." Nothing is added.', () => {
        cy.get('#mktPolType').select('WARRANTY')
        cy.get('#mktPolName').clear().type('No provider')
        cy.get('#mktPolProvider').clear()
        cy.get('#mktPolMonths').clear().type('12')
        cy.get('#mktPolCreate').click()
        cy.get('#mktPolMsg').should('contain', 'Name the warranty provider')
      })
    step(`Kind "Warranty". Name "${W12()}". Provider "Samsung Pakistan (authorised distributor)". Months 12. Covers "Manufacturing defects". Excludes "Physical and liquid damage". Create policy.`,
      'Created. The list shows WARRANTY · the name · "12 · Samsung Pakistan (authorised distributor) · Manufacturing defects", with Deactivate and NO Edit.', () => {
        cy.get('#mktPolType').select('WARRANTY')
        cy.get('#mktPolName').clear().type(W12())
        cy.get('#mktPolProvider').clear().type('Samsung Pakistan (authorised distributor)')
        cy.get('#mktPolMonths').clear().type('12')
        cy.get('#mktPolCovers').clear().type('Manufacturing defects')
        cy.get('#mktPolExcludes').clear().type('Physical and liquid damage')
        cy.get('#mktPolCreate').click()
        cy.contains('#mktPolicyTable tr', W12(), { timeout: 15000 })
          .should('contain', '12 · Samsung Pakistan (authorised distributor)')
          .and('contain', 'Deactivate').and('not.contain', 'Edit')
      })
    step(`Kind "Returns". Name "${R7()}". Return days 7. Create policy.`, 'Listed as RETURN · "7 days".', () => {
      cy.get('#mktPolType').select('RETURN')
      cy.get('#mktPolName').clear().type(R7())
      cy.get('#mktPolDays').clear().type('7')
      cy.get('#mktPolCreate').click()
      cy.contains('#mktPolicyTable tr', R7(), { timeout: 15000 }).should('contain', 'RETURN')
    })
    step(`Kind "Commission". Name "Standard 8% ${M}". Charged on "Item price (not delivery)". Rate 8. Tick "Use for newly approved offers". Create policy.`,
      'Listed as "COMMISSION · default" with "8% · ITEMS". It replaces the earlier default for offers approved from now on.', () => {
        cy.get('#mktPolType').select('COMMISSION')
        cy.get('#mktPolName').clear().type(`Standard 8% ${M}`)
        cy.get('#mktPolBasis').select('ITEMS')
        cy.get('#mktPolRate').clear().type('8')
        cy.get('#mktPolDefault').check()
        cy.get('#mktPolCreate').click()
        cy.contains('#mktPolicyTable tr', `Standard 8% ${M}`, { timeout: 15000 }).should('contain', 'default').and('contain', '8% · ITEMS')
      })
    cy.then(() => policyNamed(W12()).then((x) => { S.w12 = x.id }))
    cy.then(() => policyNamed(R7()).then((x) => { S.r7 = x.id }))
    cleanup('None: the offers in MKT-1c use these. M-1c-07 deactivates the return policy.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1c-01', slice: 'MKT-1c', title: 'A seller creates an offer and sends it for approval',
    persona: 'owner.business@myplus.com (Shahzad Mobile Shop)', reqs: ['MKT-R5.3', 'MKT-R7.5', 'MKT-R14.1'],
    pre: `Shahzad Mobile Shop is attached to "Samsung Galaxy A32 ${M} 128GB Black" (M-1b-02, M-1b-05) and has 5 in stock. M-1c-00 done.`,
    auto: ['MKT-1c-01'] }, (step, call, cleanup) => {
    step('As owner.business@myplus.com: Sale → Marketplace → "New offer".',
      'The offer form opens: Product (only products MaxTheService matched), Price (Rs.), Delivery in (hours), Cities you deliver to, Warranty, Returns.', () => {
        as(SELLER_A)
        openMarketplace()
        cy.get('#mktNewOfferBtn').click()
        cy.get('#mktOfferForm').should('be.visible')
        cy.get(`#mktOfferProduct option[value="${S.product}"]`).should('exist')
      })
    step(`Product "Samsung Galaxy A32 ${M} 128GB Black". Price 52000. Delivery in 24 hours. Cities "Karachi, karachi , Lahore". Open the Warranty and the Returns lists.`,
      `Only ACTIVE policies are offered, among them "${W12()}" and "${R7()}". There is no way to type a warranty of your own.`, () => {
        cy.get('#mktOfferProduct').select(String(S.product), { force: true })
        cy.get('#mktOfferPrice').clear().type('52000')
        cy.get('#mktOfferPromise').clear().type('24')
        cy.get('#mktOfferArea').clear().type('Karachi, karachi , Lahore')
        cy.get(`#mktOfferWarranty option[value="${S.w12}"]`).should('exist')
        cy.get(`#mktOfferReturn option[value="${S.r7}"]`).should('exist')
        cy.get('#mktOfferWarranty').should('match', 'select')
      })
    step(`Warranty "${W12()}", Returns "${R7()}". Click "Save and send for approval".`,
      'The new row shows Rs. 52,000, cities "Karachi, Lahore" (the duplicate dropped, the spelling kept as typed) and a yellow "Waiting for review" badge. The whole table, Edit and Pause included, fits the screen without scrolling sideways.', () => {
        cy.get('#mktOfferWarranty').select(W12(), { force: true })
        cy.get('#mktOfferReturn').select(R7(), { force: true })
        cy.get('#mktOfferSubmit').click()
        cy.get('#mktOfferMsg').should('not.be.empty')
        cy.get(`${UI.offersTable} tr`).filter(`:contains("${M}")`).filter(':contains("Karachi, Lahore")').first()
          .should('contain', '52,000').within(() => cy.get('[data-status]').should('have.attr', 'data-status', 'PENDING_REVIEW'))
          .invoke('attr', 'data-offer-id').then((id) => { S.offerA = Number(id) })
        cy.get(UI.offersTable).should(($t) => expect($t[0].scrollWidth, 'no sideways scroll at 1366px').to.be.at.most($t.parent()[0].clientWidth))
      })
    cleanup('None: the offer continues in M-1c-04. (The server also refuses "send for approval" without both policies — gate MKT-1c-01.)', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1c-02', slice: 'MKT-1c', title: 'The browser cannot choose who owns or ships the stock',
    persona: 'owner.business@myplus.com, with the browser\'s developer tools', reqs: ['MKT-R3.1', 'MKT-R4.1', 'MKT-R22.1', 'MKT-R20.2', 'MKT-R3.2'],
    pre: 'M-1c-01 done; the offer id is known.', auto: ['MKT-1c-02', 'MKT-1c-04'] }, (step, call, cleanup) => {
    step('POST /mkt/saveOffer {id: <the offer>, sellerOrganizationId: 999999, stockOwnerOrganizationId: 999999, fulfillerOrganizationId: 999999}.',
      'Saved, but every party is still Shahzad Mobile Shop\'s own organisation and the price is still 52,000 (an edit keeps what it does not mention).', () => {
        as(SELLER_A)
        call('POST /mkt/saveOffer (forged parties)', post(API.saveOffer, { id: S.offerA, sellerOrganizationId: 999999,
          stockOwnerOrganizationId: 999999, fulfillerOrganizationId: 999999 })).then((r) => {
          const o = data(r.body)
          expect(o.sellerOrganizationId).to.not.eq(999999)
          expect(o.stockOwnerOrganizationId).to.eq(o.sellerOrganizationId)
          expect(o.fulfillerOrganizationId).to.eq(o.sellerOrganizationId)
          expect(Number(o.marketplacePrice)).to.eq(52000)
        })
      })
    step('POST /mkt/saveOffer {id: <the offer>, stockSourceType: "SUPPLIER"}.',
      'Refused: "Offers from supplier stock are not available yet." The source stays MERCHANT.', () => {
        call('POST /mkt/saveOffer (supplier stock)', post(API.saveOffer, { id: S.offerA, stockSourceType: 'SUPPLIER' }))
          .then((r) => { expect(ok(r.body)).to.eq(false); expect(msg(r.body)).to.contain('supplier stock are not available yet') })
        call('GET /mkt/getOffer', get(API.getOffer(S.offerA))).then((r) => expect(data(r.body).stockSourceType).to.eq('MERCHANT'))
      })
    cleanup('Nothing changed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-1c-03', slice: 'MKT-1c', title: 'A prescription product cannot be offered',
    persona: 'owner.pharma@myplus.com (a pharmacy, approved seller)', reqs: ['MKT-R20.2', 'MKT-R7.6'],
    pre: `The pharmacy has a product "Augmentin ${M}" with "Prescription required" ticked (Clinical & Safety).`,
    auto: ['MKT-1c-03', 'MKT-1b-09'] }, (step, call, cleanup) => {
    cy.then(() => { as(PHARMACY); seedRxProduct(`Augmentin ${M}`).then((id) => { S.rx = id }) })
    step(`As owner.pharma@myplus.com: Sale → Marketplace → "Propose a product" → "Augmentin ${M}", Brand GSK, Model "Augmentin ${M}". Send for review.`,
      'Refused: "Prescription and restricted products cannot be sold on the marketplace yet." Nothing is added to the proposals table.', () => {
        as(PHARMACY)
        openMarketplace()
        cy.get('#mktProposeBtn').click()
        cy.get('#mktProposeProduct').select(String(S.rx), { force: true })
        cy.get('#mktBrand').clear().type('GSK')
        cy.get('#mktModel').clear().type(`Augmentin ${M}`)
        cy.get('#mktProposeSubmit').click()
        cy.get('#mktProposeMsg', { timeout: 15000 }).should('contain', 'Prescription and restricted products cannot be sold on the marketplace yet')
        cy.get('#mktProposalsTable').should('not.contain', `AUGMENTIN-${M}`)
      })
    cleanup('Nothing was created.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-1c-04', slice: 'MKT-1c', title: 'The operator approves; a price outside the limits is refused',
    persona: 'admin@myplus.com (operator), then owner.business@myplus.com', reqs: ['MKT-R7.4', 'MKT-R22.2'],
    pre: 'M-1c-01 done: the offer waits for review. A default commission policy exists (M-1c-00).', auto: ['MKT-1c-05'] }, (step, call, cleanup) => {
    step('As admin@myplus.com: Platform → "Offer approvals" (opens on "Waiting for review").',
      `Shahzad Mobile Shop's offer for "Samsung Galaxy A32 ${M} 128GB Black" is listed at 52,000 for "Karachi, Lahore", with Approve and Reject.`, () => {
        console_('#platMktOffersBtn')
        cy.get(`#mktOfferQueue tr[data-offer-id="${S.offerA}"]`, { timeout: 15000 }).should('contain', '52,000').and('contain', 'Karachi, Lahore')
      })
    step('Click Approve.', 'The row leaves the queue.', () => {
      cy.get(`#mktOfferQueue tr[data-offer-id="${S.offerA}"] [data-decision="APPROVE"]`).click()
      cy.get(`#mktOfferQueue tr[data-offer-id="${S.offerA}"]`).should('not.exist')
    })
    step('As owner.business@myplus.com: Sale → Marketplace → My offers.', 'The offer reads "Live".', () => {
      as(SELLER_A)
      openMarketplace()
      cy.get(`${UI.offersTable} tr[data-offer-id="${S.offerA}"] [data-status]`).should('have.attr', 'data-status', 'APPROVED').and('have.text', 'Live')
    })
    step('Developer tools, as admin@: POST /platform/mkt/productLimits {id: <the marketplace product>, priceFloor: 40000, priceCeiling: 60000}.',
      'Saved.', () => {
        asOperator()
        call('POST /platform/mkt/productLimits', post(API.productLimits, { id: S.product, priceFloor: 40000, priceCeiling: 60000 }))
          .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
      })
    step('As owner.business@: My offers → Edit on the offer → Price 75000 → "Save".',
      'Refused: "This offer\'s price is outside the allowed range…". The price stays 52,000.', () => {
        as(SELLER_A)
        openMarketplace()
        cy.get(`${UI.offersTable} tr[data-offer-id="${S.offerA}"]`).contains('button', 'Edit').click()
        cy.get('#mktOfferPrice').should('have.value', '52000').clear().type('75000')
        cy.get('#mktOfferSave').click()
        cy.get('#mktOfferMsg').should('contain', 'outside the allowed range')
      })
    step('Change the price to 51500 and click "Save".', 'Saved; the row shows 51,500.', () => {
      cy.get('#mktOfferPrice').clear().type('51500')
      cy.get('#mktOfferSave').click()
      cy.get(`${UI.offersTable} tr[data-offer-id="${S.offerA}"]`).should('contain', '51,500')
    })
    cleanup('As owner.business@: Edit → Price 52000 → Save.', 'The row shows 52,000 again (later cases expect it).', () => {
      cy.get(`${UI.offersTable} tr[data-offer-id="${S.offerA}"]`).contains('button', 'Edit').click()
      cy.get('#mktOfferPrice').should('have.value', '51500').clear().type('52000')
      cy.get('#mktOfferSave').click()
      cy.get(`${UI.offersTable} tr[data-offer-id="${S.offerA}"]`).should('contain', '52,000')
    })
  })

  walk({ id: 'M-1c-05', slice: 'MKT-1c', title: 'Suspending a seller takes their offers down at once',
    persona: 'admin@myplus.com (operator) and a customer in an incognito window', reqs: ['MKT-R7.6'],
    pre: 'M-1c-04 done: the offer is Live.', auto: ['MKT-1c-07'] }, (step, call, cleanup) => {
    const productPage = (city) => `${UI.publicPage}?product=${S.product}&city=${city}`
    let sellerOrg
    cy.then(() => { asOperator(); cy.orgOf(SELLER_A).then((o) => { sellerOrg = o.id }) })
    step(`Customer (incognito): open ${'/marketplace?product=<the product>&city=Karachi'}.`,
      'Shahzad Mobile Shop is listed with Rs. 52,000.', () => {
        customer()
        cy.visit(productPage('Karachi'))
        cy.get(`${UI.offerRow}[data-offer-id="${S.offerA}"]`).should('contain', 'Rs. 52,000')
      })
    step('As admin@myplus.com: Marketplace sellers → "Approved" → Shahzad Mobile Shop → reason "documents expired" → Suspend.',
      'The row leaves "Approved".', () => {
        console_('#platMktSellersBtn')
        cy.contains('#platMktStatus button', 'Approved').click()
        cy.get(`#platMktSellerList [data-org="${sellerOrg}"]`, { timeout: 15000 }).within(() => {
          cy.get('input').type('documents expired')
          cy.get('[data-decision="SUSPEND"]').click()
        })
        cy.get(`#platMktSellerList [data-org="${sellerOrg}"]`).should('not.exist')
      })
    step('Customer: reload the product page.', 'Shahzad Mobile Shop\'s offer is gone.', () => {
      customer()
      cy.visit(productPage('Karachi'))
      cy.get(`${UI.offerRow}[data-offer-id="${S.offerA}"]`).should('not.exist')
    })
    step('As admin@: "Suspended" → Reinstate Shahzad Mobile Shop. Customer: reload.', 'The offer is back.', () => {
      console_('#platMktSellersBtn')
      cy.contains('#platMktStatus button', 'Suspended').click()
      cy.get(`#platMktSellerList [data-org="${sellerOrg}"] [data-decision="REINSTATE"]`, { timeout: 15000 }).click()
      cy.get(`#platMktSellerList [data-org="${sellerOrg}"]`).should('not.exist')
      customer()
      cy.visit(productPage('Karachi'))
      cy.get(`${UI.offerRow}[data-offer-id="${S.offerA}"]`).should('be.visible')
    })
    step('Customer: change the city in the address to Quetta (…&city=Quetta).', 'The offer is not listed: Shahzad delivers to Karachi and Lahore only.', () => {
      cy.visit(productPage('Quetta'))
      cy.get(`${UI.offerRow}[data-offer-id="${S.offerA}"]`).should('not.exist')
    })
    cleanup('Nothing left to undo: the seller was reinstated in step 4.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1c-06', slice: 'MKT-1c', title: 'Customers see the provider\'s warranty and nothing internal',
    persona: 'Customer (incognito window), developer tools', reqs: ['MKT-R9.2', 'MKT-R9.3', 'MKT-R14.2', 'MKT-R14.1'],
    pre: 'M-1c-04 done: the offer is Live.', auto: ['MKT-1c-06', 'MKT-1c-09'] }, (step, call, cleanup) => {
    step('Open /marketplace/public/products/<the product>/offers?city=Karachi and read Shahzad Mobile Shop\'s row.',
      'warrantyProvider "Samsung Pakistan (authorised distributor)", warrantyMonths 12, returnDays 7. No cost, margin, purchase, supplier or stock-movement field, and no organisationId other than the seller\'s own (sellerOrganizationId is public, by design).', () => {
        customer()
        call('GET public offers', get(`${API.publicOffers(S.product)}?city=Karachi`)).then((r) => {
          const row = list(r.body).find((o) => o.offerId === S.offerA)
          expect(row.warrantyProvider).to.eq('Samsung Pakistan (authorised distributor)')
          expect(row.warrantyMonths).to.eq(12)
          expect(row.returnDays).to.eq(7)
          const keys = Object.keys(row).join(',').toLowerCase()
          ;['cost', 'margin', 'purchase', 'supplier', 'movement'].forEach((k) => expect(keys).to.not.contain(k))
          expect(Object.keys(row)).to.not.include('organizationId')
        })
      })
    cleanup('Nothing changed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-1c-07', slice: 'MKT-1c', title: 'Deactivating a policy never changes what was sold',
    persona: 'admin@myplus.com, then owner.business@myplus.com', reqs: ['MKT-R7.4', 'MKT-R13.3'],
    pre: `The offer uses "${'7 days'} …" (M-1c-01).`, auto: ['MKT-1c-10'] }, (step, call, cleanup) => {
    step(`As admin@myplus.com: Marketplace policies → "${R7()}" → Deactivate.`,
      'The row turns grey and loses its Deactivate button.', () => {
        console_('#platMktPoliciesBtn')
        cy.get(`#mktPolicyTable tr[data-policy-id="${S.r7}"]`, { timeout: 15000 }).contains('button', 'Deactivate').click()
        cy.get(`#mktPolicyTable tr[data-policy-id="${S.r7}"]`).should('have.class', 'text-muted').find('button').should('not.exist')
      })
    step('As owner.business@: My offers → Edit on the offer. Open the Returns list.',
      `"${R7()}" is no longer offered.`, () => {
        as(SELLER_A)
        openMarketplace()
        cy.get(`${UI.offersTable} tr[data-offer-id="${S.offerA}"]`).contains('button', 'Edit').click()
        cy.get(`#mktOfferReturn option[value="${S.r7}"]`).should('not.exist')
      })
    step(`Developer tools: GET /mkt/getOffer?id=<the offer>, then POST /mkt/saveOffer {id: <the offer>, returnPolicyId: <the "${R7()}" id>}.`,
      'The offer still names the 7-day policy (what was agreed stays). Choosing it again is refused: "Choose an active return policy."', () => {
        call('GET /mkt/getOffer', get(API.getOffer(S.offerA))).then((r) => expect(data(r.body).returnPolicyId).to.eq(S.r7))
        call('POST /mkt/saveOffer (inactive policy)', post(API.saveOffer, { id: S.offerA, returnPolicyId: S.r7 }))
          .then((r) => { expect(ok(r.body)).to.eq(false); expect(msg(r.body)).to.contain('Choose an active return policy') })
      })
    cleanup('None: a deactivated policy is never re-activated. Create a new one when terms change.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1c-08', slice: 'MKT-1c', title: 'Another seller cannot see or change the offer',
    persona: 'owner.mobile@myplus.com (Mobile Distributor), developer tools', reqs: ['MKT-R22.1'],
    pre: 'Shahzad Mobile Shop\'s offer id is known.', auto: ['MKT-1c-08'] }, (step, call, cleanup) => {
    step('As owner.mobile@myplus.com: GET /mkt/myOffers.', 'Works (positive control): Mobile Distributor\'s own offers.', () => {
      as(SELLER_B)
      call('GET /mkt/myOffers', get(API.myOffers)).then((r) => expect(ok(r.body)).to.eq(true))
    })
    step('GET /mkt/getOffer?id=<Shahzad\'s offer>, then POST /mkt/saveOffer {id: <Shahzad\'s offer>, marketplacePrice: 1}.',
      'Both answer "No such offer." — exactly what an id that does not exist gets.', () => {
        call('GET /mkt/getOffer (another seller\'s)', get(API.getOffer(S.offerA))).then((r) => { expect(ok(r.body)).to.eq(false); expect(msg(r.body)).to.contain('No such offer') })
        call('POST /mkt/saveOffer (another seller\'s)', post(API.saveOffer, { id: S.offerA, marketplacePrice: 1 }))
          .then((r) => { expect(ok(r.body)).to.eq(false); expect(msg(r.body)).to.contain('No such offer') })
      })
    step('POST /platform/mkt/decideOffer {id: <Shahzad\'s offer>, decision: "SUSPEND", note: "x"}.',
      'Refused: a seller is not the operator. Shahzad\'s offer is unchanged (still Live at 52,000).', () => {
        call('POST /platform/mkt/decideOffer', post(API.decideOffer, { id: S.offerA, decision: 'SUSPEND', note: 'x' }))
          .then((r) => expect(r.status === 403 || !ok(r.body)).to.eq(true))
        as(SELLER_A)
        call('GET /mkt/getOffer (as Shahzad)', get(API.getOffer(S.offerA))).then((r) => {
          expect(data(r.body).approvalStatus).to.eq('APPROVED')
          expect(Number(data(r.body).marketplacePrice)).to.eq(52000)
        })
      })
    cleanup('Nothing changed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  // ──────────────────────────────── MKT-1d ────────────────────────────────

  const A_NAME = 'Shahzad Mobile Shop'
  const B_NAME = 'Mobile Distributor'
  const D = {}
  const page = (q) => `${UI.publicPage}?${q}`

  walk({ id: 'M-1d-01', slice: 'MKT-1d', title: 'One product, two sellers, from the lowest price',
    persona: 'Customer (incognito window)', reqs: ['MKT-R5.4', 'MKT-R7.2', 'MKT-R18.2'],
    pre: 'Shahzad Mobile Shop: Samsung Galaxy A32 128GB Black at Rs 52,000, delivery in 4 hours, 12-month warranty (Samsung Pakistan), 7-day returns. Mobile Distributor: the SAME product at Rs 51,500, 24 hours, 6-month warranty. Both Live, both deliver to Karachi only (the MKT-1c steps, done for each).',
    auto: ['MKT-1d-01', 'MKT-1d-06'] }, (step, call, cleanup) => {
    cy.then(() => {   // the precondition, made through the same seller and operator paths as MKT-1c
      seedPolicies(`${run}d`, { months: 12, provider: 'Samsung Pakistan' }).then((p) => { D.w12 = p.warranty; D.r7 = p.returns })
      seedPolicies(`${run}e`, { months: 6, provider: 'Shop warranty' }).then((p) => { D.w6 = p.warranty })
      asOperator()
      post(API.defaultSort, { sort: 'RECOMMENDED' })
      cy.then(() => publishOffer(SELLER_A, { run: `${run}d`, price: 52000, promiseHours: 4, qty: 50, warrantyPolicyId: D.w12, returnPolicyId: D.r7 }))
        .then((o) => { D.a = o.offerId; D.product = o.mktProductId })
      cy.then(() => publishOffer(SELLER_B, { run: `${run}d`, price: 51500, promiseHours: 24, qty: 50, warrantyPolicyId: D.w6, returnPolicyId: D.r7, mktProductId: D.product }))
        .then((o) => { D.b = o.offerId })
    })
    const model = `Galaxy A32 T${run}d`
    step(`Open /marketplace in an incognito window. City "Karachi". Search "${model}".`,
      `One card: "Samsung ${model} 128GB Black", "From Rs. 51,500", "Available from 2 sellers", "Delivery in 4 hours" (the fastest seller's promise).`, () => {
        customer()
        cy.visit(UI.publicPage)
        cy.get(UI.city).clear().type('Karachi')
        cy.get(UI.search).clear().type(`${model}{enter}`)
        cy.contains(UI.productCard, model).within(() => {
          cy.get(UI.fromPrice).should('have.text', 'From Rs. 51,500')
          cy.get(UI.offerCount).should('have.text', 'Available from 2 sellers')
          cy.get('.card-fast').should('have.text', 'Delivery in 4 hours')
        })
      })
    step('Open the card.',
      `Two sellers. ${A_NAME}: Rs. 52,000, Delivery in 4 hours, 12 months warranty · Samsung Pakistan, Returns within 7 days, "No ratings yet", "Stock checked … ago". ${B_NAME}: Rs. 51,500, Delivery in 1 day, 6 months.`, () => {
        cy.contains(UI.productCard, model).find('a').click()
        cy.get(UI.offerRow).should('have.length', 2)
        cy.get(`${UI.offerRow}[data-offer-id="${D.a}"]`).should('contain', A_NAME).and('contain', 'Rs. 52,000')
          .and('contain', 'Samsung Pakistan').and('contain', '12 months').and('contain', '7 days')
          .and('contain', 'No ratings yet').and('contain', 'Stock checked')
        cy.get(`${UI.offerRow}[data-offer-id="${D.b}"]`).should('contain', B_NAME).and('contain', 'Rs. 51,500').and('contain', '6 months')
      })
    step('Press the browser\'s Back button.', 'The same search results come back.', () => {
      cy.go('back')
      cy.contains(UI.productCard, model).should('be.visible')
    })
    cleanup('None: the next MKT-1d cases use these two offers.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1d-02', slice: 'MKT-1d', title: 'The customer chooses the order',
    persona: 'Customer (incognito window)', reqs: ['MKT-R7.1', 'MKT-R18.4'], pre: 'As M-1d-01.', auto: ['MKT-1d-02', 'MKT-1d-05'] }, (step, call, cleanup) => {
    const first = (id) => cy.get(UI.offerRow).first().should('have.attr', 'data-offer-id', String(id))
    step('On the product page open "Sort sellers by".',
      'Five choices: Recommended, Lowest price, Fastest delivery, Longest warranty, Longest returns. Nearest, rating and promotion are NOT offered: there is no data behind them yet.', () => {
        customer()
        cy.visit(page(`product=${D.product}&city=Karachi`))
        cy.get(`${UI.sort} option`).then(($o) => expect([...$o].map((o) => o.textContent.trim()))
          .to.deep.eq(['Recommended', 'Lowest price', 'Fastest delivery', 'Longest warranty', 'Longest returns']))
      })
    step('Choose "Lowest price".', `${B_NAME} (Rs. 51,500) is first.`, () => { cy.get(UI.sort).select('LOWEST_PRICE'); first(D.b) })
    step('Choose "Fastest delivery".', `${A_NAME} (4 hours) is first.`, () => { cy.get(UI.sort).select('FASTEST'); first(D.a) })
    step('Choose "Longest warranty".', `${A_NAME} (12 months) is first.`, () => { cy.get(UI.sort).select('WARRANTY'); first(D.a) })
    step('Choose "Lowest price" again and reload the page.',
      `Still "Lowest price" with ${B_NAME} first: the choice lives in the address (…&sort=LOWEST_PRICE).`, () => {
        cy.get(UI.sort).select('LOWEST_PRICE')
        cy.url().should('include', 'sort=LOWEST_PRICE')
        cy.reload()
        cy.get(UI.sort).should('have.value', 'LOWEST_PRICE')
        first(D.b)
      })
    step('Copy the address into another browser (here: cookies cleared) and open it.', `The same order: "Lowest price", ${B_NAME} first.`, () => {
      cy.url().then((u) => { customer(); cy.visit(u) })
      cy.get(UI.sort).should('have.value', 'LOWEST_PRICE')
      first(D.b)
    })
    step('Change the address to …&sort=<script> and open it.', 'The page opens on "Recommended" with both sellers. No error page.', () => {
      cy.visit(page(`product=${D.product}&city=Karachi&sort=${encodeURIComponent('<script>')}`))
      cy.get(UI.sort).should('have.value', 'RECOMMENDED')
      cy.get(UI.offerRow).should('have.length', 2)
    })
    cleanup('Nothing was changed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-1d-03', slice: 'MKT-1d', title: 'Nothing is chosen for the customer',
    persona: 'Customer (incognito window), keyboard', reqs: ['MKT-R7.3', 'MKT-R7.1'], pre: 'As M-1d-01, sorted by Lowest price.', auto: ['MKT-1d-03'] }, (step, call, cleanup) => {
    step('Open the product sorted by Lowest price. Look at the button at the bottom before choosing.',
      'No seller is selected. The button reads "Choose a seller first" and is disabled.', () => {
        customer()
        cy.visit(page(`product=${D.product}&city=Karachi&sort=LOWEST_PRICE`))
        cy.get(UI.chooseOffer).should('not.be.checked')
        cy.get(UI.buyButton).should('be.disabled').and('have.text', 'Choose a seller first')
      })
    step(`Using the keyboard (Tab to the list, then Space), choose ${A_NAME} — the dearer one.`,
      `${A_NAME}'s row is selected; the button reads "Buy from ${A_NAME}".`, () => {
        cy.get(`${UI.offerRow}[data-offer-id="${D.a}"] ${UI.chooseOffer}`).focus().check()
        cy.get(UI.buyButton).should('be.enabled').and('have.text', `Buy from ${A_NAME}`)
      })
    step('Press the button.',
      `The checkout opens for ${A_NAME}: Total Rs. 52,000, and "Cash on delivery: you pay the seller when the order arrives. Nothing is charged now."`, () => {
        cy.get(UI.buyButton).click()
        cy.get('#mktCoSeller').should('have.text', A_NAME)
        cy.get('#mktCoTotal').should('have.text', 'Rs. 52,000')
        cy.get('#mktCheckoutView').should('contain', 'Cash on delivery: you pay the seller when the order arrives. Nothing is charged now.')
      })
    cleanup('Close the window: nothing is placed until "Place order".', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-1d-04', slice: 'MKT-1d', title: 'A city with no seller says so',
    persona: 'Customer (incognito window)', reqs: ['MKT-R7.6', 'MKT-R20.1'], pre: 'As M-1d-01: both offers serve Karachi only.', auto: ['MKT-1d-04'] }, (step, call, cleanup) => {
    step('On the product page change the City box to "Lahore".',
      '"No seller delivers this product to Lahore yet." No rows; the button stays disabled.', () => {
        customer()
        cy.visit(page(`product=${D.product}&city=Karachi`))
        cy.get(UI.offerRow).should('have.length', 2)          // the page has loaded, as a person would see
        cy.get('#mktOfferCity').clear().type('Lahore')
        cy.contains('No seller delivers this product to Lahore yet').should('be.visible')
        cy.get(UI.offerRow).should('not.exist')
        cy.get(UI.buyButton).should('be.disabled')
      })
    step(`Go to the search, City "Lahore", search "Galaxy A32 T${run}d".`,
      'No card for the phone, and "No products match in Lahore. Try fewer words or another city." No error.', () => {
        cy.visit(UI.publicPage)
        cy.get(UI.city).clear().type('Lahore')
        cy.get(UI.search).clear().type(`Galaxy A32 T${run}d{enter}`)
        cy.contains('No products match in Lahore. Try fewer words or another city.').should('be.visible')
      })
    cleanup('Nothing was changed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-1d-05', slice: 'MKT-1d', title: 'A paused offer leaves the card and the table together',
    persona: 'owner.mobile@myplus.com (Mobile Distributor) and a customer', reqs: ['MKT-R7.6', 'MKT-R5.4', 'MKT-R18.2', 'MKT-R18.5'], pre: 'As M-1d-01.', auto: ['MKT-1d-07'] }, (step, call, cleanup) => {
    const model = `Galaxy A32 T${run}d`
    step('As owner.mobile@myplus.com: Sale → Marketplace → My offers → Pause on the Galaxy A32 offer.', 'The offer reads "Paused" and its button "Resume".', () => {
      as(SELLER_B)
      openMarketplace()
      cy.get(`${UI.offersTable} tr[data-offer-id="${D.b}"] .mkt-pause`).should('have.text', 'Pause').click()
      cy.get(`${UI.offersTable} tr[data-offer-id="${D.b}"]`).should('contain', 'Paused').find('.mkt-pause').should('have.text', 'Resume')
    })
    step(`Customer: search "${model}" in Karachi and open the product.`,
      `The card reads "From Rs. 52,000 · Available from 1 seller"; the product page lists only ${A_NAME}. The card's number always equals the rows on the page.`, () => {
        customer()
        cy.visit(page(`q=${encodeURIComponent(model)}&city=Karachi`))
        cy.contains(UI.productCard, model).within(() => {
          cy.get(UI.fromPrice).should('have.text', 'From Rs. 52,000')
          cy.get(UI.offerCount).should('have.text', 'Available from 1 seller')
        })
        cy.contains(UI.productCard, model).find('a').click()
        cy.get(UI.offerRow).should('have.length', 1).and('contain', A_NAME)
      })
    cleanup('As owner.mobile@: My offers → Resume.', 'Both sellers are back on the card and the page.', () => {
      as(SELLER_B)
      openMarketplace()
      cy.get(`${UI.offersTable} tr[data-offer-id="${D.b}"] .mkt-pause`).should('have.text', 'Resume').click()
      cy.get(`${UI.offersTable} tr[data-offer-id="${D.b}"] .mkt-pause`).should('have.text', 'Pause')
    })
  })

  walk({ id: 'M-1d-06', slice: 'MKT-1d', title: 'The operator chooses the order customers see first',
    persona: 'admin@myplus.com (operator), then a customer and owner.business@', reqs: ['MKT-R7.4', 'MKT-R18.4'], pre: 'As M-1d-01.', auto: ['MKT-1d-08'] }, (step, call, cleanup) => {
    step('As admin@myplus.com: Platform → Marketplace policies → "Order customers see first" → Lowest price → Save.', 'Saved.', () => {
      console_('#platMktPoliciesBtn')
      cy.get('#mktDefaultSort').select('LOWEST_PRICE')
      cy.get('#mktDefaultSortSave').click()
      cy.get('#mktDefaultSortMsg').should('not.be.empty').and('not.contain', 'failed')
    })
    step('Customer: open the product WITHOUT choosing a sort.', `"Lowest price" is selected and ${B_NAME} is first. The customer can still change it.`, () => {
      customer()
      cy.visit(page(`product=${D.product}&city=Karachi`))
      cy.get(UI.sort).should('have.value', 'LOWEST_PRICE')
      cy.get(UI.offerRow).first().should('have.attr', 'data-offer-id', String(D.b))
    })
    step('Developer tools, as owner.business@ (a shop, not the operator): POST /platform/mkt/defaultSort {"sort":"FASTEST"}.',
      'Refused. The setting stays "Lowest price".', () => {
        as(SELLER_A)
        call('POST /platform/mkt/defaultSort (as a shop)', post(API.defaultSort, { sort: 'FASTEST' }))
          .then((r) => expect(r.status === 403 || !ok(r.body)).to.eq(true))
        asOperator()
        call('GET /platform/mkt/defaultSort (as operator)', get(API.defaultSort)).then((r) => expect(data(r.body).sort).to.eq('LOWEST_PRICE'))
      })
    cleanup('As admin@: "Order customers see first" → Recommended → Save.', 'Saved; new visitors see Recommended again.', () => {
      console_('#platMktPoliciesBtn')
      cy.get('#mktDefaultSort').select('RECOMMENDED')
      cy.get('#mktDefaultSortSave').click()
      cy.get('#mktDefaultSortMsg').should('not.be.empty')
      get(API.defaultSort).then((r) => expect(data(r.body).sort).to.eq('RECOMMENDED'))
    })
  })

  walk({ id: 'M-1d-07', slice: 'MKT-1d', title: 'Works in Urdu, on a phone, and keeps the page when the language changes',
    persona: 'Customer on a phone (375 px wide)', reqs: ['MKT-R7.6', 'MKT-R7.2'], pre: 'As M-1d-01.', auto: ['i18n bundles (2865 keys × 6)'] }, (step, call, cleanup) => {
    step('On a phone, open the product page with ?lang=ur.',
      'The page reads right to left (dir="rtl"), in Urdu. Nothing scrolls sideways.', () => {
        cy.viewport(375, 812)
        customer()
        cy.visit(page(`product=${D.product}&city=Karachi&lang=ur`))
        cy.get('html').should('have.attr', 'dir', 'rtl')
        cy.get(UI.offerRow).should('have.length', 2)
        cy.document().then((d) => expect(d.documentElement.scrollWidth, 'no sideways scroll').to.be.at.most(375))
      })
    step('Press "English" in the language links at the top.',
      'The SAME product page opens in English (left to right), with the same two sellers — the language link keeps the page.', () => {
        cy.get('.langs a[data-lang="en"]').click()
        cy.get('html').should('have.attr', 'dir', 'ltr')
        cy.url().should('include', `product=${D.product}`)
        cy.get(UI.offerRow).should('have.length', 2)
      })
    step('Run an accessibility check on the page (axe).', 'No serious or critical violations: every control has a name, contrast is sufficient.', () => {
      cy.injectAxe()
      cy.checkA11y(null, { includedImpacts: ['serious', 'critical'] }, (v) => cy.task('a11yLog',
        v.map((x) => `${x.impact} ${x.id}: ${x.help} — ${x.nodes.map((n) => n.target.join(' ')).join(' | ')}`).join('\n')))
    })
    cleanup('Nothing was changed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-1d-08', slice: 'MKT-1d', title: 'Search text is just text',
    persona: 'Customer (incognito window)', reqs: ['MKT-R7.6'], pre: 'As M-1d-01.', auto: ['MKT-1d-09', 'MKT-1d-10'] }, (step, call, cleanup) => {
    const search = (q) => {
      cy.visit(UI.publicPage)
      cy.get(UI.city).clear().type('Karachi')
      cy.get(UI.search).clear().type(q, { parseSpecialCharSequences: false })   // typed literally, braces included
      cy.get(UI.search).type('{enter}')
    }
    ;['%', '_', "' OR 1=1 --", '%{enter}'].forEach((q) => step(`Search for: ${q}`, 'Answers normally ("No products match in Karachi…"); it does NOT list every product.', () => {
      customer()
      search(q)
      cy.contains('No products match in Karachi').should('be.visible')
    }))
    step('Open /marketplace?product=999999999', '"No such product." No error page.', () => {
      cy.visit(page('product=999999999'))
      cy.contains('No such product.').should('be.visible')
    })
    cleanup('Nothing was changed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  // ──────────────────────────────── MKT-1e ────────────────────────────────

  const phone = (k) => `0300${String(run).slice(-6)}${k}`        // a fresh number per case: 3 waiting orders per phone
  const IMEI = (k) => `35${String(run).slice(-12)}${k}`           // 15 digits, unique per recording
  const buy = (offerId, { name = 'Ali', ph, address = '1 Clifton', qty } = {}) => {
    cy.get(`${UI.offerRow}[data-offer-id="${offerId}"] ${UI.chooseOffer}`).check()
    cy.get(UI.buyButton).click()
    cy.get('#mktCoName').clear().type(name)
    cy.get('#mktCoPhone').clear().type(ph)
    cy.get('#mktCoAddress').clear().type(address)
    if (qty) cy.get('#mktCoQty').select(String(qty))
  }
  const incoming = (status) => get(`${API.incomingOrders}?status=${status}&size=100`)

  walk({ id: 'M-1e-01', slice: 'MKT-1e', title: 'Buying waits for the seller, never pretends; the sale lands in the seller\'s books',
    persona: 'Customer (incognito window), then owner.business@myplus.com', reqs: ['MKT-R10.2', 'MKT-R18.5', 'MKT-R10.1', 'MKT-R1.3'],
    pre: 'As M-1d-01 (Shahzad Mobile Shop Live at Rs 52,000 in Karachi). The acceptance window is 5 minutes.', auto: ['MKT-1e-01'] }, (step, call, cleanup) => {
    cy.then(() => { asOperator(); post(API.acceptWindow, { minutes: 5 }) })
    step(`Customer: open the product (Karachi), choose ${A_NAME}, press "Buy from ${A_NAME}". Name "Ali", phone ${phone(1)}, address "1 Clifton". Check the total.`,
      'Total Rs. 52,000. The payment line reads "Cash on delivery: you pay the seller when the order arrives. Nothing is charged now."', () => {
        customer()
        cy.visit(page(`product=${D.product}&city=Karachi`))
        buy(D.a, { ph: phone(1) })
        cy.get('#mktCoTotal').should('have.text', 'Rs. 52,000')
      })
    step('Press "Place order".',
      `"Waiting for ${A_NAME} to confirm", an order number MKT-…, and "${A_NAME} has 4:5x to confirm. Your stock is held." The address bar shows ?order=MKT-… and NO phone number.`, () => {
        cy.get('#mktCoPlace').click()
        cy.get(UI.checkoutStatus).should('contain', `Waiting for ${A_NAME} to confirm`)
        cy.get('#mktOrderDetail').invoke('text').should('match', /has [0-5]:[0-5]\d to confirm\. Your stock is held\./)
        cy.get('#mktCoOrderNo').invoke('text').should('match', /^MKT-\d+/).then((no) => { D.o1 = no })
        cy.url().should('include', 'order=MKT-').and('not.include', phone(1))
      })
    step(`As owner.business@myplus.com: Sale → Marketplace → "Incoming marketplace orders" ("Waiting for you").`,
      'The order is listed with the items, Ali, the phone and address, and a countdown under 5:00 running down.', () => {
        as(SELLER_A)
        openMarketplace()
        cy.contains(`${UI.incoming} tr`, D.o1).should('contain', 'Ali').and('contain', phone(1))
          .find(UI.countdown).invoke('text').should('match', /^[0-4]:[0-5]\d$/)
      })
    step('Press Accept.',
      'The row turns to "Accepted" with "Invoice INV-…", "In your orders as …" and "Accepted. The sale is in your books; deliver and collect the cash."', () => {
        cy.contains(`${UI.incoming} tr`, D.o1).find(UI.acceptBtn).click()
        cy.contains(`${UI.incoming} tr`, D.o1).should('contain', 'Accepted').and('contain', 'Invoice')
          .and('contain', 'In your orders as').and('contain', 'The sale is in your books')
      })
    step('Customer: reopen the order page (the same address, with the phone number) and wait up to 10 seconds.',
      `"Confirmed by ${A_NAME}" and "${A_NAME} will deliver and collect Rs. 52,000 in cash."`, () => {
        customer()
        cy.visit(page(`order=${encodeURIComponent(D.o1)}&phone=${phone(1)}`))
        cy.get(UI.checkoutStatus, { timeout: 15000 }).should('contain', `Confirmed by ${A_NAME}`)
        cy.get('#mktOrderDetail').should('contain', 'will deliver and collect Rs. 52,000 in cash')
      })
    cleanup('None: an accepted sale is a real sale in the seller\'s books (return it with the store\'s Sale Returns if needed).', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1e-02', slice: 'MKT-1e', title: 'One checkout is one seller while the operator has not switched on baskets',
    persona: 'Customer (incognito window), developer tools', reqs: ['MKT-R20.1', 'MKT-R17.1', 'MKT-R20.2'], pre: 'As M-1d-01.', auto: ['MKT-1e-09'] }, (step, call, cleanup) => {
    step(`On the product page choose ${A_NAME}, then ${B_NAME}.`, `Only one can be chosen (radio buttons): choosing ${B_NAME} unchooses ${A_NAME}; the button reads "Buy from ${B_NAME}".`, () => {
      customer()
      cy.visit(page(`product=${D.product}&city=Karachi`))
      cy.get(`${UI.offerRow}[data-offer-id="${D.a}"] ${UI.chooseOffer}`).check()
      cy.get(`${UI.offerRow}[data-offer-id="${D.b}"] ${UI.chooseOffer}`).check()
      cy.get(`${UI.offerRow}[data-offer-id="${D.a}"] ${UI.chooseOffer}`).should('not.be.checked')
      cy.get(UI.buyButton).should('have.text', `Buy from ${B_NAME}`)
    })
    step(`Developer tools: POST /marketplace/public/checkout with a basket of both offers: "lines": [{offerId: <${A_NAME}'s offer>}, {offerId: <${B_NAME}'s offer>}]. (Operator: Platform → Marketplace policies → "Customers can buy from several sellers in one order" is OFF, the default.)`,
      `Refused: "Items from different sellers must be checked out separately." Nothing is held or ordered. A multi-seller basket exists only when the operator switches it on (M-2a-01).`, () => {
        cy.then(() => { asOperator(); post(API.acceptWindow, { multiSeller: false }) })
        cy.then(() => { customer(); cy.visit(UI.publicPage) })
        call('POST /marketplace/public/checkout (two sellers)', post(API.checkout, { customerName: 'Ali', customerPhone: phone(2), address: '1 Clifton',
          city: 'Karachi', idempotencyKey: `w-${run}-2`, lines: [{ offerId: D.a, quantity: 1, expectedPrice: 52000 }, { offerId: D.b, quantity: 1, expectedPrice: 51500 }] }))
          .then((r) => {
            expect(ok(r.body), JSON.stringify(r.body)).to.eq(false)
            expect(msg(r.body)).to.include('Items from different sellers must be checked out separately.')
          })
      })
    cleanup('Nothing was ordered or held.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-1e-03', slice: 'MKT-1e', title: 'The seller rejects: the stock comes back and the customer is told',
    persona: 'Two customers (two incognito windows), then owner.business@', reqs: ['MKT-R10.2', 'MKT-R10.5', 'MKT-R10.3'],
    pre: `${A_NAME} has a second Live offer with exactly 2 in stock (another phone, its own product).`, auto: ['MKT-1e-04'] }, (step, call, cleanup) => {
    cy.then(() => publishOffer(SELLER_A, { run: `${run}h`, price: 52000, qty: 2, warrantyPolicyId: D.w12, returnPolicyId: D.r7 }))
      .then((o) => { D.small = o })
    step(`Customer 1 (phone ${phone(3)}): open that product, choose ${A_NAME}, Quantity 2, Place order.`, `"Waiting for ${A_NAME} to confirm". Both units are now held.`, () => {
      customer()
      cy.visit(page(`product=${D.small.mktProductId}&city=Karachi`))
      buy(D.small.offerId, { ph: phone(3), qty: 2 })
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', 'Waiting for')
      cy.get('#mktCoOrderNo').invoke('text').then((no) => { D.o3 = no })
    })
    step(`Customer 2 (another window, phone ${phone(4)}): the same product, Quantity 1, Place order.`,
      'Refused on the checkout: "This seller no longer has enough stock. Please choose another offer." Nothing is placed.', () => {
        customer()
        cy.visit(page(`product=${D.small.mktProductId}&city=Karachi`))
        buy(D.small.offerId, { name: 'Sara', ph: phone(4) })
        cy.get('#mktCoPlace').click()
        cy.get('#mktCoError').should('contain', 'This seller no longer has enough stock. Please choose another offer.')
      })
    step(`As owner.business@: Incoming → the order for 2 → leave the reason box EMPTY → Reject.`, 'Refused under the buttons: "Give a reason. MaxTheService support will see it." The order still waits.', () => {
      as(SELLER_A)
      openMarketplace()
      cy.contains(`${UI.incoming} tr`, D.o3).find(UI.rejectBtn).click()
      cy.contains(`${UI.incoming} tr`, D.o3).should('contain', 'Give a reason')
    })
    step('Type "out of stock in store" in the reason box and press Reject.', 'The row turns to "Rejected" with the reason.', () => {
      cy.contains(`${UI.incoming} tr`, D.o3).find('input[placeholder*="cannot fulfil"]').type('out of stock in store')
      cy.contains(`${UI.incoming} tr`, D.o3).find(UI.rejectBtn).click()
      cy.contains(`${UI.incoming} tr`, D.o3).should('contain', 'Rejected').and('contain', 'out of stock in store')
    })
    step('Customer 1: reopen the order page.', '"Cancelled" and "The seller could not fulfil this order."', () => {
      customer()
      cy.visit(page(`order=${encodeURIComponent(D.o3)}&phone=${phone(3)}`))
      cy.get(UI.checkoutStatus).should('contain', 'Cancelled')
      cy.get('#mktOrderDetail').should('contain', 'The seller could not fulfil this order.')
    })
    step('Customer 2: Place order again.', `"Waiting for ${A_NAME} to confirm": the stock came back.`, () => {
      cy.visit(page(`product=${D.small.mktProductId}&city=Karachi`))
      buy(D.small.offerId, { name: 'Sara', ph: phone(4) })
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', 'Waiting for')
      cy.get('#mktCoOrderNo').invoke('text').then((no) => { D.o4 = no })
    })
    cleanup('As owner.business@: Incoming → Reject customer 2\'s order with the reason "walk cleanup".', 'Rejected; its unit is released.', () => {
      as(SELLER_A)
      openMarketplace()
      cy.contains(`${UI.incoming} tr`, D.o4).find('input[placeholder*="cannot fulfil"]').type('walk cleanup')
      cy.contains(`${UI.incoming} tr`, D.o4).find(UI.rejectBtn).click()
      cy.contains(`${UI.incoming} tr`, D.o4).should('contain', 'Rejected')
    })
  })

  walk({ id: 'M-1e-04', slice: 'MKT-1e', title: 'Nobody answers: the hold expires, and a late Accept is refused',
    persona: 'admin@myplus.com, a customer, owner.business@', reqs: ['MKT-R10.2', 'MKT-R19.1', 'MKT-R18.5', 'MKT-R10.5', 'MKT-R7.4'], pre: 'As M-1d-01.', auto: ['MKT-1e-05'] }, (step, call, cleanup) => {
    step('As admin@myplus.com: Platform → Marketplace policies → "Minutes a seller has to accept an order" = 1 → Save.', 'Saved.', () => {
      console_('#platMktPoliciesBtn')
      cy.get('#mktAcceptWindow').clear().type('1')
      cy.get('#mktAcceptWindowSave').click()
      cy.get('#mktAcceptWindowMsg').should('not.be.empty').and('not.contain', 'failed')
    })
    step(`Customer (phone ${phone(5)}): order ${A_NAME}'s phone. The seller does nothing.`, `"${A_NAME} has 0:5x to confirm. Your stock is held."`, (snap) => {
      customer()
      cy.visit(page(`product=${D.product}&city=Karachi`))
      buy(D.a, { ph: phone(5) })
      cy.get('#mktCoPlace').click()
      cy.get('#mktOrderDetail').invoke('text').should('match', /has 0:[0-5]\d to confirm/)
      cy.get('#mktCoOrderNo').invoke('text').then((no) => { D.o5 = no })
      snap()
      as(SELLER_A)
      incoming('OFFERED').then((r) => { D.so5 = list(r.body).find((x) => x.orderNo === D.o5) })   // what the seller's page held
    })
    step('Wait about 2 minutes (the minute, a 30-second grace and one sweep). Reopen the customer\'s order page.',
      '"Cancelled" and "The seller did not confirm in time." The stock is free again.', () => {
        cy.wait(130 * 1000)
        customer()
        cy.visit(page(`order=${encodeURIComponent(D.o5)}&phone=${phone(5)}`))
        cy.get(UI.checkoutStatus).should('contain', 'Cancelled')
        cy.get('#mktOrderDetail').should('contain', 'The seller did not confirm in time.')
      })
    step('As owner.business@: Incoming → choose "All".', 'The order reads "Expired", with no Accept button.', () => {
      as(SELLER_A)
      openMarketplace()
      cy.get('#mktIncomingStatus').select('', { force: true })   // shown as a styled dropdown (bootstrap-select)
      cy.contains(`${UI.incoming} tr`, D.o5).should('contain', 'Expired').find(UI.acceptBtn).should('not.exist')
    })
    step('Developer tools: replay the Accept the seller\'s page would have sent before the expiry (POST /mkt/acceptOrder with the old version).',
      'Refused: "This order expired before it was accepted." — not "someone else changed it".', () => {
        call('POST /mkt/acceptOrder (late)', post(API.acceptOrder, { id: D.so5.id, version: D.so5.version }))
          .then((r) => { expect(ok(r.body)).to.eq(false); expect(msg(r.body)).to.eq('This order expired before it was accepted.') })
      })
    cleanup('As admin@: "Minutes a seller has to accept an order" = 5 → Save.', 'Saved.', () => {
      console_('#platMktPoliciesBtn')
      cy.get('#mktAcceptWindow').clear().type('5')
      cy.get('#mktAcceptWindowSave').click()
      cy.get('#mktAcceptWindowMsg').should('not.be.empty')
      get(API.acceptWindow).then((r) => expect(data(r.body).minutes).to.eq(5))
    })
  })

  walk({ id: 'M-1e-05', slice: 'MKT-1e', title: 'A double click, or a lost answer, is still one order',
    persona: 'Customer (incognito window), developer tools (network)', reqs: ['MKT-R22.3'], pre: 'As M-1d-01.', auto: ['MKT-1e-03', 'MarketplacePublicControllerTest'] }, (step, call, cleanup) => {
    const count = (ph) => incoming('').then((r) => list(r.body).filter((x) => x.customerPhone === ph))
    step(`Fill the checkout for ${A_NAME} (phone ${phone(6)}) and DOUBLE-click "Place order".`, 'One order number. The seller has exactly ONE order for that phone.', (snap) => {
      customer()
      cy.visit(page(`product=${D.product}&city=Karachi`))
      buy(D.a, { ph: phone(6) })
      cy.get('#mktCoPlace').dblclick()
      cy.get('#mktCoOrderNo').invoke('text').should('match', /^MKT-/)
      snap()
      as(SELLER_A)
      count(phone(6)).then((rows) => expect(rows).to.have.length(1))
    })
    step(`Another order (phone ${phone(7)}): the server PLACES it, but the answer is lost on the way back (developer tools: the response replaced by "504 Gateway Timeout").`,
      'The page does not guess: "We could not confirm your order. Press the button again; it will not be placed twice."', () => {
        customer()
        cy.visit(page(`product=${D.product}&city=Karachi`))
        buy(D.a, { ph: phone(7) })
        cy.intercept({ method: 'POST', url: '**/marketplace/public/checkout', times: 1 },
          (req) => req.continue((res) => { res.send({ statusCode: 504, body: 'Gateway Timeout' }) })).as('lost')
        cy.get('#mktCoPlace').click()
        cy.wait('@lost')
        cy.get('#mktCoError').should('have.text', 'We could not confirm your order. Press the button again; it will not be placed twice.')
      })
    step('Press "Place order" again.', 'The order the server had already placed is shown — the SAME one: the seller has exactly ONE order for that phone.', (snap) => {
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', 'Waiting for')
      snap()
      as(SELLER_A)
      count(phone(7)).then((rows) => expect(rows, 'not placed twice').to.have.length(1))
    })
    cleanup('As owner.business@: Reject both orders with the reason "walk cleanup".', 'Their stock is released.', () => {
      ;[phone(6), phone(7)].forEach((ph) => count(ph).then((rows) => rows.filter((x) => x.acceptanceStatus === 'OFFERED').forEach((so) =>
        call('POST /mkt/rejectOrder', post(API.rejectOrder, { id: so.id, version: so.version, reason: 'walk cleanup' }))
          .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)))))
    })
  })

  walk({ id: 'M-1e-06', slice: 'MKT-1e', title: 'The order keeps its own copy of the terms',
    persona: 'Customer and admin@myplus.com', reqs: ['MKT-R13.3', 'MKT-R3.1', 'MKT-R3.2'],
    pre: `An order placed on an offer whose policies are its own ("12 months · Samsung Pakistan", "7 days").`, auto: ['MKT-1e-06'] }, (step, call, cleanup) => {
    cy.then(() => {
      seedPolicies(`${run}p`).then((p) => { D.own = p })
      cy.then(() => publishOffer(SELLER_A, { run: `${run}p`, price: 52000, promiseHours: 4, warrantyPolicyId: D.own.warranty, returnPolicyId: D.own.returns }))
        .then((o) => { D.p = o })
    })
    step(`Customer (phone ${phone(8)}): order that phone.`, 'The order page lists the line with "12 months warranty · Samsung Pakistan · Returns within 7 days" and Rs. 52,000.', () => {
      customer()
      cy.visit(page(`product=${D.p.mktProductId}&city=Karachi`))
      buy(D.p.offerId, { ph: phone(8) })
      cy.get('#mktCoPlace').click()
      cy.get('#mktOrderLines').should('contain', '12 months warranty · Samsung Pakistan · Returns within 7 days')
      cy.get('#mktCoOrderNo').invoke('text').then((no) => { D.o8 = no })
    })
    step('As admin@myplus.com: Marketplace policies → Deactivate that "7 days" return policy.', 'It turns grey.', () => {
      console_('#platMktPoliciesBtn')
      cy.get(`#mktPolicyTable tr[data-policy-id="${D.own.returns}"]`).contains('button', 'Deactivate').click()
      cy.get(`#mktPolicyTable tr[data-policy-id="${D.own.returns}"]`).should('have.class', 'text-muted')
    })
    step('Customer: reopen the order page.', 'Unchanged: "… Returns within 7 days", Rs. 52,000 — the terms were copied onto the order when it was placed.', () => {
      customer()
      cy.visit(page(`order=${encodeURIComponent(D.o8)}&phone=${phone(8)}`))
      cy.get('#mktOrderLines').should('contain', 'Returns within 7 days').and('contain', '52,000')
    })
    step('Developer tools: compare the customer\'s order (GET /marketplace/public/orders/…) with the seller\'s incoming order (GET /mkt/incomingOrders).',
      'Only the SELLER sees the commission it was charged under (commissionBasis ITEMS); the customer\'s order has no commission field.', () => {
        call('GET customer order', get(API.trackOrder(D.o8, phone(8)))).then((r) =>
          expect(data(r.body).lines[0]).to.not.have.any.keys('commissionBasis', 'commissionRate'))
        as(SELLER_A)
        call('GET /mkt/incomingOrders', incoming('OFFERED')).then((r) =>
          expect(list(r.body).find((x) => x.orderNo === D.o8).lines[0].commissionBasis).to.eq('ITEMS'))
      })
    cleanup('As owner.business@: Reject the order with "walk cleanup".', 'Released.', () => {
      incoming('OFFERED').then((r) => {
        const so = list(r.body).find((x) => x.orderNo === D.o8)
        call('POST /mkt/rejectOrder', post(API.rejectOrder, { id: so.id, version: so.version, reason: 'walk cleanup' }))
      })
    })
  })

  walk({ id: 'M-1e-08', slice: 'MKT-1e', title: 'Order, payment and the seller\'s answer are separate facts',
    persona: 'admin@myplus.com (operator)', reqs: ['MKT-R19.1', 'MKT-R20.1', 'MKT-R22.1'], pre: 'Orders in each state exist (M-1e-01, -03, -04).', auto: ['MKT-1e-09'] }, (step, call, cleanup) => {
    cy.then(() => {   // one order waiting right now, so the first list has something in it
      customer()
      cy.visit(UI.publicPage)                                  // the page hands out the security token the POST needs
      post(API.checkout, { offerId: D.a, quantity: 1, expectedPrice: 52000, customerName: 'Bilal', customerPhone: phone(11),
        address: '2 Clifton', city: 'Karachi', idempotencyKey: `w-${run}-11` }).then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        D.o11 = data(r.body)
      })
    })
    step('As admin@myplus.com: Platform → "Marketplace orders" (opens on "Waiting for a seller").', 'Only waiting orders, each with its number and total, the seller, the items and the city (here: Bilal\'s order to Karachi).', () => {
      console_('#platMktOrdersBtn')
      cy.get('#mktOrderList tbody tr').should('have.length.greaterThan', 0)
      cy.get('#mktOrderList tbody tr').each(($r) => expect($r.text()).to.contain('Waiting'))
      cy.get(`#mktOrderList tr[data-order-no="${D.o11.orderNo}"]`).should('contain', A_NAME).and('contain', 'Karachi')
    })
    step('Choose "Confirmed".', `M-1e-01's order is listed, by ${A_NAME}.`, () => {
      cy.contains('#platMktOrderStatus button', 'Confirmed').click()
      cy.get(`#mktOrderList tr[data-order-no="${D.o1}"]`).should('contain', A_NAME)
    })
    step('Choose "Cancelled".', 'M-1e-03\'s order shows "The seller could not fulfil this order."; M-1e-04\'s shows "The seller did not confirm in time."', () => {
      cy.contains('#platMktOrderStatus button', 'Cancelled').click()
      cy.get(`#mktOrderList tr[data-order-no="${D.o3}"]`).should('contain', 'The seller could not fulfil this order.')
      cy.get(`#mktOrderList tr[data-order-no="${D.o5}"]`).should('contain', 'The seller did not confirm in time.')
    })
    step('Developer tools: GET /platform/mkt/orders?status=CONFIRMED, read M-1e-01\'s order.', 'status CONFIRMED, paymentMode COD, paymentStatus UNPAID: cash is collected at delivery; settlement is MKT-1g.', () => {
      call('GET /platform/mkt/orders', get(`${API.operatorOrders}?status=CONFIRMED&size=100`)).then((r) => {
        const o = list(r.body).find((x) => x.orderNo === D.o1)
        expect(o.status).to.eq('CONFIRMED')
        expect(o.paymentMode).to.eq('COD')
        expect(o.paymentStatus).to.eq('UNPAID')
      })
    })
    step('As owner.business@ (a shop): GET /platform/mkt/orders.', 'Refused: a shop never sees the platform\'s order list.', () => {
      as(SELLER_A)
      call('GET /platform/mkt/orders (as a shop)', get(API.operatorOrders)).then((r) => expect(r.status === 403 || !ok(r.body)).to.eq(true))
    })
    cleanup('As owner.business@: Reject Bilal\'s order with "walk cleanup".', 'Released.', () => {
      call('POST /mkt/rejectOrder', post(API.rejectOrder, { id: D.o11.sellerOrderId, version: D.o11.sellerOrderVersion, reason: 'walk cleanup' }))
        .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    })
  })

  walk({ id: 'M-1e-09', slice: 'MKT-1e', title: 'Phase 1 is cash on delivery; a seller who takes no cash is not offered',
    persona: 'Customer, and owner.business@', reqs: ['MKT-R20.1'], pre: 'As M-1d-01.', auto: ['MarketplaceOrderFlowTest.codOffRefused'] }, (step, call, cleanup) => {
    step('Customer: open the checkout for any seller.', '"Cash on delivery: you pay the seller when the order arrives. Nothing is charged now." No card or wallet choice.', () => {
      customer()
      cy.visit(page(`product=${D.product}&city=Karachi`))
      cy.get(`${UI.offerRow}[data-offer-id="${D.a}"] ${UI.chooseOffer}`).check()
      cy.get(UI.buyButton).click()
      cy.get('#mktCheckoutView').should('contain', 'Cash on delivery: you pay the seller when the order arrives. Nothing is charged now.')
    })
    step.flow('The seller switches cash on delivery OFF (store delivery setting "Accept cash on delivery", key order.payment.codEnabled), then a customer tries to order from them.',
      'Refused at "Place order": "This seller does not accept cash on delivery yet. Please choose another offer." Nothing is held.',
      'SCREEN NOT AVAILABLE: the setting lives in marketplace-service and has no screen in the business dashboard yet (Configuration lists business-service and auth settings only). Written from MarketplaceCheckoutService.checkout (line 137).',
      'Unit test MarketplaceOrderFlowTest.codOffRefused (added 2026-10-03: this refusal had no test before).')
    cleanup('Nothing was changed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  walk({ id: 'M-1e-10', slice: 'MKT-1e', title: 'Phones need their IMEI; nothing is let go before it is given',
    persona: 'owner.business@myplus.com', reqs: ['MKT-R10.2', 'MKT-R10.3', 'MKT-R10.4', 'MKT-R10.1'],
    pre: 'Shahzad Mobile Shop has a Live phone offer whose product is set to "requires serial number" (Products → edit → "Track serial numbers"), and RECEIVED two of those phones with their IMEIs on a purchase (Purchase → New purchase, IMEIs typed in). A customer has ordered 2.',
    auto: ['MarketplaceOrderFlowTest (serials)'] }, (step, call, cleanup) => {
    cy.then(() => publishOffer(SELLER_A, { run: `${run}s`, price: 52000, qty: 5, warrantyPolicyId: D.w12, returnPolicyId: D.r7 }))
      .then((o) => { D.s = o })
    cy.then(() => {
      as(SELLER_A)
      cy.request({ method: 'POST', url: '/setProductTracking', form: true, body: { id: D.s.sourceProductId, requiresSerial: true } })
        .then((r) => expect(r.body && r.body.success !== false, JSON.stringify(r.body)).to.eq(true))
      // the two phones arrive WITH their IMEIs, as a shop receives them: only an IMEI in stock can be sold
      cy.request({ method: 'POST', url: '/addPurchase', form: true, body: { productId: D.s.sourceProductId, quantity: 2,
        purchaseRate: 40000, 'stock.bpurchaseRate': 40000, 'stock.bsellRate': 52000, totalAmount: 80000, netAmount: 80000,
        paidAmount: 80000, purchaseInvoiceNo: `WALK-${run}`, serials: `${IMEI(1)},${IMEI(2)}`, conditionGrade: 'NEW' } })
        .then((r) => expect(r.body && r.body.status !== 'ERROR' && r.body.success !== false, JSON.stringify(r.body)).to.eq(true))
      customer()
      cy.visit(UI.publicPage)
      post(API.checkout, { offerId: D.s.offerId, quantity: 2, expectedPrice: 52000, customerName: 'Hamza', customerPhone: phone(12),
        address: '3 Clifton', city: 'Karachi', idempotencyKey: `w-${run}-12` }).then((r) => {
        expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
        D.o12 = data(r.body)
      })
    })
    const row = () => cy.contains(`${UI.incoming} tr`, D.o12.orderNo)
    step('As owner.business@: Incoming → the order for 2 → press Accept without typing any IMEI.',
      'Refused under the buttons: "Enter the serial number (IMEI) of each unit you are sending: 2 for …". The order still waits; the stock stays held.', () => {
        as(SELLER_A)
        openMarketplace()
        row().find('.mkt-imei').should('have.length', 2)
        row().find(UI.acceptBtn).click()
        row().should('contain', 'Enter the serial number (IMEI) of each unit you are sending: 2 for')
      })
    step(`Type ONE IMEI (${IMEI(1)}) and press Accept.`, 'The same refusal: two units need two IMEIs.', () => {
      row().find('.mkt-imei').first().type(IMEI(1))
      row().find(UI.acceptBtn).click()
      row().should('contain', 'Enter the serial number (IMEI) of each unit you are sending: 2 for')
    })
    step(`Type the second IMEI (${IMEI(2)}) and press Accept.`, 'Accepted: "Invoice INV-…"; the invoice records those two IMEIs as sold. (An IMEI the shop never received is refused: "The sale could not be recorded: Serial … is not in stock." — and the order keeps waiting, stock held.)', () => {
      row().find('.mkt-imei').eq(1).type(IMEI(2))
      row().find(UI.acceptBtn).click()
      row().should('contain', 'Accepted').and('contain', 'Invoice')
    })
    cleanup('None: the sale is real. Undo it through the store\'s Sale Returns if needed.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1e-11', slice: 'MKT-1e', title: 'No one can hold a shop\'s stock hostage; an expired page says so',
    persona: 'Customer (incognito window)', reqs: ['MKT-R22.3', 'MKT-R22.1'], pre: 'As M-1d-01.', auto: ['MKT-1e-10', 'MKT-1e-08'] }, (step, call, cleanup) => {
    const ph = phone(9)
    const place = (p) => {
      customer()
      cy.visit(page(`product=${D.product}&city=Karachi`))
      buy(D.a, { ph: p })
      cy.get('#mktCoPlace').click()
    }
    step(`With phone ${ph}, place 3 orders and leave them unanswered.`, 'Each reads "Waiting for … to confirm".', () => {
      ;[1, 2, 3].forEach(() => { place(ph); cy.get(UI.checkoutStatus).should('contain', 'Waiting for') })
    })
    step(`Place a 4th with the SAME number written differently: (${ph.slice(0, 4)}) ${ph.slice(4)}.`,
      'Refused: "You already have 3 orders waiting for sellers to confirm. Please wait for an answer first."', () => {
        place(`(${ph.slice(0, 4)}) ${ph.slice(4)}`)
        cy.get('#mktCoError').should('contain', 'You already have 3 orders waiting for sellers to confirm. Please wait for an answer first.')
      })
    step(`Place one with a different number (${phone(0)}).`, 'Placed: the limit is per number, not per shop.', () => {
      place(phone(0))
      cy.get(UI.checkoutStatus).should('contain', 'Waiting for')
    })
    step('Open the checkout, then let the page\'s security token lapse (developer tools: delete the XSRF-TOKEN cookie) and press "Place order".',
      '"This page expired. Please reload it and try again." Nothing is placed.', () => {
        customer()
        cy.visit(page(`product=${D.product}&city=Karachi`))
        buy(D.a, { ph: phone(10) })
        cy.clearCookie('XSRF-TOKEN')
        cy.get('#mktCoPlace').click()
        cy.get('#mktCoError').should('have.text', 'This page expired. Please reload it and try again.')
      })
    cleanup('As owner.business@: Reject the 4 waiting orders with "walk cleanup".', 'Their stock is released.', () => {
      as(SELLER_A)
      incoming('OFFERED').then((r) => list(r.body).filter((x) => [ph, phone(0)].includes(x.customerPhone)).forEach((so) =>
        call('POST /mkt/rejectOrder', post(API.rejectOrder, { id: so.id, version: so.version, reason: 'walk cleanup' }))
          .then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))))
    })
  })

  // ──────────────────────────────── MKT-1e2 ────────────────────────────────

  const E = {}
  const PW2 = 'Shop!ng2026'
  const cph = (k) => `0312${String(run).slice(-6)}${k}`            // customer phones for the 1e2 walk
  const signIn = (ph) => {
    customer()
    cy.visit(UI.publicPage)
    cy.get('#mktAccountBtn').click()
    cy.get('#mktAccPhone').clear().type(ph)
    cy.get('#mktAccPassword').clear().type(PW2)
    cy.get('#mktAccSubmit').click()
    cy.get('#mktAccOrdersBox').should('be.visible')
  }
  const buyAs = (offerId, ph, { cardToken } = {}) => {
    cy.visit(page(`product=${E.product}&city=Karachi`))
    cy.get(`${UI.offerRow}[data-offer-id="${offerId}"] ${UI.chooseOffer}`).check()
    cy.get(UI.buyButton).click()
    cy.get('#mktCoName').clear().type('Ali Raza')
    cy.get('#mktCoPhone').clear().type(ph)
    cy.get('#mktCoAddress').clear().type('1 Clifton')
    if (cardToken) {
      cy.get('#mktPayCard').check()
      cy.get('#mktCardToken').clear().type(cardToken)
    }
    cy.get('#mktCoPlace').click()
  }

  walk({ id: 'M-1e2-01', slice: 'MKT-1e2', title: 'A shopper creates an account with phone and password',
    persona: 'Customer (incognito window)', reqs: ['MKT-R1.1', 'MKT-R22.3'],
    pre: 'Shahzad Mobile Shop has a Live phone offer in Karachi (as M-1d-01).', auto: ['MKT-1e2-01', 'MKT-1e2-02'] }, (step, call, cleanup) => {
    cy.then(() => seedPolicies(`${run}x`).then((p) => cy.then(() => publishOffer(SELLER_A, { run: `${run}x`, price: 52000, qty: 40,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))).then((o) => { E.a = o.offerId; E.product = o.mktProductId }))
    step('Open /marketplace in an incognito window and press "Sign in" at the top.',
      'The account panel opens: Phone number, Password, "Sign in", and "New here? Create an account". The forgot-password line says to contact MaxTheService support with an order number and the phone you ordered with.', () => {
        customer()
        cy.visit(UI.publicPage)
        cy.get('#mktAccountBtn').should('have.text', 'Sign in').click()
        cy.get('#mktAccSignIn').should('be.visible').and('contain', 'Contact MaxTheService support')
      })
    step(`Press "New here? Create an account". Phone ${cph(1)}, Your name "Ali Raza", Password "short". Press "Create account".`,
      'Refused: "Choose a password of at least 8 characters." The hint under the password says "At least 8 characters. Not your phone number."', () => {
        cy.get('#mktAccCreate').click()
        cy.get('#mktAccHint').should('be.visible')
        cy.get('#mktAccPhone').type(cph(1))
        cy.get('#mktAccName').type('Ali Raza')
        cy.get('#mktAccPassword').type('short')
        cy.get('#mktAccSubmit').should('have.text', 'Create account').click()
        cy.get('#mktAccMsg').should('have.text', 'Choose a password of at least 8 characters.')
      })
    step(`Password "${PW2}". Press "Create account".`,
      'Signed in: the top button now reads "Ali Raza"; "My orders" shows "No orders yet." with "Add an order you placed before" below.', () => {
        cy.get('#mktAccPassword').clear().type(PW2)
        cy.get('#mktAccSubmit').click()
        cy.get('#mktAccountBtn').should('have.text', 'Ali Raza')
        cy.get('#mktMyOrders').should('contain', 'No orders yet.')
        cy.get('#mktClaimForm').should('be.visible')
      })
    step('Developer tools → Application → Cookies: look for MKT_SESSION.',
      'The session cookie is HttpOnly (page scripts cannot read it) and SameSite=Lax; document.cookie does not contain it.', () => {
        call('Browser cookie MKT_SESSION (value withheld)', cy.getCookie('MKT_SESSION').then((c) => ({
          status: 'cookie', body: { name: c.name, httpOnly: c.httpOnly, sameSite: c.sameSite, path: c.path, secure: c.secure } })))
          .then((r) => { expect(r.body.httpOnly).to.eq(true); expect(r.body.sameSite).to.match(/lax/i) })
        call('document.cookie as page scripts see it', cy.document().its('cookie').then((s) => ({ status: 'script', body: s || '(empty)' })))
          .then((r) => expect(String(r.body)).not.to.contain('MKT_SESSION'))
      }, { screen: false })
    step(`Press "Sign out", then try to create another account with the same phone written as (${cph(1).slice(0, 4)}) ${cph(1).slice(4)}.`,
      'Refused: "This phone number already has an account. Sign in instead." — one account per phone, however it is written.', () => {
        cy.get('#mktAccLogout').click()
        cy.get('#mktAccSignIn').should('be.visible')
        cy.get('#mktAccCreate').click()
        cy.get('#mktAccPhone').clear().type(`(${cph(1).slice(0, 4)}) ${cph(1).slice(4)}`)
        cy.get('#mktAccName').clear().type('Someone Else')
        cy.get('#mktAccPassword').clear().type('Another#2026')
        cy.get('#mktAccSubmit').click()
        cy.get('#mktAccMsg').should('contain', 'already has an account')
      })
    cleanup('Nothing to undo: the account stays for the next cases.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1e2-02', slice: 'MKT-1e2', title: 'Five wrong passwords lock the phone for 15 minutes',
    persona: 'Customer', reqs: ['MKT-R22.3'], pre: 'An account exists for a phone (here a fresh one).', auto: ['MKT-1e2-03'] }, (step, call, cleanup) => {
    cy.then(() => { customer(); cy.visit(UI.publicPage); post('/marketplace/account/register', { phone: cph(2), name: 'Sara', password: PW2 }); post('/marketplace/account/logout', {}) })
    step(`Sign in with phone ${cph(2)} and a wrong password, five times.`, 'Each time: "The phone number or password is not right." — the same sentence an unknown phone gets.', () => {
      customer()
      cy.visit(UI.publicPage)
      cy.get('#mktAccountBtn').click()
      cy.get('#mktAccPhone').type(cph(2))
      for (let i = 0; i < 5; i++) {
        cy.get('#mktAccPassword').clear().type('wrong-one')
        cy.get('#mktAccSubmit').click()
        cy.get('#mktAccMsg').should('have.text', 'The phone number or password is not right.')
      }
    })
    step('Now type the RIGHT password and press "Sign in".', 'Refused: "Too many wrong passwords. Please try again in 15 minutes."', () => {
      cy.get('#mktAccPassword').clear().type(PW2)
      cy.get('#mktAccSubmit').click()
      cy.get('#mktAccMsg').should('contain', 'try again in 15 minutes')
    })
    cleanup('Wait 15 minutes; the lock lifts by itself.', 'Signing in works again after 15 minutes.', () => {}, { screen: false })
  })

  walk({ id: 'M-1e2-03', slice: 'MKT-1e2', title: 'My orders shows only what is proven yours; an earlier order is added by number + phone',
    persona: 'Customer', reqs: ['MKT-R1.1', 'MKT-R22.1'], pre: 'M-1e2-01 done (account for ' + 'the walk phone).', auto: ['MKT-1e2-04', 'MKT-1e2-05'] }, (step, call, cleanup) => {
    step(`Signed OUT, order the phone with phone ${cph(1)} (cash on delivery).`, '"Waiting for Shahzad Mobile Shop to confirm" — an anonymous order.', () => {
      customer()
      buyAs(E.a, cph(1))
      cy.get(UI.checkoutStatus).should('contain', 'Waiting for')
      cy.get('#mktCoOrderNo').should('not.be.empty').invoke('text').then((no) => { E.anon = no })
    })
    step(`Sign in as ${cph(1)} and open My orders.`, 'The anonymous order is NOT there: the same phone is not proof that it is yours.', () => {
      signIn(cph(1))
      cy.get('#mktMyOrders').should('not.contain', E.anon)
    })
    step('Under "Add an order you placed before": the order number and a WRONG phone (03009999999). Press "Add to my orders".', 'Refused: "No such order. Check the order number and the phone it was placed with."', () => {
      cy.get('#mktClaimNo').type(E.anon)
      cy.get('#mktClaimPhone').type('03009999999')
      cy.get('#mktClaimBtn').click()
      cy.get('#mktClaimMsg').should('contain', 'No such order')
    })
    step(`The same number with phone ${cph(1)}. Press "Add to my orders".`, 'The order appears in My orders with its seller, "Waiting for the seller", the total and "Cash on delivery".', () => {
      cy.get('#mktClaimPhone').clear().type(cph(1))
      cy.get('#mktClaimBtn').click()
      cy.get(`#mktMyOrders li[data-order-no="${E.anon}"]`).should('contain', 'Waiting for the seller').and('contain', 'Cash on delivery')
    })
    step('Signed in, order again (cash on delivery).', 'The new order is in My orders at once — no claim needed.', () => {
      buyAs(E.a, cph(1))
      cy.get(UI.checkoutStatus).should('contain', 'Waiting for')
      cy.get('#mktCoOrderNo').should('not.be.empty').invoke('text').then((no) => {
        E.mine = no
        cy.visit(page('account=orders'))
        cy.get(`#mktMyOrders li[data-order-no="${no}"]`).should('be.visible')
      })
    })
    cleanup('None: M-1e2-04 cancels these orders.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1e2-04', slice: 'MKT-1e2', title: 'The shopper cancels while the seller has not answered; after Accept they cannot',
    persona: 'Customer, then owner.business@myplus.com', reqs: ['MKT-R10.5'], pre: 'M-1e2-03 done: two waiting orders in My orders.', auto: ['MKT-1e2-06'] }, (step, call, cleanup) => {
    step(`Signed in as ${cph(1)}: My orders → "Cancel order" on ${'the first order'} → reason "changed my mind" → "Cancel order".`,
      'The order turns "Cancelled" with "You cancelled this order. changed my mind"; its Cancel button is gone.', () => {
        signIn(cph(1))
        cy.get(`#mktMyOrders li[data-order-no="${E.anon}"] .mkt-cancel`).click()
        cy.get('#uiC-input').type('changed my mind')
        cy.get('.uiC-ok').click()
        cy.get(`#mktMyOrders li[data-order-no="${E.anon}"]`).should('contain', 'Cancelled').and('contain', 'You cancelled this order.')
          .find('.mkt-cancel').should('not.exist')
      })
    step('As owner.business@myplus.com: Sale → Marketplace → Incoming → "All".', 'The order reads "Cancelled": the seller is told, and the held stock was given back.', () => {
      as(SELLER_A)
      openMarketplace()
      cy.get('#mktIncomingStatus').select('', { force: true })
      cy.contains(`${UI.incoming} tr`, E.anon).should('contain', 'Cancelled')
    })
    step('The seller presses Accept on the OTHER waiting order. Then the shopper opens My orders.', 'That order reads "Confirmed" and has no Cancel button: after Accept, cancelling is a support case.', () => {
      cy.contains(`${UI.incoming} tr`, E.mine).should('contain', 'Waiting for you').find(UI.acceptBtn).click()
      cy.contains(`${UI.incoming} tr`, E.mine).should('contain', 'Accepted')
      signIn(cph(1))
      cy.get(`#mktMyOrders li[data-order-no="${E.mine}"]`).should('contain', 'Confirmed').find('.mkt-cancel').should('not.exist')
    })
    cleanup('None: the accepted sale is real (return it through Sale Returns if needed).', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1e2-05', slice: 'MKT-1e2', title: 'Pay online now: charged at once; a seller reject refunds it exactly once',
    persona: 'Customer, then owner.business@myplus.com', reqs: ['MKT-R19.1', 'MKT-R20.1', 'MKT-R13.1'], pre: 'Signed in (M-1e2-01).', auto: ['MKT-1e2-07'] }, (step, call, cleanup) => {
    step('Signed in, buy the phone. At "How do you want to pay?" choose "Pay online now".', 'A card field appears, labelled test mode: "Test payments only: no real card is charged." The cash-on-delivery line is hidden.', () => {
      signIn(cph(1))
      cy.visit(page(`product=${E.product}&city=Karachi`))
      cy.get(`${UI.offerRow}[data-offer-id="${E.a}"] ${UI.chooseOffer}`).check()
      cy.get(UI.buyButton).click()
      cy.get('#mktPayChoice').should('be.visible')
      cy.get('#mktPayCard').check()
      cy.get('#mktCardRow').should('be.visible')
      cy.get('#mktCardNote').should('contain', 'no real card is charged')
      cy.get('#mktCheckoutView .cod').should('not.be.visible')
    })
    step('Card "4242 4242 4242 4242", your details, "Place order".', '"Waiting for Shahzad Mobile Shop to confirm"; in My orders the order reads "Paid online".', () => {
      cy.get('#mktCoName').clear().type('Ali Raza')
      cy.get('#mktCoPhone').clear().type(cph(1))
      cy.get('#mktCoAddress').clear().type('1 Clifton')
      cy.get('#mktCardToken').type('4242424242424242')
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', 'Waiting for')
      cy.get('#mktCoOrderNo').should('not.be.empty').invoke('text').then((no) => {
        E.paid = no
        cy.visit(page('account=orders'))
        cy.get(`#mktMyOrders li[data-order-no="${no}"]`).should('contain', 'Paid online')
      })
    })
    step('As owner.business@: Incoming → that order → reason "out of stock" → Reject. Then the shopper reopens My orders.', 'The order reads "Cancelled", "The seller could not fulfil this order." and "Refunded".', () => {
      as(SELLER_A)
      openMarketplace()
      cy.contains(`${UI.incoming} tr`, E.paid).find('input[placeholder*="cannot fulfil"]').type('out of stock')
      cy.contains(`${UI.incoming} tr`, E.paid).find(UI.rejectBtn).click()
      cy.contains(`${UI.incoming} tr`, E.paid).should('contain', 'Rejected')
      signIn(cph(1))
      cy.get(`#mktMyOrders li[data-order-no="${E.paid}"]`).should('contain', 'Cancelled').and('contain', 'Refunded')
    })
    step('Developer tools: GET /marketplace/account/orders and read that order\'s payments.', 'Exactly ONE succeeded CHARGE and ONE succeeded REFUND for the same amount — never two.', () => {
      call('GET /marketplace/account/orders', get('/marketplace/account/orders?size=50')).then((r) => {
        const o = list(r.body).find((x) => x.orderNo === E.paid)
        expect(o.payments.filter((x) => x.kind === 'CHARGE' && x.status === 'SUCCEEDED')).to.have.length(1)
        expect(o.payments.filter((x) => x.kind === 'REFUND' && x.status === 'SUCCEEDED')).to.have.length(1)
      })
    })
    cleanup('None: a refunded order is complete.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1e2-06', slice: 'MKT-1e2', title: 'A declined card places nothing',
    persona: 'Customer', reqs: ['MKT-R19.1'], pre: 'Signed in (M-1e2-01).', auto: ['MKT-1e2-08'] }, (step, call, cleanup) => {
    step('Signed in, buy the phone, "Pay online now", card "fail" (the test card that is always declined). "Place order".',
      'Refused on the checkout: "Your card was declined. Please use another card or choose cash on delivery." No order number is shown.', () => {
        as(SELLER_A)
        get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => { E.waitingBefore = list(r.body).filter((x) => x.customerPhone === cph(1)).length })
        signIn(cph(1))
        buyAs(E.a, cph(1), { cardToken: 'fail' })
        cy.get('#mktCoError').should('have.text', 'Your card was declined. Please use another card or choose cash on delivery.')
        cy.get('#mktOrderView').should('not.be.visible')
      })
    step('As owner.business@: Incoming → "Waiting for you".', 'No new order from this shopper is waiting: the seller never sees a declined order, and its stock was given back.', () => {
      as(SELLER_A)
      get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) =>
        expect(list(r.body).filter((x) => x.customerPhone === cph(1)), 'waiting orders from this shopper, before vs after').to.have.length(E.waitingBefore))
      openMarketplace()
    })
    cleanup('Nothing was placed.', 'Nothing to undo.', () => {}, { screen: false })
  })

  // ──────────────────────────────── MKT-1f ────────────────────────────────

  const F = {}
  const fph = (k) => `0313${String(run).slice(-6)}${k}`
  const F_PW = 'Shop!ng2026'
  /** Signed in as `ph` (created if new) on the public page. */
  const fSignIn = (ph) => {
    customer()
    cy.visit(UI.publicPage)
    post('/marketplace/account/login', { phone: ph, password: F_PW }).then((r) => {
      if (!ok(r.body)) post('/marketplace/account/register', { phone: ph, name: 'Ali Raza', password: F_PW })
    })
  }
  /** A DELIVERED order for `ph`, through the seller's real steps: Accept → Packed → parcel → Delivered. Yields the order number. */
  /** The 1f offer — published once, by whichever 1f case runs first (each case can be recorded on its own). */
  const fOffer = () => (F.offer ? cy.wrap(F.offer) : seedPolicies(`${run}f`).then((p) => cy.then(() => publishOffer(SELLER_A,
    { run: `${run}f`, price: 52000, qty: 40, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))).then((o) => { F.offer = o.offerId; return F.offer }))
  const fDelivered = (ph, mode = 'COD', offerId) => {
    const o = {}
    if (!offerId) fOffer()
    fSignIn(ph)
    cy.then(() => post(API.checkout, { offerId: offerId || F.offer, quantity: 1, expectedPrice: 52000, customerName: 'Ali Raza',
      customerPhone: ph, address: '1 Clifton', city: 'Karachi', idempotencyKey: `wf-${run}-${Math.random()}`, paymentMode: mode,
      cardToken: mode === 'CARD' ? '4242424242424242' : undefined }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      Object.assign(o, { no: data(r.body).orderNo, so: data(r.body).sellerOrderId, v: data(r.body).sellerOrderVersion })
    }))
    as(SELLER_A)
    cy.then(() => post(API.acceptOrder, { id: o.so, version: o.v }))
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`).then((s) => { o.store = list(s.body).find((x) => x.orderNo === o.no).storeOrderId }))
    cy.then(() => post('/updateOrderStatus', { id: o.store, status: 'PACKED' }))
    cy.then(() => get(`/getOrder?id=${o.store}`).then((r) => {
      const l = data(r.body).items[0]
      post('/shipOrder', { id: o.store, lines: [{ orderItemId: l.id, quantity: l.quantity }], carrier: 'Own rider', trackingNumber: `W-${run}` })
    }))
    cy.then(() => post('/updateOrderStatus', { id: o.store, status: 'DELIVERED' }))
    return cy.wrap(o)
  }
  const myOrders = (ph) => { fSignIn(ph); cy.visit(page('account=orders')); cy.get('#mktAccOrdersBox').should('be.visible') }
  /** Fill the "Get help" form on the order's row. */
  const getHelp = (no, { topic, reason, note }) => {
    cy.get(`#mktMyOrders li[data-order-no="${no}"] .mkt-help`).click()
    cy.get(`#mktMyOrders li[data-order-no="${no}"] .mkt-help-topic`).select(topic)
    if (reason) cy.get(`#mktMyOrders li[data-order-no="${no}"] .mkt-help-reason`).select(reason)
    if (note) cy.get(`#mktMyOrders li[data-order-no="${no}"] .mkt-help-note`).type(note)
    cy.get(`#mktMyOrders li[data-order-no="${no}"] .mkt-help-send`).click()
  }
  const caseOf = (no) => `#mktMyOrders li[data-order-no="${no}"] .mkt-case`
  const supportCases = () => { asOperator(); cy.visit('/platformDashboard'); cy.get('#platMktCasesBtn').click(); cy.get('#platMktCases').should('be.visible') }
  const openCaseRow = (caseNo) => { cy.get(`#mktCaseList tr[data-case-no="${caseNo}"]`).click(); cy.get(`#mktCaseDetail [data-case-no="${caseNo}"]`).should('be.visible') }
  const sellerTasks = () => { as(SELLER_A); openMarketplace(); cy.get('#mktTasksBox').should('be.visible') }

  walk({ id: 'M-1f-01', slice: 'MKT-1f', title: 'One place to complain', persona: 'Customer → MaxTheService operator → owner.business@myplus.com',
    reqs: ['MKT-R8.2', 'MKT-R22.1'], pre: 'Ali\'s order from Shahzad Mobile Shop is DELIVERED (the seller recorded the delivery).', auto: ['MKT-1f-01', 'MKT-1f-02', 'MKT-1f-03'] }, (step, call, cleanup) => {
    cy.then(() => fDelivered(fph(1)).then((o) => { F.o1 = o.no }))
    step('Customer: open /marketplace → your name → My orders.', 'The order reads "Delivered" and shows "Get help".', () => {
      myOrders(fph(1))
      cy.get(`#mktMyOrders li[data-order-no="${F.o1}"]`).should('contain', 'Delivered').find('.mkt-help').should('be.visible')
    })
    step('"Get help" → "Something is wrong with my order" → note "Box was open" → "Send to MaxTheService".',
      'A help request SC-… appears under the order; nowhere is a seller phone number shown.', () => {
        getHelp(F.o1, { topic: 'ORDER_PROBLEM', note: 'Box was open' })
        cy.get(caseOf(F.o1)).should('contain', 'SC-').invoke('attr', 'data-case-no').then((c) => { F.c1 = c })
        cy.get(caseOf(F.o1)).should('contain', 'Box was open').and('not.contain', 'Shahzad')   // the help thread never names the seller
      })
    step('Operator: platform dashboard → Support cases → the case → write "check seller history", tick "Internal note" → Send. Then write "Check the box and call the customer" → "Task the seller".',
      'The thread shows the internal note marked (internal); the case reads "Waiting for seller".', () => {
        supportCases()
        openCaseRow(F.c1)
        cy.get('#mktCaseDetail .mkt-op-reply').type('check seller history')
        cy.get('#mktCaseDetail .mkt-op-internal').check()
        cy.get('#mktCaseDetail .mkt-op-send').click()
        cy.get('#mktCaseDetail').should('contain', '(internal)')
        cy.get('#mktCaseDetail .mkt-op-reply').clear().type('Check the box and call the customer')
        cy.get('#mktCaseDetail .mkt-op-task').click()
        cy.get(`#mktCaseList tr[data-case-no="${F.c1}"]`).should('contain', 'Waiting for seller')
      })
    step('Seller A: Sale → Marketplace → "Tasks from MaxTheService" → the task → answer "Charger sent with our rider today" → Send.',
      'The task shows the order number, the customer\'s pickup details and the operator\'s task — not the internal note. The answer is sent.', () => {
        sellerTasks()
        cy.get(`#mktTasks .mkt-task[data-case-no="${F.c1}"]`).should('contain', F.o1).and('contain', 'Check the box').and('not.contain', 'seller history')
        cy.get(`#mktTasks .mkt-task[data-case-no="${F.c1}"] .mkt-task-reply`).type('Charger sent with our rider today')
        cy.get(`#mktTasks .mkt-task[data-case-no="${F.c1}"] .mkt-task-send`).click()
        cy.get('#mktTasks').should('contain', 'Sent to MaxTheService support')
      })
    step('Seller B (owner.mobile@) opens the same Tasks box.', 'Seller A\'s case is not there.', () => {
      as(SELLER_B); openMarketplace()
      cy.get('#mktTasks').should('not.contain', F.c1)
    })
    step('Customer: My orders.', 'The seller\'s answer is shown signed "MaxTheService support"; the internal note is NOT shown.', () => {
      myOrders(fph(1))
      cy.get(caseOf(F.o1)).should('contain', 'Charger sent with our rider today').and('contain', 'MaxTheService support').and('not.contain', 'seller history')
    })
    cleanup('Operator: the case → write "walk cleanup" → Resolve.', 'The case reads Resolved.', () => {
      supportCases(); openCaseRow(F.c1)
      cy.get('#mktCaseDetail .mkt-op-reply').type('walk cleanup')
      cy.get('#mktCaseDetail .mkt-op-resolve').click()
      cy.get('#platMktCaseStatus button[data-status="RESOLVED"]').click()
      cy.get(`#mktCaseList tr[data-case-no="${F.c1}"]`).should('contain', 'Resolved')
    })
  })

  walk({ id: 'M-1f-02', slice: 'MKT-1f', title: 'Return cost follows the cause', persona: 'Customer, then MaxTheService operator',
    reqs: ['MKT-R13.1', 'MKT-R13.3'], pre: 'Delivered test orders (M-1f-01\'s offer, 7 return days).', auto: ['MKT-1f-04', 'MKT-1f-05', 'MKT-1f-06'] }, (step, call, cleanup) => {
    cy.then(() => fDelivered(fph(2)).then((o) => { F.o2a = o.no }))
    cy.then(() => fDelivered(fph(2)).then((o) => { F.o2b = o.no }))
    step('Customer: My orders → Get help → "Return this item" → "Wrong item sent" → Send.', '"Return requested" RT-… under the order; the refund shown is Rs 52,000.', () => {
      myOrders(fph(2))
      getHelp(F.o2a, { topic: 'RETURN', reason: 'WRONG_PRODUCT', note: 'it is a 64GB' })
      cy.get(caseOf(F.o2a)).should('contain', 'RT-').and('contain', 'Return requested').and('contain', '52,000')
      cy.get(caseOf(F.o2a)).invoke('attr', 'data-case-no').then((c) => { F.c2a = c })
    })
    step('On the second order: Return this item → "Changed my mind" → Send.', 'Return requested; the refund is Rs 51,750 with "pickup fee Rs 250" — the customer bears a change of mind.', () => {
      getHelp(F.o2b, { topic: 'RETURN', reason: 'CHANGE_OF_MIND' })
      cy.get(caseOf(F.o2b)).should('contain', '51,750').and('contain', 'pickup fee')
      cy.get(caseOf(F.o2b)).invoke('attr', 'data-case-no').then((c) => { F.c2b = c })
    })
    step('Operator: Support cases → the first case.', 'Cost bearer: FULFILLER (Shahzad Mobile Shop) — resolved from what the order line recorded when it was placed.', () => {
      supportCases(); openCaseRow(F.c2a)
      cy.get('#mktCaseDetail .mkt-op-return').should('contain', 'FULFILLER').and('contain', 'Shahzad')
    })
    step('The second case.', 'Cost bearer: CUSTOMER; Refund 51750.00 (−250.00).', () => {
      openCaseRow(F.c2b)
      cy.get('#mktCaseDetail .mkt-op-return').should('contain', 'CUSTOMER').and('contain', '−250')
    })
    cleanup('Operator: each return → note "walk cleanup" → Reject.', 'They read REJECTED; the customer is told why.', () => {
      ;[F.c2a, F.c2b].forEach((c) => {
        openCaseRow(c)
        cy.get('#mktCaseDetail .mkt-op-return input').type('walk cleanup')
        cy.get('#mktCaseDetail .mkt-reject').click()
        cy.get('#mktCaseDetail .mkt-op-return').should('contain', 'REJECTED')
      })
    })
  })

  walk({ id: 'M-1f-03', slice: 'MKT-1f', title: 'A paid-online return end to end', persona: 'Customer → MaxTheService operator → owner.business@myplus.com',
    reqs: ['MKT-R13.1', 'MKT-R13.2'], pre: 'A delivered Rs 52,000 order paid online, inside its 7-day return window.', auto: ['MKT-1f-04'] }, (step, call, cleanup) => {
    cy.then(() => fDelivered(fph(3), 'CARD').then((o) => { F.o3 = o.no; F.s3 = o.store }))
    step('Customer: My orders → Get help → Return this item → "Wrong item sent" → Send.', '"Return requested" RT-…; the order reads "Paid online".', () => {
      myOrders(fph(3))
      cy.get(`#mktMyOrders li[data-order-no="${F.o3}"]`).should('contain', 'Paid online')
      getHelp(F.o3, { topic: 'RETURN', reason: 'WRONG_PRODUCT' })
      cy.get(caseOf(F.o3)).should('contain', 'Return requested').invoke('attr', 'data-case-no').then((c) => { F.c3 = c })
    })
    step('Operator: Support cases → the case → note "pickup tomorrow" → Approve.', '"Approved. The seller\'s rider collects it." The return reads APPROVED.', () => {
      supportCases(); openCaseRow(F.c3)
      cy.get('#mktCaseDetail .mkt-op-return input').type('pickup tomorrow')
      cy.get('#mktCaseDetail .mkt-approve').click()
      cy.get('#mktCaseDetail .mkt-op-return').should('contain', 'APPROVED')
    })
    step('Seller A: Tasks from MaxTheService → the return → "Restock: back on the shelf" → "Item received". (No "Cash handed back" box: it was paid online.)',
      '"Received. The customer\'s refund is done." The task leaves the list.', () => {
        sellerTasks()
        cy.get(`#mktTasks .mkt-task[data-case-no="${F.c3}"] .mkt-cash`).should('not.exist')
        cy.get(`#mktTasks .mkt-task[data-case-no="${F.c3}"] .mkt-outcome`).select('RESTOCK')
        cy.get(`#mktTasks .mkt-task[data-case-no="${F.c3}"] .mkt-received`).click()
        cy.get('#mktTasks').should('contain', 'refund is done')
      })
    step('Developer tools: GET /getOrder for the store order behind it.', 'fulfilmentStatus RETURNED — the seller\'s books took a credit note against the invoice.', () => {
      call('GET /getOrder', get(`/getOrder?id=${F.s3}`)).then((r) => expect(data(r.body).fulfilmentStatus).to.eq('RETURNED'))
    }, { screen: false })
    step('Customer: My orders.', 'The help request shows "Refunded" and the message that Rs 52,000 is on its way to the card; the order shows one refund.', (snap) => {
      myOrders(fph(3))
      cy.get(caseOf(F.o3)).should('contain', 'Refunded').and('contain', 'on its way to your card')
      snap()
      call('GET /marketplace/account/orders', get('/marketplace/account/orders?size=50')).then((r) => {
        const o = list(r.body).find((x) => x.orderNo === F.o3)
        expect(o.payments.filter((p) => p.kind === 'REFUND' && p.status === 'SUCCEEDED')).to.have.length(1)
      })
    })
    cleanup('None: a refunded return is complete. (Pressing "Item received" again changes nothing: one credit note, one refund.)', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1f-04', slice: 'MKT-1f', title: 'Unsafe or expired goods escalate at once', persona: 'Customer → MaxTheService operator',
    reqs: ['MKT-R13.4'], pre: 'A delivered order.', auto: ['MKT-1f-08'] }, (step, call, cleanup) => {
    cy.then(() => fDelivered(fph(4)).then((o) => { F.o4 = o.no }))
    step('Customer: My orders → Get help → Return this item → "Expired or unsafe" → note "battery swollen" → Send.', 'Return requested.', () => {
      myOrders(fph(4))
      getHelp(F.o4, { topic: 'RETURN', reason: 'EXPIRED_OR_UNSAFE', note: 'battery swollen' })
      cy.get(caseOf(F.o4)).should('contain', 'Return requested').invoke('attr', 'data-case-no').then((c) => { F.c4 = c })
    })
    step('Operator: Support cases.', 'The case is marked URGENT and sits in the urgent group at the top — above every case that is not urgent, however much older.', () => {
      supportCases()
      cy.get(`#mktCaseList tr[data-case-no="${F.c4}"]`).should('contain', 'URGENT')
      cy.get('#mktCaseList tbody tr').then(($rows) => {
        const rows = [...$rows]
        const mine = rows.findIndex((r) => r.getAttribute('data-case-no') === F.c4)
        const firstCalm = rows.findIndex((r) => !r.textContent.includes('URGENT'))
        expect(mine, 'the case is in the queue').to.be.at.least(0)
        if (firstCalm >= 0) expect(mine, 'above every non-urgent case').to.be.below(firstCalm)
      })
    })
    cleanup('Operator: the return → note "walk cleanup" → Reject; then resolve the case with "walk cleanup".', 'Rejected; Resolved.', () => {
      openCaseRow(F.c4)
      cy.get('#mktCaseDetail .mkt-op-return input').type('walk cleanup')
      cy.get('#mktCaseDetail .mkt-reject').click()
      cy.get('#mktCaseDetail .mkt-op-reply').type('walk cleanup')
      cy.get('#mktCaseDetail .mkt-op-resolve').click()
      cy.get(`#mktCaseList tr[data-case-no="${F.c4}"]`).should('not.exist')
    })
  })

  walk({ id: 'M-1f-05', slice: 'MKT-1f', title: 'Every action leaves a trail', persona: 'owner.business@myplus.com',
    reqs: ['MKT-R22.4'], pre: 'After M-1f-01 to -04.', auto: ['MKT-1f-12'] }, (step, call, cleanup) => {
    step('Developer tools, signed in as Seller A: read the business\'s audit trail.',
      'MKT_CASE_TASKED and MKT_RETURN_DECIDED rows for Seller A\'s orders, actor type PLATFORM_OPERATOR — the operator\'s actions, filed in the seller\'s own trail.', () => {
        as(SELLER_A)
        cy.findAudit((a) => a.action === 'MKT_RETURN_DECIDED' && a.actorType === 'PLATFORM_OPERATOR', 'a return decision in the seller\'s trail')
        cy.auditLog().then((rows) => {
          const mine = rows.filter((a) => String(a.action || '').startsWith('MKT_'))
          call('audit trail (MKT_* rows)', cy.wrap({ status: 200, body: mine.slice(0, 5).map((a) => ({ action: a.action, ref: a.entityRef, actor: a.actorType, after: a.afterValue })) }))
          expect(mine.map((a) => a.action)).to.include.members(['MKT_CASE_TASKED', 'MKT_RETURN_DECIDED'])
        })
      }, { screen: false })
    cleanup('Nothing to undo: the trail is append-only.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1f-06', slice: 'MKT-1f', title: 'A cash-on-delivery return: cash back at pickup', persona: 'Customer → MaxTheService operator → owner.business@myplus.com',
    reqs: ['MKT-R13.2'], pre: 'A delivered cash-on-delivery order.', auto: ['MKT-1f-10'] }, (step, call, cleanup) => {
    cy.then(() => fDelivered(fph(6)).then((o) => { F.o6 = o.no }))
    step('Customer: Return this item → "Does not work" → Send. Operator: approve it.', 'Approved; the customer is told the rider hands Rs 52,000 back in cash when collecting it.', () => {
      myOrders(fph(6))
      getHelp(F.o6, { topic: 'RETURN', reason: 'DEFECTIVE', note: 'screen flickers' })
      cy.get(caseOf(F.o6)).invoke('attr', 'data-case-no').then((c) => { F.c6 = c })
      cy.then(() => { supportCases(); openCaseRow(F.c6) })
      cy.get('#mktCaseDetail .mkt-approve').click()
      cy.get('#mktCaseDetail').should('contain', 'in cash when collecting it')
    })
    step('Seller A: Tasks → the return → "Quarantine" → "Item received" WITHOUT ticking "Cash handed back".',
      'Refused: "This order was paid in cash: hand Rs 52,000 back to the customer at pickup, then tick "Cash handed back"." (ruling R-MKT-12)', () => {
        sellerTasks()
        cy.get(`#mktTasks .mkt-task[data-case-no="${F.c6}"] .mkt-outcome`).select('QUARANTINE')
        cy.get(`#mktTasks .mkt-task[data-case-no="${F.c6}"] .mkt-received`).click()
        cy.get(`#mktTasks .mkt-task[data-case-no="${F.c6}"]`).should('contain', 'paid in cash')
      })
    step('Tick "Cash handed back (Rs 52,000)" → "Item received".', '"Received. The customer\'s refund is done." — recorded as cash at pickup; no card refund is attempted; the unit is quarantined, not sellable.', () => {
      cy.get(`#mktTasks .mkt-task[data-case-no="${F.c6}"] .mkt-cash`).check()
      cy.get(`#mktTasks .mkt-task[data-case-no="${F.c6}"] .mkt-received`).click()
      cy.get('#mktTasks').should('contain', 'refund is done')
    })
    cleanup('None: the return is complete.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1f-07', slice: 'MKT-1f', title: 'No way around MaxTheService', persona: 'Customer, then owner.business@myplus.com',
    reqs: ['MKT-R8.2'], pre: 'A delivered marketplace order (store order SO-…).', auto: ['MKT-1f-09'] }, (step, call, cleanup) => {
    cy.then(() => fDelivered(fph(7), 'CARD').then((o) => { F.o7 = o.no; F.s7 = o.store }))
    step('As the shopper, ask the shop\'s own return path (POST /storefront/return) with the store order and the phone.',
      'Refused: "Returns for marketplace orders go through MaxTheService: open My orders on the marketplace and choose Get help."', () => {
        customer()
        call('POST /storefront/return', post('/storefront/return', { ref: F.s7, contact: fph(7), reason: 'bypass' }))
          .then((r) => expect(msg(r.body)).to.contain('go through MaxTheService'))
      }, { screen: false })
    step('Seller A: the store order → Process return (POST /processReturn).', 'Refused: "This is a marketplace order: its return goes through MaxTheService, which refunds the customer." The order stays Delivered.', () => {
      as(SELLER_A)
      call('POST /processReturn', post('/processReturn', { id: F.s7 })).then((r) => expect(msg(r.body)).to.contain('through MaxTheService'))
      call('GET /getOrder', get(`/getOrder?id=${F.s7}`)).then((r) => expect(data(r.body).fulfilmentStatus).to.eq('DELIVERED'))
    }, { screen: false })
    cleanup('Nothing was changed.', '—', () => {}, { screen: false })
  })

  // ──────────────────────────────── MKT-1g ────────────────────────────────

  const G = {}
  const gph = (k) => `0314${String(run).slice(-6)}${k}`
  const OPS2 = 'ops2@myplus.com'   // the second operator (walk-reset.sql): a payout needs two people
  /** The 1g offer: Rs 52,000 under a 0-day return policy, so with T+0 a line delivered on a business day is payable that day. */
  const gOffer = () => (G.offer ? cy.wrap(G.offer) : seedPolicies(`${run}g`, { returnDays: 0 }).then((p) => cy.then(() => publishOffer(SELLER_A,
    { run: `${run}g`, price: 52000, qty: 40, warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))).then((o) => { G.offer = o.offerId; return G.offer }))
  /** Placed and accepted (not delivered). Yields {no, store}. */
  const gAccepted = (ph, mode = 'CARD') => {
    const o = {}
    gOffer()
    fSignIn(ph)
    cy.then(() => post(API.checkout, { offerId: G.offer, quantity: 1, expectedPrice: 52000, customerName: 'Ali Raza',
      customerPhone: ph, address: '1 Clifton', city: 'Karachi', idempotencyKey: `wg-${run}-${Math.random()}`, paymentMode: mode,
      cardToken: mode === 'CARD' ? '4242424242424242' : undefined }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      Object.assign(o, { no: data(r.body).orderNo, so: data(r.body).sellerOrderId, v: data(r.body).sellerOrderVersion })
    }))
    as(SELLER_A)
    cy.then(() => post(API.acceptOrder, { id: o.so, version: o.v }))
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`).then((s) => { o.store = list(s.body).find((x) => x.orderNo === o.no).storeOrderId }))
    return cy.wrap(o)
  }
  /** The seller's delivery steps on the store order (signed in as Seller A): Packed → parcel with the own rider → Delivered. */
  const gDeliver = (o, rec) => {
    const c = rec || ((label, chain) => chain)
    cy.then(() => c('POST /updateOrderStatus PACKED', post('/updateOrderStatus', { id: o.store, status: 'PACKED' })))
    cy.then(() => get(`/getOrder?id=${o.store}`).then((r) => {
      const l = data(r.body).items[0]
      c('POST /shipOrder', post('/shipOrder', { id: o.store, lines: [{ orderItemId: l.id, quantity: l.quantity }], carrier: 'Own rider', trackingNumber: `WG-${run}` }))
    }))
    cy.then(() => c('POST /updateOrderStatus DELIVERED', post('/updateOrderStatus', { id: o.store, status: 'DELIVERED' }))
      .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)))
  }
  const gDelivered = (ph, mode = 'CARD') => gAccepted(ph, mode).then((o) => { gDeliver(o); return cy.wrap(o) })
  /** Operator → Platform dashboard → Settlement and payouts. */
  const payouts = (email) => {
    if (!G.org) { asOperator(); sellerOrg() }
    if (email) cy.loginAsOperator(email); else asOperator()
    cy.visit('/platformDashboard')
    cy.get('#platMktPayoutsBtn').should('be.visible').click()
    cy.get('#platMktPayouts').should('be.visible')
    cy.get('#mktTPlus').should(($i) => expect($i.val()).to.not.eq(''))
  }
  const settleNow = () => {
    cy.get('#mktRunSettlement').click()
    return cy.get('#mktSetMsg').should('contain', 'Settled')
  }
  /** Seller A's organisation id, looked up once as admin@ (cached: the second operator never needs the lookup). */
  const sellerOrg = () => (G.org ? cy.wrap(G.org) : cy.orgOf(SELLER_A).then((org) => { G.org = org.id; return org.id }))
  const sellerRow = () => cy.then(() => cy.get(`#mktAccountList tr.mkt-account[data-org="${G.org}"]`, { timeout: 15000 }))
  const statement = () => {
    as(SELLER_A); openMarketplace()
    cy.get('#mktStatementTab').scrollIntoView().click()
    cy.get('#mktStatementTable tbody tr.mkt-line', { timeout: 15000 }).should('have.length.at.least', 1)
  }
  /** The order's statement row, scrolled into view (the capture must show the proof, not the offers above it). */
  const line = (no) => cy.get('#mktStatementTable tbody tr.mkt-line').filter(`:contains("${no}")`).first()
    .scrollIntoView({ offset: { top: -250, left: 0 } })
  const num = ($el) => Number(($el.text() || '0').replace(/[^0-9.-]/g, ''))
  const balanceOf = ($tr) => num($tr.find('.mkt-balance'))
  /** The trial balance's rows ({code, name, debit, credit}, each account netted to one side). */
  const tbRows = (r) => ((data(r.body) || r.body || {}).rows) || []
  const creditOf = (rows, code) => { const a = rows.find((x) => x.code === code) || {}; return Number(a.credit || 0) - Number(a.debit || 0) }

  walk({ id: 'M-1g-01', slice: 'MKT-1g', title: 'The worked example adds up', persona: 'MaxTheService operator (admin@myplus.com) → owner.business@myplus.com',
    reqs: ['MKT-R15.5', 'MKT-R16.1'], auto: ['MKT-1g-01'],
    pre: 'Ali\'s Rs 52,000 order from Shahzad Mobile Shop was paid online and delivered today, on a business day. Its offer was sold with 0 return days; commission policy: 8% of the items.' }, (step, call, cleanup) => {
    cy.then(() => gDelivered(gph(1)).then((o) => { G.o1 = o.no }))
    step('Operator: Platform dashboard → "Settlement and payouts". Type 0 in "Business days after the return window" → Save. If "Book commission in my organisation" is shown, press it.',
      '"Settlement settings saved." The box shows 0, and "Book commission in my organisation" is gone: commission is booked in your organisation.', () => {
        payouts()
        cy.get('#mktTPlus').clear().type('0')
        cy.get('#mktSetSave').click()
        cy.get('#mktSetMsg').should('contain', 'Settlement settings saved.')
        cy.get('body').then(($b) => { if ($b.find('#mktUseMyBooks:visible').length) cy.get('#mktUseMyBooks').click() })
        cy.get('#mktUseMyBooks').should('not.be.visible')
        cy.get('#mktTPlus').should('have.value', '0')
      })
    step('Press "Settle what is due now".',
      '"Settled 1 line(s). …" (or more, if other delivered lines were due); Shahzad Mobile Shop is listed with a balance above zero.', () => {
        settleNow().invoke('text').should('match', /Settled [1-9]/)
        sellerRow().should(($tr) => expect(balanceOf($tr)).to.be.greaterThan(0))
      })
    step('Seller A (owner.business@myplus.com): Sale → Marketplace → "Show statement". Find Ali\'s order.',
      'The row reads "Payable", payable on today\'s date. Customer paid 52,000; Commission 4,160; Delivery, fees, tax, reserve, correction 0; Payable to you 47,840. Every row adds up: payable + commission + the other deductions = what the customer paid.', () => {
        statement()
        line(G.o1).should('contain', 'Payable').then(($tr) => {
          expect(num($tr.find('.mkt-customer-amount'))).to.eq(52000)
          expect(num($tr.find('.mkt-commission'))).to.eq(4160)
          expect(num($tr.find('.mkt-payable'))).to.eq(47840)
        })
        cy.get('#mktStatementTable tbody tr.mkt-line').each(($tr) => {
          const n = (sel) => num($tr.find(sel))
          const parts = n('.mkt-payable') + n('.mkt-commission') + n('.mkt-delivery') + n('.mkt-fees') + n('.mkt-tax') + n('.mkt-reserve') + n('.mkt-adjustment')
          expect(Math.round(parts * 100), `row ${$tr.attr('data-line-id')} adds up`).to.eq(Math.round(n('.mkt-customer-amount') * 100))
        })
      })
    step('Scroll to "Ledger" under the statement.',
      'Two lines carry the order number as reference: SALE, 52,000 owed to you, and COMMISSION, 4,160 owed by you. The balance line above reads "MaxTheService owes you Rs …".', () => {
        cy.get('#mktLedgerTable tbody tr.mkt-entry[data-entry-type="SALE"]').filter(`:contains("${G.o1}")`).should('contain', '52,000')
        cy.get('#mktLedgerTable tbody tr.mkt-entry[data-entry-type="COMMISSION"]').filter(`:contains("${G.o1}")`).should('contain', '4,160')
          .first().scrollIntoView()
        cy.get('#mktBalance').should('contain', 'MaxTheService owes you')
      })
    cleanup('Nothing to undo: a settled line is paid out in M-1g-04, and the ledger is never edited.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1g-02', slice: 'MKT-1g', title: 'Nothing is payable before delivery and the return window', persona: 'owner.business@myplus.com and the MaxTheService operator',
    reqs: ['MKT-R15.2', 'MKT-R15.3', 'MKT-R16.2'], auto: ['MKT-1g-02'],
    pre: 'Ali placed a Rs 52,000 order (paid online) and Shahzad Mobile Shop ACCEPTED it, but has not delivered it. T+0 and 0 return days (M-1g-01).' }, (step, call, cleanup) => {
    cy.then(() => gAccepted(gph(2)).then((o) => { G.a2 = o }))
    step('Operator: Settlement and payouts → "Settle what is due now".', '"Settled 0 line(s) …" or a count that does not include this order: it is not delivered.', () => {
      payouts(); settleNow()
    })
    step('Seller A: Sale → Marketplace → "Show statement". Find the order.',
      'The row reads "Not delivered yet" and "Payable on" is "—". Nothing is owed on it and the ledger has no line for it.', () => {
        statement()
        line(G.a2.no).should('contain', 'Not delivered yet').find('.mkt-eligible-on').should('have.text', '—')
        cy.get('#mktLedgerTable tbody').should('not.contain', G.a2.no)
      })
    step('Seller A records the delivery: Sale → Orders → the store order → Packed → Ship (carrier "Own rider") → Delivered. Then the operator presses "Settle what is due now" again.',
      'The store order reads Delivered; the run settles it ("Settled 1 line(s)" or more).', (snap) => {
        gDeliver(G.a2, call)
        payouts()
        settleNow().invoke('text').should('match', /Settled [1-9]/)
        snap()
      })
    step('Seller A: "Show statement" again.', 'The row now reads "Payable", payable on today\'s date, with SALE and COMMISSION lines in the ledger.', () => {
      statement()
      line(G.a2.no).should('contain', 'Payable').find('.mkt-eligible-on').invoke('text').should('match', /^\d{4}-\d{2}-\d{2}$/)
      cy.get('#mktLedgerTable tbody').should('contain', G.a2.no)
    })
    cleanup('None: the order was delivered and settled, as a real one would be.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1g-03', slice: 'MKT-1g', title: 'T+N counts business days and skips the weekend', persona: 'MaxTheService operator, then owner.business@myplus.com',
    reqs: ['MKT-R15.1'], auto: ['MKT-1g-03'],
    pre: 'Today is a Monday. A Rs 52,000 order (0 return days) is delivered today, after the operator sets T+5.' }, (step, call, cleanup) => {
    step('Operator: Settlement and payouts → type 5 in "Business days after the return window" → Save.', '"Settlement settings saved." The box shows 5.', () => {
      payouts()
      cy.get('#mktTPlus').clear().type('5')
      cy.get('#mktSetSave').click()
      cy.get('#mktSetMsg').should('contain', 'Settlement settings saved.')
      cy.get('#mktTPlus').should('have.value', '5')
    })
    cy.then(() => gDelivered(gph(3)).then((o) => { G.o3 = o.no }))
    step('Ali\'s new order is delivered today (Monday). Operator: "Settle what is due now".', 'The order is not settled: it is counted as waiting ("… still wait for their payable date").', () => {
      payouts(); settleNow().invoke('text').should('match', /[1-9]\d* still wait/)
    })
    step('Seller A: "Show statement" → the new order.',
      '"Return days running", payable on the Monday a week later: five business days, Saturday and Sunday not counted.', () => {
        statement()
        line(G.o3).should('contain', 'Return days running').find('.mkt-eligible-on').invoke('text').then((d) => {
          const on = new Date(`${d}T00:00:00Z`)
          expect(on.getUTCDay(), `${d} is a Monday`).to.eq(1)
          call('payable date', cy.wrap({ status: 200, body: { eligibleOn: d } }))
        })
      }, { alsoScreen: true })
    cleanup('Operator: set "Business days after the return window" back to 0 → Save → "Settle what is due now".',
      '"Settlement settings saved.", then "Settled 1 line(s) …": the waiting line\'s date follows the setting in force, so with 0 it is due today and settles.', () => {
        payouts()
        cy.get('#mktTPlus').clear().type('0')
        cy.get('#mktSetSave').click()
        cy.get('#mktTPlus').should('have.value', '0')
        settleNow().invoke('text').should('match', /Settled [1-9]/)
      })
  })

  walk({ id: 'M-1g-04', slice: 'MKT-1g', title: 'A payout needs two people and happens once', persona: 'admin@myplus.com, then ops2@myplus.com (a second operator)',
    reqs: ['MKT-R16.3', 'MKT-R22.3'], auto: ['MKT-1g-04'],
    pre: 'Shahzad Mobile Shop has a positive balance (M-1g-01, M-1g-02) and no payout open (one left by an earlier run is approved by ops2 and paid first). ops2@myplus.com exists (walk-reset.sql).' }, (step, call, cleanup) => {
    // a payout cannot be withdrawn, only paid: finish one an earlier run (the gate) left open, so this case starts clean
    asOperator()
    sellerOrg().then((orgId) => {
      get('/platform/mkt/payouts').then((r) => {
        const open = list(r.body).find((p) => p.organizationId === orgId && p.status !== 'PAID')
        if (!open) return
        if (open.status === 'REQUESTED') { cy.loginAsOperator(OPS2); post(API.approvePayout, { id: open.id }) }
        post(API.markPayoutPaid, { id: open.id, bankReference: `TRX-PRE-${run}` }).then((x) => expect(ok(x.body), JSON.stringify(x.body)).to.eq(true))
      })
    })
    step('admin@myplus.com, developer tools: request a payout for Shahzad Mobile Shop twice with the SAME key (POST /platform/mkt/requestPayout), as a double click would.',
      'Both answers are the same payout PO-… for the whole balance, status REQUESTED: one payout, not two.', () => {
        asOperator()
        sellerOrg().then((orgId) => {
          const org = { id: orgId }
          const k = `walk-po-${run}`
          call('POST /platform/mkt/requestPayout', post(API.requestPayout, { organizationId: org.id, idempotencyKey: k })).then((r1) => {
            expect(ok(r1.body), JSON.stringify(r1.body)).to.eq(true)
            G.po = data(r1.body).payoutNo
            call('POST /platform/mkt/requestPayout (same key)', post(API.requestPayout, { organizationId: org.id, idempotencyKey: k }))
              .then((r2) => { expect(data(r2.body).id).to.eq(data(r1.body).id); expect(data(r2.body).status).to.eq('REQUESTED') })
          })
          call('POST /platform/mkt/requestPayout (a new key while one is open)', post(API.requestPayout, { organizationId: org.id, idempotencyKey: `${k}-2` }))
            .then((r) => expect(msg(r.body)).to.contain('still'))
        })
      }, { screen: false })
    step('Settlement and payouts → Shahzad Mobile Shop.', 'The row shows the payout PO-… with its amount and REQUESTED, and an "Approve" button; no "Request payout" button while it is open.', () => {
      payouts()
      sellerRow().should('contain', G.po).and('contain', 'REQUESTED').find('.mkt-approve-payout').should('be.visible')
      sellerRow().find('.mkt-request-payout').should('not.exist')
    })
    step('The same operator presses "Approve".', 'Refused: "Another person must approve this payout: you requested it." The payout stays REQUESTED.', () => {
      sellerRow().find('.mkt-approve-payout').click()
      sellerRow().should('contain', 'Another person must approve this payout: you requested it.').and('contain', 'REQUESTED')
    })
    step('Sign out; sign in as ops2@myplus.com. Settlement and payouts → the payout → "Approve".', 'The row reads APPROVED and shows a "Bank reference" box with "Mark paid".', () => {
      payouts(OPS2)
      sellerRow().find('.mkt-approve-payout').click()
      sellerRow().should('contain', 'APPROVED').find('.mkt-bank-ref').should('be.visible')
    })
    step('Press "Mark paid" with the box empty.', 'Refused: "Enter the bank\'s reference for the transfer."', () => {
      sellerRow().find('.mkt-mark-paid').click()
      sellerRow().should('contain', 'Enter the bank\'s reference for the transfer.')
    })
    step('Type "TRX-WALK-1" → "Mark paid".', 'The payout leaves the row; the balance is 0.', () => {
      sellerRow().find('.mkt-bank-ref').type('TRX-WALK-1')
      sellerRow().find('.mkt-mark-paid').click()
      sellerRow().should('not.contain', 'APPROVED').should(($tr) => expect(balanceOf($tr)).to.eq(0))
    })
    step('Seller A: "Show statement".', 'The paid lines read "Paid" with the payout number; the ledger has a PAYOUT line PO-… for the amount; "MaxTheService owes you Rs 0."', () => {
      statement()
      cy.get('#mktStatementTable tbody tr.mkt-line[data-status="PAID"]').should('have.length.at.least', 1).first().should('contain', G.po)
        .scrollIntoView({ offset: { top: -250, left: 0 } })
      cy.get('#mktLedgerTable tbody tr.mkt-entry[data-entry-type="PAYOUT"]').filter(`:contains("${G.po}")`).should('have.length', 1)
      cy.get('#mktBalance').should('contain', 'owes you Rs 0')
    })
    cleanup('None: a paid payout is final (a mistake is corrected with a new ledger line, M-1g-05).', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-1g-05', slice: 'MKT-1g', title: 'Mistakes are corrected with a new line, never edited', persona: 'MaxTheService operator',
    reqs: ['MKT-R15.6', 'MKT-R16.2'], auto: ['MKT-1g-05'], pre: 'Shahzad Mobile Shop has ledger lines (M-1g-01 to -04).' }, (step, call, cleanup) => {
    step('Operator: Settlement and payouts → Shahzad Mobile Shop → "Ledger".', 'The seller\'s lines are listed; no line has an edit or delete control. Below them: an amount, a reason and "Record correction".', () => {
      payouts()
      sellerRow().then(($tr) => { G.bal5 = balanceOf($tr) })
      sellerRow().find('.mkt-ledger').click()
      cy.get('#mktOpsLedger .mkt-ops-ledger tbody tr.mkt-entry').should('have.length.at.least', 2).then(($rows) => { G.rows5 = $rows.length })
      cy.get('#mktOpsLedger .mkt-ops-ledger').find('button, input').should('not.exist')
      cy.get('#mktAdjSave').should('be.visible')
    })
    step('Type -100 and the reason "walk correction" → "Record correction".', 'A new ADJUSTMENT line "walk correction", 100 owed by the seller, is added at the top; every earlier line is unchanged; the balance is 100 lower.', () => {
      cy.get('#mktAdjAmount').type('-100')
      cy.get('#mktAdjReason').type('walk correction')
      cy.get('#mktAdjSave').click()
      cy.get('#mktOpsLedger .mkt-ops-ledger tbody tr.mkt-entry').should('have.length', G.rows5 + 1).first()
        .should('have.attr', 'data-entry-type', 'ADJUSTMENT').and('contain', 'walk correction').and('contain', '100')
      sellerRow().should(($tr) => expect(Math.round((G.bal5 - balanceOf($tr)) * 100)).to.eq(10000))
    })
    step('Developer tools: try to change a ledger line (PUT /platform/mkt/ledgerEntry).', 'There is no such route (404 or 405): the ledger has no edit path.', () => {
      call('PUT /platform/mkt/ledgerEntry', cy.request({ method: 'PUT', url: '/platform/mkt/ledgerEntry', failOnStatusCode: false, body: { id: 1, credit: 0 } }))
        .then((r) => expect(r.status).to.be.oneOf([404, 405]))
    }, { screen: false })
    cleanup('Record the opposite correction: +100, reason "walk correction undone".', 'A second ADJUSTMENT line; the balance is back where it was.', () => {
      cy.get('#mktAdjAmount').clear().type('100')
      cy.get('#mktAdjReason').clear().type('walk correction undone')
      cy.get('#mktAdjSave').click()
      cy.get('#mktOpsLedger .mkt-ops-ledger tbody tr.mkt-entry').first().should('contain', 'walk correction undone')
      sellerRow().should(($tr) => expect(Math.round(balanceOf($tr) * 100)).to.eq(Math.round(G.bal5 * 100)))
    })
  })

  walk({ id: 'M-1g-06', slice: 'MKT-1g', title: 'Commission reaches the books', persona: 'MaxTheService operator (admin@myplus.com)',
    reqs: ['MKT-R15.5', 'MKT-R1.3'], auto: ['MKT-1g-06'], pre: 'A new Rs 52,000 order paid online, delivered today on a business day; T+0, 0 return days.' }, (step, call, cleanup) => {
    step('Operator: read the trial balance of the books commission is booked in (GET /gl/trialBalance), account 4500 Marketplace Commission.', 'Note the 4500 balance (0 on a fresh system).', () => {
      asOperator()
      call('GET /gl/trialBalance', get('/gl/trialBalance')).then((r) => { G.rev0 = creditOf(tbRows(r), '4500') })
    }, { screen: false })
    cy.then(() => gDelivered(gph(6)).then((o) => { G.o6 = o.no }))
    step('Settlement and payouts → "Settle what is due now".', '"Settled 1 line(s)."', () => { payouts(); settleNow().invoke('text').should('match', /Settled [1-9]/) })
    step('Read the trial balance again.', '4500 Marketplace Commission is higher by exactly 4,160.00 (8% of 52,000); 2400 Marketplace Seller Balances moved by the 47,840.00 payable; the journal arrives within seconds through the outbox.', () => {
      asOperator()
      const until = (n) => get('/gl/trialBalance').then((r) => {
        const rows = tbRows(r)
        const rev1 = creditOf(rows, '4500')
        if (Math.round((rev1 - G.rev0) * 100) === 416000 || n === 0) {
          call('GET /gl/trialBalance', cy.wrap({ status: r.status, body: rows.filter((a) => ['1010', '2400', '4500'].includes(a.code)) }))
          expect(Math.round((rev1 - G.rev0) * 100), '4500 moved by exactly the commission').to.eq(416000)
          return
        }
        cy.wait(1000)
        until(n - 1)
      })
      until(20)
    }, { screen: false })
    cleanup('Nothing to undo: the journal is the record.', '—', () => {}, { screen: false })
  })

  // ──────────────────────────────── MKT-2a ────────────────────────────────

  const H = {}
  const hph = (k) => `0315${String(run).slice(-6)}${k}`            // customer phones for the 2a walk
  const H_PW = 'Shop!ng2026'
  /** Two sellers on ONE product: A at 52,000 (promise 4 h), B at 51,500 (promise 24 h). Published once, by the first 2a case. */
  const hOffers = () => (H.a ? cy.wrap(H) : seedPolicies(`${run}m`).then((p) => {
    publishOffer(SELLER_A, { run: `${run}m`, price: 52000, promiseHours: 4, qty: 40, warrantyPolicyId: p.warranty, returnPolicyId: p.returns })
      .then((o) => { H.a = o.offerId; H.product = o.mktProductId })
    cy.then(() => publishOffer(SELLER_B, { run: `${run}mb`, price: 51500, promiseHours: 24, qty: 40, mktProductId: H.product,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns })).then((o) => { H.b = o.offerId })
    return cy.then(() => H)
  }))
  /** The operator's switch, through the API (a case's starting state; M-2a-01 shows the screen). */
  const multiSeller = (on) => { asOperator(); return post(API.acceptWindow, { multiSeller: on, minutes: 5 }) }
  /** Platform → Marketplace policies: the switch as the operator sees it. */
  const policies = () => {
    console_('#platMktPoliciesBtn')
    cy.get('#mktMultiSellerForm').scrollIntoView().should('be.visible')
    return cy.get('#mktMultiSeller').should('be.enabled')     // its saved state has loaded
  }
  /** Customer: on the product page add A's offer (and B's, `qtyB` of it) to an empty basket and open the basket. */
  const fillBasket = (qtyB = 1, { withA = true, withB = true, product, a, b } = {}) => {
    customer()
    cy.visit(page(`product=${product || H.product}&city=Karachi`), { onBeforeLoad: (w) => w.localStorage.removeItem('mkt.basket') })
    if (withA) { cy.get(`${UI.offerRow}[data-offer-id="${a || H.a}"] ${UI.chooseOffer}`).check(); cy.get('#mktAddBtn').click() }
    if (withB) { cy.get(`${UI.offerRow}[data-offer-id="${b || H.b}"] ${UI.chooseOffer}`).check(); cy.get('#mktAddBtn').click() }
    cy.get('#mktBasketBtn').should('be.visible').click()
    if (withB && qtyB > 1) cy.contains('.mkt-basket-group', B_NAME).find('.mkt-basket-qty').select(String(qtyB))
  }
  const contact = (ph, name = 'Ali') => {
    cy.get('#mktCoName').clear().type(name)
    cy.get('#mktCoPhone').clear().type(ph)
    cy.get('#mktCoAddress').clear().type('1 Clifton')
  }
  /** A basket order placed through the API (a case's starting state). Yields the order. */
  const hBasket = (ph, over = {}, signedIn = false) => {
    if (!signedIn) { customer(); cy.visit(UI.publicPage) }
    return post(API.checkout, Object.assign({ customerName: 'Ali', customerPhone: ph, address: '1 Clifton', city: 'Karachi',
      idempotencyKey: `w2a-${run}-${ph}`, lines: [{ offerId: H.a, quantity: 1, expectedPrice: 52000 }, { offerId: H.b, quantity: 1, expectedPrice: 51500 }] }, over))
      .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  }
  const hAccount = (ph, name) => {
    customer()
    cy.visit(UI.publicPage)
    return post('/marketplace/account/login', { phone: ph, password: H_PW }).then((r) => {
      if (!ok(r.body)) post('/marketplace/account/register', { phone: ph, name, password: H_PW })
    })
  }
  const hMyOrders = (ph, name) => { hAccount(ph, name); cy.visit(page('account=orders')); cy.get('#mktAccOrdersBox').should('be.visible') }
  const rowOf = (no) => cy.get(`#mktMyOrders li[data-order-no="${no}"]`)
  /** The seller's own part of order `no`, in its Incoming list (status filter: Waiting for you, or All). */
  const partRow = (email, no, all = false) => {
    as(email)
    openMarketplace()
    if (all) cy.get('#mktIncomingStatus').select('', { force: true })
    return cy.contains(`${UI.incoming} tr`, no)
  }
  /** Behind the scenes: the seller answers its part (cleanup, or a case's starting state). */
  const answer = (email, no, verb) => {
    as(email)
    return get(`${API.incomingOrders}?status=OFFERED&size=100`).then((r) => {
      const so = list(r.body).find((x) => x.orderNo === no)
      if (!so) return null
      return verb === 'accept' ? post(API.acceptOrder, { id: so.id, version: so.version })
        : post(API.rejectOrder, { id: so.id, version: so.version, reason: 'walk cleanup' })
    })
  }
  const orderPage = (no, ph) => { customer(); cy.visit(page(`order=${encodeURIComponent(no)}&phone=${ph}`)) }
  const part = (seller) => cy.get(`#mktOrderParts .mkt-part[data-seller="${seller}"]`)

  walk({ id: 'M-2a-01', slice: 'MKT-2a', title: 'The operator decides whether one order can hold several sellers',
    persona: 'MaxTheService operator (admin@myplus.com), then a customer (incognito window)', reqs: ['MKT-R17.1', 'MKT-R17.2'],
    pre: 'Shahzad Mobile Shop (Rs 52,000) and Mobile Distributor (Rs 51,500) both sell the same phone in Karachi. The switch is off (the default).',
    auto: ['MKT-2a-01'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => multiSeller(false))
    step('Operator: Platform → "Marketplace policies".',
      'Under the acceptance minutes: "Customers can buy from several sellers in one order", UNTICKED, with the hint "Each seller confirms, delivers and is paid for its own part. When off, a basket with two sellers is refused."', () => {
        policies()
        cy.get('#mktMultiSeller').should('not.be.checked')
        cy.get('#mktMultiSellerForm').should('contain', 'When off, a basket with two sellers is refused.')
      })
    step(`Customer: open the phone (Karachi). Choose ${A_NAME} → "Add to basket"; choose ${B_NAME} → "Add to basket". Press "Basket (2)" at the top.`,
      `The basket lists the two sellers separately, one phone each; the total is Rs. 103,500. The note says each seller confirms and delivers its own items.`, () => {
        fillBasket()
        cy.get('.mkt-basket-group').should('have.length', 2)
        cy.get('#mktCoTotal').should('contain', 'Rs. 103,500')
        cy.get('#mktCoBasket').should('contain', 'Each seller confirms and delivers its own items.')
      })
    step(`Name "Ali", phone ${hph(1)}, address "1 Clifton" → "Place order".`,
      'Refused under the button: "Items from different sellers must be checked out separately." No order number; nothing is held.', () => {
        contact(hph(1))
        cy.get('#mktCoPlace').click()
        cy.get('#mktCoError').should('contain', 'Items from different sellers must be checked out separately.')
        cy.get('#mktCoOrderNo').should('have.text', '')
      })
    step('Operator: tick "Customers can buy from several sellers in one order" → Save.', '"Order settings saved." Reopening the panel shows it ticked.', () => {
      policies()
      cy.get('#mktMultiSeller').check()
      cy.get('#mktMultiSellerSave').click()
      cy.get('#mktMultiSellerMsg').should('contain', 'Order settings saved.')
      policies()
      cy.get('#mktMultiSeller').should('be.checked')
    })
    step('Customer: the same basket → "Place order" again.', '"Waiting for the sellers to confirm" with an order number MKT-…; one row per seller, each "Waiting for confirmation · m:ss left".', () => {
      fillBasket()
      contact(hph(1))
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', 'Waiting for the sellers to confirm')
      cy.get('#mktOrderParts .mkt-part').should('have.length', 2)
      cy.get('#mktCoOrderNo').invoke('text').then((no) => { H.o1 = no })
    })
    cleanup('Both sellers reject their part with the reason "walk cleanup". Operator: untick the switch → Save.',
      'The order reads Cancelled and its stock is back; the switch reads unticked again.', () => {
        cy.then(() => answer(SELLER_A, H.o1, 'reject'))
        cy.then(() => answer(SELLER_B, H.o1, 'reject'))
        policies()
        cy.get('#mktMultiSeller').uncheck()
        cy.get('#mktMultiSellerSave').click()
        cy.get('#mktMultiSellerMsg').should('contain', 'Order settings saved.')
      })
  })

  walk({ id: 'M-2a-02', slice: 'MKT-2a', title: 'One basket, one checkout, one part per seller',
    persona: 'Customer (incognito window)', reqs: ['MKT-R17.2', 'MKT-R20.3'],
    pre: 'As M-2a-01, with the switch ON.', auto: ['MKT-2a-02'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => multiSeller(true))
    step(`Open the phone (Karachi). Choose ${A_NAME} → "Add to basket".`, `Under the button: "Added to your basket: … from ${A_NAME}." The top shows "Basket (1)".`, () => {
      customer()
      cy.visit(page(`product=${H.product}&city=Karachi`), { onBeforeLoad: (w) => w.localStorage.removeItem('mkt.basket') })
      cy.get(`${UI.offerRow}[data-offer-id="${H.a}"] ${UI.chooseOffer}`).check()
      cy.get('#mktAddBtn').click()
      cy.get('#mktBasketMsg').should('contain', `from ${A_NAME}`)
      cy.get('#mktBasketBtn').should('contain', 'Basket (1)')
    })
    step(`Choose ${B_NAME} → "Add to basket". Press "Basket (2)". Set ${B_NAME}'s quantity to 2.`,
      `Two groups, ${A_NAME} (1 × Rs 52,000) and ${B_NAME} (2 × Rs 51,500). Total Rs. 155,000.`, () => {
        cy.get(`${UI.offerRow}[data-offer-id="${H.b}"] ${UI.chooseOffer}`).check()
        cy.get('#mktAddBtn').click()
        cy.get('#mktBasketBtn').should('contain', 'Basket (2)').click()
        cy.contains('.mkt-basket-group', B_NAME).find('.mkt-basket-qty').select('2')
        cy.get('.mkt-basket-group').should('have.length', 2)
        cy.get('#mktCoTotal').should('contain', 'Rs. 155,000')
      })
    step(`Name "Ali", phone ${hph(2)}, address "1 Clifton", cash on delivery → "Place order".`,
      `"Waiting for the sellers to confirm", one order number, and one row per seller: ${A_NAME} Rs. 52,000 and ${B_NAME} Rs. 103,000, each with its own "m:ss left". The Basket button is gone.`, () => {
        contact(hph(2))
        cy.get('#mktCoPlace').click()
        cy.get(UI.checkoutStatus).should('contain', 'Waiting for the sellers to confirm')
        part(A_NAME).should('contain', 'Rs. 52,000').and('contain', 'left')
        part(B_NAME).should('contain', 'Rs. 103,000').and('contain', 'left')
        cy.get('#mktBasketBtn').should('not.be.visible')
        cy.get('#mktCoOrderNo').invoke('text').then((no) => { H.o2 = no })
      })
    step('Developer tools: GET the order (track with the phone).', 'One order, total 155,000, two sellerOrders with different sellers, each OFFERED with its own promisedBy and deliveryFee.', () => {
      call('GET /marketplace/public/orders/{no}', get(API.trackOrder(H.o2, hph(2)))).then((r) => {
        const o = data(r.body)
        expect(Number(o.total)).to.eq(155000)
        expect(o.sellerOrders).to.have.length(2)
        expect(new Set(o.sellerOrders.map((s) => s.sellerOrganizationId)).size).to.eq(2)
        o.sellerOrders.forEach((s) => { expect(s.status).to.eq('OFFERED'); expect(s).to.include.keys('promisedBy', 'deliveryFee') })
      })
    }, { screen: false })
    cleanup('Both sellers reject their part with the reason "walk cleanup".', 'The order page reads "Cancelled"; the held stock is released.', () => {
      cy.then(() => answer(SELLER_A, H.o2, 'reject'))
      cy.then(() => answer(SELLER_B, H.o2, 'reject'))
      orderPage(H.o2, hph(2))
      cy.get(UI.checkoutStatus).should('contain', 'Cancelled')
    })
  })

  walk({ id: 'M-2a-03', slice: 'MKT-2a', title: 'Each seller sees only its own part',
    persona: 'owner.business@myplus.com (Shahzad Mobile Shop), then Mobile Distributor\'s owner', reqs: ['MKT-R17.2', 'MKT-R22.1'],
    pre: `A customer placed one basket: 1 phone from ${A_NAME}, 2 from ${B_NAME} (Rs 155,000 in all). The switch is ON.`, auto: ['MKT-2a-03'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => multiSeller(true))
    cy.then(() => hBasket(hph(3), { lines: [{ offerId: H.a, quantity: 1, expectedPrice: 52000 }, { offerId: H.b, quantity: 2, expectedPrice: 51500 }] }))
      .then((o) => { H.o3 = o.orderNo })
    step(`As owner.business@myplus.com: Sale → Marketplace → "Incoming marketplace orders".`,
      `The order is listed with ONE phone and Rs 52,000: ${B_NAME}'s items and the basket total of 155,000 appear nowhere.`, () => {
        partRow(SELLER_A, H.o3).should('contain', '52,000').and('not.contain', '155,000').and('not.contain', '103,000').and('not.contain', B_NAME)
      })
    step(`As ${B_NAME}'s owner: the same screen.`, `The same order number with 2 phones and Rs 103,000; nothing of ${A_NAME}'s.`, () => {
      partRow(SELLER_B, H.o3).should('contain', '103,000').and('not.contain', '155,000').and('not.contain', A_NAME)
    })
    cleanup('Both sellers reject their part with the reason "walk cleanup".', 'Both rows read Rejected; the stock is released.', () => {
      cy.then(() => answer(SELLER_A, H.o3, 'reject'))
      cy.then(() => answer(SELLER_B, H.o3, 'reject'))
    }, { screen: false })
  })

  walk({ id: 'M-2a-04', slice: 'MKT-2a', title: 'One seller accepts, the other rejects: the order goes ahead for the accepted part',
    persona: 'Both sellers, then the customer', reqs: ['MKT-R17.2', 'MKT-R10.2'],
    pre: `A customer placed a basket: 1 phone from each seller, cash on delivery. The switch is ON.`, auto: ['MKT-2a-04'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => multiSeller(true))
    cy.then(() => hBasket(hph(4))).then((o) => { H.o4 = o.orderNo })
    step(`As owner.business@myplus.com: Incoming → the order → Accept.`, '"Accepted" with an invoice: the sale of this part is in Shahzad Mobile Shop\'s books.', () => {
      partRow(SELLER_A, H.o4).find(UI.acceptBtn).click()
      cy.contains(`${UI.incoming} tr`, H.o4).should('contain', 'Accepted').and('contain', 'Invoice')
    })
    step('Customer: open the order page.', `"Confirmed by some sellers": ${A_NAME}'s row reads "Confirmed: ${A_NAME} will deliver and collect Rs. 52,000 in cash"; ${B_NAME}'s still counts down.`, () => {
      orderPage(H.o4, hph(4))
      cy.get(UI.checkoutStatus).should('contain', 'Confirmed by some sellers')
      part(A_NAME).should('contain', `Confirmed: ${A_NAME} will deliver and collect Rs. 52,000 in cash`)
      part(B_NAME).should('contain', 'left')
    })
    step(`As ${B_NAME}'s owner: Incoming → the order → reason "out of stock in store" → Reject.`, 'The row reads "Rejected" with the reason.', () => {
      partRow(SELLER_B, H.o4).find('input[placeholder*="cannot fulfil"]').type('out of stock in store')
      cy.contains(`${UI.incoming} tr`, H.o4).find(UI.rejectBtn).click()
      cy.contains(`${UI.incoming} tr`, H.o4).should('contain', 'Rejected').and('contain', 'out of stock in store')
    })
    step('Customer: reopen the order page.', `"Confirmed" and "Each seller delivers its own items."; ${B_NAME}'s row reads "The seller could not fulfil this part". The order is NOT cancelled.`, () => {
      orderPage(H.o4, hph(4))
      cy.get(UI.checkoutStatus).should('contain', 'Confirmed').and('not.contain', 'some sellers')
      cy.get('#mktOrderDetail').should('contain', 'Each seller delivers its own items.')
      part(B_NAME).should('contain', 'The seller could not fulfil this part')
    })
    cleanup(`None: ${A_NAME}'s accepted part is a real sale (return it with the store's Sale Returns if needed); ${B_NAME}'s stock was released on Reject.`, '—', () => {}, { screen: false })
  })

  walk({ id: 'M-2a-05', slice: 'MKT-2a', title: 'All or nothing: one seller short means nothing is ordered',
    persona: 'Two customers (two incognito windows)', reqs: ['MKT-R17.2', 'MKT-R10.2'],
    pre: 'Both sellers have a second phone with exactly ONE in stock each (another product). The switch is ON.', auto: ['MKT-2a-05'] }, (step, call, cleanup) => {
    cy.then(() => multiSeller(true))
    cy.then(() => seedPolicies(`${run}n`)).then((p) => {
      publishOffer(SELLER_A, { run: `${run}na`, price: 52000, qty: 1, warrantyPolicyId: p.warranty, returnPolicyId: p.returns })
        .then((o) => { H.sa = o.offerId; H.sp = o.mktProductId })
      cy.then(() => publishOffer(SELLER_B, { run: `${run}nb`, price: 51500, qty: 1, mktProductId: H.sp, warrantyPolicyId: p.warranty,
        returnPolicyId: p.returns })).then((o) => { H.sb = o.offerId })
    })
    step(`Customer 1 (phone ${hph(5)}): open that phone, choose ${B_NAME}, "Buy from ${B_NAME}", Place order.`, `"Waiting for ${B_NAME} to confirm": ${B_NAME}'s only unit is now held.`, () => {
      customer()
      cy.visit(page(`product=${H.sp}&city=Karachi`), { onBeforeLoad: (w) => w.localStorage.removeItem('mkt.basket') })
      cy.get(`${UI.offerRow}[data-offer-id="${H.sb}"] ${UI.chooseOffer}`).check()
      cy.get(UI.buyButton).click()
      contact(hph(5))
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', `Waiting for ${B_NAME} to confirm`)
      cy.get('#mktCoOrderNo').invoke('text').then((no) => { H.o5a = no })
    })
    step(`Customer 2 (phone ${hph(6)}): add both sellers' units to the basket → Place order.`,
      `Refused: "${B_NAME} no longer has enough stock. Please remove its items and place the order again." No order number.`, () => {
        fillBasket(1, { product: H.sp, a: H.sa, b: H.sb })
        contact(hph(6), 'Sara')
        cy.get('#mktCoPlace').click()
        cy.get('#mktCoError').should('contain', `${B_NAME} no longer has enough stock. Please remove its items and place the order again.`)
      })
    step(`Remove ${B_NAME}'s item ("Remove") → Place order.`, `"Waiting for ${A_NAME} to confirm": ${A_NAME}'s unit was not lost to the refused attempt.`, () => {
      cy.contains('.mkt-basket-group', B_NAME).find('.mkt-remove').click()
      cy.get('.mkt-basket-group').should('have.length', 1)
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', `Waiting for ${A_NAME} to confirm`)
      cy.get('#mktCoOrderNo').invoke('text').then((no) => { H.o5b = no })
    })
    cleanup('Each seller rejects its waiting order with the reason "walk cleanup".', 'Both orders read Cancelled; each unit is back in stock.', () => {
      cy.then(() => answer(SELLER_B, H.o5a, 'reject'))
      cy.then(() => answer(SELLER_A, H.o5b, 'reject'))
    }, { screen: false })
  })

  walk({ id: 'M-2a-06', slice: 'MKT-2a', title: 'Paid online: one charge, and a rejected part is refunded on its own, once',
    persona: 'Customer with an account, then Mobile Distributor\'s owner', reqs: ['MKT-R17.2', 'MKT-R13.1'],
    pre: `The customer (phone ${'0315…6'}) has an account. The switch is ON. The test card token "tok_ok" is accepted.`, auto: ['MKT-2a-06'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => multiSeller(true))
    step(`Signed in as ${hph(7)}: basket with one phone from each seller → "Pay online", card token "tok_ok" → Place order.`,
      '"Waiting for the sellers to confirm"; Rs. 103,500 is charged once.', () => {
        hAccount(hph(7), 'Sana Basket')
        cy.visit(page(`product=${H.product}&city=Karachi`), { onBeforeLoad: (w) => w.localStorage.removeItem('mkt.basket') })
        cy.get(`${UI.offerRow}[data-offer-id="${H.a}"] ${UI.chooseOffer}`).check(); cy.get('#mktAddBtn').click()
        cy.get(`${UI.offerRow}[data-offer-id="${H.b}"] ${UI.chooseOffer}`).check(); cy.get('#mktAddBtn').click()
        cy.get('#mktBasketBtn').click()
        contact(hph(7), 'Sana Basket')
        cy.get('#mktPayCard').check()
        cy.get('#mktCardToken').clear().type('tok_ok')
        cy.get('#mktCoPlace').click()
        cy.get(UI.checkoutStatus).should('contain', 'Waiting for the sellers to confirm')
        cy.get('#mktCoOrderNo').invoke('text').then((no) => { H.o6 = no })
      })
    step(`As ${B_NAME}'s owner: Incoming → the order → reason "no stock" → Reject.`, 'The row reads "Rejected".', () => {
      partRow(SELLER_B, H.o6).find('input[placeholder*="cannot fulfil"]').type('no stock')
      cy.contains(`${UI.incoming} tr`, H.o6).find(UI.rejectBtn).click()
      cy.contains(`${UI.incoming} tr`, H.o6).should('contain', 'Rejected')
    })
    step('Customer: My orders.', `The order reads "Waiting for the sellers to confirm" and Rs. 103,500 · "Partly refunded"; ${B_NAME}'s row reads "The seller could not fulfil this part"; ${A_NAME}'s still waits.`, () => {
      hMyOrders(hph(7), 'Sana Basket')
      rowOf(H.o6).should('contain', 'Waiting for the sellers to confirm').and('contain', 'Partly refunded')
      rowOf(H.o6).find(`.mkt-acc-part[data-seller="${B_NAME}"]`).should('contain', 'The seller could not fulfil this part')
      rowOf(H.o6).find(`.mkt-acc-part[data-seller="${A_NAME}"]`).should('contain', 'Waiting for confirmation')
    })
    step('Developer tools: GET /marketplace/account/orders, the order\'s payments.', 'Exactly ONE CHARGE of 103,500 and ONE REFUND of 51,500 (B\'s part). Rejecting the part again changes nothing.', () => {
      call('GET /marketplace/account/orders', get('/marketplace/account/orders')).then((r) => {
        const o = list(r.body).find((x) => x.orderNo === H.o6)
        const charges = o.payments.filter((p) => p.kind === 'CHARGE')
        const refunds = o.payments.filter((p) => p.kind === 'REFUND')
        expect(charges).to.have.length(1)
        expect(Number(charges[0].amount)).to.eq(103500)
        expect(refunds).to.have.length(1)
        expect(Number(refunds[0].amount)).to.eq(51500)
      })
    }, { screen: false })
    cleanup(`${A_NAME} rejects its part with the reason "walk cleanup".`, 'The order reads "Cancelled" and "Refunded": the remaining 52,000 is refunded, and nothing is refunded twice.', () => {
      cy.then(() => answer(SELLER_A, H.o6, 'reject'))
      hMyOrders(hph(7), 'Sana Basket')
      rowOf(H.o6).should('contain', 'Cancelled').and('contain', 'Refunded')
    })
  })

  walk({ id: 'M-2a-07', slice: 'MKT-2a', title: 'The customer cancels a basket no seller has answered',
    persona: 'Customer with an account, then owner.business@myplus.com', reqs: ['MKT-R17.2', 'MKT-R10.5'],
    pre: 'The customer placed a basket (one phone from each seller, cash on delivery) and neither seller has answered. The switch is ON.', auto: ['MKT-2a-07'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => multiSeller(true))
    cy.then(() => hAccount(hph(8), 'Bilal Basket')).then(() => hBasket(hph(8), { customerName: 'Bilal Basket' }, true)).then((o) => { H.o7 = o.orderNo })
    step(`Signed in as ${hph(8)}: My orders.`, 'The order lists both sellers, each "Waiting for confirmation", and offers "Cancel order".', () => {
      hMyOrders(hph(8), 'Bilal Basket')
      rowOf(H.o7).find('.mkt-acc-part').should('have.length', 2).each(($p) => expect($p.text()).to.contain('Waiting for confirmation'))
      rowOf(H.o7).find('.mkt-cancel').should('be.visible')
    })
    step('"Cancel order" → reason "ordered twice" → "Cancel order".', 'The order reads "Cancelled"; BOTH sellers\' rows read Cancelled; the Cancel button is gone.', () => {
      rowOf(H.o7).find('.mkt-cancel').click()
      cy.get('#uiC-input').type('ordered twice')
      cy.get('.uiC-ok').click()
      rowOf(H.o7).should('contain', 'Cancelled').find('.mkt-cancel').should('not.exist')
      rowOf(H.o7).find('.mkt-acc-part').each(($p) => expect($p.text()).to.contain('Cancelled'))
    })
    step('As owner.business@myplus.com: Incoming → "All".', 'Shahzad Mobile Shop\'s part reads "Cancelled": the seller is told and the held stock was given back.', () => {
      partRow(SELLER_A, H.o7, true).should('contain', 'Cancelled')
    })
    cleanup('Nothing to undo: the order ended and every hold was released.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-2a-08', slice: 'MKT-2a', title: 'Help is per seller: the customer picks the item, the case goes to that seller',
    persona: 'Customer with an account, then the MaxTheService operator', reqs: ['MKT-R8.2', 'MKT-R17.2'],
    pre: 'The customer\'s basket (one phone from each seller) was ACCEPTED by both sellers. The switch is ON.', auto: ['MKT-2a-08'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => multiSeller(true))
    cy.then(() => hAccount(hph(9), 'Hina Basket')).then(() => hBasket(hph(9), { customerName: 'Hina Basket' }, true)).then((o) => { H.o8 = o.orderNo })
    cy.then(() => answer(SELLER_A, H.o8, 'accept'))
    cy.then(() => answer(SELLER_B, H.o8, 'accept'))
    step(`Signed in as ${hph(9)}: My orders → the order → "Get help".`,
      `The form asks "Item" first, listing each phone with its seller ("… × 1 · ${A_NAME}", "… × 1 · ${B_NAME}"), then what you need help with.`, () => {
        hMyOrders(hph(9), 'Hina Basket')
        rowOf(H.o8).find('.mkt-help').click()
        rowOf(H.o8).find('.mkt-help-item option').should('have.length', 2)
        rowOf(H.o8).find('.mkt-help-item').should('contain', A_NAME).and('contain', B_NAME)
      })
    step(`Item: ${B_NAME}'s phone; "Something is wrong with my order"; note "Where is the second phone?" → "Send to MaxTheService".`,
      `A help request "Help request SC-… · ${B_NAME}" appears under the order: it is about ${B_NAME}'s part only.`, () => {
        rowOf(H.o8).find('.mkt-help-item option').contains(B_NAME).then(($o) => rowOf(H.o8).find('.mkt-help-item').select($o.val()))
        rowOf(H.o8).find('.mkt-help-topic').select('ORDER_PROBLEM')
        rowOf(H.o8).find('.mkt-help-note').type('Where is the second phone?')
        rowOf(H.o8).find('.mkt-help-send').click()
        rowOf(H.o8).find('.mkt-case').should('contain', 'SC-').and('contain', B_NAME).invoke('attr', 'data-case-no').then((c) => { H.c8 = c })
      })
    step('Operator: Platform → "Support cases" → the case.', `The case shows the order, the customer's note, and ${B_NAME} as the seller tasked; ${A_NAME} is not involved.`, () => {
      supportCases(); openCaseRow(H.c8)
      cy.get('#mktCaseDetail').should('contain', 'Where is the second phone?').and('contain', B_NAME)
    })
    cleanup('Operator: the case → write "walk cleanup" → Resolve. Operator: Platform → Marketplace policies → untick the switch → Save.',
      'The case reads Resolved; the switch is off again (the default).', () => {
        cy.get('#mktCaseDetail .mkt-op-reply').type('walk cleanup')
        cy.get('#mktCaseDetail .mkt-op-resolve').click()
        cy.get('#platMktCaseStatus button[data-status="RESOLVED"]').click()
        cy.get(`#mktCaseList tr[data-case-no="${H.c8}"]`).should('contain', 'Resolved')
        cy.then(() => multiSeller(false))
      })
  })

  // ──────────────────────────────── MKT-2b ────────────────────────────────

  const K = {}
  const kph = (k) => `0316${String(run).slice(-6)}${k}`            // customer phones for the 2b walk
  /**
   * Three phones, each sold by both sellers, so every case has exactly one other seller to try (published once):
   *   later   A 52,000 in 4 h · B 51,500 in 24 h   (cheaper but later: the customer is asked)
   *   quiet   A 52,000 in 24 h · B 51,500 in 4 h   (cheaper and sooner: moved without asking)
   *   dearer  A 51,000 in 4 h · B 51,500 in 4 h    (dearer: asked if cash, never offered to a card)
   */
  const kOffers = () => (K.later ? cy.wrap(K) : seedPolicies(`${run}k`).then((p) => {
    const pair = (key, aPrice, aHours, bPrice, bHours) => {
      K[key] = {}
      publishOffer(SELLER_A, { run: `${run}k${key}`, price: aPrice, promiseHours: aHours, qty: 40, warrantyPolicyId: p.warranty,
        returnPolicyId: p.returns }).then((o) => { K[key].a = o.offerId; K[key].aPrice = aPrice; K[key].product = o.mktProductId })
      cy.then(() => publishOffer(SELLER_B, { run: `${run}k${key}b`, price: bPrice, promiseHours: bHours, qty: 40,
        mktProductId: K[key].product, warrantyPolicyId: p.warranty, returnPolicyId: p.returns })).then((o) => { K[key].b = o.offerId })
    }
    pair('later', 52000, 4, 51500, 24)
    pair('quiet', 52000, 24, 51500, 4)
    pair('dearer', 51000, 4, 51500, 4)
    return cy.then(() => K)
  }))
  /** The operator's reroute switch, through the API (a case's starting state; M-2b-01 shows the screen). */
  const reroute = (on) => { asOperator(); return post(API.acceptWindow, { reroute: on, minutes: 5, multiSeller: false }) }
  /** A one-seller order from Shahzad Mobile Shop, placed through the API (a case's starting state). */
  const kOrder = (key, ph, over = {}, signedIn = false) => {
    if (!signedIn) { customer(); cy.visit(UI.publicPage) }
    return post(API.checkout, Object.assign({ customerName: 'Ali', customerPhone: ph, address: '1 Clifton', city: 'Karachi',
      idempotencyKey: `w2b-${run}-${ph}`, lines: [{ offerId: K[key].a, quantity: 1, expectedPrice: K[key].aPrice }] }, over))
      .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  }
  /** Shahzad Mobile Shop rejects its part on Incoming, with a reason and a cause, as the owner does. */
  const rejectOnScreen = (no, reason, cause) => {
    partRow(SELLER_A, no).find('input[placeholder*="cannot fulfil"]').type(reason)
    if (cause) cy.contains(`${UI.incoming} tr`, no).find('.mkt-reject-cause').select(cause)
    cy.contains(`${UI.incoming} tr`, no).find(UI.rejectBtn).click()
    return cy.contains(`${UI.incoming} tr`, no).should('contain', 'Rejected')
  }
  const shortRow = () => part(A_NAME)

  walk({ id: 'M-2b-01', slice: 'MKT-2b', title: 'A rejection is recorded with its cause; with the switch off the order ends as before',
    persona: 'MaxTheService operator, then owner.business@myplus.com (Shahzad Mobile Shop), then the customer', reqs: ['MKT-R11.4', 'MKT-R12.4', 'MKT-R11.1'],
    pre: 'Both sellers sell the same phone in Karachi (Shahzad Mobile Shop Rs 52,000 in 24 h, Mobile Distributor Rs 51,500 in 4 h). A customer has ordered it from Shahzad Mobile Shop, cash on delivery.',
    auto: ['MKT-2b-01', 'MKT-2b-08'] }, (step, call, cleanup) => {
    cy.then(() => kOffers()).then(() => reroute(false))
    cy.then(() => kOrder('quiet', kph(1))).then((o) => { K.o1 = o.orderNo })
    step('Operator: Platform → "Marketplace policies".',
      'Below the multi-seller switch: "When a seller cannot fulfil a part, find another seller", UNTICKED, with the hint "Same price or lower and no later: moved without asking. Otherwise the customer chooses within 30 minutes. When off, the part is cancelled and refunded."', () => {
        console_('#platMktPoliciesBtn')
        cy.get('#mktRerouteForm').scrollIntoView().should('be.visible').and('contain', 'Otherwise the customer chooses within 30 minutes.')
        cy.get('#mktReroute').should('be.enabled').and('not.be.checked')
      })
    step('As owner.business@myplus.com: Sale → Marketplace → Incoming → the order. Open the "Cause" list under the reason box.',
      'Three causes: "Out of stock in my shop" (the default), "My supplier could not deliver", "MaxTheService showed the wrong stock". "Not accepted in time" is not offered: only the clock records it.', () => {
        partRow(SELLER_A, K.o1).find('.mkt-reject-cause option').then(($o) => {
          expect([...$o].map((x) => x.textContent)).to.deep.eq(['Out of stock in my shop', 'My supplier could not deliver', 'MaxTheService showed the wrong stock'])
        })
      })
    step('Reason "supplier did not deliver", cause "My supplier could not deliver" → Reject.',
      'The row reads "Rejected" with the reason and "Cause: My supplier could not deliver · Recorded", and a "Dispute this cause" button.', () => {
        rejectOnScreen(K.o1, 'supplier did not deliver', 'SUPPLIER_STALE_STOCK')
        cy.contains(`${UI.incoming} tr`, K.o1).find('.mkt-so-shortage').should('contain', 'Cause: My supplier could not deliver · Recorded')
          .find('.mkt-dispute').should('be.visible')
      })
    step('Customer: open the order page.', '"Cancelled" and "The seller could not fulfil this order.", exactly as before this slice: with the switch off no other seller is tried, and the recorded cause is not shown to the customer. Nothing is charged to the seller.', () => {
      orderPage(K.o1, kph(1))
      cy.get(UI.checkoutStatus).should('contain', 'Cancelled')
      cy.get('#mktOrderDetail').should('contain', 'The seller could not fulfil this order.')
      cy.get('#mktOrderParts').should('not.be.visible')
      call('GET /marketplace/public/orders/{no}', get(API.trackOrder(K.o1, kph(1)))).then((r) => {
        const v = data(r.body)
        expect(v.sellerOrders).to.have.length(1)
        expect(v.sellerOrders[0].shortage).to.eq(null)
      })
    }, { alsoScreen: true })
    cleanup('Nothing to undo: the order is already cancelled and its stock released; the cause stays on record by design.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-2b-02', slice: 'MKT-2b', title: 'Cheaper and sooner from another seller: the part moves without asking',
    persona: 'MaxTheService operator, Shahzad Mobile Shop, the customer, then Mobile Distributor', reqs: ['MKT-R11.1', 'MKT-R11.3'],
    pre: 'As M-2b-01: Mobile Distributor sells the SAME phone for less (Rs 51,500) and sooner (4 h). A new cash order from Shahzad Mobile Shop.',
    auto: ['MKT-2b-02'] }, (step, call, cleanup) => {
    cy.then(() => kOffers()).then(() => reroute(false))
    cy.then(() => kOrder('quiet', kph(2))).then((o) => { K.o2 = o.orderNo })
    step('Operator: Platform → "Marketplace policies" → tick "When a seller cannot fulfil a part, find another seller" → Save.', '"Order settings saved." Reopening the panel shows it ticked.', () => {
      console_('#platMktPoliciesBtn')
      cy.get('#mktReroute').should('be.enabled').check()
      cy.get('#mktRerouteSave').click()
      cy.get('#mktRerouteMsg').should('contain', 'Order settings saved.')
      console_('#platMktPoliciesBtn')
      cy.get('#mktReroute').should('be.enabled').and('be.checked')
    })
    step('As owner.business@myplus.com: Incoming → the order → reason "none left", cause "Out of stock in my shop" → Reject.', 'The row reads "Rejected", "Cause: Out of stock in my shop · Recorded".', () => {
      rejectOnScreen(K.o2, 'none left', 'MERCHANT_STALE_STOCK')
      cy.contains(`${UI.incoming} tr`, K.o2).find('.mkt-so-shortage').should('contain', 'Out of stock in my shop')
    })
    step('Customer: open the order page.', `Still waiting, not cancelled. ${A_NAME}'s row: "Moved to ${B_NAME} at the same or a lower price". ${B_NAME}'s row: Rs. 51,500, "Waiting for confirmation · m:ss left".`, () => {
      orderPage(K.o2, kph(2))
      cy.get(UI.checkoutStatus).should('not.contain', 'Cancelled')
      shortRow().should('contain', `Moved to ${B_NAME} at the same or a lower price`)
      part(B_NAME).should('contain', 'Rs. 51,500').and('contain', 'left')
    })
    step(`As ${B_NAME}'s owner: Sale → Marketplace → Incoming.`, 'The order is waiting for them with one phone at Rs 51,500 and its own countdown.', () => {
      partRow(SELLER_B, K.o2).should('contain', '51,500').find(UI.acceptBtn).should('be.visible')
    })
    cleanup(`${B_NAME} rejects with "walk cleanup". Operator: untick the switch → Save.`, 'The order reads Cancelled; the switch is off again (the default).', () => {
      cy.then(() => answer(SELLER_B, K.o2, 'reject'))
      cy.then(() => reroute(false))
      orderPage(K.o2, kph(2))
      cy.get(UI.checkoutStatus).should('contain', 'Cancelled')
    })
  })

  walk({ id: 'M-2b-03', slice: 'MKT-2b', title: 'A later delivery needs the customer: they accept the other seller',
    persona: 'Shahzad Mobile Shop, then the customer, then Mobile Distributor', reqs: ['MKT-R11.2'],
    pre: 'The switch is ON. Mobile Distributor sells the same phone for Rs 51,500 but in 24 h (Shahzad Mobile Shop promised 4 h). A new cash order from Shahzad Mobile Shop.',
    auto: ['MKT-2b-03'] }, (step, call, cleanup) => {
    cy.then(() => kOffers()).then(() => reroute(true))
    cy.then(() => kOrder('later', kph(3))).then((o) => { K.o3 = o.orderNo })
    step('As owner.business@myplus.com: Incoming → the order → reason "none left" → Reject.', 'The row reads "Rejected".', () => {
      rejectOnScreen(K.o3, 'none left')
    })
    step('Customer: open the order page.',
      `${A_NAME}'s row: "${A_NAME} could not fulfil these items. ${B_NAME} can deliver them for Rs. 51,500 (Rs. 500 less), within 24 hours.", "Answer within 29:5x…", and two buttons "Accept ${B_NAME}" and "Decline".`, () => {
        orderPage(K.o3, kph(3))
        shortRow().find('.mkt-sh-offer').should('contain', `${B_NAME} can deliver them for Rs. 51,500`).and('contain', 'Rs. 500 less').and('contain', 'within 24 hours')
        shortRow().find('.mkt-sh-left').should('contain', 'Answer within 29:')
        shortRow().find('.mkt-sh-accept').should('contain', `Accept ${B_NAME}`)
        shortRow().find('.mkt-sh-decline').should('be.visible')
      })
    step(`Press "Accept ${B_NAME}".`, `${A_NAME}'s row: "You chose ${B_NAME} for these items". A new row for ${B_NAME}: Rs. 51,500, "Waiting for confirmation · m:ss left".`, () => {
      shortRow().find('.mkt-sh-accept').click()
      shortRow().should('contain', `You chose ${B_NAME} for these items`)
      part(B_NAME).should('contain', 'Rs. 51,500').and('contain', 'left')
    })
    step(`As ${B_NAME}'s owner: Incoming.`, 'The order waits for them at Rs 51,500.', () => {
      partRow(SELLER_B, K.o3).should('contain', '51,500')
    })
    cleanup(`${B_NAME} rejects with "walk cleanup".`, 'The order reads Cancelled; both holds are released.', () => {
      cy.then(() => answer(SELLER_B, K.o3, 'reject'))
      orderPage(K.o3, kph(3))
      cy.get(UI.checkoutStatus).should('contain', 'Cancelled')
    })
  })

  walk({ id: 'M-2b-04', slice: 'MKT-2b', title: 'The customer declines the other seller: the order ends with their reason',
    persona: 'Shahzad Mobile Shop, then the customer', reqs: ['MKT-R11.2'],
    pre: 'As M-2b-03, a new cash order.', auto: ['MKT-2b-04'] }, (step, call, cleanup) => {
    cy.then(() => kOffers()).then(() => reroute(true))
    cy.then(() => kOrder('later', kph(4))).then((o) => { K.o4 = o.orderNo })
    step('As owner.business@myplus.com: Incoming → the order → reason "none left" → Reject.', 'The row reads "Rejected".', () => {
      rejectOnScreen(K.o4, 'none left')
    })
    step('Customer: open the order page → "Decline".', '"Cancelled" with "You declined the alternative offered for this order."; the row reads "You declined the alternative". The held phone at Mobile Distributor is released.', () => {
      orderPage(K.o4, kph(4))
      shortRow().find('.mkt-sh-decline').click()
      cy.get(UI.checkoutStatus).should('contain', 'Cancelled')
      cy.get('#mktOrderDetail').should('contain', 'You declined the alternative offered for this order.')
      shortRow().should('contain', 'You declined the alternative')
    })
    cleanup('Nothing to undo: the order is cancelled and every hold released.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-2b-05', slice: 'MKT-2b', title: 'Paid by card: a dearer seller is never offered; the money goes back',
    persona: 'A signed-in customer (paid by card), then Shahzad Mobile Shop', reqs: ['MKT-R11.2', 'MKT-R13.1'],
    pre: 'The switch is ON. Shahzad Mobile Shop sells a phone at Rs 51,000; Mobile Distributor sells the same phone at Rs 51,500. The customer has an account and paid by card.',
    auto: ['MKT-2b-05'] }, (step, call, cleanup) => {
    cy.then(() => kOffers()).then(() => reroute(true))
    cy.then(() => hAccount(kph(5), 'Sana Card'))
    cy.then(() => kOrder('dearer', kph(5), { customerName: 'Sana Card', paymentMode: 'CARD', cardToken: 'tok_ok' }, true)).then((o) => { K.o5 = o.orderNo })
    step('As owner.business@myplus.com: Incoming → the order → reason "none left" → Reject.', 'The row reads "Rejected".', () => {
      rejectOnScreen(K.o5, 'none left')
    })
    step('Customer: My orders.', '"Cancelled" and "Rs. 51,000 · Refunded"; the part reads "No other seller had these items". The dearer phone at Mobile Distributor was not offered, because a card is never asked for more.', () => {
      hMyOrders(kph(5), 'Sana Card')
      rowOf(K.o5).should('contain', 'Cancelled').and('contain', 'Refunded')
      rowOf(K.o5).find('.mkt-shortage').should('contain', 'No other seller had these items')
    })
    cleanup('Nothing to undo: the order is cancelled and the card refunded.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-2b-06', slice: 'MKT-2b', title: 'The customer cancels while the other seller waits for their answer',
    persona: 'A signed-in customer, after Shahzad Mobile Shop rejected', reqs: ['MKT-R10.5', 'MKT-R11.2'],
    pre: 'As M-2b-03, ordered by a signed-in customer; Shahzad Mobile Shop has rejected, and Mobile Distributor is offered for their answer.',
    auto: ['MKT-2b-06'] }, (step, call, cleanup) => {
    cy.then(() => kOffers()).then(() => reroute(true))
    cy.then(() => hAccount(kph(6), 'Bilal Answer'))
    cy.then(() => kOrder('later', kph(6), { customerName: 'Bilal Answer' }, true)).then((o) => { K.o6 = o.orderNo })
    cy.then(() => answer(SELLER_A, K.o6, 'reject'))
    step('Customer: My orders.', `The order reads "Waiting for your answer"; ${A_NAME}'s row offers ${B_NAME} with "Accept ${B_NAME}" and "Decline"; "Cancel order" is there.`, () => {
      hMyOrders(kph(6), 'Bilal Answer')
      rowOf(K.o6).should('contain', 'Waiting for your answer')
      rowOf(K.o6).find('.mkt-sh-accept').should('contain', `Accept ${B_NAME}`)
      rowOf(K.o6).find('.mkt-cancel').should('be.visible')
    })
    step('"Cancel order" → reason "found it elsewhere" → confirm.', 'The order reads "Cancelled"; the Accept and Decline buttons are gone.', () => {
      rowOf(K.o6).find('.mkt-cancel').click()
      cy.get('#uiC-input').type('found it elsewhere')
      cy.get('.uiC-ok').click()
      rowOf(K.o6).should('contain', 'Cancelled').find('.mkt-sh-accept').should('not.exist')
    })
    cleanup('Nothing to undo: the order is cancelled and the other seller\'s hold released.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-2b-07', slice: 'MKT-2b', title: 'The seller disputes a recorded cause and MaxTheService overturns it; no money moves',
    persona: 'Shahzad Mobile Shop, then the MaxTheService operator', reqs: ['MKT-R11.4', 'MKT-R12.4'],
    pre: 'The switch is OFF. A new cash order from Shahzad Mobile Shop.', auto: ['MKT-2b-07'] }, (step, call, cleanup) => {
    cy.then(() => kOffers()).then(() => reroute(false))
    cy.then(() => kOrder('quiet', kph(7))).then((o) => { K.o7 = o.orderNo })
    step('As owner.business@myplus.com: Incoming → the order → reason "listing was wrong", cause "MaxTheService showed the wrong stock" → Reject.', 'The row reads "Rejected", "Cause: MaxTheService showed the wrong stock · Recorded".', () => {
      rejectOnScreen(K.o7, 'listing was wrong', 'PLATFORM_SYNC_DEFECT')
    })
    step('Set the list to "Rejected (cause and disputes)". On the order: "Dispute this cause" → write "The listing still showed 3 in stock" → "Send dispute".',
      '"Disputed: MaxTheService is reviewing it" and "You said: The listing still showed 3 in stock"; the Dispute button is gone.', () => {
        cy.intercept('GET', '**/mkt/incomingOrders*status=REJECTED*').as('rejectedList')
        cy.get('#mktIncomingStatus').select('REJECTED', { force: true })
        cy.wait('@rejectedList')                                   // the filtered list has replaced the old one
        cy.contains(`${UI.incoming} tr`, K.o7).find('.mkt-dispute').click()
        cy.contains(`${UI.incoming} tr`, K.o7).find('.mkt-dispute-note').type('The listing still showed 3 in stock')
        cy.contains(`${UI.incoming} tr`, K.o7).find('.mkt-dispute-send').click()
        cy.contains(`${UI.incoming} tr`, K.o7).find('.mkt-so-shortage').should('contain', 'Disputed: MaxTheService is reviewing it')
          .and('contain', 'You said: The listing still showed 3 in stock').find('.mkt-dispute').should('not.exist')
      })
    step('Operator: Platform → "Unfulfilled parts" (the "Disputed" tab is open).', `The order, ${A_NAME}, "MaxTheService showed the wrong stock", the seller's words, "Order cancelled, refunded", "Disputed by the seller", a reason box and "Uphold the cause" / "Overturn it".`, () => {
      console_('#platMktShortagesBtn')
      cy.contains('#mktShortageList .mkt-shortage-row', K.o7).should('contain', A_NAME).and('contain', 'MaxTheService showed the wrong stock')
        .and('contain', 'Seller: The listing still showed 3 in stock').and('contain', 'Disputed by the seller')
    })
    step('Press "Overturn it" with the reason empty.', 'Refused: "Write the reason for the seller."', () => {
      cy.contains('#mktShortageList .mkt-shortage-row', K.o7).find('.mkt-shortage-overturn').click()
      cy.contains('#mktShortageList .mkt-shortage-row', K.o7).should('contain', 'Write the reason for the seller.')
    })
    step('Reason "Our sync was late; not the seller\'s fault." → "Overturn it".', '"Overturned by MaxTheService" with the reason; the buttons are gone. No payment line changes anywhere.', () => {
      cy.contains('#mktShortageList .mkt-shortage-row', K.o7).find('.mkt-shortage-note').type('Our sync was late; not the seller\'s fault.')
      cy.contains('#mktShortageList .mkt-shortage-row', K.o7).find('.mkt-shortage-overturn').click()
      cy.contains('#mktShortageList .mkt-shortage-row', K.o7).should('contain', 'Overturned by MaxTheService').find('.mkt-shortage-uphold').should('not.exist')
    })
    step('As owner.business@myplus.com: Incoming → "Rejected (cause and disputes)".', '"Overturned by MaxTheService" and "MaxTheService: Our sync was late; not the seller\'s fault."', () => {
      partRow(SELLER_A, K.o7, true)
      cy.intercept('GET', '**/mkt/incomingOrders*status=REJECTED*').as('rejectedList')
      cy.get('#mktIncomingStatus').select('REJECTED', { force: true })
      cy.wait('@rejectedList')                                   // the filtered list has replaced the old one
      cy.contains(`${UI.incoming} tr`, K.o7).find('.mkt-so-shortage').should('contain', 'Overturned by MaxTheService')
        .and('contain', 'MaxTheService: Our sync was late')
    })
    cleanup('Nothing to undo: the decision is the record (it never moved money).', '—', () => {}, { screen: false })
  })

  // ──────────────────────────────── MKT-2c ────────────────────────────────
  // Needs marketplace-service started with MKT_ROUTING_TEST_SWITCH=true (the operator's test switch, never in production).

  const lph = (k) => `0317${String(run).slice(-6)}${k}`            // customer phones for the 2c walk
  const ROUTING = { view: '/platform/mkt/routing', close: '/platform/mkt/routingClose', test: '/platform/mkt/routingTest' }
  /** Behind the scenes: Shahzad Mobile Shop answers after `ms` (0 = normally) and is asked again (a case's starting state). */
  const slowShop = (ms) => {
    asOperator()
    return get(ROUTING.view).then((r) => {
      const org = data(r.body).sellers.find((x) => x.name === A_NAME).sellerOrganizationId
      post(ROUTING.test, { sellerOrganizationId: ms ? org : null, delayMs: ms })
      return post(ROUTING.close, { sellerOrganizationId: org })
    })
  }
  /** Platform → Marketplace policies → the routing box. */
  const routingBox = () => {
    console_('#platMktPoliciesBtn')
    return cy.get('#mktRoutingForm').scrollIntoView().should('be.visible').and('contain', 'Each seller has')
  }
  /** Customer: the phone's page, one seller's offer chosen → "Buy now" → the contact details. */
  const buyFrom = (offerId, ph) => {
    customer()
    cy.visit(page(`product=${H.product}&city=Karachi`))
    cy.get(`${UI.offerRow}[data-offer-id="${offerId}"] ${UI.chooseOffer}`).check()
    cy.get(UI.buyButton).click()
    contact(ph)
  }
  const SLOW = 'This seller did not answer in time. Please choose another offer.'

  walk({ id: 'M-2c-01', slice: 'MKT-2c', title: 'The operator sees how sellers are asked for stock, and (on a test system) makes one slow',
    persona: 'MaxTheService operator (admin@myplus.com)', reqs: ['MKT-R18.3'],
    pre: 'A test system: marketplace-service was started with the test switch (MKT_ROUTING_TEST_SWITCH=true). Every seller is answering.',
    auto: ['MKT-2c-04', 'MKT-2c-06'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => slowShop(0))
    step('Operator: Platform → "Marketplace policies" → the box "Asking sellers for stock at checkout".',
      '"Each seller has 800 ms to answer and a checkout 2000 ms in all. A seller that does not answer 3 times in a row is not asked for 30 seconds." Below it: "Every seller is being asked." Then the test-only fields: "Test only: make this seller slow" (a list of the sellers), "Delay in milliseconds (0 = off)" and Save.', () => {
        routingBox().should('contain', 'Each seller has 800 ms to answer and a checkout 2000 ms in all. A seller that does not answer 3 times in a row is not asked for 30 seconds.')
        cy.get('#mktRoutingOpen').should('contain', 'Every seller is being asked.')
        cy.get('#mktRoutingTest').should('be.visible')
        cy.get('#mktRoutingSeller option').should('contain', A_NAME).and('contain', B_NAME)
        cy.get('#mktRoutingDelay').should('have.value', '0')
        cy.wait(1000)                                               // the panel's other lists have drawn
        cy.get('#mktRoutingForm').scrollIntoView()                 // the whole box on the screen
      })
    step(`Choose "${A_NAME}", delay 3000 → Save.`, '"Test slowness is on." Reopening the panel shows the same seller and 3000.', () => {
      cy.get('#mktRoutingSeller').select(A_NAME)
      cy.get('#mktRoutingDelay').clear().type('3000')
      cy.get('#mktRoutingTestSave').click()
      cy.get('#mktRoutingMsg').should('contain', 'Test slowness is on.')
      routingBox()
      cy.get('#mktRoutingSeller option:selected').should('have.text', A_NAME)
      cy.get('#mktRoutingDelay').should('have.value', '3000')
    })
    cleanup('Delay 0 → Save.', '"Test slowness is off."', () => {
      cy.get('#mktRoutingDelay').clear().type('0')
      cy.get('#mktRoutingTestSave').click()
      cy.get('#mktRoutingMsg').should('contain', 'Test slowness is off.')
    })
  })

  walk({ id: 'M-2c-02', slice: 'MKT-2c', title: 'Checkout never hangs on a slow seller: the customer is told in time and buys from another',
    persona: 'Customer "Ali" (incognito window)', reqs: ['MKT-R18.1', 'MKT-R18.3', 'MKT-R18.5'],
    pre: `${A_NAME} (Rs 52,000) and ${B_NAME} (Rs 51,500) both sell the same phone in Karachi. Test slowness is on for ${A_NAME}: 3 seconds (M-2c-01).`,
    auto: ['MKT-2c-01', 'MKT-2c-02', 'MKT-2c-05'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => slowShop(3000))
    step(`Customer: open the phone (Karachi) → choose ${A_NAME} → "Buy now". Name "Ali", phone ${lph(2)}, address "1 Clifton" → "Place order".`,
      `Within about 2 seconds, under the button: "${SLOW}" No order number. The button works again.`, () => {
        buyFrom(H.a, lph(2))
        cy.get('#mktCoPlace').click()
        cy.get('#mktCoError', { timeout: 2500 }).should('have.text', SLOW)
        cy.get('#mktCoOrderNo').should('have.text', '')
        cy.get('#mktCoPlace').should('not.be.disabled')
      })
    step(`Choose ${B_NAME} instead → "Buy now" → the same details → "Place order".`,
      `"Waiting for ${B_NAME} to confirm" with an order number MKT-….`, () => {
        buyFrom(H.b, lph(2))
        cy.get('#mktCoPlace').click()
        cy.get(UI.checkoutStatus).should('contain', `Waiting for ${B_NAME} to confirm`)
        cy.get('#mktCoOrderNo').invoke('text').should('match', /^MKT-\d+/).then((no) => { H.r2 = no })
      })
    cleanup(`${B_NAME} rejects with the reason "walk cleanup". Operator: test delay 0 → Save.`, 'The order reads Cancelled; "Test slowness is off."', () => {
      cy.then(() => answer(SELLER_B, H.r2, 'reject'))
      routingBox()
      cy.get('#mktRoutingDelay').clear().type('0')
      cy.get('#mktRoutingTestSave').click()
      cy.get('#mktRoutingMsg').should('contain', 'Test slowness is off.')
    })
  })

  walk({ id: 'M-2c-03', slice: 'MKT-2c', title: 'A seller that keeps not answering is not asked for a while; the operator asks it again',
    persona: 'Customer "Ali" (incognito window), then the MaxTheService operator', reqs: ['MKT-R18.3', 'MKT-R18.5'],
    pre: `Test slowness is on for ${A_NAME}: 3 seconds. Every seller is being asked.`,
    auto: ['MKT-2c-04'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => slowShop(3000))
    step(`Customer: choose ${A_NAME} → "Buy now" → name, phone ${lph(3)}, address → "Place order". When the answer comes, press "Place order" again, three times in all.`,
      `Each time, in under a second: "${SLOW}"`, () => {
        buyFrom(H.a, lph(3))
        for (let i = 0; i < 3; i++) {
          cy.get('#mktCoError').invoke('text', '')
          cy.get('#mktCoPlace').should('not.be.disabled').click()
          cy.get('#mktCoError', { timeout: 2500 }).should('have.text', SLOW)
        }
      })
    step('Press "Place order" a fourth time.', `The same sentence at once: ${A_NAME} is not being asked any more, so there is nothing to wait for.`, () => {
      cy.get('#mktCoError').invoke('text', '')
      cy.get('#mktCoPlace').should('not.be.disabled').click()
      cy.get('#mktCoError', { timeout: 500 }).should('have.text', SLOW)
    })
    step('Operator: Platform → "Marketplace policies" → "Asking sellers for stock at checkout".',
      `"${A_NAME} is not answering: not asked until hh:mm:ss." with a button "Ask it again now".`, () => {
        routingBox()
        cy.contains('#mktRoutingOpen .mkt-routing-row', A_NAME).should('contain', 'is not answering: not asked until')
          .find('.mkt-routing-close').should('contain', 'Ask it again now')
      })
    step(`The shop says it is fixed: delay 0 → Save, then "Ask it again now" on ${A_NAME}'s line.`,
      '"Test slowness is off.", then "The seller will be asked again on its next order." and "Every seller is being asked."', () => {
        cy.get('#mktRoutingDelay').clear().type('0')
        cy.get('#mktRoutingTestSave').click()
        cy.get('#mktRoutingMsg').should('contain', 'Test slowness is off.')
        cy.contains('#mktRoutingOpen .mkt-routing-row', A_NAME).find('.mkt-routing-close').click()
        cy.get('#mktRoutingMsg').should('contain', 'The seller will be asked again on its next order.')
        cy.get('#mktRoutingOpen').should('contain', 'Every seller is being asked.')
      })
    step(`Customer: choose ${A_NAME} again → "Buy now" → the same details → "Place order".`, `"Waiting for ${A_NAME} to confirm" with an order number MKT-….`, () => {
      buyFrom(H.a, lph(3))
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', `Waiting for ${A_NAME} to confirm`)
      cy.get('#mktCoOrderNo').invoke('text').should('match', /^MKT-\d+/).then((no) => { H.r3 = no })
    })
    cleanup(`${A_NAME} rejects with the reason "walk cleanup".`, 'The order reads Cancelled.', () => {
      cy.then(() => answer(SELLER_A, H.r3, 'reject'))
    }, { screen: false })
  })

  walk({ id: 'M-2c-04', slice: 'MKT-2c', title: 'One slow seller in a basket: the basket is refused in time, that seller named',
    persona: 'Customer "Ali" (incognito window)', reqs: ['MKT-R18.1', 'MKT-R17.2', 'MKT-R18.5'],
    pre: `Operator: "Customers can buy from several sellers in one order" is ticked (M-2a-01). Test slowness is on for ${A_NAME}: 3 seconds.`,
    auto: ['MKT-2c-03'] }, (step, call, cleanup) => {
    cy.then(() => hOffers()).then(() => multiSeller(true)).then(() => slowShop(3000))
    step(`Customer: open the phone (Karachi). Choose ${A_NAME} → "Add to basket"; choose ${B_NAME} → "Add to basket". Open the basket; name, phone ${lph(4)}, address → "Place order".`,
      `Within about 2 seconds, under the button: "${A_NAME} did not answer in time. Please remove its items and place the order again." No order number.`, () => {
        fillBasket()
        contact(lph(4))
        cy.get('#mktCoPlace').click()
        cy.get('#mktCoError', { timeout: 2500 }).should('have.text', `${A_NAME} did not answer in time. Please remove its items and place the order again.`)
        cy.get('#mktCoOrderNo').should('have.text', '')
      })
    step(`"Remove" on ${A_NAME}'s phone → "Place order".`, `"Waiting for ${B_NAME} to confirm" with an order number MKT-…: only ${B_NAME}'s phone was ordered.`, () => {
      cy.contains('.mkt-basket-group', A_NAME).find('.mkt-remove').click()
      cy.get('.mkt-basket-group').should('have.length', 1)
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', `Waiting for ${B_NAME} to confirm`)
      cy.get('#mktCoOrderNo').invoke('text').should('match', /^MKT-\d+/).then((no) => { H.r4 = no })
    })
    cleanup(`${B_NAME} rejects with "walk cleanup". Operator: test delay 0 → Save; untick "Customers can buy from several sellers in one order" → Save.`,
      'The order reads Cancelled; "Test slowness is off."; the multi-seller switch is off again.', () => {
        cy.then(() => answer(SELLER_B, H.r4, 'reject'))
        routingBox()
        cy.get('#mktRoutingDelay').clear().type('0')
        cy.get('#mktRoutingTestSave').click()
        cy.get('#mktRoutingMsg').should('contain', 'Test slowness is off.')
        cy.then(() => slowShop(0))
        policies()
        cy.get('#mktMultiSeller').uncheck()
        cy.get('#mktMultiSellerSave').click()
        cy.get('#mktMultiSellerMsg').should('contain', 'Order settings saved.')
      })
  })

  // ──────────────────────────────── MKT-2d ────────────────────────────────
  // Cash orders: what a seller owes MaxTheService, since when, and the money it pays. Mobile Distributor plays the seller
  // (the gate uses Shahzad Mobile Shop, so the two never share a debt). M-2d-01 and -02 run on today's stack;
  // M-2d-03 runs after the whole stack is restarted twelve days later (FAKETIME=+12d), with --env later=1.

  const CD = {}
  const dph = (k) => `0318${String(run).slice(-6)}${k}`            // customer phones for the 2d walk
  const COD_STOPPED = 'This seller cannot take cash on delivery right now. Please pay online or choose another offer.'
  /** Mobile Distributor's offer, sold with 0 return days (so a line delivered today settles today under T+0). */
  const dOffer = () => (CD.offer ? cy.wrap(CD) : seedPolicies(`${run}cd`, { returnDays: 0 }).then((p) => cy.then(() => publishOffer(SELLER_B,
    { run: `${run}cd`, price: 52000, qty: 40, warrantyPolicyId: p.warranty, returnPolicyId: p.returns })))
    .then((o) => { CD.offer = o.offerId; CD.product = o.mktProductId; return CD }))
  const dOrg = () => (CD.org ? cy.wrap(CD.org) : (asOperator(), cy.orgOf(SELLER_B).then((o) => { CD.org = o.id; return o.id })))
  const dAccount = () => { asOperator(); return dOrg().then((org) => get(`/platform/mkt/settlementAccount?organizationId=${org}`)).then((r) => data(r.body)) }
  /** Behind the scenes: Mobile Distributor starts square (pays what it owes, or a correction takes a positive balance to zero). */
  const dSquare = () => dAccount().then((a) => {
    const bal = Number(a.balance)
    if (bal < 0) return post('/platform/mkt/recordRemittance', { organizationId: CD.org, amount: -bal, reference: 'WALK-SQUARE', idempotencyKey: `wsq-${run}-${Math.random()}` })
    if (bal > 0) return post('/platform/mkt/adjustLedger', { organizationId: CD.org, amount: -bal, reason: 'walk: start from zero', idempotencyKey: `wsq-${run}-${Math.random()}` })
    return null
  })
  /** Behind the scenes: Ali's cash order from Mobile Distributor, accepted and delivered through the seller's real steps. */
  const dDelivered = (ph) => {
    const o = {}
    dOffer()
    fSignIn(ph)
    cy.then(() => post(API.checkout, { offerId: CD.offer, quantity: 1, expectedPrice: 52000, customerName: 'Ali Raza', customerPhone: ph,
      address: '1 Clifton', city: 'Karachi', idempotencyKey: `wd-${run}-${Math.random()}`, paymentMode: 'COD' }).then((r) => {
      expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
      Object.assign(o, { no: data(r.body).orderNo, so: data(r.body).sellerOrderId, v: data(r.body).sellerOrderVersion })
    }))
    as(SELLER_B)
    cy.then(() => post(API.acceptOrder, { id: o.so, version: o.v }))
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`).then((s) => { o.store = list(s.body).find((x) => x.orderNo === o.no).storeOrderId }))
    cy.then(() => gDeliver(o))
    return cy.wrap(o)
  }
  /** Operator → Platform dashboard → "Settlement and payouts", scrolled to "Cash orders: what sellers owe". */
  const codBox = () => {
    dOrg()
    asOperator()
    cy.visit('/platformDashboard')
    cy.get('#platMktPayoutsBtn').should('be.visible').click()
    cy.get('#mktCodDays').should(($i) => expect($i.val()).to.not.eq(''))
    cy.wait(800)                                                  // the panel's other lists have drawn
    cy.get('#mktCodBox').scrollIntoView()
  }
  const dRow = () => cy.then(() => cy.get(`#mktCodList tr.mkt-cod-row[data-org="${CD.org}"]`, { timeout: 15000 }))
  const amountOf = ($el) => Number(($el.text() || '0').replace(/[^0-9.]/g, ''))
  const rs = (n) => Number(n).toLocaleString('en-US', { maximumFractionDigits: 2 })
  const rs2 = (n) => Number(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  /** Seller B: Sale → Marketplace → "Show statement". */
  const dStatement = () => {
    as(SELLER_B); openMarketplace()
    cy.get('#mktStatementTab').scrollIntoView().click()
    cy.get('#mktStatementBody').should('be.visible')
  }
  /** Customer: Mobile Distributor's phone (Karachi) → "Buy now" → the contact details. */
  const dBuy = (ph) => {
    customer()
    cy.visit(page(`product=${CD.product}&city=Karachi`))
    cy.get(`${UI.offerRow}[data-offer-id="${CD.offer}"] ${UI.chooseOffer}`).check()
    cy.get(UI.buyButton).click()
    contact(ph)
  }

  walk({ id: 'M-2d-01', slice: 'MKT-2d', title: 'A cash order delivered: the operator and the seller see what is owed, since when and by when',
    persona: 'MaxTheService operator (admin@myplus.com), then owner.mobile@myplus.com (Mobile Distributor)', reqs: ['MKT-R20.3'],
    pre: 'Ali\'s Rs 52,000 cash-on-delivery order from Mobile Distributor was delivered today, on a business day; the offer was sold with 0 return days and settlement runs T+0 (M-1g-01). Mobile Distributor owed nothing before it. Days to pay: 7.',
    auto: ['MKT-2d-01', 'MKT-2d-02'] }, (step, call, cleanup) => {
    cy.then(() => dSquare()).then(() => dDelivered(dph(1))).then((o) => { CD.o1 = o.no })
    asOperator()
    cy.then(() => post('/platform/mkt/settlementSettings', { codRemitDays: 7, codStopWhenOverdue: false }))
    step('Operator: Platform dashboard → "Settlement and payouts" → "Settle what is due now". Scroll to "Cash orders: what sellers owe".',
      '"Settled 1 line(s). …" Under "Cash orders: what sellers owe", Mobile Distributor\'s row: "Cash collected" includes the 52,000 Ali paid the rider; "Owes now" is the commission on that order, in red; "Pay by" is 7 days from today, with "Owed since <today>." under it. "Days a seller has to pay" reads 7.', (snap) => {
        codBox()
        cy.get('#mktRunSettlement').click()
        cy.get('#mktSetMsg').should('contain', 'Settled')
        cy.get('#mktCodDays').should('have.value', '7')
        cy.get('#mktCodBox').scrollIntoView()
        dRow().find('.mkt-cod-owed').should(($td) => expect(amountOf($td)).to.be.greaterThan(0))
        dRow().find('.mkt-cod-collected').should(($td) => expect(amountOf($td)).to.be.at.least(52000))
        dRow().find('.mkt-cod-payby').should('contain', 'Owed since')
        cy.get('#mktCodBox').scrollIntoView()
        snap()                                                    // the proof on screen, before the behind-the-scenes check
        cy.then(() => dAccount()).then((a) => {
          CD.owed = -Number(a.balance)
          CD.since = a.cod.owedSince
          CD.payBy = a.cod.payBy
          expect(CD.owed, 'the commission of the one cash order').to.be.greaterThan(0)
          expect(a.entries[0].effectiveAt.substring(0, 10), 'owed since today').to.eq(CD.since)
          const d = new Date(`${CD.since}T00:00:00Z`)
          d.setUTCDate(d.getUTCDate() + 7)
          expect(CD.payBy, 'pay by = owed since + 7 days').to.eq(d.toISOString().substring(0, 10))
        })
      })
    step('Log in as owner.mobile@myplus.com. Sale → Marketplace → "Show statement".',
      'Under "You owe MaxTheService Rs <the commission> in commission.", in yellow: "Please pay MaxTheService Rs <the commission> for your cash orders by <the pay-by date>."', () => {
        dStatement()
        cy.get('#mktCodStanding').should('be.visible').and('have.class', 'alert-warning')
          .and('have.text', `Please pay MaxTheService Rs ${rs(CD.owed)} for your cash orders by ${CD.payBy}.`)
        cy.get('#mktCodStanding').scrollIntoView({ offset: { top: -120, left: 0 } })
      })
    step('Scroll down to "Ledger".',
      'Ali\'s order is there three times: SALE (owed to you, 52,000), COMMISSION (owed by you) and COLLECTED_BY_SELLER, "Cash collected by your rider on delivery" (owed by you, 52,000).', () => {
        cy.get('#mktLedgerTable tbody tr.mkt-entry[data-entry-type="SALE"]').filter(`:contains("${CD.o1}")`).should('have.length', 1).and('contain', '52,000')
        cy.get('#mktLedgerTable tbody tr.mkt-entry[data-entry-type="COMMISSION"]').filter(`:contains("${CD.o1}")`).should('have.length', 1)
        cy.get('#mktLedgerTable tbody tr.mkt-entry[data-entry-type="COLLECTED_BY_SELLER"]').filter(`:contains("${CD.o1}")`).should('have.length', 1)
          .and('contain', 'Cash collected by your rider on delivery').and('contain', '52,000')
        cy.get('#mktLedgerTable tbody tr.mkt-entry').first().scrollIntoView({ offset: { top: -200, left: 0 } })
      })
    cleanup('None: the debt is paid in M-2d-02 and M-2d-03.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-2d-02', slice: 'MKT-2d', title: 'The seller pays part of what it owes: the operator records it, with the reason it is short',
    persona: 'MaxTheService operator (admin@myplus.com), then owner.mobile@myplus.com', reqs: ['MKT-R20.3', 'MKT-R15.6'],
    pre: 'Mobile Distributor owes MaxTheService the commission on Ali\'s cash order (M-2d-01). It sent half of it by bank transfer.',
    auto: ['MKT-2d-03', 'MKT-2d-04'] }, (step, call, cleanup) => {
    cy.then(() => dAccount()).then((a) => {
      CD.owed = -Number(a.balance)
      CD.since = a.cod.owedSince
      CD.payBy = a.cod.payBy
      CD.part = Math.floor(CD.owed / 2)
      expect(CD.owed, 'precondition: Mobile Distributor owes (M-2d-01)').to.be.greaterThan(0)
    })
    step('Operator: "Settlement and payouts" → "Cash orders: what sellers owe" → Mobile Distributor\'s row. Type half of what it owes in the amount box, "HBL-2210" as the reference, leave the reason empty → "Record payment".',
      'Under the button, in red: "The seller owes Rs <owed> and paid Rs <half>. Say why it paid less: the note is shown on the seller\'s statement." Nothing is recorded: "Owes now" is unchanged.', () => {
        codBox()
        dRow().within(() => {
          cy.get('.mkt-cod-amount').clear().type(String(CD.part))
          cy.get('.mkt-cod-ref').type('HBL-2210')
          cy.get('.mkt-cod-record').click()
          cy.get('.mkt-cod-msg').should('have.text', `The seller owes Rs ${rs2(CD.owed)} and paid Rs ${rs2(CD.part)}. Say why it paid less: the note is shown on the seller's statement.`)
          cy.get('.mkt-cod-owed').should(($td) => expect(amountOf($td)).to.eq(CD.owed))
        })
      })
    step('Type "Rider still holds one order\'s cash" in the reason box → "Record payment".',
      '"Payment recorded for Mobile Distributor." "Paid to MaxTheService" goes up by the half; "Owes now" is the other half; "Owed since" is the same date as before: the debt is smaller, not newer.', () => {
        dRow().within(() => {
          cy.get('.mkt-cod-note').type('Rider still holds one order\'s cash')
          cy.get('.mkt-cod-record').click()
        })
        cy.get('#mktCodMsg').should('have.text', 'Payment recorded for Mobile Distributor.')
        dRow().find('.mkt-cod-owed').should(($td) => expect(amountOf($td)).to.eq(Math.round((CD.owed - CD.part) * 100) / 100))
        dRow().find('.mkt-cod-payby').should('contain', `Owed since ${CD.since}.`)
      })
    step('As owner.mobile@myplus.com: Sale → Marketplace → "Show statement".',
      'The yellow line now asks for the rest, by the same date: "Please pay MaxTheService Rs <the other half> for your cash orders by <the same date>."', () => {
        dStatement()
        cy.get('#mktCodStanding').should('have.text', `Please pay MaxTheService Rs ${rs(Math.round((CD.owed - CD.part) * 100) / 100)} for your cash orders by ${CD.payBy}.`)
        cy.get('#mktCodStanding').scrollIntoView({ offset: { top: -120, left: 0 } })
      })
    step('Scroll down to "Ledger".',
      'The newest line is REMITTANCE, RM-…, owed to you: the half, with "Paid to MaxTheService for cash orders, ref HBL-2210. Rider still holds one order\'s cash". Nothing above it was changed.', () => {
        cy.get('#mktLedgerTable tbody tr.mkt-entry').first().should('have.attr', 'data-entry-type', 'REMITTANCE')
          .and('contain', 'RM-').and('contain', 'Paid to MaxTheService for cash orders, ref HBL-2210. Rider still holds one order\'s cash')
          .and('contain', rs(CD.part))
          .scrollIntoView({ offset: { top: -200, left: 0 } })
      })
    cleanup('None: Mobile Distributor still owes the other half; it is collected in M-2d-03, twelve days later.', '—', () => {}, { screen: false })
  })

  const laterWalk = Cypress.env('later') ? walk : (meta) => it.skip(`${meta.id} ${meta.title} (run with later=1, twelve days later)`)
  laterWalk({ id: 'M-2d-03', slice: 'MKT-2d', title: 'Twelve days later the seller has not paid: it is late, the operator stops its cash orders, then records the payment',
    persona: 'MaxTheService operator, a customer (incognito window), owner.mobile@myplus.com', reqs: ['MKT-R20.3'],
    pre: 'Twelve days after M-2d-02 (the whole system restarted on a clock 12 days later). Mobile Distributor still owes the other half; it had 7 days to pay.',
    auto: ['MKT-2d-08', 'MKT-2d-09', 'MKT-2d-10'] }, (step, call, cleanup) => {
    cy.then(() => dOffer()).then(() => dAccount()).then((a) => {
      CD.owed = -Number(a.balance)
      expect(CD.owed, 'precondition: Mobile Distributor still owes (M-2d-02)').to.be.greaterThan(0)
      expect(a.cod.overdue, 'precondition: twelve days have passed').to.eq(true)
      CD.payBy = a.cod.payBy
    })
    asOperator()
    cy.then(() => post('/platform/mkt/settlementSettings', { codStopWhenOverdue: false }))
    step('Operator: "Settlement and payouts" → "Cash orders: what sellers owe".',
      'Mobile Distributor\'s row is red and among the first: "Overdue. Owed since <date>." under its pay-by date. Its cash orders still work: the stop box is not ticked.', () => {
        codBox()
        dRow().should('have.class', 'danger').find('.mkt-cod-payby').should('contain', 'Overdue. Owed since')
        cy.get('#mktCodStop').should('not.be.checked')
      })
    step('Tick "Stop cash on delivery for a seller that has not paid in time" → Save.',
      '"Settlement settings saved." Mobile Distributor\'s row adds "Cash on delivery is stopped for this seller."', () => {
        cy.get('#mktCodStop').check()
        cy.get('#mktCodSave').click()
        cy.get('#mktCodMsg').should('contain', 'Settlement settings saved.')
        dRow().find('.mkt-cod-stopped').should('have.text', 'Cash on delivery is stopped for this seller.')
      })
    step(`Customer: open Mobile Distributor's phone (Karachi) → "Buy now". Name "Ali", phone ${dph(3)}, address "1 Clifton", cash on delivery → "Place order".`,
      `Under the button: "${COD_STOPPED}" No order number.`, () => {
        dBuy(dph(3))
        cy.get('#mktCoPlace').click()
        cy.get('#mktCoError').should('have.text', COD_STOPPED)
        cy.get('#mktCoOrderNo').should('have.text', '')
      })
    step('As owner.mobile@myplus.com: Sale → Marketplace → "Show statement".',
      'In red: "Overdue: please pay MaxTheService Rs <what is left> for your cash orders. It was due by <date>." and below it "Customers cannot choose cash on delivery from you until you pay."', () => {
        dStatement()
        cy.get('#mktCodStanding').should('have.class', 'alert-danger')
          .and('contain', `Overdue: please pay MaxTheService Rs ${rs(CD.owed)} for your cash orders. It was due by ${CD.payBy}.`)
          .and('contain', 'Customers cannot choose cash on delivery from you until you pay.')
        cy.get('#mktCodStanding').scrollIntoView({ offset: { top: -120, left: 0 } })
      })
    step('Mobile Distributor pays the rest. Operator: its row → the amount box already holds what is left; reference "HBL-2299" → "Record payment".',
      '"Payment recorded for Mobile Distributor." Its row is no longer red; "Owes now" reads "—".', () => {
        codBox()
        dRow().within(() => {
          cy.get('.mkt-cod-amount').should(($i) => expect(Number($i.val())).to.eq(CD.owed))
          cy.get('.mkt-cod-ref').type('HBL-2299')
          cy.get('.mkt-cod-record').click()
        })
        cy.get('#mktCodMsg').should('have.text', 'Payment recorded for Mobile Distributor.')
        dRow().should('not.have.class', 'danger').find('.mkt-cod-owed').should('have.text', '—')
      })
    step('Customer: the same order again → "Place order".', '"Waiting for Mobile Distributor to confirm" with an order number MKT-…: cash on delivery works again.', () => {
      dBuy(dph(3))
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', 'Waiting for Mobile Distributor to confirm')
      cy.get('#mktCoOrderNo').invoke('text').should('match', /^MKT-\d+/).then((no) => { CD.o3 = no })
    })
    cleanup('Mobile Distributor rejects the order with "walk cleanup". Operator: untick "Stop cash on delivery for a seller that has not paid in time" → Save.',
      'The order reads Cancelled; "Settlement settings saved." and the box is unticked.', () => {
        cy.then(() => answer(SELLER_B, CD.o3, 'reject'))
        codBox()
        cy.get('#mktCodStop').uncheck()
        cy.get('#mktCodSave').click()
        cy.get('#mktCodMsg').should('contain', 'Settlement settings saved.')
        cy.get('#mktCodStop').should('not.be.checked')
      })
  })
  // ──────────────────────────────── MKT-2e ────────────────────────────────
  // Seller performance: the operator's scorecard, the seller's own, and "accepts more of its orders" breaking a tie in
  // the catalogue. Read-only screens: every figure is checked against the service, never against a number written here,
  // because the two shops carry the history of every earlier gate and walk.

  const PF = {}
  const pct = (part, whole) => `${Math.round((part / whole) * 100)}% (${part} of ${whole})`
  /** Behind the scenes: the operator's 30-day figures for a shop (opening them also refreshes the ranking). */
  const pScore = (name, days = 30) => {
    asOperator()
    return get(`/platform/mkt/sellerPerformance?days=${days}`).then((r) => data(r.body).sellers.find((x) => x.sellerName === name))
  }
  /** Behind the scenes: both shops sell one phone at Rs 52,000, 24 hours, the same policies; the shop that accepts more publishes second. */
  const pOffers = () => (PF.product ? cy.wrap(PF) : cy.then(() => {
    let p, first, second
    seedPolicies(`${run}pf`).then((x) => { p = x })
    pScore(A_NAME).then((a) => { PF.rateA = a && a.acceptanceRate !== null ? a.acceptanceRate : 1 })
    pScore(B_NAME).then((b) => { PF.rateB = b && b.acceptanceRate !== null ? b.acceptanceRate : 1 })
    cy.then(() => {
      first = PF.rateA > PF.rateB ? SELLER_B : SELLER_A
      second = first === SELLER_A ? SELLER_B : SELLER_A
      return publishOffer(first, { run: `${run}pf1`, price: 52000, promiseHours: 24, qty: 40, warrantyPolicyId: p.warranty, returnPolicyId: p.returns })
    }).then((o) => { PF.product = o.mktProductId; PF[first] = o.offerId })
    cy.then(() => publishOffer(second, { run: `${run}pf2`, price: 52000, promiseHours: 24, qty: 40, mktProductId: PF.product,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns })).then((o) => { PF[second] = o.offerId })
    return cy.wrap(PF)
  }))
  /** Behind the scenes: a customer's cash order for Mobile Distributor's offer. Yields the order number. */
  const pOrder = () => pOffers().then(() => {
    customer()
    cy.visit(UI.publicPage)
    return post(API.checkout, { lines: [{ offerId: PF[SELLER_B], quantity: 1, expectedPrice: 52000 }], customerName: 'Ali',
      customerPhone: `0319${String(run).slice(-6)}1`, address: '1 Clifton', city: 'Karachi', idempotencyKey: `wpf-${run}-${Math.random()}` })
      .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body).orderNo })
  })
  /** Operator: Platform dashboard → "Seller performance". */
  const perfPanel = () => {
    asOperator()
    cy.intercept('GET', '**/platform/mkt/sellerPerformance*').as('perf')
    cy.visit(UI.operatorPage)
    cy.get('#platMktPerformanceBtn').should('be.visible').click()
    cy.wait('@perf')
    return cy.get('#platMktPerformance').should('be.visible')
  }
  const perfRow = (name) => cy.contains('#mktPerfList .mkt-perf-row', name)

  walk({ id: 'M-2e-01', slice: 'MKT-2e', title: 'The operator reads how each seller handles its orders, over 7, 30 or 90 days',
    persona: 'MaxTheService operator (admin@myplus.com)', reqs: ['MKT-R20.3'],
    pre: 'Both shops have had marketplace orders in the last 30 days (earlier cases).', auto: ['MKT-2e-01', 'MKT-2e-06'] }, (step, call, cleanup) => {
    cy.then(() => pScore(A_NAME)).then((a) => { PF.a30 = a })
    step('Operator: Platform dashboard → "Seller performance".',
      '"Seller performance", with "30 days" selected. One row per shop that had an order in the last 30 days, shops that need attention first. Shahzad Mobile Shop\'s row: "Orders accepted" as "<percent>% (<accepted> of <decided>)", "Time to accept" in minutes, "Delivered on time", "Not fulfilled by the seller", "Returns the seller caused", and in red under "Needs attention" each reason, or "Nothing".', () => {
        perfPanel()
        cy.get('#platMktPerfDays button.is-on').should('have.attr', 'data-days', '30')
        perfRow(A_NAME).within(() => {
          const d = PF.a30.accepted + PF.a30.missed
          cy.get('.mkt-perf-accept').should('have.text', PF.a30.acceptanceRate === null ? 'Not enough orders yet' : pct(PF.a30.accepted, d))
          cy.get('.mkt-perf-missed').should('contain', String(PF.a30.missed))
          cy.get('.mkt-perf-returns').should('have.text', String(PF.a30.sellerFaultReturns))
          if (PF.a30.flags.length) cy.get('.mkt-perf-flag').should('have.length', PF.a30.flags.length)
          else cy.get('.mkt-perf-flags').should('have.text', 'Nothing')
        })
        perfRow(B_NAME).should('be.visible')
        cy.get('#mktPerfList .mkt-perf-row').then(($rows) => {
          const f = [...$rows].map((r) => (r.getAttribute('data-flags') || '') !== '')
          expect(f.indexOf(false) === -1 || f.slice(f.indexOf(false)).every((x) => !x), 'shops that need attention first').to.eq(true)
        })
      })
    step('Press "7 days".', '"7 days" is selected and the figures are those of the orders placed in the last 7 days: never more orders than in 30 days.', () => {
      cy.intercept('GET', '**/platform/mkt/sellerPerformance?days=7').as('perf7')
      cy.get('#platMktPerfDays button[data-days="7"]').click()
      cy.wait('@perf7').then((x) => {
        const a7 = x.response.body.data.sellers.find((r) => r.sellerName === A_NAME)
        expect(a7 ? a7.accepted + a7.missed : 0, 'a shorter window holds no more orders').to.be.at.most(PF.a30.accepted + PF.a30.missed)
      })
      cy.get('#platMktPerfDays button.is-on').should('have.attr', 'data-days', '7')
    })
    step('Press "90 days".', '"90 days" is selected; Shahzad Mobile Shop has at least as many orders as in 30 days.', () => {
      cy.intercept('GET', '**/platform/mkt/sellerPerformance?days=90').as('perf90')
      cy.get('#platMktPerfDays button[data-days="90"]').click()
      cy.wait('@perf90').then((x) => {
        const a90 = x.response.body.data.sellers.find((r) => r.sellerName === A_NAME)
        expect(a90.accepted + a90.missed, 'a longer window holds no fewer orders').to.be.at.least(PF.a30.accepted + PF.a30.missed)
      })
      cy.get('#platMktPerfDays button.is-on').should('have.attr', 'data-days', '90')
    })
    cleanup('None: reading changes nothing.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-2e-02', slice: 'MKT-2e', title: 'A missed order counts against the seller until it disputes it; overturned, it is not the seller\'s fault',
    persona: 'owner.mobile@myplus.com (Mobile Distributor), then the MaxTheService operator', reqs: ['MKT-R20.3', 'MKT-R11.4', 'MKT-R12.4'],
    pre: 'A customer has just ordered Mobile Distributor\'s phone, cash on delivery. Mobile Distributor has no stock on the shelf.',
    auto: ['MKT-2e-02', 'MKT-2e-03', 'MKT-2e-04'] }, (step, call, cleanup) => {
    cy.then(() => pOrder()).then((no) => { PF.o2 = no })
    cy.then(() => pScore(B_NAME)).then((b) => { PF.b0 = b })
    step('As owner.mobile@myplus.com: Sale → Marketplace → Incoming orders → the new order → reason "none on the shelf", cause "Out of stock in my shop" → Reject.',
      'The row reads "Rejected", "Cause: … · Recorded".', () => {
        partRow(SELLER_B, PF.o2).find('input[placeholder*="cannot fulfil"]').type('none on the shelf')
        cy.contains(`${UI.incoming} tr`, PF.o2).find('.mkt-reject-cause').select('MERCHANT_STALE_STOCK')
        cy.contains(`${UI.incoming} tr`, PF.o2).find(UI.rejectBtn).click()
        cy.contains(`${UI.incoming} tr`, PF.o2).should('contain', 'Rejected')
        cy.wait(800)                                                  // the page's other lists have drawn
        cy.contains(`${UI.incoming} tr`, PF.o2).scrollIntoView({ offset: { top: -200, left: 0 } })
      })
    step('Operator: Platform dashboard → "Seller performance".', 'Mobile Distributor\'s "Not fulfilled by the seller" is one more than before.', (snap) => {
      perfPanel()
      perfRow(B_NAME).find('.mkt-perf-missed').should('contain', String(PF.b0.missed + 1))
      snap()
      cy.then(() => pScore(B_NAME)).then((b) => expect(b.missed - PF.b0.missed, 'missed +1').to.eq(1))
    })
    step('As owner.mobile@myplus.com: Incoming orders → "Rejected (cause and disputes)" → the order → "Dispute this cause" → "The stock count said 3" → "Send dispute".',
      '"Disputed: MaxTheService is reviewing it".', () => {
        partRow(SELLER_B, PF.o2, true)
        cy.intercept('GET', '**/mkt/incomingOrders*status=REJECTED*').as('rejectedList')
        cy.get('#mktIncomingStatus').select('REJECTED', { force: true })
        cy.wait('@rejectedList')
        cy.contains(`${UI.incoming} tr`, PF.o2).find('.mkt-dispute').click()
        cy.contains(`${UI.incoming} tr`, PF.o2).find('.mkt-dispute-note').type('The stock count said 3')
        cy.contains(`${UI.incoming} tr`, PF.o2).find('.mkt-dispute-send').click()
        cy.contains(`${UI.incoming} tr`, PF.o2).find('.mkt-so-shortage').should('contain', 'Disputed: MaxTheService is reviewing it')
        cy.contains(`${UI.incoming} tr`, PF.o2).scrollIntoView({ offset: { top: -200, left: 0 } })
      })
    step('Operator: "Seller performance" again.', 'Mobile Distributor\'s "Not fulfilled by the seller" is back to what it was, with "1 under dispute" (or one more than before) under it: a disputed record is not used until MaxTheService decides.', (snap) => {
      perfPanel()
      perfRow(B_NAME).find('.mkt-perf-missed').should('contain', String(PF.b0.missed)).and('contain', `${PF.b0.disputed + 1} under dispute`)
      snap()
    })
    step('Operator: "Unfulfilled parts" → the order → reason "Our count was late; not the seller\'s fault." → "Overturn it".', '"Overturned by MaxTheService".', () => {
      console_('#platMktShortagesBtn')
      cy.contains('#mktShortageList .mkt-shortage-row', PF.o2).find('.mkt-shortage-note').type('Our count was late; not the seller\'s fault.')
      cy.contains('#mktShortageList .mkt-shortage-row', PF.o2).find('.mkt-shortage-overturn').click()
      cy.contains('#mktShortageList .mkt-shortage-row', PF.o2).should('contain', 'Overturned by MaxTheService')
    })
    step('Operator: "Seller performance" again.', 'Mobile Distributor: "Not fulfilled by the seller" as before the order, nothing under dispute from it, and one more "not the seller\'s fault".', (snap) => {
      perfPanel()
      perfRow(B_NAME).find('.mkt-perf-missed').should('contain', String(PF.b0.missed)).and('contain', `${PF.b0.excused + 1} not the seller's fault`)
      snap()
      cy.then(() => pScore(B_NAME)).then((b) => {
        expect(b.missed - PF.b0.missed).to.eq(0)
        expect(b.disputed - PF.b0.disputed).to.eq(0)
        expect(b.excused - PF.b0.excused).to.eq(1)
      })
    })
    cleanup('None: the order ended when it was rejected, and the decision is the record (it moved no money).', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-2e-03', slice: 'MKT-2e', title: 'The seller reads its own scorecard; of two equal offers the customer sees the seller that accepts more first',
    persona: 'owner.mobile@myplus.com (Mobile Distributor), then a customer (incognito window)', reqs: ['MKT-R20.3'],
    pre: 'Both shops sell the same phone at Rs 52,000, delivery in 24 hours, the same warranty and return policy, both in Karachi.',
    auto: ['MKT-2e-07', 'MKT-2e-08'] }, (step, call, cleanup) => {
    cy.then(() => pOffers())
    cy.then(() => pScore(B_NAME)).then((b) => { PF.b = b })
    step('As owner.mobile@myplus.com: Sale → Marketplace. Scroll to "Your performance".',
      'Four figures for the last 30 days: "Orders accepted", "Time to accept", "Delivered on time", "Returns you caused", the same MaxTheService sees; under them, for each reason, "MaxTheService has noted: …".', () => {
        as(SELLER_B)
        cy.intercept('GET', '**/mkt/myPerformance*').as('mine')
        openMarketplace()
        cy.wait('@mine')
        cy.get('#mktPerfBox').scrollIntoView().should('be.visible')
        cy.get('#mktPerfAccept').should('have.text', PF.b.acceptanceRate === null ? 'Not enough orders yet' : pct(PF.b.accepted, PF.b.accepted + PF.b.missed))
        cy.get('#mktPerfOnTime').should('have.text', PF.b.onTimeRate === null ? 'Not enough orders yet' : pct(PF.b.onTime, PF.b.due))
        cy.get('#mktPerfReturns').should('have.text', String(PF.b.sellerFaultReturns))
        cy.get('#mktPerfFlags .mkt-perf-flag').should('have.length', PF.b.flags.length)
        cy.get('#mktOffersTable tbody tr').should('have.length.at.least', 1)
        cy.wait(800)                                                  // the page's other lists have drawn above it
        cy.get('#mktPerfBox').scrollIntoView({ offset: { top: -120, left: 0 } })
        cy.window().then((w) => cy.get('#mktPerfBox').should(($b) => expect($b[0].getBoundingClientRect().top, 'on screen').to.be.within(0, w.innerHeight - 100)))
      })
    step('Customer: open the phone both shops sell (Karachi).',
      'The shop that accepts more of its orders is listed first, although it published its offer later: price, delivery time and policies are the same.', (snap) => {
        const better = PF.rateA > PF.rateB ? SELLER_A : SELLER_B
        const other = better === SELLER_A ? SELLER_B : SELLER_A
        expect(PF[better], 'positive control: the better shop published second').to.be.greaterThan(PF[other])
        customer()
        cy.visit(page(`product=${PF.product}&city=Karachi`))
        cy.get(UI.offerRow).should('have.length.at.least', 2).then(($r) => {
          const ids = [...$r].map((x) => Number(x.getAttribute('data-offer-id'))).filter((id) => id === PF[better] || id === PF[other])
          expect(ids, `${better === SELLER_A ? A_NAME : B_NAME} first`).to.deep.eq([PF[better], PF[other]])
        })
        snap()
      })
    cleanup('None: both offers stay on sale, as after M-1d.', '—', () => {}, { screen: false })
  })
  // ──────────────────────────────── MKT-2f ────────────────────────────────
  // Settlement reports and bank holidays. Every report figure is checked against the service (the shops carry every
  // earlier run's money); the holiday case builds its own line with 3 return days, so its payable day is in the future.

  const RP = {}
  const iso = (d) => d.toISOString().substring(0, 10)
  const plusDays = (s, n) => { const d = new Date(`${s}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return iso(d) }
  const isWeekend = (s) => [0, 6].includes(new Date(`${s}T00:00:00Z`).getUTCDay())
  const nextBusinessDay = (s) => { let d = plusDays(s, 1); while (isWeekend(d)) d = plusDays(d, 1); return d }
  const rRs = (v) => (Number(v || 0) ? Number(v).toLocaleString('en-US', { maximumFractionDigits: 2 }) : '—')
  /** Behind the scenes: the operator's report for a period ('' = this month). */
  const rReport = (q = '') => {
    asOperator()
    return get(`/platform/mkt/settlementReport${q}`).then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); return data(r.body) })
  }
  /** Operator: Platform dashboard → "Settlement and payouts" → scroll to `box`. */
  const payoutsPanel = (box) => {
    asOperator()
    cy.intercept('GET', '**/platform/mkt/settlementReport*').as('rep')
    cy.intercept('GET', '**/platform/mkt/holidays*').as('hol')
    cy.visit(UI.operatorPage)
    cy.get('#platMktPayoutsBtn').should('be.visible').click()
    cy.wait(['@rep', '@hol'])
    cy.get('#mktAccountList tbody tr').should('have.length.at.least', 1)
    cy.wait(600)                                                   // the panel's other lists have drawn above it
    return cy.get(box).scrollIntoView({ offset: { top: -80, left: 0 } }).should('be.visible')
  }
  /** The seller's statement line for an order. */
  const rLine = (no) => {
    as(SELLER_A)
    return get(`${API.statement}?size=100`).then((r) => list(r.body).find((x) => x.orderNo === no))
  }

  walk({ id: 'M-2f-01', slice: 'MKT-2f', title: 'The operator reads the settlement report for a period and downloads it',
    persona: 'MaxTheService operator (admin@myplus.com)', reqs: ['MKT-R20.3', 'MKT-R15.6'],
    pre: 'Both shops have settled marketplace sales (earlier cases).', auto: ['MKT-2f-01', 'MKT-2f-02', 'MKT-2f-04', 'MKT-2f-06'] }, (step, call, cleanup) => {
    cy.then(() => rReport()).then((v) => { RP.month = v })
    step('Operator: Platform dashboard → "Settlement and payouts". Scroll to "Settlement report".',
      'From the 1st of this month to today. One row per shop: Closing (what the shop is owed at the end), then Opening, Sales, Commission, Fees and tax, Reserve, Refunds, Corrections, Cash kept by seller, Paid in by seller, Paid out, Lines; what the shop lost is in red; "All sellers" adds them up. For every shop, Opening plus the columns is its Closing.', (snap) => {
        payoutsPanel('#mktReportBox')
        cy.get('#mktRepFrom').should('have.value', RP.month.from)
        cy.get('#mktRepTo').should('have.value', RP.month.to)
        expect(RP.month.from.endsWith('-01'), 'from the 1st').to.eq(true)
        cy.get('#mktReportList .mkt-rep-row').should('have.length', RP.month.rows.length)
        RP.month.rows.forEach((r) => {
          const cols = ['opening', 'sales', 'commission', 'feesAndTax', 'reserve', 'refunds', 'corrections', 'collectedBySeller', 'remitted', 'paidOut']
          expect(cols.reduce((t, c) => t + Math.round(Number(r[c]) * 100), 0), `${r.sellerName} adds up`).to.eq(Math.round(Number(r.closing) * 100))
          cy.get(`#mktReportList .mkt-rep-row[data-org="${r.organizationId}"]`).within(() => {
            cy.get('.mkt-rep-sales').should('have.text', rRs(r.sales))
            cy.get('.mkt-rep-commission').should('have.text', rRs(r.commission))
            cy.get('.mkt-rep-closing').should('have.text', rRs(r.closing))
          })
        })
        cy.get('#mktReportList .mkt-rep-total .mkt-rep-closing').should('have.text', rRs(RP.month.totals.closing))
        cy.get('#mktReportList .mkt-rep-total .mkt-rep-closing').should(($c) => {
          const box = $c.closest('.table-responsive')[0].getBoundingClientRect()
          expect($c[0].getBoundingClientRect().right, 'Closing is on screen, not past the table\'s scroll edge').to.be.at.most(box.right)
        })
        cy.get('#mktReportBox').scrollIntoView({ offset: { top: -40, left: 0 } })
        snap()
      })
    step('Set "From" to today and "To" to yesterday. Press "Show".',
      '"The start of the period is after its end." The table is empty and "Download CSV" cannot be pressed.', () => {
        cy.get('#mktRepFrom').clear().type(RP.month.to)
        cy.get('#mktRepTo').clear().type(plusDays(RP.month.to, -1))
        cy.get('#mktRepShow').click()
        cy.get('#mktRepMsg').should('have.text', 'The start of the period is after its end.')
        cy.get('#mktReportList .mkt-rep-row').should('have.length', 0)
        cy.get('#mktRepCsv').should('be.disabled')
      })
    step('Set "From" to 10 days ago and "To" to today. Press "Show".',
      'The shops with money in those days. Each shop\'s "Opening" is what it was owed at the start of the 10 days: the closing of the days before.', () => {
        RP.from = plusDays(RP.month.to, -9)
        cy.then(() => rReport(`?from=${plusDays(RP.month.to, -30)}&to=${plusDays(RP.month.to, -10)}`)).then((v) => { RP.before = v })
        cy.then(() => rReport(`?from=${RP.from}&to=${RP.month.to}`)).then((v) => { RP.ten = v })
        payoutsPanel('#mktReportBox')
        cy.intercept('GET', `**/platform/mkt/settlementReport?from=${RP.from}*`).as('ten')
        cy.get('#mktRepFrom').clear().type(RP.from)
        cy.get('#mktRepTo').clear().type(RP.month.to)
        cy.get('#mktRepShow').click()
        cy.wait('@ten')
        cy.then(() => {
          RP.ten.rows.forEach((r) => {
            const b = RP.before.rows.find((x) => x.organizationId === r.organizationId)
            expect(Number(r.opening), `${r.sellerName}: opening = the closing of the days before`).to.eq(b ? Number(b.closing) : 0)
            cy.get(`#mktReportList .mkt-rep-row[data-org="${r.organizationId}"] .mkt-rep-opening`).should('have.text', rRs(r.opening))
          })
        })
        cy.get('#mktReportBox').scrollIntoView({ offset: { top: -40, left: 0 } })
      })
    step('Press "Download CSV".',
      'A file "settlement-report-<from>-to-<to>.csv": a header line with the same columns, one line per shop, and "All sellers". It opens in Excel.', (snap) => {
        cy.get('#mktRepCsv').should('not.be.disabled').click()
        snap()
        cy.then(() => cy.readFile(`cypress/downloads/settlement-report-${RP.from}-to-${RP.month.to}.csv`, 'utf8')).then((t) => {
          const lines = t.replace(/^﻿/, '').trim().split(/\r\n/)
          expect(lines[0]).to.eq('Seller ID,Seller,Closing,Opening,Sales,Commission,Fees and tax,Reserve,Refunds,Corrections,Cash kept by seller,Paid in by seller,Paid out,Lines')
          expect(lines.length).to.eq(RP.ten.rows.length + 2)
          expect(lines[lines.length - 1]).to.contain('All sellers')
          RP.csv = lines.slice(0, 3).join('\n')
        })
      })
    cleanup('None: reading changes nothing.', '—', () => {}, { screen: false })
  })

  walk({ id: 'M-2f-02', slice: 'MKT-2f', title: 'A bank holiday added ahead of time moves a line due that day to the next business day',
    persona: 'MaxTheService operator (admin@myplus.com), then owner.business@myplus.com (Shahzad Mobile Shop)', reqs: ['MKT-R15.1', 'MKT-R22.1'],
    pre: 'Shahzad Mobile Shop has just delivered a marketplace phone sold with a 3-day return policy, so it becomes payable a few days from now.',
    auto: ['MKT-2f-07', 'MKT-2f-08'] }, (step, call, cleanup) => {
    // behind the scenes: the sale and its delivery, the way the shop does it (as in M-1g)
    let offer
    cy.then(() => rReport()).then((v) => { RP.today = v.to })
    seedPolicies(`${run}rp`, { returnDays: 3 }).then((p) => publishOffer(SELLER_A, { run: `${run}rp`, price: 52000, qty: 5,
      warrantyPolicyId: p.warranty, returnPolicyId: p.returns })).then((o) => { offer = o })
    cy.then(() => {
      customer()
      cy.visit(UI.publicPage)
      post(API.checkout, { lines: [{ offerId: offer.offerId, quantity: 1, expectedPrice: 52000 }], customerName: 'Ali',
        customerPhone: `0318${String(run).slice(-6)}2`, address: '1 Clifton', city: 'Karachi', idempotencyKey: `wrp-${run}` })
        .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); RP.no = data(r.body).orderNo })
    })
    as(SELLER_A)
    cy.then(() => get(`${API.incomingOrders}?status=OFFERED&size=100`)).then((r) => {
      const so = list(r.body).find((x) => x.orderNo === RP.no)
      return post(API.acceptOrder, { id: so.id, version: so.version })
    }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.then(() => get(`${API.incomingOrders}?status=ACCEPTED&size=100`)).then((r) => {
      const store = list(r.body).find((x) => x.orderNo === RP.no).storeOrderId
      post('/updateOrderStatus', { id: store, status: 'PACKED' })
      get(`/getOrder?id=${store}`).then((g) => {
        const l = data(g.body).items[0]
        post('/shipOrder', { id: store, lines: [{ orderItemId: l.id, quantity: l.quantity }], carrier: 'Own rider', trackingNumber: `WRP-${run}` })
      })
      post('/updateOrderStatus', { id: store, status: 'DELIVERED' }).then((u) => expect(ok(u.body), JSON.stringify(u.body)).to.eq(true))
    })
    cy.then(() => rLine(RP.no)).then((l) => { RP.day = l.eligibleOn; RP.next = nextBusinessDay(l.eligibleOn) })
    step('As owner.business@myplus.com: Sale → Marketplace → "Statement" → "Show".',
      'The delivered order\'s line, "Payable on" a weekday a few days from now (after its 3 return days).', () => {
        as(SELLER_A)
        openMarketplace()
        cy.get('#mktStatementTab').click()
        cy.contains(`${UI.statementTable} tr.mkt-line`, RP.no).scrollIntoView({ offset: { top: -200, left: 0 } })
          .find('.mkt-eligible-on').should('have.text', RP.day)
        cy.then(() => expect(isWeekend(RP.day), 'a weekday').to.eq(false))
      })
    step('Operator: Platform dashboard → "Settlement and payouts" → "Bank holidays". Day: today, Name "Test". Press "Add holiday".',
      '"A holiday can be added only for a day after today: lines already payable keep their day." Nothing is added.', () => {
        payoutsPanel('#mktHolidayBox')
        cy.get('#mktHolDate').type(RP.today)
        cy.get('#mktHolName').type('Test')
        cy.get('#mktHolAdd').click()
        cy.get('#mktHolMsg').should('have.text', 'A holiday can be added only for a day after today: lines already payable keep their day.')
        cy.get('#mktHolidayList tbody').should('not.contain', 'Test')
      })
    step('Day: the line\'s payable day. Name "Walk bank holiday". Press "Add holiday".',
      '"Holiday added. Lines due that day are paid on the next business day." The day is listed with its name and a "Remove" button.', () => {
        cy.get('#mktHolDate').clear().type(RP.day)
        cy.get('#mktHolName').clear().type(`Walk bank holiday ${M}`)
        cy.get('#mktHolAdd').click()
        cy.get('#mktHolMsg').should('have.text', 'Holiday added. Lines due that day are paid on the next business day.')
        cy.get(`#mktHolidayList .mkt-holiday[data-date="${RP.day}"]`).should('contain', `Walk bank holiday ${M}`)
          .find('.mkt-holiday-remove').should('be.visible')
      })
    step('As owner.business@myplus.com: the statement again.',
      'The same line is now "Payable on" the next business day after the holiday (a Monday if the holiday was a Friday).', () => {
        as(SELLER_A)
        openMarketplace()
        cy.get('#mktStatementTab').click()
        cy.contains(`${UI.statementTable} tr.mkt-line`, RP.no).scrollIntoView({ offset: { top: -200, left: 0 } })
          .find('.mkt-eligible-on').should('have.text', RP.next)
      })
    cleanup('Operator: "Bank holidays" → the walk\'s holiday → "Remove". Then the shop\'s statement.',
      '"Holiday removed." It is no longer listed, and the line is "Payable on" its own day again.', () => {
        payoutsPanel('#mktHolidayBox')
        cy.get(`#mktHolidayList .mkt-holiday[data-date="${RP.day}"] .mkt-holiday-remove`).click()
        cy.get('#mktHolMsg').should('have.text', 'Holiday removed.')
        cy.get(`#mktHolidayList .mkt-holiday[data-date="${RP.day}"]`).should('not.exist')
        cy.then(() => rLine(RP.no)).then((l) => expect(l.eligibleOn, 'back to its own day').to.eq(RP.day))
      })
  })

  walk({ id: 'M-2f-03', slice: 'MKT-2f', title: 'A seller reads its own period summary: the figures MaxTheService reconciles with',
    persona: 'owner.business@myplus.com (Shahzad Mobile Shop)', reqs: ['MKT-R20.3', 'MKT-R22.1'],
    pre: 'The shop has settled marketplace sales (earlier cases).', auto: ['MKT-2f-05'] }, (step, call, cleanup) => {
    cy.then(() => rReport()).then((v) => { RP.mine = v.rows.find((x) => x.sellerName === A_NAME) })
    step('As owner.business@myplus.com: Sale → Marketplace → "Statement" → "Show". Scroll to "Period summary".',
      'This month to today: "Owed to you at the start", Sales, Commission, "Delivery, fees and tax", "Reserve held and released", Refunds, Corrections, "Cash your riders kept", "You paid MaxTheService", "Paid out to you", "Owed to you at the end", and "<n> sale line(s) settled in this period.": the same figures as the shop\'s row in MaxTheService\'s report. When the shop owes money at the end, the last line reads "You owe MaxTheService at the end" with the amount in red.', () => {
        as(SELLER_A)
        cy.intercept('GET', '**/mkt/settlementReport*').as('mine')
        openMarketplace()
        cy.get('#mktStatementTab').click()
        cy.wait('@mine')
        cy.get('#mktMyReport tbody tr').should('have.length', 11)
        const abs = (v) => Math.abs(Number(v)).toLocaleString('en-US', { maximumFractionDigits: 2 })
        cy.get('#mktMyReport .mkt-myrep-opening td').eq(1).should('have.text', abs(RP.mine.opening))
        cy.get('#mktMyReport .mkt-myrep-sales td').eq(1).should('have.text', rRs(RP.mine.sales))
        cy.get('#mktMyReport .mkt-myrep-commission td').eq(1).should('have.text', rRs(RP.mine.commission))
        cy.get('#mktMyReport .mkt-myrep-closing td').eq(0).should('have.text', Number(RP.mine.closing) < 0 ? 'You owe MaxTheService at the end' : 'Owed to you at the end')
        cy.get('#mktMyReport .mkt-myrep-closing td').eq(1).should('have.text', abs(RP.mine.closing))
        cy.get('#mktMyRepMsg').should('have.text', `${RP.mine.lines} sale line(s) settled in this period.`)
        cy.wait(600)
        cy.get('#mktMyReport').scrollIntoView({ offset: { top: -160, left: 0 } })
      })
    step('In the browser\'s address bar open /platform/mkt/settlementReport (MaxTheService\'s report of every shop).',
      'Refused: the shop sees only its own row.', () => {
        call('GET /platform/mkt/settlementReport as the shop', get('/platform/mkt/settlementReport')).then((r) => {
          expect(r.status).to.eq(403)
          expect(ok(r.body)).to.eq(false)
        })
      })
    cleanup('None: reading changes nothing.', '—', () => {}, { screen: false })
  })

  // ──────────────────────────────── MKT-2-06 ────────────────────────────────
  // The time a seller has to accept by the order's value. The case lists its own offer (stock 20), so it runs alone.

  const AV = {}
  /** Operator: Platform dashboard → "Marketplace policies" → the "Time to accept by order value" box. */
  const tiersBox = () => {
    asOperator()
    cy.intercept('GET', '**/platform/mkt/acceptTiers*').as('tiers')
    cy.visit(UI.operatorPage)
    cy.get('#platMktPoliciesBtn').should('be.visible').click()
    cy.wait('@tiers')
    return cy.get('#mktAcceptTiersForm').scrollIntoView({ offset: { top: -120, left: 0 } }).should('be.visible')
  }
  const avOrder = (qty, ph, total) => {
    customer()
    cy.visit(page(`product=${AV.product}&city=Karachi`))
    buy(AV.offer, { ph, qty })
    cy.get('#mktCoTotal').should('have.text', total)
    cy.get('#mktCoPlace').click()
    cy.get(UI.checkoutStatus).should('contain', `Waiting for ${A_NAME} to confirm`)
    return cy.get('#mktCoOrderNo').invoke('text').should('match', /^MKT-\d+/)
  }

  walk({ id: 'M-2-06', slice: 'MKT-2', title: 'Larger orders give the seller longer to accept',
    persona: 'admin@myplus.com (operator), a customer, then owner.business@myplus.com', reqs: ['MKT-R10.6', 'MKT-R10.5', 'MKT-R22.1'],
    pre: 'Shahzad Mobile Shop has a Live phone offer at Rs 52,000 in Karachi. "Minutes a seller has to accept an order" is 5. No value rules.',
    auto: ['MKT-2-06-02', 'MKT-2-06-03', 'MKT-2-06-04', 'MKT-2-06-06'] }, (step, call, cleanup) => {
    cy.then(() => {
      asOperator()
      post(API.acceptWindow, { minutes: 5 })
      post('/platform/mkt/acceptTiers', { tiers: [] })
      seedPolicies(`${run}v`).then((p) => cy.then(() => publishOffer(SELLER_A, { run: `${run}v`, price: 52000, qty: 20,
        warrantyPolicyId: p.warranty, returnPolicyId: p.returns }))).then((o) => { AV.offer = o.offerId; AV.product = o.mktProductId })
    })
    step('As admin@myplus.com: Platform → "Marketplace policies". Find "Time to accept by order value".',
      'The box explains the rule ("A seller\'s part worth more than an amount gets that rule\'s minutes …"), and the table reads "No rules: every order gets the minutes above."', () => {
        tiersBox()
        cy.get('#mktTiersList tbody').should('contain', 'No rules: every order gets the minutes above.')
        cy.get('#mktAcceptWindow').should('have.value', '5')
      })
    step('Press "Add a rule". Orders above, Rs: 100000. Minutes to accept: 75. Press "Save rules".',
      'Refused in words: "Each rule\'s minutes are 1 to 60." Nothing is saved.', () => {
        cy.get('#mktTiersAdd').click()
        cy.get('#mktTiersList .mkt-tier-above').type('100000')
        cy.get('#mktTiersList .mkt-tier-minutes').type('75')
        cy.get('#mktTiersSave').click()
        cy.get('#mktTiersMsg').should('contain', 'Each rule\'s minutes are 1 to 60.')
        cy.get('#mktAcceptTiersForm').scrollIntoView({ offset: { top: -120, left: 0 } })
      })
    step('Change the minutes to 15 and press "Save rules".',
      '"Acceptance rules saved. They apply to orders placed from now on." The rule stays listed: above 100000, 15 minutes.', () => {
        cy.get('#mktTiersList .mkt-tier-minutes').clear().type('15')
        cy.get('#mktTiersSave').click()
        cy.get('#mktTiersMsg').should('contain', 'Acceptance rules saved. They apply to orders placed from now on.')
        cy.get('#mktTiersList .mkt-tier-above').should('have.value', '100000')
        cy.get('#mktTiersList .mkt-tier-minutes').should('have.value', '15')
        cy.get('#mktAcceptTiersForm').scrollIntoView({ offset: { top: -120, left: 0 } })
      })
    step(`Customer (incognito): open the product (Karachi), choose ${A_NAME}, "Buy", Quantity 3, name "Ali", phone ${phone(61)}, address "1 Clifton". Press "Place order".`,
      `Total Rs. 156,000. "Waiting for ${A_NAME} to confirm" and "${A_NAME} has 14:5x to confirm": the order is above Rs 100,000, so 15 minutes, not 5.`, () => {
        avOrder(3, phone(61), 'Rs. 156,000').then((no) => { AV.big = no })
        cy.get('#mktOrderDetail').invoke('text').should('match', /has (15:00|14:[0-5]\d) to confirm/)
      })
    step(`Customer: the same, Quantity 1, phone ${phone(62)}.`,
      `Total Rs. 52,000. "${A_NAME} has 4:5x to confirm": under Rs 100,000, the 5 minutes as before.`, () => {
        avOrder(1, phone(62), 'Rs. 52,000').then((no) => { AV.small = no })
        cy.get('#mktOrderDetail').invoke('text').should('match', /has [0-5]:[0-5]\d to confirm/)
      })
    step('As owner.business@myplus.com: Sale → Marketplace → "Incoming marketplace orders".',
      'Both orders wait for the shop: the Rs 156,000 order counts down from about 15:00, the Rs 52,000 order from about 5:00.', () => {
        as(SELLER_A)
        openMarketplace()
        cy.contains(`${UI.incoming} tr`, AV.big).find(UI.countdown).invoke('text').should('match', /^(15:00|14:[0-5]\d)$/)
        cy.contains(`${UI.incoming} tr`, AV.small).find(UI.countdown).invoke('text').should('match', /^[0-5]:[0-5]\d$/)
        cy.contains(`${UI.incoming} tr`, AV.big).scrollIntoView({ offset: { top: -160, left: 0 } })
      })
    step('In the browser\'s address bar open /platform/mkt/acceptTiers (the shop tries to read MaxTheService\'s rules).',
      'Refused: the rules are MaxTheService\'s.', () => {
        call('GET /platform/mkt/acceptTiers as the shop', get('/platform/mkt/acceptTiers')).then((r) => {
          expect(r.status).to.eq(403)
          expect(ok(r.body)).to.eq(false)
        })
      })
    cleanup('As owner.business@: Incoming → Reject both orders with the reason "walk cleanup".', 'Both rows read "Rejected"; their units are released.', () => {
      as(SELLER_A)
      openMarketplace()
      ;[AV.big, AV.small].forEach((no) => {
        cy.contains(`${UI.incoming} tr`, no).find('input[placeholder*="cannot fulfil"]').type('walk cleanup')
        cy.contains(`${UI.incoming} tr`, no).find(UI.rejectBtn).click()
        cy.contains(`${UI.incoming} tr`, no).should('contain', 'Rejected')
      })
      cy.contains(`${UI.incoming} tr`, AV.small).scrollIntoView({ offset: { top: -160, left: 0 } })
    })
    cleanup('As admin@: Marketplace policies → "Time to accept by order value" → "Remove" on the rule → "Save rules".',
      '"Acceptance rules saved." and "No rules: every order gets the minutes above."', () => {
        tiersBox()
        cy.get('#mktTiersList .mkt-tier-remove').click()
        cy.get('#mktTiersSave').click()
        cy.get('#mktTiersMsg').should('contain', 'Acceptance rules saved.')
        cy.get('#mktTiersList tbody').should('contain', 'No rules: every order gets the minutes above.')
        cy.get('#mktAcceptTiersForm').scrollIntoView({ offset: { top: -120, left: 0 } })
      })
  })
})
