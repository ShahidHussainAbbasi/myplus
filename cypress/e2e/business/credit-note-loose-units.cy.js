/**
 * CN-1 — the printed CREDIT NOTE says what the customer bought, and still prints after a full return.
 *
 * Design: microservices/docs/slices/cn-1-credit-note-loose-snapshot.md.
 *
 * ⚠ WRITTEN, NOT RUN. Authored under the 2026-09-17 build freeze: the production survey is running and nothing
 * may be rebuilt under it. V65 is not applied to the dev database yet, so cases 1-3 CANNOT pass until
 * business-service is rebuilt — that is expected, and running this before the rebuild proves nothing.
 *
 * TWO DEFECTS, ONE CAUSE — sale_return stored only the SHELF figure:
 *   1. a loose return printed "0.075 × 311.60" for three tablets (live in dev: CRN-000038, quantity 0.075);
 *   2. ⚠ EVERY fully-returned note printed with NO RATE, pack sales included, because creditNote resolves the
 *      rate from the Sell row and a full return DELETES that row (sellService.deleteById).
 *
 * ⚠ NEVER owner.business@ — the account the user works in. This spec sells, returns and prints; demo.business@
 * is the tenant the finance and e2e specs already use for exactly this kind of money-shaped fixture.
 *
 * Run headed (after the rebuild):
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/credit-note-loose-units.cy.js
 */

const PACK = 40
const PACK_RATE = 311.60
const PIECES_SOLD = 10
const PIECES_RETURNED = 3

/*
 * The fixtures below mirror sell-loose.cy.js rather than inventing commands. There is no cy.sellLoose or
 * cy.sellPack in this codebase — a spec that calls helpers which do not exist fails on its own scaffolding and
 * tells you nothing about the product.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

/** A pack-ruled product that may be split, with the unit NOUN the printed note will show. */
const packProduct = (name) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name, sellingPrice: PACK_RATE, unit: 'pack', packSize: PACK,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose: true, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored').to.exist
      expect(p.packSize, 'pack size came back — U1 must be deployed for this gate to mean anything').to.eq(PACK)
      return p
    })
  })

const sell = (lines, total) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customer: { name: `Walkin_${uniq()}`, contact: '03009999999' },
      sales: lines, tenders: [{ method: 'CASH', amount: total }],
      paidAmount: total, grandTotal: total, idempotencyKey: `cy-cn1-${uniq()}`,
    },
  }).then((r) => {
    expect(r.body.status, `addSell: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
    return r.body.object          // the invoice number
  })

/** The stored line id for an invoice — /getUserSell is FLAT: each row IS a line, carrying its customerHistory. */
const lineIdOf = (invoiceNo) =>
  cy.request('/getUserSell').then((r) => {
    const rows = list(r.body).filter((row) => (row.customerHistory || {}).invoiceNo === invoiceNo)
    expect(rows.length, `invoice ${invoiceNo} has lines`).to.be.greaterThan(0)
    return rows[0].sellId || rows[0].sell_id || rows[0].id
  })

/** The credit note exactly as the printer receives it — the same endpoint printReturnDocument reads. */
const noteOf = (creditNoteNo) =>
  cy.request({ url: `/creditNote?no=${encodeURIComponent(creditNoteNo)}`, failOnStatusCode: false }).then((r) => {
    expect(r.status, 'creditNote HTTP').to.eq(200)
    expect(r.body && r.body.status, `creditNote: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
    const doc = r.body.object
    expect(doc && doc.lines, 'the note has lines').to.be.an('array').and.have.length.greaterThan(0)
    return doc.lines[0]
  })

describe('CN-1 — a credit note prints in the customer\'s units', () => {
  const run = String(Date.now()).slice(-7)
  let productId
  let looseSellId
  let packSellId

  before(() => {
    cy.loginAsBusiness()
    packProduct(`CN1_${run}`).then((p) => { productId = p.id || p.productId })
    cy.then(() => cy.request({
      method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
      body: { productId, quantity: 5, batchNo: `CN1B_${run}`, purchasePrice: 200 },
    })).then((r) => expect(r.status, 'seed stock').to.eq(200))
  })

  beforeEach(() => cy.loginAsBusiness())

  it('⭐⭐ 1 — three tablets returned print as 3 at the per-piece rate, not 0.075 of a box', () => {
    /*
     * THE CASE. The figures are chosen so the two views cannot be confused: 10 tablets of a 40-box is 0.25 on
     * the shelf, and returning 3 is 0.075 — the exact number on CRN-000038 in dev today.
     */
    sell([{ productId, soldUnit: 'LOOSE', soldQuantity: PIECES_SOLD }], PACK_RATE / PACK * PIECES_SOLD)
      .then((invoiceNo) => lineIdOf(invoiceNo))
      .then((id) => { looseSellId = id })

    cy.then(() => cy.request({
      method: 'POST', url: '/saleReturn', form: true,
      body: { sellId: looseSellId, quantity: PIECES_RETURNED, returnUnit: 'LOOSE', reason: 'CN-1 gate' },
    })).then((r) => {
      expect(r.body.status, `saleReturn: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
      return cy.wrap(r.body.object)          // the credit note number
    }).then((creditNoteNo) => {
      noteOf(creditNoteNo).then((line) => {
        expect(line.soldUnit, 'the note records that the line was sold loose').to.eq('LOOSE')
        expect(Number(line.soldQuantity), 'three TABLETS, not 0.075 of a box').to.eq(PIECES_RETURNED)
        // ⚠ The per-piece rate is READ from the snapshot, never derived: a tax inspector reconciles
        // quantity × rate against the line total, and "3 tablets" beside 311.60 would not reconcile.
        expect(Number(line.soldRate), 'at the price per tablet').to.be.closeTo(PACK_RATE / PACK, 0.01)
        expect(Number(line.packSize), 'and the pack size that applied at the sale').to.eq(PACK)
        // The shelf view is still there for stock and money — this slice adds a view, it does not replace one.
        expect(Number(line.quantity), 'the shelf figure is unchanged').to.be.closeTo(0.075, 0.0005)
      })
    })
  })

  it('⭐⭐ 2 — a FULLY returned note still prints a rate (the defect that hits pack sales too)', () => {
    /*
     * A full return deletes the Sell row, and the reader resolved the rate from it — so before V65 this note
     * printed with no rate at all. Deliberately a PACK sale: the defect was never about loose selling, and a
     * gate that only proved the loose case would leave the commoner one untested.
     */
    sell([{ productId, quantity: 1, sellRate: PACK_RATE, totalAmount: PACK_RATE }], PACK_RATE)
      .then((invoiceNo) => lineIdOf(invoiceNo))
      .then((id) => { packSellId = id })

    cy.then(() => cy.request({
      method: 'POST', url: '/saleReturn', form: true,
      body: { sellId: packSellId, quantity: 1, reason: 'CN-1 gate — full return' },
    })).then((r) => {
      expect(r.body.status, `saleReturn: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
      return cy.wrap(r.body.object)
    }).then((creditNoteNo) => {
      noteOf(creditNoteNo).then((line) => {
        expect(line.rate, 'a fully returned note must still carry its rate').to.not.be.null
        expect(Number(line.rate), 'the pack rate as it was at the sale').to.be.closeTo(PACK_RATE, 0.01)
        expect(line.soldUnit, 'a pack line carries no loose view').to.be.oneOf([null, undefined, 'PACK'])
      })
    })
  })

  it('⭐⭐ 2b — a fully returned note still NAMES ITS CUSTOMER (CN-1b)', () => {
    /*
     * The other half of case 2's defect, and it was missed the first time.
     *
     * A full return deletes the Sell line, and BOTH the rate and the party were resolved from that line. V65
     * snapshotted the rate; the party was left resolving to nothing, so a fully returned note printed with no
     * customer at all ("the note names a party: .empty was passed non-string primitive null") and the register
     * showed an em-dash where a name belongs. Two other specs — return-documents and returns-list — failed on
     * exactly that, made likelier by THIS spec's own full returns sitting at the top of the register.
     *
     * CN-1b resolves the party from the INVOICE HEADER, which outlives its lines. Resolved rather than
     * snapshotted: the invoice number is already on the return row, and the header already holds the customer.
     */
    sell([{ productId, quantity: 1, sellRate: PACK_RATE, totalAmount: PACK_RATE }], PACK_RATE)
      .then((invoiceNo) => lineIdOf(invoiceNo))
      .then((id) => cy.request({
        method: 'POST', url: '/saleReturn', form: true,
        body: { sellId: id, quantity: 1, reason: 'CN-1b gate — full return, party must survive' },
      }))
      .then((r) => {
        expect(r.body.status, `saleReturn: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
        return cy.wrap(r.body.object)
      })
      .then((creditNoteNo) => {
        cy.request({ url: `/creditNote?no=${encodeURIComponent(creditNoteNo)}`, failOnStatusCode: false })
          .then((res) => {
            const doc = res.body.object
            expect(doc.partyName, 'the printed note names its customer even though the line is gone')
              .to.be.a('string').and.not.be.empty
          })
        // ...and the register agrees, since a shop finds the note there before printing it.
        cy.request({ url: '/getSaleReturns', failOnStatusCode: false }).then((res) => {
          const all = res.body.collection || res.body.object || []
          const row = all.find((d) => d.documentNo === creditNoteNo)
          expect(row, 'the note is in the register').to.exist
          expect(row.partyName, 'and the register names the party, not an em-dash')
            .to.be.a('string').and.not.be.empty
        })
      })
  })

  it('⭐⭐ 3 — the REGISTER and the printed note agree (one mapper, or they drift)', () => {
    /*
     * toCreditNoteDto serves both the single-note read and the register. They were changed together on
     * purpose; this case is what stops a later edit fixing one and leaving the other behind.
     */
    cy.request({ url: '/getSaleReturns', failOnStatusCode: false }).then((r) => {
      expect(r.body.status, 'getSaleReturns').to.eq('SUCCESS')
      /*
       * ⚠ Number() on BOTH sides, not ===.
       *
       * The id crosses a JSON boundary more than once (product list → sell payload → register DTO) and can come
       * back as a number or a string. A strict === then matches nothing while the sale and the return still work
       * perfectly, because the server coerces — so this case failed on its own filter and reported it as "the
       * register lists no notes", which reads like a register defect and is not one.
       */
      const same = (a, b) => Number(a) === Number(b)
      /*
       * ⚠ `collection`, NOT `object`. getSaleReturns answers with GenericResponse("SUCCESS", msg, List) and
       * Java picks the MOST SPECIFIC overload — Collection<?> over Object — so a LIST payload lands in
       * `collection` while `object` stays null. Reading `object` yielded an empty register and looked exactly
       * like a product defect: "the register lists none of this product's notes", against a register that was
       * working perfectly. This is why the other specs carry a list() helper that tries both.
       */
      const all = r.body.collection || r.body.object || r.body.data || []
      const rows = all.filter((d) => (d.lines || []).some((l) => same(l.productId, productId)))
      expect(rows.length,
        `register holds ${all.length} notes; productId=${productId}; ` +
        `first note lines: ${JSON.stringify(((all[0] || {}).lines || []).map((l) => l.productId))}`)
        .to.be.greaterThan(0)

      const looseRow = rows.find((d) => (d.lines || []).some((l) => l.soldUnit === 'LOOSE'))
      expect(looseRow, 'including the loose one').to.exist
      const line = looseRow.lines.find((l) => l.soldUnit === 'LOOSE')
      expect(Number(line.soldQuantity), 'with the same pieces the printed note shows').to.eq(PIECES_RETURNED)

      // And the register's own copy of that note, read singly, matches field for field.
      noteOf(looseRow.documentNo).then((single) => {
        expect(Number(single.soldQuantity)).to.eq(Number(line.soldQuantity))
        expect(Number(single.soldRate)).to.be.closeTo(Number(line.soldRate), 0.001)
        expect(Number(single.rate)).to.be.closeTo(Number(line.rate), 0.001)
      })
    })
  })

  it('⭐ 4 — a note written BEFORE V65 still prints, with the shelf figure (no blanks, no invention)', function () {
    /*
     * Legacy rows have no snapshot and their sell line may be gone. They must fall back to the old behaviour
     * rather than printing an empty quantity — an old note that suddenly renders blank is worse than one that
     * renders in shelf units, because the shop cannot tell whether the data or the printer failed.
     *
     * CRN-000038 is a real pre-V65 loose return in dev (quantity 0.075). It is READ ONLY here.
     */
    cy.request({ url: '/creditNote?no=CRN-000038', failOnStatusCode: false }).then(function (r) {
      if (r.status !== 200 || !r.body || r.body.status !== 'SUCCESS') {
        /*
         * ⚠ PENDING, not a silent pass. A fresh database has no pre-V65 note, and this case genuinely cannot
         * run there — but a case that quietly goes green on a missing fixture is how a gate comes to prove
         * nothing. this.skip() reports it as PENDING, which is visible in the run summary.
         */
        cy.log('⚠ CRN-000038 absent — legacy fallback NOT exercised here')
        this.skip()
        return
      }
      const line = (r.body.object.lines || [])[0]
      expect(line, 'the legacy note still has a line').to.exist
      expect(Number(line.quantity), 'and still prints its shelf quantity').to.be.greaterThan(0)
      expect(line.soldQuantity, 'with no invented loose view').to.be.oneOf([null, undefined])
    })
  })
})
