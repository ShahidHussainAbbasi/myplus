/**
 * SER-5 — the dashboard breakdown cards, the filtered drill-through, and the SERVER-PAGED product grid.
 *
 * <h3>What is actually at risk here, and therefore what is asserted</h3>
 * Every defect this slice could ship reports success and renders a plausible screen. So each case below
 * asserts a VALUE that would still be wrong if the feature were broken, never that a request returned 200:
 *
 *   · a card counting one population while its drill shows another (active vs. inactive products)
 *   · the uncategorised bucket being unreachable, so its card row silently returns the whole catalogue
 *   · page 2 returning page 1's rows, which looks like a working pager over a 50-row catalogue
 *   · server-side search matching only name and SKU, so a category or manufacturer term finds nothing
 *   · the condition card counting SOLD units, telling a shop it holds stock it has already sold
 *   · the paging totals arriving as nulls because the envelope's field names do not match
 *
 * ⚠ The monolith proxies answer through GenericResponse-shaped maps: a refusal is `status:"ERROR"` inside
 * an HTTP 200. Assert the ENVELOPE, never the status code.
 */

const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)

const categoryCounts = () =>
  cy.request('/getCategoryCounts').then((r) => (r.body && r.body.collection) || [])

const productPage = (params = {}) =>
  cy.request({ url: '/getProductPage', qs: params, failOnStatusCode: false }).then((r) => r.body || {})

const conditionCounts = () =>
  cy.request('/serialConditionCounts').then((r) => (r.body && r.body.collection) || [])

const unitsByCondition = (grade, params = {}) =>
  cy.request({
    url: '/serialUnitsByCondition',
    qs: Object.assign({ grade, status: 'IN_STOCK' }, params),
    failOnStatusCode: false,
  }).then((r) => r.body || {})

const countFor = (rows, grade) => {
  const hit = rows.find((r) => r.grade === grade)
  return hit ? Number(hit.count) : 0
}

/**
 * Receive one serialised unit at a given grade.
 *
 * ⚠ The wire field is `conditionGrade`. `purchaseCondition` is the <select>'s DOM **id** — the browser reads
 * that element and posts `conditionGrade` (main.js ~line 737). Posting the id name instead is silently
 * accepted: the bill saves, the unit is registered, and it lands on the DEFAULT grade. Nothing reports a
 * problem; the count simply never moves. Same class as the DTO-twin traps this codebase keeps paying for.
 *
 * `serialsSubmitted` is sent because the browser sends it — it is what lets the edit path tell "the operator
 * cleared the box" from "this client never had one".
 */
const receiveUnit = (productId, serial, grade) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: {
      productId, quantity: 1,
      purchaseRate: 100, 'stock.bpurchaseRate': 100, 'stock.bsellRate': 150,
      totalAmount: 100, netAmount: 100, paidAmount: 100,
      purchaseInvoiceNo: `SER5-${uniq()}`,
      serials: serial, serialsSubmitted: true, conditionGrade: grade,
    },
  }).then((r) => {
    expect(String(r.body && r.body.status), `receiving ${serial}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
  })

/**
 * Sell one serialised unit.
 *
 * ⚠ `/addSell` takes **JSON** with a `customer` object and a nested `sales[]` array, and the serial rides on
 * the LINE as `serials`. A flat form body posting `sellSerials` (again the DOM id) does not fail loudly —
 * it just does not sell the handset. Shape copied from serial-register.cy.js, which is green.
 */
const sellUnit = (productId, serial) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      customer: { name: `SER5 Buyer ${uniq()}`, contact: `0300${uniq()}`, paidAmount: 150, dueAmount: 0 },
      sales: [{ productId, quantity: 1, sellRate: 150, totalAmount: 150, netAmount: 150, serials: serial }],
      paidAmount: 150, dueAmount: 0, grandTotal: 150,
    },
  }).then((r) => {
    expect(String(r.body && r.body.status), `selling ${serial}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
  })

describe('SER-5 — dashboard breakdown cards and the paged product grid', () => {
  describe('Category card (POS tenant)', () => {
    const CAT = `SER5CAT_${uniq()}`
    let seeded = null

    before(() => {
      cy.loginAsOwner()
      cy.seedProduct({ name: `SER5_CATPROD_${uniq()}`, category: CAT }).then((p) => { seeded = p })
    })

    beforeEach(() => cy.loginAsOwner())   // testIsolation: re-login for authed cy.requests

    it('C1 — counts the tenant\'s own categories, and the seeded one appears with its product', () => {
      categoryCounts().then((rows) => {
        expect(rows.length, 'the tenant has categories to break down').to.be.greaterThan(0)
        const mine = rows.find((r) => r.categoryName === CAT)
        expect(mine, `the seeded category ${CAT} must be counted: ${JSON.stringify(rows.slice(0, 5))}`)
          .to.not.eq(undefined)
        expect(Number(mine.count), 'and must count the product just seeded into it').to.be.greaterThan(0)
      })
    })

    it('C2 — every count MATCHES what clicking that row actually shows', () => {
      /*
       * The defect this exists for: countByCategoryScoped filters isActive, searchScoped did not. The card
       * would have said 120 and the click shown 166, with nothing on screen to explain the difference.
       * Asserted across several categories rather than one, because a single-product category can agree by
       * coincidence.
       */
      categoryCounts().then((rows) => {
        const sample = rows.filter((r) => !r.uncategorised).slice(0, 3)
        expect(sample.length, 'need at least one real category to check').to.be.greaterThan(0)
        sample.forEach((r) => {
          productPage({ category: r.categoryId, size: 1 }).then((body) => {
            expect(body.page, `page meta for category ${r.categoryName}`).to.not.eq(undefined)
            expect(Number(body.page.totalElements),
              `card says ${r.count} for "${r.categoryName}" — the drill must show the same`)
              .to.eq(Number(r.count))
          })
        })
      })
    })

    it('C3 — the uncategorised bucket is REACHABLE and is not "no filter"', () => {
      /*
       * `categoryId IS NULL` already meant "any category", so before this slice there was no value that
       * could ask for the products with NO category. The bucket's card row would have been clickable and
       * returned the entire catalogue — a filter that silently does the opposite of its label.
       */
      categoryCounts().then((rows) => {
        const bucket = rows.find((r) => r.uncategorised)
        if (!bucket) {
          cy.log('This tenant has no uncategorised products — nothing to assert.')
          return
        }
        productPage({ uncategorised: true, size: 1 }).then((body) => {
          expect(Number(body.page.totalElements), 'the bucket returns exactly what its card counted')
            .to.eq(Number(bucket.count))
        })
        // And it must NOT be the whole catalogue, which is what a broken flag would return.
        productPage({ size: 1 }).then((all) => {
          expect(Number(all.page.totalElements), 'the unfiltered total is a different number')
            .to.be.greaterThan(Number(bucket.count))
        })
      })
    })
  })

  describe('Server-paged product grid', () => {
    beforeEach(() => cy.loginAsOwner())

    it('P1 — the page envelope is fully populated (no null totals)', () => {
      /*
       * common.web.PageResponse names its fields pageNo / pageSize / last. Reading Spring Data's own
       * "page" / "size" / "first" returns null from every one of them, and null is not an error: the grid
       * still draws and the pager simply stops knowing where it is.
       */
      productPage({ page: 0, size: 5 }).then((body) => {
        expect(body.status, JSON.stringify(body).slice(0, 200)).to.eq('SUCCESS')
        expect(body.page.page, 'page number').to.eq(0)
        expect(body.page.size, 'page size').to.eq(5)
        expect(body.page.totalElements, 'total').to.be.a('number')
        expect(body.page.totalPages, 'total pages').to.be.a('number')
        expect(body.page.first, 'first flag').to.eq(true)
        expect(body.collection.length, 'a full page of rows').to.eq(5)
      })
    })

    it('P2 — page 2 returns DIFFERENT rows from page 1', () => {
      // A pager that re-fetches page 1 looks like a working pager over a 50-row catalogue.
      productPage({ page: 0, size: 5 }).then((first) => {
        if (Number(first.page.totalElements) <= 5) {
          cy.log('Fewer than 6 products — paging cannot be exercised.')
          return
        }
        productPage({ page: 1, size: 5 }).then((second) => {
          const a = first.collection.map((r) => r.id)
          const b = second.collection.map((r) => r.id)
          expect(b.length, 'page 2 has rows').to.be.greaterThan(0)
          b.forEach((id) => expect(a, `id ${id} must not repeat from page 1`).to.not.include(id))
        })
      })
    })

    it('P3 — the row projection carries every field the grid renders', () => {
      /*
       * The projection is an ALLOW-LIST: a field not named in productRow() is silently dropped, and the
       * column renders empty rather than failing. This is the same shape as the gl_outbox defect.
       */
      productPage({ size: 1 }).then((body) => {
        const row = body.collection[0]
        expect(row, 'a row to inspect').to.not.eq(undefined)
        ;['id', 'name', 'sku', 'barcode', 'unit', 'sellingPrice', 'lastPurchaseRate', 'lastSaleRate',
          'taxRate', 'categoryName', 'manufacturer', 'packSize', 'allowLoose', 'isActive', 'userId']
          .forEach((f) => expect(row, `projection must carry ${f}`).to.have.property(f))
      })
    })

    it('P4 — search runs on the SERVER and covers category and manufacturer, not just name/SKU', () => {
      /*
       * THE defect that would have made this screen worse than the one it replaces. DataTables searches the
       * rows it holds; at 50 a page its box would search 50 products out of 1,042 and report "No matching
       * records" for a product the tenant owns. Moving search to the server is only correct if it covers
       * what the client-side box covered — the grid renders Category and Manufacturer columns.
       */
      const MAKER = `SER5MAKE${uniq()}`
      const CAT = `SER5FIND${uniq()}`
      cy.seedProduct({ name: `SER5_SEARCH_${uniq()}`, manufacturer: MAKER, category: CAT }).then((p) => {
        productPage({ q: MAKER }).then((body) => {
          expect(body.collection.map((r) => r.id), `searching the MANUFACTURER "${MAKER}" must find it`)
            .to.include(p.productId)
        })
        productPage({ q: CAT }).then((body) => {
          expect(body.collection.map((r) => r.id), `searching the CATEGORY "${CAT}" must find it`)
            .to.include(p.productId)
        })
        productPage({ q: p.sku }).then((body) => {
          expect(body.collection.map((r) => r.id), 'and SKU still works').to.include(p.productId)
        })
      })
    })

    it('P5 — a deactivated product leaves the grid unless includeInactive is asked for', () => {
      cy.seedProduct({ name: `SER5_GONE_${uniq()}` }).then((p) => {
        /*
         * ⚠ JSON with a COMMA-LIST under `checked` — the Product screen deactivates a bulk selection, so
         * there is no single-id form. A form body carrying `id` is not rejected: the proxy finds `checked`
         * null and answers `{success:false}` with HTTP 200, having deactivated nothing. The product then
         * legitimately stays in the grid and the assertion below blames the grid for it.
         */
        cy.request({
          method: 'POST', url: '/deactivateProduct', failOnStatusCode: false,
          headers: { 'Content-Type': 'application/json' },
          body: { checked: String(p.productId) },
        }).then((r) => {
          expect(r.body && r.body.success, `deactivate must really happen: ${JSON.stringify(r.body)}`).to.eq(true)
        })
        productPage({ q: p.sku }).then((body) => {
          expect(body.collection.map((r) => r.id), 'hidden by default').to.not.include(p.productId)
        })
        productPage({ q: p.sku, includeInactive: true }).then((body) => {
          expect(body.collection.map((r) => r.id), 'and visible when asked for').to.include(p.productId)
        })
      })
    })

    it('P6 — an unknown sort field falls back instead of 500ing the grid', () => {
      // The value lands in Spring Data's Pageable as a property path; an unknown one raises
      // PropertyReferenceException, which would be a 500 on a header click.
      productPage({ sort: 'DROP TABLE,desc', size: 2 }).then((body) => {
        expect(body.status, JSON.stringify(body).slice(0, 200)).to.eq('SUCCESS')
        expect(body.collection.length).to.be.greaterThan(0)
      })
    })

    it('P7 — the grid renders through the paged path and shows the filter chip when drilled into', () => {
      cy.visit('/businessDashboard')
      cy.get('#dashCategoryCard .breakdown-row', { timeout: 20000 }).should('exist')
      cy.get('#dashCategoryCard .breakdown-row').first().then(($row) => {
        const label = $row.data('label')
        cy.wrap($row).click()
        cy.get('#ProductDiv', { timeout: 15000 }).should('be.visible')
        cy.get('#productFilterBar').should('be.visible').and('contain.text', label)
        // Clearing the chip must actually widen the result set, not just repaint the bar.
        cy.get('#tableProduct_info').invoke('text').then((filtered) => {
          cy.get('#productFilterClear').click()
          cy.get('#productFilterBar').should('not.be.visible')
          cy.get('#tableProduct_info', { timeout: 15000 }).invoke('text').should('not.eq', filtered)
        })
      })
    })
  })

  describe('Condition card (mobile-shop tenant)', () => {
    let tracked = null

    before(() => {
      cy.loginAsMobileOwner()
      cy.setCapability('serialTracking', true)
      cy.setCapability('conditionGrading', true)
      cy.seedProduct({ name: `SER5_UNIT_${uniq()}` }).then((p) => {
        tracked = p.productId
        cy.request({ method: 'POST', url: '/setProductTracking', form: true,
          body: { id: tracked, requiresSerial: true } })
          .then((r) => expect(r.body && r.body.success, JSON.stringify(r.body)).to.not.eq(false))
      })
    })

    beforeEach(() => cy.loginAsMobileOwner())

    it('D1 — every grade is returned, including the ones at zero', () => {
      /*
       * A tile that appears only once the first refurbished handset exists is a feature nobody discovers:
       * the shop has to see the grade before it books one in.
       */
      conditionCounts().then((rows) => {
        const grades = rows.map((r) => r.grade)
        expect(grades, 'NEW').to.include('NEW')
        expect(grades, 'USED').to.include('USED')
        expect(grades, 'REFURBISHED').to.include('REFURBISHED')
      })
    })

    it('D2 — receiving a USED handset moves the USED count by exactly one', () => {
      const serial = `SER5U${uniq()}`
      conditionCounts().then((before) => {
        const was = countFor(before, 'USED')
        receiveUnit(tracked, serial, 'USED')
        conditionCounts().then((after) => {
          expect(countFor(after, 'USED'), 'USED +1').to.eq(was + 1)
        })
        // …and the unit is findable in the drill, with the detail the screen renders.
        unitsByCondition('USED', { size: 200 }).then((body) => {
          const hit = body.collection.find((u) => u.serialNo === serial)
          expect(hit, `the new unit must appear in the USED list: ${serial}`).to.not.eq(undefined)
          expect(hit.serialNo, 'the SERIAL is the column this screen exists for').to.eq(serial)
          expect(hit.productName, 'the product name is resolved, not left as an id').to.be.a('string')
          expect(hit.purchaseInvoiceNo, 'and the bill number, not a purchaseId').to.be.a('string')
        })
      })
    })

    it('D3 — the count is IN_STOCK, so selling the handset removes it from the card', () => {
      /*
       * The register keeps sold units for ever — that is what answers "who did we sell this to?". A card
       * counting every row would tell a shop it holds twelve used handsets when eleven are on the shelf and
       * one is in a customer's pocket.
       */
      const serial = `SER5S${uniq()}`
      receiveUnit(tracked, serial, 'USED')
      // Counted AFTER the receive, so `was` includes this unit — the assertion is about the SALE.
      conditionCounts().then((before) => {
        const was = countFor(before, 'USED')
        sellUnit(tracked, serial)
        conditionCounts().then((after) => {
          expect(countFor(after, 'USED'), 'a sold handset leaves the shelf count').to.eq(was - 1)
        })
        unitsByCondition('USED', { size: 200 }).then((body) => {
          expect(body.collection.map((u) => u.serialNo), 'and leaves the in-stock list')
            .to.not.include(serial)
        })
      })
    })

    it('D4 — the drill is paged, and an empty page still carries its totals', () => {
      unitsByCondition('USED', { page: 0, size: 1 }).then((body) => {
        expect(body.page, 'page meta is a SIBLING of collection, never riding on the rows').to.not.eq(undefined)
        expect(body.page.totalElements, 'total').to.be.a('number')
        expect(body.collection.every((u) => u._page === undefined),
          'the business-service _page workaround must not reach the browser').to.eq(true)

        const total = Number(body.page.totalElements)
        // A page well past the end: still SUCCESS with its totals intact, never a "no data" screen.
        unitsByCondition('USED', { page: total + 50, size: 1 }).then((far) => {
          expect(far.collection.length, 'no rows out there').to.eq(0)
          expect(Number(far.page.totalElements), 'but the totals are still stated').to.eq(total)
          expect(far.collection.some((u) => u && u._empty),
            'and the placeholder row is dropped').to.eq(false)
        })
      })
    })

    it('D5 — the condition card and its drill open from the dashboard in one click', () => {
      cy.visit('/businessDashboard')
      cy.get('#dashConditionCard .grade-tile', { timeout: 20000 }).should('have.length.greaterThan', 2)
      cy.get('#dashConditionCard .grade-tile[data-grade="USED"]').click()
      cy.get('#ConditionUnitsDiv', { timeout: 15000 }).should('be.visible')
      cy.get('#conditionUnitsTitle').should('not.have.text', '')
      // The serial column is populated — `u.serial` instead of `u.serialNo` would render every row blank.
      cy.get('#conditionUnitsBody tr').first().find('td').first()
        .invoke('text').should('not.eq', '')
    })
  })
})
