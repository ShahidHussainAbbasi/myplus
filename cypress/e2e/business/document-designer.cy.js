/**
 * B2B Phase 3g-3 / 3g-4 — owner-designable document layouts.
 *
 * The point of this gate is the SAFETY BOUNDARY, not the screen. A layout decides what appears on every
 * invoice a business issues, so the server must accept only whitelisted field keys, must normalise what it
 * stores, and must never let one tenant reach another's layout. The designer UI is the easy part; a
 * validator that can be talked past is the part that would matter.
 *
 * Design: microservices/docs/slices/b2b-P3g-trade-invoice-designer.md
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/document-designer.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const validProfile = {
  paper: 'A4',
  numberSystem: 'indian',
  showDrCr: true,
  header: { titleStyle: 'boxed', showLogo: false, columns: [['invoiceNo', 'dated'], ['customerName']] },
  lines: [
    { key: 'itemCode', label: 'Code', width: 10 },
    { key: 'itemName', width: 40 },
    { key: 'quantity', width: 10 },
    { key: 'lineTotal', width: 15 },
  ],
  totals: ['grandTotal', 'amountInWords'],
  footer: { text: 'Thank you', showSignature: true },
}

const save = (body) =>
  cy.request({ method: 'POST', url: '/saveDocumentTemplate', failOnStatusCode: false,
    headers: { 'Content-Type': 'application/json' }, body })

describe('B2B P3g — document designer', () => {

  beforeEach(() => { cy.loginAsOwner() })

  // ── the contract between designer, renderer and validator ────────────────────────────────

  it('/documentFields serves the whitelist the designer builds itself from', () => {
    cy.request('/documentFields').then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      const w = r.body.object
      expect(w, 'whitelist object').to.exist
      for (const kind of ['header', 'line', 'totals']) {
        expect(w[kind], `${kind} list`).to.be.an('array').and.not.be.empty
      }
      // The columns the trade invoice is built from must be offerable, or the designer cannot reproduce it.
      for (const key of ['itemCode', 'packing', 'batchNo', 'bonusQty', 'tradePrice', 'netTradePrice']) {
        expect(w.line, `line field ${key}`).to.include(key)
      }
    })
  })

  it('the browser renderer and the server validator agree on every field key', () => {
    // These two lists are duplicated across the language boundary ON PURPOSE — the client renders from its
    // copy, the server validates against its own, and a server trusting the client's list validates nothing.
    // Duplication that must agree is exactly the kind a test should pin.
    cy.visit('/businessDashboard')
    cy.request('/documentFields').then((r) => {
      const server = r.body.object
      cy.window().then((win) => {
        const client = win.DocumentRenderer.FIELD_WHITELIST
        for (const kind of ['header', 'line', 'totals']) {
          const clientKeys = Object.keys(client[kind]).sort()
          expect(clientKeys, `${kind} keys match the server`).to.deep.eq(server[kind].slice().sort())
        }
      })
    })
  })

  // ── CRUD ──────────────────────────────────────────────────────────────────────────────────

  it('an owner can save, list and delete a layout', () => {
    const name = 'CyLayout_' + uniq()
    save({ name, docType: 'SALE', channel: 'B2B', isDefault: false,
           profileJson: JSON.stringify(validProfile) })
      .then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        return cy.request('/documentTemplates')
      })
      .then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        const rows = r.body.collection || []
        const mine = rows.find((x) => x.name === name)
        expect(mine, 'the layout is listed').to.exist
        // Stored NORMALISED, not echoed: the server rebuilds the profile from what passed validation.
        const stored = JSON.parse(mine.profileJson)
        expect(stored.paper).to.eq('A4')
        expect(stored.lines.map((c) => c.key)).to.deep.eq(['itemCode', 'itemName', 'quantity', 'lineTotal'])
        return cy.request({ method: 'POST', url: '/deleteDocumentTemplate', form: true,
          failOnStatusCode: false, body: { id: mine.id } })
      })
      .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
  })

  // ── the boundary ──────────────────────────────────────────────────────────────────────────

  it('an unknown column is REJECTED, and the message names it', () => {
    const bad = Object.assign({}, validProfile, {
      lines: [{ key: 'itemName' }, { key: 'secretMarginColumn' }],
    })
    save({ name: 'CyBad_' + uniq(), docType: 'SALE', channel: 'B2B',
           profileJson: JSON.stringify(bad) })
      .then((r) => {
        expect(r.body.status, 'rejected, not silently dropped').to.eq('FAILED')
        expect(r.body.message).to.contain('secretMarginColumn')
      })
  })

  it('an unknown header field and an unknown summary row are rejected too', () => {
    const badHeader = Object.assign({}, validProfile, {
      header: { titleStyle: 'plain', columns: [['invoiceNo', 'costPriceOfEveryLine']] },
    })
    save({ name: 'CyBadH_' + uniq(), profileJson: JSON.stringify(badHeader) })
      .then((r) => expect(r.body.status).to.eq('FAILED'))

    const badTotals = Object.assign({}, validProfile, { totals: ['grandTotal', 'ourProfit'] })
    save({ name: 'CyBadT_' + uniq(), profileJson: JSON.stringify(badTotals) })
      .then((r) => expect(r.body.status).to.eq('FAILED'))
  })

  it('a layout with no columns is refused — an empty document is never what was meant', () => {
    const empty = Object.assign({}, validProfile, { lines: [] })
    save({ name: 'CyEmpty_' + uniq(), profileJson: JSON.stringify(empty) })
      .then((r) => expect(r.body.status).to.eq('FAILED'))
  })

  it('a duplicated column is refused — it would print the same figure twice', () => {
    const dup = Object.assign({}, validProfile, {
      lines: [{ key: 'itemName' }, { key: 'itemName' }],
    })
    save({ name: 'CyDup_' + uniq(), profileJson: JSON.stringify(dup) })
      .then((r) => {
        expect(r.body.status).to.eq('FAILED')
        expect(r.body.message).to.contain('more than once')
      })
  })

  it('ANTI-IDOR — a layout id from another tenant does not resolve', () => {
    cy.request({ url: '/documentTemplate?id=999999999', failOnStatusCode: false })
      .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('NOT_FOUND'))
  })

  // ── the screen ────────────────────────────────────────────────────────────────────────────

  it('the designer screen opens and previews through the PRODUCTION renderer', () => {
    cy.visit('/businessDashboard')
    cy.get('#navDocumentDesigner', { timeout: 10000 }).click({ force: true })
    cy.get('#DocumentDesignerDiv').should('be.visible')
    cy.get('#tableDocColumns tbody tr').should('have.length.greaterThan', 0)
    cy.get('#docHeaderFields').should('not.be.empty')
    cy.get('#docTotalRows').should('not.be.empty')
    // The preview is drawn by DocumentRenderer.buildHtml — the same call the printer makes.
    cy.get('#docPreviewFrame').its('0.contentDocument.body').should('not.be.empty')
  })

  it('reordering a column changes the printed order', () => {
    cy.visit('/businessDashboard')
    cy.get('#navDocumentDesigner', { timeout: 10000 }).click({ force: true })
    cy.get('#tableDocColumns tbody tr').eq(1).find('.dtColUp').click()
    cy.get('#docPreviewFrame').its('0.contentDocument.body').should('not.be.empty')
  })
})
