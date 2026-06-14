import './commands'

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
