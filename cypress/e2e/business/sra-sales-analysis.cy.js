/**
 * SRA-4a — the Sale Detail Report by rep and area: every new column a filter AND a grouping, free goods at TP, and each
 * member sees only the rows their set allows. Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §7 / §7.6.
 *
 * The scripted month (Test Book 29b), on owner.marketplace@ — every name carries this run's stamp:
 *
 *   sale  how it came in                       rep        customer · area / city              lines                       total
 *   S1    the office keys Irfan's paper order  Irfan      Sarwar · Zahir Pir / Khanpur        Davitran 30 @ 115, bonus 3   3,450
 *   S2    the booker books signed in           Booker     Labaik · 1st Sector / Khanpur       Davitran 10 @ 115 · Cefim 5 @ 230  2,300
 *   S3    a walk-in at the counter             —          Model Chemist · Model Town / RYK    Cefim 2 @ 230                  460
 *
 *   Davitran is bought from "SRA4 Davis" at 100 (its TP, so the 3 free = 300); Cefim is stocked with no purchase (no
 *   supplier, no TP). Khanpur = 5,750 · Rahim Yar Khan = 460 · Davis = 4,600 · no supplier = 1,610 · grand 6,210.
 *
 * Cleanup: the reps are deactivated and the booker UNLINKED (a linked booker narrows every booking spec); the till sales
 * are voided. The dispatched order's invoice is voided too. Areas, customers and products carry the stamp and stay.
 */
const RUN = String(Date.now()).slice(-6)
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return [] }
const post = (url, body) => cy.request({ method: 'POST', url, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false, body })
const form = (url, body) => cy.request({ method: 'POST', url, form: true, failOnStatusCode: false, body })
const ctx = { reps: [], invoices: [] }

/** The report as the screen asks for it (this month), narrowed by `q`. */
const report = (q) => form('/loadSR', Object.assign({ rp: 0 }, q)).then((r) => {
  expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
  return r.body
})
const sum = (rows, k) => Math.round(rows.reduce((a, r) => a + Number(r[k] || 0), 0) * 100) / 100
const userIdOf = (email) =>
  cy.request({ method: 'POST', url: 'http://localhost:8765/api/auth/login', headers: { 'Content-Type': 'application/json' },
    body: { email, password: 'Demo@2025!' } }).then((r) => ({
    token: r.body.data.accessToken,
    id: Number(JSON.parse(atob(r.body.data.accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).userId),
  }))
const sale = (c, lines, repId) => post('/addSell', {
  customer: { customerId: c.customerId, name: c.name, contact: c.contact }, sales: lines, repId,
  tenders: [], paidAmount: 0, grandTotal: lines.reduce((a, l) => a + l.totalAmount, 0),
  idempotencyKey: `cy-sra4-${RUN}-${c.customerId}`,
}).then((r) => {
  expect(r.body.status, JSON.stringify(r.body).slice(0, 300)).to.eq('SUCCESS')
  ctx.invoices.push(r.body.object)
  return r.body.object
})

describe('SRA-4a — the sale report by rep and area', () => {
  before(() => {
    cy.loginAsMarketplaceOwner()
    userIdOf('booker.marketplace@myplus.com').then((b) => { ctx.booker = b })
    cy.request('/getSalesReps').then((r) => {   // an interrupted run may have left the booker linked
      const linked = list(r.body).find((x) => Number(x.userId) === ctx.booker.id)
      if (linked) form('/updateSalesRep', { id: linked.id, userId: 0 })
    })

    // ── reps ──
    form('/addSalesRep', { name: `SRA4 Irfan ${RUN}` }).then((r) => { ctx.irfan = r.body.object.id; ctx.reps.push(ctx.irfan) })
    cy.then(() => form('/addSalesRep', { name: `SRA4 Booker ${RUN}`, userId: ctx.booker.id }))
      .then((r) => { expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'); ctx.bookerRep = r.body.object.id; ctx.reps.push(ctx.bookerRep) })

    // ── cities and areas ──
    const area = (cityKey, cityName, key, name) => {
      const withCity = (id) => form('/addArea', { cityId: id, name }).then((r) => { ctx[key] = r.body.object.id })
      if (ctx[cityKey]) return withCity(ctx[cityKey])
      return form('/addCity', { name: cityName }).then((r) => { ctx[cityKey] = r.body.object.id; return withCity(ctx[cityKey]) })
    }
    cy.then(() => area('khanpur', `SRA4 Khanpur ${RUN}`, 'zahirPir', `SRA4 Zahir Pir ${RUN}`))
    cy.then(() => area('khanpur', '', 'sector1', `SRA4 1st Sector ${RUN}`))
    cy.then(() => area('ryk', `SRA4 Rahim Yar Khan ${RUN}`, 'modelTown', `SRA4 Model Town ${RUN}`))

    // ── customers ──
    const customer = (key, name, n, extra) => cy.then(() => form('/addCustomer', Object.assign({ name, contact: `030${n}${RUN}1` }, extra())))
      .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS'))
      .then(() => cy.request('/getUserCustomer')).then((r) => { ctx[key] = list(r.body).find((c) => c.name === name) ?? null; expect(ctx[key], name).to.not.eq(null) })
    customer('sarwar', `SRA4 Sarwar ${RUN}`, 2, () => ({ areaId: ctx.zahirPir, cityId: ctx.khanpur, defaultRepId: ctx.irfan }))
    customer('labaik', `SRA4 Labaik ${RUN}`, 3, () => ({ areaId: ctx.sector1, cityId: ctx.khanpur }))
    customer('chemist', `SRA4 Model Chemist ${RUN}`, 4, () => ({ areaId: ctx.modelTown, cityId: ctx.ryk }))

    // ── products: Davitran bought from a supplier at 100 (its TP); Cefim stocked with no purchase ──
    cy.ensureCompany().then((companyId) => {
      form('/addVender', { name: `SRA4 Davis ${RUN}`, companyId, mobile: '0306' + RUN + '1', email: `sra4${RUN}@t.com` })
      cy.request('/getUserVenders').then((vr) => {
        const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
        ctx.davis = Number((new RegExp(`<option value=(\\d+)[^>]*>SRA4 Davis ${RUN}`).exec(html) || [])[1])
        expect(ctx.davis, 'the supplier').to.be.greaterThan(0)
      })
    })
    post('/addProduct', { name: `SRA4 Davitran ${RUN}`, sku: 'SD' + RUN, sellingPrice: 115, taxRate: 0, unit: 'pcs' })
      .then((r) => { ctx.davitran = r.body.data.id })
    post('/addProduct', { name: `SRA4 Cefim ${RUN}`, sku: 'SC' + RUN, sellingPrice: 230, taxRate: 0, unit: 'pcs' })
      .then((r) => { ctx.cefim = r.body.data.id })
    cy.then(() => form('/addPurchase', { productId: ctx.davitran, venderId: ctx.davis, quantity: 100, purchaseRate: 100,
      'stock.bpurchaseRate': 100, 'stock.bsellRate': 115,
      // owner.marketplace tracks batches and expiry (a pharmacy distributor) — a purchase must name both
      'stock.batchNo': 'SRA4-B-' + RUN, 'stock.bexpDate': '2028-06-30', totalAmount: 10000, paidAmount: 10000, purchaseInvoiceNo: 'SRA4-P-' + RUN }))
      .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 300)).to.eq('SUCCESS'))
    cy.then(() => post('/addProductStock', { productId: ctx.cefim, quantity: 50 })).its('body.success').should('eq', true)

    // ── S1 the office keys Irfan's paper order at the till ──
    cy.then(() => sale(ctx.sarwar,
      [{ productId: ctx.davitran, quantity: 30, sellRate: 115, totalAmount: 3450, bonusQuantity: 3 }], ctx.irfan))
      .then((inv) => { ctx.s1 = inv })
    // ── S2 the booker books signed in; the owner confirms and dispatches ──
    cy.then(() => {
      cy.loginAsOrderBooker()
      post('/bookOrder', { customerId: ctx.labaik.customerId, customerName: ctx.labaik.name, customerContact: ctx.labaik.contact,
        items: [{ productId: ctx.davitran, quantity: 10, price: 115, productName: 'Davitran' },
                { productId: ctx.cefim, quantity: 5, price: 230, productName: 'Cefim' }] }).then((r) => {
        expect(r.body.success, JSON.stringify(r.body).slice(0, 300)).to.eq(true)
        const o = r.body.data
        cy.loginAsMarketplaceOwner()
        post('/confirmOrder', { id: o.id }).its('body.success').should('eq', true)
        post('/shipOrder', { id: o.id, lines: o.items.map((i) => ({ orderItemId: i.id, quantity: i.quantity })), carrier: 'SRA4' })
          .its('body.success').should('eq', true)
        cy.request('/getOrder?id=' + o.id).then((g) => { ctx.s2 = g.body.data.invoiceNo; ctx.invoices.push(ctx.s2) })
      })
    })
    // ── S3 a walk-in at the counter ──
    cy.then(() => sale(ctx.chemist,
      [{ productId: ctx.cefim, quantity: 2, sellRate: 230, totalAmount: 460 }], 0)).then((inv) => { ctx.s3 = inv })
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  after(() => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => ctx.invoices.forEach((invoiceNo) => form('/voidSell', { invoiceNo, reason: 'CY SRA-4a cleanup' })))
    cy.then(() => ctx.reps.forEach((id) => form('/updateSalesRep', { id, userId: 0, status: 'INACTIVE' })))
  })

  it('R1 — by rep: Irfan\'s rows are his paper order, with the free goods valued at TP', () => {
    report({ repId: ctx.irfan }).then((b) => {
      const rows = b.collection
      expect(rows.map((r) => r.invoiceNo), 'only S1').to.deep.eq([ctx.s1])
      const r = rows[0]
      expect(r.repName).to.eq(`SRA4 Irfan ${RUN}`)
      expect(r.saleSource).to.eq('REP_PAPER')
      expect(r.area).to.eq(`SRA4 Zahir Pir ${RUN}`)
      expect(r.city).to.eq(`SRA4 Khanpur ${RUN}`)
      expect(r.supplier).to.eq(`SRA4 Davis ${RUN}`)
      expect(Number(r.bonusQuantity), 'bonus qty').to.eq(3)
      expect(Number(r.bonusValue), '3 free × TP 100').to.eq(300)
      expect(sum(rows, 'totalAmount')).to.eq(3450)
    })
    report({ repId: ctx.bookerRep }).then((b) => {
      expect(sum(b.collection, 'totalAmount'), "the booker's own order").to.eq(2300)
      b.collection.forEach((r) => expect(r.saleSource).to.eq('REP_LOGIN'))
    })
  })

  it('R2 — by city, grouped by area: the groups add up to the city, which is the two areas', () => {
    report({ cityId: ctx.khanpur, groupBy: 'AREA' }).then((b) => {
      expect(sum(b.collection, 'totalAmount'), 'Khanpur = S1 + S2').to.eq(5750)
      const g = Object.fromEntries(b.object.map((x) => [x.label, Number(x.total)]))
      expect(g).to.deep.eq({ [`SRA4 Zahir Pir ${RUN}`]: 3450, [`SRA4 1st Sector ${RUN}`]: 2300 })
    })
    report({ areaId: ctx.modelTown }).then((b) => expect(sum(b.collection, 'totalAmount'), 'Model Town = S3').to.eq(460))
  })

  it('R3 — a counter sale groups as NO_REP (a code, not a name), and the source filter finds it', () => {
    report({ cityId: ctx.ryk, saleSource: 'COUNTER', groupBy: 'REP' }).then((b) => {
      expect(b.collection.map((r) => r.invoiceNo)).to.deep.eq([ctx.s3])
      expect(b.object).to.have.length(1)
      expect(b.object[0].label ?? null, 'no name').to.eq(null)
      expect(b.object[0].labelCode).to.eq('NO_REP')
    })
    report({ cityId: ctx.khanpur, groupBy: 'SOURCE' }).then((b) => {
      const g = Object.fromEntries(b.object.map((x) => [x.labelCode, Number(x.total)]))
      expect(g).to.deep.eq({ REP_PAPER: 3450, REP_LOGIN: 2300 })
    })
  })

  it('R4 — by supplier, and with / without bonus', () => {
    report({ cityId: ctx.khanpur, supplierId: ctx.davis }).then((b) => {
      expect(sum(b.collection, 'totalAmount'), 'Davitran in Khanpur: 3,450 + 1,150').to.eq(4600)
    })
    report({ cityId: ctx.khanpur, supplierId: 0, groupBy: 'SUPPLIER' }).then((b) => {
      expect(sum(b.collection, 'totalAmount'), 'Cefim — never bought, so no supplier').to.eq(1150)
      expect(b.object[0].labelCode).to.eq('NO_SUPPLIER')
    })
    report({ cityId: ctx.khanpur, bonus: 'WITH', groupBy: 'REP' }).then((b) => {
      expect(b.collection.map((r) => r.invoiceNo)).to.deep.eq([ctx.s1])
      expect(Number(b.object[0].bonusQuantity)).to.eq(3)
      expect(Number(b.object[0].bonusValue)).to.eq(300)
    })
  })

  it('R5 — the CSV carries the same rows and the new columns, grouped or not', () => {
    report({ cityId: ctx.khanpur }).then((b) => {
      cy.request(`/saleReport.csv?rp=0&cityId=${ctx.khanpur}`).then((csv) => {
        const lines = String(csv.body).trim().split(/\r?\n/)
        expect(lines[0]).to.eq('Date,Invoice,Customer,Channel,SKU,Item,Category,Qty,Rate,Total,Tax,'
          + 'Rep,Source,Keyed by,Area,City,Supplier,Bonus qty,Bonus value')
        expect(lines.length - 1, 'one CSV row per JSON row').to.eq(b.collection.length)
        expect(lines.find((l) => l.includes(ctx.s1)), 'S1 names its rep, source, area and the free goods')
          .to.contain(`SRA4 Irfan ${RUN},REP_PAPER`).and.contain(`SRA4 Zahir Pir ${RUN},SRA4 Khanpur ${RUN},SRA4 Davis ${RUN},3,300.00`)
      })
    })
    cy.request(`/saleReport.csv?rp=0&cityId=${ctx.khanpur}&groupBy=SOURCE`).then((csv) => {
      const lines = String(csv.body).trim().split(/\r?\n/)
      expect(lines[0]).to.eq('SOURCE,Invoices,Qty,Total,Tax,Gross,Bonus qty,Bonus value')
      expect(lines.find((l) => l.startsWith('REP_PAPER,')), 'a code, stable for a spreadsheet').to.contain(',3,300.00')
    })
  })

  it('R6 — a member on "Sees: Own" sees only the sales credited to their rep (positive control: the owner sees all three)', () => {
    report({ cityId: ctx.khanpur }).then((b) => expect(sum(b.collection, 'totalAmount'), 'the owner: S1 + S2').to.eq(5750))
    // business-service's own rule, reached with the booker's token — no monolith rule on the way.
    cy.then(() => cy.request({ method: 'POST', url: 'http://localhost:8765/api/business/loadSR', form: true, failOnStatusCode: false,
      headers: { Authorization: 'Bearer ' + ctx.booker.token }, body: { rp: 0, cityId: ctx.khanpur } })).then((r) => {
      expect(r.status, JSON.stringify(r.body).slice(0, 200)).to.eq(200)
      const rows = list(r.body)
      expect(rows.length, 'the booker has a row of their own (positive control)').to.be.greaterThan(0)
      expect([...new Set(rows.map((x) => x.invoiceNo))], 'only the order credited to their rep').to.deep.eq([ctx.s2])
    })
  })

  it('R7 — the screen: the new filters and columns, and a code shown in words', () => {
    cy.viewport(1400, 900)
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#rfRep').should('exist')
    cy.get('#rfSource option[value="COUNTER"]').should('have.text', 'Counter sale')
    cy.get('#rfBonus option[value="WITH"]').should('exist')
    cy.get('#tableSellReport thead th').should('have.length', 18)
    cy.get('#rfGroupBy').select('REP', { force: true })
    cy.contains('#srGroups td', 'No rep', { timeout: 30000 }).should('exist')
  })
})
