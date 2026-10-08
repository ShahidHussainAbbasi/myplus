#!/usr/bin/env node
/**
 * Builds the "One partner, customer AND supplier" manual test page from the capture run.
 *
 *   inputs   cypress/guide-out/dual-role/*.json   one file per case, written by cypress/e2e/docs/dual-role-guide.cy.js
 *            cypress/screenshots/**                the pictures that run took (DRG-<case>-<step>-<name>.png)
 *   output   <outDir>/index.html + <outDir>/img/*.png
 *
 * Usage:  node docs/guides/build-dual-role-guide.js <outDir>
 *
 * The page itself is built by guide-page.js (shared with the other step-by-step guides).
 */
const path = require('path')
const { build, ROOT } = require('./guide-page')

build({
  caseDir: 'cypress/guide-out/dual-role',
  shotPrefix: 'DRG-',
  outDir: process.argv[2] || path.join(ROOT, 'target', 'dual-role-guide'),
  title: 'Dual-Role Partner Tests',
  heading: 'One partner, customer AND supplier — manual test cases',
  design: 'microservices/docs/party-dual-role-customer-supplier-analysis.md',
  storageKey: 'drg-ticks',
  slices: {
    'DR-1': 'Matching — the same partner, never two',
    'DR-2': 'The second role from the screen',
    'DR-3': 'Position — what they owe and are owed',
    'DR-4': 'Set-off — never cash, reversible',
    'DR-5': 'The payment screens mention the other side',
  },
  beforeYouStart: [
    '<strong>DR-1 and DR-2</strong> cases run on <code>owner.business@myplus.com</code> and create and delete their own records.',
    `<strong>DR-3 to DR-5</strong> move money, so they run on <code>owner.lifecycle@myplus.com</code>, the sacrificial business. Its cutover date is
        <strong>2026-09-01</strong> and stays there: opening balances stand on it, and the lock follows the books.`,
    'All passwords <code>Demo@2025!</code>. Names in the steps carry a run number — use your own, any unique suffix will do.',
    'Your ticks are kept in this browser only.',
  ],
})
