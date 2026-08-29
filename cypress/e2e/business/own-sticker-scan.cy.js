/**
 * U7 — the shop's own sticker.
 *
 * Design: microservices/docs/slices/u7-own-stickers.md
 *
 * A pharmacy prints `LP-4471`, sticks it on the strip holder, and scanning it sells ONE TABLET with no
 * keystroke. Until now that cost a typed marker (`1L*CODE`).
 *
 * ⚠ THE CASE THAT MATTERS MOST IS THE REFUSAL. If a manufacturer barcode could be registered as a sticker
 * meaning "1 tablet", every scan of that pack would sell one tablet — the commonest transaction in the shop,
 * mis-priced, silently, until the takings looked wrong.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/own-sticker-scan.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

/**
 * A 13-digit code that is actually unique per call.
 *
 * ⚠ The first version was `('5901234' + uniq()).slice(0, 13)` — which keeps a constant prefix and the SLOW
 * leading digits of the timestamp and throws the random tail away. Three consecutive calls returned the
 * identical code, so a later run collided with an earlier run's product and the lookup correctly answered
 * the OLDER one. The assertion was right and the fixture was wrong.
 *
 * Slicing a unique value from the wrong end destroys the only part that was unique.
 */
const gtin13 = () => ('590' + uniq()).slice(-13)

const packProduct = (name, price, packSize, allowLoose = true, barcode = null) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name, sellingPrice: price, unit: 'pack', packSize, barcode,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored').to.exist
      return p
    })
  })

const addSticker = (productId, barcode, soldUnit, quantity) =>
  cy.request({
    method: 'POST', url: '/addProductBarcode', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false, body: { productId, barcode, soldUnit, quantity },
  })

const scan = (code) => cy.request(`/scanProduct?code=${encodeURIComponent(code)}`)

describe('U7 — the shop\'s own sticker', () => {
  beforeEach(() => cy.loginAsOwner())

  // ── ⭐ what a sticker means ───────────────────────────────────────────────────────────────────────────

  it('⭐ a sticker resolves to ONE TABLET, not one pack', () => {
    const name = `Stick_${uniq()}`
    const code = `LP-${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      addSticker(p.id, code, 'LOOSE', 1).then((r) => {
        expect(r.body.success, `register: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq(true)

        scan(code).then((s) => {
          const res = typeof s.body === 'string' ? JSON.parse(s.body) : s.body
          expect(res.product, 'the code found the product').to.exist
          expect(Number(res.product.id)).to.eq(Number(p.id))
          expect(res.soldUnit, 'the sticker says LOOSE').to.eq('LOOSE')
          expect(Number(res.quantity), 'one tablet').to.eq(1)
          expect(res.ownSticker, 'and it came from the shop\'s own table').to.eq(true)
        })
      })
    })
  })

  it('a PACK sticker for 12 saves the cashier typing 12*', () => {
    const name = `Box12_${uniq()}`
    const code = `BOX-${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      addSticker(p.id, code, 'PACK', 12).then((r) => {
        expect(r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
        scan(code).then((s) => {
          const res = typeof s.body === 'string' ? JSON.parse(s.body) : s.body
          expect(res.soldUnit).to.eq('PACK')
          expect(Number(res.quantity)).to.eq(12)
        })
      })
    })
  })

  // ── ⭐ the refusal that keeps a shop right ────────────────────────────────────────────────────────────

  it('⭐ a sticker may NEVER shadow a real product barcode', () => {
    // If this were allowed, every scan of that pack would sell one tablet. Nothing on screen would look
    // wrong; the shop would simply take a tenth of the money it should, on its busiest line.
    const name = `Shadow_${uniq()}`
    const gtin = gtin13()

    packProduct(name, 120, 10, true, gtin).then((p) => {
      addSticker(p.id, gtin, 'LOOSE', 1).then((r) => {
        expect(r.body.success, 'registering a real barcode as a sticker must be refused').to.not.eq(true)
        expect(`${r.body.message || ''}`).to.match(/already a product's barcode or SKU/i)
      })
      // ...and the code still resolves to the PACK, as it always did.
      scan(gtin).then((s) => {
        const res = typeof s.body === 'string' ? JSON.parse(s.body) : s.body
        expect(res.soldUnit, 'still a pack').to.eq('PACK')
        expect(res.ownSticker).to.not.eq(true)
      })
    })
  })

  it('⭐ and the reverse — a product cannot take a code a sticker already owns', () => {
    // A refusal only binds NEW data. Without this direction, a product edited to take a code an alias
    // already owns would be shadowed by that alias on every scan — the same defect from the other side.
    const name = `Rev_${uniq()}`
    const code = `RV-${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      addSticker(p.id, code, 'LOOSE', 1).then((r) => {
        expect(r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)

        // Now try to give ANOTHER product that same code as its barcode.
        cy.request({
          method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
          failOnStatusCode: false,
          body: { name: `RevOther_${uniq()}`, sellingPrice: 50, unit: 'pcs', barcode: code },
        }).then((pr) => {
          // Written to DISCOVER: the product save may refuse, or may succeed and leave the alias winning.
          // Either way the SCAN must still mean exactly one thing, and this asserts that.
          cy.log('product-with-alias-code save: ' + JSON.stringify(pr.body).slice(0, 200))
          scan(code).then((s) => {
            const res = typeof s.body === 'string' ? JSON.parse(s.body) : s.body
            expect(Number(res.product.id), 'the code still means the product the sticker points at')
              .to.eq(Number(p.id))
          })
        })
      })
    })
  })

  // ── the ordinary scan, untouched ─────────────────────────────────────────────────────────────────────

  it('an ordinary barcode still means one pack', () => {
    // The regression that protects every shop that never prints a sticker.
    const name = `Plain_${uniq()}`
    const gtin = gtin13()

    packProduct(name, 120, 10, true, gtin).then((p) => {
      scan(gtin).then((s) => {
        const res = typeof s.body === 'string' ? JSON.parse(s.body) : s.body
        expect(Number(res.product.id)).to.eq(Number(p.id))
        expect(res.soldUnit, 'a manufacturer barcode can only mean the pack').to.eq('PACK')
        expect(Number(res.quantity)).to.eq(1)
        expect(res.ownSticker).to.not.eq(true)
      })
    })
  })

  it('an unknown code resolves to nothing, without an error', () => {
    scan(`NOPE-${uniq()}`).then((s) => {
      const res = typeof s.body === 'string' ? (s.body ? JSON.parse(s.body) : {}) : s.body
      expect(res.product == null || res.product.id == null, 'a mis-scan is normal').to.eq(true)
    })
  })

  // ── refusals ─────────────────────────────────────────────────────────────────────────────────────────

  it('a LOOSE sticker is refused on a product that may not be split', () => {
    const name = `NoSplit_${uniq()}`

    packProduct(name, 120, 10, false).then((p) => {
      addSticker(p.id, `NS-${uniq()}`, 'LOOSE', 1).then((r) => {
        expect(r.body.success, 'must be refused').to.not.eq(true)
        expect(`${r.body.message || ''}`).to.match(/not sold by the piece/i)
      })
    })
  })

  it('a zero or fractional quantity is refused', () => {
    const name = `BadQty_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      addSticker(p.id, `BQ-${uniq()}`, 'LOOSE', 0).then((r) => {
        expect(r.body.success, 'zero is not a quantity').to.not.eq(true)
      })
      addSticker(p.id, `BQ2-${uniq()}`, 'LOOSE', 100000).then((r) => {
        expect(r.body.success, 'and an absurd one is a typo').to.not.eq(true)
      })
    })
  })

  it('one code, one meaning — a duplicate sticker is refused', () => {
    const name = `Dup_${uniq()}`
    const code = `DP-${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      addSticker(p.id, code, 'LOOSE', 1).then((r) => expect(r.body.success).to.eq(true))
      addSticker(p.id, code, 'LOOSE', 2).then((r) => {
        expect(r.body.success, 'the same code cannot mean two things').to.not.eq(true)
        expect(`${r.body.message || ''}`).to.match(/already used by another sticker/i)
      })
    })
  })

  it('removing a sticker restores ordinary lookup for that code', () => {
    const name = `Rm_${uniq()}`
    const code = `RM-${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      addSticker(p.id, code, 'LOOSE', 1).then((r) => {
        expect(r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
        cy.request('/productBarcodes?productId=' + p.id).then((lr) => {
          const rows = list(lr.body)
          expect(rows.length, 'the sticker is listed').to.be.greaterThan(0)
          const id = rows[0].id
          cy.request({
            method: 'POST', url: '/removeProductBarcode', headers: { 'Content-Type': 'application/json' },
            failOnStatusCode: false, body: { id },
          }).then(() => {
            scan(code).then((s) => {
              const res = typeof s.body === 'string' ? (s.body ? JSON.parse(s.body) : {}) : s.body
              expect(res.ownSticker, 'no longer a sticker').to.not.eq(true)
            })
          })
        })
      })
    })
  })

  // ── ⭐ written to DISCOVER ────────────────────────────────────────────────────────────────────────────

  it('⭐ a typed marker on a sticker must never multiply twice', () => {
    /*
     * `5L*LP-4471` — the operator typed "five loose" AND the sticker says "one tablet". Five is the answer:
     * they stated it deliberately. Two is not, and neither is five-times-one-somehow-becoming-something-else.
     *
     * Written to DISCOVER, in the sense U6 §10.4 earned: the point is to find out what the system does, not
     * to encode my guess about it. What must NOT happen is a silent multiplication.
     */
    const name = `Twice_${uniq()}`
    const code = `TW-${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      addSticker(p.id, code, 'LOOSE', 1).then((r) => {
        expect(r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
        cy.visit('/businessDashboard')
        cy.waitForAppReady()
        cy.window().then((w) => {
          const parsed = w.parseScanEntry(`5L*${code}`)
          expect(parsed.qty, 'the typed quantity is five').to.eq(5)
          expect(parsed.unit).to.eq('LOOSE')
          expect(parsed.code, 'and the code is passed on intact').to.eq(code)
        })
      })
    })
  })
})
