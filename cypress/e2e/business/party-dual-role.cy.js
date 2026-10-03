/**
 * DR — one business partner, two roles (customer AND supplier).
 *
 * Design: microservices/docs/party-dual-role-customer-supplier-analysis.md
 * Manual cases: the "Dual-Role Partner Tests" page (same ids as below: W1, DR1-1 …).
 *
 * ── How this spec is organised ──────────────────────────────────────────────────────────────────────
 * "Works today" runs NOW and pins the behaviour the design builds on.
 * Each DR-n block is the GATE for that slice. Its cases are written against the designed API and are `it.skip`
 * until the slice ships; enabling them is part of the slice. An endpoint named here that does not exist yet is
 * the design's name for it — change both together if the design changes.
 *
 * ── Tenants ─────────────────────────────────────────────────────────────────────────────────────────
 * Identity cases run on owner.business@ (no money). Position and set-off cases move AR/AP, so they run on
 * owner.lifecycle@ — the Test Book's sacrificial tenant — and build their balances with OPENING BALANCES, which
 * create an open receivable and an open payable without a sale or a purchase (cutover 2026-09-01, as in
 * opening-balances.cy.js).
 *
 * Run headed:
 *   npx cypress run --headed --browser electron --spec cypress/e2e/business/party-dual-role.cy.js
 */
const LIFECYCLE = 'owner.lifecycle@myplus.com'
const PW = 'Demo@2025!'

const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return Array.isArray(b) ? b : [] }
const payload = (b) => (b && (b.object || b.data)) || null
const parse = (b) => { try { return typeof b === 'string' ? JSON.parse(b) : b } catch (e) { return {} } }
const uniq = () => String(Date.now()).slice(-9)
/** A valid mobile (03 + 9 digits) that is unique per call. */
const mobile = (seed) => '03' + String(seed).padStart(9, '0').slice(-9)

/** Register a customer; resolves to its row (re-read, because the party stamp lands after the response). */
const addCustomer = (body) =>
  cy.request({ method: 'POST', url: '/addCustomer', form: true, body, failOnStatusCode: false })
    .then((r) => expect(r.body.status, `addCustomer: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))
    .then(() => cy.request('/getUserCustomer?q=-1'))
    .then((r) => {
      const c = list(r.body).find((x) => x.name === body.name)
      expect(c, `customer ${body.name} readable back`).to.be.an('object')
      return c
    })

/** A supplier needs at least one company; one is made per call. Resolves to the supplier row. */
const addSupplier = (body) => {
  const coName = 'DRCo_' + uniq()
  return cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: coName, email: `${coName}@t.com` } })
    .then(() => cy.request('/getUserCompany'))
    .then((co) => {
      const companyId = list(co.body).find((x) => x.name === coName).id
      return cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false, body: { companyId, ...body } })
    })
    .then((v) => expect(v.body.status, `addVender: ${JSON.stringify(v.body)}`).to.eq('SUCCESS'))
    .then(() => cy.request('/getUserVender'))
    .then((r) => {
      const v = list(r.body).find((x) => x.name === body.name)
      expect(v, `supplier ${body.name} readable back`).to.be.an('object')
      return v
    })
}

const partyRoles = (pid) => cy.request({ url: `/partyRoles?id=${pid}`, failOnStatusCode: false }).then((r) => parse(r.body))

// ── Works today ─────────────────────────────────────────────────────────────────────────────────────
describe('DR — works today: one partner, both roles, separate accounts', () => {
  beforeEach(() => cy.loginAsOwner())

  it('W1 — a customer and a supplier with the same mobile are ONE partner holding BOTH roles', () => {
    const s = uniq()
    const phone = mobile(s)
    addCustomer({ name: 'DR Cust ' + s, contact: phone }).then((c) => {
      expect(c.partyId, 'customer linked to a partner').to.be.a('number')
      addSupplier({ name: 'DR Supp ' + s, mobile: phone }).then((v) => {
        expect(v.partyId, 'supplier linked to the SAME partner').to.eq(c.partyId)
        partyRoles(c.partyId).then((d) => {
          const roles = (d.roles || []).filter((x) => String(x.module).toLowerCase() === 'business').map((x) => x.role)
          expect(roles, 'the partner holds both business roles').to.include.members(['CUSTOMER', 'VENDOR'])
        })
      })
    })
  })

  it('W2 — the two roles keep their own records: separate ids, separate statements', () => {
    const s = uniq()
    const phone = mobile(s)
    addCustomer({ name: 'DR Cust2 ' + s, contact: phone }).then((c) => {
      addSupplier({ name: 'DR Supp2 ' + s, mobile: phone }).then((v) => {
        expect(c.customerId || c.id, 'customer id').to.not.eq(undefined)
        expect(v.id, 'supplier id').to.not.eq(undefined)
        cy.request(`/customerStatement?customerId=${c.customerId || c.id}`).its('status').should('eq', 200)
        cy.request(`/vendorStatement?venderId=${v.id}`).its('status').should('eq', 200)
      })
    })
  })
})

// ── DR-1: matching ──────────────────────────────────────────────────────────────────────────────────
describe('DR-1 — matching finds the same partner and never merges two', () => {
  beforeEach(() => cy.loginAsOwner())

  it('DR1-1 — the same number in different formats is ONE partner (0300-…, +92300…)', () => {
    const s = uniq()
    const digits = String(s).padStart(9, '0').slice(-9)           // 9 digits after "03"
    addCustomer({ name: 'DR Fmt C ' + s, contact: `03${digits.slice(0, 2)}-${digits.slice(2)}` }).then((c) => {
      addSupplier({ name: 'DR Fmt S ' + s, mobile: `+923${digits}` }).then((v) => {
        expect(c.partyId, 'customer linked').to.be.a('number')
        expect(v.partyId, '+92 format matched the 0300- format').to.eq(c.partyId)
      })
    })
  })

  it('DR1-2 — a shared email does NOT merge two partners whose phones differ', () => {
    const s = uniq()
    const email = `office${s}@t.com`
    addCustomer({ name: 'DR Mail C ' + s, contact: mobile(s), email }).then((c) => {
      addSupplier({ name: 'DR Mail S ' + s, mobile: mobile(Number(s) + 1), email }).then((v) => {
        expect(v.partyId, 'supplier linked').to.be.a('number')
        expect(v.partyId, 'different phones = different partners, whatever the email').to.not.eq(c.partyId)
      })
    })
  })

  it('DR1-3 — a supplier with only a phone (no mobile) is matched on it', () => {
    const s = uniq()
    const phone = mobile(s)
    addCustomer({ name: 'DR Ph C ' + s, contact: phone }).then((c) => {
      addSupplier({ name: 'DR Ph S ' + s, mobile: '', phone }).then((v) => expect(v.partyId).to.eq(c.partyId))
    })
  })

  it('DR1-4 — the same CNIC/NTN matches even when the phones differ', () => {
    const s = uniq()
    const cnic = '35201' + String(s).padStart(8, '0').slice(-8)
    addCustomer({ name: 'DR Tax C ' + s, contact: mobile(s), cnic }).then((c) => {
      addSupplier({ name: 'DR Tax S ' + s, mobile: mobile(Number(s) + 7), cnicNtn: cnic }).then((v) => {
        expect(v.cnicNtn, 'the supplier keeps its CNIC / NTN').to.eq(cnic)
        expect(v.partyId, 'tax id outranks phone').to.eq(c.partyId)
      })
    })
  })

  it('DR1-5 — changing a customer\'s phone re-links it, and the old partner stops claiming it', () => {
    const s = uniq()
    const supplierPhone = mobile(s)
    addSupplier({ name: 'DR Re S ' + s, mobile: supplierPhone }).then((v) => {
      addCustomer({ name: 'DR Re C ' + s, contact: mobile(Number(s) + 3) }).then((c) => {
        const oldParty = c.partyId
        expect(oldParty, 'different phone = different partner first').to.not.eq(v.partyId)
        cy.request({ method: 'POST', url: '/addCustomer', form: true,
          body: { customerId: c.customerId || c.id, name: c.name, contact: supplierPhone, customerType: c.customerType || 'WALK_IN' } })
          .its('body.status').should('eq', 'SUCCESS')
        cy.request('/getUserCustomer?q=-1').then((r) => {
          const after = list(r.body).find((x) => (x.customerId || x.id) === (c.customerId || c.id))
          expect(after.partyId, 're-linked after the phone edit').to.eq(v.partyId)
        })
        partyRoles(oldParty).then((d) => {
          const stale = (d.roles || []).filter((x) => x.role === 'CUSTOMER' && Number(x.localId) === Number(c.customerId || c.id))
          expect(stale, 'the old partner no longer lists this customer').to.have.length(0)
        })
      })
    })
  })

  it('DR1-5b — an edit that changes nothing it is matched on keeps the same partner', () => {
    const s = uniq()
    addCustomer({ name: 'DR Keep C ' + s, contact: mobile(s) }).then((c) => {
      cy.request({ method: 'POST', url: '/addCustomer', form: true,
        body: { customerId: c.customerId || c.id, name: c.name + ' (renamed)', contact: c.contact, customerType: c.customerType || 'WALK_IN' } })
        .its('body.status').should('eq', 'SUCCESS')
      cy.request('/getUserCustomer?q=-1').then((r) => {
        const after = list(r.body).find((x) => (x.customerId || x.id) === (c.customerId || c.id))
        expect(after.partyId, 'a rename keeps the partner').to.eq(c.partyId)
      })
    })
  })

  it('DR1-6 — owner opens "Possible duplicates" from Customers and sees a split pair; a cashier is refused', () => {
    // The one way left to create two partners on one number: same phone, DIFFERENT CNIC / NTN. DR-1 keeps them
    // apart (a tax id is a legal identity) and the owner's list shows them side by side.
    const s = uniq()
    const phone = mobile(s)
    addCustomer({ name: 'DR Dup C ' + s, contact: phone, cnic: '35201' + String(s).padStart(8, '0').slice(-8) }).then((c) => {
      addSupplier({ name: 'DR Dup S ' + s, mobile: phone, cnicNtn: '42101' + String(Number(s) + 1).padStart(8, '0').slice(-8) }).then((v) => {
        expect(v.partyId, 'different CNIC kept them apart').to.not.eq(c.partyId)

        cy.visit('/businessDashboard'); cy.waitForAppReady()
        cy.openSection('CustomerDiv')
        cy.get('#partyDuplicatesBtn').should('be.visible').click()
        cy.get('.c360-card').should('be.visible').and('contain', 'Same phone number')
        cy.get(`.c360-card [data-pd-party="${c.partyId}"]`).scrollIntoView().should('be.visible').and('contain', 'DR Dup C ' + s)
        cy.get(`.c360-card [data-pd-party="${v.partyId}"]`).scrollIntoView().should('be.visible')
        cy.get('.c360-card .c360-x').click()
        cy.get('.c360-card').should('not.exist')
      })
    })
    cy.loginAsCashierA()
    cy.request({ url: '/partyDuplicates', failOnStatusCode: false }).then((r) => {
      const body = parse(r.body)
      expect(r.status === 403 || !Array.isArray(body), 'cashier refused ' + r.status).to.eq(true)
    })
  })

  it('DR1-7 — the supplier form offers CNIC / NTN, saves it, and loads it back on Edit', () => {
    const s = uniq()
    const ntn = '7' + String(s).padStart(6, '0').slice(-6)
    addSupplier({ name: 'DR Form S ' + s, mobile: mobile(s), cnicNtn: ntn }).then((v) => {
      expect(v.cnicNtn, 'saved').to.eq(ntn)
      cy.visit('/businessDashboard'); cy.waitForAppReady()
      cy.openSection('VenderDiv')
      cy.get('#VenderDiv input[type="search"]').first().clear().type(v.name)
      cy.contains('#VenderDiv tr', v.name, { timeout: 10000 }).find('.js-edit-row').first().click()
      cy.get('#VenderModal').should('be.visible')
      cy.get('#venderCnicNtn').should('be.visible').and('have.value', ntn)
    })
  })
})

// ── DR-2: the second role from the screen ───────────────────────────────────────────────────────────
describe('DR-2 — second role, badges, link and unlink', () => {
  beforeEach(() => cy.loginAsOwner())

  const supplierRow = (id) => cy.request('/getUserVender').then((r) => list(r.body).find((x) => x.id === id))
  const customerRow = (id) => cy.request('/getUserCustomer?q=-1').then((r) => list(r.body).find((x) => (x.customerId || x.id) === id))
  const json = (url, body, opts = {}) => cy.request({ method: 'POST', url, headers: { 'Content-Type': 'application/json' }, body, failOnStatusCode: false, ...opts })
  const searchGrid = (div, text) => cy.get(`#${div} input[type="search"]`).first().clear().type(text)

  it('DR2-1 — rows say which partners hold the other role, in the data AND on the grid', () => {
    const s = uniq()
    const phone = mobile(s)
    addCustomer({ name: 'DR Badge C ' + s, contact: phone }).then((c) => {
      addSupplier({ name: 'DR Badge S ' + s, mobile: phone }).then((v) => {
        customerRow(c.customerId || c.id).its('alsoSupplier').should('eq', true)
        supplierRow(v.id).its('alsoCustomer').should('eq', true)
        cy.openSection('CustomerDiv')
        searchGrid('CustomerDiv', c.name)
        cy.contains('#CustomerDiv tr', c.name).find('[data-dr-badge="supplier"]').should('be.visible')
        // A partner with the other role already does not offer to add it again.
        cy.contains('#CustomerDiv tr', c.name).find('[data-action="add-as-supplier"]').should('not.exist')
      })
    })
    // The control: a customer with no supplier role has no badge, and is offered "+ Supplier".
    const t = uniq()
    addCustomer({ name: 'DR NoBadge C ' + t, contact: mobile(Number(t) + 11) }).then((c) => {
      customerRow(c.customerId || c.id).its('alsoSupplier').should('eq', false)
    })
  })

  it('DR2-2 — "+ Supplier" opens the supplier form filled from the customer; saving it makes ONE partner', () => {
    const s = uniq()
    const coName = 'DR2Co_' + s
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: coName, email: `${coName}@t.com` } })
    cy.request('/getUserCompany').then((co) => {
      const companyId = list(co.body).find((x) => x.name === coName).id
      addCustomer({ name: 'DR Add C ' + s, contact: mobile(s), email: `add${s}@t.com`, address: 'Shop 4, Main Bazar' }).then((c) => {
        cy.openSection('CustomerDiv')
        searchGrid('CustomerDiv', c.name)
        cy.contains('#CustomerDiv tr', c.name).find('[data-action="add-as-supplier"]').scrollIntoView().click()
        cy.get('#VenderModal').should('be.visible')
        cy.get('#venderName').should('have.value', c.name)
        cy.get('#venderMobile').should('have.value', c.contact)
        cy.get('#venderEmail').should('have.value', c.email)
        cy.get('#venderAddress').should('have.value', 'Shop 4, Main Bazar')
        cy.get('#venderId').should('have.value', '')   // a NEW supplier, never an edit of another
        // D7: the company stays the operator's own pick.
        cy.window().then((w) => w.$('#venderCompanyDD').selectpicker('val', [String(companyId)]))
        cy.get('#addVender').click()
        cy.get('#VenderModal').should('not.be.visible')
        cy.request('/getUserVender').then((r) => {
          const v = list(r.body).find((x) => x.name === c.name)
          expect(v, 'the supplier was saved').to.be.an('object')
          expect(v.partyId, 'one partner for both records').to.eq(c.partyId)
          expect(v.alsoCustomer, 'and the supplier row says so').to.eq(true)
        })
      })
    })
  })

  it('DR2-3 — owner links a customer to a supplier matching could not find, and unlinks it; refusals are sentences', () => {
    const s = uniq()
    addCustomer({ name: 'DR Link C ' + s, contact: mobile(s) }).then((c) => {
      addSupplier({ name: 'DR Link S ' + s, mobile: mobile(Number(s) + 9) }).then((v) => {
        expect(v.partyId, 'different numbers = different partners at first').to.not.eq(c.partyId)
        json('/partyLink', { customerId: c.customerId || c.id, venderId: v.id }).its('body.status').should('eq', 'SUCCESS')
        supplierRow(v.id).then((row) => {
          expect(row.partyId, 'linked to the customer\'s partner').to.eq(c.partyId)
          expect(row.alsoCustomer).to.eq(true)
        })
        partyRoles(c.partyId).then((d) => {
          const roles = (d.roles || []).filter((x) => x.module === 'business').map((x) => x.role + ':' + x.localId)
          expect(roles, 'the partner lists both records').to.include.members(['CUSTOMER:' + (c.customerId || c.id), 'VENDOR:' + v.id])
        })
        // Linking again is not an error.
        json('/partyLink', { customerId: c.customerId || c.id, venderId: v.id }).its('body.status').should('eq', 'SUCCESS')

        json('/partyUnlink', { role: 'VENDOR', id: v.id }).its('body.status').should('eq', 'SUCCESS')
        supplierRow(v.id).then((row) => {
          expect(row.partyId, 'a partner of its own').to.be.a('number').and.not.eq(c.partyId)
          expect(row.alsoCustomer).to.eq(false)
        })
        partyRoles(c.partyId).then((d) => {
          const vend = (d.roles || []).filter((x) => x.role === 'VENDOR' && Number(x.localId) === v.id)
          expect(vend, 'the old partner no longer claims the supplier').to.have.length(0)
        })
        // Unlinking a record that shares its partner with nothing is refused with a sentence, and changes nothing.
        json('/partyUnlink', { role: 'VENDOR', id: v.id }).then((r) => {
          expect(r.body.status).to.eq('FAILED')
          expect(r.body.message).to.match(/does not share/)
        })
      })
    })
  })

  it('DR2-4 — a cashier can neither link nor unlink', () => {
    cy.loginAsCashierA()
    json('/partyLink', { customerId: 1, venderId: 1 })
      .then((r) => expect(r.status === 403 || (r.body && r.body.status !== 'SUCCESS'), 'link refused ' + r.status).to.eq(true))
    json('/partyUnlink', { role: 'VENDOR', id: 1 })
      .then((r) => expect(r.status === 403 || (r.body && r.body.status !== 'SUCCESS'), 'unlink refused ' + r.status).to.eq(true))
  })

  it('DR2-5 — link and unlink by hand, from the 360 view on a customer row', () => {
    const s = uniq()
    addCustomer({ name: 'DR UI C ' + s, contact: mobile(s) }).then((c) => {
      addSupplier({ name: 'DR UI S ' + s, mobile: mobile(Number(s) + 21) }).then((v) => {
        cy.openSection('CustomerDiv')
        searchGrid('CustomerDiv', c.name)
        cy.contains('#CustomerDiv tr', c.name).contains('button', '360').click()
        cy.get('.c360-card [data-dr-link-open]').should('be.visible').click()
        cy.get('.c360-card [data-dr-filter]').type(v.name)
        cy.get('.c360-card [data-dr-list] option').should('have.length', 1).first().then(($o) => {
          cy.get('.c360-card [data-dr-list]').select($o.val())
        })
        cy.get('.c360-card [data-dr-link]').click()
        cy.get('[data-ui-confirm="ok"]').click()
        // The view comes back on the same partner, now holding both roles — so it offers Unlink, not Link.
        cy.get('.c360-card [data-dr-unlink="VENDOR:' + v.id + '"]', { timeout: 10000 }).should('be.visible')
        supplierRow(v.id).its('partyId').should('eq', c.partyId)

        cy.get('.c360-card [data-dr-unlink="VENDOR:' + v.id + '"]').click()
        cy.get('[data-ui-confirm="ok"]').click()
        cy.get('.c360-card [data-dr-link-open]', { timeout: 10000 }).should('be.visible')
        supplierRow(v.id).its('partyId').should('not.eq', c.partyId)
        cy.get('.c360-card .c360-x').click()
      })
    })
  })

  it('DR2-6 — supplier rows show the badge and the 360 view (G9), and "+ Customer" fills the customer form', () => {
    const s = uniq()
    const phone = mobile(s)
    addCustomer({ name: 'DR Sup360 C ' + s, contact: phone }).then(() => {
      addSupplier({ name: 'DR Sup360 S ' + s, mobile: phone }).then((v) => {
        cy.openSection('VenderDiv')
        searchGrid('VenderDiv', v.name)
        cy.contains('#VenderDiv tr', v.name).find('[data-dr-badge="customer"]').should('be.visible')
        cy.contains('#VenderDiv tr', v.name).contains('button', '360').click()
        cy.get('.c360-card').should('be.visible').and('contain', 'DR Sup360')
        cy.get('.c360-card .c360-x').click()
      })
    })
    const t = uniq()
    addSupplier({ name: 'DR AddC S ' + t, mobile: mobile(Number(t) + 31), email: `addc${t}@t.com` }).then((v) => {
      cy.openSection('VenderDiv')
      searchGrid('VenderDiv', v.name)
      cy.contains('#VenderDiv tr', v.name).find('[data-action="add-as-customer"]').scrollIntoView().click()
      cy.get('#CustomerModal').should('be.visible')
      cy.get('#customerName').should('have.value', v.name)
      cy.get('#contact').should('have.value', v.mobile)
      cy.get('#email').should('have.value', v.email)
      cy.get('#customerId').should('have.value', '')
    })
  })
})

// ── DR-3 / DR-4: position and set-off (MONEY — lifecycle tenant) ────────────────────────────────────
describe('DR-3 / DR-4 — position and set-off', () => {
  const asLifecycle = () => cy.loginAs(LIFECYCLE, PW, '/getBusinessDashboardStats')
  const trialBalance = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
  const net = (tb, code) => { const a = ((tb && tb.rows) || []).find((x) => x.code === code) || { debit: 0, credit: 0 }; return Number(a.debit) - Number(a.credit) }

  /** A partner that owes us 30,000 (customer opening balance) and is owed 50,000 (supplier opening balance). */
  const seedPartner = () => {
    const s = uniq()
    const phone = mobile(s)
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: 'business.cutoverDate', value: '2026-09-01' }, failOnStatusCode: false })
    return addCustomer({ name: 'DR Pos C ' + s, contact: phone }).then((c) =>
      addSupplier({ name: 'DR Pos S ' + s, mobile: phone }).then((v) => {
        cy.request({ method: 'POST', url: '/postOpeningBalance', form: true, body: { customerId: c.customerId || c.id, amount: 30000, reference: 'dr ' + s } })
          .its('body.status').should('eq', 'SUCCESS')
        cy.request({ method: 'POST', url: '/postOpeningBalance', form: true, body: { venderId: v.id, amount: 50000, reference: 'dr ' + s } })
          .its('body.status').should('eq', 'SUCCESS')
        // cy.wrap: this callback queued commands, so it must hand back a chainable, not a plain object.
        return cy.wrap({ c, v, partyId: c.partyId })
      }))
  }

  /*
   * LEAVE NO SERVER STATE. Posting an opening balance sets AND LOCKS the tenant's cutover date. Left locked, it broke
   * opening-balances.cy.js on its next run: that spec clears the date (the lock allows clearing), then cannot set it
   * again, and every posting after is refused "Set the cutover date first" (2026-10-02, 12 red cases). So the two
   * settings are read before this block and put back after it — unlock first, as that spec's own after() does.
   */
  const CUT = 'business.cutoverDate', LOCK = 'business.cutoverLocked'
  let saved = null
  const setCfg = (key, value) => cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value }, failOnStatusCode: false })
    .then((r) => expect(r.body && (r.body.success === true || r.body.status === 'SUCCESS'), `restore ${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))

  before(() => {
    asLifecycle()
    cy.request('/getBusinessConfig').then((r) => {
      const rows = list(r.body)
      const val = (k) => { const x = rows.find((y) => y.key === k); return x ? String(x.value == null ? '' : x.value) : '' }
      saved = { cut: val(CUT), lock: val(LOCK) || 'false' }
    })
  })

  after(() => {
    if (!saved) return
    asLifecycle()
    setCfg(LOCK, 'false')
    setCfg(CUT, saved.cut)
    if (saved.lock === 'true') setCfg(LOCK, 'true')
  })

  beforeEach(asLifecycle)

  it('DR3-1 — position shows both balances and the net, and posts nothing', () => {
    seedPartner().then(({ c, v, partyId }) => {
      trialBalance().then((before) => {
        cy.request(`/partyPosition?partyId=${partyId}`).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          const p = payload(r.body)
          expect(Number(p.receivable), 'they owe us').to.eq(30000)
          expect(Number(p.payable), 'we owe them').to.eq(50000)
          expect(Number(p.netIfSetOff), 'net (we owe 20,000)').to.eq(-20000)
          expect(Number(p.setOffLimit), 'a set-off could clear at most the smaller side').to.eq(30000)
          expect(p.customers.map((x) => x.id), 'the customer record').to.include(c.customerId || c.id)
          expect(p.suppliers.map((x) => x.id), 'the supplier record').to.include(v.id)
        })
        trialBalance().then((after) => {
          expect(after.totalDebit, 'a read posts nothing').to.eq(before.totalDebit)
          expect(after.totalCredit).to.eq(before.totalCredit)
        })
        // The balances themselves are untouched by reading them.
        cy.request('/getUserCustomer?q=-1').then((r) =>
          expect(Number(list(r.body).find((x) => (x.customerId || x.id) === (c.customerId || c.id)).dueAmount)).to.eq(30000))
      })
    })
  })

  it('DR3-2 — the 360 view shows the position, and each record opens its own statement', () => {
    seedPartner().then(({ c, v }) => {
      cy.openSection('CustomerDiv')
      cy.get('#CustomerDiv input[type="search"]').first().clear().type(c.name)
      cy.contains('#CustomerDiv tr', c.name).contains('button', '360').click()
      cy.get('.c360-card [data-dr-pos="receivable"]', { timeout: 10000 }).should('have.attr', 'data-amount', '30000')
      cy.get('.c360-card [data-dr-pos="payable"]').should('have.attr', 'data-amount', '50000')
      cy.get('.c360-card [data-dr-pos="net"]').should('have.attr', 'data-amount', '20000')
        .and('contain', 'we would still owe them')
      cy.get(`.c360-card [data-dr-stmt="VENDOR:${v.id}"]`).click()
      cy.get('.c360-card').should('not.exist')
      cy.get('#StatementDialog').should('be.visible').and('contain', v.name)
    })
  })

  it('DR3-3 — a partner with one role has no position block; another tenant\'s partner and a cashier are refused', () => {
    const s = uniq()
    addCustomer({ name: 'DR OneRole C ' + s, contact: mobile(Number(s) + 41) }).then((c) => {
      cy.request(`/partyPosition?partyId=${c.partyId}`).its('body.status').should('eq', 'SUCCESS')   // readable…
      cy.openSection('CustomerDiv')
      cy.get('#CustomerDiv input[type="search"]').first().clear().type(c.name)
      cy.contains('#CustomerDiv tr', c.name).contains('button', '360').click()
      cy.get('.c360-card [data-dr-box]').should('be.visible')
      cy.get('.c360-card [data-dr-position]').should('not.exist')   // …but not shown: one side is its own statement
      cy.get('.c360-card .c360-x').click()

      // The same partner id asked for by ANOTHER tenant: refused, never an empty position.
      cy.loginAsOwner()
      cy.request(`/partyPosition?partyId=${c.partyId}`).then((r) => {
        expect(r.body.status).to.eq('FAILED')
        expect(r.body.message).to.match(/No customer or supplier/)
      })
      cy.loginAsCashierA()
      cy.request({ url: `/partyPosition?partyId=${c.partyId}`, failOnStatusCode: false })
        .then((r) => expect(r.status === 403 || (r.body && r.body.status !== 'SUCCESS'), 'cashier refused ' + r.status).to.eq(true))
    })
  })

  // ── DR-4: set-off — both legs in ONE finance transaction, or nothing moves ─────────────────────────────────
  const setOff = (body, opts = {}) => cy.request({ method: 'POST', url: '/partySetOff', failOnStatusCode: false,
    headers: { 'Content-Type': 'application/json' }, body: { sameBusiness: true, reason: 'agreed contra', idempotencyKey: 'dr-' + uniq() + Math.random(), ...body }, ...opts })
  const reverse = (setOffId, reason = 'entered in error') => cy.request({ method: 'POST', url: '/partySetOffReverse',
    failOnStatusCode: false, headers: { 'Content-Type': 'application/json' }, body: { setOffId, reason, idempotencyKey: 'rev-' + uniq() + Math.random() } })
  const position = (partyId) => cy.request(`/partyPosition?partyId=${partyId}`).then((r) => payload(r.body))
  const pair = (c, v) => ({ customerId: c.customerId || c.id, venderId: v.id })

  it('DR4-1 — refused without the "same business" tick, without a reason, or over the smaller balance; nothing moves', () => {
    seedPartner().then(({ c, v, partyId }) => {
      setOff({ ...pair(c, v), amount: 100, sameBusiness: false }).then((r) => {
        expect(r.body.status).to.eq('FAILED'); expect(r.body.message).to.match(/same business/)
      })
      setOff({ ...pair(c, v), amount: 100, reason: '' }).then((r) => {
        expect(r.body.status).to.eq('FAILED'); expect(r.body.message).to.match(/reason/i)
      })
      setOff({ ...pair(c, v), amount: 30000.01 }).then((r) => {
        expect(r.body.status).to.eq('FAILED'); expect(r.body.message, 'names the limit').to.match(/30000/)
      })
      position(partyId).then((p) => {
        expect(Number(p.receivable), 'nothing moved').to.eq(30000)
        expect(Number(p.payable)).to.eq(50000)
        expect(Number(p.setOffLimit), 'the screen offers what the guard accepts').to.eq(30000)
      })
    })
  })

  it('DR4-2 — a set-off moves AR and AP only: AR −30,000, AP −30,000, cash and bank untouched, 1900 nets to zero', () => {
    seedPartner().then(({ c, v, partyId }) => {
      trialBalance().then((before) => {
        setOff({ ...pair(c, v), amount: 30000, reference: 'letter 12' }).then((r) => {
          expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
          const o = payload(r.body)
          expect(o.setOffNo).to.match(/^SETOFF-\d{6}$/)
          expect(o.receiptNo, 'finance confirmed the receipt leg').to.be.a('string').and.not.be.empty
          expect(o.voucherNo, 'finance confirmed the payment leg').to.be.a('string').and.not.be.empty
        })
        trialBalance().then((after) => {
          expect(net(after, '1100') - net(before, '1100'), 'AR credited 30,000').to.eq(-30000)
          expect(net(after, '2000') - net(before, '2000'), 'AP debited 30,000').to.eq(30000)
          expect(net(after, '1000') - net(before, '1000'), 'cash did not move').to.eq(0)
          expect(net(after, '1010') - net(before, '1010'), 'bank did not move').to.eq(0)
          expect(net(after, '1900') - net(before, '1900'), 'clearing nets to zero').to.eq(0)
          expect(after.balanced, 'trial balance still balances').to.eq(true)
        })
        position(partyId).then((p) => {
          expect(Number(p.receivable), 'they owe us nothing now').to.eq(0)
          expect(Number(p.payable), 'we still owe them 20,000').to.eq(20000)
        })
      })
    })
  })

  it('DR4-3 — the same idempotency key twice records ONE set-off and clears once', () => {
    seedPartner().then(({ c, v, partyId }) => {
      const key = 'dr-once-' + uniq()
      setOff({ ...pair(c, v), amount: 10000, idempotencyKey: key }).then((a) => {
        expect(a.body.status).to.eq('SUCCESS')
        setOff({ ...pair(c, v), amount: 10000, idempotencyKey: key }).then((b) => {
          expect(b.body.status).to.eq('SUCCESS')
          expect(payload(b.body).replay, 'answered as a replay').to.eq(true)
          expect(payload(b.body).setOffNo, 'the first document').to.eq(payload(a.body).setOffNo)
        })
      })
      position(partyId).then((p) => expect(Number(p.receivable), 'cleared ONCE').to.eq(20000))
    })
  })

  it('DR4-4 — both statements show the set-off; a cashier cannot create one', () => {
    seedPartner().then(({ c, v }) => {
      setOff({ ...pair(c, v), amount: 5000 }).then((r) => {
        const no = payload(r.body).setOffNo
        cy.request(`/customerStatement?customerId=${c.customerId || c.id}`).then((st) => expect(JSON.stringify(st.body), 'customer statement').to.contain(no))
        cy.request(`/vendorStatement?venderId=${v.id}`).then((st) => expect(JSON.stringify(st.body), 'supplier statement').to.contain(no))
      })
      cy.loginAsCashierA()
      setOff({ ...pair(c, v), amount: 1 }).then((r2) =>
        expect(r2.status === 403 || (r2.body && r2.body.status !== 'SUCCESS'), 'cashier refused ' + r2.status).to.eq(true))
    })
  })

  it('DR4-5 — a customer and a supplier of DIFFERENT partners are refused', () => {
    const s = uniq()
    addCustomer({ name: 'DR X C ' + s, contact: mobile(s) }).then((c) =>
      addSupplier({ name: 'DR X S ' + s, mobile: mobile(Number(s) + 5) }).then((v) =>
        setOff({ ...pair(c, v), amount: 1 }).then((r) => {
          expect(r.body.status).to.eq('FAILED')
          expect(r.body.message).to.match(/not the same partner/)
        })))
  })

  it('DR4-6 — reversal restores both balances and every account; a second reversal is refused; unlink waits for it', () => {
    seedPartner().then(({ c, v, partyId }) => {
      trialBalance().then((before) => {
        setOff({ ...pair(c, v), amount: 30000 }).then((r) => {
          const id = payload(r.body).id
          // While it stands, the two records cannot be pulled apart.
          cy.request({ method: 'POST', url: '/partyUnlink', failOnStatusCode: false, headers: { 'Content-Type': 'application/json' },
            body: { role: 'VENDOR', id: v.id } }).then((u) => {
            expect(u.body.status).to.eq('FAILED'); expect(u.body.message).to.match(/set-off stands/)
          })
          reverse(id).its('body.status').should('eq', 'SUCCESS')
          reverse(id).then((again) => {
            expect(again.body.status).to.eq('FAILED'); expect(again.body.message).to.match(/already reversed/)
          })
        })
        position(partyId).then((p) => {
          expect(Number(p.receivable), 'they owe us again').to.eq(30000)
          expect(Number(p.payable), 'we owe them again').to.eq(50000)
        })
        trialBalance().then((after) => {
          ;['1100', '2000', '1000', '1010', '1900'].forEach((code) =>
            expect(net(after, code) - net(before, code), `account ${code} back where it started`).to.eq(0))
          expect(after.balanced).to.eq(true)
        })
      })
    })
  })

  it('DR4-7 — by hand: Set off… in the 360 view, then Reverse; the position follows each step', () => {
    seedPartner().then(({ c, v }) => {
      cy.openSection('CustomerDiv')
      cy.get('#CustomerDiv input[type="search"]').first().clear().type(c.name)
      cy.contains('#CustomerDiv tr', c.name).contains('button', '360').click()
      cy.get('.c360-card [data-dr-setoff-open]', { timeout: 10000 }).should('be.enabled').click()
      cy.get('.c360-card [data-dr-amount]').should('have.value', '30000')   // offered: the most that can be set off
      cy.get('.c360-card [data-dr-amount]').clear().type('12000')
      cy.get('.c360-card [data-dr-setoff-save]').click()
      cy.get('.c360-card [data-dr-setoff-form] .dr-say').should('contain', 'Tick')          // the tick is required
      cy.get('.c360-card [data-dr-same]').check()
      cy.get('.c360-card [data-dr-setoff-save]').click()
      cy.get('.c360-card [data-dr-setoff-form] .dr-say').should('contain', 'reason')         // so is a reason
      cy.get('.c360-card [data-dr-reason]').type('agreed by phone')
      cy.get('.c360-card [data-dr-setoff-save]').click()
      // The view comes back on the partner with the new figures and the document listed.
      cy.get('.c360-card [data-dr-pos="receivable"]', { timeout: 10000 }).should('have.attr', 'data-amount', '18000')
      cy.get('.c360-card [data-dr-pos="payable"]').should('have.attr', 'data-amount', '38000')
      cy.get('.c360-card [data-dr-setoff-row]').should('have.length', 1).first().invoke('attr', 'data-dr-setoff-row').then((no) => {
        cy.get(`.c360-card [data-dr-reverse="${no}"]`).click()
        cy.get('.uiC-card .uiC-input').type('typed the wrong amount')
        cy.get('[data-ui-confirm="ok"]').click()
        cy.get('.c360-card [data-dr-pos="receivable"]', { timeout: 10000 }).should('have.attr', 'data-amount', '30000')
        cy.get(`.c360-card [data-dr-setoff-row="${no}"]`).should('contain', 'reversed').find('[data-dr-reverse]').should('not.exist')
      })
      cy.get('.c360-card .c360-x').click()
      cy.wrap(v).its('id').should('be.a', 'number')
    })
  })

  // ── DR-5: the payment hint — mentions the other side, applies nothing ───────────────────────────────────
  it('DR5-1 — the hint names what the partner is owed on the other side, and moves nothing', () => {
    seedPartner().then(({ c, v, partyId }) => {
      cy.request(`/partyPaymentHint?customerId=${c.customerId || c.id}`).then((r) => {
        const h = payload(r.body)
        expect(Number(h.otherSideOpen), 'we owe them 50,000 as a supplier').to.eq(50000)
        expect(Number(h.setOffLimit)).to.eq(30000)
        expect(h.partyId).to.eq(partyId)
      })
      cy.request(`/partyPaymentHint?venderId=${v.id}`).then((r) =>
        expect(Number(payload(r.body).otherSideOpen), 'they owe us 30,000 as a customer').to.eq(30000))
      position(partyId).then((p) => {
        expect(Number(p.receivable), 'nothing applied by the hint').to.eq(30000)
        expect(Number(p.payable)).to.eq(50000)
      })
    })
  })

  it('DR5-2 — Receive Payment shows the note; "Set off instead…" opens the set-off, and the receipt is untouched', () => {
    seedPartner().then(({ c }) => {
      cy.openSection('CustomerDiv')
      cy.get('#CustomerDiv input[type="search"]').first().clear().type(c.name)
      cy.contains('#CustomerDiv tr', c.name).find('.rcv-pay-btn').click()
      cy.get('#ReceivePaymentModal [data-dr-hint="CUSTOMER"]', { timeout: 10000 })
        .should('be.visible').and('have.attr', 'data-amount', '50000').and('contain', 'we owe them')
      cy.get('#rcvAmount').should('have.value', '30000')   // the dialog's own figures are not touched
      cy.get('#ReceivePaymentModal [data-dr-hint-setoff]').click()
      cy.get('#ReceivePaymentModal').should('not.be.visible')
      cy.get('.c360-card [data-dr-setoff-open]', { timeout: 10000 }).should('be.visible')
      cy.get('.c360-card .c360-x').click()
    })
  })

  it('DR5-3 — Pay Vendor shows what the partner owes us', () => {
    seedPartner().then(({ v }) => {
      cy.openSection('VenderDiv')
      cy.get('#VenderDiv input[type="search"]').first().clear().type(v.name)
      cy.contains('#VenderDiv tr', v.name).find('.pay-vendor-btn').click()
      cy.get('#PayVendorModal [data-dr-hint="VENDOR"]', { timeout: 10000 })
        .should('be.visible').and('have.attr', 'data-amount', '30000').and('contain', 'owes us')
      cy.get('#PayVendorModal .crud-x').click()
    })
  })

  it('DR5-4 — no note for a one-role customer; a cashier is refused the hint', () => {
    const s = uniq()
    addCustomer({ name: 'DR NoHint C ' + s, contact: mobile(Number(s) + 61) }).then((c) => {
      cy.request(`/partyPaymentHint?customerId=${c.customerId || c.id}`).then((r) =>
        expect(Number(payload(r.body).otherSideOpen), 'nothing on the other side').to.eq(0))
      cy.loginAsCashierA()
      cy.request({ url: `/partyPaymentHint?customerId=${c.customerId || c.id}`, failOnStatusCode: false })
        .then((r) => expect(r.status === 403 || (r.body && r.body.status !== 'SUCCESS'), 'cashier refused ' + r.status).to.eq(true))
    })
  })

})
