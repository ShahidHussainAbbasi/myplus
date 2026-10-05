#!/usr/bin/env node
/**
 * Builds the "What a purchase does to the selling price" manual test page (PR-1) from the capture run.
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
  shotPrefix: ['P', 'Q'],
  outDir: process.argv[2] || path.join(ROOT, 'target', 'price-mode-guide'),
  title: 'Selling Price Tests',
  heading: 'Selling price from purchases — manual test cases',
  design: 'microservices/docs/selling-price-per-purchase-analysis.md',
  storageKey: 'prg-ticks',
  coverMap: false,
  slices: { 'PR-1': 'Latest or Keep, and the price history', 'PR-2': 'The markup rule: suggest or set a price from the cost' },
  beforeYouStart: [
    'Cases that save a purchase (<strong>P2, P3, Q2, Q4, Q5</strong>) move money, so they run on <code>owner.lifecycle@myplus.com</code>, the sacrificial business, and <strong>void</strong> their bill as cleanup.',
    'The <strong>Q</strong> cases (the markup rule) put every pricing setting they touch back as it was. The worked figure throughout: <strong>14.5% on a cost of 210 = 240.45</strong>.',
    '<strong>P5</strong> runs on <code>owner.business@myplus.com</code> with its members <code>admin.business@</code> and <code>user.business@</code>, and saves nothing.',
    'The products and the supplier each case buys are made beforehand through the same requests the Product and Supplier forms send — make your own with any name.',
    'A <strong>void does not undo a price change</strong>: the price is a decision, and its history keeps it. Change the price on the product if needed.',
    'All passwords <code>Demo@2025!</code>. Your ticks are kept in this browser only.',
  ],
})
