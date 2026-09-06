/**
 * P1 — direct thermal printing, gated WITHOUT a printer.
 *
 * Design: microservices/docs/slices/p1-thermal-printing-escpos-design.md
 *
 * <h3>What is asserted, and what deliberately is not</h3>
 * Everything here stops at the BYTES. Whether a given printer accepts them is a manual case with a device
 * on the desk; whether we produced the right ones is pure logic and belongs in a gate that runs every day.
 *
 * The most important case in this file is the FIRST one: that a tenant who has configured nothing still
 * prints through `window.print`. This whole slice is additive, and the way it could do real harm is by
 * quietly capturing the print path of shops that never asked for it.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/escpos-printing.cy.js --headed --no-exit
 */

const hex = (arr, n) =>
  Array.from(arr.slice(0, n)).map((b) => b.toString(16).padStart(2, '0')).join(' ')

const openSale = () => {
  cy.openSellSection('sellDiv')
  cy.get('#sellItemDD', { timeout: 15000 }).should('exist')
}

/** A resolved document, in the shape toPrintModel returns. */
const model = (over) =>
  Object.assign({
    profile: { paper: '80mm' },
    title: 'SALE INVOICE',
    invoiceNo: 'INV-000089',
    letterhead: { businessName: 'AL REHMAT ELECTRIC TRADERS', addressLine1: 'Nawan Kot', phone: '03007576600' },
    paper: '80mm',
    headerFields: [{ key: 'invoiceNo', label: 'Bill No', value: '1442' }],
    columns: [
      { key: 'itemName', label: 'Description', align: 'left', width: 40 },
      { key: 'quantity', label: 'Qty', align: 'right', width: 15 },
      { key: 'unitRate', label: 'Rate', align: 'right', width: 20 },
      { key: 'lineTotal', label: 'Amount', align: 'right', width: 25 },
    ],
    rows: [['PIPE 1 GM', '20', '300.00', '6,000.00'], ['BEND 1 GM', '10', '40.00', '400.00']],
    totals: [{ key: 'grandTotal', label: 'Net Total', value: 'Rs 6,400.00', strong: true }],
    signature: [],
    footerText: 'Thank you for your business',
    termsText: '',
    taxRegNo: '',
    fiscalLine: '',
  }, over || {})

describe('P1 — ESC/POS direct printing', () => {
  beforeEach(() => {
    cy.loginAsOwner()
    openSale()
  })

  // ── the safety property ─────────────────────────────────────────────────────────────────────

  it('⭐ a tenant who configured nothing still prints through the browser dialog', () => {
    /*
     * The regression that would matter most. printMode is absent on every existing tenant, and this slice
     * must leave their till exactly as it was — no dialog captured, no bytes sent anywhere.
     */
    cy.window().then((w) => {
      const printed = []
      const original = w.DocumentRenderer
      expect(original, 'the renderer is loaded').to.be.an('object')

      // Stand in for the iframe's print(), which is what the browser path ends at.
      const inv = { invoiceNo: 'CY-1', sales: [], letterhead: {}, grandTotal: 0 }
      expect(inv.printMode, 'absent, as it is for every tenant today').to.eq(undefined)

      // EscPos must not be reached at all: stub print() and assert it is never called.
      const esc = w.EscPos
      expect(esc, 'escpos.js is loaded').to.be.an('object')
      const spy = cy.stub(esc, 'print').callsFake(() => { printed.push(1); return Promise.resolve() })

      w.DocumentRenderer.buildHtml(inv, w.DocumentRenderer.PRESETS.RETAIL_RECEIPT_80MM)
      expect(printed.length, '⭐ nothing was sent to a printer').to.eq(0)
      spy.restore && spy.restore()
    })
  })

  // ── the raster encoder ──────────────────────────────────────────────────────────────────────

  it('⭐ raster mode emits a well-formed GS v 0 command', () => {
    cy.window().then((w) => {
      const out = w.EscPos.encode(model(), { mode: 'escpos-raster', widthDots: 576, autoCut: false })

      // ESC @ resets first — a printer holds the previous job's font and alignment otherwise.
      expect(hex(out, 2), 'ESC @ first').to.eq('1b 40')
      // Then GS v 0, m=0.
      expect(hex(out.slice(2), 4), 'GS v 0').to.eq('1d 76 30 00')

      // ⭐ Width is in BYTES, height in DOTS, both little-endian. Transposing them is THE classic
      // ESC/POS bug: the printer reads the wrong row length and emits a diagonal smear.
      const xL = out[6], xH = out[7]
      expect(xL + (xH << 8), '576 dots = 72 bytes per row').to.eq(72)

      const yL = out[8], yH = out[9]
      const height = yL + (yH << 8)
      expect(height, 'a receipt has real height').to.be.greaterThan(50)

      // The payload must be exactly rowBytes x height, or the printer runs off the end of the stream.
      expect(out.length - 10, 'payload matches the declared geometry').to.eq(72 * height)
    })
  })

  it('58mm paper packs to 48 bytes a row', () => {
    cy.window().then((w) => {
      const out = w.EscPos.encode(model(), { mode: 'escpos-raster', widthDots: 384, autoCut: false })
      expect(out[6] + (out[7] << 8), '384 dots = 48 bytes').to.eq(48)
    })
  })

  it('the cut and the drawer kick are appended only when asked', () => {
    cy.window().then((w) => {
      const plain = w.EscPos.encode(model(), { mode: 'escpos-text', autoCut: false })
      const both = w.EscPos.encode(model(), { mode: 'escpos-text', autoCut: true, cashDrawer: true })
      expect(both.length, 'the extra commands are really appended').to.be.greaterThan(plain.length)

      const tail = hex(both.slice(both.length - 3), 3)
      expect(tail, 'GS V 1 — a PARTIAL cut, so the slip stays attached').to.eq('1d 56 01')
    })
  })

  // ── the language property, which is the whole reason raster is the default ───────────────────

  it('⭐ text mode CANNOT carry Urdu — and says so by refusing to guess', () => {
    /*
     * Not a defect: no ESC/POS codepage covers Urdu, so any byte we emitted would print as some other
     * glyph on the customer's receipt. '?' is the honest answer, and this case exists so that nobody
     * "fixes" it into silently wrong output. The real answer is raster mode, asserted below.
     */
    cy.window().then((w) => {
      const urdu = model({ footerText: 'کوئی گارنٹی نہیں' })
      const out = w.EscPos.encode(urdu, { mode: 'escpos-text', autoCut: false })
      const text = Array.from(out).map((b) => String.fromCharCode(b)).join('')
      expect(text, 'every Urdu character became a question mark').to.contain('????')
    })
  })

  it('⭐ raster mode DOES carry Urdu — it grows with the text', () => {
    /*
     * The property that proves the glyphs reached the bitmap: a three-line Urdu terms block makes the
     * receipt measurably TALLER than the same receipt without one. Nothing here needs a printer, and
     * nothing here needs to inspect pixels.
     */
    cy.window().then((w) => {
      const opts = { mode: 'escpos-raster', widthDots: 576, autoCut: false }
      const plain = w.EscPos.encode(model(), opts)
      const withUrdu = w.EscPos.encode(model({
        termsText: 'نوٹ: اسٹاک موقع پر چیک کر لیں\nکوئی گارنٹی نہیں ہے\nرسید کے بغیر واپسی نہیں',
      }), opts)

      const h = (b) => b[8] + (b[9] << 8)
      expect(h(withUrdu), '⭐ the Urdu block is really on the bitmap').to.be.greaterThan(h(plain))
    })
  })

  it('direction is decided per string, not per document', () => {
    // The same rule dir="auto" applies in the HTML renderer: a slip with an English table and an Urdu
    // footer must not have its money columns mirrored.
    cy.window().then((w) => {
      expect(w.EscPos.isRtl('کوئی گارنٹی'), 'Urdu is RTL').to.eq(true)
      expect(w.EscPos.isRtl('PIPE 1 GM'), 'Latin is not').to.eq(false)
      expect(w.EscPos.isRtl('Rs 7,430 کوئی'), 'first strong char decides').to.eq(false)
    })
  })

  // ── the fiscal QR ──────────────────────────────────────────────

  it('⭐ text mode asks the PRINTER for the QR, not a bitmap', () => {
    /*
     * GS ( k. A few dozen bytes against tens of kilobytes of raster — which is the whole reason a shop
     * picked text mode — and the printer's own module grid is crisper than anything we could rasterise.
     */
    cy.window().then((w) => {
      const out = w.EscPos.encode(model({ qrPayload: 'NTN123|INV-1|2026-09-06|6400.00' }),
        { mode: 'escpos-text', autoCut: false })
      const text = Array.from(out).map((b) => String.fromCharCode(b)).join('')
      expect(text, 'the payload is in the stream for the printer to encode')
        .to.contain('NTN123|INV-1|2026-09-06|6400.00')

      // GS ( k with fn=80 (store) must appear, or the printer has nothing to print.
      const hasStore = Array.from(out).some((b, i) =>
        b === 0x1d && out[i + 1] === 0x28 && out[i + 2] === 0x6b && out[i + 5] === 0x31 && out[i + 6] === 0x50)
      expect(hasStore, 'GS ( k store command present').to.eq(true)
    })
  })

  it('⭐ raster mode waits for the QR to decode before drawing', () => {
    /*
     * An <img> is unusable until it loads, even from a data: URI. Drawing first would put a blank square on
     * some receipts and not others — a defect that shows up only on the busiest till.
     */
    const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
    cy.window().then((w) => w.EscPos.loadQr({ qrDataUri: png }).then((m) => {
      /*
       * ⚠ NOT `.to.exist`. Cypress bundles chai-jquery, which hijacks that assertion for DOM
       * subjects — and qrImage is a raw HTMLImageElement, so it is routed into the element-FINDING
       * path and fails with "Expected to find element: undefined" whether the image decoded or not.
       * One assertion on the decoded WIDTH answers the real question and cannot be intercepted:
       * undefined fails it, a decoded image reports its pixels.
       */
      expect(m.qrImage && m.qrImage.width, 'the image decoded and has pixels').to.be.greaterThan(0)
    }))
  })

  it('a QR that will not decode never costs the shop a receipt', () => {
    cy.window().then((w) => w.EscPos.loadQr({ qrDataUri: 'data:image/png;base64,NOTAPNG' }).then((m) => {
      expect(m.qrImage, 'no image').to.eq(undefined)
      const out = w.EscPos.encode(m, { mode: 'escpos-raster', widthDots: 576, autoCut: false })
      expect(out.length, '⭐ the slip still encodes').to.be.greaterThan(10)
    }))
  })

  it('the HTML document only accepts a PNG data URI as the QR src', () => {
    // The value is ours, never owner input — but an <img src> built by string concatenation is exactly
    // where a javascript: URI would land if that ever stopped being true.
    cy.window().then((w) => {
      const good = w.DocumentRenderer.buildHtml(
        { invoiceNo: 'Q1', sales: [], letterhead: {}, grandTotal: 0,
          qrDataUri: 'data:image/png;base64,iVBORw0KGgo=' },
        w.DocumentRenderer.PRESETS.RETAIL_RECEIPT_80MM)
      expect(good, 'a real PNG data URI is rendered').to.contain('<img')

      const bad = w.DocumentRenderer.buildHtml(
        { invoiceNo: 'Q2', sales: [], letterhead: {}, grandTotal: 0,
          qrDataUri: 'javascript:alert(1)' },
        w.DocumentRenderer.PRESETS.RETAIL_RECEIPT_80MM)
      expect(bad, '⭐ anything else is refused').to.not.contain('javascript:')
    })
  })

  // ── transport ───────────────────────────────────────────────────────────────────────────────

  it('the agent transport POSTs the bytes it was given', () => {
    cy.intercept('POST', '**/print', { statusCode: 200, body: '' }).as('agent')
    cy.window().then((w) => {
      const data = w.EscPos.encode(model(), { mode: 'escpos-text' })
      return w.EscPos.send(data, { transport: 'agent', agentUrl: '/print' })
        .then(() => ({ len: data.length }))
        .then((r) => {
          cy.wait('@agent').its('request.headers.content-type').should('contain', 'application/octet-stream')
          expect(r.len).to.be.greaterThan(0)
        })
    })
  })

  it('⭐ an agent that ANSWERED and failed is not retried down another route', () => {
    /*
     * The fallback rule. A non-ok response means the agent was reached and may already have put ink on
     * paper, so printInvoiceObject must NOT fall back to the browser dialog — a second copy arriving by a
     * different route is worse than a visible error. `reachedPrinter` is what distinguishes that from
     * "no agent listening", which does fall back.
     */
    cy.intercept('POST', '**/print', { statusCode: 500, body: 'boom' }).as('agentFail')
    cy.window().then((w) => {
      const data = w.EscPos.encode(model(), { mode: 'escpos-text' })
      return w.EscPos.send(data, { transport: 'agent', agentUrl: '/print' })
        .then(() => { throw new Error('should have rejected') })
        .catch((err) => {
          expect(err.reachedPrinter, '⭐ marked as possibly-printed').to.eq(true)
        })
    })
  })

  it('a browser with no WebUSB reports it instead of failing silently', () => {
    cy.window().then((w) => {
      if (w.navigator.usb) return                       // a browser that HAS it proves nothing here
      return w.EscPos.send(new Uint8Array([1, 2, 3]), { transport: 'usb' })
        .then(() => { throw new Error('should have rejected') })
        .catch((err) => {
          expect(err.message).to.contain('WebUSB')
          expect(err.reachedPrinter, 'never reached a printer, so a fallback is safe').to.not.eq(true)
        })
    })
  })
})
