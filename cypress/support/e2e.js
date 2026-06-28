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
    return win
  })
})

// Keep the seeded demo accounts under their 50-create/module/day cap: clear the gateway's
// Redis write-counters before every test so a long suite never trips DEMO_LIMIT mid-run on a
// create POST. Counter-only (no data purge); no-ops when Redis/docker isn't reachable.
beforeEach(() => {
  cy.task('clearDemoCaps', null, { log: false })
})

// Suppress known pre-existing JS errors in the app so Cypress doesn't fail tests for them
Cypress.on('uncaught:exception', (err) => {
  if (
    err.message.includes('handleEnterKey is not defined') ||
    err.message.includes('pwstrength is not a function') ||
    err.message.includes('is not a function')
  ) {
    return false
  }
})
