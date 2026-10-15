#!/usr/bin/env node
/**
 * Builds the "Selling price from purchases" manual test page (PR-1, PR-2, PR-3a/b/c, PR-4) from the capture run.
 *
 *   inputs   cypress/guide-out/price-mode/*.json + img/   written by cypress/e2e/docs/price-mode-guide.cy.js
 *   output   <outDir>/index.html + <outDir>/img/*.png
 *
 * Usage:  node docs/guides/build-price-mode-guide.js <outDir>
 */
const path = require('path')
const { build, ROOT } = require('./guide-page')

build({
  caseDir: 'cypress/guide-out/price-mode',
  shotPrefix: ['P', 'Q', 'R', 'X', 'S', 'T'],
  outDir: process.argv[2] || path.join(ROOT, 'target', 'price-mode-guide'),
  title: 'Selling Price Tests',
  heading: 'Selling price from purchases — manual test cases',
  design: 'microservices/docs/selling-price-per-purchase-analysis.md',
  storageKey: 'prg-ticks',
  coverMap: false,
  slices: {
    'PR-1': 'Latest or Keep, and the price history',
    'PR-2': 'The markup rule: suggest or set a price from the cost',
    'PR-3a': 'One product on two lines: batches by line',
    'PR-3b': 'Per batch: a purchase prices its own stock',
    'PR-3c': 'Per batch: the sale is priced from the batches it takes',
    'PR-4': 'Approval: the owner approves a price before customers pay it',
  },
  beforeYouStart: [
    'Cases that save a purchase (<strong>P2, P3, P6, Q2, Q4, Q5</strong>) move money, so they run on <code>owner.lifecycle@myplus.com</code>, the sacrificial business, and <strong>void</strong> their bill as cleanup.',
    'The <strong>Q</strong> cases (the markup rule) put every pricing setting they touch back as it was. The worked figure throughout: <strong>14.5% on a cost of 210 = 240.45</strong>.',
    '<strong>P5</strong> runs on <code>owner.business@myplus.com</code> with its members <code>admin.business@</code> and <code>user.business@</code>, and saves nothing.',
    'The products and the supplier each case buys are made beforehand through the same requests the Product and Supplier forms send — make your own with any name.',
    'A <strong>void does not undo a price change</strong>: the price is a decision, and its history keeps it. Change the price on the product if needed.',
    'The <strong>R, X and S</strong> cases (each purchase sells at its own price) also run on <code>owner.lifecycle@</code>: every bill and invoice they make is <strong>voided on screen</strong>, and the purchase mode is put back to Latest. The worked figure: <strong>7 bought to sell at 200, then 10 at 250 — a sale of 10 is 7 × 200 + 3 × 250 = 2150.00</strong>.',
    'Steps marked <em>Through the screen’s own request</em> read the invoice’s batches from the data the <strong>Print</strong> button loads: which batches a line used is recorded on the invoice, but this business’s receipt layout does not print them.',
    'The <strong>T</strong> cases (Approval) run on <code>owner.lifecycle@</code> too: each bill is voided on screen, a proposal left waiting is rejected on screen, and the markup rule is put back to Suggest. <strong>T5</strong> runs on <code>owner.business@</code>’s members and saves nothing.',
    'All passwords <code>Demo@2025!</code>. Your ticks are kept in this browser only.',
  ],
})
