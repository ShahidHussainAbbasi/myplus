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
    cy.then(() => fn())
    cy.then(() => {
      const s = cur
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

  walk({ id: 'M-1e-02', slice: 'MKT-1e', title: 'One checkout is one seller',
    persona: 'Customer (incognito window), developer tools', reqs: ['MKT-R20.1', 'MKT-R17.1', 'MKT-R20.2'], pre: 'As M-1d-01.', auto: ['MKT-1e-09'] }, (step, call, cleanup) => {
    step(`On the product page choose ${A_NAME}, then ${B_NAME}.`, `Only one can be chosen (radio buttons): choosing ${B_NAME} unchooses ${A_NAME}; the button reads "Buy from ${B_NAME}".`, () => {
      customer()
      cy.visit(page(`product=${D.product}&city=Karachi`))
      cy.get(`${UI.offerRow}[data-offer-id="${D.a}"] ${UI.chooseOffer}`).check()
      cy.get(`${UI.offerRow}[data-offer-id="${D.b}"] ${UI.chooseOffer}`).check()
      cy.get(`${UI.offerRow}[data-offer-id="${D.a}"] ${UI.chooseOffer}`).should('not.be.checked')
      cy.get(UI.buyButton).should('have.text', `Buy from ${B_NAME}`)
    })
    step(`Developer tools: POST /marketplace/public/checkout for ${A_NAME}'s offer, adding "lines": [{offerId: <${B_NAME}'s offer>}].`,
      `The extra "lines" are ignored: the order has exactly one seller order, for ${A_NAME}. Mixing sellers in one checkout waits for Phase 2.`, () => {
        call('POST /marketplace/public/checkout (+ lines)', post(API.checkout, { offerId: D.a, quantity: 1, expectedPrice: 52000,
          customerName: 'Ali', customerPhone: phone(2), address: '1 Clifton', city: 'Karachi', idempotencyKey: `w-${run}-2`,
          lines: [{ offerId: D.b, quantity: 1 }] })).then((r) => {
          expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
          D.o2 = data(r.body)
        })
        cy.then(() => call('GET the order', get(API.trackOrder(D.o2.orderNo, phone(2))))).then((r) => {
          const ls = data(r.body).lines
          expect(ls).to.have.length(1)
          expect(ls[0].offerId === undefined || ls[0].offerId === D.a).to.eq(true)
          expect(data(r.body).sellerName).to.eq(A_NAME)
        })
      })
    cleanup(`As owner.business@: Incoming → Reject that order with the reason "walk cleanup".`, 'The stock is released; the customer\'s page reads Cancelled.', () => {
      as(SELLER_A)
      call('POST /mkt/rejectOrder', post(API.rejectOrder, { id: D.o2.sellerOrderId, version: D.o2.sellerOrderVersion, reason: 'walk cleanup' }))
        .then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    })
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
    step(`Customer (phone ${phone(5)}): order ${A_NAME}'s phone. The seller does nothing.`, `"${A_NAME} has 0:5x to confirm. Your stock is held."`, () => {
      customer()
      cy.visit(page(`product=${D.product}&city=Karachi`))
      buy(D.a, { ph: phone(5) })
      cy.get('#mktCoPlace').click()
      cy.get('#mktOrderDetail').invoke('text').should('match', /has 0:[0-5]\d to confirm/)
      cy.get('#mktCoOrderNo').invoke('text').then((no) => { D.o5 = no })
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
    step(`Fill the checkout for ${A_NAME} (phone ${phone(6)}) and DOUBLE-click "Place order".`, 'One order number. The seller has exactly ONE order for that phone.', () => {
      customer()
      cy.visit(page(`product=${D.product}&city=Karachi`))
      buy(D.a, { ph: phone(6) })
      cy.get('#mktCoPlace').dblclick()
      cy.get('#mktCoOrderNo').invoke('text').should('match', /^MKT-/)
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
    step('Press "Place order" again.', 'The order the server had already placed is shown — the SAME one: the seller has exactly ONE order for that phone.', () => {
      cy.get('#mktCoPlace').click()
      cy.get(UI.checkoutStatus).should('contain', 'Waiting for')
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
})
