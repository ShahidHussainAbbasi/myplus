/**
 * Slice I2 — CSV template + import for Product.
 * Design: microservices/docs/slices/import-I2-product-csv.md
 *
 * WHAT IS GENUINELY NEW HERE, and therefore what these cases are really for. I1 already gated the engine —
 * dry runs write nothing, one bad row refuses the file, a replay is inert. Repeating those against Product
 * proves the SPEC is wired, but the slice's real risk is elsewhere:
 *
 *   1. The registry now spans TWO SERVICES. Product's spec lives in catalog-service, Customer's in
 *      business-service, and the monolith routes per entity and merges the listings. If that routing is
 *      wrong the Product buttons never appear, or appear and post into a void.
 *   2. The two services answer in DIFFERENT ENVELOPES (GenericResponse vs ApiResponse). The proxy
 *      normalises catalog's into the one the browser speaks. A mistake there looks exactly like a working
 *      import that silently reports nothing.
 *
 * So the cases below assert both entities are reachable through ONE contract, and — as in I1 — every
 * outcome is measured as a COUNT OR A VALUE IN THE MASTER, never as "the response said SUCCESS".
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/product-import.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

/** The report travels in `object` once the proxy has normalised catalog's `data`. */
const report = (body) => (body && body.object) || null

const HEADERS =
  'sku,name,description,categoryName,unit,manufacturer,sellingPrice,taxRate,barcode,rxRequired,controlledSubstance,'
  + 'packSize,looseUnit,looseUnitPlural,allowLoose'

/** One CSV line with everything after `name` blank. */
const row = (sku, name, rest = ',,,,,,,,,') => `${sku},${name}${rest}`

const csvOf = (...rows) => [HEADERS, ...rows].join('\n') + '\n'

const products = () => cy.request('/getUserProduct?q=-1&includeInactive=true').then((r) => list(r.body))

const countProducts = () => products().then((ps) => ps.length)

const validate = (csv) =>
  cy.request({ method: 'POST', url: '/import/product/validate', body: { csv }, failOnStatusCode: false })

const commit = (csv) =>
  cy.request({ method: 'POST', url: '/import/product/commit', body: { csv }, failOnStatusCode: false })

describe('I2 — Product CSV import', () => {
  beforeEach(() => {
    // testIsolation clears the session between tests, so login belongs in beforeEach.
    // Owner rather than demo.business: the import is ADMIN_PRIVILEGE-gated, and demo carries DEMO_ROLE=SUPER,
    // which would make an authority assertion prove nothing.
    cy.loginAsOwner()
  })

  // ── the registry now spans two services ───────────────────────────────────────────────────────────────

  it('BOTH product and customer are importable through one merged listing', () => {
    cy.request('/import/entities').then((r) => {
      const names = list(r.body).map((e) => e.entity)

      // The case that proves the cross-service routing: product comes from catalog-service, customer from
      // business-service, and the browser sees one list. Either half missing means a grid loses its buttons.
      expect(names, 'catalog-service half').to.include('product')
      expect(names, 'business-service half').to.include('customer')

      // Still absent, and still on purpose: these are numbered documents whose creation moves stock and
      // posts to the ledger. A row inserted behind the sale path is a row the books disagree with.
      expect(names).to.not.include('sell')
      expect(names).to.not.include('purchase')
    })
  })

  // ── template, and the round-trip contract ─────────────────────────────────────────────────────────────

  it('the template header is exactly the columns the parser accepts', () => {
    cy.request('/import/product/template.csv').then((r) => {
      expect(r.status).to.eq(200)
      expect(r.headers['content-disposition'], 'served as a download').to.contain('attachment')

      const header = String(r.body).split(/\r?\n/)[0].trim()
      // Exact equality: a "contains sku" check would pass while the order or the optional set drifted.
      expect(header).to.eq(HEADERS)
    })
  })

  it('the template offers no stamped-rate column — those are facts, not attributes', () => {
    cy.request('/import/product/template.csv').then((r) => {
      const header = String(r.body).split(/\r?\n/)[0]
      // Stamped by the purchase/sale flows (slice 107). Importing them is I1's dueAmount mistake again.
      expect(header).to.not.contain('lastPurchaseRate')
      expect(header).to.not.contain('lastSaleRate')
      expect(header).to.not.contain('isActive')
    })
  })

  // ── the dry run writes nothing ────────────────────────────────────────────────────────────────────────

  it('a dry run reports what it WOULD create and creates nothing', () => {
    const run = uniq()
    const csv = csvOf(row(`SKU-A${run}`, `Dry A ${run}`), row(`SKU-B${run}`, `Dry B ${run}`))

    countProducts().then((before) => {
      validate(csv).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const rep = report(r.body)
        expect(rep, 'the report survived the envelope translation').to.not.be.null
        expect(rep.toCreate).to.eq(2)
        expect(rep.committed).to.eq(false)

        countProducts().then((after) => expect(after, 'a dry run must write nothing').to.eq(before))
      })
    })
  })

  // ── the happy path ────────────────────────────────────────────────────────────────────────────────────

  it('a clean file imports every row, with the values from the file', () => {
    const run = uniq()
    const sku = `SKU-V${run}`
    const name = `Imported Widget ${run}`
    const csv = csvOf(
      `${sku},${name},Box of 20,Analgesics ${run},Box,Acme,120.50,17,,false,false`,
      row(`SKU-W${run}`, `Imported Gadget ${run}`))

    countProducts().then((before) => {
      commit(csv).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        expect(report(r.body).toCreate).to.eq(2)

        countProducts().then((after) =>
          expect(after, 'exactly the file, no more and no fewer').to.eq(before + 2))

        // Values, not just a count — a row that arrives blank is not an import.
        products().then((ps) => {
          const p = ps.find((x) => x.sku === sku)
          expect(p, 'the imported product is readable').to.exist
          expect(p.name).to.eq(name)
          expect(Number(p.sellingPrice)).to.eq(120.5)
          expect(p.isActive, 'sellable the moment it lands').to.not.eq(false)
        })
      })
    })
  })

  // ── ⭐ THE CASE THAT CARRIES THE SLICE ─────────────────────────────────────────────────────────────────

  it('one bad row refuses the WHOLE file — the good rows are NOT written', () => {
    const run = uniq()
    // Two good rows around one missing its required NAME. (sku became optional 2026-08-20; name is now
    // the field carrying both the empty check and the duplicate check.)
    const csv = csvOf(
      row(`SKU-G1${run}`, `Good One ${run}`),
      row(`SKU-BAD${run}`, ''),
      row(`SKU-G2${run}`, `Good Two ${run}`))

    countProducts().then((before) => {
      commit(csv).then((r) => {
        expect(r.body.status, 'refused').to.not.eq('SUCCESS')

        // POSITIVE CONTROL — added after run 1, where this case PASSED while catalog-service was entirely
        // unreachable. "Not SUCCESS" and "count unchanged" are both true of a total outage, so on their own
        // they prove nothing about the refusal. Requiring the report, and requiring it to name exactly one
        // refused row, means the file must actually have been READ and JUDGED.
        const rep = report(r.body)
        expect(rep, 'the file was actually parsed, not merely unreachable').to.not.be.null
        expect(rep.refused, 'exactly the one bad row was refused').to.eq(1)

        // THE assertion. "The response said FAILED" passes under a partial commit too — only the count
        // distinguishes all-or-nothing from most-of-it.
        countProducts().then((after) =>
          expect(after, 'not even the two valid rows may be written').to.eq(before))

        products().then((ps) => {
          expect(ps.find((x) => x.sku === `SKU-G1${run}`), 'valid row 1 not created').to.be.undefined
          expect(ps.find((x) => x.sku === `SKU-G2${run}`), 'valid row 3 not created').to.be.undefined
        })
      })
    })
  })

  it('the refusal names the row number and the reason', () => {
    const run = uniq()
    const csv = csvOf(row(`SKU-F${run}`, `Fine ${run}`), row(`SKU-BR${run}`, ''))

    validate(csv).then((r) => {
      const bad = report(r.body).rows.find((x) => x.status === 'ERROR')
      expect(bad, 'the bad row is reported').to.exist
      // Header is line 1, so the second data row is line 3 — the number in the operator's spreadsheet.
      expect(bad.rowNumber).to.eq(3)
      expect(bad.message).to.contain('name')
    })
  })

  // ── create-only ───────────────────────────────────────────────────────────────────────────────────────

  it('re-importing the same file creates nothing and reports every row as already present', () => {
    const run = uniq()
    const csv = csvOf(row(`SKU-R1${run}`, `Repeat A ${run}`), row(`SKU-R2${run}`, `Repeat B ${run}`))

    commit(csv).then((first) => {
      expect(report(first.body).toCreate).to.eq(2)

      countProducts().then((afterFirst) => {
        commit(csv).then((second) => {
          const rep = report(second.body)
          expect(rep.toCreate, 'nothing new').to.eq(0)
          expect(rep.skipped, 'both already exist').to.eq(2)
          expect(rep.refused, 'already existing is not a failure').to.eq(0)

          countProducts().then((afterSecond) =>
            expect(afterSecond, 'create-only: a replayed file is inert').to.eq(afterFirst))
        })
      })
    })
  })

  it('sku is OPTIONAL — a product with no code still imports', () => {
    const run = uniq()
    const name = `No Sku Product ${run}`

    countProducts().then((before) => {
      commit(csvOf(row('', name))).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        countProducts().then((after) =>
          expect(after, 'the master allows a product with no SKU, so the import must too').to.eq(before + 1))
      })
    })
  })

  it('a repeated NAME is skipped — name carries the duplicate check now that sku is optional', () => {
    const run = uniq()
    const name = `Shared Name ${run}`
    const csv = csvOf(row(`SKU-N1${run}`, name), row(`SKU-N2${run}`, name))

    countProducts().then((before) => {
      commit(csv).then((r) => {
        const rep = report(r.body)
        expect(rep.toCreate).to.eq(1)
        expect(rep.skipped, 'the second is reported, not silently created').to.eq(1)
        countProducts().then((after) => expect(after).to.eq(before + 1))
      })
    })
  })

  it('re-importing a file of SKU-less products creates nothing the second time', () => {
    // The property the required-SKU rule used to protect, now carried by name: without ANY key these rows
    // would be re-created on every upload and the catalogue would silently double.
    const run = uniq()
    const csv = csvOf(row('', `Keyless A ${run}`), row('', `Keyless B ${run}`))

    commit(csv).then(() => {
      countProducts().then((afterFirst) => {
        commit(csv).then((second) => {
          expect(report(second.body).skipped, 'both recognised by name').to.eq(2)
          countProducts().then((afterSecond) =>
            expect(afterSecond, 'a replayed file is inert even with no SKUs').to.eq(afterFirst))
        })
      })
    })
  })

  // ── refusals ──────────────────────────────────────────────────────────────────────────────────────────

  it('a file carrying a stamped rate column is refused whole', () => {
    const run = uniq()
    const csv = `sku,name,lastPurchaseRate\nSKU-X${run},Rate Smuggler ${run},50\n`

    countProducts().then((before) => {
      commit(csv).then((r) => {
        expect(r.body.status).to.not.eq('SUCCESS')
        expect(report(r.body) && report(r.body).fileError, JSON.stringify(r.body))
          .to.contain('lastPurchaseRate')
        countProducts().then((after) => expect(after).to.eq(before))
      })
    })
  })

  it('the deleted migration endpoint is gone', () => {
    // POST /api/catalog/products/import was ungated, uncapped and had zero callers; it repaired bad rows
    // instead of refusing them. Its purpose was discharged when the item→product migration completed.
    //
    // TWO THINGS THIS CASE HAD TO LEARN (run 1 and run 2 both got it wrong):
    //
    // 1. It must be AUTHENTICATED. Unauthenticated, the gateway's JWT filter answers 401 before it ever
    //    resolves a route — so a deleted path and a live one are indistinguishable, and the assertion could
    //    never have meant anything.
    //
    // 2. It must not expect 404. catalog-service answers 500 for ANY unmapped path (its GlobalExceptionHandler
    //    swallows the no-handler case into a generic error), which is a pre-existing platform behaviour and
    //    not something this slice introduced.
    //
    // So the honest assertion compares against a REFERENCE rather than a magic number: the deleted route must
    // behave exactly like a route that never existed, and differently from a live one. That also survives the
    // platform later fixing its 404 handling — both sides would move together.
    cy.request({
      method: 'POST', url: 'http://localhost:8765/api/auth/login',
      headers: { 'Content-Type': 'application/json' },
      body: { email: 'owner.business@myplus.com', password: 'Demo@2025!' },
      failOnStatusCode: false,
    }).then((login) => {
      expect(login.status, 'gateway login').to.eq(200)
      const headers = { Authorization: `Bearer ${login.body.data.accessToken}` }

      // POSITIVE CONTROL: catalog-service is live and routable, so what follows means something.
      cy.request({
        method: 'GET', url: 'http://localhost:8765/api/catalog/products/count',
        headers, failOnStatusCode: false,
      }).then((live) => {
        expect(live.status, 'catalog-service is up — a live route answers 200').to.eq(200)

        // REFERENCE: a path that has never existed, to learn what "unmapped" looks like here.
        cy.request({
          method: 'GET', url: 'http://localhost:8765/api/catalog/definitely-not-a-route',
          headers, failOnStatusCode: false,
        }).then((absent) => {
          cy.request({
            method: 'POST', url: 'http://localhost:8765/api/catalog/products/import',
            headers, body: [], failOnStatusCode: false,
          }).then((deleted) => {
            expect(deleted.status, 'the deleted route answers exactly as a never-existed one does')
              .to.eq(absent.status)
            expect(deleted.status, 'and is certainly not still serving').to.not.eq(200)
          })
        })
      })
    })
  })

  // ── authority ─────────────────────────────────────────────────────────────────────────────────────────

  it('a non-admin is refused by the SERVER, not by a hidden button', () => {
    const run = uniq()
    cy.loginAsTier('user', 'business')

    countProducts().then((before) => {
      commit(csvOf(row(`SKU-S${run}`, `Sneaky ${run}`))).then((r) => {
        expect(r.body.status, 'the write is refused').to.not.eq('SUCCESS')
        countProducts().then((after) => expect(after, 'nothing was written').to.eq(before))
      })
    })
  })

  // ── the screen ────────────────────────────────────────────────────────────────────────────────────────

  it('the Product grid offers both buttons, and the Vendor grid offers neither', () => {
    // POSITIVE CONTROL FIRST, so the absence below is evidence rather than a screen that drew no toolbar.
    cy.openSection('CustomerDiv')          // Customer proves the mechanism is live at all
    cy.get('.btn-import-template', { timeout: 10000 }).should('exist')

    // Product is not on #registrationType — showProducts() is the screen's own entry point, and it sets
    // tableV='Product' before calling the SAME loadDataTable every other grid uses. So the buttons arrive
    // with no UI change of I2's own. (This is how product-crud.cy.js reaches the screen too.)
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showProducts())
    cy.get('#ProductDiv').should('be.visible')
    cy.waitForAppReady()
    cy.get('.btn-import-template', { timeout: 10000 }).should('exist')
    cy.get('.btn-import-csv').should('exist')

    // NEGATIVE CONTROL: Vendor runs the SAME loadDataTable with the SAME buttons array, so the only variable
    // is whether a server has an ImportSpec for it. Deliberately not the till, which builds no grid at all.
    cy.openSection('VenderDiv')
    cy.get('.btn-import-template').should('not.exist')
    cy.get('.btn-import-csv').should('not.exist')
  })

  // ── U9: the pack rules are importable ────────────────────────────────────────────────────────────────

  it('⭐ a pack-rule row creates a product the till can split', () => {
    /*
     * The reason this slice exists: a pharmacy switching loose selling on must say what a pack holds for
     * every medicine it splits. By hand, on 1,200 products, that is why a shop does not adopt the feature.
     */
    const name = 'PackImp_' + uniq()
    const csv = csvOf(`,${name},,,pack,,120,,,,,10,tablet,tablets,true`)

    commit(csv).then((r) => {
      expect(report(r.body).rows.filter((x) => x.status === 'ERROR'), JSON.stringify(r.body).slice(0, 250))
        .to.have.length(0)
    })
    products().then((ps) => {
      const p = ps.find((x) => x.name === name)
      expect(p, 'the product landed').to.exist
      expect(p.packSize, 'a pack holds ten').to.eq(10)
      expect(p.looseUnit).to.eq('tablet')
      expect(p.looseUnitPlural, '"5 tablet" is wrong in every language here').to.eq('tablets')
      expect(p.allowLoose, 'and it may be split').to.eq(true)
    })
  })

  it('⭐ allowLoose without a pack size is REFUSED, with the reason', () => {
    // A contradiction the product form cannot produce — it hides the loose fields until a pack size above 1
    // is entered. An import must not be a way around that, or the till refuses to split products the
    // catalogue says are splittable and nothing explains why.
    const name = 'BadLoose_' + uniq()
    const csv = csvOf(`,${name},,,pack,,120,,,,,,tablet,tablets,true`)

    validate(csv).then((r) => {
      const bad = report(r.body).rows.find((x) => x.status === 'ERROR')
      expect(bad, 'the row is refused: ' + JSON.stringify(r.body).slice(0, 250)).to.exist
      expect(bad.message).to.match(/allowLoose is true but packSize is missing/i)
    })
  })

  it('a pack size of 1 is refused for the same reason', () => {
    const name = 'PackOne_' + uniq()
    const csv = csvOf(`,${name},,,pack,,120,,,,,1,tablet,tablets,true`)

    validate(csv).then((r) => {
      const bad = report(r.body).rows.find((x) => x.status === 'ERROR')
      expect(bad, 'a pack of one is not divisible').to.exist
      expect(bad.message).to.match(/more than one/i)
    })
  })

  it("⭐ a file WITHOUT the pack columns does not strip a product's pack rules", () => {
    /*
     * THE PROPERTY THAT PROTECTS EXISTING DATA. A blank packSize means "not supplied", never "make this
     * indivisible" — the same rule the product form follows. Without it, re-importing last year's file over
     * a configured catalogue would quietly un-split every medicine in it, and the failure would surface at
     * the counter as "this product is not sold by the piece".
     */
    const name = 'Keep_' + uniq()

    // 1) create it WITH pack rules
    commit(csvOf(`,${name},,,pack,,120,,,,,10,tablet,tablets,true`))
    products().then((ps) => {
      const p = ps.find((x) => x.name === name)
      expect(p, 'created with pack rules').to.exist
      expect(p.packSize).to.eq(10)

      // 2) now re-import the SAME product from an old-style file that has no pack columns at all
      const oldStyleHeader = 'sku,name,description,categoryName,unit,manufacturer,sellingPrice,taxRate,'
        + 'barcode,rxRequired,controlledSubstance'
      const oldCsv = [oldStyleHeader, `,${name},,,pack,,130,,,,`].join('\n') + '\n'
      commit(oldCsv).then((r) => cy.log('old-style re-import: ' + JSON.stringify(r.body).slice(0, 200)))

      products().then((after) => {
        const q = after.find((x) => x.name === name)
        expect(q, 'still there').to.exist
        expect(q.packSize, 'the pack rules survived a file that never mentioned them').to.eq(10)
        expect(q.allowLoose, 'and it can still be split').to.eq(true)
      })
    })
  })

  it('an ordinary row is completely unaffected by the new columns', () => {
    // Most of the catalogue is not divisible, and importing it must look exactly as it did before U9.
    const name = 'PlainImp_' + uniq()
    const csv = csvOf(`,${name},,,pcs,,50,,,,`)

    commit(csv).then((r) => {
      expect(report(r.body).rows.filter((x) => x.status === 'ERROR')).to.have.length(0)
    })
    products().then((ps) => {
      const p = ps.find((x) => x.name === name)
      expect(p, 'imported').to.exist
      expect(p.packSize, 'no pack size').to.be.oneOf([null, undefined])
      expect(p.allowLoose, 'and not splittable').to.eq(false)
    })
  })
})
