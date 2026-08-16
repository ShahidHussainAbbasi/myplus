import './commands'

// Slow-motion for watching a HEADED run (so the navigation is visible). Enable per-run with
//   npx cypress run --headed --browser chrome --env slowMo=1200 --spec "<spec>"
// slowMo = ms paused AFTER each action command (visit/click/type/select/...). Off by default (0) so the
// regression suite stays fast. Only action commands are slowed — assertions/get keep their retry-ability.
const SLOW_MO = Number(Cypress.env('slowMo') || 0)
if (SLOW_MO > 0) {
  const ACTIONS = ['visit', 'click', 'dblclick', 'type', 'clear', 'select', 'trigger', 'check', 'uncheck', 'focus', 'blur', 'scrollIntoView', 'submit']
  for (const name of ACTIONS) {
    Cypress.Commands.overwrite(name, (originalFn, ...args) => {
      const result = originalFn(...args)
      return new Cypress.Promise((resolve) => setTimeout(() => resolve(result), SLOW_MO))
    })
  }
}

// Make CSS animations/transitions instant during tests. The dashboard sections (.formDiv) fade in via a
// `sectionIn` keyframe with `animation-fill-mode: both`; when a section is re-shown the keyframe can leave it
// stuck at its start state (opacity:0), so `should('be.visible')` times out even though the section IS shown
// (display:block). Zeroing the durations makes a shown element settle at its resting opacity immediately —
// deterministic visibility without changing production UX.
Cypress.Commands.overwrite('visit', (originalFn, ...args) => {
  return originalFn(...args).then((win) => {
    const doc = win && win.document ? win.document : null
    if (doc && doc.head && !doc.getElementById('cy-no-animations')) {
      const style = doc.createElement('style')
      style.id = 'cy-no-animations'
      style.innerHTML = '*,*::before,*::after{animation-duration:0s !important;animation-delay:0s !important;' +
        'transition-duration:0s !important;transition-delay:0s !important;}'
      doc.head.appendChild(style)
    }

    // Neutralise the post-sale AUTO-PRINT, which otherwise FREEZES THE WHOLE RUN.
    //
    // A successful `addSell` calls `printReceipt(invoiceNo)` (main.js) whenever `pos.receipt.autoPrint` is
    // on — it is on by default. That renders the receipt into a hidden iframe and calls
    // `frame.contentWindow.print()` (receipt.js). **`window.print()` is synchronous and blocking**: it
    // opens the browser's print dialog and halts the main thread until a human dismisses it, and in an
    // automated browser nobody ever does.
    //
    // The symptom was brutal to read, which is why it cost so long: the sale SUCCEEDS first (the invoice is
    // written — INV-000220 landed mid-freeze), so the server looks healthy; then the event loop stops, so
    // **no Cypress timeout can fire either** and the spec neither fails nor finishes. No error, no
    // screenshot, just silence — `pos-quickpick`, `sell-edit` and `pos-sale-endtoend` each hung 12+ minutes.
    // `sell.cy.js` passes because its cases stop short of completing a sale.
    //
    // This is NOT a product defect: auto-printing a receipt is exactly what a till should do. So it is
    // neutralised HERE rather than changed in the app. Stubbed as a no-op FUNCTION because three specs
    // assert `typeof printReceipt === 'function'` (they check the script is loaded, never that it prints).
    try { win.print = function () {} } catch (e) { /* some browsers make print non-writable */ }
    win.printReceipt = function () {}

    return win
  })
})

// Keep the seeded demo accounts under their 50-create/module/day cap: clear the gateway's
// Redis write-counters before every test so a long suite never trips DEMO_LIMIT mid-run on a
// create POST. Counter-only (no data purge); no-ops when Redis/docker isn't reachable.
beforeEach(() => {
  cy.task('clearDemoCaps', null, { log: false })
})

// Suppress known pre-existing JS errors in the app so Cypress doesn't fail tests for them.
//
// ⚠ THE BARE `is not a function` CATCH-ALL WAS REMOVED (2026-08-16). It matched the most common shape of
// TypeError there is, so it suppressed real defects in OUR OWN code — the exact opposite of the intent
// stated in the jspdf note below, which deliberately matches on a third-party file so that "a real error
// in our own code still fails the test".
//
// It was removed while hunting the wedge in `pos-quickpick` / `sell-edit` / `pos-sale-endtoend`, on the
// theory that an uncaught throw was being swallowed. **That theory was WRONG and the removal did not fix
// them** — with the catch-all gone the specs still hang without failing, which is itself the evidence that
// no exception is being thrown: the page's main thread is blocked, so no Cypress timeout can fire either.
// The removal stands on its own merits regardless; it is not the cure for that hang.
//
// Keep suppressions SPECIFIC. Each one below names the symbol it tolerates, so a new error still fails.
Cypress.on('uncaught:exception', (err) => {
  if (
    err.message.includes('handleEnterKey is not defined') ||
    err.message.includes('pwstrength is not a function')
  ) {
    return false
  }
  // jspdf's autotable plugin throws while initialising on dashboard load. Third-party, pre-existing,
  // and unrelated to anything under test — but it only surfaces on SOME loads, so it turns whichever
  // spec happens to hit it red. Matched on the file rather than the message so the suppression stays
  // narrow: a real error in our own code still fails the test.
  if (err.stack && err.stack.includes('jspdf.plugin.autotable')) {
    return false
  }
})
